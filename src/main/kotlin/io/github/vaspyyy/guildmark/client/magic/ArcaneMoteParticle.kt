package io.github.vaspyyy.guildmark.client.magic

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource

/**
 * A violet-white glimmer of sorcery: it drifts the way it was flung, slows, and shrinks from a four-pointed
 * sparkle to a point (its sprites, over its life), glowing whatever the light around it.
 */
class ArcaneMoteParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
    private val sprites: SpriteSet,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    init {
        this.xd = xd
        this.yd = yd
        this.zd = zd
        friction = 0.9f
        gravity = 0f
        hasPhysics = false
        lifetime = 14 + random.nextInt(14)
        quadSize = 0.06f + random.nextFloat() * 0.06f
        // From cold white to amethyst
        val tint = random.nextFloat()
        rCol = 0.92f - tint * 0.25f
        gCol = 0.86f - tint * 0.45f
        bCol = 1f
        setSpriteFromAge(sprites)
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int = LightCoordsUtil.withBlock(super.getLightCoords(a), 15)

    override fun getQuadSize(a: Float): Float {
        val life = (age + a) / lifetime
        return quadSize * (1f - life * 0.5f)
    }

    override fun tick() {
        super.tick()
        if (!isAlive) return
        setSpriteFromAge(sprites)
        alpha = if (age > lifetime - 6) (lifetime - age) / 6f else 1f
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = ArcaneMoteParticle(level, x, y, z, xAux, yAux, zAux, sprites)
    }
}
