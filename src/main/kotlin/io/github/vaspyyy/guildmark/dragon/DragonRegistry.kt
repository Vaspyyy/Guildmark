package io.github.vaspyyy.guildmark.dragon

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.Item
import net.minecraft.world.item.SpawnEggItem
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * Everything the dragons register: kept apart from the shared registries so the dragon package stands on its
 * own. Sounds are listed in assets/guildmark/sounds.json, the flame's sprites in particles/dragon_flame.json.
 */
object DragonRegistry {
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)

    /** A dragon of the high peaks. Its body fills the box; neck, tail and wings reach well past it (drawn at twice its model's size). */
    val DRAGON: DeferredHolder<EntityType<*>, EntityType<Dragon>> = ENTITIES.register("dragon") { ->
        EntityType.Builder.of(::Dragon, MobCategory.MONSTER)
            .sized(3.6f, 3.8f)
            .eyeHeight(3.4f)
            .fireImmune()
            .noLootTable()
            .clientTrackingRange(14)
            // It flies fast: every tick, so those watching see it glide rather than jump
            .updateInterval(1)
            .build(key("dragon"))
    }

    /** A bolt of fire a dragon spits. */
    val FIREBALL: DeferredHolder<EntityType<*>, EntityType<DragonFireball>> = ENTITIES.register("dragon_fireball") { ->
        EntityType.Builder.of(::DragonFireball, MobCategory.MISC)
            .sized(1.0f, 1.0f)
            .noLootTable()
            .clientTrackingRange(10)
            .updateInterval(1)
            .build(key("dragon_fireball"))
    }

    val FLAME: DeferredHolder<ParticleType<*>, SimpleParticleType> = PARTICLES.register("dragon_flame") { -> SimpleParticleType(true) }

    val SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("dragon_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(DRAGON.get())
    })

    private fun sound(name: String): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)) }

    val ROAR = sound("dragon.roar")
    val GROWL = sound("dragon.growl")
    val SNORE = sound("dragon.snore")
    val HURT = sound("dragon.hurt")
    val DEATH = sound("dragon.death")
    val WING_FLAP = sound("dragon.wing_flap")
    val STEP = sound("dragon.step")
    val BITE = sound("dragon.bite")
    val SWIPE = sound("dragon.swipe")
    val SLAM = sound("dragon.slam")
    val INHALE = sound("dragon.inhale")
    val BREATH = sound("dragon.breath")
    val SPIT = sound("dragon.fireball")
    val FIRE_BURST = sound("dragon.fire_burst")
    val FLYBY = sound("dragon.flyby")
    val MUSIC = sound("music.dragon")

    private fun key(name: String): ResourceKey<EntityType<*>> =
        ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))
}
