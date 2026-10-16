"""The island gull: its model, its three textures, the Kotlin that poses it, the Blockbench file and the previews.

    python3 -B art/scripts/island_gull.py [assets dir] [preview dir] [art/models dir] [kotlin dir] [options]

With no arguments every output goes where it belongs, from the repo root: the textures into
`src/main/resources/assets/guildmark/textures/entity/island/` (`gull.png`, `gull_blackback.png`, `gull_young.png`), the model class into
`src/main/kotlin/io/github/vaspyyy/guildmark/client/island/GullModel.kt`, the Blockbench file into `art/models/gull.bbmodel`, and the previews
(the parts report and the GIFs) into <preview dir> (default: `guildmark-previews/island` under the system's temp directory).

options (with none, everything is made):
    --model       the textures, the Kotlin and the .bbmodel         --check     compile GullModel.kt against Minecraft's own classes and compare
    --previews    the stills and the GIFs (--only a,b,c for some)               its setupAnim with the Python animation, pose by pose
    --no-previews everything but the previews                       --no-check  skip that check

How it is made:
* `island_gull_geo.py` the parts and cubes; `island_gull_anim.py` what the bird does, written once in a small subset of Python between its
  `# ==== DSL` markers; `island_gull_paint.py` the three birds' painting; `island_kit.py` turns the animation into Kotlin; `island_gull_previews.py`
  the pictures. The previews run the very functions the Kotlin is made from, and `--check` compiles that Kotlin and compares the two numerically.
* Everything is deterministic: running it twice writes the same bytes.
"""
import os
import re
import shutil
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np
from PIL import Image

import sea_kit as K
import island_kit as IK
import island_gull_anim as A
import island_gull_geo as G
import island_gull_paint as PT

REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'guildmark')
KOTLIN_DIR = os.path.join(REPO, 'src', 'main', 'kotlin', 'io', 'github', 'vaspyyy', 'guildmark', 'client', 'island')
STATE_KT = os.path.join(KOTLIN_DIR, 'GullRenderState.kt')
MODELS_DIR = os.path.join(REPO, 'art', 'models')
PACKAGE = 'io.github.vaspyyy.guildmark.client.island'


# ================================================================== the model, painted

def built():
    """(model, scene): the geometry packed into its atlas, and the animation told its rest pose."""
    model = G.build()
    K.pack_atlas(model)
    scene = K.SScene(model, G.DOUBLE)
    A.set_rest({p.name: (np.array(p.pivot, float), np.array([np.radians(a) for a in p.rot], float)) for p in model.all_parts()})
    return model, scene


def painted():
    """(model, scene, [(name, texture)] of the three birds)."""
    model, scene = built()
    return model, scene, PT.paint_all(scene, model)


# ================================================================== the Kotlin

