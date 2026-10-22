package io.github.vaspyyy.guildmark.economy.fortune

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Event
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What weighs on a capital's fortunes (living-world/design-influence.md section 4's table): each reason's kind, its
 * points and how long it lasts. Most are read at the close from what the market, the road, the calendar and the works
 * already keep ([read]); the rest are noted as they happen (see [Noted] and [Fortunes.note]) and added up at the
 * close ([noted]). Every reason has an end: "the day" ones count at the one close, "while so" ones while the record
 * still shows it, and the noted ones for their days. [text] says each in a sentence, for the board, the crier and the
 * commands.
 */
object FortuneReasons {
    // ---- The kinds, in the table's order ----

    const val HUNGRY = "hungry"
    const val FED = "fed"
    const val DEAR = "dear"
    const val CHEAP = "cheap"
    const val WANTING = "wanting"
    const val IDLE = "idle"
    const val ROAD = "road"
    const val WAYLAID = "waylaid"
    const val RELIEF = "relief"
    const val ESCORTED = "escorted"
    const val SUPPLIED = "supplied"
    const val PLENTY = "plenty"
    const val TROUBLE = "trouble"
    const val FEVER = "fever"
    const val MOURNING = "mourning"
    const val CRIME = "crime"
    const val FESTIVAL = "festival"
    const val BUILDING = "building"
    const val RAISED = "raised"
    const val EASED = "eased"
    const val NEWCOMERS = "newcomers"
    const val LEAVING = "leaving"

    /**
     * Added by the designs after the table, in the order they were accepted (living-world/build-plan.md section 2): a
     * big delivery from afar (Trade), a tale's ending (Tales), a village's siege held or lost (Villages).
     */
    const val HAULED = "hauled"
    const val TALE = "tale"
    const val HELD = "held"
    const val LOST = "lost"

    /** Every kind of reason, in the table's order. */
    val KINDS: List<String> = listOf(
        HUNGRY, FED, DEAR, CHEAP, WANTING, IDLE, ROAD, WAYLAID, RELIEF, ESCORTED, SUPPLIED,
        PLENTY, TROUBLE, FEVER, MOURNING, CRIME, FESTIVAL, BUILDING, RAISED, EASED, NEWCOMERS, LEAVING,
        HAULED, TALE, HELD, LOST,
    )

    // ---- What the close reads ----

    /** Hungry: the share of yesterday's meals gone without for -1, -2 and -4 (and at least a whole meal). */
    private const val PECKISH = 0.02
    private const val HUNGRY_SHARE = 0.05
    private const val STARVING = 0.15
    private const val A_MEAL = 1.0

    /** Fed: this many days of record running with under a meal gone without each. */
    private const val FED_DAYS = 7

    /** Food against its usual, weighed by its share of the eating: dear at these for -1, -2 and -3, cheap at this or under for +1. */
    private const val DEAR_1 = 1.25
    private const val DEAR_2 = 1.5
    private const val DEAR_3 = 2.0
    private const val CHEAP_AT = 0.85

    /** A food named as the dearest or cheapest only if the town eats this share of it or more (not the honey). */
    private const val NOTABLE = 0.03

    /** Wanting: a good gone short by at least so much of it, and this share of what the town wanted of it. */
    private const val SHORT_LEAST = 0.25
    private const val SHORT_SHARE = 0.02

    /** Coal counts while the homes are burning it for warmth at this or more of the usual (not high summer). */
    private const val HEATING = 0.75

    /** Idle: the mill or the ovens stood this many hours or more yesterday for want of grain or flour. */
    private const val IDLE_HOURS = 3

    /** A festival weighs for this many days after its own. */
    private const val FESTIVAL_DAYS = 3

    /** The market's trouble that counts as trouble here: the waylaid wagons and a fever are reasons of their own. */
    private val TROUBLES = setOf(
        EventKind.BLIGHT, EventKind.MURRAIN, EventKind.FOWL_PEST, EventKind.MILL_BROKEN, EventKind.OVEN_CRACKED, EventKind.POOR_CATCH,
    )

