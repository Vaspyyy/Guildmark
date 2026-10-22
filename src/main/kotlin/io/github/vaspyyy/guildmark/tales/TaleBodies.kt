package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * The people of a tale as bodies in the world (living-world/stories/design.md 4.4), Part B: who stands in a cast slot just now, a
 * resident the tale **holds** (their day is the tale's for now) and a **figure** the tale makes (a driver at a gate, a hooded
 * go-between). The record is the truth and a body is memory, so everything here is rebuilt from the record each second ([tidy]) and
 * from the bodies themselves ([tend]); a restart loses only the marks and clips a body stood at (it stands where it was saved, at
 * rest, until the tale holds it again).
 *
 * - **Holding.** [hold] puts a resident's `held` flag in the record (three at once) and sees to the body: one that is about and
 *   within 48 blocks of the mark is simply tagged ([Townsperson.tale]) where it stands and walked to the mark by its [TaleHold] (no
 *   one is moved or made to appear for a seed scene, rule 1); one further off leaves by the gate if a player can see it, or is dropped
 *   if none can, and the tale body is made at the mark by `Comings.body` once a player comes within 64 blocks of it. One that is not
 *   loaded is dealt with when it loads. A held resident is not "away": no household leaves, nothing else in the mod reacts.
 * - **Figures.** [figure] registers one at a mark: made only while a player is within 64 blocks of it (and the spot is out of sight,
 *   or the player near), at most six for a tale, discarded when no player is within reach, never saved and never adopted.
 * - **Releasing.** [release] clears the flag and untags the body, which goes about its day from where it stands (a body far from
 *   home that no one can see is dropped, and made again at home out of sight, `Comings.later`); a figure walks out by the nearest gate
 *   if it is seen, else is discarded.
 *
 * With townTaleScenes off nobody is held and no figure is made ([hold] and [figure] say no), and a body that is still tagged is let go
 * the next time it ticks ([tend]).
 */
object TaleBodies {
    /** Blocks from the mark a day body is tagged where it stands and walked there, rather than dropped and made at the mark. */
    private const val NEAR_MARK = 48.0

    /** Blocks within which a player sees a body made, so it is made though the spot is in sight; and beyond which a figure goes, after [FAR_FOR] ticks. */
    private const val SHOWN = 32.0
    private const val LEFT_FAR = 80.0
    private const val FAR_FOR = 100L

    /** Blocks down the road from a gate that a body leaving the town walks before it is gone. */
    private const val GATE_RUN = 14

    /** Ticks after `tale figures release` before the tidy holds and makes anything again. */
    private const val HUSH = 1200L

    private class Held(val tale: Int, val slot: String) {
        var mark: BlockPos? = null
        var yaw: Float? = null
        var clip: Activity? = null
        var body: UUID? = null
        var leaving: UUID? = null
        var changed = false
    }

    private class Figure(
        val tale: Int, val slot: String, var mark: BlockPos, var yaw: Float?, val role: TownRole, val look: Int, var clip: Activity?, val name: String,
    ) {
        var body: UUID? = null
        var farSince = -1L
    }

    private val held = HashMap<String, Held>()
    private val figures = HashMap<String, Figure>()
    private val escorts = HashMap<UUID, TaleEscort>()
    private var hush = 0L

    private fun key(tale: Int, slot: String): String = "$tale:$slot"

    // ---- Finding the people ----

    /**
     * The body of the cast member in [slot] of [t], if one is about (loaded and alive): the resident's own body, wherever their day
     * has them, or the tale body made for them; or a figure's. Null for a slot with no one, a resident the world no longer has, or a
     * body that is not in a loaded chunk. This is how a template finds the people of its scene.
     */
    fun body(level: ServerLevel, t: Tale, slot: String): Townsperson? {
        val c = t.cast(slot) ?: return null
        if (c.resident < 0) return figureBody(level, figures[key(t.id, slot)])
        val r = Residents.get(level)[c.resident] ?: return null
        return Comings.body(level, r)
    }

    /** The loaded bodies of [t]'s cast, in cast order (a slot with no body about is left out). */
    fun bodies(level: ServerLevel, t: Tale): List<Townsperson> = t.cast.mapNotNull { body(level, t, it.slot) }

