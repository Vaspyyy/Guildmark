# Audio sources

Every sound in `src/main/resources/assets/guildmark/sounds/` is made by these scripts. Nothing is
sampled: the effects are synthesised with numpy/scipy, the music is composed in code and played by
FluidSynth, and the voices are Kokoro TTS: Halvard processed into a hollow undead knight, the named
characters (Wren, Tobin, Ilsa, Pip and Maren) given a lighter touch, the three faction leaders each in a blend of
Kokoro voices of their own, and the renegade Silvan Marrow made cold and a little uncanny. The city ambience
(`ambience/`) is synthesised the same way, its crowds, criers and night watch built from Kokoro takes.

- `sfxkit.py`: the shared DSP toolkit (noise, filters, modal metal, creature voice source, room
  reverb, finalising and Ogg export).
- `make_sfx.py`: the 116 mono sound effects (`combat/`, `hollow_soldier/`, `fallen_warden/`, `hill_troll/`, `dusk_rider/`,
  `wardfire/`, `flask/`, `marks/`, `dungeon/` and the menu sounds in `ui/`). Each sound is one `@recipe(...)`
  function that sets its variant count, length cap and fade-out.
- `make_music.py`: the four boss tracks (`music/`) and the two stingers (`ui/`). Halvard's two tracks and
  the stingers are built on his leitmotif; the field boss theme is a piece of its own in E Phrygian, and the
  Dusk Rider's is a 6/8 gallop in D minor on a horn call of its own. Each piece is a `compose_*` function, and
  the stem balance lives in `PHASE1_MIX`, `PHASE2_MIX`, `FIELD_MIX`, `RIDER_MIX` and `STINGER_MIX`.
- `make_menu_music.py`: the title-screen theme, "Where the Road Begins" (`music/menu.ogg`), Guildmark's own
  tune in D major built on `make_music.py`'s parts, rendering and mixer. It sets its loudness by measurement,
  about 4 LU under the boss tracks.
- `make_region_music.py` and `make_land_music.py`: the regions' exploration themes (`music/region/`), one
  per kind of land, each a standalone piece with a soft opening and a real ending (the game plays it once,
  then waits minutes). `make_region_music.py` has the wild and far lands': the marsh ("Murk and Reed", D
  wavering between dorian and phrygian), the ashen highlands ("Ash and Ember", an F minor sarabande on a
  falling ground), the frozen peaks ("Snow and Silence", E aeolian under falling snow), the crystal deeps
  ("Glass and Echo", lydian, on vibraphone and celesta), the open sea ("Tide and Sail", a sea song in 6/8)
  and the windswept crags ("Wind and Stone", horn calls in 5/4). `make_land_music.py` has the common
  lands': the heartland ("Hearth and Meadow", a walking tune in D major), the wildwood ("Moss and Shadow",
  C dorian on bassoon and pizzicato), the dunes ("Sand and Mirage", E hijaz on a slow caravan drum), the
  steppe ("Grass and Sky", G mixolydian at a canter), the jungle ("Vine and Ruin", B dorian on marimba and
  pan flute) and the mushroom isles ("Spore and Lantern", a music-box waltz in F lydian). Both master to
  -17 LUFS with true peaks under -1 dBTP. The caverns and the deep dark keep the game's own music.
- `make_dragon_audio.py` and `make_dragon_music.py`: the dragon, "the Unbowed": its 15 effects and their variants
  (`dragon/`: roars, growls, snores, hurt, death, wingbeats, footfalls, bite, swipe, slam, inhale, fire stream,
  fireball, fire burst, flyby), synthesised with `sfxkit.py` and levelled to the Hill Troll's and Dusk Rider's
  loudness, and its battle theme (`music/dragon.ogg`, 3:14 in C minor, a horn call that keeps returning, its last
  chord falling back into the first bar so it loops), composed in code and played through `make_music.py`'s
  parts, stems and hall, its loudness set by measurement like the menu theme's. `make_dragon_audio.py` runs both;
  `--only` takes effect names and/or `music`. Its variants are listed in `sounds.json` by hand.
