package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.wardfire.Wardfires
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource

/**
 * Growing stronger. Guild XP is banked as it's earned and spent at a Wardfire, a level at a time: each
 * level raises one stat and gives a skill tree point. Nodes are learned anywhere from the Guild Ledger;
 * forgetting them costs Guild Marks and a Wardfire, so a build is a choice you live with for a while.
 */
object Levelling {
    /** Most stat raises or nodes one request can hold. */
    private const val MAX_AT_ONCE = 64
    /** Guild Marks to forget one node, and per node to start the tree over (at least [RESET_MIN]). */
    const val UNLEARN_COST = 3
    const val RESET_PER_NODE = 1
    const val RESET_MIN = 5

    /** Beside the lit Wardfire at [pos], or anywhere in a world without Wardfires (so nothing waits on one). */
    fun atRest(player: ServerPlayer, pos: BlockPos?): Boolean {
        if (!GuildmarkConfig.WARDFIRES.get()) return true
        return pos != null && Wardfires.near(player, pos)
    }

    /** What forgetting the whole tree costs [sheet]'s owner. */
    fun resetCost(sheet: CharacterSheet): Int = (sheet.nodes.size * RESET_PER_NODE).coerceAtLeast(RESET_MIN)

    /**
     * Raises the stats in [raises], one point each, in order. Raises owed from levels already gained are
     * free; each one after costs the next level's guild XP. Stops at the first that can't be paid.
     */
    fun grow(player: ServerPlayer, pos: BlockPos?, raises: List<String>) {
        if (!player.isAlive || !atRest(player, pos)) return
        val before = Progression.get(player)
        var progress = before
        var sheet = Talents.sheet(player)
        var raised = 0
        for (id in raises.take(MAX_AT_ONCE)) {
            val stat = Stat.byId(id) ?: continue
            val value = sheet.stat(stat)
            if (value >= Stat.MAX) continue
            if (sheet.owed(progress.level) <= 0) {
                if (progress.isMaxLevel()) break
                val cost = progress.xpToNext()
                if (progress.xp < cost) break
                progress = progress.copy(level = progress.level + 1, xp = progress.xp - cost)
            }
            sheet = sheet.withStat(stat, value + 1)
            raised++
        }
        if (raised == 0) return
        player.setData(ModAttachments.GUILD_PROGRESS, progress)
        player.setData(ModAttachments.SHEET, sheet)
        player.getData(ModAttachments.SKILL_BUFFER).toldReady = false
        Talents.apply(player)
        val level = player.level()
        if (progress.level > before.level) {
            player.sendSystemMessage(Component.translatable("message.guildmark.grew", progress.level, Component.keybind("key.guildmark.ledger")).withColor(GOLD))
            level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6f, 1.0f)
        } else {
            level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.2f)
        }
    }

    /** Learns the nodes in [ids], in order, each paid with a tree point; anywhere, at any time. */
    fun learn(player: ServerPlayer, ids: List<String>) {
        if (!player.isAlive) return
        val progress = Progression.get(player)
        val skills = Skills.get(player)
        var sheet = Talents.sheet(player)
        var learned = 0
        for (id in ids.take(MAX_AT_ONCE)) {
            val node = SkillTree.node(id) ?: continue
            if (SkillTree.blocked(node, progress, skills, sheet) != null) continue
            sheet = sheet.copy(nodes = sheet.nodes + id)
            learned++
        }
        if (learned == 0) return
        player.setData(ModAttachments.SHEET, sheet)
        Talents.apply(player)
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.8f, 1.1f)
        Milestones.award(player, Milestones.LEDGER)
    }

    /** Forgets one node for [UNLEARN_COST] Guild Marks at a Wardfire, if the rest still reach the root without it. */
    fun unlearn(player: ServerPlayer, pos: BlockPos?, id: String) {
        if (!player.isAlive || !atRest(player, pos)) return
        val sheet = Talents.sheet(player)
        if (id !in sheet.nodes) return
        val rest = sheet.nodes - id
        if (!SkillTree.connected(rest)) return
        if (!pay(player, UNLEARN_COST)) return
        player.setData(ModAttachments.SHEET, sheet.copy(nodes = rest))
        Talents.apply(player)
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 0.8f, 0.8f)
    }

    /** Forgets every node for [resetCost] Guild Marks at a Wardfire. Stats stay where they are. */
    fun reset(player: ServerPlayer, pos: BlockPos?) {
        if (!player.isAlive || !atRest(player, pos)) return
        val sheet = Talents.sheet(player)
        if (sheet.nodes.isEmpty()) return
        if (!pay(player, resetCost(sheet))) return
        player.setData(ModAttachments.SHEET, sheet.copy(nodes = listOf()))
        Talents.apply(player)
        player.sendSystemMessage(Component.translatable("message.guildmark.tree_reset").withColor(GOLD))
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1f, 0.6f)
    }

    private fun pay(player: ServerPlayer, marks: Int): Boolean {
        if (player.isCreative) return true
        if (TownControl.marks(player) < marks) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.tree_marks", marks).withColor(RED))
            return false
        }
        TownControl.pay(player, marks)
        return true
    }

    private const val GOLD = 0xE8C26A
    private const val RED = 0xE05A4A
}
