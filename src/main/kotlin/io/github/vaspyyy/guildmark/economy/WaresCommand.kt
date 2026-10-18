package io.github.vaspyyy.guildmark.economy

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for the market's displays of goods, run standing in a capital. `/guildmark market displays` says
 * how many displays the capital's plan has, how many are out and how many still to be set out, and lists the nearest
 * few with their goods and how full each is; `displays place` sets out every one still missing whose land is loaded,
 * with players about or not (every other rule of [WaresPlacer] holds).
 */
object WaresCommand {
    /** How many of the nearest displays are listed. */
    private const val NEAREST = 6

    /** Has the commands registered with the server's others. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    /** The `displays` commands, under `/guildmark market` (merged with [MarketCommand]'s). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("market").then(
                        Commands.literal("displays")
                            .executes { list(it) }
                            .then(Commands.literal("place").executes { place(it) }),
                    ),
                ),
        )
    }

    /** The capital [context]'s player stands in and its places; null (and why, said) if there aren't any. */
    private fun capital(context: CommandContext<CommandSourceStack>): Pair<CapitalPiece, CityPlaces>? {
        val source = context.source
        val capital = Capitals.covering(source.level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val places = CityPlaces.of(source.level, capital)
        if (places == null) {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        return capital to places
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val (capital, places) = capital(context) ?: return 0
        val spots = WaresSpots.get(level)
        var out = 0
        var due = 0
        var left = 0
        var away = 0
        var awayDue = 0
        val shown = ArrayList<WaresBlockEntity>()
        for (d in places.census.displays) {
            val pos = WaresPlacer.spot(capital, d)
            if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) {
                away++
                if (!spots.has(pos)) awayDue++
                continue
            }
            val display = level.getBlockEntity(pos) as? WaresBlockEntity
            when {
                display != null -> {
                    out++
                    shown.add(display)
                }
                spots.has(pos) || WaresPlacer.look(level, capital, d, pos, quiet = false) == WaresPlacer.Outcome.CHANGED -> left++
                else -> due++
            }
        }
        say(source, header("Displays of goods in ${VillageNames.of(capital.boardAnchor)}"))
        say(source, line().append(label("The plan has ${places.census.displays.size}: ")).append(plain(
            "$out out, $due still to set out, $left left as players have them" +
                if (away > 0) "; $away more where the land isn't loaded ($awayDue of them still to set out)." else ".",
        )))
        val entry = Markets.get(level)[capital.center]
        if (entry == null) say(source, grey("The market hasn't opened yet: displays show the town's usual stock."))
        val here = source.playerOrException.blockPosition()
        for (display in shown.sortedBy { it.blockPos.distSqr(here) }.take(NEAREST)) say(source, row(display, entry))
        say(source, grey(when {
            !WaresRegistry.enabled() -> "Displays are switched off (setting: Market Displays): no more are set out, and those out stand still."
            !Markets.enabled() -> "The markets are switched off (setting: Capital Markets): displays show the town's usual stock."
            else -> "Missing ones are set out out of sight as players come by."
        }))
        if (WaresRegistry.enabled() && due > 0) say(source, line().append(
            Component.literal("[Set them out now]").withStyle {
                it.withColor(ChatFormatting.AQUA)
                    .withClickEvent(ClickEvent.RunCommand("/guildmark market displays place"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Every missing display whose land is loaded, players about or not")))
            },
        ))
        return out
    }

    /** One display: where it is, what it is, and each of its goods' items against a full display and the market's stock against what the town keeps. */
    private fun row(display: WaresBlockEntity, entry: Markets.Entry?): Component {
        val c = line().append(at(display.blockPos)).append(plain(" ")).append(display.blockState.block.name.withStyle(ChatFormatting.WHITE)).append(grey(":"))
        val full = display.full()
        display.wares.goods.forEachIndexed { i, id ->
            val good = Good.byId(id) ?: return@forEachIndexed
            c.append(plain(" ")).append(Component.translatable(good.key).withStyle(ChatFormatting.WHITE))
            c.append(plain(" ${display.counts.getOrElse(i) { 0 }}/${full.getOrElse(i) { 0 }}"))
            if (entry != null) c.append(grey(" (%.0f%%)".format(100 * entry.market.stock(good) / entry.town.target(good))))
        }
        return c
    }

    private fun place(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!WaresRegistry.enabled()) return fail(source, "Displays are switched off (setting: Market Displays).")
        val level: ServerLevel = source.level
        val (capital, places) = capital(context) ?: return 0
        val spots = WaresSpots.get(level)
        val made = ArrayList<BlockPos>()
        var seen = 0
        var left = 0
        var waiting = 0
        for (d in places.census.displays) {
            val pos = WaresPlacer.spot(capital, d)
            // Seen to before: out already, or taken away and never filled again
            if (spots.has(pos)) {
                seen++
                continue
            }
            when (WaresPlacer.visit(level, capital, d, quiet = false)) {
                WaresPlacer.Outcome.PLACED -> made.add(pos)
                WaresPlacer.Outcome.THERE -> seen++
                WaresPlacer.Outcome.CHANGED -> left++
                WaresPlacer.Outcome.WAITING -> waiting++
            }
        }
        say(source, plain(
            "Set out ${made.size} ${if (made.size == 1) "display" else "displays"} in ${VillageNames.of(capital.boardAnchor)}; " +
                "$seen seen to before (out, or taken away), $left left as players have them" +
                if (waiting > 0) ", $waiting waiting (land not loaded, or someone standing there)." else ".",
        ))
        if (made.isNotEmpty()) {
            val c = line().append(label("New: "))
            made.take(8).forEachIndexed { i, pos ->
                if (i > 0) c.append(grey(", "))
                c.append(at(pos))
            }
            if (made.size > 8) c.append(grey(" and ${made.size - 8} more"))
            say(source, c)
        }
        return made.size
    }

    // ---- Text ----

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    /** A position to click to go to. */
    private fun at(pos: BlockPos): MutableComponent = Component.literal("[${pos.x} ${pos.y} ${pos.z}]").withStyle {
        it.withColor(ChatFormatting.AQUA)
            .withClickEvent(ClickEvent.RunCommand("/tp @s ${pos.x} ${pos.y + 1} ${pos.z}"))
            .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to go there")))
    }

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
