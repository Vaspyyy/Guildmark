package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
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
import kotlin.math.min

/**
 * Testing commands for the haul tickets and the hauled ledger (trader design, Part 2; operators only), under `/guildmark trade`.
 *
 * `hauls [list [town]]` lists the tickets out at a town (the capital you stand in, else every town's): number, kind, good, the shop it is for, what it
 * pays, how much is in, booked and open, the hours it has left, and who holds a booking. `hauls post [good] [units] [kind]` posts one at the capital you
 * stand in, at the usual pay for its kind; it stays until its day whatever the stock does. `hauls book <id> [units]` books it for you, the deposit held,
 * as the screen's Take does. `hauls fill <id> [units]` fills your booking as if you had brought the goods: the units come off your ledger (nothing
 * is taken from your pack), and you are paid and given the deposit's share back as for a delivery. `hauls lapse [id]` lapses your booking on that
 * ticket now (on every ticket, with no number), as if a day had gone without a quarter of its goods bought far enough away: the deposit lost and
 * the keeper's word waiting. `hauls lapse <id> ticket` ends the ticket as if its own day were up. `hauls rate [n | reset]` says how many tickets
 * a capital posts a day just now, or sets it by hand (`reset` for the setting's again).
 *
 * `ledger` lists your hauled units (the good, where it was bought and the days it has left), `ledger add <good> <units> [days]` writes some in, bought
 * far from where you stand and counting for [days] more days (ten if not said), to try the dealer's lot and the tickets without the walk, and
 * `ledger clear` empties it. A town is named as its capital is (where its ground is not loaded, by where it stands, "x_z"). Nothing here makes a file
 * by looking, and every command works with the tickets switched off (the tickets then stand still).
 */
