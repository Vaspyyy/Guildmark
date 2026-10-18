package io.github.vaspyyy.guildmark.economy.works

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.GoodsCodec
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/** Marks a player put toward a works, as kept: [free] for an op's (`works fund`), which took no Marks and is never paid back. */
internal class PledgeRec(val player: UUID, var name: String, val marks: Int, val day: Long, val free: Boolean)

/** Marks paid back from works called off that [name] hasn't had yet: handed over when they next join, use the board or open a works. */
internal class Owed(var name: String, var marks: Int)

/**
 * One works, from the day it's wanted to the day it's done (design-influence.md sections 1 to 3): which works it is
 * and for whom ([road] a tower's, [owner] a stall's), its [price] and its [parts], its [bill], the market day it was
 * [wanted] and [why] (a lang key). Then, as its life goes on: its state and pledges; its site (once the building finds
 * one; [noRoom] the day it last found none, [siteLost] the first day a paid-for works has stood without one); its
 * yard: what's [here], what the builders have [used], what players [brought] (counted in the other two), and what
 * the road has [lost] of its freight; a granary's first filling still to come ([fill], by good); the days it was
 * funded, begun and done, and the market hour its freight last landed ([landed], for the long way round); its stage;
 * whether a granary has given grain since it opened ([gave]); the newcomer Comings sent for it ([hired]); and the
 * last market day it eased each thing ([eased]).
 */
internal class WorksRec(
    val id: Int, val capital: Long, val kind: WorksKind, val road: Int, val owner: UUID?,
    val price: Int, val parts: Map<String, Int>, val bill: IntArray, val wanted: Long, val why: String,
) {
    var state = WorksState.WANTED
    val pledges = ArrayList<PledgeRec>()
    var site: WorksSite? = null
    var noRoom = -1L
    var siteLost = -1L
    val here = IntArray(Stuff.PAID.size)
    val used = IntArray(Stuff.PAID.size)
    val brought = IntArray(Stuff.PAID.size)
    val lost = IntArray(Stuff.PAID.size)
    val fill = DoubleArray(Good.entries.size)
    var funded = -1L
    var begun = -1L
    var done = -1L
    var landed = Long.MIN_VALUE / 2
    var stage: Stage? = null
    var laid = 0
    var total = 0
    var gave = false
    var hired = -1
    val eased = HashMap<String, Long>()

    /** Not kept: read back from a save with a kind this build doesn't know, so dropped. */
    var unknown = false

    /** All the Marks pledged toward it. */
    val pledged: Int get() = pledges.sumOf { it.marks }

    /** Paid for, or past it. */
    val paid: Boolean get() = state != WorksState.WANTED

    /** Paid for and not done: its materials are wanted. */
    val open: Boolean get() = state == WorksState.FUNDED || state == WorksState.BUILDING
}

/**
 * What a capital's works keep of its market days: the last day closed ([closed], so a day is never closed twice);
 * whether the bread went short on each of the last eight ([short], a bit a day, the newest lowest); and of the day
 * under way, the lowest the market's wheat stood against its target ([low]), whether an oven was cracked or the
 * wheat blighted at any hour of it, and the roads a wagon was waylaid on.
 */
internal class TownRec(val capital: Long) {
    var closed = Long.MIN_VALUE / 2
    var short = 0
    var low = Double.MAX_VALUE
    var cracked = false
    var blighted = false
    val waylaid = HashSet<Int>()

    /** Starts the day afresh, once it's closed. */
    fun begin() {
        low = Double.MAX_VALUE
        cracked = false
        blighted = false
        waylaid.clear()
    }
}

/**
 * The town works of every capital (`town_works`, kept with the overworld): the works by id, each capital's days, the
 * Marks owed back to players from works called off, and the wagons whose strike a tower's watch kept off ([deterred],
 * for its thanks when they get in).
 */
