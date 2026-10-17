package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * Getting about a capital's buildings, floor to floor: townsfolk can't find their own way up a ladder, so a way to
 * somewhere upstairs (or down from upstairs) goes by the building's ladder, climbed hand over hand ([Climb]), with
 * an ordinary walk either side of it.
 */
object Ways {
    /** Whether a climber's body fits through [pos]: a ladder, or nothing to bump into. */
    fun passable(level: ServerLevel, pos: BlockPos): Boolean = level.getBlockState(pos).block is LadderBlock || Places.free(level, pos)

    /** The heights at which someone can step off [ladder] onto a floor (or onto it from one), lowest first. */
    fun exits(level: ServerLevel, ladder: CityPlaces.Ladder): List<Int> {
        val out = ArrayList<Int>(3)
        for (y in ladder.bottom..ladder.top + 1) {
            if (!level.isLoaded(ladder.at(y))) return out
            if (passable(level, ladder.at(y)) && passable(level, ladder.at(y + 1)) && Places.standable(level, ladder.landing(y))) out.add(y)
        }
        return out
    }

    /** Whether the shaft of [ladder] is clear from [a] to [b] for a body to go up or down it. */
    fun clear(level: ServerLevel, ladder: CityPlaces.Ladder, a: Int, b: Int): Boolean {
        for (y in minOf(a, b)..maxOf(a, b) + 1) if (!passable(level, ladder.at(y))) return false
        return true
    }

    /** Whether [y] is above [lodging]'s ground floor (where a ladder is the way up). */
    fun upstairs(lodging: CityPlaces.Lodging, y: Int): Boolean = y >= lodging.y + 2

    /** A ladder of [lodging] with a way off at about [from] and at about [to], and those two heights; the nearest to [near]. */
    fun ladder(level: ServerLevel, lodging: CityPlaces.Lodging, from: Int, to: Int, near: BlockPos): Triple<CityPlaces.Ladder, Int, Int>? {
        var best: Triple<CityPlaces.Ladder, Int, Int>? = null
        var bestDist = Double.MAX_VALUE
        for (ladder in lodging.ladders) {
            val exits = exits(level, ladder)
            val a = exits.filter { abs(it - from) <= 1 }.minByOrNull { abs(it - from) } ?: continue
            val b = exits.filter { abs(it - to) <= 1 }.minByOrNull { abs(it - to) } ?: continue
            if (a == b || !clear(level, ladder, a, b)) continue
            val d = ladder.at(a).distSqr(near)
            if (d < bestDist) {
                bestDist = d
                best = Triple(ladder, a, b)
            }
        }
        return best
    }

    /**
     * The way for [p] to [target], as one task: down the ladder first if they're upstairs and [target] isn't on the
     * same floor, then up the ladder of [target]'s building if it's upstairs, and a walk to it ([speed], [near],
     * [overlay] and [exact] as for [Walk]). Outside a capital, or between floors with no ladder, just a walk.
     */
    fun to(
        p: Townsperson, places: CityPlaces?, target: BlockPos, speed: Double = Jobs.WALK, near: Double = 1.5,
        overlay: Activity = Activity.NONE, exact: Boolean = false,
    ): Task {
        val walk = Walk(p, target, speed, near, overlay, exact)
        if (places == null) return walk
        val level = p.level() as ServerLevel
        val here = p.blockPosition()
        val from = places.lodgingAt(here)?.takeIf { it.contains(here) }
        val into = places.lodgingAt(target)?.takeIf { it.contains(target) }
        val legs = ArrayList<Task>(5)
        var y = here.y
        var at = here
        if (from != null && upstairs(from, y) && !(into === from && abs(target.y - y) <= 1)) {
            val goal = if (into === from) target.y else from.y
            ladder(level, from, y, goal, here)?.let { (ladder, a, b) ->
                legs.add(Walk(p, ladder.landing(a), speed, exact = true))
                legs.add(Climb(p, ladder, a, b))
                y = b
                at = ladder.landing(b)
            }
        }
        if (into != null && upstairs(into, target.y) && !(into === from && abs(target.y - y) <= 1)) {
            ladder(level, into, if (into === from) y else into.y, target.y, at)?.let { (ladder, a, b) ->
                legs.add(Walk(p, ladder.landing(a), speed, overlay = overlay, exact = true))
                legs.add(Climb(p, ladder, a, b))
            }
        }
        if (legs.isEmpty()) return walk
        legs.add(walk)
        val first = legs[0]
        for (i in 1 until legs.size) first.andThen(legs[i])
        return first
    }
}

