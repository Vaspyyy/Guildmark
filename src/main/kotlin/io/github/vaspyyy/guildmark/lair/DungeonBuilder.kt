package io.github.vaspyyy.guildmark.lair

import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CaveVines
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.DecoratedPotBlock
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.MultifaceBlock
import net.minecraft.world.level.block.RailBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.SpeleothemBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.RailShape
import net.minecraft.world.level.block.state.properties.RotationSegment
import net.minecraft.world.level.block.state.properties.SpeleothemThickness
import net.minecraft.world.level.storage.loot.LootTable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Digs a dungeon out of the rock a room at a time, as players come near: the stairwell and entrance hall
 * first, then each room with the corridor back to the room before it. Rooms are dressed for the lair's
 * [DungeonStyle], treasure rooms get their chests, and the boss hall its columns, lights and mist gate.
 */
class DungeonBuilder(private val level: ServerLevel, private val layout: DungeonLayout, private val lair: Lair) {
    private val style: DungeonStyle = lair.theme.style ?: DungeonStyle.CRYPT
    private val plan = layout.plan
    private val random: RandomSource = level.random
    private val major = lair.shard >= 0

    /** Fences and walls placed this build, to join up to their neighbours once everything stands. */
    private val joins = ArrayList<BlockPos>()

    /** The entrance: its hall, the stairwell down from the surface, the marker over it and a cold Wardfire. */
    fun buildEntrance() {
        shell(0)
        stairwell()
        surface()
        dress(0)
        wardfire()
        join()
    }

    /** One more room, with the corridor that leads to it from the room before it. */
    fun buildRoom(index: Int) {
        shell(index)
        layout.linkTo(index)?.let { corridor(it) }
        if (plan.rooms[index].kind == RoomKind.TREASURE) treasure(index)
        dress(index)
        join()
    }

    // ---- Placing blocks ----

    private fun set(pos: BlockPos, state: BlockState) {
        if (level.isOutsideBuildHeight(pos)) return
        // Sand or gravel over a hollow would drop into it, and whatever it held up would follow
        val placed = if (state.block is FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) steady(state) else state
        level.setBlock(pos, placed, FLAGS)
        if (placed.block is CrossCollisionBlock || placed.block is WallBlock) joins.add(pos.immutable())
    }

    /** Something that stays put, in place of a block that would fall. */
    private fun steady(state: BlockState): BlockState = when {
        state.`is`(Blocks.SAND) -> Blocks.SMOOTH_SANDSTONE.defaultBlockState()
        state.`is`(Blocks.RED_SAND) -> Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState()
        else -> Blocks.COBBLESTONE.defaultBlockState()
    }

    private fun at(room: PlanRoom, du: Int, dv: Int, y: Int): BlockPos = layout.pos(room.u + du, room.v + dv, y)

    private fun air(pos: BlockPos): Boolean = level.getBlockState(pos).isAir

    /** Fences and walls went in without neighbour updates; join each to whatever stands beside it. */
    private fun join() {
        for (pos in joins) {
            val state = level.getBlockState(pos)
            val joined = Block.updateFromNeighbourShapes(state, level, pos)
            if (joined != state) level.setBlock(pos, joined, FLAGS)
        }
        joins.clear()
    }

    /** Rock that can stay as a cave wall: solid, dry and not about to fall. */
    private fun sound(pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.isAir && state.fluidState.isEmpty && state.block !is FallingBlock && !state.canBeReplaced() &&
            state.isCollisionShapeFullBlock(level, pos)
    }

    /** Within the entrance hall's stairwell, where nothing may hang, cling or pool in the way of the steps. */
    private fun stairs(index: Int, du: Int, dv: Int): Boolean = index == 0 && abs(du) <= 3 && abs(dv) <= 3

