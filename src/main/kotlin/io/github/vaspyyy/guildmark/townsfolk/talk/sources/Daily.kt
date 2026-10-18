package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Lines
import io.github.vaspyyy.guildmark.townsfolk.Schedule
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Stretch
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Phase
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import net.minecraft.core.BlockPos

/**
 * What a townsperson is about (living-world/design-voices.md section 1, "Doing"): the stretch of their day they're in,
 * said as one of the daily round's families ("Fetching water. The house never has enough."), with what comes next and
 * when after it in the text ("Then the tavern, at 18:30."). Work, the watch, the cart and the cry are the trades'
 * ([TradeSource]); children say their own. Always on offer, and what "What are you up to?" is answered with.
 */
object DailySource : TopicSource {
    override val id = "doing"

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (!fits(ask.occasion)) return
        val life = ask.life ?: return
        val now = Days.stretch(life, ask.level.overworldClockTime)
        val p = ask.speaker
        if (p.role == TownRole.CHILD) {
            val line = Topics.take(ask, "child.doing") ?: return
            out += Topic("doing.child.${now.doing.name.lowercase()}", Kind.DOING, weight(ask), Utterance.of(line), gesture = Activity.TALK)
            return
        }
        if (now.doing in WORKING) return
        val rest = Days.restDay(Days.dayOf(ask.level.overworldClockTime)) && now.doing.phase == Phase.FREE
        val family = if (rest) "doing.rest" else FAMILY[now.doing] ?: return
        val line = Topics.take(ask, family) ?: return
        val next = next(life, now)
        val detail = next?.let { Speech.of("talk.detail.day.next", "say.guildmark.talk.day.${it.doing.name.lowercase()}", "=" + Days.hourOf(it.start)) }
        val id = "doing.${now.doing.name.lowercase()}" + (next?.let { ".then.${it.doing.name.lowercase()}" } ?: "")
        out += Topic(id, Kind.DOING, weight(ask), Utterance.of(line, detail), gesture = if (toward(life) != null) Activity.POINT else Activity.EXPLAIN, toward = toward(life))
    }

    /** The occasions what they're doing is said on. */
    fun fits(occasion: Occasion): Boolean = occasion == Occasion.DOING || occasion == Occasion.OPENER || occasion == Occasion.BARK || occasion == Occasion.CHAT

    /** What they're doing matters most when they're asked it outright. */
    fun weight(ask: Ask): Double = if (ask.occasion == Occasion.DOING) 1.0 else 0.5

    /** The stretch of [life]'s day after [now] (tomorrow's first, past the last), unless it's only more of the same. */
    fun next(life: Life, now: Stretch): Stretch? {
        val stretches = life.agenda?.stretches ?: return null
        val i = stretches.indexOf(now)
        val after = stretches.drop(i + 1).firstOrNull { it.doing != now.doing } ?: return null
        return after
    }

    /** Where they're bound this stretch, to point at: the well, a stall, a house. */
    private fun toward(life: Life): BlockPos? = when (val bound = life.bound) {
        is BlockPos -> bound
        is CityPlaces.Stall -> bound.customer.pos
        is CityPlaces.Lodging -> bound.front?.outside
        else -> null
    }

    /** The stretches that are someone's trade, said by [TradeSource]. */
    private val WORKING = setOf(Doing.WORK, Doing.WATCH, Doing.CART, Doing.CRY, Doing.LESSONS, Doing.PLAY)

    /** The daily round's family for each stretch that isn't work (section 5). */
    private val FAMILY = mapOf(
        Doing.RISE to "doing.rise",
        Doing.BREAKFAST to "doing.breakfast",
        Doing.LUNCH to "doing.lunch",
        Doing.MARKET to "doing.market",
        Doing.WATER to "doing.water",
        Doing.ERRAND to "doing.errand",
        Doing.VISIT to "doing.visit",
        Doing.WORSHIP to "doing.worship",
        Doing.STROLL to "doing.stroll",
        Doing.TAVERN to "doing.tavern",
        Doing.SUPPER to "doing.supper",
        Doing.EVENING to "doing.evening",
        Doing.BED to "doing.bed",
    )
}

/**
 * What a townsperson's trade has them at (design-voices.md section 1, "Trade"), from its real state: the forge busy
 * or idle, the ovens going or cold for want of flour, the fisher's basket, the farmer's season, the carter's cart,
 * the crier's day, the miller's stones, the watch by day or night. Their own trouble (cold ovens, an empty basket)
 * matters as much as anything they could say.
 */
