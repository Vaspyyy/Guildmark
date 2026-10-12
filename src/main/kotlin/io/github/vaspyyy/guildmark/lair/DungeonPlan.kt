package io.github.vaspyyy.guildmark.lair

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Random

/** What a dungeon room is for. */
enum class RoomKind { ENTRANCE, HALL, TREASURE, BOSS }

/**
 * A room of a dungeon plan, in plan space: [u] runs from the entrance toward the boss and [v] across it,
 * both in blocks from the entrance room's centre. The room's open interior is [u] ± [halfU] by [v] ± [halfV],
 * [height] blocks high from [floor], which is in blocks relative to the entrance room's floor.
 */
class PlanRoom(val kind: RoomKind, val u: Int, val v: Int, val halfU: Int, val halfV: Int, val height: Int, var floor: Int)

/** A corridor joining two rooms whose centres line up, running along u (or else along v). */
class PlanLink(val a: Int, val b: Int, val alongU: Boolean)

/** The rooms and corridors of one dungeon. Room 0 is the entrance and the boss room is always last. */
class DungeonPlan(val rooms: List<PlanRoom>, val links: List<PlanLink>) {
    val boss: PlanRoom get() = rooms.last()

    /** Open blocks between the walls of the two rooms a corridor joins. */
    fun run(link: PlanLink): Int {
        val a = rooms[link.a]
        val b = rooms[link.b]
        return if (link.alongU) Math.abs(a.u - b.u) - (a.halfU + 1) - (b.halfU + 1) - 1
        else Math.abs(a.v - b.v) - (a.halfV + 1) - (b.halfV + 1) - 1
    }

    /** The furthest a corridor can climb or drop: a step down every block, with a landing at each end. */
    fun maxDrop(link: PlanLink): Int = (run(link) - 2).coerceAtLeast(0)

    companion object {
        /** Each room as its kind, centre, half sizes, height and floor; each corridor as its two rooms and its run. */
        val CODEC: Codec<DungeonPlan> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.listOf(7, 7).listOf().fieldOf("rooms").forGetter { plan ->
                    plan.rooms.map { listOf(it.kind.ordinal, it.u, it.v, it.halfU, it.halfV, it.height, it.floor) }
                },
                Codec.INT.listOf(3, 3).listOf().fieldOf("links").forGetter { plan ->
                    plan.links.map { listOf(it.a, it.b, if (it.alongU) 1 else 0) }
                },
            ).apply(i) { rooms, links ->
                DungeonPlan(
                    rooms.map { PlanRoom(RoomKind.entries.getOrElse(it[0]) { RoomKind.HALL }, it[1], it[2], it[3], it[4], it[5], it[6]) },
                    links.filter { it[0] in rooms.indices && it[1] in rooms.indices }.map { PlanLink(it[0], it[1], it[2] != 0) },
                )
            }
        }
    }
}

/**
 * Lays dungeons out on a grid of cells, one room to a cell: a winding path of halls from the entrance,
 * a few side rooms off it for treasure, and the boss hall at the end, two cells deep and three across.
 * Pure arithmetic, so the same seed always gives the same dungeon.
 */
object DungeonPlanner {
    private data class Cell(val u: Int, val v: Int) {
        operator fun plus(o: Cell) = Cell(u + o.u, v + o.v)
        operator fun times(k: Int) = Cell(u * k, v * k)
    }

    private val FORWARD = Cell(1, 0)
    private val LEFT = Cell(0, -1)
    private val RIGHT = Cell(0, 1)

    /** Cell size in blocks: room for a hall and a corridor long enough to take a flight of stairs. */
    fun cellSize(major: Boolean): Int = if (major) 22 else 18

    fun plan(seed: Long, rank: Int, major: Boolean): DungeonPlan {
        val random = Random(seed)
        repeat(60) { attempt(random, rank, major)?.let { return it } }
        return straight(rank, major)
    }