- `make_magic_audio.py`: the two schools of magic, sorcery and prayer: its 25 effects and 40 files (`magic/`). Sorcery
  is cold and glassy (struck crystal, clumped sparks, rushing air, frost crackle), prayer warm (bells and gongs struck
  soft, a small choir humming on a vowel, fire). Synthesised with `sfxkit.py` like the dragon's effects, one-shots
  peaking at -3 dBFS (the soft swells lower, the cycle tick at -12 dBFS). `--only` takes effect names; `--mp3 <file>`
  joins what was rendered into one listen-through MP3, a chapter mark at each sound. Its variants are listed in
  `sounds.json` by hand.
- `make_faction_audio.py`: the three factions' and the renegade's sounds: its 25 effects and 37 files (`faction/`).
  The Starwatch's ward-lights are cold glass like the sorcery (a hum, a shimmer where a blade passes through, a falling
  chime when one is put out, a cold mote spat); the Hearthsworn steel, gravel, brass and fire (blades tapped, a sword set
  down, a low horn, a Wardfire choked, a dawn swell and one bell stroke, a warm bell chord); the Quiet Hand cloth,
  leather, coin and a brass hand bell (a purse lifted and opened, a wrist seized, a watcher's suspicion, the alarm, a
  plucked figure and a flicked coin); the renegade stone and cold glass (the standing stones waking, a cold whisper, a
  ward cracking, a palm blast, a long fall). Synthesised with `sfxkit.py` like the magic effects, one-shots peaking at
  -3 dBFS (the ward-light's hum at -14, the lifted purse at -12). `--only` takes effect names or a group such as
  `heist`; `--mp3 <file>` joins what was rendered into one listen-through MP3, a chapter mark at each sound, and a full
  run adds the members' new work sounds from `make_townsfolk_sfx.py`. Its variants are listed in `sounds.json` by hand.
- `make_companion_audio.py`: the companions' six small cues, one file each (`companion/<name>.ogg`): two soft bells
  rising a third for approval and one low note sagging a fourth for disapproval, a body going down on one knee in its
  kit (the knee, steel and leather, a breath let out), a breath drawn in with a hand slapped into a hand and a small
  ember swell for a revive, a short questioning figure of three plucked lute notes (a companion has something to ask)
  and a warm swell of lute and low strings for the oath. Synthesised with `sfxkit.py` from the prayers' bells, the
  factions' plucked and bowed strings and the townsfolk's and combat's steel, leather and ground, in D major, the
  Wardfires' key; the cues that come often are quiet (peaks at -11 and -12 dBFS for approval and disapproval, -7 for
  the questioning figure), the fall, the revive and the oath at -4.
  `--only` takes effect names; `--mp3 <file>` joins what was rendered into one listen-through MP3, a chapter mark at
  each sound. Their events are listed in `sounds.json` by hand.
- `make_ship_audio.py`: the cog's 19 events and 40 files (`ship/`): her oak creaking, the sail filling and luffing,
  the halyard, the wake, lapping water and the wind in her rigging (the three loops), the anchor going down and coming
  up, the bell, grounding and knocks, the wheel and the hold. `--only` takes event names; `--mp3 <file>` joins what was
  rendered into one listen-through MP3, and `--fragments <dir>` writes the `sounds.json` entries and subtitles for
  merging. Its variants are listed in `sounds.json` by hand.
- `make_sea_audio.py` and `make_sea_music.py`: the sea monsters, the Gnashfins and the Maelgrasp: 31 effects and 55
  files (`sea/`: the Gnashfins' fin, leap, dive, snap, bite, gnaw, flop, hurt and death; the Maelgrasp's omens,
  bubbles, surge, bursting, rearing, slamming, sweeping and gripping arms, hurt and severed arms, the ship's timbers
  groaning, and its head rising, roaring, blinking, biting, spewing ink, diving and dying), synthesised with
  `sfxkit.py` and the cog's parts from `make_ship_audio.py` (bubbles, oak under strain, flesh and sucker squelches, a
  throat the size of a hold) and levelled by the loudest 100 ms like the cog's and the dragon's, and the Maelgrasp's
  battle theme (`music/maelgrasp.ogg`, 2:28 in F sharp minor, a heaving 6/8 on a three-note call, built as exactly one
  turn of a loop so that its last heartbeat runs into its first), composed in code and played through
  `make_music.py`'s parts, stems and hall, its loudness set by measurement like the dragon's. `make_sea_audio.py` runs
  both; `--only` takes effect names (`gnashfin_snap`, `sea.maelgrasp.roar`) and/or `music`; `--mp3 <file>` joins what
  was rendered into one listen-through MP3, a chapter mark at each file, and `--fragments <dir>` writes
  `sea_sounds.json` and `sea_subtitles.json` for merging. Its variants are listed in `sounds.json` by hand.
