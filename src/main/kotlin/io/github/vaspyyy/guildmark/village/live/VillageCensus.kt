package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Spot
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One building the census found: its [kind] (the strings the daily-life code looks at: HOUSE, GUILD_HALL, LIBRARY, TEMPLE,
 * SMITHY, BAKERY, WORKSHOP, BARN or TOWER), the [box] round it, the height [y] its people stand at on the ground floor and the
 * other [floors], and what stands in it. [partial] when part of it was not loaded, or it was too big to follow to its end.
 * [lodging] makes the [CityPlaces.Lodging] the daily-life code reads, with the id the village's record gave it.
 */
class Building(
    val kind: String, val box: BoundingBox, val y: Int, val floors: List<Int>, val doors: List<CityPlaces.Door>,
    val ladders: List<CityPlaces.Ladder>, val beds: MutableList<BlockPos>, val seats: List<Spot>, val tables: List<BlockPos>,
    val stations: List<CityPlaces.Station>, val stores: List<BlockPos>, val partial: Boolean,
) {
    /** Where the home is kept: the door's lower half, or where there is no door the first bed, or else the middle of the box. */
    val door: BlockPos get() = doors.firstOrNull()?.pos ?: beds.firstOrNull() ?: box.center

    fun lodging(id: Int): CityPlaces.Lodging = CityPlaces.Lodging(id, kind, box, y, floors, doors, ladders, beds.toList(), seats, tables, stations, stores)
}

/** What a census cost: ticks it took, nanoseconds in all and in the worst tick, in each slice (A to E), blocks read, columns, chunks asked for that were not loaded. */
class Stats(val ticks: Int, val nanos: Long, val worstTick: Long, val slices: LongArray, val reads: Long, val columns: Int, val misses: Long)

/**
 * Everything one census found, in the shapes the village's places take (see [VillagePlaces]). Kept in memory while the village is
 * awake, and replaced by the next census.
 */
class Found(
    val bell: BlockPos, val radius: Int, val board: BlockPos?, val boardFacing: Direction?, val advances: Int,
    val buildings: List<Building>, val towers: List<BlockPos>, val jobs: List<CityPlaces.Station>,
    val wells: List<BlockPos>, val lamps: List<BlockPos>, val benches: List<Spot>, val stalls: List<CityPlaces.Stall>,
    val fields: List<CityPlaces.Field>, val green: BlockPos, val barns: Int, val coops: Int, val cattle: Int, val hens: Int,
    val at: Long, val partial: Boolean, val stats: Stats,
) {
    /** The cells of crops (or of farmland waiting for them) in all the fields. */
    val crops: Int get() = fields.sumOf { it.crops.size }

    /** The beds in all the buildings. */
    val beds: Int get() = buildings.sumOf { it.beds.size }

    /** What it found, by name, for the readout of what a census changed. */
    fun counts(): LinkedHashMap<String, Int> = linkedMapOf(
        "homes" to buildings.count { it.beds.isNotEmpty() }, "beds" to beds, "other buildings" to buildings.count { it.beds.isEmpty() && it.kind != "TOWER" },
        "towers" to buildings.count { it.kind == "TOWER" }, "wells" to wells.size, "benches" to benches.size, "lamps" to lamps.size,
        "stalls" to stalls.size, "fields" to fields.size, "crops" to crops, "work blocks" to jobs.size, "barns" to barns, "coops" to coops,
    )
}

/**
 * The census (living-world/villages/design.md section 4.3): what a village is made of, read from the world in slices, a little each
 * tick, never loading a chunk. A [Job] is one village's count, worked through by [Job.step] under a deadline and a number of
 * columns, and finished when [Job.done]; [merge] then puts what it found into the village's record.
 *
 * - Slice A reads the points of interest (beds, job blocks, the board).
 * - Slice B floods out from each household's beds, and from each job block that no home takes in, to find the buildings, their doors,
 *   and what stands in them; and, at the end, the towers.
 * - Slice C reads the top of every column of the village's ground: farmland, wells, lamp posts, benches, stalls, Archer Posts.
 * - Slice D counts the pens of farm animals.
 * - Slice E finds the middle of the green and the board.
 */
object VillageCensus {
    /** How far from the bell the points of interest are read: the same 64 as the board's search. */
    const val SEARCH = 64

    /** Beds this close to each other (across, and 8 up or down) are one household; a household spans 16 blocks at most. */
    const val BED_JOIN = 7
    const val SPAN = 16
    const val MAX_BEDS = 400
    const val MAX_JOBS = 120

    /** Most buildings with beds, and most buildings of any kind. */
    const val MAX_HOMES = 40
    const val MAX_BUILDINGS = 64

    /**
     * How far past its beds a household's flood may reach (a bed can be at the far end of a house from its door), and past a job block
     * that of a building with no beds.
     */
    const val REACH = 12
    const val JOB_REACH = 10

    /** How close to the bell the column pass looks down into a roofed well. */
    const val WELL_LOOK = 12

    /** The cap on what the column pass keeps. */
    const val MAX_FIELD_CELLS = 6000
    const val MAX_WATER = 128
    const val MAX_LAMPS = 64
    const val MAX_BENCHES = 64
    const val MAX_WELLS = 8

    /** Farmland this close (across) is one field, and a field has at least this many cells. */
    const val FIELD_GAP = 2
    const val FIELD_LEAST = 3

    /** The green is the open ground within this of the bell. */
    const val GREEN = 8

    private const val SLICES = 5

    private val DX = intArrayOf(1, -1, 0, 0, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1, 0, 0)
    private val DZ = intArrayOf(0, 0, 0, 0, 1, -1)

    /** The one flood and the one scanner every census shares: only one census runs at a time. */
    private val flood = Flood()
    private val scanner = Scanner()

    /** What the census cost since the world opened (see `village census`). */
    object Perf {
        var runs = 0
            private set
        var ticks = 0L
            private set
        var nanos = 0L
            private set
        var worstTick = 0L
            private set
        var worstRun = 0L
            private set
        var reads = 0L
            private set
        var columns = 0L
            private set
        val slices = LongArray(SLICES)

        /** A tick of the census, as the budget gave it (a count in the middle of the others, or the one that finished): [spent] nanoseconds in all. */
        fun tick(spent: Long) {
            ticks++
            nanos += spent
            if (spent > worstTick) worstTick = spent
        }

