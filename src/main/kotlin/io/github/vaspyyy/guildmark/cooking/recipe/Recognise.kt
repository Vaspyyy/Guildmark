package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import kotlin.math.max
import kotlin.math.min

/**
 * What a pot's contents are taken for (2.4): the [kind] (null is Gruel), how many [portions], the mix's [share] and its
 * [distance] from the ideal of that kind in this world (total variation, 0 to 1; 1.0 for Gruel, which has no ideal).
 */
class Recognised(val kind: DishKind?, val portions: Int, val share: DoubleArray, val distance: Double)

/** What [kind] a pot of [n] items is taken for, and how many [portions] it makes; [nearest] is the closest standard bill among the kinds open to the cook, [tv] its distance. */
class Pick(val kind: DishKind?, val portions: Int, val n: Int, val nearest: DishKind?, val tv: Double)

/**
 * Naming what is in a pot (living-world/cook/design.md 2.4), when the first stir (or shut door, or crank) starts the clock:
 *
 *  1. **Count.** Below half the standard count of the smallest kind the station makes (pottage 5, stew 4, chowder 4, broth 3,
 *     cakes 2, roots 2, pie 4, tart 4, roast 3, fish 2, loaf 4, strips 3) it is Gruel with `n / 2` portions (at least 1).
 *  2. **Recognise.** The shares of the mix are compared (total variation) with the *standard* shares, not the hidden
 *     ideal, of each kind the station makes and the cook has unlocked; the nearest wins if it is within [RECOGNISED_TV],
 *     else Gruel.
 *  3. **Portions.** `floor(kind.portions * n / standard count)`, at least 1, at most 1.5 times the kind's portions.
 */
object Recognise {
    /** A pot is taken for the nearest kind only if its mix is within this total variation of that kind's standard bill. */
    const val RECOGNISED_TV = 0.30

    /** How many times [run] has been asked since the game started, for `cook perf`. */
    var runs = 0L
        private set

    /** What [items] in a pot on [station] make, for a cook of COOKING level [cookLevel]. */
    fun run(level: ServerLevel, station: Station, items: List<ItemStack>, cookLevel: Int): Recognised {
        runs++
        val stacks = items.filter { Roles.food(it) != null }
        val share = Roles.share(stacks)
        val pick = decide(station, stacks.sumOf { it.count }, share, cookLevel)
        val kind = pick.kind ?: return Recognised(null, pick.portions, share, 1.0)
        return Recognised(kind, pick.portions, share, Quality.distance(share, Ideal.of(level, kind)))
    }

    /** The decision of [run] for a pot of [n] items whose mix is [share]: pure, so that it can be tried outside the game. */
    fun decide(station: Station, n: Int, share: DoubleArray, cookLevel: Int): Pick {
        val gruel = max(1, n / 2)
        if (n <= 0) return Pick(null, 0, n, null, 1.0)
        val makes = DishKind.entries.filter { it.station == station }
        val least = makes.minOfOrNull { Standard.half(it) } ?: return Pick(null, gruel, n, null, 1.0)
        val near = nearest(share, cookLevel, station)
        if (n < least || near == null || near.second > RECOGNISED_TV) return Pick(null, gruel, n, near?.first, near?.second ?: 1.0)
        val best = near.first
        val most = best.portions * 3 / 2
        val portions = (best.portions * n / Standard.count(best)).coerceIn(1, most)
        return Pick(best, portions, n, best, near.second)
    }

    /**
     * The kind whose standard bill [share] is nearest, among those the cook of COOKING level [cookLevel] has unlocked and that
     * are made at [station] (at any station if it is null), with its distance; null if there is none.
     */
    fun nearest(share: DoubleArray, cookLevel: Int, station: Station?): Pair<DishKind, Double>? {
        var best: DishKind? = null
        var bestTv = Double.MAX_VALUE
        for (kind in DishKind.entries) {
            if (kind.level > cookLevel || (station != null && kind.station != station)) continue
            val tv = Quality.distance(share, Standard.raw(kind))
            if (tv < bestTv) {
                best = kind
                bestTv = tv
            }
        }
        return best?.let { it to bestTv }
    }

    /**
     * How many portions the cook's `COOK_EXTRA` adds to a batch of [portions] of [kind] (design 2.4 and 2.7): each portion has
     * the chance of one more, and the batch never grows by more than half or past 1.5 times the kind's portions. Rolled on
     * the server with [player]'s own random source; the serve (Part B) calls it once for the batch.
     */
    fun extra(player: ServerPlayer, kind: DishKind, portions: Int): Int =
        extraPortions(portions, kind, Talents.build(player)[Bonus.COOK_EXTRA]) { player.random.nextDouble() }

    /** The rule of [extra], with the chance given and the dice passed in (each call of [roll] answers 0 up to 1). */
    fun extraPortions(portions: Int, kind: DishKind, chance: Double, roll: () -> Double): Int {
        if (chance <= 0.0 || portions <= 0) return 0
        val room = min(portions / 2, kind.portions * 3 / 2 - portions)
        if (room <= 0) return 0
        var more = 0
        repeat(portions) { if (roll() < chance) more++ }
        return min(more, room)
    }
}
