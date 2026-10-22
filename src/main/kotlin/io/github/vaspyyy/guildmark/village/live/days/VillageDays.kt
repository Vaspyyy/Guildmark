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
 *
 * The rest day keeps its own shape (the service, the market, visits); the stalls rest and market day gives way to it. The guards'
 * stretches and the children's lessons are the capital's as they are, played on the village's places ([VillageActs]).
 */
object VillageDays {
    const val STALL = "stall"
    const val GREEN = "green"
    const val BELL = "bell"
    const val BOARD = "board"

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

    /** What is never cut across: bed, the watch and the crier's and builders' stretches. */
    private val KEPT = setOf(Doing.SLEEP, Doing.BED, Doing.WATCH, Doing.CRY, Doing.BUILD)

    /** [life]'s day number [day] as [base] (the capital's planner's) has it, shaped to the village. */
    fun shape(life: Life, base: Agenda, day: Long): Agenda {
        val r = life.resident
        val list = base.stretches.toMutableList()
        val rest = Days.restDay(day)
        if (!rest) {
            when {
                life.keeper >= 0 -> stall(list, life.market)
                life.market && r.role == TownRole.CHILD -> children(list)
                life.market && r.role.adult && r.role != TownRole.GUARD && !life.carter -> interrupt(list, GREEN_FROM, GREEN_TO, Doing.MARKET, GREEN)
            }
        }
        // The dusk bell, every day of the week
        if (life.bellman) interrupt(list, BELL_FROM, BELL_TO, Doing.ERRAND, BELL)
        return if (list.size == base.stretches.size && list.indices.all { list[it] === base.stretches[it] }) base else Agenda(day, list)
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
