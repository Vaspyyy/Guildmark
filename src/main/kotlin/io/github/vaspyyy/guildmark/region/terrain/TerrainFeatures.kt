package io.github.vaspyyy.guildmark.region.terrain

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.region.Noise2
import io.github.vaspyyy.guildmark.region.RegionCells
import io.github.vaspyyy.guildmark.region.RegionKind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.MultifaceBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.SpeleothemBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SpeleothemThickness
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.material.Fluids
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

// The four lands' ground cover. Each feature runs once for every overworld chunk as it's first made (its placed
// feature has no placement, so it's handed the chunk's corner; see data/guildmark/worldgen/placed_feature/region_*),
// looks the chunk over column by column, keeps to its own land's columns and writes only inside the chunk. With
// the regionTerrain setting off they do nothing; land made before keeps what it had.

/** Block updates only, as other features place theirs: no neighbour updates while the chunk is being made. */
private const val FLAGS = 2

/**
 * The columns of the chunk at [origin] that lie in [kind], told by the biome at the ground of each 4x4 cell,
 * as biomes are stored. [any] is false when none do, and there's nothing to do here.
 */
internal class Patch(level: WorldGenLevel, origin: BlockPos, kind: RegionKind) {
    private val chunk = ChunkPos.containing(origin)
    val minX = chunk.minBlockX
    val minZ = chunk.minBlockZ
    private val cells = BooleanArray(16)
    val any: Boolean

    init {
        val pos = BlockPos.MutableBlockPos()
        var found = false
        for (cx in 0 until 4) for (cz in 0 until 4) {
            val x = minX + cx * 4 + 2
            val z = minZ + cz * 4 + 2
            pos.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z)
            if (RegionKind.ofBiome(level.getBiome(pos)) == kind) {
                cells[cx * 4 + cz] = true
                found = true
            }
        }
        any = found
    }

    /** Whether column ([dx], [dz]) of the chunk lies in the land. */
    fun has(dx: Int, dz: Int): Boolean = cells[(dx shr 2) * 4 + (dz shr 2)]

    /** Whether world column ([x], [z]) lies inside the chunk, where writing is allowed. */
    fun inside(x: Int, z: Int): Boolean = x - minX in 0..15 && z - minZ in 0..15
}

/** The slow noises that lay the ground cover out in patches, drifts and veins, made once for each world seed. */
internal class TerrainNoise private constructor(seed: Long) {
    val rot = Noise2(seed xor 0x0B06_807L)
    val lanterns = Noise2(seed xor 0x1A57_E2L)
    val reeds = Noise2(seed xor 0x2EED_5L)
    val ash = Noise2(seed xor 0x0A5A_5L)
    val ashFine = Noise2(seed xor 0x0A5A_6L)
    val heat = Noise2(seed xor 0x4EA7_0L)
    val burnt = Noise2(seed xor 0xB12E_7L)
    val veins = Noise2(seed xor 0x6E1A_5L)

    companion object {
        private val BY_SEED = ConcurrentHashMap<Long, TerrainNoise>()

        fun of(seed: Long): TerrainNoise = BY_SEED.computeIfAbsent(seed) { TerrainNoise(it) }
    }
}

/**
 * The marsh: its bare mud and grass rotting under drifts of black leaves, bog lanterns glowing in a few hollows,
 * and cattails standing in the shallows and along the banks.
 */
class MarshGrowth : Feature {
    override fun codec(): MapCodec<MarshGrowth> = CODEC

