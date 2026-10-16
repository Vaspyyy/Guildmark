package io.github.vaspyyy.guildmark.outlaw

import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.Cushion
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity
import net.minecraft.world.entity.raid.Raid
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.entity.BannerBlockEntity
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.RotationSegment
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * An outlaw band's camp in the wild: a trodden clearing round a fire ringed with cushioned stools, the captain's big
 * tent at the back with the band's strongbox, an ominous banner at its mouth and the captain's own seat before it,
 * pup tents for the band, a lookout tower over the way in, horses tethered at a rail and stolen goods heaped by the
 * tent. Everything stands at fixed places round the fire, turned by the way the camp faces (from the fire to the
 * captain's tent), so [layout] can say where everyone belongs without anything being saved.
 */
object OutlawCamp {
    /** How far the clearing reaches from the fire. */
    const val RADIUS = 10
    /** How far anything of the camp reaches from the fire. */
    const val REACH = RADIUS + 2

    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    /** Where everyone in the camp belongs (see [layout]). */
    class Layout(
        val fire: BlockPos,
        /** The stools round the fire: the block each cushion sits on. */
        val seats: List<BlockPos>,
        /** The captain's own seat, before the big tent. */
        val chair: BlockPos,
        /** The beds' heads: the captain's first, then the pup tents'. */
        val beds: List<BlockPos>,
        /** Where the lookout stands, up on the tower. */
        val lookout: BlockPos,
        /** The sentries' posts either side of the way in. */
        val posts: List<BlockPos>,
        /** Just outside the mouth of each pup tent, where the band's men come out from. */
        val doors: List<BlockPos>,
        /** Where one of the band might go to do something by day: the stew pot, the loot, the horses, the tower, the road. */
        val chores: List<BlockPos>,
    )

    /** The camp round [fire] facing [facing], [a] blocks to the right of the fire and [b] towards the captain's tent. */
    private fun at(fire: BlockPos, facing: Direction, a: Int, b: Int, y: Int = 0): BlockPos =
        fire.relative(facing.clockWise, a).relative(facing, b).above(y)

    private val SEATS = listOf(-3 to -1, -3 to 1, 3 to -1, 3 to 1, -1 to -3, 1 to -3)

    fun layout(fire: BlockPos, facing: Direction): Layout = Layout(
        fire = fire,
        seats = SEATS.map { (a, b) -> at(fire, facing, a, b) },
        chair = at(fire, facing, 0, 4),
        beds = listOf(at(fire, facing, 1, 9), at(fire, facing, -6, 0), at(fire, facing, 6, 0), at(fire, facing, 5, 7)),
        lookout = at(fire, facing, 6, -6, 5),
        posts = listOf(at(fire, facing, -2, -8), at(fire, facing, 2, -8)),
        doors = listOf(at(fire, facing, -4, 0), at(fire, facing, 4, 0), at(fire, facing, 5, 5)),
        chores = listOf(at(fire, facing, 2, 2), at(fire, facing, -3, 7), at(fire, facing, -6, -4), at(fire, facing, 4, -6), at(fire, facing, 0, -6)),
    )

    /** Builds the camp round [fire] (the open block at ground level), its captain's tent on the [facing] side. */
    fun build(level: ServerLevel, fire: BlockPos, facing: Direction, random: RandomSource) {
        clear(level, fire, random)
        val right = facing.clockWise

        // The fire: a gravel hearth, stones at its corners, the band's stew pot by it
        put(level, fire.below(), Blocks.GRAVEL.defaultBlockState())
        level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), FLAGS)
        for (d in Direction.Plane.HORIZONTAL) {
            put(level, fire.relative(d).below(), Blocks.GRAVEL.defaultBlockState())
            set(level, fire.relative(d).relative(d.clockWise), pick(random, Blocks.COBBLESTONE to 2, Blocks.MOSSY_COBBLESTONE to 1, Blocks.ANDESITE to 1))
        }
        set(level, at(fire, facing, 1, 2), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 2))

        // Stools round the fire with a cushion on each, and the captain's seat before the tent
        val colours = listOf(DyeColor.BROWN, DyeColor.GRAY, DyeColor.GREEN, DyeColor.BLACK, DyeColor.LIGHT_GRAY)
        for ((a, b) in SEATS) stool(level, at(fire, facing, a, b), colours[random.nextInt(colours.size)])
        stool(level, at(fire, facing, 0, 4), DyeColor.RED)

        // The captain's tent: patched canvas, a red rug, the strongbox, a barrel table, a hanging lantern
        val canvas = Blocks.WOOL.pick(if (random.nextBoolean()) DyeColor.BROWN else DyeColor.LIGHT_GRAY).defaultBlockState()
        val patch = Blocks.WOOL.pick(if (random.nextBoolean()) DyeColor.WHITE else DyeColor.GRAY).defaultBlockState()
        tent(level, at(fire, facing, 0, 6), facing, 2, 5, canvas, patch, random)
        // Rugs either side of the way in (the middle is left bare: it's only just high enough to walk in there)
        for ((a, b) in listOf(-1 to 6, 1 to 6, -1 to 7, 1 to 7, -1 to 8)) set(level, at(fire, facing, a, b), Blocks.CARPET.pick(DyeColor.RED).defaultBlockState())
        bed(level, at(fire, facing, 1, 9), facing, DyeColor.RED)
        val strongbox = at(fire, facing, 0, 9)
        level.setBlock(strongbox, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing.opposite), FLAGS)
        RandomizableContainer.setBlockEntityLootTable(level, random, strongbox, BuiltInLootTables.PILLAGER_OUTPOST)
        set(level, at(fire, facing, -1, 9), Blocks.BARREL.defaultBlockState())
        (level.getBlockEntity(at(fire, facing, -1, 9)) as? BarrelBlockEntity)?.setItem(random.nextInt(27), ItemStack(ModItems.GUILD_MARK.get(), 4 + random.nextInt(8)))
        level.setBlock(at(fire, facing, 0, 8, 1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), FLAGS)
        banner(level, at(fire, facing, -3, 5), facing.opposite)

        // Pup tents for the band, each with a bedroll, their mouths to the fire
        val bedding = listOf(DyeColor.BROWN, DyeColor.GRAY, DyeColor.GREEN, DyeColor.BLACK)
        tent(level, at(fire, facing, -5, 0), right.opposite, 1, 3, canvas, patch, random)
        bed(level, at(fire, facing, -6, 0), right.opposite, bedding[random.nextInt(bedding.size)])
        tent(level, at(fire, facing, 5, 0), right, 1, 3, canvas, patch, random)
        bed(level, at(fire, facing, 6, 0), right, bedding[random.nextInt(bedding.size)])
        tent(level, at(fire, facing, 5, 6), facing, 1, 3, canvas, patch, random)
        bed(level, at(fire, facing, 5, 7), facing, bedding[random.nextInt(bedding.size)])

        // What they've taken from the road, heaped by the captain's tent
        for ((pos, table) in listOf(at(fire, facing, -4, 6) to BuiltInLootTables.VILLAGE_PLAINS_HOUSE, at(fire, facing, -4, 7) to BuiltInLootTables.VILLAGE_BUTCHER)) {
            set(level, pos, Blocks.BARREL.defaultBlockState())
            RandomizableContainer.setBlockEntityLootTable(level, random, pos, table)
        }
        set(level, at(fire, facing, -5, 6), Blocks.HAY_BLOCK.defaultBlockState())
        set(level, at(fire, facing, -4, 6, 1), Blocks.BARREL.defaultBlockState())

        // The lookout tower over the way in: log posts, a plank platform, a rail round it and a ladder up
        for ((a, b) in listOf(5 to -7, 7 to -7, 5 to -5, 7 to -5)) {
            for (y in 0..3) set(level, at(fire, facing, a, b, y), Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y))
        }
        for (a in 5..7) for (b in -7..-5) {
            level.setBlock(at(fire, facing, a, b, 4), Blocks.SPRUCE_PLANKS.defaultBlockState(), FLAGS)
            val edge = a != 6 || b != -6
            if (edge && !(a == 5 && b == -5)) set(level, at(fire, facing, a, b, 5), Blocks.SPRUCE_FENCE.defaultBlockState())
        }
        set(level, at(fire, facing, 7, -7, 6), Blocks.LANTERN.defaultBlockState())
        for (y in 0..4) set(level, at(fire, facing, 4, -5, y), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, right.opposite))

        // The way in: a beaten path to the fire between two lantern posts
        for (b in -10..-4) {
            val ground = at(fire, facing, 0, b, -1)
            if (level.getBlockState(ground).`is`(BlockTags.SUBSTRATE_OVERWORLD) && level.getBlockState(ground.above()).isAir) level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), FLAGS)
        }
        for (a in intArrayOf(-2, 2)) {
            set(level, at(fire, facing, a, -9), Blocks.SPRUCE_FENCE.defaultBlockState())
            set(level, at(fire, facing, a, -9, 1), Blocks.LANTERN.defaultBlockState())
        }

        // Horses at a rail on the near side, with hay and a trough
        for (a in -7..-5) set(level, at(fire, facing, a, -5), Blocks.SPRUCE_FENCE.defaultBlockState())
        set(level, at(fire, facing, -8, -5), Blocks.HAY_BLOCK.defaultBlockState())
        set(level, at(fire, facing, -4, -5), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3))
        for ((a, post) in listOf(-7 to at(fire, facing, -7, -5), -5 to at(fire, facing, -5, -5))) {
            if (random.nextInt(3) == 0 && a == -5) continue
            val horse = EntityTypes.HORSE.create(level, EntitySpawnReason.STRUCTURE) ?: continue
            val spot = at(fire, facing, a, -7)
            horse.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, facing.toYRot(), 0f)
            horse.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.STRUCTURE, null)
            // Broken in and saddled: whoever breaks the band can ride off on its horses
            horse.setTamed(true)
            horse.setItemSlot(EquipmentSlot.SADDLE, ItemStack(Items.SADDLE))
            horse.setPersistenceRequired()
            level.addFreshEntity(horse)
            horse.setLeashedTo(LeashFenceKnotEntity.getOrCreateKnot(level, post), true)
        }
    }

    /** The clearing: plants, bushes and low boughs gone, hollows filled, the ground trodden bare in places. */
    private fun clear(level: ServerLevel, fire: BlockPos, random: RandomSource) {
        for (dx in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            val d = sqrt((dx * dx + dz * dz).toDouble())
            if (d > RADIUS + 0.5) continue
            val edge = d > RADIUS - 1.5
            if (edge && random.nextInt(3) == 0) continue
            for (h in 0..6) {
                val pos = fire.offset(dx, h, dz)
                val state = level.getBlockState(pos)
                if (state.isAir || !state.fluidState.isEmpty) continue
                if (state.canBeReplaced() || state.`is`(BlockTags.LEAVES) || state.block is VegetationBlock || (h <= 4 && natural(state))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
            }
            val ground = fire.offset(dx, -1, dz)
            var down = ground
            while (down.y > ground.y - 3 && (level.getBlockState(down).isAir || level.getBlockState(down).canBeReplaced())) {
                level.setBlock(down, Blocks.DIRT.defaultBlockState(), FLAGS)
                down = down.below()
            }
            if (natural(level.getBlockState(ground)) && random.nextInt(if (edge) 4 else 3) == 0) {
                put(level, ground, pick(random, Blocks.COARSE_DIRT to 4, Blocks.PODZOL to 1, Blocks.ROOTED_DIRT to 1))
            }
        }
    }

    /**
     * A tent of canvas over a ridge, its mouth at [front] (the middle of its front row, at ground level) and running
     * [depth] blocks [forward]; [half] is how far each slope reaches out from the ridge, which stands [half] high. The
     * mouth is open, the back closed, and a pole stands up from the ridge at each end.
     */
    private fun tent(level: ServerLevel, front: BlockPos, forward: Direction, half: Int, depth: Int, canvas: BlockState, patch: BlockState, random: RandomSource) {
        val side = forward.clockWise
        for (b in 0 until depth) for (a in -half..half) {
            val column = front.relative(side, a).relative(forward, b)
            val roof = half - abs(a)
            support(level, column)
            val back = b == depth - 1
            // Set straight in, not on [set]'s filled-in ground: the tent stands over open air
            for (y in 0 until roof) level.setBlock(column.above(y), if (back) cloth(canvas, patch, random) else Blocks.AIR.defaultBlockState(), FLAGS)
            level.setBlock(column.above(roof), cloth(canvas, patch, random), FLAGS)
        }
        for (b in intArrayOf(0, depth - 1)) level.setBlock(front.relative(forward, b).above(half + 1), Blocks.SPRUCE_FENCE.defaultBlockState(), FLAGS)
    }

    private fun cloth(canvas: BlockState, patch: BlockState, random: RandomSource): BlockState = if (random.nextInt(5) == 0) patch else canvas

    /** A bed with its head at [head], running towards [facing]. */
    private fun bed(level: ServerLevel, head: BlockPos, facing: Direction, colour: DyeColor) {
        val state = Blocks.BED.pick(colour).defaultBlockState().setValue(BedBlock.FACING, facing)
        val foot = head.relative(facing.opposite)
        support(level, head)
        support(level, foot)
        level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), FLAGS)
        level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), FLAGS)
    }

    /** A stool: a spruce slab with a cushion on it. */
    private fun stool(level: ServerLevel, pos: BlockPos, colour: DyeColor) {
        set(level, pos, Blocks.SPRUCE_SLAB.defaultBlockState())
        val cushion = EntityTypes.CUSHION.create(level, EntitySpawnReason.STRUCTURE) ?: return
        cushion.setColor(colour)
        cushion.snapTo(Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5), 0f, 0f)
        level.addFreshEntity(cushion)
    }

    /** The cushion on the stool at [pos], if it's still there. */
    fun cushion(level: ServerLevel, pos: BlockPos): Cushion? =
        level.getEntitiesOfClass(Cushion::class.java, AABB(pos).inflate(0.2, 0.6, 0.2)).firstOrNull()

    /** The band's ominous banner, standing at [pos] and facing [toward]. */
    private fun banner(level: ServerLevel, pos: BlockPos, toward: Direction) {
        support(level, pos)
        val state = Blocks.BANNER.pick(DyeColor.WHITE).defaultBlockState().setValue(BannerBlock.ROTATION, RotationSegment.convertToSegment(toward))
        level.setBlock(pos, state, FLAGS)
        val banner = level.getBlockEntity(pos) as? BannerBlockEntity ?: return
        banner.applyComponentsFromItemStack(Raid.getOminousBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)))
        banner.setChanged()
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
    }

    /** [state] set at [pos], on firm ground: any hollow under it is filled first. */
    private fun set(level: ServerLevel, pos: BlockPos, state: BlockState) {
        support(level, pos)
        level.setBlock(pos, state, FLAGS)
    }

    /** Fills any hollow up to three deep under [pos] with earth, so what stands there stands on the ground. */
    private fun support(level: ServerLevel, pos: BlockPos) {
        var down = pos.below()
        while (down.y >= pos.y - 3 && level.getBlockState(down).canBeReplaced()) {
            level.setBlock(down, Blocks.DIRT.defaultBlockState(), FLAGS)
            down = down.below()
        }
    }

    /** Ground laid at [pos]; gravel over a hollow would drop into it, so there it's cobblestone instead. */
    private fun put(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val steady = if (state.block is FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) Blocks.COBBLESTONE.defaultBlockState() else state
        level.setBlock(pos, steady, FLAGS)
    }

    /** Ground and plants that may be cleared or trodden over: never anything a player built. */
    private fun natural(state: BlockState): Boolean =
        state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.SAND) ||
            state.`is`(Blocks.GRAVEL) || state.`is`(BlockTags.SNOW) || state.`is`(Blocks.CLAY)

    private fun pick(random: RandomSource, vararg options: Pair<Block, Int>): BlockState {
        var roll = random.nextInt(options.sumOf { it.second })
        for ((block, weight) in options) {
            roll -= weight
            if (roll < 0) return block.defaultBlockState()
        }
        return options[0].first.defaultBlockState()
    }
}
