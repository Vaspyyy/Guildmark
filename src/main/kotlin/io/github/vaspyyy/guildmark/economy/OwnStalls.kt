package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.floor

/**
 * A player's stall on one of a capital's market hall tables (see [OwnStalls]): who rents it ([renter], called [name],
 * in their [color], a [io.github.vaspyyy.guildmark.city.plan.Dye] ordinal), the market hours of rent still to run
 * ([left]), what's on it of each good (fractions and all, as the town buys a little at a time) and the Marks in its
 * till.
 */
class OwnStall(
    val renter: UUID,
    var name: String,
    var color: Int,
    var left: Int,
    val stock: DoubleArray = DoubleArray(Good.entries.size),
    var till: Double = 0.0,
    /** Marks taken since the hall's merchant last said how well it's doing. */
    var takings: Double = 0.0,
    /** Whether its renter has been told its rent is nearly out. */
    var warned: Boolean = false,
) {
    /** What it sold of each good in the hour last worked out, and since the town's day began (neither kept with the world). */
    val sold = DoubleArray(Good.entries.size)
    val today = DoubleArray(Good.entries.size)

    /** Of what it's sold, what its display still shows until the shoppers come for it (see [StallShoppers]; not kept). */
    val waiting = DoubleArray(Good.entries.size)

    /** Whole units of [good] on it. */
    fun whole(good: Good): Int = floor(stock[good.ordinal] + 1e-6).toInt()

    /** The goods with a whole unit or more on it, most first. */
    fun goods(): List<Good> = Good.TRADED.filter { whole(it) > 0 }.sortedByDescending { stock[it.ordinal] }
}

/** What a player's stall left with the hall's merchant when it was let go: its goods, whole, and the Marks from its till. */
class Kept(var name: String, val goods: IntArray = IntArray(Good.entries.size), var marks: Double = 0.0, var told: Boolean = false) {
    val empty: Boolean get() = goods.all { it <= 0 } && marks < 1.0
}

/** A capital's market hall as [OwnStalls] keeps it: each table's stall, if it's let, and what the merchant keeps for whom. */
class Hall(val capital: Long) {
    val stalls: Array<OwnStall?> = arrayOfNulls(OwnStalls.TABLES)
    val kept = LinkedHashMap<UUID, Kept>()

    /** [player]'s stall here and its table, if they rent one. */
    fun of(player: UUID): Int = stalls.indexOfFirst { it?.renter == player }

    val idle: Boolean get() = stalls.all { it == null } && kept.isEmpty()
}

/**
 * Players' own stalls: the tables of a capital's market hall, let a week at a time to its Trusted Suppliers (see
 * [SupplierLink]), each stocked from its renter's pack and selling to the town while they're away. Every market hour
 * a stall sells the town up to a third of what it bought there of each good the stall holds, at the market's price
 * less a tenth, into its till: the market's own stock covers the rest, so it never sells more than the town would
 * have bought anyway, and stalls holding the same good share what the town bought between them ([sell], from the
 * hourly simulation, so a market catching up after a sleep comes out as it would have hour by hour). Each hour also
 * runs a stall's rent down; once it's out the stall is let go, and its goods and till wait with the hall's merchant
 * until its renter comes for them. Kept with the overworld, by capital (its centre, as [Markets] keys them).
 *
 * The tables in the world, their displays and pennants are [StallTables]; the stall's screen and the merchant who
 * keeps what was let go are [StallDesk]; the shoppers who come for what's been sold, [StallShoppers].
 */
class OwnStalls(private val halls: MutableMap<Long, Hall> = HashMap()) : SavedData() {
    /** The hall of the capital whose centre is [capital], set up afresh if nothing was ever let there. */
    fun hall(capital: Long): Hall = halls.getOrPut(capital) { Hall(capital) }

    /** The hall of the capital whose centre is [capital], if anything was ever let there. */
    fun find(capital: Long): Hall? = halls[capital]

    fun all(): Collection<Hall> = halls.values

    fun changed() = setDirty()

    /** Forgets the halls with nothing let and nothing kept. */
    fun tidy() {
        if (halls.values.removeIf { it.idle }) setDirty()
    }