/**
 * Up or down [ladder] from the way off at height [from] to the one at [to]: a step in to face the rungs, hand over
 * hand up (or down) the shaft, and a turn and a step off onto the floor. Out of everyone's sight they're simply
 * there. Stopped halfway (a fright, a fight), they're set down on the nearest floor rather than left hanging.
 */
class Climb(p: Townsperson, private val ladder: CityPlaces.Ladder, private val from: Int, private val to: Int) : Task(p) {
    private enum class Stage { ON, CLIMBING, OFF, DONE }

    private var stage = Stage.ON
    private var t = 0
    private var y = from.toDouble()
    private val wall = ladder.facing.opposite
    private val shaft = Vec3(ladder.x + 0.5, 0.0, ladder.z + 0.5)

    override val anchored: Boolean get() = stage != Stage.DONE

    override fun start() {
        p.navigation.stop()
        if (!Ways.clear(level, ladder, from, to)) {
            stage = Stage.DONE
            return
        }
        if (unseen()) {
            setDown(to)
            return
        }
        p.isNoGravity = true
        p.act(Activity.NONE)
        p.face(wall)
    }

    override fun run(): Boolean {
        p.deltaMovement = Vec3.ZERO
        when (stage) {
            Stage.ON -> {
                // A step in off the floor, facing the rungs
                t++
                val f = t / STEP.toDouble()
                val landing = ladder.landing(from)
                p.setPos(lerp(landing.x + 0.5, shaft.x, f), from.toDouble(), lerp(landing.z + 0.5, shaft.z, f))
                p.face(wall)
                if (t >= STEP) {
                    stage = Stage.CLIMBING
                    p.act(Activity.CLIMB)
                }
            }
            Stage.CLIMBING -> {
                val up = to > from
                y += if (up) UP else -DOWN
                val there = if (up) y >= to else y <= to
                if (there) y = to.toDouble()
                p.setPos(shaft.x, y, shaft.z)
                p.face(wall)
                if (age % 8 == 0) level.playSound(null, p.blockPosition(), SoundEvents.LADDER_STEP, SoundSource.NEUTRAL, 0.35f, 1f)
                if (there) {
                    stage = Stage.OFF
                    t = 0
                    p.act(Activity.NONE)
                }
            }
            Stage.OFF -> {
                // A turn and a step off onto the floor
                t++
                val f = t / STEP.toDouble()
                val landing = ladder.landing(to)
                p.face(ladder.facing)
                p.setPos(lerp(shaft.x, landing.x + 0.5, f), to.toDouble(), lerp(shaft.z, landing.z + 0.5, f))
                if (t >= STEP) {
                    setDown(to)
                    return false
                }
            }
            Stage.DONE -> return false
        }
        return true
    }

    override fun stop() {
        if (stage == Stage.DONE) return
        // Never left hanging in the shaft
        val nearest = Ways.exits(level, ladder).minByOrNull { abs(it - y) } ?: from
        setDown(nearest)
        if (p.activity == Activity.CLIMB) p.act(Activity.NONE)
    }

    private fun setDown(at: Int) {
        val landing = ladder.landing(at)
        p.isNoGravity = false
        p.setPos(landing.x + 0.5, at.toDouble(), landing.z + 0.5)
        p.fallDistance = 0.0
        stage = Stage.DONE
    }

    private fun unseen(): Boolean = level.getNearestPlayer(p.x, p.y, p.z, SEEN, false) == null

    private fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f.coerceIn(0.0, 1.0)

    private companion object {
        /** Ticks to step on or off. */
        const val STEP = 8

        /** Blocks a tick, up and down: one pull of the arms is 16 ticks of the clip, two rungs. */
        const val UP = 0.125
        const val DOWN = 0.15

        /** How near a player must be for the climb to be played out. */
        const val SEEN = 48.0
    }
}
