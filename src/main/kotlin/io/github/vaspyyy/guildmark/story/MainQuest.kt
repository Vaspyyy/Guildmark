package io.github.vaspyyy.guildmark.story

import io.github.vaspyyy.guildmark.progression.Milestones
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.lair.Lair
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.LodestoneTracker
import net.minecraft.world.level.Level
import java.util.Optional

/** A player's place in the main story. */
data class MainQuestState(
    /** Shards of the Hollow Crown found so far (0 to 7). */
    val found: Int = 0,
    /** The vault (a lair id) holding the next shard, or -1. */
    val vault: Int = -1,
    /** Where the shard compass points: the next vault, the throne, or nowhere yet. */
    val target: Optional<BlockPos> = Optional.empty(),
    /** The Hollow King is dead and the crown broken. */
    val finished: Boolean = false,
) {
    val started: Boolean get() = found > 0
    val active: Boolean get() = started && !finished
    /** The courtier guarding the next shard, if the next goal is a vault. */
    val nextCourtier: Courtier? get() = if (active) Courtier.forShard(found) else null

    companion object {
        const val SHARDS = 7

        val CODEC: Codec<MainQuestState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("found", 0).forGetter(MainQuestState::found),
                Codec.INT.optionalFieldOf("vault", -1).forGetter(MainQuestState::vault),
                BlockPos.CODEC.optionalFieldOf("target").forGetter(MainQuestState::target),
                Codec.BOOL.optionalFieldOf("finished", false).forGetter(MainQuestState::finished),
            ).apply(i, ::MainQuestState)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, MainQuestState> = ByteBufCodecs.fromCodec(CODEC)
    }
}

/**
 * The main story, "The Hollow Crown". Wren's story ends with the first shard and a compass. The compass
 * leads to a vault for each of the six courtiers in turn (each only once you hold the rank it needs);
 * the last shard turns it toward the Hollow Throne, where Maren waits and the Hollow King rises.
 */
object MainQuest {
    fun get(player: Player): MainQuestState = player.getData(ModAttachments.MAIN_QUEST)
    private fun set(player: Player, state: MainQuestState) {
        player.setData(ModAttachments.MAIN_QUEST, state)
        if (state.found >= 1) Milestones.award(player, Milestones.FIRST_SHARD)
        if (state.found >= MainQuestState.SHARDS) Milestones.award(player, Milestones.SEVEN_SHARDS)
        if (state.finished) Milestones.award(player, Milestones.HOLLOW_KING)
    }

    /** Wren hands over the first shard and her compass. */
    fun start(level: ServerLevel, player: Player) {
        if (get(player).started) return
        set(player, MainQuestState(found = 1))
        give(player, shard(0))
        give(player, compass())
        say(player, StoryCharacter.WREN, "main.guildmark.start")
        player.sendSystemMessage(Component.translatable("message.guildmark.main_started").withStyle { it.withColor(0xC08CFF) })
        retarget(level, player)
    }

    /** Point the quest (and every shard compass the player carries) at whatever comes next. */
    fun retarget(level: ServerLevel, player: Player) {
        var state = get(player)
        if (!state.active) {
            set(player, state.copy(target = Optional.empty()))
            refreshCompasses(player)
            return
        }
        val overworld = level.server.overworld()
        val courtier = state.nextCourtier
        state = when {
            courtier == null -> state.copy(vault = -1, target = Optional.of(HollowThrone.get(overworld).ensure(overworld)))
            Progression.get(player).adventurerRank < courtier.minRank -> state.copy(target = Optional.empty())
            else -> {
                val lairs = Lairs.get(overworld)
                val existing = lairs.lair(state.vault)?.takeIf { !it.cleared && it.shard == courtier.shard }
                val vault = existing ?: lairs.createVault(overworld, player.blockPosition(), courtier)
                if (vault == null) state.copy(target = Optional.empty())
                else state.copy(vault = vault.id, target = Optional.of(vault.pos))
            }
        }
        set(player, state)
        refreshCompasses(player)
    }

