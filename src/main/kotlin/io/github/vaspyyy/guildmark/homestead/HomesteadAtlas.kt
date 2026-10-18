package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.network.AtlasMark
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * Homesteads on the map and the compass (atlas/Atlas). A player's own homestead and their party's are always there;
 * anyone else's goes in once they've come within sight of its post, named for its owner. The mark carries the tier it
 * stands at ([AtlasMark.size]) and the owner's colour; it is flagged YOURS on the owner's own and RESTED when the
 * player's bed is on its land.
 */
object HomesteadAtlas {
    /** Someone else's homesteads that [player] has just come within [sight] of and hasn't in their atlas yet. */
    fun found(level: ServerLevel, player: ServerPlayer, known: List<AtlasPlace>, sight: Double, now: Long): List<AtlasPlace> {
        if (!Homesteads.enabled() || level.dimension() != Level.OVERWORLD) return listOf()
        val here = player.blockPosition()
        val out = ArrayList<AtlasPlace>()
        for (h in Homesteads.all(level)) {
            if (!h.post.closerThan(here, sight) || Homesteads.belongsTo(level, h, player)) continue
            if (known.any { it.kind == AtlasPlace.HOMESTEAD && it.dimension == level.dimension() && it.ref == h.id }) continue
            out.add(AtlasPlace(AtlasPlace.HOMESTEAD, level.dimension(), h.post, ref = h.id, found = now))
        }
        return out
    }

    /** The mark for a homestead [player] found (none when it's gone, or one of theirs: [own] has those). */
    fun mark(level: ServerLevel, player: ServerPlayer, place: AtlasPlace, restingAt: BlockPos?): AtlasMark? {
        if (!Homesteads.enabled() || level.dimension() != Level.OVERWORLD) return null
        val h = Homesteads.get(level).byId(place.ref) ?: return null
        if (Homesteads.belongsTo(level, h, player)) return null
        return markOf(h, player, restingAt)
    }

    /** The marks of [player]'s own homestead and their party's, found or not. */
    fun own(level: ServerLevel, player: ServerPlayer, restingAt: BlockPos?): List<AtlasMark> {
        if (!Homesteads.enabled() || level.dimension() != Level.OVERWORLD) return listOf()
        return Homesteads.all(level).filter { Homesteads.belongsTo(level, it, player) }.map { markOf(it, player, restingAt) }
    }

    private fun markOf(h: Homestead, player: ServerPlayer, restingAt: BlockPos?): AtlasMark {
        var flags = 0
        if (h.owner == player.uuid) flags = flags or AtlasMark.YOURS
        if (restingAt != null && h.covers(restingAt.x, restingAt.z)) flags = flags or AtlasMark.RESTED
        val color = Dye.entries[h.color.coerceIn(0, Dye.entries.size - 1)].rgb
        return AtlasMark(AtlasPlace.HOMESTEAD, h.post, h.ownerName, h.tier, flags, "", color, if (h.building) h.build.tier else 0, listOf())
    }
}
