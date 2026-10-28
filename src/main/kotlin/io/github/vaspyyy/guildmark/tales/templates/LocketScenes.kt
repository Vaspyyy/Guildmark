package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Await
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Rise
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Stand
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * What the Locket shows (living-world/stories/design.md 8.11, 4.3): the owner on their doorstep wringing their hands (S1's variant, the opener,
 * and the same doorstep without the passer-by while the words are gathered), the return of the locket in the three ways it can go home, and the
 * owner who has stopped looking. Each is a [Stage] made of the people the record names or, for `tale scene`, of whoever is free near the one who
 * ran the command; the tale's ask ([Locket.scene]) wraps them with the checks the record makes. Every body walks at its own pace to where it plays
 * and goes back to its day after: nobody is made to appear.
 *
 *  - **doorstep** (steps 0 to 2, by day): the owner walks to their doorstep and stands on it looking down the lane, wringing their hands (a
 *    standing clip: never played seated), sighing and looking about; at the opener a passer-by comes up with a word of comfort, the owner says
 *    what the locket held and waves the comfort off.
 *  - **the Return** (A, at dusk, step 4): the culprit comes to the owner's door with the locket in both hands, holds it out and bows; the owner
 *    receives it, holds it to their chest and says their word; they embrace and the owner forgives the hungry neighbour; the street gazes and nods.
 *  - **the Clasp** (B, as the tale ends): the owner takes the locket from the player's word, holds it to their chest, the villagers' green about
 *    them; the culprit watches from the corner and goes home.
 *  - **the Naming** (C, by day, step 4): at the well, the culprit holds the locket out, the owner takes it, folds their arms and says it was not
 *    wanted so; the watchers (the watch first) gaze, and grumble.
 *  - **lost** (W, at dusk): the owner stands on their step with their face in their hands and says they have stopped looking.
 *
 * The locket in a hand is the drawn `tale_prop` of the look `locket` ([Sets.prop]); it is never an item that a pack can hold.
 */
object LocketScenes {
    /** The hours of the doorstep (the owner is out by day), of the dusk scenes, of the street scene at the well, and of the Clasp. */
    val DAY: ClosedFloatingPointRange<Double> = 10.0..18.0
    val DUSK: ClosedFloatingPointRange<Double> = 17.0..21.0
    val STREET: ClosedFloatingPointRange<Double> = 9.0..20.0
    val CLASP: ClosedFloatingPointRange<Double> = 7.0..22.0

    /** How far from their door the owner may be for a scene to be asked (they walk the rest), and how far the culprit may be to come to them. */
    private const val OWNER_FAR = 90.0
    private const val CULPRIT_FAR = 120.0

    /** How near their door the owner must be for the Clasp to be played on the doorstep (else it is played where they stand). */
    private const val CLASP_DOOR = 24.0

    /** How near a watcher must be to be one, how many, and how near the player who gave the locket back must be to be turned to. */
    private const val CROWD_NEAR = 26.0
    private const val WATCHERS = 2
    private const val PLAYER_NEAR = 48.0

    /** How far down the lane the owner looks, how far from the doorstep the culprit waits (a corner), and the gaps of a handing and of a hug. */
    private const val LANE = 7.0
    private const val CORNER = 6.0
    private const val HANDING = 1.2
    private const val HUG = 1.1

    /** The tick of HAND_OVER the goods leave the hand, and of RECEIVE they show in the other (13.2.3). */
    private const val HANDED_AT = 16

    private val LOCKET get() = Sets.prop("locket")

    // ---- Making the parts ----

    /** A part for someone who stays where they stand or sit: a worker at their post, or someone seated, is played in place. */
    private fun still(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves, station = body.seat != null || body.routine.anchored, seated = body.seat != null)

    /** A part for someone who walks about in it. */
    private fun walker(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves)

