package io.github.vaspyyy.guildmark.tales

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/**
 * One reason a tale stands where it does (living-world/stories/design.md 1.1 and 5.4): a [kind] and the words' [args], in
 * the shape of a fortunes `Reason` so that the same sentence machinery says it. Every tale starts with one ("the times were
 * lean for five days") and adds one for every turn the player or the world takes ([Tale.addWhy]); "Why?" reads them back.
 *
 * The sentence is the language key `fortune.guildmark.reason.tale.why.<kind>` with [args] filled in. A template's own kinds
 * start with its id (`note.lean`, `chair.died`), the engine's are bare words (`died`, `left`, `stood_in`, `none_left`,
 * `late`, `skipped`, `finished`), so two parts never write the same key. A few short strings: names, a number as text.
 */
class Why(val kind: String, val args: List<String> = listOf()) {
    companion object {
        val CODEC: Codec<Why> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("kind").forGetter(Why::kind),
                Codec.STRING.listOf().optionalFieldOf("args", listOf()).forGetter(Why::args),
            ).apply(i) { kind, args -> Why(kind, args) }
        }
    }
}

/**
 * One person of a tale's cast (1.2): the [slot]'s name in the template ("debtor", "lender"), the [resident]'s id (-1 for a
 * figure the tale makes that has no record), the [name] kept so the chronicle can still say it when they are dead or gone,
 * and whether the resident's day is the tale's for now ([held], 4.4: their day body is not made, and the household cannot
 * leave from under it). Whether a slot is essential or standing is the template's to say, not the record's.
 */
class Cast(val slot: String, var resident: Int = -1, var name: String = "", var held: Boolean = false) {
    companion object {
        val CODEC: Codec<Cast> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("slot").forGetter(Cast::slot),
                Codec.INT.optionalFieldOf("resident", -1).forGetter(Cast::resident),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Cast::name),
                Codec.BOOL.optionalFieldOf("held", false).forGetter(Cast::held),
            ).apply(i) { slot, resident, name, held -> Cast(slot, resident, name, held) }
        }
    }
}

/**
 * One player's part in a tale (1.1): [player] (the uuid, so a lost, wiped or renamed player leaves nothing to repair),
 * where they [status] stands, how many times the cast have [asks] asked them and the market day of the [lastAsk] (so "Not
 * now" costs nothing: the cast ask again after [TaleLimits.ASK_AGAIN_DAYS] days, [TaleLimits.ASKS_MORE] more times), what
 * they [did] (a bitmask of the real things, below: what a reward is paid on, never presence), the points their tale's own
 * contract notes have already [paid] them (4.6, taken off the ending's pay), and the Marks they have put in and not yet had
 * spent or returned ([escrow], 7.5).
 */
class Part(
    val player: UUID,
    var status: Status = Status.UNMET,
    var asks: Int = 0,
    var lastAsk: Long = TaleData.NEVER,
    var did: Int = 0,
    var paid: Int = 0,
    var escrow: Int = 0,
) {
    /** Never spoken to about it, told but not yet said yes, said yes, did a real step, said no. */
    enum class Status {
        UNMET, ASKED, IN, DONE, DECLINED;

        val id: String get() = name.lowercase()
    }

    /** Whether [bit] of [did] is set. */
    fun did(bit: Int): Boolean = did and bit != 0

    companion object {
        /** `did` bits (7.2): a GIVE of a quarter of its sum or its remainder, a DO whose contract was handed in, a WATCH that was this player's deed, a CHOOSE whose word decided. */
        const val GAVE = 1
        const val HANDED = 2
        const val WALKED = 4
        const val CHOSE = 8

        /** This player's deed settled the ending (8.0, "the deciding deed"): they are paid in full, any other holder of a bit at half. */
        const val DECIDED = 16

        private val STATUS: Codec<Status> = Codec.STRING.xmap(
            { id: String -> Status.entries.firstOrNull { it.id == id } ?: Status.UNMET },
            { s: Status -> s.id },
        )

        val CODEC: Codec<Part> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Part::player),
                STATUS.optionalFieldOf("status", Status.UNMET).forGetter(Part::status),
                Codec.INT.optionalFieldOf("asks", 0).forGetter(Part::asks),
                Codec.LONG.optionalFieldOf("lastAsk", TaleData.NEVER).forGetter(Part::lastAsk),
                Codec.INT.optionalFieldOf("did", 0).forGetter(Part::did),
                Codec.INT.optionalFieldOf("paid", 0).forGetter(Part::paid),
                Codec.INT.optionalFieldOf("escrow", 0).forGetter(Part::escrow),
            ).apply(i) { player, status, asks, lastAsk, did, paid, escrow -> Part(player, status, asks, lastAsk, did, paid, escrow) }
        }
    }
}

