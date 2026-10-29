package io.github.vaspyyy.guildmark.village.live.grow

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The bodies' part in a village's growth (living-world/villages/design.md 5.6), played where a player can see it, each a short
 * script for everyone in it: a [Birth] shown in the lane of a morning, a neighbour's word on a child [Grown] up, a household's
 * [Arrival] off the road at dusk, and a household's [Departure] with its cart. Those in one are in its charge ([Hand]) and out of
 * their day's round; a word from a player gets a nod, and on with it.
 *
 * A scene ends when its script does; cut short (anyone in it hurt, nobody within [NEAR] blocks for [ALONE] ticks, or [MOST] ticks
 * gone by), what it was about is done all the same (the record has it already) and its people go back to their day, those leaving
 * going as soon as nobody can see them. Nothing here is saved: after a restart the record stands, and the bodies are as it says.
 */
internal object GrowScenes {
    /** How near a player must be to the door for an arrival to be played, and for the rest. */
    const val SEEN = 96.0
    const val NEAR = 64.0

    /** The longest a scene plays (ticks), and how long with nobody near before it is cut short. */
    private const val MOST = 6000
    private const val ALONE = 300

    /** How far neighbours come from, and the most that come. */
    private const val CALL = 32.0
    private const val NEIGHBOURS = 3

    /** Ticks they take the place in on arrival; the longest the neighbours are given at the door, and with none. */
    private val LOOK = Activity.LOOK_ABOUT.ticks + 20
    private const val WELCOME = 420
    private const val NOBODY = 40

    /** The longest the carrying in or packing goes on, and the cart's wait to be brought round unseen. */
    private const val WORK = 2400
    private const val CART_WAIT = 600

    /** HAND_OVER's hand-off: from that tick the piece is the cart's (as ComingsBodies has it). */
    private const val HANDOFF = 16

    /**
     * Loops of CRADLE the baby is shown for, at least and at most (on until the neighbours have had their look); the ticks in each
     * loop its coos are heard (the clip's `coo` moments).
     */
    private const val CRADLE_LOOPS = 3
    private const val CRADLE_MOST = 9
    private val COOS = intArrayOf(16, 46)

    private val scenes = ArrayList<Scene>()

    /** Residents (by id) a scene has charge of, or whose body a scene is to make. */
    private val held = HashMap<Int, Scene>()

    fun holds(id: Int): Boolean = held.containsKey(id)

    fun has(p: Townsperson): Boolean = p.director is Hand

    /** Whether a scene plays in the village [key] now. */
    fun running(key: Long): Boolean = scenes.any { it.key == key }

    /** What plays in the village [key] now, in words (for `village grow`). */
    fun describe(key: Long): List<String> = scenes.filter { it.key == key }.map { "${it.name}: ${it.stage}, ${it.crew.size} in it, ${it.age / 20}s in" }

    /** The server stopped: nothing here is kept. */
    fun forget() {
        scenes.clear()
        held.clear()
    }

    /** Whether any scene is under way. */
    fun any(): Boolean = scenes.isNotEmpty()

    /** Every scene under way ended now, as one cut short is (Births and Newcomers or Living Villages switched off). */
    fun endAll() {
        for (s in scenes.toList()) finish(s, off = true)
    }

    fun start(s: Scene) {
        scenes += s
    }

    // ---- Each tick ----

    fun tick(level: ServerLevel) {
        if (scenes.isEmpty()) return
        for (s in scenes.toList()) {
            if (s.level !== level) continue
            try {
                run(s)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A village's growth scene ({}) failed", s.name, e)
                finish(s, off = true)
            }
        }
    }

    private fun run(s: Scene) {
        s.age++
        s.t++
        // Anyone taken up by something else meanwhile (a fight, a tale) is out of it
        s.crew.removeIf { h ->
            (!h.p.isAlive || h.p.isRemoved || h.p.director != null && h.p.director !== h).also { out ->
                // The baby goes in with whoever has them now, not left in their arms
                if (out) h.p.swaddled = false
            }
        }
        for (h in s.crew) if (h.p.director == null) h.p.director = h
        if (s.age % 20 == 0) {
            val near = s.level.players().any { pl -> pl.distanceToSqr(s.at) < NEAR * NEAR || s.crew.any { it.p.distanceToSqr(pl) < NEAR * NEAR } }
            s.alone = if (near) 0 else s.alone + 20
        }
        if (s.crew.any { it.p.hurtTime > 0 } || s.alone >= ALONE || s.age > MOST || s.crew.isEmpty()) return finish(s, off = true)
        if (!s.step()) finish(s, off = false)
    }

    /** [s] is over: what it leaves is seen to, and everyone in it goes back to their day. */
    private fun finish(s: Scene, off: Boolean) {
        scenes.remove(s)
        try {
            s.end(off)
        } finally {
            for (h in s.crew) release(h)
            s.crew.clear()
            held.values.removeIf { it === s }
        }
    }

    /** [p] in a scene's charge from now: up, standing, and out of anything else they were at. */
    private fun take(p: Townsperson) {
        if (p.isSleeping) p.stopSleeping()
        if (p.seat != null) p.standUp()
        (p.director as? Aside)?.end(p, drop = true)
        p.routine.interrupt()
        p.navigation.stop()
        p.carried = ItemStack.EMPTY
    }

    /** [h] out of the scene's charge, to their day. */
    private fun release(h: Hand) {
        val p = h.p
        p.swaddled = false
        if (p.director === h) p.director = null
        p.navigation.stop()
        if (!p.carried.isEmpty) p.carried = ItemStack.EMPTY
        if (p.activity.loop || p.activity.overlay || p.activity == Handcarts.PULL) p.act(Activity.NONE)
        p.routine.interrupt()
    }

    // ---- Who comes over ----

    /** The free grown-ups of the village [key] within [CALL] blocks of [at] (none of [not]), nearest first, [most] at most. */
    fun neighbours(level: ServerLevel, key: Long, at: Vec3, not: Set<Int>, most: Int = NEIGHBOURS): List<Townsperson> =
        Residents.get(level).byCentre(BlockPos.of(key)).asSequence()
            .filter { it.alive && it.role.adult && it.id !in not && !holds(it.id) }
            .mapNotNull { r -> r.uuid?.let { level.getEntity(it) } as? Townsperson }
            .filter { it.isAlive && it.director == null && !it.isSleeping && it.tale < 0 && it.distanceToSqr(at) < CALL * CALL }
            .sortedBy { it.distanceToSqr(at) }
            .take(most)
            .toList()