KT_HEAD = r'''package io.github.vaspyyy.guildmark.client.island

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import java.util.EnumSet
@@IMPORTS@@

/**
 * A gull: a herring gull, a great black-backed gull or a young one (three textures, one model), a little under a block and a half across the
 * wings. Models are in pixels with y down and the bird facing -z (its right is -x); the origin is the ground under its feet when it stands,
 * and [BODY_Y] is how high the middle of its body is over them: the renderer pitches and rolls the bird in flight about that point (the model
 * never rolls or pitches the whole bird itself, apart from what it does with its own body: crouching, a flare, a plunge).
 *
 * The bird is a tree of parts hung from `body`: seven stepped slices of torso; a neck in two links carrying the head (skull, hooked bill, lower
 * bill, two eyes and a fish for after a dive); a tail of three feathers that fans; two legs of three links; and two wings, each of an arm, a
 * forearm and a hand with its own four primaries and a flap of secondaries on the arm and the forearm, so a wing folds at its shoulder, elbow
 * and wrist (a Z, as a real wing folds, the black tips crossing over the tail), spreads, beats with the hand and the tip lagging the arm,
 * flexing its primaries and fanning them, and glides with a little dihedral.
 *
 * What [GullRenderState] says: `flying`, `spread`, `flap` and `flapPhase` (the beat), `bank` (the outer wing sweeps up), `hover`, `floating`
 * (riding low on the water), `headYaw`, `headPitch` and `beak` (where it looks and how wide it gapes), `walk` and `walkPhase` (the gait), and
 * `action` with `actionTime` (a scripted action from the start: [ACTION_TICKS] says how long each lasts; every action is blended in over its
 * first ticks and out over its last, so none pops). It also holds its own small stirrings: the head turning from look to look, blinking and
 * the tail flicking, which die away when an action or a walk has the bird.
 *
 * Every pose is made by the functions below, which come from one place: art/scripts/island_gull_anim.py (edit it there). That script writes
 * these functions as Kotlin, runs them as they are for the previews, and checks this compiled class against them over thousands of poses. The
 * geometry and the texture come from island_gull_geo.py and island_gull_paint.py: the texture is HD (the model declares @@W@@x@@H@@ and the PNGs
 * are painted at twice that). It is drawn with [RenderTypes.entityCutout] (the wings, the tail and the feet are cut to shape in the texture's
 * alpha, and that type does not cull back faces, so a thin card is seen from both sides).
 */
class GullModel(root: ModelPart) : EntityModel<GullRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

@@PARTS@@

    override fun setupAnim(state: GullRenderState) {
        super.setupAnim(state)
        poseAll(state)
    }

    // What the animation does to a part: turn it, move it, scale it (set, not added to), show or hide it
    private fun turn(p: ModelPart, x: Float, y: Float, z: Float) {
        p.xRot += x
        p.yRot += y
        p.zRot += z
    }

    private fun shift(p: ModelPart, x: Float, y: Float, z: Float) {
        p.x += x
        p.y += y
        p.z += z
    }

    private fun squash(p: ModelPart, x: Float, y: Float, z: Float) {
        p.xScale = x
        p.yScale = y
        p.zScale = z
    }

    private fun show(p: ModelPart, on: Boolean) {
        p.visible = on
    }

@@FUNCS@@

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "gull"), "main")

        /**
         * How long each action lasts, in ticks, indexed by the action constants of [GullRenderState] (0, `NONE`, is not an action: its entry is
         * unused). The entity runs `actionTime` from 0 to this and then back to `NONE`: the pose blends in over the first few ticks and out over
         * the last few, so ending an action there never pops.
         */
        val ACTION_TICKS = intArrayOf(@@TICKS@@)

        /** How high the middle of the body is over the soles of the feet when it stands (px): the point the renderer pitches and rolls the bird about. */
        const val BODY_Y = @@BODY_Y@@

        /** How far the body sinks while it floats (px): it rides with its belly in the water, so the entity sits it on the surface at its feet's level. */
        const val FLOAT_SINK = @@FLOAT_SINK@@

        /** Ticks into TAKEOFF at which the feet leave the ground: the entity starts to rise then (before it, the bird only crouches and springs). */
        const val TAKEOFF_LIFT = @@TAKEOFF_LIFT@@

        /** Ticks into LAND at which the feet touch the ground (before it, the bird flares in the air; after it, it settles and folds its wings). */
        const val LAND_TOUCH = @@LAND_TOUCH@@

        /** Ticks into DIVE at which the beak enters the water, and at which the bird comes up again (wings folded in between, then opening to climb out). */
        const val DIVE_HIT = @@DIVE_HIT@@
        const val DIVE_SURFACE = @@DIVE_SURFACE@@

        private const val PI_F = 3.1415927f

        // The numbers the animation uses (the same as in island_gull_anim.py)
@@CONSTS@@
        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
@@CALLS@@
            return LayerDefinition.create(mesh, @@W@@, @@H@@)
        }
@@CHUNKS@@@@FACES@@    }
}
'''


def translator():
    fields, consts = IK.parse_state_fields(open(STATE_KT).read())
    names = {k: v for k, v in vars(A).items() if k.isupper()}
    for k, v in consts.items():
        names[k] = v
    return IK.Translator(A.DSL, names, fields, 'GullRenderState', A.STATE_CONSTS)


