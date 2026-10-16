package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.faction.FactionSeats
import io.github.vaspyyy.guildmark.faction.Seat
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.Vec3

/**
 * The factions' days in their seats (see FactionSeats). The leaders keep their post at every hour: Idris writing
 * at the library's reading table, Corra watching the sparring rings, Odile at the head of her table in the
 * tavern's attic, all of them nodding off there in the small hours. The members keep a working day of their
 * faction's own:
 *
 * - the Starwatch read at the lecterns, write at the reading table, work the map table, conjure motes in the
 *   plaza (with passers-by stopping to watch) and lecture one another; they read on into the night and doze
 *   over their books before dawn;
 * - the Hearthsworn kneel in a row before the barracks at dawn, spar in pairs in the rings, drill at the
 *   practice posts, sharpen at the grindstone and stand at the barracks door; evenings at the tavern, nights in
 *   the bunks;
 * - the Quiet Hand sleep late, dice and count coin in the attic and whisper in corners, keep the taproom of an
 *   evening, then lurk against walls and pick locks round the market by night.
 *
 * Anything not to hand (a seat gone, a lectern broken) falls through to an ordinary townsperson's day.
 */
object FactionWork {
    fun next(p: Townsperson, phase: Phase): Task? {
        val faction = Faction.of(p.role) ?: return null
        val seat = FactionSeats.seatOf(p) ?: return null
        if (p.isLeader) return leader(p, seat, phase)
        return when (faction) {
            Faction.STARWATCH -> mage(p, seat, phase)
            Faction.HEARTHSWORN -> knight(p, seat, phase)
            Faction.QUIET_HAND -> thief(p, seat, phase)
        }
    }

    private fun level(p: Townsperson): ServerLevel = p.level() as ServerLevel
    private fun r(p: Townsperson, n: Int): Int = p.random.nextInt(n)
    private fun top(pos: BlockPos, up: Double = 1.0): Vec3 = Jobs.top(pos, up)

    // ---- The leaders ----

    private fun leader(p: Townsperson, seat: Seat, phase: Phase): Task {
        val level = level(p)
        val dozing = phase == Phase.SLEEP
        val chair = seat.leaderSeat
        if (chair != null && Places.isSeat(level, level.getBlockState(chair), chair)) {
            val moods = when {
                dozing -> listOf(Activity.SIT)
                seat.faction == Faction.STARWATCH -> listOf(Activity.SCRIBE, Activity.SCRIBE, Activity.SCRIBE, Activity.SIT)
                else -> listOf(Activity.DICE, Activity.SIT_DRINK, Activity.SIT_TALK, Activity.SIT, Activity.SIT_DRINK)
            }
            return Sit(p, chair, 1200, moods)
        }
        // Standing: Corra by the rings, or anyone whose chair has gone
        val spot = seat.leader.takeIf { Places.standable(level, it.pos) }
            ?: FactionSeats.standNear(level, seat.leader.pos)?.let { Spot(it, seat.leader.facing) }
            ?: Spot(Jobs.home(p), seat.leader.facing)
        val watching = top(spot.pos.relative(spot.facing, 4), 1.4)
        if (dozing) return Work(p, spot, watching, listOf(Step(Activity.STAND_GUARD, 1200)))
        return Work(p, spot, watching, { i ->
            when {
                i >= 6 -> null
                r(p, 5) == 0 -> Step(Activity.CLAP)
                r(p, 6) == 0 -> Step(Activity.SALUTE)
                else -> Step(Activity.STAND_GUARD, 120 * (1 + r(p, 3)))
            }
        })
    }

    // ---- The Starwatch ----

    private fun mage(p: Townsperson, seat: Seat, phase: Phase): Task? = when (phase) {
        Phase.WORK -> when (r(p, 20)) {
            in 0..6 -> read(p, seat)
            in 7..10 -> scribe(p, seat)
            in 11..14 -> if (!p.level().isRaining) conjure(p, seat) else null
            in 15..17 -> lecture(p, seat)
            else -> mapTable(p, seat)
        } ?: read(p, seat) ?: scribe(p, seat)
        // Half of them to the tavern of an evening; the rest stay with their books
        Phase.EVENING -> if (r(p, 2) == 0) null else read(p, seat) ?: scribe(p, seat)
        // Reading on into the night by candle
        Phase.FREE -> if (r(p, 3) == 0) scribe(p, seat) ?: read(p, seat) else read(p, seat) ?: scribe(p, seat)
        // Nodding off over the books, or reading on
        Phase.SLEEP -> doze(p, seat) ?: read(p, seat, long = true)
        Phase.WAKE, Phase.HOME -> null
    }

