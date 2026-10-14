package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.AddPackFindersEvent

/**
 * The optional "Guildmark HD" resource pack: vanilla stone, wood, grass, leaves and ores
 * repainted at 32x to sit next to Guildmark's own 32x blocks. Off by default; players
 * switch it on in Options > Resource Packs. Textures come from art/scripts/vanilla_hd.py.
 */
object HdPack {
    fun register(modBus: IEventBus) {
        modBus.addListener(AddPackFindersEvent::class.java) { event ->
            if (event.packType == PackType.CLIENT_RESOURCES) {
                event.addPackFinders(
                    Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "resourcepacks/guildmark_hd"),
                    PackType.CLIENT_RESOURCES,
                    Component.literal("Guildmark HD"),
                    PackSource.BUILT_IN,
                    false,
                    Pack.Position.TOP,
                )
            }
        }
    }
}
