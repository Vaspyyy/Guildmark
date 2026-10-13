package io.github.vaspyyy.guildmark.dragon

import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent

/**
 * The dragons of the high peaks: their registries, their roosts' upkeep each tick, and their test commands.
 * Everything about them lives in this package; the mod's main class only calls [register].
 */
object Dragons {
    fun register(modBus: IEventBus) {
        DragonRegistry.ENTITIES.register(modBus)
        DragonRegistry.SOUNDS.register(modBus)
        DragonRegistry.PARTICLES.register(modBus)
        DragonRegistry.ITEMS.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) {
            it.put(DragonRegistry.DRAGON.get(), Dragon.createAttributes().build())
        }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) event.accept(DragonRegistry.SPAWN_EGG.get())
        }
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() == Level.OVERWORLD) DragonRoosts.get(level).tick(level)
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { DragonCommand.register(it.dispatcher) }
    }
}
