package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * The telling of a capital's open tale as the player sees it (living-world/stories/design.md 4.2, 3.3 and 6.7 stage 7), Part B.
 * Once a second for each player in a capital, from [Tales.look] (slot 19 of `Scenes.tick`), it
 *
 * - sets the town's `seen` (the last market day a player was in the capital, which the engine reads for a tale to start);
 * - keeps the capital's one stage: lets go of one that is over, takes the parts of one that waits (ten tries, then its
 *   [Stage.then] hears `false` and the step is settled in the record without a scene), and, forty seconds after the last,
 *   asks the open tale's template for its next scene (`Template.scene`) when a player is within 48 blocks of its cast;
 * - and sends the motes of the cast member who wants the player ([motes]).
 *
 * When a seed scene or an ending plays, the street hears it: up to two free adults near it turn and watch ([hear]).
 * Once a second for the whole world, whether a player is in a capital or not, a listener on the server tick ([register]) tidies
 * the held residents and the made figures ([TaleBodies.tidy]) and sees to the stages ([upkeep]): one that is over is heard to be,
 * one gone stale is cut off, and with the scenes switched off the capitals' stages are let go.
 *
 * Everything here is memory and is rebuilt: a restart loses the stages, the counts of how often a seed scene has played and
 * the motes, and the next second begins them again where the record says. A hash lookup of the capital's slot and the open
 * tale is all it costs while nothing is due.
 */
object TaleLook {
    /** How near a player must be to the cast for a scene to be asked for, and to the scene for an ending to be seen. */
    const val NEAR = 48.0

    /** How near a player must be to see the motes. */
    const val MOTES = 24.0

    /** Tries to take a stage's parts before the step goes on without it. */
    private const val TRIES = 10

    /** The most times a seed scene plays in one step. */
    private const val PLAYS = 3

    /** How far from a scene a free adult is who turns to watch it, and how many (the street hears). */
    private const val HEARS = 16.0
    private const val HEARERS = 2

    /** Ticks between two warnings in the log from the same kind of failure in the once-a-second work. */
    private const val WARN_EVERY = 1200L

    /** Ticks between one stage and the next in a capital, between asks of a template, and the longest a stage waits to be taken. */
    private const val GAP = TaleLimits.STAGE_GAP_SECONDS * 20L
    private const val ASK = 40L
    private const val WAITS = 600L

    private class Slot {
        var playing: Stage? = null
        var pending: Stage? = null
        var tries = 0
        var queued = Long.MIN_VALUE / 2
        var last = Long.MIN_VALUE / 2
        var asked = Long.MIN_VALUE / 2
        var worked = Long.MIN_VALUE / 2

        /** The tale and step the pending stage is a seed scene of (-1: it is not one), and whether it jumps the gap (an ending, a one-person clip). */
        var tale = -1
        var step = -1
        var urgent = false

        /** Whether the street hears it when it plays (a seed scene or an ending, not a one-person clip). */
        var hears = false
    }

    private val slots = HashMap<Long, Slot>()

    /** How often a seed scene has played, by tale and step (`id shl 8 or step`), and the tales whose ending scene has been queued. */
    private val plays = HashMap<Long, Int>()
    private val endings = HashSet<Int>()

    /** Stages played by the `tale scene` command, kept only to be listed and let go. */
    private val manual = ArrayList<Stage>()

    // ---- Once a second for each player in a capital ----

    /** From [Tales.look]. */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {
        val key = here.key
        val data = TaleData.get(level)
        // The first thing: a player was here today
        val day = Fortunes.today(level, key)
        val town = data.town(key)
        if (town.seen != day) {
            town.seen = day
            data.changed()
        }
        if (!Tales.scenes()) return
        val tale = data.openIn(key)
        val slot = slots[key]
        if (tale == null && slot == null) return
        val now = level.gameTime
        val s = slot ?: Slot().also { slots[key] = it }
        // The capital's one stage is kept once a second, whoever's second it is
        if (now - s.worked >= 20) {
            s.worked = now
            keep(level, here, s, tale, player)
        }
        if (tale != null) motes(level, player, tale)
    }

