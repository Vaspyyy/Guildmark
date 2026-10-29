package io.github.vaspyyy.guildmark.village.live.grow

/**
 * The rules of a village's growth (living-world/villages/design.md 5.5 and 5.6), worked on a plain picture of its record: its
 * homes and their beds, who lives where, who died lately, who is away, and how the times stand. Nothing here touches the world,
 * so the very same rules run at each village's daily close ([VillageGrowth]) and in the harness that runs a record over many
 * days to prove the caps and the waits.
 *
 * At each close ([close]), in this order:
 * - **Coming of age**: a child [CHILD_DAYS] days in the village is grown up (their body is remade out of sight).
 * - **The waits**: each death seen, and each home found empty, begins a wait of [WAIT] to [WAIT] + [WAIT_SPAN] - 1 days (fixed by
 *   the village and what it is about) for a newcomer.
 * - **A birth**: in good enough times (not hard, [FED], no siege lost in the last [SIEGE_DAYS] days) and with room, each home
 *   with two grown-ups and a free bed rolls one in [BIRTH_ODDS], in an order fixed by the day; the first to roll has a child,
 *   one a day at most. With a player in that home the birth waits until the next close.
 * - **Newcomers**: a wait whose day has come brings a household to an empty home at the next dusk, one at a time: those of
 *   the village who left in hard times first (to their old home if it is empty), else kin of a household here or strangers
 *   seeking work. Not in hard times (the wait goes on); never past the bed count or the cap (the wait is dropped); never
 *   without an empty home.
 * - **At zero**: a village with nobody left takes a household [ZERO_EVERY] days after it emptied or after the last came, and
 *   [ZERO_HARD] in hard times: never fewer than one a week.
 * - **Leaving**: after [HARD_RUN] hard days running a household goes, then one a [LEAVE_EVERY] days at most: never one with a
 *   duty, the newest first, and never below [KEEP_HOUSEHOLDS] households, [KEEP_PEOPLE] people or [LEAVE_FLOOR] of the people
 *   there were when the hard run began, so the village never empties by its own doing.
 *
 * The cap is the beds and `maxVillageFolk`, whichever is less, counted with the vanilla villagers still about and the household
 * on its way. A village whose villagers have not been counted yet grows nothing (but its children grow up).
 */
object GrowthRules {
    /** One in this many fed couples with a free bed has a child at a close. */
    const val BIRTH_ODDS = 6

    /** Days a child is a baby (no body: carried in its parent's arms), and days until they are grown. */
    const val BABY_DAYS = 3
    const val CHILD_DAYS = 8

    /** Days a newcomer is waited for after a death or an empty home: [WAIT] to [WAIT] + [WAIT_SPAN] - 1. */
    const val WAIT = 3
    const val WAIT_SPAN = 5

    /** Days between households coming to a village with nobody left, and in hard times. */
    const val ZERO_EVERY = 4
    const val ZERO_HARD = 7

    /** No birth for this many days after a siege was lost; the hungry days looked back over for [FED]. */
    const val SIEGE_DAYS = 3
    const val FED_DAYS = 3

    /** Hard days running before a household leaves, and the least days between two leaving. */
    const val HARD_RUN = 5
    const val LEAVE_EVERY = 7

    /** Nobody leaves who would leave fewer than these, or under this share of the people at the start of the hard run. */
    const val KEEP_HOUSEHOLDS = 2
    const val KEEP_PEOPLE = 3
    const val LEAVE_FLOOR = 0.85

    /** Days a death stays in the picture; days past its day a wait is given up; the most waits kept. */
    const val DEAD_DAYS = 30
    const val STALE = 30
    const val MOST_DUE = 24

    /** In this many of a hundred, strangers' newcomers are kin of a household of the village instead. */
    const val KIN = 40

    /** Why a newcomer comes. */
    const val DEATH = "death"
    const val EMPTY = "empty"
    const val ZERO = "zero"
    const val RETURN = "return"
    const val COMMAND = "command"

