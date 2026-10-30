package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.outlaw.Raids
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.village.live.Turns
import io.github.vaspyyy.guildmark.village.live.VillageSounds
import io.github.vaspyyy.guildmark.village.live.days.BellKeeper
import io.github.vaspyyy.guildmark.village.live.links.Greeter
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.phys.Vec3

/**
 * One of a village's folk sheltering (living-world/villages/design.md 11.2): the director of their townsperson while it lasts, as
 * a lamplighter's is. In turn:
 *
 * - ALERT: they stop, set down what they carry ([drop]), turn to the trouble; the first two to hear shout (10 to 30 ticks, the
 *   farther the later), the second once the first is heard out ([Turns]);
 * - RUN: to their door ([Shelter.havenFor]) at 0.75 (a child at 1.0, to the door their household's grown-up runs for), at most 400
 *   ticks; with no door within reach they cower where they stand, back to a wall;
 * - IN: the first in steps inside and turns to the door ([Shelter.Haven.stand]: its inner face half a block ahead), waiting a moment
 *   for those just behind; the rest go to their corners;
 * - BOLT: BOLT_DOOR, the door swung to at tick 10 and the bolt shot at 30 (`village.door_bolt`, a puff of dust from the frame);
 * - KNOCK and WAIT: a latecomer at a bolted door knocks and calls; someone inside comes to open it (OPEN: "Quick, in!") and bolts it
 *   again behind them; nobody coming, they let themselves in;
 * - COWER: in the spot farthest from the door and the windows, facing the door; a child beside a grown-up of their home, the two
 *   holding each other (EMBRACE) first; a whisper now and then ([whispers]);
 * - EMERGE: at the all-clear, each in their turn: the door opened, out into the lane, a look about, then the end's own beat (a cheer
 *   and a clap for a siege held or a raid beaten off, a sigh and a slow walk to the broken wall for a siege lost), and back to the day.
 *
 * Hurt, or had by something else for more than a few ticks (the flee), they are let go and taken again a little later; one a raider
 * puts on a rope is let go (the door they are behind forced open) and not taken again while they are on it, crying out once as
 * they are dragged off ("No! Let me go!").
 */
class Sheltering internal constructor(internal val called: Shelter.Called, val p: Townsperson, private val alert: Int, private val shout: Boolean) : Director {
    enum class S { ALERT, RUN, IN, BOLT, KNOCK, WAIT, OPEN, COWER, EMERGE }

    internal var stage = S.ALERT
        private set
    internal var haven: Shelter.Haven? = null
        private set
    internal var done = false
        private set

    private var since = 0
    private var began = false
    private var lastTick = -1
    private var walker: Walker? = null
    private var spot: BlockPos? = null
    private var at = false
    private var shut = false
    private var shot = false
    private var said = false
    private var emergeAt = -1
    private var phase = 0
    /** The tick of ALERT they shouted on, or -1 before they have. */
    private var shoutAt = -1
    /** Setting down what they carried, at the alert (PUT_DOWN). */
    private var settingDown = false
    /** Cowering where they stood: the way their back is to (a wall). */
    private var wall: Direction? = null
    /** The stand at the door, as the bolter or the opener. */
    private var standing = false

    private fun walker(): Walker = walker ?: Walker(p).also { walker = it }

    private fun to(s: S) {
        stage = s
        since = p.tickCount
        at = false
        phase = 0
        said = false
    }

