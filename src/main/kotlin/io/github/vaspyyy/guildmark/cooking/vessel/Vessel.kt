package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.Tier
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The vessel (living-world/cook/design.md 1.2): what a station holds and how it cooks, a state machine with three
 * clocks. It knows nothing of the world: the block entity gives it the game time and an [Env] and plays what it reports.
 *
 * - the **dish-clock** [p], in dish-seconds, runs while the vessel cooks and its fire has fuel, at the heat's [SPEED];
 * - the **fuel clock** is the store [fuel], in seconds of mid-heat cooking, burnt at [Fuel.BURN] by the notch;
 * - the **calls clock** [callClock] counts real fuelled seconds toward the next call (1.4).
 *
 * [advance] brings it up to a game time from its [stamp] by segments, so a pot that was not ticked (nobody near, its chunk
 * unloaded) is worked out when next touched: the fuel runs out, the window passes, the dish burns, clamped at BURNT and
 * never worse, with no calls issued and none missed (1.2, 9.3). Every timing is a named constant at the top.
 */
class Vessel(val station: Station) {
    companion object {
        // ---- The numbers (1.2): my estimates of what feels right, the first things to turn ----

        /** Real seconds, with the fire fuelled, from the first stir to the dish-clock starting. */
        const val HEAT_SECONDS = 12.0

        /** Dish-seconds the dish-clock gains in a real second on the low, mid and high notch. */
        val SPEED: DoubleArray = doubleArrayOf(0.6, 1.0, 1.6)

        /** The ready window is the kind's time less this to the kind's time plus this, dish-seconds. */
        const val WINDOW = 8.0

        /** The timing score is 1 less the dish-seconds from the kind's time over this: 0.75 at the window's edge, 0 at this far. */
        const val SPREAD = 32.0

        /** The dish burns this many dish-seconds past the kind's time. */
        const val BURN_PAST = 32.0

        /** The dish burns at the third missed STICK. */
        const val STICKS_TO_BURN = 3

        /** The time of a pot that makes Gruel (it has no kind), dish-seconds. */
        const val GRUEL_TIME = 60.0

        /** A step longer than this many ticks (5 s) is an unattended one: no calls are issued or missed in it (1.2). */
        const val UNATTENDED_TICKS = 100L

        /** The water of a full bucket, in steps of the surface (2: "the level drops a step every two portions"). */
        const val WATER_FULL = 8

        /** The most items a station holds: the pot 12, the pan 6, the oven's tray 10, the spit 6 (1.1). */
        fun capacity(station: Station): Int = when (station) {
            Station.STEWPOT -> 12
            Station.HOB -> 6
            Station.OVEN -> 10
            Station.SPIT -> 6
        }

        /** The tolerance of the segment loop, seconds. */
        private const val EPS = 1e-6

        /** The fewest items a station starts with: half the standard bill of the smallest kind it makes, and never under 2 (2.4). */
        fun minItems(station: Station): Int =
            max(2, DishKind.entries.filter { it.station == station }.minOf { (it.bill.values.sum() + 1) / 2 })
    }

    /** Where a vessel is (Appendix C.2: sent as the ordinal in [StationView.state]). */
    enum class State { EMPTY, FILLING, HEATING, COOKING, READY, DONE, BURNT, SERVED }

    /** What the vessel needs of the world. */
    interface Env {
        /** The world's randomness. */
        val random: RandomSource

        /** Whether the fire burns no fuel here (1.3), as last asked. */
        fun free(): Boolean

        /** Whether a clockwork jack turns the spit and answers its TURN calls. */
        val jack: Boolean

        /** The player a call goes to at game time [now] (the nearest within 16 blocks who is not busy), or null if nobody can take one. */
        fun callee(now: Long): UUID?
    }

    /** What happened while the vessel was brought up to date. */
    enum class Happened { CALLED, MISSED, JACKED, HEATED, READY, RIPE, DONE, BURNT, OUT }

    /** One thing that happened: [what], and for a call which one and to whom. */
    class Event(val what: Happened, val call: Call? = null, val who: UUID? = null)

    /** What adding fuel came to. */
    enum class FuelResult { OK, TOO_MUCH, FULL }

    // ---- What it holds and where it is ----

    var state = State.EMPTY
    var notch = 1
    var lit = false

