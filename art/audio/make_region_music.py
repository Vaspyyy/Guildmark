"""Guildmark region music: exploration themes for the wild and far lands, composed in code and rendered
with FluidSynth through the toolkit in make_music.py.

usage (from the repo root):
    python3 -B art/audio/make_region_music.py src/main/resources/assets/guildmark --work DIR
        [--sf2 FluidR3_GM.sf2] [--wav DIR] [--only marsh]

Minecraft plays one of these once in place of the overworld music while the player is in a region of that
kind, then waits minutes before the next, so each is a standalone piece with a soft opening and a real
ending, not a loop. They are calmer than the boss tracks: each is mixed like them (dry FluidSynth stems,
per-stem gain and reverb send into a synthetic hall, gentle glue compression), then mastered to about
-17 LUFS integrated with the true peak kept under -1 dBTP.
"""
import argparse
import os
import sys
import warnings
from itertools import product

import numpy as np
from scipy import signal
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_music import (SF2_DEFAULT, CHORDS, P, Song, export_stem, in_range, mix, pcs, render, roll,  # noqa: E402
                        stereo_limit, swell, voice_lead)
from sfxkit import SR, db, eq, hp, lp, rng_for, room_ir, write_ogg, write_wav  # noqa: E402

warnings.filterwarnings("ignore", category=wavfile.WavFileWarning)  # FluidSynth's float WAVs carry a LIST chunk

LUFS_TARGET = -17.0
TRUE_PEAK_MAX = -1.8  # before encoding; Vorbis adds a few tenths, and the files must stay under -1 dBTP

# chord spellings beyond make_music's (root, third, fifth, then the added note voice_lead tries to keep)
REGION_CHORDS = {
    "Dm9": "D F A E", "Bbmaj7": "Bb D F A", "Ebmaj7": "Eb G Bb D", "Em7b5": "E G Bb D", "A7": "A C# E G",
    "D5": "D A", "Gm7": "G Bb D F", "Fmaj7": "F A C E",
}
for _k, _v in REGION_CHORDS.items():
    assert _k not in CHORDS or CHORDS[_k] == _v, _k
    CHORDS[_k] = _v


# ------------------------------------------------------------------ mastering
def lufs(x):
    """integrated loudness of a stereo signal (ITU-R BS.1770: K-weighting, 400 ms blocks, both gates)"""
    z = 0.0
    n, step = int(0.4 * SR), int(0.1 * SR)
    for c in x:
        k = eq(eq(c, "highshelf", 1681.974450955533, 0.7071752369554196, 3.999843853973347),
               "hp", 38.13547087602444, 0.5003270373238773)
        cs = np.concatenate([[0.0], np.cumsum(k ** 2)])
        st = np.arange(0, len(k) - n + 1, step)
        z = z + (cs[st + n] - cs[st]) / n
    lv = -0.691 + 10 * np.log10(z + 1e-20)
    gated = z[lv > -70.0]
    rel = -0.691 + 10 * np.log10(gated.mean()) - 10.0
    return -0.691 + 10 * np.log10(z[(lv > -70.0) & (lv > rel)].mean())


def true_peak(x):
    """dBTP, from 4x oversampling"""
    return 20 * np.log10(np.max(np.abs(signal.resample_poly(x, 4, 1, axis=1))) + 1e-12)


def master(x, target=LUFS_TARGET, ceiling=TRUE_PEAK_MAX):
    """bring the mix to the target loudness, then limit only if the true peak would pass the ceiling"""
    x = x * db(target - lufs(x))
    for _ in range(6):
        if true_peak(x) <= ceiling:
            break
        x = stereo_limit(x, ceiling - 0.6)
        x = x * db(target - lufs(x))
    return x


# ------------------------------------------------------------------ per-stem processing
def delay(y, seconds):
    k = int(round(seconds * SR))
    return np.concatenate([np.zeros(k), y[:len(y) - k]])


def stem_fx(y, ops):
    """channel processing before the mix: ('hp', f) ('lp', f) ('eq', kind, f, q, gain)
    ('echo', [(seconds, gain, side), ...], lowpass_hz) where side -1 is left, 1 right, 0 both"""
    y = y.copy()
    for op in ops:
        if op[0] == "hp":
            y = np.vstack([hp(c, op[1]) for c in y])
        elif op[0] == "lp":
            y = np.vstack([lp(c, op[1]) for c in y])
        elif op[0] == "eq":
            y = np.vstack([eq(c, *op[1:]) for c in y])
        elif op[0] == "echo":
            src = lp(y.mean(axis=0), op[2])
            out = y.copy()
            for t, g, side in op[1]:
                d = delay(src, t) * g
                out[0] += d * (1.0 if side <= 0 else 0.35)
                out[1] += d * (1.0 if side >= 0 else 0.35)
            y = out
    return y


