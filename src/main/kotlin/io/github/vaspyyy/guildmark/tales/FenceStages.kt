package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Await
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Stand
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * The scenes of the Fence Between (living-world/stories/design.md 8.5), Tales T1a: the Quarrel in the lane (S3, with the third's words), and the
 * four dusks at the well, each made from the sets' parts (`Sets.quarrel`, `Sets.beside`, `Sets.freeAdults`, the handshake's measured place) but
 * with the well's own geometry, which the sets' general handing does not have: the two sit on the well's step to eat and are got up again.
 * They are played by `TaleLook` for the tale at its steps (the Quarrel at step 0, the Mending or what is left of it at step 3) and, once, for
 * the tale that has just ended by a command or by the world's way with a player near; `tale scene mending|nod|waited|fence` plays any by hand.
 *
 * - **The Quarrel (step 0):** `Sets.quarrel`, from 11:00 to 15:00: A shouts (`tale.fence.shout_a`, the thing in grey), B folds their arms and
 *   waves it off and answers (`tale.fence.shout_b`), the third comes up and shushes them in words (`tale.fence.third`), two watchers look on.
 * - **A, the Mending:** at dusk a waits at the well with the loaf, b comes up, a says they were wrong and hands the loaf over (HAND_OVER, RECEIVE),
 *   b says the same, they shake hands a block apart (SHAKE_HANDS on the same tick), sit on the well's step and eat; the third raises a cup
 *   (`tale.set.toast`) and the watchers clap and cheer, petals fall.
 * - **B, the nod:** the same meeting with no bread and no words: they stand a block apart, nod, and each goes home; the third nods too.
 * - **C, the wait:** the one who agreed goes to the well at dusk, waits (LOOK_ABOUT, SIGH) with an eye on the other's door, shrugs
 *   (`tale.fence.glum`) and goes home.
 * - **W:** none. The lane goes quiet.
 */
internal object FenceStages {
    /** The hours the quarrel is played in (the lane, noon), and the dusk the endings are played at. */
    val NOON: ClosedFloatingPointRange<Double> = 11.0..15.0
    val DUSK: ClosedFloatingPointRange<Double> = 17.0..19.5

    /** How far from the well the two who look on are drawn from, and how far from it a step to sit on is looked for (blocks). */
    private const val WATCH_REACH = 20.0
    private const val STEP_REACH = 6

    /** How far a quarrel's two may be from each other for the lane to be the place of it (blocks); farther, the scene waits for them to be near. */
    const val APART = 32.0

    /** The people and the place of one scene. [site] is the well, or the middle of the lane if there is none. */
    class Folk(
        val level: ServerLevel,
        val a: Townsperson,
        val b: Townsperson,
        val third: Townsperson?,
        val site: BlockPos,
        val thing: Int,
    ) {
        val centre: Vec3 get() = Vec3.atCenterOf(site)
    }

    // ---- The pieces ----

    private fun walker(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves, seated = body.seat != null)

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    /** The word for the thing they quarrel about, as a grey detail's argument: the language's own key. */
    fun thingKey(thing: Int): String = "say.guildmark.talk.detail.tale.fence.thing.${thing.coerceIn(0, THINGS - 1)}"

    /** The seven things the lane falls out over (`tale.fence.thing.0` to `.6`). */
    const val THINGS = 7

    /** Up to [n] free people of the street near the well, other than the cast, who come to look on. */
    private fun crowd(f: Folk, n: Int): List<Townsperson> =
        Sets.freeAdults(f.level, f.centre, WATCH_REACH, listOfNotNull(f.a, f.b, f.third)).take(n)

    /** The flat unit step from [from] towards [to] (east if they are the same place). */
    private fun heading(from: Vec3, to: Vec3): Vec3 {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val len = sqrt(dx * dx + dz * dz)
        return if (len < 0.1) Vec3(1.0, 0.0, 0.0) else Vec3(dx / len, 0.0, dz / len)
    }

    /** The place exactly [gap] blocks from [of] on the line towards [from], level with [from]: where the handshake is made. */
    private fun apart(of: Townsperson, from: Townsperson, gap: Double): Vec3 {
        val d = heading(of.position(), from.position())
        return Vec3(of.x + d.x * gap, from.y, of.z + d.z * gap)
    }

    /**
     * The steps by the well to sit on, nearest first: stairs of the town's own (`Places.isSeat`) within [STEP_REACH] blocks of the site. Looked
     * for once, as the scene is made, never while it plays.
     */
    private fun steps(f: Folk): List<BlockPos> {
        val level = f.level
        return Places.all(level, f.site, STEP_REACH, 2, 24) { state, pos -> Places.isSeat(level, state, pos) }.sortedBy { it.distSqr(f.site) }
    }

