package io.github.vaspyyy.guildmark.client.ui

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkClient
import io.github.vaspyyy.guildmark.network.TalkChoice
import io.github.vaspyyy.guildmark.network.TalkEndPayload
import io.github.vaspyyy.guildmark.network.TalkOpenPayload
import io.github.vaspyyy.guildmark.network.TalkPart
import io.github.vaspyyy.guildmark.network.TalkPickPayload
import io.github.vaspyyy.guildmark.network.TalkSayPayload
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Talking with a townsperson (living-world/design-voices.md section 2): the dialogue kit's look on what the server
 * sends. The plate gives their name and what they are, and at the panel's right a small word for how they know the
 * player: "knows you", "has heard of you", "wary", or nothing for a stranger.
 *
 * The screen plays no voice of its own: the townsperson says each part aloud, so everyone near hears it, and the
 * screen types along in step. Each part's sentence types first, and its detail follows in a softer colour a moment
 * after, on a line of its own; the screen waits out the part's take, and a breath more, before the next part, and
 * words with no take type to the tick of a pen. More than the panel's four lines roll up as they come. Then the
 * answers ease in one by one, those asked already dimmed. A pick goes to the server, the words rise away while the
 * townsperson thinks, and their answer types in its turn. Their last words hold a moment and the screen sinks away.
 *
 * It closes beyond 12 blocks, at a blow, when the server ends the talk, at Escape, or after 100 ticks of waiting on
 * the server for nothing (not counting while the townsperson is busy answering someone else).
 */
class TalkScreen(private var open: TalkOpenPayload) : DialogueKit(Component.translatable("gui.guildmark.talk")) {

    /** Who's talking: the townsperson's entity id, or -1 for nobody (the test). */
    override val entityId: Int get() = open.entity

    /** A part as the screen says it: whether the pen ticks for it (no take that can be heard), when it began, when its last letter showed. */
    private class Said(val part: TalkPart, val blips: Boolean) {
        var at = -1
        var typedAt = -1
    }

    /** Words typed as one, in one colour: a part's sentence, or its [detail]; wrapped to the panel. */
    private class Run(val part: Int, val detail: Boolean, val text: Component) {
        var lines: List<String> = emptyList()
        var joined = ""
        /** The tick it may begin, or -1 while its turn isn't known yet. */
        var startAt = -1
        var begun = false
    }

    /** A line as it's drawn: its words, its colour, and how many of its letters show. */
    private class Shown(val text: String, val color: Int, val letters: Int)

    /** Words rising away: the lines as they stood, scrolled [scroll] lines up, from [at]. */
    private class Leaving(val lines: List<Shown>, val scroll: Float, val at: Int)

    /** Answers fading out after a pick: where their box was, the rows, the one picked ([lit], or -1), from [at]. */
    private class Gone(val box: Box, val rows: List<Row>, val lit: Int, val at: Int)

    private var said: List<Said> = emptyList()
    private var runs: List<Run> = emptyList()

    /** The run that's typing, or next to type; every run before it is all shown. */
    private var run = 0

    /** When the last part was over, its words and its take: -1 while it's still being said. */
    private var doneAt = -1

    /** The answers the server offers, and the say they came with (a pick sends its serial back). */
    private var choices: List<TalkChoice> = emptyList()
        set(value) {
            field = value
            answers = value.map { Row(it.label, it.asked) }
        }
    private var answers: List<Row> = emptyList()
    private var serial = 0

    /** These are the last words: once said, they hold a moment ([holdUntil]) and the screen sinks away ([sinkAt]). */
    private var closing = false
    private var holdUntil = -1
    private var sinkAt = -1

    /** The server ended the talk, or the screen told it it's closed: nothing more goes back. */
    private var closed = false
    private var ended = false

    /** Waiting on the server since this tick (-1: not waiting), and the ticks of silence counted. */
    private var waitingSince = -1
    private var silence = 0

    private var leaving: Leaving? = null
    private var gone: Gone? = null

    /** Lines scrolled up out of the panel's four, easing to their place. */
    private var scroll = 0f
    private var scrollLast = 0f

    /** When the word for how they know the player last changed: it fades in after. */
    private var knowsAt = 0

    /** A say that came before the screen was laid out, and whether it has been. */
    private var pending: TalkSayPayload? = null
    private var laidOut = false

    override fun init() {
        super.init()
        if (!laidOut) {
            laidOut = true
            waitingSince = ticks
            pending?.let {
                pending = null
                say(it)
            }
            return
        }
        // A resize: the same words, wrapped to the new width, as far as they'd got
        for (r in runs) wrap(r)
        runs.getOrNull(run)?.let { shown = min(shown, it.joined.length.toFloat()) }
    }

