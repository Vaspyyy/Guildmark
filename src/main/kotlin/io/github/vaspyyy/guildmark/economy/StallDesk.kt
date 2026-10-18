package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.guild.Guilds
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.homestead.Homesteads
import io.github.vaspyyy.guildmark.network.OpenStallPayload
import io.github.vaspyyy.guildmark.network.StallActionPayload
import io.github.vaspyyy.guildmark.network.StallClosePayload
import io.github.vaspyyy.guildmark.network.StallGood
import io.github.vaspyyy.guildmark.network.StallPennantsPayload
import io.github.vaspyyy.guildmark.network.StallRefreshPayload
import io.github.vaspyyy.guildmark.network.StallUpdatePayload
import io.github.vaspyyy.guildmark.network.StallView
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
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
import net.minecraft.util.Prediction
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
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
import kotlin.math.floor

/**
 * Players' own stalls at the market hall tables (see [OwnStalls]), face to face. Using a table (its display, the table
 * itself or the lantern on it) opens its screen: a free table can be rented there for a week by a Trusted Supplier of
 * the capital (see [SupplierLink]), and its renter sets their goods out on it from their pack, takes them back,
 * collects the till, pays for another week or gives it up. Anyone else sees whose it is and what's on it. A player's
 * own stall in the square (see [OwnStall.pitch]) has the same screen ([openPitch]), with no rent: it's their one place
 * in that market, so while they have it they rent no table there.
 *
 * Whatever the screen asks, the server checks everything: the player at the table, the table theirs, the Marks, and
 * the goods their own (none of what they or their party bought at this market in the last week, see
 * [SupplierLink.boughtByParty]). After anything done at a table, every screen open in that hall sees it.
 *
 * The hall's merchants have a word for the renters: when they rent and pay, now and then when their stall's doing
 * well, and when a stall has been let go, whose goods and till they keep until its renter comes for them (using the
 * merchant hands it all over; with no merchant about, the table's screen does).
 */
object StallDesk {
    /** What a table's screen can ask for (see [StallActionPayload]). */
    const val RENT = 0
    const val PAY = 1
    const val GIVE_UP = 2
    const val PUT = 3
    const val TAKE = 4
    const val COLLECT = 5
    const val COLLECT_KEPT = 6

    /** A table free, the player's own, or someone else's (see [StallView.state]). */
    const val FREE = 0
    const val YOURS = 1
    const val TAKEN = 2

    /** How near the table a player has to stand to use it; the screen closes a little further off. */
    const val REACH = 6.0
    private const val SLACK = 1.5

    /** The most goods of different kinds on one stall, and the most of each. */
    const val KINDS = 6
    const val MOST = 256

    /** Ticks a screen may go without a word from its player before it's taken for gone, and fewest ticks between two refreshes. */
    private const val TIMEOUT = 200L
    private const val REFRESH = 10L

    /** Ticks between looks at the open screens, and at the renters about the halls. */
    private const val SWEEP = 10
    private const val ROUND = 40

    /** How near a hall merchant a renter comes for a word from them, and how near the table one has to be to see it rented. */
    private const val WORD = 8.0
    private const val NEAR_TABLE = 16.0

    /** How near the table no hall merchant may be for its screen to hand over what's kept. */
    private const val NO_MERCHANT = 24.0

    /** Marks a stall takes before its merchant says it's doing well, and the fewest ticks between two such words to a renter. */
    private const val WELL = 4.0
    private const val WELL_GAP = 12000L

    val RENTED: SoundEvent = sound("stall.rent")
    val SET_OUT: SoundEvent = sound("stall.set_out")
    val TILL: SoundEvent = sound("stall.till")

    /**
     * A player's open screen at table [t], in the capital called [place] whose centre is [centre]; or, with [t] null,
     * at [owner]'s own stall in its square (see [OwnStall.pitch]).
     */
    private class Session(val player: UUID, val t: StallTables.Table?, val owner: UUID?, val centre: BlockPos, val place: String, now: Long) {
        /** Game time of the last word from the screen, of the last refresh, and of the last thing done at it. */
        var heard = now
        var refreshed = -1L
        var acted = -1L