    /**
     * The reasons read from the records at the close that begins market day [day] (two in the morning: the market
     * has just closed the day before), for [entry]'s capital: yesterday's hunger or a week fed, food dear or cheap,
     * candles, coal or tools gone short, the mill or the ovens idle, the worst road in, the market's luck and
     * trouble, a fever, a festival just past, and works going up. "The day" ones last until [day] only; "while so"
     * ones hold (-1) until the close that no longer finds them.
     */
    fun read(level: ServerLevel, entry: Markets.Entry, day: Long): List<Reason> {
        val m = entry.market
        val out = ArrayList<Reason>()
        hungry(m, entry.town.people, day)?.let(out::add)
        fed(m)?.let(out::add)
        prices(m)?.let(out::add)
        wanting(entry, day)?.let(out::add)
        idle(m, day)?.let(out::add)
        road(level, m.capital)?.let(out::add)
        out.addAll(events(m))
        festival(level, m, day)?.let(out::add)
        building(level, m.capital)?.let(out::add)
        return out
    }

    /** -1, -2 or -4 by the share of yesterday's meals the town went without for want of food; its words, the meals and the share. */
    private fun hungry(m: Market, people: Int, day: Long): Reason? {
        val missed = m.hungry.lastOrNull() ?: return null
        val meals = people * Economy.MEALS
        if (meals <= 0.0 || missed < A_MEAL) return null
        val share = missed / meals
        val points = when {
            share >= STARVING -> -4
            share >= HUNGRY_SHARE -> -2
            share >= PECKISH -> -1
            else -> return null
        }
        return reason(HUNGRY, points, day, listOf(missed.roundToInt().toString(), (share * 100).roundToInt().toString()))
    }

    /** +1 while no meal has gone without for a week (under one a day, for a week of record). */
    private fun fed(m: Market): Reason? {
        val week = m.hungry.takeLast(FED_DAYS)
        if (week.size < FED_DAYS || week.any { it >= A_MEAL }) return null
        return reason(FED, 1, -1)
    }

    /**
     * Each food's last close against its usual (its base price and what it costs to bring in, as the stalls reckon
     * it), weighed by its share of the eating: dear or cheap, with the share of the usual it comes to and the food
     * furthest off it.
     */
    private fun prices(m: Market): Reason? {
        var sum = 0.0
        var weight = 0.0
        var dearest: Good? = null
        var dearestOff = 1.0
        var cheapest: Good? = null
        var cheapestOff = 1.0
        for (g in Good.FOODS) {
            val close = m.closes[g.ordinal].lastOrNull() ?: continue
            val off = close / m.freight(g)
            sum += g.share * off
            weight += g.share
            if (g.share < NOTABLE) continue
            if (off > dearestOff) {
                dearest = g
                dearestOff = off
            }
            if (off < cheapestOff) {
                cheapest = g
                cheapestOff = off
            }
        }
        if (weight <= 0.0) return null
        val index = sum / weight
        val points = when {
            index >= DEAR_3 -> -3
            index >= DEAR_2 -> -2
            index >= DEAR_1 -> -1
            index <= CHEAP_AT -> 1
            else -> return null
        }
        val good = if (points < 0) dearest else cheapest
        return reason(if (points < 0) DEAR else CHEAP, points, -1, listOfNotNull((index * 100).roundToInt().toString(), good?.id))
    }

    /** -1 for each of candles, coal (while the homes burn it for warmth) and tools the town went short of yesterday. */
    private fun wanting(entry: Markets.Entry, day: Long): Reason? {
        val m = entry.market
        val heating = Markets.felt(entry).heating >= HEATING
        val goods = listOfNotNull(Good.CANDLE, Good.COAL.takeIf { heating }, Good.TOOLS).filter { g ->
            val short = m.yesterday(Tally.SHORT, g)
            short >= SHORT_LEAST && short >= (m.yesterday(Tally.USED, g) + short) * SHORT_SHARE
        }
        if (goods.isEmpty()) return null
        return reason(WANTING, -goods.size, day, goods.map { it.id })
    }