- `make_island_audio.py`: the islands' 28 events and 101 files (`island/`): the surf on the sand, on rock and in a cave,
  the wind (a stereo bed and six gusts), the gulls (calls, cries, alarms, wings, squabbles, splashes and a colony loop),
  the lighthouse (a clockwork loop, the fog horn and the fog bell), the wreck (groaning timbers, the torn sail, the
  drowned bell), the smugglers' cove (drips, whistled signals, the camp), the drowned shrine (a drone loop, chimes,
  bubbles, whispers), the fishing haven (boats and rope) and the discovery sting. Synthesised with `sfxkit.py`, the cog's
  parts from `make_ship_audio.py` and the harbour's gull voice, one-shots peaking at -3 dBFS and levelled by the loudest
  100 ms like the cog's, the four loops (the wind bed, the gull colony, the lighthouse clockwork and the shrine's drone,
  8 to 12 s) exact at -27 dBFS RMS; how far each sound carries is in its recipe (`reach`, 16 to 128 blocks) and in the
  script's header, with the lengths. The surf's files also have marks (`break`, `stall`, `impact`: moments in seconds a
  particle can sync to). `--only` takes names or groups (`surf`, `gull`, `island.wreck.bell`); `--mp3 <file>` joins the
  sounds into one listen-through MP3, place by place, at about their pace and levels in the game; `--variants-mp3 <file>`
  joins every file, a chapter mark at each; `--fragments <dir>` writes `island_sounds_entries.json` (the `sounds.json`
  entries, with `music.isles`), `island_subtitles.json` and `island_marks.json` for merging. Its variants are listed in
  `sounds.json` by hand.
- `make_island_music.py`: the islands' theme, "Sun and Spindrift" (`music/isles.ogg`, 2:33.6, 48 bars of A major at 75
  beats a minute): a harp, plucked strings and a wooden flute over soft strings and a light hand drum, on a tune made
  of the gull's leap of a fourth, built as exactly one turn of a loop (its last chord, E, A, B, is its first moved a
  fourth) so that it joins itself. Composed in code and played through `make_music.py`'s parts, stems and hall, its
  loudness set by measurement like the sea theme's (-18 LUFS, true peak under -1 dBTP in the encoded file). `--work <dir>`
  keeps the per-stem renders and `--reuse` mixes again from them, `--mp3 <file>` writes it to listen to, a chapter mark at
  each section and the loop's join once more at the end.
- `make_voice.py`: Halvard's three lines (`voice/`). It has two stages, `tts` and `process`.
- `make_renegade_voice.py`: the renegade Silvan Marrow's three lines (`voice/renegade_<line>.ogg`), in a blend of
  three American men's voices and cold, thin processing: a faint whispered double, a stone slap and wind (out on a
  hill of standing stones, not in a hall), and a glassy shimmer on his phase-two cry. Two stages, `tts` and
  `process`, as for Halvard.
- `make_cast_voices.py`: the named characters' 344 dialogue lines (`voice/<character>/`), read from the
  language file: Wren, Tobin, Ilsa, Pip and Maren, the faction leaders Idris, Corra and Odile, and the four
  companions Bram, Ysolde, Kestrel and Lucan (240 of the lines: everything a companion says but their written notes,
  barks on the road included, each in a blend of Kokoro voices of their own, dry and close). It has three
  stages, `tts`, `process` and `sounds`.
- `ambkit.py`: what the city ambience shares on top of `sfxkit.py`: the `@recipe` registry, seamless
  stereo loops (the extra tail crossfaded over the head), RMS levelling, street and square reverbs,
  stereo panning, and waveform and spectrogram pictures for checking a sound by eye.
- `make_ambience.py`: the city ambience (`ambience/`), one recipe module per part of town in
  `ambience/`: `bells.py`, `workshops.py`, `harbour.py`, `wind.py` and `voices.py` (the crowds, criers
  and watch). The four background beds are stereo loops that play unpositioned; everything else is a
  mono one-shot the game places in the world.
