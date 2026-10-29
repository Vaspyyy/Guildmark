package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkLooks
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The topic sources of living-world/design-voices.md section 1 that the town, its market, the sky, the calendar and
 * the townsfolk's memory give: what goes on in a household, at the market, in the sky, in the season and at the
 * festival, in the streets, the player's deeds and dealings, the news, the player's homestead, and (M6) the town's works
 * and how the town is faring, its households' comings and goings with it. Each is registered once at setup by [init],
 * whatever its switch, and checks its own switch and its facts each time it's asked, so it offers a topic only while
 * the world holds it true.
 *
 * Not here: the doing, trade and faction sources (the talk's own), the road (M4's, round Caravans.talk) and the
 * supplier (M3's), each registered with its system as these are.
 */
object TalkSources {
    private var started = false

    /** Every source here, in the order they're asked. */
    val ALL: List<TopicSource> = listOf(
        HouseholdTopics, MarketTopics, WeatherTopics, SeasonTopics, FestivalTopics, TownTopics, DeedTopics, DealingTopics,
        NewsTopics, HomesteadTopics, WorksTopics, FortuneTopics,
        KitchenTopics,
        TradeTopics,
        TaleTopics,
        io.github.vaspyyy.guildmark.village.live.talk.VillageTopics,
    )

    /** Registers every source with [Topics] and starts what they keep watch on: once, at setup (Townsfolk.init). */
    fun init() {
        if (started) return
        started = true
        ALL.forEach(Topics::register)
        // Villages (V6): the bellmen's word, looked at as the day goes
        io.github.vaspyyy.guildmark.village.live.talk.Bellman.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { forget() }
    }

    /** What the sources keep watch on as the world goes: the sky each second, the festivals and the streets every five. */
    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val t = server.tickCount
        if (t % 20 == 0) WeatherTopics.watch(level)
        if (t % 100 == 0) FestivalTopics.watch(level)
        if (t % 100 == 50) TownTopics.watch(level)
    }

    /** The server has stopped: nothing it saw is kept for the next. */
    private fun forget() {
        WeatherTopics.forget()
        FestivalTopics.forget()
        TownTopics.forget()
        NewsTopics.forget()
        WorksTopics.forget()
        FortuneTopics.forget()
        TaleTopics.forget()
    }
}

/** What the sources share: a take of a family, the clock and the day, the speaker's town, and the words details use. */
internal object Say {
    /** The lang keys of the words details are put together from: a place, a time, a person (each a Speech argument). */
    const val WORD = "say.guildmark.talk.detail.word."

    /** A talk, a chat, or the answer to a question: where any topic may come up. */
    val TALK: Set<Occasion> = setOf(Occasion.OPENER, Occasion.ANSWER, Occasion.CHAT)

    /** Below this from a town's heart, without its walls to go by, a place is in town. */
    private const val TOWN = 96.0

    /** Out to this far it's close by; to [AWAY], away; beyond, far off. */
    private const val NEAR = 400.0
    private const val AWAY = 1500.0

    /** A take of [family] for [ask]'s speaker: one their listener hasn't heard lately, or "" while none is recorded. */
    fun take(ask: Ask, family: String): String = TalkLines.pick(family, Topics.avoid(ask.speaker, ask.player), ask.speaker.random) ?: ""

    /**
     * [family] said with [detail] after it; null if there'd be nothing to show (no take recorded, and no detail). A family of the
     * kitchen's with no take is read as one of its wordings, the detail in grey after it ([KitchenLines]).
     */
    fun part(ask: Ask, family: String, detail: String?): Utterance.Part? {
        val take = take(ask, family)
        if (take.isEmpty()) KitchenLines.text(ask, family, detail)?.let { return it }
        if (take.isEmpty() && detail.isNullOrEmpty()) return null
        return Utterance.Part(take, detail, take.isNotEmpty() && TalkLines.FAMILIES[family]?.named == true)
    }

    /** A take of [family] said ahead of another part (a lead-in), with [detail]; null while it has no take (but for a kitchen family, which reads as one of its wordings). */
    fun lead(ask: Ask, family: String, detail: String? = null): Utterance.Part? {
        val take = take(ask, family)
        if (take.isEmpty()) return KitchenLines.text(ask, family, detail)
        return Utterance.Part(take, detail, TalkLines.FAMILIES[family]?.named == true)
    }

    /** [part] as a whole line. */
    fun line(ask: Ask, family: String, detail: String?): Utterance? = part(ask, family, detail)?.let { Utterance(listOf(it)) }

