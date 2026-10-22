package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Feasted
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import java.util.WeakHashMap
import kotlin.random.Random

/**
 * What the capitals' people remember of the players and what goes round among them (living-world/design-voices.md
 * sections 3 and 4): each resident's acquaintance with each player, the rumours a capital holds, who has heard which
 * and in which version, and what befell each resident. The talk, the topic sources and the crier read and add to it
 * only through what follows.
 *
 * It's kept in [TownTalkData] (`guildmark:town_talk`), worked out by [Memory] (what's remembered, the opinion and
 * familiarity) and [Rumours] (what's said, how hot, in which version), spread hour by hour by [Spread], started by
 * what [Witness] sees and hears of, and cried by the crier as the talk of the town ([TownCry]). With
 * [Memory.enabled] off everyone is a stranger and nothing new is remembered of the players; with [Rumours.enabled] off
 * nothing goes from mouth to mouth and the townsfolk know only what they saw and what the crier cried. Neither drops
 * anything from the save.
 *
 * Townsfolk with no record (passers-by, visitors: a [Resident] with id -1) keep what passed with players only for the
 * day, in memory, and know what more than half the town knows.
 */
object TownTalk {
    /** What townsfolk with no record remember of the day: kept in memory, with the day it's for. */
    private val passers = WeakHashMap<Resident, Pair<Int, Mind>>()

    /** Picks for tellings seen on screen. */
    private val random = Random(System.nanoTime())

    /** Sets memory and rumours going: the hourly step, the listeners and the commands. Called once at setup. */
    fun register() {
        Spread.register()
        Witness.register()
        TalkCommand.register()
    }

    /** How well [r] knows [player] (a stranger for anyone with no resident record). */
    fun familiarity(level: ServerLevel, r: Resident?, player: ServerPlayer): Familiarity {
        if (r == null || !Memory.enabled()) return Familiarity.STRANGER
        return weigh(level, r, player.uuid).familiarity
    }

    /** What [r] remembers of [player], worked out to now, or null if they've never met nor seen them. */
    fun knows(level: ServerLevel, r: Resident, player: UUID): Acquaintance? {
        if (!Memory.enabled()) return null
        val w = weigh(level, r, player)
        val a = w.acq ?: return null
        return Memory.view(a, Memory.day(level), w.named, w.opinion)
    }

    /**
     * Whether [r] knows [player]'s name: they were introduced (and haven't forgotten), a rumour that named them reached
     * [r] as it happened or grown a little (version 0 or 1), or the player is a hero of the capital's board.
     */
    fun name(level: ServerLevel, r: Resident, player: UUID): Boolean = Memory.enabled() && weigh(level, r, player).named

    /** [r] and [player] have talked or dealt today. */
    fun met(level: ServerLevel, r: Resident, player: ServerPlayer) {
        val a = acq(level, r, player.uuid) ?: return
        Memory.met(a, Memory.day(level))
        changed(level, r)
    }

    /** [player] told [r] their name. */
    fun named(level: ServerLevel, r: Resident, player: ServerPlayer) {
        val a = acq(level, r, player.uuid) ?: return
        Memory.named(a, Memory.day(level))
        changed(level, r)
    }

    /** [player] dealt at [r]'s stall for [marks] Marks; [short]: in what the town was short of, a favour too. */
    fun traded(level: ServerLevel, r: Resident, player: ServerPlayer, marks: Int, short: Boolean) {
        val a = acq(level, r, player.uuid) ?: return
        val day = Memory.day(level)
        Memory.traded(a, day, marks)
        if (short) Memory.favour(a, day, Favour.SOLD_SHORT)
        changed(level, r)
    }

    /** [player] did [r] a good turn. */
    fun favour(level: ServerLevel, r: Resident, player: UUID, favour: Favour) {
        val a = acq(level, r, player) ?: return
        Memory.favour(a, Memory.day(level), favour)
        changed(level, r)
    }

    /** [player] wronged [r]. */
    fun slight(level: ServerLevel, r: Resident, player: UUID, slight: Slight) {
        val a = acq(level, r, player) ?: return
        if (Memory.slight(a, Memory.day(level), slight)) changed(level, r)
    }

    // ---- The works and the alms table (M6) ----

