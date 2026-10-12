package io.github.vaspyyy.guildmark.lair

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.levelgen.Heightmap
import java.util.Optional
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * A dug dungeon's lasting record: the seed its plan grows from, where and which way it lies, how deep
 * each room sits, and which rooms have been built and had their guards rise.
 */
class DungeonState(
    val seed: Long,
    /** Which way the dungeon runs from its entrance, as a horizontal direction's 2D value. */
    val heading: Int,
    /** The entrance hall's centre, at its floor. */
    val origin: BlockPos,
    /** Each room's floor height. */
    val floors: List<Int>,
    /** Rooms built so far, one bit each. */
    var built: Long = 0L,
    /** Rooms whose guards have risen, one bit each. */
    var guarded: Long = 0L,
    /** The plan as it was laid out, so rooms still to be built match the ones already dug whatever the planner does later. */
    val plan: DungeonPlan? = null,
) {
    fun isBuilt(room: Int): Boolean = built and (1L shl room) != 0L
    fun isGuarded(room: Int): Boolean = guarded and (1L shl room) != 0L

    companion object {
        val CODEC: Codec<DungeonState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("seed").forGetter(DungeonState::seed),
                Codec.INT.fieldOf("heading").forGetter(DungeonState::heading),
                BlockPos.CODEC.fieldOf("origin").forGetter(DungeonState::origin),
                Codec.INT.listOf().fieldOf("floors").forGetter(DungeonState::floors),
                Codec.LONG.optionalFieldOf("built", 0L).forGetter(DungeonState::built),
                Codec.LONG.optionalFieldOf("guarded", 0L).forGetter(DungeonState::guarded),
                DungeonPlan.CODEC.lenientOptionalFieldOf("plan").forGetter { Optional.ofNullable(it.plan) },
            ).apply(i) { seed, heading, origin, floors, built, guarded, plan ->
                DungeonState(seed, heading, origin, floors, built, guarded, plan.orElse(null)?.takeIf { it.rooms.size == floors.size })
            }
        }
    }
}

/** A box of blocks, both corners included. */
data class Box(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    fun grow(n: Int): Box = Box(minX - n, minY - n, minZ - n, maxX + n, maxY + n, maxZ + n)

    fun union(o: Box): Box = Box(min(minX, o.minX), min(minY, o.minY), min(minZ, o.minZ), max(maxX, o.maxX), max(maxY, o.maxY), max(maxZ, o.maxZ))

    fun contains(x: Double, y: Double, z: Double): Boolean =
        x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1

    fun contains(entity: Entity): Boolean = contains(entity.x, entity.y, entity.z)

    fun contains(pos: BlockPos): Boolean =
        pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ
}

/**
 * Where a dungeon's plan lands in the world. Plan space is turned to face [forward]: u runs that way
 * and v to its right, both counted from the entrance hall's centre.
 */
class DungeonLayout(val state: DungeonState, val plan: DungeonPlan) {
    val forward: Direction = Direction.from2DDataValue(state.heading)
    val right: Direction = forward.clockWise

    /** The boss hall, always the last room. */
    val boss: Int = plan.rooms.size - 1

    private val parents = IntArray(plan.rooms.size) { -1 }.also { for (link in plan.links) it[link.b] = link.a }

    /** The room a room's corridor leads back to (toward the entrance), or -1 for the entrance. */
    fun parent(room: Int): Int = parents[room]

    /** The corridor that leads into a room from its parent. */
    fun linkTo(room: Int): PlanLink? = plan.links.firstOrNull { it.b == room }

    fun floor(room: Int): Int = state.floors[room]

    fun pos(u: Int, v: Int, y: Int): BlockPos = BlockPos(
        state.origin.x + u * forward.stepX + v * right.stepX,
        y,
        state.origin.z + u * forward.stepZ + v * right.stepZ,
    )

    /** The world direction of a step in plan space. */
    fun dir(du: Int, dv: Int): Direction = when {
        du > 0 -> forward
        du < 0 -> forward.opposite
        dv > 0 -> right
        else -> right.opposite
    }

    /** A room's centre, at its floor. */
    fun center(room: Int): BlockPos {
        val r = plan.rooms[room]
        return pos(r.u, r.v, floor(room))
    }

