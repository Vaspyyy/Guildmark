package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

/**
 * Puts the counting of [TaleLog] on a player (living-world/stories/design.md 11.3 and 7.6, Part E): the attachment
 * `ModAttachments.TALE_LOG`, and the three milestones the log earns.
 */
object TaleLogs {
    /** [player]'s log (empty if they have none yet). */
    fun of(player: Player): TaleLog = player.getData(ModAttachments.TALE_LOG)

    /**
     * Adds tale [tale] of [template], which ended [ending] in [capital] (null: no longer known), to [player]'s log, once: an ending that
     * is not A or B, or a tale the log counted already, changes nothing. Then the milestones are looked at, since the log is what they
     * read. Called from the one place pay is made, so it happens when the player is paid, whenever that is.
     */
    fun record(player: ServerPlayer, tale: Int, template: String, ending: String, capital: Long?) {
        val log = of(player)
        val now = log.counted(tale, template, ending, capital)
        if (now === log) return
        player.setData(ModAttachments.TALE_LOG, now)
        award(player, now)
    }

    /** The milestones [log] has earned: the first tale, three in at least two towns, and all twelve templates. Awarding one twice does nothing. */
    fun award(player: ServerPlayer, log: TaleLog) {
        if (log.first) Milestones.award(player, Milestones.TALE)
        if (log.threeInTwo) Milestones.award(player, Milestones.TALES_THREE)
        if (log.wholeChronicle) Milestones.award(player, Milestones.TALES_ALL)
    }
}