    /**
     * [r] laboured on works [works] today: a building day, which stays with them for a month as their own pride in it
     * (the labourer's line, "I laid stone on that."). A day counts once, so it's safe to call each time they set to
     * work. Part B calls it from Labour.kt as a labourer takes up the day's work at the site; Witness records the
     * day's crew as the works begins, rises a stage and is finished.
     */
    fun laboured(level: ServerLevel, r: Resident, works: Int) {
        if (r.id < 0) return
        val mind = mind(level, r, true) ?: return
        val day = Memory.day(level)
        if (mind.felt.any { it.experience == Experience.LABOURED && it.about == works && it.day == day }) return
        Memory.felt(mind, Experience.LABOURED, day, works)
        changed(level, r)
    }

    /**
     * [r] ate at the alms table on a day [player] gave there: a small favour ([Favour.FED]), counted once a day however
     * often either happens, and only while the favour [r] feels for them is small ([Memory.DAILY_CAP]). Part D2's
     * alms table calls it for each who eats there on a day the player gave, and for each who's eaten there that day
     * when the player gives.
     */
    fun fed(level: ServerLevel, r: Resident, player: UUID) = once(level, r, player, Favour.FED)

    /** [player] brought materials to the yard of the works [r] labours on: the crew's thanks ([Favour.BROUGHT]), once a day (Witness hears it). */
    fun brought(level: ServerLevel, r: Resident, player: UUID) = once(level, r, player, Favour.BROUGHT)

    /**
     * [r] relished a dish of [tier] (of [kind], if it is told) that [cook] cooked (Hearthcraft, living-world/cook/design.md
     * 8.2): Sound earns [Favour.RELISHED], Fine and Hallmarked [Favour.RELISHED_FINE], Plain and Spoilt nothing, once a day and
     * only while the favour felt is small, as the other daily favours are; and a Sound dish or better stays with them for a
     * week as a feast ([Experience.FEASTED], kept as [Feasted] says), which has them say "that stew of yours" to the cook and
     * "best supper in weeks" to anyone. The ledger calls it for a sample of the diners at a meal hour, and the back door for
     * the keeper. With Kitchen Talk or the townsfolk's memory off it remembers nothing.
     */
    fun relished(level: ServerLevel, r: Resident, cook: UUID, tier: Tier, kind: DishKind? = null) {
        if (!Cooking.talk() || !Memory.enabled()) return
        val favour = when (tier) {
            Tier.SOUND -> Favour.RELISHED
            Tier.FINE, Tier.HALLMARKED -> Favour.RELISHED_FINE
            else -> return
        }
        once(level, r, cook, favour)
        val mind = mind(level, r, true) ?: return
        if (Feasted.keep(mind, Memory.day(level), Feasted.about(cook, kind))) changed(level, r)
    }

    private fun once(level: ServerLevel, r: Resident, player: UUID, favour: Favour) {
        val a = acq(level, r, player) ?: return
        val day = Memory.day(level)
        if (Memory.favourOnce(a, day, favour, day.toLong())) changed(level, r)
    }

    /**
     * [mind] has just heard [s] for the first time ([Rumours.hear] calls it): a works paid for or raised is the
     * town's thanks to its patron and anyone who gave a quarter of its price ([Favour.FUNDED]), once for each works,
     * whichever of its rumours they hear first. Never for a stall: that's its owner's own. A tale's rumour (kind
     * `tale.<template>.<word>`) is left to the tales' memory, which thanks the one a good ending is about ([Favour.TALE_HEARD]).
     */
    internal fun heard(entry: TownTalkData.Entry, mind: Mind, s: Story) {
        val kind = s.rumour.kind
        if (kind.startsWith("tale.")) {
            io.github.vaspyyy.guildmark.tales.TaleMemory.heard(entry, mind, s)
            return
        }
        if (kind != Rumours.WORKS_FUNDED && kind != Rumours.WORKS_RAISED || mind.id < 0) return
        if (!Memory.enabled() || !Works.enabled()) return
        val id = s.rumour.args.firstOrNull()?.toIntOrNull() ?: return
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return
        val w = Works.get(level, id)?.takeIf { it.capital == entry.capital && it.kind != WorksKind.STALL } ?: return
        val day = Memory.day(level)
        var thanked = false
        for (giver in givers(w)) {
            val a = Memory.acq(mind, giver, day, true) ?: continue
            if (Memory.favourOnce(a, day, Favour.FUNDED, id.toLong())) thanked = true
        }
        if (thanked) TownTalkData.get(level).changed()
    }

