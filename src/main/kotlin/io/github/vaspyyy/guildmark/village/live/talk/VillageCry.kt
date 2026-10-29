package io.github.vaspyyy.guildmark.village.live.talk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity.Slip
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.MarketBoards
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.economy.fortune.FortuneData
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Reason
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Crier.Cry
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.build.VillageBuilds
import io.github.vaspyyy.guildmark.village.live.days.VillageHands
import io.github.vaspyyy.guildmark.village.live.days.VillageLife
import io.github.vaspyyy.guildmark.village.live.keptList
import io.github.vaspyyy.guildmark.village.live.links.VillageLinks
import io.github.vaspyyy.guildmark.village.live.links.VillageNews
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.ln

/**
 * The word of the day in a village (living-world/villages/design.md 8.2): what its bellman cries at noon ([Bellman]), built from what
 * really happened as a capital's crier's is ([Crier.news]), but in the village's own words and with two groups a capital has not. In
 * order, at most [MOST] things between the opening and the close, with room kept for the times:
 *
 * - **its own news**: a siege held or lost (the wall's lines only where it has a wall standing), the dead (the candle only where one
 *   was laid for them), a raid beaten off, one of its folk brought home, a project finished or a new road open, the builders coming
 *   in the morning, a birth or newcomers (from its chronicle);
 * - **word from outside** ([VillageNews]): from the city by the wagon where a road links the village to a capital ([VillageLinks]),
 *   else from the road by the pedlar;
 * - the news of the linked roads, the market's own news (trouble or luck begun or over), a hungry day;
 * - **the cart**: the pedlar on the green today, a visit lost (and why, only as the cart's record has it), the wagon in or long awaited;
 * - the calendar: a new season on its first day, a festival at hand or today;
 * - the one price furthest off its usual, and why;
 * - last, the village's times, moved since the noon before or still hard, lean, good or thriving, with the reasons that dress the
 *   green (or leave it bare) in grey: "Good times in Larkford: ...".
 *
 * Its own news and the word from outside are cried once each: once the bellman has cried it to the end it is told ([VillageCryData]),
 * though it stays in the day's word for the rest of that day, so what `village news` shows after noon is what was cried. The rest is the
 * market's and the calendar's, cried when it falls, as a capital's is. A day is worth a cry of its own ([Word.news]) when anything is
 * in it but a festival still to come, the price and the times standing still; a market day is always cried.
 *
 * Off with Village Bellman ([enabled]): no word, nothing kept.
 */
object VillageCry {
    /** The most things cried between the opening and the close, as in a capital (Crier.MOST). */
    const val MOST = 7

    /** How many days old a village's own news may be and still be cried, if it has not been. */
    const val FRESH = 3

    /** The most pieces of word from outside in one cry. */
    private const val WORD = 2

    /** How long what was told is kept (days): word from outside is held by [VillageNews] as long as it likes. */
    private const val KEPT_DAYS = 14L

    /** How far from the bell a new road's end may be and be the village's road. */
    private const val ROAD_END = 64

    /** The village went hungry when this share of its meals went uneaten for want of food yesterday, and at least so many (as Crier's). */
    private const val HUNGRY = 0.05
    private const val HUNGRY_MEALS = 3.0

    /** The prices' whys a village does not cry: they speak of a town's tools, a town's feast, a capital's caravans. */
    private val UNSAID = setOf("worn_tools", "feast", "from_afar")

    /** The capital's lines that name the town or its quay, and the village's own in their place. */
    private val OWN_WORDS = mapOf("crier_fever" to "bellman_fever", "crier_good_catch" to "bellman_good_catch")

    /** The project each finished building is cried with. */
    private val BUILT = mapOf(
        Advance.LAMP_POSTS to "bellman_built_lamps", Advance.GUILD_HALL to "bellman_built_hall", Advance.TRADE_ROAD to "bellman_built_road",
        Advance.PALISADE to "bellman_built_palisade", Advance.ARCHER_TOWER to "bellman_built_tower", Advance.MARKET to "bellman_built_market",
        Advance.STONE_WALLS to "bellman_built_walls", Advance.KEEP to "bellman_built_keep",
    )

    fun enabled(): Boolean = VillageLife.enabled() && try {
        GuildmarkConfig.VILLAGE_BELLMAN.get()
    } catch (e: IllegalStateException) {
        true
    }