    private fun keep(level: ServerLevel, here: Scenes.Here, s: Slot, tale: Tale?, player: ServerPlayer) {
        val now = level.gameTime
        s.playing?.let { st ->
            if (st.finished) {
                s.playing = null
                s.last = st.endedAt
                fire(st, true)
            } else if (st.stale(level)) {
                st.cancel(level)
            }
        }
        take(level, s, tale)
        val due = tale != null && tale.open && s.pending == null && s.playing == null && now - s.last >= GAP && now - s.asked >= ASK
        if (due && tale != null) {
            s.asked = now
            ask(level, here, s, tale, player)
            take(level, s, tale)
        }
    }

    /** The stage that waits is taken if its parts are free: ten tries a second apart, then it is given up. */
    private fun take(level: ServerLevel, s: Slot, tale: Tale?) {
        val waiting = s.pending ?: return
        if (s.playing != null) {
            if (level.gameTime - s.queued > WAITS) give(s, level, waiting)
            return
        }
        when {
            !waiting.inHours(level) -> s.pending = null
            waiting.start(level) -> {
                s.playing = waiting
                s.pending = null
                s.tries = 0
                if (s.tale >= 0 && !s.urgent) plays.merge(playKey(s.tale, s.step), 1, Int::plus)
                if (s.hears) hear(level, waiting)
            }
            ++s.tries >= TRIES || level.gameTime - s.queued > WAITS -> give(s, level, waiting)
        }
    }

    /** The stage could not be taken: the step goes on without it, in the record. */
    private fun give(s: Slot, level: ServerLevel, waiting: Stage) {
        s.pending = null
        s.last = level.gameTime
        s.tries = 0
        fire(waiting, false)
    }

    private fun ask(level: ServerLevel, here: Scenes.Here, s: Slot, tale: Tale, player: ServerPlayer) {
        val template = Templates.byId(tale.template) ?: return
        if ((plays[playKey(tale.id, tale.step)] ?: 0) >= PLAYS) return
        if (TaleBodies.bodies(level, tale).none { it.distanceToSqr(player) <= NEAR * NEAR }) return
        val c = ctx(level, here) ?: return
        val stage = try {
            template.scene(c, tale)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("Tale #{} ({}) failed to give a scene", tale.id, tale.template, e)
            s.asked = level.gameTime + 600
            null
        } ?: return
        s.pending = stage
        s.queued = level.gameTime
        s.tries = 0
        s.tale = tale.id
        s.step = tale.step
        s.urgent = false
        s.hears = true
    }

    private fun ctx(level: ServerLevel, here: Scenes.Here): Ctx? {
        val entry = Markets.of(level, here.capital) ?: return null
        return Ctx(level, here, entry, Fortunes.today(level, here.key))
    }

    private fun fire(st: Stage, played: Boolean) {
        val then = st.then ?: return
        st.then = null
        try {
            then(played)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("The end of a tale's scene ({}) failed", st.name, e)
        }
    }

    private fun playKey(tale: Int, step: Int): Long = (tale.toLong() shl 8) or (step.toLong() and 0xFF)

    // ---- Queued by the engine and the helpers ----

    /**
     * Stage 7 of `Tales.finish` (6.7, 4.2): the ending seen. Asks the template for the scene of the tale's ending (once the tale
     * has ended, `Template.scene` answers the scene of its ending, or null for none) and queues it on the capital's stage when a
     * player is within 48 blocks of it; with none near nothing is done (the record is the truth). Once for a tale.
     */
    fun queueEnding(c: Ctx, t: Tale) {
        if (!Tales.scenes() || !endings.add(t.id)) return
        val template = Templates.byId(t.template) ?: return
        val stage = try {
            template.scene(c, t)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("Tale #{} ({}) failed to give the scene of its ending", t.id, t.template, e)
            null
        } ?: return
        val at = stage.anchor()
        if (c.level.getNearestPlayer(at.x, at.y, at.z, NEAR, false) == null) return
        queue(c.level, c.capital, stage, urgent = true, replace = true, hears = true)
    }

