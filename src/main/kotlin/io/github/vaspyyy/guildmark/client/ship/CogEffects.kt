package io.github.vaspyyy.guildmark.client.ship

import io.github.vaspyyy.guildmark.ship.Cog
import io.github.vaspyyy.guildmark.ship.ShipRegistry
import io.github.vaspyyy.guildmark.ship.Wind
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a cog sounds and looks like going along, worked out on each client from what it sees of her: the bow
 * wave and the wake spreading from her quarters, spray over the bow when she drives hard, bubbles off her
 * rudder and water running off the anchor as it's weighed; her timbers creaking as she rolls, the canvas
 * cracking full or flogging head to wind, the wheel's pawl clicking as it goes over; the rush of water past
 * her hull, water lapping at it as she lies still, and the wind in her rigging. Her lanterns are lit at dusk
 * and in foul weather.
 */
object CogEffects {
    private class State {
        var wake: Loop? = null
        var lap: Loop? = null
        var rigging: Loop? = null
        var lanterns = 0f
        var lanternsO = 0f
        var nextCreak = 40
        var nextFlap = 0
        var full = false
        var aback = false
        var wheelCooldown = 0
    }

    private val states = WeakHashMap<Cog, State>()

    fun register() {
        Cog.clientEffects = ::tick
    }

    /** How brightly [cog]'s lanterns burn, flickering as flames do. */
    fun lanterns(cog: Cog, partial: Float): Float {
        val s = states[cog] ?: return 0f
        val lit = Mth.lerp(partial, s.lanternsO, s.lanterns)
        if (lit <= 0f) return 0f
        val t = cog.tickCount + partial + cog.id * 7.3f
        return lit * (0.86f + 0.08f * sin(t * 0.9f) + 0.06f * sin(t * 2.3f + 1.7f))
    }

    private fun tick(cog: Cog) {
        val level = cog.level()
        val s = states.getOrPut(cog) { State() }
        s.lanternsO = s.lanterns
        val dark = level.isDarkOutside || level.getRainLevel(1f) > 0.6f || level.getThunderLevel(1f) > 0.3f
        s.lanterns = Cog.approach(s.lanterns, if (dark) 1f else 0f, if (dark) LIGHT_UP else PUT_OUT)

        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.isPaused) return
        val distance = player.distanceToSqr(cog)
        val near = distance < HEAR * HEAR && !cog.isRemoved
        val speed = cog.seenSpeed
        val wind = Wind.strength(level)
        val rough = level.getRainLevel(1f) * 0.5f + level.getThunderLevel(1f) * 0.5f
        val random = level.random

        // ---- The loops: always going near her, louder or softer with what she's doing ----
        if (near) {
            s.wake = keep(minecraft, s.wake, ShipRegistry.WAKE.get(), cog, 0.0, 0.2, 1.0)
            s.lap = keep(minecraft, s.lap, ShipRegistry.LAP.get(), cog, 1.4, 0.1, -1.0)
            s.rigging = keep(minecraft, s.rigging, ShipRegistry.RIGGING.get(), cog, 0.0, 5.5, 0.0)
        }
        s.wake?.let {
            it.target = if (near) (speed / FULL_WAKE).coerceIn(0f, 1f) * 0.9f else 0f
            it.pitchTo = 0.8f + (speed / FULL_WAKE).coerceIn(0f, 1.3f) * 0.3f
        }
        s.lap?.let { it.target = if (near) 0.12f + 0.4f * (1f - (speed / 0.12f).coerceIn(0f, 1f)) + 0.15f * rough else 0f }
        s.rigging?.let {
            it.target = if (near) (0.08f + 0.5f * wind * (0.4f + 0.6f * cog.sail) + 0.15f * cog.luff).coerceAtMost(0.85f) else 0f
            it.pitchTo = 0.85f + 0.3f * wind
        }
        if (!near) return

