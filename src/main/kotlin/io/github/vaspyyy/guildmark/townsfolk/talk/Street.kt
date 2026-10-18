package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Quote
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.StallTalk
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Audience
import io.github.vaspyyy.guildmark.townsfolk.Chat
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Follow
import io.github.vaspyyy.guildmark.townsfolk.Hear
import io.github.vaspyyy.guildmark.townsfolk.Lines
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Social
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.Work
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Talk out in the street (living-world/design-voices.md section 2, "In passing" and "To each other"), on the rhythms
 * the town already keeps: [Social.greet]'s notice of each player every 140 ticks, the steps of a stall keeper's and a
 * worker's day, the guard's stops on the beat, the tavern's moods and the turns of a chat ([Exchange]).
 *
 * In passing, whoever is nearest in front of a player (or knows them, sees them and is within 10 blocks) notices them
 * as they know them: a stranger mostly with a nod or a glance and a line one time in three; someone met with a
 * greeting, by name if they know it; a friend now and then with a topic too; the wary and the hostile coldly. Walkers
 * wave or nod over the walk without stopping, the idle turn to the player for a moment, the seated turn their heads,
 * and those at work only look up: they say a trade line only to a player who has watched them for two seconds. A
 * keeper calls what their stall really sells and what's really cheap today. Each townsperson notices each player once
 * an in-game hour, unless they have something new (a topic of 0.7 or more: a deed done today, big news); and while two
 * others close by are already talking, a nod does for a line, so a crowded square never talks all at once.
 *
 * Acquaintances (the same household, the same trade, a visit lately) passing each other near a player nod or wave,
 * and one time in three say good day.
 *
 * Everything is worked out on the server when it's about to be said; the topics are looked up only then. The
 * townsfolkBarks setting turns this off, and the old greetings and chats answer as before.
 */
object Street {
    // --- The rates -----------------------------------------------------------------------------------------------

    /** In front of a player, this near (the old greeting's reach); someone who knows them and can see them, this near. */
    private const val FRONT = 6.0
    private const val KNOWN = 10.0

    /** What counts as in front of the player (the old greeting's cone, 60 degrees either way across the ground). */
    private const val AHEAD = 0.5

    /** Someone who knows the player sees them if they face within 80 degrees of their way (and nothing's between). */
    private const val SEES = 0.17

    /** The most who know the player, of those not in front, looked into for each notice, nearest first. */
    private const val LOOKS = 4

    /** Ticks between two notices of the same player: Social.greet's rhythm. */
    private const val RHYTHM = 140L

    /** Each townsperson notices each player once an in-game hour (ticks), unless they've something new: a topic scoring this. */
    private const val HOUR = 1000L
    private const val NEW = 0.7

    /** How often someone who isn't due may be looked into for something new, for each player (ticks). */
    private const val LOOKED = 600L

    /** A friend's topic after the greeting comes one time in this many, if one scores this much. */
    private const val FRIENDLY = 3
    private const val FRIEND_TOPIC = 0.3

    /** A stranger gets a line one time in this many; the rest, a nod or a glance. */
    private const val STRANGERS = 3

    /** Watching someone at work: from within this far, looking this nearly at them (about 30 degrees off), for this long. */
    private const val WATCH = 8.0
    private const val GAZE = 0.86
    private const val WATCHED = 40L

    /** A look away for no longer than this doesn't end the watching. */
    private const val BLINK = 20L

    /** Hanging about: within this far of one spot for this long (ticks), as a guard sees it. */
    private const val STIR = 2.0
    private const val STILL = 200L

    /** Acquaintances passing: this near each other, within this far of a player, each once in this long; looked for this often. */
    private const val PASS = 4.0
    private const val PLAYERS = 24.0
    private const val PASSED = 600L
    private const val PASS_EVERY = 40

    /** A visit makes neighbours acquaintances for this many days. */
    private const val VISIT_DAYS = 3L

    /** Ticks after the first of two acquaintances nods before the other does. */
    private const val SECOND = 8

    /** A line goes unsaid while this many others within [CROWD] blocks are saying something: three at most at once. */
    private const val HUSH = 2
    private const val CROWD = 10.0

    /** Ticks of a glance in passing, of turning to a player, between two parts of a line, and for a take with no length. */
    private const val GLANCE = 30
    private const val ATTEND = 40
    private const val GAP = 6
    private const val UNTIMED = 40

    /** Ticks a listener looks round at the player the news is about. */
    private const val LOOK_ROUND = 40

    /** What a townsperson keeps doing while they can't stop for anyone: the cry, the bell, a song, running. */
    private val PERFORMING = setOf(Activity.FLEE, Activity.ANNOUNCE, Activity.RING_BELL, Activity.LUTE, Activity.MINSTREL)

    /** Talking with their hands: kept up while they turn to a player to say something (as Routine lets it). */
    private val TALKING = setOf(Activity.TALK, Activity.EXPLAIN, Activity.GOSSIP, Activity.RECOUNT)

    /** The keepers' calls of their wares that are true at any stall (merchant_4 and _5 only where apples and fish are sold). */
    private val CALLS = listOf("merchant_0", "merchant_1", "merchant_2", "merchant_4", "merchant_5")

    /** The innkeeper's words across the bar. */
    private val BAR = listOf("inn_0", "inn_1", "inn_2", "inn_3")

    /** The guard's words on the beat. */
    private val GUARD = listOf("guard_0", "guard_1", "guard_2", "guard_3", "guard_4")

    /** The old small talk. */
    private val GOSSIP = (0 until 12).map { "gossip_$it" }

    /** How long the lines said are kept for `/guildmark talk street` (ticks), and at most how many. */
    private const val KEPT = 2400L
    private const val KEEP = 400

    // --- What's kept (in memory only) ------------------------------------------------------------------------------

    /** When each player was last noticed. */
    private val noticed = HashMap<UUID, Long>()

    /** For each player, when each townsperson last noticed them, and when each was last looked into for something new. */
    private val barked = HashMap<UUID, HashMap<UUID, Long>>()
    private val looked = HashMap<UUID, HashMap<UUID, Long>>()

    /** What a player's been looking at: who, since when, last seen when; and where they've stood still since when. */
    private class Eye(var who: UUID?, var since: Long, var last: Long, var spot: Vec3, var still: Long)

    private val eyes = HashMap<UUID, Eye>()

    /** When each townsperson last nodded to an acquaintance in passing. */
    private val nodded = HashMap<UUID, Long>()

    /** The day each resident last called at each lodging (resident id and lodging id together). */
    private val visits = HashMap<Long, Long>()

    /** Things to do a few ticks on: the second part of a line, the second nod, putting a gesture by. */
    private class Beat(val at: Long, val run: () -> Unit)

    private val beats = ArrayList<Beat>()
    private var clock = 0L

    /** The parts of the street talk that have failed (each logged once). */
    private val broken = HashSet<String>()

    /** A line said in the street, for `/guildmark talk street`. */
    private class Said(val at: Long, val who: String, val to: UUID?, val line: String, val why: String, val pos: Vec3)

    private val said = ArrayDeque<Said>()

    private var started = false

    /** Sets the street talk going: the beats, and forgetting players as they leave. Once, at setup (Townsfolk.init). */
    fun init() {
        if (started) return
        started = true
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick() }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { forget(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> clear() }
    }

    /** Whether townsfolk talk in passing and to each other from the topics (townsfolkTalk and townsfolkBarks both on). */
    fun on(): Boolean = Talk.enabled() && Talk.READY && barks()

    private fun barks(): Boolean = try {
        GuildmarkConfig.TOWNSFOLK_BARKS.get()
    } catch (e: IllegalStateException) {
        true
    }

    private fun tick() {
        clock++
        if (beats.isEmpty()) return
        val due = beats.filter { it.at <= clock }
        if (due.isEmpty()) return
        beats.removeAll { it.at <= clock }
        for (beat in due) {
            try {
                beat.run()
            } catch (e: Exception) {
                failed("a line in the street", e)
            }
        }
    }

    /** Does [run] in [ticks] ticks. */
    private fun later(ticks: Int, run: () -> Unit) {
        beats += Beat(clock + maxOf(1, ticks), run)
    }

