package io.github.vaspyyy.guildmark.cooking.market

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.Verdict
import io.github.vaspyyy.guildmark.cooking.shop.ShopRec
import net.minecraft.core.UUIDUtil
import java.util.Optional
import java.util.UUID

/*
 * The records the ledger passes round and keeps (living-world/cook/design.md 5.3, 12.3). Part G's file: the shapes the
 * other parts call ([Lot], [Sale], [Request]) and what the ledger saves ([Rec], [Owing]) with their codecs. Everything
 * saved is optional with a default, so an old world, or one that never cooked, loads and writes nothing.
 */

/** The day of something that has not happened: before any day there is. */
const val NO_DAY: Long = Long.MIN_VALUE / 4

/**
 * A lot in a door's pantry: [portions] of [kind] at [tier] bought or given and not yet served, cooked by [cook]
 * ([cookName]). [day] is the town's day it came in (the ledger stamps it when the lot is added), which is how a lot more
 * than two days old is told at the 2:00 reckoning.
 */
class Lot(val kind: DishKind, val tier: Tier, val cook: UUID?, val cookName: String, var portions: Int, var day: Long = NO_DAY) {
    companion object {
        val CODEC: Codec<Lot> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("kind").forGetter { it.kind.id },
                Codec.INT.optionalFieldOf("tier", Tier.PLAIN.ordinal).forGetter { it.tier.ordinal },
                UUIDUtil.CODEC.optionalFieldOf("cook").forGetter { Optional.ofNullable(it.cook) },
                Codec.STRING.optionalFieldOf("name", "").forGetter { it.cookName },
                Codec.INT.optionalFieldOf("n", 0).forGetter { it.portions },
                Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter { it.day },
            ).apply(i) { kind, tier, cook, name, n, day ->
                // A kind this version does not know is a lot of nothing, which the ledger drops
                val known = DishKind.byId(kind)
                Lot(known ?: DishKind.POTTAGE, Tier.of(tier), cook.orElse(null), name, if (known == null) 0 else n.coerceAtLeast(0), day)
            }
        }
    }
}

/**
 * What one sale came to: [taken] portions off the stack for [marks] whole Marks, whether it was the [first] of its kind
 * sold in the town (paid double), the [tier] and [stage] it was judged at, the keeper's [verdict] on the mix (null when
 * none was given), and whether the door is [full] for the day.
 */
class Sale(val taken: Int, val marks: Int, val first: Boolean, val tier: Tier, val stage: Stage, val verdict: Verdict?, val full: Boolean)

/** A capital's request of the day: [portions] of [kind], and whether it is [filled]. */
class Request(val kind: DishKind, val portions: Int, val filled: Boolean)

/**
 * Whole Marks earned at a back door that could not be handed over (the player left in the middle of the sale, or was
 * not alive to take them): [marks] for [kind] at [door], paid at the player's next chance (5.3, 7.7).
 */
class Owing(val door: Door, val kind: DishKind?, var marks: Int)

/** The request's saved state for one day: [salt] is what `cook request new` changes, so the draw is another. */
class Asked(val day: Long, val salt: Int, val filled: Boolean, val by: UUID?) {
    companion object {
        val CODEC: Codec<Asked> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter { it.day },
                Codec.INT.optionalFieldOf("salt", 0).forGetter { it.salt },
                Codec.BOOL.optionalFieldOf("filled", false).forGetter { it.filled },
                UUIDUtil.CODEC.optionalFieldOf("by").forGetter { Optional.ofNullable(it.by) },
            ).apply(i) { day, salt, filled, by -> Asked(day, salt, filled, by.orElse(null)) }
        }
    }
}

/**
 * One capital's record in the ledger (5.3): what each door sold today, who sold where lately, what each cook has taken
 * and carries, the pantry, the request, the cookshops' records and the seven days of meal prices behind the index. The
 * counters are whole portions: a door's cap is rounded to the nearest portion where it is told (see [Doors]).
 */
class Rec(val key: Long) {
    /** The town's day the counters below are for. */
    var day: Long = NO_DAY

    /** Portions sold today, by door (the ordinal of [Door]) and kind (the ordinal of [DishKind]). */
    val sold: Array<IntArray> = Array(DOORS) { IntArray(KINDS) }

    /** By door: the last town's day each cook sold there (for the 70% rule: another cook in the last two days). */
    val seen: Array<MutableMap<UUID, Long>> = Array(DOORS) { HashMap() }

    /** Portions each cook has taken at each door today. */
    val mine: MutableMap<UUID, IntArray> = HashMap()

    /** The fraction of a Mark each cook carries at each door: what has been earned and not yet paid. */
    val carry: MutableMap<UUID, DoubleArray> = HashMap()

    /** The kinds each cook has had the first-of-kind fee for here, as a 12-bit mask. */
    val taster: MutableMap<UUID, Int> = HashMap()

