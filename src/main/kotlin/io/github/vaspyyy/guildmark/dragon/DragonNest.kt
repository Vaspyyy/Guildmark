package io.github.vaspyyy.guildmark.dragon

import io.github.vaspyyy.guildmark.field.TrollCamp
import io.github.vaspyyy.guildmark.lair.LoreBooks
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.region.terrain.RegionTerrain
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.RotationSegment
import net.minecraft.world.level.levelgen.Heightmap
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A dragon's nest on a summit: the top flattened and cleared to open sky, a bed of scorched stone for the dragon
 * to lie on, a jagged rim of blackstone and basalt round it hung with bone and charred wood and open to the front
 * and either side, the ground beyond burnt black and brown, and at the back a heap of gold. A slain dragon's hoard is
 * revealed in three chests set in the heap (see [hoard]). Everything is laid on loaded land only, and only on
 * ground nobody has built on.
 */
object DragonNest {
    /** How far from the summit it was found anything of a nest may reach (its ramps, at most): every chunk this near must be loaded. */
    const val SPAN = 26

    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE
    /** The columns of ground read each side of the summit: far enough for a nest set a little off it, skirt and all. */
    private const val WINDOW = 14
    /** The most blocks of stone laid under a column of the floor, and of open air cleared over it. */
    private const val FILL_MAX = 18
    private const val CLEAR_UP = 24
    /** Blocks the tallest tusk and the dragon's headroom need between the floor and the top of the world. */
    private const val SKY = 14
    /** Steps in a way out of a gap, the most air one may stand over and the most land it may be cut into. */
    private const val RAMP_STEPS = 10
    private const val RAMP_FILL = 6
    private const val RAMP_CUT = 4
    /** The farthest block of the rim from the middle, along an axis: where a way out begins. */
    private const val RIM_EDGE = 6
    /** A camp's bench reaches this far from its fire, cuts or fills the ground by at most the second, and clears growth the third high over it. */
    private const val BENCH = 4
    private const val BENCH_REACH = 8
    private const val BENCH_CLEAR = 12

    /** North, east, south, west, the order of [SIDE_X] and [SIDE_Z]. */
    private val SIDES = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

    /** Where a nest will stand: its middle, the height of its floor, which way the dragon lies, and the land it's laid on. */
    class Plan internal constructor(
        val x: Int,
        val z: Int,
        val floor: Int,
        val facing: Direction,
        private val heights: IntArray,
        private val originX: Int,
        private val originZ: Int,
        internal val stone: BlockState,
    ) {
        /** The block the dragon lies on: the first open one over the middle of the floor. */
        val feet: BlockPos get() = BlockPos(x, floor + 1, z)

        /** The height of the ground at [x], [z] as it was before the nest (null beyond what was looked at). */
        fun ground(x: Int, z: Int): Int? {
            val dx = x - originX + WINDOW
            val dz = z - originZ + WINDOW
            if (dx < 0 || dz < 0 || dx > 2 * WINDOW || dz > 2 * WINDOW) return null
            return heights[dz * (2 * WINDOW + 1) + dx]
        }
    }

    // ---- Reading the land (loaded chunks only) ----

    /** Whether every chunk within [reach] blocks of [x], [z] is loaded. */
    fun loaded(level: ServerLevel, x: Int, z: Int, reach: Int): Boolean {
        for (cx in ((x - reach) shr 4)..((x + reach) shr 4)) for (cz in ((z - reach) shr 4)..((z + reach) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) return false
        }
        return true
    }

