package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.Serving
import io.github.vaspyyy.guildmark.economy.Stalls
import io.github.vaspyyy.guildmark.network.TalkChoice
import io.github.vaspyyy.guildmark.network.TalkClosePayload
import io.github.vaspyyy.guildmark.network.TalkOpenPayload
import io.github.vaspyyy.guildmark.network.TalkPart
import io.github.vaspyyy.guildmark.network.TalkPayloads
import io.github.vaspyyy.guildmark.network.TalkSayPayload
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Lines
import io.github.vaspyyy.guildmark.townsfolk.Pull
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.DailySource
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * The server's side of a talk with a townsperson (living-world/design-voices.md section 2): opening one on a
 * right-click, the greeting that knows the player, the opener and the choices, the answers, up to four players at once
 * answered in the order they asked, and the end. The townsperson says each part aloud ([Townsperson.say]), so it comes
 * from their mouth and everyone near hears it, with its gesture, while the player's own screen types it in step; the
 * body is [Talking]'s. The talk screen's payloads come in through [picked], [ended] and [asked].
 */
object Talk {
    /**
     * Whether talks open at all, built in (the townsfolkTalk setting turns them off in play): false, none does, a
     * right-click answers with a line as before, and the market screen's Talk tab stays hidden.
     */
    const val READY = true

    /** How far a player may be from whoever they're talking with. */
    const val REACH = 12.0

    /** How many players one townsperson talks with at once. */
    const val MOST = 4

    /** Half a minute with no choice made ends a talk; so does two minutes in all. */
    private const val IDLE = 30 * 20L
    private const val LONGEST = 2 * 60 * 20L

    /** How soon a stretch of their day that won't wait must be due to cut a talk short, or keep them from stopping. */
    private const val DUE = 100

    /** How long after a stretch that won't wait begins someone is still on their way to it. */
    private const val ON_THE_WAY = 600

    /** How often a screen waiting on an answer hears the server's still there (it gives up after 100 ticks of silence). */
    private const val ALIVE = 60L

    /** Ticks between the parts of an answer, and how long a part whose take isn't timed is given. */
    private const val GAP = 6
    private const val UNTIMED = 40

    /** The least a topic must score for them to go straight on with it after the greeting. */
    private const val OPENER = 0.3

    /** The stretches of a day that won't wait for a talk. */
    private val MUST = setOf(Doing.WORK, Doing.WATCH, Doing.CRY, Doing.LESSONS, Doing.BUILD)

    /** What someone carrying a load is seen doing. */
    private val LADEN = setOf(Activity.CARRY, Activity.CARRY_SACK, Activity.CARRY_BASKET, Activity.HAUL, Activity.CARRY_BLOCK, Activity.PULL_CART, Activity.CARRY_POLE)

    /** How a talk comes to an end. */
    private enum class Ending { BYE, WALKED, IDLE, OFF, GONE }

    /** One player's talk with one townsperson. */
    private class Session(val player: ServerPlayer, val p: Townsperson, val script: Script?, val started: Long) {
        /** The number of the say on the player's screen now: a choice answers it, once. */
        var serial = 0

        /** When the player last chose something (or the talk began). */
        var last = started

        /** An answer is on its way: no choice is taken until it's said. */
        var waiting = true

        /** The choices asked this talk. */
        val asked = HashSet<String>()

        /** The ids of the choices the screen was sent with its last say: a pick of any other is dropped. */
        var offered: Set<String> = emptySet()

        /** How many pieces of news they've told this talk. */
        var news = 0

        /** Hostile: one cold line, and only Goodbye. */
        var cold = false

        /** Whoever has charge of them for the talk ([Talking], or the director that runs [script]): if they let go, the talk's over. */
        var holder: Director? = null

        /** When the screen last heard from the server. */
        var sent = started

        /** How the wary or hostile stand through the whole talk. */
        var wary = false

        /** Opened from the market screen's Talk tab: Trade takes the player back to it. */
        var market = false

        var ending: Ending? = null
    }

    /** One part of an answer as it's said: the line as it goes out ([Speech]), its voiced key ("" for text alone), its gesture, how long it lasts and where it points. */
    private class Spoken(val speech: String, val voiced: String, val gesture: Activity?, val ticks: Int, val toward: BlockPos?)

    /** An answer waiting its turn or being said: to [session] (null for a recital with nobody), its [parts], and whether the talk ends with it. */
    private class Answer(val session: Session?, val parts: List<Spoken>, val closing: Boolean) {
        var next = 0
        var at = -1L
    }

    private val sessions = HashMap<UUID, Session>()
    private val queues = WeakHashMap<Townsperson, ArrayDeque<Answer>>()

    /** Who each townsperson is answering now, or last answered. */
    private val answering = WeakHashMap<Townsperson, Session>()

    /** The day each resident last took a walking-off to heart, per player: once a day at most. */
    private val walkedOff = HashMap<String, Long>()