    /** The fuel store, seconds of mid-heat cooking. */
    var fuel = 0.0
    var water = 0
    val items = ArrayList<ItemStack>()

    /** Real fuelled seconds of HEATING done. */
    var heated = 0.0

    /** The dish-clock, dish-seconds. */
    var p = 0.0

    /** The game time it was last brought up to; 0 is never. */
    var stamp = 0L

    /** Whether the middle of the ready window has passed (its ting has sounded). */
    var ripe = false

    // ---- The batch, fixed when the clock starts (2.4) ----

    /** What the contents were taken for; null is Gruel. */
    var kind: DishKind? = null
    var portions = 0
    var share = DoubleArray(Role.entries.size)
    var distance = 0.0

    // ---- The calls (1.4) ----

    var call: Call? = null
    var callAt = 0L
    var callWho: UUID? = null
    var callClock = 0.0
    var callGap = Calls.FIRST_SECONDS

    /** Calls issued (the jack's included), credit earned for them, and what was missed. */
    var calls = 0
    var credit = 0.0
    var sticks = 0
    var boils = 0
    var scorches = 0
    var seasons = 0
    var accents = 0
    var lastCall: Call? = null

    // ---- The serving (2.5) ----

    /** The tier, fixed at the first serve; null until then. */
    var tier: Tier? = null

    /** Portions still in the vessel and the batch's total, from the first serve. */
    var left = 0
    var total = 0

    /** The name and id the dish carries, the day and the keep, fixed at the first serve. */
    var cookName = ""
    var cookId: UUID? = null
    var day = 0L
    var keep = 0

    val who = Contributors()

    /** What happened in the last [advance], for the block entity to play and tell; it empties it. */
    val events = ArrayList<Event>(4)

    /** Whether the last [advance] was a step the cook was there for (an unattended one plays no sounds). */
    var attendedLast = true

    /** Set by anything the clients see; the block entity sends when it is set and clears it. */
    var dirty = false

    // ---- What it is ----

    /** The most items it holds. */
    val capacity: Int get() = capacity(station)

    /** The time of the dish being cooked, dish-seconds. */
    val cookTime: Double get() = kind?.time?.toDouble() ?: GRUEL_TIME

    /** Whether the dish-clock may be running: cooking, ready or overdone. */
    val cooking: Boolean get() = state == State.COOKING || state == State.READY || state == State.DONE

    /** Whether the clock has been started: heating, cooking, ready, overdone, burnt or served. */
    val started: Boolean get() = state.ordinal >= State.HEATING.ordinal

    /** Whether the contents may still be changed: nothing started yet. */
    val open: Boolean get() = state == State.EMPTY || state == State.FILLING

    /** Whether the fire is burning: lit, and with fuel or a free fire. */
    fun burning(free: Boolean): Boolean = lit && (fuel > 0.0 || free)

    /** The dish-seconds the dish-clock gains each real second now. */
    val speed: Double get() = SPEED[notch.coerceIn(0, 2)]

    /** The timing score a serve now would get (2.5): 1 at the kind's time, 0.75 at the window's edge, 0 at [SPREAD] away. */
    fun timing(): Double = max(0.0, 1.0 - abs(p - cookTime) / SPREAD)

    /** The attention (1.4): credit over calls, or a half for a vessel that never called. */
    fun attention(): Double = if (calls <= 0) 0.5 else (credit / calls).coerceIn(0.0, 1.0)

    /** Whether meat, fish or bone is over a fifth of the locked mix (a SKIM may be asked). */
    private fun meaty(): Boolean = share[Role.MEAT.ordinal] + share[Role.SEA.ordinal] > Calls.SKIM_SHARE

    // ---- Bringing it up to date ----