    private val t: Int get() = p.tickCount - since

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        if (done) return
        if (called.over || called.ending == null && !Trouble.on()) return finish()
        // Put on a raider's rope (the raid leaving with a hostage): the shelter lets go, so they are not steered against the rope
        if (p.isLeashed) {
            // The raiders force the door for the one they take
            haven?.let { h ->
                if (h.contains(p.blockPosition()) && !open(level, h)) {
                    swing(level, h, true)
                    h.bolted = false
                }
            }
            // and the one taken cries out as they are dragged off: once, and only on a raider's rope
            if (p.leashHolder?.entityTags()?.contains(Raids.TAG) == true && called.cried.add(p.id)) Trouble.say(p, "shelter_taken", hearing = Trouble.SHOUT)
            Shelter.handed(this)
            return finish()
        }
        // Back from something that had them meanwhile (running from a monster), or hurt: theirs again; taken again in a while
        if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0) {
            Shelter.handed(this)
            return finish()
        }
        lastTick = p.tickCount
        if (emergeAt >= 0 && stage != S.EMERGE && p.tickCount >= emergeAt) emerge(level)
        when (stage) {
            S.ALERT -> alerted(level)
            S.RUN -> running(level)
            S.IN -> inside(level)
            S.BOLT -> bolting(level)
            S.KNOCK -> knocking(level)
            S.WAIT -> waiting(level)
            S.OPEN -> opening(level)
            S.COWER -> cowering(level)
            S.EMERGE -> emerging(level)
        }
    }

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        p.lookAt(player)
        val line = when (stage) {
            S.ALERT, S.RUN, S.KNOCK, S.WAIT -> if (p.role == TownRole.CHILD) "shelter_run_1" else "shelter_run_2"
            S.COWER, S.IN, S.BOLT, S.OPEN -> hush(p)
            S.EMERGE -> return false
        }
        Trouble.say(p, line)
        return true
    }

    /** The all-clear: they come out in [delay] ticks; those still on their way go back to their day sooner. */
    internal fun allClear(delay: Int) {
        val inside = stage == S.COWER || stage == S.BOLT || stage == S.IN || stage == S.OPEN
        emergeAt = p.tickCount + if (inside) delay else minOf(delay, 20)
    }

    // ---- The horn ----

    private fun alerted(level: ServerLevel) {
        if (!began) {
            began = true
            since = p.tickCount
            p.navigation.stop()
            p.face(p.yawTo(called.toward()))
            settingDown = drop()
            if (!settingDown) p.act(Activity.NONE)
        }
        if (settingDown) {
            if (t < Activity.PUT_DOWN.ticks) return
            settingDown = false
            p.carried = ItemStack.EMPTY
            p.act(Activity.NONE)
        }
        // The shout, in its turn: the second of the two once the first one's is heard out (or not at all, if it never is in time)
        if (shout && shoutAt < 0 && t < SHOUT_WAIT && Turns.freeBy(p)) {
            shoutAt = t
            p.act(Activity.SHOUT)
            Trouble.say(p, Shelter.alertLine(called, p), Trouble.SHOUT)
        }
        // Off once the alert is taken in and the shout given (the arms down from it)
        if (t >= alert && (!shout || shoutAt >= 0 && t >= shoutAt + SHOUTED || t >= SHOUT_WAIT)) run(level)
    }

    /**
     * What they carry (a basket, a sack) set down where they stand: PUT_DOWN, the goods on the ground from its moment (the client's
     * thud and dust), the hands free to run; true if they had anything. It is not left lying once they go: what townsfolk carry is
     * drawn in the hands and on the ground by the clip, not an item in the world, and a real item set down would be goods no store
     * gave up, there for a player or a hopper to take.
     */
    private fun drop(): Boolean {
        if (p.carried.isEmpty) return false
        p.act(Activity.PUT_DOWN)
        return true
    }

    private fun run(level: ServerLevel) {
        val h = Shelter.havenFor(level, this)
        haven = h
        if (h == null) return inPlace(level)
        h.folk += this
        to(S.RUN)
        p.act(Activity.FLEE)
        if (h.contains(p.blockPosition())) return enter(level)
        walker().go(h.door.outside, if (p.role == TownRole.CHILD) CHILD_PACE else Jobs.RUN, near = 1.0)
        // A grown-up with a child of their home near them calls them along; a child cries out
        val kin = Shelter.household(level, this)
        when {
            p.role == TownRole.CHILD -> if (p.random.nextInt(3) == 0) Trouble.say(p, "shelter_run_1")
            kin.any { it.p.role == TownRole.CHILD && it.p.distanceToSqr(p) < CALL_ALONG * CALL_ALONG } -> Trouble.say(p, "shelter_run_2")
            p.random.nextInt(6) == 0 -> Trouble.say(p, "shelter_run_1")
        }
    }

    private fun running(level: ServerLevel) {
        val h = haven ?: return inPlace(level)
        if (t > RUN_MOST) return inPlace(level)
        if (p.activity != Activity.FLEE) p.act(Activity.FLEE)
        when (walker().tick()) {
            Walker.State.WALKING -> {}
            Walker.State.ARRIVED -> atDoor(level, h)
            Walker.State.FAILED -> inPlace(level)
        }
    }

    private fun atDoor(level: ServerLevel, h: Shelter.Haven) {
        if (h.bolted && !open(level, h)) {
            to(S.KNOCK)
            p.navigation.stop()
            p.face(p.yawTo(Vec3.atCenterOf(h.door.pos)))
            p.act(Activity.KNOCK)
            Trouble.say(p, "shelter_knock")
            h.knocking += this
            return
        }
        swing(level, h, true)
        enter(level)
    }

    /** In through the door: the first in to the door's inside to bolt it, the rest to their corners. */
    private fun enter(level: ServerLevel) {
        val h = haven ?: return inPlace(level)
        h.knocking.remove(this)
        to(S.IN)
        p.act(Activity.NONE)
        val bolter = h.bolter
        if (bolter == null || bolter.done || bolter === this) {
            h.bolter = this
            standing = true
            walker().go(BlockPos.containing(h.stand(level)), Jobs.RUN, exact = true)
        } else {
            standing = false
            spot = h.spotFor(level, this, nextTo = kinSpot(level))
            walker().go(spot ?: h.door.inside, Jobs.RUN, near = 0.6)
        }
    }

    private fun inside(level: ServerLevel) {
        val h = haven ?: return inPlace(level)
        if (!at) {
            val state = walker().tick()
            if (state == Walker.State.WALKING && t < 200) return
            at = true
            since = p.tickCount
            if (!standing) return cower(level)
            p.navigation.stop()
            snapTo(h.stand(level))
            p.face(h.door.inward.opposite)
        }
        p.face(h.door.inward.opposite)
        // A moment for those just behind them to come in
        val coming = h.folk.any { it !== this && !it.done && it.stage == S.RUN && it.p.distanceToSqr(Vec3.atCenterOf(h.door.pos)) < 64.0 }
        if (coming && t < WAIT_BEHIND) return
        bolt(level)
    }

    private fun bolt(level: ServerLevel) {
        to(S.BOLT)
        shut = false
        shot = false
        p.act(Activity.BOLT_DOOR)
    }

    private fun bolting(level: ServerLevel) {
        val h = haven ?: return inPlace(level)
        p.navigation.stop()
        p.face(h.door.inward.opposite)
        if (t >= SHUT && !shut) {
            shut = true
            swing(level, h, false)
        }
        if (t >= BOLTED && !shot) {
            shot = true
            h.bolted = true
            val c = Vec3.atCenterOf(h.door.pos)
            VillageSounds.play(level, c, VillageSounds.DOOR_BOLT)
            // A puff of dust from the frame, where the bolt went home
            val frame = level.getBlockState(h.door.pos.above(2)).takeIf { !it.isAir } ?: level.getBlockState(h.door.pos)
            val side = h.door.inward.clockWise
            level.sendParticles(
                BlockParticleOption(ParticleTypes.BLOCK, frame), c.x + side.stepX * 0.45 + h.door.inward.stepX * 0.3, c.y + 0.6,
                c.z + side.stepZ * 0.45 + h.door.inward.stepZ * 0.3, 6, 0.05, 0.3, 0.05, 0.02,
            )
        }
        if (t < Activity.BOLT_DOOR.ticks) return
        // Someone already knocking: let them in at once
        if (h.knocking.any { !it.done }) return opener(level)
        standing = false
        spot = h.spotFor(level, this)
        to(S.IN)
        at = false
        walker().go(spot ?: h.door.inside, Jobs.WALK, near = 0.6)
    }

    // ---- A latecomer ----

    private fun knocking(level: ServerLevel) {
        val h = haven ?: return inPlace(level)
        p.navigation.stop()
        if (!open(level, h) && p.actDone) {
            to(S.WAIT)
            p.act(Activity.NONE)
        }
        if (open(level, h)) enter(level)
    }

    private fun waiting(level: ServerLevel) {
        val h = haven ?: return inPlace(level)
        p.navigation.stop()
        p.face(p.yawTo(Vec3.atCenterOf(h.door.pos)))
        if (open(level, h)) return enter(level)
        // Knocking again, louder; and nobody coming, they let themselves in (there is no real bolt on a door)
        if (t == KNOCK_AGAIN) {
            p.act(Activity.KNOCK)
            Trouble.say(p, "shelter_knock")
        }
        if (t >= LET_SELF_IN) {
            swing(level, h, true)
            h.bolter = null
            h.bolted = false
            enter(level)
        }
    }

    /** Someone at the door: [this], inside, goes to let them in. */
    internal fun opener(level: ServerLevel) {
        val h = haven ?: return
        to(S.OPEN)
        standing = true
        h.bolter = this
        p.act(Activity.NONE)
        walker().go(BlockPos.containing(h.stand(level)), Jobs.RUN, exact = true)
    }

    private fun opening(level: ServerLevel) {
        val h = haven ?: return inPlace(level)
        if (!at) {
            val state = walker().tick()
            if (state == Walker.State.WALKING && t < 160) return
            at = true
            since = p.tickCount
            snapTo(h.stand(level))
            p.face(h.door.inward.opposite)
            swing(level, h, true)
            Trouble.say(p, "shelter_open")
            p.act(Activity.BECKON)
        }
        p.face(h.door.inward.opposite)
        val left = h.knocking.filter { !it.done }
        if (left.isNotEmpty() && t < 80) return
        h.knocking.clear()
        bolt(level)
    }

    // ---- Behind the door ----

    private fun cower(level: ServerLevel) {
        to(S.COWER)
        at = true
        p.navigation.stop()
        val h = haven
        if (h != null) p.face(p.yawTo(Vec3.atCenterOf(h.door.pos))) else wall?.let { p.face(it.opposite) }
        // A child held by a grown-up of their home, the two holding each other a moment first
        val other = embraceWith(level)
        if (other != null) {
            p.face(p.yawTo(other.p.position()))
            p.act(Activity.EMBRACE)
            if (other.stage == S.COWER && other.p.activity == Activity.COWER) {
                other.p.face(other.p.yawTo(p.position()))
                other.p.act(Activity.EMBRACE)
            }
        } else {
            p.act(Activity.COWER)
        }
    }

    private fun cowering(level: ServerLevel) {
        if (!at) {
            val state = walker().tick()
            if (state == Walker.State.WALKING && t < 200) return
            return cower(level)
        }
        p.navigation.stop()
        val h = haven
        // Someone knocking: the bolter, or the first grown-up behind the door, goes to let them in
        if (h != null && h.knocking.any { !it.done } && p.role != TownRole.CHILD) {
            val opener = h.folk.firstOrNull { !it.done && it.stage == S.COWER && it.p.role != TownRole.CHILD }
            if (opener === this) return opener(level)
        }
        if (p.activity != Activity.COWER && (p.actDone || p.activity == Activity.NONE)) {
            h?.let { p.face(p.yawTo(Vec3.atCenterOf(it.door.pos))) }
            p.act(Activity.COWER)
        }
    }

    /** A grown-up of [p]'s home cowering beside them (for a child), or the child beside them (for the grown-up): who to hold. */
    private fun embraceWith(level: ServerLevel): Sheltering? {
        val h = haven ?: return null
        val kin = Shelter.household(level, this).filter { it.haven === h && it.stage == S.COWER && it.p.distanceToSqr(p) < 4.0 }
        return if (p.role == TownRole.CHILD) kin.firstOrNull { it.p.role != TownRole.CHILD } else kin.firstOrNull { it.p.role == TownRole.CHILD }
    }

    /** A grown-up of a child's home already in their corner: the child takes the spot beside them. */
    private fun kinSpot(level: ServerLevel): BlockPos? {
        if (p.role != TownRole.CHILD) return null
        return Shelter.household(level, this).firstOrNull { it.haven === haven && it.p.role != TownRole.CHILD && it.spot != null }?.spot
    }

    /** No door within reach, or no way to one: they crouch where they stand, back to a wall if one is near. */
    private fun inPlace(level: ServerLevel) {
        haven?.folk?.remove(this)
        haven = null
        to(S.COWER)
        val here = p.blockPosition()
        var best: Pair<BlockPos, Direction>? = null
        var bestD = Int.MAX_VALUE
        for (dx in -4..4) for (dz in -4..4) {
            val pos = here.offset(dx, 0, dz)
            if (!Places.standable(level, pos)) continue
            val back = Direction.Plane.HORIZONTAL.firstOrNull { !Places.free(level, pos.relative(it)) && !Places.free(level, pos.relative(it).above()) } ?: continue
            val d = dx * dx + dz * dz
            if (d < bestD) {
                bestD = d
                best = pos to back
            }
        }
        val b = best
        if (b == null) {
            wall = null
            return cower(level)
        }
        wall = b.second
        spot = b.first
        at = false
        walker().go(b.first, Jobs.RUN, exact = true)
    }

    // ---- The all-clear ----

    private fun emerge(level: ServerLevel) {
        to(S.EMERGE)
        p.act(Activity.NONE)
        val h = haven
        if (h != null && h.contains(p.blockPosition())) {
            if (open(level, h)) {
                phase = 1
                walker().go(lane(level, h), pace(), near = 1.0)
            } else {
                phase = 0
                walker().go(BlockPos.containing(h.stand(level)), Jobs.WALK, exact = true)
            }
        } else {
            phase = 2
            since = p.tickCount
            p.act(Activity.LOOK_ABOUT)
        }
    }

    private fun emerging(level: ServerLevel) {
        val h = haven
        when (phase) {
            // To the door, and open it
            0 -> {
                if (h == null || walker().tick() == Walker.State.WALKING && t < 120) return
                p.face(h.door.inward.opposite)
                swing(level, h, true)
                h.bolted = false
                phase = 1
                walker().go(lane(level, h), pace(), near = 1.0)
            }
            // Out into the lane
            1 -> {
                if (walker().tick() == Walker.State.WALKING && t < 240) return
                phase = 2
                since = p.tickCount
                p.act(Activity.LOOK_ABOUT)
            }
            // A look about
            2 -> if (p.actDone || t > Activity.LOOK_ABOUT.ticks) after(level)
            // The end's own beat
            3 -> if (p.actDone || t > 60) {
                if (called.ending == Shelter.Outcome.LOST && spot != null) {
                    phase = 4
                    since = p.tickCount
                    p.act(Activity.NONE)
                    walker().go(spot ?: p.blockPosition(), SLOW, near = 3.0)
                } else {
                    finish()
                }
            }
            // A siege lost: a slow walk to the gap, and a look at it
            4 -> {
                if (walker().tick() == Walker.State.WALKING && t < 400) return
                phase = 5
                since = p.tickCount
                spot?.let { p.face(p.yawTo(Vec3.atCenterOf(it))) }
                p.act(Activity.LOOK_ABOUT)
                if (called.walled && !called.lostSaid && Trouble.say(p, "shelter_lost")) called.lostSaid = true
            }
            5 -> if (p.actDone || t > Activity.LOOK_ABOUT.ticks) finish()
        }
    }

    /** After the look about: the cheer, the clap, the sigh, as the trouble ended. */
    private fun after(level: ServerLevel) {
        phase = 3
        since = p.tickCount
        spot = null
        val how = called.ending ?: Shelter.Outcome.QUIET
        p.face(p.yawTo(called.toward()))
        when (how) {
            Shelter.Outcome.HELD, Shelter.Outcome.BEATEN -> {
                Shelter.cheer(level, called)
                // Those they feared for: one of their home close by is held
                val kin = Shelter.household(level, this).firstOrNull { it.stage == S.EMERGE && it.p.distanceToSqr(p) < 9.0 }
                if (kin != null && p.random.nextBoolean()) {
                    p.face(p.yawTo(kin.p.position()))
                    p.act(Activity.EMBRACE)
                } else {
                    p.act(if (p.random.nextBoolean()) Activity.CHEER else Activity.CLAP)
                }
                if (!called.allClearSaid && p.role != TownRole.CHILD && Trouble.say(p, "shelter_allclear_2")) called.allClearSaid = true
            }
            Shelter.Outcome.LOST -> {
                p.act(Activity.SIGH)
                spot = breach(level)
            }
            Shelter.Outcome.TAKEN -> {
                p.act(Activity.SIGH)
                if (!called.allClearSaid && p.role != TownRole.CHILD && Trouble.say(p, "shelter_allclear_1")) called.allClearSaid = true
            }
            Shelter.Outcome.QUIET -> {
                p.act(Activity.NONE)
                if (!called.allClearSaid && p.role != TownRole.CHILD && Trouble.say(p, "shelter_allclear_1")) called.allClearSaid = true
            }
        }
    }

    /** The nearest of the blocks the siege broke, within 40 blocks: the gap they go to look at. */
    private fun breach(level: ServerLevel): BlockPos? {
        val damage = TroubleData.peek(level)?.peek(called.key)?.damage ?: return null
        val here = p.blockPosition()
        val near = damage.map { BlockPos.of(it.pos) }.minByOrNull { it.distSqr(here) } ?: return null
        if (near.distSqr(here) > 40 * 40) return null
        return GroundNear.stand(level, near) ?: near
    }

    /** Out in the lane before the door, two or three blocks out. */
    private fun lane(level: ServerLevel, h: Shelter.Haven): BlockPos {
        for (n in intArrayOf(3, 2, 4)) {
            val pos = h.door.pos.relative(h.door.inward.opposite, n)
            GroundNear.stand(level, pos)?.let { return it }
        }
        return h.door.outside
    }

    private fun pace(): Double = if (called.ending == Shelter.Outcome.LOST) SLOW else Jobs.WALK

    /** Back to their day. */
    internal fun finish() {
        if (done) return
        done = true
        haven?.let { h ->
            h.taken.entries.removeIf { it.value == p.id }
            h.knocking.remove(this)
            if (h.bolter === this) h.bolter = null
        }
        walker?.stop()
        if (p.director === this) p.director = null
        p.act(Activity.NONE)
        p.navigation.stop()
        p.routine.interrupt()
    }

    // ---- Helpers ----

    private fun snapTo(v: Vec3) {
        if (p.position().distanceToSqr(v) < 1.5 * 1.5) p.setPos(v.x, p.y, v.z)
    }

    private fun open(level: ServerLevel, h: Shelter.Haven): Boolean {
        val state = level.getBlockState(h.door.pos)
        val door = state.block as? DoorBlock ?: return true
        return door.isOpen(state)
    }

    private fun swing(level: ServerLevel, h: Shelter.Haven, open: Boolean) {
        for (pos in listOf(h.door.pos, h.door.pos.above(), h.door.pos.below())) {
            if (!level.isLoaded(pos)) continue
            val state = level.getBlockState(pos)
            val block = state.block as? DoorBlock ?: continue
            if (block.isOpen(state) != open) block.setOpen(p, level, state, pos, open)
            return
        }
    }

    companion object {
        private const val CHILD_PACE = 1.0
        private const val SLOW = 0.38
        private const val RUN_MOST = 400
        /** The door swung to (BOLT_DOOR tick 10) and the bolt shot home (tick 30). */
        const val SHUT = 10
        const val BOLTED = 30
        private const val WAIT_BEHIND = 60
        private const val KNOCK_AGAIN = 30
        private const val LET_SELF_IN = 60
        /** How near a child of their home must be for a grown-up to call them along. */
        private const val CALL_ALONG = 8.0
        /** The longest a shouter waits for their turn to shout (then they run without), and how long the shout holds them. */
        private const val SHOUT_WAIT = 80
        private val SHOUTED = Activity.SHOUT.ticks - 6

        /** The whispers behind each door, every 8 to 14 seconds: a grown-up's hush, a child's fear. Every 20 ticks, from [Shelter.tick]. */
        internal fun whispers(level: ServerLevel, c: Shelter.Called) {
            val now = level.gameTime
            for (h in c.havens.values) {
                val in_ = h.folk.filter { !it.done && it.stage == S.COWER && it.at }
                if (in_.isEmpty()) continue
                if (h.whisper == 0L) h.whisper = now + 60 + in_.first().p.random.nextInt(100)
                if (now < h.whisper) continue
                val who = in_[in_.first().p.random.nextInt(in_.size)]
                h.whisper = now + 160 + who.p.random.nextInt(121)
                val line = if (who.p.role == TownRole.CHILD) "shelter_child_${1 + who.p.random.nextInt(2)}" else hush(who.p)
                Trouble.say(who.p, line, WHISPER)
            }
        }

        /** How far a whisper is heard. */
        private const val WHISPER = 12.0

        /** One of the grown-ups' hushes that [p] has the voice for. */
        internal fun hush(p: Townsperson): String {
            if (p.role == TownRole.CHILD) return "shelter_child_${1 + p.random.nextInt(2)}"
            val fit = (1..4).map { "shelter_hush_$it" }.filter { Trouble.fits(p, it) }
            return fit.getOrNull(p.random.nextInt(maxOf(1, fit.size))) ?: "shelter_hush_2"
        }
    }
}

