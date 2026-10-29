package io.github.vaspyyy.guildmark.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import io.github.vaspyyy.guildmark.trade.HaulKind
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The anchor cell of a Haul Board (see [HaulBoardBlock]): the slate's rows (a row for each town round about: how old the
 * news of it is, what it is short of and has too much of, its worst road, the tickets it has out) and the rail's tickets
 * (this town's own, up to [HOOKS] of them, the dearest). Synced to clients whole, so the board can be drawn as it stands:
 * a row chalked since a viewer last looked is written stroke by stroke ([Row.chalked]), a ticket hung since then flutters
 * on its hook and is stamped ([Ticket.pinned], [Ticket.stamped]), one booked is stamped again in its booker's colour
 * ([Ticket.sealed]), and one that is gone peels off and drops. A stamp of [LONG_AGO] says the thing was found so, and shows
 * whole (see [io.github.vaspyyy.guildmark.trade.HaulBoards], which writes it all).
 *
 * Everything on it comes from the Word and the haul tickets as they stand: it keeps no news of its own.
 */
class HaulBoardBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.HAUL_BOARD.get(), pos, state) {
    /**
     * One town's row, as the board has it: the capital's heart ([capital]) and [name] (empty while its name is not known),
     * its [distance] in blocks, the overworld clock tick its news is of ([tick]: the glass's sand runs from then; a news
     * that is only as old as the delay allows, the Word's first, has no tick but an [age] in game ticks that stays as it is,
     * else [age] is -1), the goods it was short of and had too much of (at most [CHIPS] each), the stage of its worst road ([road]: 0 open to 3 closed,
     * -1 not kept), how many tickets it had out ([tickets]), who brought the news if a report did ([by], else empty), and
     * the game time the row was written ([chalked]; [LONG_AGO] for a row found written, which shows whole).
     */
    class Row(
        val capital: Long, val name: String, val distance: Int, val tick: Long, val age: Long,
        val shorts: List<Good>, val gluts: List<Good>, val road: Int, val tickets: Int, val by: String, val chalked: Long,
    ) {
        /** Whether it says the same as [other], so writing it again would change nothing on the board. */
        fun same(other: Row?): Boolean =
            other != null && capital == other.capital && name == other.name && distance == other.distance && tick == other.tick && age == other.age &&
                shorts == other.shorts && gluts == other.gluts && road == other.road && tickets == other.tickets && by == other.by

        /** The same row, written at game time [at]. */
        fun written(at: Long): Row = Row(capital, name, distance, tick, age, shorts, gluts, road, tickets, by, at)

        companion object {
            private val GOODS: Codec<List<Good>> = Codec.STRING.listOf().xmap({ ids -> ids.mapNotNull { Good.byId(it) } }, { goods -> goods.map { it.id } })

            val CODEC: Codec<Row> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("capital").forGetter(Row::capital),
                    Codec.STRING.optionalFieldOf("name", "").forGetter(Row::name),
                    Codec.INT.optionalFieldOf("distance", 0).forGetter(Row::distance),
                    Codec.LONG.optionalFieldOf("tick", 0L).forGetter(Row::tick),
                    Codec.LONG.optionalFieldOf("age", -1L).forGetter(Row::age),
                    GOODS.optionalFieldOf("shorts", listOf()).forGetter(Row::shorts),
                    GOODS.optionalFieldOf("gluts", listOf()).forGetter(Row::gluts),
                    Codec.INT.optionalFieldOf("road", -1).forGetter(Row::road),
                    Codec.INT.optionalFieldOf("tickets", 0).forGetter(Row::tickets),
                    Codec.STRING.optionalFieldOf("by", "").forGetter(Row::by),
                    Codec.LONG.optionalFieldOf("chalked", LONG_AGO).forGetter(Row::chalked),
                ).apply(i, ::Row)
            }
        }
    }

    /**
     * One of the town's haul tickets on a hook of the rail: number [id], [count] of [good] wanted at [shop] for [pay] Marks, of
     * its [kind], with [days] left of the [span] it was posted for (the notch row along its foot), the colour (0 to 5) of the seal
     * of whoever booked it ([seal], -1 for nobody), the hook it hangs on ([slot], 0 until [HOOKS]) and, in game time, when it
     * was hung ([pinned]), when the hall's stamp came down on it ([stamped]; [UNSTAMPED] while it has none yet) and when its
     * booker's seal was pressed ([sealed]). A time of [LONG_AGO] is a thing found as it is, shown whole.
     */
    class Ticket(
        val id: Int, val good: Good, val count: Int, val pay: Int, val shop: Shop, val kind: HaulKind, val days: Int, val span: Int,
        val seal: Int, val slot: Int, val pinned: Long, val stamped: Long, val sealed: Long,
    ) {
        /** Whether it shows the same as [other] (but for when it was stamped and sealed). */
        fun same(other: Ticket): Boolean =
            id == other.id && good == other.good && count == other.count && pay == other.pay && shop == other.shop && kind == other.kind &&
                days == other.days && span == other.span && seal == other.seal && slot == other.slot

        /** The same ticket with what is given changed. */
        fun with(
            count: Int = this.count, pay: Int = this.pay, days: Int = this.days, seal: Int = this.seal, slot: Int = this.slot,
            pinned: Long = this.pinned, stamped: Long = this.stamped, sealed: Long = this.sealed,
        ): Ticket = Ticket(id, good, count, pay, shop, kind, days, span, seal, slot, pinned, stamped, sealed)

        companion object {
            val CODEC: Codec<Ticket> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("id").forGetter(Ticket::id),
                    Codec.STRING.fieldOf("good").forGetter { it.good.id },
                    Codec.INT.fieldOf("count").forGetter(Ticket::count),
                    Codec.INT.optionalFieldOf("pay", 0).forGetter(Ticket::pay),
                    Codec.STRING.optionalFieldOf("shop", Shop.HALL.id).forGetter { it.shop.id },
                    Codec.STRING.optionalFieldOf("kind", HaulKind.PARCEL.id).forGetter { it.kind.id },
                    Codec.INT.optionalFieldOf("days", 1).forGetter(Ticket::days),
                    Codec.INT.optionalFieldOf("span", 1).forGetter(Ticket::span),
                    Codec.INT.optionalFieldOf("seal", -1).forGetter(Ticket::seal),
                    Codec.INT.optionalFieldOf("slot", 0).forGetter(Ticket::slot),
                    Codec.LONG.optionalFieldOf("pinned", LONG_AGO).forGetter(Ticket::pinned),
                    Codec.LONG.optionalFieldOf("stamped", LONG_AGO).forGetter(Ticket::stamped),
                    Codec.LONG.optionalFieldOf("sealed", LONG_AGO).forGetter(Ticket::sealed),
                ).apply(i) { id, good, count, pay, shop, kind, days, span, seal, slot, pinned, stamped, sealed ->
                    Ticket(
                        id, Good.byId(good) ?: Good.WHEAT, count, pay, Shop.byId(shop) ?: Shop.HALL, HaulKind.byId(kind) ?: HaulKind.PARCEL,
                        days, span, seal, slot, pinned, stamped, sealed,
                    )
                }
            }
        }
    }

    /** The rows of the slate, nearest town first, at most [ROWS]. */
    val rows: MutableList<Row> = ArrayList()

    /** The tickets on the rail, each on its own hook. */
    val tickets: MutableList<Ticket> = ArrayList()

    /** The last clerk's round (see [io.github.vaspyyy.guildmark.trade.HaulBoards.round]) the board was written up for; -1 if never. Not sent to anyone. */
    var round: Long = -1
        private set

    /** The game time the clerk began writing the slate up, or [LONG_AGO] if it was found written (nothing to see written). */
    var begun: Long = LONG_AGO
        private set

    /** The row the clerk's chalk is on right now, or -1; drawn as the hand at work. */
    var writing: Int = -1
        private set

    /** The game time of the last stroke the clerk made (the chalk is taken to be put down for good a while after it). */
    var stroke: Long = LONG_AGO
        private set

    /** Whether the last write-up was seen through: false while the clerk is at it, and if they were called away from it. */
    var finished: Boolean = true
        private set

    /** Whether the clerk's satchel stands on the shelf, and the game time it was set down there ([LONG_AGO] if it was found so). */
    var satchel: Boolean = false
        private set
    var satchelAt: Long = LONG_AGO
        private set

    // ---- The clerk's writing ----

    /** Server side: the clerk begins the write-up for [round]: their satchel is set on the shelf, nothing is changed on the slate yet. */
    fun begin(round: Long) {
        this.round = round
        begun = level?.gameTime ?: LONG_AGO
        finished = false
        sync()
    }

    /** Server side: the satchel is set down on the shelf, or taken up again. */
    fun satchel(on: Boolean) {
        if (satchel == on) return
        satchel = on
        satchelAt = if (on) level?.gameTime ?: LONG_AGO else LONG_AGO
        sync()
    }

    /** Server side: writes [row] at [index], the chalk moving on to it. Rows past the last are added; the rest stay. */
    fun chalk(index: Int, row: Row) {
        if (index !in 0 until ROWS) return
        while (rows.size <= index) rows.add(Row(row.capital, "", 0, row.tick, row.age, listOf(), listOf(), -1, 0, "", LONG_AGO))
        rows[index] = row
        writing = index
        stroke = level?.gameTime ?: LONG_AGO
        sync()
    }

    /** Server side: the chalk is put down. */
    fun done() {
        if (writing == -1) return
        writing = -1
        sync()
    }

    /** Server side: the write-up is seen through (rows past the [count] that the Word has are wiped off, the chalk and the satchel put away). */
    fun finish(count: Int) {
        while (rows.size > count) rows.removeAt(rows.size - 1)
        finished = true
        writing = -1
        satchel = false
        satchelAt = LONG_AGO
        sync()
    }

    /** Server side: the board is as it was written for [round], whether the clerk came or nothing needed writing. Nothing is sent. */
    fun current(round: Long) {
        if (this.round == round) return
        this.round = round
        setChanged()
    }

    /** Server side: writes the whole slate at once, as it would be found by someone coming by afterwards ([at] [LONG_AGO]), or as it comes about before someone's eyes. */
    fun write(next: List<Row>, round: Long) {
        rows.clear()
        rows.addAll(next.take(ROWS))
        this.round = round
        begun = LONG_AGO
        writing = -1
        finished = true
        satchel = false
        satchelAt = LONG_AGO
        sync()
    }

    // ---- The rail ----

    /** Server side: hangs [ticket] on its hook, taking down whatever hung there (and any other ticket for its number). */
    fun hang(ticket: Ticket) {
        tickets.removeAll { it.slot == ticket.slot || it.id == ticket.id }
        tickets.add(ticket)
        sync()
    }

    /** Server side: the hall's stamp comes down on ticket [id] now. */
    fun stamp(id: Int) {
        val at = tickets.indexOfFirst { it.id == id }
        if (at < 0) return
        tickets[at] = tickets[at].with(stamped = level?.gameTime ?: LONG_AGO)
        sync()
    }

    /** Server side: the tickets as they now stand, if that's changed. */
    fun tickets(next: List<Ticket>) {
        if (next.size == tickets.size && next.indices.all { next[it].same(tickets[it]) && next[it].stamped == tickets[it].stamped && next[it].sealed == tickets[it].sealed }) return
        tickets.clear()
        tickets.addAll(next)
        sync()
    }

    /**
     * Server side: everything is wiped off (`trade board chalk`): the slate rubbed clean and the tickets taken down, and the round left owing, so that the
     * clerk writes it all up again as at the round.
     */
    fun wipe() {
        rows.clear()
        tickets.clear()
        round = -1
        begun = LONG_AGO
        writing = -1
        finished = true
        satchel = false
        satchelAt = LONG_AGO
        sync()
    }

    /** The ticket numbered [id] on the rail. */
    fun ticket(id: Int): Ticket? = tickets.firstOrNull { it.id == id }

    private fun sync() {
        setChanged()
        val level = level ?: return
        if (level.isClientSide()) return
        val state = blockState
        level.sendBlockUpdated(blockPos, state, state, Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putLong("round", round)
        output.putLong("begun", begun)
        output.store("rows", Row.CODEC.listOf(), rows)
        output.store("tickets", Ticket.CODEC.listOf(), tickets)
        output.putInt("writing", writing)
        output.putLong("stroke", stroke)
        output.putBoolean("finished", finished)
        output.putBoolean("satchel", satchel)
        output.putLong("satchel_at", satchelAt)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        round = input.getLongOr("round", -1L)
        begun = input.getLongOr("begun", LONG_AGO)
        rows.clear()
        input.read("rows", Row.CODEC.listOf()).ifPresent { rows.addAll(it.take(ROWS)) }
        tickets.clear()
        input.read("tickets", Ticket.CODEC.listOf()).ifPresent { tickets.addAll(it.take(HOOKS)) }
        writing = input.getIntOr("writing", -1)
        stroke = input.getLongOr("stroke", LONG_AGO)
        finished = input.getBooleanOr("finished", true)
        satchel = input.getBooleanOr("satchel", false)
        satchelAt = input.getLongOr("satchel_at", LONG_AGO)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** The most rows the slate holds (as many as the Word gives a board), and the hooks on the rail (two tiers of four). */
        const val ROWS = 8
        const val HOOKS = 8

        /** The most red chips and green chips a row shows. */
        const val CHIPS = 3

        /** The ticks a clerk takes over one row, and over one ticket (hanging it, then stamping it). */
        const val ROW_TICKS = 64
        const val HANG_TICKS = 30
        const val STAMP_TICKS = 20

        /** A [Row.chalked], [Ticket.pinned], [Ticket.stamped] or [Ticket.sealed] time long past: shown whole, with nothing still being written. */
        const val LONG_AGO = Long.MIN_VALUE / 4

        /** A [Ticket.stamped] that is not yet: the ticket is hung and the hall's stamp is still to come down on it. */
        const val UNSTAMPED = Long.MAX_VALUE / 4
    }
}
