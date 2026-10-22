package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Lots
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.players.NameAndId
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Testing commands for the market's loose ends (trader design, Part 7; operators only), under `/guildmark trade`.
 * `herd [town]` is the herds' stop on wool, leather, feathers and apples: what the stalls and the yard hold of each
 * against what the town keeps, whether the stop holds, is lifted by a waylaid wagon or is switched off, and the lot the
 * stalls would pay for it now. `freight [town] [good]` is what bringing goods in costs there (the carry) against where
 * it is heading (its goal) and how long it has to go; `freight [town] snap` puts every carry on its goal now, and
 * `freight [town] ease <days>` works the ease on by that many days. `owed [player]` is the dealing a player's carters
 * did while they were away, kept to be given as Trade XP at their next login; `owed <player> set <marks>` sets it and
 * `owed <player> give` settles it now. A town is named as its capital is (see [nameOf]; where its ground isn't loaded,
 * by where it stands, "x_z"); left out, it is the capital you stand in.
 */
object LeftoverCommand {
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
                            Commands.literal("herd")
                                .executes { herd(it, null) }
                                .then(town().executes { herd(it, StringArgumentType.getString(it, "town")) }),
                        )
                        .then(
                            Commands.literal("freight")
                                .executes { freight(it, null, null) }
                                .then(Commands.literal("snap").executes { snap(it, null) })
                                .then(Commands.literal("ease").then(days().executes { ease(it, null, IntegerArgumentType.getInteger(it, "days")) }))
                                .then(
                                    town()
                                        .executes { freight(it, StringArgumentType.getString(it, "town"), null) }
                                        .then(Commands.literal("snap").executes { snap(it, StringArgumentType.getString(it, "town")) })
                                        .then(
                                            Commands.literal("ease").then(
                                                days().executes { ease(it, StringArgumentType.getString(it, "town"), IntegerArgumentType.getInteger(it, "days")) },
                                            ),
                                        )
                                        .then(
                                            Commands.argument("good", StringArgumentType.word())
                                                .suggests { _, b -> SharedSuggestionProvider.suggest(Good.TRADED.map { it.id }, b) }
                                                .executes { freight(it, StringArgumentType.getString(it, "town"), StringArgumentType.getString(it, "good")) },
                                        ),
                                ),
                        )
                        .then(
                            Commands.literal("owed")
                                .executes { owed(it, null) }
                                .then(
                                    Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes { owed(it, profiles(it)) }
                                        .then(
                                            Commands.literal("set").then(
                                                Commands.argument("marks", IntegerArgumentType.integer(0, 100000))
                                                    .executes { set(it, profiles(it), IntegerArgumentType.getInteger(it, "marks")) },
                                            ),
                                        )
                                        .then(Commands.literal("give").executes { give(it, profiles(it)) }),
                                ),
                        ),
                ),
        )
    }

    private fun town(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("town", StringArgumentType.word()).suggests { c, b -> SharedSuggestionProvider.suggest(towns(c.source), b) }

    private fun days(): RequiredArgumentBuilder<CommandSourceStack, Int> = Commands.argument("days", IntegerArgumentType.integer(1, 60))

    private fun profiles(context: CommandContext<CommandSourceStack>): List<NameAndId> = GameProfileArgument.getGameProfiles(context, "player").toList()

    // ---- The town asked about ----

    /** What the commands call [entry]'s capital: its name as its orders keep it or its ground has it, or where it stands if neither is known now. */
    private fun nameOf(level: ServerLevel, entry: Markets.Entry): String = Suppliers.place(level, entry.capital).ifEmpty { where(entry) }

    private fun where(entry: Markets.Entry): String = "${entry.capital.x}_${entry.capital.z}"

    /** The names of every open market, for the suggestions and for saying what there is to choose from. */
    private fun towns(source: CommandSourceStack): List<String> {
        val level = source.level
        return Markets.get(level).all().map { nameOf(level, it) }.sorted()
    }

    private fun listed(source: CommandSourceStack): String {
        val names = towns(source)
        if (names.isEmpty()) return "no market is open yet (a capital's opens when a player first walks in)."
        return names.take(NAMES).joinToString(", ") + if (names.size > NAMES) ", and ${names.size - NAMES} more." else "."
    }

    /** The open market called [name] (or at "x_z"); the nearest to [source] if two capitals share a name. */
    private fun find(source: CommandSourceStack, name: String): Markets.Entry? {
        val level = source.level
        val at = source.position
        return Markets.get(level).all()
            .filter { nameOf(level, it).equals(name, ignoreCase = true) || where(it) == name }
            .minByOrNull { square(it.capital.x - at.x) + square(it.capital.z - at.z) }
    }

    private fun square(x: Double): Double = x * x

    /** The market named [name], or if none is the capital the sender stands in, opened now if need be; null (and why, said) if there isn't one. */
    private fun entry(source: CommandSourceStack, name: String?): Markets.Entry? {
        val level = source.level
        val entry = if (name != null) {
            find(source, name) ?: run {
                fail(source, "No open market is called $name: " + listed(source))
                return null
            }
        } else {
            val player = source.player ?: run {
                fail(source, "Name a town: " + listed(source))
                return null
            }
            val capital = Capitals.covering(level, player.blockPosition()) ?: run {
                fail(source, "You're not in a capital: stand in one, or name a town: " + listed(source))
                return null
            }
            Markets.of(level, capital) ?: run {
                fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
                return null
            }
        }
        if (!Markets.enabled()) say(source, grey("The markets are switched off (setting: Capital Markets): this one stands still."))
        return entry
    }

    // ---- The herds' stop ----

    private fun herd(context: CommandContext<CommandSourceStack>, town: String?): Int {
        val source = context.source
        val level = source.level
        val entry = entry(source, town) ?: return 0
        val m = entry.market
        val factor = Economy.herdStop()
        val trouble = m.waylaid()
        // The lot a stall pays the sender, at the share their standing there earns them (the usual share for no one)
        val share = source.player?.let { Suppliers.share(level, entry.capital, it.uuid) } ?: Lots.SELL_SHARE
        say(source, header("The herds' stop at ${nameOf(level, entry)}"))
        when {
            factor == null -> say(source, grey("Off (setting: Herd Stop): the herds, coops and orchards make as they always did."))
            trouble != null -> say(source, line().append(gold("Lifted")).append(grey(": a waylaid wagon has the road in trouble for ${days(trouble.until - m.hour)} more, so the herds make all they can.")))
            else -> say(source, grey("On, at %.0f%% of what the town keeps (setting: Herd Stop Height): the herds make no more once the stalls and the yard hold that, so the stalls have room.".format(factor * 100)))
        }
        for (g in Economy.HERD_STOPPED.sortedBy { it.ordinal }) {
            val q = Trading.quote(entry, g, share)
            val keeps = entry.town.target(g)
            val stock = m.stock(g)
            val yard = m[Site.FARMS, g]
            say(source, line().append(Component.translatable(g.key).withStyle(ChatFormatting.YELLOW))
                .append(plain(": %.1f in the stalls (%.2f of the %.0f it keeps) and %.1f in the yard".format(stock, stock / keeps, keeps, yard))))
            val stop = Economy.herdStopAt(m, entry.town, g)
            say(source, when {
                factor == null -> line().append(red("   off")).append(grey(": it is made all the day's working hours"))
                stop == null -> line().append(gold("   lifted")).append(grey(": it is made all the day's working hours while the wagon is out"))
                stock + yard >= stop - AT_STOP -> line().append(green("   holds")).append(grey(": the stalls and the yard are at the stop of %.1f, so none is made".format(stop)))
                else -> line().append(green("   holds")).append(grey(": %.1f short of the stop of %.1f, made up in working hours".format(stop - stock - yard, stop)))
            })
            val lot = q.sell?.let { "a stall pays ${it.marks} Marks for ${it.count}" }
                ?: if (q.wants < 1) "no room" else "no lot (the room is worth under a Mark, and a market holding more than it keeps takes none then)"
            say(source, grey("   price %.3f against %.3f usual (x%.2f); room for %d; %s".format(q.price, q.usual, q.price / q.usual, q.wants, lot)))
        }
        return 1
    }

    // ---- What bringing goods in costs ----

    private fun freight(context: CommandContext<CommandSourceStack>, town: String?, good: String?): Int {
        val source = context.source
        val level = source.level
        // "freight fish" with no town named: a good where a town goes, for the capital the sender stands in
        val asGood = town?.takeIf { good == null && find(source, it) == null }?.let { Good.byId(it) }
        val entry = entry(source, if (asGood != null) null else town) ?: return 0
        val only = asGood ?: good?.let { id -> Good.byId(id)?.takeIf { it.traded } ?: return fail(source, "The market doesn't deal in $id.") }
        val m = entry.market
        say(source, header("What bringing goods in costs at ${nameOf(level, entry)}"))
        say(source, grey(
            if (Market.easing()) "The carry closes %.0f%% of the way to its goal each day (setting: Carry Ease Rate).".format(Market.carryEaseRate() * 100)
            else "Not easing (setting: Carry Ease is off, or its rate is 100%): the carry jumps to its goal.",
        ))
        if (only != null) {
            say(source, carryRow(m, only))
            say(source, grey("   %.2f of what the town uses lately came by caravan; its price is %.3f against %.3f usual".format(m.brought[only.ordinal], m.price(entry.town, only), only.base * m.freight(only))))
            return 1
        }
        val (moving, there) = Good.TRADED.partition { abs(m.carryGoal[it.ordinal] - m.carry[it.ordinal]) >= SAME }
        for (g in moving.sortedByDescending { abs(m.carryGoal[it.ordinal] - m.carry[it.ordinal]) }) say(source, carryRow(m, g))
        if (there.isNotEmpty()) say(source, grey("On their goals: " + there.joinToString(", ") { Component.translatable(it.key).string + " %.3f".format(m.carry[it.ordinal]) }))
        say(source, grey("${moving.size} of ${Good.TRADED.size} goods are on their way."))
        return 1
    }

    /** One good's carry against its goal: the days to within a few hundredths of it and to being on it, and what that does to the freight on its price. */
    private fun carryRow(m: Market, g: Good): MutableComponent {
        val i = g.ordinal
        val carry = m.carry[i]
        val goal = m.carryGoal[i]
        val name = Component.translatable(g.key).withStyle(ChatFormatting.YELLOW)
        if (abs(goal - carry) < SAME) return line().append(name).append(grey(": carry %.3f, on its goal (freight x%.2f)".format(carry, m.freight(g))))
        return line().append(name).append(plain(": carry %.3f, heading for %.3f".format(carry, goal)))
            .append(grey("   within %.2f of it in %.1f days, on it in %.1f (freight x%.2f now, x%.2f on the goal)".format(CLOSE, m.carryDays(g, CLOSE), m.carryDays(g), m.freight(g), 1.0 + goal * m.brought[i])))
    }

    private fun snap(context: CommandContext<CommandSourceStack>, town: String?): Int {
        val source = context.source
        val entry = entry(source, town) ?: return 0
        val m = entry.market
        val before = m.carry.copyOf()
        m.carryGoal.copyInto(m.carry)
        return moved(source, entry, before, "Put the carry on its goal at once")
    }

    private fun ease(context: CommandContext<CommandSourceStack>, town: String?, days: Int): Int {
        val source = context.source
        val entry = entry(source, town) ?: return 0
        val m = entry.market
        val before = m.carry.copyOf()
        m.easeCarry(days * 24)
        return moved(source, entry, before, "Eased the carry on $days ${if (days == 1) "day" else "days"}")
    }

    /** Says which carries [what] moved, from [before] to where they stand, and keeps the market. */
    private fun moved(source: CommandSourceStack, entry: Markets.Entry, before: DoubleArray, what: String): Int {
        val m = entry.market
        Markets.get(source.level).changed()
        val goods = Good.TRADED.filter { abs(m.carry[it.ordinal] - before[it.ordinal]) >= SAME }
        if (goods.isEmpty()) return say(source, plain("$what, but no carry had anywhere to go: every one is on its goal already, or the ease is off."))
        say(source, plain("$what: ${goods.size} of ${Good.TRADED.size} goods moved."))
        for (g in goods.sortedByDescending { abs(m.carry[it.ordinal] - before[it.ordinal]) }) {
            val i = g.ordinal
            say(source, line().append(Component.translatable(g.key).withStyle(ChatFormatting.YELLOW))
                .append(plain(": %.3f to %.3f".format(before[i], m.carry[i])))
                .append(grey(" (goal %.3f, freight x%.2f)".format(m.carryGoal[i], m.freight(g)))))
        }
        return goods.size
    }

    // ---- The owed Trade XP ----

    private fun owed(context: CommandContext<CommandSourceStack>, who: List<NameAndId>?): Int {
        val source = context.source
        val people = who ?: run {
            val player = source.player ?: return fail(source, "Name a player.")
            listOf(NameAndId(player.uuid, player.gameProfile.name))
        }
        if (!Suppliers.owedEnabled()) say(source, grey("Owed Trade XP is switched off (setting: Owed Trade XP): nothing is kept for anyone who is away, and nothing is given at login."))
        for (p in people) describe(source, p)
        return people.size
    }

    private fun describe(source: CommandSourceStack, who: NameAndId) {
        val kept = Suppliers.get(source.level).owed[who.id]
        val most = Suppliers.owedMost()
        val player = source.server.playerList.getPlayer(who.id)
        if (kept == null) {
            say(source, line().append(label("${who.name}: ")).append(plain("nothing owed.")))
        } else {
            val given = minOf(kept, most.toDouble())
            say(source, line().append(label("${who.name}: ")).append(plain("${num(kept)} Marks of dealing owed, ${num(given * Suppliers.TRADE_XP)} Trade XP when it is given (before their talents and the XP multiplier).")))
            if (kept > most) say(source, grey("   More than the most kept for a player ($most Marks; setting: Most Owed Trade XP): only that much is given."))
        }
        if (player == null) {
            say(source, grey("   Not on: what is owed them is given when they next log in."))
            return
        }
        say(source, grey("   On now, Trade level ${Skills.level(player, Skill.TRADE)}." + if (kept != null) " 'trade owed ${who.name} give' settles it now." else ""))
        if (kept != null && (player.isCreative || player.isSpectator)) say(source, grey("   In creative or spectator mode no Trade XP is given: it is kept for a login in another mode."))
    }

    private fun set(context: CommandContext<CommandSourceStack>, who: List<NameAndId>, marks: Int): Int {
        val source = context.source
        val data = Suppliers.get(source.level)
        for (p in who) {
            val was = data.owed[p.id]
            if (marks > 0) data.owed[p.id] = marks.toDouble() else data.owed.remove(p.id)
            say(source, plain("${p.name} is owed $marks Marks of dealing now" + (was?.let { " (was ${num(it)})" } ?: "") + ": ${num(marks * Suppliers.TRADE_XP)} Trade XP when it is given."))
        }
        data.changed()
        if (!Suppliers.owedEnabled()) say(source, grey("Owed Trade XP is switched off (setting: Owed Trade XP): nothing is given at login while it is."))
        else if (marks > Suppliers.owedMost()) say(source, grey("Only ${Suppliers.owedMost()} Marks are given (setting: Most Owed Trade XP)."))
        return who.size
    }

    private fun give(context: CommandContext<CommandSourceStack>, who: List<NameAndId>): Int {
        val source = context.source
        val data = Suppliers.get(source.level)
        var given = 0
        for (p in who) {
            val kept = data.owed[p.id]
            val player = source.server.playerList.getPlayer(p.id)
            when {
                !Suppliers.owedEnabled() -> fail(source, "Owed Trade XP is switched off (setting: Owed Trade XP): nothing is given.")
                kept == null -> fail(source, "Nothing is owed ${p.name}.")
                player == null -> fail(source, "${p.name} isn't on: what is owed them is given when they next log in.")
                player.isCreative || player.isSpectator -> fail(source, "${p.name} is in creative or spectator mode, where no Trade XP is given: it is kept for a login in another mode.")
                else -> {
                    val level = Skills.level(player, Skill.TRADE)
                    val xp = Suppliers.settle(player)
                    say(source, plain("Settled ${p.name}'s ${num(kept)} Marks of dealing: ${num(xp)} Trade XP asked of the skill (their grey line says what it took, with their talents and the XP multiplier). Trade was level $level; the XP lands within half a second."))
                    given++
                }
            }
        }
        return given
    }

    // ---- Text ----

    /** Most names listed when there is a choice of towns. */
    private const val NAMES = 12

    /** A carry this near its goal reads as on it (three places are shown), and a stock this near its stop as at it. */
    private const val SAME = 0.0005
    private const val AT_STOP = 0.001

    /** How near a goal a carry is said to be "within" (the freight on a price barely moves past that). */
    private const val CLOSE = 0.02

    /** A count of Marks or XP, with a decimal only where it has one. */
    private fun num(x: Double): String = if (abs(x - x.roundToInt()) < 0.05) "%.0f".format(x) else "%.1f".format(x)

    private fun days(hours: Long): String = "%.1f days".format(hours / 24.0)

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun green(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GREEN)
    private fun gold(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD)
    private fun red(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)
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