/** A place to stand at or right by a block: the block, a step up or down, then round it. */
internal object GroundNear {
    fun stand(level: ServerLevel, at: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1, 2, -2)) if (level.isLoaded(at.above(dy)) && Places.standable(level, at.above(dy))) return at.above(dy)
        for (d in Direction.Plane.HORIZONTAL) for (dy in intArrayOf(0, 1, -1)) {
            val pos = at.relative(d).above(dy)
            if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
        }
        return null
    }
}

/**
 * The alarm (design 16.1, moment 19): the village's bellman, if they are by the bell, runs to it and rings it hard, six strokes close
 * together, the bell's own (`Townsperson.greatBell`, so no handbell plays on top), and then shelters with the rest; with no bellman
 * near, or none who gets there in time, the bell rings by itself.
 */
internal object Ringer {
    /** How near the bell the bellman must be to ring the alarm. */
    const val NEAR = 24.0
    private const val STROKES = 6
    private const val EVERY = 10
    private const val LATE = 160

    private val ringing = HashMap<Long, Ring>()

    fun alarm(level: ServerLevel, c: Shelter.Called) {
        val bellOk = level.isLoaded(c.bell) && level.getBlockState(c.bell).block is BellBlock
        if (!bellOk) return
        val p = Shelter.bellman(level, c)
        val spot = p?.let { BellKeeper.standAt(level, c.bell, it.blockPosition(), it) }
        if (p == null || spot == null) {
            Trouble.ring(level, c.bell, STROKES, EVERY, after = 10)
            return
        }
        when (val d = p.director) {
            is Greeter -> d.release()
            is BuilderCrew.Builder -> d.handBack()
            is Sheltering -> d.finish()
            else -> {}
        }
        if (p.isSleeping) p.stopSleeping()
        p.standUp()
        p.routine.interrupt()
        p.navigation.stop()
        val ring = Ring(c, p, spot)
        ringing[c.key] = ring
        p.director = ring
    }

