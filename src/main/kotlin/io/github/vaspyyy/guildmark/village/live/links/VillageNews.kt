package io.github.vaspyyy.guildmark.village.live.links

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.guild.NewsItem
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The word a village has from outside, by the wagon or the pedlar (living-world/villages/design.md section 9.5), written here and
 * read by the bellman and the talk (part V6).
 *
 * When a wagon comes back to its village ([carried], from [VillageWagons.home]), it brings the capital's headlines: the last
 * [MARKET] pieces of the capital's market news (trouble or luck begun or over) within [WITHIN] hours, its roads' news of the same
 * hours (each road's latest stage, a band broken and the relief on its way), and the guild's news of that day and the day before.
 * A village with no capital hears the guild's news alone, by the pedlar, and slower: what is two to [DAYS] days old, kept the day
 * the pedlar makes their call ([look]). So what a village hears is what really happened, and when and how it really reached it.
 *
 * Each piece is kept once (the same words are not heard twice in [DAYS] days), [KEEP] at most, the newest first, until it is cried
 * ([cried]) or [DAYS] days old. Kept in the wagons' store, on the village's [VillageWagons.Book]. With the switch off
 * ([VillageLinks.enabled]) nothing is heard or written.
 */
object VillageNews {
    /** How old the capital's news may be (market hours) for the wagon to carry it, and how many of its market's pieces. */
    const val WITHIN = 36L
    const val MARKET = 3

    /** Pieces kept for a village at most, and the days one is kept. */
    const val KEEP = 8
    const val DAYS = 3L

    /** The least the pedlar's news is old (days): it comes on foot. */
    const val SLOWER = 2L

    /** How a piece of news came ([Heard.by]). */
    const val WAGON = "wagon"
    const val PEDLAR = "pedlar"

    /** The capitals' market news that is cried (trouble or luck begun, or over). */
    private const val EVENT_NEWS = "news.guildmark.market.event."
    private const val OVER_NEWS = "news.guildmark.market.over."

    /**
     * A piece of news the village heard on [day] (the guild's day, as its news is dated) at market [hour]: its words ([key] and [args],
     * as a [NewsItem]'s), how it came ([by]: [WAGON] or [PEDLAR]) and from which capital ([from], -1 for the pedlar's), and whether the
     * bellman has cried it.
     */
    class Heard(val day: Long, val hour: Long, val key: String, val args: List<String>, val by: String, val from: Long, var cried: Boolean = false) {
        val item: NewsItem get() = NewsItem(day, key, args)

        companion object {
            val CODEC: Codec<Heard> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Heard::day),
                    Codec.LONG.optionalFieldOf("hour", 0L).forGetter(Heard::hour),
                    Codec.STRING.fieldOf("key").forGetter(Heard::key),
                    Codec.STRING.listOf().optionalFieldOf("args", listOf()).forGetter(Heard::args),
                    Codec.STRING.optionalFieldOf("by", WAGON).forGetter(Heard::by),
                    Codec.LONG.optionalFieldOf("from", -1L).forGetter(Heard::from),
                    Codec.BOOL.optionalFieldOf("cried", false).forGetter(Heard::cried),
                ).apply(i, ::Heard)
            }
        }
    }

    /** The wagon from [capital] came in at [village] (the keys of their bells and centres): the capital's headlines are kept for the village's next cry. */
    fun carried(level: ServerLevel, village: Long, capital: Long) {
        if (!VillageLinks.enabled()) return
        val now = Markets.hourOf(level)
        val day = VillageBoards.day(level)
        val heard = ArrayList<Heard>()
        Markets.get(level)[BlockPos.of(capital)]?.market?.news
            ?.filter { it.hour in now - WITHIN..now && (it.key.startsWith(EVENT_NEWS) || it.key.startsWith(OVER_NEWS)) }
            ?.takeLast(MARKET)
            ?.forEach { n -> heard += Heard(day, n.hour, n.key, listOfNotNull(n.good?.let { "#" + it.key }), WAGON, capital) }
        CaravanData.get(level).capitalOrNull(capital)?.news?.filter { it.hour in now - WITHIN..now }?.let { news ->
            // Each road's latest stage, as the capital's crier has it; a band broken, with its relief
            val stages = news.filter { n -> RoadPeril.Stage.entries.any { it.id == n.kind && it != RoadPeril.Stage.OPEN } || n.kind == "clear" }
                .groupBy { it.road }.values.map { it.last() }
            for (n in stages.sortedBy { it.hour }) heard += Heard(day, n.hour, "chat.guildmark.crier.road.${n.kind}", listOf("#" + way(capital, n.road)), WAGON, capital)
            news.lastOrNull { it.kind == "relief" }?.let { n -> heard += Heard(day, n.hour, "chat.guildmark.crier.road.relief", listOf("#" + way(capital, n.road)), WAGON, capital) }
        }
        GuildNews.get(level).items.filter { it.day >= day - 1 }.take(2).reversed()
            .forEach { heard += Heard(day, now, it.key, it.args, WAGON, capital) }
        keep(level, village, heard)
    }

    /** What the village [key] has heard and not yet cried, the newest first: none while there is nothing to say. */
    fun items(level: ServerLevel, key: Long): List<NewsItem> = heard(level, key).map { it.item }

    /** As [items], with how each piece came and from where (for "From [the capital], by the wagon:" and "From the road:"). */
    fun heard(level: ServerLevel, key: Long): List<Heard> {
        if (!VillageLinks.enabled()) return emptyList()
        val book = VillageWagons.book(level, key) ?: return emptyList()
        val day = VillageBoards.day(level)
        return book.news.filter { !it.cried && day - it.day <= DAYS }.reversed()
    }

    /** The bellman (V6) has cried what the village [key] had heard: none of it is said again. */
    fun cried(level: ServerLevel, key: Long) {
        val book = VillageWagons.book(level, key) ?: return
        if (book.news.none { !it.cried }) return
        for (h in book.news) h.cried = true
        VillageWagons.store(level).changed()
    }

    /**
     * Once in a while (each 200 ticks, from [VillageLinks]): a village with no capital whose pedlar made their call today hears the
     * guild's news they bring, two to [DAYS] days old, once that day.
     */
    fun look(level: ServerLevel) {
        if (!VillageLinks.enabled()) return
        val day = VillageBoards.day(level)
        val guild = GuildNews.get(level).items.filter { day - it.day in SLOWER..DAYS }
        if (guild.isEmpty()) return
        val now = Markets.hourOf(level)
        val today = Conditions(now).day
        for (rec in Villages.awake(level)) {
            val cart = VillageMarkets.rec(level, rec.key)?.cart ?: continue
            if (cart.called != today || cart.by != "pedlar") continue
            if (VillageWagons.book(level, rec.key)?.pedlarDay == today) continue
            if (VillageLinks.linked(level, rec.key).isNotEmpty()) continue
            keep(level, rec.key, guild.take(2).reversed().map { Heard(day, now, it.key, it.args, PEDLAR, -1L) })
            VillageWagons.book(level, rec.key, make = true)!!.pedlarDay = today
            VillageWagons.store(level).changed()
        }
    }

    /** [heard] kept for [village], each piece once in [DAYS] days, the oldest going past [KEEP]. */
    private fun keep(level: ServerLevel, village: Long, heard: List<Heard>) {
        if (heard.isEmpty()) return
        val book = VillageWagons.book(level, village, make = true)!!
        val day = VillageBoards.day(level)
        book.news.removeIf { day - it.day > DAYS }
        for (h in heard) {
            if (book.news.any { it.key == h.key && it.args == h.args }) continue
            book.news.add(h)
        }
        while (book.news.size > KEEP) book.news.removeAt(0)
        VillageWagons.store(level).changed()
    }

    /** The road [road] into [capital], by the gate it comes in at, as road news names it ("road.guildmark.way.n"). */
    private fun way(capital: Long, road: Int): String =
        "road.guildmark.way." + (CaravanRoutes.cached(capital, road)?.side?.name?.lowercase() ?: "any")
}
