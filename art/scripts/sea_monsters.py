"""The sea monsters' art, all of it: the Gnashfin, the Maelgrasp's arm and the Maelgrasp's head (models, textures, glow maps, the Kotlin that
poses them), the four item icons, the figurehead on the cog (ship.py builds that one) and the previews.

    python3 -B art/scripts/sea_monsters.py src/main/resources/assets/guildmark <preview dir> art/models [kotlin dir] [options]

options (several can be given; with none, everything is made):
    --models    the three models and their files, and `sea_monsters_report.txt` (every part, what animates it, how far each model reaches, what the
                renderers must do) in the preview folder
    --items     the four item icons and their model and item JSON, and in the preview folder a sheet of them and a GIF of them turning on a shelf
    --check     compile the generated Kotlin against Minecraft's own classes and compare it with the Python poses, for the three models and for the
                cog's figurehead (needs the kit under /tmp/kcheck); the scratch folders it works in are removed afterwards
    --previews  the stills, the GIFs (and an MP4 of each, to watch where a GIF's 256 colours show), the contact sheets and an overview   --no-previews  everything but those

Each model has its own script (`sea_gnashfin.py`, `sea_arm.py`, `sea_head.py`) with its geometry, painting, Python `pose()` and Kotlin template;
the shared machinery (parts, the atlas, the painting driver, the Kotlin emission, the stage the previews use) is `sea_kit.py`. Everything is
deterministic: running it twice writes the same bytes.
"""
import os
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from PIL import Image

import sea_kit as K

REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
KOTLIN_DIR = os.path.join(REPO, 'src', 'main', 'kotlin', 'io', 'github', 'vaspyyy', 'guildmark', 'client', 'sea')


def modules():
    """[(texture name, Kotlin class name, module)] of the three models, in the order they are made."""
    import sea_arm, sea_head, sea_gnashfin
    return [('maelgrasp_arm', 'MaelgraspArmModel', sea_arm), ('maelgrasp', 'MaelgraspModel', sea_head), ('gnashfin', 'GnashfinModel', sea_gnashfin)]


def write_model(name, cls, mod, out, art, kdir, prev, say=print):
    """Builds, paints and writes one model: its two PNGs under [out]/textures/entity/sea, its Kotlin class in [kdir], its .bbmodel in [art]
    and its part list and extents report in [prev]. Returns (model, scene, texture, glow map, the problems found)."""
    t0 = time.time()
    model, scene, tex, glow = mod.painted()
    problems, used = K.check_atlas(scene, model.tex_w, model.tex_h)
    ent = os.path.join(out, 'textures', 'entity', 'sea')
    for d in (ent, art, kdir, prev):
        os.makedirs(d, exist_ok=True)
    img = Image.fromarray(tex, 'RGBA')
    img.save(os.path.join(ent, name + '.png'), optimize=True)
    Image.fromarray(glow, 'RGBA').save(os.path.join(ent, name + '_glow.png'), optimize=True)
    with open(os.path.join(kdir, cls + '.kt'), 'w') as f:
        f.write(mod.kotlin_source(model))
    K.write_bbmodel(model, img, name, os.path.join(art, name + '.bbmodel'))
    with open(os.path.join(prev, name + '_parts.txt'), 'w') as f:
        f.write(mod.parts_report(model, scene))
    parts, cubes = len(list(model.all_parts())), len(list(model.all_cubes()))
    say(f'{name}: {parts} parts, {cubes} cubes, {used} of {model.tex_w * K.HD * model.tex_h * K.HD} texels on faces, painted in {time.time() - t0:.0f} s')
    for p in problems:
        say('PROBLEM ' + p)
    return model, scene, tex, glow, problems


