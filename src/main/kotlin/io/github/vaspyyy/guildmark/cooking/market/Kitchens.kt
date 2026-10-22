package io.github.vaspyyy.guildmark.cooking.market

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.shop.ShopRec
import io.github.vaspyyy.guildmark.economy.Markets
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/**
 * The ledger of the capitals' kitchens (living-world/cook/design.md 5.3): one record ([Rec]) for each capital, keyed by
 * its centre's `BlockPos.asLong()`, kept with the overworld as [io.github.vaspyyy.guildmark.economy.Suppliers] is. It holds
 * what each door sold today and who sold it, the carries, the first-of-kind masks, the pantry waiting to be served, the
 * request, the cookshops' records and the week of meal prices behind the index; and, for the whole world, the Marks owed
 * to players who were not there to be paid. A capital gets a record only when something is first sold, given or asked
 * of it, so a world that never cooks keeps nothing here and writes nothing.
 */
class Kitchens(
    private val recs: MutableMap<Long, Rec> = HashMap(),
    /** Whole Marks earned at a back door that could not be handed over, by player (7.7). */
    val owing: MutableMap<UUID, MutableList<Owing>> = HashMap(),
) : SavedData() {
    /** The record of the capital kept under [capital], if it has one. */
    fun rec(capital: Long): Rec? = recs[capital]

    /** The record of [capital], started if it has none. */
    fun recOrNew(capital: Long): Rec = recs.getOrPut(capital) { Rec(capital) }

    /** Every capital's record. */
    fun all(): Collection<Rec> = recs.values

    fun changed() = setDirty()

    /** Sells the dish in [stack] to the keeper of [door] in [capital] for [player], taking what is bought off the stack in one go (7.7; stub: nothing taken). */
    fun sell(level: ServerLevel, capital: Long, door: Door, player: ServerPlayer, stack: ItemStack): Sale =
        Sale(0, 0, false, Tier.PLAIN, Stage.FRESH, null, false)

    /** The three kinds the town wants on [day] (5.2; stub: none). */
    fun wanted(level: ServerLevel, capital: Long, day: Long): List<DishKind> = emptyList()

    /** The request of [day] (5.4; stub: none). */
    fun request(level: ServerLevel, capital: Long, day: Long): Request? = null

    /**
     * Puts [lot] in the pantry of [door] (the inn's, the bakery's or the alms table's), stamped with today's date if it
     * has none. When the door already holds [Rec.LOTS] lots the oldest two of one kind and tier become one, else the
     * oldest is eaten. A cookshop has no pantry.
     */
    fun addLot(level: ServerLevel, capital: Long, door: Door, lot: Lot) {
        if (lot.portions <= 0 || door == Door.SHOP) return
        val rec = recOrNew(capital)
        if (lot.day == NO_DAY) lot.day = Cooking.today(level)
        put(rec.lots[door.ordinal], lot)
        setDirty()
    }

    /** The lots in [door]'s pantry, oldest first. */
    fun lots(capital: Long, door: Door): List<Lot> = recs[capital]?.lots?.get(door.ordinal) ?: emptyList()

    /** The cookshops' records of [capital] (6.4), by owner. */
    fun shops(capital: Long): MutableMap<UUID, ShopRec> = recOrNew(capital).shops

    /** [lot] joins [list]: with the same cook's lot of the same day if there is one, at the end if not, and the oldest are merged or eaten when the pantry is over full. */
    private fun put(list: MutableList<Lot>, lot: Lot) {
        val same = list.lastOrNull { it.kind == lot.kind && it.tier == lot.tier && it.cook == lot.cook && it.day == lot.day }
        if (same != null) {
            same.portions += lot.portions
            return
        }
        list.add(lot)
        while (list.size > Rec.LOTS) {
            var merged = false
            search@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (list[i].kind != list[j].kind || list[i].tier != list[j].tier) continue
                    list[i].portions += list[j].portions
                    list.removeAt(j)
                    merged = true
                    break@search
                }
            }
            if (!merged) list.removeAt(0)
        }
    }

    companion object {
        private val OWING: Codec<Owing> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("door", Door.INN.ordinal).forGetter { it.door.ordinal },
                Codec.STRING.optionalFieldOf("kind").forGetter { Optional.ofNullable(it.kind?.id) },
                Codec.INT.optionalFieldOf("marks", 0).forGetter { it.marks },
            ).apply(i) { door, kind, marks -> Owing(Door.entries.getOrElse(door) { Door.INN }, kind.map { DishKind.byId(it) }.orElse(null), marks) }
        }

        private val CODEC: Codec<Kitchens> = RecordCodecBuilder.create { i ->
            i.group(
                Rec.CODEC.listOf().optionalFieldOf("capitals", listOf()).forGetter { k -> k.recs.values.filter { !it.blank() } },
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, OWING.listOf()).optionalFieldOf("owed", mapOf()).forGetter { k -> k.owing.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() } },
            ).apply(i) { list, owed ->
                Kitchens(
                    list.associateBy { it.key }.toMutableMap(),
                    owed.mapValues { (_, v) -> v.filter { it.marks > 0 }.toMutableList() }.toMutableMap(),
                )
            }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "kitchens"), Supplier { Kitchens() }, CODEC)

        /** The ledger, kept with the overworld. */
        fun get(level: ServerLevel): Kitchens = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /**
         * One market hour of the kitchens (5.3): the meal-hour serving and the cookshops' selling hours, called by
         * [Markets.step] just before the economy works the hour out, and what it answers is the portions served, which
         * the town does not eat as plain meals (stub: 0, nothing served).
         */
        fun step(level: ServerLevel, entry: Markets.Entry, hour: Long): Double = 0.0
    }
}
