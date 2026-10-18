package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Lot
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.RoadNews
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import net.minecraft.network.chat.Component

/**
 * The road into the speaker's capital (design-road.md section 2, "Townsfolk"), all from the records the wagons keep
 * (see [Caravans]): grumbling while a road is Troubled or worse, about its band once it has a name and a price on its
 * head, about a wagon taken lately and where, the goods dear since and the bread gone up; and then relief: wagons in
 * again, the band broken, prices coming down, the road safe again. The spoken lines are the townsfolk's own road lines
 * (gossip_road_1 to 8 while there's trouble, gossip_road_clear_1 to 4 after it), each with the facts after it in grey;
 * the keepers and the gate's guards also tell, in plain words, when the next wagon is due and what's just come in.
 * "About the road..." asks how each road stands. Registered with the wagons (see [Caravans.register]).
 */
object RoadTopics : TopicSource {
    override val id = "road"

    private const val ASK = "road:ask"

    private val OCCASIONS = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** Market hours a wagon taken is fresh news; and how long the good news after trouble stays news. */
    private const val FRESH = 24L
    private const val GLAD = 48L

    /** Market hours after the wagons get in again that prices coming down are still the road's doing. */
    private const val EASING = 72L

    /** How far over its usual bread must be, and rising, for the road to be blamed for it. */
    private const val BREAD_DEAR = 1.2

    private const val OVER = "news.guildmark.market.over.waylaid"

    /** A road into the town as its records stand: its leg, how much trouble it's in, and the band that works it, if the town knows it. */
    private class Way(val leg: CaravanRoutes.Leg, val peril: Int, val band: OutlawBand?) {
        val stage: RoadPeril.Stage = RoadPeril.Stage.of(peril)

        /** Its name as a Speech argument ("road.guildmark.way.n": "the north road"). */
        val name: String get() = "road.guildmark.way." + leg.side.name.lowercase()
    }

    /** The town [Ask.speaker] speaks for, as the wagons know it: its heart, its roads (the worst first), and its market. */
    private class Town(val capital: Long, val ways: List<Way>, val data: CaravanData, val entry: Markets.Entry?) {
        val worst: Way get() = ways.first()
        val news: List<RoadNews> get() = data.capitalOrNull(capital)?.news ?: listOf()
    }