/**
 * The ten stages of `Tales.finish` (6.7), in order. Each sets its [bit] in [Tale.done] when it has run, so an ended tale
 * whose mask is not full is finished at the next close from what the record holds: a crash cannot leave a tale ended with its
 * stone unset or its pay unpaid. [MARK] is the record itself and is complete once it has run.
 */
enum class Finish {
    /** 1. The tale is ENDED with its ending, its last `Why`, and the chronicle line. */
    MARK,

    /** 2. The template's `end`: the household, the flips, the works, the stall's seal, the memorial it asks for. */
    END,

    /** 3. The times: `TaleTimes.run`. */
    TIMES,

    /** 4. Memory: `TaleMemory.run`. */
    MEMORY,

    /** 5. Told: `TaleTold.run`. */
    TOLD,

    /** 6. Pay: `TalePay.run`. */
    PAY,

    /** 7. Seen: `TaleLook.queueEnding`, `TaleBannerPayload.send`, the keepsake. */
    SEEN,

    /** 8. Props and blocks: `Memorials.place`. */
    BLOCKS,

    /** 9. Free: the cast and the held residents are released, `quiet` is set, `last[template]` is the day. */
    FREE,

    /** 10. Heard: whatever wants to know that a tale ended. */
    HEARD;

    val bit: Int get() = 1 shl ordinal

    companion object {
        /** [Tale.done] with every stage run. */
        val ALL: Int = (1 shl Finish.entries.size) - 1
    }
}

/**
 * A tale (1.1): a short saved story about a few people of one capital, stepped on the server's own clock. The record is
 * the truth; bodies, scenes, banners and rows are rebuilt from it. Days are market days.
 *
 * It is `id`, `template` (one of the twelve, 8.0), `capital` (the key tales go by: the capital's heart, as `Scenes.Here.key`),
 * [phase], [ending] ("" until it ends, then "A", "B", "C" or "W"), [step] (an index into the template's steps) with the day it
 * began ([stepDay]) and the day the world settles it if nobody has ([by], counted on from [d0]), [d0] (the day it was seeded:
 * "D5" is `d0 + 5`), [ended] (the day it ended, which the forty-day keep counts from) and [done] (the [Finish] mask).
 * Then [cast], [n] and [s] (small numbers and short strings the template keeps, named in its section of the design and only ever
 * appended to), [why] and [parts]. That is sixteen fields, so the record is written as a header of eleven and a body of five.
 *
 * [n] always holds [TaleLimits.NUMBERS] numbers (0 until set) and [s] [TaleLimits.STRINGS] strings (""): `t.n[3]` is always
 * there to read and to set. Use [setS] for a string, which keeps it to [TaleLimits.STRING_LETTERS] letters.
 */
