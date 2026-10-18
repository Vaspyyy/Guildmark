package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Experience
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import net.minecraft.core.BlockPos

/**
 * The speaker's household (section 1), said only to those they've met and more to friends: a death in the house (as
 * heavy as anything they carry), a newcomer's first days, a full house, the children, no bed of their own yet, going
 * hungry or being robbed lately (from what they remember), the old folk's own complaints, and, to those they know
 * well, the neighbours' ways. Everything comes from the town's record of its people: who lives where, since when,
 * who died and when, and how each goes about their day.
 */
object HouseholdTopics : TopicSource {
    override val id = "household"

    /** What a household matter weighs (section 1). */
    private const val HOME = 0.3

    /** How long a death in the house stays on their mind (days), from all of it to under a third. */
    private const val GRIEF = 30L

    /** How near another's home must be for them to count as a neighbour (blocks, between the homes' middles). */
    private const val NEIGHBOUR = 32.0

    /** How many days after they came someone is still new. */
    private const val NEW = 2L

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in Say.TALK || !Days.enabled() || Say.child(ask.speaker)) return
        val r = ask.resident ?: return
        if (!r.alive) return
        val level = ask.level
        val today = Say.day(level)
        val people = Residents.get(level).of(r.capital)
        val home = if (r.home >= 0) people.filter { it.home == r.home && it.id != r.id } else listOf()
        val living = home.filter { it.alive }

        // A death in the house, while they lived there
        home.filter { !it.alive && it.died >= r.since && today - it.died in 0..GRIEF }.maxByOrNull { it.died }?.let { dead ->
            val days = today - dead.died
            val detail = when (days) {
                0L -> Speech.of("talk.detail.home.grief.0", "=${dead.name}")
                1L -> Speech.of("talk.detail.home.grief.1", "=${dead.name}")
                else -> Speech.of("talk.detail.home.grief", "=${dead.name}", days)
            }
            add(ask, out, "home.grief", "household.grief.${dead.id}", 1.0 - 0.7 * days / GRIEF, -1, detail, Activity.SIGH)
        }

        // Only just moved in
        if (newcomer(r, people, today)) {
            add(ask, out, "home.new", "household.new.${r.id}", 0.4, 0, "talk.detail.home.new.${(today - r.since).coerceIn(0L, NEW)}")
        }

        // A full house, and the children in it
        if (living.size + 1 >= 4) add(ask, out, "home.busy", "household.busy.${living.size + 1}", HOME, 0, Speech.of("talk.detail.home.count", living.size + 1))
        val children = living.filter { it.role == TownRole.CHILD }
        when {
            children.size >= 2 -> Speech.of("talk.detail.home.children", "=${children[0].given}", "=${children[1].given}")
            children.size == 1 -> Speech.of("talk.detail.home.child", "=${children[0].given}")
            else -> null
        }?.let { add(ask, out, "home.children", "household.children." + children.joinToString("+") { it.id.toString() }, HOME, 1, it) }

        // No bed of their own yet
        if (r.home >= 0 && r.bed == null) add(ask, out, "home.nobed", "household.nobed.${r.id}", HOME + 0.05, -1, null)

        // What befell them lately, as they remember it
        if (Say.memory()) {
            val felt = TownTalk.felt(level, r)
            for (experience in listOf(Experience.HUNGRY to "home.hungry", Experience.ROBBED to "home.robbed")) {
                val f = felt.firstOrNull { it.experience == experience.first } ?: continue
                val days = today - f.day
                if (days < 0 || days > experience.first.days) continue
                add(ask, out, experience.second, "household.${experience.first.name.lowercase()}.${f.day}", 1.0 - 0.6 * days / experience.first.days, -1, Say.agoDetail(days), Activity.SIGH)
            }
        }

        // The old folk's own
        if (Say.timbre(ask.speaker) == "old") add(ask, out, "home.old", "household.old.$today", HOME - 0.05, 0, null)

        // The neighbours' ways, to those they know well
        neighbour(ask, r, people, today)?.let { (n, trait) ->
            val say = Say.line(ask, "home.neighbours", Speech.of("talk.detail.home.neighbour.${trait.name.lowercase()}", "=${n.name}")) ?: return@let
            out.add(Topic("household.neighbours.${n.id}.${trait.name.lowercase()}", Kind.HOUSEHOLD, HOME, say, Familiarity.ACQUAINTED))
        }
    }

    private fun add(ask: Ask, out: MutableList<Topic>, family: String, id: String, weight: Double, tone: Int, detail: String?, gesture: Activity? = null) {
        val say = Say.line(ask, family, detail) ?: return
        out.add(Topic(id, Kind.HOUSEHOLD, weight, say, Familiarity.MET, tone, gesture))
    }

    /**
     * Whether [r] has only just come to live here: in the last two days, after the town's first people were taken
     * in, into the place in the plan of someone who'd died. (Those taken in the first time their street is loaded came
     * then only as far as the record goes, so they're never new.)
     */
    fun newcomer(r: Resident, people: List<Resident>, today: Long): Boolean {
        if (today - r.since !in 0..NEW || r.planned < 0) return false
        val first = people.minOfOrNull { it.since } ?: return false
        if (r.since <= first) return false
        return people.any { it.id != r.id && it.planned == r.planned && !it.alive && it.died in 0..r.since }
    }

    /** A neighbour of [r]'s (another household close by) with a way worth remarking on, picked afresh each day. */
    private fun neighbour(ask: Ask, r: Resident, people: List<Resident>, today: Long): Pair<Resident, Trait>? {
        val places = ask.life?.places ?: return null
        val mine = middle(places, r.home) ?: return null
        val near = people.filter { n ->
            n.alive && n.id != r.id && n.home >= 0 && n.home != r.home && n.role != TownRole.CHILD && n.traits != 0 &&
                middle(places, n.home)?.let { it.distSqr(mine) <= NEIGHBOUR * NEIGHBOUR } == true
        }
        if (near.isEmpty()) return null
        val n = near[Math.floorMod(today * 31 + r.id, near.size.toLong()).toInt()]
        val traits = Trait.entries.filter { n.has(it) }
        if (traits.isEmpty()) return null
        return n to traits[Math.floorMod(today + n.id, traits.size.toLong()).toInt()]
    }

    /** The middle of lodging [home] of [places], if it's one of theirs. */
    private fun middle(places: CityPlaces, home: Int): BlockPos? = places.lodgings[home]?.box?.center
}