    /** Those the town thanks for [w]: its patron, and anyone who gave a quarter of its price or more. */
    fun givers(w: WorksView): List<UUID> =
        (listOfNotNull(w.patron) + w.pledges.filter { it.marks * 4 >= w.price }.map { it.player }).distinct()

    /**
     * Something happened at [at]: the residents within [radius] who could see it know it as a rumour (of [kind], about
     * player [about] and resident [resident], with its facts [args] as Speech arguments), which is returned; null if
     * nobody saw it or it heated an old rumour of the same thing.
     */
    fun witness(
        level: ServerLevel, at: BlockPos, kind: String, about: UUID?, resident: Int, args: List<String>,
        heat: Float, fade: Fade, scope: Scope, tone: Int, radius: Double = 16.0,
    ): Rumour? {
        val seen = Witness.seen(level, Vec3.atCenterOf(at), null, radius, true)
        val capital = Witness.capitalOf(seen) ?: return null
        val (s, new) = begin(level, capital, kind, about, resident, args, heat, fade, scope, tone, at, Rumours.TOWN, seen.map { it.second.id to Venue.SEEN })
        return if (new) s.rumour else null
    }

    /** A rumour a system starts at [capital], known first by [knowers] (resident ids). */
    fun start(
        level: ServerLevel, capital: BlockPos, kind: String, about: UUID?, resident: Int, args: List<String>,
        heat: Float, fade: Fade, scope: Scope, tone: Int, knowers: Collection<Int>,
    ): Rumour = begin(level, capital, kind, about, resident, args, heat, fade, scope, tone, null, Rumours.TOWN, knowers.map { it to Venue.SEEN }).first.rumour

    /**
     * Starts a rumour at [capital] (or heats the same one going round already), begun [by] the town, the guild's news,
     * the market's or word from afar: [knowers] (resident ids of that capital) know it as their venue has it, those
     * who saw it ([Venue.SEEN]) remember the player's deed, and word that carries is sent on to the other capitals.
     * The story, and whether it's new.
     */
    internal fun begin(
        level: ServerLevel, capital: BlockPos, kind: String, about: UUID?, resident: Int, args: List<String>,
        heat: Float, fade: Fade, scope: Scope, tone: Int, origin: BlockPos?, by: Int, knowers: Collection<Pair<Int, Venue>>,
    ): Pair<Story, Boolean> {
        val data = TownTalkData.get(level)
        val entry = entry(level, capital, true)!!
        val hour = Spread.hourOf(level)
        val (s, new) = Rumours.start(entry, hour, kind, about, resident, args, heat, fade, scope, tone, origin, by)
        val day = Memory.day(level)
        val residents = Residents.get(level)
        for ((id, venue) in knowers) {
            val r = residents[id]?.takeIf { it.capital == capital } ?: continue
            val mind = entry.mind(r.id, true)!!
            Rumours.hear(entry, mind, s, hour, Rumours.SAW, venue, Rumours.TRUE)
            if (venue == Venue.SEEN && about != null && tone != 0 && Memory.enabled()) {
                Memory.acq(mind, about, day, true)?.let { Memory.saw(it, day, kind, tone, fade != Fade.SMALL, s.rumour.id) }
            }
        }
        if (new && Rumours.carries(s, heat)) Spread.carry(level, entry, s)
        data.changed()
        return s to new
    }

    /** What [r] has heard, hottest first. */
    fun known(level: ServerLevel, r: Resident): List<Known> {
        val entry = TownTalkData.get(level)[r.capital] ?: return listOf()
        val hour = Spread.hourOf(level)
        if (r.id < 0) {
            return common(level, entry, hour).map { s ->
                Rumours.settle(s, hour)
                Known(s.rumour, Heard(s.rumour.id, s.rumour.born, Rumours.FAR, Venue.STREET, Rumours.TRUE, 0))
            }
        }
        val mind = entry.minds[r.id] ?: return listOf()
        val all = Rumours.enabled()
        val out = ArrayList<Known>()
        for (h in mind.heard.values) {
            val s = entry.rumours[h.rumour] ?: continue
            if (s.rumour.born > hour || !all && !Rumours.firstHand(h.venue)) continue
            Rumours.settle(s, hour)
            if (s.rumour.heat < Rumours.GONE) continue
            out.add(Known(s.rumour, h))
        }
        out.sortByDescending { it.rumour.heat }
        return out
    }

