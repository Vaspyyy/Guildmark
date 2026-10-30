package io.github.vaspyyy.guildmark.village.live

import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.Decoder
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.MapCodec
import com.mojang.serialization.MapDecoder
import com.mojang.serialization.MapLike
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.stream.Stream

/**
 * A list read element by element: one that can't be read is left out and the rest are kept, so a bad element drops only
 * itself and a village's record, or the store of them, is never lost to one line of it (living-world/villages/design.md
 * section 14.1). An element read only in part (a bad line in a list of its own, a village's market with one bad price in
 * it) is kept as far as it reads, as the game keeps a store read in part. Written as [Codec.listOf] writes it.
 */
fun <A : Any> keptList(element: Codec<A>): Codec<List<A>> = Codec.of(
    element.listOf(),
    object : Decoder<List<A>> {
        override fun <T> decode(ops: DynamicOps<T>, input: T): DataResult<Pair<List<A>, T>> =
            ops.getStream(input).map { stream ->
                val kept: MutableList<A> = ArrayList()
                stream.forEach { item -> element.parse(ops, item).resultOrPartial { }.ifPresent { kept.add(it) } }
                Pair.of<List<A>, T>(kept, input)
            }
    },
)

/**
 * A field written as [Codec.fieldOf] writes it (always, so an older build reads it as before), read the way a [keptList]
 * reads its elements: left out, or of no use at all, it takes a [default] made fresh for it, and read only in part it is
 * kept as far as it reads. For the field a store or a record can't do without but has a plain empty or blank form for
 * (a store's lists, a record's part whose own fields all have defaults), so that one bad field never loses the store
 * or the record round it.
 */
fun <A : Any> Codec<A>.keptField(name: String, default: () -> A): MapCodec<A> = MapCodec.of(
    fieldOf(name),
    object : MapDecoder.Implementation<A>() {
        override fun <T> decode(ops: DynamicOps<T>, input: MapLike<T>): DataResult<A> =
            DataResult.success(input.get(name)?.let { parse(ops, it).resultOrPartial { }.orElse(null) } ?: default())

        override fun <T> keys(ops: DynamicOps<T>): Stream<T> = Stream.of(ops.createString(name))
    },
    { "KeptField[$name]" },
)

/** An enum written by its lower-case name; a name it doesn't know reads as [fallback]. */
private fun <E : Enum<E>> byName(all: List<E>, fallback: E): Codec<E> =
    Codec.STRING.xmap({ s -> all.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: fallback }, { it.name.lowercase() })

/** How big a village is by what has been built at its board: the table of design section 4.4 (see [Size.of]). */
enum class Size {
    HAMLET, VILLAGE, TOWN, CITY;

    companion object {
        val CODEC: Codec<Size> = byName(entries, HAMLET)

        /** The size of a village whose board has [advanceIndex] advances built (0 to 1 a hamlet, to 5 a village, to 7 a town, then a city). */
        fun of(advanceIndex: Int): Size = when {
            advanceIndex <= 1 -> HAMLET
            advanceIndex <= 5 -> VILLAGE
            advanceIndex <= 7 -> TOWN
            else -> CITY
        }
    }
}

/**
 * What a village is doing with the world's time: [LIVE] with a player within 96 blocks, [SLEEPING] further off (its
 * hours still worked out, no bodies about), [PAUSED] when more are awake than the settings allow, and [LOST] when its
 * bell has gone (design section 4.5).
 */
enum class Live {
    LIVE, SLEEPING, PAUSED, LOST;

    companion object {
        val CODEC: Codec<Live> = byName(entries, SLEEPING)
    }
}

/**
 * One of a village's homes: a door and the beds behind it. Its [id] is stable (homes are appended and never reordered),
 * so a resident's home is the number, not the place. [gone] when its door and beds are both gone; [kind] is what the census
 * called it, by the strings the daily-life code looks at (HOUSE, GUILD_HALL, LIBRARY, TEMPLE, SMITHY, BAKERY, WORKSHOP, BARN or TOWER).
 */
