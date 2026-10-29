package io.github.vaspyyy.guildmark.trade.hunt

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.trade.Rungs
import io.github.vaspyyy.guildmark.trade.TradeBooks
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.carry.Laden
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * The commands of the Hunt and the hired guard (living-world/trader/design.md section 9; operators only), hung on the `trade` literal that
 * [io.github.vaspyyy.guildmark.trade.TradeCommand] has, with the same permission, so Brigadier merges them by name:
 * - `trade hunt`: how your Hunt stands, if you are in one, and what the roll would make of you here.
 * - `trade hunt now [band]`: the nearest band (or the band with that id) hunts you now, whatever the odds, its rest and the day's gap.
 * - `trade hunt end`: ends your Hunt; with none, ends every Hunt there is. Nothing is lost by it and its party goes.
 * - `trade hunt chance`: for each band whose lookout can see you (the nearest band, if none can), every factor of the odds, the caravans'
 *   table alone (what `caravan list` shows a wagon here, before a tower's watch), the product with the Hunt's own and the number after the cap.
 * - `trade hunt list`: the bands within reach of you and their rest, and the Hunts on now.
 * - `trade guard`: your guards' contracts. `trade guard hire <days>`: one more guard (no Marks taken; the ladder's limit is not asked, three
 *   at most). `trade guard dismiss`: every guard of yours is sent off.
 */