    /**
     * Works the vessel up from [stamp] to game time [now] by segments (the fuel runs out, a state changes, a call falls due
     * or lapses) and leaves what happened in [events]. A step longer than [UNATTENDED_TICKS] is lazy catch-up: no call is
     * issued, and one that was pending is dropped without effect (it stays counted, so attention falls).
     */
    fun advance(now: Long, env: Env) {
        if (stamp <= 0L) {
            stamp = now
            return
        }
        if (now <= stamp) {
            if (now < stamp) stamp = now
            return
        }
        val base = stamp
        val span = now - base
        val attended = span <= UNATTENDED_TICKS
        attendedLast = attended
        var left = span / 20.0
        var t = 0.0
        if (!attended && call != null) dropCall(env.random)
        var blocked = false
        var guard = 0
        while (guard++ < 64) {
            val free = env.free()
            val burning = lit && (fuel > 0.0 || free)
            val running = cooking
            val sp = speed
            val rate = when {
                !lit || free -> 0.0
                state == State.HEATING || running -> Fuel.BURN[notch.coerceIn(0, 2)]
                else -> Fuel.IDLE_BURN
            }
            var seg = left
            if (burning) {
                if (rate > 0.0) seg = min(seg, fuel / rate)
                if (state == State.HEATING) seg = min(seg, HEAT_SECONDS - heated)
                if (running) {
                    val c = cookTime
                    val target = when (state) {
                        State.COOKING -> c - WINDOW
                        State.READY -> if (ripe) c + WINDOW else c
                        else -> c + BURN_PAST
                    }
                    seg = min(seg, (target - p) / sp)
                    if (attended && call == null && !blocked) seg = min(seg, callGap - callClock)
                }
            }
            if (call != null) seg = min(seg, (callAt + Calls.LATE_TICKS - (base + t * 20.0)) / 20.0)
            if (seg < 0.0) seg = 0.0
            if (seg > left) seg = left

            if (burning) {
                if (rate > 0.0) fuel = max(0.0, fuel - rate * seg)
                if (state == State.HEATING) heated += seg
                if (running) {
                    p += sp * seg
                    if (attended && call == null) callClock += seg
                }
            }
            t += seg
            left -= seg
            val cursor = base + (t * 20.0).toLong()

            if (lit && !free && fuel <= EPS) {
                fuel = 0.0
                lit = false
                dirty = true
                events.add(Event(Happened.OUT))
            }
            if (state == State.HEATING && heated >= HEAT_SECONDS - EPS) {
                state = State.COOKING
                heated = HEAT_SECONDS
                callClock = 0.0
                callGap = Calls.FIRST_SECONDS
                dirty = true
                events.add(Event(Happened.HEATED))
            }
            if (state == State.COOKING && p >= cookTime - WINDOW - EPS) {
                state = State.READY
                dirty = true
                events.add(Event(Happened.READY))
            }
            if (state == State.READY && !ripe && p >= cookTime - EPS) {
                ripe = true
                events.add(Event(Happened.RIPE))
            }
            if (state == State.READY && p >= cookTime + WINDOW - EPS) {
                state = State.DONE
                dirty = true
                events.add(Event(Happened.DONE))
            }
            if (state == State.DONE && p >= cookTime + BURN_PAST - EPS) burn()
            if (call != null && base + t * 20.0 >= callAt + Calls.LATE_TICKS - 1e-3) miss(env.random)
            if (attended && cooking && call == null && lit && (fuel > 0.0 || free) && callClock >= callGap - EPS && !blocked) {
                if (!issue(env, cursor)) blocked = true
            }
            if (left <= EPS) break
        }
        stamp = now
    }

    /** The dish is burnt: the contents blacken (a burnt pot can only be tipped out). */
    private fun burn() {
        state = State.BURNT
        dropHold()
        call = null
        callWho = null
        dirty = true
        events.add(Event(Happened.BURNT))
    }

    /** Lets go of the player a pending call was held for (they have none to answer now). */
    fun dropHold() {
        callWho?.let { Calls.release(it) }
    }

    /** The pending call goes unanswered and uncounted (the cook was not there): no effect, no credit. */
    private fun dropCall(random: RandomSource) {
        dropHold()
        call = null
        callWho = null
        callClock = 0.0
        callGap = Calls.gap(notch, random)
        dirty = true
    }

    /** The pending call lapses unanswered (7.5 s): its missed effect, then a gap until the next. */
    private fun miss(random: RandomSource) {
        val c = call ?: return
        val to = callWho
        dropHold()
        call = null
        callWho = null
        callClock = 0.0
        callGap = Calls.gap(notch, random)
        dirty = true
        when (c) {
            Call.STICK -> sticks++
            Call.BOIL_OVER -> boils++
            Call.FLARE -> scorches++
            else -> {}
        }
        events.add(Event(Happened.MISSED, c, to))
        if (sticks >= STICKS_TO_BURN && cooking) burn()
    }