    /** -1 each for the mill and the ovens standing idle yesterday for want of grain or flour. */
    private fun idle(m: Market, day: Long): Reason? {
        val makers = listOf(Maker.MILL, Maker.OVENS).filter { m.idleYesterday[it.ordinal] >= IDLE_HOURS }
        if (makers.isEmpty()) return null
        return reason(IDLE, -makers.size, day, makers.map { it.id })
    }

    /** -1 Troubled, -2 Perilous, -3 Closed, for the worst road into the capital whose heart is [capital] (with the bands about). */
    private fun road(level: ServerLevel, capital: Long): Reason? {
        if (!Caravans.ambushes()) return null
        val worst = CaravanData.get(level).roads.filter { it.capital == capital }.maxByOrNull { it.peril } ?: return null
        val stage = RoadPeril.stage(worst.peril)
        val points = when (stage) {
            RoadPeril.Stage.OPEN -> return null
            RoadPeril.Stage.TROUBLED -> -1
            RoadPeril.Stage.PERILOUS -> -2
            RoadPeril.Stage.CLOSED -> -3
        }
        return reason(ROAD, points, -1, listOf(stage.id, side(capital, worst.road)))
    }

    /** The market's luck (+1 for a bumper crop or a good catch), its other trouble (-1 each) and a fever (-2), while they're on. */
    private fun events(m: Market): List<Reason> {
        val out = ArrayList<Reason>()
        val luck = m.events.filter { it.kind == EventKind.BUMPER || it.kind == EventKind.GOOD_CATCH }.map(::code).distinct()
        if (luck.isNotEmpty()) out.add(reason(PLENTY, 1, -1, luck))
        val trouble = m.events.filter { it.kind in TROUBLES }.map(::code).distinct()
        if (trouble.isNotEmpty()) out.add(reason(TROUBLE, -trouble.size, -1, trouble))
        if (m.events.any { it.kind == EventKind.FEVER }) out.add(reason(FEVER, -2, -1))
        return out
    }

    /** An event in words' terms: its kind, and the crop it falls on ("blight:wheat"). */
    private fun code(e: Event): String = e.good?.let { "${e.kind.id}:${it.id}" } ?: e.kind.id

    /**
     * +2 for the [FESTIVAL_DAYS] days after a festival, and +1 more if no meal went without on the festival's day
     * (the market's day and the calendar's are the same day, but for the small hours).
     */
    private fun festival(level: ServerLevel, m: Market, day: Long): Reason? {
        if (!Seasons.festivalsEnabled()) return null
        for (k in 1..FESTIVAL_DAYS) {
            val on = day - k
            val kind = FestivalCalendar.on(Seasons.dateOn(level.server, on)) ?: continue
            val missed = m.hungry.getOrNull(m.hungry.size - k)
            val fed = missed != null && missed < A_MEAL
            return reason(FESTIVAL, if (fed) 3 else 2, on + FESTIVAL_DAYS, listOfNotNull(kind.id, "fed".takeIf { fed }))
        }
        return null
    }

    /** +1 while a works of the town's is going up (a stall of a player's own is theirs, not the town's). */
    private fun building(level: ServerLevel, capital: Long): Reason? {
        if (!Works.enabled()) return null
        val going = Works.all(level, capital).filter { it.state == WorksState.BUILDING && it.kind.per != WorksKind.Per.PLAYER }
        if (going.isEmpty()) return null
        return reason(BUILDING, 1, -1, going.map { it.kind.id }.distinct())
    }

    private fun reason(kind: String, points: Int, until: Long, args: List<String> = listOf()): Reason = Reason(kind, points, until, args, listOf(), -1)

    /** The way [road] comes into the capital whose heart is [capital], as road news names it: "n", "e", "s", "w", or "any" not knowing. */
    fun side(capital: Long, road: Int): String = CaravanRoutes.cached(capital, road)?.side?.name?.lowercase() ?: "any"

    // ---- What's noted ----