def kotlin_source(model):
    """GullModel.kt as a string: the template above, the animation translated from `island_gull_anim.py`, the mesh generated from [model]."""
    tr = translator()
    funcs, consts, parts_used, sets_used, imports = tr.kotlin()
    # the part sets are members, named in lower camel case
    set_name = {n: IK.camel(n.lower()) for n in sets_used}
    for n in sorted(sets_used, key=len, reverse=True):
        funcs = re.sub(r'\b%s\[' % n, set_name[n] + '[', funcs)
    singles = [p for p in parts_used if not any(p in getattr(A, s) for s in sets_used)]
    lines = []
    for p in singles:
        lines.append(f'    private val {IK.camel(p)} = part("{p}")')
    if lines:
        lines.append('')
    for n in sets_used:
        lines.append(f'    private val {set_name[n]} = arrayOf({", ".join(chr(34).join(("part(", p, ")")) for p in getattr(A, n))})')
    parts_src = '\n'.join(lines)
    # BODY_Y and ACTION_TICKS are public (written by the template); the rest of the constants are private
    consts = '\n'.join(ln for ln in consts.split('\n') if not re.match(r'\s*private (const )?val (BODY_Y|ACTION_TICKS|FLOAT_SINK|TAKEOFF_LIFT|LAND_TOUCH|DIVE_HIT|DIVE_SURFACE)\b', ln))
    calls, chunks, faces = K.kt_mesh([('gull', model.parts)], model.tex_w, model.tex_h)
    out = KT_HEAD
    out = out.replace('@@IMPORTS@@', '\n'.join('import ' + i for i in sorted(set(imports))))
    out = out.replace('@@PARTS@@', parts_src).replace('@@FUNCS@@', funcs)
    out = out.replace('@@TICKS@@', ', '.join(str(t) for t in A.ACTION_TICKS)).replace('@@BODY_Y@@', IK.kt_float(A.BODY_Y))
    for nm in ('FLOAT_SINK', 'TAKEOFF_LIFT', 'LAND_TOUCH', 'DIVE_HIT', 'DIVE_SURFACE'):
        out = out.replace(f'@@{nm}@@', IK.kt_float(getattr(A, nm)))
    out = out.replace('@@CONSTS@@', consts + '\n' if consts else '')
    out = out.replace('@@CALLS@@', calls).replace('@@CHUNKS@@', chunks).replace('@@FACES@@', '\n' + faces + '\n' if faces else '')
    out = out.replace('@@W@@', str(model.tex_w)).replace('@@H@@', str(model.tex_h))
    names = {p.name for p in model.all_parts()}
    for m_ in re.finditer(r'part\("([a-z0-9_]+)"\)', out):
        assert m_.group(1) in names, m_.group(1)
    return out


# ================================================================== checking the Kotlin against the Python animation

def check_cases(seed=11):
    """Render states to compare the Kotlin with `A.pose` on: every field over its range at random, every action at every tick of its length in
    the states the entity holds for it, and the corners."""
    rng = np.random.default_rng(seed)
    out = []

    def rnd(**over):
        c = dict(time=float(rng.uniform(0, 9000)), flying=float(rng.choice([0.0, 1.0, rng.uniform(0, 1)])), spread=float(rng.uniform(0, 1)),
                 flapPhase=float(rng.uniform(0, 400)), flap=float(rng.choice([0.0, 1.0, rng.uniform(0, 1)])), bank=float(rng.uniform(-1, 1)),
                 hover=float(rng.choice([0.0, 1.0, rng.uniform(0, 1)])), floating=float(rng.choice([0.0, 0.0, 1.0, rng.uniform(0, 1)])),
                 headYaw=float(rng.uniform(-80, 80)), headPitch=float(rng.uniform(-50, 50)), beak=float(rng.uniform(0, 1)),
                 walk=float(rng.choice([0.0, 1.0, rng.uniform(0, 1)])), walkPhase=float(rng.uniform(0, 80)), action=0, actionTime=0.0,
                 fish=bool(rng.integers(0, 2)), variant=int(rng.integers(0, 3)))
        c.update(over)
        return c
    for _ in range(160):
        out.append(rnd())
    for a in range(1, len(A.ACTION_TICKS)):
        n = A.ACTION_TICKS[a]
        for k in range(0, n + 1):
            out.append(rnd(action=a, actionTime=float(k) + float(rng.choice([0.0, 0.5, 0.25]))))
        for _ in range(6):
            out.append(rnd(action=a, actionTime=float(rng.uniform(0, n))))
    base = dict(time=0.0, flying=0.0, spread=0.0, flapPhase=0.0, flap=0.0, bank=0.0, hover=0.0, floating=0.0, headYaw=0.0, headPitch=0.0, beak=0.0,
                walk=0.0, walkPhase=0.0, action=0, actionTime=0.0, fish=False, variant=0)
    out.append(dict(base))
    for k, hi in (('flying', 1.0), ('spread', 1.0), ('flap', 1.0), ('bank', 1.0), ('bank', -1.0), ('hover', 1.0), ('floating', 1.0), ('beak', 1.0),
                  ('walk', 1.0), ('headYaw', 90.0), ('headPitch', -60.0)):
        out.append(dict(base, **{k: hi, 'time': 77.0, 'walkPhase': 3.0}))
    out.append(dict(base, flying=1.0, spread=1.0, flap=1.0, flapPhase=1.7, hover=1.0, time=33.0))
    out.append(dict(base, fish=True, time=5.0))
    return out


