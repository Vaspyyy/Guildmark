package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer

/**
 * The engine's commands under `/guildmark tale` (living-world/stories/design.md 12.4, the rows marked A): the capital's tale and
 * why there is none, one tale in full, what each template finds, and the ways to start, close, step, skip, end, clear and quiet
 * one, to set a player's part and to check the templates. Game masters only, run standing in a capital. The ones that only read
 * answer with tales off and say what is off; the ones that write say why they will not and change nothing.
 */
internal object TaleEngineCommands {
    /** Appends the engine's subcommands to the `tale` command (one line of `Tales.register` calls this through [TaleEngine.register]). */
    fun add() {
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("show")
                    .executes { show(it, null) }
                    .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes { show(it, IntegerArgumentType.getInteger(it, "id")) }),
            )
            tale.then(Commands.literal("seeds").executes { seeds(it) })
            tale.then(
                Commands.literal("start").then(
                    Commands.argument("template", StringArgumentType.word())
                        .suggests { _, b -> SharedSuggestionProvider.suggest(Templates.IDS + Templates.PROBE.id, b) }
                        .executes { start(it, StringArgumentType.getString(it, "template"), false) }
                        .then(Commands.literal("force").executes { start(it, StringArgumentType.getString(it, "template"), true) }),
                ),
            )
            tale.then(
                Commands.literal("day")
                    .executes { day(it, 1) }
                    .then(Commands.argument("n", IntegerArgumentType.integer(1, 14)).executes { day(it, IntegerArgumentType.getInteger(it, "n")) }),
            )
            tale.then(Commands.literal("step").executes { step(it) })
            tale.then(Commands.literal("skip").executes { skip(it) })
            tale.then(
                Commands.literal("end").then(
                    Commands.argument("ending", StringArgumentType.word())
                        .suggests { _, b -> SharedSuggestionProvider.suggest(Ending.LETTERS, b) }
                        .executes { end(it, StringArgumentType.getString(it, "ending")) },
                ),
            )
            tale.then(Commands.literal("clear").executes { clear(it) })
            tale.then(
                Commands.literal("quiet").then(
                    Commands.argument("days", IntegerArgumentType.integer(0, 60)).executes { quiet(it, IntegerArgumentType.getInteger(it, "days")) },
                ),
            )
            tale.then(
                Commands.literal("part").then(
                    Commands.argument("player", EntityArgument.player()).then(
                        Commands.argument("status", StringArgumentType.word())
                            .suggests { _, b -> SharedSuggestionProvider.suggest(Part.Status.entries.map { it.id }, b) }
                            .executes { part(it, EntityArgument.getPlayer(it, "player"), StringArgumentType.getString(it, "status")) },
                    ),
                ),
            )
            tale.then(Commands.literal("check").executes { TaleCheck.run(it.source) })
        }
    }

    // ---- The capital you stand in ----

    /** A capital the command is run in, and what a template is given for it. */
    private class At(val c: Ctx, val name: String)

    /** The capital [context]'s player stands in, with its places and market; null (and why, said) if there isn't one. */
    private fun at(context: CommandContext<CommandSourceStack>): At? {
        val here = TaleCommand.here(context) ?: return null
        val level = context.source.level
        val places = CityPlaces.of(level, here.capital)
        if (places == null) {
            TaleCommand.fail(context.source, if (CityPlaces.failed(here.capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        TaleEngine.use(level)
        return At(Ctx(level, Scenes.Here(here.capital, places), here.entry, TaleEngine.today(level, here.key)), here.name)
    }

    /** True, and says so, while tales are not in this build ([Tales.READY]): a command then answers in one grey line and touches nothing. */
    private fun notReady(source: CommandSourceStack): Boolean {
        if (Tales.READY) return false
        say(source, TaleCommand.grey(TaleCommand.off() ?: "Tales are not ready in this build."))
        return true
    }

    /** True, and says why, if tales are off, so a command that writes does nothing. */
    private fun refuse(source: CommandSourceStack): Boolean {
        Tales.refresh()
        val why = TaleCommand.off() ?: return false
        TaleCommand.fail(source, "$why Nothing was changed.")
        return true
    }

    private fun say(source: CommandSourceStack, c: Component): Int = TaleCommand.say(source, c)

    private fun fail(source: CommandSourceStack, s: String): Int = TaleCommand.fail(source, s)

    private fun labelled(label: String, text: String): MutableComponent = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    private fun days(day: Long): String = if (TaleKeep.real(day)) "day $day" else "none"

    // ---- tale ----

    /** `tale`: the capital's tale if one is open, then the pace and why there is none today. */
    fun overview(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (notReady(source)) return 1
        val here = at(context) ?: return 0
        val c = here.c
        TaleCommand.off()?.let { say(source, TaleCommand.grey(it)) }
        say(source, TaleCommand.header("Tales in ${here.name}: market day ${c.day}"))
        val town = c.data.find(c.capital)
        val open = c.data.openIn(c.capital)
        if (open == null) {
            say(source, TaleCommand.plain("No tale is open here."))
        } else {
            val template = Templates.find(open.template)
            val step = template?.steps?.getOrNull(open.step)
            say(source, labelled("Open", "the ${open.template} tale #${open.id}, at step ${open.step + 1} of ${template?.steps?.size ?: "?"} (${step?.verb?.name ?: "?"})"))
            say(source, labelled("Days", "seeded ${days(open.d0)}; this step began ${days(open.stepDay)}; the world settles it at the close after ${days(open.by)}; the hard stop is after day ${open.d0 + TaleLimits.TALE_DAYS}"))
            val lead = open.lead?.let { TaleEngine.playerName(c.level, it.player).ifEmpty { it.player.toString() } }
            say(source, labelled("Taken up by", lead ?: "nobody yet"))
            say(source, labelled("Cast", open.cast.joinToString(", ") { "${it.slot} ${it.name}" + (if (it.held) " (held)" else "") + (if (it.gone.isNotEmpty()) " (${it.gone})" else "") }.ifEmpty { "none" }))
            say(source, TaleCommand.grey("tale show for the rest; tale step, skip, end <A|B|C|W> or clear to move it."))
        }
        val quiet = if (town != null && TaleKeep.real(town.quiet) && town.quiet >= c.day) "through day ${town.quiet}" else "none"
        say(source, labelled("Quiet", quiet))
        say(source, labelled("Seen", "${days(town?.seen ?: TaleData.NEVER)} (a player must have been here within ${TaleLimits.SEEN_DAYS} days)"))
        say(source, labelled("Closed", "${days(town?.closed ?: TaleData.NEVER)} (the last close the tales lived here)"))
        val told = town?.chronicle?.lastOrNull()
        say(source, labelled("Chronicle", if (town == null || told == null) "no line yet" else "${town.chronicle.size} lines; the newest is the ${told.template} tale, ended at ${told.ending} on day ${told.day}"))
        val pace = Tales.pace()
        say(source, labelled("Pace", "%.1fx: a draw of %d in 100 at each close".format(pace, Math.round(TaleRules.chance(pace) * 100))))
        if (open == null) {
            val stop = if (town == null) "no player has been here yet" else TaleRules.stopped(c.day + 1, town.quiet, town.seen, TaleClose.daysOnRecord(c), TaleClose.startsOn(c.day + 1), false)
            say(source, labelled("At the next close", stop ?: "the draw is open: ${Math.round(TaleRules.chance(pace) * 100)} in 100, and then the templates in the day's order"))
            TaleClose.lastDraw(c.capital)?.let { say(source, labelled("Last draw", it)) }
        }
        return 1
    }

    // ---- tale show ----

    /** `tale show [id]`: one tale in full: the open tale here, or the newest here when none is open. */
    private fun show(context: CommandContext<CommandSourceStack>, id: Int?): Int {
        val source = context.source
        if (notReady(source)) return 1
        val here = at(context) ?: return 0
        val c = here.c
        TaleCommand.off()?.let { say(source, TaleCommand.grey(it)) }
        val t = when {
            id != null -> c.data[id]
            else -> c.data.openIn(c.capital) ?: c.data.all().filter { it.capital == c.capital }.maxByOrNull { it.id }
        }
        if (t == null) return fail(source, if (id != null) "No tale #$id is on record." else "No tale is on record here.")
        val template = Templates.find(t.template)
        say(source, TaleCommand.header("Tale #${t.id}: ${t.template} (${t.phase.id}${if (t.ending.isNotEmpty()) " at ${t.ending}" else ""})"))
        if (t.capital != c.capital) say(source, TaleCommand.grey("It belongs to another capital (heart at ${t.capital})."))
        if (template == null) say(source, TaleCommand.grey("This build has no ${t.template} tale: the next close ends it quietly."))
        say(source, labelled("Days", "seeded ${days(t.d0)}; step began ${days(t.stepDay)}; settles after ${days(t.by)}; ended ${days(t.ended)}"))
        say(source, labelled("Finished", Finish.entries.joinToString(" ") { (if (t.done and it.bit != 0) "+" else "-") + it.name.lowercase() }))
        if (template != null) {
            say(source, label("Steps:"))
            template.steps.forEachIndexed { i, s ->
                val now = if (i == t.step && t.open) "  <- here" else ""
                say(source, TaleCommand.plain("  $i ${s.verb.name} by D${s.by}${if (s.real) ", real" else ""}$now"))
            }
        }
        say(source, labelled("Cast", t.cast.joinToString("; ") { "${it.slot}: ${it.name} (#${it.resident})" + (if (it.held) " held" else "") + (if (it.gone.isNotEmpty()) " ${it.gone}" else "") }.ifEmpty { "none" }))
        val numbers = t.n.indices.filter { t.n[it] != 0 || it < (template?.numbers?.size ?: 0) }.joinToString(", ") { "${template?.numbers?.getOrNull(it) ?: "n$it"}=${t.n[it]}" }
        say(source, labelled("Numbers", numbers.ifEmpty { "none" }))
        val strings = t.s.indices.filter { t.s[it].isNotEmpty() || it < (template?.strings?.size ?: 0) }.joinToString(", ") { "${template?.strings?.getOrNull(it) ?: "s$it"}=\"${t.s[it]}\"" }
        say(source, labelled("Strings", strings.ifEmpty { "none" }))
        if (t.parts.isEmpty()) {
            say(source, labelled("Parts", "none"))
        } else {
            say(source, label("Parts:"))
            for (p in t.parts) {
                val who = TaleEngine.playerName(c.level, p.player).ifEmpty { p.player.toString() }
                say(source, TaleCommand.plain("  $who: ${p.status.id}, did ${p.did}, paid ${p.paid}, escrow ${p.escrow}, asked ${p.asks}"))
            }
        }
        say(source, label("Reasons, oldest first:"))
        for (w in t.why) say(source, TaleCommand.line().append(TaleCommand.plain("  ")).append(TaleKeys.text(w)).append(TaleCommand.grey("  (${w.kind})")))
        return 1
    }

    private fun label(s: String): MutableComponent = TaleCommand.label(s)

    // ---- tale seeds ----

    /** `tale seeds`: for each of the twelve, what it finds here today or the first thing that stops it. */
    private fun seeds(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (notReady(source)) return 1
        val here = at(context) ?: return 0
        val c = here.c
        TaleCommand.off()?.let { say(source, TaleCommand.grey(it)) }
        say(source, TaleCommand.header("What the tales find in ${here.name} on day ${c.day}"))
        val town = c.data.find(c.capital)
        val open = c.data.openIn(c.capital)
        val rule = when {
            open != null -> "the ${open.template} tale #${open.id} is open (one tale at a time)"
            town == null -> "no player has been here yet"
            else -> TaleRules.stopped(c.day, town.quiet, town.seen, TaleClose.daysOnRecord(c), TaleClose.startsOn(c.day), false)
        }
        say(source, labelled("The draw's rules now", rule ?: "none stops a draw today"))
        for (id in Templates.IDS) {
            val template = Templates.byId(id)
            if (template == null) {
                say(source, TaleCommand.line().append(label("$id: ")).append(TaleCommand.grey("not built yet")))
                continue
            }
            val last = town?.last?.get(id) ?: TaleData.NEVER
            val found = when {
                !TaleSeeding.needs(template) -> TaleCommand.grey("stands on something that is off (or could not say)")
                TaleRules.again(c.day, last) -> TaleCommand.grey("ended here on day $last, and a template waits ${TaleLimits.AGAIN_DAYS} days to be seeded again")
                else -> dryRun(c, template)
            }
            say(source, TaleCommand.line().append(label("$id: ")).append(found))
        }
        return 1
    }

    /** What [template] would find here today, without making anything. */
    private fun dryRun(c: Ctx, template: Template): MutableComponent {
        val seeding = Seeding(c.level, c.here, c.entry, c.day, false, template.id)
        val plan = try {
            template.seed(seeding)
        } catch (e: Exception) {
            return TaleCommand.plain("threw while it looked (see the log)").withStyle(ChatFormatting.RED)
        }
        if (plan == null) return TaleCommand.grey("finds nothing: ${seeding.found.ifEmpty { "no reason given" }}")
        val cast = plan.cast.joinToString(", ") { "${it.slot} ${it.name}" }
        return TaleCommand.plain("finds its trouble: $cast (${plan.why.kind})").withStyle(ChatFormatting.GREEN)
    }

    // ---- tale start ----

    /** `tale start <template> [force]`: seeds it here now, whatever the draw, the quiet, the forty days, the first six days and `seen` say. */
    private fun start(context: CommandContext<CommandSourceStack>, id: String, force: Boolean): Int {
        val source = context.source
        if (refuse(source)) return 0
        val template = Templates.find(id)
            ?: return fail(source, if (id in Templates.IDS) "The $id tale is not built yet in this build: tale seeds says which are." else "There is no tale called $id: " + (Templates.IDS + Templates.PROBE.id).joinToString(", "))
        val here = at(context) ?: return 0
        val c = here.c
        if (!TaleSeeding.needs(template)) return fail(source, "The $id tale stands on something that is off (see tale seeds), so it cannot be started.")
        val open = c.data.openIn(c.capital)
        if (open != null) {
            if (!force) return fail(source, "The ${open.template} tale #${open.id} is open here: tale start $id force clears it first, as tale clear does.")
            TaleFinish.clear(c, open, quiet = false)
            say(source, TaleCommand.grey("Cleared the ${open.template} tale #${open.id}."))
        }
        val town = c.data.find(c.capital)
        val set = ArrayList<String>()
        if (town != null && TaleKeep.real(town.quiet) && town.quiet >= c.day) set += "the quiet through day ${town.quiet}"
        if (town != null && TaleRules.again(c.day, town.last[id] ?: TaleData.NEVER)) set += "the ${TaleLimits.AGAIN_DAYS}-day rule"
        if (town == null || town.seen < c.day - TaleLimits.SEEN_DAYS) set += "the rule that a player has been here"
        if (TaleClose.daysOnRecord(c) < TaleLimits.FIRST_DAY) set += "the first ${TaleLimits.FIRST_DAY} days on record"
        val made = TaleSeeding.attempt(c, template, forced = true)
        val t = made.tale ?: return fail(source, "The $id tale found nothing to start from here: ${made.why}.")
        say(source, TaleCommand.header("Started the ${t.template} tale #${t.id} in ${here.name}"))
        say(source, labelled("Step", "${t.step} (${template.steps[t.step].verb.name}); the world settles it at the close after ${days(t.by)}"))
        say(source, labelled("Cast", t.cast.joinToString(", ") { "${it.slot} ${it.name}" }))
        if (set.isNotEmpty()) say(source, TaleCommand.grey("Set aside: ${set.joinToString(", ")}."))
        if (made.madeUp.isNotEmpty()) say(source, TaleCommand.grey("Made up for the test: ${made.madeUp}."))
        return 1
    }

    // ---- tale day ----

    /** `tale day [n]`: the capital lives its next market day or days now, a real close, and says how long each took. */
    private fun day(context: CommandContext<CommandSourceStack>, n: Int): Int {
        val source = context.source
        if (refuse(source)) return 0
        val here = at(context) ?: return 0
        val key = here.c.capital
        repeat(n) {
            val began = System.nanoTime()
            val said = TaleClose.live(here.c.level, key) ?: return fail(source, "Its places are still being worked out: try again in a second.")
            val ms = (System.nanoTime() - began) / 1_000_000.0
            say(source, TaleCommand.line().append(TaleCommand.plain(said)).append(TaleCommand.grey("  (%.2f ms)".format(ms))))
        }
        return n
    }

    // ---- The open tale ----

    /** The open tale here, or (and says so) none. */
    private fun openTale(source: CommandSourceStack, c: Ctx): Tale? =
        c.data.openIn(c.capital) ?: run {
            fail(source, "No tale is open here: tale start <template> makes one.")
            null
        }

    /** `tale step`: the current step settled by the world's way now, as the close would. */
    private fun step(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (refuse(source)) return 0
        val c = at(context)?.c ?: return 0
        val t = openTale(source, c) ?: return 0
        val template = Templates.find(t.template) ?: return fail(source, "This build has no ${t.template} tale: the next close ends it quietly.")
        val was = t.step
        TaleSteps.lapse(c, t)
        return say(source, TaleCommand.plain("Settled step $was (${template.steps.getOrNull(was)?.verb?.name ?: "?"}) of the ${t.template} tale #${t.id} by the world's way: " + where(t)))
    }

    /** `tale skip`: the current step let go as if it could not be done (5.7). */
    private fun skip(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (refuse(source)) return 0
        val c = at(context)?.c ?: return 0
        val t = openTale(source, c) ?: return 0
        val was = t.step
        TaleSteps.skip(c, t)
        return say(source, TaleCommand.plain("Skipped step $was of the ${t.template} tale #${t.id}: " + where(t)))
    }

    /** `tale end <A|B|C|W>`: the open tale ended with that ending through the whole pipeline of 6.7. */
    private fun end(context: CommandContext<CommandSourceStack>, letter: String): Int {
        val source = context.source
        if (refuse(source)) return 0
        val ending = letter.uppercase()
        if (ending !in Ending.LETTERS) return fail(source, "An ending is A, B, C or W.")
        val c = at(context)?.c ?: return 0
        val t = openTale(source, c) ?: return 0
        val template = Templates.find(t.template)
        if (template != null && template.ending(ending) == null) return fail(source, "The ${t.template} tale has no ending $ending.")
        Tales.finish(c, t, ending)
        return say(source, TaleCommand.plain("Ended the ${t.template} tale #${t.id} at $ending. Finished: " + Finish.entries.joinToString(" ") { (if (t.done and it.bit != 0) "+" else "-") + it.name.lowercase() }))
    }

    /** `tale clear`: the open tale taken out of the record with no consequence (no chronicle line, no pay; the held let go, the escrow given back). */
    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (refuse(source)) return 0
        val c = at(context)?.c ?: return 0
        val t = openTale(source, c) ?: return 0
        TaleFinish.clear(c, t, quiet = false)
        return say(source, TaleCommand.plain("Cleared the ${t.template} tale #${t.id}: no chronicle line, no pay, its people let go and what was put in given back."))
    }

    /** `tale quiet <days>`: no tale is drawn here for that many closes from today (0 clears it). */
    private fun quiet(context: CommandContext<CommandSourceStack>, days: Int): Int {
        val source = context.source
        if (refuse(source)) return 0
        val c = at(context)?.c ?: return 0
        val town = c.data.town(c.capital)
        town.quiet = if (days == 0) TaleData.NEVER else c.day + days
        c.data.changed()
        return say(source, TaleCommand.plain(if (days == 0) "The quiet here is cleared." else "No tale is drawn here through day ${town.quiet} (the next $days closes). tale start still starts one."))
    }

    /** `tale part <player> <status>`: sets a player's part in the open tale, so a second player can be tested alone. */
    private fun part(context: CommandContext<CommandSourceStack>, player: ServerPlayer, status: String): Int {
        val source = context.source
        if (refuse(source)) return 0
        val to = Part.Status.entries.firstOrNull { it.id == status } ?: return fail(source, "A part is " + Part.Status.entries.joinToString(", ") { it.id } + ".")
        val c = at(context)?.c ?: return 0
        val t = openTale(source, c) ?: return 0
        val part = t.partFor(player.uuid) ?: return fail(source, "The tale has ${TaleLimits.PARTS} parts already and none can be let go.")
        part.status = to
        c.data.changed()
        return say(source, TaleCommand.plain("${player.name.string}'s part in the ${t.template} tale #${t.id} is ${to.id} now."))
    }

    /** Where a tale stands after a step: its step, or its end. */
    private fun where(t: Tale): String =
        if (t.open) "now at step ${t.step}, settling after ${days(t.by)}" else "it ended at ${t.ending}"
}
