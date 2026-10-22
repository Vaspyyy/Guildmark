package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.vessel.Call
import io.github.vaspyyy.guildmark.cooking.vessel.Calls
import io.github.vaspyyy.guildmark.cooking.vessel.PrepBoardBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import io.github.vaspyyy.guildmark.cooking.vessel.StationView
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlock
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotStyle
import io.github.vaspyyy.guildmark.cooking.vessel.VesselBlockEntity
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.RandomSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What an anim asks of the client around it (so the anim itself names no global of the game): to make a particle of ours or a
 * vanilla one at a point, to find which way the nearest player is, and to play a one-shot sound at a point of the cell.
 */
interface StationHooks {
    /** A particle of ours (through [CookParticleProviders], so the options, the range and the cap hold). */
    fun particle(
        type: SimpleParticleType, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
        r: Float, g: Float, b: Float, size: Float, alpha: Float,
    )

    /** A vanilla particle (a flame), under the same options, range and cap. */
    fun vanilla(type: ParticleOptions, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double)

    /** Whether the station at [pos] is near enough to the camera for its particles to be made (they are not made, and cost nothing, beyond 24 blocks). */
    fun inRange(pos: BlockPos): Boolean

    /** The unit direction on the ground from the station at [pos] toward the nearest player (0, 0 when there is none). */
    fun towardPlayer(pos: BlockPos): FloatArray

    /** The sound [name] (one of CookSounds.NAMES) at the cell pixel ([px], [py], [pz]) of the station at [pos] turned for [facing]. */
    fun sound(pos: BlockPos, facing: Direction, px: Float, py: Float, pz: Float, name: String, volume: Float, pitch: Float)
}

/** An item going into a pot: it arcs in from the cook's side, lands [at] this tick, bobs, and sinks into the place it keeps among the contents. */
class Plop(val item: Int, val at: Long, val dx: Float, val dz: Float, val index: Int) {
    var landed = false
}

/** A ring spreading over the surface from ([x], [z]) px, started [at] this tick, [power] big (1 an ordinary plop). */
class Ripple(val at: Long, val x: Float, val z: Float, val power: Float)

/**
 * What one station's picture is now (living-world/cook/design.md 1.8: the client animates everything from the clock against
 * the stamps the server sent): the [StationView] it was last given, and the numbers each renderer draws from it, every one of
 * them eased a tick at a time with the value of the tick before it kept ([Ease]), so a frame draws between the two by the
 * partial tick and nothing jumps. [StationFx] steps each anim once a tick, for the stations near the camera ([step]) and
 * hands it the particles it may make ([Budget]); the renderers only read it.
 *
 * It does not guess: a heat is the view's notch, a level the view's water, a colour the view's tint, the ring the view's stamps;
 * what it keeps of its own is only what a view cannot say (the moment an item went in, when a call was cued, answered or
 * missed, the angle a rod has turned) and all of that is worked out from changes in the view.
 */
class StationAnim(val pos: BlockPos, val kind: Station?, var be: StationBlockEntity) {
    // ---- Where it is ----

    /** The way the block faces (north for a station that has no facing), and the hearth style of the pot. */
    var facing: Direction = Direction.NORTH
    var hearth = false

    /** The game time of the last step, and the last time anything asked for this anim (it is dropped when nothing has for a while). */
    var stamp = Long.MIN_VALUE
    var touched = 0L

    /** Whether the next step is the first (it puts everything where the view says, with no motion). */
    private var first = true

    /** The view of the last step. */
    var view: StationView = StationView.cold(kind)

    /** How many ticks this anim has been stepped (a phase for the things that wander). */
    var age = 0L

    private val random: RandomSource = RandomSource.create(pos.asLong())

    // ---- What eases ----

    private val all = ArrayList<Ease>()

    private fun ease(start: Float = 0f): Ease = Ease(start).also { all.add(it) }

    /** The pot's height on its chain, in notches (0 to 2, springing as the weight settles), and how full it is (0 to 8, in eighths). */
    val heat = ease()
    val fill = ease()

    /** The pot swinging on its chain: degrees about x and about z. */
    val swayX = ease()
    val swayZ = ease()

    /** The hearth's crane, degrees (0 over the fire, 60 off it). */
    val crane = ease()

    /** How hot the station is, 0 to 1: what the steam, the bubbles, the sizzle, the glow and the loop follow. */
    val energy = ease()

    /** The surface's colour (0 to 255 each), eased toward the view's tint. */
    val tintR = ease(0x4A.toFloat())
    val tintG = ease(0x7F.toFloat())
    val tintB = ease(0xBF.toFloat())

    /** How glossy the surface is (ready), how much film lies on it (a SKIM), how high the foam has climbed (a BOIL_OVER), how much crust (burnt). */
    val gloss = ease()
    val film = ease()
    val foam = ease()
    val crust = ease()

    /** How sooty the pot is, 0 to 3 (the three soot pieces). */
    val soot = ease()

    /** How browned what is in the pan or on the trays is, 0 to 1. */
    val brown = ease()

    /** How hard the surface (or the pan) is being stirred, 0 to 1; the angle it has turned (radians, clockwise as it grows); how far the chunks have orbited. */
    val swirl = ease()
    val spin = ease()
    val orbit = ease()

    /** The oven's door and the larder's: 0 shut, 1 open; how far the trays are out; and the oven window's glow. */
    val door = ease()
    val tray = ease()
    val glow = ease()

    /** The spit's rod, degrees turned, and how far its drips fall. */
    val rod = ease()

    /** The swipe that clears a film: 0 when none, then 0 to 1 as it sweeps. */
    val wipe = ease()

    /** How much of the ready ring shows (0 to 1, it fades in and out), and the phase it was last at, which it keeps while it fades out. */
    val ringA = ease()
    var ringLast = 0f
        private set

    // ---- What the view said before this tick ----

    private var lastCount = 0
    private var lastState = -1
    private var lastCall = 0
    private var lastNotch = -1
    private var lastSwirl = 0L
    private var lastItems = 0

    // ---- What happened, from changes in the view ----

    /** The call showing now, its cue (game time) and its deadline; 0 for none. */
    var callNow: Call? = null
        private set
    var cueAt = 0L
        private set
    var dueAt = 0L
        private set

    /** The last call answered or missed, and when. */
    var answered: Call? = null
        private set
    var answeredAt = Long.MIN_VALUE / 2
        private set
    var missed: Call? = null
        private set
    var missedAt = Long.MIN_VALUE / 2
        private set

    /** When the state last changed, and to what. */
    var stateAt = Long.MIN_VALUE / 2
        private set

    /** The latest stir (or scrape, door, crank, chop): when it began and when it ends. */
    var swirlFrom = 0L
        private set
    var swirlTo = 0L
        private set