    /** A home of the village: its id in the record, and how many beds it has. */
    class Home(val id: Int, val beds: Int)

    /** Someone living in the village: grown or a child, their home (-1: none), the day they came or were born, and whether a duty keeps them (the watch). */
    class Person(val id: Int, val adult: Boolean, val home: Int, val since: Long, val duty: Boolean = false)

    /** Someone who died here on [day], who lived in [home]. */
    class Dead(val id: Int, val day: Long, val home: Int)

    /** A household of the village away since [day] (gone in hard times): [members], [adults] of them grown, and the home they had. */
    class Away(val members: List<Int>, val adults: Int, val home: Int, val day: Long)

    /**
     * The village as its record has it at a close: its standing [homes], its [living], its [dead] of the last [DEAD_DAYS] days,
     * those [away], and the vanilla villagers it still has ([vanilla], -1 until they have been counted).
     */
    class Picture(val homes: List<Home>, val living: List<Person>, val dead: List<Dead>, val away: List<Away>, val vanilla: Int)

    /**
     * How things stand at a close: [fed] (no hungry day in the last [FED_DAYS]), the times [hard], the [hardRun] (hard days
     * running), the day the last siege was lost ([siegeLost], -1 for none), the most folk a village may have ([cap]), and
     * [watched]: whether a player is in a home (by its id) now.
     */
    class Times(
        val fed: Boolean, val hard: Boolean, val hardRun: Int, val siegeLost: Long, val cap: Int, val watched: (Int) -> Boolean = { false },
    )

    /** A newcomer waited for: why ([DEATH], [EMPTY]), the home it is about (-1: none), the day the wait began and the day they come. */
    class Due(val cause: String, val home: Int, val from: Long, val at: Long)

    /**
     * A household on its way: the [day] it comes (at dusk), the [home] it comes to, why ([cause]), how many grown-ups and
     * children, the home whose family they are kin to ([kin], -1 for strangers), the members of an away household coming
     * back ([back], empty for new people), the day the wait began ([from]) and the [seed] their names and looks are drawn by.
     */
    class Coming(
        val day: Long, val home: Int, val cause: String, val adults: Int, val children: Int, val kin: Int, val back: List<Int>, val from: Long, val seed: Long,
    ) {
        val size: Int get() = if (back.isNotEmpty()) back.size else adults + children
    }

    /** A household to leave of a morning on [day]: the one living in [home]. */
    class Leaving(val day: Long, val home: Int)

    /** What a village's growth keeps from day to day (saved with the world by [GrowthData]). */
    class State {
        /** The last day closed, so no day is closed twice. */
        var closed = -1L

        /** The days of the last birth, arrival and leaving (-1: none yet). */
        var born = -1L
        var arrived = -1L
        var left = -1L

        /** The day the village was first found with nobody, while it has nobody (-1 otherwise). */
        var zero = -1L

        /** The people there were as the hard run that lets households leave began (-1 when not in one). */
        var base = -1

        /** A home whose birth waits for a player to leave it (-1: none). */
        var waiting = -1

        /** The deaths and the empty homes whose waits have begun. */
        val seen: MutableSet<Int> = java.util.TreeSet()
        val empty: MutableSet<Int> = java.util.TreeSet()

        val due: MutableList<Due> = ArrayList()
        var coming: Coming? = null
        var leaving: Leaving? = null

        /** Why nothing more came of the last close, in words (for `village grow`). */
        var why = ""

        /** The clock set back [days] days: every day kept goes back with it ([closed] too, so the days come round again). */
        fun back(days: Long) {
            if (days <= 0L) return
            fun b(d: Long) = if (d < 0L) d else maxOf(0L, d - days)
            closed = if (closed < 0L) closed else closed - days
            born = b(born)
            arrived = b(arrived)
            left = b(left)
            zero = b(zero)
            due.replaceAll { Due(it.cause, it.home, b(it.from), b(it.at)) }
            coming = coming?.let { Coming(b(it.day), it.home, it.cause, it.adults, it.children, it.kin, it.back, b(it.from), it.seed) }
            leaving = leaving?.let { Leaving(b(it.day), it.home) }
        }
    }