# ------------------------------------------------------------------ shared writing helpers
def drips(part, song, bars, chord_of, lo, hi, vel, seed, every=(1.5, 4.0), double=0.3, dur=1.5):
    """sparse, irregular single notes from the harmony, some with a softer echo a step below"""
    rng = rng_for(song.name, part.name, seed)
    beat = song.b(bars[0]) + rng.uniform(0.0, every[0])
    end = song.b(bars[-1]) + song.bpb
    while beat < end:
        ch = chord_of(beat)
        cands = [m for m in range(lo, hi + 1) if m % 12 in pcs(ch)]
        q = int(rng.choice(cands))
        v = vel(beat) if callable(vel) else vel
        part.n(beat, dur, q, v + rng.uniform(-6, 4))
        if rng.random() < double:
            below = [m for m in cands if m < q]
            if below:
                part.n(beat + rng.choice([0.25, 0.375, 0.5]), dur, below[-1], v - 14)
        beat += rng.uniform(*every)
        beat = round(beat * 8) / 8


def voicings(chords, ranges, bass=None, start=None):
    """voice_lead with a cleaner ear for slow, exposed harmony: one note per voice (ranges low to high),
    moving as little as possible, covering root, third and fifth before any colour note, and never a minor
    second or minor ninth between two voices or against the bass note given for that chord"""
    out = []
    prev = start or [(lo + hi) // 2 for lo, hi in ranges]
    for i, ch in enumerate(chords):
        pc = pcs(ch)
        cand = [[m for m in range(lo, hi + 1) if m % 12 in pc] for lo, hi in ranges]
        b = P(bass[i]) if bass is not None else None
        best, bestc = None, 1e9
        for combo in product(*cand):
            if any(combo[k] >= combo[k + 1] for k in range(len(combo) - 1)):
                continue
            cost = sum(abs(x - y) for x, y in zip(combo, prev))
            have = [m % 12 for m in combo]
            for k, w in enumerate((12, 10, 6, 5)):
                if k < len(pc) and pc[k] not in have:
                    cost += w
            if len(pc) > 2 and have.count(pc[1]) > 1:
                cost += 4
            notes = list(combo) + ([b] if b is not None else [])
            for x in range(len(notes)):
                for y in range(x + 1, len(notes)):
                    iv = abs(notes[x] - notes[y])
                    if iv in (1, 13):
                        cost += 30
                    elif iv == 25:
                        cost += 6
                    elif iv == 2 and min(notes[x], notes[y]) < 55:
                        cost += 6
            if cost < bestc:
                best, bestc = combo, cost
        out.append(list(best))
        prev = list(best)
    return out


def place(part, song, bars, vcs, idxs, vel, legato=1.015, beats=None):
    """put voices [idxs] of a per-bar voicing list on a part as held chords"""
    for bar, vc in zip(bars, vcs):
        v = vel(bar) if callable(vel) else vel
        part.chord(song.b(bar), (beats or song.bpb) * legato, [vc[i] for i in idxs if i < len(vc)], v)


# ================================================================== the marsh
def compose_marsh():
    """A rotting marsh under green murk. D, wavering between dorian (the B natural) and phrygian (the Eb).
    A lonely five-note motif rises a minor third, slips back a step and falls a fifth, and every time it
    returns it starts a step lower: the bass clarinet carries it down from A to A an octave below while
    a clarinet answers higher up and drifts the other way. Bowed glass and a contrabass drone hold the
    murk, harp, pizzicato and celesta drip in irregular time. The strings take the motif at the
    climax over a falling bass and peak on the Eb chord, the phrygian flat second, and the piece sinks
    back to the drone through that same Eb."""
    s = Song("region_marsh", bpm=56, bars=34, tail=6.5)
    prog = (["D5", "D5", "Dm9", "Dm9", "C", "Am", "F", "Gm", "Bb", "Ebmaj7", "Dm"]
            + ["F", "Am", "Bbmaj7", "Gm", "Em7b5", "Bb", "Eb", "A"]
            + ["Bb", "F", "Gm", "Dm", "Eb", "Ebmaj7", "A7", "A"]
            + ["Dm9", "Dm", "Dm9", "C", "Ebmaj7", "Dm", "Dm9"])
    bass = (["D2"] * 11 + ["C2", "C2", "Bb1", "Bb1", "E2", "Bb1", "Eb2", "A1"]
            + ["Bb1", "A1", "G1", "F1", "Eb1", "Eb1", "A1", "A1"] + ["D2"] * 7)
    assert len(prog) == 34 and len(bass) == 34
    s.chord_at = lambda beat: prog[min(len(prog) - 1, int(beat // 4))]
    INTRO, A, B, C, D = range(1, 4), range(4, 12), range(12, 20), range(20, 28), range(28, 35)

    bcl = s.part("bcl", 71, "winds", pan=56, volume=104, hum=0.012, vjit=3, lag=0.02)
    cl = s.part("cl", 71, "winds", pan=76, volume=96, hum=0.012, vjit=3, lag=0.02)
    bsn = s.part("bsn", 70, "winds", pan=60, volume=84, hum=0.01, vjit=3)
    cb = s.part("cb", 43, "low", pan=70, volume=100, hum=0.01, vjit=3)
    vcl = s.part("vcl", 42, "low", pan=80, volume=96, hum=0.012, vjit=3, lag=0.015)
    glass = s.part("glass", 92, "pads", pan=44, volume=96, hum=0.02, vjit=3)
    glass2 = s.part("glass2", 92, "pads", pan=84, volume=90, hum=0.02, vjit=3)
    strs = s.part("strs", 49, "strings", pan=58, volume=96, hum=0.02, vjit=3)
    vln = s.part("vln", 49, "strings", pan=36, volume=100, hum=0.014, vjit=3, lag=0.012)
    vla = s.part("vla", 49, "strings", pan=66, volume=96, hum=0.014, vjit=3, lag=0.014)
    trem = s.part("trem", 44, "strings", pan=72, volume=84, hum=0.01, vjit=3)
    ohh = s.part("ohh", 53, "choir", pan=50, volume=92, hum=0.02, vjit=3)
    harp = s.part("harp", 46, "drips", pan=30, volume=100, hum=0.006, vjit=4)
    pizz = s.part("pizz", 45, "drips", pan=86, volume=96, hum=0.006, vjit=4)
    cel = s.part("cel", 8, "drips", pan=64, volume=84, hum=0.004, vjit=4)
    timp = s.part("timp", 47, "perc", pan=58, volume=100, hum=0.004, vjit=3)

    inten = np.interp(np.arange(1, 35), [1, 3, 4, 11, 12, 19, 20, 24, 25, 27, 28, 34],
                      [.20, .30, .36, .42, .46, .62, .70, .92, .96, .80, .40, .22])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[int(bar) - 1]

    def Vb(lo, hi):  # by beat
        return lambda beat: lo + (hi - lo) * inten[min(33, int(beat // 4))]

    # ---- the drone: contrabass on the bass line, a low bassoon colouring the pedal now and then
    for bar in range(1, 35):
        cb.n(s.b(bar), 4.06, bass[bar - 1], V(46, 82)(bar))
    cb.expr([(0, 40), (s.b(3), 92), (s.b(12), 96), (s.b(20), 108), (s.b(25), 116), (s.b(28), 100),
             (s.b(33), 92), (s.b(35) - 0.1, 50)])
    bsn.line(s.b(2), "D2:8", 46, leg=1.0)
    bsn.expr([(s.b(2), 50), (s.b(3), 96), (s.b(4) - 0.1, 60)])

    # ---- one voicing for every held layer, so glass, strings and choir never rub against each other
    PV = voicings(prog, [(50, 57), (55, 62), (59, 66), (62, 70), (66, 74)], bass=bass)
    ALL = list(range(1, 35))

    # ---- bowed glass: the murk, breathing in two-bar swells
    place(glass, s, ALL[:2], PV[:2], [1, 2], V(44, 74), legato=1.0)
    place(glass, s, ALL[2:], PV[2:], [0, 1, 2], V(44, 74))
    place(glass2, s, ALL[2:], PV[2:], [3, 4], lambda b: V(44, 74)(b) - 6)
    for p_ in (glass, glass2):
        swell(p_, s, ALL[:32], lo=64, hi=104, every=2)
        p_.expr([(s.b(33), 90), (s.b(35) - 0.1, 30)])

    # ---- choir: a distant hum, opening and closing; full oohs at the climax
    ohh.chord(s.b(1), 12.1, ["D3", "A3"], 40)
    ohh.expr([(0, 30), (s.b(2), 90), (s.b(4) - 0.1, 70)])
    place(ohh, s, list(C), PV[19:27], [0, 1, 2, 3], V(40, 70))
    swell(ohh, s, list(C), lo=60, hi=110, every=2)
    place(ohh, s, [33, 34], PV[32:34], [0, 1, 2], 44)
    ohh.expr([(s.b(33), 80), (s.b(35) - 0.1, 30)])

    # ---- the motif: bass clarinet sinking, clarinet answering higher and rising
    bcl.line(s.b(4), "A3:2 C4:1.5 B3:0.5 E3:4", 70, leg=1.0)
    cl.line(s.b(6), "E4:2 G4:1.5 F4:0.5 C4:3", 60, leg=1.0)
    bcl.line(s.b(8), "G3:2 Bb3:1.5 A3:0.5 D3:4", 72, leg=1.0)
    cl.line(s.b(10), "D4:2 F4:1.5 Eb4:0.5 A3:3", 62, leg=1.0)
    bcl.line(s.b(12), "F3:2 A3:1.5 G3:0.5 C3:4", 74, leg=1.0)
    cl.line(s.b(14), "F4:2 A4:1.5 G4:0.5 D4:3", 66, leg=1.0)
    bcl.line(s.b(16), "E3:2 G3:1.5 F3:0.5 Bb2:4", 76, leg=1.0)
    cl.line(s.b(18), "G4:2 Bb4:1.5 A4:0.5 E4:4", 72, leg=1.0)
    # under the climax the clarinets hold the inner harmony
    bcl.line(s.b(20), "F3:4 F3:4 D3:4 A2:4 G3:4 G3:4 E3:4 C#3:4", 60, leg=1.0)
    cl.line(s.b(20), "Bb3:4 C4:4 Bb3:4 D4:4 Bb3:4 Bb3:4 C#4:4 C#4:4", 56, leg=1.0)
    for p_, phrase in ((bcl, [(4, 6), (8, 10), (12, 14), (16, 18)]), (cl, [(6, 8), (10, 12), (14, 16), (18, 20)])):
        for b0, b1 in phrase:
            p_.expr([(s.b(b0), 84), (s.b(b0) + 2.5, 112), (s.b(b0 + 1) + 1, 100), (s.b(b1) - 0.3, 64)])
    for p_ in (bcl, cl):
        p_.expr([(s.b(20), 70), (s.b(24), 92), (s.b(28) - 0.1, 70)])
    # the ending: the motif from the bottom, a memory of its first form, the phrygian slip, home
    bcl.line(s.b(28), "D3:2 F3:1.5 E3:0.5 A2:4", 68, leg=1.0)
    cl.line(s.b(30), "A4:2 C5:1.5 B4:0.5 E4:4", 54, leg=1.0)
    bcl.line(s.b(32), "D3:2 F3:1.5 Eb3:0.5 A2:3.5 R:0.5 D3:8", 62, leg=1.0)
    bsn.line(s.b(34), "D2:5", 52, leg=1.0)
    bcl.expr([(s.b(28), 84), (s.b(28) + 2.5, 110), (s.b(30) - 0.3, 70), (s.b(32), 84), (s.b(32) + 2.5, 104),
              (s.b(34), 84), (s.b(35) + 1, 40)])
    cl.expr([(s.b(30), 70), (s.b(30) + 2.5, 100), (s.b(32) - 0.2, 60)])
    bsn.expr([(s.b(34), 70), (s.b(35) + 1, 40)])

    # ---- tremolo strings: unease growing under the development
    place(trem, s, list(B), PV[11:19], [2, 3], V(36, 64))
    trem.expr([(s.b(12), 50), (s.b(16), 80), (s.b(19), 100), (s.b(20) - 0.1, 112)])

    # ---- the climax: the motif in the strings, octaves over a falling bass
    climax = ("D5:2 F5:1.5 E5:0.5 A4:3 C5:1 Bb4:2 D5:1.5 C5:0.5 F4:3 A4:1 "
              "G4:1 Bb4:1 Eb5:2 D5:2 C5:1.5 Bb4:0.5 A4:2 G4:2 E4:4")
    vln.line(s.b(20), climax, 76, leg=1.02)
    vla.line(s.b(20), climax, 70, tr=-12, leg=1.02)
    for bar in C:  # the cellos sing the falling bass an octave above the contrabasses
        vcl.n(s.b(bar), 4.04, P(bass[bar - 1]) + 12, 66)
    for p_ in (vln, vla, vcl):
        p_.expr([(s.b(20), 70), (s.b(22), 96), (s.b(24), 112), (s.b(25), 124), (s.b(26), 104),
                 (s.b(28) - 0.1, 70)])
    place(strs, s, list(range(12, 28)), PV[11:27], [1, 2, 3], V(30, 66))
    swell(strs, s, list(range(12, 28)), lo=60, hi=108, every=2)

    # ---- drips: harp low, pizzicato, a celesta far off
    def chord_beat(beat):
        return prog[min(33, int(beat // 4))]
    drips(harp, s, list(range(1, 35)), chord_beat, 38, 57, Vb(40, 64), seed=1, every=(2.0, 5.0), double=0.35)
    drips(pizz, s, list(range(3, 28)), chord_beat, 38, 55, Vb(42, 70), seed=2, every=(2.5, 6.0), double=0.25,
          dur=1.0)
    drips(cel, s, list(range(2, 34)), chord_beat, 74, 86, Vb(30, 48), seed=3, every=(4.0, 9.0), double=0.4)
    harp.n(s.b(34, 3), 4, "A4", 50)
    cel.n(s.b(34, 3.5), 3, "E6", 34)

    # ---- timpani: a distant roll into the climax and under its peak, a last soft stroke
    roll(timp, s, s.b(19), 4, P("A1"), 30, 70, rate=0.25)
    roll(timp, s, s.b(23, 3), 2, P("Eb2"), 40, 80, rate=0.25)
    timp.n(s.b(24), 2, "Eb2", 76)
    roll(timp, s, s.b(27), 4, P("A1"), 34, 66, rate=0.25)
    timp.n(s.b(28), 2, "D2", 56)
    timp.n(s.b(34), 3, "D2", 40)
    return s


MARSH_MIX = {"winds": (4.0, 0.30), "low": (2.0, 0.20), "pads": (0.0, 0.42), "strings": (1.0, 0.34),
             "choir": (0.0, 0.46), "drips": (3.0, 0.52), "perc": (-2.0, 0.30)}


# ================================================================== driver
PIECES = {
    "marsh": dict(fn=compose_marsh, mix=MARSH_MIX, ir=dict(rt60=2.9, seed=201, predelay=0.03, hf_damp=0.7),
                  fade_out=3.0, comp=(-18.0, 1.6)),
}


def build(name, assets, sf2, work, wav_dir=None):
    cfg = PIECES[name]
    song = cfg["fn"]()
    os.makedirs(work, exist_ok=True)
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        mid = os.path.join(work, f"region_{name}_{stem}.mid")
        wav = os.path.join(work, f"region_{name}_{stem}.wav")
        export_stem(song, stem, mid, tail_beats=10)
        stems[stem] = stem_fx(render(mid, wav, sf2), cfg.get("fx", {}).get(stem, ()))
    length = song.end_beat * song.spb + song.tail
    ir = room_ir(early=18, early_span=0.07, stereo=True, density_ms=35.0, **cfg["ir"])
    out = mix(stems, cfg["mix"], ir, length, fade_in=0.02, fade_out=cfg["fade_out"],
              master_eq=cfg.get("master_eq", True), comp=cfg.get("comp", (-18.0, 1.6)))
    out = master(out)
    path = os.path.join(assets, "sounds", "music", "region", name + ".ogg")
    write_ogg(path, out, quality=cfg.get("quality", 3))
    if wav_dir:
        write_wav(os.path.join(wav_dir, name + ".wav"), out)
    print(f"{path}  {out.shape[1] / SR:.2f}s  {lufs(out):.1f} LUFS  {true_peak(out):.1f} dBTP", flush=True)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--sf2", default=SF2_DEFAULT)
    ap.add_argument("--work", default="build/music_work")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    a = ap.parse_args()
    names = a.only.split(",") if a.only else list(PIECES)
    for n in names:
        build(n, a.assets, a.sf2, a.work, a.wav)


if __name__ == "__main__":
    main()
