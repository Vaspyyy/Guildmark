package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/** What a pot's contents are taken for (2.4): the [kind] (null is Gruel), how many [portions], the mix's [share] and its [distance] from the ideal. */
class Recognised(val kind: DishKind?, val portions: Int, val share: DoubleArray, val distance: Double)

/** Naming what is in a pot (living-world/cook/design.md 2.4). Part A's; until it lands, everything is Gruel. */
object Recognise {
    /** What [items] in a pot on [station] make, for a cook of COOKING level [cookLevel] (stub: Gruel, no portions). */
    fun run(level: ServerLevel, station: Station, items: List<ItemStack>, cookLevel: Int): Recognised =
        Recognised(null, 0, DoubleArray(Role.entries.size), 0.0)
}
