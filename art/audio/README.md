# Audio sources

Every sound in `src/main/resources/assets/guildmark/sounds/` is made by these scripts. Nothing is
sampled: the effects are synthesised with numpy/scipy, the music is composed in code and played by
FluidSynth, and the boss voice is Kokoro TTS processed into a hollow undead knight.

- `sfxkit.py`: the shared DSP toolkit (noise, filters, modal metal, creature voice source, room
  reverb, finalising and Ogg export).
- `make_sfx.py`: the 50 mono sound effects (`combat/`, `hollow_soldier/`, `fallen_warden/`). Each sound
  is one `@recipe(...)` function that sets its variant count, length cap and fade-out.
- `make_music.py`: the two boss tracks (`music/`) and the two stingers (`ui/`), all built on Halvard's
  leitmotif. Each piece is a `compose_*` function, and the stem balance lives in `PHASE1_MIX`,
  `PHASE2_MIX` and `STINGER_MIX`.
- `make_voice.py`: Halvard's three lines (`voice/`). It has two stages, `tts` and `process`.

All randomness is seeded, so a rerun gives bit-identical files. The scripts do not touch `sounds.json`.
If you add or remove a variant, update that file by hand.

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
