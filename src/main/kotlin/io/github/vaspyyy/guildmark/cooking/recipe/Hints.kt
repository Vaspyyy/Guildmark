package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.Verdict
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One sentence of what the ladle says, before it is turned into words: a language key and what fills its blanks (whole
 * numbers, or other sentences such as the name of a role). Kept apart from [Component] so that what a hint says can be
 * worked out and tried outside the game.
 */
class Sentence(val key: String, val args: List<Any> = listOf())

/**
 * What the ladle tells a cook and what a keeper says of a dish (living-world/cook/design.md 2.6). The ladle says more as the
 * cook's COOKING level rises, each step adding to the last:
 *
 * | COOKING level | The ladle says |
 * |---|---|
 * | 1 | how close the mix is to the world's best one: Spot on (under 0.05), Close (under 0.10), Not far (under 0.18), Far off |
 * | 10 | and the role furthest out, and which way ("Too much in the greens." / "It wants meat.") |
 * | 25 | and by how much, in items of the kind's standard batch ("About two more items of savour.") |
 * | 50 | and, while it cooks, the timing ("Pull it in 6 seconds.") and an accent the flavour is thin on |
 * | 75 | and what it will be ("At best this will be Fine. One more item of savour and it could be Hallmarked.") |
 *
 * Only the mix and the clock are known to the ladle: the attention and the care are not yet, so a forecast is a best case
 * (perfect timing, attention and care), with the cook's own level bonus. A pot that was taken for nothing (Gruel) is
 * said to be gruel, and from level 10 which dish it is nearest and what it wants to be that. Everything it names is out
 * by a real amount of the world's hidden ideal, or of the nearest standard bill for Gruel.
 */
object Hints {
    /** The mix is Spot on under this distance from the ideal, Close under [CLOSE], Not far under [NOT_FAR], and Far off beyond it. */
    const val SPOT_ON = 0.05
    const val CLOSE = 0.10
    const val NOT_FAR = 0.18

    /** The COOKING levels at which the ladle learns to say more (design 2.6). */
    const val LEVEL_CLOSE = 1
    const val LEVEL_ROLE = 10
    const val LEVEL_AMOUNT = 25
    const val LEVEL_TIMING = 50
    const val LEVEL_FORECAST = 75

    /** A role this close to its ideal share is not "out": the ladle does not name it. */
    const val NEGLIGIBLE = 0.02

    // Dish-seconds (design 1.2): the dish is raw this far before its time, ready 8 either side, burnt 32 past it.
    private const val RAW = 32.0
    private const val NOW = 1.0
    private const val WINDOW = 8.0
    private const val CATCHING = 20.0

    // The language keys, whole, so that `check_lang.py` reads them. Indexed by Verdict.close (0 far off to 3 spot on).
    private val CLOSENESS = listOf(
        "message.guildmark.cook.hint.far", "message.guildmark.cook.hint.not_far",
        "message.guildmark.cook.hint.close", "message.guildmark.cook.hint.spot_on",
    )

    /** The word for each role, in [Role] order: grain, meat, fish, greens, sweetness, richness, savour. */
    private val ROLE_NOUNS = listOf(
        "message.guildmark.cook.role.body", "message.guildmark.cook.role.meat", "message.guildmark.cook.role.sea",
        "message.guildmark.cook.role.green", "message.guildmark.cook.role.sweet", "message.guildmark.cook.role.rich",
        "message.guildmark.cook.role.savour",
    )

    /** The name of each tier, by [Tier.ordinal]. */
    private val TIER_NAMES = listOf(
        "message.guildmark.cook.tier.spoilt", "message.guildmark.cook.tier.plain", "message.guildmark.cook.tier.sound",
        "message.guildmark.cook.tier.fine", "message.guildmark.cook.tier.hallmarked",
    )

