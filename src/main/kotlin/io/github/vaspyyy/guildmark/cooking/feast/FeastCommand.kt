package io.github.vaspyyy.guildmark.cooking.feast

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.DishTooltip
import io.github.vaspyyy.guildmark.festival.FestivalContest
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import io.github.vaspyyy.guildmark.festival.FestivalPrizes
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.sqrt

/**
 * `/guildmark cook feast` (operators; living-world/cook/design.md 6.8, 10.3, Part L), the Harvest Table at the nearest Sheafmoot:
 *
 *  - `feast`: open or not, whether it is held there (a capital's only) and the switch, the trestle's plates, and each entry: name,
 *    kinds and points, town or player;
 *  - `feast score`: what the dish in your hand would earn there, with the working, or why it is refused (it is only weighed);
 *  - `feast enter`: enters the dish in your hand as if shown to the host, with no host near and no tasting;
 *  - `feast town [reroll]`: the inn's and the bakery's entries, and throws them again;
 *  - `feast award`: pays the Harvest Table's prizes now (it pays once).
 *
 * Its words are an operator's, in plain English; what the host says is the host's own lines (the same as at the fair).
 */
object FeastCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("feast")
                                .executes { table(it) }
                                .then(Commands.literal("score").executes { score(it) })
                                .then(Commands.literal("enter").executes { enter(it) })
                                .then(Commands.literal("town").executes { town(it, false) }.then(Commands.literal("reroll").executes { town(it, true) }))
                                .then(Commands.literal("award").executes { award(it) }),
                        ),
                ),
        )
    }

    // ---- Where it is run from ----

    /** The live Sheafmoot nearest where the command is run. */
    private fun sheafmoot(source: CommandSourceStack): FestivalSite? =
        Festivities.all().filter { it.live && it.kind == FestivalKind.SHEAFMOOT }.minByOrNull { it.centre.distanceToSqr(source.position) }

    /** The nearest live Sheafmoot, or null once the operator has been told there is none. */
    private fun site(source: CommandSourceStack): FestivalSite? = sheafmoot(source) ?: run {
        fail(source, "No Sheafmoot is on. In a capital, /guildmark festival start sheafmoot and then festival phase fair hold one.")
        null
    }

    /** Whether the Harvest Table is held at [site]; if not, the operator is told why and it is false. */
    private fun held(source: CommandSourceStack, site: FestivalSite): Boolean {
        why(site)?.let {
            fail(source, "The Harvest Table is not held at ${site.townName}: $it.")
            return false
        }
        return true
    }

    /** Why the Harvest Table is not held at [site], or null if it is. */
    private fun why(site: FestivalSite): String? = when {
        !Cooking.cookOff() -> "its switch is off (Harvest Table, Hearthcraft), so the host only looks at the harvest"
        !site.contest.capital -> "this is a village's Sheafmoot, and only a capital's holds the table (a dish shown here goes to the Produce Show as anything else does)"
        else -> null
    }

    /** The dish in [player]'s hand, the main hand's first. */
    private fun dish(player: ServerPlayer): ItemStack? = listOf(player.mainHandItem, player.offhandItem).firstOrNull { it.item is DishItem }

    private fun far(source: CommandSourceStack, site: FestivalSite): String = "${sqrt(site.centre.distanceToSqr(source.position)).toInt()} blocks off"

    // ---- cook feast ----

    private fun table(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val site = site(source) ?: return 0
        val contest = site.contest
        val state = when {
            why(site) != null -> "not held: ${why(site)}"
            contest.open -> "open, the host tastes dishes"
            site.phase == FestivalPhase.SETUP -> "not open yet (it opens with the fair)"
            else -> "closed" + if (site.cookAwarded) ", paid" else ", not paid yet"
        }
        say(source, line().append(label("Harvest Table at ${site.townName}")).append(plain(" (${far(source, site)}, ")).append(site.phase.title).append(plain("): $state.")))
        if (why(site) != null) return 1
        val plates = site.plan.trestle.size
        say(
            source,
            grey(
                if (plates == 0) "No trestle: there was no room beside the bales, so the table has no plates (it is held all the same)."
                else "Trestle: $plates plates, ${HarvestTable.dressed(site)} of them showing a dish.",
            ),
        )
        val results = contest.cookResults()
        if (results.isEmpty()) say(source, grey("No entries yet. The town's cooks enter when the fair opens; a player enters by showing the host a dish they cooked."))
        val town = HarvestTable.town(site)
        results.forEachIndexed { i, e ->
            val c = line().append(label("${i + 1}. ")).append(FestivalContest.named(e.name).copy().withStyle(ChatFormatting.WHITE)).append(plain(", ${e.score} points"))
            if (e.npc) {
                val dish = town[e.id]
                c.append(grey(", the town's (" + (if (dish?.inn != false) "the inn" else "the bakery") + ")"))
                if (dish != null) c.append(grey(": ")).append(DishTooltip.tierWord(dish.tier).withStyle(ChatFormatting.GRAY)).append(grey(" ")).append(HarvestTable.dishName(dish.kind).withStyle(ChatFormatting.GRAY))
            } else {
                c.append(grey(", a player: "))
                val kinds = contest.dishesOf(e.id).entries.sortedByDescending { it.value }
                kinds.forEachIndexed { j, (kind, points) ->
                    if (j > 0) c.append(grey(", "))
                    val counted = j < FestivalContest.BEST_DISHES
                    c.append(HarvestTable.dishName(kind).withStyle(if (counted) ChatFormatting.GRAY else ChatFormatting.DARK_GRAY))
                    c.append(Component.literal(" $points" + if (counted) "" else " (not counted)").withStyle(if (counted) ChatFormatting.GRAY else ChatFormatting.DARK_GRAY))
                }
            }
            say(source, c)
        }
        say(source, grey("Entries are kept in memory only, like the Produce Show's: a restart in the middle of the fair loses them."))
        return 1
    }

    // ---- cook feast score ----

    private fun score(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val stack = dish(player) ?: return fail(source, "Hold a dish in your hand. Anything else shown to the host at a Sheafmoot is the Produce Show's.")
        val site = sheafmoot(source)
        // Weighed by the nearest capital's table as it stands, else by an open one with nothing on it yet
        val contest = site?.contest?.takeIf { it.capital } ?: FestivalContest(FestivalKind.SHEAFMOOT, 0L, capital = true).also { it.open = true }
        val tasted = contest.taste(source.level, player, stack, record = false) ?: return fail(source, "That's not a dish the table judges.")
        val where = when {
            site == null -> "no Sheafmoot is on, so as at an open table with nothing of yours on it"
            !site.contest.capital -> "the Sheafmoot at ${site.townName} is a village's and holds no table (its Produce Show would look at this), so as at a capital's"
            else -> "at ${site.townName}'s table as it stands"
        }
        val dish = tasted.dish
        if (dish == null || !tasted.judged) {
            say(source, line().append(label("Refused ")).append(grey("($where): ")).append(Component.translatable(tasted.refusal ?: "message.guildmark.feast.not_dish", *tasted.args.toTypedArray())))
            say(source, grey("A refusal costs nothing: the dish stays yours."))
            return 1
        }
        say(source, line().append(label("${tasted.points} points: ")).append(HarvestTable.working(dish, tasted.tier)).append(plain(".")))
        say(
            source,
            grey(
                "Its tier ${HarvestTable.tierPoints(tasted.tier)}, times 1 + 0.15 for each of the kind's ${dish.complexity} steps of complexity, " +
                    (if (HarvestTable.autumn(dish)) "times ${HarvestTable.AUTUMN} for a kind autumn favours" else "and no autumn favour for this kind") +
                    ", rounded. Shown $where, your best ${FestivalContest.BEST_DISHES} kinds would come to ${tasted.total}" +
                    (if (tasted.before > 0) " (this kind stood at ${tasted.before})" else "") + ". Nothing was entered.",
            ),
        )
        return tasted.points
    }

    // ---- cook feast enter ----

    private fun enter(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val site = site(source) ?: return 0
        if (!site.contest.capital) return fail(source, "The Sheafmoot at ${site.townName} is a village's: it holds no Harvest Table, and its Produce Show would look at a dish shown there.")
        val stack = dish(player) ?: return fail(source, "Hold a dish in your hand.")
        val tasted = site.contest.taste(source.level, player, stack) ?: return fail(source, "That's not a dish the table judges.")
        HarvestTable.enter(source.level, site, player, tasted)
        val mine = site.contest.cook(player.uuid)
        say(
            source,
            line().append(label(if (tasted.judged) "Entered at ${site.townName}: " else "Not entered at ${site.townName}: ")).append(
                grey(
                    if (tasted.judged) "${tasted.points} points for it; your best ${FestivalContest.BEST_DISHES} kinds make ${mine?.score ?: tasted.total}, " +
                        "${place(site, player)}. The dish stays in your hand."
                    else "the host's words are above, and the dish stays yours.",
                ),
            ),
        )
        return 1
    }

    private fun place(site: FestivalSite, player: ServerPlayer): String {
        val results = site.contest.cookResults()
        val i = results.indexOfFirst { it.id == player.uuid }
        return if (i < 0) "no place" else "in place ${i + 1} of ${results.size}"
    }

    // ---- cook feast town [reroll] ----

    private fun town(context: CommandContext<CommandSourceStack>, reroll: Boolean): Int {
        val source = context.source
        val level = source.level
        val site = site(source) ?: return 0
        if (!held(source, site)) return 0
        if (reroll) HarvestTable.reroll(level, site)
        val town = HarvestTable.town(site)
        if (town.isEmpty()) {
            say(source, grey("The town's cooks have not entered at ${site.townName} yet: they enter when the fair opens (or with the first dish judged, if the switch was turned on since). cook feast town reroll enters them now."))
            return 1
        }
        val index = HarvestTable.index(level, site.key)
        say(
            source,
            line().append(label((if (reroll) "Thrown again" else "The town's cooks") + " at ${site.townName}: ")).append(
                grey("the price index stands at ${String.format(Locale.ROOT, "%.2f", index)} this week, so they gain ${String.format(Locale.ROOT, "%.1f", HarvestTable.lift(level, site.key))} points (0 to 10)."),
            ),
        )
        val results = site.contest.cookResults()
        for ((id, dish) in town) {
            val e = site.contest.cook(id) ?: continue
            val place = results.indexOfFirst { it.id == id } + 1
            say(
                source,
                line().append(label(if (dish.inn) "The inn: " else "The bakery: ")).append(FestivalContest.named(e.name).copy().withStyle(ChatFormatting.WHITE))
                    .append(plain(", ${e.score} points"))
                    .append(grey(" (${if (dish.inn) "32 to 62" else "30 to 58"} before the lift), laid as "))
                    .append(DishTooltip.tierWord(dish.tier).withStyle(ChatFormatting.GRAY)).append(grey(" ")).append(HarvestTable.dishName(dish.kind).withStyle(ChatFormatting.GRAY))
                    .append(grey(if (place > 0) ", place $place." else ".")),
            )
        }
        say(source, grey("They take a place and are never paid; a player must beat them both for the Gilded Ladle."))
        return 1
    }

    // ---- cook feast award ----

    private fun award(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val site = site(source) ?: return 0
        if (!held(source, site)) return 0
        if (site.cookAwarded) return fail(source, "The Harvest Table at ${site.townName} has been paid already: it pays once.")
        val results = site.contest.cookResults()
        if (results.none { !it.npc }) return fail(source, "No player has a dish on the table at ${site.townName}: nothing to pay (the town's cooks are never paid).")
        site.awardCook(level)
        say(source, line().append(label("Paid the Harvest Table at ${site.townName}:")))
        results.forEachIndexed { i, e ->
            val what = when {
                e.npc -> "the town's: a place, nothing paid"
                i == 0 -> "${FestivalPrizes.MARKS[0]} Marks and the Gilded Ladle"
                else -> "${FestivalPrizes.MARKS.getOrElse(i) { FestivalPrizes.MARKS_ENTERED }} Marks"
            }
            say(source, line().append(label("${i + 1}. ")).append(FestivalContest.named(e.name).copy().withStyle(ChatFormatting.WHITE)).append(grey(" (${e.score}): $what")))
        }
        say(source, grey("A player who is not on has theirs at their next login. Dishes shown from now on still place, but are not paid."))
        return 1
    }

    // ---- Words ----

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
