"""Guildmark's title-screen theme, "Where the Road Begins", composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 -B art/audio/make_menu_music.py src/main/resources/assets/guildmark [--sf2 FluidR3_GM.sf2]
        [--work DIR] [--wav DIR]

It writes sounds/music/menu.ogg. The building blocks come from make_music.py (parts and songs, per-stem MIDI
export with seeded humanising, FluidSynth rendering, the synthetic hall, the glue compressor and the limiter).
This script adds the piece, a ritardando (the score is re-timed before export), and a master that sets the
loudness by measurement (ITU-R BS.1770, gated) rather than by peak: the title screen sits about 4 LU under
the boss tracks, with its true peak held under -1 dBFS in the encoded file.

The piece is Guildmark's own theme, original like everything else here. It is a sixteen-bar tune in D major
in a slow 3/4 (68 BPM). The head climbs mi-fa-sol, leaps up to the high tonic and settles back on the sixth
(setting out, and a look back); the second phrase answers it and closes; the third climbs in thirds over G,
A and B minor; the last turns wistful through G minor before it comes home.

Form: an intro (harp arpeggios over a low D drone and soft strings, celesta embers drifting upward, the
head called once on a distant horn); the theme on a solo cello over the harp; the theme again in octave
strings with the choir coming in; a two-bar lift through Bb and C; the broad statement on horns in thirds
with the violins an octave above, choir, trombones and soft timpani; then the coda, where a flute and the
cello trade the first and last phrases over a nylon guitar and slow into a long D major chord that decays
to silence, so the loop can start again after a pause.
"""
import argparse
import os
import sys

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_music import (CHORDS, P, SF2_DEFAULT, Song, export_stem, hall_ir, in_range, pcs, render,  # noqa: E402
                        roll, stereo_comp, stereo_limit, voice_lead)
from sfxkit import SR, db, eq, fade, hp, read_audio, write_ogg, write_wav  # noqa: E402

CHORDS.update({"Bm": "B D F#", "Bm7": "B D F# A", "Gadd9": "G B D A"})

BPM = 68
BARS = 69
OUT = "music/menu"
LOUDNESS = -14.4  # LUFS integrated: 3 to 5 LU under every boss track (they measure -9.6 to -11.2)
TRUE_PEAK = -1.0  # dBFS, measured on the decoded Ogg

# ------------------------------------------------------------------ the theme
# one 3/4 bar per entry, in the home octave (violins); the cello and horns play it an octave lower
THEME = [
    "F#4:2 G4:1", "A4:2 D5:1", "C#5:2 A4:1", "B4:3",
    "F#4:2 G4:1", "A4:2 D5:1", "E5:1.5 D5:0.5 C#5:1", "D5:3",
    "B4:1 D5:2", "C#5:1 E5:2", "D5:1 F#5:2", "E5:3",
    "D5:2 B4:1", "Bb4:2 G4:1", "A4:1.5 F#4:0.5 E4:1", "D4:3",
]
# per bar: (chord, bass[, beats]). The bass falls D B A G under the first phrase and climbs F# G A D under
# the answer; the tune's passing G gets a G chord of its own, "Asus" (A D E) resolves to A under the long
# notes, and G minor is the look back before home.
THEME_HARM = [
    [("D", "D", 2), ("G", "D", 1)], [("Bm7", "B")], [("A", "A")], [("G", "G")],
    [("D", "F#", 2), ("G", "G", 1)], [("Gadd9", "G")], [("Asus", "A", 2), ("A", "A", 1)], [("D", "D")],
    [("G", "G")], [("A", "A")], [("Bm", "B")], [("Asus", "A", 2), ("A", "A", 1)],
    [("G", "G")], [("Gm6", "G")], [("D", "A", 2), ("A7", "A", 1)], [("D", "D")],
]
INTRO_HARM = [[("D", "D")], [("G", "D")], [("D", "D")], [("G", "D")],
              [("D", "D", 2), ("G", "D", 1)], [("Bm7", "D")], [("G", "G")], [("Asus", "A", 2), ("A", "A", 1)]]
# the second horn's line under the theme in the broad statement (horn register, sounding)
HORN2 = ("D3:3 F#3:2 B3:1 A3:2 E3:1 G3:3 D3:3 D3:2 B3:1 A3:3 F#3:3 "
         "G3:1 B3:2 A3:1 C#4:2 B3:1 D4:4 C#4:1 B3:2 G3:1 G3:2 E3:1 F#3:1.5 D3:0.5 C#3:1 F#3:3")

