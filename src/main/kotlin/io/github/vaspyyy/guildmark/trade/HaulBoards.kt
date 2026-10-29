package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.HaulBoardBlock
import io.github.vaspyyy.guildmark.block.HaulBoardBlockEntity
import io.github.vaspyyy.guildmark.block.MarketBoardBlock
import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.client.trade.HaulBoardArt
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.UUID

private typealias Hung = HaulBoardBlockEntity.Ticket
private typealias Chalked = HaulBoardBlockEntity.Row

/**
 * The Haul Board (living-world/trader/design.md, 12.6): what goes on it and when, on the pattern of [io.github.vaspyyy.guildmark.economy.MarketBoards]. It
 * stands beside a capital's market board and carries the Word on its slate (a row for each town round about: see [Word.view]) and the town's own haul
 * tickets on its rail (see [Hauls]). Nothing on it is its own news: the rows are the Word's and the tickets are the book's, and the board only keeps
 * what it was last written up as, so a client can draw the writing as it comes.
 *
 * The hall's merchant writes it up at half past seven in the morning and half past twelve (see [HaulClerk]), when a player is by to watch, and hangs
 * a new ticket whenever one is posted. A board nobody is near to see written up, or one the merchant never came to (they died, or could not get there
 * in [CLERK_WAIT] ticks), is found written up all the same; and what is seen to change when no merchant is writing it comes about by a slow
 * cross-fade on the client, never a jump. A booked ticket is stamped in its booker's colour at once, and one that is filled, lapsed or withdrawn
 * comes down at once. Old capitals, from before there were Haul Boards, get theirs put up beside the market board while nobody is looking.
 *
 * With `tradeLife` off the board does nothing and writes nothing, and stands as it was; with the Word off its rows stand as they were, and with
 * the tickets off so do its tickets.
 */
