package io.github.vaspyyy.guildmark.cooking.recipe

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.item.ItemArgument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

/**
 * Testing commands for the recipes (operators only; living-world/cook/design.md 10.3), the branch of Part A under
 * `/guildmark cook`:
 *
 *  - `roles [item]`: what the item in your hand, or the one you name, is in a pot; with neither, every food of the table.
 *  - `ideal <kind>`: this world's best mix of a kind and how far the book's bill is from it; `ideal <kind> seed <n>` for a
 *    made-up world of that seed; `ideal <kind> sample <n>` over `n` made-up worlds (the check against `cook_roles.py`).
 *  - `rate <kind> <food:count ...>`: a mix rated as that kind: its distance from the best mix, what the pot is taken for, what
 *    the ladle says at each level and the quality it can reach.
 *  - `quality <mix> <timing> <attention> <care> [level <n>]`: q and the tier, with the level's bonus.
 *  - `xp <kind> <tier> <portions> [give]`: what a batch pays, and with `give` pays it to you.
 *
 * Every line is worked out by [RecipeReport], which runs outside the game too; this file only turns it into coloured text.
 * With Hearthcraft off the commands still work (they are sums, and pay nothing), and say so.
 */
object RecipeCommand {
    /** The most made-up worlds `ideal ... sample` will work through. */
    private const val MAX_WORLDS = 100_000