    /**
     * The day's word: [cries], the opening and the close among them; the ids of what is told once they are cried ([told]); whether any
     * of it is news worth a cry of its own ([news]); and whether it is the village's market day ([market]).
     */
    class Word(val cries: List<Cry>, val told: List<String>, val news: Boolean, val market: Boolean) {
        /** Whether the bellman cries it today: on market day, or with news. */
        val due: Boolean get() = market || news
    }

    /** One thing to cry: its [cries], said together; the [ids] it is told by (none for the market's, the calendar's); whether it is [news]. */
    private class Item(val cries: List<Cry>, val ids: List<String> = listOf(), val news: Boolean = true)

    /**
     * The word of the village at [places] at [c]'s noon, its market [entry] (if it is open): the same each time it's asked that day,
     * but for what happened since.
     */
    fun word(level: ServerLevel, places: TownPlaces, entry: Markets.Entry?, c: Conditions): Word {
        val key = places.centre.asLong()
        val rec = Villages.byKey(level, key)
        val at = level.gameTime
        val day = c.day
        val kept = VillageCryData.get(level).peek(key)
        // Its own news and the word from outside: what has not been cried, or was cried today
        val untold = { id: String -> kept?.told?.get(id).let { it == null || it == day } }
        val noon = day * 24 + 6
        val items = ArrayList<Item>()
        val cried = HashSet<EventKind>()
        if (rec != null) {
            own(level, rec, kept, at, untold, items)
            outside(level, rec, at, untold, items)
            roads(level, rec, noon, at, items)
        }
        if (entry != null) market(entry, noon, at, cried, items)
        if (rec != null) cart(level, rec, day, noon, at, untold, items)
        calendar(level, c, at, items)
        if (entry != null) price(entry, at, cried, items)
        val last = times(level, key, places.name, noon, at)
        val chosen = ArrayList<Cry>()
        val told = ArrayList<String>()
        var news = last?.second == true
        for (item in items) {
            // The same thing twice in the records (one of its folk brought home twice in a day, say) is cried once
            if (item.ids.isNotEmpty() && item.ids.all { it in told }) continue
            if (chosen.size + item.cries.size > MOST - (if (last != null) 1 else 0)) continue
            chosen.addAll(item.cries)
            told.addAll(item.ids)
            if (item.news) news = true
        }
        last?.let { chosen.add(it.first) }
        val market = VillageHands.market(level, places.centre, day)
        // Market day with nothing else to tell: the stalls are out (with keepers for them), or the green is the market's
        if (chosen.isEmpty() && market) chosen.add(Cry("bellman_market_day", said("bellman_market_day"), null, 1))
        val open = Cry("bellman_open", said("bellman_open"), null, 0)
        val close = Cry("bellman_close", said("bellman_close"), null, 0)
        return Word(listOf(open) + chosen + close, told, news, market)
    }

    // ---- Its own news ----

