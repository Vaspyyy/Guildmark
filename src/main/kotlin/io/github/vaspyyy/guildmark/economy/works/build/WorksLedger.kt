package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.cooking.shop.CookShops
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.works.Ovens
import io.github.vaspyyy.guildmark.economy.works.Reserve
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksKinds
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.HomesteadRegistry
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.network.WorksActionPayload
import io.github.vaspyyy.guildmark.network.WorksClosePayload
import io.github.vaspyyy.guildmark.network.WorksDayLine
import io.github.vaspyyy.guildmark.network.WorksDrawingPayload
import io.github.vaspyyy.guildmark.network.WorksEasedLine
import io.github.vaspyyy.guildmark.network.WorksFortuneView
import io.github.vaspyyy.guildmark.network.WorksLedgerPayload
import io.github.vaspyyy.guildmark.network.WorksLine
import io.github.vaspyyy.guildmark.network.WorksNotYetLine
import io.github.vaspyyy.guildmark.network.WorksPayloads
import io.github.vaspyyy.guildmark.network.WorksPledgeLine
import io.github.vaspyyy.guildmark.network.WorksReasonLine
import io.github.vaspyyy.guildmark.network.WorksRegardCount
import io.github.vaspyyy.guildmark.network.WorksRegardView
import io.github.vaspyyy.guildmark.network.WorksShipLine
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Memory
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.trade.Houses
import io.github.vaspyyy.guildmark.trade.Stores
import io.github.vaspyyy.guildmark.village.StandingTier
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.CustomClickActionEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.Optional
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The works' screen, on the server (living-world/design-influence.md sections 2 to 4, and open question 10): who has
 * which capital's works open, what they're sent, and what they ask.
 *
 * It opens three ways: at a works' post, crate or yard ([openAt]), from the market board's WORKS slip or the works
 * command's list (a click in chat, [onClick]), and when its master builder is asked ([open]). Whoever opens it is
 * paid back first what called-off works owe them (Works.owed). It shows every works the capital offers its reader
 * and those it doesn't want yet; for each, its drawing (sent once: [WorksDrawingPayload]) and its build as it stands,
 * its stages, crew, materials and the wagons bringing them; its pledges; the town's fortunes; and the town's regard
 * for the reader. It's sent again to everyone looking whenever a works changes ([changed]: a pledge, a stage, a load
 * brought), every second while one of the town's works is going up or on its way, and every few seconds otherwise.
 *
 * Everything is decided here: a pledge or a withdrawal asked from the screen is taken only from a reader who has it
 * open, still where they opened it, for a works of that capital that's offered to them, by the amounts the screen
 * offers ([AMOUNTS]), no faster than [ASKS] asks a second; and then Works.pledge and Works.withdraw check the rest
 * (the works on, unpaid, the Marks in the purse, a stall's owner).
 */
object WorksLedger {
    /** What the screen asks ([WorksActionPayload.action]). */
    const val SELECT = 0
    const val REFRESH = 1
    const val PLEDGE = 2
    const val WITHDRAW = 3
    const val CLOSE = 4
    const val PAGE = 5

    /** "The rest" of a works' price, as a pledge's amount. */
    const val REST = -1

    /** The amounts the screen pledges. */
    private val AMOUNTS = setOf(1, 10, 50, REST)

    /** What came of the reader's last ask ([WorksLedgerPayload.tone]): their pledge (the coins heard on the screen, or at the post), the works paid for by it, taken back, refused. */
    const val NONE = 0
    const val PLEDGED = 1
    const val PLEDGED_AT_POST = 2
    const val PAID = 3
    const val WITHDRAWN = 4
    const val REFUSED = 5

    /** The pages, as the screen numbers them ([PAGE]'s arg): the regard is only worked out while its page is open. */
    const val WORKS_PAGE = 0
    const val PLEDGES_PAGE = 1
    const val FORTUNES_PAGE = 2
    const val REGARD_PAGE = 3

    /** How far from where it was opened a reader's asks still count (and past it the screen closes). */
    const val REACH = 32.0

    /** Asks a reader may make in a second. */
    private const val ASKS = 8

    /** Ticks between sends to a reader: while a works of theirs is going up or on its way, otherwise; at soonest after a change. */
    private const val BUSY = 20L
    private const val IDLE = 100L
    private const val SOON = 4L

    /** Ticks a reader's regard is kept before it's worked out again. */
    private const val REGARD_KEPT = 200L

    /** The chat click that opens a works' screen (the board's WORKS slip, the command's list). */
    val CLICK: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "works")

    private class Session(val capital: Long, var selected: Int, val at: Vec3) {
        var page = WORKS_PAGE
        var sent = Long.MIN_VALUE / 2
        var dirty = true
        var busy = false
        var notice: Component = Component.empty()
        var tone = NONE
        var serial = 0

        /** The drawings sent, by works id, and their hashes. */
        val drawings = HashMap<Int, Int>()
        var regard: WorksRegardView? = null
        var regardAt = Long.MIN_VALUE / 2
        var asks = 0
        var window = 0L
    }

    private val sessions = HashMap<UUID, Session>()

    /** Each works' drawing as last worked out, by id. */
    private val drawings = HashMap<Int, WorksDrawingPayload>()

    /** Its payloads, and its readers kept up to date each tick. */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, WorksPayloads::register)
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
    }

    /** A chat click on a WORKS slip or the command's list: that works' screen, for a reader standing in its capital. */
    fun onClick(event: CustomClickActionEvent) {
        if (event.identifier != CLICK) return
        val player = event.player ?: return
        val level = player.level()
        if (!Works.enabled() || level.dimension() != Level.OVERWORLD) return
        val id = (event.payload as? NumericTag)?.intValue() ?: return
        val v = Works.get(level, id) ?: return
        if (Capitals.covering(level, player.blockPosition())?.center?.asLong() != v.capital) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.works.screen_far").withStyle(ChatFormatting.GRAY))
            return
        }
        start(level, player, v.capital, id, player.position())
    }

    /** A clickable line that opens works [id]'s screen ([text], underlined, with its hover). */
    fun link(id: Int, text: MutableComponent): MutableComponent = text.withStyle {
        it.withUnderlined(true)
            .withClickEvent(ClickEvent.Custom(CLICK, Optional.of<Tag>(IntTag.valueOf(id))))
            .withHoverEvent(HoverEvent.ShowText(Component.translatable("message.guildmark.works.screen_open")))
    }

    /** [player] closed the screen, or left. */
    fun close(player: ServerPlayer) {
        sessions.remove(player.uuid)
    }

    /** The server has stopped. */
    fun reset() {
        sessions.clear()
        drawings.clear()
    }

    /** Works [id] is gone: everyone looking is sent their screen without it. */
    fun gone(level: ServerLevel, id: Int) {
        drawings.remove(id)
        for (s in sessions.values) {
            s.dirty = true
            s.drawings.remove(id)
        }
    }

    /** Works [id] has changed: everyone looking at its capital is sent it again. */
    fun changed(level: ServerLevel, id: Int) {
        val capital = Works.get(level, id)?.capital ?: return
        for (s in sessions.values) if (s.capital == capital) s.dirty = true
    }

    /** [player] used the works' post, crate or yard at [pos]: its screen, at that works. */
    fun openAt(level: ServerLevel, player: ServerPlayer, pos: BlockPos) {
        if (!Works.enabled()) return
        val v = Works.at(level, pos) ?: return
        start(level, player, v.capital, v.id, Vec3.atCenterOf(pos))
        level.playSound(null, pos, HomesteadRegistry.LEDGER.get(), SoundSource.BLOCKS, 0.6f, 1.05f)
    }

    /** Works [id]'s screen opened for [player] where they stand (its master asked, or the command). */
    fun open(level: ServerLevel, player: ServerPlayer, id: Int) {
        if (!Works.enabled()) return
        val v = Works.get(level, id) ?: return
        start(level, player, v.capital, id, player.position())
    }

    private fun start(level: ServerLevel, player: ServerPlayer, capital: Long, id: Int, at: Vec3) {
        Works.owed(level, player)
        io.github.vaspyyy.guildmark.tales.Tales.owed(level, player)
        val s = Session(capital, id, at)
        s.page = when (Works.get(level, id)?.state) {
            WorksState.WANTED -> PLEDGES_PAGE
            else -> WORKS_PAGE
        }
        sessions[player.uuid] = s
        send(level, player, s, fresh = true)
    }

    // ---- What the screen asks ----

    /** [player] asked [action] of works [id] with [arg], on their screen: checked here, whatever the screen allowed. */
    fun act(player: ServerPlayer, id: Int, action: Int, arg: Int) {
        val s = sessions[player.uuid] ?: return
        val level = player.level()
        if (action == CLOSE) {
            sessions.remove(player.uuid)
            return
        }
        val now = level.gameTime
        if (now - s.window >= 20L) {
            s.window = now
            s.asks = 0
        }
        if (++s.asks > ASKS) return
        if (!usable(level, player, s)) {
            shut(player, s)
            return
        }
        when (action) {
            SELECT -> if (Works.get(level, id)?.capital == s.capital) s.selected = id
            PAGE -> s.page = arg.coerceIn(WORKS_PAGE, REGARD_PAGE)
            REFRESH -> {}
            PLEDGE -> pledge(level, player, s, id, arg)
            WITHDRAW -> withdraw(level, player, s, id)
            else -> return
        }
        send(level, player, s)
    }

    /** Whether [player] may still use [s]: the works on, alive in the overworld, near where they opened it. */
    private fun usable(level: ServerLevel, player: ServerPlayer, s: Session): Boolean =
        Works.enabled() && level.dimension() == Level.OVERWORLD && player.isAlive && !player.isSpectator && player.position().distanceToSqr(s.at) <= REACH * REACH

    private fun shut(player: ServerPlayer, s: Session) {
        sessions.remove(player.uuid)
        PacketDistributor.sendToPlayer(player, WorksClosePayload(s.capital))
    }

    /** [player] pledges [arg] Marks (or the rest) toward works [id], of their screen's capital and offered to them. */
    private fun pledge(level: ServerLevel, player: ServerPlayer, s: Session, id: Int, arg: Int) {
        if (arg !in AMOUNTS) return
        val v = Works.offered(level, s.capital, player.uuid).firstOrNull { it.id == id } ?: return refuse(s, Component.translatable("gui.guildmark.works.why.gone"))
        val before = v.pledged
        val refusal = Works.pledge(level, player, id, if (arg == REST) Int.MAX_VALUE else arg)
        if (refusal != null) return refuse(s, Works.text(refusal.key, refusal.args.map { it.toString() }))
        val after = Works.get(level, id) ?: return
        val n = after.pledged - before
        val heard = WorksBuildData.get(level)[id]?.let { WorksYard.atPost(level, it, player) } == true
        tell(
            s, if (after.state != WorksState.WANTED) PAID else if (heard) PLEDGED_AT_POST else PLEDGED,
            Component.translatable(if (after.state != WorksState.WANTED) "gui.guildmark.works.notice.paid" else "gui.guildmark.works.notice.pledged", n, Component.translatable(v.kind.key)),
        )
        s.selected = id
    }

    /** [player] takes back what they pledged toward works [id] while it's unpaid. */
    private fun withdraw(level: ServerLevel, player: ServerPlayer, s: Session, id: Int) {
        val v = Works.get(level, id)?.takeIf { it.capital == s.capital } ?: return refuse(s, Component.translatable("gui.guildmark.works.why.gone"))
        if (v.state != WorksState.WANTED) return refuse(s, Component.translatable("gui.guildmark.works.why.spent"))
        if (v.pledges.none { it.player == player.uuid }) return refuse(s, Component.translatable("gui.guildmark.works.why.nothing"))
        val marks = Works.withdraw(level, player, id)
        tell(s, WITHDRAWN, Component.translatable("gui.guildmark.works.notice.withdrawn", marks, Component.translatable(v.kind.key)))
        // Works.withdraw tells no one: the post's seals and everyone looking are told here
        WorksBuildData.get(level)[id]?.let { WorksYard.stamp(level, it) }
        changed(level, id)
    }

    private fun refuse(s: Session, why: Component) = tell(s, REFUSED, why)

    private fun tell(s: Session, tone: Int, notice: Component) {
        s.tone = tone
        s.notice = notice
        s.serial++
    }

    // ---- Keeping readers up to date ----

    private fun tick(server: MinecraftServer) {
        if (sessions.isEmpty()) return
        val level = server.overworld()
        val now = level.gameTime
        val i = sessions.entries.iterator()
        while (i.hasNext()) {
            val (uuid, s) = i.next()
            val player = server.playerList.getPlayer(uuid)
            if (player == null) {
                i.remove()
                continue
            }
            if (player.level() !== level || !usable(level, player, s)) {
                i.remove()
                PacketDistributor.sendToPlayer(player, WorksClosePayload(s.capital))
                continue
            }
            val since = now - s.sent
            if ((s.dirty && since >= SOON) || since >= (if (s.busy) BUSY else IDLE)) send(level, player, s)
        }
    }

    /**
     * [player]'s screen of [s]'s capital as it stands now ([fresh]: opening it), and the drawing of the works they're
     * looking at if they haven't it yet.
     */
    private fun send(level: ServerLevel, player: ServerPlayer, s: Session, fresh: Boolean = false) {
        s.sent = level.gameTime
        s.dirty = false
        val build = WorksBuildData.get(level)
        val offered = Works.offered(level, s.capital, player.uuid).sortedWith(compareBy<WorksView>({ order(it.state) }, { it.kind.ordinal }, { it.id }))
        if (offered.none { it.id == s.selected }) s.selected = offered.firstOrNull()?.id ?: -1
        s.busy = offered.any { it.state == WorksState.FUNDED || it.state == WorksState.BUILDING }
        val lines = offered.map { line(level, player, it, build) }
        val notYet = Works.notYet(level, s.capital, player.uuid).map { n ->
            WorksNotYetLine(n.kind.ordinal, if (n.road >= 0) way(s.capital, n.road) else Component.empty(), Works.text(n.why, n.args))
        }
        // The regard is worked out only while its page is open, and kept a while
        if (s.page == REGARD_PAGE && (s.regard == null || level.gameTime - s.regardAt >= REGARD_KEPT)) {
            s.regard = regard(level, player, s.capital)
            s.regardAt = level.gameTime
        }
        val regard = s.regard ?: WorksRegardView.PENDING
        val town = CaravanRoutes.capital(level, s.capital)?.let { VillageNames.of(it.boardAnchor) } ?: ""
        PacketDistributor.sendToPlayer(
            player,
            WorksLedgerPayload(
                s.capital, town, BlockPos.containing(s.at), s.selected, TownControl.marks(player), Works.enabled(), lines, notYet,
                fortune(level, s.capital), regard, s.notice, s.tone, s.serial, fresh,
            ),
        )
        // The drawing of the works they're looking at, once
        val rec = build[s.selected] ?: return
        val drawing = drawing(level, rec) ?: return
        if (s.drawings[rec.id] == drawing.hash) return
        s.drawings[rec.id] = drawing.hash
        PacketDistributor.sendToPlayer(player, drawing)
    }

    /** Building and paid-for works first, then those wanted, then those standing. */
    private fun order(state: WorksState): Int = when (state) {
        WorksState.BUILDING -> 0
        WorksState.FUNDED -> 1
        WorksState.WANTED -> 2
        WorksState.DONE -> 3
    }

    // ---- A works, as its screen shows it ----

    private fun line(level: ServerLevel, player: ServerPlayer, v: WorksView, build: WorksBuildData): WorksLine {
        val rec = build[v.id]
        val w = Works.record(level, v.id)
        val site = WorksBuild.live(v.id)
        val pledges = v.pledges.map { WorksPledgeLine(it.name, it.marks, it.day, it.player == player.uuid) }
        val patron = v.pledges.indexOfFirst { it.player == v.patron }
        val owner = if (v.kind.per == WorksKind.Per.PLAYER) v.owner?.let { name(level, v, it) } ?: "" else ""
        val used = w?.used?.copyOf() ?: IntArray(Stuff.PAID.size)
        val ships = ships(level, v)
        val sited = rec != null && rec.sited && v.site != null
        val plan = if (rec != null && sited) WorksPlans.of(rec).plan else null
        val starts = plan?.stageStarts?.copyOf() ?: IntArray(0)
        val built = rec?.build
        val total = plan?.steps?.size ?: v.total
        val step: Int
        val bits: LongArray
        when {
            v.state == WorksState.DONE -> {
                step = total
                bits = LongArray(0)
            }
            built != null && plan != null -> {
                step = built.step.coerceIn(0, total)
                bits = built.done.get(step, max(step, total)).toLongArray()
            }
            else -> {
                step = 0
                bits = LongArray(0)
            }
        }
        val laid = if (v.state == WorksState.DONE) total else if (built != null && plan != null) built.step + built.done.cardinality() else v.laid
        val stage = when {
            v.state == WorksState.DONE -> Stage.GROUNDS.ordinal
            plan != null && laid > 0 -> plan.stageOf(step).ordinal
            else -> v.stage?.ordinal ?: -1
        }
        // The crew: the master, the labourers it wants, those at it now, and today's from the town
        val crewKey = -1 - v.id
        val working = BuilderCrew.builders(crewKey).count { !it.leaving }
        val names = WorksBuild.crewOf(level, v.id).mapNotNull { Residents.get(level)[it]?.name }
        val crew = WorksKinds.crew(v.kind)
        val masterBody = MasterBuilder.body(level, v.id)
        val masterAt = when {
            masterBody != null -> 1
            rec?.came == true -> 2
            else -> 0
        }
        val master = rec?.let { MasterBuilder.nameOf(it) } ?: Component.empty()
        val eta = if (site != null && v.state != WorksState.DONE && v.state != WorksState.WANTED) ((site.total - site.laid) / max(1e-6, site.rate())).roundToInt() else -1
        val (waiting, alarm) = waiting(level, v, w, site, ships)
        val drawing = if (rec != null && sited) hash(rec) else 0
        return WorksLine(
            v.id, v.kind.ordinal, if (v.road >= 0) way(v.capital, v.road) else Component.empty(), owner, v.state.ordinal,
            v.price, v.parts.entries.map { it.key to it.value }, pledges, patron,
            Works.text(v.why), v.wanted, v.begun, v.done,
            stage, laid, total, starts,
            v.bill, v.here, v.coming, used, ships,
            sited, status(level, v, w), waiting, alarm,
            master, masterAt, crew, working, names, (crew - names.size).coerceAtLeast(0),
            eta, step, bits, drawing, v.pledges.filter { it.player == player.uuid }.sumOf { it.marks }, doing(level, v),
            Works.lastEased(level, v.id).entries.map { it.key to it.value },
        )
    }

    /** A player's name as the world knows it, from their pledges, or the server's own record. */
    private fun name(level: ServerLevel, v: WorksView, player: UUID): String? =
        v.pledges.firstOrNull { it.player == player }?.name
            ?: level.server.playerList.getPlayer(player)?.name?.string
            ?: level.server.services().nameToIdCache().get(player).map { it.name }.orElse(null)

    /** [capital]'s road [road] in words: "the north road". */
    private fun way(capital: Long, road: Int): Component = Component.translatable("road.guildmark.way." + FortuneReasons.side(capital, road))

    /** The wagons on their way in with [v]'s materials: where each is, and when it's due. */
    private fun ships(level: ServerLevel, v: WorksView): List<WorksShipLine> {
        val ships = Works.shipments(level, v.id)
        if (ships.isEmpty()) return listOf()
        val caravans = CaravanData.get(level).caravans
        val now = level.overworldClockTime
        return ships.sortedBy { it.due }.map { s ->
            val c = caravans.firstOrNull { it.id == s.caravan }
            val travel = c?.let { Caravans.ticksFor(it.length) } ?: 0L
            val along = if (c == null || travel <= 0L) 0f else ((now - (s.due - travel)).toFloat() / travel).coerceIn(0f, 1f)
            val phase = when (c?.phase) {
                Phase.WAITING -> WorksShipLine.WAITING
                Phase.STRUCK -> WorksShipLine.STRUCK
                else -> WorksShipLine.ROLLING
            }
            WorksShipLine(s.caravan, way(v.capital, s.road), s.stuff.copyOf(), Caravans.clockOf(Math.floorDiv(s.due, 1000L)), s.due - now, if (phase == WorksShipLine.WAITING) 0f else along, phase)
        }
    }

    /** What's to say of [v]'s site: none found yet, none found for days (and when it's called off), or something in its way. */
    private fun status(level: ServerLevel, v: WorksView, w: io.github.vaspyyy.guildmark.economy.works.WorksRec?): Component {
        if (v.state == WorksState.DONE) return if (v.done >= 0) Component.translatable("gui.guildmark.works.status.standing", v.done) else Component.empty()
        if (v.site != null) return Component.empty()
        WorksSiting.inWay(v.id)?.let { at -> return Component.translatable("gui.guildmark.works.status.in_way", at.x, at.y, at.z) }
        if (w == null) return Component.empty()
        return when {
            w.siteLost >= 0 -> Component.translatable("gui.guildmark.works.status.lost", w.noRoom, w.siteLost + Works.SITE_LOST_DAYS)
            w.noRoom >= 0 -> Component.translatable("gui.guildmark.works.status.no_room")
            v.kind == WorksKind.TOWER -> Component.translatable("gui.guildmark.works.status.tower")
            else -> Component.translatable("gui.guildmark.works.status.looking")
        }
    }

    /** What holds [v]'s work up now, in words, and whether it's bad enough to show red (materials run out): empty while nothing does. */
    private fun waiting(
        level: ServerLevel, v: WorksView, w: io.github.vaspyyy.guildmark.economy.works.WorksRec?, site: TownWorksSite?, ships: List<WorksShipLine>,
    ): Pair<Component, Boolean> {
        val none = Component.empty() to false
        if (w == null || (v.state != WorksState.FUNDED && v.state != WorksState.BUILDING)) return none
        if (v.site == null) return none
        val arrived = w.here.any { it > 0 } || w.used.any { it > 0 }
        if (!arrived) {
            val first = ships.firstOrNull()
            return (if (first != null) Component.translatable("gui.guildmark.works.wait.first", first.road, clock(first.clock)) else Component.translatable("gui.guildmark.works.wait.first_none")) to false
        }
        if (site == null) return none
        site.blocked?.let { at -> return Component.translatable("gui.guildmark.works.wait.blocked", at.x, at.y, at.z) to false }
        site.short?.let { stuff ->
            val i = Stuff.PAID.indexOf(stuff)
            val name = Component.translatable("gui.guildmark.homestead.stuff.${stuff.key}")
            val ship = ships.firstOrNull { i >= 0 && it.stuff.getOrElse(i) { 0 } > 0 }
            return (
                if (ship != null) Component.translatable("gui.guildmark.works.wait.short_coming", name, ship.road, ship.stuff[i], clock(ship.clock))
                else Component.translatable("gui.guildmark.works.wait.short", name)
                ) to true
        }
        if (!site.workday()) return Component.translatable("gui.guildmark.works.wait.night") to false
        return none
    }

    private fun clock(hour: Int): String = "%02d:00".format(hour)

    /** What [v] does now it stands, in a line; empty while it doesn't. */
    private fun doing(level: ServerLevel, v: WorksView): Component {
        if (v.state != WorksState.DONE) return Component.empty()
        val entry = Markets.get(level)[BlockPos.of(v.capital)] ?: return Component.empty()
        val m = entry.market
        val town = entry.town
        return when (v.kind) {
            WorksKind.OVEN -> Component.translatable(
                "gui.guildmark.works.doing.oven", Ovens.working(town), (Ovens.bake(m, town) * Economy.BAKE_HOURS).roundToInt(), town.use(Good.BREAD).roundToInt(),
            )
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> {
                val room = Reserve.room(town)
                val held = Good.entries.filter { room[it.ordinal] > 0.0 }.sumOf { m[Site.GRANARY, it] }
                Component.translatable("gui.guildmark.works.doing.granary", held.roundToInt(), room.sum().roundToInt())
            }
            WorksKind.TOWER -> {
                val watch = Works.watch(level, v.capital, v.road) ?: return Component.translatable("gui.guildmark.works.doing.tower_road")
                val clock = io.github.vaspyyy.guildmark.economy.Conditions(m.hour).clock
                Component.translatable("gui.guildmark.works.doing.tower", watch.along.roundToInt(), watch.sight.roundToInt(), Works.watchmen(level, v.capital, v.road, clock))
            }
            WorksKind.STALL -> {
                val owner = v.owner ?: return Component.empty()
                val s = OwnStalls.get(level).find(v.capital)?.pitches?.get(owner) ?: return Component.empty()
                Component.translatable("gui.guildmark.works.doing.stall", s.goods().size, s.till.roundToInt())
            }
            WorksKind.COOKSHOP -> CookShops.doing(level, v.capital, v.owner)
            WorksKind.STORE -> Stores.doing(level, v.capital, v.owner)
            WorksKind.COUNTING_HOUSE -> Houses.doing(level, v.capital, v.owner)
        }
    }

    // ---- The drawing ----

    /** Works [rec]'s drawing's hash: the same while its record says the same. */
    private fun hash(rec: BuildRec): Int = (rec.signature().hashCode() * 31 + WorksPlans.of(rec).plan.steps.size).let { if (it == 0) 1 else it }

    /**
     * [rec]'s drawing as its screen draws it: its front, block by block as each step lays it, those behind one another
     * front first, each in its colour shaded by how far back it stands; scaffolding, stakes and the bough with the step
     * that takes them down again. Null until it's sited.
     */
    private fun drawing(level: ServerLevel, rec: BuildRec): WorksDrawingPayload? {
        if (!rec.sited) return null
        val hash = hash(rec)
        drawings[rec.id]?.let { if (it.hash == hash) return it }
        val planned = WorksPlans.of(rec)
        val site = WorksBuild.site(rec.id)?.takeIf { it.planned === planned } ?: TownWorksSite(level, rec, planned)
        val steps = planned.plan.steps
        // What takes each scaffold pole, stake and bough down again, by where it stands
        val downs = HashMap<Long, Int>()
        for ((i, s) in steps.withIndex()) {
            if (s.kind == StepKind.UNSCAFFOLD || s.kind == StepKind.PULL_STAKE || s.kind == StepKind.UNTOP) downs.putIfAbsent(BlockPos.asLong(s.x, s.y, s.z), i)
        }
        class E(val col: Int, val row: Int, val z: Int, val step: Int, val gone: Int, val color: Int)
        val shown = ArrayList<E>()
        var x0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y0 = Int.MAX_VALUE
        var y1 = Int.MIN_VALUE
        var z0 = Int.MAX_VALUE
        var z1 = Int.MIN_VALUE
        val raw = ArrayList<IntArray>()
        for ((i, s) in steps.withIndex()) {
            val style = when (s.kind) {
                StepKind.BLOCK -> SOLID
                StepKind.SCAFFOLD -> SCAFFOLD
                StepKind.STAKE, StepKind.TOPPING -> POLE
                else -> continue
            }
            val state = if (s.kind == StepKind.BLOCK && s.b.m == io.github.vaspyyy.guildmark.city.plan.M.AIR) continue else site.stateFor(i)
            if (state.isAir) continue
            val rgb = colour(state)
            if (rgb < 0) continue
            val gone = if (style == SOLID) -1 else downs[BlockPos.asLong(s.x, s.y, s.z)] ?: -1
            raw += intArrayOf(s.x, s.y, s.z, i, gone, rgb, style)
            x0 = minOf(x0, s.x)
            x1 = maxOf(x1, s.x)
            y0 = minOf(y0, s.y)
            y1 = maxOf(y1, s.y)
            z0 = minOf(z0, s.z)
            z1 = maxOf(z1, s.z)
        }
        if (raw.isEmpty()) return null
        val width = (x1 - x0 + 1).coerceAtMost(64)
        val height = (y1 - y0 + 1).coerceAtMost(96)
        for (r in raw) {
            val col = x1 - r[0]
            val row = r[1] - y0
            if (col !in 0 until width || row !in 0 until height) continue
            val depth = r[2] - z0
            val shade = if (r[6] == SOLID) (1f - 0.075f * depth).coerceAtLeast(0.5f) else 1f
            val c = (r[6] shl 24) or (scale(r[5], shade) and 0xFFFFFF)
            shown += E(col, row, depth, r[3], r[4], c)
        }
        val cells = width * height
        val byCell = shown.groupBy { it.row * width + it.col }
        val starts = IntArray(cells + 1)
        val outSteps = ArrayList<Int>()
        val outColors = ArrayList<Int>()
        var n = 0
        for (c in 0 until cells) {
            starts[c] = n
            val list = byCell[c]?.sortedWith(compareBy({ it.z }, { it.step })) ?: continue
            for (e in list.take(127)) {
                // A scaffold pole, stake or bough with no step to take it down shows only as laid: its step and none
                outSteps += e.step
                outSteps += e.gone
                outColors += e.color
                n++
            }
        }
        starts[cells] = n
        val stepArr = IntArray(n) { outSteps[it * 2] }
        val goneArr = IntArray(n) { outSteps[it * 2 + 1] }
        val colors = IntArray(n) { outColors[it] }
        val d = planned.drawing
        val payload = WorksDrawingPayload(
            rec.id, hash, rec.origin, rec.front.get2DDataValue(), x1, z0, z1, y0, d.y0 - y0, width, height, starts, stepArr, goneArr, colors,
        )
        drawings[rec.id] = payload
        return payload
    }

    /** How a cell is drawn ([WorksDrawingPayload.colors]' top byte): a block, a scaffold pole's lattice, a slim post (a stake, the bough). */
    const val SOLID = 0xFF
    const val SCAFFOLD = 0x01
    const val POLE = 0x02

    /** [state]'s colour as a map draws it (glass pale, a light warm), or -1 for none. */
    private fun colour(state: BlockState): Int {
        val mc = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        if (mc != MapColor.NONE) return mc.col and 0xFFFFFF
        return when {
            state.soundType == SoundType.GLASS -> 0xA9CFDA
            state.lightEmission > 0 -> 0xF0C060
            else -> -1
        }
    }

    private fun scale(rgb: Int, k: Float): Int {
        val r = ((rgb shr 16 and 0xFF) * k).roundToInt().coerceIn(0, 255)
        val g = ((rgb shr 8 and 0xFF) * k).roundToInt().coerceIn(0, 255)
        val b = ((rgb and 0xFF) * k).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    // ---- The fortunes ----

    private fun fortune(level: ServerLevel, capital: Long): WorksFortuneView {
        if (!Fortunes.enabled()) return WorksFortuneView.NONE
        val today = Fortunes.today(level, capital)
        val days = Fortunes.days(level, capital).map { WorksDayLine(it.day, it.score, it.times.ordinal) }
        val reasons = Fortunes.reasons(level, capital).map { r ->
            val left = when {
                r.until < 0 -> -1
                r.until <= today -> 0
                else -> (r.until - today).toInt()
            }
            WorksReasonLine(FortuneReasons.label(r.kind), FortuneReasons.text(r), r.points, left, r.kind == FortuneReasons.EASED || r.kind == FortuneReasons.RAISED)
        }
        // The days a works eased something, each named with its patron as the day's reason was (FortuneReasons.text)
        val eased = Works.all(level, capital).flatMap { w ->
            val patron = w.pledges.firstOrNull { it.player == w.patron }?.name
            val kind = FortuneReasons.works(w.kind.id)
            Works.lastEased(level, w.id).filterKeys { it in EASED }.map { (what, day) ->
                val text = when {
                    what == "band" && patron != null -> Component.translatable("fortune.guildmark.reason.eased.band", kind, way(capital, w.road), patron)
                    what == "band" -> Component.translatable("fortune.guildmark.reason.eased.band.nobody", kind, way(capital, w.road))
                    patron != null -> Component.translatable("fortune.guildmark.reason.eased.$what", kind, patron)
                    else -> Component.translatable("fortune.guildmark.reason.eased.$what.nobody", kind)
                }
                WorksEasedLine(day, text)
            }
        }
        return WorksFortuneView(
            true, Fortunes.times(level, capital).ordinal, Fortunes.score(level, capital), today, Fortunes.since(level, capital),
            Fortunes.from(level, capital).ordinal, days, reasons, eased,
        )
    }

    /** What a works eases (Works.lastEased's keys). */
    private val EASED = setOf("mill", "bread", "band")

    // ---- The regard ----

    /** Why someone thinks well of a player, as the regard counts it. */
    private enum class Warm { TRADED, FUNDED, BROUGHT, FED, DEEDS, FAVOURS, TALKED, STANDING }

    /**
     * How [capital]'s residents regard [player] (open question 10): how many know them how well, and for what: among
     * those who count them a friend or an acquaintance, each thing that warms them (their trade, a works they paid for,
     * materials brought, food given, deeds seen, good turns, talk on many days, their standing at the board); among the
     * wary and the hostile, what weighs most with each (the worst wrong they remember, or a deed they saw).
     */
    private fun regard(level: ServerLevel, player: ServerPlayer, capital: Long): WorksRegardView {
        val pos = BlockPos.of(capital)
        val tier = TownTalk.standing(level, pos, player.uuid)
        val standing = tier?.takeIf { it != StandingTier.STRANGER }?.title ?: Component.empty()
        if (!Memory.enabled()) return WorksRegardView(false, IntArray(Familiarity.entries.size), standing, listOf(), listOf())
        val counts = IntArray(Familiarity.entries.size)
        val warm = IntArray(Warm.entries.size)
        val cool = LinkedHashMap<String, Int>()
        for (r in Residents.get(level).of(pos)) {
            if (!r.alive) continue
            val f = TownTalk.familiarity(level, r, player)
            counts[f.ordinal]++
            if (f != Familiarity.FRIEND && f != Familiarity.ACQUAINTED && f != Familiarity.WARY && f != Familiarity.HOSTILE) continue
            val a = TownTalk.knows(level, r, player.uuid)
            if (f == Familiarity.WARY || f == Familiarity.HOSTILE) {
                val why = a?.worst?.name?.lowercase()?.let { "slight.$it" } ?: if (a?.witnessed?.any { it.tone < 0 } == true) "deeds" else "talk"
                cool.merge(why, 1, Int::plus)
                continue
            }
            val thanked = TownTalk.kept(level, r, player.uuid)?.thanked.orEmpty()
            var any = false
            fun count(w: Warm) {
                warm[w.ordinal]++
                any = true
            }
            if (a != null && a.trades > 0) count(Warm.TRADED)
            if (thanked.any { it.favour == Favour.FUNDED }) count(Warm.FUNDED)
            if (thanked.any { it.favour == Favour.BROUGHT }) count(Warm.BROUGHT)
            if (thanked.any { it.favour == Favour.FED }) count(Warm.FED)
            if (a != null && a.witnessed.any { it.tone > 0 }) count(Warm.DEEDS)
            if (!any && a != null && a.favour > 0.25) count(Warm.FAVOURS)
            if (a != null && a.days >= 3) count(Warm.TALKED)
            if (!any && tier != null && tier.ordinal >= StandingTier.KNOWN.ordinal) count(Warm.STANDING)
        }
        val warmList = Warm.entries.filter { warm[it.ordinal] > 0 }.sortedByDescending { warm[it.ordinal] }
            .map { WorksRegardCount(Component.translatable("gui.guildmark.works.regard.warm.${it.name.lowercase()}"), warm[it.ordinal]) }
        val coolList = cool.entries.sortedByDescending { it.value }
            .map { WorksRegardCount(Component.translatable("gui.guildmark.works.regard.cool.${it.key}"), it.value) }
        return WorksRegardView(true, counts, standing, warmList, coolList)
    }
}
