package io.github.vaspyyy.guildmark.homestead.plan

import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Canvas
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.city.plan.Loot
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Spawn

/**
 * A canvas the homestead's layout draws on: a city plan canvas that is also told what each thing it draws is
 * ([piece]), so the blocks can be sorted into stages afterwards.
 */
interface PlanCanvas : Canvas {
    /** Everything drawn from now on, until the next call, belongs to a [kind] of thing on [lot] standing at [y]. */
    fun piece(kind: PieceKind, lot: Rect, y: Int)

    /**
     * Every block drawn from now on, until the next call, goes up in [stage], whatever [Staging] would make of it (null:
     * as Staging has it). A town works' drawing says this of what its stages show (economy/works/build/WorksPlans); a
     * homestead's never does.
     */
    fun stage(stage: Stage?) {}
}

/** A place in the plot's own frame packed into a number (x and z within +-2^20, y within +-2^19). */
object Packed {
    fun of(x: Int, y: Int, z: Int): Long = ((x.toLong() + OFF) shl 42) or ((z.toLong() + OFF) shl 21) or (y.toLong() + YOFF)
    fun x(p: Long): Int = ((p ushr 42) and MASK).toInt() - OFF
    fun z(p: Long): Int = ((p ushr 21) and MASK).toInt() - OFF
    fun y(p: Long): Int = (p and MASK).toInt() - YOFF
    fun column(x: Int, z: Int): Long = ((x.toLong() + OFF) shl 21) or (z.toLong() + OFF)
    fun cx(c: Long): Int = ((c ushr 21) and MASK).toInt() - OFF
    fun cz(c: Long): Int = (c and MASK).toInt() - OFF

    private const val OFF = 1 shl 20
    private const val YOFF = 1 shl 19
    private const val MASK = (1L shl 21) - 1
}

/** A block the plan sets, the piece that set it last, and the stage the drawing put it in (null: as Staging has it). */
class Placed(val b: B, val piece: Int, val stage: Stage? = null)

/** A column of ground made to stand at [y], with [surface] under it and [fill] below that ([Canvas.ground]). */
class GroundOp(val y: Int, val surface: B, val fill: B, val piece: Int)

/** A column of the land's own ground made to stand at [y] ([Canvas.clearing]). */
class ClearOp(val y: Int, val piece: Int)

/** A beast the plan puts out (the pen's animals, a horse at the stable). */
class SpawnOp(val what: Spawn, val x: Int, val y: Int, val z: Int, val facing: Dir)

/** Everything one tier's layout drew, in the plot's own frame: the result of a [Recording]. */
class Recorded(
    val blocks: Map<Long, Placed>,
    val grounds: Map<Long, GroundOp>,
    val clearings: Map<Long, ClearOp>,
    val spawns: List<SpawnOp>,
    val pieces: List<PieceInfo>,
)

/**
 * Records what a layout draws instead of drawing it: the last block set at each place, the last ground or clearing in
 * each column, the beasts, and which piece drew each. Chests come in as plain chests (a homestead's are empty), and the
 * townsfolk a city's buildings would house are left out: a homestead's people are its owners.
 */
class Recording(override val clip: Rect) : PlanCanvas {
    private val blocks = LinkedHashMap<Long, Placed>()
    private val grounds = LinkedHashMap<Long, GroundOp>()
    private val clearings = LinkedHashMap<Long, ClearOp>()
    private val spawns = ArrayList<SpawnOp>()
    private val pieces = ArrayList<PieceInfo>()
    private var stage: Stage? = null

    private fun current(): Int {
        if (pieces.isEmpty()) pieces.add(PieceInfo(PieceKind.YARD, clip, 0))
        return pieces.size - 1
    }

    override fun piece(kind: PieceKind, lot: Rect, y: Int) {
        pieces.add(PieceInfo(kind, lot, y))
    }

    override fun stage(stage: Stage?) {
        this.stage = stage
    }

    override fun set(x: Int, y: Int, z: Int, b: B) {
        if (!clip.contains(x, z)) return
        blocks[Packed.of(x, y, z)] = Placed(b, current(), stage)
    }

    override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {
        if (!clip.contains(x, z)) return
        val key = Packed.column(x, z)
        grounds[key] = GroundOp(y, surface, fill, current())
        clearings.remove(key)
    }

    override fun clearing(x: Int, z: Int, y: Int) {
        if (!clip.contains(x, z)) return
        val key = Packed.column(x, z)
        // A column laid as ground keeps it: the banks round one lot never undo another's levelling
        if (key !in grounds) clearings[key] = ClearOp(y, current())
    }

    override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) = set(x, y, z, B(M.CHEST, facing))

    // A homestead has no quest board, and no one comes to live in it with the walls
    override fun board(x: Int, y: Int, z: Int, facing: Dir) {}

    override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {
        if (!clip.contains(x, z) || what == Spawn.VILLAGER || what == Spawn.RECEPTIONIST || what == Spawn.GOLEM) return
        spawns.add(SpawnOp(what, x, y, z, facing))
    }

    override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) {}

    fun finish(): Recorded {
        // Each building's roof starts at its lowest roof block; what stands at or above that is the roof's
        for ((key, placed) in blocks) {
            val piece = pieces[placed.piece]
            if (piece.kind != PieceKind.HOUSE) continue
            val m = placed.b.m
            if (m == M.ROOF || m == M.ROOF_STAIRS || m == M.ROOF_SLAB) piece.roofBase = minOf(piece.roofBase, Packed.y(key))
        }
        return Recorded(blocks, grounds, clearings, spawns, pieces)
    }
}
