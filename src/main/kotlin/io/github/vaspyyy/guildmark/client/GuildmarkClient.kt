package io.github.vaspyyy.guildmark.client

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge

/** Client-only setup: the Guild Ledger key. */
@Mod(value = Guildmark.MOD_ID, dist = [Dist.CLIENT])
class GuildmarkClient(modBus: IEventBus) {
    private val category = KeyMapping.Category(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "main"))
    private val ledgerKey = KeyMapping("key.guildmark.ledger", InputConstants.KEY_G, category)

    init {
        modBus.addListener(RegisterKeyMappingsEvent::class.java) { event ->
            event.registerCategory(category)
            event.register(ledgerKey)
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ ->
            val minecraft = Minecraft.getInstance()
            while (ledgerKey.consumeClick()) {
                if (minecraft.gui.screen() == null && minecraft.player != null) minecraft.gui.setScreen(LedgerScreen())
            }
        }
    }
}
