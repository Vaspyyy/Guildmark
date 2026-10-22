package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.server.level.ServerPlayer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The COOKING XP a batch pays (living-world/cook/design.md 2.5), with the soft cap of 5.6 inside:
 *
 * ```
 * batch XP = portions * (3 + 2 * complexity) * the tier's XP factor        (a Sound stew of 8: 40; a Hallmarked roast of 4: 79)
 * ```
 *
 * A cook is paid their share of it by `Skills.gain(player, Skill.COOKING, xp)`, so the XP node of the tree (+10%) and the
 * config's multiplier apply on top, as they do for every skill. The first [COOK_XP_SOFT_CAP] XP a cook earns in a town's day
 * are paid in full and the rest at [COOK_XP_PAST_CAP] of it: a cook who makes 200 portions a day and gives them away is
 * not made a master by it. The day's count is kept on the player (`guildmark:cook_xp_day`) and counts the XP before the cap
 * took its cut, so it never stands still.
 */
object CookXp {
    /** The first this much COOKING XP a day is paid at the full rate; the rest of the day's, at [COOK_XP_PAST_CAP] of it (5.6). */
    const val COOK_XP_SOFT_CAP = 900

    /** The share of the full rate that XP past [COOK_XP_SOFT_CAP] in a day is paid at (5.6). */
    const val COOK_XP_PAST_CAP = 0.4

    /** How many batches have been paid since the game started, for `cook perf`. */
    var paid = 0L
        private set

    /** The XP a whole batch of [portions] of [kind] at [tier] is worth, before it is shared among the cooks and before the cap. */
    fun batch(kind: DishKind, tier: Tier, portions: Int): Double = max(0, portions) * (3 + 2 * kind.complexity) * tier.xp

    /** What [xp] of a day's earnings is paid as when [earned] has already been earned that day: full up to the cap, [COOK_XP_PAST_CAP] of the rest. */
    fun pay(xp: Double, earned: Int): Double {
        val room = max(0, COOK_XP_SOFT_CAP - earned).toDouble()
        val full = min(xp, room)
        return full + (xp - full) * COOK_XP_PAST_CAP
    }

    /** The COOKING XP [player] has earned today (the town's day, [Cooking.today]), before the cap took its cut. */
    fun earnedToday(player: ServerPlayer): Int {
        val state = player.getData(CookRegistry.COOK_XP_DAY)
        return if (state.day == Cooking.today(player.level())) state.xp else 0
    }

    /** Whether the game pays skill XP to [player] at all (it does not in creative or spectator, `Skills.gain`). */
    fun paysXp(player: ServerPlayer): Boolean = !player.isCreative && !player.isSpectator

    /**
     * Pays [player] their [share] (0 to 1) of a batch of [portions] of [kind] at [tier], and answers the XP given, before
     * the tree's and the config's multipliers: whole, the XP that [pay] would pay. It pays nothing, and writes nothing, with
     * Hearthcraft off or to a player in creative or spectator mode.
     */
    fun award(player: ServerPlayer, kind: DishKind, tier: Tier, portions: Int, share: Double): Int {
        if (!Cooking.enabled() || !paysXp(player)) return 0
        val xp = batch(kind, tier, portions) * share.coerceIn(0.0, 1.0)
        if (xp <= 0.0) return 0
        val day = Cooking.today(player.level())
        val earned = earnedToday(player)
        val given = pay(xp, earned)
        player.setData(CookRegistry.COOK_XP_DAY, CookXpDay(day, earned + xp.roundToInt()))
        Skills.gain(player, Skill.COOKING, given)
        paid++
        return given.roundToInt()
    }
}
