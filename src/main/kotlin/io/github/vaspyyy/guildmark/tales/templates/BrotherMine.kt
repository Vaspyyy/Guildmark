package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.lair.Lair
import io.github.vaspyyy.guildmark.lair.LairTheme
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.quest.Expeditions
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import io.github.vaspyyy.guildmark.wardfire.Wardfires
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The Brother Under the Hill's mine (living-world/stories/design.md 8.12), Tales T5: which old mine the tale sends someone into, where
 * in it they wait, where its fire is, and when they are out of it. Everything here reads the lair's own record and its layout
 * (`Lairs.layout`), and looks only at land that is loaded; the one thing it changes is the lair's fire, raised as a first visit would
 * raise it ([raise]), and the mine itself, made as a lair note makes one ([make]).
 */
internal object BrotherMine {
    /** How far from the board an old mine already standing may be to be the one (the reuse a lair note makes, `Contracts`). */
    const val REUSE = 400.0

    /** Blocks across the ground from a dug mine's way in that count as out of it (and no more than two below its mouth). */
    const val MOUTH = 8.0

    /** Blocks from a surface ring's altar that count as out of it, and where in it the one below waits. */
    const val RING_OUT = 30.0
    const val RING_MARK = 6.0

    /** How far across the ground the mine's fire may be from its mouth (a dug mine's waits at the foot of its stairs; a ring's is raised 24 to 32 out). */
    const val FIRE_NEAR = 40

    /** An old mine standing uncleared, of rank 1 or 2, within [REUSE] blocks of [board] and none of [except]'s: the nearest. */
    fun reuse(level: ServerLevel, board: BlockPos, except: Set<Int>): Lair? =
        Lairs.get(level).lairs
            .filter { !it.cleared && it.shard < 0 && it.theme == LairTheme.OLD_MINE && it.rank in 1..2 && it.id !in except && Expeditions.horizontalDistance(it.pos, board) < REUSE }
            .minByOrNull { Expeditions.horizontalDistance(it.pos, board) }

    /** A new old mine of rank 1, 120 to 220 blocks from [board], as a lair note makes one; null if there is no spot. */
    fun make(level: ServerLevel, board: BlockPos): Lair? = Lairs.get(level).create(level, board, 1, LairTheme.OLD_MINE)

