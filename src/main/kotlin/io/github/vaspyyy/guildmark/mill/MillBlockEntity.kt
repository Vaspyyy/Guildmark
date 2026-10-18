package io.github.vaspyyy.guildmark.mill

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.plan.Dye
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A mill's hub's block entity. Kept with the world: the capital it grinds for ([capital], whose market says when it
 * grinds), its city's [style] (the wood its sails or its wheel are built of), the colour of the sails' [cloth], and for a
 * windmill which of its capital's windmills it is ([rank] of [count]), so that only one of them shows the broken sail.
 * On a client it also keeps how the sails or the wheel are turning just now, eased from what its block's [MillBlock.WORK]
 * says: the server only ever tells it what the mill is doing, never where the sails are.
 */
class MillBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(MillRegistry.MILL.get(), pos, state) {
    /** The middle of the capital it grinds for (the key to its market); null for a hub nobody set up, one placed by hand. */
    var capital: BlockPos? = null
        private set

    /** The wood its sails or its wheel are built of: its city's style. */
    var style: CityStyle = CityStyle.TEMPERATE
        private set

    /** The colour of its sails' cloth. */
    var cloth: Dye = Dye.WHITE
        private set

    /** Which of its capital's windmills it is, counted from 0: only one of them shows a broken sail. */
    var rank = 0
        private set

    /** How many windmills its capital has. */
    var count = 0
        private set

    /** Whether it turns sails or a wheel. */
    val kind: MillKind get() = (blockState.block as? MillBlock)?.kind ?: MillKind.SAILS