    override fun place(level: WorldGenLevel, chunkGenerator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (!RegionTerrain.enabled()) return false
        val patch = Patch(level, origin, RegionKind.MARSH)
        if (!patch.any) return false
        val noise = TerrainNoise.of(level.seed)
        val pos = BlockPos.MutableBlockPos()
        val rot = RegionTerrain.BOG_ROT.get().defaultBlockState()
        val lantern = RegionTerrain.BOG_LANTERN.get().defaultBlockState()
        val cattails = RegionTerrain.CATTAILS.get().defaultBlockState()
        for (dx in 0 until 16) for (dz in 0 until 16) {
            if (!patch.has(dx, dz)) continue
            val x = patch.minX + dx
            val z = patch.minZ + dz
            // The ground or the water's top; down through the water to the bottom, if it's shallow
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            var floorY = top
            while (floorY > top - SHALLOW && level.isWaterAt(pos.set(x, floorY, z))) floorY--
            val depth = top - floorY
            val floor = level.getBlockState(pos.set(x, floorY, z))
            val reeds = noise.reeds.at(x / 7.0, z / 7.0)
            when (depth) {
                0 -> {
                    if (!floor.`is`(BlockTags.SUBSTRATE_OVERWORLD)) continue
                    val at = pos.set(x, top + 1, z).immutable()
                    val here = level.getBlockState(at)
                    val open = here.isAir
                    // Reeds along the water's edge
                    if (open && waterBeside(level, x, top, z) && level.isEmptyBlock(at.above()) &&
                        random.nextFloat() < CATTAILS_BANK + reeds.toFloat() * 0.2f) {
                        DoublePlantBlock.placeAt(level, cattails, at, FLAGS)
                    } else if (open && noise.lanterns.at(x / 23.0, z / 23.0) > 0.42 && random.nextFloat() < LANTERNS) {
                        level.setBlock(at, lantern, FLAGS)
                    } else if ((open || here.`is`(Blocks.SHORT_GRASS)) &&
                        noise.rot.at(x / 9.0, z / 9.0) + noise.rot.at(x / 3.0 + 40, z / 3.0) * 0.3 > 0.05 && random.nextFloat() < ROT) {
                        level.setBlock(at, rot, FLAGS)
                    }
                }
                1 -> {
                    // Standing in one block of still water, the heads up in the air
                    if (!reedBed(floor)) continue
                    val water = level.getBlockState(pos.set(x, top, z))
                    if (!water.`is`(Blocks.WATER) || !water.fluidState.isSource || !level.isEmptyBlock(pos.set(x, top + 1, z))) continue
                    if (random.nextFloat() < CATTAILS_SHALLOWS + reeds.toFloat() * 0.3f) {
                        DoublePlantBlock.placeAt(level, cattails, BlockPos(x, top, z), FLAGS)
                    }
                }
            }
        }
        return true
    }

    /** Whether there's water level with the ground at ([x], [y], [z]) next to it: the bank of a pool. */
    private fun waterBeside(level: WorldGenLevel, x: Int, y: Int, z: Int): Boolean {
        val pos = BlockPos.MutableBlockPos()
        for (dir in Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.set(x + dir.stepX, y, z + dir.stepZ)).fluidState.isSourceOfType(Fluids.WATER)) return true
        }
        return false
    }

    /** Ground cattails root in (see CattailBlock.mayPlaceOn). */
    private fun reedBed(floor: BlockState): Boolean =
        floor.`is`(BlockTags.SUPPORTS_VEGETATION) || floor.`is`(BlockTags.SAND) || floor.`is`(Blocks.CLAY) || floor.`is`(Blocks.GRAVEL)

    companion object {
        val CODEC: MapCodec<MarshGrowth> = MapCodec.unit(MarshGrowth())
        /** Water deeper than this (blocks) grows nothing here. */
        private const val SHALLOW = 3
        /** Chances on a fitting spot: cattails on a bank and in the shallows, a lantern in its hollows, rot in its patches. */
        private const val CATTAILS_BANK = 0.3f
        private const val CATTAILS_SHALLOWS = 0.32f
        private const val LANTERNS = 0.08f
        private const val ROT = 0.7f
    }
}

/**
 * The ashen highlands: ash drifted over everything, deepest where the ground is hottest; the burnt trunks of
 * trees, standing and fallen; and in the hottest ground, vents with the fire still in them.
 */
class Ashfall : Feature {
    override fun codec(): MapCodec<Ashfall> = CODEC