    /** [where] failed: it's logged the first time, and skipped. */
    private fun failed(where: String, e: Exception) {
        if (broken.add(where)) Guildmark.LOGGER.error("Townsfolk talk: {} failed and is skipped", where, e)
    }

    /**
     * Runs [what], the street talk's part in [where] (from the town's own ticks); should it throw, it's logged once and
     * [fallback] does instead, so a fault here costs a line, never the server's tick.
     */
    private inline fun <T> guarded(where: String, fallback: T, what: () -> T): T = try {
        what()
    } catch (e: Exception) {
        failed(where, e)
        fallback
    }

    /** [guarded], for a part with nothing to give back. */
    private inline fun guarded(where: String, what: () -> Unit) {
        try {
            what()
        } catch (e: Exception) {
            failed(where, e)
        }
    }

    private fun forget(player: UUID) {
        noticed.remove(player)
        barked.remove(player)
        looked.remove(player)
        eyes.remove(player)
    }

    private fun clear() {
        noticed.clear()
        barked.clear()
        looked.clear()
        eyes.clear()
        nodded.clear()
        visits.clear()
        beats.clear()
        said.clear()
    }

    // --- In passing ------------------------------------------------------------------------------------------------

    /** How someone noticing a player is placed to: what they can do about it without dropping what they're at. */
    private enum class How { SEATED, KEEPING, WORKING, WALKING, IDLE, BUSY }

    /** What a notice comes to: the lines said (none for a nod or a glance), the gesture they're said with, and why, for the log. */
    private class Notice(val lines: List<Line>, val gesture: Activity?, val why: String)

    /**
     * Every ten ticks for each player, from [Social.greet]: what they're watching, acquaintances passing near them
     * (every 40), and every 140 ticks someone noticing them.
     */
    fun greet(level: ServerLevel, player: ServerPlayer): Unit = guarded("noticing players in passing") {
        if (player.isSpectator) return
        val now = level.gameTime
        watch(level, player, now)
        if (player.tickCount % PASS_EVERY == 0) passing(level, player, now)
        if (now - (noticed[player.uuid] ?: Long.MIN_VALUE / 2) < RHYTHM) return
        if (bark(level, player, now)) noticed[player.uuid] = now
    }

    /** Whether [p] is free to notice anyone: up, out of danger, not talking, in nobody's charge, nothing said just now. */
    private fun free(p: Townsperson, now: Long): Boolean =
        p.isAlive && !p.isSleeping && p.speech.isEmpty() && now >= p.nextSpeech && p.director == null && !p.isLeader &&
            p.captiveOf < 0 && !p.watcher && p.target == null && p.attending == null && p.activity !in PERFORMING &&
            p.routine.task.let { it !is Chat && it !is Audience && it !is Hear && it !is Follow }

    /** How [p] is placed just now. */
    private fun how(p: Townsperson): How {
        val task = p.routine.task
        return when {
            p.seat != null -> How.SEATED
            p.routine.anchored -> if (keeping(p)) How.KEEPING else How.WORKING
            !p.navigation.isDone -> How.WALKING
            task == null || task.leisure -> How.IDLE
            else -> How.BUSY
        }
    }

    /** A merchant at their stall. */
    private fun keeping(p: Townsperson): Boolean =
        p.role == TownRole.MERCHANT && p.routine.task is Work && p.post?.let { p.blockPosition().distSqr(it) <= 2.0 } == true

