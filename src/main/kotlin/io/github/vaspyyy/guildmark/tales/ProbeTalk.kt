package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.townsfolk.Activity
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * The probe's voice in the talk (living-world/stories/design.md 14.5 and 14.7), Part C. The probe's file is the engine's (A), so its
 * talk is kept here and registered for it ([TaleTalk]); a real tale writes the same as part of its own template, by implementing
 * [TaleVoice], and this is the model of one: the asker, the grey words of each beat from the numbers the tale keeps, and what is
 * offered at each of its verbs.
 *
 *  - **CHOOSE** (step 1): two replies, the first word wins (`n[0]`), and the second player hears "It's been decided.".
 *  - **GIVE** (step 2): one Mark, or the rest of the sum, put into the tale (`TaleOptions.put`, held until the step is met).
 *  - **DO** (step 5): a plain note for a stick (4.6): "I'll take the note.", "I lost the note." while it is given and not carried, and
 *    "I've done it." while it is carried, which hands it in as the board's own turn-in does and pays as it does (the board's least, 1 Mark).
 *    A FETCH is the probe's alone: a real tale never hands one (4.6).
 *
 * Its lines are typed (`say.guildmark.talk.tale.probe.<beat>_<n>`) and unrecorded, so they read as text alone, as a tale does until its
 * takes are made.
 */
internal object ProbeTalk : TaleVoice {
    private const val AT_CHOOSE = 1
    private const val AT_GIVE = 2
    private const val AT_DO = 5

    override fun asker(t: Tale): String = "asker"

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val asker = TaleLines.lit(turn.name("asker"))
        return when (beat) {
            "ask" -> TaleLines.facts("talk.detail.tale.probe.ask", asker, t.n[1], TaleLines.due(turn.left))
            "passing", "rumour" -> TaleLines.facts("talk.detail.tale.probe.passing", asker)
            "stand" -> stand(turn, asker)
            else -> null
        }
    }

    /** How it stands, from what the tale keeps: the Marks given and asked, the days the step has. */
    private fun stand(turn: TaleTurn, asker: String): String? {
        val t = turn.tale
        val closes = TaleLines.due(turn.left)
        return when (t.step) {
            0 -> TaleLines.facts("talk.detail.tale.probe.stand.0", asker, closes)
            AT_GIVE -> TaleLines.facts("talk.detail.tale.probe.stand.2", t.n[2], t.n[1], closes)
            else -> TaleLines.facts("talk.detail.tale.probe.stand.${t.step}", closes)
        }
    }

    override fun options(turn: TaleTurn): List<TaleOption> = when (turn.step) {
        AT_CHOOSE -> listOf(reply(1), reply(2))
        AT_GIVE -> give(turn)
        AT_DO -> note(turn)
        else -> listOf()
    }

    // ---- CHOOSE ----

    /** One of the two replies: the first said wins, and the tale moves on; to a second player it is already settled. */
    private fun reply(which: Int) = TaleOption("reply$which", Component.translatable("talk.guildmark.choice.tale.probe.reply_$which")) { turn ->
        val t = turn.tale
        if (t.step != AT_CHOOSE || t.n[0] != 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        t.n[0] = which
        turn.mine?.let { it.did = it.did or Part.CHOSE }
        // The reason first, so that what the next step adds is newer than it
        t.addWhy(Why("probe.chose", listOf(turn.who, which.toString())))
        turn.changed()
        c.next(t)
        TaleReply(listOf(turn.say("chose", TaleLines.facts("talk.detail.tale.probe.chose", which))), Activity.NOD)
    }

    // ---- GIVE ----

    /** One Mark, or what is still wanted of the sum. */
    private fun give(turn: TaleTurn): List<TaleOption> {
        val wanted = (turn.tale.n[1] - turn.tale.n[2]).coerceAtLeast(0)
        if (wanted <= 0) return listOf()
        val out = ArrayList<TaleOption>()
        out += put("give1", Component.translatable("talk.guildmark.choice.tale.probe.give_one"), 1)
        if (wanted > 1) out += put("give$wanted", Component.translatable("talk.guildmark.choice.tale.probe.give_rest", wanted), wanted)
        return out
    }

    private fun put(id: String, label: Component, marks: Int) = TaleOption(id, label) { turn ->
        val t = turn.tale
        if (t.step != AT_GIVE) return@TaleOption null
        when (TaleOptions.check(turn, marks)) {
            TaleOptions.Put.SHORT -> return@TaleOption TaleReply(turn.family("short"), gesture = Activity.SHRUG)
            TaleOptions.Put.LATER -> return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
            else -> {}
        }
        // The reason first, so that what the step's end adds is newer than it
        val why = Why(if (marks == 1) "probe.gave_one" else "probe.gave", listOf(turn.who, marks.toString()))
        t.addWhy(why)
        if (TaleOptions.put(turn, marks) != TaleOptions.Put.DONE) {
            if (t.why.lastOrNull() === why) t.why.removeAt(t.why.size - 1)
            return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        }
        val moved = t.step != AT_GIVE
        TaleReply(listOf(turn.say(if (moved) "gave_all" else "gave", TaleLines.facts("talk.detail.tale.probe.gave", t.n[2].coerceAtMost(t.n[1]), t.n[1]))), Activity.THANK)
    }

    // ---- DO ----

    /** The note: taken, taken again if it was lost, handed in. */
    private fun note(turn: TaleTurn): List<TaleOption> {
        val player = turn.player ?: return listOf()
        return when {
            TaleOptions.carried(player, turn.tale) != null -> listOf(handIn())
            TaleOptions.wasGiven(turn) -> listOf(take("lost", "talk.guildmark.choice.tale.probe.lost_note"))
            else -> listOf(take("take", "talk.guildmark.choice.tale.probe.take_note"))
        }
    }

    private fun take(id: String, label: String) = TaleOption(id, Component.translatable(label)) { turn ->
        val t = turn.tale
        val player = turn.player
        if (t.step != AT_DO || player == null || TaleOptions.carried(player, t) != null) return@TaleOption null
        if (turn.ctx() == null) return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val asker = turn.name("asker")
        val note = QuestNote(
            QuestType.FETCH, asker, Identifier.fromNamespaceAndPath("minecraft", "stick"), 1, 1,
            (turn.left + 1).toInt().coerceIn(2, 6), chain = TaleOptions.chain(t),
        )
        val said = Component.translatable("message.guildmark.tale.note", asker, Component.translatable(TaleKeys.title(t.template)))
        if (!TaleOptions.give(turn, note, said)) return@TaleOption TaleReply(turn.family("nonote"), gesture = Activity.SHRUG)
        TaleReply(listOf(turn.say(if (id == "lost") "lost" else "note")), Activity.NOD)
    }

    private fun handIn() = TaleOption("done", Component.translatable("talk.guildmark.choice.tale.probe.done_note")) { turn ->
        if (turn.step != AT_DO) return@TaleOption null
        when (TaleOptions.handIn(turn)) {
            TaleOptions.Hand.DONE -> TaleReply(turn.family("done"), gesture = Activity.THANK)
            TaleOptions.Hand.EXPIRED -> TaleReply(turn.family("late"), gesture = Activity.SHRUG)
            TaleOptions.Hand.NOT_YET -> TaleReply(turn.family("notyet"), gesture = Activity.SHRUG)
            TaleOptions.Hand.NONE -> TaleReply(turn.family("nonote"), gesture = Activity.SHRUG)
            TaleOptions.Hand.LATER -> TaleReply("ask.think", gesture = Activity.THINK)
        }
    }
}