    /** The least and most each noted kind's notes come to together; any other kind's (from another part, or by hand) is kept to the score's bounds. */
    private val CAPS: Map<String, IntRange> = mapOf(
        WAYLAID to -2..0, RELIEF to 0..2, ESCORTED to 0..2, SUPPLIED to 0..2, MOURNING to -2..0, CRIME to -3..0,
        RAISED to 0..2, EASED to 0..1, NEWCOMERS to 0..1, LEAVING to -1..0,
        HAULED to 0..2, TALE to -2..2, HELD to 0..2, LOST to -3..0,
    )
    private val ANY = Fortunes.LOWEST..Fortunes.HIGHEST

    /** Kinds whose notes, said again on the same day of the same works or player, are the one note (a player selling twice, a granary easing each hour, a player delivering twice). */
    internal val SAME: Set<String> = setOf(SUPPLIED, RAISED, EASED, HAULED)

    /** The most notes a capital keeps (the oldest go first; every kind is capped well under it). */
    internal const val NOTES = 64

    /**
     * The noted reasons in force at the close that begins market day [day]: each note counts at the closes after the
     * day it was made ([now]: the day's own as well, for `fortune step`) until its last. A kind's notes make one
     * reason, kept to its cap: supplied counts the players (+1, or +2 for three or more), raised and eased are one
     * reason a works, and a note made by hand is a reason of its own.
     */
    fun noted(notes: List<FortuneData.Note>, day: Long, now: Boolean = false): List<Reason> {
        val live = notes.filter { (it.from < day || now && it.from <= day) && it.until >= day }
        val out = ArrayList<Reason>()
        for (n in live) if (n.hand) out.add(Reason(n.kind, n.points, n.until, n.args, listOfNotNull(n.about), n.works))
        for ((kind, list) in live.filter { !it.hand }.groupBy { it.kind }) out.addAll(merge(kind, list))
        return out.filter { it.points != 0 }
    }

    /** One kind's notes ([notes], in the order they were made) as the reasons they make. */
    private fun merge(kind: String, notes: List<FortuneData.Note>): List<Reason> {
        val cap = CAPS[kind] ?: ANY
        val until = notes.maxOf { it.until }
        val about = notes.mapNotNull { it.about }.distinct()
        val last = notes.last()
        return when (kind) {
            SUPPLIED -> {
                // Each player once: a note without one is someone of its own
                val players = notes.map { it.about?.toString() ?: "#" + it.args.firstOrNull().orEmpty() + "@" + it.from }.distinct().size
                val names = notes.mapNotNull { it.args.firstOrNull()?.takeIf { s -> s.isNotEmpty() } }.distinct()
                listOf(Reason(kind, (if (players >= 3) 2 else 1).coerceIn(cap), until, names, about, -1))
            }
            RAISED, EASED -> notes.groupBy { it.works }.map { (works, list) ->
                Reason(kind, list.maxOf { it.points }.coerceIn(cap), list.maxOf { it.until }, list.last().args, list.mapNotNull { it.about }.distinct(), works)
            }
            CRIME -> {
                // What was done, each with how many times: "theft", "2", "raid", "1"
                val counts = LinkedHashMap<String, Int>()
                for (n in notes) n.args.firstOrNull()?.let { counts[it] = (counts[it] ?: 0) + 1 }
                listOf(Reason(kind, notes.sumOf { it.points }.coerceIn(cap), until, counts.flatMap { (what, n) -> listOf(what, n.toString()) }, about, -1))
            }
            // How many, then the last one's words
            WAYLAID, NEWCOMERS, LEAVING -> listOf(
                Reason(kind, notes.sumOf { it.points }.coerceIn(cap), until, listOf(notes.size.toString()) + last.args, about, last.works),
            )
            // How many wagons, then every escort's name
            ESCORTED -> listOf(
                Reason(kind, notes.sumOf { it.points }.coerceIn(cap), until, listOf(notes.size.toString()) + notes.mapNotNull { it.args.firstOrNull() }.distinct(), about, -1),
            )
            MOURNING -> listOf(Reason(kind, notes.sumOf { it.points }.coerceIn(cap), until, notes.mapNotNull { it.args.firstOrNull() }.distinct(), about, -1))
            else -> listOf(Reason(kind, notes.sumOf { it.points }.coerceIn(cap), until, last.args, about, last.works))
        }
    }

