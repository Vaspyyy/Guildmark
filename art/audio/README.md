# Audio sources

Every sound in `src/main/resources/assets/guildmark/sounds/` is made by these scripts. Nothing is
sampled: the effects are synthesised with numpy/scipy, the music is composed in code and played by
FluidSynth, and the voices are Kokoro TTS: Halvard processed into a hollow undead knight, the named
characters (Wren, Tobin, Ilsa, Pip and Maren) given a lighter touch.

- `sfxkit.py`: the shared DSP toolkit (noise, filters, modal metal, creature voice source, room
  reverb, finalising and Ogg export).
- `make_sfx.py`: the 61 mono sound effects (`combat/`, `hollow_soldier/`, `fallen_warden/` and the menu
  sounds in `ui/`). Each sound is one `@recipe(...)` function that sets its variant count, length cap and
  fade-out.
- `make_music.py`: the two boss tracks (`music/`) and the two stingers (`ui/`), all built on Halvard's
  leitmotif. Each piece is a `compose_*` function, and the stem balance lives in `PHASE1_MIX`,
  `PHASE2_MIX` and `STINGER_MIX`.
- `make_voice.py`: Halvard's three lines (`voice/`). It has two stages, `tts` and `process`.
- `make_cast_voices.py`: the named characters' 55 dialogue lines (`voice/<character>/`), read from the
  language file. It has three stages, `tts`, `process` and `sounds`.

All randomness is seeded, so a rerun gives bit-identical files. Only `make_cast_voices.py sounds` touches
`sounds.json`. For everything else, if you add or remove a variant, update that file by hand.

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
```

`--wav` also writes WAV copies for listening. `--work` is where the music script keeps its per-stem
MIDI and WAV renders; it defaults to `build/music_work`. A full music render takes about a minute.

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

The game times its captions to the voice files' lengths:

| File | Length |
|---|---|
| `halvard_intro` | 8.802 s |
| `halvard_phase2` | 4.356 s |
| `halvard_death` | 4.521 s |

A new voice, speed or text changes these lengths. Check the new lengths with `soxi -D` and update the
caption timings in the code.

### Character voices

The dialogue screen speaks every line it shows: introductions, each chapter's request, the "still
waiting", "all done" and goodbye lines, the topics, and Maren's scenes. The words come from
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

`SAY_AS` in the script respells words the TTS gets wrong ("Hmph" was spelled out letter by letter, and
"Halvard" came out as "Harvard"). Every line was checked with Whisper base.en: 98% of words come back
as written.
