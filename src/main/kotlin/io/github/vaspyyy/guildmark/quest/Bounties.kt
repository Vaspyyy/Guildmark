package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * Wanted notes on the outlaw bands camped out in the wild (see outlaw/OutlawCamps). A board posts one only while a band
 * at large has made camp within reach of it: the note names the captain, says what they're wanted for and what's on
 * their head, which goes up the longer they're left at it. Taking it points the way to their camp, and the captain's
 * fall counts for everyone close by who holds a note on them.
 */
object Bounties {
    /** How far from its board a wanted note reaches. */
    private const val RANGE = 1200.0

    /** The one target a wanted note takes in a pool. */
    val OUTLAW: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "outlaw")

    /** A band's notice as a board pins it: whose band, the captain's name, the price on them and what they're wanted for. */
    class Wanted(val site: Int, val subject: String, val reward: Int, val story: String)

    /** The bands at large within reach of the board at [board], nearest first. */
    fun wanted(level: ServerLevel, board: BlockPos): List<Wanted> {
        if (level.dimension() != Level.OVERWORLD || !Outlaws.enabled()) return listOf()
        return OutlawCamps.get(level).wanted(board, RANGE).map(::notice)
    }

    /** [note] made out against [notice]'s band: whose head, what for, and the price on it if that's more. */
    fun dress(note: QuestNote, notice: Wanted): QuestNote =
        note.copy(subject = notice.subject, site = notice.site, reward = maxOf(note.reward, notice.reward), story = notice.story)

    /** A wanted note on a band that's no longer at large: its captain fallen, or the band gone. */
    fun stale(level: ServerLevel, note: QuestNote): Boolean {
        if (note.type != QuestType.BOUNTY || note.site < 0 || level.dimension() != Level.OVERWORLD) return false
        val band = OutlawCamps.get(level).band(note.site)
        return band == null || band.slain
    }

    private fun notice(band: OutlawBand): Wanted {
        val crime = band.latestCrime()?.id ?: "nothing"
        val where = band.region.ifEmpty { "#${Outlaws.WILDS}" }
        return Wanted(band.id, band.label, band.bounty, "quest.guildmark.wanted_for.$crime|$where")
    }

    /**
     * The band [note] names, while it's still at large; a note with no name on it (one made for testing) goes after the
     * nearest band in reach of [board].
     */
    fun quarry(level: ServerLevel, board: BlockPos, note: QuestNote): OutlawBand? {
        if (level.dimension() != Level.OVERWORLD) return null
        val camps = OutlawCamps.get(level)
        if (note.site >= 0) return camps.band(note.site)?.takeIf { it.built && !it.slain && it.captain() != null }
        return camps.wanted(board, RANGE).firstOrNull()
    }

    /** Whether [player] already carries an open contract on band [site]. */
    fun holds(player: Player, site: Int): Boolean {
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.BOUNTY) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.site == site && state.progress < note.count && !state.isExpired(player.level().gameTime)) return true
        }
        return false
    }

    /** The captain of band [site] fell: every one of [players] with a contract on them has the job done. */
    fun onSlain(players: List<Player>, site: Int) {
        for (player in players) {
            val inventory = player.inventory
            for (slot in 0 until inventory.containerSize) {
                val stack = inventory.getItem(slot)
                val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
                if (note.type != QuestType.BOUNTY) continue
                val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
                if (state.site != site || state.progress >= note.count || state.isExpired(player.level().gameTime)) continue
                Contracts.setProgress(stack, note.count)
                player.sendOverlayMessage(Component.translatable("message.guildmark.bounty_done", state.labelText()))
            }
        }
    }
}
