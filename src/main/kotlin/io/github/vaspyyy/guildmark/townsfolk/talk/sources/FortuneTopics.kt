package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.fortune.FortuneDay
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Reason
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Experience
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Memory
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumours
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.network.chat.Component
import kotlin.math.abs

/**
 * How the town is faring, as its people talk of it (living-world/design-influence.md section 7), all from the
 * fortunes' record ([Fortunes]) and what each speaker knows:
 * - the times ("Times are hard. There's no two ways about it."), with the biggest reason pulling them that way as the
 *   detail, a reason of the speaker's own trade or household first (the baker on her cold ovens, a carter on the shut
 *   road, a mother on the hungry days); their own reasons the times don't go by they say on their own;
 * - the change: the times moved in the last three days ("Times were lean till yesterday. They're steady now."), or a
 *   week's swing in how the town is doing, with the biggest reason for it;
 * - a family gone in hard times, or back ("They're back! Good to see their lamp lit again.", pointing at their home),
 *   from the households' rumours (Witness), as long as the word of it is warm;
 * - and a household's own, its first days: newcomers ("So this is the place.") and those come home ("Home again. I
 *   never thought I'd be so glad.").
 *
 * Children say they're hungry when they or the town went without. "How's the town?" asks the times and their two
 * biggest reasons. The times' talk says nothing while the fortunes are off ([Fortunes.enabled]), the households'
 * while the comings are ([Comings.enabled]; the test commands may hear one meanwhile, see [trying]).
 */
object FortuneTopics : TopicSource {
    override val id = "fortune"

    /** The talk screen's choice: "How's the town?". */
    const val ASK = "fortune:ask"

    private val OCCASIONS = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** What the times weigh, said: hard times and thriving are talked of most. */
    private val WEIGHTS = mapOf(Times.HARD to 0.6, Times.LEAN to 0.45, Times.GOOD to 0.4, Times.THRIVING to 0.55)

    /** Market days a move of the times is talked of, and the swing in the score over a week that's felt as a change. */
    private const val MOVED_DAYS = 3L
    private const val WEEK = 7L
    private const val SWING = 3

    /** A household's news is talked of while it's this warm; their home is pointed at from this near (blocks). */
    private const val WARM = 0.1f
    private const val HOME_NEAR = 64.0

    /** Market days a household's own first days are talked of: newcomers, and those come home. */
    private const val NEW_DAYS = 2L
    private const val HOME_DAYS = 3L

    /** The answer's parts at most: the times and their two biggest reasons, or the move. */
    private const val PARTS = 3

    /** The roads' stages a reason names (the open road is never one), and the crimes one counts. */
    private val STAGES = setOf("troubled", "perilous", "closed")
    private val CRIMES = setOf("theft", "caught", "raid", "heist")
    private val SIDES = setOf("n", "e", "s", "w", "any")

    /**
     * For the test commands (`/guildmark rumour household`): the households' talk heard though the comings are off,
     * and the households they made up to hear it by, until the server stops. Nothing in a game sets them.
     */
    @Volatile
    var trying = false
    val tried = ArrayList<Household>()

    fun forget() {
        trying = false
        tried.clear()
    }

    /** Whether the households' talk is heard: the comings are on, or a test command asked for it. */
    private fun households(): Boolean = Comings.enabled() || trying

    /** The town [Ask.speaker] speaks for, as its fortunes stand: the times, their reasons (biggest first), the day, and when and from what they last moved. */
    private class Town(
        val capital: Long, val times: Times, val reasons: List<Reason>, val today: Long, val since: Long, val from: Times, val days: List<FortuneDay>,
    ) {
        /** -1 for the hard and lean times, 1 for the good and thriving, 0 steady. */
        val sign: Int get() = Integer.signum(times.ordinal - Times.STEADY.ordinal)

        /** Whether they moved in the last few days, and which way: 1 for the better. */
        val moved: Boolean get() = since >= 0 && today - since in 0L..MOVED_DAYS && from != times
        val way: Int get() = Integer.signum(times.ordinal - from.ordinal)
    }

