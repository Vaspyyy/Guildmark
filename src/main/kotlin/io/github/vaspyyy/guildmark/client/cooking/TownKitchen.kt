package io.github.vaspyyy.guildmark.client.cooking

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos

/**
 * Whether a townsperson is at a station (living-world/cook/design.md 7.5, 12.3), for the renderers of the oven and the spit to
 * ask: the oven's door swings open for one working at it, the spit's crank turns for one turning it. Part I3's; until it
 * lands the answer is no, so the door stays shut and the crank still.
 */
object TownKitchen {
    /** Whether a townsperson stands at the oven at [pos], so its door is open (stub: no). */
    fun doorOpen(level: ClientLevel, pos: BlockPos): Boolean = false

    /** Whether a townsperson is turning the spit at [pos], so its crank turns (stub: no). */
    fun cranking(level: ClientLevel, pos: BlockPos): Boolean = false
}
