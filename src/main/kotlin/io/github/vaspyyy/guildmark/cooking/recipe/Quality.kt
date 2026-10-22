package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.server.level.ServerPlayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How well a batch comes out (living-world/cook/design.md 2.5):
 *
 * ```
 * q = 0.45 * mix + 0.17 * timing + 0.17 * attention + 0.11 * care + bonus        (clamped to 0..1)
 * bonus = 0.0015 * max(0, level - 5) + COOK_CRAFT
 * mix = max(0, 1 - tv / 0.40),   tv = half the sum over the roles of |share - ideal share|
 * ```
 *
 * The tiers start at [Tier.q]: Spoilt below 0.30, Plain 0.30, Sound 0.52, Fine 0.74, Hallmarked 0.90.
 */
object Quality {
    /** A mix this far (total variation) from the ideal scores 0; the score falls in a straight line from 1 at the ideal. */
    const val MIX_SPAN = 0.40

    /** What the mix, the timing, the attention and the care are each worth of the quality. */
    const val W_MIX = 0.45
    const val W_TIMING = 0.17
    const val W_ATTENTION = 0.17
    const val W_CARE = 0.11

    /** What each COOKING level above [LEVEL_FREE] adds to the quality: +0.0675 at 50, +0.1425 at 100. */
    const val LEVEL_COEF = 0.0015
    const val LEVEL_FREE = 5

    /** A quality this close under a tier's line still earns it (floating point: 0.52 may be worked out as 0.5199999999999999). */
    private const val EDGE = 1.0e-9

    private val ROLES = Role.entries.size

    /** The total variation between two mixes: half the sum over the roles of |share - ideal share| (both in [Role] order). */
    fun distance(share: DoubleArray, ideal: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until ROLES) sum += abs(share.getOrElse(i) { 0.0 } - ideal.getOrElse(i) { 0.0 })
        return 0.5 * sum
    }

    /** How good a mix is, 0 to 1, against the [ideal] (both in [Role] order): 1 at the ideal, 0 from [MIX_SPAN] away. */
    fun mix(share: DoubleArray, ideal: DoubleArray): Double = mixOf(distance(share, ideal))

    /** The mix score of a mix [tv] from the ideal. */
    fun mixOf(tv: Double): Double = max(0.0, 1.0 - tv / MIX_SPAN)

    /** The quality of a batch from its [mix], [timing], [attention] and [care] (each 0 to 1) and the cook's [bonus]. */
    fun q(mix: Double, timing: Double, attention: Double, care: Double, bonus: Double): Double {
        val sum = W_MIX * unit(mix) + W_TIMING * unit(timing) + W_ATTENTION * unit(attention) + W_CARE * unit(care) + bonus
        return sum.coerceIn(0.0, 1.0)
    }

    /** The tier a quality earns: the highest whose [Tier.q] it reaches. */
    fun tier(q: Double): Tier = Tier.entries.lastOrNull { q + EDGE >= it.q } ?: Tier.SPOILT

    /** What a COOKING [level] adds to the quality by itself. */
    fun levelBonus(level: Int): Double = LEVEL_COEF * max(0, level - LEVEL_FREE)

    /** The bonus a cook of COOKING [level] with [craft] from the tree (`COOK_CRAFT`) adds to a batch. */
    fun bonus(level: Int, craft: Double): Double = levelBonus(level) + craft

    /**
     * What a cook who has taken every cooking node of the tree has of `COOK_CRAFT` at [level]: +0.01 at 15, 25 and 50 and
     * +0.03 at 65, the numbers `cook_model.py` uses (design 2.7).
     */
    fun craftAt(level: Int): Double =
        (if (level >= 15) 0.01 else 0.0) + (if (level >= 25) 0.01 else 0.0) + (if (level >= 50) 0.01 else 0.0) + (if (level >= 65) 0.03 else 0.0)

    /** The `COOK_CRAFT` [player]'s tree gives. */
    fun craft(player: ServerPlayer): Double = Talents.build(player)[Bonus.COOK_CRAFT]

    /** The bonus [player]'s COOKING level and the tree's `COOK_CRAFT` give a batch. */
    fun bonus(player: ServerPlayer): Double = bonus(Skills.level(player, Skill.COOKING), craft(player))

    /**
     * The `COOK_KEEP` [player]'s tree gives, as the whole percent a dish is to remember (design 3.2: `DishData.keep`, 0 to 100):
     * `camp_cook` makes it 50, so every dish keeps half as long again.
     */
    fun keep(player: ServerPlayer): Int = (Talents.build(player)[Bonus.COOK_KEEP] * 100.0).roundToInt().coerceIn(0, 100)

    private fun unit(x: Double): Double = min(1.0, max(0.0, x))
}