    /** [from] told [to] rumour [rumour] in a chat seen on screen. */
    fun tell(level: ServerLevel, from: Resident, to: Resident, rumour: Int) {
        if (!Rumours.enabled() || to.id < 0 || from.capital != to.capital) return
        val data = TownTalkData.get(level)
        val entry = data[from.capital] ?: return
        val s = entry.rumours[rumour] ?: return
        val hour = Spread.hourOf(level)
        val theirs = if (from.id >= 0) entry.minds[from.id]?.heard?.get(rumour) else null
        if (from.id >= 0 && theirs == null) return
        val warp = Rumours.retold(from, s.rumour, theirs?.warp ?: Rumours.TRUE, random)
        val teller = if (from.id >= 0) from.id else Rumours.FAR
        if (Rumours.hear(entry, entry.mind(to.id, true)!!, s, hour, teller, venue(level, from), warp)) {
            theirs?.let { it.told++ }
            data.changed()
        }
    }

    /** [from] (null: the crier, or someone with no record) told [player] rumour [rumour]. */
    fun told(level: ServerLevel, from: Resident?, player: ServerPlayer, rumour: Int) {
        val capital = from?.capital ?: Furnish.capitalAt(level, player.blockPosition())?.center ?: return
        val data = TownTalkData.get(level)
        val entry = data[capital] ?: return
        if (!entry.rumours.containsKey(rumour)) return
        val theirs = if (from != null && from.id >= 0) entry.minds[from.id]?.heard?.get(rumour) else null
        val warp = theirs?.warp ?: Rumours.TRUE
        val heard = entry.players.getOrPut(player.uuid) { HashMap() }
        val old = heard[rumour]
        if (old == null || warp < old) heard[rumour] = warp
        theirs?.let { it.told++ }
        data.changed()
    }

    /** The rumours [player] has heard at [capital]: each rumour's id to the version they heard. */
    fun heard(level: ServerLevel, capital: BlockPos, player: UUID): Map<Int, Int> {
        val entry = TownTalkData.get(level)[capital] ?: return mapOf()
        return entry.players[player]?.filterKeys { it in entry.rumours } ?: mapOf()
    }

    /** What has befallen [r] lately, newest first. */
    fun felt(level: ServerLevel, r: Resident): List<Felt> = Memory.felt(mind(level, r, false), Memory.day(level))

    /** The capital's hottest rumour fit for the crier and the board, if one is. */
    fun talkOfTheTown(level: ServerLevel, capital: BlockPos): Rumour? {
        if (!Rumours.enabled()) return null
        val entry = TownTalkData.get(level)[capital] ?: return null
        val hour = Spread.hourOf(level)
        val s = TownCry.pick(entry, Spread.view(capital), hour) ?: return null
        Rumours.settle(s, hour)
        return s.rumour
    }

    // ---- Within ----

    /** What [r] makes of a player now: what they keep of them, the opinion, the familiarity, and whether they know the name. */
    private class Weighed(val acq: Acq?, val opinion: Int, val familiarity: Familiarity, val named: Boolean)

    private fun weigh(level: ServerLevel, r: Resident, player: UUID): Weighed {
        val day = Memory.day(level)
        val hour = Spread.hourOf(level)
        val mind = mind(level, r, false)
        val a = mind?.known?.get(player)
        val tier = standing(level, r.capital, player)
        val about = about(level, r, mind, player, hour)
        val deeds = Memory.deedScore(a?.let { Memory.deeds(it, day) }.orEmpty(), about.map { it.first })
        val opinion = Memory.opinion(r, a, day, deeds, tier)
        val heardOf = about.isNotEmpty() || fromStanding(r, player, tier)
        val named = Memory.hasName(a, day) || about.any { it.second <= 1 } || tier == StandingTier.HERO
        return Weighed(a, opinion, Memory.familiarity(opinion, a, heardOf), named)
    }

    /** The rumours about [player] that [r] knows (and in which version), as [Rumours.enabled] lets them know. */
    private fun about(level: ServerLevel, r: Resident, mind: Mind?, player: UUID, hour: Long): List<Pair<Rumour, Int>> {
        val entry = TownTalkData.get(level)[r.capital] ?: return listOf()
        if (r.id < 0) return common(level, entry, hour).filter { it.rumour.about == player }.map { it.rumour to Rumours.TRUE }
        val heard = mind?.heard ?: return listOf()
        val all = Rumours.enabled()
        val out = ArrayList<Pair<Rumour, Int>>()
        for (h in heard.values) {
            val s = entry.rumours[h.rumour] ?: continue
            if (s.rumour.about != player || s.rumour.born > hour || !all && !Rumours.firstHand(h.venue)) continue
            if (Rumours.heat(s, hour) < Rumours.GONE) continue
            out.add(s.rumour to h.warp)
        }
        return out
    }

