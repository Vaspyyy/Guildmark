package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Testing commands for the trading houses (trader design, Part 5; build part G; operators only), under `/guildmark trade house`.
 *
 * - `house [list]` lists every House: whose, where, its purse, its carters (each with what they are doing), the trips on the road and why an idle
 *   carter is not sent, and its totals since it opened.
 * - `house carter add` hires a carter for the sender's House (whatever the cap, not past six); `house carter remove <id>` lets one go (one out
 *   with a wagon goes when they are back); `house carter guards <id> <n>` sets one's extra guards.
 * - `house purse <marks>` sets the sender's House's purse (nothing counted as put in or taken out).
 * - `house step <hours>` runs the world's clock forward that many market hours, a few at a time, so that the markets, the tickets, the caravans
 *   and the Houses each keep up, and then tells what each House's money did over them; `house step 0` stops a run.
 * - `house plan` says what the planner would send an idle carter of the sender's House on now, best first, and why not if nothing.
 * - `house odds` gives, for each House wagon on the road with a caravan record, the game's chance of a strike on it (`Caravans.chanceFor`, its
 *   factors by name) and of losing the fight, beside the model's single exposure (`agent.py`'s `waylay`).
 *
 * Nothing here makes a record by looking.
 */
object HouseCommand {
    /** A clock run (`house step`): the market hours still to go and what each House's totals were when it started. */
    private class Stepping(val source: CommandSourceStack, var left: Long, val hours: Long, val from: Map<String, HouseTotals>, val startHour: Long) {
        var waits = 0
    }

