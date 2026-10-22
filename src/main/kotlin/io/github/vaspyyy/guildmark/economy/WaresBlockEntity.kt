package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.city.plan.Look
import io.github.vaspyyy.guildmark.city.plan.Wares
import io.github.vaspyyy.guildmark.economy.works.Reserve
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A market display's block entity: whose market it shows (its capital, by the centre), what it shows ([wares]), and
 * how many items of each of its goods are on it ([counts], one lane of [WaresLayout] each). Every [CHECK] ticks while
 * a player is within [NEAR] blocks the server works the counts out from the market's stock, and it tells clients only
 * when they change; a client then has the items that came settle in, or those that went lift out ([motion]). On a
 * market hall table a player has let, it shows their own goods instead ([own], set by [StallTables]).
 */
class WaresBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(WaresRegistry.ENTITY.get(), pos, state) {
    /** How it looks, from its block. */
    val look: Look get() = (blockState.block as? WaresBlock)?.look ?: Look.BASKET

    /** What it shows: set by the city plan, or the first of its look's for one put down by hand. */
    var wares: Wares = Wares.of(look)
        private set

    /** The centre of the capital whose market it shows; null until known. */
    private var capital: BlockPos? = null

    /** A player's own goods (by id) it shows while the market hall table it stands on is theirs; null while it shows the market's. */
    var own: List<String>? = null
        private set

    /** The goods it shows, a lane each: a player's own while it shows theirs, else its [wares]'. */
    val goods: List<String> get() = own ?: wares.goods

    /** How many items of each good it shows, in the order of [goods]. */
    var counts: IntArray = full()
        private set

    /** Server side: when it last looked for its capital, for one put down by hand. */
    private var sought = Long.MIN_VALUE / 2

    /**
     * Client side: each good's items on the move, settling in or lifting out since the counts last changed; null for
     * a good whose items are still. The renderer works out each item's part in it.
     */
    var motion: Array<Motion?> = arrayOf()
        private set

    /** Client side: whether it has had its first counts, which show as they are, with nothing moving. */
    private var primed = false

    /** Client side: whatever the renderer keeps worked out for drawing it, its items' places (see client/economy/WaresRenderer). */
    @JvmField
    var drawn: Any? = null

    /** A good's items going from [from] to [to], starting at game time [since] (the client's). */
    class Motion(val from: Int, val to: Int, val since: Long)

    /**
     * The streets' slate (M6 Part D2, townsfolk/streets/Awnings): leaning on its front, chalked "Nothing today", while
     * the stall it stands on is shut with nothing to sell. 0 for none, -1 for one there already, else the game time it
     * was set down (it's chalked as it's seen). Sent to clients, never saved: after a restart the keeper finds the stall
     * shut again and the slate goes back.
     */
    var slate = 0L
        private set

    /**
     * Which words are chalked on the slate (Tales T1a, the Marlow note): [SLATE_NOTHING] "Nothing today", [SLATE_SEALED] "Closed:
     * the note", [SLATE_LEASE] "<name>'s lease", with [slateName] filled in. Sent to clients with the slate, never saved.
     */
    var slateWord = SLATE_NOTHING
        private set
    var slateName = ""
        private set

    /** Client side: the game time the slate was last taken away (it's lifted off as it's seen). */
    @JvmField
    var slateGone = Long.MIN_VALUE / 2