        /** A count is done: what it read and where its time went, by slice. (Its ticks are told by [tick], as they happen.) */
        fun add(s: Stats) {
            runs++
            if (s.nanos > worstRun) worstRun = s.nanos
            reads += s.reads
            columns += s.columns
            for (i in 0 until SLICES) slices[i] += s.slices[i]
        }

        fun forget() {
            runs = 0
            ticks = 0L
            nanos = 0L
            worstTick = 0L
            worstRun = 0L
            reads = 0L
            columns = 0L
            java.util.Arrays.fill(slices, 0L)
        }
    }

    // ---- Merging into the record ----

    /** What a census changed in the record's homes. */
    class Diff(val added: Int, val gone: Int, val back: Int, val homes: Int, val beds: Int, val bedsBefore: Int)

    /**
     * Puts [found]'s buildings into [rec]'s homes and answers the id of each (in the order of [Found.buildings]). A home keeps its id
     * if its door is within 3 blocks of a known one's (or they share a bed); a new one is appended; a home whose door and beds are
     * both gone, in chunks that were loaded, is marked gone; and a bed with a sleeper in it is never taken out (it waits for the next
     * count). The record keeps the board, the radius and the size the count found, and the name it was given the first time.
     */
    fun merge(rec: VillageRec, found: Found, t: Terrain): Pair<List<Int>, Diff> {
        val before = rec.homes.filter { !it.gone }
        val bedsBefore = before.sumOf { it.beds.size }
        val used = HashSet<Int>()
        val ids = ArrayList<Int>(found.buildings.size)
        var added = 0
        var back = 0
        var nextId = (rec.homes.maxOfOrNull { it.id } ?: -1) + 1
        for (b in found.buildings) {
            val door = b.door
            var best = -1
            var bestScore = Int.MAX_VALUE
            for ((k, h) in rec.homes.withIndex()) {
                if (h.id in used) continue
                val hp = BlockPos.of(h.door)
                val near = maxOf(abs(hp.x - door.x), abs(hp.z - door.z)).takeIf { abs(hp.y - door.y) <= 3 && it <= 3 }
                val shares = b.beds.any { it.asLong() in h.beds }
                if (near == null && !shares) continue
                val score = near ?: 4
                if (score < bestScore) {
                    bestScore = score
                    best = k
                }
            }
            if (best >= 0) {
                val h = rec.homes[best]
                used.add(h.id)
                ids.add(h.id)
                if (h.gone) back++
                // A bed with a sleeper in it stays in the home until the next count
                val keep = h.beds.filter { it !in b.beds.map { p -> p.asLong() } && sleeping(t, it) }
                val beds = b.beds.map { it.asLong() } + keep
                rec.homes[best] = Home(h.id, door.asLong(), beds, b.kind, false)
            } else {
                val id = nextId++
                used.add(id)
                ids.add(id)
                added++
                rec.homes.add(Home(id, door.asLong(), b.beds.map { it.asLong() }, b.kind, false))
            }
        }
        var gone = 0
        for ((k, h) in rec.homes.withIndex()) {
            if (h.id in used || h.gone) continue
            if (!standsNoMore(t, h)) continue
            rec.homes[k] = Home(h.id, h.door, h.beds, h.kind, true)
            gone++
        }
        // The list only grows; the oldest homes that are gone go first past the cap
        while (rec.homes.size > MAX_HOME_RECORDS) {
            val old = rec.homes.indexOfFirst { it.gone }
            if (old < 0) break
            rec.homes.removeAt(old)
        }
        rec.radius = found.radius
        rec.counted = found.at
        rec.board = found.board?.asLong() ?: if (boardGone(t, rec.board)) -1L else rec.board
        if (found.board != null) rec.size = Size.of(found.advances)
        val after = rec.homes.filter { !it.gone }
        return ids to Diff(added, gone, back, after.size, after.sumOf { it.beds.size }, bedsBefore)
    }

    /** The most homes (gone ones too) a record keeps. */
    const val MAX_HOME_RECORDS = 160

    /** Whether the bed at [bed] (a `BlockPos.asLong()`) still stands with someone asleep in it. */
    private fun sleeping(t: Terrain, bed: Long): Boolean {
        val p = BlockPos.of(bed)
        val s = t.state(p.x, p.y, p.z) ?: return false
        return Cls.of(s) and Cls.BED_HEAD != 0 && s.hasProperty(BlockStateProperties.OCCUPIED) && s.getValue(BlockStateProperties.OCCUPIED)
    }

    /**
     * Whether a home has been looked at and found gone: its door is no door and none of its beds is a bed, and every one of those
     * blocks is in a chunk that is loaded. A home any part of which could not be looked at is left as it was.
     */
    private fun standsNoMore(t: Terrain, h: Home): Boolean {
        val door = BlockPos.of(h.door)
        val s = t.state(door.x, door.y, door.z) ?: return false
        if (Cls.of(s) and (Cls.DOOR or Cls.BED_HEAD) != 0) return false
        for (bed in h.beds) {
            val p = BlockPos.of(bed)
            val b = t.state(p.x, p.y, p.z) ?: return false
            if (Cls.of(b) and Cls.BED_HEAD != 0) return false
        }
        return true
    }

    /** Whether the board at [board] (or none) was in a loaded chunk and is not there now. */
    private fun boardGone(t: Terrain, board: Long): Boolean {
        if (board < 0L) return false
        val p = BlockPos.of(board)
        if (!t.loaded(p.x shr 4, p.z shr 4)) return false
        return t.board(board) == null
    }

    /** What changed between two counts, as words ("homes 12 to 13, wells 1 to 2"), or "nothing changed". */
    fun changes(old: Found?, now: Found): String {
        if (old == null) return "the first count"
        val a = old.counts()
        val out = ArrayList<String>()
        for ((k, v) in now.counts()) {
            val was = a[k] ?: 0
            if (was != v) out.add("$k $was to $v")
        }
        return if (out.isEmpty()) "nothing changed" else out.joinToString(", ")
    }

    // ---- One village's count ----

    private enum class Phase { POIS, HOMES, COLUMNS, FINISH, LIVESTOCK, GREEN, DONE }
    private enum class Sub { IDLE, FLOODING, SCANNING }
    private enum class Finish { WELLS, STALLS, FIELDS, TOWERS }

    /**
     * One census of the village whose bell is at [bell] (key [key]), begun at game time [now]. [old] is the last one, whose parts in
     * chunks that are not loaded now carry over. [notify] is the player who asked for it, to be told when it is done.
     */
    class Job(val key: Long, val bell: BlockPos, private val old: Found?, val notify: UUID?, val now: Long) {
        private var phase = Phase.POIS
        val done: Boolean get() = phase == Phase.DONE

