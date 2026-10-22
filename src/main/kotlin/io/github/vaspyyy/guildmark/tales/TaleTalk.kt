package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.CapitalCommand
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Fade
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumours
import io.github.vaspyyy.guildmark.townsfolk.talk.Scope
import io.github.vaspyyy.guildmark.townsfolk.talk.Spread
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.Venue
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.TaleTopics
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import java.util.Locale

/**
 * What the tales' talk sets up and shows (living-world/stories/design.md 3.2 and 14.7), Part C. [register] is the one line
 * `Tales.register` has for it:
 *
 *  - the probe's voice is registered ([ProbeTalk]; a real tale carries its own, `TaleVoice`);
 *  - when a tale is seeded its first beat becomes a rumour, as a death or a newcomer does (`Witness`): kind `tale.<template>.ask`,
 *    the asker as its resident, heat 0.7, [Fade.BIG], [Scope.TOWN], known first by the cast (who saw it), their households, a few of
 *    their trade and their street; its first argument is the tale's id, from which `TaleTopics` reads the live words, so the telling
 *    always has the tale as it is, never as it was; it carries the asker's door as its place, to be pointed to. From there it travels
 *    as any rumour does, and is told in the tale's own voice by `TaleTopics`, never as news nor cried (the head `tale` of
 *    `RumourWords`);
 *  - the command `/guildmark tale talk`, which prints, for the townsperson you look at, what the talk will say and show for the tale
 *    in the order the screen shows it, and changes nothing.
 */
object TaleTalk {
    /** The seed rumour's heat, and how many know it first: the cast, their households, [WORKMATES] of the asker's trade, the street of the first [STREETS]. */
    private const val HEAT = 0.7f
    private const val KNOWERS = 40
    private const val WORKMATES = 3
    private const val STREETS = 2

    /** Called once from `Tales.register`, before the commands are built. */
    fun register() {
        TaleVoices.register("probe", ProbeTalk)
        Tales.listen(Seed)
        TaleCommand.sub.add { tale -> tale.then(Commands.literal("talk").executes { show(it) }) }
    }

    private object Seed : Tales.Listener {
        override fun started(level: ServerLevel, t: Tale) = rumour(level, t)
    }

    // ---- The rumour of a tale's first beat ----

    /** Starts [t]'s seed rumour (3.2): nothing if its asker is no one with a record, or nobody has one to know it. */
    private fun rumour(level: ServerLevel, t: Tale) {
        if (!Tales.READY) return
        val template = Templates.find(t.template) ?: return
        val slot = TaleVoiceGuard.run(t.template, "asker", null) { TaleVoices.of(template)?.asker(t) } ?: t.cast.firstOrNull()?.slot ?: return
        val residents = Residents.get(level)
        val asker = residents[t.cast(slot)?.resident ?: return]?.takeIf { it.alive } ?: return
        val cast = t.cast.filter { it.resident >= 0 && it.gone.isEmpty() }.mapNotNull { residents[it.resident] }.filter { it.alive }.distinctBy { it.id }
        val knowers = knowers(level, asker, cast)
        if (knowers.isEmpty()) return
        val door = Spread.view(asker.capital).places?.lodgings?.get(asker.home)?.box?.center
        TownTalk.begin(
            level, BlockPos.of(t.capital), "tale.${t.template}.ask", null, asker.id, listOf(t.id.toString(), Witness.named(asker.name)),
            HEAT, Fade.BIG, Scope.TOWN, -1, door, Rumours.TOWN, knowers,
        )
    }

    /** Those who know it first (3.2): the [cast] who saw it, their households, workmates of the [first] and the street of the first of the cast. */
    private fun knowers(level: ServerLevel, first: Resident, cast: List<Resident>): List<Pair<Int, Venue>> {
        val out = ArrayList<Pair<Int, Venue>>()
        for (r in cast) out.add(r.id to Venue.SEEN)
        for (r in cast) for (h in TaleMemory.household(level, r)) out.add(h.id to Venue.HOUSEHOLD)
        Residents.get(level).of(first.capital).filter { it.alive && it.id != first.id && it.role == first.role }.take(WORKMATES)
            .forEach { out.add(it.id to Venue.WORK) }
        for (r in cast.take(STREETS)) for (n in Witness.neighbours(level, r)) out.add(n.id to Venue.STREET)
        return out.distinctBy { it.first }.take(KNOWERS)
    }

    // ---- /guildmark tale talk ----