    private fun town(ask: Ask): Town? {
        if (!Caravans.enabled()) return null
        val capital = (ask.resident?.capital ?: Say.market(ask)?.capital)?.asLong() ?: return null
        val legs = CaravanRoutes.legs(ask.level, capital)?.takeIf { it.isNotEmpty() } ?: return null
        val data = CaravanData.get(ask.level)
        val camps = OutlawCamps.get(ask.level)
        val ways = legs.map { leg ->
            val rec = data.roadOrNull(capital, leg.road)
            // The band that last struck it, or the one camped near it, once its captain has a name to go by
            val band = if (!Caravans.ambushes()) null else (rec?.band?.takeIf { it >= 0 }?.let(camps::band) ?: CaravanRoutes.band(ask.level, leg))
            Way(leg, rec?.peril ?: 0, band?.takeIf { !it.slain && (it.built || it.named) })
        }.sortedByDescending { it.peril }
        return Town(capital, ways, data, Say.market(ask))
    }

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS || Say.child(ask.speaker)) return
        val town = town(ask) ?: return
        val level = ask.level
        val hour = Markets.hourOf(level)
        val t = level.overworldClockTime
        val bark = ask.occasion == Occasion.BARK
        val news = ask.occasion == Occasion.NEWS
        val m = town.entry?.market
        val waylaid = m?.waylaid()
        val worst = town.worst

        // A wagon taken lately: where, and the dearest of what it carried; the driver, if they're still sat at the gate
        if (waylaid != null && hour - waylaid.since in 0..FRESH) {
            val place = waylaid.place.takeIf { it in Caravans.PLACES } ?: "road"
            val good = waylaid.goods.maxByOrNull { it.base }
            val detail = if (good != null) Speech.of("talk.detail.road.taken.$place", good.key + ".many") else "talk.detail.road.taken.$place.none"
            val parts = mutableListOf(Utterance.Part(pick(ask, listOf("gossip_road_6")), detail))
            if (!bark && town.data.drivers.any { it.capital == town.capital && it.until > t }) parts.add(Utterance.Part("", "talk.detail.road.driver"))
            out.add(Topic("road.taken.${waylaid.since}", Kind.ROAD, 0.85 - 0.3 * (hour - waylaid.since) / FRESH, Utterance(parts), tone = -1))
        }

        // Trouble on the road: three spears a wagon, a noon convoy, or none going but with an escort
        if (worst.stage.troubled && (!bark || worst.stage == RoadPeril.Stage.CLOSED)) {
            val lines = mutableListOf("gossip_road_1", "gossip_road_3")
            if (worst.stage >= RoadPeril.Stage.PERILOUS) lines.add("gossip_road_8")
            val parts = mutableListOf(Utterance.Part(pick(ask, lines), Speech.of("talk.detail.road.stage.${worst.stage.id}", worst.name)))
            // To a player: there's work in it, on the capital's board
            if (ask.player != null && !bark && Caravans.escorts()) parts.add(Utterance.Part("", Speech.of("talk.detail.road.escort", worst.name)))
            out.add(Topic("road.trouble.${worst.leg.road}.${worst.stage.id}", Kind.ROAD, 0.5 + 0.1 * worst.stage.ordinal, Utterance(parts), tone = -1))
        }

        // The band that works it, once it has a name and a price on its head
        val banded = town.ways.firstOrNull { it.band != null && it.stage.troubled }
        val band = banded?.band
        if (band != null && !bark) {
            val detail = Speech.of("talk.detail.road.band", "${Outlaws.NAME_KEY}.${band.name}", "${Outlaws.EPITHET_KEY}.${band.epithet}", band.bounty)
            out.add(Topic("road.band.${band.id}.${band.bounty}", Kind.ROAD, if (ask.player != null) 0.55 else 0.4, Utterance.of(pick(ask, listOf("gossip_road_4", "gossip_road_5")), detail), tone = -1))
        }

        // What the waylaid wagons have done to prices: their goods dear, and the bread gone up for the grain and flour
        val entry = town.entry
        if (entry != null && waylaid != null && !bark && !news) {
            val dear = waylaid.goods.filter { it.traded }.map { Trading.quote(entry, it) }.filter { it.event == EventKind.WAYLAID }
                .sortedByDescending { it.price / it.usual }
            if (dear.isNotEmpty()) {
                val names = dear.take(2).map { it.good.key + ".many" }
                val detail = if (names.size >= 2) Speech.of("talk.detail.road.dear.two", names[0], names[1]) else Speech.of("talk.detail.road.dear.one", names[0])
                out.add(Topic("road.dear." + dear.take(2).joinToString("+") { it.good.id }, Kind.ROAD, 0.5, Utterance.of("gossip_road_2", detail), tone = -1))
            }
            if (Good.WHEAT in waylaid.goods || Good.FLOUR in waylaid.goods || Good.BREAD in waylaid.goods) {
                val q = Trading.quote(entry, Good.BREAD)
                if (q.trend > 0 && q.price >= q.usual * BREAD_DEAR) {
                    out.add(Topic("road.bread.${Say.day(level)}", Kind.ROAD, 0.6, Utterance.of("gossip_road_7", price(Good.BREAD, q.buy)), tone = -1))
                }
            }
        }

        // Then the good news: the wagons in again, the band broken, prices easing, the road safe
        val over = m?.news?.lastOrNull { it.key == OVER && hour - it.hour in 0..GLAD }
        if (over != null && !worst.stage.troubled) {
            out.add(Topic("road.in.${over.hour}", Kind.ROAD, 0.5, Utterance.of("gossip_road_clear_1", standing(town) ?: Say.agoDetail(days(over.hour, hour))), tone = 1))
        }
        val relief = town.news.lastOrNull { it.kind == "relief" && hour - it.hour in 0..GLAD }
        if (relief != null) {
            val detail = if (relief.names.size >= 2) Speech.of("talk.detail.road.broken", relief.names[0], relief.names[1]) else "talk.detail.road.broken.band"
            val parts = mutableListOf(Utterance.Part("gossip_road_clear_2", detail))
            if (!bark) parts.add(Utterance.Part("", "talk.detail.road.relief"))
            out.add(Topic("road.broken.${relief.hour}", Kind.ROAD, 0.7, Utterance(parts), tone = 1))
        }
        val eased = m?.news?.lastOrNull { it.key == OVER && hour - it.hour in 0..EASING }
        if (entry != null && eased != null && waylaid == null && !bark && !news) {
            // What comes by wagon, coming down again from dear
            val easing = Good.TRADED.filter { entry.town.shortfall(it) >= 0.5 }.map { Trading.quote(entry, it) }
                .filter { it.trend < 0 && it.price > it.usual }.maxByOrNull { it.price / it.usual }
            if (easing != null) {
                out.add(Topic("road.easing.${easing.good.id}.${Say.day(level)}", Kind.ROAD, 0.4, Utterance.of("gossip_road_clear_3", Speech.of("talk.detail.road.easing", easing.good.key + ".many")), tone = 1))
            }
        }
        val clear = town.news.lastOrNull { it.kind == "clear" && hour - it.hour in 0..GLAD }
        val cleared = clear?.let { c -> town.ways.firstOrNull { it.leg.road == c.road } }
        if (clear != null && (cleared == null || !cleared.stage.troubled) && relief?.hour != clear.hour) {
            out.add(Topic("road.clear.${clear.hour}", Kind.ROAD, 0.5, Utterance.of("gossip_road_clear_4", cleared?.let { Speech.of("talk.detail.road.clear", it.name) }), tone = 1))
        }

        // The keepers and the gate's guards know the wagons' comings and goings: what's just in, and when the next is due
        val role = ask.speaker.role
        if (!bark && !news && (role == TownRole.MERCHANT || role == TownRole.GUARD || role == TownRole.INNKEEPER)) {
            standing(town)?.let { out.add(Topic("road.standing.${town.capital}.${hour}", Kind.ROAD, 0.3, Utterance.of("", it))) }
            due(ask, town)?.let { (way, at) ->
                out.add(Topic("road.due.${way.leg.road}.${Math.floorDiv(at, 1000L)}", Kind.ROAD, 0.2, Utterance.of("", Speech.of("talk.detail.road.due", way.name, hourKey(at)))))
            }
        }
    }

    override fun choice(ask: Ask): Choice? {
        if (Say.child(ask.speaker) || ask.player == null) return null
        town(ask) ?: return null
        return Choice(ASK, Component.translatable("gui.guildmark.talk.road"))
    }

    /** How the roads stand: the worst of them, and then its band and what's on its head, the note on the board, or the next wagon. */
    override fun answer(ask: Ask, choice: String): Utterance? {
        if (choice != ASK) return null
        val town = town(ask) ?: return null
        val worst = town.worst
        val first = if (worst.stage.troubled) {
            Utterance.Part(pick(ask, listOf("gossip_road_1", "gossip_road_3")), Speech.of("talk.detail.road.stage.${worst.stage.id}", worst.name))
        } else {
            val over = town.entry?.market?.news?.lastOrNull { it.key == OVER && Markets.hourOf(ask.level) - it.hour in 0..GLAD }
            val quiet = if (town.ways.size == 1) Speech.of("talk.detail.road.quiet.one", worst.name) else Speech.of("talk.detail.road.quiet", town.ways.size)
            Utterance.Part(if (over != null) "gossip_road_clear_4" else "", quiet)
        }
        val band = worst.band
        val second = when {
            worst.stage.troubled && band != null ->
                Speech.of("talk.detail.road.band", "${Outlaws.NAME_KEY}.${band.name}", "${Outlaws.EPITHET_KEY}.${band.epithet}", band.bounty)
            worst.stage.troubled && Caravans.escorts() -> Speech.of("talk.detail.road.escort", worst.name)
            else -> due(ask, town)?.let { (way, at) -> Speech.of("talk.detail.road.due", way.name, hourKey(at)) }
        }
        return Utterance(listOfNotNull(first, second?.let { Utterance.Part("", it) }))
    }

    /** A take of [lines] that [ask]'s listener hasn't heard lately, if there's one; else any of them. */
    private fun pick(ask: Ask, lines: List<String>): String {
        val avoid = Topics.avoid(ask.speaker, ask.player)
        val fresh = lines.filter { it !in avoid }.ifEmpty { lines }
        return fresh[ask.speaker.random.nextInt(fresh.size)]
    }

    /** A wagon standing in the town now, unloading, and the road it came by: "There's a wagon in from the east road now." */
    private fun standing(town: Town): String? {
        val c = town.data.caravans.firstOrNull { it.capital == town.capital && it.phase == Phase.STANDING } ?: return null
        val way = town.ways.firstOrNull { it.leg.road == c.road } ?: return null
        return Speech.of("talk.detail.road.standing", way.name)
    }

    /** The road whose wagon is due in next, and when (clock ticks), of the roads not closed. */
    private fun due(ask: Ask, town: Town): Pair<Way, Long>? = town.ways.filter { it.stage != RoadPeril.Stage.CLOSED }
        .mapNotNull { way -> Caravans.dueIn(ask.level, town.capital, way.leg.road)?.let { way to it } }
        .minByOrNull { it.second }

    /** The hour [at] (clock ticks) falls in, as a Speech argument ("quest.guildmark.caravan.hour.14": "two in the afternoon"). */
    private fun hourKey(at: Long): String = "quest.guildmark.caravan.hour." + Caravans.clockOf(Math.floorDiv(at, 1000L))

    /** "Bread: 3 for 1 Mark.", as the market sells it now; "Bread: none at the stalls." if it has none. */
    private fun price(good: Good, lot: Lot?): String {
        val name = good.key + ".many"
        if (lot == null) return Speech.of("talk.detail.market.none", name)
        return if (lot.marks == 1) Speech.of("talk.detail.market.price.one", name, lot.count) else Speech.of("talk.detail.market.price", name, lot.count, lot.marks)
    }

    /** Whole days between market hours [from] and [to], as the town counts its days (from six in the morning). */
    private fun days(from: Long, to: Long): Long = Math.floorDiv(to, 24L) - Math.floorDiv(from, 24L)
}
