package io.github.vaspyyy.guildmark.client.ui

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Cutscenes
import io.github.vaspyyy.guildmark.network.AcceptChapterPayload
import io.github.vaspyyy.guildmark.network.EndTalkPayload
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.github.vaspyyy.guildmark.story.StoryCharacter
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ARGB
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Talking with a named character. The camera settles over the player's shoulder (see Cutscenes.talk),
 * the character's words type out in a panel at the foot of the screen, in their own voice where a
 * recording exists, and the player's answers come up beside it: take the job on offer, ask about
 * something, or say goodbye. Enter, Space or a click moves the talk on; the arrow keys, W and S, the
 * wheel or the number keys pick an answer.
 */
class DialogueScreen(private val data: OpenCharacterPayload) : Screen(Component.translatable("gui.guildmark.character")) {

    /** One thing they say, and the recording of it if there is one. */
    private class Speech(val text: Component, val voice: Identifier?)

    /** Something to say back. A [topic] is a question, dimmed once it has been asked. */
    private class Choice(val label: Component, val topic: String?, val pick: () -> Unit)

    private val character = StoryCharacter.byId(data.character)

    /** Who's talking. */
    val entityId: Int get() = data.entityId
    private val finished get() = character == null || data.chapters >= character.steps.size
    /** Characters without jobs (Maren) play a scene instead: data.chapters is the scene number. */
    private val scene get() = character != null && !character.givesJobs
    /** The scene where Maren waits at the throne for the shards. */
    private val ritual get() = scene && data.chapters == 1
    private val offering get() = character != null && !scene && !finished && !data.carrying

    private var speeches: List<Speech> = emptyList()
    private var speech = 0
    /** The current speech cut into pages of lines that fit the panel. */
    private var pages: List<List<String>> = emptyList()
    private var page = 0
    /** Letters of this page shown so far (a line break counts as one), and ticks before the next. */
    private var shown = 0f
    private var wait = 0
    private var choices: List<Choice> = emptyList()
    private val heard = HashSet<String>()
    private var selected = 0
    /** When the answers came up, or -1 while the character is still talking. */
    private var choicesAt = -1
    /**
     * Answers can't be picked until the key or button used to skip to them has been let go (held keys
     * repeat), so skipping fast through the last line can't pick one by accident.
     */
    private var armed = false
    private var ticks = 0
    private var voice: SoundInstance? = null
    private var ended = false
    /** The player's hurt flash last tick, to notice a new hit. */
    private var lastHurt = -1

    private var panelX = 0
    private var panelY = 0
    private var panelWidth = 0
    private val textWidth get() = panelWidth - PAD * 2 - 10

    override fun init() {
        panelWidth = min(width - 24, 400)
        panelX = (width - panelWidth) / 2
        panelY = height - PANEL_HEIGHT - 10
        if (speeches.isEmpty()) {
            choices = choices()
            say(opening())
        } else {
            // A resize: the same words, wrapped to the new width
            pages = paginate(speeches[speech].text)
            page = min(page, pages.lastIndex)
            shown = min(shown, pageText().length.toFloat())
        }
    }

    override fun added() {
        minecraft.level?.getEntity(data.entityId)?.let { Cutscenes.talk(it, this) }
    }

    override fun removed() {
        stopVoice()
        if (!ended) {
            ended = true
            ClientPacketDistributor.sendToServer(EndTalkPayload(data.entityId))
        }
    }

    // ---- What's said ----