    /**
     * The height of the ground at [x], [z]: the first block down from the surface that isn't a tree or a plant
     * (never through water). Null if that land isn't loaded.
     */
    fun ground(level: ServerLevel, x: Int, z: Int): Int? {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return null
        var y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15)
        val pos = BlockPos.MutableBlockPos(x, y, z)
        while (y > level.minY) {
            val state = chunk.getBlockState(pos.setY(y))
            if (state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES) || (state.canBeReplaced() && state.fluidState.isEmpty)) y-- else break
        }
        return y
    }

    /** Whether there's water or lava at the ground block at [pos] or just over it. */
    fun wet(level: ServerLevel, pos: BlockPos): Boolean = !level.getFluidState(pos).isEmpty || !level.getFluidState(pos.above()).isEmpty

    // ---- Planning ----

    /**
     * Where a nest would stand on the summit near [sx], [sz], or null if it can't: some of the land isn't loaded,
     * there's water or lava about, or anything has been built or planted where it would reach. The middle may
     * move a few blocks from the summit, to the spot and height that take the least cutting and filling.
     */
    fun plan(level: ServerLevel, sx: Int, sz: Int): Plan? {
        if (!loaded(level, sx, sz, SPAN + 1)) return null
        val size = 2 * WINDOW + 1
        val heights = IntArray(size * size)
        for (dz in -WINDOW..WINDOW) for (dx in -WINDOW..WINDOW) {
            heights[(dz + WINDOW) * size + dx + WINDOW] = ground(level, sx + dx, sz + dz) ?: return null
        }
        // The rim and the open sky over the bed must fit under the top of the world
        val choice = choose(heights, WINDOW, level.maxY - SKY)
        val cx = sx + choice.ox
        val cz = sz + choice.oz
        val floor = choice.floor
        if (floor < level.minY + 24) return null
        val pos = BlockPos.MutableBlockPos()
        for (dz in -EDGE..EDGE) for (dx in -EDGE..EDGE) {
            val d = hypot(dx.toDouble(), dz.toDouble())
            if (d > SKIRT) continue
            val ground = heights[(cz + dz - sz + WINDOW) * size + (cx + dx - sx + WINDOW)]
            if (wet(level, pos.set(cx + dx, ground, cz + dz))) return null
            // Nothing anyone has built, planted or stored where the floor and rim go, and no water or lava running through
            if (d <= RIM + 1.2) for (y in floor - 2..floor + DEEPEST + 2) {
                val state = level.getBlockState(pos.set(cx + dx, y, cz + dz))
                if (!(TrollCamp.wild(state) || soil(state)) || !state.fluidState.isEmpty) return null
            }
        }
        val spin = Random(sx * 341873128712L + sz * 132897987541L).nextInt(4)
        val facing = SIDES[lowestSide(heights, WINDOW, choice.ox, choice.oz, spin)]
        val centre = heights[(choice.oz + WINDOW) * size + choice.ox + WINDOW]
        return Plan(cx, cz, floor, facing, heights, sx, sz, stoneBelow(level, cx, cz, centre, Blocks.STONE.defaultBlockState()))
    }

    // ---- Building ----

    /** Lays the nest [plan] describes. [seed] settles its look. */
    fun build(level: ServerLevel, plan: Plan, seed: Long) {
        val rng = Random(seed)
        val layout = NestLayout(rng, plan.facing.stepX, plan.facing.stepZ)
        val nuggets = ArrayList<BlockPos>()
        for (spot in layout.spots) {
            if (spot.zone == Zone.SKIRT) skirt(level, plan, rng, spot, nuggets) else disc(level, plan, rng, spot, nuggets)
        }
        for (way in listOf(plan.facing, plan.facing.clockWise, plan.facing.counterClockWise)) ramp(level, plan, rng, way)
        for (at in nuggets) scatterNuggets(level, rng, at)
    }

    /** One column of the flattened ground: cleared to open sky, filled up from below, floored, and the rim, heap or tusk stood on it. */
    private fun disc(level: ServerLevel, plan: Plan, rng: Random, spot: Spot, nuggets: MutableList<BlockPos>) {
        val x = plan.x + spot.dx
        val z = plan.z + spot.dz
        val floor = plan.floor
        val ground = plan.ground(x, z) ?: floor
        clearAbove(level, x, z, floor + 1)
        for (y in max(ground + 1, floor - FILL_MAX) until floor) set(level, x, y, z, stoneBelow(level, x, z, ground, plan.stone))
        set(level, x, floor, z, floorBlock(spot, rng))
        for (k in 1..spot.rise) set(level, x, floor + k, z, stackBlock(spot, k, rng))
        val above = BlockPos(x, floor + spot.rise + 1, z)
        when (spot.feature) {
            Feature.BONES -> set(level, above.x, above.y, above.z, bone(rng))
            Feature.SKULL -> set(level, above.x, above.y, above.z, skull(rng))
            Feature.NUGGETS -> nuggets.add(above)
            Feature.NONE -> if (spot.zone == Zone.SCORCH && rng.nextInt(4) == 0) set(level, above.x, above.y, above.z, ash(rng))
            else -> {}
        }
    }

    /** One column of the burnt skirt: the ground blackened, brown or browned, with a snag, bones or ash on it here and there. */
    private fun skirt(level: ServerLevel, plan: Plan, rng: Random, spot: Spot, nuggets: MutableList<BlockPos>) {
        val x = plan.x + spot.dx
        val z = plan.z + spot.dz
        val ground = plan.ground(x, z) ?: return
        val pos = BlockPos(x, ground, z)
        val above = pos.above()
        if (!scorchable(level.getBlockState(pos)) || !level.getFluidState(above).isEmpty) return
        // Burnt thick by the rim, thinning to nothing at the edge
        val burnt = !spot.path && rng.nextDouble() < 0.9 - (spot.distance - RIM) * 0.25
        if (burnt) {
            // A little magma well out from the rim, where the ways in don't go
            level.setBlock(pos, scorched(rng, spot.distance < 7.0), FLAGS)
            clearPlant(level, above)
        }
        when (spot.feature) {
            Feature.VENT -> {
                clearPlant(level, above)
                level.setBlock(pos, RegionTerrain.EMBER_VENT.get().defaultBlockState(), FLAGS)
            }
            Feature.GOLD -> {
                clearPlant(level, above)
                level.setBlock(pos, gold(rng), FLAGS)
            }
            Feature.SNAG -> {
                clearPlant(level, above)
                for (k in 0 until spot.rise) {
                    val at = above.above(k)
                    if (!open(level, at)) break
                    level.setBlock(at, charred(Direction.Axis.Y), FLAGS)
                }
            }
            Feature.LOG -> {
                val along = if (spot.axis == 0) Direction.EAST else Direction.SOUTH
                for (k in 0 until 3) {
                    val at = above.relative(along, k)
                    if (!open(level, at) || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) break
                    level.setBlock(at, charred(along.axis), FLAGS)
                }
            }
            Feature.BONES -> if (open(level, above)) level.setBlock(above, bone(rng), FLAGS)
            Feature.SKULL -> if (open(level, above)) level.setBlock(above, skull(rng), FLAGS)
            Feature.NUGGETS -> if (open(level, above)) nuggets.add(above)
            else -> if (burnt && spot.distance < 8.3 && rng.nextInt(4) == 0 && open(level, above)) level.setBlock(above, ash(rng), FLAGS)
        }
    }

    /**
     * A way out of the nest through the gap that opens toward [way], three wide, a block higher or lower for each block
     * out, so it can be walked: down where the land falls away (each step on stone down to the ground), up where it rises
     * (cut into it and open to the sky). It's laid only if its steps meet the land within reach; where the land is
     * already within a step of the floor there's no need of one, and where it falls away faster than steps can follow
     * there's none, rather than a causeway ending in mid-air.
     */
    private fun ramp(level: ServerLevel, plan: Plan, rng: Random, way: Direction) {
        val tops = steps(level, plan, way) ?: return
        val side = way.clockWise
        for ((n, top) in tops.withIndex()) {
            val x = plan.x + way.stepX * (RIM_EDGE + n + 1)
            val z = plan.z + way.stepZ * (RIM_EDGE + n + 1)
            for (l in -1..1) {
                val sx = x + side.stepX * l
                val sz = z + side.stepZ * l
                val ground = ground(level, sx, sz) ?: continue
                if (wet(level, BlockPos(sx, ground, sz))) continue
                for (y in ground + 1 until top) set(level, sx, y, sz, stoneBelow(level, sx, sz, ground, plan.stone))
                set(level, sx, top, sz, scorched(rng, true))
                clearAbove(level, sx, sz, top + 1)
            }
        }
    }

    /** The height of each step of a way out toward [way], the last on the level of the land, or null if none is to be laid. */
    private fun steps(level: ServerLevel, plan: Plan, way: Direction): List<Int>? {
        val tops = ArrayList<Int>()
        var top = plan.floor
        for (i in 1..RAMP_STEPS) {
            val x = plan.x + way.stepX * (RIM_EDGE + i)
            val z = plan.z + way.stepZ * (RIM_EDGE + i)
            val middle = ground(level, x, z) ?: return null
            if (wet(level, BlockPos(x, middle, z))) return null
            if (i == 1 && abs(middle - top) <= 1) return null
            top += (middle - top).coerceIn(-1, 1)
            if (top - middle > RAMP_FILL || middle - top > RAMP_CUT) return null
            tops.add(top)
            if (top == middle) return tops
        }
        return null
    }

    private fun set(level: ServerLevel, x: Int, y: Int, z: Int, state: BlockState) {
        level.setBlock(BlockPos(x, y, z), state, FLAGS)
    }

    /** Clears everything over [from] up to open sky (or as high as is worth the trouble) in the column at [x], [z]. */
    private fun clearAbove(level: ServerLevel, x: Int, z: Int, from: Int) {
        val air = Blocks.AIR.defaultBlockState()
        val pos = BlockPos.MutableBlockPos(x, from, z)
        for (y in from..min(level.maxY, from + CLEAR_UP)) {
            pos.setY(y)
            if (!level.getBlockState(pos).isAir) level.setBlock(pos, air, FLAGS)
        }
    }

    /** Clears a plant or a drift of snow standing at [pos]; never anything solid. */
    private fun clearPlant(level: ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (!state.isAir && state.canBeReplaced() && state.fluidState.isEmpty) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
    }

    /** Whether [pos] is open to put something in (air, or a plant or drift that gives way). */
    private fun open(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.isAir || (state.canBeReplaced() && state.fluidState.isEmpty)
    }

    // ---- A bench for a camp ----

    /**
     * Levels a bench for the camp whose fire is to stand at [at] (the open block over the ground), so it can stand on a
     * slope: the ground within [BENCH] blocks is cut or filled to the level under [at], and anything growing over it
     * cleared. Only wild, dry, loaded ground is touched, and no more than [BENCH_REACH] blocks of it up or down.
     * False, with nothing changed, if that can't be done.
     */
    fun bench(level: ServerLevel, at: BlockPos): Boolean {
        if (!loaded(level, at.x, at.z, BENCH + 3)) return false
        val floor = at.y - 1
        val columns = ArrayList<BlockPos>()
        val pos = BlockPos.MutableBlockPos()
        for (dz in -BENCH..BENCH) for (dx in -BENCH..BENCH) {
            if (dx * dx + dz * dz > BENCH * BENCH) continue
            val x = at.x + dx
            val z = at.z + dz
            val ground = ground(level, x, z) ?: return false
            if (abs(ground - floor) > BENCH_REACH || wet(level, pos.set(x, ground, z))) return false
            for (y in min(ground, floor) - 1..max(ground, floor) + 4) {
                val state = level.getBlockState(pos.set(x, y, z))
                if (!(TrollCamp.wild(state) || soil(state)) || !state.fluidState.isEmpty) return false
            }
            columns.add(BlockPos(x, ground, z))
        }
        val surface = level.getBlockState(at.below())
        val filling = if (soil(surface) || surface.`is`(BlockTags.DIRT)) Blocks.DIRT.defaultBlockState()
        else stoneBelow(level, at.x, at.z, floor, Blocks.STONE.defaultBlockState())
        val air = Blocks.AIR.defaultBlockState()
        for (column in columns) {
            val x = column.x
            val z = column.z
            if (column.y < floor) {
                for (y in column.y + 1 until floor) set(level, x, y, z, filling)
                set(level, x, floor, z, surface)
            } else if (column.y > floor) {
                set(level, x, floor, z, surface)
            }
            // The hump cut away, then the growth over it (trees, bushes, drifts) as high as it goes
            for (y in floor + 1..min(level.maxY, max(column.y, floor) + BENCH_CLEAR)) {
                pos.set(x, y, z)
                val state = level.getBlockState(pos)
                if (state.isAir) continue
                if (y <= column.y || state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES) || state.canBeReplaced()) level.setBlock(pos, air, FLAGS) else break
            }
        }
        return true
    }

    // ---- What it is made of ----

    private fun pick(rng: Random, vararg options: Pair<Block, Int>): BlockState {
        var roll = rng.nextInt(options.sumOf { it.second })
        for ((block, weight) in options) {
            roll -= weight
            if (roll < 0) return block.defaultBlockState()
        }
        return options[0].first.defaultBlockState()
    }

    private fun floorBlock(spot: Spot, rng: Random): BlockState = when {
        spot.feature == Feature.GOLD && spot.rise == 0 -> gold(rng)
        spot.feature == Feature.VENT -> RegionTerrain.EMBER_VENT.get().defaultBlockState()
        spot.zone == Zone.BED -> pick(rng, Blocks.BLACKSTONE to 4, Blocks.SMOOTH_BASALT to 3, Blocks.COARSE_DIRT to 2, Blocks.GILDED_BLACKSTONE to 1)
        spot.zone == Zone.SCORCH -> scorched(rng, spot.path || spot.distance < 4.0)
        else -> rimStone(rng)
    }

    /** What stands [k] blocks up from the floor in a column of rim, heap or tusk; [Spot.rise] is its height. */
    private fun stackBlock(spot: Spot, k: Int, rng: Random): BlockState = when (spot.feature) {
        Feature.HEAP -> pick(rng, Blocks.RAW_GOLD_BLOCK to 4, Blocks.GOLD_BLOCK to 2, Blocks.GILDED_BLACKSTONE to 4)
        Feature.CHEST -> Blocks.RAW_GOLD_BLOCK.defaultBlockState()
        Feature.GOLD -> gold(rng)
        Feature.TUSK -> Blocks.BONE_BLOCK.defaultBlockState()
        else -> when {
            // The odd crown of bone, snag of charred wood or lump of gold set into the rim
            k == spot.rise && rng.nextInt(16) == 0 -> Blocks.BONE_BLOCK.defaultBlockState()
            k == spot.rise && rng.nextInt(14) == 0 -> charred(Direction.Axis.Y)
            k == 1 && rng.nextInt(28) == 0 -> gold(rng)
            else -> rimStone(rng)
        }
    }

    private fun rimStone(rng: Random): BlockState = pick(
        rng, Blocks.BLACKSTONE to 5, Blocks.BASALT to 4, Blocks.SMOOTH_BASALT to 3, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS to 2,
        Blocks.POLISHED_BLACKSTONE to 1, Blocks.COBBLED_DEEPSLATE to 1, Blocks.TUFF to 1,
    )

    /** Scorched ground; with magma in it here and there, unless it's [safe] to walk (the dragon's bed, the ways in). */
    private fun scorched(rng: Random, safe: Boolean): BlockState =
        if (safe) pick(rng, Blocks.COARSE_DIRT to 5, Blocks.BLACKSTONE to 3, Blocks.BASALT to 2, Blocks.SMOOTH_BASALT to 1)
        else pick(rng, Blocks.COARSE_DIRT to 5, Blocks.BLACKSTONE to 3, Blocks.BASALT to 2, Blocks.MAGMA_BLOCK to 1, Blocks.SMOOTH_BASALT to 1)

    private fun gold(rng: Random): BlockState =
        (if (rng.nextInt(4) == 0) Blocks.GOLD_BLOCK else Blocks.RAW_GOLD_BLOCK).defaultBlockState()

    private fun charred(axis: Direction.Axis): BlockState = RegionTerrain.CHARRED_LOG.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis)

    private fun bone(rng: Random): BlockState =
        Blocks.BONE_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, if (rng.nextBoolean()) Direction.Axis.X else Direction.Axis.Z)

    private fun skull(rng: Random): BlockState =
        (if (rng.nextInt(6) == 0) Blocks.WITHER_SKELETON_SKULL else Blocks.SKELETON_SKULL).defaultBlockState()
            .setValue(SkullBlock.ROTATION, RotationSegment.convertToSegment(rng.nextFloat() * 360f))

    private fun ash(rng: Random): BlockState =
        RegionTerrain.ASH.get().defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + rng.nextInt(3))

    /**
     * Natural soil that the dirt tag leaves out in this version: grass, podzol and mycelium (the grass-blocks tag),
     * moss and mud. [TrollCamp.wild] doesn't count it, and a summit's meadow is mostly this.
     */
    private fun soil(state: BlockState): Boolean =
        state.`is`(BlockTags.GRASS_BLOCKS) || state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.MOSS_BLOCK) ||
            state.`is`(Blocks.PALE_MOSS_BLOCK) || state.`is`(Blocks.MUD)

    /** Ground and growth that may be burnt: never anything a player made. */
    private fun scorchable(state: BlockState): Boolean =
        soil(state) || state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.SNOW) ||
            state.`is`(BlockTags.TERRACOTTA) || state.`is`(BlockTags.ICE) || state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.CLAY) ||
            state.`is`(Blocks.SANDSTONE) || state.`is`(Blocks.RED_SANDSTONE) || state.`is`(Blocks.CALCITE)

    /** The stone to fill with in the column at [x], [z]: what's under its ground (a few blocks down), else [fallback]. */
    private fun stoneBelow(level: ServerLevel, x: Int, z: Int, ground: Int, fallback: BlockState): BlockState {
        val pos = BlockPos.MutableBlockPos(x, ground, z)
        for (dy in 0..2) {
            val state = level.getBlockState(pos.setY(ground - dy))
            if (state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.TERRACOTTA) || state.`is`(Blocks.SANDSTONE) || state.`is`(Blocks.RED_SANDSTONE)) return state
        }
        return fallback
    }

    /** A few gold nuggets lying loose on the ground at [at], there for good (they never fade) until someone takes them. */
    private fun scatterNuggets(level: ServerLevel, rng: Random, at: BlockPos) {
        val item = ItemEntity(
            level, at.x + 0.25 + rng.nextDouble() * 0.5, at.y + 0.1, at.z + 0.25 + rng.nextDouble() * 0.5,
            ItemStack(Items.GOLD_NUGGET, 1 + rng.nextInt(3)),
        )
        item.setDeltaMovement(0.0, 0.0, 0.0)
        item.setUnlimitedLifetime()
        level.addFreshEntity(item)
    }

    // ---- The hoard ----

    /** Where the hoard's three chests go: side by side in the heap at the back of the nest ([feet] is where the dragon lies). */
    fun chestSpots(feet: BlockPos, facing: Direction): List<BlockPos> {
        val back = facing.opposite
        val side = back.clockWise
        return intArrayOf(-2, 0, 2).map { a -> feet.relative(back, 4).relative(side, a) }
    }

    /**
     * Reveals a slain dragon's hoard: the gold at the back of the nest gives way to three chests, filled with what
     * [loot] makes for land of [tier]. A chest that's still there from an earlier dragon, with things left in it,
     * takes the new hoard as far as it has room, and what doesn't fit lies beside it.
     */
    fun hoard(level: ServerLevel, feet: BlockPos, facing: Direction, tier: Int) {
        val rng = Random(level.random.nextLong())
        for ((spot, load) in chestSpots(feet, facing).zip(loot(level, tier))) deposit(level, spot, facing, load, rng)
        level.playSound(null, feet, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.HOSTILE, 1.0f, 1.0f)
    }

    /** When a new dragon takes the nest: the hoard's chests that were emptied go back to gold; any with things in them stay. */
    fun cover(level: ServerLevel, feet: BlockPos, facing: Direction) {
        for (spot in chestSpots(feet, facing)) {
            val chest = level.getBlockEntity(spot) as? ChestBlockEntity ?: continue
            if (chest.isEmpty) level.setBlock(spot, Blocks.RAW_GOLD_BLOCK.defaultBlockState(), FLAGS)
        }
    }

    private fun deposit(level: ServerLevel, pos: BlockPos, facing: Direction, load: List<ItemStack>, rng: Random) {
        if (!level.getBlockState(pos).`is`(Blocks.CHEST)) {
            level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), FLAGS)
        }
        val chest = level.getBlockEntity(pos) as? ChestBlockEntity
        for (stack in load) {
            val free = if (chest == null) emptyList() else (0 until chest.containerSize).filter { chest.getItem(it).isEmpty }
            if (chest == null || free.isEmpty()) {
                level.addFreshEntity(ItemEntity(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, stack))
                continue
            }
            chest.setItem(free[rng.nextInt(free.size)], stack)
        }
    }

    /**
     * What a dragon's hoard holds on land of [tier], in three lots (the chests, left to right): coin and emeralds,
     * the relics (the best the land's tier allows) with the dragons' lore book and diamonds, and gold. A dragon
     * with no nest drops all of it where it falls.
     */
    fun loot(level: ServerLevel, tier: Int): List<List<ItemStack>> {
        val random = level.random
        val top = if (tier >= 3) Rarity.LEGENDARY else Rarity.EPIC
        val next = if (tier >= 3) Rarity.EPIC else Rarity.RARE
        val coin = ArrayList<ItemStack>()
        splitInto(coin, ModItems.GUILD_MARK.get(), 120 + tier * 30 + random.nextInt(30))
        splitInto(coin, Items.EMERALD, 14 + tier * 6 + random.nextInt(8))
        val treasure = arrayListOf(
            Relics.roll(level, min(tier + 4, 6), top),
            Relics.roll(level, min(tier + 3, 6), next),
            Relics.cache(next),
            LoreBooks.fragment(LORE),
        )
        splitInto(treasure, Items.DIAMOND, 6 + tier * 3 + random.nextInt(4))
        val gold = ArrayList<ItemStack>()
        splitInto(gold, Items.GOLD_INGOT, 28 + tier * 12 + random.nextInt(16))
        splitInto(gold, Items.GOLD_BLOCK, 2 + random.nextInt(3))
        return listOf(coin, treasure, gold)
    }

    private fun splitInto(out: MutableList<ItemStack>, item: net.minecraft.world.item.Item, count: Int) {
        var left = count
        while (left > 0) {
            val n = min(left, 64)
            out.add(ItemStack(item, n))
            left -= n
        }
    }

    /** The dragons' lore book (lore.guildmark.30.*). */
    private const val LORE = 30

    // BEGIN LAYOUT: the geometry below uses no Minecraft types, so a scratch test can compile and run it by itself.

    /**
     * The land under the nest and the nest's own measure, as scratch figures: [choose] picks the spot and height that
     * take the least filling and cutting, [lowestSide] the way the dragon looks out.
     */
    internal class Choice(val ox: Int, val oz: Int, val floor: Int, val cost: Double)

    /** How far the nest's middle may move from the summit found, each way. */
    private const val SEARCH = 4
    /** How far the floor may sit below the highest ground of its bed and rim. */
    private const val DEEPEST = 14

    internal val SIDE_X = intArrayOf(0, 1, 0, -1)
    internal val SIDE_Z = intArrayOf(-1, 0, 1, 0)

    /** The columns (as x, z pairs) of the bed and rim: where the ground is flattened. */
    private val FLAT: IntArray = run {
        val out = ArrayList<Int>()
        for (dz in -EDGE..EDGE) for (dx in -EDGE..EDGE) if (hypot(dx.toDouble(), dz.toDouble()) < RIM) {
            out.add(dx)
            out.add(dz)
        }
        out.toIntArray()
    }

    /**
     * Picks where the nest's middle goes (within [SEARCH] of the window's middle) and how high its floor lies (no
     * higher than [ceiling]), given the ground heights [h] of a window [w] either side of that middle: the least ground
     * cut away and filled in, with a fill deeper than [FILL_MAX] costing most, and a mild pull toward the summit and
     * toward keeping its height.
     */
    internal fun choose(h: IntArray, w: Int, ceiling: Int): Choice {
        val size = 2 * w + 1
        var best: Choice? = null
        for (oz in -SEARCH..SEARCH) for (ox in -SEARCH..SEARCH) {
            val pull = sqrt((ox * ox + oz * oz).toDouble())
            if (pull > SEARCH + 0.5) continue
            var top = Int.MIN_VALUE
            for (k in FLAT.indices step 2) top = max(top, h[(oz + FLAT[k + 1] + w) * size + ox + FLAT[k] + w])
            val highest = min(top, ceiling)
            for (floor in min(top - DEEPEST, highest)..highest) {
                var cut = 0
                var fill = 0
                var over = 0
                for (k in FLAT.indices step 2) {
                    val g = h[(oz + FLAT[k + 1] + w) * size + ox + FLAT[k] + w]
                    if (g > floor) {
                        cut += g - floor
                    } else if (g < floor) {
                        fill += floor - g
                        if (floor - g > FILL_MAX) over += floor - g - FILL_MAX
                    }
                }
                val cost = cut * 0.4 + fill + over * 4.0 + (top - floor) * 5.0 + pull * 3.0
                val current = best
                if (current == null || cost < current.cost) best = Choice(ox, oz, floor, cost)
            }
        }
        return best!!
    }

    /**
     * Which of the four sides (north, east, south, west: [SIDE_X], [SIDE_Z]) the ground falls away most on, past the rim
     * from the middle ([ox], [oz]) of a window [w] either side; ties go to the first from [start]. The dragon looks out that way.
     */
    internal fun lowestSide(h: IntArray, w: Int, ox: Int, oz: Int, start: Int): Int {
        val size = 2 * w + 1
        var best = start and 3
        var bestSum = Int.MAX_VALUE
        for (n in 0 until 4) {
            val side = (start + n) and 3
            var sum = 0
            for (k in 7..9) {
                val x = (ox + SIDE_X[side] * k).coerceIn(-w, w)
                val z = (oz + SIDE_Z[side] * k).coerceIn(-w, w)
                sum += h[(z + w) * size + x + w]
            }
            if (sum < bestSum) {
                bestSum = sum
                best = side
            }
        }
        return best
    }

    // END LAYOUT
}

