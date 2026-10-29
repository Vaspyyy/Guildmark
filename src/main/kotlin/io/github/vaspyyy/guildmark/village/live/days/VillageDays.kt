package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.townsfolk.Agenda
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Stretch
import io.github.vaspyyy.guildmark.townsfolk.TownRole

/**
 * A village's people's days (living-world/villages/design.md 5.4): the capital's own day for who they are ([Days.plan], a pure
 * function of the resident and the date), shaped to the village they live in. What changes is a few stretches put in or taken out,
 * each of the shape its place in the day calls for, and the ones that need to be played differently carry a [Stretch.tag]:
 *
 * - [STALL]: a keeper's day. Their own trade until ten (eight on a market day), the stall until four with an hour at noon, then
 *   their trade again.
 * - [GREEN]: market day for the rest. From half past ten to half past one they are about the green, the stalls and the folk there
 *   (a lunch on a bench if they like), and the children play on the green the day through.
 * - [BELL]: the holder of the bell goes to it and rings the dusk bell at half past five.
 * - [BOARD]: the day's poster takes a note to the board and nails it up.
 * - [LAMPS] and [SNUFF]: a keeper of the lamps is free of an evening from five to seven for the round, and up of a morning from a
 *   quarter past five to a quarter to seven to put them out (cutting their sleep short), every day of the week.
 * - [BUILD]: one lent to the village's building site works there from seven to noon and from one to a quarter to six, with their
 *   dinner at home between, on any day but the rest day (townsfolk/Labour hands them to the crew).
 *
 * The rest day keeps its own shape (the service, the market, visits); the stalls rest and market day gives way to it. The guards'
 * stretches and the children's lessons are the capital's as they are, played on the village's places ([VillageActs]).
 */
object VillageDays {
    const val STALL = "stall"
    const val GREEN = "green"
    const val BELL = "bell"
    const val BOARD = "board"
    const val LAMPS = "lamps"
    const val SNUFF = "snuff"
    const val BUILD = "build"

    /** The hour a keeper is at the stall from, on a working day and on a market day, and until. */
    private const val STALL_FROM = 10.0
    private const val STALL_FROM_MARKET = 8.0
    private const val STALL_TO = 16.0

    /** The green's hours on market day. */
    private const val GREEN_FROM = 10.5
    private const val GREEN_TO = 13.5

    /** The bellman's errand: from five, to be at the bell and to wait for the hour ([BellKeeper.DUSK]); at ten to six they are let go. */
    private const val BELL_FROM = 17.0
    private const val BELL_TO = 17.9

    /** The lamp keeper's evening, free for the round, and their morning, up to put the lamps out. */
    private const val LAMPS_FROM = 17.0
    private const val LAMPS_TO = 19.0
    private const val SNUFF_FROM = 5.25
    private const val SNUFF_TO = 6.75

    /** A builder's hours at the village's site (the crew's own day is from sunrise to a quarter to six: VillageSite). */
    private const val BUILD_FROM = 7.0
    private const val BUILD_TO = 17.75

    /** What is never cut across: bed, the watch and the crier's and builders' stretches. */
    private val KEPT = setOf(Doing.SLEEP, Doing.BED, Doing.WATCH, Doing.CRY, Doing.BUILD)

    /** [life]'s day number [day] as [base] (the capital's planner's) has it, shaped to the village. */
    fun shape(life: Life, base: Agenda, day: Long): Agenda {
        val r = life.resident
        val list = base.stretches.toMutableList()
        val rest = Days.restDay(day)
        // Lent to the village's site: its hours, with dinner at home at noon (first, since nothing is laid over a building stretch)
        if (!rest && life.builder) build(list)
        if (!rest) {
            when {
                life.keeper >= 0 -> stall(list, life.market)
                life.market && r.role == TownRole.CHILD -> children(list)
                life.market && r.role.adult && r.role != TownRole.GUARD && !life.carter -> interrupt(list, GREEN_FROM, GREEN_TO, Doing.MARKET, GREEN)
            }
        }
        // The rounds of the lamps, every evening and morning of the week: the keeper's evening is free of other things, their morning up early
        if (life.lamps) {
            interrupt(list, LAMPS_FROM, LAMPS_TO, Doing.ERRAND, LAMPS)
            wake(list, SNUFF_FROM, SNUFF_TO, SNUFF)
        }
        // The day's note to the board, in the day's own slot or the next that fits; not if it is pinned already (the day is re-planned)
        if (life.poster && r.pinned != day) {
            for ((from, to) in Posters.slots(life.places.centre, day, rest)) if (interrupt(list, from, to, Doing.ERRAND, BOARD)) break
        }
        // The dusk bell, every day of the week
        if (life.bellman) interrupt(list, BELL_FROM, BELL_TO, Doing.ERRAND, BELL)
        return if (list.size == base.stretches.size && list.indices.all { list[it] === base.stretches[it] }) base else Agenda(day, list)
    }

