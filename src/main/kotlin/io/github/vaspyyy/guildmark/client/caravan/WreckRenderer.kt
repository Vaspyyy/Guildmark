package io.github.vaspyyy.guildmark.client.caravan

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.caravan.LootPile
import io.github.vaspyyy.guildmark.caravan.Wreck
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import kotlin.math.exp

/**
 * What this client keeps of one wreck: when it first saw it and whether it had only just gone over then (its
 * wheels in the air spin on, running down), and the ground under each of its lots and under the dead horse.
 */
internal class WreckLook(val born: Int, val fresh: Boolean) {
    val ground = DoubleArray(PLACES + 1) { Double.NaN }
    var probedAt = Int.MIN_VALUE

    /** Degrees the ground under the hull slopes: its nose up, its right side up. */
    var pitch = 0f
    var roll = 0f

    companion object {
        /** WagonMesh.WRECK_LOTS' places (lots 8 to 11 heaped on 0 to 3); the dead horse's ground is the last. */
        const val PLACES = 8
    }
}

/** What a wreck's renderer needs to draw it. */
class WreckRenderState : EntityRenderState() {
    /** Where it lies (the state's own x, y and z are moved under its middle, for its shadow) and which way the wagon headed. */
    var originX = 0.0
    var originY = 0.0
    var originZ = 0.0
    var yaw = 0f

    /** Degrees the hull lies tilted with the ground under it: its nose up, its right side up. */
    var pitch = 0f
    var roll = 0f

    /** Over onto its left side; the band's arrows in its boards (bits 0 to 5); radians its wheels in the air have turned. */
    var mirror = false
    var arrows = 0
    var spin = 0f

    /** The lots still there: each one's part (WagonDraw.PARTS), where it lies (px, the wreck's space) and its turn (degrees). */
    var lots = 0
    val kind = IntArray(Wreck.LOTS)
    val lx = FloatArray(Wreck.LOTS)
    val ly = FloatArray(Wreck.LOTS)
    val lz = FloatArray(Wreck.LOTS)
    val turn = FloatArray(Wreck.LOTS)

    /** The horse lying dead in its cut harness by it, if it was cut down: how it's posed, where (px) and its turn (degrees). */
    var horse: HorseRenderState? = null
    var horseX = 0f
    var horseY = 0f
    var horseZ = 0f
    var horseTurn = 0f
}

/** The wreck (WagonMesh.wreck()): the band's arrows standing in it as they were shot, its wheels in the air spinning on. */
class WreckModel(root: ModelPart) : EntityModel<WreckRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()
    private fun part(name: String): ModelPart = find.apply(name) ?: error("No part $name")
    private val arrows = (0 until 6).map { part("w_arrow$it") }
    private val back = part("w_wheel_rl")
    private val front = part("w_wheel_fl")

    override fun setupAnim(state: WreckRenderState) {
        super.setupAnim(state)
        for ((k, arrow) in arrows.withIndex()) arrow.visible = state.arrows and (1 shl k) != 0
        back.xRot = state.spin
        front.xRot = state.spin * FRONT_SPIN
    }

    companion object {
        /** The front wheel is smaller: it turns faster for the same push. */
        private const val FRONT_SPIN = WagonMesh.REAR_RADIUS / WagonMesh.FRONT_RADIUS
    }
}

/**
 * A waylaid wagon's wreck by the road: the hull over on its side (to its left, mirrored, if it went that way), a
 * wheel off, the shafts torn away, the lantern out in the grass, the band's arrows in its boards, what it spilled
 * lying round it on the ground as it lies (broken open, gone as each lot is taken), and the horse dead by it if it was
 * cut down. Just gone over, its wheels in the air spin on a while.
 */
class WreckRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Wreck, WreckRenderState>(context) {
    private val model = WreckModel(context.bakeLayer(WagonModel.WRECK))
    private val parts: List<ModelPart> = context.bakeLayer(WagonModel.LOAD).let { load -> WagonDraw.PARTS.map { load.getChild(it) } }
    private val horse = HorseModel(context.bakeLayer(HorseModel.LAYER))
    private val pose = WagonPose()
    private val at = Vector3d()

    init {
        shadowRadius = 2f
    }

    override fun createRenderState(): WreckRenderState = WreckRenderState()

