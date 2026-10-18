package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Lines
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** What a line is for. */
enum class Occasion { GREET, OPENER, DOING, NEWS, ANSWER, BARK, CHAT, FAREWELL }

/** What a line is about: the weights and the "not twice" rules go by it. */
enum class Kind { DOING, TRADE, HOUSEHOLD, MARKET, EVENT, SEASON, FESTIVAL, WEATHER, DEEDS, DEALINGS, NEWS, ROAD, FACTION, HOMESTEAD }

/** How well the speaker knows the player (living-world/design-voices.md section 3). */
enum class Familiarity {
    STRANGER, HEARD_OF, MET, ACQUAINTED, FRIEND, WARY, HOSTILE;

    /** How well they know them, whatever they think of them: the wary and the hostile have at least met them, or heard. */
    private val closeness: Int get() = when (this) {
        STRANGER -> 0
        HEARD_OF -> 1
        MET, WARY, HOSTILE -> 2
        ACQUAINTED -> 3
        FRIEND -> 4
    }

    /** Whether a topic that needs [least] may be said to someone known like this: a topic for the wary only to the wary or hostile. */
    fun reaches(least: Familiarity): Boolean = when (least) {
        WARY -> this == WARY || this == HOSTILE
        HOSTILE -> this == HOSTILE
        else -> closeness >= least.closeness
    }
}

/** A line as it's said: one or two voiced parts, each with an unvoiced detail. */
class Utterance(val parts: List<Part>) {
    /**
     * [line]: a voiced key (say.guildmark.<line>, townsfolk.voice.<timbre>.<line>), or "" for text alone.
     * [detail]: Speech-format text after it ("talk.detail.price|item.minecraft.bread|3"), or null.
     * [named]: show the line's <line>.named text with the listener's name, if they know it.
     */
    class Part(val line: String, val detail: String? = null, val named: Boolean = false)

    companion object {
        /** A single part. */
        fun of(line: String, detail: String? = null, named: Boolean = false): Utterance = Utterance(listOf(Part(line, detail, named)))
    }
}

/** Something a townsperson could say now. */
class Topic(
    /** What it says, facts and all ("market.dear.bread.no_flour"): never twice running. */
    val id: String,
    val kind: Kind,
    /** How much it matters now, 0 to 1, before the speaker's leanings. */
    val weight: Double,
    val say: Utterance,
    /** The least they must know the listener to say it. */
    val least: Familiarity = Familiarity.STRANGER,
    /** -1 bad, 0, +1 good: the gesture, and the listener's reply. */
    val tone: Int = 0,
    /** Else chosen from the kind and tone (design-voices.md section 6). */
    val gesture: Activity? = null,
    /** Where they point: the well, the gate, the stall. */
    val toward: BlockPos? = null,
    /** The rumour it tells: the listener knows it afterwards. */
    val rumour: Int? = null,
    /** The player it's about. */
    val about: UUID? = null,
)

/** Everything a source may look at. */
class Ask(
    val level: ServerLevel, val speaker: Townsperson, val resident: Resident?, val life: Life?,
    val occasion: Occasion,
    /** Whom they're talking to: a player... */
    val player: ServerPlayer?,
    /** ...or another townsperson, in a chat. */
    val listener: Townsperson?,
    val familiarity: Familiarity, val knows: Acquaintance?,
    val market: Markets.Entry?, val now: Long,
)

/** A choice on the talk screen: its id ("road:ask"), its label, whether it's been asked this talk. */
class Choice(val id: String, val label: Component, val asked: Boolean = false)

/**
 * Something that gives townsfolk things to talk about. Every system registers its source once at setup, whatever its
 * switch, and checks its switch and its data inside [offer], so switches work without a restart.
 */
interface TopicSource {
    val id: String

    /** What this source would have [Ask.speaker] say now; nothing if it has nothing, or its system is off. */
    fun offer(ask: Ask, out: MutableList<Topic>)

