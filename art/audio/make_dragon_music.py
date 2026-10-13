"""The Unbowed: the dragon's battle theme, composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 -B art/audio/make_dragon_music.py src/main/resources/assets/guildmark [--sf2 FluidR3_GM.sf2]
        [--work DIR] [--wav DIR]

It writes sounds/music/dragon.ogg. (make_dragon_audio.py runs it too, after the effects.) The building blocks come
from make_music.py: parts and songs, per-stem MIDI export with seeded humanising, FluidSynth rendering, the synthetic
hall, the glue compressor and the limiter. This script adds the piece and a master that sets the loudness by
measurement (ITU-R BS.1770, gated) to the level of the boss tracks (they measure -9.6 to -11.2 LUFS).

The piece is C minor, in 4/4 at 90 BPM, 72 bars (3:12, and 3:14 with the last chord's tail), and it is built on
one idea, the dragon's call: a horn climbing from the tonic by a fifth, then a semitone and a third up to a cry,
and sinking back by way of the flat second (the Phrygian D flat over C) to the dominant:
C - G - A flat - C - E flat ... D flat - C - G.
Everything else grows out of it. The low strings' ostinato is a 3+3+2 sixteenth-note drive whose accents spell the
call's head (root, fifth, the note above it) and whose pairs of bars lean on the flat second; the low brass riff, the
tread of the thing, leans on it too; the bridge sequences the call's first leap upward (C-G, D flat-A flat, E flat-
B flat, F-C, G-D) while everything gathers; the last statement lifts it into C Dorian (A natural for A flat) and
climbs a fifth higher than ever.

Form (bars): the awakening (1-8) a low C and a heartbeat on the timpani, the call on a far horn, the choir
rising on D flat; the tread and the call (9-24) the ostinato, the riff and the call in horns, twice; the old
chant (25-40) a slow choir tune over A flat, E flat, B flat, F minor with the ostinato at half the speed; the
hunt (41-48) the call's head climbing, the strings shrieking, drums gathering, and one beat of silence, the
dragon drawing breath; the unbowed (49-64) the call in everything, the riff, the lifted call at its height, the
tread once more; the echo (65-72) the call dies away on a horn and the piece comes to rest on D flat, the
Phrygian cadence: the piece loops from the start, so the D flat falls a semitone to the C and the heartbeat of
the first bar.
"""
import argparse
import os
import sys

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_music as mm  # noqa: E402
from make_music import (P, SF2_DEFAULT, Song, export_stem, hall_ir, in_range, pads, pcs, render,  # noqa: E402
                        roll, stereo_comp, stereo_limit, swell)
from make_menu_music import lufs, true_peak  # noqa: E402
from sfxkit import SR, db, eq, fade, hp, read_audio, write_ogg, write_wav  # noqa: E402

mm.CHORDS.update({"G7": "G B D F"})

BPM = 90
BARS = 72
OUT = "music/dragon"
LOUDNESS = -10.4  # LUFS integrated: the four boss tracks measure -9.6 to -11.2
TRUE_PEAK = -0.6  # dBFS, measured on the decoded Ogg (the other boss tracks decode to about -0.5)
QUALITY = 5

# sections (bars, 1-based)
INTRO, A1, B, BRIDGE, A2, OUTRO = (range(1, 9), range(9, 25), range(25, 41), range(41, 49), range(49, 65),
                                   range(65, 73))

# one chord per bar
PROG = (["Cm", "Cm", "Cm", "Cm", "Db", "Cm", "Fm", "G"]                      # the awakening
        + ["Cm", "Cm", "Db", "Cm", "Cm", "Ab", "Db", "G"] * 2                 # the tread (9-12) and the call (13-16), twice
        + ["Ab", "Eb", "Bb", "Fm", "Ab", "Eb", "Bb", "G"]                     # the old chant
        + ["Ab", "Eb", "Bb", "Fm", "Ab", "Eb", "Db", "G"]
        + ["Cm", "Db", "Cm", "Db", "Ab", "Bb", "G", "G"]                      # the hunt
        + ["Cm", "Ab", "Db", "G", "Cm", "Cm", "Db", "Cm"]                     # the unbowed: the call, then the tread
        + ["Cm", "F", "Ab", "Bb", "Cm", "Db", "Cm", "G"]                      # the lifted call, the last tread
        + ["Cm", "Cm", "Ab", "Ab", "Db", "Db", "Db", "Db"])                   # the echo, and rest on D flat
assert len(PROG) == BARS

# (bar, intensity 0..1): the arc of the piece, as the other tracks do it, driving velocities
ARC = [(1, .26), (4, .34), (8, .56), (9, .70), (16, .76), (17, .80), (24, .88), (25, .60), (32, .68), (33, .72),
       (40, .84), (41, .66), (47, .92), (48, .96), (49, 1.0), (64, 1.0), (65, .60), (68, .46), (72, .30)]

