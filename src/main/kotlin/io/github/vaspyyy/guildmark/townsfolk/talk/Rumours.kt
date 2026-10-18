package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Trait
import net.minecraft.core.BlockPos
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The rumours a capital holds (living-world/design-voices.md section 4), as [TownTalkData] keeps them: starting one
 * (or heating the same one again), how hot it is at any hour, who hears it and in which version, and the limits that
 * let the coldest go.
 *
 * A rumour's heat is kept with the hour it was last worked out ([Story.at]) and cools at each dawn after it ([cool]),
 * so a capital nobody steps for a while, or with the rumours switched off, finds them as cool as they'd be by now.
 * Below [OLD] a rumour is old news, not passed on; below [GONE] it's forgotten and let go.
 *
 * The kinds, and what their [Rumour.args] are (Speech arguments: "=Name" a name, a bare lang key, a number). The
 * player it's about is [Rumour.about]; the resident it's about, [Rumour.resident]:
 * - `deed.town` [=player, monster's name key]: a monster killed in town in sight of residents (0.7, small).
 * - `deed.saved` [=player, =resident, monster's name key]: the monster was after that resident (0.8, big).
 * - `deed.rescue` [=player, =resident]: a captive brought home (0.9, big); from the guild's news [=resident, =village].
 * - `slight.assault` [=player, =resident]: a resident struck (0.8, small).
 * - `slight.murder` [=player, =resident]: a resident killed by a player (1.0, big, goes to the other capitals).
 * - `slight.theft` [=player, =resident]: caught lifting a purse (0.7, small).
 * - `death` [=resident, their trade's name key, and what killed them if anything did: its name key]: (0.8, small).
 * - `newcomer` [=family, =given name, their trade's name key]: someone new taken in (0.4, small).
 * - `festival.won` [=player, festival's name key]: (0.7, small); from the guild's news [=player, festival key, =town].
 * - `homestead.raised` [=player, the tier's name key]: a homestead finished near the capital (0.4, small).
 * - `market.event.<event>` [the good's key, if it falls on one] and `market.over.<event>` [the same]: the market's
 *   trouble or luck starting, or the trouble over (0.6, small); `market.hungry` []: the town went hungry (0.6, small).
 * - `road.waylaid` [the band's name key, the good's key, if any were taken], `road.clear` [], `road.relief` []: the
 *   wagons (M4, 0.8, small); `deed.escort` [=player]: they saw a wagon in (0.6, small).
 * - `salvage.brought` / `salvage.kept` [=player] (M4, 0.5, small); `order.filled` / `order.lapsed` [=player] (M3, 0.3).
 * - From the guild's news, as it's sorted for the crier: `deed.dragon`, `deed.king`, `deed.sea` (1.0, lore);
 *   `deed.beast`, `deed.lair`, `deed.siege`, `deed.band_broken` (0.8, big); `siege.lost` (0.8, big, bad);
 *   `deed.band` (0.6); `outlaws.<crime>` and `outlaws.taken` (0.6, bad); `deed.rank` (0.4); and `guild.<news>` for
 *   the rest (0.4). Their args are the news' own, a name as "=Name", a lang key bare.
 */
object Rumours {
    /** How a rumour began ([Story.by]): in the town itself, the guild's news, the market's news, or from another capital. */
    const val TOWN = 0
    const val GUILD = 1
    const val MARKET = 2
    const val AFAR = 3

    /** Whom a resident heard it from ([Heard.from]) besides another resident: they saw it themselves, the crier, from afar. */
    const val SAW = 0
    const val CRIER = -1
    const val FAR = -2

    /** Below this heat a rumour is old news, not passed on; below this, forgotten. */
    const val OLD = 0.1f
    const val GONE = 0.03f

    /** The most rumours a capital keeps going (and lore besides), and the most one resident has heard. */
    const val LIVE = 48
    const val LORE = 8
    const val HEARD = 12

    /** Hours within which the same thing again heats the rumour already going rather than starting another. */
    const val SAME = 72L

    /** The guild's news goes round at this share of its heat until the crier has cried it. */
    const val HELD = 0.6f

    /** A rumour born in town at this heat or hotter, or of the region's scope, goes to the other capitals too. */
    const val CARRIES = 0.8f

    /** How far another capital may be for word to reach it, the blocks it travels a day, and the share of heat it keeps. */
    const val RANGE = 1500.0
    const val DAILY = 400.0
    const val ARRIVES = 0.6f

    /** Visitors bring word from another capital at this share of its heat. */
    const val VISITORS = 0.4f

    /** The truest and the tallest versions. */
    const val TRUE = 0
    const val TALL = 3

    fun enabled(): Boolean = try {
        GuildmarkConfig.TOWNSFOLK_RUMOURS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** The dawns between hour [from] and hour [to] (negative if [to] is earlier). */
    fun dawns(from: Long, to: Long): Int = (Math.floorDiv(to, 24L) - Math.floorDiv(from, 24L)).toInt()

    /** [heat] as it was at hour [from], cooled (or before it, warmed) to hour [to] as [fade] has it. */
    fun cool(heat: Float, fade: Fade, from: Long, to: Long): Float {
        val n = dawns(from, to)
        return if (n == 0) heat else (heat * fade.daily.pow(n)).coerceIn(0f, 1f)
    }

    /** How hot [s] is at [hour]. */
    fun heat(s: Story, hour: Long): Float = cool(s.rumour.heat, s.rumour.fade, s.at, hour)

    /** Works [s]'s heat out to [hour] (no earlier than it was), so the rumour handed out says how hot it is now. */
    fun settle(s: Story, hour: Long) {
        if (hour <= s.at) return
        val n = dawns(s.at, hour)
        if (n > 0) {
            val f = s.rumour.fade.daily.pow(n)
            s.rumour.heat *= f
            s.full *= f
        }
        s.at = hour
    }

    /** The same thing going round at [entry] already, if it is: the same kind, about the same people, from the last [SAME] hours. */
    fun same(entry: TownTalkData.Entry, kind: String, about: UUID?, resident: Int, args: List<String>, hour: Long): Story? =
        entry.rumours.values.firstOrNull { s ->
            val r = s.rumour
            r.kind == kind && r.about == about && r.resident == resident && r.args.firstOrNull() == args.firstOrNull() &&
                hour - r.born in 0..SAME && heat(s, hour) >= GONE
        }

    /**
     * Starts a rumour at [entry] at [hour] ([by]: how it began; the guild's news is held at [HELD] of its heat until
     * cried), or heats the same one if it's going round already. The story, and whether it's new.
     */
    fun start(
        entry: TownTalkData.Entry, hour: Long, kind: String, about: UUID?, resident: Int, args: List<String>, heat: Float,
        fade: Fade, scope: Scope, tone: Int, origin: BlockPos?, by: Int, from: Long = 0L, source: Int = -1,
    ): Pair<Story, Boolean> {
        val h = heat.coerceIn(0f, 1f)
        same(entry, kind, about, resident, args, hour)?.let { old ->
            settle(old, hour)
            if (old.full > 0f) {
                old.full = min(1f, max(old.full, h) + 0.05f * h)
                old.rumour.heat = min(1f, max(old.rumour.heat, h * HELD) + 0.05f * h * HELD)
            } else {
                old.rumour.heat = min(1f, max(old.rumour.heat, h) + 0.05f * h)
            }
            return old to false
        }
        val held = by == GUILD
        val id = entry.next++
        val rumour = Rumour(id, kind, about, resident, args, hour, if (held) h * HELD else h, fade, scope, tone, origin)
        val story = Story(rumour, hour, if (held) h else 0f, false, by, from, source)
        entry.rumours[id] = story
        trim(entry, hour)
        return story to true
    }

    /** Whether [s], just begun in town at [heat], goes on to the other capitals. */
    fun carries(s: Story, heat: Float): Boolean = s.by == TOWN && (s.rumour.scope == Scope.REGION || heat >= CARRIES)

    /** The days word takes to go [distance] blocks. */
    fun travel(distance: Double): Int = max(1, Math.ceil(distance / DAILY).toInt())

    /** [s] has been cried: the guild's news goes round at its full heat from now on. */
    fun cried(s: Story, hour: Long) {
        settle(s, hour)
        if (s.full > 0f) {
            s.rumour.heat = max(s.rumour.heat, s.full)
            s.full = 0f
        }
    }

    // ---- Hearing ----

    /** Whether one heard it at first hand: saw it, or had it from the crier. */
    fun firstHand(venue: Venue): Boolean = venue == Venue.SEEN || venue == Venue.CRIER

    /**
     * [mind] hears [s] (version [warp], from [from], at [venue], at [hour]): kept if it's new to them, or truer than
     * what they had (or as true and at first hand). With [HEARD] rumours already, the coldest makes room, if it's
     * colder than this one. True if anything changed.
     */
    fun hear(entry: TownTalkData.Entry, mind: Mind, s: Story, hour: Long, from: Int, venue: Venue, warp: Int): Boolean {
        val id = s.rumour.id
        val w = warp.coerceIn(TRUE, TALL)
        val old = mind.heard[id]
        if (old != null) {
            val truer = w < old.warp || w == old.warp && firstHand(venue) && !firstHand(old.venue)
            if (!truer) return false
            mind.heard[id] = Heard(id, hour, from, venue, w, old.told)
            return true
        }
        if (mind.heard.size >= HEARD) {
            val heat = heat(s, hour)
            var coldest: Int? = null
            var least = Float.MAX_VALUE
            for (k in mind.heard.keys) {
                val v = entry.rumours[k]?.let { heat(it, hour) } ?: -1f
                if (v < least) {
                    least = v
                    coldest = k
                }
            }
            if (coldest == null || least >= heat) return false
            mind.heard.remove(coldest)
        }
        mind.heard[id] = Heard(id, hour, from, venue, w, 0)
        return true
    }

    /** The chance a telling by [teller] changes [rumour] by a step: the chatty and idle embroider, the diligent and shy less. */
    fun warpChance(teller: Resident?, rumour: Rumour): Double {
        if (teller == null) return 0.08
        var chance = 0.08
        if (teller.has(Trait.CHATTY)) chance += 0.07
        if (teller.has(Trait.IDLE)) chance += 0.05
        if (teller.has(Trait.DILIGENT)) chance -= 0.04
        if (teller.has(Trait.SHY)) chance -= 0.03
        // The grumpy make bad news worse in the telling, the cheerful good news better
        if (teller.has(Trait.GRUMPY) && rumour.tone < 0) chance += 0.05
        if (teller.has(Trait.CHEERFUL) && rumour.tone > 0) chance += 0.05
        return chance.coerceAtLeast(0.0)
    }

    /** The version [teller], who had it as version [warp], tells of [rumour]. */
    fun retold(teller: Resident?, rumour: Rumour, warp: Int, random: Random): Int =
        if (warp < TALL && random.nextDouble() < warpChance(teller, rumour)) warp + 1 else warp

    // ---- Limits ----

    /** Lets [s] go: nobody has heard it any more, and no player either. */
    fun forget(entry: TownTalkData.Entry, id: Int) {
        entry.rumours.remove(id)
        for (m in entry.minds.values) m.heard.remove(id)
        for (p in entry.players.values) p.remove(id)
    }

    /** Keeps [entry] to [LIVE] rumours and [LORE] of lore besides, the coldest let go. True if any were. */
    fun trim(entry: TownTalkData.Entry, hour: Long): Boolean {
        if (entry.rumours.size <= LIVE) return false
        val (lore, rest) = entry.rumours.values.partition { it.rumour.fade == Fade.LORE }
        val sortedLore = lore.sortedByDescending { heat(it, hour) }
        val live = (rest + sortedLore.drop(LORE)).sortedByDescending { heat(it, hour) }
        if (live.size <= LIVE) return false
        for (s in live.drop(LIVE)) forget(entry, s.rumour.id)
        return true
    }

    /** Lets go of every rumour at [entry] that's forgotten by [hour]. True if any was. */
    fun fade(entry: TownTalkData.Entry, hour: Long): Boolean {
        val gone = entry.rumours.values.filter { heat(it, hour) < GONE }.map { it.rumour.id }
        for (id in gone) forget(entry, id)
        return gone.isNotEmpty()
    }
}