        /** The census, once [done]. */
        var found: Found? = null
            private set

        /** The name of the slice it is in, for the readout. */
        val slice: String get() = when (phase) {
            Phase.POIS -> "A, points of interest"
            Phase.HOMES -> "B, homes"
            Phase.COLUMNS, Phase.FINISH -> "C, the columns"
            Phase.LIVESTOCK -> "D, livestock"
            Phase.GREEN, Phase.DONE -> "E, the green"
        }

        // What it cost
        private var ticks = 0
        private var nanos = 0L
        private var worst = 0L
        private val slices = LongArray(SLICES)
        private var reads = 0L
        private var misses = 0L
        private var counted = 0

        // Slice A
        private val cxFrom = (bell.x - SEARCH) shr 4
        private val cxTo = (bell.x + SEARCH) shr 4
        private val czTo = (bell.z + SEARCH) shr 4
        private var pcx = cxFrom
        private var pcz = (bell.z - SEARCH) shr 4
        private val raw = ArrayList<Poi>()
        private var radius = 24
        private var beds: List<Long> = emptyList()
        private var jobs: List<Long> = emptyList()
        private var board: BlockPos? = null
        private var boardFacing: Direction? = null
        private var advances = 0

        // Slice B
        private var clusters: List<List<Long>> = emptyList()
        private var clusterIdx = 0
        private var jobIdx = 0
        private var sub = Sub.IDLE
        private var curBeds: List<Long> = emptyList()
        private var curJob: BlockPos? = null
        private var curM: M? = null
        private var sx0 = 0
        private var sy0 = 0
        private var sz0 = 0
        private var sx1 = 0
        private var sy1 = 0
        private var sz1 = 0
        private val buildings = ArrayList<Building>()
        private val stations = ArrayList<CityPlaces.Station>()
        private var homeCount = 0
        private var unloadedParts = false

        // Slice C
        private var colX = bell.x
        private var colZ = bell.z
        private var colDone = false
        private val fieldCells = ArrayList<Long>()
        private val waters = ArrayList<Long>()
        private val lamps = ArrayList<BlockPos>()
        private val wool = HashSet<Long>()
        private val posts = ArrayList<BlockPos>()
        private var finish = Finish.WELLS
        private var cursor = 0
        private val wells = ArrayList<BlockPos>()
        private val stalls = ArrayList<CityPlaces.Stall>()
        private val benches = ArrayList<Spot>()
        private val fields = ArrayList<CityPlaces.Field>()
        private val towers = ArrayList<BlockPos>()
        private var towerClusters: List<List<BlockPos>> = emptyList()

        // Slices D and E
        private var barns = 0
        private var coops = 0
        private var cattle = 0
        private var hens = 0
        private var green: BlockPos = bell

        /**
         * Works on the count until [deadline] (a `System.nanoTime()`), reading at most [columns] columns of the ground, and answers how
         * many it read. Does at least a little whenever it is called with time left. When [done], [found] has the count.
         */
        fun step(t: Terrain, deadline: Long, columns: Int): Int {
            val start = System.nanoTime()
            var used = 0
            ticks++
            while (phase != Phase.DONE) {
                val before = System.nanoTime()
                if (before >= deadline) break
                val at = sliceIndex()
                when (phase) {
                    Phase.POIS -> if (readPois(t, deadline)) phase = Phase.HOMES
                    Phase.HOMES -> if (homes(t, deadline)) phase = Phase.COLUMNS
                    Phase.COLUMNS -> {
                        if (colX == Int.MIN_VALUE) {
                            colX = bell.x - radius
                            colZ = bell.z - radius
                        }
                        used += columnsStep(t, deadline, columns - used)
                        if (colDone) phase = Phase.FINISH
                    }
                    Phase.FINISH -> if (finishUp(t, deadline)) phase = Phase.LIVESTOCK
                    Phase.LIVESTOCK -> {
                        livestock(t)
                        phase = Phase.GREEN
                    }
                    Phase.GREEN -> {
                        green(t)
                        phase = Phase.DONE
                    }
                    Phase.DONE -> {}
                }
                slices[at] += System.nanoTime() - before
                if (phase == Phase.COLUMNS && used >= columns) break
            }
            val spent = System.nanoTime() - start
            nanos += spent
            if (spent > worst) worst = spent
            reads += t.reads
            misses += t.misses
            counted += used
            if (phase == Phase.DONE && found == null) found = assemble(t)
            return used
        }

        private fun sliceIndex(): Int = when (phase) {
            Phase.POIS -> 0
            Phase.HOMES -> 1
            Phase.COLUMNS -> 2
            Phase.FINISH -> if (finish == Finish.TOWERS) 1 else 2
            Phase.LIVESTOCK -> 3
            else -> 4
        }

        // ---- Slice A: points of interest ----

        private fun readPois(t: Terrain, deadline: Long): Boolean {
            while (pcz <= czTo) {
                while (pcx <= cxTo) {
                    t.pois(pcx, pcz, raw)
                    pcx++
                    if (System.nanoTime() >= deadline) return false
                }
                pcx = cxFrom
                pcz++
            }
            derive(t)
            return true
        }

        /** The radius from the furthest bed, the beds and job blocks that belong, the board and its size; then the households. */
        private fun derive(t: Terrain) {
            val bx = bell.x
            val bz = bell.z
            var furthest = -1.0
            val bedPois = ArrayList<Pair<Double, Long>>()
            val jobPois = ArrayList<Pair<Double, Long>>()
            var boardAt: BlockPos? = null
            var boardD = Double.MAX_VALUE
            for (p in raw) {
                val px = BlockPos.getX(p.pos)
                val py = BlockPos.getY(p.pos)
                val pz = BlockPos.getZ(p.pos)
                val dx = (px - bx).toDouble()
                val dz = (pz - bz).toDouble()
                val dy = (py - bell.y).toDouble()
                val flat = sqrt(dx * dx + dz * dz)
                when (p.kind) {
                    PoiKind.BED -> {
                        if (dx * dx + dy * dy + dz * dz <= SEARCH.toDouble() * SEARCH) if (flat > furthest) furthest = flat
                        bedPois.add(flat to p.pos)
                    }
                    PoiKind.JOB -> jobPois.add(flat to p.pos)
                    PoiKind.BOARD -> if (maxOf(abs(dx), abs(dz)) <= BOARD_REACH) {
                        val d = dx * dx + dy * dy + dz * dz
                        if (d < boardD) {
                            boardD = d
                            boardAt = BlockPos(px, py, pz)
                        }
                    }
                    else -> {}
                }
            }
            // As `Advances.villageRadius` has it: just outside the furthest bed, kept between 20 and 48 (18 with no bed)
            radius = ((if (furthest < 0.0) 18.0 else furthest).toInt() + 6).coerceIn(20, 48)
            // The furthest bed may lie in ground that is not loaded: the village does not shrink for what could not be read
            if (old != null && old.radius > radius && unloadedWithin(t, old.radius)) radius = old.radius
            val reach = radius + GREEN.toDouble()
            beds = bedPois.filter { it.first <= reach }.sortedBy { it.first }.take(MAX_BEDS).map { it.second }
            jobs = jobPois.filter { it.first <= reach }.sortedBy { it.first }.take(MAX_JOBS).map { it.second }
            val look = boardAt?.let { t.board(it.asLong()) }
            if (look != null) {
                board = boardAt
                boardFacing = look.facing
                advances = look.advances
            }
            clusters = households()
            colX = Int.MIN_VALUE
        }

