package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/*
 * What a template gives the talk (living-world/stories/design.md 3.1, 4.5, 4.6 and 5.4), Part C: the few things only the template
 * knows, in a form that lets the twelve tales be written side by side. The talk itself (the opener, the choice on the screen, "Not now",
 * "How does it stand?", "Why?", the second player's lines, the rumours and the after-lines) is the talk's, in `TaleTopics` and
 * `TaleScript`, the same for every tale; a template adds only what is its own:
 *
 *  - which cast member asks ([TaleVoice.asker]),
 *  - the grey words that go with each line it says, from the numbers it keeps ([TaleVoice.detail]),
 *  - what it says at a yes, if that is not the talk's own thanks ([TaleVoice.yes]),
 *  - what a cast member offers the player to say or do at the step the tale is at ([TaleVoice.options]).
 *
 * A template takes these up by implementing [TaleVoice] besides [Template] (as it implements `TaleWords.Names` to choose its words),
 * or, for a template whose file is not the talk's to touch (the probe's), by being registered in [TaleVoices]. A template that
 * wants a conversation wholly its own returns it from `Template.script` and `TaleScript` is not used for that cast member.
 *
 * The lines themselves are the families of the voice table (8.1): `tale.<id>.ask` (the request, said as the opener and again when the
 * player asks about it), `tale.<id>.passing` (said in passing), `tale.<id>.rumour` (what anyone who has heard says),
 * `tale.<id>.stand` or `tale.<id>.stand_<step>` ("How does it stand?"), and whatever else an option or the yes names. A family is
 * voiced when its takes are recorded and typed when they are not (`say.guildmark.talk.tale.<id>.<beat>_<n>`); the grey detail of a
 * beat is `say.guildmark.talk.detail.tale.<id>.<beat>`, put together by [TaleVoice.detail] with [TaleLines.facts].
 */

/**
 * One line a cast member says: [family] (`tale.<id>.<beat>`, or one every tale shares, `tale.ask.later`) with the grey [detail] after it
 * (Speech format, built with [TaleLines.facts]; null for none). [instead] is the family said in its place when [family] has nothing at
 * all to say, no take, no typed wording and no detail (a tale that has not written the line yet).
 */
class TaleSay(val family: String, val detail: String? = null, val instead: String? = null)

/**
 * What a cast member says back: one or two lines ([says]) made with [gesture] (null: the explaining the talk does), pointing [toward]
 * a place if it names one. [ends] closes the talk; [back] hands it back to the ordinary talk with the same person (their choices come
 * back, "Goodbye" among them). A tale that ended with the reply hands the talk back whatever [back] says.
 */
class TaleReply(
    val says: List<TaleSay>,
    val gesture: Activity? = null,
    val toward: BlockPos? = null,
    val ends: Boolean = false,
    val back: Boolean = false,
) {
    constructor(
        family: String, detail: String? = null, gesture: Activity? = null, toward: BlockPos? = null, ends: Boolean = false, back: Boolean = false,
    ) : this(listOf(TaleSay(family, detail)), gesture, toward, ends, back)

    /** This reply, and the talk handed back to the ordinary one after it. */
    fun handingBack(): TaleReply = TaleReply(says, gesture, toward, ends, true)
}

/**
 * One thing a player may say or do to a cast member at a step: its [id] (a word, unique among the options offered together), the
 * [label] as the player would put it ("I'll cover it."), and [run], which does it and answers what the cast member says back, or
 * null if it no longer applies (another player decided first: the talk then says "It's been decided."). [run] is called on the server
 * thread, only for the step the option was offered at, with the tale still open.
 */
class TaleOption(val id: String, val label: Component, val run: (TaleTurn) -> TaleReply?)

/**
 * Where a talk about a tale stands, for everything a template is asked in it: the [ask] (the level, the speaker, the player), the
 * [tale] and the [slot] the speaker is in its cast, the [template] and the [player] (null while the speaker is only talking to a
 * neighbour: a chat that tells the tale). Nothing here is kept between calls.
 */
class TaleTurn(val ask: Ask, val tale: Tale, val slot: String, val template: Template, val player: ServerPlayer?) {
    val level: ServerLevel get() = ask.level

    /** The template's id: the stem of every family and key of the tale. */
    val id: String get() = tale.template

    val step: Int get() = tale.step

    /** The market day tales stand at in the tale's capital. */
    val today: Long get() = TaleEngine.today(ask.level, tale.capital)

