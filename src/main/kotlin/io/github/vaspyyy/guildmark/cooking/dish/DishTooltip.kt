package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.meal.Meals
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * What a dish says of itself (living-world/cook/design.md 3.1): its name, with the tier first ("Fine Drover's Stew"), and
 * below it how it has kept and who cooked it ("Fresh · cooked by Ransom · today"), the meal it gives, and what is coming
 * ("Goes stale in 2 days"). Everything is read from the stack and the day, on the client, when the tooltip is drawn:
 * how it has kept is worked out from the day it was cooked ([Freshness.stageOf]) and not taken from the stage the model
 * shows; the meal's line is Part F's own ([Meals.describe]), worked out from the same numbers as the effect itself and for
 * how the dish has kept; the price is not here (a cook learns that at the door).
 */
object DishTooltip {
    private const val GREY = 0x9C9A94
    private const val DIM = 0x7A7872

    /** The colour a tier is written in: grey for Plain, white for Sound, a silver-blue for Fine and gold for Hallmarked (the colours of the seals on the models). */
    fun tierColor(tier: Tier): Int = when (tier) {
        Tier.SPOILT -> 0x6E6C66
        Tier.PLAIN -> 0xB4AFA4
        Tier.SOUND -> 0xFFFFFF
        Tier.FINE -> 0xB9D8F2
        Tier.HALLMARKED -> 0xF2B93B
    }

    /** The colour a stage is written in: fresh in a leaf green, stale in amber, gone off in red. */
    fun stageColor(stage: Stage): Int = when (stage) {
        Stage.FRESH -> 0x86CC7E
        Stage.STALE -> 0xE2B04B
        Stage.GONE -> 0xE5584D
    }

    /** The word for [tier] ("Fine"): the one the ladle uses too (Part A's `message.guildmark.cook.tier.*`). */
    fun tierWord(tier: Tier): MutableComponent = Component.translatable("message.guildmark.cook.tier.${tier.name.lowercase()}")

    /** The word for [stage] ("Gone off"). */
    fun stageWord(stage: Stage): MutableComponent = Component.translatable("gui.guildmark.cook.dish.stage.${stage.name.lowercase()}")

    /**
     * The name of [stack]: [base] (the kind's own, "Drover's Stew") with the tier first and in the tier's colour. Gruel
     * and Scorched mess are not graded and keep their own name, and so does anything that is not a dish.
     */
    fun name(stack: ItemStack, base: Component): Component {
        val item = stack.item as? DishItem ?: return base
        if (item.kind == null) return base
        val tier = (stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN).tier
        return Component.translatable("gui.guildmark.cook.dish.named", tierWord(tier), base).withColor(tierColor(tier))
    }

    /** "today", "yesterday" or "3 days ago": how long ago a dish was cooked. */
    private fun ago(age: Long): MutableComponent = when {
        age <= 0L -> Component.translatable("gui.guildmark.cook.dish.when.today")
        age == 1L -> Component.translatable("gui.guildmark.cook.dish.when.yesterday")
        else -> Component.translatable("gui.guildmark.cook.dish.when.days", age)
    }

    private fun grey(text: MutableComponent): MutableComponent = text.withColor(GREY)

    /**
     * The lines under the name of [stack] as seen in [level] (null when there is none to ask, as in a search of the
     * creative tabs; the day-dependent lines are then left out). Server rules are read as the client has them, which is as
     * the server sent them.
     */
    fun append(stack: ItemStack, level: Level?, add: Consumer<Component>) {
        val item = stack.item as? DishItem ?: return
        if (item.scorched) {
            add.accept(grey(Component.translatable("gui.guildmark.cook.dish.scorched")))
            return
        }
        val kind = item.kind
        val data = stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN
        val stage = if (level == null) Stage.FRESH else Freshness.stageOf(level, stack)
        val dated = level != null && data.day != DishData.UNDATED
        val age = if (level != null && dated) Freshness.age(Freshness.today(level), data) else 0L

        // "Fresh · cooked by Ransom · today"
        val head = Component.empty().append(stageWord(stage).withColor(stageColor(stage)))
        if (data.cook.isNotEmpty()) head.append(Component.literal(" · ").withColor(DIM)).append(grey(Component.translatable("gui.guildmark.cook.dish.by", data.cook)))
        if (dated) head.append(Component.literal(" · ").withColor(DIM)).append(grey(ago(age)))
        add.accept(head)

        // the meal it gives, in Part F's words and for how the dish has kept (a stale portion's figures are already the lesser ones)
        val meal = if (kind != null && Cooking.enabled()) Meals.describe(kind, data.tier, stage) else null
        if (meal != null) add.accept(meal)
        when (stage) {
            Stage.STALE -> {
                val food = (DishFood.STALE_SHARE * 100).roundToInt()
                val note = if (kind != null && meal != null) {
                    Component.translatable("gui.guildmark.cook.dish.stale.meal", food, effectShare(kind, data.tier))
                } else {
                    Component.translatable("gui.guildmark.cook.dish.stale", food)
                }
                add.accept(note.withColor(stageColor(Stage.STALE)))
            }
            Stage.GONE -> add.accept(Component.translatable("gui.guildmark.cook.dish.rotten").withColor(stageColor(Stage.GONE)))
            Stage.FRESH -> {}
        }

        // what is coming: the days to the next stage (the town's day turns at two in the morning, so "tomorrow" is the next two o'clock)
        if (level != null && dated && stage != Stage.GONE && Cooking.spoilage()) {
            val limit = Freshness.limits(kind, data.keep)
            val ahead = (if (stage == Stage.FRESH) limit[0] else limit[1]) + 1 - age
            val key = if (stage == Stage.FRESH) "gui.guildmark.cook.dish.keeps.stale" else "gui.guildmark.cook.dish.keeps.off"
            add.accept(
                Component.translatable(if (ahead <= 1L) "$key.one" else key, ahead).withColor(DIM),
            )
        }
    }

    /** The whole percent of its effect a stale portion of [kind] at [tier] keeps, as Part F works it out (100 for a kind with no meal). */
    private fun effectShare(kind: DishKind, tier: Tier): Int {
        val fresh = Meals.Boon.of(kind, tier, Stage.FRESH) ?: return 100
        val stale = Meals.Boon.of(kind, tier, Stage.STALE) ?: return 100
        return (stale.factor / fresh.factor * 100).roundToInt()
    }
}
