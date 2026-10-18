package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.MarketClosePayload
import io.github.vaspyyy.guildmark.network.MarketGood
import io.github.vaspyyy.guildmark.network.MarketRefreshPayload
import io.github.vaspyyy.guildmark.network.MarketTradePayload
import io.github.vaspyyy.guildmark.network.MarketUpdatePayload
import io.github.vaspyyy.guildmark.network.MarketView
import io.github.vaspyyy.guildmark.network.OpenMarketPayload
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.VillageNames
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
 * while they're at their work, or standing at their stall, opens the market screen for its goods; out of hours they
 * say they're shut instead. While the screen is open the keeper serves the player (see [Serving]).
 *
 * Whatever the screen asks, the server checks everything a deal needs: the player within [REACH] of the keeper, the
 * keeper alive and still keeping that shop, the markets switched on, and the good one the shop deals in. Deals go
 * through [Trading], so the prices are the market's own, and after each one every screen open on that market gets
 * fresh quotes. `/guildmark market screen <shop>` opens a shop's screen with nobody keeping it, for testing.
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

        /** The goods whose prices the keeper has explained this time. */
        val told = HashSet<Good>()
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
        if (p.role != TownRole.MERCHANT && p.role != TownRole.BAKER && p.role != TownRole.SMITH) return false
        if (!Markets.enabled() || player.isSpectator) return false
        // Something else has charge of them (a job, a festival): theirs to answer for
        val serving = p.director
        if (serving != null && serving !is Serving) return false
        val life = Days.life(p) ?: return false
        val shop = Shop.kept(p) ?: return false
        val doing = Days.stretch(life, level.overworldClockTime).doing
        if (serving == null && (p.isSleeping || doing != Doing.WORK && !atStall(p))) {
            if (!p.isSleeping) p.attend(player, 60)
            p.say(StallTalk.shut(p, life, doing, shop, level), force = true)
            return true
        }
        val entry = Markets.of(level, life.places.capital) ?: return false
        open(player, shop, entry, life.places.capital, p)
        return true
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
            val news = StallTalk.news(shop.goods.filter { it.traded }.map { Trading.quote(entry, it) }, s.told)
            news?.let { s.told.add(it.good) }
            serving.arrive(keeper, player, shop, again, news?.let(StallTalk::price))
        }
        PacketDistributor.sendToPlayer(player, OpenMarketPayload(view(s, entry, player)))
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
        var stop: Deal? = null
        for (i in 0 until lots.coerceIn(1, MANY)) {
            val deal = if (buying) Trading.buy(player, entry, good) else Trading.sell(player, entry, good)
            if (deal.outcome != Deal.Outcome.DONE) {
                stop = deal
                break
            }
            done++
        }
        s.lots += done
        (keeper?.director as? Serving)?.dealt(keeper, player, good, buying, done, stop, Trading.quote(entry, good), s.told)
        // Every screen open on this market sees the prices move, this one first
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

    private fun view(s: Session, entry: Markets.Entry, player: ServerPlayer): MarketView {
        val goods = s.shop.goods.mapNotNull { g ->
            val item = g.item?.takeIf { g.traded } ?: return@mapNotNull null
            val q = Trading.quote(entry, g)
            MarketGood(
                g.id, q.stock, floor(entry.town.target(g)).toInt(), q.buy?.count ?: 0, q.buy?.marks ?: 0, q.sell?.count ?: 0,
                q.sell?.marks ?: 0, q.wants, q.trend, (q.price / q.usual).toFloat(), reason(q), q.why?.dear == true, Trading.count(player.inventory, item),
            )
        }
        return MarketView(s.shop.id, s.keeper?.id ?: -1, s.place, TownControl.marks(player), goods)
    }

    /** The lang key of why [q]'s price is well off its usual (taking the good's name, as [MarketCommand.reason] gives it), or "". */
    private fun reason(q: Quote): String {
        val why = q.why ?: return ""
        return if (q.event != null) "${why.key}.${q.event.id}" else why.key
    }
}