    /**
     * Whether [r] has heard of [player] from their standing alone (old worlds, and fame): at Known a fifth of the town,
     * at Trusted two fifths, at Honored seven tenths, at Hero all of it; someone with no record knows what more than
     * half the town does.
     */
    private fun fromStanding(r: Resident, player: UUID, tier: StandingTier?): Boolean {
        val tenths = when (tier) {
            StandingTier.KNOWN -> 2
            StandingTier.TRUSTED -> 4
            StandingTier.HONORED -> 7
            StandingTier.HERO -> 10
            else -> 0
        }
        if (tenths == 0) return false
        if (r.id < 0) return tenths > 5
        val hash = Spread.mix(r.id * -0x61c8864680b583ebL xor player.mostSignificantBits xor player.leastSignificantBits * 31L)
        return Math.floorMod(hash, 10L) < tenths
    }

    /** What [r] keeps of [player] just as it's kept, unworked (for the test commands), or null. */
    internal fun kept(level: ServerLevel, r: Resident, player: UUID): Acq? = mind(level, r, false)?.known?.get(player)

    /** [player]'s standing at [capital]'s board: read there if it's loaded, else as last seen. Null if never known. */
    fun standing(level: ServerLevel, capital: BlockPos, player: UUID): StandingTier? {
        val view = Spread.view(capital)
        val board = view.board
        if (board != null) {
            Standing.board(level.server.overworld(), board)?.let { b ->
                val points = b.standingOf(player)
                view.standing[player] = points
                return StandingTier.of(points)
            }
        }
        return view.standing[player]?.let { StandingTier.of(it) }
    }

    /** What more than half [entry]'s town has heard, as of [hour] (worked out once an hour): what anyone in the street knows. */
    private fun common(level: ServerLevel, entry: TownTalkData.Entry, hour: Long): List<Story> {
        val view = Spread.view(entry.capital)
        if (view.commonHour == hour) return view.common.mapNotNull { entry.rumours[it] }
        val residents = Residents.get(level)
        val living = residents.of(entry.pos).count { it.alive }
        val all = Rumours.enabled()
        val counts = HashMap<Int, Int>()
        for ((id, m) in entry.minds) {
            if (residents[id]?.alive != true) continue
            for (h in m.heard.values) if (all || Rumours.firstHand(h.venue)) counts.merge(h.rumour, 1, Int::plus)
        }
        val common = counts.filter { (id, n) ->
            val s = entry.rumours[id]
            s != null && n * 2 > living && s.rumour.born <= hour && Rumours.heat(s, hour) >= Rumours.GONE
        }.keys.sortedByDescending { id -> entry.rumours[id]?.let { Rumours.heat(it, hour) } ?: 0f }
        view.commonHour = hour
        view.common = common
        return common.mapNotNull { entry.rumours[it] }
    }

    /** The place [r] is telling something in now, by what their body is at (in the street if it isn't about). */
    private fun venue(level: ServerLevel, r: Resident): Venue {
        val body = r.uuid?.let { level.getEntity(it) } as? Townsperson
        val doing = body?.life?.stretch?.doing ?: return Venue.STREET
        return when (doing) {
            Doing.BREAKFAST, Doing.LUNCH, Doing.SUPPER, Doing.EVENING, Doing.RISE, Doing.BED -> Venue.HOUSEHOLD
            Doing.VISIT -> Venue.VISIT
            Doing.TAVERN -> Venue.TAVERN
            Doing.WATER -> Venue.WELL
            Doing.MARKET, Doing.CRY -> Venue.MARKET
            Doing.WORSHIP -> Venue.TEMPLE
            Doing.STROLL -> Venue.STROLL
            Doing.WORK, Doing.WATCH, Doing.CART, Doing.LESSONS -> when (r.role) {
                TownRole.INNKEEPER -> Venue.TAVERN
                TownRole.MERCHANT, TownRole.BARD -> Venue.MARKET
                TownRole.PRIEST -> Venue.TEMPLE
                else -> Venue.WORK
            }
            else -> Venue.STREET
        }
    }

