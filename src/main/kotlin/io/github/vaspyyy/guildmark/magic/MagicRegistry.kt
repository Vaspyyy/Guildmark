package io.github.vaspyyy.guildmark.magic

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.UnaryOperator

/** Vigil's vow: less damage taken and a firmer stance while it lasts (see Magic). */
class VigilEffect : MobEffect(MobEffectCategory.BENEFICIAL, 0xF2C25A)

/**
 * Everything magic registers, kept apart so the magic package stands on its own. Sounds are listed in
 * assets/guildmark/sounds.json, the mote's sprites in particles/arcane_mote.json, the damage types in
 * data/guildmark/damage_type.
 */
object MagicRegistry {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)
    val EFFECTS: DeferredRegister<MobEffect> = DeferredRegister.create(Registries.MOB_EFFECT, Guildmark.MOD_ID)
    val COMPONENTS: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /** A player's learned and attuned spells. Kept through death, synced to its player for the HUD and the attunement screen. */
    val SPELLBOOK: DeferredHolder<AttachmentType<*>, AttachmentType<SpellbookState>> = ATTACHMENTS.register("spellbook") { ->
        AttachmentType.builder { -> SpellbookState() }
            .serialize(SpellbookState.CODEC.fieldOf("spellbook"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, SpellbookState.STREAM_CODEC)
            .build()
    }

    /** The spell a scroll teaches, by id; a scroll without one teaches the next spell of its school its reader doesn't know. */
    val SPELL: DeferredHolder<DataComponentType<*>, DataComponentType<String>> = COMPONENTS.registerComponentType(
        "spell",
        UnaryOperator<DataComponentType.Builder<String>> { it.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8) },
    )

    // ---- Catalysts: three of each school, plain, finer and rare ----

    val AMETHYST_STAFF = catalyst("amethyst_staff", School.SORCERY, 1.0f, Rarity.COMMON)
    val ENDER_STAFF = catalyst("ender_staff", School.SORCERY, 1.2f, Rarity.UNCOMMON)
    val ECHO_STAFF = catalyst("echo_staff", School.SORCERY, 1.45f, Rarity.RARE)
    val IRON_CENSER = catalyst("iron_censer", School.PRAYER, 1.0f, Rarity.COMMON)
    val GILDED_CENSER = catalyst("gilded_censer", School.PRAYER, 1.2f, Rarity.UNCOMMON)
    val BLAZING_CENSER = catalyst("blazing_censer", School.PRAYER, 1.45f, Rarity.RARE)

    val SORCERY_SCROLL: DeferredItem<SpellScrollItem> = ITEMS.registerItem("sorcery_scroll", { SpellScrollItem(School.SORCERY, it) }, UnaryOperator<Item.Properties> {
        it.stacksTo(16).rarity(Rarity.UNCOMMON)
    })
    val PRAYER_SCROLL: DeferredItem<SpellScrollItem> = ITEMS.registerItem("prayer_scroll", { SpellScrollItem(School.PRAYER, it) }, UnaryOperator<Item.Properties> {
        it.stacksTo(16).rarity(Rarity.UNCOMMON)
    })

    /** What a spell in flight is drawn as: its kind picks the sprite (items/spell_bolt.json). Never held. */
    val SPELL_BOLT_ITEM: DeferredItem<Item> = ITEMS.registerSimpleItem("spell_bolt")

    /** Catalysts and scrolls, in the order the creative tab shows them. */
    val TAB: List<DeferredItem<out Item>> = listOf(
        AMETHYST_STAFF, ENDER_STAFF, ECHO_STAFF, IRON_CENSER, GILDED_CENSER, BLAZING_CENSER, SORCERY_SCROLL, PRAYER_SCROLL,
    )

    private fun catalyst(name: String, school: School, power: Float, rarity: Rarity): DeferredItem<CatalystItem> =
        ITEMS.registerItem(name, { CatalystItem(school, power, it) }, UnaryOperator<Item.Properties> {
            it.stacksTo(1).rarity(rarity).let { p -> if (power >= 1.45f) p.fireResistant() else p }
        })

    // ---- Spells in the world ----

    /** A bolt, splinter, lance, orb, star or ember in flight (see SpellBolt). */
    val SPELL_BOLT: DeferredHolder<EntityType<*>, EntityType<SpellBolt>> = ENTITIES.register("spell_bolt") { ->
        EntityType.Builder.of(::SpellBolt, MobCategory.MISC)
            .sized(0.4f, 0.4f)
            .noLootTable()
            .noSave()
            .clientTrackingRange(8)
            .updateInterval(1)
            .build(key("spell_bolt"))
    }

    /** Ground a spell lies on for a while: Undertow's vortex, Hallowed Ground (see SpellZone). */
    val SPELL_ZONE: DeferredHolder<EntityType<*>, EntityType<SpellZone>> = ENTITIES.register("spell_zone") { ->
        EntityType.Builder.of(::SpellZone, MobCategory.MISC)
            .sized(0.5f, 0.25f)
            .noLootTable()
            .noSave()
            .clientTrackingRange(8)
            .updateInterval(10)
            .build(key("spell_zone"))
    }

    /** A violet-white glimmer of sorcery. */
    val ARCANE_MOTE: DeferredHolder<ParticleType<*>, SimpleParticleType> = PARTICLES.register("arcane_mote") { -> SimpleParticleType(false) }

    val VIGIL: DeferredHolder<MobEffect, VigilEffect> = EFFECTS.register("vigil") { -> VigilEffect() }

    // ---- Damage ----

    val SORCERY_DAMAGE: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, id("sorcery"))
    val PRAYER_DAMAGE: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, id("prayer"))
    /** Both: magic for the resistances, and spared the melee bonuses (spells reckon their own). */
    val IS_SPELL: TagKey<DamageType> = TagKey.create(Registries.DAMAGE_TYPE, id("is_spell"))

    // ---- Sounds ----

    private fun sound(name: String): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(id(name)) }

    val ARCANE_CHARGE = sound("magic.arcane_charge")
    val ARC_BOLT_CAST = sound("magic.arc_bolt_cast")
    val ARC_BOLT_IMPACT = sound("magic.arc_bolt_impact")
    val SPLINTER_CAST = sound("magic.splinter_cast")
    val SPLINTER_IMPACT = sound("magic.splinter_impact")
    val RIME_CAST = sound("magic.rime_cast")
    val RIME_IMPACT = sound("magic.rime_impact")
    val UNDERTOW_OPEN = sound("magic.undertow_open")
    val UNDERTOW_LOOP = sound("magic.undertow_loop")
    val FLICKER = sound("magic.flicker")
    val STAR_CALL = sound("magic.star_call")
    val STAR_FALL = sound("magic.star_fall")
    val STAR_IMPACT = sound("magic.star_impact")
    val PRAYER_CHARGE = sound("magic.prayer_charge")
    val HEARTHLIGHT = sound("magic.hearthlight")
    val FLARE = sound("magic.flare")
    val VIGIL_SOUND = sound("magic.vigil")
    val WAYFLAME_CAST = sound("magic.wayflame_cast")
    val WAYFLAME_IMPACT = sound("magic.wayflame_impact")
    val HALLOWED_GROUND = sound("magic.hallowed_ground")
    val DAWNBURST = sound("magic.dawnburst")
    val LEARN = sound("magic.learn")
    val ATTUNE = sound("magic.attune")
    val CYCLE = sound("magic.cycle")
    val FIZZLE = sound("magic.fizzle")

    private fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    private fun key(name: String): ResourceKey<EntityType<*>> = ResourceKey.create(Registries.ENTITY_TYPE, id(name))
}
