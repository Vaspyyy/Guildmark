package io.github.vaspyyy.guildmark.trade.carry

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.trade.Carriers
import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * The commands of carrying (living-world/trader/design.md section 9; operators only), hung on the `trade` literal that
 * [io.github.vaspyyy.guildmark.trade.TradeCommand] has, with the same permission, so Brigadier merges them by name and neither file
 * edits the other: `trade laden [marks|off]` here, and the pack animal's `trade pack ...` and `trade carry ...` beside it.
 */
object CarryCommand {
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
                            Commands.literal("laden")
                                .executes { laden(it) }
                                .then(Commands.literal("off").executes { unpin(it) })
                                .then(
                                    Commands.argument("marks", IntegerArgumentType.integer(0, Laden.PIN_MOST))
                                        .executes { pin(it, IntegerArgumentType.getInteger(it, "marks")) },
                                ),
                        ),
                ),
        )
    }

    // ---- trade laden ----

    /** What the sender's load is worth now, and what shows of it. */
    private fun laden(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        say(source, header("Laden"))
        report(source, player)
        return 1
    }

    /** Pins the sender's load at [marks] for a minute. */
    private fun pin(context: CommandContext<CommandSourceStack>, marks: Int): Int {
        val source = context.source
        val player = source.playerOrException
        Laden.pin(player, marks)
        say(source, line().append(label("Pinned: ")).append(plain("your load is $marks Marks for a minute (${Laden.PIN_TICKS / 20} seconds); ")).append(grey("`trade laden off` lets go.")))
        report(source, player)
        return 1
    }

    private fun unpin(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        Laden.unpin(player)
        say(source, line().append(label("Let go: ")).append(plain("your load is what you carry again.")))
        report(source, player)
        return 1
    }

    /** The lines both say: the number, its level and what that looks like, the switches that decide whether it is drawn, and the pack's share against the carriers'. */
    private fun report(source: CommandSourceStack, player: net.minecraft.server.level.ServerPlayer) {
        val marks = Laden.of(player)
        val level = Laden.level(marks)
        val pinned = Laden.pinned(player)
        say(
            source,
            line().append(label("Load: ")).append(plain("$marks Marks, level $level (${LOOKS[level]})"))
                .append(grey(if (pinned != null) " - pinned" else " - counted from what you carry")),
        )
        say(source, grey("The levels begin at ${Laden.SHOWN}, ${Laden.FRAME} and ${Laden.TALL} Marks."))
        if (!TradeConfig.carry()) say(source, line().append(red("Carrying is off")).append(grey(" (setting: Carrying, or Trader's Life): no pack is drawn; the Hunt still reads the number.")))
        if (!Laden.started(player)) say(source, grey("You have not taken up the trade (no Tally yet), so a count of your pack is 0; a pinned value shows all the same."))
        val parts = Carriers.holdings(player, Carriers.REACH).mapIndexed { i, container ->
            val worth = Laden.worth(container)
            (if (i == 0) "pack" else "carrier $i") + " " + "%.1f".format(Locale.ROOT, worth)
        }
        say(source, line().append(label("At base prices: ")).append(plain(parts.joinToString(", "))))
    }

    // ---- Words ----

    private val LOOKS = arrayOf("no pack", "a rolled blanket and a sack", "a frame with two sacks and a lantern", "a tall frame with sacks, a barrel and pots")

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun red(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component) {
        source.sendSuccess({ c }, false)
    }
}