    /** A part for someone who walks, but who may be seated or at their post when the stage takes them: they get up, or set the work down, first. */
    private fun rising(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        if (body.seat != null || body.routine.anchored) still(slot, body, listOf<Move>(Rise()) + moves, essential) else walker(slot, body, moves, essential)

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    /** A wait of [ticks] in [pose]. */
    private fun pause(ticks: Int, pose: Activity = Activity.NONE): Move = Wait({ _, t -> t >= ticks }, most = ticks + 1, pose = pose)

    private fun joy(level: ServerLevel, p: Townsperson) = Fx.play(level, Fx.Kind.HAPPY, p.position().add(0.0, 1.5, 0.0))

    /** The spot [gap] blocks from [of] on the side [from] is on, at [of]'s height: where someone stands to hand a thing over or to hug. */
    private fun before(of: Townsperson, from: Townsperson, gap: Double): Vec3 {
        val o = of.position()
        var dx = from.x - o.x
        var dz = from.z - o.z
        val len = Math.sqrt(dx * dx + dz * dz)
        if (len < 1.0e-3) {
            dx = 0.0
            dz = 1.0
        } else {
            dx /= len
            dz /= len
        }
        return Vec3(o.x + dx * gap, o.y, o.z + dz * gap)
    }

    // ---- Where a doorstep scene is played ----

    /** Where a scene at a door is played: the [spot] on the doorstep and the [look] down the lane, away from the door, at eye height. */
    class Step(val spot: BlockPos, val look: Vec3) {
        val feet: Vec3 get() = Vec3.atBottomCenterOf(spot)
    }

    /** The doorstep of [door]: the block outside it, and the lane it opens on. Null if there is nowhere to stand. */
    fun stepOf(level: ServerLevel, door: CityPlaces.Door): Step? {
        val spot = Sets.standable(level, door.outside) ?: return null
        val out = door.inward.opposite
        return Step(spot, Vec3(spot.x + 0.5 + out.stepX * LANE, spot.y + 1.6, spot.z + 0.5 + out.stepZ * LANE))
    }

    /** A step [ahead] blocks in front of [player], looking back at them: where a scene played by command is set. Null if there is nowhere to stand. */
    fun stepBefore(level: ServerLevel, player: ServerPlayer, ahead: Double = 3.0): Step? {
        val view = player.lookAngle
        val flat = Vec3(view.x, 0.0, view.z).let { if (it.lengthSqr() < 1.0e-4) Vec3(0.0, 0.0, 1.0) else it.normalize() }
        val at = player.position().add(flat.scale(ahead))
        val spot = Sets.standable(level, BlockPos.containing(at)) ?: return null
        return Step(spot, player.eyePosition)
    }

    /** The doorstep of the home of the person in [slot] of [t], or null if the record or the places do not have one. */
    internal fun doorstepOf(c: Ctx, t: Tale, slot: String): Step? {
        val r = c.resident(t, slot) ?: return null
        val door = c.here.places.lodgings[r.home]?.front ?: return null
        return stepOf(c.level, door)
    }

    // ---- The doorstep (S1, a variant) ----

    /**
     * The owner walks to their doorstep and settles on it, turned to the lane: LOOK_ABOUT, WRING_HANDS, SIGH. If [friend] is given (the opener),
     * the friend walks up (the walk takes as long as it takes, and the owner waits for them), says a word of comfort (`tale.set.comfort`); the owner
     * turns to them, says what the locket held (`tale.locket.passing`), waves the comfort off, sighs, and turns back to the lane wringing their
     * hands. About a minute with the friend, twenty seconds without. Never seated: the clip is drawn standing.
     */
    fun doorstep(owner: Townsperson, friend: Townsperson?, at: Step, hours: ClosedFloatingPointRange<Double>? = DAY): Stage {
        val level = owner.level() as ServerLevel
        val look = { at.look }
        val k = ArrayList<Move>()
        k += GoTo({ at.spot }, Jobs.WALK, near = 0.8, most = 1500)
        k += Stand({ at.feet }, toward = look)
        k += Play(Activity.LOOK_ABOUT, at = look)
        k += Play(Activity.WRING_HANDS, 120, at = look)
        k += Play(Activity.SIGH, at = look)
        val parts = ArrayList<Stage.Part>()
        val spot = friend?.let { Sets.beside(level, at.feet, it.position(), 1.6) }
        if (friend != null && spot != null) {
            // About eight ticks a block at a stroll, and a breath for the owner's first moves
            val meet = (500 + 10 * friend.distanceTo(owner)).toInt().coerceAtMost(1400)
            k += Barrier("meet", most = meet, pose = Activity.WRING_HANDS, at = look)
            k += Turn(eye(friend))
            k += Barrier("said", most = 400, pose = Activity.LISTEN, at = eye(friend))
            k += Say("tale.locket.passing", gesture = Activity.SIGH, at = eye(friend))
            k += Barrier("told", most = 300, at = eye(friend))
            k += Play(Activity.WAVE_OFF, at = eye(friend))
            k += Turn(look)
            k += Play(Activity.WRING_HANDS, 60, at = look)
            val f = ArrayList<Move>()
            f += GoTo({ spot }, Jobs.STROLL, near = 1.2, most = meet)
            f += Turn(eye(owner))
            f += Barrier("meet", most = meet, at = eye(owner))
            f += Play(Activity.NOD, at = eye(owner))
            f += Say("tale.set.comfort", gesture = Activity.TALK, at = eye(owner))
            f += Barrier("said", most = 400, at = eye(owner))
            f += Barrier("told", most = 300, pose = Activity.LISTEN, at = eye(owner))
            f += Play(Activity.SIGH, at = eye(owner))
            parts += walker("friend", friend, f, essential = false)
        } else {
            k += pause(40)
            k += Play(Activity.LOOK_ABOUT, at = look)
        }
        parts.add(0, rising("owner", owner, k))
        return Stage("locket_doorstep", parts, hours, at.feet, most = 3000)
    }

    /** The doorstep of the tale [t] by day: the owner if they are within reach of their door, and at the opener a passer-by to comfort them. */
    internal fun doorstepFor(c: Ctx, t: Tale, comfort: Boolean): Stage? {
        val level = c.level
        if (!Stage.within(level, DAY)) return null
        val owner = TaleBodies.body(level, t, LocketCast.OWNER) ?: return null
        val at = doorstepOf(c, t, LocketCast.OWNER) ?: return null
        if (owner.position().distanceToSqr(at.feet) > OWNER_FAR * OWNER_FAR) return null
        val cast = t.cast.mapNotNull { TaleBodies.body(level, t, it.slot) }
        val friend = if (comfort) Sets.passerBy(owner, cast) else null
        return doorstep(owner, friend, at)
    }

    // ---- The Return (A) ----

    /**
     * The Return (S4, a variant): the culprit carries the locket in both hands to a corner of the owner's lane while the owner comes out onto the
     * doorstep; when both are there the culprit walks up, holds it out (HAND_OVER) and bows. The owner RECEIVEs it, holds it to their chest
     * (THANK) and says `tale.locket.returned`, then tucks it away; they embrace (both EMBRACE, the owner forgiving the hungry neighbour with
     * `tale.locket.forgive`, the villagers' green about them) and the owner nods. The [watchers] (the first two) gaze and nod. When the owner has
     * nodded, `played[0]` is set. About a minute and a half. Null if there is no corner to wait at.
     */
    fun homecoming(owner: Townsperson, culprit: Townsperson, at: Step, watchers: List<Townsperson>, hours: ClosedFloatingPointRange<Double>?, played: BooleanArray): Stage? {
        val level = owner.level() as ServerLevel
        val corner = Sets.beside(level, at.feet, culprit.position(), CORNER) ?: return null
        val locket = LOCKET
        val them = eye(culprit)
        val o = ArrayList<Move>()
        o += GoTo({ at.spot }, Jobs.WALK, near = 0.8, most = 1500)
        o += Stand({ at.feet }, toward = { at.look })
        o += Barrier("ready", most = 1800, pose = Activity.LOOK_ABOUT, at = { at.look })
        o += Turn(them)
        o += Barrier("hand", most = 900, at = them)
        o += Beat(Activity.RECEIVE, at = them) { p, tick -> if (tick == HANDED_AT) Stage.give(p, locket) }
        o += Barrier("handed", most = 120, at = them)
        o += Say("tale.locket.returned", gesture = Activity.THANK, at = them)
        o += Hold(ItemStack.EMPTY)
        o += Barrier("hug", most = 400, at = them)
        var forgave = false
        o += Beat(Activity.EMBRACE, at = them) { p, tick ->
            // The owner's word comes once the culprit's arms are about them
            if (!forgave && tick >= 20) forgave = Words.sayWith(p, "tale.locket.forgive", why = "forgiving a hungry neighbour") != null
            if (tick % 12 == 6) joy(level, p)
        }
        o += Barrier("hugged", most = 200, at = them)
        o += Play(Activity.NOD, at = them)
        o += Now { played[0] = true }
        val c = ArrayList<Move>()
        val her = eye(owner)
        c += Hold(locket)
        c += GoTo({ corner }, Jobs.WALK, near = 1.5, overlay = Activity.CARRY, most = 1500)
        c += Turn(her)
        c += Barrier("ready", most = 1800, pose = Activity.CARRY, at = her)
        c += GoTo({ Sets.standable(level, BlockPos.containing(before(owner, culprit, HANDING))) }, Jobs.STROLL, near = 0.9, overlay = Activity.CARRY, most = 500)
        c += Stand({ before(owner, culprit, HANDING) }, toward = her, overlay = Activity.CARRY)
        c += Barrier("hand", most = 300, pose = Activity.CARRY, at = her)
        c += Beat(Activity.HAND_OVER, at = her) { p, tick -> if (tick == HANDED_AT) Stage.give(p, null) }
        c += Barrier("handed", most = 120, at = her)
        c += Play(Activity.BOW, at = her)
        c += Stand({ before(owner, culprit, HUG) }, toward = her)
        c += Barrier("hug", most = 600, at = her)
        c += Beat(Activity.EMBRACE, at = her) { p, tick -> if (tick % 12 == 0) joy(level, p) }
        c += Barrier("hugged", most = 200, at = her)
        c += Play(Activity.NOD, at = her)
        val parts = listOf(rising("owner", owner, o), rising("culprit", culprit, c))
        return Stage("locket_return", parts, hours, at.feet, most = 3600, extras = onlookers(watchers, listOf(owner, culprit), eye(owner), "hugged", Activity.NOD))
    }

    /** The street that looks on: up to two of [watchers] turn to [at] and gaze until the barrier [until] has passed, then play [after]. */
    private fun onlookers(watchers: List<Townsperson>, except: List<Townsperson>, at: () -> Vec3?, until: String, after: Activity): List<Stage.Part> =
        watchers.filter { it !in except }.take(WATCHERS).mapIndexed { i, w ->
            val m = ArrayList<Move>()
            m += Turn(at)
            m += Await(until, 3000, Activity.GAZE, at)
            m += Wait({ _, t -> t >= 6 * i }, most = 30)
            m += Play(after, at = at)
            walker("watcher${i + 1}", w, m, essential = false)
        }

    /** The Return of the tale [t] at dusk (an A): the culprit and the owner of the record, and the free adults about the owner's door for the street. */
    internal fun homecomingFor(c: Ctx, t: Tale, played: BooleanArray): Stage? {
        val level = c.level
        if (!Stage.within(level, DUSK)) return null
        val owner = TaleBodies.body(level, t, LocketCast.OWNER) ?: return null
        val culprit = TaleBodies.body(level, t, LocketCast.CULPRIT) ?: return null
        val at = doorstepOf(c, t, LocketCast.OWNER) ?: return null
        if (owner.position().distanceToSqr(at.feet) > OWNER_FAR * OWNER_FAR) return null
        if (culprit.position().distanceToSqr(at.feet) > CULPRIT_FAR * CULPRIT_FAR) return null
        val street = Sets.freeAdults(level, at.feet, CROWD_NEAR, listOf(owner, culprit))
        return homecoming(owner, culprit, at, street, DUSK, played)
    }

    // ---- The Clasp (B) ----

    /**
     * The Clasp: the owner comes out onto [at] and turns to [player] (else down the lane), who has told them the locket was found: they RECEIVE it,
     * hold it to their chest (THANK) with the villagers' green about them, say `tale.locket.returned`, and sigh. The culprit, if they are about,
     * goes to a corner of the lane and watches (LOOKOUT) until the owner has said their word, then turns for home. About forty seconds.
     */
    fun clasp(owner: Townsperson, culprit: Townsperson?, at: Step, player: ServerPlayer?, hours: ClosedFloatingPointRange<Double>?): Stage {
        val level = owner.level() as ServerLevel
        val locket = LOCKET
        val you = { player?.takeIf { it.isAlive }?.eyePosition ?: at.look }
        val o = ArrayList<Move>()
        o += GoTo({ at.spot }, Jobs.WALK, near = 0.8, most = 900)
        o += Stand({ at.feet }, toward = you)
        o += Turn(you)
        o += Beat(Activity.RECEIVE, at = you) { p, tick -> if (tick == HANDED_AT) Stage.give(p, locket) }
        o += Beat(Activity.THANK, at = you) { p, tick -> if (tick == 6 || tick == 18) joy(level, p) }
        o += Say("tale.locket.returned", at = you)
        o += Barrier("thanked", most = 200, at = you)
        o += Play(Activity.SIGH, at = you)
        o += Hold(ItemStack.EMPTY)
        val parts = ArrayList<Stage.Part>()
        parts += rising("owner", owner, o)
        val corner = culprit?.let { Sets.beside(level, at.feet, it.position(), CORNER + 2.0) }
        if (culprit != null && corner != null) {
            val home = culprit.home?.let { Sets.standable(level, it) }
            val k = ArrayList<Move>()
            k += GoTo({ corner }, Jobs.STROLL, near = 1.5, most = 1200)
            k += Turn(eye(owner))
            k += Barrier("thanked", most = 2400, pose = Activity.LOOKOUT, at = eye(owner))
            k += Play(Activity.SIGH, at = eye(owner))
            if (home != null) k += GoTo({ home }, Jobs.STROLL, near = 1.5, most = 900)
            parts += walker("culprit", culprit, k, essential = false)
        }
        return Stage("locket_clasp", parts, hours, at.feet, most = 3000)
    }

    /**
     * The Clasp of the tale [t] as it ended B: the owner (on their doorstep if they are near it, else where they stand, turned to the player who gave
     * it back), the lead if they are near, and the culprit if they are about. Null outside its hours or with no owner about.
     */
    internal fun claspFor(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!Stage.within(level, CLASP)) return null
        val owner = TaleBodies.body(level, t, LocketCast.OWNER) ?: return null
        val door = doorstepOf(c, t, LocketCast.OWNER)?.takeIf { owner.position().distanceToSqr(it.feet) <= CLASP_DOOR * CLASP_DOOR }
        val player = lead(c, t, owner.position())
        val at = door ?: Sets.standable(level, owner.blockPosition())?.let { Step(it, player?.eyePosition ?: owner.eyePosition.add(owner.lookAngle.scale(LANE))) } ?: return null
        val culprit = TaleBodies.body(level, t, LocketCast.CULPRIT)?.takeIf { it.position().distanceToSqr(at.feet) <= CULPRIT_FAR * CULPRIT_FAR }
        return clasp(owner, culprit, at, player, CLASP)
    }