    /** The all-clear came first: the ringer stops and goes to their day. */
    fun stand(level: ServerLevel, c: Shelter.Called) {
        ringing.remove(c.key)?.finish(level, shelter = false)
    }

    fun tick(level: ServerLevel) {
        if (ringing.isEmpty()) return
        ringing.values.removeIf { it.done }
    }

    fun forget() = ringing.clear()

    private class Ring(val c: Shelter.Called, val p: Townsperson, val spot: Spot) : Director {
        var done = false
        private var strokes = 0
        private var at = -1
        private var lastTick = -1
        private var started = 0
        private val walker = Walker(p)

        override fun tick(p: Townsperson) {
            val level = p.level() as? ServerLevel ?: return
            if (done) return
            if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0 || c.over || !Trouble.on()) return finish(level, shelter = false)
            if (lastTick < 0) {
                started = p.tickCount
                walker.go(spot.pos, 0.9, exact = true)
                p.act(Activity.FLEE)
            }
            lastTick = p.tickCount
            if (at < 0) {
                when (walker.tick()) {
                    Walker.State.WALKING -> if (p.tickCount - started > LATE) return finish(level, shelter = true)
                    Walker.State.ARRIVED -> {
                        at = p.tickCount
                        p.face(spot.facing)
                        p.greatBell = true
                        p.act(Activity.RING_BELL)
                    }
                    Walker.State.FAILED -> return finish(level, shelter = true)
                }
                return
            }
            p.face(spot.facing)
            val into = p.tickCount - at
            if (into % EVERY == 0 && strokes < STROKES) {
                strokes++
                (level.getBlockState(c.bell).block as? BellBlock)?.attemptToRing(level, c.bell, spot.facing)
            }
            if (strokes >= STROKES && into >= STROKES * EVERY + 10) finish(level, shelter = true)
        }

        /** Done ringing (or never got there: the bell rings the strokes left by itself); with [shelter], into the shelter with the rest. */
        fun finish(level: ServerLevel, shelter: Boolean) {
            if (done) return
            done = true
            if (strokes < STROKES && c.ending == null) Trouble.ring(level, c.bell, STROKES - strokes, EVERY)
            if (p.director === this) p.director = null
            p.act(Activity.NONE)
            p.navigation.stop()
            p.routine.interrupt()
            if (shelter && c.ending == null && !c.over && p.isAlive) {
                val s = Sheltering(c, p, 0, false)
                c.folk[p.id] = s
                p.director = s
            }
        }
    }
}
