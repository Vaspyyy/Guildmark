package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleHold
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.templates.Informer.AT_CHOOSE
import io.github.vaspyyy.guildmark.tales.templates.Informer.AT_TELL
import io.github.vaspyyy.guildmark.tales.templates.Informer.AT_TEST
import io.github.vaspyyy.guildmark.tales.templates.Informer.FRIEND
import io.github.vaspyyy.guildmark.tales.templates.Informer.SCOUT
import io.github.vaspyyy.guildmark.tales.templates.Informer.SERGEANT
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * The Sergeant's Friend's once-a-second care (living-world/stories/design.md 8.10), Tales T3, while Town Tales is on and a player is online:
 *
 *  - **The post.** From 10:00 to 17:00, with a player within 64 blocks of the gate the road leaves by, the sergeant stands at it (STAND_GUARD),
 *    to be asked and to count the wagons on a slate (S1 The Count, the tale's seed scene, [InformerScenes.slate]).
 *  - **First light.** An answer of the sergeant's that a player in the tale has not heard (a decoy struck or not, the band lying low) is said
 *    to them as they come within ten blocks of the sergeant, or in place of how it stands when they talk ([Informer.script]).
 *  - **The dead drop.** At the test, with the friend told and not yet tried, from 18:00 to 19:30 (scenes on, a player within 48 blocks of the
 *    drop): the friend walks out of the gate to a spot on the verge of the road a dozen blocks out, the band's scout comes in along the road to meet
 *    them, and a folded paper passes ([InformerScenes.drop]). A player who comes within eight blocks where the friend can see them (four, if they
 *    crouch) before the paper has passed, and the friend turns for home; a player who speaks to the friend at the drop catches them in the act
 *    ([Informer.topics]). Once a market day, given up after two minutes or at 19:30.
 *  - **The scene.** The way chosen: the Naming in the gate yard (A, 10:00 to 16:00, a player within 48 blocks) or the Handshake at the friend's
 *    door (B, 17:30 to 20:30): the two are held at their marks, the scene is queued on the capital's stage and its end ends the tale. Spoken
 *    to first (the route B), the friend waits on their own step for the player while one is near. With scenes
 *    off the record ends it at once; not seen in two market days, the close ends it ([InformerTrap.close]).
 *
 * Everything it keeps is memory and lost on a restart (the holds and the figures are the engine's, and the record has the rest): a player who
 * heard an answer before a restart may hear it again once.
 */
internal object InformerWatch {
    /** How near a player must be for the sergeant to stand at the post, and the drop and the scenes to be played. */
    private const val NEAR = 64.0
    private const val SEES = 48.0

    /** How near the sergeant a player in the tale must come to be told a fresh answer. */
    private const val TELLS = 10.0

    /** How near a day body must be to its mark to be held there if a player could see it go. */
    private const val MARK_NEAR = 44.0

    /** How near the drop the friend's day body must be for the drop to be tried, and the friend counted at it. */
    private const val DROP_REACH = 90.0
    private const val AT_DROP = 4.0

    /** The drop: blocks out from the gate's front along the road, and off it onto the verge; the scout's spot further out. */
    private const val DROP_OUT = 12.0
    private const val VERGE = 3
    private const val SCOUT_OUT = 18.0

    /** How near (and, crouching, how near) a player the friend can see must be to be seen before the paper passes, and the cone they see in. */
    private const val SEEN_AT = 8.0
    private const val SEEN_CROUCHING = 4.0
    private const val CONE = 0.45

    /** Ticks the drop may take, from the friend setting out; ticks a scene may wait once the two are at their marks. */
    private const val DROP_TICKS = 2400L
    private const val SCENE_WAITS = 1200L

    /** The scout's look: the young thief with his hood up and a scarf over his mouth. */
    private const val SCOUT_LOOK = 1

    private class Memo {
        /** The marks each slot is held at by the watch. */
        val placed = HashMap<String, BlockPos>()

        /** The players who have heard the sergeant's last answer. */
        val heard = HashSet<UUID>()

        /** The dead drop: the market day it was tried; whether the friend is on their way, since when; where; the stage; the paper passed. */
        var dropDay = Long.MIN_VALUE
        var going = false
        var goingSince = 0L
        var dropAt: BlockPos? = null
        var scoutAt: BlockPos? = null
        var drop: Stage? = null
        var handed = false

        /** Whether `tale informer drop` has the drop's hours now, whatever the clock. */
        var dropForced = false

        /** The ending's scene: since when the two have been at their marks (-1: not yet), and the stage queued. */
        var ready = -1L
        var scene: Stage? = null

        /** Whether `tale informer scene` has the scene's hours now, whatever the clock. */
        var forced = false
    }

    private val memos = HashMap<Int, Memo>()

    /** From `WagonWatch.register` (the T3 line in `Tales.register`): the commands and scenes, and the open informers seen to once a second. */
    fun register() {
        InformerCommands.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forgetAll() }
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % 20 != 13) return
        if (!Tales.live) return
        val level = server.overworld()
        if (level.players().isEmpty()) return
        val open = TaleEngine.openTales(level)
        for (t in open.toList()) {
            if (t.template != Informer.id || !t.open) continue
            TaleLook.guarded(level, "the informer's second") { second(level, t) }
        }
        if (memos.size > 8) memos.keys.retainAll(open.map { it.id }.toSet())
    }

    private fun second(level: ServerLevel, t: Tale) {
        val c = TaleEngine.ctx(level, t.capital) ?: return
        val memo = memos.getOrPut(t.id) { Memo() }
        if (InformerTrap.toTell(t) && !Tales.scenes()) return c.end(t, Informer.outcome(t))
        if (!Tales.scenes()) return
        answer(c, t, memo)
        drop(c, t, memo)
        if (!t.open) return
        holds(c, t, memo)
        scene(c, t, memo)
    }

    // ---- The answers ----

    /** Whether [player] has yet to hear the sergeant's last answer. */
    fun fresh(t: Tale, player: UUID): Boolean = Informer.answer(t) != 0 && memos[t.id]?.heard?.contains(player) != true

    /** [player] has heard the sergeant's last answer. */
    fun heard(t: Tale, player: UUID) {
        memos.getOrPut(t.id) { Memo() }.heard += player
    }

    /** The sergeant has a new answer: nobody has heard it yet. */
    fun answered(id: Int) {
        memos[id]?.heard?.clear()
    }

    /** First light: a fresh answer said aloud by the sergeant to a player in the tale who comes near. */
    private fun answer(c: Ctx, t: Tale, memo: Memo) {
        if (t.step != AT_TEST && t.step != AT_CHOOSE) return
        val family = Informer.answerFamily(t) ?: return
        val level = c.level
        val body = TaleBodies.body(level, t, SERGEANT) ?: return
        if (body.attending != null || body.speech.isNotEmpty()) return
        val player = level.players().firstOrNull { p ->
            p.isAlive && !p.isSpectator && p.distanceTo(body) < TELLS && p.uuid !in memo.heard && t.part(p.uuid).let { it != null && (it.status == Part.Status.IN || it.status == Part.Status.DONE) }
        } ?: return
        body.lookAt(player)
        if (Words.sayWith(body, family, detail = Informer.answerDetail(t), why = "the sergeant's answer") != null) memo.heard += player.uuid
    }

    // ---- The dead drop ----

    /** The drop's spot: [DROP_OUT] blocks out along the road from the gate's front, [VERGE] off it to one side; null while the land is not loaded. */
    private fun dropSpot(c: Ctx, t: Tale): BlockPos? {
        val level = c.level
        val front = Informer.front(c, t)
        val way = WayOut.outward(level, c.here, front, 48)
        val on = way.minByOrNull { kotlin.math.abs(flat(it, front) - DROP_OUT) } ?: return null
        val side = c.here.capital.direction(Informer.gate(t)).clockWise
        val off = on.relative(side, VERGE * Informer.verge(t))
        return Sets.standable(level, off) ?: Sets.standable(level, on)
    }

    /** Where the scout waits: [SCOUT_OUT] blocks further out along the road from the drop. */
    private fun scoutSpot(c: Ctx, t: Tale, drop: BlockPos): BlockPos? {
        val level = c.level
        val front = Informer.front(c, t)
        val way = WayOut.outward(level, c.here, front, 72)
        val far = way.minByOrNull { kotlin.math.abs(flat(it, front) - (DROP_OUT + SCOUT_OUT)) } ?: return null
        return Sets.standable(level, far)?.takeIf { flat(it, drop) > 8.0 }
    }

    private fun dropWanted(c: Ctx, t: Tale, memo: Memo): Boolean {
        if (t.step != AT_TEST || Informer.pending(t) != Informer.BIT_FRIEND || Informer.flag(t, Informer.SEEN)) return false
        if (memo.dropDay == c.day) return false
        val now = Informer.clock(c.level)
        return memo.dropForced || now in Informer.DROP_FROM until Informer.DROP_TO
    }

    private fun drop(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val now = Informer.clock(level)
        memo.drop?.let { st ->
            if (st.finished) return endDrop(c, t, memo)
            val friend = TaleBodies.body(level, t, FRIEND)
            if (!memo.handed && friend != null && seenBy(level, friend) != null) {
                st.cancel(level)
                return endDrop(c, t, memo)
            }
            if (level.gameTime - memo.goingSince > DROP_TICKS || (!memo.dropForced && now !in Informer.DROP_FROM until Informer.DROP_TO)) {
                st.cancel(level)
                return endDrop(c, t, memo)
            }
            return
        }
        if (memo.going) {
            val friend = TaleBodies.body(level, t, FRIEND)
            val at = memo.dropAt
            if (friend == null || at == null || t.step != AT_TEST || Informer.pending(t) != Informer.BIT_FRIEND ||
                level.gameTime - memo.goingSince > DROP_TICKS || (!memo.dropForced && now !in Informer.DROP_FROM until Informer.DROP_TO)
            ) return endDrop(c, t, memo)
            // Seen on the way out: they think better of it, and turn for home
            if (seenBy(level, friend) != null) return endDrop(c, t, memo)
            if (friend.tale != t.id) return
            if (memo.placed[FRIEND] != at) {
                if (Tales.hold(level, t, FRIEND, at, Activity.LOOK_ABOUT)) memo.placed[FRIEND] = at
                return
            }
            if (friend.distanceToSqr(Vec3.atCenterOf(at)) > 2.5 * 2.5 || friend.attending != null) return
            val spot = memo.scoutAt ?: return endDrop(c, t, memo)
            TaleBodies.figure(level, t, SCOUT, spot, TownRole.THIEF, look = SCOUT_LOOK, clip = Activity.LOOKOUT, yaw = yawTo(spot, at))
            val scout = TaleBodies.body(level, t, SCOUT) ?: return
            if (scout.director !is TaleHold || scout.attending != null) return
            val away = spot
            val stage = InformerScenes.drop(friend, scout, at, away) { memo.handed = true }
            if (stage.start(level)) {
                TaleLook.note(stage)
                memo.drop = stage
            }
            return
        }
        if (!dropWanted(c, t, memo)) return
        val at = memo.dropAt ?: dropSpot(c, t)?.also { memo.dropAt = it } ?: return
        if (level.getNearestPlayer(at.x + 0.5, at.y + 0.5, at.z + 0.5, SEES, false) == null) return
        val friend = TaleBodies.body(level, t, FRIEND) ?: return
        if (friend.distanceToSqr(Vec3.atCenterOf(at)) > DROP_REACH * DROP_REACH || friend.attending != null || friend.isSleeping) return
        val spot = memo.scoutAt ?: scoutSpot(c, t, at)?.also { memo.scoutAt = it } ?: return
        // The friend is held where they stand (their own body taken in place), and walked to the drop from the next second
        if (!Tales.hold(level, t, FRIEND, friend.blockPosition(), null)) return
        memo.placed[FRIEND] = friend.blockPosition()
        memo.dropDay = c.day
        memo.going = true
        memo.goingSince = level.gameTime
        memo.handed = false
        memo.scoutAt = spot
    }

    /** The first player the friend can see near enough to be seen at the drop, or null. */
    private fun seenBy(level: ServerLevel, friend: Townsperson): ServerPlayer? {
        val look = friend.lookAngle
        val lx = look.x
        val lz = look.z
        val ll = kotlin.math.sqrt(lx * lx + lz * lz).coerceAtLeast(0.01)
        for (p in level.players()) {
            if (!p.isAlive || p.isSpectator) continue
            val reach = if (p.isCrouching) SEEN_CROUCHING else SEEN_AT
            val d = p.distanceTo(friend).toDouble()
            if (d > reach) continue
            val dx = p.x - friend.x
            val dz = p.z - friend.z
            val dl = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.01)
            // Right by them, they are seen whichever way the friend looks
            if (d > 2.0 && (lx * dx + lz * dz) / (ll * dl) < CONE) continue
            if (!friend.hasLineOfSight(p)) continue
            return p
        }
        return null
    }

    /** The drop is over (played, seen, given up or caught): the friend and the scout are let go to their day and away. */
    private fun endDrop(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        memo.drop?.let { if (!it.finished) it.cancel(level) }
        memo.drop = null
        memo.going = false
        memo.dropForced = false
        if (memo.placed.remove(FRIEND) != null) Tales.release(level, t, FRIEND)
        if (t.cast(SCOUT) != null) Tales.release(level, t, SCOUT)
    }

    /** Whether [speaker] (the friend) is at the dead drop now, for the stake-out. */
    fun atDrop(level: ServerLevel, t: Tale, speaker: Townsperson): Boolean {
        val memo = memos[t.id] ?: return false
        val at = memo.dropAt ?: return false
        if (!memo.going && memo.drop == null) return false
        return speaker.distanceToSqr(Vec3.atCenterOf(at)) <= AT_DROP * AT_DROP
    }

    /** The friend was caught at the drop: it is over, and the scout is off down the road. */
    fun dropCaught(c: Ctx, t: Tale) {
        val memo = memos[t.id] ?: return
        endDrop(c, t, memo)
    }

    // ---- The holds ----

    private fun near(level: ServerLevel, at: BlockPos, r: Double): Boolean =
        level.players().any { it.isAlive && !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(at)) < r * r }

    /** Where the sergeant stands at the gate: two blocks beside its front. */
    internal fun post(c: Ctx, t: Tale): BlockPos {
        val front = Informer.front(c, t)
        val side = c.here.capital.direction(Informer.gate(t)).clockWise
        return Sets.standable(c.level, front.relative(side, 2)) ?: front
    }

    /** The Naming's two marks: the sergeant in the gate yard, the friend three blocks nearer the gate. */
    internal fun namingMarks(c: Ctx, t: Tale): Pair<BlockPos, BlockPos> {
        val level = c.level
        val yard = Informer.yard(c, t)
        val sergeant = Sets.standable(level, yard) ?: yard
        val friend = Sets.standable(level, Informer.inward(c, t, 3.0)) ?: sergeant
        return sergeant to friend
    }

    /** The Handshake's two marks: the friend on their step, the sergeant three blocks along the street; null with no door known. */
    internal fun doorMarks(c: Ctx, t: Tale): Pair<BlockPos, BlockPos>? {
        val level = c.level
        val door = Informer.doorOf(c, t, FRIEND) ?: return null
        val friend = Sets.standable(level, door) ?: door
        val sergeant = Sets.beside(level, Vec3.atCenterOf(friend), Vec3.atCenterOf(c.here.capital.center), 3.0) ?: friend
        return friend to sergeant
    }

    private fun holds(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val now = Informer.clock(level)
        val front = Informer.front(c, t)
        if (t.step < AT_TELL) {
            val mark = if (t.step <= AT_CHOOSE && now in Informer.POST_FROM until Informer.POST_TO && near(level, front, NEAR)) post(c, t) else null
            place(c, t, memo, SERGEANT, mark, Activity.STAND_GUARD)
            // Spoken to first: the friend waits on their step for the player, whatever the hour, while one is near
            if (t.step == AT_CHOOSE && Informer.flag(t, Informer.HUSH)) {
                val door = doorMarks(c, t)?.first
                place(c, t, memo, FRIEND, door?.takeIf { near(level, it, NEAR) }, Activity.SIT)
            }
            return
        }
        if (!InformerTrap.toTell(t)) return
        if (Informer.way(t) == Informer.WAY_A) {
            val (s, f) = namingMarks(c, t)
            val on = (memo.forced || now in Informer.NAMING_FROM until Informer.NAMING_TO) && near(level, s, SEES)
            place(c, t, memo, SERGEANT, if (on) s else null, Activity.ARMS_FOLDED)
            place(c, t, memo, FRIEND, if (on) f else null, Activity.LOOK_ABOUT)
        } else {
            val marks = doorMarks(c, t)
            val on = marks != null && (memo.forced || now in Informer.DUSK_FROM until Informer.DUSK_TO) && near(level, marks.first, SEES)
            place(c, t, memo, FRIEND, if (on) marks?.first else null, Activity.SIT)
            place(c, t, memo, SERGEANT, if (on) marks?.second else null, Activity.SIGH)
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
        // The drop has the friend while it is on
        if (slot == FRIEND && (memo.going || memo.drop != null)) return
        if (mark == null) {
            if (memo.placed.remove(slot) != null) Tales.release(level, t, slot)
            return
        }
        if (memo.placed[slot] == mark) return
        val body = TaleBodies.body(level, t, slot)
        if (body != null && body.tale != t.id && body.distanceToSqr(Vec3.atCenterOf(mark)) > MARK_NEAR * MARK_NEAR && !Comings.unseen(level, body.blockPosition())) return
        if (Tales.hold(level, t, slot, mark, clip)) memo.placed[slot] = mark
    }

    // ---- The ending's scene ----

    private fun scene(c: Ctx, t: Tale, memo: Memo) {
        if (!InformerTrap.toTell(t)) return
        val level = c.level
        val now = level.gameTime
        memo.scene?.let { st ->
            // Queued and not yet taken: its own end ends the tale; one given up does too
            if (!st.finished && now - memo.ready < SCENE_WAITS * 2) return
            if (!st.finished) st.cancel(level)
            return c.end(t, Informer.outcome(t))
        }
        val naming = Informer.way(t) == Informer.WAY_A
        val marks = if (naming) namingMarks(c, t) else doorMarks(c, t)?.let { it.second to it.first }
        val s = TaleBodies.body(level, t, SERGEANT)?.takeIf { it.tale == t.id && it.attending == null }
        val f = TaleBodies.body(level, t, FRIEND)?.takeIf { it.tale == t.id && it.attending == null }
        val there = marks != null && s != null && f != null &&
            s.distanceToSqr(Vec3.atCenterOf(marks.first)) < 2.5 * 2.5 && f.distanceToSqr(Vec3.atCenterOf(marks.second)) < 2.5 * 2.5
        if (!there || s == null || f == null) {
            memo.ready = -1L
            return
        }
        if (memo.ready < 0) memo.ready = now
        if (now - memo.ready > SCENE_WAITS) return c.end(t, Informer.outcome(t))
        val player = (t.parts.firstOrNull { it.did(Part.DECIDED) }?.player?.let { level.getPlayerByUUID(it) } as? ServerPlayer)?.takeIf { it.isAlive && it.distanceTo(s) < 32.0 }
        val stage = if (naming) {
            val guards = Sets.freeAdults(level, s.position(), 24.0, listOf(s, f)).filter { it.role == TownRole.GUARD }.take(2)
            val watchers = Sets.freeAdults(level, s.position(), 20.0, listOf(s, f) + guards).filter { it.role != TownRole.GUARD }.take(2)
            val home = Informer.doorOf(c, t, FRIEND)
            InformerScenes.naming(s, f, player, home, guards, watchers)
        } else {
            val watchers = Sets.freeAdults(level, f.position(), 16.0, listOf(s, f)).take(2)
            InformerScenes.handshake(f, s, watchers)
        }
        stage.then = { if (InformerTrap.toTell(t)) TaleEngine.ctx(level, t.capital)?.let { cc -> cc.end(t, Informer.outcome(t)) } }
        if (TaleLook.queue(level, t.capital, stage, urgent = true, hears = true)) memo.scene = stage
    }

    // ---- The end ----

    /** The tale is ending: the drop and the scene are called off (the engine lets the holds and the figures go after). */
    fun letGo(c: Ctx, t: Tale) {
        val memo = memos[t.id] ?: return
        val level = c.level
        memo.drop?.let { if (!it.finished) it.cancel(level) }
        memo.drop = null
        memo.going = false
        memo.scene?.let { if (!it.finished && !it.playing) it.cancel(level) }
    }

    /** [slot] of tale [id] is someone else now, or gone: its mark is worked out again. */
    fun forget(id: Int, slot: String) {
        memos[id]?.placed?.remove(slot)
    }

    /** [id] ended: nothing of it is kept. */
    fun ended(id: Int) {
        memos.remove(id)
    }

    /** For `tale informer`: what the watch has in hand for [t], in a few words. */
    fun state(t: Tale): String {
        val m = memos[t.id] ?: return "nothing yet"
        val out = ArrayList<String>()
        if (m.placed.isNotEmpty()) out += "holding " + m.placed.entries.joinToString { "${it.key} at ${it.value.toShortString()}" }
        if (m.heard.isNotEmpty()) out += "${m.heard.size} player(s) have heard the last answer"
        m.dropAt?.let { out += "the drop at ${it.toShortString()}" + (m.scoutAt?.let { s -> ", the scout from ${s.toShortString()}" } ?: "") }
        if (m.dropDay != Long.MIN_VALUE) out += "the drop tried on day ${m.dropDay}"
        if (m.going) out += "the friend is on their way to the drop"
        if (m.drop?.playing == true) out += if (m.handed) "the paper has passed" else "the drop is playing"
        if (m.ready >= 0) out += "the two are at their marks"
        if (m.scene != null) out += "the scene is on the stage"
        return out.joinToString("; ").ifEmpty { "nothing yet" }
    }

    /** `tale informer scene`: the ending's scene is in its hours now, whatever the clock; false if no scene is to come. */
    fun now(t: Tale): Boolean {
        if (!InformerTrap.toTell(t)) return false
        memos.getOrPut(t.id) { Memo() }.forced = true
        return true
    }

    /** `tale informer drop`: the dead drop is tried now (the friend told, at the test), whatever the hour and whatever was tried before. */
    fun again(t: Tale) {
        val memo = memos.getOrPut(t.id) { Memo() }
        memo.dropDay = Long.MIN_VALUE
        memo.dropForced = true
    }

    /** The marks of the drop for [t], worked out now if they were not: for `tale informer drop`. */
    fun dropMarks(c: Ctx, t: Tale): Pair<BlockPos?, BlockPos?> {
        val memo = memos.getOrPut(t.id) { Memo() }
        val at = memo.dropAt ?: dropSpot(c, t)?.also { memo.dropAt = it }
        val scout = memo.scoutAt ?: at?.let { scoutSpot(c, t, it) }?.also { memo.scoutAt = it }
        return at to scout
    }

    private fun flat(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    private fun yawTo(from: BlockPos, to: BlockPos): Float =
        (Math.toDegrees(kotlin.math.atan2((to.z - from.z).toDouble(), (to.x - from.x).toDouble())) - 90.0).toFloat()

    private fun forgetAll() {
        memos.clear()
        InformerScenes.forget()
    }
}