internal class WorksData(
    var next: Int = 1,
    val works: LinkedHashMap<Int, WorksRec> = LinkedHashMap(),
    val towns: HashMap<Long, TownRec> = HashMap(),
    val owed: LinkedHashMap<UUID, Owed> = LinkedHashMap(),
    val deterred: HashSet<Long> = HashSet(),
) : SavedData() {
    fun changed() = setDirty()

    /** [capital]'s days, kept from the first time its works are looked at. */
    fun town(capital: Long): TownRec = towns.getOrPut(capital) { TownRec(capital) }

    /** Every works in [capital], oldest first. */
    fun of(capital: Long): List<WorksRec> = works.values.filter { it.capital == capital }

    /** [capital]'s works of [kind]: for [road] if it's a tower, for [owner] if it's a stall. */
    fun find(capital: Long, kind: WorksKind, road: Int = -1, owner: UUID? = null): WorksRec? = works.values.firstOrNull {
        it.capital == capital && it.kind == kind &&
            (kind.per != WorksKind.Per.ROAD || it.road == road) && (kind.per != WorksKind.Per.PLAYER || it.owner == owner)
    }

    /** [capital]'s works of [kind] that stands done, if there is one. */
    fun done(capital: Long, kind: WorksKind): WorksRec? = works.values.firstOrNull { it.capital == capital && it.kind == kind && it.state == WorksState.DONE }

    companion object {
        /** Units of each paid material by [Stuff.PAID], kept by their keys. */
        internal val STUFF: Codec<IntArray> = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(
            { map -> IntArray(Stuff.PAID.size).also { a -> for ((k, v) in map) Stuff.PAID.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { a[it] = v } } },
            { a -> Stuff.PAID.indices.filter { a[it] != 0 }.associate { Stuff.PAID[it].key to a[it] } },
        )

        val SITE: Codec<WorksSite> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("origin").forGetter { it.origin.asLong() },
                Direction.CODEC.optionalFieldOf("facing", Direction.NORTH).forGetter(WorksSite::facing),
                BoundingBox.CODEC.fieldOf("lot").forGetter(WorksSite::lot),
                Codec.LONG.fieldOf("crate").forGetter { it.crate.asLong() },
                Codec.LONG.fieldOf("post").forGetter { it.post.asLong() },
                Codec.BOOL.optionalFieldOf("planned", false).forGetter(WorksSite::planned),
            ).apply(i) { origin, facing, lot, crate, post, planned -> WorksSite(BlockPos.of(origin), facing, lot, BlockPos.of(crate), BlockPos.of(post), planned) }
        }

        private val PLEDGE: Codec<PledgeRec> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(PledgeRec::player),
                Codec.STRING.optionalFieldOf("name", "").forGetter(PledgeRec::name),
                Codec.INT.fieldOf("marks").forGetter(PledgeRec::marks),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(PledgeRec::day),
                Codec.BOOL.optionalFieldOf("free", false).forGetter(PledgeRec::free),
            ).apply(i, ::PledgeRec)
        }

        private class Head(
            val id: Int, val capital: Long, val kind: String, val road: Int, val owner: Optional<UUID>, val state: String,
            val price: Int, val parts: Map<String, Int>, val bill: IntArray, val wanted: Long, val why: String,
        )

        private val HEAD: MapCodec<Head> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Head::id),
                Codec.LONG.fieldOf("capital").forGetter(Head::capital),
                Codec.STRING.fieldOf("kind").forGetter(Head::kind),
                Codec.INT.optionalFieldOf("road", -1).forGetter(Head::road),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Head::owner),
                Codec.STRING.optionalFieldOf("state", "wanted").forGetter(Head::state),
                Codec.INT.optionalFieldOf("price", 0).forGetter(Head::price),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("parts", mapOf()).forGetter(Head::parts),
                STUFF.optionalFieldOf("bill", IntArray(Stuff.PAID.size)).forGetter(Head::bill),
                Codec.LONG.optionalFieldOf("wanted", -1L).forGetter(Head::wanted),
                Codec.STRING.optionalFieldOf("why", "").forGetter(Head::why),
            ).apply(i, ::Head)
        }

        private class Yard(val here: IntArray, val used: IntArray, val brought: IntArray, val lost: IntArray, val fill: DoubleArray)

        private val YARD: MapCodec<Yard> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                STUFF.optionalFieldOf("here", IntArray(Stuff.PAID.size)).forGetter(Yard::here),
                STUFF.optionalFieldOf("used", IntArray(Stuff.PAID.size)).forGetter(Yard::used),
                STUFF.optionalFieldOf("brought", IntArray(Stuff.PAID.size)).forGetter(Yard::brought),
                STUFF.optionalFieldOf("lost", IntArray(Stuff.PAID.size)).forGetter(Yard::lost),
                GoodsCodec.CODEC.optionalFieldOf("fill", DoubleArray(Good.entries.size)).forGetter(Yard::fill),
            ).apply(i, ::Yard)
        }

        private class Build(val site: Optional<WorksSite>, val noRoom: Long, val siteLost: Long, val stage: String, val laid: Int, val total: Int)

        private val BUILD: MapCodec<Build> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                SITE.optionalFieldOf("site").forGetter(Build::site),
                Codec.LONG.optionalFieldOf("no_room", -1L).forGetter(Build::noRoom),
                Codec.LONG.optionalFieldOf("site_lost", -1L).forGetter(Build::siteLost),
                Codec.STRING.optionalFieldOf("stage", "").forGetter(Build::stage),
                Codec.INT.optionalFieldOf("laid", 0).forGetter(Build::laid),
                Codec.INT.optionalFieldOf("total", 0).forGetter(Build::total),
            ).apply(i, ::Build)
        }

        private class Times(val funded: Long, val begun: Long, val done: Long, val landed: Long)

        private val TIMES: MapCodec<Times> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.LONG.optionalFieldOf("funded", -1L).forGetter(Times::funded),
                Codec.LONG.optionalFieldOf("begun", -1L).forGetter(Times::begun),
                Codec.LONG.optionalFieldOf("done", -1L).forGetter(Times::done),
                Codec.LONG.optionalFieldOf("landed", Long.MIN_VALUE / 2).forGetter(Times::landed),
            ).apply(i, ::Times)
        }

        private class Rest(val pledges: List<PledgeRec>, val gave: Boolean, val hired: Int, val eased: Map<String, Long>)

        private val REST: MapCodec<Rest> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                PLEDGE.listOf().optionalFieldOf("pledges", listOf()).forGetter(Rest::pledges),
                Codec.BOOL.optionalFieldOf("gave", false).forGetter(Rest::gave),
                Codec.INT.optionalFieldOf("hired", -1).forGetter(Rest::hired),
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("eased", mapOf()).forGetter(Rest::eased),
            ).apply(i, ::Rest)
        }

        private val WORKS: Codec<WorksRec> = RecordCodecBuilder.create { i ->
            i.group(
                HEAD.forGetter { Head(it.id, it.capital, it.kind.id, it.road, Optional.ofNullable(it.owner), it.state.name.lowercase(), it.price, it.parts, it.bill, it.wanted, it.why) },
                YARD.forGetter { Yard(it.here, it.used, it.brought, it.lost, it.fill) },
                BUILD.forGetter { Build(Optional.ofNullable(it.site), it.noRoom, it.siteLost, it.stage?.key ?: "", it.laid, it.total) },
                TIMES.forGetter { Times(it.funded, it.begun, it.done, it.landed) },
                REST.forGetter { Rest(it.pledges, it.gave, it.hired, it.eased) },
            ).apply(i) { h, y, b, t, r ->
                val kind = WorksKind.entries.firstOrNull { it.id == h.kind }
                WorksRec(h.id, h.capital, kind ?: WorksKind.OVEN, h.road, h.owner.orElse(null), h.price, h.parts, h.bill, h.wanted, h.why).also { w ->
                    w.unknown = kind == null
                    w.state = WorksState.entries.firstOrNull { it.name.lowercase() == h.state } ?: WorksState.WANTED
                    y.here.copyInto(w.here)
                    y.used.copyInto(w.used)
                    y.brought.copyInto(w.brought)
                    y.lost.copyInto(w.lost)
                    y.fill.copyInto(w.fill)
                    w.site = b.site.orElse(null)
                    w.noRoom = b.noRoom
                    w.siteLost = b.siteLost
                    w.stage = Stage.entries.firstOrNull { it.key == b.stage }
                    w.laid = b.laid
                    w.total = b.total
                    w.funded = t.funded
                    w.begun = t.begun
                    w.done = t.done
                    w.landed = t.landed
                    w.pledges.addAll(r.pledges)
                    w.gave = r.gave
                    w.hired = r.hired
                    w.eased.putAll(r.eased)
                }
            }
        }

        private val TOWN: Codec<TownRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(TownRec::capital),
                Codec.LONG.optionalFieldOf("closed", Long.MIN_VALUE / 2).forGetter(TownRec::closed),
                Codec.INT.optionalFieldOf("short", 0).forGetter(TownRec::short),
                Codec.DOUBLE.optionalFieldOf("low", Double.MAX_VALUE).forGetter(TownRec::low),
                Codec.INT.listOf().optionalFieldOf("waylaid", listOf()).forGetter { it.waylaid.toList() },
                Codec.BOOL.optionalFieldOf("cracked", false).forGetter(TownRec::cracked),
                Codec.BOOL.optionalFieldOf("blighted", false).forGetter(TownRec::blighted),
            ).apply(i) { capital, closed, short, low, waylaid, cracked, blighted ->
                TownRec(capital).also { t ->
                    t.closed = closed
                    t.short = short
                    t.low = low
                    t.waylaid.addAll(waylaid)
                    t.cracked = cracked
                    t.blighted = blighted
                }
            }
        }

        private val OWED: Codec<Pair<UUID, Owed>> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter { it.first },
                Codec.STRING.optionalFieldOf("name", "").forGetter { it.second.name },
                Codec.INT.fieldOf("marks").forGetter { it.second.marks },
            ).apply(i) { player, name, marks -> player to Owed(name, marks) }
        }

        private val CODEC: Codec<WorksData> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("next", 1).forGetter(WorksData::next),
                WORKS.listOf().optionalFieldOf("works", listOf()).forGetter { it.works.values.toList() },
                TOWN.listOf().optionalFieldOf("towns", listOf()).forGetter { it.towns.values.toList() },
                OWED.listOf().optionalFieldOf("owed", listOf()).forGetter { d -> d.owed.map { (p, o) -> p to o } },
                Codec.LONG.listOf().optionalFieldOf("deterred", listOf()).forGetter { it.deterred.toList() },
            ).apply(i) { next, works, towns, owed, deterred ->
                val kept = works.filter { !it.unknown }
                WorksData(
                    maxOf(next, (kept.maxOfOrNull { it.id } ?: 0) + 1),
                    LinkedHashMap(kept.associateBy { it.id }),
                    HashMap(towns.associateBy { it.capital }),
                    LinkedHashMap(owed.toMap()),
                    HashSet(deterred),
                )
            }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "town_works"), Supplier { WorksData() }, CODEC)

        /** The world's, kept with the overworld whichever [level] asks. */
        fun get(level: ServerLevel): WorksData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
