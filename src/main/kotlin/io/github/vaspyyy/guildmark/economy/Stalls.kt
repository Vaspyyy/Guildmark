package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.MarketClosePayload
import io.github.vaspyyy.guildmark.network.MarketGood
import io.github.vaspyyy.guildmark.network.MarketHaulPayload
import io.github.vaspyyy.guildmark.network.MarketOrderPayload
import io.github.vaspyyy.guildmark.network.MarketRefreshPayload
import io.github.vaspyyy.guildmark.network.MarketTradePayload
import io.github.vaspyyy.guildmark.network.MarketUpdatePayload
import io.github.vaspyyy.guildmark.network.MarketView
import io.github.vaspyyy.guildmark.network.OpenMarketPayload
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.talk.Street
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.trade.HaulKind
import io.github.vaspyyy.guildmark.trade.Hauls
import io.github.vaspyyy.guildmark.trade.Taker
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.floor

/**
 * Trading face to face at a capital's market. Using a townsperson who keeps one of its shops (see [Shop.kept])
 * while they're at their work, or standing at their stall, opens the market screen for its goods and its orders (see
 * [Orders]); out of hours they say they're shut instead. The inn's keeper sells nothing over a counter: at their work,
 * with an order of the inn's for the player to see, they open a screen of just its orders, and otherwise talk as
 * anyone does. While the screen is open the keeper serves the player (see [Serving]).
 *
 * Whatever the screen asks, the server checks everything a deal needs: the player within [REACH] of the keeper, the
 * keeper alive and still keeping that shop, the markets switched on, and the good one the shop deals in. Deals go
 * through [Trading] and orders through [Orders], so the prices and the pay are the market's own, and after each one
 * every screen open on that market gets fresh quotes. `/guildmark market screen <shop>` opens a shop's screen with
 * nobody keeping it, for testing.
 */
object Stalls {
    /** How near its keeper a player has to stand to trade; the screen closes a little further off. */
    const val REACH = 6.0
    private const val SLACK = 1.5

    /** Lots a shift-click deals at once. */
    const val MANY = 5

    /** How near their post, or the oven or anvil they work at (squared), a keeper stands to be at their stall. */
    private const val AT_STALL = 6.25

    /** Ticks a screen may go without a word from its player before it's taken for gone. */
    private const val TIMEOUT = 200L

    /** Fewest ticks between two refreshes for one player. */
    private const val REFRESH = 10L

    /** Ticks an open screen may sit with no deal after its keeper's hours before they pack up. */
    private const val CLOSING = 600L

    /** Ticks between looks at whether every open trade can go on. */
    private const val SWEEP = 10

    /** How long a keeper remembers a customer, to greet them as one. */
    private const val REMEMBER = 24000L

    /**
     * A player's open screen: the [shop] at the capital whose centre is [capital] (called [place]), kept by [keeper]
     * (null for the testing screen).
     */
    private class Session(val player: UUID, val shop: Shop, val capital: BlockPos, val place: String, val keeper: Townsperson?, now: Long) {
        /** Game time of the last word from the screen, of the last deal or the opening, of the last batch, and of the last refresh. */
        var heard = now
        var active = now
        var dealt = -1L
        var refreshed = -1L

        /** Lots bought and sold this time. */
        var lots = 0

        /** The goods whose prices the keeper has explained this time, and those they've said came off the market's own stalls. */
        val told = HashSet<Good>()
        val own = HashSet<Good>()

        /** The goods they've remarked came off another capital's waylaid wagon (see [Marked]). */
        val marked = HashSet<Good>()
    }

    private val sessions = HashMap<UUID, Session>()

    /** When each keeper last served each player: (keeper, player) to game time. */
    private val served = HashMap<Pair<UUID, UUID>, Long>()

