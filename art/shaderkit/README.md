# Shader kit

Guildmark's shader pack lives in `src/main/resources/shaderpack/Guildmark/`. The mod copies it into the game's
`shaderpacks/` folder on start (`client/shader/GuildmarkShaders.kt`) and picks it in Iris the first time. This kit
checks the pack and takes pictures of it without the game.

## The pack

The look is dark fantasy: muted colour, deep shade, heavy mist and strong light shafts.

- `shaders/lib/settings.glsl` holds every option Iris lists under Shader Pack Settings. `shaders.properties` lays
  out the settings screens and the Low, Medium, High and Ultra profiles. `lang/en_us.lang` names them.
- `shaders/program/*.glsl` are the programs. The `.vsh`/`.fsh` files beside them are two-line stubs written by
  `stubs.py`: the root folder is the Overworld, `world-1` the Nether and `world1` the End. Iris doesn't fall back
  to the root for a dimension folder, so each folder has every program.
- The passes:
  - `gbuffers_*` write albedo, normal, light levels and material into colortex0 to 2.
  - `deferred` lights them and paints the sky.
  - `gbuffers_water` leaves the waves' normal in colortex4.
  - `composite` draws the water.
  - `composite1` traces the light shafts and sun rays, at half size.
  - `composite2` lays the mist over everything.
  - `composite3` gathers bloom.
  - `composite4` exposes and grades.
  - `final` smooths edges and adds grain.
- `block.properties` maps blocks to the ids in `lib/common.glsl`. These cover swaying plants and leaves, water,
  flames, glowing blocks and Guildmark's own Wardfire and mist gate.

## Tools

They need Python 3 with moderngl, numpy and Pillow, and a GPU or Mesa's EGL. Run from the repo root:

```
python3 -I art/shaderkit/check.py src/main/resources/shaderpack/Guildmark/shaders [--quick]
python3 -I art/shaderkit/preview.py --pack src/main/resources/shaderpack/Guildmark/shaders \
    --mcjar <minecraft client jar> --assets src/main/resources/assets/guildmark --out <folder> \
    [--size 960x540] [--only noon,dusk] [--sheet] [--world world-1] [-D OPTION=value] [--timelapse]
python3 -I art/shaderkit/stubs.py src/main/resources/shaderpack/Guildmark/shaders
```

- `check.py` compiles every program as `iris_lite` runs it. It covers each dimension, with and without Distant
  Horizons, and each switch flipped from its default, so a typo in a rare branch shows up here and not in the game.
- `preview.py` renders the preview vale through the pack. The vale has a ruined tower on a hill, a cottage with
  a lantern-lit path, a Wardfire, a pond and a stream, and woods. The shots are noon, golden hour, dusk, night,
  rain, dawn, the Wardfire, a cloud deck, under the pond and late sun through the woods. Options:
  - `--sheet` adds a contact sheet.
  - `--timelapse` renders a day from the tower's top as frames and a GIF.
  - `-D` sets an option as Shader Pack Settings would, e.g. `-D BLOOM=false` or `-D MIST_DENSITY=1.5`.
  - `--world world-1` uses the Nether's programs (`world1` the End).
  - `--dump` saves every buffer after every pass and prints its range.
  - `--probe x,y` prints colortex0 at a point after every pass.
- `iris_lite.py` is a small stand-in for Iris. It reads the pack's programs, fallbacks, includes, buffer
  directives, render targets and blend and scale lines the way Iris does. It runs the passes in Iris's order, with
  the uniforms worked out as Iris documents them. It is not Iris: it covers what this pack uses, and the game
  remains the last word.
- `scene.py` builds and meshes the vale the way Minecraft meshes terrain. It covers smooth light, sky and block
  light, cutout and translucent layers, and rain cards round the camera. Textures come from the client jar and
  the mod's own assets.