# sections (bars, 1-based); between them the lift is bars 41-42 and the final chord bars 67-69
INTRO, A1, A2, A3, CODA = range(1, 9), range(9, 25), range(25, 41), range(43, 59), range(59, 67)


def harmony():
    """the whole piece's harmony as segments (beat, beats, chord, bass)"""
    th = THEME_HARM
    bars = INTRO_HARM + th + th + [[("Bb", "Bb")], [("C", "C")]] + th + th[:4] + th[12:] + [[("D", "D")]] * 3
    assert len(bars) == BARS
    segs = []
    for i, bar in enumerate(bars):
        at = i * 3.0
        for item in bar:
            d = float(item[2]) if len(item) > 2 else 3.0
            segs.append((at, d, item[0], item[1]))
            at += d
        assert abs(at - (i + 1) * 3.0) < 1e-9
    return segs


SEGS = harmony()
_STARTS = np.array([sg[0] for sg in SEGS])


def seg_at(beat):
    """(chord, bass) sounding at an absolute beat"""
    i = int(np.searchsorted(_STARTS, beat + 1e-6, side="right")) - 1
    return SEGS[max(0, i)][2], SEGS[max(0, i)][3]


_CHANGES = np.array([sg[0] for i, sg in enumerate(SEGS) if i == 0 or SEGS[i - 1][2] != sg[2]])


def damp(beat, dur, grace=0.12):
    """a plucked or struck note stops (after a little grace) where the chord changes, as a harpist damps"""
    i = int(np.searchsorted(_CHANGES, beat + 1e-6))
    return min(dur, _CHANGES[i] - beat + grace) if i < len(_CHANGES) else dur


def segs_in(bars):
    """harmony segments inside a run of bars, as (beat, beats, chord, bass)"""
    lo, hi = (min(bars) - 1) * 3.0, max(bars) * 3.0
    return [sg for sg in SEGS if lo - 1e-6 <= sg[0] < hi - 1e-6]


def pc(name):
    return P(name + "0") % 12


# ------------------------------------------------------------------ writing helpers
def sing(part, beat, spec, vel, tr=0, leg=1.0, lift=0.6, down=4):
    """a melody line ('A4:2 D5:1 ...'): a little louder up high and on each downbeat, as a player phrases"""
    toks = [t.rsplit(":", 1) for t in spec.split()]
    ref = np.mean([P(nm) for nm, _ in toks if nm != "R"])
    for nm, d in toks:
        d = float(d)
        if nm != "R":
            q = P(nm)
            v = vel + lift * (q - ref) + (down if abs(beat / 3 - round(beat / 3)) < 1e-6 else 0)
            part.n(beat, d * leg, q + tr, v)
        beat += d
    return beat


def theme(bars):
    """the theme's bars (1-based theme bar numbers) as one line"""
    return " ".join(THEME[i - 1] for i in bars)


def pad(part_voices, segs, ranges, vel, start=None, overlap=0.05):
    """voice-led sustained chords over harmony segments; repeated chords merge and a voice that keeps its
    note is tied over, so only the moving voices re-attack. part_voices = [(part, [voice index, ...]), ...]"""
    merged = []
    for b, d, ch, _ in segs:
        if merged and merged[-1][2] == ch and abs(merged[-1][0] + merged[-1][1] - b) < 1e-6:
            merged[-1][1] += d
        else:
            merged.append([b, d, ch])
    vcs = voice_lead([m[2] for m in merged], ranges, start)
    for part, idxs in part_voices:
        for i in idxs:
            cur = None
            for (b, d, _), vc in zip(merged, vcs):
                if cur and cur[2] == vc[i]:
                    cur[1] += d
                    continue
                if cur:
                    part.n(cur[0], cur[1] + overlap, cur[2], cur[3])
                cur = [b, d, vc[i], vel(b) if callable(vel) else vel]
            part.n(cur[0], cur[1] + overlap, cur[2], cur[3])
    return vcs


def bassline(part, segs, lo, hi, vel, overlap=0.05):
    """each segment's bass note in [lo, hi], tied while it stays put"""
    cur = None
    for b, d, _, bass in segs:
        q = in_range(pc(bass), lo, hi)
        if cur and cur[2] == q:
            cur[1] += d
            continue
        if cur:
            part.n(cur[0], cur[1] + overlap, cur[2], cur[3])
        cur = [b, d, q, vel(b) if callable(vel) else vel]
    part.n(cur[0], cur[1] + overlap, cur[2], cur[3])