    override fun removed() {
        if (closed || ended) return
        ended = true
        ClientPacketDistributor.sendToServer(TalkEndPayload(open.entity))
    }

    // ---- From the server ----

    /** The server began the talk again ([TalkOpenPayload] for the same speaker): fresh words, the panel staying up. */
    fun reopen(payload: TalkOpenPayload) {
        if (payload.knows != open.knows) knowsAt = ticks
        open = payload
        dropChoices(-1)
        leave()
        choices = emptyList()
        closing = false
        closed = false
        holdUntil = -1
        sinkAt = -1
        waitingSince = ticks
        silence = 0
    }

    /** What the speaker says ([TalkSayPayload]): their words, then the answers, or their last words. */
    fun say(payload: TalkSayPayload) {
        if (!laidOut) {
            pending = payload
            return
        }
        // The last words are said already: nothing comes after them
        if (sinkAt >= 0 || holdUntil >= 0) return
        // Nothing to say: the server is only still there, while the townsperson answers someone else first
        if (payload.parts.isEmpty() && payload.choices.isEmpty() && !payload.closing) {
            silence = 0
            return
        }
        waitingSince = -1
        silence = 0
        serial = payload.serial
        closing = payload.closing
        if (closing || payload.parts.isNotEmpty()) dropChoices(-1)
        if (payload.parts.isNotEmpty()) {
            // What's there rises away, and the new words come once it's gone (and the panel is up)
            leave()
            val left = leaving?.let { max(0, LEAVE - (ticks - it.at)) } ?: 0
            said = payload.parts.map { Said(it, !heard(it)) }
            runs = payload.parts.withIndex().flatMap { (i, part) ->
                listOfNotNull(Run(i, false, part.text), part.detail?.let { Run(i, true, it) })
            }
            runs.forEach(::wrap)
            run = 0
            doneAt = -1
            // The first part begins now, as they say it; its words after the shortest of pauses
            runs.first().startAt = ticks
            shown = 0f
            wait = max(MIN_LEAD, max(OPEN_LEAD - ticks, left))
        }
        choices = if (closing) emptyList() else payload.choices
        // No words with it: new answers for the words already there, or a farewell with nothing to add
        if (payload.parts.isEmpty() && (doneAt >= 0 || runs.isEmpty())) after()
    }

    /** The server ended the talk ([TalkClosePayload]): the screen sinks away, after the last words if they're being said. */
    fun shut() {
        closed = true
        // A farewell being said ends the screen itself once it's over
        if (closing && sinkAt < 0) return
        if (sinkAt < 0) sinkAt = ticks
    }

    // ---- What's said ----

    private fun wrap(r: Run) {
        r.lines = font.splitter.splitLines(r.text, textWidth, Style.EMPTY).map { it.string }
        r.joined = r.lines.joinToString("\n")
    }

    private fun speaker(): Entity? = if (open.entity < 0) null else minecraft.level?.getEntity(open.entity)

    /** Whether the speaker is saying something aloud now (to this player or another). */
    private fun talking(speaker: Entity?): Boolean = (speaker as? Townsperson)?.speech?.isNotEmpty() == true

    /**
     * Whether [part]'s take will be heard from the speaker: it has one, this client has it in the speaker's voice (as
     * TownsfolkClient.speak finds it: the old borrow the men's takes, children the women's), and townsfolk can be heard.
     */
    private fun heard(part: TalkPart): Boolean {
        if (part.line.isEmpty() || part.ticks <= 0) return false
        val p = speaker() as? Townsperson ?: return false
        if (minecraft.options.getFinalSoundSourceVolume(SoundSource.NEUTRAL) <= 0f) return false
        val timbre = TownsfolkClient.lookOf(p)?.timbre ?: if (p.role == TownRole.CHILD) "child" else "man"
        val stand = when (timbre) {
            "old" -> "man"
            "child" -> "woman"
            else -> null
        }
        return listOfNotNull(timbre, stand).any { voice ->
            Identifier.tryBuild(Guildmark.MOD_ID, "townsfolk.voice.$voice.${part.line}")?.let { minecraft.soundManager.getSoundEvent(it) } != null
        }
    }

    /** What's on the panel rises away and fades, and nothing is being said. */
    private fun leave() {
        val lines = shownLines()
        if (lines.isNotEmpty()) leaving = Leaving(lines, scroll, ticks)
        said = emptyList()
        runs = emptyList()
        run = 0
        doneAt = -1
        scroll = 0f
        scrollLast = 0f
    }

