package io.github.vaspyyy.guildmark.cooking.home

import io.github.vaspyyy.guildmark.homestead.Homestead
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel

/**
 * The homestead's kitchen (living-world/cook/design.md 6.7): the tier's pot, board, plate, oven and larder fitted into
 * the house the builders raised, and free fire for them while the homestead's people are home. Part K's; until it lands
 * no kitchen is fitted and no fire is free.
 */
object HomesteadKitchens {
    /** Part K's registration: the fit-out's event and scan, and the `cook homestead` and `cook herb` commands (stub: nothing). */
    fun register() {}

    /** Fits the tier's kitchen into [home] once; the number of items fitted (stub: 0). */
    fun raise(level: ServerLevel, home: Homestead): Int = 0

    /** Whether the fire at [pos] is on a homestead's land while its people are home, so it burns no fuel (stub: no). */
    fun freeAt(level: ServerLevel, pos: BlockPos): Boolean = false

    /** The lines for the Menu board by the farmhouse door at [board] (stub: none). */
    fun pantryLines(level: ServerLevel, board: BlockPos): List<Component> = emptyList()
}