class Tale(
    val id: Int,
    val template: String,
    val capital: Long,
    var phase: Phase = Phase.OPEN,
    var ending: String = "",
    var step: Int = 0,
    var stepDay: Long = 0L,
    var by: Long = 0L,
    var d0: Long = 0L,
    var ended: Long = TaleData.NEVER,
    var done: Int = 0,
    val cast: MutableList<Cast> = ArrayList(),
    val n: IntArray = IntArray(TaleLimits.NUMBERS),
    val s: Array<String> = Array(TaleLimits.STRINGS) { "" },
    val why: MutableList<Why> = ArrayList(),
    val parts: MutableList<Part> = ArrayList(),
) {
    /** Open until it is ended; an ended tale is kept [TaleLimits.KEPT_DAYS] days for the chronicle and the rumours, then dropped. */
    enum class Phase {
        OPEN, ENDED;

        val id: String get() = name.lowercase()
    }

    val open: Boolean get() = phase == Phase.OPEN

    /** The cast member in [slot], or null. */
    fun cast(slot: String): Cast? = cast.firstOrNull { it.slot == slot }

    /** The resident id of the cast member in [slot], or -1 (no such slot, or a made figure). */
    fun resident(slot: String): Int = cast(slot)?.resident ?: -1

    /** Adds [c] to the cast; false (and nothing added) at [TaleLimits.CAST]. */
    fun addCast(c: Cast): Boolean {
        if (cast.size >= TaleLimits.CAST) return false
        cast.add(c)
        return true
    }

    /** [player]'s part, if the tale has met them. */
    fun part(player: UUID): Part? = parts.firstOrNull { it.player == player }

    /**
     * [player]'s part, begun (UNMET) if the tale has not met them. At [TaleLimits.PARTS] the oldest part that is only UNMET or
     * ASKED and holds nothing is let go to make room; a part with a `did` bit or Marks in escrow is never dropped. Null if
     * there is no room: a ninth player may be told the tale and talk, but gets no part.
     */
    fun partFor(player: UUID): Part? {
        part(player)?.let { return it }
        if (parts.size >= TaleLimits.PARTS) {
            val spare = parts.firstOrNull { it.did == 0 && it.escrow == 0 && (it.status == Part.Status.UNMET || it.status == Part.Status.ASKED) } ?: return null
            parts.remove(spare)
        }
        return Part(player).also { parts.add(it) }
    }

    /** The tale's lead: the first part that is IN or DONE (the one who took it up), or null. Derived, never stored. */
    val lead: Part? get() = parts.firstOrNull { it.status == Part.Status.IN || it.status == Part.Status.DONE }

    /** Adds a reason, oldest first. At [TaleLimits.WHY] the second is dropped, never the first or the last. */
    fun addWhy(w: Why) {
        if (why.size >= TaleLimits.WHY) why.removeAt(1)
        why.add(w)
    }

    fun addWhy(kind: String, vararg args: String) = addWhy(Why(kind, args.toList()))

    /** Sets string slot [i] to [v], cut to [TaleLimits.STRING_LETTERS] letters. */
    fun setS(i: Int, v: String) {
        s[i] = v.take(TaleLimits.STRING_LETTERS)
    }

    /** Whether every stage of [Finish] has run. */
    val finished: Boolean get() = done and Finish.ALL == Finish.ALL

    /** The eleven fields written first (the header group of 11.1). */
    private class Header(
        val id: Int, val template: String, val capital: Long, val phase: Phase, val ending: String, val step: Int,
        val stepDay: Long, val by: Long, val d0: Long, val ended: Long, val done: Int,
    )

    /** The five written after them (the body group of 11.1), as they read back before they are put in the fixed sizes. */
    private class Body(val cast: List<Cast>, val n: List<Int>, val s: List<String>, val why: List<Why>, val parts: List<Part>)

    companion object {
        private val PHASE: Codec<Phase> = Codec.STRING.xmap(
            { id: String -> Phase.entries.firstOrNull { it.id == id } ?: Phase.OPEN },
            { p: Phase -> p.id },
        )

        private val HEADER: MapCodec<Header> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Header::id),
                Codec.STRING.optionalFieldOf("template", "").forGetter(Header::template),
                Codec.LONG.optionalFieldOf("capital", 0L).forGetter(Header::capital),
                PHASE.optionalFieldOf("phase", Phase.OPEN).forGetter(Header::phase),
                Codec.STRING.optionalFieldOf("ending", "").forGetter(Header::ending),
                Codec.INT.optionalFieldOf("step", 0).forGetter(Header::step),
                Codec.LONG.optionalFieldOf("stepDay", 0L).forGetter(Header::stepDay),
                Codec.LONG.optionalFieldOf("by", 0L).forGetter(Header::by),
                Codec.LONG.optionalFieldOf("d0", 0L).forGetter(Header::d0),
                Codec.LONG.optionalFieldOf("ended", TaleData.NEVER).forGetter(Header::ended),
                Codec.INT.optionalFieldOf("done", 0).forGetter(Header::done),
            ).apply(i) { id, template, capital, phase, ending, step, stepDay, by, d0, ended, done ->
                Header(id, template, capital, phase, ending, step, stepDay, by, d0, ended, done)
            }
        }

        private val BODY: MapCodec<Body> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Cast.CODEC.listOf().optionalFieldOf("cast", listOf()).forGetter(Body::cast),
                Codec.INT.listOf().optionalFieldOf("n", listOf()).forGetter(Body::n),
                Codec.STRING.listOf().optionalFieldOf("s", listOf()).forGetter(Body::s),
                Why.CODEC.listOf().optionalFieldOf("why", listOf()).forGetter(Body::why),
                Part.CODEC.listOf().optionalFieldOf("parts", listOf()).forGetter(Body::parts),
            ).apply(i) { cast, n, s, why, parts -> Body(cast, n, s, why, parts) }
        }

        /** The numbers without their trailing zeros, so the file holds only what was set. */
        private fun trimmed(n: IntArray): List<Int> = n.toList().dropLastWhile { it == 0 }

        /** The strings without their trailing empty ones. */
        private fun trimmed(s: Array<String>): List<String> = s.toList().dropLastWhile { it.isEmpty() }

        val CODEC: Codec<Tale> = RecordCodecBuilder.create { i ->
            i.group(
                HEADER.forGetter { t: Tale -> Header(t.id, t.template, t.capital, t.phase, t.ending, t.step, t.stepDay, t.by, t.d0, t.ended, t.done) },
                BODY.forGetter { t: Tale -> Body(t.cast, trimmed(t.n), trimmed(t.s).map { it.take(TaleLimits.STRING_LETTERS) }, t.why, t.parts) },
            ).apply(i) { h, b ->
                Tale(
                    h.id, h.template, h.capital, h.phase, h.ending, h.step, h.stepDay, h.by, h.d0, h.ended, h.done,
                    b.cast.take(TaleLimits.CAST).toMutableList(),
                    IntArray(TaleLimits.NUMBERS) { b.n.getOrElse(it) { 0 } },
                    Array(TaleLimits.STRINGS) { b.s.getOrElse(it) { "" }.take(TaleLimits.STRING_LETTERS) },
                    b.why.takeLast(TaleLimits.WHY).toMutableList(),
                    b.parts.take(TaleLimits.PARTS).toMutableList(),
                )
            }
        }
    }
}

