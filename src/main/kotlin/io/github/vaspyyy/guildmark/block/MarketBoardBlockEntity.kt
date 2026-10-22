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
 * the news they pinned beside it, and the capital's orders hung on tickets along the rail under them. Synced to
 * clients whole, so the board can be drawn as it stands: a line written since a viewer last looked comes up stroke
 * by stroke (see [Line.chalked]), a slip pinned flutters up.
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
     * for its paper (see [Slip.Kind]), and the game time it went up ([pinned]). A WORKS slip names its works
     * ([works], a WorksView id, for the works screen to open from it; -1 on every other slip).
     */
    class Slip(val kind: String, val headline: Component, val text: Component, val pinned: Long, val works: Int = -1) {
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

            /** The talk of the town, cried at noon (townsfolk/talk/TownCry.kt). */
            TALK("talk"),

            /** The town's times moving, and why (economy/fortune/Fortunes.kt). */
            FORTUNE("fortune"),

            /** A works wanted, funded or raised (economy/works/Works.kt). */
            WORKS("works"),

            /** A tale's ending, told: its name and what became of it (Tales). Drawn on the word paper until its own paper is drawn. */
            TALE("tale"),
        }

        companion object {
            val CODEC: Codec<Slip> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("kind").forGetter(Slip::kind),
                    ComponentSerialization.CODEC.fieldOf("headline").forGetter(Slip::headline),
                    ComponentSerialization.CODEC.fieldOf("text").forGetter(Slip::text),
                    Codec.LONG.optionalFieldOf("pinned", LONG_AGO).forGetter(Slip::pinned),
                    Codec.INT.optionalFieldOf("works", -1).forGetter(Slip::works),
                ).apply(i, ::Slip)
            }
        }
    }

    /**
     * One of the capital's orders on a ticket hung from the rail (see [io.github.vaspyyy.guildmark.economy.Orders]):
     * the [order]'s number, [count] of [good] wanted at [shop], whether someone has [taken] it (stamped so), the
     * [slot] along the rail it hangs in, and the game time it was hung ([pinned]; [LONG_AGO] if it was found hung).
     */
    class Ticket(val order: Int, val good: Good, val count: Int, val shop: Component, val taken: Boolean, val slot: Int, val pinned: Long) {
        /** It as its order now stands: [count] wanted, [taken] or not. */
        fun with(count: Int, taken: Boolean): Ticket = if (count == this.count && taken == this.taken) this else Ticket(order, good, count, shop, taken, slot, pinned)

        /** Whether it shows the same as [other]. */
        fun same(other: Ticket): Boolean =
            order == other.order && good == other.good && count == other.count && shop == other.shop && taken == other.taken && slot == other.slot && pinned == other.pinned

        companion object {
            val CODEC: Codec<Ticket> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("order").forGetter(Ticket::order),
                    Codec.STRING.fieldOf("good").forGetter { it.good.id },
                    Codec.INT.fieldOf("count").forGetter(Ticket::count),
                    ComponentSerialization.CODEC.fieldOf("shop").forGetter(Ticket::shop),
                    Codec.BOOL.optionalFieldOf("taken", false).forGetter(Ticket::taken),
                    Codec.INT.optionalFieldOf("slot", 0).forGetter(Ticket::slot),
                    Codec.LONG.optionalFieldOf("pinned", LONG_AGO).forGetter(Ticket::pinned),
                ).apply(i) { order, good, count, shop, taken, slot, pinned -> Ticket(order, Good.byId(good) ?: Good.WHEAT, count, shop, taken, slot, pinned) }
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

    /** The orders' tickets on the rail, each in its own slot (0 until [TICKETS]). */
    val tickets: MutableList<Ticket> = ArrayList()

    /** The line the crier's chalk is on right now, or -1; drawn as the hand at work. */
    var writing: Int = -1
        private set

    /** Whether the last write-up was seen through: false while the crier is at it, and if they were called away from it. */
    var finished: Boolean = true
        private set

    /** The town's times as the medallion at the board's head shows them (a [io.github.vaspyyy.guildmark.economy.fortune.Times.id]). */
    var times: String = "steady"
        private set

    /** The game time the medallion last turned to [times], or [LONG_AGO] if it was found so (nothing to see turn). */
    var turned: Long = LONG_AGO
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

    /** Server side: hangs [ticket] up in its slot, taking down whatever hung there (and any other ticket for its order). */
    fun hang(ticket: Ticket) {
        tickets.removeAll { it.slot == ticket.slot || it.order == ticket.order }
        tickets.add(ticket)
        sync()
    }

    /** Server side: the tickets as they now stand, if that's changed (see [io.github.vaspyyy.guildmark.economy.MarketBoards.tickets]). */
    fun tickets(next: List<Ticket>) {
        if (next.size == tickets.size && next.indices.all { next[it].same(tickets[it]) }) return
        tickets.clear()
        tickets.addAll(next)
        sync()
    }

    /** Server side: the medallion turns to [times] (a [io.github.vaspyyy.guildmark.economy.fortune.Times.id]), if it isn't showing it already. */
    fun times(times: String) = times(times, seen = true)

    /**
     * Server side: as [times], turning in sight of someone ([seen]), or else found turned ([turned] [LONG_AGO]: the
     * times moved while nobody was by to see it go).
     */
    fun times(times: String, seen: Boolean) {
        if (this.times == times) return
        this.times = times
        turned = if (seen) level?.gameTime ?: LONG_AGO else LONG_AGO
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
        output.store("tickets", Ticket.CODEC.listOf(), tickets)
        output.putInt("writing", writing)
        output.putBoolean("finished", finished)
        output.putString("times", times)
        output.putLong("turned", turned)
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
        tickets.clear()
        input.read("tickets", Ticket.CODEC.listOf()).ifPresent { tickets.addAll(it) }
        writing = input.getIntOr("writing", -1)
        finished = input.getBooleanOr("finished", true)
        times = input.getStringOr("times", "steady")
        turned = input.getLongOr("turned", LONG_AGO)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** The most slips a board holds. */
        const val SLIPS = 4

        /** How many tickets hang along the rail, at most (as many as a capital has orders out). */
        const val TICKETS = 6

        /** A [Line.chalked], [Slip.pinned] or [Ticket.pinned] time long past: shown whole, with nothing still being written. */
        const val LONG_AGO = Long.MIN_VALUE / 4
    }
}
