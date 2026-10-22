package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.cooking.CookParticles
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.server.level.ParticleStatus
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import kotlin.math.PI
import kotlin.math.sin

/**
 * The seven particles of Hearthcraft on a client (living-world/cook/design.md 11.4): a soft curl of steam, a bubble that
 * swells and pops, dark greasy smoke, specks of fat, a crumb, the gold glint of a call and a bead of foam. Every one of them,
 * whoever asks for it (a station's own client-side emitters, or the bursts the server sends with a cue or a miss), goes
 * through the providers here, and a provider says no, and no particle is made, when
 *
 *  - the option Cooking Particles is off, or the game's particle setting is Minimal (and on Decreased, one in two is made);
 *  - it would be more than [RANGE] blocks from the camera;
 *  - [ALIVE_MAX] of them are alive already (they are counted in [alive]; the list is cleaned each tick).
 *
 * So the option, the distance and the cap hold for the server's bursts as for the client's own. The look of each (how long
 * it lives, how it rises, swells and fades) is a [Look].
 */
object CookParticleProviders {
    /** No particle of ours is made further than this from the camera, blocks (9.5). */
    const val RANGE = 24.0

    /** No more than this many of ours are alive at once (9.5). */
    const val ALIVE_MAX = 120

    /** The ones alive, by how they were made; cleaned each tick, and cleared when the level changes. */
    private val alive = ArrayList<CookParticle>(ALIVE_MAX + 8)
    private var world: ClientLevel? = null

    /** What `cook perf` counts: particles made, and refused by the cap, the range or the options. */
    @JvmField
    var made = 0L

    @JvmField
    var refused = 0L

    /** How many of ours are alive now. */
    fun count(): Int = alive.size

    fun register(event: RegisterParticleProvidersEvent) {
        event.registerSpriteSet(CookParticles.STEAM.get()) { Provider(it, STEAM) }
        event.registerSpriteSet(CookParticles.BUBBLE.get()) { Provider(it, BUBBLE) }
        event.registerSpriteSet(CookParticles.SMOKE.get()) { Provider(it, SMOKE) }
        event.registerSpriteSet(CookParticles.SIZZLE.get()) { Provider(it, SIZZLE) }
        event.registerSpriteSet(CookParticles.CRUMB.get()) { Provider(it, CRUMB) }
        event.registerSpriteSet(CookParticles.CALL.get()) { Provider(it, CALL) }
        event.registerSpriteSet(CookParticles.FOAM.get()) { Provider(it, FOAM) }
    }

    /** Each client tick: forget the ones that are gone. */
    fun tick(level: ClientLevel?) {
        if (level !== world) {
            alive.clear()
            world = level
        }
        if (alive.isNotEmpty()) alive.removeIf { !it.isAlive }
    }

    fun reset() {
        alive.clear()
        world = null
    }

    private var thin = 1L