RENDERER_NOTES = """What the renderers must do (the three client renderers in client/sea do all of this)

All three models: units are px (1/16 block), y down, the creature faces -z and its right is -x, so each is an ordinary `LivingEntityRenderer` model:
  * `scale()` hands back the 1.501 blocks vanilla lifts a model by (translate (0, 1.501, 0)), so the model's own origin lands on the entity's feet.
  * The base texture is drawn as the model declares it (`RenderTypes::entityCutout`, cull-free); the glow map is a second submit of the same model with
    `RenderTypes.eyes(<name>_glow.png)` and the colour `ARGB.white(alpha)`: the alpha is the glow, so a model that glows less just fades its layer out.
    Only what burns is painted into the glow map; everything else in it is black. Skip the second pass when the alpha is under 0.01.
  * `setupAnim` sets every animated part every frame (including `visible`), from the render state alone: nothing in the models depends on the entity.
  * The texture PNGs are HD (twice the declared size; the UVs are normalised): 128x64 declared / 256x128 on disk for the Gnashfin, 384x256 / 768x512 for
    the arm, 512x512 / 1024x1024 for the head.

Gnashfin (GnashfinRenderState: time, swim, bend, jaw, leap, stranded, gnaw, glow, pitch, roll)
  * The origin is the middle of the body, on the spine: turn the whole fish about it (pitch about x, roll about z) before the model's own swimming.
  * `leap` is -1 when it is not leaping, else 0 to 1 over the jump (0 leaving the water, 1 re-entering). `pitch` is nose up positive, `roll` is degrees.
  * Glow alpha: `glow` (0 to 1): ten flank lights, the chin lure and its bead.
  * Reaches about 1.1 blocks above and 0.9 below the origin, 1.8 ahead and 1.5 behind (see the extents): inflate the culling box by about (1.2, 0.8, 1.2).

Maelgrasp's arm (MaelgraspArmRenderState: joints[39], cut, start, time, grip, glow, rage, piece)
  * `joints` is 13 points (x, y, z) in the arm's own frame, in blocks, y up, +z toward the ship, the origin where the arm breaks the surface (what
    `Tentacle.solve` gives); the model multiplies them by -16 and lays one segment along each pair. Nothing else needs doing per part.
  * `cut` hides segments from there to the tip (a hacked arm gets a raw stump cap); `start` hides those before it (the severed length is drawn from a
    second state with `start = cut` and its own joints, in the same frame); `grip` clenches the suckers; `rage` flushes the skin and swells its ripple.
  * Glow alpha: `glow` times a little more in a rage (`glow * (1 + 0.5 * rage)`): the veins under the skin.
  * Culling: the skin, suckers, barnacles and frill stand up to 0.9 block off the line through the joints, so a culling box round the joints wants
    that margin all round. The arm is 11.5 blocks long, so wherever its joints go it stays within 12.5 blocks of its root (over the poses tried in the
    extents it reaches 9.5 blocks up and 7 toward the ship).

Maelgrasp's head (MaelgraspRenderState: time, rise, lookYaw, lookPitch, pupil, lid, beak, lunge, ink, rage, dying, glow)
  * The origin is the waterline under the middle of the head and the mantle stands from 5.5 blocks above it to 4 below: the game lowers and raises the
    entity itself; `rise` (0 to 1) only splays and uncurls the arm roots and spreads the fins, so it can stay 0 while the head is deep.
  * The eye's centre is (0, -32, -37) px with a radius of about 11, the beak's tip (0, -6, -46): the model space of the head, y down, facing -z. The
    siphon's mouth, where the ink comes from, is centred at about (50, -4, 9) px with a radius of about 7 (the model's right is -x, so it is on
    its left flank), pointing along (0.85, 0.35, 0.40): out, a little back and down.
  * `dying` (0 to 1) sinks the head a little, rolls the eye up, clouds it over and slackens the roots; `ink` (0 to 1) kicks the siphon back and flares it.
  * Glow alpha: `glow * (1 + 0.4 * rage)`: the veins under the mantle and the ring round the eye.
  * Culling: the mantle is 5.25 blocks across (8.3 with the fins out) and 9.5 tall; on a lunge it reaches 5.6 blocks ahead of the origin. Inflate the
    entity's box by about (3, 4.5, 3).

The cog's figurehead is part of the cog's own model (CogModel, the part `figurehead`): `CogRenderState.figurehead` shows it, nothing else is needed.
"""


def write_report(prev, built):
    """Writes `sea_monsters_report.txt` into [prev]: the parts report of each model built, the figurehead's, and what the renderers must do."""
    import sea_cog
    parts = [mod.parts_report(built[name][0], built[name][1]) for name, _cls, mod in modules() if name in built]
    parts.append(sea_cog.figure_report())
    path = os.path.join(prev, 'sea_monsters_report.txt')
    with open(path, 'w') as f:
        f.write('\n\n'.join(parts) + '\n\n' + RENDERER_NOTES)
    return path


def check_all(prev, say=print):
    """Compiles the generated Kotlin of the three models and of the cog against Minecraft's own classes and compares every part's pose with the Python
    `pose()`. Returns the problems found; the scratch folders are removed afterwards."""
    import sea_cog
    problems = []
    for name, cls, mod in modules():
        work = os.path.join(prev, '_check_' + name)
        r = mod.check(work)
        say(f'{name}: Kotlin vs Python poses over {r[3]} parts: position {r[0]:.5f} px, turn {r[1]:.5f}, scale {r[2]:.5f}')
        problems += r[4]
        shutil.rmtree(work, ignore_errors=True)
    work = os.path.join(prev, '_check_cog')
    r = sea_cog.check(work)
    say(f'cog (with the figurehead): Kotlin vs Python poses over {r[3]} parts: position {r[0]:.5f} px, turn {r[1]:.5f}, scale {r[2]:.5f}')
    problems += r[4]
    shutil.rmtree(work, ignore_errors=True)
    return problems


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    flags = {a for a in argv if a.startswith('--')}
    if len(args) < 3:
        raise SystemExit(__doc__)
    out, prev, art = args[0], args[1], args[2]
    kdir = args[3] if len(args) > 3 else KOTLIN_DIR
    want = {f for f in flags if f in ('--models', '--items', '--check', '--previews')}
    everything = not want
    problems = []
    built = {}
    if everything or '--models' in want or '--previews' in want or '--check' in want:
        for name, cls, mod in modules():
            built[name] = write_model(name, cls, mod, out, art, kdir, prev)
            problems += built[name][4]
    if everything or '--items' in want:
        import sea_items
        sea_items.write_all(out, prev)
    if everything or '--models' in want:
        print('report:', write_report(prev, built))
    if '--check' in want or everything:
        problems += check_all(prev)
    if (everything or '--previews' in want) and '--no-previews' not in flags:
        import sea_previews
        sea_previews.write_all(out, prev, art, built)
    for p in problems:
        print('PROBLEM', p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