// BEGIN LAYOUT TYPES

/** Distances from the middle of the bed, in blocks: the dragon lies within the first, the floor is scorched out to the second, the rim stands out to the third and the burnt skirt reaches the fourth. */
private const val BED = 3.5
private const val SCORCH = 4.8
private const val RIM = 6.3
private const val SKIRT = 9.3
/** The columns of a nest lie within this many blocks of its middle, along either axis. */
private const val EDGE = 9
private const val SIDE_LEN = 2 * EDGE + 1
/** How much of the way round behind the bed is the high back wall: a column is in it if it's this near straight back (the cosine of 40 degrees). */
private const val BACK_WALL = 0.766

/** Which part of the nest a column belongs to. */
internal enum class Zone { BED, SCORCH, RIM, SKIRT }

/** What is set on or into a column besides its ground. */
internal enum class Feature { NONE, HEAP, GOLD, CHEST, TUSK, SNAG, LOG, BONES, SKULL, VENT, NUGGETS }

/** One column of a nest, [dx] and [dz] from the middle of the bed. */
internal class Spot(val dx: Int, val dz: Int, val zone: Zone) {
    val distance: Double = hypot(dx.toDouble(), dz.toDouble())
    /** Blocks of rim, heap or tusk standing on the floor here. */
    var rise = 0
    var feature = Feature.NONE
    /** Which way a lying log runs: 0 along x, 1 along z. */
    var axis = 0
    /** One of the ways in, kept free of rim and magma. */
    var path = false
}