    /** Asks for something now at game time [at]: false if there was a call to make and nobody to make it to (it is tried again next step). */
    private fun issue(env: Env, at: Long): Boolean {
        val c = Calls.pick(station, kind, notch, p / cookTime, meaty(), seasons > 0, lastCall, env.random)
        if (c == null) {
            callClock = 0.0
            callGap = Calls.gap(notch, env.random)
            return true
        }
        return post(c, env, at)
    }

    /**
     * Makes the call [c] at game time [at]: a jack on a spit answers its TURN itself (and the credit counts), any other goes to
     * the player the world names, or, with nobody to take it, is not made (false) and tried again.
     */
    fun post(c: Call, env: Env, at: Long): Boolean {
        if (c == Call.TURN && station == Station.SPIT && env.jack) {
            calls++
            credit += 1.0
            lastCall = c
            callClock = 0.0
            callGap = Calls.gap(notch, env.random)
            dirty = true
            events.add(Event(Happened.JACKED, c))
            return true
        }
        val to = env.callee(at) ?: return false
        call = c
        callAt = at
        callWho = to
        calls++
        lastCall = c
        if (c == Call.SEASON) seasons++
        Calls.hold(to, at + Calls.LATE_TICKS)
        dirty = true
        events.add(Event(Happened.CALLED, c, to))
        return true
    }

    /** For `cook vessel burn`: the dish catches now. A vessel that is not cooking is left as it is. */
    fun burnNow() {
        if (state == State.HEATING || cooking) burn()
    }

    /**
     * The pending call is answered at game time [now] by [by] (named [name]): the credit it earns, 1 or a half, or 0 if
     * none was pending. The heat is changed before this when the answer eases it, so the next gap is the new notch's.
     */
    fun resolve(now: Long, by: UUID?, name: String, random: RandomSource): Double {
        if (call == null) return 0.0
        val earned = Calls.credit(now - callAt)
        credit = min(credit + earned, calls.toDouble())
        dropHold()
        if (by != null && earned > 0.0) who.answered(by, name)
        call = null
        callWho = null
        callClock = 0.0
        callGap = Calls.gap(notch, random)
        dirty = true
        return earned
    }

    // ---- What a player does to it ----

    /** Puts one [stack] in, as a single item; [by] named [name] is on the batch's books. The caller has checked the rules. */
    fun add(stack: ItemStack, by: UUID?, name: String) {
        items.add(stack.copyWithCount(1))
        if (state == State.EMPTY) state = State.FILLING
        if (by != null) who.added(by, name)
        dirty = true
    }

    /** Puts [seconds] of fuel in the store. */
    fun addFuel(seconds: Double): FuelResult {
        if (seconds > Fuel.STORE_CAP) return FuelResult.TOO_MUCH
        if (fuel + seconds > Fuel.STORE_CAP + EPS) return FuelResult.FULL
        fuel += seconds
        dirty = true
        return FuelResult.OK
    }

    /** Sets the heat to [to] (0 to 2). */
    fun setHeat(to: Int) {
        val n = to.coerceIn(0, 2)
        if (n == notch) return
        notch = n
        dirty = true
    }

    /**
     * Why the clock cannot start now, as the key of the line the cook is told, or null if it can (1.2): the fire must be
     * lit and have fuel, a pot must have water, and there must be as many items as the smallest kind needs.
     */
    fun startRefusal(free: Boolean): String? = when {
        !burning(free) -> "light_first"
        station == Station.STEWPOT && water <= 0 -> "dry"
        items.size < minItems(station) -> "too_little"
        else -> null
    }

    /** Starts the clock on what the contents were taken for: [what] (null is Gruel) in [count] portions, of mix [mix] and [off] from the ideal. */
    fun begin(what: DishKind?, count: Int, mix: DoubleArray, off: Double, now: Long) {
        kind = what
        portions = count
        share = mix.copyOf(Role.entries.size)
        distance = off
        state = State.HEATING
        heated = 0.0
        p = 0.0
        ripe = false
        call = null
        callWho = null
        callClock = 0.0
        callGap = Calls.FIRST_SECONDS
        calls = 0
        credit = 0.0
        sticks = 0
        boils = 0
        scorches = 0
        seasons = 0
        accents = 0
        lastCall = null
        tier = null
        left = 0
        total = 0
        stamp = now
        dirty = true
    }