    /** A vault's courtier died at [site]: everyone there who was hunting that shard takes it. */
    fun onVaultCleared(level: ServerLevel, lair: Lair, heroes: List<Player>, site: BlockPos) {
        val courtier = Courtier.forShard(lair.shard) ?: return
        for (hero in heroes) {
            val state = get(hero)
            if (!state.active || state.found != courtier.shard) continue
            set(hero, state.copy(found = state.found + 1, vault = -1))
            give(hero, shard(courtier.shard))
            hero.sendSystemMessage(Component.translatable("message.guildmark.shard_found", courtier.shard + 1, MainQuestState.SHARDS).withStyle { it.withColor(0xC08CFF) })
            level.playSound(null, hero.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5f, 0.6f)
            say(hero, courtier.writer, "main.guildmark.letter${courtier.shard}")
            GuildNews.add(level, "news.guildmark.shard", hero.name.string, "#courtier.guildmark.${courtier.id}")
            retarget(level, hero)
            val next = get(hero).nextCourtier
            if (next != null && get(hero).target.isEmpty) {
                hero.sendSystemMessage(Component.translatable("message.guildmark.shard_rank", AdventurerRank.of(next.minRank).letter))
            }
        }
        // Halfway there, the Last Warden shows herself
        if (courtier == Courtier.BARROW_LORD && heroes.isNotEmpty()) Characters.appear(level, StoryCharacter.MAREN, site, 20 * 150)
    }

    /** Rank changed: a vault that was waiting on it can now be found. */
    fun onPromoted(player: Player) {
        val level = player.level() as? ServerLevel ?: return
        val state = get(player)
        if (state.active && state.target.isEmpty) {
            retarget(level, player)
            if (get(player).target.isPresent) player.sendSystemMessage(Component.translatable("message.guildmark.compass_turns"))
        }
    }

    /** The King is dead: the story is over for this player. */
    fun finish(player: Player) {
        set(player, get(player).copy(found = MainQuestState.SHARDS, finished = true, vault = -1, target = Optional.empty()))
        refreshCompasses(player)
    }

    /** For testing: jump to having found [found] shards. */
    fun setFound(level: ServerLevel, player: Player, found: Int) {
        set(player, MainQuestState(found = found.coerceIn(0, MainQuestState.SHARDS)))
        for (i in 0 until found.coerceAtMost(MainQuestState.SHARDS)) give(player, shard(i))
        if (found > 0 && !carriesCompass(player)) give(player, compass())
        retarget(level, player)
    }

    fun shard(index: Int): ItemStack {
        val stack = ItemStack(ModItems.CROWN_SHARD.get())
        stack.set(ModDataComponents.SHARD.get(), index)
        stack.set(DataComponents.ITEM_NAME, Component.translatable("item.guildmark.crown_shard.n", ROMAN[index]))
        val from = Courtier.forShard(index)?.title ?: Component.translatable("courtier.guildmark.wren")
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.crown_shard.from", from).withStyle { it.withColor(0x9A7AB8).withItalic(true) })))
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    fun compass(): ItemStack {
        val stack = ItemStack(Items.COMPASS)
        stack.set(ModDataComponents.SHARD_COMPASS.get(), true)
        stack.set(DataComponents.ITEM_NAME, Component.translatable("item.guildmark.shard_compass"))
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.shard_compass.lore").withStyle { it.withColor(0x9A7AB8).withItalic(true) })))
        return stack
    }

    fun carriesCompass(player: Player): Boolean =
        (0 until player.inventory.containerSize).any { player.inventory.getItem(it).has(ModDataComponents.SHARD_COMPASS.get()) }

    /** Point every shard compass the player carries at the current target. */
    fun refreshCompasses(player: Player) {
        val target = get(player).target.map { GlobalPos.of(Level.OVERWORLD, it) }
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (!stack.has(ModDataComponents.SHARD_COMPASS.get())) continue
            if (target.isPresent) stack.set(DataComponents.LODESTONE_TRACKER, LodestoneTracker(target, false))
            else stack.remove(DataComponents.LODESTONE_TRACKER)
        }
    }

    /** Take every crown shard out of the player's inventory (they're set on the throne). */
    fun takeShards(player: Player) {
        for (slot in 0 until player.inventory.containerSize) {
            if (player.inventory.getItem(slot).`is`(ModItems.CROWN_SHARD.get())) player.inventory.setItem(slot, ItemStack.EMPTY)
        }
    }

    /** A line from one of the characters, as a letter or a voice in the player's ear. */
    fun say(player: Player, who: StoryCharacter, key: String) {
        player.sendSystemMessage(Component.translatable("gui.guildmark.character.says", who.title, Component.translatable(key)))
    }

    private fun give(player: Player, stack: ItemStack) = player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII")
}
