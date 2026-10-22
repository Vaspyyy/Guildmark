package io.github.vaspyyy.guildmark.cooking.home

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/**
 * What became of each item of each homestead's kitchen (living-world/cook/design.md 6.7), kept with the overworld so a kitchen
 * is fitted once and a station an owner took away is not fitted again. The plan itself is never saved: only the verdicts. An
 * item with no record is still to be looked at (it waits for its chunk, or for the tier). A homestead gets a record when its
 * first item is settled. A world with no homesteads, or with the kitchens off, never asks for this at all, so it creates no
 * file and writes nothing; a homestead's record is written only when one of its items is settled.
 */
class HomeKitchenData(private val homes: MutableMap<Int, Home> = HashMap()) : SavedData() {
    /** How an item ended. */
    enum class Status {
        /** Fitted: [Entry.cells] are where its stations stand. */
        DONE,

        /** Its cell was not the plan's own any more, or held something: left as the owner has it (`kit refit` looks again). */
        KEPT,

        /** The plan has no cell for it (the rule's reason is in [Entry.note]). */
        NOT_FOUND,
    }

    /** One item's verdict: the cells it took in the world (the first is where `cook homestead` points) and a word on how or why. */
    class Entry(val status: Status, val cells: List<BlockPos> = emptyList(), val note: String = "")

    /** One homestead's verdicts, by item key ([KitchenSets.keys]), in the order they were settled. */
    class Home(val id: Int, val items: MutableMap<String, Entry> = LinkedHashMap())

    /** The record of homestead [id], if it has one. */
    fun home(id: Int): Home? = homes[id]

    /** The record of homestead [id], started if it has none. */
    fun homeOrNew(id: Int): Home = homes.getOrPut(id) { Home(id) }

    /** Every record. */
    fun all(): Collection<Home> = homes.values

    /** Writes [entry] as the verdict on [key] of homestead [id]. */
    fun settle(id: Int, key: String, entry: Entry) {
        homeOrNew(id).items[key] = entry
        setDirty()
    }

    /** Takes the verdict on [key] of homestead [id] back (the item is looked at again); whether there was one. */
    fun unsettle(id: Int, key: String): Boolean {
        val removed = homes[id]?.items?.remove(key) != null
        if (removed) setDirty()
        return removed
    }

    /** Forgets homestead [id] altogether (its charter was given up). */
    fun forget(id: Int) {
        if (homes.remove(id) != null) setDirty()
    }

    companion object {
        private class Row(val key: String, val status: Status, val cells: List<BlockPos>, val note: String)

        private val ROW: Codec<Row> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("key").forGetter(Row::key),
                Codec.STRING.optionalFieldOf("status", "done").forGetter { it.status.name.lowercase() },
                BlockPos.CODEC.listOf().optionalFieldOf("cells", listOf()).forGetter(Row::cells),
                Codec.STRING.optionalFieldOf("note", "").forGetter(Row::note),
            ).apply(i) { key, status, cells, note ->
                Row(key, Status.entries.firstOrNull { it.name.equals(status, ignoreCase = true) } ?: Status.KEPT, cells, note)
            }
        }

        private class Rec(val id: Int, val rows: List<Row>)

        private val REC: Codec<Rec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Rec::id),
                ROW.listOf().optionalFieldOf("items", listOf()).forGetter(Rec::rows),
            ).apply(i, ::Rec)
        }

        private val CODEC: Codec<HomeKitchenData> = REC.listOf().optionalFieldOf("homes", listOf()).codec().xmap(
            { list ->
                HomeKitchenData(
                    list.associate { rec ->
                        rec.id to Home(rec.id, rec.rows.associateTo(LinkedHashMap()) { it.key to Entry(it.status, it.cells, it.note) })
                    }.toMutableMap(),
                )
            },
            { data ->
                data.homes.values.filter { it.items.isNotEmpty() }.map { home ->
                    Rec(home.id, home.items.map { (key, e) -> Row(key, e.status, e.cells, e.note) })
                }
            },
        )

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "home_kitchens"), Supplier { HomeKitchenData() }, CODEC)

        /** The verdicts, kept with the overworld. */
        fun get(level: ServerLevel): HomeKitchenData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
