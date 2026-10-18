package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.death.PurseState
import net.minecraft.network.codec.ByteBufCodecs
import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.atlas.AtlasState
import io.github.vaspyyy.guildmark.combat.CombatState
import io.github.vaspyyy.guildmark.combat.StanceState
import io.github.vaspyyy.guildmark.guild.RivalChallenge
import io.github.vaspyyy.guildmark.progression.BuildCache
import io.github.vaspyyy.guildmark.progression.CharacterSheet
import io.github.vaspyyy.guildmark.progression.GuildProgress
import io.github.vaspyyy.guildmark.progression.PlacedBlocks
import io.github.vaspyyy.guildmark.progression.SkillBuffer
import io.github.vaspyyy.guildmark.progression.SkillState
import io.github.vaspyyy.guildmark.road.TrafficState
import io.github.vaspyyy.guildmark.road.TravelProgress
import io.github.vaspyyy.guildmark.story.MainQuestState
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import io.github.vaspyyy.guildmark.wardfire.FlaskState
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

    /** On a village bell's chunk: the village has put up its bounty board, or found no room for one. */
    val BOUNTY_BOARD_CHECKED: DeferredHolder<AttachmentType<*>, AttachmentType<Boolean>> = ATTACHMENTS.register("bounty_board_checked") { ->
        AttachmentType.builder { -> false }.serialize(Codec.BOOL.fieldOf("checked")).build()
    }

    /** Set on a village's bell chunk once the village has been given its Wardfire (or found to have one). */
    val WARDFIRE_CHECKED: DeferredHolder<AttachmentType<*>, AttachmentType<Boolean>> = ATTACHMENTS.register("wardfire_checked") { ->
        AttachmentType.builder { -> false }.serialize(Codec.BOOL.fieldOf("checked")).build()
    }

    /** Ores and logs put down in a chunk, so breaking them again pays no skill XP (see PlacedBlocks). */
    val PLACED_BLOCKS: DeferredHolder<AttachmentType<*>, AttachmentType<PlacedBlocks>> = ATTACHMENTS.register("placed_blocks") { ->
        AttachmentType.builder { -> PlacedBlocks() }.serialize(PlacedBlocks.CODEC.fieldOf("positions")) { !it.positions.isEmpty() }.build()
    }

    /** Day index a villager last pinned a note, so each villager posts at most once a day. */
    val LAST_PINNED_DAY: DeferredHolder<AttachmentType<*>, AttachmentType<Long>> = ATTACHMENTS.register("last_pinned_day") { ->
        AttachmentType.builder { -> -1L }.serialize(Codec.LONG.fieldOf("day")).build()
    }

    /** A player's guild level, banked guild XP and adventurer rank. Survives death and syncs to its own player only. */
    val GUILD_PROGRESS: DeferredHolder<AttachmentType<*>, AttachmentType<GuildProgress>> = ATTACHMENTS.register("guild_progress") { ->
        AttachmentType.builder { -> GuildProgress() }
            .serialize(GuildProgress.CODEC.fieldOf("progress"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, GuildProgress.STREAM_CODEC)
            .build()
    }

    /** A player's stats and learned skill tree nodes (see CharacterSheet). Kept through death, synced to its player. */
    val SHEET: DeferredHolder<AttachmentType<*>, AttachmentType<CharacterSheet>> = ATTACHMENTS.register("sheet") { ->
        AttachmentType.builder { -> CharacterSheet() }
            .serialize(CharacterSheet.CODEC.fieldOf("sheet"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, CharacterSheet.STREAM_CODEC)
            .build()
    }

    /** A player's skill XP (see Skills). Kept through death, synced to its player. */
    val SKILLS: DeferredHolder<AttachmentType<*>, AttachmentType<SkillState>> = ATTACHMENTS.register("skills") { ->
        AttachmentType.builder { -> SkillState() }
            .serialize(SkillState.CODEC.fieldOf("xp"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, SkillState.STREAM_CODEC)
            .build()
    }

    /** What a player's stats, skills and nodes add up to, worked out again when they change (never saved). */
    val BUILD: DeferredHolder<AttachmentType<*>, AttachmentType<BuildCache>> = ATTACHMENTS.register("build") { ->
        AttachmentType.builder { -> BuildCache() }.build()
    }

    /** Skill XP earned but not yet added up, and the timers of the passive bonuses (server side, never saved). */
    val SKILL_BUFFER: DeferredHolder<AttachmentType<*>, AttachmentType<SkillBuffer>> = ATTACHMENTS.register("skill_buffer") { ->
        AttachmentType.builder { -> SkillBuffer() }.build()
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

    /** On a bandit: the traveller or caravan it was sent to waylay. */
    val WAYLAYS: DeferredHolder<AttachmentType<*>, AttachmentType<UUID>> = ATTACHMENTS.register("waylays") { ->
        AttachmentType.builder { -> net.minecraft.util.Util.NIL_UUID }.serialize(UUIDUtil.CODEC.fieldOf("traveller")).build()
    }

    /** On a waylaid traveller or caravan: the damage each player has dealt its bandits, so those who fought are paid. */
    val AMBUSH_FIGHTERS: DeferredHolder<AttachmentType<*>, AttachmentType<Map<UUID, Float>>> = ATTACHMENTS.register("ambush_fighters") { ->
        AttachmentType.builder { -> mapOf<UUID, Float>() }.serialize(Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.FLOAT).fieldOf("damage")).build()
    }

    /** Marks a guild hall receptionist, holding the board anchor of the village they serve. */
    val RECEPTIONIST: DeferredHolder<AttachmentType<*>, AttachmentType<BlockPos>> = ATTACHMENTS.register("receptionist") { ->
        AttachmentType.builder { -> BlockPos.ZERO }.serialize(BlockPos.CODEC.fieldOf("board")).build()
    }

    /** Marks one of the named story characters, by id (see StoryCharacter). */
    val CHARACTER: DeferredHolder<AttachmentType<*>, AttachmentType<String>> = ATTACHMENTS.register("character") { ->
        AttachmentType.builder { -> "" }.serialize(Codec.STRING.fieldOf("id")).build()
    }

    /** How far a player has got in the main story, the hunt for the Hollow Crown. */
    val MAIN_QUEST: DeferredHolder<AttachmentType<*>, AttachmentType<MainQuestState>> = ATTACHMENTS.register("main_quest") { ->
        AttachmentType.builder { -> MainQuestState() }
            .serialize(MainQuestState.CODEC.fieldOf("state"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, MainQuestState.STREAM_CODEC)
            .build()
    }

    /** The other members of a player's party, synced so the tracker can show them. Rebuilt on login. */
    val PARTY: DeferredHolder<AttachmentType<*>, AttachmentType<List<UUID>>> = ATTACHMENTS.register("party") { ->
        AttachmentType.builder { -> listOf<UUID>() }
            .sync({ holder, player -> holder === player }, UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()))
            .build()
    }

    /** Open rival challenges against villages the player's guild holds, synced for the tracker. Rebuilt every few seconds. */
    val CHALLENGES: DeferredHolder<AttachmentType<*>, AttachmentType<List<RivalChallenge>>> = ATTACHMENTS.register("challenges") { ->
        AttachmentType.builder { -> listOf<RivalChallenge>() }
            .sync({ holder, player -> holder === player }, RivalChallenge.STREAM_CODEC.apply(ByteBufCodecs.list()))
            .build()
    }

    /** The title a player wears after their name (see Title), or empty. Everyone nearby sees it. */
    val TITLE: DeferredHolder<AttachmentType<*>, AttachmentType<String>> = ATTACHMENTS.register("title") { ->
        AttachmentType.builder { -> "" }
            .serialize(Codec.STRING.fieldOf("title"))
            .copyOnDeath()
            .sync({ _, _ -> true }, ByteBufCodecs.STRING_UTF8)
            .build()
    }

    /** How many chapters of each named character's story a player has finished. */
    val STORY: DeferredHolder<AttachmentType<*>, AttachmentType<Map<String, Int>>> = ATTACHMENTS.register("story") { ->
        AttachmentType.builder { -> mapOf<String, Int>() }
            .serialize(Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("chapters"))
            .copyOnDeath()
            .build()
    }

    /** A player's Warden's Flask and its draughts (see WardensFlask). Kept through death, synced to its player for the HUD. */
    val FLASK: DeferredHolder<AttachmentType<*>, AttachmentType<FlaskState>> = ATTACHMENTS.register("flask") { ->
        AttachmentType.builder { -> FlaskState() }
            .serialize(FlaskState.CODEC.fieldOf("flask"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, FlaskState.STREAM_CODEC)
            .build()
    }

    /** Where a player's lost Guild Marks lie (see Purses). Kept through death, synced to its player for the map and death screen. */
    val PURSE: DeferredHolder<AttachmentType<*>, AttachmentType<PurseState>> = ATTACHMENTS.register("purse") { ->
        AttachmentType.builder { -> PurseState() }
            .serialize(PurseState.CODEC.fieldOf("purse"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, PurseState.STREAM_CODEC)
            .build()
    }

    /** The places a player has found, for the compass and the map (see Atlas). Kept through death. */
    val ATLAS: DeferredHolder<AttachmentType<*>, AttachmentType<AtlasState>> = ATTACHMENTS.register("atlas") { ->
        AttachmentType.builder { -> AtlasState() }
            .serialize(AtlasState.CODEC.fieldOf("places"))
            .copyOnDeath()
            .build()
    }

    /** Day index a player last took a guild hall contract (one a day). */
    val HALL_CONTRACT_DAY: DeferredHolder<AttachmentType<*>, AttachmentType<Long>> = ATTACHMENTS.register("hall_contract_day") { ->
        AttachmentType.builder { -> -1L }.serialize(Codec.LONG.fieldOf("day")).build()
    }

    /** Day index a player last took a lair hunt from a guild hall (one a day). */
    val LAIR_HUNT_DAY: DeferredHolder<AttachmentType<*>, AttachmentType<Long>> = ATTACHMENTS.register("lair_hunt_day") { ->
        AttachmentType.builder { -> -1L }.serialize(Codec.LONG.fieldOf("day")).build()
    }

    /** Marks a monster marching in a siege, with the siege's id. */
    val SIEGE: DeferredHolder<AttachmentType<*>, AttachmentType<Int>> = ATTACHMENTS.register("siege") { ->
        AttachmentType.builder { -> -1 }.build()
    }

    /** Marks a lair boss with the id of its lair. */
    val LAIR_BOSS: DeferredHolder<AttachmentType<*>, AttachmentType<Int>> = ATTACHMENTS.register("lair_boss") { ->
        AttachmentType.builder { -> -1 }.serialize(Codec.INT.fieldOf("lair")).build()
    }

    /** Marks one of an outlaw band, captain or not, with the id of its band (see outlaw/OutlawCamps). */
    val OUTLAW: DeferredHolder<AttachmentType<*>, AttachmentType<Int>> = ATTACHMENTS.register("outlaw") { ->
        AttachmentType.builder { -> -1 }.serialize(Codec.INT.fieldOf("band")).build()
    }

    /** Marks a merchant or traveller walking the roads on its own (see Traffic). */
    val TRAFFIC: DeferredHolder<AttachmentType<*>, AttachmentType<TrafficState>> = ATTACHMENTS.register("traffic") { ->
        AttachmentType.builder { -> TrafficState() }.serialize(TrafficState.CODEC.fieldOf("traffic")).build()
    }

    /** A player's stamina, focus and current combat move (server side, never saved). */
    val COMBAT: DeferredHolder<AttachmentType<*>, AttachmentType<CombatState>> = ATTACHMENTS.register("combat") { ->
        AttachmentType.builder { -> CombatState() }.build()
    }

    /** A creature's stance under the new combat (server side, never saved). */
    val STANCE: DeferredHolder<AttachmentType<*>, AttachmentType<StanceState>> = ATTACHMENTS.register("stance") { ->
        AttachmentType.builder { -> StanceState() }.build()
    }
}