def check(work, say=print):
    """Compiles GullModel.kt (what `kotlin_source` makes) with the game's own render state and Minecraft's ModelPart, runs its setupAnim over
    `check_cases` and compares every part's position, turn, scale and visibility with `A.pose`. Returns (worst position error px, worst turn
    error, worst scale error, parts compared, problems)."""
    model, scene = built()
    files = K.scratch_tree(work, {'client/island/GullModel.kt': kotlin_source(model)}, ['client/island/GullRenderState.kt'])
    cases = check_cases()
    r = K.verify_pose(files, PACKAGE, 'GullModel', 'GullRenderState', scene, cases, lambda c: A.GullState(**c), A.pose, work)
    return r[0], r[1], r[2], r[3], list(r[4]), len(cases)


# ================================================================== the report: parts, what animates them, extents

NOTES = [
    (r'^body$', 'body (the origin of the pose: the middle of the body, BODY_Y over the feet)', 'pitched a touch by what it does (stance, a peck, a flare, a plunge); sinks when floating, crouches to spring, bobs with the beat and the stride; the renderer pitches and rolls it in flight'),
    (r'^torso$', 'torso (seven octagonal slices, breast to rump)', 'puffs up (scaled about the body) in a ruffle'),
    (r'^neck1$', 'neck, lower link', 'its own lean for standing, floating, flying; follows the look and the tip of the head; peck, call, preen, threat, takeoff, flare, plunge'),
    (r'^neck2$', 'neck, upper link', 'as the lower link, more so; swells across the throat with each note of the call'),
    (r'^head$', 'head (skull, eyes, fish)', '`headYaw`, `headPitch`, the idle looks, the head shake, the counter-roll against the bank'),
    (r'^bill$', 'upper bill (hooked, with the nostril)', 'lifts a little as the beak opens'),
    (r'^jaw$', 'lower bill (with the red spot)', '`beak`: gapes; the call, the threat, the preen'),
    (r'^eye_[lr]$', 'eyes', 'blink, squint (the preen, the ruffle)'),
    (r'^fish$', 'a fish, crosswise in the beak', 'shown by `fish`'),
    (r'^fish_tail$', 'the fish\'s tail', 'rides the fish'),
    (r'^tail$', 'tail (centre feather card)', 'pitches with the beat, the flare, the threat; flicks when it is calm; swings with the bank'),
    (r'^tail_[lr]$', 'tail, outer feathers', 'fan: wider in flight, hovering, the flare, the threat'),
    (r'^arm_[lr]$', 'wing: arm (shoulder joint)', 'folds back over the body like a roof; spreads; beats; dihedral and bank; the threat, the preen, the ruffle, the flare'),
    (r'^arm_[lr]_s$', 'wing: secondaries of the arm', 'fold under; camber with the beat'),
    (r'^fore_[lr]$', 'wing: forearm (elbow joint)', 'folds forward in the Z of a folded wing; lags the arm in the beat'),
    (r'^fore_[lr]_s$', 'wing: secondaries of the forearm', 'fold under; camber with the beat'),
    (r'^hand_[lr]$', 'wing: hand (wrist joint)', 'folds back again; lags the forearm; flexes on the upstroke'),
    (r'^pri_[lr][1-4]$', 'wing: primaries, inner to outer', 'fan in a glide and a flare, close on the downstroke, bend at the tips with the beat'),
    (r'^leg_[lr]$', 'leg: thigh (hip)', 'the walk (two-link reach to the ground), tucked up in flight, dangling in a hover, thrown forward in a flare'),
    (r'^shin_[lr]$', 'leg: tarsus (knee)', 'follows the reach'),
    (r'^foot_[lr]$', 'webbed foot', 'plants on the ground; narrows in flight'),
]


