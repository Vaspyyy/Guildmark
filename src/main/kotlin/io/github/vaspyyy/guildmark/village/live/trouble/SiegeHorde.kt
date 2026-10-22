package io.github.vaspyyy.guildmark.village.live.trouble

import net.minecraft.world.entity.Mob

/**
 * The siege's mobs, as a village's folk see them (living-world/villages/design.md section 11.1). The name and signature are
 * the contract of design section 19.3; part V10 fills it in. Until then a siege's mobs go after what they went after.
 */
object SiegeHorde {
    /** Arms [mob], one of the siege on the village [key], with the goal of going after its townsfolk. */
    fun arm(mob: Mob, key: Long) {}
}
