package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleHold
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * The Fourth Wagon's once-a-second care (living-world/stories/design.md 8.3), Tales T3, while Town Tales is on and a player is online:
 *
 *  - **The band's record.** A band broken while it holds the hand ([Wagon.SLAIN]: whoever was at the camp did the deed); the hand cut loose by
 *    a player's knife (the band's record says freed, and the rider never gave the word: [Wagon.knifed]); the rider's word carried out once the
 *    hand's body is about ([WagonCamp.free]); the price lapsing at dusk on its fourth day ([Wagon.LAPSED]).
 *  - **The gate.** The kin waits at the gate's front from 15:00 to 20:00 while the band has the hand (a player within 64 blocks), looking down the
 *    road; the captain stands there from 8:00 to 18:00 while he is to be asked. Once the hand is free the kin goes to the town's board to wait
 *    for them, and the hand, home and thanked, is held where they stand.
 *  - **The rider.** From 6:00 to 20:00, while the price stands and a player is within 64 blocks of the spot, the band's go-between waits at the
 *    parley spot ([Wagon.parley]) on the town's side of the camp. The price paid, they play the parley ([WagonScenes.parley]) and ride back
 *    to the camp, gone once nobody sees them.
 *  - **The reunion.** The hand and the kin together (at the board, or at the gate for the watch's return), the reunion is queued on the
 *    capital's stage and its end ends the tale; not played in a minute, the record ends it.
 *  - **The watch's return** (C). On the market day the watch rode, from half past six, with a player near the gate: the hand, the captain and a
 *    guard of the watch are set down on the road out of sight and walk in to the gate; with nobody near by seven (or if it never comes off by
 *    noon) the record has them home.
 *
 * Everything it keeps is memory and lost on a restart (the holds and figures are the engine's, and the record has the rest).
 */
internal object WagonWatch {
    /** Ticks the reunion may wait to be played once the two are together, before the record ends the tale without it. */
    private const val REUNION_WAITS = 1200L

    /** Ticks the paid rider has to be about and free for the parley, before the band's word is given by the record. */
    private const val PARLEY_WAITS = 600L

    /** Ticks the watch's return has to come in, from the setting down. */
    private const val RETURN_FOR = 3000L

    /** How near a player must be for the kin and the captain to be held at the gate, and the rider at the spot. */
    private const val NEAR = 64.0

    /** How near the gate a player must be for the watch's return to be walked rather than the record's. */
    private const val SEES_GATE = 48.0

    /** How near the bound hand a player in the tale must come for the hand to call out to them. */
    private const val FOUND = 10.0

    /** How far down the road from the gate the watch is set down, at the least. */
    private const val OUT = 40.0

    /** How near a day body must be to its mark to be held there if a player could see it go. */
    private const val MARK_NEAR = 44.0

    /** The captain's hours at the gate. */
    private val CAPTAIN_FROM: Int = Days.clock(8.0)
    private val CAPTAIN_TO: Int = Days.clock(18.0)

    /** When the kin goes to the gate on the morning of the watch's return, and when the watch is set down on the road. */
    private val KIN_MORNING: Int = Days.clock(6.0)
    private val RETURN_FROM: Int = Days.clock(6.5)

    private class Memo {
        /** The marks each slot is held at by the watch. */
        val placed = HashMap<String, BlockPos>()

        /** The parley spot and the rider's way back, once worked out. */
        var spot: BlockPos? = null
        var away: BlockPos? = null

        /** The player who paid, and since when the parley waits (0: none waits); the parley playing. */
        var payer: UUID? = null
        var parleyAt = 0L
        var parley: Stage? = null

        /** Whether the hand has called out to a player who came for them. */
        var found = false

        /** Since when the two have been together for the reunion (-1: not yet); the reunion queued. */
        var together = -1L
        var reunion: Stage? = null

        /** The watch's return; whether `tale wagon dawn` has it now, whatever the hour. */
        var ret: Return? = null
        var forced = false
    }

    /** The watch's return, set down at [at] (game time), the hand's mark [mark] on the road. */
    private class Return(val at: Long, val mark: BlockPos) {
        var walking = false
        var arrived = false
    }

    private val memos = HashMap<Int, Memo>()

    /** One line in `Tales.register`: the commands and scenes, the open wagons seen to once a second, and the hand's guard. */
    fun register() {
        WagonCommands.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java) { WagonCamp.guard(it) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forgetAll() }
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % 20 != 5) return
        if (!Tales.live) {
            WagonCamp.guarded.clear()
            return
        }
        val level = server.overworld()
        WagonCamp.refresh(level)
        WagonCamp.tidy(level)
        if (level.players().isEmpty()) return
        val open = TaleEngine.openTales(level)
        for (t in open.toList()) {
            if (t.template != Wagon.id || !t.open) continue
            TaleLook.guarded(level, "the wagon's second") { second(level, t) }
        }
        if (memos.size > 8) memos.keys.retainAll(open.map { it.id }.toSet())
    }

    private fun second(level: ServerLevel, t: Tale) {
        val c = TaleEngine.ctx(level, t.capital) ?: return
        val memo = memos.getOrPut(t.id) { Memo() }
        record(c, t, memo)
        if (!t.open) return
        rider(c, t, memo)
        if (!t.open) return
        back(c, t, memo)
        if (!t.open || !Tales.scenes()) {
            // No scenes: the hand home is the end
            if (t.open && Wagon.flag(t, Wagon.HOME)) c.end(t, Wagon.outcome(t))
            return
        }
        holds(c, t, memo)
        found(c, t, memo)
        reunion(c, t, memo)
    }

    // ---- The band's record ----

    private fun record(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        if (!Wagon.flag(t, Wagon.TAKEN) || Wagon.flag(t, Wagon.RODE) || Wagon.flag(t, Wagon.HOME)) return
        val band = Wagon.band(level, t)
        if (!Wagon.flag(t, Wagon.SLAIN) && (band == null || band.slain)) {
            Wagon.set(t, Wagon.SLAIN)
            // Whoever was at the camp when it was broken did the deed
            band?.let { b ->
                val at = Vec3.atCenterOf(b.pos)
                for (p in level.players()) {
                    if (!p.isAlive || p.isSpectator || p.distanceToSqr(at) > 64.0 * 64.0) continue
                    t.partFor(p.uuid)?.let { it.did = it.did or Part.WALKED }
                }
            }
            c.why(t, "wagon.slain", Wagon.bandWord(t), Wagon.handName(t))
            c.data.changed()
        }
        // The band has no record of them, and the tale never heard them freed (Tales were off as they came home): they are out of its hands
        if (!Wagon.freed(t) && c.resident(t, Wagon.HAND)?.alive == true && !WagonCamp.held(level, t)) {
            Wagon.set(t, Wagon.OUTSIDE)
            c.why(t, "wagon.own_way", Wagon.handName(t))
            c.data.changed()
            val b = TaleBodies.body(level, t, Wagon.HAND)
            if (b != null && b.captiveOf < 0) Wagon.home(c, t) else if (t.step != Wagon.AT_HOME) c.go(t, Wagon.AT_HOME)
            return
        }
        if (!Wagon.flag(t, Wagon.KNIFE) && !Wagon.flag(t, Wagon.RELEASED) && WagonCamp.freedOnRecord(level, t)) {
            Wagon.knifed(c, t, WagonCamp.cutter(level, t))
            if (!t.open) return
        }
        if (Wagon.flag(t, Wagon.RELEASED) && WagonCamp.bound(level, t) != null) WagonCamp.free(level, t, memo.payer)
        if (!Wagon.flag(t, Wagon.LAPSED) && !Wagon.flag(t, Wagon.PAID) && !Wagon.freed(t) && t.step <= Wagon.AT_FREE && !Wagon.priceStands(level, t)) {
            Wagon.set(t, Wagon.LAPSED)
            c.why(t, "wagon.lapsed", Wagon.bandWord(t), Wagon.price(t).toString())
            c.data.changed()
        }
    }

    // ---- The rider ----

    /** The price is paid: the rider plays the parley as soon as they are about and free (or the band's word is given by the record). */
    fun parley(c: Ctx, t: Tale, player: ServerPlayer) {
        val memo = memos.getOrPut(t.id) { Memo() }
        memo.payer = player.uuid
        memo.parleyAt = c.level.gameTime
    }

    private fun rider(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val band = Wagon.band(level, t)
        memo.parley?.let { st ->
            if (!st.finished) return
            memo.parley = null
        }
        if (memo.parleyAt > 0L) {
            val rider = TaleBodies.body(level, t, Wagon.RIDER)
            if (rider != null && band != null && Tales.scenes() && rider.attending == null && rider.director is TaleHold) {
                val payer = (memo.payer?.let { level.getPlayerByUUID(it) } as? ServerPlayer)?.takeIf { it.distanceTo(rider) < 24.0 }
                val away = memo.away ?: campward(level, t, band).also { memo.away = it }
                val st = WagonScenes.parley(rider, payer, Vec3.atCenterOf(band.pos), away ?: rider.blockPosition()) { released(c, t, memo) }
                if (st.start(level)) {
                    TaleLook.note(st)
                    memo.parley = st
                    memo.parleyAt = 0L
                    return
                }
            }
            if (rider == null || band == null || !Tales.scenes() || level.gameTime - memo.parleyAt > PARLEY_WAITS) {
                memo.parleyAt = 0L
                released(c, t, memo)
            }
            return
        }
        val now = Wagon.clock(level)
        val wanted = band != null && Tales.scenes() && t.step in Wagon.AT_MEET..Wagon.AT_FREE && Wagon.flag(t, Wagon.TAKEN) &&
            !Wagon.freed(t) && !Wagon.flag(t, Wagon.RODE) && Wagon.priceStands(level, t) && now in Wagon.RIDER_FROM until Wagon.DUSK
        if (wanted && band != null) {
            val spot = memo.spot ?: parleySpot(level, t, band)?.also { memo.spot = it } ?: return
            if (level.getNearestPlayer(spot.x + 0.5, spot.y + 0.5, spot.z + 0.5, NEAR, false) == null) return
            val town = BlockPos.of(t.capital)
            val yaw = yawTo(spot, town)
            TaleBodies.figure(level, t, Wagon.RIDER, spot, TownRole.CITIZEN, look = RIDER_LOOK, clip = Activity.ARMS_FOLDED, yaw = yaw)
            return
        }
        goBack(c, t, memo, band)
    }

    /** The rider is not wanted at the spot: they walk back towards the camp and are gone once nobody sees them. */
    private fun goBack(c: Ctx, t: Tale, memo: Memo, band: OutlawBand?) {
        val level = c.level
        if (t.cast(Wagon.RIDER) == null) return
        val rider = TaleBodies.body(level, t, Wagon.RIDER)
        if (rider == null || Comings.unseen(level, rider.blockPosition())) {
            Tales.release(level, t, Wagon.RIDER)
            return
        }
        if (rider.director !is TaleHold || band == null) return
        val away = memo.away ?: campward(level, t, band)?.also { memo.away = it } ?: return
        TaleBodies.figure(level, t, Wagon.RIDER, away, TownRole.CITIZEN, look = RIDER_LOOK, clip = Activity.LOOK_ABOUT)
    }

    /** The band gives its word: the hand is let go (B), and walks home behind the payer as soon as their body is about. */
    private fun released(c: Ctx, t: Tale, memo: Memo) {
        if (!t.open || Wagon.flag(t, Wagon.RELEASED) || Wagon.flag(t, Wagon.KNIFE)) return
        Wagon.set(t, Wagon.RELEASED)
        c.why(t, "wagon.released", Wagon.bandWord(t), Wagon.handName(t))
        c.data.changed()
        WagonCamp.free(c.level, t, memo.payer)
    }

    /** The rider's look: a man of the town's own kind (the band's go-between has no hood of their own in the art yet). */
    private const val RIDER_LOOK = 1

    /** The parley spot on the ground, or null while its land is not loaded. */
    private fun parleySpot(level: ServerLevel, t: Tale, band: OutlawBand): BlockPos? = ground(level, Wagon.parley(t, band))

    /** A dozen blocks back from the parley spot towards the camp's fire (still outside its ring): where the rider goes back to. */
    private fun campward(level: ServerLevel, t: Tale, band: OutlawBand): BlockPos? {
        val spot = Wagon.parley(t, band)
        val dx = (band.pos.x - spot.x).toDouble()
        val dz = (band.pos.z - spot.z).toDouble()
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1.0)
        return ground(level, BlockPos.containing(spot.x + 0.5 + dx / len * 12.0, spot.y.toDouble(), spot.z + 0.5 + dz / len * 12.0))
    }

    private fun ground(level: ServerLevel, at: BlockPos): BlockPos? {
        if (!level.isLoaded(at)) return null
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.x, at.z)
        return Sets.standable(level, BlockPos(at.x, y, at.z))
    }

    private fun yawTo(from: BlockPos, to: BlockPos): Float =
        (Math.toDegrees(kotlin.math.atan2((to.z - from.z).toDouble(), (to.x - from.x).toDouble())) - 90.0).toFloat()

    // ---- The holds ----

    private fun near(level: ServerLevel, at: BlockPos, r: Double): Boolean =
        level.players().any { it.isAlive && !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(at)) < r * r }

    /** Where the kin waits inside the gate for the watch to come in: four blocks in from its front. */
    private fun inside(c: Ctx, t: Tale): BlockPos {
        val front = Wagon.front(c, t)
        return Sets.beside(c.level, Vec3.atCenterOf(front), Vec3.atCenterOf(c.here.capital.center), 4.0) ?: front
    }

    /** Where the kin waits at the board for the hand: three blocks out from it towards the wagon's gate. */
    private fun atBoard(c: Ctx, t: Tale): BlockPos? {
        val board = Wagon.board(c.level, t) ?: return null
        return Sets.beside(c.level, Vec3.atCenterOf(board), Vec3.atCenterOf(Wagon.front(c, t)), 3.0)
    }

    /** Where the captain stands at the gate: beside its front. */
    private fun captainAt(c: Ctx, t: Tale): BlockPos {
        val front = Wagon.front(c, t)
        val side = c.here.capital.direction(Wagon.gate(t)).clockWise
        return Sets.standable(c.level, front.relative(side, 2)) ?: front
    }

    private fun holds(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val now = Wagon.clock(level)
        val front = Wagon.front(c, t)
        val today = c.day
        val freed = Wagon.freed(t)
        val rode = Wagon.flag(t, Wagon.RODE)
        if (Wagon.askerOf(t) == Wagon.KIN) {
            val mark = when {
                rode -> if (t.step == Wagon.AT_HOME && (memo.forced || today > Wagon.comes(t) || today == Wagon.comes(t) && now >= KIN_MORNING)) inside(c, t) else null
                freed -> atBoard(c, t)
                t.step <= Wagon.AT_FREE && now in Wagon.GATE_FROM until Wagon.DUSK && near(level, front, NEAR) -> front
                else -> null
            }
            place(c, t, memo, Wagon.KIN, mark, Activity.LOOKOUT)
        }
        if (!rode) {
            val mark = if (t.step in Wagon.AT_MEET..Wagon.AT_FREE && !freed && now in CAPTAIN_FROM until CAPTAIN_TO && near(level, front, NEAR)) captainAt(c, t) else null
            place(c, t, memo, Wagon.CAPTAIN, mark, Activity.STAND_GUARD)
        }
        if (Wagon.flag(t, Wagon.HOME) && Wagon.HAND !in memo.placed) {
            val b = TaleBodies.body(level, t, Wagon.HAND)
            if (b != null && b.captiveOf < 0 && b.director == null) place(c, t, memo, Wagon.HAND, b.blockPosition(), null)
        }
    }

    /**
     * Holds [slot] at [mark] with [clip] (or lets them go to their day, for a null mark), once for each mark: a day body far from the mark that a
     * player could see go is left to its day and tried again.
     */
    private fun place(c: Ctx, t: Tale, memo: Memo, slot: String, mark: BlockPos?, clip: Activity?) {
        val level = c.level
        val cast = t.cast(slot) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        if (mark == null) {
            if (memo.placed.remove(slot) != null) Tales.release(level, t, slot)
            return
        }
        if (memo.placed[slot] == mark) return
        val body = TaleBodies.body(level, t, slot)
        if (body != null && body.tale != t.id && body.distanceToSqr(Vec3.atCenterOf(mark)) > MARK_NEAR * MARK_NEAR && !Comings.unseen(level, body.blockPosition())) return
        if (Tales.hold(level, t, slot, mark, clip)) memo.placed[slot] = mark
    }

    /** The hand calls out to a player of the tale who comes near them in the camp, once (`tale.wagon.found`). */
    private fun found(c: Ctx, t: Tale, memo: Memo) {
        if (memo.found) return
        val level = c.level
        val body = WagonCamp.bound(level, t) ?: return
        val player = level.players().firstOrNull { p ->
            p.isAlive && !p.isSpectator && p.distanceTo(body) < FOUND && t.part(p.uuid)?.let { it.status == Part.Status.IN || it.status == Part.Status.DONE } == true
        } ?: return
        body.lookAt(player)
        if (Words.sayWith(body, "tale.wagon.found", why = "the wagon's hand found") != null) memo.found = true
    }

    // ---- The reunion ----

    private fun reunion(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        if (t.step != Wagon.AT_HOME) return
        val rode = Wagon.flag(t, Wagon.RODE)
        val ready = if (rode) memo.ret?.arrived == true else Wagon.flag(t, Wagon.HOME)
        if (!ready) return
        // Nobody waits for them: home is home
        if (Wagon.askerOf(t) != Wagon.KIN) return c.end(t, Wagon.outcome(t))
        val now = level.gameTime
        if (memo.together < 0) memo.together = now
        memo.reunion?.let { st ->
            // Queued and not yet taken: the stage's own end ends the tale; one given up does too
            if (!st.finished && now - memo.together < REUNION_WAITS * 2) return
            if (!st.finished) st.cancel(level)
            return c.end(t, Wagon.outcome(t))
        }
        if (now - memo.together > REUNION_WAITS) return c.end(t, Wagon.outcome(t))
        val kin = TaleBodies.body(level, t, Wagon.KIN)?.takeIf { it.tale == t.id } ?: return
        val hand = TaleBodies.body(level, t, Wagon.HAND)?.takeIf { it.tale == t.id && it.captiveOf < 0 } ?: return
        if (kin.distanceTo(hand) > 32.0 || kin.attending != null || hand.attending != null) return
        val decider = t.parts.firstOrNull { it.did(Part.DECIDED) }?.player
        val player = (decider?.let { level.getPlayerByUUID(it) } as? ServerPlayer)?.takeIf { it.isAlive && it.distanceTo(kin) < 32.0 }
            ?: level.getNearestPlayer(hand, 24.0) as? ServerPlayer
        val tippers = if (rode) listOfNotNull(TaleBodies.body(level, t, Wagon.CAPTAIN), TaleBodies.body(level, t, Wagon.WATCH)) else listOf()
        val watchers = Sets.freeAdults(level, kin.position(), 16.0, listOf(kin, hand) + tippers)
        val quiet = Wagon.outcome(t) == Ending.B
        val stage = WagonScenes.reunion(kin, hand, player, watchers, tippers, quiet) ?: return c.end(t, Wagon.outcome(t))
        stage.then = { if (t.open && t.step == Wagon.AT_HOME) TaleEngine.ctx(level, t.capital)?.let { cc -> cc.end(t, Wagon.outcome(t)) } }
        if (TaleLook.queue(level, t.capital, stage, urgent = true, hears = true)) memo.reunion = stage
    }

    // ---- The watch's return ----

    /**
     * The watch's return, once a second at the last step after the ride. The night through nothing is done; from half past six on the day,
     * with a player near the gate (and scenes on), the three are set down on the road out of sight and walk in; with nobody near by seven, or
     * if it has not come in by noon (or in [RETURN_FOR] ticks), the record has the hand home and the tale ends.
     */
    private fun back(c: Ctx, t: Tale, memo: Memo) {
        if (!Wagon.flag(t, Wagon.RODE) || t.step != Wagon.AT_HOME) return
        val level = c.level
        val today = c.day
        val clock = Wagon.clock(level)
        val comes = Wagon.comes(t)
        val r = memo.ret
        val forced = memo.forced
        if (r == null) {
            if (!forced) {
                if (today > comes || today == comes && clock >= Wagon.NOON) return c.end(t, Ending.C)
                if (today < comes || clock < RETURN_FROM) return
            }
            val front = Wagon.front(c, t)
            if (!Tales.scenes() || !near(level, front, SEES_GATE) || !begin(c, t, memo)) {
                if (forced || clock >= Wagon.WATCH_IN) c.end(t, Ending.C)
            }
            return
        }
        if (level.gameTime - r.at > RETURN_FOR || !forced && clock >= Wagon.NOON || !Tales.scenes()) return c.end(t, Ending.C)
        if (r.arrived) return
        val hand = TaleBodies.body(level, t, Wagon.HAND)?.takeIf { it.tale == t.id } ?: return
        if (!r.walking) {
            if (hand.distanceToSqr(Vec3.atCenterOf(r.mark)) > 4.0 * 4.0) return
            r.walking = true
            c.why(t, "wagon.back", t.cast(Wagon.CAPTAIN)?.name.orEmpty(), Wagon.handName(t))
        }
        // A word from a player stops a walk: it is taken up again from where they stand
        if (hand.director !is RoadIn && hand.attending == null) walkIn(c, t, hand, r)
        for (slot in listOf(Wagon.CAPTAIN, Wagon.WATCH)) {
            val b = TaleBodies.body(level, t, slot) ?: continue
            if (b.director !is RoadIn && b.attending == null && b.distanceTo(hand) > 6.0) escortIn(c, t, b, slot)
        }
    }

    /** The watch is set down on the road out of sight: the hand at a road point [OUT] blocks or more from the gate, the captain and a guard beside. */
    private fun begin(c: Ctx, t: Tale, memo: Memo): Boolean {
        val level = c.level
        val front = Wagon.front(c, t)
        val way = WayOut.outward(level, c.here, front, 72)
        if (way.isEmpty()) return false
        val far = way.filter { flat(it, front) >= OUT }
        val mark = far.firstOrNull { Comings.unseen(level, it) } ?: far.lastOrNull() ?: way.last()
        if (!Tales.hold(level, t, Wagon.HAND, mark, null)) return false
        memo.placed[Wagon.HAND] = mark
        val at = Vec3.atCenterOf(mark)
        val gate = Vec3.atCenterOf(front)
        Sets.beside(level, at, gate, 1.6)?.let { if (Tales.hold(level, t, Wagon.CAPTAIN, it, null)) memo.placed[Wagon.CAPTAIN] = it }
        val side = c.here.capital.direction(Wagon.gate(t)).clockWise
        val guardAt = Sets.standable(level, mark.relative(side, 2)) ?: mark
        TaleBodies.figure(level, t, Wagon.WATCH, guardAt, TownRole.GUARD, clip = Activity.STAND_GUARD, yaw = yawTo(guardAt, front))
        memo.ret = Return(level.gameTime, mark)
        return true
    }

    private fun flat(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    /** The hand walks the road in to the gate's front; there they are held for the reunion. */
    private fun walkIn(c: Ctx, t: Tale, hand: Townsperson, r: Return) {
        val level = c.level
        val way = WayOut.homeward(level, c.here, hand.blockPosition())
        if (way.isEmpty()) return c.end(t, Ending.C)
        hand.navigation.stop()
        hand.director = RoadIn(way, RETURN_FOR.toInt()) { _ ->
            if (!t.open) return@RoadIn
            r.arrived = true
            val cc = TaleEngine.ctx(level, t.capital) ?: return@RoadIn
            val front = Wagon.front(cc, t)
            if (Tales.hold(level, t, Wagon.HAND, front, null)) memos[t.id]?.placed?.set(Wagon.HAND, front)
        }
    }

    /** The captain (or the guard of the watch) walks the road in a step behind, and stands by the gate once there. */
    private fun escortIn(c: Ctx, t: Tale, b: Townsperson, slot: String) {
        val level = c.level
        val way = WayOut.homeward(level, c.here, b.blockPosition())
        if (way.isEmpty()) return
        b.navigation.stop()
        b.director = RoadIn(way, RETURN_FOR.toInt()) { p ->
            if (!t.open) return@RoadIn
            val cc = TaleEngine.ctx(level, t.capital) ?: return@RoadIn
            val at = captainAt(cc, t).let { if (slot == Wagon.WATCH) Sets.standable(level, it.relative(cc.here.capital.direction(Wagon.gate(t)).clockWise, 2)) ?: it else it }
            if (slot == Wagon.WATCH) {
                p.director = TaleHold(at, null, Activity.STAND_GUARD)
                TaleBodies.figure(level, t, Wagon.WATCH, at, TownRole.GUARD, clip = Activity.STAND_GUARD)
            } else if (Tales.hold(level, t, slot, at, Activity.STAND_GUARD)) {
                memos[t.id]?.placed?.set(slot, at)
            }
        }
    }

    /** `tale wagon dawn`: the watch's return of [t] is now, whatever the hour (the kin is at the gate for it); false if the watch has not ridden. */
    fun dawn(c: Ctx, t: Tale): Boolean {
        if (!t.open || !Wagon.flag(t, Wagon.RODE) || t.step != Wagon.AT_HOME) return false
        val memo = memos.getOrPut(t.id) { Memo() }
        memo.forced = true
        Wagon.setComes(t, c.day)
        c.data.changed()
        return true
    }

    // ---- The end ----

    /** The tale is ending: the walks and the parley are called off (the engine lets the holds and the figures go after). */
    fun letGo(c: Ctx, t: Tale) {
        val level = c.level
        memos[t.id]?.parley?.let { if (!it.finished) it.cancel(level) }
        for (slot in listOf(Wagon.HAND, Wagon.CAPTAIN, Wagon.WATCH, Wagon.KIN)) {
            val b = TaleBodies.body(level, t, slot) ?: continue
            if (b.director is RoadIn) b.director = null
        }
    }

    /** [slot] of tale [id] is someone else now, or gone: its mark is worked out again. */
    fun forget(id: Int, slot: String) {
        memos[id]?.placed?.remove(slot)
    }

    /** [id] ended: nothing of it is kept. */
    fun ended(id: Int) {
        memos.remove(id)
    }

    /** For `tale wagon`: what the watch has in hand for [t], in a few words. */
    fun state(t: Tale): String {
        val m = memos[t.id] ?: return "nothing yet"
        val out = ArrayList<String>()
        if (m.placed.isNotEmpty()) out += "holding " + m.placed.entries.joinToString { "${it.key} at ${it.value.toShortString()}" }
        m.spot?.let { out += "parley spot ${it.toShortString()}" }
        if (m.parleyAt > 0L) out += "the parley waits"
        if (m.parley?.playing == true) out += "the parley is playing"
        if (m.found) out += "the hand has called out"
        if (m.together >= 0) out += "the two are together"
        if (m.reunion != null) out += "the reunion is on the stage"
        m.ret?.let { out += "the watch is " + if (it.arrived) "in" else if (it.walking) "walking in" else "set down at ${it.mark.toShortString()}" }
        return out.joinToString("; ").ifEmpty { "nothing yet" }
    }

    private fun forgetAll() {
        memos.clear()
        WagonCamp.forgetAll()
        WagonScenes.forget()
    }
}