def spread(bass, ch, n=6, first=7, gap=3):
    """an open harp/guitar voicing: the bass, then chord tones at least `gap` semitones apart"""
    p = pcs(ch)
    out, m = [bass], bass + first
    while len(out) < n:
        while m % 12 not in p:
            m += 1
        out.append(m)
        m += gap
    return out


def arps(part, bars, vel, lo, hi, pattern=(0, 1, 2, 3, 4, 3), ring=2.2, step=0.5, n=6):
    """broken chords in eighths following the harmony (the chord can change mid-bar)"""
    for bar in bars:
        for i, idx in enumerate(pattern):
            beat = (bar - 1) * 3.0 + i * step
            ch, bass = seg_at(beat)
            tones = spread(in_range(pc(bass), lo, hi), ch, n=n)
            v = (vel(beat) if callable(vel) else vel) + (5 if i == 0 else 0) - (3 if idx >= 4 else 0)
            part.n(beat, damp(beat, ring + (1.0 if i == 0 else 0.0)), tones[idx], v)


def rolled(part, beat, bass_lo, bass_hi, vel, ring=4.0, n=7, spacing=0.07):
    """a harp chord spread upward from the bass"""
    ch, bass = seg_at(beat)
    for k, q in enumerate(spread(in_range(pc(bass), bass_lo, bass_hi), ch, n=n)):
        part.n(beat + k * spacing, damp(beat, ring) - k * spacing, q, vel - 2 * k)


def embers(part, bars, rng, chance, vel=(30, 44), lo="A5", hi="E7"):
    """celesta sparks drifting up: short rising chord-tone figures that fade as they rise"""
    for bar in bars:
        if rng.random() >= chance:
            continue
        step = 0.5 if rng.random() < 0.55 else 0.25
        off = float(rng.choice([0.5, 1.0, 1.5]))
        k = min(int(rng.integers(2, 5)), int((3.0 - off) / step) + 1)
        b0 = (bar - 1) * 3.0 + off
        tones = [m for m in range(P(lo), P(hi) + 1) if m % 12 in pcs(seg_at(b0)[0])]
        i0 = int(rng.integers(0, len(tones) - k + 1))
        v0 = rng.uniform(*vel)
        last = 0
        for j in range(k):
            b = b0 + j * step
            q = max(tones[i0 + j], last + 1)
            while q % 12 not in pcs(seg_at(b)[0]):  # keep to the chord sounding at that moment
                q += 1
            part.n(b, damp(b, 2.5), q, v0 * (1 - 0.1 * j))
            last = q


def phrases(bars, lo, hi, every=4, peak=0.6, ease=0.45):
    """CC11 points: each `every`-bar phrase swells to `hi` at `peak` of its length, then eases back"""
    bars, pts = list(bars), []
    for i in range(0, len(bars), every):
        b0 = (bars[i] - 1) * 3.0
        b1 = bars[min(len(bars) - 1, i + every - 1)] * 3.0
        pts += [(b0, lo), (b0 + (b1 - b0) * peak, hi), (b1 - 0.02, lo + (hi - lo) * ease)]
    return pts


def retime(song, knots, res=96):
    """a ritardando without tempo events: every note and controller is moved through the speed curve
    [(score beat, speed), ...] (1 = a tempo), so the MIDI keeps its single tempo"""
    xs = np.arange(0.0, song.end_beat + 64.0, 1.0 / res)
    speed = np.interp(xs, [k[0] for k in knots], [k[1] for k in knots])
    real = np.concatenate([[0.0], np.cumsum(1.0 / speed[:-1]) / res])

    def w(b):
        return float(np.interp(b, xs, real))
    for p in song.parts:
        for nt in p.notes:
            b0 = w(nt[0])
            nt[1] = w(nt[0] + nt[1]) - b0
            nt[0] = b0
        p.ccs = [(w(b), c, v) for b, c, v in p.ccs]
    song.end_beat = w(song.end_beat)


