package io.github.vaspyyy.guildmark.cooking.dish

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.Verdict
import io.github.vaspyyy.guildmark.cooking.recipe.Hints
import io.github.vaspyyy.guildmark.cooking.recipe.Ideal
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.abs
import kotlin.math.min

/**
 * Part D's branch of the testing commands (operators only; living-world/cook/design.md 10.3), under `/guildmark cook`
 * (Brigadier merges the nodes, so the root in `CookCommand` is not edited):
 *
 *  - `give <kind> [tier] [n] [daysOld]`: n portions (default 1) of a kind (the twelve, `gruel` or `scorched`) at a tier
 *    (plain, sound, fine or hallmarked; default sound), cooked that many days ago (default 0), in your name;
 *  - `age <days>`: moves the day the dish in your hand was cooked back that many days (forward, if negative), and says how it reads;
 *  - `book`: gives you a Cook's Book; `book learn <kind>`: teaches your journal what this world's ideal says is out in the
 *    kind's standard bill (a cheat for testing, the only one: the Book itself never shows the ideal); `book reset`: an empty
 *    journal, and the first use of a station will hand you a Book again.
 */
object DishCommand {
    /** The most portions one `give` makes, and the most days old or aged. */
    private const val MAX_PORTIONS = 256
    private const val MAX_DAYS = 3650

    /** The tiers a `give` can make, in the order they are suggested. */
    private val TIERS = listOf(Tier.PLAIN, Tier.SOUND, Tier.FINE, Tier.HALLMARKED)