    /** The lead of the tale, if they are on and within [PLAYER_NEAR] blocks of [near]. */
    private fun lead(c: Ctx, t: Tale, near: Vec3): ServerPlayer? {
        val id = t.lead?.player ?: return null
        return c.level.server.playerList.getPlayer(id)?.takeIf { it.level() === c.level && it.position().distanceToSqr(near) <= PLAYER_NEAR * PLAYER_NEAR }
    }

    // ---- The Naming (C) ----

    /**
     * The Naming (S3, lite): the culprit carries the locket to the well and waits there with it; the owner comes and stands before them. The culprit
     * holds it out (HAND_OVER); the owner takes it (RECEIVE), folds their arms (ARMS_FOLDED) and says `tale.locket.shame`; the culprit sighs. The
     * [watchers] (the watch first) come to the ring about the well, gaze, and grumble once the owner has spoken. When the owner has said their word,
     * `played[0]` is set. About a minute. Null if there is nowhere to stand at [well].
     */
    fun naming(owner: Townsperson, culprit: Townsperson, well: BlockPos, watchers: List<Townsperson>, hours: ClosedFloatingPointRange<Double>?, played: BooleanArray): Stage? {
        val level = owner.level() as ServerLevel
        val middle = Vec3.atBottomCenterOf(well)
        val mark = Sets.beside(level, middle, culprit.position(), 2.5) ?: return null
        val markAt = Vec3.atBottomCenterOf(mark)
        val stand = Sets.beside(level, markAt, owner.position(), HANDING) ?: return null
        val face = { markAt.add(0.0, 1.5, 0.0) }
        val locket = LOCKET
        val them = eye(culprit)
        val her = eye(owner)
        val o = ArrayList<Move>()
        o += GoTo({ stand }, Jobs.WALK, near = 0.9, most = 1800)
        o += Stand({ Vec3.atBottomCenterOf(stand) }, toward = them)
        o += Barrier("set", most = 1800, at = them)
        o += Barrier("hand", most = 300, at = them)
        o += Beat(Activity.RECEIVE, at = them) { p, tick -> if (tick == HANDED_AT) Stage.give(p, locket) }
        o += Barrier("handed", most = 120, at = them)
        o += Say("tale.locket.shame", gesture = Activity.ARMS_FOLDED, at = them)
        o += Now { played[0] = true }
        o += Barrier("said", most = 200, at = them)
        o += Play(Activity.SIGH, at = them)
        val c = ArrayList<Move>()
        c += Hold(locket)
        c += GoTo({ mark }, Jobs.WALK, near = 0.8, overlay = Activity.CARRY, most = 1800)
        c += Stand({ markAt }, toward = her, overlay = Activity.CARRY)
        c += Barrier("set", most = 1800, pose = Activity.CARRY, at = her)
        c += Turn(her)
        c += Barrier("hand", most = 300, pose = Activity.CARRY, at = her)
        c += Beat(Activity.HAND_OVER, at = her) { p, tick -> if (tick == HANDED_AT) Stage.give(p, null) }
        c += Barrier("handed", most = 120, at = her)
        c += Barrier("said", most = 600, at = her)
        c += Play(Activity.SIGH, at = her)
        val extras = watchers.filter { it !== owner && it !== culprit }.take(WATCHERS).mapIndexed { i, w ->
            val ring = { Sets.beside(level, markAt, w.position(), 3.5 + i) }
            val m = ArrayList<Move>()
            m += GoTo(ring, Jobs.STROLL, near = 1.5, most = 900)
            m += Turn(face)
            m += Await("said", 3000, Activity.GAZE, face)
            m += Wait({ _, t -> t >= 8 * i }, most = 40)
            m += Play(Activity.GRUMBLE, at = face)
            walker("watcher${i + 1}", w, m, essential = false)
        }
        return Stage("locket_naming", listOf(rising("owner", owner, o), rising("culprit", culprit, c)), hours, middle, most = 3600, extras = extras)
    }