    private fun figureBody(level: ServerLevel, f: Figure?): Townsperson? =
        f?.body?.let { level.getEntity(it) as? Townsperson }?.takeIf { it.isAlive && !it.isRemoved }

    // ---- Holding a resident (Tales.hold, Tales.release) ----

    /**
     * Holds the resident in [slot] of [t] (4.4 rule 2) with their body at [at] (null: where they are, or where they were put last)
     * playing [clip] on a loop. False if scenes are off, the tale is over, there is no such resident, or three are held already. Asked
     * again for a slot that is held it changes the mark and the clip.
     */
    fun hold(level: ServerLevel, t: Tale, slot: String, at: BlockPos? = null, clip: Activity? = null): Boolean {
        if (!Tales.scenes() || !t.open) return false
        val c = t.cast(slot) ?: return false
        if (c.resident < 0) return false
        val r = Residents.get(level)[c.resident]?.takeIf { it.alive } ?: return false
        if (!c.held && t.cast.count { it.held } >= TaleLimits.HELD) return false
        val h = held.getOrPut(key(t.id, slot)) { Held(t.id, slot) }
        if (at != null) h.mark = at
        h.clip = clip
        h.changed = true
        if (!c.held) {
            c.held = true
            TaleData.get(level).changed()
        }
        // No day body is made for them at home while the tale has them
        Comings.met(level, r.capital.asLong(), listOf(r.id))
        ensure(level, t, c, h)
        return true
    }

    /**
     * Lets the resident in [slot] of [t] go back to their day (4.4 rule 4), or a figure go. Works whatever the switches say: a flag in
     * the record must never outlast the tale. Their body goes about its day from where it stands; one far from home that no one can see
     * is dropped and made again at home, out of sight, when a player next comes.
     */
    fun release(level: ServerLevel, t: Tale, slot: String) {
        val c = t.cast(slot) ?: return
        if (c.resident < 0) {
            figures[key(t.id, slot)]?.let { retire(level, it, visible = true) }
            return
        }
        val h = held.remove(key(t.id, slot))
        if (c.held) {
            c.held = false
            TaleData.get(level).changed()
        }
        val r = Residents.get(level)[c.resident] ?: return
        val b = Comings.body(level, r)
        if (b == null) {
            // Their tale body is gone (or was never made): their home body is made out of sight, if they have none
            if (r.alive) Comings.later(level, r.capital.asLong(), listOf(r.id))
            return
        }
        h?.leaving = null
        if (b.tale == t.id) untag(level, b, r)
    }

    /** [b] is no one's tale body any more: it goes about its day, or, far from home and out of sight, is dropped to be made again at home. */
    private fun untag(level: ServerLevel, b: Townsperson, r: Resident?) {
        val e = escorts.remove(b.uuid)
        if (e != null && b.director === e) b.director = null
        if (b.director is TaleHold) b.director = null
        b.tale = -1
        if (b.seat == null && !b.activity.overlay) b.act(Activity.NONE)
        val home = b.home
        if (r != null && home != null && b.distanceToSqr(home.x + 0.5, home.y + 0.5, home.z + 0.5) > 40.0 * 40.0 && Comings.unseen(level, b.blockPosition())) {
            b.discard()
            Comings.later(level, r.capital.asLong(), listOf(r.id))
        }
    }

    // ---- Seeing to a held resident, once a second ----

    private fun ensure(level: ServerLevel, t: Tale, c: Cast, h: Held) {
        val r = Residents.get(level)[c.resident]?.takeIf { it.alive } ?: return
        val b = Comings.body(level, r)
        val mark = h.mark
        if (b != null && b.tale == t.id) {
            h.leaving = null
            h.body = b.uuid
            val d = b.director
            if (d is TaleHold && h.changed) {
                mark?.let { d.mark = it }
                d.clip = h.clip
                d.seat = seatFor(level, b, h)
            }
            h.changed = false
            if (d == null && b.attending == null) give(level, b, h)
            return
        }
        if (b != null && b.tale >= 0) return
        if (b != null) {
            // Their day body is about
            if (h.leaving == b.uuid) return
            val far = mark != null && b.distanceToSqr(mark.x + 0.5, mark.y + 0.5, mark.z + 0.5) > NEAR_MARK * NEAR_MARK
            if (!far) {
                takeInPlace(level, t, b, h)
                return
            }
            // The mark is a way off: out of sight they simply go, in sight they leave by the gate (once nothing else has charge of them)
            if (Comings.unseen(level, b.blockPosition())) b.discard() else {
                if (b.director == null && b.attending == null) leave(level, b, h)
                return
            }
        }
        make(level, t, r, h)
    }