    /**
     * Someone near [player] notices them: the nearest due (in front, or knowing them and seeing them), else, now and
     * then, the nearest if they've something new. True if anyone was near enough to notice them at all.
     */
    private fun bark(level: ServerLevel, player: ServerPlayer, now: Long): Boolean {
        val look = player.getViewVector(1f)
        val near = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(KNOWN, 3.0, KNOWN)) { free(it, now) }
        if (near.isEmpty()) return false
        val front = ArrayList<Townsperson>()
        val aside = ArrayList<Townsperson>()
        for (p in near) {
            val d2 = p.distanceToSqr(player)
            if (d2 > KNOWN * KNOWN) continue
            val to = p.position().subtract(player.position()).normalize()
            if (d2 <= FRONT * FRONT && to.x * look.x + to.z * look.z > AHEAD) front += p
            else if (sees(p, player)) aside += p
        }
        aside.sortBy { it.distanceToSqr(player) }
        val ring = aside.take(LOOKS).filter { knows(level, it, player) }
        val all = (front + ring).sortedBy { it.distanceToSqr(player) }
        if (all.isEmpty()) return false
        var first: Townsperson? = null
        for (p in all) {
            val seen = if (p in front) player.hasLineOfSight(p) else p.hasLineOfSight(player)
            if (!seen) continue
            if (due(player.uuid, p, now)) {
                notice(level, player, p, null, false)
                return true
            }
            if (first == null) first = p
        }
        // Nobody's due: the nearest may still have something new to tell (looked into once in a while)
        val p = first ?: return false
        val mine = looked.getOrPut(player.uuid) { HashMap() }
        if (now - (mine[p.uuid] ?: Long.MIN_VALUE / 2) < LOOKED) return true
        mine[p.uuid] = now
        prune(mine, now, LOOKED)
        if (hushed(p)) return true
        val how = how(p)
        val ask = Talk.ask(level, p, player, Occasion.BARK)
        val watched = watcher(level, p)?.uuid == player.uuid
        pick(ask, NEW) { fits(it, how) && (how != How.WORKING && how != How.BUSY || it.kind != Kind.TRADE || watched) }?.let { notice(level, player, p, it, false) }
        return true
    }

    /** Whether [p] faces [other]'s way (within 80 degrees of it), as they'd see them. */
    private fun sees(p: Townsperson, other: Entity): Boolean {
        val yaw = Math.toRadians(p.yHeadRot.toDouble())
        val dx = other.x - p.x
        val dz = other.z - p.z
        val d = sqrt(dx * dx + dz * dz)
        if (d < 0.01) return true
        return (-sin(yaw) * dx + cos(yaw) * dz) / d > SEES
    }

    private fun resident(level: ServerLevel, p: Townsperson): Resident? = Days.life(p)?.resident ?: Residents.get(level).of(p)

    /** Whether [p] knows [player] at all: has met them, or heard of them. */
    private fun knows(level: ServerLevel, p: Townsperson, player: ServerPlayer): Boolean =
        TownTalk.familiarity(level, resident(level, p), player) != Familiarity.STRANGER

    /** Whether [p] is due to notice [player] again: not within the hour. */
    private fun due(player: UUID, p: Townsperson, now: Long): Boolean = barked[player]?.get(p.uuid)?.let { now - it >= HOUR } ?: true

    private fun mark(player: UUID, p: Townsperson, now: Long) {
        val mine = barked.getOrPut(player) { HashMap() }
        mine[p.uuid] = now
        prune(mine, now, HOUR)
    }

    private fun prune(map: HashMap<UUID, Long>, now: Long, keep: Long) {
        if (map.size > 512) map.values.removeIf { now - it >= keep }
    }

    /** [p] notices [player]: [fresh] is the new topic they've to tell, if that's why; [forced] by the test command (no chances, no hush). */
    private fun notice(level: ServerLevel, player: ServerPlayer, p: Townsperson, fresh: Topic?, forced: Boolean): Notice {
        val now = level.gameTime
        mark(player.uuid, p, now)
        val how = how(p)
        val ask = Talk.ask(level, p, player, Occasion.BARK)
        val n = when {
            fresh != null -> topical(ask, fresh, "something new")
            !forced && hushed(p) -> Notice(listOf(), nod(ask), "others talking")
            else -> content(level, ask, how, forced)
        }
        perform(player, p, how, n)
        return n
    }

    /** What [ask]'s speaker does on noticing the player, placed as they are ([how]). */
    private fun content(level: ServerLevel, ask: Ask, how: How, forced: Boolean): Notice {
        val p = ask.speaker
        val player = ask.player
        val r = p.random
        val f = ask.familiarity
        val known = f == Familiarity.MET || f == Familiarity.ACQUAINTED || f == Familiarity.FRIEND
        val where = how.name.lowercase(Locale.ROOT)
        when (how) {
            // At work they only look up: a word to someone they know, and their trade only to someone who's watched a while
            How.WORKING, How.BUSY -> {
                if (player != null && watcher(level, p)?.uuid == player.uuid) work(ask)?.let { return Notice(listOf(it), null, "watched at work") }
                return Notice(if (known) listOfNotNull(hello(ask)) else listOf(), null, "${name(f)}, $where")
            }
            // At the stall: a word to someone they know; what's for sale to anyone else, now and then
            How.KEEPING -> {
                if (known) return Notice(listOfNotNull(hello(ask)), Activity.NOD, "${name(f)}, at the stall")
                if (forced || r.nextBoolean()) call(level, p, player)?.let { return Notice(listOf(it), Activity.CALL_OUT, "calling the wares") }
                return Notice(listOf(), null, "${name(f)}, at the stall")
            }
            else -> {}
        }
        // A faction's own word for one of theirs, or for its foes
        if (Faction.of(p.role) != null && (forced || r.nextBoolean())) {
            pick(ask, Topics.LEAST) { it.kind == Kind.FACTION }?.let { return topical(ask, it, "faction, $where") }
        }
        return when (f) {
            Familiarity.HOSTILE -> (if (forced || r.nextBoolean()) pick(ask, Topics.LEAST) { cold(it) && fits(it, how) } else null)
                ?.let { topical(ask, it, "hostile, $where") }
                ?: Notice(listOf(), if (how == How.IDLE) Activity.RECOIL else null, "hostile, $where")
            Familiarity.WARY -> pick(ask, Topics.LEAST) { cold(it) && fits(it, how) }?.let { topical(ask, it, "wary, $where") }
                ?: Notice(listOfNotNull(if (forced || r.nextBoolean()) hello(ask) else null), Activity.NOD, "wary, $where")
            Familiarity.STRANGER -> {
                if (!forced && r.nextInt(STRANGERS) != 0) return Notice(listOf(), nod(ask), "stranger, $where")
                (if (r.nextBoolean()) pick(ask, Topics.LEAST) { idle(it) && fits(it, how) } else null)?.let { return topical(ask, it, "stranger, $where") }
                Notice(listOfNotNull(hello(ask)), greeting(ask), "stranger, $where")
            }
            Familiarity.HEARD_OF -> {
                if (!forced && r.nextBoolean()) return Notice(listOf(), nod(ask), "heard of, $where")
                pick(ask, Topics.LEAST) { it.kind == Kind.DEEDS && fits(it, how) }?.let { return topical(ask, it, "heard of, $where") }
                Notice(listOfNotNull(hello(ask)), greeting(ask), "heard of, $where")
            }
            Familiarity.MET, Familiarity.ACQUAINTED -> Notice(listOfNotNull(hello(ask)), greeting(ask), "${name(f)}, $where")
            Familiarity.FRIEND -> {
                val topic = if (how != How.WALKING && (forced || r.nextInt(FRIENDLY) == 0)) pick(ask, FRIEND_TOPIC) { it.kind != Kind.FACTION } else null
                when {
                    topic == null -> Notice(listOfNotNull(hello(ask)), greeting(ask), "friend, $where")
                    // News comes straight out, lead-in and all
                    topic.rumour != null -> topical(ask, topic, "friend with news, $where")
                    else -> Notice(listOfNotNull(hello(ask)) + said(ask, topic), greeting(ask), "friend with a topic, $where")
                }
            }
        }
    }

    private fun name(f: Familiarity): String = f.name.lowercase(Locale.ROOT).replace('_', ' ')

    /** [topic] as a notice: its parts (a lead-in before news), with its gesture. */
    private fun topical(ask: Ask, topic: Topic, why: String): Notice =
        Notice(said(ask, topic), topic.gesture ?: Talk.gesture(topic), "$why: ${topic.id}")

    /** Whether [topic] can be said placed as they are: a walker says one part, and no news (it wants a lead-in, and they're going). */
    private fun fits(topic: Topic, how: How): Boolean = how != How.WALKING || topic.rumour == null && topic.say.parts.size == 1

    /** What a stranger might say in passing: not what they're doing, nor their trade, their household or their dealings. */
    private fun idle(topic: Topic): Boolean =
        topic.kind != Kind.DOING && topic.kind != Kind.TRADE && topic.kind != Kind.HOUSEHOLD && topic.kind != Kind.DEALINGS && topic.kind != Kind.HOMESTEAD

    /** A cold word: a grudge, a wrong they know of, a faction's scorn. */
    private fun cold(topic: Topic): Boolean = topic.tone < 0 && (topic.kind == Kind.DEALINGS || topic.kind == Kind.DEEDS || topic.kind == Kind.FACTION)

    /** A nod, a touch of the brow (a child's wave or hop), or half the time just a look. */
    private fun nod(ask: Ask): Activity? {
        val p = ask.speaker
        val r = p.random
        if (r.nextBoolean()) return null
        return when {
            p.role == TownRole.CHILD -> if (r.nextBoolean()) Activity.WAVE else Activity.JUMP
            r.nextBoolean() -> Activity.TIP_HAT
            else -> Activity.NOD
        }
    }

    /** The gesture a greeting goes with, as a talk opens with it: a bow from a noble, a guard's salute to a hero, a friend's wave. */
    private fun greeting(ask: Ask): Activity {
        val p = ask.speaker
        val f = ask.familiarity
        return when {
            p.role == TownRole.CHILD -> if (p.random.nextInt(3) == 0) Activity.JUMP else Activity.WAVE
            p.role == TownRole.NOBLE -> if (f == Familiarity.FRIEND || f == Familiarity.HEARD_OF || Talk.hero(ask)) Activity.BOW else Activity.TIP_HAT
            (p.role == TownRole.GUARD || p.role == TownRole.KNIGHT) && Talk.hero(ask) -> Activity.SALUTE
            f == Familiarity.FRIEND -> Activity.WAVE
            f == Familiarity.WARY -> Activity.NOD
            else -> listOf(Activity.WAVE, Activity.NOD, Activity.TIP_HAT)[p.random.nextInt(3)]
        }
    }

    /** [p] does what [n] says, placed as they are, toward [player]. */
    private fun perform(player: ServerPlayer, p: Townsperson, how: How, n: Notice) {
        val total = n.lines.sumOf { it.ticks + GAP }
        when (how) {
            // The idle turn to them a moment: they talk with their hands while they say it, or a gesture plays through,
            // then they stand (Routine)
            How.IDLE -> {
                p.attend(player, maxOf(ATTEND, total + 10))
                val g = n.gesture
                if (g in TALKING && n.lines.isNotEmpty()) play(p, g!!, total) else brief(g)?.let { play(p, it) }
            }
            // The seated turn their heads, and say it with their seated talk, laugh or lean-in
            How.SEATED -> {
                p.attend(player, maxOf(ATTEND, total + 10))
                if (n.lines.isNotEmpty()) (Talk.seated(p, n.gesture ?: Activity.TALK) ?: Activity.SIT_TALK).let { play(p, it, total) }
            }
            // Walkers don't stop: a wave or a nod over the walk, if their hands are free
            How.WALKING -> {
                p.glance(player, maxOf(GLANCE, total))
                if (n.lines.isNotEmpty() || n.gesture != null) {
                    val wave = n.gesture == Activity.WAVE || n.gesture == Activity.JUMP
                    if (p.activity == Activity.NONE || p.activity == Activity.STROLL) play(p, if (wave) Activity.WAVE_WALK else Activity.NOD_WALK)
                }
            }
            // At work, a look up; a keeper at the stall calls out, or nods, and goes back to the stall
            else -> {
                p.glance(player, maxOf(GLANCE, total + 10))
                if (how == How.KEEPING) brief(n.gesture)?.let { play(p, it) }
            }
        }
        speak(p, player, n.lines, "bark: ${n.why}")
    }

    /** [g] as made to someone stopped for a moment: a one-shot (a loop would be cut off at once), a nod for talk and pointing. */
    private fun brief(g: Activity?): Activity? = when {
        g == null -> null
        g == Activity.ARMS_FOLDED -> Activity.WAVE_OFF
        g == Activity.POINT || g.loop -> Activity.NOD
        else -> g
    }

    /**
     * [p] makes [g] now and goes back to what they were at once it's over ([hold] ticks for a loop): the loop they had
     * going, else sitting or standing. Nothing if they're at it already.
     */
    private fun play(p: Townsperson, g: Activity, hold: Int = g.ticks) {
        val was = p.activity
        if (was == g && g.loop) return
        p.act(g)
        val start = p.activityStart
        later(maxOf(hold, g.ticks)) {
            if (p.isAlive && p.director == null && p.activity == g && p.activityStart == start) {
                p.act(if (was.loop && was != g) was else if (p.seat != null) Activity.SIT else Activity.NONE)
            }
        }
    }

    // --- What they say ----------------------------------------------------------------------------------------------

    /** One part of a line as it's said: the line as it goes out ([Speech]), its voiced key ("" for text alone), its length. */
    internal class Line(val speech: String, val voiced: String, val ticks: Int)

    /**
     * Says [lines] one after another, spaced by their lengths, in [player]'s hearing ([toThem]: to them, not overheard);
     * [before] hears each part's index as it's said. [why] goes in the log.
     */
    internal fun speak(p: Townsperson, player: ServerPlayer?, lines: List<Line>, why: String, toThem: Boolean = true, before: ((Int) -> Unit)? = null) {
        val first = lines.firstOrNull() ?: return
        before?.invoke(0)
        if (!p.say(first.speech, force = true, ticks = first.ticks + 20L)) return
        heard(p, player, first, why, toThem)
        var at = first.ticks + GAP
        for (i in 1 until lines.size) {
            val line = lines[i]
            later(at) {
                if (p.isAlive && !p.isRemoved && p.director == null) {
                    before?.invoke(i)
                    if (p.say(line.speech, force = true, ticks = line.ticks + 20L)) heard(p, player, line, why, toThem)
                }
            }
            at += line.ticks + GAP
        }
    }

    /**
     * [p] said [line] in [player]'s hearing ([toThem]: to them): the take is out for a while, from them and for that
     * player, so neither says nor hears it again soon; and the log has it.
     */
    internal fun heard(p: Townsperson, player: ServerPlayer?, line: Line, why: String, toThem: Boolean = true) {
        if (line.voiced.isNotEmpty()) Topics.spoke(p, player, line.voiced)
        val now = p.level().gameTime
        said.addLast(Said(now, "${p.name.string} (${p.role.id})", if (toThem) player?.uuid else null, line.speech, why, p.position()))
        while (said.size > KEEP || said.isNotEmpty() && now - said.first().at > KEPT) said.removeFirst()
    }

    /** [topic]'s parts as said, as Talk says them (named for the listener where they know the name); news in passing gets its lead-in. */
    internal fun said(ask: Ask, topic: Topic): List<Line> {
        val lead = if (topic.rumour != null && ask.occasion == Occasion.BARK && topic.say.parts.size == 1) frame(ask, topic.rumour) else null
        return listOfNotNull(lead) + lines(ask, topic.say)
    }

    private fun lines(ask: Ask, say: Utterance): List<Line> = say.parts.mapNotNull { part ->
        val name = if (part.named && ask.knows?.named == true) ask.player?.gameProfile?.name else null
        val key = Speech.key(part.line)
        val speech = when {
            part.line.isEmpty() -> part.detail ?: ""
            name != null && '|' !in part.line -> Speech.withDetail(Speech.of(part.line, "=$name"), part.detail)
            else -> Speech.withDetail(part.line, part.detail)
        }
        if (speech.isEmpty()) null else Line(speech, key, if (key.isEmpty()) UNTIMED else Talk.ticks(ask.speaker, key))
    }

    /** The lead-in for rumour [rumour] as [ask]'s speaker had it ("Word in the tavern is..."), as a talk gives it. */
    private fun frame(ask: Ask, rumour: Int): Line? {
        val r = ask.resident
        val known = r?.let { TownTalk.known(ask.level, it).firstOrNull { k -> k.rumour.id == rumour } }
        val family = when {
            ask.speaker.role == TownRole.CHILD -> "child.news"
            known == null -> "news.frame.they"
            known.heard.warp >= 3 -> "news.frame.tall"
            known.rumour.kind.startsWith("slight.") && r.has(Trait.CHATTY) != true -> "news.frame.secret"
            known.heard.warp == 2 -> "news.frame.they"
            else -> "news.frame." + when (known.heard.venue) {
                Venue.SEEN -> "seen"
                Venue.HOUSEHOLD -> "home"
                Venue.TAVERN -> "tavern"
                Venue.WELL -> "well"
                Venue.MARKET -> "market"
                Venue.WORK -> "work"
                Venue.CRIER -> "crier"
                Venue.AFAR -> "afar"
                Venue.VISIT, Venue.TEMPLE, Venue.STROLL, Venue.STREET -> "they"
            }
        }
        val key = Topics.take(ask, family) ?: return null
        // Who told them, when it was one of the town (in grey, as a talk gives it)
        val from = known?.heard?.takeIf { it.from >= 0 && it.venue != Venue.SEEN }?.let { Residents.get(ask.level)[it.from]?.name }
        return Line(Speech.withDetail(key, from?.let { Speech.of("talk.detail.news.from", "=$it") }), key, Talk.ticks(ask.speaker, key))
    }

    /** A take of [family] [ask]'s player hasn't heard lately (named for them if [named] and they know the name); null if all have been. */
    private fun family(ask: Ask, family: String, named: Boolean = false): Line? {
        val f = TalkLines.FAMILIES[family] ?: return null
        val avoid = Topics.avoid(ask.speaker, ask.player)
        val keys = (0 until f.count).map { "talk.${family}_$it" }.filter { it !in avoid }
        if (keys.isEmpty()) return null
        val key = keys[ask.speaker.random.nextInt(keys.size)]
        val name = if (named && f.named && ask.knows?.named == true) ask.player?.gameProfile?.name else null
        return Line(if (name != null) Speech.of(key, "=$name") else key, key, Talk.ticks(ask.speaker, key))
    }

    /** One of [keys] (takes, or old lines) true for [p] now and not heard lately from them or by [player]; null if none is. */
    private fun choose(p: Townsperson, player: ServerPlayer?, keys: List<String>): Line? {
        val avoid = Topics.avoid(p, player)
        val pool = keys.filter { it !in avoid && OldLines.holds(p, it, player) }
        if (pool.isEmpty()) return null
        val key = pool[p.random.nextInt(pool.size)]
        return Line(key, key, Talk.ticks(p, key))
    }

    /**
     * A greeting that knows the player, as a talk opens with one (Talk.greeting): a friend's, again today, after a
     * long while, heard of, a hero, shy, wary, or by the hour and the sky; a child's own. Null for the hostile, and
     * when every wording fit has been heard lately (a nod does instead).
     */
    private fun hello(ask: Ask): Line? {
        val p = ask.speaker
        val f = ask.familiarity
        if (p.role == TownRole.CHILD) return when {
            f == Familiarity.WARY || f == Familiarity.HOSTILE -> family(ask, "child.wary")
            // "Are you a real adventurer?"
            f == Familiarity.STRANGER && p.random.nextInt(3) == 0 -> choose(p, ask.player, listOf("child_2"))
            else -> null
        } ?: family(ask, "child.greet")
        val known = ask.knows
        val today = Days.dayOf(ask.level.overworldClockTime)
        return when (f) {
            Familiarity.HOSTILE -> null
            // "Oh. It's you." (the others want the player to have spoken first)
            Familiarity.WARY -> choose(p, ask.player, listOf("talk.greet.wary_2"))
            Familiarity.FRIEND -> family(ask, "greet.friend", named = true)
            Familiarity.MET, Familiarity.ACQUAINTED -> when {
                known != null && known.seen.toLong() == today -> family(ask, "greet.again", named = true)
                known != null && today - known.seen >= 7 -> family(ask, "greet.long", named = true)
                else -> null
            } ?: byHour(ask, stranger = false)
            Familiarity.HEARD_OF -> family(ask, if (Talk.hero(ask)) "greet.hero" else "greet.heard")
            Familiarity.STRANGER -> (if (ask.resident?.has(Trait.SHY) == true && p.random.nextBoolean()) family(ask, "greet.shy") else null)
                ?: byHour(ask, stranger = true)
        }
    }

    /** The hour's greeting, from its family and the old greetings true now (greet_3 and greet_6 to strangers, by day). */
    private fun byHour(ask: Ask, stranger: Boolean): Line? {
        val hour = Say.hour(ask.level)
        val family = Talk.byHour(ask)
        val takes = family?.let { fam -> TalkLines.FAMILIES[fam]?.let { f -> (0 until f.count).map { "talk.${fam}_$it" } } }.orEmpty()
        val old = when {
            hour >= 5.0 && hour < 12.0 -> listOf("greet_1", "greet_0", "greet_2", "greet_7")
            hour >= 12.0 && hour < 17.0 -> listOf("greet_0", "greet_2", "greet_7")
            hour >= 17.0 && hour < 21.0 -> listOf("night_1", "greet_0", "night_2")
            else -> listOf("night_0", "night_1", "night_2")
        } + if (stranger) listOf("greet_3", "greet_6") else listOf()
        return choose(ask.speaker, ask.player, takes + old)
    }

    /** What someone watched at work says of it: how their trade's going (a topic), or one of their trade's old lines true now. */
    private fun work(ask: Ask): Line? {
        val p = ask.speaker
        if (p.random.nextBoolean()) pick(ask, Topics.LEAST) { it.kind == Kind.TRADE && it.say.parts.size == 1 }?.let { t -> lines(ask, t.say).firstOrNull()?.let { return it } }
        val trade = Lines.trade(p, ask.player) ?: return null
        return choose(p, ask.player, listOf(trade))
    }

    /** A topic for [ask] scoring [least] or more that [allow] lets through; null while no source is registered (the old lines answer). */
    internal fun pick(ask: Ask, least: Double, allow: (Topic) -> Boolean = { true }): Topic? =
        if (Topics.registered.isEmpty()) null else Topics.pick(ask, least, allow)

    /** Whether more than one other close by is already saying something ([except] doesn't count: the one they're answering). */
    internal fun hushed(p: Townsperson, except: Townsperson? = null): Boolean =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(CROWD, 4.0, CROWD)) { it !== p && it !== except && it.speech.isNotEmpty() }.size >= HUSH

    // --- Watching, hanging about -----------------------------------------------------------------------------------

    private fun watch(level: ServerLevel, player: ServerPlayer, now: Long) {
        val eye = eyes.getOrPut(player.uuid) { Eye(null, now, now, player.position(), now) }
        if (player.position().distanceToSqr(eye.spot) > STIR * STIR) {
            eye.spot = player.position()
            eye.still = now
        }
        val seen = gazedAt(level, player)
        if (seen == null) {
            if (now - eye.last > BLINK) eye.who = null
            return
        }
        if (seen.uuid != eye.who || now - eye.last > BLINK) {
            eye.who = seen.uuid
            eye.since = now
        }
        eye.last = now
    }

    /** The townsperson at work (or keeping still somewhere) that [player] is looking at, within [WATCH]: the one nearest their gaze. */
    private fun gazedAt(level: ServerLevel, player: ServerPlayer): Townsperson? {
        val from = player.eyePosition
        val look = player.getViewVector(1f)
        var best: Townsperson? = null
        var most = GAZE
        for (p in level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(WATCH, 3.0, WATCH)) { it.isAlive && it.routine.anchored && !it.isSleeping }) {
            val to = p.boundingBox.center.subtract(from)
            val d = to.length()
            if (d > WATCH || d < 0.01) continue
            val dot = (to.x * look.x + to.y * look.y + to.z * look.z) / d
            if (dot > most) {
                most = dot
                best = p
            }
        }
        return best?.takeIf { player.hasLineOfSight(it) }
    }

    /** The player who has been watching [p] for two seconds or more from within 8 blocks, and still is, if one has. */
    fun watcher(level: ServerLevel, p: Townsperson): ServerPlayer? {
        val now = level.gameTime
        for ((id, eye) in eyes) {
            if (eye.who != p.uuid || now - eye.last > BLINK || eye.last - eye.since < WATCHED) continue
            val player = level.getPlayerByUUID(id) as? ServerPlayer ?: continue
            if (!player.isSpectator && player.distanceToSqr(p) <= WATCH * WATCH) return player
        }
        return null
    }

    /** Whether [player] has hung about in one spot (within 2 blocks) for ten seconds or more. */
    fun loitering(player: Player): Boolean {
        val eye = eyes[player.uuid] ?: return false
        return player.level().gameTime - eye.still >= STILL
    }

    // --- Acquaintances passing ---------------------------------------------------------------------------------------

    /** How two residents know each other. */
    private enum class Ties { HOUSEHOLD, TRADE, VISIT }

    /** Acquaintances near [player] passing within 4 blocks of each other, one of them on the move: a nod or a wave from each. */
    private fun passing(level: ServerLevel, player: ServerPlayer, now: Long) {
        val folk = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(PLAYERS, 6.0, PLAYERS)) {
            it.resident >= 0 && it.isAlive && !it.isSleeping && it.director == null && it.attending == null && !it.isLeader &&
                it.captiveOf < 0 && it.target == null && it.activity !in PERFORMING && it.routine.task !is Chat && rested(it, now)
        }
        if (folk.size < 2) return
        val residents = Residents.get(level)
        val today = Days.dayOf(level.overworldClockTime)
        for (i in 0 until folk.size - 1) {
            val a = folk[i]
            if (!rested(a, now)) continue
            for (j in i + 1 until folk.size) {
                val b = folk[j]
                if (!rested(b, now) || a.distanceToSqr(b) > PASS * PASS) continue
                if (a.navigation.isDone && b.navigation.isDone) continue
                val ties = ties(residents[a.resident] ?: continue, residents[b.resident] ?: continue, today) ?: continue
                if (!a.hasLineOfSight(b)) continue
                pass(player, a, b, ties, now)
                break
            }
        }
    }

    private fun rested(p: Townsperson, now: Long): Boolean = nodded[p.uuid]?.let { now - it >= PASSED } ?: true

    /** How [a] and [b] know each other, if they do: one household, one trade, or a call at the other's house in the last three days. */
    private fun ties(a: Resident, b: Resident, today: Long): Ties? = when {
        a.capital != b.capital -> null
        a.home >= 0 && a.home == b.home -> Ties.HOUSEHOLD
        a.role == b.role && a.role != TownRole.CITIZEN && a.role != TownRole.CHILD -> Ties.TRADE
        called(a.id, b.home, today) || called(b.id, a.home, today) -> Ties.VISIT
        else -> null
    }

    private fun called(resident: Int, lodging: Int, today: Long): Boolean =
        lodging >= 0 && visits[(resident.toLong() shl 32) or (lodging.toLong() and 0xFFFFFFFFL)]?.let { today - it <= VISIT_DAYS } == true

    /** [p] has called at lodging [lodging] (a neighbour's house): they're acquaintances of the household for three days. */
    fun visited(p: Townsperson, lodging: Int) {
        if (p.resident < 0 || lodging < 0) return
        val today = Days.dayOf(p.level().overworldClockTime)
        visits[(p.resident.toLong() shl 32) or (lodging.toLong() and 0xFFFFFFFFL)] = today
        if (visits.size > 4096) visits.values.removeIf { today - it > VISIT_DAYS }
    }

    /** [a] and [b] pass each other: the one on the move nods first, the other a moment after, and one time in three one says good day. */
    private fun pass(player: ServerPlayer, a: Townsperson, b: Townsperson, ties: Ties, now: Long) {
        nodded[a.uuid] = now
        nodded[b.uuid] = now
        if (nodded.size > 1024) nodded.values.removeIf { now - it >= PASSED }
        val (first, second) = if (!a.navigation.isDone) a to b else b to a
        val wave = ties == Ties.HOUSEHOLD
        acknowledge(first, second, wave)
        later(SECOND) { if (second.isAlive && second.director == null && second.attending == null) acknowledge(second, first, wave) }
        if (first.random.nextInt(3) != 0) return
        val speaker = if (first.random.nextInt(3) != 0) first else second
        val line = goodDay(speaker, ties) ?: return
        later(if (speaker === first) 4 else SECOND + 4) {
            if (speaker.isAlive && speaker.director == null && !hushed(speaker) && speaker.say(line.speech, ticks = line.ticks + 20L)) {
                heard(speaker, player, line, "passing: ${ties.name.lowercase(Locale.ROOT)}", toThem = false)
            }
        }
    }

    /** [p] sees [other] go by: a look, and a nod or a wave (over the walk, or standing), if their hands are free for it. */
    private fun acknowledge(p: Townsperson, other: Townsperson, wave: Boolean) {
        p.glance(other, GLANCE)
        val task = p.routine.task
        when {
            !p.navigation.isDone -> if (p.activity == Activity.NONE || p.activity == Activity.STROLL) play(p, if (wave) Activity.WAVE_WALK else Activity.NOD_WALK)
            // From a seat or the work, a look up does
            p.seat != null || p.routine.anchored -> {}
            // Standing about, facing their way
            (task == null || task.leisure) && sees(p, other) ->
                play(p, if (wave || p.role == TownRole.CHILD) Activity.WAVE else if (p.random.nextBoolean()) Activity.NOD else Activity.TIP_HAT)
        }
    }

    /** Good day in passing to an acquaintance, true to the hour; at home's door only "Morning!" or "Evening.". */
    private fun goodDay(p: Townsperson, ties: Ties): Line? {
        val level = p.level() as? ServerLevel ?: return null
        val hour = Say.hour(level)
        val morning = hour >= 5.0 && hour < 12.0
        val afternoon = hour >= 12.0 && hour < 17.0
        val evening = hour >= 17.0 && hour < 21.0
        val keys = when {
            ties == Ties.HOUSEHOLD -> if (morning) listOf("greet_1") else if (hour >= 17.0) listOf("night_1") else listOf()
            morning -> listOf("greet_1", "talk.greet.morning_1", "greet_0")
            afternoon -> listOf("greet_0", "talk.greet.afternoon_0", "talk.greet.afternoon_1")
            evening -> listOf("talk.greet.evening_0", "talk.greet.evening_1", "night_1")
            else -> listOf("night_1", "night_0", "night_2")
        }
        return choose(p, null, keys)
    }

    // --- At work: the stall, the bar, the beat, the bench -----------------------------------------------------------

    /**
     * The merchant's call to [near] from the stall (Jobs' CALL_OUT step): what's cheap today and why, or their wares,
     * with a price, once an hour to each player; null if they've nothing to call (they tidy the stall instead).
     * Talk off, the old call; barks off, one of the old calls that's true.
     */
    fun calling(p: Townsperson, near: Player): Step? = guarded("calling the wares", null) {
        if (!Talk.enabled()) return Step(Activity.CALL_OUT, line = "merchant_${p.random.nextInt(6)}")
        val level = p.level() as? ServerLevel ?: return null
        if (!on()) return OldLines.pool(p, CALLS, near).let { if (it.isEmpty()) null else Step(Activity.CALL_OUT, line = it[p.random.nextInt(it.size)]) }
        val player = near as? ServerPlayer ?: return null
        val now = level.gameTime
        if (!due(player.uuid, p, now) || p.speech.isNotEmpty() || now < p.nextSpeech || hushed(p)) return null
        val call = call(level, p, player) ?: return null
        mark(player.uuid, p, now)
        p.glance(player, call.ticks + 10)
        speak(p, player, listOf(call), "calling the wares")
        Step(Activity.CALL_OUT)
    }

    /**
     * What [p] calls from their stall now: half the time, when something they sell is cheap for a reason, that it's
     * cheap and why ("Cheap today: there's plenty about."), else their wares ("Fine wares, fair prices!"); after it,
     * unvoiced, what it really goes for ("Carrots: 4 for 1 Mark."). Null with nothing in stock to sell.
     */
    private fun call(level: ServerLevel, p: Townsperson, player: ServerPlayer?): Line? {
        val life = Days.life(p)
        val shop = Shop.kept(p)?.takeIf { it.goods.isNotEmpty() }
        val entry = life?.let { Trades.market(p, it) }
        if (shop == null || entry == null) return choose(p, player, CALLS)
        val quotes = shop.goods.filter { entry.market.stock(it) >= 1.0 }.map { Trading.quote(entry, it) }.filter { it.buy != null }
        if (quotes.isEmpty()) return null
        val avoid = Topics.avoid(p, player)
        val cheap = quotes.filter { it.why?.dear == false }.maxByOrNull { it.usual / it.price }
        if (cheap != null && p.random.nextBoolean()) {
            val key = StallTalk.price(cheap)
            if (key != null && key !in avoid) return Line(Speech.withDetail(key, price(cheap)), key, Talk.ticks(p, key))
        }
        val wares = choose(p, player, CALLS) ?: return null
        val shown = when (wares.voiced) {
            "merchant_4" -> quotes.firstOrNull { it.good == Good.APPLE }
            "merchant_5" -> quotes.firstOrNull { it.good == Good.COD || it.good == Good.SALMON }
            else -> null
        } ?: cheap ?: quotes[p.random.nextInt(quotes.size)]
        return Line(Speech.withDetail(wares.speech, price(shown)), wares.voiced, wares.ticks)
    }

    /** "Carrots: 4 for 1 Mark.": what a player pays for [q]'s good now. */
    private fun price(q: Quote): String? {
        val lot = q.buy ?: return null
        val name = q.good.key + ".many"
        return if (lot.marks == 1) Speech.of("talk.detail.market.price.one", name, lot.count) else Speech.of("talk.detail.market.price", name, lot.count, lot.marks)
    }

    /** The innkeeper pours with [near] at the bar (Jobs' POUR step), with a word to them now and then; true words only, with talk on. */
    fun pouring(p: Townsperson, near: Player): Step = guarded("pouring at the bar", Step(Activity.POUR)) {
        if (!Talk.enabled()) return Step(Activity.POUR, line = "inn_${p.random.nextInt(4)}")
        val level = p.level() as? ServerLevel ?: return Step(Activity.POUR)
        if (!on()) return Step(Activity.POUR, line = OldLines.pool(p, BAR, near).let { if (it.isEmpty()) null else it[p.random.nextInt(it.size)] })
        val player = near as? ServerPlayer ?: return Step(Activity.POUR)
        val now = level.gameTime
        if (!due(player.uuid, p, now) || p.speech.isNotEmpty() || now < p.nextSpeech || hushed(p)) return Step(Activity.POUR)
        val line = choose(p, player, BAR) ?: return Step(Activity.POUR)
        mark(player.uuid, p, now)
        p.glance(player, line.ticks + 10)
        speak(p, player, listOf(line), "at the bar")
        Step(Activity.POUR)
    }

    /**
     * The line a step of work starts with (Work.begin): talk off, as it always was; talk on, only if it's true (or
     * another of its kind that is); with barks on as well, only to a player who's watched them at it for two seconds,
     * once an hour, and half the time how the work's going instead.
     */
    fun step(p: Townsperson, line: String): Unit = guarded("a line at work") {
        if (!Talk.enabled()) {
            p.say(line)
            return
        }
        // The priest's words are the service's, to the room
        if (!on() || p.role == TownRole.PRIEST) {
            if (!on() || !hushed(p)) OldLines.alike(p, line)?.let { p.say(it) }
            return
        }
        val level = p.level() as? ServerLevel ?: return
        val player = watcher(level, p) ?: return
        val now = level.gameTime
        if (!due(player.uuid, p, now) || p.speech.isNotEmpty() || now < p.nextSpeech || hushed(p)) return
        val ask = Talk.ask(level, p, player, Occasion.BARK)
        val topic = if (p.random.nextBoolean()) pick(ask, Topics.LEAST) { it.kind == Kind.TRADE && it.say.parts.size == 1 } else null
        val said = topic?.let { lines(ask, it.say).take(1) }?.ifEmpty { null }
            ?: OldLines.alike(p, line, player)?.let { choose(p, player, listOf(it)) }?.let { listOf(it) }
            ?: return
        mark(player.uuid, p, now)
        p.glance(player, said.first().ticks + 10)
        speak(p, player, said, "watched at work")
    }

    /**
     * A guard at a stop on the beat sees [passer] go by: talk off, a salute and a word as always; with talk on, only
     * true words; with barks on as well, a salute to a hero and a nod to anyone else, and a word once an hour as
     * they know them.
     */
    fun salute(p: Townsperson, passer: Player): Unit = guarded("a guard's word on the beat") {
        if (!Talk.enabled()) {
            p.act(Activity.SALUTE)
            p.say("guard_${p.random.nextInt(5)}")
            return
        }
        val level = p.level() as? ServerLevel ?: return
        val player = passer as? ServerPlayer
        if (!on() || player == null) {
            p.act(Activity.SALUTE)
            OldLines.pool(p, GUARD, passer).let { if (it.isNotEmpty()) p.say(it[p.random.nextInt(it.size)]) }
            return
        }
        val ask = Talk.ask(level, p, player, Occasion.BARK)
        p.act(if (Talk.hero(ask)) Activity.SALUTE else Activity.NOD)
        val now = level.gameTime
        if (!due(player.uuid, p, now) || p.speech.isNotEmpty() || now < p.nextSpeech || hushed(p)) return
        mark(player.uuid, p, now)
        val r = p.random
        val line = when (ask.familiarity) {
            Familiarity.FRIEND, Familiarity.MET, Familiarity.ACQUAINTED -> (if (r.nextBoolean()) hello(ask) else null) ?: choose(p, player, GUARD)
            Familiarity.HEARD_OF -> if (Talk.hero(ask)) hello(ask) else choose(p, player, GUARD)
            Familiarity.STRANGER -> if (r.nextInt(STRANGERS) == 0) choose(p, player, GUARD) else null
            // "Move along."
            Familiarity.WARY, Familiarity.HOSTILE -> choose(p, player, listOf("guard_0"))
        } ?: return
        speak(p, player, listOf(line), "guard on the beat, ${name(ask.familiarity)}")
    }

    /** The keeper hands a townsperson what they bought (Acts.serve): now and then a word with it ("There you are."). */
    fun handed(keeper: Townsperson): String? {
        if (!Talk.enabled()) return if (keeper.random.nextInt(3) == 0) "merchant_${keeper.random.nextInt(6)}" else null
        if (keeper.random.nextInt(3) != 0 || hushed(keeper)) return null
        return "market.sold.${keeper.random.nextInt(4)}"
    }

    /**
     * The keeper's greeting to [player] stepping up to the stall (Serving.arrive), from the topics (Occasion.GREET)
     * and what they remember of them: a faction's word, a friend's greeting, "back again", "it's been a while", the
     * wary's, the famous'; with its gesture. Null for a stranger, or someone met who's nothing particular to be
     * greeted for: the keeper's own greeting (by the weather, the hour, their ways) does for them.
     */
    fun counter(level: ServerLevel, p: Townsperson, player: ServerPlayer): Pair<String, Activity>? = guarded("a keeper's greeting", null) {
        if (!Talk.enabled() || Topics.registered.isEmpty()) return null
        val ask = Talk.ask(level, p, player, Occasion.GREET)
        pick(ask, Topics.LEAST)?.let { t ->
            lines(ask, t.say).firstOrNull()?.let { line ->
                if (line.voiced.isNotEmpty()) Topics.spoke(p, player, line.voiced)
                return line.speech to (brief(t.gesture ?: Talk.gesture(t)) ?: Activity.NOD)
            }
        }
        val known = ask.knows
        val today = Days.dayOf(level.overworldClockTime)
        val (family, gesture) = when (ask.familiarity) {
            Familiarity.FRIEND -> "greet.friend" to Activity.WAVE
            Familiarity.MET, Familiarity.ACQUAINTED -> when {
                known != null && known.seen.toLong() == today -> "greet.again" to Activity.NOD
                known != null && today - known.seen >= 7 -> "greet.long" to Activity.WAVE
                else -> return null
            }
            Familiarity.WARY, Familiarity.HOSTILE -> "greet.wary" to Activity.NOD
            Familiarity.HEARD_OF -> (if (Talk.hero(ask)) "greet.hero" else "greet.heard") to Activity.NOD
            Familiarity.STRANGER -> return null
        }
        val line = family(ask, family, named = true) ?: return null
        Topics.spoke(p, player, line.voiced)
        line.speech to gesture
    }

    /** A word from [p] to [player] with nothing more to it (a right-click with no talk to open): what's on their mind, or a greeting that knows them. */
    fun word(p: Townsperson, player: Player?): String? = guarded("a word to a player", null) {
        val level = p.level() as? ServerLevel ?: return null
        if (Topics.registered.isEmpty()) return null
        val sp = player as? ServerPlayer
        val ask = Talk.ask(level, p, sp, Occasion.BARK)
        val line = pick(ask, Topics.LEAST) { it.rumour == null && it.say.parts.size == 1 }?.let { lines(ask, it.say).firstOrNull() } ?: hello(ask) ?: return null
        if (line.voiced.isNotEmpty()) Topics.spoke(p, sp, line.voiced)
        line.speech
    }

    // --- To each other: chats and the tavern ---------------------------------------------------------------------------

    /**
     * What [p] might bring up to [other] (Occasion.CHAT, the other as listener), and how well they know each other:
     * one household as friends, one trade or a visit lately as acquaintances, the rest of their town as met.
     */
    internal fun chat(level: ServerLevel, p: Townsperson, other: Townsperson?): Ask {
        val life = Days.life(p)
        val r = life?.resident ?: Residents.get(level).of(p)
        val o = other?.let { Days.life(it)?.resident ?: Residents.get(level).of(it) }
        val familiarity = when {
            r == null || o == null || r.capital != o.capital -> Familiarity.STRANGER
            r.home >= 0 && r.home == o.home -> Familiarity.FRIEND
            ties(r, o, Days.dayOf(level.overworldClockTime)) != null -> Familiarity.ACQUAINTED
            else -> Familiarity.MET
        }
        return Ask(level, p, r, life, Occasion.CHAT, null, other, familiarity, null, life?.let { Trades.market(p, it) }, level.gameTime)
    }

    /** What a chat leaves out: their own household's news, between those who live in it. */
    internal fun allow(ask: Ask): (Topic) -> Boolean {
        val home = ask.familiarity == Familiarity.FRIEND
        return { !(home && it.kind == Kind.HOUSEHOLD) }
    }

    /** One of the old small talk lines true for [p] now and not said lately; null if none is. */
    internal fun gossip(p: Townsperson): Line? = choose(p, null, GOSSIP)

    /** How [p] answers [teller]'s [topic] (null: one of the old lines): its line and its gesture. */
    internal class Reply(val line: Line, val gesture: Activity?)

    /**
     * [p]'s answer to what [teller] just told them: by its tone for a rumour (glad, sorry, amazed, or doubtful of a
     * tall tale, and the grumpy now and then of good news), else, half the time, a word ("Aye.", "Is that so?").
     */
    internal fun reply(p: Townsperson, teller: Townsperson, topic: Topic?): Reply? {
        val level = p.level() as? ServerLevel ?: return null
        if (!p.isAlive || p.director != null || hushed(p, teller)) return null
        val r = p.random
        val rumour = topic?.rumour
        if (topic != null && rumour != null) {
            val ask = chat(level, p, teller)
            val warp = topic.id.substringAfterLast('.').toIntOrNull() ?: 0
            val grumpy = ask.resident?.has(Trait.GRUMPY) == true
            val (family, gesture) = when {
                warp >= 3 || topic.tone > 0 && grumpy && r.nextInt(3) == 0 -> "reply.doubt" to Activity.WAVE_OFF
                topic.tone > 0 -> if (r.nextInt(3) == 0) "reply.wonder" to Activity.RECOIL else "reply.glad" to Activity.NOD
                topic.tone < 0 -> "reply.sorry" to Activity.SIGH
                else -> "reply.wonder" to Activity.RECOIL
            }
            // "Poor soul." is for a person; for the rest of the bad news, the other two
            val person = family != "reply.sorry" || kind(level, teller, rumour).let { it == "death" || it.startsWith("slight.") }
            val f = TalkLines.FAMILIES[family] ?: return null
            val avoid = Topics.avoid(p, null)
            val keys = (0 until f.count).map { "talk.${family}_$it" }.filter { person || it != "talk.reply.sorry_0" }
            val key = keys.filter { it !in avoid }.ifEmpty { keys }.let { it[r.nextInt(it.size)] }
            return Reply(Line(key, key, Talk.ticks(p, key)), gesture)
        }
        if (r.nextBoolean()) return null
        val tone = topic?.tone ?: 0
        val keys = when {
            tone > 0 -> listOf("reply_5", "reply_3")
            tone < 0 -> listOf("reply_1", "reply_3", "reply_4")
            else -> listOf("reply_0", "reply_2", "reply_3", "reply_4")
        }
        val line = choose(p, null, keys) ?: return null
        return Reply(line, if (line.voiced == "reply_5") Activity.LAUGH else Activity.NOD)
    }

    /** The kind of rumour [rumour] as [teller] knows it ("" if they don't). */
    private fun kind(level: ServerLevel, teller: Townsperson, rumour: Int): String {
        val r = resident(level, teller) ?: return ""
        return TownTalk.known(level, r).firstOrNull { it.rumour.id == rumour }?.rumour?.kind ?: ""
    }

    /** [from] told [to] rumour [rumour] where it was seen: [to] knows it now (TownTalk.tell). */
    internal fun tell(level: ServerLevel, from: Townsperson, to: Townsperson, rumour: Int) {
        val a = resident(level, from) ?: return
        val b = resident(level, to) ?: return
        TownTalk.tell(level, a, b, rumour)
    }

    /**
     * A turn of talk in a tavern or the square of an evening (Jobs' Mingle, the TALK mood), one time in three: talk off,
     * the old small talk; on, a topic for whoever's nearest (a rumour passes to them, and they answer it), said with
     * its gesture. The ticks it takes, so the mood lasts it out.
     */
    fun mingle(p: Townsperson): Int = guarded("tavern talk", 0) {
        if (!Talk.enabled()) {
            if (p.random.nextInt(3) == 0) p.say(Lines.gossip(p))
            return 0
        }
        if (p.random.nextInt(3) != 0) return 0
        val level = p.level() as? ServerLevel ?: return 0
        val now = level.gameTime
        if (p.speech.isNotEmpty() || now < p.nextSpeech || hushed(p)) return 0
        val player = level.getNearestPlayer(p, Townsperson.HEARING) as? ServerPlayer ?: return 0
        val other = Social.nearestFolk(p, 4.0)?.takeIf { it.director == null && it.isAlive }
        val ask = chat(level, p, other)
        val topic = pick(ask, Topics.LEAST, allow(ask))
        val lines = topic?.let { said(ask, it) }?.ifEmpty { null } ?: listOfNotNull(gossip(p))
        if (lines.isEmpty()) return 0
        if (topic != null) {
            val g = topic.gesture ?: Talk.gesture(topic)
            // Pointing out someone (or somewhere) across a crowded room: a lean in and a look will do
            val made = when {
                g == Activity.POINT -> if (topic.rumour != null) Activity.GOSSIP else Activity.EXPLAIN
                p.seat != null -> Talk.seated(p, g)
                else -> g
            }
            if (made != null) if (made.loop) p.act(made) else play(p, made)
        }
        // "That's the one, over there.": across a crowded room, a lean in and a look round at them does for a point
        val about = topic?.about?.let { level.server.playerList.getPlayer(it) }?.takeIf { it.level() === level && !it.isSpectator }
        val why = "tavern talk" + (topic?.let { ": ${it.id}" } ?: "")
        speak(p, player, lines, why, toThem = false) { i ->
            if (about != null && lines[i].voiced.startsWith("talk.news.there")) {
                p.glance(about, LOOK_ROUND)
                other?.glance(about, LOOK_ROUND)
            }
        }
        val total = lines.sumOf { it.ticks + GAP }
        if (other != null) later(total + 4) {
            topic?.rumour?.let { tell(level, p, other, it) }
            if (other.isAlive && other.director == null && other.speech.isEmpty()) reply(other, p, topic)?.let { answer ->
                other.lookAt(p)
                if (other.say(answer.line.speech, force = true, ticks = answer.line.ticks + 20L)) {
                    heard(other, player, answer.line, "tavern reply" + (topic?.let { ": ${it.id}" } ?: ""), toThem = false)
                    answer.gesture?.let { g -> Talk.seated(other, g)?.let { if (!it.loop) play(other, it) } }
                }
            }
        }
        total + 40
    }

    // --- For /guildmark talk street, bark and chat -----------------------------------------------------------------------

    /** What's been said in the street within earshot of [player] in the last minute, the rates, and how [p] (if any) stands with them. */
    fun report(level: ServerLevel, player: ServerPlayer, p: Townsperson?): List<Component> {
        val now = level.gameTime
        val out = ArrayList<Component>()
        out += Component.literal("Street talk: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(if (on()) "on" else "off (the old greetings and chats)").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("; talk ${if (Talk.enabled()) "on" else "off"}, barks ${if (barks()) "on" else "off"}").withStyle(ChatFormatting.GRAY))
        val near = said.filter { now - it.at <= 1200 && it.pos.distanceToSqr(player.position()) <= PLAYERS * PLAYERS }
        val kinds = near.groupingBy { it.why.substringBefore(':').substringBefore(',') }.eachCount()
        out += Component.literal("Within 24 blocks in the last minute: ${near.size} lines" +
            if (kinds.isEmpty()) "." else " (" + kinds.entries.sortedByDescending { it.value }.joinToString { "${it.value} ${it.key}" } + ").").withStyle(ChatFormatting.GRAY)
        for (s in near.takeLast(8)) {
            out += Component.literal("  ${(now - s.at) / 20}s ago, ${s.who}${if (s.to == player.uuid) " to you" else ""}: ").withStyle(ChatFormatting.GRAY)
                .append(Speech.text(s.line).copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  [${s.why}]").withStyle(ChatFormatting.DARK_GRAY))
        }
        out += Component.literal("Rates: a notice of you every 7 s at most; each townsperson once an in-game hour (50 s), sooner only with " +
            "something new (0.7); strangers a line 1 in 3; a trade line only after 2 s of watching from 8 blocks; acquaintances " +
            "passing within 4 blocks once in 30 s each; no new line while 2 others within 10 blocks are talking.").withStyle(ChatFormatting.DARK_GRAY)
        if (p != null) {
            val last = barked[player.uuid]?.get(p.uuid)
            val f = TownTalk.familiarity(level, resident(level, p), player)
            val watching = watcher(level, p)?.uuid == player.uuid
            out += Component.literal("${p.name.string}: ${name(f)}, ${how(p).name.lowercase(Locale.ROOT)}; " +
                (if (last == null) "hasn't noticed you yet" else "noticed you ${(now - last) / 20}s ago" + if (now - last < HOUR) ", due again in ${(HOUR - (now - last)) / 20}s" else ", due") +
                (if (watching) "; you've been watching them long enough for a trade line" else "") + ".").withStyle(ChatFormatting.GRAY)
        }
        return out
    }

    /** [p] notices [player] now as they would in passing, whatever the hour, the chances and the talk round them: what they did, and why. */
    fun barkNow(level: ServerLevel, player: ServerPlayer, p: Townsperson): Component {
        if (!on()) return Component.literal("Street talk is off (townsfolkTalk or townsfolkBarks): nothing in passing.")
        if (!free(p, level.gameTime) && (p.director != null || p.isSleeping || p.routine.task is Chat)) {
            return Component.literal("${p.name.string} is busy just now (asleep, in a talk or a chat, or in someone's charge).")
        }
        val n = notice(level, player, p, null, true)
        val words = n.lines.joinToString(" ") { Speech.text(it.speech).string }
        return Component.literal("${p.name.string} (${how(p).name.lowercase(Locale.ROOT)}): ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(if (words.isEmpty()) "no words" else "\"$words\"").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" ${n.gesture?.name?.lowercase(Locale.ROOT) ?: "a look"}; ${n.why}").withStyle(ChatFormatting.GRAY))
    }

    /** [p] starts a chat with whoever's nearest and free, now. */
    fun chatNow(p: Townsperson): Component {
        val task = Social.chat(p) ?: return Component.literal("Nobody free to chat with within 10 blocks of ${p.name.string} (or they're a child).")
        p.routine.replace(task)
        val partner = (task as? Chat)?.partner
        return Component.literal("${p.name.string} goes to chat with ${partner?.name?.string ?: "someone"}" +
            if (on()) ", from the topics." else ", the old way (street talk is off).")
    }
}
