package io.github.vaspyyy.guildmark.cooking

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * The seven particles of Hearthcraft (living-world/cook/design.md 11.4, Appendix C.6), registered as
 * `SimpleParticleType(false)` like the seasons' (season/SeasonRegistry.kt). Each is a holder: `STEAM.get()` is the type.
 * Their sprites (`textures/particle/<name>_<n>.png`, `particles/<name>.json`) are Part M1's and the providers that spawn
 * them are Part C's, client only; nothing spawns one yet.
 */
object CookParticles {
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)

    private fun particle(name: String): DeferredHolder<ParticleType<*>, SimpleParticleType> =
        PARTICLES.register(name) { -> SimpleParticleType(false) }

    /** A soft white curl that thins as it rises: a pot, pan, oven, plate or bowl, and the ready ring's gold. */
    val STEAM = particle("cook_steam")

    /** A bubble that swells and pops, at the rim of a heating pot. */
    val BUBBLE = particle("stew_bubble")

    /** A dark, greasy curl: a missed call, a burning pot, a burnt crust. */
    val SMOKE = particle("cook_smoke_dark")

    /** A few specks of fat that spit and fall: the hob, the spit's drip, a flare. */
    val SIZZLE = particle("cook_sizzle")

    /** A crumb or a fleck of flour: a loaf cut, a pie sliced, a bite eaten. */
    val CRUMB = particle("cook_crumb")

    /** A small gold glint that lifts and fades: a vessel wants attention. */
    val CALL = particle("cook_call")

    /** A white foam bead: a pot about to boil over, and the spill. */
    val FOAM = particle("cook_foam")

    fun register(modBus: IEventBus) {
        PARTICLES.register(modBus)
    }
}
