package io.github.vaspyyy.guildmark.cooking.town

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/**
 * How the retrofit's look at a lodging came out (living-world/cook/design.md 7.2): [FITTED] its kitchen was fitted (or
 * already was), [NO_ROOM] a cell had no room for its block, [SKIPPED] someone had changed a cell, so the town goes
 * without that part. Whatever it is, the lodging is not looked at again.
 */
enum class FitState(val id: String) {
    FITTED("fitted"),
    NO_ROOM("no_room"),
    SKIPPED("skipped");

    companion object {
        /** A state by its saved name; one this build does not know (a newer one's) reads as SKIPPED, which also means looked at. */
        fun of(id: String): FitState = entries.firstOrNull { it.id == id } ?: SKIPPED
    }
}

/**
 * The retrofit's notes (living-world/cook/design.md 7.2, 9.1), kept with the overworld as `kitchen_fit`: for each
 * capital, by its centre, the lodgings the round has looked at and how each came out, so it never looks twice. A few
 * bytes a lodging, and at most a tavern and a bakery or two for a capital. Missing, it is empty, and the round looks
 * at the lodging once, which is harmless: it fits only where the plan's own block still stands.
 */
class KitchenFitData(private val seen: MutableMap<Long, MutableMap<Int, FitState>> = HashMap()) : SavedData() {
    /** How the round came out for [lodging] of the capital with centre [capital], or null if it has not looked. */
    fun state(capital: Long, lodging: Int): FitState? = seen[capital]?.get(lodging)

    fun has(capital: Long, lodging: Int): Boolean = state(capital, lodging) != null

    /** Notes how looking at [lodging] came out. */
    fun set(capital: Long, lodging: Int, state: FitState) {
        val of = seen.getOrPut(capital) { HashMap() }
        if (of.put(lodging, state) != state) setDirty()
    }

    /** How many lodgings have been looked at, over all capitals. */
    val size: Int get() = seen.values.sumOf { it.size }

    private class Note(val capital: Long, val lodging: Int, val state: String)

    private fun notes(): List<Note> = seen.flatMap { (capital, lodgings) -> lodgings.map { (id, state) -> Note(capital, id, state.id) } }

    companion object {
        private val NOTE: Codec<Note> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter { it.capital },
                Codec.INT.fieldOf("lodging").forGetter { it.lodging },
                Codec.STRING.optionalFieldOf("state", FitState.SKIPPED.id).forGetter { it.state },
            ).apply(i) { capital, lodging, state -> Note(capital, lodging, state) }
        }

        private val CODEC: Codec<KitchenFitData> = RecordCodecBuilder.create { i ->
            i.group(NOTE.listOf().optionalFieldOf("lodgings", listOf()).forGetter { it.notes() })
                .apply(i) { notes ->
                    val seen = HashMap<Long, MutableMap<Int, FitState>>()
                    for (n in notes) seen.getOrPut(n.capital) { HashMap() }[n.lodging] = FitState.of(n.state)
                    KitchenFitData(seen)
                }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "kitchen_fit"), Supplier { KitchenFitData() }, CODEC)

        fun get(level: ServerLevel): KitchenFitData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