    /** The pantry of each door, oldest first, at most [LOTS] lots. */
    val lots: Array<MutableList<Lot>> = Array(DOORS) { ArrayList() }

    /** Portions of Gruel in the alms table's pot, which have no kind and so no lot. */
    var gruel: Int = 0

    /** Portions the cookshops sold since the last meal hour, which the next one adds to what the town ate. */
    var pending: Double = 0.0

    /** The request's state for a day. */
    var asked: Asked = Asked(NO_DAY, 0, false, null)

    /** The cookshops' records, by owner (6.4). */
    val shops: MutableMap<UUID, ShopRec> = HashMap()

    /** The meal price, as a multiple of the plain meal's, of each of the last [Pricing.WINDOW] hours (0: not seen), and the hour last written. */
    val ring: FloatArray = FloatArray(Pricing.WINDOW)
    var ringHour: Long = NO_DAY

    /** What the town ate of the dishes at the last three meal hours, by kind (not saved). */
    val served: IntArray = IntArray(KINDS)

    /** Whether there is nothing here worth writing down. */
    fun blank(): Boolean = day == NO_DAY && gruel == 0 && pending == 0.0 && shops.isEmpty() && asked.day == NO_DAY && ringHour == NO_DAY &&
        lots.all { it.isEmpty() } && carry.isEmpty() && taster.isEmpty() && mine.isEmpty() && seen.all { it.isEmpty() } && sold.all { row -> row.all { it == 0 } }

    /** The portions sold today at [door], of every kind. */
    fun soldAt(door: Door): Int = sold[door.ordinal].sum()

    companion object {
        /** The rows of the counters: one for each [Door]. */
        val DOORS: Int = Door.entries.size
        val KINDS: Int = DishKind.entries.size

        /** The most lots a door's pantry holds (when it is full the oldest of a kind and tier are merged). */
        const val LOTS = 12

        private val SEEN: Codec<Map<UUID, Long>> = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)

        val CODEC: Codec<Rec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("key").forGetter { it.key },
                Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter { it.day },
                Codec.INT.listOf().optionalFieldOf("sold", listOf()).forGetter { r -> r.sold.flatMap { it.toList() } },
                SEEN.listOf().optionalFieldOf("seen", listOf()).forGetter { r -> r.seen.map { it.toMap() } },
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT.listOf()).optionalFieldOf("mine", mapOf()).forGetter { r -> r.mine.mapValues { it.value.toList() } },
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.DOUBLE.listOf()).optionalFieldOf("carry", mapOf()).forGetter { r -> r.carry.mapValues { it.value.toList() } },
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("taster", mapOf()).forGetter { it.taster },
                Lot.CODEC.listOf().listOf().optionalFieldOf("lots", listOf()).forGetter { r -> r.lots.map { it.toList() } },
                Codec.INT.optionalFieldOf("gruel", 0).forGetter { it.gruel },
                Codec.DOUBLE.optionalFieldOf("pending", 0.0).forGetter { it.pending },
                Asked.CODEC.optionalFieldOf("asked", Asked(NO_DAY, 0, false, null)).forGetter { it.asked },
                ShopRec.CODEC.listOf().optionalFieldOf("shops", listOf()).forGetter { r -> r.shops.values.toList() },
                Codec.FLOAT.listOf().optionalFieldOf("ring", listOf()).forGetter { r -> if (r.ringHour == NO_DAY) listOf() else r.ring.toList() },
                Codec.LONG.optionalFieldOf("ring_hour", NO_DAY).forGetter { it.ringHour },
            ).apply(i) { key, day, sold, seen, mine, carry, taster, lots, gruel, pending, asked, shops, ring, ringHour ->
                val r = Rec(key)
                r.day = day
                for (d in 0 until DOORS) for (k in 0 until KINDS) r.sold[d][k] = sold.getOrElse(d * KINDS + k) { 0 }.coerceAtLeast(0)
                for (d in 0 until DOORS) seen.getOrNull(d)?.let { r.seen[d].putAll(it) }
                for ((p, row) in mine) r.mine[p] = IntArray(DOORS) { row.getOrElse(it) { 0 }.coerceAtLeast(0) }
                for ((p, row) in carry) r.carry[p] = DoubleArray(DOORS) { row.getOrElse(it) { 0.0 }.coerceIn(0.0, 0.999999) }
                r.taster.putAll(taster)
                for (d in 0 until DOORS) lots.getOrNull(d)?.let { list -> r.lots[d].addAll(list.filter { it.portions > 0 }.take(LOTS)) }
                r.gruel = gruel.coerceAtLeast(0)
                r.pending = pending.coerceAtLeast(0.0)
                r.asked = asked
                for (s in shops) r.shops[s.owner] = s
                if (ring.isNotEmpty() && ringHour != NO_DAY) {
                    for (h in 0 until Pricing.WINDOW) r.ring[h] = ring.getOrElse(h) { 0f }.coerceAtLeast(0f)
                    r.ringHour = ringHour
                }
                r
            }
        }
    }
}