/**
 * Where everything goes in a nest, for a dragon lying facing ([fx], [fz]) (one step along an axis): the rim, jagged
 * and open to the front and sides, with a high wall behind; the heap of gold at the back with a place for each of
 * the hoard's three chests; four tusks of bone at the diagonals; and snags, bones, vents and loose gold about.
 */
internal class NestLayout(private val rng: Random, private val fx: Int, private val fz: Int) {
    val spots = ArrayList<Spot>()
    private val grid = arrayOfNulls<Spot>(SIDE_LEN * SIDE_LEN)
    private val bx = -fx
    private val bz = -fz
    private val sx = -bz
    private val sz = bx

    init {
        for (dz in -EDGE..EDGE) for (dx in -EDGE..EDGE) {
            val d = hypot(dx.toDouble(), dz.toDouble())
            if (d > SKIRT) continue
            val zone = when {
                d <= BED -> Zone.BED
                d < SCORCH -> Zone.SCORCH
                d < RIM -> Zone.RIM
                else -> Zone.SKIRT
            }
            val spot = Spot(dx, dz, zone)
            spots.add(spot)
            grid[(dz + EDGE) * SIDE_LEN + dx + EDGE] = spot
        }
        for (spot in spots) spot.path = ways().any { (gx, gz) -> along(spot, gx, gz) > 0 && across(spot, gx, gz) <= 1 }
        rim()
        heap()
        tusks()
        scatter(Feature.VENT, 3, 4.0, 8.5, Zone.SCORCH, Zone.SKIRT)
        scatter(Feature.GOLD, 4, 4.0, 8.5, Zone.SCORCH, Zone.RIM, Zone.SKIRT)
        scatter(Feature.BONES, 6, 3.5, 8.5, Zone.SCORCH, Zone.SKIRT)
        scatter(Feature.SKULL, 3, 3.5, 8.5, Zone.SCORCH, Zone.SKIRT)
        scatter(Feature.SNAG, 3, 7.0, 9.0, Zone.SKIRT)
        scatter(Feature.LOG, 2, 6.5, 9.0, Zone.SKIRT)
        scatter(Feature.NUGGETS, 8, 1.5, 5.0, Zone.BED, Zone.SCORCH)
        for (spot in spots) {
            when (spot.feature) {
                Feature.SNAG -> spot.rise = 2 + rng.nextInt(3)
                Feature.LOG -> spot.axis = rng.nextInt(2)
                else -> {}
            }
        }
    }

