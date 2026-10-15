package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.Capitals
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.abs

/**
 * A place with townsfolk: a square of [radius] blocks round [center], with a [square] where people gather
 * (a capital's bell, or the middle of a town zone).
 */
class Town(val center: BlockPos, val radius: Int, val square: BlockPos) {
    fun contains(x: Int, z: Int): Boolean = abs(x - center.x) <= radius && abs(z - center.z) <= radius
    fun contains(pos: BlockPos): Boolean = contains(pos.x, pos.z)
}

/**
 * Where the towns are: Guildmark's capitals, and any zone registered through [Townsfolk.registerTown] (like the
 * test square `/guildmark townsfolk demo` builds). Crowds only gather in towns.
 */
object Towns {
    private class Seen(val at: Long, val town: Town?)

    private val byPlayer = HashMap<UUID, Seen>()

    /** The town at [pos], if any. Looking up a capital costs a little, so ask [around] for players. */
    fun at(level: ServerLevel, pos: BlockPos): Town? {
        TownZones.get(level).zones.firstOrNull { it.contains(pos) }?.let { return it.town() }
        if (level.dimension() != Level.OVERWORLD || !Capitals.enabled()) return null
        val capital = Capitals.covering(level, pos)?.takeIf { it.within(pos.x, pos.z) } ?: return null
        return Town(capital.center, capital.plan.outer, capital.bell)
    }

    /** The town round [player]'s position, looked up at most every few seconds. */
    fun around(level: ServerLevel, playerId: UUID, pos: BlockPos): Town? {
        val seen = byPlayer[playerId]
        if (seen != null && level.gameTime - seen.at < 100 && (seen.town == null || seen.town.contains(pos))) return seen.town
        val town = at(level, pos)
        if (byPlayer.size > 256) byPlayer.clear()
        byPlayer[playerId] = Seen(level.gameTime, town)
        return town
    }
}

/** Town zones registered by hand or by other features, kept with each level. */
class TownZones(val zones: MutableList<Zone> = mutableListOf()) : SavedData() {
    data class Zone(val center: BlockPos, val radius: Int) {
        fun contains(pos: BlockPos): Boolean = abs(pos.x - center.x) <= radius && abs(pos.z - center.z) <= radius
        fun town(): Town = Town(center, radius, center)

        companion object {
            val CODEC: Codec<Zone> = RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("center").forGetter(Zone::center),
                    Codec.INT.fieldOf("radius").forGetter(Zone::radius),
                ).apply(i, ::Zone)
            }
        }
    }

    fun add(center: BlockPos, radius: Int) {
        zones.removeIf { it.center == center }
        zones.add(Zone(center, radius))
        setDirty()
    }

    fun remove(center: BlockPos): Boolean {
        val removed = zones.removeIf { it.contains(center) }
        if (removed) setDirty()
        return removed
    }

    companion object {
        private val CODEC: Codec<TownZones> = RecordCodecBuilder.create { i ->
            i.group(
                Zone.CODEC.listOf().optionalFieldOf("zones", listOf()).forGetter { it.zones },
            ).apply(i) { zones -> TownZones(zones.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "town_zones"), Supplier { TownZones() }, CODEC)

        fun get(level: ServerLevel): TownZones = level.dataStorage.computeIfAbsent(TYPE)
    }
}
