package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FarmlandBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType

/**
 * A small square to try the townsfolk out in (`/guildmark townsfolk demo`): a smithy, a bakery, a market
 * stall, a farm plot, a fishing pond, a chapel, a tavern with a bar and tables, a row of beds and benches round
 * a fountain, with one of each trade living there. It's marked as a town, so passers-by gather too.
 */
object DemoYard {
    /** How far the square reaches from its middle. */
    const val REACH = 20

    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    /** Builds the square round [origin] (the ground the player stands on) and peoples it; returns how many came. */
    fun build(level: ServerLevel, origin: BlockPos): Int {
        val y = origin.y
        val ox = origin.x
        val oz = origin.z
        fun at(x: Int, dy: Int, z: Int): BlockPos = BlockPos(ox + x, y + dy, oz + z)
        fun set(x: Int, dy: Int, z: Int, state: BlockState) {
            level.setBlock(at(x, dy, z), state, FLAGS)
        }
        fun fill(x0: Int, dy0: Int, z0: Int, x1: Int, dy1: Int, z1: Int, state: BlockState) {
            for (x in minOf(x0, x1)..maxOf(x0, x1)) for (dy in minOf(dy0, dy1)..maxOf(dy0, dy1)) for (z in minOf(z0, z1)..maxOf(z0, z1)) set(x, dy, z, state)
        }
        fun stairs(x: Int, z: Int, back: Direction) = set(x, 0, z, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, back))
        fun post(x: Int, z: Int, height: Int, lantern: Boolean) {
            fill(x, 0, z, x, height - 1, z, Blocks.OAK_FENCE.defaultBlockState())
            if (lantern) set(x, height, z, Blocks.LANTERN.defaultBlockState())
        }
        fun roof(x0: Int, z0: Int, x1: Int, z1: Int, dy: Int) {
            fill(x0, dy, z0, x1, dy, z1, Blocks.SPRUCE_SLAB.defaultBlockState())
            for ((x, z) in listOf(x0 to z0, x0 to z1, x1 to z0, x1 to z1)) fill(x, 0, z, x, dy - 1, z, Blocks.SPRUCE_FENCE.defaultBlockState())
        }

        // Clear the ground and lay the square
        val air = Blocks.AIR.defaultBlockState()
        fill(-REACH, 0, -REACH, REACH, 8, REACH, air)
        fill(-REACH, -3, -REACH, REACH, -2, REACH, Blocks.DIRT.defaultBlockState())
        for (x in -REACH..REACH) for (z in -REACH..REACH) {
            val edge = maxOf(kotlin.math.abs(x), kotlin.math.abs(z)) == REACH
            set(x, -1, z, if (edge) Blocks.POLISHED_ANDESITE.defaultBlockState() else if ((x + z) % 5 == 0) Blocks.COBBLESTONE.defaultBlockState() else Blocks.STONE_BRICKS.defaultBlockState())
        }

        // The fountain in the middle, with benches round it and lamps at the corners
        fill(-1, -1, -1, 1, -1, 1, Blocks.WATER.defaultBlockState())
        fill(-1, -2, -1, 1, -2, 1, Blocks.STONE_BRICKS.defaultBlockState())
        for (x in -2..2) for (z in -2..2) if (kotlin.math.abs(x) == 2 || kotlin.math.abs(z) == 2) set(x, 0, z, Blocks.STONE_BRICK_SLAB.defaultBlockState())
        stairs(-5, 0, Direction.WEST)
        stairs(5, -2, Direction.EAST)
        stairs(0, -5, Direction.NORTH)
        stairs(-2, 5, Direction.SOUTH)
        for ((x, z) in listOf(-7 to -7, 7 to -7, -7 to 7, 7 to 7)) post(x, z, 3, lantern = true)

