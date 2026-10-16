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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A Gnashfin: the pack predator that hunts under the ships, two and a half blocks of armoured pike. Models are in pixels with y down and the fish
 * facing -z (its right is -x); the origin is the middle of its body, on the spine. It has a long low skull, an underslung jaw that juts past the
 * snout with a pair of needle fangs standing up at its tip, a tall ragged dorsal fin (eleven slats) that cuts the surface, long pectorals, a
 * narrow body that tapers to a deeply forked tail, and a row of small sea-green lights along each flank and a lure on a stalk under its chin that
 * glow (the glow map).
 *
 * The body is a chain of segments (`mid` at the origin; `fore` and `skull` toward the head; `aft`, `stalk` and `tail` toward the tail). Swimming
 * yaws every segment in a wave that runs back along it, growing toward the tail; [GnashfinRenderState.swim] makes the wave wider and quicker (three
 * fixed beats, idle, cruise and sprint, crossfaded by `swim`: a beat that changed with time would jump its phase) and tucks the pectorals back
 * along the flank; [GnashfinRenderState.bend] curves the body into a turn (its nose swings to its own right above 0); [GnashfinRenderState.jaw]
 * gapes the jaw; [GnashfinRenderState.leap] (-1 when it is not leaping, otherwise 0 to 1) arches it nose up while rising and nose down while
 * falling, throws the pectorals wide and flicks the tail at the top; [GnashfinRenderState.stranded] makes it flounder (the body bucks, the tail
 * slaps, the fins flail, the gills gasp, the jaw gapes and snaps); [GnashfinRenderState.gnaw] shakes its head side to side with the jaw clamped;
 * [GnashfinRenderState.time] flutters the fins, pulses the gills, the lights and the lure. The renderer does the pitch and the roll of the whole
 * fish and tints the glow layer by [GnashfinRenderState.glow].
 *
 * The geometry comes from art/scripts/sea_gnashfin.py (edit it there: it writes this file's body and the textures, and its `pose()` is the same
 * animation in Python, run for the previews and checked against this class by sea_monsters.py). The texture is HD: the model declares
 * 128x64 and the PNGs are painted at twice that. It is drawn with [RenderTypes.entityCutout] (the fins are cut to shape in the texture's
 * alpha, and that type does not cull back faces); the glow layer is the same model drawn again with [RenderTypes.eyes] over gnashfin_glow.png.
 */