    /** Says the voiced line [key] (one of the single lines, such as `welcome_1`) now if a player is in hearing; true if said. */
    fun line(p: Townsperson, key: String): Boolean = Words.on() && p.isAlive && p.say(key, force = true, ticks = TalkLines.ticks(key) + 20L)

    // ---- Where ----

    /** Somewhere to stand at or right by [at] (the block, then round it, a step up or down), or null. */
    fun stand(level: ServerLevel, at: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1)) if (level.isLoaded(at.above(dy)) && Places.standable(level, at.above(dy))) return at.above(dy)
        for (d in Direction.Plane.HORIZONTAL) for (dy in intArrayOf(0, 1, -1)) {
            val pos = at.relative(d).above(dy)
            if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
        }
        return null
    }

    /** The lane before [door], [out] blocks from it: where a birth is shown and a household gathers. */
    fun laneOf(level: ServerLevel, door: CityPlaces.Door, out: Int = 3): BlockPos {
        for (n in intArrayOf(out, out - 1, out + 1, 2)) stand(level, door.pos.relative(door.inward.opposite, n))?.let { return it }
        return door.outside
    }

    /** The [k]th of [n] places on an arc [r] blocks round [centre], toward [toward]: where a crowd of neighbours stands. */
    fun arc(level: ServerLevel, centre: Vec3, toward: Vec3, k: Int, n: Int, r: Double = 2.6): BlockPos {
        val base = Math.atan2(toward.z - centre.z, toward.x - centre.x)
        val a = base + (k - (n - 1) / 2.0) * 0.7
        val at = BlockPos.containing(centre.x + cos(a) * r, centre.y + 0.5, centre.z + sin(a) * r)
        return stand(level, at) ?: BlockPos.containing(centre)
    }

    /** A place to put things down in [home] for the [k]th of them: by its beds, else its middle. */
    fun roomSpot(level: ServerLevel, home: CityPlaces.Lodging, door: CityPlaces.Door, k: Int): BlockPos {
        val spots = home.beds + listOf(BlockPos(home.box.center.x, home.y, home.box.center.z))
        val base = spots[Math.floorMod(k, spots.size)]
        for (d in listOf(null) + Direction.Plane.HORIZONTAL.toList()) {
            val at = if (d == null) base else base.relative(d)
            val pos = stand(level, at) ?: continue
            if (home.contains(pos)) return pos
        }
        return door.inside
    }

    /** [door]'s door opened or shut, if it is a door; true if it was one. */
    fun swing(level: ServerLevel, door: CityPlaces.Door, open: Boolean, by: Townsperson?): Boolean {
        for (pos in listOf(door.pos, door.pos.above(), door.pos.below())) {
            if (!level.isLoaded(pos)) continue
            val state = level.getBlockState(pos)
            val block = state.block as? DoorBlock ?: continue
            if (block.isOpen(state) != open) block.setOpen(by, level, state, pos, open)
            return true
        }
        return false
    }

    fun eye(p: Townsperson): Vec3 = p.position().add(0.0, 1.5, 0.0)

    fun top(pos: BlockPos): Vec3 = Vec3.atCenterOf(pos).add(0.0, 1.0, 0.0)

    // ---- A scene, and those in it ----

    /** One of the growth's scenes in the village [key]: its [crew] in its charge, its [step] each tick until it is done. */
    abstract class Scene(val level: ServerLevel, val key: Long, val name: String) {
        val crew = ArrayList<Hand>()
        var age = 0
        var t = 0
        var alone = 0

        /** Where it plays, for whether a player is near. */
        abstract val at: Vec3

        /** Its stage, in a word. */
        abstract val stage: String

        /** One tick of it; false once it is done. */
        abstract fun step(): Boolean

        /** It is over ([off]: cut short): anything it leaves is seen to. Its crew is released after. */
        open fun end(off: Boolean) {}

        /** [p] (resident [id], -1 for none) in this scene's charge from now. */
        fun hand(p: Townsperson, id: Int): Hand {
            take(p)
            val h = Hand(this, p)
            crew += h
            p.director = h
            if (id >= 0) held[id] = this
            return h
        }

        /** Resident [id] is this scene's (their body is its to make). */
        fun hold(id: Int) {
            held[id] = this
        }

        /** [h] done with: out of the scene and back to their day. */
        fun drop(h: Hand) {
            if (crew.remove(h)) release(h)
        }

        /** On to the next stage: [t] counts from it. */
        protected fun next() {
            t = 0
        }
    }

    /** Someone in a scene: their [Act]s in turn. */
    class Hand(val scene: Scene, val p: Townsperson) : Director {
        val walker = Walker(p)
        private val queue = ArrayDeque<Act>()
        private var act: Act? = null
        private var started = false

        /** Their last walk could not be walked. */
        var lost = false

        /** Their part done (in their new home, the cart packed). */
        var home = false

        /** A piece of a household's goods they are taking to the cart. */
        var piece: Handcarts.Placed? = null

        private var nodTo: ServerPlayer? = null
        private var nodT = 0

        val idle: Boolean get() = act == null && queue.isEmpty()

        /** Standing where they are to wait, until the next stage. */
        val waiting: Boolean get() = queue.isEmpty() && act?.forever == true

        fun set(vararg acts: Act) {
            act?.stop(this)
            act = null
            started = false
            queue.clear()
            queue.addAll(acts)
        }

        override fun tick(p: Townsperson) {
            val player = nodTo
            if (player != null) {
                if (nodT == 0) {
                    p.navigation.stop()
                    p.act(Activity.NOD)
                }
                p.lookAt(player.eyePosition)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(player.position()), 18f))
                if (++nodT >= Activity.NOD.ticks + 10) {
                    nodTo = null
                    nodT = 0
                    act?.start(this)
                }
                return
            }
            var guard = 0
            while (guard++ < 4) {
                val current = act ?: queue.removeFirstOrNull()?.also {
                    act = it
                    started = false
                } ?: return
                if (!started) {
                    started = true
                    current.start(this)
                }
                if (current.tick(this)) return
                current.stop(this)
                act = null
            }
        }

        /** Busy just now: a nod to whoever speaks to them, and on with it. */
        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            if (nodTo == null) {
                nodTo = player
                nodT = 0
            }
            return true
        }

        fun face(at: Vec3) {
            val yaw = p.yawTo(at)
            p.face(yaw)
        }
    }

    /** Someone gone from the village, standing where they are until nobody can see them (VillageGrowth takes them away then). */
    object Still : Director {
        override fun tick(p: Townsperson) {
            p.navigation.stop()
        }

        override fun interact(p: Townsperson, player: ServerPlayer): Boolean = true
    }

    // ---- What one of them does ----

    abstract class Act {
        open val forever: Boolean get() = false

        open fun start(h: Hand) {}

        /** One tick of it; false once it is done. */
        abstract fun tick(h: Hand): Boolean

        open fun stop(h: Hand) {}

        /** Keeps [want] playing once a one-shot is done. */
        protected fun keep(h: Hand, want: Activity) {
            if (h.p.activity != want && (h.p.activity.loop || h.p.actDone)) h.p.act(want)
        }

        /** What they hold as they go: the shafts with the cart in hand, the baby or their arms full as they carry, else [overlay]. */
        protected fun holding(h: Hand, overlay: Activity): Activity = when {
            Handcarts.pulling(h.p) -> Handcarts.PULL
            h.p.swaddled || !h.p.carried.isEmpty -> Activity.CARRY
            overlay == Handcarts.PULL || overlay == Activity.CARRY -> Activity.NONE
            else -> overlay
        }
    }

    /** A walk to wherever [to] says as it starts. */
    class Go(
        private val to: (Hand) -> BlockPos?, private val speed: Double, private val near: Double = 1.0,
        private val overlay: Activity = Activity.NONE, private val exact: Boolean = false, private val most: Int = 900,
    ) : Act() {
        private var target: BlockPos? = null
        private var t = 0

        override fun start(h: Hand) {
            t = 0
            target = to(h)
            val at = target ?: return
            h.p.act(holding(h, overlay))
            h.walker.go(at, speed, near, exact)
        }

        override fun tick(h: Hand): Boolean {
            if (target == null) return false
            if (++t > most) {
                h.lost = true
                return false
            }
            keep(h, holding(h, overlay))
            return when (h.walker.tick()) {
                Walker.State.WALKING -> true
                Walker.State.ARRIVED -> false
                Walker.State.FAILED -> {
                    h.lost = true
                    false
                }
            }
        }

        override fun stop(h: Hand) = h.walker.stop()
    }

    /** Stands [ticks] (-1: until the stage moves on) facing whatever [at] says, in [mood] (a loop such as ADMIRE), looking about now and then if [about]. */
    class Stand(private val ticks: Int, private val at: (Hand) -> Vec3?, private val mood: Activity = Activity.NONE, private val about: Boolean = false) : Act() {
        private var t = 0
        private var next = 0

        override val forever: Boolean get() = ticks < 0

        override fun start(h: Hand) {
            t = 0
            next = 40 + h.p.random.nextInt(80)
            h.p.navigation.stop()
            h.p.act(holding(h, mood))
        }

        override fun tick(h: Hand): Boolean {
            t++
            at(h)?.let {
                h.p.lookAt(it)
                h.p.face(Mth.approachDegrees(h.p.yBodyRot, h.p.yawTo(it), 12f))
            }
            val base = holding(h, mood)
            if (about && base == Activity.NONE && --next <= 0) {
                next = 120 + h.p.random.nextInt(160)
                if (h.p.random.nextBoolean() && h.p.activity == Activity.NONE) h.p.act(Activity.LOOK_ABOUT)
            }
            if (!(about && h.p.activity == Activity.LOOK_ABOUT && !h.p.actDone)) keep(h, base)
            return ticks < 0 || t < ticks
        }
    }

    /** A one-shot played facing whatever [at] says; [done] on the move's tick [moment] (-1: as it ends). */
    class Play(
        private val activity: Activity, private val at: (Hand) -> Vec3?, private val moment: Int = -1, private val done: (Hand) -> Unit = {},
    ) : Act() {
        private var t = 0
        private var fired = false

        override fun start(h: Hand) {
            t = 0
            if (fired) return
            h.p.navigation.stop()
            at(h)?.let { h.face(it) }
            h.p.act(activity)
        }

        override fun tick(h: Hand): Boolean {
            if (fired && t == 0) return false
            at(h)?.let { h.p.lookAt(it) }
            t++
            if (!fired && t == (if (moment < 0) activity.ticks else moment + 1)) {
                fired = true
                done(h)
            }
            return t < activity.ticks
        }
    }

    /** Stands facing whatever [at] says until [ready] (or [most] ticks have gone by). */
    class Until(private val ready: (Hand) -> Boolean, private val at: (Hand) -> Vec3?, private val most: Int = 400, private val mood: Activity = Activity.NONE) : Act() {
        private var t = 0

        override fun start(h: Hand) {
            t = 0
            h.p.navigation.stop()
            h.p.act(holding(h, mood))
        }

        override fun tick(h: Hand): Boolean {
            if (ready(h) || ++t > most) return false
            at(h)?.let {
                h.p.lookAt(it)
                h.p.face(Mth.approachDegrees(h.p.yBodyRot, h.p.yawTo(it), 12f))
            }
            keep(h, holding(h, mood))
            return true
        }
    }

    /** Something done at once, in its turn. */
    class Do(private val what: (Hand) -> Unit) : Act() {
        override fun tick(h: Hand): Boolean {
            what(h)
            return false
        }
    }

    /**
     * The baby held up for the lane to see (CRADLE, [least] times, and on up to [most] until [ready]), facing whatever [at] says,
     * its coos heard at the clip's moments.
     */
    class Cradle(private val least: Int, private val most: Int, private val ready: () -> Boolean, private val at: (Hand) -> Vec3?) : Act() {
        private var t = 0

        override fun start(h: Hand) {
            t = 0
            h.p.navigation.stop()
            at(h)?.let { h.face(it) }
            h.p.act(Activity.CRADLE)
        }

        override fun tick(h: Hand): Boolean {
            t++
            keep(h, Activity.CRADLE)
            at(h)?.let { h.p.face(Mth.approachDegrees(h.p.yBodyRot, h.p.yawTo(it), 4f)) }
            val loopT = Math.floorMod(h.p.tickCount - h.p.activityStart, Activity.CRADLE.ticks)
            if (h.p.activity == Activity.CRADLE && loopT in COOS) {
                io.github.vaspyyy.guildmark.village.live.VillageSounds.play(h.scene.level, eye(h.p).subtract(0.0, 0.3, 0.0), io.github.vaspyyy.guildmark.village.live.VillageSounds.BABY, 0.8f)
            }
            // Held up a loop at a time: at least [least] times, and on (to [most]) until the neighbours have had their look
            if (t % Activity.CRADLE.ticks != 0) return true
            val loops = t / Activity.CRADLE.ticks
            return loops < least || loops < most && !ready()
        }
    }

    /** The [k]th of the rest walking with [lead]: beside the cart (the first two, one each side) or behind it. */
    class Trail(private val k: Int, private val lead: () -> Townsperson?, private val about: Boolean) : Act() {
        private var target: BlockPos? = null
        private var pause = 0
        private var next = 0

        override val forever: Boolean get() = true

        override fun start(h: Hand) {
            target = null
            pause = 0
            next = 160 + h.p.random.nextInt(200)
        }

        override fun tick(h: Hand): Boolean {
            val lead = lead() ?: return false
            if (lead === h.p) return false
            if (pause > 0) {
                if (--pause == 0) h.p.act(holding(h, Activity.NONE))
                return true
            }
            val spot = spot(h, lead)
            val d = h.p.position().distanceToSqr(spot)
            if (about && h.p.carried.isEmpty && --next <= 0 && d < 3.0 * 3.0) {
                next = 240 + h.p.random.nextInt(260)
                if (h.p.random.nextInt(3) == 0) {
                    h.walker.stop()
                    target = null
                    h.p.act(Activity.LOOK_ABOUT)
                    pause = Activity.LOOK_ABOUT.ticks
                    return true
                }
            }
            val pos = BlockPos.containing(spot)
            val now = target
            if (d > 1.6 * 1.6 && (now == null || now.distSqr(pos) > 2.5)) {
                target = pos
                h.walker.go(pos, if (d > 6.0 * 6.0) io.github.vaspyyy.guildmark.townsfolk.Jobs.BRISK else io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.9)
            }
            if (target != null) {
                if (h.walker.tick() != Walker.State.WALKING) target = null
            } else {
                h.p.face(Mth.approachDegrees(h.p.yBodyRot, lead.yBodyRot, 10f))
            }
            keep(h, holding(h, Activity.NONE))
            return true
        }

        override fun stop(h: Hand) = h.walker.stop()

        private fun spot(h: Hand, lead: Townsperson): Vec3 {
            val a = Math.toRadians(lead.yBodyRot.toDouble())
            val fx = -sin(a)
            val fz = cos(a)
            val cart = Handcarts.pulling(lead)
            val back = if (k < 2) (if (cart) 2.0 else 1.6) else (if (cart) 4.6 else 3.2)
            val side = (if (k < 2) (if (cart) 1.5 else 1.0) else 0.7) * (if (k % 2 == 0) 1.0 else -1.0)
            val want = Vec3(lead.x - fx * back + fz * side, lead.y, lead.z - fz * back - fx * side)
            val level = h.p.level()
            val at = BlockPos.containing(want)
            for (dy in intArrayOf(0, 1, -1)) if (Places.standable(level, at.above(dy))) return Vec3(want.x, at.y + dy.toDouble(), want.z)
            return Vec3(lead.x - fx * back, lead.y, lead.z - fz * back)
        }
    }

    // ---- A birth shown ----

    /**
     * The morning after a birth, with a player near: a parent fetches the baby from its cot (PICK_UP), carries it swaddled out
     * into the lane before the house (CARRY, the client's carry_swaddle) and holds it up for the street to see (CRADLE, three
     * times over, its coos heard at the clip's moments), "Meet our newest." (born_proud), the green sparks of a glad thing once;
     * the other parent stands by admiring; up to three neighbours come over in turn: one with a word (INTRODUCE, born_cheer_1),
     * one with a cheer (CHEER, born_cheer_2, the village's cheer heard), one clapping; then they admire it a while, wave, and go
     * back to their day, and the baby is carried in and laid down again.
     */
    class Birth(
        level: ServerLevel, key: Long, private val g: Grow, private val baby: Baby, private val cot: BlockPos, private val bed: BlockPos,
        private val door: CityPlaces.Door, private val household: Set<Int>,
    ) : Scene(level, key, "a birth shown") {
        private val lane = laneOf(level, door)
        override val at: Vec3 = Vec3.atCenterOf(lane)
        private var shown = 0
        private var back = false
        private var cheers = 0
        private var expected = 0
        private var said = false
        private var called = false
        private var parent: Hand? = null
        private var other: Hand? = null
        private val crowd = ArrayList<Hand>()

        override val stage: String get() = when {
            back -> "carried in again"
            shown > 0 -> "shown in the lane"
            else -> "fetched from the cot"
        }

        /** [mother] carries the baby out; [father] (if any) stands by. */
        fun begin(mother: Townsperson, motherId: Int, father: Townsperson?, fatherId: Int) {
            val h = hand(mother, motherId)
            parent = h
            val toStreet = { _: Hand -> street() }
            h.set(
                Go({ cot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 1.2),
                Play(Activity.PICK_UP, { Vec3.atCenterOf(bed) }, 10) { it.p.swaddled = true },
                Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 0.8),
                Go({ lane }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 0.6),
                Do { show() },
                Cradle(CRADLE_LOOPS, CRADLE_MOST, { called && cheers >= expected && crowd.none { it.p.position().distanceToSqr(at) > 9.0 * 9.0 } }, toStreet),
                Do { back = true },
                Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 0.8),
                Go({ cot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 1.0),
                Play(Activity.PUT_DOWN, { Vec3.atCenterOf(bed) }, 12) { it.p.swaddled = false },
            )
            if (father != null) {
                other = hand(father, fatherId).also { o ->
                    o.set(Until({ shown > 0 }, { parent?.p?.let { p -> eye(p) } }, 1800))
                }
            }
            start(this)
        }


        /** Out in the lane: the word, the sparks, the other parent beside them and the neighbours called over. */
        private fun show() {
            shown = 1
            next()
            val p = parent?.p ?: return
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, p.x, p.y + 1.4, p.z, 7, 0.45, 0.3, 0.45, 0.02)
            other?.let { o ->
                val beside = arc(level, p.position(), street(), 0, 1, 1.4).let { stand(level, it.relative(door.inward.clockWise)) ?: it }
                o.set(
                    Go({ beside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.8),
                    Until({ back }, { parent?.p?.let { eye(it).subtract(0.0, 0.4, 0.0) } }, 900, Activity.ADMIRE),
                    Play(Activity.NOD, { parent?.p?.let { eye(it) } }),
                    Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 0.8),
                )
            }
        }

        /** The neighbours, once the baby is out: up to three, in turn, a word, a cheer and a clap, admiring it after. */
        private fun call() {
            called = true
            val p = parent?.p ?: return
            val came = neighbours(level, key, p.position(), household)
            expected = came.size
            val street = street()
            for ((k, n) in came.withIndex()) {
                val h = hand(n, n.resident)
                crowd += h
                val spot = arc(level, p.position(), street, k, came.size)
                val face = { _: Hand -> parent?.p?.let { eye(it).subtract(0.0, 0.4, 0.0) } }
                val part: Act = when (k) {
                    0 -> Play(Activity.INTRODUCE, face, 8) {
                        line(it.p, "born_cheer_1")
                        cheers++
                    }
                    1 -> Play(Activity.CHEER, face, 6) {
                        line(it.p, "born_cheer_2")
                        io.github.vaspyyy.guildmark.village.live.VillageSounds.play(level, eye(it.p), io.github.vaspyyy.guildmark.village.live.VillageSounds.CHEER, 0.9f)
                        cheers++
                    }
                    else -> Play(Activity.CLAP, face, 4) { cheers++ }
                }
                h.set(
                    Go({ spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.9),
                    Until({ cheers >= k }, face, 600),
                    part,
                    Until({ back }, face, 900, Activity.ADMIRE),
                    Play(Activity.WAVE, face),
                )
            }
        }

        private fun street(): Vec3 = top(door.pos.relative(door.inward.opposite, 7))

        override fun step(): Boolean {
            val p = parent ?: return false
            if (p !in crew) return false
            if (shown > 0 && !said && t % 20 == 2 && t < 200) said = line(p.p, "born_proud")
            if (shown > 0 && !called && t >= 30) call()
            for (h in crowd.toList()) if (h.idle) {
                crowd.remove(h)
                drop(h)
            }
            other?.let { if (it.idle && back) drop(it) }
            return !p.idle
        }

        override fun end(off: Boolean) {
            parent?.p?.swaddled = false
            baby.shown = true
            GrowthData.get(level).changed()
        }
    }

    // ---- A child grown up ----

    /** The morning after a child grew up, a neighbour stops by them with a word on it (POINT, "Look how they've grown!"), and they shrug or nod it off. */
    class Remark(level: ServerLevel, key: Long, private val grown: Grown, private val shrug: Boolean) : Scene(level, key, "a word on one grown up") {
        override var at: Vec3 = Vec3.ZERO
        private var pointed = false
        private var them: Hand? = null
        private var by: Hand? = null

        override val stage: String get() = if (pointed) "said" else "coming over"

        fun begin(who: Townsperson, neighbour: Townsperson) {
            at = who.position()
            val g = hand(who, grown.id)
            val n = hand(neighbour, neighbour.resident)
            them = g
            by = n
            val spot = stand(level, BlockPos.containing(who.position().add(neighbour.position().subtract(who.position()).normalize().scale(2.0)))) ?: neighbour.blockPosition()
            n.set(
                Go({ spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.8),
                Play(Activity.POINT, { eye(who) }, 12) {
                    line(it.p, "grown_up")
                    pointed = true
                },
                Stand(40, { eye(who) }),
            )
            g.set(
                Until({ pointed }, { eye(neighbour) }, 600),
                Stand(24, { eye(neighbour) }),
                Play(if (shrug) Activity.SHRUG else Activity.NOD, { eye(neighbour) }),
            )
            start(this)
        }

        override fun step(): Boolean = crew.any { !it.idle }

        override fun end(off: Boolean) {
            grown.said = true
            GrowthData.get(level).changed()
        }
    }

    // ---- A household come ----

    /**
     * A household come at dusk with a player near: set down out of sight at the end of the village's road (toward the capital
     * it is linked to), the first grown-up pulling a handcart heaped with the household's goods ([Handcarts.household]) and the
     * rest alongside, they walk in to their empty home; at its door they stop and take the place in (LOOK_ABOUT, "So this is
     * the place"), the door is opened, and the neighbours come over: a wave and "Welcome to the village." (INTRODUCE,
     * welcome_1), the next with welcome_2, the third a bow and welcome_3, the household's first bowing back with welcome_reply.
     * Then the goods are carried in off the cart a piece at a time, what is on top first (the children the bundles), the door is
     * shut behind them, and the empty cart goes once nobody is looking.
     */
    class Arrival(
        level: ServerLevel, key: Long, private val home: CityPlaces.Lodging, private val door: CityPlaces.Door, private val back: Boolean,
    ) : Scene(level, key, if (back) "a household come home" else "a household come") {
        private enum class Stage { ROAD, LOOK, WELCOME, CARRY }

        private var now = Stage.ROAD
        override val at: Vec3 = Vec3.atCenterOf(door.pos)
        override val stage: String get() = now.name.lowercase()

        private var bay: BlockPos? = null
        private var cart = false
        private var opened = false
        private var said = false
        private var welcomed = 0
        private var replied = false
        private var tries = 0
        private val heaped = ArrayList<Handcarts.Placed>()
        private val crowd = ArrayList<Hand>()
        private val household = ArrayList<Hand>()

        private val lead: Hand? get() = household.firstOrNull { it.p.role.adult } ?: household.firstOrNull()

        /**
         * Their bodies set down at [setDown] (out of sight) facing their way in: the ids of [members] whose bodies were made (the
         * rest are made at home out of sight). Starts the scene if anyone was set down.
         */
        fun begin(rec: io.github.vaspyyy.guildmark.village.live.VillageRec, places: io.github.vaspyyy.guildmark.village.live.VillagePlaces, members: List<Resident>, setDown: BlockPos): List<Int> {
            val toward = Vec3.atCenterOf(door.pos)
            val yaw = (Math.toDegrees(Math.atan2(toward.z - (setDown.z + 0.5), toward.x - (setDown.x + 0.5))) - 90.0).toFloat()
            val backward = Vec3.atCenterOf(setDown).subtract(toward).normalize()
            val made = ArrayList<Int>()
            val ordered = members.sortedWith(compareBy({ if (it.role.adult) 0 else 1 }, { it.id }))
            for ((k, r) in ordered.withIndex()) {
                val off = if (k == 0) Vec3.ZERO else Vec3(
                    backward.x * (2.5 + 1.4 * ((k + 1) / 2)) + backward.z * (if (k % 2 == 0) 1.0 else -1.0), 0.0,
                    backward.z * (2.5 + 1.4 * ((k + 1) / 2)) - backward.x * (if (k % 2 == 0) 1.0 else -1.0),
                )
                val spot = stand(level, BlockPos.containing(Vec3.atBottomCenterOf(setDown).add(off))) ?: setDown
                val p = VillageGrowth.body(level, rec, places, r, spot, yaw) ?: continue
                household += hand(p, r.id)
                made += r.id
            }
            if (household.isEmpty()) return made
            val puller = lead ?: return made
            val goods = Handcarts.household(members.size)
            if (Handcarts.hitch(puller.p) != null) {
                cart = true
                Handcarts.heap(puller.p, goods)
                heaped.addAll(goods)
            } else {
                // No cart: what they can carry in their arms, a piece each
                val pieces = ArrayDeque(goods.map { it.stack })
                for (h in household) {
                    val piece = (if (h.p.role.adult) pieces.removeFirstOrNull() else pieces.firstOrNull { it.`is`(net.minecraft.world.item.Items.BUNDLE) }?.also { pieces.remove(it) }) ?: continue
                    h.p.carried = piece
                }
            }
            road()
            start(this)
            return made
        }

        private fun road() {
            val puller = lead ?: return
            bay = if (cart) Handcarts.bay(level, door, puller.p) else null
            val goal = bay ?: laneOf(level, door, 2)
            if (cart) Handcarts.headFor(puller.p, goal)
            puller.set(Go({ goal }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, if (bay != null) 0.8 else 1.4, most = 3600))
            household.filter { it !== puller }.forEachIndexed { k, h -> h.set(Trail(k, { lead?.p }, about = true)) }
        }

        private fun front(): Vec3 = top(door.pos)

        override fun step(): Boolean {
            household.removeIf { it !in crew }
            crowd.removeIf { it !in crew }
            val puller = lead ?: return false
            when (now) {
                Stage.ROAD -> if (puller.idle) {
                    if (puller.lost && tries++ < 3) {
                        puller.lost = false
                        road()
                    } else {
                        Handcarts.unhitch(puller.p)
                        Handcarts.headFor(puller.p, null)
                        now = Stage.LOOK
                        next()
                        val lane = laneOf(level, door, 2)
                        for ((k, h) in household.withIndex()) {
                            val spot = if (h === puller) null else arc(level, Vec3.atCenterOf(lane), front(), k, household.size, 1.8)
                            if (spot != null) h.set(Go({ spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.9), Play(Activity.LOOK_ABOUT, { front() }), Stand(-1, { front() }))
                            else h.set(Play(Activity.LOOK_ABOUT, { front() }), Stand(-1, { front() }))
                        }
                    }
                }
                Stage.LOOK -> {
                    if (!said && t % 30 == 10 && t < 300) said = Words.say(puller.p, if (back) "comings.home" else "comings.arrive", "come to their new door in a village")
                    if (t >= LOOK) {
                        now = Stage.WELCOME
                        next()
                        welcome()
                    }
                }
                Stage.WELCOME -> {
                    if (!replied && welcomed >= 1 && puller.waiting) {
                        replied = true
                        val first = crowd.firstOrNull()?.p
                        puller.set(
                            Play(Activity.BOW, { first?.let { eye(it) } }, 8) { line(it.p, "welcome_reply") },
                            Stand(-1, { first?.let { eye(it) } ?: front() }),
                        )
                    }
                    val done = crowd.isEmpty() && t >= NOBODY || crowd.isNotEmpty() && welcomed >= crowd.size && replied && t >= 120 && puller.waiting
                    if (done || t > WELCOME) {
                        // Their welcome said (or the time up), the neighbours wave and go back to their day
                        for (h in crowd.toList()) h.set(Play(Activity.WAVE, { lead?.p?.let { eye(it) } }))
                        now = Stage.CARRY
                        next()
                        for (h in household) h.set()
                    }
                }
                Stage.CARRY -> {
                    for (h in crowd.toList()) if (h.idle) drop(h)
                    for ((k, h) in household.withIndex()) if (h.idle) unload(h, k)
                    if (household.all { it.home } || t > WORK) return false
                }
            }
            return true
        }

        /** The door opened, and the neighbours called over, each with their part in turn. */
        private fun welcome() {
            val puller = lead ?: return
            opened = swing(level, door, true, puller.p) || opened
            val ids = household.map { it.p.resident }.toSet()
            val came = neighbours(level, key, at, ids)
            val faceThem = { _: Hand -> lead?.p?.let { eye(it) } }
            val centre = Vec3.atCenterOf(laneOf(level, door, 2))
            val from = Vec3.atCenterOf(door.pos.relative(door.inward.opposite, 8))
            for ((k, n) in came.withIndex()) {
                val h = hand(n, n.resident)
                crowd += h
                val spot = arc(level, centre, from, k, came.size, 2.8)
                val word: Act = when (k) {
                    0 -> Play(Activity.INTRODUCE, faceThem, 8) {
                        line(it.p, "welcome_1")
                        welcomed++
                    }
                    1 -> Play(Activity.INTRODUCE, faceThem, 8) {
                        line(it.p, "welcome_2")
                        welcomed++
                    }
                    else -> Play(Activity.BOW, faceThem, 8) {
                        line(it.p, "welcome_3")
                        welcomed++
                    }
                }
                h.set(
                    Play(Activity.WAVE, faceThem),
                    Go({ spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.9),
                    // In turn, the household's reply after the first
                    Until({ welcomed >= k && (k == 0 || replied) }, faceThem, 500),
                    Stand(10 + 45 * minOf(k, 1), faceThem),
                    word,
                    Stand(-1, faceThem),
                )
            }
        }

        /** [h] (the [k]th of them) at the door with things still on the cart: off with the next for them, and in with it. */
        private fun unload(h: Hand, k: Int) {
            if (h.home) return
            val puller = lead ?: return
            val room = roomSpot(level, home, door, k)
            if (!cart || Handcarts.parked(puller.p) == null) {
                h.set(
                    Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.6),
                    Go({ room }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.8),
                    Play(Activity.PUT_DOWN, { null }, 10) { it.p.carried = ItemStack.EMPTY },
                    Do { it.home = true },
                    Stand(-1, { null }),
                )
                return
            }
            val piece = nextPiece(h.p.role.adult)
            if (piece == null) {
                h.set(Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.6), Go({ room }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.8), Do { it.home = true }, Stand(-1, { null }))
                return
            }
            val from = Vec3.atCenterOf(door.pos)
            h.set(
                Go({ Handcarts.side(puller.p, from) ?: bay ?: laneOf(level, door, 2) }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.7),
                Play(Activity.PICK_UP, { Handcarts.bed(puller.p) }, 10) {
                    // Taken off the cart now: whatever suits them that's still there, what's on top first
                    val p = nextPiece(it.p.role.adult)
                    if (p != null) {
                        heaped.remove(p)
                        Handcarts.heap(puller.p, heaped)
                        it.p.carried = p.stack
                    }
                },
                Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.6),
                Go({ room }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.8),
                Play(Activity.PUT_DOWN, { null }, 10) { it.p.carried = ItemStack.EMPTY },
            )
        }

        /** The next piece off the cart for a grown-up (anything, the top first) or a child (a bundle). */
        private fun nextPiece(adult: Boolean): Handcarts.Placed? {
            val order = heaped.sortedByDescending { it.place }
            return if (adult) order.firstOrNull() else order.firstOrNull { it.stack.`is`(net.minecraft.world.item.Items.BUNDLE) }
        }

        override fun end(off: Boolean) {
            if (opened) swing(level, door, false, lead?.p)
            lead?.p?.let {
                Handcarts.headFor(it, null)
                Handcarts.leave(it)
            }
        }
    }

    // ---- A household gone ----

    /**
     * A household leaving of a morning with a player near: they gather at their door, their cart is brought round to its bay
     * while nobody is looking that way, and they fetch their goods out of the house a piece at a time and heap them on it
     * (HAND_OVER); the neighbours come out to see them off (WAVE, "comings.farewell"), "comings.leave", and away with the cart
     * along the way they would have come, toward the road's end, where they are taken away once nobody can see them.
     */
    class Departure(
        level: ServerLevel, key: Long, private val home: CityPlaces.Lodging, private val door: CityPlaces.Door, private val away: BlockPos,
    ) : Scene(level, key, "a household leaving") {
        private enum class Stage { GATHER, CART, PACK, FAREWELL, OUT }

        private var now = Stage.GATHER
        override val at: Vec3 = Vec3.atCenterOf(door.pos)
        override val stage: String get() = now.name.lowercase()

        private var bay: BlockPos? = null
        private var cart = false
        private var said = false
        private var legs = 0
        private val load = ArrayList<Handcarts.Placed>()
        private val heaped = ArrayList<Handcarts.Placed>()
        private val crowd = ArrayList<Hand>()
        private val household = ArrayList<Hand>()

        private val lead: Hand? get() = household.firstOrNull { it.p.role.adult } ?: household.firstOrNull()

        fun begin(bodies: List<Townsperson>) {
            for (p in bodies.sortedWith(compareBy({ if (it.role.adult) 0 else 1 }, { it.resident }))) household += hand(p, p.resident)
            if (household.isEmpty()) return
            gather()
            start(this)
        }

        private fun street(): Vec3 = top(door.pos.relative(door.inward.opposite, 6))

        private fun gather() {
            val puller = lead ?: return
            bay = if (Handcarts.enabled()) Handcarts.bay(level, door, puller.p) else null
            bay?.let { Handcarts.headFor(puller.p, it) }
            val lane = laneOf(level, door, 2)
            for ((k, h) in household.withIndex()) {
                val spot = if (h === puller) bay ?: lane else arc(level, Vec3.atCenterOf(lane), street(), k, household.size, 1.6)
                h.set(Go({ spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.9), Stand(-1, { top(door.pos) }))
            }
        }

        override fun step(): Boolean {
            household.removeIf { it !in crew }
            crowd.removeIf { it !in crew }
            val puller = lead ?: return false
            when (now) {
                Stage.GATHER -> if (household.all { it.waiting } || t > CART_WAIT) {
                    now = Stage.CART
                    next()
                }
                Stage.CART -> {
                    val b = bay
                    if (b == null) {
                        now = Stage.PACK
                        next()
                        pack()
                    } else if (t % 10 == 0 && Comings.unseen(level, b) || t > CART_WAIT) {
                        // Brought round while nobody was looking: behind them as they face the door, clear of it
                        puller.face(Vec3.atCenterOf(door.pos))
                        if (Handcarts.hitch(puller.p) != null) {
                            Handcarts.unhitch(puller.p)
                            Handcarts.load(puller.p, listOf())
                            cart = true
                        }
                        now = Stage.PACK
                        next()
                        pack()
                    }
                }
                Stage.PACK -> {
                    for ((k, h) in household.withIndex()) if (h.idle) packOne(h, k)
                    val cartThere = cart && Handcarts.parked(puller.p) != null
                    if ((load.isEmpty() || !cartThere) && household.all { it.waiting } || t > WORK) {
                        now = Stage.FAREWELL
                        next()
                        farewell()
                    }
                }
                Stage.FAREWELL -> {
                    if (!said && t % 30 == 10 && t < 300) said = Words.say(puller.p, "comings.leave", "leaving a village in hard times")
                    if (t >= (if (crowd.isEmpty()) 80 else 240)) {
                        now = Stage.OUT
                        next()
                        out()
                    }
                }
                Stage.OUT -> {
                    for (h in crowd.toList()) if (h.idle) drop(h)
                    if (puller.idle && legs++ < 6) {
                        // On away from the village while still watched, the rest after them
                        val on = onward(legs)
                        puller.set(Go({ on }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 1.8, most = 2400))
                    }
                    if (t % 10 == 0 && household.all { Comings.unseen(level, it.p.blockPosition()) }) return false
                }
            }
            return true
        }

        private fun pack() {
            load.clear()
            load.addAll(Handcarts.household(household.size))
            heaped.clear()
            for (h in household) h.set()
        }

        /** [h] (the [k]th of them) with things still to pack: into the house for the next, and out to the cart with it. */
        private fun packOne(h: Hand, k: Int) {
            if (h.waiting) return
            val puller = lead ?: return
            val piece = load.firstOrNull { h.p.role.adult || it.stack.`is`(net.minecraft.world.item.Items.BUNDLE) }
            val spot = arc(level, Vec3.atCenterOf(laneOf(level, door, 2)), street(), k, household.size, 1.6)
            if (piece == null || !cart || Handcarts.parked(puller.p) == null) {
                h.set(Go({ spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.9), Stand(-1, { street() }))
                return
            }
            load.remove(piece)
            h.piece = piece
            val room = roomSpot(level, home, door, k)
            val from = Vec3.atCenterOf(door.pos)
            h.set(
                Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.6),
                Go({ room }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.8),
                Play(Activity.PICK_UP, { null }, 10) { it.p.carried = piece.stack },
                Go({ door.inside }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.6),
                Go({ Handcarts.side(puller.p, from) ?: bay ?: spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.7),
                Play(Activity.HAND_OVER, { Handcarts.bed(puller.p) }, HANDOFF) {
                    heaped += piece
                    Handcarts.heap(puller.p, heaped.sortedBy { p -> p.place })
                    it.p.carried = ItemStack.EMPTY
                    it.piece = null
                },
            )
        }

        private fun farewell() {
            for (h in household) {
                // Cut short with a piece in their arms: it goes on all the same
                h.piece?.let { piece ->
                    if (piece !in heaped) heaped += piece
                    lead?.p?.let { Handcarts.heap(it, heaped.sortedBy { p -> p.place }) }
                    h.p.carried = ItemStack.EMPTY
                    h.piece = null
                }
                h.set(Stand(-1, { street() }))
            }
            val ids = household.map { it.p.resident }.toSet()
            val came = neighbours(level, key, at, ids, 2)
            val them = { _: Hand -> lead?.p?.let { eye(it) } }
            for ((k, n) in came.withIndex()) {
                val h = hand(n, n.resident)
                crowd += h
                val spot = arc(level, Vec3.atCenterOf(laneOf(level, door, 3)), street(), k, came.size, 2.8)
                h.set(
                    Go({ spot }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.9),
                    Play(Activity.WAVE, them, 6) { if (k == 0) Words.say(it.p, "comings.farewell", "seeing a household off from a village") },
                    Stand(-1, them),
                )
            }
        }

        private fun out() {
            val puller = lead ?: return
            val first = onward(0)
            if (cart && Handcarts.parked(puller.p) != null) {
                puller.set(
                    Go({ Handcarts.fetch(it.p) }, io.github.vaspyyy.guildmark.townsfolk.Jobs.WALK, 0.45, exact = true),
                    Do { Handcarts.hitch(it.p) },
                    Go({ first }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 1.8, most = 3600),
                )
            } else puller.set(Go({ first }, io.github.vaspyyy.guildmark.townsfolk.Jobs.STROLL, 1.8, most = 3600))
            household.filter { it !== puller }.forEachIndexed { k, h ->
                if (h.p.carried.isEmpty) h.set(Play(Activity.WAVE, { street() }), Trail(k, { lead?.p }, about = false)) else h.set(Trail(k, { lead?.p }, about = false))
            }
            for (h in crowd) h.set(Play(Activity.WAVE, { lead?.p?.let { eye(it) } }), Stand(60, { lead?.p?.let { eye(it) } }), Play(Activity.WAVE, { lead?.p?.let { eye(it) } }))
        }

        /** The [n]th leg's end: the road's end, then on past it the same way. */
        private fun onward(n: Int): BlockPos {
            if (n == 0) return away
            val from = Vec3.atCenterOf(door.pos)
            val dir = Vec3.atCenterOf(away).subtract(from).let { Vec3(it.x, 0.0, it.z).normalize() }
            val at = BlockPos.containing(Vec3.atCenterOf(away).add(dir.scale(16.0 * n)))
            return VillageGrowth.ground(level, at) ?: away
        }

        override fun end(off: Boolean) {
            lead?.p?.let {
                Handcarts.headFor(it, null)
                Handcarts.leave(it)
            }
            // The household is gone: out of sight they go now, in sight they stand until nobody can see them
            for (h in household) {
                crew.remove(h)
                val p = h.p
                p.swaddled = false
                p.carried = ItemStack.EMPTY
                p.navigation.stop()
                if (Comings.unseen(level, p.blockPosition())) {
                    p.discard()
                } else {
                    if (p.activity.loop || p.activity.overlay || p.activity == Handcarts.PULL) p.act(Activity.NONE)
                    p.director = Still
                }
            }
            household.clear()
        }
    }
}