    override fun extractRenderState(w: Wreck, s: WreckRenderState, partialTicks: Float) {
        super.extractRenderState(w, s, partialTicks)
        val look = w.look as? WreckLook ?: WreckLook(w.tickCount, w.has(Wreck.FLAG_FRESH)).also { w.look = it }
        s.originX = s.x
        s.originY = s.y
        s.originZ = s.z
        s.yaw = w.getYRot(partialTicks)
        s.mirror = w.has(Wreck.FLAG_MIRROR)
        s.arrows = w.arrows
        val t = w.tickCount - look.born + partialTicks
        s.spin = if (look.fresh) SPIN * RUN_DOWN * (1f - exp(-t / RUN_DOWN)) else 0f
        val m = if (s.mirror) -1f else 1f
        pose.set(s.x, s.y, s.z, s.yaw, 0f, 0f)
        // The ground under each lot and under the horse, looked at now and then
        if (w.tickCount - look.probedAt >= PROBE_EVERY || look.probedAt == Int.MIN_VALUE) {
            look.probedAt = w.tickCount
            for (k in 0..WreckLook.PLACES) {
                val (px, pz) = if (k < WreckLook.PLACES) WagonMesh.WRECK_LOTS[k][0] * m to WagonMesh.WRECK_LOTS[k][1] else WagonMesh.WRECK_HORSE[0] * m to WagonMesh.WRECK_HORSE[1]
                look.ground[k] = groundAt(w, px, pz)
            }
            // The slope it lies on: the ground at the hull's two ends, and near the road and out in the verge
            val back = groundAt(w, HULL_X * m, HULL_BACK)
            val front = groundAt(w, HULL_X * m, HULL_FRONT)
            val near = groundAt(w, HULL_NEAR * m, HULL_Z)
            val far = groundAt(w, HULL_FAR * m, HULL_Z)
            look.pitch = slope(front - back, HULL_BACK - HULL_FRONT)
            look.roll = m * slope(far - near, HULL_NEAR - HULL_FAR)
        }
        s.pitch = look.pitch
        s.roll = look.roll
        // What's left of the load, lying where it spilled
        var n = 0
        for (k in 0 until Wreck.LOTS) {
            val g = w.lot(k) ?: continue
            val place = WagonMesh.WRECK_LOTS[k % WreckLook.PLACES]
            val under = if (k >= WreckLook.PLACES) w.lot(k - WreckLook.PLACES) else null
            val on = if (under != null) WagonDraw.height(WagonDraw.broken(under)) else 0f
            s.kind[n] = WagonDraw.broken(g)
            s.lx[n] = place[0] * m
            s.ly[n] = -(rise(look, k % WreckLook.PLACES, s.y) + on)
            s.lz[n] = place[1]
            s.turn[n] = place[2] * m + (if (under != null) HEAPED_TURN else 0f)
            n++
        }
        s.lots = n
        // The horse dead in its cut harness, as it was dragged over with the wagon
        if (w.has(Wreck.FLAG_HORSE)) {
            s.horse = HorseRenderState().also {
                it.coat = w.coat
                it.lieWeight = 1f
                it.dead = true
                it.cut = true
            }
            s.horseX = WagonMesh.WRECK_HORSE[0] * m
            s.horseY = -rise(look, WreckLook.PLACES, s.y)
            s.horseZ = WagonMesh.WRECK_HORSE[1]
            s.horseTurn = WagonMesh.WRECK_HORSE[2] * m
        } else {
            s.horse = null
        }
        // Its shadow under the middle of the hull lying over
        pose.world(SHADOW_X * m, 0f, SHADOW_Z, at)
        s.x = at.x
        s.z = at.z
    }

    /** The ground's height at [px], [pz] of the wreck's space (as [pose] has it now). */
    private fun groundAt(w: Wreck, px: Float, pz: Float): Double {
        pose.world(px, 0f, pz, at)
        return WagonClient.groundAt(w.level(), at.x, at.z, w.y)
    }

    /** Degrees a rise of [dy] blocks over [px] px slopes, kept to what a hull would lie on. */
    private fun slope(dy: Double, px: Float): Float =
        Math.toDegrees(kotlin.math.atan2(dy, px / 16.0)).toFloat().coerceIn(-MOST_SLOPE, MOST_SLOPE)

    /** Px the ground under place [k] is above the wreck's own. */
    private fun rise(look: WreckLook, k: Int, y: Double): Float {
        val ground = look.ground[k]
        return if (ground.isNaN()) 0f else ((ground - y) * 16.0).toFloat()
    }

