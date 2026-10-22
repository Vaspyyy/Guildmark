package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.ServedPayload
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.town.ServedCommand
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import kotlin.math.min

/**
 * What each capital's inn last served, as this client knows it (living-world/cook/design.md 7.4, 9.4): the last
 * [ServedPayload] of every capital, kept only while it is still the meal to show, for [TownPlates] to draw the diners from.
 *
 * Nothing is asked of the server. The ledger (`Pantry`) sends a meal to the players within 128 blocks of its capital when it
 * is served, and sends it again to a player who has come into range later (or logged in), for as long as the meal is
 * [SHOWN_HOURS] market hours old or less; so a late joiner is told the same meal the others were and shows the same diners: a
 * meal is known by its window (three to a town day, which also says which market hour it was served at), and whether it is
 * still shown is worked out from the clock, with the server's own limit, not from when it happened to arrive here. Told again
 * of a meal already known, the client keeps it as it was (the same diners go on eating, none decided afresh).
 *
 * A made-up meal (`cook served`, [ServedCommand.TEST_WINDOW]) has no hour and is shown for [ServedCommand.TEST_TICKS] from
 * its arrival; one told with nothing in it takes a capital's meal away. A meal's tables are the kinds the inn sells and its
 * benches the baked kinds (a cake, a pie or a tart is both, the payload counting them once), each weighted by its count.
 */
internal object ServedState {
    /**
     * Market hours after its meal hour that a meal is still shown: the ledger's own limit (`Pantry`'s `STALE`, 2), which a
     * client has to match so that a player told late sees what the rest do.
     */
    const val SHOWN_HOURS = 2L

    /** The market hour of each of a town day's three meals, counted from the day's own start (7:00, 12:00 and 18:00 on the clock). */
    private val MEAL_HOURS = longArrayOf(1L, 6L, 12L)

    private val KINDS = DishKind.entries.size

    /** A capital's meal: what its inn served at a meal hour, cleaned of anything a payload could not honestly say. */
    class Meal(payload: ServedPayload, val serial: Int, val arrived: Long) {
        val capital: Long = payload.capital
        val window: Int = payload.window
        val inn: BoundingBox = payload.inn

        /** Whether the meal is a made-up one (`cook served`), which has no hour. */
        val test: Boolean = payload.window == ServedCommand.TEST_WINDOW

        /** The market hour it was served at (meaningless for a made-up meal). */
        val hour: Long = 24L * Math.floorDiv(window, 3) + MEAL_HOURS[Math.floorMod(window, 3)]

        /** Whether it is the noon meal of the town's day (the one that sends people to a bench by their work). */
        val lunch: Boolean = Math.floorMod(window, 3) == 1

        /** The portions of each kind ([DishKind] ordinals), and the best tier served of each (a [Tier] ordinal; -1 for none). */
        val counts = IntArray(KINDS)
        val best = ByteArray(KINDS)

        /** The portions of each kind the inn's tables could have and the baked ones, with their totals. */
        val tavern = IntArray(KINDS)
        val baked = IntArray(KINDS)
        val tavernTotal: Int
        val bakedTotal: Int

        init {
            for (k in 0 until KINDS) {
                counts[k] = payload.counts.getOrElse(k) { 0 }.coerceIn(0, MOST)
                best[k] = payload.best.getOrElse(k) { (-1).toByte() }.toInt().coerceIn(-1, Tier.entries.size - 1).toByte()
                val doors = DishKind.entries[k].doors
                if (Door.INN in doors) tavern[k] = counts[k]
                if (Door.BAKERY in doors) baked[k] = counts[k]
            }
            tavernTotal = tavern.sum()
            bakedTotal = baked.sum()
        }

        /** Whether [payload] tells the very meal this is (the same window and the same portions), as it does when sent again. */
        fun same(payload: ServedPayload): Boolean {
            if (payload.window != window) return false
            for (k in 0 until KINDS) {
                if (payload.counts.getOrElse(k) { 0 }.coerceIn(0, MOST) != counts[k]) return false
                if (payload.best.getOrElse(k) { (-1).toByte() }.toInt().coerceIn(-1, Tier.entries.size - 1) != best[k].toInt()) return false
            }
            return true
        }