# ------------------------------------------------------------------ loudness and mastering
def lufs(x):
    """integrated loudness of a stereo signal: ITU-R BS.1770-4 K-weighting, 400 ms blocks, gated"""
    blk, hop = int(0.4 * SR), int(0.1 * SR)
    z = 0.0
    for c in range(2):
        k = eq(eq(x[c], "highshelf", 1681.974450955533, 0.7071752369554196, 3.999843853973347),
               "hp", 38.13547087602444, 0.5003270373238773)
        cs = np.concatenate([[0.0], np.cumsum(k * k)])
        st = np.arange(0, len(k) - blk + 1, hop)
        z = z + (cs[st + blk] - cs[st]) / blk
    lk = -0.691 + 10 * np.log10(z + 1e-30)
    rel = -0.691 + 10 * np.log10(z[lk > -70.0].mean()) - 10.0
    return -0.691 + 10 * np.log10(z[(lk > -70.0) & (lk > rel)].mean())


def true_peak(x, chunk=1 << 20):
    """dBFS of the 4x oversampled peak"""
    pk = 0.0
    for c in range(x.shape[0]):
        for i in range(0, x.shape[1], chunk):
            seg = x[c, max(0, i - 64):i + chunk + 64]
            pk = max(pk, float(np.max(np.abs(signal.resample_poly(seg, 4, 1)))))
    return 20 * np.log10(pk + 1e-12)


def master(stems, settings, ir, n_out, ceiling, fade_in=0.6, fade_out=2.5):
    """sum the stems (each narrowed to its width) with their sends into the hall, EQ and glue them, then set
    the integrated loudness to LOUDNESS by measurement, limiting peaks at `ceiling` dBFS"""
    n = max(y.shape[1] for y in stems.values())
    dry = np.zeros((2, n))
    send = np.zeros(n)
    for name, y in stems.items():
        g_db, snd, width = settings[name]
        y = np.pad(y, ((0, 0), (0, n - y.shape[1])))
        mid, side = (y[0] + y[1]) / 2, (y[0] - y[1]) / 2 * width
        y = np.vstack([mid + side, mid - side])
        dry += db(g_db) * y
        send += snd * db(g_db) * y.mean(axis=0)
    send = hp(send, 160)
    wet = np.vstack([signal.fftconvolve(send, ir[c])[:n] for c in range(2)])
    out = np.pad(dry + wet, ((0, 0), (0, max(0, n_out - n))))[:, :n_out]
    for c in range(2):
        out[c] = hp(out[c], 28, order=2)
        out[c] = eq(out[c], "peak", 300, 0.8, -1.5)
        out[c] = eq(out[c], "highshelf", 7000, 0.7, 2.5)
    out /= np.max(np.abs(out)) + 1e-12
    out = stereo_comp(out, thresh_db=-20.0, ratio=1.6, attack=0.03, release=0.5)
    g = 1.0
    for _ in range(5):
        y = stereo_limit(out * g, ceiling, lookahead=0.005, release=0.3)
        g *= db(LOUDNESS - lufs(y))
    y = stereo_limit(out * g, ceiling, lookahead=0.005, release=0.3)
    for c in range(2):
        y[c] = fade(y[c], fin=fade_in, fout=fade_out)
    return y


