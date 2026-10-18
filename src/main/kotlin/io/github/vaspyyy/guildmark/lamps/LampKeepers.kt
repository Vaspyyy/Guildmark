package io.github.vaspyyy.guildmark.lamps

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/**
 * Who keeps each capital's lamps: for each of its lamplighters' rounds (see [LampPlan.rounds]) the resident who goes
 * round it at dusk and dawn, kept with the world so that it's the same person day after day for as long as they live
 * there. A round with nobody (or whose lamplighter has died) is given the nearest free grown-up with their evenings to
 * themselves: a townsman, a merchant or a fisher, failing those a guard (the night watch light the lamps on their way
 * to the walls). A farm's lamps are kept by the farmer of that farmhouse, coming in from the fields and going out to them.
 */
class LampKeepers(private val towns: MutableMap<BlockPos, IntArray> = HashMap()) : SavedData() {
    operator fun get(capital: BlockPos): IntArray? = towns[capital]

    operator fun set(capital: BlockPos, keepers: IntArray) {
        towns[capital] = keepers
        setDirty()
    }

    companion object {
        private class Entry(val capital: BlockPos, val keepers: List<Int>)

        private val ENTRY: Codec<Entry> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("capital").forGetter(Entry::capital),
                Codec.INT.listOf().fieldOf("keepers").forGetter(Entry::keepers),
            ).apply(i, ::Entry)
        }

        private val CODEC: Codec<LampKeepers> = ENTRY.listOf().xmap(
            { list -> LampKeepers(list.associate { it.capital to it.keepers.toIntArray() }.toMutableMap()) },
            { data -> data.towns.map { (capital, keepers) -> Entry(capital, keepers.toList()) } },
        )

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lamp_keepers"), Supplier { LampKeepers() }, CODEC)

        fun get(level: ServerLevel): LampKeepers = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Who may keep a round (guards only if nobody else is free). */
        private val KEEPERS = listOf(TownRole.CITIZEN, TownRole.MERCHANT, TownRole.FISHER, TownRole.GUARD)

        /**
         * Sets each of [town]'s rounds' keepers ([LampTown.Run.keeper]): its lamplighter as kept, while they're alive and
         * still the town's; a new one for any round without; the farmer of each farm with lamps at its gate.
         */
        fun assign(level: ServerLevel, town: LampTown) {
            val residents = Residents.get(level)
            val center = town.capital.center
            val data = get(level)
            val rounds = town.plan.rounds.size
            val kept = data[center]?.takeIf { it.size == rounds }?.copyOf() ?: IntArray(rounds) { -1 }
            val city = town.runs.filter { it.kind == LampTown.Kind.CITY }
            val taken = HashSet<Int>()
            for (run in city) {
                val r = residents[kept[run.round]]
                if (r != null && r.alive && r.capital == center && r.role in KEEPERS) {
                    run.keeper = r.id
                    taken.add(r.id)
                } else {
                    run.keeper = -1
                    kept[run.round] = -1
                }
            }
            val open = city.filter { it.keeper < 0 }
            val people = if (open.isEmpty()) listOf() else residents.of(center).filter { it.alive && it.id !in taken && it.home >= 0 && it.role in KEEPERS }
            if (open.isNotEmpty() && people.isNotEmpty()) {
                // Nearest pairs first, of each round's first lamp and each free grown-up's door; a guard only once the rest are gone
                class Pair(val run: LampTown.Run, val id: Int, val score: Double)
                val pairs = ArrayList<Pair>()
                for (r in people) {
                    val door = town.places.lodgings[r.home]?.front?.outside ?: continue
                    for (run in open) {
                        val first = town.pos[run.lamps[0]]
                        pairs.add(Pair(run, r.id, door.distSqr(first) + if (r.role == TownRole.GUARD) GUARD_AFTER else 0.0))
                    }
                }
                pairs.sortBy { it.score }
                val given = HashSet<Int>()
                for (pair in pairs) {
                    if (pair.run.keeper >= 0 || pair.id in given) continue
                    pair.run.keeper = pair.id
                    kept[pair.run.round] = pair.id
                    given.add(pair.id)
                }
            }
            if (!kept.contentEquals(data[center] ?: IntArray(0))) data[center] = kept
            // A farm's lamps: its own farmer's
            val all = residents.of(center)
            for (run in town.runs) {
                if (run.kind != LampTown.Kind.FARM) continue
                run.keeper = all.filter { it.alive && it.home == run.farm && it.role == TownRole.FARMER }.minByOrNull { it.id }?.id ?: -1
            }
        }

        /** How much further off than anyone else a guard is reckoned (in blocks squared): a guard only if there's nobody else. */
        private const val GUARD_AFTER = 1.0e9

        /** When [run]'s farmer comes in from the fields on the evening of [night] (ticks since noon), if it has one. */
        fun farmerIn(level: ServerLevel, run: LampTown.Run, night: Long): Int? = farmerAt(level, run, night, Doing.SUPPER)

        /** When [run]'s farmer goes out to the fields on the morning after [night], if it has one. */
        fun farmerOut(level: ServerLevel, run: LampTown.Run, night: Long): Int? = farmerAt(level, run, night + 1, Doing.WORK)

        private fun farmerAt(level: ServerLevel, run: LampTown.Run, day: Long, doing: Doing): Int? {
            if (run.keeper < 0) return null
            val r = Residents.get(level)[run.keeper] ?: return null
            val stretch = Days.plan(r, day, lessons = false, nightWatch = false).stretches.firstOrNull { it.doing == doing } ?: return null
            // A day's offsets count from two in the morning (see Days); the lamps' from noon
            return Math.floorMod(stretch.start - 10000, 24000)
        }
    }
}
