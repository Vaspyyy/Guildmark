package io.github.vaspyyy.guildmark.client.sea

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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Maelgrasp's head: the colossal horror that comes up beside the ship. Models are in pixels with y down and the creature facing -z
 * (its right is -x); the origin is the waterline under the middle of the head, which stands about 5.5 blocks above it and reaches 4
 * below. The eye's centre is at (0, -32, -37) (radius 11) and the beak's tip at (0, -6, -46): the game aims at them.
 *
 * It is a scarred, bulbous mantle (a stack of round slices hung from a spine of six joints, which the lean shears forward joint by joint so
 * no gap opens), one enormous wet eye on its face (a stepped sphere that turns in its socket: [MaelgraspRenderState.lookYaw] to its right,
 * [MaelgraspRenderState.lookPitch] up; a gold-green iris whose ring glows; a pupil of five strips that goes from a narrow bar to a round
 * hole with [MaelgraspRenderState.pupil]; heavy lids that shut and blink with [MaelgraspRenderState.lid], follow the gaze, narrow in a rage and
 * half close as it dies; four dithered discs of milky film that cloud over it one after the other as it dies), a heavy brow, a black hooked
 * cracked beak whose lower half drops with [MaelgraspRenderState.beak], a crown of eight short arm-roots (suckers on their inner side) that
 * trail below when it sits low in the water, splay when it has surfaced, reach forward as it lunges and hang limp as it dies, writhing in
 * waves of their own all the while, a siphon on its flank that flares and pulses with [MaelgraspRenderState.ink], two ripple fins on the
 * flanks of the dome, barnacle crusts, harpoons broken off in it with their ropes and weed trailing from it. Its mantle heaves with a slow breath that
 * runs up it, deeper when it has risen and in a rage, and is drawn in as it lunges. Veins under its skin, the fins' rays and the ring of the
 * iris glow (the glow map).
 *
 * The geometry comes from art/scripts/sea_head.py (edit it there: it writes this file's body and the textures, and its `pose()` is the same
 * animation in Python, run for the previews and checked against this class by sea_monsters.py). The texture is HD: the model declares
 * 512x512 and the PNGs are painted at twice that. It is drawn with [RenderTypes.entityCutout] (the fins, weed, ropes and film are cut to shape in the
 * texture's alpha, and that type does not cull back faces); the glow layer is the same model drawn again with [RenderTypes.eyes] over
 * maelgrasp_glow.png, tinted by [MaelgraspRenderState.glow].
 */
class MaelgraspModel(root: ModelPart) : EntityModel<MaelgraspRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The head as a whole, the spine's joints (the first never moves) and the mantle's slices
    private val head = part("head")
    private val spine = Array(SPINE_Y.size) { part("sp$it") }
    private val slices = Array(SLICE_Y.size) { part("mt$it") }

    // The eye, its pupil's strips and the discs of film that cloud it, the lids and the brow
    private val eye = part("eye")
    private val pupil = Array(PUPIL_STRIPS) { part("pu$it") }
    private val films = Array(FILMS) { part("fm$it") }
    private val lidU = part("lid_u")
    private val lidL = part("lid_l")
    private val brow = part("brow")
    private val browL = part("brow_l")
    private val browR = part("brow_r")

    // The beak (the upper one's base, the lower one's two rods, the throat between them) and the crown of arm-roots
    private val beakUpper = part("bu0")
    private val jawA = part("bl0")
    private val jawB = part("bl1")
    private val throat = part("throat")
    private val holders = Array(ROOTS) { part("rh$it") }
    private val roots = Array(ROOTS) { k -> Array(ROOT_SEGS) { part("rt${k}_${it + 1}") } }

    // The siphon, the fins (left, right), the weed and the harpoons' ropes
    private val siphon = part("siphon")
    private val flare = Array(3) { part("sf${it + 1}") }
    private val fins = Array(2) { side -> Array(FIN_SEGS) { part("fn_${if (side == 0) "l" else "r"}${it + 1}") } }
    private val weeds = Array(WEEDS) { k -> Array(WEED_PIECES) { part("wd${k}_${it + 1}") } }
    private val ropes = Array(HARPOONS) { k -> Array(2) { part("hr${k}_${it + 1}") } }

    // Scratch: nothing is allocated per frame
    private val pupilW = FloatArray(PUPIL_STRIPS)
    private val pupilH = FloatArray(PUPIL_STRIPS)
    private val pupilY = FloatArray(PUPIL_STRIPS)
    private val dir = FloatArray(3)

    override fun setupAnim(state: MaelgraspRenderState) {
        super.setupAnim(state)
        val t = state.time
        val rise = state.rise.coerceIn(0f, 1f)
        val lunge = state.lunge.coerceIn(0f, 1f)
        val rage = state.rage.coerceIn(0f, 1f)
        val dying = state.dying.coerceIn(0f, 1f)
        val ink = state.ink.coerceIn(0f, 1f)
        val beak = state.beak.coerceIn(0f, 1f)
        val alive = 1f - dying
        val lookYaw = state.lookYaw.coerceIn(-70f, 70f) * RAD
        val lookPitch = state.lookPitch.coerceIn(-50f, 50f) * RAD

        // The lean: the whole head surges forward and ducks as it lunges, and the mantle shears forward joint by joint (the slices stay level, so no
        // wedge opens at a joint); it slumps back as it dies; the dome sways a little
        head.y += 5f * lunge + 3f * dying
        head.z += -9f * lunge
        val lean = -16f * lunge + 14f * dying
        val top = spine.size - 1
        for (i in 1..top) {
            val sx = if (i == top) 0.012f * sin(t * 0.031f) * alive else 0f
            val sz = (if (i == top) 0.022f * sin(t * 0.027f + 1f) * alive * rise
            else if (i == top - 1) 0.012f * sin(t * 0.023f + 0.5f * i) * alive * rise else 0f) + 0.02f * dying
            spine[i].z += shear(SPINE_Y[i], lean) - shear(SPINE_Y[i - 1], lean)
            spine[i].xRot += sx
            spine[i].zRot += sz
        }
        // The mantle heaves: a slow breath that runs up it, deeper at the surface and in a rage, drawn in as it lunges; still as it dies
        val amp = (0.010f + 0.016f * rise + 0.014f * rage) * alive
        for (j in slices.indices) {
            val a = amp * sin(t * 0.085f - 0.13f * j) - 0.05f * lunge + 0.03f * dying
            val s = slices[j]
            s.z += shear(SLICE_Y[j], lean) - shear(SPINE_Y[SLICE_JOINT[j]], lean)
            s.xScale = 1f + a
            s.zScale = 1f + a
        }

        // The eye turns in its socket (rolling up as it dies) with a faint tremor; the pupil's strips reshape from a bar to a round hole
        eye.xRot = -lookPitch - 0.55f * dying + 0.010f * sin(t * 0.37f + 1f) * alive
        eye.yRot = lookYaw + 0.012f * sin(t * 0.29f) * alive
        val p = (state.pupil + 0.03f * sin(t * 0.21f) * alive + 0.6f * dying * (1f - state.pupil)).coerceIn(0f, 1f)
        pupilLayout(p)
        for (k in 0 until PUPIL_STRIPS) {
            pupil[k].y = pupilY[k]
            pupil[k].z = PUPIL_FRONT + 0.5f
            pupil[k].xScale = pupilW[k] / 5f
            pupil[k].yScale = pupilH[k] / 0.5f
        }
        // The milky film clouds over the dying eye: four dithered discs, shown one after the other
        for (k in 0 until FILMS) {
            val shown = dying > 0.06f + 0.11f * k
            films[k].visible = shown
            films[k].z = if (shown) FILM_Z else FILM_HIDDEN_Z
        }
        // The lids: shut when told to, half shut as it dies, narrowed in a rage, following the gaze up and down (built closed, they rest turned open)
        val closed = max(state.lid, 0.55f * dying).coerceIn(0f, 1f)
        val open = 1f - closed
        lidU.xRot += -LID_U_OPEN * closed + 0.20f * rage * open - 0.35f * lookPitch * open
        lidL.xRot += -LID_L_OPEN * closed - 0.12f * rage * open - 0.20f * lookPitch * open
        // The brow lifts a little when the pupil opens, and scowls down in a rage and as the lids shut
        val drop = -0.8f * (state.pupil.coerceIn(0f, 1f) - 0.3f) + 1.8f * rage + 1f * closed
        brow.y += drop
        browL.y += drop
        browL.zRot += 0.35f * rage
        browR.y += drop
        browR.zRot -= 0.35f * rage

        // The beak: the lower one drops, the upper lifts, the throat shows; it chatters faintly at rest and grinds in a rage
        val chat = (0.03f * sin(t * 0.11f) + 0.05f * rage * sin(t * 0.83f)) * alive
        beakUpper.xRot += -0.20f * beak
        jawA.xRot += 0.70f * beak + chat
        jawB.xRot += 0.15f * beak
        throat.y += 3f * beak
        throat.yScale = 1f + 1.2f * beak

        // The arm-roots: each a holder turned to its direction, and five segments that curl toward their suckers and writhe in waves
        for (k in 0 until ROOTS) {
            val th = (ROOT_A0 + k * ROOT_STEP) * RAD
            rootDirection(th, k, rise, lunge, dying, t, rage)
            var ux = -cos(th)
            var uy = -sin(th)
            val dot = ux * dir[0] + uy * dir[1]
            ux -= dot * dir[0]
            uy -= dot * dir[1]
            var uz = -dot * dir[2]
            var ul = sqrt(ux * ux + uy * uy + uz * uz)
            if (ul < 1.0e-4f) {
                ux = 0f
                uy = 0f
                uz = 1f
                ul = 1f
            }
            ux /= ul
            uy /= ul
            uz /= ul
            val ezx = -ux
            val ezy = -uy
            val ezz = -uz
            turn(holders[k], dir[1] * ezz - dir[2] * ezy, dir[2] * ezx - dir[0] * ezz, dir[0] * ezy - dir[1] * ezx, dir[0], dir[1], dir[2], ezx, ezy, ezz)
            for (j in 1..ROOT_SEGS) {
                val wave = sin(t * 0.082f - 0.85f * j + 1.7f * k)
                val wave2 = sin(t * 0.057f - 0.7f * j + 1.1f * k + 2f)
                val ampJ = (0.10f + 0.05f * j) * (1f + 0.9f * rage) * (1f + 0.6f * (1f - rise)) * (1f - 0.75f * dying) * (1f - 0.5f * lunge)
                val curl = (0.16f + 0.05f * j) * (1f - 0.75f * lunge) + 0.30f * dying * (0.6f + 0.1f * j)
                roots[k][j - 1].xRot = -curl + ampJ * wave
                roots[k][j - 1].zRot = 0.8f * ampJ * wave2
            }
        }

        // The siphon breathes out with the mantle, and flares and pulses as it spews ink
        val breath = 0.05f * sin(t * 0.085f - 0.9f) * alive
        val pulse = 0.5f + 0.5f * sin(t * 0.85f)
        val pulse2 = 0.5f + 0.5f * sin(t * 0.85f + 1f)
        val s1 = 1f + breath * 0.5f + 0.15f * ink
        flare[0].xScale = s1
        flare[0].zScale = s1
        val s2 = 1f + breath + 0.30f * ink + 0.10f * ink * pulse
        flare[1].xScale = s2
        flare[1].zScale = s2
        val s3 = 1f + breath + 0.45f * ink + 0.18f * ink * pulse2
        flare[2].xScale = s3
        flare[2].yScale = 1f + 0.40f * ink * pulse2
        flare[2].zScale = s3
        siphon.xRot += -0.20f * ink - 0.06f * ink * pulse

        // The fins ripple in a wave that runs out to the tips; they sweep back as it lunges and droop as it dies
        val fa = (0.5f + 0.5f * rise) * (1f + 0.9f * rage) * alive
        for (side in 0..1) {
            val sgn = if (side == 0) 1f else -1f
            for (j in 1..FIN_SEGS) {
                val flap = (0.10f + 0.045f * j) * fa * sin(t * 0.11f - 0.8f * j + 1.9f * side)
                val flap2 = 0.04f * j * fa * sin(t * 0.07f - 0.6f * j + 0.7f * side + 1f)
                val f = fins[side][j - 1]
                f.xRot += flap2
                if (j == 1) f.yRot += -sgn * 0.5f * lunge
                f.zRot += -sgn * flap + sgn * 0.25f * dying * (0.5f + 0.2f * j)
            }
        }

        // The weed and the harpoons' ropes drift in the water
        val drive = 1f + 0.6f * lunge + 0.5f * rage
        for (k in 0 until WEEDS) {
            for (j in 1..WEED_PIECES) {
                weeds[k][j - 1].xRot += 0.10f * drive * sin(t * 0.055f + 1.9f * k - 0.9f * j)
                weeds[k][j - 1].zRot += 0.08f * drive * sin(t * 0.043f + 2.3f * k - 0.7f * j)
            }
        }
        for (k in 0 until HARPOONS) {
            for (j in 1..2) {
                ropes[k][j - 1].xRot += 0.14f * drive * sin(t * 0.047f + 2.1f * k - 0.9f * j)
                ropes[k][j - 1].zRot += 0.10f * drive * sin(t * 0.039f + 1.4f * k - 0.6f * j)
            }
        }
    }

    /** The five strips of the pupil for openness [p] (0 a narrow bar, 1 a round hole): half width, half height and y of each, from the top. */
    private fun pupilLayout(p: Float) {
        val a = PUPIL_A0 + (PUPIL_A1 - PUPIL_A0) * p
        val b = PUPIL_B0 + (PUPIL_B1 - PUPIL_B0) * p
        val s = smooth(0.25f, 0.85f, p)
        val hb = 2f * b / PUPIL_STRIPS
        for (k in 0 until PUPIL_STRIPS) {
            val yk = (k - (PUPIL_STRIPS - 1) / 2f) * hb
            val r = abs(yk) / b
            pupilW[k] = a * (1f - s * (1f - sqrt(max(0f, 1f - r * r))))
            pupilH[k] = hb / 2f
            pupilY[k] = yk
        }
    }

    /** Root [k]'s direction now, into [dir] (a unit vector): splayed at the surface, trailing below when low in the water, reaching forward in a lunge, limp as it dies. */
    private fun rootDirection(th: Float, k: Int, rise: Float, lunge: Float, dying: Float, t: Float, rage: Float) {
        val c = cos(th)
        val s = sin(th)
        val wd = dying
        val wl = (1f - dying) * lunge
        val wt = (1f - dying) * (1f - lunge) * (1f - smooth(0.15f, 0.9f, rise))
        val ws = 1f - wd - wl - wt
        val sw = 0.07f * (1f + 0.8f * rage) * (1f - 0.6f * dying)
        var dx = ws * (0.85f * c) + wt * (0.35f * c) + wl * (0.30f * c) + wd * (0.55f * c)
        var dy = ws * (0.85f * s + 0.2f) + wt * 1f + wl * (0.30f * s) + wd * 1f
        var dz = ws * -0.5f + wt * 0.30f + wl * -1f + wd * -0.15f
        dx += sw * sin(t * 0.037f + 1.3f * k)
        dy += 0.6f * sw * sin(t * 0.029f + 0.8f * k + 1f)
        dz += 0.5f * sw * sin(t * 0.043f + 2.1f * k + 2f)
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        dir[0] = dx / n
        dir[1] = dy / n
        dir[2] = dz / n
    }

    /** Sets [p]'s xRot, yRot and zRot (the game applies them as Rz Ry Rx) to the turn whose columns are the axes (ex), (ey) and (ez). */
    private fun turn(p: ModelPart, exx: Float, exy: Float, exz: Float, eyx: Float, eyy: Float, eyz: Float, ezx: Float, ezy: Float, ezz: Float) {
        val y = asin((-exz).coerceIn(-1f, 1f))
        p.yRot = y
        if (cos(y) > 1.0e-4f) {
            p.xRot = atan2(eyz, ezz)
            p.zRot = atan2(exy, exx)
        } else {
            p.xRot = atan2(-ezy, eyy)
            p.zRot = 0f
        }
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How far (px, along z) the mantle at height [y] is shifted by a lean of [lean] px at its very top: nothing at the base, more the higher it is. */
    private fun shear(y: Float, lean: Float): Float = lean * min(1.2f, max(0f, (SPINE_Y[0] - y) / (SPINE_Y[0] - Y_TOP))).pow(1.3f)

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "maelgrasp"), "main")

        private const val RAD = 0.017453292f

        // The spine: the height (y, px) of each joint, and the top of the mantle; each slice's middle and the joint it hangs from
        private val SPINE_Y = floatArrayOf(36f, 16f, -4f, -24f, -46f, -66f)
        private const val Y_TOP = -88f
        private val SLICE_Y = floatArrayOf(-86.5f, -83.5f, -80.5f, -77f, -72.5f, -67f, -60f, -52f, -44f, -36f, -28f, -20f, -12f, -4f, 4f, 12f, 20f, 28f, 36f, 43f, 48.5f, 53f, 56.5f, 59.5f, 62.5f)
        private val SLICE_JOINT = intArrayOf(5, 5, 5, 5, 5, 5, 5, 4, 4, 4, 3, 3, 2, 2, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0)

        // The pupil: five strips from a bar (half width A0, half height B0) to a round hole (A1, B1), their fronts at PUPIL_FRONT; the film's four discs
        private const val PUPIL_STRIPS = 5
        private const val PUPIL_A0 = 5.4f
        private const val PUPIL_A1 = 4.6f
        private const val PUPIL_B0 = 1.15f
        private const val PUPIL_B1 = 4.6f
        private const val PUPIL_FRONT = -10.7f
        private const val FILMS = 4
        private const val FILM_Z = -12f
        private const val FILM_HIDDEN_Z = 40f

        // The lids' resting turn (radians: they are built closed); the arm-roots: how many, their segments, the angle of the first round the beak and the step to the next (degrees)
        private const val LID_U_OPEN = -0.663f
        private const val LID_L_OPEN = 0.419f
        private const val ROOTS = 8
        private const val ROOT_SEGS = 5
        private const val ROOT_A0 = -55f
        private const val ROOT_STEP = 40f

        // The fins' slats on each side, the weed (strips each of its pieces) and the harpoons whose ropes hang
        private const val FIN_SEGS = 4
        private const val WEEDS = 7
        private const val WEED_PIECES = 3
        private const val HARPOONS = 3

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            buildHead1(root)
            return LayerDefinition.create(mesh, 512, 512)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead6(p: PartDefinition) {
            val sp4 = p.addOrReplaceChild("sp4", CubeListBuilder.create(), PartPose.offset(0f, -22f, 0f))
            val sp5 = sp4.addOrReplaceChild("sp5", CubeListBuilder.create(), PartPose.offset(0f, -20f, 0f))
            val mt0 = sp5.addOrReplaceChild("mt0", CubeListBuilder.create().texOffs(40, 21).addBox(-6.105f, -1.5f, -6.105f, 12f, 3f, 12f, FACES_TRFLK), PartPose.offset(0f, -20.5f, 13.45f))
            mt0.addOrReplaceChild("mt0_c1", CubeListBuilder.create().texOffs(0, 291).addBox(-6.105f, -1.4f, -6.105f, 12f, 3f, 12f, FACES_TRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            val mt1 = sp5.addOrReplaceChild("mt1", CubeListBuilder.create().texOffs(138, 279).addBox(-10.244f, -1.5f, -10.244f, 20.5f, 3f, 20.5f, FACES_RFLK), PartPose.offset(0f, -17.5f, 13.15f))
            mt1.addOrReplaceChild("mt1_c1", CubeListBuilder.create().texOffs(138, 282).addBox(-10.244f, -1.4f, -10.244f, 20.5f, 3f, 20.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt1.addOrReplaceChild("mt1_c2", CubeListBuilder.create().texOffs(86, 285).addBox(-10.244f, -1.3f, -10.244f, 20.5f, 3f, 20.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt2 = sp5.addOrReplaceChild("mt2", CubeListBuilder.create().texOffs(371, 240).addBox(-13.089f, -1.5f, -13.089f, 26f, 3f, 26f, FACES_RFLK), PartPose.offset(0f, -14.5f, 12.85f))
            mt2.addOrReplaceChild("mt2_c1", CubeListBuilder.create().texOffs(371, 243).addBox(-13.089f, -1.4f, -13.089f, 26f, 3f, 26f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt2.addOrReplaceChild("mt2_c2", CubeListBuilder.create().texOffs(167, 245).addBox(-13.089f, -1.3f, -13.089f, 26f, 3f, 26f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt3 = sp5.addOrReplaceChild("mt3", CubeListBuilder.create().texOffs(126, 16).addBox(-15.659f, -2f, -15.659f, 31.5f, 4f, 31.5f, FACES_RFLK), PartPose.offset(0f, -11f, 12.5f))
            mt3.addOrReplaceChild("mt3_c1", CubeListBuilder.create().texOffs(252, 16).addBox(-15.659f, -1.9f, -15.659f, 31.5f, 4f, 31.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt3.addOrReplaceChild("mt3_c2", CubeListBuilder.create().texOffs(378, 16).addBox(-15.659f, -1.8f, -15.659f, 31.5f, 4f, 31.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt4 = sp5.addOrReplaceChild("mt4", CubeListBuilder.create().texOffs(354, 202).addBox(-18.289f, -2.5f, -18.289f, 36.5f, 5f, 36.5f, FACES_RFLK), PartPose.offset(0f, -6.5f, 12.05f))
            mt4.addOrReplaceChild("mt4_c1", CubeListBuilder.create().texOffs(354, 207).addBox(-18.289f, -2.4f, -18.289f, 36.5f, 5f, 36.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt4.addOrReplaceChild("mt4_c2", CubeListBuilder.create().texOffs(354, 212).addBox(-18.289f, -2.3f, -18.289f, 36.5f, 5f, 36.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt5 = sp5.addOrReplaceChild("mt5", CubeListBuilder.create().texOffs(188, 197).addBox(-20.854f, -3f, -20.854f, 41.5f, 6f, 41.5f, FACES_RFLK), PartPose.offset(0f, -1f, 11.5f))
            mt5.addOrReplaceChild("mt5_c1", CubeListBuilder.create().texOffs(188, 203).addBox(-20.854f, -2.9f, -20.854f, 41.5f, 6f, 41.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt5.addOrReplaceChild("mt5_c2", CubeListBuilder.create().texOffs(0, 205).addBox(-20.854f, -2.8f, -20.854f, 41.5f, 6f, 41.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt6 = sp5.addOrReplaceChild("mt6", CubeListBuilder.create().texOffs(0, 183).addBox(-23.428f, -4f, -23.428f, 47f, 8f, 47f, FACES_RFLK), PartPose.offset(0f, 6f, 10.8f))
            mt6.addOrReplaceChild("mt6_c1", CubeListBuilder.create().texOffs(188, 183).addBox(-23.428f, -3.9f, -23.428f, 47f, 8f, 47f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt6.addOrReplaceChild("mt6_c2", CubeListBuilder.create().texOffs(0, 191).addBox(-23.428f, -3.8f, -23.428f, 47f, 8f, 47f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            sp5.addOrReplaceChild("bh29", CubeListBuilder.create().texOffs(79, 325).addBox(-2f, -2f, -1.5f, 4f, 4f, 3f), PartPose.offsetAndRotation(13.856f, -9.972f, 31.471f, 0f, -2.513f, 0f))
            sp5.addOrReplaceChild("bh30", CubeListBuilder.create().texOffs(343, 297).addBox(-1.25f, -1f, -1.25f, 2.5f, 2f, 2.5f), PartPose.offsetAndRotation(13.585f, -8.819f, 32.538f, 0f, -2.551f, 0f))
            sp5.addOrReplaceChild("bh31", CubeListBuilder.create().texOffs(102, 281).addBox(-2.5f, -1f, -1.5f, 5f, 2f, 3f), PartPose.offsetAndRotation(9.694f, -13.39f, 30.595f, 0f, -2.644f, 0f))
            sp5.addOrReplaceChild("bh32", CubeListBuilder.create().texOffs(269, 329).addBox(-2f, -1.25f, -2f, 4f, 2.5f, 4f), PartPose.offsetAndRotation(11.454f, -8.71f, 34.411f, 0f, -2.664f, 0f))
            sp5.addOrReplaceChild("hp2", CubeListBuilder.create().texOffs(502, 56).addBox(-1f, -4f, -1f, 2f, 26f, 2f).texOffs(438, 322).addBox(-2f, 0f, -2f, 4f, 3.5f, 4f).texOffs(60, 13).addBox(-1f, 21.5f, -0.5f, 1f, 4.5f, 1f).texOffs(0, 21).addBox(0f, 21f, -1f, 1f, 3.5f, 1f), PartPose.offsetAndRotation(23.964f, -4f, 24.542f, 2.074f, 1.082f, 0f))
            val hr2_1 = sp5.addOrReplaceChild("hr2_1", CubeListBuilder.create().texOffs(508, 97).addBox(-0.75f, 0f, -0.25f, 1.5f, 8f, 0.5f, FACES_FK), PartPose.offset(38.663f, -13.156f, 32.358f))
            hr2_1.addOrReplaceChild("hr2_2", CubeListBuilder.create().texOffs(508, 132).addBox(-0.75f, 0f, -0.25f, 1.5f, 8f, 0.5f, FACES_FK), PartPose.offset(0f, 8f, 0f))
            val wd0_1 = sp5.addOrReplaceChild("wd0_1", CubeListBuilder.create().texOffs(426, 8).addBox(-2.5f, 0f, -0.25f, 5f, 11f, 0.5f, FACES_FK), PartPose.offsetAndRotation(32.659f, 6f, 16.559f, -0.598f, -1.745f, 0f))
            val wd0_2 = wd0_1.addOrReplaceChild("wd0_2", CubeListBuilder.create().texOffs(339, 266).addBox(-2f, 0f, -0.25f, 4f, 10f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 11f, 0f, 0.269f, 0f, 0f))
            wd0_2.addOrReplaceChild("wd0_3", CubeListBuilder.create().texOffs(102, 0).addBox(-1.5f, 0f, -0.25f, 3f, 9f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 10f, 0f, 0.269f, 0f, 0f))
            val wd4_1 = sp5.addOrReplaceChild("wd4_1", CubeListBuilder.create().texOffs(500, 234).addBox(-2.5f, 0f, -0.25f, 5f, 11f, 0.5f, FACES_FK), PartPose.offsetAndRotation(4.834f, -10f, 35.143f, -0.886f, -2.932f, 0f))
            val wd4_2 = wd4_1.addOrReplaceChild("wd4_2", CubeListBuilder.create().texOffs(250, 292).addBox(-2f, 0f, -0.25f, 4f, 10f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 11f, 0f, 0.399f, 0f, 0f))
            wd4_2.addOrReplaceChild("wd4_3", CubeListBuilder.create().texOffs(501, 84).addBox(-1.5f, 0f, -0.25f, 3f, 9f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 10f, 0f, 0.399f, 0f, 0f))
            val mt7 = sp4.addOrReplaceChild("mt7", CubeListBuilder.create().texOffs(206, 147).addBox(-25.501f, -4f, -25.501f, 51f, 8f, 51f, FACES_RFLK), PartPose.offset(0f, -6f, 10f))
            mt7.addOrReplaceChild("mt7_c1", CubeListBuilder.create().texOffs(0, 155).addBox(-25.501f, -3.9f, -25.501f, 51f, 8f, 51f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt7.addOrReplaceChild("mt7_c2", CubeListBuilder.create().texOffs(204, 155).addBox(-25.501f, -3.8f, -25.501f, 51f, 8f, 51f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt7.addOrReplaceChild("mt7_c3", CubeListBuilder.create().texOffs(0, 163).addBox(-25.501f, -3.7f, -25.501f, 51f, 8f, 51f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            val mt8 = sp4.addOrReplaceChild("mt8", CubeListBuilder.create().texOffs(0, 127).addBox(-27.203f, -4f, -27.203f, 54.5f, 8f, 54.5f, FACES_RFLK), PartPose.offset(0f, 2f, 9.2f))
            mt8.addOrReplaceChild("mt8_c1", CubeListBuilder.create().texOffs(218, 127).addBox(-27.203f, -3.9f, -27.203f, 54.5f, 8f, 54.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt8.addOrReplaceChild("mt8_c2", CubeListBuilder.create().texOffs(0, 135).addBox(-27.203f, -3.8f, -27.203f, 54.5f, 8f, 54.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt8.addOrReplaceChild("mt8_c3", CubeListBuilder.create().texOffs(218, 135).addBox(-27.203f, -3.7f, -27.203f, 54.5f, 8f, 54.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            val mt9 = sp4.addOrReplaceChild("mt9", CubeListBuilder.create().texOffs(230, 92).addBox(-28.456f, -4f, -28.456f, 57f, 8f, 57f, FACES_RFLK), PartPose.offset(0f, 10f, 8.4f))
            mt9.addOrReplaceChild("mt9_c1", CubeListBuilder.create().texOffs(0, 100).addBox(-28.456f, -3.9f, -28.456f, 57f, 8f, 57f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt9.addOrReplaceChild("mt9_c2", CubeListBuilder.create().texOffs(228, 100).addBox(-28.456f, -3.8f, -28.456f, 57f, 8f, 57f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt9.addOrReplaceChild("mt9_c3", CubeListBuilder.create().texOffs(0, 108).addBox(-28.456f, -3.7f, -28.456f, 57f, 8f, 57f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            sp4.addOrReplaceChild("brow", CubeListBuilder.create().texOffs(0, 0).addBox(-13f, -3f, -3.5f, 26f, 6f, 7f), PartPose.offset(0f, -3f, -41.5f))
            sp4.addOrReplaceChild("brow_l", CubeListBuilder.create().texOffs(282, 18).addBox(0f, -3f, -3.5f, 13f, 6f, 7f), PartPose.offsetAndRotation(13f, -3f, -41.5f, 0f, 0f, 0.419f))
            sp4.addOrReplaceChild("brow_r", CubeListBuilder.create().texOffs(0, 21).addBox(-13f, -3f, -3.5f, 13f, 6f, 7f), PartPose.offsetAndRotation(-13f, -3f, -41.5f, 0f, 0f, -0.419f))
            val fn_l1 = sp4.addOrReplaceChild("fn_l1", CubeListBuilder.create().texOffs(34, 0).addBox(0f, -0.25f, -12.16f, 9f, 0.5f, 32f, FACES_TB), PartPose.offsetAndRotation(33f, -8f, 10f, 0f, 0f, -0.279f))
            val fn_l2 = fn_l1.addOrReplaceChild("fn_l2", CubeListBuilder.create().texOffs(455, 0).addBox(0f, -0.25f, -11.4f, 9f, 0.5f, 30f, FACES_TB), PartPose.offset(9f, 0f, 0f))
            val fn_l3 = fn_l2.addOrReplaceChild("fn_l3", CubeListBuilder.create().texOffs(466, 135).addBox(0f, -0.25f, -9.88f, 8f, 0.5f, 26f, FACES_TB), PartPose.offset(9f, 0f, 0f))
            fn_l3.addOrReplaceChild("fn_l4", CubeListBuilder.create().texOffs(478, 161).addBox(0f, -0.25f, -6.84f, 7f, 0.5f, 18f, FACES_TB), PartPose.offset(8f, 0f, 0f))
            val fn_r1 = sp4.addOrReplaceChild("fn_r1", CubeListBuilder.create().texOffs(52, 0).addBox(-9f, -0.25f, -12.16f, 9f, 0.5f, 32f, FACES_TB), PartPose.offsetAndRotation(-33f, -8f, 10f, 0f, 0f, 0.279f))
            val fn_r2 = fn_r1.addOrReplaceChild("fn_r2", CubeListBuilder.create().texOffs(408, 9).addBox(-9f, -0.25f, -11.4f, 9f, 0.5f, 30f, FACES_TB), PartPose.offset(-9f, 0f, 0f))
            val fn_r3 = fn_r2.addOrReplaceChild("fn_r3", CubeListBuilder.create().texOffs(454, 161).addBox(-8f, -0.25f, -9.88f, 8f, 0.5f, 26f, FACES_TB), PartPose.offset(-9f, 0f, 0f))
            fn_r3.addOrReplaceChild("fn_r4", CubeListBuilder.create().texOffs(478, 179).addBox(-7f, -0.25f, -6.84f, 7f, 0.5f, 18f, FACES_TB), PartPose.offset(-8f, 0f, 0f))
            sp4.addOrReplaceChild("bh10", CubeListBuilder.create().texOffs(166, 246).addBox(-2.5f, -1.25f, -1.25f, 5f, 2.5f, 2.5f), PartPose.offsetAndRotation(-28.544f, 7.848f, 36.901f, 0f, 2.352f, 0f))
            sp4.addOrReplaceChild("bh11", CubeListBuilder.create().texOffs(147, 286).addBox(-1.5f, -1.5f, -1.75f, 3f, 3f, 3.5f), PartPose.offsetAndRotation(-28.842f, 8.747f, 37.191f, 0f, 2.353f, 0f))
            sp4.addOrReplaceChild("bh37", CubeListBuilder.create().texOffs(29, 328).addBox(-1.5f, -2f, -1.75f, 3f, 4f, 3.5f), PartPose.offsetAndRotation(-35.357f, -0.58f, -5.654f, 0f, 1.167f, 0f))
            sp4.addOrReplaceChild("bh38", CubeListBuilder.create().texOffs(281, 298).addBox(-2.5f, -1f, -1.5f, 5f, 2f, 3f), PartPose.offsetAndRotation(-34.715f, 2.999f, -9.209f, 0f, 1.085f, 0f))
            sp4.addOrReplaceChild("bh39", CubeListBuilder.create().texOffs(165, 328).addBox(-1.5f, -2f, -1.75f, 3f, 4f, 3.5f), PartPose.offsetAndRotation(-34.18f, 5.156f, -11.716f, 0f, 1.028f, 0f))
            sp4.addOrReplaceChild("bh40", CubeListBuilder.create().texOffs(255, 327).addBox(-2.5f, -1f, -2f, 5f, 2f, 4f), PartPose.offsetAndRotation(-34.708f, 2.847f, -9.762f, 0f, 1.073f, 0f))
            sp4.addOrReplaceChild("bh41", CubeListBuilder.create().texOffs(196, 306).addBox(-2f, -1f, -1.5f, 4f, 2f, 3f), PartPose.offsetAndRotation(-35.737f, 2.678f, -6.897f, 0f, 1.149f, 0f))
            sp4.addOrReplaceChild("bh42", CubeListBuilder.create().texOffs(91, 330).addBox(-2.5f, -1.5f, -1.25f, 5f, 3f, 2.5f), PartPose.offsetAndRotation(32.245f, -8.999f, -4.263f, 0f, -1.147f, 0f))
            sp4.addOrReplaceChild("bh43", CubeListBuilder.create().texOffs(484, 190).addBox(-1.25f, -1f, -1.75f, 2.5f, 2f, 3.5f), PartPose.offsetAndRotation(31.329f, -8.066f, -7.588f, 0f, -1.054f, 0f))
            sp4.addOrReplaceChild("bh44", CubeListBuilder.create().texOffs(394, 25).addBox(-1.5f, -1.25f, -1.25f, 3f, 2.5f, 2.5f), PartPose.offsetAndRotation(32.307f, -7.471f, -5.612f, 0f, -1.117f, 0f))
            sp4.addOrReplaceChild("hp0", CubeListBuilder.create().texOffs(503, 0).addBox(-1f, -4f, -1f, 2f, 26f, 2f).texOffs(392, 214).addBox(-2f, 0f, -2f, 4f, 3.5f, 4f).texOffs(0, 0).addBox(-1f, 21.5f, -0.5f, 1f, 4.5f, 1f).texOffs(282, 18).addBox(0f, 21f, -1f, 1f, 3.5f, 1f), PartPose.offsetAndRotation(28.142f, -6f, -11.987f, -1.068f, 0.908f, 3.142f))
            val hr0_1 = sp4.addOrReplaceChild("hr0_1", CubeListBuilder.create().texOffs(498, 39).addBox(-0.75f, 0f, -0.25f, 1.5f, 8f, 0.5f, FACES_FK), PartPose.offset(41.26f, -15.156f, -22.236f))
            hr0_1.addOrReplaceChild("hr0_2", CubeListBuilder.create().texOffs(476, 76).addBox(-0.75f, 0f, -0.25f, 1.5f, 8f, 0.5f, FACES_FK), PartPose.offset(0f, 8f, 0f))
            sp4.addOrReplaceChild("hp1", CubeListBuilder.create().texOffs(504, 28).addBox(-1f, -4f, -1f, 2f, 26f, 2f).texOffs(0, 315).addBox(-2f, 0f, -2f, 4f, 3.5f, 4f).texOffs(59, 0).addBox(-1f, 21.5f, -0.5f, 1f, 4.5f, 1f).texOffs(337, 18).addBox(0f, 21f, -1f, 1f, 3.5f, 1f), PartPose.offsetAndRotation(-37.533f, 6f, -1.962f, -1.068f, -1.292f, 3.142f))
            val hr1_1 = sp4.addOrReplaceChild("hr1_1", CubeListBuilder.create().texOffs(476, 84).addBox(-0.75f, 0f, -0.25f, 1.5f, 8f, 0.5f, FACES_FK), PartPose.offset(-53.536f, -3.156f, -6.551f))
            hr1_1.addOrReplaceChild("hr1_2", CubeListBuilder.create().texOffs(508, 84).addBox(-0.75f, 0f, -0.25f, 1.5f, 8f, 0.5f, FACES_FK), PartPose.offset(0f, 8f, 0f))
            val wd1_1 = sp4.addOrReplaceChild("wd1_1", CubeListBuilder.create().texOffs(40, 21).addBox(-2.5f, 0f, -0.25f, 5f, 11f, 0.5f, FACES_FK), PartPose.offsetAndRotation(-35.384f, 0f, 23.696f, -0.444f, 1.955f, 0f))
            val wd1_2 = wd1_1.addOrReplaceChild("wd1_2", CubeListBuilder.create().texOffs(0, 290).addBox(-2f, 0f, -0.25f, 4f, 10f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 11f, 0f, 0.2f, 0f, 0f))
            wd1_2.addOrReplaceChild("wd1_3", CubeListBuilder.create().texOffs(477, 8).addBox(-1.5f, 0f, -0.25f, 3f, 9f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 10f, 0f, 0.2f, 0f, 0f))
            val wd6_1 = sp4.addOrReplaceChild("wd6_1", CubeListBuilder.create().texOffs(95, 265).addBox(-2.5f, 0f, -0.25f, 5f, 11f, 0.5f, FACES_FK), PartPose.offsetAndRotation(-23.766f, -4f, -18.523f, -0.483f, 0.698f, 0f))
            val wd6_2 = wd6_1.addOrReplaceChild("wd6_2", CubeListBuilder.create().texOffs(420, 292).addBox(-2f, 0f, -0.25f, 4f, 10f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 11f, 0f, 0.218f, 0f, 0f))
            wd6_2.addOrReplaceChild("wd6_3", CubeListBuilder.create().texOffs(504, 205).addBox(-1.5f, 0f, -0.25f, 3f, 9f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 10f, 0f, 0.218f, 0f, 0f))
            val mt10 = p.addOrReplaceChild("mt10", CubeListBuilder.create().texOffs(0, 50).addBox(-29.319f, -4f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offset(0f, -4f, 7.6f))
            mt10.addOrReplaceChild("mt10_c1", CubeListBuilder.create().texOffs(234, 50).addBox(-29.319f, -3.9f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt10.addOrReplaceChild("mt10_c2", CubeListBuilder.create().texOffs(0, 58).addBox(-29.319f, -3.8f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt10.addOrReplaceChild("mt10_c3", CubeListBuilder.create().texOffs(234, 58).addBox(-29.319f, -3.7f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            val mt11 = p.addOrReplaceChild("mt11", CubeListBuilder.create().texOffs(0, 17).addBox(-29.824f, -4f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offset(0f, 4f, 6.8f))
            mt11.addOrReplaceChild("mt11_c1", CubeListBuilder.create().texOffs(238, 17).addBox(-29.824f, -3.9f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt11.addOrReplaceChild("mt11_c2", CubeListBuilder.create().texOffs(0, 25).addBox(-29.824f, -3.8f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt11.addOrReplaceChild("mt11_c3", CubeListBuilder.create().texOffs(238, 25).addBox(-29.824f, -3.7f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead7(p: PartDefinition) {
            val eye = p.addOrReplaceChild("eye", CubeListBuilder.create(), PartPose.offset(0f, -8f, -37f))
            val eb0 = eye.addOrReplaceChild("eb0", CubeListBuilder.create().texOffs(244, 327).addBox(-3.45f, -3.45f, -10.3f, 7f, 7f, 1f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb0.addOrReplaceChild("eb0_c1", CubeListBuilder.create().texOffs(484, 327).addBox(-3.45f, -3.45f, -10.2f, 7f, 7f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.524f))
            eb0.addOrReplaceChild("eb0_c2", CubeListBuilder.create().texOffs(0, 328).addBox(-3.45f, -3.45f, -10.1f, 7f, 7f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.047f))
            val eb1 = eye.addOrReplaceChild("eb1", CubeListBuilder.create().texOffs(405, 322).addBox(-4.465f, -4.465f, -9.5f, 9f, 9f, 1f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb1.addOrReplaceChild("eb1_c1", CubeListBuilder.create().texOffs(423, 322).addBox(-4.465f, -4.465f, -9.4f, 9f, 9f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb1.addOrReplaceChild("eb1_c2", CubeListBuilder.create().texOffs(52, 324).addBox(-4.465f, -4.465f, -9.3f, 9f, 9f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb1.addOrReplaceChild("eb1_c3", CubeListBuilder.create().texOffs(63, 325).addBox(-4.465f, -4.465f, -9.2f, 9f, 9f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            val eb2 = eye.addOrReplaceChild("eb2", CubeListBuilder.create().texOffs(96, 318).addBox(-5.314f, -5.314f, -8.6f, 10.5f, 10.5f, 1f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb2.addOrReplaceChild("eb2_c1", CubeListBuilder.create().texOffs(117, 318).addBox(-5.314f, -5.314f, -8.5f, 10.5f, 10.5f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb2.addOrReplaceChild("eb2_c2", CubeListBuilder.create().texOffs(138, 318).addBox(-5.314f, -5.314f, -8.4f, 10.5f, 10.5f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb2.addOrReplaceChild("eb2_c3", CubeListBuilder.create().texOffs(182, 320).addBox(-5.314f, -5.314f, -8.3f, 10.5f, 10.5f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            val eb3 = eye.addOrReplaceChild("eb3", CubeListBuilder.create().texOffs(38, 315).addBox(-6.029f, -6.029f, -7.6f, 12f, 12f, 1f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb3.addOrReplaceChild("eb3_c1", CubeListBuilder.create().texOffs(168, 315).addBox(-6.029f, -6.029f, -7.5f, 12f, 12f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb3.addOrReplaceChild("eb3_c2", CubeListBuilder.create().texOffs(271, 315).addBox(-6.029f, -6.029f, -7.4f, 12f, 12f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb3.addOrReplaceChild("eb3_c3", CubeListBuilder.create().texOffs(295, 315).addBox(-6.029f, -6.029f, -7.3f, 12f, 12f, 1f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            val eb4 = eye.addOrReplaceChild("eb4", CubeListBuilder.create().texOffs(377, 291).addBox(-6.652f, -6.652f, -6.5f, 13.5f, 13.5f, 1.5f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb4.addOrReplaceChild("eb4_c1", CubeListBuilder.create().texOffs(404, 291).addBox(-6.652f, -6.652f, -6.4f, 13.5f, 13.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb4.addOrReplaceChild("eb4_c2", CubeListBuilder.create().texOffs(431, 291).addBox(-6.652f, -6.652f, -6.3f, 13.5f, 13.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb4.addOrReplaceChild("eb4_c3", CubeListBuilder.create().texOffs(458, 291).addBox(-6.652f, -6.652f, -6.2f, 13.5f, 13.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            val eb5 = eye.addOrReplaceChild("eb5", CubeListBuilder.create().texOffs(102, 286).addBox(-7.199f, -7.199f, -5.2f, 14.5f, 14.5f, 1.5f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb5.addOrReplaceChild("eb5_c1", CubeListBuilder.create().texOffs(120, 288).addBox(-7.199f, -7.199f, -5.1f, 14.5f, 14.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb5.addOrReplaceChild("eb5_c2", CubeListBuilder.create().texOffs(263, 289).addBox(-7.199f, -7.199f, -5f, 14.5f, 14.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb5.addOrReplaceChild("eb5_c3", CubeListBuilder.create().texOffs(68, 290).addBox(-7.199f, -7.199f, -4.9f, 14.5f, 14.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            val eb6 = eye.addOrReplaceChild("eb6", CubeListBuilder.create().texOffs(76, 263).addBox(-7.641f, -7.641f, -3.6f, 15.5f, 15.5f, 2f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb6.addOrReplaceChild("eb6_c1", CubeListBuilder.create().texOffs(107, 263).addBox(-7.641f, -7.641f, -3.5f, 15.5f, 15.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb6.addOrReplaceChild("eb6_c2", CubeListBuilder.create().texOffs(320, 264).addBox(-7.641f, -7.641f, -3.4f, 15.5f, 15.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb6.addOrReplaceChild("eb6_c3", CubeListBuilder.create().texOffs(351, 264).addBox(-7.641f, -7.641f, -3.3f, 15.5f, 15.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            val eb7 = eye.addOrReplaceChild("eb7", CubeListBuilder.create().texOffs(476, 94).addBox(-7.855f, -7.855f, -1.5f, 15.5f, 15.5f, 3f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb7.addOrReplaceChild("eb7_c1", CubeListBuilder.create().texOffs(468, 113).addBox(-7.855f, -7.855f, -1.4f, 15.5f, 15.5f, 3f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb7.addOrReplaceChild("eb7_c2", CubeListBuilder.create().texOffs(468, 132).addBox(-7.855f, -7.855f, -1.3f, 15.5f, 15.5f, 3f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb7.addOrReplaceChild("eb7_c3", CubeListBuilder.create().texOffs(458, 151).addBox(-7.855f, -7.855f, -1.2f, 15.5f, 15.5f, 3f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            val eb8 = eye.addOrReplaceChild("eb8", CubeListBuilder.create().texOffs(445, 170).addBox(-7.557f, -7.557f, 1.5f, 15f, 15f, 3f, FACES_TBRFL), PartPose.offset(0f, 0f, 0f))
            eb8.addOrReplaceChild("eb8_c1", CubeListBuilder.create().texOffs(463, 187).addBox(-7.557f, -7.557f, 1.6f, 15f, 15f, 3f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            eb8.addOrReplaceChild("eb8_c2", CubeListBuilder.create().texOffs(410, 198).addBox(-7.557f, -7.557f, 1.7f, 15f, 15f, 3f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            eb8.addOrReplaceChild("eb8_c3", CubeListBuilder.create().texOffs(431, 205).addBox(-7.557f, -7.557f, 1.8f, 15f, 15f, 3f, FACES_TBRFL), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            eye.addOrReplaceChild("pu0", CubeListBuilder.create().texOffs(102, 16).addBox(-5f, -0.5f, -0.5f, 10f, 1f, 1f, FACES_TBRFL), PartPose.offset(0f, -1.748f, -10.2f))
            eye.addOrReplaceChild("pu1", CubeListBuilder.create().texOffs(122, 16).addBox(-5f, -0.5f, -0.5f, 10f, 1f, 1f, FACES_TBRFL), PartPose.offset(0f, -0.874f, -10.2f))
            eye.addOrReplaceChild("pu2", CubeListBuilder.create().texOffs(0, 34).addBox(-5f, -0.5f, -0.5f, 10f, 1f, 1f, FACES_TBRFL), PartPose.offset(0f, 0f, -10.2f))
            eye.addOrReplaceChild("pu3", CubeListBuilder.create().texOffs(416, 223).addBox(-5f, -0.5f, -0.5f, 10f, 1f, 1f, FACES_TBRFL), PartPose.offset(0f, 0.874f, -10.2f))
            eye.addOrReplaceChild("pu4", CubeListBuilder.create().texOffs(376, 232).addBox(-5f, -0.5f, -0.5f, 10f, 1f, 1f, FACES_TBRFL), PartPose.offset(0f, 1.748f, -10.2f))
            eye.addOrReplaceChild("fm0", CubeListBuilder.create().texOffs(405, 8).addBox(-10.6f, -10.6f, -0.25f, 21f, 21f, 0.5f, FACES_F), PartPose.offset(0f, 0f, 40f))
            eye.addOrReplaceChild("fm1", CubeListBuilder.create().texOffs(456, 8).addBox(-10.6f, -10.6f, -0.25f, 21f, 21f, 0.5f, FACES_F), PartPose.offset(0f, 0f, 40f))
            eye.addOrReplaceChild("fm2", CubeListBuilder.create().texOffs(480, 51).addBox(-10.6f, -10.6f, -0.25f, 21f, 21f, 0.5f, FACES_F), PartPose.offset(0f, 0f, 40f))
            eye.addOrReplaceChild("fm3", CubeListBuilder.create().texOffs(480, 72).addBox(-10.6f, -10.6f, -0.25f, 21f, 21f, 0.5f, FACES_F), PartPose.offset(0f, 0f, 40f))
            val lid_u = p.addOrReplaceChild("lid_u", CubeListBuilder.create(), PartPose.offsetAndRotation(0f, -8f, -37f, -0.663f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u0", CubeListBuilder.create().texOffs(140, 0).addBox(-15.5f, -2.5f, -14.7f, 31f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -0.07f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u1", CubeListBuilder.create().texOffs(209, 0).addBox(-15.5f, -2.5f, -14.7f, 31f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -0.314f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u2", CubeListBuilder.create().texOffs(278, 0).addBox(-15.5f, -2.5f, -14.7f, 31f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -0.559f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u3", CubeListBuilder.create().texOffs(347, 0).addBox(-15.5f, -2.5f, -14.7f, 31f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -0.803f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u4", CubeListBuilder.create().texOffs(416, 0).addBox(-15.5f, -2.5f, -14.7f, 31f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -1.047f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u5", CubeListBuilder.create().texOffs(150, 9).addBox(-15.5f, -2.5f, -14.7f, 31f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -1.292f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u6", CubeListBuilder.create().texOffs(219, 9).addBox(-14f, -2.5f, -14.7f, 28f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -1.536f, 0f, 0f))
            lid_u.addOrReplaceChild("lid_u7", CubeListBuilder.create().texOffs(282, 9).addBox(-14f, -2.5f, -14.7f, 28f, 5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, -1.78f, 0f, 0f))
            val lid_l = p.addOrReplaceChild("lid_l", CubeListBuilder.create(), PartPose.offsetAndRotation(0f, -8f, -37f, 0.419f, 0f, 0f))
            lid_l.addOrReplaceChild("lid_l0", CubeListBuilder.create().texOffs(345, 9).addBox(-13.5f, -2.5f, -14.5f, 27f, 5f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.07f, 0f, 0f))
            lid_l.addOrReplaceChild("lid_l1", CubeListBuilder.create().texOffs(0, 13).addBox(-13.5f, -2.5f, -14.5f, 27f, 5f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.314f, 0f, 0f))
            lid_l.addOrReplaceChild("lid_l2", CubeListBuilder.create().texOffs(342, 17).addBox(-13.5f, -2.5f, -14.5f, 27f, 5f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.559f, 0f, 0f))
            lid_l.addOrReplaceChild("lid_l3", CubeListBuilder.create().texOffs(102, 18).addBox(-13.5f, -2.5f, -14.5f, 27f, 5f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.803f, 0f, 0f))
            lid_l.addOrReplaceChild("lid_l4", CubeListBuilder.create().texOffs(162, 18).addBox(-13.5f, -2.5f, -14.5f, 27f, 5f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 1.047f, 0f, 0f))
            lid_l.addOrReplaceChild("lid_l5", CubeListBuilder.create().texOffs(222, 18).addBox(-13.5f, -2.5f, -14.5f, 27f, 5f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 1.292f, 0f, 0f))
            p.addOrReplaceChild("bh1", CubeListBuilder.create().texOffs(270, 26).addBox(-1.25f, -1.25f, -1.5f, 2.5f, 2.5f, 3f), PartPose.offsetAndRotation(-13.221f, 5.901f, 47.279f, 0f, 2.827f, 0f))
            p.addOrReplaceChild("bh2", CubeListBuilder.create().texOffs(431, 330).addBox(-2.5f, -1f, -1.75f, 5f, 2f, 3.5f), PartPose.offsetAndRotation(-13.819f, 7.595f, 47.138f, 0f, 2.814f, 0f))
            p.addOrReplaceChild("bh8", CubeListBuilder.create().texOffs(11, 328).addBox(-2.5f, -1f, -2f, 5f, 2f, 4f), PartPose.offsetAndRotation(-28.651f, -9.306f, 38.175f, 0f, 2.38f, 0f))
            p.addOrReplaceChild("bh9", CubeListBuilder.create().texOffs(416, 216).addBox(-2.5f, -2f, -1.25f, 5f, 4f, 2.5f), PartPose.offsetAndRotation(-27.584f, -8.34f, 38.661f, 0f, 2.408f, 0f))
            p.addOrReplaceChild("bh24", CubeListBuilder.create().texOffs(305, 271).addBox(-1.5f, -2f, -2f, 3f, 4f, 4f), PartPose.offsetAndRotation(39.424f, -3.792f, 22.863f, 0f, -1.941f, 0f))
            p.addOrReplaceChild("bh25", CubeListBuilder.create().texOffs(127, 265).addBox(-1.75f, -1.5f, -1.5f, 3.5f, 3f, 3f), PartPose.offsetAndRotation(35.846f, -9.781f, 28.363f, 0f, -2.084f, 0f))
            p.addOrReplaceChild("bh26", CubeListBuilder.create().texOffs(175, 332).addBox(-1.75f, -1f, -1.75f, 3.5f, 2f, 3.5f), PartPose.offsetAndRotation(38.097f, -9.967f, 24.032f, 0f, -1.965f, 0f))
            p.addOrReplaceChild("bh27", CubeListBuilder.create().texOffs(448, 293).addBox(-1.25f, -1f, -1.25f, 2.5f, 2f, 2.5f), PartPose.offsetAndRotation(38.554f, -3.603f, 23.853f, 0f, -1.971f, 0f))
            p.addOrReplaceChild("bh28", CubeListBuilder.create().texOffs(88, 32).addBox(-1.75f, -1f, -1.25f, 3.5f, 2f, 2.5f), PartPose.offsetAndRotation(37.551f, -2.674f, 26.19f, 0f, -2.033f, 0f))
            val wd2_1 = p.addOrReplaceChild("wd2_1", CubeListBuilder.create().texOffs(497, 97).addBox(-2.5f, 0f, -0.25f, 5f, 11f, 0.5f, FACES_FK), PartPose.offsetAndRotation(27.613f, -7f, 38.567f, -0.316f, -2.409f, 0f))
            val wd2_2 = wd2_1.addOrReplaceChild("wd2_2", CubeListBuilder.create().texOffs(365, 290).addBox(-2f, 0f, -0.25f, 4f, 10f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 11f, 0f, 0.142f, 0f, 0f))
            wd2_2.addOrReplaceChild("wd2_3", CubeListBuilder.create().texOffs(477, 17).addBox(-1.5f, 0f, -0.25f, 3f, 9f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 10f, 0f, 0.142f, 0f, 0f))
            val wd3_1 = p.addOrReplaceChild("wd3_1", CubeListBuilder.create().texOffs(466, 173).addBox(-2.5f, 0f, -0.25f, 5f, 11f, 0.5f, FACES_FK), PartPose.offsetAndRotation(-42.132f, 1f, 8.571f, -0.255f, 1.606f, 0f))
            val wd3_2 = wd3_1.addOrReplaceChild("wd3_2", CubeListBuilder.create().texOffs(503, 290).addBox(-2f, 0f, -0.25f, 4f, 10f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 11f, 0f, 0.115f, 0f, 0f))
            wd3_2.addOrReplaceChild("wd3_3", CubeListBuilder.create().texOffs(496, 30).addBox(-1.5f, 0f, -0.25f, 3f, 9f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 10f, 0f, 0.115f, 0f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead5(p: PartDefinition) {
            val sp3 = p.addOrReplaceChild("sp3", CubeListBuilder.create(), PartPose.offset(0f, -20f, 0f))
            buildHead6(sp3)
            buildHead7(sp3)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead8(p: PartDefinition) {
            val mt12 = p.addOrReplaceChild("mt12", CubeListBuilder.create().texOffs(0, 0).addBox(-29.991f, -4f, -29.991f, 60f, 8f, 60f, FACES_RFLK), PartPose.offset(0f, -8f, 6f))
            mt12.addOrReplaceChild("mt12_c1", CubeListBuilder.create().texOffs(240, 0).addBox(-29.991f, -3.9f, -29.991f, 60f, 8f, 60f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt12.addOrReplaceChild("mt12_c2", CubeListBuilder.create().texOffs(0, 8).addBox(-29.991f, -3.8f, -29.991f, 60f, 8f, 60f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt12.addOrReplaceChild("mt12_c3", CubeListBuilder.create().texOffs(240, 8).addBox(-29.991f, -3.7f, -29.991f, 60f, 8f, 60f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            val mt13 = p.addOrReplaceChild("mt13", CubeListBuilder.create().texOffs(0, 33).addBox(-29.824f, -4f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offset(0f, 0f, 5.2f))
            mt13.addOrReplaceChild("mt13_c1", CubeListBuilder.create().texOffs(238, 33).addBox(-29.824f, -3.9f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt13.addOrReplaceChild("mt13_c2", CubeListBuilder.create().texOffs(0, 41).addBox(-29.824f, -3.8f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt13.addOrReplaceChild("mt13_c3", CubeListBuilder.create().texOffs(238, 41).addBox(-29.824f, -3.7f, -29.824f, 59.5f, 8f, 59.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            val mt14 = p.addOrReplaceChild("mt14", CubeListBuilder.create().texOffs(0, 66).addBox(-29.319f, -4f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offset(0f, 8f, 4.4f))
            mt14.addOrReplaceChild("mt14_c1", CubeListBuilder.create().texOffs(234, 66).addBox(-29.319f, -3.9f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            mt14.addOrReplaceChild("mt14_c2", CubeListBuilder.create().texOffs(0, 74).addBox(-29.319f, -3.8f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            mt14.addOrReplaceChild("mt14_c3", CubeListBuilder.create().texOffs(234, 74).addBox(-29.319f, -3.7f, -29.319f, 58.5f, 8f, 58.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            val bu0 = p.addOrReplaceChild("bu0", CubeListBuilder.create().texOffs(102, 0).addBox(-7f, 0f, -5f, 14f, 6f, 10f), PartPose.offsetAndRotation(0f, -11.5f, -35f, -1.396f, 0f, 0f))
            val bu1 = bu0.addOrReplaceChild("bu1", CubeListBuilder.create().texOffs(133, 264).addBox(-5f, 0f, -3.5f, 10f, 5.5f, 7f), PartPose.offsetAndRotation(0f, 6f, 0f, 0.524f, 0f, 0f))
            bu1.addOrReplaceChild("bu2", CubeListBuilder.create().texOffs(492, 215).addBox(-2.5f, 0f, -2f, 5f, 5f, 4f), PartPose.offsetAndRotation(0f, 5.5f, 0f, 0.696f, 0f, 0f))
            val bl0 = p.addOrReplaceChild("bl0", CubeListBuilder.create().texOffs(40, 263).addBox(-6f, 0f, -3f, 12f, 6.5f, 6f), PartPose.offsetAndRotation(0f, -5f, -35f, -1.082f, 0f, 0f))
            bl0.addOrReplaceChild("bl1", CubeListBuilder.create().texOffs(436, 188).addBox(-4f, 0f, -2.25f, 8f, 5f, 4.5f), PartPose.offsetAndRotation(0f, 6.5f, 0f, 0.454f, 0f, 0f))
            p.addOrReplaceChild("throat", CubeListBuilder.create().texOffs(480, 258).addBox(-5f, -4f, -3f, 10f, 8f, 6f), PartPose.offset(0f, -4f, -37f))
            val rh0 = p.addOrReplaceChild("rh0", CubeListBuilder.create(), PartPose.offsetAndRotation(6.883f, -8.83f, -36f, -0.642f, 0.226f, -2.531f))
            val rt0_1 = rh0.addOrReplaceChild("rt0_1", CubeListBuilder.create().texOffs(354, 15).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt0_2 = rt0_1.addOrReplaceChild("rt0_2", CubeListBuilder.create().texOffs(271, 262).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt0_3 = rt0_2.addOrReplaceChild("rt0_3", CubeListBuilder.create().texOffs(317, 284).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt0_4 = rt0_3.addOrReplaceChild("rt0_4", CubeListBuilder.create().texOffs(315, 12).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt0_5 = rt0_4.addOrReplaceChild("rt0_5", CubeListBuilder.create().texOffs(422, 26).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt0_5.addOrReplaceChild("rt0_5_c1", CubeListBuilder.create().texOffs(496, 224).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt0_4.addOrReplaceChild("rt0_4_c1", CubeListBuilder.create().texOffs(412, 46).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt0_3.addOrReplaceChild("rt0_3_c1", CubeListBuilder.create().texOffs(222, 286).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt0_3.addOrReplaceChild("rt0_3_c2", CubeListBuilder.create().texOffs(475, 286).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt0_2.addOrReplaceChild("rt0_2_c1", CubeListBuilder.create().texOffs(0, 264).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt0_2.addOrReplaceChild("rt0_2_c2", CubeListBuilder.create().texOffs(371, 264).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt0_1.addOrReplaceChild("rt0_1_c1", CubeListBuilder.create().texOffs(456, 20).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt0_1.addOrReplaceChild("rt0_1_c2", CubeListBuilder.create().texOffs(464, 195).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val rh1 = p.addOrReplaceChild("rh1", CubeListBuilder.create(), PartPose.offsetAndRotation(11.591f, -2.106f, -36f, -0.591f, 0.369f, -1.833f))
            val rt1_1 = rh1.addOrReplaceChild("rt1_1", CubeListBuilder.create().texOffs(452, 205).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt1_2 = rt1_1.addOrReplaceChild("rt1_2", CubeListBuilder.create().texOffs(405, 264).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt1_3 = rt1_2.addOrReplaceChild("rt1_3", CubeListBuilder.create().texOffs(24, 287).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt1_4 = rt1_3.addOrReplaceChild("rt1_4", CubeListBuilder.create().texOffs(434, 46).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt1_5 = rt1_4.addOrReplaceChild("rt1_5", CubeListBuilder.create().texOffs(86, 292).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt1_5.addOrReplaceChild("rt1_5_c1", CubeListBuilder.create().texOffs(52, 294).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt1_4.addOrReplaceChild("rt1_4_c1", CubeListBuilder.create().texOffs(456, 46).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt1_3.addOrReplaceChild("rt1_3_c1", CubeListBuilder.create().texOffs(220, 295).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt1_3.addOrReplaceChild("rt1_3_c2", CubeListBuilder.create().texOffs(475, 295).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt1_2.addOrReplaceChild("rt1_2_c1", CubeListBuilder.create().texOffs(439, 264).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt1_2.addOrReplaceChild("rt1_2_c2", CubeListBuilder.create().texOffs(473, 264).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt1_1.addOrReplaceChild("rt1_1_c1", CubeListBuilder.create().texOffs(376, 212).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt1_1.addOrReplaceChild("rt1_1_c2", CubeListBuilder.create().texOffs(416, 215).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val rh2 = p.addOrReplaceChild("rh2", CubeListBuilder.create(), PartPose.offsetAndRotation(10.876f, 6.071f, -36f, -0.517f, 0.348f, -1.134f))
            val rt2_1 = rh2.addOrReplaceChild("rt2_1", CubeListBuilder.create().texOffs(456, 215).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt2_2 = rt2_1.addOrReplaceChild("rt2_2", CubeListBuilder.create().texOffs(167, 266).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt2_3 = rt2_2.addOrReplaceChild("rt2_3", CubeListBuilder.create().texOffs(248, 298).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt2_4 = rt2_3.addOrReplaceChild("rt2_4", CubeListBuilder.create().texOffs(490, 111).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt2_5 = rt2_4.addOrReplaceChild("rt2_5", CubeListBuilder.create().texOffs(452, 315).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt2_5.addOrReplaceChild("rt2_5_c1", CubeListBuilder.create().texOffs(492, 316).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt2_4.addOrReplaceChild("rt2_4_c1", CubeListBuilder.create().texOffs(490, 119).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt2_3.addOrReplaceChild("rt2_3_c1", CubeListBuilder.create().texOffs(0, 299).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt2_3.addOrReplaceChild("rt2_3_c2", CubeListBuilder.create().texOffs(28, 299).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt2_2.addOrReplaceChild("rt2_2_c1", CubeListBuilder.create().texOffs(201, 266).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt2_2.addOrReplaceChild("rt2_2_c2", CubeListBuilder.create().texOffs(235, 266).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt2_1.addOrReplaceChild("rt2_1_c1", CubeListBuilder.create().texOffs(166, 241).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt2_1.addOrReplaceChild("rt2_1_c2", CubeListBuilder.create().texOffs(206, 241).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val rh3 = p.addOrReplaceChild("rh3", CubeListBuilder.create(), PartPose.offsetAndRotation(5.071f, 11.876f, -36f, -0.457f, 0.167f, -0.436f))
            val rt3_1 = rh3.addOrReplaceChild("rt3_1", CubeListBuilder.create().texOffs(246, 241).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt3_2 = rt3_1.addOrReplaceChild("rt3_2", CubeListBuilder.create().texOffs(34, 267).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt3_3 = rt3_2.addOrReplaceChild("rt3_3", CubeListBuilder.create().texOffs(56, 299).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt3_4 = rt3_3.addOrReplaceChild("rt3_4", CubeListBuilder.create().texOffs(484, 192).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt3_5 = rt3_4.addOrReplaceChild("rt3_5", CubeListBuilder.create().texOffs(152, 318).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt3_5.addOrReplaceChild("rt3_5_c1", CubeListBuilder.create().texOffs(196, 320).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt3_4.addOrReplaceChild("rt3_4_c1", CubeListBuilder.create().texOffs(281, 285).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt3_3.addOrReplaceChild("rt3_3_c1", CubeListBuilder.create().texOffs(168, 299).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt3_3.addOrReplaceChild("rt3_3_c2", CubeListBuilder.create().texOffs(276, 299).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt3_2.addOrReplaceChild("rt3_2_c1", CubeListBuilder.create().texOffs(127, 268).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt3_2.addOrReplaceChild("rt3_2_c2", CubeListBuilder.create().texOffs(269, 271).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt3_1.addOrReplaceChild("rt3_1_c1", CubeListBuilder.create().texOffs(286, 241).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt3_1.addOrReplaceChild("rt3_1_c2", CubeListBuilder.create().texOffs(0, 243).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val rh4 = p.addOrReplaceChild("rh4", CubeListBuilder.create(), PartPose.offsetAndRotation(-3.106f, 12.591f, -36f, -0.449f, -0.103f, 0.262f))
            val rt4_1 = rh4.addOrReplaceChild("rt4_1", CubeListBuilder.create().texOffs(40, 243).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt4_2 = rt4_1.addOrReplaceChild("rt4_2", CubeListBuilder.create().texOffs(68, 272).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt4_3 = rt4_2.addOrReplaceChild("rt4_3", CubeListBuilder.create().texOffs(304, 299).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt4_4 = rt4_3.addOrReplaceChild("rt4_4", CubeListBuilder.create().texOffs(320, 309).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt4_5 = rt4_4.addOrReplaceChild("rt4_5", CubeListBuilder.create().texOffs(212, 320).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt4_5.addOrReplaceChild("rt4_5_c1", CubeListBuilder.create().texOffs(468, 320).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt4_4.addOrReplaceChild("rt4_4_c1", CubeListBuilder.create().texOffs(342, 309).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt4_3.addOrReplaceChild("rt4_3_c1", CubeListBuilder.create().texOffs(332, 299).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt4_3.addOrReplaceChild("rt4_3_c2", CubeListBuilder.create().texOffs(360, 299).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt4_2.addOrReplaceChild("rt4_2_c1", CubeListBuilder.create().texOffs(0, 273).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt4_2.addOrReplaceChild("rt4_2_c2", CubeListBuilder.create().texOffs(303, 273).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt4_1.addOrReplaceChild("rt4_1_c1", CubeListBuilder.create().texOffs(80, 243).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt4_1.addOrReplaceChild("rt4_1_c2", CubeListBuilder.create().texOffs(120, 243).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val rh5 = p.addOrReplaceChild("rh5", CubeListBuilder.create(), PartPose.offsetAndRotation(-9.83f, 7.883f, -36f, -0.499f, -0.317f, 0.96f))
            val rt5_1 = rh5.addOrReplaceChild("rt5_1", CubeListBuilder.create().texOffs(326, 244).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt5_2 = rt5_1.addOrReplaceChild("rt5_2", CubeListBuilder.create().texOffs(337, 273).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt5_3 = rt5_2.addOrReplaceChild("rt5_3", CubeListBuilder.create().texOffs(388, 299).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt5_4 = rt5_3.addOrReplaceChild("rt5_4", CubeListBuilder.create().texOffs(364, 309).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt5_5 = rt5_4.addOrReplaceChild("rt5_5", CubeListBuilder.create().texOffs(309, 322).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt5_5.addOrReplaceChild("rt5_5_c1", CubeListBuilder.create().texOffs(325, 322).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt5_4.addOrReplaceChild("rt5_4_c1", CubeListBuilder.create().texOffs(386, 309).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt5_3.addOrReplaceChild("rt5_3_c1", CubeListBuilder.create().texOffs(416, 299).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt5_3.addOrReplaceChild("rt5_3_c2", CubeListBuilder.create().texOffs(444, 299).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt5_2.addOrReplaceChild("rt5_2_c1", CubeListBuilder.create().texOffs(371, 273).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt5_2.addOrReplaceChild("rt5_2_c2", CubeListBuilder.create().texOffs(405, 273).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt5_1.addOrReplaceChild("rt5_1_c1", CubeListBuilder.create().texOffs(366, 244).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt5_1.addOrReplaceChild("rt5_1_c2", CubeListBuilder.create().texOffs(406, 244).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead9(p: PartDefinition) {
            val rh6 = p.addOrReplaceChild("rh6", CubeListBuilder.create(), PartPose.offsetAndRotation(-11.954f, -0.046f, -36f, -0.574f, -0.379f, 1.658f))
            val rt6_1 = rh6.addOrReplaceChild("rt6_1", CubeListBuilder.create().texOffs(446, 244).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt6_2 = rt6_1.addOrReplaceChild("rt6_2", CubeListBuilder.create().texOffs(439, 273).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt6_3 = rt6_2.addOrReplaceChild("rt6_3", CubeListBuilder.create().texOffs(84, 302).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt6_4 = rt6_3.addOrReplaceChild("rt6_4", CubeListBuilder.create().texOffs(408, 309).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt6_5 = rt6_4.addOrReplaceChild("rt6_5", CubeListBuilder.create().texOffs(341, 322).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt6_5.addOrReplaceChild("rt6_5_c1", CubeListBuilder.create().texOffs(357, 322).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt6_4.addOrReplaceChild("rt6_4_c1", CubeListBuilder.create().texOffs(430, 309).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt6_3.addOrReplaceChild("rt6_3_c1", CubeListBuilder.create().texOffs(112, 302).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt6_3.addOrReplaceChild("rt6_3_c2", CubeListBuilder.create().texOffs(140, 302).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt6_2.addOrReplaceChild("rt6_2_c1", CubeListBuilder.create().texOffs(473, 273).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt6_2.addOrReplaceChild("rt6_2_c2", CubeListBuilder.create().texOffs(161, 275).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt6_1.addOrReplaceChild("rt6_1_c1", CubeListBuilder.create().texOffs(160, 251).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt6_1.addOrReplaceChild("rt6_1_c2", CubeListBuilder.create().texOffs(200, 251).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val rh7 = p.addOrReplaceChild("rh7", CubeListBuilder.create(), PartPose.offsetAndRotation(-8.485f, -7.485f, -36f, -0.633f, -0.276f, 2.356f))
            val rt7_1 = rh7.addOrReplaceChild("rt7_1", CubeListBuilder.create().texOffs(240, 251).addBox(-4.928f, 0f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val rt7_2 = rt7_1.addOrReplaceChild("rt7_2", CubeListBuilder.create().texOffs(195, 275).addBox(-4.137f, 0f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offset(0f, 9.5f, 0f))
            val rt7_3 = rt7_2.addOrReplaceChild("rt7_3", CubeListBuilder.create().texOffs(196, 304).addBox(-3.382f, 0f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offset(0f, 9f, 0f))
            val rt7_4 = rt7_3.addOrReplaceChild("rt7_4", CubeListBuilder.create().texOffs(52, 311).addBox(-2.72f, 0f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offset(0f, 8.5f, 0f))
            val rt7_5 = rt7_4.addOrReplaceChild("rt7_5", CubeListBuilder.create().texOffs(373, 322).addBox(-2.022f, 0f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offset(0f, 7.5f, 0f))
            rt7_5.addOrReplaceChild("rt7_5_c1", CubeListBuilder.create().texOffs(389, 322).addBox(-2.022f, 0.1f, -2.022f, 4f, 6.5f, 4f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt7_4.addOrReplaceChild("rt7_4_c1", CubeListBuilder.create().texOffs(74, 312).addBox(-2.72f, 0.1f, -2.72f, 5.5f, 7.5f, 5.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            rt7_3.addOrReplaceChild("rt7_3_c1", CubeListBuilder.create().texOffs(472, 304).addBox(-3.382f, 0.1f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt7_3.addOrReplaceChild("rt7_3_c2", CubeListBuilder.create().texOffs(224, 307).addBox(-3.382f, 0.2f, -3.382f, 7f, 8.5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt7_2.addOrReplaceChild("rt7_2_c1", CubeListBuilder.create().texOffs(229, 275).addBox(-4.137f, 0.1f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt7_2.addOrReplaceChild("rt7_2_c2", CubeListBuilder.create().texOffs(34, 276).addBox(-4.137f, 0.2f, -4.137f, 8.5f, 9f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            rt7_1.addOrReplaceChild("rt7_1_c1", CubeListBuilder.create().texOffs(280, 251).addBox(-4.928f, 0.1f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            rt7_1.addOrReplaceChild("rt7_1_c2", CubeListBuilder.create().texOffs(0, 253).addBox(-4.928f, 0.2f, -4.928f, 10f, 9.5f, 10f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val siphon = p.addOrReplaceChild("siphon", CubeListBuilder.create(), PartPose.offsetAndRotation(40f, -4f, 4f, 1.927f, -1.131f, -3.142f))
            val sf1 = siphon.addOrReplaceChild("sf1", CubeListBuilder.create().texOffs(242, 19).addBox(-3.455f, 0f, -3.455f, 7f, 5f, 7f, FACES_RFLK), PartPose.offset(0f, 0f, 0f))
            val sf2 = sf1.addOrReplaceChild("sf2", CubeListBuilder.create().texOffs(322, 17).addBox(-4.117f, 0f, -4.117f, 8f, 4.5f, 8f, FACES_RFLK), PartPose.offset(0f, 5f, 0f))
            val sf3 = sf2.addOrReplaceChild("sf3", CubeListBuilder.create().texOffs(233, 314).addBox(-4.779f, 0f, -4.779f, 9.5f, 3.5f, 9.5f, FACES_BRFLK), PartPose.offset(0f, 4.5f, 0f))
            sf3.addOrReplaceChild("sf3_c1", CubeListBuilder.create().texOffs(0, 315).addBox(-4.779f, 0.1f, -4.779f, 9.5f, 3.5f, 9.5f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            sf2.addOrReplaceChild("sf2_c1", CubeListBuilder.create().texOffs(210, 18).addBox(-4.117f, 0.1f, -4.117f, 8f, 4.5f, 8f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            sf1.addOrReplaceChild("sf1_c1", CubeListBuilder.create().texOffs(394, 23).addBox(-3.455f, 0.1f, -3.455f, 7f, 5f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            p.addOrReplaceChild("bh0", CubeListBuilder.create().texOffs(480, 154).addBox(-1.5f, -2f, -1.5f, 3f, 4f, 3f), PartPose.offsetAndRotation(-17.084f, -9.931f, 45.53f, 0f, 2.732f, 0f))
            p.addOrReplaceChild("bh12", CubeListBuilder.create().texOffs(105, 326).addBox(-2f, -1.5f, -1.75f, 4f, 3f, 3.5f), PartPose.offsetAndRotation(35.762f, -2.211f, 29.168f, 0f, -2.157f, 0f))
            p.addOrReplaceChild("bh13", CubeListBuilder.create().texOffs(502, 109).addBox(-1.25f, -2f, -1.25f, 2.5f, 4f, 2.5f), PartPose.offsetAndRotation(36.774f, -0.348f, 26.614f, 0f, -2.097f, 0f))
            p.addOrReplaceChild("bh14", CubeListBuilder.create().texOffs(42, 330).addBox(-1.25f, -1.5f, -2f, 2.5f, 3f, 4f), PartPose.offsetAndRotation(38.656f, 1.686f, 23.538f, 0f, -2.017f, 0f))
            p.addOrReplaceChild("bh15", CubeListBuilder.create().texOffs(427, 20).addBox(-1.25f, -1.5f, -1.5f, 2.5f, 3f, 3f), PartPose.offsetAndRotation(35.788f, -3.171f, 29.019f, 0f, -2.152f, 0f))
            p.addOrReplaceChild("bh20", CubeListBuilder.create().texOffs(147, 329).addBox(-2f, -1.25f, -2f, 4f, 2.5f, 4f), PartPose.offsetAndRotation(37.412f, 6.303f, -15.488f, 0f, -1.079f, 0f))
            p.addOrReplaceChild("bh21", CubeListBuilder.create().texOffs(448, 165).addBox(-1.25f, -1f, -1.25f, 2.5f, 2f, 2.5f), PartPose.offsetAndRotation(36.488f, 8.66f, -15.911f, 0f, -1.064f, 0f))
            p.addOrReplaceChild("bh22", CubeListBuilder.create().texOffs(495, 327).addBox(-1.75f, -1.5f, -2f, 3.5f, 3f, 4f), PartPose.offsetAndRotation(35.426f, 6.806f, -18.769f, 0f, -0.989f, 0f))
            p.addOrReplaceChild("bh23", CubeListBuilder.create().texOffs(450, 326).addBox(-2.5f, -1f, -2f, 5f, 2f, 4f), PartPose.offsetAndRotation(34.952f, 8.891f, -19.247f, 0f, -0.978f, 0f))
            val wd5_1 = p.addOrReplaceChild("wd5_1", CubeListBuilder.create().texOffs(500, 245).addBox(-2.5f, 0f, -0.25f, 5f, 11f, 0.5f, FACES_FK), PartPose.offsetAndRotation(40.515f, -8f, -7.164f, -0.175f, -1.257f, 0f))
            val wd5_2 = wd5_1.addOrReplaceChild("wd5_2", CubeListBuilder.create().texOffs(393, 292).addBox(-2f, 0f, -0.25f, 4f, 10f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 11f, 0f, 0.079f, 0f, 0f))
            wd5_2.addOrReplaceChild("wd5_3", CubeListBuilder.create().texOffs(460, 140).addBox(-1.5f, 0f, -0.25f, 3f, 9f, 0.5f, FACES_FK), PartPose.offsetAndRotation(0f, 10f, 0f, 0.079f, 0f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead4(p: PartDefinition) {
            val sp2 = p.addOrReplaceChild("sp2", CubeListBuilder.create(), PartPose.offset(0f, -20f, 0f))
            buildHead5(sp2)
            buildHead8(sp2)
            buildHead9(sp2)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead10(p: PartDefinition) {
            val mt15 = p.addOrReplaceChild("mt15", CubeListBuilder.create().texOffs(0, 83).addBox(-28.672f, -4f, -28.672f, 57.5f, 8f, 57.5f, FACES_RFLK), PartPose.offset(0f, -4f, 3.6f))
            mt15.addOrReplaceChild("mt15_c1", CubeListBuilder.create().texOffs(230, 83).addBox(-28.672f, -3.9f, -28.672f, 57.5f, 8f, 57.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt15.addOrReplaceChild("mt15_c2", CubeListBuilder.create().texOffs(0, 91).addBox(-28.672f, -3.8f, -28.672f, 57.5f, 8f, 57.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt16 = p.addOrReplaceChild("mt16", CubeListBuilder.create().texOffs(228, 110).addBox(-27.409f, -4f, -27.409f, 55f, 8f, 55f, FACES_RFLK), PartPose.offset(0f, 4f, 2.8f))
            mt16.addOrReplaceChild("mt16_c1", CubeListBuilder.create().texOffs(0, 118).addBox(-27.409f, -3.9f, -27.409f, 55f, 8f, 55f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt16.addOrReplaceChild("mt16_c2", CubeListBuilder.create().texOffs(220, 118).addBox(-27.409f, -3.8f, -27.409f, 55f, 8f, 55f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            p.addOrReplaceChild("bh3", CubeListBuilder.create().texOffs(464, 331).addBox(-1.75f, -1f, -2f, 3.5f, 2f, 4f), PartPose.offsetAndRotation(40.662f, -7.079f, -4.767f, 0f, -1.361f, 0f))
            p.addOrReplaceChild("bh4", CubeListBuilder.create().texOffs(71, 332).addBox(-2f, -1f, -1.75f, 4f, 2f, 3.5f), PartPose.offsetAndRotation(40.236f, -3.037f, -2.754f, 0f, -1.416f, 0f))
            p.addOrReplaceChild("bh5", CubeListBuilder.create().texOffs(340, 277).addBox(-1.5f, -1f, -1.25f, 3f, 2f, 2.5f), PartPose.offsetAndRotation(40.358f, -3.204f, 0.766f, 0f, -1.503f, 0f))
            p.addOrReplaceChild("bh6", CubeListBuilder.create().texOffs(207, 331).addBox(-2f, -1.5f, -1.25f, 4f, 3f, 2.5f), PartPose.offsetAndRotation(40.651f, -8.832f, -3.713f, 0f, -1.381f, 0f))
            p.addOrReplaceChild("bh7", CubeListBuilder.create().texOffs(191, 331).addBox(-2f, -1f, -2f, 4f, 2f, 4f), PartPose.offsetAndRotation(40.701f, -5.218f, -3.097f, 0f, -1.405f, 0f))
            p.addOrReplaceChild("bh33", CubeListBuilder.create().texOffs(295, 328).addBox(-1.25f, -2f, -2f, 2.5f, 4f, 4f), PartPose.offsetAndRotation(-36.846f, -3.087f, 21.215f, 0f, 2.019f, 0f))
            p.addOrReplaceChild("bh34", CubeListBuilder.create().texOffs(282, 332).addBox(-1.5f, -1f, -1.75f, 3f, 2f, 3.5f), PartPose.offsetAndRotation(-36.501f, -5.883f, 22.966f, 0f, 2.055f, 0f))
            p.addOrReplaceChild("bh35", CubeListBuilder.create().texOffs(452, 208).addBox(-1.5f, -1f, -1.25f, 3f, 2f, 2.5f), PartPose.offsetAndRotation(-36.47f, -3.93f, 21.407f, 0f, 2.025f, 0f))
            p.addOrReplaceChild("bh36", CubeListBuilder.create().texOffs(228, 327).addBox(-2.5f, -1.5f, -1.5f, 5f, 3f, 3f), PartPose.offsetAndRotation(-35.481f, 0.699f, 21.14f, 0f, 2.04f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead3(p: PartDefinition) {
            val sp1 = p.addOrReplaceChild("sp1", CubeListBuilder.create(), PartPose.offset(0f, -20f, 0f))
            buildHead4(sp1)
            buildHead10(sp1)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead11(p: PartDefinition) {
            val mt17 = p.addOrReplaceChild("mt17", CubeListBuilder.create().texOffs(0, 0).addBox(-25.694f, -4f, -25.694f, 51.5f, 8f, 51.5f, FACES_RFLK), PartPose.offset(0f, -8f, 2f))
            mt17.addOrReplaceChild("mt17_c1", CubeListBuilder.create().texOffs(206, 0).addBox(-25.694f, -3.9f, -25.694f, 51.5f, 8f, 51.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt17.addOrReplaceChild("mt17_c2", CubeListBuilder.create().texOffs(0, 146).addBox(-25.694f, -3.8f, -25.694f, 51.5f, 8f, 51.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt18 = p.addOrReplaceChild("mt18", CubeListBuilder.create().texOffs(204, 167).addBox(-23.428f, -4f, -23.428f, 47f, 8f, 47f, FACES_RFLK), PartPose.offset(0f, 0f, 1.2f))
            mt18.addOrReplaceChild("mt18_c1", CubeListBuilder.create().texOffs(0, 175).addBox(-23.428f, -3.9f, -23.428f, 47f, 8f, 47f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt18.addOrReplaceChild("mt18_c2", CubeListBuilder.create().texOffs(188, 175).addBox(-23.428f, -3.8f, -23.428f, 47f, 8f, 47f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt19 = p.addOrReplaceChild("mt19", CubeListBuilder.create().texOffs(0, 0).addBox(-20.854f, -3f, -20.854f, 41.5f, 6f, 41.5f, FACES_RFLK), PartPose.offset(0f, 7f, 0.5f))
            mt19.addOrReplaceChild("mt19_c1", CubeListBuilder.create().texOffs(166, 0).addBox(-20.854f, -2.9f, -20.854f, 41.5f, 6f, 41.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt19.addOrReplaceChild("mt19_c2", CubeListBuilder.create().texOffs(332, 0).addBox(-20.854f, -2.8f, -20.854f, 41.5f, 6f, 41.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt20 = p.addOrReplaceChild("mt20", CubeListBuilder.create().texOffs(0, 0).addBox(-18.289f, -2.5f, -18.289f, 36.5f, 5f, 36.5f, FACES_RFLK), PartPose.offset(0f, 12.5f, -0.05f))
            mt20.addOrReplaceChild("mt20_c1", CubeListBuilder.create().texOffs(146, 0).addBox(-18.289f, -2.4f, -18.289f, 36.5f, 5f, 36.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt20.addOrReplaceChild("mt20_c2", CubeListBuilder.create().texOffs(292, 0).addBox(-18.289f, -2.3f, -18.289f, 36.5f, 5f, 36.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt21 = p.addOrReplaceChild("mt21", CubeListBuilder.create().texOffs(102, 0).addBox(-15.659f, -2f, -15.659f, 31.5f, 4f, 31.5f, FACES_RFLK), PartPose.offset(0f, 17f, -0.5f))
            mt21.addOrReplaceChild("mt21_c1", CubeListBuilder.create().texOffs(228, 0).addBox(-15.659f, -1.9f, -15.659f, 31.5f, 4f, 31.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            mt21.addOrReplaceChild("mt21_c2", CubeListBuilder.create().texOffs(0, 16).addBox(-15.659f, -1.8f, -15.659f, 31.5f, 4f, 31.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            val mt22 = p.addOrReplaceChild("mt22", CubeListBuilder.create().texOffs(102, 0).addBox(-13.375f, -1.5f, -13.375f, 27f, 3f, 27f, FACES_RFLK), PartPose.offset(0f, 20.5f, -0.85f))
            mt22.addOrReplaceChild("mt22_c1", CubeListBuilder.create().texOffs(376, 208).addBox(-13.375f, -1.4f, -13.375f, 27f, 3f, 27f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            val mt23 = p.addOrReplaceChild("mt23", CubeListBuilder.create().texOffs(138, 272).addBox(-10.467f, -1.5f, -10.467f, 21f, 3f, 21f, FACES_RFLK), PartPose.offset(0f, 23.5f, -1.15f))
            mt23.addOrReplaceChild("mt23_c1", CubeListBuilder.create().texOffs(138, 275).addBox(-10.467f, -1.4f, -10.467f, 21f, 3f, 21f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            val mt24 = p.addOrReplaceChild("mt24", CubeListBuilder.create().texOffs(281, 291).addBox(-6.105f, -1.5f, -6.105f, 12f, 3f, 12f, FACES_BRFLK), PartPose.offset(0f, 26.5f, -1.45f))
            mt24.addOrReplaceChild("mt24_c1", CubeListBuilder.create().texOffs(329, 291).addBox(-6.105f, -1.4f, -6.105f, 12f, 3f, 12f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            p.addOrReplaceChild("bh16", CubeListBuilder.create().texOffs(412, 328).addBox(-1.75f, -1.25f, -2f, 3.5f, 2.5f, 4f), PartPose.offsetAndRotation(30.513f, 5.969f, 4.582f, 0f, -1.7f, 0f))
            p.addOrReplaceChild("bh17", CubeListBuilder.create().texOffs(440, 198).addBox(-2.5f, -1.5f, -1.75f, 5f, 3f, 3.5f), PartPose.offsetAndRotation(32.227f, 2.296f, 5.452f, 0f, -1.709f, 0f))
            p.addOrReplaceChild("bh18", CubeListBuilder.create().texOffs(126, 326).addBox(-1.75f, -1.5f, -2f, 3.5f, 3f, 4f), PartPose.offsetAndRotation(30.658f, 5.635f, 4.924f, 0f, -1.71f, 0f))
            p.addOrReplaceChild("bh19", CubeListBuilder.create().texOffs(281, 324).addBox(-2.5f, -1.25f, -2f, 5f, 2.5f, 4f), PartPose.offsetAndRotation(30.587f, 4.832f, 7.783f, 0f, -1.798f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead2(p: PartDefinition) {
            val sp0 = p.addOrReplaceChild("sp0", CubeListBuilder.create(), PartPose.offset(0f, 36f, 0f))
            buildHead3(sp0)
            buildHead11(sp0)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHead1(p: PartDefinition) {
            val head = p.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.offset(0f, 0f, 0f))
            buildHead2(head)
        }

        private val FACES_BRFLK: Set<Direction> = EnumSet.of(Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)
        private val FACES_F: Set<Direction> = EnumSet.of(Direction.NORTH)
        private val FACES_FK: Set<Direction> = EnumSet.of(Direction.NORTH, Direction.SOUTH)
        private val FACES_RFLK: Set<Direction> = EnumSet.of(Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)
        private val FACES_TB: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP)
        private val FACES_TBRFL: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST)
        private val FACES_TRFLK: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)
    }
}
