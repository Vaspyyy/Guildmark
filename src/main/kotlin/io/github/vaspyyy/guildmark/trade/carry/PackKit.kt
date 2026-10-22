package io.github.vaspyyy.guildmark.trade.carry

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.economy.Good
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import java.util.Optional
import java.util.UUID
import kotlin.math.min

/**
 * What a pack animal wears (living-world/trader/design.md, Part 3, "The pack animal"): the attachment [Carry.PACK_KIT] of a tame
 * donkey, mule or llama that has had a Pack Harness fitted. Its presence is what makes an animal a pack animal; the animal's
 * own chest stays vanilla's, and so do its owner and its tameness.
 *
 * - [mode] is what it does when its owner moves: it follows ([Mode.FOLLOW]) or stays where it was told ([Mode.STAY]).
 * - [colour] is the blanket's, one of [COLOURS], worked out from the owner when the harness is fitted ([colourOf]).
 * - [bell] is whether the brass bell on its breast strap rings (it is hushed while a Hunt's horn is sounding).
 * - [owner] is the owner's name when the harness was fitted, for the line a stranger is told.
 * - [anchor] is the last place it was told to wait at, or called to (the owner's last whistle point), where a bolted animal
 *   comes back to. Saved, not sent.
 * - [fill] (0 to 3) and [shown] (up to three goods in the panniers, as item ids, the most valuable first) are what the panniers look
 *   like, worked out from the chest every half second; sent to everyone who sees it, not saved.
 *
 * The record is immutable: every change is a new value set on the animal, which is what makes it sync.
 */
data class PackKit(
    val mode: Mode = Mode.FOLLOW,
    val colour: Int = 0,
    val bell: Boolean = true,
    val owner: String = "",
    val anchor: BlockPos? = null,
    val fill: Int = 0,
    val shown: List<Int> = emptyList(),
) {
    /** What it does when its owner moves. The ids are what is saved. */
    enum class Mode(val id: String) {
        FOLLOW("follow"),
        STAY("stay"),
        ;

        companion object {
            fun byId(id: String): Mode = entries.firstOrNull { it.id == id } ?: FOLLOW
        }
    }

    /** [mode] as a number, for the wire. */
    val modeNumber: Int get() = mode.ordinal

    companion object {
        /** How many colours the blankets come in (the booker's six: red, blue, green, amber, violet, teal). */
        const val COLOURS = 6

        /** The most goods shown on top of the panniers. */
        const val SHOWN = 3

        /** What is saved with the animal. */
        val CODEC: MapCodec<PackKit> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.STRING.optionalFieldOf("mode", Mode.FOLLOW.id).forGetter { it.mode.id },
                Codec.INT.optionalFieldOf("colour", 0).forGetter { it.colour },
                Codec.BOOL.optionalFieldOf("bell", true).forGetter { it.bell },
                Codec.STRING.optionalFieldOf("owner", "").forGetter { it.owner },
                BlockPos.CODEC.optionalFieldOf("anchor").forGetter { Optional.ofNullable(it.anchor) },
            ).apply(i) { mode, colour, bell, owner, anchor -> PackKit(Mode.byId(mode), colour, bell, owner, anchor.orElse(null)) }
        }

        /** What is sent to everyone who sees the animal: how it stands, how it looks and how full it is. */
        val STREAM_CODEC: StreamCodec<ByteBuf, PackKit> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PackKit::modeNumber,
            ByteBufCodecs.VAR_INT, PackKit::colour,
            ByteBufCodecs.BOOL, PackKit::bell,
            ByteBufCodecs.VAR_INT, PackKit::fill,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(SHOWN)), PackKit::shown,
        ) { mode, colour, bell, fill, shown ->
            PackKit(Mode.entries.getOrElse(mode) { Mode.FOLLOW }, colour, bell, "", null, fill, shown)
        }

        /** [entity]'s kit, if it wears one (reading never makes one). */
        fun of(entity: Entity): PackKit? = entity.getExistingDataOrNull(Carry.PACK_KIT)

        /** The blanket colour a harness fitted for [owner] has: always the same one for the same player. */
        fun colourOf(owner: UUID): Int = Math.floorMod(owner.hashCode(), COLOURS)

        /**
         * How full a chest looks: 0 for empty, then 1, 2 and 3 as a third, two thirds and more than that of its slots hold something
         * (the panniers bulge in these four steps).
         */
        fun fillOf(chest: Container): Int {
            val size = chest.containerSize
            if (size == 0) return 0
            var used = 0
            for (slot in 0 until size) if (!chest.getItem(slot).isEmpty) used++
            return when {
                used == 0 -> 0
                used * 3 <= size -> 1
                used * 3 <= size * 2 -> 2
                else -> 3
            }
        }

        /**
         * Up to [SHOWN] goods the chest holds, as item ids: the ones whose stacks are worth the most at their base prices come first
         * (a loaf, a candle, an ingot), whatever else is in there. Only the goods the market trades are shown.
         */
        fun shownOf(chest: Container): List<Int> {
            val worth = HashMap<Item, Double>()
            for (slot in 0 until chest.containerSize) {
                val stack = chest.getItem(slot)
                if (stack.isEmpty) continue
                val good = Good.of(stack.item) ?: continue
                worth.merge(stack.item, good.base * stack.count, Double::plus)
            }
            return worth.entries.sortedByDescending { it.value }.take(min(SHOWN, worth.size)).map { Item.getId(it.key) }
        }
    }
}
