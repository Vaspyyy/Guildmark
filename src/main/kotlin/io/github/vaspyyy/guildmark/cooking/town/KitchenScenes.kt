package io.github.vaspyyy.guildmark.cooking.town

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerLevel

/** The four scenes of a town's kitchen (living-world/cook/design.md 7.3, 7.5): the innkeeper at the pot, the baker with the peel, the baker slicing, the rest-day roast. */
enum class KitchenSceneKind { TAVERN, PEEL, SLICE, ROAST }

/**
 * The kitchen scenes (living-world/cook/design.md 7.3, 7.5): the innkeeper at the pot, the baker at the oven and the
 * counter, the rest-day roast. Part I2's; until it lands no keeper leaves their post to cook.
 */
object KitchenScenes {
    /** Part I2's registration: the 20-tick check and the `cook scene` command (stub: nothing). */
    fun register() {}

    /** Takes [p] for the [scene] now, as the commands do; whether it began (stub: no). */
    fun take(level: ServerLevel, p: Townsperson, scene: KitchenSceneKind): Boolean = false
}
