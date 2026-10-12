package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.guild.RivalAdventurer
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.death.LostMarks
import io.github.vaspyyy.guildmark.enemy.DuskRider
import io.github.vaspyyy.guildmark.enemy.FallenWarden
import io.github.vaspyyy.guildmark.enemy.HillTroll
import io.github.vaspyyy.guildmark.enemy.HollowSoldier
import io.github.vaspyyy.guildmark.enemy.LanternWisp
import io.github.vaspyyy.guildmark.enemy.TrollBoulder
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.item.SpectralSteed
import io.github.vaspyyy.guildmark.story.HollowKing
import io.github.vaspyyy.guildmark.story.StoryNpc
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object ModEntities {
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)

    /** A villager recruited into a player's guild: follows its leader and fights beside them. */
    val GUILD_MEMBER: DeferredHolder<EntityType<*>, EntityType<GuildMember>> = ENTITIES.register("guild_member") { ->
        EntityType.Builder.of(::GuildMember, MobCategory.MISC)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.62f)
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "guild_member")))
    }

    /** A named story character with their own model (Wren, Tobin, Ilsa, Maren). */
    val STORY_NPC: DeferredHolder<EntityType<*>, EntityType<StoryNpc>> = ENTITIES.register("story_npc") { ->
        EntityType.Builder.of(::StoryNpc, MobCategory.MISC)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.62f)
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "story_npc")))
    }

    /** An adventurer (or captain) of a rival guild. */
    val RIVAL: DeferredHolder<EntityType<*>, EntityType<RivalAdventurer>> = ENTITIES.register("rival_adventurer") { ->
        EntityType.Builder.of(::RivalAdventurer, MobCategory.MISC)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.62f)
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "rival_adventurer")))
    }

    /** The final boss of the main story, risen at the Hollow Throne. Drawn 1.6 times humanoid size. */
    val HOLLOW_KING: DeferredHolder<EntityType<*>, EntityType<HollowKing>> = ENTITIES.register("hollow_king") { ->
        EntityType.Builder.of(::HollowKing, MobCategory.MONSTER)
            .sized(0.95f, 3.1f)
            .eyeHeight(2.6f)
            .fireImmune()
            .clientTrackingRange(12)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hollow_king")))
    }

    /** The Guild Marks a player dropped when they fell, waiting where they fell (see Purses). */
    val LOST_MARKS: DeferredHolder<EntityType<*>, EntityType<LostMarks>> = ENTITIES.register("lost_marks") { ->
        EntityType.Builder.of(::LostMarks, MobCategory.MISC)
            .sized(0.5f, 0.5f)
            .fireImmune()
            .noSummon()
            .noLootTable()
            .clientTrackingRange(8)
            .updateInterval(20)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lost_marks")))
    }

    /** A soldier of the Hollow King's fallen army, still holding the roads at night. Fights with the new combat. */
    val HOLLOW_SOLDIER: DeferredHolder<EntityType<*>, EntityType<HollowSoldier>> = ENTITIES.register("hollow_soldier") { ->
        EntityType.Builder.of(::HollowSoldier, MobCategory.MONSTER)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.62f)
            .clientTrackingRange(8)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hollow_soldier")))
    }

    /** Halvard, the Fallen Warden: a boss who kneels at his post until someone comes near. Drawn 1.5 times humanoid size. */
    val FALLEN_WARDEN: DeferredHolder<EntityType<*>, EntityType<FallenWarden>> = ENTITIES.register("fallen_warden") { ->
        EntityType.Builder.of(::FallenWarden, MobCategory.MONSTER)
            .sized(0.9f, 2.9f)
            .eyeHeight(2.5f)
            .fireImmune()
            .clientTrackingRange(12)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "fallen_warden")))
    }

    /** A hill troll, a field boss asleep by the fire of its own camp in the wild. Drawn 1.6 times humanoid size. */
    val HILL_TROLL: DeferredHolder<EntityType<*>, EntityType<HillTroll>> = ENTITIES.register("hill_troll") { ->
        EntityType.Builder.of(::HillTroll, MobCategory.MONSTER)
            .sized(1.8f, 3.6f)
            .eyeHeight(3.2f)
            .clientTrackingRange(12)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hill_troll")))
    }

    /** A boulder a hill troll tore up and threw. */
    val TROLL_BOULDER: DeferredHolder<EntityType<*>, EntityType<TrollBoulder>> = ENTITIES.register("troll_boulder") { ->
        EntityType.Builder.of(::TrollBoulder, MobCategory.MISC)
            .sized(0.8f, 0.8f)
            .noLootTable()
            .clientTrackingRange(8)
            .updateInterval(2)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "troll_boulder")))
    }

    /** The Dusk Rider, a field boss on a black destrier that rides out at night. Drawn 1.15 times its model's size. */
    val DUSK_RIDER: DeferredHolder<EntityType<*>, EntityType<DuskRider>> = ENTITIES.register("dusk_rider") { ->
        EntityType.Builder.of(::DuskRider, MobCategory.MONSTER)
            .sized(1.4f, 3.2f)
            .eyeHeight(2.9f)
            .fireImmune()
            .noLootTable()
            .clientTrackingRange(12)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dusk_rider")))
    }

    /** A wisp of cold light cast from a Dusk Rider's lantern. */
    val LANTERN_WISP: DeferredHolder<EntityType<*>, EntityType<LanternWisp>> = ENTITIES.register("lantern_wisp") { ->
        EntityType.Builder.of(::LanternWisp, MobCategory.MISC)
            .sized(0.5f, 0.5f)
            .noLootTable()
            .clientTrackingRange(8)
            .updateInterval(2)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lantern_wisp")))
    }

    /** The ghost of a horse a Dusk Bridle calls; sized and saddled like a skeleton horse. */
    val SPECTRAL_STEED: DeferredHolder<EntityType<*>, EntityType<SpectralSteed>> = ENTITIES.register("spectral_steed") { ->
        EntityType.Builder.of(::SpectralSteed, MobCategory.MISC)
            .sized(1.3964844f, 1.6f)
            .eyeHeight(1.52f)
            .passengerAttachments(1.31875f)
            .noSummon()
            .noLootTable()
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "spectral_steed")))
    }
}