    fun at(dx: Int, dz: Int): Spot? = if (abs(dx) > EDGE || abs(dz) > EDGE) null else grid[(dz + EDGE) * SIDE_LEN + dx + EDGE]

    /** The column [a] to one side of the middle and [b] behind it (the back is where the heap lies). */
    fun frame(a: Int, b: Int): Spot? = at(a * sx + b * bx, a * sz + b * bz)

    /** The three ways in and out, as steps along an axis: straight ahead and to either side. */
    private fun ways(): List<Pair<Int, Int>> = listOf(fx to fz, sx to sz, -sx to -sz)

    private fun along(spot: Spot, gx: Int, gz: Int): Int = spot.dx * gx + spot.dz * gz

    private fun across(spot: Spot, gx: Int, gz: Int): Int = abs(spot.dz * gx - spot.dx * gz)

    /** The rim: a jagged ring a block or two high, now and then five, low in the ways in and high behind the heap. */
    private fun rim() {
        val steps = 48
        val profile = IntArray(steps)
        var h = 2
        for (i in 0 until steps) {
            h = (h + when (rng.nextInt(10)) {
                0, 1, 2 -> -1
                3, 4, 5 -> 0
                else -> 1
            }).coerceIn(1, 4)
            profile[i] = if (rng.nextInt(12) == 0) 5 else h
        }
        for (spot in spots) {
            if (spot.zone != Zone.RIM) continue
            val angle = atan2(spot.dz.toDouble(), spot.dx.toDouble())
            val index = (((angle + PI) / (2 * PI)) * steps).toInt().coerceIn(0, steps - 1)
            var rise = profile[index] + rng.nextInt(3) - 1
            for ((gx, gz) in ways()) {
                val ahead = along(spot, gx, gz)
                val wide = across(spot, gx, gz)
                if (ahead > 0 && wide <= 1) rise = 0 else if (ahead > 0 && wide == 2) rise = min(rise, 1)
            }
            if ((spot.dx * bx + spot.dz * bz) / spot.distance > BACK_WALL) rise = max(rise, 3)
            spot.rise = max(rise, 0)
        }
    }