        /** The beds in households: beds within [BED_JOIN] of one another, a household spanning [SPAN] at most, the nearest the bell first. */
        private fun households(): List<List<Long>> {
            val n = beds.size
            if (n == 0) return emptyList()
            val pos = beds.map { BlockPos.of(it) }
            val taken = BooleanArray(n)
            val out = ArrayList<List<Long>>()
            for (i in 0 until n) {
                if (taken[i]) continue
                taken[i] = true
                val group = arrayListOf(i)
                var minX = pos[i].x
                var maxX = minX
                var minZ = pos[i].z
                var maxZ = minZ
                var k = 0
                while (k < group.size) {
                    val a = pos[group[k++]]
                    for (j in 0 until n) {
                        if (taken[j]) continue
                        val b = pos[j]
                        if (abs(a.x - b.x) > BED_JOIN || abs(a.z - b.z) > BED_JOIN || abs(a.y - b.y) > BED_JOIN + 1) continue
                        if (maxOf(maxX, b.x) - minOf(minX, b.x) > SPAN || maxOf(maxZ, b.z) - minOf(minZ, b.z) > SPAN) continue
                        taken[j] = true
                        group.add(j)
                        minX = minOf(minX, b.x)
                        maxX = maxOf(maxX, b.x)
                        minZ = minOf(minZ, b.z)
                        maxZ = maxOf(maxZ, b.z)
                    }
                }
                out.add(group.map { beds[it] })
            }
            return out
        }

        // ---- Slice B: homes and other buildings ----

        private fun homes(t: Terrain, deadline: Long): Boolean {
            while (true) {
                if (System.nanoTime() >= deadline) return false
                when (sub) {
                    Sub.IDLE -> if (!nextTarget(t)) return true
                    Sub.FLOODING -> if (flood.run(t, deadline)) startScan(t) else return false
                    Sub.SCANNING -> if (scanner.run(t, deadline)) {
                        finishBuilding()
                        sub = Sub.IDLE
                    } else return false
                }
            }
        }

        /** Sets up the next flood: a household's, then each job block no building takes in. False when there are no more. */
        private fun nextTarget(t: Terrain): Boolean {
            while (clusterIdx < clusters.size) {
                val cluster = clusters[clusterIdx++]
                if (homeCount >= MAX_HOMES || buildings.size >= MAX_BUILDINGS) {
                    clusterIdx = clusters.size
                    break
                }
                // Beds in a building already found belong to it
                val into = buildings.firstOrNull { b -> cluster.any { b.box.isInside(BlockPos.of(it)) } }
                if (into != null) {
                    for (bed in cluster) {
                        val p = BlockPos.of(bed)
                        if (into.box.isInside(p) && into.beds.none { it == p }) into.beds.add(p)
                    }
                    continue
                }
                val ps = cluster.map { BlockPos.of(it) }
                flood.begin(
                    ps.minOf { it.x } - REACH, ps.minOf { it.y } - 4, ps.minOf { it.z } - REACH,
                    ps.maxOf { it.x } + REACH, ps.maxOf { it.y } + 10, ps.maxOf { it.z } + REACH,
                )
                for (p in ps) flood.seed(t, p.x, p.y, p.z)
                curBeds = cluster
                curJob = null
                curM = null
                sub = Sub.FLOODING
                return true
            }
            while (jobIdx < jobs.size) {
                val pos = BlockPos.of(jobs[jobIdx++])
                val s = t.state(pos.x, pos.y, pos.z)
                if (s == null) {
                    unloadedParts = true
                    continue
                }
                val m = Cls.station(Cls.of(s)) ?: Cls.counter(s)
                if (m != null) stations.add(CityPlaces.Station(pos, m))
                if (m == null || buildings.size >= MAX_BUILDINGS) continue
                if (buildings.any { it.box.isInside(pos) }) continue
                flood.begin(pos.x - JOB_REACH, pos.y - 3, pos.z - JOB_REACH, pos.x + JOB_REACH, pos.y + 8, pos.z + JOB_REACH)
                for (k in 0 until 6) flood.seed(t, pos.x + DX[k], pos.y + DY[k], pos.z + DZ[k])
                curBeds = emptyList()
                curJob = pos
                curM = m
                sub = Sub.FLOODING
                return true
            }
            return false
        }

        /** The flood is done: scan the box round the inside for what stands in it. */
        private fun startScan(t: Terrain) {
            if (flood.unloaded) unloadedParts = true
            // Round a job block, a room has walls: a canopy on posts, or a corner of open ground with a roof over it, is not a building
            if (flood.cells >= 2 && (curJob == null || (!flood.capped && flood.solid >= 2 * flood.loose))) {
                sx0 = flood.minX - 1
                sy0 = flood.minY - 1
                sz0 = flood.minZ - 1
                sx1 = flood.maxX + 1
                sy1 = flood.maxY + 1
                sz1 = flood.maxZ + 1
            } else if (curJob == null) {
                // Beds under the open sky (or in a room that could not be read): a few blocks round them
                val ps = curBeds.map { BlockPos.of(it) }
                sx0 = ps.minOf { it.x } - 2
                sy0 = ps.minOf { it.y } - 1
                sz0 = ps.minOf { it.z } - 2
                sx1 = ps.maxOf { it.x } + 2
                sy1 = ps.maxOf { it.y } + 2
                sz1 = ps.maxOf { it.z } + 2
            } else {
                // A job block out in the open is no building
                sub = Sub.IDLE
                return
            }
            scanner.begin(sx0, sy0, sz0, sx1, sy1, sz1)
            sub = Sub.SCANNING
        }

