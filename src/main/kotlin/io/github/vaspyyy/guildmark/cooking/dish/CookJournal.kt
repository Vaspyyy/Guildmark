package io.github.vaspyyy.guildmark.cooking.dish

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.network.codec.ByteBufCodecs

/**
 * What a cook has learned (living-world/cook/design.md 3.4), the data of the attachment `guildmark:cook_journal`: it
 * lives on the player and is synced to them, so losing the Cook's Book loses nothing. One [Page] a kind, kept under
 * the kind's id. Part D's: Part 0 gives it the shape 3.4 describes so that the attachment can be registered, and D may
 * add fields (each optional with a default, so a world saved before reads back whole). Nothing writes to it yet.
 */
class CookJournal {
    /**
     * One kind's page: how many [made] batches, the [best] tier reached (a [io.github.vaspyyy.guildmark.cooking.Tier]
     * ordinal, -1 for none), the [mix] of the best batch (item counts, at most 12), and for each [Role] in order what the
     * cook knows of this world's ideal: [direction] (0 not known, 1 too much in the standard bill, -1 too little) and
     * [amount] (how many tenths of an item, known only from COOKING 25).
     */
    class Page(
        var made: Int = 0,
        var best: Int = -1,
        var mix: IntArray = IntArray(0),
        var direction: IntArray = IntArray(Role.entries.size),
        var amount: IntArray = IntArray(Role.entries.size),
    )

    /** The pages by kind id. */
    val pages: MutableMap<String, Page> = LinkedHashMap()

    /** [kind]'s page, begun if there is none. */
    fun page(kind: DishKind): Page = pages.getOrPut(kind.id) { Page() }

    /** Whether nothing has been learned (an empty journal is not saved at all). */
    fun isEmpty(): Boolean = pages.isEmpty()

    companion object {
        private val INTS: Codec<IntArray> = Codec.INT.listOf().xmap({ it.toIntArray() }, { it.toList() })

        private val PAGE: Codec<Page> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("made", 0).forGetter { it.made },
                Codec.INT.optionalFieldOf("best", -1).forGetter { it.best },
                INTS.optionalFieldOf("mix", IntArray(0)).forGetter { it.mix },
                INTS.optionalFieldOf("direction", IntArray(Role.entries.size)).forGetter { it.direction },
                INTS.optionalFieldOf("amount", IntArray(Role.entries.size)).forGetter { it.amount },
            ).apply(i) { made, best, mix, direction, amount -> Page(made, best, mix, direction.fit(), amount.fit()) }
        }

        /** A role array of the right length, however it was saved. */
        private fun IntArray.fit(): IntArray = if (size == Role.entries.size) this else copyOf(Role.entries.size)

        val CODEC: Codec<CookJournal> = Codec.unboundedMap(Codec.STRING, PAGE).xmap(
            { saved -> CookJournal().also { it.pages.putAll(saved) } },
            { it.pages },
        )

        /** The same on the wire: the Book's screen reads it on the client. */
        val STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC)
    }
}