    override fun place(level: WorldGenLevel, chunkGenerator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (!RegionTerrain.enabled()) return false
        val patch = Patch(level, origin, RegionKind.ASHEN)
        if (!patch.any) return false
        val noise = TerrainNoise.of(level.seed)
        // Burnt trees, more where the fire ran worst
        val burnt = noise.burnt.at((patch.minX + 8) / 90.0, (patch.minZ + 8) / 90.0)
        val trees = if (burnt > 0.25) 3 else if (burnt > -0.2) 1 else 0
        repeat(trees) { if (random.nextFloat() < TREE) snag(level, patch, random) }
        // Vents in the hottest ground
        val pos = BlockPos.MutableBlockPos()
        val vents = ArrayList<BlockPos>()
        for (dx in 0 until 16) for (dz in 0 until 16) {
            if (!patch.has(dx, dz)) continue
            val x = patch.minX + dx
            val z = patch.minZ + dz
            val heat = noise.heat.at(x / 52.0, z / 52.0)
            if (heat < HOT || random.nextFloat() > (heat - HOT).toFloat() * VENTS) continue
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            if (!scorchable(level.getBlockState(pos.set(x, y, z))) || !level.isEmptyBlock(pos.set(x, y + 1, z))) continue
            level.setBlock(pos.set(x, y, z), RegionTerrain.EMBER_VENT.get().defaultBlockState(), FLAGS)
            vents.add(BlockPos(x, y, z))
        }
        // Ash over it all, in drifts, deeper by the vents
        val ash = RegionTerrain.ASH.get().defaultBlockState()
        for (dx in 0 until 16) for (dz in 0 until 16) {
            if (!patch.has(dx, dz)) continue
            val x = patch.minX + dx
            val z = patch.minZ + dz
            val drift = noise.ash.at(x / 15.0, z / 15.0) * 0.8 + noise.ashFine.at(x / 4.0, z / 4.0) * 0.3
            var layers = if (drift > 0.8) 3 else if (drift > 0.5) 2 else if (drift > 0.1) 1 else 0
            if (vents.any { Math.abs(it.x - x) <= 2 && Math.abs(it.z - z) <= 2 }) layers++
            if (layers == 0) continue
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            val ground = level.getBlockState(pos.set(x, y, z))
            if (!scorchable(ground) || !ground.isFaceSturdy(level, pos, Direction.UP)) continue
            val above = level.getBlockState(pos.set(x, y + 1, z))
            if (!above.isAir && !above.`is`(Blocks.SHORT_GRASS) && !above.`is`(Blocks.SHORT_DRY_GRASS)) continue
            level.setBlock(pos, ash.setValue(SnowLayerBlock.LAYERS, min(layers, 4)), FLAGS)
            // Grass doesn't live long under a deep drift
            if (layers >= 2 && ground.`is`(Blocks.GRASS_BLOCK)) level.setBlock(pos.set(x, y, z), Blocks.COARSE_DIRT.defaultBlockState(), FLAGS)
        }
        return true
    }

    /** A burnt trunk standing with a stub of a branch or two, or now and then one fallen and lying along the ground. */
    private fun snag(level: WorldGenLevel, patch: Patch, random: RandomSource) {
        val dx = 2 + random.nextInt(12)
        val dz = 2 + random.nextInt(12)
        if (!patch.has(dx, dz)) return
        val x = patch.minX + dx
        val z = patch.minZ + dz
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        val pos = BlockPos.MutableBlockPos(x, y - 1, z)
        if (!scorchable(level.getBlockState(pos))) return
        val log = RegionTerrain.CHARRED_LOG.get().defaultBlockState()
        if (random.nextFloat() < FALLEN) {
            val axis = if (random.nextBoolean()) Direction.Axis.X else Direction.Axis.Z
            val length = 3 + random.nextInt(3)
            var laid = 0
            for (i in 0 until length) {
                val px = if (axis == Direction.Axis.X) x + i else x
                val pz = if (axis == Direction.Axis.Z) z + i else z
                if (!patch.inside(px, pz) || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) != y) break
                if (!level.isEmptyBlock(pos.set(px, y, pz))) break
                laid++
            }
            if (laid < 2) return
            for (i in 0 until laid) {
                pos.set(if (axis == Direction.Axis.X) x + i else x, y, if (axis == Direction.Axis.Z) z + i else z)
                level.setBlock(pos, log.setValue(RotatedPillarBlock.AXIS, axis), FLAGS)
            }
            return
        }
        val height = 2 + random.nextInt(4)
        for (i in 0..height) if (!level.isEmptyBlock(pos.set(x, y + i, z))) return
        for (i in 0 until height) level.setBlock(pos.set(x, y + i, z), log, FLAGS)
        repeat(random.nextInt(3)) {
            val h = 1 + random.nextInt(height - 1)
            val dir = Direction.Plane.HORIZONTAL.getRandomDirection(random)
            val length = if (random.nextFloat() < 0.35f) 2 else 1
            for (j in 1..length) {
                pos.set(x + dir.stepX * j, y + h, z + dir.stepZ * j)
                if (!patch.inside(pos.x, pos.z) || !level.isEmptyBlock(pos)) break
                level.setBlock(pos, log.setValue(RotatedPillarBlock.AXIS, dir.axis), FLAGS)
            }
        }
    }

    /** Natural ground fire could have scorched: rock, clay, soil and sand. */
    private fun scorchable(state: BlockState): Boolean =
        state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.TERRACOTTA) || state.`is`(BlockTags.SUBSTRATE_OVERWORLD) ||
            state.`is`(BlockTags.SAND) || state.`is`(Blocks.GRAVEL)

    companion object {
        val CODEC: MapCodec<Ashfall> = MapCodec.unit(Ashfall())
        /** The chance each try at a burnt tree comes to one, and that one has fallen. */
        private const val TREE = 0.55f
        private const val FALLEN = 0.3f
        /** How hot (the heat noise, -1 to 1) ground must be for vents, and how fast they thicken past it. */
        private const val HOT = 0.38
        private const val VENTS = 0.06f
    }
}