    private var stepping: Stepping? = null

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % 20 == 15) step(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> stepping = null }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("trade")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("house")
                                .executes { list(it) }
                                .then(Commands.literal("list").executes { list(it) })
                                .then(
                                    Commands.literal("carter")
                                        .then(Commands.literal("add").executes { hire(it) })
                                        .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(0)).executes { dismiss(it, IntegerArgumentType.getInteger(it, "id")) }))
                                        .then(
                                            Commands.literal("guards").then(
                                                Commands.argument("id", IntegerArgumentType.integer(0)).then(
                                                    Commands.argument("n", IntegerArgumentType.integer(0, Houses.EXTRA_GUARDS))
                                                        .executes { guards(it, IntegerArgumentType.getInteger(it, "id"), IntegerArgumentType.getInteger(it, "n")) },
                                                ),
                                            ),
                                        ),
                                )
                                .then(Commands.literal("purse").then(Commands.argument("marks", IntegerArgumentType.integer(0, 999_999)).executes { purse(it, IntegerArgumentType.getInteger(it, "marks")) }))
                                .then(Commands.literal("step").then(Commands.argument("hours", IntegerArgumentType.integer(0, 24 * 56)).executes { start(it, IntegerArgumentType.getInteger(it, "hours")) }))
                                .then(Commands.literal("plan").executes { plan(it) })
                                .then(Commands.literal("odds").executes { odds(it) }),
                        ),
                ),
        )
    }

    // ---- Every House ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        if (!TradeConfig.house()) say(source, grey("Trading houses are switched off (setting: Trading House, or what it needs): no trip is sent and no wage falls due; the wagons on the road still get in."))
        val data = TradeHouses.peek(level)
        if (data == null || data.houses.isEmpty()) return say(source, grey("No trading house has opened in this world."))
        say(source, header("Trading houses"))
        val now = Markets.hourOf(level)
        for (h in data.houses) {
            val who = level.server.playerList.getPlayer(h.owner)?.name?.string ?: h.owner.toString()
            val state = when {
                h.closed -> " (taken down: winding up)"
                Houses.away(h, now) -> " (its owner away more than ${TradeConfig.houseOffline()} days: the carters wait)"
                else -> ""
            }
            say(source, line().append(label("${h.name.ifEmpty { who }} ")).append(plain("in ${Houses.placeOf(level, h.capital).ifEmpty { where(h.capital) }}"))
                .append(grey(" ($who's; purse ${h.purse} Marks; ${h.carters.size} of ${Houses.most(level)} carters; tickets held ${HousePlanner.heldBy(level, h)} of ${HousePlanner.HELD})$state")))
            if (h.idleWhy.isNotEmpty() && h.carters.any { it.free }) say(source, grey("  idle: ${idleText(h.idleWhy)}"))
            for (c in h.carters) {
                val trip = if (c.trip >= 0) h.trip(c.trip) else null
                val doing = when {
                    trip == null -> if (c.state == Carter.State.UNPAID) "waiting for ${c.unpaid} day's wage" else "at the yard"
                    else -> tripText(level, trip)
                }
                say(source, grey("  #${c.id} ${c.name}: $doing; ${c.trips} trips, earned ${c.earned}; extra guards ${c.guards}${if (c.unpaid > 0) "; owed ${c.unpaid} days" else ""}"))
            }
            val t = h.totals
            val days = if (t.since == Long.MIN_VALUE) 0.0 else max(1L, h.hour - t.since) / 24.0
            say(source, grey("  since it opened (%.1f days): %d trips, %d waylaid; goods %d, deposits %d, pay %d, given back %d, sold %d, wright %d, wages %d + guards %d; net %d (%.2f a day); put in %d, taken out %d".format(
                Locale.ROOT, days, t.trips, t.waylaid, t.cost, t.deposit, t.pay, t.refund, t.sold, t.repair, t.wages, t.guardWages, t.net, if (days > 0) t.net / days else 0.0, t.put, t.taken,
            )))
        }
        return 1
    }

    private fun tripText(level: ServerLevel, trip: Trip): String {
        val now = level.overworldClockTime
        val where = trip.destName.ifEmpty { where(trip.dest) }
        return when (trip.stage) {
            Trip.Stage.LOADING -> "loading for $where (${trip.units()} units), leaves in ${hours(trip.departAt - now)}"
            Trip.Stage.OUT -> "on the road to $where (${trip.units()} units, ${trip.guards} guards${if (trip.record >= 0) ", caravan #${trip.record}" else ", on its own clock"}), in in ${hours(trip.arriveAt - now)}"
            Trip.Stage.BACK -> (if (trip.waylaid) "waylaid at $where, limping home" else "on the way home from $where") + ", back in ${hours(trip.backAt - now)}"
        }
    }

    private fun idleText(why: String): String = when (why) {
        "off" -> "the House is switched off"
        "away" -> "its owner has been away too long"
        "float" -> "the purse is short of a trip and a day's wages"
        "held" -> "it holds as many tickets as it may"
        "word" -> "its Word shows no other town"
        "tickets" -> "no ticket in reach pays for a trip"
        "closed" -> "its town's market is closed"
        else -> why
    }

    // ---- The sender's House ----

    private fun house(source: CommandSourceStack): HouseRec? {
        val player = source.player ?: run {
            fail(source, "Only a player can do that (it is their House).")
            return null
        }
        val h = TradeHouses.peek(source.level)?.of(player.uuid) ?: run {
            fail(source, "You have no trading house (works propose counting_house, fund it and finish it).")
            return null
        }
        return h
    }

    private fun hire(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = house(source) ?: return 0
        if (h.carters.size >= 6) return fail(source, "A House keeps six carters at the most.")
        val c = Houses.hire(source.level, h, force = true) ?: return fail(source, "The House cannot hire (it is being taken down).")
        return say(source, plain("${c.name} is hired, carter #${c.id} (${h.carters.size} now, the setting allows ${Houses.most(source.level)})."))
    }

    private fun dismiss(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val h = house(source) ?: return 0
        val c = h.carter(id) ?: return fail(source, "The House has no carter #$id.")
        if (Houses.dismiss(source.level, h, c)) return say(source, plain("${c.name} is let go."))
        return say(source, plain("${c.name} is out with a wagon: they go when they are back."))
    }

    private fun guards(context: CommandContext<CommandSourceStack>, id: Int, n: Int): Int {
        val source = context.source
        val h = house(source) ?: return 0
        val c = h.carter(id) ?: return fail(source, "The House has no carter #$id.")
        Houses.guards(source.level, c, n)
        return say(source, plain("${c.name}'s wagon takes $n extra guard${if (n == 1) "" else "s"} from the next trip (${"%.1f".format(Locale.ROOT, n * TradeConfig.guardWage())} Marks a day)."))
    }

    private fun purse(context: CommandContext<CommandSourceStack>, marks: Int): Int {
        val source = context.source
        val h = house(source) ?: return 0
        Houses.setPurse(source.level, h, marks)
        return say(source, plain("The purse holds ${h.purse} Marks."))
    }

    // ---- The planner ----

    private fun plan(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val h = house(source) ?: return 0
        val entry = if (Markets.enabled()) Markets.get(level)[BlockPos.of(h.capital)] else null
        if (entry == null) return fail(source, "The House's town has no market open.")
        val reserve = h.carters.size * TradeConfig.carterWage()
        val plans = HousePlanner.plans(level, h, entry, level.overworldClockTime, h.purse - reserve)
        say(source, header("What ${h.name.ifEmpty { "the House" }} would send a carter on now"))
        say(source, grey("Purse ${h.purse}, less a day's wages ($reserve); ${HousePlanner.known(level, h, level.overworldClockTime).size} towns in its Word; ${HousePlanner.heldBy(level, h)} tickets held."))
        if (plans.isEmpty()) return say(source, grey("Nothing: ${idleText(HousePlanner.lastWhy)}."))
        for ((i, p) in plans.take(6).withIndex()) say(source, (if (i == 0) plain("1. ") else grey("${i + 1}. ")).append(grey(HousePlanner.line(p))))
        return 1
    }

    // ---- The odds ----

    private fun odds(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val data = TradeHouses.peek(level)
        val trips = data?.houses?.flatMap { h -> h.trips.filter { it.stage == Trip.Stage.OUT && it.record >= 0 }.map { h to it } } ?: listOf()
        if (trips.isEmpty()) return say(source, grey("No House wagon is on the road with a caravan record."))
        val caravans = CaravanData.get(level)
        val camps = OutlawCamps.get(level)
        say(source, header("House wagons' exposure: the game's against the model's"))
        for ((h, trip) in trips) {
            val c = caravans.caravans.firstOrNull { it.id == trip.record && it.run == Run.HOUSE } ?: continue
            val head = "${h.name.ifEmpty { "?" }}'s wagon to ${trip.destName.ifEmpty { where(trip.dest) }} (caravan #${c.id}, ${c.guards} guards)"
            val band = camps.band(c.band)
            if (band == null || c.strike < 0) {
                say(source, plain(head).append(grey(": no band works its road in (the game's chance 0; the model's would be 0 too: no band)")))
                continue
            }
            val at = c.setOut + c.held + Caravans.ticksFor(c.strike)
            val e = Caravans.Exposure(c.reach, at, (c.guards - 2).coerceAtLeast(0), c.capital, c.road)
            val p = Caravans.chanceFor(level, caravans, band, e)
            val factors = Caravans.factors(level, caravans, e)
            val lose = Caravans.odds(caravans, c, band)
            say(source, plain(head))
            say(source, grey("  the game: chance %.3f = 0.15 x %s (at most 0.6); lose the fight %.2f (band %d against %.1f); %s".format(
                Locale.ROOT, p, factors.joinToString(" x ") { "%s %.2f".format(Locale.ROOT, it.first, it.second) }, lose,
                Caravans.strength(band, caravans.bandOrNull(band.id)), c.guards + 0.3 * c.wagons,
                if (c.struck == 0) "struck at ${c.strike.toInt()} of ${c.length.toInt()} blocks" else if (c.struck == 2) "struck" else "past its strike point",
            )))
            // The model's single exposure: 0.15 x 0.65 (the camp's reach on average) x night x weather x 0.85^max(g-2, 0) x (1 + peril/4) x min(2, 0.5 + value/70)
            val named = factors.toMap()
            val value = trip.cost.toDouble()
            val g = c.guards
            val model = (0.15 * 0.65 * (named["night"] ?: 1.0) * (named["weather"] ?: 1.0) * 0.85.pow(max(g - 2, 0)) * (named["peril"] ?: 1.0) * min(2.0, 0.5 + value / 70.0)).coerceAtMost(0.6)
            val b = Caravans.strength(band, caravans.bandOrNull(band.id)).toDouble()
            val modelLose = b / (b + 1.5 * (g + 0.3))
            say(source, grey("  the model: chance %.3f (reach 0.65, laden %.2f for a load worth %d), lose %.2f; waylaid %.3f a trip against the game's %.3f".format(
                Locale.ROOT, model, min(2.0, 0.5 + value / 70.0), trip.cost, modelLose, model * modelLose, p * lose,
            )))
        }
        return 1
    }

    // ---- The clock ----

    private fun start(context: CommandContext<CommandSourceStack>, hours: Int): Int {
        val source = context.source
        val level = source.level
        if (hours == 0) {
            val was = stepping ?: return say(source, grey("No clock run is going."))
            stepping = null
            report(level, was)
            return say(source, plain("The clock run is stopped."))
        }
        if (stepping != null) return fail(source, "A clock run is going already (house step 0 stops it).")
        if (!Markets.enabled()) return fail(source, "The markets are switched off: there is no market hour to run.")
        val from = TradeHouses.peek(level)?.houses?.associate { key(it) to Houses.snapshot(it) } ?: mapOf()
        stepping = Stepping(source, hours.toLong(), hours.toLong(), from, Markets.hourOf(level))
        return say(source, plain("Running the clock $hours market hours forward, a few at a time (house step 0 stops it)."))
    }

    private fun key(h: HouseRec): String = "${h.owner}/${h.capital}"

    /** Once a second while a run is going: once every market, the tickets and the Houses have caught up, the clock goes on a few hours more. */
    private fun step(server: MinecraftServer) {
        val run = stepping ?: return
        val level = server.overworld()
        if (!Markets.enabled()) {
            stepping = null
            run.source.sendFailure(Component.literal("The markets were switched off: the clock run is stopped."))
            return
        }
        val now = Markets.hourOf(level)
        val markets = Markets.get(level)
        // Each keeps up before the clock goes on (half a minute at most, so that nothing that has stopped for its own reasons holds the run)
        val books = if (TradeConfig.hauls()) HaulData.peek(level)?.all() else null
        val behind = markets.all().any { it.market.hour != Long.MIN_VALUE && it.market.hour < now } ||
            books != null && books.any { markets[BlockPos.of(it.capital)] != null && it.hour != Long.MIN_VALUE && it.hour < now } ||
            !Houses.caughtUp(level, now)
        if (behind && run.waits++ < 30) return
        run.waits = 0
        if (run.left <= 0) {
            stepping = null
            report(level, run)
            return
        }
        val k = min(run.left, max(1L, 48L / max(1, markets.all().size)))
        val clock = level.dimensionType().defaultClock().orElse(null) ?: run {
            stepping = null
            run.source.sendFailure(Component.literal("This world's clock cannot be run forward."))
            return
        }
        server.clockManager().addTicks(clock, (k * 1000L).toInt())
        run.left -= k
    }

    /** What each House's money did over [run]. */
    private fun report(level: ServerLevel, run: Stepping) {
        val source = run.source
        val hours = Markets.hourOf(level) - run.startHour
        val days = max(1L, hours) / 24.0
        say(source, header("The clock ran %d market hours (%.1f days)".format(Locale.ROOT, hours, days)))
        val data = TradeHouses.peek(level)
        if (data == null || data.houses.isEmpty()) {
            say(source, grey("No trading house."))
            return
        }
        for (h in data.houses) {
            val a = run.from[key(h)] ?: HouseTotals()
            val b = h.totals
            val trips = b.trips - a.trips
            val net = b.net - a.net
            val wages = (b.wages - a.wages) + (b.guardWages - a.guardWages)
            say(source, line().append(label("${h.name.ifEmpty { "?" }}: ")).append(plain("%d trips, %d waylaid; goods %d, pay %d, given back %d, sold %d, wright %d, wages %d; net %d, %.2f Marks a day (%d carters)".format(
                Locale.ROOT, trips, b.waylaid - a.waylaid, b.cost - a.cost, b.pay - a.pay, (b.refund - a.refund) - (b.deposit - a.deposit), b.sold - a.sold, b.repair - a.repair, wages, net, net / days, h.carters.size,
            ))))
            if (net / days > 20.0) say(source, Component.literal("  More than 20 Marks a day: that is more than the design's table allows, and likely a loop; tell the lead.").withStyle(ChatFormatting.RED))
        }
    }

    // ---- Small helpers ----

    private fun hours(ticks: Long): String = if (ticks <= 0) "now" else "%.1f h".format(Locale.ROOT, ticks / 1000.0)

    private fun where(capital: Long): String = BlockPos.of(capital).let { "${it.x}_${it.z}" }

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
