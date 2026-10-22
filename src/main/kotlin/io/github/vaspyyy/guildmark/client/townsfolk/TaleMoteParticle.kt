package io.github.vaspyyy.guildmark.client.townsfolk

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import kotlin.math.cos
import kotlin.math.sin

/**
 * A warm gold speck that rises from the shoulder of the cast member of a tale who wants a player's help (living-world/stories/
 * design.md 3.3). It comes up slowly with a little sway side to side, fades in and out, and glows whatever the light around
 * it; its four frames (`tale_mote_0` to `_3`) are near white and grow smaller and fainter, and it tints them gold.
 */
class TaleMoteParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
    private val sprites: SpriteSet,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    private val phase = random.nextDouble() * 2 * Math.PI

    init {
        this.xd = xd
        this.yd = yd
        this.zd = zd
        friction = 0.97f
        gravity = 0f
        hasPhysics = false
        lifetime = 30 + random.nextInt(18)
        quadSize = 0.06f + random.nextFloat() * 0.03f
        setSpriteFromAge(sprites)
        tint()
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int = LightCoordsUtil.withBlock(super.getLightCoords(a), 15)

    override fun getQuadSize(a: Float): Float = quadSize * (1f - (age + a) / lifetime * 0.35f)

    override fun tick() {
        super.tick()
        if (!isAlive) return
        // A slow drift to one side and the other as it climbs
        val w = age * 0.2 + phase
        xd += sin(w) * 0.0008
        zd += cos(w * 0.8) * 0.0008
        setSpriteFromAge(sprites)
        tint()
    }

    /** Gold, from a pale yellow to a deeper amber as it goes; in and out of sight at either end. */
    private fun tint() {
        val life = age.toFloat() / lifetime
        rCol = 1f
        gCol = 0.84f - life * 0.16f
        bCol = 0.42f - life * 0.14f
        alpha = when {
            life < 0.2f -> life / 0.2f
            life > 0.6f -> (1f - life) / 0.4f
            else -> 1f
        }
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = TaleMoteParticle(level, x, y, z, xAux, yAux, zAux, sprites)
    }
}