    // By the amount out, in items: under 0.75, about one, about two, about three, more.
    private val MORE = listOf(
        "message.guildmark.cook.hint.more_little", "message.guildmark.cook.hint.more_1", "message.guildmark.cook.hint.more_2",
        "message.guildmark.cook.hint.more_3", "message.guildmark.cook.hint.more_lots",
    )
    private val LESS = listOf(
        "message.guildmark.cook.hint.less_little", "message.guildmark.cook.hint.less_1", "message.guildmark.cook.hint.less_2",
        "message.guildmark.cook.hint.less_3", "message.guildmark.cook.hint.less_lots",
    )

    private const val OVER = "message.guildmark.cook.hint.over"
    private const val UNDER = "message.guildmark.cook.hint.under"
    private const val THIN = "message.guildmark.cook.hint.thin"
    private const val ADD = "message.guildmark.cook.hint.add"
    private const val DROP = "message.guildmark.cook.hint.drop"
    private const val FORECAST = "message.guildmark.cook.hint.forecast"
    private const val FORECAST_UP = "message.guildmark.cook.hint.forecast_up"
    private const val GRUEL = "message.guildmark.cook.hint.gruel"
    private const val GRUEL_NEAR = "message.guildmark.cook.hint.gruel_near"
    private const val TIMING_RAW = "message.guildmark.cook.hint.timing_raw"
    private const val TIMING_SOON = "message.guildmark.cook.hint.timing_soon"
    private const val TIMING_SOON_ONE = "message.guildmark.cook.hint.timing_soon_one"
    private const val TIMING_NOW = "message.guildmark.cook.hint.timing_now"
    private const val TIMING_LATE = "message.guildmark.cook.hint.timing_late"
    private const val TIMING_OVER = "message.guildmark.cook.hint.timing_over"
    private const val TIMING_BURNING = "message.guildmark.cook.hint.timing_burning"

    private val ROLES = Role.entries.size

    /** The ladle's hint to [player] about a pot of [share] cooking as [kind] (null: Gruel) at [clock] dish-seconds (null while it is filling), at mid heat. */
    fun taste(player: ServerPlayer, kind: DishKind?, share: DoubleArray, clock: Double?): Component = taste(player, kind, share, clock, 1.0)

    /** As [taste], with the heat's [speed] (0.6 low, 1.0 mid, 1.6 high), so that "in 6 seconds" is seconds on the clock and not dish-seconds. */
    fun taste(player: ServerPlayer, kind: DishKind?, share: DoubleArray, clock: Double?, speed: Double): Component =
        taste(player, kind, share, clock, speed, null)

    /**
     * As the above, with the [station] the pot is on, so that a Gruel pot is told which dish of that station it is nearest to
     * (null: of any station).
     */
    fun taste(player: ServerPlayer, kind: DishKind?, share: DoubleArray, clock: Double?, speed: Double, station: Station?): Component {
        val level = Skills.level(player, Skill.COOKING)
        val ideal = if (kind == null) null else Ideal.of(player.level(), kind)
        return component(say(level, kind, share, ideal, clock, speed, Quality.bonus(player), station))
    }

    /** A keeper's verdict on a mix of [share] as [kind] in [level]'s world: the role furthest out, which way, by how many items, and how close it is. */
    fun verdict(level: ServerLevel, kind: DishKind, share: DoubleArray): Verdict = judge(share, Ideal.of(level, kind), Standard.count(kind))

    /** The verdict on a mix of [share] against [ideal] when the kind's standard bill holds [standardCount] items (pure). */
    fun judge(share: DoubleArray, ideal: DoubleArray, standardCount: Int): Verdict {
        val at = furthest(share, ideal)
        val gap = diff(share, ideal, at)
        return Verdict(Role.entries[at], gap > 0.0, abs(gap) * standardCount, closeness(Quality.distance(share, ideal)))
    }

    /** How close a mix [tv] from the ideal is: 3 spot on, 2 close, 1 not far, 0 far off. */
    fun closeness(tv: Double): Int = when {
        tv < SPOT_ON -> 3
        tv < CLOSE -> 2
        tv < NOT_FAR -> 1
        else -> 0
    }

