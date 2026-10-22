package io.github.vaspyyy.guildmark.cooking.market

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.shop.CookShops
import io.github.vaspyyy.guildmark.cooking.shop.ShopRec
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
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
    /** Whole Marks earned at a back door and not yet in the player's hand, by player: written with the sale, taken off when paid ([Owed], 7.7). */
    val owing: MutableMap<UUID, MutableList<Owing>> = HashMap(),
) : SavedData() {
    /** The record of the capital kept under [capital], if it has one. */
    fun rec(capital: Long): Rec? = recs[capital]

    /** The record of [capital], started if it has none. */
    fun recOrNew(capital: Long): Rec = recs.getOrPut(capital) { Rec(capital) }

    /** Every capital's record. */
    fun all(): Collection<Rec> = recs.values

    fun changed() = setDirty()

    /**
     * Sells the dish in [stack] to the keeper of [door] in [capital] for [player] (7.7): every portion the door will take today
     * is taken off the stack and written into the ledger in this one step on the server thread, and the Sale says what they came
     * to. It pays nothing: the door counts the Marks out in the order of its scene, or [Owed.settle] does at once if the scene
     * is cut short. See [Selling] for the arithmetic, [Selling.quote] for what a sale would come to without making it.
     */
    fun sell(level: ServerLevel, capital: Long, door: Door, player: ServerPlayer, stack: ItemStack): Sale =
        Selling.commit(level, this, capital, player, stack, Selling.quote(level, this, capital, door, player, stack))

    /**
     * The three kinds the inn wants on [day] (5.2), drawn from the world's seed, the capital and the day with the season's
     * good kinds twice as likely (and broth and pottage in lean times): the same answer whenever it is asked, and nothing
     * saved. None while the back doors are off.
     */
    fun wanted(level: ServerLevel, capital: Long, day: Long): List<DishKind> {
        if (!Cooking.doors()) return emptyList()
        return Requests.wanted(level.seed, capital, day, Requests.season(level, day), Fortunes.times(level, capital))
    }

    /**
     * The request of [day] (5.4): one kind, 5 portions, and whether it is filled. It is drawn from the seed like the wanted
     * kinds, so only whether it was filled (and `cook request new`'s salt) is kept. None while the back doors are off.
     */
    fun request(level: ServerLevel, capital: Long, day: Long): Request? {
        if (!Cooking.doors()) return null
        val asked = recs[capital]?.asked?.takeIf { it.day == day }
        return Request(Requests.requested(level.seed, capital, day, asked?.salt ?: 0), Requests.PORTIONS, asked?.filled == true)
    }

    /** [capital]'s record brought to the town's [day] (and started if [create]), or null if it has none. */
    fun rolled(capital: Long, day: Long, create: Boolean = false): Rec? {
        val rec = (if (create) recOrNew(capital) else recs[capital]) ?: return null
        roll(rec, day)
        return rec
    }

    /**
     * Brings [rec] to the town's [day] if it is for another: the counters of the old day are zeroed, those who sold lately
     * are remembered for two days, and lots more than two days old are dropped (the town ate them). A clock set back is the
     * same without the dropping, so the lots are kept and the counters start again. Done the same wherever it is first noticed.
     */
    fun roll(rec: Rec, day: Long) {
        if (rec.day == day) return
        val back = rec.day != NO_DAY && day < rec.day
        for (row in rec.sold) row.fill(0)
        rec.mine.clear()
        for (seen in rec.seen) seen.values.removeIf { it < day - Doors.ABOUT }
        if (!back) for (list in rec.lots) list.removeIf { it.day != NO_DAY && day - it.day > LOT_DAYS }
        rec.day = day
        setDirty()
    }

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

    /**
     * Writes into the ledger what [player] gave at the alms table of [capital] (7.6): [portions] of a dish of [kind] and [tier] as
     * a lot of the alms door, or of Gruel ([kind] null), which has no kind, as a count of its own. Nothing is paid, and the market
     * is not told: the portions wait in the pantry and are served with the next meal hour like any door's, which is how the town
     * eats them, counted once.
     */
    fun gift(level: ServerLevel, capital: Long, player: ServerPlayer, kind: DishKind?, tier: Tier, portions: Int) {
        if (portions <= 0) return
        if (kind == null) {
            val rec = recOrNew(capital)
            rec.gruel = minOf(rec.gruel + portions, GRUEL_MOST)
            setDirty()
            return
        }
        addLot(level, capital, Door.ALMS, Lot(kind, tier, player.uuid, player.name.string, portions))
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
        /** A lot more than this many days old is dropped at the 2:00 reckoning: the town ate it. */
        private const val LOT_DAYS = 2L

        /** The most Gruel a capital's alms table keeps waiting to be served: three days' worth of the pot. */
        private const val GRUEL_MOST = 96

        private val OWING: Codec<Owing> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("door", Door.INN.ordinal).forGetter { it.door.ordinal },
                Codec.STRING.optionalFieldOf("kind").forGetter { Optional.ofNullable(it.kind?.id) },
                Codec.INT.optionalFieldOf("marks", 0).forGetter { it.marks },
                Codec.LONG.optionalFieldOf("capital", NO_KEY).forGetter { it.capital },
            ).apply(i) { door, kind, marks, capital -> Owing(Door.entries.getOrElse(door) { Door.INN }, kind.map { DishKind.byId(it) }.orElse(null), marks, capital) }
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
         * the town does not eat as plain meals. At the clock hours 8, 12 and 16 the cookshops sell ([CookShops.sellHour]) and
         * what they sold waits in the record for the next meal; at 7, 12 and 18 the doors' lots are served ([Pantry.serve]) with
         * those portions added. A capital that nothing was ever sold, given or asked of has no record and costs nothing here.
         *
         * Each hour is worked out for its own town's day, so hours caught up in order serve the lots as they would have been
         * served hour by hour; a record found to be for a later day than the clock's own (the clock was set back) has its
         * counters zeroed and its lots kept, as a sale would find it ([Kitchens.roll]).
         */
        fun step(level: ServerLevel, entry: Markets.Entry, hour: Long): Double {
            if (!Cooking.enabled()) return 0.0
            val kitchens = get(level)
            val capital = entry.market.capital
            val at = Conditions(hour)
            val clock = at.clock
            val sold = if (clock in Pantry.SHOP_CLOCKS && Cooking.shops()) CookShops.sellHour(level, entry, hour) else 0.0
            val rec = (if (sold > 0.0) kitchens.recOrNew(capital) else kitchens.rec(capital)) ?: return 0.0
            val today = Cooking.today(level)
            // A clock set back leaves the record in a day to come; an hour worked out ahead of the clock (the test commands) does not mean that
            if (rec.day != NO_DAY && rec.day > today && hour <= Markets.hourOf(level)) kitchens.roll(rec, today)
            if (rec.day == NO_DAY || at.day > rec.day) kitchens.roll(rec, at.day)
            val doors = Cooking.doors()
            if (doors) {
                Index.record(rec, entry, hour)
                kitchens.changed()
            }
            if (sold > 0.0) {
                rec.pending += sold
                kitchens.changed()
            }
            if (!Economy.meal(clock)) return 0.0
            val served = Pantry.serve(level, rec, entry, hour, at.day, clock, doors) + rec.pending
            rec.pending = 0.0
            kitchens.changed()
            return served
        }
    }
}
