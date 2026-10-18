package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.MarketBoardBlock
import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity
import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * The capitals' market boards (see [MarketBoardBlock]): what goes on them and when. The town crier writes a board up
 * twice a day, first thing in the morning and at noon after crying the news, a line at a time and only the lines
 * that changed (see [line] and [MarketBoardBlockEntity.chalk]). A board nobody was by to see written up, or one the
 * crier never came to (they died, or couldn't get there), is found written up all the same. Old capitals, from
 * before there were boards, get theirs put up while nobody is looking.
 */
object MarketBoards {
    /** Every traded good in the board's order, by section: the staples, the produce, the catch, the meat, the wares. */
    val ORDER: List<Good> = listOf(
        Good.BREAD, Good.WHEAT,
        Good.CARROT, Good.POTATO, Good.BEETROOT, Good.APPLE, Good.HONEY,
        Good.COD, Good.SALMON,
        Good.BEEF, Good.PORK, Good.MUTTON, Good.CHICKEN,
        Good.WOOL, Good.STRING, Good.LEATHER, Good.FEATHER, Good.ARROW, Good.FLINT, Good.CANDLE, Good.BOTTLE, Good.IRON, Good.COAL,
    )

    /** The board's sections: a lang key for the heading and how many of [ORDER]'s goods it takes in. */
    val SECTIONS: List<Pair<String, Int>> = listOf(
        "board.guildmark.market.staples" to 2,
        "board.guildmark.market.produce" to 5,
        "board.guildmark.market.catch" to 2,
        "board.guildmark.market.meat" to 4,
        "board.guildmark.market.wares" to 10,
    )

    /** When the crier writes the board up: the clock's hours, the morning's and noon's. */
    const val MORNING = 7
    const val NOON = 12

    /** How many hours after it's due a board goes on being left to the crier, while anyone is by to see. */
    private const val GRACE = 2

    /** How near a player may be for anything to happen to a board out of sight. */
    private const val UNSEEN = 24.0

    /** How often (in ticks) each loaded board looks to itself, and how often the capitals near players are looked over for missing boards. */
    private const val EVERY = 100L
    private const val LOOK = 200L

    /** How long after its last stroke the crier's chalk is taken to be put down for good (they were called away). */
    private const val PUT_DOWN = 200L

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { look(it.server) }
    }

    /** One good's line as the board would have it now, written at game time [at]. */
    fun line(entry: Markets.Entry, good: Good, at: Long): MarketBoardBlockEntity.Line {
        val q = Trading.quote(entry, good)
        val off = q.why?.let { if (it.dear) 1 else -1 } ?: 0
        return MarketBoardBlockEntity.Line(good, q.buy?.count ?: 0, q.buy?.marks ?: 0, q.trend, off, at)
    }

    /** Every line of the board as it would be now, written at game time [at]. */
    fun lines(entry: Markets.Entry, at: Long): List<MarketBoardBlockEntity.Line> = ORDER.map { line(entry, it, at) }

    /** The board's write-up due last, as of [c]: its day, and whether it's noon's (or else the morning's). */
    fun due(c: Conditions): Pair<Long, Boolean> = when {
        c.clock >= NOON -> c.day to true
        c.clock >= MORNING -> c.day to false
        // Before the morning's (the day starts at two): yesterday's noon
        else -> c.day - 1 to true
    }

    /** Whether [board] is written up for [due] or later. */
    fun current(board: MarketBoardBlockEntity, due: Pair<Long, Boolean>): Boolean =
        board.day > due.first || board.day == due.first && (board.noon || !due.second)

    /** The board of [places]' capital, if it stands where the plan put it and its chunk is loaded. */
    fun board(level: ServerLevel, places: CityPlaces): MarketBoardBlockEntity? {
        val pos = places.marketBoard.pos
        if (!level.hasChunkAt(pos)) return null
        return level.getBlockEntity(pos) as? MarketBoardBlockEntity
    }

    /** The name of the capital a board stands in, for its head. */
    fun town(capital: CapitalPiece): Component = Component.literal(VillageNames.of(capital.boardAnchor))

    /** Runs on every loaded board's anchor: writes it up if the crier is late with it or nobody's by to see. */
    fun tick(level: ServerLevel, anchor: BlockPos) {
        val time = level.gameTime
        if (Math.floorMod(time + anchor.asLong(), EVERY) != 0L) return
        val board = level.getBlockEntity(anchor) as? MarketBoardBlockEntity ?: return
        val writing = board.lines.getOrNull(board.writing)
        if (board.writing != -1 && (writing == null || time - writing.chalked > PUT_DOWN)) board.done()
        if (!Markets.enabled()) return
        val capital = Furnish.capitalAt(level, anchor) ?: return
        val entry = Markets.of(level, capital) ?: return
        val c = Conditions(Markets.hourOf(level))
        val due = due(c)
        if (current(board, due)) return
        // Left to the crier while they still might come and someone's about to see them do it
        val hour = if (due.second) NOON else MORNING
        val late = board.day < 0 || c.day != due.first || c.clock >= hour + GRACE
        if (!late && level.getNearestPlayer(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5, UNSEEN * 2, false) != null) return
        board.write(town(capital), due.first, due.second, lines(entry, MarketBoardBlockEntity.LONG_AGO))
    }

    /** [player] reads the board at [anchor]: every price on it and the news, in chat. */
    fun read(level: ServerLevel, player: ServerPlayer, anchor: BlockPos) {
        val board = level.getBlockEntity(anchor) as? MarketBoardBlockEntity
        if (board == null || board.day < 0 || board.lines.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("board.guildmark.market.bare"))
            return
        }
        val c = Conditions(Markets.hourOf(level))
        val `when` = when {
            board.day == c.day -> if (board.noon) "board.guildmark.market.noon" else "board.guildmark.market.morning"
            board.day == c.day - 1 -> if (board.noon) "board.guildmark.market.yesterday_noon" else "board.guildmark.market.yesterday_morning"
            else -> "board.guildmark.market.old"
        }
        player.sendSystemMessage(
            Component.translatable("board.guildmark.market.head", board.town, Component.translatable(`when`)).withStyle(ChatFormatting.GOLD),
        )
        var i = 0
        for ((key, n) in SECTIONS) {
            val row = Component.empty().append(Component.translatable(key).withStyle(ChatFormatting.YELLOW)).append(" ")
            board.lines.subList(minOf(i, board.lines.size), minOf(i + n, board.lines.size)).forEachIndexed { j, line ->
                if (j > 0) row.append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
                row.append(entry(line))
            }
            i += n
            player.sendSystemMessage(row)
        }
        for (slip in board.slips) {
            player.sendSystemMessage(Component.literal("✎ ").withStyle(ChatFormatting.GRAY).append(slip.text.copy().withStyle(ChatFormatting.WHITE)))
        }
    }

    /** One line in chat: the good, its lot ("6 for 1"), the arrow, red if dear and green if cheap. */
    private fun entry(line: MarketBoardBlockEntity.Line): MutableComponent {
        val colour = when (line.off) {
            1 -> ChatFormatting.RED
            -1 -> ChatFormatting.GREEN
            else -> ChatFormatting.WHITE
        }
        val lot = if (line.count <= 0) Component.translatable("board.guildmark.market.none")
        else Component.translatable("board.guildmark.market.lot", line.count, line.marks)
        val arrow = when (line.trend) {
            1 -> " ▲"
            -1 -> " ▼"
            else -> ""
        }
        return Component.translatable(line.good.key).append(" ").append(lot).append(arrow).withStyle(colour)
    }

    // ---- Boards for the capitals that were built without one ----

    private var looked = 0L

    private fun look(server: MinecraftServer) {
        val level = server.overworld()
        val time = level.gameTime
        if (time - looked < LOOK || level.players().isEmpty() || !Markets.enabled()) return
        looked = time
        val seen = HashSet<BlockPos>()
        for (player in level.players()) {
            val capital = Furnish.capitalAt(level, player.blockPosition()) ?: continue
            if (!seen.add(capital.center)) continue
            val places = CityPlaces.of(level, capital) ?: continue
            put(level, places)
        }
    }

    /**
     * Puts [places]' capital's board up where the plan has it, if it isn't there, nobody is near enough to see it go
     * up and every cell of it is free (nothing is ever knocked down for it) on firm ground. Gives whether it's up now.
     */
    fun put(level: ServerLevel, places: CityPlaces, seen: Boolean = false): Boolean {
        val anchor = places.marketBoard.pos
        val facing = places.marketBoard.facing
        val cells = BoardPart.entries.map { NoticeBoardBlock.cellPos(anchor, facing, it) }
        if (cells.any { !level.hasChunkAt(it) }) return false
        if (level.getBlockState(anchor).block is MarketBoardBlock) return true
        if (!seen && level.getNearestPlayer(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5, UNSEEN, false) != null) return false
        for (cell in cells) {
            if (!level.getBlockState(cell).canBeReplaced()) return false
        }
        for (part in BoardPart.entries.filter { it.row == 0 }) {
            val below = NoticeBoardBlock.cellPos(anchor, facing, part).below()
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false
        }
        val state = ModBlocks.MARKET_BOARD.get().defaultBlockState().setValue(NoticeBoardBlock.FACING, facing)
        for (part in BoardPart.entries) level.setBlock(NoticeBoardBlock.cellPos(anchor, facing, part), state.setValue(NoticeBoardBlock.PART, part), Block.UPDATE_ALL)
        return true
    }
}