    /** The last TURN answered at a pan or an oven: each piece is flipped in turn from then. */
    var flipAt = Long.MIN_VALUE / 2
        private set

    /** Items going in, and rings spreading, as they were made. */
    val plops = ArrayList<Plop>(MAX_PLOPS)
    val ripples = ArrayList<Ripple>(MAX_RIPPLES)

    /** The hearth's crane swings off and on for a change of heat: ticks since it began, or -1; and the notch the pot is shown at meanwhile. */
    private var swing = -1
    private var shownNotch = 0

    /** Clicks of the spit's crank seen, and the angle the rod is turning toward. */
    private var clicks = 0
    private var rodTarget = 0f

    /** The moment the ring last passed its tightest (ready's ripe moment): a gold puff is made once. */
    private var ripeSeen = false

    /** Whether the cursor of this client's player rests on the oven's door with food in hand, which opens it a little ahead of the server. */
    var aimed = false

    /** A townsperson at the oven or the spit (I3), as last asked. */
    private var townDoor = false
    private var townCrank = false

    // ---- Particles and sounds it may make ----

    /** The tokens of this station's particle budget (six a second): one spent for each particle. */
    private var tokens = 2f

    /** Whether the camera is near enough for any particle to be made (asked once a step). */
    private var near = false

    /** How much of the station's loop is wanted (0 to 1) and of which kind; read by [CookLoops]. */
    var loopLevel = 0f
        private set

    /** Ticks until the next drip may be heard (a spit's fat on the coals), so a fast fall is not a rattle. */
    private var dripHush = 0

    /** What a step measured: when it was last worked out and what the loop's reach is, for [StationFx]. */
    var distance = 1.0e9

    // ---- The step ----

    /** Brings this anim up to game time [now] (one tick, or the ticks since the last, up to a few) from the station's view. */
    fun step(now: Long, level: ClientLevel, fx: StationHooks) {
        if (be.isRemoved) return
        if (stamp != Long.MIN_VALUE && now == stamp) return
        var gap = if (stamp == Long.MIN_VALUE) 1L else now - stamp
        if (gap < 0L || gap > SNAP_AFTER) {
            first = true
            gap = 1L
        }
        stamp = now
        age += gap
        val v = be.view()
        readBlock()
        town(level)
        near = fx.inRange(pos)
        if (first) {
            baseline(v, now)
            return
        }
        for (e in all) e.begin()
        view = v
        changes(v, now, fx)
        motion(v, now)
        emit(v, now, fx)
    }

    private fun readBlock() {
        val state = be.blockState
        facing = if (state.hasProperty(StationProps.FACING)) state.getValue(StationProps.FACING) else Direction.NORTH
        hearth = state.hasProperty(StewpotBlock.STYLE) && state.getValue(StewpotBlock.STYLE) == StewpotStyle.HEARTH
    }

    private fun town(level: ClientLevel) {
        townDoor = kind == Station.OVEN && TownKitchen.doorOpen(level, pos)
        townCrank = kind == Station.SPIT && TownKitchen.cranking(level, pos)
    }

    // ---- First sight: everything where the view says ----

    private fun baseline(v: StationView, now: Long) {
        first = false
        view = v
        lastCount = v.addedCount
        lastState = v.state
        lastCall = 0
        lastNotch = v.notch
        lastSwirl = v.swirlUntil
        lastItems = v.items.size
        val doorOpen = doorWanted(v, now)
        shownNotch = v.notch
        swing = -1
        stateAt = now - 2 * LONG
        flipAt = now - 2 * LONG
        callNow = null
        val pending = pendingCall(v, now)
        if (pending != null) {
            callNow = pending
            dueAt = v.callDue
            cueAt = v.callDue - Calls.LATE_TICKS
            lastCall = v.call
        }
        // Put each eased number at its target at once
        for (e in all) e.snap(0f)
        heat.snap(heatTarget())
        fill.snap(fillTarget(v))
        energy.snap(energyTarget(v))
        val rgb = tintOf(v)
        tintR.snap((rgb shr 16 and 0xFF).toFloat())
        tintG.snap((rgb shr 8 and 0xFF).toFloat())
        tintB.snap((rgb and 0xFF).toFloat())
        gloss.snap(glossTarget(v))
        film.snap(filmTarget(v, now))
        foam.snap(foamTarget(v, now))
        crust.snap(if (v.burnt) 1f else 0f)
        soot.snap(sootTarget(v))
        brown.snap(brownTarget(v, now))
        door.snap(if (doorOpen) 1f else 0f)
        tray.snap(if (doorOpen) 1f else 0f)
        glow.snap(glowTarget(v, now))
        crane.snap(0f)
        rodTarget = 0f
        rod.snap(0f)
        ringA.snap(if (ringPhase(now.toDouble()) >= 0f) 1f else 0f)
        ringLast = max(0f, ringPhase(now.toDouble()))
        loopLevel = loopWanted(v)
    }

    // ---- What changed ----

    private fun changes(v: StationView, now: Long, fx: StationHooks) {
        // The contents: an item went in (the counter rose) or the pot was emptied or served down
        if (v.addedCount != lastCount) {
            val n = v.addedCount - lastCount
            if (n in 1..MAX_PLOPS) {
                for (k in 0 until n) {
                    val index = v.items.size - n + k
                    val id = if (index in v.items.indices) v.items[index] else v.added
                    addPlop(id, now + 2L * k, index, fx)
                }
            }
            lastCount = v.addedCount
        }
        if (v.items.size < lastItems && v.state != lastState) {
            // Emptied: a swell of the water and a kick of the pot
            if (kind == Station.STEWPOT) {
                sway(2.4f, -1.5f)
            }
        }
        lastItems = v.items.size

        // The state
        if (v.state != lastState) {
            stateAt = now
            lastState = v.state
        }

        // The heat: a notch more or less moves the pot on its chain; at a hearth the crane swings it off the fire and on again
        if (v.notch != lastNotch) {
            if (hearth && kind == Station.STEWPOT) swing = 0
            if (kind == Station.STEWPOT) {
                sway(if (v.notch > lastNotch) 3f else -3f, 1.2f)
            }
            lastNotch = v.notch
        }

        // A stir, a scrape, a door, a crank turn or a chop: the view's swirlUntil moved on
        if (v.swirlUntil != lastSwirl) {
            if (v.swirlUntil > now) {
                swirlTo = v.swirlUntil
                swirlFrom = max(now - 2L, v.swirlUntil - swirlLength())
                if (kind == Station.SPIT) clicks++
            }
            lastSwirl = v.swirlUntil
        }

        // The call: cued, answered or let go
        val pending = pendingCall(v, now)
        val code = if (pending == null) 0 else v.call
        if (code != lastCall) {
            val was = Call.of(lastCall)
            if (pending != null) {
                callNow = pending
                dueAt = v.callDue
                cueAt = v.callDue - Calls.LATE_TICKS
                if (kind == Station.STEWPOT) sway(1.8f, -1.8f)
            } else if (was != null) {
                if (now < dueAt - 3L) {
                    answered = was
                    answeredAt = now
                    if (was == Call.TURN && (kind == Station.HOB || kind == Station.OVEN)) flipAt = now
                    if (was == Call.SKIM) wipe.snap(0f)
                } else {
                    missed = was
                    missedAt = now
                }
                callNow = null
            }
            lastCall = code
        } else if (pending != null) {
            dueAt = v.callDue
        }
    }

