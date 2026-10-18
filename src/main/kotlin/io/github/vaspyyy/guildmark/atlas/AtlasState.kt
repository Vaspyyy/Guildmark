package io.github.vaspyyy.guildmark.atlas

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * One place a player has found. A village is known by its board's anchor cell, a lair by its id, the
 * Hollow Throne by its altar, a Wardfire by its own block, a field boss's camp by its site, an island by its middle at
 * the sea's surface. [size] and [standing] are what the village looked like on the last visit (advances built, the
 * player's standing tier), since a far-off village's board can't be read. An island's [ref] is what it is
 * (IslandMarks.ref) and its [size] how far its land reaches.
 */
data class AtlasPlace(
    val kind: Int,
    val dimension: ResourceKey<Level>,
    val pos: BlockPos,
    val ref: Int = -1,
    val size: Int = 0,
    val standing: Int = 0,
    /** Game time it was found. */
    val found: Long = 0,
) {
    companion object {
        const val VILLAGE = 0
        const val LAIR = 1
        const val THRONE = 2
        const val WARDFIRE = 3
        /** A field boss's camp; [ref] is its site in FieldBosses. */
        const val FIELD_BOSS = 4
        /** An island out at sea; [ref] says what it is (IslandMarks), [size] how far its land reaches. */
        const val ISLAND = 5
        /** An outlaw band's camp; [ref] is its band in OutlawCamps. */
        const val OUTLAW_CAMP = 6
        /** Someone else's homestead, at its Charter Post; [ref] is its id in Homesteads (homestead/HomesteadAtlas). */
        const val HOMESTEAD = 7

        /** A village's [ref] when it's a capital city. */
        const val CAPITAL_REF = 1

        val CODEC: Codec<AtlasPlace> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("kind").forGetter(AtlasPlace::kind),
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(AtlasPlace::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(AtlasPlace::pos),
                Codec.INT.optionalFieldOf("ref", -1).forGetter(AtlasPlace::ref),
                Codec.INT.optionalFieldOf("size", 0).forGetter(AtlasPlace::size),
                Codec.INT.optionalFieldOf("standing", 0).forGetter(AtlasPlace::standing),
                Codec.LONG.optionalFieldOf("found", 0L).forGetter(AtlasPlace::found),
            ).apply(i, ::AtlasPlace)
        }
    }
}

/** Every place a player has found (see Atlas). Kept through death. */
data class AtlasState(val places: List<AtlasPlace> = emptyList()) {
    fun find(kind: Int, dimension: ResourceKey<Level>, pos: BlockPos): AtlasPlace? =
        places.firstOrNull { it.kind == kind && it.dimension == dimension && it.pos == pos }

    fun lair(dimension: ResourceKey<Level>, id: Int): AtlasPlace? =
        places.firstOrNull { it.kind == AtlasPlace.LAIR && it.dimension == dimension && it.ref == id }

    companion object {
        val CODEC: Codec<AtlasState> = AtlasPlace.CODEC.listOf().xmap(::AtlasState, AtlasState::places)
    }
}