    /** What the talk says and shows of the tale for the townsperson looked at, in order (3.1). Changes nothing. */
    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        if (!Tales.live) return 0
        val player = source.playerOrException
        val p = CapitalCommand.lookedAt(player) ?: return TaleCommand.fail(source, "Look at a townsperson (up to 16 blocks off), or stand right by one.")
        val level = source.level
        val ask = Talk.ask(level, p, player, Occasion.OPENER)
        val found = TaleSpeakers.find(level, p)
        TaleCommand.say(source, TaleCommand.header("The talk with ${p.name.string}"))
        if (found == null) {
            TaleCommand.say(source, TaleCommand.grey("They are in the cast of no open tale. What they say of one is a rumour, if they have heard it:"))
        } else {
            who(source, ask, found)
        }
        topics(source, "Opening the talk, after the greeting", Topics.offered(ask))
        screen(source, ask)
        script(source, ask)
        topics(source, "In passing (a bark)", Topics.offered(Talk.again(ask, Occasion.BARK)))
        topics(source, "When asked for news", Topics.offered(Talk.again(ask, Occasion.NEWS)))
        return 1
    }

    private fun who(source: CommandSourceStack, ask: Ask, found: TaleSpeakers.Found) {
        val t = found.tale
        val player = ask.player ?: return
        val today = TaleEngine.today(ask.level, t.capital)
        val verb = found.template.steps.getOrNull(t.step)?.verb
        val asker = found.template.let { TaleVoices.of(it)?.asker(t) } ?: t.cast.firstOrNull()?.slot
        TaleCommand.say(
            source,
            TaleCommand.line().append(TaleCommand.label("${found.slot} "))
                .append(TaleCommand.plain("of the ${t.template} tale #${t.id}, step ${t.step}${verb?.let { " ($it)" }.orEmpty()}, closing in ${(t.by - today).coerceAtLeast(0)} market day(s); "))
                .append(TaleCommand.grey(if (found.asks) "they are the one who asks." else "they do not ask: that is the $asker.")),
        )
        val part = t.part(player.uuid)
        val due = TaleSpeakers.askable(t, player.uuid, today)
        val why = when {
            due -> "the cast ask you now"
            TaleSpeakers.joined(part) -> "you have taken it up"
            part?.status == Part.Status.ASKED -> "they have told you already, and it is a choice from here"
            part?.status == Part.Status.DECLINED && part.asks >= 1 + TaleLimits.ASKS_MORE -> "you said Not now ${part.asks} times, and they ask no more"
            part?.status == Part.Status.DECLINED -> "you said Not now on day ${part.lastAsk}; they ask again from day ${part.lastAsk + TaleLimits.ASK_AGAIN_DAYS}"
            else -> "the tale has no room for another part"
        }
        TaleCommand.say(
            source,
            TaleCommand.line().append(TaleCommand.label("Your part: "))
                .append(TaleCommand.plain("${part?.status?.name?.lowercase(Locale.ROOT) ?: "not met"}, asked ${part?.asks ?: 0} time(s), last on day ${part?.lastAsk ?: "-"} (today is day $today); "))
                .append(TaleCommand.grey(why)),
        )
    }

    /** The tale's own topics among [offered], as the talk weighs them: the first is said ahead of the draw. */
    private fun topics(source: CommandSourceStack, title: String, offered: List<Topic>) {
        val ours = offered.filter { it.id.startsWith("tale.") }
        TaleCommand.say(source, TaleCommand.label("$title: ").append(TaleCommand.grey(if (ours.isEmpty()) "no tale topic." else "")))
        for (topic in ours) {
            TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.plain("  ${topic.id}")).append(
                    TaleCommand.grey(" w%.2f%s%s".format(Locale.ROOT, topic.weight, if (topic.first) ", said ahead of the draw" else "", topic.rumour?.let { ", carries rumour #$it" }.orEmpty())),
                ),
            )
            render(source, topic.say)
        }
    }

    /** The choices of the talk screen, in the order [Talk] builds them from the sources (3.1): Doing, News, Name while unnamed, the two most pressing, Goodbye. */
    private fun screen(source: CommandSourceStack, ask: Ask) {
        val answer = Talk.again(ask, Occasion.ANSWER)
        val chosen = Topics.choices(answer).sortedByDescending { it.pressing }.take(2)
        val rows = ArrayList<String>()
        rows += "Doing"
        rows += "News"
        if (ask.resident != null && ask.knows?.named != true) rows += "Name"
        chosen.mapTo(rows) { "${it.label.string} [${it.id}, pressing %.2f]".format(Locale.ROOT, it.pressing) }
        rows += "Goodbye"
        TaleCommand.say(source, TaleCommand.label("The screen then shows, in this order: "))
        rows.forEachIndexed { i, row -> TaleCommand.say(source, TaleCommand.grey("  ${i + 1}. $row")) }
    }

    /** What "About the note..." opens in place: how it begins, and the choices it offers. Read only: the script's own opener would count an ask. */
    private fun script(source: CommandSourceStack, ask: Ask) {
        val answer = Talk.again(ask, Occasion.ANSWER)
        val script = Topics.script(answer, TaleTopics.ASK)
        if (script == null) {
            TaleCommand.say(source, TaleCommand.label("The tale's choice: ").append(TaleCommand.grey("none for this person.")))
            return
        }
        TaleCommand.say(source, TaleCommand.label("The tale's choice opens, in place, with the same person; it offers: "))
        script.choices(answer).forEachIndexed { i, c -> TaleCommand.say(source, TaleCommand.grey("  ${i + 1}. ${c.label.string} [${c.id}]")) }
        val found = TaleSpeakers.find(ask.level, ask.speaker) ?: return
        val player = ask.player ?: return
        val turn = TaleTurn(answer, found.tale, found.slot, found.template, player)
        val said = if (TaleSpeakers.joined(turn.mine)) TaleSay(TaleSays.standFamily(turn), TaleSays.detail(turn, found.voice, "stand")) else TaleSays.ask(turn, found.voice)
        val part = TaleLines.part(answer, said.family, said.detail)
        TaleCommand.say(source, TaleCommand.label("It first says (${said.family}): "))
        if (part != null) render(source, Utterance(listOf(part))) else TaleCommand.say(source, TaleCommand.grey("  nothing: no take, no typed wording and no detail."))
    }

    /** The lines of [say] as the screen shows them: the words, and the grey detail after. */
    private fun render(source: CommandSourceStack, say: Utterance) {
        for (part in say.parts) {
            val text: Component = if (part.line.isEmpty()) Speech.text(part.detail.orEmpty()) else Speech.text(Speech.withDetail(part.line, part.detail))
            TaleCommand.say(source, Component.literal("    ").append(text))
        }
    }
}