    /** The heap of gold at the back: a row of chest places among gold blocks, a taller row of heap behind them, and gold half sunk in the floor in front. */
    private fun heap() {
        val rises = intArrayOf(1, 2, 3, 3, 3, 2, 1)
        for (a in -3..3) frame(a, 5)?.let {
            it.feature = Feature.HEAP
            it.rise = rises[a + 3]
        }
        for (a in intArrayOf(-2, 0, 2)) frame(a, 4)?.let {
            it.feature = Feature.CHEST
            it.rise = 1
        }
        for (a in intArrayOf(-1, 1)) frame(a, 4)?.let {
            it.feature = Feature.GOLD
            it.rise = 1
        }
        for (a in intArrayOf(-1, 1)) frame(a, 3)?.let {
            it.feature = Feature.GOLD
            it.rise = 0
        }
    }

    /** Four tusks of bone, tall on the rim at the diagonals. */
    private fun tusks() {
        for ((u, v) in listOf(fx + sx to fz + sz, fx - sx to fz - sz, bx + sx to bz + sz, bx - sx to bz - sz)) {
            val length = hypot(u.toDouble(), v.toDouble())
            var best: Spot? = null
            var bestScore = -1.0
            for (spot in spots) {
                if (spot.zone != Zone.RIM || spot.feature != Feature.NONE || spot.distance < 5.2) continue
                val score = (spot.dx * u + spot.dz * v) / (spot.distance * length)
                if (score > bestScore) {
                    bestScore = score
                    best = spot
                }
            }
            best?.let {
                it.feature = Feature.TUSK
                it.rise = 5 + rng.nextInt(2)
            }
        }
    }

    /** The heap and the room before it: nothing else is put there. */
    private fun reserved(spot: Spot): Boolean {
        val b = spot.dx * bx + spot.dz * bz
        val a = spot.dx * sx + spot.dz * sz
        return b in 2..7 && abs(a) <= 4
    }

    /** Puts [feature] on [count] columns picked at random between [from] and [to] blocks out, in [zones], where nothing else is. */
    private fun scatter(feature: Feature, count: Int, from: Double, to: Double, vararg zones: Zone) {
        val options = spots.filter {
            it.feature == Feature.NONE && it.zone in zones && it.distance in from..to && !reserved(it) && !it.path
        }.shuffled(rng)
        for (spot in options.take(count)) {
            spot.feature = feature
            if (feature == Feature.GOLD) spot.rise = 0
        }
    }
}

// END LAYOUT TYPES
