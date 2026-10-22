package io.github.vaspyyy.guildmark.lamps

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import net.minecraft.core.BlockPos

/**
 * One town's street lamps as [StreetLamps] keeps them while players are about it: where each lamp is in the world,
 * what it was last set to, and its [Run]s (the rounds the lamps are lit and put out in) with how far each has got this
 * evening or this morning. A capital's ([capital]) is made from its [LampPlan] once that is worked out, a village's
 * ([village]) from the posts its census found ([VillageLampPlan]). The town goes by its [key]: a capital's centre, a village's
 * bell. The [plan] numbers its lamps in its own frame, and [world] says where a point of it is in the world (a capital's frame is
 * the city's own, a village's the world's).
 */
class LampTown(val key: BlockPos, val places: TownPlaces, val plan: LampSet, world: (Int, Int, Int) -> BlockPos, val capital: CapitalPiece? = null) {
    /** Whether this is a village's. */
    val isVillage: Boolean get() = capital == null

    /** Each lamp's lantern in the world. */
    val pos: Array<BlockPos> = Array(plan.lamps.size) { i -> plan.lamps[i].at.let { world(it.x, it.y, it.z) } }

    /** Where a lamplighter may stand to each lamp, a block from its post facing it, best first (see [LampPlan.Lamp.stands]). */
    val stands: Array<List<BlockPos>> = Array(plan.lamps.size) { i ->
        val l = plan.lamps[i]
        l.stands.map { d -> world(l.at.x + d.dx, l.at.y - 3, l.at.z + d.dz) }
    }

    /** What each lamp was last set to by the keeper or a lamplighter ([UNKNOWN] until it's been looked at). */
    val applied = ByteArray(plan.lamps.size)

    /** Game time before which a lamp left unknown (an older capital's lantern in sight) isn't looked at again. */
    val retry = LongArray(plan.lamps.size)

    /** The rounds: the lamplighters' (in [LampPlan.rounds]' order), then a farmhouse's, then each lone lamp out in the country. */
    val runs: List<Run>

    /** For each lamp, its run and its place in it. */
    val runOf = IntArray(plan.lamps.size)
    val placeOf = IntArray(plan.lamps.size)

    /** The last time a player was about the capital. */
    var seen = 0L

    /** The night (noon to noon, see [StreetLamps.night]) the runs are set for, the furthest stretch of it reached, and that stretch's key. */
    var night = Long.MIN_VALUE
    var reached = 0
    var window = Long.MIN_VALUE

    /** Whether it rained as this evening's rounds began, and as this morning's did: they began that much earlier (or later). */
    var rainyDusk = false
    var rainyDawn = false

    /**
     * Whether tonight's lamps are rationed (candles short, see [StreetLamps.Rationing]): the lamplighters light every
     * other lamp, and the rest catch by themselves a little before seven ([StreetLamps.skipped]). Settled as the
     * evening's rounds begin.
     */
    var rationed = false

    init {
        val list = ArrayList<Run>()
        for (r in plan.rounds) {
            val offsets = IntArray(r.lamps.size)
            var walked = 0
            for (k in r.lamps.indices) {
                walked += r.legs[k]
                offsets[k] = LampPlan.ticks(k, walked)
            }
            list.add(Run(list.size, Kind.CITY, r.lamps, offsets, -1, r.index))
        }
        val farms = plan.lamps.filter { !it.city && it.farm >= 0 }.groupBy { it.farm }
        for ((farm, lamps) in farms.toSortedMap()) {
            val ids = lamps.map { it.index }.toIntArray()
            list.add(Run(list.size, Kind.FARM, ids, IntArray(ids.size) { k -> k * FARM_LAMP_TICKS }, farm, -1))
        }
        for (l in plan.lamps) if (!l.city && l.farm < 0) list.add(Run(list.size, Kind.LONE, intArrayOf(l.index), intArrayOf(0), -1, -1))
        runs = list
        for (run in runs) run.lamps.forEachIndexed { k, i ->
            runOf[i] = run.index
            placeOf[i] = k
        }
    }

    /**
     * A village's posts as they stood when this town was made ([stamp]): once its census finds them otherwise, the town is made
     * again from the new ones, carrying on from this one ([carry]).
     */
    var stamp = 0L

