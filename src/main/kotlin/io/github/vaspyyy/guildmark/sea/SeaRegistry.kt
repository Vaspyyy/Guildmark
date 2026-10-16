package io.github.vaspyyy.guildmark.sea

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.UnaryOperator

/**
 * Everything the sea monsters register. Their models and textures come from art/scripts/sea_monsters.py, their
 * sounds and the Maelgrasp's theme from art/audio/make_sea_audio.py (listed in assets/guildmark/sounds.json).
 */
object SeaRegistry {
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /** A pike-like hunter of the open sea that runs in packs. Its body fills the box; its jaw and tail reach past it. */
    val GNASHFIN: DeferredHolder<EntityType<*>, EntityType<Gnashfin>> = ENTITIES.register("gnashfin") { ->
        EntityType.Builder.of(::Gnashfin, MobCategory.MONSTER)
            .sized(1.2f, 0.7f)
            .eyeHeight(0.4f)
            .clientTrackingRange(8)
            // It leaps clear across a deck in a second: every tick, so it flies rather than jumps
            .updateInterval(1)
            .build(key("gnashfin"))
    }

    /** One of the Maelgrasp's arms. Its own box is where it breaks the surface; [SeaPart]s cover the rest of it. */
    val MAELGRASP_ARM: DeferredHolder<EntityType<*>, EntityType<MaelgraspArm>> = ENTITIES.register("maelgrasp_arm") { ->
        EntityType.Builder.of(::MaelgraspArm, MobCategory.MONSTER)
            .sized(1.2f, 1.2f)
            .noLootTable()
            .noSummon()
            .clientTrackingRange(10)
            .updateInterval(1)
            .build(key("maelgrasp_arm"))
    }

    /** The Maelgrasp itself: the head that comes up beside the ship, and the mind behind the arms. */
    val MAELGRASP: DeferredHolder<EntityType<*>, EntityType<Maelgrasp>> = ENTITIES.register("maelgrasp") { ->
        EntityType.Builder.of(::Maelgrasp, MobCategory.MONSTER)
            .sized(5.0f, 5.5f)
            .eyeHeight(2.0f)
            .noLootTable()
            .noSummon()
            .clientTrackingRange(12)
            .updateInterval(1)
            .build(key("maelgrasp"))
    }

    // ---- Items ----

    val GNASHFIN_FANG: DeferredItem<Item> = ITEMS.registerSimpleItem("gnashfin_fang")

    val RAW_GNASHFIN: DeferredItem<Item> = ITEMS.registerSimpleItem("raw_gnashfin", UnaryOperator<Item.Properties> {
        it.food(FoodProperties.Builder().nutrition(3).saturationModifier(0.3f).build())
    })

    val COOKED_GNASHFIN: DeferredItem<Item> = ITEMS.registerSimpleItem("cooked_gnashfin", UnaryOperator<Item.Properties> {
        it.food(FoodProperties.Builder().nutrition(8).saturationModifier(0.8f).build())
    })

    /** The beak of a slain Maelgrasp: lashed to a cog's stem, it keeps the Gnashfins off her. */
    val MAELGRASP_BEAK: DeferredItem<MaelgraspBeakItem> = ITEMS.registerItem("maelgrasp_beak", ::MaelgraspBeakItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
    })

    /** The game time a player last met the Maelgrasp (it won't come for them again for a while). Kept through death. */
    val MAELGRASP_MET: DeferredHolder<AttachmentType<*>, AttachmentType<Long>> = ATTACHMENTS.register("maelgrasp_met") { ->
        AttachmentType.builder { -> Long.MIN_VALUE }
            .serialize(Codec.LONG.fieldOf("at"))
            .copyOnDeath()
            .build()
    }

    // ---- Sounds ----

    /** A sound heard out to [range] blocks whatever its loudness (as far as its attenuation in sounds.json reaches), or as far as its loudness carries it. */
    private fun sound(name: String, range: Float? = null): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> if (range != null) SoundEvent.createFixedRangeEvent(id(name), range) else SoundEvent.createVariableRangeEvent(id(name)) }

    val GNASHFIN_FIN = sound("sea.gnashfin.fin", 24f)
    val GNASHFIN_LEAP = sound("sea.gnashfin.leap", 32f)
    val GNASHFIN_DIVE = sound("sea.gnashfin.dive", 32f)
    val GNASHFIN_SNAP = sound("sea.gnashfin.snap", 24f)
    val GNASHFIN_BITE = sound("sea.gnashfin.bite", 24f)
    val GNASHFIN_GNAW = sound("sea.gnashfin.gnaw", 24f)
    val GNASHFIN_FLOP = sound("sea.gnashfin.flop", 24f)
    val GNASHFIN_HURT = sound("sea.gnashfin.hurt", 24f)
    val GNASHFIN_DEATH = sound("sea.gnashfin.death", 32f)

    val OMEN = sound("sea.maelgrasp.omen", 96f)
    val BUBBLES = sound("sea.maelgrasp.bubbles", 48f)
    val SURGE = sound("sea.maelgrasp.surge", 64f)
    val BURST = sound("sea.maelgrasp.burst", 64f)
    val WRITHE = sound("sea.maelgrasp.writhe", 32f)
    val REAR = sound("sea.maelgrasp.rear", 48f)
    val SLAM = sound("sea.maelgrasp.slam", 64f)
    val SWEEP = sound("sea.maelgrasp.sweep", 48f)
    val GRAB = sound("sea.maelgrasp.grab", 32f)
    val SQUEEZE = sound("sea.maelgrasp.squeeze", 32f)
    val HURL = sound("sea.maelgrasp.hurl", 40f)
    val ARM_HURT = sound("sea.maelgrasp.arm_hurt", 32f)
    val SEVER = sound("sea.maelgrasp.sever", 48f)
    val SINK = sound("sea.maelgrasp.sink", 32f)
    val CRUSH = sound("sea.maelgrasp.crush", 48f)
    val RISE = sound("sea.maelgrasp.rise", 96f)
    val ROAR = sound("sea.maelgrasp.roar", 128f)
    val BLINK = sound("sea.maelgrasp.blink", 24f)
    val BITE = sound("sea.maelgrasp.bite", 48f)
    val INK = sound("sea.maelgrasp.ink", 48f)
    val DIVE = sound("sea.maelgrasp.dive", 64f)
    val DEATH = sound("sea.maelgrasp.death", 128f)
    val MUSIC = sound("music.maelgrasp")

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    private fun key(name: String): ResourceKey<EntityType<*>> = ResourceKey.create(Registries.ENTITY_TYPE, id(name))
}
