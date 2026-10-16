"""Sun and Spindrift: the islands' theme (music.isles), composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 -B art/audio/make_island_music.py src/main/resources/assets/guildmark [--sf2 FluidR3_GM.sf2]
        [--work DIR] [--wav DIR] [--mp3 FILE] [--reuse]

It writes sounds/music/isles.ogg. The building blocks come from make_music.py (parts and songs, per-stem MIDI export with
seeded humanising, FluidSynth rendering, the synthetic hall, the glue compressor and the limiter); the master follows
make_sea_music.py (an exact loop, the level set by measurement, ITU-R BS.1770, gated). It is made to sit under a game: a
chamber piece, harp and plucked strings and a wooden flute over soft strings and a light hand drum, no brass, no choir, no
big drums, nothing that arrives suddenly; it measures -18 LUFS (the region themes, which are heard once and then not for
minutes, are -17) and its true peak stays under -1 dBTP in the encoded file.

The piece is in A major with the flat seventh of the mixolydian (the G that turns up over the A and wants to fall to the F
sharp), in a lilting 4/4 at 75 beats a minute, 48 bars, 2:33.6 (153.600 s to the sample). It is built on one small figure,
the gull's: the flute leaps a fourth, E to A, and comes down the scale, G, F sharp, as a bird drops from a gust. The tune is
eight bars of it, a first phrase that rises a fourth and settles on the fifth of the dominant, a second that reaches for the
high C sharp and walks down the scale to E, with a pickup that brings the tune round again.

Form (bars), and who plays: dawn on the shingle (1-8) the harp rolls an open A chord in quarter notes over a low A (the
contrabass, held all eight bars) and soft strings, a celesta murmurs the figure an octave up (bar 4), then the flute spells
it out in long notes, one to a bar (E, A, G-F sharp, E) over a cello's A, as the harp starts to run in eighths and the
pizzicato bass comes in (bar 7); the tune (9-16) the flute with the harp's eighths, the pizzicato bass, the strings and a
soft conga, a cabasa and the cello's roots joining under the second phrase (bar 13); the tune again (17-24) the nylon guitar
takes up the harp's eighths and the harp slows to quarters, a cello sings a line under the flute, the second phrase lifts
to a high E (from bar 21) with the violins an octave under it and a celesta ringing its long notes; spindrift (25-32) a new
tune, rising in arpeggios and falling in steps, on the violins and the flute in unison, the guitar chopping the off-beats,
the bass walking, the hand drums at their fullest, and a falling run of the harp as the spray comes down (bar 32); the long
swell (33-40) almost everything stops: a cello alone (the D minor chord of bar 35 is the one wistful moment), the guitar
picking, droplets of celesta and harp, then the flute remembering the tune (bar 37) over F sharp minor and D, and the
strings, the bass and the drum coming back under it (bar 39); home water (41-48) the tune once more on the flute with the
violins an octave under it, the harp running, the guitar chopping again, and then everything slowing and thinning to the
last chord: E, A, B, with the flute's last E ringing on over the join.

The last chord of the file (E, A, B over an E) and the first (A, B, E over an A) are the same three notes: the end of the
piece is the start of it with the root moved a fourth, which is how a V sus4 runs into the I sus2. The game plays it as a loop,
so the file is exactly one turn of it: it is the 48 bars to the sample, and what rings on past the last bar (the flute's last
note, the strings' release, the tail of the hall) is not thrown away or faded but folded over the first seconds, where the
music itself would have it. The ends are only rounded (4 ms in, 8 ms out), at the quietest of the piece.

--mp3 writes the theme to listen to on a phone, a chapter at each section, then once more the last ten seconds and the first
fourteen, joined as the game joins them, to hear the loop turn.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import warnings
from itertools import product

import numpy as np
from scipy import signal
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_music as mm  # noqa: E402
from make_music import (SF2_DEFAULT, Song, export_stem, hall_ir, in_range, pcs, render, stereo_comp,  # noqa: E402
                        stereo_limit, swell)
from make_menu_music import lufs, true_peak  # noqa: E402
from sfxkit import SR, db, eq, fade, hp, read_audio, rng_for, write_ogg, write_wav  # noqa: E402

warnings.filterwarnings("ignore", category=wavfile.WavFileWarning)  # FluidSynth's float WAVs carry a LIST chunk

# chords the theme uses beyond make_music's (the others are the same spelling wherever they are defined)
ISLE_CHORDS = {"Asus2": "A B E", "Esus4": "E A B", "Dmaj7": "D F# A C#", "F#m": "F# A C#", "F#m7": "F# A C# E",
               "Bm": "B D F#", "Bm7": "B D F# A", "C#m": "C# E G#"}
for _k, _v in ISLE_CHORDS.items():
    assert _k not in mm.CHORDS or mm.CHORDS[_k] == _v, _k
    mm.CHORDS[_k] = _v

BPM = 75
BPB = 4
BARS = 48
OUT = "music/isles"
LOUDNESS = -18.0  # LUFS integrated: the region themes measure -17, the boss themes about -10
TRUE_PEAK = -1.4  # dBFS, measured on the decoded Ogg (it has to stay under -1)
QUALITY = 5
FADE_IN, FADE_OUT = 0.004, 0.008  # seconds: the loop is circular, so the ends are only rounded

# one entry a bar (two chords, a half bar each, where the harmony turns at the third beat)
PROG = (["Asus2", "A", "Dmaj7", "A", "A", "A", ("A7", "D"), ("Esus4", "E")]                       # 1-8 dawn on the shingle
        + ["A", "D", "F#m", "E", "A", "Dmaj7", ("Esus4", "E"), "A"]                               # 9-16 the tune
        + ["A", "D", "F#m", "E", "A", "Dmaj7", ("Esus4", "E"), "A"]                               # 17-24 the tune again
        + ["D", "E", "C#m", "F#m", "Bm", "E", ("D", "E"), "A"]                                    # 25-32 spindrift
        + ["F#m7", "Dmaj7", "Dm", "A", "F#m7", "Dmaj7", "Bm7", ("Esus4", "E")]                    # 33-40 the long swell
        + ["A", "D", "F#m", "E", "A", "Dmaj7", "Bm7", "Esus4"])                                   # 41-48 home water
assert len(PROG) == BARS
BASS_PC = {3: 9, 36: 4}  # where the bass is not the chord's root: the A that holds under the Dmaj7 (bar 3), the E under the A (36)

# sections (bars, 1-based), and the chapter marks of the listen-through
DAWN, TUNE1, TUNE2, SPINDRIFT, SWELL, HOME = (range(1, 9), range(9, 17), range(17, 25), range(25, 33), range(33, 41),
                                              range(41, 49))
CHAPTERS = [(1, "dawn on the shingle"), (9, "the tune"), (17, "the tune again"), (25, "spindrift"), (33, "the long swell"),
            (41, "home water")]

# (bar, intensity 0..1): the arc of the piece, driving velocities
ARC = [(1, .18), (4, .24), (8, .38), (9, .44), (16, .58), (17, .56), (24, .70), (25, .72), (30, .90), (32, .84), (33, .36),
       (36, .32), (38, .38), (40, .58), (41, .66), (44, .72), (46, .58), (48, .24)]

# the tune, in the flute's own pitches: two bars to a line (A = the first phrase, B = the second)
TUNE_A1 = "E5:1.5 A5:0.5 G5:1 F#5:3 E5:1 D5:1"              # bars 1-2: the figure, then down to D
TUNE_A2 = "C#5:1.5 D5:0.5 C#5:1 A4:1 B4:3 R:1"              # bars 3-4: down to A, and up a step to the B that holds
TUNE_B1 = "E5:1.5 A5:0.5 B5:1 C#6:3 B5:2"                   # bars 5-6: up to the high C sharp
TUNE_B2 = "A5:2 G#5:1 F#5:1 E5:3 R:0.5 B4:0.5"              # bars 7-8: A over the sus chord, down the scale to E, and the pickup
TUNE_A2_VAR = "C#5:1.5 D5:0.5 C#5:1 A4:1 B4:2 G#4:1 B4:1"   # the same, a step more in the last bar
TUNE_B1_LIFT = "E5:1.5 A5:0.5 B5:1 C#6:1 E6:2 D6:1 B5:1"    # the same, lifted over the Dmaj7 to the high E
TUNE_B2_LIFT = "A5:2 G#5:1 F#5:1 E5:4"
# spindrift: rising arpeggios (a bar) answered by steps coming down
SPIN_1 = "F#5:1 A5:1 D6:2  C#6:1.5 B5:0.5 G#5:2  E5:1 G#5:1 C#6:2  B5:1.5 A5:0.5 F#5:2"
CELLO_2 = "E3:2 C#3:1 E3:1  F#3:2 A3:2  A3:2 F#3:1 C#3:1  B2:2 E3:2  E3:2 C#3:1 E3:1  F#3:2 A3:2  E3:2 G#3:1 B3:1  A2:4"
SPIN_2A = "D6:1.5 B5:0.5 F#5:2  G#5:1 B5:1 E6:2  D6:1.5 C#6:0.5 B5:1 G#5:1"   # bars 29-31
SPIN_END = "A5:3 R:1"                                                         # bar 32

# per-stem (gain dB, reverb send)
ISLES_MIX = {"harp": (0.0, 0.30), "pluck": (-1.0, 0.20), "bass": (-3.0, 0.10), "low": (-1.0, 0.12), "strings": (-2.0, 0.40),
             "wind": (1.5, 0.34), "color": (-4.0, 0.48), "perc": (2.5, 0.14)}

# per-stem channel processing before the mix, ('hp', f) or ('eq', kind, f, q, gain): the harp and the guitar sit low in
# their registers and the soft strings are dark, so the top of them is lifted a little and the bottom of everything but the
# bass is taken away
STEM_FX = {"harp": [("hp", 90), ("eq", "lowshelf", 220, 0.7, -2.0), ("eq", "highshelf", 2500, 0.7, 3.5)],
           "pluck": [("hp", 90), ("eq", "highshelf", 2500, 0.7, 3.0)],
           "bass": [("hp", 55)],
           "low": [("hp", 45), ("eq", "highshelf", 2000, 0.7, 2.0)],
           "strings": [("hp", 120), ("eq", "highshelf", 3000, 0.7, 1.5)],
           "wind": [("hp", 250), ("eq", "highshelf", 4000, 0.7, 1.0)],
           "color": [("hp", 400)],
           "perc": [("hp", 150), ("eq", "lp", 8500, 0.7, 0.0)]}
# (bar, dB): the mix is ridden over the piece, after the stems are summed, to give it the arc the arrangement alone does not
RIDE = [(1, 3.0), (4.5, 3.0), (6.5, 0.0), (20.5, 0.0), (21.5, -1.2), (24.5, -1.2), (25.5, 0.8), (28.5, 0.8), (29.5, 0.3),
        (32.5, 0.0), (33.5, 1.5), (36.5, 1.5), (37.5, 0.0), (47.5, 0.0), (49, 3.0)]

# the arpeggios: (first half bar, second half bar, step in beats), as steps up the chord's tones from the root
PATS = {"q": ([0, 2], [3, 4], 1.0), "wave": ([0, 2, 3, 4], [3, 2, 3, 2], 0.5), "sway": ([0, 2, 3, 2], [4, 3, 2, 3], 0.5),
        "rise": ([0, 2, 3, 4], [2, 3, 4, 3], 0.5)}
CYCLE = ["wave", "sway", "wave", "rise"]  # the harp's eighths go round these, a bar each, so that no two bars in a row are the same
ACCENT = {1.0: (7, 0), 0.5: (7, 0, 3, 0)}
# the seventh is for the harp and the guitar, which can spread it; the strings hold the triad (the Dmaj7's C sharp next to its
# D, the sevenths of F sharp minor and B minor, would only rub)
PAD_CHORD = {"Dmaj7": "D", "F#m7": "F#m", "Bm7": "Bm"}
# the same for the arpeggios (the colour tones are the flute's and the celesta's): the harp and the guitar play the triad
ARP_CHORD = {"Dmaj7": "D", "F#m7": "F#m", "Bm7": "Bm", "A7": "A"}


def voicings(chords, ranges):
    """chords for a string section, a note a voice (ranges low to high), each voicing near the one before: as make_music's
    voice_lead, with the root and the third kept in, and no two voices a semitone apart (a second only in a sus chord), nor a
    seventh"""
    out, prev = [], [(lo + hi) // 2 for lo, hi in ranges]
    for ch in chords:
        pc = pcs(ch)
        sus = ch.startswith(("Asus", "Esus", "Dsus"))
        cand = [[m for m in range(lo, hi + 1) if m % 12 in pc] for lo, hi in ranges]
        best, bestc = None, 1e9
        for combo in product(*cand):
            if any(combo[i] >= combo[i + 1] for i in range(len(combo) - 1)):
                continue
            cost = sum(abs(a - b) for a, b in zip(combo, prev))
            have = [m % 12 for m in combo]
            cost += 12 * (pc[0] not in have) + 10 * (pc[1] not in have) + 3 * (len(pc) > 2 and pc[2] not in have)
            cost += 6 * (have.count(pc[1]) > 1)
            for i in range(len(combo)):
                for j in range(i + 1, len(combo)):
                    d = combo[j] - combo[i]
                    if d % 12 == 1 or d % 12 == 11:
                        cost += 40
                    elif d == 2:
                        cost += (3 if sus else 8) if j == i + 1 else 0
            if combo[-1] - combo[0] > 19:
                cost += 2
            if cost < bestc:
                best, bestc = combo, cost
        out.append(list(best))
        prev = list(best)
    return out


def compose_isles():
    s = Song("isles", bpm=BPM, bars=BARS, bpb=BPB, tail=0.0)
    rng = rng_for("isles", "arrangement")
    inten = np.interp(np.arange(1, BARS + 1), [a for a, _ in ARC], [b for _, b in ARC])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[bar - 1]

    def segs(bars):
        """(bar, beat offset, beats, chord) for every chord of the bars"""
        out = []
        for bar in bars:
            c = PROG[bar - 1]
            out += [(bar, 0.0, 2.0, c[0]), (bar, 2.0, 2.0, c[1])] if isinstance(c, tuple) else [(bar, 0.0, 4.0, c)]
        return out

    # ---- the players
    harp = s.part("harp", 46, "harp", pan=40, volume=100, hum=0.006, vjit=5)
    harp2 = s.part("harp2", 46, "harp", pan=88, volume=92, hum=0.008, vjit=5)
    gtr = s.part("gtr", 24, "pluck", pan=80, volume=96, hum=0.008, vjit=5)
    pizz = s.part("pizz", 45, "bass", pan=54, volume=104, hum=0.008, vjit=4)
    vc = s.part("vc", 42, "low", pan=68, volume=104, hum=0.01, vjit=3)
    cb = s.part("cb", 43, "low", pan=60, volume=92, hum=0.008)
    pad1 = s.part("pad1", 49, "strings", pan=44, volume=92, hum=0.02)
    pad2 = s.part("pad2", 49, "strings", pan=84, volume=92, hum=0.02)
    vns = s.part("vns", 48, "strings", pan=36, volume=96, lag=0.01, hum=0.012)
    fl = s.part("fl", 73, "wind", pan=58, volume=104, hum=0.012, vjit=3)
    cel = s.part("cel", 8, "color", pan=68, volume=88, hum=0.01)
    kit = s.part("kit", 0, "perc", drum=True, pan=64, volume=100, hum=0.006, vjit=5)  # FluidR3's standard kit
    pr = [(52, 62), (57, 67), (61, 71), (64, 74)]  # the ranges of the four voices of the soft strings

    # ================================================================ shared figures
    def ladder(chord, lo, hi):
        pc = pcs(chord)
        return [m for m in range(lo, hi + 1) if m % 12 in pc]

    def run(part, bar, off, chord, half, step, vel, lo, hi, root_pc=None, dur=1.4, end=4.0):
        chord = ARP_CHORD.get(chord, chord)
        """half a bar of an arpeggio: notes `half` steps up the chord's tones from its lowest root at or over lo. A note is let
        go where its chord ends (the harp rings a little past that, as a harp does, but not over the next chord)"""
        lad = ladder(chord, lo, hi)
        rp = pcs(chord)[0] if root_pc is None else root_pc
        r = next(i for i, m in enumerate(lad) if m % 12 == rp)
        acc = ACCENT[step]
        for k, idx in enumerate(half):
            at = off + k * step
            part.n(s.b(bar) + at, min(dur, end - at + 0.15), lad[min(len(lad) - 1, r + idx)], vel + acc[k % len(acc)])

    def arp(part, bars, pat, vel, lo=41, hi=74, dur=1.4):
        for bar in bars:
            first, second, step = PATS[CYCLE[bar % len(CYCLE)] if pat == "cycle" else pat]
            sg = segs([bar])
            rp = BASS_PC.get(bar)
            if len(sg) == 1:
                _, _, _, ch = sg[0]
                run(part, bar, 0.0, ch, first, step, vel(bar), lo, hi, rp, dur)
                run(part, bar, 2.0, ch, second, step, vel(bar) - 3, lo, hi, rp, dur)
            else:
                for _, off, _, ch in sg:
                    run(part, bar, off, ch, first, step, vel(bar), lo, hi, None, dur, end=off + 2.0)

    def pad(bars, parts, ranges, vel, legato=1.03):
        """soft sustained chords, voice-led across the half-bar changes: parts is [(part, [voices])]"""
        sg = segs(bars)
        for (bar, off, dur, _), vc_ in zip(sg, voicings([PAD_CHORD.get(c, c) for _, _, _, c in sg], ranges)):
            for part, voices in parts:
                for i in voices:
                    part.n(s.b(bar) + off, dur * legato, vc_[i], vel(bar))

    def stabs(part, bars, vel, lo=52, hi=70, at=(1.5, 3.5)):
        """the guitar chopping the off-beats: the chord's three notes, strummed (a thirtieth of a beat between them)"""
        for bar, off, dur, ch in segs(bars):
            tones = ladder(ARP_CHORD.get(ch, ch), lo, hi)[:3]
            for beat in at:
                if off <= beat < off + dur:
                    for k, m in enumerate(tones):
                        part.n(s.b(bar) + beat + 0.03 * k, 0.4, m, vel(bar) - 3 * k)

    def bass(part, bars, lo, hi, vel, style):
        """the bass under every chord: the root on the beat, the fifth on the third beat ('boom-chick'); 'walk' adds the octave"""
        for bar, off, dur, ch in segs(bars):
            pc = BASS_PC.get(bar, pcs(ch)[0]) if off == 0.0 else pcs(ch)[0]
            r = in_range(pc, lo, hi)
            v = vel(bar)
            b0 = s.b(bar) + off
            part.n(b0, 0.9, r, v + 6)
            if style == "walk":
                part.n(b0 + 1.0, 0.9, r + 12, v - 8)
                if dur > 2.0:
                    part.n(b0 + 2.0, 0.9, r + 7, v - 2)
                    part.n(b0 + 3.0, 0.9, r + 12, v - 10)
            elif dur > 2.0:
                part.n(b0 + 2.0, 0.9, r + 7, v - 2)

    def drone(part, bars, pc, lo, hi, vel):
        """one long note under whole bars, whatever the chords do over it"""
        for bar in bars:
            part.n(s.b(bar), BPB + 0.05, in_range(pc, lo, hi), vel(bar))

    def roots(part, bars, lo, hi, vel):
        """the bass of every chord, held to its end (under the half-bar changes as under whole bars)"""
        for bar, off, dur, ch in segs(bars):
            pc = BASS_PC.get(bar, pcs(ch)[0]) if off == 0.0 else pcs(ch)[0]
            part.n(s.b(bar) + off, dur * 1.02, in_range(pc, lo, hi), vel(bar))

    def breathe(part, beat, spec, base=100, bump=12, vib=0):
        """the flute's (or the strings') breath: every long note swells a little and eases off at its end, and carries a vibrato
        that comes in after it has begun; the short ones only lean on their first half. Spec as Part.line"""
        pts, vpts, b = [], [], beat
        for tok in spec.split():
            nm, d = tok.rsplit(":", 1)
            d = float(d)
            if nm != "R":
                if d >= 1.5:
                    pts += [(b, base - 10), (b + 0.35 * d, base + bump), (b + d - 0.12, base - 8)]
                    vpts += [(b, 0), (b + 0.4 * d, 0), (b + 0.8 * d, vib), (b + d - 0.05, 0.6 * vib)]
                else:
                    pts += [(b, base - 4), (b + 0.5 * d, base + 3)]
                    vpts += [(b, 0), (b + d * 0.9, 0)]
            b += d
        part.expr(pts)
        if vib:
            part.expr(vpts, cc=1)

    def sing(part, beat, spec, vel, base=100, bump=12, vib=22, tr=0, leg=0.97, accent=4):
        part.line(beat, spec, vel, tr=tr, leg=leg, accent=accent)
        breathe(part, beat, spec, base, bump, vib)

    def conga(bars, kind, vel):
        """the hand drums: low conga on the beat, open and muted hi congas between"""
        for k, bar in enumerate(bars):
            b0, v = s.b(bar), vel(bar)
            if kind == "heart":
                kit.n(b0, 0.5, 64, v)
                kit.n(b0 + 2.0, 0.5, 64, v - 8)
            elif kind == "light":
                for beat, note, dv in ((0.0, 64, 0), (1.5, 63, -14), (2.0, 64, -8), (3.5, 62, -18)):
                    kit.n(b0 + beat, 0.5, note, v + dv)
            elif k % 2 == 0:
                for beat, note, dv in ((0.0, 64, 0), (1.5, 63, -14), (2.0, 64, -6), (3.0, 62, -16), (3.5, 63, -12)):
                    kit.n(b0 + beat, 0.5, note, v + dv)
            else:
                for beat, note, dv in ((0.0, 64, -2), (0.5, 62, -18), (1.5, 63, -12), (2.0, 64, -6), (3.5, 63, -14)):
                    kit.n(b0 + beat, 0.5, note, v + dv)

    def cabasa(bars, vel, gap=0.5):
        for bar in bars:
            for k in range(int(BPB / gap)):
                kit.n(s.b(bar) + k * gap, 0.2, 69, vel(bar) - (14 if k % 2 == 0 else 22))

    def bongo_fill(bar, vel):
        for k, (note, dv) in enumerate(((61, -10), (61, -6), (60, -8), (60, -2))):
            kit.n(s.b(bar, 4) + 0.25 * k, 0.2, note, vel + dv)

    def flourish(part, bar, beat, notes, step, vel, slope=2):
        """a run of the harp: one note every `step` beats from the beat, growing (or dying, a negative slope) as it goes"""
        for k, m in enumerate(notes):
            part.n(s.b(bar, beat) + step * k, 0.9, m, vel + slope * k)

    def ping(part, beat, spec, vel, min_d=2.0, tr=0):
        """a bell (celesta) on the long notes of a line, unison with it: a note's attack and its ring, no more"""
        b = beat
        for tok in spec.split():
            nm, d = tok.rsplit(":", 1)
            d = float(d)
            if nm != "R" and d >= min_d:
                part.line(b, f"{nm}:{min(d, 2.5)}", vel, tr=tr, leg=0.98)
            b += d

    # ================================================================ 1-8: dawn on the shingle
    arp(harp, range(1, 5), "q", V(46, 66))
    arp(harp, range(5, 9), "cycle", V(48, 66))
    cb.n(s.b(1), 32.2, 33, 46)  # a low A, held under all eight bars
    cb.expr([(0, 66), (s.b(5), 88), (s.b(9) - 0.1, 100)])
    vc.n(s.b(5), 16.1, 45, 52)
    vc.expr([(s.b(5), 70), (s.b(8), 98), (s.b(9) - 0.1, 86)])
    vc.expr([(s.b(5), 0), (s.b(7), 12), (s.b(9), 0)], cc=1)
    pad(range(1, 9), [(pad1, [0, 1]), (pad2, [2, 3])], pr, V(30, 48))
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(1, 9)), lo=62, hi=100, every=2)
    cel.line(s.b(4), "E6:1.5 A6:0.5 G6:1 F#6:1", 38, leg=0.98)
    sing(fl, s.b(5), "E5:4 A5:4 G5:2 F#5:2 E5:3.5 B4:0.5", 58, base=94, bump=10, vib=18, leg=0.99)
    bass(pizz, range(7, 9), 36, 47, V(44, 58), "boom")
    kit.n(s.b(8, 3), 0.3, 61, 30)
    kit.n(s.b(8, 4), 0.3, 60, 36)

    # ================================================================ 9-16: the tune
    arp(harp, TUNE1, "cycle", V(46, 62))
    sing(fl, s.b(9), TUNE_A1 + " " + TUNE_A2, 62, base=96, bump=10, vib=22)
    sing(fl, s.b(13), TUNE_B1 + " " + TUNE_B2, 70, base=104, bump=10, vib=22)
    bass(pizz, TUNE1, 36, 47, V(46, 62), "boom")
    pad(TUNE1, [(pad1, [0, 1]), (pad2, [2, 3])], pr, V(30, 46))
    for p_ in (pad1, pad2):
        swell(p_, s, list(TUNE1), lo=64, hi=102, every=2)
    roots(vc, range(13, 17), 38, 50, V(46, 62))
    vc.expr([(s.b(13), 74), (s.b(15), 100), (s.b(17) - 0.1, 88)])
    conga(TUNE1, "light", V(40, 56))
    cabasa(range(13, 17), V(36, 50))
    flourish(harp2, 12, 4, [64, 68, 71, 76], 0.25, 52)
    bongo_fill(16, 44)

    # ================================================================ 17-24: the tune again
    arp(gtr, TUNE2, "cycle", V(44, 58), lo=45, hi=70, dur=1.0)
    arp(harp, TUNE2, "q", V(48, 64))
    sing(fl, s.b(17), TUNE_A1 + " " + TUNE_A2_VAR, 66, base=100, bump=10, vib=24)
    sing(fl, s.b(21), TUNE_B1_LIFT + " " + TUNE_B2_LIFT, 76, base=108, bump=10, vib=24)
    sing(vns, s.b(21), TUNE_B1_LIFT + " " + TUNE_B2_LIFT, 46, base=98, bump=10, vib=14, tr=-12, leg=0.99)
    bass(pizz, TUNE2, 36, 47, V(48, 64), "boom")
    pad(TUNE2, [(pad1, [0, 1]), (pad2, [2, 3])], pr, V(34, 52))
    for p_ in (pad1, pad2):
        swell(p_, s, list(TUNE2), lo=66, hi=104, every=2)
    sing(vc, s.b(17), CELLO_2, 56, base=100, bump=8, vib=26, leg=0.99)  # a cello line under the flute: the roots are the pizzicato's
    conga(TUNE2, "flow", V(44, 60))
    cabasa(TUNE2, V(40, 54))
    ping(cel, s.b(21), TUNE_B1_LIFT + " " + TUNE_B2_LIFT, 38)
    flourish(harp2, 24, 4, [69, 73, 76, 81], 0.25, 50)
    bongo_fill(24, 48)

    # ================================================================ 25-32: spindrift
    arp(harp, SPINDRIFT, "sway", V(52, 72), lo=43, hi=72)
    stabs(gtr, SPINDRIFT, V(46, 62))
    sing(vns, s.b(25), SPIN_1, 74, base=104, bump=10, vib=20, leg=0.99)
    sing(vns, s.b(29), SPIN_2A, 80, base=110, bump=10, vib=22, leg=0.99)
    sing(vns, s.b(32), SPIN_END, 70, base=100, bump=8, vib=20, leg=0.99)
    sing(fl, s.b(25), SPIN_1, 60, base=98, bump=10, vib=22)
    sing(fl, s.b(29), SPIN_2A, 66, base=104, bump=10, vib=22)
    sing(fl, s.b(32), SPIN_END, 58, base=96, bump=8, vib=22)
    ping(cel, s.b(25), SPIN_1 + " " + SPIN_2A, 40)
    bass(pizz, SPINDRIFT, 36, 47, V(54, 70), "walk")
    pad(SPINDRIFT, [(pad1, [0, 1]), (pad2, [2, 3])], pr, V(38, 56))
    for p_ in (pad1, pad2):
        swell(p_, s, list(SPINDRIFT), lo=70, hi=108, every=2)
    roots(vc, SPINDRIFT, 38, 50, V(54, 74))
    vc.expr([(s.b(25), 90), (s.b(29), 110), (s.b(33) - 0.1, 90)])
    conga(SPINDRIFT, "flow", V(50, 66))
    cabasa(SPINDRIFT, V(46, 60))
    for bar in (26, 28, 30):
        kit.n(s.b(bar, 4) + 0.5, 0.2, 60, V(50, 66)(bar) - 20)
    flourish(harp2, 32, 4, [81, 80, 78, 76, 74, 73, 71, 69], 0.25, 54, slope=-3)  # the spray comes down: A major from the high A
    bongo_fill(32, 44)

    # ================================================================ 33-40: the long swell
    # a cello alone, then the guitar, the droplets, the flute remembering
    sing(vc, s.b(33), "A3:2 C#4:1 E4:1 F#4:3 E4:1 F4:2 E4:1 D4:1 C#4:3 R:1", 58, base=104, bump=10, vib=40, leg=0.99)
    arp(gtr, range(33, 41), "sway", V(42, 56), lo=45, hi=69, dur=1.2)
    cb.n(s.b(33), 32.2, 33, 44)
    cb.expr([(s.b(33), 76), (s.b(37), 90), (s.b(41), 100)])
    pad(range(33, 41), [(pad1, [0, 1]), (pad2, [2, 3])], pr, V(26, 40))
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(33, 41)), lo=58, hi=96, every=4)
    arp(harp, range(37, 41), "q", V(38, 52))
    for bar in range(33, 41):
        for _, off, dur, ch in segs([bar]):
            tones_c = [m for m in range(81, 97) if m % 12 in pcs(ch)]
            tones_h = [m for m in range(74, 90) if m % 12 in pcs(ch)]
            for part, tones, p_on, lo_v, hi_v in ((cel, tones_c, 0.75, 34, 52), (harp2, tones_h, 0.6, 38, 54)):
                if rng.random() < p_on:
                    part.n(s.b(bar) + off + rng.uniform(0.0, dur - 0.4), 1.5, int(rng.choice(tones)),
                           V(lo_v, hi_v)(bar) + int(rng.integers(-5, 6)))
    sing(fl, s.b(37), "E5:1.5 A5:0.5 G#5:1 F#5:3 E5:1 D5:1 C#5:1.5 D5:0.5 C#5:1 B4:1 A4:2 G#4:1 B4:1", 58, base=96, bump=10, vib=20, leg=0.99)
    roots(vc, range(37, 41), 38, 50, V(42, 60))
    vc.expr([(s.b(37), 70), (s.b(41) - 0.1, 100)])
    conga(range(33, 39, 2), "heart", V(28, 36))
    conga(range(39, 41), "light", V(34, 46))
    cabasa(range(39, 41), V(30, 40))
    bass(pizz, range(39, 41), 36, 47, V(42, 54), "boom")
    pad(range(39, 41), [(vns, [1, 2, 3])], pr, V(34, 48))
    vns.expr([(s.b(39), 60), (s.b(41) - 0.1, 104)])
    flourish(harp2, 40, 3, [64, 68, 71, 76, 80], 0.25, 46)
    bongo_fill(40, 40)

    # ================================================================ 41-48: home water
    arp(harp, range(41, 47), "cycle", V(48, 64))
    stabs(gtr, range(41, 47), V(40, 56))
    sing(fl, s.b(41), TUNE_A1 + " " + TUNE_A2, 64, base=98, bump=10, vib=24)
    sing(fl, s.b(45), TUNE_B1 + " D6:2 C#6:1 B5:1 A5:2", 70, base=102, bump=10, vib=24)
    sing(fl, s.b(48, 3), "E5:4", 52, base=84, bump=6, vib=20, leg=1.0)
    sing(vns, s.b(41), TUNE_A1 + " " + TUNE_A2, 46, base=98, bump=10, vib=14, tr=-12, leg=0.99)
    bass(pizz, range(41, 47), 36, 47, V(48, 62), "boom")
    pad(range(41, 49), [(pad1, [0, 1]), (pad2, [2, 3])], pr, V(36, 50))
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(41, 49)), lo=62, hi=102, every=2)
    roots(vc, range(41, 49), 38, 50, V(48, 64))
    vc.expr([(s.b(41), 84), (s.b(45), 100), (s.b(49) - 0.1, 70)])
    arp(harp, range(47, 49), "q", V(42, 54))
    conga(range(41, 47), "flow", V(44, 58))
    cabasa(range(41, 47), V(38, 50))
    kit.n(s.b(47), 0.5, 64, 32)
    ping(cel, s.b(45), "E6:1.5 A6:0.5 B6:1 C#7:4", 36)
    cb.n(s.b(47), 8.4, 33, 40)  # the low A under the last two bars goes on into the first
    return s


