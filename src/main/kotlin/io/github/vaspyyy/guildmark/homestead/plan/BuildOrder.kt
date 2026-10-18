package io.github.vaspyyy.guildmark.homestead.plan

import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Spawn
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/** What one step of a build does (see [Step]). */
enum class StepKind {
    /** An old corner stake pulled up: its post and banner. */
    PULL_STAKE,
    /** A corner stake knocked in: a post with the owner's banner on it. */
    STAKE,
    /** A column of ground levelled to stand at y, its surface [Step.b] and [Step.fill] under it. */
    GROUND,
    /** A column of the land's own ground cut or built up to stand at y, the edge of a levelled lot. */
    BANK,
    /** A block of the plan set. */
    BLOCK,
    /** A pole of scaffolding put up. */
    SCAFFOLD,
    /** The green bough set on the ridge. */
    TOPPING,
    /** A pole of scaffolding taken down. */
    UNSCAFFOLD,
    /** The bough taken down again. */
    UNTOP,
    /** A shipping crate set in the yard. */
    SHIPPING,
    /** A beast let out into its pen. */
    SPAWN,
}

/** One step of a tier's build, in the plot's frame, done by a builder (or by the time a builder would have taken). */
class Step(
    val kind: StepKind,
    val stage: Stage,
    val x: Int,
    val y: Int,
    val z: Int,
    val b: B = B.AIR,
    val fill: B? = null,
    val stuff: Stuff = Stuff.FREE,
    val spawn: Spawn? = null,
    val facing: Dir = Dir.N,
    /** The piece of the plan it belongs to (a house's steps go up together), or -1. */
    val piece: Int = -1,
)

/** A tier's whole build, step by step, and how many steps of each paid material it has. */
class TierPlan(val tier: Int, val steps: List<Step>, val pieces: List<PieceInfo>) {
    /** Steps of each paid material ([Stuff.PAID] order). */
    val needs: IntArray = IntArray(Stuff.PAID.size).also { n -> for (s in steps) if (s.stuff != Stuff.FREE) n[s.stuff.ordinal]++ }

    /** Where each stage's steps start (an empty stage starts where the next does). */
    val stageStarts: IntArray = IntArray(Stage.entries.size + 1).also { starts ->
        var i = 0
        for (stage in Stage.entries) {
            starts[stage.ordinal] = i
            while (i < steps.size && steps[i].stage == stage) i++
        }
        starts[Stage.entries.size] = steps.size
    }

    /** The stage step [i] is in. */
    fun stageOf(i: Int): Stage = if (i >= steps.size) Stage.GROUNDS else steps[i].stage

    /** How many steps of materials of each paid kind are left from step [from] on. */
    fun left(from: Int): IntArray {
        val n = IntArray(Stuff.PAID.size)
        for (i in from until steps.size) {
            val s = steps[i].stuff
            if (s != Stuff.FREE) n[s.ordinal]++
        }
        return n
    }
}

/**
 * Turns a tier of the layout into the order its crew builds it in: the stakes, the groundwork lot by lot, then each
 * stage over every building in turn (a house's walls course by course round it, its roof from the eaves up), the
 * scaffolding up before the roofs and down after the bough, the grounds, the shipping crates, and last the beasts.
 */
