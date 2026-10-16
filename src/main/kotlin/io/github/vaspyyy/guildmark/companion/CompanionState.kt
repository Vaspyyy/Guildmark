package io.github.vaspyyy.guildmark.companion

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.core.GlobalPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import java.util.Optional

/**
 * A player's companions. Immutable: replace it with Companions.set so it syncs (the client gets what the HUD, the
 * character sheet and the spell costs need: who travels with them, how they feel, who is sworn). Days are game days
 * (gameTime / 24000), so sleeping through a night doesn't skip one.
 */
data class CompanionState(
    /** Who has come to the player's fire at least once. */
    val met: Set<String> = setOf(),
    /** Who has travelled with them. */
    val recruited: Set<String> = setOf(),
    /** Who travels with them now (following, or waiting where they were told). */
    val active: String? = null,
    /** Whether [active] follows, or waits at their spot. */
    val following: Boolean = true,
    val loyalty: Map<String, Int> = mapOf(),
    /** Where each one who isn't following waits: told to wait, parted with, or not yet asked along. */
    val spots: Map<String, GlobalPos> = mapOf(),
    val sworn: Set<String> = setOf(),
    /** The day each left in anger; they'll hear the player out again from the next day. */
    val leftOn: Map<String, Long> = mapOf(),
    /** The day someone last came to the player's fire, one a day at most. */
    val metOn: Long = -1,
    /** The day each was last given a gift. */
    val giftedOn: Map<String, Long> = mapOf(),
    /** The day each last told a story at the fire, and how many they've told. */
    val toldOn: Map<String, Long> = mapOf(),
    val told: Map<String, Int> = mapOf(),
    /** How often each deed has moved each one's loyalty today: "<day>:<id>:<deed>". */
    val deeds: Map<String, Int> = mapOf(),
    /** The day Ysolde's Last Light was spent. */
    val lightOn: Long = -1,
    /** Whose open chapter they've already asked about at a fire (the chapter's number). */
    val asked: Map<String, Int> = mapOf(),
    /**
     * The day each parted from the player (or was told to wait), or last came by one of their fires: a few days on, if
     * the player has gone far, they come by a fire of theirs (see Companions.visit).
     */
    val partedOn: Map<String, Long> = mapOf(),
) {
    fun loyalty(kind: CompanionKind): Int = loyalty[kind.id] ?: CompanionKind.FIRST
    fun isSworn(kind: CompanionKind): Boolean = kind.id in sworn
    fun isActive(kind: CompanionKind): Boolean = active == kind.id
    fun hasMet(kind: CompanionKind): Boolean = kind.id in met
    fun hasJoined(kind: CompanionKind): Boolean = kind.id in recruited
    val activeKind: CompanionKind? get() = active?.let(CompanionKind::byId)

    fun withLoyalty(kind: CompanionKind, value: Int): CompanionState =
        copy(loyalty = loyalty + (kind.id to value.coerceIn(CompanionKind.LEAST, CompanionKind.MOST)))

    fun withSpot(kind: CompanionKind, spot: GlobalPos?): CompanionState =
        copy(spots = if (spot == null) spots - kind.id else spots + (kind.id to spot))

    /** How often [deed] has moved [kind]'s loyalty on [day]. */
    fun deedsToday(day: Long, kind: CompanionKind, deed: Deed): Int = deeds["$day:${kind.id}:${deed.name}"] ?: 0

    /** One more of [deed] for [kind] on [day]; older days are forgotten. */
    fun countDeed(day: Long, kind: CompanionKind, deed: Deed): CompanionState {
        val key = "$day:${kind.id}:${deed.name}"
        val today = deeds.filterKeys { it.startsWith("$day:") }
        return copy(deeds = today + (key to (today[key] ?: 0) + 1))
    }

    companion object {
        private val STRINGS: Codec<Set<String>> = Codec.STRING.listOf().xmap({ it.toSet() }, { it.toList() })
        private val INTS: Codec<Map<String, Int>> = Codec.unboundedMap(Codec.STRING, Codec.INT)
        private val DAYS: Codec<Map<String, Long>> = Codec.unboundedMap(Codec.STRING, Codec.LONG)

        val CODEC: Codec<CompanionState> = RecordCodecBuilder.create { i ->
            i.group(
                STRINGS.optionalFieldOf("met", setOf()).forGetter(CompanionState::met),
                STRINGS.optionalFieldOf("recruited", setOf()).forGetter(CompanionState::recruited),
                Codec.STRING.optionalFieldOf("active").forGetter { Optional.ofNullable(it.active) },
                Codec.BOOL.optionalFieldOf("following", true).forGetter(CompanionState::following),
                INTS.optionalFieldOf("loyalty", mapOf()).forGetter(CompanionState::loyalty),
                Codec.unboundedMap(Codec.STRING, GlobalPos.CODEC).optionalFieldOf("spots", mapOf()).forGetter(CompanionState::spots),
                STRINGS.optionalFieldOf("sworn", setOf()).forGetter(CompanionState::sworn),
                DAYS.optionalFieldOf("left_on", mapOf()).forGetter(CompanionState::leftOn),
                Codec.LONG.optionalFieldOf("met_on", -1L).forGetter(CompanionState::metOn),
                DAYS.optionalFieldOf("gifted_on", mapOf()).forGetter(CompanionState::giftedOn),
                DAYS.optionalFieldOf("told_on", mapOf()).forGetter(CompanionState::toldOn),
                INTS.optionalFieldOf("told", mapOf()).forGetter(CompanionState::told),
                INTS.optionalFieldOf("deeds", mapOf()).forGetter(CompanionState::deeds),
                Codec.LONG.optionalFieldOf("light_on", -1L).forGetter(CompanionState::lightOn),
                INTS.optionalFieldOf("asked", mapOf()).forGetter(CompanionState::asked),
                DAYS.optionalFieldOf("parted_on", mapOf()).forGetter(CompanionState::partedOn),
            ).apply(i) { met, recruited, active, following, loyalty, spots, sworn, leftOn, metOn, giftedOn, toldOn, told, deeds, lightOn, asked, partedOn ->
                CompanionState(met, recruited, active.orElse(null), following, loyalty, spots, sworn, leftOn, metOn, giftedOn, toldOn, told, deeds, lightOn, asked, partedOn)
            }
        }

        /** What the client is told: who travels with them, who has, how each feels and who is sworn. */
        val STREAM_CODEC: StreamCodec<ByteBuf, CompanionState> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), { it.recruited.toList() },
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), { Optional.ofNullable(it.active) },
            ByteBufCodecs.BOOL, CompanionState::following,
            ByteBufCodecs.map({ HashMap<String, Int>() }, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT, 8), { HashMap(it.loyalty) },
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), { it.sworn.toList() },
        ) { recruited, active, following, loyalty, sworn ->
            CompanionState(recruited = recruited.toSet(), active = active.orElse(null), following = following, loyalty = loyalty, sworn = sworn.toSet())
        }
    }
}
