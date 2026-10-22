package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.trade.WordCommand.ageText
import io.github.vaspyyy.guildmark.trade.WordCommand.fail
import io.github.vaspyyy.guildmark.trade.WordCommand.find
import io.github.vaspyyy.guildmark.trade.WordCommand.goods
import io.github.vaspyyy.guildmark.trade.WordCommand.grey
import io.github.vaspyyy.guildmark.trade.WordCommand.header
import io.github.vaspyyy.guildmark.trade.WordCommand.label
import io.github.vaspyyy.guildmark.trade.WordCommand.line
import io.github.vaspyyy.guildmark.trade.WordCommand.listed
import io.github.vaspyyy.guildmark.trade.WordCommand.nameOf
import io.github.vaspyyy.guildmark.trade.WordCommand.plain
import io.github.vaspyyy.guildmark.trade.WordCommand.say
import io.github.vaspyyy.guildmark.trade.WordCommand.town
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for the Tally (trader design, Part 1; operators only), under `/guildmark trade`, beside the Word's ([WordCommand]).
 *
 * `sight <town> [hours]` puts a sight of the town in your Tally as it was that many hours ago (now, if the hours are left out): the way to test
 * the Tally's rings and a report without walking. `tally` gives you a Tally and lists what it holds, `tally copy` copies the slate of the board of the
 * capital you stand in into it (what reading a Haul Board does), and `report <town>` files your sight of that town at the board of the capital you
 * stand in, as the Tally's Report button does.
 */
object TallyCommand {
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
                            Commands.literal("sight").then(
                                town()
                                    .executes { sight(it, StringArgumentType.getString(it, "town"), 0.0) }
                                    .then(
                                        Commands.argument("hours", DoubleArgumentType.doubleArg(0.0, 2000.0))
                                            .executes { sight(it, StringArgumentType.getString(it, "town"), DoubleArgumentType.getDouble(it, "hours")) },
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("tally")
                                .executes { tally(it) }
                                .then(Commands.literal("copy").executes { copy(it) }),
                        )
                        .then(Commands.literal("report").then(town().executes { report(it, StringArgumentType.getString(it, "town")) })),
                ),
        )
    }

    // ---- A sight, set ----

    private fun sight(context: CommandContext<CommandSourceStack>, town: String, hours: Double): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val entry = find(source, town) ?: return fail(source, "No open market is called $town: " + listed(source))
        val key = entry.market.capital
        if (entry.market.hour == Long.MIN_VALUE) return fail(source, "That market has not run yet.")
        val now = level.overworldClockTime
        val tick = now - (hours * Word.HOUR).toLong()
        val name = nameOf(level, entry)
        val was = Tally.seen(level, player.uuid, key)
        val seen = if (was != null) {
            Seen(key, name, now, Seen.SET, was.sight.stamped(tick))
        } else {
            Seen(key, name, now, Seen.SET, Sight.of(level, entry, tick, Word.tickets(level, entry.capital)))
        }
        Tally.note(level, player, seen, force = true)
        say(
            source,
            line().append(label("Your Tally: ")).append(plain("$name is now a sight ${ageText(hours)} old")).append(grey(if (was != null) " (the one you had, made older or newer)." else " (taken from its market as it stands, dated back).")),
        )
        return 1
    }

    // ---- The Tally ----

    private fun tally(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        Tally.give(level, player)
        if (!TradeConfig.life()) say(source, grey("Trader's Life is off (setting: Trader's Life or Capital Markets): the Tally will not open until it is on."))
        val seen = Tally.seen(level, player.uuid)
        if (seen.isEmpty()) {
            say(source, grey("Gave you a Tally. It has no sight of any town yet: open it in a capital, or use 'trade sight <town>'."))
            return 1
        }
        say(source, header("Your Tally"))
        val now = level.overworldClockTime
        for (s in seen) say(source, seenLine(s, now))
        return seen.size
    }

    /** One sight of a Tally, in a line. */
    private fun seenLine(seen: Seen, now: Long): MutableComponent {
        val how = when (seen.from) {
            Seen.VISIT -> "stood there"
            Seen.BOARD -> "read from a board"
            Seen.LIVE -> "kept live by a stall or a Store"
            else -> "set by command"
        }
        val sight = seen.sight
        val out = line().append(label(seen.name.ifEmpty { "${BlockPos.of(seen.capital).x}_${BlockPos.of(seen.capital).z}" })).append(plain(" ${ageText(sight.age(now))} old")).append(grey(" ($how)"))
        val short = sight.shorts(3)
        val glut = sight.gluts(3)
        if (short.isNotEmpty()) out.append(grey("; short of ")).append(goods(short, ChatFormatting.RED))
        if (glut.isNotEmpty()) out.append(grey("; glut of ")).append(goods(glut, ChatFormatting.GREEN))
        return out
    }

    private fun copy(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!TradeConfig.word()) return fail(source, "The Word is off (setting: The Word, or Trader's Life or Capital Markets): no board has a slate to copy.")
        val here = Furnish.capitalAt(level, player.blockPosition())?.center ?: return fail(source, "You're not in a capital: stand in one to copy its board.")
        val n = Tally.copyBoard(level, player, here)
        say(source, grey(if (n == 0) "Nothing on this board is newer than what your Tally has." else "Copied $n sights from this board into your Tally."))
        return n
    }

    private fun report(context: CommandContext<CommandSourceStack>, town: String): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val entry = find(source, town) ?: return fail(source, "No open market is called $town: " + listed(source))
        val result = Tally.report(level, player, entry.market.capital)
        if (result == Tally.Reported.FILED) source.sendSuccess({ Component.translatable(result.key).withStyle(ChatFormatting.GREEN) }, false)
        else source.sendFailure(Component.translatable(result.key))
        return if (result == Tally.Reported.FILED) 1 else 0
    }
}
