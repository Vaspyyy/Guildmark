package io.github.vaspyyy.guildmark.economy

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.random.Random

/**
 * Testing commands for supplying the capitals (operators only). `/guildmark supplier [player]` is a player's standing
 * at every capital (rank, points, orders filled and let lapse, what they've bought there lately) and the orders they
 * hold; `supplier set <points> [player]` sets it at the capital you stand in. Standing in a capital, `/guildmark
 * capital orders` lists its orders and what its businesses are; `orders post [good]` posts what the town would ask
 * for at dawn (or an order for that good); `orders take <id>` takes one as you would at its screen; `orders fill <id>
 * [count]` delivers to one of yours with goods from nowhere (and takes it for you first, if it's open); `orders lapse
 * [id]` lets one lapse now, or every one held there.
 */
object SupplierCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("supplier")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { standing(it, it.source.playerOrException) }
                        .then(Commands.argument("player", EntityArgument.player()).executes { standing(it, EntityArgument.getPlayer(it, "player")) })
                        .then(
                            Commands.literal("set").then(
                                Commands.argument("points", DoubleArgumentType.doubleArg(0.0, 100000.0))
                                    .executes { set(it, it.source.playerOrException) }
                                    .then(Commands.argument("player", EntityArgument.player()).executes { set(it, EntityArgument.getPlayer(it, "player")) }),
                            ),
                        ),
                )
                .then(
                    Commands.literal("capital").then(
                        Commands.literal("orders")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes { list(it) }
                            .then(
                                Commands.literal("post")
                                    .executes { post(it, null) }
                                    .then(
                                        Commands.argument("good", StringArgumentType.word())
                                            .suggests { _, b -> SharedSuggestionProvider.suggest(Good.TRADED.map { it.id }, b) }
                                            .executes { post(it, StringArgumentType.getString(it, "good")) },
                                    ),
                            )
                            .then(Commands.literal("take").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes { take(it, IntegerArgumentType.getInteger(it, "id")) }))
                            .then(
                                Commands.literal("fill").then(
                                    Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes { fill(it, IntegerArgumentType.getInteger(it, "id"), -1) }
                                        .then(
                                            Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                                .executes { fill(it, IntegerArgumentType.getInteger(it, "id"), IntegerArgumentType.getInteger(it, "count")) },
                                        ),
                                ),
                            )
                            .then(
                                Commands.literal("lapse")
                                    .executes { lapse(it, -1) }
                                    .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes { lapse(it, IntegerArgumentType.getInteger(it, "id")) }),
                            ),
                    ),
                ),
        )
    }

    // ---- Standing ----

    private fun standing(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val source = context.source
        val level = source.level
        if (!Suppliers.enabled()) say(source, grey("Supplying the capitals is switched off (setting: Supplier Orders): no standing counts, and the stalls pay as they always have."))
        val name = player.gameProfile.name
        val best = Suppliers.best(level, player.uuid)
        val held = Orders.get(level).held(player.uuid)
        say(source, header("$name as a supplier"))
        say(source, line().append(label("Best standing: ")).append(plain("${best.title("its capital").string}, holding ${held.size} of the ${best.orders} orders that allows")))
        val everywhere = Suppliers.get(level).everywhere(player.uuid)
        if (everywhere.isEmpty()) say(source, grey("No standing anywhere yet."))
        val today = Suppliers.today(level)
        for ((capital, s) in everywhere) {
            val place = Suppliers.place(level, capital).ifEmpty { "the capital at ${capital.x}, ${capital.z}" }
            val rank = s.rank
            val next = rank.next?.let { ", ${it.points - s.points.toInt()} to ${it.title(place).string}" } ?: ""
            say(source, line().append(label("$place: ")).append(plain("${rank.title(place).string}, %.1f points$next".format(s.points))))
            val traded = if (s.day == Standing.NEVER) "never" else "day ${s.day}"
            say(source, grey("  ${s.filled} orders filled, ${s.lapsed} let lapse; last traded $traded; greeted up to ${SupplierRank.entries[s.greeted].id}"))
            val bought = s.bought.filter { it.day > today - Suppliers.WEEK && it.count > 0 }.groupBy { it.good }
            if (bought.isNotEmpty()) say(source, grey("  Bought there in the last week: " + bought.entries.joinToString(", ") { (g, list) -> "${list.sumOf { it.count }} ${g.id}" }))
        }
        val now = Markets.hourOf(level)
        for ((book, o) in held) say(source, grey("Holds #${o.id}: ${describe(o, now)} at ${book.name}"))
        return 1
    }

    private fun set(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition()) ?: return fail(source, "Stand in a capital to set a standing there.")
        val points = DoubleArgumentType.getDouble(context, "points")
        val (before, after) = Suppliers.set(level, capital.center, player.uuid, points)
        val place = Suppliers.place(level, capital.center)
        Orders.sync(level, player)
        return say(source, plain("${player.gameProfile.name} now has %.0f points at $place: ${after.title(place).string}".format(points) + if (after != before) " (was ${before.title(place).string})" else ""))
    }

    // ---- The capital's orders ----

    /** The book and market of the capital [context]'s player stands in, its businesses learned now; null (and why, said) if there isn't one. */
    private fun book(context: CommandContext<CommandSourceStack>): Pair<Book, Markets.Entry>? {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        if (!Suppliers.enabled()) say(source, grey("Supplying the capitals is switched off (setting: Supplier Orders): its orders stand still."))
        val places = CityPlaces.of(level, capital)
        val entry = Markets.of(level, capital)
        if (places == null || entry == null) {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        val data = Orders.get(level)
        val book = data.book(capital.center)
        Orders.learn(data, book, places)
        return book to entry
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (book, entry) = book(context) ?: return 0
        val now = Markets.hourOf(source.level)
        say(source, header("The orders of ${book.name}"))
        say(source, grey("Businesses: " + book.shops.keys.joinToString(", ") { s -> s.id + if (Orders.asks(s, entry.town).isEmpty()) " (asks nothing)" else "" }))
        if (book.hour == Long.MIN_VALUE) say(source, grey("Nothing posted yet: the first orders come once a player has stood here with the orders on."))
        if (book.orders.isEmpty()) say(source, grey("No orders out."))
        for (o in book.orders.sortedBy { it.id }) say(source, line().append(label("#${o.id} ")).append(plain(describe(o, now))))
        val fed = Good.entries.filter { book.fed[it.ordinal] >= 1.0 }
        if (fed.isNotEmpty()) say(source, grey("Lately into the mill and the cellar: " + fed.joinToString(", ") { "%.0f %s".format(book.fed[it.ordinal], it.id) }))
        if (book.fills.isNotEmpty()) say(source, grey("Filled lately: " + book.fills.joinToString(", ") { "${it.count} ${it.good.id} (${it.who})" }))
        if (book.owed > 0) say(source, grey("${book.owed} to post next hour"))
        return 1
    }

    /** "The Bakery wants 64 wheat at 0.31 each: taken by Steve, 20 in, 2 days left". */
    private fun describe(o: Order, now: Long): String {
        val shop = Component.translatable(o.shop.key).string
        val each = if (o.held) o.each else Orders.each(o.basis, 0.0, standing = o.state == OrderState.OFFERED)
        val who = when (o.state) {
            OrderState.OPEN -> "open"
            OrderState.TAKEN -> "taken by ${o.name}"
            OrderState.OFFERED -> "offered to ${o.name} as a standing order"
            OrderState.STANDING -> "standing, ${o.name}'s, week ${o.week} of ${Orders.WEEKS}"
        }
        return "$shop wants ${o.amount} ${o.good.id} at %.2f each (worth %.2f to the market): $who, ${o.delivered} in, ${Orders.daysLeft(o.due, now)} days left (%d hours)".format(each, o.basis, o.due - now)
    }

    private fun post(context: CommandContext<CommandSourceStack>, id: String?): Int {
        val source = context.source
        val (book, entry) = book(context) ?: return 0
        val data = Orders.get(source.level)
        val hour = Markets.hourOf(source.level)
        if (id != null) {
            val good = Good.byId(id)?.takeIf { it.traded } ?: return fail(source, "No good called $id.")
            val o = Orders.postFor(data, book, entry, good, hour) ?: return fail(source, "No business here asks for $id, or there's an order out for it already.")
            return say(source, plain("Posted #${o.id}: ${describe(o, hour)}"))
        }
        val posted = Orders.post(data, book, entry, 3, Random(source.level.gameTime), hour)
        if (posted.isEmpty()) return fail(source, "Nothing posted: the town wants nothing more just now, or it has ${Orders.MOST} orders out already.")
        for (o in posted) say(source, plain("Posted #${o.id}: ${describe(o, hour)}"))
        return posted.size
    }

    private fun take(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val (book, entry) = book(context) ?: return 0
        val o = book.orders.firstOrNull { it.id == id } ?: return fail(source, "No order #$id here.")
        val why = Orders.take(source.level, player, book, entry, o)
        if (why != null) return fail(source, "Can't take it: " + Component.translatable("gui.guildmark.order.refused.$why").string)
        return say(source, plain("Took #${o.id}: ${describe(o, Markets.hourOf(source.level))}"))
    }

    private fun fill(context: CommandContext<CommandSourceStack>, id: Int, count: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (book, entry) = book(context) ?: return 0
        val o = book.orders.firstOrNull { it.id == id } ?: return fail(source, "No order #$id here.")
        if (o.state == OrderState.OPEN) {
            Orders.take(level, player, book, entry, o)?.let { return fail(source, "Can't take it: " + Component.translatable("gui.guildmark.order.refused.$it").string) }
        }
        if (!o.held || o.taker != player.uuid) return fail(source, "#$id is someone else's, or an offer still to be answered.")
        val d = Orders.deliver(level, player, book, entry, o, conjured = if (count < 0) o.left else count)
        val rank = if (d.after != d.before) " Standing here: ${d.before.id} to ${d.after.id}." else ""
        val done = if (d.done) (if (d.offered) " Filled, and offered again as a standing order." else " Filled.") else " ${o.left} still wanted."
        return say(source, plain("Delivered ${d.count} ${o.good.id} from nowhere for ${d.pay} Marks.$done$rank"))
    }

    private fun lapse(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val level = source.level
        val (book, _) = book(context) ?: return 0
        val chosen = if (id < 0) book.orders.filter { it.held } else listOfNotNull(book.orders.firstOrNull { it.id == id })
        if (chosen.isEmpty()) return fail(source, if (id < 0) "Nobody holds an order here." else "No order #$id here.")
        for (o in chosen) Orders.lapse(level, book, o)
        return say(source, plain("Let lapse: " + chosen.joinToString(", ") { "#${it.id}" }))
    }

    // ---- Text ----

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
