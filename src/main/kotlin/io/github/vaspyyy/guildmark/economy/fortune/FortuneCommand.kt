package io.github.vaspyyy.guildmark.economy.fortune

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.MarketBoards
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for a capital's fortunes (living-world/design-influence.md section 9), run standing in the
 * capital. `/guildmark fortune` shows its times and since when, the score and the times it calls for, the reasons of
 * its last close with their points and days left, what's been noted since, the last 14 days, and the crowds' and the
 * spending's share; `set <times> [days]` puts the times there now, held for so many closes (3 unless you say);
 * `reason <kind> <points> <days>` notes a reason by hand; `step` works the day's move out now; `clear` forgets the
 * capital's record.
 */
object FortuneCommand {
    /** How many closes `fortune set` holds the times through, unless told. */
    private const val HOLD = 3

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("fortune")
                        .executes { show(it) }
                        .then(
                            Commands.literal("set").then(
                                Commands.argument("times", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(Times.entries.map { it.id }, b) }
                                    .executes { set(it, StringArgumentType.getString(it, "times"), HOLD) }
                                    .then(
                                        Commands.argument("days", IntegerArgumentType.integer(0, 30))
                                            .executes { set(it, StringArgumentType.getString(it, "times"), IntegerArgumentType.getInteger(it, "days")) },
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("reason").then(
                                Commands.argument("kind", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(FortuneReasons.KINDS, b) }
                                    .then(
                                        Commands.argument("points", IntegerArgumentType.integer(Fortunes.LOWEST, Fortunes.HIGHEST)).then(
                                            Commands.argument("days", IntegerArgumentType.integer(1, 30)).executes {
                                                reason(
                                                    it, StringArgumentType.getString(it, "kind"),
                                                    IntegerArgumentType.getInteger(it, "points"), IntegerArgumentType.getInteger(it, "days"),
                                                )
                                            },
                                        ),
                                    ),
                            ),
                        )
                        .then(Commands.literal("step").executes { step(it) })
                        .then(Commands.literal("clear").executes { clear(it) }),
                ),
        )
    }

    // ---- The capital you stand in ----

    private class Here(val capital: CapitalPiece, val entry: Markets.Entry) {
        val key: Long get() = entry.market.capital
        val name: String get() = VillageNames.of(capital.boardAnchor)

        /** Its market's day. */
        val today: Long get() = Conditions(entry.market.hour).day
    }

    /** The capital [context]'s player stands in, with its market; null (and why, said) if there isn't one. */
    private fun here(context: CommandContext<CommandSourceStack>): Here? {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val entry = Markets.of(level, capital)
        if (entry == null) {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        return Here(capital, entry)
    }

    /** Why the fortunes aren't being worked out, or null if they are. */
    private fun off(): String? = when {
        Fortunes.enabled() -> null
        !Markets.enabled() -> "The markets are switched off (setting: Capital Markets), so the fortunes are too."
        else -> "The fortunes are off (Part C's READY is false in Fortunes.kt, or the setting Town Fortunes is off): every capital's times read steady, and the record is kept as it was."
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val here = here(context) ?: return 0
        val c = Conditions(here.entry.market.hour)
        say(source, header("The fortunes of ${here.name}: market day ${c.day}, ${clock(c.clock)}"))
        off()?.let { say(source, grey(it)) }
        val town = FortuneData.get(source.level)[here.key]
        if (town == null) {
            say(source, plain("No record yet: the times are steady, and the next close (at two in the morning) starts it."))
            return 1
        }
        val held = if (town.held >= c.day) "; held there through day ${town.held} by fortune set" else ""
        val since = if (town.since >= 0) " since day ${town.since} (moved from ${town.from.id})" else " (never moved)"
        say(source, line().append(label("Times: ")).append(plain(town.times.id + since + held)))
        val called = Fortunes.called(town.score)
        val closed = if (town.closed > FortuneData.NEVER) "the close of day ${town.closed}" else "no close yet"
        say(source, line().append(label("Score: ")).append(plain("${town.score} at $closed, calling for ${called.id}" + if (called != town.times) ", so a step toward it at the next close" else "")))
        say(source, line().append(label("Crowds and spending: ")).append(plain("%.2f and %.2f".format(Fortunes.busy(source.level, here.key), Fortunes.spend(source.level, here.key)))))
        medallion(context, here)?.let { say(source, line().append(label("The board's medallion: ")).append(plain(it))) }
        if (town.reasons.isEmpty()) say(source, grey("Nothing weighed on it at its last close."))
        else {
            say(source, label("Reasons at the last close, biggest first:"))
            for (r in town.reasons) say(source, reason(r, c.day))
        }
        val pending = town.notes.filter { it.from >= town.closed }
        if (pending.isNotEmpty()) {
            say(source, label("Noted since, counted from the next close:"))
            for (n in pending) {
                val about = (if (n.args.isNotEmpty()) " [" + n.args.joinToString(", ") + "]" else "") + if (n.hand) " (by hand)" else ""
                say(source, plain("  %+d %s%s, from day %d through day %d".format(n.points, n.kind, about, n.from, n.until)))
            }
        }
        if (town.days.isNotEmpty()) {
            say(source, line().append(label("Last ${town.days.size} days, oldest first: ")).append(plain(town.days.joinToString("  ") { "${it.day}: %+d %s".format(it.score, it.times.id) })))
        }
        val news = town.works.takeLast(4)
        if (news.isNotEmpty()) {
            say(source, line().append(label("Works news for the crier: ")).append(plain(news.joinToString("; ") { n ->
                val at = Conditions(n.hour)
                "#${n.id} ${n.kind} ${n.what} day ${at.day} ${clock(at.clock)}" + (if (n.patron.isNotEmpty()) " (${n.patron})" else "")
            })))
        }
        return 1
    }

    /**
     * What the medallion at the head of [here]'s market board shows (its state, saved and synced, whether or not its
     * faces are drawn yet), and when it turned; null with the board's chunk not loaded.
     */
    private fun medallion(context: CommandContext<CommandSourceStack>, here: Here): String? {
        val level = context.source.level
        val board = CityPlaces.of(level, here.capital)?.let { MarketBoards.board(level, it) } ?: return null
        val turned = if (board.turned > MarketBoardBlockEntity.LONG_AGO) "turned %.0f s ago in sight of someone".format((level.gameTime - board.turned) / 20.0) else "found turned (nobody saw it go)"
        val pos = board.blockPos
        return "${board.times}, $turned; the board at ${pos.x} ${pos.y} ${pos.z}"
    }

    /** A reason's line: its points, kind, words and how long it has. */
    private fun reason(r: Reason, today: Long): MutableComponent {
        val colour = if (r.points < 0) ChatFormatting.RED else ChatFormatting.GREEN
        return line().append(Component.literal("  %+d ".format(r.points)).withStyle(colour))
            .append(label(r.kind + " "))
            .append(FortuneReasons.text(r).withStyle(ChatFormatting.WHITE))
            .append(grey("  (" + FortuneReasons.lasts(r, today) + (if (r.works >= 0) ", works #${r.works}" else "") + ")"))
    }

    private fun set(context: CommandContext<CommandSourceStack>, id: String, days: Int): Int {
        val source = context.source
        off()?.let { return fail(source, it) }
        val to = Times.entries.firstOrNull { it.id == id } ?: return fail(source, "No times called $id: " + Times.entries.joinToString(", ") { it.id })
        val here = here(context) ?: return 0
        val from = Fortunes.times(source.level, here.key)
        Fortunes.set(source.level, here.key, to, if (days > 0) here.today + days else -1L)
        val held = if (days > 0) "held there through day ${here.today + days} ($days closes)" else "not held: the next close moves them a step toward the score"
        val moved = if (from != to) "The medallion on the market board turns within a few seconds, and the crier cries it at the next noon with a FORTUNE slip." else "They were ${to.id} already."
        return say(source, plain("The times in ${here.name} are ${to.id} now, $held. $moved"))
    }

    private fun reason(context: CommandContext<CommandSourceStack>, kind: String, points: Int, days: Int): Int {
        val source = context.source
        off()?.let { return fail(source, it) }
        if (kind !in FortuneReasons.KINDS) return fail(source, "No reason called $kind: " + FortuneReasons.KINDS.joinToString(", "))
        if (points == 0) return fail(source, "A reason of 0 points weighs nothing.")
        val here = here(context) ?: return 0
        Fortunes.hand(source.level, here.key, kind, points, days)
        return say(source, plain("Noted $kind %+d for $days days in ${here.name}, by hand. It counts from the next close (or now, with /guildmark fortune step).".format(points)))
    }

    private fun step(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        off()?.let { return fail(source, it) }
        val here = here(context) ?: return 0
        val from = Fortunes.times(source.level, here.key)
        val called = Fortunes.step(source.level, here.entry)
        val to = Fortunes.times(source.level, here.key)
        val score = Fortunes.score(source.level, here.key)
        val moved = if (to != from) "the times move from ${from.id} to ${to.id}" else "the times stay ${to.id}"
        say(source, plain("Worked day ${here.today} out now: score $score, calling for ${called.id}; $moved."))
        return show(context)
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        off()?.let { return fail(source, it) }
        val here = here(context) ?: return 0
        Fortunes.clear(source.level, here.key)
        return say(source, plain("Cleared the fortunes of ${here.name}: the times are steady, with no reasons, notes, history or works news; the next close starts it again."))
    }

    // ---- Text ----

    private fun clock(h: Int): String = "%02d:00".format(h)

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