        // ---- One-shots ----
        // Her timbers creak as she works in the swell, more often in a sea or driving hard
        if (--s.nextCreak <= 0) {
            val strain = (rough + abs(cog.heel) / 10f + speed * 1.5f).coerceIn(0f, 1f)
            val at = hullPoint(cog, random.nextFloat() * Mth.TWO_PI, 0.6 + random.nextDouble() * 1.4)
            play(level, at, ShipRegistry.CREAK.get(), 0.3f + 0.45f * strain, 0.8f + random.nextFloat() * 0.3f)
            s.nextCreak = ((50 + random.nextInt(110)) * (1f - 0.55f * strain)).toInt()
        }
        // The canvas flogging as the wind spills out of it
        if (cog.luff > 0.12f) {
            if (--s.nextFlap <= 0) {
                val at = cog.deckPoint(0.0, 6.0, 0.3, 1f)
                play(level, at.x, at.y, at.z, ShipRegistry.LUFF.get(), 0.25f + 0.6f * cog.luff, 0.9f + 0.2f * wind + random.nextFloat() * 0.1f)
                s.nextFlap = ((22 + random.nextInt(14)) / (0.5f + cog.luff)).toInt()
            }
        } else {
            s.nextFlap = 0
        }
        // The sail cracking full as it fills, or slapping back against the mast taken aback
        val fill = cog.fill
        if (!s.full && fill > 0.45f) {
            s.full = true
            val at = cog.deckPoint(0.0, 5.0, 0.6, 1f)
            play(level, at.x, at.y, at.z, ShipRegistry.SAIL_FILL.get(), 0.5f + 0.5f * fill, 0.95f + random.nextFloat() * 0.1f)
        } else if (s.full && fill < 0.2f) {
            s.full = false
        }
        if (!s.aback && fill < -0.3f) {
            s.aback = true
            val at = cog.deckPoint(0.0, 5.0, 0.2, 1f)
            play(level, at.x, at.y, at.z, ShipRegistry.SAIL_FILL.get(), 0.6f, 0.75f)
        } else if (s.aback && fill > -0.1f) {
            s.aback = false
        }
        // The wheel's pawl, clicking over as the helm goes over and comes back
        if (s.wheelCooldown > 0) s.wheelCooldown--
        if (abs(cog.rudder - cog.rudderO) > 0.003f && s.wheelCooldown == 0) {
            val at = cog.deckPoint(0.0, 2.6, -2.9, 1f)
            play(level, at.x, at.y, at.z, ShipRegistry.WHEEL.get(), 0.35f, 0.95f + random.nextFloat() * 0.15f)
            s.wheelCooldown = 5
        }

