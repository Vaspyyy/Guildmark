package io.github.vaspyyy.guildmark.cooking.meal

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Family
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * The commands of Part F (living-world/cook/design.md 10.3), under `/guildmark cook` and for operators only:
 * `cook meal <family> <tier> [minutes]`, a meal's effect as if the dish were eaten, and `cook chill [on|off|now]`, which
 * forces Winter Chill on or off or looks at the conditions now and says which fail. Brigadier joins this branch to the
 * root [io.github.vaspyyy.guildmark.cooking.CookCommand] makes, so neither edits the other.
 */
object MealCommand {
    private val TIERS = listOf(Tier.PLAIN, Tier.SOUND, Tier.FINE, Tier.HALLMARKED)

    /** The dish a family's name stands for: its usual one (the strong kind is named by its own id). */
    private val USUAL = mapOf(Family.WARMTH to DishKind.POTTAGE, Family.HEARTY to DishKind.STEW, Family.STEADY to DishKind.PIE, Family.PROVISIONED to DishKind.LOAF)

    /** What `cook meal` takes first: a family, or one of the nine dishes that give one (roast for Steady's strong kind, broth, strips...). */
    private val WHAT: List<String> = Family.entries.map { it.name.lowercase() } + DishKind.entries.filter { it.family != null }.map { it.id }

    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { build(it.dispatcher) }
    }

    private fun build(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(meal())
                        .then(chill()),
                ),
        )
    }

    // ---- cook meal ----

    private fun meal() = Commands.literal("meal").then(
        Commands.argument("what", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WHAT, builder) }
            .then(
                Commands.argument("tier", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(TIERS.map { it.name.lowercase() }, builder) }
                    .executes { eat(it, null, false) }
                    .then(Commands.literal("stale").executes { eat(it, null, true) })
                    .then(
                        Commands.argument("minutes", DoubleArgumentType.doubleArg(0.1, 1440.0))
                            .executes { eat(it, DoubleArgumentType.getDouble(it, "minutes"), false) }
                            .then(Commands.literal("stale").executes { eat(it, DoubleArgumentType.getDouble(it, "minutes"), true) }),
                    ),
            ),
    )

    private fun eat(context: CommandContext<CommandSourceStack>, minutes: Double?, stale: Boolean): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player can eat a meal.")
        if (!Cooking.enabled()) return fail(source, "Hearthcraft is off, so a meal gives nothing.")
        val what = StringArgumentType.getString(context, "what").lowercase()
        val tierName = StringArgumentType.getString(context, "tier").lowercase()
        val tier = TIERS.firstOrNull { it.name.lowercase() == tierName } ?: return fail(source, "The tier is plain, sound, fine or hallmarked.")
        val kind = Family.entries.firstOrNull { it.name.lowercase() == what }?.let { USUAL.getValue(it) }
            ?: DishKind.byId(what)?.takeIf { it.family != null }
            ?: return fail(source, "That is not a family (warmth, hearty, steady, provisioned) or a dish that gives one (${WHAT.drop(4).joinToString(", ")}).")
        val boon = Meals.Boon.of(kind, tier, if (stale) Stage.STALE else Stage.FRESH, minutes) ?: return fail(source, "That gives no effect.")
        val given = Meals.give(player, boon)
        val dish = Component.translatable("item.guildmark.dish_${kind.id}")
        when (given.outcome) {
            Meals.Outcome.TOOK -> say(source, Component.literal("As if a ${if (stale) "stale " else ""}${tier.name.lowercase()} ").withStyle(ChatFormatting.YELLOW).append(dish).append(Component.literal(" were eaten: ")).append(boon.describe()))
            Meals.Outcome.KEPT -> say(source, Component.literal("Nothing changed: what you have of ${boon.family.name.lowercase()} is as strong and as long, or stronger or longer.").withStyle(ChatFormatting.YELLOW))
        }
        given.replaced?.let { say(source, Component.literal("  It replaced ${it.name.lowercase()}: only one family holds at a time.").withStyle(ChatFormatting.GRAY)) }
        if (given.lifted) say(source, Component.literal("  It lifted the cold.").withStyle(ChatFormatting.GRAY))
        return 1
    }

    // ---- cook chill ----

    private fun chill() = Commands.literal("chill")
        .executes { now(it) }
        .then(Commands.literal("on").executes { force(it, true) })
        .then(Commands.literal("off").executes { force(it, false) })
        .then(Commands.literal("now").executes { now(it) })

    private fun force(context: CommandContext<CommandSourceStack>, on: Boolean): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player can feel the cold.")
        if (on && !Cooking.winterChill()) return fail(source, "Winter Chill is off (its switch, or Hearthcraft's), so nobody is ever Chilled.")
        Chill.force(player, on)
        say(
            source,
            Component.literal(
                if (on) "Chilled is forced on you: the season, the hour, the roof and your leather no longer count; a Warmth meal still keeps it off. `cook chill now` gives you back to the weather."
                else "Chilled is forced off you, whatever the weather. `cook chill now` gives you back to the weather.",
            ).withStyle(ChatFormatting.YELLOW),
        )
        return 1
    }

    private fun now(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player can feel the cold.")
        val was = Chill.forcing(player)
        Chill.force(player, null)
        say(source, Component.literal("== Winter Chill ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
        if (!Cooking.winterChill()) say(source, Component.literal("  Winter Chill is off (its switch, or Hearthcraft's): none of this is felt. This is how the conditions would stand.").withStyle(ChatFormatting.RED))
        if (was != null) say(source, Component.literal("  The forcing is lifted: the cold follows the weather again.").withStyle(ChatFormatting.GRAY))
        for (line in Chill.report(player)) say(source, line)
        return 1
    }

    // ---- Words ----

    private fun say(source: CommandSourceStack, text: Component): Int {
        source.sendSuccess({ text }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, text: String): Int {
        source.sendFailure(Component.literal(text))
        return 0
    }
}