    override fun submit(s: WreckRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.translate(s.originX - s.x, s.originY - s.y, s.originZ - s.z)
        poseStack.rotateDegrees(Axis.YP, 180f - s.yaw)
        // The hull lying with the slope under it (its lots and the horse lie on the ground where each is)
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.XP, s.pitch)
        poseStack.rotateDegrees(Axis.ZP, s.roll)
        poseStack.scale(-1f, -1f, 1f)
        if (s.mirror) poseStack.scale(-1f, 1f, 1f)
        collector.submitModel(model, s, poseStack, WagonRenderer.TEXTURE, s.lightCoords, OverlayTexture.NO_OVERLAY, s.outlineColor)
        poseStack.popPose()
        poseStack.scale(-1f, -1f, 1f)
        for (i in 0 until s.lots) {
            poseStack.pushPose()
            poseStack.translate(s.lx[i] / 16f, s.ly[i] / 16f, s.lz[i] / 16f)
            poseStack.rotateDegrees(Axis.YP, -s.turn[i])
            collector.submitModelPart(parts[s.kind[i]], poseStack, WagonRenderer.CUTOUT, s.lightCoords, OverlayTexture.NO_OVERLAY, null, -1, s.outlineColor)
            poseStack.popPose()
        }
        val dead = s.horse
        if (dead != null) {
            poseStack.pushPose()
            poseStack.translate(s.horseX / 16f, s.horseY / 16f, s.horseZ / 16f)
            poseStack.rotateDegrees(Axis.YP, -s.horseTurn)
            // Its model's ground is 24 px under its own origin
            poseStack.translate(0f, -HORSE_GROUND / 16f, 0f)
            collector.submitModel(horse, dead, poseStack, HorseRenderer.texture(dead.coat), s.lightCoords, OverlayTexture.NO_OVERLAY, s.outlineColor)
            poseStack.popPose()
        }
        poseStack.popPose()
        super.submit(s, poseStack, collector, camera)
    }

    // Lying over it reaches some 54 px to the side it fell, its lots to 84 and the horse 96 ahead
    override fun getBoundingBoxForCulling(w: Wreck, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(w, partialTicks).inflate(REACH, 1.0, REACH)

    companion object {
        /** Its wheels in the air: radians a tick as it lands, and the ticks they take to run down. */
        private const val SPIN = 0.45f
        private const val RUN_DOWN = 28f

        /** Ticks between looks at the ground under its lots. */
        private const val PROBE_EVERY = 40

        /**
         * Where it feels the slope under the hull lying over (px, its space, over to its right): along it at [HULL_X]
         * from [HULL_BACK] to [HULL_FRONT], and across it at [HULL_Z] from [HULL_NEAR] the road to [HULL_FAR] out; and
         * the most it tilts with it (degrees).
         */
        private const val HULL_X = -30f
        private const val HULL_BACK = 6f
        private const val HULL_FRONT = -48f
        private const val HULL_Z = -20f
        private const val HULL_NEAR = -16f
        private const val HULL_FAR = -50f
        private const val MOST_SLOPE = 20f

        /** Degrees a lot heaped on another is turned from its place's own turn. */
        private const val HEAPED_TURN = 23f

        /** The horse model's ground, px under its origin. */
        private const val HORSE_GROUND = 24f

        /** Where under it its shadow falls (px, its space, to the side it fell and ahead), and how far its box reaches. */
        private const val SHADOW_X = -28f
        private const val SHADOW_Z = -14f
        private const val REACH = 6.5
    }
}

/** What a loot pile's renderer needs: which way it's turned and how many of its six pieces are left. */
class LootPileRenderState : EntityRenderState() {
    var yaw = 0f
    var pieces = 6
}

/** The band's loot pile (WagonMesh.loot()): its pieces taken one by one, the tarp and its ropes coming off with the first. */
class LootPileModel(root: ModelPart) : EntityModel<LootPileRenderState>(root, RenderTypes::entityCutout) {
    private val loot = (0 until 6).map { root.getChild("loot_$it") }
    private val tarp = root.getChild("tarp")
    private val ropes = root.getChild("tarp_ropes")

    override fun setupAnim(state: LootPileRenderState) {
        super.setupAnim(state)
        for (part in loot) part.visible = true
        val gone = (loot.size - state.pieces).coerceIn(0, loot.size)
        for (i in 0 until gone) loot[TAKEN_ORDER[i]].visible = false
        // The tarp rests on "loot_4": left up without it, it would float over the rest
        tarp.visible = loot[4].visible
        ropes.visible = loot[4].visible
    }

    companion object {
        /** The order the pieces go in: the one on top first. */
        private val TAKEN_ORDER = intArrayOf(4, 5, 3, 2, 1, 0)
    }
}

/** The band's loot pile at its camp: what's left of what it took, under a tarp till the first piece is taken. */
class LootPileRenderer(context: EntityRendererProvider.Context) : EntityRenderer<LootPile, LootPileRenderState>(context) {
    private val model = LootPileModel(context.bakeLayer(WagonModel.LOOT))

    init {
        shadowRadius = 1f
    }

    override fun createRenderState(): LootPileRenderState = LootPileRenderState()

    override fun extractRenderState(pile: LootPile, s: LootPileRenderState, partialTicks: Float) {
        super.extractRenderState(pile, s, partialTicks)
        s.yaw = pile.getYRot(partialTicks)
        s.pieces = pile.pieces
    }

    override fun submit(s: LootPileRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, 180f - s.yaw)
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModel(model, s, poseStack, WagonRenderer.TEXTURE, s.lightCoords, OverlayTexture.NO_OVERLAY, s.outlineColor)
        poseStack.popPose()
        super.submit(s, poseStack, collector, camera)
    }
}