    private fun town(ask: Ask): Town? {
        if (!Fortunes.enabled()) return null
        val heart = ask.resident?.capital ?: Say.market(ask)?.capital ?: return null
        val capital = heart.asLong()
        val level = ask.level
        return Town(
            capital, Fortunes.times(level, capital), Fortunes.reasons(level, capital), Fortunes.today(level, capital),
            Fortunes.since(level, capital), Fortunes.from(level, capital), Fortunes.days(level, capital),
        )
    }

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS) return
        val r = ask.resident ?: return
        val town = town(ask)
        if (Say.child(ask.speaker)) {
            if (town != null) hungry(ask, r, town, out)
            return
        }
        if (town != null) {
            times(ask, r, town, out)
            change(ask, town, out)
        }
        if (households()) {
            neighbours(ask, r, out)
            comings(ask, r, out)
        }
    }

    // ---- The times ----

    /**
     * The times, with the biggest reason pulling them that way (the speaker's own first), and, each on its own, the
     * speaker's other reasons of their own: those pulling against the times, the steady times', and the rest.
     */
    private fun times(ask: Ask, r: Resident, town: Town, out: MutableList<Topic>) {
        val own = own(ask, r, town)
        val mine = if (town.times != Times.STEADY) own.firstOrNull { Integer.signum(it.first.points) == town.sign } else null
        if (town.times != Times.STEADY) {
            val reason = mine?.first ?: town.reasons.firstOrNull { Integer.signum(it.points) == town.sign }
            val detail = mine?.second ?: reason?.let(::detail) ?: "talk.detail.fortune.times.${town.times.id}"
            Say.line(ask, "fortune.${town.times.id}", detail)?.let { say ->
                val weight = (WEIGHTS[town.times] ?: 0.4) * (if (mine != null) 1.15 else 1.0)
                val id = "fortune.times.${town.times.id}.${reason?.kind ?: "held"}" + if (mine != null) ".own" else ""
                out.add(Topic(id, Kind.FORTUNE, weight, say, tone = town.sign))
            }
        }
        if (ask.occasion == Occasion.BARK) return
        for ((reason, detail) in own) {
            if (reason === mine?.first) continue
            out.add(Topic("fortune.own.${reason.kind}.${town.today}", Kind.FORTUNE, 0.35, Utterance(listOf(aloud(ask, detail))), tone = Integer.signum(reason.points)))
        }
    }

    /**
     * The reasons [r] has words of their own for, from their trade or their household, biggest first, each with
     * them: the baker's cold or cracked ovens, the miller's idle mill, the farmer's blight and bumper crops, the
     * fisher's nets, the keeper's dear food and empty stalls, the carter's and the guard's roads, the priest's sick and
     * dead, the innkeeper's festival, a labourer's works, and a parent's hungry children.
     */
    private fun own(ask: Ask, r: Resident, town: Town): List<Pair<Reason, String>> {
        if (r.id < 0 || town.reasons.isEmpty()) return listOf()
        val level = ask.level
        val people = Residents.get(level).of(r.capital)
        val parent = r.home >= 0 && people.any { it.alive && it.home == r.home && it.id != r.id && it.role == TownRole.CHILD }
        val felt = TownTalk.felt(level, r)
        val hungry = felt.any { it.experience == Experience.HUNGRY && Memory.day(level) - it.day <= 1 }
        val building = felt.filter { it.experience == Experience.LABOURED }.mapNotNull { f -> Works.get(level, f.about) }
            .firstOrNull { it.state == WorksState.BUILDING && it.capital == town.capital }
        val life = ask.life
        val who = Who(r.role, life?.mill != null, life?.carter == true, parent, hungry, building?.kind)
        return town.reasons.mapNotNull { reason -> mine(who, reason)?.let { reason to it } }
    }

    /** What the speaker is, for their own reasons: their trade, whether they keep the mill or drive a cart today, a parent, hungry lately, and the works they're building. */
    private class Who(val role: TownRole, val miller: Boolean, val carter: Boolean, val parent: Boolean, val hungry: Boolean, val building: WorksKind?)

    /** [reason] in [who]'s own words, if it's theirs. */
    private fun mine(who: Who, reason: Reason): String? {
        val a = reason.args
        val role = who.role
        val k = "talk.detail.fortune.own."
        return when (reason.kind) {
            FortuneReasons.IDLE -> when {
                role == TownRole.BAKER && "ovens" in a -> k + "idle.ovens"
                who.miller && "mill" in a -> k + "idle.mill"
                else -> null
            }
            FortuneReasons.TROUBLE -> a.firstNotNullOfOrNull { code ->
                val kind = code.substringBefore(':')
                when {
                    kind == "oven_cracked" && role == TownRole.BAKER -> k + "trouble.oven_cracked"
                    kind == "mill_broken" && who.miller -> k + "trouble.mill_broken"
                    kind == "blight" && role == TownRole.FARMER -> good(code)?.let { Speech.of(k + "trouble.blight", it) }
                    (kind == "murrain" || kind == "fowl_pest") && role == TownRole.FARMER -> k + "trouble.$kind"
                    kind == "poor_catch" && role == TownRole.FISHER -> k + "trouble.poor_catch"
                    else -> null
                }
            }
            FortuneReasons.PLENTY -> a.firstNotNullOfOrNull { code ->
                val kind = code.substringBefore(':')
                when {
                    kind == "bumper" && role == TownRole.FARMER -> good(code)?.let { Speech.of(k + "plenty.bumper", it) }
                    kind == "good_catch" && role == TownRole.FISHER -> k + "plenty.catch"
                    else -> null
                }
            }
            FortuneReasons.HUNGRY -> when {
                who.parent -> k + "hungry.children"
                who.hungry -> k + "hungry.self"
                else -> null
            }
            FortuneReasons.FED -> if (who.parent) k + "fed" else null
            FortuneReasons.DEAR -> if (role == TownRole.MERCHANT) k + "dear" else null
            FortuneReasons.CHEAP -> if (role == TownRole.MERCHANT) k + "cheap" else null
            FortuneReasons.WANTING -> when {
                role == TownRole.SMITH && Good.TOOLS.id in a -> k + "wanting.tools"
                role == TownRole.MERCHANT -> a.firstNotNullOfOrNull { Good.byId(it) }?.let { Speech.of(k + "wanting", it.key + ".many") }
                else -> null
            }
            FortuneReasons.ROAD -> when {
                a.getOrNull(0) !in STAGES -> null
                who.carter -> Speech.of(k + "road.carter", way(a.getOrNull(1)))
                role == TownRole.GUARD -> Speech.of(k + "road.guard", way(a.getOrNull(1)))
                else -> null
            }
            FortuneReasons.WAYLAID -> when {
                role == TownRole.MERCHANT -> k + "waylaid.merchant"
                who.carter -> k + "waylaid.carter"
                role == TownRole.GUARD -> k + "waylaid.guard"
                else -> null
            }
            FortuneReasons.RELIEF -> if (role == TownRole.GUARD || who.carter) Speech.of(k + "relief", way(a.getOrNull(0))) else null
            FortuneReasons.ESCORTED -> if (who.carter || role == TownRole.MERCHANT) {
                a.drop(1).firstOrNull { it.isNotEmpty() }?.let { Speech.of(k + "escorted", Witness.named(it)) }
            } else null
            FortuneReasons.SUPPLIED -> if (role == TownRole.MERCHANT) a.firstOrNull { it.isNotEmpty() }?.let { Speech.of(k + "supplied", Witness.named(it)) } else null
            FortuneReasons.FEVER -> if (role == TownRole.PRIEST) k + "fever" else null
            FortuneReasons.MOURNING -> if (role == TownRole.PRIEST) a.firstOrNull { it.isNotEmpty() }?.let { Speech.of(k + "mourning", Witness.named(it)) } else null
            FortuneReasons.CRIME -> if (role == TownRole.GUARD) k + "crime" else null
            FortuneReasons.FESTIVAL -> {
                val festival = a.getOrNull(0)?.let(::festival)
                when {
                    festival == null -> null
                    role == TownRole.INNKEEPER -> Speech.of(k + "festival.inn", festival)
                    role == TownRole.BARD -> Speech.of(k + "festival.bard", festival)
                    else -> null
                }
            }
            FortuneReasons.BUILDING -> who.building?.takeIf { it.id in a }?.let { Speech.of(k + "building", works(it.id)) }
            FortuneReasons.EASED -> when {
                a.getOrNull(1) == Works.BAND && role == TownRole.GUARD -> Speech.of(k + "eased.band", way(a.getOrNull(3)))
                a.getOrNull(1) == Works.BREAD && role == TownRole.BAKER -> k + "eased.bread"
                a.getOrNull(1) == Works.MILL && who.miller -> k + "eased.mill"
                else -> null
            }
            else -> null
        }
    }

    /**
     * [reason] in the town's words, as a detail ("The mill stood idle yesterday for want of grain."): its own facts,
     * the names in it, the road and the place; a reason made by hand, with no facts, in a word of its kind. Null for a
     * kind it has no words for.
     */
    fun detail(reason: Reason): String? {
        val a = reason.args
        val k = "talk.detail.fortune."
        return when (reason.kind) {
            FortuneReasons.HUNGRY -> a.getOrNull(0)?.toIntOrNull()?.let { if (it <= 1) k + "hungry.one" else Speech.of(k + "hungry", it) } ?: (k + "hungry.none")
            FortuneReasons.FED -> k + "fed"
            FortuneReasons.DEAR, FortuneReasons.CHEAP ->
                a.getOrNull(1)?.let(Good::byId)?.let { Speech.of(k + reason.kind, it.key + ".many") } ?: (k + reason.kind + ".all")
            FortuneReasons.WANTING -> {
                val goods = a.mapNotNull(Good::byId).map { it.key + ".many" }
                when (goods.size) {
                    0 -> k + "wanting.none"
                    1 -> Speech.of(k + "wanting.one", goods[0])
                    else -> Speech.of(k + "wanting.two", goods[0], goods[1])
                }
            }
            FortuneReasons.IDLE -> when {
                "mill" in a && "ovens" in a -> k + "idle.both"
                "mill" in a -> k + "idle.mill"
                "ovens" in a -> k + "idle.ovens"
                else -> k + "idle.none"
            }
            FortuneReasons.ROAD -> a.getOrNull(0)?.takeIf { it in STAGES }?.let { Speech.of("talk.detail.road.stage.$it", way(a.getOrNull(1))) } ?: (k + "road.none")
            FortuneReasons.WAYLAID -> {
                val n = a.getOrNull(0)?.toIntOrNull()
                when {
                    n == null -> k + "waylaid.none"
                    n == 1 && a.size >= 3 -> Speech.of(k + "waylaid.one", place(a[1]), way(a[2]))
                    else -> Speech.of(k + "waylaid.many", n)
                }
            }
            FortuneReasons.RELIEF -> if (a.isNotEmpty()) Speech.of(k + "relief", way(a[0])) else k + "relief.none"
            FortuneReasons.ESCORTED -> {
                val n = a.getOrNull(0)?.toIntOrNull()
                val who = a.drop(1).firstOrNull { it.isNotEmpty() }
                when {
                    n == null || who == null -> k + "escorted.none"
                    n == 1 -> Speech.of(k + "escorted.one", Witness.named(who))
                    else -> Speech.of(k + "escorted.many", n, Witness.named(who))
                }
            }
            FortuneReasons.SUPPLIED -> names(k + "supplied", a)
            FortuneReasons.PLENTY -> a.firstNotNullOfOrNull { event(k, it, plenty = true) } ?: (k + "plenty.none")
            FortuneReasons.TROUBLE -> a.firstNotNullOfOrNull { event(k, it, plenty = false) } ?: (k + "trouble.none")
            FortuneReasons.FEVER -> k + "fever"
            FortuneReasons.MOURNING -> names(k + "mourning", a)
            FortuneReasons.CRIME -> a.chunked(2).firstOrNull { it.size == 2 && it[0] in CRIMES }?.let { (what, n) ->
                val count = n.toIntOrNull() ?: 1
                if (count <= 1) k + "crime.$what.one" else Speech.of(k + "crime.$what", count)
            } ?: (k + "crime.none")
            FortuneReasons.FESTIVAL -> a.getOrNull(0)?.let(::festival)?.let { Speech.of(if ("fed" in a) k + "festival.fed" else k + "festival", it) } ?: (k + "festival.none")
            FortuneReasons.BUILDING -> {
                val kinds = a.filter { WorksKind.byId(it) != null }.map(::works)
                when (kinds.size) {
                    0 -> k + "building.none"
                    1 -> Speech.of(k + "building", kinds[0])
                    else -> Speech.of(k + "building.two", kinds[0], kinds[1])
                }
            }
            FortuneReasons.RAISED -> a.getOrNull(0)?.takeIf { WorksKind.byId(it) != null }?.let { kind ->
                val patron = a.getOrNull(1)?.takeIf { it.isNotEmpty() }
                if (patron != null) Speech.of(k + "raised", works(kind), Witness.named(patron)) else Speech.of(k + "raised.nobody", works(kind))
            } ?: (k + "raised.none")
            FortuneReasons.EASED -> {
                val kind = a.getOrNull(0)?.takeIf { WorksKind.byId(it) != null }
                when (a.getOrNull(1)) {
                    Works.MILL, Works.BREAD -> kind?.let { Speech.of(k + "eased.${a[1]}", works(it)) }
                    Works.BAND -> Speech.of(k + "eased.band", way(a.getOrNull(3)))
                    else -> null
                } ?: (k + "eased.none")
            }
            FortuneReasons.NEWCOMERS, FortuneReasons.LEAVING -> {
                val n = a.getOrNull(0)?.toIntOrNull() ?: 1
                val family = a.getOrNull(2)?.takeIf { it.isNotEmpty() }
                when {
                    n > 1 -> Speech.of(k + "${reason.kind}.many", n)
                    family != null && reason.kind == FortuneReasons.NEWCOMERS && a.getOrNull(1) == "return" -> Speech.of(k + "newcomers.home", Witness.named(family))
                    family != null -> Speech.of(k + "${reason.kind}.family", Witness.named(family))
                    else -> k + "${reason.kind}.one"
                }
            }
            else -> null
        }
    }

    /** A market event's code ("blight:wheat") in words, as plenty or as trouble. */
    private fun event(k: String, code: String, plenty: Boolean): String? = when (val kind = code.substringBefore(':')) {
        "bumper" -> if (plenty) good(code)?.let { Speech.of(k + "plenty.bumper", it) } else null
        "good_catch" -> if (plenty) k + "plenty.catch" else null
        "blight" -> if (!plenty) good(code)?.let { Speech.of(k + "trouble.blight", it) } else null
        "murrain", "fowl_pest", "mill_broken", "oven_cracked", "poor_catch" -> if (!plenty) k + "trouble.$kind" else null
        else -> null
    }

    /** The crop an event's code names, as a Speech argument ("good.guildmark.wheat.many"). */
    private fun good(code: String): String? = Good.byId(code.substringAfter(':', ""))?.let { it.key + ".many" }

    /** [key] naming the one of [names], ".two" both, or ".more" the first and how many others; ".none" with none. */
    private fun names(key: String, names: List<String>): String {
        val named = names.filter { it.isNotEmpty() }
        return when (named.size) {
            0 -> "$key.none"
            1 -> Speech.of(key, Witness.named(named[0]))
            2 -> Speech.of("$key.two", Witness.named(named[0]), Witness.named(named[1]))
            else -> Speech.of("$key.more", Witness.named(named[0]), named.size - 1)
        }
    }

    /** A festival by its id, as a Speech argument ("festival.guildmark.sunhearth"). */
    private fun festival(id: String): String? = FestivalKind.entries.firstOrNull { it.id == id }?.let { "festival.guildmark.${it.id}" }

    /** A works by its kind's id, in a sentence ("granary"). */
    private fun works(kind: String): String = "fortune.guildmark.works.$kind"

    /** The road in by [side] ("the north road"), as the fortunes name it. */
    private fun way(side: String?): String = "road.guildmark.way." + (side?.takeIf { it in SIDES } ?: "any")

    /** Where on the road a wagon was taken ("at the ford"). */
    private fun place(place: String): String = "road.guildmark.place." + (place.takeIf { it in Caravans.PLACES } ?: "road")

    /** The times in a word ("lean"). */
    private fun word(times: Times): String = Say.WORD + "times." + times.id

    /** What a reason's words are keyed by: the speaker's own, the town's, and a road's stage. */
    private const val OWN_WORDS = "talk.detail.fortune.own."
    private const val TOWN_WORDS = "talk.detail.fortune."
    private const val ROAD_WORDS = "talk.detail.road.stage."

    /**
     * A reason's [detail] (the speaker's own, or the town's) said on its own: a take of its family, fortune.own.<its key
     * after talk.detail.fortune.own.> or fortune.why.<its key after talk.detail.fortune.> (a road's stage,
     * fortune.why.road.<stage>). Where its words are fixed the take says them and is all there is; where they name
     * something (how many, a good, a road, a person, a festival, a works) the take says what it means, and the detail
     * follows in words. In words alone while none is recorded.
     */
    private fun aloud(ask: Ask, detail: String): Utterance.Part {
        val key = Speech.key(detail)
        val family = when {
            key.startsWith(OWN_WORDS) -> "fortune.own." + key.removePrefix(OWN_WORDS)
            key.startsWith(ROAD_WORDS) -> "fortune.why.road." + key.removePrefix(ROAD_WORDS)
            key.startsWith(TOWN_WORDS) -> "fortune.why." + key.removePrefix(TOWN_WORDS)
            else -> null
        }
        return family?.let { Say.lead(ask, it, if ('|' in detail) detail else null) } ?: Utterance.Part("", detail)
    }

    // ---- The change ----

    /**
     * The times moved lately, and which way ("Times were lean till yesterday. They're steady now."), with the biggest
     * reason for it; or, with the times as they were, a swing of three or more in a week, with the biggest reason that
     * way. Not the reason the times' own talk gives, so it isn't heard twice. In passing, only a move of the last day.
     */
    private fun change(ask: Ask, town: Town, out: MutableList<Topic>) {
        val bark = ask.occasion == Occasion.BARK
        val shown = if (town.times != Times.STEADY) town.reasons.firstOrNull { Integer.signum(it.points) == town.sign } else null
        if (town.moved) {
            val days = town.today - town.since
            if (bark && days > 1) return
            val say = Say.part(ask, if (town.way > 0) "fortune.better" else "fortune.worse", moved(town)) ?: return
            val more = if (bark) null else town.reasons.firstOrNull { Integer.signum(it.points) == town.way && it !== shown }?.let(::detail)
            out.add(Topic(
                "fortune.moved.${town.since}.${town.times.id}", Kind.FORTUNE, if (days <= 1) 0.6 else 0.5,
                Utterance(listOfNotNull(say, more?.let { aloud(ask, it) })), tone = town.way,
            ))
            return
        }
        if (bark) return
        val now = town.days.lastOrNull() ?: return
        val then = town.days.lastOrNull { it.day <= now.day - WEEK } ?: return
        if (abs(now.score - then.score) < SWING) return
        val way = Integer.signum(now.score - then.score)
        val why = town.reasons.firstOrNull { Integer.signum(it.points) == way && it !== shown }?.let(::detail)
        val say = Say.line(ask, if (way > 0) "fortune.better" else "fortune.worse", why) ?: return
        out.add(Topic("fortune.swing.$way.${town.today}", Kind.FORTUNE, 0.5, say, tone = way))
    }

    /** "Times were lean till yesterday. They're steady now.": the move, from what and when. */
    private fun moved(town: Town): String {
        val days = town.today - town.since
        return if (days <= 0) Speech.of("talk.detail.fortune.moved.0", word(town.from), word(town.times))
        else Speech.of("talk.detail.fortune.moved", word(town.from), Say.ago(days), word(town.times))
    }

    // ---- The households ----

    /**
     * A family gone in hard times, or back, as the speaker heard it (a household's rumour, Witness), while the word of
     * it is warm: sighed over, or glad of and their home pointed at. Not of the speaker's own household.
     */
    private fun neighbours(ask: Ask, r: Resident, out: MutableList<Topic>) {
        val level = ask.level
        val bark = ask.occasion == Occasion.BARK
        val today = Say.day(level)
        val residents = Residents.get(level)
        for (k in TownTalk.known(level, r)) {
            val rumour = k.rumour
            val gone = rumour.kind == Rumours.HOUSEHOLD_GONE
            if (!gone && rumour.kind != Rumours.HOUSEHOLD_BACK || rumour.heat < WARM) continue
            val member = residents[rumour.resident]
            if (member != null && member.home >= 0 && member.home == r.home && member.family == r.family) continue
            val family = rumour.args.firstOrNull()?.takeIf { it.length > 1 && it.startsWith("=") } ?: continue
            val age = today - Math.floorDiv(rumour.born, 24L)
            if (bark && age > 1) continue
            val detail = Speech.of(if (gone) "talk.detail.fortune.gone" else "talk.detail.fortune.back", family, Say.ago(age))
            val say = Say.line(ask, if (gone) "fortune.gone" else "fortune.back", detail) ?: continue
            val home = rumour.origin?.takeIf { !gone && it.distSqr(ask.speaker.blockPosition()) <= HOME_NEAR * HOME_NEAR }
            out.add(Topic(
                "fortune.${if (gone) "gone" else "back"}.${rumour.id}", Kind.FORTUNE, 0.2 + 0.6 * rumour.heat, say,
                tone = if (gone) -1 else 1,
                gesture = when {
                    gone -> Activity.SIGH
                    home != null -> Activity.POINT
                    else -> Activity.LAUGH
                },
                toward = home, rumour = rumour.id.takeIf { !bark },
            ))
        }
    }

    /** A household's own first days: newcomers, and why they came; those come home, and how long they've been back. */
    private fun comings(ask: Ask, r: Resident, out: MutableList<Topic>) {
        if (r.id < 0) return
        val level = ask.level
        val capital = r.capital.asLong()
        val arrived = if (Comings.enabled()) Comings.arrived(level, capital, HOME_DAYS.toInt()) else listOf()
        val h = (arrived + tried.filter { it.capital == capital }).lastOrNull { r.id in it.members } ?: return
        val age = Conditions(Markets.hourOf(level)).day - h.day
        if (ask.occasion == Occasion.BARK && age > 1) return
        if (h.reason == "return") {
            if (age !in 0L..HOME_DAYS) return
            val detail = when (age) {
                0L -> "talk.detail.comings.home.0"
                1L -> "talk.detail.comings.home.1"
                else -> Speech.of("talk.detail.comings.home", age)
            }
            Say.line(ask, "comings.home", detail)?.let { out.add(Topic("comings.home.${h.id}", Kind.FORTUNE, 0.6 - 0.1 * age, it, tone = 1)) }
            return
        }
        if (age !in 0L..NEW_DAYS) return
        val why = when (h.reason) {
            "replace", "grow" -> h.reason
            "trade" -> when (r.role) {
                TownRole.BAKER -> "baker"
                TownRole.MERCHANT -> "merchant"
                else -> "trade"
            }
            else -> "other"
        }
        Say.line(ask, "comings.arrive", "talk.detail.comings.new.$why")?.let { out.add(Topic("comings.arrive.${h.id}", Kind.FORTUNE, 0.6 - 0.15 * age, it)) }
    }

    // ---- The children ----

    /** A child who went without lately, or in lean or hard times with the town going hungry: "I'm hungry. Is there anything to eat?". */
    private fun hungry(ask: Ask, r: Resident, town: Town, out: MutableList<Topic>) {
        val level = ask.level
        val felt = r.id >= 0 && TownTalk.felt(level, r).any { it.experience == Experience.HUNGRY && Memory.day(level) - it.day <= 1 }
        val lean = town.times <= Times.LEAN && town.reasons.any { it.kind == FortuneReasons.HUNGRY }
        if (!felt && !lean) return
        val say = Say.line(ask, "child.hungry", null) ?: return
        out.add(Topic("fortune.child.hungry.${town.today}", Kind.FORTUNE, 0.5, say, tone = -1, gesture = Activity.SIGH))
    }

    // ---- "How's the town?" ----

    override fun choice(ask: Ask): Choice? {
        if (Say.child(ask.speaker) || ask.player == null || ask.resident == null) return null
        val town = town(ask) ?: return null
        val pressing = when {
            town.times == Times.HARD -> 0.72
            town.moved -> 0.65
            town.times == Times.THRIVING -> 0.6
            town.times == Times.STEADY -> 0.2
            else -> 0.4
        }
        return Choice(ASK, Component.translatable("gui.guildmark.talk.fortune"), pressing = pressing)
    }

    /**
     * The times and their two biggest reasons (the one pulling the times' way first, in the speaker's own words if it's
     * theirs); steady times say so, or how they moved; and a move of the last few days after.
     */
    override fun answer(ask: Ask, choice: String): Utterance? {
        if (choice != ASK) return null
        val town = town(ask) ?: return null
        val own = ask.resident?.let { own(ask, it, town) } ?: listOf()
        val said = ArrayList<Reason>()
        val parts = ArrayList<Utterance.Part>()
        when {
            town.times != Times.STEADY -> {
                val mine = own.firstOrNull { Integer.signum(it.first.points) == town.sign }
                val reason = mine?.first ?: town.reasons.firstOrNull { Integer.signum(it.points) == town.sign }
                reason?.let(said::add)
                val detail = mine?.second ?: reason?.let(::detail) ?: "talk.detail.fortune.times.${town.times.id}"
                Say.part(ask, "fortune.${town.times.id}", detail)?.let(parts::add)
            }
            town.moved -> Say.part(ask, if (town.way > 0) "fortune.better" else "fortune.worse", moved(town))?.let(parts::add)
            else -> parts.add(Say.part(ask, "fortune.steady", null) ?: Utterance.Part("", "talk.detail.fortune.steady"))
        }
        // The reasons pulling the times' way first (biggest first among them), then the rest
        for (reason in town.reasons.sortedByDescending { town.sign != 0 && Integer.signum(it.points) == town.sign }) {
            if (said.size >= 2 || parts.size >= PARTS) break
            if (said.any { it === reason }) continue
            val detail = own.firstOrNull { it.first === reason }?.second ?: detail(reason) ?: continue
            said.add(reason)
            parts.add(aloud(ask, detail))
        }
        if (town.moved && town.times != Times.STEADY && parts.size < PARTS) {
            Say.part(ask, if (town.way > 0) "fortune.better" else "fortune.worse", moved(town))?.let(parts::add)
        }
        if (parts.isEmpty()) return null
        return Utterance(parts.take(PARTS))
    }
}
