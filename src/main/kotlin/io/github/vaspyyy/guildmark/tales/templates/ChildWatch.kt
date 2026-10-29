package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleEscort
import io.github.vaspyyy.guildmark.tales.TaleGuard
import io.github.vaspyyy.guildmark.tales.TaleLeave
import io.github.vaspyyy.guildmark.tales.TaleTalk
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The child tale's once-a-second look at the world (living-world/stories/design.md 8.4), which no step of the table can be: a child goes
 * out at three in the afternoon without any step happening, and is found by a player coming near. Everything here is memory: the record
 * ([Child]'s numbers and flags, the cast's `held` flags) is the truth, and what this keeps (the marks it has put people at, the clearing it
 * found, who began the walk, how far the bringing in has got) is worked out again from it after a restart.
 *
 * - **The child.** From 15:00 (or a forced start) the child is held at a mark: beside the wardfire if one stands within [Child.CLEARING]
 *   blocks of the place on the road (only loaded land is looked at, so it is looked for again as land loads), else the road's edge. By day
 *   they stand clutching a cloth bundle (CARRY, the bundle in their arms), and shiver once the light is gone.
 * - **The find.** The first player within [FIND] blocks of the child, at the mark, finds them: the player takes the tale up, the bit is
 *   set, and the tale is at the walk home. A child nobody speaks to says why they went on their own after ten seconds.
 * - **The gate.** The parent waits at the gate from dusk, and from the find; the guard stands at it while he is to be asked.
 * - **The reunion.** A child brought to the gate whose reunion cannot be played within a minute is home in the record all the same.
 * - **First light** ([Child.DAWN]). The close has come and gone with the child still out and a player near: the child waits where they
 *   are (beyond harm, as every tale body is) and the parent at the gate; at first light the guard walks from the gate to the child and brings
 *   them in by the road, and the tale ends as they reach the gate. With no player near at first light, or anything in the way (no guard,
 *   no road, a walk that will not go), it ends at once and the record does the rest.
 * - **The rumour.** From half past three the parent has missed the child, and the town hears of it.
 */
internal object ChildWatch {
    /** The player who began each tale's walk home (the deciding deed of A and B), by tale. */
    val walkers = HashMap<Int, UUID>()

    /** The wardfire each tale's child was found a clearing at. */
    val clearings = HashMap<Int, BlockPos>()

    /** The `tale:slot`s this run has put at a mark; asked again after a restart, and when a stand-in takes a slot. */
    val placed = HashSet<String>()

    /** The mark and the clip each child was last held with, by tale. */
    private val marks = HashMap<Int, BlockPos>()
    private val clips = HashMap<Int, Activity?>()

    /** The tales whose clearing has been looked for with all the land round the place loaded. */
    private val looked = HashSet<Int>()

    /** When the child of each tale was found (game time), and the tales whose child has asked of their own accord. */
    private val foundAt = HashMap<Int, Long>()
    private val prompted = HashSet<Int>()

    /** When each tale's child was first seen at the gate waiting for the reunion (game time). */
    private val arrivedAt = HashMap<Int, Long>()

    /** How far each tale's bringing in at first light has got. */
    private val bringing = HashMap<Int, Bring>()

    /** The watch going out for a child at first light: begun at [at]; [home] once the guard has reached them and both walk the road in. */
    private class Bring(val at: Long) {
        var home = -1L
        var guardIn = false
    }

    /** How near a player must be to find the child, how near the child must be to their mark for that, how near the player for the prompt. */
    private const val FIND = 6.0
    private const val AT_MARK = 16.0
    private const val PROMPT_AFTER = 200L
    private const val PROMPT_NEAR = 16.0

    /** How near the gate the parent's or the guard's day body must be to be held there, if a player could see it go. */
    private const val GATE_NEAR = 44.0

    /** How far to the side of the road the child sits when no wardfire is near, and how far from a wardfire. */
    private const val EDGE = 3.0
    private const val BESIDE_FIRE = 2

