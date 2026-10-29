package io.github.vaspyyy.guildmark.trade

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlockEntity
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks
import io.github.vaspyyy.guildmark.economy.works.build.WorksFacingBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/*
 * The Counting House's fittings as blocks (living-world/trader/design.md, Part 5; build part F2 draws and places them, part G works them):
 * the strongbox by the scribe's desk (and the shut one in the owner's room), the counting frame on the desk, the map of the roads on the
 * back wall and the bell on its post in the yard. Their ids, models and blockstates are art stream X3's (`art/scripts/store_art.py` and
 * `house_art.py`); they are registered in [TradeBlocks], laid by the builders from the drawing's markers, and drawn moving by
 * client/trade's renderers from what their block entities keep here. Each keeps only what its renderer needs, set by the House's day
 * ([HouseLife]) and, once it is written, by part G; every motion runs from the times kept, so nothing snaps and every client sees the same.
 */

/** The strongbox by the scribe's desk: using it, its owner lifts the lid and looks in ([TradeBlocks.used]). */
class StrongboxBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = StrongboxBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        return if (TradeBlocks.used(level, player, pos)) InteractionResult.SUCCESS else InteractionResult.PASS
    }

    companion object {
        // X3's model facing north: the iron-bound box on its shoes, its back toward the wall
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(0.0, 0.0, 1.8, 16.0, 7.0, 11.5))
    }
}

/**
 * The strongbox's lid: lifted at [opened] (game time; 0, never: shut) and let fall again at [shuts] (0 or before [opened]: not yet; a time
 * still to come, already told). The renderer runs the lid and its hasp from these, so a lid lifted and dropped in one breath needs one
 * word to the clients, not two.
 */
class StrongboxBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(TradeBlocks.STRONGBOX_ENTITY.get(), pos, state) {
    var opened: Long = 0L
        private set
    var shuts: Long = 0L
        private set

    /** Whether the lid is up (or going up) now. */
    val open: Boolean get() = opened != 0L && (shuts <= opened || (level?.gameTime ?: 0L) < shuts)

    /** The lid lifted now, to stay up until [shut] (or, [ticks] more than 0, to fall again by itself that many ticks on); and [sound]. */
    fun lift(ticks: Int = 0, sound: Boolean = true) {
        val now = level?.gameTime ?: return
        if (!open) opened = now
        shuts = if (ticks > 0) now + ticks else 0L
        WorksBlocks.sync(this)
        if (sound) (level as? ServerLevel)?.playSound(null, blockPos, TradeSounds.get("trade.strongbox"), SoundSource.BLOCKS, 0.9f, 0.95f + (level?.random?.nextFloat() ?: 0f) * 0.1f)
    }

    /** The lid let fall now (if it is up). */
    fun shut() {
        val now = level?.gameTime ?: return
        if (!open) return
        shuts = now
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putLong("opened", opened)
        output.putLong("shuts", shuts)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        opened = input.getLongOr("opened", 0L)
        shuts = input.getLongOr("shuts", 0L)
    }
}

/** The shut strongbox in the owner's room upstairs: the whole box with its lid on, still. */
class StrongboxShutBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties) {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    companion object {
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(0.0, 0.0, 1.1, 16.0, 10.6, 12.5))
    }
}

/** The counting frame on the scribe's high desk: using it is using the House's desk ([TradeBlocks.used]). */
class CountingFrameBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CountingFrameBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        return if (TradeBlocks.used(level, player, pos)) InteractionResult.SUCCESS else InteractionResult.PASS
    }

    companion object {
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(0.7, 0.0, 4.9, 15.3, 15.2, 11.1))
    }
}

/**
 * What the counting frame shows: a number in its six rows of nine beads, row 0 (the lowest) its units, each row's count the beads pushed
 * to the counted side ([rows]); the rows as they stood before ([was]) and when the beads were set sliding from those to these ([moved],
 * game time; 0: always so). A push runs along a row a bead at a time, and the rows that change go one after another from the lowest,
 * [ROW_GAP] ticks apart (StoreArt's numbers, which the renderer reads).
 */
class CountingFrameBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(TradeBlocks.FRAME_ENTITY.get(), pos, state) {
    var rows: IntArray = IntArray(ROWS)
        private set
    var was: IntArray = IntArray(ROWS)
        private set
    var moved: Long = 0L
        private set

    /** The number the frame shows. */
    val shown: Long get() = rows.foldIndexed(0L) { i, n, d -> n + d * POWERS[i] }

    /**
     * The frame set to show [n] (at most [MOST]): the beads slid there now ([show]), or set at once as though long since. Gives the rows
     * that change, in the order they move, with the tick (after now) each starts; empty if it showed [n] already.
     */
    fun show(n: Long, show: Boolean): List<Pair<Int, Int>> {
        val next = IntArray(ROWS) { ((n.coerceIn(0L, MOST) / POWERS[it]) % 10L).toInt() }
        if (next.contentEquals(rows)) return listOf()
        was = if (show) rows.copyOf() else next.copyOf()
        rows = next
        moved = if (show) level?.gameTime ?: 0L else 0L
        WorksBlocks.sync(this)
        return if (show) starts(was, rows) else listOf()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putIntArray("rows", rows)
        output.putIntArray("was", was)
        output.putLong("moved", moved)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        rows = input.getIntArray("rows").map { fit(it) }.orElse(IntArray(ROWS))
        was = input.getIntArray("was").map { fit(it) }.orElse(rows.copyOf())
        moved = input.getLongOr("moved", 0L)
    }

    companion object {
        const val ROWS = 6
        const val BEADS = 9

        /** The most a frame of six rows of nine shows; ticks between one changing row's start and the next's (StoreArt.BEAD_ROW_GAP). */
        const val MOST = 999_999L
        const val ROW_GAP = 5

        private val POWERS = LongArray(ROWS) { var p = 1L; repeat(it) { p *= 10L }; p }

        private fun fit(a: IntArray): IntArray = IntArray(ROWS) { a.getOrElse(it) { 0 }.coerceIn(0, BEADS) }

        /** The rows that change from [from] to [to], lowest first, each with the tick its push starts. */
        fun starts(from: IntArray, to: IntArray): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            for (r in 0 until ROWS) if (from[r] != to[r]) out.add(r to out.size * ROW_GAP)
            return out
        }
    }
}

/** The map of the roads on the back wall: this block is the middle of the bottom row of a panel three blocks wide and two high. */
class HouseMapBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = HouseMapBlockEntity(pos, state)

    companion object {
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(0.0, 0.0, 9.6, 16.0, 16.0, 16.0))
    }
}

/**
 * What the map shows: a brass pin for each capital its owner knows ([pins]: the capital's key, its pin's size 0 to 3, when it was set and
 * when it was taken out), a thread from the House's own town (the size 3 pin) to each town where they keep a stall or a Store (the size
 * 2 pins), and a wagon in the House's [colour] (a dye id) on each trip under way ([trips]). F2 sets the pins and the colour (HouseLife);
 * the trips are part G's: until it writes them the list stays empty and no wagon runs.
 */
class HouseMapBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(TradeBlocks.MAP_ENTITY.get(), pos, state) {
    /** A pin: the capital [capital] (its heart's key), size [size] (0 to 3), set at [born] (game time; 1: there since before anyone looked), taken out at [gone] (0: not). */
    class MapPin(val capital: Long, val size: Int, val born: Long, val gone: Long)

    /** A wagon on its way from the capital [from] to [to], setting out at [start] (game time) and coming in [ticks] later. */
    class MapTrip(val from: Long, val to: Long, val start: Long, val ticks: Int)

    var pins: List<MapPin> = listOf()
        private set
    var trips: List<MapTrip> = listOf()
        private set
    var colour: Int = 14
        private set

    /** The renderer's own store of how it last framed the map (it eases to a new frame rather than jumping), kept with the map it is for. */
    @JvmField
    var view: Any? = null