    /** Registers the screen's payloads, the checks on open trades, and the clearing up when a player leaves. */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(OpenMarketPayload.TYPE, OpenMarketPayload.STREAM_CODEC, OpenMarketPayload::handle)
                .playToClient(MarketUpdatePayload.TYPE, MarketUpdatePayload.STREAM_CODEC, MarketUpdatePayload::handle)
                .playToServer(MarketTradePayload.TYPE, MarketTradePayload.STREAM_CODEC, MarketTradePayload::handle)
                .playToServer(MarketRefreshPayload.TYPE, MarketRefreshPayload.STREAM_CODEC, MarketRefreshPayload::handle)
                .playToServer(MarketClosePayload.TYPE, MarketClosePayload.STREAM_CODEC, MarketClosePayload::handle)
        }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { sweep(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::close) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            sessions.clear()
            served.clear()
        }
    }

    // ---- Stepping up ----

    /**
     * [player] used [p]: if they keep a shop and they're at their work (or standing at their stall), its screen
     * opens; out of hours they say they're shut. False if they keep no shop, so they talk as usual.
     */
    fun interact(level: ServerLevel, player: ServerPlayer, p: Townsperson): Boolean {
        if (p.role != TownRole.MERCHANT && p.role != TownRole.BAKER && p.role != TownRole.SMITH && p.role != TownRole.INNKEEPER) return false
        if (!Markets.enabled() || player.isSpectator) return false
        // Something else has charge of them (a job, a festival): theirs to answer for
        val serving = p.director
        if (serving != null && serving !is Serving) return false
        val life = Days.life(p) ?: return false
        val shop = Shop.kept(p) ?: return false
        val doing = Days.stretch(life, level.overworldClockTime).doing
        // The inn's keeper is only a business to those it has an order for, and only while they're at their work
        if (shop == Shop.INN) {
            if (serving == null && (p.isSleeping || doing != Doing.WORK)) return false
            if (!innOrders(level, player, life.places.centre)) return false
        }
        if (serving == null && (p.isSleeping || doing != Doing.WORK && !atStall(p))) {
            val shut = StallTalk.shut(p, life, doing, shop, level)
            // Awake, they'll talk all the same, saying they're shut first
            if (!p.isSleeping && Talk.open(level, player, p, shut, false)) return true
            if (!p.isSleeping) p.attend(player, 60)
            p.say(shut, force = true)
            return true
        }
        val capital = (life.places as? CityPlaces)?.capital ?: return false
        val entry = Markets.of(level, capital) ?: return false
        open(player, shop, entry, capital, p)
        return true
    }

    /** Whether the inn at [capital] has an order [player] could see: one to be had, or one of theirs; or a haul ticket of the same kind. */
    private fun innOrders(level: ServerLevel, player: ServerPlayer, capital: BlockPos): Boolean {
        if (Hauls.waiting(level, player.uuid, capital, Shop.INN)) return true
        if (!Suppliers.enabled()) return false
        val book = Orders.get(level)[capital] ?: return false
        return book.orders.any { it.shop == Shop.INN && (it.state == OrderState.OPEN || it.taker == player.uuid) }
    }

    /** Whether [p] stands at their stall: by their post, or by the oven or anvil they work at. */
    private fun atStall(p: Townsperson): Boolean {
        val spot = p.post ?: p.work ?: return false
        return p.blockPosition().distSqr(spot) <= AT_STALL
    }

    /** Opens [shop]'s screen for [player] at [capital]'s market ([entry]), kept by [keeper] (null: the testing screen, which nobody keeps). */
    fun open(player: ServerPlayer, shop: Shop, entry: Markets.Entry, capital: CapitalPiece, keeper: Townsperson?) {
        val now = player.level().gameTime
        val old = sessions[player.uuid]
        if (old != null && old.keeper === keeper && old.shop == shop) {
            // The same click twice: the screen is open already (or about to be)
            old.heard = now
            PacketDistributor.sendToPlayer(player, OpenMarketPayload(view(old, entry, player)))
            return
        }
        if (old != null) end(player.uuid, old, walked = false)
        val s = Session(player.uuid, shop, capital.center, VillageNames.of(capital.boardAnchor), keeper, now)
        sessions[player.uuid] = s
        if (keeper != null) {
            val serving = keeper.director as? Serving ?: Serving().also { take(keeper, it) }
            val key = keeper.uuid to player.uuid
            val again = served[key]?.let { now - it < REMEMBER } == true
            served[key] = now
            if (served.size > 512) served.entries.removeIf { now - it.value >= REMEMBER }
            val level = player.level()
            // Holding out salvage off the capital's own waylaid wagon: the keeper takes it all back first, and pays for it
            val holding = Marked.home(player.mainHandItem, s.capital) || Marked.home(player.offhandItem, s.capital)
            val returned = if (holding) Marked.back(level, player, keeper, entry) else null
            if (returned != null) {
                serving.arrive(keeper, player, shop, again, null, quiet = true)
                serving.delivered(keeper, returned.dearest, StallTalk.SALVAGE_BACK, returned.pay)
                risen(level, s.capital, player.uuid)?.let { serving.risen(keeper, it) }
                Witness.opened(level, keeper, player)
                PacketDistributor.sendToPlayer(player, OpenMarketPayload(view(s, entry, player)))
                return
            }
            val news = StallTalk.news(shop.goods.filter { it.traded }.map { Trading.quote(entry, it) }, s.told)
            news?.let { s.told.add(it.good) }
            // A rank they've risen to comes first, then an order they let lapse here; an order to be had comes up after
            val rank = risen(level, s.capital, player.uuid)
            val scold = rank == null && Orders.scold(level, s.capital, player.uuid, shop)
            // Or a booking on a haul ticket they let lapse here
            val lapsed = rank == null && !scold && Hauls.scold(level, s.capital, player.uuid, shop)
            val word = when {
                Orders.offered(level, player.uuid, s.capital, shop) -> StallTalk.STANDING
                Orders.toBeHad(level, player.uuid, s.capital, shop) -> StallTalk.offer(keeper)
                else -> null
            }
            // Cool on a player who kept what they took off the capital's wagon, for a week (see Marked.lapse)
            val cool = rank == null && !scold && !lapsed && Marked.cool(level, s.capital, player.uuid)
            serving.arrive(keeper, player, shop, again, word ?: news?.let(StallTalk::price), rank, scold || lapsed, cool, haul = lapsed)
            Witness.opened(level, keeper, player)
        }
        PacketDistributor.sendToPlayer(player, OpenMarketPayload(view(s, entry, player)))
    }

    /** The rank [player] has risen to at [capital] that no keeper there has greeted yet, if they have: greeted now. */
    private fun risen(level: ServerLevel, capital: BlockPos, player: UUID): SupplierRank? {
        if (!Suppliers.enabled()) return null
        val data = Suppliers.get(level)
        val standing = data[capital, player] ?: return null
        val rank = standing.rank
        if (rank.ordinal <= standing.greeted) return null
        standing.greeted = rank.ordinal
        data.changed()
        return rank
    }

    /** [p] stops what they were at to serve: [serving] has charge of them until the last customer goes. */
    private fun take(p: Townsperson, serving: Serving) {
        // Whatever they were at ends here, as when they're called away from it (what they held is put down)
        p.routine.interrupt()
        p.director = serving
        p.navigation.stop()
        if (p.seat != null) p.standUp()
        p.act(Activity.NONE)
    }

    /** [p] goes back to their day, whatever they were at starting afresh. */
    fun release(p: Townsperson) {
        if (p.director !is Serving) return
        p.director = null
        p.carried = ItemStack.EMPTY
        p.act(Activity.NONE)
        p.attending?.let { p.stopListening(it) }
        p.routine.interrupt()
    }

    // ---- Dealing ----

    /** [player] asks to buy ([buying]) or sell [lots] lots of the good [id] at the shop whose screen they have open. */
    fun trade(player: ServerPlayer, id: String, buying: Boolean, lots: Int) {
        val s = sessions[player.uuid] ?: return
        val now = player.level().gameTime
        s.heard = now
        val entry = check(player, s, REACH + SLACK) ?: return
        // A step too far to deal, though not so far that the trade's over
        val keeper = s.keeper
        if (keeper != null && player.distanceToSqr(keeper) > REACH * REACH) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.market.closer"))
            return send(player, s, entry)
        }
        val good = Good.byId(id)?.takeIf { it.traded && it in s.shop.goods }
        // One batch a tick however fast the clicks come; an ask that makes no sense gets the screen put right
        if (good == null || s.dealt == now) return send(player, s, entry)
        s.dealt = now
        s.active = now
        var done = 0
        var own = 0
        var dealer = 0
        var stop: Deal? = null
        // Selling what came off another capital's waylaid wagon is trade, but the keeper remarks on the mark, once
        val marked = !buying && Marked.foreign(player, s.capital, good)
        for (i in 0 until lots.coerceIn(1, MANY)) {
            val deal = if (buying) Trading.buy(player, entry, good) else Trading.sell(player, entry, good)
            if (deal.outcome != Deal.Outcome.DONE) {
                stop = deal
                break
            }
            done++
            own += deal.own
            if (deal.hauled) dealer += deal.lot?.count ?: 0
        }
        s.lots += done
        val level = player.level()
        // Goods that came off its own stalls lately sell, but earn no standing: the keeper says so the first time
        if (own > 0) player.sendOverlayMessage(Component.translatable("gui.guildmark.order.own_sold", own, Component.translatable(good.key + ".many")))
        // Goods that came a long way sold as a dealer's lot (see Trading.dealerLot)
        if (dealer > 0) player.sendOverlayMessage(Component.translatable("gui.guildmark.trade.dealer_lot.sold", dealer, Component.translatable(good.key + ".many")))
        // Salvage off this capital's own wagon: not for sale here, but to be handed in
        if (done == 0 && stop?.outcome == Deal.Outcome.OWN_WAGON) player.sendOverlayMessage(Component.translatable("gui.guildmark.market.own_wagon", s.place))
        val serving = keeper?.director as? Serving
        if (keeper != null && serving != null) {
            val remark = marked && done > 0 && s.marked.add(good)
            serving.dealt(keeper, player, good, buying, done, stop, Trading.quote(entry, good, Suppliers.share(level, s.capital, player.uuid)), s.told, own > 0 && s.own.add(good), remark)
            risen(level, s.capital, player.uuid)?.let { serving.risen(keeper, it) }
        }
        // Who bought what, and how much, is the town's to talk of
        if (keeper != null && done > 0) Witness.dealt(level, keeper, player, entry, good, buying, done)
        everyone(player, s, entry)
    }

    // ---- Orders ----

    /** [player] asks to do [action] (see [MarketOrderPayload]) with the order [id] at the business whose screen they have open. */
    fun order(player: ServerPlayer, id: Int, action: Int) {
        val s = sessions[player.uuid] ?: return
        val level = player.level()
        val now = level.gameTime
        s.heard = now
        val entry = check(player, s, REACH + SLACK) ?: return
        val keeper = s.keeper
        if (keeper != null && player.distanceToSqr(keeper) > REACH * REACH) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.market.closer"))
            return send(player, s, entry)
        }
        // One a tick however fast the clicks come
        if (s.dealt == now || !Suppliers.enabled()) return send(player, s, entry)
        s.dealt = now
        s.active = now
        val serving = keeper?.director as? Serving
        val book = Orders.get(level)[s.capital]
        val o = book?.orders?.firstOrNull { it.id == id && it.shop == s.shop }
        if (book == null || o == null) {
            refuse(player, keeper, serving, "gone")
            return everyone(player, s, entry)
        }
        val mine = o.held && o.taker == player.uuid
        when (action) {
            MarketOrderPayload.TAKE -> {
                val why = Orders.take(level, player, book, entry, o)
                if (why != null) refuse(player, keeper, serving, why) else {
                    s.lots++
                    player.sendSystemMessage(
                        Component.translatable("supplier.guildmark.taken", Orders.shopName(o.shop), Orders.what(o), book.name, Orders.marks(floor(o.each * o.amount).toInt()), Orders.due(o, Markets.hourOf(level)))
                            .withStyle(ChatFormatting.YELLOW),
                    )
                    if (keeper != null) serving?.took(keeper, StallTalk.taken(keeper))
                }
            }
            MarketOrderPayload.DELIVER -> if (mine) deliver(player, s, entry, book, o, keeper, serving) else refuse(player, keeper, serving, "not_yours")
            MarketOrderPayload.GIVE_UP -> if (mine) {
                Orders.giveUp(level, player, book, o)
                if (keeper != null) serving?.gesture(keeper, Activity.SHRUG, StallTalk.GIVEN_UP)
            } else {
                refuse(player, keeper, serving, "not_yours")
            }
            MarketOrderPayload.ACCEPT -> {
                val why = Orders.accept(level, player, book, entry, o)
                if (why != null) refuse(player, keeper, serving, why) else {
                    s.lots++
                    player.sendSystemMessage(
                        Component.translatable("supplier.guildmark.standing.accepted", Orders.shopName(o.shop), Orders.what(o), book.name, Orders.WEEKS, Orders.marks(floor(o.each * o.amount).toInt()))
                            .withStyle(ChatFormatting.YELLOW),
                    )
                    if (keeper != null) serving?.took(keeper, StallTalk.taken(keeper))
                }
            }
            MarketOrderPayload.DECLINE -> if (o.state == OrderState.OFFERED && o.taker == player.uuid) {
                Orders.decline(level, player, book, o)
                if (keeper != null) serving?.gesture(keeper, Activity.NOD, StallTalk.DECLINED)
            } else {
                refuse(player, keeper, serving, "gone")
            }
        }
        everyone(player, s, entry)
    }

    /** [player] delivers what they carry of their order [o]: the keeper takes it across the counter and counts out the pay. */
    private fun deliver(player: ServerPlayer, s: Session, entry: Markets.Entry, book: Book, o: Order, keeper: Townsperson?, serving: Serving?) {
        val level = player.level()
        val good = o.good
        val name = Component.translatable(good.key + ".many")
        val d = Orders.deliver(level, player, book, entry, o)
        if (d.count == 0) {
            if (d.own > 0) {
                player.sendOverlayMessage(Component.translatable("gui.guildmark.order.refused.own", d.own, name))
                if (keeper != null) serving?.gesture(keeper, Activity.SHRUG, StallTalk.BOUGHT_HERE)
            } else {
                refuse(player, keeper, serving, "none")
            }
            return
        }
        s.lots++
        val key = when {
            !d.done -> "supplier.guildmark.delivered"
            o.state == OrderState.STANDING -> "supplier.guildmark.delivered.week"
            else -> "supplier.guildmark.delivered.all"
        }
        player.sendSystemMessage(Component.translatable(key, d.count, name, Orders.shopName(o.shop), Orders.marks(d.pay), o.left).withStyle(ChatFormatting.YELLOW))
        if (d.own > 0) player.sendSystemMessage(Component.translatable("supplier.guildmark.kept_back", d.own, name).withStyle(ChatFormatting.GRAY))
        if (d.offered) {
            player.sendSystemMessage(Component.translatable("supplier.guildmark.standing.offered", Orders.shopName(o.shop), Orders.what(o), book.name, Orders.WEEKS).withStyle(ChatFormatting.GOLD))
        }
        if (keeper == null || serving == null) return
        serving.delivered(keeper, good, if (d.done) StallTalk.thanks(keeper) else StallTalk.part(keeper), d.pay)
        risen(level, s.capital, player.uuid)?.let { serving.risen(keeper, it) }
        if (d.offered) serving.after(keeper, StallTalk.STANDING)
    }

    // ---- Haul tickets ----

    /**
     * [player] asks to do [action] (see [MarketHaulPayload]) with the haul ticket [id] at the shop whose screen they have open: to book what is still unbooked of
     * it (a deposit held), to hand over what they carry of its good that came a long way, or to give their booking up (the deposit is lost). The keeper takes
     * the goods as for an order, and the counting is knocked out on a tally stick.
     */
    fun haul(player: ServerPlayer, id: Int, action: Int) {
        val s = sessions[player.uuid] ?: return
        val level = player.level()
        val now = level.gameTime
        s.heard = now
        val entry = check(player, s, REACH + SLACK) ?: return
        val keeper = s.keeper
        if (keeper != null && player.distanceToSqr(keeper) > REACH * REACH) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.market.closer"))
            return send(player, s, entry)
        }
        // One a tick however fast the clicks come
        if (s.dealt == now || !TradeConfig.hauls()) return send(player, s, entry)
        s.dealt = now
        s.active = now
        val serving = keeper?.director as? Serving
        val h = Hauls.find(level, id)?.takeIf { it.capital == s.capital && it.shop == s.shop }
        if (h == null) {
            turnedAway(player, keeper, serving, "gone")
            return everyone(player, s, entry)
        }
        val name = Component.translatable(h.good.key + ".many")
        when (action) {
            MarketHaulPayload.TAKE -> {
                val why = Hauls.book(level, id, Taker.Player(player.uuid))
                val row = if (why == null) Hauls.view(level, player, s.capital, s.shop).firstOrNull { it.id == id } else null
                if (row == null) turnedAway(player, keeper, serving, why ?: "gone") else {
                    s.lots++
                    player.sendSystemMessage(
                        Component.translatable(
                            "message.guildmark.trade.haul.booked", row.mine, name, Orders.shopName(h.shop), s.place, Orders.marks(row.pay), Orders.marks(row.deposit), Hauls.due(row.hours),
                        ).withStyle(ChatFormatting.YELLOW),
                    )
                    if (keeper != null) serving?.booked(keeper, StallTalk.booked(keeper))
                }
            }
            MarketHaulPayload.DELIVER -> {
                val why = Hauls.cannotDeliver(level, player, id)
                val d = if (why == null) Hauls.deliver(level, player, id) else null
                if (d == null) turnedAway(player, keeper, serving, why ?: "none") else {
                    s.lots++
                    val filled = d.left <= 0
                    player.sendSystemMessage(
                        Component.translatable(
                            if (filled) "message.guildmark.trade.haul.delivered.all" else "message.guildmark.trade.haul.delivered",
                            d.units, name, Orders.shopName(d.shop), Orders.marks(d.pay), d.left,
                        ).withStyle(ChatFormatting.YELLOW),
                    )
                    if (d.refund > 0) player.sendSystemMessage(Component.translatable("message.guildmark.trade.haul.deposit_back", Orders.marks(d.refund)).withStyle(ChatFormatting.GRAY))
                    if (d.pay > 0 && Suppliers.enabled()) player.sendSystemMessage(Component.translatable("message.guildmark.trade.haul.standing", d.pay, s.place).withStyle(ChatFormatting.GRAY))
                    if (keeper != null && serving != null) {
                        val relief = d.kind == HaulKind.RELIEF
                        serving.hauled(keeper, d.good, if (filled) StallTalk.haulFilled(keeper, relief) else StallTalk.haulPart(keeper), d.pay, d.units, filled, relief)
                        risen(level, s.capital, player.uuid)?.let { serving.risen(keeper, it) }
                    }
                }
            }
            MarketHaulPayload.GIVE_UP -> {
                val booking = Hauls.bookedOn(level, player.uuid, id)
                val left = booking?.left ?: 0
                val lost = booking?.let { (it.deposit - it.repaid).coerceAtLeast(0) } ?: 0
                if (booking == null || Hauls.release(level, player.uuid, id) < 0) turnedAway(player, keeper, serving, "not_yours") else {
                    player.sendSystemMessage(
                        Component.translatable(
                            if (lost > 0) "message.guildmark.trade.haul.given_up" else "message.guildmark.trade.haul.given_up.free",
                            left, name, Orders.shopName(h.shop), s.place, Orders.marks(lost),
                        ).withStyle(ChatFormatting.GRAY),
                    )
                    if (keeper != null) serving?.gesture(keeper, Activity.SHRUG, StallTalk.GIVEN_UP)
                }
            }
        }
        everyone(player, s, entry)
    }

    /** [player] can't do what they asked with a haul ticket: told [why] (a key under gui.guildmark.trade.haul.refused.), with a shrug from the keeper. */
    private fun turnedAway(player: ServerPlayer, keeper: Townsperson?, serving: Serving?, why: String) {
        player.sendOverlayMessage(Component.translatable("gui.guildmark.trade.haul.refused.$why"))
        if (keeper != null) serving?.gesture(keeper, Activity.SHRUG, null)
    }

    /** [player] can't do what they asked with an order: told [why] (a key under gui.guildmark.order.refused.), with a shrug from the keeper. */
    private fun refuse(player: ServerPlayer, keeper: Townsperson?, serving: Serving?, why: String) {
        player.sendOverlayMessage(Component.translatable("gui.guildmark.order.refused.$why"))
        if (keeper != null) serving?.gesture(keeper, Activity.SHRUG, null)
    }

    /** Every screen open on [s]'s market sees what changed, [player]'s first. */
    private fun everyone(player: ServerPlayer, s: Session, entry: Markets.Entry) {
        send(player, s, entry)
        for (other in sessions.values) if (other !== s && other.capital == s.capital) {
            player.level().server.playerList.getPlayer(other.player)?.let { send(it, other, entry) }
        }
    }

    /** [player]'s screen asks for fresh quotes, and says it's still open. */
    fun refresh(player: ServerPlayer) {
        val s = sessions[player.uuid] ?: return
        val now = player.level().gameTime
        s.heard = now
        if (now - s.refreshed < REFRESH) return
        s.refreshed = now
        val entry = check(player, s, REACH + SLACK) ?: return
        send(player, s, entry)
    }

    /** [player] closed their screen, or left the game. */
    fun close(player: ServerPlayer) {
        val s = sessions[player.uuid] ?: return
        end(player.uuid, s, walked = false)
    }

    /**
     * [player] asks [p], who's serving them, for a talk instead (the screen's Talk tab): their trade ends here with no
     * farewell, and the keeper is let go to talk. False, with nothing changed, if [p] is serving someone else too.
     */
    fun spare(player: ServerPlayer, p: Townsperson): Boolean {
        val serving = p.director as? Serving ?: return false
        if (!serving.only(player.uuid)) return false
        if (sessions[player.uuid]?.keeper === p) sessions.remove(player.uuid)
        p.director = null
        p.carried = ItemStack.EMPTY
        p.act(Activity.NONE)
        return true
    }

    // ---- Keeping it honest ----

    /**
     * The market [s] trades with, if the trade can go on with [player] within [reach] of the keeper; otherwise
     * their screen is closed (and they're told why) and it's null.
     */
    private fun check(player: ServerPlayer, s: Session, reach: Double): Markets.Entry? {
        val why = trouble(player, s, reach)
        if (why != null) {
            stop(player, s, why)
            return null
        }
        val entry = Markets.get(player.level())[s.capital]
        if (entry == null) stop(player, s, "gone")
        return entry
    }

    /**
     * What stops [s]'s trade going on, if anything: the last part of a key under gui.guildmark.market.closed. that
     * tells the player, or "" to tell them nothing.
     */
    private fun trouble(player: ServerPlayer, s: Session, reach: Double): String? {
        if (!Markets.enabled()) return "off"
        if (!player.isAlive || player.isSpectator) return ""
        val level = player.level()
        val keeper = s.keeper
        if (keeper == null) {
            // The testing screen: an operator, standing in the capital
            if (!level.server.playerList.isOp(player.nameAndId())) return ""
            return if (Capitals.covering(level, player.blockPosition())?.center != s.capital) "left" else null
        }
        if (keeper.isRemoved || !keeper.isAlive || keeper.level() !== level) return "gone"
        if (keeper.director !is Serving) return "called_away"
        if (player.distanceToSqr(keeper) > reach * reach) return "far"
        if (Shop.kept(keeper) != s.shop) return "moved"
        // The inn's screen is nothing but its orders, and its haul tickets
        if (s.shop == Shop.INN && !Suppliers.enabled() && !TradeConfig.hauls()) return "orders_off"
        return null
    }

    /** Ends [s]'s trade from this side: the screen closes and the player is told [why] (see [trouble]). */
    private fun stop(player: ServerPlayer, s: Session, why: String, line: String? = null) {
        end(player.uuid, s, walked = why == "far", line = line)
        PacketDistributor.sendToPlayer(player, MarketUpdatePayload(MarketView(s.shop.id, s.keeper?.id ?: -1, s.place, 0, listOf()), closed = true))
        if (why.isNotEmpty() && why != "far") player.sendOverlayMessage(Component.translatable("gui.guildmark.market.closed.$why"))
    }

    /** [s] is over: its keeper sees the player off, with [line] if there's something else to say. */
    private fun end(player: UUID, s: Session, walked: Boolean, line: String? = null) {
        if (sessions[player] === s) sessions.remove(player)
        val keeper = s.keeper ?: return
        if (keeper.isRemoved || !keeper.isAlive) return
        (keeper.director as? Serving)?.leave(keeper, player, s.lots > 0, walked, line)
    }

    /** Ends every trade that can't go on: the player gone or out of reach, the keeper gone, or their hours over and nothing bought for a while. */
    private fun sweep(server: MinecraftServer) {
        if (sessions.isEmpty() || server.tickCount % SWEEP != 0) return
        for (s in sessions.values.toList()) {
            val player = server.playerList.getPlayer(s.player)
            if (player == null) {
                end(s.player, s, walked = false)
                continue
            }
            val now = player.level().gameTime
            if (now - s.heard > TIMEOUT) {
                stop(player, s, "")
                continue
            }
            val why = trouble(player, s, REACH + SLACK)
            if (why != null) {
                stop(player, s, why)
                continue
            }
            val keeper = s.keeper ?: continue
            if (now - s.active > CLOSING && !working(keeper)) stop(player, s, "", StallTalk.CLOSING)
        }
    }

    /** Whether it's [p]'s hours at their trade. */
    private fun working(p: Townsperson): Boolean {
        val life = Days.life(p) ?: return false
        return Days.stretch(life, p.level().overworldClockTime).doing == Doing.WORK
    }

    // ---- What the screen shows ----

    private fun send(player: ServerPlayer, s: Session, entry: Markets.Entry) {
        PacketDistributor.sendToPlayer(player, MarketUpdatePayload(view(s, entry, player), closed = false))
    }

    /** What [s]'s screen shows [player]: the shop's goods at the share their standing earns them, their standing, and the business's orders. */
    private fun view(s: Session, entry: Markets.Entry, player: ServerPlayer): MarketView {
        val level = player.level()
        val share = Suppliers.share(level, s.capital, player.uuid)
        val goods = s.shop.goods.mapNotNull { g ->
            val item = g.item?.takeIf { g.traded } ?: return@mapNotNull null
            val q = Trading.quote(entry, g, share, Hauls.hauledHere(level, player, entry, g))
            MarketGood(
                g.id, q.stock, floor(entry.town.target(g)).toInt(), q.buy?.count ?: 0, q.buy?.marks ?: 0, q.sell?.count ?: 0,
                q.sell?.marks ?: 0, q.wants, q.trend, (q.price / q.usual).toFloat(), reason(q), q.why?.dear == true, Trading.count(player.inventory, item),
                q.hauledSell?.count ?: 0, q.hauledSell?.marks ?: 0, q.hauledLeft,
            )
        }
        val keeper = s.keeper?.id ?: -1
        val marks = TownControl.marks(player)
        val hauls = Hauls.view(level, player, s.capital, s.shop)
        if (!Suppliers.enabled()) return MarketView(s.shop.id, keeper, s.place, marks, goods, hauls = hauls)
        val points = Suppliers.get(level)[s.capital, player.uuid]?.points ?: 0.0
        val rank = SupplierRank.of(points)
        val orders = Orders.get(level)
        val shown = orders[s.capital]?.let { Orders.view(level, player, it, s.shop) } ?: listOf()
        return MarketView(
            s.shop.id, keeper, s.place, marks, goods, rank.ordinal, floor(points).toInt(), rank.next?.points ?: -1,
            orders.held(player.uuid).size, Suppliers.best(level, player.uuid).orders, shown, hauls,
        )
    }

    /** The lang key of why [q]'s price is well off its usual (taking the good's name, as [MarketCommand.reason] gives it), or "". */
    private fun reason(q: Quote): String = Trading.reasonKey(q)
}