        /** The scan is done: the building it was of. */
        private fun finishBuilding() {
            val bedMap = LinkedHashMap<Long, BlockPos>()
            for (b in curBeds) bedMap[b] = BlockPos.of(b)
            for (b in scanner.beds) bedMap[b.asLong()] = b
            val doors = flood.doors.toList()
            val bedList = bedMap.values.toMutableList()
            val ground = doors.firstOrNull()?.pos?.y ?: bedList.minOfOrNull { it.y } ?: (sy0 + 1)
            val floors = (listOf(ground) + bedList.map { it.y }).distinct().sorted()
            val kind = kindOf(bedList.size, scanner, curM)
            val b = Building(
                kind, BoundingBox(sx0, sy0, sz0, sx1, sy1 + 2, sz1), ground, floors, doors, scanner.ladders(), bedList,
                scanner.seats.toList(), scanner.tables.toList(), scanner.stations.toList(), scanner.stores.toList(), flood.unloaded || flood.capped,
            )
            buildings.add(b)
            if (bedList.isNotEmpty()) homeCount++
        }

        // ---- Slice C: the columns ----

        /** Reads columns from where it left off, a row of the square at a time; answers how many. */
        private fun columnsStep(t: Terrain, deadline: Long, budget: Int): Int {
            var used = 0
            val r2 = radius * radius
            while (colZ <= bell.z + radius) {
                while (colX <= bell.x + radius) {
                    if (used >= budget) return used
                    if ((used and 15) == 15 && System.nanoTime() >= deadline) return used
                    val x = colX++
                    val dx = x - bell.x
                    val dz = colZ - bell.z
                    val d2 = dx * dx + dz * dz
                    if (d2 > r2) continue
                    used++
                    column(t, x, colZ, d2)
                }
                colX = bell.x - radius
                colZ++
            }
            colDone = true
            return used
        }

        /** What the top of the column ([x], [z]) is, if it is one of the things the pass looks for. */
        private fun column(t: Terrain, x: Int, z: Int, d2: Int) {
            val top = t.top(x, z)
            if (top == Terrain.UNLOADED) {
                unloadedParts = true
                return
            }
            val y = top - 1
            if (y < t.minY) return
            val s = t.state(x, y, z) ?: return
            if (s.isAir) return
            val c = Cls.of(s)
            when {
                c and Cls.FARMLAND != 0 -> if (fieldCells.size < MAX_FIELD_CELLS) fieldCells.add(BlockPos.asLong(x, top, z))
                c and Cls.WATER != 0 -> if (waters.size < MAX_WATER && rimmed(t, x, y, z)) waters.add(BlockPos.asLong(x, y, z))
                c and Cls.LAMP_BLOCK != 0 -> if (lamps.size < MAX_LAMPS) lamps.add(BlockPos(x, y, z))
                c and Cls.LANTERN != 0 -> lantern(t, x, y, z)
                c and Cls.SEAT != 0 -> if (benches.size < MAX_BENCHES) Scanner.seat(t, x, y, z, s)?.let { benches.add(it) }
                c and Cls.WOOL != 0 -> wool.add(BlockPos.asLong(x, y, z))
                Cls.feature(c) == Cls.F_POST -> posts.add(BlockPos(x, y, z))
                d2 <= WELL_LOOK * WELL_LOOK && c and Cls.FLOOR != 0 -> hollow(t, x, y, z)
            }
        }

        /**
         * Whether water at ([x], [y], [z]) has a block of stone beside it at its own level. A pool that is a well has one all round, so
         * every well has such a cell; a lake or the sea, whose thousands of cells would fill the list before a well further on is
         * reached, mostly has none.
         */
        private fun rimmed(t: Terrain, x: Int, y: Int, z: Int): Boolean {
            for (dir in Ways.ALL) {
                val s = t.state(x + dir.stepX, y, z + dir.stepZ) ?: continue
                if (Cls.of(s) and Cls.STONE != 0) return true
            }
            return false
        }

        /** A lantern standing on two fence posts or walls at least is a lamp post. */
        private fun lantern(t: Terrain, x: Int, y: Int, z: Int) {
            if (lamps.size >= MAX_LAMPS) return
            val a = t.state(x, y - 1, z) ?: return
            val b = t.state(x, y - 2, z) ?: return
            if (Cls.of(a) and Cls.POST != 0 && Cls.of(b) and Cls.POST != 0) lamps.add(BlockPos(x, y, z))
        }

        /** A roof over a hollow with water at the bottom (a well with a roof, which the open-water test misses): looked down into near the bell. */
        private fun hollow(t: Terrain, x: Int, roof: Int, z: Int) {
            if (waters.size >= MAX_WATER) return
            var y = roof - 1
            val under = t.state(x, y, z) ?: return
            if (Cls.of(under) and Cls.FREE == 0) return
            var steps = 0
            while (y > t.minY && steps < 9) {
                val s = t.state(x, y, z) ?: return
                val c = Cls.of(s)
                if (c and Cls.WATER != 0) {
                    waters.add(BlockPos.asLong(x, y, z))
                    return
                }
                if (c and Cls.FREE == 0) return
                y--
                steps++
            }
        }

        // ---- Slice C, finishing: what the pass found, sorted out ----

        private fun finishUp(t: Terrain, deadline: Long): Boolean {
            while (true) {
                if (System.nanoTime() >= deadline) return false
                when (finish) {
                    Finish.WELLS -> {
                        findWells(t)
                        finish = Finish.STALLS
                    }
                    Finish.STALLS -> {
                        findStalls(t)
                        finish = Finish.FIELDS
                    }
                    Finish.FIELDS -> {
                        findFields()
                        towerClusters = clusterPosts()
                        cursor = 0
                        finish = Finish.TOWERS
                    }
                    Finish.TOWERS -> if (!towers(t, deadline)) return false else return true
                }
            }
        }

        /** Water ringed by stone and by itself, a pool of no more than [POOL] cells, is a well; its place is the cell nearest its middle. */
        private fun findWells(t: Terrain) {
            val seen = HashSet<Long>()
            for (w in waters) {
                if (wells.size >= MAX_WELLS) break
                if (w in seen) continue
                val pool = pool(t, BlockPos.of(w), seen) ?: continue
                val cx = pool.sumOf { it.x }.toDouble() / pool.size
                val cz = pool.sumOf { it.z }.toDouble() / pool.size
                wells.add(pool.minBy { (it.x - cx) * (it.x - cx) + (it.z - cz) * (it.z - cz) })
            }
        }

