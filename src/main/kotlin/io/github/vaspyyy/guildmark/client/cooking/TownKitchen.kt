package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.townsfolk.LivingSchedule
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ParticleStatus
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether a townsperson is at a station (living-world/cook/design.md 7.5, 12.3), for the renderers of the oven and the spit to
 * ask: the oven's door swings open for one working at it, the spit's crank turns for one turning it. And what the seven
 * kitchen clips sound and look like on their moments (`stir`, `taste`, `flip`, `turn_spit`, `carve`, `slice`, `peel`: the
 * sounds of Appendix C.5 and a curl of steam, a spit of fat or a spray of crumbs at the place the clip says it happens).
 * Part I3's, client only.
 *
 * The station keeps no state for either: [doorOpen] and [cranking] look, at most once a tick for each station, for a
 * townsperson in the activity (`OVEN` or `PEEL` for the door, `TURN_SPIT` for the crank) within three blocks of it and
 * turned to face it; the renderer eases the door and the crank into and out of the answer. With `townKitchens` off the
 * answer is no, as the stubs gave: the oven stays shut and the crank still.
 */
object TownKitchen {
    /** How near the station a townsperson works for it to answer (blocks). */
    private const val NEAR = 3.0

    /** The answers asked for this tick, by station: 1 for no, 2 for yes (0 is not asked yet). */
    private const val NO: Byte = 1
    private const val YES: Byte = 2
    private val doors = Long2ByteOpenHashMap()
    private val cranks = Long2ByteOpenHashMap()
    private var stamp = Long.MIN_VALUE
    private var enabled = false

    /** Whether a townsperson stands at the oven at [pos], working it (loading it or drawing a tray), so its door is open. */
    fun doorOpen(level: ClientLevel, pos: BlockPos): Boolean = ask(level, pos, doors, false)

    /** Whether a townsperson is turning the spit at [pos], so its crank turns. */
    fun cranking(level: ClientLevel, pos: BlockPos): Boolean = ask(level, pos, cranks, true)

    private fun ask(level: ClientLevel, pos: BlockPos, known: Long2ByteOpenHashMap, crank: Boolean): Boolean {
        val time = level.gameTime
        if (time != stamp) {
            stamp = time
            doors.clear()
            cranks.clear()
            enabled = Cooking.townKitchens()
        }
        if (!enabled) return false
        val key = pos.asLong()
        val before = known.get(key)
        if (before != 0.toByte()) return before == YES
        val answer = anyone(level, pos, crank)
        known.put(key, if (answer) YES else NO)
        return answer
    }

    /** Whether a townsperson in the right activity is within [NEAR] blocks of the station at [pos] and turned toward it. */
    private fun anyone(level: ClientLevel, pos: BlockPos, crank: Boolean): Boolean {
        val cx = pos.x + 0.5
        val cz = pos.z + 0.5
        for (p in level.getEntitiesOfClass(Townsperson::class.java, AABB(pos).inflate(NEAR, 2.0, NEAR))) {
            val activity = p.shownActivity
            val works = if (crank) activity == Activity.TURN_SPIT else activity == Activity.OVEN || activity == Activity.PEEL
            if (!works) continue
            val dx = cx - p.x
            val dz = cz - p.z
            val d = dx * dx + dz * dz
            if (d > NEAR * NEAR) continue
            // Turned to face it (someone at the very foot of it is taken to)
            if (d > 0.09) {
                val yaw = Math.toRadians(p.yBodyRot.toDouble())
                val ahead = (-sin(yaw) * dx + cos(yaw) * dz) / sqrt(d)
                val least = if (crank) 0.2 else 0.35
                if (ahead < least) continue
            }
            return true
        }
        return false
    }

    // ---- The seven clips' moments ----

    private val FLOUR = DustParticleOptions(0xF4EEDF, 0.9f)
    private val CRUMB_FALLBACK = ItemParticleOption(ParticleTypes.ITEM, Items.BREAD)