# =========================================================================== the master
def stem_fx(y, ops):
    """channel processing of a stem: ('hp', f) or ('eq', kind, f, q, gain_db)"""
    y = y.copy()
    for op in ops:
        for c in range(y.shape[0]):
            y[c] = hp(y[c], op[1]) if op[0] == "hp" else eq(y[c], *op[1:])
    return y


def premaster(stems, settings, ir, n_out):
    """sum the stems with their sends into the hall, EQ and glue them as make_music.mix does (leaving the level to
    `finish`); what runs past n_out is folded over the start"""
    n = max(y.shape[1] for y in stems.values())
    dry = np.zeros((2, n))
    send = np.zeros(n)
    for name, y in stems.items():
        g_db, snd = settings[name]
        y = np.pad(y, ((0, 0), (0, n - y.shape[1])))
        y = stem_fx(y, STEM_FX.get(name, []))
        dry += db(g_db) * y
        send += snd * db(g_db) * y.mean(axis=0)
    send = hp(send, 160)
    wet = np.vstack([signal.fftconvolve(send, ir[0])[:n], signal.fftconvolve(send, ir[1])[:n]])
    out = dry + wet
    if n_out < n:
        tail = out[:, n_out:]
        out = out[:, :n_out].copy()
        k = min(tail.shape[1], n_out)
        out[:, :k] += tail[:, :k]
    else:
        out = np.pad(out, ((0, 0), (0, n_out - n)))
    for c in range(2):
        out[c] = hp(out[c], 30, order=2)
        out[c] = eq(out[c], "peak", 320, 0.8, -2.0)
        out[c] = eq(out[c], "highshelf", 6000, 0.7, 1.5)
    t_bar = np.arange(n_out) / (SR * 60.0 / BPM * BPB) + 1.0  # (the ride is circular: its end is its start)
    out = out * db(np.interp(t_bar, [a for a, _ in RIDE], [b for _, b in RIDE]))
    out /= np.max(np.abs(out)) + 1e-12
    out = stereo_comp(out, -14.0, 1.6)
    return out / (np.max(np.abs(out)) + 1e-12)