        /** The cells of the pool [from] is in, if it is small and ringed by stone all round; null if not. Every cell looked at goes in [seen]. */
        private fun pool(t: Terrain, from: BlockPos, seen: HashSet<Long>): List<BlockPos>? {
            val cells = arrayListOf(from)
            seen.add(from.asLong())
            var k = 0
            var ringed = true
            while (k < cells.size) {
                val c = cells[k++]
                for (dir in Ways.ALL) {
                    val nx = c.x + dir.stepX
                    val nz = c.z + dir.stepZ
                    val s = t.state(nx, c.y, nz)
                    if (s == null) {
                        ringed = false
                        continue
                    }
                    val cls = Cls.of(s)
                    if (cls and Cls.WATER != 0) {
                        if (seen.add(BlockPos.asLong(nx, c.y, nz))) {
                            if (cells.size >= POOL) ringed = false else cells.add(BlockPos(nx, c.y, nz))
                        } else if (cells.none { it.x == nx && it.z == nz }) {
                            // Water an earlier look met and found to be in a pool too big: this is the same pool
                            ringed = false
                        }
                    } else if (cls and Cls.STONE == 0) {
                        ringed = false
                    }
                }
                if (!ringed) return null
            }
            return cells
        }

        /** A stall: a canopy of wool three blocks across on four posts, with a job block for a counter beside the middle. */
        private fun findStalls(t: Terrain) {
            val centres = ArrayList<BlockPos>()
            for (key in wool.sorted()) {
                val x = BlockPos.getX(key)
                val y = BlockPos.getY(key)
                val z = BlockPos.getZ(key)
                var all = true
                loop@ for (dx in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    if (BlockPos.asLong(x + dx, y, z + dz) !in wool) {
                        all = false
                        break@loop
                    }
                }
                if (!all || centres.any { abs(it.x - x) <= 2 && abs(it.z - z) <= 2 }) continue
                centres.add(BlockPos(x, y, z))
            }
            for (c in centres) {
                if (stalls.size >= MAX_STALLS) break
                val base = c.y - 2
                var posts = 0
                for (dx in intArrayOf(-1, 1)) for (dz in intArrayOf(-1, 1)) {
                    val s = t.state(c.x + dx, c.y - 1, c.z + dz) ?: continue
                    if (Cls.of(s) and Cls.POST != 0) posts++
                }
                if (posts < 4) continue
                var facing: Direction? = null
                var trade: M? = null
                for (side in Ways.ALL) {
                    val s = t.state(c.x + side.stepX, base, c.z + side.stepZ) ?: continue
                    val m = Cls.counter(s) ?: continue
                    facing = side
                    trade = m
                    break
                }
                if (facing == null || trade == null) continue
                val counter = BlockPos(c.x + facing.stepX, base, c.z + facing.stepZ)
                val back = BlockPos(c.x - facing.stepX, base, c.z - facing.stepZ)
                val goods = t.state(back.x, back.y, back.z)?.takeIf { Cls.of(it) and Cls.FLOOR != 0 }?.let { listOf(back) } ?: emptyList()
                stalls.add(
                    CityPlaces.Stall(
                        stalls.size, Spot(BlockPos(c.x, base, c.z), facing),
                        Spot(BlockPos(c.x + facing.stepX * 2, base, c.z + facing.stepZ * 2), facing.opposite), listOf(counter), goods, trade,
                    ),
                )
            }
        }

        /** Fields: farmland (with a crop or without) within [FIELD_GAP] of each other, a field of [FIELD_LEAST] cells at least. */
        private fun findFields() {
            if (fieldCells.isEmpty()) return
            // The farmland laid in a grid over its box, each cell by its height (none where there is no farmland), so that a field is
            // followed over arrays and not through a map: a farm of thousands of cells is a field of a fraction of a millisecond
            var x0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var z0 = Int.MAX_VALUE
            var z1 = Int.MIN_VALUE
            for (k in fieldCells) {
                val x = BlockPos.getX(k)
                val z = BlockPos.getZ(k)
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (z < z0) z0 = z
                if (z > z1) z1 = z
            }
            val w = x1 - x0 + 1
            val ys = IntArray(w * (z1 - z0 + 1))
            java.util.Arrays.fill(ys, NO_FIELD)
            for (k in fieldCells) ys[(BlockPos.getZ(k) - z0) * w + (BlockPos.getX(k) - x0)] = BlockPos.getY(k)
            val seen = BooleanArray(ys.size)
            val queue = IntArray(ys.size)
            for (start in fieldCells.sorted()) {
                val si = (BlockPos.getZ(start) - z0) * w + (BlockPos.getX(start) - x0)
                if (seen[si]) continue
                seen[si] = true
                var head = 0
                var tail = 0
                queue[tail++] = si
                val crops = ArrayList<BlockPos>()
                while (head < tail) {
                    val i = queue[head++]
                    val x = x0 + i % w
                    val z = z0 + i / w
                    val y = ys[i]
                    crops.add(BlockPos(x, y, z))
                    for (dx in -FIELD_GAP..FIELD_GAP) for (dz in -FIELD_GAP..FIELD_GAP) {
                        if (dx == 0 && dz == 0) continue
                        val nx = x + dx
                        val nz = z + dz
                        if (nx < x0 || nx > x1 || nz < z0 || nz > z1) continue
                        val ni = (nz - z0) * w + (nx - x0)
                        val ny = ys[ni]
                        if (ny == NO_FIELD || abs(ny - y) > FIELD_GAP || seen[ni]) continue
                        seen[ni] = true
                        queue[tail++] = ni
                    }
                }
                if (crops.size < FIELD_LEAST) continue
                var box = BoundingBox(crops[0])
                for (p in crops) box = box.encapsulate(p)
                fields.add(CityPlaces.Field(fields.size, crops, box, null))
            }
        }

        /** The Archer Posts in groups within 6 blocks of each other, each group a tower (or a keep). */
        private fun clusterPosts(): List<List<BlockPos>> {
            val out = ArrayList<MutableList<BlockPos>>()
            for (p in posts) {
                val into = out.firstOrNull { g -> g.any { abs(it.x - p.x) <= 6 && abs(it.z - p.z) <= 6 } }
                if (into != null) into.add(p) else out.add(arrayListOf(p))
            }
            return out
        }