    /** The answers go: their box fades out with [lit] (the one picked, or -1) lit as it goes. */
    private fun dropChoices(lit: Int) {
        if (choicesAt < 0) return
        gone = Gone(choiceBox(), answers, lit, ticks)
        choicesAt = -1
    }

    /** The words are over: the answers come up, or the farewell holds, or the screen waits for the server's next say. */
    private fun after() {
        when {
            closing -> if (holdUntil < 0) holdUntil = if (runs.isEmpty()) ticks else ticks + HOLD
            choices.isNotEmpty() -> showChoices()
            waitingSince < 0 -> {
                waitingSince = ticks
                silence = 0
            }
        }
    }

    private fun showChoices() {
        choicesAt = ticks
        armed = false
        selected = choices.indexOfFirst { !it.asked }.takeIf { it >= 0 } ?: choices.lastIndex
        waitingSince = -1
    }

    /** The answers as the kit's box shows them: the asked ones dimmed. */
    override fun rows(): List<Row> = answers

    override fun pick(index: Int) {
        val choice = choices.getOrNull(index) ?: return
        Ui.select()
        ClientPacketDistributor.sendToServer(TalkPickPayload(open.entity, serial, choice.id))
        dropChoices(index)
        leave()
        waitingSince = ticks
        silence = 0
    }

    /**
     * Enter, Space or a click while they talk: all of the part being said at once; then the next part straight away,
     * or the answers. Over the last words, it closes the screen.
     */
    override fun advance() {
        if (holdUntil >= 0 || sinkAt >= 0) {
            onClose()
            return
        }
        if (doneAt >= 0 || runs.isEmpty()) return
        val r = runs.getOrNull(run)
        when {
            r == null -> {
                doneAt = ticks
                after()
            }
            r.begun || r.detail -> finish(r.part)
            else -> {
                r.startAt = ticks
                wait = 0
            }
        }
    }

    /** All of part [index]'s words at once, and the next part in its turn. */
    private fun finish(index: Int) {
        while (run < runs.size && runs[run].part == index) {
            runs[run].begun = true
            run++
        }
        shown = 0f
        wait = 0
        val s = said[index]
        if (s.at < 0) s.at = ticks
        done(s)
    }

    /** [s]'s words are all shown: the next part begins once its take is over and a breath more. */
    private fun done(s: Said) {
        s.typedAt = ticks
        val next = runs.getOrNull(run) ?: return
        next.startAt = if (s.part.ticks > 0) max(s.at + s.part.ticks + PART_GAP, ticks + BREATH) else ticks + PART_GAP
    }

    // ---- Every tick ----

    override fun tick() {
        ticks++
        scrollLast = scroll
        val player = minecraft.player
        val speaker = speaker()
        // Walked off, struck, or the townsperson is gone: the talk is over. Poison, hunger or fire don't count
        val struck = struck(player)
        val lost = open.entity >= 0 && (speaker == null || !speaker.isAlive || player != null && speaker.distanceToSqr(player) > REACH * REACH)
        if (player == null || struck || lost) {
            onClose()
            return
        }
        // The lines roll up to make room as they come, and what went away is gone
        val target = max(0, shownLines().size - MAX_LINES).toFloat()
        scroll = if (abs(target - scroll) < 0.02f) target else scroll + (target - scroll) * SCROLL_EASE
        if (leaving.let { it != null && ticks - it.at >= LEAVE }) leaving = null
        if (gone.let { it != null && ticks - it.at >= FADE_OUT }) gone = null
        if (sinkAt >= 0) {
            if (ticks - sinkAt >= SINK) onClose()
            return
        }
        if (holdUntil >= 0) {
            if (ticks >= holdUntil) sinkAt = ticks
            return
        }
        arm()
        pen()
        // Waiting on the server for nothing: it's over, though not while the townsperson is answering someone else
        if (waitingSince >= 0) {
            if (!talking(speaker)) silence++
            if (silence >= SILENCE) onClose()
        }
    }