    // ---- In words ----

    /** The most names a reason lists before "and others". */
    private const val NAMES = 3

    /** [reason] in a sentence, as the board's slip, the crier and the commands say it (its kind's name if its words are missing, as one made by hand). */
    fun text(reason: Reason): MutableComponent {
        val a = reason.args
        val said: MutableComponent? = when (reason.kind) {
            HUNGRY -> a.getOrNull(0)?.let { if (it == "1") say("hungry.one") else say("hungry", it) }
            FED -> say("fed")
            DEAR, CHEAP -> a.getOrNull(0)?.let { percent ->
                val good = a.getOrNull(1)?.let(Good::byId)
                if (good != null) say(reason.kind, percent, many(good)) else say("${reason.kind}.all", percent)
            }
            WANTING -> a.mapNotNull(Good::byId).takeIf { it.isNotEmpty() }?.let { say("wanting", list(it.map(::many))) }
            IDLE -> when {
                "mill" in a && "ovens" in a -> say("idle.both")
                "mill" in a -> say("idle.mill")
                "ovens" in a -> say("idle.ovens")
                else -> null
            }
            ROAD -> a.getOrNull(0)?.takeIf { s -> RoadPeril.Stage.entries.any { it.id == s && it != RoadPeril.Stage.OPEN } }?.let { say("road.$it", way(a.getOrNull(1))) }
            WAYLAID -> a.getOrNull(0)?.toIntOrNull()?.let { n ->
                if (n == 1 && a.size >= 3) say("waylaid.one", place(a[1]), way(a[2])) else say("waylaid.many", n)
            }
            RELIEF -> say("relief", way(a.getOrNull(0)))
            ESCORTED -> a.getOrNull(0)?.toIntOrNull()?.let { n ->
                val who = a.drop(1).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() } ?: return@let null
                if (n == 1) say("escorted.one", names(who)) else say("escorted.many", names(who), n)
            }
            SUPPLIED -> a.takeIf { it.isNotEmpty() }?.let { say("supplied", names(it)) }
            PLENTY -> a.takeIf { it.isNotEmpty() }?.let { say("plenty", list(it.map(::event))) }
            TROUBLE -> a.takeIf { it.isNotEmpty() }?.let { say("trouble", list(it.map(::event))) }
            FEVER -> say("fever")
            MOURNING -> a.takeIf { it.isNotEmpty() }?.let { say("mourning", names(it)) }
            CRIME -> a.chunked(2).filter { it.size == 2 }.mapNotNull { (what, n) -> crime(what, n.toIntOrNull() ?: 1) }
                .takeIf { it.isNotEmpty() }?.let { say("crime", list(it)) }
            FESTIVAL -> a.getOrNull(0)?.let { id -> FestivalKind.entries.firstOrNull { it.id == id } }?.let {
                say(if ("fed" in a) "festival.fed" else "festival", it.title)
            }
            BUILDING -> a.takeIf { it.isNotEmpty() }?.let { say("building", list(it.map(::works))) }
            RAISED -> a.getOrNull(0)?.let { kind ->
                val patron = a.getOrNull(1)?.takeIf { it.isNotEmpty() }
                if (patron != null) say("raised", works(kind), patron) else say("raised.nobody", works(kind))
            }
            EASED -> a.getOrNull(0)?.let { kind ->
                val what = a.getOrNull(1)?.takeIf { it == "mill" || it == "bread" || it == "band" } ?: return@let null
                val patron = a.getOrNull(2)?.takeIf { it.isNotEmpty() }
                val road = way(a.getOrNull(3))
                when {
                    what == "band" && patron != null -> say("eased.band", works(kind), road, patron)
                    what == "band" -> say("eased.band.nobody", works(kind), road)
                    patron != null -> say("eased.$what", works(kind), patron)
                    else -> say("eased.$what.nobody", works(kind))
                }
            }
            NEWCOMERS, LEAVING -> a.getOrNull(0)?.toIntOrNull()?.let { n ->
                val why = a.getOrNull(1).orEmpty()
                val family = a.getOrNull(2)?.takeIf { it.isNotEmpty() }
                val kind = reason.kind
                when {
                    n > 1 -> say("$kind.many", n)
                    family != null && kind == NEWCOMERS && why == "return" -> say("newcomers.home", family)
                    family != null -> say("$kind.family", family)
                    else -> say("$kind.one")
                }
            }
            // The side the goods came in from ("n", "e", "s", "w"); a note by hand has none, and reads as its kind's name
            HAULED -> a.getOrNull(0)?.let { say("hauled", way(it)) }
            // "<template>.<ending>" ("note.paid") then the names its sentence uses; a sentence the language file lacks reads "A tale."
            TALE -> a.getOrNull(0)?.let { sayOr("tale.$it", TALE_FALLBACK, a.getOrNull(1).orEmpty(), a.getOrNull(2).orEmpty()) }
            HELD -> say("held")
            LOST -> say("lost")
            else -> null
        }
        return said ?: label(reason.kind).append(".")
    }

    /**
     * The two biggest of [reasons] (biggest first already) that pull the way [sign] says (1 for the better, -1 for
     * the worse), each in a sentence, one after another; null if none does.
     */
    fun because(reasons: List<Reason>, sign: Int, most: Int = 2): MutableComponent? {
        val pulling = reasons.filter { Integer.signum(it.points) == sign }.take(most)
        if (pulling.isEmpty()) return null
        val out = Component.empty()
        pulling.forEachIndexed { k, r ->
            if (k > 0) out.append(" ")
            out.append(text(r))
        }
        return out
    }

    /** A kind's name ("Hunger", "Mourning"), or the kind itself for one this table doesn't know. */
    fun label(kind: String): MutableComponent = Component.translatableWithFallback("fortune.guildmark.kind.$kind", kind)

    /** A works of [kind] in a sentence, "the granary"'s "granary" (see [WorksKind]). */
    fun works(kind: String): MutableComponent = Component.translatableWithFallback("fortune.guildmark.works.$kind", kind.replace('_', ' '))

    private fun say(key: String, vararg args: Any): MutableComponent = Component.translatable("fortune.guildmark.reason.$key", *args)

    /** As [say], for a sentence the language file may not have (each tale's is its own): [fallback] reads in its place, never the key. */
    private fun sayOr(key: String, fallback: String, vararg args: Any): MutableComponent =
        Component.translatableWithFallback("fortune.guildmark.reason.$key", fallback, *args)

    /** What a tale's reason reads when its sentence is missing: its kind's name (fortune.guildmark.kind.tale), with the stop [text] puts after one. */
    private const val TALE_FALLBACK = "A tale."

    private fun many(g: Good): MutableComponent = Component.translatable(g.key + ".many")

    /** The road in by [side] ("the north road"; "the road" not knowing which). */
    private fun way(side: String?): MutableComponent = Component.translatable("road.guildmark.way." + (side?.takeIf { it in SIDES } ?: "any"))

    private val SIDES = setOf("n", "e", "s", "w", "any")

    /** Where on the road a wagon was taken ("at the ford"). */
    private fun place(place: String): MutableComponent = Component.translatable("road.guildmark.place." + (place.takeIf { it in Caravans.PLACES } ?: "road"))

    /** An event's code (see [code]) in words: "the Wheat crop failing", "an oven cracked". */
    private fun event(code: String): MutableComponent {
        val kind = code.substringBefore(':')
        val good = code.substringAfter(':', "").let(Good::byId)
        return if (good != null) Component.translatable("fortune.guildmark.event.$kind", Component.translatable(good.key))
        else Component.translatableWithFallback("fortune.guildmark.event.$kind", kind.replace('_', ' '))
    }

    /** One kind of crime, [n] times: "a purse lifted", "3 purses lifted". */
    private fun crime(what: String, n: Int): MutableComponent? {
        if (what !in CRIMES) return null
        return if (n <= 1) Component.translatable("fortune.guildmark.crime.$what") else Component.translatable("fortune.guildmark.crime.$what.many", n)
    }

    private val CRIMES = setOf("theft", "caught", "raid", "heist")

    /** Up to [NAMES] names, and "others" for the rest. */
    private fun names(names: List<String>): MutableComponent {
        val shown: List<MutableComponent> = names.take(NAMES).map { Component.literal(it) }
        return list(if (names.size > NAMES) shown + Component.translatable("fortune.guildmark.others") else shown)
    }

    /** [parts] one after another: "A", "A and B", "A, B and C". */
    fun list(parts: List<MutableComponent>): MutableComponent {
        if (parts.size <= 1) return parts.firstOrNull() ?: Component.empty()
        val head = Component.empty()
        parts.dropLast(1).forEachIndexed { k, p ->
            if (k > 0) head.append(", ")
            head.append(p)
        }
        return Component.translatable("fortune.guildmark.and", head, parts.last())
    }

    /** How a reason's days stand at market day [today]: "today", "while so", or how many days more. */
    fun lasts(reason: Reason, today: Long): String = when {
        reason.until < 0 -> "while so"
        reason.until <= today -> "today"
        reason.until - today == 1L -> "1 more day"
        else -> "${reason.until - today} more days"
    }

    /** The sum of [reasons]' points, kept to the score's bounds. */
    fun score(reasons: List<Reason>): Int = reasons.sumOf { it.points }.coerceIn(Fortunes.LOWEST, Fortunes.HIGHEST)

    /** [reasons] biggest first (for or against), in the table's order between equals. */
    fun biggestFirst(reasons: List<Reason>): List<Reason> = reasons.sortedWith(
        compareByDescending<Reason> { abs(it.points) }.thenBy { KINDS.indexOf(it.kind).let { k -> if (k < 0) KINDS.size else k } },
    )
}