/**
 * A shop's keeper serving the players who have its screen open (see [Stalls]). In place of their day's round they
 * stay where they are and turn to whoever they're dealing with; for each deal they hand the goods over
 * ([Activity.HAND_OVER]) or count out the coin ([Activity.PAY]) to the clink of it, a refusal gets a shrug, and they
 * say what fits (see [StallTalk]). An order's goods they take across the counter a step at a time ([Beat]): received,
 * put down with the rest, the pay counted out and handed over; an order taken gets its slip handed over, and a
 * supplier's rise in standing they mark in their own way. Once the last of them goes they see them off and, a moment
 * later, go back to their day.
 */
class Serving : Director {
    /** One step of something taken across the counter: [activity] with [held] in hand for [ticks], saying [line] as it starts, to [coins] clinks of coin. */
    private class Beat(val activity: Activity, val held: ItemStack, val ticks: Int, val line: String? = null, val coins: Int = 0)

    /** The players with the screen open, in the order they came. */
    private val customers = LinkedHashSet<UUID>()

    /** The one they're dealing with, whom they face. */
    private var facing: UUID? = null

    /** Game time they go back to their day, once everyone's gone; -1 while someone's still there. */
    private var leaving = -1L

    /** A line to say a little later (why a price is where it is, after the greeting), and when. */
    private var later: String? = null
    private var laterAt = 0L

