package io.github.vaspyyy.guildmark.village.live.trouble

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob

/**
 * The siege's mobs, as a village's folk see them (living-world/villages/design.md section 11.1). The name and signature are
 * the contract of design section 19.3; part V10 fills it in. Until then a siege's mobs go after what they went after.
 */
object SiegeHorde {
    /** A siege on a village of the registry, while it lasts: the side the horde came from. */
    private class On(val key: Long, val bell: BlockPos, val from: Direction)

    /** The sieges on villages under way, by the village's key; memory only, as the sieges are. */
    private val on = HashMap<Long, On>()

    /** Arms [mob], one of the siege on the village [key], with the goal of going after its townsfolk. */
    fun arm(mob: Mob, key: Long) {}

    /** The side the horde of a siege on the village round [pos] came from ("east"), if one is under way there. */
    fun sideOf(level: ServerLevel, pos: BlockPos): String? {
        val rec = Trouble.village(level, pos) ?: return null
        return on[rec.key]?.from?.serializedName
    }
}