class Home(val id: Int, val door: Long, var beds: List<Long>, var kind: String, var gone: Boolean) {
    companion object {
        val CODEC: Codec<Home> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Home::id),
                Codec.LONG.fieldOf("door").forGetter(Home::door),
                Codec.LONG.listOf().optionalFieldOf("beds", listOf()).forGetter(Home::beds),
                Codec.STRING.optionalFieldOf("kind", "HOUSE").forGetter(Home::kind),
                Codec.BOOL.optionalFieldOf("gone", false).forGetter(Home::gone),
            ).apply(i, ::Home)
        }
    }
}

/** A resident who died here: their name, the [day] and the [cause] ("fell in the siege"), and the [home] they had (or -1). */
data class Dead(val name: String, val day: Long, val cause: String, val home: Int) {
    companion object {
        val CODEC: Codec<Dead> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("name").forGetter(Dead::name),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Dead::day),
                Codec.STRING.optionalFieldOf("cause", "").forGetter(Dead::cause),
                Codec.INT.optionalFieldOf("home", -1).forGetter(Dead::home),
            ).apply(i, ::Dead)
        }
    }
}

/** One happening in a village's chronicle: on [day], a [kind] (built, siege, raid, rescued, born, arrived, died, mended) and what it was about. */
data class Chron(val day: Long, val kind: String, val arg: String) {
    companion object {
        val CODEC: Codec<Chron> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("kind").forGetter(Chron::kind),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Chron::day),
                Codec.STRING.optionalFieldOf("arg", "").forGetter(Chron::arg),
            ).apply(i) { kind, day, arg -> Chron(day, kind, arg) }
        }
    }
}

/**
 * A wagon road that ends at the village: the [capital] (its centre's key) whose wagons come by [road] (its id in the road
 * network), and how far it runs, [length] blocks (design section 9.1).
 */
data class Link(val capital: Long, val road: Int, val length: Int) {
    companion object {
        val CODEC: Codec<Link> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(Link::capital),
                Codec.INT.optionalFieldOf("road", -1).forGetter(Link::road),
                Codec.INT.optionalFieldOf("length", 0).forGetter(Link::length),
            ).apply(i, ::Link)
        }
    }
}

/**
 * A village as the world keeps it (design section 4.2): the cluster round a bell, found the first time a player came
 * within 64 blocks of it. The [key] is the bell's `BlockPos.asLong()`; [board] is the Guildmark board's anchor cell, or
 * -1; [found] is the day it was first counted, and [counted] the game time of the last census, or -1 before the first;
 * [seen] is the game time a player was last within [Live.LIVE]'s reach; [radius] is how far the village reaches (20 to 48);
 * [folk] is false after `village release`, when nobody is taken in until `village adopt`. [lostDay] is the day its bell was
 * found gone (the state is [Live.LOST] from then), or -1 while it stands.
 *
 * The lists are bounded: [dead] holds the last [MAX_DEAD] and [chronicle] the last [MAX_CHRON] (read back the same, whatever
 * the file holds), and [homes] only grows. What is not here is worked out when wanted or kept in a store of its own: the
 * places, the market, the folk, the keepsakes, the fortunes.
 *
 * Every field but the key is optional with a default in what is saved ([CODEC]), so an older file reads, and a part that
 * adds a field adds it optional. The codec is built of groups (a record codec holds sixteen fields at most, and a group
 * nested in another is flat in what is saved): a new field goes into [Ledger] or a group of its own.
 */