    /**
     * Fixes the batch at the first serve: [tier] frozen, [count] portions to serve, and who is named on the dish. The
     * vessel is off the heat from now on and holds the rest.
     */
    fun freeze(tier: Tier, count: Int, name: String, id: UUID?, today: Long, keepPercent: Int) {
        this.tier = tier
        total = count
        left = count
        cookName = name
        cookId = id
        day = today
        keep = keepPercent
        state = State.SERVED
        // A pot's surface is its water; the pan, the tray and the rod show how much of the dish is left in the same eighths
        if (station != Station.STEWPOT) water = WATER_FULL
        dropHold()
        call = null
        callWho = null
        dirty = true
    }

    /** One portion is taken: how many are left, the surface (or the dish, in eighths) falling to match, and the vessel empty when the last goes. */
    fun portionTaken() {
        if (left > 0) left--
        if (total > 0) {
            val full = if (water > 0) water.coerceAtLeast(1) else WATER_FULL
            if (waterAtFreeze <= 0) waterAtFreeze = full
            water = (waterAtFreeze * left + total - 1) / total
        }
        dirty = true
        if (left <= 0) empty()
    }

    /** The surface when the first portion was taken, steps; the level falls from it as the portions go. */
    var waterAtFreeze = 0

    /** Empties the vessel: the contents, the water and the whole batch go; the fire, its fuel and the heat stay. */
    fun empty() {
        dropHold()
        items.clear()
        water = 0
        waterAtFreeze = 0
        state = State.EMPTY
        heated = 0.0
        p = 0.0
        ripe = false
        kind = null
        portions = 0
        share = DoubleArray(Role.entries.size)
        distance = 0.0
        call = null
        callWho = null
        callClock = 0.0
        callGap = Calls.FIRST_SECONDS
        calls = 0
        credit = 0.0
        sticks = 0
        boils = 0
        scorches = 0
        seasons = 0
        accents = 0
        lastCall = null
        tier = null
        left = 0
        total = 0
        cookName = ""
        cookId = null
        day = 0L
        keep = 0
        who.clear()
        dirty = true
    }

    // ---- What the clients are told ----

    /** The game time the ready window opens at, at the dish-clock's present speed; 0 when the clock is not running (not cooking, or the fire cold). */
    fun readyFrom(now: Long, free: Boolean): Long {
        if (!cooking || !burning(free)) return 0L
        return now + max(0.0, ((cookTime - WINDOW - p) / speed) * 20.0).toLong()
    }

    /** The game time the ready window closes at, likewise. */
    fun readyTo(now: Long, free: Boolean): Long {
        if (!cooking || !burning(free)) return 0L
        return now + max(0.0, ((cookTime + WINDOW - p) / speed) * 20.0).toLong()
    }

    // ---- Saved with the block entity ----

    /** Writes the whole vessel (about 150 bytes and a few more for each item). */
    fun save(out: ValueOutput) {
        out.putInt("state", state.ordinal)
        out.putInt("notch", notch)
        out.putBoolean("lit", lit)
        out.putDouble("fuel", fuel)
        out.putInt("water", water)
        out.putInt("waterAtFreeze", waterAtFreeze)
        out.putDouble("heated", heated)
        out.putDouble("p", p)
        out.putLong("stamp", stamp)
        out.putBoolean("ripe", ripe)
        if (items.isNotEmpty()) {
            val list = out.list("items", ItemStack.CODEC)
            for (stack in items) list.add(stack)
        }
        kind?.let { out.putString("kind", it.id) }
        out.putInt("portions", portions)
        if (started) {
            out.putIntArray("share", IntArray(share.size) { (share[it] * 1_000_000).toInt() })
            out.putDouble("distance", distance)
        }
        call?.let {
            out.putInt("call", it.ordinal)
            out.putLong("callAt", callAt)
            callWho?.let { id -> out.putString("callWho", id.toString()) }
        }
        out.putDouble("callClock", callClock)
        out.putDouble("callGap", callGap)
        out.putInt("calls", calls)
        out.putDouble("credit", credit)
        out.putInt("sticks", sticks)
        out.putInt("boils", boils)
        out.putInt("scorches", scorches)
        out.putInt("seasons", seasons)
        out.putInt("accents", accents)
        lastCall?.let { out.putInt("lastCall", it.ordinal) }
        tier?.let {
            out.putInt("tier", it.ordinal)
            out.putInt("left", left)
            out.putInt("total", total)
            out.putString("cookName", cookName)
            cookId?.let { id -> out.putString("cookId", id.toString()) }
            out.putLong("day", day)
            out.putInt("keep", keep)
        }
        who.save(out)
    }

