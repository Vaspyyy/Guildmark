package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.story.MainQuest
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import kotlin.math.roundToInt

/** Server-side rules for guild XP, levels and ranks. Levels themselves are grown at Wardfires, see [Levelling]. */
object Progression {
    /** Guild XP per Guild Mark a contract pays. */
    const val XP_PER_MARK = 10
    /** Most guild XP that can be banked. */
    private const val MAX_BANK = 10_000_000L

    fun get(player: Player): GuildProgress = player.getData(ModAttachments.GUILD_PROGRESS)

    /** [base] guild XP after the config's multiplier and the tree's guild XP nodes. */
    fun scaled(player: Player, base: Int): Int =
        (base * GuildmarkConfig.XP_MULTIPLIER.get() * (1.0 + Talents[player, Bonus.GUILD_XP]).coerceAtLeast(0.0)).toInt()

    /** Banks guild XP; the first time there's enough to grow a level, says where to spend it. */
    fun addXp(player: Player, base: Int) {
        val amount = scaled(player, base)
        val progress = get(player)
        if (amount <= 0 || progress.isMaxLevel()) return
        val after = progress.copy(xp = (progress.xp.toLong() + amount).coerceAtMost(MAX_BANK).toInt())
        player.setData(ModAttachments.GUILD_PROGRESS, after)
        if (player !is ServerPlayer || after.levelsBanked() == 0) return
        val buffer = player.getData(ModAttachments.SKILL_BUFFER)
        if (buffer.toldReady) return
        buffer.toldReady = true
        val key = if (GuildmarkConfig.WARDFIRES.get()) "message.guildmark.ready_to_grow" else "message.guildmark.ready_to_grow.anywhere"
        player.sendSystemMessage(Component.translatable(key, Component.keybind("key.guildmark.ledger")).withColor(0xE8C26A))
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.4f, 1.3f)
    }

    /** A passed trial: the adventurer moves up to [rank]. */
    fun promote(player: Player, rank: Int) {
        val progress = get(player)
        if (rank <= progress.adventurerRank) return
        player.setData(ModAttachments.GUILD_PROGRESS, progress.copy(adventurerRank = rank))
        val title = AdventurerRank.of(rank).title
        player.sendSystemMessage(Component.translatable("message.guildmark.promoted", title))
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 0.8f)
        GuildNews.add(player.level(), "news.guildmark.promoted", player.name.string, AdventurerRank.of(rank).letter)
        MainQuest.onPromoted(player)
        Milestones.onRank(player, rank)
    }

    /** Guild Marks for a contract, with the tree's contract nodes. */
    fun reward(player: Player, base: Int): Int =
        (base * (1.0 + Talents[player, Bonus.CONTRACT_MARKS]).coerceAtLeast(0.0)).roundToInt()

    /** Extra deadline days from the tree. */
    fun bonusDays(player: Player): Int = Talents[player, Bonus.DEADLINE_DAYS].toInt()
}