    /** Whole market days to the day the world settles the step (0: at tonight's close). */
    val left: Long get() = (tale.by - today).coerceAtLeast(0L)

    /** The player's part, if the tale has met them. */
    val mine: Part? get() = player?.let { tale.part(it.uuid) }

    /** The player's part, begun if the tale has not met them; null if there is no player or no room for one (a ninth). */
    fun part(): Part? = player?.let { tale.partFor(it.uuid) }

    /** What a template is given to act with, or null until the capital's places are known (seconds, after a restart): an option that changes the tale waits for it. */
    fun ctx(): Ctx? = TaleEngine.ctx(ask.level, tale.capital)

    /** Marks the saved record changed (a number, a bit or a status set by hand). */
    fun changed() = TaleData.get(ask.level).changed()

    /** The family `tale.<id>.<beat>`. */
    fun family(beat: String): String = "tale.$id.$beat"

    /** A line of the tale's own family [beat] with the grey [detail]. */
    fun say(beat: String, detail: String? = null): TaleSay = TaleSay(family(beat), detail)

    /** The cast member in [slot]'s name, or "". */
    fun name(slot: String): String = tale.cast(slot)?.name.orEmpty()

    /** The player's name, or "". */
    val who: String get() = player?.gameProfile?.name.orEmpty()
}

/**
 * What a template may implement, besides [Template], to speak in the talk. Every function has an answer for a tale that has said
 * nothing of it: a tale with no [detail] is its families alone, one with no [options] is the talk's "Not now", "How does it stand?" and
 * "Why?" only.
 */
interface TaleVoice {
    /** The cast slot that asks (says the opener, and is who "About the note..." is about): the first of the cast unless the template says otherwise. */
    fun asker(t: Tale): String? = t.cast.firstOrNull()?.slot

    /**
     * The grey words under the line of [beat] ("ask", "passing", "rumour", "thanks", "stand"...) as the tale is now: Speech format
     * ("talk.detail.tale.note.ask|24|=Orrin Vale|say.guildmark.talk.detail.tale.when.5", made with [TaleLines.facts], so a line the
     * language lacks is left out and not shown as a key), or null for none. Every number in it is read from the tale.
     */
    fun detail(beat: String, turn: TaleTurn): String? = null

    /**
     * What the asker says the moment the player says yes, if it is a line of the tale's own (`tale.note.thanks`, "You'd stand for us?
     * Truly?"); null for the talk's general thanks (`tale.yes`). Not the line of an ending: a family that thanks the player for what
     * was done (the wagon's, the cart's) is said by the ending's scene and the after-lines, not here.
     */
    fun yes(turn: TaleTurn): TaleSay? = null

    /** What the cast member in [TaleTurn.slot] offers the player at the step the tale is at, at most four: the choices of a CHOOSE, the sums of a GIVE, the tools of a MEET, the note of a DO. */
    fun options(turn: TaleTurn): List<TaleOption> = listOf()
}

/** The voices of the templates whose files are not the talk's to touch, and the lookup that finds a template's whichever way it has one. */
object TaleVoices {
    private val registered = HashMap<String, TaleVoice>()

    /** Registers [voice] for the template [id], once, at setup. */
    fun register(id: String, voice: TaleVoice) {
        registered[id] = voice
    }

    /** [template]'s voice: the template itself if it implements [TaleVoice], else the one registered for its id. */
    fun of(template: Template): TaleVoice? = (template as? TaleVoice) ?: registered[template.id]
}

/**
 * Calls into a template's voice, which may be wrong: what it throws is logged, once for each template and each kind of call, and the
 * talk goes on without that line (a tale's talk never ends a tale, 15.2: only the engine's own calls count against it).
 */
internal object TaleVoiceGuard {
    private val told = HashSet<String>()

    fun <R> run(template: String, what: String, fallback: R, body: () -> R): R = try {
        body()
    } catch (e: Exception) {
        if (told.add("$template/$what")) Guildmark.LOGGER.warn("The {} tale's voice threw in {}: its talk goes on without it", template, what, e)
        fallback
    }

    fun forget() = told.clear()
}

/** The helpers an option is made of: Marks put into a GIVE, and the note of a DO (4.5, 4.6, 7.5). */
object TaleOptions {
    /** How a try at [put] went: it was done, the player has not that many Marks, or the tale cannot hear it yet (no places, no part, no sense). */
    enum class Put { DONE, SHORT, LATER }

