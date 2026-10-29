package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Lamplighter
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageSounds
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.days.BellKeeper
import io.github.vaspyyy.guildmark.village.live.links.Greeter
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.TransparentBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A village called to shelter (living-world/villages/design.md section 11.2): its folk go to the nearest door, bolt it, and
 * come out at the all-clear. The names and signatures are the contract of design section 19.3, filled in by part V10.
 *
 * [call] takes every able body of the village within reach off their day (a [Sheltering] director each, as the lamplighters are
 * taken) and has the bell rung hard: by the bellman if they are near it (the bell's own strokes, `Townsperson.greatBell`), else by
 * itself. Each door they run for is a [Haven]: the first in pushes it to and bolts it, a latecomer knocks and someone inside lets
 * them in, and those inside huddle in the corners away from the door and the windows, whispering. [end] is the all-clear, with how it
 * ended ([Outcome]): the bell's slow strokes, the doors opening one by one, a cheer, or a sigh and a slow walk to the gap.
 *
 * Nobody is ever shut in: the shelter ends at the siege's or the raid's own end, when no monster is within [QUIET_REACH] blocks of the
 * bell for ten seconds (and the event's own mobs are gone), and at the latest [CAP] ticks after the call, or after the event's last
 * mob was seen near; with the switch off, at a restart (nothing here is saved), and for anyone hurt or taken by something else.
 */
object Shelter {
    /** What the village is sheltering from. */
    enum class Why { SIEGE, RAID }

    /** How a shelter ended: by itself (quiet), a siege held or lost, a raid beaten off or one of the folk taken. */
    enum class Outcome { QUIET, HELD, LOST, BEATEN, TAKEN }

    /** The longest a shelter holds after its call (or after the event's last mob was near): five minutes. */
    const val CAP = 6000L

    /** No monster this near the bell, for [QUIET_TICKS], ends a shelter by itself. */
    const val QUIET_REACH = 40.0
    private const val QUIET_TICKS = 200L

    /** Ticks after a call before the quiet can end it: the horde gathers out of sight first. */
    private const val GRACE = 600L

    /** How far a door is looked for. */
    const val DOOR_REACH = 40.0

    /** Someone handed back (hurt, taken by another) is taken again this long after. */
    private const val AGAIN = 100

    /** A shelter under way in one village. */
    class Called(val key: Long, val bell: BlockPos, var reach: Int, val why: Why, var from: BlockPos?, val at: Long, val walled: Boolean) {
        /** The game time it ends by itself at the latest. */
        var until = at + CAP
        /** When the bell's ground was first seen clear of monsters, or -1. */
        var quietFrom = -1L
        /** How it ended, once the all-clear is given; [over] once everyone is out. */
        var ending: Outcome? = null
        var endedAt = 0L
        var over = false
        /** Those sheltering, by entity id. */
        val folk = HashMap<Int, Sheltering>()
        /** The doors, by the door's place. */
        val havens = HashMap<Long, Haven>()
        /** Who was let go when, by entity id, so they are taken again only after [AGAIN] ticks. */
        val handed = HashMap<Int, Int>()
        /** The alert lines said so far (the first two to hear shout). */
        var shouts = 0
        /** Whether the cheer of the all-clear has sounded, and the lost line been said. */
        var cheered = false
        var lostSaid = false
        var allClearSaid = false

        /** The direction the trouble comes from, as seen from the bell: the horde's gathering place, else nowhere in particular. */
        fun toward(): Vec3 = Vec3.atCenterOf(from ?: bell)
    }

    /** One door of the village with folk behind it. */
    class Haven(val door: CityPlaces.Door, val lodging: CityPlaces.Lodging?) {
        /** Who bolts it (the first in), or null before anyone is. */
        var bolter: Sheltering? = null
        var bolted = false
        /** Those who chose it. */
        val folk = ArrayList<Sheltering>()
        /** The spots inside, the farthest from the door and the windows first, and who has which. */
        var spots: List<BlockPos>? = null
        val taken = HashMap<Long, Int>()
        /** Latecomers knocking, the first first. */
        val knocking = ArrayList<Sheltering>()
        /** When someone behind it next whispers. */
        var whisper = 0L

        /** Where to stand to push the door to and bolt it: its inner face half a block ahead (BOLT_DOOR's reach), in the doorway's middle. */
        fun stand(level: ServerLevel): Vec3 {
            val c = Vec3.atBottomCenterOf(door.pos)
            val inward = door.inward
            val state = level.getBlockState(door.pos)
            // A shut door's leaf lies against the side opposite its facing (DoorBlock's shapes): on the inside face, or the outside
            val face = if (state.block is DoorBlock) {
                val leaf = state.getValue(DoorBlock.FACING).opposite
                when (leaf) {
                    inward -> 0.0
                    inward.opposite -> -13.0 / 16.0
                    else -> 0.0
                }
            } else 0.0
            val d = 1.0 + face
            return Vec3(c.x + inward.stepX * d, c.y, c.z + inward.stepZ * d)
        }

        /** The spot inside for [s], held for them: the farthest from the door and the windows still free; null if none (they stand by the door). */
        fun spotFor(level: ServerLevel, s: Sheltering, nextTo: BlockPos? = null): BlockPos? {
            val all = spots ?: inside(level).also { spots = it }
            taken.entries.removeIf { it.value == s.p.id }
            val free = all.filter { it.asLong() !in taken }
            val pick = if (nextTo != null) free.minByOrNull { it.distSqr(nextTo) }?.takeIf { it.distSqr(nextTo) <= 4 } ?: free.firstOrNull() else free.firstOrNull()
            pick?.let { taken[it.asLong()] = s.p.id }
            return pick
        }

        /** The standing places on the ground floor inside, far from the door and from glass first. */
        private fun inside(level: ServerLevel): List<BlockPos> {
            val l = lodging ?: return listOf(door.inside)
            val y = door.pos.y
            val out = ArrayList<Pair<BlockPos, Double>>()
            val box = l.box
            for (x in box.minX()..box.maxX()) for (z in box.minZ()..box.maxZ()) {
                val pos = BlockPos(x, y, z)
                if (!level.isLoaded(pos) || pos == door.inside) continue
                if (!Places.standable(level, pos)) continue
                val glass = Direction.Plane.HORIZONTAL.count { glassy(level, pos.relative(it)) || glassy(level, pos.relative(it).above()) }
                val bed = level.getBlockState(pos.below()).`is`(BlockTags.BEDS)
                if (bed) continue
                out += pos to (pos.distSqr(door.pos) - glass * 12.0)
                if (out.size > 96) break
            }
            if (out.isEmpty()) return listOf(door.inside)
            return out.sortedByDescending { it.second }.map { it.first }
        }

        private fun glassy(level: ServerLevel, pos: BlockPos): Boolean {
            val b = level.getBlockState(pos).block
            return b is TransparentBlock || b is IronBarsBlock
        }

        /** Whether [pos] is inside this door's house. */
        fun contains(pos: BlockPos): Boolean = lodging?.contains(pos) ?: (pos == door.inside)
    }

    private val called = HashMap<Long, Called>()

    /** Whether any village is sheltering. */
    fun any(): Boolean = called.isNotEmpty()

    /** The shelter of the village [key], if one is under way. */
    fun of(key: Long): Called? = called[key]

    /** Whether the village [key] is sheltering now (the all-clear not yet given). */
    fun sheltering(key: Long): Boolean = called[key]?.let { it.ending == null } == true

    /** Calls the village [key], whose bell is at [bell], to shelter from [why]: the folk within [reach] blocks of the bell go indoors. */
    fun call(level: ServerLevel, key: Long, bell: BlockPos, reach: Int, why: Why) = call(level, key, bell, reach, why, null)

    /** As [call], the trouble coming from [from] (the horde's gathering place, the raiders' way in): they turn to it as they hear. */
    fun call(level: ServerLevel, key: Long, bell: BlockPos, reach: Int, why: Why, from: BlockPos?) {
        val rec = Trouble.village(level, key) ?: return
        val now = level.gameTime
        val old = called[key]
        if (old != null && old.ending == null) {
            // Called again (the next wave): those who came out go back in
            old.reach = maxOf(old.reach, reach)
            if (from != null) old.from = from
            old.until = maxOf(old.until, now + CAP)
            gather(level, old)
            Watch.horn(level, old)
            return
        }
        // The all-clear of the last one still under way: those coming out go back in too
        old?.let { finishAll(it) }
        val c = Called(key, bell, reach, why, from, now, Trouble.walled(level, rec))
        called[key] = c
        Ringer.alarm(level, c)
        gather(level, c)
        Watch.horn(level, c)
        Trouble.log(level, rec, "sheltering from a ${why.name.lowercase()}: ${c.folk.size} going indoors")
    }

    /** The all-clear for the village [key]: those sheltering come out. */
    fun end(level: ServerLevel, key: Long) = end(level, key, Outcome.QUIET)

    /** The all-clear for the village [key], the trouble having ended [how]: the bell, the doors, the cheer or the sigh. */
    fun end(level: ServerLevel, key: Long, how: Outcome) {
        val c = called[key] ?: return
        if (c.ending != null) return
        c.ending = how
        c.endedAt = level.gameTime
        Ringer.stand(level, c)
        Watch.allClear(level, key, how)
        // The all-clear: three slow strokes for a siege held, two more for a raid beaten off, one for the rest
        when (how) {
            Outcome.HELD -> Trouble.ring(level, c.bell, 3, 40, after = 20)
            Outcome.BEATEN -> Trouble.ring(level, c.bell, 2, 40, after = 40)
            else -> Trouble.ring(level, c.bell, 1, 1, after = 30)
        }
        // They come out one by one, those nearest the bell first: 60 to 200 ticks
        val out = c.folk.values.sortedBy { it.p.distanceToSqr(Vec3.atCenterOf(c.bell)) }
        for ((k, s) in out.withIndex()) s.allClear(60 + (if (out.size <= 1) 0 else k * 140 / (out.size - 1)))
    }

    /** Everyone out now, every village (the switch turned off, the server stopping). */
    fun endAll(level: ServerLevel) {
        for (c in called.values.toList()) finishAll(c)
        called.clear()
        Ringer.forget()
    }

    /** At a server stop: nothing is kept. */
    fun forget() {
        called.clear()
        Ringer.forget()
    }

    private fun finishAll(c: Called) {
        for (s in c.folk.values.toList()) s.finish()
        c.folk.clear()
        c.over = true
    }

    /** Each tick: the alarm's ringer; every 20, the quiet, the cap, those let go taken again, and an end once everyone is out. */
    fun tick(level: ServerLevel) {
        if (called.isEmpty()) return
        Ringer.tick(level)
        val now = level.gameTime
        if (now % 20L != 0L) return
        for (c in called.values.toList()) {
            c.folk.values.removeIf { it.done }
            if (c.ending != null) {
                // Everyone out, or a minute after the all-clear at the most
                if (c.folk.isEmpty() || now - c.endedAt > 1200L) {
                    finishAll(c)
                    called.remove(c.key)
                }
                continue
            }
            if (Trouble.village(level, c.key) == null) {
                finishAll(c)
                called.remove(c.key)
                continue
            }
            val event = SiegeHorde.on(level, c.key) || RaidWatch.on(level, c.key)
            if (event) c.until = maxOf(c.until, now + 600L)
            val threat = event || monsters(level, c.bell)
            if (now - c.at < GRACE || threat) {
                c.quietFrom = -1L
            } else {
                if (c.quietFrom < 0) c.quietFrom = now
                if (now - c.quietFrom >= QUIET_TICKS) {
                    end(level, c.key, Outcome.QUIET)
                    continue
                }
            }
            if (now >= c.until) {
                end(level, c.key, Outcome.QUIET)
                continue
            }
            if (now % 100L == 0L) gather(level, c)
            Sheltering.whispers(level, c)
        }
    }

    /** Whether a monster stands within [QUIET_REACH] of [bell]. */
    private fun monsters(level: ServerLevel, bell: BlockPos): Boolean =
        level.getEntitiesOfClass(Mob::class.java, AABB(bell).inflate(QUIET_REACH, 16.0, QUIET_REACH)) { it is Enemy && it.isAlive && it !is Townsperson }.isNotEmpty()

    /** Takes off their day every able body of [c]'s village within its reach not sheltering yet. */
    private fun gather(level: ServerLevel, c: Called) {
        val reach = c.reach.toDouble()
        val folk = level.getEntitiesOfClass(Townsperson::class.java, AABB(c.bell).inflate(reach, 24.0, reach)) { able(level, c, it) }
        if (folk.isEmpty()) return
        val toward = c.toward()
        val order = folk.sortedBy { it.distanceToSqr(toward) }
        for (p in order) {
            if (c.folk.containsKey(p.id)) continue
            take(level, c, p)
        }
    }

    /** Whether [p] answers [c]'s call: one of its folk, alive and their own, not on the watch, not held by someone else's director. */
    private fun able(level: ServerLevel, c: Called, p: Townsperson): Boolean {
        if (!p.isAlive || p.isRemoved || p.captiveOf >= 0 || p.isLeader || p.isPassenger || p.fights || p.role == TownRole.GUARD || p.tale >= 0) return false
        // On a raider's rope: being led off, not sheltering
        if (p.isLeashed) return false
        if (p.crowd || p.watcher) return false
        if (c.folk[p.id]?.done == false) return false
        c.handed[p.id]?.let { if (p.tickCount - it < AGAIN && p.tickCount >= it) return false }
        if (p.hurtTime > 0) return false
        val d = p.director
        if (d != null && d !is Sheltering && d !is Lamplighter && d !is Greeter && d !is BuilderCrew.Builder) return false
        if (!Trouble.folkOf(level, p, c.key)) return false
        return level.isPositionEntityTicking(p.blockPosition())
    }

    /** [p] off their day and into the shelter. */
    private fun take(level: ServerLevel, c: Called, p: Townsperson) {
        when (val d = p.director) {
            is Greeter -> d.release()
            is BuilderCrew.Builder -> d.handBack()
            else -> {}
        }
        if (p.isSleeping) p.stopSleeping()
        p.standUp()
        p.routine.interrupt()
        p.navigation.stop()
        val far = p.distanceToSqr(c.toward()).let { kotlin.math.sqrt(it) }
        val alert = (10 + far / maxOf(1.0, c.reach.toDouble() + 18.0) * 20).toInt().coerceIn(10, 30)
        val shout = c.shouts < 2 && Trouble.fits(p, alertLine(c, p)) && p.role != TownRole.CHILD
        if (shout) c.shouts++
        val s = Sheltering(c, p, alert, shout)
        c.folk[p.id] = s
        p.director = s
    }

    /** The line [p] shouts at the horn: a raid's "Raiders! Get in!" where they have the voice for it, else the shelter's. */
    internal fun alertLine(c: Called, p: Townsperson): String {
        val first = if (c.why == Why.RAID) "shelter_alert_3" else "shelter_alert_1"
        return if (Trouble.fits(p, first) && (c.shouts == 0 || c.why == Why.RAID)) first else "shelter_alert_2"
    }

    /** [s] let go of the shelter (hurt, taken by something else): taken again [AGAIN] ticks from now, if it still holds. */
    internal fun handed(s: Sheltering) {
        s.called.handed[s.p.id] = s.p.tickCount
    }

    // ---- Where to go ----

    /** The kinds of building a person runs for, in the order they are looked for, and the barn last. */
    private val KINDS = listOf("HOUSE", "GUILD_HALL", "LIBRARY", "TEMPLE", "BAKERY", "SMITHY", "WORKSHOP")

    /**
     * The door [s] runs for: their home's if it is within [DOOR_REACH], else the nearest house, hall or library (or any building with
     * a door), else a barn; a child the door their household's grown-up runs for. Null with none within reach (they cower where they
     * stand, back to a wall).
     */
    internal fun havenFor(level: ServerLevel, s: Sheltering): Haven? {
        val c = s.called
        val p = s.p
        val places = Villages.places(level, c.key) ?: return null
        // A child goes with a grown-up of their home
        if (p.role == TownRole.CHILD) {
            val lead = household(level, s).firstOrNull { it.haven != null && it.p.role != TownRole.CHILD }
            lead?.haven?.let { return it }
        }
        val reach = DOOR_REACH * DOOR_REACH
        val here = p.blockPosition()
        val home = Days.life(p)?.home ?: homeLodging(level, p, places)
        home?.front?.let { if (it.pos.distSqr(here) <= reach) return haven(c, it, home) }
        for (l in places.nearest(here, KINDS)) {
            val door = l.front ?: continue
            if (door.pos.distSqr(here) > reach) break
            return haven(c, door, l)
        }
        for (l in places.nearest(here, listOf("BARN"))) {
            val door = l.front ?: continue
            if (door.pos.distSqr(here) > reach) break
            return haven(c, door, l)
        }
        return null
    }

    private fun homeLodging(level: ServerLevel, p: Townsperson, places: VillagePlaces): CityPlaces.Lodging? {
        if (p.resident < 0) return null
        val r = Residents.get(level)[p.resident] ?: return null
        return places.lodgings[r.home]
    }

    private fun haven(c: Called, door: CityPlaces.Door, lodging: CityPlaces.Lodging?): Haven = c.havens.getOrPut(door.pos.asLong()) { Haven(door, lodging) }

    /** The others of [s]'s household sheltering with them (residents of the same home). */
    internal fun household(level: ServerLevel, s: Sheltering): List<Sheltering> {
        val p = s.p
        if (p.resident < 0) return listOf()
        val residents = Residents.get(level)
        val home = residents[p.resident]?.home ?: return listOf()
        if (home < 0) return listOf()
        return s.called.folk.values.filter { it !== s && !it.done && it.p.resident >= 0 && residents[it.p.resident]?.home == home }
    }

    /** What `village watch` says of the shelter of [key]. */
    fun describe(key: Long, now: Long): List<String> {
        val c = called[key] ?: return listOf("not sheltering")
        val out = ArrayList<String>()
        val state = c.ending?.let { "the all-clear given (${it.name.lowercase()}) ${(now - c.endedAt) / 20}s ago" } ?: "sheltering from a ${c.why.name.lowercase()} for ${(now - c.at) / 20}s, until ${(c.until - now) / 20}s at the latest"
        out += state
        out += "${c.folk.size} sheltering, behind ${c.havens.values.count { it.folk.isNotEmpty() }} doors (${c.havens.values.count { it.bolted }} bolted)"
        val by = c.folk.values.groupingBy { it.stage }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
        if (by.isNotEmpty()) out += by
        if (c.quietFrom >= 0) out += "no monster within ${QUIET_REACH.toInt()} blocks of the bell for ${(now - c.quietFrom) / 20}s (the all-clear at ${QUIET_TICKS / 20}s)"
        return out
    }

    /** The day as [VillageBoards] counts it, for those who note what happened. */
    internal fun day(level: ServerLevel): Long = VillageBoards.day(level)

    /** The all-clear's cheer at the green, once, as the first come out. */
    internal fun cheer(level: ServerLevel, c: Called) {
        if (c.cheered) return
        c.cheered = true
        val places = Villages.places(level, c.key)
        val at = places?.market ?: c.bell
        VillageSounds.play(level, Vec3.atCenterOf(at).add(0.0, 1.0, 0.0), VillageSounds.CHEER)
    }

    /** The bell's own place, for the client's hush loop and the commands. */
    internal fun bellOf(key: Long): BlockPos = BlockPos.of(key)

    /** The bellman of [c]'s village today, if they are one of those able to ring. */
    internal fun bellman(level: ServerLevel, c: Called): Townsperson? {
        val id = BellKeeper.holder(level, c.bell, Days.dayOf(level.overworldClockTime)) ?: return null
        val r = Residents.get(level)[id]?.takeIf { it.alive } ?: return null
        val p = level.getEntity(r.uuid ?: return null) as? Townsperson ?: return null
        if (!p.isAlive || p.resident != id || p.distanceToSqr(Vec3.atCenterOf(c.bell)) > Ringer.NEAR * Ringer.NEAR) return null
        if (p.captiveOf >= 0 || p.isPassenger || p.tale >= 0) return null
        val d = p.director
        if (d != null && d !is Sheltering && d !is Lamplighter && d !is Greeter && d !is BuilderCrew.Builder) return null
        return p
    }
}