object BuildOrder {
    fun plan(layout: HomesteadLayout, tier: Int): TierPlan {
        val rec = Recording(PlotShape.RESERVED.grow(4))
        layout.draw(tier, rec)
        val out = rec.finish()
        val pieces = out.pieces
        val steps = ArrayList<Step>()

        // The stakes: the old ones up, the new ones in
        val stakes = out.blocks.entries.filter { pieces[it.value.piece].kind == PieceKind.STAKE }
        for ((key, placed) in stakes.sortedBy { -Packed.y(it.key) }) if (placed.b.m == M.AIR) {
            steps.add(Step(StepKind.PULL_STAKE, Stage.STAKES, Packed.x(key), Packed.y(key), Packed.z(key), piece = placed.piece))
        }
        for ((key, placed) in stakes.sortedBy { Packed.y(it.key) }) if (placed.b.m == M.FENCE) {
            steps.add(Step(StepKind.STAKE, Stage.STAKES, Packed.x(key), Packed.y(key), Packed.z(key), placed.b, piece = placed.piece))
        }
        val banners = stakes.filter { it.value.b.m == M.BANNER }.associate { Packed.column(Packed.x(it.key), Packed.z(it.key)) to it.value.b }

        // The groundwork, lot by lot in the order they were laid out: each lot's levelled ground swept row by row,
        // then the banks round it
        val grounds = out.grounds.entries.groupBy { it.value.piece }
        val banks = out.clearings.entries.groupBy { it.value.piece }
        for (piece in (grounds.keys + banks.keys).toSortedSet()) {
            for ((col, op) in (grounds[piece] ?: listOf()).sortedWith(compareBy({ Packed.cz(it.key) }, { snake(Packed.cx(it.key), Packed.cz(it.key)) }))) {
                val stuff = if (op.surface.m == M.FOUNDATION) Stuff.STONE else Stuff.FREE
                steps.add(Step(StepKind.GROUND, Stage.GROUNDWORK, Packed.cx(col), op.y, Packed.cz(col), op.surface, op.fill, stuff, piece = piece))
            }
            for ((col, op) in (banks[piece] ?: listOf()).sortedWith(compareBy({ Packed.cz(it.key) }, { Packed.cx(it.key) }))) {
                steps.add(Step(StepKind.BANK, Stage.GROUNDWORK, Packed.cx(col), op.y, Packed.cz(col), piece = piece))
            }
        }

        // Every other block, by stage, by piece, from the bottom up and round each piece
        val blocks = out.blocks.entries.filter { pieces[it.value.piece].kind != PieceKind.STAKE }
            .map { (key, placed) ->
                val piece = pieces[placed.piece]
                val y = Packed.y(key)
                Triple(key, placed, Staging.stage(placed.b, y, piece))
            }
        val byStage = blocks.groupBy { it.third }
        val houses = pieces.withIndex().filter { it.value.kind == PieceKind.HOUSE && it.value.roofBase != Int.MAX_VALUE }

        for (stage in Stage.entries) {
            if (stage == Stage.STAKES) continue
            when (stage) {
                Stage.SCAFFOLD -> scaffold(steps, houses, out, layout)
                Stage.TOPPING -> topping(steps, houses, out)
                Stage.UNSCAFFOLD -> {
                    val up = steps.filter { it.kind == StepKind.SCAFFOLD }
                    // Down from the top of each pole, the highest first
                    for (s in up.sortedWith(compareBy({ -it.y }, { it.x }, { it.z }))) {
                        steps.add(Step(StepKind.UNSCAFFOLD, Stage.UNSCAFFOLD, s.x, s.y, s.z, piece = s.piece))
                    }
                    steps.filter { it.kind == StepKind.TOPPING }.toList().forEach {
                        steps.add(Step(StepKind.UNTOP, Stage.UNSCAFFOLD, it.x, it.y, it.z, piece = it.piece))
                    }
                }
                else -> {}
            }
            val here = byStage[stage] ?: continue
            val sorted = here.sortedWith(compareBy<Triple<Long, Placed, Stage>>(
                { it.second.piece },
                { if (stage == Stage.GROUNDS || stage == Stage.GROUNDWORK) 0 else Packed.y(it.first) },
                { if (stage == Stage.GROUNDS || stage == Stage.GROUNDWORK) Packed.z(it.first) else 0 },
                { round(pieces[it.second.piece], Packed.x(it.first), Packed.z(it.first), stage) },
                { Packed.y(it.first) },
            ))
            for ((key, placed, st) in sorted) {
                val piece = pieces[placed.piece]
                steps.add(Step(StepKind.BLOCK, st, Packed.x(key), Packed.y(key), Packed.z(key), placed.b, stuff = if (placed.b.m == M.AIR) Stuff.FREE else Staging.stuff(placed.b, piece), piece = placed.piece))
            }
        }

        // The shipping crates this tier adds, at the edge of the grounds, and the beasts let out last of all
        val before = if (tier > 0) layout.places(tier - 1).shipping.toSet() else setOf()
        for ((x, z) in layout.places(tier).shipping) {
            if ((x to z) in before) continue
            val y = out.grounds[Packed.column(x, z)]?.y ?: out.clearings[Packed.column(x, z)]?.y ?: layout.groundHeight(x, z)
            steps.add(Step(StepKind.SHIPPING, Stage.GROUNDS, x, y, z))
        }
        for (s in out.spawns) steps.add(Step(StepKind.SPAWN, Stage.GROUNDS, s.x, s.y, s.z, spawn = s.what, facing = s.facing))

        // The stake's banner goes up with its post: one step, the banner carried on the post's step
        val staked = steps.map { s ->
            if (s.kind != StepKind.STAKE) s
            else Step(StepKind.STAKE, s.stage, s.x, s.y, s.z, banners[Packed.column(s.x, s.z)] ?: s.b, s.b, s.stuff, piece = s.piece)
        }
        return TierPlan(tier, staked, pieces)
    }

    /** Alternate rows run back and forth, so a row's end is beside the next row's start. */
    private fun snake(x: Int, z: Int): Int = if (Math.floorMod(z, 2) == 0) x else -x