    /** How a try at [handIn] went: no note carried, not done yet, handed in, handed in too late, or the tale cannot hear it yet. */
    enum class Hand { NONE, NOT_YET, DONE, EXPIRED, LATER }

    /**
     * The player puts [marks] Marks into the tale, at its GIVE step (7.5): taken from the pack, held in their part's escrow (the engine
     * spends it when the step is met and gives it back in full if the step lapses or the tale ends), and the tale hears an `Event.Gave`.
     * The template's `heard` counts it and moves the tale on when the sum is met. Nothing is taken if the tale cannot hear it yet.
     */
    fun put(turn: TaleTurn, marks: Int): Put {
        val player = turn.player ?: return Put.LATER
        if (marks <= 0 || !Tales.live || !turn.tale.open || turn.ctx() == null) return Put.LATER
        val part = turn.part() ?: return Put.LATER
        if (TownControl.marks(player) < marks) return Put.SHORT
        TownControl.pay(player, marks)
        part.escrow += marks
        turn.changed()
        Tales.tell(turn.level, Event.Gave(turn.tale.id, player, marks))
        return Put.DONE
    }

    /** The chain the tale's note for [step] carries, `tale:<id>:<step>`. */
    fun chain(t: Tale, step: Int = t.step): String = "tale:${t.id}:$step"

    /** The tale's note for [step] that [player] carries, or null: a look through the pack, only when the talk offers the note. */
    fun carried(player: Player, t: Tale, step: Int = t.step): ItemStack? {
        val chain = chain(t, step)
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.get(ModDataComponents.QUEST_NOTE.get())?.chain == chain) return stack
        }
        return null
    }

    /** The board of the tale's capital, where its notes are given and handed in (4.6), or null while the capital cannot be found. */
    fun board(turn: TaleTurn): BlockPos? = CaravanRoutes.capital(turn.level, turn.tale.capital)?.boardAnchor

    /** The notes handed over in this session, per player, tale and step: what makes the offer "I lost the note" and not "I'll take the note". */
    private val given = HashSet<String>()

    /** Whether a note for [turn]'s step was handed to the player since the server began. */
    fun wasGiven(turn: TaleTurn): Boolean = turn.player?.let { "${it.uuid}/${chain(turn.tale, turn.step)}" in given } ?: false

    /**
     * Hands the player [note] for the tale's step (4.6): the contract is set up as the board's own (`Contracts.take` from the hall, which
     * skips the board's standing check), put in their pack, a page turns, and [said] (a message naming the tale) is told them. The note's
     * chain is [chain]. False if it could not be set up (no board, no road, no spot): the step is the template's to let go.
     */
    fun give(turn: TaleTurn, note: QuestNote, said: Component? = null): Boolean {
        val player = turn.player ?: return false
        val board = board(turn) ?: return false
        val tagged = if (note.chain == chain(turn.tale)) note else note.copy(chain = chain(turn.tale))
        val stack = Contracts.take(tagged, turn.level, board, Direction.NORTH, player, fromHall = true) ?: return false
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        turn.level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 1.0f)
        said?.let { player.sendSystemMessage(it) }
        given.add("${player.uuid}/${chain(turn.tale, turn.step)}")
        return true
    }

    /**
     * The player hands in the tale's note for the step they carry (4.6): the board's own turn-in, which pays as ever and tells the
     * tale (`Event.Contract`). NONE if they carry none, NOT_YET if the note says it is not done (the turn-in says why in their overlay),
     * LATER if the tale cannot hear it yet (nothing is handed in), DONE if the note was taken and paid, EXPIRED if it was taken after
     * its days (it is worth nothing, and the tale heard so).
     */
    fun handIn(turn: TaleTurn): Hand {
        val player = turn.player ?: return Hand.NONE
        val stack = carried(player, turn.tale, turn.step) ?: return Hand.NONE
        val board = board(turn)
        if (board == null || turn.ctx() == null) return Hand.LATER
        val late = stack.get(ModDataComponents.CONTRACT_STATE.get())?.isExpired(turn.level.gameTime) == true
        Contracts.turnIn(stack, player, turn.level, board, null, board)
        return when {
            !stack.isEmpty -> Hand.NOT_YET
            late -> Hand.EXPIRED
            else -> Hand.DONE
        }
    }

    fun forget() = given.clear()
}
