package io.github.vaspyyy.guildmark.economy

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.trade.Hauls
import io.github.vaspyyy.guildmark.trade.Ledger
import io.github.vaspyyy.guildmark.village.VillageNames
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for a capital's market, run standing in the capital. `/guildmark market` lists every good: what
 * the market holds against what the town likes to keep, the lots to buy and sell, which way the price is going and
 * why it's off, if it is; `good <good>` is one good in full (every store, today's and yesterday's tallies, the last
 * closes, and the dealer's lot for goods of yours that came a long way); `news` the market's news; `event <kind> [good] [days]` starts trouble or luck now; `run <hours>` works
 * the market ahead; `buy <good> [lots]` and `sell <good> [lots]` trade with what you carry; `shop <shop>` is what a
 * shop would show, and `screen <shop>` opens its market screen with nobody keeping it; `open` starts the market afresh.
 */
object MarketCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("market")
                        .executes { list(it) }
                        .then(
                            Commands.literal("good").then(
                                Commands.argument("good", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(Good.entries.map { it.id }, b) }
                                    .executes { good(it, StringArgumentType.getString(it, "good")) },
                            ),
                        )
                        .then(Commands.literal("news").executes { news(it) })
                        .then(
                            Commands.literal("event").then(
                                Commands.argument("kind", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(EventKind.entries.map { it.id }, b) }
                                    .executes { event(it, StringArgumentType.getString(it, "kind"), null, 0) }
                                    .then(
                                        Commands.argument("good", StringArgumentType.word())
                                            .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("any") + Good.entries.map { it.id }, b) }
                                            .executes { event(it, StringArgumentType.getString(it, "kind"), StringArgumentType.getString(it, "good"), 0) }
                                            .then(
                                                Commands.argument("days", IntegerArgumentType.integer(1, 30))
                                                    .executes {
                                                        event(it, StringArgumentType.getString(it, "kind"), StringArgumentType.getString(it, "good"), IntegerArgumentType.getInteger(it, "days"))
                                                    },
                                            ),
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("run").then(
                                Commands.argument("hours", IntegerArgumentType.integer(1, 24 * 14))
                                    .executes { run(it, IntegerArgumentType.getInteger(it, "hours")) },
                            ),
                        )
                        .then(trade("buy", true))
                        .then(trade("sell", false))
                        .then(
                            Commands.literal("shop").then(
                                Commands.argument("shop", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(Shop.entries.map { it.id }, b) }
                                    .executes { shop(it, StringArgumentType.getString(it, "shop")) },
                            ),
                        )
                        .then(
                            Commands.literal("screen").then(
                                Commands.argument("shop", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(Shop.entries.map { it.id }, b) }
                                    .executes { screen(it, StringArgumentType.getString(it, "shop")) },
                            ),
                        )
                        .then(Commands.literal("open").executes { open(it) }),
                ),
        )
    }

    private fun trade(name: String, buying: Boolean) = Commands.literal(name).then(
        Commands.argument("good", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(Good.TRADED.map { it.id }, b) }
            .executes { deal(it, StringArgumentType.getString(it, "good"), 1, buying) }
            .then(
                Commands.argument("lots", IntegerArgumentType.integer(1, 64))
                    .executes { deal(it, StringArgumentType.getString(it, "good"), IntegerArgumentType.getInteger(it, "lots"), buying) },
            ),
    )

    // ---- The market you stand in ----

    /** The market of the capital or village [context]'s player stands in, opened now if need be; null (and why, said) if there isn't one. */
    private fun entry(context: CommandContext<CommandSourceStack>): Markets.Entry? {
        val source = context.source
        val level = source.level
        val here = source.playerOrException.blockPosition()
        val capital = Capitals.covering(level, here)
        if (capital == null) return village(source, here)
        if (!Markets.enabled()) say(source, grey("The markets are switched off (setting: Capital Markets): this one stands still."))
        val entry = Markets.of(level, capital)
        if (entry == null) fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
        return entry
    }

    /** The market of the village [here] is in, opened now if its census is done and it has none; null (and why, said) if there isn't one. */
    private fun village(source: CommandSourceStack, here: BlockPos): Markets.Entry? {
        val level = source.level
        val rec = Villages.at(level, here)
        if (rec == null) {
            fail(source, "You're not in a capital or a village.")
            return null
        }
        if (!VillageMarkets.enabled()) {
            fail(source, "Villages have no markets just now (settings: Capital Markets, Living Villages and Village Markets are all needed).")
            return null
        }
        VillageMarkets.find(level, rec.key)?.let { return it }
        val places = Villages.places(level, rec.key)
        if (places == null) {
            fail(source, "${rec.name} has not been counted yet, so it has no market: try /guildmark village census now.")
            return null
        }
        return VillageMarkets.open(level, rec, places)
    }

    /** The name the market [entry] is of, for its heading: the capital's or the village's. */
    private fun nameOf(source: CommandSourceStack, entry: Markets.Entry): String {
        val level = source.level
        Capitals.covering(level, entry.capital)?.let { return VillageNames.of(it.boardAnchor) }
        return Villages.byKey(level, entry.market.capital)?.name ?: "the town"
    }

    /** A day on which more than this many meals went short is a hungry day (as the villages design's model counts it, section 18). */
    private const val HUNGRY_DAY = 0.5

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val entry = entry(context) ?: return 0
        val m = entry.market
        val town = entry.town
        val c = Conditions(m.hour)
        val village = VillageMarkets.owns(source.level, entry)
        say(source, header("The market of ${nameOf(source, entry)}: day ${c.day}, ${clock(c.clock)}" + if (c.restDay) " (rest day)" else ""))
        say(source, grey("People: $town"))
        town.planned?.let { built -> if (built.people != town.people) say(source, grey("Built for ${built.people} (${built.people - town.people} gone)")) }
        // A village's hungry days are counted beside its town (the villages design, section 18.7)
        if (village && m.hungry.isNotEmpty()) {
            val hungry = m.hungry.count { it > HUNGRY_DAY }
            say(source, line().append(label("Hungry days: ")).append(plain("$hungry of the last ${m.hungry.size}")).append(grey(" (a day on which more than half a meal went short)")))
        }
        if (m.events.isNotEmpty()) say(source, line().append(label("Under way: ")).append(plain(m.events.joinToString(", ") { e ->
            e.kind.id + (e.good?.let { " (${it.id})" } ?: "") + (if (e.goods.isNotEmpty()) " (${e.goods.joinToString("+") { it.id }})" else "") +
                (if (e.place.isNotEmpty()) " at the ${e.place}" else "") + " x%.2f, %d h left".format(e.factor, e.until - m.hour)
        })))
        m.hungry.lastOrNull()?.let { if (it > 0.5) say(source, line().append(label("Went hungry yesterday: ")).append(plain("%.0f meals".format(it)))) }
        say(source, grey("good: stock / keeps   price (usual)   you buy   you sell (room)   trend   why"))
        for (g in Good.TRADED) say(source, row(entry, g))
        say(source, grey("The town's own: flour %.0f at the mill, %.0f at the bakehouse; tools %.1f; ale %.0f".format(
            m[Site.MILL, Good.FLOUR], m[Site.BAKEHOUSE, Good.FLOUR], m.stock(Good.TOOLS), m[Site.TAVERN, Good.ALE],
        )))
        return 1
    }

    private fun row(entry: Markets.Entry, g: Good): Component {
        val q = Trading.quote(entry, g)
        val target = entry.town.target(g)
        val trend = when (q.trend) {
            1 -> Component.literal(" ▲").withStyle(ChatFormatting.RED)
            -1 -> Component.literal(" ▼").withStyle(ChatFormatting.GREEN)
            else -> Component.literal(" ·").withStyle(ChatFormatting.DARK_GRAY)
        }
        val priceColour = when {
            q.price >= q.usual * 1.2 -> ChatFormatting.RED
            q.price <= q.usual / 1.2 -> ChatFormatting.GREEN
            else -> ChatFormatting.WHITE
        }
        val out = line().append(Component.translatable(g.key).withStyle(ChatFormatting.YELLOW))
            .append(plain(": ${q.stock} / %.0f   ".format(target)))
            .append(Component.literal("%.3f".format(q.price)).withStyle(priceColour))
            .append(grey(" (%.3f)   ".format(q.usual)))
            .append(plain(q.buy?.let { "$it" } ?: "none"))
            .append(grey("   "))
            .append(plain(q.sell?.let { "$it" } ?: "full"))
            .append(grey(" (${q.wants})"))
            .append(trend)
        q.why?.let { why -> out.append(grey("  ")).append(reason(q).withStyle(if (why.dear) ChatFormatting.GOLD else ChatFormatting.AQUA)) }
        return out
    }

    /** What the stall says about [q]'s price. */
    fun reason(q: Quote): MutableComponent {
        if (q.why == null) return Component.empty()
        return Component.translatable(Trading.reasonKey(q), Component.translatable(q.good.key))
    }

    private fun good(context: CommandContext<CommandSourceStack>, id: String): Int {
        val source = context.source
        val g = Good.byId(id) ?: return fail(source, "No good called $id.")
        val entry = entry(context) ?: return 0
        val m = entry.market
        val town = entry.town
        say(source, header(Component.translatable(g.key).string))
        say(source, line().append(label("Uses ")).append(plain("%.1f a day".format(town.use(g))))
            .append(label("  makes ")).append(plain("%.1f".format(town.made(g))))
            .append(label("  keeps ")).append(plain("%.0f".format(town.target(g))))
            .append(label("  goes off ")).append(plain("%.1f".format(town.spoils(g))))
            .append(label("  brought in as a rule ")).append(plain("%.0f%%".format(town.shortfall(g) * 100))))
        say(source, line().append(label("Held: ")).append(plain(Site.entries.filter { m[it, g] > 0.0 }.joinToString(", ") { "${it.id} %.1f".format(m[it, g]) }.ifEmpty { "nothing" })))
        if (g.traded) {
            val q = Trading.quote(entry, g)
            say(source, line().append(label("Price ")).append(plain("%.3f".format(q.price)))
                .append(label("  usual ")).append(plain("%.3f".format(q.usual)))
                .append(label("  freight ")).append(plain("x%.2f".format(m.freight(g))))
                .append(label("  outlook ")).append(plain("x%.2f".format(m.outlook(g))))
                .append(label("  held lately ")).append(plain("%.1f".format(m.held[g.ordinal]))))
            say(source, line().append(label("You buy ")).append(plain(q.buy?.toString() ?: "none"))
                .append(label("  you sell ")).append(plain(q.sell?.toString() ?: "it's full")).append(grey(" (takes ${q.wants} more)")))
            if (q.why != null) say(source, line().append(label("Why: ")).append(reason(q)))
            dealer(source, entry, g)?.let { say(source, it) }
        }
        fun tallies(of: (Tally) -> Double) = Tally.entries.filter { of(it) > 0.0 }.joinToString(", ") { "${it.id} %.1f".format(of(it)) }.ifEmpty { "nothing" }
        say(source, line().append(label("Today: ")).append(plain(tallies { m.today(it, g) })))
        say(source, line().append(label("Yesterday: ")).append(plain(tallies { m.yesterday(it, g) })))
        val closes = m.closes[g.ordinal]
        if (closes.isNotEmpty()) say(source, line().append(label("Closes (x base): ")).append(plain(closes.joinToString("  ") { "%.2f".format(it) })))
        return 1
    }

    /**
     * What the market pays the sender for goods of theirs that came a long way (the dealer's lot, see [Trading.dealerLot]), beside the ordinary lot; null
     * if their ledger holds none of [g] that counts at this market, so the output is as it was for anyone who has hauled nothing.
     */
    private fun dealer(source: CommandSourceStack, entry: Markets.Entry, g: Good): MutableComponent? {
        val player = source.player ?: return null
        val level = source.level
        val ledger = Ledger.count(level, player.uuid, g, entry.capital)
        if (ledger <= 0) return null
        val carried = Hauls.hauledHere(level, player, entry, g)
        val q = Trading.quote(entry, g, Suppliers.share(level, entry.capital, player.uuid), carried)
        val out = line().append(label("Hauled: ")).append(plain("$ledger on your ledger, $carried of them in your pack"))
        val lot = q.hauledSell
        if (lot == null) {
            return out.append(grey("; no dealer's lot (it would be bigger than you carry, or the market takes none, or it is no better than the ordinary lot)"))
        }
        return out.append(grey("; dealer's lot ")).append(plain(lot.toString())).append(grey(" (the ordinary lot: ${q.sell?.toString() ?: "none"})"))
    }

    private fun news(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val entry = entry(context) ?: return 0
        val m = entry.market
        if (m.news.isEmpty()) return say(source, grey("No news yet."))
        for (n in m.news.reversed()) {
            val c = Conditions(n.hour)
            val text = if (n.good != null) Component.translatable(n.key, Component.translatable(n.good.key)) else Component.translatable(n.key)
            say(source, line().append(grey("day ${c.day} ${clock(c.clock)}  ")).append(text.withStyle(ChatFormatting.WHITE)))
        }
        return 1
    }

    private fun event(context: CommandContext<CommandSourceStack>, id: String, goodId: String?, days: Int): Int {
        val source = context.source
        val kind = EventKind.byId(id) ?: return fail(source, "No event called $id: " + EventKind.entries.joinToString(", ") { it.id })
        val entry = entry(context) ?: return 0
        val m = entry.market
        // Rolled as the day would roll it, then given the good and length asked for
        val rolled = generateSequence { Economy.roll(entry.town, Markets.conditions(source.level, m.hour, entry.capital), kotlin.random.Random.Default) }
            .take(400).firstOrNull { it.kind == kind }
        val good = if (goodId == null || goodId == "any") rolled?.good ?: Good.WHEAT.takeIf { kind == EventKind.BLIGHT || kind == EventKind.BUMPER }
            else Good.byId(goodId) ?: return fail(source, "No good called $goodId.")
        val factor = rolled?.factor ?: FACTORS[kind] ?: 0.5
        val length = if (days > 0) days * 24L else (rolled?.let { it.until - it.since } ?: 72L)
        m.events.removeIf { it.kind == kind }
        if (kind == EventKind.WAYLAID) {
            // A wagon's load lost on the road: the good asked for, or the dearest of what a wagon would bring now
            val load = Economy.bring(m, entry.town, false)
            val lost = if (good != null) setOf(good) else {
                Good.entries.filter { it.traded && load[it.ordinal] >= 1.0 }.sortedByDescending { it.base * load[it.ordinal] }.take(WAYLAID_GOODS).toSet()
                    .ifEmpty { setOf(Good.CANDLE, Good.IRON) }
            }
            val place = Caravans.PLACES.random()
            m.events.add(Event(kind, null, factor, m.hour, m.hour + length, lost, place))
            m.say("news.guildmark.market.event.${kind.id}", lost.first())
            VillageMarkets.changed(source.level, entry)
            return say(source, plain("Started waylaid (at the $place) on ${lost.joinToString(", ") { it.id }} for ${length / 24} days (x%.2f).".format(factor)))
        }
        m.events.add(Event(kind, good, factor, m.hour, m.hour + length))
        m.say("news.guildmark.market.event.${kind.id}", good)
        VillageMarkets.changed(source.level, entry)
        return say(source, plain("Started ${kind.id}" + (good?.let { " on ${it.id}" } ?: "") + " for ${length / 24} days (x%.2f).".format(factor)))
    }

    /** The most goods a waylaid wagon started by hand loses, when no good is named. */
    private const val WAYLAID_GOODS = 5

    /** What each kind does when the town can't roll it (no farmers for a blight, say). */
    private val FACTORS = mapOf(
        EventKind.BLIGHT to 0.25, EventKind.BUMPER to 1.6, EventKind.MURRAIN to 0.4, EventKind.FOWL_PEST to 0.3,
        EventKind.MILL_BROKEN to 0.0, EventKind.OVEN_CRACKED to 0.5, EventKind.GOOD_CATCH to 1.8, EventKind.POOR_CATCH to 0.3,
        EventKind.FEVER to 0.75, EventKind.WAYLAID to RoadPeril.Stage.TROUBLED.factor,
    )

    private fun run(context: CommandContext<CommandSourceStack>, hours: Int): Int {
        val source = context.source
        val entry = entry(context) ?: return 0
        val m = entry.market
        val from = m.hour
        val village = VillageMarkets.get(source.level)[entry.market.capital]?.takeIf { it.entry === entry }
        for (h in 1..hours) if (village != null) VillageMarkets.step(source.level, village, from + h) else Markets.step(source.level, entry, from + h)
        // Then back to the clock's hour, as if those hours had been lived already
        Markets.rewind(m, hours.toLong())
        VillageMarkets.changed(source.level, entry)
        return say(source, plain("Worked out $hours hours of the market at once; it carries on from the clock's hour."))
    }

    private fun deal(context: CommandContext<CommandSourceStack>, id: String, lots: Int, buying: Boolean): Int {
        val source = context.source
        val g = Good.byId(id)?.takeIf { it.traded } ?: return fail(source, "The market doesn't deal in $id.")
        val entry = entry(context) ?: return 0
        val player = source.playerOrException
        var units = 0
        var marks = 0
        var done = 0
        var dealers = 0
        var last: Deal? = null
        repeat(lots) {
            val deal = if (buying) Trading.buy(player, entry, g) else Trading.sell(player, entry, g)
            last = deal
            if (deal.outcome != Deal.Outcome.DONE) return@repeat
            units += deal.lot!!.count
            marks += deal.lot.marks
            done++
            if (deal.hauled) dealers++
        }
        val name = Component.translatable(g.key)
        if (done > 0) {
            say(source, line().append(plain(if (buying) "Bought $units " else "Sold $units ")).append(name).append(plain(" for $marks Marks in $done lots.")))
            if (dealers > 0) say(source, grey("$dealers of the lots were dealer's lots: goods that came a long way, off your ledger."))
        }
        val stop = last?.takeIf { it.outcome != Deal.Outcome.DONE }
        if (stop != null) say(source, grey(when (stop.outcome) {
            Deal.Outcome.NONE -> if (buying) "The market has none left to sell." else "The market won't take any more."
            Deal.Outcome.POOR -> "Not enough Marks for the next lot (${stop.lot})."
            Deal.Outcome.LACKING -> "Not enough of it on you for the next lot (${stop.lot})."
            Deal.Outcome.OWN_WAGON -> "All you carry of it is salvage off this capital's own waylaid wagon, which it won't buy back: hold it out to a keeper here to hand it in."
            Deal.Outcome.DONE -> ""
        }))
        say(source, row(entry, g))
        return done
    }

    private fun shop(context: CommandContext<CommandSourceStack>, id: String): Int {
        val source = context.source
        val shop = Shop.byId(id) ?: return fail(source, "No shop called $id: " + Shop.entries.joinToString(", ") { it.id })
        val entry = entry(context) ?: return 0
        say(source, header(Component.translatable(shop.key).string))
        for (g in shop.goods) say(source, row(entry, g))
        return 1
    }

    /** Opens [id]'s market screen with nobody keeping it: deals as at the shop itself, but nobody acts them out. */
    private fun screen(context: CommandContext<CommandSourceStack>, id: String): Int {
        val source = context.source
        val shop = Shop.byId(id) ?: return fail(source, "No shop called $id: " + Shop.entries.joinToString(", ") { it.id })
        if (!Markets.enabled()) return fail(source, "The markets are switched off (setting: Capital Markets).")
        val player = source.playerOrException
        val entry = entry(context) ?: return 0
        val capital = Capitals.covering(source.level, player.blockPosition())
        val town = capital?.let { MarketTown.of(it) } ?: MarketTown(BlockPos.of(entry.market.capital), nameOf(source, entry))
        Stalls.open(player, shop, entry, town, null)
        return say(source, grey("Opened the ${shop.id} screen. Nobody keeps it, so nobody acts the deals out; walk out of the ${if (capital != null) "capital" else "village"} and it closes."))
    }

    private fun open(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = source.playerOrException.blockPosition()
        val capital = Capitals.covering(level, here)
        if (capital == null) {
            // A village's market opened afresh, from the census it has (the town counted now, wherever the box is loaded)
            val rec = Villages.at(level, here) ?: return fail(source, "You're not in a capital or a village.")
            if (!VillageMarkets.enabled()) return fail(source, "Villages have no markets just now (settings: Capital Markets, Living Villages and Village Markets are all needed).")
            val places = Villages.places(level, rec.key) ?: return fail(source, "${rec.name} has not been counted yet: try /guildmark village census now.")
            val entry = VillageMarkets.open(level, rec, places)
            return say(source, plain("Opened the market afresh: everything at what the village likes to keep. People: ${entry.town}"))
        }
        val places = CityPlaces.of(level, capital) ?: return fail(source, "Its places are still being worked out: try again in a second.")
        val entry = Markets.get(level).open(level, places)
        return say(source, plain("Opened the market afresh: everything at what the town likes to keep. People: ${entry.town}"))
    }

    // ---- Text ----

    private fun clock(h: Int): String = "%02d:00".format(h)

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