    /**
     * Queues [stage] on [capital]'s one stage, ahead of the forty seconds between two scenes (a one-person clip a template asks
     * for, an ending): false if an urgent one is already waiting for the stage (a seed scene waiting is replaced by one that is
     * not), and with [replace] the scene that is playing is cut short first. With [hears] the street turns to watch it ([hear]).
     */
    fun queue(level: ServerLevel, capital: Long, stage: Stage, urgent: Boolean, replace: Boolean = false, hears: Boolean = false): Boolean {
        val s = slots.getOrPut(capital) { Slot() }
        val waiting = s.pending
        if (waiting != null) {
            if (s.urgent) return false
            s.pending = null
            fire(waiting, false)
        }
        // An ending cuts the scene that is playing short: the tale it showed has moved on
        if (replace) s.playing?.let { st ->
            st.cancel(level)
            s.playing = null
            s.last = st.endedAt
            fire(st, true)
        }
        s.pending = stage
        s.queued = level.gameTime
        s.tries = 0
        s.tale = -1
        s.step = -1
        s.urgent = urgent
        s.hears = hears
        return true
    }

    /**
     * `Ctx.act` (4.2): [slot]'s body plays [activity] once as a one-person part of the stage, where they stand (a worker at their
     * post goes back to it), ahead of the forty seconds between two scenes. Nothing if no body is about or no player is within 48
     * blocks of it, or if the capital's stage already has an urgent scene waiting.
     */
    fun act(c: Ctx, t: Tale, slot: String, activity: Activity) {
        if (!Tales.scenes()) return
        val body = TaleBodies.body(c.level, t, slot) ?: return
        if (c.level.getNearestPlayer(body, NEAR) == null) return
        val part = Stage.Part(slot, body, true, listOf(Play(activity)), station = true, seated = body.seat != null)
        queue(c.level, c.capital, Stage("act", listOf(part)), urgent = true)
    }

    // ---- The motes ----

    /**
     * A few warm gold specks rise from the shoulder of the cast member who wants [player]'s help, sent to this player alone
     * (3.3): when they are within 24 blocks, can see them, and are still UNMET or ASKED (no part yet is UNMET too); one burst
     * every two seconds. The one who asks is the first of the cast (a template puts them first). Nothing is sent with
     * townTaleScenes off, and nothing to a player who has said yes.
     */
    private fun motes(level: ServerLevel, player: ServerPlayer, tale: Tale) {
        if ((player.tickCount / 20) % 2 != 0) return
        val part = tale.part(player.uuid)
        if (part != null && part.status != Part.Status.UNMET && part.status != Part.Status.ASKED) return
        val asker = tale.cast.firstOrNull() ?: return
        val body = TaleBodies.body(level, tale, asker.slot) ?: return
        if (!body.isAlive || body.distanceToSqr(player) > MOTES * MOTES || !player.hasLineOfSight(body)) return
        val r = level.random
        // Over the left shoulder, a little behind
        val yaw = Math.toRadians(body.yBodyRot.toDouble())
        val x = body.x + Math.cos(yaw) * 0.3 + Math.sin(yaw) * 0.08
        val z = body.z + Math.sin(yaw) * 0.3 - Math.cos(yaw) * 0.08
        val y = body.y + body.eyeHeight - 0.25
        repeat(3) {
            level.sendParticles(
                player, Townsfolk.TALE_MOTE.get(), false, false,
                x + (r.nextDouble() - 0.5) * 0.16, y + r.nextDouble() * 0.1, z + (r.nextDouble() - 0.5) * 0.16,
                0, (r.nextDouble() - 0.5) * 0.01, 0.018 + r.nextDouble() * 0.012, (r.nextDouble() - 0.5) * 0.01, 1.0,
            )
        }
    }

    // ---- The street hears ----