        /** The best tier of [kind] served (Plain if the payload named none, which the ledger never does for a kind it served). */
        fun tierOf(kind: Int): Tier = Tier.of(maxOf(best[kind].toInt(), Tier.PLAIN.ordinal))

        /** The kind among [weights] (summing to [total]) that [coin], in 0 to 1, picks, each kind in proportion to its portions; -1 if there are none. */
        fun pick(weights: IntArray, total: Int, coin: Double): Int {
            if (total <= 0) return -1
            var at = min((coin * total).toInt(), total - 1)
            for (k in weights.indices) {
                if (at < weights[k]) return k
                at -= weights[k]
            }
            return -1
        }
    }

    /** The most portions of one kind a meal is taken to have, whatever a payload says (a real day serves a few dozen). */
    private const val MOST = 10_000

    /** How far from a capital's centre a lunch-eater is taken to be of that town (the ledger tells players within 128 blocks of it). */
    private const val TOWN = 112.0

    private val meals = HashMap<Long, Meal>()
    private var serials = 0

    /** The market hour and the game time as of the last tick (the hour is [Long.MIN_VALUE] before the first). */
    private var hour = Long.MIN_VALUE
    private var now = 0L

    /** Whether any meal is being shown: the one thing asked of everyone in sight every frame. */
    var any = false
        private set

    /** The meals kept, for the readout. */
    val count: Int get() = meals.size

    /** A [ServedPayload] has come (the main thread; [TownPlates.register] sets this as the payload's handler). */
    fun accept(payload: ServedPayload) {
        val level = Minecraft.getInstance().level ?: return
        val old = meals[payload.capital]
        if (old != null && old.same(payload)) return
        val fresh = Meal(payload, ++serials, level.gameTime)
        if (fresh.counts.sum() <= 0) meals.remove(payload.capital) else meals[payload.capital] = fresh
        tick(level)
    }

    /** Each client tick: the clock, and the meals that are no longer to be shown let go. */
    fun tick(level: ClientLevel) {
        now = level.gameTime
        hour = Math.floorDiv(level.overworldClockTime, 1000L)
        if (meals.isEmpty()) {
            any = false
            return
        }
        val each = meals.values.iterator()
        while (each.hasNext()) if (!live(each.next())) each.remove()
        any = meals.isNotEmpty()
    }

    /**
     * Whether [meal] is still to be shown: a made-up one for [ServedCommand.TEST_TICKS] after it came, a real one while its
     * hour is [SHOWN_HOURS] or fewer behind (and not more than an hour ahead of this client's clock, which can be a little
     * behind the server's, but not by a whole hour unless the time has been set back).
     */
    private fun live(meal: Meal): Boolean =
        if (meal.test) now - meal.arrived in 0L..ServedCommand.TEST_TICKS else hour - meal.hour in -1L..SHOWN_HOURS

    /** The meal of the inn whose box holds the block at ([x], [y], [z]), when it has anything for the tables; null otherwise. */
    fun tavernAt(x: Int, y: Int, z: Int): Meal? {
        for (meal in meals.values) if (meal.tavernTotal > 0 && meal.inn.isInside(x, y, z)) return meal
        return null
    }

    /** The meal of the town nearest ([x], [z]) within [TOWN] blocks of its centre that has baked portions in it; null if there is none. */
    fun bakedNear(x: Double, z: Double): Meal? {
        var found: Meal? = null
        var nearest = TOWN * TOWN
        for (meal in meals.values) {
            if (meal.bakedTotal <= 0) continue
            val centre = BlockPos.of(meal.capital)
            val dx = x - (centre.x + 0.5)
            val dz = z - (centre.z + 0.5)
            val d = dx * dx + dz * dz
            if (d < nearest) {
                nearest = d
                found = meal
            }
        }
        return found
    }

    /** Forgets every meal (the player has left the world). */
    fun reset() {
        meals.clear()
        any = false
        hour = Long.MIN_VALUE
    }
}