    private fun line(key: String): Speech {
        val c = character!!
        val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "voice.${c.id}.$key")
        return Speech(c.line(key), id.takeIf { minecraft.soundManager.getSoundEvent(it) != null })
    }

    /** The keys under [prefix] (prefix.0, prefix.1, ...) that the language file has. */
    private fun numbered(prefix: String, sep: String = "."): List<String> {
        val c = character ?: return emptyList()
        val language = Language.getInstance()
        return (0 until 6).map { "$prefix$sep$it" }.filter { language.has("character.guildmark.${c.id}.$it") }
    }

    private fun opening(): List<Speech> {
        if (character == null) return emptyList()
        if (scene) return numbered("scene${data.chapters}", ".p").map(::line)
        return when {
            data.finale -> listOf(line("finale"))
            finished -> listOf(line("done"))
            data.carrying -> listOf(line("waiting"))
            data.chapters == 0 -> listOf(line("intro"), line("step0"))
            else -> listOf(line("step${data.chapters}"))
        }
    }

    private fun choices(): List<Choice> {
        val c = character ?: return emptyList()
        val list = ArrayList<Choice>()
        if (ritual) list += Choice(Component.translatable("gui.guildmark.character.ritual"), null) { accept() }
        else if (offering) list += Choice(Component.translatable("gui.guildmark.character.accept"), null) { accept() }
        for (topic in c.topics) list += Choice(c.line("topic.$topic"), topic) { ask(topic) }
        val leave = when {
            ritual -> "gui.guildmark.character.not_yet"
            offering -> "gui.guildmark.character.later"
            else -> "gui.guildmark.character.farewell"
        }
        list += Choice(Component.translatable(leave), null) { onClose() }
        return list
    }

    private fun ask(topic: String) {
        heard += topic
        say(numbered("topic.$topic").map(::line))
    }

    private fun accept() {
        ClientPacketDistributor.sendToServer(AcceptChapterPayload(data.entityId))
        onClose()
    }

    private fun say(list: List<Speech>) {
        speeches = list.ifEmpty { listOf(Speech(Component.empty(), null)) }
        start(0)
    }

    private fun start(index: Int) {
        speech = index
        pages = paginate(speeches[index].text)
        page = 0
        shown = 0f
        wait = if (index == 0) 6 else 3
        choicesAt = -1
        stopVoice()
        voice = speeches[index].voice?.let(::playVoice)
    }

    private fun paginate(text: Component): List<List<String>> =
        font.splitter.splitLines(text, textWidth, Style.EMPTY).map { it.string }.chunked(MAX_LINES).ifEmpty { listOf(listOf("")) }

    private fun pageText(): String = pages.getOrNull(page)?.joinToString("\n") ?: ""

    private fun lastPage(): Boolean = speech == speeches.lastIndex && page == pages.lastIndex

    // ---- Every tick ----

    override fun tick() {
        ticks++
        val player = minecraft.player
        val speaker = minecraft.level?.getEntity(data.entityId)
        // Walked off, struck, or the character is gone: the talk is over. Poison, hunger or fire don't count
        val blow = player?.getLastDamageSource(2)
        val struck = player != null && lastHurt >= 0 && player.hurtTime > lastHurt && blow != null && (blow.entity != null || blow.directEntity != null)
        lastHurt = player?.hurtTime ?: 0
        if (character == null || player == null || speaker == null || !speaker.isAlive || speaker.distanceToSqr(player) > 144.0 || struck) {
            onClose()
            return
        }
        if (choicesAt >= 0 && !armed && ticks - choicesAt >= CHOICE_GUARD && !skipHeld()) armed = true
        if (wait > 0) {
            wait--
            return
        }
        val text = pageText()
        if (shown >= text.length) {
            if (choicesAt < 0 && lastPage()) showChoices()
            return
        }
        val from = shown.toInt()
        shown = min(text.length.toFloat(), shown + SPEED)
        // The letters just shown: a tick of the pen for every few (when no one's speaking), a breath after each sentence
        for (i in from until shown.toInt()) {
            val ch = text[i]
            if (voice == null && i % 4 == 0 && !ch.isWhitespace()) blip()
            val next = text.getOrNull(i + 1)
            if (next != null && !next.isWhitespace()) continue
            val pause = when (ch) {
                '.', '!', '?' -> 6
                ',', ';', ':' -> 2
                else -> 0
            }
            if (pause > 0 && next != null) {
                shown = (i + 1).toFloat()
                wait = pause
                break
            }
        }
    }

    private fun showChoices() {
        choicesAt = ticks
        armed = false
        // Never the ritual by default: it can't be taken back
        selected = if (ritual) choices.indexOfFirst { it.topic != null && it.topic !in heard }.takeIf { it >= 0 } ?: choices.lastIndex
            else choices.indexOfFirst { it.topic == null || it.topic !in heard }.coerceAtLeast(0)
    }

    private fun skipHeld(): Boolean =
        InputConstants.isKeyDown(InputConstants.KEY_RETURN) || InputConstants.isKeyDown(InputConstants.KEY_SPACE) ||
            InputConstants.isKeyDown(InputConstants.KEY_NUMPADENTER) || minecraft.mouseHandler.isLeftPressed

    /** Shows the rest of the page, or turns to the next. */
    private fun advance() {
        val text = pageText()
        when {
            shown < text.length -> {
                shown = text.length.toFloat()
                wait = 0
            }
            page < pages.lastIndex -> {
                page++
                shown = 0f
                wait = 2
            }
            speech < speeches.lastIndex -> start(speech + 1)
        }
    }

    private fun pick(index: Int) {
        val choice = choices.getOrNull(index) ?: return
        Ui.select()
        choice.pick()
    }

    private fun move(by: Int) {
        if (choices.isEmpty()) return
        selected = Math.floorMod(selected + by, choices.size)
        Ui.hover()
    }

    // ---- Sound ----

    private val blipPitch = when (character) {
        StoryCharacter.WREN -> 1.25f
        StoryCharacter.TOBIN -> 0.78f
        StoryCharacter.ILSA -> 1.05f
        StoryCharacter.PIP -> 1.35f
        StoryCharacter.MAREN -> 0.9f
        null -> 1f
    }

    private fun blip() = Ui.play(ModSounds.UI_TEXT.get(), blipPitch + (Math.random().toFloat() - 0.5f) * 0.08f, 0.2f)

    /** The line spoken aloud, or null if it can't be heard (voices muted), so the pen ticks instead. */
    private fun playVoice(id: Identifier): SoundInstance? {
        val instance = SimpleSoundInstance(
            id, SoundSource.VOICE, 1f, 1f, SoundInstance.createUnseededRandom(),
            false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
        )
        return instance.takeIf { minecraft.soundManager.play(it) == SoundEngine.PlayResult.STARTED }
    }

    private fun stopVoice() {
        voice?.let { minecraft.soundManager.stop(it) }
        voice = null
    }

    // ---- Input ----

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isEscape()) return super.keyPressed(event)
        val key = event.key()
        if (choicesAt < 0) {
            if (!event.isSelection()) return super.keyPressed(event)
            advance()
            return true
        }
        when {
            event.isUp() || key == InputConstants.KEY_W -> move(-1)
            event.isDown() || key == InputConstants.KEY_S -> move(1)
            event.isSelection() -> if (armed) pick(selected)
            key in InputConstants.KEY_1..InputConstants.KEY_9 -> if (armed) pick(key - InputConstants.KEY_1)
            else -> return super.keyPressed(event)
        }
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick)
        if (choicesAt < 0) advance()
        else if (armed) rowAt(event.x(), event.y()).takeIf { it >= 0 }?.let(::pick)
        return true
    }

    override fun mouseMoved(x: Double, y: Double) {
        if (choicesAt < 0) return
        val row = rowAt(x, y)
        if (row >= 0 && row != selected) {
            selected = row
            Ui.hover()
        }
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (choicesAt < 0 || scrollY == 0.0) return false
        move(if (scrollY > 0) -1 else 1)
        return true
    }

    // ---- Layout ----

    private class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** The answers, in a panel above the right end of the speech panel. */
    private fun choiceBox(): Box {
        val w = max(150, (choices.maxOfOrNull { font.width(it.label) } ?: 0) + 34).coerceAtMost(panelWidth)
        val h = choices.size * ROW + 10
        return Box(panelX + panelWidth - w, panelY - 12 - h, w, h)
    }

    private fun rowAt(x: Double, y: Double): Int {
        val box = choiceBox()
        if (x < box.x + 4 || x >= box.x + box.w - 4 || y < box.y + 5) return -1
        val row = ((y - box.y - 5) / ROW).toInt()
        return if (row in choices.indices) row else -1
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // The world stays in view: only a shade rising behind the panel and a thin one from the top
        val k = min(1f, (ticks + a) / 8f)
        graphics.fillGradient(0, height * 11 / 20, width, height, 0, ARGB.color((170 * k).toInt(), 0, 0, 0))
        graphics.fillGradient(0, 0, width, 36, ARGB.color((100 * k).toInt(), 0, 0, 0), 0)
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val c = character ?: return
        val t = ticks + a
        val rise = ease(min(1f, t / 8f))
        val y = panelY + ((1f - rise) * 14f).roundToInt()

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL, panelX, y, panelWidth, PANEL_HEIGHT, ARGB.white(rise))

        // Their name on a plate across the panel's top edge, and what they're known as beside it
        val name = Ui.heading(Component.literal(c.displayName))
        val plateWidth = font.width(name) + 20
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.NAME_PLATE, panelX + 10, y - 7, plateWidth, 15, ARGB.white(rise))
        graphics.text(font, name, panelX + 20, y - 4 + Ui.HEADING_RISE, Ui.GOLD_BRIGHT, true)
        val epithetX = panelX + 10 + plateWidth + 8
        val room = panelX + panelWidth - PAD - epithetX
        if (room > 30) {
            val epithet = font.substrByWidth(c.epithet, room)
            graphics.text(font, Language.getInstance().getVisualOrder(epithet), epithetX, y + 9, Ui.TEXT_FAINT, false)
        }

        // The words so far
        var left = shown.toInt()
        for ((i, row) in (pages.getOrNull(page) ?: emptyList()).withIndex()) {
            if (left <= 0) break
            graphics.text(font, if (left >= row.length) row else row.substring(0, left), panelX + PAD, y + TEXT_TOP + i * LINE, Ui.TEXT, false)
            left -= row.length + 1
        }

        // More to come: a diamond bobbing in the corner
        if (choicesAt < 0 && wait == 0 && shown >= pageText().length && !lastPage()) {
            val bob = (sin(t * 0.25f) * 1.5f).roundToInt()
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND_LARGE, panelX + panelWidth - PAD - 4, y + PANEL_HEIGHT - 16 + bob, 7, 7)
        }

        if (choicesAt >= 0) {
            val k = ease(min(1f, (t - choicesAt) / 6f))
            val box = choiceBox()
            drawChoices(graphics, box, k)
            if (offering) drawOffer(graphics, c, box, k)
        }
    }

    private fun drawChoices(graphics: GuiGraphicsExtractor, box: Box, k: Float) {
        val x = box.x + ((1f - k) * 12f).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL, x, box.y, box.w, box.h, ARGB.white(k))
        for ((i, choice) in choices.withIndex()) {
            val rowY = box.y + 5 + i * ROW
            val lit = i == selected
            if (lit) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x + 4, rowY, box.w - 8, ROW, ARGB.white(k))
            val asked = choice.topic != null && choice.topic in heard
            val color = when {
                lit -> Ui.GOLD_BRIGHT
                asked -> Ui.TEXT_FAINT
                else -> Ui.TEXT
            }
            graphics.text(font, "${i + 1}", x + 10, rowY + 4, if (lit) Ui.GOLD else Ui.TEXT_FAINT, false)
            graphics.text(font, choice.label, x + 22, rowY + 4, color, false)
        }
    }

    /** The job they're offering, as a card above the left end of the speech panel. */
    private fun drawOffer(graphics: GuiGraphicsExtractor, c: StoryCharacter, choiceBox: Box, k: Float) {
        val note = c.note(data.chapters)
        val heading = Ui.heading("gui.guildmark.dialogue.chapter", data.chapters + 1, c.steps.size)
        val rows = listOf(note.title() to Ui.TEXT, note.rankLine() to Ui.TEXT_SOFT, note.rewardLine() to Ui.GOLD, note.deadlineLine() to Ui.TEXT_FAINT)
        val w = max(font.width(heading), rows.maxOf { font.width(it.first) }) + 24
        val h = 22 + rows.size * 11 + 6
        // Beside the answers if there's room, above them if not
        val beside = panelX + w + 6 <= choiceBox.x
        val x = panelX - ((1f - k) * 12f).roundToInt()
        val y = if (beside) panelY - 12 - h else choiceBox.y - 6 - h
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL, x, y, w, h, ARGB.white(k))
        Ui.headingAt(graphics, font, heading, x + 12, y + 8)
        for ((i, row) in rows.withIndex()) graphics.text(font, row.first, x + 12, y + 22 + i * 11, row.second, false)
    }

    private fun ease(k: Float): Float = 1f - (1f - k) * (1f - k) * (1f - k)

    override fun isPauseScreen(): Boolean = false


    /** Subtitles are drawn over this screen, not hidden under its shade. */

    override fun isInGameUi(): Boolean = true

    private companion object {
        const val PAD = 14
        const val LINE = 11
        const val MAX_LINES = 4
        const val TEXT_TOP = 23
        const val PANEL_HEIGHT = TEXT_TOP + MAX_LINES * LINE + 9
        const val ROW = 16
        /** Letters shown each tick. */
        const val SPEED = 2.2f
        /** Ticks after the answers come up before one can be picked. */
        const val CHOICE_GUARD = 6
    }
}