    /** At a lectern (or the map table), head bowed over the page. */
    private fun read(p: Townsperson, seat: Seat, long: Boolean = false): Task? {
        val level = level(p)
        val lecterns = seat.stations.filter { level.getBlockState(it).block is LecternBlock && Claims.free(level, it, p) }
        val lectern = lecterns.randomOrNull() ?: return null
        val spot = Places.beside(level, lectern, p.blockPosition()) ?: return null
        val pages = if (long) 10 else 2 + r(p, 3)
        return Work(p, spot, top(lectern, 1.0), listOf(
            Step(Activity.READ, 120 * pages, line = if (r(p, 4) == 0) "mage_${listOf(0, 3)[r(p, 2)]}" else null),
            Step(Activity.NONE, 30 + r(p, 40)),
        ), lectern)
    }

    /** Writing at the reading table. */
    private fun scribe(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val chair = seat.seats.filter { Places.isSeat(level, level.getBlockState(it), it) && Claims.free(level, it, p) }.randomOrNull() ?: return null
        return Sit(p, chair, 600 + r(p, 600), listOf(Activity.SCRIBE, Activity.SCRIBE, Activity.SIT))
    }

    /** Asleep over the book at the reading table. */
    private fun doze(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val chair = seat.seats.filter { Places.isSeat(level, level.getBlockState(it), it) && Claims.free(level, it, p) }.randomOrNull() ?: return null
        return Sit(p, chair, 2400, listOf(Activity.SIT))
    }

    /** Charts spread on the map table. */
    private fun mapTable(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val table = seat.stations.firstOrNull { level.getBlockState(it).`is`(net.minecraft.world.level.block.Blocks.CARTOGRAPHY_TABLE) } ?: return null
        val spot = Places.beside(level, table, p.blockPosition()) ?: return null
        return Work(p, spot, top(table, 0.95), listOf(Step(Activity.ARRANGE, 60 * (2 + r(p, 3))), Step(Activity.READ, 120), Step(Activity.NONE, 30)), table)
    }

    /** Practising in the plaza outside: motes thrown from the palm (and a few passers-by stop to watch). */
    private fun conjure(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val at = Places.street(level, seat.square, 6, p.random, tries = 8) ?: return null
        val facing = Direction.fromYRot(p.yawTo(Vec3.atBottomCenterOf(seat.outside)).toDouble()).opposite
        return Walk(p, at, Jobs.WALK, near = 1.0).andThen(Work(p, Spot(at, facing), top(at.relative(facing, 3), 1.6), listOf(
            Step(Activity.CONJURE, 80 * (3 + r(p, 3)), line = if (r(p, 3) == 0) "mage_5" else null),
            Step(Activity.NONE, 40),
        )))
    }

    /** One mage teaching another: the lecturer at a lectern's side, the listener before them. */
    private fun lecture(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val partner = colleague(p) ?: return null
        val at = Places.standable(level, p.blockPosition()).let { if (it) p.blockPosition() else null } ?: return null
        val facing = Direction.fromYRot(p.yawTo(partner.position()).toDouble())
        val there = at.relative(facing, 2).takeIf { Places.standable(level, it) } ?: return null
        val length = 360 + r(p, 360)
        partner.routine.replace(Duet(partner, p, Spot(there, facing.opposite), Spot(at, facing), Activity.LISTEN, Activity.LECTURE, length, lead = false))
        return Duet(p, partner, Spot(at, facing), Spot(there, facing.opposite), Activity.LECTURE, Activity.LISTEN, length, lead = true, line = "mage_4")
    }

    // ---- The Hearthsworn ----

    private fun knight(p: Townsperson, seat: Seat, phase: Phase): Task? = when (phase) {
        // The dawn vigil before the barracks
        Phase.WAKE -> vigil(p, seat)
        Phase.WORK -> when (r(p, 20)) {
            in 0..6 -> spar(p, seat)
            in 7..11 -> drill(p, seat)
            in 12..15 -> sharpen(p, seat)
            else -> standGuard(p, seat)
        } ?: drill(p, seat) ?: standGuard(p, seat)
        // The tavern of an evening, the bunks at night: as anyone's day, from their home in the barracks
        Phase.EVENING, Phase.HOME, Phase.SLEEP, Phase.FREE -> null
    }

