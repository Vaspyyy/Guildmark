package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.roundToInt

/**
 * How well a capital knows a player as a supplier, from a stranger to its purveyor: the [points] it takes, how many
 * orders a player may hold at once ([orders]: counted across every capital, by their best standing anywhere), and
 * what it adds to the share of its price a stall pays them for their goods ([bonus], on top of [Lots.SELL_SHARE]).
 * The points are set against the markets' real prices, so that someone farming steadily and filling the orders that
 * come up reaches Known Supplier in a day and Trusted Supplier in one to two weeks (two to six days in winter, when
 * the stalls take more), and a homesteader with beasts besides in three to six days.
 */
enum class SupplierRank(val id: String, val points: Int, val orders: Int, val bonus: Double) {
    STRANGER("stranger", 0, 1, 0.0),
    GROWER("grower", 12, 1, 0.02),
    KNOWN("known", 30, 2, 0.04),
    TRUSTED("trusted", 150, 3, 0.06),
    PURVEYOR("purveyor", 500, 4, 0.08);

    /** The lang key of its name ("Known Supplier"); the purveyor's takes the capital's name. */
    val key: String get() = "supplier.guildmark.rank.$id"

    /** The next rank up, if there is one. */
    val next: SupplierRank? get() = entries.getOrNull(ordinal + 1)

    /** Its name, at the capital called [place]. */
    fun title(place: String): Component = Component.translatable(key, place)

    companion object {
        /** The rank [points] have earned. */
        fun of(points: Double): SupplierRank = entries.last { points >= it.points }
    }
}

/** [count] of [good] a player bought at a capital's market on the town's [day] (see [Days.dayOf]) and hasn't sold back since. */
class Bought(val day: Long, val good: Good, var count: Int)

/**
 * One player's standing at one capital: their [points], how many orders they've [filled] there and let [lapsed], the
 * town's day they last traded there ([day]), the highest rank a keeper there has greeted them at ([greeted]), and what
 * they've bought at its market lately and not sold back ([bought]).
 */
class Standing(var points: Double = 0.0, var filled: Int = 0, var lapsed: Int = 0, var day: Long = NEVER, var greeted: Int = 0) {
    val bought = ArrayList<Bought>()

    val rank: SupplierRank get() = SupplierRank.of(points)

    /** How many of [good] they bought on the town's [since] day or later, as remembered on [today]. */
    fun bought(good: Good, since: Long, today: Long): Int {
        forget(today)
        return bought.filter { it.good == good && it.day >= since }.sumOf { it.count }
    }

    /** What they bought more than [Suppliers.KEPT] days before [today] is forgotten; a day ahead of it (time set back) counts as today. */
    fun forget(today: Long) {
        bought.removeAll { it.day <= today - Suppliers.KEPT || it.count <= 0 }
        for (i in bought.indices) if (bought[i].day > today) bought[i] = Bought(today, bought[i].good, bought[i].count)
    }

    companion object {
        const val NEVER = Long.MIN_VALUE / 4
    }
}

/**
 * Every player's standing as a supplier at every capital (see [SupplierRank]), kept with the overworld. Points come
 * from Marks earned there: one for each Mark an order pays, and one for each Mark from selling at its stalls, from a
 * stall of their own there or from shipping to it, a bonus for an order filled in full and in time, and a loss for
 * one let lapse. The better a player's standing, the more a capital's stalls pay them, the more orders they may
 * hold, and the more its businesses offer them. Every Mark earned dealing at a market so also grows the Trade skill
 * (see [dealt]), standing switched on or off.
 *
 * Each capital also remembers what a player bought at its market in the last [WEEK] days: those goods can't be
 * delivered to its orders or set out on a stall of the player's own there, and selling them back earns no standing
 * (see [sold]). Standing is off with `supplierOrders` (see [enabled]): no bonus, no orders, the stalls as they always
 * were. What was bought is remembered whenever the markets run, as players' own stalls need it either way.
 *
 * Players' own stalls and their homesteads' shipping hear of standing through [SupplierLink] (see [link]).
 */
class Suppliers(private val capitals: MutableMap<Long, MutableMap<UUID, Standing>> = HashMap()) : SavedData() {
    /** [player]'s standing at the capital whose centre is [capital], if they have any. */
    operator fun get(capital: BlockPos, player: UUID): Standing? = capitals[capital.asLong()]?.get(player)

    /** [player]'s standing at [capital], started afresh if they have none yet. */
    fun of(capital: BlockPos, player: UUID): Standing =
        capitals.getOrPut(capital.asLong()) { HashMap() }.getOrPut(player) { Standing() }

    /** Every capital [player] has standing at (by its centre), best first. */
    fun everywhere(player: UUID): List<Pair<BlockPos, Standing>> =
        capitals.mapNotNull { (capital, players) -> players[player]?.let { BlockPos.of(capital) to it } }.sortedByDescending { it.second.points }

    fun changed() = setDirty()