/**
 * A shop's keeper serving the players who have its screen open (see [Stalls]). In place of their day's round they
 * stay where they are and turn to whoever they're dealing with; for each deal they hand the goods over
 * ([Activity.HAND_OVER]) or count out the coin ([Activity.PAY]) to the clink of it, a refusal gets a shrug, and they
 * say what fits (see [StallTalk]). Once the last of them goes they see them off and, a moment later, go back to
 * their day.
 */
class Serving : Director {
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
        if (clinks > 0 && p.tickCount >= clinkAt) {
            clinks--
            clinkAt = p.tickCount + 5
            level.playSound(null, p.x, p.y + 1.0, p.z, COIN, SoundSource.NEUTRAL, 0.5f, 0.9f + p.random.nextFloat() * 0.25f)
        }
        val line = later
        if (line != null && now >= laterAt) {
            later = null
            say(p, line)
        }
        if (leaving in 0..now) Stalls.release(p)
    }

    // A player crouching behind a busy keeper may try their purse; anyone else steps up to the shop
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean =
        !player.isShiftKeyDown && Stalls.interact(p.level() as ServerLevel, player, p)

    /** [player] has stepped up to [shop]: a greeting, then, a little later, [news] (why a price is where it is). */
    fun arrive(p: Townsperson, player: ServerPlayer, shop: Shop, again: Boolean, news: String?) {
        val level = p.level() as? ServerLevel ?: return
        val busy = customers.any { it != player.uuid }
        customers.add(player.uuid)
        facing = player.uuid
        leaving = -1
        say(p, StallTalk.greet(p, Days.life(p), shop, level, busy, again))
        if (!busy) {
            p.carried = ItemStack.EMPTY
            p.act(if (p.random.nextInt(3) == 0) Activity.WAVE else Activity.NOD)
        }
        later = news
        laterAt = level.gameTime + NEWS
    }

    /**
     * A deal at the shop: [player] [buying] or selling [good], [done] lots of it gone through and [stop] what stopped
     * the rest, if anything did. [after] is its quote now; [told] the goods whose prices they've explained to [player].
     */
    fun dealt(p: Townsperson, player: ServerPlayer, good: Good, buying: Boolean, done: Int, stop: Deal?, after: Quote, told: MutableSet<Good>) {
        val now = p.level().gameTime
        facing = player.uuid
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
        val line = StallTalk.deal(good, buying, done, stop, after, told)
            ?: StallTalk.remark(p, buying, done, after.why?.dear == true).takeIf { done > 0 && now - spoke >= CHAT && p.random.nextInt(2) == 0 }
        if (line != null) {
            later = null
            say(p, line)
        }
    }

    /**
     * [player] has gone: [traded] if they bought or sold anything, [walked] if they walked off; the last to go is
     * seen off (with [line], if there's something else to say), and a moment later they go back to their day.
     */
    fun leave(p: Townsperson, player: UUID, traded: Boolean, walked: Boolean, line: String?) {
        customers.remove(player)
        if (facing == player) facing = customers.lastOrNull()
        if (customers.isNotEmpty()) return
        val level = p.level() as? ServerLevel ?: return
        later = null
        say(p, line ?: StallTalk.bye(p, Days.life(p), level, traded, walked))
        p.carried = ItemStack.EMPTY
        p.act(
            when {
                walked -> Activity.WAVE
                traded -> Activity.BOW
                else -> Activity.NOD
            },
        )
        leaving = level.gameTime + LINGER
    }

    private fun say(p: Townsperson, line: String) {
        if (p.say(line, force = true)) spoke = p.level().gameTime
    }

    private companion object {
        /** Degrees a tick they turn toward whoever they're dealing with. */
        const val TURN = 20f

        /** Fewest ticks between two lines of small talk. */
        const val CHAT = 80L

        /** Ticks after the greeting that they say why a price is where it is. */
        const val NEWS = 70L

        /** Ticks they linger, once the last customer's gone, before going back to their day. */
        const val LINGER = 40L

        /** Ticks into a handing over before another deal starts it again. */
        const val RESTART = 14

        /** Ticks into a handing over that the coin clinks. */
        const val CLINK = 10

        val COIN: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.thief.coin"))
    }
}