    /** Sits down on the step at [seat] (SIT_DOWN lowers them onto it), if it is still a seat and nobody else has it. */
    private class Sit(private val seat: BlockPos) : Move() {
        private var sat = false

        override fun start(p: Townsperson, a: Aside) {
            val level = p.level() as ServerLevel
            if (!Places.isSeat(level, level.getBlockState(seat), seat) || !Claims.claim(level, seat, p, 900)) return
            sat = true
            p.navigation.stop()
            p.sitOn(seat)
            p.act(Activity.SIT_DOWN)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean = sat && t < Activity.SIT_DOWN.ticks
    }

    /** Gets up off the step (STAND_UP, then onto the floor in front of it), if they are on one. */
    private class Up : Move() {
        private var getting = false

        override fun start(p: Townsperson, a: Aside) {
            getting = false
            if (p.seat != null) {
                getting = true
                p.act(Activity.STAND_UP)
            }
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean = getting && t < Activity.STAND_UP.ticks

        override fun stop(p: Townsperson) {
            if (p.seat != null) p.standUp()
        }
    }

    /** Eats the loaf for [ticks], sitting if they are on a step. */
    private class Eat(private val ticks: Int, private val at: () -> Vec3?) : Move() {
        private fun clip(p: Townsperson): Activity = if (p.seat != null) Activity.SIT_EAT else Activity.EAT

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(clip(p))
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            at()?.let { p.lookAt(it) }
            val clip = clip(p)
            if (p.activity != clip && (p.activity.loop || p.actDone)) p.act(clip)
            return t < ticks
        }
    }

    // ---- The Quarrel ----

    /** S3 The Quarrel in the lane (4.3), from 11:00 to 15:00: A shouts about the thing, B answers, the third shushes in words, two of the street look on. Null if the two are far apart or the set has no room. */
    fun quarrel(f: Folk): Stage? {
        if (f.a.distanceToSqr(f.b) > APART * APART) return null
        val between = Vec3((f.a.x + f.b.x) / 2.0, (f.a.y + f.b.y) / 2.0, (f.a.z + f.b.z) / 2.0)
        val watchers = Sets.freeAdults(f.level, between, WATCH_REACH, listOfNotNull(f.a, f.b, f.third)).take(2)
        return Sets.quarrel(
            f.a, f.b, f.third, watchers,
            shout = Sets.Line("tale.fence.shout_a", TaleLines.facts("talk.detail.tale.fence.shout", thingKey(f.thing))),
            retort = Sets.Line("tale.fence.shout_b"),
            hush = Sets.Line("tale.fence.third"),
            hours = NOON,
        )
    }

    // ---- The Mending ----