    /**
     * What the ladle says to a cook of COOKING [level] about a pot of [share] taken for [kind] (null: Gruel) whose world's
     * ideal for it is [ideal], [clock] dish-seconds into the cooking (null while it fills) at the heat's [speed], for a cook
     * whose bonus to the quality is [bonus]. A Gruel pot is compared with the dishes of [station] (null: of any station). Pure.
     */
    fun say(level: Int, kind: DishKind?, share: DoubleArray, ideal: DoubleArray?, clock: Double?, speed: Double, bonus: Double, station: Station? = null): List<Sentence> {
        if (kind == null || ideal == null) return gruel(level, share, station)
        val out = ArrayList<Sentence>()
        val close = closeness(Quality.distance(share, ideal))
        if (level >= LEVEL_CLOSE) out.add(Sentence(CLOSENESS[close]))
        val at = furthest(share, ideal)
        val gap = diff(share, ideal, at)
        val named = level >= LEVEL_ROLE && close < 3 && abs(gap) >= NEGLIGIBLE
        if (named) out.add(outBy(level, gap, Standard.count(kind), at))
        if (level >= LEVEL_TIMING && clock != null) {
            out.add(timing(kind, clock, speed))
            thin(share, ideal, if (named) at else -1)?.let { out.add(it) }
        }
        if (level >= LEVEL_FORECAST) out.addAll(forecast(kind, share, ideal, bonus))
        return out
    }

    /** The sentences joined into one line, a space between. */
    fun component(sentences: List<Sentence>): Component {
        val out = Component.empty()
        for ((i, sentence) in sentences.withIndex()) {
            if (i > 0) out.append(Component.literal(" "))
            out.append(component(sentence))
        }
        return out
    }

    private fun component(sentence: Sentence): Component =
        Component.translatable(sentence.key, *sentence.args.map { if (it is Sentence) component(it) else it }.toTypedArray())

    // ---- What is out, and by how much ----

    /** The role whose share is furthest from [target]'s (the first of them, if two are as far). */
    private fun furthest(share: DoubleArray, target: DoubleArray): Int {
        var at = 0
        var most = -1.0
        for (i in 0 until ROLES) {
            val away = abs(diff(share, target, i))
            if (away > most) {
                most = away
                at = i
            }
        }
        return at
    }

    /** How much more of role [i] [share] has than [target]: negative if it has less. */
    private fun diff(share: DoubleArray, target: DoubleArray, i: Int): Double = share.getOrElse(i) { 0.0 } - target.getOrElse(i) { 0.0 }

    /** The sentence that says role [at] is out by [gap] of a share: which way (level 10), and by how many items of [count] (level 25). */
    private fun outBy(level: Int, gap: Double, count: Int, at: Int): Sentence {
        val noun = Sentence(ROLE_NOUNS[at])
        val over = gap > 0.0
        if (level < LEVEL_AMOUNT) return Sentence(if (over) OVER else UNDER, listOf(noun))
        val items = abs(gap) * count
        val step = when {
            items < 0.75 -> 0
            items < 1.5 -> 1
            items < 2.5 -> 2
            items < 3.5 -> 3
            else -> 4
        }
        return Sentence((if (over) LESS else MORE)[step], listOf(noun))
    }

    /** The accent (sweet, rich or savour: what a SEASON call can add) the pot is thinnest on, if it is, and is not [skip]. */
    private fun thin(share: DoubleArray, ideal: DoubleArray, skip: Int): Sentence? {
        var at = -1
        var most = NEGLIGIBLE
        for (i in intArrayOf(Role.SWEET.ordinal, Role.RICH.ordinal, Role.SAVOUR.ordinal)) {
            val short = -diff(share, ideal, i)
            if (i != skip && short >= most) {
                most = short
                at = i
            }
        }
        return if (at < 0) null else Sentence(THIN, listOf(Sentence(ROLE_NOUNS[at])))
    }

    // ---- When to pull it ----

