package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.trade.scenes.ReliefCheer
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * `/guildmark trade witness [town] [kind] [units] [house]` and `/guildmark trade cheer [big | reset]` (the town's answer to a delivery,
 * living-world/trader/design.md Part 6; operators only), to see what a delivery does with no ticket to fill and nobody to carry the goods.
 *
 * `witness` makes the town see a delivery now. [town] is a capital's name, or `x_z` for where its heart stands, or `here` (also what it is when it is
 * left out) for the capital you stand in; [kind] is `hauled` (a consignment of 128 units, the default), `relief` (96), `feast` (64) or `parcel` (32);
 * [units] says how many instead; `house` has it come by a carter of your House, not by you. The delivery is of the good the town is shortest of, to
 * the shop that deals in it, from the nearest other open market, and it is answered just as a ticket filled is ([WorldAnswers.answer]): the page, the
 * cheer (only if you are in that town, near the shop, and it is a delivery the street cheers), the talk and warmth, and for a relief the guild's news,
 * under the same switches and the same hour between two cheers. Nothing is paid and no ticket is touched. It says what each did.
 *
 * `cheer` plays the street's cheer at your feet (`cheer big` the bigger, as for a relief) whatever the switches say and whenever you like, and does not
 * start the hour a real one waits; it says who joined and how long it takes, and when a real delivery here could cheer next. `cheer reset` forgets
 * when each town last cheered. Both work with the town's answer switched off, except that `witness` then only says so.
 *
 * [WorldAnswers.register] calls [init]. The `trade` literal is the one [TradeCommand] has, with the same permission, so Brigadier merges them by name.
 */
object WorldCommand {
    /** What the first word of `witness` can be, for the capital you stand in. */
    private const val HERE = "here"

    /** The most units a made-up delivery can be. */
    private const val MOST_UNITS = 1024

    /** What each kind of made-up delivery is, and how many units it comes to unless said. */
    private class Spec(val kind: HaulKind, val units: Int)