        /** Scans round each group of posts for its ladder and what else stands in the tower; false while a scan is going on. */
        private fun towers(t: Terrain, deadline: Long): Boolean {
            while (true) {
                if (sub == Sub.SCANNING) {
                    if (!scanner.run(t, deadline)) return false
                    sub = Sub.IDLE
                    val g = towerClusters[cursor++]
                    val roof = g.maxOf { it.y }
                    val ladders = scanner.ladders()
                    val ground = ladders.minOfOrNull { it.bottom } ?: (g.minOf { it.y } - 9)
                    val b = Building(
                        "TOWER", BoundingBox(sx0, sy0, sz0, sx1, sy1, sz1), ground, listOf(ground, roof), emptyList(), ladders, arrayListOf(),
                        scanner.seats.toList(), scanner.tables.toList(), scanner.stations.toList(), scanner.stores.toList(), false,
                    )
                    buildings.add(b)
                    towers.addAll(g)
                    continue
                }
                if (System.nanoTime() >= deadline) return false
                if (cursor >= towerClusters.size) return true
                if (buildings.size >= MAX_BUILDINGS + MAX_TOWERS) return true
                val g = towerClusters[cursor]
                sx0 = g.minOf { it.x } - TOWER_MARGIN
                sz0 = g.minOf { it.z } - TOWER_MARGIN
                sx1 = g.maxOf { it.x } + TOWER_MARGIN
                sz1 = g.maxOf { it.z } + TOWER_MARGIN
                sy0 = maxOf(t.minY, g.minOf { it.y } - 12)
                sy1 = g.maxOf { it.y } + 1
                scanner.begin(sx0, sy0, sz0, sx1, sy1, sz1)
                sub = Sub.SCANNING
            }
        }

        // ---- Slice D: livestock ----

        /** Pens: two or more cows, sheep or pigs together in a fence make a barn; three or more hens, a coop. */
        private fun livestock(t: Terrain) {
            val all = ArrayList<Beast>()
            t.beasts(bell.x - radius, bell.z - radius, bell.x + radius, bell.z + radius, bell.y, all)
            val r2 = radius.toDouble() * radius
            val inside = all.filter { val dx = it.x - bell.x; val dz = it.z - bell.z; dx * dx + dz * dz <= r2 }
            cattle = inside.count { !it.hen }
            hens = inside.count { it.hen }
            barns = pens(t, inside.filter { !it.hen }, 2)
            coops = pens(t, inside.filter { it.hen }, 3)
            // Animals in a chunk that is not loaded are not seen: what was seen of them in the last count stands
            if (old != null && unloadedWithin(t, radius)) {
                barns = maxOf(barns, old.barns)
                coops = maxOf(coops, old.coops)
                cattle = maxOf(cattle, old.cattle)
                hens = maxOf(hens, old.hens)
            }
        }

        /** Whether any chunk of the square [r] blocks each way from the bell is not loaded. */
        private fun unloadedWithin(t: Terrain, r: Int): Boolean {
            val x0 = (bell.x - r) shr 4
            val x1 = (bell.x + r) shr 4
            val z0 = (bell.z - r) shr 4
            val z1 = (bell.z + r) shr 4
            for (cx in x0..x1) for (cz in z0..z1) if (!t.loaded(cx, cz)) return true
            return false
        }

        /** The number of groups of at least [least] animals standing within 6 blocks of each other (chained) and fenced in. */
        private fun pens(t: Terrain, animals: List<Beast>, least: Int): Int {
            val taken = BooleanArray(animals.size)
            var pens = 0
            for (i in animals.indices) {
                if (taken[i]) continue
                taken[i] = true
                val group = arrayListOf(i)
                var k = 0
                while (k < group.size) {
                    val a = animals[group[k++]]
                    for (j in animals.indices) {
                        if (taken[j]) continue
                        val b = animals[j]
                        if (abs(a.x - b.x) > 6.0 || abs(a.z - b.z) > 6.0) continue
                        taken[j] = true
                        group.add(j)
                    }
                }
                if (group.size >= least && fencedIn(t, group.map { animals[it] })) pens++
            }
            return pens
        }

        /**
         * Whether the group stands fenced in: the ground they walk on, followed out from where they stand over cells a body fits in
         * (a fence, a closed gate, a wall or water stops it), comes to an end within [PEN_CELLS] cells. A pasture with no fence runs on
         * past that; so does a meadow too big to be a pen. Ground that is not loaded is not known to end.
         */
        private fun fencedIn(t: Terrain, group: List<Beast>): Boolean {
            // Their feet are at the top of what they stand on (a path or farmland is 15/16 of a block): the cells they walk through are at the nearest whole height
            val y = Math.round(group.sumOf { it.y } / group.size).toInt()
            val seen = HashSet<Long>()
            val queue = ArrayList<Long>()
            for (b in group) {
                val x = Math.floor(b.x).toInt()
                val z = Math.floor(b.z).toInt()
                val s = t.state(x, y, z) ?: return false
                if (Cls.of(s) and Cls.PASS == 0) continue
                if (seen.add(BlockPos.asLong(x, y, z))) queue.add(BlockPos.asLong(x, y, z))
            }
            if (queue.isEmpty()) return false
            var k = 0
            while (k < queue.size) {
                if (queue.size > PEN_CELLS) return false
                val key = queue[k++]
                val x = BlockPos.getX(key)
                val z = BlockPos.getZ(key)
                for (dir in Ways.ALL) {
                    val nk = BlockPos.asLong(x + dir.stepX, y, z + dir.stepZ)
                    if (nk in seen) continue
                    val s = t.state(x + dir.stepX, y, z + dir.stepZ) ?: return false
                    if (Cls.of(s) and Cls.PASS == 0) continue
                    seen.add(nk)
                    queue.add(nk)
                }
            }
            return true
        }

        // ---- Slice E: the green ----

        /** The middle of the open ground within [GREEN] of the bell, at the height people stand. */
        private fun green(t: Terrain) {
            val xs = ArrayList<BlockPos>()
            for (dz in -GREEN..GREEN) for (dx in -GREEN..GREEN) {
                if (dx * dx + dz * dz > GREEN * GREEN) continue
                val x = bell.x + dx
                val z = bell.z + dz
                val top = t.top(x, z)
                if (top == Terrain.UNLOADED || abs(top - bell.y) > 3) continue
                val s = t.state(x, top - 1, z) ?: continue
                val c = Cls.of(s)
                if (c and Cls.FLOOR != 0 && c and Cls.WATER == 0) xs.add(BlockPos(x, top, z))
            }
            if (xs.size >= 6) {
                val mx = xs.sumOf { it.x }.toDouble() / xs.size
                val mz = xs.sumOf { it.z }.toDouble() / xs.size
                green = xs.minBy { (it.x - mx) * (it.x - mx) + (it.z - mz) * (it.z - mz) }
                return
            }
            val top = t.top(bell.x, bell.z)
            green = if (top == Terrain.UNLOADED) bell else BlockPos(bell.x, top, bell.z)
        }

