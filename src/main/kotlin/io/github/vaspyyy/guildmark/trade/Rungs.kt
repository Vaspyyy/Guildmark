package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.SupplierLink
import io.github.vaspyyy.guildmark.progression.Milestones
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * The ladder's gates, and what its rungs give (living-world/trader/design.md, Part 5; build part F1). The rule itself, with nothing of
 * the game in it, is [Ladder]; this gathers the facts it reads from the world (the player's [TradeBooks.Book], their supplier rank at each
 * open capital, their tables and pitches) and answers the other parts' questions: a part asks, it never works out a rung for itself.
 *
 * - **Reading never writes.** Every question reads the books with [peek], which does not make `trade_books.dat` if nobody has written
 *   to it: a look at a player who has never traded leaves no trace. Only a part that writes (a delivery counted, a Warrant bought) makes them.
 * - **A rung only goes up in play.** [check] raises a rung when its asks are met, and nothing in the game lowers one; an operator's
 *   `trade rung` command sets any, and then the periodic look leaves that player be until something real happens (a login, a delivery,
 *   a Warrant, a Store or a House), so that the gates of a lower rung can be tried.
 * - **`tradeRungs` off is "every gate passes".** [reached], [heldLimit], [may] and [guardLimit] answer as for the top rung, nothing is
 *   counted and no rung rises; what the book holds is left as it is, and [of] still tells it.
 */
object Rungs {
    /** Ticks between looks at everyone online, for standing that crosses a line at a stall or an order (a delivery looks for itself). */
    private const val LOOK = 200

    /** Players whose rung an operator has just set, until something real is looked at for them (see the class's notes). */
    private val pinned = HashSet<UUID>()

    // ---- What the other parts ask ----

    /** The rung [player] holds: what their book has, by its id (a Pedlar if it has none). With the ladder off it still tells the truth. */
    fun of(player: ServerPlayer): Rung = book(player)?.let { Rung.byId(it.rung) } ?: Rung.PEDLAR

    /** Whether [player] has climbed to [rung] (or higher); always, with the ladder off. */
    fun reached(player: ServerPlayer, rung: Rung): Boolean = !TradeConfig.rungs() || of(player) >= rung

    /** How many tickets [player] may hold booked at once (the top rung's, with the ladder off). */
    fun heldLimit(player: ServerPlayer): Int = Ladder.heldLimit(held(player))

    /** Whether [player] may take a ticket of this [kind]: a parcel anyone, the rest from a Chapman. */
    fun may(player: ServerPlayer, kind: HaulKind): Boolean = kind == HaulKind.PARCEL || Ladder.bigTickets(held(player))

    /** How many guards [player] may have hired at once (the top rung's, with the ladder off). */
    fun guardLimit(player: ServerPlayer): Int = Ladder.guardLimit(held(player))

    /** The size of [perk] for [player], as [Perk] says; 0.0 for none. The tree's bonuses, kept within what the design allows. */
    fun perk(player: ServerPlayer, perk: Perk): Double = 0.0

    /** Whether [player] has any of [perk]. */
    fun has(player: ServerPlayer, perk: Perk): Boolean = perk(player, perk) > 0.0

    /**
     * What the Standing page shows [player]: the rung they hold and what the next asks, each with how far along they are. A look: it does
     * not raise a rung or write a book. Empty asks at the top.
     */
    fun view(player: ServerPlayer): RungView {
        val facts = facts(player.level(), player.uuid, book(player))
        val next = Rung.entries.getOrNull(facts.rung.ordinal + 1) ?: return RungView(facts.rung, listOf())
        return RungView(facts.rung, Ladder.asks(facts, next))
    }

    /**
     * Looks over [player]'s record and raises their rung if they have earned it: at login, at each delivery, and from the parts that give
     * one the means (the Warrant bought, a Store or a House opened). It is what an operator's setting of a rung waits for. Does nothing with
     * the trade or the ladder off, and nothing for a player nothing has been written for (they have earned nothing).
     */
    fun check(level: ServerLevel, player: ServerPlayer) {
        pinned.remove(player.uuid)
        settle(level, player)
    }

    // ---- For the commands ----

    /** Marks [player]'s rung as set by an operator, so the periodic look leaves it until [check] is asked for. */
    internal fun pin(player: ServerPlayer) {
        pinned.add(player.uuid)
    }

    /** The books if any have been written, without making them: a look must never leave an empty `trade_books.dat`. */
    internal fun peek(level: ServerLevel): TradeBooks? = level.server.overworld().dataStorage.get(TradeBooks.TYPE)

