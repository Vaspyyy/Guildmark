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
    /** A rival guild (id) that has challenged this hold, or -1. */
    var challenger: Int = -1,
    /** The day the challenge must be answered by, or the rival takes the village. */
    var challengeDeadline: Long = -1,
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
                Codec.INT.optionalFieldOf("challenger", -1).forGetter(Holding::challenger),
                Codec.LONG.optionalFieldOf("challenge_deadline", -1L).forGetter(Holding::challengeDeadline),
            ).apply(i) { anchor, guild, since, tribute, visit, loyalty, banners, challenger, deadline ->
                Holding(anchor, guild, since, tribute, visit, loyalty, banners.toMutableList(), challenger, deadline)
            }
        }
    }
}

/** Every held village in the overworld. */
class Holdings(
    val holdings: MutableList<Holding> = mutableListOf(),
    var lastDay: Long = -1,
    /** Every village board anchor anyone has been near, so rivals know where villages are. */
    val known: MutableSet<BlockPos> = mutableSetOf(),
) : SavedData() {
    fun remember(anchor: BlockPos) {
        if (known.add(anchor)) setDirty()
    }

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
                BlockPos.CODEC.listOf().optionalFieldOf("known", listOf()).forGetter { it.known.toList() },
            ).apply(i) { holdings, day, known -> Holdings(holdings.toMutableList(), day, known.toMutableSet()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "holdings"), Supplier { Holdings() }, CODEC)

        fun get(level: ServerLevel): Holdings = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