    /** The Naming of the tale [t] by day (a C before the street or by the Order's word): the well nearest the owner's door, and the watch to look on. */
    internal fun namingFor(c: Ctx, t: Tale, played: BooleanArray): Stage? {
        val level = c.level
        if (!Stage.within(level, STREET)) return null
        val owner = TaleBodies.body(level, t, LocketCast.OWNER) ?: return null
        val culprit = TaleBodies.body(level, t, LocketCast.CULPRIT) ?: return null
        val from = doorstepOf(c, t, LocketCast.OWNER)?.spot ?: owner.blockPosition()
        val well = c.here.places.wells.minByOrNull { it.distSqr(from) } ?: c.here.places.market
        val middle = Vec3.atBottomCenterOf(well)
        if (owner.position().distanceToSqr(middle) > OWNER_FAR * OWNER_FAR) return null
        if (culprit.position().distanceToSqr(middle) > CULPRIT_FAR * CULPRIT_FAR) return null
        val street = Sets.freeAdults(level, middle, CROWD_NEAR, listOf(owner, culprit))
            .sortedBy { if (it.role == TownRole.GUARD) 0 else 1 }
        return naming(owner, culprit, well, street, STREET, played)
    }

    // ---- The owner who has stopped looking (W) ----

    /**
     * The end of a W: the owner walks to their doorstep at dusk and stands there looking down the lane, then turns half away with their face in
     * their hands (GRIEVE, a standing clip), says `tale.locket.lost`, and sighs. About thirty seconds.
     */
    fun lost(owner: Townsperson, at: Step, hours: ClosedFloatingPointRange<Double>?): Stage {
        val look = { at.look }
        val k = ArrayList<Move>()
        k += GoTo({ at.spot }, Jobs.STROLL, near = 0.8, most = 1500)
        k += Stand({ at.feet }, toward = look)
        k += Play(Activity.LOOK_ABOUT, at = look)
        k += Say("tale.locket.lost", gesture = Activity.GRIEVE, at = look)
        k += Play(Activity.GRIEVE, 160)
        k += Play(Activity.SIGH, at = look)
        return Stage("locket_lost", listOf(rising("owner", owner, k)), hours, at.feet, most = 2400)
    }

