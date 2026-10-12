package io.github.vaspyyy.guildmark.client.wardfire

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import kotlin.math.sin

/**
 * A gold spark off a Wardfire. It rises slowly, sways, and shrinks to a point as it dies, glowing
 * whatever the light around it.
 */
class WardEmberParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
    private val sprites: SpriteSet,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    /** Where in its sway this spark starts, so sparks don't move in step. */
    private val phase = random.nextFloat() * 6.28f

    init {
        this.xd = xd
        this.yd = yd
        this.zd = zd
        friction = 0.97f
        gravity = -0.002f
        hasPhysics = false
        lifetime = 36 + random.nextInt(40)
        quadSize = 0.045f + random.nextFloat() * 0.035f
        // From pale gold to a deeper orange
        val warmth = random.nextFloat()
        rCol = 1f
        gCol = 0.86f - warmth * 0.3f
        bCol = 0.5f - warmth * 0.35f
        setSpriteFromAge(sprites)
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int = LightCoordsUtil.withBlock(super.getLightCoords(a), 15)

    override fun getQuadSize(a: Float): Float {
        val life = (age + a) / lifetime
        return quadSize * (1f - life * life * 0.7f)
    }

    override fun tick() {
        super.tick()
        if (!isAlive) return
        setSpriteFromAge(sprites)
        // A lazy sway as it climbs
        val sway = sin(age * 0.18f + phase) * 0.0016
        xd += sway
        zd += sin(age * 0.13f + phase * 1.7f) * 0.0016
        alpha = if (age > lifetime - 10) (lifetime - age) / 10f else 1f
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = WardEmberParticle(level, x, y, z, xAux, yAux, zAux, sprites)
    }
}
