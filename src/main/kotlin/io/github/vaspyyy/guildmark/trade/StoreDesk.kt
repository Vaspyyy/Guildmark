package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.SupplierLink
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.build.CountingHouse
import io.github.vaspyyy.guildmark.economy.works.build.Pin
import io.github.vaspyyy.guildmark.economy.works.build.Store
import io.github.vaspyyy.guildmark.economy.works.build.WorksBuildData
import io.github.vaspyyy.guildmark.economy.works.build.WorksPlans
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.OpenStorePayload
import io.github.vaspyyy.guildmark.network.StoreActionPayload
import io.github.vaspyyy.guildmark.network.StoreClosePayload
import io.github.vaspyyy.guildmark.network.StoreGood
import io.github.vaspyyy.guildmark.network.StoreParcelLine
import io.github.vaspyyy.guildmark.network.StoreRefreshPayload
import io.github.vaspyyy.guildmark.network.StoreTown
import io.github.vaspyyy.guildmark.network.StoreUpdatePayload
import io.github.vaspyyy.guildmark.network.StoreView
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.carry.WagonBill
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The Carriers' Store's desk (living-world/trader/design.md, Part 5; build part F2): the screen its owner opens by using the clerk, the
 * scale on the counter or the writing desk behind it, on the pattern of a market hall table's ([StallDesk]). Five tabs: *Bonded goods*
 * (bond from the pack, take back, each hauled parcel with the days its name has left), *Word* (the live word of the Store's town and of
 * the towns where the owner keeps a stall), *Wagon* (the Carrier's Wagon: its Bill and horse sold, where it is, called to the yard; build part
 * D3's [WagonBill.desk]), *Guards* (who is hired) and *Account* (the clerk's wage
 * and the half rent). Only the Store's owner may use it; anyone else using the clerk talks with them as with anyone.
 *
 * What the screen asks, the server checks: the player at the Store, the Store theirs, the goods in their pack and the Marks. A move is
 * seen in the Store: the hoist lifts a crate in or lets one down ([StoreLife.hoist]), the clerk takes or hands over the goods, weighs a
 * sack and says a word; and an owing wage is said by the clerk too. A Store taken down keeps its goods for its owner (see
 * [Stores.closing]), and any market hall's merchant hands them back.
 */
object StoreDesk {
    /** What the desk can be asked for (see [StoreActionPayload]). */
    const val BOND = 0
    const val TAKE = 1
    const val PAY = 2

    /** The Wagon tab's (build part D3, [WagonBill.desk]): the Wagon Bill, a horse, the wagon called to this yard, a new copy of the Bill. */
    const val WAGON_BILL = 3
    const val WAGON_HORSE = 4
    const val WAGON_RECALL = 5
    const val WAGON_COPY = 6

    /** How many a click moves (a shift-click: [Stores.MOST], all of them). */
    const val BATCH = 16

    /** How near where the desk was opened a player has to stand to use it; the screen closes a little further off. */
    private const val REACH = 7.0
    private const val SLACK = 2.0

    /** Ticks a screen may go without a word from its player, fewest ticks between two refreshes, and between two looks at the towns' word. */
    private const val TIMEOUT = 200L
    private const val REFRESH = 10L
    private const val WORD_EVERY = 100L

    /** Ticks a strongbox's lid stays up when its owner lifts it for a look. */
    private const val PEEK = 32

    /** Ticks between looks at the open screens, and at the owners about their clerks. */
    private const val SWEEP = 10
    private const val ROUND = 40

    /** How near the clerk the owner comes to be greeted or told the wage is owing, and the fewest ticks between two greetings. */
    private const val NEAR_CLERK = 10.0
    private const val HELLO_GAP = 6000L

    /** Fewest ticks between two of the clerk's words at the desk ("Bonded...", "My wage is owing..."). */
    private const val WORD_GAP = 160L

    private val HELLO = listOf("trade_clerk_hello_0", "trade_clerk_hello_1", "trade_clerk_hello_2")
    private val BONDED = listOf("trade_clerk_bonded_0", "trade_clerk_bonded_1")
    private val UNPAID = listOf("trade_clerk_unpaid_0", "trade_clerk_unpaid_1")

    /** A player's open desk: works [works], the Store of [owner] in [capital] (called [place]), opened standing near [at]. */
    private class Session(val player: UUID, val works: Int, val capital: Long, val owner: UUID, val place: String, val at: Vec3, now: Long) {
        var heard = now
        var refreshed = -1L
        var acted = -1L
        var said = Long.MIN_VALUE / 2

