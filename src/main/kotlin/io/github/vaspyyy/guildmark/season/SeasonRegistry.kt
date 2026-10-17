package io.github.vaspyyy.guildmark.season

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.UnaryOperator

/**
 * What the seasons add to the registries: the air's particles (art/scripts/season_art.py), the stings a new
 * season comes in on (art/audio/make_festival_music.py, listed in sounds.json) and the Almanac.
 */
object SeasonRegistry {
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    private fun particle(name: String): DeferredHolder<ParticleType<*>, SimpleParticleType> = PARTICLES.register(name) { -> SimpleParticleType(false) }

    /** A blossom petal drifting down from the leaves in spring. */
    val PETAL = particle("season_petal")
    /** A turning leaf falling in autumn, tinted as its tree. */
    val LEAF = particle("season_leaf")
    /** A breath on the winter air. */
    val BREATH = particle("season_breath")
    /** A glint of frost on a still winter night. */
    val GLINT = particle("season_glint")

    private fun sound(name: String): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)) }

    /** The short sting each season comes in on, in order: spring, summer, autumn, winter. */
    val STINGS: List<DeferredHolder<SoundEvent, SoundEvent>> = Season.entries.map { sound("season.sting.${it.id}") }

    /** The Almanac: the year at a glance, the festivals and what grows when. */
    val ALMANAC: DeferredItem<AlmanacItem> = ITEMS.registerItem("almanac", ::AlmanacItem, UnaryOperator<Item.Properties> { it.stacksTo(1).rarity(Rarity.UNCOMMON) })

    /** Whether a player has been handed their Almanac (once, the first time they're in the world with the seasons on). */
    val ALMANAC_GIVEN: DeferredHolder<AttachmentType<*>, AttachmentType<Boolean>> = ATTACHMENTS.register("almanac_given") { ->
        AttachmentType.builder { -> false }.serialize(Codec.BOOL.fieldOf("given")) { it }.copyOnDeath().build()
    }

    fun register(modBus: IEventBus) {
        PARTICLES.register(modBus)
        SOUNDS.register(modBus)
        ITEMS.register(modBus)
        ATTACHMENTS.register(modBus)
    }
}
