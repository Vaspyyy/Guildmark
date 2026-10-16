package io.github.vaspyyy.guildmark.client.ship

import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.client.combat.CombatArmPose
import io.github.vaspyyy.guildmark.ship.Cog
import io.github.vaspyyy.guildmark.ship.CogStation
import io.github.vaspyyy.guildmark.ship.ShipRegistry
import io.github.vaspyyy.guildmark.ship.Ships
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.max

/**
 * The client half of the ships: the cog's renderer and model, the sailing HUD, the wake and the sounds aboard
 * (see [CogEffects]), the helmsman's wheel turning as soon as they press, and how whoever stands at the helm
 * or keeps the lookout is posed.
 */
object ShipClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(ShipRegistry.COG.get(), ::CogRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(CogModel.LAYER, CogModel::createBodyLayer)
        }
        modBus.addListener(RegisterGuiLayersEvent::class.java) { event ->
            event.registerAbove(VanillaGuiLayers.HOTBAR, ShipHud.ID, ShipHud)
        }
        CogEffects.register()
        Cog.localHelm = ::localHelm
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> ShipHud.tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> ShipHud.reset() }
        // Looking on from behind, aboard, the camera stands back far enough to take in the ship
        NeoForge.EVENT_BUS.addListener(CalculateDetachedCameraDistanceEvent::class.java) { event ->
            if (event.camera.entity()?.vehicle is Cog) event.distance = max(event.distance, CAMERA_DISTANCE)
        }
    }

    /** Which way this client's own player is putting [cog]'s helm over, if they're at it. */
    private fun localHelm(cog: Cog): Int? {
        val player = Minecraft.getInstance().player ?: return null
        if (player.vehicle !== cog || cog.stationOf(player) != CogStation.HELM) return null
        val keys = player.input.keyPresses
        return (if (keys.right()) 1 else 0) - (if (keys.left()) 1 else 0)
    }

    /**
     * Where [player]'s own cog lies, as they last left her, and what to call her on the compass and the map;
     * nothing while they're aboard a ship, or when she lies in another world.
     */
    fun berth(player: Player): Pair<Vec3, Component>? {
        if (player.vehicle is Cog) return null
        val berth = Ships.berth(player)
        val at = berth.at.orElse(null) ?: return null
        if (at.dimension() != player.level().dimension()) return null
        val name = if (berth.name.isEmpty()) Component.translatable("atlas.guildmark.ship") else Component.literal(berth.name)
        return Vec3.atCenterOf(at.pos()) to name
    }

    private val armPose: HumanoidModel.ArmPose by lazy { CombatArmPose.PROXY.value }

    /**
     * Whoever stands at the helm has both hands on the wheel, leaning into it as it goes over; whoever keeps
     * the lookout at the bow stands braced, shading their eyes and looking about. A combat move (the clips
     * are filled in first) wins over either. Registered after the combat animator's, which clears the frame.
     */
    val AVATAR = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
            val cog = entity.vehicle as? Cog ?: return
            val station = cog.stationOf(entity) ?: return
            if (!station.standing) return
            state.isPassenger = false
            if (state.getRenderData(CombatAnimator.FRAME) != null) return
            val clip = Clips.get(if (station == CogStation.HELM) HELM_CLIP else LOOKOUT_CLIP) ?: return
            val time = if (station == CogStation.HELM) {
                // The clip runs from the wheel hard over to port, through amidships, to hard over to starboard
                clip.length * (0.5f + 0.5f * Mth.lerp(state.partialTick, cog.rudderO, cog.rudder))
            } else {
                (state.ageInTicks + entity.id * 13f) % clip.length
            }
            state.setRenderData(CombatAnimator.FRAME, CombatAnimator.Frame(clip, time, 1f, 0f, 0f, state.ageInTicks, null))
            state.rightArmPose = armPose
            state.leftArmPose = armPose
            state.swingAnimation = 0f
        }
    }

    /** Blocks the camera stands back from a player aboard, looking on from behind or ahead. */
    private const val CAMERA_DISTANCE = 9f

    const val HELM_CLIP = "ship/helm"
    const val LOOKOUT_CLIP = "ship/lookout"
}
