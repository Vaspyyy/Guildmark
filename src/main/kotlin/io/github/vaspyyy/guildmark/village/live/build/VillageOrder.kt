package io.github.vaspyyy.guildmark.village.live.build

import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.registry.ModBlocks
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.WallBannerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.Heightmap
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * What the crew lays, and in what order (living-world/villages/design.md 12.3): a recording turned into a plan. Each block the
 * builders recorded that is not what already stands there is sorted into a stage by what it is and where it stands in its project
 * (the ground and foundation, the walls, the roof, the openings, the fittings, and the grounds about), the stages go in that order
 * and, within one, the blocks in the order a crew would lay them: a palisade or a wall column by column round the ring, a lamp post
 * or a stall all at once and the next, a building course by course round its walls. Nothing is laid before what it hangs from or
 * stands on (a block waits for a neighbour to be there: a ladder for the wall behind it, a hanging lantern for what it hangs from),
 * so nothing floats at any step; a block whose support comes in a later stage waits for that stage.
 *
 * Four corner stakes go in first for a hall or a tower, a green bough is set on the highest point of the three big projects (the hall,
 * the walls, the keep) when their roof is on, and both come away again as the last steps, so that what stands at the end is what the
 * builders drew.
 */
object VillageOrder {
    /**
     * One step of a plan. [state] is the block it lays; for a stake, what stood where the post goes, and for pulling it, what stood
     * where its flag goes (both put back as they were); null for the bough, which is built in.
     */
    class Planned(val pos: Long, val kind: Int, val state: BlockState?, val swap: Boolean, val stage: Stage)

    /** A recorded block on its way into the plan. */
    private class Item(val pos: Long, val state: BlockState, val swap: Boolean) {
        var stage: Stage = Stage.GROUNDS
        var cluster = 0
        var rank = 0
        val x: Int get() = BlockPos.getX(pos)
        val y: Int get() = BlockPos.getY(pos)
        val z: Int get() = BlockPos.getZ(pos)
    }

    /**
     * What a plan comes to: the steps in the order they are laid, what holds each up ([deps]: the step it stands on, hangs from or leans
     * against, -1 where the world holds it or it needs nothing), and what is laid out of sight at once because it lies deep underground.
     */
    class Result(val steps: List<Planned>, val buried: List<Pair<Long, BlockState>>, val deps: IntArray = IntArray(steps.size) { -1 })

    /** How far out from a corner of the work its stake may be put, where the corner itself is taken. */
    private const val STAKE_OUT = 3

    /** Deeper than this under the ground is out of the crew's reach (and sight): a hall's path laid at the wrong height is one. */
    private const val DEEP = 24

    /** The stages the blocks of a plan fall in, in the order they are laid (the bough has the stage between the roof and the openings). */
    private val RUN = listOf(Stage.GROUNDWORK, Stage.FOOTINGS, Stage.WALLS, Stage.ROOF, Stage.TOPPING, Stage.OPENINGS, Stage.FITTING, Stage.GROUNDS)

    /** Plans [rec]: [project] says how its blocks are sorted and ordered; [bell] and [crate] where the work is about and where it starts. */
    fun plan(level: ServerLevel, project: Advance, rec: BuildSink.Recording, bell: BlockPos, crate: BlockPos?): Result {
        val items = ArrayList<Item>()
        val buried = ArrayList<Pair<Long, BlockState>>()
        for (k in 0 until rec.size) {
            val pos = rec.order.getLong(k)
            val state = rec.states[k]
            val p = BlockPos.of(pos)
            if (!level.isLoaded(p)) {
                // Never looked at in an unloaded place: the builders read the world there, so it is loaded unless it was left since
                items.add(Item(pos, state, true))
                continue
            }
            val was = level.getBlockState(p)
            if (was == state) continue
            if (p.y < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x, p.z) - DEEP) {
                buried.add(pos to state)
                continue
            }
            items.add(Item(pos, state, !was.isAir && !soft(was)))
        }
        if (items.isEmpty()) return Result(listOf(), buried)

        val shape = Shape(project, items)
        for (it in items) it.stage = shape.stage(it)
        val clusters = clusters(project, items, bell)
        val centre = shape.centre
        val start = crate ?: bell
        val sweepFrom = angle(start.x - centre.first, start.z - centre.second)