    /** At most one choice for the talk screen ("About the road..."), shown while it applies. */
    fun choice(ask: Ask): Choice? = null

    /** The answer to this source's choice, or null if it no longer applies. */
    fun answer(ask: Ask, choice: String): Utterance? = null
}

/** A topic on offer as [Topics.all] weighs it: its weight times how it suits the listener, the speaker and the moment. */
class Scored(val topic: Topic, val familiarity: Double, val leaning: Double, val freshness: Double) {
    val score: Double = topic.weight * familiarity * leaning * freshness
}

/**
 * What townsfolk talk about (design-voices.md section 1): every source's topics, scored by weight, how well the
 * speaker knows the listener, the speaker's leanings and what's been said lately, and one drawn from the best five.
 * While no source is registered, the old [Lines] answer as before.
 */
object Topics {
    /** Below this nothing is worth saying: a nod, or the greeting alone. */
    const val LEAST = 0.08

    /** How many of the best go into the draw. */
    private const val DRAW = 5

    /** How many topic ids each townsperson keeps per listener, and for how long (ticks). */
    private const val RECENT = 6
    private const val RECENT_TICKS = 20 * 60 * 20L

    /** No one says the same take twice within this long, and each player's ring holds this many for this long. */
    private const val SAME_TAKE = 2 * 60 * 20L
    private const val RING = 16
    private const val RING_TICKS = 5 * 60 * 20L

    private val sources = CopyOnWriteArrayList<TopicSource>()
    private val broken = HashSet<String>()

    /** Each townsperson's last topics per listener (a player's id, or "r<resident>"): id and game time. */
    private val recent = WeakHashMap<Townsperson, HashMap<String, ArrayDeque<Pair<String, Long>>>>()

    /** The takes each townsperson said lately, and when. */
    private val takes = WeakHashMap<Townsperson, ArrayDeque<Pair<String, Long>>>()

    /** The last takes each player heard, town-wide. */
    private val rings = HashMap<UUID, ArrayDeque<Pair<String, Long>>>()

    /** Per player, the day and, for each topic id, the townsfolk who told it them that day. */
    private val toldToday = HashMap<UUID, Pair<Long, HashMap<String, HashSet<UUID>>>>()

    /** Adds [source], once (a second with the same id is ignored). */
    fun register(source: TopicSource) {
        if (sources.none { it.id == source.id }) sources.add(source)
    }

    /** Every registered source, in the order they came. */
    val registered: List<TopicSource> get() = sources

    /** What [Ask.speaker] says for [Ask.occasion], or null: nothing worth saying (they nod). */
    fun pick(ask: Ask): Topic? {
        if (sources.isEmpty()) return old(ask)
        val best = all(ask).take(DRAW).filter { it.score >= LEAST }
        if (best.isEmpty()) return null
        // The chance goes with the score squared, so the best usually wins and the others come up now and then
        var roll = ask.speaker.random.nextDouble() * best.sumOf { it.score * it.score }
        val chosen = best.firstOrNull { roll -= it.score * it.score; roll <= 0 } ?: best.first()
        said(ask, chosen.topic)
        return chosen.topic
    }

    /** Every topic on offer, scored, with the reasons, best first (for /guildmark talk topics). */
    fun all(ask: Ask): List<Scored> = offered(ask).filter { ask.familiarity.reaches(it.least) }.map { score(ask, it) }.sortedByDescending { it.score }

    /** Every source's topics for [ask], before scoring; a source that throws is skipped and logged once. */
    fun offered(ask: Ask): List<Topic> {
        val out = ArrayList<Topic>()
        for (source in sources) {
            val before = out.size
            try {
                source.offer(ask, out)
            } catch (e: Exception) {
                while (out.size > before) out.removeAt(out.size - 1)
                if (broken.add(source.id)) Guildmark.LOGGER.error("Townsfolk talk: the {} topics failed and are skipped", source.id, e)
            }
        }
        return out
    }

