package io.github.vaspyyy.guildmark.village.live.build

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.advance.GrandBuilders
import io.github.vaspyyy.guildmark.guild.GuildHall
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.ticks.BlackholeTickAccess
import net.minecraft.world.ticks.LevelTickAccess
import net.minecraft.world.ticks.ScheduledTick
import net.minecraft.world.ticks.TickPriority

/**
 * The one door every village builder puts its blocks through (living-world/villages/design.md section 12.2). The names and
 * signatures of [put], [record] and [afterwards] are the contract of design section 19.3.
 *
 * While no recording is open, [put] sets the block where it is put, as `level.setBlock(pos, state, UPDATE_ALL)` does, and what is
 * to be done [afterwards] is done at once: the builders build as they always did (and do, with `villageBuilds` off or Living
 * Villages off, when none is ever opened). Inside [record] a block put is written down instead, with what setting it would have
 * done to the shapes about it (a fence joining the next, a pane its frame, the top of a tall plant going with its foot), in the
 * order first put, the last state at each place standing; the builder reads the world as it was (the blocks it checks are the
 * world's, never its own, but for the hall's panes, which read back through [stateAt] and [joined]), so a recording comes out
 * block for block what a direct build leaves. When the body is over and it did not fail, [VillageBuilds.accept] makes the
 * recording a site for a crew to lay over days (or, if there is no room for a site, lays it out at once).
 *
 * What a builder wants done [afterwards] (the hall's receptionist, the market's stall-keepers) is done when the crew has laid the
 * last block. A task that must outlast a restart is given as a name and the place it is for ([afterwards] with a kind): that is
 * what is saved with the site, and a [Reviver] says what to do with it.
 */
object BuildSink {
    /** The most blocks a build may be recorded as; more than this is laid at once (the keep is about 1,500). */
    const val CAP = 4000

    /** How deep a cascade of shape updates goes, as `Level.setBlock` has it. */
    private const val LIMIT = 512

    /** The order the six neighbours of a block hear it change, as the game's own (`BlockBehaviour.UPDATE_SHAPE_ORDER`). */
    private val ORDER = arrayOf(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP)