- `make_city_voices.py`: the raw Kokoro takes behind the crowds, criers and night watch.
- `make_mill_audio.py`: the capitals' mills, three seamless mono loops (`mill/`) the game plays by a mill while it
  turns, louder and a little higher the faster it goes (`client/mill/MillSounds.kt`): `sails` (8 s, one turn: four
  sails sweeping past the tower, the wind in the lattice, the windshaft groaning, the stocks creaking), `stones` (6 s:
  the millstones grinding inside the mill, heard through its walls, the stone nut's cogs and the damsel knocking the
  shoe) and `wheel` (8 s, one turn: sixteen paddles slapping into the stream, the water pouring off them, the churn,
  the timbers and the axle). Built in a circle with the cog's parts from `make_ship_audio.py` and set quiet by RMS
  (-30, -30 and -28 dBFS, peaks under -10). `--only` takes names; `--mp3 <file>` joins them into one listen-through
  MP3. They are listed in `sounds.json` by hand (`mill.sails`, `mill.stones`, `mill.wheel`).

All randomness is seeded, so a rerun gives bit-identical files. Only `make_cast_voices.py sounds` and
`make_ambience.py --sounds` touch `sounds.json`, each rewriting only its own entries. For everything
else, if you add or remove a variant, update that file by hand.

## Requirements

- Python 3 with numpy and scipy, plus mido for the music.
- `ffmpeg` with libvorbis, and `sox`.
- `fluidsynth` with `/usr/share/sounds/sf2/FluidR3_GM.sf2` (use `--sf2` to point elsewhere).

These versions were tested: Python 3.13, FluidSynth 2.3.4, SoX 14.4.2 and ffmpeg 6.1.

## Run (from the repo root)

`-B` stops Python from writing a `__pycache__` folder into `art/audio`.

```
python3 -B art/audio/make_sfx.py src/main/resources/assets/guildmark [--wav <dir>] [--only combat/parry,fallen_warden/]
python3 -B art/audio/make_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only boss_appear]
python3 -B art/audio/make_menu_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>]
python3 -B art/audio/make_region_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only marsh,ashen]
python3 -B art/audio/make_land_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only heartland,dunes]
python3 -B art/audio/make_dragon_audio.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only roar,growl,music]
python3 -B art/audio/make_magic_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only arc_bolt_cast,flare] [--mp3 <file>]
python3 -B art/audio/make_faction_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only ward_light,heist.alarm] [--mp3 <file>]
python3 -B art/audio/make_ship_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only creak,wake] [--mp3 <file>] [--fragments <dir>]
python3 -B art/audio/make_sea_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only gnashfin_snap,music] [--mp3 <file>] [--fragments <dir>] [--theme-mp3 <file>]
python3 -B art/audio/make_island_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only surf,gull,island.wreck.bell] [--mp3 <file>] [--variants-mp3 <file>] [--fragments <dir>]
python3 -B art/audio/make_island_music.py src/main/resources/assets/guildmark [--work <tmp-dir>] [--wav <dir>] [--mp3 <file>] [--reuse]
python3 -B art/audio/make_mill_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only sails,wheel] [--mp3 <file>]
```

`--wav` also writes WAV copies for listening. `--work` is where the music script keeps its per-stem
MIDI and WAV renders; it defaults to `build/music_work`. A full music render takes about a minute.

### City ambience

```
python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark --raw <raw-dir> [--only bell_,gull] [--modules bells,harbour] [--wav <dir>] [--png <dir>]
python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark --sounds
```

`--only` takes sound names or prefixes, `--modules` the recipe modules to load. `--png` draws each
sound's waveform and spectrogram, the quickest check of a loop's seam or a bell's ring. `--raw` is the
folder of speech takes from `make_city_voices.py` (below); without it the crowds, criers and watch are
skipped and keep their files. The second command rewrites the `ambience.*` entries of `sounds.json`
from the files on disk: `EVENTS` in the script sets how far each sound carries and its subtitle. Run it
after adding or removing a sound or a variant.

The city's speech uses the same virtualenv, model files and `--espeak-data` as the voice lines below:

