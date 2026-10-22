package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Rise
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the Silent Lute shows (living-world/stories/design.md 8.6, 4.3): the bard sitting out the songs on her bench with a friend to comfort
 * her (the hush, S1's variant), the concert at dusk, the cheer that follows it, and the lute handed back to its keeper. Each is a [Stage]
 * made of the people the record names or, for `tale scene lute_*`, of whoever is free near the one who ran the command; the tale's ask
 * ([Lute.scene]) wraps them with the checks the record makes. Every body walks at its own pace to where it plays and goes back to its day
 * after: nobody is made to appear, and a scene that cannot be taken is simply not played (the record is the truth).
 *
 *  - **hush** (steps 0 and 1, by day): she sighs, sits, shrugs; a friend ([Lute] `regular`, else the nearest free adult) walks up, says a
 *    word of comfort and goes on; she waves it off and sighs. Nothing here plays the lute.
 *  - **concert** (step 3, 18:00 to 20:00): she gets up from her seat in the tavern, walks to the square, turns, says her line, and takes up
 *    `Activity.LUTE`, as `Perform` does, so the town's own listening follows (`Social.performingBard`, `Audience`: anyone free within 16
 *    blocks joins on their own); up to four listeners walk to a half ring before her and listen; she bows and they clap and cheer.
 *  - **cheer** (the end of an A or a B): S7, with her at the toast.
 *  - **giveback** (the end of a C): she walks the lute to its keeper at the bar, who says his word.
 */
object LuteScenes {
    /** The hours the hush plays in (she sits on her bench by day), and the cheer (S7). */
    val DAY: ClosedFloatingPointRange<Double> = 10.0..17.5
    val CHEER: ClosedFloatingPointRange<Double> = 9.0..21.0

    /** The hours the lute goes back in: the evening, after the concert. */
    private val EVENING: ClosedFloatingPointRange<Double> = 17.0..22.0

    /** A song is 700 to 900 ticks, a little longer than the street bard's. */
    private const val SONG = 700
    private const val SONG_MORE = 200

    /** The most listeners (a stage has five parts, one of them the bard), and how near the square the regular must be to be one. */
    private const val LISTENERS = TaleLimits.STAGE_PARTS - 1
    private const val REGULAR_NEAR = 36.0
    private const val CROWD_NEAR = 24.0

    /** How far from the square the bard may be for the hush (she is on a bench in it) and the concert (she walks there from the tavern), in blocks. */
    private const val HUSH_NEAR = 30.0
    private const val CONCERT_FAR = 120.0

    /** The keeper must be this near the bard for the lute to be walked to him. */
    private const val GIVEBACK_NEAR = 48.0

    /** Market days a listener is a volunteer for the works (6.3). */
    private const val VOLUNTEER_DAYS = 10

    // ---- Making the parts ----

    /** A part for someone who stays where they stand or sit: a worker at their post, or someone seated, is played in place. */
    private fun still(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves, station = body.seat != null || body.routine.anchored, seated = body.seat != null)

    /** A part for someone who walks about in it. */
    private fun walker(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves)

    /** A part for someone who walks, but who may be sitting when the stage takes them (the bard in her seat): they get up first. */
    private fun rising(slot: String, body: Townsperson, moves: List<Move>): Stage.Part =
        if (body.seat != null) still(slot, body, listOf<Move>(Rise()) + moves) else walker(slot, body, moves)

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    /** A wait of [ticks] in [pose]. */
    private fun pause(ticks: Int, pose: Activity): Move = Wait({ _, t -> t >= ticks }, most = ticks + 1, pose = pose)

    // ---- The hush (S1, a variant) ----