def report_poses():
    st = lambda **kw: A.pose(A.GullState(**kw))
    poses = {
        'standing': st(time=40.0),
        'walking': st(time=40.0, walk=1.0, walkPhase=1.0),
        'floating': st(time=40.0, floating=1.0),
        'gliding': st(time=40.0, flying=1.0, spread=1.0),
        'flapping, top': st(time=40.0, flying=1.0, spread=1.0, flap=1.0, flapPhase=0.0),
        'flapping, bottom': st(time=40.0, flying=1.0, spread=1.0, flap=1.0, flapPhase=3.0),
        'banked': st(time=40.0, flying=1.0, spread=1.0, bank=1.0),
        'hovering': st(time=40.0, flying=1.0, spread=1.0, flap=0.4, hover=1.0),
        'long call': st(time=40.0, action=A.CALL, actionTime=29.0),
        'threat': st(time=40.0, action=A.THREAT, actionTime=25.0),
        'preen, wing up': st(time=40.0, action=A.PREEN, actionTime=22.0),
        'ruffle': st(time=40.0, action=A.RUFFLE, actionTime=10.0),
        'plunge': st(time=40.0, action=A.DIVE, actionTime=15.0),
    }
    return poses


def parts_report(model, scene):
    head = ['Origin: the ground under the feet when the bird stands; px (1/16 block), y down, the bird faces -z, its right is -x.',
            f'BODY_Y = {A.BODY_Y:g} px: the middle of the body over the soles. The renderer pitches and rolls the bird about that point.',
            f'ACTION_TICKS = {tuple(A.ACTION_TICKS)} (index 0 unused): PREEN, CALL, PECK, RUFFLE, THREAT, TAKEOFF, LAND, DIVE.',
            f'TAKEOFF: the feet leave the ground at tick {A.TAKEOFF_LIFT:g}.  LAND: they touch down at tick {A.LAND_TOUCH:g}.  '
            f'DIVE: the beak enters the water at tick {A.DIVE_HIT:g} and comes up at tick {A.DIVE_SURFACE:g}.',
            f'Floating: the body sinks {A.FLOAT_SINK:g} px (the belly in the water).',
            '']
    return K.report_text('The gull (GullModel)', model, scene, NOTES, report_poses(), head)


# ================================================================== writing everything

def write_model(assets, prev, art, kdir, say=print):
    """Builds, paints and writes the model: the three PNGs, GullModel.kt, gull.bbmodel and the parts report. Returns (model, scene, [(name, tex)], problems)."""
    t0 = time.time()
    model, scene, texs = painted()
    problems, used = K.check_atlas(scene, model.tex_w, model.tex_h)
    ent = os.path.join(assets, 'textures', 'entity', 'island')
    for d in (ent, art, kdir, prev):
        os.makedirs(d, exist_ok=True)
    for name, tex in texs:
        Image.fromarray(tex, 'RGBA').save(os.path.join(ent, name + '.png'), optimize=True)
    with open(os.path.join(kdir, 'GullModel.kt'), 'w') as f:
        f.write(kotlin_source(model))
    K.write_bbmodel(model, Image.fromarray(texs[0][1], 'RGBA'), 'gull', os.path.join(art, 'gull.bbmodel'))
    with open(os.path.join(prev, 'gull_parts.txt'), 'w') as f:
        f.write(parts_report(model, scene))
    say(f'gull: {len(list(model.all_parts()))} parts, {len(list(model.all_cubes()))} cubes, {used} of {model.tex_w * K.HD * model.tex_h * K.HD} texels on '
        f'faces, three textures painted in {time.time() - t0:.0f} s')
    for p in problems:
        say('PROBLEM ' + p)
    return model, scene, texs, problems


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    flags = [a for a in argv if a.startswith('--')]
    only = None
    if '--only' in flags:
        i = argv.index('--only')
        only = argv[i + 1].split(',')
        args = [a for a in args if a != argv[i + 1]]
    assets = args[0] if len(args) > 0 else ASSETS
    prev = args[1] if len(args) > 1 else os.path.join(tempfile.gettempdir(), 'guildmark-previews', 'island')
    art = args[2] if len(args) > 2 else MODELS_DIR
    kdir = args[3] if len(args) > 3 else KOTLIN_DIR
    want = {f for f in flags if f in ('--model', '--check', '--previews')}
    everything = not want
    problems = []
    texs = None
    if everything or want:
        model, scene, texs, probs = write_model(assets, prev, art, kdir)
        problems += probs
    if ('--check' in want or everything) and '--no-check' not in flags:
        work = tempfile.mkdtemp(prefix='gull_check_')
        try:
            r = check(work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        print(f'gull: Kotlin vs Python over {r[5]} poses, {r[3]} parts compared: position {r[0]:.5f} px, turn {r[1]:.5f}, scale {r[2]:.5f}')
        problems += r[4]
    if (everything or '--previews' in want) and '--no-previews' not in flags:
        import island_gull_previews
        island_gull_previews.write_all(assets, prev, only=only)
    for p in problems:
        print('PROBLEM', p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
