package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.townsfolk.Days
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import kotlin.math.floor
import kotlin.math.max

/**
 * How a dish keeps (living-world/cook/design.md 3.2), worked out and never trusted. A dish is fresh while it is no more
 * than the kind's first number of days old, stale up to its second, and gone off after that; the cook's COOK_KEEP (written
 * into the dish when it was cooked) stretches both. Nothing ticks and nothing is stored but the day the dish was cooked: the
 * stage is worked out from that day wherever it matters (a meal, a sale, the tooltip), and the one thing written to a stack
 * is the cosmetic stage the item model reads, by [sync], when a stack in someone's pack changes stage: at most twice in a
 * dish's life. The Larder's half-rate ageing is in [LarderBlockEntity]; the days it gives back move the dish's day, so
 * what is here needs no knowledge of it.
 *
 * The days are the town's (`Days.dayOf(level.overworldClockTime)`, [today]), and an age is never below 0, so a dish cooked
 * "in the future" (after a `/time set` back, or carried from a world whose clock is behind) is simply fresh (9.3).
 */
object Freshness {
    /** Ticks between one look at a holder's dishes and the next (9.5), staggered by the holder's entity id. */
    const val RECHECK = 200

    /** The days Gruel keeps, which no row of the kinds' table gives it: the pottage's. */
    private val GRUEL_KEEP = intArrayOf(2, 4, 6)

    // What `cook perf` reads (CookStats): plain counters, kept by the server thread and read when someone asks.
    var looks = 0L
        private set
    var writes = 0L
        private set

    /** The town's day it is now, on either side of the wire: the same number [Cooking.today] gives the server. */
    fun today(level: Level): Long = Days.dayOf(level.overworldClockTime)

    /**
     * The days a dish of [kind] ([kind] null is Gruel) stays fresh and then stale, for a cook whose COOK_KEEP was [keep]
     * percent (3.2): the table's first two numbers times `1 + keep / 100`, rounded. (The table's third number, the
     * day the dish is thrown away, belongs to the model's sale and to nothing in the game: there is no fourth stage.)
     */
    fun limits(kind: DishKind?, keep: Int): IntArray {
        val base = kind?.keep ?: GRUEL_KEEP
        val scale = 1.0 + keep.coerceIn(0, 100) / 100.0
        return intArrayOf(floor(base[0] * scale + 0.5).toInt(), floor(base[1] * scale + 0.5).toInt())
    }

    /** The stage of a dish [age] days old (3.2). */
    fun stageFor(age: Long, kind: DishKind?, keep: Int): Stage {
        val limit = limits(kind, keep)
        return when {
            age <= limit[0] -> Stage.FRESH
            age <= limit[1] -> Stage.STALE
            else -> Stage.GONE
        }
    }

    /** The days old [data] is on day [today]: 0 for one cooked today or in the future. */
    fun age(today: Long, data: DishData): Long = max(0L, today - data.day)

    /** The stage [data] is at on day [today], whatever the switch says (a caller that cares asks [Cooking.spoilage] first). */
    fun stageAt(today: Long, kind: DishKind?, data: DishData): Stage = stageFor(age(today, data), kind, data.keep)

    /**
     * The stage of [stack] in [level]'s world, worked out from its day, on either side of the wire (the tooltip asks it
     * on the client). Fresh when it is not a dish, when it is Scorched mess, and always while `dishSpoilage` is off.
     */
    fun stageOf(level: Level, stack: ItemStack): Stage {
        val item = stack.item as? DishItem ?: return Stage.FRESH
        if (item.scorched || !Cooking.spoilage()) return Stage.FRESH
        return stageAt(today(level), item.kind, stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN)
    }

    /**
     * A copy of [stack] ([count] of it) dressed as it looks today in [level]'s world: its cosmetic stage set from its
     * day, on either side of the wire. For what is drawn, never kept: a Plate's dish and the dishes seen through a
     * Larder's slats are shown this way, so what is lying there for days still goes stale to the eye. Nothing is counted.
     */
    fun looking(level: Level, stack: ItemStack, count: Int = 1): ItemStack {
        val copy = stack.copyWithCount(count)
        val shown = cosmetic(stageOf(level, copy))
        if (shown == 0) copy.remove(CookRegistry.DISH_STAGE.get()) else copy.set(CookRegistry.DISH_STAGE.get(), shown)
        return copy
    }

    /** The cosmetic number for [stage]: what the item model's `select` reads (0 fresh, 1 stale, 2 gone off). */
    fun cosmetic(stage: Stage): Int = when (stage) {
        Stage.FRESH -> 0
        Stage.STALE -> 1
        Stage.GONE -> 2
    }

    /** The stage the model of [stack] shows now, as last written by [sync] (fresh when never written). */
    fun shown(stack: ItemStack): Stage = when (stack.getOrDefault(CookRegistry.DISH_STAGE.get(), 0)) {
        1 -> Stage.STALE
        2 -> Stage.GONE
        else -> Stage.FRESH
    }

    /**
     * Brings [stack] up to date with the day it is: works out its stage from its day, writes the cosmetic stage the model
     * reads if that changed, and makes its `FOOD` the food it is at that stage (3.1, 3.2), so that whatever eats it,
     * the game's own eating or the talents' extra on the food, sees the right figures. Returns the stage. Server side;
     * a stack that is not a dish, and Scorched mess, are left alone. With `dishSpoilage` off every dish is fresh, so a
     * dish that went stale before the switch went off is made fresh again.
     */
    fun sync(level: ServerLevel, stack: ItemStack): Stage {
        val item = stack.item as? DishItem ?: return Stage.FRESH
        if (item.scorched) return Stage.FRESH
        looks++
        val data = stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN
        val stage = if (Cooking.spoilage()) stageAt(Cooking.today(level), item.kind, data) else Stage.FRESH
        val shown = cosmetic(stage)
        if (stack.getOrDefault(CookRegistry.DISH_STAGE.get(), 0) != shown) {
            if (shown == 0) stack.remove(CookRegistry.DISH_STAGE.get()) else stack.set(CookRegistry.DISH_STAGE.get(), shown)
            writes++
        }
        val food = DishFood.food(item.kind, data.tier, stage)
        if (stack.get(DataComponents.FOOD) != food) stack.set(DataComponents.FOOD, food)
        return stage
    }
}
