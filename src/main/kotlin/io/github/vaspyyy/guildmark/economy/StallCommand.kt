package io.github.vaspyyy.guildmark.economy

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.floor

/**
 * Testing commands for players' own stalls, run standing in a capital. `/guildmark capital stall` lists its market
 * hall's tables: whose each is, the rent left, what's on it and in its till, and what the hall keeps for whom;
 * `stall rent [table]` lets you a table for a week for nothing, whatever your standing (the nearest free one if none
 * is named); `stall sell [hours]` works the market's hours ahead with the stalls selling in them, then carries on
 * from the clock's hour (as `market run` does); `stall left <hours>` sets the rent left on your stall, or the nearest
 * let one; and `stall letgo [table]` lets a stall go now, its goods and till to the hall's merchant as when its rent
 * runs out.
 */
object StallCommand {
    /** Has the commands registered with the server's others. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    /** The `stall` commands, under `/guildmark capital` (merged with the capital's other commands). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val table = IntegerArgumentType.integer(1, OwnStalls.TABLES)
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("capital").then(
                        Commands.literal("stall")
                            .executes { list(it) }
                            .then(
                                Commands.literal("rent").executes { rent(it, null) }
                                    .then(Commands.argument("table", table).executes { rent(it, IntegerArgumentType.getInteger(it, "table") - 1) }),
                            )
                            .then(
                                Commands.literal("sell").executes { sell(it, 1) }
                                    .then(Commands.argument("hours", IntegerArgumentType.integer(1, 24 * 14)).executes { sell(it, IntegerArgumentType.getInteger(it, "hours")) }),
                            )
                            .then(
                                Commands.literal("left").then(
                                    Commands.argument("hours", IntegerArgumentType.integer(1, OwnStalls.WEEK * 2)).executes { left(it, IntegerArgumentType.getInteger(it, "hours")) },
                                ),
                            )
                            .then(
                                Commands.literal("letgo").executes { letGo(it, null) }
                                    .then(Commands.argument("table", table).executes { letGo(it, IntegerArgumentType.getInteger(it, "table") - 1) }),
                            ),
                    ),
                ),
        )
    }

    /** The capital [context]'s player stands in, its market and its hall's tables; null (and why, said) if there aren't any. */
    private fun hall(context: CommandContext<CommandSourceStack>): Triple<CapitalPiece, Markets.Entry, List<StallTables.Table>>? {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val entry = Markets.of(level, capital)
        val tables = StallTables.of(level, capital)
        if (entry == null || tables == null) {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        if (tables.isEmpty()) {
            fail(source, "This capital has no market hall.")
            return null
        }
        if (!OwnStalls.enabled()) say(source, grey("Own stalls are switched off (setting: Own Stalls): nothing is let or sold."))
        if (!Markets.enabled()) say(source, grey("The markets are switched off (setting: Capital Markets): the stalls stand still."))
        return Triple(capital, entry, tables)
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val (capital, entry, tables) = hall(context) ?: return 0
        val hall = OwnStalls.get(level).find(capital.center.asLong())
        val player = source.playerOrException
        say(source, header("The market hall of ${VillageNames.of(capital.boardAnchor)}"))
        val rank = SupplierLink.rank(level, player.uuid, capital.center)
        say(source, grey("You: supplier rank $rank (renting takes ${SupplierLink.TRUSTED}), a week's keep ${StallDesk.keep(rank)} Marks."))
        for (t in tables) {
            val s = hall?.stalls?.getOrNull(t.index)
            val head = line().append(label("Table ${t.index + 1} ")).append(at(t))
            if (s == null) {
                say(source, head.append(grey("  free")))
                continue
            }
            say(source, head.append(plain("  ${s.name}, ${s.left} h of rent left" + if (s.warned) " (told it's running out)" else "")))
            val goods = Good.TRADED.filter { s.stock[it.ordinal] > 1e-6 || s.waiting[it.ordinal] > 1e-6 }
            say(source, grey("   on it: " + goods.joinToString(", ") { g ->
                "${g.id} %.2f".format(s.stock[g.ordinal]) + (if (s.waiting[g.ordinal] > 1e-6) " (+%.1f to fetch)".format(s.waiting[g.ordinal]) else "") +
                    " at %.3f".format(entry.market.price(entry.town, g) * (1.0 - OwnStalls.CUT))
            }.ifEmpty { "nothing" }))
            val sold = Good.TRADED.filter { s.today[it.ordinal] > 1e-6 }
            say(source, grey("   till %.2f Marks; sold since the day began: ".format(s.till) + sold.joinToString(", ") { "${it.id} %.1f".format(s.today[it.ordinal]) }.ifEmpty { "nothing" }))
        }
        for ((id, k) in hall?.kept ?: mapOf()) {
            val goods = Good.entries.filter { k.goods[it.ordinal] > 0 }.joinToString(", ") { "${it.id} ${k.goods[it.ordinal]}" }.ifEmpty { "no goods" }
            say(source, line().append(label("Kept for ${k.name.ifEmpty { id.toString() }}: ")).append(plain("$goods, %.2f Marks".format(k.marks) + if (k.told) "" else " (not told yet)")))
        }
        return 1
    }

    /** Lets [index] (the nearest free table if null) to the player for a week, for nothing. */
    private fun rent(context: CommandContext<CommandSourceStack>, index: Int?): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val (capital, _, tables) = hall(context) ?: return 0
        val data = OwnStalls.get(level)
        val hall = data.hall(capital.center.asLong())
        if (hall.of(player.uuid) >= 0) return fail(source, "You rent table ${hall.of(player.uuid) + 1} here already: let it go first.")
        val t = if (index != null) tables.getOrNull(index) ?: return fail(source, "This hall has only ${tables.size} tables.")
            else tables.filter { hall.stalls[it.index] == null }.minByOrNull { it.middle.distanceToSqr(player.position()) } ?: return fail(source, "Every table here is let.")
        if (hall.stalls[t.index] != null) return fail(source, "Table ${t.index + 1} is ${hall.stalls[t.index]!!.name}'s.")
        hall.stalls[t.index] = OwnStall(player.uuid, player.name.string, StallDesk.colorOf(level, player.uuid), VillageNames.of(capital.boardAnchor), t.display, t.front, OwnStalls.WEEK)
        data.changed()
        if (level.isLoaded(t.display)) StallTables.sync(level, t, hall.stalls[t.index])
        return say(source, plain("Table ${t.index + 1} is yours for a week. Use it to set your goods out."))
    }