/**
 * The frozen peaks: icicles hanging under the ledges and in the mouths of caves, a short one standing under a
 * long one now and then, and hoarfrost feathered over the stone of the cliffs and the caves.
 */
class Rime : Feature {
    override fun codec(): MapCodec<Rime> = CODEC

    override fun place(level: WorldGenLevel, chunkGenerator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (!RegionTerrain.enabled()) return false
        val patch = Patch(level, origin, RegionKind.FROZEN)
        if (!patch.any) return false
        val pos = BlockPos.MutableBlockPos()
        val frost = RegionTerrain.HOARFROST.get().defaultBlockState()
        for (dx in 0 until 16) for (dz in 0 until 16) {
            if (!patch.has(dx, dz)) continue
            val x = patch.minX + dx
            val z = patch.minZ + dz
            // From a little way up the cliffs beside this column, down under its ledges and into the mouths of its caves
            val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
            val from = min(top + REACH_UP, level.maxY - 1)
            val to = maxOf(top - REACH_DOWN, level.minY + 1)
            var above = level.getBlockState(pos.set(x, from + 1, z))
            for (y in from downTo to) {
                pos.set(x, y, z)
                val here = level.getBlockState(pos)
                if (here.isAir) {
                    if (holds(above) && above.isFaceSturdy(level, pos.above(), Direction.DOWN) && random.nextFloat() < ICICLES) {
                        hang(level, x, y, z, random)
                    } else if (y != top && random.nextFloat() < FROST) {
                        var state = frost
                        for (dir in Direction.values()) {
                            val wall = pos.relative(dir)
                            val wallState = level.getBlockState(wall)
                            if (!frosts(wallState) || !wallState.isFaceSturdy(level, wall, dir.opposite) || random.nextFloat() > 0.75f) continue
                            state = state.setValue(MultifaceBlock.getFaceProperty(dir), true)
                        }
                        if (state != frost) level.setBlock(pos, state, FLAGS)
                    }
                }
                above = level.getBlockState(pos)
            }
        }
        return true
    }

    /**
     * An icicle hanging from the roof over ([x], [y], [z]), as long as the room under it allows (it never quite
     * reaches the floor), and now and then a stub standing up under it.
     */
    private fun hang(level: WorldGenLevel, x: Int, y: Int, z: Int, random: RandomSource) {
        val pos = BlockPos.MutableBlockPos()
        var room = 0
        while (room < ROOM && level.isEmptyBlock(pos.set(x, y - room, z))) room++
        val pick = random.nextFloat()
        val wanted = if (pick < 0.35f) 1 else if (pick < 0.65f) 2 else if (pick < 0.87f) 3 else 4
        val length = min(wanted, room - 1)
        if (length < 1) return
        for (i in 0 until length) level.setBlock(pos.set(x, y - i, z), icicle(Direction.DOWN, piece(i, length)), FLAGS)
        // A stub standing up from the floor under it, if the floor's in reach
        val floorY = y - room
        if (room < ROOM && room - length >= 3 && random.nextFloat() < STUBS) {
            val floor = level.getBlockState(pos.set(x, floorY, z))
            if (holds(floor) && floor.isFaceSturdy(level, pos, Direction.UP)) {
                level.setBlock(pos.set(x, floorY + 1, z), icicle(Direction.UP, SpeleothemThickness.TIP), FLAGS)
            }
        }
    }

