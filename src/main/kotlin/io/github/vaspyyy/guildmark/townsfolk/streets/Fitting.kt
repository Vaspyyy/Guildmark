package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.festival.FestivalRegistry
import io.github.vaspyyy.guildmark.festival.FestivalTrimBlock
import io.github.vaspyyy.guildmark.festival.TrimLook
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scened
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * One piece of the streets' work at one place, as a resident does it: up a ladder set against a wall (or a lamp's
 * post), or from the ground for what goes on a doorstep. [piece] is where it goes; the worker sets the ladder from
 * [foot], its bottom at [ladder] facing [facing] (away from the wall, as a vanilla ladder's facing), and climbs [rise]
 * blocks up it; with no ladder they stand at [foot] facing [facing]. They look at [look] while they work, holding
 * [holding]. [up] puts the piece up now (true if it stands), [untie] begins taking it down (its coming-down look), and
 * [down] takes it down now. Something set down from the ground may then be knocked in, [stake] strokes of STAKE (a
 * board's post), and once it's up they say [words] (a voice family) to whoever's by, [why] being why.
 */
class Station(
    val piece: BlockPos,
    val foot: BlockPos,
    val ladder: BlockPos?,
    val facing: Direction,
    val rise: Int,
    val look: Vec3,
    val holding: ItemStack,
    val up: (ServerLevel) -> Boolean,
    val untie: ((ServerLevel) -> Unit)?,
    val down: (ServerLevel) -> Unit,
    val stake: Int = 0,
    val words: String? = null,
    val why: String = "",
) {
    val key: Long get() = piece.asLong()
}

/**
 * The residents who put the streets' pieces up and take them down (the dressing's, and the "To let" boards'): each
 * takes a round of a few [Station]s near one another as a part played aside from their day ([Aside]), carrying the
 * ladder from one to the next, setting it against the wall (PUT_DOWN), climbing it (CLIMB), hanging or untying the
 * piece (HANG), climbing down and taking the ladder up again (PICK_UP); a pot or a board is set down from the ground
 * (PUT_DOWN, and a board knocked in, STAKE) or picked up (PICK_UP). A child near by points at what's gone up and
 * cheers, saying so (`child.festoons`). Whatever ends their part early, they're set down safe on the ground and the
 * ladder goes with them.
 */
object Fitting {
    /** The ladders standing now (their blocks, by position): the dressing's record keeps them only while they're here. */
    val ladders = HashSet<Long>()

    /** When a child last cheered something going up in each capital (by column). */
    private val cheered = HashMap<Long, Long>()

    /** The trades that go up ladders for the town: not the watch, the gentry, the knights, the mages or the priests. */
    private val HANDS = setOf(TownRole.CITIZEN, TownRole.SMITH, TownRole.BAKER, TownRole.MERCHANT, TownRole.FARMER, TownRole.FISHER, TownRole.INNKEEPER)

    /**
     * Someone of the capital at [heart] free to take a round near [near] (within [REACH] blocks), nearest first, not
     * one of [not]; with [loose], anyone of its people up and about who's in no one's charge and no scene, whatever
     * they're at (the work's been waiting for someone free a while).
     */
    fun hire(level: ServerLevel, near: BlockPos, heart: BlockPos, not: Set<Int>, loose: Boolean, roles: Set<TownRole> = HANDS): Townsperson? {
        val at = Vec3.atCenterOf(near)
        return level.getEntitiesOfClass(Townsperson::class.java, AABB(near).inflate(REACH, 8.0, REACH)) { p ->
            p.id !in not && p.role in roles && p.life?.places?.capital?.center == heart && (if (loose) able(p) else Aside.free(p, seated = true))
        }.minByOrNull { it.distanceToSqr(at) }
    }

    /** Whether [p] can be taken off what they're at for a round: up and about, in nobody's charge, in no scene. */
    private fun able(p: Townsperson): Boolean =
        p.isAlive && !p.isRemoved && p.director == null && p.captiveOf < 0 && !p.isLeader && !p.watcher && !p.crowd &&
            !p.isPassenger && !p.isSleeping && p.attending == null && p.target == null && p.activity != Activity.FLEE &&
            p.carried.isEmpty && p.routine.task !is Scened && Days.life(p) != null

    /**
     * Sends [p] round [stations] (in order) to put them up ([putting]) or take them down; [onEnd] hears the round end,
     * however it ends. True if they took it.
     */
    fun send(level: ServerLevel, p: Townsperson, stations: List<Station>, putting: Boolean, column: Long, onEnd: () -> Unit): Boolean {
        if (stations.isEmpty()) return false
        val aside = Aside(listOf(Fit(stations, putting, column)), reach = Aside.REACH, most = MOST, fresh = true)
        aside.onEnd = onEnd
        if (Aside.take(p, aside, seated = true)) return true
        if (!able(p)) return false
        // Taken off what they were at (it's waited long enough for someone free): their day picks up afresh after
        p.routine.interrupt()
        p.navigation.stop()
        p.director = aside
        return true
    }

    /**
     * A child near [at] (something just gone up in [column]'s streets) turns to it, points and cheers, saying so; at
     * most once in [CHEER_EVERY] ticks a capital.
     */
    fun cheer(level: ServerLevel, column: Long, at: Vec3, heart: BlockPos) {
        val now = level.gameTime
        if (now - (cheered[column] ?: Long.MIN_VALUE / 2) < CHEER_EVERY) return
        val child = level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(at, CHEER_REACH * 2, 8.0, CHEER_REACH * 2)) { p ->
            p.role == TownRole.CHILD && p.life?.places?.capital?.center == heart && Aside.free(p)
        }.minByOrNull { it.distanceToSqr(at) } ?: return
        val aside = Aside(listOf(
            Turn({ at }, 16),
            Play(Activity.POINT, at = { at }, first = { c -> Words.say(c, "child.festoons", "the streets dressed for good times") }),
            Play(Activity.CHEER, at = { at }),
            Play(Activity.JUMP, at = { at }),
        ), most = 200, fresh = false)
        if (Aside.take(child, aside)) cheered[column] = now
    }

    /** The ladder at [station] set up (its blocks, a trim's ladder look facing out from the wall) as [column]'s piece [run]. */
    internal fun setLadder(level: ServerLevel, station: Station, column: Long, run: Long): Boolean {
        val bottom = station.ladder ?: return true
        val state = FestivalRegistry.TRIM.get().defaultBlockState().setValue(FestivalTrimBlock.LOOK, TrimLook.LADDER).setValue(FestivalTrimBlock.FACING, station.facing)
        val set = ArrayList<BlockPos>()
        for (k in 0..station.rise) {
            val pos = bottom.above(k)
            ladders.add(pos.asLong())
            if (!Streets.place(level, pos, state, column, Streets.DRESSING, run, solid = false)) {
                ladders.remove(pos.asLong())
                for (s in set) {
                    Streets.restore(level, s, column, Streets.DRESSING)
                    ladders.remove(s.asLong())
                }
                return false
            }
            set.add(pos)
        }
        level.playSound(null, bottom, SoundEvents.LADDER_PLACE, SoundSource.NEUTRAL, 0.9f, 0.95f)
        return true
    }

    /** The ladder at [station] taken up again. */
    internal fun takeLadder(level: ServerLevel, station: Station, column: Long) {
        val bottom = station.ladder ?: return
        for (k in station.rise downTo 0) {
            val pos = bottom.above(k)
            ladders.remove(pos.asLong())
            Streets.restore(level, pos, column, Streets.DRESSING)
        }
        level.playSound(null, bottom, SoundEvents.LADDER_BREAK, SoundSource.NEUTRAL, 0.7f, 1.05f)
    }

    /**
     * Whether the ladder is free to stand at [station]: air (or ours) all the way up, with something to lean on behind
     * it all the way up (a wall, a lamp's post), not an open doorway or thin air.
     */
    fun clear(level: ServerLevel, station: Station): Boolean {
        val bottom = station.ladder ?: return true
        for (k in 0..station.rise) {
            val pos = bottom.above(k)
            if (!level.isLoaded(pos)) return false
            val state = level.getBlockState(pos)
            if (!state.isAir && !(state.block is FestivalTrimBlock && state.getValue(FestivalTrimBlock.LOOK) == TrimLook.LADDER) && !state.canBeReplaced()) return false
            if (!state.fluidState.isEmpty) return false
            if (level.getBlockState(pos.relative(station.facing.opposite)).isAir) return false
        }
        return true
    }

    fun forget() {
        ladders.clear()
        cheered.clear()
    }

    /** How far off a worker is fetched from, the longest a round runs, and children cheering: how often, and how near. */
    const val REACH = 28.0
    private const val MOST = 6000
    private const val CHEER_EVERY = 600L
    private const val CHEER_REACH = 14.0

    /**
     * A round of [stations] put up ([putting]) or taken down for [column]: the move that has charge of the worker for
     * all of it.
     */
    private class Fit(private val stations: List<Station>, private val putting: Boolean, private val column: Long) : Move() {
        private enum class S { WALK, SET, ON, UP, WORK, DOWN, OFF, TAKE, GROUND, STAKE, DONE }

        private var s = S.WALK
        private var t = 0
        private var k = 0
        private var walker: Walker? = null
        private var y = 0.0
        private var placed = false
        private var untied = false
        private var laddered = false

        private val station: Station? get() = stations.getOrNull(k)

        override fun start(p: Townsperson, a: Aside) {
            walker = Walker(p)
            go(p)
        }

        /** Off to the station they're on, carrying what it needs. */
        private fun go(p: Townsperson) {
            val st = station ?: return finish(p)
            s = S.WALK
            t = 0
            placed = false
            untied = false
            laddered = false
            p.carried = when {
                st.ladder != null -> ItemStack(Items.LADDER)
                putting -> st.holding.copy()
                else -> ItemStack.EMPTY
            }
            p.act(if (p.carried.isEmpty) Activity.NONE else Activity.CARRY)
            walker?.go(st.foot, Jobs.WALK, exact = true)
        }

        override fun tick(p: Townsperson, a: Aside, t0: Int): Boolean {
            val level = p.level() as? ServerLevel ?: return false
            val st = station ?: return false
            t++
            when (s) {
                S.WALK -> {
                    if (!p.carried.isEmpty && p.activity != Activity.CARRY && (p.activity.loop || p.actDone)) p.act(Activity.CARRY)
                    val w = walker ?: return false
                    val state = w.tick()
                    if (state == Walker.State.WALKING && t < 1200) return true
                    w.stop()
                    if (state == Walker.State.FAILED || t >= 1200 || p.blockPosition().distManhattan(st.foot) > 2) return skip(p)
                    p.face(st.facing.opposite)
                    if (st.ladder == null) {
                        // From the ground: set down (or picked up) where it goes
                        p.face(p.yawTo(Vec3.atCenterOf(st.piece)))
                        p.act(if (putting) Activity.PUT_DOWN else Activity.PICK_UP)
                        s = S.GROUND
                    } else {
                        if (!clear(level, st)) return skip(p)
                        p.act(Activity.PUT_DOWN)
                        s = S.SET
                    }
                    t = 0
                }
                S.GROUND -> {
                    p.lookAt(st.look)
                    if (t == GROUND_AT) {
                        if (putting) {
                            placed = st.up(level)
                            p.carried = ItemStack.EMPTY
                            if (placed && st.stake == 0) cheer(level, column, st.look, heart(p))
                        } else {
                            st.down(level)
                            p.carried = st.holding.copy()
                        }
                    }
                    if (t >= Activity.PUT_DOWN.ticks + 6) {
                        if (putting && placed && st.stake > 0) {
                            // Knocked in, a stroke at a time
                            s = S.STAKE
                            t = 0
                            p.act(Activity.STAKE)
                        } else {
                            if (putting && placed) st.words?.let { Words.say(p, it, st.why) }
                            next(p)
                        }
                    }
                }
                S.STAKE -> {
                    p.lookAt(st.look)
                    if (p.activity != Activity.STAKE) p.act(Activity.STAKE)
                    if (t % Activity.STAKE.ticks == STRIKE_AT) {
                        level.playSound(null, st.piece, SoundEvents.WOOD_HIT, SoundSource.NEUTRAL, 0.7f, 0.85f + level.random.nextFloat() * 0.1f)
                    }
                    if (t >= Activity.STAKE.ticks * st.stake) {
                        p.act(Activity.NONE)
                        p.face(st.facing)
                        st.words?.let { Words.say(p, it, st.why) }
                        next(p)
                    }
                }
                S.SET -> {
                    p.face(st.facing.opposite)
                    if (t == SET_AT) {
                        laddered = setLadder(level, st, column, Dressing.ladderRun(level, column))
                        p.carried = ItemStack.EMPTY
                        if (!laddered) return skip(p)
                    }
                    if (t >= Activity.PUT_DOWN.ticks + 4) {
                        s = S.ON
                        t = 0
                        y = st.ladder!!.y.toDouble()
                        p.isNoGravity = true
                        p.act(Activity.NONE)
                    }
                }
                S.ON -> {
                    hold(p, st, lerp(st.foot, st.ladder!!, t / STEP.toDouble()))
                    if (t >= STEP) {
                        s = S.UP
                        t = 0
                        p.act(Activity.CLIMB)
                    }
                }
                S.UP -> {
                    val top = st.ladder!!.y + st.rise.toDouble()
                    y = minOf(top, y + UP)
                    hold(p, st, null)
                    if (y >= top) {
                        s = S.WORK
                        t = 0
                        p.carried = if (putting) st.holding.copy() else ItemStack.EMPTY
                        p.act(Activity.HANG)
                    }
                }
                S.WORK -> {
                    hold(p, st, null)
                    p.lookAt(st.look)
                    if (p.activity != Activity.HANG) p.act(Activity.HANG)
                    if (putting && t == HANG_AT) {
                        placed = st.up(level)
                        p.carried = ItemStack.EMPTY
                        if (placed) cheer(level, column, st.look, heart(p))
                    }
                    if (!putting && t == UNTIE_AT) {
                        st.untie?.invoke(level)
                        untied = true
                    }
                    if (!putting && t == UNTIE_AT + UNTIE_TICKS) {
                        st.down(level)
                        p.carried = st.holding.copy()
                    }
                    if (t >= WORK_TICKS) {
                        s = S.DOWN
                        t = 0
                        p.act(Activity.CLIMB)
                    }
                }
                S.DOWN -> {
                    val bottom = st.ladder!!.y.toDouble()
                    y = maxOf(bottom, y - DOWN)
                    hold(p, st, null)
                    if (y <= bottom) {
                        s = S.OFF
                        t = 0
                        p.act(Activity.NONE)
                    }
                }
                S.OFF -> {
                    hold(p, st, lerp(st.ladder!!, st.foot, t / STEP.toDouble()))
                    if (t >= STEP) {
                        ground(p, st)
                        p.face(st.facing.opposite)
                        p.act(Activity.PICK_UP)
                        s = S.TAKE
                        t = 0
                    }
                }
                S.TAKE -> {
                    if (t == TAKE_AT && laddered) {
                        takeLadder(level, st, column)
                        laddered = false
                        p.carried = ItemStack(Items.LADDER)
                    }
                    if (t >= Activity.PICK_UP.ticks + 4) next(p)
                }
                S.DONE -> return false
            }
            return s != S.DONE
        }

        /** On the ladder: held in its shaft (at [at] across, or the shaft itself) at the height they've climbed to, facing the wall. */
        private fun hold(p: Townsperson, st: Station, at: Vec3?) {
            val bottom = st.ladder!!
            val x = at?.x ?: (bottom.x + 0.5)
            val z = at?.z ?: (bottom.z + 0.5)
            p.navigation.stop()
            p.deltaMovement = Vec3.ZERO
            p.setPos(x, y, z)
            p.fallDistance = 0.0
            p.face(st.facing.opposite)
        }

        private fun lerp(a: BlockPos, b: BlockPos, f: Double): Vec3 {
            val g = f.coerceIn(0.0, 1.0)
            return Vec3(a.x + 0.5 + (b.x - a.x) * g, 0.0, a.z + 0.5 + (b.z - a.z) * g)
        }

        /** Set down on the ground at [st]'s foot. */
        private fun ground(p: Townsperson, st: Station) {
            p.isNoGravity = false
            p.deltaMovement = Vec3.ZERO
            p.setPos(st.foot.x + 0.5, st.foot.y.toDouble(), st.foot.z + 0.5)
            p.fallDistance = 0.0
        }

        /** This station can't be done (blocked, out of reach): on to the next. */
        private fun skip(p: Townsperson): Boolean {
            next(p)
            return s != S.DONE
        }

        private fun next(p: Townsperson) {
            k++
            if (k >= stations.size) finish(p) else go(p)
        }

        /** Done: whatever they've in hand (the ladder, what they took down) carried off a few steps into their day. */
        private fun finish(p: Townsperson) {
            s = S.DONE
            if (!p.carried.isEmpty) p.carriedUntil = p.tickCount + CARRY_OFF
            if (p.activity == Activity.CARRY || p.activity == Activity.HANG || p.activity == Activity.CLIMB || p.activity == Activity.STAKE) p.act(Activity.NONE)
        }

        private fun heart(p: Townsperson): BlockPos = p.life?.places?.capital?.center ?: p.blockPosition()

        override fun stop(p: Townsperson) {
            walker?.stop()
            p.isNoGravity = false
            // Done in the ordinary way: what's in hand goes off with them (see finish)
            if (s == S.DONE) return
            val level = p.level() as? ServerLevel
            val st = station
            if (st != null && level != null) {
                // Never left up a ladder, nor a ladder left standing, nor a piece left half untied
                if (s == S.ON || s == S.UP || s == S.WORK || s == S.DOWN || s == S.OFF) ground(p, st)
                if (laddered) takeLadder(level, st, column)
                if (untied && !putting && s == S.WORK) st.down(level)
            }
            p.carried = ItemStack.EMPTY
            if (p.activity == Activity.CARRY || p.activity == Activity.HANG || p.activity == Activity.CLIMB || p.activity == Activity.STAKE) p.act(Activity.NONE)
            s = S.DONE
        }

        companion object {
            /** Ticks to step on or off the ladder, and blocks a tick up and down it (as Ways' Climb). */
            const val STEP = 8
            const val UP = 0.0625
            const val DOWN = 0.075

            /** The ticks of PUT_DOWN the ladder (or a pot) touches down on, and of PICK_UP it lifts on. */
            const val SET_AT = 10
            const val GROUND_AT = 10
            const val TAKE_AT = 9

            /** At the top: the tick of HANG a piece is tied on, the tick its untying begins and how long that takes, and how long they're up there. */
            const val HANG_AT = 34
            const val UNTIE_AT = 12
            const val UNTIE_TICKS = 30
            const val WORK_TICKS = 62

            /** The tick of each STAKE stroke the post's knocked on, and ticks what's in hand is carried off for at the end. */
            const val STRIKE_AT = 12
            const val CARRY_OFF = 160
        }
    }
}