object TradeSource : TopicSource {
    override val id = "trade"

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (!DailySource.fits(ask.occasion)) return
        val p = ask.speaker
        val life = ask.life
        val working = life == null && Schedule.phase(p) == Phase.WORK ||
            life != null && Days.stretch(life, ask.level.overworldClockTime).doing.let { it == Doing.WORK || it == Doing.WATCH || it == Doing.CART || it == Doing.CRY }
        if (!working || p.role == TownRole.CHILD) return
        val (family, trouble) = family(ask, life) ?: return
        val line = Topics.take(ask, family) ?: return
        val detail = when (family) {
            "doing.baker_cold" -> "talk.detail.work.no_flour"
            "doing.fisher_catch" -> life?.basket?.takeIf { it > 0 }?.let { Speech.of("talk.detail.work.basket", it) }
            else -> null
        }
        val base = DailySource.weight(ask)
        val weight = when (trouble) {
            -1 -> 1.0
            1 -> maxOf(base, 0.6)
            else -> base
        }
        val facts = if (family == "doing.fisher_catch") ".${life?.basket ?: 0}" else ""
        out += Topic("trade.${family.removePrefix("doing.")}$facts", Kind.TRADE, weight, Utterance.of(line, detail), tone = trouble,
            gesture = if (trouble < 0) Activity.SIGH else Activity.EXPLAIN)
    }

    /** The trade family [ask]'s speaker says now, and whether it's trouble (-1), luck (+1) or neither. */
    private fun family(ask: Ask, life: Life?): Pair<String, Int>? {
        val p = ask.speaker
        if (life != null) {
            if (life.crier) return "doing.crier" to 0
            if (life.mill != null) return "doing.miller" to 0
            if (life.carter) return "doing.carter" to 0
        }
        return when (p.role) {
            TownRole.SMITH -> if (life != null && Trades.forgeIdle(p, life)) "doing.smith_idle" to 0 else "doing.smith" to 0
            TownRole.BAKER -> if (life != null && Trades.ovensCold(p, life)) "doing.baker_cold" to -1 else "doing.baker" to 0
            TownRole.MERCHANT -> "doing.merchant" to 0
            TownRole.GUARD -> (if (p.nightWatch || life?.nightWatch == true) "doing.guard_night" else "doing.guard") to 0
            TownRole.FARMER -> farmer(ask) to 0
            TownRole.FISHER -> fisher(ask, life)
            TownRole.INNKEEPER -> "doing.inn" to 0
            TownRole.PRIEST -> "doing.priest" to 0
            TownRole.NOBLE -> "doing.noble" to 0
            TownRole.BARD -> "doing.bard" to 0
            TownRole.MAGE -> "doing.mage" to 0
            TownRole.KNIGHT -> "doing.knight" to 0
            TownRole.THIEF -> "doing.thief" to 0
            TownRole.CITIZEN -> (if (p.work != null) "doing.craft" else "doing.citizen") to 0
            TownRole.CHILD -> null
        }
    }

    /** Sowing in spring, the harvest in autumn, tending the rest of the year (and always, with no seasons). */
    private fun farmer(ask: Ask): String {
        if (!Seasons.enabled()) return "doing.farmer_tend"
        return when (Seasons.today(ask.level).season) {
            Season.SPRING -> "doing.farmer_sow"
            Season.AUTUMN -> "doing.farmer_reap"
            else -> "doing.farmer_tend"
        }
    }

    /** Fish in the basket is luck; an empty one after a while at the water is trouble; otherwise just fishing. */
    private fun fisher(ask: Ask, life: Life?): Pair<String, Int> {
        val basket = life?.basket ?: return "doing.fisher" to 0
        if (basket > 0) return "doing.fisher_catch" to 1
        val stretch = Days.stretch(life, ask.level.overworldClockTime)
        val into = Math.floorMod(Days.offsetOf(ask.level.overworldClockTime) - stretch.start, 24000)
        return if (into > FISHING_A_WHILE) "doing.fisher_none" to -1 else "doing.fisher" to 0
    }

    /** How long a fisher must have been at it, in ticks, before an empty basket is worth a word. */
    private const val FISHING_A_WHILE = 1500
}

/**
 * A faction member's word for a player (design-voices.md section 1, "Faction"; the old [Lines.kin]): warm to one of
 * their own, cold to someone their faction threw out or who sides with its rival.
 */
object FactionSource : TopicSource {
    override val id = "faction"

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        val player = ask.player ?: return
        if (ask.occasion != Occasion.OPENER && ask.occasion != Occasion.BARK && ask.occasion != Occasion.GREET) return
        if (Faction.of(ask.speaker.role) == null) return
        val line = Lines.kin(ask.speaker, player) ?: return
        val cold = "_cold_" in line
        out += Topic("faction.${if (cold) "cold" else "kin"}", Kind.FACTION, if (cold) 0.8 else 0.6, Utterance.of(line), tone = if (cold) -1 else 1,
            gesture = if (cold) Activity.ARMS_FOLDED else Activity.NOD)
    }
}

/** The thread's own sources, registered once at setup. */
object DailySources {
    fun init() {
        Topics.register(DailySource)
        Topics.register(TradeSource)
        Topics.register(FactionSource)
    }
}
