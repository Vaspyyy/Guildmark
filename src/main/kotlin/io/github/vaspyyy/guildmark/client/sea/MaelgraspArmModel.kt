package io.github.vaspyyy.guildmark.client.sea

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.sea.Tentacle
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
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One of the Maelgrasp's arms, drawn from the thirteen joints [MaelgraspArmRenderState.joints] holds (the arm's frame, blocks;
 * [Tentacle] works them out). It is twelve segments, each a part of its own at the root of the model: segment i sits on joint i, is
 * turned to run along the segment to joint i + 1 and is stretched to the distance between them (it tapers from the radius [Tentacle.RADII]
 * gives at one joint to the radius at the next), and the turn is carried from segment to segment so the suckers' side never flips: it
 * starts toward the ship at the root (arm +z) and is turned the shortest way at every joint. Models are in pixels with y down and
 * the creature facing -z, so a joint is at -16 times its arm-frame coordinates; the model's origin is where the arm breaks the surface.
 *
 * Thick and muscular at the root, drowned slate-blue going to bruise-violet, crusted with barnacles and scarred along its outer side;
 * its underside is pale bone-ivory with two rows of suckers (bigger at the root, each ringed with small hooked teeth) that pulse on
 * their own beats and clench as it grips, a ragged frill along the outer edge of its last third that flutters (the harder in a rage), a
 * hooked tip that tightens its curl as it grips, and veins under the skin that glow (the glow map). Where it has been hacked through
 * ([MaelgraspArmRenderState.cut]: segments from there to the tip are hidden) the last one left ends in a raw ragged stump, and the length
 * hacked off that falls away alone ([MaelgraspArmRenderState.start]: segments before it are hidden) begins with the same stump facing
 * back along it; they are two parts, so both can be drawn.
 *
 * The geometry comes from art/scripts/sea_arm.py (edit it there: it writes this file's body and the textures, and its `pose()` is the
 * same animation in Python, run for the previews and checked against this class by sea_monsters.py). The texture is HD: the model
 * declares 384x256 and the PNGs are painted at twice that. It is drawn with [RenderTypes.entityCutout] (the frill is cut to shape in
 * the texture's alpha, and that type does not cull back faces); the glow layer is the same model drawn again with [RenderTypes.eyes]
 * over maelgrasp_arm_glow.png, tinted by [MaelgraspArmRenderState.glow] (it holds only the veins).
 */
class MaelgraspArmModel(root: ModelPart) : EntityModel<MaelgraspArmRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The twelve segments (each with its suckers, barnacles and, on the last third, its frill panels), the hook's three pieces, the two stumps
    private val segs = Array(SEGMENTS) { part("seg$it") }
    private val suckers = Array(SEGMENTS) { i -> Array(2 * SUCKERS[i]) { part("su${i}_$it") } }
    private val frills = Array(FRILLS) { part("fr$it") }
    private val hooks = Array(HOOKS) { part("hk${it + 1}") }
    private val stump = part("stump")
    private val stumpStart = part("stump_start")

    // Scratch for the frame carried along the arm (each segment's x, y and z axes) and each segment's length: nothing allocated per frame
    private val frames = FloatArray(SEGMENTS * 9)
    private val lens = FloatArray(SEGMENTS)

    override fun setupAnim(state: MaelgraspArmRenderState) {
        super.setupAnim(state)
        val t = state.time
        val grip = state.grip.coerceIn(0f, 1f)
        val rage = state.rage.coerceIn(0f, 1f)
        val j = state.joints
        // The frame carried along the arm: its +y runs joint to joint, its -z is the underside, which starts toward the ship
        var ux = 0f
        var uy = 0f
        var uz = -1f
        var px = 0f
        var py = -1f
        var pz = 0f
        for (i in 0 until SEGMENTS) {
            var dx = (j[i * 3 + 3] - j[i * 3]) * -16f
            var dy = (j[i * 3 + 4] - j[i * 3 + 1]) * -16f
            var dz = (j[i * 3 + 5] - j[i * 3 + 2]) * -16f
            val ln = sqrt(dx * dx + dy * dy + dz * dz)
            if (ln > 1.0e-4f) {
                dx /= ln
                dy /= ln
                dz /= ln
            } else {
                dx = px
                dy = py
                dz = pz
            }
            if (i > 0) {
                // Turn the underside by the turn that takes the last direction to this one (about their cross product)
                var ax = py * dz - pz * dy
                var ay = pz * dx - px * dz
                var az = px * dy - py * dx
                val s = sqrt(ax * ax + ay * ay + az * az)
                if (s > 1.0e-5f) {
                    val c = px * dx + py * dy + pz * dz
                    ax /= s
                    ay /= s
                    az /= s
                    val kx = ay * uz - az * uy
                    val ky = az * ux - ax * uz
                    val kz = ax * uy - ay * ux
                    val kd = ax * ux + ay * uy + az * uz
                    val nx = ux * c + kx * s + ax * kd * (1f - c)
                    val ny = uy * c + ky * s + ay * kd * (1f - c)
                    val nz = uz * c + kz * s + az * kd * (1f - c)
                    ux = nx
                    uy = ny
                    uz = nz
                }
            }
            var k = ux * dx + uy * dy + uz * dz
            ux -= dx * k
            uy -= dy * k
            uz -= dz * k
            var ul = sqrt(ux * ux + uy * uy + uz * uz)
            if (ul < 1.0e-4f) {
                // The underside was lost (it ran along the arm): any side will do
                if (abs(dx) < 0.9f) {
                    ux = 1f
                    uy = 0f
                    uz = 0f
                } else {
                    ux = 0f
                    uy = 1f
                    uz = 0f
                }
                k = ux * dx + uy * dy + uz * dz
                ux -= dx * k
                uy -= dy * k
                uz -= dz * k
                ul = sqrt(ux * ux + uy * uy + uz * uz)
            }
            ux /= ul
            uy /= ul
            uz /= ul
            // The part's axes: y along the segment, z away from the underside, x = y cross z
            val ezx = -ux
            val ezy = -uy
            val ezz = -uz
            val at = i * 9
            frames[at] = dy * ezz - dz * ezy
            frames[at + 1] = dz * ezx - dx * ezz
            frames[at + 2] = dx * ezy - dy * ezx
            frames[at + 3] = dx
            frames[at + 4] = dy
            frames[at + 5] = dz
            frames[at + 6] = ezx
            frames[at + 7] = ezy
            frames[at + 8] = ezz
            lens[i] = ln
            px = dx
            py = dy
            pz = dz
        }
        // The segments, each from its joint along its length: the first one drawn to the last, thicker as it grips and ripple-skinned
        val first = max(state.start, 0)
        val last = min(state.cut, SEGMENTS)
        for (i in 0 until SEGMENTS) {
            val at = i * 9
            val seg = segs[i]
            turn(seg, frames[at], frames[at + 1], frames[at + 2], frames[at + 3], frames[at + 4], frames[at + 5], frames[at + 6], frames[at + 7], frames[at + 8])
            val ripple = 0.018f * (1f + 1.3f * rage) * sin(t * 0.21f - 0.9f * i) + 0.07f * grip + 0.04f * rage
            seg.x = j[i * 3] * -16f
            seg.y = j[i * 3 + 1] * -16f
            seg.z = j[i * 3 + 2] * -16f
            seg.xScale = 1f + ripple
            seg.yScale = lens[i] / LEN[i]
            seg.zScale = 1f + ripple
            seg.visible = i >= first && i < last
        }
        // The suckers each pulse on a beat of their own, and clench (smaller mouths, standing higher) as the arm grips
        for (i in 0 until SEGMENTS) {
            val row = suckers[i]
            for (k in row.indices) {
                val ph = 0.9f * i + 2.1f * (k / 2) + (if (k % 2 == 1) 1.7f else 0f)
                val pulse = 0.5f + 0.5f * sin(t * 0.17f - ph)
                val mouth = 1f + 0.12f * pulse * (1f - grip) - 0.34f * grip
                row[k].xScale = mouth
                row[k].yScale = mouth
                row[k].zScale = 1f + 0.35f * pulse + grip
            }
        }
        // The frill streams and flutters, a wave running along it, the harder in a rage
        for (f in 0 until FRILLS) {
            val a = (0.34f + 0.30f * rage) * sin(t * 0.33f - 0.8f * f) + 0.16f * sin(t * 0.71f - 1.9f * f + 1f)
            val b = 0.14f * sin(t * 0.43f - 0.8f * f + 2f)
            frills[f].xRot = FRILL_REST + b
            frills[f].yRot = a
            frills[f].zScale = 1f + 0.22f * rage
        }
        // The hook curls toward the suckers, tighter as it grips
        for (k in 0 until HOOKS) {
            hooks[k].xRot = -(0.45f + 0.42f * grip + 0.05f * sin(t * 0.19f + k)) * (1f + 0.16f * k)
        }
        // The stumps: one on the end of what is left on the arm, one on the start of a length that has fallen away, each facing out of its cut
        val showEnd = last in 1 until SEGMENTS && last > first
        stump.visible = showEnd
        if (showEnd) cap(stump, j, last, last - 1, false)
        val showStart = first > 0 && first < last
        stumpStart.visible = showStart
        if (showStart) cap(stumpStart, j, first, first, true)
    }

    /** Puts a stump on joint [joint] with the frame of segment [seg] (turned about its x axis to face back along the arm when [back]). */
    private fun cap(p: ModelPart, j: FloatArray, joint: Int, seg: Int, back: Boolean) {
        val at = seg * 9
        val m = if (back) -1f else 1f
        turn(p, frames[at], frames[at + 1], frames[at + 2], m * frames[at + 3], m * frames[at + 4], m * frames[at + 5], m * frames[at + 6], m * frames[at + 7], m * frames[at + 8])
        p.x = j[joint * 3] * -16f
        p.y = j[joint * 3 + 1] * -16f
        p.z = j[joint * 3 + 2] * -16f
        val s = 16f * Tentacle.RADII[joint] / STUMP_R
        p.xScale = s
        p.yScale = s.pow(0.7f)
        p.zScale = s
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

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "maelgrasp_arm"), "main")

        private const val SEGMENTS = Tentacle.SEGMENTS
        private val LEN = FloatArray(SEGMENTS) { Tentacle.LENGTHS[it] * 16f }

        // Suckers in each of a segment's two rows; the frill's panels (two on each of the last four segments); the hook's pieces
        private val SUCKERS = intArrayOf(3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 1)
        private const val FRILLS = 8
        private const val HOOKS = 3
        private const val FRILL_REST = -0.401f

        // The radius (px) the stumps are built to: each is scaled to the radius of the joint it sits on
        private const val STUMP_R = 8f

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            buildSegments1(root)
            buildSegments2(root)
            buildCaps3(root)
            return LayerDefinition.create(mesh, 384, 256)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildSegments1(p: PartDefinition) {
            val seg0 = p.addOrReplaceChild("seg0", CubeListBuilder.create().texOffs(0, 0).addBox(-6.969f, 0f, -6.969f, 14f, 7.5f, 14f, FACES_TRFLK).texOffs(226, 9).addBox(-6.741f, 7.467f, -6.741f, 13.5f, 7.5f, 13.5f, FACES_RFLK).texOffs(72, 19).addBox(-6.512f, 14.933f, -6.512f, 13f, 7.5f, 13f, FACES_RFLK).texOffs(296, 8).addBox(-0.27f, 2.503f, 9.192f, 3f, 2.5f, 2f).texOffs(140, 0).addBox(-2.891f, 5.351f, 8.986f, 3.5f, 2.5f, 3f).texOffs(266, 0).addBox(5.624f, 8.812f, 6.056f, 3f, 2.5f, 2.5f).texOffs(333, 9).addBox(-7.683f, 13.927f, 6.619f, 3f, 1.5f, 2f).texOffs(47, 86).addBox(2.816f, 2.189f, 8.452f, 2.5f, 2f, 2f), PartPose.offsetAndRotation(0f, 38.4f, 0f, 0f, 0f, 3.142f))
            seg0.addOrReplaceChild("seg0_c1", CubeListBuilder.create().texOffs(56, 0).addBox(-6.969f, 0.1f, -6.969f, 14f, 7.5f, 14f, FACES_TRFLK).texOffs(280, 9).addBox(-6.741f, 7.567f, -6.741f, 13.5f, 7.5f, 13.5f, FACES_RFLK).texOffs(124, 19).addBox(-6.512f, 15.033f, -6.512f, 13f, 7.5f, 13f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            seg0.addOrReplaceChild("seg0_c2", CubeListBuilder.create().texOffs(112, 0).addBox(-6.969f, 0.2f, -6.969f, 14f, 7.5f, 14f, FACES_TRFLK).texOffs(226, 17).addBox(-6.741f, 7.667f, -6.741f, 13.5f, 7.5f, 13.5f, FACES_RFLK).texOffs(176, 25).addBox(-6.512f, 15.133f, -6.512f, 13f, 7.5f, 13f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg0.addOrReplaceChild("seg0_c3", CubeListBuilder.create().texOffs(168, 0).addBox(-6.969f, 0.3f, -6.969f, 14f, 7.5f, 14f, FACES_TRFLK).texOffs(280, 17).addBox(-6.741f, 7.767f, -6.741f, 13.5f, 7.5f, 13.5f, FACES_RFLK).texOffs(228, 25).addBox(-6.512f, 15.233f, -6.512f, 13f, 7.5f, 13f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            seg0.addOrReplaceChild("su0_0", CubeListBuilder.create().texOffs(353, 0).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(5.25f, 2.817f, -7.786f, 0f, -0.589f, 0f))
            seg0.addOrReplaceChild("su0_1", CubeListBuilder.create().texOffs(286, 0).addBox(-3f, -3f, -1f, 6f, 6f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-5.302f, 6.229f, -7.582f, 0f, 0.606f, 0f))
            seg0.addOrReplaceChild("su0_2", CubeListBuilder.create().texOffs(298, 0).addBox(-3f, -3f, -1f, 6f, 6f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(5.326f, 10.161f, -7.369f, 0f, -0.621f, 0f))
            seg0.addOrReplaceChild("su0_3", CubeListBuilder.create().texOffs(310, 0).addBox(-3f, -3f, -1f, 6f, 6f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-4.636f, 13.564f, -7.651f, 0f, 0.54f, 0f))
            seg0.addOrReplaceChild("su0_4", CubeListBuilder.create().texOffs(84, 0).addBox(-2.5f, -2.5f, -1f, 5f, 5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(4.92f, 17.767f, -7.268f, 0f, -0.59f, 0f))
            seg0.addOrReplaceChild("su0_5", CubeListBuilder.create().texOffs(364, 0).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-4.407f, 21.335f, -7.415f, 0f, 0.532f, 0f))
            val seg1 = p.addOrReplaceChild("seg1", CubeListBuilder.create().texOffs(224, 0).addBox(-6.284f, -3f, -6.284f, 12.5f, 10f, 12.5f, FACES_RFLK).texOffs(0, 31).addBox(-6.055f, 6.933f, -6.055f, 12f, 7f, 12f, FACES_RFLK).texOffs(0, 39).addBox(-5.827f, 13.867f, -5.827f, 11.5f, 7f, 11.5f, FACES_RFLK).texOffs(361, 83).addBox(5.93f, 17.068f, 4.371f, 2f, 2f, 3f).texOffs(350, 8).addBox(-9.769f, 9.321f, 3.055f, 3f, 1.5f, 3f).texOffs(372, 68).addBox(-5.637f, 8.944f, 6.876f, 2.5f, 2.5f, 2.5f).texOffs(350, 82).addBox(-9.185f, 6.104f, 3.855f, 2.5f, 2f, 3f).texOffs(309, 86).addBox(-8.171f, 14.756f, 4.259f, 2f, 1.5f, 3f), PartPose.offsetAndRotation(0f, 16f, 0f, 0f, 0f, 3.142f))
            seg1.addOrReplaceChild("seg1_c1", CubeListBuilder.create().texOffs(274, 0).addBox(-6.284f, -2.9f, -6.284f, 12.5f, 10f, 12.5f, FACES_RFLK).texOffs(174, 34).addBox(-6.055f, 7.033f, -6.055f, 12f, 7f, 12f, FACES_RFLK).texOffs(174, 42).addBox(-5.827f, 13.967f, -5.827f, 11.5f, 7f, 11.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            seg1.addOrReplaceChild("seg1_c2", CubeListBuilder.create().texOffs(324, 0).addBox(-6.284f, -2.8f, -6.284f, 12.5f, 10f, 12.5f, FACES_RFLK).texOffs(222, 34).addBox(-6.055f, 7.133f, -6.055f, 12f, 7f, 12f, FACES_RFLK).texOffs(220, 42).addBox(-5.827f, 14.067f, -5.827f, 11.5f, 7f, 11.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg1.addOrReplaceChild("seg1_c3", CubeListBuilder.create().texOffs(0, 9).addBox(-6.284f, -2.7f, -6.284f, 12.5f, 10f, 12.5f, FACES_RFLK).texOffs(48, 37).addBox(-6.055f, 7.233f, -6.055f, 12f, 7f, 12f, FACES_RFLK).texOffs(266, 43).addBox(-5.827f, 14.167f, -5.827f, 11.5f, 7f, 11.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            seg1.addOrReplaceChild("su1_0", CubeListBuilder.create().texOffs(163, 40).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(4.402f, 2.457f, -7.241f, 0f, -0.542f, 0f))
            seg1.addOrReplaceChild("su1_1", CubeListBuilder.create().texOffs(285, 47).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-4.847f, 5.649f, -6.786f, 0f, 0.615f, 0f))
            seg1.addOrReplaceChild("su1_2", CubeListBuilder.create().texOffs(73, 79).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(4.799f, 9.45f, -6.614f, 0f, -0.623f, 0f))
            seg1.addOrReplaceChild("su1_3", CubeListBuilder.create().texOffs(84, 79).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-4.203f, 13.029f, -6.816f, 0f, 0.548f, 0f))
            seg1.addOrReplaceChild("su1_4", CubeListBuilder.create().texOffs(95, 79).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(4.139f, 16.126f, -6.694f, 0f, -0.549f, 0f))
            seg1.addOrReplaceChild("su1_5", CubeListBuilder.create().texOffs(94, 0).addBox(-2.5f, -2.5f, -1f, 5f, 5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-4.148f, 19.858f, -6.495f, 0f, 0.564f, 0f))
            val seg2 = p.addOrReplaceChild("seg2", CubeListBuilder.create().texOffs(50, 11).addBox(-5.617f, -3f, -5.617f, 11f, 9.5f, 11f, FACES_RFLK).texOffs(80, 48).addBox(-5.427f, 6.4f, -5.427f, 11f, 6.5f, 11f, FACES_RFLK).texOffs(294, 54).addBox(-5.237f, 12.8f, -5.237f, 10.5f, 6.5f, 10.5f, FACES_RFLK).texOffs(158, 81).addBox(-6.512f, 12.601f, 5.048f, 2.5f, 2.5f, 3f).texOffs(271, 83).addBox(1.742f, 2.867f, 6.895f, 2f, 2.5f, 2.5f).texOffs(209, 83).addBox(5.259f, 7.343f, 4.156f, 2.5f, 2f, 3f).texOffs(362, 8).addBox(6.018f, 3.335f, 3.16f, 2.5f, 2.5f, 2f), PartPose.offsetAndRotation(0f, -4.8f, 0f, 0f, 0f, 3.142f))
            seg2.addOrReplaceChild("seg2_c1", CubeListBuilder.create().texOffs(94, 11).addBox(-5.617f, -2.9f, -5.617f, 11f, 9.5f, 11f, FACES_RFLK).texOffs(162, 50).addBox(-5.427f, 6.5f, -5.427f, 11f, 6.5f, 11f, FACES_RFLK).texOffs(34, 55).addBox(-5.237f, 12.9f, -5.237f, 10.5f, 6.5f, 10.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            seg2.addOrReplaceChild("seg2_c2", CubeListBuilder.create().texOffs(138, 11).addBox(-5.617f, -2.8f, -5.617f, 11f, 9.5f, 11f, FACES_RFLK).texOffs(206, 50).addBox(-5.427f, 6.6f, -5.427f, 11f, 6.5f, 11f, FACES_RFLK).texOffs(76, 55).addBox(-5.237f, 13f, -5.237f, 10.5f, 6.5f, 10.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg2.addOrReplaceChild("seg2_c3", CubeListBuilder.create().texOffs(182, 12).addBox(-5.617f, -2.7f, -5.617f, 11f, 9.5f, 11f, FACES_RFLK).texOffs(250, 51).addBox(-5.427f, 6.7f, -5.427f, 11f, 6.5f, 11f, FACES_RFLK).texOffs(118, 55).addBox(-5.237f, 13.1f, -5.237f, 10.5f, 6.5f, 10.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            seg2.addOrReplaceChild("su2_0", CubeListBuilder.create().texOffs(0, 66).addBox(-2.25f, -2.25f, -1f, 4.5f, 4.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(4.263f, 1.907f, -6.281f, 0f, -0.592f, 0f))
            seg2.addOrReplaceChild("su2_1", CubeListBuilder.create().texOffs(106, 79).addBox(-2.75f, -2.75f, -1f, 5.5f, 5.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.794f, 5.482f, -6.404f, 0f, 0.53f, 0f))
            seg2.addOrReplaceChild("su2_2", CubeListBuilder.create().texOffs(9, 66).addBox(-2.25f, -2.25f, -1f, 4.5f, 4.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.986f, 8.43f, -6.15f, 0f, -0.571f, 0f))
            seg2.addOrReplaceChild("su2_3", CubeListBuilder.create().texOffs(104, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.657f, 11.946f, -6.185f, 0f, 0.53f, 0f))
            seg2.addOrReplaceChild("su2_4", CubeListBuilder.create().texOffs(218, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.946f, 15.05f, -5.861f, 0f, -0.588f, 0f))
            seg2.addOrReplaceChild("su2_5", CubeListBuilder.create().texOffs(18, 66).addBox(-2.25f, -2.25f, -1f, 4.5f, 4.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.687f, 18.135f, -5.88f, 0f, 0.556f, 0f))
            val seg3 = p.addOrReplaceChild("seg3", CubeListBuilder.create().texOffs(334, 24).addBox(-5.046f, -3f, -5.046f, 10f, 9f, 10f, FACES_RFLK).texOffs(160, 58).addBox(-4.856f, 5.973f, -4.856f, 9.5f, 6f, 9.5f, FACES_RFLK).texOffs(0, 63).addBox(-4.665f, 11.947f, -4.665f, 9.5f, 6f, 9.5f, FACES_RFLK).texOffs(113, 86).addBox(5.217f, 8.965f, 2.672f, 2.5f, 1.5f, 2.5f).texOffs(153, 0).addBox(-8.087f, 2.171f, 3.36f, 3.5f, 2.5f, 2.5f).texOffs(283, 8).addBox(4.293f, 5.693f, 3.597f, 3.5f, 1.5f, 3f).texOffs(306, 8).addBox(-6.519f, 14.109f, 4.048f, 3f, 2.5f, 2f), PartPose.offsetAndRotation(0f, -24f, 0f, 0f, 0f, 3.142f))
            seg3.addOrReplaceChild("seg3_c1", CubeListBuilder.create().texOffs(280, 28).addBox(-5.046f, -2.9f, -5.046f, 10f, 9f, 10f, FACES_RFLK).texOffs(198, 58).addBox(-4.856f, 6.073f, -4.856f, 9.5f, 6f, 9.5f, FACES_RFLK).texOffs(38, 63).addBox(-4.665f, 12.047f, -4.665f, 9.5f, 6f, 9.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.393f, 0f))
            seg3.addOrReplaceChild("seg3_c2", CubeListBuilder.create().texOffs(72, 30).addBox(-5.046f, -2.8f, -5.046f, 10f, 9f, 10f, FACES_RFLK).texOffs(236, 59).addBox(-4.856f, 6.173f, -4.856f, 9.5f, 6f, 9.5f, FACES_RFLK).texOffs(76, 63).addBox(-4.665f, 12.147f, -4.665f, 9.5f, 6f, 9.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg3.addOrReplaceChild("seg3_c3", CubeListBuilder.create().texOffs(112, 30).addBox(-5.046f, -2.7f, -5.046f, 10f, 9f, 10f, FACES_RFLK).texOffs(274, 62).addBox(-4.856f, 6.273f, -4.856f, 9.5f, 6f, 9.5f, FACES_RFLK).texOffs(114, 63).addBox(-4.665f, 12.247f, -4.665f, 9.5f, 6f, 9.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.178f, 0f))
            seg3.addOrReplaceChild("su3_0", CubeListBuilder.create().texOffs(372, 61).addBox(-2.5f, -2.5f, -1f, 5f, 5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.467f, 1.869f, -5.869f, 0f, -0.529f, 0f))
            seg3.addOrReplaceChild("su3_1", CubeListBuilder.create().texOffs(312, 79).addBox(-2.25f, -2.25f, -1f, 4.5f, 4.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.725f, 5.198f, -5.542f, 0f, 0.587f, 0f))
            seg3.addOrReplaceChild("su3_2", CubeListBuilder.create().texOffs(226, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.711f, 8.038f, -5.405f, 0f, -0.597f, 0f))
            seg3.addOrReplaceChild("su3_3", CubeListBuilder.create().texOffs(234, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.515f, 10.767f, -5.394f, 0f, 0.573f, 0f))
            seg3.addOrReplaceChild("su3_4", CubeListBuilder.create().texOffs(209, 0).addBox(-1.75f, -1.75f, -1f, 3.5f, 3.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.438f, 14.272f, -5.266f, 0f, -0.574f, 0f))
            seg3.addOrReplaceChild("su3_5", CubeListBuilder.create().texOffs(242, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.46f, 17.224f, -5.1f, 0f, 0.591f, 0f))
            val seg4 = p.addOrReplaceChild("seg4", CubeListBuilder.create().texOffs(334, 14).addBox(-4.49f, -2.688f, -4.49f, 9f, 11f, 9f, FACES_RFLK).texOffs(46, 48).addBox(-4.259f, 8.32f, -4.259f, 8.5f, 8.5f, 8.5f, FACES_RFLK).texOffs(231, 83).addBox(3.576f, 7.024f, 3.165f, 3.5f, 1.5f, 2.5f).texOffs(165, 0).addBox(-4.444f, 11.215f, 4.909f, 3.5f, 2f, 3f).texOffs(319, 86).addBox(-5.625f, 8.747f, 3.771f, 2f, 2f, 2f), PartPose.offsetAndRotation(0f, -41.92f, 0f, 0f, 0f, 3.142f))
            seg4.addOrReplaceChild("seg4_c1", CubeListBuilder.create().texOffs(0, 23).addBox(-4.49f, -2.588f, -4.49f, 9f, 11f, 9f, FACES_RFLK).texOffs(128, 48).addBox(-4.259f, 8.42f, -4.259f, 8.5f, 8.5f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            seg4.addOrReplaceChild("seg4_c2", CubeListBuilder.create().texOffs(36, 23).addBox(-4.49f, -2.488f, -4.49f, 9f, 11f, 9f, FACES_RFLK).texOffs(0, 49).addBox(-4.259f, 8.52f, -4.259f, 8.5f, 8.5f, 8.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            seg4.addOrReplaceChild("su4_0", CubeListBuilder.create().texOffs(250, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.411f, 2.126f, -5.003f, 0f, -0.594f, 0f))
            seg4.addOrReplaceChild("su4_1", CubeListBuilder.create().texOffs(258, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.279f, 4.481f, -4.986f, 0f, 0.577f, 0f))
            seg4.addOrReplaceChild("su4_2", CubeListBuilder.create().texOffs(169, 81).addBox(-1.75f, -1.75f, -1f, 3.5f, 3.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.047f, 7.251f, -5.009f, 0f, -0.542f, 0f))
            seg4.addOrReplaceChild("su4_3", CubeListBuilder.create().texOffs(177, 81).addBox(-1.75f, -1.75f, -1f, 3.5f, 3.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.195f, 10.115f, -4.793f, 0f, 0.583f, 0f))
            seg4.addOrReplaceChild("su4_4", CubeListBuilder.create().texOffs(185, 81).addBox(-1.75f, -1.75f, -1f, 3.5f, 3.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(2.902f, 13.217f, -4.84f, 0f, -0.536f, 0f))
            seg4.addOrReplaceChild("su4_5", CubeListBuilder.create().texOffs(193, 81).addBox(-1.75f, -1.75f, -1f, 3.5f, 3.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-2.96f, 15.814f, -4.693f, 0f, 0.558f, 0f))
            val seg5 = p.addOrReplaceChild("seg5", CubeListBuilder.create().texOffs(342, 35).addBox(-4.029f, -2.419f, -4.029f, 8f, 10f, 8f, FACES_RFLK).texOffs(312, 64).addBox(-3.799f, 7.68f, -3.799f, 7.5f, 7.5f, 7.5f, FACES_RFLK).texOffs(56, 86).addBox(3.322f, 1.954f, 3.525f, 2f, 2.5f, 2f).texOffs(196, 0).addBox(3.339f, 8.412f, 2.375f, 3.5f, 2.5f, 3f).texOffs(220, 83).addBox(-5.529f, 1.463f, 4.026f, 3.5f, 2f, 2f), PartPose.offsetAndRotation(0f, -58.56f, 0f, 0f, 0f, 3.142f))
            seg5.addOrReplaceChild("seg5_c1", CubeListBuilder.create().texOffs(96, 41).addBox(-4.029f, -2.319f, -4.029f, 8f, 10f, 8f, FACES_RFLK).texOffs(152, 66).addBox(-3.799f, 7.78f, -3.799f, 7.5f, 7.5f, 7.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.524f, 0f))
            seg5.addOrReplaceChild("seg5_c2", CubeListBuilder.create().texOffs(342, 45).addBox(-4.029f, -2.219f, -4.029f, 8f, 10f, 8f, FACES_RFLK).texOffs(182, 66).addBox(-3.799f, 7.88f, -3.799f, 7.5f, 7.5f, 7.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 1.047f, 0f))
            seg5.addOrReplaceChild("su5_0", CubeListBuilder.create().texOffs(322, 0).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(3.041f, 1.9f, -4.516f, 0f, -0.588f, 0f))
            seg5.addOrReplaceChild("su5_1", CubeListBuilder.create().texOffs(48, 43).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-3.148f, 4.082f, -4.336f, 0f, 0.623f, 0f))
            seg5.addOrReplaceChild("su5_2", CubeListBuilder.create().texOffs(201, 81).addBox(-1.75f, -1.75f, -1f, 3.5f, 3.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(2.773f, 7.129f, -4.438f, 0f, -0.554f, 0f))
            seg5.addOrReplaceChild("su5_3", CubeListBuilder.create().texOffs(56, 43).addBox(-2f, -2f, -1f, 4f, 4f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-2.743f, 9.465f, -4.347f, 0f, 0.558f, 0f))
            seg5.addOrReplaceChild("su5_4", CubeListBuilder.create().texOffs(277, 0).addBox(-1.5f, -1.5f, -1f, 3f, 3f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(2.716f, 12.18f, -4.237f, 0f, -0.566f, 0f))
            seg5.addOrReplaceChild("su5_5", CubeListBuilder.create().texOffs(342, 82).addBox(-1.75f, -1.75f, -1f, 3.5f, 3.5f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-2.743f, 14.412f, -4.114f, 0f, 0.583f, 0f))
            val seg6 = p.addOrReplaceChild("seg6", CubeListBuilder.create().texOffs(312, 47).addBox(-3.647f, -2.15f, -3.647f, 7.5f, 9.5f, 7.5f, FACES_RFLK).texOffs(28, 0).addBox(-3.411f, 7.2f, -3.411f, 7f, 7f, 7f, FACES_RFLK).texOffs(0, 87).addBox(0.516f, 4.438f, 4.331f, 2f, 1.5f, 2f).texOffs(36, 86).addBox(-4.908f, 3.604f, 3.229f, 2.5f, 1.5f, 3f), PartPose.offsetAndRotation(0f, -73.92f, 0f, 0f, 0f, 3.142f))
            seg6.addOrReplaceChild("seg6_c1", CubeListBuilder.create().texOffs(342, 56).addBox(-3.647f, -2.05f, -3.647f, 7.5f, 9.5f, 7.5f, FACES_RFLK).texOffs(84, 0).addBox(-3.411f, 7.3f, -3.411f, 7f, 7f, 7f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg6.addOrReplaceChild("su6_0", CubeListBuilder.create().texOffs(375, 0).addBox(-1.5f, -1.5f, -1f, 3f, 3f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(2.669f, 1.581f, -4.036f, 0f, -0.58f, 0f))
            seg6.addOrReplaceChild("su6_1", CubeListBuilder.create().texOffs(27, 68).addBox(-1.25f, -1.25f, -0.5f, 2.5f, 2.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-2.779f, 3.976f, -3.838f, 0f, 0.622f, 0f))
            seg6.addOrReplaceChild("su6_2", CubeListBuilder.create().texOffs(64, 43).addBox(-1.5f, -1.5f, -1f, 3f, 3f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(2.689f, 6.636f, -3.763f, 0f, -0.616f, 0f))
            seg6.addOrReplaceChild("su6_3", CubeListBuilder.create().texOffs(243, 83).addBox(-1.5f, -1.5f, -1f, 3f, 3f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-2.617f, 8.765f, -3.703f, 0f, 0.611f, 0f))
            seg6.addOrReplaceChild("su6_4", CubeListBuilder.create().texOffs(250, 83).addBox(-1.5f, -1.5f, -1f, 3f, 3f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(2.428f, 11.172f, -3.706f, 0f, -0.575f, 0f))
            seg6.addOrReplaceChild("su6_5", CubeListBuilder.create().texOffs(257, 83).addBox(-1.5f, -1.5f, -1f, 3f, 3f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(-2.467f, 13.758f, -3.548f, 0f, 0.603f, 0f))
            val seg7 = p.addOrReplaceChild("seg7", CubeListBuilder.create().texOffs(342, 67).addBox(-3.176f, -1.882f, -3.176f, 6.5f, 8.5f, 6.5f, FACES_RFLK).texOffs(218, 0).addBox(-2.941f, 6.72f, -2.941f, 6f, 6.5f, 6f, FACES_RFLK).texOffs(64, 86).addBox(-4.528f, 7.171f, 2.462f, 2.5f, 2f, 2f), PartPose.offsetAndRotation(0f, -88.32f, 0f, 0f, 0f, 3.142f))
            seg7.addOrReplaceChild("seg7_c1", CubeListBuilder.create().texOffs(212, 68).addBox(-3.176f, -1.782f, -3.176f, 6.5f, 8.5f, 6.5f, FACES_RFLK).texOffs(242, 0).addBox(-2.941f, 6.82f, -2.941f, 6f, 6.5f, 6f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg7.addOrReplaceChild("su7_0", CubeListBuilder.create().texOffs(264, 83).addBox(-1.5f, -1.5f, -1f, 3f, 3f, 2f, FACES_TBRFL), PartPose.offsetAndRotation(2.235f, 1.759f, -3.57f, 0f, -0.555f, 0f))
            seg7.addOrReplaceChild("su7_1", CubeListBuilder.create().texOffs(327, 86).addBox(-1.25f, -1.25f, -0.5f, 2.5f, 2.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-2.263f, 3.647f, -3.45f, 0f, 0.576f, 0f))
            seg7.addOrReplaceChild("su7_2", CubeListBuilder.create().texOffs(333, 86).addBox(-1.25f, -1.25f, -0.5f, 2.5f, 2.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(2.239f, 6.266f, -3.323f, 0f, -0.588f, 0f))
            seg7.addOrReplaceChild("su7_3", CubeListBuilder.create().texOffs(371, 86).addBox(-1.25f, -1.25f, -0.5f, 2.5f, 2.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-2.091f, 8.494f, -3.298f, 0f, 0.56f, 0f))
            seg7.addOrReplaceChild("su7_4", CubeListBuilder.create().texOffs(377, 86).addBox(-1.25f, -1.25f, -0.5f, 2.5f, 2.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(2.118f, 10.615f, -3.166f, 0f, -0.585f, 0f))
            seg7.addOrReplaceChild("su7_5", CubeListBuilder.create().texOffs(120, 0).addBox(-1f, -1f, -0.5f, 2f, 2f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-2.17f, 13.005f, -2.998f, 0f, 0.622f, 0f))
            val seg8 = p.addOrReplaceChild("seg8", CubeListBuilder.create().texOffs(140, 0).addBox(-2.706f, -1.613f, -2.706f, 5.5f, 8f, 5.5f, FACES_RFLK).texOffs(162, 0).addBox(-2.47f, 6.24f, -2.47f, 5f, 6f, 5f, FACES_RFLK), PartPose.offsetAndRotation(0f, -101.76f, 0f, 0f, 0f, 3.142f))
            seg8.addOrReplaceChild("seg8_c1", CubeListBuilder.create().texOffs(196, 0).addBox(-2.706f, -1.513f, -2.706f, 5.5f, 8f, 5.5f, FACES_RFLK).texOffs(266, 0).addBox(-2.47f, 6.34f, -2.47f, 5f, 6f, 5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg8.addOrReplaceChild("su8_0", CubeListBuilder.create().texOffs(120, 4).addBox(-1f, -1f, -0.5f, 2f, 2f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(2.101f, 2.252f, -2.887f, 0f, -0.624f, 0f))
            seg8.addOrReplaceChild("su8_1", CubeListBuilder.create().texOffs(64, 6).addBox(-1f, -1f, -0.5f, 2f, 2f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-1.805f, 5.301f, -2.903f, 0f, 0.552f, 0f))
            seg8.addOrReplaceChild("su8_2", CubeListBuilder.create().texOffs(120, 8).addBox(-1f, -1f, -0.5f, 2f, 2f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(1.757f, 8.5f, -2.748f, 0f, -0.564f, 0f))
            seg8.addOrReplaceChild("su8_3", CubeListBuilder.create().texOffs(64, 10).addBox(-1f, -1f, -0.5f, 2f, 2f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-1.817f, 11.656f, -2.521f, 0f, 0.62f, 0f))
            seg8.addOrReplaceChild("fr0", CubeListBuilder.create().texOffs(0, 69).addBox(-0.25f, -4f, -0.6f, 0.5f, 8f, 9.5f, FACES_RL), PartPose.offsetAndRotation(0f, 3.12f, 3.312f, -0.401f, 0f, 0f))
            seg8.addOrReplaceChild("fr1", CubeListBuilder.create().texOffs(20, 70).addBox(-0.25f, -4f, -0.6f, 0.5f, 8f, 9f, FACES_RL), PartPose.offsetAndRotation(0f, 9.36f, 3.024f, -0.401f, 0f, 0f))
            val seg9 = p.addOrReplaceChild("seg9", CubeListBuilder.create().texOffs(368, 69).addBox(-2.117f, -1.344f, -2.117f, 4f, 13f, 4f, FACES_RFLK), PartPose.offsetAndRotation(0f, -114.24f, 0f, 0f, 0f, 3.142f))
            seg9.addOrReplaceChild("seg9_c1", CubeListBuilder.create().texOffs(282, 74).addBox(-2.117f, -1.244f, -2.117f, 4f, 13f, 4f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg9.addOrReplaceChild("su9_0", CubeListBuilder.create().texOffs(220, 33).addBox(-1f, -1f, -0.5f, 2f, 2f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(1.592f, 1.959f, -2.497f, 0f, -0.563f, 0f))
            seg9.addOrReplaceChild("su9_1", CubeListBuilder.create().texOffs(336, 64).addBox(-1f, -1f, -0.5f, 2f, 2f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-1.52f, 4.798f, -2.364f, 0f, 0.567f, 0f))
            seg9.addOrReplaceChild("su9_2", CubeListBuilder.create().texOffs(177, 11).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(1.412f, 8.015f, -2.23f, 0f, -0.56f, 0f))
            seg9.addOrReplaceChild("su9_3", CubeListBuilder.create().texOffs(163, 48).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-1.262f, 10.57f, -2.161f, 0f, 0.524f, 0f))
            seg9.addOrReplaceChild("fr2", CubeListBuilder.create().texOffs(39, 70).addBox(-0.25f, -3.75f, -0.6f, 0.5f, 7.5f, 8.5f, FACES_RL), PartPose.offsetAndRotation(0f, 2.88f, 2.736f, -0.401f, 0f, 0f))
            seg9.addOrReplaceChild("fr3", CubeListBuilder.create().texOffs(57, 71).addBox(-0.25f, -3.75f, -0.6f, 0.5f, 7.5f, 7.5f, FACES_RL), PartPose.offsetAndRotation(0f, 8.64f, 2.448f, -0.401f, 0f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildSegments2(p: PartDefinition) {
            val seg10 = p.addOrReplaceChild("seg10", CubeListBuilder.create().texOffs(370, 19).addBox(-1.647f, -1.075f, -1.647f, 3.5f, 11.5f, 3.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, -125.76f, 0f, 0f, 0f, 3.142f))
            seg10.addOrReplaceChild("seg10_c1", CubeListBuilder.create().texOffs(298, 74).addBox(-1.647f, -0.975f, -1.647f, 3.5f, 11.5f, 3.5f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg10.addOrReplaceChild("su10_0", CubeListBuilder.create().texOffs(9, 0).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(1.344f, 1.742f, -1.931f, 0f, -0.603f, 0f))
            seg10.addOrReplaceChild("su10_1", CubeListBuilder.create().texOffs(37, 0).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-1.225f, 4.546f, -1.815f, 0f, 0.589f, 0f))
            seg10.addOrReplaceChild("su10_2", CubeListBuilder.create().texOffs(175, 0).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(1.124f, 7.193f, -1.697f, 0f, -0.58f, 0f))
            seg10.addOrReplaceChild("su10_3", CubeListBuilder.create().texOffs(162, 11).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-0.979f, 9.51f, -1.629f, 0f, 0.537f, 0f))
            seg10.addOrReplaceChild("fr4", CubeListBuilder.create().texOffs(270, 40).addBox(-0.25f, -3.5f, -0.6f, 0.5f, 7f, 7f, FACES_RL), PartPose.offsetAndRotation(0f, 2.64f, 2.16f, -0.401f, 0f, 0f))
            seg10.addOrReplaceChild("fr5", CubeListBuilder.create().texOffs(0, 0).addBox(-0.25f, -3.5f, -0.6f, 0.5f, 7f, 6.5f, FACES_RL), PartPose.offsetAndRotation(0f, 7.92f, 1.872f, -0.401f, 0f, 0f))
            val seg11 = p.addOrReplaceChild("seg11", CubeListBuilder.create().texOffs(56, 0).addBox(-1.117f, -0.806f, -1.117f, 2f, 10.5f, 2f, FACES_BRFLK), PartPose.offsetAndRotation(0f, -136.32f, 0f, 0f, 0f, 3.142f))
            seg11.addOrReplaceChild("seg11_c1", CubeListBuilder.create().texOffs(112, 0).addBox(-1.117f, -0.706f, -1.117f, 2f, 10.5f, 2f, FACES_BRFLK), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0.785f, 0f))
            seg11.addOrReplaceChild("su11_0", CubeListBuilder.create().texOffs(167, 11).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(0.847f, 3.135f, -1.344f, 0f, -0.558f, 0f))
            seg11.addOrReplaceChild("su11_1", CubeListBuilder.create().texOffs(172, 11).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 1.5f, FACES_TBRFL), PartPose.offsetAndRotation(-0.628f, 7.82f, -1.04f, 0f, 0.539f, 0f))
            seg11.addOrReplaceChild("fr6", CubeListBuilder.create().texOffs(320, 0).addBox(-0.25f, -3.25f, -0.6f, 0.5f, 6.5f, 6f, FACES_RL), PartPose.offsetAndRotation(0f, 2.4f, 1.548f, -0.401f, 0f, 0f))
            seg11.addOrReplaceChild("fr7", CubeListBuilder.create().texOffs(34, 53).addBox(-0.25f, -3.25f, -0.6f, 0.5f, 6.5f, 5f, FACES_RL), PartPose.offsetAndRotation(0f, 7.2f, 1.188f, -0.401f, 0f, 0f))
            val hk1 = seg11.addOrReplaceChild("hk1", CubeListBuilder.create().texOffs(150, 81).addBox(-1f, 0f, -1f, 2f, 4.5f, 2f), PartPose.offsetAndRotation(0f, 9.2f, 0f, -0.454f, 0f, 0f))
            val hk2 = hk1.addOrReplaceChild("hk2", CubeListBuilder.create().texOffs(64, 0).addBox(-0.75f, 0f, -0.75f, 1.5f, 4f, 1.5f), PartPose.offsetAndRotation(0f, 4.5f, 0f, -0.454f, 0f, 0f))
            hk2.addOrReplaceChild("hk3", CubeListBuilder.create().texOffs(52, 0).addBox(-0.5f, 0f, -0.5f, 1f, 3.5f, 1f), PartPose.offsetAndRotation(0f, 4f, 0f, -0.454f, 0f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildCaps3(p: PartDefinition) {
            val stump = p.addOrReplaceChild("stump", CubeListBuilder.create().texOffs(298, 38).addBox(-5.587f, -1.5f, -5.587f, 11f, 5f, 11f, FACES_BRFLK).texOffs(0, 0).addBox(5.2f, 2f, -0.791f, 3f, 3.5f, 3f).texOffs(321, 79).addBox(2.989f, 2f, 3.874f, 2.5f, 3.5f, 3f).texOffs(117, 79).addBox(-0.984f, 2f, 4.919f, 3f, 4.5f, 2.5f).texOffs(374, 41).addBox(-4.22f, 2f, 3.108f, 2f, 4.5f, 2.5f).texOffs(333, 0).addBox(-6.881f, 2f, -2.036f, 3f, 6.5f, 2f).texOffs(374, 48).addBox(-6.669f, 2f, -4.93f, 2f, 4.5f, 2.5f).texOffs(128, 79).addBox(-1.838f, 2f, -7.543f, 2.5f, 4.5f, 3f).texOffs(374, 34).addBox(3.465f, 2f, -4.462f, 3f, 4.5f, 2f).texOffs(28, 0).addBox(-1.5f, 2.5f, -1.5f, 3f, 4f, 3f), PartPose.offset(0f, 0f, 0f))
            stump.addOrReplaceChild("stump_c1", CubeListBuilder.create().texOffs(176, 22).addBox(-5.587f, -1.5f, -5.587f, 11f, 5f, 11f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val stump_start = p.addOrReplaceChild("stump_start", CubeListBuilder.create().texOffs(130, 40).addBox(-5.587f, -1.5f, -5.587f, 11f, 5f, 11f, FACES_BRFLK).texOffs(374, 55).addBox(4.294f, 2f, -2.001f, 2f, 3.5f, 2.5f).texOffs(343, 0).addBox(2.968f, 2f, 2.236f, 2f, 6.5f, 3f).texOffs(332, 79).addBox(-0.323f, 2f, 3.609f, 2f, 3.5f, 3f).texOffs(162, 56).addBox(-5.723f, 2f, 4.034f, 3f, 2.5f, 2.5f).texOffs(331, 43).addBox(-7.972f, 2f, -2.127f, 2.5f, 3.5f, 2.5f).texOffs(374, 13).addBox(-5.725f, 2f, -4.621f, 2.5f, 5.5f, 2.5f).texOffs(139, 79).addBox(-1.548f, 2f, -8.201f, 2.5f, 4.5f, 3f).texOffs(371, 5).addBox(1.998f, 2f, -5.838f, 3f, 4.5f, 3f).texOffs(40, 0).addBox(-1.5f, 2.5f, -1.5f, 3f, 4f, 3f), PartPose.offset(0f, 0f, 0f))
            stump_start.addOrReplaceChild("stump_start_c1", CubeListBuilder.create().texOffs(238, 67).addBox(-5.587f, -1.5f, -5.587f, 11f, 5f, 11f, FACES_RFLK), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
        }

        private val FACES_BRFLK: Set<Direction> = EnumSet.of(Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)
        private val FACES_RFLK: Set<Direction> = EnumSet.of(Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)
        private val FACES_RL: Set<Direction> = EnumSet.of(Direction.WEST, Direction.EAST)
        private val FACES_TBRFL: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST)
        private val FACES_TRFLK: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)
    }
}
