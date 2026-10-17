"""The festivals' music and the seasons' stings, composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 -B art/audio/make_festival_music.py src/main/resources/assets/guildmark [--sf2 FluidR3_GM.sf2]
        [--work DIR] [--only ribbonwake,stings,fanfare] [--mp3 DIR]

Each grand festival has its own band and its own dance tune, original like everything else here, played in sets in
the town's square while the townsfolk dance. A set is four phrases of eight bars and a finale of four, at 120 beats
a minute in 4/4: a bar is two seconds, forty game ticks, which is what a dance figure takes, so the dancers step on
the music. The game starts a set on a tick of its own and plays phrase n of it 320 ticks after phrase n - 1; each
phrase is its own file so that someone who comes into the square halfway through a set hears the next phrase start
on time (sounds can't be started partway). A phrase's file is its eight bars to the sample and then its ring: the
next phrase starts over the ring, as the band would play on.

The tune of a festival is two strains of eight bars, A and B, and a set plays them A B A B with the band filling out:
the first A on the festival's lead alone over the chords, the bass and a light drum; B on the second lead with the
pad coming in; A again on both leads in octaves, a second voice a third under them and the drums in full; B again
in full, with bells on its long notes and a fill into the finale; and the finale runs up to the tonic and holds it
under a roll and a cymbal, while the dancers bow.

- Ribbonwake (spring): a reel in D major. Fiddle and tin whistle, a steel-strung guitar chopping the off-beats,
  pizzicato bass, a frame drum, tambourine and claps, an accordion holding the chords in the full strains.
- Sunhearth (summer): a processional in A major, dotted like a galliard. A recorder and a trumpet, a lute rolling
  arpeggios, a cello's boom and a tuba's in the full strains, a tabor (snare, side stick and bass drum), strings.
- Sheafmoot (autumn): a stamping harvest dance in G major, turning to E minor. Oboe and fiddle, a clarinet a third
  under them, an accordion's oom-pah, bassoon on the bass, stamps (bass drum), claps and a wood block.
- Lanternrise (winter): a round dance in F major, gentle, in long notes. Flute and celesta, glockenspiel on the long
  notes, a harp's arpeggios, a choir's "ah" and soft strings, a pizzicato bass, triangle and a soft tambourine.

The stings (sounds/season/sting_<season>.ogg) are each season's arrival, four seconds or so: spring's harp running
up under a flute's call, summer's trumpets over a timpani roll, autumn's horn over a strummed guitar and low strings,
winter's celesta and bells over a choir. The fanfare (sounds/festival/fanfare.ogg) is the prize-giving: trumpets
and drums, five seconds.

Levels: a festival's five files are mastered together, so the light first strain stays lighter than the full ones:
one gain for the five, set so the set as the game joins it measures -17 LUFS, with every file's true peak under
-1 dBTP. The stings measure -17 LUFS each and the fanfare -16.

--mp3 DIR writes, to listen to on a phone: each festival's set as the game plays it (a chapter mark at each phrase)
and the stings and the fanfare in a row.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import warnings

import numpy as np
from scipy import signal
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_music as mm  # noqa: E402
from make_music import (P, SF2_DEFAULT, Song, export_stem, hall_ir, in_range, pcs, render, stereo_comp,  # noqa: E402
                        stereo_limit)
from make_menu_music import lufs, true_peak  # noqa: E402
from sfxkit import SR, db, eq, fade, hp, read_audio, rng_for, write_ogg, write_wav  # noqa: E402

warnings.filterwarnings("ignore", category=wavfile.WavFileWarning)

MORE_CHORDS = {"F#m": "F# A C#", "E7": "E G# B D", "Bm": "B D F#", "D7": "D F# A C", "C7": "C E G Bb", "G7": "G B D F",
               "Bbm": "Bb Db F"}
for _k, _v in MORE_CHORDS.items():
    assert _k not in mm.CHORDS or mm.CHORDS[_k] == _v, _k
    mm.CHORDS[_k] = _v

BPM = 120
BPB = 4
PHRASE_BARS = 8
FINALE_BARS = 4
TAIL = 2.6           # seconds of ring kept after a phrase's last bar
LOUDNESS = -17.0     # LUFS, a festival's whole set as the game joins it
TRUE_PEAK = -1.2     # dBFS, measured on the decoded Ogg
QUALITY = 5

MAJOR = [0, 2, 4, 5, 7, 9, 11]


# ================================================================ the festivals
# A strain is 8 bars: per bar a chord, or a pair of chords a half bar each. Tunes are in the lead's sounding pitch.
FESTIVALS = {
    "ribbonwake": dict(
        key="D", drums="reel", chords="strum", bass="boom", hall=1.5,
        prog_a=["D", "D", "G", "A", "D", "D", ("G", "A"), "D"],
        prog_b=["G", "D", "Em", "A", "G", "D", ("Em", "A"), "D"],
        prog_f=["G", "A", "D", "D"],
        tune_a=("D5:0.5 F#5:0.5 A5:0.5 F#5:0.5 D5:0.5 F#5:0.5 A5:1 | B5:0.5 A5:0.5 F#5:0.5 A5:0.5 F#5:0.5 E5:0.5 D5:1 | "
                "G5:0.5 B5:0.5 D6:0.5 B5:0.5 G5:0.5 B5:0.5 A5:0.5 G5:0.5 | F#5:0.5 E5:0.5 F#5:0.5 G5:0.5 A5:1 E5:1 | "
                "D5:0.5 F#5:0.5 A5:0.5 D6:0.5 C#6:0.5 A5:0.5 B5:0.5 A5:0.5 | G5:0.5 F#5:0.5 E5:0.5 F#5:0.5 D5:1 A4:1 | "
                "B4:0.5 C#5:0.5 D5:0.5 B4:0.5 C#5:0.5 D5:0.5 E5:0.5 C#5:0.5 | D5:1 F#5:0.5 E5:0.5 D5:1 A4:1"),
        tune_b=("B5:1 A5:0.5 G5:0.5 B5:1 D6:1 | A5:1.5 F#5:0.5 D5:1 F#5:1 | "
                "G5:0.5 F#5:0.5 E5:0.5 F#5:0.5 G5:0.5 A5:0.5 B5:0.5 G5:0.5 | A5:2 C#5:1 E5:1 | "
                "D6:1 B5:0.5 G5:0.5 D6:1 B5:1 | A5:0.5 B5:0.5 A5:0.5 F#5:0.5 D5:2 | "
                "E5:0.5 F#5:0.5 G5:0.5 E5:0.5 C#5:0.5 D5:0.5 E5:0.5 C#5:0.5 | D5:2 R:1 A4:1"),
        tune_f=("B5:0.5 A5:0.5 G5:0.5 B5:0.5 D6:1 B5:1 | C#6:0.5 B5:0.5 A5:0.5 C#6:0.5 E6:1 C#6:1 | "
                "D6:1 A5:0.5 F#5:0.5 D5:1 A5:1 | D6:3 R:1"),
        # (name, GM program, stem, pan, volume, transpose for the octave it plays the tune in)
        lead1=("fiddle", 40, "lead", 50, 104, 0),
        lead2=("whistle", 72, "lead2", 78, 88, 0),
        second=("fiddle2", 40, "harm", 40, 92),
        comp=("guitar", 25, "chords", 86, 100),
        pad=("accordion", 21, "pad", 64, 74),
        bassi=("pizz", 45, "bass", 60, 108),
        bells=("glock", 9, "color", 72, 70),
        mix={"lead": (0.0, 0.18), "lead2": (-2.5, 0.2), "harm": (-4.5, 0.2), "chords": (-3.0, 0.12), "pad": (-8.0, 0.2),
             "bass": (-2.0, 0.08), "drums": (-1.5, 0.08), "color": (-7.0, 0.3)},
    ),
    "sunhearth": dict(
        key="A", drums="march", chords="arp", bass="boom", hall=1.7,
        prog_a=["A", "D", "A", "E", "A", "D", "E", "A"],
        prog_b=["F#m", "D", "A", "E", "F#m", "D", "E", ("E7", "A")],
        prog_f=["D", "E", "A", "A"],
        tune_a=("A4:1.5 C#5:0.5 E5:1 A5:1 | F#5:1 E5:0.5 D5:0.5 F#5:1 A5:1 | E5:1.5 D5:0.5 C#5:1 E5:1 | "
                "B4:1.5 C#5:0.5 D5:1 E5:1 | A5:1.5 G#5:0.5 A5:1 E5:1 | F#5:1 A5:0.5 F#5:0.5 D5:1 F#5:1 | "
                "E5:1 G#5:1 B5:1 G#5:1 | A5:3 E5:1"),
        tune_b=("C#6:1 B5:0.5 A5:0.5 C#6:1 F#5:1 | D6:1 C#6:0.5 B5:0.5 A5:1 F#5:1 | "
                "E5:0.5 F#5:0.5 G#5:0.5 A5:0.5 B5:1 C#6:1 | B5:2 G#5:1 E5:1 | A5:1 C#6:0.5 B5:0.5 A5:1 F#5:1 | "
                "F#5:1 A5:0.5 F#5:0.5 D5:2 | E5:0.5 G#5:0.5 B5:0.5 D6:0.5 C#6:1 B5:1 | A5:2 R:1 E5:1"),
        tune_f=("F#5:0.5 A5:0.5 D6:1 C#6:0.5 B5:0.5 A5:1 | G#5:0.5 B5:0.5 E6:1 D6:0.5 C#6:0.5 B5:1 | "
                "A5:1 E5:0.5 C#5:0.5 A4:1 E5:1 | A5:3 R:1"),
        lead1=("recorder", 74, "lead", 56, 104, 0),
        lead2=("trumpet", 56, "lead2", 74, 96, -12),
        second=("horn", 60, "harm", 44, 90),
        comp=("lute", 24, "chords", 84, 104),
        pad=("strings", 48, "pad", 64, 84),
        bassi=("cello", 42, "bass", 58, 104),
        bells=("tubular", 14, "color", 70, 62),
        mix={"lead": (0.0, 0.2), "lead2": (-3.5, 0.22), "harm": (-6.0, 0.22), "chords": (-2.0, 0.14), "pad": (-7.0, 0.26),
             "bass": (-2.0, 0.1), "drums": (-2.5, 0.1), "color": (-9.0, 0.32)},
    ),
    "sheafmoot": dict(
        key="G", drums="stomp", chords="oompah", bass="walk", hall=1.4,
        prog_a=["G", "C", "G", "D", "G", "C", "D", "G"],
        prog_b=["Em", "C", "G", "D", "Em", "C", "D", "G"],
        prog_f=["C", "D", "G", "G"],
        tune_a=("G4:0.5 B4:0.5 D5:1 D5:0.5 E5:0.5 D5:1 | E5:0.5 G5:0.5 E5:0.5 C5:0.5 E5:1 C5:1 | "
                "D5:0.5 B4:0.5 G4:0.5 B4:0.5 D5:1 G5:1 | F#5:1 E5:0.5 D5:0.5 A4:1 D5:1 | "
                "B4:0.5 D5:0.5 G5:1 G5:0.5 A5:0.5 G5:1 | E5:0.5 G5:0.5 C6:1 B5:0.5 A5:0.5 G5:1 | "
                "F#5:0.5 G5:0.5 A5:0.5 F#5:0.5 D5:1 C5:1 | B4:1 G4:1 G4:1 D5:1"),
        tune_b=("E5:1 B4:0.5 E5:0.5 G5:1 E5:1 | C5:1 E5:0.5 G5:0.5 C6:1 G5:1 | B5:0.5 A5:0.5 G5:0.5 F#5:0.5 G5:1 D5:1 | "
                "A5:1.5 G5:0.5 F#5:1 D5:1 | G5:0.5 F#5:0.5 E5:0.5 F#5:0.5 G5:1 B5:1 | C6:1 B5:0.5 A5:0.5 G5:1 E5:1 | "
                "D5:0.5 E5:0.5 F#5:0.5 G5:0.5 A5:1 F#5:1 | G5:2 R:1 D5:1"),
        tune_f=("E5:0.5 G5:0.5 C6:1 B5:0.5 A5:0.5 G5:1 | F#5:0.5 A5:0.5 D6:1 C6:0.5 B5:0.5 A5:1 | "
                "G5:1 D5:0.5 B4:0.5 G4:1 D5:1 | G5:3 R:1"),
        lead1=("oboe", 68, "lead", 52, 104, 0),
        lead2=("fiddle", 40, "lead2", 78, 100, 0),
        second=("clarinet", 71, "harm", 40, 96),
        comp=("accordion", 21, "chords", 86, 96),
        pad=("strings", 49, "pad", 64, 76),
        bassi=("bassoon", 70, "bass", 58, 108),
        bells=("marimba", 12, "color", 70, 70),
        mix={"lead": (0.0, 0.16), "lead2": (-2.0, 0.18), "harm": (-4.5, 0.18), "chords": (-4.0, 0.12), "pad": (-9.0, 0.2),
             "bass": (-1.5, 0.08), "drums": (-1.0, 0.07), "color": (-8.0, 0.24)},
    ),
    "lanternrise": dict(
        key="F", drums="gentle", chords="harp", bass="root", hall=2.2,
        prog_a=["F", "Bb", "F", "C", "Dm", "Bb", "C", "F"],
        prog_b=["Bb", "F", "Gm", "C", "Bb", "F", ("Gm", "C"), "F"],
        prog_f=["Bb", "C", "F", "F"],
        tune_a=("C5:1 F5:1 A5:1 G5:0.5 F5:0.5 | Bb4:1 D5:1 F5:2 | A5:1 G5:0.5 F5:0.5 C5:1 F5:1 | E5:2 G5:1 C5:1 | "
                "D5:1 F5:1 A5:1 F5:1 | D5:1 Bb4:1 D5:1 F5:1 | G5:1.5 F5:0.5 E5:1 G5:1 | F5:3 C5:1"),
        tune_b=("D6:1.5 C6:0.5 Bb5:1 F5:1 | A5:1.5 G5:0.5 F5:1 C5:1 | D5:1 G5:1 Bb5:1 A5:0.5 G5:0.5 | G5:2 E5:1 C5:1 | "
                "F5:1 Bb5:1 D6:1 C6:0.5 Bb5:0.5 | A5:1 C6:1 F5:2 | G5:1 Bb5:0.5 A5:0.5 G5:1 E5:1 | F5:3 C5:1"),
        tune_f=("D5:1 F5:1 Bb5:1 D6:1 | E5:1 G5:1 C6:1 E6:1 | F6:2 C6:1 A5:1 | F5:3 R:1"),
        lead1=("flute", 73, "lead", 54, 100, 0),
        lead2=("celesta", 8, "lead2", 76, 104, 12),
        second=("strings_hi", 49, "harm", 40, 92),
        comp=("harp", 46, "chords", 84, 100),
        pad=("choir", 52, "pad", 64, 82),
        bassi=("pizz", 45, "bass", 58, 100),
        bells=("glock", 9, "color", 72, 76),
        mix={"lead": (0.0, 0.26), "lead2": (-3.0, 0.3), "harm": (-6.0, 0.3), "chords": (-2.5, 0.24), "pad": (-6.5, 0.34),
             "bass": (-3.0, 0.14), "drums": (-5.0, 0.18), "color": (-6.0, 0.36)},
    ),
}
ORDER = ["ribbonwake", "sunhearth", "sheafmoot", "lanternrise"]


def bars_of(tune):
    """A tune written bar by bar (bars split by '|') as one line spec, checking every bar has four beats."""
    bars = [b.strip() for b in tune.split("|")]
    for i, b in enumerate(bars):
        total = sum(float(t.rsplit(":", 1)[1]) for t in b.split())
        assert abs(total - BPB) < 1e-6, (i + 1, total, b)
    return bars


def notes_of(spec):
    """[(beat, dur, midi or None)] of a line spec, from beat 0."""
    out, b = [], 0.0
    for tok in spec.split():
        nm, d = tok.rsplit(":", 1)
        d = float(d)
        out.append((b, d, None if nm == "R" else P(nm)))
        b += d
    return out


def chord_at(prog, beat):
    """The chord sounding at a beat of a strain."""
    bar = int(beat // BPB)
    c = prog[min(bar, len(prog) - 1)]
    if isinstance(c, tuple):
        return c[0] if (beat - bar * BPB) < 2 else c[1]
    return c


def segs(prog):
    """(beat, beats, chord) of every chord of a strain."""
    out = []
    for i, c in enumerate(prog):
        if isinstance(c, tuple):
            out += [(i * BPB, 2.0, c[0]), (i * BPB + 2.0, 2.0, c[1])]
        else:
            out.append((i * BPB, 4.0, c))
    return out


def ladder(chord, lo, hi):
    pc = pcs(chord)
    return [m for m in range(lo, hi + 1) if m % 12 in pc]


def second_voice(key_pc, prog, spec):
    """A voice under the tune: on the long notes the highest of the chord's own tones a third to a sixth under it, on
    the short ones the note a third under it in the key (so it moves with the tune)."""
    scale = [(key_pc + s) % 12 for s in MAJOR]
    out = []
    for b, d, m in notes_of(spec):
        if m is None:
            out.append((b, d, None))
            continue
        h = None
        if d >= 1.0:
            cands = [c for c in range(m - 9, m - 2) if c % 12 in pcs(chord_at(prog, b))]
            h = max(cands) if cands else None
        if h is None:
            if m % 12 in scale:
                i = scale.index(m % 12)
                t = scale[(i - 2) % 7]
                h = m - ((m % 12 - t) % 12)
            else:
                h = m - 3 if (m - 3) % 12 in scale else m - 4
        out.append((b, d, h))
    return out


# ================================================================ arranging a strain
def arrange(fest, song, section, intensity):
    """Writes one section (strain A or B, light or full, or the finale) into a fresh song."""
    f = FESTIVALS[fest]
    key_pc = P(f["key"] + "0") % 12
    full = intensity >= 0.6
    which = {"a1": "a", "b1": "b", "a2": "a", "b2": "b", "f": "f"}[section]
    prog = f["prog_" + which]
    tune = " ".join(bars_of(f["tune_" + which]))
    nbars = len(prog)
    rng = rng_for(fest, section, "arrangement")

    def vel(base):
        return base + 22 * intensity

    # ---- the players
    l1 = song.part(f["lead1"][0], f["lead1"][1], f["lead1"][2], pan=f["lead1"][3], volume=f["lead1"][4], hum=0.008, vjit=4)
    l2 = song.part(f["lead2"][0], f["lead2"][1], f["lead2"][2], pan=f["lead2"][3], volume=f["lead2"][4], hum=0.008, vjit=4)
    sv = song.part(f["second"][0], f["second"][1], f["second"][2], pan=f["second"][3], volume=f["second"][4], hum=0.01, vjit=4)
    cp = song.part(f["comp"][0], f["comp"][1], f["comp"][2], pan=f["comp"][3], volume=f["comp"][4], hum=0.008, vjit=5)
    pd = song.part(f["pad"][0], f["pad"][1], f["pad"][2], pan=f["pad"][3], volume=f["pad"][4], hum=0.015, vjit=3)
    bs = song.part(f["bassi"][0], f["bassi"][1], f["bassi"][2], pan=f["bassi"][3], volume=f["bassi"][4], hum=0.008, vjit=4)
    bl = song.part(f["bells"][0], f["bells"][1], f["bells"][2], pan=f["bells"][3], volume=f["bells"][4], hum=0.006, vjit=3)
    kit = song.part("kit", 0, "drums", drum=True, pan=64, volume=100, hum=0.006, vjit=5)

    # ---- the tune
    def play(part, spec, v, tr=0, leg=0.92, breath=True):
        part.line(0.0, spec, v, tr=tr, leg=leg, accent=6)
        if breath:
            pts = []
            for b, d, m in notes_of(spec):
                if m is None:
                    continue
                if d >= 1.5:
                    pts += [(b, 92), (b + 0.4 * d, 112), (b + d - 0.1, 96)]
                else:
                    pts += [(b, 100), (b + d * 0.6, 106)]
            part.expr(pts)

    lead1_tr, lead2_tr = f["lead1"][5], f["lead2"][5]
    if section == "a1":
        play(l1, tune, vel(66), tr=lead1_tr)
    elif section == "b1":
        play(l2, tune, vel(68), tr=lead2_tr)
        play(l1, tune, vel(40), tr=lead1_tr - 12 if lead1_tr >= 0 else lead1_tr)  # a soft double under it
    else:
        play(l1, tune, vel(70), tr=lead1_tr)
        play(l2, tune, vel(62), tr=lead2_tr)
        for b, d, h in second_voice(key_pc, prog, tune):
            if h is not None:
                sv.n(b, d * 0.95, h, vel(54))
        sv.expr([(0, 96), (nbars * BPB - 0.1, 104)])

    # bells on the long notes of the full B strain and the finale
    if section in ("b2", "f"):
        for b, d, m in notes_of(tune):
            if m is not None and d >= 1.0:
                bl.n(b, min(d, 2.0), m + 12, vel(46) + int(rng.integers(-4, 5)))

    # ---- the chords
    kind = f["chords"]
    for b0, dur, ch in segs(prog):
        if kind == "strum":
            tones = ladder(ch, 50, 67)[:4]
            hits = [0.0, 0.5, 1.5, 2.5, 3.5] if full else [0.5, 1.5, 2.5, 3.5]
            for h in hits:
                if h < dur:
                    for k, m in enumerate(tones):
                        cp.n(b0 + h + 0.025 * k, 0.35, m, vel(48) - 3 * k + (8 if h == 0.0 else 0))
        elif kind == "arp":
            lad = ladder(ch, 45, 76)
            r = next(i for i, m in enumerate(lad) if m % 12 == pcs(ch)[0])
            pat = [0, 2, 1, 2, 3, 2, 1, 2] if full else [0, 2, 1, 2]
            step = 0.5 if full else 1.0
            for k in range(int(dur / step)):
                idx = min(len(lad) - 1, r + pat[k % len(pat)])
                cp.n(b0 + k * step, 1.2, lad[idx], vel(46) + (6 if k == 0 else 0))
        elif kind == "oompah":
            tones = ladder(ch, 55, 70)[:3]
            for h in (1.0, 3.0) if dur >= 4 else (1.0,):
                for m in tones:
                    cp.n(b0 + h, 0.45, m, vel(46))
            if full:
                for h in (1.5, 3.5) if dur >= 4 else (1.5,):
                    for m in tones:
                        cp.n(b0 + h, 0.25, m, vel(34))
        elif kind == "harp":
            lad = ladder(ch, 41, 79)
            r = next(i for i, m in enumerate(lad) if m % 12 == pcs(ch)[0])
            pat = [0, 2, 4, 5, 6, 5, 4, 2] if full else [0, 2, 4, 2]
            step = 0.5 if full else 1.0
            for k in range(int(dur / step)):
                idx = min(len(lad) - 1, r + pat[k % len(pat)])
                cp.n(b0 + k * step, 1.6, lad[idx], vel(44) + (6 if k == 0 else 0))
        # the pad holds the chord in the fuller strains
        if section != "a1":
            for m in ladder(ch, 53, 69)[:3]:
                pd.n(b0, dur * 1.02, m, vel(36))
    if section != "a1":
        pd.expr([(0, 80), (nbars * BPB * 0.6, 104), (nbars * BPB - 0.1, 94)])

    # ---- the bass
    style = f["bass"]
    for b0, dur, ch in segs(prog):
        r = in_range(pcs(ch)[0], 36, 47)
        fifth = in_range(pcs(ch)[2], 36, 52) if len(pcs(ch)) > 2 else r + 7
        if style == "boom":
            bs.n(b0, 0.9, r, vel(56) + 6)
            if dur > 2:
                bs.n(b0 + 2.0, 0.9, fifth if fifth - r < 12 else fifth - 12, vel(52))
        elif style == "walk":
            third = in_range(pcs(ch)[1], r, r + 11)
            steps = [r, third, fifth if fifth < r + 12 else fifth - 12, third] if dur > 2 else [r, fifth if fifth < r + 12 else fifth - 12]
            for k, m in enumerate(steps):
                bs.n(b0 + k, 0.8, m, vel(54) + (6 if k == 0 else 0))
        else:
            bs.n(b0, dur * 0.9, r, vel(50))
            if full and dur > 2:
                bs.n(b0 + 2.5, 0.4, r + 12, vel(40))

    # ---- the drums (GM kit: 35/36 bass drum, 37 side stick, 38 snare, 39 clap, 41/45 toms, 42 hat, 49 crash,
    # 54 tambourine, 76/77 wood block, 81 open triangle)
    pattern = f["drums"]
    for bar in range(nbars):
        b0 = bar * BPB
        last = bar == nbars - 1
        if pattern == "reel":
            for beat, note, v in ((0, 41, 60), (1.5, 45, 40), (2, 41, 54), (3.5, 45, 42)):
                kit.n(b0 + beat, 0.3, note, vel(v))
            for beat in (1, 3):
                kit.n(b0 + beat, 0.2, 54, vel(38))
                if full:
                    kit.n(b0 + beat, 0.2, 39, vel(40))
            if full:
                for k in range(8):
                    kit.n(b0 + k * 0.5, 0.1, 54, vel(18 + (8 if k % 2 else 0)))
        elif pattern == "march":
            kit.n(b0, 0.3, 36, vel(54))
            kit.n(b0 + 2, 0.3, 36, vel(48))
            if full:
                for beat in (1, 3):
                    kit.n(b0 + beat, 0.2, 38, vel(46))
                kit.n(b0 + 2.75, 0.1, 38, vel(26))
                for k in range(8):
                    kit.n(b0 + k * 0.5, 0.1, 54, vel(16 + (6 if k % 2 == 0 else 0)))
            else:
                for k in range(4):
                    kit.n(b0 + k + 0.5, 0.1, 37, vel(30))
        elif pattern == "stomp":
            kit.n(b0, 0.3, 35, vel(66))
            kit.n(b0 + 2, 0.3, 35, vel(60))
            for beat in (1, 3):
                kit.n(b0 + beat, 0.2, 39, vel(44 if full else 32))
            for beat in (0.5, 1.5, 2.5, 3.5):
                kit.n(b0 + beat, 0.1, 76 if beat in (1.5, 3.5) else 77, vel(28 if full else 20))
        elif pattern == "gentle":
            kit.n(b0, 1.0, 81, vel(30))
            if full:
                kit.n(b0, 0.3, 35, vel(38))
                kit.n(b0 + 2, 0.3, 35, vel(30))
                for k in range(4):
                    kit.n(b0 + k + 0.5, 0.15, 54, vel(14))
        # a fill into the next strain (the full B strain and the finale run up with toms)
        if last and section in ("b1", "b2", "a2") and pattern != "gentle":
            for k, note in enumerate((50, 48, 47, 45)):
                kit.n(b0 + 3 + k * 0.25, 0.2, note, vel(40) + 4 * k)

    # ---- the finale's end: the tonic held under a roll and a cymbal
    if section == "f":
        tonic = prog[-1]
        end = (nbars - 1) * BPB
        roll_note = 38 if pattern in ("march", "reel") else (41 if pattern == "stomp" else 81)
        k = 0
        t = end - BPB
        while t < end:
            kit.n(t, 0.1, roll_note, 24 + (t - (end - BPB)) / BPB * 50)
            t += 0.125
            k += 1
        kit.n(end, 2.0, 49, vel(70))
        kit.n(end, 0.5, 36 if pattern != "gentle" else 35, vel(70))
        for m in ladder(tonic, 50, 74):
            cp.n(end, 3.5, m, vel(56))
        bs.n(end, 3.5, in_range(pcs(tonic)[0], 31, 42), vel(66))
        for m in ladder(tonic, 55, 72)[:4]:
            pd.n(end, 3.6, m, vel(52))
    return song


SECTIONS = [("a1", 0.25), ("b1", 0.45), ("a2", 0.75), ("b2", 0.9), ("f", 1.0)]


def compose(fest, section, intensity):
    bars = FINALE_BARS if section == "f" else PHRASE_BARS
    s = Song(f"{fest}_{section}", bpm=BPM, bars=bars, bpb=BPB, tail=TAIL)
    return arrange(fest, s, section, intensity)


# ================================================================ the stings and the fanfare
def compose_sting(season):
    s = Song(f"sting_{season}", bpm=96, bars=2, bpb=4, tail=3.0)
    if season == "spring":
        harp = s.part("harp", 46, "chords", pan=44, volume=100)
        fl = s.part("flute", 73, "lead", pan=70, volume=104)
        st = s.part("strings", 49, "pad", pan=64, volume=90)
        for k, m in enumerate([50, 54, 57, 62, 66, 69, 74, 78, 81]):  # D major, up the harp
            harp.n(k * 0.125, 2.5, m, 60 + 3 * k)
        fl.line(1.0, "A5:0.5 D6:0.5 F#6:1.5 E6:0.5 D6:3", 78)
        fl.expr([(1, 90), (3, 112), (7, 96)])
        for m in (62, 66, 69, 74):
            st.n(0.5, 7.0, m, 52)
        st.expr([(0.5, 60), (3.0, 106), (7.5, 84)])
    elif season == "summer":
        tp = s.part("trumpets", 56, "lead", pan=60, volume=104)
        tp2 = s.part("trumpets2", 56, "lead2", pan=74, volume=96)
        tim = s.part("timpani", 47, "drums", pan=64, volume=110)
        st = s.part("strings", 48, "pad", pan=64, volume=92)
        for k in range(16):
            tim.n(k * 0.125, 0.12, 45, 40 + 3 * k)
        tim.n(2.0, 1.5, 33 + 12, 104)
        tp.line(2.0, "A4:0.5 C#5:0.25 E5:0.25 A5:1.5 E5:0.5 A5:3", 92)
        tp2.line(2.0, "E4:0.5 A4:0.25 C#5:0.25 E5:1.5 C#5:0.5 E5:3", 84)
        for m in (57, 61, 64, 69):
            st.n(2.0, 5.5, m, 70)
    elif season == "autumn":
        hn = s.part("horn", 60, "lead", pan=56, volume=104)
        gt = s.part("guitar", 24, "chords", pan=80, volume=100)
        lo = s.part("low", 42, "pad", pan=50, volume=96)
        hn.line(0.5, "D4:0.75 G4:0.25 B4:1.5 A4:0.5 G4:3", 84)
        hn.expr([(0.5, 92), (2.5, 112), (6.5, 90)])
        for k, m in enumerate([43, 47, 50, 55, 59, 62]):
            gt.n(0.0 + k * 0.04, 4.0, m, 70 - 2 * k)
        for k, m in enumerate([43, 47, 50, 55, 59, 62]):
            gt.n(4.0 + k * 0.04, 3.5, m, 62 - 2 * k)
        for m in (31, 38, 43):
            lo.n(0.0, 7.5, m, 60)
    else:
        cel = s.part("celesta", 8, "lead", pan=60, volume=104)
        bell = s.part("bells", 14, "color", pan=74, volume=90)
        ch = s.part("choir", 52, "pad", pan=64, volume=96)
        cel.line(0.0, "C6:0.5 F6:0.5 A6:0.5 C7:0.5 A6:0.5 F6:0.5 G6:2 F6:3", 66)
        bell.n(0.0, 4.0, 77, 54)
        bell.n(3.0, 4.0, 72, 48)
        for m in (53, 57, 60, 65):
            ch.n(0.0, 7.5, m, 54)
        ch.expr([(0, 60), (2.5, 104), (7.5, 80)])
    return s


def compose_fanfare():
    s = Song("fanfare", bpm=108, bars=2, bpb=4, tail=3.2)
    tp = s.part("trumpets", 56, "lead", pan=58, volume=106)
    tp2 = s.part("trumpets2", 56, "lead2", pan=74, volume=100)
    tb = s.part("trombones", 57, "pad", pan=46, volume=98)
    kit = s.part("kit", 0, "drums", drum=True, pan=64, volume=100)
    tim = s.part("timpani", 47, "bass", pan=64, volume=110)
    tp.line(0.0, "C5:0.5 C5:0.25 C5:0.25 G5:1 E5:0.5 G5:0.5 C6:4", 94)
    tp2.line(0.0, "G4:0.5 G4:0.25 G4:0.25 E5:1 C5:0.5 E5:0.5 G5:4", 86)
    tb.line(0.0, "C4:0.5 C4:0.25 C4:0.25 C4:1 G3:0.5 G3:0.5 C4:4", 84)
    for k in range(8):
        kit.n(1.0 + k * 0.125, 0.1, 38, 40 + 5 * k)
    kit.n(3.0, 2.0, 49, 100)
    kit.n(3.0, 0.4, 36, 100)
    tim.n(0.0, 0.5, 48, 90)
    tim.n(3.0, 2.0, 48, 110)
    return s


STING_MIX = {"lead": (0.0, 0.26), "lead2": (-3.0, 0.26), "chords": (-2.0, 0.28), "pad": (-4.0, 0.34), "drums": (-2.0, 0.18),
             "color": (-4.0, 0.36), "bass": (-2.0, 0.16)}


# ================================================================ rendering and mastering
def render_song(song, work, sf2):
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        if not any(p.notes for p in song.parts if p.stem == stem):
            continue
        mid = os.path.join(work, f"{song.name}_{stem}.mid")
        wav = os.path.join(work, f"{song.name}_{stem}.wav")
        export_stem(song, stem, mid, tail_beats=int(np.ceil(song.tail / song.spb)) + 2)
        stems[stem] = render(mid, wav, sf2)
    return stems


def premix(stems, settings, ir, n_out):
    """Sum the stems with their sends into the hall and EQ them, keeping the level (the master sets it)."""
    n = max(y.shape[1] for y in stems.values())
    dry = np.zeros((2, n))
    send = np.zeros(n)
    for name, y in stems.items():
        g_db, snd = settings[name]
        y = np.pad(y, ((0, 0), (0, n - y.shape[1])))
        dry += db(g_db) * y
        send += snd * db(g_db) * y.mean(axis=0)
    send = hp(send, 180)
    wet = np.vstack([signal.fftconvolve(send, ir[0])[:n], signal.fftconvolve(send, ir[1])[:n]])
    out = dry + wet
    out = np.pad(out, ((0, 0), (0, max(0, n_out - n))))[:, :n_out]
    for c in range(2):
        out[c] = hp(out[c], 35, order=2)
        out[c] = eq(out[c], "peak", 300, 0.8, -1.5)
        out[c] = eq(out[c], "highshelf", 7000, 0.7, 1.5)
    return out


def finish(y, g, ceiling, fin=0.003, fout=0.6):
    y = stereo_comp(y * g, -12.0, 1.5)
    y = stereo_limit(y, ceiling, lookahead=0.005, release=0.12)
    for c in range(2):
        y[c] = fade(y[c], fin=fin, fout=fout)
    return y


def joined(pieces, spacing):
    """The pieces as the game plays them: each starting `spacing` seconds after the one before, rings overlapping."""
    n = int(round(spacing * SR)) * (len(pieces) - 1) + pieces[-1].shape[1]
    out = np.zeros((2, n))
    for i, p in enumerate(pieces):
        a = int(round(spacing * SR)) * i
        out[:, a:a + p.shape[1]] += p
    return out


def build_festival(fest, assets, work, sf2, mp3_dir=None):
    f = FESTIVALS[fest]
    ir = hall_ir(rt60=f["hall"], seed=211 + ORDER.index(fest), predelay=0.018)
    phrase_s = PHRASE_BARS * BPB * 60.0 / BPM
    pre = {}
    for section, inten in SECTIONS:
        song = compose(fest, section, inten)
        stems = render_song(song, work, sf2)
        n_out = int(round((song.end_beat * song.spb + TAIL) * SR))
        pre[section] = premix(stems, f["mix"], ir, n_out)
    order = [s for s, _ in SECTIONS]
    # One gain for the five files, set on the set as the game joins it
    g, ceiling = 1.0, TRUE_PEAK - 1.0
    for _ in range(6):
        outs = {s: finish(pre[s], g, ceiling) for s in order}
        loud = lufs(joined([outs[s] for s in order], phrase_s))
        err = LOUDNESS - loud
        if abs(err) < 0.05:
            break
        g *= db(err)
    names = {"a1": "0", "b1": "1", "a2": "2", "b2": "3", "f": "finale"}
    worst = -99.0
    for _ in range(4):
        decoded = {}
        for s in order:
            path = os.path.join(assets, "sounds", "festival", f"{fest}_{names[s]}.ogg")
            write_ogg(path, outs[s], quality=QUALITY)
            decoded[s] = read_audio(path)
        worst = max(true_peak(d) for d in decoded.values())
        if worst <= TRUE_PEAK:
            break
        ceiling -= (worst - TRUE_PEAK) + 0.2
        outs = {s: finish(pre[s], g, ceiling) for s in order}
    total = joined([decoded[s] for s in order], phrase_s)
    print(f"{fest}: 5 files, set {total.shape[1] / SR:.1f}s, {lufs(total):.2f} LUFS, true peak {worst:.2f} dBFS", flush=True)
    if mp3_dir:
        marks, t = [], 0.0
        for k, s in enumerate(order):
            title = {"a1": "strain A, light", "b1": "strain B", "a2": "strain A, full", "b2": "strain B, full", "f": "finale"}[s]
            end = t + (phrase_s if s != "f" else decoded[s].shape[1] / SR)
            marks.append((t, end, title))
            t += phrase_s
        write_mp3(total, os.path.join(mp3_dir, f"festival_{fest}.mp3"), f"{fest.capitalize()} (the dance set)", marks)
    return total


def build_short(name, song, assets, rel, work, sf2, target):
    stems = render_song(song, work, sf2)
    n_out = int(round((song.end_beat * song.spb + song.tail) * SR))
    ir = hall_ir(rt60=2.0, seed=307)
    pre = premix(stems, STING_MIX, ir, n_out)
    g, ceiling = 1.0, TRUE_PEAK - 1.0
    path = os.path.join(assets, "sounds", rel + ".ogg")
    for _ in range(8):
        out = finish(pre, g, ceiling, fout=1.2)
        write_ogg(path, out, quality=QUALITY)
        dec = read_audio(path)
        loud, tp = lufs(dec), true_peak(dec)
        if tp > TRUE_PEAK:
            ceiling -= (tp - TRUE_PEAK) + 0.2
            continue
        if abs(loud - target) < 0.15:
            break
        g *= db(target - loud)
    print(f"{rel}: {dec.shape[1] / SR:.2f}s, {loud:.2f} LUFS, true peak {tp:.2f} dBFS", flush=True)
    return dec


def write_mp3(y, path, title, marks):
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "t.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "t.mp3")
        write_wav(wav, y.astype(np.float32))
        with open(meta, "w") as fh:
            fh.write(f";FFMETADATA1\ntitle={title}\nartist=Guildmark\n")
            for a, b, t in marks:
                fh.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\ntitle={t}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1", "-map_chapters", "1",
                        "-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "3", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)
    print(f"{path}  {y.shape[1] / SR:.1f}s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--sf2", default=SF2_DEFAULT)
    ap.add_argument("--work", default=None)
    ap.add_argument("--only", default=None, help="festival names, 'stings' and/or 'fanfare'")
    ap.add_argument("--mp3", default=None, help="a folder for listen-through MP3s")
    a = ap.parse_args()
    only = set(a.only.split(",")) if a.only else set(ORDER) | {"stings", "fanfare"}
    work = a.work or tempfile.mkdtemp(prefix="festival_music_")
    os.makedirs(work, exist_ok=True)
    try:
        for fest in ORDER:
            if fest in only:
                build_festival(fest, a.assets, work, a.sf2, a.mp3)
        shorts = []
        if "stings" in only:
            for season in ("spring", "summer", "autumn", "winter"):
                shorts.append((season, build_short(season, compose_sting(season), a.assets, f"season/sting_{season}", work, a.sf2, -17.0)))
        if "fanfare" in only:
            shorts.append(("fanfare", build_short("fanfare", compose_fanfare(), a.assets, "festival/fanfare", work, a.sf2, -16.0)))
        if a.mp3 and shorts:
            gap = np.zeros((2, int(1.0 * SR)))
            parts, marks, t = [], [], 0.0
            for name, y in shorts:
                parts += [y, gap]
                marks.append((t, t + y.shape[1] / SR, name))
                t += y.shape[1] / SR + 1.0
            write_mp3(np.concatenate(parts, axis=1), os.path.join(a.mp3, "season_stings.mp3"), "The seasons' stings and the fanfare", marks)
    finally:
        if not a.work:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