    /** Somewhere a thing can stand: two blocks of air over a firm floor. */
    private fun standable(pos: BlockPos): Boolean =
        air(pos) && air(pos.above()) && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)

    // ---- Rooms ----

    /** A built room's floor, walls and ceiling round open air; a cave room is hollowed out instead. */
    private fun shell(index: Int) {
        if (style.organic) {
            hollow(index)
            return
        }
        val room = plan.rooms[index]
        val f = layout.floor(index)
        for (du in -room.halfU - 1..room.halfU + 1) for (dv in -room.halfV - 1..room.halfV + 1) {
            val edge = abs(du) > room.halfU || abs(dv) > room.halfV
            for (y in f - 1..f + room.height) set(at(room, du, dv, y), when {
                y == f + room.height -> style.ceiling(random, y)
                edge -> style.wall(random, y)
                y == f - 1 -> style.floor(random, y)
                else -> AIR
            })
        }
    }

    /**
     * A cave room: a lumpy dome hollowed from the rock. The rock round it stays where it's sound; where it's
     * loose, wet or open to another cave it's patched with the style's wall, and the ground is laid fresh.
     */
    private fun hollow(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val su = room.halfU + 1
        val sv = room.halfV + 1
        val depth = 2 * sv + 1
        val height = room.height + 2
        val open = BooleanArray((2 * su + 1) * depth * height)
        fun slot(du: Int, dv: Int, y: Int) = ((du + su) * depth + (dv + sv)) * height + (y - f + 1)
        for (du in -su..su) for (dv in -sv..sv) for (y in f - 1..f + room.height) open[slot(du, dv, y)] = open(index, du, dv, y)
        fun isOpen(du: Int, dv: Int, y: Int) = du in -su..su && dv in -sv..sv && y in f - 1..f + room.height && open[slot(du, dv, y)]

        for (du in -su..su) for (dv in -sv..sv) for (y in f - 1..f + room.height) {
            val pos = at(room, du, dv, y)
            when {
                isOpen(du, dv, y) -> set(pos, AIR)
                isOpen(du, dv, y + 1) -> set(pos, style.floor(random, y))
                (isOpen(du, dv, y - 1) || isOpen(du + 1, dv, y) || isOpen(du - 1, dv, y) || isOpen(du, dv + 1, y) || isOpen(du, dv - 1, y)) &&
                    !sound(pos) -> set(pos, style.wall(random, y))
            }
        }
    }

    /** Whether a cave room is open at [du], [dv] from its centre and height [y]: a lumpy dome. */
    private fun open(index: Int, du: Int, dv: Int, y: Int): Boolean {
        val room = plan.rooms[index]
        val dy = y - layout.floor(index)
        if (abs(du) > room.halfU || abs(dv) > room.halfV || dy < 0 || dy >= room.height) return false
        val a = room.halfU + 0.7
        val b = room.halfV + 0.7
        val t = dy.toDouble() / room.height
        val r = (du / a) * (du / a) + (dv / b) * (dv / b) + t * t * t * 1.15
        return r + (noise(layout.state.seed + index * 7919L, du / 3.0, dv / 3.0, dy / 2.5) - 0.5) * 0.36 <= 1.0
    }

    /**
     * A corridor between two rooms: three wide and four high, with a flight of stairs where one room is
     * deeper than the other and a doorway into each. The way into the boss hall is closed by a mist gate.
     */
    private fun corridor(link: PlanLink) {
        val a = plan.rooms[link.a]
        val b = plan.rooms[link.b]
        val fa = layout.floor(link.a)
        val fb = layout.floor(link.b)
        val alongU = link.alongU
        val s = if (alongU) (b.u - a.u).sign else (b.v - a.v).sign
        val line = if (alongU) a.v else a.u
        val start = (if (alongU) a.u else a.v) + s * ((if (alongU) a.halfU else a.halfV) + 2)
        val run = plan.run(link)
        fun cell(k: Int, w: Int, y: Int): BlockPos =
            if (alongU) layout.pos(start + s * k, line + w, y) else layout.pos(line + w, start + s * k, y)
        fun across(w: Int): Direction = if (alongU) layout.dir(0, w) else layout.dir(w, 0)

        val high = max(fa, fb)
        val drop = abs(fa - fb)
        val towardA = if (alongU) layout.dir(-s, 0) else layout.dir(0, -s)
        // Stairs climb toward the higher room
        val stair = style.stairs.defaultBlockState().setValue(StairBlock.FACING, if (fa >= fb) towardA else towardA.opposite)
        for (k in 0 until run) {
            val fromHigh = if (fa >= fb) k else run - 1 - k
            val step = fromHigh in 1..drop
            val base = when {
                fromHigh == 0 -> high - 1
                step -> high - fromHigh
                else -> high - drop - 1
            }
            for (w in -2..2) {
                if (abs(w) == 2) {
                    for (y in base - 1..base + 5) set(cell(k, w, y), style.wall(random, y))
                    continue
                }
                set(cell(k, w, base - 1), style.wall(random, base - 1))
                set(cell(k, w, base), if (step) stair else style.floor(random, base))
                for (y in base + 1..base + 4) set(cell(k, w, y), AIR)
                set(cell(k, w, base + 5), style.ceiling(random, base + 5))
            }
            // A light now and then, on alternate walls
            if (k % 6 == 3) {
                val side = if ((k / 6) % 2 == 0) 2 else -2
                wallLight(cell(k, side, base + 3), across(-side.sign))
            }
        }

        // A doorway at each end; the way into the boss hall gets the mist gate instead
        val gate = link.b == layout.boss
        val gateState = ModBlocks.MIST_GATE.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, towardA.opposite)
        for (w in -1..1) {
            set(cell(-1, w, fa - 1), style.floor(random, fa - 1))
            for (y in fa..fa + 3) set(cell(-1, w, y), AIR)
            set(cell(run, w, fb - 1), style.floor(random, fb - 1))
            for (y in fb..fb + 3) set(cell(run, w, y), if (gate) gateState else AIR)
        }
        if (style.organic) {
            tunnel(link.a, alongU, start - s, -s, line)
            tunnel(link.b, alongU, start + s * run, s, line)
        }
    }

    /** In through a cave room's doorway until it meets the cave, so the corridor always opens into it. */
    private fun tunnel(index: Int, alongU: Boolean, wall: Int, inward: Int, line: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        for (t in 1..3) {
            val c = wall + inward * t
            for (w in -2..2) for (y in f - 1..f + 4) {
                val u = if (alongU) c else line + w
                val v = if (alongU) line + w else c
                if (open(index, u - room.u, v - room.v, y)) continue
                val pos = layout.pos(u, v, y)
                when {
                    abs(w) <= 1 && y in f..f + 3 -> set(pos, AIR)
                    y == f - 1 -> set(pos, style.floor(random, y))
                    !sound(pos) -> set(pos, style.wall(random, y))
                }
            }
        }
    }

    /** A light on a wall at [wall] (the wall block itself), shining out toward [out]. */
    private fun wallLight(wall: BlockPos, out: Direction) {
        val front = wall.relative(out)
        when (style) {
            DungeonStyle.CRYPT, DungeonStyle.PIT, DungeonStyle.RIME ->
                set(front, Blocks.SOUL_WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, out))
            DungeonStyle.MINE -> set(front, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, out))
            DungeonStyle.SHRINE -> set(wall, Blocks.SEA_LANTERN.defaultBlockState())
            DungeonStyle.GROTTO -> set(wall, Blocks.SHROOMLIGHT.defaultBlockState())
            DungeonStyle.CAVE -> set(front, lichen(out.opposite))
        }
    }

    // ---- The way in ----

    /** A spiral of steps round a solid core, from the surface down into the entrance hall. */
    private fun stairwell() {
        val room = plan.rooms[0]
        val f = layout.floor(0)
        val top = lair.pos.y
        val ceiling = f + room.height
        for (du in -3..3) for (dv in -3..3) for (y in ceiling until top) set(at(room, du, dv, y), style.wall(random, y))
        for (du in -1..1) for (dv in -1..1) for (y in f until ceiling) set(at(room, du, dv, y), style.pillar(random, y))
        for (i in 0 until top - f) {
            val (cu, cv) = RING[i % RING.size]
            // Each step faces the way the climb comes into it, so its low half is the side stepped onto, corners too
            val (nu, nv) = RING[(i + 1) % RING.size]
            val y = top - 1 - i
            set(at(room, cu, cv, y), style.stairs.defaultBlockState().setValue(StairBlock.FACING, layout.dir(cu - nu, cv - nv)))
            for (h in 1..3) set(at(room, cu, cv, y + h), AIR)
            // A light on the core now and then, where the core is right beside the step
            if (i % 8 == 5 && (abs(cu) != 2 || abs(cv) != 2)) {
                val du = if (abs(cu) == 2) cu.sign else 0
                val dv = if (abs(cv) == 2) cv.sign else 0
                wallLight(at(room, cu - du, cv - dv, y + 2), layout.dir(du, dv))
            }
        }
    }

    /**
     * The entrance hall's Wardfire, once the hall is dressed: in a far corner from the stairs (either side)
     * or the nearest spot to it on firm, clear ground, so columns, cave walls and clutter never bury it.
     */
    private fun wardfire() {
        val room = plan.rooms[0]
        val f = layout.floor(0)
        val cu = -(room.halfU - 1)
        val cv = (if (random.nextBoolean()) 1 else -1) * (room.halfV - 1)
        val near = ArrayList<Pair<Int, Int>>()
        for (du in -room.halfU..room.halfU) for (dv in -room.halfV..room.halfV) if (!stairs(0, du, dv)) near.add(du to dv)
        near.sortBy { (du, dv) -> (du - cu) * (du - cu) + (dv - cv) * (dv - cv) }
        val spot = near.map { (du, dv) -> at(room, du, dv, f) }.firstOrNull { standable(it) } ?: at(room, cu, cv, f).also {
            set(it.below(), style.floor(random, f - 1))
            set(it.above(), AIR)
        }
        set(spot, ModBlocks.WARDFIRE.get().defaultBlockState())
    }

    /** What marks the way down on the surface: a level yard round the stairwell and the style's marker. */
    private fun surface() {
        val room = plan.rooms[0]
        val top = lair.pos.y
        for (du in -5..5) for (dv in -5..5) {
            val ring = max(abs(du), abs(dv))
            for (y in top..top + 8) if (!air(at(room, du, dv, y))) set(at(room, du, dv, y), AIR)
            if (ring <= 3) continue
            if (ring == 5 && random.nextInt(3) == 0) continue
            set(at(room, du, dv, top - 1), style.floor(random, top - 1))
            // Footings, so the yard never hangs over a slope
            var y = top - 2
            while (y > top - 8 && !sound(at(room, du, dv, y))) {
                set(at(room, du, dv, y), style.wall(random, y))
                y--
            }
        }
        val core = at(room, 0, 0, top)
        when (style) {
            DungeonStyle.CRYPT -> {
                mausoleum(room, top)
                candles(core)
            }
            DungeonStyle.MINE -> headframe(room, top)
            DungeonStyle.SHRINE -> {
                colonnade(room, top)
                set(core, Blocks.SEA_LANTERN.defaultBlockState())
            }
            else -> {
                stones(room, top)
                when (style) {
                    DungeonStyle.PIT -> set(core, Blocks.SOUL_CAMPFIRE.defaultBlockState())
                    DungeonStyle.RIME -> {
                        set(core, Blocks.PACKED_ICE.defaultBlockState())
                        set(core.above(), Blocks.BLUE_ICE.defaultBlockState())
                    }
                    DungeonStyle.GROTTO -> set(core, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3))
                    else -> set(core, Blocks.COBWEB.defaultBlockState())
                }
            }
        }
    }

    /** A crypt's mausoleum: worn walls, a doorway, chiselled corners and a roof fallen in here and there. */
    private fun mausoleum(room: PlanRoom, top: Int) {
        for (du in -4..4) for (dv in -4..4) {
            val edge = abs(du) == 4 || abs(dv) == 4
            if (abs(du) == 4 && abs(dv) == 4) {
                for (y in top..top + 4) set(at(room, du, dv, y), Blocks.CHISELED_STONE_BRICKS.defaultBlockState())
                continue
            }
            if (edge) {
                val door = du == -4 && abs(dv) <= 1
                for (y in top..top + 3) {
                    if (door && y <= top + 2) continue
                    if (y > top && random.nextInt(8) == 0) continue
                    set(at(room, du, dv, y), style.wall(random, y))
                }
            }
            if (random.nextInt(5) != 0) set(at(room, du, dv, top + 4), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        }
        for (side in intArrayOf(-2, 2)) set(at(room, -5, side, top), Blocks.SOUL_LANTERN.defaultBlockState())
    }

    /** A mine's headframe: four posts, a frame of beams with a lantern hung from it, and a fence round the pit. */
    private fun headframe(room: PlanRoom, top: Int) {
        val post = Blocks.SPRUCE_LOG.defaultBlockState()
        val beamU = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, layout.forward.axis)
        val beamV = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, layout.right.axis)
        for (du in intArrayOf(-4, 4)) for (dv in intArrayOf(-4, 4)) for (y in top..top + 4) set(at(room, du, dv, y), post)
        for (k in -3..3) {
            set(at(room, k, -4, top + 4), beamU)
            set(at(room, k, 4, top + 4), beamU)
            set(at(room, -4, k, top + 4), beamV)
            set(at(room, 4, k, top + 4), beamV)
            set(at(room, 0, k, top + 4), beamV)
        }
        set(at(room, 0, 0, top + 3), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
        for (du in -4..4) for (dv in -4..4) {
            if (max(abs(du), abs(dv)) != 4 || (abs(du) == 4 && abs(dv) == 4) || (du == -4 && abs(dv) <= 1)) continue
            set(at(room, du, dv, top), Blocks.SPRUCE_FENCE.defaultBlockState())
        }
        set(at(room, -5, 3, top), Blocks.BARREL.defaultBlockState())
        set(at(room, -5, -3, top), Blocks.COBBLESTONE.defaultBlockState())
        set(at(room, -5, -4, top), Blocks.GRAVEL.defaultBlockState())
        set(at(room, -5, 2, top), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, layout.dir(0, -1)))
    }

    /** A shrine's colonnade: broken columns of sea stone, one or two still crowned with light, in drifted sand. */
    private fun colonnade(room: PlanRoom, top: Int) {
        for ((du, dv) in listOf(-4 to -4, -4 to 4, 4 to -4, 4 to 4, 4 to 0, 0 to -4, 0 to 4)) {
            val height = 2 + random.nextInt(4)
            for (h in 0 until height) set(at(room, du, dv, top + h), (if (h == 0) Blocks.DARK_PRISMARINE else Blocks.PRISMARINE_BRICKS).defaultBlockState())
            if (height == 5) set(at(room, du, dv, top + 5), Blocks.SEA_LANTERN.defaultBlockState())
        }
        repeat(14) {
            val angle = random.nextDouble() * Math.PI * 2
            val r = 4.0 + random.nextDouble() * 1.8
            val pos = at(room, (cos(angle) * r).roundToInt(), (sin(angle) * r).roundToInt(), top - 1)
            if (!air(pos) && air(pos.above())) set(pos, Blocks.SAND.defaultBlockState())
        }
    }

    /** Uprights round the pit: boulders for a den, basalt for a pit, ice for a vault, stumps for a hollow. */
    private fun stones(room: PlanRoom, top: Int) {
        val count = 9
        val start = random.nextDouble() * Math.PI * 2
        for (n in 0 until count) {
            val angle = start + Math.PI * 2 * n / count + random.nextDouble() * 0.35
            val du = (cos(angle) * 4.6).roundToInt()
            val dv = (sin(angle) * 4.6).roundToInt()
            if (du <= -3 && abs(dv) <= 1) continue
            val height = 1 + random.nextInt(when (style) {
                DungeonStyle.PIT -> 5
                DungeonStyle.RIME -> 6
                else -> 3
            })
            for (h in 0 until height) set(at(room, du, dv, top + h), when (style) {
                DungeonStyle.PIT -> (if (random.nextBoolean()) Blocks.BASALT else Blocks.POLISHED_BASALT).defaultBlockState()
                DungeonStyle.RIME -> (if (h == height - 1) Blocks.BLUE_ICE else Blocks.PACKED_ICE).defaultBlockState()
                DungeonStyle.GROTTO -> Blocks.DARK_OAK_LOG.defaultBlockState()
                else -> DungeonStyle.pick(random, Blocks.STONE to 2, Blocks.COBBLESTONE to 2, Blocks.MOSSY_COBBLESTONE to 1)
            })
            val above = at(room, du, dv, top + height)
            when (style) {
                DungeonStyle.GROTTO -> set(above, Blocks.MOSS_CARPET.defaultBlockState())
                DungeonStyle.CAVE -> if (random.nextInt(2) == 0) set(above, Blocks.COBWEB.defaultBlockState())
                else -> {}
            }
        }
        // A little more round the yard
        repeat(6) {
            val angle = random.nextDouble() * Math.PI * 2
            val pos = at(room, (cos(angle) * 5.2).roundToInt(), (sin(angle) * 5.2).roundToInt(), top)
            if (!air(pos) || air(pos.below())) return@repeat
            when (style) {
                DungeonStyle.PIT -> if (random.nextBoolean()) set(pos.below(), Blocks.MAGMA_BLOCK.defaultBlockState())
                else {
                    set(pos.below(), Blocks.SOUL_SOIL.defaultBlockState())
                    set(pos, Blocks.SOUL_FIRE.defaultBlockState())
                }
                DungeonStyle.GROTTO -> set(pos, (if (random.nextBoolean()) Blocks.RED_MUSHROOM else Blocks.BROWN_MUSHROOM).defaultBlockState())
                DungeonStyle.CAVE -> if (random.nextBoolean()) skull(pos) else set(pos, Blocks.COBWEB.defaultBlockState())
                DungeonStyle.RIME -> set(pos, Blocks.PACKED_ICE.defaultBlockState())
                else -> {}
            }
        }
    }

    // ---- Dressing ----

    private fun dress(index: Int) {
        if (plan.rooms[index].kind == RoomKind.BOSS) {
            arena(index)
            return
        }
        when (style) {
            DungeonStyle.CRYPT -> crypt(index)
            DungeonStyle.CAVE -> den(index)
            DungeonStyle.MINE -> mine(index)
            DungeonStyle.PIT -> pit(index)
            DungeonStyle.SHRINE -> shrine(index)
            DungeonStyle.RIME -> rime(index)
            DungeonStyle.GROTTO -> grotto(index)
        }
    }

    /** Barrow halls: soul lanterns on chains, burial niches in the walls, coffins, candles and bones. */
    private fun crypt(index: Int) {
        val room = plan.rooms[index]
        for ((du, dv) in quarters(room)) if (random.nextInt(4) != 0) hang(index, du, dv, Blocks.SOUL_LANTERN, random.nextInt(2))
        for ((wall, inward) in walls(index)) {
            if (random.nextInt(3) != 0) continue
            for (rise in intArrayOf(1, 3)) {
                if (rise + 1 >= room.height) continue
                val niche = wall.above(rise)
                set(niche.relative(inward.opposite), style.wall(random, niche.y))
                when (random.nextInt(6)) {
                    0, 1 -> skull(niche, inward)
                    2 -> set(niche, Blocks.BONE_BLOCK.defaultBlockState())
                    3 -> candles(niche)
                    4 -> set(niche, Blocks.COBWEB.defaultBlockState())
                    else -> pot(niche, inward)
                }
            }
        }
        for (pos in spots(index, 2 + random.nextInt(3))) when (random.nextInt(5)) {
            0 -> coffin(pos)
            1 -> candles(pos)
            2 -> set(pos, Blocks.BONE_BLOCK.defaultBlockState())
            3 -> skull(pos)
            else -> pot(pos, layout.forward)
        }
        corners(index, Blocks.COBWEB.defaultBlockState(), 2)
    }

    /** A spider den: glow lichen the only light, webs thick in the roof, stalactites and old bones. */
    private fun den(index: Int) {
        cling(index, 6 + random.nextInt(5), false) { pos, face -> set(pos, lichen(face)) }
        cling(index, 10 + random.nextInt(8), true) { pos, _ -> set(pos, Blocks.COBWEB.defaultBlockState()) }
        repeat(3 + random.nextInt(3)) { stalactite(index) }
        for (pos in spots(index, 2 + random.nextInt(3))) when (random.nextInt(3)) {
            0 -> set(pos, Blocks.BONE_BLOCK.defaultBlockState())
            1 -> skull(pos)
            else -> set(pos, Blocks.COBWEB.defaultBlockState())
        }
    }

    /** An old mine working: timber sets down the hall, a run of rail, lanterns, and what the miners left. */
    private fun mine(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        timber(index)
        // A run of rail down the middle
        val alongU = room.halfU >= room.halfV
        val shape = if ((if (alongU) layout.forward else layout.right).axis == Direction.Axis.X) RailShape.EAST_WEST else RailShape.NORTH_SOUTH
        val long = if (alongU) room.halfU else room.halfV
        if (random.nextInt(3) != 0) for (a in -long..long) {
            val pos = if (alongU) at(room, a, 0, f) else at(room, 0, a, f)
            if (air(pos) && !stairs(index, a, 0) && random.nextInt(9) != 0) set(pos, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape))
        }
        for (pos in spots(index, 2 + random.nextInt(3))) when (random.nextInt(6)) {
            0 -> set(pos, Blocks.BARREL.defaultBlockState())
            1 -> {
                set(pos, Blocks.COBBLESTONE.defaultBlockState())
                if (random.nextBoolean()) set(pos.above(), Blocks.GRAVEL.defaultBlockState())
            }
            2 -> set(pos, Blocks.GRAVEL.defaultBlockState())
            3 -> set(pos, Blocks.FURNACE.defaultBlockState())
            4 -> set(pos, Blocks.CRAFTING_TABLE.defaultBlockState())
            else -> set(pos, Blocks.COBWEB.defaultBlockState())
        }
        corners(index, Blocks.COBWEB.defaultBlockState(), 3)
    }

    /** Timber sets every few blocks down a room's length: a post at each wall and a beam across the roof. */
    private fun timber(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val alongU = room.halfU >= room.halfV
        val long = if (alongU) room.halfU else room.halfV
        val cross = if (alongU) room.halfV else room.halfU
        val top = f + room.height - 1
        fun spot(a: Int, c: Int, y: Int) = if (alongU) at(room, a, c, y) else at(room, c, a, y)
        val post = Blocks.SPRUCE_LOG.defaultBlockState()
        val beam = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, (if (alongU) layout.right else layout.forward).axis)
        var a = -long + 1
        while (a <= long - 1) {
            if (abs(a) >= 2) {
                for (c in intArrayOf(-cross, cross)) for (y in f until top) if (air(spot(a, c, y))) set(spot(a, c, y), post)
                for (c in -cross..cross) set(spot(a, c, top), beam)
                if (top - 1 >= f + 3 && random.nextInt(2) == 0) set(spot(a, 0, top - 1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
                else if (random.nextInt(2) == 0) {
                    val side = if (random.nextBoolean()) 1 else -1
                    val torch = spot(a, side * (cross - 1), f + 2)
                    if (air(torch)) set(torch, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, if (alongU) layout.dir(0, -side) else layout.dir(-side, 0)))
                }
            }
            a += 4
        }
    }

    /** An ashen pit: lava pools, basalt columns, magma underfoot, soul fire and hanging chains. */
    private fun pit(index: Int) {
        val room = plan.rooms[index]
        repeat(1 + random.nextInt(2)) { pool(index, Blocks.LAVA.defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState()) }
        for (pos in spots(index, 1 + random.nextInt(2))) column(index, pos, Blocks.POLISHED_BASALT.defaultBlockState())
        for (pos in spots(index, 3 + random.nextInt(3))) set(pos.below(), Blocks.MAGMA_BLOCK.defaultBlockState())
        for (pos in spots(index, 1)) {
            set(pos.below(), Blocks.SOUL_SOIL.defaultBlockState())
            set(pos, Blocks.SOUL_FIRE.defaultBlockState())
        }
        for ((du, dv) in quarters(room)) when (random.nextInt(3)) {
            0 -> hang(index, du, dv, Blocks.LANTERN, 1)
            1 -> chains(index, du, dv)
        }
        for (pos in spots(index, 1 + random.nextInt(2))) if (random.nextInt(3) == 0) skull(pos, null, Blocks.WITHER_SKELETON_SKULL) else set(pos, Blocks.BONE_BLOCK.defaultBlockState())
    }

    /** A sunken shrine: columns banded with sea lanterns, pools with seagrass, drifted sand and dead coral. */
    private fun shrine(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        if (room.halfU >= 4 && room.halfV >= 4) {
            for (su in intArrayOf(-1, 1)) for (sv in intArrayOf(-1, 1)) {
                val du = su * (room.halfU - 1)
                val dv = sv * (room.halfV - 1)
                for (y in f until f + room.height) set(at(room, du, dv, y), (if (y == f + 2) Blocks.SEA_LANTERN else Blocks.PRISMARINE_BRICKS).defaultBlockState())
            }
        } else {
            for (pos in spots(index, 2)) set(pos.below(), Blocks.SEA_LANTERN.defaultBlockState())
        }
        repeat(1 + random.nextInt(2)) { pool(index, Blocks.SEAGRASS.defaultBlockState(), Blocks.SAND.defaultBlockState()) }
        for (pos in spots(index, 3 + random.nextInt(3))) set(pos.below(), Blocks.SAND.defaultBlockState())
        for (pos in spots(index, 2 + random.nextInt(3))) when (random.nextInt(4)) {
            0 -> set(pos, dry(Blocks.DEAD_BRAIN_CORAL_FAN))
            1 -> set(pos, dry(Blocks.DEAD_TUBE_CORAL_FAN))
            2 -> set(pos, dry(Blocks.DEAD_HORN_CORAL_FAN))
            else -> pot(pos, layout.forward)
        }
    }

    /** A rime vault: soul lanterns, ice spikes and icicles, snowdrifts, and powder snow waiting underfoot. */
    private fun rime(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        for ((du, dv) in quarters(room)) if (random.nextInt(3) != 0) hang(index, du, dv, Blocks.SOUL_LANTERN, random.nextInt(2))
        for (pos in spots(index, 2 + random.nextInt(3))) {
            val height = 1 + random.nextInt(3)
            for (h in 0 until height) set(pos.above(h), (if (h == height - 1) Blocks.BLUE_ICE else Blocks.PACKED_ICE).defaultBlockState())
        }
        repeat(3 + random.nextInt(2)) {
            val du = random.nextInt(2 * room.halfU + 1) - room.halfU
            val dv = random.nextInt(2 * room.halfV + 1) - room.halfV
            if (stairs(index, du, dv)) return@repeat
            val roof = roof(index, du, dv) ?: return@repeat
            if (roof - 3 < f + 3) return@repeat
            for (y in roof - 1 - random.nextInt(2)..roof - 1) set(at(room, du, dv, y), Blocks.PACKED_ICE.defaultBlockState())
        }
        if (random.nextInt(3) != 0) pool(index, Blocks.POWDER_SNOW.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState())
        for (pos in spots(index, 4 + random.nextInt(3))) {
            if (level.getBlockState(pos.below()).`is`(Blocks.SNOW_BLOCK)) set(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + random.nextInt(3)))
        }
        for (pos in spots(index, 1 + random.nextInt(2))) {
            if (random.nextBoolean()) skull(pos) else candles(pos)
        }
    }

    /** A witch's hollow: shroomlight and glowing vines, hanging roots, moss and mushrooms, and her things. */
    private fun grotto(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        repeat(2 + random.nextInt(2)) {
            val (du, dv) = quarters(room)[random.nextInt(4)]
            val roof = roof(index, du, dv) ?: return@repeat
            set(at(room, du, dv, roof), Blocks.SHROOMLIGHT.defaultBlockState())
        }
        repeat(3 + random.nextInt(3)) {
            val du = random.nextInt(2 * room.halfU + 1) - room.halfU
            val dv = random.nextInt(2 * room.halfV + 1) - room.halfV
            if (stairs(index, du, dv)) return@repeat
            val roof = roof(index, du, dv) ?: return@repeat
            val length = 1 + random.nextInt(3)
            if (roof - length < f + 3) return@repeat
            for (n in 1..length) {
                val pos = at(room, du, dv, roof - n)
                set(pos, if (n == length) Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, random.nextBoolean())
                else Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, random.nextInt(3) == 0))
            }
        }
        repeat(4 + random.nextInt(3)) {
            val du = random.nextInt(2 * room.halfU + 1) - room.halfU
            val dv = random.nextInt(2 * room.halfV + 1) - room.halfV
            if (stairs(index, du, dv)) return@repeat
            val roof = roof(index, du, dv) ?: return@repeat
            if (roof - 1 >= f + 3) set(at(room, du, dv, roof - 1), Blocks.HANGING_ROOTS.defaultBlockState())
        }
        cling(index, 3 + random.nextInt(3), false) { pos, face -> set(pos, lichen(face)) }
        for (pos in spots(index, 8 + random.nextInt(5))) set(pos, Blocks.MOSS_CARPET.defaultBlockState())
        for (pos in spots(index, 4 + random.nextInt(3))) set(pos, (if (random.nextBoolean()) Blocks.RED_MUSHROOM else Blocks.BROWN_MUSHROOM).defaultBlockState())
        if (room.height >= 6 && random.nextBoolean()) spots(index, 1).firstOrNull()?.let { toadstool(index, it) }
        if (random.nextInt(5) < 2) for (pos in spots(index, 2)) when (random.nextInt(3)) {
            0 -> set(pos, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 1 + random.nextInt(3)))
            1 -> set(pos, Blocks.BREWING_STAND.defaultBlockState())
            else -> set(pos, Blocks.POTTED_RED_MUSHROOM.defaultBlockState())
        }
    }

    /** A toadstool taller than a man: a pale stem under a red cap. */
    private fun toadstool(index: Int, pos: BlockPos) {
        for (h in 0..2) if (air(pos.above(h))) set(pos.above(h), Blocks.MUSHROOM_STEM.defaultBlockState())
        for (dx in -1..1) for (dz in -1..1) {
            val cap = pos.offset(dx, 3, dz)
            if (air(cap)) set(cap, Blocks.RED_MUSHROOM_BLOCK.defaultBlockState())
        }
        if (air(pos.above(4))) set(pos.above(4), Blocks.RED_MUSHROOM_BLOCK.defaultBlockState())
    }

    /** A treasure room's chests against the far wall, facing the door, and a few pots along the sides. */
    private fun treasure(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val link = layout.linkTo(index) ?: return
        val parent = plan.rooms[link.a]
        val away = if (link.alongU) (room.u - parent.u).sign else (room.v - parent.v).sign
        val toDoor = if (link.alongU) layout.dir(-away, 0) else layout.dir(0, -away)
        val offsets = if (major || random.nextInt(3) == 0) intArrayOf(-2, 2) else intArrayOf(0)
        for (c in offsets) {
            val pos = if (link.alongU) at(room, away * room.halfU, c, f) else at(room, c, away * room.halfV, f)
            chest(pos, toDoor, lair.theme.loot)
            // Candles to see it by
            val beside = pos.relative(if (link.alongU) layout.dir(0, if (c < 0) -1 else 1) else layout.dir(if (c < 0) -1 else 1, 0))
            if (c != 0 && air(beside)) candles(beside)
        }
        for (pos in spots(index, 2 + random.nextInt(2))) pot(pos, toDoor)
    }

    /**
     * The boss hall: two rows of columns lit toward the middle (or, in a cave, the bare dome), lights hung
     * down the middle, a seat for the master at the far end and the style's dressing round the walls.
     */
    private fun arena(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val link = layout.linkTo(index) ?: return
        val alongU = link.alongU
        val s = if (alongU) (room.u - plan.rooms[link.a].u).sign else (room.v - plan.rooms[link.a].v).sign
        val long = if (alongU) room.halfU else room.halfV
        val cross = if (alongU) room.halfV else room.halfU
        fun spot(a: Int, c: Int, y: Int) = if (alongU) at(room, a, c, y) else at(room, c, a, y)
        fun way(a: Int, c: Int) = if (alongU) layout.dir(a, c) else layout.dir(c, a)

        if (!style.organic) {
            val span = 2 * (long - 3)
            val count = if (long >= 13) 5 else 4
            for (k in 0 until count) {
                val a = (-span / 2.0 + k * span / (count - 1.0)).roundToInt()
                for (side in intArrayOf(-1, 1)) {
                    val c = side * (cross - 2)
                    for (y in f until f + room.height) set(spot(a, c, y), style.pillar(random, y))
                    if (major) set(spot(a, c, f + room.height - 1), Blocks.CRYING_OBSIDIAN.defaultBlockState())
                    wallLight(spot(a, c, f + 3), way(0, -side))
                }
            }
        }
        for (a in intArrayOf(-long / 2, long / 2)) for (c in intArrayOf(-cross / 2, cross / 2)) {
            val du = if (alongU) a else c
            val dv = if (alongU) c else a
            hang(index, du, dv, if (style == DungeonStyle.MINE || style == DungeonStyle.PIT) Blocks.LANTERN else Blocks.SOUL_LANTERN, room.height - 6)
        }

        // The seat of the master, against the far wall
        val seat = spot(s * long, 0, f)
        if (standable(seat)) {
            set(seat, style.stairs.defaultBlockState().setValue(StairBlock.FACING, way(s, 0)))
            for (side in intArrayOf(-1, 1)) {
                val arm = spot(s * long, side, f)
                set(arm, style.pillar(random, f))
                set(arm.above(), style.pillar(random, f + 1))
                if (style == DungeonStyle.CRYPT || style == DungeonStyle.RIME) candles(arm.above(2))
            }
        }

        // Along the walls, by style
        when (style) {
            DungeonStyle.CRYPT -> {
                for ((wall, inward) in walls(index)) if (random.nextInt(4) == 0) candles(wall.relative(inward))
                for (pos in spots(index, 4)) if (random.nextBoolean()) skull(pos) else set(pos, Blocks.BONE_BLOCK.defaultBlockState())
                corners(index, Blocks.COBWEB.defaultBlockState(), 2)
            }
            DungeonStyle.CAVE -> den(index)
            DungeonStyle.MINE -> {
                timber(index)
                for (pos in spots(index, 3)) set(pos, Blocks.COBBLESTONE.defaultBlockState())
            }
            DungeonStyle.PIT, DungeonStyle.SHRINE -> {
                // Channels of lava (or the sea) along both long walls
                val fill = if (style == DungeonStyle.PIT) Blocks.LAVA.defaultBlockState() else Blocks.WATER.defaultBlockState()
                val bed = if (style == DungeonStyle.PIT) Blocks.BLACKSTONE.defaultBlockState() else Blocks.SAND.defaultBlockState()
                for (a in -long + 2..long - 2) for (side in intArrayOf(-1, 1)) {
                    val pos = spot(a, side * cross, f)
                    if (!air(pos)) continue
                    set(pos.below(2), bed)
                    set(pos.below(), fill)
                }
            }
            DungeonStyle.RIME -> {
                for (pos in spots(index, 6)) {
                    val height = 1 + random.nextInt(4)
                    for (h in 0 until height) set(pos.above(h), (if (h == height - 1) Blocks.BLUE_ICE else Blocks.PACKED_ICE).defaultBlockState())
                }
                pool(index, Blocks.POWDER_SNOW.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState())
            }
            DungeonStyle.GROTTO -> grotto(index)
        }

        // A vault burns soul fire in its corners
        if (major) for (a in intArrayOf(-(long - 1), long - 1)) for (c in intArrayOf(-(cross - 1), cross - 1)) {
            val pos = spot(a, c, f)
            if (!standable(pos)) continue
            set(pos.below(), Blocks.SOUL_SOIL.defaultBlockState())
            set(pos, Blocks.SOUL_FIRE.defaultBlockState())
        }
    }

    // ---- Pieces ----

    /** Spots on a room's floor away from the lanes between its doors (and the stairwell), clear to stand in. */
    private fun spots(index: Int, count: Int): List<BlockPos> {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val found = ArrayList<BlockPos>()
        repeat(count * 10) {
            if (found.size >= count) return found
            val du = random.nextInt(2 * room.halfU + 1) - room.halfU
            val dv = random.nextInt(2 * room.halfV + 1) - room.halfV
            if (abs(du) <= 1 || abs(dv) <= 1) return@repeat
            if (stairs(index, du, dv)) return@repeat
            val pos = at(room, du, dv, f)
            if (pos in found || !standable(pos)) return@repeat
            found.add(pos)
        }
        return found
    }

    /** Points a quarter of the way in from each corner of a room, for hanging lights. */
    private fun quarters(room: PlanRoom): List<Pair<Int, Int>> {
        val u = (room.halfU + 1) / 2
        val v = (room.halfV + 1) / 2
        return listOf(-u to -v, -u to v, u to -v, u to v)
    }

    /** A built room's walls, clear of where doorways go: each wall block at floor height, and the way in. */
    private fun walls(index: Int): List<Pair<BlockPos, Direction>> {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val found = ArrayList<Pair<BlockPos, Direction>>()
        for (dv in -room.halfV..room.halfV) if (abs(dv) >= 3) {
            found.add(at(room, room.halfU + 1, dv, f) to layout.dir(-1, 0))
            found.add(at(room, -room.halfU - 1, dv, f) to layout.dir(1, 0))
        }
        for (du in -room.halfU..room.halfU) if (abs(du) >= 3) {
            found.add(at(room, du, room.halfV + 1, f) to layout.dir(0, -1))
            found.add(at(room, du, -room.halfV - 1, f) to layout.dir(0, 1))
        }
        return found
    }

    /** The first block above the open air at [du], [dv] in a room: its ceiling there. Null if none is close. */
    private fun roof(index: Int, du: Int, dv: Int): Int? {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        if (!air(at(room, du, dv, f + 1))) return null
        var y = f + 2
        while (y <= f + room.height + 1) {
            if (!air(at(room, du, dv, y))) return y
            y++
        }
        return null
    }

    /** A lantern hung from the ceiling on [chain] links of chain, never lower than head height. */
    private fun hang(index: Int, du: Int, dv: Int, lantern: Block, chain: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val roof = roof(index, du, dv) ?: return
        if (!level.getBlockState(at(room, du, dv, roof)).isFaceSturdy(level, at(room, du, dv, roof), Direction.DOWN)) return
        val y = (roof - 1 - chain.coerceAtLeast(0)).coerceAtLeast(f + 3)
        if (y >= roof) return
        for (cy in y + 1 until roof) set(at(room, du, dv, cy), Blocks.IRON_CHAIN.defaultBlockState())
        set(at(room, du, dv, y), lantern.defaultBlockState().setValue(LanternBlock.HANGING, true))
    }

    /** Bare chains hanging from the ceiling. */
    private fun chains(index: Int, du: Int, dv: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val roof = roof(index, du, dv) ?: return
        val length = 1 + random.nextInt(3)
        for (n in 1..length) if (roof - n >= f + 3) set(at(room, du, dv, roof - n), Blocks.IRON_CHAIN.defaultBlockState())
    }

    /** A column from floor to ceiling. */
    private fun column(index: Int, pos: BlockPos, state: BlockState) {
        val room = plan.rooms[index]
        var y = 0
        while (y < room.height && air(pos.above(y))) {
            set(pos.above(y), state)
            y++
        }
    }

    /** A stalactite hanging from the ceiling, clear of the lanes. */
    private fun stalactite(index: Int) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        val du = random.nextInt(2 * room.halfU + 1) - room.halfU
        val dv = random.nextInt(2 * room.halfV + 1) - room.halfV
        if (abs(du) <= 1 || abs(dv) <= 1 || stairs(index, du, dv)) return
        val roof = roof(index, du, dv) ?: return
        if (roof - 1 < f + 3) return
        set(at(room, du, dv, roof - 1), Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(SpeleothemBlock.TIP_DIRECTION, Direction.DOWN).setValue(SpeleothemBlock.THICKNESS, SpeleothemThickness.TIP))
    }

    /** A small pool sunk into the floor: two by two of [fill] over a [bed], clear of the lanes and walls. */
    private fun pool(index: Int, fill: BlockState, bed: BlockState) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        repeat(12) {
            val du = random.nextInt(2 * room.halfU - 2) - room.halfU + 1
            val dv = random.nextInt(2 * room.halfV - 2) - room.halfV + 1
            val cells = listOf(du to dv, du + 1 to dv, du to dv + 1, du + 1 to dv + 1)
            if (cells.any { (u, v) -> abs(u) <= 1 || abs(v) <= 1 || abs(u) > room.halfU - 1 || abs(v) > room.halfV - 1 || stairs(index, u, v) }) return@repeat
            if (cells.any { (u, v) -> !standable(at(room, u, v, f)) }) return@repeat
            for ((u, v) in cells) {
                set(at(room, u, v, f - 2), bed)
                set(at(room, u, v, f - 1), fill)
            }
            return
        }
    }

    /** Something in the top corners of a room, a few of them. */
    private fun corners(index: Int, state: BlockState, chance: Int) {
        val room = plan.rooms[index]
        val y = layout.floor(index) + room.height - 1
        for (du in intArrayOf(-room.halfU, room.halfU)) for (dv in intArrayOf(-room.halfV, room.halfV)) {
            val pos = at(room, du, dv, y)
            if (random.nextInt(chance) == 0 && air(pos)) set(pos, state)
        }
    }

    /** Air beside the rock for things that cling to it, given the spot and the way to the rock. */
    private fun cling(index: Int, count: Int, high: Boolean, place: (BlockPos, Direction) -> Unit) {
        val room = plan.rooms[index]
        val f = layout.floor(index)
        var placed = 0
        repeat(count * 12) {
            if (placed >= count) return
            val du = random.nextInt(2 * room.halfU + 1) - room.halfU
            val dv = random.nextInt(2 * room.halfV + 1) - room.halfV
            val low = if (high) room.height / 2 else 0
            val y = f + low + random.nextInt(room.height - low)
            if ((y < f + 3 && (abs(du) <= 1 || abs(dv) <= 1)) || stairs(index, du, dv)) return@repeat
            val pos = at(room, du, dv, y)
            if (!air(pos)) return@repeat
            val turn = random.nextInt(CLING.size)
            for (n in CLING.indices) {
                val face = CLING[(n + turn) % CLING.size]
                val rock = pos.relative(face)
                if (level.getBlockState(rock).isFaceSturdy(level, rock, face.opposite)) {
                    place(pos, face)
                    placed++
                    return@repeat
                }
            }
        }
    }

    /** Coral comes waterlogged; dead coral on a shrine's floor is dry, or it would be a spring. */
    private fun dry(block: Block): BlockState = block.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, false)

    private fun lichen(face: Direction): BlockState = Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(face), true)

    private fun candles(pos: BlockPos) =
        set(pos, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1 + random.nextInt(4)).setValue(CandleBlock.LIT, true))

    /** A skull, looking [facing] (or any way at all). */
    private fun skull(pos: BlockPos, facing: Direction? = null, block: Block = Blocks.SKELETON_SKULL) =
        set(pos, block.defaultBlockState().setValue(SkullBlock.ROTATION, facing?.let { RotationSegment.convertToSegment(it) } ?: random.nextInt(16)))

    /** A pot; one in three holds a few Guild Marks for whoever smashes it. */
    private fun pot(pos: BlockPos, facing: Direction) {
        set(pos, Blocks.DECORATED_POT.defaultBlockState().setValue(DecoratedPotBlock.HORIZONTAL_FACING, facing))
        if (random.nextInt(3) == 0) {
            (level.getBlockEntity(pos) as? DecoratedPotBlockEntity)?.setTheItem(ItemStack(ModItems.GUILD_MARK.get(), 1 + random.nextInt(2 + lair.rank)))
        }
    }

    /** A stone coffin, two blocks long, with a candle at its head. */
    private fun coffin(pos: BlockPos) {
        val along = if (random.nextBoolean()) layout.forward else layout.right
        val foot = pos.relative(along)
        if (!standable(foot)) return
        set(pos, Blocks.SMOOTH_STONE.defaultBlockState())
        set(foot, Blocks.SMOOTH_STONE.defaultBlockState())
        candles(pos.above())
    }

    /** A chest of the lair's loot with Guild Marks in it, often a relic and now and then a lore fragment. */
    private fun chest(pos: BlockPos, facing: Direction, loot: ResourceKey<LootTable>) {
        set(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing))
        (level.getBlockEntity(pos) as? ChestBlockEntity)?.let { chest ->
            chest.setItem(0, ItemStack(ModItems.GUILD_MARK.get(), 2 + lair.rank * 2 + random.nextInt(3)))
            if (random.nextInt(5) < 3) chest.setItem(1, Relics.roll(level, lair.rank, Rarity.COMMON))
            if (random.nextInt(4) == 0) chest.setItem(2, LoreBooks.random(random))
            chest.setLootTable(loot, random.nextLong())
        }
    }

    // ---- Noise ----

    /** Smooth noise in 0..1, the same for the same seed and place. */
    private fun noise(seed: Long, x: Double, y: Double, z: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val tx = fade(x - x0)
        val ty = fade(y - y0)
        val tz = fade(z - z0)
        fun c(dx: Int, dy: Int, dz: Int) = hash(seed, x0 + dx, y0 + dy, z0 + dz)
        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
        return lerp(
            lerp(lerp(c(0, 0, 0), c(1, 0, 0), tx), lerp(c(0, 1, 0), c(1, 1, 0), tx), ty),
            lerp(lerp(c(0, 0, 1), c(1, 0, 1), tx), lerp(c(0, 1, 1), c(1, 1, 1), tx), ty),
            tz,
        )
    }

    private fun fade(t: Double): Double = t * t * (3 - 2 * t)

    private fun hash(seed: Long, x: Int, y: Int, z: Int): Double {
        var h = seed + x * -7046029254386353131L + y * -4417276706812531889L + z * 1609587929392839161L
        h = (h xor (h ushr 33)) * -49064778989728563L
        h = (h xor (h ushr 33)) * -4265267296055464877L
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    companion object {
        /** No neighbour updates while digging: nothing flows, falls or pops off until it's all in place. */
        private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

        private val AIR: BlockState = Blocks.AIR.defaultBlockState()

        /** The stairwell's path, cell by cell round the core, starting by the doorway. */
        private val RING = listOf(
            -2 to 0, -2 to 1, -2 to 2, -1 to 2, 0 to 2, 1 to 2, 2 to 2, 2 to 1,
            2 to 0, 2 to -1, 2 to -2, 1 to -2, 0 to -2, -1 to -2, -2 to -2, -2 to -1,
        )

        /** The ways a clinging thing can face its rock: never the floor. */
        private val CLING = arrayOf(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
    }
}