        // ---- The water about her ----
        if (distance > SEE * SEE) return
        val y = cog.y + 0.05
        if (speed > 0.03f) {
            val way = (speed / FULL_WAKE).coerceIn(0f, 1.3f)
            // The bow wave, thrown out to either side
            spawn(level, (way * 4f + random.nextFloat()).toInt()) {
                val side = if (random.nextBoolean()) 1.0 else -1.0
                val at = cog.localToWorld(side * random.nextDouble() * 0.6, 0.0, 4.0 + random.nextDouble() * 0.3)
                val out = 0.05 + speed * 0.4
                val vx = Cog.rightX(cog.yRot) * side * out + Cog.forwardX(cog.yRot) * speed * 0.5
                val vz = Cog.rightZ(cog.yRot) * side * out + Cog.forwardZ(cog.yRot) * speed * 0.5
                level.addParticle(ParticleTypes.SPLASH, at.x, y + 0.1, at.z, vx, 0.0, vz)
            }
            // Foam spreading from her sides and quarters: left on the water as she goes on, a widening wake
            spawn(level, (way * 3f + random.nextFloat()).toInt()) {
                val side = if (random.nextBoolean()) 1.0 else -1.0
                val along = 1.5 - random.nextDouble() * 5.5
                val at = cog.localToWorld(side * (1.4 + 0.3 * (1.0 - abs(along + 1.0) / 3.5).coerceAtLeast(0.0)), 0.0, along)
                val out = 0.015 + speed * 0.08
                level.addParticle(ParticleTypes.FISHING, at.x, y, at.z, Cog.rightX(cog.yRot) * side * out, 0.0, Cog.rightZ(cog.yRot) * side * out)
            }
            // Churned water and bubbles off her stern and rudder
            spawn(level, (way * 2f + random.nextFloat()).toInt()) {
                val at = cog.localToWorld((random.nextDouble() - 0.5) * 1.2, 0.0, -4.7 - random.nextDouble() * 0.4)
                level.addParticle(ParticleTypes.FISHING, at.x, y, at.z, 0.0, 0.0, 0.0)
                level.addParticle(ParticleTypes.BUBBLE, at.x, y - 0.5, at.z, 0.0, 0.0, 0.0)
            }
            // Driving hard into a sea, spray comes over her bow
            if (speed > 0.2f && wind > 0.5f && random.nextFloat() < (speed - 0.2f) * wind * 0.6f) {
                spawn(level, 6 + random.nextInt(6)) {
                    val side = if (random.nextBoolean()) 1.0 else -1.0
                    val at = cog.deckPoint(side * random.nextDouble() * 0.9, 1.0 + random.nextDouble() * 0.6, 3.6 + random.nextDouble() * 0.6, 1f)
                    level.addParticle(ParticleTypes.SPLASH, at.x, at.y, at.z, 0.0, 0.0, 0.0)
                }
            }
        } else if (random.nextInt(12) == 0) {
            // Lying still, the swell rings her waterline with ripples
            val at = hullPoint(cog, random.nextFloat() * Mth.TWO_PI, 0.0)
            level.addParticle(ParticleTypes.FISHING, at.x, y, at.z, 0.0, 0.0, 0.0)
        }
        // Water running off the anchor and its rope as they're hauled in
        if (cog.anchorDrop > 0.02f && cog.anchorDrop < cog.anchorDropO) {
            val at = cog.localToWorld(ANCHOR_X, 0.9, ANCHOR_Z)
            level.addParticle(ParticleTypes.FALLING_WATER, at.x + (random.nextDouble() - 0.5) * 0.2, at.y, at.z + (random.nextDouble() - 0.5) * 0.2, 0.0, 0.0, 0.0)
        }
    }

    /** A point on [cog]'s hull at [up] blocks above the waterline, [angle] round her from the bow. */
    private fun hullPoint(cog: Cog, angle: Float, up: Double) =
        cog.localToWorld(HALF_BEAM * sin(angle).toDouble(), up, HALF_LENGTH * cos(angle).toDouble())

    private inline fun spawn(level: Level, count: Int, one: () -> Unit) {
        if (count <= 0) return
        for (i in 0 until count) one()
    }

    private fun play(level: Level, at: Vec3, sound: SoundEvent, volume: Float, pitch: Float) =
        play(level, at.x, at.y, at.z, sound, volume, pitch)

    private fun play(level: Level, x: Double, y: Double, z: Double, sound: SoundEvent, volume: Float, pitch: Float) =
        level.playLocalSound(x, y, z, sound, SoundSource.NEUTRAL, volume, pitch, false)

    /** [loop] if it's still playing, or a new one started at the point ([sx], [sy], [sz]) on [cog]. */
    private fun keep(minecraft: Minecraft, loop: Loop?, sound: SoundEvent, cog: Cog, sx: Double, sy: Double, sz: Double): Loop? {
        if (loop != null && !loop.isStopped && minecraft.soundManager.isActive(loop)) return loop
        if (loop != null && !loop.isStopped && loop.age < START_GRACE) return loop
        val fresh = Loop(sound, cog, sx, sy, sz)
        return if (minecraft.soundManager.play(fresh) == SoundEngine.PlayResult.NOT_STARTED) null else fresh
    }

    /**
     * A sound going round and round at a point on a cog, following her as she goes. It eases toward [target]
     * and [pitchTo]; once it's sat silent a while, or she's gone, it stops, to be started afresh when wanted.
     */
    private class Loop(sound: SoundEvent, private val cog: Cog, private val sx: Double, private val sy: Double, private val sz: Double) :
        AbstractTickableSoundInstance(sound, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        var target = 0f
        var pitchTo = 1f
        var age = 0
        private var silent = 0

        init {
            looping = true
            delay = 0
            volume = 0.001f
            attenuation = SoundInstance.Attenuation.LINEAR
            follow()
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            age++
            if (cog.isRemoved) {
                stop()
                return
            }
            follow()
            volume += (target - volume) * if (target > volume) 0.06f else 0.08f
            pitch += (pitchTo - pitch) * 0.05f
            silent = if (volume < 0.003f) silent + 1 else 0
            if (silent > STOP_AFTER) stop()
        }

        private fun follow() {
            val at = cog.localToWorld(sx, sy, sz)
            x = at.x
            y = at.y
            z = at.z
        }
    }

    /** Blocks away a cog is heard, and her water seen. */
    private const val HEAR = 40.0
    private const val SEE = 64.0
    /** Blocks a tick at which her wake is at its loudest and fullest. */
    private const val FULL_WAKE = 0.3f
    /** How fast her lanterns are lit and put out. */
    private const val LIGHT_UP = 0.01f
    private const val PUT_OUT = 0.02f
    /** Ticks a loop gets to be heard of by the sound engine before it's taken for lost. */
    private const val START_GRACE = 10
    private const val STOP_AFTER = 100
    /** Her waterline, near enough: half her beam and half her length. */
    private const val HALF_BEAM = 1.7
    private const val HALF_LENGTH = 4.4
    /** Where the anchor hangs at her cathead on the starboard bow (blocks to starboard, and forward). */
    private const val ANCHOR_X = 1.25
    private const val ANCHOR_Z = 3.5
}
