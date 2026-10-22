package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.Random
import java.util.UUID
import io.github.vaspyyy.guildmark.townsfolk.Household as TownHousehold

/*
 * The shapes a tale is written in (living-world/stories/design.md 1.3 to 1.6 and 10.1): the template, and the types its
 * functions take and give. Nothing here does anything. A template is one Kotlin object in `tales/templates/` that nothing
 * else in the mod names (it is listed in [Templates]); the engine (Part A2) steps it, the telling (B), the talk (C), the
 * following (D), pay and memory (E) and the memorials (F) are called through [Ctx] and read it through these types, so the
 * twelve can be written side by side.
 */

/** The eight verbs a step is one of (1.3). */
enum class Verb {
    /** A cast member wants help and says so; ends when a player says yes in the talk. */
    ASK,

    /** Go and speak to someone; ends when the script's deciding choice is made. */
    MEET,

    /** A scene the people act out for whoever is near; ends when it has played, or no one was near by the day (then settled silently). */
    SCENE,

    /** A stretch of days; ends when the day comes. */
    WAIT,

    /** A system must do something (a works finished, a wagon in, a lair cleared); ends when the listener hears it. */
    WATCH,

    /** A player puts real Marks or items in; ends when the sum is met. */
    GIVE,

    /** A real contract (a note whose chain is `tale:<id>:<step>`); ends when it is handed in. */
    DO,

    /** A talk choice among two to four, which sets a number and picks the next step; ends when one is made. */
    CHOOSE;

    val id: String get() = name.lowercase()
}

/**
 * One step of a template's table (1.3, and the "Steps" table of each tale in section 8), in the order the tale takes them.
 * [verb] is what it is, [by] the market day the world settles it if nobody has, counted from the day the tale was seeded
 * ("D5" is 5, so the first step of a tale begun on D0 with `by` 3 is settled at D3's close, 1.4), [real] whether a player's
 * deed in it can set a `did` bit (7.2), [also] the other verbs the step is on another route (the wagon's step 3 is DO, GIVE
 * or WATCH by the way chosen), [hears] the events it listens for (empty: every event of its capital reaches
 * `Template.heard`), and [needs] whether the systems it stands on are on (5.7: a step the world cannot satisfy is skipped).
 *
 * A step's tracker status is the language key `gui.guildmark.tale.<id>.status.<index>` and its banner line `.step.<index>`.
 * The record keeps only the step's index, so a template only ever appends steps to its table.
 */
class Step(
    val verb: Verb,
    val by: Int,
    val real: Boolean = false,
    val also: List<Verb> = listOf(),
    val hears: Set<Event.Kind> = setOf(),
    val needs: () -> Boolean = { true },
)

/**
 * One of a template's four endings (1.5): its [letter] (A, B or C by the player's way, W by the world's) and its [word], which
 * the keys use: the times' reason is noted as `<id>.<word>`, the rumour is `tale.<id>.<word>`, the banner line is
 * `gui.guildmark.tale.<id>.end.<word>`, the lore on the keepsake `item.guildmark.keepsake.<id>.<word>`, and the one sentence
 * every channel says is `fortune.guildmark.reason.tale.<id>.<word>` (8.0).
 */
class Ending(val letter: String, val word: String) {
    companion object {
        const val A = "A"
        const val B = "B"
        const val C = "C"
        const val W = "W"

        /** The four letters, best first. [Tale.ending] is one of them, or "" while the tale is open. */
        val LETTERS = listOf(A, B, C, W)
    }
}

/** How a cast member went, for `Template.gone` (5.6): they died, or their household left the town. */
enum class Gone {
    DIED, LEFT;

    /** "died" or "left": what [Cast.gone] keeps and the `Why` kind the engine adds. */
    val id: String get() = name.lowercase()

