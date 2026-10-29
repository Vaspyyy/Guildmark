package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.build.CountingHouse
import io.github.vaspyyy.guildmark.economy.works.build.TownWorksSite
import io.github.vaspyyy.guildmark.economy.works.build.WorksBuildData
import io.github.vaspyyy.guildmark.economy.works.build.WorksPlans
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.HouseActionPayload
import io.github.vaspyyy.guildmark.network.HouseBookEntry
import io.github.vaspyyy.guildmark.network.HouseCarterLine
import io.github.vaspyyy.guildmark.network.HouseClosePayload
import io.github.vaspyyy.guildmark.network.HouseRefreshPayload
import io.github.vaspyyy.guildmark.network.HouseUpdatePayload
import io.github.vaspyyy.guildmark.network.HouseView
import io.github.vaspyyy.guildmark.network.OpenHousePayload
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The trading house's desk (living-world/trader/design.md, Part 5; build part G): the screen its owner opens at the counting frame, the
 * ledger or the scribe, on the pattern of the Store's ([StoreDesk]). Three tabs: *Carters* (each with where they are and when they are
 * back, the extra guards their wagon takes, let go; one hired), *Book* (the last trips and what each made) and *Purse* (Marks put in, the
 * profit taken out down to the [Houses.FLOAT], and the totals since the House opened).
 *
 * What the screen asks, the server checks: the player at the House, the House theirs, the Marks in their pack. A move is seen at the House:
 * Marks in or out lift the strongbox's lid (`trade.strongbox`) with a clink (`trade.pack_clink`) and the scribe takes or hands them over;
 * a scribe who has seen the purse too short for a wagon says so. A Counting House that stood before its records were kept (build part F2's)
 * has its record made when its desk is first opened, quietly. With `tradeHouse` off a House with a record still opens its desk, to see its
 * book and empty its purse; one without keeps no books, as before.
 */
object HouseDesk {
    /** What the desk can be asked for (see [HouseActionPayload]). */
    const val HIRE = 0
    const val DISMISS = 1
    const val GUARDS = 2
    const val PUT = 3
    const val TAKE = 4

    /** How near where the desk was opened a player has to stand to use it; the screen closes a little further off. */
    private const val REACH = 7.0
    private const val SLACK = 2.0

    /** Ticks a screen may go without a word from its player, and fewest ticks between two refreshes. */
    private const val TIMEOUT = 200L
    private const val REFRESH = 10L

    /** Ticks between looks at the open screens; ticks the strongbox's lid stays up as Marks go in or out. */
    private const val SWEEP = 10
    private const val LID = 32

    /** How near the scribe the owner is to be greeted, fewest ticks between two greetings and between two of the scribe's words at the desk. */
    private const val NEAR_SCRIBE = 10.0
    private const val HELLO_GAP = 6000L
    private const val WORD_GAP = 160L

    private val BOOKS = listOf("trade_scribe_books_0", "trade_scribe_books_1")
    private val FLOAT = listOf("trade_scribe_float_0", "trade_scribe_float_1")

    /** A player's open desk: works [works], the House of [owner] in [capital] (called [place]), opened standing near [at]. */
    private class Session(val player: UUID, val works: Int, val capital: Long, val owner: UUID, val place: String, val at: Vec3, now: Long) {
        var heard = now
        var refreshed = -1L
        var acted = -1L
        var said = Long.MIN_VALUE / 2
    }

    private val sessions = HashMap<UUID, Session>()

    /** When each owner was last greeted by a scribe. */
    private val greeted = HashMap<UUID, Long>()

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(OpenHousePayload.TYPE, OpenHousePayload.STREAM_CODEC, OpenHousePayload::handle)
                .playToClient(HouseUpdatePayload.TYPE, HouseUpdatePayload.STREAM_CODEC, HouseUpdatePayload::handle)
                .playToServer(HouseActionPayload.TYPE, HouseActionPayload.STREAM_CODEC, HouseActionPayload::handle)
                .playToServer(HouseRefreshPayload.TYPE, HouseRefreshPayload.STREAM_CODEC, HouseRefreshPayload::handle)
                .playToServer(HouseClosePayload.TYPE, HouseClosePayload.STREAM_CODEC, HouseClosePayload::handle)
        }
        val bus = NeoForge.EVENT_BUS
        bus.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::close) }
        bus.addListener(ServerStoppedEvent::class.java) { _ ->
            sessions.clear()
            greeted.clear()
        }
    }

    // ---- Stepping up ----

    /**
     * Opens [w]'s desk for [player] (at its scribe [scribe], if it was the scribe): only its owner. A House with no record yet has one made
     * now, if the Houses are on; with them off it keeps no books, as before part G.
     */
    internal fun open(level: ServerLevel, player: ServerPlayer, w: WorksRec, scribe: Townsperson?): Boolean {
        val owner = w.owner ?: return false
        if (owner != player.uuid || w.state != WorksState.DONE) return false
        val near = scribe ?: StoreDesk.clerkOf(level, w)
        val h = TradeHouses.peek(level)?.find(w.capital, owner) ?: (if (TradeConfig.house()) Houses.adopt(level, w) else null)
        if (h == null) {
            near?.let { nod(it, player) }
            player.sendSystemMessage(Component.translatable("message.guildmark.trade.house.no_books").withStyle(ChatFormatting.GRAY))
            return true
        }
        val now = level.gameTime
        val old = sessions[player.uuid]
        val s = if (old != null && old.works == w.id) old.also { it.heard = now }
        else Session(player.uuid, w.id, w.capital, owner, Houses.placeOf(level, w.capital), (scribe?.position() ?: player.position()), now)
        sessions[player.uuid] = s
        greet(level, player, s, h, near)
        PacketDistributor.sendToPlayer(player, OpenHousePayload(view(level, s, player)))
        return true
    }

    private fun nod(scribe: Townsperson, player: ServerPlayer) {
        scribe.lookAt(player)
        scribe.attend(player, 40)
        if (scribe.director == null) scribe.act(Activity.NOD)
    }

    /** The scribe turns to the owner at the desk and, now and then, greets them: the books balance, or the purse is too short for a wagon. */
    private fun greet(level: ServerLevel, player: ServerPlayer, s: Session, h: HouseRec, scribe: Townsperson?) {
        scribe ?: return
        if (scribe.distanceToSqr(player) > NEAR_SCRIBE * NEAR_SCRIBE) return
        scribe.lookAt(player)
        scribe.attend(player, 80)
        if (!TradeConfig.house()) return
        val now = level.gameTime
        if (now - (greeted[player.uuid] ?: Long.MIN_VALUE / 2) < HELLO_GAP) return
        greeted[player.uuid] = now
        s.said = now
        val lines = if (short(h)) FLOAT else BOOKS
        StoreLife.say(scribe, lines[level.random.nextInt(lines.size)])
    }

    /** Whether [h]'s carters wait at the yard for want of the purse. */
    private fun short(h: HouseRec): Boolean = h.idleWhy == "float" && h.carters.any { it.free }

    /** [player]'s screen asks to see the House as it stands, and says it's still open. */
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

    /** [player] asks for [action] at the desk they have open: about carter [carter], with [count] (the guards, or the Marks). */
    fun act(player: ServerPlayer, action: Int, carter: Int, count: Int) {
        val s = sessions[player.uuid] ?: return
        val level = player.level()
        val now = level.gameTime
        s.heard = now
        if (!check(player, s)) return
        if (player.position().distanceToSqr(s.at) > REACH * REACH) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.trade.house.closer"))
            return send(player, s)
        }
        // One thing a tick however fast the clicks come
        if (s.acted == now) return send(player, s)
        s.acted = now
        val w = WorksData.get(level).works[s.works] ?: return stop(player, s)
        val h = TradeHouses.peek(level)?.find(s.capital, s.owner) ?: return stop(player, s)
        val scribe = StoreDesk.clerkOf(level, w)?.takeIf { it.distanceToSqr(player) < 24.0 * 24.0 }
        val why = when (action) {
            HIRE -> hire(level, player, h)
            DISMISS -> dismiss(level, player, h, carter)
            GUARDS -> h.carter(carter)?.let { Houses.guards(level, it, count); null }
            PUT -> put(level, player, s, w, h, count, scribe)
            TAKE -> take(level, player, s, w, h, count, scribe)
            else -> null
        }
        if (why != null) player.sendOverlayMessage(Component.translatable(why, Houses.most(level), Houses.FLOAT).withStyle(ChatFormatting.RED))
        send(player, s)
    }

    private fun hire(level: ServerLevel, player: ServerPlayer, h: HouseRec): String? {
        Houses.hireRefusal(level, h)?.let { return "gui.guildmark.trade.house.refused.$it" }
        val c = Houses.hire(level, h) ?: return "gui.guildmark.trade.house.refused.most"
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.house.hired", c.name, TradeConfig.carterWage()).withStyle(ChatFormatting.GOLD))
        return null
    }

    /** Carter [id] let go (at once at the yard, or once back with their wagon); one already let go but still out is kept on after all. */
    private fun dismiss(level: ServerLevel, player: ServerPlayer, h: HouseRec, id: Int): String? {
        val c = h.carter(id) ?: return null
        if (c.leaving) {
            c.leaving = false
            TradeHouses.peek(level)?.changed()
            player.sendSystemMessage(Component.translatable("message.guildmark.trade.house.kept", c.name).withStyle(ChatFormatting.GRAY))
            return null
        }
        val gone = Houses.dismiss(level, h, c)
        player.sendSystemMessage(Component.translatable(if (gone) "message.guildmark.trade.house.dismissed" else "message.guildmark.trade.house.leaving", c.name).withStyle(ChatFormatting.GRAY))
        return null
    }

    /** [count] Marks from [player]'s pack into the purse: the strongbox's lid lifts, the scribe takes them. */
    private fun put(level: ServerLevel, player: ServerPlayer, s: Session, w: WorksRec, h: HouseRec, count: Int, scribe: Townsperson?): String? {
        if (!TradeConfig.house()) return "gui.guildmark.trade.house.refused.off"
        if (h.closed) return "gui.guildmark.trade.house.refused.closed"
        val n = minOf(count.coerceIn(1, PUT_MOST), TownControl.marks(player))
        if (n <= 0) return "gui.guildmark.trade.house.no_marks"
        TownControl.pay(player, n)
        Houses.putIn(level, h, n)
        lid(level, w)
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.pack_clink"), SoundSource.PLAYERS, 0.7f, 1.1f)
        if (scribe != null) {
            scribe.lookAt(player)
            if (scribe.director == null) scribe.act(Activity.RECEIVE)
        }
        return null
    }

    /** Up to [count] Marks of the profit out of the purse into [player]'s pack, the float left in it while the House works. */
    private fun take(level: ServerLevel, player: ServerPlayer, s: Session, w: WorksRec, h: HouseRec, count: Int, scribe: Townsperson?): String? {
        val k = Houses.takeOut(level, h, count.coerceAtLeast(1))
        if (k <= 0) return "gui.guildmark.trade.house.nothing"
        Trading.give(player, ModItems.GUILD_MARK.get(), k)
        lid(level, w)
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.pack_clink"), SoundSource.PLAYERS, 0.7f, 0.95f)
        if (scribe != null) {
            scribe.lookAt(player)
            if (scribe.director == null) scribe.act(Activity.HAND_OVER)
            // Taken down to where the wagons cannot load: the scribe says so, now and then
            if (TradeConfig.house() && h.carters.isNotEmpty() && h.purse < max(Houses.FLOAT, h.carters.size * TradeConfig.carterWage() * 2) &&
                level.gameTime - s.said >= WORD_GAP
            ) {
                s.said = level.gameTime
                StoreLife.say(scribe, FLOAT[level.random.nextInt(FLOAT.size)])
            }
        }
        return null
    }

    /** The most Marks one click puts in. */
    private const val PUT_MOST = 640

    /** [w]'s strongbox's lid lifted for a moment, if its land is loaded. */
    private fun lid(level: ServerLevel, w: WorksRec) {
        val rec = WorksBuildData.get(level)[w.id]?.takeIf { it.sited && !it.removed } ?: return
        val site = TownWorksSite(level, rec, WorksPlans.of(rec))
        if (site.drawing !is CountingHouse) return
        val box = StoreLife.fitting<StrongboxBlockEntity>(level, site, CountingHouse.STRONGBOX_AT) ?: return
        if (!box.open) box.lift(LID)
    }

    // ---- Keeping it honest ----

    /** Whether [s]'s screen can stay open; if not, it's closed and false. */
    private fun check(player: ServerPlayer, s: Session): Boolean {
        val gone = !player.isAlive || player.isSpectator || player.level().dimension() != Level.OVERWORLD ||
            player.position().distanceToSqr(s.at) > (REACH + SLACK) * (REACH + SLACK) ||
            TradeHouses.peek(player.level())?.find(s.capital, s.owner) == null
        if (gone) stop(player, s)
        return !gone
    }

    /** Closes [s]'s screen from this side. */
    private fun stop(player: ServerPlayer, s: Session) {
        if (sessions[player.uuid] === s) sessions.remove(player.uuid)
        PacketDistributor.sendToPlayer(player, HouseUpdatePayload(view(player.level(), s, player), closed = true))
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % SWEEP != 0 || sessions.isEmpty()) return
        for (s in sessions.values.toList()) {
            val player = server.playerList.getPlayer(s.player)
            if (player == null) {
                sessions.remove(s.player)
                continue
            }
            if (player.level().gameTime - s.heard > TIMEOUT) stop(player, s) else check(player, s)
        }
    }

    // ---- What the screen shows ----

    private fun send(player: ServerPlayer, s: Session) {
        PacketDistributor.sendToPlayer(player, HouseUpdatePayload(view(player.level(), s, player), closed = false))
    }

    private fun view(level: ServerLevel, s: Session, player: ServerPlayer): HouseView {
        val h = TradeHouses.peek(level)?.find(s.capital, s.owner)
        val clock = level.overworldClockTime
        val hour = Markets.hourOf(level)
        val wage = TradeConfig.carterWage()
        val guardWage = (TradeConfig.guardWage() * 10).roundToInt()
        if (h == null) {
            return HouseView(
                player.name.string, s.place, 0, TradeConfig.house(), false, "", 0, TownControl.marks(player), Houses.FLOAT, 0, listOf(),
                Houses.most(level), "closed", wage, guardWage, Houses.EXTRA_GUARDS, listOf(), 0, 0, 0, 0, 0,
            )
        }
        val carters = h.carters.map { c -> line(h, c, clock) }
        val book = h.book.asReversed().map { b ->
            HouseBookEntry(
                (hour - b.hour).toInt(), b.carter, b.town, Good.entries.filter { b.goods[it.ordinal] > 0 }.map { it.id to b.goods[it.ordinal] },
                b.cost, b.got, b.waylaid, b.lost, b.repair,
            )
        }
        val t = h.totals
        val days = if (t.since == Long.MIN_VALUE) 0 else ((hour - t.since).coerceAtLeast(0) * 10 / 24).toInt()
        return HouseView(
            h.name.ifEmpty { player.name.string }, s.place, h.colour, TradeConfig.house(), Houses.away(h, hour),
            if (h.carters.any { it.free }) h.idleWhy else "", h.purse, TownControl.marks(player), Houses.FLOAT, Houses.takeable(h),
            carters, Houses.most(level), Houses.hireRefusal(level, h) ?: "", wage, guardWage, Houses.EXTRA_GUARDS,
            book, t.trips, t.waylaid, t.net, days, t.wages + t.guardWages,
        )
    }

    /** Carter [c]'s line at the desk: where they are and in how many tenths of an hour that is done, at clock time [clock]. */
    private fun line(h: HouseRec, c: Carter, clock: Long): HouseCarterLine {
        val trip = if (c.trip >= 0) h.trip(c.trip) else null
        val (state, where, until) = when (c.state) {
            Carter.State.IDLE -> Triple(HouseCarterLine.IDLE, "", 0L)
            Carter.State.UNPAID -> Triple(HouseCarterLine.UNPAID, "", 0L)
            Carter.State.LOADING -> Triple(HouseCarterLine.LOADING, trip?.destName ?: "", trip?.departAt ?: 0L)
            Carter.State.ROLLING -> when {
                trip == null -> Triple(HouseCarterLine.OUT, "", 0L)
                trip.stage == Trip.Stage.BACK -> Triple(HouseCarterLine.BACK, trip.destName, trip.backAt)
                else -> Triple(HouseCarterLine.OUT, trip.destName, trip.arriveAt)
            }
        }
        val eta = if (until <= 0L) 0 else ((until - clock).coerceAtLeast(0L) / 100L).toInt()
        return HouseCarterLine(c.id, c.name, Houses.lookOf(c.look), state, where, eta, c.trips, c.earned, c.guards, c.unpaid, c.robbed, c.leaving)
    }
}
