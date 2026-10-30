package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import net.minecraft.world.food.FoodProperties
import kotlin.math.roundToInt

/**
 * What a portion feeds (living-world/cook/design.md 3.1): the one table of nutrition and saturation by kind, the tier's
 * share of it and the stage's share of that. Nothing here ticks and nothing here is saved; a stack's `FOOD` component is
 * made from it. The meal a dish gives is Part F's, and so are the numbers the tooltip quotes for it (`Meals.describe`).
 *
 * The table is a Sound portion's. A Fine dish has one more nutrition and a Hallmarked two; saturation is scaled by the
 * tier (Plain x0.8, Sound x1.0, Fine x1.15, Hallmarked x1.3). A stale portion has three quarters of both, a gone-off one
 * a single point of nutrition and no saturation (it is rotten flesh with a better name). Gruel is a plain bowl whatever
 * its tier says, and Scorched mess cannot be eaten at all.
 */
object DishFood {
    private class Row(val nutrition: Int, val saturation: Float)

    /** Nutrition and saturation of a Sound portion of each kind (3.1). */
    private val ROWS: Map<DishKind, Row> = mapOf(
        DishKind.POTTAGE to Row(5, 6.0f),
        DishKind.STEW to Row(8, 9.6f),
        DishKind.BROTH to Row(4, 4.8f),
        DishKind.ROOTS to Row(4, 2.4f),
        DishKind.CHOWDER to Row(6, 7.2f),
        DishKind.CAKES to Row(5, 3.0f),
        DishKind.PIE to Row(8, 9.6f),
        DishKind.TART to Row(6, 3.6f),
        DishKind.FISH to Row(7, 8.4f),
        DishKind.ROAST to Row(10, 12.0f),
        DishKind.LOAF to Row(6, 7.2f),
        DishKind.STRIPS to Row(6, 7.2f),
    )

    /** What a bowl of Gruel feeds, at any tier. */
    private val GRUEL = Row(3, 2.0f)

    /** What a stale portion keeps of its food (3.2). */
    const val STALE_SHARE = 0.75

    /** How long a gone-off portion's Hunger lasts, in ticks (30 seconds, as rotten flesh). */
    const val HUNGER_TICKS = 600

    /** Saturation by tier (3.1). A Spoilt portion is not a thing that is made; it reads as half a Plain one. */
    private fun saturationShare(tier: Tier): Float = when (tier) {
        Tier.SPOILT -> 0.4f
        Tier.PLAIN -> 0.8f
        Tier.SOUND -> 1.0f
        Tier.FINE -> 1.15f
        Tier.HALLMARKED -> 1.3f
    }

    /** The nutrition a Fine dish has over a Sound one, and a Hallmarked dish (3.1). */
    private fun nutritionExtra(tier: Tier): Int = when (tier) {
        Tier.FINE -> 1
        Tier.HALLMARKED -> 2
        else -> 0
    }

    /** The nutrition a portion of [kind] ([kind] null is Gruel) gives at [tier] and [stage]. */
    fun nutrition(kind: DishKind?, tier: Tier, stage: Stage): Int {
        val base = if (kind == null) GRUEL.nutrition else ROWS.getValue(kind).nutrition + nutritionExtra(tier)
        return when (stage) {
            Stage.FRESH -> base
            Stage.STALE -> (base * STALE_SHARE).roundToInt().coerceAtLeast(1)
            Stage.GONE -> 1
        }
    }

    /** The saturation a portion of [kind] gives at [tier] and [stage]. */
    fun saturation(kind: DishKind?, tier: Tier, stage: Stage): Float {
        val base = if (kind == null) GRUEL.saturation else ROWS.getValue(kind).saturation * saturationShare(tier)
        return when (stage) {
            Stage.FRESH -> base
            Stage.STALE -> (base * STALE_SHARE).toFloat()
            Stage.GONE -> 0f
        }
    }

    /**
     * Whether a portion may be eaten with the food bar full. A dish that gives a meal's effect may: a cook eats a roast
     * before a fight, not because they are hungry. So may a gone-off one, as rotten flesh may (it is a penalty, and the
     * player's to take). A sweet dish and Gruel are only food, and wait for an appetite.
     */
    fun alwaysEdible(kind: DishKind?, stage: Stage): Boolean = stage == Stage.GONE || kind?.family != null

    /** The `FOOD` component of a portion of [kind] at [tier] and [stage]. */
    fun food(kind: DishKind?, tier: Tier, stage: Stage): FoodProperties =
        FoodProperties(nutrition(kind, tier, stage), saturation(kind, tier, stage), alwaysEdible(kind, stage))

    /** Whether a portion of [kind] is served in a bowl, which comes back when it is eaten (3.1): the pot dishes and Gruel. */
    fun bowl(kind: DishKind?): Boolean = kind == null || kind == DishKind.POTTAGE || kind == DishKind.STEW ||
        kind == DishKind.BROTH || kind == DishKind.CHOWDER

    /** Seconds it takes to eat a portion (3.3): a bowl 1.6, anything else 1.2, and Gruel ([kind] null), spooned slower, [GRUEL_SECONDS]. */
    fun seconds(kind: DishKind?): Float = when {
        kind == null -> GRUEL_SECONDS
        bowl(kind) -> 1.6f
        else -> 1.2f
    }

    /** Gruel's eating, half as long again as a bowl's: the same three spoonfuls (`eat_spoon`, played to its length) and the same sound, slower. */
    const val GRUEL_SECONDS = 2.4f
}
