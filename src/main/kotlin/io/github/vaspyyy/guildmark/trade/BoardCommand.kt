package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.block.HaulBoardBlockEntity
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.trade.WordCommand.fail
import io.github.vaspyyy.guildmark.trade.WordCommand.grey
import io.github.vaspyyy.guildmark.trade.WordCommand.header
import io.github.vaspyyy.guildmark.trade.WordCommand.label
import io.github.vaspyyy.guildmark.trade.WordCommand.plain
import io.github.vaspyyy.guildmark.trade.WordCommand.say
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for the Haul Board (trader design, 12.6; operators only), under `/guildmark trade board`, for the capital you stand in.
 *
 * `board` says how the board stands: where it is, what is written on it against what the Word has, whether the round is due, and whether the
 * hall's merchant is at it. `board put` puts one up beside the market board now, whoever is looking (one is put up by itself where nobody is).
 * `board chalk` wipes the board clean, its tickets peeling off and its slate rubbed out, and sends for the hall's merchant to write it all up again
 * as at the round, which is the way to watch it done; if no merchant comes, the board writes itself up after two minutes.
 */
object BoardCommand {
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
                            Commands.literal("board")
                                .executes { status(it) }
                                .then(Commands.literal("put").executes { put(it) })
                                .then(Commands.literal("chalk").executes { chalk(it) }),
                        ),
                ),
        )
    }

    /** The capital the operator stands in, or null (and they are told) if none. */
    private fun here(context: CommandContext<CommandSourceStack>): CapitalPiece? {
        val source = context.source
        val capital = Furnish.capitalAt(source.level, BlockPos.containing(source.position))
        if (capital == null) fail(source, "Stand in a capital: the Haul Board is beside its market board.")
        return capital
    }

    private fun on(value: Boolean): String = if (value) "on" else "off"

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val capital = here(context) ?: return 0
        say(source, header("Haul Board"))
        say(source, grey("Trader's Life ${on(TradeConfig.life())}, the Word ${on(TradeConfig.word())}, haul tickets ${on(TradeConfig.hauls())}"))
        val found = HaulBoards.boardOf(level, capital.center)
        if (found == null) {
            say(source, plain("No Haul Board is loaded in this capital. ").append(label("trade board put")).append(plain(" puts one up beside the market board.")))
            return 1
        }
        val (anchor, board) = found
        val key = HaulBoards.round(level)
        val want = HaulBoards.rowsFor(level, capital.center)
        val todo = HaulBoards.missing(board.rows, want)
        say(source, label("Stands at ").append(plain("${anchor.x} ${anchor.y} ${anchor.z}")))
        say(source, label("Slate: ").append(plain("${board.rows.size} rows written, ${want.size} in the Word, ${todo.size} to chalk")))
        say(source, label("Rail: ").append(plain("${board.tickets.size} of ${HaulBoardBlockEntity.HOOKS} hooks, ${HaulBoards.fresh(level, board, capital.center).size} tickets waiting to be hung")))
        val which = if (HaulBoards.morning(key)) "half past seven's" else "half past twelve's"
        say(source, label("Round: ").append(plain("$which ${if (board.round == key) "written" else "due"}${if (board.finished) "" else ", half written"}")))
        val clerk = if (HaulClerk.busy(level, anchor)) "at the board" else if (HaulClerk.merchants(level, anchor, capital.center).isEmpty()) "not near" else "at their work"
        say(source, label("The hall's merchant: ").append(plain("$clerk; the satchel ${if (board.satchel) "on the shelf" else "away"}")))
        return 1
    }

    private fun put(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        if (!TradeConfig.life()) return fail(source, "Trader's Life is off.")
        val capital = here(context) ?: return 0
        val places = CityPlaces.of(level, capital) ?: return fail(source, "This capital's places are not worked out yet: come back in a moment.")
        if (HaulBoards.boardOf(level, capital.center) != null) return say(source, plain("This capital has its Haul Board already."))
        if (!HaulBoards.put(level, places, seen = true)) return fail(source, "There is no free room beside the market board: three by two, on firm ground, with its face clear.")
        val (anchor, _) = HaulBoards.boardOf(level, capital.center) ?: return say(source, plain("A Haul Board stands beside the market board."))
        return say(source, plain("A Haul Board is put up at ${anchor.x} ${anchor.y} ${anchor.z}."))
    }

    private fun chalk(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        if (!TradeConfig.life()) return fail(source, "Trader's Life is off.")
        val capital = here(context) ?: return 0
        val (anchor, board) = HaulBoards.boardOf(level, capital.center) ?: return fail(source, "No Haul Board is loaded in this capital: `trade board put` puts one up.")
        HaulBoards.wipe(level, anchor, board)
        val sent = HaulClerk.busy(level, anchor)
        return say(source, plain(if (sent) "Wiped clean: the hall's merchant is on their way to chalk it up." else "Wiped clean. No merchant of the hall is free, so the board writes itself up in two minutes if you stay, or at once if you go."))
    }
}