    /** What came of a close: the home a child is born in (-1: none), the children grown up, and the household set coming or leaving. */
    class Out {
        var birth = -1
        val ofAge = ArrayList<Int>()
        var coming: Coming? = null
        var leaving: Leaving? = null
        val said = ArrayList<String>()
    }

    /**
     * Closes [today] (a board day) for the village [key] whose record is [p], in the times [t]: [s] is brought up to date and
     * what is to happen is in the answer. Only [Out.birth] and [Out.ofAge] are left for the caller to do; a household set
     * coming or leaving is in [s] until the caller sees to it (and calls [arrived] or [gone]).
     */
    fun close(s: State, p: Picture, t: Times, key: Long, today: Long): Out {
        val out = Out()
        s.closed = today
        val beds = p.homes.sumOf { it.beds }
        val cap = minOf(beds, t.cap)
        val coming = s.coming
        var people = p.living.size + maxOf(0, p.vanilla) + (coming?.size ?: 0)

        for (c in p.living) if (!c.adult && today - c.since >= CHILD_DAYS) out.ofAge += c.id

        // The waits a death or an empty home begins
        for (d in p.dead) if (s.seen.add(d.id)) s.due += Due(DEATH, d.home, d.day, d.day + wait(key, d.id.toLong(), d.day))
        s.seen.retainAll(p.dead.mapTo(HashSet()) { it.id })
        val lived = p.living.mapTo(HashSet()) { it.home }
        coming?.let { lived += it.home }
        val empties = p.homes.filter { it.beds > 0 && it.id !in lived }.toMutableList()
        for (h in empties) if (s.empty.add(h.id)) s.due += Due(EMPTY, h.id, today, today + wait(key, 0x10000L + h.id, today))
        s.empty.retainAll(empties.mapTo(HashSet()) { it.id })
        s.due.removeAll { today - it.at > STALE }
        while (s.due.size > MOST_DUE) s.due.removeAt(0)

        // The hard run that lets a household leave, and the people there were as it began
        if (t.hardRun >= HARD_RUN) {
            if (s.base < 0) s.base = p.living.size
        } else {
            s.base = -1
        }
        val why = ArrayList<String>()
        leave(s, p, t, today, why, out)

        if (p.vanilla < 0) {
            s.waiting = -1
            s.why = "the village's own villagers have not been counted yet (it grows once someone has been there)"
            out.said += s.why
            return out
        }

        // A birth
        val room = cap - people
        when {
            t.hard -> why += "hard times: no births"
            !t.fed -> why += "a hungry day in the last $FED_DAYS: no births"
            t.siegeLost >= 0 && today - t.siegeLost <= SIEGE_DAYS -> why += "a siege lost on day ${t.siegeLost}: no births for $SIEGE_DAYS days"
            room < 1 -> why += if (beds <= t.cap) "every bed is taken ($people of $beds): no births" else "at the most folk a village may have ($people of ${t.cap}): no births"
            else -> {
                val home = birth(s, p, t, key, today, why)
                if (home >= 0) {
                    out.birth = home
                    s.born = today
                    people++
                    lived += home
                    empties.removeAll { it.id == home }
                }
            }
        }
        if (out.birth < 0 && why.none { it.startsWith("a player") }) s.waiting = -1

        // Newcomers: one household at a time, the first wait whose day has come
        if (coming == null) arrivals(s, p, t, key, today, cap - people, empties, why, out)
        s.why = why.joinToString("; ")
        out.said += why
        return out
    }

