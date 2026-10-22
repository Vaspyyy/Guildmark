package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.townsfolk.Names
import net.minecraft.core.BlockPos
import kotlin.random.Random

/**
 * The pedlar's roster and his rounds (the villages design, sections 7.4, 7.5 and 7.9), all of it plain arithmetic on the bell
 * and the day so that the same village gets the same answer however it is asked and whenever.
 *
 * - **Who he is.** A region of the country (a square of [REGION] blocks) has four pedlars, named and looked for from the
 *   region's own seed; a village's is the one of the four its bell picks. So the same few come back to the same villages and
 *   a player learns them, and two villages down the road may share one.
 * - **When he comes.** On a round every [GAP_LEAST] to [GAP_MOST] days (drawn afresh at each visit from the bell and the number
 *   of visits), and always on the village's market day; never on the rest day, which only puts a due round off to the next day. A
 *   linked village a wagon has called on within [WAGON_DAYS] days has the wagon for its supply, and the pedlar then comes on
 *   market day alone. On a road the guild has closed he does not come. With a band at large camped near, one visit in
 *   [BAND_ONE_IN] is lost, drawn from the bell and the day.
 */
object PedlarRoster {
    /** Days between one round and the next, least and most. */
    const val GAP_LEAST = 2
    const val GAP_MOST = 4

    /** While a band is camped near, one visit in this many is lost. */
    const val BAND_ONE_IN = 4

    /** A wagon that has called on a village within this many days is its supply; the pedlar then comes on market day alone. */
    const val WAGON_DAYS = 4

    /** Days a village goes without a pedlar after one was killed. */
    const val SLAIN_DAYS = 7

    /** A square of this many blocks each way is a region with its four pedlars (about eight villages of the country's spacing). */
    const val REGION = 1024

    /** "No day" and "no hour", for the cart's saved fields. */
    const val NONE = Long.MIN_VALUE / 2

    /** The hours of the clock of his day (section 7.5): in sight on the road, at the green, packing, gone. */
    const val WALK_IN = 8.5
    const val SET_UP = 9.0
    const val PACK_UP = 16.0
    const val GONE = 16.5

    /** The clock hour his call is made in the market's record: the hour the three daily caravans' first comes in (Economy.caravan). */
    const val CALL_CLOCK = 9

    /** Why he comes today or does not (the cart command says it). */
    enum class Why(val id: String, val comes: Boolean) {
        /** On his round. */
        ROUND("round", true),

        /** It is the village's market day. */
        MARKET("market", true),

        /** The rest day: no pedlar. A round that falls on it is put off a day. */
        REST("rest", false),

        /** Neither a market day nor his round's day. */
        NOT_DUE("not_due", false),

        /** A wagon has called lately and the road is the village's supply. */
        WAGON("wagon", false),

        /** The road is closed. */
        CLOSED("closed", false),

        /** A band camped near kept him off. */
        BAND("band", false),

        /** An op held this visit back (`village cart skip`). */
        HELD("held", false),

        /** The pedlar was killed: his place is kept empty for a week and then another is named. */
        SLAIN("slain", false),

        /** The pedlar's switch is off. */
        OFF("off", false),
        ;

        companion object {
            fun of(id: String): Why? = entries.firstOrNull { it.id == id }
        }
    }

    /** Days from a visit to the next round: [GAP_LEAST] to [GAP_MOST], drawn from the bell and the number of visits so far. */
    fun gap(bell: Long, visits: Int): Int =
        GAP_LEAST + Random(bell * -0x61c8864680b583ebL xor visits.toLong() * 0x2545F4914F6CDD1DL).nextInt(GAP_MOST - GAP_LEAST + 1)

    /** Whether a band's reach loses the visit of [day] (one in [BAND_ONE_IN]). */
    fun bandLoses(bell: Long, day: Long): Boolean =
        Random(bell * 0x632BE59BD9B4E019L xor day * -0x4b47e1ab8d3b1d1bL).nextInt(BAND_ONE_IN) == 0

    /**
     * Whether he comes on [day], and why. [rest]: the rest day; [market]: the village's market day; [next]: the day his round is next
     * due ([NONE] before his first); [wagon]: a wagon has called lately; [closed]: every road to the village is closed; [band]: a band
     * is camped near; [held]: visits an op has held back; [away]: his place is empty after a pedlar was killed.
     */
    fun verdict(
        bell: Long, day: Long, rest: Boolean, market: Boolean, next: Long, wagon: Boolean, closed: Boolean, band: Boolean, held: Int,
        away: Boolean = false,
    ): Why {
        if (rest) return Why.REST
        if (closed) return Why.CLOSED
        if (away) return Why.SLAIN
        val round = round(day, next)
        if (!market && !round) return Why.NOT_DUE
        if (!market && wagon) return Why.WAGON
        if (held > 0) return Why.HELD
        if (band && bandLoses(bell, day)) return Why.BAND
        return if (market) Why.MARKET else Why.ROUND
    }

    /** Whether his round is due on [day]: [next] has come, or he has never been. */
    fun round(day: Long, next: Long): Boolean = next == NONE || day >= next

    /** A pedlar: his given name, his family name and his look (one of the merchants' reserved looks, 4 and 5). */
    class Person(val given: String, val family: String, val look: Int, val woman: Boolean) {
        val name: String get() = "$given $family"
    }

    /** The region [bell] lies in, as the pair of its squares' numbers. */
    fun region(bell: Long): Pair<Int, Int> {
        val x = BlockPos.getX(bell)
        val z = BlockPos.getZ(bell)
        return Math.floorDiv(x, REGION) to Math.floorDiv(z, REGION)
    }

    /** The four pedlars of a region, the same each time. */
    fun roster(region: Pair<Int, Int>): List<Person> {
        val seed = region.first * 73856093L xor region.second * 19349663L
        val used = HashSet<String>()
        return List(4) { i ->
            val look = 4 + i % 2
            val s = seed * 31L + i
            // The looks the art gives them: 4 a man, 5 a woman (villages design, section 17.2)
            val woman = look == 5
            var given = Names.given(null, woman, s)
            var tries = 0
            while (!used.add(given) && tries++ < 8) given = Names.given(null, woman, s + 1000L * tries)
            Person(given, Names.family(null, s), look, woman)
        }
    }

    /** The pedlar of the village whose bell is [bell]; after [slain] have been killed there, the next of the region's four takes his place. */
    fun of(bell: Long, slain: Int = 0): Person {
        val people = roster(region(bell))
        return people[Math.floorMod((bell * -0x61c8864680b583ebL ushr 33) + slain, 4L).toInt()]
    }
}
