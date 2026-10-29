package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.village.live.VillageRec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState

/**
 * What a lost siege broke (living-world/villages/design.md 11.6): each palisade log and lantern `Sieges.damage` tears down is written
 * here first, where it stood and what it was, in the village's damage list ([TroubleData.Kept.damage], at most [MOST]; what breaks
 * past it is simply not mended), and [Mend] puts them back. The project's lost progress is not mended: that is the cost of losing.
 */
object VillageDamage {
    /** The most broken blocks a village keeps to mend: a third of the logs of the biggest palisade (about 800), with its lanterns. */
    const val MOST = 600

    /**
     * The block at [pos], [state] as it stood, is about to be broken by the siege on the village whose bell is at [bell]: it goes in that
     * village's damage list. Nothing for a capital's, a bell no village of the registry has, or with trouble off.
     */
    fun record(level: ServerLevel, bell: BlockPos, pos: BlockPos, state: BlockState) {
        val rec = Trouble.village(level, bell) ?: return
        val data = TroubleData.get(level)
        val kept = data.of(rec.key)
        if (kept.damage.size >= MOST) return
        val at = pos.asLong()
        if (kept.damage.any { it.pos == at }) return
        kept.damage.add(TroubleData.Broken(at, state))
        data.setDirty()
    }

    /** How many blocks the village [key] has still to mend. */
    fun left(level: ServerLevel, key: Long): Int = TroubleData.peek(level)?.peek(key)?.damage?.size ?: 0

    /**
     * The siege on [rec] was lost and its damage is listed: the mending crew sets out on the next working morning, the second if the
     * village's times are hard (11.6, 11.7). Once set, a second loss before the mend only adds to the list.
     */
    fun lost(level: ServerLevel, rec: VillageRec) {
        val data = TroubleData.peek(level) ?: return
        val kept = data.peek(rec.key) ?: return
        if (kept.damage.isEmpty() || kept.mendFrom != TroubleData.NONE) return
        val today = VillageBoards.day(level)
        // The town day turns at six in the morning, so "the next morning" is always the next town day's, after midnight too
        var day = today + 1
        var mornings = if (Fortunes.times(level, rec.key) == Times.HARD) 2 else 1
        while (true) {
            if (!Days.restDay(day)) mornings--
            if (mornings == 0) break
            day++
        }
        kept.mendFrom = day
        data.setDirty()
    }
}