    private val KINDS: Map<String, Spec> = linkedMapOf(
        "hauled" to Spec(HaulKind.CONSIGNMENT, 128),
        "relief" to Spec(HaulKind.RELIEF, 96),
        "feast" to Spec(HaulKind.FEAST, 64),
        "parcel" to Spec(HaulKind.PARCEL, 32),
    )

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("trade")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("witness")
                                .executes { witness(it, null, KINDS.keys.first(), null, false) }
                                .then(
                                    Commands.argument("town", StringArgumentType.word())
                                        .suggests { c, b -> SharedSuggestionProvider.suggest(listOf(HERE) + towns(c.source), b) }
                                        .executes { witness(it, StringArgumentType.getString(it, "town"), KINDS.keys.first(), null, false) }
                                        .then(
                                            Commands.argument("kind", StringArgumentType.word())
                                                .suggests { _, b -> SharedSuggestionProvider.suggest(KINDS.keys, b) }
                                                .executes { witness(it, StringArgumentType.getString(it, "town"), StringArgumentType.getString(it, "kind"), null, false) }
                                                .then(
                                                    Commands.argument("units", IntegerArgumentType.integer(1, MOST_UNITS))
                                                        .executes {
                                                            witness(
                                                                it, StringArgumentType.getString(it, "town"), StringArgumentType.getString(it, "kind"),
                                                                IntegerArgumentType.getInteger(it, "units"), false,
                                                            )
                                                        }
                                                        .then(
                                                            Commands.literal("house").executes {
                                                                witness(
                                                                    it, StringArgumentType.getString(it, "town"), StringArgumentType.getString(it, "kind"),
                                                                    IntegerArgumentType.getInteger(it, "units"), true,
                                                                )
                                                            },
                                                        ),
                                                ),
                                        ),
                                ),
                        )
                        .then(
                            Commands.literal("cheer")
                                .executes { cheer(it, false) }
                                .then(Commands.literal("big").executes { cheer(it, true) })
                                .then(Commands.literal("reset").executes { reset(it) }),
                        ),
                ),
        )
    }

    // ---- The delivery ----

    private fun witness(context: CommandContext<CommandSourceStack>, town: String?, kindWord: String, units: Int?, house: Boolean): Int {
        val source = context.source
        val level = source.level
        val player = source.player ?: return fail(source, "Run this as a player: the delivery is brought in your name.")
        val entry = if (town == null || town.equals(HERE, ignoreCase = true)) {
            standing(source) ?: return 0
        } else {
            find(source, town) ?: return fail(source, "No open market is called $town: " + towns(source).joinToString(", ") + ".")
        }
        val spec = KINDS[kindWord.lowercase(Locale.ROOT)] ?: return fail(source, "No such delivery: $kindWord (one of ${KINDS.keys.joinToString(", ")}).")
        val n = units ?: spec.units
        val kind = if (spec.kind == HaulKind.CONSIGNMENT && n < Witness.HAULED_UNITS) HaulKind.PARCEL else spec.kind
        val place = nameAt(level, entry.capital)
        val (good, shop) = wanted(level, entry)
        val from = origin(level, entry)
        val taker = if (house) Taker.House(player.uuid) else Taker.Player(player.uuid)
        val haul = Haul(0, entry.capital, shop, good, n, 0, kind, 0L)
        val delivery = Delivery(taker, entry.capital, shop, good, n, 0, kind, from)

        say(source, header("The town of $place sees a delivery"))
        val by = if (house) "${player.name.string}'s carter" else player.name.string
        val road = from?.let { ", from ${nameAt(level, it)} by ${WorldAnswers.way(WorldAnswers.sideOf(entry.capital, it))}" }.orEmpty()
        say(source, line().append(plain("$by brings $n ${good.id} to the ${shop.id} (a ${kind.id}$road). ")).append(grey("Nothing is paid and no ticket is touched.")))
        val answered = WorldAnswers.answer(level, haul, delivery, player.name.string)
        answered.skipped?.let { return fail(source, "The town did not answer: $it.") }

        say(source, line().append(label("Page: ")).append(plain(answered.page.ifEmpty { "nothing" })).append(grey(". `fortune`, standing in the town, shows it with its days.")))
        val talk = answered.talk
        val said = if (talk == null) {
            "it failed (see the log)"
        } else {
            val warmed = if (talk.warmed > 0) "${talk.warmed} of the shop's own think the better of $by" else "nobody known keeps the ${shop.id}, so no one was warmed"
            val rumour = talk.rumour
            val told = if (rumour != null) {
                "; a ${rumour.kind} rumour (#${rumour.id}) at heat ${"%.2f".format(Locale.ROOT, rumour.heat)}, ${rumour.scope.name.lowercase(Locale.ROOT)} wide, " +
                    "${talk.knew} knowing it first"
            } else {
                "; not talked of (it takes ${Witness.HAULED_UNITS} units, or a relief)"
            }
            warmed + told
        }
        // The rumour is told by the trade's topics: whoever knows it says it when asked "About the trade..." (not in passing to the one it is about)
        val where = if (talk?.rumour != null) {
            ". `memory` by the shop's keeper and `rumour list` show it; ask the keeper, or anyone who stood in the cheer, \"About the trade...\" to hear it told."
        } else {
            ". `memory` by the shop's keeper shows it."
        }
        say(source, line().append(label("Talk: ")).append(plain(said)).append(grey(where)))
        say(source, line().append(label("News: ")).append(plain(answered.news ?: "only a relief is chalked")))
        val cheer = answered.cheer
        say(source, line().append(label("Cheer: ")).append(plain(if (cheer == null) "it failed (see the log)" else describe(cheer))))
        return 1
    }

    /** The good [entry]'s town is shortest of against what it keeps, and the shop that deals in it (the first of its book's that does, else the first it has, else the hall). */
    private fun wanted(level: ServerLevel, entry: Markets.Entry): Pair<Good, Shop> {
        val good = Good.TRADED.maxByOrNull { entry.town.target(it) - entry.market.stock(it) } ?: Good.WHEAT
        val shops = Orders.get(level)[entry.capital]?.shops?.keys.orEmpty()
        return good to (shops.firstOrNull { good in it.goods } ?: shops.firstOrNull() ?: Shop.HALL)
    }

    /** The heart of the nearest other open market, for the goods to have come from; null if [entry] is the only one open. */
    private fun origin(level: ServerLevel, entry: Markets.Entry): BlockPos? =
        Markets.get(level).all().filter { it !== entry }.minByOrNull { it.capital.distSqr(entry.capital) }?.capital

    // ---- The cheer ----

    private fun cheer(context: CommandContext<CommandSourceStack>, big: Boolean): Int {
        val source = context.source
        val level = source.level
        val player = source.player ?: return fail(source, "Stand where the street can see you: this needs a player.")
        if (!TradeConfig.cheer()) {
            say(source, grey("The cheer is switched off (setting: Cheer, or The Town Answers, or Trader's Life); this plays all the same."))
        }
        val played = ReliefCheer.at(level, player.blockPosition(), player, big, force = true)
        if (!played.played) return fail(source, "Nothing played: ${played.why}.")
        say(source, line().append(label(if (big) "The big cheer: " else "The cheer: ")).append(plain(describe(played))))
        Scenes.here(level, player)?.let { here ->
            val wait = ReliefCheer.cooling(level, here.key)
            say(
                source,
                grey(
                    if (wait <= 0) "A real delivery here may cheer now."
                    else "A real delivery here may cheer again in ${(wait + 19) / 20} seconds (the hour between two; `trade cheer reset` clears it).",
                ),
            )
        }
        return 1
    }

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        val n = ReliefCheer.reset()
        say(
            context.source,
            plain(if (n == 0) "No town had cheered lately: any may." else "Forgot when $n town${if (n == 1) "" else "s"} last cheered: any may cheer again now."),
        )
        return 1
    }

    /** [p] in words: who joined, who answers, what the children run to and how long it takes; or why it did not play. */
    private fun describe(p: ReliefCheer.Played): String {
        p.why?.let { return "none, $it" }
        val parts = ArrayList<String>()
        parts += "${p.adults} grown-up${if (p.adults == 1) "" else "s"}"
        parts += "${p.children} ${if (p.children == 1) "child" else "children"}"
        p.keeper?.let { parts += "the ${it.name.lowercase(Locale.ROOT)} answering" }
        if (p.children > 0) p.carrier?.let { parts += "the children running to the ${it.lowercase(Locale.ROOT)}" }
        return parts.joinToString(", ") + "; about ${p.seconds} seconds"
    }

    // ---- The town asked about ----

    /** What the commands call the capital whose heart is [capital]: its name as its orders keep it or its ground has it, or where it stands if neither is known now. */
    private fun nameAt(level: ServerLevel, capital: BlockPos): String = Witness.townOf(level, capital).ifEmpty { "${capital.x}_${capital.z}" }

    /** The names of every open market, for the suggestions and for saying what there is to choose from. */
    private fun towns(source: CommandSourceStack): List<String> {
        val level = source.level
        return Markets.get(level).all().map { nameAt(level, it.capital) }.sorted()
    }

    /** The open market called [name] (or at "x_z"); the nearest to [source] if two capitals share a name. */
    private fun find(source: CommandSourceStack, name: String): Markets.Entry? {
        val level = source.level
        val at = source.position
        return Markets.get(level).all()
            .filter { nameAt(level, it.capital).equals(name, ignoreCase = true) || "${it.capital.x}_${it.capital.z}" == name }
            .minByOrNull { square(it.capital.x - at.x) + square(it.capital.z - at.z) }
    }

    private fun square(x: Double): Double = x * x

    /** The capital's market the sender stands in, opened now if need be; null (and why, said) if there is none. */
    private fun standing(source: CommandSourceStack): Markets.Entry? {
        val level = source.level
        val player = source.player ?: run {
            fail(source, "Stand in a capital: this needs a player.")
            return null
        }
        val capital = Capitals.covering(level, player.blockPosition()) ?: run {
            fail(source, "You're not in a capital: stand in one, or name a town.")
            return null
        }
        return Markets.of(level, capital) ?: run {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            null
        }
    }

    // ---- Words ----

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
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED))
        return 0
    }
}