    /** The call a view has pending at [now]: one that is named, and not past its deadline (a made-up view's call lapses the same way). */
    private fun pendingCall(v: StationView, now: Long): Call? {
        if (v.call == 0 || now > v.callDue + 4L) return null
        return Call.of(v.call)
    }

    private fun swirlLength(): Long = when (kind) {
        Station.OVEN -> VesselBlockEntity.DOOR_TICKS
        Station.SPIT -> VesselBlockEntity.CRANK_TICKS
        null -> PrepBoardBlockEntity.SHOW_TICKS
        else -> VesselBlockEntity.STIR_TICKS
    }

    // ---- Where each number is going ----

    private fun heatTarget(): Float = shownNotch.toFloat()

    private fun fillTarget(v: StationView): Float = if (kind == Station.STEWPOT) v.water.toFloat() else 0f

    private fun tintOf(v: StationView): Int = if (v.tint == 0) PLAIN_WATER else v.tint

    private fun energyTarget(v: StationView): Float {
        if (!v.lit || v.burnt) return 0f
        val n = v.notch.coerceIn(0, 2)
        return when (v.state) {
            STATE_EMPTY -> if (kind == Station.STEWPOT) 0.08f else 0.2f + 0.08f * n
            STATE_FILLING -> 0.2f + 0.06f * n
            STATE_HEATING -> 0.38f + 0.12f * n
            STATE_COOKING -> 0.55f + 0.15f * n
            STATE_READY -> 0.65f + 0.15f * n
            STATE_DONE -> 0.62f + 0.1f * n
            STATE_SERVED -> 0.3f
            else -> 0f
        }
    }

    private fun glossTarget(v: StationView): Float = when (v.state) {
        STATE_READY -> 1f
        STATE_DONE -> 0.55f
        else -> 0f
    }

    private fun sootTarget(v: StationView): Float {
        if (v.burnt) return 3f
        return when (v.state) {
            STATE_HEATING -> if (v.lit) 0.6f else soot.cur
            STATE_COOKING -> if (v.lit) 1.1f else max(soot.cur, 0.8f)
            STATE_READY -> max(soot.cur, 1.7f)
            STATE_DONE -> max(soot.cur, 2.3f)
            STATE_EMPTY, STATE_FILLING -> if (v.lit) max(0.3f, min(soot.cur, 1.2f)) else 0f
            else -> soot.cur
        }
    }

    /** How far the pan's pieces or the oven's trays have browned: pale in the cooking, gold at the ripe moment, dark past the window, black burnt. */
    private fun brownTarget(v: StationView, now: Long): Float {
        if (v.burnt) return 1f
        val h = windowHalf(v)
        return when (v.state) {
            STATE_COOKING -> {
                if (v.readyTo <= 0L) brown.cur else {
                    val left = (v.readyTo - now).toFloat() - h
                    0.42f * (1f - (left / 900f).coerceIn(0f, 1f))
                }
            }
            STATE_READY -> {
                if (v.readyTo <= 0L) max(brown.cur, 0.5f) else {
                    val u = (((now - v.readyTo).toFloat() + 2f * h) / (2f * h)).coerceIn(0f, 1f)
                    0.45f + 0.4f * u
                }
            }
            STATE_DONE -> 0.9f
            STATE_SERVED -> max(brown.cur, 0.5f)
            else -> 0f
        }
    }

    /** Half the ready window, in ticks, at the heat now: eight dish-seconds at the notch's speed (living-world/cook/design.md 1.2). */
    private fun windowHalf(v: StationView): Float = 160f / SPEED[v.notch.coerceIn(0, 2)]

    private fun filmTarget(v: StationView, now: Long): Float {
        val c = pendingCall(v, now)
        if (c != Call.SKIM) return 0f
        return 0.18f + 0.78f * callProgress(now.toDouble())
    }

    private fun foamTarget(v: StationView, now: Long): Float {
        val c = pendingCall(v, now)
        if (c == Call.BOIL_OVER) return 0.1f + 0.9f * callProgress(now.toDouble()).let { it * it * (3f - 2f * it) }
        if (missed == Call.BOIL_OVER && now - missedAt < 50L) return 1f
        return 0f
    }

    private fun glowTarget(v: StationView, now: Long): Float {
        if (kind != Station.OVEN) return 0f
        var g = energyTarget(v) * 1.12f
        if (pendingCall(v, now) == Call.FLARE) g = max(g, 0.7f) + 0.3f * (0.5f + 0.5f * sin(age * 0.55f))
        return g.coerceIn(0f, 1f)
    }

    /** Whether the oven's door should be open: the server's swirl, a townsperson at it, or this client's player aiming at it with food. */
    private fun doorWanted(v: StationView, now: Long): Boolean = kind == Station.OVEN && (v.swirlUntil > now || townDoor || aimed)