    /** The owner of the tale [t] on their step at dusk, after a W. Null when it is not the hour or they are not about. */
    internal fun lostFor(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!Stage.within(level, DUSK)) return null
        val owner = TaleBodies.body(level, t, LocketCast.OWNER) ?: return null
        val at = doorstepOf(c, t, LocketCast.OWNER) ?: return null
        if (owner.position().distanceToSqr(at.feet) > OWNER_FAR * OWNER_FAR) return null
        return lost(owner, at, DUSK)
    }

    // ---- Played through ----

    /**
     * [stage] (a scene of step 4 of the open tale [t]) with its end heard: played through ([played] set by its last move), the tale ends at the way
     * the player chose (the ending the close would have given it); broken off (a word to someone, a monster, no one by), it is asked for again.
     */
    internal fun ending(t: Tale, level: ServerLevel, stage: Stage?, played: BooleanArray): Stage? {
        stage ?: return null
        val id = t.id
        val capital = t.capital
        stage.then = { done -> if (done && played[0]) finished(level, capital, id) }
        return stage
    }

    /** The return was played through: it is in the record, and the tale ends at its way. Nothing if the tale has moved on meanwhile. */
    private fun finished(level: ServerLevel, capital: Long, id: Int) {
        val data = TaleData.get(level)
        val t = data[id]?.takeIf { it.open && it.step == LocketSlots.AT_RETURN } ?: return
        val c = TaleEngine.ctx(level, capital) ?: return
        t.n[LocketSlots.PLAYED] = 1
        t.n[LocketSlots.LOCKET] = LocketSlots.HOME
        c.why(t, if (t.n[LocketSlots.WAY] == LocketSlots.BREAD) "locket.returned" else "locket.named", Locket.given(t.cast(LocketCast.OWNER)?.name.orEmpty()))
        data.changed()
        c.end(t, Locket.endingOf(t))
    }
}