    companion object {
        /** The tables a market hall has to let. */
        const val TABLES = 4

        /** Market hours in a week's rent, and how far ahead of it a stall may be paid (a week more at most). */
        const val WEEK = 7 * 24
        const val AHEAD = WEEK

        /** A week's keep, in Guild Marks, and a Purveyor's. */
        const val KEEP = 8
        const val KEEP_PURVEYOR = 5

        /** Of what the town buys of a good in an hour, the most a stall sells it. */
        const val SHARE = 1.0 / 3.0

        /** The hall's cut of every sale: a stall sells at the market's price less this. */
        const val CUT = 0.1

        /** Market hours of rent left when its renter is told it's nearly out. */
        const val WARN = 24

        private val GOODS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
        private val COUNTS: Codec<Map<String, Int>> = Codec.unboundedMap(Codec.STRING, Codec.INT)

        private fun goods(values: DoubleArray): Map<String, Double> =
            Good.entries.filter { values[it.ordinal] > 1e-9 }.associate { it.id to values[it.ordinal] }

        private fun counts(values: IntArray): Map<String, Int> =
            Good.entries.filter { values[it.ordinal] > 0 }.associate { it.id to values[it.ordinal] }

        private val STALL: Codec<Pair<Int, OwnStall>> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("table").forGetter { it.first },
                UUIDUtil.CODEC.fieldOf("renter").forGetter { it.second.renter },
                Codec.STRING.optionalFieldOf("name", "").forGetter { it.second.name },
                Codec.INT.optionalFieldOf("color", 14).forGetter { it.second.color },
                Codec.INT.fieldOf("left").forGetter { it.second.left },
                GOODS.optionalFieldOf("stock", mapOf()).forGetter { goods(it.second.stock) },
                Codec.DOUBLE.optionalFieldOf("till", 0.0).forGetter { it.second.till },
                Codec.DOUBLE.optionalFieldOf("takings", 0.0).forGetter { it.second.takings },
                Codec.BOOL.optionalFieldOf("warned", false).forGetter { it.second.warned },
            ).apply(i) { table, renter, name, color, left, stock, till, takings, warned ->
                val s = OwnStall(renter, name, color, left, till = till, takings = takings, warned = warned)
                for ((id, n) in stock) Good.byId(id)?.let { s.stock[it.ordinal] = n }
                table to s
            }
        }

        private val KEPT: Codec<Pair<UUID, Kept>> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter { it.first },
                Codec.STRING.optionalFieldOf("name", "").forGetter { it.second.name },
                COUNTS.optionalFieldOf("goods", mapOf()).forGetter { counts(it.second.goods) },
                Codec.DOUBLE.optionalFieldOf("marks", 0.0).forGetter { it.second.marks },
                Codec.BOOL.optionalFieldOf("told", false).forGetter { it.second.told },
            ).apply(i) { player, name, goods, marks, told ->
                val k = Kept(name, marks = marks, told = told)
                for ((id, n) in goods) Good.byId(id)?.let { k.goods[it.ordinal] = n }
                player to k
            }
        }

        private val HALL: Codec<Hall> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(Hall::capital),
                STALL.listOf().optionalFieldOf("stalls", listOf()).forGetter { h -> h.stalls.withIndex().mapNotNull { (n, s) -> s?.let { n to it } } },
                KEPT.listOf().optionalFieldOf("kept", listOf()).forGetter { h -> h.kept.map { (p, k) -> p to k } },
            ).apply(i) { capital, stalls, kept ->
                Hall(capital).also { h ->
                    for ((table, s) in stalls) if (table in 0 until TABLES) h.stalls[table] = s
                    for ((p, k) in kept) h.kept[p] = k
                }
            }
        }

        private val CODEC: Codec<OwnStalls> = RecordCodecBuilder.create { i ->
            i.group(HALL.listOf().optionalFieldOf("halls", listOf()).forGetter { it.halls.values.toList() })
                .apply(i) { list -> OwnStalls(list.associateBy { it.capital }.toMutableMap()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "own_stalls"), Supplier { OwnStalls() }, CODEC)

        /** The world's, kept with the overworld whichever [level] asks. */
        fun get(level: ServerLevel): OwnStalls = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Whether tables are let and stalls sell (GuildmarkConfig ownStalls). */
        fun enabled(): Boolean = try {
            GuildmarkConfig.OWN_STALLS.get()
        } catch (e: IllegalStateException) {
            true
        }

        /**
         * What sells for the stalls of [entry]'s capital in its market's hours (see [Economy.hour]): null when stalls
         * are switched off or none is let there, so the hour goes as it always did.
         */
        fun seller(level: ServerLevel, entry: Markets.Entry): ((Market, Town) -> Unit)? {
            if (!enabled()) return null
            val data = get(level)
            val hall = data.find(entry.market.capital) ?: return null
            if (hall.stalls.all { it == null }) return null
            return { m, town ->
                sell(hall, m, town)
                data.setDirty()
            }
        }

        /**
         * One market hour of [hall]'s stalls, once [m] has worked out what [town] bought in it ([Market.drawn]): of
         * each good, every stall holding it sells up to [SHARE] of what was bought, shared fairly when there's not
         * enough for all, and the market gets back what the town had from them instead; each sale goes into the
         * stall's till at the market's price less [CUT]. Then each stall's rent runs an hour down, and one whose rent
         * is out is let go. Plain arithmetic on the hall and the market, so it comes out the same however it's run.
         */
        fun sell(hall: Hall, m: Market, town: Town) {
            val stalls = hall.stalls
            val newDay = Conditions(m.hour).clock == Economy.NEW_DAY
            for (s in stalls) {
                if (s == null) continue
                s.sold.fill(0.0)
                if (newDay) s.today.fill(0.0)
            }
            val wants = DoubleArray(stalls.size)
            for (g in Good.TRADED) {
                val bought = m.drawn[g.ordinal]
                if (bought < 1e-6) continue
                var any = false
                for (i in stalls.indices) {
                    val have = stalls[i]?.stock?.get(g.ordinal) ?: 0.0
                    wants[i] = if (have > 1e-6) minOf(have, bought * SHARE) else 0.0
                    if (wants[i] > 0.0) any = true
                }
                if (!any) continue
                val shares = fair(wants, bought)
                val total = shares.sum()
                if (total <= 0.0) continue
                // The town had these from the stalls, so the market's own stock didn't go down by them
                m.add(Site.MARKET, g, total)
                m.tally(Tally.SOLD, g, total)
                val each = m.price(town, g) * (1.0 - CUT)
                for (i in stalls.indices) {
                    val s = stalls[i] ?: continue
                    val n = shares[i]
                    if (n <= 0.0) continue
                    s.stock[g.ordinal] = (s.stock[g.ordinal] - n).coerceAtLeast(0.0)
                    s.sold[g.ordinal] += n
                    s.today[g.ordinal] += n
                    s.waiting[g.ordinal] += n
                    s.till += n * each
                    s.takings += n * each
                }
            }
            for (i in stalls.indices) {
                val s = stalls[i] ?: continue
                s.left--
                if (s.left <= 0) letGo(hall, i)
            }
        }

        /**
         * Shares of [pool], one for each of [wants]: each as much as it wants while the pool lasts, the rest shared
         * evenly among those wanting more, so none gets more than it asked and no one more than an even share when
         * the pool runs short. The smallest wants are met first; ties go by their order.
         */
        fun fair(wants: DoubleArray, pool: Double): DoubleArray {
            val out = DoubleArray(wants.size)
            val order = wants.indices.filter { wants[it] > 0.0 }.sortedBy { wants[it] }
            var left = pool
            var n = order.size
            for (i in order) {
                val take = minOf(wants[i], left / n)
                out[i] = take
                left -= take
                n--
            }
            return out
        }

        /**
         * [hall]'s stall at [table] is let go: its whole goods and its till go to the hall's merchant to keep for its
         * renter (what's left of a unit sold a little at a time is the town's), and the table is free.
         */
        fun letGo(hall: Hall, table: Int) {
            val s = hall.stalls[table] ?: return
            val k = hall.kept.getOrPut(s.renter) { Kept(s.name) }
            k.name = s.name
            for (g in Good.entries) k.goods[g.ordinal] += s.whole(g)
            k.marks += s.till
            k.told = false
            hall.stalls[table] = null
        }
    }
}