    /** How far into its call this station is, 0 at the cue and 1 at the deadline (clamped), at game time [t]. */
    fun callProgress(t: Double): Float {
        if (dueAt <= cueAt) return 0f
        return ((t - cueAt) / (dueAt - cueAt).toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    // ---- Easing, a tick ----

    private fun motion(v: StationView, now: Long) {
        val lit = v.lit
        // Heat: the pot's level on its chain. The hearth's crane swings the pot off the fire and on for a change of heat; the pot slides while it is off.
        if (hearth && kind == Station.STEWPOT) {
            if (swing >= 0) {
                swing++
                if (swing >= SWING_SWITCH) shownNotch = v.notch
                val u = (swing / SWING_LEN.toFloat()).coerceIn(0f, 1f)
                crane.set(CRANE_SWUNG * sin(PI.toFloat() * u))
                if (swing >= SWING_LEN) swing = -1
            } else {
                shownNotch = v.notch
                crane.ease(0f, 0.07f)
            }
        } else {
            shownNotch = v.notch
        }
        heat.spring(heatTarget(), 0.2f, 0.62f)
        fill.ease(fillTarget(v), 0.11f)
        energy.ease(energyTarget(v), if (energyTarget(v) > energy.cur) 0.04f else 0.055f)
        val rgb = tintOf(v)
        tintR.ease((rgb shr 16 and 0xFF).toFloat(), 0.05f)
        tintG.ease((rgb shr 8 and 0xFF).toFloat(), 0.05f)
        tintB.ease((rgb and 0xFF).toFloat(), 0.05f)
        gloss.ease(glossTarget(v), 0.045f)
        crust.ease(if (v.burnt) 1f else 0f, 0.07f)
        soot.ease(sootTarget(v), 0.006f)
        brown.ease(brownTarget(v, now), 0.03f)

        // The film clears with a swipe when a SKIM is answered
        val filmGoal = filmTarget(v, now)
        if (answered == Call.SKIM && now - answeredAt < WIPE_TICKS) {
            wipe.set(((now - answeredAt) / WIPE_TICKS.toFloat()).coerceIn(0f, 1f))
            film.ease(0f, 0.3f)
        } else {
            wipe.set(0f)
            film.ease(filmGoal, if (filmGoal > film.cur) 0.1f else 0.14f)
        }
        foam.ease(foamTarget(v, now), if (foamTarget(v, now) > foam.cur) 0.12f else 0.11f)

        // The pot's own sway: a pendulum that every kick sets going, and a boil that keeps it trembling
        swayX.spring(0f, 0.07f, 0.93f)
        swayZ.spring(0f, 0.07f, 0.93f)
        if (kind == Station.STEWPOT) {
            val boil = energy.cur
            if (boil > 0.5f && random.nextInt(14) == 0) swayX.kick((random.nextFloat() - 0.5f) * 0.5f * boil)
            if (boil > 0.5f && random.nextInt(14) == 0) swayZ.kick((random.nextFloat() - 0.5f) * 0.5f * boil)
            if (pendingCall(v, now) == Call.BOIL_OVER && random.nextInt(5) == 0) {
                sway((random.nextFloat() - 0.5f) * 1.6f * callProgress(now.toDouble()), (random.nextFloat() - 0.5f) * 1.6f * callProgress(now.toDouble()))
            }
            // Never past four degrees
            if (abs(swayX.cur) > MAX_SWAY) swayX.set(MAX_SWAY * sign(swayX.cur))
            if (abs(swayZ.cur) > MAX_SWAY) swayZ.set(MAX_SWAY * sign(swayZ.cur))
        }

        // The ring: it shows while the ready window is open and the clock runs
        val ph = ringPhase(now.toDouble())
        if (ph >= 0f) ringLast = ph
        ringA.ease(if (ph >= 0f) 1f else 0f, 0.1f)

        // The stir: how hard, and the angle it has turned (clockwise), and the orbit of the chunks
        val stirring = now in swirlFrom until swirlTo
        swirl.ease(if (stirring) 1f else 0f, if (stirring) 0.22f else 0.05f)
        spin.set(spin.cur + 0.012f + 0.016f * energy.cur + 0.2f * swirl.cur)
        orbit.set(orbit.cur + 0.006f + 0.02f * energy.cur + 0.1f * swirl.cur)

        // The oven's door, its trays and its glow
        if (kind == Station.OVEN) {
            val want = doorWanted(v, now)
            door.swing(if (want) 1f else 0f, if (want) 0.3f else 0.2f, if (want) 0.56f else 0.6f, 0f, 1.04f, 0.3f)
            tray.ease(if (door.cur > 0.55f) 1f else 0f, 0.12f)
            glow.ease(glowTarget(v, now), 0.06f)
        }
        // The larder's door is the larder's own (its renderer drives it from the block state)

        // The spit's rod: a quarter turn a click, a slow turn for a jack or a keeper, easing to a stop between
        if (kind == Station.SPIT) {
            val turning = v.turning || townCrank
            rodTarget += clicks * 90f
            clicks = 0
            if (turning) rodTarget += SLOW_TURN
            rod.spring(rodTarget, 0.14f, 0.7f)
            if (rodTarget > 3600f) {
                rodTarget -= 3600f
                rod.shift(-3600f)
            }
        }

        // How loud its loop wants to be
        loopLevel = loopWanted(v)
    }

    private fun sign(x: Float): Float = if (x < 0f) -1f else 1f

    /** Sets the pot swinging: a push that would carry it about [degX] and [degZ] degrees (it swings back and settles in a second). */
    private fun sway(degX: Float, degZ: Float) {
        swayX.kick(degX * SWAY_PUSH)
        swayZ.kick(degZ * SWAY_PUSH)
    }

    // ---- The ring ----

    /** The ripe moment of a station that is ready (game time), or 0 when its clock is not running. */
    fun ripeAt(v: StationView = view): Long = if (v.readyTo <= 0L) 0L else v.readyTo - windowHalf(v).toLong()

    /** How far through the ready window the cook is at game time [t] (0 when it opens, 0.5 at the ripe moment, 1 when it closes), or -1 for no ring. */
    fun ringPhase(t: Double): Float {
        val v = view
        if (v.state != STATE_READY || v.readyTo <= 0L) return -1f
        val h = windowHalf(v).toDouble()
        return ((t - (v.readyTo - 2.0 * h)) / (2.0 * h)).coerceIn(0.0, 1.0).toFloat()
    }

    // ---- Loops ----

    /** How loud the station's loop is wanted, 0 to 1 (the loop's kind is the station's). */
    private fun loopWanted(v: StationView): Float {
        val e = energy.cur
        return when (kind) {
            Station.STEWPOT -> if (v.items.isEmpty() && v.water == 0) 0f else (e - 0.05f).coerceIn(0f, 1f) * 1.05f
            Station.HOB -> if (v.items.isEmpty()) 0f else (e - 0.05f).coerceIn(0f, 1f) * 1.05f
            Station.OVEN -> (e - 0.05f).coerceIn(0f, 1f) * (1f - 0.35f * door.cur)
            Station.SPIT -> {
                val turning = v.turning || townCrank
                if (v.items.isEmpty() || !v.lit) 0f else if (turning || abs(rod.speed()) > 0.2f) 0.9f else 0.35f * e
            }
            null -> 0f
        }
    }

    // ---- Particles ----

    /** The particle budget: refilled each tick at six a second, at most [BUCKET] stored. */
    private fun refill() {
        tokens = min(BUCKET, tokens + RATE / 20f)
    }

    private fun spend(): Boolean {
        if (tokens < 1f) return false
        tokens -= 1f
        return true
    }

    private fun spawn(
        fx: StationHooks, type: SimpleParticleType, px: Float, py: Float, pz: Float, vx: Float, vy: Float, vz: Float,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, size: Float = 1f, alpha: Float = 1f,
    ) {
        if (!near || !spend()) return
        val w = CookGeo.world(pos, facing, px, py, pz)
        val d = CookGeo.turn(facing, vx, vz)
        fx.particle(type, w.x, w.y, w.z, d[0].toDouble(), vy.toDouble(), d[1].toDouble(), r, g, b, size, alpha)
    }

    /** [spawn] for a vanilla particle (a flame): it costs a token too and obeys the same range. */
    private fun flame(fx: StationHooks, px: Float, py: Float, pz: Float, vx: Float, vy: Float, vz: Float) {
        if (!near || !spend()) return
        val w = CookGeo.world(pos, facing, px, py, pz)
        val d = CookGeo.turn(facing, vx, vz)
        fx.vanilla(ParticleTypes.SMALL_FLAME, w.x, w.y, w.z, d[0].toDouble(), vy.toDouble(), d[1].toDouble())
    }

    /** Whether a thing that happens [perSecond] times a second happens in this tick. */
    private fun chance(perSecond: Float): Boolean = random.nextFloat() < perSecond / 20f

    private fun addPlop(item: Int, at: Long, index: Int, fx: StationHooks) {
        if (plops.size >= MAX_PLOPS) plops.removeAt(0)
        // The item comes in from the side of the nearest player, and from over the rim
        val dir = fx.towardPlayer(pos)
        var dx = dir[0]
        var dz = dir[1]
        if (dx == 0f && dz == 0f) {
            val a = random.nextFloat() * 2f * PI.toFloat()
            dx = cos(a)
            dz = sin(a)
        }
        // In the station's own frame (undo the turn of the block)
        val own = CookGeo.turn(inverse(facing), dx, dz)
        plops.add(Plop(item, at, own[0], own[1], index))
    }

    /** The turn that undoes [facing]'s, for [CookGeo.turn] (which turns north-frame directions to the world). */
    private fun inverse(f: Direction): Direction = when (f) {
        Direction.EAST -> Direction.WEST
        Direction.WEST -> Direction.EAST
        Direction.SOUTH -> Direction.SOUTH
        else -> Direction.NORTH
    }

    /** What is made in this tick: plops landing, the ripples, steam and bubbles, the signs of a call, smoke, sizzle, drips. */
    private fun emit(v: StationView, now: Long, fx: StationHooks) {
        refill()
        when (kind) {
            Station.STEWPOT -> potEmit(v, now, fx)
            Station.HOB -> hobEmit(v, now, fx)
            Station.OVEN -> ovenEmit(v, now, fx)
            Station.SPIT -> spitEmit(v, now, fx)
            null -> {}
        }
        // Old rings and plops are done with
        ripples.removeAll { now - it.at > RIPPLE_LIFE }
        plops.removeAll { now - it.at > PLOP_FLY + PLOP_BOB + PLOP_SINK + 2 }
    }

    // ---- The pot ----

    private fun surfaceY(): Float {
        val w = fill.cur
        return (if (w < 1f) 6.35f + 0.35f * w else SURFACE_LOW + (SURFACE_HIGH - SURFACE_LOW) * ((w - 1f) / 7f)) - heat.cur * NOTCH_STEP
    }

    private fun potEmit(v: StationView, now: Long, fx: StationHooks) {
        val e = energy.cur
        val sy = surfaceY()
        val rim = POT_RIM - heat.cur * NOTCH_STEP
        val pending = pendingCall(v, now)
        val prog = callProgress(now.toDouble())

        // Items landing: a ring, a splash, a kick
        for (p in plops) {
            if (!p.landed && now >= p.at + PLOP_FLY) {
                p.landed = true
                val rest = chunkAt(p.index, p.item, orbit.cur)
                if (ripples.size >= MAX_RIPPLES) ripples.removeAt(0)
                ripples.add(Ripple(now, rest[0], rest[1], 1f))
                sway(1f, 0.7f)
                spawn(fx, CookParticles.BUBBLE.get(), rest[0], sy + 0.3f, rest[1], 0f, 0.012f, 0f, size = 0.8f)
                spawn(fx, CookParticles.STEAM.get(), rest[0], sy + 0.5f, rest[1], 0f, 0.018f, 0f, size = 0.7f, alpha = 0.7f)
            }
        }

        // A call answered: the wisp thins and goes, bubbles come up; the film's grey flecks fly off
        if (answeredAt == now) {
            when (answered) {
                Call.STICK -> repeat(2) { spawn(fx, CookParticles.BUBBLE.get(), 8f + (random.nextFloat() - 0.5f) * 4f, sy + 0.2f, 8f + (random.nextFloat() - 0.5f) * 4f, 0f, 0.015f, 0f) }
                Call.SKIM -> repeat(3) {
                    val a = random.nextFloat() * 2f * PI.toFloat()
                    spawn(fx, CookParticles.FOAM.get(), 8f + cos(a) * 2f, sy + 0.4f, 8f + sin(a) * 2f, cos(a) * 0.03f + 0.02f, 0.03f, sin(a) * 0.03f, 0.62f, 0.6f, 0.58f, 0.8f)
                }
                Call.BOIL_OVER -> repeat(2) { spawn(fx, CookParticles.STEAM.get(), 8f + (random.nextFloat() - 0.5f) * 4f, rim, 8f + (random.nextFloat() - 0.5f) * 4f, 0f, 0.03f, 0f) }
                else -> {}
            }
        }

        if (fill.cur < 0.4f) return

        // Steam over the mouth: thin when it is warming, a thick plume when it is ready
        if (e > 0.06f) {
            val rate = 0.5f + 2.6f * e + (if (v.state == STATE_READY) 1.4f * gloss.cur else 0f)
            if (chance(rate)) {
                val a = random.nextFloat() * 2f * PI.toFloat()
                val r = random.nextFloat() * 2.4f
                val gold = v.state == STATE_READY && random.nextInt(4) == 0
                spawn(
                    fx, CookParticles.STEAM.get(), 8f + cos(a) * r, rim + 0.4f, 8f + sin(a) * r, (random.nextFloat() - 0.5f) * 0.004f, 0.022f + 0.016f * e, (random.nextFloat() - 0.5f) * 0.004f,
                    if (gold) 1f else 1f, if (gold) 0.9f else 1f, if (gold) 0.62f else 1f, 0.9f + 0.5f * e,
                )
            }
        }
        // Bubbles at the rim of a pot that is heating, harder when it boils
        if (e > 0.4f && chance(0.6f + 2.2f * (e - 0.4f))) {
            val a = random.nextFloat() * 2f * PI.toFloat()
            spawn(fx, CookParticles.BUBBLE.get(), 8f + cos(a) * 2.5f, sy + 0.2f, 8f + sin(a) * 2.5f, 0f, 0.01f, 0f)
        }

        // The sign of a call: glints, and what it asks for
        if (pending != null) {
            val urgent = prog > 0.7f
            if (chance(if (urgent) 3.2f else 1.5f)) {
                val a = random.nextFloat() * 2f * PI.toFloat()
                spawn(fx, CookParticles.CALL.get(), 8f + cos(a) * 3.2f, rim + 0.6f, 8f + sin(a) * 3.2f, 0f, 0.014f, 0f)
            }
            when (pending) {
                Call.STICK -> if (chance(1.4f + 2.2f * prog)) {
                    spawn(fx, CookParticles.SMOKE.get(), 8f + (random.nextFloat() - 0.5f) * 3f, rim + 0.2f, 8f + (random.nextFloat() - 0.5f) * 3f, 0f, 0.02f, 0f, 0.55f, 0.38f, 0.22f, 0.7f, 0.3f)
                }
                Call.BOIL_OVER -> if (foam.cur > 0.55f && chance(1.2f + 3f * prog)) {
                    val a = random.nextFloat() * 2f * PI.toFloat()
                    spawn(fx, CookParticles.FOAM.get(), 8f + cos(a) * 3.2f, rim + 0.3f, 8f + sin(a) * 3.2f, cos(a) * 0.012f, 0.02f, sin(a) * 0.012f)
                }
                Call.SKIM -> if (chance(0.7f)) {
                    spawn(fx, CookParticles.STEAM.get(), 8f + (random.nextFloat() - 0.5f) * 4f, rim + 0.3f, 8f + (random.nextFloat() - 0.5f) * 4f, 0f, 0.016f, 0f, 0.8f, 0.8f, 0.78f, 0.7f, 0.6f)
                }
                Call.SEASON -> if (chance(2.2f)) {
                    spawn(fx, CookParticles.STEAM.get(), 8f + (random.nextFloat() - 0.5f) * 5f, rim + 0.3f, 8f + (random.nextFloat() - 0.5f) * 5f, 0f, 0.016f, 0f, 1f, 0.86f, 0.5f, 0.8f, 0.8f)
                }
                else -> {}
            }
        }

        // The ripe moment: a puff of gold, once
        val phase = ringPhase(now.toDouble())
        if (phase >= 0f) {
            if (!ripeSeen && phase >= 0.5f) {
                ripeSeen = true
                repeat(3) {
                    val a = random.nextFloat() * 2f * PI.toFloat()
                    spawn(fx, CookParticles.STEAM.get(), 8f + cos(a) * 1.4f, rim + 0.4f, 8f + sin(a) * 1.4f, 0f, 0.03f, 0f, 1f, 0.86f, 0.46f, 0.9f)
                }
            }
        } else {
            ripeSeen = false
        }

        // Past the window the smoke thickens; burnt it pours
        if (v.state == STATE_DONE && chance(1.4f)) {
            spawn(fx, CookParticles.SMOKE.get(), 8f + (random.nextFloat() - 0.5f) * 3f, rim + 0.3f, 8f + (random.nextFloat() - 0.5f) * 3f, 0f, 0.02f, 0f, 0.8f, 0.8f, 0.8f, 0.9f, 0.5f)
        }
        if (v.burnt && chance(3f)) {
            spawn(fx, CookParticles.SMOKE.get(), 8f + (random.nextFloat() - 0.5f) * 3.4f, rim + 0.3f, 8f + (random.nextFloat() - 0.5f) * 3.4f, 0f, 0.026f, 0f, size = 1.2f)
        }
    }

    // ---- The chunks: where an ingredient rests in the pot (px, in the station's own frame) ----

    /**
     * Where chunk [index] (item [item]) lies in the pot when the orbit has turned [orbitAngle] radians: a place within
     * [CookGeo.CHUNK_R] px of (8, 8) that it orbits slowly, quicker as the pot is stirred and as it boils. Returns x and z.
     */
    fun chunkAt(index: Int, item: Int, orbitAngle: Float): FloatArray {
        val h1 = Mix.unit(item, index * 7 + 1)
        val h2 = Mix.unit(item, index * 7 + 2)
        val h3 = Mix.unit(index, item * 3 + 5)
        val base = (index * 2.39996f) + h1 * 6.2831855f
        val r = 0.5f + 1.7f * sqrt(h2) * (0.8f + 0.2f * h3)
        val ang = base + orbitAngle * (0.45f + 0.9f * h3) * (if (index % 2 == 0) 1f else -0.7f)
        return floatArrayOf(8f + cos(ang) * r, 8f + sin(ang) * r)
    }

    // ---- The pan ----

    private fun hobEmit(v: StationView, now: Long, fx: StationHooks) {
        val e = energy.cur
        val pending = pendingCall(v, now)
        val prog = callProgress(now.toDouble())
        if (v.items.isNotEmpty() && e > 0.15f) {
            if (chance(0.8f + 3.4f * e)) {
                val s = panSlot(random.nextInt(6))
                spawn(fx, CookParticles.SIZZLE.get(), s[0] + (random.nextFloat() - 0.5f) * 1.5f, 11.2f, s[1] + (random.nextFloat() - 0.5f) * 1.5f, (random.nextFloat() - 0.5f) * 0.012f, 0.03f + 0.02f * random.nextFloat(), (random.nextFloat() - 0.5f) * 0.012f)
            }
            if (chance(0.6f + 2f * e)) {
                spawn(fx, CookParticles.STEAM.get(), 4f + random.nextFloat() * 8f, 11.4f, 4f + random.nextFloat() * 8f, 0f, 0.02f + 0.012f * e, 0f, 1f, 1f, 1f, 0.9f, 0.8f)
            }
        }
        // The flue's smoke
        if (e > 0.1f && chance(0.5f + 1.6f * e)) {
            spawn(fx, CookParticles.STEAM.get(), CookGeo.FLUE[0] + (random.nextFloat() - 0.5f), CookGeo.FLUE[1], CookGeo.FLUE[2] + (random.nextFloat() - 0.5f), 0f, 0.024f, 0f, 0.72f, 0.7f, 0.68f, 1.2f, 0.7f)
        }
        if (v.state == STATE_READY && phaseNear(now)) {
            if (chance(1.2f)) spawn(fx, CookParticles.STEAM.get(), 4f + random.nextFloat() * 8f, 11.6f, 4f + random.nextFloat() * 8f, 0f, 0.028f, 0f, 1f, 0.9f, 0.62f, 1f)
        }
        if (pending != null) {
            callSign(pending, prog, fx, 8f, 11.8f, 8f, 4.5f)
        }
        // A piece lands from its flip: a speck of fat where it comes down
        val since = now - flipAt
        if (since in 0L..(FLIP_GAP * 6L + FLIP_LEN)) {
            for (i in 0 until min(v.items.size, 6)) {
                if (since == FLIP_GAP * i + FLIP_LEN.toLong()) {
                    val s = panSlot(i)
                    spawn(fx, CookParticles.SIZZLE.get(), s[0], 11f, s[1], 0f, 0.035f, 0f)
                    spawn(fx, CookParticles.SIZZLE.get(), s[0], 11f, s[1], 0.012f, 0.03f, 0.01f)
                }
            }
        }
        if (v.burnt && chance(2.6f)) {
            spawn(fx, CookParticles.SMOKE.get(), 4f + random.nextFloat() * 8f, 11.5f, 4f + random.nextFloat() * 8f, 0f, 0.026f, 0f, size = 1.1f)
        }
        if (v.state == STATE_DONE && chance(1.2f)) {
            spawn(fx, CookParticles.SMOKE.get(), 4f + random.nextFloat() * 8f, 11.5f, 4f + random.nextFloat() * 8f, 0f, 0.022f, 0f, 0.8f, 0.8f, 0.8f, 0.9f, 0.5f)
        }
    }

    private fun panSlot(i: Int): FloatArray = CookGeo.PAN_SLOTS[i.coerceIn(0, 5)]

    private fun phaseNear(now: Long): Boolean {
        val p = ringPhase(now.toDouble())
        return p in 0.18f..0.82f
    }

    /** The glints and the sign of a call over a station that is not a pot: a glint at its place, a wisp, a flare. */
    private fun callSign(call: Call, prog: Float, fx: StationHooks, cx: Float, cy: Float, cz: Float, spread: Float) {
        val urgent = prog > 0.7f
        if (chance(if (urgent) 3.2f else 1.5f)) {
            val a = random.nextFloat() * 2f * PI.toFloat()
            spawn(fx, CookParticles.CALL.get(), cx + cos(a) * spread, cy + 0.4f, cz + sin(a) * spread * 0.7f, 0f, 0.014f, 0f)
        }
        when (call) {
            Call.STICK -> if (chance(1.2f + 2.2f * prog)) {
                spawn(fx, CookParticles.SMOKE.get(), cx + (random.nextFloat() - 0.5f) * spread, cy, cz + (random.nextFloat() - 0.5f) * spread, 0f, 0.02f, 0f, 0.55f, 0.38f, 0.22f, 0.7f, 0.3f)
            }
            Call.FLARE -> if (chance(1.5f + 2.5f * prog)) {
                flame(fx, cx + (random.nextFloat() - 0.5f) * spread * 1.4f, cy - 1.2f, cz + (random.nextFloat() - 0.5f) * spread, 0f, 0.04f + 0.03f * random.nextFloat(), 0f)
            }
            Call.TURN -> if (chance(1.8f)) {
                spawn(fx, CookParticles.STEAM.get(), cx + (random.nextFloat() - 0.5f) * spread, cy, cz + (random.nextFloat() - 0.5f) * spread, 0f, 0.016f, 0f, 1f, 0.86f, 0.5f, 0.7f, 0.7f)
            }
            Call.SEASON -> if (chance(2.2f)) {
                spawn(fx, CookParticles.STEAM.get(), cx + (random.nextFloat() - 0.5f) * spread, cy, cz + (random.nextFloat() - 0.5f) * spread, 0f, 0.016f, 0f, 1f, 0.86f, 0.5f, 0.8f, 0.8f)
            }
            else -> {}
        }
    }

    // ---- The oven ----

    private fun ovenEmit(v: StationView, now: Long, fx: StationHooks) {
        val e = energy.cur
        val pending = pendingCall(v, now)
        val prog = callProgress(now.toDouble())
        // The chimney smokes while it is lit
        if (e > 0.1f && chance(0.6f + 2f * e)) {
            spawn(fx, CookParticles.STEAM.get(), CookGeo.CHIMNEY[0] + (random.nextFloat() - 0.5f) * 0.8f, CookGeo.CHIMNEY[1], CookGeo.CHIMNEY[2] + (random.nextFloat() - 0.5f) * 0.8f, 0f, 0.026f, 0f, 0.7f, 0.68f, 0.66f, 1.3f, 0.75f)
        }
        // A breath of heat out of the door while it is open and the oven hot
        if (door.cur > 0.4f && e > 0.3f && chance(2.2f * e)) {
            spawn(fx, CookParticles.STEAM.get(), 5.6f + random.nextFloat() * 4.8f, 11f, -0.4f, (random.nextFloat() - 0.5f) * 0.01f, 0.012f, -0.02f, 1f, 0.96f, 0.9f, 1f, 0.8f)
        }
        // The door's first opening, hot: a breath at once
        val opened = door.cur > 0.4f && !(door.prev > 0.4f)
        if (opened && e > 0.3f) {
            repeat(3) { spawn(fx, CookParticles.STEAM.get(), 5.6f + random.nextFloat() * 4.8f, 10.5f + random.nextFloat() * 2f, -0.4f, 0f, 0.016f, -0.03f, 1f, 0.96f, 0.9f, 1f, 0.8f) }
        }
        if (pending != null) callSign(pending, prog, fx, 8f, 13.2f, 1.4f, 3f)
        if (v.state == STATE_READY && phaseNear(now) && chance(1f)) {
            spawn(fx, CookParticles.STEAM.get(), CookGeo.CHIMNEY[0], CookGeo.CHIMNEY[1], CookGeo.CHIMNEY[2], 0f, 0.03f, 0f, 1f, 0.9f, 0.62f, 1f)
        }
        if (v.burnt && chance(3f)) {
            spawn(fx, CookParticles.SMOKE.get(), CookGeo.CHIMNEY[0] + (random.nextFloat() - 0.5f), CookGeo.CHIMNEY[1], CookGeo.CHIMNEY[2] + (random.nextFloat() - 0.5f), 0f, 0.03f, 0f, size = 1.3f)
        }
        if (v.state == STATE_DONE && chance(1.2f)) {
            spawn(fx, CookParticles.SMOKE.get(), CookGeo.CHIMNEY[0], CookGeo.CHIMNEY[1], CookGeo.CHIMNEY[2], 0f, 0.026f, 0f, 0.8f, 0.8f, 0.8f, 1f, 0.5f)
        }
    }

    // ---- The spit ----

    /** Fat drips: slot [k] of the schedule, a drip or not by chance, at the coals where it lands. */
    fun dripSlot(k: Long): Boolean = Mix.unit(k.toInt(), (k shr 32).toInt() + pos.hashCode()) < energy.cur * 0.9f

    private fun spitEmit(v: StationView, now: Long, fx: StationHooks) {
        val e = energy.cur
        val pending = pendingCall(v, now)
        val prog = callProgress(now.toDouble())
        if (dripHush > 0) dripHush--
        // A drip lands: a speck and a hiss on the coals
        if (v.items.isNotEmpty() && v.lit && e > 0.2f) {
            val k = Math.floorDiv(now - DRIP_FALL, DRIP_EVERY)
            val landed = k * DRIP_EVERY + DRIP_FALL + Mix.unit(k.toInt(), 3).times(DRIP_EVERY - 4).toLong()
            if (landed == now && dripSlot(k)) {
                val at = dripPlace(k)
                spawn(fx, CookParticles.SIZZLE.get(), at[0], 2.2f, at[1], 0f, 0.03f, 0f)
                if (dripHush == 0) {
                    dripHush = 8
                    fx.sound(pos, facing, at[0], 2f, at[1], "cook.drip", 0.5f, 0.9f + random.nextFloat() * 0.2f)
                }
            }
        }
        // Smoke from the coals, a thin plume
        if (e > 0.1f && chance(0.5f + 1.2f * e)) {
            spawn(fx, CookParticles.STEAM.get(), 5f + random.nextFloat() * 6f, 12.5f, 6f + random.nextFloat() * 4f, 0f, 0.022f, 0f, 0.7f, 0.68f, 0.66f, 1.1f, 0.6f)
        }
        if (v.items.isNotEmpty() && e > 0.3f && chance(0.5f + 1.2f * e)) {
            spawn(fx, CookParticles.STEAM.get(), 4f + random.nextFloat() * 8f, 9.5f, 6.5f + random.nextFloat() * 3f, 0f, 0.02f, 0f, 1f, 1f, 1f, 0.8f, 0.7f)
        }
        if (pending != null) {
            callSign(pending, prog, fx, 7.5f, 9.5f, 8f, 4.5f)
            if (pending == Call.FLARE && chance(1.5f + 2.5f * prog)) {
                // The flare leaps from the coal beds
                val front = random.nextBoolean()
                spawn(fx, CookParticles.SIZZLE.get(), 3f + random.nextFloat() * 9f, 2.2f, if (front) 4.6f + random.nextFloat() * 1.4f else 10f + random.nextFloat() * 1.4f, 0f, 0.05f, 0f)
            }
        }
        if (v.state == STATE_READY && phaseNear(now) && chance(1f)) {
            spawn(fx, CookParticles.STEAM.get(), 4f + random.nextFloat() * 8f, 11f, 8f, 0f, 0.03f, 0f, 1f, 0.9f, 0.62f, 1f)
        }
        if (v.burnt && chance(2.6f)) {
            spawn(fx, CookParticles.SMOKE.get(), 4f + random.nextFloat() * 8f, 11f, 8f, 0f, 0.026f, 0f, size = 1.2f)
        }
    }

    /** Where drip [k] lands on the tray: x and z in px. */
    fun dripPlace(k: Long): FloatArray {
        val j = (Mix.unit(k.toInt(), 11) * 5.999f).toInt()
        val x = CookGeo.JOINT_X[j] + (Mix.unit(k.toInt(), 12) - 0.5f) * 0.8f
        val z = 6.6f + Mix.unit(k.toInt(), 13) * 2.8f
        return floatArrayOf(x, z)
    }

    // ---- The prep board ----

    /** A chop is showing (the knife at work) at game time [t]: how far through it, 0 to 1, or -1 when none. */
    fun chopPhase(t: Double): Float {
        if (swirlTo <= swirlFrom) return -1f
        val u = (t - swirlFrom) / (swirlTo - swirlFrom).toDouble()
        return if (u < 0.0 || u > 1.0) -1f else u.toFloat()
    }

    companion object {
        // The states of Vessel.State, by ordinal (what a view sends)
        const val STATE_EMPTY = 0
        const val STATE_FILLING = 1
        const val STATE_HEATING = 2
        const val STATE_COOKING = 3
        const val STATE_READY = 4
        const val STATE_DONE = 5
        const val STATE_BURNT = 6
        const val STATE_SERVED = 7

        /** The dish-clock's speed at each notch (Vessel.SPEED), for the length of the ready window. */
        val SPEED = floatArrayOf(0.6f, 1.0f, 1.6f)

        /** After this many ticks unstepped an anim starts over at the view. */
        const val SNAP_AFTER = 60L
        const val LONG = 1_000_000L

        const val PLAIN_WATER = 0x4A7FBF

        const val MAX_PLOPS = 6
        const val MAX_RIPPLES = 7

        /** Ticks a thrown item is in the air, bobs on the surface, sinks, and a ring lasts. */
        const val PLOP_FLY = 6
        const val PLOP_BOB = 56
        const val PLOP_SINK = 24
        const val RIPPLE_LIFE = 26L

        /** The wipe of a film, in ticks; the flip of a piece, its length and the gap between two. */
        const val WIPE_TICKS = 7L
        const val FLIP_LEN = 11
        const val FLIP_GAP = 3

        /** The crane: ticks of a swing, when the pot changes notch in it, and how far it goes out for a change (degrees). */
        const val SWING_LEN = 30
        const val SWING_SWITCH = 12
        const val CRANE_SWUNG = 44f

        const val MAX_SWAY = 4f

        /** A push of one degree per [SWAY_PUSH] speed carries the pot about that far on its spring (k 0.07, d 0.93). */
        const val SWAY_PUSH = 0.25f

        /** Degrees a jack or a keeper turns the rod in a tick (a turn in six seconds). */
        const val SLOW_TURN = 3f

        /** Spit drips: one chance a slot of [DRIP_EVERY] ticks, falling for [DRIP_FALL]. */
        const val DRIP_EVERY = 22L
        const val DRIP_FALL = 5L

        /** The particle budget of 9.5: six a second, with a few stored. */
        const val RATE = 6f
        const val BUCKET = 4f

        // Geometry in px, from CookGeo
        const val NOTCH_STEP = CookGeo.NOTCH_STEP
        const val SURFACE_LOW = CookGeo.SURFACE_LOW
        const val SURFACE_HIGH = CookGeo.SURFACE_HIGH
        const val POT_RIM = CookGeo.POT_TOP
    }
}

/** Small deterministic numbers from two ints (so a chunk, a drip or a bubble keeps its place from one frame to the next). */
object Mix {
    fun hash(a: Int, b: Int): Int {
        var h = a * -0x61c88647 + b * 0x27d4eb2f
        h = h xor (h ushr 15)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return h
    }

    /** A number from 0 up to but not including 1. */
    fun unit(a: Int, b: Int): Float = (hash(a, b) ushr 8) / 16777216f
}
