package io.github.vaspyyy.guildmark.client.dragon

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import kotlin.math.sqrt

/**
 * A tongue of dragonfire. Thrown fast from its jaws, it billows out as it slows, burns down from white-gold
 * through orange to a dark red wisp, and glows whatever the light around it. Slower ones, trailing off a fire
 * bolt, burn out sooner and stay small. The sprites are in particles/dragon_flame.json.
 */
class DragonFlameParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
    private val sprites: SpriteSet,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    private val spin = (random.nextFloat() - 0.5f) * 0.25f
    /** How many times its first size it billows out to. */
    private val billow: Float

    init {
        this.xd = xd
        this.yd = yd
        this.zd = zd
        val stream = sqrt(xd * xd + yd * yd + zd * zd) > 0.5
        // A stream reaches about as far as its fire burns (Dragon.BREATH_RANGE); a bolt's trail stays short
        friction = 0.96f
        gravity = -0.03f
        lifetime = if (stream) 16 + random.nextInt(7) else 7 + random.nextInt(5)
        quadSize = if (stream) 0.28f + random.nextFloat() * 0.12f else 0.3f + random.nextFloat() * 0.2f
        billow = if (stream) 3.6f + random.nextFloat() * 1.2f else 1.4f + random.nextFloat() * 0.6f
        roll = random.nextFloat() * 6.2832f
        oRoll = roll
        setSpriteFromAge(sprites)
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int = LightCoordsUtil.withBlock(super.getLightCoords(a), 15)

    override fun getQuadSize(a: Float): Float {
        val life = ((age + a) / lifetime).coerceIn(0f, 1f)
        return quadSize * (1f + (billow - 1f) * sqrt(life))
    }

    override fun tick() {
        super.tick()
        if (!isAlive) return
        setSpriteFromAge(sprites)
        oRoll = roll
        roll += spin
        val left = lifetime - age
        alpha = if (left < FADE) left / FADE.toFloat() else 1f
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = DragonFlameParticle(level, x, y, z, xAux, yAux, zAux, sprites)
    }

    private companion object {
        /** Ticks it fades over at the end. */
        const val FADE = 5
    }
}
