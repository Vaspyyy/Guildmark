package io.github.vaspyyy.guildmark.region

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries

/** What a player keeps of the regions, the regions' themes, and the motes in their air. */
object RegionData {
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)

    /** The regions a player has been in, by id: their names are lettered on the map. Kept through death, synced to its player. */
    val FOUND: DeferredHolder<AttachmentType<*>, AttachmentType<List<Long>>> = ATTACHMENTS.register("regions_found") { ->
        AttachmentType.builder { -> listOf<Long>() }
            .serialize(Codec.LONG.listOf().fieldOf("found"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()))
            .build()
    }

    /** The cells a player has come near, so their map gets those cells' borders again when they next join. */
    val CELLS: DeferredHolder<AttachmentType<*>, AttachmentType<List<Long>>> = ATTACHMENTS.register("region_cells") { ->
        AttachmentType.builder { -> listOf<Long>() }
            .serialize(Codec.LONG.listOf().fieldOf("cells"))
            .copyOnDeath()
            .build()
    }

    /** Each kind of land's theme, by kind (sound event guildmark:music.region.<id>; the files are in sounds.json). */
    val THEMES: Map<RegionKind, DeferredHolder<SoundEvent, SoundEvent>> = RegionKind.entries.filter { it.theme }.associateWith { kind ->
        SOUNDS.register(kind.musicKey) { -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, kind.musicKey)) }
    }

    // The motes in each land's air, spawned around the player by client/region/RegionParticles.kt (sprites by
    // art/scripts/region_particles.py)
    val ASH_FLAKE = mote("ash_flake")
    val ASH_EMBER = mote("ash_ember")
    val MARSH_WISP = mote("marsh_wisp")
    val MARSH_GNAT = mote("marsh_gnat")
    val SNOW_FLURRY = mote("snow_flurry")
    val CRYSTAL_GLINT = mote("crystal_glint")
    val POLLEN = mote("pollen")
    val MUSHROOM_SPORE = mote("mushroom_spore")
    val SAND_DUST = mote("sand_dust")
    val CAVE_MOTE = mote("cave_mote")
    val LEAF_BIT = mote("leaf_bit")

    private fun mote(name: String): DeferredHolder<ParticleType<*>, SimpleParticleType> = PARTICLES.register(name) { -> SimpleParticleType(false) }
}
