package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for a capital's comings and goings (living-world/design-influence.md section 9), run standing in
 * the capital. `/guildmark comings` says what's due in (who, why, and when), who's away and why, who came lately and
 * what brought them, what's playing now, and why nothing came of something that might have; `arrive [n]` brings one
 * household in now (or up to four, one after another), those due first, else one come to grow the town; `leave` sends
 * the household nearest you that may go (whatever the town); `return` brings back the household away longest. They play
 * one now whatever the times say, the bodies coming in at a gate if you're within 96 blocks of one.
 */
object ComingsCommand {
    /** Ticks between the households `comings arrive` brings, so they don't all come through the gate at once. */
    private const val STAGGER = 300

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("comings")
                        .executes { show(it) }
                        .then(
                            Commands.literal("arrive")
                                .executes { arrive(it, 1) }
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 4)).executes { arrive(it, IntegerArgumentType.getInteger(it, "n")) }),
                        )
                        .then(Commands.literal("leave").executes { leave(it) })
                        .then(Commands.literal("return").executes { comeBack(it) }),
                ),
        )
    }

    // ---- The capital you stand in ----

    private class Here(val capital: CapitalPiece, val places: CityPlaces, val entry: Markets.Entry) {
        val key: Long get() = entry.market.capital
        val name: String get() = VillageNames.of(capital.boardAnchor)
    }

    /** The capital [context]'s player stands in, its places and its market; null (and why, said) if there isn't one. */
    private fun here(context: CommandContext<CommandSourceStack>): Here? {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val places = CityPlaces.of(level, capital)
        val entry = Markets.of(level, capital)
        if (places == null || entry == null) {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        return Here(capital, places, entry)
    }

    /** Why households don't come and go, or null if they do. */
    private fun off(): String? = when {
        Comings.enabled() -> null
        !Comings.ready() -> "Comings and goings aren't switched on yet (M6 isn't wired in): nobody comes or goes."
        !Days.enabled() -> "The Living World is switched off (setting: Living Capitals), so nobody comes or goes."
        else -> "Comings and goings are off (setting: Comings and Goings): nobody comes or goes, and any household away is home again."
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = here(context) ?: return 0
        val c = Conditions(if (Markets.enabled()) here.entry.market.hour else Markets.hourOf(level))
        val today = Comings.today(level, here.key) ?: c.day
        say(source, header("Comings and goings of ${here.name}: market day $today, %02d:00".format(c.clock)))
        off()?.let { say(source, grey(it)) }
        if (today > c.day) say(source, grey("The fortunes have closed the days to day $today (a market run on ahead, or the clock set back): the comings go by them, and wait for the clock (day ${c.day}) to come round."))
        val ctx = Comings.ctx(level, here.places, begin = false)
        val town = ctx.town
        val times = Fortunes.times(level, here.key)
        val run = Comings.hardRun(level, here.key, today)
        val planned = ctx.planned
        val living = Comings.living(ctx)
        val most = Math.floor(Comings.GROWTH * planned).toInt()
        say(source, line().append(label("Times: ")).append(plain(times.id + if (run > 0) ", $run hard days running" else "")))
        say(source, line().append(label("People: ")).append(plain("$living of the $planned planned")).append(
            grey(" (good times grow it to $most at most; nobody leaves at ${Math.floor(Comings.FLOOR * planned).toInt()} or under)"),
        ))
        if (town.closed == ComingsData.NEVER) say(source, grey("No day worked out yet: the record begins with the next look, and its first day after that."))
        else say(source, line().append(label("Worked out to: ")).append(plain("day ${town.closed}")))

        // Who's due
        val queue = Comings.queue(town, times)
        if (queue.isEmpty()) say(source, grey("Nobody due in."))
        else {
            say(source, label("Due in, in the order they come (one a day at most, at the hour by each):"))
            for (due in queue) {
                val day = Comings.dueDay(due, times)
                val home = ctx.places.lodgings[due.home]?.let { " to ${it.kind.lowercase()} #${it.id}" } ?: " to an empty house as they come"
                val dead = if (due.dead.isNotEmpty()) " in the place of ${Comings.names(ctx, due.dead)}" else ""
                val roles = due.roles.joinToString(", ") { it.id }
                say(source, line().append(plain("  #${due.id} ${Comings.what(due)}")).append(grey(" ($roles)$dead$home: day $day, about ${Comings.arriveHour(due.id)}:00")))
            }
        }

        // Who's away
        if (town.away.isEmpty()) say(source, grey("Nobody away."))
        else {
            say(source, label("Away in hard times (the first back when the times are good again):"))
            for (a in town.away) {
                val people = a.members.mapNotNull { ctx.residents[it] }
                val family = people.firstOrNull()?.family ?: "?"
                val empty = if (Comings.free(ctx, a.home)) "still empty" else "taken since"
                say(source, line().append(plain("  The ${family}s (${people.joinToString(", ") { it.given }})")).append(
                    grey(": left on day ${a.day}, ${a.why.ifEmpty { "in hard times" }}; their home #${a.home} is $empty"),
                ))
            }
        }
        if (town.left >= 0) say(source, grey("The last household left on day ${town.left}: the next no sooner than day ${town.left + Comings.WEEK}."))
        if (town.leaving != ComingsData.NEVER) say(source, line().append(label("To leave: ")).append(plain("a household on day ${town.leaving}, from about ${Comings.leaveHour(here.key, town.leaving)}:00")))
        else if (times == Times.HARD) say(source, grey("After ${Comings.HARD_RUN} hard days running a household leaves; then one a week at most."))

        // Who came
        val log = town.log.asReversed()
        if (log.isNotEmpty()) {
            say(source, label("Came lately, newest first:"))
            for (m in log.take(8)) {
                val people = m.members.mapNotNull { ctx.residents[it] }
                val family = people.firstOrNull()?.family ?: "?"
                say(source, line().append(plain("  Day ${m.day}: the ${family}s (${people.joinToString(", ") { "${it.given}, ${it.role.id}" }})")).append(
                    grey(" to #${m.home}: ${m.why.ifEmpty { m.reason }}"),
                ))
            }
        }
        if (times == Times.GOOD || times == Times.THRIVING) {
            val every = if (times == Times.THRIVING) Comings.GROW_THRIVING else Comings.GROW_GOOD
            val next = if (town.grown == ComingsData.NEVER) "the next day worked out" else "day ${town.grown + every}"
            say(source, grey("In ${times.id} times a household comes to grow the town every $every days: the next on $next, if there's room."))
        }
        val empty = Comings.empty(level, here.key)
        if (empty.isNotEmpty()) say(source, line().append(label("To let: ")).append(plain(empty.joinToString(", ") { "#$it" })))
        val playing = ComingsBodies.describe(here.key)
        for (p in playing) say(source, line().append(label("Now: ")).append(plain(p)))
        val toMeet = town.bodies.count { !ComingsBodies.busy(it) }
        if (toMeet > 0) say(source, grey("$toMeet of them in by the record are still to be met at home (their bodies are made out of sight as you come near)."))
        if (town.why.isNotEmpty()) say(source, line().append(label("Lately: ")).append(grey(town.why)))
        return 1
    }

    // ---- Playing one now ----

    /** Up to [n] households come in now, one after another: those due first, else ones come to grow the town. */
    private fun arrive(context: CommandContext<CommandSourceStack>, n: Int): Int {
        val source = context.source
        val level = source.level
        off()?.let { return fail(source, it) }
        val here = here(context) ?: return 0
        val ctx = Comings.ctx(level, here.places)
        val today = Comings.today(level, here.key) ?: return fail(source, "This capital's market isn't open yet: try again in a second.")
        val times = Fortunes.times(level, here.key)
        val queue = ArrayDeque(Comings.queue(ctx.town, times))
        var came = 0
        for (i in 0 until n) {
            val due = queue.removeFirstOrNull() ?: run {
                val roles = Comings.family(Comings.mix(here.key, today * 131 + ctx.data.hired.size + i + ctx.town.log.size), 4)
                ComingsData.Due(ctx.data.newId(), Comings.GROW, roles, roles.map { -1 }, -1, today, 0, listOf(), -1)
            }
            val h = Comings.arrive(ctx, due, today, gate = null, force = true, home = null, delay = came * STAGGER, remark = "brought in now by a command")
            if (h == null) {
                fail(source, "${Comings.what(due).replaceFirstChar { it.uppercase() }} couldn't come: there's no house for them to take.")
                break
            }
            came++
            val people = h.members.mapNotNull { ctx.residents[it] }
            val playing = ComingsBodies.busy(h.members.firstOrNull() ?: -1)
            say(source, line().append(plain("The ${people.firstOrNull()?.family ?: "?"}s (${people.joinToString(", ") { "${it.given}, ${it.role.id}" }})")).append(
                grey(" come to #${h.home}, ${Comings.what(due)}: " + if (playing) "in at the gate, with their cart" else "nobody's near a gate, so they're simply in, to be met at home"),
            ))
        }
        return came
    }

    /** The household nearest you that may leave goes now, whatever the town. */
    private fun leave(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        off()?.let { return fail(source, it) }
        val here = here(context) ?: return 0
        val ctx = Comings.ctx(level, here.places)
        val today = Comings.today(level, here.key) ?: return fail(source, "This capital's market isn't open yet: try again in a second.")
        val h = Comings.leave(ctx, today, force = true, near = source.playerOrException.blockPosition())
            ?: return fail(source, ctx.town.why.ifEmpty { "No household may leave." })
        val people = h.members.mapNotNull { ctx.residents[it] }
        val playing = ComingsBodies.busy(h.members.firstOrNull() ?: -1)
        say(source, line().append(plain("The ${people.firstOrNull()?.family ?: "?"}s (${people.joinToString(", ") { it.given }})")).append(
            grey(" leave #${h.home}: " + if (playing) "packing their cart at the door" else "nobody's near, so they're simply gone"),
        ))
        return 1
    }

    /** The household away longest comes back now. */
    private fun comeBack(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        off()?.let { return fail(source, it) }
        val here = here(context) ?: return 0
        val ctx = Comings.ctx(level, here.places)
        val today = Comings.today(level, here.key) ?: return fail(source, "This capital's market isn't open yet: try again in a second.")
        val away = ctx.town.away.firstOrNull() ?: return fail(source, "Nobody's away from ${here.name}.")
        val h = Comings.comeBack(ctx, away, today, gate = null, force = true) ?: return fail(source, "They couldn't come back: there's no house for them.")
        val people = h.members.mapNotNull { ctx.residents[it] }
        val playing = ComingsBodies.busy(h.members.firstOrNull() ?: -1)
        say(source, line().append(plain("The ${people.firstOrNull()?.family ?: "?"}s (${people.joinToString(", ") { it.given }})")).append(
            grey(" come back to #${h.home}: " + if (playing) "in at the gate, with their cart" else "nobody's near a gate, so they're simply home, to be met there"),
        ))
        return 1
    }

    // ---- Words ----

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
