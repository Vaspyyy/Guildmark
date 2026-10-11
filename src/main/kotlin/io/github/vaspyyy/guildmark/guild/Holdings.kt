package io.github.vaspyyy.guildmark.guild

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/**
 * A village held by a guild. [guild] is a player guild's id, or a rival guild's (see RivalGuild).
 * Loyalty rises with tribute paid and sieges won, and falls with neglect and lost sieges; at zero
 * the village throws the banner down.
 */
data class Holding(
    val anchor: BlockPos,
    var guild: Int,
    var since: Long,
    var lastTribute: Long,
    var lastVisit: Long,
    var loyalty: Int,
    val banners: MutableList<BlockPos> = mutableListOf(),
) {
    companion object {
        const val MAX_LOYALTY = 100

        val CODEC: Codec<Holding> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("anchor").forGetter(Holding::anchor),
                Codec.INT.fieldOf("guild").forGetter(Holding::guild),
                Codec.LONG.fieldOf("since").forGetter(Holding::since),
                Codec.LONG.fieldOf("last_tribute").forGetter(Holding::lastTribute),
                Codec.LONG.fieldOf("last_visit").forGetter(Holding::lastVisit),
                Codec.INT.fieldOf("loyalty").forGetter(Holding::loyalty),
                BlockPos.CODEC.listOf().optionalFieldOf("banners", listOf()).forGetter { it.banners.toList() },
            ).apply(i) { anchor, guild, since, tribute, visit, loyalty, banners -> Holding(anchor, guild, since, tribute, visit, loyalty, banners.toMutableList()) }
        }
    }
}

/** Every held village in the overworld. */
class Holdings(val holdings: MutableList<Holding> = mutableListOf(), var lastDay: Long = -1) : SavedData() {
    fun at(anchor: BlockPos): Holding? = holdings.firstOrNull { it.anchor == anchor }
    fun of(guild: Int): List<Holding> = holdings.filter { it.guild == guild }

    fun put(holding: Holding) {
        holdings.removeIf { it.anchor == holding.anchor }
        holdings.add(holding)
        setDirty()
    }

    fun remove(anchor: BlockPos) {
        if (holdings.removeIf { it.anchor == anchor }) setDirty()
    }

    fun changed() = setDirty()

    companion object {
        private val CODEC: Codec<Holdings> = RecordCodecBuilder.create { i ->
            i.group(
                Holding.CODEC.listOf().fieldOf("holdings").forGetter { it.holdings },
                Codec.LONG.optionalFieldOf("last_day", -1L).forGetter { it.lastDay },
            ).apply(i) { holdings, day -> Holdings(holdings.toMutableList(), day) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "holdings"), Supplier { Holdings() }, CODEC)

        fun get(level: ServerLevel): Holdings = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
