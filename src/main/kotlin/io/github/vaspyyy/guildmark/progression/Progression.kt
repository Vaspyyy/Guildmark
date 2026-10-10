package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.player.Player
import kotlin.math.roundToInt

/** Server-side rules for guild XP, levels and perks. */
object Progression {
    /** Guild XP per Guild Mark a contract pays (before Haggler). */
    const val XP_PER_MARK = 10

    fun get(player: Player): GuildProgress = player.getData(ModAttachments.GUILD_PROGRESS)

    fun rank(player: Player, perk: Perk): Int = get(player).rank(perk)

    fun addXp(player: Player, amount: Int) {
        var progress = get(player)
        if (progress.isMaxLevel()) return
        var xp = progress.xp + amount
        var level = progress.level
        var points = progress.points
        while (level < GuildProgress.MAX_LEVEL && xp >= GuildProgress.xpToNext(level)) {
            xp -= GuildProgress.xpToNext(level)
            level++
            points++
        }
        if (level >= GuildProgress.MAX_LEVEL) xp = 0
        val leveledUp = level > progress.level
        progress = progress.copy(level = level, xp = xp, points = points)
        player.setData(ModAttachments.GUILD_PROGRESS, progress)

        if (leveledUp) {
            player.sendSystemMessage(Component.translatable("message.guildmark.level_up", level, Component.keybind("key.guildmark.ledger")))
            player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6f, 1.0f)
        }
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
    }

    /** Spend one point on [perk]. Returns false if there's no point or the perk is maxed. */
    fun spend(player: Player, perk: Perk): Boolean {
        val progress = get(player)
        val rank = progress.rank(perk)
        if (progress.points <= 0 || rank >= perk.maxRank) return false
        player.setData(ModAttachments.GUILD_PROGRESS, progress.copy(points = progress.points - 1, perks = progress.perks + (perk.id to rank + 1)))
        applyAttributes(player)
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f)
        return true
    }

    /** Re-apply stat perks; call on login, respawn and after buying a perk. */
    fun applyAttributes(player: Player) {
        val progress = get(player)
        for (perk in Perk.entries) {
            val attribute = perk.attribute ?: continue
            val instance = player.getAttribute(attribute) ?: continue
            val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "perk/${perk.id}")
            val rank = progress.rank(perk)
            if (rank == 0) instance.removeModifier(id)
            else instance.addOrUpdateTransientModifier(AttributeModifier(id, perk.perRank * rank, perk.operation))
        }
        if (player.health > player.maxHealth) player.health = player.maxHealth
    }

    /** Guild Marks for a contract after Haggler. */
    fun reward(player: Player, base: Int): Int =
        (base * (1.0 + Perk.HAGGLER.perRank * rank(player, Perk.HAGGLER))).roundToInt()

    /** Extra deadline days from Pathfinder. */
    fun bonusDays(player: Player): Int = (Perk.PATHFINDER.perRank * rank(player, Perk.PATHFINDER)).toInt()
}