/**
 * The reasons noted as they happen (see [Fortunes.note]): each one's kind, its points, how many market days it
 * lasts, and for crime which kind of crime it was ([what], put first among the note's words). The calls, one line
 * each, where they happen: `Residents.died` (MOURNING), `Witness.robbed` and `caught` (THEFT, CAUGHT), `Raids.start`
 * (RAID), a heist's escape (HEIST), `Witness.dealt` and `filled` (SUPPLIED); and [Fortunes]' own listeners for the
 * road (WAYLAID, RELIEF, ESCORTED), the works (RAISED, EASED) and the households (NEWCOMERS, LEAVING). The last three
 * belong to the later designs: a delivery from afar (HAULED), a siege held or lost (HELD, LOST). A tale's reason has no
 * entry here, as its points and days go by its ending (`Fortunes.note` with the kind [FortuneReasons.TALE]).
 */
enum class Noted(val kind: String, val points: Int, val days: Int, val what: String? = null) {
    WAYLAID(FortuneReasons.WAYLAID, -1, 3),
    RELIEF(FortuneReasons.RELIEF, 2, 5),
    ESCORTED(FortuneReasons.ESCORTED, 1, 3),
    SUPPLIED(FortuneReasons.SUPPLIED, 1, 3),
    MOURNING(FortuneReasons.MOURNING, -1, 7),
    THEFT(FortuneReasons.CRIME, -1, 3, "theft"),
    CAUGHT(FortuneReasons.CRIME, -1, 3, "caught"),
    RAID(FortuneReasons.CRIME, -2, 3, "raid"),
    HEIST(FortuneReasons.CRIME, -2, 3, "heist"),
    RAISED(FortuneReasons.RAISED, 2, 7),
    EASED(FortuneReasons.EASED, 1, 3),
    NEWCOMERS(FortuneReasons.NEWCOMERS, 1, 5),
    LEAVING(FortuneReasons.LEAVING, -1, 5),
    HAULED(FortuneReasons.HAULED, 1, 3),
    HELD(FortuneReasons.HELD, 2, 5),
    LOST(FortuneReasons.LOST, -3, 5),
}