        /** Its stall in [hall]: the table's, whoever's it is, or the owner's own in the square. */
        fun stall(hall: Hall?): OwnStall? = if (t != null) hall?.stalls?.getOrNull(t.index) else owner?.let { hall?.pitches?.get(it) }
    }

    /** Where [s]'s stall stands, to measure reach from: its table's display, or the owner's own stall's; null if that stall is gone. */
    private fun middle(level: ServerLevel, s: Session): Vec3? =
        s.t?.middle ?: s.stall(OwnStalls.get(level).find(s.centre.asLong()))?.let { Vec3.atCenterOf(it.at) }

    private val sessions = HashMap<UUID, Session>()

    /** The renters a merchant has reminded of what they keep for them, by (renter, capital); and when each renter was last told their stall's doing well. */
    private val reminded = HashSet<Pair<UUID, Long>>()
    private val praised = HashMap<UUID, Long>()

    /** Registers the screen's payloads and the pennants', the tables' and the merchants' use, the checks on open screens, and the clearing up. */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(OpenStallPayload.TYPE, OpenStallPayload.STREAM_CODEC, OpenStallPayload::handle)
                .playToClient(StallUpdatePayload.TYPE, StallUpdatePayload.STREAM_CODEC, StallUpdatePayload::handle)
                .playToClient(StallPennantsPayload.TYPE, StallPennantsPayload.STREAM_CODEC, StallPennantsPayload::handle)
                .playToServer(StallActionPayload.TYPE, StallActionPayload.STREAM_CODEC, StallActionPayload::handle)
                .playToServer(StallRefreshPayload.TYPE, StallRefreshPayload.STREAM_CODEC, StallRefreshPayload::handle)
                .playToServer(StallClosePayload.TYPE, StallClosePayload.STREAM_CODEC, StallClosePayload::handle)
        }
        val bus = NeoForge.EVENT_BUS
        bus.addListener(PlayerInteractEvent.RightClickBlock::class.java, ::onUseBlock)
        bus.addListener(PlayerInteractEvent.EntityInteract::class.java, ::onUseEntity)
        bus.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::close) }
        bus.addListener(ServerStoppedEvent::class.java) { _ ->
            sessions.clear()
            reminded.clear()
            praised.clear()
        }
    }

    /** Whether tables are let and stalls sell: own stalls and the markets both switched on. */
    fun open(): Boolean = OwnStalls.enabled() && Markets.enabled()

    // ---- Stepping up ----

    /** A table used (not crouching): its screen opens, whatever was in hand. Crouching, the table's blocks are used as usual. */
    private fun onUseBlock(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.isShiftKeyDown || player.isSpectator) return
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD) return
        val t = StallTables.at(event.pos) ?: return
        // With stalls switched off a table is only a table, but for anyone with goods or Marks on it still
        if (!open()) {
            val hall = OwnStalls.get(level).find(t.capital.center.asLong())
            if (hall == null || hall.of(player.uuid) < 0 && !hall.kept.containsKey(player.uuid)) return
        }
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        if (event.hand == InteractionHand.MAIN_HAND) open(level, player, t)
    }

    /** Opens table [t]'s screen for [player]. */
    fun open(level: ServerLevel, player: ServerPlayer, t: StallTables.Table) {
        val now = level.gameTime
        val old = sessions[player.uuid]
        val s = if (old != null && old.t === t) old.also { it.heard = now } else Session(player.uuid, t, null, t.capital.center, VillageNames.of(t.capital.boardAnchor), now)
        sessions[player.uuid] = s
        PacketDistributor.sendToPlayer(player, OpenStallPayload(view(level, s, player)))
    }

    /**
     * Opens the screen of [owner]'s own stall in [capital]'s square (see [OwnStall.pitch]) for [player], the same
     * screen as a table's: theirs to stock if it's their own, with no rent to pay or give up. For the stall's blocks to
     * call when they're used (the works build them); false, with nothing opened, if [owner] has no stall there.
     */
    fun openPitch(level: ServerLevel, player: ServerPlayer, capital: CapitalPiece, owner: UUID = player.uuid): Boolean {
        val centre = capital.center
        val stall = OwnStalls.get(level).find(centre.asLong())?.pitches?.get(owner) ?: return false
        val now = level.gameTime
        val old = sessions[player.uuid]
        val s = if (old != null && old.t == null && old.owner == owner && old.centre == centre) old.also { it.heard = now }
        else Session(player.uuid, null, owner, centre, stall.place, now)
        sessions[player.uuid] = s
        PacketDistributor.sendToPlayer(player, OpenStallPayload(view(level, s, player)))
        return true
    }

    /** [player]'s screen asks to see the table as it stands, and says it's still open. */
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

    /** [player] asks for [action] at the table whose screen they have open: with [count] of the good [id] to set out or take back. */
    fun act(player: ServerPlayer, action: Int, id: String, count: Int) {
        val s = sessions[player.uuid] ?: return
        val level = player.level()
        val now = level.gameTime
        s.heard = now
        if (!check(player, s)) return
        // A step too far to reach the table, though not so far that the screen's closed
        if (player.position().distanceToSqr(middle(level, s) ?: return) > REACH * REACH) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.stall.closer"))
            return send(player, s)
        }
        // One thing a tick however fast the clicks come
        if (s.acted == now) return send(player, s)
        s.acted = now
        val data = OwnStalls.get(level)
        val t = s.t
        val hall = data.hall(s.centre.asLong())
        val why = when (action) {
            RENT -> rent(level, player, s, hall)
            PAY -> pay(level, player, s, hall)
            GIVE_UP -> giveUp(level, player, s, hall)
            PUT -> put(level, player, s, hall, Good.byId(id), count)
            TAKE -> take(level, player, s, hall, Good.byId(id), count)
            COLLECT -> collect(level, player, s, hall)
            COLLECT_KEPT -> collectKept(level, player, s, hall)
            else -> null
        }
        if (why != null) player.sendOverlayMessage(Component.translatable(why, s.place, rankName(SupplierLink.TRUSTED, s.place)).withStyle(ChatFormatting.RED))
        data.changed()
        data.tidy()
        if (t != null && level.isLoaded(t.display)) StallTables.sync(level, t, data.find(t.capital.center.asLong())?.stalls?.get(t.index))
        // Everyone with a screen open in this hall sees it, this one first
        send(player, s)
        for (other in sessions.values) if (other !== s && other.centre == s.centre) {
            level.server.playerList.getPlayer(other.player)?.let { send(it, other) }
        }
    }

    /** [player] rents table [s] for a week; the lang key of why not, if they can't. */
    private fun rent(level: ServerLevel, player: ServerPlayer, s: Session, hall: Hall): String? {
        val t = s.t ?: return "gui.guildmark.stall.why.pitch"
        if (hall.stalls[t.index] != null) return "gui.guildmark.stall.why.taken"
        val rank = SupplierLink.rank(level, player.uuid, t.capital.center)
        val keep = keep(rank)
        refusal(player, hall, null, rank, keep)?.let { return it }
        TownControl.pay(player, keep)
        hall.stalls[t.index] = OwnStall(player.uuid, player.name.string, colorOf(level, player.uuid), s.place, t.display, t.front, OwnStalls.WEEK)
        level.playSound(null, t.display, RENTED, SoundSource.BLOCKS, 0.9f, 1f)
        merchantSays(level, t, "stall_rent_${level.random.nextInt(2)}")
        player.sendSystemMessage(Component.translatable("message.guildmark.stall.rented", s.place, keep).withStyle(ChatFormatting.GOLD))
        return null
    }

    /** [player] pays their stall at table [s] another week ahead. */
    private fun pay(level: ServerLevel, player: ServerPlayer, s: Session, hall: Hall): String? {
        val t = s.t ?: return "gui.guildmark.stall.why.pitch"
        val stall = hall.stalls[t.index]?.takeIf { it.renter == player.uuid } ?: return "gui.guildmark.stall.why.not_yours"
        val rank = SupplierLink.rank(level, player.uuid, t.capital.center)
        val keep = keep(rank)
        refusal(player, hall, stall, rank, keep)?.let { return it }
        TownControl.pay(player, keep)
        stall.left += OwnStalls.WEEK
        stall.warned = false
        level.playSound(null, t.display, RENTED, SoundSource.BLOCKS, 0.8f, 1.15f)
        merchantSays(level, t, "stall_paid_0")
        player.sendSystemMessage(Component.translatable("message.guildmark.stall.paid", s.place, keep).withStyle(ChatFormatting.GOLD))
        return null
    }

    /** [player] gives their stall up: what's on it and in its till comes back to them now, and the table is free (no rent comes back). */
    private fun giveUp(level: ServerLevel, player: ServerPlayer, s: Session, hall: Hall): String? {
        val t = s.t ?: return "gui.guildmark.stall.why.pitch"
        val stall = hall.stalls[t.index]?.takeIf { it.renter == player.uuid } ?: return "gui.guildmark.stall.why.not_yours"
        val goods = IntArray(Good.entries.size) { stall.whole(Good.entries[it]) }
        for (g in Good.entries) g.item?.let { give(player, it, goods[g.ordinal]) }
        val marks = floor(stall.till + 1e-6).toInt()
        if (marks > 0) {
            give(player, ModItems.GUILD_MARK.get(), marks)
            SupplierLink.earned(level, player.uuid, t.capital.center, marks)
        }
        hall.stalls[t.index] = null
        level.playSound(null, t.display, SET_OUT, SoundSource.BLOCKS, 0.8f, 0.85f)
        player.sendSystemMessage(Component.translatable("message.guildmark.stall.given_up", s.place, GoodsList.of(goods), marks).withStyle(ChatFormatting.GRAY))
        return null
    }

    /**
     * [player] sets [count] of [good] out on their stall at [s], as many of it as they carry, may sell here (not what
     * they or their party bought at this market this week) and there's room for.
     */
    private fun put(level: ServerLevel, player: ServerPlayer, s: Session, hall: Hall, good: Good?, count: Int): String? {
        val stall = s.stall(hall)?.takeIf { it.renter == player.uuid } ?: return "gui.guildmark.stall.why.not_yours"
        if (!open()) return "gui.guildmark.stall.why.off"
        val item = good?.takeIf { it.traded }?.item ?: return null
        if (stall.whole(good) <= 0 && stall.goods().size >= KINDS) return "gui.guildmark.stall.why.kinds"
        val n = minOf(count, allowed(level, player, s.centre, stall, good))
        if (n <= 0) return if (SupplierLink.boughtByParty(level, player.uuid, s.centre, good) > 0) "gui.guildmark.stall.why.bought" else null
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { it.`is`(item) }, n, false)
        stall.stock[good.ordinal] += n
        level.playSound(null, display(s, stall), SET_OUT, SoundSource.BLOCKS, 0.7f, 0.95f + level.random.nextFloat() * 0.1f)
        return null
    }

    /** [player] takes [count] of [good] back off their stall at [s]. */
    private fun take(level: ServerLevel, player: ServerPlayer, s: Session, hall: Hall, good: Good?, count: Int): String? {
        val stall = s.stall(hall)?.takeIf { it.renter == player.uuid } ?: return "gui.guildmark.stall.why.not_yours"
        val item = good?.item ?: return null
        val n = minOf(count, stall.whole(good))
        if (n <= 0) return null
        stall.stock[good.ordinal] = (stall.stock[good.ordinal] - n).coerceAtLeast(0.0)
        give(player, item, n)
        level.playSound(null, display(s, stall), SET_OUT, SoundSource.BLOCKS, 0.6f, 0.8f + level.random.nextFloat() * 0.1f)
        return null
    }

    /** [player] empties their stall's till, the whole Marks of it (what's left of one waits there). */
    private fun collect(level: ServerLevel, player: ServerPlayer, s: Session, hall: Hall): String? {
        val stall = s.stall(hall)?.takeIf { it.renter == player.uuid } ?: return "gui.guildmark.stall.why.not_yours"
        val marks = floor(stall.till + 1e-6).toInt()
        if (marks <= 0) return null
        stall.till = (stall.till - marks).coerceAtLeast(0.0)
        give(player, ModItems.GUILD_MARK.get(), marks)
        SupplierLink.earned(level, player.uuid, s.centre, marks)
        level.playSound(null, display(s, stall), TILL, SoundSource.BLOCKS, 0.9f, 1f)
        return null
    }

    /** [player] takes back what the hall keeps for them from a stall let go, at the table, with no merchant about to hand it over. */
    private fun collectKept(level: ServerLevel, player: ServerPlayer, s: Session, hall: Hall): String? {
        val kept = hall.kept[player.uuid] ?: return null
        if (merchant(level, s.centre, middle(level, s) ?: return null, NO_MERCHANT) != null) return "gui.guildmark.stall.why.ask_merchant"
        handOver(level, player, s.centre, hall, kept, null)
        return null
    }

    /** Where [s]'s [stall] is set out, for its sounds: its table's display, or the owner's own stall's. */
    private fun display(s: Session, stall: OwnStall): BlockPos = s.t?.display ?: stall.at

    /**
     * Why [player] can't rent or pay for [stall] (null: the table is free) now, as a lang key; null if they can, at
     * their [rank], for [keep] Marks.
     */
    private fun refusal(player: ServerPlayer, hall: Hall, stall: OwnStall?, rank: Int, keep: Int): String? = when {
        !open() -> "gui.guildmark.stall.why.off"
        stall != null && stall.renter != player.uuid -> "gui.guildmark.stall.why.taken"
        // Their own stall in the square is their one place in this market (economy/works)
        stall == null && hall.pitches.containsKey(player.uuid) -> "gui.guildmark.stall.why.own_stall"
        stall == null && hall.of(player.uuid) >= 0 -> "gui.guildmark.stall.why.other_table"
        stall == null && rank < SupplierLink.TRUSTED -> "gui.guildmark.stall.why.rank"
        stall != null && stall.left > OwnStalls.AHEAD -> "gui.guildmark.stall.why.ahead"
        TownControl.marks(player) < keep -> "gui.guildmark.stall.why.marks"
        else -> null
    }

    /** How many of [good] [player] may set out on [stall] at [t] now: what they carry, less what they or their party bought here this week, as far as there's room. */
    private fun allowed(level: ServerLevel, player: ServerPlayer, centre: BlockPos, stall: OwnStall, good: Good): Int {
        val item = good.item ?: return 0
        val own = Trading.count(player.inventory, item) - SupplierLink.boughtByParty(level, player.uuid, centre, good)
        return minOf(own, MOST - stall.whole(good)).coerceAtLeast(0)
    }

    /** A week's keep at [rank]: less for a Purveyor. */
    fun keep(rank: Int): Int = if (rank >= SupplierLink.PURVEYOR) OwnStalls.KEEP_PURVEYOR else OwnStalls.KEEP

    /** [rank]'s name as a supplier of the capital called [place]: "Trusted Supplier", "Purveyor to Hollowmere". */
    fun rankName(rank: Int, place: String): Component = Component.translatable("gui.guildmark.stall.rank.${rank.coerceIn(0, SupplierLink.PURVEYOR)}", place)

    /** [player]'s colour, for their pennant: their homestead's, else their guild's, else one of their own. */
    fun colorOf(level: ServerLevel, player: UUID): Int {
        Homesteads.of(level, player)?.let { return it.color }
        Guilds.get(level).guilds.firstOrNull { it.leader == player || player in it.members }?.let { return it.color.id }
        return Math.floorMod(player.hashCode(), 16)
    }

    // ---- The hall's merchants ----

    /** Using a hall merchant who keeps something for the player from a stall let go: they hand it all over. */
    private fun onUseEntity(event: PlayerInteractEvent.EntityInteract) {
        val player = event.entity as? ServerPlayer ?: return
        val p = event.target as? Townsperson ?: return
        if (event.hand != InteractionHand.MAIN_HAND || player.isSpectator) return
        val level = player.level()
        val centre = Days.life(p)?.places?.capital?.center ?: return
        if (!hallMerchant(p, centre)) return
        val hall = OwnStalls.get(level).find(centre.asLong()) ?: return
        val kept = hall.kept[player.uuid] ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        handOver(level, player, centre, hall, kept, p)
        OwnStalls.get(level).tidy()
    }

    /** [player] has back what [hall] kept for them ([kept]), from [p] if a merchant hands it over. */
    private fun handOver(level: ServerLevel, player: ServerPlayer, centre: BlockPos, hall: Hall, kept: Kept, p: Townsperson?) {
        hall.kept.remove(player.uuid)
        reminded.remove(player.uuid to hall.capital)
        for (g in Good.entries) g.item?.let { give(player, it, kept.goods[g.ordinal]) }
        val marks = floor(kept.marks + 1e-6).toInt()
        if (marks > 0) {
            give(player, ModItems.GUILD_MARK.get(), marks)
            SupplierLink.earned(level, player.uuid, centre, marks)
        }
        OwnStalls.get(level).changed()
        if (p != null) {
            p.lookAt(player)
            p.attend(player, 60)
            if (p.director == null) p.act(Activity.HAND_OVER)
            p.say("stall_gone_1", force = true)
        }
        level.playSound(null, player.blockPosition(), TILL, SoundSource.PLAYERS, 0.7f, 1.1f)
        player.sendSystemMessage(Component.translatable("message.guildmark.stall.handed_back", kept.place, GoodsList.of(kept.goods), marks).withStyle(ChatFormatting.GOLD))
    }

    /** Whether [p] is one of the market hall's merchants in the capital whose centre is [centre]. */
    private fun hallMerchant(p: Townsperson, centre: BlockPos): Boolean {
        if (p.role != TownRole.MERCHANT || !p.isAlive) return false
        val places = Days.life(p)?.places ?: return false
        if (places.capital.center != centre) return false
        return places.lodgingAt(p.post ?: p.work ?: p.blockPosition())?.kind == Kind.MARKET_HALL.name
    }

    /** The nearest of the capital's hall merchants within [range] of [near], awake; null if none is. */
    private fun merchant(level: ServerLevel, centre: BlockPos, near: Vec3, range: Double): Townsperson? =
        level.getEntitiesOfClass(Townsperson::class.java, AABB(near, near).inflate(range)) { !it.isSleeping && hallMerchant(it, centre) }
            .minByOrNull { it.distanceToSqr(near) }

    /** The hall merchant nearest table [t], if one's near enough to see it, says [line]. */
    private fun merchantSays(level: ServerLevel, t: StallTables.Table, line: String) {
        merchant(level, t.capital.center, t.middle, NEAR_TABLE)?.say(line, force = true)
    }

    // ---- Keeping it honest, and keeping the renters told ----

    /** Whether [s]'s screen can stay open; if not, it's closed and false. */
    private fun check(player: ServerPlayer, s: Session): Boolean {
        val gone = !player.isAlive || player.isSpectator || player.level().dimension() != Level.OVERWORLD ||
            (middle(player.level(), s)?.let { player.position().distanceToSqr(it) > (REACH + SLACK) * (REACH + SLACK) } ?: true)
        if (gone) stop(player, s)
        return !gone
    }

    /** Closes [s]'s screen from this side. */
    private fun stop(player: ServerPlayer, s: Session) {
        if (sessions[player.uuid] === s) sessions.remove(player.uuid)
        PacketDistributor.sendToPlayer(player, StallUpdatePayload(view(player.level(), s, player), closed = true))
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

    /**
     * Tells each renter about now of their stall let go or nearly out of rent, and has a hall merchant near a renter
     * say a word: what they keep for them, or that their stall's doing well.
     */
    private fun round(server: MinecraftServer) {
        val level = server.overworld()
        val data = OwnStalls.get(level)
        for (hall in data.all()) {
            for ((renter, kept) in hall.kept) {
                if (kept.told) continue
                val player = server.playerList.getPlayer(renter) ?: continue
                player.sendSystemMessage(Component.translatable("message.guildmark.stall.let_go", kept.place).withStyle(ChatFormatting.GOLD))
                kept.told = true
                data.changed()
            }
            for (stall in hall.stalls) {
                if (stall == null || stall.warned || stall.left > OwnStalls.WARN) continue
                val player = server.playerList.getPlayer(stall.renter) ?: continue
                player.sendSystemMessage(Component.translatable("message.guildmark.stall.running_out", stall.place).withStyle(ChatFormatting.GOLD))
                stall.warned = true
                data.changed()
            }
        }
        for (player in level.players()) {
            if (player.isSpectator) continue
            val capital = Furnish.capitalAt(level, player.blockPosition()) ?: continue
            val hall = data.find(capital.center.asLong()) ?: continue
            word(level, player, capital.center, hall)
        }
    }

    /** A hall merchant near [player] reminds them of what they keep for them, or now and then says their stall's doing well. */
    private fun word(level: ServerLevel, player: ServerPlayer, centre: BlockPos, hall: Hall) {
        val kept = hall.kept.containsKey(player.uuid)
        val stall = hall.stalls.firstOrNull { it?.renter == player.uuid }
        if (!kept && (stall == null || stall.takings < WELL)) return
        val m = merchant(level, centre, player.position(), WORD) ?: return
        if (kept) {
            if (!reminded.add(player.uuid to hall.capital)) return
            m.lookAt(player)
            m.say("stall_gone_0", force = true)
            return
        }
        val now = level.gameTime
        if (stall == null || now - (praised[player.uuid] ?: Long.MIN_VALUE / 2) < WELL_GAP) return
        if (!m.say("stall_well_${level.random.nextInt(2)}")) return
        m.lookAt(player)
        praised[player.uuid] = now
        stall.takings = 0.0
        OwnStalls.get(level).changed()
    }

    // ---- What the screen shows ----

    private fun send(player: ServerPlayer, s: Session) {
        PacketDistributor.sendToPlayer(player, StallUpdatePayload(view(player.level(), s, player), closed = false))
    }

    private fun view(level: ServerLevel, s: Session, player: ServerPlayer): StallView {
        val t = s.t
        val centre = s.centre
        val hall = OwnStalls.get(level).find(centre.asLong())
        val stall = s.stall(hall)
        val state = when {
            stall == null -> FREE
            stall.renter == player.uuid -> YOURS
            else -> TAKEN
        }
        val rank = SupplierLink.rank(level, player.uuid, centre)
        val keep = keep(rank)
        val kept = hall?.kept?.get(player.uuid)
        val entry = Markets.get(level)[centre]
        val shown = Good.TRADED.filter { g ->
            val item = g.item ?: return@filter false
            stall != null && stall.whole(g) > 0 || state != TAKEN && Trading.count(player.inventory, item) > 0
        }
        val goods = shown.map { g ->
            val carried = if (state == TAKEN) 0 else Trading.count(player.inventory, g.item!!)
            val lot = entry?.let { Lots.cheap(it.market.price(it.town, g) * (1.0 - OwnStalls.CUT)) }
            StallGood(
                g.id, stall?.whole(g) ?: 0, carried,
                if (state == YOURS && (stall!!.whole(g) > 0 || stall.goods().size < KINDS)) allowed(level, player, centre, stall, g) else 0,
                if (state == TAKEN) 0 else SupplierLink.boughtByParty(level, player.uuid, centre, g),
                lot?.count ?: 0, lot?.marks ?: 0, stall?.let { floor(it.today[g.ordinal] + 1e-6).toInt() } ?: 0,
            )
        }
        // An own stall in the square shows as table -1: no rent to pay, nothing to refuse
        return StallView(
            s.place, t?.index ?: -1, state, stall?.name ?: "", stall?.color ?: colorOf(level, player.uuid), stall?.left ?: 0, keep, rank,
            if (state == TAKEN || t == null) "" else refusal(player, hall ?: Hall(centre.asLong()), stall, rank, keep) ?: "", TownControl.marks(player),
            if (state == YOURS) floor(stall!!.till + 1e-6).toInt() else 0, kept?.goods?.sum() ?: 0, kept?.let { floor(it.marks + 1e-6).toInt() } ?: 0,
            kept != null && middle(level, s)?.let { merchant(level, centre, it, NO_MERCHANT) } != null, open(), goods,
        )
    }

    private fun give(player: ServerPlayer, item: Item, count: Int) {
        var left = count
        val most = ItemStack(item).maxStackSize
        while (left > 0) {
            val n = minOf(left, most)
            player.inventory.placeItemBackInInventory(ItemStack(item, n), Prediction.SERVER_ONLY)
            left -= n
        }
    }

    private fun sound(name: String): SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))
}
