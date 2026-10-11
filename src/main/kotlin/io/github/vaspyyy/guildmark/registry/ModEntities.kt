package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.guild.RivalAdventurer
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.guild.GuildMember
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
}
