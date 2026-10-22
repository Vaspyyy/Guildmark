package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Suppliers
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * Testing commands for the Word (trader design, Part 1; operators only), under `/guildmark trade`.
 *
 * `word [town]` is the slate of the board at a capital (the capital you stand in, or the town named): a row for each neighbour within 1,500
 * blocks with how old its news is, what it is short of and has too much of, its road and its tickets, and how full the capital's own ring of
 * snapshots is. `word age <hours>` makes every row read that old whatever the distance is (`word age off` ends it); `word snap` snapshots
 * every open capital now; `word seed` fills every ring from the markets as they stand, so a board has a row at any delay; `word clear` empties
 * the rings. The Tally's own commands are [TallyCommand]'s. A town is named as its capital is (see [nameOf]; where its ground isn't loaded,
 * by where it stands, "x_z").
 */
object WordCommand {
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
                            Commands.literal("word")
                                .executes { word(it, null) }
                                .then(
                                    Commands.literal("age")
                                        .executes { age(it, null) }
                                        .then(Commands.literal("off").executes { age(it, -1.0) })
                                        .then(Commands.argument("hours", DoubleArgumentType.doubleArg(0.0, 2000.0)).executes { age(it, DoubleArgumentType.getDouble(it, "hours")) }),
                                )
                                .then(Commands.literal("snap").executes { snap(it) })
                                .then(Commands.literal("seed").executes { seed(it) })
                                .then(Commands.literal("clear").executes { clear(it) })
                                .then(town().executes { word(it, StringArgumentType.getString(it, "town")) }),
                        ),
                ),
        )
    }

    internal fun town(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("town", StringArgumentType.word()).suggests { c, b -> SharedSuggestionProvider.suggest(towns(c.source), b) }

    // ---- The town asked about ----

    /** What the commands call [entry]'s capital: its name as the Word has it, or its orders or ground keep it, or where it stands if none is known now. */
    fun nameOf(level: ServerLevel, entry: Markets.Entry): String =
        Word.nameOf(level, entry.market.capital).ifEmpty { Suppliers.place(level, entry.capital) }.ifEmpty { where(entry) }

    private fun where(entry: Markets.Entry): String = "${entry.capital.x}_${entry.capital.z}"

    /** The names of every open market, for the suggestions and for saying what there is to choose from. */
    fun towns(source: CommandSourceStack): List<String> {
        val level = source.level
        return Markets.get(level).all().map { nameOf(level, it) }.sorted()
    }

    internal fun listed(source: CommandSourceStack): String {
        val names = towns(source)
        if (names.isEmpty()) return "no market is open yet (a capital's opens when a player first walks in)."
        return names.take(NAMES).joinToString(", ") + if (names.size > NAMES) ", and ${names.size - NAMES} more." else "."
    }

    /** The open market called [name] (or at "x_z"); the nearest to [source] if two capitals share a name. */
    fun find(source: CommandSourceStack, name: String): Markets.Entry? {
        val level = source.level
        val at = source.position
        return Markets.get(level).all()
            .filter { nameOf(level, it).equals(name, ignoreCase = true) || where(it) == name }
            .minByOrNull { square(it.capital.x - at.x) + square(it.capital.z - at.z) }
    }

    private fun square(x: Double): Double = x * x

    /** The open market named [name], or if none is named the capital the sender stands in; null (and why, said) if there isn't one. */
    fun entry(source: CommandSourceStack, name: String?): Markets.Entry? {
        val level = source.level
        if (name != null) {
            return find(source, name) ?: run {
                fail(source, "No open market is called $name: " + listed(source))
                null
            }
        }
        val player = source.player ?: run {
            fail(source, "Name a town: " + listed(source))
            return null
        }
        val capital = Capitals.covering(level, player.blockPosition()) ?: run {
            fail(source, "You're not in a capital: stand in one, or name a town: " + listed(source))
            return null
        }
        return Markets.find(level, capital.center.asLong()) ?: run {
            fail(source, "Its market has not opened yet: it does when a player first walks in.")
            null
        }
    }

    // ---- The slate ----

    private fun word(context: CommandContext<CommandSourceStack>, town: String?): Int {
        val source = context.source
        val level = source.level
        val entry = entry(source, town) ?: return 0
        val name = nameOf(level, entry)
        if (!TradeConfig.word()) {
            say(source, grey("The Word is off (setting: The Word, or Trader's Life or Capital Markets): no snapshot is taken and no board has a row."))
        }
        say(source, header("The board at $name"))
        val fixed = Word.fixed
        say(
            source,
            grey(
                if (fixed != null) "Every row reads ${ageText(fixed)} old, as set by 'word age'."
                else "News is ${num(TradeConfig.wordBase())} hours old, and ${num(TradeConfig.wordPerBlock())} more for every block between the towns.",
            ),
        )
        val ring = TradeWord.peek(level)?.rings?.get(entry.market.capital)
        val now = level.overworldClockTime
        if (ring == null || ring.sights.isEmpty()) {
            say(source, grey("$name has no snapshot of its own yet."))
        } else {
            say(
                source,
                grey("$name's own ring: ${ring.sights.size} of ${Word.KEPT} snapshots, the newest ${ageText(ring.sights.last().age(now))} old, the oldest ${ageText(ring.sights.first().age(now))}."),
            )
        }
        val rows = Word.view(level, entry.capital, source.player)
        if (rows.isEmpty()) {
            say(source, grey("No row: there is no other capital with a snapshot within ${Word.REACH.toInt()} blocks."))
            return 1
        }
        for (row in rows) say(source, rowLine(row))
        return rows.size
    }

    /** One row of a slate, in a line. */
    fun rowLine(row: Word.Row): MutableComponent {
        val out = line().append(label(row.name.ifEmpty { "${row.capital.x}_${row.capital.z}" })).append(grey(" ${row.distance} blocks, ")).append(plain("${ageText(row.age)} old"))
        if (row.seeded) out.append(grey(" (seeded)"))
        row.by?.let { out.append(grey(", brought by $it")) }
        val short = row.sight.shorts(3)
        val glut = row.sight.gluts(3)
        if (short.isNotEmpty()) out.append(grey("; short of ")).append(goods(short, ChatFormatting.RED))
        if (glut.isNotEmpty()) out.append(grey("; glut of ")).append(goods(glut, ChatFormatting.GREEN))
        row.sight.road?.let { out.append(grey("; ")).append(Component.literal(RoadPeril.Stage.entries[it.stage].id).withStyle(STAGES[it.stage])).append(grey(" road (${it.side})")) }
        if (row.sight.tickets.isNotEmpty()) out.append(grey("; ${row.sight.tickets.size} tickets out"))
        return out
    }

    internal fun goods(list: List<Good>, colour: ChatFormatting): MutableComponent {
        val out = Component.empty()
        for ((i, g) in list.withIndex()) {
            if (i > 0) out.append(grey(", "))
            out.append(Component.translatable(g.key).withStyle(colour))
        }
        return out
    }

    private val STAGES = listOf(ChatFormatting.GREEN, ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.DARK_GRAY)

    // ---- Making the Word read as a test wants ----

    private fun age(context: CommandContext<CommandSourceStack>, hours: Double?): Int {
        val source = context.source
        if (hours == null) {
            val fixed = Word.fixed
            say(source, if (fixed == null) grey("Rows read as old as the distance makes them: 'word age <hours>' fixes it.") else grey("Every row reads ${ageText(fixed)} old: 'word age off' ends it."))
            return 1
        }
        if (hours < 0.0) {
            Word.fixed = null
            say(source, grey("Rows read as old as the distance makes them again."))
        } else {
            Word.fixed = hours
            say(source, grey("Every row now reads ${ageText(hours)} old, whatever the distance (until 'word age off', or the server stops)."))
        }
        return 1
    }

    private fun snap(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!TradeConfig.word()) return fail(source, "The Word is off (setting: The Word, or Trader's Life or Capital Markets): nothing is snapshotted.")
        val n = Word.snapAll(source.level)
        say(source, grey(if (n == 0) "No market is open to snapshot." else "Snapshotted $n capitals as they stand."))
        return n
    }

    private fun seed(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!TradeConfig.word()) return fail(source, "The Word is off (setting: The Word, or Trader's Life or Capital Markets): nothing is seeded.")
        val n = Word.seed(source.level)
        say(source, grey(if (n == 0) "No market is open to seed from." else "Filled the rings of $n capitals from the markets as they stand: ${Word.KEPT} snapshots each, a quarter of a day apart."))
        return n
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val n = Word.clear(source.level)
        say(source, grey("Dropped $n snapshots and every report; the next hour's run takes new ones."))
        return n
    }

    // ---- Words ----

    private const val NAMES = 12

    /** [hours] as the Tally's pages write them: "35 min", "5 h", "1 d 12 h". */
    fun ageText(hours: Double): String {
        val whole = Math.round(hours * 4.0) / 4.0
        if (whole < 1.0) return "${Math.round(whole * 60.0)} min"
        val h = Math.round(whole).toInt()
        return if (h < 24) "$h h" else if (h % 24 == 0) "${h / 24} d" else "${h / 24} d ${h % 24} h"
    }

    private fun num(x: Double): String = if (x == Math.rint(x)) x.toLong().toString() else "%.3f".format(Locale.ROOT, x).trimEnd('0')

    private fun line(): MutableComponent = Component.empty()
    internal fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    internal fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    internal fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    internal fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    internal fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