    /**
     * Where a block comes round its piece: walls and roofs go round the building (by the angle from its middle),
     * the rest sweeps across it.
     */
    private fun round(piece: PieceInfo, x: Int, z: Int, stage: Stage): Int {
        if (stage == Stage.GROUNDS || stage == Stage.GROUNDWORK) return snake(x, z)
        val cx = (piece.lot.x0 + piece.lot.x1) / 2.0
        val cz = (piece.lot.z0 + piece.lot.z1) / 2.0
        return (atan2(z - cz, x - cx) * 1000).toInt()
    }

    /**
     * Scaffolding round each roofed building: poles two out from its walls, at its corners and every few blocks along
     * each side, from the ground to just under the eaves, wherever nothing of the plan stands in the way.
     */
    private fun scaffold(steps: MutableList<Step>, houses: List<IndexedValue<PieceInfo>>, out: Recorded, layout: HomesteadLayout) {
        val taken = HashSet<Long>()
        val columns = HashMap<Long, IntArray>()   // column -> min y, max y of plan blocks there
        for ((key, placed) in out.blocks) {
            if (placed.b.m == M.AIR) continue
            val col = Packed.column(Packed.x(key), Packed.z(key))
            val y = Packed.y(key)
            val span = columns.getOrPut(col) { intArrayOf(y, y) }
            span[0] = minOf(span[0], y)
            span[1] = max(span[1], y)
        }
        for ((index, house) in houses) {
            val walls = HashSet<Long>()
            for ((key, placed) in out.blocks) {
                if (placed.piece != index || placed.b.m == M.AIR) continue
                val y = Packed.y(key)
                if (y >= house.y && y < house.roofBase) walls.add(Packed.column(Packed.x(key), Packed.z(key)))
            }
            if (walls.isEmpty()) continue
            val top = house.roofBase - 1
            if (top - house.y < 3) continue
            var x0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var z0 = Int.MAX_VALUE; var z1 = Int.MIN_VALUE
            for (c in walls) {
                x0 = minOf(x0, Packed.cx(c)); x1 = max(x1, Packed.cx(c)); z0 = minOf(z0, Packed.cz(c)); z1 = max(z1, Packed.cz(c))
            }
            // The ring two out from the building's bounds: corners, then every fourth column along each side
            val ring = ArrayList<Pair<Int, Int>>()
            for (x in x0 - 2..x1 + 2) for (z in z0 - 2..z1 + 2) {
                val onRing = (x == x0 - 2 || x == x1 + 2 || z == z0 - 2 || z == z1 + 2)
                if (!onRing) continue
                val corner = (x == x0 - 2 || x == x1 + 2) && (z == z0 - 2 || z == z1 + 2)
                val along = if (x == x0 - 2 || x == x1 + 2) z - z0 else x - x0
                if (!corner && Math.floorMod(along, 4) != 1) continue
                ring.add(x to z)
            }
            for ((x, z) in ring) {
                val col = Packed.column(x, z)
                if (col in taken) continue
                if (!PlotShape.RESERVED.contains(x, z)) continue
                val ground = out.grounds[col]?.y ?: out.clearings[col]?.y ?: layout.groundHeight(x, z)
                if (abs(ground - house.y) > 2) continue
                val span = columns[col]
                // Anything of the plan in the way (but what lies on the ground: crops, paths, flowers) rules it out
                if (span != null && span[1] >= ground + 1) continue
                taken.add(col)
                for (y in ground..top) steps.add(Step(StepKind.SCAFFOLD, Stage.SCAFFOLD, x, y, z, piece = index))
            }
        }
    }

    /** A green bough on the ridge of the biggest building: the highest roof block it has, and above it. */
    private fun topping(steps: MutableList<Step>, houses: List<IndexedValue<PieceInfo>>, out: Recorded) {
        if (houses.isEmpty()) return
        val count = HashMap<Int, Int>()
        for (p in out.blocks.values) count.merge(p.piece, 1, Int::plus)
        val main = houses.maxBy { count[it.index] ?: 0 }.index
        var best: Long? = null
        for ((key, placed) in out.blocks) {
            if (placed.piece != main) continue
            val m = placed.b.m
            if (m != M.ROOF && m != M.ROOF_STAIRS && m != M.ROOF_SLAB) continue
            if (best == null || Packed.y(key) > Packed.y(best)) best = key
        }
        val at = best ?: return
        val above = Packed.of(Packed.x(at), Packed.y(at) + 1, Packed.z(at))
        if (out.blocks.containsKey(above)) return
        steps.add(Step(StepKind.TOPPING, Stage.TOPPING, Packed.x(at), Packed.y(at) + 1, Packed.z(at), B(M.LEAVES), piece = main))
    }
}
