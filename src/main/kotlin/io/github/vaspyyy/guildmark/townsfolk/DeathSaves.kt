package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * A death reaches the disk soon after it happens, not at the next autosave (living-world/m7/leftovers.md item 3; the build plan
 * gives it to the villages' V11): a resident's body that died is gone from its chunk when the chunk is saved, so a game killed
 * before the next autosave used to wake with a living record and no body anywhere. Now [Residents.pass] asks for a save
 * ([due]), and a second after the death the overworld's stores are saved as the autosave saves them ([net.minecraft.world.level.
 * storage.SavedDataStorage.scheduleSave]: the changed ones are written out off the server thread), the residents' dead among them,
 * and a village's dead list with them (it is in the same save).
 *
 * Throttled, so a siege that kills a dozen costs a few saves and not a dozen: the deaths of one second go in one save, and two
 * saves are at least [SPACING] ticks apart (a death in between is saved at the end of the spacing). Nothing else waits on it, and
 * it is forgotten when the server stops (which saves everything anyway).
 */
object DeathSaves {
    /** Ticks after a death before the save, so that the deaths of one moment go together. */
    const val GATHER = 20L

    /** The fewest ticks between two of these saves. */
    const val SPACING = 1200L

    private var due = Long.MIN_VALUE
    private var last = Long.MIN_VALUE

    /** For `village check`: the saves made since the server started, and the nanoseconds the last took on the server thread. */
    var saves = 0
        private set
    var lastNanos = 0L
        private set

    /** At setup. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    /** A resident died (from [Residents.pass]): the overworld's stores are saved shortly. */
    fun due(level: ServerLevel) {
        if (due != Long.MIN_VALUE) return
        val now = level.server.overworld().gameTime
        // A clock behind the last save's (another world, a clock set back) starts afresh
        if (last != Long.MIN_VALUE && now < last) last = Long.MIN_VALUE
        due = if (last == Long.MIN_VALUE) now + GATHER else maxOf(now + GATHER, last + SPACING)
    }

    /** Whether a save is waiting, and the game tick it is due. */
    fun pending(): Long? = due.takeIf { it != Long.MIN_VALUE }

    private fun tick(server: MinecraftServer) {
        if (due == Long.MIN_VALUE) return
        val level = server.overworld()
        val now = level.gameTime
        // A clock set back under the save's tick: it is due now
        if (now < due && due - now <= SPACING + GATHER) return
        due = Long.MIN_VALUE
        last = now
        val begun = System.nanoTime()
        try {
            level.dataStorage.scheduleSave()
        } catch (e: RuntimeException) {
            // The autosave saves it in the end; this one costs a log line, not the server
            Guildmark.LOGGER.warn("The save after a death could not be made; the next autosave makes it", e)
        }
        lastNanos = System.nanoTime() - begun
        saves++
    }

    private fun forget() {
        due = Long.MIN_VALUE
        last = Long.MIN_VALUE
        saves = 0
        lastNanos = 0L
    }
}