    fun init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            sessions[event.entity.uuid]?.let { end(it, Ending.GONE) }
            Topics.forget(event.entity.uuid)
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            sessions.clear()
            queues.clear()
            answering.clear()
            walkedOff.clear()
        }
    }

    /** Whether talks are on (`townsfolkTalk`). */
    fun enabled(): Boolean = try {
        GuildmarkConfig.TOWNSFOLK_TALK.get()
    } catch (e: IllegalStateException) {
        true
    }

    // --- Opening ------------------------------------------------------------------------------------------------

    /** Opens a talk between [player] and [p] if they can talk now; false if the old path should answer instead. */
    fun open(level: ServerLevel, player: ServerPlayer, p: Townsperson): Boolean = open(level, player, p, null, false)

    /**
     * As [open], with [first] (a line) said after the greeting in place of their own opener, as a keeper's "We're
     * shut" out of hours; [anyway] opens it even if they're busy (`/guildmark talk open`).
     */
    fun open(level: ServerLevel, player: ServerPlayer, p: Townsperson, first: String?, anyway: Boolean): Boolean = open(level, player, p, first, anyway, false)

    /** As [open]; [market]: asked from the market screen of the keeper serving them, who turns from the counter to talk. */
    private fun open(level: ServerLevel, player: ServerPlayer, p: Townsperson, first: String?, anyway: Boolean, market: Boolean): Boolean {
        if (!enabled() || !READY || player.isSpectator) return false
        if (!p.isAlive || p.isLeader || p.captiveOf >= 0 || p.watcher || p.target != null || p.activity == Activity.FLEE) return false
        sessions[player.uuid]?.let { s ->
            if (s.p === p) return true
            end(s, Ending.GONE)
        }
        val director = p.director
        if (market && director is Serving) {
            // Serving someone else besides, they've no time for it
            if (!Stalls.spare(player, p)) {
                word(ask(level, p, player, Occasion.GREET), "ask.busy")?.let { p.say(it, force = true) }
                return false
            }
        } else if (director != null && director !is Talking) return false
        val ask = ask(level, p, player, Occasion.GREET)
        if (p.isSleeping) {
            p.say(word(ask, "ask.asleep") ?: return false, force = true)
            return true
        }
        if (director == null && !anyway) busy(level, p)?.let { (family, gesture) ->
            p.say(word(ask, family) ?: return false, force = true)
            if (gesture != null) p.act(gesture)
            return true
        }
        if (talkers(p) >= MOST) {
            p.say(word(ask, "ask.queue") ?: return true, force = true)
            return true
        }
        if (p.director == null) Talking.take(p)
        val s = Session(player, p, null, level.gameTime)
        s.holder = p.director
        s.market = market
        sessions[player.uuid] = s
        Wire.open(player, p, name(p), epithet(ask), TalkOpenPayload.knows(ask.familiarity))
        val parts = ArrayList<Spoken>()
        // At the counter they've greeted the player already
        if (!market) greeting(ask)?.let { parts += it }
        s.wary = ask.familiarity == Familiarity.WARY || ask.familiarity == Familiarity.HOSTILE
        when {
            ask.familiarity == Familiarity.HOSTILE -> {
                s.cold = true
                // Half a step back from them; the arms fold after
                part(ask, "ask.cold", Activity.RECOIL)?.let { parts += it }
            }
            first != null -> parts += Spoken(first, first, Activity.TALK, UNTIMED, null)
            else -> parts += opener(ask) ?: listOfNotNull(part(ask, "ask.yes", null))
        }
        // Old lines while the new ones aren't recorded: a word as before
        if (parts.isEmpty()) Lines.talk(p, player).let { parts += Spoken(it, it, Activity.TALK, UNTIMED, null) }
        ask.resident?.let { TownTalk.met(level, it, player) }
        queue(s, parts, false)
        return true
    }

    /** Opens a talk between [player] and [p] that [script] runs, for a director with scripted work (M4's driver, the festival host): the director keeps the body. */
    fun script(level: ServerLevel, player: ServerPlayer, p: Townsperson, script: Script) {
        if (!READY) return
        sessions[player.uuid]?.let { end(it, Ending.GONE) }
        val s = Session(player, p, script, level.gameTime)
        s.holder = p.director
        sessions[player.uuid] = s
        val ask = ask(level, p, player, Occasion.OPENER)
        Wire.open(player, p, name(p), epithet(ask), TalkOpenPayload.knows(ask.familiarity))
        queue(s, spoken(ask, script.opener(ask), Activity.TALK, null), false)
    }

    /** Whether [p] is in a talk. */
    fun busy(p: Townsperson): Boolean = sessions.values.any { it.p === p }

    /** How many players [p] is talking with. */
    fun talkers(p: Townsperson): Int = sessions.values.count { it.p === p }

    /** The player [p] faces: whoever they're answering, else whoever spoke to them last. */
    fun facing(p: Townsperson): ServerPlayer? {
        // Seeing someone off after they pressed Escape, too
        queues[p]?.firstOrNull()?.session?.player?.let { if (!it.isRemoved) return it }
        answering[p]?.let { if (sessions[it.player.uuid] === it) return it.player }
        return sessions.values.filter { it.p === p }.maxByOrNull { it.last }?.player
    }

    /** Whether [p] is saying something in a talk just now (or has an answer waiting to be said). */
    fun speaking(p: Townsperson): Boolean = queues[p]?.isNotEmpty() == true

    /** How [p] stands between gestures, [speaking] or listening: arms folded the whole talk for the wary; seated, their seated talk or just sitting. */
    fun pose(p: Townsperson, speaking: Boolean): Activity {
        if (p.seat != null) return if (speaking) Activity.SIT_TALK else Activity.SIT
        val s = facing(p)?.let { sessions[it.uuid] }
        return when {
            s?.wary == true -> Activity.ARMS_FOLDED
            speaking -> Activity.TALK
            else -> Activity.LISTEN
        }
    }

    /** Every talk [p] is in ends at once (danger, a blow, the switch turned off). */
    fun endAll(p: Townsperson) {
        for (s in sessions.values.filter { it.p === p }) end(s, Ending.GONE)
        queues.remove(p)
    }

    // --- The player's choices -------------------------------------------------------------------------------------

    /** [player] picked [choice] in their talk with entity [entity], answering the say numbered [serial]. */
    fun picked(player: ServerPlayer, entity: Int, serial: Int, choice: String) {
        val s = sessions[player.uuid] ?: return
        if (s.p.id != entity || s.serial != serial || s.waiting || s.ending != null) return
        // Only what the screen was shown can be picked (a sent "trade" in a talk with no market behind it, or a topic's choice it never showed, is dropped)
        if (choice !in s.offered) return
        val level = s.p.level() as? ServerLevel ?: return
        s.last = level.gameTime
        val ask = ask(level, s.p, player, Occasion.ANSWER)
        s.script?.let { script ->
            s.asked += choice
            val (said, ends) = script.answer(ask, choice)
            if (ends) s.ending = Ending.BYE
            queue(s, spoken(ask, said, Activity.TALK, null), ends)
            return
        }
        when (choice) {
            "bye" -> farewell(s, ask, Ending.BYE)
            "trade" -> trade(level, s)
            "doing" -> doing(s, ask)
            "news" -> news(s, ask)
            "name" -> name(s, ask)
            else -> source(s, ask, choice)
        }
    }

    /** [player] closed their talk with entity [entity] (Escape, or the screen closed itself): Escape counts as Goodbye. */
    fun ended(player: ServerPlayer, entity: Int) {
        val s = sessions[player.uuid] ?: return
        if (s.p.id != entity) return
        if (s.ending != null) return end(s, s.ending ?: Ending.BYE)
        val level = s.p.level() as? ServerLevel ?: return end(s, Ending.GONE)
        // The farewell is said aloud as they part; the screen's already gone
        val ask = ask(level, s.p, player, Occasion.FAREWELL)
        farewell(s, ask, Ending.BYE)
        sessions.remove(player.uuid)
    }

    /** [player] pressed the market screen's Talk button, with the keeper that's entity [entity]. */
    fun asked(player: ServerPlayer, entity: Int) {
        val level = player.level() as? ServerLevel ?: return
        val p = level.getEntity(entity) as? Townsperson ?: return
        if (p.distanceToSqr(player) > REACH * REACH) return
        // Only a keeper at their counter has a Talk button (a sent one for anyone else would skip their busy check)
        if (p.director !is Serving) return
        open(level, player, p, null, true, true)
    }

    /**
     * Trade, in a talk opened from the market screen: the talk screen closes, the market screen comes up in its place,
     * and the keeper serves the player as before, back at what they were at before the talk.
     */
    private fun trade(level: ServerLevel, s: Session) {
        end(s, Ending.GONE)
        if (talkers(s.p) > 0) return
        (s.p.director as? Talking)?.hand(s.p)
        Stalls.interact(level, s.player, s.p)
    }

    private fun doing(s: Session, ask: Ask) {
        s.asked += "doing"
        val topic = Topics.pick(again(ask, Occasion.DOING))
        val parts = topic?.let { spoken(ask, it) } ?: (Lines.trade(s.p) ?: Lines.greet(s.p)).let { listOf(Spoken(it, it, Activity.TALK, UNTIMED, null)) }
        queue(s, parts, false)
    }

    /** Their best news the player hasn't heard, up to what they'll tell (two; the chatty three; the shy and strangers one), then "That's all I know." */
    private fun news(s: Session, ask: Ask) {
        val r = ask.resident
        val limit = when {
            r?.has(Trait.CHATTY) == true -> 3
            r?.has(Trait.SHY) == true || ask.familiarity == Familiarity.STRANGER -> 1
            else -> 2
        }
        val more = "news" in s.asked
        s.asked += "news"
        val topic = if (s.news < limit) Topics.pick(again(ask, Occasion.NEWS)) else null
        if (topic == null) {
            s.asked += "news.all"
            queue(s, listOfNotNull(part(ask, if (s.news == 0 && !more) "ask.dunno" else "ask.all", Activity.SHRUG)).ifEmpty { old(Lines.gossip(s.p)) }, false)
            return
        }
        s.news++
        topic.rumour?.let { TownTalk.told(ask.level, r, s.player, it) }
        val think = if (more) part(ask, "ask.think", Activity.THINK) else null
        queue(s, listOfNotNull(think) + spoken(ask, topic), false)
    }

    /** "I'm Ransom.": they know the player's name from now on, and answer with theirs. */
    private fun name(s: Session, ask: Ask) {
        s.asked += "name"
        val r = ask.resident
        r?.let { TownTalk.named(ask.level, it, s.player) }
        val given = r?.given ?: s.p.name.string
        val parts = listOfNotNull(part(ask, "name.pleased", Activity.INTRODUCE, args = listOf("=$given", "=${s.player.gameProfile.name}")))
        queue(s, parts.ifEmpty { listOf(Spoken("greet_0", "greet_0", Activity.NOD, UNTIMED, null)) }, false)
    }

    /** A source's own choice ("About the festival..."): asked once; an answer about somewhere (the works) points there as it begins. */
    private fun source(s: Session, ask: Ask, choice: String) {
        s.asked += choice
        val said = Topics.answer(ask, choice)
        queue(s, said?.let { spoken(ask, it, if (it.toward != null) Activity.POINT else Activity.EXPLAIN, it.toward) } ?: listOfNotNull(part(ask, "ask.dunno", Activity.SHRUG)).ifEmpty { old(Lines.reply(s.p)) }, false)
    }

    /** One of the old lines as an answer, while the new ones aren't recorded. */
    private fun old(line: String): List<Spoken> = listOf(Spoken(line, line, Activity.TALK, UNTIMED, null))

    /** The farewell that fits the hour, how they know the player and how the talk ends, with a wave, a touch of the brow or a bow. */
    private fun farewell(s: Session, ask: Ask, ending: Ending) {
        s.ending = ending
        val p = s.p
        val f = ask.familiarity
        val family = when {
            p.role == TownRole.CHILD -> "child.bye"
            ending == Ending.WALKED -> "bye.walked"
            ending == Ending.IDLE -> "bye.idle"
            ending == Ending.OFF -> "bye.off"
            f == Familiarity.WARY || f == Familiarity.HOSTILE -> "bye.wary"
            f == Familiarity.FRIEND -> "bye.friend"
            night(ask.level) -> "bye.night"
            else -> "bye.plain"
        }
        val gesture = when {
            ending == Ending.WALKED || f == Familiarity.WARY || f == Familiarity.HOSTILE -> Activity.NOD
            p.role == TownRole.CHILD || f == Familiarity.FRIEND -> Activity.WAVE
            p.role == TownRole.NOBLE -> Activity.BOW
            p.role == TownRole.GUARD || p.role == TownRole.KNIGHT -> if (hero(ask)) Activity.SALUTE else Activity.NOD
            else -> if (p.random.nextBoolean()) Activity.TIP_HAT else Activity.WAVE
        }
        val parts = listOfNotNull(part(ask, family, gesture, named = true))
            .ifEmpty { listOf(Spoken("greet_5", "greet_5", gesture, UNTIMED, null)) }
        queue(s, parts, true)
    }

    // --- What they say first --------------------------------------------------------------------------------------

    /** The greeting that knows the player (section 2): cold, wary, a friend's, again, after a long while, heard of, a hero, or by the hour. */
    private fun greeting(ask: Ask): Spoken? {
        val p = ask.speaker
        if (p.role == TownRole.CHILD) return part(ask, "child.greet", Activity.WAVE)
        val known = ask.knows
        val today = Days.dayOf(ask.level.overworldClockTime)
        val gesture = when {
            p.role == TownRole.NOBLE -> Activity.BOW
            (p.role == TownRole.GUARD || p.role == TownRole.KNIGHT) && hero(ask) -> Activity.SALUTE
            ask.familiarity == Familiarity.FRIEND -> Activity.WAVE
            ask.familiarity == Familiarity.WARY -> Activity.NOD
            else -> listOf(Activity.WAVE, Activity.NOD, Activity.TIP_HAT)[p.random.nextInt(3)]
        }
        val family = when (ask.familiarity) {
            Familiarity.HOSTILE -> return null
            Familiarity.WARY -> "greet.wary"
            Familiarity.FRIEND -> "greet.friend"
            Familiarity.MET, Familiarity.ACQUAINTED -> when {
                known != null && known.seen.toLong() == today -> "greet.again"
                known != null && today - known.seen >= 7 -> "greet.long"
                else -> byHour(ask) ?: return night(ask, gesture)
            }
            Familiarity.HEARD_OF -> if (hero(ask)) "greet.hero" else "greet.heard"
            Familiarity.STRANGER -> if (ask.resident?.has(Trait.SHY) == true) "greet.shy" else byHour(ask) ?: return night(ask, gesture)
        }
        return part(ask, family, gesture, named = true) ?: Lines.greet(p).let { Spoken(it, it, gesture, UNTIMED, null) }
    }

    /** The greeting for the hour (morning 5 to 12, afternoon 12 to 17, evening 17 to 21); null at night. */
    internal fun byHour(ask: Ask): String? = when (hour(ask.level)) {
        in 5 until 12 -> "greet.morning"
        in 12 until 17 -> "greet.afternoon"
        in 17 until 21 -> "greet.evening"
        else -> null
    }

    /** The night's greetings are the old ones. */
    private fun night(ask: Ask, gesture: Activity): Spoken {
        val line = "night_" + ask.speaker.random.nextInt(3)
        return Spoken(line, line, gesture, UNTIMED, null)
    }

    /** Their best topic, if it's worth going straight on with. */
    private fun opener(ask: Ask): List<Spoken>? {
        val topic = Topics.pick(again(ask, Occasion.OPENER), OPENER) ?: return null
        return spoken(ask, topic)
    }

    /** The choices for [s]'s screen now, Goodbye always last: six rows at most. */
    private fun choices(s: Session, ask: Ask): List<Choice> {
        s.script?.let { return it.choices(ask) }
        val bye = Choice("bye", label("bye"))
        if (s.cold) return listOf(bye)
        val out = ArrayList<Choice>()
        out += Choice("doing", label("doing"), "doing" in s.asked)
        out += Choice("news", label(if ("news" in s.asked) "news_more" else "news"), "news.all" in s.asked)
        if (ask.resident != null && ask.knows?.named != true && "name" !in s.asked) {
            out += Choice("name", Component.translatable("talk.guildmark.choice.name", s.player.gameProfile.name))
        }
        for (c in Topics.choices(again(ask, Occasion.ANSWER)).sortedByDescending { it.pressing }.take(2)) out += Choice(c.id, c.label, c.id in s.asked)
        // Back to the market they came from, while nobody else is talking with the keeper
        if (s.market && talkers(s.p) == 1) return out.take(4) + Choice("trade", label("trade")) + bye
        return out.take(5) + bye
    }

    private fun label(what: String): Component = Component.translatable("talk.guildmark.choice.$what")

    // --- Saying it -------------------------------------------------------------------------------------------------

    /** A take of [family], as it will be said: named for the listener if it can be and they know the name, with [args] or [detail] after it. */
    private fun part(
        ask: Ask, family: String, gesture: Activity?, named: Boolean = false, args: List<String> = listOf(), detail: String? = null, toward: BlockPos? = null,
    ): Spoken? {
        val key = Topics.take(ask, family) ?: return null
        val name = if (named && ask.knows?.named == true && TalkLines.FAMILIES[family]?.named == true) ask.player?.gameProfile?.name else null
        val line = when {
            args.isNotEmpty() -> Speech.of(key, *args.toTypedArray())
            name != null -> Speech.of(key, "=$name")
            else -> key
        }
        return Spoken(Speech.withDetail(line, detail), key, gesture, ticks(ask.speaker, key), toward)
    }

    /** A take of [family] said on its own, for a word without a talk (asleep, can't stop, one at a time). */
    private fun word(ask: Ask, family: String): String? = Topics.take(ask, family)

    /** [topic]'s parts as they'll be said, the first with its gesture. */
    private fun spoken(ask: Ask, topic: Topic): List<Spoken> = spoken(ask, topic.say, topic.gesture ?: gesture(topic), topic.toward)

    private fun spoken(ask: Ask, said: Utterance, gesture: Activity?, toward: BlockPos?): List<Spoken> = said.parts.mapIndexed { i, part ->
        val name = if (part.named && ask.knows?.named == true) ask.player?.gameProfile?.name else null
        val key = Speech.key(part.line)
        val speech = when {
            part.line.isEmpty() -> part.detail ?: ""
            name != null && '|' !in part.line -> Speech.withDetail(Speech.of(part.line, "=$name"), part.detail)
            else -> Speech.withDetail(part.line, part.detail)
        }
        Spoken(speech, key, if (i == 0) gesture else null, if (key.isEmpty()) UNTIMED else ticks(ask.speaker, key), if (i == 0) toward else null)
    }.filter { it.speech.isNotEmpty() }

    /**
     * The gesture a topic is said with when it names none (section 6): from its kind and its tone. The town's works
     * are pointed at (M6: [Topic.toward], where the works stands), and bad times grumbled at as a bad road is.
     */
    internal fun gesture(topic: Topic): Activity = when {
        topic.rumour != null || topic.kind == Kind.NEWS -> Activity.GOSSIP
        topic.kind == Kind.WORKS && topic.toward != null -> Activity.POINT
        topic.tone < 0 && (topic.kind == Kind.ROAD || topic.kind == Kind.FORTUNE) -> Activity.GRUMBLE
        topic.tone < 0 -> Activity.SIGH
        topic.tone > 0 && topic.kind == Kind.DEEDS -> Activity.CHEER
        topic.tone > 0 -> Activity.LAUGH
        topic.kind == Kind.TRADE || topic.kind == Kind.MARKET -> Activity.EXPLAIN
        else -> Activity.TALK
    }

    /** A gesture as someone seated makes it: their seated talk, laugh or lean-in, or nothing they'd have to stand for. */
    internal fun seated(p: Townsperson, gesture: Activity): Activity? {
        if (p.seat == null) return gesture
        return when (gesture) {
            Activity.LAUGH, Activity.CHEER, Activity.CLAP -> Activity.SIT_LAUGH
            Activity.GOSSIP, Activity.WHISPER, Activity.SHUSH -> Activity.SIT_GOSSIP
            Activity.TALK, Activity.EXPLAIN, Activity.INTRODUCE, Activity.THINK, Activity.SIGH, Activity.RECOUNT -> Activity.SIT_TALK
            else -> if (gesture.seated) gesture else null
        }
    }

    /** [p] makes [gesture] as a part begins: pointing the way turns them toward the place first, and setting their work down plays through. */
    private fun gesture(p: Townsperson, gesture: Activity, toward: BlockPos?) {
        if (p.activity == Activity.PAUSE_WORK && !p.actDone) return
        val talking = p.director as? Talking
        if (gesture == Activity.POINT && toward != null && talking != null && p.seat == null) return talking.point(toward)
        seated(p, gesture)?.let { p.act(it) }
    }

    /**
     * How long [p] takes to say take [key]: its length played at their pitch, as TownsfolkClient.speak plays it (an old
     * speaker lower still in the men's take of a family they have none of; children, quicker in the women's, are
     * given the full length).
     */
    internal fun ticks(p: Townsperson, key: String): Int {
        val ticks = TalkLines.ticks(key).takeIf { it > 0 } ?: return UNTIMED
        var pitch = 0.92f + Math.floorMod(p.uuid.leastSignificantBits, 17L) / 100f
        val family = TalkLines.FAMILIES[key.removePrefix("talk.").substringBeforeLast('_')]
        if (Say.timbre(p) == "old" && family?.timbres?.contains("old") != true) pitch *= 0.9f
        return ceil(ticks / pitch).toInt()
    }

    private fun queue(s: Session, parts: List<Spoken>, closing: Boolean) {
        s.waiting = true
        queues.getOrPut(s.p) { ArrayDeque() }.addLast(Answer(s, parts, closing))
    }

    /** [p] says each of [lines] in turn, spaced by their lengths, with nobody to talk to (`/guildmark talk family`). */
    fun recite(p: Townsperson, lines: List<String>) {
        val parts = lines.map { Spoken(it, Speech.key(it), Activity.TALK, ticks(p, Speech.key(it)) + 20, null) }
        queues.getOrPut(p) { ArrayDeque() }.addLast(Answer(null, parts, false))
    }

    // --- Each tick -----------------------------------------------------------------------------------------------------

    private fun tick(server: MinecraftServer) {
        if (sessions.isEmpty() && queues.isEmpty()) return
        if (!enabled()) {
            for (s in sessions.values.toList()) end(s, Ending.GONE)
            queues.clear()
            return
        }
        for (s in sessions.values.toList()) check(s)
        val each = queues.entries.iterator()
        while (each.hasNext()) {
            val (p, queue) = each.next()
            if (!p.isAlive || p.isRemoved) {
                each.remove()
                continue
            }
            speak(p, queue)
            if (queue.isEmpty()) each.remove()
        }
    }

    /** Whether [s] must end: the player gone, out of reach, struck, idle too long, the talk too long, their day calling, danger. */
    private fun check(s: Session) {
        val p = s.p
        val player = s.player
        val level = p.level() as? ServerLevel
        if (level == null || !p.isAlive || p.isRemoved || player.isRemoved || !player.isAlive || player.level() !== level || player.hasDisconnected()) {
            return end(s, Ending.GONE)
        }
        // Fleeing, fighting, or let go by whoever had charge of them
        if (p.activity == Activity.FLEE || p.target != null || p.director == null || p.director !== s.holder) return end(s, Ending.GONE)
        if (s.ending != null) return
        if (player.hurtTime > 0 && player.lastHurtByMob != null) return end(s, Ending.GONE)
        if (player.distanceToSqr(p) > REACH * REACH) return walkedOff(s, level)
        val now = level.gameTime
        // Their answer waits behind someone else's: the screen hears the server's still there
        if (s.waiting && now - s.sent >= ALIVE) {
            Wire.alive(player, p, s.serial)
            s.sent = now
        }
        if (s.script != null || queues[p]?.any { it.session === s } == true) return
        val ask = ask(level, p, player, Occasion.FAREWELL)
        when {
            now - s.last > IDLE -> farewell(s, ask, Ending.IDLE)
            now - s.started > LONGEST -> farewell(s, ask, Ending.OFF)
            due(level, p) -> farewell(s, ask, Ending.OFF)
        }
    }

    /** The player walked off without a goodbye: a word after them, and, once a day, a small slight. */
    private fun walkedOff(s: Session, level: ServerLevel) {
        s.ending = Ending.WALKED
        val ask = ask(level, s.p, s.player, Occasion.FAREWELL)
        val child = s.p.role == TownRole.CHILD
        part(ask, if (child) "child.bye" else "bye.walked", if (child) Activity.WAVE else Activity.WAVE_OFF)?.let {
            s.p.say(it.speech, force = true, ticks = it.ticks + 20L)
            Topics.spoke(s.p, s.player, it.voiced)
            it.gesture?.let { g -> gesture(s.p, g, null) }
        }
        val r = ask.resident
        val today = Days.dayOf(level.overworldClockTime)
        if (r != null && s.script == null) {
            val key = "${r.id}:${s.player.uuid}"
            if (walkedOff[key] != today) {
                walkedOff[key] = today
                TownTalk.slight(level, r, s.player.uuid, Slight.WALKED_OFF)
            }
        }
        end(s, Ending.WALKED)
    }

    /** The answer at the head of [p]'s queue, a part at a time: shown on its player's screen as it begins. */
    private fun speak(p: Townsperson, queue: ArrayDeque<Answer>) {
        val a = queue.firstOrNull() ?: return
        val s = a.session
        if (s != null && sessions[s.player.uuid] !== s && s.ending == null) {
            queue.removeFirst()
            return
        }
        // Someone settling to talk (finishing a move, setting the work down) starts when they're ready
        if ((p.director as? Talking)?.ready == false) return
        val level = p.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (a.at < 0) {
            a.at = now
            if (s != null) {
                answering[p] = s
                s.serial++
                s.waiting = false
                s.sent = now
                val ask = ask(level, p, s.player, Occasion.ANSWER)
                val offer: List<Choice> = if (a.closing) listOf() else choices(s, ask).take(TalkPayloads.MAX_CHOICES)
                s.offered = offer.mapTo(HashSet()) { it.id }
                Wire.say(s.player, p, s.serial, a.parts, offer, a.closing)
            }
        }
        if (now < a.at) return
        if (a.next < a.parts.size) {
            val part = a.parts[a.next]
            p.say(part.speech, force = true, ticks = part.ticks + 20L)
            part.gesture?.let { gesture(p, it, part.toward) }
            if (part.voiced.isNotEmpty()) Topics.spoke(p, s?.player, part.voiced)
            a.next++
            a.at = now + part.ticks + GAP
            return
        }
        queue.removeFirst()
        if (a.closing && s != null) end(s, s.ending ?: Ending.BYE)
    }

    /** [s] is over: its screen closes (unless its farewell closed it), and its answers still waiting are dropped. */
    private fun end(s: Session, ending: Ending) {
        if (sessions[s.player.uuid] === s) sessions.remove(s.player.uuid)
        queues[s.p]?.removeIf { it.session === s && it.at < 0 }
        if (answering[s.p] === s) answering.remove(s.p)
        if (ending != Ending.BYE && ending != Ending.IDLE && ending != Ending.OFF) Wire.close(s.player, s.p)
    }

    // --- What they know ------------------------------------------------------------------------------------------------

    /** Everything the topics may look at for [p] talking to [player] on [occasion]. */
    fun ask(level: ServerLevel, p: Townsperson, player: ServerPlayer?, occasion: Occasion): Ask {
        val life = Days.life(p)
        val resident = life?.resident ?: Residents.get(level).of(p)
        val familiarity = if (player != null) TownTalk.familiarity(level, resident, player) else Familiarity.STRANGER
        val knows = if (player != null && resident != null) TownTalk.knows(level, resident, player.uuid) else null
        val market = life?.let { Trades.market(p, it) }
        return Ask(level, p, resident, life, occasion, player, null, familiarity, knows, market, level.gameTime)
    }

    /** [ask] for another occasion. */
    fun again(ask: Ask, occasion: Occasion): Ask =
        Ask(ask.level, ask.speaker, ask.resident, ask.life, occasion, ask.player, ask.listener, ask.familiarity, ask.knows, ask.market, ask.now)

    /** Why [p] can't stop to talk just now: their word over the shoulder and its gesture; null if they can stop. */
    private fun busy(level: ServerLevel, p: Townsperson): Pair<String, Activity?>? {
        val walking = !p.navigation.isDone
        val wave = if (walking) Activity.NOD_WALK else Activity.WAVE_OFF
        if (!p.carried.isEmpty || p.activity in LADEN || p.routine.task is Pull) return "ask.busy" to null
        if (p.activity == Activity.ANNOUNCE || p.activity == Activity.RING_BELL || p.activity == Activity.LUTE || p.activity == Activity.MINSTREL) return "ask.busy" to null
        val life = Days.life(p) ?: return null
        val now = level.overworldClockTime
        val stretch = Days.stretch(life, now)
        if (stretch.doing == Doing.WORSHIP && Days.restDay(Days.dayOf(now))) return "ask.hush" to if (p.seat == null) Activity.SHUSH else null
        val into = Math.floorMod(Days.offsetOf(now) - stretch.start, 24000)
        if (stretch.doing in MUST && !p.routine.anchored && walking && into < ON_THE_WAY) return "ask.busy" to wave
        if (due(level, p)) return "ask.busy" to wave
        return null
    }

    /** Whether a stretch of [p]'s day that won't wait (work, the watch, the cry, lessons, the rest day's service) is due within [DUE] ticks. */
    private fun due(level: ServerLevel, p: Townsperson): Boolean {
        val life = Days.life(p) ?: return false
        val now = level.overworldClockTime
        val stretch = Days.stretch(life, now)
        val next = DailySource.next(life, stretch) ?: return false
        val service = next.doing == Doing.WORSHIP && Days.restDay(Days.dayOf(now))
        if (next.doing !in MUST && !service) return false
        return Math.floorMod(next.start - Days.offsetOf(now), 24000) <= DUE
    }

    /** The clock's hour, 0 at midnight. */
    private fun hour(level: ServerLevel): Int = Math.floorMod(Math.floorDiv(level.overworldClockTime, 1000L) + 6, 24L).toInt()

    private fun night(level: ServerLevel): Boolean = hour(level).let { it >= 21 || it < 5 }

    /** Whether the player is a hero at the capital's board. */
    internal fun hero(ask: Ask): Boolean {
        val player = ask.player ?: return false
        val capital = ask.life?.places?.capital ?: return false
        return Standing.tier(ask.level, capital.boardAnchor, player) == StandingTier.HERO
    }

    /** The name on the plate: theirs, or what they are. */
    private fun name(p: Townsperson): Component = p.name

    /** What they are on the plate: "baker of Hollowmere", "of Hollowmere" for plain townsfolk; away from a capital, their trade if they're named. */
    private fun epithet(ask: Ask): Component {
        val p = ask.speaker
        val life = ask.life
        val town = life?.places?.name
            ?: return if (p.customName == null) Component.empty() else Component.translatable("entity.guildmark.townsperson.${p.role.id}")
        val trade = when {
            life.crier -> "crier"
            life.mill != null -> "miller"
            life.carter -> "carter"
            else -> p.role.id
        }
        if (trade == TownRole.CITIZEN.id) return Component.translatable("talk.guildmark.epithet.townsfolk", town)
        return Component.translatable("talk.guildmark.epithet", Component.translatable("talk.guildmark.trade.$trade"), town)
    }

    /** The talk screen's side (network/TalkPayloads). */
    private object Wire {
        fun open(player: ServerPlayer, p: Townsperson, name: Component, epithet: Component, knows: Int) {
            PacketDistributor.sendToPlayer(player, TalkOpenPayload(p.id, name, epithet, knows))
        }

        /** [parts] to show as they're said (a part with no take types to the pen), then [choices], or the last words if [closing]. */
        fun say(player: ServerPlayer, p: Townsperson, serial: Int, parts: List<Spoken>, choices: List<Choice>, closing: Boolean) {
            val shown = parts.take(TalkPayloads.MAX_PARTS).map { TalkPart.of(it.speech, if (it.voiced.isEmpty()) 0 else it.ticks) }
            val answers = choices.take(TalkPayloads.MAX_CHOICES).map { TalkChoice(it.id, it.label, it.asked) }
            PacketDistributor.sendToPlayer(player, TalkSayPayload(p.id, serial, shown, answers, closing))
        }

        /** Nothing new: the screen waiting on an answer only hears the server's still there. */
        fun alive(player: ServerPlayer, p: Townsperson, serial: Int) {
            PacketDistributor.sendToPlayer(player, TalkSayPayload(p.id, serial, listOf(), listOf(), false))
        }

        fun close(player: ServerPlayer, p: Townsperson) {
            PacketDistributor.sendToPlayer(player, TalkClosePayload(p.id))
        }
    }
}

/** A talk a director runs itself: its opener, its choices and its answers, shown, voiced and gestured as any talk. */
interface Script {
    fun opener(ask: Ask): Utterance

    fun choices(ask: Ask): List<Choice>

    /** What they say to [choice], and whether that ends the talk. */
    fun answer(ask: Ask, choice: String): Pair<Utterance, Boolean>
}
