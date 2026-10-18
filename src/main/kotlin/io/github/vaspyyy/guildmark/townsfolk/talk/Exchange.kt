package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * A chat between two townsfolk from the topics (living-world/design-voices.md section 2, "To each other"): [turns]
 * turns, talking and listening by turns. Half the turns the one talking brings something up for the other
 * (Occasion.CHAT, the other as listener), with its gesture: a rumour passes to the other as it's told
 * ([TownTalk.tell]) and they answer it by its tone, glad, sorry, amazed or doubtful; a place is pointed out ("I'm off
 * to the market", a turn and a point that way), and so is the player the news is about, if they're in sight ("That's
 * the one, over there."), while the other looks round at them. At the end, a laugh together if it went well, else a
 * nod. Nothing is said with no player in earshot, or while two others close by are already talking.
 *
 * Both Chat tasks hold it; the one who began it ([a]) runs it from theirs, and the other's only follows.
 */
class Exchange(private val a: Townsperson, private val b: Townsperson, private val turns: Int) {
    private var age = 0
    private var turn = 0
    private var ends = 0
    private var talker = a

    /** The tick the goodbye began, or -1. */
    private var over = -1

    /** How the last thing told went down, for the goodbye: good news ends in a laugh. */
    private var tone = 0

    var done = false
        private set

    /** What each keeps to between gestures: talking or listening, standing or seated. */
    private var poseA = Activity.NONE
    private var poseB = Activity.NONE

    /** Who is pointing something out, at whom or where, since when, and whether the point has begun (they turn first). */
    private var pointer: Townsperson? = null
    private var pointAt: Entity? = null
    private var pointSpot: Vec3? = null
    private var pointSince = 0
    private var pointing = false

    private class Beat(val at: Int, val run: () -> Unit)

    private val beats = ArrayList<Beat>()

    /** Where [p] turns to: whatever they're pointing out, else the other. */
    fun turnTo(p: Townsperson): Vec3 = (if (p === pointer && p.seat == null) target() else null) ?: other(p).position()

    /** Where [p] looks: whatever they're pointing out, else the other's face. */
    fun look(p: Townsperson): Vec3 = (if (p === pointer) pointAt?.takeIf { live(it) }?.eyePosition ?: pointSpot else null) ?: other(p).eyePosition

    /** One tick of it, from [a]'s Chat; false once it's over. */
    fun tick(): Boolean {
        if (done) return false
        try {
            if (a.director != null || b.director != null || !a.isAlive || !b.isAlive) {
                done = true
                return false
            }
            age++
            if (beats.isNotEmpty()) {
                val due = beats.filter { it.at <= age }
                if (due.isNotEmpty()) {
                    beats.removeAll { it.at <= age }
                    for (beat in due) beat.run()
                }
            }
            point()
            if (over >= 0) {
                if (age >= over + BYE) done = true
                return !done
            }
            if (age >= ends && beats.isEmpty() && pointer == null) {
                if (turn >= turns) goodbye() else next()
            }
            hold(a, poseA)
            hold(b, poseB)
            return true
        } catch (e: Exception) {
            if (!broken) Guildmark.LOGGER.error("Townsfolk talk: a chat in the street failed", e)
            broken = true
            done = true
            return false
        }
    }

    private fun other(p: Townsperson): Townsperson = if (p === a) b else a

    private fun live(e: Entity): Boolean = e.isAlive && e.level() === a.level()

    private fun target(): Vec3? = pointAt?.takeIf { live(it) }?.position() ?: pointSpot

    private fun beat(at: Int, run: () -> Unit) {
        beats += Beat(at, run)
    }

    /** The next turn: the other's to talk (the first is [a]'s), and half the time something said. */
    private fun next() {
        turn++
        talker = if (turn == 1) a else other(talker)
        val listener = other(talker)
        ends = age + TURN + talker.random.nextInt(TURN)
        pose(talker, if (talker.seat != null) Activity.SIT_TALK else Activity.TALK)
        pose(listener, if (listener.seat != null) Activity.SIT else Activity.LISTEN)
        if (talker.random.nextBoolean()) words(talker, listener)
    }

    /** [p] keeps to [g] between gestures from now. */
    private fun pose(p: Townsperson, g: Activity) {
        if (p === a) poseA = g else poseB = g
        if (p.activity != g && (p.activity.loop || p.actDone)) p.act(g)
    }

    /** A gesture over (or something else come between), back to the pose. */
    private fun hold(p: Townsperson, pose: Activity) {
        if (pose == Activity.NONE || p === pointer && pointing) return
        if (p.activity != pose && (p.activity.loop || p.actDone)) p.act(pose)
    }

    /**
     * [p] brings something up for [other], if a player's in earshot to hear it and the square isn't loud already: what's
     * on their mind for them (the topics), else one of the old small talk lines true now; part after part, with its
     * gesture; then [other] has the rumour, and answers.
     */
    private fun words(p: Townsperson, other: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        if (p.speech.isNotEmpty() || level.gameTime < p.nextSpeech) return
        val near = level.getNearestPlayer(p, Townsperson.HEARING) as? ServerPlayer ?: return
        if (Street.hushed(p, other)) return
        val ask = Street.chat(level, p, other)
        val topic = Street.pick(ask, Topics.LEAST, Street.allow(ask))
        val lines = topic?.let { Street.said(ask, it) }?.ifEmpty { null } ?: listOfNotNull(Street.gossip(p))
        if (lines.isEmpty()) return
        topic?.tone?.let { if (it != 0) tone = it }
        if (topic != null) gesture(p, topic)
        val about = topic?.about?.let { level.server.playerList.getPlayer(it) }
        val why = "chat" + (topic?.let { ": ${it.id}" } ?: "")
        var at = 0
        for ((i, line) in lines.withIndex()) {
            val there = line.voiced.startsWith(THERE)
            val say = {
                if (p.isAlive && p.director == null) {
                    if (there) pointOut(p, other, about)
                    if (p.say(line.speech, force = true, ticks = line.ticks + 20L)) Street.heard(p, near, line, why, toThem = false)
                }
            }
            if (i == 0) say() else beat(age + at, say)
            at += line.ticks + GAP
        }
        ends = maxOf(ends, age + at + 10)
        beat(age + at + 4) { told(level, p, other, topic, near) }
    }

    /** The gesture [topic] is said with, standing or seated: kept up as the pose (talk, gossip, explaining) or made once. */
    private fun gesture(p: Townsperson, topic: Topic) {
        val g = topic.gesture ?: Talk.gesture(topic)
        val sat = p.seat != null
        when {
            // Where they're off to: a turn and a point that way
            g == Activity.POINT && topic.rumour == null -> {
                val toward = topic.toward
                if (!sat && toward != null) {
                    pointer = p
                    pointAt = null
                    pointSpot = Vec3.atCenterOf(toward)
                    pointSince = age
                    pointing = false
                } else pose(p, if (sat) Activity.SIT_TALK else Activity.EXPLAIN)
            }
            // The one the news is about is pointed out as they're named ("That's the one, over there."); till then, gossip
            g == Activity.POINT -> pose(p, if (sat) Activity.SIT_GOSSIP else Activity.GOSSIP)
            else -> {
                val made = Talk.seated(p, g) ?: return
                if (made.loop) pose(p, made) else p.act(made)
            }
        }
    }

    /** [p] points out [them] (or leans in, seated) as they say "That's the one, over there."; [other] looks round at them. */
    private fun pointOut(p: Townsperson, other: Townsperson, them: ServerPlayer?) {
        if (them == null || !live(them) || them.isSpectator) return
        if (p.seat == null) {
            pointer = p
            pointAt = them
            pointSpot = null
            pointSince = age
            pointing = false
        } else pose(p, Activity.SIT_GOSSIP)
        other.glance(them, LOOK_ROUND)
    }

    /** The point: once they've turned to it (or a moment's gone), the arm goes out; when it's down, back to the talk. */
    private fun point() {
        val p = pointer ?: return
        val at = target()
        if (at == null || !p.isAlive || p.seat != null) {
            stopPointing()
            return
        }
        if (!pointing) {
            val off = Mth.wrapDegrees(p.yawTo(at) - p.yBodyRot)
            if (abs(off) < AIMED || age - pointSince >= TURNING) {
                p.act(Activity.POINT)
                pointing = true
            }
        } else if (p.activity != Activity.POINT || p.actDone) stopPointing()
    }

    private fun stopPointing() {
        pointer = null
        pointAt = null
        pointSpot = null
        pointing = false
    }

    /** [p] has told [other] what [topic] says: the rumour is theirs now too, and they answer it as it strikes them. */
    private fun told(level: ServerLevel, p: Townsperson, other: Townsperson, topic: Topic?, near: ServerPlayer) {
        topic?.rumour?.let { Street.tell(level, p, other, it) }
        if (other.speech.isNotEmpty()) return
        val reply = Street.reply(other, p, topic) ?: return
        if (!other.say(reply.line.speech, force = true, ticks = reply.line.ticks + 20L)) return
        Street.heard(other, near, reply.line, "chat reply" + (topic?.let { ": ${it.id}" } ?: ""), toThem = false)
        reply.gesture?.let { g -> Talk.seated(other, g)?.let { made -> if (made.loop) pose(other, made) else other.act(made) } }
        ends = maxOf(ends, age + reply.line.ticks + 16)
    }

    /** The talk's done: a laugh together if it went well (or one time in three), else a nod; one of them laughs aloud. */
    private fun goodbye() {
        over = age
        val laugh = tone > 0 || tone == 0 && a.random.nextInt(3) == 0
        for (p in listOf(a, b)) {
            val sat = p.seat != null
            if (p === a) poseA = if (sat) Activity.SIT else Activity.NONE else poseB = if (sat) Activity.SIT else Activity.NONE
            when {
                laugh -> p.act(if (sat) Activity.SIT_LAUGH else Activity.LAUGH)
                !sat -> p.act(Activity.NOD)
                else -> p.act(Activity.SIT)
            }
        }
        if (!laugh) return
        val who = if (a.random.nextBoolean()) a else b
        val level = who.level() as? ServerLevel ?: return
        val near = level.getNearestPlayer(who, Townsperson.HEARING) as? ServerPlayer ?: return
        if (who.speech.isNotEmpty() || Street.hushed(who, other(who))) return
        val line = Street.Line(LAUGH, LAUGH, Talk.ticks(who, LAUGH))
        if (who.say(line.speech, ticks = line.ticks + 20L)) Street.heard(who, near, line, "chat's end", toThem = false)
    }

    private companion object {
        /** Each turn lasts this long, and up to as long again (ticks), or as long as what's said in it. */
        const val TURN = 60

        /** Ticks between two parts of a line, and from the goodbye to the end. */
        const val GAP = 6
        const val BYE = 40

        /** The point goes out once they've turned within this many degrees of it, or after this many ticks of turning. */
        const val AIMED = 12f
        const val TURNING = 12

        /** Ticks the other looks round at whoever's pointed out. */
        const val LOOK_ROUND = 40

        const val THERE = "talk.news.there"
        const val LAUGH = "laugh_0"

        var broken = false
    }
}
