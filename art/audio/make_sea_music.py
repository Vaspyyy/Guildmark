"""The Maelgrasp: the sea monster's battle theme, composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 -B art/audio/make_sea_music.py src/main/resources/assets/guildmark [--sf2 FluidR3_GM.sf2]
        [--work DIR] [--wav DIR] [--mp3 FILE]

It writes sounds/music/maelgrasp.ogg. (make_sea_audio.py runs it too, after the effects.) The building blocks come from
make_music.py: parts and songs, per-stem MIDI export with seeded humanising, FluidSynth rendering, the synthetic hall, the
glue compressor and the limiter. This script adds the piece and a master that sets the loudness by measurement (ITU-R
BS.1770, gated) to the level of the other battle themes (they measure -9.6 to -11.2 LUFS).

The piece is F sharp minor, in a heaving 6/8 at 60 dotted-quarter beats a minute (a beat here is an eighth note), 74 bars,
2:28 (148.000 s). It is built on three notes, the creature's call: F sharp, the G a semitone above it, and the C a tritone
over the root, a long note, a short one and a long leap, F sharp - G - C. Its answer runs back: C - C sharp - F sharp, the
tritone falling to the fifth and home. The heave itself is the rocking of the low strings and tubas between the root and the
fifth (or, where the harmony turns wrong, the tritone), a dotted quarter each, swelling and ebbing in every bar like a swell
under the keel; over it the harmony moves above a pedal F sharp, G over it, C over it, Bm and C sharp, the semitone and the
tritone that this sea is made of.

Form (bars): the deep (1-8) an F sharp on the organ and basses under a heartbeat on the timpani (F sharp, and a G a
semitone over it), a far horn calling the three notes and answering, a drowned bell tolling, the choir rising in a
cluster; the heave (9-24) the rocking basses, the low brass stating the call and its answer, then the horns, the choir
coming in on the second round; the arms (25-40) the strings turn the 6/8 into three (2+2+2, against the drums' two) and
the horns play the call on the root of every chord as the bass climbs, while the choir sings a long line that rises
and then sinks; the abyss (41-48) the sea opens under it: the drums gone to slow great blows, the choir in clusters of
semitones, the strings' tremolo climbing and the brass shouting the call over it until it stops dead on one beat of
silence, as the creature draws breath; the Maelgrasp rises (49-64) everything: the call on horns an octave up, then
lifted higher still on trumpets and violins with the choir over it, the heave in all the low brass, the drums in full;
the sinking (65-74) the call goes down, the players leave one by one and the heartbeat returns on the F sharp.

The game plays it as a boss track that starts again the moment it ends, so the file is exactly one turn of a loop: it is the
74 bars to the sample, and what rings on past the last bar (the release of the last chord, the tail of the hall) is not
thrown away or faded out but folded over the first seconds, where the music itself would have it. The last bar's heartbeat
on the F sharp, with the organ's F sharp under it, runs straight into the first bar's: the loop point is the end of the
file, the start of the file is the beat after it. The ends are only rounded (5 ms in, 40 ms out, at the quietest of the piece).
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_music as mm  # noqa: E402
from make_music import (P, SF2_DEFAULT, Song, export_stem, hall_ir, in_range, pads, pcs, render,  # noqa: E402
                        roll, stereo_comp, stereo_limit, swell)
from make_menu_music import lufs, true_peak  # noqa: E402
from sfxkit import SR, db, eq, fade, hp, read_audio, write_ogg, write_wav  # noqa: E402

mm.CHORDS.update({"F#m": "F# A C#", "Bm": "B D F#", "C#": "C# F G#", "F#dim": "F# A C"})

BPM = 180  # eighth notes a minute: a dotted quarter is 60
BPB = 6
BARS = 74
OUT = "music/maelgrasp"
LOUDNESS = -10.4  # LUFS integrated: the other battle themes measure -9.6 to -11.2
TRUE_PEAK = -0.6  # dBFS, measured on the decoded Ogg (the other boss tracks decode to about -0.5)
QUALITY = 5
END_FADE = 0.04  # seconds: the file ends on the last beat of the last bar, so it only needs its edge rounded

# sections (bars, 1-based), and the chapter marks of the listen-through
INTRO, HEAVE, ARMS, ARMS2, ABYSS, RISES, RISES2, SINK = (range(1, 9), range(9, 25), range(25, 33), range(33, 41),
                                                         range(41, 49), range(49, 57), range(57, 65), range(65, 75))
RISE_ALL = list(RISES) + list(RISES2)
CHAPTERS = [(1, "the deep"), (9, "the heave"), (25, "the arms"), (41, "the abyss"), (49, "the Maelgrasp rises"),
            (65, "the sinking")]

# one chord per bar (the harmony over the bass below)
PROG = (["F#m"] * 4 + ["G", "G", "F#m", "C"]                                         # the deep
        + ["F#m", "F#m", "G", "F#m", "Bm", "F#m", "C", "C#"] * 2                      # the heave, twice
        + ["F#m", "G", "F#m", "A", "Bm", "C", "C#", "C#"]                            # the arms: climbing
        + ["F#m", "G", "Bm", "G", "F#m", "E", "C", "C#"]                              # the arms: sinking
        + ["F#m", "G", "F#m", "G", "Bm", "C", "F#dim", "C#"]                          # the abyss
        + ["F#m", "F#m", "G", "G", "F#m", "D", "C", "C#"]                             # it rises
        + ["F#m", "A", "G", "E", "Bm", "C", "C#", "C#"]                               # the call, lifted
        + ["F#m", "F#m", "Bm", "F#m", "G", "G", "F#m", "F#m", "F#m", "F#m"])         # the sinking
assert len(PROG) == BARS

# the bass pitch class of each bar: the chord's own root, except where a pedal F sharp holds under it
BASS = [pcs(c)[0] for c in PROG]
for _b in list(range(1, 9)) + [9, 10, 11, 12, 14, 17, 18, 20, 22] + list(range(41, 45)) + [47] + list(range(65, 75)):
    BASS[_b - 1] = 6
BASS[67 - 1] = 11  # the one Bm of the sinking
assert len(BASS) == BARS
MINOR = {"F#m", "Bm", "Em", "F#dim"}

# (bar, intensity 0..1): the arc of the piece, as the other tracks do it, driving velocities
ARC = [(1, .20), (4, .28), (8, .48), (9, .56), (16, .66), (17, .72), (24, .82), (25, .62), (32, .72), (33, .76),
       (40, .86), (41, .46), (45, .62), (48, .92), (49, 1.0), (64, 1.0), (65, .56), (69, .38), (74, .22)]

# the call at its home pitch (a long note, a short one, a long leap), and its answer: F# - G - C, C - C# - F# (sounding
# pitch for the first horn; the low brass play it an octave down and the high strings an octave up)
CALL = "F#3:4 G3:2 C4:6"
ANSWER = "C4:4 C#4:2 F#3:6"
# the tune of the heave, eight bars over F#m F#m G F#m / Bm F#m C C#: the call, its answer, a calm fall to the root, and the
# tritone and the dominant held (the second half comes back to F sharp over the V)
TUNE = ("F#3:4 G3:2 C4:6  C4:4 C#4:2 F#3:6  B3:4 A3:2 F#3:6  C4:6 C#4:6")
# the tune lifted, bars 57-64 over F#m A G E / Bm C C# C#: the call an octave up, its answer, the fall in the middle, the
# tritone and the dominant
TUNE_LIFT = ("F#4:4 G4:2 C5:6  C5:4 C#5:2 F#4:6  B4:4 A4:2 G4:6  C5:6 C#5:6")
# the long line of the choir through the arms: rising (bars 25-32), then sinking (33-40): one chord tone a bar
CHANT_UP = [69, 71, 73, 76, 74, 76, 77, 80]
CHANT_DOWN = [73, 71, 71, 67, 66, 68, 67, 65]
# the taiko's and timpani's notes, tuned: for each pitch class, the note number whose sample sounds that note low down
TAIKO = {6: 40, 7: 42, 8: 44, 9: 46, 11: 50, 0: 52, 1: 54, 2: 56, 4: 60}
TIMP = {6: 30, 7: 31, 8: 32, 9: 33, 11: 35, 0: 36, 1: 37, 2: 38, 4: 40}
TOMS = [41, 43, 45, 47, 48, 50]  # the power kit's toms, low to high

# per-stem (gain dB, reverb send): the dragon's balance, with more of the cave in the choir, the horns and the drums
SEA_MIX = {"low": (3.0, 0.16), "high": (1.0, 0.34), "brass": (-2.0, 0.28), "choir": (2.0, 0.44),
           "perc": (2.5, 0.20), "war": (0.0, 0.16), "color": (4.0, 0.46), "far": (-3.0, 0.90)}


def compose_sea():
    s = Song("maelgrasp", bpm=BPM, bars=BARS, bpb=BPB, tail=0.0)
    inten = np.interp(np.arange(1, BARS + 1), [a for a, _ in ARC], [b for _, b in ARC])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[bar - 1]

    def croot(bar):
        return pcs(PROG[bar - 1])[0]

    def broot(bar, lo, hi):
        return in_range(BASS[bar - 1], lo, hi)

    # ---- the players
    cb = s.part("cb", 43, "low", pan=88, volume=112, hum=0.006, vjit=4)
    vc = s.part("vc", 42, "low", pan=70, volume=110, hum=0.006, vjit=4)
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
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=106, hum=0.004, vjit=4)  # FluidR3's orchestra kit
    toms = s.part("toms", 16, "war", drum=True, pan=52, volume=104, hum=0.005, vjit=5)  # and its power kit
    bells = s.part("bells", 14, "color", pan=62, volume=84)
    sweep = s.part("sweep", 119, "color", pan=64, volume=96)
    far = s.part("far", 60, "far", pan=60, volume=104, lag=0.014)
    cr = [(48, 60), (53, 65), (57, 69), (62, 74)]  # the choir's four voices, low to high

    # ================================================================ shared figures
    def bar_swell(part, bars, lo, hi, peak=0.5):
        """every bar a swell under the keel: up to its peak, then ebbing"""
        pts = []
        for bar in bars:
            b0 = s.b(bar)
            pts += [(b0, lo), (b0 + BPB * peak, hi), (b0 + BPB - 0.05, lo + 0.3 * (hi - lo))]
        part.expr(pts)

    def heave(bars, part, lo, hi, vel, acc=8):
        """the rocking of the sea: the root a dotted quarter, then the fifth (the tritone, where the harmony turns wrong)"""
        for bar in bars:
            r = broot(bar, lo, hi)
            iv = 6 if PROG[bar - 1] in ("C", "F#dim") else 7
            v = vel(bar)
            part.n(s.b(bar), 3.04, r, v + acc)
            part.n(s.b(bar) + 3, 3.04, r + iv, v - 6)

    def pedal(bars, part, lo, hi, vel, acc=6):
        """the bass under the bar: one long note"""
        for bar in bars:
            part.n(s.b(bar), BPB - 0.06, broot(bar, lo, hi), vel(bar) + acc)

    def heartbeat(bars, vel, dub=-14):
        """lub-dub on the timpani: the F sharp and the G a semitone over it (the call's first two notes)"""
        for bar in bars:
            v = vel(bar)
            timp.n(s.b(bar), 1.2, TIMP[BASS[bar - 1]], v)
            timp.n(s.b(bar) + 1, 1.0, TIMP[BASS[bar - 1]] + 1, v + dub)

    def tune(parts, beat, spec, vel, tr=0, leg=0.99, accent=0):
        for p_, dv, t_ in parts:
            p_.line(beat, spec, vel + dv, tr=tr + t_, leg=leg, accent=accent)

    def head(bar, lo, hi):
        """the call as a figure for one bar of the arms: on the chord's root, its second (a semitone over a minor chord,
        a whole tone over a major) and its tritone, a quarter note each"""
        r = in_range(croot(bar), lo, hi)
        return r, r + (1 if PROG[bar - 1] in MINOR else 2), r + 6

    def fill(bar, beat, vel, n=6, rate=0.5, tl=(5, 4, 3, 2, 1, 0)):
        """a run of toms falling to the bar's end, getting louder"""
        for i in range(n):
            toms.n(s.b(bar, beat) + i * rate, 0.4, TOMS[tl[i % len(tl)]], vel * (0.65 + 0.35 * (i + 1) / n))

    def reverse(target, vel=92):
        """a reverse cymbal swelling into the beat `target`: the sample is a one-shot that peaks 1.36 s (4.08 beats) in"""
        sweep.n(target - 4.08, 2.0, "C4", vel)

    def hits(bars, vel, beats=(0, 3), boom=True, taikos=True, timps=True):
        """the big drums on the pulses of the bar: taiko, timpani and the bass drum on the root"""
        for bar in bars:
            pc = BASS[bar - 1]
            v = vel(bar)
            for i, beat in enumerate(beats):
                dv = (0, -8, -14)[min(i, 2)]
                if taikos:
                    taiko.n(s.b(bar) + beat, 0.5, TAIKO[pc], v + dv)
                if timps:
                    timp.n(s.b(bar) + beat, 1.4 if i == 0 else 1.0, TIMP[pc], v + dv - (0 if i == 0 else 4))
                if boom:
                    kit.n(s.b(bar) + beat, 1, 36, v - 4 + dv)

    # ================================================================ 1-8: the deep
    org.n(s.b(1), BPB * 8 + 0.1, 42, 60)
    org.expr([(0, 66), (s.b(5), 92), (s.b(9), 112)])
    cb.line(s.b(1), "F#1:12 F#1:12 F#1:12 F#1:12", 62, leg=1.0)
    cb.expr([(0, 72), (s.b(5), 100), (s.b(9), 112)])
    heartbeat(INTRO, V(62, 96))
    for bar in (5, 6, 7, 8):
        taiko.n(s.b(bar), 0.5, TAIKO[6], V(60, 82)(bar))
    # the call, far off, and its answer
    far.line(s.b(3), CALL, 62, leg=0.99)
    far.expr([(s.b(3), 56), (s.b(4), 100), (s.b(5), 60)])
    far.line(s.b(7), ANSWER, 60, leg=0.99)
    far.expr([(s.b(7), 56), (s.b(8), 100), (s.b(9) - 0.1, 70)])
    # the choir rises
    pads([(ohh, [0, 1, 2, 3])], s, list(range(5, 9)), PROG, cr, V(44, 70), dur_beats=BPB)
    swell(ohh, s, list(range(5, 9)), lo=70, hi=118, every=4, shape="cresc")
    # a drowned bell: the F sharp, and the tritone above it
    bells.n(s.b(1), 12, "F#4", 52)
    bells.n(s.b(5), 12, "C5", 46)
    bells.n(s.b(8, 4), 6, "F#4", 54)
    tuba.line(s.b(7), "F#2:18", 54, leg=1.0)
    tuba.expr([(s.b(7), 70), (s.b(9), 100), (s.b(10), 80)])
    roll(timp, s, s.b(8, 4), 3, TIMP[6], 50, 108, rate=0.25)
    reverse(s.b(9), 88)
    kit.n(s.b(9), 6, 57, 100)

    # ================================================================ 9-24: the heave
    for bars in (HEAVE,):
        heave(bars, vc, 38, 50, V(52, 92))
        pedal(bars, cb, 28, 40, V(56, 96))
        bar_swell(vc, bars, 68, 112)
        bar_swell(cb, bars, 70, 112)
    pedal(list(HEAVE), tuba, 36, 47, V(52, 84), acc=2)
    bar_swell(tuba, HEAVE, 64, 104)
    org.n(s.b(9), BPB * 16 + 0.1, 42, 58)
    org.expr([(s.b(9), 100), (s.b(25), 112)])
    # the low brass state the call and its answer, then the horns
    tune([(tuba, 0, -24), (tbn1, -4, -12), (tbn2, -8, -12)], s.b(9), TUNE, 84, leg=0.99, accent=4)
    tune([(hn1, 0, 0), (hn2, -6, 0), (tbn1, -4, -12)], s.b(17), TUNE, 92, leg=0.99, accent=4)
    for p_ in (tbn1, tbn2, tuba):
        p_.expr([(s.b(9), 90), (s.b(13), 108), (s.b(17), 96), (s.b(21), 112), (s.b(25) - 0.1, 100)])
    for p_ in (hn1, hn2):
        p_.expr([(s.b(17), 96), (s.b(21), 112), (s.b(25) - 0.1, 100)])
    bsn.line(s.b(17), TUNE, 70, tr=-12, leg=0.96)
    # strings: the heave in violas an octave up in the second round, long notes of the ensemble over it
    heave(range(17, 25), vla, 50, 62, V(44, 70), acc=4)
    bar_swell(vla, range(17, 25), 66, 108)
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, list(HEAVE), PROG, [(50, 62), (55, 67), (60, 72), (64, 76)], V(34, 62), dur_beats=BPB)
    for p_ in (pad1, pad2):
        swell(p_, s, list(HEAVE), lo=68, hi=108, every=2)
    # the choir: ohh under, ahh in the second round
    pads([(ohh, [0, 1, 2, 3])], s, list(HEAVE), PROG, cr, V(34, 62), dur_beats=BPB)
    swell(ohh, s, list(HEAVE), lo=62, hi=108, every=2)
    pads([(ahh2, [1, 2, 3])], s, list(range(21, 25)), PROG, cr, V(54, 84), dur_beats=BPB)
    swell(ahh2, s, list(range(21, 25)), lo=74, hi=120, every=4, shape="cresc")
    bells.n(s.b(13), 12, "C5", 50)
    bells.n(s.b(21), 12, "F#4", 52)
    # drums: the pulse, the heart; the second round adds the second pulse and toms
    hits(range(9, 13), V(70, 96), beats=(0,), taikos=True)
    hits(range(13, 17), V(76, 102), beats=(0, 3))
    hits(range(17, 25), V(84, 110), beats=(0, 3))
    for bar in range(13, 25):
        taiko.n(s.b(bar) + 5, 0.5, TAIKO[BASS[bar - 1]], V(46, 66)(bar))
    for bar in range(17, 25):
        for beat, tom, dv in ((2, 2, -22), (4, 3, -20)):
            toms.n(s.b(bar) + beat, 0.4, TOMS[tom], V(80, 100)(bar) + dv)
    kit.n(s.b(13), 6, 55, 78)
    kit.n(s.b(17), 6, 57, 106)
    for bar in (16, 20):
        fill(bar, 4.0, 76, n=4, rate=0.5)
    fill(24, 1.0, 98, n=12, rate=0.5, tl=(5, 4, 3, 2, 1, 0, 1, 2, 3, 2, 1, 0))
    roll(timp, s, s.b(24, 4), 3, TIMP[11], 56, 116, rate=0.125)
    reverse(s.b(25), 92)
    kit.n(s.b(25), 6, 57, 108)

    # ================================================================ 25-40: the arms
    # the 6/8 turned into three: quarter notes (2 + 2 + 2) on the root, the third one a step above
    for bar in range(25, 41):
        r = in_range(croot(bar), 38, 49)
        nei = 1 if PROG[bar - 1] in MINOR else 2
        v = V(56, 96)(bar)
        for beat, iv, dv in ((0, 0, 10), (2, 0, -6), (4, nei, 2)):
            vc.n(s.b(bar) + beat, 1.85, r + iv, v + dv)
            vla.n(s.b(bar) + beat, 1.85, r + 12 + iv, v + dv - 12)
        cb.n(s.b(bar), BPB - 0.06, broot(bar, 28, 40), V(60, 100)(bar) + 6)
    bar_swell(vc, range(25, 41), 78, 112, 0.35)
    bar_swell(cb, range(25, 41), 78, 112, 0.35)
    # the horns' figure on the root of each chord as the bass climbs; the trombones push under it
    for bar in range(25, 41):
        if bar in range(33, 37):
            continue
        r, a, c = head(bar, 54, 65)
        v = V(80, 108)(bar)
        for p_, dv in ((hn1, 0), (hn2, -6), (hn3, -10)):
            for beat, n_, dd in ((0, r, 6), (2, a, -4), (4, c, 2)):
                p_.n(s.b(bar) + beat, 1.9, n_ - (12 if p_ is hn3 else 0), v + dv + dd)
        for p_ in (tbn1, tbn2):
            rr = in_range(croot(bar), 40, 52)
            p_.n(s.b(bar), 1.95, rr, v - 12)
            p_.n(s.b(bar) + 3, 2.95, rr + 7, v - 18)
    for p_ in (hn1, hn2, hn3):
        p_.expr([(s.b(25), 98), (s.b(29), 112), (s.b(33), 100), (s.b(37), 112), (s.b(41) - 0.1, 104)])
    for bar in range(33, 37):  # the pause of the sinking line: the low brass hold the roots, the horns the call's answer
        tuba.n(s.b(bar), BPB - 0.1, broot(bar, 36, 47), V(60, 90)(bar))
        tbn1.n(s.b(bar), BPB - 0.1, in_range(croot(bar), 40, 52), V(58, 82)(bar))
    hn1.line(s.b(33), ANSWER, 82, leg=0.99)
    hn2.line(s.b(33), ANSWER, 76, tr=-12, leg=0.99)
    hn1.line(s.b(35), "C#4:6 B3:6", 80, leg=0.99)
    hn2.line(s.b(35), "C#4:6 B3:6", 74, tr=-12, leg=0.99)
    for bar in list(range(25, 33)) + list(range(37, 41)):
        tuba.n(s.b(bar), BPB - 0.1, broot(bar, 36, 47), V(60, 90)(bar))
    tuba.expr([(s.b(25), 96), (s.b(41) - 0.1, 112)])
    # the choir's long line: up to a high G sharp, then sinking to the leading tone
    for k, bar in enumerate(range(25, 33)):
        ahh1.n(s.b(bar), BPB + 0.05, CHANT_UP[k], V(66, 96)(bar))
        ahh2.n(s.b(bar), BPB + 0.05, CHANT_UP[k] - 12, V(56, 86)(bar))
    for k, bar in enumerate(range(33, 41)):
        ahh1.n(s.b(bar), BPB + 0.05, CHANT_DOWN[k], V(74, 100)(bar))
        ahh2.n(s.b(bar), BPB + 0.05, CHANT_DOWN[k] - 12, V(64, 90)(bar))
    ahh1.expr([(s.b(25), 80), (s.b(32), 118), (s.b(33), 96), (s.b(41) - 0.1, 104)])
    ahh2.expr([(s.b(25), 76), (s.b(32), 112), (s.b(33), 90), (s.b(41) - 0.1, 100)])
    pads([(ohh, [0, 1, 2, 3])], s, list(range(25, 41)), PROG, cr, V(40, 70), dur_beats=BPB)
    swell(ohh, s, list(range(25, 41)), lo=64, hi=112, every=2)
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, list(range(25, 41)), PROG, [(50, 62), (55, 67), (60, 72), (64, 76)], V(36, 66), dur_beats=BPB)
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(25, 41)), lo=70, hi=112, every=2)
    vn.line(s.b(33), "C#5:6 B4:6 B4:6 G4:6 F#4:6 G#4:6 G4:6 F4:6", 84, leg=1.0)
    vn.expr([(s.b(33), 90), (s.b(37), 112), (s.b(41) - 0.1, 100)])
    # drums: two against three, the heart on the pulse of two, the toms on the pulse of three
    hits(range(25, 41), V(84, 112), beats=(0, 3))
    for bar in range(25, 41):
        v = V(70, 100)(bar)
        for beat, tom, dv in ((0, 1, -24), (2, 3, -16), (4, 2, -14)):
            toms.n(s.b(bar) + beat, 0.4, TOMS[tom], v + dv)
        taiko.n(s.b(bar) + 5, 0.5, TAIKO[BASS[bar - 1]], v - 28)
    kit.n(s.b(25), 6, 57, 104)
    kit.n(s.b(33), 6, 57, 98)
    kit.n(s.b(29), 6, 55, 80)
    kit.n(s.b(37), 6, 55, 82)
    for bar in (28, 32, 36):
        fill(bar, 4.0, 82, n=4, rate=0.5)
    roll(timp, s, s.b(40, 1), 6, TIMP[1], 52, 118, rate=0.125)
    roll(kit, s, s.b(40, 1), 6, 38, 40, 104, rate=0.25)
    bells.n(s.b(25), 12, "C5", 50)
    bells.n(s.b(33), 12, "F#4", 50)
    reverse(s.b(41), 92)

    # ================================================================ 41-48: the abyss
    hold = s.b(48, 4.0)  # the last half bar is silence: the creature draws breath
    for bar in range(41, 49):
        pc = BASS[bar - 1]
        v = V(50, 100)(bar)
        if bar < 48:
            cb.n(s.b(bar), BPB - 0.06, in_range(pc, 28, 40), v)
            tuba.n(s.b(bar), BPB - 0.06, in_range(pc, 36, 47), v - 6)
            vc.n(s.b(bar), BPB - 0.06, in_range(pc, 38, 50), v - 10)
        else:
            cb.n(s.b(bar), 3.0, in_range(pc, 28, 40), v + 6)
            tuba.n(s.b(bar), 3.0, in_range(pc, 36, 47), v)
            vc.n(s.b(bar), 3.0, in_range(pc, 38, 50), v)
    for p_ in (cb, vc, tuba):
        p_.expr([(s.b(41), 70), (s.b(45), 96), (hold, 124)])
    org.n(s.b(41), BPB * 8 - 3, 42, 60)
    org.expr([(s.b(41), 80), (hold, 120)])
    # the choir in clusters of semitones
    for bar, notes in ((41, (54, 55, 57, 61)), (42, (54, 55, 57, 61)), (43, (55, 57, 59, 62)), (44, (55, 57, 59, 62)),
                       (45, (59, 60, 62, 66)), (46, (60, 62, 66, 67)), (47, (54, 55, 57, 60)), (48, (61, 65, 66, 68))):
        v = V(50, 100)(bar)
        for p_, n_ in zip((ohh, ohh, ahh2, ahh1), notes):
            p_.n(s.b(bar), BPB - 0.1 if bar < 48 else 3.0, n_, v)
    for p_ in (ohh, ahh1, ahh2):
        p_.expr([(s.b(41), 72), (s.b(45), 98), (hold, 126)])
    # the strings' tremolo climbs; the violins trill high
    tv = mm.voice_lead([PROG[b - 1] for b in ABYSS], [(58, 68), (64, 74)])
    for bar, vc_ in zip(ABYSS, tv):
        trem.chord(s.b(bar), (BPB + 0.05) if bar < 48 else 3.0, vc_, 40 + 9 * (bar - 41))
    trem.expr([(s.b(41), 60), (hold, 124)])
    for bar, a_, b_ in ((45, "G5", "F#5"), (46, "G5", "F#5"), (47, "C6", "B5"), (48, "C6", "B5")):
        for i in range(6 if bar == 48 else 12):
            vn.n(s.b(bar) + i * 0.5, 0.45, a_ if i % 2 == 0 else b_, 56 + 4 * (bar - 45) + i)
    vn.expr([(s.b(45), 80), (hold, 124)])
    # the brass shout the call, climbing, over the cluster
    for bar, root_ in ((45, "B3"), (46, "C4"), (47, "F#4"), (48, "C#4")):
        v = V(70, 112)(bar)
        r = P(root_)
        for p_, tr, dv in ((hn1, 0, 0), (hn2, 0, -4), (hn3, -12, -6), (tpt1, 12, -10)):
            if bar < 48:
                p_.n(s.b(bar), 1.9, r + tr, v + dv + 6)
                p_.n(s.b(bar) + 2, 1.9, r + 1 + tr, v + dv - 4)
                p_.n(s.b(bar) + 4, 1.9, r + 6 + tr, v + dv + 2)
            else:
                p_.n(s.b(bar), 2.9, r + 6 + tr, v + dv + 8)
        tbn1.n(s.b(bar), 1.9 if bar < 48 else 2.9, r - 12, v - 12)
        tbn2.n(s.b(bar), 1.9 if bar < 48 else 2.9, r - 5, v - 16)
    for p_ in (hn1, hn2, hn3, tpt1):
        p_.expr([(s.b(45), 100), (hold, 126)])
    # drums: great slow blows, then the roll
    for bar in (41, 43):
        taiko.n(s.b(bar), 0.5, TAIKO[6], V(76, 100)(bar))
        kit.n(s.b(bar), 1, 36, V(72, 96)(bar))
        timp.n(s.b(bar), 1.6, TIMP[6], V(80, 100)(bar))
    heartbeat((42, 44), V(60, 90))
    for bar in (45, 46, 47):
        pc = BASS[bar - 1]
        v = V(76, 112)(bar)
        timp.n(s.b(bar), 1.4, TIMP[pc], v)
        taiko.n(s.b(bar), 0.5, TAIKO[pc], v - 4)
        kit.n(s.b(bar), 1, 36, v - 6)
        roll(timp, s, s.b(bar, 4), 3, TIMP[pc], v - 26, v - 4, rate=0.25)
        for beat in (0, 2, 4):
            toms.n(s.b(bar) + beat, 0.4, TOMS[(beat // 2 + bar) % 3], v - 20)
    timp.n(s.b(48), 1.8, TIMP[1], 120)
    taiko.n(s.b(48), 0.5, TAIKO[1], 112)
    kit.n(s.b(48), 2, 36, 112)
    roll(kit, s, s.b(45), hold - s.b(45), 38, 36, 108, rate=0.25)
    kit.n(s.b(41), 6, 57, 80)
    bells.n(s.b(41), 12, "C5", 54)
    bells.n(s.b(45), 12, "F#4", 52)
    reverse(s.b(49), 96)

    # ================================================================ 49-64: the Maelgrasp rises
    heave(RISE_ALL, vc, 38, 50, V(86, 108), acc=8)
    heave(RISE_ALL, bsn, 38, 50, V(60, 90), acc=4)
    pedal(RISE_ALL, cb, 28, 40, V(88, 112))
    pedal(RISE_ALL, tuba, 36, 47, V(84, 110), acc=2)
    for p_ in (vc, cb, tuba, bsn):
        bar_swell(p_, RISE_ALL, 86, 124, 0.4)
    # the call on the horns an octave up, with the low brass under it
    tune([(hn1, 0, 12), (hn2, -6, 12), (hn3, -8, 0), (hn4, -12, 0), (tbn1, -6, 0), (tbn2, -10, -12), (tuba, -6, -24)],
         s.b(49), TUNE, 104, leg=0.99, accent=6)
    for p_ in (hn1, hn2, hn3, hn4, tbn1, tbn2, tuba):
        p_.expr([(s.b(49), 110), (s.b(53), 124), (s.b(57) - 0.1, 112)])
    # the heave in the violas; the violins and trumpets lift the call
    heave(RISE_ALL, vla, 50, 62, V(62, 90), acc=4)
    tune([(hn1, 6, 0), (hn2, 0, 0), (hn3, -4, -12), (hn4, -10, -12), (tpt1, -2, 0), (tpt2, -8, 0), (vn, -10, 12),
          (tbn1, -4, -12), (tbn2, -8, -24), (tuba, -4, -24)], s.b(57), TUNE_LIFT, 108, leg=0.99, accent=6)
    for p_ in (hn1, hn2, hn3, hn4, tbn1, tbn2, tpt1, tpt2, vn, tuba):
        p_.expr([(s.b(57), 114), (s.b(61), 126), (s.b(65) - 0.1, 106)])
    for bar in RISES:
        v = V(76, 100)(bar)
        trem.chord(s.b(bar), BPB + 0.05, mm.voice_lead([PROG[bar - 1]], [(58, 68), (64, 74)])[0], v - 20)
    for bar in RISES2:
        v = V(76, 100)(bar)
        trem.chord(s.b(bar), BPB + 0.05, mm.voice_lead([PROG[bar - 1]], [(60, 70), (67, 78)])[0], v - 14)
    trem.expr([(s.b(49), 96), (s.b(57), 116), (s.b(65) - 0.1, 100)])
    # the choir: ahh on the call, ohh under
    pads([(ohh, [0, 1, 2, 3])], s, RISE_ALL, PROG, cr, V(60, 92), dur_beats=BPB)
    swell(ohh, s, RISE_ALL, lo=84, hi=124, every=2)
    tune([(ahh1, -10, 12), (ahh2, -16, 0)], s.b(49), TUNE, 100, leg=1.0)
    tune([(ahh1, -4, 0), (ahh2, -10, -12)], s.b(57), TUNE_LIFT, 100, leg=1.0)
    for p_ in (ahh1, ahh2):
        p_.expr([(s.b(49), 100), (s.b(53), 120), (s.b(57), 108), (s.b(61), 126), (s.b(65) - 0.1, 104)])
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, RISE_ALL, PROG, [(50, 62), (55, 67), (60, 72), (64, 76)],
         V(54, 84), dur_beats=BPB)
    for p_ in (pad1, pad2):
        swell(p_, s, RISE_ALL, lo=76, hi=118, every=2)
    for bar in (51, 55, 61):
        bells.n(s.b(bar), 12, "C5", 60)
    bells.n(s.b(49), 12, "F#4", 62)
    # drums: everything
    hits(range(49, 65), V(98, 122), beats=(0, 3))
    for bar in range(49, 65):
        v = V(92, 118)(bar)
        taiko.n(s.b(bar) + 5, 0.5, TAIKO[BASS[bar - 1]], v - 22)
        for beat, tom, dv in ((0, 1, -16), (2, 3, -12), (4, 2, -10)):
            toms.n(s.b(bar) + beat, 0.4, TOMS[tom], v + dv)
        kit.n(s.b(bar) + 3, 1, 36, v - 16)
    for bar in range(57, 65):
        kit.n(s.b(bar) + 2, 0.5, 38, V(80, 100)(bar) - 38)
        kit.n(s.b(bar) + 5, 0.5, 38, V(80, 100)(bar) - 34)
    for bar in (49, 57):
        kit.n(s.b(bar), 6, 57, 120)
        kit.n(s.b(bar) + 3, 6, 59, 100)
    for bar in (53, 61):
        kit.n(s.b(bar), 6, 57, 112)
    for bar in (52, 56, 60):
        fill(bar, 4.0, 100, n=4, rate=0.5)
    roll(timp, s, s.b(64, 1), 6, TIMP[1], 70, 120, rate=0.125)
    fill(64, 1.0, 108, n=12, rate=0.5, tl=(5, 4, 3, 2, 1, 0, 1, 2, 3, 2, 1, 0))
    reverse(s.b(65), 96)
    kit.n(s.b(65), 6, 57, 106)

    # ================================================================ 65-74: the sinking
    cb.line(s.b(65), "F#1:12 B1:6 F#1:42", 66, leg=1.0)
    cb.expr([(s.b(65), 96), (s.b(69), 108), (s.b(75) - 0.1, 70)])
    heave(range(65, 69), vc, 38, 50, V(40, 62), acc=4)
    bar_swell(vc, range(65, 69), 70, 104)
    tuba.line(s.b(65), "F#2:12 B2:6 F#2:12", 60, leg=1.0)
    tuba.expr([(s.b(65), 90), (s.b(69), 100), (s.b(71), 60)])
    org.n(s.b(65), BPB * 10 + 0.1, 42, 58)
    org.expr([(s.b(65), 110), (s.b(70), 120), (s.b(75) - 0.1, 90)])
    # the call, sinking: on the far horn, then slower still, the horns, the trombones, an octave lower each time
    far.line(s.b(65), CALL, 64, tr=0, leg=0.99)
    far.expr([(s.b(65), 70), (s.b(66), 104), (s.b(67), 60)])
    far.line(s.b(67), ANSWER, 62, tr=0, leg=0.99)
    far.expr([(s.b(67), 60), (s.b(68), 100), (s.b(69), 56)])
    hn3.line(s.b(65), "F#3:4 G3:2 C4:12", 60, leg=0.99)
    hn4.line(s.b(65), "F#3:4 G3:2 C4:12", 54, tr=-12, leg=0.99)
    tbn1.line(s.b(67), "C3:4 C#3:2 F#2:18", 56, leg=0.99)
    for p_ in (hn3, hn4, tbn1):
        p_.expr([(s.b(65), 80), (s.b(67), 104), (s.b(70), 80), (s.b(72), 50)])
    # the choir falls away: the ahh gives out and leaves the ohh on the F sharp minor chord and the G a semitone over it
    pads([(ohh, [0, 1, 2, 3])], s, list(range(65, 69)), PROG, cr, V(40, 58), dur_beats=BPB)
    swell(ohh, s, list(range(65, 69)), lo=76, hi=108, every=4, shape="cresc-dim")
    dv = mm.voice_lead(["F#m", "F#m", "F#m", "F#m", "F#m", "F#m"], [(49, 60), (56, 67), (61, 72), (65, 77)])
    for bar, vc_ in zip(range(69, 75), dv):
        for p_, idx in ((ahh1, (2, 3)), (ahh2, (1,)), (ohh, (0,))):
            for i in idx:
                p_.n(s.b(bar), BPB + 0.05, vc_[i], 58 - 3 * (bar - 69))
    for p_ in (ahh1, ahh2):
        p_.expr([(s.b(69), 70), (s.b(71), 98), (s.b(73), 80), (s.b(75) - 0.1, 56)])
    ohh.expr([(s.b(69), 90), (s.b(75) - 0.1, 66)])
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, list(range(65, 71)), PROG, [(50, 62), (55, 67), (60, 72), (64, 76)], V(30, 46), dur_beats=BPB)
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(65, 71)), lo=70, hi=100, every=3, shape="cresc-dim")
    bells.n(s.b(67), 12, "C5", 50)
    bells.n(s.b(71), 14, "F#4", 52)
    # the drums: the heartbeat comes back on the F sharp
    hits(range(65, 69), V(74, 96), beats=(0,), boom=True)
    for bar in range(65, 69):
        timp.n(s.b(bar) + 1, 1.0, TIMP[BASS[bar - 1]] + 1, V(54, 74)(bar))
    heartbeat(range(69, 75), V(56, 86))
    for bar in (69, 70, 71, 72):
        taiko.n(s.b(bar), 0.5, TAIKO[6], V(52, 68)(bar))
    return s


