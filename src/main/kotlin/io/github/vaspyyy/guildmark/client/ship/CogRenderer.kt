package io.github.vaspyyy.guildmark.client.ship

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.ship.Cog
import io.github.vaspyyy.guildmark.ship.CogPart
import io.github.vaspyyy.guildmark.ship.Wind
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sin

/**
 * A cog on the water. The whole ship rocks on the swell, heels to the wind in her sail and leans out of her
 * turns (all from [Cog.lean], which also places everyone aboard, so they ride the deck exactly); [CogModel]
 * works the sail, the yard, the wheel, the rudder, the anchor and the bell. After dark her lanterns and stern
 * windows burn on a glow layer.
 */
class CogRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Cog, CogRenderState>(context) {
    private val model = CogModel(context.bakeLayer(CogModel.LAYER))

    init {
        // Her hull sits in the water: a round shadow on the surface would only look wrong
        shadowRadius = 0f
    }

    override fun createRenderState(): CogRenderState = CogRenderState()

    override fun extractRenderState(entity: Cog, state: CogRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        val level = entity.level()
        state.yaw = entity.getYRot(partialTicks)
        state.sail = Mth.lerp(partialTicks, entity.sailO, entity.sail)
        state.fill = Mth.lerp(partialTicks, entity.fillO, entity.fill)
        state.luff = Mth.lerp(partialTicks, entity.luffO, entity.luff)
        state.brace = Mth.lerp(partialTicks, entity.braceO, entity.brace)
        // The way the wind blows, against the bow: a wind from astern blows straight ahead
        state.windYaw = Mth.wrapDegrees(Wind.from(level, partialTicks) + 180f - state.yaw)
        state.wind = Wind.strength(level, partialTicks)
        val helm = Mth.lerp(partialTicks, entity.rudderO, entity.rudder)
        state.rudder = helm * RUDDER_DEGREES
        state.wheel = helm * WHEEL_DEGREES
        state.anchor = Mth.lerp(partialTicks, entity.anchorDropO, entity.anchorDrop)
        state.lanterns = CogEffects.lanterns(entity, partialTicks)
        state.bell = bell(entity, partialTicks)
        state.time = entity.tickCount + partialTicks
        state.speed = Mth.lerp(partialTicks, entity.seenSpeedO, entity.seenSpeed)
        val (pitch, heel, bob) = entity.lean(partialTicks)
        state.pitch = pitch
        state.heel = heel
        state.bob = bob
        state.hurtTime = entity.hurtTime - partialTicks
        state.hurtDir = entity.hurtDir
        // Her name over the stern castle, not her middle
        if (state.nameTag != null) {
            val yaw = state.yaw
            state.nameTagAttachment = Vec3(Cog.forwardX(yaw) * NAME_AFT, NAME_UP + bob, Cog.forwardZ(yaw) * NAME_AFT)
        }
    }

    /** Ticks since the bell was struck, while it still swings, or -1. */
    private fun bell(entity: Cog, partialTicks: Float): Float {
        val struck = entity.bellStruck
        if (struck < 0) return -1f
        val now = (entity.level().gameTime and 0x3FFFFFFF).toInt()
        val since = now - struck
        return if (since < 0 || since > BELL_SWINGS) -1f else since + partialTicks
    }

    override fun submit(state: CogRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.translate(0f, state.bob, 0f)
        poseStack.rotateDegrees(Axis.YP, 180f - state.yaw)
        poseStack.rotateDegrees(Axis.XP, state.pitch)
        // A blow against her hull rocks her over and back
        val hurt = if (state.hurtTime > 0f) sin(state.hurtTime) * state.hurtTime * HURT_ROCK * state.hurtDir else 0f
        poseStack.rotateDegrees(Axis.ZP, state.heel + hurt)
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModel(model, state, poseStack, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor)
        if (state.lanterns > 0.01f) {
            collector.order(1).submitModel(
                model, state, poseStack, RenderTypes.eyes(GLOW), state.lightCoords, OverlayTexture.NO_OVERLAY, ARGB.white(state.lanterns), null, state.outlineColor,
            )
        }
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    // Her name shows when she's looked at from outside her: aboard, it would hang in front of you all the time
    override fun shouldShowName(entity: Cog, distanceToCameraSq: Double): Boolean {
        if (Minecraft.getInstance().player?.vehicle === entity) return false
        if (entity.shouldShowName()) return true
        if (!entity.hasCustomName()) return false
        val looked = entityRenderDispatcher.crosshairPickEntity
        return looked === entity || (looked is CogPart && looked.cog === entity)
    }

    // Her box is only her middle: her bow, her stern, her mast and her anchor reach far past it
    override fun getBoundingBoxForCulling(entity: Cog, partialTicks: Float): AABB {
        val box = super.getBoundingBoxForCulling(entity, partialTicks)
        return AABB(box.minX - REACH, box.minY - DRAUGHT, box.minZ - REACH, box.maxX + REACH, box.minY + MAST, box.maxZ + REACH)
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/ship/cog.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/ship/cog_glow.png")

        /** Degrees the rudder and the wheel turn with the helm hard over. */
        private const val RUDDER_DEGREES = 30f
        private const val WHEEL_DEGREES = 270f
        /** Ticks the bell swings after it's struck. */
        private const val BELL_SWINGS = 80
        /** Degrees a blow rocks her, for each tick of the shudder left. */
        private const val HURT_ROCK = 0.25f
        /** Where her name hangs: blocks aft of her middle, and up from the waterline. */
        private const val NAME_AFT = -4.0
        private const val NAME_UP = 3.6
        /** Blocks past her own box her bow and her stern reach; below the waterline her anchor drops, and above it her pennant flies. */
        private const val REACH = 6.5
        private const val DRAUGHT = 4.0
        private const val MAST = 13.0
    }
}