        val field = Field(level, items)
        val steps = ArrayList<Planned>()
        val stakes = if (project == Advance.GUILD_HALL || project == Advance.ARCHER_TOWER) stakes(level, items) else listOf()
        for (s in stakes) steps.add(Planned(s.asLong(), VillageBuild.Kinds.STAKE, level.getBlockState(s), false, Stage.STAKES))
        val top = if (project in BIG) top(items, bell) else null

        // The stages in turn: what could not go in its stage (its support is later) goes in the next
        var carried = ArrayList<Item>()
        for (stage in RUN) {
            if (stage == Stage.TOPPING) {
                if (top != null) steps.add(Planned(top.asLong(), VillageBuild.Kinds.TOP, null, false, Stage.TOPPING))
                continue
            }
            val pool = ArrayList<Item>(carried)
            for (it in items) if (it.stage == stage) pool.add(it)
            carried = ArrayList()
            if (pool.isEmpty()) continue
            val sorted = pool.sortedWith(shape.order(stage, clusters, centre, sweepFrom))
            for ((rank, it) in sorted.withIndex()) it.rank = rank
            val ordered = together(growth(field, sorted, carried))
            for (it in ordered) {
                it.stage = stage
                steps.add(Planned(it.pos, VillageBuild.Kinds.BLOCK, it.state, it.swap, stage))
            }
        }
        // What is still not supported goes last of all: it was drawn that way
        for (it in together(carried)) steps.add(Planned(it.pos, VillageBuild.Kinds.BLOCK, it.state, it.swap, Stage.GROUNDS))
        for (s in stakes) steps.add(Planned(s.asLong(), VillageBuild.Kinds.PULL, level.getBlockState(s.above()), false, Stage.GROUNDS))
        if (top != null) steps.add(Planned(top.asLong(), VillageBuild.Kinds.UNTOP, null, false, Stage.GROUNDS))
        return Result(steps, buried, deps(field, steps))
    }

    // ---- What goes in together ----

    /**
     * The key of the thing [s] at [pos] is one part of, that goes in all at once (a door's two halves, a board's six cells): the
     * other half of a door would fall without it, and a board's cells each hold to the rest. Null for a block that stands alone.
     */
    fun groupOf(pos: BlockPos, s: BlockState): Long? = when {
        s.block is DoorBlock -> (if (s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) pos else pos.below()).asLong()
        s.block is NoticeBoardBlock -> NoticeBoardBlock.anchorPos(pos, s).asLong()
        else -> null
    }

    /** [ordered] with the parts of each thing that goes in together moved up to the last of them, so that each comes after what holds the others up. */
    private fun together(ordered: List<Item>): List<Item> {
        val groups = HashMap<Long, MutableList<Item>>()
        for (it in ordered) groupOf(BlockPos.of(it.pos), it.state)?.let { g -> groups.getOrPut(g) { ArrayList() }.add(it) }
        if (groups.values.none { it.size > 1 }) return ordered
        val last = HashMap<Item, List<Item>>()
        val moved = HashSet<Item>()
        for (members in groups.values) {
            if (members.size < 2) continue
            last[members.last()] = members
            moved.addAll(members)
        }
        val out = ArrayList<Item>(ordered.size)
        for (it in ordered) {
            val members = last[it]
            if (members != null) out.addAll(members) else if (it !in moved) out.add(it)
        }
        return out
    }

    /**
     * What holds each of [steps] up: for a block, the earliest step among its supports laid before it, or -1 if the world's own block
     * holds it there (one no step takes away) or nothing does (it was drawn that way); for the bough, the roof under it; for a block
     * cleared, the clearing of the block above it, if that is cleared first. Stakes and the steps that pull them up need nothing.
     */
    private fun deps(field: Field, steps: List<Planned>): IntArray {
        val at = HashMap<Long, Int>()
        for ((i, s) in steps.withIndex()) if (s.kind == VillageBuild.Kinds.BLOCK) at[s.pos] = i
        val out = IntArray(steps.size) { -1 }
        for ((i, s) in steps.withIndex()) {
            val state = s.state
            when (s.kind) {
                VillageBuild.Kinds.BLOCK -> {
                    if (state == null) continue
                    val pos = BlockPos.of(s.pos)
                    if (state.isAir) {
                        // Cleared from the top down: what stands above, if it is cleared too, goes first
                        val j = at[pos.above().asLong()]
                        if (j != null && j < i && steps[j].state?.isAir == true) out[i] = j
                        continue
                    }
                    var best = Int.MAX_VALUE
                    var world = false
                    for (c in field.supports(state, pos)) {
                        val key = c.asLong()
                        val j = at[key]
                        val solid = j != null && steps[j].state?.isAir == false
                        when {
                            j == null -> if (field.world(key)) world = true
                            // Laid before: it holds this up once it is
                            j < i -> if (solid) best = minOf(best, j)
                            // Laid after, over the world's own block: that holds it up meanwhile
                            j > i -> if (solid && field.world(key)) world = true
                        }
                        if (world) break
                    }
                    out[i] = if (world || best == Int.MAX_VALUE) -1 else best
                }
                VillageBuild.Kinds.TOP -> out[i] = at[BlockPos.of(s.pos).below().asLong()] ?: -1
                else -> {}
            }
        }
        return out
    }

    /** The projects that are topped out. */
    private val BIG = setOf(Advance.GUILD_HALL, Advance.STONE_WALLS, Advance.KEEP)

    /** Plants, snow, water and the like: what a block goes over without any being taken out. */
    fun soft(state: BlockState): Boolean =
        state.canBeReplaced() && state.fluidState.isEmpty && !state.hasBlockEntity() ||
            state.`is`(BlockTags.FLOWERS) || state.`is`(BlockTags.SAPLINGS) || state.`is`(Blocks.SNOW)

    // ---- Stages ----

    /** How a project's blocks are sorted into stages, worked out from what was recorded (its anchors) and what each block is. */
    private class Shape(val project: Advance, val items: List<Item>) {
        private val solid = items.filter { !it.state.isAir }
        val centre: Pair<Double, Double>

        /** The lowest course of the building (the floor, the first course of walls), the lowest of its roof (the stairs, the deck), and the footprint of a keep. */
        private var y0 = Int.MIN_VALUE
        private var roofBase = Int.MAX_VALUE
        private var x0 = 0
        private var x1 = -1
        private var z0 = 0
        private var z1 = -1

        init {
            val xs = solid.map { it.x }
            val zs = solid.map { it.z }
            centre = if (solid.isEmpty()) 0.0 to 0.0 else ((xs.min() + xs.max()) / 2.0 to (zs.min() + zs.max()) / 2.0)
            when (project) {
                Advance.GUILD_HALL -> {
                    y0 = solid.filter { it.state.`is`(BlockTags.DOORS) }.minOfOrNull { it.y } ?: solid.minOfOrNull { it.y } ?: 0
                    roofBase = solid.filter { it.state.`is`(Blocks.DARK_OAK_STAIRS) }.minOfOrNull { it.y } ?: (y0 + 5)
                }
                Advance.ARCHER_TOWER -> {
                    y0 = solid.filter { it.state.`is`(Blocks.STONE_BRICKS) || it.state.`is`(Blocks.MOSSY_STONE_BRICKS) }.minOfOrNull { it.y } ?: solid.minOfOrNull { it.y } ?: 0
                    roofBase = solid.filter { it.state.`is`(Blocks.SPRUCE_PLANKS) }.minOfOrNull { it.y } ?: (y0 + 8)
                }
                Advance.KEEP -> {
                    val corners = solid.filter { it.state.`is`(Blocks.POLISHED_ANDESITE) }
                    if (corners.isNotEmpty()) {
                        y0 = corners.minOf { it.y }
                        x0 = corners.minOf { it.x }
                        x1 = corners.maxOf { it.x }
                        z0 = corners.minOf { it.z }
                        z1 = corners.maxOf { it.z }
                    } else {
                        y0 = solid.minOfOrNull { it.y } ?: 0
                    }
                    val top = solid.filter { inside(it) && isStone(it.state) }.maxOfOrNull { it.y } ?: (y0 + 15)
                    roofBase = top - 1
                }
                else -> {}
            }
        }

        private fun inside(it: Item): Boolean = it.x in x0..x1 && it.z in z0..z1

        private fun isStone(s: BlockState): Boolean =
            s.`is`(Blocks.STONE_BRICKS) || s.`is`(Blocks.MOSSY_STONE_BRICKS) || s.`is`(Blocks.CRACKED_STONE_BRICKS) || s.`is`(Blocks.POLISHED_ANDESITE)

        private fun furniture(s: BlockState): Boolean =
            s.`is`(BlockTags.WOOL_CARPETS) || s.`is`(Blocks.POLISHED_ANDESITE) || s.`is`(Blocks.LECTERN) || s.block is LanternBlock ||
                s.`is`(Blocks.BOOKSHELF) || s.`is`(Blocks.BARREL) || s.`is`(Blocks.IRON_CHAIN) || s.`is`(Blocks.CRAFTING_TABLE) || s.`is`(Blocks.CHEST) ||
                s.block is LadderBlock || s.`is`(ModBlocks.ARCHER_POST.get())

        /** The stage [it] goes up in. */
        fun stage(it: Item): Stage {
            val s = it.state
            if (s.isAir) return Stage.GROUNDWORK
            return when (project) {
                Advance.LAMP_POSTS, Advance.MARKET -> Stage.GROUNDS
                Advance.PALISADE -> Stage.WALLS
                Advance.STONE_WALLS -> if (s.`is`(Blocks.STONE_BRICK_WALL) || s.block is LanternBlock) Stage.ROOF else Stage.WALLS
                Advance.GUILD_HALL -> when {
                    it.y < y0 -> Stage.FOOTINGS
                    s.`is`(ModBlocks.MARKET_BOARD.get()) -> Stage.GROUNDS
                    s.`is`(BlockTags.DOORS) || s.`is`(Blocks.GLASS_PANE) -> Stage.OPENINGS
                    furniture(s) -> Stage.FITTING
                    it.y >= roofBase -> Stage.ROOF
                    else -> Stage.WALLS
                }
                Advance.ARCHER_TOWER -> when {
                    it.y < y0 -> Stage.FOOTINGS
                    furniture(s) -> Stage.FITTING
                    it.y >= roofBase -> Stage.ROOF
                    else -> Stage.WALLS
                }
                Advance.KEEP -> when {
                    !inside(it) -> if (it.y < y0) Stage.FOOTINGS else Stage.GROUNDS
                    it.y < y0 -> Stage.FOOTINGS
                    furniture(s) -> Stage.FITTING
                    it.y >= roofBase -> Stage.ROOF
                    else -> Stage.WALLS
                }
                Advance.TRADE_ROAD -> Stage.GROUNDS
            }
        }

        /** How the blocks of [stage] are ordered among themselves. */
        fun order(stage: Stage, clusters: Map<Int, Int>, c: Pair<Double, Double>, from: Double): Comparator<Item> = when (project) {
            // A post or a stall at a time, one after another round the village; each from the ground up (what is cleared, from the top down)
            Advance.LAMP_POSTS, Advance.MARKET -> compareBy<Item>({ clusters[it.cluster] ?: 0 }, { if (stage == Stage.GROUNDWORK) -it.y else it.y }, { it.z }, { snake(it.x, it.z) })
            // A column at a time round the ring, from the ground up (what is cleared, from the top down)
            Advance.PALISADE, Advance.STONE_WALLS -> compareBy<Item>({ sweep(it, c, from) }, { if (stage == Stage.GROUNDWORK) -it.y else it.y }, { it.x }, { it.z })
            else -> when (stage) {
                // Cleared top down, row by row
                Stage.GROUNDWORK -> compareBy<Item>({ it.z }, { snake(it.x, it.z) }, { -it.y })
                // Laid in layers, row by row
                Stage.FOOTINGS -> compareBy<Item>({ it.y }, { it.z }, { snake(it.x, it.z) })
                // Course by course, round the building
                else -> compareBy<Item>({ it.y }, { sweep(it, c, from) }, { dist(it, c) }, { it.x }, { it.z })
            }
        }

        private fun dist(it: Item, c: Pair<Double, Double>): Int {
            val dx = it.x - c.first
            val dz = it.z - c.second
            return (dx * dx + dz * dz).toInt()
        }
    }

    /** Alternate rows run back and forth, so a row's end is beside the next row's start. */
    private fun snake(x: Int, z: Int): Int = if (Math.floorMod(z, 2) == 0) x else -x

    /** The direction of (dx, dz), as an angle. */
    private fun angle(dx: Double, dz: Double): Double = atan2(dz, dx)

    /** How far round the building [it] is from the angle [from], counterclockwise, in thousandths of a turn's radian. */
    private fun sweep(it: Item, c: Pair<Double, Double>, from: Double): Int {
        var a = angle(it.x - c.first, it.z - c.second) - from
        while (a < 0) a += 2 * PI
        while (a >= 2 * PI) a -= 2 * PI
        return (a * 400).toInt()
    }

    // ---- Clusters (posts, stalls) ----

    /**
     * The separate things a project is: for lamp posts and stalls, the groups of columns that touch (one post, one stall); the order
     * they are done in, the nearest the bell first and then each the nearest to the last. Gives each item's cluster rank by [Item.cluster].
     */
    private fun clusters(project: Advance, items: List<Item>, bell: BlockPos): Map<Int, Int> {
        if (project != Advance.LAMP_POSTS && project != Advance.MARKET) return mapOf()
        val parent = HashMap<Long, Long>()
        fun col(it: Item): Long = BlockPos.asLong(it.x, 0, it.z)
        fun find(a: Long): Long {
            var r = a
            while (parent[r] != r) r = parent[r]!!
            var c = a
            while (parent[c] != r) {
                val next = parent[c]!!
                parent[c] = r
                c = next
            }
            return r
        }
        for (it in items) if (!it.state.isAir) parent.putIfAbsent(col(it), col(it))
        for (it in items) {
            if (it.state.isAir) continue
            for (dx in -1..1) for (dz in -1..1) {
                val other = BlockPos.asLong(it.x + dx, 0, it.z + dz)
                if (parent.containsKey(other)) {
                    val a = find(col(it))
                    val b = find(other)
                    if (a != b) parent[a] = b
                }
            }
        }
        val ids = HashMap<Long, Int>()
        val sums = ArrayList<DoubleArray>()
        for (it in items) {
            if (it.state.isAir) continue
            val root = find(col(it))
            val id = ids.getOrPut(root) { sums.add(DoubleArray(3)); sums.size - 1 }
            it.cluster = id
            val s = sums[id]
            s[0] += it.x.toDouble()
            s[1] += it.z.toDouble()
            s[2] += 1.0
        }
        for (it in items) if (it.state.isAir) it.cluster = 0
        val centres = sums.map { (it[0] / it[2]) to (it[1] / it[2]) }
        val left = centres.indices.toMutableList()
        val tour = HashMap<Int, Int>()
        var at = bell.x.toDouble() to bell.z.toDouble()
        var rank = 0
        while (left.isNotEmpty()) {
            val next = left.minBy { (centres[it].first - at.first).let { dx -> dx * dx } + (centres[it].second - at.second).let { dz -> dz * dz } }
            left.remove(next)
            tour[next] = rank++
            at = centres[next]
        }
        return tour
    }

    // ---- Stakes and the bough ----

    /**
     * The four corner stakes, outside the corners of the work, where there is room for a post and its banner: at each corner the first
     * place out from it (a step at a time, up to [STAKE_OUT]) that the work neither takes nor digs away, on firm ground.
     */
    private fun stakes(level: ServerLevel, items: List<Item>): List<BlockPos> {
        val solid = items.filter { !it.state.isAir }
        if (solid.isEmpty()) return listOf()
        val x0 = solid.minOf { it.x } - 1
        val x1 = solid.maxOf { it.x } + 1
        val z0 = solid.minOf { it.z } - 1
        val z1 = solid.maxOf { it.z } + 1
        val taken = LongOpenHashSet()
        val cleared = LongOpenHashSet()
        for (it in items) {
            taken.add(it.pos)
            if (it.state.isAir) cleared.add(it.pos)
        }
        val out = ArrayList<BlockPos>()
        for ((corner, out1) in listOf((x0 to z0) to (-1 to -1), (x1 to z0) to (1 to -1), (x1 to z1) to (1 to 1), (x0 to z1) to (-1 to 1))) {
            for (k in 0..STAKE_OUT) {
                val x = corner.first + out1.first * k
                val z = corner.second + out1.second * k
                if (!level.hasChunkAt(BlockPos(x, 0, z))) break
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                val pos = BlockPos(x, y, z)
                if (taken.contains(pos.asLong()) || taken.contains(pos.above().asLong())) continue
                // Not on ground the work takes away, nor on what isn't firm
                if (cleared.contains(pos.below().asLong()) || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue
                out.add(pos)
                break
            }
        }
        return out
    }

    /**
     * Where the green bough goes: on the highest roof (or wall) block, the nearest the bell of the highest, with nothing above it.
     * Null if there is nothing to top.
     */
    private fun top(items: List<Item>, bell: BlockPos): BlockPos? {
        val taken = LongOpenHashSet()
        for (it in items) if (!it.state.isAir) taken.add(it.pos)
        val roofs = items.filter { it.stage == Stage.ROOF && !it.state.isAir }
        val best = roofs.filter { !taken.contains(BlockPos.asLong(it.x, it.y + 1, it.z)) }
            .sortedWith(compareBy<Item>({ -it.y }, { abs(it.x - bell.x) + abs(it.z - bell.z) })).firstOrNull() ?: return null
        return BlockPos(best.x, best.y + 1, best.z)
    }

    // ---- Support: nothing floats ----

    /** What the world and the plan hold, as the plan is made up: a block is there if the world had one that is solid, or the plan has laid it. */
    private class Field(val level: ServerLevel, items: List<Item>) {
        val byPos = Long2ObjectOpenHashMap<Item>()
        val laid = LongOpenHashSet()
        private val orig = HashMap<Long, Boolean>()

        init {
            for (it in items) byPos.put(it.pos, it)
        }

        fun present(pos: Long): Boolean {
            val item = byPos.get(pos)
            if (item != null && laid.contains(pos)) return !item.state.isAir
            return world(pos)
        }

        /** Whether the world had something firm at [pos] when the builders recorded the work (in land not loaded: believed so). */
        fun world(pos: Long): Boolean = orig.getOrPut(pos) {
            val p = BlockPos.of(pos)
            if (!level.isLoaded(p)) true else {
                val s = level.getBlockState(p)
                !s.isAir && !s.canBeReplaced() && s.fluidState.isEmpty
            }
        }

        /** Whether [item] has what it needs to stand: the cell it hangs from or leans on, or any neighbour. */
        fun supported(item: Item): Boolean {
            val s = item.state
            if (s.isAir) return true
            return supports(s, BlockPos.of(item.pos)).any { present(it.asLong()) }
        }

        /** The cells that may hold [s] up at [pos] (any one will do): the one it hangs from or leans on, or else any neighbour. */
        fun supports(s: BlockState, pos: BlockPos): List<BlockPos> = needs(s, pos) ?: Direction.entries.map { pos.relative(it) }

        /** The cells [s] at [pos] is held up by (any one will do), or null if any neighbour will do. */
        private fun needs(s: BlockState, pos: BlockPos): List<BlockPos>? = when {
            s.block is LadderBlock -> listOf(pos.relative(s.getValue(LadderBlock.FACING).opposite))
            s.block is WallBannerBlock -> listOf(pos.relative(s.getValue(WallBannerBlock.FACING).opposite))
            s.block is LanternBlock -> listOf(if (s.getValue(LanternBlock.HANGING)) pos.above() else pos.below())
            s.`is`(Blocks.IRON_CHAIN) -> listOf(pos.above(), pos.below())
            s.`is`(BlockTags.WOOL_CARPETS) || s.`is`(BlockTags.DOORS) || s.`is`(BlockTags.BANNERS) -> listOf(pos.below())
            else -> null
        }
    }

    /**
     * The items of [sorted] in the order they can be laid: always the first, in the order given, of those that are supported just now.
     * A laid item can support those beside it; what never gets support in this stage is put in [left] for the next.
     */
    private fun growth(field: Field, sorted: List<Item>, left: MutableList<Item>): List<Item> {
        val ready = PriorityQueue<Item>(compareBy { it.rank })
        val blocked = HashSet<Item>()
        for (it in sorted) if (field.supported(it)) ready.add(it) else blocked.add(it)
        val out = ArrayList<Item>(sorted.size)
        while (ready.isNotEmpty()) {
            val it = ready.poll()
            out.add(it)
            field.laid.add(it.pos)
            if (blocked.isEmpty()) continue
            val pos = BlockPos.of(it.pos)
            for (d in Direction.entries) {
                val near = field.byPos.get(pos.relative(d).asLong()) ?: continue
                if (near in blocked && field.supported(near)) {
                    blocked.remove(near)
                    ready.add(near)
                }
            }
        }
        left.addAll(blocked.sortedBy { it.rank })
        return out
    }
}