# =========================================================================== the master
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
    send = hp(send, 160)
    wet = np.vstack([signal.fftconvolve(send, ir[0])[:n], signal.fftconvolve(send, ir[1])[:n]])
    out = dry + wet
    if n_out < n:
        # a loop: what rings on past the last bar (the release of the last chord, the tail of the hall) is not thrown away
        # but goes on under the start, so that the end runs into the beginning as the music itself does
        tail = out[:, n_out:]
        out = out[:, :n_out].copy()
        k = min(tail.shape[1], n_out)
        out[:, :k] += tail[:, :k]
    else:
        out = np.pad(out, ((0, 0), (0, n_out - n)))
    for c in range(2):
        out[c] = hp(out[c], 30, order=2)
        out[c] = eq(out[c], "peak", 320, 0.8, -1.5)
        out[c] = eq(out[c], "highshelf", 7500, 0.7, 1.0)
    out /= np.max(np.abs(out)) + 1e-12
    out = stereo_comp(out, -16.0, 2.0)
    return out / (np.max(np.abs(out)) + 1e-12)


def finish(out, ceiling, g=1.0, target=None, fade_in=0.005, fade_out=END_FADE):
    """set the integrated loudness to `target` (default LOUDNESS) by measurement, limiting peaks at `ceiling` dBFS, then
    fade the ends: returns the finished stereo signal and the gain it ended with"""
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