    /** The home a child is born in today, or -1: the one waiting on a player if it may now, else the first to roll. */
    private fun birth(s: State, p: Picture, t: Times, key: Long, today: Long, why: MutableList<String>): Int {
        val homes = p.homes.filter { h ->
            val here = p.living.filter { it.home == h.id }
            here.count { it.adult } >= 2 && here.size < h.beds
        }
        if (homes.isEmpty()) {
            why += "no home has two grown-ups and a free bed"
            return -1
        }
        val waited = homes.firstOrNull { it.id == s.waiting }
        if (waited != null) {
            if (t.watched(waited.id)) {
                why += "a player is in the home of the child to be born (it waits until nobody is)"
                return -1
            }
            s.waiting = -1
            return waited.id
        }
        for (h in homes.sortedBy { mix(key, today * 31 + it.id) }) {
            if (Math.floorMod(mix(key * 7 + h.id, today), BIRTH_ODDS.toLong()) != 0L) continue
            if (t.watched(h.id)) {
                s.waiting = h.id
                why += "a player is in the home of the child to be born (it waits until nobody is)"
                return -1
            }
            return h.id
        }
        why += "${homes.size} home${if (homes.size == 1) "" else "s"} could have a child; none did today (one in $BIRTH_ODDS a day)"
        return -1
    }

    /** A household set coming at the next dusk, if a wait's day has come (or the village has nobody and its days are up). */
    private fun arrivals(
        s: State, p: Picture, t: Times, key: Long, today: Long, room: Int, empties: MutableList<Home>, why: MutableList<String>, out: Out,
    ) {
        val day = today + 1
        val zero = p.living.isEmpty() && p.vanilla == 0
        if (!zero) {
            s.zero = -1
        } else if (s.zero < 0) {
            s.zero = today
        }
        if (zero) {
            val every = if (t.hard) ZERO_HARD else ZERO_EVERY
            val since = maxOf(s.zero, s.arrived)
            if (day - since >= every && room >= 1) {
                val home = empties.filter { it.beds > 0 }.minByOrNull { mix(key, day * 13 + it.id) }
                if (home != null) {
                    set(s, out, newcomers(key, day, home, ZERO, s.zero, minOf(room, home.beds), kinOf = null))
                    return
                }
            }
        }
        val ready = s.due.filter { it.at <= day }.sortedBy { it.at }
        if (ready.isEmpty()) {
            if (zero) {
                why += if (p.homes.none { it.beds > 0 }) "nobody lives here, and there is no bed for anyone to come to"
                else "nobody lives here: a household comes on day ${maxOf(s.zero, s.arrived) + (if (t.hard) ZERO_HARD else ZERO_EVERY)}"
            }
            return
        }
        if (t.hard) {
            why += "hard times: ${ready.size} newcomer${if (ready.size == 1) "" else "s"} wait${if (ready.size == 1) "s" else ""} for better"
            return
        }
        if (room < 1) {
            s.due.removeAll(ready)
            why += "at its bed count or cap: ${ready.size} newcomer${if (ready.size == 1) " does" else "s do"} not come"
            return
        }
        val due = ready.first()
        s.due.remove(due)
        // Those who left in hard times come home first, to their old home if it stands empty
        val away = p.away.firstOrNull { a -> a.members.size <= room && empties.any { it.beds >= a.members.size } }
        if (away != null) {
            val home = empties.firstOrNull { it.id == away.home && it.beds >= away.members.size } ?: empties.first { it.beds >= away.members.size }
            set(s, out, Coming(day, home.id, RETURN, away.adults, away.members.size - away.adults, -1, away.members, due.from, mix(key, day)))
            return
        }
        val home = empties.firstOrNull { it.id == due.home } ?: empties.minByOrNull { mix(key, day * 13 + it.id) }
        if (home == null) {
            why += "no empty home for a newcomer (the wait from day ${due.from} is over)"
            return
        }
        val households = p.living.filter { it.adult && it.home >= 0 }.map { it.home }.distinct()
        val kin = households.takeIf { Math.floorMod(mix(key, day * 17 + 5), 100L) < KIN }?.minByOrNull { mix(key, day * 19 + it) }
        set(s, out, newcomers(key, day, home, due.cause, due.from, minOf(room, home.beds), kin))
    }