    /** Works [hours] of the market ahead with its stalls selling, then winds its clock back to the hour it is. */
    private fun sell(context: CommandContext<CommandSourceStack>, hours: Int): Int {
        val source = context.source
        val level = source.level
        val (capital, entry, _) = hall(context) ?: return 0
        val hall = OwnStalls.get(level).find(capital.center.asLong())
        val before = hall?.stalls?.map { it?.till ?: 0.0 } ?: listOf()
        val m = entry.market
        val from = m.hour
        for (h in 1..hours) Markets.step(level, entry, from + h)
        Markets.rewind(m, hours.toLong())
        Markets.get(level).changed()
        OwnStalls.get(level).changed()
        say(source, plain("Worked out $hours hours of the market with its stalls; it carries on from the clock's hour."))
        hall?.stalls?.forEachIndexed { i, s ->
            if (s == null) return@forEachIndexed
            val sold = Good.TRADED.filter { s.sold[it.ordinal] > 1e-6 }.joinToString(", ") { "${it.id} %.2f".format(s.sold[it.ordinal]) }.ifEmpty { "nothing" }
            say(source, grey("Table ${i + 1} (${s.name}): took %.2f Marks; in the last hour sold $sold.".format(s.till - before.getOrElse(i) { 0.0 })))
        }
        return hours
    }

    /** Sets the rent left on the player's stall (the nearest let one if they have none) to [hours]. */
    private fun left(context: CommandContext<CommandSourceStack>, hours: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val (capital, _, tables) = hall(context) ?: return 0
        val hall = OwnStalls.get(level).find(capital.center.asLong()) ?: return fail(source, "No table here is let.")
        val i = hall.of(player.uuid).takeIf { it >= 0 }
            ?: tables.filter { hall.stalls[it.index] != null }.minByOrNull { it.middle.distanceToSqr(player.position()) }?.index ?: return fail(source, "No table here is let.")
        val s = hall.stalls[i]!!
        s.left = hours
        s.warned = false
        OwnStalls.get(level).changed()
        return say(source, plain("Table ${i + 1} (${s.name}) has $hours h of rent left."))
    }

    /** Lets [index] go now (the player's own stall if null, else the nearest let one). */
    private fun letGo(context: CommandContext<CommandSourceStack>, index: Int?): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val (capital, _, tables) = hall(context) ?: return 0
        val data = OwnStalls.get(level)
        val hall = data.find(capital.center.asLong()) ?: return fail(source, "No table here is let.")
        val i = index ?: hall.of(player.uuid).takeIf { it >= 0 }
            ?: tables.filter { hall.stalls[it.index] != null }.minByOrNull { it.middle.distanceToSqr(player.position()) }?.index ?: return fail(source, "No table here is let.")
        val s = hall.stalls.getOrNull(i) ?: return fail(source, "Table ${i + 1} isn't let.")
        val goods = Good.entries.sumOf { s.whole(it) }
        val marks = floor(s.till + 1e-6).toInt()
        OwnStalls.letGo(hall, i)
        data.changed()
        tables.getOrNull(i)?.let { if (level.isLoaded(it.display)) StallTables.sync(level, it, null) }
        return say(source, plain("Let table ${i + 1} (${s.name}) go: the hall's merchant keeps $goods goods and $marks Marks for them."))
    }

    // ---- Text ----

    private fun at(t: StallTables.Table): MutableComponent = grey("[${t.display.x} ${t.display.y} ${t.display.z}]")

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
