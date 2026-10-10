package io.github.vaspyyy.guildmark.registry

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.GuildProgress
import io.github.vaspyyy.guildmark.road.TrafficState
import io.github.vaspyyy.guildmark.road.TravelProgress
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.UUID

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

    /** An escorted traveller's progress along its road. */
    val TRAVEL_PROGRESS: DeferredHolder<AttachmentType<*>, AttachmentType<TravelProgress>> = ATTACHMENTS.register("travel_progress") { ->
        AttachmentType.builder { -> TravelProgress() }.serialize(TravelProgress.CODEC.fieldOf("progress")).build()
    }

    /** Waypoint where bandits lie in wait for this traveller or caravan, or -1 for a quiet trip. */
    val AMBUSH_AT: DeferredHolder<AttachmentType<*>, AttachmentType<Int>> = ATTACHMENTS.register("ambush_at") { ->
        AttachmentType.builder { -> -1 }.serialize(Codec.INT.fieldOf("waypoint")).build()
    }

    /** The bandits currently attacking this traveller or caravan. */
    val AMBUSHERS: DeferredHolder<AttachmentType<*>, AttachmentType<List<UUID>>> = ATTACHMENTS.register("ambushers") { ->
        AttachmentType.builder { -> listOf<UUID>() }.serialize(UUIDUtil.CODEC.listOf().fieldOf("bandits")).build()
    }

    /** Marks a guild hall receptionist, holding the board anchor of the village they serve. */
    val RECEPTIONIST: DeferredHolder<AttachmentType<*>, AttachmentType<BlockPos>> = ATTACHMENTS.register("receptionist") { ->
        AttachmentType.builder { -> BlockPos.ZERO }.serialize(BlockPos.CODEC.fieldOf("board")).build()
    }

    /** Day index a player last took a guild hall contract (one a day). */
    val HALL_CONTRACT_DAY: DeferredHolder<AttachmentType<*>, AttachmentType<Long>> = ATTACHMENTS.register("hall_contract_day") { ->
        AttachmentType.builder { -> -1L }.serialize(Codec.LONG.fieldOf("day")).build()
    }

    /** Day index a player last took a lair hunt from a guild hall (one a day). */
    val LAIR_HUNT_DAY: DeferredHolder<AttachmentType<*>, AttachmentType<Long>> = ATTACHMENTS.register("lair_hunt_day") { ->
        AttachmentType.builder { -> -1L }.serialize(Codec.LONG.fieldOf("day")).build()
    }

    /** Marks a lair boss with the id of its lair. */
    val LAIR_BOSS: DeferredHolder<AttachmentType<*>, AttachmentType<Int>> = ATTACHMENTS.register("lair_boss") { ->
        AttachmentType.builder { -> -1 }.serialize(Codec.INT.fieldOf("lair")).build()
    }

    /** Marks a merchant or traveller walking the roads on its own (see Traffic). */
    val TRAFFIC: DeferredHolder<AttachmentType<*>, AttachmentType<TrafficState>> = ATTACHMENTS.register("traffic") { ->
        AttachmentType.builder { -> TrafficState() }.serialize(TrafficState.CODEC.fieldOf("traffic")).build()
    }
}