# the call (four bars: harmony Cm, A flat, D flat, G), sounding pitch for the first horn
CALL = "C3:2 G3:1 Ab3:1 C4:2 Eb4:2 Db4:3 C4:1 G3:3 C3:1"
# the call lifted into Dorian and climbing a fifth higher (harmony Cm, F, A flat, B flat)
CALL_LIFT = "C3:2 G3:1 A3:1 C4:2 F4:1 G4:1 C5:3 Bb4:1 D5:2 C5:1 Bb4:1"
# the tread: a riff of the low brass (absolute pitches, harmony Cm, Cm, D flat, Cm)
RIFF = ["C2:1.5 Db2:0.5 C2:1 Eb2:1", "C2:1.5 Bb1:0.5 C2:1 G1:1", "Db2:1.5 C2:0.5 Db2:1 F2:1", "C2:1.5 G1:0.5 C2:1 G1:1"]
# the last tread (harmony Cm, D flat, Cm, G) gives way to the dominant
RIFF_LAST = ["C2:1.5 Db2:0.5 C2:1 Eb2:1", "Db2:1.5 C2:0.5 Db2:1 F2:1", "C2:1.5 Bb1:0.5 C2:1 G1:1", "G1:3 D2:1"]
# the old chant, a choir tune over Ab Eb Bb Fm Ab Eb Bb G (sounding pitch)
CHANT = ("C5:3 Bb4:1 G4:2 Bb4:2 D5:3 C5:1 C5:2 Ab4:2 Eb5:3 C5:1 Bb4:2 G4:2 D5:2 F5:2 D5:3 B4:1")
CHANT_END = ("C5:3 Bb4:1 G4:2 Bb4:2 D5:3 C5:1 C5:2 Ab4:2 Eb5:3 C5:1 Bb4:2 G4:2 Ab4:2 C5:2 D5:4")

# for each chord root (pitch class): the note over the fifth the ostinato's first accent group climbs to (the call's
# head: root, fifth, and a step above it), and the one it drops to under the root in the second group
UP = {0: 8, 1: 9, 3: 9, 5: 9, 7: 8, 8: 9, 10: 9}
LOW = {0: -2, 1: -1, 3: -1, 5: -2, 7: -2, 8: -1, 10: -2}
ACCENTS = (0, 3, 6, 8, 11, 14)  # 3+3+2+3+3+2 of sixteen steps
# the taiko, tuned: the note number whose sample sounds each root an octave or so below the timpani
TAIKO = {0: 28, 1: 30, 3: 33, 5: 38, 7: 42, 8: 43, 10: 48}
TIMP = {0: 36, 1: 37, 3: 39, 5: 41, 7: 31, 8: 32, 10: 34}
TOMS = [41, 43, 45, 47, 48, 50]  # the power kit's toms, low to high

# per-stem (gain dB, reverb send). Balanced from the measured active RMS of the stems, as the other boss tracks are
DRAGON_MIX = {"low": (3.0, 0.14), "high": (1.0, 0.30), "brass": (-2.0, 0.24), "choir": (2.0, 0.40),
              "perc": (2.5, 0.16), "war": (0.0, 0.14), "color": (4.0, 0.40), "far": (-3.0, 0.90)}