    /**
     * Moment [m] of the kitchen clip [clip] ([LivingSchedule.MOMENTS] has it from the clips' own `events`) happens for [p] at
     * [at]. Which moment of the clip it is decides what it is where the clip has two or more (a taste is sipped once, a peel
     * opens the oven door and then sets the loaves down), so this does not depend on what the clip calls them.
     */
    internal fun moment(p: Townsperson, clip: String, m: LivingSchedule.Moment, at: Vec3) {
        val level = p.level()
        val all = LivingSchedule.MOMENTS[clip]
        val index = all?.indexOf(m) ?: 0
        val last = all != null && index == all.size - 1
        when (clip) {
            "stir" -> {
                play(level, at, "cook.stir", 0.5f)
                puff(level, CookParticles.STEAM.get(), ParticleTypes.WHITE_SMOKE, at, 1, 0.1, 0.012)
            }
            // Sipped from the spoon once (the second moment is the look aside): a curl of steam off the spoon
            "taste" -> if (index == 0) {
                play(level, at, "cook.taste", 0.6f)
                puff(level, CookParticles.STEAM.get(), ParticleTypes.WHITE_SMOKE, at, 1, 0.06, 0.01)
            }
            "flip" -> {
                play(level, at, "cook.flip", 0.6f)
                puff(level, CookParticles.SIZZLE.get(), ParticleTypes.SMOKE, at, 3, 0.2, 0.03)
                puff(level, CookParticles.STEAM.get(), ParticleTypes.WHITE_SMOKE, at, 1, 0.15, 0.012)
            }
            "turn_spit" -> play(level, at, "cook.crank", 0.5f)
            "carve" -> {
                play(level, at, "cook.carve", 0.65f)
                puff(level, CookParticles.STEAM.get(), ParticleTypes.WHITE_SMOKE, at, 1, 0.15, 0.012)
            }
            "slice" -> {
                play(level, at, "cook.slice", 0.6f)
                puff(level, CookParticles.CRUMB.get(), CRUMB_FALLBACK, at, 4, 0.12, -0.01)
                puff(level, FLOUR, FLOUR, at, 2, 0.15, 0.01)
            }
            // The door opens on the heat of the oven; the loaves come out and are set down on the counter
            "peel" -> {
                if (index == 0) {
                    play(level, at, "cook.oven_open", 0.8f)
                    puff(level, CookParticles.STEAM.get(), ParticleTypes.WHITE_SMOKE, at, 4, 0.25, 0.02)
                    puff(level, FLOUR, FLOUR, at, 2, 0.2, 0.015)
                } else if (last) {
                    play(level, at, "cook.loaf_down", 0.7f)
                    puff(level, CookParticles.CRUMB.get(), CRUMB_FALLBACK, at, 3, 0.2, -0.01)
                    puff(level, FLOUR, FLOUR, at, 3, 0.2, 0.01)
                }
            }
        }
    }

    // ---- What the player's options allow ----

    /** The share of the full volume Hearthcraft's sounds are heard at (the `cookSounds` option, 0 to 1). */
    internal fun soundShare(): Float = if (GuildmarkClientConfig.SPEC.isLoaded) GuildmarkClientConfig.COOK_SOUNDS.get() / 100f else 1f

    /** Whether Hearthcraft's particles are drawn (the `cookParticles` option, and the game's own particle setting not at its least). */
    internal fun particlesOn(): Boolean =
        (!GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.COOK_PARTICLES.get()) && Minecraft.getInstance().options.particles().get() != ParticleStatus.MINIMAL

    /** Plays the cook sound [name] at [at] at [volume], a little different each time; [CookSoundGate] turns it down to the option's share. */
    internal fun play(level: Level, at: Vec3, name: String, volume: Float) {
        if (soundShare() <= 0.01f) return
        level.playLocalSound(at.x, at.y, at.z, CookSounds.get(name), SoundSource.NEUTRAL, volume, 0.95f + level.random.nextFloat() * 0.1f, false)
    }

    /**
     * [count] particles of [type] near [at], scattered by up to [spread] and rising (or falling, if [rise] is negative) at about
     * [rise]; when the game has no provider for [type] yet (the cook's own particles are Part C's) they are [fallback] instead.
     */
    internal fun puff(level: Level, type: ParticleOptions, fallback: ParticleOptions, at: Vec3, count: Int, spread: Double, rise: Double) {
        if (!particlesOn()) return
        val random = level.random
        val engine = Minecraft.getInstance().particleEngine
        repeat(count) {
            val x = at.x + (random.nextDouble() - 0.5) * spread
            val y = at.y + (random.nextDouble() - 0.5) * spread * 0.5
            val z = at.z + (random.nextDouble() - 0.5) * spread
            val vx = (random.nextDouble() - 0.5) * 0.01
            val vy = rise * (0.6 + random.nextDouble() * 0.8)
            val vz = (random.nextDouble() - 0.5) * 0.01
            if (engine.createParticle(type, x, y, z, vx, vy, vz) == null) level.addParticle(fallback, x, y, z, vx, vy, vz)
        }
    }
}