/**
 * Every tale and what a capital remembers of them (living-world/stories/design.md 11.1), kept with the overworld as the saved
 * data `tales`: each tale by id, open or ended; per capital the pace (`quiet`, `seen`, `closed`, `last`), the chronicle, the
 * traditions, the caravans' strikes, the works' volunteers and the households a tale protects; the memorials; the trait flips;
 * the pay and refunds waiting for a player who was away; and the stalls sealed for a time. A world with no `tales` file gets
 * an empty one on first use and nothing is retrofitted; every field reads back with a default, so an older or newer file
 * reads as far as its fields are known. Lists are written in key order and the data is marked dirty only when something
 * changed, so the file does not change when nothing did.
 *
 * Nothing opens it until the engine does (Part A2): a build with tales off never writes the file.
 */
class TaleData(
    /** The version of the file ([TaleLimits.VERSION]); one found higher is read as far as it is known. */
    var version: Int = TaleLimits.VERSION,
    private var next: Int = 1,
    private var nextMemorial: Int = 1,
    private val tales: MutableMap<Int, Tale> = HashMap(),
    private val towns: MutableMap<Long, Town> = HashMap(),
    /** The stones and cairns of 6.4, all capitals, in the order they were made. */
    val memorials: MutableList<Memorial> = ArrayList(),
    /** The trait flips of 6.5, oldest first, at most [TaleLimits.FLIP_LOG]. */
    val flips: MutableList<Flip> = ArrayList(),
    /** Pay and refunds waiting for a player who was away (7.4, 7.5), at most [TaleLimits.OWED_PER_PLAYER] a player. */
    val owed: MutableList<Owed> = ArrayList(),
    /** The stalls sealed for a time (8.1): the keeper's place as a long, to the last market day; at most [TaleLimits.SEALED]. */
    val sealed: MutableMap<Long, Long> = HashMap(),
) : SavedData() {

    /** One line of a capital's chronicle: which [template] ended [ending] on market [day], up to three [names] and the [lead]'s name. Kept oldest first; the newest is last. */
    class Line(val template: String, val ending: String, val day: Long, val names: List<String>, val lead: String) {
        companion object {
            val CODEC: Codec<Line> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("template").forGetter(Line::template),
                    Codec.STRING.optionalFieldOf("ending", "").forGetter(Line::ending),
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Line::day),
                    Codec.STRING.listOf().optionalFieldOf("names", listOf()).forGetter(Line::names),
                    Codec.STRING.optionalFieldOf("lead", "").forGetter(Line::lead),
                ).apply(i) { template, ending, day, names, lead -> Line(template, ending, day, names, lead) }
            }
        }
    }

    /** A tradition a capital keeps (6.5): its [kind] ("lantern"), the [name] it is for, the [festival] it belongs to, the [year] it began and how many festivals it has been [kept]. */
    class Tradition(val kind: String, val name: String, val festival: String, val year: Int, var kept: Int) {
        companion object {
            val CODEC: Codec<Tradition> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("kind").forGetter(Tradition::kind),
                    Codec.STRING.optionalFieldOf("name", "").forGetter(Tradition::name),
                    Codec.STRING.optionalFieldOf("festival", "").forGetter(Tradition::festival),
                    Codec.INT.optionalFieldOf("year", 0).forGetter(Tradition::year),
                    Codec.INT.optionalFieldOf("kept", 0).forGetter(Tradition::kept),
                ).apply(i) { kind, name, festival, year, kept -> Tradition(kind, name, festival, year, kept) }
            }
        }
    }

    /** A caravan waylaid on the way to a capital, pushed by the caravans' listener (10.1): the [caravan], the [band] (-1 for none), the [road] (-1: the plan's own country road) and the market [day]. */
    class Waylaid(val caravan: Long, val band: Int, val road: Int, val day: Long) {
        companion object {
            val CODEC: Codec<Waylaid> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("caravan").forGetter(Waylaid::caravan),
                    Codec.INT.optionalFieldOf("band", -1).forGetter(Waylaid::band),
                    Codec.INT.optionalFieldOf("road", -1).forGetter(Waylaid::road),
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Waylaid::day),
                ).apply(i) { caravan, band, road, day -> Waylaid(caravan, band, road, day) }
            }
        }
    }

    /** A resident the works take first when they take labourers (6.3), until market day [until]. */
    class Volunteer(val resident: Int, val until: Long) {
        companion object {
            val CODEC: Codec<Volunteer> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("resident").forGetter(Volunteer::resident),
                    Codec.LONG.optionalFieldOf("until", 0L).forGetter(Volunteer::until),
                ).apply(i) { resident, until -> Volunteer(resident, until) }
            }
        }
    }

    /** A household (its [home] id in the city's lodgings) a tale protects from leaving, until market day [until] (6.5). */
    class Protect(val home: Int, val until: Long) {
        companion object {
            val CODEC: Codec<Protect> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("home").forGetter(Protect::home),
                    Codec.LONG.optionalFieldOf("until", 0L).forGetter(Protect::until),
                ).apply(i) { home, until -> Protect(home, until) }
            }
        }
    }

    /**
     * A memorial (6.4): a "stone" or a "cairn" with its [id] (the atlas's `ref` and a street piece's `run`), where it is
     * ([pos] in [dimension], or null while no site has been found), the [names] it carries (one or two), the [template] and
     * [lead] it is for, the market [day] and the [capital] it belongs to. [placed] is whether the blocks are set; a memorial
     * is recorded either way and kept (never trimmed).
     */
    class Memorial(
        val id: Int,
        val kind: String,
        var pos: BlockPos?,
        val dimension: String,
        val names: List<String>,
        val template: String,
        val lead: String,
        val day: Long,
        val capital: Long,
        var placed: Boolean,
    ) {
        companion object {
            val CODEC: Codec<Memorial> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("id").forGetter(Memorial::id),
                    Codec.STRING.optionalFieldOf("kind", "stone").forGetter(Memorial::kind),
                    BlockPos.CODEC.optionalFieldOf("pos").forGetter { Optional.ofNullable(it.pos) },
                    Codec.STRING.optionalFieldOf("dimension", "minecraft:overworld").forGetter(Memorial::dimension),
                    Codec.STRING.listOf().optionalFieldOf("names", listOf()).forGetter(Memorial::names),
                    Codec.STRING.optionalFieldOf("template", "").forGetter(Memorial::template),
                    Codec.STRING.optionalFieldOf("lead", "").forGetter(Memorial::lead),
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Memorial::day),
                    Codec.LONG.optionalFieldOf("capital", 0L).forGetter(Memorial::capital),
                    Codec.BOOL.optionalFieldOf("placed", false).forGetter(Memorial::placed),
                ).apply(i) { id, kind, pos, dimension, names, template, lead, day, capital, placed ->
                    Memorial(id, kind, pos.orElse(null), dimension, names, template, lead, day, capital, placed)
                }
            }
        }
    }

    /** A trait flip (6.5): [resident]'s ways went from [from] (the trait cleared, or "" for none) to [to] on market [day], for tale [tale]. Traits are named as `Trait.name` lower-cased. */
    class Flip(val resident: Int, val from: String, val to: String, val day: Long, val tale: Int) {
        companion object {
            val CODEC: Codec<Flip> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("resident").forGetter(Flip::resident),
                    Codec.STRING.optionalFieldOf("from", "").forGetter(Flip::from),
                    Codec.STRING.optionalFieldOf("to", "").forGetter(Flip::to),
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Flip::day),
                    Codec.INT.optionalFieldOf("tale", -1).forGetter(Flip::tale),
                ).apply(i) { resident, from, to, day, tale -> Flip(resident, from, to, day, tale) }
            }
        }
    }

    /**
     * Pay or a refund waiting for [player] (7.4, 7.5), earned in tale [tale] of [template] that ended [ending]: [points]
     * of pay (paid through `payOut`), or [marks] of escrow to give back. A line is paid once and removed in the same call.
     */
    class Owed(val player: UUID, val tale: Int, val template: String, val points: Int, val ending: String, val marks: Int) {
        companion object {
            val CODEC: Codec<Owed> = RecordCodecBuilder.create { i ->
                i.group(
                    UUIDUtil.CODEC.fieldOf("player").forGetter(Owed::player),
                    Codec.INT.optionalFieldOf("tale", -1).forGetter(Owed::tale),
                    Codec.STRING.optionalFieldOf("template", "").forGetter(Owed::template),
                    Codec.INT.optionalFieldOf("points", 0).forGetter(Owed::points),
                    Codec.STRING.optionalFieldOf("ending", "").forGetter(Owed::ending),
                    Codec.INT.optionalFieldOf("marks", 0).forGetter(Owed::marks),
                ).apply(i) { player, tale, template, points, ending, marks -> Owed(player, tale, template, points, ending, marks) }
            }
        }
    }

    /** What one capital remembers: the pace and the lists of 11.1, keyed by the capital's heart. */
    class Town(
        /** The market day before which no new tale starts here (9.1). */
        var quiet: Long = NEVER,
        /** The last market day a player was in the capital; `Tales.look` sets it. */
        var seen: Long = NEVER,
        /** The last market day `Tales.day` closed here: the guard against closing a day twice, and what the thaw measures (11.5). */
        var closed: Long = NEVER,
        /** Template id to the market day it last ended here (the forty-day rule), at most [TaleLimits.LAST]. */
        val last: MutableMap<String, Long> = HashMap(),
        /** The last [TaleLimits.CHRONICLE] endings, oldest first. */
        val chronicle: MutableList<Line> = ArrayList(),
        /** The traditions the festival keeps, at most [TaleLimits.TRADITIONS]. */
        val traditions: MutableList<Tradition> = ArrayList(),
        /** The caravans' strikes pushed by the listener, at most [TaleLimits.WAYLAID]; one over [TaleLimits.WAYLAID_DAYS] days old is dropped. */
        val waylaid: MutableList<Waylaid> = ArrayList(),
        /** The residents the works take first, at most [TaleLimits.VOLUNTEERS]. */
        val volunteers: MutableList<Volunteer> = ArrayList(),
        /** The households a tale protects from leaving, at most [TaleLimits.PROTECT]. */
        val protect: MutableList<Protect> = ArrayList(),
    ) {
        companion object {
            private val LAST: Codec<Pair<String, Long>> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("template").forGetter { it.first },
                    Codec.LONG.optionalFieldOf("day", NEVER).forGetter { it.second },
                ).apply(i) { template, day -> template to day }
            }

            val CODEC: Codec<Pair<Long, Town>> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("capital").forGetter { it.first },
                    Codec.LONG.optionalFieldOf("quiet", NEVER).forGetter { it.second.quiet },
                    Codec.LONG.optionalFieldOf("seen", NEVER).forGetter { it.second.seen },
                    Codec.LONG.optionalFieldOf("closed", NEVER).forGetter { it.second.closed },
                    LAST.listOf().optionalFieldOf("last", listOf()).forGetter { it.second.last.entries.sortedBy { e -> e.key }.map { e -> e.key to e.value } },
                    Line.CODEC.listOf().optionalFieldOf("chronicle", listOf()).forGetter { it.second.chronicle },
                    Tradition.CODEC.listOf().optionalFieldOf("traditions", listOf()).forGetter { it.second.traditions },
                    Waylaid.CODEC.listOf().optionalFieldOf("waylaid", listOf()).forGetter { it.second.waylaid },
                    Volunteer.CODEC.listOf().optionalFieldOf("volunteers", listOf()).forGetter { it.second.volunteers },
                    Protect.CODEC.listOf().optionalFieldOf("protect", listOf()).forGetter { it.second.protect },
                ).apply(i) { capital, quiet, seen, closed, last, chronicle, traditions, waylaid, volunteers, protect ->
                    capital to Town(
                        quiet, seen, closed, last.toMap(HashMap()), chronicle.toMutableList(), traditions.toMutableList(),
                        waylaid.toMutableList(), volunteers.toMutableList(), protect.toMutableList(),
                    )
                }
            }
        }
    }

    // ---- The tales ----

    /** The tale with this id, open or ended. */
    operator fun get(id: Int): Tale? = tales[id]

    /** Every tale, open or ended, in no particular order. */
    fun all(): Collection<Tale> = tales.values

    /**
     * The open tale of [capital], or null: there is one at a time (11.2). A look at the kept tales (24 a capital at most) that
     * changes nothing; the engine (Part A2) may keep an index behind this same call, so ask it freely but not per tick.
     */
    fun openIn(capital: Long): Tale? = tales.values.firstOrNull { it.open && it.capital == capital }

    /** The open tale that has the resident [resident] in its cast, or null (a resident is in at most one, 1.2; -1 is no one). */
    fun castIn(resident: Int): Tale? = if (resident < 0) null else tales.values.firstOrNull { t -> t.open && t.cast.any { it.resident == resident } }

    /** The open tales [player] has taken up (a part that is IN or DONE), oldest first: two at most (11.2). */
    fun joinedBy(player: UUID): List<Tale> = tales.values
        .filter { t -> t.open && t.part(player)?.let { it.status == Part.Status.IN || it.status == Part.Status.DONE } == true }
        .sortedBy { it.id }

    /** Adds [t] (its id came from [newId]). */
    fun add(t: Tale) {
        tales[t.id] = t
        setDirty()
    }

    /** Takes tale [id] out of the record altogether (an ended tale past its keep, or a cleared one); the tale if there was one. */
    fun remove(id: Int): Tale? = tales.remove(id)?.also { setDirty() }

    /** A new tale's id. An id is never used twice in a world. */
    fun newId(): Int = next++.also { setDirty() }

    /** A new memorial's id (never reused, so a street piece's `run` can never name a different memorial). */
    fun newMemorialId(): Int = nextMemorial++.also { setDirty() }

    // ---- The towns ----

    /** What the capital whose heart is [capital] remembers, if it has had a record begun. */
    fun find(capital: Long): Town? = towns[capital]

    /** The capital's record, begun if it has none. */
    fun town(capital: Long): Town = towns.getOrPut(capital) {
        setDirty()
        Town()
    }

    /** Every capital with a record, by its heart. */
    fun towns(): Map<Long, Town> = towns

    fun changed() = setDirty()

    companion object {
        /** No day yet. The same distance from the ends as the comings' and fortunes', so a day taken off it cannot wrap. */
        const val NEVER = Long.MIN_VALUE / 4

        private val SEALED: Codec<Pair<Long, Long>> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("key").forGetter { it.first },
                Codec.LONG.optionalFieldOf("until", 0L).forGetter { it.second },
            ).apply(i) { key, until -> key to until }
        }

        private val CODEC: Codec<TaleData> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("version", TaleLimits.VERSION).forGetter { it.version },
                Codec.INT.optionalFieldOf("next", 1).forGetter { it.next },
                Codec.INT.optionalFieldOf("nextMemorial", 1).forGetter { it.nextMemorial },
                Tale.CODEC.listOf().lenientOptionalFieldOf("tales", listOf()).forGetter { d -> d.tales.values.sortedBy { it.id } },
                Town.CODEC.listOf().lenientOptionalFieldOf("towns", listOf()).forGetter { d -> d.towns.entries.sortedBy { it.key }.map { it.key to it.value } },
                Memorial.CODEC.listOf().lenientOptionalFieldOf("memorials", listOf()).forGetter { d -> d.memorials.sortedBy { it.id } },
                Flip.CODEC.listOf().lenientOptionalFieldOf("flips", listOf()).forGetter { it.flips },
                Owed.CODEC.listOf().lenientOptionalFieldOf("owed", listOf()).forGetter { it.owed },
                SEALED.listOf().lenientOptionalFieldOf("sealed", listOf()).forGetter { d -> d.sealed.entries.sortedBy { it.key }.map { it.key to it.value } },
            ).apply(i) { version, next, nextMemorial, tales, towns, memorials, flips, owed, sealed ->
                TaleData(
                    version, next, nextMemorial,
                    tales.associateByTo(HashMap()) { it.id },
                    towns.toMap(HashMap()),
                    memorials.toMutableList(), flips.toMutableList(), owed.toMutableList(),
                    sealed.toMap(HashMap()),
                )
            }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tales"), Supplier { TaleData() }, CODEC)

        /** The tales of the world, kept with the overworld; an empty record the first time anyone asks. */
        fun get(level: ServerLevel): TaleData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