    /** The day as the town counts it ([VillageBoards.day]: from six in the morning, as residents' days and deaths are kept). */
    fun day(level: ServerLevel): Long = VillageBoards.day(level)

    /** How far into that day it is, in ticks from six in the morning. */
    fun time(level: ServerLevel): Int = Math.floorMod(level.overworldClockTime, 24000L).toInt()

    /** The clock's hour, 0 at midnight, with its fraction. */
    fun hour(level: ServerLevel): Double = (time(level) / 1000.0 + 6.0) % 24.0

    fun child(p: Townsperson): Boolean = p.role == TownRole.CHILD

    /** The voice [p] speaks in, as the client picks it: man, woman, old or child. */
    fun timbre(p: Townsperson): String =
        TownsfolkLooks.BY_ROLE[p.role.id]?.let { it.getOrNull(p.look) ?: it.firstOrNull() }?.timbre ?: if (child(p)) "child" else "man"

    /** The market of [ask]'s town, if markets are on and it's open: a village's too, with Village Bellman. */
    fun market(ask: Ask): Markets.Entry? {
        if (!Markets.enabled()) return null
        return ask.market ?: ask.resident?.let {
            Markets.get(ask.level)[it.capital]
                ?: if (io.github.vaspyyy.guildmark.village.live.talk.VillageCry.enabled()) Markets.find(ask.level, it.capital.asLong()) else null
        }
    }

    /** A player's name as the world knows it, whether they're on or not; null if it doesn't. */
    fun name(level: ServerLevel, id: UUID): String? =
        level.server.playerList.getPlayer(id)?.name?.string ?: level.server.services().nameToIdCache().get(id).map { it.name }.orElse(null)

    /** Whether the townsfolk remember players (townsfolkMemory). */
    fun memory(): Boolean = setting { GuildmarkConfig.TOWNSFOLK_MEMORY.get() }

    /** Whether news goes from person to person (townsfolkRumours); off, they know only what they saw and what was cried. */
    fun rumours(): Boolean = setting { GuildmarkConfig.TOWNSFOLK_RUMOURS.get() }

    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }

    /** The way from [from] to [to] by the compass: "north", "east", "south" or "west". */
    fun way(from: BlockPos, to: BlockPos): String {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (abs(dx) > abs(dz)) (if (dx > 0) "east" else "west") else if (dz > 0) "south" else "north"
    }

    /** The word for the way from [from] to [to] ("direction.guildmark.north"). */
    fun wayKey(from: BlockPos, to: BlockPos): String = "direction.guildmark." + way(from, to)

    /**
     * Where [at] is, as someone in the town whose heart is [from] puts it: here in town, close by, away, or far off, by
     * the compass ("away to the north"); a Speech argument.
     */
    fun where(level: ServerLevel, from: BlockPos, at: BlockPos): String {
        val dx = (at.x - from.x).toDouble()
        val dz = (at.z - from.z).toDouble()
        val d = sqrt(dx * dx + dz * dz)
        val capital = if (d < NEAR) Capitals.covering(level, at) else null
        if (capital != null && capital.center == from && capital.inTown(at.x, at.z)) return WORD + "where.town"
        if (capital == null && d < TOWN) return WORD + "where.town"
        val reach = when {
            d < NEAR -> "near"
            d < AWAY -> "away"
            else -> "far"
        }
        return WORD + "where.$reach." + way(from, at)
    }

    /** A way that isn't the way from [from] to [at] ("out west"), for a muddled telling; [seed] picks which. */
    fun muddled(from: BlockPos?, at: BlockPos?, seed: Int): String {
        val ways = listOf("north", "east", "south", "west")
        val truth = if (from != null && at != null) ways.indexOf(way(from, at)) else -1
        val pick = if (truth < 0) Math.floorMod(seed, 4) else (truth + 1 + Math.floorMod(seed, 3)) % 4
        return WORD + "where.muddle." + ways[pick]
    }

    /** When something was, [days] days ago, as a word for the end of a detail ("yesterday", "last week"). */
    fun ago(days: Long): String = WORD + when {
        days < 0 -> "when.lately"
        days == 0L -> "when.today"
        days == 1L -> "when.yesterday"
        days <= 3 -> "when.days"
        days <= 9 -> "when.week"
        else -> "when.long"
    }

    /** "Today.", "Yesterday." or "5 days ago.": a whole detail for something [days] days ago. */
    fun agoDetail(days: Long): String = when {
        days <= 0 -> "talk.detail.when.0"
        days == 1L -> "talk.detail.when.1"
        else -> "talk.detail.when.days|$days"
    }
}
