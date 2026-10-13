package io.github.vaspyyy.guildmark.client.region

import io.github.vaspyyy.guildmark.region.RegionData
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import net.neoforged.neoforge.registries.DeferredHolder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A mote in a land's air (see RegionParticles): a flake of ash or an ember, a marsh wisp or a gnat, blown
 * snow, a crystal's glint, pollen, a spore, sand, cave dust or a bit of leaf. Its [Style] says how it moves,
 * turns, glows and fades. Most are painted pale (art/scripts/region_particles.py) and RegionParticles tints
 * them as they spawn; the ones light enough to blow about go with RegionParticles' wind.
 */
class RegionMote(
    level: ClientLevel, x: Double, y: Double, z: Double,
    private val sprites: SpriteSet, private val style: Style,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    enum class Style(
        val type: DeferredHolder<ParticleType<*>, SimpleParticleType>,
        /** How hard the wind pushes it: blocks a tick added to its speed each tick, per unit of wind. */
        val catch: Double,
        /** How many sprites it has (art/scripts/region_particles.py). */
        val frames: Int,
        /** Whether it keeps the one sprite it was given rather than change as it goes. */
        val still: Boolean,
    ) {
        ASH(RegionData.ASH_FLAKE, 0.0018, 8, false),
        EMBER(RegionData.ASH_EMBER, 0.0012, 4, false),
        WISP(RegionData.MARSH_WISP, 0.0, 4, false),
        GNAT(RegionData.MARSH_GNAT, 0.0, 2, false),
        SNOW(RegionData.SNOW_FLURRY, 0.0045, 3, true),
        GLINT(RegionData.CRYSTAL_GLINT, 0.0, 12, false),
        POLLEN(RegionData.POLLEN, 0.0014, 3, true),
        SPORE(RegionData.MUSHROOM_SPORE, 0.0006, 3, true),
        SAND(RegionData.SAND_DUST, 0.011, 3, true),
        MOTE(RegionData.CAVE_MOTE, 0.0, 2, true),
        LEAF(RegionData.LEAF_BIT, 0.003, 8, false),
    }

    /** Where in its sway, pulse or tumble it starts, so motes don't move in step. */
    private val phase = random.nextFloat() * 6.2832f
    /** Which of its shapes (ash and leaves) or colours (glints) it is. */
    private var shape = 0
    /** Tumble frames a tick (ash and leaves), and how fast it turns about its face. */
    private var tumble = 0f
    private var turn = 0f
    /** How opaque it gets, and the ticks it takes to fade in and out. */
    private var peak = 1f
    private var fadeIn = 10
    private var fadeOut = 20
    /** The least block light it shows in (15 for the ones that glow). */
    private var light = 0
    /** What it hovers about: a gnat's swarm, a wisp's patch of water. */
    private var homeX = x
    private var homeY = y
    private var homeZ = z

    init {
        hasPhysics = false
        gravity = 0f
        friction = 0.96f
        xd = 0.0
        yd = 0.0
        zd = 0.0
        when (style) {
            Style.ASH -> {
                lifetime = 170 + random.nextInt(110)
                gravity = 0.03f
                friction = 0.95f
                hasPhysics = true
                quadSize = 0.07f + random.nextFloat() * 0.04f
                shape = random.nextInt(2)
                tumble = 0.08f + random.nextFloat() * 0.1f
                turn = (random.nextFloat() - 0.5f) * 0.08f
                peak = 0.9f
            }
            Style.EMBER -> {
                lifetime = 50 + random.nextInt(50)
                gravity = -0.02f
                quadSize = 0.04f + random.nextFloat() * 0.025f
                light = 15
                fadeIn = 4
                fadeOut = 12
                yd = 0.02 + random.nextDouble() * 0.02
            }
            Style.WISP -> {
                lifetime = 240 + random.nextInt(180)
                friction = 0.9f
                quadSize = 0.13f + random.nextFloat() * 0.06f
                light = 15
                fadeIn = 40
                fadeOut = 50
                peak = 0.85f
            }
            Style.GNAT -> {
                lifetime = 90 + random.nextInt(80)
                friction = 0.8f
                quadSize = 0.035f + random.nextFloat() * 0.012f
                fadeIn = 6
                fadeOut = 10
            }
            Style.SNOW -> {
                lifetime = 60 + random.nextInt(50)
                gravity = 0.05f
                hasPhysics = true
                quadSize = 0.035f + random.nextFloat() * 0.03f
                fadeIn = 6
                fadeOut = 15
            }
            Style.GLINT -> {
                lifetime = 14 + random.nextInt(14)
                quadSize = 0.08f + random.nextFloat() * 0.07f
                // Violet most, then azure, then rose
                val pick = random.nextInt(10)
                shape = if (pick < 5) 0 else if (pick < 8) 1 else 2
                light = 15
                fadeIn = 0
                fadeOut = 0
            }
            Style.POLLEN -> {
                lifetime = 160 + random.nextInt(110)
                gravity = 0.002f
                quadSize = 0.04f + random.nextFloat() * 0.02f
                fadeIn = 20
                fadeOut = 30
                peak = 0.9f
            }
            Style.SPORE -> {
                lifetime = 140 + random.nextInt(90)
                gravity = -0.006f
                quadSize = 0.04f + random.nextFloat() * 0.025f
                light = 9
                fadeIn = 20
                fadeOut = 30
                peak = 0.85f
            }
            Style.SAND -> {
                lifetime = 30 + random.nextInt(35)
                gravity = 0.02f
                friction = 0.92f
                hasPhysics = true
                quadSize = 0.07f + random.nextFloat() * 0.05f
                fadeIn = 6
                fadeOut = 14
                peak = 0.75f
            }
            Style.MOTE -> {
                lifetime = 200 + random.nextInt(130)
                friction = 0.98f
                quadSize = 0.02f + random.nextFloat() * 0.016f
                fadeIn = 50
                fadeOut = 50
                peak = 0.55f + random.nextFloat() * 0.25f
            }
            Style.LEAF -> {
                lifetime = 140 + random.nextInt(90)
                gravity = 0.02f
                friction = 0.94f
                hasPhysics = true
                quadSize = 0.065f + random.nextFloat() * 0.035f
                shape = random.nextInt(2)
                tumble = 0.1f + random.nextFloat() * 0.12f
                turn = (random.nextFloat() - 0.5f) * 0.12f
            }
        }
        if (turn != 0f) roll = random.nextFloat() * 6.2832f
        oRoll = roll
        if (style.still) setSprite(sprites.get(random)) else pickSprite()
        fade()
    }

    /** Gives it [r], [g], [b] (0 to 1) over its painted colours. */
    fun tint(r: Float, g: Float, b: Float): RegionMote {
        rCol = r
        gCol = g
        bCol = b
        return this
    }

    /** Has it show in no less than block light [level], so it can be seen in the dark of a cave. */
    fun lit(level: Int): RegionMote {
        light = max(light, level)
        return this
    }

    /** Has a gnat buzz about, or a wisp hover over, the spot ([x], [y], [z]). */
    fun home(x: Double, y: Double, z: Double): RegionMote {
        homeX = x
        homeY = y
        homeZ = z
        return this
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int {
        val coords = super.getLightCoords(a)
        return if (light > 0 && LightCoordsUtil.block(coords) < light) LightCoordsUtil.withBlock(coords, light) else coords
    }

    override fun getQuadSize(a: Float): Float = when (style) {
        // An ember shrinks to a point as it cools
        Style.EMBER -> quadSize * (1f - (age + a) / lifetime * 0.5f)
        else -> quadSize
    }

    override fun tick() {
        oRoll = roll
        val wasOnGround = onGround
        if (style.catch > 0.0) {
            xd += RegionParticles.windX * style.catch
            zd += RegionParticles.windZ * style.catch
        }
        when (style) {
            Style.ASH -> sway(0.0012, 0.07f)
            Style.EMBER -> sway(0.0016, 0.18f)
            Style.LEAF -> {
                // Leaves flutter: side to side, and checked in their fall as they swing
                sway(0.006, 0.2f)
                yd += abs(sin(age * 0.2f + phase)) * 0.0006
            }
            Style.SNOW -> {
                xd += (random.nextDouble() - 0.5) * 0.008
                zd += (random.nextDouble() - 0.5) * 0.008
                yd += (random.nextDouble() - 0.5) * 0.004
            }
            Style.POLLEN -> {
                sway(0.0008, 0.05f)
                yd += sin(age * 0.045f + phase) * 0.0007
            }
            Style.SPORE -> sway(0.0007, 0.06f)
            Style.WISP -> {
                // A slow wander about its spot, bobbing as it goes
                xd += (homeX - x) * 0.0015 + (random.nextDouble() - 0.5) * 0.004
                zd += (homeZ - z) * 0.0015 + (random.nextDouble() - 0.5) * 0.004
                yd += (homeY + sin(age * 0.04f + phase) * 0.3 - y) * 0.004
            }
            Style.GNAT -> {
                // Buzzing about the swarm's middle
                xd += (homeX - x) * 0.03 + (random.nextDouble() - 0.5) * 0.06
                yd += (homeY - y) * 0.03 + (random.nextDouble() - 0.5) * 0.05
                zd += (homeZ - z) * 0.03 + (random.nextDouble() - 0.5) * 0.06
            }
            Style.MOTE -> {
                xd += (random.nextDouble() - 0.5) * 0.0012
                yd += (random.nextDouble() - 0.5) * 0.0008 - 0.00008
                zd += (random.nextDouble() - 0.5) * 0.0012
            }
            Style.GLINT, Style.SAND -> {}
        }
        super.tick()
        if (!isAlive) return
        roll += turn
        // Settled on the ground, it lies a moment and fades
        if (onGround && !wasOnGround && (style == Style.ASH || style == Style.LEAF || style == Style.SNOW)) {
            lifetime = min(lifetime, age + fadeOut + 10)
            turn = 0f
            tumble = 0f
        }
        // Sand skips along the ground rather than settle
        if (style == Style.SAND && onGround) yd = 0.025 + random.nextDouble() * 0.03
        if (style == Style.EMBER) cool()
        pickSprite()
        fade()
    }

    /** A drift from side to side across the wind, [push] hard, [rate] radians a tick. */
    private fun sway(push: Double, rate: Float) {
        val s = sin(age * rate + phase) * push
        xd += s * 0.7
        zd -= s * 0.7
    }

    private fun pickSprite() {
        if (removed) return
        val frame = when (style) {
            // Two shapes, four frames each as it tumbles
            Style.ASH, Style.LEAF -> shape * 4 + (((age * tumble + phase).toInt()) and 3)
            // Bigger as it brightens, then smaller: four sizes, in three colours
            Style.GLINT -> {
                val life = age.toFloat() / lifetime
                shape * 4 + min(3, ((1f - abs(life * 2f - 1f)) * 4f).toInt())
            }
            // Its glow breathes in and out
            Style.WISP -> min(3, ((sin(age * 0.11f + phase) * 0.5f + 0.5f) * 4f).toInt())
            // Wings up, wings down
            Style.GNAT -> age and 1
            Style.EMBER -> {
                setSpriteFromAge(sprites)
                return
            }
            else -> return
        }
        setSprite(sprites.get(frame, style.frames - 1))
    }

    /** From pale gold to a deep orange as it cools. */
    private fun cool() {
        val life = age.toFloat() / lifetime
        rCol = 1f
        gCol = 0.92f - life * 0.37f
        bCol = 0.82f - life * 0.47f
    }

    private fun fade() {
        val inn = if (fadeIn > 0) min(1f, age.toFloat() / fadeIn) else 1f
        val out = if (fadeOut > 0) min(1f, (lifetime - age).toFloat() / fadeOut) else 1f
        alpha = peak * max(0f, min(inn, out))
    }

    class Provider(private val sprites: SpriteSet, private val style: Style) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = RegionMote(level, x, y, z, sprites, style)
    }
}
