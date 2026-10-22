package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.server.level.ServerLevel

/**
 * The best mix of each kind in this world (living-world/cook/design.md 2.3): worked out from the world seed, the kind's
 * id and [VERSION], never saved. Part A's, and the formula it writes here never changes, because changing it would move
 * every cook's target in a world already played; until it lands, every ideal is empty.
 */
object Ideal {
    /** Frozen at 1: it is part of the seed of every ideal. */
    const val VERSION = 1

    /** The share of each role, in [Role] order and summing to 1, that [kind] is best made with in [level]'s world (stub: all zero). */
    fun of(level: ServerLevel, kind: DishKind): DoubleArray = DoubleArray(Role.entries.size)
}