def compose_dragon():
    s = Song("dragon", bpm=BPM, bars=BARS, tail=1.8)
    inten = np.interp(np.arange(1, BARS + 1), [a for a, _ in ARC], [b for _, b in ARC])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[bar - 1]

    def root(bar, lo, hi):
        return in_range(pcs(PROG[bar - 1])[0], lo, hi)

    def rpc(bar):
        return pcs(PROG[bar - 1])[0]

    # ---- the players
    vc = s.part("vc", 42, "low", pan=70, volume=110, hum=0.006, vjit=4)
    cb = s.part("cb", 43, "low", pan=88, volume=110, hum=0.006, vjit=4)
    bsn = s.part("bsn", 70, "low", pan=58, volume=98, hum=0.008)
    org = s.part("org", 19, "low", pan=64, volume=92, hum=0.004)
    vla = s.part("vla", 41, "high", pan=56, volume=100, hum=0.006, vjit=4)
    vn = s.part("vn", 40, "high", pan=34, volume=104, lag=0.012, hum=0.012)
    pad1 = s.part("pad1", 49, "high", pan=46, volume=94, hum=0.02)
    pad2 = s.part("pad2", 49, "high", pan=82, volume=94, hum=0.02)
    trem = s.part("trem", 44, "high", pan=40, volume=90, hum=0.01)
    hn1 = s.part("hn1", 60, "brass", pan=40, volume=112, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=54, volume=106, lag=0.014)
    hn3 = s.part("hn3", 60, "brass", pan=46, volume=104, lag=0.012)
    hn4 = s.part("hn4", 60, "brass", pan=58, volume=102, lag=0.014)
    tbn1 = s.part("tbn1", 57, "brass", pan=80, volume=108, lag=0.01)
    tbn2 = s.part("tbn2", 57, "brass", pan=70, volume=104, lag=0.012)
    tuba = s.part("tuba", 58, "brass", pan=88, volume=106, lag=0.01)
    tpt1 = s.part("tpt1", 56, "brass", pan=62, volume=92, lag=0.008)
    tpt2 = s.part("tpt2", 56, "brass", pan=50, volume=88, lag=0.008)
    ahh1 = s.part("ahh1", 52, "choir", pan=56, volume=104, hum=0.02)
    ahh2 = s.part("ahh2", 52, "choir", pan=40, volume=100, hum=0.02)
    ohh = s.part("ohh", 53, "choir", pan=72, volume=98, hum=0.02)
    timp = s.part("timp", 47, "perc", pan=58, volume=114, hum=0.005, vjit=3)
    taiko = s.part("taiko", 116, "perc", pan=64, volume=112, hum=0.005, vjit=4)
    # Drum kit 48 is FluidR3's Orchestra Kit: 35/36 bass drum, 38 snare, 55 splash, 57/59 crash; 41 to 53 are tuned
    # timpani there, not toms or cymbals
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=106, hum=0.004, vjit=4)
    # kit 16 is the Power Kit: big, close toms and a deep kick
    toms = s.part("toms", 16, "war", drum=True, pan=52, volume=104, hum=0.005, vjit=5)
    bells = s.part("bells", 14, "color", pan=62, volume=84)
    sweep = s.part("sweep", 119, "color", pan=64, volume=96)
    far = s.part("far", 60, "far", pan=60, volume=104, lag=0.014)

    # ================================================================ the ostinato and its drums
    def ostinato(bars, lo=36, hi=47, part=vc, vel=V(44, 92), ghost=-20, acc=+12, bass=False, oct_=0):
        """sixteenths, 3+3+2+3+3+2: every note on the root but the accents, which spell root, fifth and the note
        above it, then root, fifth and the note under it"""
        for bar in bars:
            r = root(bar, lo, hi) + oct_
            pc = rpc(bar)
            pat = [0, 0, 0, 7, 0, 0, UP[pc], 0, 0, 0, 0, 7, 0, 0, LOW[pc], 0]
            v = vel(bar)
            for i, iv in enumerate(pat):
                a = i in ACCENTS
                if bass:
                    if a:
                        part.n(s.b(bar) + i * 0.25, 0.5, r, v + 8)
                    continue
                part.n(s.b(bar) + i * 0.25, 0.3 if a else 0.2, r + iv, v + (acc if a else ghost))

    def tread_drums(bars, vel, extra=True, snare=False, toms_on=False, boom=True):
        for bar in bars:
            pc = rpc(bar)
            v = vel(bar)
            for beat, dv in ((0, 0), (1.5, -14), (2.0, -4), (2.75, -18), (3.5, -12)):
                taiko.n(s.b(bar) + beat, 0.5, TAIKO[pc], v + dv)
            timp.n(s.b(bar), 1.4, TIMP[pc], v)
            timp.n(s.b(bar, 3), 1.0, TIMP[pc], v - 10)
            if extra:
                timp.n(s.b(bar, 4.5), 0.5, TIMP[pc] + 7 if TIMP[pc] + 7 <= 43 else TIMP[pc] - 5, v - 24)
            if boom:
                kit.n(s.b(bar), 1, 36, v - 4)
                kit.n(s.b(bar, 3), 1, 36, v - 14)
            if snare:
                kit.n(s.b(bar, 2), 0.5, 38, v - 38)
                kit.n(s.b(bar, 4), 0.5, 38, v - 34)
            if toms_on:
                for beat, tom, dv in ((0.75, 2, -16), (1.5, 4, -10), (2.75, 3, -14), (3.5, 1, -8)):
                    toms.n(s.b(bar) + beat, 0.4, TOMS[tom], v + dv)

    def fill(bar, beat, vel, n=4, rate=0.25, tl=(5, 4, 3, 2)):
        """a run of toms falling down the end of a bar, getting louder"""
        for i in range(n):
            toms.n(s.b(bar, beat) + i * rate, 0.4, TOMS[tl[i % len(tl)]], vel * (0.65 + 0.35 * (i + 1) / n))

    def reverse(target, vel=92):
        """a reverse cymbal swelling into the beat `target`: the sample is a one-shot (it does not loop) that peaks
        1.36 s, 2.04 beats at this tempo, after it starts"""
        sweep.n(target - 2.04, 2.0, "C4", vel)

    # ================================================================ 1-8: the awakening
    for bar in range(1, 5):  # a low C and a heartbeat
        org.n(s.b(bar), 4.1, "C2", V(58, 70)(bar))
        cb.n(s.b(bar), 4.1, "C1", V(60, 74)(bar))
        timp.n(s.b(bar), 1.2, 36, V(76, 94)(bar))
        timp.n(s.b(bar, 1.75), 1.0, 31, V(62, 78)(bar))
    org.expr([(0, 78), (s.b(5), 100)])
    cb.expr([(0, 84), (s.b(5), 108)])
    tuba.line(s.b(3), "C2:8", 66, leg=1.0)
    tuba.expr([(s.b(3), 78), (s.b(5), 104), (s.b(7), 88)])
    # the call, far off
    far.line(s.b(3), CALL, 66, tr=0, leg=0.99)
    far.expr([(s.b(3), 70), (s.b(4), 104), (s.b(5), 112), (s.b(6), 96), (s.b(7) - 0.1, 78)])
    # the choir rises on D flat, falls to C, and gathers
    cr = [(48, 60), (53, 65), (57, 69), (62, 74)]
    pads([(ohh, [0, 1, 2, 3])], s, list(range(5, 9)), PROG, cr, V(50, 80))
    swell(ohh, s, list(range(5, 9)), lo=76, hi=118, every=2, shape="cresc")
    pads([(ahh1, [2, 3])], s, [7, 8], PROG, cr, V(56, 80))
    ahh1.expr([(s.b(7), 80), (s.b(9) - 0.1, 120)])
    bells.n(s.b(5), 8, "Db4", 54)
    # drums: the heartbeat gains weight, a quiet snare roll and a timpani roll draw the breath
    for bar in (5, 6, 7):
        taiko.n(s.b(bar), 0.5, TAIKO[rpc(bar)], V(62, 80)(bar))
        timp.n(s.b(bar), 1.2, TIMP[rpc(bar)], V(78, 96)(bar))
        timp.n(s.b(bar, 1.75), 1.0, TIMP[rpc(bar)] - 5, V(64, 80)(bar))
    roll(kit, s, s.b(7, 3), 6, 38, 36, 90, rate=0.25)
    roll(timp, s, s.b(8), 4, TIMP[7], 56, 112, rate=0.25)
    kit.n(s.b(9), 4, 57, 108)
    reverse(s.b(9))
    cb.line(s.b(5), "Db1:4 C1:4 F1:4 G1:4", 72, leg=1.0)
    org.line(s.b(5), "Db2:4 C2:4 F2:4 G2:4", 68, leg=1.0)

    # ================================================================ 9-24: the tread and the call
    ostinato(range(9, 25), part=vc, vel=V(46, 90))
    ostinato(range(9, 25), lo=24, hi=35, part=cb, vel=V(50, 94), bass=True)
    ostinato(range(9, 25), lo=48, hi=59, part=bsn, vel=V(40, 78), ghost=-60, acc=0, bass=True)
    ostinato(range(17, 25), lo=48, hi=59, part=vla, vel=V(38, 70))
    for k, bars in enumerate((range(9, 13), range(17, 21))):  # the tread: the riff in low brass
        for j, bar in enumerate(bars):
            tuba.line(s.b(bar), RIFF[j], V(78, 104)(bar), leg=0.96, accent=8)
            tbn1.line(s.b(bar), RIFF[j], V(72, 98)(bar), tr=12, leg=0.96, accent=8)
            bsn.line(s.b(bar), RIFF[j], V(60, 84)(bar), tr=12, leg=0.9)
            if k:
                tbn2.line(s.b(bar), RIFF[j], V(70, 94)(bar), tr=19, leg=0.94)
                hn3.line(s.b(bar), RIFF[j], V(66, 92)(bar), tr=24, leg=0.92)
        for bar in bars:
            hn3.chord(s.b(bar), 3.9, [P("G3"), P("Eb4")] if rpc(bar) == 0 else [P("Ab3"), P("F4")], V(48, 66)(bar))
    for bars, k in ((range(13, 17), 0), (range(21, 25), 1)):  # the call in horns over the ostinato
        b0 = s.b(bars[0])
        hn1.line(b0, CALL, V(84, 100)(bars[0]), leg=0.99, accent=8)
        hn2.line(b0, CALL, V(78, 94)(bars[0]), leg=0.99)
        tbn1.line(b0, CALL, V(62, 80)(bars[0]), tr=-12, leg=0.99)
        hn1.expr([(b0, 96), (b0 + 4, 110), (b0 + 8, 122), (b0 + 12, 104), (b0 + 16, 96)])
        hn2.expr([(b0, 96), (b0 + 4, 110), (b0 + 8, 122), (b0 + 12, 104), (b0 + 16, 96)])
        for bar, nt in zip(bars, ("C2", "Ab1", "Db2", "G1")):
            tuba.n(s.b(bar), 3.95, nt, V(74, 98)(bar))
        if k:
            tpt1.line(b0, CALL, 80, tr=12, leg=0.98)
            vn.line(b0, CALL, 78, tr=12, leg=1.0)
            tpt1.expr([(b0, 90), (b0 + 8, 118), (b0 + 16, 100)])
    # horns and strings hold the harmony under the tread and the call
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, list(range(9, 25)), PROG, [(50, 62), (55, 67), (60, 72), (64, 76)],
         V(36, 64))
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(9, 25)), lo=70, hi=108)
    pads([(ohh, [0, 1, 2, 3])], s, list(range(9, 25)), PROG, cr, V(34, 62))
    swell(ohh, s, list(range(9, 25)), lo=64, hi=108)
    pads([(ahh2, [1, 2, 3])], s, list(range(21, 25)), PROG, cr, V(60, 86))
    swell(ahh2, s, list(range(21, 25)), lo=76, hi=122, every=4, shape="cresc")
    for bar in (11, 19):
        bells.n(s.b(bar), 4, "Db4", 52)
    # drums
    tread_drums(range(9, 17), V(72, 104), extra=True)
    tread_drums(range(17, 25), V(78, 110), extra=True, toms_on=True)
    kit.n(s.b(9), 4, 57, 110)
    kit.n(s.b(17), 4, 57, 112)
    kit.n(s.b(13), 3, 55, 84)
    kit.n(s.b(21), 3, 55, 90)
    for bar in (12, 16, 20):
        fill(bar, 4.0, 84)
    roll(timp, s, s.b(24, 3), 2, TIMP[7], 70, 118, rate=0.125)
    fill(24, 3.0, 96, n=8, tl=(5, 4, 3, 2, 1, 0, 1, 2))
    reverse(s.b(25), 84)

    # ================================================================ 25-40: the old chant
    # the ostinato at half the speed: eighths on the root, the call's head on the accents of each half-bar
    for bar in B:
        r = root(bar, 36, 47)
        pc = rpc(bar)
        v = V(40, 74)(bar)
        for i, iv in enumerate([0, 0, 7, 0, 0, 0, UP[pc], 0]):
            vc.n(s.b(bar) + i * 0.5, 0.4, r + iv, v + (10 if i in (0, 2, 6) else -12))
        for beat in (0, 2):
            cb.n(s.b(bar) + beat, 1.9, r - 12, V(54, 84)(bar))
    ostinato(range(37, 41), part=vla, lo=48, hi=59, vel=V(38, 66), ghost=-14, acc=6)
    for p_ in (vc,):
        p_.expr([(s.b(25), 96), (s.b(33), 108), (s.b(41) - 0.1, 112)])
    for bar in B:
        tuba.n(s.b(bar), 3.95, root(bar, 24, 35), V(58, 84)(bar))
    brv = [(45, 55), (50, 60)]
    pads([(tbn1, [1]), (tbn2, [0])], s, list(B), PROG, brv, V(50, 78))
    swell(tbn1, s, list(B), lo=74, hi=108)
    swell(tbn2, s, list(B), lo=74, hi=108)
    pads([(hn3, [1]), (hn4, [0])], s, list(B), PROG, [(53, 64), (58, 69)], V(50, 80))
    swell(hn3, s, list(B), lo=76, hi=112)
    swell(hn4, s, list(B), lo=76, hi=112)
    # the tune
    ahh1.line(s.b(25), CHANT, 84, leg=1.0, accent=4)
    ahh1.line(s.b(33), CHANT_END, 94, leg=1.0, accent=4)
    ahh2.line(s.b(25), CHANT, 76, tr=-12, leg=1.0)
    ahh2.line(s.b(33), CHANT_END, 86, tr=-12, leg=1.0)
    for a0 in (25, 33):
        ahh1.expr([(s.b(a0), 92), (s.b(a0 + 3), 112), (s.b(a0 + 4), 96), (s.b(a0 + 7), 118), (s.b(a0 + 8) - 0.1, 100)])
        ahh2.expr([(s.b(a0), 92), (s.b(a0 + 3), 112), (s.b(a0 + 4), 96), (s.b(a0 + 7), 118), (s.b(a0 + 8) - 0.1, 100)])
    pads([(ohh, [0, 1, 2, 3])], s, list(B), PROG, cr, V(44, 74))
    swell(ohh, s, list(B), lo=66, hi=112)
    hn1.line(s.b(33), CHANT_END, 82, leg=1.0)
    hn2.line(s.b(33), CHANT_END, 76, tr=-12, leg=1.0)
    vn.line(s.b(33), CHANT_END, 86, tr=12, leg=1.0)
    for p_ in (hn1, hn2, vn):
        p_.expr([(s.b(33), 92), (s.b(36), 112), (s.b(37), 98), (s.b(40), 122), (s.b(41) - 0.1, 100)])
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, list(B), PROG, [(50, 62), (55, 67), (60, 72), (64, 76)], V(40, 70))
    for p_ in (pad1, pad2):
        swell(p_, s, list(B), lo=72, hi=112)
    # the call's fragment answering in horn at the ends of the phrases
    hn1.line(s.b(31), "G3:1 Ab3:1 C4:2", 78, leg=0.99)
    hn2.line(s.b(31), "G3:1 Ab3:1 C4:2", 72, leg=0.99)
    tpt1.line(s.b(39), "Db4:2 F4:1 Ab4:1 D5:4", 84, leg=0.99)
    # drums: half time
    for bar in B:
        pc = rpc(bar)
        v = V(64, 100)(bar)
        timp.n(s.b(bar), 1.6, TIMP[pc], v)
        timp.n(s.b(bar, 3), 1.2, TIMP[pc], v - 12)
        taiko.n(s.b(bar), 0.5, TAIKO[pc], v - 10)
        kit.n(s.b(bar), 1, 36, v - 14)
    for bar in (25, 33):
        kit.n(s.b(bar), 4, 57, 96)
    for bar in (29, 37):
        kit.n(s.b(bar), 3, 55, 78)
    for bar in (28, 36):
        roll(timp, s, s.b(bar, 3), 2, TIMP[rpc(bar)], 52, 98, rate=0.125)
    roll(timp, s, s.b(32, 3), 2, TIMP[7], 56, 104, rate=0.125)
    roll(timp, s, s.b(40, 1), 4, TIMP[7], 56, 118, rate=0.125)
    roll(kit, s, s.b(39), 8, 38, 36, 100, rate=0.25)
    reverse(s.b(41), 88)
    bells.n(s.b(25), 8, "Eb4", 46)

    # ================================================================ 41-48: the hunt
    hold = s.b(48, 4.0)  # the last beat is silence: the dragon draws breath
    for bar in BRIDGE:
        r = root(bar, 36, 47)
        v = V(56, 96)(bar)
        for beat in (0, 0.75, 1.5, 2.0, 2.75, 3.5):  # the ostinato, shortened to its accents: stabs
            if s.b(bar) + beat + 0.3 > hold:
                continue
            vc.n(s.b(bar) + beat, 0.3, r, v + (6 if beat in (0, 2.0) else -4))
            cb.n(s.b(bar) + beat, 0.3, r - 12, v + 4)
        tuba.n(s.b(bar), 1.4, root(bar, 24, 35), v + 6)
        tbn1.n(s.b(bar), 1.4, r, v + 6)
        tbn2.n(s.b(bar), 1.4, r + 7, v)
        if bar < 48:
            tuba.n(s.b(bar, 3), 0.9, root(bar, 24, 35), v)
            tbn1.n(s.b(bar, 3), 0.9, r, v)
    # the call's first leap, climbing: C-G, D flat-A flat, then up through E flat, F, G
    shouts = [(41, "C4", "G4"), (42, "Db4", "Ab4"), (43, "C4", "G4"), (44, "Db4", "Ab4"), (45, "Eb4", "Bb4"),
              (46, "F4", "C5"), (47, "G4", "D5")]
    for bar, lo_, hi_ in shouts:
        v = V(70, 108)(bar)
        spec = f"{lo_}:1 {hi_}:{3 if bar < 47 else 2}"
        for p_, tr, dv_ in ((hn1, 0, 0), (hn2, 0, 0), (tpt1, 0, 0), (hn3, -12, 6)):
            p_.line(s.b(bar), spec, v - dv_, tr=tr, leg=0.97)
        tbn1.line(s.b(bar), spec, v - 10, tr=-12, leg=0.97)
    for p_ in (hn1, hn2, tpt1):
        p_.n(s.b(48), 2.95, "D5", 112)  # the top of the climb, held till the breath
        p_.expr([(s.b(41), 100), (s.b(45), 116), (hold, 126)])
    # the strings shriek: tremolo climbing, violins trilling high
    tv = mm.voice_lead([PROG[b - 1] for b in BRIDGE], [(64, 74), (69, 79)])
    for bar, vc_ in zip(BRIDGE, tv):
        trem.chord(s.b(bar), 4.05 if bar < 48 else 2.95, vc_, 56 + 7 * (bar - 41))
    trem.expr([(s.b(41), 64), (hold, 124)])
    for bar, a_, b_ in ((43, "Db6", "C6"), (44, "Db6", "C6"), (47, "G6", "Ab6"), (48, "G6", "Ab6")):
        for i in range(12):
            vn.n(s.b(bar) + i * 0.25, 0.24, a_ if i % 2 == 0 else b_, 70 + 3 * (bar - 41) + i)
    vn.expr([(s.b(43), 80), (hold, 124)])
    pads([(ahh1, [2, 3])], s, list(range(45, 48)), PROG, cr, V(56, 100))
    for i in (2, 3):
        ahh1.n(s.b(48), 2.95, mm.voice_lead([PROG[47]], cr)[0][i], 104)
    ahh1.expr([(s.b(45), 90), (hold, 126)])
    pads([(ohh, [0, 1])], s, list(range(41, 45)), PROG, cr, V(40, 68))
    # drums gather
    for bar in range(41, 45):
        pc = rpc(bar)
        v = V(66, 100)(bar)
        timp.n(s.b(bar), 1.4, TIMP[pc], v)
        timp.n(s.b(bar, 3), 1.2, TIMP[pc], v - 10)
        taiko.n(s.b(bar), 0.5, TAIKO[pc], v)
        taiko.n(s.b(bar, 3), 0.5, TAIKO[pc], v - 8)
        kit.n(s.b(bar), 1, 36, v - 6)
    roll(timp, s, s.b(44, 3), 2, TIMP[0], 50, 100, rate=0.125)
    for bar in range(45, 49):
        pc = rpc(bar)
        v = V(76, 112)(bar)
        for beat in (0, 1, 2, 3):
            if s.b(bar, 1 + beat) >= hold:
                continue
            toms.n(s.b(bar, 1 + beat), 0.4, TOMS[(beat + bar) % 3], v - 8 + 2 * beat)
            taiko.n(s.b(bar, 1 + beat), 0.5, TAIKO[pc], v - 6)
    roll(kit, s, s.b(45), hold - s.b(45), 38, 40, 112, rate=0.125)
    roll(timp, s, s.b(46, 3), 2, TIMP[7], 56, 104, rate=0.125)
    roll(timp, s, s.b(47), hold - s.b(47), TIMP[7], 60, 122, rate=0.125)
    kit.n(s.b(41), 4, 57, 96)

    # ================================================================ 49-64: the unbowed
    ostinato(range(49, 65), part=vc, vel=V(70, 104))
    ostinato(range(49, 65), lo=24, hi=35, part=cb, vel=V(76, 108), bass=True)
    ostinato(range(49, 65), lo=48, hi=59, part=vla, vel=V(60, 90))
    ostinato(range(49, 65), lo=48, hi=59, part=bsn, vel=V(56, 90), bass=True)
    ostinato(range(57, 65), lo=60, hi=71, part=vn, vel=V(48, 80), ghost=-16, acc=8)
    for b0, call in ((49, CALL), (57, CALL_LIFT)):
        t0 = s.b(b0)
        lift = b0 == 57
        v = 108 if lift else 102
        hn1.line(t0, call, v, leg=0.99, accent=6)
        hn2.line(t0, call, v - 8, leg=0.99)
        hn3.line(t0, call, v - 10, tr=0 if lift else 12, leg=0.99)
        hn4.line(t0, call, v - 14, tr=0 if lift else 12, leg=0.99)
        tbn1.line(t0, call, v - 10, tr=-12, leg=0.98)
        tbn2.line(t0, call, v - 14, tr=-12, leg=0.98)
        tpt1.line(t0, call, v - 6, tr=12, leg=0.98, accent=6)
        tpt2.line(t0, call, v - 14, tr=12, leg=0.98)
        vn.line(t0, call, v - 18, tr=12, leg=1.0)
        ahh1.line(t0, call, v - 6, tr=12, leg=1.0)
        ahh2.line(t0, call, v - 12, tr=0, leg=1.0)
        for p_ in (hn1, hn2, hn3, hn4, tpt1, tpt2, tbn1, tbn2, ahh1, ahh2, vn):
            p_.expr([(t0, 108), (t0 + 4, 118), (t0 + 8, 126), (t0 + 12, 118), (t0 + 16 - 0.1, 110)])
    for bar, nt in zip(range(49, 53), ("C2", "Ab1", "Db2", "G1")):
        tuba.n(s.b(bar), 3.95, nt, 104)
    for bar, nt in zip(range(57, 61), ("C2", "F2", "Ab1", "Bb1")):
        tuba.n(s.b(bar), 3.95, nt, 108)
    for k, bars in enumerate((range(53, 57), range(61, 65))):  # the tread, in everything
        for j, bar in enumerate(bars):
            rf = (RIFF, RIFF_LAST)[k][j]
            tuba.line(s.b(bar), rf, 108, leg=0.96, accent=8)
            tbn1.line(s.b(bar), rf, 104, tr=12, leg=0.96, accent=8)
            tbn2.line(s.b(bar), rf, 100, tr=19, leg=0.94)
            hn1.line(s.b(bar), rf, 98, tr=24, leg=0.92)
            hn2.line(s.b(bar), rf, 94, tr=24, leg=0.92)
            bsn.line(s.b(bar), rf, 88, tr=12, leg=0.9)
            ahh1.chord(s.b(bar), 3.9, [P("Eb4"), P("G4")] if rpc(bar) in (0, 7) else [P("F4"), P("Ab4")], 70)
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, list(A2), PROG, [(50, 62), (55, 67), (60, 72), (64, 76)], V(54, 84))
    for p_ in (pad1, pad2):
        swell(p_, s, list(A2), lo=76, hi=118)
    pads([(ohh, [0, 1, 2, 3])], s, list(A2), PROG, cr, V(56, 90))
    swell(ohh, s, list(A2), lo=80, hi=122)
    for bar in (51, 62):
        bells.n(s.b(bar), 4, "Db5", 62)
    bells.n(s.b(59), 4, "C5", 62)
    # drums: everything
    tread_drums(range(49, 65), V(92, 120), extra=True, snare=True, toms_on=True)
    for bar in (49, 57):
        kit.n(s.b(bar), 4, 57, 120)
        kit.n(s.b(bar, 3), 3, 59, 100)
    for bar in (53, 61):
        kit.n(s.b(bar), 4, 57, 112)
    for bar in (52, 56, 60):
        fill(bar, 4.0, 100)
    for bar in range(57, 61):
        taiko.n(s.b(bar, 2), 0.5, TAIKO[rpc(bar)], 96)
    roll(timp, s, s.b(63, 3), 6, TIMP[7], 70, 118, rate=0.125)
    roll(kit, s, s.b(64), 4, 38, 50, 112, rate=0.125)
    reverse(s.b(65), 96)
    kit.n(s.b(65), 4, 57, 104)

    # ================================================================ 65-72: the echo
    ostinato(range(65, 67), part=vc, vel=V(40, 60))
    ostinato(range(65, 67), lo=24, hi=35, part=cb, vel=V(42, 60), bass=True)
    far.line(s.b(65), "C3:2 G3:1 Ab3:1 C4:2 Eb4:6", 66, leg=0.99)
    far.expr([(s.b(65), 70), (s.b(67), 100), (s.b(69), 70), (s.b(70), 40)])
    hn3.line(s.b(65), "C3:8", 50, leg=1.0)
    tuba.line(s.b(65), "C2:4 C2:4", 62, leg=1.0)
    tuba.line(s.b(67), "Ab1:8", 58, leg=1.0)
    tuba.line(s.b(69), "Db2:16", 60, leg=1.0)
    tuba.expr([(s.b(65), 80), (s.b(69), 100), (s.b(73) - 0.1, 70)])
    cb.line(s.b(67), "Ab1:8 Db1:16", 62, leg=1.0)
    cb.expr([(s.b(67), 80), (s.b(69), 104), (s.b(72), 90), (s.b(73) - 0.1, 70)])
    org.line(s.b(65), "C2:8 Ab1:8 Db2:16", 56, leg=1.0)
    org.expr([(s.b(65), 70), (s.b(69), 100), (s.b(73) - 0.1, 76)])
    pads([(ohh, [0, 1, 2, 3])], s, list(range(65, 69)), PROG, cr, V(40, 56))
    swell(ohh, s, list(range(65, 69)), lo=70, hi=100, every=4, shape="cresc-dim")
    dv = mm.voice_lead(["Db", "Db", "Db", "Db"], [(49, 60), (56, 67), (61, 72), (65, 77)])
    for bar, vc_ in zip(range(69, 73), dv):
        for p_, idx in ((ahh1, (2, 3)), (ahh2, (1,)), (ohh, (0,))):
            for i in idx:
                p_.n(s.b(bar), 4.05, vc_[i], 66 - 4 * (bar - 69))
    ahh1.expr([(s.b(69), 70), (s.b(70), 100), (s.b(72), 90), (s.b(73) - 0.1, 70)])
    ahh2.expr([(s.b(69), 70), (s.b(70), 100), (s.b(72), 90), (s.b(73) - 0.1, 70)])
    ohh.expr([(s.b(69), 90), (s.b(73) - 0.1, 70)])
    hn4.line(s.b(69), "Ab3:16", 52, leg=1.0)
    hn4.expr([(s.b(69), 70), (s.b(71), 100), (s.b(73) - 0.1, 70)])
    bells.n(s.b(69), 8, "Db5", 52)
    # the heartbeat comes back on D flat, so the loop's C beats on from it
    for bar in range(65, 73):
        pc = rpc(bar)
        v = V(44, 74)(bar) + (6 if bar > 68 else 0)
        timp.n(s.b(bar), 1.2, TIMP[pc], v)
        timp.n(s.b(bar, 1.75), 1.0, TIMP[pc] - 5, v - 14)
        if bar <= 68:
            taiko.n(s.b(bar), 0.5, TAIKO[pc], v - 10)
    return s


