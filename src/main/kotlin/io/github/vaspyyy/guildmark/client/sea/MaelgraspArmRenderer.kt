package io.github.vaspyyy.guildmark.client.sea

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.sea.Maelgrasp
import io.github.vaspyyy.guildmark.sea.MaelgraspArm
import io.github.vaspyyy.guildmark.sea.Tentacle
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB

/**
 * One of the Maelgrasp's arms. Its shape is the same [Tentacle] the server works out (from the pose it last heard,
 * eased between ticks), so it's drawn exactly where its blows land and where it can be struck; [MaelgraspArmModel] lays
 * a segment along each pair of joints. The renderer only stands it where it breaks the surface and turns it to face the
 * ship. Hacked through, the stump stays on it while the length cut off falls away into the sea on its own ([SeveredArm]),
 * drawn from a second state. The veins under its skin burn on a glow layer, brighter by night and in the Maelgrasp's fury.
 */
class MaelgraspArmRenderer(context: EntityRendererProvider.Context) :
    LivingEntityRenderer<MaelgraspArm, MaelgraspArmRenderState, MaelgraspArmModel>(context, MaelgraspArmModel(context.bakeLayer(MaelgraspArmModel.LAYER)), 0f) {

    /** Room to work an arm's shape out in, on the render thread. */
    private val scratch = Tentacle.Shape()

    init {
        addLayer(object : RenderLayer<MaelgraspArmRenderState, MaelgraspArmModel>(this) {
            override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: MaelgraspArmRenderState, yRot: Float, xRot: Float) {
                glow(parentModel, poseStack, collector, light, state)
            }
        })
    }

    override fun createRenderState(): MaelgraspArmRenderState = MaelgraspArmRenderState()
    override fun getTextureLocation(state: MaelgraspArmRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: MaelgraspArm, state: MaelgraspArmRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        // It faces the ship's middle from where it rose, and nothing else turns it
        state.bodyRot = entity.armYaw
        state.yRot = 0f
        state.xRot = 0f
        val o = entity.poseO
        val n = entity.pose
        val time = entity.time(partialTicks)
        Tentacle.solve(
            scratch,
            Mth.lerp(partialTicks, o.tip.x(), n.tip.x()), Mth.lerp(partialTicks, o.tip.y(), n.tip.y()), Mth.lerp(partialTicks, o.tip.z(), n.tip.z()),
            Mth.lerp(partialTicks, o.from.x(), n.from.x()), Mth.lerp(partialTicks, o.from.y(), n.from.y()), Mth.lerp(partialTicks, o.from.z(), n.from.z()),
            Mth.lerp(partialTicks, o.curl, n.curl), Mth.lerp(partialTicks, o.ripple, n.ripple), time, entity.phase,
        )
        scratch.joints.copyInto(state.joints)
        state.cut = entity.cut
        state.start = 0
        state.time = entity.tickCount + partialTicks
        state.grip = Mth.lerp(partialTicks, o.grip, n.grip)
        val boss = entity.level().getEntity(entity.bossId) as? Maelgrasp
        state.rage = boss?.rage ?: 0f
        state.glow = glowOf(entity, partialTicks)
        state.hasRedOverlay = entity.hurtTime > 0 && entity.deathTime <= 0

        // The length hacked off it, falling away
        val severed = entity.severed
        state.piece = if (severed == null) null else {
            val piece = MaelgraspArmRenderState()
            val since = entity.tickCount - entity.severedAt + partialTicks
            SeveredArm.place(severed, state.cut, since, piece.joints)
            piece.cut = Tentacle.SEGMENTS
            piece.start = state.cut
            piece.time = state.time
            piece.grip = 0f
            piece.rage = state.rage
            piece.glow = state.glow * (1f - (since / PIECE_DIM).coerceIn(0f, 1f))
            piece.lightCoords = state.lightCoords
            piece.outlineColor = state.outlineColor
            piece.bodyRot = state.bodyRot
            piece
        }
    }

    /** How brightly its veins burn: brighter at night, flaring as it strikes, guttering out as it dies. */
    private fun glowOf(entity: MaelgraspArm, partialTicks: Float): Float {
        val level = entity.level()
        var g = if (level.isDarkOutside) NIGHT_GLOW else DAY_GLOW
        if (entity.mode.attacking) g += STRIKE_GLOW
        if (entity.mode == MaelgraspArm.Mode.LIMP || entity.mode == MaelgraspArm.Mode.WITHDRAW) g *= 0.5f
        if (entity.deathTime > 0) g *= 1f - ((entity.deathTime + partialTicks) / STUMP_DIM).coerceIn(0f, 1f)
        return g.coerceIn(0f, 1f)
    }

    override fun submit(state: MaelgraspArmRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        super.submit(state, poseStack, collector, camera)
        val piece = state.piece ?: return
        // In the arm's own frame, as the arm is drawn
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, 180f - state.bodyRot)
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModel(model, piece, poseStack, model.renderType(TEXTURE), piece.lightCoords, OverlayTexture.NO_OVERLAY, -1, null, piece.outlineColor)
        glow(model, poseStack, collector, piece.lightCoords, piece)
        poseStack.popPose()
    }

    private fun glow(model: MaelgraspArmModel, poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: MaelgraspArmRenderState) {
        // In its fury its veins blaze
        val a = (state.glow * (1f + RAGE_GLOW * state.rage)).coerceIn(0f, 1f)
        if (a < 0.01f) return
        collector.order(1).submitModel(model, state, poseStack, RenderTypes.eyes(GLOW), light, OverlayTexture.NO_OVERLAY, ARGB.white(a), null, state.outlineColor)
    }

    // Its origin is where it breaks the surface: undo the lift every model gets to stand its feet on the ground
    override fun scale(state: MaelgraspArmRenderState, poseStack: PoseStack) {
        poseStack.translate(0f, MODEL_LIFT, 0f)
    }

    // Hacked through, it doesn't topple over: its stump sinks away (the server moves it)
    override fun getFlipDegrees(): Float = 0f

    // Its box is only where it breaks the surface: it towers far over the ship and reaches right across her
    override fun getBoundingBoxForCulling(entity: MaelgraspArm, partialTicks: Float): AABB {
        val box = super.getBoundingBoxForCulling(entity, partialTicks)
        return AABB(box.minX - REACH, box.minY - DEPTH, box.minZ - REACH, box.maxX + REACH, box.minY + HEIGHT, box.maxZ + REACH)
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/sea/maelgrasp_arm.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/sea/maelgrasp_arm_glow.png")

        /** What LivingEntityRenderer lifts every model by, so its feet (24 px down) stand on the ground. */
        private const val MODEL_LIFT = 1.501f
        /** Blocks it reaches out from where it rises, down to (its root, and its severed length sinking) and up to. */
        private const val REACH = 12.0
        private const val DEPTH = 5.0
        private const val HEIGHT = 13.0
        /** How brightly its veins burn by day and by night, how much more as it strikes, and in the Maelgrasp's fury. */
        private const val DAY_GLOW = 0.4f
        private const val NIGHT_GLOW = 0.8f
        private const val STRIKE_GLOW = 0.2f
        private const val RAGE_GLOW = 0.5f
        /** Ticks the light takes to go out of its stump, and out of the length cut off it. */
        private const val STUMP_DIM = 30f
        private const val PIECE_DIM = 36f
    }
}