    /** A household of new people coming on [day] to [home] for [cause], of at most [room]: kin of the home [kinOf], or strangers. */
    private fun newcomers(key: Long, day: Long, home: Home, cause: String, from: Long, room: Int, kinOf: Int?): Coming {
        val seed = mix(key, day * 0x9E37L + home.id)
        val (adults, children) = if (kinOf != null) kin(seed, room) else family(seed, room)
        return Coming(day, home.id, cause, adults, children, kinOf ?: -1, listOf(), from, seed)
    }

    private fun set(s: State, out: Out, c: Coming) {
        s.coming = c
        out.coming = c
    }

    /** A household set leaving of a morning, after a hard run, if one may go without the village falling under its floors. */
    private fun leave(s: State, p: Picture, t: Times, today: Long, why: MutableList<String>, out: Out) {
        if (s.leaving != null || t.hardRun < HARD_RUN) return
        val day = today + 1
        if (s.left >= 0 && day - s.left < LEAVE_EVERY) return
        val households = p.living.filter { it.home >= 0 }.groupBy { it.home }
        val floor = Math.ceil(LEAVE_FLOOR * maxOf(0, s.base)).toInt()
        val pick = households.entries
            .filter { (_, members) -> members.any { it.adult } && members.none { it.duty } }
            .sortedByDescending { (_, members) -> members.maxOf { it.since } }
            .firstOrNull { (_, members) ->
                val rest = p.living.size - members.size
                rest >= KEEP_PEOPLE && households.size - 1 >= KEEP_HOUSEHOLDS && rest >= floor
            }
        if (pick == null) {
            why += "hard times, but nobody leaves: the village keeps at least $KEEP_HOUSEHOLDS households, $KEEP_PEOPLE people and ${(LEAVE_FLOOR * 100).toInt()}% of its people"
            return
        }
        val l = Leaving(day, pick.key)
        s.leaving = l
        s.left = day
        out.leaving = l
    }

    /** The household [c] has come ([day]): the village has someone new. */
    fun arrived(s: State, day: Long) {
        s.coming = null
        s.arrived = day
        s.zero = -1
    }

    /** The household set leaving has gone (or no longer goes). */
    fun gone(s: State) {
        s.leaving = null
    }

    // ---- The numbers that are fixed by the village ----

    /** The wait for a newcomer about [what] from [day]: [WAIT] to [WAIT] + [WAIT_SPAN] - 1 days. */
    fun wait(key: Long, what: Long, day: Long): Int = WAIT + Math.floorMod(mix(key, what * 31 + day), WAIT_SPAN.toLong()).toInt()

    /** One or two grown-ups (two, mostly) and up to two children, as a capital's newcomers are (townsfolk/Comings.family), in [room]. */
    fun family(seed: Long, room: Int): Pair<Int, Int> {
        var adults = if (Math.floorMod(seed, 100L) < 70) 2 else 1
        var children = when (Math.floorMod(seed ushr 8, 100L).toInt()) {
            in 0..34 -> 0
            in 35..74 -> 1
            else -> 2
        }
        while (adults + children > room && children > 0) children--
        while (adults + children > room && adults > 1) adults--
        return adults to children
    }

    /** Kin come on their own, or with a child, in [room]. */
    fun kin(seed: Long, room: Int): Pair<Int, Int> {
        val children = if (Math.floorMod(seed ushr 12, 100L) < 30 && room >= 2) 1 else 0
        return 1 to children
    }

    /** A well-stirred mix of [a] and [b] (the same as townsfolk/Comings.mix, kept here so the rules stand alone). */
    fun mix(a: Long, b: Long): Long {
        var x = a * -0x61c8864680b583ebL xor b * 0x2545F4914F6CDD1DL
        x = x xor (x ushr 31)
        x *= -0x4b6d499041670d8dL
        return x xor (x ushr 29)
    }
}