    /** Piece [i] of an icicle [length] long, counted from its root: base, middle, frustum, then the tip. */
    private fun piece(i: Int, length: Int): SpeleothemThickness = when (i) {
        length - 1 -> SpeleothemThickness.TIP
        length - 2 -> SpeleothemThickness.FRUSTUM
        0 -> SpeleothemThickness.BASE
        else -> SpeleothemThickness.MIDDLE
    }

    private fun icicle(tip: Direction, thickness: SpeleothemThickness): BlockState = RegionTerrain.ICICLE.get().defaultBlockState()
        .setValue(SpeleothemBlock.TIP_DIRECTION, tip).setValue(SpeleothemBlock.THICKNESS, thickness)

    /** Natural ground an icicle can hang from: rock, ice, packed snow, soil. */
    private fun holds(state: BlockState): Boolean = frosts(state) || state.`is`(Blocks.SNOW_BLOCK) || state.`is`(BlockTags.SUBSTRATE_OVERWORLD)

    /** Natural ground frost feathers over: rock and ice. */
    private fun frosts(state: BlockState): Boolean =
        state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(Blocks.ICE) || state.`is`(Blocks.PACKED_ICE) || state.`is`(Blocks.BLUE_ICE) ||
            state.`is`(Blocks.CALCITE) || state.`is`(Blocks.COBBLESTONE)

    companion object {
        val CODEC: MapCodec<Rime> = MapCodec.unit(Rime())
        /** How far above a column's top (up the cliffs beside it) and below it (under ledges, into caves) to look. */
        private const val REACH_UP = 16
        private const val REACH_DOWN = 24
        /** The most room under a roof looked for, in blocks. */
        private const val ROOM = 7
        /** Chances: an icicle under a roof, frost on an open spot by stone, a stub standing under an icicle. */
        private const val ICICLES = 0.2f
        private const val FROST = 0.1f
        private const val STUBS = 0.3f
    }
}

/**
 * The crystal deeps: glimmer clusters grown from the floors, roofs and walls of the caves, the rock around them
 * veined with glowing glimmerstone (here and there grown right through into crystal), and now and then a pillar
 * of glimmer crystal standing up from a cave floor.
 */
class CrystalGrowth : Feature {
    override fun codec(): MapCodec<CrystalGrowth> = CODEC

    override fun place(level: WorldGenLevel, chunkGenerator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (!RegionTerrain.enabled()) return false
        val chunk = ChunkPos.containing(origin)
        val minX = chunk.minBlockX
        val minZ = chunk.minBlockZ
        val cells = RegionCells.of(level.seed)
        val top = RegionCells.CRYSTAL_TOP
        // The crystal runs in great blots: a chunk none of whose corners or middle is in one has none worth the look
        val corners = listOf(0 to 0, 15 to 0, 0 to 15, 15 to 15, 8 to 8)
        if (corners.none { (dx, dz) -> cells.crystalAt(minX + dx, top, minZ + dz) }) return false
        val noise = TerrainNoise.of(level.seed)
        val pos = BlockPos.MutableBlockPos()
        val bottom = level.minY + 1
        val columns = ArrayList<Int>()
        for (dx in 0 until 16) for (dz in 0 until 16) {
            val x = minX + dx
            val z = minZ + dz
            if (!cells.crystalAt(x, top, z) || level.getBiome(pos.set(x, DEEP_DARK_LOOK, z)).`is`(Biomes.DEEP_DARK)) continue
            columns.add(dx * 16 + dz)
            var above = level.getBlockState(pos.set(x, top + 1, z))
            for (y in top downTo bottom) {
                pos.set(x, y, z)
                val here = level.getBlockState(pos)
                if (here.isAir) {
                    // A roof over it
                    val roof = pos.above()
                    if (hosts(above) && above.isFaceSturdy(level, roof, Direction.DOWN)) {
                        vein(level, roof, noise, random)
                        if (random.nextFloat() < ROOFS) level.setBlock(pos, cluster(random, Direction.DOWN), FLAGS)
                    }
                    // A wall beside it
                    if (level.getBlockState(pos).isAir) {
                        val dir = Direction.Plane.HORIZONTAL.getRandomDirection(random)
                        val wall = pos.relative(dir)
                        val wallState = level.getBlockState(wall)
                        if (hosts(wallState) && wallState.isFaceSturdy(level, wall, dir.opposite)) {
                            if (wall.x - minX in 0..15 && wall.z - minZ in 0..15) vein(level, wall, noise, random)
                            if (random.nextFloat() < WALLS) level.setBlock(pos, cluster(random, dir.opposite), FLAGS)
                        }
                    }
                } else if (above.isAir && hosts(here) && here.isFaceSturdy(level, pos, Direction.UP)) {
                    // A floor under open air
                    vein(level, pos.immutable(), noise, random)
                    if (random.nextFloat() < FLOORS) level.setBlock(pos.set(x, y + 1, z), cluster(random, Direction.UP), FLAGS)
                }
                above = level.getBlockState(pos.set(x, y, z))
            }
        }
        if (columns.isNotEmpty() && random.nextFloat() < PILLAR) {
            val pick = columns[random.nextInt(columns.size)]
            pillar(level, minX + pick / 16, minZ + pick % 16, minX, minZ, random)
        }
        return true
    }