internal object HuntCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    literal("trade")
                        .then(
                            literal("hunt")
                                .executes { status(it) }
                                .then(
                                    literal("now")
                                        .executes { now(it, null) }
                                        .then(Commands.argument("band", IntegerArgumentType.integer(0)).executes { now(it, IntegerArgumentType.getInteger(it, "band")) }),
                                )
                                .then(literal("end").executes { end(it) })
                                .then(literal("chance").executes { chance(it) })
                                .then(literal("list").executes { list(it) }),
                        )
                        .then(
                            literal("guard")
                                .executes { guards(it) }
                                .then(
                                    literal("hire")
                                        .executes { hire(it, 1) }
                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, Company.MOST_DAYS)).executes { hire(it, IntegerArgumentType.getInteger(it, "days")) }),
                                )
                                .then(literal("dismiss").executes { dismiss(it) }),
                        ),
                ),
        )
    }

    // ---- trade hunt ----

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        say(source, header("The Hunt"))
        switches(source)
        val line = Hunt.describe(level, player.uuid)
        if (line != null) say(source, line().append(label("You are hunted: ")).append(plain(line)))
        else say(source, line().append(label("You are not hunted. ")).append(grey("`trade hunt chance` shows the odds here, `trade hunt now` starts one.")))
        val book = TradeBooks.peek(level)?.get(player.uuid)
        val hunts = book?.hunts ?: mapOf()
        val counts = listOf("started", "fought", "robbed", "won", "ran_down", "escaped").joinToString(", ") { "$it ${hunts[it] ?: 0}" }
        say(source, line().append(label("Your counts: ")).append(plain(counts)).append(grey(", units lost ${hunts["units"] ?: 0}")))
        hunts["owed"]?.let { say(source, line().append(red("Owed: ")).append(plain("$it% of what you carry, taken a few seconds after you are back in the game"))) }
        return 1
    }

    /** The switches that decide whether there are Hunts at all. */
    private fun switches(source: CommandSourceStack) {
        if (!TradeConfig.life()) say(source, line().append(red("Off: ")).append(grey("the trader's life (or the capital markets) is switched off; no Hunt starts and no guard walks.")))
        else if (!Caravans.ambushes()) say(source, line().append(red("Off: ")).append(grey("the roads' ambushes are switched off, and with them the Hunt; no guard walks.")))
        else if (!TradeConfig.hunt()) say(source, line().append(red("Off: ")).append(grey("the Hunt is switched off (tradeHunt): no band hunts anyone and no guard walks; contracts wait.")))
    }

    private fun now(context: CommandContext<CommandSourceStack>, id: Int?): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val camps = OutlawCamps.get(level)
        val band = if (id != null) camps.band(id) else camps.bands.filter { usable(it) }.minByOrNull { HuntOdds.distance(player, it) }
        if (band == null) {
            say(source, red(if (id != null) "There is no band $id." else "There is no band with a camp and a captain to send a party."))
            return 0
        }
        if (!usable(band)) {
            say(source, red("Band ${band.id} cannot send a party: ${if (band.slain) "it is broken" else if (!band.built) "its camp is not built yet" else "it has no captain"}."))
            return 0
        }
        val why = Hunt.start(level, player, band, true)
        if (why != null) {
            say(source, line().append(red("No Hunt: ")).append(plain(why)))
            return 0
        }
        say(source, line().append(label("The horn: ")).append(band.title().copy().withStyle(ChatFormatting.RED))
            .append(plain(" (band ${band.id}, ${"%.0f".format(Locale.ROOT, HuntOdds.distance(player, band))} blocks off) hunts you. ")).append(grey("The party comes in six to ten seconds.")))
        if (Laden.of(player) < TradeConfig.huntLaden()) say(source, grey("You carry less than ${TradeConfig.huntLaden()} Marks of goods: the party will find little worth taking."))
        return 1
    }

    private fun usable(band: OutlawBand): Boolean = band.built && !band.slain && band.captain() != null

    private fun end(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.player
        if (player != null && Hunt.endFor(level, player.uuid)) {
            say(source, line().append(label("Ended: ")).append(plain("your Hunt; the party is gone and nothing more is taken.")))
            return 1
        }
        val n = Hunt.endAll(level)
        say(source, if (n == 0) grey("No Hunt is on.") else line().append(label("Ended: ")).append(plain("$n Hunt${if (n == 1) "" else "s"}.")))
        return n
    }

    private fun chance(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        say(source, header("Odds of a Hunt here"))
        switches(source)
        if (player.level().dimension() != Level.OVERWORLD) {
            say(source, red("The Hunt is for the overworld."))
            return 0
        }
        val marks = Laden.of(player)
        val stand = HuntOdds.stand(level, player.blockPosition())
        val followers = Company.followers(player)
        val clock = level.overworldClockTime
        val road = if (stand.onRoad) "on road ${stand.road} of the capital at ${stand.heart?.toShortString()}" else if (stand.heart != null) "off the roads; the nearest town is at ${stand.heart?.toShortString()}" else "off the roads, with no town near"
        say(source, line().append(label("You: ")).append(plain("$marks Marks of goods (a Hunt asks ${TradeConfig.huntLaden()}), $followers following, $road")))
        if (HuntOdds.inTown(level, player.blockPosition())) say(source, line().append(red("In a town: ")).append(grey("no Hunt starts here (a capital's heart within ${HuntOdds.TOWN.toInt()} blocks, a village's bell within ${HuntOdds.BELL}).")))
        val last = TradeBooks.peek(level)?.get(player.uuid)?.hunts?.get("last")
        if (last != null && clock / 20L - last < 24000L / 20L) say(source, line().append(red("Hunted today: ")).append(grey("a player is hunted at most once a game day (${(24000L / 20L - (clock / 20L - last)) / 50L} h to go).")))
        val camps = OutlawCamps.get(level)
        var bands = camps.bands.filter { usable(it) && HuntOdds.distance(player, it) <= HuntOdds.reachOf(it) }.sortedBy { HuntOdds.distance(player, it) }
        if (bands.isEmpty()) {
            val nearest = camps.bands.filter { usable(it) }.minByOrNull { HuntOdds.distance(player, it) }
            if (nearest == null) {
                say(source, grey("There is no band with a camp and a captain anywhere."))
                return 0
            }
            say(source, grey("No band's lookout can see you here; the nearest band's odds, were it in reach:"))
            bands = listOf(nearest)
        }
        for (band in bands) {
            val odds = HuntOdds.odds(level, player, band, marks, stand, followers, clock)
            say(source, line().append(band.title().copy().withStyle(ChatFormatting.RED))
                .append(grey(" (band ${band.id}, ${"%.0f".format(Locale.ROOT, odds.reach)} blocks off, its lookout sees ${HuntOdds.reachOf(band).toInt()})")))
            val table = odds.parts.take(odds.tableParts).joinToString(" x ") { "${it.name} ${f(it.factor)}" }
            val own = odds.parts.drop(odds.tableParts).joinToString(" x ") { "${it.name} ${f(it.factor)}" }
            say(source, line().append(plain("  0.15 x $table")))
            say(source, line().append(label("  The caravans' table: ")).append(plain(pct(odds.table))).append(grey(" (as `caravan list` works a wagon's chance here, before a tower's watch)")))
            say(source, line().append(plain("  x $own")))
            if (odds.rested) {
                say(source, line().append(label("  Now: ")).append(red("0%")).append(grey(" - the band is resting (two days after a strike, or the day of a village raid and the day after)")))
            } else {
                say(source, line().append(label("  Product: ")).append(plain(pct(odds.product))).append(label("  After the cap: ")).append(plain(pct(odds.chance)))
                    .append(grey(" - rolled once every ${8000 / 1000} hours a band, the nearest first")))
            }
        }
        return 1
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.player
        say(source, header("Bands and Hunts"))
        switches(source)
        if (player != null) {
            val data = CaravanData.get(level)
            val clock = level.overworldClockTime
            val near = OutlawCamps.get(level).bands.filter { !it.slain }.sortedBy { HuntOdds.distance(player, it) }
            val reach = near.filter { HuntOdds.distance(player, it) <= HuntOdds.reachOf(it) }
            if (reach.isEmpty()) say(source, grey("No band's lookout can see you here."))
            for (band in reach) {
                val rec = data.bandOrNull(band.id)
                val free = Caravans.allowed(level, data, band, clock)
                val struck = rec?.struck?.takeIf { it > Long.MIN_VALUE / 4 }?.let { "struck ${(clock - it) / 1000L} h ago" } ?: "never struck"
                val raid = if (band.raided >= 0) ", raided a village on day ${band.raided}" else ""
                val state = when {
                    !usable(band) -> "cannot send a party (${if (!band.built) "camp not built" else "no captain"})"
                    Hunt.bandOut(band.id) -> "out on a Hunt now"
                    free -> "free to strike"
                    else -> "resting"
                }
                say(source, line().append(band.title().copy().withStyle(ChatFormatting.RED))
                    .append(plain(" (band ${band.id}, ${"%.0f".format(Locale.ROOT, HuntOdds.distance(player, band))} blocks, sees ${HuntOdds.reachOf(band).toInt()}): $state")).append(grey(" - $struck$raid, ${rec?.wounds ?: 0} wounded")))
            }
        }
        val on = Hunt.hunted()
        if (on.isEmpty()) say(source, grey("No Hunt is on."))
        for (id in on) {
            val who = level.server.playerList.getPlayer(id)?.name?.string ?: id.toString()
            say(source, line().append(label("Hunted: ")).append(plain("$who - ${Hunt.describe(level, id) ?: "?"}")))
        }
        return 1
    }

    // ---- trade guard ----

    private fun guards(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        say(source, header("Hired guards"))
        switches(source)
        val lines = Company.report(player)
        if (lines.isEmpty()) say(source, grey("You have no guard hired. `trade guard hire <days>` hires one; the hall's counter sells them."))
        for (l in lines) say(source, plain(l))
        say(
            source,
            grey("Your rung allows ${Rungs.guardLimit(player)} in company (a travelling companion counts as one); you have ${Company.company(player)}. " +
                "A guard costs ${TradeConfig.guardWage()} Marks a day at the counter (${(1..Company.MOST_DAYS).joinToString(", ") { "$it day${if (it == 1) "" else "s"} ${Company.price(it)}" }})."),
        )
        return 1
    }

    private fun hire(context: CommandContext<CommandSourceStack>, days: Int): Int {
        val source = context.source
        val player = source.playerOrException
        if (!TradeConfig.hunt()) {
            say(source, red("The Hunt is switched off, and no guard walks while it is."))
            return 0
        }
        if (player.level().dimension() != Level.OVERWORLD) {
            say(source, red("Guards walk the overworld's roads only."))
            return 0
        }
        val have = TradeBooks.peek(player.level())?.get(player.uuid)?.guards?.size ?: 0
        if (have >= Company.COMMAND_MOST) {
            say(source, red("You have ${Company.COMMAND_MOST} guards already, the most the command hires."))
            return 0
        }
        if (Company.hire(player, days) == null) {
            say(source, red("No guard could be hired."))
            return 0
        }
        say(source, line().append(label("Hired: ")).append(plain("a guard for $days day${if (days == 1) "" else "s"}, at no cost. ")).append(grey("They come up from behind you in a second or two (or appear at your side if there is nowhere out of sight).")))
        return 1
    }

    private fun dismiss(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val n = Company.dismiss(player)
        say(source, if (n == 0) grey("You have no guard hired.") else line().append(label("Dismissed: ")).append(plain("$n guard${if (n == 1) "" else "s"}.")))
        return n
    }

    // ---- Words ----

    private fun f(x: Double): String = "%.2f".format(Locale.ROOT, x)
    private fun pct(x: Double): String = "%.1f%% (%.4f)".format(Locale.ROOT, x * 100, x)
    private fun say(source: CommandSourceStack, c: Component) = source.sendSuccess({ c }, false)
    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun red(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
}
