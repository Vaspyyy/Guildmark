package io.github.vaspyyy.guildmark.registry

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
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
}