    /** [capital]'s talk, made now if [make] and it has none yet. */
    private fun entry(level: ServerLevel, capital: BlockPos, make: Boolean): TownTalkData.Entry? {
        val data = TownTalkData.get(level)
        return data[capital] ?: if (make) data.open(capital, Spread.hourOf(level), Memory.day(level).toLong()) else null
    }

    /** The mind kept for [r]: their record's, or for someone with no record today's in memory; made if [make]. */
    private fun mind(level: ServerLevel, r: Resident, make: Boolean): Mind? {
        if (r.id < 0) {
            val day = Memory.day(level)
            passers[r]?.takeIf { it.first == day }?.let { return it.second }
            if (!make) return null
            return Mind(-1).also { passers[r] = day to it }
        }
        return entry(level, r.capital, make)?.mind(r.id, make)
    }

    /** What [r] keeps of [player], made if need be; null with memory switched off. */
    private fun acq(level: ServerLevel, r: Resident, player: UUID): Acq? {
        if (!Memory.enabled()) return null
        val mind = mind(level, r, true) ?: return null
        return Memory.acq(mind, player, Memory.day(level), true)
    }

    private fun changed(level: ServerLevel, r: Resident) {
        if (r.id >= 0) TownTalkData.get(level).changed()
    }
}

/**
 * What a resident remembers of one player (section 3), worked out to the moment it was asked for: [met] the day they
 * first talked or dealt (-1: only seen); whether they know the player's name ([named]); the last day they talked or dealt
 * ([seen]) and on how many [days]; their [trades] at the resident's stall and the Marks [spent] there; the [favour] and
 * [slights] felt now, faded; the slight that weighs most now ([worst]); the deeds they saw the player do ([witnessed],
 * newest first); and the [opinion] worked out from it all, from -100 to 100.
 */
class Acquaintance(
    val player: UUID,
    val met: Int,
    val named: Boolean,
    val seen: Int,
    val days: Int,
    val trades: Int,
    val spent: Int,
    val favour: Double,
    val slights: Double,
    val worst: Slight?,
    val witnessed: List<Deed>,
    val opinion: Int,
)

/** A deed a resident saw a player do: its rumour kind ("deed.dragon"), the day, and whether it was for good or ill. */
class Deed(val kind: String, val day: Int, val tone: Int)

/** A good turn a player did a resident, and how much it counts (section 3; half-life 10 days). */
enum class Favour(val amount: Double) {
    /** Sold the town what it was short of, at their stall. */
    SOLD_SHORT(0.5),
    /** Escorted the wagon in (M4): the gate guards and keepers. */
    ESCORTED(0.5),
    /** Filled their order (M3). */
    ORDER_FILLED(1.0),
    /** Brought back salvage from their wagon (M4). */
    SALVAGE(1.0),
    /** Freed them, or brought them home. */
    FREED(3.0),
    /** Saved them from a monster. */
    SAVED(3.0),
    /**
     * Paid for one of their town's works (M6): its patron, and anyone who gave a quarter of its price, from each
     * resident who hears of it, once for each works (see [TownTalk.heard]).
     */
    FUNDED(1.5),
    /** Brought materials to the yard of the works they're labouring on (M6): from its crew, once a day ([TownTalk.brought]). */
    BROUGHT(0.5),
    /** Gave at the alms table on a day they ate there (M6): once a day ([TownTalk.fed]). */
    FED(0.5),

    // Appended for the living-world designs, in the order they were accepted (living-world/build-plan.md section 2): the
    // names and amounts only; each design's part says when one counts, and whether it counts once a day ([daily])
    /** A Sound dish of the player's, relished at a meal (Hearthcraft). */
    RELISHED(0.5),
    /** A Fine or Hallmarked dish of the player's, relished at a meal (Hearthcraft). */
    RELISHED_FINE(1.0),
    /** Goods the player hauled to the town from afar (Trade): from the shop's residents. */
    HAULED(1.0),
    /** A relief the player brought the town (Trade): from every resident who hears of it. */
    RELIEVED(2.0),
    /** Helped see a tale through to a good end (Tales): from the people it was about and their households. */
    TALE_HELPED(3.0),
    /** A tale's good end, heard of (Tales): from each resident who hears the rumour, once for each tale. */
    TALE_HEARD(1.0);

