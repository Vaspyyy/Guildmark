package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerLevel

/**
 * The people of a tale as bodies in the world (living-world/stories/design.md 4.4): who stands in a cast slot just now. The
 * record is the truth and a body is memory, so a body is looked for where it is, never made or moved for a scene that only
 * wants to be seen (4.4 rule 1).
 */
object TaleBodies {
    /**
     * The body of the cast member in [slot] of [t], if one is about (loaded and alive): the resident's own body, wherever
     * their day has them, or a tale body made for them. Null for a slot with no one, a resident the world no longer has, or
     * a body that is not in a loaded chunk. This is how a template finds the people of its scene.
     */
    fun body(level: ServerLevel, t: Tale, slot: String): Townsperson? {
        val id = t.resident(slot)
        if (id < 0) return null
        val r = Residents.get(level)[id] ?: return null
        return Comings.body(level, r)
    }

    /** The loaded bodies of [t]'s cast, in cast order (a slot with no body about is left out). */
    fun bodies(level: ServerLevel, t: Tale): List<Townsperson> = t.cast.mapNotNull { body(level, t, it.slot) }
}