    /** The day body [b] is the tale's from now: it stays where it stands and its hold walks it to the mark. False if it is busy just now. */
    private fun takeInPlace(level: ServerLevel, t: Tale, b: Townsperson, h: Held): Boolean {
        if (b.director != null || b.isSleeping || b.attending != null || b.captiveOf >= 0 || b.isLeader || b.watcher || b.isPassenger) return false
        val clip = h.clip
        // Someone seated stays seated for a sitting clip (what they were at is paused where it is); anyone else is let go of it
        if (b.seat != null && clip != null && !sitting(clip)) {
            b.routine.interrupt()
            b.standUp()
        }
        if (b.seat == null) b.routine.interrupt()
        b.navigation.stop()
        b.tale = t.id
        b.life = null
        give(level, b, h)
        return true
    }

    /** The tale body of [r], made at the mark when a player is near it (4.4 rule 2). */
    private fun make(level: ServerLevel, t: Tale, r: Resident, h: Held): Boolean {
        val mark = h.mark ?: return false
        if (!level.isLoaded(mark)) return false
        val near = level.getNearestPlayer(mark.x + 0.5, mark.y + 0.5, mark.z + 0.5, TaleLimits.FIGURE_RANGE.toDouble(), false) ?: return false
        if (near.distanceToSqr(mark.x + 0.5, mark.y + 0.5, mark.z + 0.5) > SHOWN * SHOWN && !Comings.unseen(level, mark)) return false
        val capital = Capitals.covering(level, r.capital) ?: return false
        val places = CityPlaces.of(level, capital) ?: return false
        val stand = Sets.standable(level, mark) ?: return false
        val b = Comings.body(level, places, r, stand, h.yaw) ?: return false
        b.tale = t.id
        b.life = null
        give(level, b, h)
        Comings.met(level, r.capital.asLong(), listOf(r.id))
        return true
    }

    /** The day body [b] walks out of the town by the nearest gate, in sight, and is gone there. */
    private fun leave(level: ServerLevel, b: Townsperson, h: Held) {
        val way = exitWay(level, b.blockPosition())
        if (way == null) {
            b.discard()
            return
        }
        if (b.seat != null) b.standUp()
        b.routine.interrupt()
        b.navigation.stop()
        b.carried = ItemStack.EMPTY
        b.director = TaleLeave(way) { it.discard() }
        h.leaving = b.uuid
    }

    /** The front of the gate nearest [from], and a little way down its road, or null if [from] is not in a capital with a gate to go by. */
    private fun exitWay(level: ServerLevel, from: BlockPos): List<BlockPos>? {
        val capital = Capitals.covering(level, from) ?: return null
        val places = CityPlaces.of(level, capital) ?: return null
        val plan = places.census.plan
        val gate = plan.outerGates.filter { g -> plan.outskirts.roads.any { it.side == g } }.minByOrNull { frontOf(places, it).distSqr(from) } ?: return null
        val front = frontOf(places, gate)
        // A little way down the gate's road, in the city's own frame turned into the world's
        val road = plan.outskirts.roads.first { it.side == gate }
        val row = minOf(road.rows - 1, GATE_RUN)
        val out = if (row < 0) null else road.at(row, 0).let { p -> Sets.standable(level, places.capital.world(p.x, road.heights[row], p.z)) }
        return listOf(front, out ?: front)
    }

    /** Just outside the gatehouse on [gate]'s side, on the road. */
    private fun frontOf(places: CityPlaces, gate: Dir): BlockPos {
        val plan = places.census.plan
        val p = plan.sidePos(gate, 0, plan.outer + CityPlan.GATE_OUT + 1)
        return places.capital.world(p.x, plan.level(p.x, p.z), p.z)
    }