    /** What is to be done once the crew has laid the last block, in a form that can be saved: a [kind] of thing and where it goes ([at], [other], [n]). */
    class After(val kind: String, val at: Long, val other: Long = 0L, val n: Int = 0) {
        companion object {
            val CODEC: Codec<After> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("kind").forGetter(After::kind),
                    Codec.LONG.fieldOf("at").forGetter(After::at),
                    Codec.LONG.optionalFieldOf("other", 0L).forGetter(After::other),
                    Codec.INT.optionalFieldOf("n", 0).forGetter(After::n),
                ).apply(i, ::After)
            }
        }
    }

    /** What to do for one kind of [After]. */
    fun interface Reviver {
        fun run(level: ServerLevel, at: BlockPos, other: BlockPos, n: Int)
    }

    /**
     * What a recording has written down so far: each place put or changed by what was put beside it, in the order it first was, and
     * the state it has now. The world itself is never written while it is open.
     */
    class Recording(val level: ServerLevel, val key: Long, val project: Int) {
        /** Each place (packed), in the order it was first put or changed, and the last state there. */
        val order = LongArrayList()
        val states = ArrayList<BlockState>()
        private val where = Long2IntOpenHashMap().also { it.defaultReturnValue(-1) }

        /** What is to be done afterwards, in the forms that are saved and that are not. */
        val after = ArrayList<After>()
        val closures = ArrayList<() -> Unit>()

        val size: Int get() = states.size

        /** The state at [pos] as the build has it so far: what it put (or changed) there, else the world's. */
        fun stateAt(pos: BlockPos): BlockState {
            val at = where.get(pos.asLong())
            return if (at >= 0) states[at] else level.getBlockState(pos)
        }

        private fun write(pos: BlockPos, state: BlockState) {
            val key = pos.asLong()
            val at = where.get(key)
            if (at >= 0) {
                states[at] = state
            } else {
                where.put(key, states.size)
                order.add(key)
                states.add(state)
            }
        }

        /**
         * Writes down [state] at [pos] as `level.setBlock(pos, state, UPDATE_ALL)` would set it: nothing if it is there already, and the
         * six neighbours told, each taking the shape it would take (and those beside them in turn, [limit] deep), a block that cannot
         * stand any more going and leaving its water, as `Block.updateOrDestroy` and `Level.destroyBlock` have it.
         */
        fun put(pos: BlockPos, state: BlockState, limit: Int = LIMIT) {
            if (!level.isInValidBounds(pos)) return
            if (stateAt(pos) == state) return
            write(pos.immutable(), state)
            if (limit <= 0) return
            for (d in ORDER) changed(pos.relative(d), d.opposite, pos, state, limit - 1)
        }

        private fun changed(at: BlockPos, toward: Direction, from: BlockPos, fromState: BlockState, limit: Int) {
            if (!level.isInValidBounds(at)) return
            val current = stateAt(at)
            val next = current.updateShape(view, ticks, at, toward, from, fromState, level.random)
            if (next == current) return
            if (next.isAir) {
                if (!current.isAir) put(at, current.fluidState.createLegacyBlock(), limit)
            } else {
                put(at, next, limit)
            }
        }

        /** [state] at [pos] given the shape its neighbours give it, as the build has them so far (`Block.updateFromNeighbourShapes`). */
        fun joined(pos: BlockPos, state: BlockState): BlockState {
            var s = state
            for (d in ORDER) {
                val n = pos.relative(d)
                s = s.updateShape(view, ticks, pos, d, n, stateAt(n), level.random)
            }
            return s
        }

        /** The world as the build has it so far, for the blocks' own shape rules to read. */
        private val view: LevelReader = object : LevelReader by level {
            override fun getBlockState(pos: BlockPos): BlockState = stateAt(pos)
            override fun getFluidState(pos: BlockPos): FluidState = stateAt(pos).fluidState
            override fun isEmptyBlock(pos: BlockPos): Boolean = stateAt(pos).isAir
            override fun getBlockEntity(pos: BlockPos): BlockEntity? = if (where.get(pos.asLong()) >= 0) null else level.getBlockEntity(pos)
        }

        /** Ticks a shape rule would ask for go nowhere: nothing is set yet for them to tick. */
        private val ticks: ScheduledTickAccess = object : ScheduledTickAccess {
            override fun <T : Any> createTick(pos: BlockPos, type: T, tickDelay: Int, priority: TickPriority): ScheduledTick<T> = level.createTick(pos, type, tickDelay, priority)
            override fun <T : Any> createTick(pos: BlockPos, type: T, tickDelay: Int): ScheduledTick<T> = level.createTick(pos, type, tickDelay)
            override fun getBlockTicks(): LevelTickAccess<Block> = BlackholeTickAccess.emptyLevelList()
            override fun getFluidTicks(): LevelTickAccess<Fluid> = BlackholeTickAccess.emptyLevelList()
            override fun scheduleTick(pos: BlockPos, type: Block, tickDelay: Int, priority: TickPriority) {}
            override fun scheduleTick(pos: BlockPos, type: Block, tickDelay: Int) {}
            override fun scheduleTick(pos: BlockPos, type: Fluid, tickDelay: Int, priority: TickPriority) {}
            override fun scheduleTick(pos: BlockPos, type: Fluid, tickDelay: Int) {}
        }
    }

    private var open: Recording? = null

    private val revivers = HashMap<String, Reviver>().also {
        it["receptionist"] = Reviver { level, at, other, n -> GuildHall.seat(level, at, other, n.toFloat()) }
        it["stall_keeper"] = Reviver { level, at, _, _ -> GrandBuilders.keeper(level, at) }
    }

    /** Says what to do for [kind] once a build is over (another part's task, saved as its [After]). */
    fun reviver(kind: String, run: Reviver) {
        revivers[kind] = run
    }

    /** The recording open for [level], if any. */
    private fun openFor(level: Level): Recording? = open?.takeIf { it.level === level }

    /** Puts [state] at [pos]: sets it, as `level.setBlock(pos, state, UPDATE_ALL)` does, while no recording is open; written down when one is. */
    fun put(level: Level, pos: BlockPos, state: BlockState) {
        val r = openFor(level)
        if (r != null) {
            r.put(pos, state)
            return
        }
        level.setBlock(pos, state, Block.UPDATE_ALL)
    }

    /** The state at [pos] as the build has it so far: the world's while no recording is open; with what has been put, while one is. */
    fun stateAt(level: Level, pos: BlockPos): BlockState = openFor(level)?.stateAt(pos) ?: level.getBlockState(pos)

    /** [state] at [pos] with the shape its neighbours give it (`Block.updateFromNeighbourShapes`), its neighbours as the build has them so far. */
    fun joined(level: ServerLevel, pos: BlockPos, state: BlockState): BlockState =
        openFor(level)?.joined(pos, state) ?: Block.updateFromNeighbourShapes(state, level, pos)

    /** Whether a recording is open. */
    fun recording(): Boolean = open != null

    /**
     * Runs [body], recording what it puts for the village [key]'s [project] when a recording can be opened ([VillageBuilds.canRecord]);
     * otherwise it only runs it. A body that throws or that fails (a `String` is the builders' way of saying why, `false` that there
     * was no room) puts nothing: its recording is thrown away. A recording that is kept becomes a site ([VillageBuilds.accept]).
     */
    fun <T> record(level: ServerLevel, key: Long, project: Int, body: () -> T): T {
        if (open != null || !VillageBuilds.canRecord(level, key)) return body()
        // The village's project still going up is finished first: this one is built on what that one leaves
        VillageBuilds.beforeRecord(level, key)
        val r = Recording(level, key, project)
        open = r
        val result = try {
            body()
        } finally {
            open = null
        }
        if (result is String || result == false) return result
        try {
            VillageBuilds.accept(level, r)
        } catch (e: RuntimeException) {
            // A site that can't be made costs the show, not the project: it goes up at once, as it always did
            Guildmark.LOGGER.warn("A village project could not be made a site; it is laid at once", e)
            VillageBuilds.layAtOnce(level, r)
        }
        return result
    }

    /** Does [task] when the crew has laid the last block (the closure is not saved: after a restart it is not done); with no recording open, at once. */
    fun afterwards(task: () -> Unit) {
        val r = open
        if (r == null) task() else r.closures.add(task)
    }

    /** Does what the [Reviver] of [kind] says at [at] when the crew has laid the last block, and still after a restart; with no recording open, at once. */
    fun afterwards(level: ServerLevel, kind: String, at: BlockPos, other: BlockPos = BlockPos.ZERO, n: Int = 0) {
        val r = openFor(level)
        if (r == null) revive(level, After(kind, at.asLong(), other.asLong(), n)) else r.after.add(After(kind, at.asLong(), other.asLong(), n))
    }

    /** Does one saved task. */
    fun revive(level: ServerLevel, task: After) {
        val run = revivers[task.kind] ?: return
        try {
            run.run(level, BlockPos.of(task.at), BlockPos.of(task.other), task.n)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("A task left for the end of a village build ({}) failed", task.kind, e)
        }
    }
}
