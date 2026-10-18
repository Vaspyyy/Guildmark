package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * The neighbours at a household's coming and going (living-world/design-influence.md sections 5 and 6), as
 * [Comings.listen] tells of them. A household new to the street is waved to and shown in at its door by the nearest
 * neighbour (WAVE, "Welcome to the street!", SHOW_IN: an open arm toward the door, walking alongside); one coming
 * back after the hard times is hugged (EMBRACE: both of them, the one clip mirrored) and welcomed back; and one
 * leaving is hugged at its door, and waved off (WAVE) until it's out of sight. One or two neighbours each time,
 * whoever lives nearest and is free; once for each household's coming or going, and only with a player there to see.
 *
 * **The ways in, for Part D3** (the household's own bodies and handcart): call [at] when the household's bodies are at
 * their door (come in at the gate and up to the house; packed and about to set off; back home), which plays the
 * neighbours' part at once with a player near; and set [embrace] to play the household's half of a hug while D3 has
 * charge of their bodies (otherwise it's played here when they're free, and the neighbour waves instead when they're
 * not). Until D3 calls [at], the neighbours come over when they see one of the household at its door within a few
 * minutes of [Comings] telling of it. Everything here is on [Comings.enabled].
 */
object Neighbours : Comings.Listener {
    /** What's happening at the door. */
    enum class Kind { ARRIVED, LEFT, RETURNED }

    /** The household's half of a hug, while Part D3 has charge of their bodies. */
    fun interface Embrace {
        /** [member] (one of a household coming or going) hugs [neighbour] now (EMBRACE, facing them): true if it's played. */
        fun embrace(level: ServerLevel, member: Townsperson, neighbour: Townsperson): Boolean
    }

    /** Part D3's half of the hugs, if it has charge of the household's bodies. */
    @JvmStatic
    var embrace: Embrace? = null

    /** How long after the record the neighbours look out for the household at its door, and how near a player must be. */
    private const val WINDOW = 4800L
    private const val SEEN = 64.0

    /** How near the door the household and its neighbours must be. */
    private const val AT_DOOR = 8.0
    private const val NEIGHBOURS = 24.0

    private class Pending(val h: Household, val kind: Kind, val until: Long) {
        var door: CityPlaces.Door? = null
        var looked = Long.MIN_VALUE
    }

    private val pending = HashMap<Int, Pending>()

    /** Who's being hugged by whom just now, for a household member in a scene of ours already (the scene command's stand-ins). */
    private val hugs = WeakHashMap<Townsperson, Townsperson>()

    /** Those waiting at a door in our charge to be greeted (they hug back when hugged). */
    private val greeted = WeakHashMap<Townsperson, Boolean>()

    override fun arrived(level: ServerLevel, h: Household) = note(level, h, Kind.ARRIVED)

    override fun left(level: ServerLevel, h: Household) = note(level, h, Kind.LEFT)

    override fun returned(level: ServerLevel, h: Household) = note(level, h, Kind.RETURNED)

    private fun note(level: ServerLevel, h: Household, kind: Kind) {
        if (!Comings.enabled()) return
        pending[h.id] = Pending(h, kind, level.gameTime + WINDOW)
        if (pending.size > 64) pending.entries.removeIf { level.gameTime > it.value.until }
    }

    /**
     * For Part D3: [h]'s bodies ([members]) are at their door for [kind]. The neighbours come over now if a player's
     * near enough to see it (true if they did); otherwise there's nobody about to see, and nothing's played.
     */
    fun at(level: ServerLevel, h: Household, kind: Kind, members: List<Townsperson>): Boolean {
        if (!Comings.enabled()) return false
        val door = door(level, h) ?: return false
        if (!Scenes.seen(level, door.pos, SEEN)) return false
        pending.remove(h.id)
        return play(level, kind, members.filter { it.isAlive && !it.isRemoved }, door, h.members.toSet()) > 0
    }

    /** [h]'s front door, if its home's known. */
    private fun door(level: ServerLevel, h: Household): CityPlaces.Door? {
        val capital = Capitals.covering(level, BlockPos.of(h.capital)) ?: return null
        val places = CityPlaces.of(level, capital) ?: return null
        return places.lodgings[h.home]?.front
    }

    /** Once a second for each player: a household come or going near them, seen at its door, has its neighbours come over. */
    fun look(level: ServerLevel, player: ServerPlayer) {
        if (pending.isEmpty()) return
        val now = level.gameTime
        pending.entries.removeIf { now > it.value.until }
        for (w in pending.values.toList()) {
            if (w.looked == now) continue
            val door = w.door ?: door(level, w.h)?.also { w.door = it } ?: continue
            if (player.distanceToSqr(Vec3.atCenterOf(door.pos)) > SEEN * SEEN) continue
            w.looked = now
            val members = level.getEntitiesOfClass(Townsperson::class.java, AABB(door.pos).inflate(AT_DOOR, 4.0, AT_DOOR)) {
                it.isAlive && it.resident >= 0 && it.resident in w.h.members
            }
            if (members.isEmpty()) continue
            pending.remove(w.h.id)
            play(level, w.kind, members, door, w.h.members.toSet())
        }
    }

    /**
     * The neighbours' part at [door] for [kind], with [members] of the household there: the nearest-living one or two
     * who are free come over. How many came; [not] are the household's own (never their own neighbours).
     */
    fun play(level: ServerLevel, kind: Kind, members: List<Townsperson>, door: CityPlaces.Door, not: Set<Int>, chosen: List<Townsperson>? = null): Int {
        if (members.isEmpty()) return 0
        val at = Vec3.atCenterOf(door.pos)
        val most = if (kind == Kind.ARRIVED) 1 + level.random.nextInt(2) else 2
        val neighbours = chosen ?: level.getEntitiesOfClass(Townsperson::class.java, AABB(door.pos).inflate(NEIGHBOURS, 6.0, NEIGHBOURS)) {
            it.resident >= 0 && it.resident !in not && it !in members && it.role != TownRole.GUARD && Aside.free(it, seated = true)
        }.sortedWith(compareBy<Townsperson>({ if (it.role.adult) 0 else 1 }, { home(it)?.distSqr(door.pos) ?: Double.MAX_VALUE }, { it.distanceToSqr(at) }))
            .take(most)
        var n = 0
        for ((i, neighbour) in neighbours.withIndex()) {
            val member = members[i % members.size]
            val moves = when (kind) {
                Kind.ARRIVED -> welcome(neighbour, member, door, first = i == 0)
                Kind.RETURNED -> back(neighbour, member, door, first = i == 0)
                Kind.LEFT -> farewell(neighbour, member, door, first = i == 0)
            }
            // A step behind the first, so they don't all arrive at once
            val lag = i * (20 + level.random.nextInt(20))
            val all = if (lag > 0) listOf<Move>(Wait({ _, t -> t >= lag }, lag + 1)) + moves else moves
            if (Aside.take(neighbour, Aside(all, reach = SEEN + 16.0, most = 1800, fresh = true), seated = true)) n++
        }
        return n
    }

    /** Where [p] lives: their house's door, if they're one of a capital's own. */
    private fun home(p: Townsperson): BlockPos? = (Days.life(p) ?: p.life)?.home?.front?.pos

    /** A new household's welcome: over to them, a wave and a word, and shown in at the door. */
    private fun welcome(p: Townsperson, member: Townsperson, door: CityPlaces.Door, first: Boolean): List<Move> {
        val them = { member.takeIf { it.isAlive }?.eyePosition }
        val moves = ArrayList<Move>()
        moves += GoTo({ near(p, member, 2) }, Jobs.WALK, near = 1.4, most = 500)
        moves += Turn(them)
        moves += Play(Activity.WAVE, at = them, first = { who -> if (first) Words.say(who, "comings.welcome", "welcoming a household new to the street") })
        moves += Play(Activity.NOD, at = them)
        if (first) {
            // An open arm toward the door, walking them up to it
            moves += GoTo({ door.outside.relative(door.inward.clockWise) }, Jobs.STROLL, near = 1.0, overlay = Activity.SHOW_IN, most = 300)
            moves += Turn({ member.takeIf { it.isAlive }?.eyePosition ?: Vec3.atCenterOf(door.pos) })
            moves += Play(Activity.NOD, at = them)
        } else moves += Play(Activity.WAVE, at = them)
        return moves
    }

    /** A household back after the hard times: a hug and a welcome back, and shown in at the old door. */
    private fun back(p: Townsperson, member: Townsperson, door: CityPlaces.Door, first: Boolean): List<Move> {
        val them = { member.takeIf { it.isAlive }?.eyePosition }
        val moves = ArrayList<Move>()
        moves += GoTo({ near(p, member, 1) }, Jobs.BRISK, near = 1.1, most = 500)
        moves += Turn(them, most = 8)
        moves += Hug(member, "comings.return", "welcoming a household back", speak = first)
        moves += Play(if (p.random.nextBoolean()) Activity.LAUGH else Activity.NOD, at = them)
        if (first) moves += GoTo({ door.outside.relative(door.inward.clockWise) }, Jobs.STROLL, near = 1.0, overlay = Activity.SHOW_IN, most = 300)
        moves += Play(Activity.WAVE, at = them)
        return moves
    }

    /** A household leaving: a hug at the door and a word, a step back, and a wave until they're out of sight. */
    private fun farewell(p: Townsperson, member: Townsperson, door: CityPlaces.Door, first: Boolean): List<Move> {
        val them = { member.takeIf { it.isAlive }?.eyePosition }
        val moves = ArrayList<Move>()
        moves += GoTo({ near(p, member, 1) }, Jobs.WALK, near = 1.1, most = 500)
        moves += Turn(them, most = 8)
        moves += Hug(member, "comings.farewell", "seeing a household off", speak = first)
        moves += GoTo({ back(p, door) }, Jobs.STROLL, near = 0.8, most = 200)
        // Watching them go, then a wave after them as they turn out of the street
        moves += Wait({ _, _ -> !member.isAlive || member.distanceToSqr(Vec3.atCenterOf(door.pos)) > 10.0 * 10.0 }, 600, at = them)
        moves += Play(Activity.WAVE, at = them)
        moves += Play(Activity.WAVE, at = them)
        if (p.random.nextInt(2) == 0) moves += Play(Activity.SIGH, at = them)
        return moves
    }

    /** A place to stand [gap] blocks from [member], on [p]'s side of them. */
    private fun near(p: Townsperson, member: Townsperson, gap: Int): BlockPos {
        val level = p.level()
        val from = member.blockPosition()
        val dirs = Direction.Plane.HORIZONTAL.toList().sortedBy { from.relative(it, gap).distSqr(p.blockPosition()) }
        for (d in dirs) for (dy in intArrayOf(0, 1, -1)) {
            val pos = from.relative(d, gap).above(dy)
            if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
        }
        return from
    }

    /** A step back from [door] into the street, to see them off from. */
    private fun back(p: Townsperson, door: CityPlaces.Door): BlockPos {
        val level = p.level()
        val side = if (p.random.nextBoolean()) door.inward.clockWise else door.inward.counterClockWise
        for (out in intArrayOf(3, 2, 4)) for (dy in intArrayOf(0, 1, -1)) {
            val pos = door.pos.relative(door.inward.opposite, out).relative(side, 2).above(dy)
            if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
        }
        return p.blockPosition()
    }

    /**
     * [member]'s half of a hug from [neighbour]: Part D3's ([embrace]); or the hug back of someone waiting in a scene
     * of ours; or a short part of their own if they're free. False if none (the neighbour waves instead).
     */
    private fun half(level: ServerLevel, member: Townsperson, neighbour: Townsperson): Boolean {
        val d3 = try {
            embrace?.embrace(level, member, neighbour) == true
        } catch (e: RuntimeException) {
            false
        }
        if (d3) return true
        if (greeted[member] == true) {
            hugs[member] = neighbour
            return true
        }
        if (!Aside.free(member)) return false
        val aside = Aside(listOf(
            Turn({ neighbour.takeIf { it.isAlive }?.eyePosition }, most = 4),
            Play(Activity.EMBRACE, at = { neighbour.takeIf { it.isAlive }?.eyePosition }),
        ), most = 200)
        return Aside.take(member, aside)
    }

    /** A hug: both of them, facing (the neighbour waves instead if [member] can't hug back), with a word from the first. */
    private class Hug(private val member: Townsperson, private val family: String, private val why: String, private val speak: Boolean) : Move() {
        private var what = Activity.WAVE

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            val level = p.level() as ServerLevel
            val close = member.isAlive && member.distanceToSqr(p) < 2.5 * 2.5
            what = if (close && half(level, member, p)) Activity.EMBRACE else Activity.WAVE
            p.act(what)
            if (speak) Words.say(p, family, why)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            if (member.isAlive) {
                val at = member.eyePosition
                p.lookAt(at)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(at), Aside.TURN))
            }
            return t < what.ticks
        }
    }

    /**
     * A household member in our charge (the scene command's stand-ins) waiting at their door to be greeted: looking
     * about them, and hugging back whoever hugs them. Done after [most] ticks, or once hugged and the hug's played.
     */
    private class Greeted(private val most: Int, private val about: Boolean) : Move() {
        private var hugging = -1

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            greeted[p] = true
            p.act(if (about) Activity.LOOK_ABOUT else Activity.NONE)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            val by = hugs.remove(p)
            if (by != null && hugging < 0) {
                hugging = t
                p.face(p.yawTo(by.position()))
                p.lookAt(by.eyePosition)
                p.act(Activity.EMBRACE)
            }
            if (hugging >= 0) return t - hugging < Activity.EMBRACE.ticks + 10
            // Taking in the street now and then, new to it, and standing easy between
            if (!p.activity.loop && p.actDone) p.act(if (about && p.random.nextInt(4) == 0) Activity.LOOK_ABOUT else Activity.NONE)
            return t < most
        }

        override fun stop(p: Townsperson) {
            greeted.remove(p)
            hugs.remove(p)
        }
    }

    /**
     * `/guildmark capital scene welcome|return|farewell`: at the house door nearest [player], a stand-in household (one
     * or two of whoever's free nearest it) comes to it, and the neighbours play their part. How many came; -1 if
     * there's no house with a door near, or nobody to stand in.
     */
    fun play(level: ServerLevel, player: ServerPlayer, here: Scenes.Here, kind: Kind): Int {
        val houses = here.places.nearest(player.blockPosition(), setOf("HOUSE", "COTTAGE")).filter { it.front != null }.take(1)
        val home = houses.firstOrNull() ?: return -1
        val door = home.front!!
        if (Vec3.atCenterOf(door.pos).distanceToSqr(player.position()) > 48.0 * 48.0) return -1
        val at = Vec3.atCenterOf(door.pos)
        val free = level.getEntitiesOfClass(Townsperson::class.java, AABB(door.pos).inflate(28.0, 8.0, 28.0)) {
            it.resident >= 0 && it.role != TownRole.GUARD && Aside.free(it, seated = true)
        }.sortedBy { it.distanceToSqr(at) }
        if (free.size < 2) return -1
        val members = free.take(if (free.size >= 4) 2 else 1)
        val rest = free.drop(members.size)
        for ((i, m) in members.withIndex()) {
            val spot = door.outside.relative(door.inward.opposite, 1).relative(if (i == 0) door.inward.clockWise else door.inward.counterClockWise)
            val moves = ArrayList<Move>()
            moves += GoTo({ spot }, Jobs.WALK, near = 0.8, most = 500)
            moves += Turn({ Vec3.atCenterOf(door.outside.relative(door.inward.opposite, 4)).add(0.0, 1.0, 0.0) })
            moves += Greeted(500, about = kind != Kind.LEFT)
            if (kind == Kind.LEFT) {
                // Off down the street, as a household leaving would be
                moves += GoTo({ door.pos.relative(door.inward.opposite, 16) }, Jobs.WALK, near = 2.0, most = 500)
            } else moves += Play(Activity.NOD)
            Aside.take(m, Aside(moves, reach = SEEN + 16.0, most = 1800, fresh = true), seated = true)
        }
        // The neighbours come over once the household's at the door
        var n = 0
        val neighbours = rest.take(2)
        for ((i, neighbour) in neighbours.withIndex()) {
            val member = members[i % members.size]
            val moves = when (kind) {
                Kind.ARRIVED -> welcome(neighbour, member, door, first = i == 0)
                Kind.RETURNED -> back(neighbour, member, door, first = i == 0)
                Kind.LEFT -> farewell(neighbour, member, door, first = i == 0)
            }
            val lag = i * 30
            val all = listOf<Move>(Wait({ _, t -> members.all { !it.isAlive || it.distanceToSqr(at) < 3.5 * 3.5 } && t >= lag }, 300 + lag)) + moves
            if (Aside.take(neighbour, Aside(all, reach = SEEN + 16.0, most = 1800, fresh = true), seated = true)) n++
        }
        return n
    }

    fun forget() {
        pending.clear()
        hugs.clear()
        greeted.clear()
    }
}