```
<venv>/bin/python -I -B art/audio/make_city_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--only hawker_,watch_] [--espeak-data <dir>]
python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark --raw <raw-dir> --modules voices
```

None of the criers or the watchman uses a voice the named characters or Halvard speak with.

### Voice lines

1. Set up the TTS in a virtualenv: `pip install kokoro-onnx soundfile`. This was tested with kokoro-onnx 0.6.1.
2. Download the model files into a folder of their own:
   - https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx
   - https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin
3. Run the two stages:

```
<venv>/bin/python -I -B art/audio/make_voice.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir>
python3 -B art/audio/make_voice.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark
```

The defaults are voice `bm_lewis` at speed 1.0. `--voice` also takes a blend such as
`bm_george*0.6+bm_lewis*0.4`.

espeak-ng refuses a data path longer than 160 characters. If the `tts` stage fails with
`Error processing file .../phontab`, copy the `espeak-ng-data` folder from the `espeakng_loader`
package to a short path and pass it as `--espeak-data <dir>`.

Silvan Marrow's lines (`make_renegade_voice.py`) run the same way, `tts` then `process`. His voice is the blend
`am_liam*0.4+am_fenrir*0.3+am_santa*0.3`, read as American English (the American voices turned "dark" into "dock"
and "chart" into "shot" when given British phonemes), and he speaks up, 1 to 2 dB under Halvard's level. Whisper
base.en reads all three lines back exactly as written.

The game times its captions to the voice files' lengths:

| File | Length |
|---|---|
| `halvard_intro` | 8.802 s |
| `halvard_phase2` | 4.356 s |
| `halvard_death` | 4.521 s |
| `renegade_intro` | 5.478 s |
| `renegade_phase2` | 3.234 s |
| `renegade_death` | 2.720 s |

A new voice, speed or text changes these lengths. Check the new lengths with `soxi -D` and update the
caption timings in the code.

### Character voices

The dialogue screen speaks every line it shows: introductions, each chapter's request, the "still
waiting", "all done" and goodbye lines, the topics, Maren's scenes, and a leader's "work" offer, the grumble when
woken and the words for a rival or an expelled member. The words come from
`en_us.json`, so after changing a character's line, voice it again. Use the same virtualenv, model files
and `--espeak-data` as for Halvard:

```
<venv>/bin/python -I -B art/audio/make_cast_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--only tobin] [--keys '^intro$']
python3 -B art/audio/make_cast_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--only tobin] [--keys '^intro$']
python3 -B art/audio/make_cast_voices.py sounds --assets src/main/resources/assets/guildmark
```

`--only` takes character ids and `--keys` a regex on the line key (`step1`, `topic.maps.0`, `scene0.p2`).
`sounds` adds a `voice.<character>.<line>` event for every recorded line; a line without a recording
falls back to the typed-text ticks in game.

| Character | Kokoro voice | Speed | Processing |
|---|---|---|---|
| Wren | `af_heart` | 1.06 | small room |
| Tobin | `bm_george` | 0.9 | down 1 semitone, a little grit, small room |
| Ilsa | `bf_emma` | 0.94 | chapel reverb |
| Pip | `bm_fable` | 1.06 | small room |
| Maren | `af_bella` | 0.9 | down 0.6 semitone, a faint chorused octave above, large cold hall |
| Idris | `bm_daniel*0.6+am_echo*0.4` | 0.8 | down 0.6 semitone, a slight tremor, a little grit, a library (books soak up the highs) |
| Corra | `bf_isabella*0.7+bm_george*0.3` | 1.0 | down 2 semitones, a stone barracks with a bright tail |
| Odile | `bf_alice*0.55+af_nicole*0.45` | 0.92 | down 1 semitone, a velvet top, a small close attic |

A voice may be a blend of Kokoro voices (`name*weight+name*weight`, as `--voice` takes for Halvard). The three
leaders' blends are theirs alone: no other line in the game is read with them. `.note` lines are written notes,
never spoken, and the "gated" line is not voiced.

