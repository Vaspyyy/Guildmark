package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.lamps.LampTown
import io.github.vaspyyy.guildmark.lamps.StreetLampBlock
import io.github.vaspyyy.guildmark.lamps.StreetLamps
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3

/**
 * The lamplighters: when the clock has a capital's round of street lamps begin (see [StreetLamps]), its keeper, if they
 * can be had just then (about, awake to it or woken, not busy with something else that has charge of them, nor
 * running from anything), is taken off their day to go round it: home to their door for the long pole kept there
 * (its wick lit, of an evening), lamp to lamp along the streets with it held up before them (CARRY_POLE), at each lamp
 * a block in front of its post to light it (LIGHT_LAMP: it catches as the wick goes in) or, of a morning, to put it out
 * with the brass cap on the pole's other end (SNUFF_LAMP), and home again to put the pole away, and so back to their
 * day. A farmer does the same for the lamps at their gate, coming in from the fields and going out to them. A lamp
 * already as it should be is passed by; one they can't get to is left to catch by itself. Hurt, running from
 * something, taken over by something else, or gone out of everyone's reach, they hand what's left of the round back
 * to the clock. Now and then they say something to whoever's by, and spoken to, they answer and carry on.
 */
object Lamplighters : StreetLamps.Taker {
    /** The ticks of LIGHT_LAMP the lamp catches on (its "lit" moment), and of SNUFF_LAMP it goes out on (the hiss). */
    const val LIT_TICK = 24
    const val SNUFF_TICK = 23

    /** One in how many lamps they say something after, if anyone's by. */
    private const val CHATTY = 4

    private val WICK: Holder<SoundEvent> =
        Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.lamp.wick")))

    /** Sets the lamplighters to take their rounds as the clock has them begin. */
    fun init() {
        StreetLamps.taker = this
    }

    override fun take(level: ServerLevel, town: LampTown, run: LampTown.Run, dusk: Boolean, time: Int): Director? {
        val r = Residents.get(level)[run.keeper]?.takeIf { it.alive } ?: return null
        val p = level.getEntity(r.uuid ?: return null) as? Townsperson ?: return null
        if (!able(level, p, r.id)) return null
        val lamplighter = Lamplighter(town, run, dusk)
        if (p.isSleeping) p.stopSleeping()
        p.standUp()
        p.routine.interrupt()
        p.navigation.stop()
        p.director = lamplighter
        return lamplighter
    }

    /** Whether [p] (resident [id]) can be taken off what they're doing just now to go round the lamps. */
    private fun able(level: ServerLevel, p: Townsperson, id: Int): Boolean =
        p.isAlive && !p.isRemoved && p.resident == id && p.director == null && p.captiveOf < 0 && !p.isLeader && !p.watcher &&
            !p.crowd && !p.isPassenger && p.attending == null && p.activity != Activity.FLEE &&
            level.isPositionEntityTicking(p.blockPosition())

    /** Their lit wick catching from the match, as they set out of an evening: a little fizz and a spark at the pole's end. */
    internal fun wick(level: ServerLevel, p: Townsperson) {
        val look = Vec3.directionFromRotation(0f, p.yRot)
        val x = p.x + look.x * 0.9
        val z = p.z + look.z * 0.9
        level.playSound(null, x, p.y + 2.4, z, WICK, SoundSource.NEUTRAL, 0.5f, 0.95f + p.random.nextFloat() * 0.1f)
        level.sendParticles(ParticleTypes.SMALL_FLAME, x, p.y + 2.6, z, 2, 0.03, 0.03, 0.03, 0.004)
    }

    /** What they say now and then on their round, and to someone who speaks to them. */
    internal fun line(p: Townsperson, dusk: Boolean): String = if (dusk) "lamplighter_dusk_${1 + p.random.nextInt(DUSK_LINES)}"
    else "lamplighter_dawn_${1 + p.random.nextInt(DAWN_LINES)}"

    internal fun hello(p: Townsperson): String = "lamplighter_hello_${1 + p.random.nextInt(HELLO_LINES)}"

    const val DUSK_LINES = 4
    const val DAWN_LINES = 3
    const val HELLO_LINES = 3

    internal fun chatty(p: Townsperson): Boolean = p.random.nextInt(CHATTY) == 0
}

/** A lamplighter going round [run] of [town]'s lamps, lighting them ([dusk]) or putting them out: the director of their townsperson meanwhile. */
class Lamplighter(private val town: LampTown, private val run: LampTown.Run, private val dusk: Boolean) : Director {
    private enum class S { FETCH, PICK, GO, WORK, BACK, STOW }