    /** Counted at most once a day from each resident, and only while the favour felt is small ([Memory.DAILY_CAP]). */
    val daily: Boolean get() = this == BROUGHT || this == FED || this == RELISHED || this == RELISHED_FINE
}

/** A wrong a player did a resident: how much it counts, and its half-life in days (section 3). */
enum class Slight(val amount: Double, val halfLife: Int) {
    /** Walked off mid-talk (once a day). */
    WALKED_OFF(0.2, 5),
    /** Let their order lapse (M3). */
    ORDER_LAPSED(0.5, 5),
    /** Kept salvage off their wagon (M4). */
    SALVAGE_KEPT(1.0, 5),
    /** Caught lifting their purse. */
    THEFT(2.0, 15),
    /** Struck them. */
    STRUCK(3.0, 15),
    /** Struck one of their household. */
    STRUCK_KIN(1.5, 15),
    /** Killed someone in front of them. */
    KILLING_SEEN(2.0, 15),
    /** Killed one of their household. */
    KILLED_KIN(8.0, 60),

    // Appended for the living-world designs, in the order they were accepted (living-world/build-plan.md section 2)
    /** Gave their word on the Marlow note and broke it (Tales): the lender and the debtor. */
    WORD_BROKEN(1.5, 15),
    /** Accused the wrong person of the locket (Tales): the one accused. */
    WRONGED(2.0, 15),
    /** Broke a stone or a cairn the town set down (Tales): the household it was for. */
    DEFACED(1.0, 15),
}

/** How fast a rumour cools: its heat is multiplied by [daily] each dawn. */
enum class Fade(val daily: Float) {
    /** Small talk. */
    SMALL(0.7f),
    /** Big news. */
    BIG(0.85f),
    /** Lore: a dragon slain, the Hollow King fallen, talked of for a season. */
    LORE(0.95f),
}

/** How far a rumour may go: its own capital, or the others too. */
enum class Scope { TOWN, REGION }

/** Where a resident heard a rumour, which the lead-in says ("Word in the tavern is..."). */
enum class Venue { SEEN, HOUSEHOLD, VISIT, TAVERN, WELL, MARKET, WORK, TEMPLE, STROLL, STREET, CRIER, AFAR }

/**
 * Something said about the town (section 4): [id] within its capital; its [kind] ("deed.dragon", "slight.murder",
 * "death", "market.event.blight", "road.waylaid", ...); the player it's [about] and the [resident] it's about (or -1);
 * its facts as Speech arguments ([args]: the good, the band's name, the place, a name as "=Tam Miller"); the hour it was
 * [born]; how much people want to tell it ([heat], 0 to 1), how it fades and how far it goes; whether it's bad or good
 * news ([tone]); and where it happened ([origin]), for "at the ford".
 */
class Rumour(
    val id: Int,
    val kind: String,
    val about: UUID?,
    val resident: Int,
    val args: List<String>,
    val born: Long,
    var heat: Float,
    val fade: Fade,
    val scope: Scope,
    val tone: Int,
    val origin: BlockPos?,
)

/**
 * How a resident came by a rumour: which ([rumour]), the [hour], [from] whom (a resident's id; 0 they saw it
 * themselves; -1 the crier; -2 from afar),
 * where ([venue]), which version they had (warp: 0 as it happened, 1 grown in the telling, 2 muddled, 3 a tall tale)
 * and how many they've [told].
 */
class Heard(val rumour: Int, val hour: Long, val from: Int, val venue: Venue, val warp: Int, var told: Int)

/** A rumour a resident knows, as they heard it. */
class Known(val rumour: Rumour, val heard: Heard)

/**
 * What befell a resident lately, and how many days it stays with them: LABOURED, a building day on one of the town's
 * works (M6), stays a month, the labourer's own pride in it (see [TownTalk.laboured]). FEASTED, a week, is a player's
 * dish well eaten, its [Felt.about] the dish's kind (Hearthcraft, living-world/cook/design.md section 8.2).
 */
enum class Experience(val days: Int) { HUNGRY(7), FRIGHTENED(7), ROBBED(15), SAVED(15), BEREAVED(30), LABOURED(30), FEASTED(7) }

/**
 * Something that befell a resident on [day]; [about] is the resident it concerned (the one who died), for LABOURED
 * the works' id, or -1.
 */
class Felt(val experience: Experience, val day: Int, val about: Int = -1)
