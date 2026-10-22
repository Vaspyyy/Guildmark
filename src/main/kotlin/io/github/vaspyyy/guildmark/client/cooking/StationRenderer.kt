package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.cooking.vessel.StationBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * What the renderers of the five stations that have a [StationAnim] (the Stewpot, the Hob, the Bake oven, the Spit and the Prep
 * Board) share: the render state, the extraction, the box to cull by (a crane swings a pot past its cell, an oven door out of
 * it) and the counting of what drawing costs. A subclass draws in [draw], with the anim, from the frame's partial tick.
 */
abstract class StationRenderer<BE : StationBlockEntity>(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BE, StationState> {

    protected val resolver: ItemModelResolver = context.itemModelResolver()

    override fun createRenderState(): StationState = StationState()

    override fun getRenderBoundingBox(be: BE): AABB {
        val p = be.blockPos
        return AABB(p.x - 1.0, p.y.toDouble(), p.z - 1.0, p.x + 2.0, p.y + 2.0, p.z + 2.0)
    }

    override fun extractRenderState(
        be: BE, state: StationState, partialTicks: Float, cameraPosition: Vec3, breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        state.fill(be, partialTicks, cameraPosition)
    }

    override fun submit(state: StationState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val anim = state.anim ?: return
        val t0 = System.nanoTime()
        draw(state, anim, poseStack, collector)
        StationFx.drew(state.nanos + System.nanoTime() - t0)
    }

    /** Draws the station, with the pose at its block's origin. */
    protected abstract fun draw(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector)
}

/**
 * Item pictures to lay down in one go (the chunks in a pot, the pieces in a pan, what lies on a tray or a heap): each a flat sprite
 * at a place, turned and tipped, in a colour, written as cutout geometry (one submission for each atlas the pictures are in,
 * which is usually one). A picture that is missing draws nothing.
 */
class Sprites(private val capacity: Int) {
    var size = 0
        private set
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    private val yaw = FloatArray(capacity)
    private val tiltX = FloatArray(capacity)
    private val tiltZ = FloatArray(capacity)
    private val half = FloatArray(capacity)
    private val colour = IntArray(capacity)
    private val from = FloatArray(capacity)
    private val to = FloatArray(capacity)
    private val flat = arrayOfNulls<CookItems.Flat>(capacity)

    /** Lays [flat] with its middle at ([px], [py], [pz]) px, [half] px from the middle to each edge (only the part from [from] to [to] along x, if given). */
    fun add(
        flat: CookItems.Flat?, px: Float, py: Float, pz: Float, yaw: Float, tiltX: Float, tiltZ: Float, half: Float, argb: Int,
        from: Float = 0f, to: Float = 1f,
    ) {
        if (flat == null || size >= capacity || half <= 0.01f) return
        val i = size++
        this.flat[i] = flat
        this.from[i] = from
        this.to[i] = to
        x[i] = px
        y[i] = py
        z[i] = pz
        this.yaw[i] = yaw
        this.tiltX[i] = tiltX
        this.tiltZ[i] = tiltZ
        this.half[i] = half
        colour[i] = argb
    }

    /** Writes every picture, in the pose [poseStack] has now. */
    fun submit(state: StationState, collector: SubmitNodeCollector, poseStack: PoseStack) {
        if (size == 0) return
        var first: Identifier? = null
        var second: Identifier? = null
        var others: ArrayList<Identifier>? = null
        for (i in 0 until size) {
            val atlas = flat[i]!!.atlas
            if (atlas == first || atlas == second || others?.contains(atlas) == true) continue
            when {
                first == null -> first = atlas
                second == null -> second = atlas
                else -> (others ?: ArrayList<Identifier>(2).also { others = it }).add(atlas)
            }
        }
        first?.let { write(state, collector, poseStack, it) }
        second?.let { write(state, collector, poseStack, it) }
        others?.forEach { write(state, collector, poseStack, it) }
    }

    private fun write(state: StationState, collector: SubmitNodeCollector, poseStack: PoseStack, atlas: Identifier) {
        state.layer(collector, 0, poseStack, Gfx.cutout(atlas)) { pen ->
            for (i in 0 until size) {
                val f = flat[i] ?: continue
                if (f.atlas != atlas) continue
                pen.sprite(x[i], y[i], z[i], half[i], half[i], yaw[i], tiltX[i], tiltZ[i], f, colour[i], from[i], to[i])
            }
        }
    }
}

/**
 * Item pictures standing on a rod (a spit's joints): each at a place along the rod, turned to an angle about it, drawn as a pair
 * of pictures at right angles so that a face shows from every way round. One submission for each atlas in use.
 */
class Spokes(private val capacity: Int) {
    var size = 0
        private set
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    private val hx = FloatArray(capacity)
    private val hr = FloatArray(capacity)
    private val angle = FloatArray(capacity)
    private val colour = IntArray(capacity)
    private val flat = arrayOfNulls<CookItems.Flat>(capacity)

    /** Stands [flat] on the rod at ([px], [py], [pz]) px, [hx] each way along the rod and [hr] each way out from it, turned [angle] radians about it. */
    fun add(flat: CookItems.Flat?, px: Float, py: Float, pz: Float, hx: Float, hr: Float, angle: Float, argb: Int) {
        if (flat == null || size >= capacity) return
        val i = size++
        this.flat[i] = flat
        x[i] = px
        y[i] = py
        z[i] = pz
        this.hx[i] = hx
        this.hr[i] = hr
        this.angle[i] = angle
        colour[i] = argb
    }

    fun submit(state: StationState, collector: SubmitNodeCollector, poseStack: PoseStack) {
        if (size == 0) return
        var first: Identifier? = null
        var second: Identifier? = null
        for (i in 0 until size) {
            val atlas = flat[i]!!.atlas
            if (atlas == first || atlas == second) continue
            if (first == null) first = atlas else if (second == null) second = atlas
        }
        first?.let { write(state, collector, poseStack, it) }
        second?.let { write(state, collector, poseStack, it) }
    }

    private fun write(state: StationState, collector: SubmitNodeCollector, poseStack: PoseStack, atlas: Identifier) {
        state.layer(collector, 0, poseStack, Gfx.cutout(atlas)) { pen ->
            for (i in 0 until size) {
                val f = flat[i] ?: continue
                if (f.atlas != atlas) continue
                pen.spoke(x[i], y[i], z[i], hx[i], hr[i], angle[i], f, colour[i])
                pen.spoke(x[i], y[i], z[i], hx[i], hr[i], angle[i] + 1.5708f, f, colour[i])
            }
        }
    }
}