    /** [player]'s book, if one has been written for them. */
    internal fun book(player: ServerPlayer): TradeBooks.Book? = peek(player.level())?.get(player.uuid)

    /**
     * What the rule knows of [player] now: their [book] (null if none; the defaults), their supplier rank at each open capital and the
     * capitals where they have a table or a pitch. With the markets switched off there are no capitals to ask, and none is asked (the
     * markets' own records are not made by a look).
     */
    internal fun facts(level: ServerLevel, player: UUID, book: TradeBooks.Book?): Ladder.Facts {
        val ranks = LinkedHashMap<Long, Int>()
        val stalls = HashSet<Long>()
        val stallsOn = OwnStalls.enabled()
        if (Markets.enabled()) {
            val halls = if (stallsOn) OwnStalls.get(level) else null
            for (entry in Markets.get(level).all()) {
                val key = entry.market.capital
                ranks[key] = SupplierLink.rank(level, player, entry.capital)
                val hall = halls?.find(key)
                if (hall != null && (hall.of(player) >= 0 || hall.pitches.containsKey(player))) stalls.add(key)
            }
        }
        return Ladder.Facts(
            rung = book?.let { Rung.byId(it.rung) } ?: Rung.PEDLAR,
            deliveries = book?.deliveries ?: 0,
            warrant = book?.warrant ?: false,
            ranks = ranks,
            stores = book?.stores?.toSet() ?: setOf(),
            stalls = stalls,
            houses = book?.houses?.toSet() ?: setOf(),
            stallsOn = stallsOn,
        )
    }

    // ---- Setting it going ----

    /** Hooks the ladder into the game (once, at setup): the deliveries counted, a look at login and now and then, and the Warrant. */
    fun register() {
        Hauls.listen(Counting)
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let { check(it.level(), it) } }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> pinned.clear() }
    }

    /** The rung the gates ask about: the top one with the ladder off, else the one held. */
    private fun held(player: ServerPlayer): Rung = if (TradeConfig.rungs()) of(player) else Rung.HOUSE

    /** Every few seconds, a look at each player online, so a rung ties when standing crosses its line at a stall. */
    private fun tick(server: MinecraftServer) {
        if (server.tickCount % LOOK != 0 || !TradeConfig.life() || !TradeConfig.rungs()) return
        for (player in server.playerList.players) if (player.uuid !in pinned) settle(player.level(), player)
    }

    private fun settle(level: ServerLevel, player: ServerPlayer) {
        if (!TradeConfig.life() || !TradeConfig.rungs()) return
        val books = peek(level) ?: return
        val book = books[player.uuid] ?: return
        val was = Rung.byId(book.rung)
        val now = Ladder.rung(facts(level, player.uuid, book))
        if (now <= was) return
        book.rung = now.id
        books.changed()
        risen(player, was, now)
    }

    /** [player] has risen from [was] to [now]: they are told, and each title the rungs passed on the way is offered. */
    private fun risen(player: ServerPlayer, was: Rung, now: Rung) {
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.rung.${now.id}").withStyle(ChatFormatting.GOLD))
        for (rung in Rung.entries) {
            if (rung <= was || rung > now) continue
            when (rung) {
                Rung.CHAPMAN -> Milestones.award(player, Milestones.CHAPMAN)
                Rung.FACTOR -> Milestones.award(player, Milestones.FACTOR)
                Rung.HOUSE -> Milestones.award(player, Milestones.TRADING_HOUSE)
                else -> {}
            }
        }
    }

    /** Counts the tickets a player fills, for the ladder's deliveries, and looks at their rung. */
    private object Counting : Hauls.Listener {
        override fun delivered(level: ServerLevel, haul: Haul, delivery: Delivery) {
            if (!TradeConfig.life() || !TradeConfig.rungs()) return
            // A House's carters fill tickets for their owner, who is long past counting
            val taker = delivery.taker as? Taker.Player ?: return
            val books = TradeBooks.get(level)
            val book = books.of(taker.owner)
            book.deliveries = (book.deliveries + weight(delivery.kind)).coerceAtMost(MOST_DELIVERIES)
            books.changed()
            level.server.playerList.getPlayer(taker.owner)?.let { check(level, it) }
        }

        /** What a ticket of this kind counts for: a parcel 1, a consignment (and a feast, which is posted as one) 2, a relief 3. */
        private fun weight(kind: HaulKind): Int = when (kind) {
            HaulKind.RELIEF -> 3
            HaulKind.CONSIGNMENT, HaulKind.FEAST -> 2
            else -> 1
        }
    }

    /** A count that nothing reaches, kept so that it cannot run round. */
    private const val MOST_DELIVERIES = 1_000_000
}
