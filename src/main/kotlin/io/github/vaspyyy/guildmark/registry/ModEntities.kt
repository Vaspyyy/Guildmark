package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.guild.GuildMember
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
}