    /** Ticks the reunion has to be played once the child is at the gate before the record ends the tale without it. */
    private const val REUNION_WAITS = 1200L

    /** How near a player must be to the child, or to the gate, for the watch's bringing in to be played rather than the record's. */
    private const val SEES_CHILD = 64.0
    private const val SEES_GATE = 48.0

    /** Ticks the guard has to reach the child, and the two of them to walk the road in, before the record does it; how near is "reached". */
    private const val OUT_FOR = 3000L
    private const val HOME_FOR = 1800L
    private const val REACHED = 3.0

    /** A child brought in by the record at the end is not walked to the gate if they are already this near it. */
    private const val AT_GATE = 16.0

    /** How near a player must be to a child on a walk at first light for the walk to go on rather than the watch bring them in. */
    private const val WALK_NEAR = 32.0

    // ---- Once a second ----

    /** From the tick, once a second: each open child tale is looked at, guarded. */
    fun watch(server: MinecraftServer) {
        if (!Tales.live) return
        val level = server.overworld()
        for (t in TaleEngine.openTales(level)) {
            if (t.template != Child.id) continue
            val c = TaleEngine.ctx(level, t.capital) ?: continue
            TaleGuard.run(c, t, "the watch on the road") { see(c, t) }
        }
    }

    private fun see(c: Ctx, t: Tale) {
        val level = c.level
        // The parent has missed the child, and the town hears of it
        if (!Child.flag(t, Child.RUMOUR) && Child.missed(level, t)) {
            Child.set(t, Child.RUMOUR)
            c.data.changed()
            TaleTalk.rumour(level, t)
        }
        if (Child.flag(t, Child.DAWN)) return dawn(c, t)
        if (!Tales.scenes()) return
        if (t.step < Child.AT_WALK) child(c, t)
        if (t.step == Child.AT_GUARD) guard(c, t)
        if (t.step >= Child.AT_WALK || Child.missed(level, t) && Child.dark(level)) parent(c, t)
        if (t.step < Child.AT_WALK) find(c, t)
        if (t.step == Child.AT_WALK) {
            bundle(level, t)
            prompt(level, t)
        }
        if (t.step == Child.AT_SCENE) late(c, t)
    }

    // ---- The child ----

    /** Holds the child at their mark (the clearing, else the road's edge), with the clip that suits the hour; asked again when either changes. */
    private fun child(c: Ctx, t: Tale) {
        val level = c.level
        if (!Child.left(level, t)) return
        val cast = t.cast(Child.CHILD) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        val spot = Child.place(t)
        look(level, t, spot)
        val mark = markFor(c, t, spot)
        val clip = clipFor(level)
        val key = key(t, Child.CHILD)
        if (key in placed && marks[t.id] == mark && clips[t.id] == clip) {
            bundle(level, t)
            return
        }
        if (Tales.hold(level, t, Child.CHILD, mark, clip)) {
            placed += key
            marks[t.id] = mark
            clips[t.id] = clip
        }
        bundle(level, t)
    }

    /** Looks for a wardfire within the clearing's reach of the place once all the land round it is loaded; never reads what is not loaded. */
    private fun look(level: ServerLevel, t: Tale, spot: BlockPos) {
        if (t.id in looked || t.id in clearings) return
        val r = Child.CLEARING
        for (cx in ((spot.x - r) shr 4)..((spot.x + r) shr 4)) for (cz in ((spot.z - r) shr 4)..((spot.z + r) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) return
        }
        looked += t.id
        WardfireSites.nearest(level, spot, r)?.let { clearings[t.id] = it }
    }