    // ---- The directors ----

    /** Gives [b] its director: the walk it is on, else the hold at its mark. */
    private fun give(level: ServerLevel, b: Townsperson, h: Held?) {
        h?.body = b.uuid
        val e = escorts[b.uuid]
        if (e != null && !e.done) {
            b.director = e
            return
        }
        val at = h?.mark ?: b.blockPosition()
        b.director = TaleHold(at, h?.yaw, h?.clip, if (h != null) seatFor(level, b, h) else b.seat)
    }

    /** The seat a body sits on at its hold: the one it is on, else the nearest to the mark if the clip is a sitting one. */
    private fun seatFor(level: ServerLevel, b: Townsperson, h: Held): BlockPos? {
        val clip = h.clip
        if (clip == null) return b.seat
        if (!sitting(clip)) return null
        return b.seat ?: seatNear(level, h.mark ?: b.blockPosition())
    }

    /** A bench or doorstep stair within three blocks of [at] to sit on. */
    private fun seatNear(level: ServerLevel, at: BlockPos): BlockPos? = Places.nearest(level, at, 3, 2, null) { state, pos -> Places.isSeat(level, state, pos) }

    private fun sitting(a: Activity): Boolean =
        a == Activity.SIT || a == Activity.SIT_DRINK || a == Activity.SIT_TALK || a == Activity.SIT_EAT || a == Activity.SIT_GOSSIP || a == Activity.SIT_LAUGH

    /** From [TaleEscort.start]: [escort] has [p]. */
    internal fun escorting(p: Townsperson, escort: TaleEscort) {
        escorts[p.uuid] = escort
    }

    /** From [TaleEscort]: its walk is over; a tale's body is given back to its hold, here. */
    internal fun escortEnded(p: Townsperson, e: TaleEscort) {
        if (escorts[p.uuid] === e) escorts.remove(p.uuid)
        if (p.director === e) p.director = null
        val level = p.level() as? ServerLevel ?: return
        if (p.tale < 0 || !Tales.scenes()) return
        val h = held.values.firstOrNull { it.body == p.uuid }
        h?.mark = p.blockPosition()
        h?.yaw = null
        give(level, p, h)
    }

    /** From [TaleHold.rest]: a scene left [p] at [mark]; that is where it is held now. */
    internal fun marked(p: Townsperson, mark: BlockPos) {
        held.values.firstOrNull { it.body == p.uuid }?.let {
            it.mark = mark
            it.yaw = null
        }
        figures.values.firstOrNull { it.body == p.uuid }?.let {
            it.mark = mark
            it.yaw = null
        }
    }

    // ---- Figures (4.4 rule 3) ----

    /**
     * A figure the tale makes in [slot] of [t]: a [role] (and [look]; -1 any) standing at [at] playing [clip], made only while a
     * player is within 64 blocks of it, at most six for a tale, never saved. The cast gets a member with no resident id and a name
     * from `Names` if the slot has none. Asked again for the same slot it moves the mark and changes the clip (so a template can ask
     * in `enter` and again in `scene`, which is how a figure comes back after a restart). False if scenes are off, the tale is over,
     * the slot is a resident's, or the tale has six.
     */
    fun figure(
        level: ServerLevel, t: Tale, slot: String, at: BlockPos, role: TownRole, look: Int = -1, clip: Activity? = null, yaw: Float? = null,
    ): Boolean {
        if (!Tales.scenes() || !t.open) return false
        val k = key(t.id, slot)
        figures[k]?.let { f ->
            f.mark = at
            f.clip = clip
            f.yaw = yaw
            return true
        }
        if (figures.values.count { it.tale == t.id } >= TaleLimits.FIGURES) return false
        val existing = t.cast(slot)
        if (existing != null && existing.resident >= 0) return false
        val data = TaleData.get(level)
        val seed = t.id * 31L + slot.hashCode()
        val chosen = if (look in 0 until role.looks) look else java.util.Random(seed).nextInt(maxOf(1, role.common))
        val member = existing ?: Cast(slot, -1, nameFor(level, role, chosen, at, seed))
        if (existing == null) {
            if (!t.addCast(member)) return false
            data.changed()
        } else if (member.name.isEmpty()) {
            member.name = nameFor(level, role, chosen, at, seed)
            data.changed()
        }
        val f = Figure(t.id, slot, at, yaw, role, chosen, clip, member.name)
        figures[k] = f
        ensureFigure(level, f)
        return true
    }

