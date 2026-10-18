package io.github.vaspyyy.guildmark.economy.works.build

import net.minecraft.server.level.ServerLevel

/**
 * The works' freight carried from the wagon stand to the works' yard (design section 2, "Materials on the wagons"):
 * what's still being carried, by works and material, so the yard's heaps fill as it comes in.
 */
internal object Unloads {
    /** Units of material [i] (Stuff.PAID's order) still on their way from the wagon to works [works]'s yard. */
    fun carrying(works: Int, i: Int): Int = 0

    fun tick(level: ServerLevel) {}

    fun reset() {}
}