    /** The most items in a mix `rate` will take. */
    private const val MAX_ITEMS = 96

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher, it.buildContext) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, context: CommandBuildContext) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("roles")
                                .executes { roles(it, null) }
                                .then(Commands.argument("item", ItemArgument.item(context)).executes { roles(it, ItemArgument.getItem(it, "item").createItemStack(1)) }),
                        )
                        .then(
                            Commands.literal("ideal").then(
                                kindArgument("kind")
                                    .executes { ideal(it, null) }
                                    .then(
                                        Commands.literal("sample").then(
                                            Commands.argument("n", IntegerArgumentType.integer(1, MAX_WORLDS)).executes { sample(it, IntegerArgumentType.getInteger(it, "n")) },
                                        ),
                                    )
                                    .then(
                                        Commands.literal("seed").then(
                                            Commands.argument("seed", LongArgumentType.longArg()).executes { ideal(it, LongArgumentType.getLong(it, "seed")) },
                                        ),
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("rate").then(
                                kindArgument("kind").then(
                                    Commands.argument("items", StringArgumentType.greedyString())
                                        .suggests { _, builder -> suggestFoods(builder) }
                                        .executes { rate(it, StringArgumentType.getString(it, "items")) },
                                ),
                            ),
                        )
                        .then(
                            Commands.literal("quality").then(
                                Commands.argument("mix", DoubleArgumentType.doubleArg(0.0, 1.0)).then(
                                    Commands.argument("timing", DoubleArgumentType.doubleArg(0.0, 1.0)).then(
                                        Commands.argument("attention", DoubleArgumentType.doubleArg(0.0, 1.0)).then(
                                            Commands.argument("care", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .executes { quality(it, null) }
                                                .then(
                                                    Commands.literal("level").then(
                                                        Commands.argument("level", IntegerArgumentType.integer(1, Skill.MAX)).executes { quality(it, IntegerArgumentType.getInteger(it, "level")) },
                                                    ),
                                                ),
                                        ),
                                    ),
                                ),
                            ),
                        )
                        .then(
                            Commands.literal("xp").then(
                                kindArgument("kind").then(
                                    Commands.argument("tier", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(Tier.entries.map { it.name.lowercase(Locale.ROOT) }, builder) }
                                        .then(
                                            Commands.argument("portions", IntegerArgumentType.integer(1, 99))
                                                .executes { xp(it, false) }
                                                .then(Commands.literal("give").executes { xp(it, true) }),
                                        ),
                                ),
                            ),
                        ),
                ),
        )
    }

    private fun kindArgument(name: String): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument(name, StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(DishKind.entries.map { it.id }, builder) }

    /** Suggests the foods of the table (and the colon that a count follows) for the word being typed in `rate`'s list. */
    private fun suggestFoods(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val from = builder.remaining.lastIndexOf(' ') + 1
        return SharedSuggestionProvider.suggest(Roles.current().foods.map { it.name + ":" }, builder.createOffset(builder.start + from))
    }

    // ---- The commands ----

    /** `roles [item]`: what the item in hand, or the named one, is in a pot; the whole table if there is neither. */
    private fun roles(context: CommandContext<CommandSourceStack>, named: ItemStack?): Int {
        val source = context.source
        val stack = named ?: source.player?.mainHandItem ?: ItemStack.EMPTY
        note(source)
        if (stack.isEmpty) {
            say(source, header("Foods of the pot"))
            for (r in RecipeReport.table(Roles.current())) say(source, lineOf(r))
            return 1
        }
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val back = Roles.returns(stack)
        say(source, header("Roles of $id"))
        for (r in RecipeReport.roles(id, Roles.food(stack), if (back.isEmpty) null else BuiltInRegistries.ITEM.getKey(back.item).toString())) say(source, lineOf(r))
        return 1
    }

    /** `ideal <kind>` and `ideal <kind> seed <n>`: the best mix of a kind in this world, or in a made-up one of that seed. */
    private fun ideal(context: CommandContext<CommandSourceStack>, seed: Long?): Int {
        val source = context.source
        val kind = kind(context) ?: return 0
        val world = seed ?: Ideal.worldSeed(source.level)
        val best = if (seed == null) Ideal.of(source.level, kind) else Ideal.make(seed, kind)
        note(source)
        say(source, header("Best mix of ${kind.id}" + if (seed == null) " in this world" else " in a made-up world"))
        for (r in RecipeReport.ideal(kind, world, best)) say(source, lineOf(r))
        return 1
    }

    /** `ideal <kind> sample <n>`: how far the book's bill is from the best mix over [n] made-up worlds. */
    private fun sample(context: CommandContext<CommandSourceStack>, n: Int): Int {
        val source = context.source
        val kind = kind(context) ?: return 0
        note(source)
        say(source, header("Best mix of ${kind.id} over $n made-up worlds"))
        for (r in RecipeReport.sampleRows(kind, RecipeReport.sample(kind, n))) say(source, lineOf(r))
        return 1
    }

    /** `rate <kind> <food:count ...>`: a mix rated as a kind. */
    private fun rate(context: CommandContext<CommandSourceStack>, text: String): Int {
        val source = context.source
        val kind = kind(context) ?: return 0
        val parsed = RecipeReport.parseItems(text, Roles.current()) { foodOfItem(it) }
        val error = parsed.error
        if (error != null) return fail(source, error)
        if (parsed.parts.isEmpty()) return fail(source, "Name what is in the pot, like beef:3 carrot:2.")
        if (parsed.parts.sumOf { it.second } > MAX_ITEMS) return fail(source, "That is more than $MAX_ITEMS items.")
        val player = source.player
        val level = player?.let { Skills.level(it, Skill.COOKING) } ?: 1
        val bonus = player?.let { Quality.bonus(it) } ?: Quality.levelBonus(level)
        note(source)
        say(source, header("Rate for ${kind.id}"))
        for (r in RecipeReport.rate(kind, parsed.parts, { Ideal.of(source.level, it) }, level, bonus)) say(source, lineOf(r))
        return 1
    }

    /** `quality <mix> <timing> <attention> <care> [level <n>]`: q and the tier with the level's bonus. */
    private fun quality(context: CommandContext<CommandSourceStack>, given: Int?): Int {
        val source = context.source
        val player = source.player
        val level = given ?: player?.let { Skills.level(it, Skill.COOKING) } ?: 1
        val ofTree = given == null && player != null
        val craft = if (ofTree && player != null) Quality.craft(player) else Quality.craftAt(level)
        val whose = if (ofTree) "from your tree" else "if every cooking node up to that level is taken"
        val mix = DoubleArgumentType.getDouble(context, "mix")
        val timing = DoubleArgumentType.getDouble(context, "timing")
        val attention = DoubleArgumentType.getDouble(context, "attention")
        val care = DoubleArgumentType.getDouble(context, "care")
        note(source)
        say(source, header("Quality"))
        for (r in RecipeReport.quality(mix, timing, attention, care, level, craft, whose)) say(source, lineOf(r))
        return 1
    }

    /** `xp <kind> <tier> <portions> [give]`: what a batch pays, and with `give` pays it. */
    private fun xp(context: CommandContext<CommandSourceStack>, give: Boolean): Int {
        val source = context.source
        val kind = kind(context) ?: return 0
        val name = StringArgumentType.getString(context, "tier")
        val tier = Tier.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return fail(source, "No such tier: $name. The tiers are ${Tier.entries.joinToString(", ") { it.name.lowercase(Locale.ROOT) }}.")
        val portions = IntegerArgumentType.getInteger(context, "portions")
        val player = source.player
        if (give && player == null) return fail(source, "Only a player can be paid.")
        note(source)
        say(source, header("XP of $portions portions of ${kind.id}, ${tier.name.lowercase(Locale.ROOT)}"))
        for (r in RecipeReport.xp(kind, tier, portions, if (player == null) 0 else CookXp.earnedToday(player))) say(source, lineOf(r))
        if (!give || player == null) return 1
        if (!Cooking.enabled()) return fail(source, "Hearthcraft is off, so nothing was paid.")
        if (!CookXp.paysXp(player)) return fail(source, "You are in creative or spectator mode, where the game pays no skill XP, so nothing was paid or counted.")
        val before = Skills.get(player).xp(Skill.COOKING)
        val paid = CookXp.award(player, kind, tier, portions, 1.0)
        val multiplier = (1.0 + Talents.build(player).skillXp(Skill.COOKING)).coerceAtLeast(0.0) * GuildmarkConfig.SKILL_XP_MULTIPLIER.get()
        say(
            source,
            line().append(label("Paid: ")).append(
                plain(
                    "$paid XP; your tree's XP bonus and the config's multiplier come on top (x${RecipeReport.f(multiplier, 2)}), so COOKING takes about " +
                        "${(paid * multiplier).roundToInt()} (it was $before XP). Today: ${CookXp.earnedToday(player)} XP counted",
                ),
            ),
        )
        return 1
    }

    // ---- Reading what was typed ----

    private fun kind(context: CommandContext<CommandSourceStack>): DishKind? {
        val name = StringArgumentType.getString(context, "kind")
        val kind = DishKind.byId(name.lowercase(Locale.ROOT))
        if (kind == null) fail(context.source, "No such kind: $name. The kinds are ${DishKind.entries.joinToString(", ") { it.id }}.")
        return kind
    }

    /** The food an item id is in the merged table, for `rate`: null if the game has no such item or it is not for the pot. */
    private fun foodOfItem(id: String): Food? {
        val key = Identifier.tryParse(id) ?: return null
        val item = BuiltInRegistries.ITEM.getOptional(key).orElse(null) ?: return null
        return Roles.food(ItemStack(item))
    }

    // ---- Words ----

    /** A [ReportRow] as a line: the label in yellow, the text in white, and what the ladle says in aqua. */
    private fun lineOf(row: ReportRow): MutableComponent {
        val out = line().append(label(row.label)).append(plain(row.text))
        val said = row.said ?: return out
        if (said.isEmpty()) return out.append(grey("(nothing)"))
        return out.append(Component.empty().withStyle(ChatFormatting.AQUA).append(Hints.component(said)))
    }

    /** A grey line that says the answers are only sums, when Hearthcraft is off. */
    private fun note(source: CommandSourceStack) {
        if (!Cooking.enabled()) say(source, grey("Hearthcraft is off (setting): these are only sums; nothing is paid or changed."))
    }

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