        // The smithy: anvil, forge, quenching cauldron and grindstone under a roof
        roof(-17, -17, -9, -9, 3)
        set(-13, 0, -13, Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.EAST))
        set(-16, 0, -13, Blocks.BLAST_FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH))
        set(-10, 0, -13, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3))
        set(-13, 0, -16, Blocks.GRINDSTONE.defaultBlockState())
        set(-16, 0, -16, Blocks.SMITHING_TABLE.defaultBlockState())

        // The bakery: an oven, a kneading table and flour barrels
        roof(9, -17, 17, -9, 3)
        set(13, 0, -15, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH))
        set(10, 0, -15, Blocks.CRAFTING_TABLE.defaultBlockState())
        set(16, 0, -15, Blocks.BARREL.defaultBlockState())
        set(16, 1, -15, Blocks.BARREL.defaultBlockState())

        // A market stall on the east side, the trader behind the counter facing the square
        val counter = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
        set(13, 0, -1, counter)
        set(13, 0, 0, Blocks.BARREL.defaultBlockState())
        set(13, 0, 1, counter)
        roof(12, -2, 15, 2, 3)
        fill(12, 3, -2, 15, 3, 2, Blocks.WOOL.red().defaultBlockState())

        // A farm plot by the west edge with a water channel and a composter
        for (x in -19..-14) for (z in -4..4) {
            if (x == -16) {
                set(x, -1, z, Blocks.WATER.defaultBlockState())
            } else {
                set(x, -1, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7))
                set(x, 0, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, level.random.nextInt(8)))
            }
        }
        set(-12, 0, 0, Blocks.COMPOSTER.defaultBlockState())

        // A fishing pond by the south edge, two deep
        fill(-5, -3, 13, 5, -1, 19, Blocks.STONE_BRICKS.defaultBlockState())
        fill(-4, -2, 14, 4, -1, 18, Blocks.WATER.defaultBlockState())

        // The chapel corner: a lectern and two rows of pews facing it
        set(-13, 0, 16, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH))
        for (row in listOf(13, 11)) for (x in listOf(-16, -15, -11, -10)) stairs(x, row, Direction.NORTH)
        post(-17, 17, 2, lantern = true)
        post(-9, 17, 2, lantern = true)

        // The tavern: four walls, a door, a bar, tables with chairs and a lamp
        val planks = Blocks.SPRUCE_PLANKS.defaultBlockState()
        for (x in 8..18) for (z in 8..18) {
            if (x == 8 || x == 18 || z == 8 || z == 18) fill(x, 0, z, x, 2, z, planks)
        }
        fill(8, 3, 8, 18, 3, 18, planks)
        for (x in listOf(10, 16)) set(x, 1, 8, Blocks.GLASS_PANE.defaultBlockState())
        val door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH)
        set(13, 0, 8, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER))
        set(13, 1, 8, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        for (x in 11..15) set(x, 0, 15, if (x == 13) Blocks.BARREL.defaultBlockState() else counter)
        set(10, 0, 17, Blocks.BARREL.defaultBlockState())
        set(16, 0, 17, Blocks.BARREL.defaultBlockState())
        for ((tx, tz) in listOf(10 to 11, 15 to 11)) {
            set(tx, 0, tz, Blocks.OAK_FENCE.defaultBlockState())
            set(tx, 1, tz, Blocks.OAK_PRESSURE_PLATE.defaultBlockState())
            // Chairs on three sides, backs away from the table
            stairs(tx - 1, tz, Direction.WEST)
            stairs(tx + 1, tz, Direction.EAST)
            stairs(tx, tz + 1, Direction.SOUTH)
        }
        set(13, 2, 12, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))

        // A row of beds along the north edge, under a roof
        roof(-9, -20, 9, -16, 3)
        for (x in -7..7 step 2) {
            val bed = Blocks.BED.blue().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
            set(x, 0, -19, bed.setValue(BedBlock.PART, BedPart.HEAD))
            set(x, 0, -18, bed.setValue(BedBlock.PART, BedPart.FOOT))
        }

        // A guard's post at the south entrance
        post(-2, REACH - 1, 3, lantern = true)
        post(2, REACH - 1, 3, lantern = true)

        // The folk who live here
        val town = Town(origin, REACH + 2, origin)
        Townsfolk.registerTown(level, origin, REACH + 2)
        val tavern = at(13, 0, 12)
        var count = 0
        fun live(role: TownRole, x: Int, z: Int, home: Int, work: BlockPos? = null, post: BlockPos? = null, facing: Direction? = null): Townsperson? {
            val person = Townsfolk.spawn(level, at(x, 0, z), role, work = work, home = at(home, 0, -17), post = post, facing = facing, evening = tavern, town = town)
            if (person != null) count++
            return person
        }
        live(TownRole.SMITH, -13, -11, -7, work = at(-13, 0, -13))
        live(TownRole.BAKER, 13, -13, -5, work = at(13, 0, -15))
        live(TownRole.MERCHANT, 14, 0, -3, post = at(14, 0, 0), facing = Direction.WEST)
        live(TownRole.GUARD, 0, REACH - 1, -1, post = at(0, 0, REACH - 1), facing = Direction.SOUTH)?.nightWatch = false
        live(TownRole.GUARD, 0, 10, 1)?.nightWatch = true
        live(TownRole.FARMER, -12, 2, 3, work = at(-12, 0, 0))
        live(TownRole.FISHER, 0, 12, 5, post = at(0, 0, 13), facing = Direction.SOUTH)
        live(TownRole.CHILD, 3, 4, 7)
        live(TownRole.CHILD, -3, 4, 7)
        live(TownRole.NOBLE, 4, -6, -7)
        live(TownRole.INNKEEPER, 13, 16, -5, post = at(13, 0, 16), facing = Direction.NORTH)
        live(TownRole.BARD, 4, 0, -3, post = at(4, 0, 0), facing = Direction.WEST)
        live(TownRole.PRIEST, -13, 14, 3, work = at(-13, 0, 16))
        set(-8, 0, -12, Blocks.LOOM.defaultBlockState())
        live(TownRole.CITIZEN, -8, -10, 5, work = at(-8, 0, -12))
        live(TownRole.CITIZEN, 6, 6, 7)
        return count
    }

    /** Spawns one resident of [role] at [pos] for testing, living there. */
    fun one(level: ServerLevel, pos: BlockPos, role: TownRole, look: Int): Townsperson? {
        val person = Townsfolk.create(level, pos, role, look, reason = EntitySpawnReason.COMMAND) ?: return null
        person.home = pos
        if (role == TownRole.MERCHANT || role == TownRole.INNKEEPER) {
            person.post = pos
            person.postFacing = Direction.fromYRot(person.yRot.toDouble())
        }
        person.setPersistenceRequired()
        level.addFreshEntity(person)
        return person
    }
}