    companion object {
        /** Days a capital remembers what a player bought at its market, for its own sake, and for its orders (at most). */
        const val WEEK = 7L
        const val KEPT = 10L

        /** Points for each Mark earned selling at the stalls, from a stall of one's own or by shipping (an order's Marks count whole too). */
        const val SOLD = 1.0

        /** Trade skill XP for each Mark earned dealing at a market, as a contract gives for each Mark of its reward. */
        const val TRADE_XP = 2.0

        private val BOUGHT: Codec<Triple<Long, String, Int>> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("day").forGetter { it.first },
                Codec.STRING.fieldOf("good").forGetter { it.second },
                Codec.INT.fieldOf("count").forGetter { it.third },
            ).apply(i) { day, good, count -> Triple(day, good, count) }
        }

        private class Saved(val player: UUID, val standing: Standing)

        private val PLAYER: Codec<Saved> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Saved::player),
                Codec.DOUBLE.optionalFieldOf("points", 0.0).forGetter { it.standing.points },
                Codec.INT.optionalFieldOf("filled", 0).forGetter { it.standing.filled },
                Codec.INT.optionalFieldOf("lapsed", 0).forGetter { it.standing.lapsed },
                Codec.LONG.optionalFieldOf("day", Standing.NEVER).forGetter { it.standing.day },
                Codec.INT.optionalFieldOf("greeted", 0).forGetter { it.standing.greeted },
                BOUGHT.listOf().optionalFieldOf("bought", listOf()).forGetter { s -> s.standing.bought.map { Triple(it.day, it.good.id, it.count) } },
            ).apply(i) { player, points, filled, lapsed, day, greeted, bought ->
                val standing = Standing(points, filled, lapsed, day, greeted)
                for ((d, good, count) in bought) Good.byId(good)?.let { standing.bought.add(Bought(d, it, count)) }
                Saved(player, standing)
            }
        }

        private val CAPITAL: Codec<Pair<Long, List<Saved>>> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter { it.first },
                PLAYER.listOf().fieldOf("players").forGetter { it.second },
            ).apply(i) { capital, players -> capital to players }
        }

        private val CODEC: Codec<Suppliers> = RecordCodecBuilder.create { i ->
            i.group(
                CAPITAL.listOf().optionalFieldOf("capitals", listOf()).forGetter { s ->
                    s.capitals.map { (capital, players) -> capital to players.map { (uuid, standing) -> Saved(uuid, standing) } }
                },
            ).apply(i) { list ->
                Suppliers(list.associate { (capital, players) -> capital to players.associate { it.player to it.standing }.toMutableMap() }.toMutableMap())
            }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "suppliers"), Supplier { Suppliers() }, CODEC)

        fun get(level: ServerLevel): Suppliers = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Whether standing and orders are switched on (they need the markets running too). */
        fun enabled(): Boolean = Markets.enabled() && try {
            GuildmarkConfig.SUPPLIER_ORDERS.get()
        } catch (e: IllegalStateException) {
            true
        }

        /** The town's day it is now (see [Days.dayOf]). */
        fun today(level: ServerLevel): Long = Days.dayOf(level.overworldClockTime)

        // ---- For the rest of the game ----

        /** [player]'s rank at the capital whose centre is [capital]: 0 a stranger up to 4 its purveyor (0 with standing switched off). */
        fun rank(level: ServerLevel, capital: BlockPos, player: UUID): Int {
            if (!enabled()) return 0
            return get(level)[capital, player]?.rank?.ordinal ?: 0
        }

        /** The share of its price [capital]'s stalls pay [player] for their goods: [Lots.SELL_SHARE] and their standing's bonus. */
        fun share(level: ServerLevel, capital: BlockPos, player: UUID): Double =
            Lots.SELL_SHARE + SupplierRank.entries[rank(level, capital, player)].bonus

        /**
         * [player] earned [marks] Guild Marks at [capital] selling there (from a stall of their own or by shipping to
         * it): a point of standing each, and the Trade skill's XP. For goods that might have come off its own stalls,
         * use [sold] instead.
         */
        fun earned(level: ServerLevel, capital: BlockPos, player: UUID, marks: Int) {
            if (marks <= 0) return
            dealt(level, player, marks.toDouble())
            if (!enabled()) return
            gain(level, capital, player, marks * SOLD)
        }

        /**
         * [player], if they're on, earned [marks] dealing at a market for goods they didn't buy there lately (sold at
         * its stalls, from a stall of their own, shipped to it, or delivered to its orders): [TRADE_XP] each of the
         * Trade skill.
         */
        fun dealt(level: ServerLevel, player: UUID, marks: Double) {
            if (marks <= 0.0) return
            level.server.playerList.getPlayer(player)?.let { Skills.gain(it, Skill.TRADE, marks * TRADE_XP) }
        }

        /**
         * [player] sold [capital]'s market [count] of [good] for [marks]: as many of them as they bought there in the
         * last [WEEK] days are taken to be those, which earn no standing and no Trade and are off the record now; the
         * rest earn a point a Mark and the Trade skill's XP (see [dealt]). Gives how many of them came off its own
         * stalls (none with standing switched off, though they come off the record and earn no Trade all the same).
         */
        fun sold(level: ServerLevel, capital: BlockPos, player: UUID, good: Good, count: Int, marks: Int): Int {
            if (count <= 0) return 0
            val data = get(level)
            val standing = data.of(capital, player)
            val today = today(level)
            standing.forget(today)
            var own = 0
            for (b in standing.bought.sortedBy { it.day }) {
                if (own >= count) break
                if (b.good != good || b.day <= today - WEEK) continue
                val n = minOf(b.count, count - own)
                b.count -= n
                own += n
            }
            standing.forget(today)
            standing.day = today
            data.setDirty()
            val earned = marks * (count - own).toDouble() / count
            dealt(level, player, earned)
            if (!enabled()) return 0
            if (earned > 0.0) gain(level, capital, player, earned * SOLD)
            return own
        }

        /** How many of [good] [player] bought at [capital]'s market in the last [WEEK] days (and hasn't sold back). */
        fun bought(level: ServerLevel, capital: BlockPos, player: UUID, good: Good): Int {
            val today = today(level)
            return get(level)[capital, player]?.bought(good, today - WEEK + 1, today) ?: 0
        }

        /**
         * Sets [SupplierLink]'s hooks (once, at start-up), so that players' own stalls and their homesteads' shipping
         * go by standing: [rank], [earned] and [bought]. With standing switched off, everyone counts as a Trusted
         * Supplier there (a stall may be rented, at the usual keep) and Marks earned count for nothing.
         */
        fun link() {
            SupplierLink.rank = { level, player, capital -> if (enabled()) rank(level, capital, player) else SupplierLink.TRUSTED }
            SupplierLink.earned = { level, player, capital, marks -> earned(level, capital, player, marks) }
            SupplierLink.bought = { level, player, capital, good -> bought(level, capital, player, good) }
        }

        // ---- Kept by the market and its orders ----

        /** [player] bought [count] of [good] at [capital]'s market just now (remembered with standing switched off too). */
        fun purchased(level: ServerLevel, capital: BlockPos, player: UUID, good: Good, count: Int) {
            if (count <= 0) return
            val data = get(level)
            val standing = data.of(capital, player)
            val today = today(level)
            standing.forget(today)
            val same = standing.bought.firstOrNull { it.day == today && it.good == good }
            if (same != null) same.count += count else standing.bought.add(Bought(today, good, count))
            standing.day = today
            data.setDirty()
        }

        /** How many of [good] [player] bought at [capital]'s market since the town's day [since] (and not sold back). */
        fun boughtSince(level: ServerLevel, capital: BlockPos, player: UUID, good: Good, since: Long): Int {
            val today = today(level)
            return get(level)[capital, player]?.bought(good, since, today) ?: 0
        }

        /** [player]'s best rank at any capital, which sets how many orders they may hold at once. */
        fun best(level: ServerLevel, player: UUID): SupplierRank =
            get(level).everywhere(player).maxOfOrNull { it.second.rank } ?: SupplierRank.STRANGER

        /**
         * [player]'s standing at [capital] moves by [points] (up or down, never below none). Gives the rank it was and
         * the rank it is, and tells the player (if they're on) when it changes.
         */
        fun gain(level: ServerLevel, capital: BlockPos, player: UUID, points: Double): Pair<SupplierRank, SupplierRank> {
            val data = get(level)
            val standing = data.of(capital, player)
            val before = standing.rank
            standing.points = (standing.points + points).coerceAtLeast(0.0)
            standing.day = today(level)
            data.setDirty()
            val after = standing.rank
            if (after != before) level.server.playerList.getPlayer(player)?.let { it.sendSystemMessage(changeOf(level, capital, before, after)) }
            return before to after
        }

        /** Sets [player]'s points at [capital] to [points] (for the commands). */
        fun set(level: ServerLevel, capital: BlockPos, player: UUID, points: Double): Pair<SupplierRank, SupplierRank> =
            gain(level, capital, player, points - (get(level)[capital, player]?.points ?: 0.0))

        /** The name of the capital whose centre is [capital], as its orders keep it, or else as its ground has it ("" if that isn't loaded). */
        fun place(level: ServerLevel, capital: BlockPos): String {
            Orders.get(level)[capital]?.name?.takeIf { it.isNotEmpty() }?.let { return it }
            return Capitals.covering(level, capital)?.let { VillageNames.of(it.boardAnchor) } ?: ""
        }

        /** Word of a change of rank at [capital], for the chat: what the new one brings. */
        private fun changeOf(level: ServerLevel, capital: BlockPos, before: SupplierRank, after: SupplierRank): Component {
            val place = place(level, capital)
            val name = after.title(place).copy().withStyle(ChatFormatting.GOLD)
            if (after < before) return Component.translatable("supplier.guildmark.down", name, place).withStyle(ChatFormatting.GRAY)
            val share = ((Lots.SELL_SHARE + after.bonus) * 100).roundToInt()
            val more = when (after) {
                SupplierRank.KNOWN -> Component.translatable("supplier.guildmark.up.known")
                SupplierRank.PURVEYOR -> Component.translatable("supplier.guildmark.up.purveyor")
                else -> Component.empty()
            }
            return Component.translatable("supplier.guildmark.up", name, place, share, after.orders, more).withStyle(ChatFormatting.YELLOW)
        }
    }
}