    private fun vigil(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val spot = seat.vigil.filter { Places.standable(level, it.pos) && Claims.free(level, it.pos, p) }.randomOrNull() ?: return null
        return Work(p, spot, top(spot.pos.relative(spot.facing, 3), 2.0), listOf(Step(Activity.KNEEL, 120 * (3 + r(p, 3))), Step(Activity.NONE, 20)))
    }

    /** A bout in one of the yard's rings with another knight, trading attack and guard each round. */
    private fun spar(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val ring = seat.rings.filter { Claims.free(level, it.pos, p) && Places.standable(level, it.pos) }.randomOrNull() ?: return null
        val partner = sparringPartner(p) ?: return null
        val axis = ring.facing
        val mine = ring.pos.relative(axis)
        val theirs = ring.pos.relative(axis.opposite)
        if (!Places.standable(level, mine) || !Places.standable(level, theirs)) return null
        val length = 160 * (3 + r(p, 3))
        partner.routine.replace(Duet(partner, p, Spot(theirs, axis), Spot(mine, axis.opposite), Activity.SPAR_GUARD, Activity.SPAR_ATTACK, length, swap = 160, lead = false, claim = ring.pos))
        return Duet(p, partner, Spot(mine, axis.opposite), Spot(theirs, axis), Activity.SPAR_ATTACK, Activity.SPAR_GUARD, length, swap = 160, lead = true,
            line = if (r(p, 3) == 0) "knight_2" else null, claim = ring.pos)
    }

    private fun drill(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val post = seat.spots.filter { Places.standable(level, it.pos) && Claims.free(level, it.pos, p) }.randomOrNull() ?: return null
        return Work(p, post, top(post.pos.relative(post.facing), 1.4), listOf(
            Step(Activity.DRILL, 32 * (5 + r(p, 5)), line = if (r(p, 5) == 0) "knight_4" else null),
            Step(Activity.NONE, 30 + r(p, 30)),
        ))
    }

    private fun sharpen(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val stone = seat.stations.firstOrNull { level.getBlockState(it).`is`(net.minecraft.world.level.block.Blocks.GRINDSTONE) } ?: return null
        val spot = Places.beside(level, stone, p.blockPosition()) ?: return null
        return Work(p, spot, top(stone, 0.9), listOf(Step(Activity.SHARPEN, 40 * (3 + r(p, 3))), Step(Activity.NONE, 30)), stone)
    }

    private fun standGuard(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val spot = seat.guards.filter { Places.standable(level, it.pos) && Claims.free(level, it.pos, p) }.randomOrNull() ?: return null
        return Work(p, spot, top(spot.pos.relative(spot.facing, 4), 1.6), listOf(
            Step(Activity.STAND_GUARD, 120 * (3 + r(p, 4)), line = if (r(p, 4) == 0) "knight_0" else null),
        ))
    }

    // ---- The Quiet Hand ----

    private fun thief(p: Townsperson, seat: Seat, phase: Phase): Task? = when (phase) {
        // Days in the attic
        Phase.WORK -> when (r(p, 20)) {
            in 0..7 -> dice(p, seat)
            in 8..12 -> count(p, seat)
            in 13..16 -> whisper(p, seat)
            else -> lounge(p, seat)
        } ?: dice(p, seat) ?: count(p, seat)
        // The night's work round the market
        Phase.FREE -> when (r(p, 10)) {
            in 0..4 -> lurk(p, seat)
            in 5..8 -> pickLock(p, seat)
            else -> null
        } ?: lurk(p, seat) ?: prowl(p, seat)
        Phase.WAKE, Phase.EVENING, Phase.HOME, Phase.SLEEP -> null
    }

    private fun dice(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val chair = seat.seats.filter { Places.isSeat(level, level.getBlockState(it), it) && Claims.free(level, it, p) }.randomOrNull() ?: return null
        return Sit(p, chair, 500 + r(p, 600), listOf(Activity.DICE, Activity.DICE, Activity.SIT_DRINK, Activity.SIT_TALK))
    }

