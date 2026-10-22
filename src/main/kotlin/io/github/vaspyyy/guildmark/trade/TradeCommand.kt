package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.Markets
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The root of the trader's commands, `/guildmark trade` (living-world/trader/design.md section 9; operators only), for the parts of
 * the trader's life to hang their own literals on. The `trade` literal is the same one the market's loose ends already have (herd,
 * freight, owed), with the same permission, so Brigadier merges them into one tree by name: a part adds its own commands from its own
 * file, registering `guildmark` > `trade` > its literal the same way, and none of them edits this one.
 *
 * What the root says itself is `status` (and `/guildmark trade` alone): each system of the trader's life, whether it is working and,
 * if it is not, which setting is off or what it needs, with its numbers, and the sender's own record. It is how the switches are
 * checked before the systems they switch exist.
 */
object TradeCommand {
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
                        .executes { status(it) }
                        .then(Commands.literal("status").executes { status(it) }),
                ),
        )
    }

    // ---- Status ----

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val opened = Markets.get(level).all().count()
        say(source, header("The trader's life"))
        row(source, "Trader's Life", TradeConfig.life(), TradeConfig.setting(GuildmarkConfig.TRADE_LIFE), "Capital Markets",
            "the whole of it: tickets, boards, Word, Hunts, new House trips and what the counter sells")
        row(source, "The Word", TradeConfig.word(), TradeConfig.setting(GuildmarkConfig.TRADE_WORD), "Trader's Life",
            "news is ${num(TradeConfig.wordBase())} hours old, plus ${num(TradeConfig.wordPerBlock())} an hour for every block between towns")
        row(source, "Haul Tickets", TradeConfig.hauls(), TradeConfig.setting(GuildmarkConfig.TRADE_HAULS), "Trader's Life",
            "up to ${num(TradeConfig.haulRate())} a capital a day (${num(TradeConfig.haulRateMax())} as carriers join), ${percent(TradeConfig.haulDeposit())} held at booking; " +
                "stalls pay ${percent(TradeConfig.dealerShare())} of the price for goods carried ${TradeConfig.haulMin()} blocks or more, with room for ${TradeConfig.haulRoomDays()} days of the caravans' load")
        row(source, "Carrying", TradeConfig.carry(), TradeConfig.setting(GuildmarkConfig.TRADE_CARRY), "Trader's Life",
            "a cart drags ${percent(TradeConfig.cartSlow())}, a wagon waits when you are ${TradeConfig.wagonRein()} blocks ahead")
        row(source, "Carrier's Wagon", TradeConfig.wagon(), TradeConfig.setting(GuildmarkConfig.TRADE_WAGON), "Carrying", "")
        row(source, "The Hunt", TradeConfig.hunt(), TradeConfig.setting(GuildmarkConfig.TRADE_HUNT), "Trader's Life and Road Ambushes",
            "chance x${num(TradeConfig.huntChance())}, goods worth ${TradeConfig.huntLaden()} Marks or more, camps see ${TradeConfig.huntReach()} blocks, " +
                "a party ${if (TradeConfig.huntKills()) "may" else "never will"} kill, ${TradeConfig.huntPurse()} Marks for each outlaw downed, guards ${num(TradeConfig.guardWage())} Marks a day")
        val rungs = TradeConfig.rungs()
        row(source, "Trader's Ladder", rungs, rungs, "", if (rungs) "every gate is asked" else "every gate passes")
        row(source, "Carriers' Stores", TradeConfig.stores(), TradeConfig.setting(GuildmarkConfig.TRADE_STORES), "Trader's Life and Town Works", "")
        row(source, "Trading House", TradeConfig.house(), TradeConfig.setting(GuildmarkConfig.TRADE_HOUSE), "Carriers' Stores and Haul Tickets",
            "up to ${TradeConfig.carters(opened)} carters with $opened capitals open, ${TradeConfig.carterWage()} Marks a day each, going on ${TradeConfig.houseOffline()} days without its owner")
        row(source, "The Town Answers", TradeConfig.world(), TradeConfig.setting(GuildmarkConfig.TRADE_WORLD), "Trader's Life", "")
        row(source, "Cheer", TradeConfig.cheer(), TradeConfig.setting(GuildmarkConfig.TRADE_CHEER), "The Town Answers", "")
        source.player?.let { player ->
            val book = TradeBooks.peek(level)?.get(player.uuid)
            if (book == null) {
                say(source, grey("Your book has nothing written in it yet."))
            } else {
                say(
                    source,
                    line().append(label("Your book: "))
                        .append(
                            plain(
                                "${if (book.started) "started" else "not started"}, rung ${book.rung}, ${if (book.warrant) "a Warrant" else "no Warrant"}, ${book.deliveries} deliveries, " +
                                    "${book.held.sumOf { it.units }} hauled units held, Stores at ${book.stores.size} capitals, Houses at ${book.houses.size}",
                            ),
                        ),
                )
            }
        }
        return 1
    }

    /** One system's line: its name and whether it is working; if it is not, the setting that is off or what it needs; then [detail]. */
    private fun row(source: CommandSourceStack, name: String, on: Boolean, setting: Boolean, needs: String, detail: String) {
        val out = line().append(label(name)).append(plain(": "))
        out.append(
            when {
                on -> green("on")
                !setting -> red("off").append(grey(" (setting: $name)"))
                else -> red("off").append(grey(" (needs $needs)"))
            },
        )
        if (detail.isNotEmpty()) out.append(grey(" - $detail"))
        say(source, out)
    }

    /** [x] with no more decimals than it needs ("12", "2.5", "0.024"). */
    private fun num(x: Double): String = if (x == Math.rint(x)) x.toLong().toString() else "%.3f".format(Locale.ROOT, x).trimEnd('0')

    private fun percent(x: Double): String = "${(x * 100).roundToInt()}%"

    // ---- Words ----

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun green(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GREEN)
    private fun red(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }
}