    /** Sets a hub up for the capital it grinds for, as the world generates it or as an older capital's mill is swapped for it. */
    fun found(capital: BlockPos, style: CityStyle, cloth: Dye, rank: Int, count: Int) {
        this.capital = capital
        this.style = style
        this.cloth = cloth
        this.rank = rank
        this.count = count
        setChanged()
        val level = level
        if (level is ServerLevel) level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun onLoad() {
        super.onLoad()
        val level = level ?: return
        if (level is ServerLevel) Mills.add(level, this) else if (level.isClientSide()) LOADED.add(this)
    }

    override fun setRemoved() {
        super.setRemoved()
        val level = level ?: return
        if (level is ServerLevel) Mills.remove(level, this) else if (level.isClientSide()) LOADED.remove(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        capital?.let { output.putLong("capital", it.asLong()) }
        output.putString("style", style.name.lowercase())
        output.putString("cloth", cloth.name.lowercase())
        output.putInt("rank", rank)
        output.putInt("count", count)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        capital = input.getLong("capital").map(BlockPos::of).orElse(null)
        style = CityStyle.entries.firstOrNull { it.name.equals(input.getStringOr("style", ""), true) } ?: CityStyle.TEMPERATE
        cloth = Dye.entries.firstOrNull { it.name.equals(input.getStringOr("cloth", ""), true) } ?: Dye.WHITE
        rank = input.getIntOr("rank", 0)
        count = input.getIntOr("count", 0)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    // ---- How it turns, on a client ----

    /**
     * How far round the sails or the wheel have turned, in radians: the sails turn against the clock seen from in front,
     * the wheel whichever way its stream runs ([spin]).
     */
    var turn = 0f
        private set

    /** Where [turn] was a tick ago. */
    var turnO = 0f
        private set

    /** How fast it turns just now, in radians a tick. */
    var speed = 0f
        private set

    /** Which way the wheel turns, seen from in front: 1 with the clock, -1 against it. */
    var spin = 1
        private set

    /** How far the sails' cloth is furled up along the whips, from 0 spread to 1 furled. */
    var furl = 0f
        private set

    /** Where [furl] was a tick ago. */
    var furlO = 0f
        private set

    /** Which of the four sails (counted round from the first) is broken and hanging, or -1. */
    var broken = -1
        private set

    /** How far the broken sail's hanging end swings from straight down, in radians. */
    var swing = 0f
        private set

    /** Where [swing] was a tick ago. */
    var swingO = 0f
        private set
    private var swingSpeed = 0f

    /** Where braked sails come to rest, once they're braking (NaN while they turn or stand), and how long they've stood. */
    private var stop = Float.NaN
    private var rested = 0
    private var started = false

    /** Eases the sails or the wheel along one tick toward what [state]'s work says. */
    fun clientTick(level: Level, state: BlockState) {
        val on = Mills.enabled()
        val work = if (on) state.getValue(MillBlock.WORK) else MillWork.IDLE
        if (!started) {
            started = true
            start(work)
        }
        turnO = turn
        furlO = furl
        swingO = swing
        if (kind == MillKind.WHEEL) wheel(work, on) else sails(work, level)
        // Whole turns at a time, so nothing jumps: a long-running float would lose its fine steps
        if (turn > WRAP || turn < -WRAP) {
            val by = if (turn > 0) WRAP else -WRAP
            turn -= by
            turnO -= by
            if (!stop.isNaN()) stop -= by
        }
    }

    /** How a mill first seen is already going: a working one at speed, a braked one at rest with a sail upright and its cloth furled. */
    private fun start(work: MillWork) {
        val seed = blockPos.asLong() * -7046029254386353131L
        val quarter = ((seed ushr 40) and 3L).toInt()
        spin = if ((seed ushr 33) and 1L == 0L) 1 else -1
        if (kind == MillKind.WHEEL) {
            turn = ((seed ushr 20) and 0xFFFF).toFloat() / 0xFFFF * TWO_PI
            speed = if (work == MillWork.WORKING) WHEEL_WORK else WHEEL_IDLE
            return
        }
        if (work == MillWork.WORKING) {
            turn = ((seed ushr 20) and 0xFFFF).toFloat() / 0xFFFF * TWO_PI
            speed = SAIL_SPEED
            return
        }
        turn = quarter * QUARTER
        furl = 1f
        rested = FURL_AFTER
        if (work == MillWork.BROKEN) broken = brokenArm(quarter)
    }

    private fun sails(work: MillWork, level: Level) {
        if (work == MillWork.WORKING) {
            stop = Float.NaN
            rested = 0
            broken = -1
            // The cloth is spread before the brake comes off
            furl = max(0f, furl - UNFURL)
            if (furl < 0.3f) speed = min(SAIL_SPEED, speed + SAIL_ACCEL)
        } else {
            if (speed > 0f) {
                // Braked to come to rest with a sail upright: the first such place past where it would coast to anyway
                if (stop.isNaN()) stop = ceil((turn + speed * speed / (2f * SAIL_ACCEL)) / QUARTER) * QUARTER
                val left = stop - turn
                if (left <= speed) {
                    speed = left
                } else {
                    speed = max(speed - speed * speed / (2f * left), MIN_SPEED)
                }
            }
            if (work == MillWork.BROKEN && broken < 0) broken = brokenArm(((if (stop.isNaN()) turn else stop) / QUARTER).roundToInt())
            if (work != MillWork.BROKEN) broken = -1
        }
        turn += speed
        if (!stop.isNaN() && turn >= stop - 1e-5f) {
            turn = stop
            speed = 0f
            stop = Float.NaN
        }
        if (work != MillWork.WORKING && speed == 0f) {
            rested++
            if (rested > FURL_AFTER) furl = min(1f, furl + FURL)
        }
        if (broken >= 0) {
            // The broken end swings on what's left of it, the wind catching it now and then
            swingSpeed += -SWING_PULL * swing - SWING_DAMP * swingSpeed + (level.random.nextFloat() - 0.5f) * SWING_GUST
            swing += swingSpeed
        } else {
            swing = 0f
            swingSpeed = 0f
        }
    }

    private fun wheel(work: MillWork, on: Boolean) {
        val target = when {
            !on -> 0f
            work == MillWork.WORKING -> WHEEL_WORK
            else -> WHEEL_IDLE
        }
        speed += (target - speed) * WHEEL_EASE
        if (target == 0f && speed < 1e-4f) speed = 0f
        // Grinding, it labours: each spoke's paddle that bites into the stream pushes it on a little harder
        val surge = if (work == MillWork.WORKING) WORK_SURGE else IDLE_SURGE
        turn += spin * speed * (1f + surge * sin(SURGE_BEATS * turn))
    }

    /**
     * The sail that's shown broken when the sails come to rest [quarter] quarter turns round: the one lying level to the
     * left seen from in front, whose cloth is on its upper side, so that its broken end hangs clear of the cloth.
     */
    private fun brokenArm(quarter: Int): Int = Math.floorMod(quarter + 3, 4)

    companion object {
        /** Every mill hub loaded on this client, for the sounds by them (client/mill/MillSounds). */
        val LOADED = HashSet<MillBlockEntity>()

        /** A whole turn, in radians. */
        const val TWO_PI = (2.0 * PI).toFloat()

        /** A quarter turn, in radians: from one sail to the next. */
        const val QUARTER = (PI / 2.0).toFloat()
        private const val WRAP = TWO_PI * 64f

        /** The sails at work, in radians a tick: a whole turn in eight seconds, up to speed in about six and braked to rest in about as long. */
        const val SAIL_SPEED = TWO_PI / 160f
        private const val SAIL_ACCEL = SAIL_SPEED / 120f
        private const val MIN_SPEED = SAIL_SPEED / 400f

        /** Ticks the sails stand before the cloth is furled, and the share of it furled or spread each tick. */
        private const val FURL_AFTER = 100
        private const val FURL = 1f / 80f
        private const val UNFURL = 1f / 60f

        /** The wheel turning with the stream while the mill is idle, in radians a tick: a turn in sixteen seconds. */
        const val WHEEL_IDLE = TWO_PI / 320f

        /** The wheel while the mill grinds, in radians a tick: a turn in eight seconds. */
        const val WHEEL_WORK = TWO_PI / 160f

        // How quickly the wheel takes up a new pace, and how its pace surges as each paddle bites
        private const val WHEEL_EASE = 0.012f
        private const val SURGE_BEATS = 8f
        private const val WORK_SURGE = 0.16f
        private const val IDLE_SURGE = 0.04f

        // The broken sail's swing: how hard it's pulled back to hanging, how quickly it settles, how hard a gust pushes it
        private const val SWING_PULL = 0.03f
        private const val SWING_DAMP = 0.04f
        private const val SWING_GUST = 0.006f
    }
}