    /** Where the child waits: beside the wardfire if one was found, else at the road's edge; the place itself while the land is not loaded to stand on. */
    private fun markFor(c: Ctx, t: Tale, spot: BlockPos): BlockPos {
        val level = c.level
        val fire = clearings[t.id]
        if (fire != null) {
            for ((dx, dz) in listOf(BESIDE_FIRE to 0, -BESIDE_FIRE to 0, 0 to BESIDE_FIRE, 0 to -BESIDE_FIRE)) {
                Sets.standable(level, fire.offset(dx, 0, dz))?.let { return it }
            }
            return fire
        }
        val run = WayOut.run(level, c.here, spot)
        val edge = BlockPos.containing(spot.x + 0.5 - run.z * EDGE, spot.y.toDouble(), spot.z + 0.5 + run.x * EDGE)
        return Sets.standable(level, edge) ?: Sets.standable(level, spot) ?: spot
    }

    /**
     * SHIVER once the light is gone; by day CARRY, standing with the bundle held to them (the clips that sit draw nothing in the hands, so a
     * seated child would have put it down).
     */
    private fun clipFor(level: ServerLevel): Activity = if (Child.dark(level)) Activity.SHIVER else Activity.CARRY

    /** The cloth bundle in the child's arms, put there once their tale body is about. */
    private fun bundle(level: ServerLevel, t: Tale) {
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        if (body.tale != t.id || !body.carried.isEmpty) return
        body.carried = ItemStack(Items.BUNDLE)
        body.carriedUntil = 0
    }

    // ---- The find ----

