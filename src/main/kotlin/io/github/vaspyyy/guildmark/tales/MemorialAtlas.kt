package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.network.AtlasMark
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The memorials on a player's atlas (living-world/stories/design.md 6.4, 10.5.2): a stone or a cairn the town keeps goes on the atlas once
 * the player has come within [SIGHT] blocks of it, is struck off once the town no longer keeps it and the player is there to see so, and
 * is sent to the client named for the people it remembers. Called from `Atlas`, which owns the loop and the player's saved list.
 */
internal object MemorialAtlas {
    /** How near a player comes to a standing memorial before it is theirs to find. */
    const val SIGHT = 24.0

    /** The memorials standing within [SIGHT] blocks of [here] that [places] (the player's atlas) do not have yet, as places found at game time [now]. */
    fun found(level: ServerLevel, here: BlockPos, places: List<AtlasPlace>, now: Long): List<AtlasPlace> {
        val dimension = level.dimension()
        val key = Memorials.dimension(level)
        val out = ArrayList<AtlasPlace>()
        for (m in TaleData.get(level).memorials) {
            val pos = m.pos ?: continue
            if (!m.placed || m.dimension != key || !pos.closerThan(here, SIGHT)) continue
            if (places.any { it.kind == AtlasPlace.MEMORIAL && it.dimension == dimension && it.ref == m.id }) continue
            out += AtlasPlace(AtlasPlace.MEMORIAL, dimension, pos, ref = m.id, size = if (m.kind == MemorialKind.CAIRN.id) 1 else 0, found = now)
        }
        return out
    }

    /** Whether [place] is a memorial the town no longer keeps, and [here] is near enough, with its ground loaded, to see that it is gone. */
    fun gone(level: ServerLevel, place: AtlasPlace, here: BlockPos): Boolean {
        if (place.kind != AtlasPlace.MEMORIAL || place.dimension != level.dimension() || !place.pos.closerThan(here, SIGHT) || !level.isLoaded(place.pos)) return false
        return TaleData.get(level).memorials.none { it.id == place.ref && it.placed }
    }

    /**
     * The client's mark for the memorial of [place]: the names it carries as its label (up to three, plain), a cairn's size 1 and a stone's 0.
     * Null if the town keeps no such memorial now (it is not sent).
     */
    fun mark(level: ServerLevel, place: AtlasPlace): AtlasMark? {
        val rec = TaleData.get(level).memorials.firstOrNull { it.id == place.ref && it.placed } ?: return null
        return AtlasMark(AtlasPlace.MEMORIAL, rec.pos ?: place.pos, MemorialWords.label(rec.names), place.size, 0, "", 0, 0, listOf())
    }
}
