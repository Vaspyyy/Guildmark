package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleEscort
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The lantern's living part (living-world/stories/design.md 8.9): where the old resident is held and when, the slow walk to the square
 * (Tales B's `TaleEscort`, led: they walk the way at their own pace with the player beside them), what happens when they get there,
 * and their lantern going up with the town's. Everything here is scenery and memory; the record is [Lantern]'s numbers, and whatever is
 * lost to a restart (the walk, a scene) is taken up again from them within a second ([second]).
 *
 * - **The door.** From ten to five on the days before the festival, and from ten to nine on the day, the old resident is held at their
 *   front door (sitting on the step or a bench by it if there is one); outside those hours they go about their day.
 * - **The walk.** "Shall we walk to the square?" sets them out ([setOut]) for a place a few blocks out from the fountain's rim on their
 *   side of it, the lantern alight in their hand (the townsfolk's own lantern, which the HOBBLE leaves the free hand for). A walk broken
 *   by a restart is taken up again from where they stand when the one who walked with them is near. At eight (the festival down) a walk
 *   still on the way is over: they say it is too late and the tale ends C.
 * - **At the square** ([arrived]) they say they are there and wait, sitting if there is a seat by them, until the town gathers at six.
 *   When the town's lanterns begin ([lanterns]) with them within the fountain's rim and six blocks, they let theirs go on the same tick
 *   (A); come after that, or at seven at a square with no festival, they let it go themselves (B).
 *
 * An ending is decided here ([Lantern.N_ROUTE]) and made at the end of the scene that shows it (or at once if there is no scene, and in
 * any case within a minute), so the banner comes after the lantern has gone up.
 */
internal object LanternWalk {
    /** How far out from the fountain's rim the old resident's place at the square is, and how far out still counts as at the square. */
    private const val PLACE = 5.0
    private const val AT = 6.0

    /** How near the one who walked must be for a broken walk to be taken up again, and how near anyone else who is in the tale. */
    private const val RESUME = 16.0
    private const val RESUME_OTHER = 8.0

    /** How near the old resident a player who is in the tale must be at the square to have walked too. */
    private const val WALKED_WITH = 16.0

    /** How long an ending decided waits for its scene before it is made anyway (ticks). */
    private const val ROUTE_WAITS = 1200L

    /** The game time each tale's ending was decided at, and the mark each is held at: memory, rebuilt from the record. */
    private val routed = HashMap<Int, Long>()
    private val marks = HashMap<Int, Long>()
    private val standing = HashMap<Int, Long>()

    /** Each tale's walk on the way, as [TaleEscort.start] began it: one missing or over while the record says they are on the way was lost. */
    private val walks = HashMap<Int, TaleEscort>()

    // ---- The square ----

    /** The middle of the square: the live Lanternrise's centre, else the fountain of the plan. */
    fun centre(c: Ctx, t: Tale): Vec3 {
        LanternClock.site(t)?.let { return it.centre }
        val plan = c.here.places.census.plan
        return Vec3.atBottomCenterOf(c.here.capital.standing(plan, plan.fountain))
    }

    /** How far from [centre] is at the square: the fountain's rim and six blocks. */
    fun reach(c: Ctx): Double = c.here.places.census.plan.fountainRadius + AT

    /** The old resident's place at the square, on the side of it [from] is on (their door), or null if none can be stood on. */
    fun place(c: Ctx, t: Tale, from: Vec3): BlockPos? = Sets.beside(c.level, centre(c, t), from, c.here.places.census.plan.fountainRadius + PLACE)

    /** Their place at the square as they set out for it (kept in the record, so a walk taken up again keeps to it), or null. */
    fun square(t: Tale): BlockPos? = t.s[Lantern.S_SQUARE].toLongOrNull()?.let { BlockPos.of(it) }

    /** Whether [p] is at the square. */
    fun atSquare(c: Ctx, t: Tale, p: Townsperson): Boolean {
        val at = centre(c, t)
        val dx = p.x - at.x
        val dz = p.z - at.z
        return sqrt(dx * dx + dz * dz) <= reach(c) && abs(p.y - at.y) < 8.0
    }

    // ---- The walk ----

    /**
     * "Shall we walk to the square?" (8.9 step 2): [old] sets out with [player] for their place at the square, the lantern alight in
     * their hand. False if they cannot be taken just now (the talk then says it is not the time).
     */
    fun setOut(c: Ctx, t: Tale, player: ServerPlayer, old: Townsperson): Boolean {
        if (!Tales.scenes() || t.step != Lantern.AT_WALK || t.n[Lantern.N_WALK] != 0) return false
        val from = LanternClock.door(t)?.let { Vec3.atBottomCenterOf(it) } ?: old.position()
        val target = place(c, t, from) ?: return false
        if (!walk(c.level, t, old, player, target)) return false
        t.n[Lantern.N_WALK] = 1
        t.setS(Lantern.S_WALKER, player.uuid.toString())
        t.setS(Lantern.S_SQUARE, target.asLong().toString())
        t.partFor(player.uuid)
        c.why(t, "lantern.walk", Lantern.given(t), player.gameProfile.name)
        c.data.changed()
        return true
    }

    /** The walk itself: [old] led by the way to [target], [player] beside them, the lantern alight in their hand. */
    private fun walk(level: ServerLevel, t: Tale, old: Townsperson, player: ServerPlayer, target: BlockPos): Boolean {
        if (old.seat != null) old.standUp()
        if (!TaleEscort.start(level, t, Lantern.OLD, old, player, target, leads = true)) return false
        (old.director as? TaleEscort)?.let { walks[t.id] = it }
        clearProp(old)
        old.lantern = true
        return true
    }

    /** Whether [t]'s walk was lost on the way (a restart, the switch off and on): the record says they are on the way and no walk has them. */
    fun lost(t: Tale): Boolean = t.n[Lantern.N_WALK] == 1 && walks[t.id]?.done != false

    /**
     * "Shall we walk to the square?" said to them again where a walk was lost: they go on from where they stand with [player], to the
     * same place. False if they cannot be taken just now.
     */
    fun resume(c: Ctx, t: Tale, player: ServerPlayer, old: Townsperson): Boolean {
        if (!Tales.scenes() || t.step != Lantern.AT_WALK || !lost(t)) return false
        val target = square(t) ?: place(c, t, LanternClock.door(t)?.let { Vec3.atBottomCenterOf(it) } ?: old.position()) ?: return false
        if (!walk(c.level, t, old, player, target)) return false
        t.setS(Lantern.S_WALKER, player.uuid.toString())
        t.setS(Lantern.S_SQUARE, target.asLong().toString())
        t.partFor(player.uuid)
        c.data.changed()
        return true
    }

    /** From [Lantern.heard]: the old resident is at their place at the square. */
    fun arrived(c: Ctx, t: Tale) {
        if (t.step != Lantern.AT_WALK || t.n[Lantern.N_WALK] != 1) return
        val old = TaleBodies.body(c.level, t, Lantern.OLD)
        reached(c, t, old)
        c.next(t)
        val begun = t.n[Lantern.N_BEGUN] != 0 || LanternClock.site(t)?.let { it.local(c.level) >= io.github.vaspyyy.guildmark.festival.FestivalSite.LANTERNS_AT + 600 } == true
        val late = begun || LanternClock.over(c.level, t) || LanternClock.site(t) == null && (LanternClock.dusk(c.level, t) || gone(c, t))
        if (late && old != null) {
            // The town's lanterns are up already, or there is no festival and it is dusk: they let theirs go now
            letGo(c, t, old, Lantern.ROUTE_B, "tale.lantern.arrive")
            return
        }
        if (late) {
            route(c, t, Lantern.ROUTE_B)
            return
        }
        if (old != null) {
            Words.sayWith(old, "tale.lantern.arrive", why = "a tale: tale.lantern.arrive")
            settle(c, t, old)
        }
    }

    /** The walk is done: whoever walked with them has walked, and the record says they are there. */
    private fun reached(c: Ctx, t: Tale, old: Townsperson?) {
        t.n[Lantern.N_WALK] = 2
        val walker = walker(t)
        for (p in t.parts) {
            val near = old != null && (c.level.getPlayerByUUID(p.player)?.let { it.distanceToSqr(old) <= WALKED_WITH * WALKED_WITH } == true)
            val joined = p.status == Part.Status.IN || p.status == Part.Status.DONE
            if (p.player == walker || joined && near) p.did = p.did or Part.WALKED
        }
        c.why(t, "lantern.arrived", Lantern.given(t))
        c.data.changed()
    }

    /** At the square to wait for the lanterns: held where they stopped, sitting if there is a seat by them. */
    private fun settle(c: Ctx, t: Tale, old: Townsperson) {
        val at = old.blockPosition()
        val seat = Places.seat(c.level, at, 3, old)
        c.hold(t, Lantern.OLD, at, if (seat != null) Activity.SIT else null)
        marks[t.id] = at.asLong()
        old.lantern = true
    }

    // ---- The lanterns ----

    /**
     * From [Lantern.heard]: the Lanternrise's lanterns of the square at [e]'s centre are about to go up, or go up. When they begin with the
     * old resident at the square (or walked there by the record) they let theirs go with the town's, on the same tick (A): RELEASE, the
     * release line, and at the town's let-go the sky lantern from their hands; then they gaze after it, thank the player, and the tale ends.
     */
    fun lanterns(c: Ctx, t: Tale, e: Event.Lanterns) {
        val site = LanternClock.siteAt(e.centre) ?: return
        if (site.key != t.capital) return
        val old = TaleBodies.body(c.level, t, Lantern.OLD)
        if (!e.release) {
            if (t.n[Lantern.N_BEGUN] == 0) {
                t.n[Lantern.N_BEGUN] = 1
                c.data.changed()
            }
            if (t.n[Lantern.N_ROUTE] != 0) return
            val walking = t.n[Lantern.N_WALK] == 1 && old != null && atSquare(c, t, old)
            if (t.n[Lantern.N_WALK] != 2 && !walking) return
            if (walking) {
                // Come into the square as the lanterns begin: the walk is done here
                (old?.director as? TaleEscort)?.cancel(old)
                reached(c, t, old)
                c.go(t, Lantern.AT_WATCH)
            }
            t.n[Lantern.N_ROUTE] = Lantern.ROUTE_A
            t.n[Lantern.N_LETGO] = 1
            routed[t.id] = c.level.gameTime
            c.data.changed()
            if (old != null && Tales.scenes()) withTheTown(c, t, old)
            return
        }
        if (t.n[Lantern.N_ROUTE] != Lantern.ROUTE_A || t.n[Lantern.N_LETGO] != 1) return
        t.n[Lantern.N_LETGO] = 2
        c.data.changed()
        if (old == null || old.activity != Activity.RELEASE || !Tales.scenes()) {
            c.end(t, Ending.A)
            return
        }
        LanternScenes.rise(old)
        val stage = LanternScenes.afterRelease(old, thanked(c.level, t, old))
        endWith(c, t, stage, Ending.A)
    }

    /** RELEASE on the tick the town's begins, as the festival has its own do; the hold gazes after it if no scene takes them. */
    private fun withTheTown(c: Ctx, t: Tale, old: Townsperson) {
        (old.director as? TaleEscort)?.cancel(old)
        if (old.seat != null) old.standUp()
        old.navigation.stop()
        clearProp(old)
        old.lantern = true
        c.hold(t, Lantern.OLD, old.blockPosition(), Activity.GAZE)
        old.act(Activity.RELEASE)
        Words.sayWith(old, "tale.lantern.release", why = "a tale: tale.lantern.release")
    }

    /** The tale's own let-go (B, or C at the door): decided now, played as the scene [LanternScenes.letGo], ended as it ends. */
    private fun letGo(c: Ctx, t: Tale, old: Townsperson, route: Int, line: String?) {
        route(c, t, route)
        val ending = ending(route)
        val stage = LanternScenes.letGo(old, thanked(c.level, t, old), line)
        endWith(c, t, stage, ending)
    }

    /** Decides [t]'s ending by [route]; it is made at the end of its scene, or by [second] a minute on if none plays. */
    private fun route(c: Ctx, t: Tale, route: Int) {
        t.n[Lantern.N_ROUTE] = route
        routed[t.id] = c.level.gameTime
        c.data.changed()
    }

    /** Queues [stage] on the capital's stage for everyone to see; as it ends (played or not) [t] ends with [ending]. */
    private fun endWith(c: Ctx, t: Tale, stage: Stage, ending: String) {
        val level = c.level
        val id = t.id
        val capital = t.capital
        stage.then = { _ ->
            val tale = TaleData.get(level)[id]
            val cc = TaleEngine.ctx(level, capital)
            if (tale != null && tale.open && cc != null) {
                tale.n[Lantern.N_LETGO] = 2
                cc.end(tale, ending)
            }
        }
        if (!TaleLook.queue(level, capital, stage, urgent = true, hears = true)) {
            stage.then = null
            t.n[Lantern.N_LETGO] = 2
            c.end(t, ending)
        }
    }

    /** The player the old resident thanks: the one who walked with them if near, else the nearest within twelve blocks. */
    private fun thanked(level: ServerLevel, t: Tale, old: Townsperson): Player? {
        val walker = walker(t)?.let { level.getPlayerByUUID(it) }?.takeIf { it.distanceToSqr(old) <= 16.0 * 16.0 }
        return walker ?: level.getNearestPlayer(old, 12.0)
    }

    /** The player who set out with them, or null. */
    fun walker(t: Tale): UUID? = try {
        t.s[Lantern.S_WALKER].takeIf { it.isNotBlank() }?.let { UUID.fromString(it) }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The ending of [route]. */
    fun ending(route: Int): String = when (route) {
        Lantern.ROUTE_A -> Ending.A
        Lantern.ROUTE_B -> Ending.B
        Lantern.ROUTE_C -> Ending.C
        else -> Ending.W
    }

    // ---- Once a second ----

    /**
     * Once a second for each open lantern tale ([LanternFestival]): holds the old resident at the door in its hours, takes a broken walk
     * up again, calls off a walk still on the way when the festival comes down (C), has them stand to watch when the town gathers, lets
     * the lantern go at seven at a square with no festival (B), and makes an ending decided a minute ago whose scene never played.
     */
    fun second(level: ServerLevel, t: Tale) {
        val c = TaleEngine.ctx(level, t.capital) ?: return
        val route = t.n[Lantern.N_ROUTE]
        if (route != 0) {
            val since = routed.getOrPut(t.id) { level.gameTime }
            if (level.gameTime - since > ROUTE_WAITS) c.end(t, ending(route))
            return
        }
        if (!Tales.scenes()) return
        val old = TaleBodies.body(level, t, Lantern.OLD)
        when (t.n[Lantern.N_WALK]) {
            0 -> door(c, t, old)
            1 -> walking(c, t, old)
            else -> waiting(c, t, old)
        }
    }

    /** At the door in its hours, and about their day outside them. */
    private fun door(c: Ctx, t: Tale, old: Townsperson?) {
        val door = LanternClock.door(t) ?: return
        val held = t.cast(Lantern.OLD)?.held == true
        if (!LanternClock.atDoor(c.level, t)) {
            if (held) {
                old?.let { clearProp(it) }
                c.release(t, Lantern.OLD)
                marks.remove(t.id)
            }
            return
        }
        if (held && marks[t.id] == door.asLong()) return
        val seat = old?.let { Places.seat(c.level, door, 3, it) }
        if (c.hold(t, Lantern.OLD, door, if (seat != null) Activity.SIT else null)) marks[t.id] = door.asLong()
    }

    /** On the way: the lantern in their hand, the mark kept with them, a broken walk taken up again, and at eight it is over. */
    private fun walking(c: Ctx, t: Tale, old: Townsperson?) {
        if (LanternClock.over(c.level, t)) {
            // The festival is down and they are not there: too late now
            if (old != null) {
                (old.director as? TaleEscort)?.cancel(old)
                Words.sayWith(old, "tale.lantern.late", why = "a tale: tale.lantern.late")
            }
            c.why(t, "lantern.late")
            c.end(t, if (t.n[Lantern.N_CANDLE] != 0) Ending.C else Ending.W)
            return
        }
        if (old == null) return
        old.lantern = true
        // Where they have got to is where a body made again after a restart stands
        if (c.level.gameTime % 100L < 20L) {
            c.hold(t, Lantern.OLD, old.blockPosition(), null)
            marks[t.id] = old.blockPosition().asLong()
        }
        if (!lost(t) || old.attending != null || old.director !is io.github.vaspyyy.guildmark.tales.TaleHold) return
        // The walk was lost (a restart, the switch): taken up again from here by whoever walked with them, or anyone in the tale beside them
        val walker = walker(t)?.let { c.level.getPlayerByUUID(it) as? ServerPlayer }?.takeIf { it.distanceToSqr(old) <= RESUME * RESUME }
        val other = t.parts.filter { it.status == Part.Status.IN || it.status == Part.Status.DONE }
            .mapNotNull { c.level.getPlayerByUUID(it.player) as? ServerPlayer }.firstOrNull { it.distanceToSqr(old) <= RESUME_OTHER * RESUME_OTHER }
        val with = walker ?: other ?: return
        resume(c, t, with, old)
    }

    /** At the square: up on their feet when the town gathers; at seven, with no festival here, the lantern goes up beside the fountain. */
    private fun waiting(c: Ctx, t: Tale, old: Townsperson?) {
        val site = LanternClock.site(t)
        if (site == null && (LanternClock.dusk(c.level, t) || LanternClock.over(c.level, t) || gone(c, t))) {
            if (old != null) letGo(c, t, old, Lantern.ROUTE_B, null) else route(c, t, Lantern.ROUTE_B)
            return
        }
        if (site != null && site.phase == io.github.vaspyyy.guildmark.festival.FestivalPhase.TEARDOWN) {
            // The festival came down and no lanterns were seen to go (nobody near enough for it to keep its evening): theirs goes up as it ends
            if (old != null) letGo(c, t, old, Lantern.ROUTE_B, null) else route(c, t, Lantern.ROUTE_B)
            return
        }
        if (old == null) return
        old.lantern = true
        if (!LanternClock.gathering(c.level, t) || old.seat == null || old.director !is io.github.vaspyyy.guildmark.tales.TaleHold) return
        // Up on their feet to watch as the town gathers: the clip first (the hold no longer sits them), then onto the floor before the seat
        val since = standing[t.id]
        if (since == null) {
            c.hold(t, Lantern.OLD, old.blockPosition(), null)
            old.act(Activity.STAND_UP)
            standing[t.id] = c.level.gameTime
            return
        }
        if (c.level.gameTime - since < Activity.STAND_UP.ticks) return
        standing.remove(t.id)
        old.standUp()
        c.hold(t, Lantern.OLD, old.blockPosition(), null)
        marks[t.id] = old.blockPosition().asLong()
    }

    /**
     * Whether the Lanternrise they walked for has gone without its lanterns: none live, and it is not the festival's day (a forced one
     * stopped, or over, before the day the record keeps). They let theirs go where they are rather than wait days for the calendar's.
     */
    private fun gone(c: Ctx, t: Tale): Boolean = LanternClock.site(t) == null && !LanternClock.festivalDay(c.level, t) && LanternClock.today(c.level, t) < LanternClock.festival(t)

    // ---- Tidy ----

    /** A paper lantern of this tale's in [p]'s hands is taken out of them (they are given back to their day with empty hands). */
    fun clearProp(p: Townsperson) {
        val held = p.carried
        if (held.isEmpty) return
        if (ItemStack.isSameItemSameComponents(held, LanternScenes.lantern(false)) || ItemStack.isSameItemSameComponents(held, LanternScenes.lantern(true))) {
            p.carried = ItemStack.EMPTY
            p.carriedUntil = 0
        }
    }

    /** [t] has ended: its memory is let go, and the old resident's lantern is put away. */
    fun ended(level: ServerLevel, t: Tale) {
        routed.remove(t.id)
        marks.remove(t.id)
        standing.remove(t.id)
        walks.remove(t.id)
        TaleBodies.body(level, t, Lantern.OLD)?.let {
            clearProp(it)
            if (it.activity != Activity.RELEASE) it.lantern = false
        }
    }

    /** Everything forgotten, as the server stops. */
    fun forget() {
        routed.clear()
        marks.clear()
        standing.clear()
        walks.clear()
    }
}