    /** A room's open inside, floor to ceiling. */
    fun box(room: Int): Box {
        val r = plan.rooms[room]
        val a = pos(r.u - r.halfU, r.v - r.halfV, floor(room))
        val b = pos(r.u + r.halfU, r.v + r.halfV, floor(room) + r.height - 1)
        return Box(min(a.x, b.x), a.y, min(a.z, b.z), max(a.x, b.x), b.y, max(a.z, b.z))
    }

    /** Every room of the dungeon. */
    val bounds: Box by lazy { plan.rooms.indices.map { box(it) }.reduce(Box::union) }

    /** Whether the land a room and the corridor to it are dug from is loaded. */
    fun loaded(level: ServerLevel, room: Int): Boolean {
        val parent = parent(room)
        var area = box(room).grow(3)
        if (parent >= 0) area = area.union(box(parent).grow(3))
        for (cx in (area.minX shr 4)..(area.maxX shr 4)) for (cz in (area.minZ shr 4)..(area.maxZ shr 4)) {
            if (!level.hasChunk(cx, cz)) return false
        }
        return true
    }

    /** The boss hall's door, where the mist gate hangs: three wide and four high. */
    val bossDoor: List<BlockPos> by lazy {
        val link = linkTo(boss) ?: return@lazy emptyList()
        val a = plan.rooms[link.a]
        val b = plan.rooms[boss]
        val f = floor(boss)
        val door = ArrayList<BlockPos>()
        if (link.alongU) {
            val u = b.u - (b.u - a.u).sign * (b.halfU + 1)
            for (w in -1..1) for (y in f..f + 3) door.add(pos(u, b.v + w, y))
        } else {
            val v = b.v - (b.v - a.v).sign * (b.halfV + 1)
            for (w in -1..1) for (y in f..f + 3) door.add(pos(b.u + w, v, y))
        }
        door
    }

    /** The way through the boss hall's door, into the hall. */
    val bossDoorFacing: Direction by lazy {
        val link = linkTo(boss)
        if (link == null) forward
        else {
            val a = plan.rooms[link.a]
            val b = plan.rooms[boss]
            if (link.alongU) dir((b.u - a.u).sign, 0) else dir(0, (b.v - a.v).sign)
        }
    }

    companion object {
        /** How far below the entrance's ground the entrance hall's floor lies. */
        fun depth(major: Boolean): Int = if (major) 18 else 14

        /**
         * How deep each room of [plan] can sit under the real land, with the entrance at [entrance] and
         * the plan turned to [heading]: never closer than a few blocks under the ground, even beneath a
         * valley, and no corridor steeper than its stairs. Reads the land from the generator, so it works
         * for land nobody has loaded yet.
         */
        fun survey(level: ServerLevel, plan: DungeonPlan, entrance: BlockPos, heading: Int, major: Boolean): List<Int> {
            val generator = level.chunkSource.generator
            val randomState = level.chunkSource.randomState()
            val forward = Direction.from2DDataValue(heading)
            val right = forward.clockWise
            val start = entrance.y - depth(major)
            val lowest = level.minY + 8
            val floors = IntArray(plan.rooms.size)
            for ((i, room) in plan.rooms.withIndex()) {
                var ground = if (i == 0) entrance.y else Int.MAX_VALUE
                for (du in intArrayOf(-room.halfU - 1, 0, room.halfU + 1)) for (dv in intArrayOf(-room.halfV - 1, 0, room.halfV + 1)) {
                    val x = entrance.x + (room.u + du) * forward.stepX + (room.v + dv) * right.stepX
                    val z = entrance.z + (room.u + du) * forward.stepZ + (room.v + dv) * right.stepZ
                    ground = min(ground, generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState))
                }
                floors[i] = max(lowest, min(start + room.floor, ground - 5 - room.height))
            }
            // A room pushed down under a valley pulls the rooms beside it down after it
            repeat(256) {
                var changed = false
                for (link in plan.links) {
                    val most = plan.maxDrop(link)
                    if (floors[link.a] - floors[link.b] > most) {
                        floors[link.a] = max(lowest, floors[link.b] + most)
                        changed = true
                    } else if (floors[link.b] - floors[link.a] > most) {
                        floors[link.b] = max(lowest, floors[link.a] + most)
                        changed = true
                    }
                }
                if (!changed) return floors.toList()
            }
            return floors.toList()
        }
    }
}
