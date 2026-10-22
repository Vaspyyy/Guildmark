package io.github.vaspyyy.guildmark.client.cooking

import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.Avatar
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier

/**
 * Hearthcraft's client side (living-world/cook/design.md 1.8, 12.3, 12.5): the one place the game's client start-up calls,
 * and so the client twin of [io.github.vaspyyy.guildmark.cooking.Cooking.register]. [register] calls each part's own client
 * `register(modBus)` in turn (Part C's stations drawn, Part D's book, Part F's cold vignette, Part I3's plates; Part E's own
 * handlers are written in here), so no part edits GuildmarkClient.kt again. Part E's: it makes this the real thing (the
 * cook's pose and hands, and the handler of the action payload). Until then it registers the stubs, which do nothing.
 *
 * Nothing outside `client/` names a class in here; common code reaches the client only through hooks the client sets
 * ([io.github.vaspyyy.guildmark.cooking.CookPayloads.onAction] and `onServed`), so a dedicated server loads none of it.
 */
object CookClient {
    fun register(modBus: IEventBus) {
        CookStationsClient.register(modBus)
        CookBookClient.register(modBus)
        ChillOverlay.register(modBus)
        TownPlates.register(modBus)
    }

    /**
     * The pose of a cook at their station, filled into the combat animator's frame when no combat move is playing (1.8).
     * Registered after the combat animator's, the cutscenes', the ship's and the sea's. Part E's; until then it does nothing.
     */
    val AVATAR = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
        }
    }
}