    /**
     * The ending at the well: [bread] is the Mending proper (A), without it the nod (B). Both meet a block apart, in the measured place of
     * the handshake; with bread there are words, a handing, a handshake, a seat on the well's step, a toast and a clap.
     */
    fun mending(f: Folk, bread: Boolean): Stage? {
        val level = f.level
        val at = f.centre
        val spotA = Sets.beside(level, at, f.a.position(), 2.2) ?: return null
        val aHere = Vec3.atCenterOf(spotA)
        val meet = Sets.beside(level, aHere, f.b.position(), 1.4) ?: return null
        val loaf = ItemStack(Items.BREAD)
        val seats = if (bread) steps(f) else listOf()
        val seatA = seats.firstOrNull()
        val seatB = seatA?.let { first -> seats.drop(1).firstOrNull { it.distSqr(first) <= 9.0 } }
        val a = ArrayList<Move>()
        val b = ArrayList<Move>()
        // a is at the well first, with the bread in hand; b comes up from their side of the lane
        if (bread) a += Hold(loaf)
        a += GoTo({ spotA }, Jobs.WALK, near = 0.6, overlay = if (bread) Activity.CARRY else Activity.NONE, most = 800)
        a += Turn(eye(f.b))
        a += Barrier("here", most = 1200, at = eye(f.b))
        b += GoTo({ meet }, Jobs.WALK, near = 0.8, most = 800)
        b += Turn(eye(f.a))
        b += Barrier("here", most = 1200, at = eye(f.a))
        // A block apart, middle to middle: where the clips of the handshake are made to be played
        b += Stand({ apart(f.a, f.b, 1.0) }, eye(f.a))
        if (bread) {
            // a says they were wrong (b listens), hands the loaf over (b takes it), and b says the same (a listens)
            a += Say("tale.fence.sorry", gesture = Activity.NONE, at = eye(f.b))
            a += Barrier("said", most = 600, at = eye(f.b))
            a += Beat(Activity.HAND_OVER, at = eye(f.b)) { p, tick -> if (tick == 16) Stage.give(p, null) }
            a += Barrier("handed", most = 120, at = eye(f.b))
            a += Barrier("replied", most = 600, pose = Activity.LISTEN, at = eye(f.b))
            b += Barrier("said", most = 600, pose = Activity.LISTEN, at = eye(f.a))
            b += Beat(Activity.RECEIVE, at = eye(f.a)) { p, tick -> if (tick == 12) Stage.give(p, loaf.copy()) }
            b += Barrier("handed", most = 120, at = eye(f.a))
            b += Say("tale.fence.same", gesture = Activity.NONE, at = eye(f.a))
            b += Barrier("replied", most = 600, at = eye(f.a))
            // They shake hands on the same tick
            a += Barrier("clasp", most = 300, at = eye(f.b))
            a += Play(Activity.SHAKE_HANDS, at = eye(f.b))
            b += Barrier("clasp", most = 300, at = eye(f.a))
            b += Play(Activity.SHAKE_HANDS, at = eye(f.a))
            // To the well's step, a loaf each, and a bite
            a += Hold(loaf.copy())
            if (seatA != null) {
                a += GoTo({ Places.access(level, seatA) }, Jobs.WALK, near = 0.6, most = 300)
                a += Sit(seatA)
            }
            a += Barrier("seated", most = 600, pose = if (seatA != null) Activity.SIT_EAT else Activity.EAT, at = eye(f.b))
            a += Eat(80, eye(f.b))
            a += Barrier("toasted", most = 900, pose = if (seatA != null) Activity.SIT_EAT else Activity.EAT, at = eye(f.b))
            a += Play(Activity.LAUGH)
            a += Hold(ItemStack.EMPTY)
            a += Up()
            if (seatB != null) {
                b += GoTo({ Places.access(level, seatB) }, Jobs.WALK, near = 0.6, most = 300)
                b += Sit(seatB)
            }
            b += Barrier("seated", most = 600, pose = if (seatB != null) Activity.SIT_EAT else Activity.EAT, at = eye(f.a))
            b += Eat(80, eye(f.a))
            b += Barrier("toasted", most = 900, pose = if (seatB != null) Activity.SIT_EAT else Activity.EAT, at = eye(f.a))
            b += Play(Activity.LAUGH)
            b += Hold(ItemStack.EMPTY)
            b += Up()
        } else {
            // No bread, no words: a nod at the well, and each goes home
            a += Barrier("nod", most = 300, at = eye(f.b))
            a += Play(Activity.NOD, at = eye(f.b))
            b += Barrier("nod", most = 300, at = eye(f.a))
            b += Play(Activity.NOD, at = eye(f.a))
        }
        val parts = ArrayList<Stage.Part>()
        parts += walker("a", f.a, a)
        parts += walker("b", f.b, b)
        f.third?.let { third ->
            val t = ArrayList<Move>()
            val watch = Sets.beside(level, at, third.position(), 4.0)
            if (watch != null) t += GoTo({ watch }, Jobs.STROLL, near = 1.0, most = 800)
            t += Turn(eye(f.a))
            if (bread) {
                // They watch the pair shake hands and sit, and raise a cup to them
                t += Await("clasp", 1800, Activity.GAZE, eye(f.a))
                t += Await("seated", 900, Activity.GAZE, eye(f.a))
                t += Turn({ at })
                t += Beat(Activity.TOAST, at = { at }) { p, tick -> if (tick == 4) Words.sayWith(p, "tale.set.toast", why = "a toast") }
                t += Wait({ p, _ -> p.speech.isEmpty() }, most = 120, at = { at })
                t += Fx(Fx.Kind.HAPPY, { p -> p.position().add(0.0, 1.9, 0.0) })
                t += Barrier("toasted", most = 300)
                t += Play(Activity.NOD, at = { at })
            } else {
                t += Await("nod", 1800, Activity.GAZE, eye(f.a))
                t += Play(Activity.NOD, at = { at })
            }
            parts += walker("third", third, t, essential = false)
        }
        // Two of the street come up and look on; with bread they clap and cheer when the cup is raised
        val extras = ArrayList<Stage.Part>()
        for ((i, who) in crowd(f, 2).withIndex()) {
            val m = ArrayList<Move>()
            Sets.beside(level, at, who.position(), 5.0 + i)?.let { spot -> m += GoTo({ spot }, Jobs.STROLL, near = 1.0, most = 600) }
            m += Turn(eye(f.a))
            if (bread) {
                m += Await("seated", 2400, Activity.GAZE, eye(f.a))
                m += Await("toasted", 900, Activity.GAZE, eye(f.a))
                m += Wait({ _, tick -> tick >= 5 * (i + 1) }, most = 30)
                m += Play(Activity.CLAP)
                m += Play(Activity.CHEER)
            } else {
                m += Await("nod", 2400, Activity.GAZE, eye(f.a))
                m += Play(Activity.NOD)
            }
            extras += walker("watcher${i + 1}", who, m, essential = false)
        }
        return Stage(if (bread) "mending" else "nod", parts, DUSK, f.centre, extras = extras)
    }