`SAY_AS` in the script respells words the TTS gets wrong ("Hmph" was spelled out letter by letter, and
"Halvard" came out as "Harvard"). Every line was checked with Whisper base.en: 98% of words come back
as written (99% for the leaders' 49 lines, 42 of them exactly).

### Townsfolk

The sounds the city's people make (`townsfolk/`): their work and play, the bard's tunes, and what they
say as you pass.

- `make_townsfolk_sfx.py`: the 81 effects (31 events and their variants, the factions' mages, knights and
  thieves among them, and the town crier's chalk, notice and scroll), each timed to a townsfolk animation, and the
  bard's four lute tunes (`townsfolk/bard/tune1..4`). The effects are close-up and dry
  apart from a small room (the crier's are out in the square, with its slap off the house fronts instead).
  Each is set to a loudness target (its loudest 100 ms, K-weighted) instead of by
  peak: the smith's hammer is the loudest at -12 LUFS, the soft sounds sit at -17 to -18, and peaks stay under
  -1 dBFS. `@effect(...)` on each recipe sets its variants, subtitle, target and carry distance. The tunes
  are composed in the script and played by FluidSynth's General MIDI nylon guitar, with a second string a
  few cents sharp for the lute's paired courses and a small synthesised frame drum in the dances. They are
  levelled to -18 LUFS integrated.
- `make_townsfolk_voices.py`: the 527 voice lines (`townsfolk/voice/<timbre>/<line>`) in four Kokoro voices, 108 of
  them what the three factions' members say in the street (`mage_`, `knight_`, `thief_`, `alarm_`, `suspect_`, `calm_`
  and `purse_` lines, in the man's, the woman's and the old man's timbres) and 226 the town crier's news (`crier_`
  lines, in the man's and the woman's).
  It also writes the two fragment files the lead merges into `sounds.json` and `en_us.json`:
  `townsfolk_sounds.json` and `townsfolk_lang.json`.

```
python3 -B art/audio/make_townsfolk_sfx.py src/main/resources/assets/guildmark [--wav <dir>] [--only smith.hammer,tavern.,tunes] [--work <tmp-dir>]
<venv>/bin/python -I -B art/audio/make_townsfolk_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--only man,child] [--keys '^greet'] [--espeak-data <dir>]
python3 -B art/audio/make_townsfolk_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--only man] [--keys '^greet'] [--wav <dir>]
python3 -B art/audio/make_townsfolk_voices.py fragments
```

`--only` takes event names without the `townsfolk.` prefix, a group prefix such as `tavern.`, or `tunes`.
`--work` holds the tunes' MIDI and FluidSynth renders (default `build/townsfolk_music_work`). The whole
effects script takes under a minute, and the `tts` stage a few minutes. The voices script's `--only` takes
timbres and `--keys` a regex on the line name. A `phonemes` stage (the `tts` arguments without `--out`)
prints what Kokoro will say for each line after `SAY_AS`. `fragments` rewrites both fragment files from the
two scripts and warns about any voice line that has not been rendered.

| File | Tune | Length |
|---|---|---|
| `tune1` | "The Copper Kettle", a double jig in D, 6/8 | 37.1 s |
| `tune2` | "Over the Market Square", a reel in G | 41.9 s |
| `tune3` | "The Lantern by the Water", a slow air in A minor, 3/4 | 39.1 s |
| `tune4` | "The Guildhall Hornpipe", a hornpipe in A | 44.4 s |

Each tune starts on its first note and ends on a chord that dies away to silence, so they can play back to
back.

| Timbre | Kokoro voice | Speed | Processing | Lines |
|---|---|---|---|---|
| `man` | `am_puck` | 1.0 (the crier 0.88) | street (the crier: the square) | 223 |
| `woman` | `af_nova` | 1.12 (the crier 0.97) | street (the crier: the square) | 223 |
| `old` | `am_adam` | 0.86 | down 1.2 semitones, a slight tremor, a little grit, street | 75 |
| `child` | `af_jessica` | 1.1 | up 3 semitones, street | 6 |

All four read British phonemes (`en-gb`), as the city's criers do. None of them is a voice used by the named
characters, Halvard, the criers or the watch. Old women use `woman`, and the game varies the pitch a little
per townsperson. Each line plays as `townsfolk.voice.<timbre>.<line>` and is subtitled with its own text
(`say.guildmark.<line>`). Line lengths do not matter: nothing in the game is timed to them, so a line can be
voiced again freely (the town crier's are the exception, below). `SAY_AS` respells "Hmph" (otherwise spelled out
letter by letter) and "Hmm" (otherwise a weak, swallowed sound), and puts a comma where a line breathes when the recogniser lost a word without it.
`PHONEMES` gives the IPA outright for the eight lines no respelling fixed: the American voices turn the British
long "a" of "stars", "guards" and "hearth" into an "o" ("stalls", "gods", "Hawthorne") unless the r is sounded,
and `alarm_0` ("Thief! Stop, thief!") is written out so that its lead-in can be. `PHONEMES_FOR` gives a line other
phonemes for one timbre only (the woman's and the old man's `alarm_0`, whose first "Thief" came out as "Thieve"
with a plain "f"). Kokoro also lets the first word of a take go (an opening "th" is almost silent), so `LEAD_IN`
has a throw-away "oh" read in front of such a line and the `tts` stage cuts it off again where its vowel ends
(`cut_lead_in`, with an 8 ms fade-in): "Thief" then starts as it would in the middle of a sentence.
The processing notches out thin whistles that Kokoro's vocoder leaves at 4.8 and
9.6 kHz. It sets every line to the named characters' speech level, with cries 1 to 2 dB above it and
mutters 1 to 3 dB below. The first 193 lines were checked by their phonemes, speaking rate, pitch and spectrograms,
the 108 faction lines also with Whisper base.en (the encoded files, with a retry when its decoder loops on a word):
107 of them come back exactly as written. The one that does not is the man's `knight_kin_0` ("Hearth and road,
friend." is heard as "Harthing Road, friend"); the larger fp32 model of Whisper also hears the old man's `alarm_0`
as "Thieve, stop thief".

The town crier's 113 lines (`CRIER`) are in the man's and the woman's voice only: an old crier borrows the man's a
little lower, as old men do with every line their own voice lacks, so there are no `old` takes. They are the
everyday voices crying instead of talking (`process_crier`): read slower (`CRIER_SPEED`, 0.88 and 0.97), each take
cut into its calls where the voice rests between them, each call's last stressed vowel drawn out by a fifth and the
last call's by a third (`CRIER_DRAW`, with `ambience/voices.py`'s WSOLA) and at least 0.36 s left between calls, then
projected (less chest, +3.5 dB around 2.6 kHz, a gentle 2.5:1 compressor) and set in the square: a slap off its stone
fronts (34 to 283 ms, `CRIER_SLAP`) and a faint open tail ducked under the words. Every crier line sits at -19 LUFS
(within 0.3), about 2.3 LU over the everyday lines' average, with true peaks under -1 dBTP, and its `sounds.json`
entry has `attenuation_distance` 48 so the whole square hears it. The server gives each line 100 ticks, so none may
run over 4.5 s: the longest are the man's and the woman's `crier_fever`, 3.7 and 3.8 s. `CRIER_SPEED_FOR` gives a
line its own speed (only the woman's `crier_festival_soon` and `crier_blight_carrot`, a little quicker, which Whisper
heard better). `CRIER_PHONEMES` puts right, in espeak's British reading, what Whisper base.en heard wrong: "Oyez" is
read oh-YEZ (heard as "Oh yes", as criers say it) and "murrain" MURR-in; the r is sounded where the American voices
lost or bent a vowel without it ("dear", "are", "short", "board", "pork", "banners", "bumper", "iron"); and as both
voices say a short vowel a step lower than written, "Bread" is read with the vowel of "bid" (else "Brad") and the
hens in "among the hens" likewise. `CRIER_PHONEMES_FOR` has a line's fixes for one timbre (the man's "Bring, in the
harvest", else "bringing the harvest"). With them Whisper base.en hears 105 of each voice's 113 lines word for word
(98.3% of the man's words, 98.8% of the woman's; the fp32 model 98.0% and 98.8%). What it still mishears is a single
word in a short line: "Cod" as "Caught" (the man's both, the woman's cheap), the woman's "Beetroot" as "Beat wrote",
"pest" as "past", "murrain" as "Maran" (a word it does not know), and once each "Flint" as "Flynn", "Coal" as "Call",
"Mutton" as "Martin", the man's "Wheat is dear" as "We is dear", "hens are laying" as "hands" and "want of flour" as
"one of flour", and the woman's "ovens" as "oven". The 226 takes were also checked for clicks (none) and clipping
(none).
