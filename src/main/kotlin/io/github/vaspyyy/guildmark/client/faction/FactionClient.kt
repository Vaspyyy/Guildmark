package io.github.vaspyyy.guildmark.client.faction

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.EnemyGlowLayer
import io.github.vaspyyy.guildmark.client.combat.EnemyAnimation
import io.github.vaspyyy.guildmark.client.model.EnemyRenderState
import io.github.vaspyyy.guildmark.client.model.HollowSoldierModel
import io.github.vaspyyy.guildmark.client.model.RenegadeModel
import io.github.vaspyyy.guildmark.faction.FactionRegistry
import io.github.vaspyyy.guildmark.faction.Renegade
import io.github.vaspyyy.guildmark.faction.SwornKnight
import io.github.vaspyyy.guildmark.faction.WardLight
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.entity.HumanoidArm
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.min
import kotlin.math.sin

/** The factions on screen: the ward-lights, the sworn knights, Silvan Marrow, and the eye on a heist. */
object FactionClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(FactionRegistry.WARD_LIGHT.get(), ::WardLightRenderer)
            event.registerEntityRenderer(FactionRegistry.SWORN_KNIGHT.get(), ::SwornKnightRenderer)
            event.registerEntityRenderer(FactionRegistry.RENEGADE.get(), ::RenegadeRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(RenegadeModel.LAYER, RenegadeModel::createBodyLayer)
        }
        modBus.addListener(RegisterGuiLayersEvent::class.java) { it.registerAbove(VanillaGuiLayers.CROSSHAIR, SuspicionHud.ID, SuspicionHud) }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> SuspicionHud.tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> SuspicionHud.reset() }
    }
}

class WardLightRenderState : EntityRenderState() {
    var sentry = false
}

/**
 * A ward-light: a star of light that always faces you, its soft outer glow turning one way and its bright core the
 * other, breathing as it hangs there. A test light is the pale blue of the Starwatch's lamps; a renegade's sentry
 * burns violet. Each blooms open over its first few ticks.
 */
class WardLightRenderer(context: EntityRendererProvider.Context) : EntityRenderer<WardLight, WardLightRenderState>(context) {
    override fun createRenderState(): WardLightRenderState = WardLightRenderState()

    override fun extractRenderState(entity: WardLight, state: WardLightRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.sentry = entity.sentry
    }

    override fun submit(state: WardLightRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val t = state.ageInTicks
        val bloom = min(1f, t / 8f)
        val breath = 1f + 0.12f * sin(t * 0.21f) + 0.04f * sin(t * 0.83f)
        val tint = if (state.sentry) SENTRY else TEST
        poseStack.pushPose()
        poseStack.translate(0f, state.boundingBoxHeight / 2f, 0f)
        poseStack.rotate(camera.orientation)
        star(poseStack, collector, 1.1f * breath * bloom, t * 1.6f, ARGB.color(0.55f, tint))
        star(poseStack, collector, 0.55f * breath * bloom, -t * 4.5f, ARGB.color(1f, ARGB.srgbLerp(0.55f, tint, 0xFFFFFF)))
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    /** One camera-facing quad of the star, [size] blocks across, turned [spin] degrees. */
    private fun star(poseStack: PoseStack, collector: SubmitNodeCollector, size: Float, spin: Float, color: Int) {
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.ZP, spin)
        val h = size / 2f
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE)) { pose, buffer ->
            fun corner(x: Float, y: Float, u: Float, v: Float) {
                buffer.addVertex(pose, x, y, 0f).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f)
            }
            corner(-h, -h, 0f, 1f)
            corner(h, -h, 1f, 1f)
            corner(h, h, 1f, 0f)
            corner(-h, h, 0f, 0f)
        }
        poseStack.popPose()
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/ward_light.png")
        private const val TEST = 0xA8C8FF
        private const val SENTRY = 0xB46CFF
    }
}

/**
 * A sworn knight of the Hearthsworn: the Hollow Soldier's frame (they were one order once) in bright steel and a red
 * tabard, with none of the arrows the hollow ones still carry. Shield up while they guard, and their blade work is
 * the soldiers' own clips.
 */
class SwornKnightRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<SwornKnight, EnemyRenderState, HollowSoldierModel>(context, HollowSoldierModel(context.bakeLayer(HollowSoldierModel.LAYER)), 0.5f) {

    init {
        val body = model.root().getChild("body")
        body.getChild("arrow").visible = false
        body.getChild("arrow_stub").visible = false
    }

    override fun createRenderState(): EnemyRenderState = EnemyRenderState()
    override fun getTextureLocation(state: EnemyRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: SwornKnight, state: EnemyRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.guarding = entity.guarding
        if (entity.guarding) {
            if (entity.mainArm == HumanoidArm.RIGHT) state.leftArmPose = HumanoidModel.ArmPose.BLOCK
            else state.rightArmPose = HumanoidModel.ArmPose.BLOCK
        }
        EnemyAnimation.extract(entity, state, partialTicks)
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/sworn_knight.png")
    }
}

/**
 * Silvan Marrow, a little over a man's height in his hood: his eyes and the cracked star sigils on his mantle glow
 * from the dark of his robe. He falls to his knees and slumps over his staff to die.
 */
class RenegadeRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<Renegade, EnemyRenderState, RenegadeModel>(context, RenegadeModel(context.bakeLayer(RenegadeModel.LAYER)), 0.55f) {

    init {
        addLayer(EnemyGlowLayer(this) { GLOW })
    }

    override fun createRenderState(): EnemyRenderState = EnemyRenderState()
    override fun getTextureLocation(state: EnemyRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: Renegade, state: EnemyRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.phaseTwo = entity.phaseTwo
        state.hasRedOverlay = entity.hurtTime > 0 && entity.deathTime <= 0
        EnemyAnimation.extract(entity, state, partialTicks)
        if (entity.deathTime > 0) EnemyAnimation.hold(state, "renegade/death", entity.deathTime + partialTicks)
    }

    override fun getFlipDegrees(): Float = 0f

    override fun scale(state: EnemyRenderState, poseStack: PoseStack) {
        poseStack.scale(SCALE, SCALE, SCALE)
    }

    companion object {
        const val SCALE = 1.05f
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/renegade.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/renegade_glow.png")
    }
}