object HaulBoards {
    /** The board's block item, in a register of its own (as the Tally has), so that no shared registry is touched; its model is `items/haul_board.json`. */
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)

    val HAUL_BOARD: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("haul_board", ModBlocks.HAUL_BOARD)

    /** How near a player may be for the board to count as seen (the clerk comes only for these); and for it to be put up where it was not. */
    const val NEAR = 48.0
    private const val UNSEEN = 24.0

    /** The ticks between a board's looks to itself, between looks over the capitals near players for a missing board, and between tries for one that cannot be put up. */
    private const val EVERY = 20L
    private const val LOOK = 200L
    private const val RETRY = 2400L

    /** How long the merchant has to come once the board is seen to want them, and how long after its last stroke the chalk is taken to be put down for good, and the satchel taken up. */
    private const val CLERK_WAIT = 2400L
    private const val PUT_DOWN = 200L
    private const val SATCHEL_LEFT = 600L

    /** The ticks between trips the merchant makes to hang tickets posted between the rounds. */
    private const val AGAIN = 1200L

    /** The clock's minutes of the morning's round and of noon's (half past seven and half past twelve). */
    private const val MORNING_AT = 450L
    private const val NOON_AT = 750L

    /** Where beside the market board a Haul Board is put: the anchor's offset, in cells, along the board's row. The first is flush (a board is three wide). */
    private val BESIDE = intArrayOf(3, -3, 4, -4)

    // ---- What is kept while the world runs (none of it saved) ----

    /** The anchor of each capital's board that is loaded, by the capital's heart. */
    private val known = HashMap<Long, BlockPos>()

    /** The capital's heart each board anchor belongs to. */
    private val capitals = HashMap<Long, Long>()

    /** Since when a board that the merchant should come to has been waiting, by anchor; and when the merchant last made a trip for tickets. */
    private val waiting = HashMap<Long, Long>()
    private val trips = HashMap<Long, Long>()

    /** The capitals whose board is up (so they are not looked at again), and the game time a capital's board could not be put up (so it is not tried over and over). */
    private val up = HashSet<Long>()
    private val tried = HashMap<Long, Long>()
    private var looked = 0L

    /** The boards players have read, to be told what changes (see [HaulBoardViews]). */
    internal val reading = HashMap<UUID, BlockPos>()

    fun register(modBus: IEventBus) {
        ITEMS.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) event.accept(HAUL_BOARD.get())
        }
        BoardCommand.init()
        Hauls.listen(object : Hauls.Listener {
            override fun posted(level: ServerLevel, haul: Haul) = refresh(level, haul.capital)
            override fun booked(level: ServerLevel, haul: Haul) = refresh(level, haul.capital)
            override fun delivered(level: ServerLevel, haul: Haul, delivery: Delivery) = refresh(level, haul.capital)
            override fun lapsed(level: ServerLevel, haul: Haul) = refresh(level, haul.capital)
            override fun withdrawn(level: ServerLevel, haul: Haul) = refresh(level, haul.capital)
        })
        Word.listen(object : Word.Listener {
            override fun updated(level: ServerLevel, capital: BlockPos) = refresh(level, capital)
        })
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { look(it.server) }
        // The next world's clock starts again from its own time, which may be earlier than this one's
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> reset() }
    }

    /** The server stopped: nothing kept is carried into the next world. */
    fun reset() {
        known.clear()
        capitals.clear()
        waiting.clear()
        trips.clear()
        up.clear()
        tried.clear()
        reading.clear()
        looked = 0L
        HaulClerk.reset()
    }

    // ---- Where things are on the board ----

    /** The way the board at [anchor] faces. */
    internal fun facing(level: Level, anchor: BlockPos): Direction? =
        level.getBlockState(anchor).takeIf { it.block is HaulBoardBlock }?.getValue(NoticeBoardBlock.FACING)

    /**
     * The point on the board at [anchor] that is [u] blocks along it from its left edge, [v] down from its top and [depth] back from the
     * front of its cells (the numbers of [HaulBoardArt]).
     */
    internal fun at(anchor: BlockPos, facing: Direction, u: Double, v: Double, depth: Double): Vec3 {
        val right = NoticeBoardBlock.rightOf(facing)
        val front = 0.5 - depth
        return Vec3(
            anchor.x + 0.5 + right.stepX * (u - 1.5) + facing.stepX * front,
            anchor.y + 2.0 - v,
            anchor.z + 0.5 + right.stepZ * (u - 1.5) + facing.stepZ * front,
        )
    }

    /** The middle of the ticket on hook [slot]. */
    internal fun hook(anchor: BlockPos, facing: Direction, slot: Int): Vec3 {
        val u = HaulBoardArt.HOOK_U0 + (slot % 4) * HaulBoardArt.HOOK_PITCH + 0.0
        val v = (if (slot < 4) HaulBoardArt.HOOK_V0 else HaulBoardArt.HOOK_V1) + HaulBoardArt.TICKET_BLOCKS * HaulBoardArt.TICKET_H / HaulBoardArt.TICKET_W * 0.5
        return at(anchor, facing, u, v, HaulBoardArt.TICKET_DEPTH.toDouble())
    }

    /** The middle of the slate. */
    internal fun slate(anchor: BlockPos, facing: Direction): Vec3 =
        at(anchor, facing, (HaulBoardArt.SLATE_U0 + HaulBoardArt.SLATE_U1) / 2.0, (HaulBoardArt.PANEL_V0 + HaulBoardArt.PANEL_V1) / 2.0, HaulBoardArt.SLATE_DEPTH.toDouble())

    /** The middle of the shelf's foot, where the satchel stands. */
    internal fun shelf(anchor: BlockPos, facing: Direction): Vec3 =
        at(anchor, facing, HaulBoardArt.SATCHEL_U.toDouble(), HaulBoardArt.SATCHEL_V.toDouble(), HaulBoardArt.SATCHEL_DEPTH.toDouble())

    /** The block in front of the board that is [col] cells along it from its middle (-1 the left cell's, 0 the middle's, 1 the right's). */
    internal fun stand(anchor: BlockPos, facing: Direction, col: Int): BlockPos = anchor.relative(facing).relative(NoticeBoardBlock.rightOf(facing), col)

    /** The column to stand in to reach hook [slot]. */
    internal fun column(slot: Int): Int = if (HaulBoardArt.HOOK_U0 + (slot % 4) * HaulBoardArt.HOOK_PITCH < 2.0f) 0 else 1

    /** A sound at [at], named for [TradeSounds]. */
    internal fun sound(level: ServerLevel, at: Vec3, name: String, volume: Float = 1.0f, pitch: Float = 1.0f) {
        level.playSound(null, at.x, at.y, at.z, TradeSounds.get(name), SoundSource.BLOCKS, volume, pitch)
    }

    /** The middle of row [k] of the slate, where the chalk is while it is written. */
    internal fun rowAt(anchor: BlockPos, facing: Direction, k: Int): Vec3 {
        val u = HaulBoardArt.SLATE_U0 + HaulBoardArt.PAD_L + 0.55
        val v = HaulBoardArt.PANEL_V0 + HaulBoardArt.ROWS_TOP + (k + 0.5) * HaulBoardArt.ROW_PITCH
        return at(anchor, facing, u, v, HaulBoardArt.SLATE_DEPTH.toDouble())
    }

    // ---- The round ----

    /**
     * The merchant's round that is due as of now: the day's number twice over, plus one from half past twelve; the morning's is even, noon's odd.
     * Rounds are by the clock (a day is 24,000 ticks and the world starts at six in the morning), as the market board's are.
     */
    internal fun round(level: ServerLevel): Long {
        val calendar = Math.floorDiv(level.overworldClockTime * 6L, 100L) + 360L
        val day = Math.floorDiv(calendar, 1440L)
        val of = Math.floorMod(calendar, 1440L)
        return when {
            of >= NOON_AT -> day * 2 + 1
            of >= MORNING_AT -> day * 2
            else -> day * 2 - 1
        }
    }

    internal fun morning(round: Long): Boolean = Math.floorMod(round, 2L) == 0L

    // ---- What the board should say ----

    /** The slate as the Word would have it written now for the capital whose heart is [capital], nearest town first. */
    internal fun rowsFor(level: ServerLevel, capital: BlockPos): List<Chalked> =
        Word.view(level, capital, null).take(HaulBoardBlockEntity.ROWS).map { r ->
            Chalked(
                r.capital.asLong(), r.name, r.distance, if (r.seeded) 0L else r.tick, if (r.seeded) (r.age * Word.HOUR).toLong() else -1L,
                r.sight.shorts(HaulBoardBlockEntity.CHIPS), r.sight.gluts(HaulBoardBlockEntity.CHIPS), r.sight.road?.stage ?: -1, r.sight.tickets.size, r.by ?: "",
                HaulBoardBlockEntity.LONG_AGO,
            )
        }

    /** The indexes of the rows [want] that the board does not have as they are. */
    internal fun missing(have: List<Chalked>, want: List<Chalked>): List<Int> = want.indices.filter { !want[it].same(have.getOrNull(it)) }

    /** Ticket [t] of [book] as the rail shows it, hung on [slot], with the times it was hung, stamped and sealed. */
    private fun shown(clock: Long, t: Ticket, slot: Int, pinned: Long, stamped: Long, sealed: Long): Hung {
        val count = if (t.open > 0) t.open else t.left
        val seal = t.bookings.firstOrNull { it.left > 0 }?.let { Hauls.seal(it.taker) } ?: -1
        val days = ceilDiv(t.due - clock, 24L).coerceIn(0L, HaulBoardArt.NOTCH_MAX.toLong()).toInt()
        val span = ceilDiv(t.due - t.posted, 24L).coerceIn(1L, HaulBoardArt.NOTCH_MAX.toLong()).toInt()
        return Hung(t.id, t.good, count, TallyLinks.part(t.pay, count, t.units), t.shop, t.kind, minOf(days, span), span, seal, slot, pinned, stamped, sealed)
    }

    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

    /** The capital's open tickets that still have goods to come in; none while the tickets are off. */
    private fun book(level: ServerLevel, capital: BlockPos): HaulBook? = if (!TradeConfig.hauls()) null else HaulData.peek(level)?.get(capital.asLong())

    /**
     * The tickets of [capital] the rail does not have, each on a free hook (the dearest first), found hung ([HaulBoardBlockEntity.LONG_AGO]); a ticket
     * waits for a hook while the rail is full, and no ticket that is hung is ever taken down to make room.
     */
    internal fun fresh(level: ServerLevel, board: HaulBoardBlockEntity, capital: BlockPos): List<Hung> {
        val book = book(level, capital) ?: return listOf()
        val clock = Hauls.clock(level, book)
        val free = (0 until HaulBoardBlockEntity.HOOKS).filter { s -> board.tickets.none { it.slot == s } }
        val wanted = book.tickets.filter { it.left > 0 && board.ticket(it.id) == null }.sortedWith(compareBy({ -it.pay }, { it.id }))
        return wanted.zip(free) { t, slot -> shown(clock, t, slot, HaulBoardBlockEntity.LONG_AGO, HaulBoardBlockEntity.LONG_AGO, HaulBoardBlockEntity.LONG_AGO) }
    }

    // ---- The board looks to itself ----

    /** Runs on every loaded board's anchor, every tick: every [EVERY] it looks to itself. */
    fun tick(level: ServerLevel, anchor: BlockPos) {
        if (Math.floorMod(level.gameTime + anchor.asLong(), EVERY) != 0L) return
        check(level, anchor)
    }

    /** The capital's heart the board at [anchor] belongs to (worked out once, and only from chunks already loaded), if it is in one. */
    internal fun capitalOf(level: ServerLevel, anchor: BlockPos): BlockPos? {
        capitals[anchor.asLong()]?.let { return BlockPos.of(it) }
        val capital = Furnish.capitalAt(level, anchor)?.center ?: return null
        capitals[anchor.asLong()] = capital.asLong()
        known[capital.asLong()] = anchor.immutable()
        return capital
    }

    /** The loaded board of the capital whose heart is [capital], if it stands. */
    internal fun boardOf(level: ServerLevel, capital: BlockPos): Pair<BlockPos, HaulBoardBlockEntity>? {
        val anchor = known[capital.asLong()] ?: return null
        if (!level.hasChunkAt(anchor)) return null
        val board = level.getBlockEntity(anchor) as? HaulBoardBlockEntity ?: return null
        return anchor to board
    }

    /** Something the board shows has changed at [capital]: it looks to itself now. */
    private fun refresh(level: ServerLevel, capital: BlockPos) {
        if (!TradeConfig.life()) return
        val (anchor, _) = boardOf(level, capital) ?: return
        check(level, anchor)
    }

    private fun near(level: ServerLevel, anchor: BlockPos, reach: Double): Boolean =
        level.getNearestPlayer(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5, reach, false) != null

    private fun check(level: ServerLevel, anchor: BlockPos) {
        if (!TradeConfig.life()) return
        val board = level.getBlockEntity(anchor) as? HaulBoardBlockEntity ?: return
        val capital = capitalOf(level, anchor) ?: return
        val now = level.gameTime
        val busy = HaulClerk.busy(level, anchor)
        // The chalk is put down, the satchel taken up, when the merchant has been called away from them
        if (board.writing != -1 && !busy && now - board.stroke > PUT_DOWN) board.done()
        if (board.satchel && !busy && now - board.satchelAt > SATCHEL_LEFT) board.satchel(false)
        val facing = facing(level, anchor) ?: return
        val seen = near(level, anchor, NEAR)
        tickets(level, board, anchor, facing, capital, seen)
        if (busy) {
            waiting[anchor.asLong()] = now
            return
        }
        val key = round(level)
        val ak = anchor.asLong()
        val rows = if (TradeConfig.word()) rowsFor(level, capital) else null
        val todo = if (rows == null) listOf() else missing(board.rows, rows)
        val shrink = rows != null && board.rows.size > rows.size
        val fresh = fresh(level, board, capital)
        val owed = board.round != key || !board.finished
        if (todo.isEmpty() && !shrink && fresh.isEmpty()) {
            waiting.remove(ak)
            if (rows != null) {
                if (!board.finished) board.finish(board.rows.size)
                board.current(key)
            }
            return
        }
        if (seen) {
            val first = waiting.getOrPut(ak) { now }
            val rowsNow = if (owed) todo else listOf()
            val shrinkNow = shrink && owed
            val hang = if (owed || now - (trips[ak] ?: (Long.MIN_VALUE / 2)) >= AGAIN) fresh else listOf()
            if (rowsNow.isEmpty() && !shrinkNow && hang.isEmpty()) {
                // Changed since the round, with someone looking: the next round has it
                waiting.remove(ak)
                return
            }
            if (now - first < CLERK_WAIT) {
                // The hall's merchant is sent for; until one can come, the board waits for them
                if (HaulClerk.call(level, anchor, board, facing, capital, rows.orEmpty(), rowsNow, shrinkNow, hang, key)) trips[ak] = now
                return
            }
            // No merchant came: the writing comes about by itself, faded in
            write(board, rows, if (owed) todo else listOf(), shrinkNow, fresh, key)
        } else {
            write(board, rows, todo, shrink, fresh, key)
        }
        waiting.remove(ak)
    }

    /** The board writes itself up: [todo] rows (and the rest taken off if [shrink]) and the [fresh] tickets, found as they are. */
    private fun write(board: HaulBoardBlockEntity, rows: List<Chalked>?, todo: List<Int>, shrink: Boolean, fresh: List<Hung>, key: Long) {
        if (rows != null && (todo.isNotEmpty() || shrink)) {
            // The rows already written as they are stay as they were chalked
            board.write(rows.mapIndexed { k, row -> board.rows.getOrNull(k)?.takeIf { row.same(it) } ?: row }, key)
        }
        if (fresh.isNotEmpty()) board.tickets(board.tickets + fresh)
    }

    /**
     * Keeps the hung tickets to the book's: counting what is wanted now, a booker's seal pressed in their colour (with the thump, if the board is seen) and
     * taken off if the booking goes, and one that is filled, lapsed or withdrawn peeled off and let fall. Hangs nothing new.
     */
    private fun tickets(level: ServerLevel, board: HaulBoardBlockEntity, anchor: BlockPos, facing: Direction, capital: BlockPos, seen: Boolean) {
        if (!TradeConfig.hauls() || board.tickets.isEmpty()) return
        val book = HaulData.peek(level)?.get(capital.asLong())
        val live = book?.tickets?.filter { it.left > 0 }?.associateBy { it.id } ?: mapOf()
        val clock = book?.let { Hauls.clock(level, it) } ?: 0L
        val now = level.gameTime
        val next = ArrayList<Hung>()
        for (old in board.tickets) {
            val t = live[old.id]
            if (t == null) {
                if (seen) sound(level, hook(anchor, facing, old.slot), "trade.ticket_peel", 0.9f)
                continue
            }
            var n = shown(clock, t, old.slot, old.pinned, old.stamped, old.sealed)
            if (n.seal != old.seal) {
                // Booked: stamped again in the booker's colour, the stamp's thump and the wax pressed after it
                val pressed = if (n.seal >= 0 && seen) now else HaulBoardBlockEntity.LONG_AGO
                n = n.with(sealed = pressed)
                if (n.seal >= 0 && seen) {
                    val at = hook(anchor, facing, old.slot)
                    sound(level, at, "trade.board_stamp", 0.8f, 1.1f)
                    sound(level, at, "trade.seal_press", 0.9f)
                }
            }
            next.add(n)
        }
        board.tickets(next)
    }

    /**
     * `trade board chalk`: the board at [anchor] is wiped clean (its tickets peel off and its slate is rubbed out, which a client near enough sees) and
     * left owing its round, so the hall's merchant is sent for to write it all up again, as at the round, the next time it looks to itself.
     */
    internal fun wipe(level: ServerLevel, anchor: BlockPos, board: HaulBoardBlockEntity) {
        val facing = facing(level, anchor)
        if (facing != null) for (t in board.tickets) sound(level, hook(anchor, facing, t.slot), "trade.ticket_peel", 0.8f)
        board.wipe()
        waiting.remove(anchor.asLong())
        trips.remove(anchor.asLong())
        check(level, anchor)
    }

    // ---- Boards for the capitals that were built without one ----

    private fun look(server: MinecraftServer) {
        val level = server.overworld()
        val time = level.gameTime
        if (time - looked < LOOK || level.players().isEmpty() || !TradeConfig.life()) return
        looked = time
        val seen = HashSet<BlockPos>()
        for (player in level.players()) {
            val capital = Furnish.capitalAt(level, player.blockPosition()) ?: continue
            val heart = capital.center
            if (heart.asLong() in up || !seen.add(heart)) continue
            if (time - (tried[heart.asLong()] ?: Long.MIN_VALUE / 2) < RETRY) continue
            val places = CityPlaces.of(level, capital) ?: continue
            if (put(level, places)) up.add(heart.asLong()) else tried[heart.asLong()] = time
        }
    }

    /**
     * Puts [places]' capital's Haul Board up beside its market board, if it isn't there: nobody is near enough to see it go up (unless [seen]), and every
     * cell of it is free (nothing is ever knocked down for it) on firm ground. It goes flush beside the market board on the right, or else the left, or
     * a cell further off. Gives whether it is up now.
     */
    fun put(level: ServerLevel, places: CityPlaces, seen: Boolean = false): Boolean {
        val market = places.marketBoard
        val facing = market.facing
        val right = NoticeBoardBlock.rightOf(facing)
        if (!level.hasChunkAt(market.pos) || level.getBlockState(market.pos).block !is MarketBoardBlock) return false
        boardOf(level, places.centre)?.let { return true }
        val anchors = BESIDE.map { market.pos.relative(right, it) }
        for (anchor in anchors) {
            if (level.hasChunkAt(anchor) && level.getBlockState(anchor).block is HaulBoardBlock) return mark(places.centre, anchor)
        }
        for (anchor in anchors) if (place(level, anchor, facing, seen)) return mark(places.centre, anchor)
        return false
    }

    /** The board of the capital whose heart is [capital] stands at [anchor]. */
    private fun mark(capital: BlockPos, anchor: BlockPos): Boolean {
        known[capital.asLong()] = anchor.immutable()
        capitals[anchor.asLong()] = capital.asLong()
        return true
    }

    private fun place(level: ServerLevel, anchor: BlockPos, facing: Direction, seen: Boolean): Boolean {
        val cells = BoardPart.entries.map { NoticeBoardBlock.cellPos(anchor, facing, it) }
        if (cells.any { !level.hasChunkAt(it) || level.isOutsideBuildHeight(it) }) return false
        if (!seen && near(level, anchor, UNSEEN)) return false
        for (cell in cells) {
            if (!level.getBlockState(cell).canBeReplaced()) return false
        }
        // Its face must be clear to read (and for the merchant to stand at): never put up facing a wall, a stall or a doorway
        for (part in BoardPart.entries) {
            val front = NoticeBoardBlock.cellPos(anchor, facing, part).relative(facing)
            if (!level.hasChunkAt(front) || !level.getBlockState(front).getCollisionShape(level, front).isEmpty) return false
        }
        for (part in BoardPart.entries.filter { it.row == 0 }) {
            val below = NoticeBoardBlock.cellPos(anchor, facing, part).below()
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false
        }
        val state = ModBlocks.HAUL_BOARD.get().defaultBlockState().setValue(NoticeBoardBlock.FACING, facing)
        for (part in BoardPart.entries) level.setBlock(NoticeBoardBlock.cellPos(anchor, facing, part), state.setValue(NoticeBoardBlock.PART, part), Block.UPDATE_ALL)
        return true
    }

    // ---- Reading it ----

    /**
     * [player] reads the board at [anchor]: what it says goes into their Tally if they carry one (see [Tally.copyBoard]), and the board's screen
     * opens on what the Word says of the towns round about and the tickets this town has out (see [HaulBoardViews]).
     */
    fun read(level: ServerLevel, player: ServerPlayer, anchor: BlockPos) {
        if (!TradeConfig.life()) {
            player.sendSystemMessage(Component.translatable(Wares.OFF).withStyle(ChatFormatting.GRAY))
            return
        }
        val capital = capitalOf(level, anchor)
        if (capital == null) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.trade.board.bare"))
            return
        }
        if (Tally.has(player)) Tally.copyBoard(level, player, capital)
        reading[player.uuid] = anchor.immutable()
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.board.bare"))
    }
}