# ================================================================== the piece
def compose_menu():
    s = Song("menu", bpm=BPM, bars=BARS, bpb=3, tail=5.0)
    b = s.b

    # players (GM programs of FluidR3)
    cb = s.part("cb", 43, "bass", pan=70, volume=100, hum=0.012, vjit=3)
    vcl = s.part("vcl", 48, "bass", pan=80, volume=86, hum=0.015, vjit=3)  # celli on the bass line
    pad1 = s.part("pad1", 49, "strings", pan=44, volume=96, hum=0.02, vjit=3)
    pad2 = s.part("pad2", 49, "strings", pan=84, volume=96, hum=0.02, vjit=3)
    vln = s.part("vln", 48, "strings", pan=36, volume=100, lag=0.010, hum=0.012, vjit=3)
    vla = s.part("vla", 48, "strings", pan=82, volume=96, lag=0.012, hum=0.012, vjit=3)
    cello = s.part("cello", 42, "solo", pan=62, volume=104, lag=0.012, hum=0.012, vjit=3)
    flute = s.part("flute", 73, "solo", pan=54, volume=96, lag=0.010, hum=0.012, vjit=3)
    hn1 = s.part("hn1", 60, "brass", pan=46, volume=106, lag=0.012, hum=0.010, vjit=3)
    hn2 = s.part("hn2", 60, "brass", pan=56, volume=100, lag=0.014, hum=0.010, vjit=3)
    tbn = s.part("tbn", 57, "brass", pan=76, volume=90, lag=0.010, hum=0.010, vjit=3)
    tuba = s.part("tuba", 58, "brass", pan=84, volume=84, lag=0.010, hum=0.010, vjit=3)
    ahh = s.part("ahh", 52, "choir", pan=56, volume=100, hum=0.02, vjit=3)
    ohh = s.part("ohh", 53, "choir", pan=70, volume=96, hum=0.02, vjit=3)
    timp = s.part("timp", 47, "perc", pan=60, volume=100, hum=0.006, vjit=3)
    harp = s.part("harp", 46, "color", pan=32, volume=100, hum=0.008, vjit=4)
    cel = s.part("cel", 8, "color", pan=90, volume=84, hum=0.010, vjit=4)
    gtr = s.part("gtr", 24, "color", pan=42, volume=96, hum=0.012, vjit=4)

    # one intensity curve (per bar, 0..1) shapes the velocities of the whole piece
    inten = np.interp(np.arange(1, BARS + 1),
                      [1, 8, 9, 24, 25, 32, 40, 41, 42, 43, 46, 50, 54, 55, 58, 59, 66, 69],
                      [.20, .32, .36, .46, .48, .58, .70, .74, .86, .92, .90, .88, 1.0, .88, .84, .40, .32, .22])

    def V(lo, hi):
        return lambda beat: lo + (hi - lo) * inten[min(BARS - 1, int(beat // 3))]

    # ---- the bass: a low D drone under the intro, then the theme's bass lines; celli an octave up
    bassline(cb, SEGS, 30, 41, V(48, 92))
    bassline(vcl, segs_in(range(9, 70)), 42, 53, V(40, 84))

    # ---- string pads: full and soft in the intro, a high halo above the cello, inner harmony after
    R4 = [(50, 61), (55, 66), (59, 70), (62, 74)]
    pad([(pad1, [0, 1]), (pad2, [2, 3])], segs_in(INTRO), R4, V(40, 62))
    pad([(pad1, [0]), (pad2, [1, 2])], segs_in(A1), [(62, 69), (66, 74), (69, 78)], V(36, 58))
    pad([(pad1, [0]), (pad2, [1, 2])], segs_in(range(25, 43)), [(50, 59), (54, 64), (57, 67)], V(32, 54))
    pad([(pad1, [0, 1]), (pad2, [2, 3])], segs_in(A3), R4, V(40, 62))
    pad([(pad1, [0]), (pad2, [1, 2])], segs_in(range(59, 70)), [(50, 60), (55, 65), (59, 69)], V(40, 58))

    # ---- choir: oohs breathe under the intro and the coda; aahs come in with the second statement
    pad([(ohh, [0, 1, 2])], segs_in(range(3, 9)), [(50, 60), (57, 66), (62, 71)], V(40, 58))
    pad([(ohh, [0, 1, 2])], segs_in(range(63, 70)), [(50, 60), (55, 65), (59, 69)], V(42, 56))
    choir4 = [(50, 60), (55, 65), (59, 69), (62, 72)]
    pad([(ahh, [0, 1, 2, 3])], segs_in(range(29, 43)), choir4, V(34, 62))
    pad([(ahh, [0, 1, 2, 3])], segs_in(A3), choir4, V(50, 80))

    # ---- intro: the head called once on a distant horn, left open on the high D
    sing(hn1, b(5), "F#3:2 G3:1 A3:2 D4:4", 64, lift=0.3, down=2)

    # ---- A1: the theme on the solo cello, in its singing register
    sing(cello, b(9), theme(range(1, 17)), 72, tr=-12)

    # ---- A2: the strings take it in octaves
    sing(vln, b(25), theme(range(1, 17)), 84)
    sing(vla, b(25), theme(range(1, 17)), 78, tr=-12)
    # the horns steal in under its second half, holding the harmony
    pad([(hn1, [1]), (hn2, [0])], segs_in(range(33, 41)), [(48, 57), (53, 62)], V(40, 56))

    # ---- the lift: the climbing thirds over Bb and C, the violins rising a step into the theme
    sing(hn1, b(41), "D3:1 F3:2 E3:1 G3:2", 78)
    sing(hn2, b(41), "Bb2:1 D3:2 C3:1 E3:2", 72)
    sing(vla, b(41), "D3:1 F3:2 E3:1 G3:2", 74)
    sing(vln, b(41), "F4:3 G4:3", 74)

    # ---- A3: the broad statement. Horns in thirds, violins an octave above, violas with the horns,
    # trombones and tuba holding the harmony softly underneath
    sing(hn1, b(43), theme(range(1, 17)), 94, tr=-12)
    sing(hn2, b(43), HORN2, 86)
    sing(vln, b(43), theme(range(1, 17)), 90)
    sing(vla, b(43), theme(range(1, 17)), 82, tr=-12)
    pad([(tbn, [0, 1])], segs_in(range(41, 59)), [(43, 52), (48, 57)], V(46, 70))
    bassline(tuba, segs_in(range(41, 59)), 30, 41, V(44, 62))

    # ---- coda: the flute remembers the head, the cello answers with the close
    sing(flute, b(59), theme(range(1, 5)), 62, tr=12)
    sing(cello, b(63), theme(range(13, 17)), 74, tr=-12)

    # ---- harp: arpeggios through the intro and both statements (higher and lighter in the second),
    # a sweep up the C chord into the broad statement, rolled chords at its phrase heads, the last chord
    arps(harp, INTRO, V(40, 66), 43, 54)
    arps(harp, A1, V(40, 62), 43, 54)
    arps(harp, A2, V(36, 56), 50, 61)
    arps(harp, [41], V(40, 62), 46, 57)
    for i, q in enumerate(["C3", "E3", "G3", "C4", "E4", "G4", "C5", "E5", "G5", "C6", "E6", "G6"]):
        harp.n(b(42) + i * 0.25, damp(b(42) + i * 0.25, 2.0), q, 46 + 2.2 * i)
    arps(harp, A3, V(40, 60), 43, 54)
    for bar in (43, 47, 51, 55):
        rolled(harp, b(bar), 38, 50, 76)
    rolled(harp, b(67), 38, 50, 66, ring=9.0, n=8, spacing=0.09)

    # ---- nylon guitar under the coda, like a lute by the fire
    arps(gtr, CODA, lambda beat: 54 if beat < b(63) else 47, 40, 51, pattern=(0, 2, 3, 4, 3, 2), ring=1.6, n=5)
    rolled(gtr, b(67), 40, 51, 56, ring=8.0, n=6, spacing=0.06)

    # ---- celesta: embers drifting up from the Wardfire, and a last one over the final chord
    rng = np.random.default_rng(1871)
    embers(cel, range(2, 9), rng, 0.85, vel=(44, 58))
    embers(cel, A1, rng, 0.3, vel=(40, 52))
    embers(cel, A2, rng, 0.25, vel=(40, 54))
    embers(cel, CODA, rng, 0.45, vel=(40, 52))
    for i, (q, v) in enumerate((("A5", 48), ("D6", 45), ("E6", 42), ("A6", 38))):
        cel.n(b(68) + 0.5 + 0.5 * i, 3.0, q, v)

    # ---- timpani, gently: a roll through the lift, strokes at the broad statement's landmarks
    roll(timp, s, b(41), 3, P("D2"), 34, 56)
    roll(timp, s, b(42), 2.9, P("C2"), 50, 84)
    for bar, q, v in ((43, "D2", 90), (47, "D2", 66), (50, "D2", 58), (51, "G2", 72), (52, "A1", 70),
                      (55, "G2", 80), (58, "D2", 76)):
        timp.n(b(bar), 2.0, q, v)
    roll(timp, s, b(54), 2.9, P("A1"), 56, 98)
    roll(timp, s, b(57), 2.9, P("A1"), 48, 80)
    timp.n(b(67), 3.0, "D2", 40)

    # ---- expression (CC11), one curve per player: phrases swell and ease, the lift crescendos, the ending
    # dies away. The second statement's second half swells harder than its first, so the theme grows.
    def lift(lo, hi):
        return [(b(41), lo), (b(42, 3) + 0.9, hi)]
    A2a, A2b = range(25, 33), range(33, 41)
    cb.expr([(0, 72), (b(3), 100), (b(58, 3), 104), (b(59), 86), (b(67), 86), (b(69, 3) + 0.9, 40)])
    vcl.expr([(b(9), 90), (b(58, 3), 104), (b(59), 84), (b(67), 84), (b(69, 3) + 0.9, 40)])
    pads_curve = ([(0, 56), (b(4), 92), (b(8, 3) + 0.9, 100)] + phrases(A1, 74, 96) + phrases(A2a, 78, 98)
                  + phrases(A2b, 92, 116) + lift(96, 118) + phrases(A3, 96, 116) + phrases(CODA, 82, 100)
                  + [(b(67), 96), (b(69, 3) + 0.9, 34)])
    for p_ in (pad1, pad2):
        p_.expr(pads_curve)
    cello.expr(phrases(A1, 86, 114) + phrases(range(63, 67), 90, 114))
    flute.expr(phrases(range(59, 63), 86, 112))
    strings_curve = phrases(A2a, 94, 112) + phrases(A2b, 102, 124) + lift(100, 122) + phrases(A3, 106, 126, ease=0.4)
    for p_ in (vln, vla):
        p_.expr(strings_curve)
    hn1.expr([(b(5), 78), (b(6), 104), (b(7, 2), 70)] + phrases(A2b, 72, 100) + lift(78, 116) + phrases(A3, 98, 122))
    hn2.expr(phrases(A2b, 72, 100) + lift(78, 116) + phrases(A3, 96, 120))
    tbn.expr(lift(70, 110) + phrases(A3, 92, 116))
    tuba.expr(lift(70, 106) + phrases(A3, 88, 110))
    ahh.expr(phrases(range(29, 33), 70, 94) + phrases(A2b, 88, 114) + lift(96, 118) + phrases(A3, 94, 116))
    ohh.expr([(b(3), 50), (b(6), 92), (b(8, 3) + 0.9, 84), (b(63), 70), (b(66), 96), (b(67), 92),
              (b(69, 3) + 0.9, 30)])

    # ---- tempo: a breath before the broad statement, a broadening at its cadence, the coda slowing to rest
    retime(s, [(0, 1.0), (b(42), 1.0), (b(42, 3), 0.92), (b(43), 1.0), (b(55), 1.0), (b(57, 3), 0.86),
               (b(58, 2), 0.9), (b(59), 1.0), (b(63), 1.0), (b(66, 3), 0.84), (b(69, 3), 0.78),
               (s.end_beat + 64, 0.78)])
    return s


# per-stem (gain dB, hall send, stereo width). FluidR3's cello and string pads are very wide stereo samples
# (L/R correlation about 0.2 even panned centre), so the solos are narrowed to a point and the strings a little.
MENU_MIX = {"bass": (-1.5, 0.22, 1.0), "strings": (0.0, 0.34, 0.7), "solo": (4.5, 0.26, 0.3),
            "brass": (-3.0, 0.30, 1.0), "choir": (-4.0, 0.42, 1.0), "perc": (-3.0, 0.26, 1.0),
            "color": (1.0, 0.38, 1.0)}
QUALITY = 4  # Vorbis -q: about 120 kbps here, which keeps the file under 3 MB


# ================================================================== driver
def build(assets, sf2, work, wav_dir=None):
    song = compose_menu()
    os.makedirs(work, exist_ok=True)
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        mid = os.path.join(work, f"menu_{stem}.mid")
        wav = os.path.join(work, f"menu_{stem}.wav")
        export_stem(song, stem, mid)
        stems[stem] = render(mid, wav, sf2)
    n_out = int(round((song.end_beat * song.spb + song.tail) * SR))
    ir = hall_ir(rt60=2.8, seed=111, predelay=0.03)
    path = os.path.join(assets, "sounds", OUT + ".ogg")
    # the encoder overshoots the limiter a little, so check the decoded file and tighten if needed
    ceiling = TRUE_PEAK - 0.8
    for _ in range(4):
        out = master(stems, MENU_MIX, ir, n_out, ceiling)
        write_ogg(path, out, quality=QUALITY)
        dec = read_audio(path)
        tp = true_peak(dec)
        if tp <= TRUE_PEAK - 0.05:
            break
        ceiling -= tp - (TRUE_PEAK - 0.15)
    if wav_dir:
        write_wav(os.path.join(wav_dir, OUT + ".wav"), out)
    print(f"{path}  {dec.shape[1] / SR:.3f}s  {lufs(dec):.2f} LUFS  true peak {tp:.2f} dBFS", flush=True)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--sf2", default=SF2_DEFAULT)
    ap.add_argument("--work", default="build/music_work")
    ap.add_argument("--wav", default=None)
    a = ap.parse_args()
    build(a.assets, a.sf2, a.work, a.wav)


if __name__ == "__main__":
    main()