def premaster(stems, settings, ir, n_out):
    """sum the stems with their sends into the hall, EQ and glue them as make_music.mix does (leaving the level to
    `finish`)"""
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
    out = np.pad(dry + wet, ((0, 0), (0, max(0, n_out - n))))[:, :n_out]
    for c in range(2):
        out[c] = hp(out[c], 30, order=2)
        out[c] = eq(out[c], "peak", 320, 0.8, -1.5)
        out[c] = eq(out[c], "highshelf", 7500, 0.7, 2.0)
    out /= np.max(np.abs(out)) + 1e-12
    out = stereo_comp(out, -16.0, 2.0)
    return out / (np.max(np.abs(out)) + 1e-12)


def finish(out, ceiling, g=1.0, target=None, fade_in=0.005, fade_out=1.0):
    """set the integrated loudness to `target` (default LOUDNESS) by measurement, limiting peaks at `ceiling` dBFS,
    then fade the ends: returns the finished stereo signal and the gain it ended with"""
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


def render_stems(song, name, work, sf2):
    os.makedirs(work, exist_ok=True)
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        mid = os.path.join(work, f"{name}_{stem}.mid")
        wav = os.path.join(work, f"{name}_{stem}.wav")
        export_stem(song, stem, mid)
        stems[stem] = render(mid, wav, sf2)
    return stems


