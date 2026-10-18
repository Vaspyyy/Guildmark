package io.github.vaspyyy.guildmark.client.festival

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A Lanternrise sky lantern let go: a paper lantern with a flame in it, rising slowly into the night with a little
 * sway, all of them carried the same way by the evening's breeze, its flame flickering, until it's a spark far up that
 * winks out. It burns whatever the light round it.
 */
class SkyLanternParticle(
    level: ClientLevel, x: Double, y: Double, z: Double,
    private val sprites: SpriteSet,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    private val seed = random.nextFloat() * 100f
    private val rise = 0.028 + random.nextDouble() * 0.018

    init {
        xd = 0.0
        yd = 0.0
        zd = 0.0
        friction = 1f
        gravity = 0f
        hasPhysics = false
        lifetime = 520 + random.nextInt(260)
        quadSize = 0.32f + random.nextFloat() * 0.08f
        // Paper from warm white to apricot
        val tint = random.nextFloat()
        rCol = 1f
        gCol = 0.86f - tint * 0.12f
        bCol = 0.62f - tint * 0.2f
        setSprite(sprites.get(random))
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int = LightCoordsUtil.FULL_BRIGHT

    override fun getQuadSize(a: Float): Float {
        // A moment to fill with hot air, then its own size
        val grow = min(1f, (age + a) / 12f)
        return quadSize * (0.6f + 0.4f * grow)
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        val t = age / 20.0
        // Lift builds as it fills, then steadies; the breeze is the same for all of them, the sway each its own
        val lift = rise * min(1.0, age / 40.0)
        val breeze = min(1.0, age / 120.0)
        x += BREEZE_X * breeze + sin(t * 0.9 + seed) * 0.006
        z += BREEZE_Z * breeze + cos(t * 0.7 + seed * 1.3) * 0.006
        y += lift
        // The flame flickers; at the end it gutters out
        val flicker = 0.82f + 0.18f * sin((t * 11.0 + seed).toFloat()) * sin((t * 7.3 + seed * 2.0).toFloat())
        val end = ((lifetime - age) / 60f).coerceIn(0f, 1f)
        alpha = flicker.coerceIn(0f, 1f) * end
        if (age % 6 == 0) setSprite(sprites.get(random))
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = SkyLanternParticle(level, x, y, z, sprites)
    }

    companion object {
        /** The evening's breeze, blocks a tick. */
        private val BREEZE_X = cos(PI * 0.15) * 0.012
        private val BREEZE_Z = sin(PI * 0.15) * 0.012
    }
}
