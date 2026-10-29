package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenLines
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * What the town says of a pot it sees (living-world/cook/design.md 1.5 rows 14 and 15, 6.5, 8.5, 11.5): a passer-by within
 * [NEAR] blocks who can see the station remarks on a pot catching (one time in three), coming ready, a Fine or Hallmarked dish
 * served from it, and its burning (`kitchen.pot.*`); the innkeeper, within earshot, is cross when the inn's back pot burns
 * (`kitchen.guest.burnt`) and shakes her head at a player she does not know who asks for it (`kitchen.guest.no`).
 *
 * Each is a line of Part J's ([KitchenLines.say]): a take, or its words alone while it has none; said only with a player in
 * hearing, never over someone else talking, by an awake adult, and with Kitchen Talk off not at all. The same remark about the
 * same station waits [COOL] ticks before it can be made again. Nothing is scanned: it looks, only when a station does one of
 * these things, at the townsfolk in a box round it.
 */
object Onlookers {
    /** How far a passer-by can be from the station and still remark on it (blocks, with a clear line to it). */
    private const val NEAR = 8.0

    /** How far the innkeeper hears the back pot from (blocks, through walls: she smells it). */
    private const val EARSHOT = 16.0

    /** Ticks before the same remark about the same station can be made again. */
    private const val COOL = 400L

    /** The game time each remark was last made about each station, by "family@pos". */
    private val made = HashMap<String, Long>()

    init {
        Cooking.onForget { made.clear() }
    }

    /** A call went unanswered and the pot is catching: "Something's catching." one time in three. */
    fun catching(level: ServerLevel, pos: BlockPos) {
        if (level.random.nextInt(3) == 0) passerBy(level, pos, "kitchen.pot.catching")
    }

    /** The pot has come ready: "Smells good!" */
    fun smells(level: ServerLevel, pos: BlockPos) = passerBy(level, pos, "kitchen.pot.smells")

    /** The first portion of a batch of [tier] was served: a Fine or Hallmarked one is remarked on. */
    fun served(level: ServerLevel, pos: BlockPos, tier: Tier) {
        when (tier) {
            Tier.FINE -> passerBy(level, pos, "kitchen.pot.fine")
            Tier.HALLMARKED -> passerBy(level, pos, "kitchen.pot.hallmark")
            else -> {}
        }
    }

    /** The pot has burnt: "Oh dear, that's ruined." */
    fun burnt(level: ServerLevel, pos: BlockPos) = passerBy(level, pos, "kitchen.pot.burnt")

    /** The inn's back pot at [pos] has burnt: the innkeeper, if she is within earshot. */
    fun guestBurnt(level: ServerLevel, pos: BlockPos) = innkeeper(level, pos, "kitchen.guest.burnt")

    /** The back pot at [pos] was refused to a player below Known: the innkeeper, if she is within earshot, says why. */
    fun guestNo(level: ServerLevel, pos: BlockPos) = innkeeper(level, pos, "kitchen.guest.no")

    private fun passerBy(level: ServerLevel, pos: BlockPos, family: String) {
        say(level, pos, family, NEAR, true) { it.role.adult }
    }

    private fun innkeeper(level: ServerLevel, pos: BlockPos, family: String) {
        say(level, pos, family, EARSHOT, false) { it.role == TownRole.INNKEEPER }
    }

    /** The nearest of those within [radius] of the station (seeing it, if [sight]) whom [who] allows says [family], unless it was said of it lately. */
    private fun say(level: ServerLevel, pos: BlockPos, family: String, radius: Double, sight: Boolean, who: (Townsperson) -> Boolean) {
        if (!Cooking.talk()) return
        val now = level.gameTime
        val key = "$family@${pos.asLong()}"
        val last = made[key]
        if (last != null && now - last in 0 until COOL) return
        val at = Vec3.atCenterOf(pos).add(0.0, 0.4, 0.0)
        val speaker = Witness.seen(level, at, null, radius, sight).map { it.first }.filter(who).minByOrNull { it.distanceToSqr(at) } ?: return
        if (!KitchenLines.say(speaker, family, "pot")) return
        if (made.size > 256) made.clear()
        made[key] = now
    }
}