    /**
     * She sits on her bench with her hands empty: SIGH, a pause, SHRUG; if [friend] is given, the friend walks up (the walk takes as long as it
     * takes, and she waits for them), says a word of comfort (`tale.set.comfort`), she turns to them, waves it off and sighs, and goes
     * on sitting. About a minute. A standing bard (no seat was free) does the same on her feet.
     */
    fun hush(bard: Townsperson, friend: Townsperson?, hours: ClosedFloatingPointRange<Double>? = DAY): Stage {
        val level = bard.level() as ServerLevel
        val seated = bard.seat != null
        val rest = if (seated) Activity.SIT else Activity.NONE
        val spot = friend?.let { Sets.beside(level, bard.position(), it.position(), 2.0) }
        val k = ArrayList<Move>()
        k += Play(Activity.SIGH)
        k += pause(80, rest)
        k += Play(Activity.SHRUG)
        val parts = ArrayList<Stage.Part>()
        if (friend != null && spot != null) {
            // About eight ticks a block at a stroll, and a breath for the first of her moves
            val meet = (260 + 10 * friend.distanceTo(bard)).toInt().coerceAtMost(900)
            k += Turn(eye(friend))
            k += Barrier("meet", most = meet, pose = rest, at = eye(friend))
            k += Barrier("said", most = 400, pose = if (seated) Activity.SIT else Activity.LISTEN, at = eye(friend))
            k += Play(Activity.WAVE_OFF, at = eye(friend))
            k += Play(Activity.SIGH)
            val f = ArrayList<Move>()
            f += GoTo({ spot }, Jobs.STROLL, near = 1.2, most = meet)
            f += Turn(eye(bard))
            f += Barrier("meet", most = meet, at = eye(bard))
            f += Play(Activity.NOD, at = eye(bard))
            f += Say("tale.set.comfort", gesture = Activity.TALK, at = eye(bard))
            f += Barrier("said", most = 400, at = eye(bard))
            f += Play(Activity.NOD, at = eye(bard))
            parts += walker("friend", friend, f, essential = false)
        }
        k += pause(60, rest)
        parts.add(0, still("bard", bard, k))
        return Stage("hush", parts, hours, bard.position(), most = 3000)
    }