    /** The first player within six blocks of the child, who is at their mark, finds them. */
    private fun find(c: Ctx, t: Tale) {
        val level = c.level
        if (Child.flag(t, Child.FOUND) || !Child.left(level, t)) return
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        val mark = marks[t.id] ?: return
        if (body.tale != t.id || body.distanceToSqr(Vec3.atCenterOf(mark)) > AT_MARK * AT_MARK) return
        val who = level.players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(body) <= FIND * FIND }
            .minByOrNull { it.distanceToSqr(body) } ?: return
        found(c, t, who)
    }

    private fun found(c: Ctx, t: Tale, player: ServerPlayer) {
        val level = c.level
        Child.set(t, Child.FOUND)
        t.partFor(player.uuid)?.let { it.did = it.did or Part.WALKED }
        Tales.join(level, t, player)
        foundAt[t.id] = level.gameTime
        c.why(t, "child.found", player.gameProfile.name, Child.childName(t))
        c.data.changed()
        c.go(t, Child.AT_WALK)
    }

    /** A child nobody has spoken to says why they went, on their own, after ten seconds, once. */
    private fun prompt(level: ServerLevel, t: Tale) {
        if (t.id in prompted || Child.flag(t, Child.WALKING)) return
        val at = foundAt[t.id] ?: return
        if (level.gameTime - at < PROMPT_AFTER) return
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        if (level.getNearestPlayer(body, PROMPT_NEAR) == null) return
        prompted += t.id
        Words.sayWith(body, "tale.child.found", why = "a lost child")
    }

    // ---- The gate ----

    /** The parent waits at the gate, looking down the road. */
    fun parent(c: Ctx, t: Tale) = gate(c, t, Child.PARENT, Activity.LOOKOUT)

    /** The guard stands at the gate while he is to be asked. */
    private fun guard(c: Ctx, t: Tale) = gate(c, t, Child.GUARD, Activity.STAND_GUARD)

    /**
     * Holds [slot] at the gate's front with [clip], once this run: if their day body is far from it and in sight they are left to their day
     * and tried again (a held body that is far and seen would walk out of the gate and be made again, in sight, at the mark).
     */
    private fun gate(c: Ctx, t: Tale, slot: String, clip: Activity) {
        val level = c.level
        val cast = t.cast(slot) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        val key = key(t, slot)
        if (key in placed) return
        val front = Child.front(t)
        val body = TaleBodies.body(level, t, slot)
        if (body != null && body.tale != t.id && body.distanceToSqr(Vec3.atCenterOf(front)) > GATE_NEAR * GATE_NEAR && !Comings.unseen(level, body.blockPosition())) return
        if (Tales.hold(level, t, slot, front, clip)) placed += key
    }

    /** The child is at the gate: the reunion is the stage's to play, and if a minute goes by without it the record has them home. */
    private fun late(c: Ctx, t: Tale) {
        val now = c.level.gameTime
        val since = arrivedAt.getOrPut(t.id) { now }
        if (now - since >= REUNION_WAITS) c.end(t, Child.arrival(t))
    }

    // ---- First light ----

    /**
     * Whether the close should keep [t] open for the watch to bring the child in at first light: the child's tale body is about, and a player
     * is within [SEES_CHILD] blocks of it or [SEES_GATE] of the gate to see it done. With none, the close ends the tale and the record has the
     * child home.
     */
    fun waits(c: Ctx, t: Tale): Boolean {
        if (!Tales.scenes()) return false
        val level = c.level
        val body = TaleBodies.body(level, t, Child.CHILD)?.takeIf { it.tale == t.id } ?: return false
        val gate = Vec3.atCenterOf(Child.front(t))
        return level.players().any { it.isAlive && !it.isSpectator && (it.distanceToSqr(body) < SEES_CHILD * SEES_CHILD || it.distanceToSqr(gate) < SEES_GATE * SEES_GATE) }
    }

    /**
     * After the close ([Child.DAWN]): through the night the child waits where they are and the parent at the gate; at first light the guard
     * goes out for them ([begin]), and the tale ends as they reach the gate ([bring]). A walk home a player began goes on, and ends the tale at
     * the gate itself. Nobody near at first light, or no way to do it, and the record ends it now.
     */
    private fun dawn(c: Ctx, t: Tale) {
        val level = c.level
        bringing[t.id]?.let { return bring(c, t, it) }
        if (Tales.scenes()) {
            if (t.step < Child.AT_WALK) child(c, t) else bundle(level, t)
            parent(c, t)
            if (t.step == Child.AT_SCENE) return late(c, t)
        }
        if (!Child.firstLight(level, t)) return
        if (walking(level, t)) return
        if (!waits(c, t) || !begin(c, t)) c.end(t, Child.decided(t))
    }

    /**
     * Whether the child is on a walk with a player just now: one with no player near them (logged off, gone far ahead out of reach) is called
     * off, and the watch brings them in instead.
     */
    private fun walking(level: ServerLevel, t: Tale): Boolean {
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return false
        val escort = (body.director as? TaleEscort)?.takeIf { !it.done } ?: return false
        if (level.getNearestPlayer(body, WALK_NEAR) != null) return true
        escort.cancel(body)
        return false
    }

    /** The guard is sent out from the gate for the child: held at the gate's front, and once his tale body is there his mark is moved to the child's side. */
    private fun begin(c: Ctx, t: Tale): Boolean {
        val level = c.level
        val guard = t.cast(Child.GUARD)?.takeIf { it.resident >= 0 && it.gone.isEmpty() } ?: return false
        if (TaleBodies.body(level, t, Child.CHILD)?.takeIf { it.tale == t.id } == null) return false
        if (WayOut.homeward(level, c.here, Child.place(t)).isEmpty()) return false
        if (!Tales.hold(level, t, Child.GUARD, Child.front(t), Activity.STAND_GUARD)) return false
        placed += key(t, Child.GUARD)
        bringing[t.id] = Bring(level.gameTime)
        c.why(t, "child.watch_out", guard.name, Child.childName(t))
        return true
    }

    /**
     * The bringing in, once a second: the guard walks out to the child (his hold's mark is beside them: the hold walks him there), then the two
     * walk the road in to the gate, and the tale ends as the child reaches it (or as the walk is cut short with nobody near to see the rest, the
     * record's way). Too long at either, or a body gone, and the record ends it.
     */
    private fun bring(c: Ctx, t: Tale, b: Bring) {
        val level = c.level
        val now = level.gameTime
        val child = TaleBodies.body(level, t, Child.CHILD)?.takeIf { it.tale == t.id }
        val guard = TaleBodies.body(level, t, Child.GUARD)?.takeIf { it.tale == t.id }
        if (child == null) return c.end(t, Child.decided(t))
        if (b.home < 0) {
            if (now - b.at > OUT_FOR) return c.end(t, Child.decided(t))
            if (guard == null) return
            if (guard.distanceTo(child) > REACHED) {
                // Sent from the gate once he is there; till then his hold walks him to it
                val front = Vec3.atCenterOf(Child.front(t))
                if (guard.distanceToSqr(front) < 6.0 * 6.0 || now - b.at > 600L) beside(c, t, child)
                return
            }
            homeward(c, t, b, child, guard)
            return
        }
        if (now - b.home > HOME_FOR) return c.end(t, Child.decided(t))
        // A word from a player stops the walk: it is taken up again from where they stand
        if (child.director !is RoadIn && child.attending == null) walkIn(c, t, b, child)
        if (guard != null && !b.guardIn && guard.director !is RoadIn && guard.attending == null) guardIn(c, t, b, guard)
    }

    /** The guard's mark is beside the child, on the town's side: his hold walks him out along the road to it. */
    private fun beside(c: Ctx, t: Tale, child: Townsperson) {
        val level = c.level
        val front = Child.front(t)
        val dx = front.x + 0.5 - child.x
        val dz = front.z + 0.5 - child.z
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1.0)
        val at = BlockPos.containing(child.x + dx / len * 1.5, child.y, child.z + dz / len * 1.5)
        Tales.hold(level, t, Child.GUARD, Sets.standable(level, at) ?: child.blockPosition(), null)
    }

    /** The guard has reached the child: he beckons, the child gets up and asks if they will be cross, and the two set off down the road in. */
    private fun homeward(c: Ctx, t: Tale, b: Bring, child: Townsperson, guard: Townsperson) {
        b.home = c.level.gameTime
        guard.lookAt(child)
        guard.act(Activity.BECKON)
        if (child.seat != null) child.standUp()
        child.lookAt(guard)
        Words.sayWith(child, "tale.child.agree", why = "a lost child brought in")
        walkIn(c, t, b, child)
        guardIn(c, t, b, guard)
    }

    /** The child walks the road in to the gate's front; the tale ends when the walk does (at the gate, or a way that will not go: the record's). */
    private fun walkIn(c: Ctx, t: Tale, b: Bring, child: Townsperson) {
        val level = c.level
        val way = WayOut.homeward(level, c.here, child.blockPosition())
        if (way.isEmpty()) return c.end(t, Child.decided(t))
        child.navigation.stop()
        child.director = RoadIn(way, HOME_FOR.toInt()) { _ ->
            if (t.open && bringing[t.id] === b) TaleEngine.ctx(level, t.capital)?.let { cc -> cc.end(t, Child.decided(t)) }
        }
        if (!child.carried.isEmpty) child.act(Activity.CARRY)
    }

    /** The guard walks the road in a step ahead of the child, and stands at the gate again once there. */
    private fun guardIn(c: Ctx, t: Tale, b: Bring, guard: Townsperson) {
        val level = c.level
        val way = WayOut.homeward(level, c.here, guard.blockPosition())
        if (way.isEmpty()) return
        guard.navigation.stop()
        guard.director = RoadIn(way, HOME_FOR.toInt()) { _ ->
            b.guardIn = true
            if (t.open) Tales.hold(level, t, Child.GUARD, Child.front(t), Activity.STAND_GUARD)
        }
    }

    // ---- The end ----

    /** The tale is ending: the walk is called off, and the guard's walk too. */
    fun letGo(c: Ctx, t: Tale) {
        walkers.remove(t.id)
        bringing.remove(t.id)
        val body = TaleBodies.body(c.level, t, Child.CHILD) ?: return
        (body.director as? TaleEscort)?.cancel(body)
    }

    /**
     * The child is brought in by the ending (C and W) when it finds them out on the road, more than [AT_GATE] blocks from the gate, where a
     * player can see: they walk the road to the gate and are gone there, and are made again at home out of sight. One nobody can see is left to
     * the release (dropped where they stand and made again at home); one at the gate goes home from there.
     */
    fun bringIn(c: Ctx, t: Tale) {
        val level = c.level
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        val r = c.resident(t, Child.CHILD) ?: return
        val from = body.blockPosition()
        body.carried = ItemStack.EMPTY
        body.carriedUntil = 0
        if (Comings.unseen(level, from)) return
        if (body.distanceToSqr(Vec3.atCenterOf(Child.front(t))) < AT_GATE * AT_GATE) return
        if (body.director is TaleLeave) return
        val way = WayOut.homeward(level, c.here, from)
        if (way.isEmpty()) return
        val capital = r.capital.asLong()
        body.navigation.stop()
        body.director = TaleLeave(way) { p ->
            p.discard()
            Comings.later(level, capital, listOf(r.id))
        }
    }

    // ---- Forgetting ----

    private fun key(t: Tale, slot: String): String = "${t.id}:$slot"

    /** A tale is over: what this kept of it is let go. */
    fun forget(tale: Int) {
        walkers.remove(tale)
        clearings.remove(tale)
        marks.remove(tale)
        clips.remove(tale)
        looked.remove(tale)
        foundAt.remove(tale)
        prompted.remove(tale)
        arrivedAt.remove(tale)
        bringing.remove(tale)
        placed.removeIf { it.startsWith("$tale:") }
    }

    /** The server stopped: all of it forgotten. */
    fun forgetAll() {
        walkers.clear()
        clearings.clear()
        placed.clear()
        marks.clear()
        clips.clear()
        looked.clear()
        foundAt.clear()
        prompted.clear()
        arrivedAt.clear()
        bringing.clear()
    }
}