    /**
     * The pins set to [next] (capital key to size): a new one set now ([show]: it pops in, each a little after the last) or as though
     * long since; one no longer there taken out now (it shrinks away) and forgotten at the next setting after that. True if anything changed.
     */
    fun pin(next: Map<Long, Int>, show: Boolean): Boolean {
        val now = level?.gameTime ?: return false
        val old = pins.associateBy { it.capital }
        val out = ArrayList<MapPin>()
        var fresh = 0
        var changed = false
        for ((capital, size) in next) {
            val was = old[capital]
            when {
                was != null && was.gone == 0L -> {
                    out.add(if (was.size == size) was else MapPin(capital, size, was.born, 0L))
                    if (was.size != size) changed = true
                }
                else -> {
                    out.add(MapPin(capital, size, if (show) now + STAGGER * fresh++ else 1L, 0L))
                    changed = true
                }
            }
        }
        for (was in pins) {
            if (was.capital in next) continue
            if (was.gone == 0L) {
                out.add(MapPin(was.capital, was.size, was.born, if (show) now else 1L))
                changed = true
            } else if (now - was.gone < FORGET) out.add(was) else changed = true
        }
        if (!changed) return false
        pins = out
        WorksBlocks.sync(this)
        return true
    }

    /** The House's colour (a dye id), for its wagons. */
    fun colour(colour: Int) {
        if (this.colour == colour) return
        this.colour = colour
        WorksBlocks.sync(this)
    }

    /** The trips under way (part G's; at most [TRIPS] are drawn). */
    fun trips(next: List<MapTrip>) {
        trips = next.take(TRIPS)
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.store("pins", PIN.listOf(), pins)
        output.store("trips", TRIP.listOf(), trips)
        output.putInt("colour", colour)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        pins = input.read("pins", PIN.listOf()).orElse(listOf())
        trips = input.read("trips", TRIP.listOf()).orElse(listOf())
        colour = input.getIntOr("colour", 14)
    }

    companion object {
        /** The most pins and trips a map keeps; ticks between one new pin's popping in and the next's; ticks a pin taken out is kept to shrink away. */
        const val PINS = 16
        const val TRIPS = 6
        const val STAGGER = 6L
        const val FORGET = 100L

        private val PIN: Codec<MapPin> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter { it.capital },
                Codec.INT.optionalFieldOf("size", 0).forGetter { it.size },
                Codec.LONG.optionalFieldOf("born", 1L).forGetter { it.born },
                Codec.LONG.optionalFieldOf("gone", 0L).forGetter { it.gone },
            ).apply(i, ::MapPin)
        }

        private val TRIP: Codec<MapTrip> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("from").forGetter { it.from },
                Codec.LONG.fieldOf("to").forGetter { it.to },
                Codec.LONG.optionalFieldOf("start", 0L).forGetter { it.start },
                Codec.INT.optionalFieldOf("ticks", 1).forGetter { it.ticks },
            ).apply(i, ::MapTrip)
        }
    }
}

/** The bell on its bracket, hung from the post behind it: anyone may pull its rope ([HouseBellBlockEntity.ring]). */
class HouseBellBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = HouseBellBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        (level.getBlockEntity(pos) as? HouseBellBlockEntity)?.ring()
        return InteractionResult.SUCCESS
    }

    companion object {
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(3.0, 7.0, 2.4, 13.0, 16.0, 16.0))
    }
}

/** When the bell's rope was last pulled ([rung], game time; 0: never): the renderer swings it, its clapper and its rope from that. */
class HouseBellBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(TradeBlocks.BELL_ENTITY.get(), pos, state) {
    var rung: Long = 0L
        private set

    /** Whether a ring is still going (a pull now would be lost in it). */
    val ringing: Boolean get() = rung != 0L && (level?.gameTime ?: 0L) - rung < RING

    /** The rope pulled now, if the bell has done ringing: it swings and rings (`trade.house_bell`). False if it was still ringing. */
    fun ring(): Boolean {
        val level = level as? ServerLevel ?: return false
        if (ringing) return false
        rung = level.gameTime
        WorksBlocks.sync(this)
        level.playSound(null, blockPos, TradeSounds.get("trade.house_bell"), SoundSource.BLOCKS, 1.2f, 0.97f + level.random.nextFloat() * 0.06f)
        return true
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putLong("rung", rung)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        rung = input.getLongOr("rung", 0L)
    }

    companion object {
        /** Ticks a ring lasts (HouseArt.RING_TICKS). */
        const val RING = 140L
    }
}