    /** The choices the sources add to the talk screen for [ask]. */
    fun choices(ask: Ask): List<Choice> = sources.mapNotNull { source ->
        try {
            source.choice(ask)
        } catch (e: Exception) {
            if (broken.add(source.id)) Guildmark.LOGGER.error("Townsfolk talk: the {} choice failed and is skipped", source.id, e)
            null
        }
    }

    /** The answer to [choice] ("<source id>:<what>"), from the source it names; null if it no longer applies. */
    fun answer(ask: Ask, choice: String): Utterance? {
        val source = sources.firstOrNull { choice.startsWith(it.id + ":") } ?: return null
        return try {
            source.answer(ask, choice)
        } catch (e: Exception) {
            if (broken.add(source.id)) Guildmark.LOGGER.error("Townsfolk talk: the {} answer failed and is skipped", source.id, e)
            null
        }
    }

    /** Weighs [topic] for [ask]. */
    fun score(ask: Ask, topic: Topic): Scored = Scored(topic, familiarity(topic.kind, ask.familiarity), leaning(ask, topic), freshness(ask, topic))

    /** [Ask.speaker] said [topic] to their listener: it's out for a while, and other tellers of it today count for less. */
    fun said(ask: Ask, topic: Topic) {
        val now = ask.level.gameTime
        val key = listener(ask) ?: return
        val mine = recent.getOrPut(ask.speaker) { HashMap() }.getOrPut(key) { ArrayDeque() }
        mine.addLast(topic.id to now)
        while (mine.size > RECENT) mine.removeFirst()
        val player = ask.player ?: return
        val day = ask.level.overworldClockTime / 24000L
        val today = toldToday[player.uuid]?.takeIf { it.first == day } ?: (day to HashMap<String, HashSet<UUID>>()).also { toldToday[player.uuid] = it }
        today.second.getOrPut(topic.id) { HashSet() }.add(ask.speaker.uuid)
    }

    /** A take of [family] for [ask]'s speaker to say to their listener, the freshest first; null if none is recorded. */
    fun take(ask: Ask, family: String): String? = TalkLines.pick(family, avoid(ask.speaker, ask.player), ask.speaker.random)

    /** The takes to keep from [speaker] now: theirs of the last two minutes, and the last [RING] that [player] heard. */
    fun avoid(speaker: Townsperson, player: ServerPlayer?): Set<String> {
        val now = speaker.level().gameTime
        val out = HashSet<String>()
        takes[speaker]?.let { said -> said.removeIf { now - it.second > SAME_TAKE }; said.mapTo(out) { it.first } }
        if (player != null) rings[player.uuid]?.let { ring -> ring.removeIf { now - it.second > RING_TICKS }; ring.mapTo(out) { it.first } }
        return out
    }

    /** [speaker] said take [key], to [player] if a player heard it. */
    fun spoke(speaker: Townsperson, player: ServerPlayer?, key: String) {
        val now = speaker.level().gameTime
        takes.getOrPut(speaker) { ArrayDeque() }.addLast(key to now)
        if (player == null) return
        val ring = rings.getOrPut(player.uuid) { ArrayDeque() }
        ring.addLast(key to now)
        while (ring.size > RING) ring.removeFirst()
    }

    /** The player is gone: what they heard is let go. */
    fun forget(player: UUID) {
        rings.remove(player)
        toldToday.remove(player)
    }

    private fun listener(ask: Ask): String? = ask.player?.uuid?.toString() ?: ask.listener?.let { if (it.resident >= 0) "r${it.resident}" else it.uuid.toString() }

