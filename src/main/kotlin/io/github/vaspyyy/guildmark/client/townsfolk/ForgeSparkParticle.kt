package io.github.vaspyyy.guildmark.client.townsfolk

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource

/**
 * A spark off hot iron under a smith's hammer. It leaps up and out, arcs down, skips once off the floor and
 * cools from white through gold to a dull red as it dies, glowing whatever the light around it.
 */
class ForgeSparkParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
    private val sprites: SpriteSet,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    init {
        this.xd = xd
        this.yd = yd
        this.zd = zd
        friction = 0.94f
        gravity = 0.9f
        hasPhysics = true
        lifetime = 8 + random.nextInt(12)
        quadSize = 0.035f + random.nextFloat() * 0.03f
        setSpriteFromAge(sprites)
        cool()
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int = LightCoordsUtil.withBlock(super.getLightCoords(a), 15)

    override fun getQuadSize(a: Float): Float = quadSize * (1f - (age + a) / lifetime * 0.6f)

    override fun tick() {
        val wasOnGround = onGround
        super.tick()
        if (!isAlive) return
        // A skip off the floor, the first time it lands
        if (onGround && !wasOnGround && yd <= 0.0) {
            yd = 0.06 + random.nextDouble() * 0.04
            xd *= 0.6
            zd *= 0.6
        }
        setSpriteFromAge(sprites)
        cool()
    }

    /** From white-hot to gold to a dull red over its life. */
    private fun cool() {
        val life = age.toFloat() / lifetime
        rCol = 1f
        gCol = (1f - life * 1.1f).coerceIn(0.25f, 1f)
        bCol = (0.8f - life * 2.2f).coerceIn(0.05f, 0.8f)
        alpha = if (life > 0.7f) (1f - life) / 0.3f else 1f
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = ForgeSparkParticle(level, x, y, z, xAux, yAux, zAux, sprites)
    }
}