    /** The hush of the tale [t] by day: the bard if she is in the square, and her friend, else the nearest free adult. Null when it is not the hour or she is not there. */
    internal fun hushFor(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!Stage.within(level, DAY)) return null
        val bard = TaleBodies.body(level, t, "bard") ?: return null
        val square = bard.town?.square ?: c.here.places.market
        if (bard.position().distanceToSqr(Vec3.atBottomCenterOf(square)) > HUSH_NEAR * HUSH_NEAR) return null
        val friend = TaleBodies.body(level, t, "regular")?.takeIf { it !== bard && it.distanceToSqr(bard) <= REGULAR_NEAR * REGULAR_NEAR } ?: Sets.passerBy(bard)
        return hush(bard, friend)
    }

    // ---- The concert ----

    /**
     * The concert (8.6): the bard rises if she sits, walks to a spot of the square, turns to face it and waits for the listeners; says her line
     * (`tale.lute.intro`), and takes up `Activity.LUTE` for a song (700 to 900 ticks: the client draws the notes and plays the tune while she
     * does), then bows (SALUTE). The [listeners] (at most four) walk to a half ring before her and wait for her in a listening pose (they hear the
     * line and the song), and at the bow clap and cheer a beat apart. When she has bowed, `played[0]` is set, which is how the template tells a
     * concert played through from one broken off. Null if there is nowhere to stand in or near [square].
     */
    fun concert(
        bard: Townsperson, listeners: List<Townsperson>, square: BlockPos, hours: ClosedFloatingPointRange<Double>?, played: BooleanArray,
    ): Stage? {
        val level = bard.level() as ServerLevel
        val spot = Places.street(level, square, 5, level.random, tries = 6) ?: Sets.standable(level, square) ?: return null
        val centre = Vec3.atBottomCenterOf(spot)
        val face = Vec3.atCenterOf(square)
        val look = Vec3(face.x, centre.y + 1.4, face.z)
        val b = ArrayList<Move>()
        b += GoTo({ spot }, Jobs.WALK, near = 0.8, most = 1500)
        b += Turn({ look })
        b += Barrier("set", most = 1500, at = { look })
        b += Say("tale.lute.intro", at = { look })
        b += Play(Activity.LUTE, SONG + bard.random.nextInt(SONG_MORE), at = { look })
        b += Barrier("played", most = 200)
        b += Play(Activity.SALUTE, at = { look })
        b += Now { played[0] = true }
        val parts = ArrayList<Stage.Part>()
        parts += rising("bard", bard, b)
        // A half ring before her, on the side she faces: the square's middle
        val toward = atan2(face.z - centre.z, face.x - centre.x)
        val crowd = listeners.take(LISTENERS)
        for ((i, o) in crowd.withIndex()) {
            val angle = toward + (i - (crowd.size - 1) / 2.0) * 0.7
            val ring = Sets.standable(level, BlockPos.containing(centre.x + cos(angle) * 3.0, centre.y, centre.z + sin(angle) * 3.0))
            val m = ArrayList<Move>()
            if (ring != null) m += GoTo({ ring }, Jobs.WALK, near = 0.9, most = 1500)
            m += Turn({ Vec3(centre.x, centre.y + 1.4, centre.z) })
            m += Barrier("set", most = 1500, at = { Vec3(centre.x, centre.y + 1.4, centre.z) })
            m += Barrier("played", most = 2400, pose = Activity.LISTEN, at = { Vec3(centre.x, centre.y + 1.4, centre.z) })
            m += Wait({ _, tick -> tick >= 5 * i }, most = 30)
            m += Play(if (i % 2 == 0) Activity.CLAP else Activity.CHEER)
            if (i % 2 == 1) m += Play(Activity.CLAP)
            parts += walker(if (i == 0) "listener" else "listener${i + 1}", o, m, essential = false)
        }
        return Stage("concert", parts, hours, centre, most = 3600)
    }

    /** The concert of the tale [t]: her listeners the regular if she is near the square and the free adults about it. When played through it ends the tale ([played]). */
    internal fun concertFor(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!Stage.within(level, LuteSilence.HUSH)) return null
        val bard = TaleBodies.body(level, t, "bard") ?: return null
        val square = bard.town?.square ?: c.here.places.market
        val middle = Vec3.atBottomCenterOf(square)
        if (bard.position().distanceToSqr(middle) > CONCERT_FAR * CONCERT_FAR) return null
        val regular = TaleBodies.body(level, t, "regular")?.takeIf { it !== bard && it.position().distanceToSqr(middle) <= REGULAR_NEAR * REGULAR_NEAR }
        val crowd = Sets.freeAdults(level, middle, CROWD_NEAR, listOfNotNull(bard, regular))
        val played = BooleanArray(1)
        val stage = concert(bard, listOfNotNull(regular) + crowd, square, LuteSilence.HUSH, played) ?: return null
        val id = t.id
        val capital = t.capital
        // Heard by the telling once the stage is over; a concert broken off (a word to the bard, a monster, no one by) is asked for again
        stage.then = { done -> if (done && played[0]) finished(level, capital, id, stage) }
        return stage
    }

    /**
     * The concert was played through: it is in the record, the listeners who stood before her are the works' volunteers for ten days if the lute
     * was redeemed outright (A, 6.3), and the tale ends at the way the player chose. Nothing if the tale has moved on meanwhile.
     */
    private fun finished(level: ServerLevel, capital: Long, id: Int, stage: Stage) {
        val data = TaleData.get(level)
        val t = data[id]?.takeIf { it.open && it.step == LuteSlots.AT_SCENE } ?: return
        val c = TaleEngine.ctx(level, capital) ?: return
        t.n[LuteSlots.PLAYED] = 1
        c.why(t, "lute.played")
        val ending = Lute.endingOf(t)
        if (ending == Ending.A) {
            val town = data.town(capital)
            val until = c.day + VOLUNTEER_DAYS
            for (r in stage.taken().filter { it.slot.startsWith("listener") }.map { it.body.resident }.filter { it >= 0 }.distinct()) {
                town.volunteers.removeAll { it.resident == r }
                town.volunteers.add(TaleData.Volunteer(r, until))
            }
            while (town.volunteers.size > TaleLimits.VOLUNTEERS) town.volunteers.removeAt(0)
        }
        data.changed()
        LuteSilence.refresh(capital)
        c.end(t, ending)
    }

    // ---- The cheer (S7) and the lute's return ----

    /** S7 with the bard at the toast, the regular and the free adults about her cheering, after an A or a B. Null with no one to cheer. */
    internal fun cheerFor(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!Stage.within(level, CHEER)) return null
        val bard = TaleBodies.body(level, t, "bard") ?: return null
        val regular = TaleBodies.body(level, t, "regular")?.takeIf { it !== bard && it.distanceToSqr(bard) <= REGULAR_NEAR * REGULAR_NEAR }
        val others = (listOfNotNull(regular) + Sets.freeAdults(level, bard.position(), CROWD_NEAR, listOfNotNull(bard, regular))).take(LISTENERS)
        if (others.isEmpty()) return null
        return Sets.cheer(bard, others, Sets.bellNear(level, bard.blockPosition()), CHEER)
    }

    /**
     * The lute goes back (8.6, C): she walks it to its keeper, holds it out (HAND_OVER, the lute in her hand for the first half of it); he
     * RECEIVEs it, says his word (`tale.lute.returned`), and she nods and goes on. The lute in the hand is the Lute Pick's picture (no lute
     * is drawn for a hand). Null if the keeper is too far to walk to.
     */
    fun giveBack(bard: Townsperson, keeper: Townsperson, hours: ClosedFloatingPointRange<Double>?): Stage? {
        val level = bard.level() as ServerLevel
        if (bard.distanceToSqr(keeper) > GIVEBACK_NEAR * GIVEBACK_NEAR) return null
        val meet = Sets.beside(level, keeper.position(), bard.position(), 1.0) ?: return null
        val lute = Sets.prop("lute")
        val g = ArrayList<Move>()
        g += Hold(lute)
        g += GoTo({ meet }, Jobs.WALK, near = 0.8, overlay = Activity.CARRY, most = 900)
        g += Turn(eye(keeper))
        g += Barrier("hand", most = 300, at = eye(keeper))
        g += Beat(Activity.HAND_OVER, at = eye(keeper)) { p, tick -> if (tick == 16) Stage.give(p, null) }
        g += Barrier("handed", most = 120, at = eye(keeper))
        g += Barrier("said", most = 400, at = eye(keeper))
        g += Play(Activity.NOD, at = eye(keeper))
        val k = ArrayList<Move>()
        k += Turn(eye(bard))
        k += Barrier("hand", most = 300, at = eye(bard))
        k += Beat(Activity.RECEIVE, at = eye(bard)) { p, tick -> if (tick == 12) Stage.give(p, lute) }
        k += Barrier("handed", most = 120, at = eye(bard))
        k += Say("tale.lute.returned", gesture = Activity.NOD, at = eye(bard))
        k += Barrier("said", most = 400, at = eye(bard))
        k += Hold(ItemStack.EMPTY)
        return Stage("giveback", listOf(rising("bard", bard, g), still("keeper", keeper, k)), hours, keeper.position(), most = 3000)
    }

    /** The lute going back to its keeper after a C: the bard and the keeper of the record. */
    internal fun giveBackFor(c: Ctx, t: Tale): Stage? {
        val level = c.level
        val bard = TaleBodies.body(level, t, "bard") ?: return null
        val keeper = TaleBodies.body(level, t, "keeper") ?: return null
        return giveBack(bard, keeper, EVENING)
    }
}