    private var s = S.FETCH
    private var walker: Walker? = null
    private var started = false
    /** The lamp they're on, as its place in the round. */
    private var k = 0
    private var lastTick = -1
    /** Whether the lamp they're at has caught (or gone out) yet, this time round its move. */
    private var struck = false
    /** Their front door, where the pole is kept; null for someone with no home (they've it with them). */
    private var door: CityPlaces.Door? = null
    private var done = false

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        if (done) return
        // Back from something that had them meanwhile (running from a monster): the round is the clock's again
        if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0) return quit(level, p)
        lastTick = p.tickCount
        val mine = StreetLamps.holds(town, run, this)
        if (mine) StreetLamps.alive(level, run, this)
        val walker = walker ?: Walker(p).also { walker = it }
        if (!started) {
            started = true
            k = run.done
            door = Days.life(p)?.home?.front
            val d = door
            if (d == null) pick(level, p) else walker.go(d.outside, Jobs.BRISK, near = 1.2)
        }
        // Spoken to: they stop to answer
        val other = p.attending
        if (other != null) {
            p.navigation.stop()
            p.lookAt(other)
            return
        }
        when (s) {
            S.FETCH -> {
                if (!mine) return finish(p)
                if (walker.tick() != Walker.State.WALKING) {
                    door?.let { p.face(p.yawTo(Vec3.atCenterOf(it.pos))) }
                    p.act(Activity.PICK_UP)
                    s = S.PICK
                }
            }
            S.PICK -> if (p.actDone) pick(level, p)
            S.GO -> {
                if (!mine) return back(p)
                if (!dusk && StreetLamps.dark(level)) return quit(level, p)
                // Passed by on the way: lit (or out) already, by the clock or a player
                if (p.tickCount % 20 == 0 && settled(level, run.lamps[k])) {
                    StreetLamps.seenTo(run, this, k)
                    k++
                    return next(level, p)
                }
                when (walker.tick()) {
                    Walker.State.WALKING -> {}
                    Walker.State.ARRIVED -> {
                        p.face(p.yawTo(Vec3.atCenterOf(town.pos[run.lamps[k]])))
                        p.act(if (dusk) Activity.LIGHT_LAMP else Activity.SNUFF_LAMP)
                        struck = false
                        s = S.WORK
                    }
                    Walker.State.FAILED -> {
                        // Can't get there: it catches (or goes out) by itself
                        StreetLamps.seenTo(run, this, k)
                        k++
                        next(level, p)
                    }
                }
            }
            S.WORK -> {
                if (!struck && p.actTicks >= (if (dusk) Lamplighters.LIT_TICK else Lamplighters.SNUFF_TICK)) {
                    struck = true
                    if (mine) StreetLamps.light(level, town, run, this, k, dusk)
                }
                if (p.actDone) {
                    if (Lamplighters.chatty(p)) p.say(Lamplighters.line(p, dusk))
                    StreetLamps.seenTo(run, this, k)
                    k++
                    if (mine) next(level, p) else back(p)
                }
            }
            S.BACK -> if (walker.tick() != Walker.State.WALKING) {
                door?.let { p.face(p.yawTo(Vec3.atCenterOf(it.pos))) }
                p.act(Activity.PUT_DOWN)
                s = S.STOW
            }
            S.STOW -> if (p.actDone) finish(p)
        }
    }

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        p.attend(player)
        p.lookAt(player)
        p.say(Lamplighters.hello(p), force = true)
        return true
    }

    /** The pole in hand (its wick lit, of an evening): off to the first lamp. */
    private fun pick(level: ServerLevel, p: Townsperson) {
        if (dusk) {
            p.lantern = true
            Lamplighters.wick(level, p)
        } else {
            p.lantern = false
        }
        next(level, p)
    }

    /** On to the next lamp that wants seeing to, or home once there are none left. */
    private fun next(level: ServerLevel, p: Townsperson) {
        val walker = walker ?: return
        p.act(Activity.CARRY_POLE)
        while (k < run.lamps.size) {
            val i = run.lamps[k]
            val stand = if (settled(level, i)) null else stand(level, i)
            if (stand != null) {
                walker.go(stand, Jobs.BRISK, exact = true)
                s = S.GO
                return
            }
            StreetLamps.seenTo(run, this, k)
            k++
        }
        back(p)
    }

    /** Lamp [i] as the round would leave it already (lit at dusk, out at dawn), or not a lamp to see to at all (not loaded, gone). */
    private fun settled(level: ServerLevel, i: Int): Boolean {
        val pos = town.pos[i]
        if (!level.isLoaded(pos)) return true
        val state = level.getBlockState(pos)
        return !StreetLampBlock.isLamp(state) || state.getValue(StreetLampBlock.LIT) == dusk
    }

    /** Where to stand to lamp [i]: a block in front of its post, on the street if it's a street lamp; null if nowhere will do. */
    private fun stand(level: ServerLevel, i: Int): BlockPos? {
        for (base in town.stands[i]) {
            for (dy in intArrayOf(0, 1, -1)) {
                val pos = base.above(dy)
                if (Places.standable(level, pos)) return pos
            }
        }
        return null
    }

    /** Home with the pole (or, with no home to keep it, done here). */
    private fun back(p: Townsperson) {
        val d = door ?: return finish(p)
        p.act(Activity.CARRY_POLE)
        walker?.go(d.outside, Jobs.BRISK, near = 1.2)
        s = S.BACK
    }

    /** The round's over (or no longer theirs): back to their day. */
    private fun finish(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        StreetLamps.release(level, town, run, this)
        done = true
        if (p.director === this) p.director = null
        p.lantern = false
        p.act(Activity.NONE)
        p.navigation.stop()
        p.routine.interrupt()
    }

    /** Hurt, or kept from it a while: what's left of the round is the clock's to do. */
    private fun quit(level: ServerLevel, p: Townsperson) {
        StreetLamps.release(level, town, run, this)
        finish(p)
    }
}
