package io.github.vaspyyy.guildmark.registry

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.GuildProgress
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries

object ModAttachments {
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /** Set on a village bell's chunk once we've tried to give that village a board, so breaking it doesn't respawn it. */
    val VILLAGE_BOARD_CHECKED: DeferredHolder<AttachmentType<*>, AttachmentType<Boolean>> = ATTACHMENTS.register("village_board_checked") { ->
        AttachmentType.builder { -> false }.serialize(Codec.BOOL.fieldOf("checked")).build()
    }

    /** Day index a villager last pinned a note, so each villager posts at most once a day. */
    val LAST_PINNED_DAY: DeferredHolder<AttachmentType<*>, AttachmentType<Long>> = ATTACHMENTS.register("last_pinned_day") { ->
        AttachmentType.builder { -> -1L }.serialize(Codec.LONG.fieldOf("day")).build()
    }

    /** A player's guild level, XP and perks. Survives death and syncs to its own player only. */
    val GUILD_PROGRESS: DeferredHolder<AttachmentType<*>, AttachmentType<GuildProgress>> = ATTACHMENTS.register("guild_progress") { ->
        AttachmentType.builder { -> GuildProgress() }
            .serialize(GuildProgress.CODEC.fieldOf("progress"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, GuildProgress.STREAM_CODEC)
            .build()
    }
}