    /** Where the veins run, the rock at [at] turned to glimmerstone (or, at their heart, to crystal). */
    private fun vein(level: WorldGenLevel, at: BlockPos, noise: TerrainNoise, random: RandomSource) {
        val v = noise.veins.at(at.x / 5.0 + at.y * 0.31, at.z / 5.0 - at.y * 0.17)
        if (v < VEINS) return
        val state = if (v > HEART && random.nextFloat() < 0.4f) RegionTerrain.GLIMMER_CRYSTAL.get() else RegionTerrain.GLIMMERSTONE.get()
        level.setBlock(at, state.defaultBlockState(), FLAGS)
    }

    /** A pillar of glimmer crystal on a cave floor in column ([x], [z]), crowned and studded with clusters. */
    private fun pillar(level: WorldGenLevel, x: Int, z: Int, minX: Int, minZ: Int, random: RandomSource) {
        val pos = BlockPos.MutableBlockPos()
        var air = 0
        for (y in RegionCells.CRYSTAL_TOP downTo level.minY + 1) {
            val state = level.getBlockState(pos.set(x, y, z))
            if (state.isAir) {
                air++
                continue
            }
            if (air >= 6 && hosts(state) && state.isFaceSturdy(level, pos, Direction.UP)) {
                val height = 2 + random.nextInt(4)
                val crystal = RegionTerrain.GLIMMER_CRYSTAL.get().defaultBlockState()
                for (i in 1..height) level.setBlock(pos.set(x, y + i, z), crystal, FLAGS)
                level.setBlock(pos.set(x, y + height + 1, z), RegionTerrain.LARGE_GLIMMER_CLUSTER.get().defaultBlockState(), FLAGS)
                for (i in 1..height) for (dir in Direction.Plane.HORIZONTAL) {
                    pos.set(x + dir.stepX, y + i, z + dir.stepZ)
                    if (pos.x - minX !in 0..15 || pos.z - minZ !in 0..15 || !level.isEmptyBlock(pos) || random.nextFloat() > 0.3f) continue
                    level.setBlock(pos, cluster(random, dir), FLAGS)
                }
                return
            }
            air = 0
        }
    }

    /** A cluster growing toward [facing], small, middling or large. */
    private fun cluster(random: RandomSource, facing: Direction): BlockState {
        val pick = random.nextFloat()
        val block = if (pick < 0.45f) RegionTerrain.SMALL_GLIMMER_CLUSTER.get() else if (pick < 0.8f) RegionTerrain.GLIMMER_CLUSTER.get()
        else RegionTerrain.LARGE_GLIMMER_CLUSTER.get()
        return block.defaultBlockState().setValue(AmethystClusterBlock.FACING, facing)
    }

    /** The deep caves' natural rock, that crystal grows from. */
    private fun hosts(state: BlockState): Boolean =
        state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(Blocks.CALCITE) || state.`is`(Blocks.SMOOTH_BASALT) ||
            state.`is`(RegionTerrain.GLIMMERSTONE.get())

    companion object {
        val CODEC: MapCodec<CrystalGrowth> = MapCodec.unit(CrystalGrowth())
        /** The height the deep dark is looked for at: no crystal grows through its sculk. */
        private const val DEEP_DARK_LOOK = -40
        /** Chances of a cluster on a floor, a roof and a wall, and of a pillar in a chunk. */
        private const val FLOORS = 0.1f
        private const val ROOFS = 0.07f
        private const val WALLS = 0.05f
        private const val PILLAR = 0.35f
        /** Where the vein noise (-1 to 1) turns rock to glimmerstone, and to crystal. */
        private const val VEINS = 0.6
        private const val HEART = 0.85
    }
}