        /** The towns' word as last looked at, and when. */
        var towns: List<StoreTown> = listOf()
        var townsAt = Long.MIN_VALUE / 2
    }

    private val sessions = HashMap<UUID, Session>()

    /** When each owner was last greeted by a clerk; the town's day each Store's clerk last said their wage is owing. */
    private val greeted = HashMap<UUID, Long>()
    private val owed = HashMap<Int, Long>()

    /** Towns' names by their hearts, as found (they do not change). */
    private val names = HashMap<Long, String>()

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(OpenStorePayload.TYPE, OpenStorePayload.STREAM_CODEC, OpenStorePayload::handle)
                .playToClient(StoreUpdatePayload.TYPE, StoreUpdatePayload.STREAM_CODEC, StoreUpdatePayload::handle)
                .playToServer(StoreActionPayload.TYPE, StoreActionPayload.STREAM_CODEC, StoreActionPayload::handle)
                .playToServer(StoreRefreshPayload.TYPE, StoreRefreshPayload.STREAM_CODEC, StoreRefreshPayload::handle)
                .playToServer(StoreClosePayload.TYPE, StoreClosePayload.STREAM_CODEC, StoreClosePayload::handle)
        }
        TradeBlocks.used = ::usedFitting
        val bus = NeoForge.EVENT_BUS
        bus.addListener(PlayerInteractEvent.RightClickBlock::class.java, ::onUseBlock)
        bus.addListener(PlayerInteractEvent.EntityInteract::class.java, ::onUseEntity)
        bus.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::close) }
        bus.addListener(ServerStoppedEvent::class.java) { _ ->
            sessions.clear()
            greeted.clear()
            owed.clear()
            names.clear()
        }
    }

    // ---- Stepping up ----

    /** Where works [w]'s [pin] is in the world, if it is sited. */
    private fun at(level: ServerLevel, w: WorksRec, pin: Pin): BlockPos? {
        val rec = WorksBuildData.get(level)[w.id]?.takeIf { it.sited && !it.removed } ?: return null
        val planned = WorksPlans.of(rec)
        return planned.frame.world(pin.x, planned.drawing.y0 + pin.y, pin.z)
    }

    /** The standing Store (or, [kind], Counting House) with [pin] at [pos], if one has. */
    private fun storeWith(level: ServerLevel, pos: BlockPos, pin: Pin, kind: WorksKind = WorksKind.STORE): WorksRec? =
        WorksData.get(level).works.values.firstOrNull { it.kind == kind && it.state == WorksState.DONE && at(level, it, pin) == pos }

    /**
     * One of the trader's fittings used: the scale on a Store's counter opens its desk, for its owner; a Counting House's counting frame is
     * its desk ([Houses.desk]), and its strongbox's lid its owner may lift for a look inside.
     */
    private fun usedFitting(level: ServerLevel, player: ServerPlayer, pos: BlockPos): Boolean {
        if (player.isSpectator || player.isShiftKeyDown) return false
        when (level.getBlockState(pos).block) {
            TradeBlocks.SCALE.get() -> {
                val w = storeWith(level, pos, Store.SCALE_AT) ?: return false
                return open(level, player, w, Vec3.atCenterOf(pos), null)
            }
            TradeBlocks.COUNTING_FRAME.get() -> {
                val w = storeWith(level, pos, CountingHouse.FRAME_AT, WorksKind.COUNTING_HOUSE) ?: return false
                return Houses.desk(level, player, w, null)
            }
            TradeBlocks.STRONGBOX.get() -> {
                val w = storeWith(level, pos, CountingHouse.STRONGBOX_AT, WorksKind.COUNTING_HOUSE) ?: return false
                if (w.owner != player.uuid) return false
                val box = level.getBlockEntity(pos) as? StrongboxBlockEntity ?: return false
                if (!box.open) box.lift(PEEK)
                return true
            }
            else -> return false
        }
    }

    /** The writing desk behind a Store's counter (a lectern) used, not crouching: its desk opens, for its owner; and a Counting House's ledger, its desk. */
    private fun onUseBlock(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.isShiftKeyDown || player.isSpectator) return
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD || !level.getBlockState(event.pos).`is`(Blocks.LECTERN)) return
        val house = storeWith(level, event.pos, CountingHouse.LEDGER_AT, WorksKind.COUNTING_HOUSE)
        if (house != null) {
            if (house.owner != player.uuid) return
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            if (event.hand == InteractionHand.MAIN_HAND) Houses.desk(level, player, house, null)
            return
        }
        val w = storeWith(level, event.pos, Store.DESK_AT) ?: return
        if (!may(level, player, w)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        if (event.hand == InteractionHand.MAIN_HAND) open(level, player, w, Vec3.atCenterOf(event.pos), null)
    }

    /** Using a Store's clerk (or a Counting House's scribe): their owner has the desk; and a hall merchant hands back what a Store taken down held. */
    private fun onUseEntity(event: PlayerInteractEvent.EntityInteract) {
        val player = event.entity as? ServerPlayer ?: return
        val p = event.target as? Townsperson ?: return
        if (event.hand != InteractionHand.MAIN_HAND || player.isSpectator || p.role != TownRole.MERCHANT) return
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD) return
        val works = Stores.clerks(level)[p.resident]
        if (works != null) {
            val w = WorksData.get(level).works[works] ?: return
            if (w.state != WorksState.DONE || w.owner != player.uuid) return
            // A Counting House's scribe: its desk
            if (w.kind == WorksKind.COUNTING_HOUSE) {
                if (Houses.desk(level, player, w, p)) {
                    event.isCanceled = true
                    event.cancellationResult = InteractionResult.SUCCESS
                }
                return
            }
            if (w.kind != WorksKind.STORE) return
            if (open(level, player, w, p.position(), p)) {
                event.isCanceled = true
                event.cancellationResult = InteractionResult.SUCCESS
            }
            return
        }
        val back = TradeStores.peek(level)?.returns?.get(player.uuid) ?: return
        if (!hallMerchant(p)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        handBack(level, player, back, p)
    }

    /** Whether [p] is one of a capital's market hall merchants. */
    private fun hallMerchant(p: Townsperson): Boolean {
        if (!p.isAlive) return false
        val places = Days.life(p)?.places ?: return false
        return places.lodgingAt(p.post ?: p.work ?: p.blockPosition())?.kind == Kind.MARKET_HALL.name
    }

    /** [p], a hall merchant, hands [player] back what a Store of theirs taken down held ([back]): the goods, their parcels into the books. */
    private fun handBack(level: ServerLevel, player: ServerPlayer, back: StoreReturn, p: Townsperson) {
        val data = TradeStores.peek(level) ?: return
        data.returns.remove(player.uuid)
        data.changed()
        for (g in Good.entries) {
            val n = back.goods[g.ordinal]
            if (n > 0) g.item?.let { Trading.give(player, it, n) }
        }
        Ledger.add(level, player.uuid, back.parcels)
        p.lookAt(player)
        p.attend(player, 60)
        if (p.director == null) p.act(Activity.HAND_OVER)
        p.say("stall_gone_1", force = true)
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.sack_lift"), SoundSource.PLAYERS, 0.8f, 1f)
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.store.handed_back", back.place, back.goods.sum()).withStyle(ChatFormatting.GOLD))
    }

    /**
     * Opens [w]'s desk for [player], used at [at] (by its clerk [clerk], if it was the clerk), if they may: only its owner, and with the
     * Stores switched off only while it still holds goods of theirs. False, with nothing opened, if not.
     */
    internal fun open(level: ServerLevel, player: ServerPlayer, w: WorksRec, at: Vec3, clerk: Townsperson?): Boolean {
        if (!may(level, player, w)) return false
        val owner = w.owner ?: return false
        val rec = (if (TradeConfig.stores()) Stores.recOf(level, w) else TradeStores.peek(level)?.find(w.capital, owner)) ?: return false
        val now = level.gameTime
        val old = sessions[player.uuid]
        val s = if (old != null && old.works == w.id) old.also { it.heard = now }
        else Session(player.uuid, w.id, w.capital, owner, Stores.placeOf(level, w.capital), at, now)
        sessions[player.uuid] = s
        greet(level, player, w, rec, clerk ?: clerkOf(level, w))
        PacketDistributor.sendToPlayer(player, OpenStorePayload(view(level, s, player)))
        return true
    }

    /** Whether [player] may have [w]'s desk: it is theirs, and with the Stores switched off only while it still holds goods of theirs. */
    private fun may(level: ServerLevel, player: ServerPlayer, w: WorksRec): Boolean {
        if (w.owner != player.uuid) return false
        if (TradeConfig.stores()) return true
        return (TradeStores.peek(level)?.find(w.capital, player.uuid)?.units() ?: 0) > 0
    }

    /** The clerk turns to the owner at the desk and, now and then, greets them; or says their wage is owing. */
    private fun greet(level: ServerLevel, player: ServerPlayer, w: WorksRec, rec: StoreRec, clerk: Townsperson?) {
        clerk ?: return
        if (clerk.distanceToSqr(player) > NEAR_CLERK * NEAR_CLERK) return
        clerk.lookAt(player)
        clerk.attend(player, 80)
        if (!TradeConfig.stores()) return
        if (Stores.owing(level, rec)) {
            owing(level, w, clerk, player)
            return
        }
        val now = level.gameTime
        if (now - (greeted[player.uuid] ?: Long.MIN_VALUE / 2) < HELLO_GAP) return
        greeted[player.uuid] = now
        StoreLife.say(clerk, HELLO[level.random.nextInt(HELLO.size)])
    }

    /** [clerk] says their wage is owing, once a town's day at most unless [player] is at the desk. */
    private fun owing(level: ServerLevel, w: WorksRec, clerk: Townsperson, player: ServerPlayer) {
        owed[w.id] = Suppliers.today(level)
        clerk.lookAt(player)
        StoreLife.say(clerk, UNPAID[level.random.nextInt(UNPAID.size)])
    }

    /** [w]'s clerk, if they have come and are about. */
    internal fun clerkOf(level: ServerLevel, w: WorksRec): Townsperson? {
        if (w.hired < 0) return null
        val uuid = Residents.get(level)[w.hired]?.uuid ?: return null
        return (level.getEntity(uuid) as? Townsperson)?.takeIf { it.isAlive }
    }

    /** [player]'s screen asks to see the Store as it stands, and says it's still open. */
    fun refresh(player: ServerPlayer) {
        val s = sessions[player.uuid] ?: return
        val now = player.level().gameTime
        s.heard = now
        if (now - s.refreshed < REFRESH) return
        s.refreshed = now
        if (!check(player, s)) return
        send(player, s)
    }

    /** [player] closed their screen, or left the game. */
    fun close(player: ServerPlayer) {
        sessions.remove(player.uuid)
    }

    // ---- Doing things ----

    /** [player] asks for [action] at the desk they have open: with [count] of the good [id] to bond or take back. */
    fun act(player: ServerPlayer, action: Int, id: String, count: Int) {
        val s = sessions[player.uuid] ?: return
        val level = player.level()
        val now = level.gameTime
        s.heard = now
        if (!check(player, s)) return
        if (player.position().distanceToSqr(s.at) > REACH * REACH) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.trade.store.closer"))
            return send(player, s)
        }
        // One thing a tick however fast the clicks come
        if (s.acted == now) return send(player, s)
        s.acted = now
        val w = WorksData.get(level).works[s.works] ?: return stop(player, s)
        val rec = TradeStores.peek(level)?.find(s.capital, s.owner) ?: return stop(player, s)
        val clerk = clerkOf(level, w)?.takeIf { it.distanceToSqr(player) < 24.0 * 24.0 }
        val why = when (action) {
            BOND -> bond(level, player, s, w, rec, Good.byId(id), count, clerk)
            TAKE -> take(level, player, s, w, rec, Good.byId(id), count, clerk)
            PAY -> pay(level, player, rec, clerk)
            in WAGON_BILL..WAGON_COPY -> {
                WagonBill.desk(player, action - WAGON_BILL, s.capital, s.owner, clerk)?.let { player.sendOverlayMessage(it.copy().withStyle(ChatFormatting.RED)) }
                null
            }
            else -> null
        }
        if (why != null) player.sendOverlayMessage(Component.translatable(why, s.place, Stores.WAGE).withStyle(ChatFormatting.RED))
        send(player, s)
    }

    private fun bond(level: ServerLevel, player: ServerPlayer, s: Session, w: WorksRec, rec: StoreRec, good: Good?, count: Int, clerk: Townsperson?): String? {
        good ?: return null
        val moved = Stores.bond(level, player, rec, good, count.coerceIn(1, Stores.MOST))
        moved.why?.let { why ->
            if (why == "gui.guildmark.trade.store.why.unpaid" && clerk != null && level.gameTime - s.said >= WORD_GAP) {
                s.said = level.gameTime
                owing(level, w, clerk, player)
            }
            return why
        }
        // The crate goes up into the loft, the clerk takes the goods in and weighs a sack of them
        StoreLife.hoist(level, s.capital, s.owner, ItemStack(Items.BARREL), down = false)
        StoreLife.weigh(level, s.capital, s.owner)
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.sack_lift"), SoundSource.PLAYERS, 0.7f, 0.95f + level.random.nextFloat() * 0.1f)
        if (clerk != null) {
            clerk.lookAt(player)
            if (clerk.director == null) clerk.act(Activity.RECEIVE)
            if (level.gameTime - s.said >= WORD_GAP) {
                s.said = level.gameTime
                StoreLife.say(clerk, BONDED[level.random.nextInt(BONDED.size)])
            }
        }
        return null
    }

    private fun take(level: ServerLevel, player: ServerPlayer, s: Session, w: WorksRec, rec: StoreRec, good: Good?, count: Int, clerk: Townsperson?): String? {
        good ?: return null
        val moved = Stores.withdraw(level, player, rec, good, count.coerceIn(1, Stores.MOST))
        moved.why?.let { return it }
        StoreLife.hoist(level, s.capital, s.owner, ItemStack(Items.BARREL), down = true)
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.sack_lift"), SoundSource.PLAYERS, 0.7f, 0.8f + level.random.nextFloat() * 0.1f)
        if (clerk != null) {
            clerk.lookAt(player)
            if (clerk.director == null) clerk.act(Activity.HAND_OVER)
        }
        return null
    }

    private fun pay(level: ServerLevel, player: ServerPlayer, rec: StoreRec, clerk: Townsperson?): String? {
        Stores.pay(level, player, rec)?.let { return it }
        level.playSound(null, player.blockPosition(), StallDesk.TILL, SoundSource.PLAYERS, 0.8f, 1.05f)
        if (clerk != null) {
            clerk.lookAt(player)
            if (clerk.director == null) clerk.act(Activity.PAY)
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.store.paid", Stores.WAGE, Stores.paidDays(level, rec)).withStyle(ChatFormatting.GOLD))
        return null
    }

    // ---- Keeping it honest ----

    /** Whether [s]'s screen can stay open; if not, it's closed and false. */
    private fun check(player: ServerPlayer, s: Session): Boolean {
        val gone = !player.isAlive || player.isSpectator || player.level().dimension() != Level.OVERWORLD ||
            player.position().distanceToSqr(s.at) > (REACH + SLACK) * (REACH + SLACK) ||
            TradeStores.peek(player.level())?.find(s.capital, s.owner) == null
        if (gone) stop(player, s)
        return !gone
    }

    /** Closes [s]'s screen from this side. */
    private fun stop(player: ServerPlayer, s: Session) {
        if (sessions[player.uuid] === s) sessions.remove(player.uuid)
        PacketDistributor.sendToPlayer(player, StoreUpdatePayload(view(player.level(), s, player), closed = true))
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % SWEEP == 0 && sessions.isNotEmpty()) for (s in sessions.values.toList()) {
            val player = server.playerList.getPlayer(s.player)
            if (player == null) {
                sessions.remove(s.player)
                continue
            }
            if (player.level().gameTime - s.heard > TIMEOUT) stop(player, s) else check(player, s)
        }
        if (server.tickCount % ROUND == 0) round(server)
    }

    /** A Store's clerk whose wage is owing says so to its owner near them, once a town's day. */
    private fun round(server: MinecraftServer) {
        if (!TradeConfig.stores()) return
        val level = server.overworld()
        val data = TradeStores.peek(level) ?: return
        val today = Suppliers.today(level)
        for (rec in data.stores) {
            if (!Stores.owing(level, rec)) continue
            val player = server.playerList.getPlayer(rec.owner) ?: continue
            if (player.level() !== level || player.isSpectator) continue
            val w = WorksData.get(level).find(rec.capital, WorksKind.STORE, owner = rec.owner) ?: continue
            if ((owed[w.id] ?: Long.MIN_VALUE) >= today) continue
            val clerk = clerkOf(level, w) ?: continue
            if (clerk.distanceToSqr(player) > NEAR_CLERK * NEAR_CLERK || clerk.isSleeping) continue
            owing(level, w, clerk, player)
        }
    }

    // ---- What the screen shows ----

    private fun send(player: ServerPlayer, s: Session) {
        PacketDistributor.sendToPlayer(player, StoreUpdatePayload(view(player.level(), s, player), closed = false))
    }

    /** The name of the town whose heart is [capital], as found once. */
    private fun nameOf(level: ServerLevel, capital: Long): String =
        names[capital] ?: Word.nameOf(level, capital).ifEmpty { Suppliers.place(level, BlockPos.of(capital)) }.also { if (it.isNotEmpty()) names[capital] = it }

    private fun view(level: ServerLevel, s: Session, player: ServerPlayer): StoreView {
        val rec = TradeStores.peek(level)?.find(s.capital, s.owner)
        val w = WorksData.get(level).works[s.works]
        val inventory = player.inventory
        // What the ledger counts as hauled, by good, of everything the player has
        val hauled = IntArray(Good.entries.size)
        for (line in Ledger.lines(level, player.uuid)) hauled[line.parcel.good.ordinal] += line.parcel.units
        val goods = ArrayList<StoreGood>()
        if (rec != null) {
            if (Stores.tidy(level, rec)) TradeStores.get(level).changed()
            for (g in Good.TRADED) {
                val item = g.item ?: continue
                val carried = Stores.carried(inventory, item)
                if (rec.of(g) == 0 && carried == 0) continue
                val parcels = rec.parcels.filter { it.good == g }.map { StoreParcelLine(it.units, Stores.daysLeft(level, it), nameOf(level, it.origin.asLong())) }
                goods.add(StoreGood(g.id, rec.of(g), carried, minOf(carried, hauled[g.ordinal]), Stores.room(rec, g), parcels))
            }
        }
        // The towns' word is looked at only every little while: a market's sight is worked out afresh each time
        val now = level.gameTime
        if (now - s.townsAt >= WORD_EVERY) {
            s.townsAt = now
            s.towns = towns(level, s, player)
        }
        // The tables the player rents, and what a week of them costs with the Store and would without
        var tables = 0
        var keepNow = 0
        var keepFull = 0
        for (hall in OwnStalls.get(level).all()) {
            val n = hall.stalls.count { it?.renter == player.uuid }
            if (n == 0) continue
            val rank = SupplierLink.rank(level, player.uuid, BlockPos.of(hall.capital))
            tables += n
            keepNow += n * StallDesk.keep(rank, player)
            keepFull += n * StallDesk.keep(rank)
        }
        val book = TradeBooks.peek(level)?.get(player.uuid)
        return StoreView(
            s.place, rec?.name ?: player.name.string, rec?.colour ?: StallDesk.colorOf(level, player.uuid), TradeConfig.stores(), TownControl.marks(player),
            rec?.let { Stores.refusal(level, it) } ?: "", Stores.WAGE, rec?.let { Stores.paidDays(level, it).toInt() } ?: 0, Stores.AHEAD.toInt(),
            rec?.let { Stores.payRefusal(level, player, it) } ?: "", w != null && clerkOf(level, w) != null,
            goods, Stores.KINDS, Stores.MOST, TradeConfig.word(), s.towns,
            TradeConfig.wagon(), Stores.yard(player, s.capital), TradeConfig.hunt(), book?.guards?.size ?: 0, (TradeConfig.guardWage() * 10).roundToInt(),
            tables, keepNow, keepFull, Stores.halves(player), WagonBill.view(player, s.capital),
        )
    }

    /** The live word at the desk: the Store's own town as it stands now, then each town where the player keeps a stall. */
    private fun towns(level: ServerLevel, s: Session, player: ServerPlayer): List<StoreTown> {
        if (!TradeConfig.word()) return listOf()
        val here = BlockPos.of(s.capital)
        val out = ArrayList<StoreTown>()
        val capitals = listOf(s.capital) + Tally.owned(level, player).filter { it != s.capital }
        for (c in capitals.take(TOWNS)) {
            val seen = Tally.look(level, BlockPos.of(c), Seen.LIVE, false) ?: continue
            val sight = seen.sight
            val best = sight.tickets.maxByOrNull { it.pay }
            val there = BlockPos.of(c)
            out.add(
                StoreTown(
                    seen.name.ifEmpty { nameOf(level, c) }, hypot((there.x - here.x).toDouble(), (there.z - here.z).toDouble()).toInt(), c == s.capital,
                    sight.shorts(SHOWN).map { it.id }, sight.gluts(SHOWN).map { it.id }, sight.tickets.size,
                    best?.good?.id ?: "", best?.units ?: 0, best?.pay ?: 0,
                ),
            )
        }
        return out
    }

    /** The most towns the Word tab shows, and the most goods it names a town short of or glutted with. */
    private const val TOWNS = 6
    private const val SHOWN = 4
}
