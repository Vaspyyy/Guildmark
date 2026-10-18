package io.github.vaspyyy.guildmark.townsfolk.streets

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.lamps.LampTown
import io.github.vaspyyy.guildmark.lamps.StreetLamps
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.ShutStall
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * `/guildmark streets` (operators; living-world/design-influence.md section 5, M6 Part D): every sign up in the capital
 * you stand in, and why, with its cause from the records: the dressing, the lamps tonight, the shut stalls' flaps and
 * slates, the "To let" boards and the alms table. And a way to see each of them now, without first making the times
 * for it (each lasts this session only, and runs whatever the M6 switches say):
 *
 * - `dress good|thriving|down|clear`: the dressing up as in good times (or thriving: the lanterns too), taken down, or
 *   cleared away at once;
 * - `lamps`: tonight's lamps rationed, and the round nearest you started now;
 * - `slate`: the stall nearest you shut for two minutes (its keeper lets the flap down, the slate goes up);
 * - `board`: a "To let" board for the home nearest you for two minutes;
 * - `alms` / `alms clear`: the alms table set up now by the market's well nearest you for three minutes, or cleared.
 */
object StreetsCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("streets")
                        .executes { show(it) }
                        .then(
                            Commands.literal("dress")
                                .then(Commands.literal("good").executes { dress(it, StreetsData.FORCE_GOOD) })
                                .then(Commands.literal("thriving").executes { dress(it, StreetsData.FORCE_THRIVING) })
                                .then(Commands.literal("down").executes { dress(it, StreetsData.FORCE_DOWN) })
                                .then(Commands.literal("clear").executes { clearDress(it) }),
                        )
                        .then(Commands.literal("lamps").executes { lamps(it) })
                        .then(Commands.literal("slate").executes { slate(it) })
                        .then(Commands.literal("board").executes { board(it) })
                        .then(
                            Commands.literal("alms")
                                .executes { alms(it) }
                                .then(Commands.literal("clear").executes { clearAlms(it) }),
                        ),
                ),
        )
    }

    /** The capital you stand in, with its places; null (and said why) if none. */
    private fun here(context: CommandContext<CommandSourceStack>): Scenes.Here? {
        val source = context.source
        val player = source.player ?: run {
            fail(source, "Only a player can ask that, standing in a capital.")
            return null
        }
        val h = Scenes.here(source.level, player)
        if (h == null) fail(source, "You're not in a capital (or its places are still being worked out: try again in a second).")
        return h
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val h = here(context) ?: return 0
        val column = Streets.column(h.capital)
        say(source, header("The streets of the capital at ${h.capital.center.toShortString()}"))
        val switches = ArrayList<String>()
        if (!Fortunes.enabled()) switches += "the times are off (Town Fortunes, or M6 not switched on): no dressing, slates, rationing or alms table but the tests'"
        if (!Comings.enabled()) switches += "comings and goings are off: no \"To let\" boards but the tests'"
        if (Streets.testing) switches += "the streets command's tests are on this session"
        for (s in switches) say(source, grey(s))
        say(source, line().append(label("Dressing: ")).append(plain(Dressing.status(level, column))))
        say(source, line().append(label("Lamps tonight: ")).append(plain(Rationing.why(level, h.capital))))
        val stalls = h.places.stalls.mapNotNull { Awnings.status(level, column, it) }
        say(source, line().append(label("Shut stalls: ")).append(plain(if (stalls.isEmpty()) "none" else "${stalls.size}")))
        for (s in stalls) say(source, grey("  $s"))
        val boards = Signs.status(level, column, h)
        say(source, line().append(label("\"To let\" boards: ")).append(plain(if (boards.isEmpty()) "none" else "${boards.size}")))
        for (b in boards) say(source, grey("  $b"))
        say(source, line().append(label("Alms table: ")).append(plain(AlmsTable.status(level, column, h) ?: "none today")))
        return 1
    }

    private fun dress(context: CommandContext<CommandSourceStack>, how: Int): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val run = Dressing.force(source.level, Streets.column(h.capital), how)
        val what = when (how) {
            StreetsData.FORCE_GOOD -> "The streets dress as in good times: residents fetch their ladders and go round in a second or two (by day; at night it waits for morning unless you're testing, which you are, so now)."
            StreetsData.FORCE_THRIVING -> "The streets dress as in thriving times, the lantern strings over the square too, lit as the lamps are lit."
            else -> "The dressing comes down: residents go round and take it down; out of sight it simply goes."
        }
        return say(source, line().append(plain(what)).append(grey(" (Dressing #$run; for this session only.)")))
    }

    private fun clearDress(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val n = Dressing.clear(source.level, Streets.column(h.capital), h.capital.center, RECORDS)
        return say(source, plain("Cleared the dressing at once: $n pieces put back as they were."))
    }

    private fun lamps(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val h = here(context) ?: return 0
        if (!StreetLamps.enabled()) return fail(source, "The street lamps are switched off (setting: Street Lamps): switch them on first.")
        val town = StreetLamps.town(level, h.capital) ?: return fail(source, "Its lamps aren't kept just now (or couldn't be worked out).")
        Rationing.force(level, h.capital, RATIONED)
        town.rationed = true
        val near = town.nearest(source.playerOrException.blockPosition()) ?: return fail(source, "It has no lamps.")
        val run = town.runs[town.runOf[near]]
        if (run.kind != LampTown.Kind.CITY) {
            say(source, grey("The nearest lamps are a farm's, which aren't rationed: go nearer the streets for the city's."))
        }
        val lighting = StreetLamps.startNow(level, town, run)
        if (!lighting) return say(source, plain("Rationed tonight (for the next ten minutes). Most of the nearest round's lamps are lit already, so it's put out now: put them out (/guildmark capital lamps out) and run this again to see it lit by halves."))
        return say(
            source, line().append(plain("Rationed tonight: the lamplighter lights every other lamp of the nearest round, saying why; the others catch by themselves a little after."))
                .append(grey(" (For the next ten minutes, this session only.)")),
        )
    }

    private fun slate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        Streets.testing = true
        if (!ShutStall.play(source.level, source.playerOrException, h)) return fail(source, "No stall within 48 blocks with anyone to keep it.")
        return say(source, plain("The nearest stall has nothing to sell for two minutes: its keeper lets the awning's flap down, tidies the counter (the slate goes up on the display), sits by it, and puts it all back after."))
    }

    private fun board(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val home = Signs.force(source.level, h, source.playerOrException.blockPosition(), BOARD)
        if (home < 0) return fail(source, "No front door within 24 blocks.")
        return say(source, plain("A \"To let\" board for the home nearest you (#$home) for two minutes: someone brings it, knocks it in and says the household's gone; after, someone takes it up again."))
    }

    private fun alms(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        AlmsTable.force(source.level, h, source.playerOrException.blockPosition(), ALMS)?.let { return fail(source, "No table: $it.") }
        return say(source, plain("The alms table goes up now by the market's well nearest you, for three minutes: a noble (or the innkeeper) carries it out, sets it up and ladles to the hungry. Give food at it to top the pot up."))
    }

    private fun clearAlms(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val was = AlmsTable.clear(source.level, h)
        return say(source, plain(if (was) "Cleared the alms table away at once." else "There was no table standing."))
    }

    /** How far round a capital's heart the dressing's records are looked for to clear them. */
    private const val RECORDS = 256

    /** How long each test lasts: rationed lamps, a board, a table. */
    private const val RATIONED = 12000L
    private const val BOARD = 2400L
    private const val ALMS = 3600L

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, s: String): Int {
        source.sendFailure(Component.literal(s))
        return 0
    }
}