object HaulCommand {
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
                            Commands.literal("hauls")
                                .executes { list(it, null) }
                                .then(
                                    Commands.literal("list")
                                        .executes { list(it, null) }
                                        .then(town().executes { list(it, StringArgumentType.getString(it, "town")) }),
                                )
                                .then(
                                    Commands.literal("post")
                                        .executes { post(it, null, null, null) }
                                        .then(
                                            good()
                                                .executes { post(it, StringArgumentType.getString(it, "good"), null, null) }
                                                .then(
                                                    units()
                                                        .executes { post(it, StringArgumentType.getString(it, "good"), IntegerArgumentType.getInteger(it, "units"), null) }
                                                        .then(
                                                            Commands.argument("kind", StringArgumentType.word())
                                                                .suggests { _, b -> SharedSuggestionProvider.suggest(HaulKind.entries.map { it.id }, b) }
                                                                .executes {
                                                                    post(
                                                                        it, StringArgumentType.getString(it, "good"),
                                                                        IntegerArgumentType.getInteger(it, "units"), StringArgumentType.getString(it, "kind"),
                                                                    )
                                                                },
                                                        ),
                                                ),
                                        ),
                                )
                                .then(
                                    Commands.literal("book").then(
                                        id().executes { book(it, IntegerArgumentType.getInteger(it, "id"), 0) }
                                            .then(units().executes { book(it, IntegerArgumentType.getInteger(it, "id"), IntegerArgumentType.getInteger(it, "units")) }),
                                    ),
                                )
                                .then(
                                    Commands.literal("fill").then(
                                        id().executes { fill(it, IntegerArgumentType.getInteger(it, "id"), 0) }
                                            .then(units().executes { fill(it, IntegerArgumentType.getInteger(it, "id"), IntegerArgumentType.getInteger(it, "units")) }),
                                    ),
                                )
                                .then(
                                    Commands.literal("lapse")
                                        .executes { lapse(it, null, false) }
                                        .then(
                                            id().executes { lapse(it, IntegerArgumentType.getInteger(it, "id"), false) }
                                                .then(Commands.literal("ticket").executes { lapse(it, IntegerArgumentType.getInteger(it, "id"), true) }),
                                        ),
                                )
                                .then(
                                    Commands.literal("rate")
                                        .executes { rate(it, null) }
                                        .then(Commands.literal("reset").executes { rate(it, RESET) })
                                        .then(Commands.argument("n", DoubleArgumentType.doubleArg(0.0, MOST_RATE)).executes { rate(it, DoubleArgumentType.getDouble(it, "n")) }),
                                ),
                        )
                        .then(
                            Commands.literal("ledger")
                                .executes { ledger(it) }
                                .then(
                                    Commands.literal("add").then(
                                        good().then(
                                            units()
                                                .executes { add(it, StringArgumentType.getString(it, "good"), IntegerArgumentType.getInteger(it, "units"), null) }
                                                .then(
                                                    Commands.argument("days", IntegerArgumentType.integer(1, Ledger.HELD_DAYS.toInt()))
                                                        .executes {
                                                            add(
                                                                it, StringArgumentType.getString(it, "good"),
                                                                IntegerArgumentType.getInteger(it, "units"), IntegerArgumentType.getInteger(it, "days"),
                                                            )
                                                        },
                                                ),
                                        ),
                                    ),
                                )
                                .then(Commands.literal("clear").executes { clear(it) }),
                        ),
                ),
        )
    }

    private fun town(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("town", StringArgumentType.word()).suggests { c, b -> SharedSuggestionProvider.suggest(towns(c.source), b) }

    private fun good(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("good", StringArgumentType.word()).suggests { _, b -> SharedSuggestionProvider.suggest(Good.TRADED.map { it.id }, b) }

    private fun units(): RequiredArgumentBuilder<CommandSourceStack, Int> = Commands.argument("units", IntegerArgumentType.integer(1, MOST_UNITS))

    private fun id(): RequiredArgumentBuilder<CommandSourceStack, Int> = Commands.argument("id", IntegerArgumentType.integer(1))

    // ---- The town asked about ----

    /** What the commands call the capital whose heart is [capital]: its name as its orders keep it or its ground has it, or where it stands if neither is known now. */
    private fun nameAt(level: ServerLevel, capital: BlockPos): String = Suppliers.place(level, capital).ifEmpty { where(capital) }

    private fun where(capital: BlockPos): String = "${capital.x}_${capital.z}"

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
            .filter { nameAt(level, it.capital).equals(name, ignoreCase = true) || where(it.capital) == name }
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
            fail(source, "You're not in a capital: stand in one.")
            return null
        }
        return Markets.of(level, capital) ?: run {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            null
        }
    }

    // ---- The tickets ----

    private fun list(context: CommandContext<CommandSourceStack>, town: String?): Int {
        val source = context.source
        val level = source.level
        if (!TradeConfig.hauls()) say(source, grey("The haul tickets are switched off (setting: Haul Tickets, or Trader's Life): none is posted, booked or paid, and the days stand still."))
        // The town asked for, else the one the sender stands in, else all of them
        val only: BlockPos? = if (town != null) {
            (find(source, town) ?: return fail(source, "No open market is called $town: " + towns(source).joinToString(", "))).capital
        } else {
            source.player?.let { Capitals.covering(level, it.blockPosition()) }?.center
        }
        val data = HaulData.peek(level)
        val books: List<HaulBook> = if (data == null) listOf() else if (only != null) listOfNotNull(data[only.asLong()]) else data.all().sortedBy { it.capital }
        val total = books.sumOf { it.tickets.size }
        if (data == null || total == 0) {
            say(source, grey(if (only != null) "No ticket is out at ${nameAt(level, only)}." else "No ticket is out anywhere."))
            return 0
        }
        val rate = num(HaulRun.rate(level, data))
        for (book in books) {
            if (book.tickets.isEmpty()) continue
            say(source, header("${nameAt(level, BlockPos.of(book.capital))}: ${book.tickets.size} out, $rate a day, hour ${Hauls.clock(level, book)}"))
            for (t in book.tickets.sortedBy { it.id }) say(source, describe(level, book, t))
        }
        return total
    }

    private fun describe(level: ServerLevel, book: HaulBook, t: Ticket): MutableComponent {
        val hours = (t.due - Hauls.clock(level, book)).coerceAtLeast(0)
        val out = line().append(label("#${t.id} "))
            .append(plain("${t.kind.id}: ${t.units} "))
            .append(Component.translatable(t.good.key + ".many").withStyle(ChatFormatting.WHITE))
            .append(plain(" for "))
            .append(Orders.shopName(t.shop).copy().withStyle(ChatFormatting.WHITE))
            .append(grey(", ${t.pay} Marks (${String.format(Locale.ROOT, "%.3f", t.each)} each), ${t.done} in, ${t.booked} booked, ${t.open} open, $hours h left${if (t.fixed) ", fixed" else ""}"))
        if (t.bookings.isNotEmpty()) {
            out.append(grey("; held by " + t.bookings.joinToString(", ") { "${it.name.ifEmpty { it.taker.toString().take(8) }}${if (it.house) " (House)" else ""} ${it.left}/${it.units}" }))
        }
        return out
    }

    private fun post(context: CommandContext<CommandSourceStack>, goodId: String?, units: Int?, kindId: String?): Int {
        val source = context.source
        val level = source.level
        if (!TradeConfig.hauls()) return fail(source, "The haul tickets are switched off (setting: Haul Tickets, or Trader's Life): none can be posted.")
        val entry = standing(source) ?: return 0
        val good = if (goodId != null) {
            Good.byId(goodId)?.takeIf { it.traded } ?: return fail(source, "The market doesn't deal in $goodId.")
        } else {
            // The good the town is shortest of, against what it keeps
            Good.TRADED.maxByOrNull { HaulRules.shortness(entry.market.stock(it), entry.town.target(it)) } ?: return 0
        }
        val kind = when {
            kindId != null -> HaulKind.byId(kindId) ?: return fail(source, "No such kind of ticket: $kindId (one of ${HaulKind.entries.joinToString(", ") { it.id }}).")
            units != null -> HaulRules.kindFor(units)
            else -> HaulKind.PARCEL
        }
        val n = (
            units ?: when (kind) {
                HaulKind.PARCEL -> PARCEL_UNITS
                HaulKind.CONSIGNMENT -> CONSIGNMENT_UNITS
                HaulKind.RELIEF -> HaulRules.MOST
                HaulKind.FEAST -> FEAST_UNITS
            }
            ).coerceIn(1, HaulRules.RELIEF_MOST)
        val haul = HaulRun.postBy(level, entry.capital, good, n, kind, null, null)
            ?: return fail(source, "The ticket could not be posted (its market is not open, or its day is over).")
        say(
            source,
            line().append(label("Posted #${haul.id}: ")).append(plain("${haul.kind.id}, ${haul.units} "))
                .append(Component.translatable(haul.good.key + ".many").withStyle(ChatFormatting.WHITE))
                .append(grey(" for ")).append(Orders.shopName(haul.shop).copy().withStyle(ChatFormatting.WHITE))
                .append(grey(" at ${nameAt(level, entry.capital)}, ${haul.pay} Marks in all, ${haul.due - haul.posted} hours to run.")),
        )
        return 1
    }

    private fun book(context: CommandContext<CommandSourceStack>, id: Int, units: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.player ?: return fail(source, "Only a player can book a ticket.")
        val why = Hauls.book(level, id, Taker.Player(player.uuid), units)
        if (why != null) return fail(source, Component.translatable("gui.guildmark.trade.haul.refused.$why"))
        val haul = Hauls.find(level, id)
        val b = Hauls.bookedOn(level, player.uuid, id)
        say(
            source,
            line().append(label("Booked #$id: ")).append(plain("${b?.units ?: 0} "))
                .append(haul?.let { Component.translatable(it.good.key + ".many") } ?: Component.literal("units")).append(grey(", deposit ${b?.deposit ?: 0} Marks held.")),
        )
        return 1
    }

    private fun fill(context: CommandContext<CommandSourceStack>, id: Int, units: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.player ?: return fail(source, "Only a player can fill a ticket.")
        if (!TradeConfig.hauls()) return fail(source, "The haul tickets are switched off (setting: Haul Tickets, or Trader's Life).")
        val h = Hauls.find(level, id) ?: return fail(source, "No ticket is numbered $id.")
        val b = Hauls.bookedOn(level, player.uuid, id) ?: return fail(source, "You hold no booking on ticket $id: book it first (trade hauls book $id).")
        val have = Ledger.count(level, player.uuid, h.good, h.capital)
        if (have <= 0) {
            return fail(source, "Your ledger holds no ${h.good.id} that counts at ${nameAt(level, h.capital)}: trade ledger add ${h.good.id} <units> writes some in.")
        }
        val n = min(if (units <= 0) b.left else min(units, b.left), have)
        val parcels = Ledger.take(level, player.uuid, h.good, n, h.capital)
        val taken = parcels.sumOf { it.units }
        val d = Hauls.fill(level, id, taken, Taker.Player(player.uuid), Hauls.mostFrom(parcels))
        if (d == null) {
            // Nothing could be filled: what came off the ledger goes back on it
            Ledger.add(level, player.uuid, parcels)
            return fail(source, "The ticket could not be filled.")
        }
        say(
            source,
            line().append(label("Filled #$id: ")).append(plain("${d.units} ")).append(Component.translatable(h.good.key + ".many").withStyle(ChatFormatting.WHITE))
                .append(grey(" taken off your ledger; paid ${d.pay} Marks and ${d.refund} of your deposit back, ${d.left} still to come in.")),
        )
        return 1
    }

    private fun lapse(context: CommandContext<CommandSourceStack>, id: Int?, ticket: Boolean): Int {
        val source = context.source
        val level = source.level
        if (!TradeConfig.hauls()) return fail(source, "The haul tickets are switched off (setting: Haul Tickets, or Trader's Life).")
        val data = HaulData.peek(level) ?: return fail(source, "No ticket is out anywhere.")
        if (ticket && id != null) {
            val (book, t) = data.find(id) ?: return fail(source, "No ticket is numbered $id.")
            HaulRun.end(level, data, book, t, Hauls.clock(level, book))
            say(source, grey("Ticket #$id ended as if its day were up: whoever still owed units lost the deposit held for them."))
            return 1
        }
        val player = source.player ?: return fail(source, "Only a player has bookings to lapse.")
        var n = 0
        for (book in data.all().toList()) {
            for (t in book.tickets.toList()) {
                if (id != null && t.id != id) continue
                val b = Hauls.bookedOn(level, player.uuid, t.id) ?: continue
                HaulRun.lapse(level, data, book, t, b, Hauls.clock(level, book))
                n++
            }
        }
        if (n == 0) return fail(source, if (id != null) "You hold no booking on ticket $id." else "You hold no booking.")
        say(source, grey("$n of your bookings lapsed: the units are back on the board, the deposits are lost, and the keepers have a word waiting."))
        return n
    }

    private fun rate(context: CommandContext<CommandSourceStack>, set: Double?): Int {
        val source = context.source
        val level = source.level
        if (set == null) {
            val data = HaulData.peek(level)
            val now = if (data != null) HaulRun.rate(level, data) else TradeConfig.haulRate()
            val carriers = if (data != null) HaulRun.carriers(level, data) else 0
            val how = if (data != null && data.rate >= 0.0) "set by hand" else "the setting: ${num(TradeConfig.haulRate())}, rising to ${num(TradeConfig.haulRateMax())}"
            say(source, plain("A capital posts ${num(now)} tickets a day just now ($carriers carriers at work; $how)."))
            return 1
        }
        if (set < 0.0) {
            // Back to the setting's: nothing to write if nothing was ever set
            HaulData.peek(level)?.let {
                it.rate = RESET
                it.changed()
            }
            say(source, plain("The rate is the setting's again (${num(TradeConfig.haulRate())} a capital a day, rising to ${num(TradeConfig.haulRateMax())})."))
            return 1
        }
        val data = HaulData.get(level)
        data.rate = set
        data.changed()
        say(source, plain("A capital now posts ${num(set)} tickets a day, rising with its carriers to no more than ${num(maxOf(TradeConfig.haulRateMax(), set))}."))
        return 1
    }

    // ---- The hauled ledger ----

    private fun ledger(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.player ?: return fail(source, "Only a player has a ledger.")
        val lines = Ledger.lines(level, player.uuid)
        if (lines.isEmpty()) {
            say(source, grey("Your ledger holds nothing: goods bought ${TradeConfig.haulMin()} blocks or more from where they are sold count as hauled for ${Suppliers.KEPT} days."))
            return 0
        }
        say(source, header("Your hauled ledger: ${lines.sumOf { it.parcel.units }} units"))
        for (l in lines) {
            say(
                source,
                line().append(plain("${l.parcel.units} ")).append(Component.translatable(l.parcel.good.key + ".many").withStyle(ChatFormatting.WHITE))
                    .append(grey(" bought at ${nameAt(level, l.parcel.origin)}, ${l.daysLeft} days left${if (l.kept) " (kept in your books)" else ""}")),
            )
        }
        return lines.size
    }

    private fun add(context: CommandContext<CommandSourceStack>, goodId: String, units: Int, days: Int?): Int {
        val source = context.source
        val level = source.level
        val player = source.player ?: return fail(source, "Only a player has a ledger.")
        val good = Good.byId(goodId)?.takeIf { it.traded } ?: return fail(source, "The market doesn't deal in $goodId.")
        // Bought far from where the sender stands: at the farthest open market if that is far enough, else at a place that is
        val here = player.blockPosition()
        val far = Markets.get(level).all().map { it.capital }.maxByOrNull { Hauls.distance(it, here) }
        val origin = if (far != null && Hauls.distance(far, here) >= TradeConfig.haulMin()) far else BlockPos(here.x + TradeConfig.haulMin() + FAR_EXTRA, here.y, here.z)
        val keeps = days ?: Suppliers.KEPT.toInt()
        val boughtAt = Suppliers.today(level) + keeps - Ledger.HELD_DAYS
        Ledger.add(level, player.uuid, listOf(Parcel(good, units, origin, boughtAt)))
        say(
            source,
            line().append(label("Written in: ")).append(plain("$units ")).append(Component.translatable(good.key + ".many").withStyle(ChatFormatting.WHITE))
                .append(grey(" bought at ${nameAt(level, origin)}, $keeps days left: they count at any market ${TradeConfig.haulMin()} blocks or more from there.")),
        )
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player has a ledger.")
        val n = Ledger.clear(source.level, player.uuid)
        say(source, grey("Your ledger is empty: $n units went off it."))
        return 1
    }

    // ---- Words ----

    private fun num(x: Double): String = if (x == Math.rint(x)) x.toLong().toString() else String.format(Locale.ROOT, "%.2f", x)

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int = fail(source, Component.literal(message))

    private fun fail(source: CommandSourceStack, message: Component): Int {
        source.sendFailure(message)
        return 0
    }

    /** The units a ticket posted by hand asks for when none are said: a parcel's, a consignment's, a feast's (a relief ticket asks the most the rules post). */
    private const val PARCEL_UNITS = 32
    private const val CONSIGNMENT_UNITS = 96
    private const val FEAST_UNITS = 64

    /** The most units a command writes in or asks for, and the most tickets a day it sets. */
    private const val MOST_UNITS = 4096
    private const val MOST_RATE = 24.0

    /** What [HaulData.rate] holds for "the setting rules". */
    private const val RESET = -1.0

    /** Blocks past the haul distance a made-up place of purchase lies from where the sender stands, when no open market is far enough. */
    private const val FAR_EXTRA = 100
}
