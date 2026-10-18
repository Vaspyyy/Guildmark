package io.github.vaspyyy.guildmark.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The anchor cell of a market board (see [MarketBoardBlock]): every good's price as the crier last chalked it up,
 * and the news they pinned beside it. Synced to clients whole, so the board can be drawn as it stands: a line
 * written since a viewer last looked comes up stroke by stroke (see [Line.chalked]), a slip pinned flutters up.
 */
class MarketBoardBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.MARKET_BOARD.get(), pos, state) {
    /**
     * One good's line: the lot a player can buy ([count] for [marks] Guild Marks; a [count] of 0 means none to be
     * had), which way its price is going against yesterday's close ([trend]: 1 up, -1 down, 0 steady), how it
     * stands against its usual ([off]: 1 dear, -1 cheap, 0 about usual), and the game time it was last written
     * ([chalked]; [LONG_AGO] for a board written up while nobody was by, which shows whole at once).
     */
    class Line(val good: Good, val count: Int, val marks: Int, val trend: Int, val off: Int, val chalked: Long) {
        /** Whether it says the same as [other], so writing it again would change nothing on the board. */
        fun same(other: Line?): Boolean =
            other != null && other.good == good && other.count == count && other.marks == marks && other.trend == trend && other.off == off

        companion object {
            val CODEC: Codec<Line> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("good").forGetter { it.good.id },
                    Codec.INT.fieldOf("count").forGetter(Line::count),
                    Codec.INT.fieldOf("marks").forGetter(Line::marks),
                    Codec.INT.optionalFieldOf("trend", 0).forGetter(Line::trend),
                    Codec.INT.optionalFieldOf("off", 0).forGetter(Line::off),
                    Codec.LONG.optionalFieldOf("chalked", LONG_AGO).forGetter(Line::chalked),
                ).apply(i) { good, count, marks, trend, off, chalked -> Line(Good.byId(good) ?: Good.BREAD, count, marks, trend, off, chalked) }
            }
        }
    }

    /**
     * A slip of news pinned to the board: [headline] in a big hand, [text] in full (what the crier cried), [kind]
     * for its paper (see [Slip.Kind]), and the game time it went up ([pinned]).
     */
    class Slip(val kind: String, val headline: Component, val text: Component, val pinned: Long) {
        /** The papers a slip can be on. */
        enum class Kind(val id: String) {
            /** Trouble at the market: blight, a broken mill, a fever. */
            TROUBLE("trouble"),

            /** Luck, or trouble over: a bumper crop, the boats in full, the mill turning again. */
            GOOD("good"),

            /** A price that's moved a long way, and why. */
            PRICE("price"),

            /** Word from the land: deeds, sieges, outlaws (the guild's news). */
            WORD("word"),

            /** The year: a festival coming, the season turning. */
            YEAR("year"),
        }

        companion object {
            val CODEC: Codec<Slip> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("kind").forGetter(Slip::kind),
                    ComponentSerialization.CODEC.fieldOf("headline").forGetter(Slip::headline),
                    ComponentSerialization.CODEC.fieldOf("text").forGetter(Slip::text),
                    Codec.LONG.optionalFieldOf("pinned", LONG_AGO).forGetter(Slip::pinned),
                ).apply(i, ::Slip)
            }
        }
    }

    /** The capital's name, for the board's head. */
    var town: Component = Component.empty()
        private set

    /** The town's day the prices are from (see [io.github.vaspyyy.guildmark.economy.Conditions.day]); -1 if never written. */
    var day: Long = -1
        private set

    /** Written up at noon, after the crier's round (otherwise first thing in the morning). */
    var noon: Boolean = false
        private set

    /** The game time the crier began writing it up, or [LONG_AGO] if it was found written up (nothing to see written). */
    var begun: Long = LONG_AGO
        private set

    /** Every traded good's line, in the board's order (see [io.github.vaspyyy.guildmark.economy.MarketBoards.ORDER]). */
    val lines: MutableList<Line> = ArrayList()

    /** The news pinned up, oldest first, at most [SLIPS]. */
    val slips: MutableList<Slip> = ArrayList()

    /** The line the crier's chalk is on right now, or -1; drawn as the hand at work. */
    var writing: Int = -1
        private set

    /** Whether the last write-up was seen through: false while the crier is at it, and if they were called away from it. */
    var finished: Boolean = true
        private set

    /** Server side: starts writing the board up for [day] ([noon] or the morning) for [town]. Nothing changes on it yet. */
    fun begin(town: Component, day: Long, noon: Boolean) {
        this.town = town
        this.day = day
        this.noon = noon
        begun = level?.gameTime ?: LONG_AGO
        finished = false
        sync()
    }

    /** Server side: writes [line] at [index] (the board's order), the chalk moving on to it. */
    fun chalk(index: Int, line: Line) {
        while (lines.size <= index) lines.add(Line(line.good, 0, 0, 0, 0, LONG_AGO))
        lines[index] = line
        writing = index
        sync()
    }

    /** Server side: the chalk is put down. */
    fun done() {
        if (writing == -1) return
        writing = -1
        sync()
    }

    /** Server side: the write-up is seen through, every line that wanted it rewritten; the chalk is put down. */
    fun finish() {
        finished = true
        writing = -1
        sync()
    }

    /** Server side: writes the whole board at once, as it would be found by someone coming by afterwards. */
    fun write(town: Component, day: Long, noon: Boolean, all: List<Line>) {
        this.town = town
        this.day = day
        this.noon = noon
        begun = LONG_AGO
        lines.clear()
        lines.addAll(all)
        writing = -1
        finished = true
        sync()
    }

    /** Server side: pins [slip] up, taking the oldest down if the board is full. */
    fun pin(slip: Slip) {
        slips.add(slip)
        while (slips.size > SLIPS) slips.removeAt(0)
        sync()
    }

    /** Server side: takes every slip down (yesterday's news, before today's goes up). */
    fun unpin() {
        if (slips.isEmpty()) return
        slips.clear()
        sync()
    }

    private fun sync() {
        setChanged()
        val level = level ?: return
        if (level.isClientSide()) return
        val state = blockState
        level.sendBlockUpdated(blockPos, state, state, Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.store("town", ComponentSerialization.CODEC, town)
        output.putLong("day", day)
        output.putBoolean("noon", noon)
        output.putLong("begun", begun)
        output.store("lines", Line.CODEC.listOf(), lines)
        output.store("slips", Slip.CODEC.listOf(), slips)
        output.putInt("writing", writing)
        output.putBoolean("finished", finished)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        town = input.read("town", ComponentSerialization.CODEC).orElse(Component.empty())
        day = input.getLongOr("day", -1L)
        noon = input.getBooleanOr("noon", false)
        begun = input.getLongOr("begun", LONG_AGO)
        lines.clear()
        input.read("lines", Line.CODEC.listOf()).ifPresent { lines.addAll(it) }
        slips.clear()
        input.read("slips", Slip.CODEC.listOf()).ifPresent { slips.addAll(it) }
        writing = input.getIntOr("writing", -1)
        finished = input.getBooleanOr("finished", true)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** The most slips a board holds. */
        const val SLIPS = 4

        /** A [Line.chalked] or [Slip.pinned] time long past: shown whole, with nothing still being written. */
        const val LONG_AGO = Long.MIN_VALUE / 4
    }
}