/**
 * A walk along [way] for the bringing in at first light (the guard and the child) and the family setting off from the camp (the cart): each
 * point in turn at a walking pace, with [overlay] played over the walk (the shafts of a cart in the hands, a bundle in the arms), and [done]
 * when the last is reached, when the way will not go, or after [most] ticks. Unlike [TaleLeave] it is not cut short when nobody is near to
 * watch (the walker itself puts them there when no one can see either end of a leg), so a player waiting at the gate sees them come in. A
 * player's word comes first: they stop for it, and the watch takes the walk up again from where they stand.
 */
internal class RoadIn(
    private val way: List<BlockPos>, private val most: Int, private val overlay: Activity = Activity.NONE, private val done: (Townsperson) -> Unit,
) : Director {
    private var i = 0
    private var age = 0
    private var walker: Walker? = null
    private var over = false

    override fun tick(p: Townsperson) {
        if (over) return
        age++
        if (age > most || i >= way.size) return end(p)
        val w = walker ?: Walker(p).also {
            walker = it
            it.go(way[i], Jobs.WALK, near = 1.5)
        }
        when (w.tick()) {
            Walker.State.ARRIVED -> {
                i++
                walker = null
            }
            Walker.State.FAILED -> end(p)
            Walker.State.WALKING -> when {
                // Pulling a cart (or carrying) as they go: the overlay is played over the walk
                overlay != Activity.NONE -> if (p.activity != overlay && (p.activity.loop || p.actDone)) p.act(overlay)
                p.activity != Activity.NONE && !p.activity.overlay && (p.activity.loop || p.actDone) -> p.act(Activity.NONE)
            }
        }
    }

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        if (p.director === this) p.director = null
        walker?.stop()
        walker = null
        return false
    }

    private fun end(p: Townsperson) {
        over = true
        walker?.stop()
        if (p.director === this) p.director = null
        done(p)
    }
}
