package io.github.vaspyyy.guildmark.client.cooking

import net.minecraft.world.item.Item
import org.joml.Vector3f

/**
 * Where a first-person cooking clip turns a held tool about (living-world/cook/design.md 11.3, 12.3): the grip point of the
 * ladle, the bowl, the knife and the turner, as `FirstPersonCombat`'s `HOLDS` gives one for a weapon. Part M3's script
 * `cook_anims.py` writes the real file; this is the stand-in Part E reads until it does: no tool has a grip, so the
 * hand is left as the game draws it.
 */
object CookGrips {
    /** The grip point of [item], or null when none is known (stand-in: always null). */
    fun grip(item: Item): Vector3f? = null
}