def build(assets, sf2, work, wav_dir=None):
    song = compose_dragon()
    stems = render_stems(song, "dragon", work, sf2)
    n_out = int(round((song.end_beat * song.spb + song.tail) * SR))
    ir = hall_ir(rt60=2.6, seed=107)
    pre = premaster(stems, DRAGON_MIX, ir, n_out)
    path = os.path.join(assets, "sounds", OUT + ".ogg")
    # the encoder overshoots the limiter by a dB or so (a different amount each time) and takes a little of the
    # loudness: measure the decoded file and move the limiter's ceiling and the loudness target until its true peak
    # lands just under TRUE_PEAK and its loudness on LOUDNESS
    ceiling, g, target = TRUE_PEAK - 1.2, 1.0, LOUDNESS
    for _ in range(6):
        out, g = finish(pre, ceiling, g, target)
        write_ogg(path, out, quality=QUALITY)
        dec = read_audio(path)
        tp, loud = true_peak(dec), lufs(dec)
        if TRUE_PEAK - 0.4 <= tp <= TRUE_PEAK + 0.1 and abs(loud - LOUDNESS) <= 0.12:
            break
        ceiling += 0.8 * ((TRUE_PEAK - 0.15) - tp)
        target += LOUDNESS - loud
    if wav_dir:
        write_wav(os.path.join(wav_dir, OUT + ".wav"), out)
    print(f"{path}  {dec.shape[1] / SR:.3f}s  {loud:.2f} LUFS  true peak {tp:.2f} dBFS", flush=True)
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