    private fun lounge(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val chair = seat.seats.filter { Places.isSeat(level, level.getBlockState(it), it) && Claims.free(level, it, p) }.randomOrNull() ?: return null
        return Sit(p, chair, 300 + r(p, 400), listOf(Activity.SIT, Activity.SIT_DRINK, Activity.SIT_TALK))
    }

    /** Counting the take into stacks on a barrel head. */
    private fun count(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val spot = seat.spots.filter { Places.standable(level, it.pos) && Claims.free(level, it.pos, p) }.randomOrNull() ?: return null
        return Work(p, spot, top(spot.pos.relative(spot.facing), 1.0), listOf(Step(Activity.COUNT, 60 * (2 + r(p, 3))), Step(Activity.NONE, 30)))
    }

    /** A word in someone's ear: the whisperer leans in to the right, the listener beside them. */
    private fun whisper(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val partner = colleague(p) ?: return null
        val at = p.blockPosition().takeIf { Places.standable(level, it) } ?: return null
        val facing = Direction.fromYRot(p.yRot.toDouble())
        val right = facing.clockWise
        val there = at.relative(right).takeIf { Places.standable(level, it) } ?: return null
        val length = 250 + r(p, 250)
        partner.routine.replace(Duet(partner, p, Spot(there, right.opposite), Spot(at, facing), Activity.LISTEN, Activity.WHISPER, length, lead = false))
        return Duet(p, partner, Spot(at, facing), Spot(there, right.opposite), Activity.WHISPER, Activity.LISTEN, length, lead = true, line = "thief_2")
    }

    /** Against a wall in a side street near the market, arms folded, watching who goes by. */
    private fun lurk(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val spot = p.routine.recall("lurk", { Places.standable(level, it) }) {
            var found: BlockPos? = null
            repeat(8) {
                if (found != null) return@repeat
                val at = Places.street(level, seat.square, 24, p.random, minR = 6, tries = 4) ?: return@repeat
                if (wallBehind(level, at) != null) found = at
            }
            found
        } ?: return null
        val wall = wallBehind(level, spot) ?: return null
        val facing = wall.opposite
        return Work(p, Spot(spot, facing), top(spot.relative(facing, 5), 1.6), listOf(
            Step(Activity.LURK, 100 * (3 + r(p, 4)), line = if (r(p, 4) == 0) "thief_3" else null),
        ))
    }

    /** The side of [pos] with a wall two blocks high to lean on, if any. */
    private fun wallBehind(level: ServerLevel, pos: BlockPos): Direction? = Direction.Plane.HORIZONTAL.firstOrNull { d ->
        val a = pos.relative(d)
        val stateA = level.getBlockState(a)
        val stateB = level.getBlockState(a.above())
        stateA.isCollisionShapeFullBlock(level, a) && stateB.isCollisionShapeFullBlock(level, a.above())
    }

    /** At a door off the market in the dark, working a pick in the lock and glancing back now and then. */
    private fun pickLock(p: Townsperson, seat: Seat): Task? {
        val level = level(p)
        val door = p.routine.recall("lock", { level.getBlockState(it).block is DoorBlock }) {
            Places.nearest(level, seat.square, 18, 4, p) { state, _ -> state.block is DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER }
        } ?: return null
        val outside = Direction.Plane.HORIZONTAL.map { door.relative(it) to it }
            .firstOrNull { (at, _) -> Places.standable(level, at) && level.canSeeSky(at) } ?: return null
        return Work(p, Spot(outside.first, outside.second.opposite), top(door, 1.0), listOf(
            Step(Activity.PICK_LOCK, 60 * (3 + r(p, 3))),
            Step(Activity.NONE, 20),
        ), door)
    }

    /** A slow walk through the dark streets round the market. */
    private fun prowl(p: Townsperson, seat: Seat): Task? {
        val target = Places.street(level(p), seat.square, 28, p.random, minR = 4) ?: return null
        return Walk(p, target, Jobs.STROLL, near = 2.0)
    }

    // ---- Company ----

    /** Another member of [p]'s faction close by who's free to break off. */
    private fun colleague(p: Townsperson): Townsperson? =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(8.0, 2.0, 8.0)) {
            it !== p && it.role == p.role && !it.isLeader && !it.watcher && it.attending == null && !it.isSleeping && it.seat == null &&
                it.routine.task !is Duet
        }.minByOrNull { p.distanceToSqr(it) }

    /** A knight free to spar: anyone at the yard's work, but not one already in a bout. */
    private fun sparringPartner(p: Townsperson): Townsperson? =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(24.0, 4.0, 24.0)) {
            it !== p && it.role == TownRole.KNIGHT && !it.isLeader && !it.watcher && it.attending == null && !it.isSleeping && it.seat == null &&
                it.routine.phase == Phase.WORK && it.routine.task !is Duet
        }.minByOrNull { p.distanceToSqr(it) }
}