    /** One tick of the words: the pen across the run that's typing, each run beginning in its turn. */
    private fun pen() {
        if (doneAt >= 0 || runs.isEmpty()) return
        val r = runs.getOrNull(run)
        if (r == null) {
            // Every word is shown: the last part is over once its take is
            val last = said.last()
            if (last.part.ticks <= 0 || ticks >= last.at + last.part.ticks) {
                doneAt = ticks
                after()
            }
            return
        }
        if (!r.begun) {
            if (r.startAt < 0 || ticks < r.startAt) return
            r.begun = true
            if (!r.detail) said[r.part].at = r.startAt
            shown = 0f
        }
        val s = said[r.part]
        type(r.joined, s.blips)
        if (wait > 0 || shown < r.joined.length) return
        run++
        val next = runs.getOrNull(run)
        if (next != null && next.part == r.part) {
            // The sentence is out: its detail follows a moment later
            next.startAt = ticks
            wait = DETAIL_DELAY - 1
            return
        }
        done(s)
    }

    /** The lines on the panel, oldest first: each run's as far as it has typed. */
    private fun shownLines(): List<Shown> {
        val out = ArrayList<Shown>()
        for ((i, r) in runs.withIndex()) {
            if (i > run || i == run && !r.begun) break
            val color = if (r.detail) Ui.TEXT_SOFT else Ui.TEXT
            var left = if (i < run) Int.MAX_VALUE else shown.toInt()
            for ((j, line) in r.lines.withIndex()) {
                if (j > 0 && left <= 0) break
                out += Shown(line, color, left.coerceIn(0, line.length))
                left -= line.length + 1
            }
        }
        return out
    }

    // ---- Drawing ----

    /** The screen sinking away once it's over: 1 while it's up. */
    override fun presence(a: Float): Float = if (sinkAt < 0) 1f else 1f - ease(min(1f, (ticks + a - sinkAt) / SINK))

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val t = ticks + a
        val present = presence(a)
        val rise = ease(min(1f, t / 8f)) * present
        val y = drawPanel(graphics, rise)

        // Their name on the plate and what they are beside it, and how they know the player at the right
        val knows = knowsWord()
        val room = knows?.let { font.width(it.first) + 10 } ?: 0
        drawPlate(graphics, open.name, open.epithet, y, rise, panelX + panelWidth - PAD - room, present)
        if (knows != null) {
            val k = ((t - knowsAt - KNOWS_IN) / 6f).coerceIn(0f, 1f) * present
            if (k >= 0.05f) graphics.text(font, knows.first, panelX + panelWidth - PAD - font.width(knows.first), y + 9, faded(knows.second, k), false)
        }

        drawWords(graphics, y + TEXT_TOP, a, present)

        // More to come once this take is over: the diamond; waiting on the server: three dots
        val between = runs.getOrNull(run)?.let { !it.begun && !it.detail && run > 0 } == true
        if (between && sinkAt < 0) drawMore(graphics, y, t)
        if (waitingSince >= 0 && choicesAt < 0 && sinkAt < 0) drawDots(graphics, y, t)