    /** The two that belong to no kind. */
    private const val GRUEL = "gruel"
    private const val SCORCHED = "scorched"

    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        fun kind(name: String, extra: List<String>): RequiredArgumentBuilder<CommandSourceStack, String> =
            Commands.argument(name, StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(DishKind.entries.map { it.id } + extra, builder) }
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("give").then(
                                kind("kind", listOf(GRUEL, SCORCHED)).executes { give(it, Tier.SOUND, 1, 0) }.then(
                                    Commands.argument("tier", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(TIERS.map { it.name.lowercase() }, builder) }
                                        .executes { give(it, tier(it) ?: return@executes 0, 1, 0) }
                                        .then(
                                            Commands.argument("n", IntegerArgumentType.integer(1, MAX_PORTIONS))
                                                .executes { give(it, tier(it) ?: return@executes 0, IntegerArgumentType.getInteger(it, "n"), 0) }
                                                .then(
                                                    Commands.argument("daysOld", IntegerArgumentType.integer(0, MAX_DAYS)).executes {
                                                        give(it, tier(it) ?: return@executes 0, IntegerArgumentType.getInteger(it, "n"), IntegerArgumentType.getInteger(it, "daysOld"))
                                                    },
                                                ),
                                        ),
                                ),
                            ),
                        )
                        .then(
                            Commands.literal("age").then(
                                Commands.argument("days", IntegerArgumentType.integer(-MAX_DAYS, MAX_DAYS)).executes { age(it) },
                            ),
                        )
                        .then(
                            Commands.literal("book")
                                .executes { book(it) }
                                .then(Commands.literal("learn").then(kind("learn", emptyList()).executes { learn(it) }))
                                .then(Commands.literal("reset").executes { reset(it) }),
                        ),
                ),
        )
    }

    // ---- give ----

    private fun tier(context: CommandContext<CommandSourceStack>): Tier? {
        val word = StringArgumentType.getString(context, "tier")
        return TIERS.firstOrNull { it.name.equals(word, ignoreCase = true) } ?: run {
            context.source.sendFailure(Component.literal("Unknown tier '$word': ${TIERS.joinToString(", ") { it.name.lowercase() }}."))
            null
        }
    }

    private fun give(context: CommandContext<CommandSourceStack>, tier: Tier, n: Int, daysOld: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val word = StringArgumentType.getString(context, "kind").lowercase()
        val kind = DishKind.entries.firstOrNull { it.id == word }
        if (kind == null && word != GRUEL && word != SCORCHED) {
            source.sendFailure(Component.literal("Unknown dish '$word': ${(DishKind.entries.map { it.id } + listOf(GRUEL, SCORCHED)).joinToString(", ")}."))
            return 0
        }
        val data = DishData(tier, Cooking.today(level) - daysOld, player.scoreboardName, player.uuid, 0)
        val stacks = ArrayList<ItemStack>()
        var left = n
        while (left > 0) {
            val stack = when {
                kind != null -> Dishes.make(kind, data, 1)
                word == GRUEL -> ItemStack(CookRegistry.GRUEL.get()).also {
                    it.set(CookRegistry.DISH.get(), data.copy(tier = Tier.PLAIN))
                    it.set(CookRegistry.DISH_TIER.get(), Tier.PLAIN.ordinal)
                }
                else -> ItemStack(CookRegistry.SCORCHED_MESS.get())
            }
            stack.count = min(left, stack.maxStackSize)
            left -= stack.count
            Freshness.sync(level, stack)
            stacks.add(stack)
        }
        for (stack in stacks) player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        val first = stacks.first()
        val stage = Freshness.stageOf(level, first)
        val age = when {
            word == SCORCHED -> ""
            daysOld == 0 -> ", cooked today"
            else -> ", cooked $daysOld day${if (daysOld == 1) "" else "s"} ago (${stageWord(stage)})"
        }
        source.sendSuccess({ Component.literal("Gave $n × ").append(first.hoverName).append(age) }, true)
        return n
    }

    // ---- age ----

    private fun age(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val days = IntegerArgumentType.getInteger(context, "days")
        val stack = listOf(player.mainHandItem, player.offhandItem).firstOrNull { (it.item as? DishItem)?.scorched == false }
        if (stack == null) {
            source.sendFailure(Component.literal("Hold a dish in your hand (Scorched mess does not keep)."))
            return 0
        }
        val today = Cooking.today(level)
        val before = stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN
        val moved = before.copy(day = min(min(before.day, today) - days, today))
        stack.set(CookRegistry.DISH.get(), moved)
        val stage = Freshness.sync(level, stack)
        val old = Freshness.age(today, moved)
        val note = if (Cooking.spoilage()) "" else " (Dishes Go Stale is off, so it reads Fresh)"
        source.sendSuccess(
            {
                Component.literal(if (days >= 0) "Aged " else "Freshened ").append(stack.hoverName)
                    .append(" by ${abs(days)} day${if (abs(days) == 1) "" else "s"}: now $old day${if (old == 1L) "" else "s"} old, ${stageWord(stage)}$note.")
            },
            true,
        )
        return 1
    }

    private fun stageWord(stage: Stage): String = when (stage) {
        Stage.FRESH -> "fresh"
        Stage.STALE -> "stale"
        Stage.GONE -> "gone off"
    }

    // ---- book ----

    private fun book(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        player.inventory.placeItemBackInInventory(ItemStack(CookRegistry.COOK_BOOK.get()), Prediction.SERVER_ONLY)
        source.sendSuccess({ Component.literal("Gave you a Cook's Book. Its journal is yours whether you hold it or not.") }, true)
        return 1
    }

    private fun learn(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val word = StringArgumentType.getString(context, "learn").lowercase()
        val kind = DishKind.entries.firstOrNull { it.id == word }
        if (kind == null) {
            source.sendFailure(Component.literal("Unknown dish '$word': ${DishKind.entries.joinToString(", ") { it.id }}."))
            return 0
        }
        if (!Cooking.enabled()) {
            source.sendFailure(Component.literal("Hearthcraft is off, so nothing is learned."))
            return 0
        }
        // What this world's ideal says is out in the standard bill: every role out by two fifths of an item or more, the three furthest first
        val mix = BillFoods.share(kind)
        val ideal = Ideal.of(level, kind)
        val total = kind.bill.values.sum().toDouble()
        val taught = ArrayList<Verdict>()
        if (ideal.sum() > 0.0) {
            taught += Role.entries
                .map { Verdict(it, mix[it.ordinal] > ideal[it.ordinal], abs(mix[it.ordinal] - ideal[it.ordinal]) * total, 0) }
                .filter { it.items >= 0.4 }
                .sortedByDescending { it.items }
                .take(3)
        }
        // Or, while the ideal is not there, what a keeper's verdict on the standard bill says
        if (taught.isEmpty()) {
            val verdict = Hints.verdict(level, kind, mix)
            if (verdict.items > 0.0) taught += verdict
        }
        if (taught.isEmpty()) {
            source.sendFailure(Component.literal("There is nothing to learn about ${kind.id} yet: this world's ideal for it is not known."))
            return 0
        }
        for (verdict in taught) Journal.learn(player, kind, verdict)
        val said = taught.joinToString(", ") { "${it.role.name.lowercase()} too ${if (it.over) "high" else "low"} by ${"%.1f".format(it.items)}" }
        source.sendSuccess({ Component.literal("Your journal now knows about ${kind.id}: $said.").withStyle(ChatFormatting.WHITE) }, true)
        return taught.size
    }

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        player.setData(CookRegistry.COOK_JOURNAL, CookJournal())
        player.setData(CookRegistry.COOK_BOOK_GIVEN, false)
        source.sendSuccess({ Component.literal("Your journal is empty. The first use of a station will hand you a Cook's Book again.") }, true)
        return 1
    }
}
