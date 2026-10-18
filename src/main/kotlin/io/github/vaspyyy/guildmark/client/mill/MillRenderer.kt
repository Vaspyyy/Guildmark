package io.github.vaspyyy.guildmark.client.mill

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.mill.MillBlock
import io.github.vaspyyy.guildmark.mill.MillBlockEntity
import io.github.vaspyyy.guildmark.mill.MillKind
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** What a mill's hub needs drawn for one frame. */
class MillRenderState : BlockEntityRenderState() {
    /** Whether it's a watermill's wheel; otherwise it's a windmill's sails. */
    var wheel = false

    /** Degrees: the way the hub faces, out of the mill (as Direction.toYRot gives it). */
    var yaw = 0f

    /** The wood the sails' frame or the wheel is built of: its city's style. */
    var style = CityStyle.TEMPERATE

    /** The sails' cloth's colour, the tint the white canvas is drawn with (0xAARRGGBB). */
    var cloth = -1

    /** Radians the sails or the wheel have turned, with the partial tick. */
    var turn = 0f

    /** How far the sails' cloth is furled, from 0 spread to 1 furled. */
    var furl = 0f

    /** Which of the four sails is broken and hanging, or -1. */
    var broken = -1

    /** Radians the broken sail's end swings from hanging straight down. */
    var swing = 0f
}

/**
 * A mill's hub, drawn: a windmill's sails (their timber frame in the city's wood and their cloth in its dye, see
 * [SailsModel] and [ClothModel]) or a watermill's wheel ([WheelModel]), turned as far as the hub's block entity has eased
 * them on this client ([MillBlockEntity.turn]). The sails reach eight and a half blocks from the hub and the wheel three
 * and a half, so each is drawn whenever its reach is in view, and from far off: a windmill is a landmark.
 */
class MillRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<MillBlockEntity, MillRenderState> {
    private val sails = SailsModel(context.bakeLayer(SailsModel.LAYER))
    private val cloth = ClothModel(context.bakeLayer(ClothModel.LAYER))
    private val wheel = WheelModel(context.bakeLayer(WheelModel.LAYER))

    override fun createRenderState(): MillRenderState = MillRenderState()

    override fun extractRenderState(
        mill: MillBlockEntity, state: MillRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(mill, state, breakProgress)
        val block = mill.blockState
        state.wheel = mill.kind == MillKind.WHEEL
        state.yaw = if (block.hasProperty(MillBlock.FACING)) block.getValue(MillBlock.FACING).toYRot() else 0f
        state.style = mill.style
        state.cloth = ARGB.opaque(mill.cloth.rgb)
        state.turn = Mth.lerp(partialTicks, mill.turnO, mill.turn)
        state.furl = Mth.lerp(partialTicks, mill.furlO, mill.furl)
        state.broken = mill.broken
        state.swing = Mth.lerp(partialTicks, mill.swingO, mill.swing)
    }

    override fun submit(state: MillRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        // Model space is the hub block's own, -z out of the mill
        poseStack.translate(0.5f, 0.5f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, 180f - state.yaw)
        poseStack.scale(-1f, -1f, 1f)
        val style = state.style.ordinal
        if (state.wheel) {
            collector.submitModel(wheel, state, poseStack, WHEELS[style], state.lightCoords, OverlayTexture.NO_OVERLAY, 0)
        } else {
            collector.submitModel(sails, state, poseStack, FRAMES[style], state.lightCoords, OverlayTexture.NO_OVERLAY, 0)
            collector.submitModel(cloth, state, poseStack, cloth.renderType(CLOTH), state.lightCoords, OverlayTexture.NO_OVERLAY, state.cloth, null, 0)
        }
        poseStack.popPose()
    }

    override fun getRenderBoundingBox(mill: MillBlockEntity): AABB =
        AABB(mill.blockPos).inflate(if (mill.kind == MillKind.WHEEL) WHEEL_REACH else SAIL_REACH)

    // Its block can be out of sight while the sails or the wheel are in it
    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = VIEW

    companion object {
        private val FRAMES = CityStyle.entries.map { texture("sails_${it.name.lowercase()}") }
        private val WHEELS = CityStyle.entries.map { texture("wheel_${it.name.lowercase()}") }
        private val CLOTH = texture("cloth")

        private fun texture(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/mill/$name.png")

        /** Blocks from the hub's block that the sails (with the windshaft) and the wheel reach, a little over. */
        private const val SAIL_REACH = 9.0
        private const val WHEEL_REACH = 4.0

        /** Blocks off that a mill is still drawn. */
        private const val VIEW = 256
    }
}
