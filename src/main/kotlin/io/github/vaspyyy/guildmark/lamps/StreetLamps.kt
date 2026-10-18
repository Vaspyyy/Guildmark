package io.github.vaspyyy.guildmark.lamps

import io.github.vaspyyy.guildmark.GuildmarkConfig
import net.neoforged.bus.api.IEventBus

/**
 * The capitals' street lamps, lit at dusk and put out at dawn (the Street Lamps setting): the lamp blocks themselves
 * ([StreetLampBlock], [LampRegistry]).
 */
object StreetLamps {
    /** Sets the street lamps going: their blocks. */
    fun register(modBus: IEventBus) {
        LampRegistry.BLOCKS.register(modBus)
    }

    /** Whether the street lamps are lit and put out at all (the Street Lamps setting). */
    fun enabled(): Boolean = try {
        GuildmarkConfig.STREET_LAMPS.get()
    } catch (e: IllegalStateException) {
        true
    }
}