    private fun nameFor(level: ServerLevel, role: TownRole, look: Int, at: BlockPos, seed: Long): String {
        val style = Capitals.covering(level, at)?.style
        return "${Names.given(style, Names.woman(role, look, seed), seed)} ${Names.family(style, seed)}"
    }

    private fun ensureFigure(level: ServerLevel, f: Figure) {
        val now = level.gameTime
        val mark = f.mark
        val b = figureBody(level, f)
        if (b == null) {
            f.body = null
            f.farSince = -1
            val near = level.getNearestPlayer(mark.x + 0.5, mark.y + 0.5, mark.z + 0.5, TaleLimits.FIGURE_RANGE.toDouble(), false) ?: return
            if (!level.isLoaded(mark)) return
            if (near.distanceToSqr(mark.x + 0.5, mark.y + 0.5, mark.z + 0.5) > SHOWN * SHOWN && !Comings.unseen(level, mark)) return
            val stand = Sets.standable(level, mark) ?: return
            val p = Townsfolk.spawn(level, stand, f.role, look = f.look) ?: return
            p.tale = f.tale
            p.customName = Component.literal(f.name)
            f.yaw?.let {
                p.snapTo(p.x, p.y, p.z, it, 0f)
                p.yHeadRot = it
                p.yBodyRot = it
            }
            f.body = p.uuid
            val clip = f.clip
            p.director = TaleHold(stand, f.yaw, clip, if (clip != null && sitting(clip)) seatNear(level, stand) else null)
            return
        }
        // Only while a player is within reach of the mark
        if (level.getNearestPlayer(mark.x + 0.5, mark.y + 0.5, mark.z + 0.5, LEFT_FAR, false) == null) {
            if (f.farSince < 0) f.farSince = now else if (now - f.farSince >= FAR_FOR) {
                b.discard()
                f.body = null
                f.farSince = -1
            }
        } else f.farSince = -1
        val d = b.director
        if (d is TaleHold) {
            if (d.mark != f.mark) d.mark = f.mark
            if (d.clip != f.clip) d.clip = f.clip
        }
    }

    /** The figure [f] is done with: out by the gate if a player can see it (and [visible]), else gone where it stands. */
    private fun retire(level: ServerLevel, f: Figure, visible: Boolean) {
        figures.remove(key(f.tale, f.slot))
        val b = figureBody(level, f) ?: return
        val way = if (visible && !Comings.unseen(level, b.blockPosition())) exitWay(level, b.blockPosition()) else null
        if (way == null) {
            b.discard()
            return
        }
        escorts.remove(b.uuid)
        b.routine.interrupt()
        b.navigation.stop()
        b.director = TaleLeave(way) { it.discard() }
    }

    // ---- The body's own tick, and the world's, once a second ----

    /**
     * From a tagged body's tick, each second: it is looked after whether or not a player is in a capital. A body whose tale is gone,
     * over, switched off or no longer holds it is let go (a resident goes about their day, a figure is discarded); one that has lost
     * its director (a talk is over) is given it back.
     */
    fun tend(level: ServerLevel, p: Townsperson) {
        TaleLook.guarded(level, "the care of a tale body") { see(level, p) }
    }

    private fun see(level: ServerLevel, p: Townsperson) {
        if (p.tale < 0 || p.director is TaleLeave) return
        val t = if (Tales.scenes()) TaleData.get(level)[p.tale] else null
        val wanted = t != null && t.open && if (p.resident >= 0) t.cast.any { it.resident == p.resident && it.held } else figures.values.any { it.body == p.uuid }
        if (!wanted) {
            drop(level, p)
            return
        }
        if (p.director != null || p.attending != null) return
        val h = held.values.firstOrNull { it.body == p.uuid } ?: t?.let { tt -> tt.cast.firstOrNull { it.resident == p.resident }?.let { held[key(tt.id, it.slot)] } }
        give(level, p, h)
    }