    /** Whether the options, the camera's distance and the cap let a particle be made at ([x], [y], [z]). */
    fun allowed(x: Double, y: Double, z: Double): Boolean {
        if (GuildmarkClientConfig.SPEC.isLoaded && !GuildmarkClientConfig.COOK_PARTICLES.get()) return no()
        val minecraft = Minecraft.getInstance()
        when (minecraft.options.particles().get()) {
            ParticleStatus.MINIMAL -> return no()
            ParticleStatus.DECREASED -> {
                // One in two, by a cheap hash of a counter and not by a draw from anyone's random
                thin = thin * 6364136223846793005L + 1442695040888963407L
                if (thin ushr 63 == 0L) return no()
            }
            else -> {}
        }
        if (alive.size >= ALIVE_MAX) return no()
        val camera = minecraft.gameRenderer.mainCamera()
        if (camera.isInitialized) {
            val at = camera.position()
            val dx = at.x - x
            val dy = at.y - y
            val dz = at.z - z
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) return no()
        }
        return true
    }

    private fun no(): Boolean {
        refused++
        return false
    }

    /**
     * Makes one of [type] at the point, moving at ([xd], [yd], [zd]) blocks a tick, tinted ([r], [g], [b]) if given and
     * [size] times as big; null when the options, the range or the cap refuse it. The particle is the engine's from then on.
     */
    fun spawn(
        type: SimpleParticleType, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, size: Float = 1f, alpha: Float = 1f,
    ): CookParticle? {
        val engine = Minecraft.getInstance().particleEngine
        val particle = engine.createParticle(type, x, y, z, xd, yd, zd) as? CookParticle ?: return null
        if (r != 1f || g != 1f || b != 1f) particle.setColor(r, g, b)
        if (size != 1f) particle.scale(size)
        particle.alphaMul = alpha
        return particle
    }

    // ---- How each one looks ----

    /**
     * How a particle lives. [life] is the least and most ticks; [size] is its quad's size in blocks, growing to [grow]
     * times that by its end; [gravity] pulls it down (negative lifts it) and [friction] slows it; it fades in over the first
     * [fadeIn] of its life and out over the last [fadeOut], peaking at [peak]; [glow] is the block light it carries (it is lit by
     * its own light and not the cell's), [spin] how fast it turns, [sway] how much it wanders sideways as it goes, and [frames]
     * whether its sprite runs through the frames over its life or is one picked at random.
     */
    class Look(
        val life: IntRange, val size: Float, val grow: Float, val gravity: Float, val friction: Float, val fadeIn: Float, val fadeOut: Float,
        val peak: Float, val glow: Int, val spin: Float, val sway: Float, val frames: Boolean,
    )

    /** A curl of steam: it rises, widens and thins away. */
    val STEAM = Look(life = 26..40, size = 0.13f, grow = 2.1f, gravity = -0.012f, friction = 0.96f, fadeIn = 0.2f, fadeOut = 0.55f, peak = 0.72f, glow = 0, spin = 0.01f, sway = 0.0035f, frames = true)

    /** A bubble: it sits at the surface, swells, and pops in its last frames. */
    val BUBBLE = Look(life = 9..15, size = 0.05f, grow = 1.7f, gravity = 0f, friction = 0.8f, fadeIn = 0.12f, fadeOut = 0.12f, peak = 0.95f, glow = 0, spin = 0f, sway = 0.0004f, frames = true)

    /** Dark smoke: slower and heavier than steam, and it lasts. */
    val SMOKE = Look(life = 34..54, size = 0.15f, grow = 2.6f, gravity = -0.008f, friction = 0.965f, fadeIn = 0.16f, fadeOut = 0.5f, peak = 0.85f, glow = 0, spin = 0.008f, sway = 0.003f, frames = true)

    /** Specks of fat: spat up and fallen back, quickly. */
    val SIZZLE = Look(life = 9..15, size = 0.06f, grow = 0.8f, gravity = 0.9f, friction = 0.96f, fadeIn = 0.05f, fadeOut = 0.35f, peak = 1f, glow = 4, spin = 0f, sway = 0f, frames = true)

    /** A crumb, tumbling as it falls. */
    val CRUMB = Look(life = 14..24, size = 0.055f, grow = 0.85f, gravity = 0.7f, friction = 0.94f, fadeIn = 0.05f, fadeOut = 0.3f, peak = 1f, glow = 0, spin = 0.5f, sway = 0f, frames = false)

    /** The gold glint of a call: it lifts, turns and twinkles, lit by its own light. */
    val CALL = Look(life = 18..28, size = 0.11f, grow = 1.25f, gravity = -0.02f, friction = 0.95f, fadeIn = 0.15f, fadeOut = 0.45f, peak = 1f, glow = 15, spin = 0.12f, sway = 0.001f, frames = true)

    /** A bead of foam: flung up and out from the rim, it falls and settles. */
    val FOAM = Look(life = 14..24, size = 0.08f, grow = 1.1f, gravity = 0.55f, friction = 0.93f, fadeIn = 0.1f, fadeOut = 0.3f, peak = 1f, glow = 0, spin = 0.05f, sway = 0f, frames = true)

    /** One of the seven particles. */
    class CookParticle(
        level: ClientLevel, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
        private val sprites: SpriteSet, private val look: Look,
    ) : SingleQuadParticle(level, x, y, z, sprites.first()) {
        private val baseSize = look.size * (0.85f + random.nextFloat() * 0.3f)
        private val phase = random.nextFloat() * (2f * PI.toFloat())
        private val turn = if (random.nextBoolean()) 1f else -1f
        private var sizeMul = 1f

        /** How much of its look's strength it has (a thin wisp of smoke is a smoke at a fifth of it). */
        @JvmField
        var alphaMul = 1f

        init {
            this.xd = xd
            this.yd = yd
            this.zd = zd
            gravity = look.gravity
            friction = look.friction
            hasPhysics = false
            lifetime = look.life.first + random.nextInt(look.life.last - look.life.first + 1)
            quadSize = baseSize
            roll = random.nextFloat() * (2f * PI.toFloat())
            oRoll = roll
            if (look.frames) setSpriteFromAge(sprites) else setSprite(sprites.get(random))
            alpha = 0f
            alive.add(this)
            made++
        }

        override fun getLayer(): Layer = Layer.TRANSLUCENT

        override fun getLightCoords(a: Float): Int {
            val own = super.getLightCoords(a)
            return if (look.glow > 0) LightCoordsUtil.withBlock(own, look.glow) else own
        }

        override fun getQuadSize(a: Float): Float {
            val t = ((age + a) / lifetime).coerceIn(0f, 1f)
            var size = baseSize * sizeMul * (1f + (look.grow - 1f) * t)
            // A call's glint twinkles, a bubble swells quicker at the start and is gone at its pop
            if (look === CALL) size *= 0.8f + 0.2f * sin(phase + (age + a) * 0.9f)
            return size
        }

        override fun tick() {
            super.tick()
            if (!isAlive) return
            val t = age.toFloat() / lifetime
            alpha = look.peak * alphaMul * when {
                t < look.fadeIn -> t / look.fadeIn
                t > 1f - look.fadeOut -> (1f - t) / look.fadeOut
                else -> 1f
            }.coerceIn(0f, 1f)
            if (look.sway != 0f) {
                xd += sin(phase + age * 0.35f) * look.sway
                zd += sin(phase * 1.7f + age * 0.29f) * look.sway
            }
            oRoll = roll
            roll += look.spin * turn
            if (look.frames) setSpriteFromAge(sprites)
        }

        /** [scale] times as big (the game's own `scale` would change a size that this particle works out for itself each frame). */
        override fun scale(scale: Float): Particle {
            sizeMul *= scale
            return this
        }

        /** Tints it ([r], [g], [b] each 0 to 1), as the caller asks; [setColor] is the game's own. */
        fun tint(r: Float, g: Float, b: Float): CookParticle {
            setColor(r, g, b)
            return this
        }
    }

    /**
     * What a provider hands back in place of a particle it will not make: one that is gone before it is drawn. The game's particle
     * engine answers null for a type that has no provider at all, and the town's kitchens (TownKitchen.puff), who ask the engine
     * for our steam, fat and crumbs and draw the game's smoke if it answers null, would read a plain refusal that way and draw the
     * smoke beyond the range, over the cap and on every other one when the setting is Decreased.
     */
    private class Stillborn(level: ClientLevel, x: Double, y: Double, z: Double, sprites: SpriteSet) :
        SingleQuadParticle(level, x, y, z, sprites.first()) {
        init {
            alpha = 0f
            remove()
        }

        override fun getLayer(): Layer = Layer.TRANSLUCENT
    }

    private class Provider(private val sprites: SpriteSet, private val look: Look) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle? {
            if (!allowed(x, y, z)) return Stillborn(level, x, y, z, sprites)
            return CookParticle(level, x, y, z, xAux, yAux, zAux, sprites, look)
        }
    }
}