    // ---- C, the wait ----

    /** The one who agreed waits at the well at dusk, with an eye on the other's door, shrugs, and goes home. [other] is the one who did not come. */
    fun waited(f: Folk, waiter: Townsperson, other: Townsperson): Stage? {
        val level = f.level
        val spot = Sets.beside(level, f.centre, waiter.position(), 2.2) ?: return null
        val door = waiter.home?.let { Sets.standable(level, it) }
        val m = ArrayList<Move>()
        m += GoTo({ spot }, Jobs.WALK, near = 0.6, most = 800)
        m += Turn(eye(other))
        m += Wait({ _, _ -> false }, most = 100, pose = Activity.ARMS_FOLDED, at = eye(other))
        m += Play(Activity.LOOK_ABOUT)
        m += Wait({ _, _ -> false }, most = 120, pose = Activity.ARMS_FOLDED, at = eye(other))
        m += Play(Activity.SIGH)
        m += Say("tale.fence.glum", gesture = Activity.SHRUG, at = eye(other))
        if (door != null) m += GoTo({ door }, Jobs.WALK, near = 2.0, most = 800)
        return Stage("waited", listOf(walker("waiter", waiter, m)), DUSK, f.centre)
    }

    // ---- tale scene mending|nod|waited|fence ----

    /**
     * Adds the fence's scenes to `tale scene` (one line in [NoteStall.register]): `mending` the dusk at the well with bread, `nod` the one
     * without, `waited` the one who waits alone, `fence` the quarrel in the lane. They are played near you, whatever the switches say, with
     * the nearest free adults, and nothing is written to any tale.
     */
    fun register() {
        TaleSceneCommand.add("mending") { level, player -> manual(level, player, Mode.MENDING) }
        TaleSceneCommand.add("nod") { level, player -> manual(level, player, Mode.NOD) }
        TaleSceneCommand.add("waited") { level, player -> manual(level, player, Mode.WAITED) }
        TaleSceneCommand.add("fence") { level, player -> manual(level, player, Mode.QUARREL) }
    }

    private enum class Mode { MENDING, NOD, WAITED, QUARREL }

    private fun manual(level: ServerLevel, player: ServerPlayer, mode: Mode): String {
        Capitals.covering(level, player.blockPosition()) ?: throw TaleSceneCommand.Unplayable("You are not in a capital.")
        val around = player.position()
        val people = Sets.freeAdults(level, around, 24.0)
        if (people.size < 2) throw TaleSceneCommand.Unplayable("Fewer than two free adults within 24 blocks of you.")
        val a = people[0]
        val b = people[1]
        val third = people.getOrNull(2)
        val site = Sets.standable(level, player.blockPosition()) ?: throw TaleSceneCommand.Unplayable("Nowhere to stand here.")
        val f = Folk(level, a, b, third, site, level.random.nextInt(THINGS))
        val stage = when (mode) {
            Mode.MENDING -> mending(f, bread = true)
            Mode.NOD -> mending(f, bread = false)
            Mode.WAITED -> waited(f, a, b)
            Mode.QUARREL -> quarrel(f)
        } ?: throw TaleSceneCommand.Unplayable("Nowhere to play it here.")
        val names = "${TaleSceneCommand.name(a)} and ${TaleSceneCommand.name(b)}"
        return TaleSceneCommand.begin(
            level, stage,
            when (mode) {
                Mode.MENDING -> "$names make it up at your feet, with bread."
                Mode.NOD -> "$names nod to each other at your feet."
                Mode.WAITED -> "${TaleSceneCommand.name(a)} waits at your feet for ${TaleSceneCommand.name(b)}, who does not come."
                Mode.QUARREL -> "$names fall out over thing number ${f.thing}."
            },
        )
    }
}