    /** A tagged body nothing wants any more. */
    private fun drop(level: ServerLevel, p: Townsperson) {
        if (p.resident < 0) {
            escorts.remove(p.uuid)
            p.discard()
            return
        }
        untag(level, p, Residents.get(level)[p.resident])
    }

    /** Once a second for the whole world: the held residents and the figures are seen to wherever a player is, in a capital or on the road. */
    fun tidy(server: MinecraftServer) {
        val level = server.overworld()
        if (!Tales.scenes()) {
            if (held.isNotEmpty() || figures.isNotEmpty() || escorts.isNotEmpty()) pause(level)
            return
        }
        escorts.entries.removeIf { (id, e) -> e.done || level.getEntity(id) == null }
        if (level.gameTime < hush || level.players().isEmpty()) return
        val data = TaleData.get(level)
        for (t in data.all()) {
            if (!t.open) continue
            for (c in t.cast) {
                if (c.held && c.resident >= 0) ensure(level, t, c, held.getOrPut(key(t.id, c.slot)) { Held(t.id, c.slot) })
            }
        }
        for (f in figures.values.toList()) {
            val t = data[f.tale]
            if (t == null || !t.open) retire(level, f, visible = true) else ensureFigure(level, f)
        }
        held.entries.removeIf { (_, h) -> data[h.tale]?.let { t -> !t.open || t.cast(h.slot)?.held != true } ?: true }
    }

    // ---- For the switch, the commands and the stop ----

    /**
     * The switch went off (or `tale figures release`): the figures are gone, the held residents' bodies go about their day and the
     * walks end. Nothing is written to the record: its `held` flags stay as they are, and the tidy holds them again if tales come on.
     */
    fun pause(level: ServerLevel) {
        for (f in figures.values.toList()) retire(level, f, visible = false)
        figures.clear()
        for ((id, e) in escorts.entries.toList()) (level.getEntity(id) as? Townsperson)?.let { e.cancel(it) }
        escorts.clear()
        val data = TaleData.get(level)
        for (t in data.all()) {
            for (c in t.cast) {
                if (!c.held || c.resident < 0) continue
                val r = Residents.get(level)[c.resident] ?: continue
                val b = Comings.body(level, r) ?: continue
                if (b.tale >= 0) untag(level, b, r)
            }
        }
        held.clear()
    }

    /** `tale figures release`: everything is let go, and nothing is held or made again for a minute. */
    fun releaseAll(level: ServerLevel) {
        hush = level.gameTime + HUSH
        pause(level)
    }

    /** One line for each figure and each held resident, for `tale figures`. */
    fun listing(level: ServerLevel): List<String> {
        val out = ArrayList<String>()
        for (f in figures.values.sortedBy { it.tale }) {
            val b = figureBody(level, f)
            out += "tale #${f.tale} ${f.slot}: ${f.name}, a ${f.role.id}, " + (b?.let { "at ${it.blockPosition().toShortString()}" } ?: "not made (waits for a player within ${TaleLimits.FIGURE_RANGE} blocks of ${f.mark.toShortString()})")
        }
        val data = TaleData.get(level)
        val on = Tales.scenes()
        for (t in data.all()) {
            if (!t.open) continue
            for (c in t.cast) {
                if (!c.held || c.resident < 0) continue
                val r = Residents.get(level)[c.resident]
                val b = r?.let { Comings.body(level, it) }
                val h = held[key(t.id, c.slot)]
                out += "tale #${t.id} ${c.slot}: ${r?.name ?: c.name} held" +
                    (if (on) "" else " in the record only (the scenes are switched off)") +
                    (b?.let { ", " + (if (it.tale == t.id) "tale body" else "day body") + " at ${it.blockPosition().toShortString()}" } ?: ", no body about") +
                    (h?.mark?.let { ", mark ${it.toShortString()}" } ?: "")
            }
        }
        return out
    }

    /** The server stops: all of it forgotten. */
    fun forget() {
        held.clear()
        figures.clear()
        escorts.clear()
        hush = 0L
    }
}
