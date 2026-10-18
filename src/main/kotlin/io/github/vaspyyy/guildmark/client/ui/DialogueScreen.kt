package io.github.vaspyyy.guildmark.client.ui

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.companion.CompanionOrderPayload
import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.faction.FactionWorkPayload
import io.github.vaspyyy.guildmark.faction.Factions
import io.github.vaspyyy.guildmark.network.AcceptChapterPayload
import io.github.vaspyyy.guildmark.network.EndTalkPayload
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.story.StoryCharacter
import net.minecraft.client.gui.GuiGraphicsExtractor
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

/**
 * Talking with a named character. The camera settles over the player's shoulder (see Cutscenes.talk),
 * the character's words type out in a panel at the foot of the screen, in their own voice where a
 * recording exists, and the player's answers come up beside it: take the job on offer, ask about
 * something, or say goodbye. Enter, Space or a click moves the talk on; the arrow keys, W and S, the
 * wheel or the number keys pick an answer. A faction's leader has more to say: a word if they were dozing, a
 * warning about their rivals, a refusal if the player isn't rated high enough or has been thrown out, and work to
 * give between chapters. A companion is asked along, told to follow, wait or go their own way, given a gift, asked
 * how things stand, and opens their next chapter only once they trust the player enough.
 *
 * The panel, the plate, the typing, the answers' box, the keys and the camera are the dialogue kit's (see
 * [DialogueKit]), which the townsfolk's talk screen shares.
 */
class DialogueScreen(private val data: OpenCharacterPayload) : DialogueKit(Component.translatable("gui.guildmark.character")) {

    /** One thing they say, and the recording of it if there is one. */
    private class Speech(val text: Component, val voice: Identifier?)

    /** Something to say back. A [topic] is a question, dimmed once it has been asked. */
    private class Choice(val label: Component, val topic: String?, val pick: () -> Unit)

    private val character = StoryCharacter.byId(data.character)

    /** Who's talking. */
    override val entityId: Int get() = data.entityId
    private val finished get() = character == null || data.chapters >= character.steps.size
    /** Characters without jobs (Maren) play a scene instead: data.chapters is the scene number. */
    private val scene get() = character != null && !character.givesJobs
    /** The scene where Maren waits at the throne for the shards. */
    private val ritual get() = scene && data.chapters == 1
    /** A faction's leader, with their own say on the talk (data.flags: Factions.GATED and the rest). */
    private val leads = character?.faction != null
    private fun flag(bit: Int): Boolean = leads && data.flags and bit != 0
    private val shut get() = flag(Factions.SHUT)
    private val gated get() = flag(Factions.GATED) || has(Companions.GATED)
    private val offering get() = character != null && !scene && !finished && !data.carrying && !shut && !gated &&
        (companion == null || has(Companions.OFFER))
    /** A companion, with their own say: met or not, with the player or not, how they feel (data.flags: Companions.STRANGER and the rest). */
    private val companion = character?.companion
    private fun has(bit: Int): Boolean = companion != null && data.flags and bit != 0
    /** Something they were told this talk (to wait, to come along): they answer, and all that's left is goodbye. */
    private var settled = false
    /** A gift handed over this talk. */
    private var gave = false
    /** The player has asked for work this talk (it's in their pack now). */
    private var workTaken = false

    private var speeches: List<Speech> = emptyList()
    private var speech = 0
    /** The current speech cut into pages of lines that fit the panel. */
    private var pages: List<List<String>> = emptyList()
    private var page = 0
    private var choices: List<Choice> = emptyList()
    private val heard = HashSet<String>()
    private var voice: SoundInstance? = null
    private var ended = false

    override fun init() {
        super.init()
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
        if (companion != null) return companionOpening()
        if (scene) return numbered("scene${data.chapters}", ".p").map(::line)
        if (shut) return listOf(line("expelled"))
        val woken = if (flag(Factions.WOKEN)) listOf(line("woken")) else emptyList()
        val said = when {
            data.finale -> listOf(line("finale"))
            finished -> listOf(line("done"))
            data.carrying -> listOf(line("waiting"))
            gated -> (if (data.chapters == 0) listOf(line("intro")) else emptyList()) + notYet(character)
            data.chapters == 0 -> listOf(line("intro"), line("step0"))
            else -> listOf(line("step${data.chapters}"))
        }
        // Before a job against their rivals, a word to someone who runs with them
        val rival = if (offering && flag(Factions.RIVAL)) listOf(line("rival")) else emptyList()
        return woken + said + rival
    }