def finish(out, ceiling, g=1.0, target=None, fade_in=FADE_IN, fade_out=FADE_OUT):
    """set the integrated loudness to `target` (default LOUDNESS) by measurement, limiting peaks at `ceiling` dBFS, then round
    the ends: returns the finished stereo signal and the gain it ended with"""
    target = LOUDNESS if target is None else target
    for _ in range(4):
        y = stereo_limit(out * g, ceiling, lookahead=0.005, release=0.15)
        err = target - lufs(y)
        if abs(err) < 0.03:
            break
        g *= db(err)
    y = stereo_limit(out * g, ceiling, lookahead=0.005, release=0.15)
    for c in range(2):
        y[c] = fade(y[c], fin=fade_in, fout=fade_out)
    return y, g


def render_stems(song, name, work, sf2, reuse=False):
    """every stem of the song rendered dry by FluidSynth into `work` (reuse: those already there are read, not rendered again,
    for working on the mix)"""
    os.makedirs(work, exist_ok=True)
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        mid = os.path.join(work, f"{name}_{stem}.mid")
        wav = os.path.join(work, f"{name}_{stem}.wav")
        if reuse and os.path.exists(wav):
            stems[stem] = wavfile.read(wav)[1].astype(np.float64).T
            continue
        export_stem(song, stem, mid)
        stems[stem] = render(mid, wav, sf2)
    return stems


