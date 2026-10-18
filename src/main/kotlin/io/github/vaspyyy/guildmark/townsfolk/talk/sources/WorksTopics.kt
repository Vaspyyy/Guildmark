package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Labour
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Experience
import io.github.vaspyyy.guildmark.townsfolk.talk.Felt
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Known
import io.github.vaspyyy.guildmark.townsfolk.talk.Memory
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumours
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * The town's works as its people talk of them (living-world/design-influence.md section 7), all from the works'
 * records ([Works]) and what each speaker knows of them:
 * - a works wanted, and why, the day the need showed ("We could do with a granary.", "The mill stood idle yesterday
 *   for want of grain."), pointing at its post; the Marks given toward it against its price;
 * - one paid for, waiting on its materials (in words only: no voice says it yet);
 * - one going up, pointed at, with the stage the builders are at and how much is built; held up when its yard has
 *   run out of what it's laying and the wagon bringing more is still out ("They're out of stone. The next of it's on
 *   the wagon in by the north road, due about nine in the morning.");
 * - one raised, for a week, and by whose gift as far as the speaker knows; and what a works did for the town, for
 *   three days after each time it eased something ("The granary saw us through. The mill never stopped.");
 * - and the deeds of the works: its patron (and anyone who gave a quarter of its price) thanked to their face, as
 *   DEEDS, by those who've heard who paid or built it; the patron pointed out to others while they're in sight, or
 *   else named (if the speaker knows the name); and a labourer's own pride in what they built, for a month after
 *   their last day on it ([TownTalk.laboured]);
 * - a labourer's building day as they're about it, asked what they're doing: the works they're lent to, and whether
 *   they're at work on it, waiting about the site or on their way (where the builders have a site for them).
 *
 * Children play at builders while a works is going up. In passing, a works is talked of near it, or while its news is
 * fresh. "About the works..." asks what's wanted, what's going up and what's been raised, and by whom. Nothing while
 * the works are off ([Works.enabled]).
 */
object WorksTopics : TopicSource {
    override val id = "works"

    /** The talk screen's choice: "About the works...". */
    const val ASK = "works:ask"

    private val OCCASIONS = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** The occasions a player is thanked to their face for a works (as their deeds are). */
    private val THANKS = setOf(Occasion.OPENER, Occasion.ANSWER, Occasion.BARK)

    /** Market days a works raised is still news, and talked of after each time a works eased something. */
    private const val RAISED_DAYS = 7L
    private const val EASED_DAYS = 3L

    /** In passing a works is talked of only this near it (blocks), or while its news is this fresh (market days). */
    private const val NEAR = 48.0
    private const val FRESH = 1L

    /** How near the patron must be to be pointed out, and how often each may be (as the news points players out). */
    private const val THERE = 16.0
    private const val THERE_TICKS = 2 * 60 * 20L

    /** The stages that lay the paid-for materials: at these a crew with an empty yard stands about. */
    private val LAYING = setOf(Stage.FOOTINGS, Stage.WALLS, Stage.ROOF, Stage.OPENINGS, Stage.FITTING)

    /** The answer's parts at most: the first works, then two more in a word each. */
    private const val PARTS = 3

    /** How near the place they report at a labourer is at the site (blocks), as Labour has it. */
    private const val SITE = 10.0

    /** The family a works going up is talked of with (see [going]). */
    private const val GOING = "works.building"

    /** When each patron was last pointed out (game time); kept in memory only. */
    private val pointed = HashMap<UUID, Long>()

    fun forget() = pointed.clear()

    /** The town [Ask.speaker] speaks for, as its works stand: its heart, its works as the listener may see them, and its market day. */
    private class Town(val heart: BlockPos, val works: List<WorksView>, val today: Long) {
        val capital: Long get() = heart.asLong()
    }

    private fun town(ask: Ask): Town? {
        if (!Works.enabled()) return null
        val heart = ask.resident?.capital ?: Say.market(ask)?.capital ?: return null
        val works = Works.offered(ask.level, heart.asLong(), ask.player?.uuid)
        if (works.isEmpty()) return null
        return Town(heart, works, Works.today(ask.level, heart.asLong()))
    }

    /**
     * One works as [ask]'s speaker sees it now: where it stands ([at]: its post while it's wanted or paid for, its lot's
     * middle once it's going up), whether they're [near] it, the rumour of it paid for or raised they [heard] (the
     * hotter), and their own last building day on it ([laboured]).
     */
    private class Seen(val ask: Ask, val town: Town, val w: WorksView, val at: BlockPos?, val near: Boolean, val heard: Known?, val laboured: Felt?) {
        val bark: Boolean get() = ask.occasion == Occasion.BARK

        /** Its name in a sentence ("fortune.guildmark.works.granary": "granary"), a Speech argument. */
        val kind: String get() = "fortune.guildmark.works.${w.kind.id}"
    }

    /** What [ask]'s speaker knows of [town]'s works: each as they see it. */
    private fun seen(ask: Ask, town: Town): List<Seen> {
        val r = ask.resident ?: return listOf()
        val level = ask.level
        val heard = TownTalk.known(level, r).filter { it.rumour.kind == Rumours.WORKS_FUNDED || it.rumour.kind == Rumours.WORKS_RAISED }
        val felt = if (r.id >= 0) TownTalk.felt(level, r).filter { it.experience == Experience.LABOURED } else listOf()
        val from = ask.speaker.blockPosition()
        return town.works.map { w ->
            val at = place(w)
            val id = w.id.toString()
            Seen(
                ask, town, w, at, at != null && from.distSqr(at) <= NEAR * NEAR,
                heard.filter { it.rumour.args.firstOrNull() == id }.maxByOrNull { it.rumour.heat },
                felt.firstOrNull { it.about == w.id },
            )
        }
    }

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (DailySource.fits(ask.occasion)) labouring(ask, out)
        if (ask.occasion !in OCCASIONS) return
        val town = town(ask) ?: return
        for (s in seen(ask, town)) {
            if (Say.child(ask.speaker)) {
                builders(s, out)
                continue
            }
            when (s.w.state) {
                WorksState.WANTED -> wanted(s, out)
                WorksState.FUNDED -> funded(s, out)
                WorksState.BUILDING -> building(s, out)
                WorksState.DONE -> done(s, out)
            }
            thanks(s, out)
            patron(s, out)
            labourer(s, out)
        }
    }

    // ---- What's wanted, and paid for ----

    /** A works wanted, and why, the day it showed; then (in a talk) where pledges go and what it still wants. */
    private fun wanted(s: Seen, out: MutableList<Topic>) {
        val w = s.w
        if (w.kind == WorksKind.STALL) return
        val ask = s.ask
        val age = s.town.today - w.wanted
        if (s.bark && !s.near && age > FRESH) return
        val first = Say.part(ask, "works.wanted.${w.kind.id}", why(w, age)) ?: return
        val parts = mutableListOf(first)
        if (!s.bark) parts.add(Utterance.Part("", Speech.of("talk.detail.works.post", (w.price - w.pledged).coerceAtLeast(0))))
        out.add(Topic("works.wanted.${w.id}", Kind.WORKS, 0.55 - 0.03 * age.coerceIn(0L, 7L), Utterance(parts), toward = s.at))

        // What's been given toward it, and how much of that was the listener's
        if (w.pledged <= 0) return
        val share = w.pledged.toDouble() / w.price.coerceAtLeast(1)
        val bracket = when {
            share < 0.15 -> "start"
            share < 0.4 -> "some"
            share < 0.75 -> "half"
            else -> "most"
        }
        val funding = Say.part(ask, "works.funding", Speech.of("talk.detail.works.funding.$bracket", w.pledged, w.price)) ?: return
        val mine = ask.player?.let { p -> w.pledges.firstOrNull { it.player == p.uuid }?.marks } ?: 0
        val more = if (!s.bark && mine > 0) Utterance.Part("", Speech.of("talk.detail.works.yours", mine)) else null
        out.add(Topic("works.funding.${w.id}.$bracket", Kind.WORKS, 0.4 + 0.2 * share, Utterance(listOfNotNull(funding, more)), tone = 1, toward = s.at))
    }

    /** Why the town wants [w], [age] market days after the need showed ("The mill stood idle yesterday for want of grain."). */
    private fun why(w: WorksView, age: Long): String {
        val why = w.why.removePrefix("gui.guildmark.works.why.")
        val key = "talk.detail.works.why." + if (why in WHYS) why else "other"
        return if (w.kind == WorksKind.TOWER) Speech.of(key, way(w), Say.ago(age)) else Speech.of(key, Say.ago(age))
    }

    /** The needs the works' wants are found by (WorksWants), each with its words here. */
    private val WHYS = setOf(
        "oven.cracked", "oven.short", "oven.grown", "granary.idle", "granary.low", "granary.blight", "granary.hungry",
        "great_granary.drawn", "tower.waylaid", "tower.troubled", "proposed",
    )

    /** A works paid for and not yet begun, in words only (no voice says it yet): waiting on the wagon with its first materials. */
    private fun funded(s: Seen, out: MutableList<Topic>) {
        if (s.bark || s.w.kind == WorksKind.STALL) return
        val parts = listOf(Utterance.Part("", Speech.of("talk.detail.works.funded", s.kind)), Utterance.Part("", coming(s)))
        out.add(Topic("works.funded.${s.w.id}", Kind.WORKS, 0.45, Utterance(parts), tone = 1, toward = s.at))
    }

    /** The first wagon on its way with [s]'s materials: its road and when it's due; or that none is yet. */
    private fun coming(s: Seen): String {
        val ship = Works.shipments(s.ask.level, s.w.id).minByOrNull { it.due } ?: return "talk.detail.works.funded.none"
        return Speech.of("talk.detail.works.funded.due", way(s.w.capital, ship.road), hour(ship.due))
    }

    // ---- Going up ----

    /** A works going up, pointed at: the stage the builders are at, and how much is built; held up when its yard is empty. */
    private fun building(s: Seen, out: MutableList<Topic>) {
        if (s.bark && !s.near) return
        val w = s.w
        val ask = s.ask
        late(s)?.let(out::add)
        val stage = w.stage
        val detail = Speech.of("talk.detail.works.building" + (stage?.let { ".${it.key}" } ?: ""), s.kind)
        val first = going(ask, w, detail)
        val more = if (!s.bark) share(w)?.let { Utterance.Part("", "talk.detail.works.share.$it") } else null
        out.add(Topic(
            "works.building.${w.id}.${stage?.key ?: "begun"}", Kind.WORKS, if (s.near) 0.6 else 0.5,
            Utterance(listOfNotNull(first, more)), tone = 1, toward = s.at,
        ))
    }

    /**
     * [w] going up (or about to), said with [detail]: a take of works.building that's true of it as it stands, the
     * listener's freshest. Its wordings as recorded: "Have you seen it going up?" (_0) once its footings are going in,
     * "It'll be a fine sight when it's done." (_1) at any stage, "Not long now, and it'll be finished." (_2) once it's
     * topped out or nearly all laid. Text alone while none is recorded.
     */
    private fun going(ask: Ask, w: WorksView, detail: String): Utterance.Part {
        val family = TalkLines.FAMILIES[GOING]
        val building = w.state == WorksState.BUILDING
        val stage = w.stage
        val share = share(w)
        val up = building && (stage != null && stage >= Stage.FOOTINGS || share != null && share != "begun")
        val late = building && (stage != null && stage >= Stage.TOPPING || share == "nearly")
        val keys = (0 until minOf(family?.count ?: 0, 3)).filter { it == 1 || it == 0 && up || it == 2 && late }.map { "talk.${GOING}_$it" }
        val avoid = Topics.avoid(ask.speaker, ask.player)
        val from = keys.filter { it !in avoid }.ifEmpty { keys }
        val take = if (from.isEmpty()) "" else from[ask.speaker.random.nextInt(from.size)]
        return Utterance.Part(take, detail, take.isNotEmpty() && family?.named == true)
    }

    /** How much of [w] is laid, in a word; null while the builders haven't said (no pieces counted yet). */
    private fun share(w: WorksView): String? {
        if (w.total <= 0) return null
        val share = w.laid.toDouble() / w.total
        return when {
            share < 0.1 -> "begun"
            share < 0.4 -> "quarter"
            share < 0.6 -> "half"
            share < 0.85 -> "past_half"
            else -> "nearly"
        }
    }

    /**
     * [s]'s works held up: at a stage that lays materials, with nothing left in its yard while its bill still wants
     * some: what it's out of most, and the wagon bringing it (its road and when it's due), if one is on its way.
     */
    private fun late(s: Seen): Topic? {
        val w = s.w
        if (w.stage !in LAYING || w.here.any { it > 0 }) return null
        val wanting = Works.wanting(s.ask.level, w.id)
        val i = wanting.indices.maxByOrNull { wanting[it] }?.takeIf { wanting[it] > 0 } ?: return null
        val stuff = Say.WORD + "stuff." + Stuff.PAID[i].key
        val ships = Works.shipments(s.ask.level, w.id)
        val ship = ships.filter { it.stuff.getOrElse(i) { 0 } > 0 }.minByOrNull { it.due } ?: ships.minByOrNull { it.due }
        val detail = if (ship != null) Speech.of("talk.detail.works.late.due", stuff, way(w.capital, ship.road), hour(ship.due))
        else Speech.of("talk.detail.works.late.none", stuff)
        val say = Say.line(s.ask, "works.late", detail) ?: return null
        return Topic("works.late.${w.id}.$i.${ship?.caravan ?: -1}", Kind.WORKS, 0.55, say, tone = -1, gesture = Activity.GRUMBLE, toward = s.at)
    }

    // ---- Raised ----

    /** A works raised, for a week, by whose gift as far as the speaker knows; then what it's done for the town since. */
    private fun done(s: Seen, out: MutableList<Topic>) {
        val w = s.w
        val ask = s.ask
        val age = s.town.today - w.done
        val stall = w.kind == WorksKind.STALL
        // A stall of one's own is news to everyone but its owner
        if (age in 0..RAISED_DAYS && !(stall && ask.player?.uuid == w.owner) && (!s.bark || s.near || age <= FRESH)) {
            val detail = when {
                stall -> owner(ask, w)?.let { Speech.of("talk.detail.works.raised.stall", it, Say.ago(age)) }
                else -> patronName(ask, s)?.let { Speech.of("talk.detail.works.raised", Say.ago(age), it) }
            } ?: Speech.of(if (stall) "talk.detail.works.raised.stall.nobody" else "talk.detail.works.raised.nobody", Say.ago(age))
            Say.line(ask, "works.raised.${w.kind.id}", detail)?.let {
                out.add(Topic("works.raised.${w.id}", Kind.WORKS, 0.65 - 0.05 * age, it, tone = 1, toward = s.at))
            }
        }
        if (stall) return
        // What it eased lately: the mill kept turning, the bread kept coming, a band seen off
        for ((what, day) in Works.lastEased(ask.level, w.id)) {
            val since = s.town.today - day
            if (since !in 0..EASED_DAYS || s.bark && since > FRESH && !s.near) continue
            val family = when (what) {
                Works.MILL -> "works.eased.granary"
                Works.BREAD -> "works.eased.oven"
                Works.BAND -> "works.eased.tower"
                else -> continue
            }
            val detail = if (what == Works.BAND) Speech.of("talk.detail.works.eased.band", way(w), Say.ago(since))
            else Speech.of("talk.detail.works.eased.$what", s.kind, Say.ago(since))
            val say = Say.line(ask, family, detail) ?: continue
            out.add(Topic("works.eased.${w.id}.$what.$day", Kind.WORKS, 0.5 - 0.08 * since, say, tone = 1, toward = s.at))
        }
    }

    // ---- The deeds of the works ----

    /**
     * To [s]'s patron, or anyone who gave a quarter of its price, as DEEDS: thanked by those who've heard who paid for
     * it (by name if they know it) or who built it, while it's paid for, going up and after (as long as the word of it
     * stays warm), pointing at it. In passing, only while the news is fresh or near it.
     */
    private fun thanks(s: Seen, out: MutableList<Topic>) {
        val w = s.w
        val ask = s.ask
        val player = ask.player ?: return
        if (ask.occasion !in THANKS || w.kind == WorksKind.STALL || w.state == WorksState.WANTED) return
        if (player.uuid !in TownTalk.givers(w)) return
        val heard = s.heard
        if (heard == null && s.laboured == null) return
        val told = heard?.let { Say.day(ask.level) - Math.floorDiv(it.rumour.born, 24L) }
        if (s.bark && !s.near && (told == null || told > 3)) return
        val detail = when {
            player.uuid != w.patron -> Speech.of("talk.detail.works.patron.share", w.pledges.firstOrNull { it.player == player.uuid }?.marks ?: 0, s.kind)
            w.state == WorksState.FUNDED -> Speech.of("talk.detail.works.patron.funded", s.kind)
            w.state == WorksState.BUILDING -> Speech.of("talk.detail.works.patron.building", s.kind)
            else -> Speech.of("talk.detail.works.patron.raised", s.kind, Say.ago(s.town.today - w.done))
        }
        val say = Say.line(ask, "works.patron", detail) ?: return
        val weight = if (heard != null) 0.35 + 0.4 * heard.rumour.heat else 0.55
        out.add(Topic(
            "works.patron.${w.id}.${w.state.name.lowercase()}", Kind.DEEDS, weight.coerceAtMost(0.75), say, tone = 1,
            gesture = if (s.at != null) Activity.POINT else null, toward = s.at, about = player.uuid,
        ))
    }

    /**
     * [s]'s patron as others hear of them: pointed out while they're close by and in sight ("That's the one who paid
     * for it, over there."), once in two minutes for each patron; else named, if the speaker knows who it was ("We've
     * a friend of the town to thank for it."). Told this way the listener has the word of it too.
     */
    private fun patron(s: Seen, out: MutableList<Topic>) {
        val w = s.w
        val ask = s.ask
        if (w.kind == WorksKind.STALL || w.state == WorksState.WANTED) return
        val patron = w.patron ?: return
        val heard = s.heard
        if (heard == null && s.laboured == null) return
        if (ask.player?.uuid?.let { it in TownTalk.givers(w) } == true) return
        val name = patronName(ask, s)
        val there = there(ask, patron)
        if (there != null) {
            val detail = if (name != null) Speech.of("talk.detail.works.patron_there.named", name, s.kind) else Speech.of("talk.detail.works.patron_there.vague", s.kind)
            val say = Say.line(ask, "works.patron_there", detail) ?: return
            out.add(Topic(
                "works.patron_there.${w.id}.$patron", Kind.WORKS, 0.6, say, tone = 1, gesture = Activity.POINT,
                toward = there.blockPosition().above(), rumour = heard?.rumour?.id?.takeIf { !s.bark }, about = patron,
            ))
            pointed[patron] = ask.level.gameTime
            return
        }
        if (s.bark) return
        val detail = if (name != null) Speech.of("talk.detail.works.patron_other.named", s.kind, name) else Speech.of("talk.detail.works.patron_other.vague", s.kind)
        val say = Say.line(ask, "works.patron_other", detail) ?: return
        val weight = if (heard != null) 0.25 + 0.3 * heard.rumour.heat else 0.4
        out.add(Topic(
            "works.patron_other.${w.id}", Kind.WORKS, weight, say, tone = 1, gesture = if (s.at != null) Activity.POINT else null,
            toward = s.at, rumour = heard?.rumour?.id, about = patron,
        ))
    }

    /** [patron], if they're close by [ask]'s speaker and in their sight (and not the one being told), and not pointed out lately. */
    private fun there(ask: Ask, patron: UUID): ServerPlayer? {
        if (patron == ask.player?.uuid) return null
        val them = ask.level.server.playerList.getPlayer(patron) ?: return null
        if (them.level() !== ask.level || them.isSpectator || them.distanceToSqr(ask.speaker) > THERE * THERE) return null
        if (!ask.speaker.hasLineOfSight(them)) return null
        val last = pointed[patron]
        if (last != null && ask.level.gameTime - last in 0 until THERE_TICKS) return null
        return them
    }

    /**
     * A labourer's own, for a month after their last day on [s]'s works ([TownTalk.laboured]): "I had a hand in
     * building that, you know.", with the day they were last on it, or that they saw it finished.
     */
    private fun labourer(s: Seen, out: MutableList<Topic>) {
        val f = s.laboured ?: return
        val w = s.w
        if (w.state != WorksState.BUILDING && w.state != WorksState.DONE || s.bark && !s.near) return
        val age = (Memory.day(s.ask.level) - f.day).toLong().coerceAtLeast(0L)
        val detail = when {
            w.state == WorksState.BUILDING && age == 0L -> Speech.of("talk.detail.works.labourer.today", s.kind)
            w.state == WorksState.BUILDING -> Speech.of("talk.detail.works.labourer.building", s.kind, Say.ago(age))
            f.day >= w.done -> Speech.of("talk.detail.works.labourer.end", s.kind)
            else -> Speech.of("talk.detail.works.labourer.done", s.kind, Say.ago(age))
        }
        val say = Say.line(s.ask, "works.labourer", detail) ?: return
        val weight = 0.55 - 0.25 * age / Experience.LABOURED.days
        out.add(Topic("works.labourer.${w.id}.${w.state.name.lowercase()}", Kind.WORKS, weight, say, tone = 1, toward = s.at))
    }

    /**
     * A labourer on their building day ([Doing.BUILD]), asked what they're about or saying it in passing as the day's
     * round is said ([DailySource]): the works they're lent to, pointing at it, and where they are in their day: at work
     * for the crew ("I'm on the granary today, till sunset.", and how much of it is built), waiting about the site for
     * the builders to set them on, or on their way there. Only where the builders have a site for them ([Labour.site]).
     */
    private fun labouring(ask: Ask, out: MutableList<Topic>) {
        val life = ask.life ?: return
        val p = ask.speaker
        if (!Works.enabled() || Say.child(p) || Days.stretch(life, ask.level.overworldClockTime).doing != Doing.BUILD) return
        val site = Labour.site(p, life) ?: return
        val w = Works.at(ask.level, site)?.takeIf { it.state == WorksState.BUILDING || it.state == WorksState.FUNDED } ?: return
        val kind = "fortune.guildmark.works.${w.kind.id}"
        val how = when {
            Labour.lentNow(p) -> "at"
            p.blockPosition().distSqr(site) <= SITE * SITE -> "waiting"
            else -> "going"
        }
        val detail = Speech.of(if (how == "at") "talk.detail.works.labour" else "talk.detail.works.labour.$how", kind)
        val first = going(ask, w, detail)
        val more = if (how == "at" && w.state == WorksState.BUILDING && ask.occasion != Occasion.BARK) share(w)?.let { Utterance.Part("", "talk.detail.works.share.$it") } else null
        out.add(Topic(
            "works.labouring.${w.id}.$how", Kind.DOING, DailySource.weight(ask), Utterance(listOfNotNull(first, more)),
            tone = 1, gesture = Activity.POINT, toward = place(w) ?: site,
        ))
    }

    /** A child at play while a works is going up ("I'm the master builder! You carry the stones!"). */
    private fun builders(s: Seen, out: MutableList<Topic>) {
        if (s.w.state != WorksState.BUILDING || s.bark && !s.near) return
        val say = Say.line(s.ask, "child.builders", null) ?: return
        out.add(Topic("works.child.${s.w.id}", Kind.WORKS, if (s.near) 0.55 else 0.35, say, tone = 1))
    }

    // ---- "About the works..." ----

    override fun choice(ask: Ask): Choice? {
        if (Say.child(ask.speaker) || ask.player == null || ask.resident == null) return null
        val town = town(ask) ?: return null
        if (ranked(town).isEmpty()) return null
        val pressing = when {
            town.works.any { it.state == WorksState.BUILDING || it.state == WorksState.DONE && town.today - it.done in 0L..3L } -> 0.7
            town.works.any { it.state == WorksState.WANTED || it.state == WorksState.FUNDED } -> 0.6
            else -> 0.3
        }
        return Choice(ASK, Component.translatable("gui.guildmark.talk.works"), pressing = pressing)
    }

    /**
     * What's wanted, what's going up and what's been raised, and by whom: the first of them as it would be talked of
     * (pointing at it), then the others in a word each, as many as there's room for.
     */
    override fun answer(ask: Ask, choice: String): Utterance? {
        if (choice != ASK) return null
        val town = town(ask) ?: return null
        val all = seen(ask, town).associateBy { it.w.id }
        val ranked = ranked(town).mapNotNull { all[it.id] }
        val first = ranked.firstOrNull() ?: return null
        val parts = ArrayList(head(first))
        for (s in ranked.drop(1)) {
            if (parts.size >= PARTS) break
            parts.add(Utterance.Part("", summary(s)))
        }
        return Utterance(parts.take(PARTS), first.at)
    }

    /** [town]'s works to answer of, most worth telling first: going up, paid for, wanted (the most given first), raised (the newest first). */
    private fun ranked(town: Town): List<WorksView> = town.works
        .filter { it.kind != WorksKind.STALL || it.state == WorksState.DONE && town.today - it.done in 0..RAISED_DAYS }
        .sortedWith(compareBy<WorksView> { rank(town, it) }.thenByDescending { if (it.state == WorksState.DONE) it.done else it.pledged.toLong() })

    private fun rank(town: Town, w: WorksView): Int = when (w.state) {
        WorksState.BUILDING -> 0
        WorksState.FUNDED -> 1
        WorksState.WANTED -> 2
        WorksState.DONE -> if (town.today - w.done in 0..RAISED_DAYS) 3 else 4
    }

    /** The answer's first works, as it's talked of: going up (or held up), paid for, wanted and why, or raised and by whom. */
    private fun head(s: Seen): List<Utterance.Part> {
        val w = s.w
        val ask = s.ask
        val kind = s.kind
        return when (w.state) {
            WorksState.BUILDING -> {
                val late = late(s)
                val first = late?.say?.parts?.firstOrNull()
                    ?: going(ask, w, Speech.of("talk.detail.works.building" + (w.stage?.let { ".${it.key}" } ?: ""), kind))
                listOfNotNull(first, share(w)?.let { Utterance.Part("", "talk.detail.works.share.$it") })
            }
            WorksState.FUNDED -> listOf(Utterance.Part("", Speech.of("talk.detail.works.funded", kind)), Utterance.Part("", coming(s)))
            WorksState.WANTED -> {
                val bracket = if (w.pledged > 0) Utterance.Part("", Speech.of("talk.detail.works.ask.pledged", w.pledged, w.price)) else null
                listOfNotNull(Say.part(ask, "works.wanted.${w.kind.id}", why(w, s.town.today - w.wanted)), bracket)
            }
            WorksState.DONE -> {
                val age = s.town.today - w.done
                val detail = if (w.kind == WorksKind.STALL) owner(ask, w)?.let { Speech.of("talk.detail.works.raised.stall", it, Say.ago(age)) }
                else patronName(ask, s)?.let { Speech.of("talk.detail.works.raised", Say.ago(age), it) }
                listOfNotNull(Say.part(ask, "works.raised.${w.kind.id}", detail ?: Speech.of("talk.detail.works.raised.nobody", Say.ago(age))))
            }
        }
    }

    /** Another works, in a word: "The town wants a watchtower too: 40 of 180 Marks pledged." */
    private fun summary(s: Seen): String {
        val w = s.w
        return when (w.state) {
            WorksState.WANTED -> Speech.of("talk.detail.works.ask.wanted", s.kind, w.pledged, w.price)
            WorksState.FUNDED -> Speech.of("talk.detail.works.ask.funded", s.kind)
            WorksState.BUILDING -> Speech.of("talk.detail.works.ask.building", s.kind)
            WorksState.DONE -> {
                val ago = Say.ago(s.town.today - w.done)
                val by = if (w.kind == WorksKind.STALL) owner(s.ask, w) else patronName(s.ask, s)
                if (by != null) Speech.of("talk.detail.works.ask.raised", s.kind, ago, by) else Speech.of("talk.detail.works.ask.raised.nobody", s.kind, ago)
            }
        }
    }

    // ---- Words ----

    /** Where [w] stands to point at: its post while it's wanted or paid for, its lot's middle once it's going up. */
    private fun place(w: WorksView): BlockPos? {
        val site = w.site ?: return null
        return if (w.state == WorksState.WANTED || w.state == WorksState.FUNDED) site.post else site.lot.center
    }

    /** The road [w] watches (a tower's), as a Speech argument ("the north road"). */
    private fun way(w: WorksView): String = if (w.road >= 0) way(w.capital, w.road) else "road.guildmark.way.any"

    private fun way(capital: Long, road: Int): String = "road.guildmark.way." + FortuneReasons.side(capital, road)

    /** The hour clock time [at] falls in, as a Speech argument ("two in the afternoon"), as the road's talk has it. */
    private fun hour(at: Long): String = "quest.guildmark.caravan.hour." + Caravans.clockOf(Math.floorDiv(at, 1000L))

    /**
     * [s]'s patron by name, as a Speech argument, if [ask]'s speaker knows it: they heard who paid for it as it happened
     * or a little grown in the telling, or they were on its crew.
     */
    private fun patronName(ask: Ask, s: Seen): String? {
        val heard = s.heard
        if (heard != null && heard.heard.warp <= 1) heard.rumour.args.getOrNull(2)?.takeIf { it.length > 1 && it.startsWith("=") }?.let { return it }
        if (s.laboured == null) return null
        val patron = s.w.patron ?: return null
        val name = s.w.pledges.firstOrNull { it.player == patron }?.name?.takeIf { it.isNotEmpty() } ?: Say.name(ask.level, patron) ?: return null
        return Witness.named(name)
    }

    /** A stall's owner by name, as a Speech argument: the name it was paid for under, or the one the world knows. */
    private fun owner(ask: Ask, w: WorksView): String? {
        val owner = w.owner ?: return null
        val name = w.pledges.firstOrNull { it.player == owner }?.name?.takeIf { it.isNotEmpty() } ?: Say.name(ask.level, owner) ?: return null
        return Witness.named(name)
    }
}