    /**
     * The street hears (4.3): when a seed scene or an ending plays, up to two free adults within 16 blocks of it turn and watch (a
     * gaze, then folded arms or a nod) and are let go as it ends; the first may say a line of the town's old talk (a fortune or a
     * deed, through [Words.about]) so the usual talk goes on round it. They are not cast: the stage takes them as bystanders.
     */
    fun hear(level: ServerLevel, stage: Stage) {
        if (!stage.playing) return
        val at = stage.anchor()
        val busy = stage.taken().map { it.body } + stage.onlookers()
        for ((i, p) in Sets.freeAdults(level, at, HEARS, busy).take(HEARERS).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn({ at })
            m += Stage.Await("-", 300, Activity.GAZE, { at })
            if (i == 0) m += Now { who -> Words.about(level, who, if (level.random.nextBoolean()) Kind.FORTUNE else Kind.DEEDS, null, "the street hears a tale") }
            if (i == 1 || level.random.nextBoolean()) m += Play(Activity.NOD, at = { at })
            m += Stage.Await("-", 3000, Activity.ARMS_FOLDED, { at })
            stage.onlook(level, Stage.Part("hears${i + 1}", p, false, m))
        }
    }

    // ---- The world's second ----

    /** One line in [Tales.register]: the `tale` commands of Part B, and the once-a-second tidy of the held residents, the made figures and the stages. */
    fun register() {
        TaleSceneCommand.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % 20 != 11) return
        val level = server.overworld()
        guarded(level, "the tidy of the tale bodies") { TaleBodies.tidy(server) }
        guarded(level, "the upkeep of the stages") { upkeep(level) }
    }

    /** Game time of the last warning from [guarded], so a failure that comes every second is in the log once a minute. */
    private var warned = -WARN_EVERY

    /**
     * Runs [block] from the server's tick or a body's: one that fails is logged (once a minute at most) and the world goes on, as a
     * tick that throws takes the server down. Whatever it left undone, the record is the truth and the next second sees to it again.
     */
    internal fun guarded(level: ServerLevel, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            if (level.gameTime - warned >= WARN_EVERY) {
                warned = level.gameTime
                Guildmark.LOGGER.warn("Tales: {} failed", what, e)
            }
        }
    }

    /**
     * Once a second whether a player is in a capital or not: a stage that is over is heard to be (its `then`), one that has gone stale
     * is cut off, and, with the scenes switched off, the capitals' stages are let go (the `tale scene` command's are the game master's own).
     */
    private fun upkeep(level: ServerLevel) {
        val on = Tales.scenes()
        for (s in slots.values) {
            if (!on) {
                s.pending?.let { fire(it, false) }
                s.pending = null
                s.playing?.cancel(level)
            }
            val st = s.playing ?: continue
            if (st.finished) {
                s.playing = null
                s.last = st.endedAt
                fire(st, true)
            } else if (st.stale(level)) {
                st.cancel(level)
            }
        }
        manual.removeAll { it.finished }
        for (st in manual.toList()) if (st.stale(level)) st.cancel(level)
    }

    // ---- For the commands and the switch ----

    /** Notes a stage played by the `tale scene` command, so it can be listed and let go. */
    fun note(stage: Stage) {
        manual.removeAll { it.finished }
        manual += stage
    }

    /** The stages that are playing or waiting just now: capital key and stage. */
    fun stages(): List<Pair<Long, Stage>> {
        val out = ArrayList<Pair<Long, Stage>>()
        for ((key, s) in slots) {
            s.playing?.let { out += key to it }
            s.pending?.let { out += key to it }
        }
        manual.removeAll { it.finished }
        for (st in manual) out += 0L to st
        return out
    }

    /**
     * Lets every stage go now, back to their day (`tale figures release`; the switch going off does the same once a second, for the
     * capitals' stages, in [upkeep]). The bodies are [TaleBodies.pause]'s.
     */
    fun pause(level: ServerLevel) {
        for (s in slots.values) {
            s.pending?.let { fire(it, false) }
            s.pending = null
            s.playing?.cancel(level)
            s.playing = null
        }
        for (st in manual) st.cancel(level)
        manual.clear()
    }

    /** Everything forgotten, as the server stops: the stages, the held residents' marks and the made figures with them. */
    fun forget() {
        slots.clear()
        plays.clear()
        endings.clear()
        manual.clear()
        TaleBodies.forget()
    }

    /** The loaded bodies of [tale]'s cast within [r] blocks of [at]. */
    internal fun near(level: ServerLevel, tale: Tale, at: Vec3, r: Double): List<Townsperson> =
        TaleBodies.bodies(level, tale).filter { it.distanceToSqr(at) <= r * r }
}
