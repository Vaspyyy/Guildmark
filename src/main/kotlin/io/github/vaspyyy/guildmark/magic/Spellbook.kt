package io.github.vaspyyy.guildmark.magic

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Levelling
import io.github.vaspyyy.guildmark.progression.Talents
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player

/** The spells a player has learned, and the ones attuned at a Wardfire, which are the ones they can cast. By id, in order. */
data class SpellbookState(
    val known: List<String> = Spell.STARTERS.map { it.id },
    val attuned: List<String> = Spell.STARTERS.map { it.id },
) {
    fun knows(spell: Spell): Boolean = spell.id in known
    fun attuned(spell: Spell): Boolean = spell.id in attuned
    fun attunedSpells(): List<Spell> = attuned.mapNotNull(Spell::byId)
    fun knownSpells(): List<Spell> = known.mapNotNull(Spell::byId)

    companion object {
        val CODEC: Codec<SpellbookState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.listOf().optionalFieldOf("known", Spell.STARTERS.map { it.id }).forGetter(SpellbookState::known),
                Codec.STRING.listOf().optionalFieldOf("attuned", Spell.STARTERS.map { it.id }).forGetter(SpellbookState::attuned),
            ).apply(i, ::SpellbookState)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, SpellbookState> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(Spell.entries.size)), SpellbookState::known,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(Spellbook.MAX_SLOTS)), SpellbookState::attuned,
            ::SpellbookState,
        )
    }
}

/**
 * Learning spells (from scrolls) and attuning them. A player can carry as many attuned spells as they have
 * slots: three, and more from the tree. Attuning happens at a Wardfire; a spell learned with a slot free is
 * attuned at once, so a new scroll can be tried straight away.
 */
object Spellbook {
    const val BASE_SLOTS = 3
    const val MAX_SLOTS = 8

    fun get(player: Player): SpellbookState = player.getData(MagicRegistry.SPELLBOOK)

    fun slots(player: Player): Int = (BASE_SLOTS + Talents[player, Bonus.SPELL_SLOTS].toInt()).coerceIn(1, MAX_SLOTS)

    /** Teaches [player] [spell]. False when they knew it already. */
    fun learn(player: ServerPlayer, spell: Spell): Boolean {
        val book = get(player)
        if (book.knows(spell)) return false
        val attuned = if (book.attuned.size < slots(player)) book.attuned + spell.id else book.attuned
        player.setData(MagicRegistry.SPELLBOOK, book.copy(known = order(book.known + spell.id), attuned = attuned))
        return true
    }

    /** Forgets [spell] (a command, for testing). */
    fun forget(player: ServerPlayer, spell: Spell) {
        val book = get(player)
        player.setData(MagicRegistry.SPELLBOOK, SpellbookState(book.known - spell.id, book.attuned - spell.id))
    }

    /** The next spell of [school] in the order scrolls teach them that [player] doesn't know, or null. */
    fun nextUnknown(player: Player, school: School): Spell? {
        val book = get(player)
        return Spell.entries.firstOrNull { it.school == school && !book.knows(it) }
    }

    /** Attunes [ids] (known spells, as many as fit), at the Wardfire at [at]. */
    fun attune(player: ServerPlayer, at: BlockPos?, ids: List<String>) {
        if (!Levelling.atRest(player, at)) return
        val book = get(player)
        val wanted = ids.distinct().filter { id -> Spell.byId(id)?.let(book::knows) == true }.take(slots(player))
        if (wanted == book.attuned) return
        player.setData(MagicRegistry.SPELLBOOK, book.copy(attuned = wanted))
        player.level().playSound(null, player.x, player.y, player.z, MagicRegistry.ATTUNE.get(), SoundSource.PLAYERS, 0.8f, 1f)
    }

    /** Fewer slots than spells (a node forgotten): the last attuned spells fall out. */
    fun fit(player: ServerPlayer) {
        val book = get(player)
        val slots = slots(player)
        if (book.attuned.size > slots) player.setData(MagicRegistry.SPELLBOOK, book.copy(attuned = book.attuned.take(slots)))
    }

    /** Known spells in the order they're listed. */
    private fun order(ids: List<String>): List<String> =
        ids.distinct().sortedBy { Spell.byId(it)?.ordinal ?: Int.MAX_VALUE }
}
