package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.recipe.Tastes
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.server.level.ServerLevel
import java.util.Random

/**
 * What the inn wants and asks for (living-world/cook/design.md 5.2, 5.4): the three kinds chalked on the slate each day and
 * the request of the day. Neither is saved; both are drawn from the world's seed, the capital and the town's day, so the
 * slate, the innkeeper's talk, the command and the sale all read the same answer.
 *
 * The three wanted kinds are three of the inn's ten kinds (the table of kinds sends loaf and strips to the bakery alone),
 * picked one at a time without putting any back; a kind the season favours counts twice as much, and in lean or hard times
 * broth and pottage do too. The request is one of the twelve kinds, as the model draws it (`cook_model.py`): 5 portions.
 */
object Requests {
    /** The portions a request asks for. */
    const val PORTIONS = 5

    /** The kinds the inn wants each day. */
    const val WANTED = 3

    /** The kinds the inn can want: those the table of kinds sends to it. */
    val INN: List<DishKind> = DishKind.entries.filter { Door.INN in it.doors }

    /** How much more likely the season's good kinds, and broth and pottage in lean times, are to be wanted. */
    const val LEANING = 2.0

    /** The standing a request goes to: the innkeeper gives her special orders to cooks she knows (6.3, and `ladder.py`'s `REQUEST_RANK`): Known, 30 points. */
    val RANK: Int = SupplierRank.KNOWN.ordinal

    /** Whether a cook of standing [rank] may fill the request. With standing switched off nobody has a rank, so then the request is anyone's. */
    fun open(rank: Int): Boolean = rank >= RANK || !Suppliers.enabled()

    /** The season on the town's [day], or null with seasons off (then no kind is the season's). */
    fun season(level: ServerLevel, day: Long): Season? = if (Seasons.enabled()) Seasons.dateOn(level.server, day).season else null

    /** How likely [kind] is to be wanted when the season is [season] and the times [times], against 1 for a kind with nothing in its favour. */
    fun weight(kind: DishKind, season: Season?, times: Times): Double {
        var w = 1.0
        if (season != null && Tastes.season(kind, season) > 1.0) w *= LEANING
        if ((times == Times.LEAN || times == Times.HARD) && (kind == DishKind.BROTH || kind == DishKind.POTTAGE)) w *= LEANING
        return w
    }

    /** The three kinds the inn wants on [day] in the capital keyed [capital] of the world seeded [seed] (the same every time it is asked). */
    fun wanted(seed: Long, capital: Long, day: Long, season: Season?, times: Times): List<DishKind> {
        val rng = rng(seed, capital, day, 0, 1)
        val pool = INN.toMutableList()
        val picked = ArrayList<DishKind>(WANTED)
        repeat(minOf(WANTED, pool.size)) {
            val weights = DoubleArray(pool.size) { weight(pool[it], season, times) }
            var r = rng.nextDouble() * weights.sum()
            var i = 0
            while (i < pool.size - 1 && r >= weights[i]) {
                r -= weights[i]
                i++
            }
            picked.add(pool.removeAt(i))
        }
        return picked
    }

    /** The kind the inn asks for on [day]; [salt] is what `cook request new` changes to ask for another. */
    fun requested(seed: Long, capital: Long, day: Long, salt: Int): DishKind {
        val rng = rng(seed, capital, day, salt, 2)
        return DishKind.entries[rng.nextInt(DishKind.entries.size)]
    }

    /** A generator for one draw: the seed, the capital, the day, the request's salt and which draw it is, mixed so that nearby days are not alike. */
    private fun rng(seed: Long, capital: Long, day: Long, salt: Int, stream: Int): Random {
        var h = seed xor (capital * -0x61c8864680b583ebL)
        h = (h xor (h ushr 31)) * -0x40a7b892e31b1a47L
        h = h xor (day * 0x2545F4914F6CDD1DL)
        h = (h xor (h ushr 29)) * -0x6b2fb644ecceee15L
        h = h xor (salt.toLong() * 0x1B873593L + stream.toLong() * 0x7F4A7C15L)
        h = (h xor (h ushr 32)) * -0x61c8864680b583ebL
        return Random(h xor (h ushr 29))
    }
}