    companion object {
        /** The way [id] names, or null for "" and anything else. */
        fun of(id: String): Gone? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a template's `seed` is given (2.1): the records are read through [level] and the capital's [here] (its places) and market
 * [entry], on market [day]. [forced] is `tale start <template>`: the draw, the quiet, the forty-day rule, the first six days and
 * `seen` have been set aside, and the template makes up a window its trigger asks for (the lean days, the six-day strike list, a
 * festival within six days) and says so in [Planned.madeUp]; it never makes up a fact about a person (a death, a theft, a
 * household that left). [random] is seeded by the capital, the day and the template, so running the same day again picks the same
 * people (1.2). A template that finds nothing says why through [none].
 */
class Seeding(
    val level: ServerLevel,
    val here: Scenes.Here,
    val entry: Markets.Entry,
    val day: Long,
    val forced: Boolean,
    template: String,
) {
    /** The key tales go by: the capital's heart. */
    val capital: Long get() = here.key

    val random: Random = Random(here.key * -0x61C8864680B583EBL + day * 0x632BE59BD9B4E019L + template.hashCode())

    /** Why the template found nothing today, as `tale seeds` prints it ("" until [none] says). */
    var found: String = ""
        private set

    /** The template finds nothing: records [reason] for `tale seeds` and answers null, so a seed is `return c.none("no stall keeper at home")`. */
    fun none(reason: String): Planned? {
        found = reason
        return null
    }
}

/**
 * A plan a template's `seed` makes (2.2 step 7): the [cast] (a [Cast] with `held` set is held at once), the first [why], the
 * [step] to begin at, the starting numbers [n] and strings [s] (a sum fixed at the seed goes in `n`, 7.5), and in [madeUp] what a
 * forced seed made up ("" if nothing).
 */
class Planned(
    val cast: List<Cast>,
    val why: Why,
    val step: Int = 0,
    val n: List<Int> = listOf(),
    val s: List<String> = listOf(),
    val madeUp: String = "",
)

/**
 * A tale as one player's tracker row shows it (5.1), before the following (D) makes a row of it: [title] and [status] are
 * language keys with their arguments (the title is 16 letters or fewer, the status 16 or fewer with given names only), [tone]
 * how the row is inked, and [target] the place the map and the compass point at (a door, a stall, a gate, a camp; a place, never
 * a body), or null for none.
 */
class View(
    val title: String,
    val titleArgs: List<String> = listOf(),
    val status: String,
    val statusArgs: List<String> = listOf(),
    val tone: Tone = Tone.TALE,
    val target: BlockPos? = null,
) {
    /** Amber by default; red when the step's day is today or tomorrow (the engine says so); green when only the telling is left. */
    enum class Tone { TALE, LATE, DONE }
}

/**
 * What reaches a tale from the systems it listens to (10.1): one case for each hook. The engine hands one to the open tale of
 * the capital it concerns (or to the tale it names), which the template reads in `heard`. An event is only valid during the
 * call: a template never keeps the player or the entity it carries.
 */
sealed class Event {
    /** Which hook an event is, for a step's [Step.hears] and the engine's who-cares maps (10.0). */
    enum class Kind { DAY, BACK, WORKS, WAGON, HOUSEHOLD, DIED, LAIR, CAPTIVE, BOUGHT, CONTRACT, LANTERNS, KILL, KINDLED, ARRIVED, GAVE }

    abstract val kind: Kind

    /** The tale an event names (a contract's chain, an escort's, a script's), or -1 when it concerns a capital instead. */
    open val tale: Int get() = -1

    /** `Tales.day`: market day [day] was closed in [capital]. */
    class Day(val capital: Long, val day: Long) : Event() {
        override val kind: Kind get() = Kind.DAY
    }

    /** `Tales.back`: the clock was set back [days] market days. */
    class Back(val days: Long) : Event() {
        override val kind: Kind get() = Kind.BACK
    }

    /** A works did [what] ("wanted", "pledged", "funded", "begun", "staged", "finished", "eased", "removed" or "brought"): the [view], and for a pledge or a delivery the [player] and the [marks] or units. */
    class Works(val what: String, val view: WorksView, val player: UUID? = null, val marks: Int = 0) : Event() {
        override val kind: Kind get() = Kind.WORKS
    }

    /** A wagon bound for [capital] was struck on [road] (-1: the plan's own country road) by [band] (-1 for none), [waylaid] or not, on market [day]. */
    class Wagon(val capital: Long, val caravan: Long, val band: Int, val road: Int, val waylaid: Boolean, val day: Long) : Event() {
        override val kind: Kind get() = Kind.WAGON
    }

    /** A household [what] ("arrived", "left" or "returned"). */
    class Household(val what: String, val household: TownHousehold) : Event() {
        override val kind: Kind get() = Kind.HOUSEHOLD
    }

    /** [resident] died, or was put to rest. */
    class Died(val resident: Resident) : Event() {
        override val kind: Kind get() = Kind.DIED
    }

    /** Lair [lair] fell, with [heroes] about. */
    class Lair(val lair: Int, val heroes: List<UUID>) : Event() {
        override val kind: Kind get() = Kind.LAIR
    }

    /** The captive whose resident is [resident] came [home], or died. */
    class Captive(val resident: Int, val home: Boolean) : Event() {
        override val kind: Kind get() = Kind.CAPTIVE
    }

    /** [player] dealt [lots] lots of [good] at the stall of the keeper whose resident is [keeper], buying or selling, for [marks] Marks. */
    class Bought(val player: ServerPlayer, val keeper: Int, val good: Good, val buying: Boolean, val lots: Int, val marks: Int) : Event() {
        override val kind: Kind get() = Kind.BOUGHT
    }

    /** [player] handed in [note] (its chain is `tale:<tale>:<step>`) in [state], or after its deadline ([expired]). */
    class Contract(override val tale: Int, val step: Int, val player: Player, val note: QuestNote, val state: ContractState, val expired: Boolean) : Event() {
        override val kind: Kind get() = Kind.CONTRACT
    }

    /** The Lanternrise's lanterns are about to go ([release] false) or are let go ([release] true) at the festival's [centre]. */
    class Lanterns(val centre: Vec3, val release: Boolean) : Event() {
        override val kind: Kind get() = Kind.LANTERNS
    }

    /** [killer] killed [victim]. */
    class Kill(val victim: LivingEntity, val killer: Player) : Event() {
        override val kind: Kind get() = Kind.KILL
    }

    /** [player] kindled the Wardfire at [pos]. */
    class Kindled(val pos: BlockPos, val player: ServerPlayer) : Event() {
        override val kind: Kind get() = Kind.KINDLED
    }

    /** The walker in cast slot [slot] of tale [tale] arrived (the escort, 4.4). */
    class Arrived(override val tale: Int, val slot: String) : Event() {
        override val kind: Kind get() = Kind.ARRIVED
    }

    /** [player] gave toward tale [tale] in a talk (4.5): [marks] Marks, and/or [items]. */
    class Gave(override val tale: Int, val player: ServerPlayer, val marks: Int = 0, val items: List<ItemStack> = listOf()) : Event() {
        override val kind: Kind get() = Kind.GAVE
    }
}

/**
 * What a template is, in code (1.6). A template is one Kotlin object, listed in [Templates]; its lines are in the voice table
 * (13) and nothing else in the mod names it. The engine calls these in a close, a listener, a scene or a talk, always on the
 * server thread, each guarded: a template that throws is counted, and one that throws three times in a close ends its tale at W
 * (15.2). The record is the truth: a template keeps nothing of its own between calls but what is in the [Tale].
 */
interface Template {
    /** "note", "chair", ...: one of the twelve ids of 8.0 ([Templates.IDS]); unique. */
    val id: String

    /** 1 to 3: the reward table (7.1). */
    val size: Int

    /** The step table, in order (1.3); at least one. */
    val steps: List<Step>

    /** The four endings, A, B, C and W, with their words. */
    val endings: List<Ending>

    /** Whether the world's way (W) hurts others: the five tales whose W leaves the times a reason of -1 (6.1: note, wagon, moot, informer, brother). */
    val hurts: Boolean get() = false

    /** Whether the tale's days move on with the clock when tales come back on (11.5). False for the one tied to a festival's date (the lantern). */
    val thaws: Boolean get() = true

    /**
     * The cast slots that are standing, not essential (1.2, 5.6; the "Kind" column of each tale's cast table in section 8): when
     * a standing person dies or their household leaves, the engine casts a stand-in of the same trade from the nearest home and
     * adds a `Why`, and only if there is none does the template's [gone] run. Every other slot is essential: [gone] runs at once,
     * and a tale whose essential slots have all gone ends at W. Empty (every slot essential) unless a template says.
     */
    val standing: Set<String> get() = setOf()

    /** The names of the `n` slots it uses, in order (at most [TaleLimits.NUMBERS]), for `tale show` and `tale check`. */
    val numbers: List<String> get() = listOf()

    /** The names of the `s` slots it uses, in order (at most [TaleLimits.STRINGS]). */
    val strings: List<String> get() = listOf()

    /** The ending with this [letter], or null. */
    fun ending(letter: String): Ending? = endings.firstOrNull { it.letter == letter }

    /** The systems it stands on are on (12.3): `Works.enabled()`, `Caravans.enabled()`, `Tales.scenes()` ... Must not throw. */
    fun needs(): Boolean

    /** Reads the records; a cast, a first step and a `Why`, or null (and [Seeding.none] says why). */
    fun seed(c: Seeding): Planned?

    /** A step begins: queue its scene, prepare its note, point its target. */
    fun enter(c: Ctx, t: Tale)

    /** A listener event reached the tale (10.1). */
    fun heard(c: Ctx, t: Tale, e: Event)

    /** A scene that is due now, if one is (4.2). */
    fun scene(c: Ctx, t: Tale): Stage?

    /** The talk with this cast member, in this step (3.1, 4.5). */
    fun script(a: Ask, t: Tale, slot: String): Script?

    /** What this cast member says in passing, and the rumours (3.2). */
    fun topics(a: Ask, t: Tale, out: MutableList<Topic>)

    /** The world's way, at the step's day (1.4): what the town does without a hero. */
    fun settle(c: Ctx, t: Tale)

    /** A cast member died or left (5.6): the template's path, after the engine's own. */
    fun gone(c: Ctx, t: Tale, slot: String, how: Gone)

    /**
     * A standing slot's person went and a stand-in was cast in their place (5.6): [wasHeld] says the slot was held, so the
     * template holds the new person where the old one stood (`c.hold`). Nothing by default.
     */
    fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {}

    /** The consequences of an ending (6, stage 2 of the finish pipeline): the household, the flips, the works, the stall's seal, the memorial. */
    fun end(c: Ctx, t: Tale, ending: String)

    /** The tracker row (5.1). */
    fun view(t: Tale, player: UUID): View
}