        gone?.let { g ->
            val k = (1f - ease(min(1f, (t - g.at) / FADE_OUT))) * present
            if (k >= 0.05f) drawChoices(graphics, g.box, k, g.rows, g.lit) { k }
        }
        if (choicesAt >= 0) {
            val k = ease(min(1f, (t - choicesAt) / 6f)) * present
            drawChoices(graphics, choiceBox(), k) { i -> ease(((t - choicesAt - 2f - i * ROW_STAGGER) / 5f).coerceIn(0f, 1f)) * present }
        }
    }

    /** The words in the panel's four lines from [top]: those rising away, and these, rolling up as they come. */
    private fun drawWords(graphics: GuiGraphicsExtractor, top: Int, a: Float, alpha: Float) {
        val x = panelX + PAD
        val bottom = top + MAX_LINES * LINE
        graphics.enableScissor(panelX + 4, top - 2, panelX + panelWidth - 4, bottom)
        leaving?.let { l ->
            val k = ease(min(1f, (ticks + a - l.at) / LEAVE))
            for ((i, line) in l.lines.withIndex()) {
                val y = top + ((i - l.scroll) * LINE - k * RISE).roundToInt()
                line(graphics, line, x, y, top, alpha * (1f - k))
            }
        }
        val scrolled = Mth.lerp(a, scrollLast, scroll)
        for ((i, line) in shownLines().withIndex()) {
            val y = top + ((i - scrolled) * LINE).roundToInt()
            if (y < bottom) line(graphics, line, x, y, top, alpha)
        }
        graphics.disableScissor()
    }

    /** One [line] at [y], fading as it rolls up past the panel's [top]. */
    private fun line(graphics: GuiGraphicsExtractor, line: Shown, x: Int, y: Int, top: Int, alpha: Float) {
        val k = alpha * (1f + (y - top).toFloat() / LINE).coerceIn(0f, 1f)
        if (k < 0.05f || line.letters <= 0) return
        graphics.text(font, if (line.letters >= line.text.length) line.text else line.text.substring(0, line.letters), x, y, faded(line.color, k), false)
    }

    /** Three dots pulsing while the townsperson thinks: where the words begin if there are none, else in the corner. */
    private fun drawDots(graphics: GuiGraphicsExtractor, y: Int, t: Float) {
        val come = ((t - waitingSince - DOTS_AFTER) / 4f).coerceIn(0f, 1f)
        if (come < 0.05f) return
        val empty = runs.isEmpty()
        val x = if (empty) panelX + PAD + 1 else panelX + panelWidth - PAD - 12
        val top = if (empty) y + TEXT_TOP + 4 else y + PANEL_HEIGHT - 13
        for (i in 0 until 3) {
            val pulse = max(0f, sin(t * 0.3f - i * 0.9f))
            graphics.fill(x + i * 4, top, x + i * 4 + 2, top + 2, faded(Ui.TEXT_SOFT, come * (0.3f + 0.7f * pulse)))
        }
    }

    /** The word for how they know the player, and its colour; null for a stranger. */
    private fun knowsWord(): Pair<Component, Int>? = when (open.knows) {
        TalkOpenPayload.KNOWS -> Component.translatable("gui.guildmark.talk.knows") to Ui.GOLD
        TalkOpenPayload.HEARD_OF -> Component.translatable("gui.guildmark.talk.heard_of") to Ui.TEXT_SOFT
        TalkOpenPayload.WARY -> Component.translatable("gui.guildmark.talk.wary") to Ui.RED
        else -> null
    }

    // ---- Sound ----

    /** The pen's tick, a little different for each townsperson, as their voice is. */
    override val blipPitch: Float = Minecraft.getInstance().level?.getEntity(open.entity)?.let { 0.92f + Math.floorMod(it.uuid.leastSignificantBits, 17L) / 100f } ?: 1f

    private companion object {
        /** Ticks after a part's take before the next part, and the least after its words if they ran over it. */
        const val PART_GAP = 6
        const val BREATH = 2

        /** Ticks after a sentence before its detail begins. */
        const val DETAIL_DELAY = 8

        /** Ticks before the first words of a talk (the panel rising), and the least before any say's words. */
        const val OPEN_LEAD = 6
        const val MIN_LEAD = 1

        /** Ticks words take to rise away, and how far they rise. */
        const val LEAVE = 6
        const val RISE = 8f

        /** Ticks the answers take to fade after a pick, and between one answer and the next easing in. */
        const val FADE_OUT = 6
        const val ROW_STAGGER = 1.5f

        /** How far the lines close on their place each tick as they roll up. */
        const val SCROLL_EASE = 0.4f

        /** Ticks the last words hold before the screen sinks away, and the sinking. */
        const val HOLD = 30
        const val SINK = 8

        /** Ticks of waiting on the server for nothing before the screen gives up. */
        const val SILENCE = 100

        /** Ticks of waiting before the dots show, so a quick answer doesn't flash them. */
        const val DOTS_AFTER = 4

        /** Ticks after the plate before the word for how they know the player fades in. */
        const val KNOWS_IN = 6
    }
}

/** The talk screen's side of the payloads: the server opens it, says what's said and ends it; the screen sends the rest. */
object TalkClient {
    /** The server began a talk ([TalkOpenPayload]): the screen opens, or the one up with the same speaker starts afresh. */
    fun open(payload: TalkOpenPayload) {
        val minecraft = Minecraft.getInstance()
        val open = minecraft.gui.screen() as? TalkScreen
        if (open != null && open.entityId == payload.entity) {
            open.reopen(payload)
            return
        }
        minecraft.gui.setScreen(TalkScreen(payload))
    }

    /** What the speaker says ([TalkSayPayload]), for the screen that's up if it's their talk. */
    fun say(payload: TalkSayPayload) {
        val screen = Minecraft.getInstance().gui.screen() as? TalkScreen ?: return
        if (screen.entityId == payload.entity) screen.say(payload)
    }

    /** The server ended the talk with [entity] ([io.github.vaspyyy.guildmark.network.TalkClosePayload]). */
    fun close(entity: Int) {
        val screen = Minecraft.getInstance().gui.screen() as? TalkScreen ?: return
        if (screen.entityId == entity) screen.shut()
    }

    /** Whether this client's talk screen is up with [entity]: what they say shows there, not over their head. */
    fun talkingWith(entity: Int): Boolean = entity >= 0 && (Minecraft.getInstance().gui.screen() as? TalkScreen)?.entityId == entity
}