        // ---- Done ----

        /** The count, with the parts of the last one in chunks that are not loaded now carried over. */
        private fun assemble(t: Terrain): Found {
            var homes: List<Building> = buildings
            var keptWells: List<BlockPos> = wells
            var keptLamps: List<BlockPos> = lamps
            var keptBenches: List<Spot> = benches
            var keptStalls: List<CityPlaces.Stall> = stalls
            var keptJobs: List<CityPlaces.Station> = stations
            var keptFields: List<CityPlaces.Field> = fields
            var keptTowers: List<BlockPos> = towers
            var keptRadius = radius
            var keptBoard = board
            var keptFacing = boardFacing
            var keptAdvances = advances
            var partial = unloadedParts
            if (old != null && unloadedParts) {
                // What stood where nothing could be read this time stands as it did; a thing that spans the edge of the loaded ground, which
                // this count found the loaded part of (or the whole of), is the one this count found
                fun dark(p: BlockPos): Boolean = !t.loaded(p.x shr 4, p.z shr 4)
                fun touchesDark(p: BlockPos, r: Int): Boolean {
                    for (cx in ((p.x - r) shr 4)..((p.x + r) shr 4)) for (cz in ((p.z - r) shr 4)..((p.z + r) shr 4)) if (!t.loaded(cx, cz)) return true
                    return false
                }
                fun near(a: BlockPos, b: BlockPos): Boolean = abs(a.x - b.x) <= 3 && abs(a.z - b.z) <= 3
                fun same(a: Building, b: Building): Boolean =
                    a.beds.any { it in b.beds } || a.stations.any { s -> b.stations.any { it.pos == s.pos } } || (near(a.door, b.door) && abs(a.door.y - b.door.y) <= 3)
                homes = buildings + old.buildings.filter { ob -> dark(ob.door) && buildings.none { same(it, ob) } }
                // A building this count found the loaded part of keeps the beds it had in the part that is not loaded
                for (ob in old.buildings) {
                    val nb = buildings.firstOrNull { same(it, ob) } ?: continue
                    for (bed in ob.beds) if (dark(bed) && bed !in nb.beds) nb.beds.add(bed)
                }
                keptWells = wells + old.wells.filter { w -> touchesDark(w, 2) && wells.none { near(it, w) } }
                keptLamps = lamps + old.lamps.filter { dark(it) }
                // A bench is looked at one block round it: one at the edge of the loaded ground could not be, and stands as it did
                keptBenches = benches + old.benches.filter { b -> touchesDark(b.pos, 1) && benches.none { it.pos == b.pos } }
                keptStalls = stalls + old.stalls.filter { s -> touchesDark(s.keeper.pos, 2) && stalls.none { near(it.keeper.pos, s.keeper.pos) } }.mapIndexed { i, s ->
                    CityPlaces.Stall(stalls.size + i, s.keeper, s.customer, s.counter, s.goods, s.trade)
                }
                keptJobs = stations + old.jobs.filter { dark(it.pos) }
                keptFields = fields + old.fields.filter { f -> f.crops.any { dark(it) } && fields.none { it.box.intersects(f.box) } }
                    .mapIndexed { i, f -> CityPlaces.Field(fields.size + i, f.crops, f.box, f.lodging) }
                keptTowers = towers + old.towers.filter { dark(it) }
                // The village does not shrink for ground that could not be read, and its board stands where its chunk is not loaded
                keptRadius = maxOf(radius, old.radius)
                val oldBoard = old.board
                if (board == null && oldBoard != null && dark(oldBoard)) {
                    keptBoard = oldBoard
                    keptFacing = old.boardFacing
                    keptAdvances = old.advances
                }
                partial = true
            }
            val stats = Stats(ticks, nanos, worst, slices.copyOf(), reads, counted, misses)
            Perf.add(stats)
            return Found(
                bell, keptRadius, keptBoard, keptFacing, keptAdvances, homes, keptTowers.toList(), keptJobs, keptWells, keptLamps, keptBenches, keptStalls,
                keptFields.toList(), green, barns, coops, cattle, hens, now, partial, stats,
            )
        }
    }

    /** The kind of a building by what stands in it: a hall of the guild, a house (it has beds), a library, a temple, a smithy, a bakery, a barn or a workshop. */
    private fun kindOf(beds: Int, s: Scanner, job: M?): String {
        fun has(vararg ms: M): Boolean = ms.any { s.count(it) > 0 || job == it }
        if (has(M.LECTERN) && s.andesite >= 3) return "GUILD_HALL"
        if (beds > 0) return "HOUSE"
        if (has(M.LECTERN) || s.count(M.BOOKSHELF) >= 4) return "LIBRARY"
        if (has(M.BREWING_STAND)) return "TEMPLE"
        if (has(M.ANVIL, M.GRINDSTONE, M.SMITHING_TABLE, M.BLAST_FURNACE)) return "SMITHY"
        if (has(M.SMOKER, M.FURNACE)) return "BAKERY"
        if (has(M.COMPOSTER) || s.hay >= 3) return "BARN"
        if (has(M.LOOM, M.FLETCHING_TABLE, M.CARTOGRAPHY_TABLE, M.STONECUTTER, M.CAULDRON, M.BARREL) || s.stations.isNotEmpty()) return "WORKSHOP"
        return "HOUSE"
    }

    /** How far from the bell a board may stand to be the village's: the same 48 the board's own search uses. */
    private const val BOARD_REACH = 48

    /** The most cells of ground a pen may enclose (a pen of 12 by 16 is about that). */
    private const val PEN_CELLS = 200

    /** The most cells a pool of water may have to be a well. */
    private const val POOL = 16
    private const val MAX_STALLS = 12
    private const val MAX_TOWERS = 8

    /** How far past its Archer Posts a tower is looked at, for the ladder up its side (a post may stand at one corner only). */
    private const val TOWER_MARGIN = 5

    /** The height of a cell of the grid of farmland that has none. */
    private const val NO_FIELD = Int.MIN_VALUE
}