def listen_mp3(y, path, song):
    """the theme as an MP3 to listen to on a phone, a chapter mark at each section"""
    import tempfile
    marks = []
    for k, (bar, title) in enumerate(CHAPTERS):
        a = (song.b(bar)) * song.spb
        b = (song.b(CHAPTERS[k + 1][0]) * song.spb) if k + 1 < len(CHAPTERS) else y.shape[1] / SR
        marks.append((a, b, f"{bar:02d} {title}"))
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "t.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "t.mp3")
        write_wav(wav, y.astype(np.float32))
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=The Maelgrasp\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\ntitle={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1", "-map_chapters", "1",
                        "-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "3", "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3],
                       check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)
    print(f"{path}  {y.shape[1] / SR:.1f}s, {os.path.getsize(path) / 1e6:.2f} MB")


def build(assets, work=None, sf2=None, wav_dir=None, mp3=None):
    sf2 = sf2 or SF2_DEFAULT
    song = compose_sea()
    if work:
        stems = render_stems(song, "maelgrasp", work, sf2)
    else:
        with tempfile.TemporaryDirectory() as td:
            stems = render_stems(song, "maelgrasp", td, sf2)
    n_out = int(round(song.end_beat * song.spb * SR))  # exactly the 74 bars: the file is one turn of the loop
    ir = hall_ir(rt60=3.0, seed=113)
    pre = premaster(stems, SEA_MIX, ir, n_out)
    path = os.path.join(assets, "sounds", OUT + ".ogg")
    # the encoder overshoots the limiter by a dB or so (a different amount each time) and takes a little of the loudness:
    # measure the decoded file and move the limiter's ceiling and the loudness target until its true peak lands just
    # under TRUE_PEAK and its loudness on LOUDNESS
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
    a = ap.parse_args()
    build(a.assets, a.work, a.sf2, a.wav, a.mp3)


if __name__ == "__main__":
    main()