    /** A leader's chapter wants an adventurer rated higher: they say what rank. */
    private fun notYet(c: StoryCharacter): Speech {
        val needed = AdventurerRank.entries[(c.steps[data.chapters].rank - 1).coerceIn(0, AdventurerRank.entries.lastIndex)]
        val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "voice.${c.id}.gated")
        return Speech(
            Component.translatable("character.guildmark.${c.id}.gated", needed.letter),
            id.takeIf { minecraft.soundManager.getSoundEvent(it) != null },
        )
    }

    /** What a companion opens with: the state of things between them and the player. */
    private fun companionOpening(): List<Speech> = when {
        has(Companions.COLD) -> listOf(line("cold"))
        has(Companions.LEFT) -> listOf(line("return"))
        data.finale -> listOf(line("finale"))
        // A chapter just handed in: how it sits with them, and the next if they're ready to ask
        has(Companions.TURNED_IN) -> listOfNotNull(lineIf("step${data.chapters - 1}.done")) + (if (offering) listOf(line("step${data.chapters}")) else emptyList())
        has(Companions.STRANGER) -> listOf(line("intro"))
        data.carrying -> listOf(line("waiting"))
        offering -> listOf(line("step${data.chapters}"))
        finished -> listOf(line("done"))
        else -> listOf(line(numbered("hello").randomOrNull() ?: "done"))
    }

    private fun lineIf(key: String): Speech? = line(key).takeIf { Language.getInstance().has("character.guildmark.${character!!.id}.$key") }

    private fun companionChoices(c: StoryCharacter): List<Choice> {
        val list = ArrayList<Choice>()
        val farewell = Choice(Component.translatable(if (offering && !settled) "gui.guildmark.character.later" else "gui.guildmark.character.farewell"), null) { onClose() }
        if (settled) return listOf(farewell)
        fun order(label: String, order: Int, reply: String) {
            list += Choice(Component.translatable(label), null) { tell(order, reply) }
        }
        val swap = has(Companions.SWAP)
        when {
            has(Companions.COLD) -> return listOf(farewell)
            has(Companions.LEFT) -> order("gui.guildmark.companion.return", CompanionOrderPayload.RETURN, "follow")
            has(Companions.STRANGER) -> {
                order(if (swap) "gui.guildmark.companion.join_swap" else "gui.guildmark.companion.join", CompanionOrderPayload.JOIN, "join")
                list += Choice(Component.translatable("gui.guildmark.companion.decline"), null) { answer("decline") }
            }
            else -> {
                if (offering) list += Choice(Component.translatable("gui.guildmark.character.accept"), null) { accept() }
                else if (gated && !data.carrying) list += Choice(Component.translatable("gui.guildmark.companion.favour"), "favour") { heard += "favour"; say(listOf(line("gated"))) }
                when {
                    has(Companions.FOLLOWING) -> {
                        order("gui.guildmark.companion.wait", CompanionOrderPayload.WAIT, "wait")
                        order("gui.guildmark.companion.part", CompanionOrderPayload.PART, "part")
                    }
                    has(Companions.WAITING) -> {
                        order("gui.guildmark.companion.follow", CompanionOrderPayload.FOLLOW, "follow")
                        order("gui.guildmark.companion.part", CompanionOrderPayload.PART, "part")
                    }
                    has(Companions.PARTED) ->
                        order(if (swap) "gui.guildmark.companion.rejoin_swap" else "gui.guildmark.companion.rejoin", CompanionOrderPayload.REJOIN, "rejoin")
                }
                if (has(Companions.GIFT) && !gave) {
                    val held = minecraft.player?.mainHandItem?.hoverName ?: Component.empty()
                    list += Choice(Component.translatable("gui.guildmark.companion.gift", held), null) {
                        gave = true
                        if (has(Companions.GIFTED)) answer("gift_again", settle = false)
                        else tell(CompanionOrderPayload.GIFT, "gift", settle = false)
                    }
                }
                val band = data.flags shr Companions.BAND_SHIFT and 7
                list += Choice(Component.translatable("gui.guildmark.companion.standing"), "standing") { heard += "standing"; say(listOf(line("standing.$band"))) }
            }
        }
        for (topic in c.topics) list += Choice(c.line("topic.$topic"), topic) { ask(topic) }
        list += farewell
        return list
    }

    /** The player tells their companion something ([order]): they answer with [reply]. */
    private fun tell(order: Int, reply: String, settle: Boolean = true) {
        ClientPacketDistributor.sendToServer(CompanionOrderPayload(data.entityId, order))
        answer(reply, settle)
    }

    private fun answer(reply: String, settle: Boolean = true) {
        if (settle) settled = true
        choices = choices()
        say(listOf(line(reply)))
    }

    private fun choices(): List<Choice> {
        val c = character ?: return emptyList()
        if (companion != null) return companionChoices(c)
        val list = ArrayList<Choice>()
        if (ritual) list += Choice(Component.translatable("gui.guildmark.character.ritual"), null) { accept() }
        else if (offering) {
            val label = if (leads && data.chapters == 0) "gui.guildmark.character.join" else "gui.guildmark.character.accept"
            list += Choice(Component.translatable(label), null) { accept() }
        }
        if (!shut) for (topic in c.topics) list += Choice(c.line("topic.$topic"), topic) { ask(topic) }
        if (flag(Factions.WORK) && !workTaken) list += Choice(Component.translatable("gui.guildmark.character.work"), null) { work() }
        val leave = when {
            ritual -> "gui.guildmark.character.not_yet"
            offering -> "gui.guildmark.character.later"
            else -> "gui.guildmark.character.farewell"
        }
        list += Choice(Component.translatable(leave), null) { onClose() }
        return list
    }

    /** The answers as the kit's box shows them: a question is dimmed once it has been asked. */
    override fun rows(): List<Row> = choices.map { Row(it.label, it.topic != null && it.topic in heard) }

    private fun ask(topic: String) {
        heard += topic
        say(numbered("topic.$topic").map(::line))
    }

    private fun accept() {
        ClientPacketDistributor.sendToServer(AcceptChapterPayload(data.entityId))
        onClose()
    }

    /** A leader's work between chapters: they hand it over with a word, and the talk goes on. */
    private fun work() {
        workTaken = true
        ClientPacketDistributor.sendToServer(FactionWorkPayload(data.entityId))
        choices = choices()
        say(listOf(line("work")))
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
        val struck = struck(player)
        if (character == null || player == null || speaker == null || !speaker.isAlive || speaker.distanceToSqr(player) > REACH * REACH || struck) {
            onClose()
            return
        }
        arm()
        if (type(pageText(), voice == null) && choicesAt < 0 && lastPage()) showChoices()
    }

    private fun showChoices() {
        choicesAt = ticks
        armed = false
        // Never the ritual by default: it can't be taken back
        selected = if (ritual) choices.indexOfFirst { it.topic != null && it.topic !in heard }.takeIf { it >= 0 } ?: choices.lastIndex
            else choices.indexOfFirst { it.topic == null || it.topic !in heard }.coerceAtLeast(0)
    }

    /** Shows the rest of the page, or turns to the next. */
    override fun advance() {
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

    override fun pick(index: Int) {
        val choice = choices.getOrNull(index) ?: return
        Ui.select()
        choice.pick()
    }

    // ---- Sound ----

    override val blipPitch = when (character) {
        StoryCharacter.WREN -> 1.25f
        StoryCharacter.TOBIN -> 0.78f
        StoryCharacter.ILSA -> 1.05f
        StoryCharacter.PIP -> 1.35f
        StoryCharacter.MAREN -> 0.9f
        StoryCharacter.IDRIS -> 0.85f
        StoryCharacter.CORRA -> 0.95f
        StoryCharacter.ODILE -> 1.1f
        StoryCharacter.BRAM -> 0.74f
        StoryCharacter.YSOLDE -> 1.15f
        StoryCharacter.KESTREL -> 1.06f
        StoryCharacter.LUCAN -> 0.95f
        null -> 1f
    }

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

    // ---- Drawing ----

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val c = character ?: return
        val t = ticks + a
        val rise = ease(min(1f, t / 8f))
        val y = drawPanel(graphics, rise)

        // Their name on a plate across the panel's top edge, and what they're known as beside it
        drawPlate(graphics, Component.literal(c.displayName), c.epithet, y, rise, panelX + panelWidth - PAD)

        // The words so far
        drawTyped(graphics, pages.getOrNull(page) ?: emptyList(), shown.toInt(), panelX + PAD, y + TEXT_TOP, Ui.TEXT)

        // More to come: a diamond bobbing in the corner
        if (choicesAt < 0 && wait == 0 && shown >= pageText().length && !lastPage()) drawMore(graphics, y, t)

        if (choicesAt >= 0) {
            val k = ease(min(1f, (t - choicesAt) / 6f))
            val box = choiceBox()
            drawChoices(graphics, box, k)
            if (offering) drawOffer(graphics, c, box, k)
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
}