class GnashfinModel(root: ModelPart) : EntityModel<GnashfinRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The chain of body segments, in the order of CHAIN_PHASE
    private val chain = Array(6) { part(CHAIN_NAMES[it]) }
    private val jaw = part("jaw")
    private val fangTips = arrayOf(part("fang_l2"), part("fang_r2"))
    private val gills = arrayOf(part("gill_l"), part("gill_r"))
    private val lure = part("lure")
    private val bead = part("bead")
    private val lights = Array(10) { part("lt$it") }
    private val dorsal = Array(DORSAL) { part("dn$it") }
    private val pec1 = arrayOf(part("pec_l1"), part("pec_r1"))
    private val pec2 = arrayOf(part("pec_l2"), part("pec_r2"))
    private val pelvic = arrayOf(part("pel_l"), part("pel_r"))
    private val anal = Array(2) { part("an$it") }
    private val lobe1 = arrayOf(part("tu1"), part("tl1"))
    private val lobe2 = arrayOf(part("tu2"), part("tl2"))

    override fun setupAnim(state: GnashfinRenderState) {
        super.setupAnim(state)
        val t = state.time
        val swim = state.swim.coerceIn(0f, 1f)
        val bend = state.bend.coerceIn(-1f, 1f)
        val jawOpen = state.jaw.coerceIn(0f, 1f)
        val stranded = state.stranded.coerceIn(0f, 1f)
        val gnaw = state.gnaw.coerceIn(0f, 1f)
        val glow = state.glow.coerceIn(0f, 1f)
        val leaping = state.leap >= 0f
        val u = if (leaping) state.leap.coerceIn(0f, 1f) else 0f
        val arch = if (leaping) cos(PI.toFloat() * u) else 0f
        val spread = if (leaping) sin(PI.toFloat() * u).pow(0.7f) else 0f
        val flick = if (leaping) exp(-(((u - 0.5f) / 0.10f) * ((u - 0.5f) / 0.10f))) else 0f
        val tuck = smooth(0.35f, 0.95f, swim)
        val effort = (0.30f + 0.70f * swim) * (1f - stranded)
        val buck = sin(0.83f * t) * (0.6f + 0.4f * sin(0.31f * t))
        val shake = sin(0.62f * t)
        val slap = sin(1.05f * t)

        // The body: every segment yaws in the travelling wave (growing toward the tail), the bend curves it into turns, the arch pitches it in a leap,
        // a stranded fish bucks and slaps its tail, a gnawing one shakes its head
        for (i in 0 until 6) {
            var yaw = CHAIN_AMP[i] * effort * swimWave(t, swim, CHAIN_PHASE[i]) + CHAIN_BEND[i] * bend
            val pitch = CHAIN_ARCH[i] * arch + CHAIN_BUCK[i] * stranded * buck
            var roll = 0f
            when (i) {
                0 -> {
                    yaw += 0.34f * gnaw * shake
                    roll = 0.14f * gnaw * sin(0.62f * t + 1f)
                }
                1 -> yaw += 0.12f * gnaw * sin(0.62f * t - 0.6f)
                2 -> yaw += 0.04f * gnaw * sin(0.62f * t - 1.2f)
                4 -> yaw += 0.55f * stranded * slap + 0.45f * flick * sin(0.8f * t)
                5 -> yaw += 0.80f * stranded * sin(1.05f * t + 0.5f) + 0.60f * flick * sin(0.8f * t + 0.6f)
            }
            chain[i].xRot += pitch
            chain[i].yRot += yaw
            chain[i].zRot += roll
        }

        // The jaw gapes with `jaw` (less as it clamps down gnawing), trembles gnawing, snaps when stranded
        val snap = stranded * (0.15f + 0.80f * (0.5f + 0.5f * sin(0.75f * t + 0.5f)).pow(2))
        jaw.xRot += 0.95f * jawOpen * (1f - 0.85f * gnaw) + 0.05f * gnaw * sin(0.9f * t) + snap
        fangTips[0].xRot += 0.10f * sin(0.3f * t)
        fangTips[1].xRot += 0.10f * sin(0.3f * t + 1f)

        // The gill covers pulse, flaring wide as it gasps stranded and as the jaw opens
        val gill = 0.26f * (0.5f + 0.5f * sin(0.21f * t)) + 0.35f * stranded * (0.5f + 0.5f * sin(0.8f * t)) + 0.15f * jawOpen
        gills[0].yRot += gill
        gills[1].yRot -= gill

        // The chin lure sways, streams back when it swims hard, and its bead pulses
        lure.xRot += 0.35f * sin(0.14f * t) + 0.5f * swim
        lure.zRot += 0.25f * sin(0.11f * t + 1f)
        val bp = 1f + 0.22f * sin(0.31f * t) * (0.3f + 0.7f * glow)
        bead.xRot += 0.4f * sin(0.2f * t + 1f)
        bead.xScale = bp
        bead.yScale = bp
        bead.zScale = bp

        // The flank lights pulse in a wave along each side
        for (n in 0 until 10) {
            val side = n / 5
            val k = n % 5
            val pl = 0.5f + 0.5f * sin(0.19f * t - 0.9f * k - 2.5f * side)
            val sc = 1f + 0.28f * pl * (0.3f + 0.7f * glow)
            lights[n].xScale = sc
            lights[n].yScale = sc
            lights[n].zScale = sc
        }

        // The dorsal fin: slat by slat it sways in a wave, folds back as it swims hard, stands as it leaps, thrashes when stranded
        for (k in 0 until DORSAL) {
            dorsal[k].zRot += 0.14f * sin(0.27f * t - 0.7f * k + 0.4f) * (1f + 0.5f * swim) + 0.40f * stranded * sin(0.9f * t + 0.8f * k)
            dorsal[k].xRot += -0.25f * swim + 0.10f * spread + 0.07f * sin(0.31f * t - 0.5f * k)
        }

        // The pectorals: sculling at rest, swept back flat along the flank when it swims hard, thrown wide in a leap, flailing when stranded
        for (side in 0..1) {
            val sgn = if (side == 0) 1f else -1f
            val flutter = 0.18f * (1f - tuck) * sin(0.23f * t + 0.5f * side) + 0.5f * stranded * sin(0.95f * t + 1.3f * side)
            val sweep = 0.75f * tuck - 0.40f * spread
            pec1[side].yRot += -sgn * sweep
            pec1[side].zRot += sgn * flutter - sgn * 0.5f * spread
            pec2[side].zRot += sgn * 0.3f * (1f - tuck) * sin(0.23f * t - 0.9f + side) + sgn * 0.3f * stranded * sin(0.95f * t - 1f + side)
            pelvic[side].zRot += sgn * 0.15f * sin(0.29f * t + side)
        }
        for (k in 0..1) {
            anal[k].xRot += 0.08f * sin(0.25f * t - 0.7f * k + 2f)
            anal[k].zRot += 0.12f * sin(0.25f * t - 0.7f * k + 1f)
        }

        // The tail's lobes ripple, spread with the flick at the top of a leap and thrash when stranded
        for (n in 0..1) {
            val sgn = if (n == 0) 1f else -1f
            val ph = if (n == 0) 0f else 0.8f
            lobe1[n].xRot += sgn * (0.10f * sin(0.37f * t - 1f + ph) + 0.35f * flick + 0.12f * stranded * slap)
            lobe2[n].xRot += sgn * 0.18f * sin(0.37f * t - 1.9f + ph)
        }
    }

    /** The travelling wave at time [t] for swimming effort [s], with phase lag [ph]: three fixed beats (idle, cruise, sprint) crossfaded by [s]. */
    private fun swimWave(t: Float, s: Float, ph: Float): Float {
        val w0 = 1f - smooth(0f, 0.5f, s)
        val w2 = smooth(0.5f, 1f, s)
        val w1 = 1f - w0 - w2
        return w0 * sin(FREQ0 * t - ph) + w1 * sin(FREQ1 * t - ph) + w2 * sin(FREQ2 * t - ph)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "gnashfin"), "main")

        // The beats of the swimming wave (rad per tick): idle, cruise, sprint
        private const val FREQ0 = 0.17f
        private const val FREQ1 = 0.31f
        private const val FREQ2 = 0.52f

        // The body chain from the head to the tail: each segment's part, the phase its wave lags by, the wave's amplitude (rad), the yaw a full bend and the pitch a full arch and a full buck give it
        private val CHAIN_NAMES = arrayOf("skull", "fore", "mid", "aft", "stalk", "tail")
        private val CHAIN_PHASE = floatArrayOf(-0.9f, -0.45f, 0f, 0.55f, 1.15f, 1.8f)
        private val CHAIN_AMP = floatArrayOf(0.045f, 0.05f, 0.03f, 0.11f, 0.2f, 0.28f)
        private val CHAIN_BEND = floatArrayOf(0.3f, 0.16f, 0f, -0.16f, -0.2f, -0.16f)
        private val CHAIN_ARCH = floatArrayOf(-0.5f, -0.2f, 0f, 0.14f, 0.22f, 0.24f)
        private val CHAIN_BUCK = floatArrayOf(0.2f, 0.3f, 0f, -0.4f, -0.35f, -0.25f)

        // The slats of the dorsal fin
        private const val DORSAL = 11

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            buildFish1(root)
            return LayerDefinition.create(mesh, 128, 64)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildFish1(p: PartDefinition) {
            val mid = p.addOrReplaceChild("mid", CubeListBuilder.create().texOffs(70, 0).addBox(-2.75f, -2.75f, -4.5f, 5.5f, 5.5f, 9f, FACES_TBRL).texOffs(47, 0).addBox(-2.25f, -3.5f, -4.5f, 4.5f, 7f, 9f, FACES_TBRL), PartPose.offset(0f, 0f, 0f))
            mid.addOrReplaceChild("dn0", CubeListBuilder.create().texOffs(79, 8).addBox(-0.25f, -5f, -0.75f, 0.5f, 5f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.35f, -8.25f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn1", CubeListBuilder.create().texOffs(4, 22).addBox(-0.25f, -9f, -0.75f, 0.5f, 9f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.35f, -6.75f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn2", CubeListBuilder.create().texOffs(118, 17).addBox(-0.25f, -13f, -0.75f, 0.5f, 13f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.35f, -5.25f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn3", CubeListBuilder.create().texOffs(106, 12).addBox(-0.25f, -15f, -0.75f, 0.5f, 15f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.329f, -3.75f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn4", CubeListBuilder.create().texOffs(0, 15).addBox(-0.25f, -14f, -0.75f, 0.5f, 14f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.288f, -2.25f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn5", CubeListBuilder.create().texOffs(122, 17).addBox(-0.25f, -12.5f, -0.75f, 0.5f, 12.5f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.246f, -0.75f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn6", CubeListBuilder.create().texOffs(61, 18).addBox(-0.25f, -11f, -0.75f, 0.5f, 11f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.204f, 0.75f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn7", CubeListBuilder.create().texOffs(8, 22).addBox(-0.25f, -9f, -0.75f, 0.5f, 9f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.163f, 2.25f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn8", CubeListBuilder.create().texOffs(23, 22).addBox(-0.25f, -7.5f, -0.75f, 0.5f, 7.5f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.121f, 3.75f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn9", CubeListBuilder.create().texOffs(57, 24).addBox(-0.25f, -6f, -0.75f, 0.5f, 6f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -3.006f, 5.25f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("dn10", CubeListBuilder.create().texOffs(110, 17).addBox(-0.25f, -4f, -0.75f, 0.5f, 4f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, -2.819f, 6.75f, -0.3f, 0f, 0f))
            mid.addOrReplaceChild("pel_l", CubeListBuilder.create().texOffs(118, 0).addBox(0f, -0.25f, -1.2f, 3.5f, 0.5f, 3f, FACES_TB), PartPose.offsetAndRotation(1.4f, 3.3f, 1f, 0f, -0.401f, 0.908f))
            mid.addOrReplaceChild("pel_r", CubeListBuilder.create().texOffs(72, 28).addBox(-3.5f, -0.25f, -1.2f, 3.5f, 0.5f, 3f, FACES_TB), PartPose.offsetAndRotation(-1.4f, 3.3f, 1f, 0f, 0.401f, -0.908f))
            mid.addOrReplaceChild("lt1", CubeListBuilder.create().texOffs(65, 24).addBox(0f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(2.5f, 0.8f, -1.5f))
            mid.addOrReplaceChild("lt2", CubeListBuilder.create().texOffs(45, 29).addBox(0f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(2.5f, 0.8f, 2.5f))
            mid.addOrReplaceChild("lt6", CubeListBuilder.create().texOffs(0, 31).addBox(-1f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(-2.5f, 0.8f, -1.5f))
            mid.addOrReplaceChild("lt7", CubeListBuilder.create().texOffs(10, 31).addBox(-1f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(-2.5f, 0.8f, 2.5f))
            val fore = mid.addOrReplaceChild("fore", CubeListBuilder.create().texOffs(23, 0).addBox(-3f, -3f, -9f, 6f, 6f, 9f, FACES_TBRFL).texOffs(0, 0).addBox(-2.5f, -3.75f, -9f, 5f, 7.5f, 9f, FACES_TBRFL).texOffs(67, 15).addBox(-2.25f, -3f, -1.5f, 4.5f, 6f, 3f).texOffs(12, 27).addBox(-1.25f, -4.15f, -7.25f, 2.5f, 1f, 2.5f).texOffs(27, 27).addBox(-1.25f, -4.15f, -3.75f, 2.5f, 1f, 2.5f), PartPose.offset(0f, 0f, -4.5f))
            val pec_l1 = fore.addOrReplaceChild("pec_l1", CubeListBuilder.create().texOffs(60, 0).addBox(0f, -0.25f, -2f, 5.5f, 0.5f, 5f, FACES_TB), PartPose.offsetAndRotation(2.8f, 1.5f, -5f, 0f, -0.5f, 0.35f))
            pec_l1.addOrReplaceChild("pec_l2", CubeListBuilder.create().texOffs(62, 5).addBox(0f, -0.25f, -1.4f, 4.5f, 0.5f, 3.5f, FACES_TB), PartPose.offset(5.5f, 0f, 0f))
            val pec_r1 = fore.addOrReplaceChild("pec_r1", CubeListBuilder.create().texOffs(85, 0).addBox(-5.5f, -0.25f, -2f, 5.5f, 0.5f, 5f, FACES_TB), PartPose.offsetAndRotation(-2.8f, 1.5f, -5f, 0f, 0.5f, -0.35f))
            pec_r1.addOrReplaceChild("pec_r2", CubeListBuilder.create().texOffs(9, 17).addBox(-4.5f, -0.25f, -1.4f, 4.5f, 0.5f, 3.5f, FACES_TB), PartPose.offset(-5.5f, 0f, 0f))
            fore.addOrReplaceChild("lt0", CubeListBuilder.create().texOffs(39, 15).addBox(0f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(2.75f, 0.8f, -4.5f))
            fore.addOrReplaceChild("lt5", CubeListBuilder.create().texOffs(112, 30).addBox(-1f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(-2.75f, 0.8f, -4.5f))
            val skull = fore.addOrReplaceChild("skull", CubeListBuilder.create().texOffs(88, 13).addBox(-3f, -2.25f, -6f, 6f, 4.5f, 6f, FACES_TBRFL).texOffs(111, 7).addBox(-2.5f, -2.75f, -6f, 5f, 5.5f, 6f, FACES_TBRFL).texOffs(44, 0).addBox(-2.25f, -1.75f, -9f, 4.5f, 3.5f, 3f, FACES_TBRFL).texOffs(19, 0).addBox(-2f, -2f, -9f, 4f, 4f, 3f, FACES_TBRFL).texOffs(0, 0).addBox(-1.5f, -1.25f, -12f, 3f, 2.5f, 3f, FACES_TBRFL).texOffs(4, 17).addBox(-1.25f, -1.5f, -12f, 2.5f, 3f, 3f, FACES_TBRFL).texOffs(109, 0).addBox(-2.25f, -2f, -1.5f, 4.5f, 4f, 3f).texOffs(90, 5).addBox(-1.5f, -3.15f, -2.5f, 3f, 1f, 2f).texOffs(108, 27).addBox(-1.5f, -3.15f, -4.5f, 3f, 1f, 2f).texOffs(65, 28).addBox(-1.5f, -3.15f, -6.5f, 3f, 1f, 2f).texOffs(93, 28).addBox(-1f, -2.4f, -8.5f, 2f, 0.5f, 2.5f).texOffs(28, 6).addBox(2.75f, -1.5f, -5.5f, 0.5f, 1.5f, 1.5f).texOffs(23, 7).addBox(1.65f, 1.75f, -6.75f, 0.5f, 1.5f, 0.5f).texOffs(25, 7).addBox(1.65f, 1.75f, -8.25f, 0.5f, 1.5f, 0.5f).texOffs(44, 7).addBox(0.95f, 1.25f, -9.75f, 0.5f, 1.5f, 0.5f).texOffs(46, 7).addBox(0.95f, 1.25f, -11.25f, 0.5f, 1.5f, 0.5f).texOffs(48, 18).addBox(-3.25f, -1.5f, -5.5f, 0.5f, 1.5f, 1.5f).texOffs(48, 7).addBox(-2.15f, 1.75f, -6.75f, 0.5f, 1.5f, 0.5f).texOffs(50, 7).addBox(-2.15f, 1.75f, -8.25f, 0.5f, 1.5f, 0.5f).texOffs(52, 7).addBox(-1.45f, 1.25f, -9.75f, 0.5f, 1.5f, 0.5f).texOffs(56, 9).addBox(-1.45f, 1.25f, -11.25f, 0.5f, 1.5f, 0.5f), PartPose.offset(0f, -0.4f, -9f))
            val jaw = skull.addOrReplaceChild("jaw", CubeListBuilder.create().texOffs(71, 15).addBox(-1.5f, 0f, -11f, 3f, 2f, 11f, FACES_TBRFL).texOffs(44, 0).addBox(1f, -1.5f, -2.25f, 0.5f, 1.5f, 0.5f).texOffs(76, 0).addBox(1f, -1.5f, -3.75f, 0.5f, 1.5f, 0.5f).texOffs(109, 0).addBox(1f, -1.5f, -5.25f, 0.5f, 1.5f, 0.5f).texOffs(76, 2).addBox(1f, -1.5f, -6.75f, 0.5f, 1.5f, 0.5f).texOffs(90, 5).addBox(1f, -1.5f, -8.25f, 0.5f, 1.5f, 0.5f).texOffs(98, 5).addBox(-1.5f, -1.5f, -2.25f, 0.5f, 1.5f, 0.5f).texOffs(100, 5).addBox(-1.5f, -1.5f, -3.75f, 0.5f, 1.5f, 0.5f).texOffs(54, 6).addBox(-1.5f, -1.5f, -5.25f, 0.5f, 1.5f, 0.5f).texOffs(19, 7).addBox(-1.5f, -1.5f, -6.75f, 0.5f, 1.5f, 0.5f).texOffs(21, 7).addBox(-1.5f, -1.5f, -8.25f, 0.5f, 1.5f, 0.5f), PartPose.offset(0f, 2.75f, -4f))
            val fang_l1 = jaw.addOrReplaceChild("fang_l1", CubeListBuilder.create().texOffs(124, 3).addBox(-0.5f, -2.5f, -0.5f, 1f, 2.5f, 1f), PartPose.offsetAndRotation(1f, 0f, -10f, 0.003f, 0f, 0f))
            fang_l1.addOrReplaceChild("fang_l2", CubeListBuilder.create().texOffs(0, 0).addBox(-0.25f, -2f, -0.25f, 0.5f, 2f, 0.5f), PartPose.offsetAndRotation(0f, -2.5f, 0f, -0.007f, 0f, 0f))
            val fang_r1 = jaw.addOrReplaceChild("fang_r1", CubeListBuilder.create().texOffs(75, 4).addBox(-0.5f, -2.5f, -0.5f, 1f, 2.5f, 1f), PartPose.offsetAndRotation(-1f, 0f, -10f, 0.003f, 0f, 0f))
            fang_r1.addOrReplaceChild("fang_r2", CubeListBuilder.create().texOffs(19, 0).addBox(-0.25f, -2f, -0.25f, 0.5f, 2f, 0.5f), PartPose.offsetAndRotation(0f, -2.5f, 0f, -0.007f, 0f, 0f))
            val lure = jaw.addOrReplaceChild("lure", CubeListBuilder.create().texOffs(30, 0).addBox(-0.25f, 0f, -0.25f, 0.5f, 4f, 0.5f), PartPose.offset(0f, 2f, -3f))
            lure.addOrReplaceChild("bead", CubeListBuilder.create().texOffs(96, 24).addBox(-1f, -1f, -1f, 2f, 2f, 2f), PartPose.offset(0f, 4f, 0f))
            skull.addOrReplaceChild("gill_l", CubeListBuilder.create().texOffs(53, 17).addBox(0f, -2.25f, 0f, 0.5f, 4.5f, 3.5f), PartPose.offset(3f, 0f, -3.5f))
            skull.addOrReplaceChild("gill_r", CubeListBuilder.create().texOffs(110, 19).addBox(-0.5f, -2.25f, 0f, 0.5f, 4.5f, 3.5f), PartPose.offset(-3f, 0f, -3.5f))
            val aft = mid.addOrReplaceChild("aft", CubeListBuilder.create().texOffs(15, 15).addBox(-2f, -2f, 0f, 4f, 4f, 8f, FACES_TBRLK).texOffs(94, 0).addBox(-1.75f, -2.5f, 0f, 3.5f, 5f, 8f, FACES_TBRLK).texOffs(31, 15).addBox(-1.25f, -1.75f, -1.5f, 2.5f, 3.5f, 3f).texOffs(37, 25).addBox(-1.25f, -2.9f, 0.75f, 2.5f, 1f, 2.5f).texOffs(47, 25).addBox(-1.25f, -2.9f, 4.25f, 2.5f, 1f, 2.5f), PartPose.offset(0f, 0f, 4.5f))
            aft.addOrReplaceChild("an0", CubeListBuilder.create().texOffs(102, 7).addBox(-0.25f, 0f, -0.75f, 0.5f, 4f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, 2.2f, 1.5f, 0.3f, 0f, 0f))
            aft.addOrReplaceChild("an1", CubeListBuilder.create().texOffs(45, 23).addBox(-0.25f, 0f, -0.75f, 0.5f, 3f, 1.5f, FACES_RL), PartPose.offsetAndRotation(0f, 2.2f, 3.5f, 0.3f, 0f, 0f))
            aft.addOrReplaceChild("lt3", CubeListBuilder.create().texOffs(50, 29).addBox(0f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(1.75f, 0.8f, 1.5f))
            aft.addOrReplaceChild("lt4", CubeListBuilder.create().texOffs(107, 30).addBox(0f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(1.75f, 0.8f, 5.5f))
            aft.addOrReplaceChild("lt8", CubeListBuilder.create().texOffs(15, 31).addBox(-1f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(-1.75f, 0.8f, 1.5f))
            aft.addOrReplaceChild("lt9", CubeListBuilder.create().texOffs(20, 31).addBox(-1f, -0.75f, -0.75f, 1f, 1.5f, 1.5f), PartPose.offset(-1.75f, 0.8f, 5.5f))
            val stalk = aft.addOrReplaceChild("stalk", CubeListBuilder.create().texOffs(51, 11).addBox(-1f, -1.25f, 0f, 2f, 2.5f, 5.5f, FACES_TBRLK).texOffs(39, 16).addBox(-0.75f, -1.5f, 0f, 1.5f, 3f, 5.5f, FACES_TBRLK).texOffs(101, 26).addBox(-0.25f, -0.75f, -1.5f, 0.5f, 1.5f, 3f).texOffs(25, 31).addBox(-0.75f, -1.9f, 1.5f, 1.5f, 0.5f, 2f), PartPose.offset(0f, 0f, 8f))
            val tail = stalk.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(88, 15).addBox(-0.5f, -0.75f, -1f, 1f, 1.5f, 2f), PartPose.offset(0f, 0f, 5.5f))
            val tu1 = tail.addOrReplaceChild("tu1", CubeListBuilder.create().texOffs(37, 25).addBox(-0.25f, -1.5f, 0f, 0.5f, 3f, 3.5f, FACES_RL), PartPose.offsetAndRotation(0f, 0f, 0f, 0.5f, 0f, 0f))
            tu1.addOrReplaceChild("tu2", CubeListBuilder.create().texOffs(89, 25).addBox(-0.25f, -1.25f, 0f, 0.5f, 2.5f, 3f, FACES_RL), PartPose.offset(0f, 0f, 3.5f))
            val tl1 = tail.addOrReplaceChild("tl1", CubeListBuilder.create().texOffs(0, 2).addBox(-0.25f, -1.5f, 0f, 0.5f, 3f, 3.5f, FACES_RL), PartPose.offsetAndRotation(0f, 0f, 0f, -0.5f, 0f, 0f))
            tl1.addOrReplaceChild("tl2", CubeListBuilder.create().texOffs(82, 25).addBox(-0.25f, -1.25f, 0f, 0.5f, 2.5f, 3f, FACES_RL), PartPose.offset(0f, 0f, 3.5f))
        }

        private val FACES_RL: Set<Direction> = EnumSet.of(Direction.WEST, Direction.EAST)
        private val FACES_TB: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP)
        private val FACES_TBRFL: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST)
        private val FACES_TBRL: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST)
        private val FACES_TBRLK: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST, Direction.SOUTH)
    }
}