    /** Reads what [save] wrote; every field is optional and a missing one reads as a cold, empty vessel. */
    fun load(input: ValueInput) {
        state = State.entries.getOrElse(input.getIntOr("state", 0)) { State.EMPTY }
        notch = input.getIntOr("notch", 1).coerceIn(0, 2)
        lit = input.getBooleanOr("lit", false)
        fuel = input.getDoubleOr("fuel", 0.0).coerceIn(0.0, Fuel.STORE_CAP)
        water = input.getIntOr("water", 0).coerceIn(0, WATER_FULL)
        waterAtFreeze = input.getIntOr("waterAtFreeze", 0).coerceIn(0, WATER_FULL)
        heated = input.getDoubleOr("heated", 0.0).coerceIn(0.0, HEAT_SECONDS)
        p = input.getDoubleOr("p", 0.0).coerceAtLeast(0.0)
        stamp = input.getLongOr("stamp", 0L)
        ripe = input.getBooleanOr("ripe", false)
        items.clear()
        for (stack in input.listOrEmpty("items", ItemStack.CODEC)) {
            if (!stack.isEmpty && items.size < capacity) items.add(stack)
        }
        kind = input.getString("kind").map { DishKind.byId(it) }.orElse(null)
        portions = input.getIntOr("portions", 0).coerceAtLeast(0)
        val mix = input.getIntArray("share").orElse(IntArray(0))
        share = DoubleArray(Role.entries.size) { if (it < mix.size) mix[it] / 1_000_000.0 else 0.0 }
        distance = input.getDoubleOr("distance", 0.0)
        val saved = input.getInt("call").map { Call.entries.getOrNull(it) }.orElse(null)
        call = saved
        callAt = input.getLongOr("callAt", 0L)
        callWho = readId(input.getStringOr("callWho", ""))
        callClock = input.getDoubleOr("callClock", 0.0).coerceAtLeast(0.0)
        callGap = input.getDoubleOr("callGap", Calls.FIRST_SECONDS).coerceAtLeast(0.0)
        calls = input.getIntOr("calls", 0).coerceAtLeast(0)
        credit = input.getDoubleOr("credit", 0.0).coerceIn(0.0, calls.toDouble())
        sticks = input.getIntOr("sticks", 0).coerceAtLeast(0)
        boils = input.getIntOr("boils", 0).coerceAtLeast(0)
        scorches = input.getIntOr("scorches", 0).coerceAtLeast(0)
        seasons = input.getIntOr("seasons", 0).coerceAtLeast(0)
        accents = input.getIntOr("accents", 0).coerceAtLeast(0)
        lastCall = input.getInt("lastCall").map { Call.entries.getOrNull(it) }.orElse(null)
        val frozen = input.getInt("tier")
        if (frozen.isPresent) {
            tier = Tier.of(frozen.get())
            left = input.getIntOr("left", 0).coerceAtLeast(0)
            total = input.getIntOr("total", left).coerceAtLeast(left)
            cookName = input.getStringOr("cookName", "")
            cookId = readId(input.getStringOr("cookId", ""))
            day = input.getLongOr("day", 0L)
            keep = input.getIntOr("keep", 0).coerceIn(0, 100)
        } else {
            tier = null
            left = 0
            total = 0
            cookName = ""
            cookId = null
            day = 0L
            keep = 0
        }
        who.load(input)
        // A call that was pending when the world was saved is not held against anyone: whoever is called next is asked afresh
        if (call != null && callWho == null) call = null
        dirty = false
    }

    private fun readId(text: String): UUID? = if (text.isEmpty()) null else try {
        UUID.fromString(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