    /** Game time of their last word, so the small talk doesn't come with every deal. */
    private var spoke = Long.MIN_VALUE / 2

    /** Clinks of coin still to sound, and the tick of the next. */
    private var clinks = 0
    private var clinkAt = 0

    /** A trade sound (`trade.knock`...) to play at the keeper when their own tick count reaches [at]: the tally stick's knocks, the crate set down, the ticket peeled off. */
    private class Cue(val at: Int, val sound: String)

    private val cues = ArrayList<Cue>()

    /** The steps still to come, and the tick (their own) that the one under way ends; -1 with none under way. */
    private val beats = ArrayDeque<Beat>()
    private var beatEnds = -1

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val now = level.gameTime
        p.navigation.stop()
        // Busy: no calling out to passers-by while they serve
        p.nextSpeech = now + 100
        val who = (facing ?: customers.lastOrNull())?.let { level.getPlayerByUUID(it) }
        if (who != null) {
            p.lookControl.setLookAt(who, 30f, 30f)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(who.position()), TURN))
            p.attend(who, 40)
        }
        if (beatEnds >= 0) {
            step(p)
        } else {
            // A gesture plays through; then they stand, and talk while they've something to say
            val doing = p.activity
            when {
                doing != Activity.NONE && doing != Activity.TALK && p.actDone -> {
                    p.carried = ItemStack.EMPTY
                    p.act(if (p.speech.isNotEmpty()) Activity.TALK else Activity.NONE)
                }
                doing == Activity.TALK && p.speech.isEmpty() -> p.act(Activity.NONE)
                doing == Activity.NONE && p.speech.isNotEmpty() -> p.act(Activity.TALK)
            }
        }
        if (clinks > 0 && p.tickCount >= clinkAt) {
            clinks--
            clinkAt = p.tickCount + 5
            level.playSound(null, p.x, p.y + 1.0, p.z, COIN, SoundSource.NEUTRAL, 0.5f, 0.9f + p.random.nextFloat() * 0.25f)
        }
        if (cues.isNotEmpty()) {
            val waiting = cues.iterator()
            while (waiting.hasNext()) {
                val cue = waiting.next()
                if (p.tickCount < cue.at) continue
                waiting.remove()
                level.playSound(null, p.x, p.y + 1.0, p.z, TradeSounds.get(cue.sound), SoundSource.NEUTRAL, 0.7f, 0.95f + p.random.nextFloat() * 0.1f)
            }
        }
        val line = later
        if (line != null && now >= laterAt) {
            later = null
            say(p, line)
        }
        // Whatever they're still handing over is finished first
        if (leaving in 0..now && beatEnds < 0) Stalls.release(p)
    }

    // A player crouching behind a busy keeper may try their purse; anyone else steps up to the shop
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean =
        !player.isShiftKeyDown && Stalls.interact(p.level() as ServerLevel, player, p)

    /**
     * [player] has stepped up to [shop]: a greeting, then, a little later, [news] (an order to be had, or why a price is
     * where it is). A [rank] they've risen to there is greeted in its place, in the keeper's own way; else, if they
     * let an order lapse here lately, they hear about it ([scold]); else, if they kept what they took off the capital's
     * waylaid wagon, a [cool] word. [quiet] if they've something else to say at once (salvage handed back), so no greeting. The [scold] is for a booking
     * on a haul ticket they let lapse if [haul] is set.
     */
    fun arrive(
        p: Townsperson, player: ServerPlayer, shop: Shop, again: Boolean, news: String?, rank: SupplierRank? = null, scold: Boolean = false,
        cool: Boolean = false, quiet: Boolean = false, haul: Boolean = false,
    ) {
        val level = p.level() as? ServerLevel ?: return
        val busy = customers.any { it != player.uuid }
        customers.add(player.uuid)
        facing = player.uuid
        leaving = -1
        if (quiet) {
            later = null
            return
        }
        // Talk on, a greeting that knows them comes after M3's (a new rank, a lapsed order) and M4's cool word: a friend's, "back again"...
        val known = if (rank == null && !scold && !cool && !busy && !again) Street.counter(level, p, player) else null
        val greeting = when {
            rank != null -> StallTalk.rank(rank)
            scold -> if (haul) StallTalk.haulLapsed(p) else StallTalk.lapsed(p)
            cool && !busy -> StallTalk.COOL
            known != null -> known.first
            else -> StallTalk.greet(p, Days.life(p), shop, level, busy, again)
        }
        say(p, greeting)
        if (!busy && beatEnds < 0) {
            p.carried = ItemStack.EMPTY
            p.act(
                when {
                    rank != null -> cheer(p)
                    scold -> Activity.SHRUG
                    cool -> Activity.NOD
                    known != null -> known.second
                    p.random.nextInt(3) == 0 -> Activity.WAVE
                    else -> Activity.NOD
                },
            )
        }
        later = news
        laterAt = level.gameTime + if (rank != null || scold || cool || known != null) VOICED else NEWS
    }

    /**
     * A deal at the shop: [player] [buying] or selling [good], [done] lots of it gone through and [stop] what stopped
     * the rest, if anything did. [after] is its quote now; [told] the goods whose prices they've explained to [player];
     * [own] if what was sold came off the market's own stalls lately, which they say this once; [marked] if it came off
     * another capital's waylaid wagon, which they remark on this once.
     */
    fun dealt(
        p: Townsperson, player: ServerPlayer, good: Good, buying: Boolean, done: Int, stop: Deal?, after: Quote, told: MutableSet<Good>, own: Boolean,
        marked: Boolean = false,
    ) {
        val now = p.level().gameTime
        facing = player.uuid
        // A deal puts by whatever they were taking across the counter
        beats.clear()
        beatEnds = -1
        if (done > 0) {
            // A quick run of clicks plays out as one handing over, not a stutter of them
            if (p.activity != Activity.HAND_OVER && p.activity != Activity.PAY || p.actTicks >= RESTART) {
                p.act(if (buying) Activity.HAND_OVER else Activity.PAY)
                p.carried = if (buying) good.item?.let { ItemStack(it) } ?: ItemStack.EMPTY else ItemStack(ModItems.GUILD_MARK.get())
                clinkAt = p.tickCount + CLINK
            }
            clinks = minOf(3, clinks + done)
        } else {
            p.carried = ItemStack.EMPTY
            p.act(Activity.SHRUG)
        }
        val line = (if (own) StallTalk.BOUGHT_HERE else null)
            ?: (if (marked) StallTalk.SALVAGE_MARKED else null)
            ?: StallTalk.deal(good, buying, done, stop, after, told)
            ?: StallTalk.remark(p, buying, done, after.why?.dear == true).takeIf { done > 0 && now - spoke >= CHAT && p.random.nextInt(2) == 0 }
        if (line != null) {
            later = null
            say(p, line)
        }
    }

    /** An order taken (or a standing one accepted): they hand over its slip, saying [line]. */
    fun took(p: Townsperson, line: String) = now(p, Beat(Activity.HAND_OVER, ItemStack(Items.PAPER), HANDING, line))

    /** A gesture over an order ([activity]: a shrug at a refusal, a nod at one turned down), saying [line] if there's something to say. */
    fun gesture(p: Townsperson, activity: Activity, line: String?) = now(p, Beat(activity, ItemStack.EMPTY, activity.ticks, line))

    /**
     * Goods delivered to an order: they take [good] across the counter saying [line], put it down with the rest, and
     * count out the [pay] (if it came to a whole Mark yet) and hand it over, to the clink of the coin.
     */
    fun delivered(p: Townsperson, good: Good, line: String, pay: Int) {
        val goods = good.item?.let { ItemStack(it) } ?: ItemStack.EMPTY
        val mark = ItemStack(ModItems.GUILD_MARK.get())
        if (pay <= 0) return now(p, Beat(Activity.RECEIVE, goods, HANDING, line), Beat(Activity.PUT_DOWN, goods, PUTTING))
        now(
            p,
            Beat(Activity.RECEIVE, goods, HANDING, line),
            Beat(Activity.PUT_DOWN, goods, PUTTING),
            Beat(Activity.COUNT, mark, COUNTING, coins = minOf(COUNTED, pay)),
            Beat(Activity.PAY, mark, HANDING, coins = minOf(HANDED, pay)),
        )
    }

    /** A haul ticket booked: they hand over its slip saying [line], to the clerk's stamp and then the booker's seal. */
    fun booked(p: Townsperson, line: String) {
        took(p, line)
        cues.add(Cue(p.tickCount + STAMP, "trade.board_stamp"))
        cues.add(Cue(p.tickCount + SEAL, "trade.seal_press"))
    }

    /**
     * Goods delivered to a haul ticket: they take [good] across the counter and pay as for an order ([delivered]) saying [line], counting [units] of it on a tally
     * stick as they go (a knock of wood for every ten, up to [KNOCKS]) and setting it down with a thud; a ticket [filled] peels off the board as the pay is counted
     * out, and a [relief] ticket filled is bowed over.
     */
    fun hauled(p: Townsperson, good: Good, line: String, pay: Int, units: Int, filled: Boolean, relief: Boolean) {
        delivered(p, good, line, pay)
        val at = p.tickCount
        for (i in 0 until (units / 10).coerceIn(1, KNOCKS)) cues.add(Cue(at + KNOCK_FIRST + i * KNOCK_EVERY, "trade.knock"))
        cues.add(Cue(at + HANDING, "trade.deliver"))
        if (filled) cues.add(Cue(at + HANDING + PUTTING + COUNTING, "trade.ticket_peel"))
        if (filled && relief) next(p, Beat(Activity.BOW, ItemStack.EMPTY, Activity.BOW.ticks))
    }

    /** A supplier they're serving has risen to [rank] at their capital: once what's under way is through, they mark it in their own way. */
    fun risen(p: Townsperson, rank: SupplierRank) {
        val cheer = cheer(p)
        next(p, Beat(Activity.NONE, ItemStack.EMPTY, PAUSE), Beat(cheer, ItemStack.EMPTY, cheer.ticks, StallTalk.rank(rank)), Beat(Activity.TALK, ItemStack.EMPTY, AFTER_WORD))
    }

    /** Once what's under way is through, they say [line] (a standing order offered, after a fill). */
    fun after(p: Townsperson, line: String) = next(p, Beat(Activity.TALK, ItemStack.EMPTY, AFTER_WORD, line))

    /** How [p] marks a supplier's rise in standing, by their ways: a cheer, a nod, a bow, a clap, or their thanks. */
    private fun cheer(p: Townsperson): Activity {
        val resident = Days.life(p)?.resident
        return when {
            resident?.has(Trait.CHEERFUL) == true -> Activity.CHEER
            resident?.has(Trait.GRUMPY) == true -> Activity.NOD
            resident?.has(Trait.SHY) == true -> Activity.BOW
            resident?.has(Trait.CHATTY) == true -> Activity.CLAP
            else -> Activity.THANK
        }
    }

    /** Puts by whatever they were at across the counter and starts [steps] at once. */
    private fun now(p: Townsperson, vararg steps: Beat) {
        later = null
        beats.clear()
        beats.addAll(steps)
        beatEnds = p.tickCount
        step(p)
    }

    /** Starts [steps] once what's under way is through: the steps already to come, or the gesture they're making. */
    private fun next(p: Townsperson, vararg steps: Beat) {
        later = null
        beats.addAll(steps)
        if (beatEnds >= 0) return
        val doing = p.activity
        beatEnds = p.tickCount + if (doing.loop) 0 else (doing.ticks - p.actTicks).coerceAtLeast(0)
    }

    /** The step under way is over: on to the next, or back to standing (and talking while they've something to say). */
    private fun step(p: Townsperson) {
        if (p.tickCount < beatEnds) return
        val beat = beats.removeFirstOrNull()
        if (beat == null) {
            beatEnds = -1
            p.carried = ItemStack.EMPTY
            p.act(if (p.speech.isNotEmpty()) Activity.TALK else Activity.NONE)
            return
        }
        p.carried = beat.held
        p.act(beat.activity)
        beatEnds = p.tickCount + beat.ticks
        beat.line?.let { say(p, it) }
        if (beat.coins > 0) {
            clinks = beat.coins
            clinkAt = p.tickCount + CLINK
        }
    }

    /** Whether [player] is the only one they're serving. */
    fun only(player: UUID): Boolean = customers.all { it == player }

    /**
     * [player] has gone: [traded] if they bought or sold anything (or dealt in an order), [walked] if they walked off;
     * the last to go is seen off (with [line], if there's something else to say), once what they're handing over is
     * through unless they walked off, and a moment later they go back to their day.
     */
    fun leave(p: Townsperson, player: UUID, traded: Boolean, walked: Boolean, line: String?) {
        customers.remove(player)
        if (facing == player) facing = customers.lastOrNull()
        if (customers.isNotEmpty()) return
        val level = p.level() as? ServerLevel ?: return
        later = null
        val bye = line ?: StallTalk.bye(p, Days.life(p), level, traded, walked)
        val gesture = when {
            walked -> Activity.WAVE
            traded -> Activity.BOW
            else -> Activity.NOD
        }
        leaving = level.gameTime + LINGER
        if (beatEnds >= 0 && !walked) return next(p, Beat(gesture, ItemStack.EMPTY, gesture.ticks, bye))
        beats.clear()
        beatEnds = -1
        say(p, bye)
        p.carried = ItemStack.EMPTY
        p.act(gesture)
    }

    private fun say(p: Townsperson, line: String) {
        if (p.say(line, force = true)) spoke = p.level().gameTime
    }

    private companion object {
        /** Degrees a tick they turn toward whoever they're dealing with. */
        const val TURN = 20f

        /** Fewest ticks between two lines of small talk. */
        const val CHAT = 80L

        /** Ticks after the greeting that they say why a price is where it is; after a spoken greeting, which takes longer. */
        const val NEWS = 70L
        const val VOICED = 90L

        /** Ticks they linger, once the last customer's gone, before going back to their day. */
        const val LINGER = 40L

        /** Ticks into a handing over before another deal starts it again. */
        const val RESTART = 14

        /** Ticks into a handing over that the coin clinks. */
        const val CLINK = 10

        /** Ticks of handing something over or taking it, of putting it down, of counting out the pay, and of a pause between. */
        const val HANDING = 30
        const val PUTTING = 20
        const val COUNTING = 40
        const val PAUSE = 10

        /** Ticks they stand talking after a word of their own, before anything else. */
        const val AFTER_WORD = 50

        /** The most clinks of coin while counting out the pay, and while handing it over. */
        const val COUNTED = 6
        const val HANDED = 3

        /** The most knocks of the tally stick for a delivery, the tick of the first and the ticks between them; the ticks into a booking that the stamp falls and the seal is pressed. */
        const val KNOCKS = 8
        const val KNOCK_FIRST = 4
        const val KNOCK_EVERY = 5
        const val STAMP = 6
        const val SEAL = 14

        val COIN: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.thief.coin"))
    }
}