    /**
     * Where the one below waits in [lair], or null while it cannot be known (the hall is not built, its land is not loaded):
     *
     *  - in a dug mine, against the wall of the first hall past the entrance (room 1), two blocks in from the end the corridor from the
     *    entrance comes in at, on [side] of it (1 or -1): never on a timber post, which stand at the corners of every fourth block along;
     *    else the hall's middle;
     *  - in a surface ring, [RING_MARK] blocks from the altar toward [town], on the ring's floor.
     */
    fun mark(level: ServerLevel, lair: Lair, side: Int, town: BlockPos): BlockPos? {
        if (lair.delve) {
            val state = lair.dungeon ?: return null
            if (!state.isBuilt(1)) return null
            val layout = Lairs.get(level).layout(lair, state)
            if (layout.plan.rooms.size < 2 || !layout.loaded(level, 1)) return null
            val room = layout.plan.rooms[1]
            val from = layout.plan.rooms[layout.parent(1).coerceAtLeast(0)]
            val link = layout.linkTo(1)
            val y = layout.floor(1)
            val at = if (link == null || link.alongU) {
                val su = (from.u - room.u).sign.let { if (it == 0) -1 else it }
                layout.pos(room.u + su * (room.halfU - 2), room.v + side * room.halfV, y)
            } else {
                val sv = (from.v - room.v).sign.let { if (it == 0) -1 else it }
                layout.pos(room.u + side * room.halfU, room.v + sv * (room.halfV - 2), y)
            }
            return Sets.standable(level, at) ?: Sets.standable(level, layout.center(1))
        }
        val dx = (town.x - lair.pos.x).toDouble()
        val dz = (town.z - lair.pos.z).toDouble()
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1.0)
        val at = BlockPos.containing(lair.pos.x + 0.5 + dx / len * RING_MARK, lair.pos.y.toDouble(), lair.pos.z + 0.5 + dz / len * RING_MARK)
        if (!level.isLoaded(at)) return null
        return Sets.standable(level, at) ?: Sets.standable(level, BlockPos(at.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.x, at.z), at.z))
    }

    /** A mark no body can be made at: the hold keeps them, and no day body is made for them, while nobody is near (below the world, under [at]). */
    fun placeholder(level: ServerLevel, at: BlockPos): BlockPos = BlockPos(at.x, level.minY, at.z)

    /** Whether [pos] is a placeholder ([placeholder]). */
    fun parked(level: ServerLevel, pos: BlockPos?): Boolean = pos != null && pos.y <= level.minY

    /** Whether someone at [pos] is out of [lair]: within [MOUTH] blocks of a dug mine's way in and no deeper than two below it, or [RING_OUT] from a ring's altar. */
    fun surfaced(lair: Lair, pos: BlockPos): Boolean {
        val dx = (pos.x - lair.pos.x).toDouble()
        val dz = (pos.z - lair.pos.z).toDouble()
        val flat = sqrt(dx * dx + dz * dz)
        return if (lair.delve) flat <= MOUTH && pos.y >= lair.pos.y - 2 else flat > RING_OUT
    }

    /** The mine's own fire, lit or cold, found across the ground from its mouth (in loaded land only). */
    fun fireOf(level: ServerLevel, lair: Lair): BlockPos? = WardfireSites.nearest(level, lair.pos, FIRE_NEAR)

    /** Whether the Wardfire at [pos] burns (false if its land is not loaded). */
    fun lit(level: ServerLevel, pos: BlockPos): Boolean = level.isLoaded(pos) && Wardfires.isLit(level.getBlockState(pos))

    /** [fire] as one number from [mouth] (a byte for each of x, y and z, kept within reach), 0 for none. */
    fun pack(mouth: BlockPos, fire: BlockPos): Int {
        fun b(d: Int): Int = (d.coerceIn(-127, 127) + 128) and 0xFF
        return ((b(fire.x - mouth.x) shl 16) or (b(fire.y - mouth.y) shl 8) or b(fire.z - mouth.z)) + 1
    }

    /** The fire [n] keeps from [mouth], or null for none. */
    fun unpack(mouth: BlockPos, n: Int): BlockPos? {
        if (n <= 0) return null
        val v = n - 1
        return BlockPos(mouth.x + ((v ushr 16) and 0xFF) - 128, mouth.y + ((v ushr 8) and 0xFF) - 128, mouth.z + (v and 0xFF) - 128)
    }

    /** A surface ring's fire, raised as a first visit would raise it, once the land round it is loaded (a dug mine has its own at the foot of the stairs). */
    fun raise(level: ServerLevel, lair: Lair) {
        if (lair.delve || lair.fire || !WardfireSites.enabled()) return
        val reach = 32
        for (cx in ((lair.pos.x - reach) shr 4)..((lair.pos.x + reach) shr 4)) for (cz in ((lair.pos.z - reach) shr 4)..((lair.pos.z + reach) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) return
        }
        Lairs.get(level).raiseFire(level, lair)
    }

    /** The way on into the dark from the first hall (the halls past it), for one who is lost: or the altar, in a ring. */
    fun deeper(level: ServerLevel, lair: Lair): List<BlockPos> {
        val state = lair.dungeon
        if (!lair.delve || state == null) return listOf(lair.pos)
        val layout = Lairs.get(level).layout(lair, state)
        val rooms = (2 until layout.plan.rooms.size).filter { layout.parent(it) >= 1 }.take(2)
        return rooms.map { layout.center(it) }.ifEmpty { listOf(layout.center(minOf(1, layout.plan.rooms.size - 1))) }
    }
}