    /** A builder's day ([BUILD]): the site from [BUILD_FROM] to noon and from one to [BUILD_TO], their dinner between (laid first, so the morning can end on it). */
    private fun build(list: MutableList<Stretch>) {
        interrupt(list, 12.0, 13.0, Doing.LUNCH, "")
        interrupt(list, BUILD_FROM, 12.0, Doing.BUILD, BUILD)
        interrupt(list, 13.0, BUILD_TO, Doing.BUILD, BUILD)
    }

    /** A keeper's day: the stall from [STALL_FROM] to [STALL_TO], an hour off at noon. */
    private fun stall(list: MutableList<Stretch>, market: Boolean) {
        interrupt(list, if (market) STALL_FROM_MARKET else STALL_FROM, STALL_TO, Doing.WORK, STALL)
        interrupt(list, 12.0, 12.75, Doing.LUNCH, "")
    }

    /** Market day for the children: the green, in place of their lessons and their play elsewhere. */
    private fun children(list: MutableList<Stretch>) {
        val from = Days.clock(8.0)
        val to = Days.clock(18.0)
        for (i in list.indices) {
            val s = list[i]
            if ((s.doing == Doing.LESSONS || s.doing == Doing.PLAY) && s.start in from until to) list[i] = Stretch(s.start, Doing.PLAY, GREEN)
        }
    }

    /** What is never cut across, but for sleep: the watch and the crier's and builders' stretches. */
    private val HARD = setOf(Doing.WATCH, Doing.CRY, Doing.BUILD)

    /**
     * An errand ([SNUFF]) from hour [from] to hour [to] in place of what the day had in between, sleep too: they are up early for it, and
     * at [to] the day goes on as it did, but for sleep (they are up for the day, and rise). False, and the day as it was, if it would
     * cut across the watch or anything else [HARD].
     */
    private fun wake(list: MutableList<Stretch>, from: Double, to: Double, tag: String): Boolean {
        val a = Days.clock(from)
        val b = Days.clock(to)
        if (b <= a) return false
        val at = list.lastOrNull { it.start <= a } ?: return false
        val resume = list.lastOrNull { it.start <= b } ?: return false
        if (at.doing in HARD || resume.doing in HARD || list.any { it.start in (a + 1)..b && it.doing in HARD }) return false
        list.removeAll { it.start in a until b }
        list.add(Stretch(a, Doing.ERRAND, tag))
        if (list.none { it.start == b }) {
            val abed = resume.doing == Doing.SLEEP || resume.doing == Doing.BED
            list.add(if (abed) Stretch(b, Doing.RISE) else Stretch(b, resume.doing, resume.tag))
        }
        list.sortBy { it.start }
        return true
    }

    /**
     * [doing] (tagged [tag]) from hour [from] to hour [to], in place of whatever stretches there were in between; the day goes on
     * at [to] as it did. False, and the day as it was, if it would cut across sleep, the watch or anything else that is [KEPT].
     */
    fun interrupt(list: MutableList<Stretch>, from: Double, to: Double, doing: Doing, tag: String): Boolean {
        val a = Days.clock(from)
        val b = Days.clock(to)
        if (b <= a) return false
        val at = list.lastOrNull { it.start <= a } ?: return false
        val resume = list.lastOrNull { it.start <= b } ?: return false
        if (at.doing in KEPT || resume.doing in KEPT || list.any { it.start in (a + 1)..b && it.doing in KEPT }) return false
        list.removeAll { it.start in a until b }
        list.add(Stretch(a, doing, tag))
        if (list.none { it.start == b }) list.add(Stretch(b, resume.doing, resume.tag))
        list.sortBy { it.start }
        return true
    }
}