    /** Where the clock stands against the kind's time (1.2): the ready window is 8 dish-seconds either side, the best moment is the middle. */
    private fun timing(kind: DishKind, clock: Double, speed: Double): Sentence {
        val v = if (speed > 0.0) speed else 1.0
        val time = kind.time.toDouble()
        val to = time - clock
        return when {
            clock < time - RAW -> Sentence(TIMING_RAW, listOf(seconds(to / v)))
            to > NOW -> {
                val s = seconds(to / v)
                if (s <= 1) Sentence(TIMING_SOON_ONE) else Sentence(TIMING_SOON, listOf(s))
            }
            to >= -NOW -> Sentence(TIMING_NOW)
            clock <= time + WINDOW -> Sentence(TIMING_LATE)
            clock <= time + CATCHING -> Sentence(TIMING_OVER)
            else -> Sentence(TIMING_BURNING)
        }
    }

    private fun seconds(x: Double): Int = max(1, x.roundToInt())

    // ---- What it will be ----

    /** The best tier a pot of this mix can earn: with perfect timing, attention and care, and the cook's [bonus]. */
    fun ceiling(share: DoubleArray, ideal: DoubleArray, bonus: Double): Tier =
        Quality.tier(Quality.q(Quality.mix(share, ideal), 1.0, 1.0, 1.0, bonus))

    private fun forecast(kind: DishKind, share: DoubleArray, ideal: DoubleArray, bonus: Double): List<Sentence> {
        val tier = ceiling(share, ideal, bonus)
        val out = ArrayList<Sentence>()
        out.add(Sentence(FORECAST, listOf(Sentence(TIER_NAMES[tier.ordinal]))))
        if (tier == Tier.HALLMARKED) return out
        val count = Standard.count(kind)
        for (i in (0 until ROLES).sortedByDescending { abs(diff(share, ideal, it)) }) {
            val gap = diff(share, ideal, i)
            if (abs(gap) < NEGLIGIBLE) break
            val changed = withOne(share, i, gap > 0.0, count) ?: continue
            val better = ceiling(changed, ideal, bonus)
            if (better.ordinal > tier.ordinal) {
                val step = Sentence(if (gap > 0.0) DROP else ADD, listOf(Sentence(ROLE_NOUNS[i])))
                out.add(Sentence(FORECAST_UP, listOf(step, Sentence(TIER_NAMES[better.ordinal]))))
                break
            }
        }
        return out
    }

    /**
     * The mix of a pot of [count] items of this [share] with one item that is wholly [role] put in (or, if [remove], taken out);
     * null if there is not one to take out.
     */
    fun withOne(share: DoubleArray, role: Int, remove: Boolean, count: Int): DoubleArray? {
        val total = count.toDouble()
        val parts = DoubleArray(ROLES) { share.getOrElse(it) { 0.0 } * total }
        val after = if (remove) total - 1.0 else total + 1.0
        if (remove) {
            if (parts[role] < 1.0 || after <= 0.0) return null
            parts[role] -= 1.0
        } else {
            parts[role] += 1.0
        }
        return DoubleArray(ROLES) { parts[it] / after }
    }

    // ---- Gruel ----

    /** A pot taken for nothing: it is gruel; from level 10 which dish (of [station], if it is known) it is nearest, and from there what it wants. */
    private fun gruel(level: Int, share: DoubleArray, station: Station?): List<Sentence> {
        if (level < LEVEL_CLOSE) return listOf()
        val near = if (level >= LEVEL_ROLE) Recognise.nearest(share, level, station) else null
        if (near == null) return listOf(Sentence(GRUEL))
        val kind = near.first
        val out = ArrayList<Sentence>()
        out.add(Sentence(GRUEL_NEAR, listOf(Sentence("item.guildmark.dish_${kind.id}"))))
        val target = Standard.raw(kind)
        val at = furthest(share, target)
        val gap = diff(share, target, at)
        if (abs(gap) >= NEGLIGIBLE) out.add(outBy(level, gap, Standard.count(kind), at))
        return out
    }
}