    /** Server side: the slate set down at [since] (-1: there already), or taken away (0), telling clients. */
    fun slate(level: ServerLevel, since: Long, word: Int = SLATE_NOTHING, name: String = "") {
        val words = if (since == 0L) SLATE_NOTHING else word
        val named = if (since == 0L) "" else name
        if (since == slate && words == slateWord && named == slateName) return
        // One up already stays as it is, unless its words are not these
        if (since != 0L && slate != 0L && words == slateWord && named == slateName) return
        slate = since
        slateWord = words
        slateName = named
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /** Sets up a display the plan puts down: showing [wares] from the market of the capital at [capital], full for now. */
    fun setUp(capital: BlockPos, wares: Wares) {
        this.capital = capital
        this.wares = wares
        own = null
        counts = full()
        setChanged()
    }

    /** Shows a player's own [goods] (see [own]), [counts] items of each, telling clients if that changes anything. */
    fun show(level: ServerLevel, goods: List<String>, counts: IntArray) {
        if (goods == own && counts.contentEquals(this.counts)) return
        own = goods
        this.counts = counts
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /** Its counts at the town's usual stock: a neat display, full to the rim. */
    fun full(): IntArray {
        val cap = WaresLayout.capacity(look, goods)
        return IntArray(cap.size / 2) { cap[it * 2] }
    }

    /** Every [CHECK] ticks, with a player near enough to see it soon, keeps up with its market. */
    fun serverTick(level: ServerLevel) {
        // Each display at its own moment in the round, so a market full of them doesn't all look at once
        if (Math.floorMod(level.gameTime + blockPos.asLong(), CHECK) != 0L || !WaresRegistry.enabled()) return
        if (!level.hasNearbyAlivePlayer(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5, NEAR)) return
        refresh(level)
    }

    /** Brings its counts up to date with its market, telling clients if they changed. */
    fun refresh(level: ServerLevel) {
        val next = target(level) ?: return
        if (next.contentEquals(counts)) return
        counts = next
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /**
     * What it should show now: each good as full as the market holds of it against what the town likes to keep (a
     * town granary's bins: as full as the granary holds of it against its room, see [Wares.reserve]), a count it's
     * near enough to kept so the heap doesn't flicker; full with the markets switched off; null (no change)
     * outside a capital, while its market hasn't opened, and while it shows a player's own goods.
     */
    private fun target(level: ServerLevel): IntArray? {
        if (own != null) return null
        if (!Markets.enabled()) return full()
        val centre = capital(level) ?: return null
        val entry = Markets.get(level)[centre] ?: return null
        val cap = WaresLayout.capacity(look, wares.goods)
        return IntArray(cap.size / 2) { i ->
            val good = Good.byId(wares.goods[i]) ?: return@IntArray cap[i * 2]
            val exact = WaresLayout.count(cap[i * 2], cap[i * 2 + 1], if (wares.reserve) Reserve.full(entry.market, entry.town, good) else entry.market.stock(good) / entry.town.target(good))
            val now = counts.getOrElse(i) { -1 }
            if (now >= 0 && abs(exact - now) < HOLD) now else exact.roundToInt().coerceIn(0, cap[i * 2 + 1])
        }
    }

    /** The centre of its capital, looked for now and then for one put down by hand; null if it stands in none. */
    private fun capital(level: ServerLevel): BlockPos? {
        capital?.let { return it }
        if (level.gameTime - sought < SEEK) return null
        sought = level.gameTime
        val found = Furnish.capitalAt(level, blockPos)?.center ?: return null
        capital = found
        setChanged()
        return found
    }

    /** Taken away, broken or built over: its spot is never filled again (see [WaresPlacer]). */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        super.preRemoveSideEffects(pos, state)
        (level as? ServerLevel)?.let { WaresPlacer.done(it, pos) }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("wares", wares.name)
        capital?.let { output.putLong("capital", it.asLong()) }
        own?.let { output.putString("own", it.joinToString(",")) }
        output.putIntArray("counts", counts)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val before = goods
        val name = input.getStringOr("wares", "")
        wares = Wares.entries.firstOrNull { it.name == name && it.look == look } ?: Wares.of(look)
        capital = input.getLong("capital").map(BlockPos::of).orElse(null)
        own = input.getString("own").map { if (it.isEmpty()) listOf() else it.split(',') }.orElse(null)
        val lanes = WaresLayout.capacity(look, goods).size / 2
        val next = input.getIntArray("counts").orElse(null)?.takeIf { it.size == lanes } ?: full()
        if (level?.isClientSide() == true) moved(before, next)
        counts = next
        // The slate comes only with what's sent to clients (see getUpdateTag)
        val nextSlate = input.getLong("slate").orElse(0L)
        val level = level
        if (level != null && level.isClientSide() && slate != 0L && nextSlate == 0L) slateGone = level.gameTime
        slate = nextSlate
        slateWord = input.getInt("slateWord").orElse(SLATE_NOTHING)
        slateName = input.getString("slateName").orElse("")
    }

    /** Client side: sets moving the items new counts bring or take away, unless these are its first (or its goods changed). */
    private fun moved(before: List<String>, next: IntArray) {
        val level = level ?: return
        if (!primed || before != goods || next.size != counts.size) {
            primed = true
            motion = arrayOfNulls(next.size)
            return
        }
        val now = level.gameTime
        motion = Array(next.size) { i ->
            val old = motion.getOrNull(i)
            when {
                // Any still on the move from the last change are taken as there (or gone) now
                next[i] != counts[i] -> Motion(counts[i], next[i], now)
                old != null && now - old.since < LONGEST -> old
                else -> null
            }
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries).also { tag ->
        if (slate != 0L) {
            tag.putLong("slate", slate)
            if (slateWord != SLATE_NOTHING) tag.putInt("slateWord", slateWord)
            if (slateName.isNotEmpty()) tag.putString("slateName", slateName)
        }
    }

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** Ticks between looks at the market. */
        const val CHECK = 20L

        /** How near (blocks) a player must be for a display to keep up with its market: well beyond where its goods are drawn. */
        const val NEAR = 48.0

        /** How far a good's exact count may stray from the one shown before the heap changes. */
        private const val HOLD = 0.75

        /** Ticks between looks for its capital, for a display put down by hand that hasn't found one. */
        private const val SEEK = 200L

        /** The longest any items take to settle in or lift out (ticks; see client/economy/WaresRenderer). */
        const val LONGEST = 60L

        /** The words of the slate (Tales T1a): the streets' "Nothing today", the note's seal, and the lender's lease. */
        const val SLATE_NOTHING = 0
        const val SLATE_SEALED = 1
        const val SLATE_LEASE = 2
    }
}