    private fun own(level: ServerLevel, rec: VillageRec, kept: VillageCryData.Kept?, at: Long, untold: (String) -> Boolean, out: MutableList<Item>) {
        val today = VillageBoards.day(level)
        val bell = BlockPos.of(rec.key)
        // A siege held or lost, as its board keeps it: the newer first, the wall's own words only where a wall stands
        board(level, rec)?.let { board ->
            val walled = walled(level, rec, board)
            val sieges = listOf(true to board.lastSiegeWon, false to board.lastSiegeLost).filter { fresh(today, it.second) }.sortedByDescending { it.second }
            for ((won, d) in sieges) {
                val id = "siege.${if (won) "held" else "lost"}.$d"
                if (!untold(id)) continue
                val line = "bellman_siege_${if (won) "held" else "lost"}" + if (walled) "" else "_nowall"
                val headline = Component.translatable("board.guildmark.slip.word.siege_${if (won) "won" else "lost"}")
                out.add(Item(listOf(Cry(line, said(line), Crier.slip(if (won) Slip.Kind.GOOD else Slip.Kind.TROUBLE, headline, said(line), at), if (won) 1 else -1)), listOf(id)))
            }
        }
        // The dead of the last days, named: the candle's words only for one a candle was laid for
        val dead = Residents.get(level).byCentre(bell).filter { !it.alive && it.died >= 0 && fresh(today, it.died) && untold("dead.${it.id}") }.sortedBy { it.died }
        if (dead.isNotEmpty()) {
            val candle = dead.any { kept?.candles?.containsKey(it.name) == true }
            val line = if (candle) "bellman_buried" else "bellman_died"
            val names = Component.literal(dead.joinToString(", ") { it.name })
            val slip = Crier.slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.village.dead"), names, at)
            out.add(Item(listOf(Cry(line, detailed(line, names), slip, -1)), dead.map { "dead.${it.id}" }))
        }
        // A raid beaten off at it, one of its folk brought home, a road laid to it: the guild's news of it, while the guild keeps it
        val linked = VillageLinks.linked(level, rec.key).isNotEmpty()
        for (n in GuildNews.get(level).items) {
            if (!fresh(today, n.day)) continue
            when (n.key) {
                "news.guildmark.outlaw.raid_beaten" -> if (n.args.lastOrNull() == rec.name && untold("raid.${n.day}")) {
                    out.add(Item(listOf(Cry("bellman_raid_beaten", detailed("bellman_raid_beaten", n.text()), Crier.slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.word.bandits"), n.text(), at), 1)), listOf("raid.${n.day}")))
                }
                "news.guildmark.outlaw.rescued" -> {
                    val id = "rescued.${n.day}.${n.args.firstOrNull().orEmpty()}"
                    if (n.args.lastOrNull() == rec.name && untold(id)) {
                        out.add(Item(listOf(Cry("bellman_rescued", detailed("bellman_rescued", n.text()), Crier.slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.word.rescued"), n.text(), at), 1)), listOf(id)))
                    }
                }
                "news.guildmark.road" -> {
                    val end = n.args.mapNotNull { it.toIntOrNull() }
                    val near = end.size == 4 && (close(bell, end[0], end[1]) || close(bell, end[2], end[3]))
                    if (near && untold("road.${n.day}")) {
                        // A wagon comes only where the road reaches a capital (the links)
                        val line = if (linked) "bellman_built_road" else "bellman_built_road_open"
                        out.add(Item(listOf(Cry(line, said(line), Crier.slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.word.road"), said(line), at), 1)), listOf("road.${n.day}")))
                    }
                }
            }
        }
        // A project finished (as the building crew finished it), and the builders coming in the morning
        for (b in kept?.built.orEmpty()) {
            val project = Advance.entries.getOrNull(b.project) ?: continue
            val id = "built.${b.day}.${b.project}"
            if (!fresh(today, b.day) || !untold(id)) continue
            val line = if (project == Advance.TRADE_ROAD && !linked) "bellman_built_road_open" else BUILT.getValue(project)
            out.add(Item(listOf(Cry(line, said(line), Crier.slip(Slip.Kind.GOOD, project.title, said(line), at), 1)), listOf(id)))
        }
        val clock = level.overworldClockTime
        VillageBuilds.all(level).filter { it.village == rec.key && !it.rushing && it.opens > clock && it.opens - clock <= DAY }.forEach { b ->
            val project = Advance.entries.getOrNull(b.project) ?: return@forEach
            val id = "crew.${b.id}"
            if (!untold(id)) return@forEach
            val what = Component.translatable("chat.guildmark.bellman.crew", project.title)
            out.add(Item(listOf(Cry("bellman_crew_start", detailed("bellman_crew_start", what), null, 1)), listOf(id)))
        }
        // A birth, newcomers: as the village's chronicle has them
        for (ch in rec.chronicle) {
            val line = when (ch.kind) {
                "born" -> "bellman_born"
                "arrived" -> "bellman_arrived"
                else -> continue
            }
            val id = "${ch.kind}.${ch.day}.${ch.arg}"
            if (fresh(today, ch.day) && untold(id)) out.add(Item(listOf(Cry(line, said(line), null, 1)), listOf(id)))
        }
    }

    /** The quest board of [rec], if it stands loaded: where its sieges and what has been built are kept. */
    private fun board(level: ServerLevel, rec: VillageRec): QuestBoardBlockEntity? {
        if (rec.board == -1L) return null
        val pos = BlockPos.of(rec.board)
        if (!level.isLoaded(pos)) return null
        return level.getBlockEntity(pos) as? QuestBoardBlockEntity
    }

    /** Whether [rec] has a wall standing: its palisade funded (and not still going up), or more. */
    fun walled(level: ServerLevel, rec: VillageRec, board: QuestBoardBlockEntity): Boolean {
        if (board.advanceIndex <= Advance.PALISADE.ordinal) return false
        return VillageBuilds.recFor(level, rec.key)?.project != Advance.PALISADE.ordinal
    }

    /** Whether town day [d] is within [FRESH] days of [today]. */
    private fun fresh(today: Long, d: Long): Boolean = d >= 0 && today - d in 0..FRESH.toLong()

    private fun close(bell: BlockPos, x: Int, z: Int): Boolean {
        val dx = (x - bell.x).toLong()
        val dz = (z - bell.z).toLong()
        return dx * dx + dz * dz <= ROAD_END.toLong() * ROAD_END
    }

    private const val DAY = 24000L

    // ---- Word from outside, and the roads ----

    /** What the village has heard from outside, by the wagon where a road links it to a capital, else by the pedlar; [WORD] at most. */
    private fun outside(level: ServerLevel, rec: VillageRec, at: Long, untold: (String) -> Boolean, out: MutableList<Item>) {
        val linked = VillageLinks.linked(level, rec.key).isNotEmpty()
        val line = if (linked) "bellman_from_capital" else "bellman_from_road"
        VillageNews.items(level, rec.key).filter { untold(newsId(it.day, it.key, it.args)) }.take(WORD).forEach { n ->
            // How the guild's crier would take it, and its slip
            val guild = Crier.word(n, at)
            out.add(Item(listOf(Cry(line, detailed(line, n.text()), guild.slip, guild.tone)), listOf(newsId(n.day, n.key, n.args))))
        }
    }

    private fun newsId(day: Long, key: String, args: List<String>): String = "news.$day.${key.removePrefix("news.guildmark.")}.${args.joinToString(",")}"

    /**
     * The roads that link the village to a capital, since the noon before market hour [noon]: each one's latest stage if it went worse,
     * or the wagons getting through again, in the capital's own words, with which road it is in grey.
     */
    private fun roads(level: ServerLevel, rec: VillageRec, noon: Long, at: Long, out: MutableList<Item>) {
        val data = CaravanData.get(level)
        for (link in VillageLinks.linked(level, rec.key)) {
            val cap = data.capitalOrNull(link.capital) ?: continue
            val last = cap.news.lastOrNull { n ->
                n.road == link.road && n.hour > noon - 24 && n.hour <= noon && (n.kind == "clear" || RoadPeril.Stage.entries.any { it.id == n.kind })
            } ?: continue
            val road = Component.translatable("chat.guildmark.bellman.road", cap.name)
            when (last.kind) {
                RoadPeril.Stage.OPEN.id -> {}
                "clear" -> out.add(Item(listOf(Cry("crier_over_waylaid", detailed("crier_over_waylaid", road), Crier.slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.road.clear"), road, at), 1))))
                else -> {
                    val line = "crier_road_${last.kind}"
                    out.add(Item(listOf(Cry(line, detailed(line, road), Crier.slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.road.${last.kind}"), road, at), -1))))
                }
            }
        }
    }

    // ---- The market ----

    /** The market's news since the noon before market hour [noon] (each kind once, as a capital's), and a hungry day. */
    private fun market(entry: Markets.Entry, noon: Long, at: Long, cried: MutableSet<EventKind>, out: MutableList<Item>) {
        val m = entry.market
        for (n in m.news) {
            if (n.hour <= noon - 24 || n.hour > noon) continue
            val (cry, kind) = Crier.market(n, at, m) ?: continue
            if (cried.add(kind) || kind == EventKind.BLIGHT || kind == EventKind.BUMPER) out.add(Item(listOf(own(cry))))
        }
        if ((m.hungry.lastOrNull() ?: 0.0) >= maxOf(HUNGRY_MEALS, entry.town.people * Economy.MEALS * HUNGRY)) {
            val slip = Crier.slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.hungry"), said("bellman_hungry"), at)
            out.add(Item(listOf(Cry("bellman_hungry", said("bellman_hungry"), slip, -1))))
        }
    }

    /** [cry] in the village's words where the capital's name its town or quay. */
    private fun own(cry: Cry): Cry {
        val line = OWN_WORDS[cry.line] ?: return cry
        return Cry(line, said(line), cry.slip, cry.tone)
    }

    /** The one price furthest off its usual, with why (not a why that speaks of a town), unless its trouble is cried already. */
    private fun price(entry: Markets.Entry, at: Long, cried: Set<EventKind>, out: MutableList<Item>) {
        val q = MarketBoards.ORDER.map { Trading.quote(entry, it) }
            .filter { q -> q.why != null && q.why.id !in UNSAID && (q.event == null || q.event !in cried) }
            .maxByOrNull { abs(ln(it.price / it.usual)) } ?: return
        val cries = Crier.price(q, at).map(::own)
        if (cries.isNotEmpty()) out.add(Item(cries, news = false))
    }

    // ---- The cart ----

    /**
     * The cart, as its record has it: the pedlar on the green today (what they brought in grey), a visit lost in the last [FRESH] days
     * (why only if the record says, for that day), the wagon in since the noon before, or no wagon for more than
     * [PedlarRoster.WAGON_DAYS] days to a village a road links.
     */
    private fun cart(level: ServerLevel, rec: VillageRec, day: Long, noon: Long, at: Long, untold: (String) -> Boolean, out: MutableList<Item>) {
        val cart = VillageMarkets.rec(level, rec.key)?.cart ?: return
        if (cart.called == day && cart.by == "pedlar" && cart.missed != day) {
            val goods = cart.brought.entries.filter { it.value >= 1.0 }.sortedByDescending { it.value }.take(3).mapNotNull { e -> Good.entries.firstOrNull { it.id == e.key } }
            val text = if (goods.isEmpty()) said("bellman_pedlar") else detailed("bellman_pedlar", Component.translatable("chat.guildmark.bellman.brought", names(goods)))
            out.add(Item(listOf(Cry("bellman_pedlar", text, null, 1))))
        }
        if (cart.missed != PedlarRoster.NONE && day - cart.missed in 0..FRESH.toLong() && untold("missed.${cart.missed}")) {
            // Why only as the record has it: the verdict kept is the day's it was lost on
            val why = PedlarRoster.Why.of(cart.why)?.takeIf { cart.decided == cart.missed }
            val because = when (why) {
                PedlarRoster.Why.BAND -> "band"
                PedlarRoster.Why.SLAIN -> "slain"
                PedlarRoster.Why.HELD -> "held"
                else -> null
            }
            val text = if (because == null) said("bellman_no_pedlar") else detailed("bellman_no_pedlar", Component.translatable("chat.guildmark.bellman.why.$because"))
            val slip = Crier.slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.village.no_pedlar"), text, at)
            out.add(Item(listOf(Cry("bellman_no_pedlar", text, slip, -1)), listOf("missed.${cart.missed}")))
        }
        val links = VillageLinks.linked(level, rec.key)
        val from = links.singleOrNull()?.let { CaravanData.get(level).capitalOrNull(it.capital)?.name }?.takeIf { it.isNotEmpty() }
        if (cart.wagon != PedlarRoster.NONE && cart.wagon > noon - 24 && cart.wagon <= noon) {
            val text = if (from == null) said("bellman_cart_in") else detailed("bellman_cart_in", Component.translatable("chat.guildmark.bellman.from", from))
            out.add(Item(listOf(Cry("bellman_cart_in", text, Crier.slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.village.wagon"), text, at), 1))))
        } else if (links.isNotEmpty() && cart.wagon != PedlarRoster.NONE && day - Conditions(cart.wagon).day > PedlarRoster.WAGON_DAYS && untold("late.${cart.wagon}")) {
            val text = said("bellman_cart_late")
            out.add(Item(listOf(Cry("bellman_cart_late", text, Crier.slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.village.late"), text, at), -1)), listOf("late.${cart.wagon}")))
        }
    }

    /** [goods] by name, one after another. */
    private fun names(goods: List<Good>): MutableComponent {
        val out = Component.empty()
        goods.forEachIndexed { k, g ->
            if (k > 0) out.append(", ")
            out.append(Component.translatable(g.key + ".many"))
        }
        return out
    }

    // ---- The calendar and the times ----

    /** The calendar's news (Crier.calendar's): a new season on its first day and a festival today are news; one still to come is not. */
    private fun calendar(level: ServerLevel, c: Conditions, at: Long, out: MutableList<Item>) {
        for (cry in Crier.calendar(level, c, at).orEmpty()) {
            when (cry.line) {
                // The feast's own line for a village, the festival's name after it as the capital's has it
                "crier_festival_today" -> {
                    val text = said("bellman_festival_today").also { t -> cry.text.siblings.forEach { t.append(it) } }
                    out.add(Item(listOf(Cry("bellman_festival_today", text, cry.slip, 1))))
                }
                "crier_festival_soon" -> out.add(Item(listOf(cry), news = false))
                else -> out.add(Item(listOf(cry)))
            }
        }
    }

    /**
     * The village's times at the noon of market hour [noon] ([Fortunes.cries]'s rule): moved since the noon before (news), or still not
     * steady; with the biggest reasons pulling that way in the village's words after the village's [name] in grey. Null if steady all
     * along, or while the village's fortunes are off.
     */
    private fun times(level: ServerLevel, key: Long, name: String, noon: Long, at: Long): Pair<Cry, Boolean>? {
        if (!Fortunes.enabled() || !VillageMarkets.fortunes()) return null
        val town = FortuneData.get(level)[key] ?: return null
        val times = if (town.moved > noon) town.from else town.times
        if (town.moved > noon - 24 && town.moved <= noon) {
            val sign = if (times.ordinal > town.from.ordinal) 1 else -1
            val line = "bellman_times_${times.id}"
            val why = because(town.reasons, sign)
            val detail = if (why != null) Component.translatable("chat.guildmark.bellman.times.${times.id}", name, why)
            else Component.translatable("chat.guildmark.bellman.times.${times.id}.plain", name)
            val slip = Crier.slip(Slip.Kind.FORTUNE, Component.translatable("board.guildmark.slip.fortune.${times.id}"), detail, at)
            return Cry(line, detailed(line, detail), slip, sign) to true
        }
        if (times == Times.STEADY) return null
        val sign = if (times < Times.STEADY) -1 else 1
        val line = "bellman_times_still_${times.id}"
        val why = because(town.reasons, sign)
        return Cry(line, if (why != null) detailed(line, Component.translatable("chat.guildmark.bellman.times.${times.id}", name, why)) else said(line), null, sign) to false
    }

    /** The two biggest of [reasons] (biggest first already) pulling the way [sign] says, each in the village's words; null if none does. */
    private fun because(reasons: List<Reason>, sign: Int): MutableComponent? {
        val pulling = reasons.filter { Integer.signum(it.points) == sign }.take(2)
        if (pulling.isEmpty()) return null
        val out = Component.empty()
        pulling.forEachIndexed { k, r ->
            if (k > 0) out.append(" ")
            out.append(FortuneReasons.villageText(r))
        }
        return out
    }

    // ---- Words ----

    private fun said(line: String): MutableComponent = Crier.said(line)

    private fun detailed(line: String, detail: Component): MutableComponent = said(line).append(" ").append(detail.copy().withStyle(ChatFormatting.GRAY))

    // ---- What is kept ----

    /** [word], cried to the end at the village [key] on [day]: what is told in it is kept, so it is not cried again. */
    fun told(level: ServerLevel, key: Long, word: Word, day: Long) {
        if (!enabled() || word.told.isEmpty()) return
        val data = VillageCryData.get(level)
        val kept = data.of(key)
        for (id in word.told) kept.told.putIfAbsent(id, day)
        kept.told.values.removeIf { day - it > KEPT_DAYS }
        data.setDirty()
    }

    /** The town day the village [key]'s bellman last cried the word, or [PedlarRoster.NONE]. */
    fun criedOn(level: ServerLevel, key: Long): Long = VillageCryData.get(level).peek(key)?.cried ?: PedlarRoster.NONE

    /** The village [key]'s bellman has set out to cry the word on [day] (once a day, whoever holds the bell). */
    fun crying(level: ServerLevel, key: Long, day: Long) {
        if (!enabled()) return
        val data = VillageCryData.get(level)
        data.of(key).cried = day
        data.setDirty()
    }

    /** From VillageBuilds, where a project is finished: the bellman cries it at the next noon. */
    fun built(level: ServerLevel, key: Long, project: Advance) {
        if (!enabled()) return
        val data = VillageCryData.get(level)
        val kept = data.of(key)
        kept.built.add(VillageCryData.Built(VillageBoards.day(level), project.ordinal))
        while (kept.built.size > VillageCryData.MAX_BUILT) kept.built.removeAt(0)
        data.setDirty()
    }

    /**
     * A candle was laid by the bell for [name], of the village [key] (the memorial stone's part calls it as one is laid): the bellman's
     * "A candle by the bell tonight." is said of them, and the talk's.
     */
    fun candle(level: ServerLevel, key: Long, name: String) {
        if (!enabled()) return
        val data = VillageCryData.get(level)
        val kept = data.of(key)
        kept.candles[name] = VillageBoards.day(level)
        val today = VillageBoards.day(level)
        kept.candles.values.removeIf { today - it > KEPT_DAYS }
        data.setDirty()
    }

    /** Whether a candle was laid for [name] of the village [key] in the last [days] days. */
    fun candleFor(level: ServerLevel, key: Long, name: String, days: Long): Boolean {
        val laid = VillageCryData.get(level).peek(key)?.candles?.get(name) ?: return false
        return VillageBoards.day(level) - laid in 0..days
    }

    /** The projects finished at the village [key] (town day and project), the oldest first. */
    fun builtAt(level: ServerLevel, key: Long): List<VillageCryData.Built> = VillageCryData.get(level).peek(key)?.built.orEmpty()

    /** The ids of what the village [key]'s bellman has told, and the day each was. */
    fun toldAt(level: ServerLevel, key: Long): Map<String, Long> = VillageCryData.get(level).peek(key)?.told.orEmpty()

    /** `village cry clear`: what the village [key] has told and cried is forgotten. */
    fun forget(level: ServerLevel, key: Long) {
        val data = VillageCryData.get(level)
        val kept = data.peek(key) ?: return
        kept.told.clear()
        kept.cried = PedlarRoster.NONE
        data.setDirty()
    }
}

/**
 * What the villages' bellmen keep (in the overworld's data, `guildmark_village_cry`): for each village, what has been told and the day
 * it was ([Kept.told]), the day the word was last cried, the projects finished lately ([Built]) and the candles laid by the bell, by
 * name and day. Every list is bounded; every field is optional, and a village with none is not written.
 */
class VillageCryData(private val villages: MutableMap<Long, Kept> = HashMap()) : SavedData() {
    class Kept(
        val told: MutableMap<String, Long> = HashMap(),
        var cried: Long = PedlarRoster.NONE,
        val built: MutableList<Built> = ArrayList(),
        val candles: MutableMap<String, Long> = HashMap(),
    )

    /** A project ([project], an [Advance] ordinal) finished on town [day]. */
    data class Built(val day: Long, val project: Int)

    fun peek(key: Long): Kept? = villages[key]

    fun of(key: Long): Kept = villages.getOrPut(key) { Kept() }

    private class Row(val key: Long, val kept: Kept)

    companion object {
        const val MAX_BUILT = 8

        private val DAYS: Codec<Map<String, Long>> = Codec.unboundedMap(Codec.STRING, Codec.LONG)

        private val BUILT: Codec<Built> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Built::day),
                Codec.INT.optionalFieldOf("project", 0).forGetter(Built::project),
            ).apply(i, ::Built)
        }

        private val ROW: Codec<Row> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("key").forGetter(Row::key),
                DAYS.optionalFieldOf("told", mapOf()).forGetter { it.kept.told },
                Codec.LONG.optionalFieldOf("cried", PedlarRoster.NONE).forGetter { it.kept.cried },
                keptList(BUILT).lenientOptionalFieldOf("built", listOf()).forGetter { it.kept.built },
                DAYS.optionalFieldOf("candles", mapOf()).forGetter { it.kept.candles },
            ).apply(i) { key, told, cried, built, candles -> Row(key, Kept(HashMap(told), cried, ArrayList(built.takeLast(MAX_BUILT)), HashMap(candles))) }
        }

        private val CODEC: Codec<VillageCryData> = RecordCodecBuilder.create { i ->
            i.group(
                keptList(ROW).lenientOptionalFieldOf("villages", listOf()).forGetter { d -> d.villages.entries.sortedBy { it.key }.map { Row(it.key, it.value) } },
            ).apply(i) { rows -> VillageCryData(rows.associateTo(HashMap()) { it.key to it.kept }) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_cry"), Supplier { VillageCryData() }, CODEC)

        /** The overworld's, whichever level asks. */
        fun get(level: ServerLevel): VillageCryData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
