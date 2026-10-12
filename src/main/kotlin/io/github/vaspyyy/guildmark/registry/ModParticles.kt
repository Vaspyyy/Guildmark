package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/** The mod's own particles. Their sprites are listed in assets/guildmark/particles. */
object ModParticles {
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)

    /** The gold sparks that drift up off a lit Wardfire. */
    val WARD_EMBER: DeferredHolder<ParticleType<*>, SimpleParticleType> = PARTICLES.register("ward_ember") { -> SimpleParticleType(false) }
}