/**
 * Two townsfolk at something together, each in their own place facing their own way: a lecture and its listener, a
 * sparring bout (the two trading attack and guard every [swap] ticks), a whisper in a corner. Both run one of these,
 * each with the other as [partner]; the [lead] keeps the time, and says [line] as it begins.
 */
class Duet(
    p: Townsperson, val partner: Townsperson, private val mine: Spot, private val theirs: Spot,
    private var doing: Activity, private var other: Activity, private val length: Int,
    private val swap: Int = 0, private val lead: Boolean, private val line: String? = null, private val claim: BlockPos? = null,
) : Task(p) {
    private val walker = Walker(p)
    private var arrived = false
    private var since = 0

    override val anchored: Boolean get() = arrived

    override fun start() {
        claim?.let { Claims.claim(level, it, p, 400) }
        p.act(Activity.NONE)
        walker.go(mine.pos, Jobs.WALK, exact = true)
    }

    override fun run(): Boolean {
        val partnerTask = partner.routine.task
        if (!partner.isAlive || partnerTask !is Duet || partnerTask.partner !== p) return false
        if (!arrived) {
            when (walker.tick()) {
                Walker.State.WALKING -> return age < 400
                Walker.State.FAILED -> return false
                Walker.State.ARRIVED -> {
                    arrived = true
                    p.face(mine.yaw)
                }
            }
        }
        p.face(mine.yaw)
        p.lookAt(partner.eyePosition)
        // Once both are in place, the lead starts it off (and keeps swapping the parts)
        if (!partnerTask.arrived) return age < 400
        if (lead) {
            if (since == 0) {
                p.act(doing)
                partner.act(other)
                line?.let { p.say(it) }
            } else if (swap > 0 && since % swap == 0) {
                val was = doing
                doing = other
                other = was
                partnerTask.doing = was
                partnerTask.other = doing
                p.act(doing)
                partner.act(was)
            }
            since++
            if (age % 100 == 0) claim?.let { Claims.claim(level, it, p, 400) }
            return since < length
        }
        return true
    }

    override fun stop() {
        walker.stop()
        claim?.let { Claims.release(level, it, p) }
        p.act(Activity.NONE)
    }
}

/**
 * A heist's watcher on their round of a building (see Heists): from post to post ([Townsperson.patrol]), a look
 * about at each, a lantern after dark. With someone in their eye ([Townsperson.suspect]) they stop and stare.
 */
class Watch(p: Townsperson) : Task(p) {
    private val walker = Walker(p)
    private var index = -1
    private var standing = 0

    override fun start() {
        onward()
    }

    private fun onward() {
        val posts = p.patrol
        if (posts.isEmpty()) {
            standing = 200
            p.act(Activity.SEARCH)
            return
        }
        index = if (index < 0) p.random.nextInt(posts.size) else (index + 1 + p.random.nextInt(maxOf(1, posts.size - 1))) % posts.size
        walker.go(posts[index], 0.4, near = 1.2)
        if (!p.activity.overlay) p.act(Activity.NONE)
    }

    override fun run(): Boolean {
        val suspect = p.suspect
        if (suspect != null) {
            p.navigation.stop()
            p.face(p.yawTo(suspect.position()))
            p.lookAt(suspect)
            if (p.activity != Activity.SEARCH && p.activity != Activity.SHOUT) p.act(Activity.SEARCH)
            standing = maxOf(standing, 60)
            return true
        }
        if (p.activity == Activity.SHOUT && !p.actDone) return true
        if (standing > 0) {
            if (--standing == 0) onward()
            return true
        }
        when (walker.tick()) {
            Walker.State.WALKING -> {}
            Walker.State.ARRIVED, Walker.State.FAILED -> {
                standing = 80 + p.random.nextInt(140)
                p.act(if (p.random.nextInt(3) != 0) Activity.SEARCH else Activity.NONE)
            }
        }
        return true
    }

    override fun stop() {
        walker.stop()
        p.act(Activity.NONE)
    }
}