def listen_mp3(y, path, song):
    """the theme as an MP3 to listen to on a phone, a chapter mark at each section; then the turn of the loop: the last ten
    seconds of the file and the first fourteen, joined"""
    marks = []
    for k, (bar, title) in enumerate(CHAPTERS):
        a = song.b(bar) * song.spb
        b = (song.b(CHAPTERS[k + 1][0]) * song.spb) if k + 1 < len(CHAPTERS) else y.shape[1] / SR
        marks.append((a, b, f"{bar:02d} {title}"))
    join = np.concatenate([y[:, -int(10 * SR):], y[:, :int(14 * SR)]], axis=1)
    marks.append((y.shape[1] / SR, y.shape[1] / SR + join.shape[1] / SR, "loop join: the last 10 s, then the start again"))
    y = np.concatenate([y, join], axis=1)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "t.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "t.mp3")
        write_wav(wav, y.astype(np.float32))
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Sun and Spindrift\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\ntitle={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1", "-map_chapters", "1",
                        "-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "3", "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3],
                       check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)
    print(f"{path}  {y.shape[1] / SR:.1f}s, {os.path.getsize(path) / 1e6:.2f} MB")


def build(assets, work=None, sf2=None, wav_dir=None, mp3=None, reuse=False):
    sf2 = sf2 or SF2_DEFAULT
    song = compose_isles()
    if work:
        stems = render_stems(song, "isles", work, sf2, reuse)
    else:
        with tempfile.TemporaryDirectory() as td:
            stems = render_stems(song, "isles", td, sf2)
    n_out = int(round(song.end_beat * song.spb * SR))  # exactly the 48 bars: the file is one turn of the loop
    ir = hall_ir(rt60=2.7, seed=127)
    pre = premaster(stems, ISLES_MIX, ir, n_out)
    path = os.path.join(assets, "sounds", OUT + ".ogg")
    # the encoder overshoots the limiter a little and takes a little of the loudness: measure the decoded file and move the
    # limiter's ceiling and the loudness target until its true peak is under TRUE_PEAK and its loudness on LOUDNESS (at this
    # level the piece peaks well under the ceiling and the limiter has nothing to do)
    ceiling, g, target = TRUE_PEAK - 1.0, 1.0, LOUDNESS
    for _ in range(6):
        out, g = finish(pre, ceiling, g, target)
        write_ogg(path, out, quality=QUALITY)
        dec = read_audio(path)
        tp, loud = true_peak(dec), lufs(dec)
        if tp <= TRUE_PEAK and abs(loud - LOUDNESS) <= 0.12:
            break
        if tp > TRUE_PEAK:
            ceiling -= (tp - TRUE_PEAK) + 0.2
        target += LOUDNESS - loud
    if wav_dir:
        write_wav(os.path.join(wav_dir, OUT + ".wav"), out)
    print(f"{path}  {dec.shape[1] / SR:.3f}s ({dec.shape[1]} samples)  {loud:.2f} LUFS  true peak {tp:.2f} dBFS", flush=True)
    if mp3:
        listen_mp3(dec, mp3, song)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--sf2", default=SF2_DEFAULT)
    ap.add_argument("--work", default=None)
    ap.add_argument("--wav", default=None)
    ap.add_argument("--mp3", default=None, help="also write the theme as an MP3 to listen to")
    ap.add_argument("--reuse", action="store_true", help="with --work: use the stems already rendered there (to work on the mix)")
    a = ap.parse_args()
    build(a.assets, a.work, a.sf2, a.wav, a.mp3, a.reuse)


if __name__ == "__main__":
    main()