    /**
     * Takes up where [old] (the same village's lamps, before its posts changed) left off: what each lamp that stands still was last
     * set to, and the night, the weather and the candles as they were.
     */
    fun carry(old: LampTown) {
        val was = HashMap<Long, Int>(old.pos.size * 2)
        old.pos.forEachIndexed { i, at -> was[at.asLong()] = i }
        for (i in pos.indices) was[pos[i].asLong()]?.let { j -> applied[i] = old.applied[j] }
        night = old.night
        reached = old.reached
        rainyDusk = old.rainyDusk
        rainyDawn = old.rainyDawn
        rationed = old.rationed
        seen = old.seen
    }

    /** A lamp's phase (0 to 1): how far into the lighting-up its clock has it, nobody seeing to it. */
    fun phase(i: Int): Double = plan.lamps[i].phase

    /** The lamp nearest [at] (null if there are none). */
    fun nearest(at: BlockPos): Int? = pos.indices.minByOrNull { pos[it].distSqr(at) }

    enum class Kind {
        /** A lamplighter's round of the street lamps. */
        CITY,
        /** The lamps at a farm's gate, which its farmer sees to coming in from the fields and going out to them. */
        FARM,
        /** A lamp out on a road or a bridge with no farmhouse by, or on the harbour's quays and piers: it keeps its own time. */
        LONE,
    }

    /**
     * A round of lamps lit (at dusk) or put out (at dawn) one after another: [lamps] in order, [offsets] the ticks from
     * the round's start to each by the clock (a lamplighter's pace); [farm] the farmhouse's lodging for a farm's lamps,
     * [round] the plan's round for a lamplighter's.
     */
    class Run(val index: Int, val kind: Kind, val lamps: IntArray, val offsets: IntArray, val farm: Int, val round: Int) {
        /** About how long the round takes, in ticks. */
        val ticks: Int get() = offsets.last() + LampPlan.LAMP_TICKS

        /** When the clock has the round begin this evening or morning (ticks since noon, see [StreetLamps.since]). */
        var start = 0

        /** The resident who sees to it ([io.github.vaspyyy.guildmark.townsfolk.Resident.id]), or -1 for none. */
        var keeper = -1

        /** The lamplighter going round it just now (their director), and the game time they last said so. */
        var held: Director? = null
        var heldAt = 0L

        /** How many of its lamps its lamplighter has seen to, while [held]. */
        var done = 0

        /** Game time its keeper is next looked for, while nobody goes round it. */
        var lookAt = 0L

        /** A round set going by the testing command: [LIT] or [OUT] for its lamps until the day's next stretch; 0 for none. */
        var force: Byte = 0

        /** How many of its lamps are seen to by [time] (ticks since noon): its lamplighter's count, or the clock's. */
        fun progress(time: Int): Int {
            if (held != null) return done
            var k = 0
            while (k < lamps.size && start + offsets[k] <= time) k++
            return k
        }

        override fun toString(): String = "${kind.name.lowercase()} round $index (${lamps.size} lamps)"
    }

    companion object {
        /** A capital's lamps: the city's frame is [capital]'s, its key the capital's centre. */
        fun capital(capital: CapitalPiece, places: CityPlaces, plan: LampPlan): LampTown =
            LampTown(capital.center, places, plan, { x, y, z -> capital.world(x, y, z) }, capital)

        /**
         * A village's lamps: [posts] are the lanterns on the posts its census found (see [VillagePlaces.lamps]), points of the
         * world itself; the town goes by the village's bell, and has a round or two of them ([VillageLampPlan]).
         */
        fun village(rec: VillageRec, places: VillagePlaces, posts: List<BlockPos>): LampTown {
            val bell = BlockPos.of(rec.key)
            return LampTown(bell, places, VillageLampPlan(bell, posts), { x, y, z -> BlockPos(x, y, z) }).also { it.stamp = stamp(posts) }
        }

        /** A number for a set of posts, the same whatever order the census lists them in. */
        fun stamp(posts: Collection<BlockPos>): Long {
            var sum = 0L
            for (p in posts.toSet()) {
                var h = p.asLong() * -0x61C8864680B583EBL
                h = (h xor (h ushr 29)) * -0x4B47D5FB3AC29A3FL
                sum += h xor (h ushr 32)
            }
            return sum
        }

        const val UNKNOWN: Byte = 0
        const val LIT: Byte = 1
        const val OUT: Byte = 2

        /** Ticks between the lamps at one farm's gate. */
        const val FARM_LAMP_TICKS = 80
    }
}