    private fun attempt(random: Random, rank: Int, major: Boolean): DungeonPlan? {
        val size = cellSize(major)
        val bound = if (major) 3 else 2
        val halls = if (major) (5 + rank / 2).coerceIn(5, 8) else (2 + (rank + 1) / 2).coerceIn(2, 5)
        val treasure = if (major) 2 + random.nextInt(2) else if (rank >= 2) 1 + random.nextInt(2) else random.nextInt(2)
        val maxU = halls + 2

        val cells = HashMap<Cell, Int>()
        val rooms = ArrayList<PlanRoom>()
        val links = ArrayList<PlanLink>()
        fun add(kind: RoomKind, cell: Cell, halfU: Int, halfV: Int, height: Int): Int {
            rooms.add(PlanRoom(kind, cell.u * size, cell.v * size, halfU, halfV, height, 0))
            cells[cell] = rooms.size - 1
            return rooms.size - 1
        }

        add(RoomKind.ENTRANCE, Cell(0, 0), 5, 5, if (major) 7 else 6)
        val path = arrayListOf(Cell(0, 0))
        for (i in 1..halls) {
            val here = path.last()
            val options = listOf(FORWARD, FORWARD, LEFT, RIGHT).map { here + it }
                .filter { it.u in 0..maxU && Math.abs(it.v) <= bound && it !in cells }
            if (options.isEmpty()) return null
            val next = options[random.nextInt(options.size)]
            val half = if (major) 4 else 3
            val spread = if (major) 3 else 3
            val index = add(RoomKind.HALL, next, half + random.nextInt(spread), half + random.nextInt(spread),
                if (major) 6 + random.nextInt(4) else 5 + random.nextInt(3))
            links.add(PlanLink(cells.getValue(here), index, next.u != here.u))
            path.add(next)
        }

        // The boss hall: two cells on from the last hall, three cells across, centred on its line
        val last = path.last()
        val directions = if (random.nextBoolean()) listOf(FORWARD, LEFT, RIGHT) else listOf(FORWARD, RIGHT, LEFT)
        var bossCells: List<Cell>? = null
        var facing = FORWARD
        for (f in directions) {
            val across = if (f.u != 0) RIGHT else FORWARD
            val block = (1..2).flatMap { k -> (-1..1).map { p -> last + f * k + across * p } }
            if (block.all { it !in cells && it.u >= -1 && it.u <= maxU + 2 && Math.abs(it.v) <= bound + 1 }) {
                bossCells = block
                facing = f
                break
            }
        }
        if (bossCells == null) return null

        // Side rooms for treasure, off any hall but the last (or the entrance if there's no other)
        val taken = HashSet(bossCells)
        repeat(treasure) {
            val parents = (if (halls > 1) path.subList(1, path.size - 1) else path.subList(0, 1)).shuffled(random)
            for (parent in parents) {
                val spot = listOf(FORWARD, LEFT, RIGHT, Cell(-1, 0)).shuffled(random).map { parent + it }
                    .firstOrNull { it !in cells && it !in taken && it.u >= -1 && Math.abs(it.v) <= bound + 1 } ?: continue
                val half = if (major) 3 + random.nextInt(2) else 3
                val index = add(RoomKind.TREASURE, spot, half, half, 5)
                links.add(PlanLink(cells.getValue(parent), index, spot.u != parent.u))
                break
            }
        }

        val alongU = facing.u != 0
        val bossU = last.u * size + facing.u * size * 3 / 2
        val bossV = last.v * size + facing.v * size * 3 / 2
        val deep = if (major) 14 else 11
        val wide = if (major) 13 else 10
        rooms.add(PlanRoom(RoomKind.BOSS, bossU, bossV, if (alongU) deep else wide, if (alongU) wide else deep, if (major) 13 else 9, 0))
        links.add(PlanLink(cells.getValue(last), rooms.size - 1, alongU))

        val plan = DungeonPlan(rooms, links)
        settle(plan, random)
        return plan
    }

    /** Each room a little deeper than the one before it, or level with it; side rooms stay level. */
    private fun settle(plan: DungeonPlan, random: Random) {
        for (link in plan.links) {
            val from = plan.rooms[link.a]
            val to = plan.rooms[link.b]
            val drop = when {
                to.kind == RoomKind.TREASURE -> 0
                to.kind == RoomKind.BOSS -> if (random.nextBoolean()) 2 else 0
                else -> intArrayOf(0, 0, 0, 2, 3, 4)[random.nextInt(6)]
            }
            to.floor = from.floor - drop.coerceAtMost(plan.maxDrop(link))
        }
    }

    /** A plain line if the grid won't take a winding one: entrance, two halls, the boss hall ahead. */
    private fun straight(rank: Int, major: Boolean): DungeonPlan {
        val size = cellSize(major)
        val rooms = arrayListOf(
            PlanRoom(RoomKind.ENTRANCE, 0, 0, 5, 5, 6, 0),
            PlanRoom(RoomKind.HALL, size, 0, 4, 4, 6, 0),
            PlanRoom(RoomKind.HALL, size * 2, 0, 4, 4, 6, 0),
            PlanRoom(RoomKind.BOSS, size * 2 + size * 3 / 2, 0, if (major) 14 else 11, if (major) 13 else 10, if (major) 13 else 9, 0),
        )
        val links = listOf(PlanLink(0, 1, true), PlanLink(1, 2, true), PlanLink(2, 3, true))
        return DungeonPlan(rooms, links)
    }
}
