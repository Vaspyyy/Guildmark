package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.cooking.CookPayloads
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier

/**
 * Hearthcraft's client (living-world/cook/design.md 1.8, 11): the stations seen and heard, the Cook's Book, the cold's
 * vignette, the town's plates, and the cook in motion: what each cook in sight is doing ([CookActionHandler], which also
 * starts the local player's clip on their click), their body ([CookPose], whose modifier [AVATAR] is) and their hands in
 * first person ([CookHands]). Only the client loads this; the server's payload reaches it through [CookPayloads.onAction].
 */
object CookClient {
    fun register(modBus: IEventBus) {
        CookStationsClient.register(modBus)
        CookBookClient.register(modBus)
        ChillOverlay.register(modBus)
        TownPlates.register(modBus)
        CookActionHandler.register()
        CookHands.register()
        CookPayloads.onAction = { CookActionHandler.onPayload(it) }
    }

    /** The cook's body, registered by GuildmarkClient after the combat animator's, the cutscenes', the ship's and the sea's. */
    val AVATAR: AvatarRenderStateModifier = CookPose.AVATAR
}