class VillageRec(
    val key: Long,
    var board: Long = -1L,
    var name: String = "",
    var found: Long = 0L,
    var radius: Int = RADIUS_LEAST,
    var size: Size = Size.HAMLET,
    var state: Live = Live.SLEEPING,
    var folk: Boolean = true,
    var seen: Long = 0L,
    var counted: Long = -1L,
    val homes: MutableList<Home> = ArrayList(),
    val links: MutableList<Link> = ArrayList(),
    val dead: MutableList<Dead> = ArrayList(),
    val chronicle: MutableList<Chron> = ArrayList(),
    var lostDay: Long = -1L,
) {
    /** [d] is the latest to die here; the oldest is dropped past [MAX_DEAD]. */
    fun bury(d: Dead) {
        dead.add(d)
        while (dead.size > MAX_DEAD) dead.removeAt(0)
    }

    /** Something happened on [day]: [kind] and what it was about; the oldest line is dropped past [MAX_CHRON]. */
    fun note(day: Long, kind: String, arg: String = "") {
        chronicle.add(Chron(day, kind, arg))
        while (chronicle.size > MAX_CHRON) chronicle.removeAt(0)
    }

    /**
     * The clock set back [days] days (from `Comings.setBack`): every date the record keeps by day goes back with it, none
     * below zero. The game times ([seen], [counted]) don't: the game's time only goes on.
     */
    fun back(days: Long) {
        if (days <= 0L) return
        found = maxOf(0L, found - days)
        if (lostDay >= 0L) lostDay = maxOf(0L, lostDay - days)
        dead.replaceAll { Dead(it.name, maxOf(0L, it.day - days), it.cause, it.home) }
        chronicle.replaceAll { Chron(maxOf(0L, it.day - days), it.kind, it.arg) }
    }

    /** The first group of what is saved: how the village is known. */
    private class Core(val board: Long, val name: String, val found: Long, val radius: Int, val size: Size, val state: Live, val folk: Boolean)

    /** The second: what it has kept, and when it was last looked at. */
    private class Ledger(
        val seen: Long, val counted: Long, val homes: List<Home>, val links: List<Link>, val dead: List<Dead>, val chronicle: List<Chron>,
        val lostDay: Long,
    )

    companion object {
        /** The most dead and the most happenings a record keeps (design section 11.4). */
        const val MAX_DEAD = 24
        const val MAX_CHRON = 12

        /** The least a village's radius is (design section 4.2: 20 to 48, by what has been built). */
        const val RADIUS_LEAST = 20

        private val CORE: MapCodec<Core> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.LONG.optionalFieldOf("board", -1L).forGetter(Core::board),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Core::name),
                Codec.LONG.optionalFieldOf("found", 0L).forGetter(Core::found),
                Codec.INT.optionalFieldOf("radius", RADIUS_LEAST).forGetter(Core::radius),
                Size.CODEC.optionalFieldOf("size", Size.HAMLET).forGetter(Core::size),
                Live.CODEC.optionalFieldOf("state", Live.SLEEPING).forGetter(Core::state),
                Codec.BOOL.optionalFieldOf("folk", true).forGetter(Core::folk),
            ).apply(i, ::Core)
        }

        private val LEDGER: MapCodec<Ledger> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.LONG.optionalFieldOf("seen", 0L).forGetter(Ledger::seen),
                Codec.LONG.optionalFieldOf("counted", -1L).forGetter(Ledger::counted),
                keptList(Home.CODEC).lenientOptionalFieldOf("homes", listOf()).forGetter(Ledger::homes),
                keptList(Link.CODEC).lenientOptionalFieldOf("links", listOf()).forGetter(Ledger::links),
                keptList(Dead.CODEC).lenientOptionalFieldOf("dead", listOf()).forGetter(Ledger::dead),
                keptList(Chron.CODEC).lenientOptionalFieldOf("chronicle", listOf()).forGetter(Ledger::chronicle),
                Codec.LONG.optionalFieldOf("lost", -1L).forGetter(Ledger::lostDay),
            ).apply(i, ::Ledger)
        }

        /** A record as it is saved: its key, then the fields of [Core] and [Ledger] side by side (nesting adds no level). */
        val CODEC: Codec<VillageRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("key").forGetter(VillageRec::key),
                CORE.forGetter { Core(it.board, it.name, it.found, it.radius, it.size, it.state, it.folk) },
                LEDGER.forGetter { Ledger(it.seen, it.counted, it.homes, it.links, it.dead, it.chronicle, it.lostDay) },
            ).apply(i) { key, core, ledger ->
                VillageRec(
                    key, core.board, core.name, core.found, core.radius, core.size, core.state, core.folk, ledger.seen, ledger.counted,
                    ArrayList(ledger.homes), ArrayList(ledger.links), ArrayList(ledger.dead.takeLast(MAX_DEAD)), ArrayList(ledger.chronicle.takeLast(MAX_CHRON)),
                    ledger.lostDay,
                )
            }
        }
    }
}