    /** Whom it's said to (section 1's table). */
    private fun familiarity(kind: Kind, f: Familiarity): Double {
        val row = when (kind) {
            Kind.DOING, Kind.TRADE, Kind.WEATHER, Kind.SEASON, Kind.FACTION -> PLAIN
            Kind.MARKET, Kind.EVENT, Kind.FESTIVAL, Kind.ROAD, Kind.HOMESTEAD -> TOWN
            Kind.NEWS -> NEWS
            Kind.HOUSEHOLD -> HOUSEHOLD
            Kind.DEEDS -> DEEDS
            Kind.DEALINGS -> DEALINGS
        }
        return row[f.ordinal]
    }

    /** The speaker's leanings, from their traits. */
    private fun leaning(ask: Ask, topic: Topic): Double {
        val r = ask.resident ?: return 1.0
        var x = 1.0
        if (topic.kind == Kind.NEWS && r.has(Trait.CHATTY)) x *= 1.3
        if (ask.familiarity == Familiarity.STRANGER && ask.player != null && r.has(Trait.SHY)) x *= 0.5
        if (topic.tone < 0) {
            if (r.has(Trait.GRUMPY)) x *= 1.5
            if (r.has(Trait.CHEERFUL)) x *= 0.7
        } else if (topic.tone > 0) {
            if (r.has(Trait.CHEERFUL)) x *= 1.5
            if (r.has(Trait.GRUMPY)) x *= 0.7
        }
        if (r.has(Trait.DEVOUT) && (topic.kind == Kind.FESTIVAL || "worship" in topic.id || "temple" in topic.id)) x *= 1.5
        if (r.has(Trait.DRINKER) && "tavern" in topic.id) x *= 1.5
        if (r.has(Trait.DILIGENT) && topic.kind == Kind.TRADE) x *= 1.5
        if (r.has(Trait.IDLE) && (topic.kind == Kind.WEATHER || topic.kind == Kind.NEWS)) x *= 1.3
        return x
    }

    /** Out if said to this listener lately; halved for each other townsperson who told the player the same today. */
    private fun freshness(ask: Ask, topic: Topic): Double {
        val now = ask.level.gameTime
        val key = listener(ask)
        if (key != null) {
            val mine = recent[ask.speaker]?.get(key)
            if (mine != null) {
                mine.removeIf { now - it.second > RECENT_TICKS }
                if (mine.any { it.first == topic.id }) return 0.0
            }
        }
        val player = ask.player ?: return 1.0
        val day = ask.level.overworldClockTime / 24000L
        val tellers = toldToday[player.uuid]?.takeIf { it.first == day }?.second?.get(topic.id) ?: return 1.0
        val others = tellers.count { it != ask.speaker.uuid }
        return Math.pow(0.5, others.toDouble())
    }

    /** The old lines, while nothing is registered. */
    private fun old(ask: Ask): Topic? {
        val p = ask.speaker
        val line = when (ask.occasion) {
            Occasion.GREET, Occasion.BARK -> Lines.greet(p)
            Occasion.OPENER, Occasion.ANSWER, Occasion.DOING -> Lines.talk(p, ask.player)
            Occasion.NEWS, Occasion.CHAT -> Lines.gossip(p)
            Occasion.FAREWELL -> return null
        }
        return Topic("old.$line", Kind.DOING, 0.5, Utterance.of(line))
    }

    // The rows of section 1's table, by Familiarity's order: stranger, heard of, met, acquainted, friend, wary, hostile
    private val PLAIN = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 0.8, 0.6, 0.6)
    private val TOWN = doubleArrayOf(0.8, 1.0, 1.0, 1.0, 1.0, 0.5, 0.5)
    private val NEWS = doubleArrayOf(0.3, 0.6, 0.8, 1.0, 1.2, 0.3, 0.3)
    private val HOUSEHOLD = doubleArrayOf(0.0, 0.0, 0.3, 0.8, 1.2, 0.0, 0.0)
    private val DEEDS = doubleArrayOf(0.6, 1.5, 1.2, 1.0, 1.0, 1.5, 1.5)
    private val DEALINGS = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0, 1.5, 1.5)
}
