package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/** Something about how a person goes about their day. */
enum class Trait {
    /** Up before the rest of the street. */
    EARLY,
    /** Up late, and late to bed. */
    LATE,
    /** At every service. */
    DEVOUT,
    /** Fond of the tavern. */
    DRINKER,
    /** Always stopping for a word. */
    CHATTY,
    /** Keeps to themselves. */
    SHY,
    /** Works long and hard. */
    DILIGENT,
    /** Would rather not. */
    IDLE,
    CHEERFUL,
    GRUMPY;

    val bit: Int get() = 1 shl ordinal
}

/**
 * One of a town's people, kept with the world whether or not their body is about: who they are (a name, a trade,
 * a look), where they live and sleep, and how they go about things. The [Townsperson] whose [Townsperson.resident]
 * is [id] acts them out. [planned] is their place in the city plan's census (see [CityPlaces.planned]), or -1.
 */
class Resident(
    val id: Int,
    var uuid: UUID?,
    val capital: BlockPos,
    val planned: Int,
    val given: String,
    val family: String,
    val role: TownRole,
    var look: Int,
    /** Their home's lodging id in [CityPlaces.lodgings], or -1. */
    var home: Int,
    /** The head of their bed, if they have one. */
    var bed: BlockPos?,
    /** Whether a bed has been made up for them (so one isn't made twice). */
    var furnished: Boolean,
    /** Their ways, a bit for each [Trait]. A tale may flip one for good (living-world/stories/design.md 6.5). */
    var traits: Int,
    var alive: Boolean,
    /** The day they died, or -1 (a death before the world's first day stands on day 0: see [Residents.diedOn]). */
    var died: Long,
    /** The day they came to live here (before the world's first, if the clock was set back past it: see [Residents.back]). */
    var since: Long,
    /**
     * The day they left in hard times (living-world/design-influence.md section 6), or -1. While they're away [alive]
     * is false and [died] stays -1.
     */
    var left: Long = -1L,
    /**
     * The vanilla profession they had before they were taken in (its id, such as "minecraft:farmer"), or "": who keeps
     * which stall in a village goes by it (living-world/villages/design.md section 7.6). A capital's people have none.
     */
    val craft: String = "",
    /**
     * The day (as [VillageBoards.day] counts) they last pinned a note on their village's board, or -1: kept here for a resident in
     * place of the villager's LAST_PINNED_DAY (living-world/villages/design.md section 6.5).
     */
    var pinned: Long = -1L,
) {
    val name: String get() = "$given $family"

    fun has(trait: Trait): Boolean = traits and trait.bit != 0

    companion object {
        /** Someone in from outside for the day (see [Visitors]): no one the town keeps a record of. */
        fun visitor(capital: BlockPos, role: TownRole): Resident =
            Resident(-1, null, capital, -1, "", "", role, 0, -1, null, true, 0, true, -1L, 0L)

        /**
         * The last of a resident's fields, in a group of their own: a record codec holds no more than sixteen, and
         * [CODEC] had sixteen. A group nested in another is flat in what's saved, so older worlds read as they did.
         */
        private class Tail(val left: Long, val craft: String, val pinned: Long)

        private val TAIL: MapCodec<Tail> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.LONG.optionalFieldOf("left", -1L).forGetter(Tail::left),
                Codec.STRING.optionalFieldOf("craft", "").forGetter(Tail::craft),
                Codec.LONG.optionalFieldOf("pinned", -1L).forGetter(Tail::pinned),
            ).apply(i, ::Tail)
        }

        val CODEC: Codec<Resident> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Resident::id),
                UUIDUtil.CODEC.optionalFieldOf("uuid").forGetter { Optional.ofNullable(it.uuid) },
                BlockPos.CODEC.fieldOf("capital").forGetter(Resident::capital),
                Codec.INT.optionalFieldOf("planned", -1).forGetter(Resident::planned),
                Codec.STRING.fieldOf("given").forGetter(Resident::given),
                Codec.STRING.fieldOf("family").forGetter(Resident::family),
                Codec.STRING.fieldOf("role").forGetter { it.role.id },
                Codec.INT.optionalFieldOf("look", 0).forGetter(Resident::look),
                Codec.INT.optionalFieldOf("home", -1).forGetter(Resident::home),
                BlockPos.CODEC.optionalFieldOf("bed").forGetter { Optional.ofNullable(it.bed) },
                Codec.BOOL.optionalFieldOf("furnished", false).forGetter(Resident::furnished),
                Codec.INT.optionalFieldOf("traits", 0).forGetter(Resident::traits),
                Codec.BOOL.optionalFieldOf("alive", true).forGetter(Resident::alive),
                Codec.LONG.optionalFieldOf("died", -1L).forGetter(Resident::died),
                Codec.LONG.optionalFieldOf("since", 0L).forGetter(Resident::since),
                TAIL.forGetter { Tail(it.left, it.craft, it.pinned) },
            ).apply(i) { id, uuid, capital, planned, given, family, role, look, home, bed, furnished, traits, alive, died, since, tail ->
                Resident(
                    id, uuid.orElse(null), capital, planned, given, family, TownRole.byName(role) ?: TownRole.CITIZEN, look,
                    home, bed.orElse(null), furnished, traits, alive, died, since, tail.left, tail.craft, tail.pinned,
                )
            }
        }
    }
}

/**
 * Every town's people (see [Resident]), kept with the overworld, and [early]: the days the dead died on that a clock
 * set back took before the world's first day (see [back]), by id.
 */
class Residents(
    private val people: MutableMap<Int, Resident> = HashMap(),
    private var next: Int = 1,
    private val early: MutableMap<Int, Long> = HashMap(),
) : SavedData() {
    private val byUuid = HashMap<UUID, Resident>().also { map -> for (r in people.values) r.uuid?.let { map[it] = r } }
    // Someone new in the plan's place of one who died has it after them, the newest last (as [add] has it)
    private val byPlan = HashMap<Pair<BlockPos, Int>, Resident>().also { map -> for (r in people.values.sortedBy { it.id }) if (r.planned >= 0) map[r.capital to r.planned] = r }
    // Each town's people, in the order [people] has them (the newest last, as [add] has it); no one leaves [people], the dead stay in it
    private val byTown = HashMap<BlockPos, MutableList<Resident>>().also { map -> for (r in people.values) map.getOrPut(r.capital) { ArrayList() }.add(r) }

    operator fun get(id: Int): Resident? = people[id]

    fun of(p: Townsperson): Resident? = if (p.resident >= 0) people[p.resident] else null

    /** Everyone living (or who lived) in the capital at [capital]. */
    fun of(capital: BlockPos): List<Resident> = byCentre(capital)

    /**
     * Everyone living (or who lived) in the town at [centre], a capital's or a village's bell, without a look at
     * everyone else: a list of its own, so people may be taken in (see [add]) while it is read.
     */
    fun byCentre(centre: BlockPos): List<Resident> = byTown[centre]?.toList() ?: listOf()

    fun all(): Collection<Resident> = people.values

    /** The plan's resident [planned] of the capital at [capital], if they've been taken in already. */
    fun planned(capital: BlockPos, planned: Int): Resident? = byPlan[capital to planned]

    /**
     * [r], a child, come of age (living-world/villages/design.md 5.6, part V13): the same person under the same id, in the place
     * [r] had, now with [role] for a trade and [look] for its look, and no body until one is made for them (the child's body is
     * nobody's from now on). The new record, which is in [r]'s place.
     */
    fun grownUp(r: Resident, role: TownRole, look: Int): Resident {
        val grown = Resident(
            r.id, null, r.capital, r.planned, r.given, r.family, role, look, r.home, r.bed, r.furnished, r.traits, r.alive, r.died, r.since,
            r.left, r.craft, r.pinned,
        )
        r.uuid?.let { if (byUuid[it] === r) byUuid.remove(it) }
        add(grown)
        return grown
    }

    fun add(r: Resident) {
        val old = people.put(r.id, r)
        if (old !== r) {
            if (old != null && old.capital != r.capital) byTown[old.capital]?.remove(old)
            // Where the one it replaces stood, else last
            val town = byTown.getOrPut(r.capital) { ArrayList() }
            val at = if (old == null) -1 else town.indexOf(old)
            if (at >= 0) town[at] = r else town.add(r)
        }
        r.uuid?.let { byUuid[it] = r }
        if (r.planned >= 0) byPlan[r.capital to r.planned] = r
        setDirty()
    }

    fun newId(): Int = next++.also { setDirty() }

    fun changed() = setDirty()

    /**
     * [id] is put away from [day] on, as a captive of a raid is (living-world/villages/design.md 11.3, part V10): not alive and
     * not dead, [Resident.left] the day, their home and bed kept for them (as a household away in hard times), so no second body
     * of them is made at home and nobody new is brought in to their place. Their body in the outlaws' camp is still theirs.
     */
    fun away(id: Int, day: Long) {
        val r = people[id] ?: return
        if (!r.alive) return
        r.alive = false
        r.left = day
        setDirty()
    }

    /** [id], put away (see [away]), is given back with [p] for a body: alive again, [p] their body, by their own name. */
    fun restore(id: Int, p: Townsperson) {
        val r = people[id] ?: return
        if (r.alive || r.died >= 0) return
        r.alive = true
        r.left = -1L
        r.uuid = p.uuid
        p.resident = id
        add(r)
        named(p, r)
    }

    /**
     * The day [r] died on, as the town's talk counts it: [Resident.died], but for a death a clock set back took before
     * the world's first day, whose record stands on day 0 ([Resident.died] is never below 0 but for -1, "not dead") and
     * whose own day is below it (still -1 for the living and those away).
     */
    fun diedOn(r: Resident): Long = early[r.id] ?: r.died

    /**
     * The clock set back [days] days (from `Comings.setBack`; the world's days, as [VillageBoards.day] counts them, from
     * six in the morning): everyone's days go back with it, so the town's talk of the newly come and of grief goes by the
     * days they had, not on from the old clock. The day they came goes back as far before the world's first as it must,
     * and so does the day a death was (kept apart in [early]); the day they left stops at the first.
     */
    fun back(days: Long) {
        if (days <= 0L || people.isEmpty()) return
        for (r in people.values) {
            r.since -= days
            if (r.left >= 0) r.left = maxOf(0L, r.left - days)
            if (r.pinned >= 0) r.pinned = maxOf(-1L, r.pinned - days)
            if (r.died >= 0) {
                val day = diedOn(r) - days
                r.died = maxOf(0L, day)
                if (day < 0) early[r.id] = day else early.remove(r.id)
            }
        }
        setDirty()
    }

    companion object {
        private class Early(val id: Int, val day: Long)

        private val EARLY: Codec<Early> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Early::id),
                Codec.LONG.fieldOf("day").forGetter(Early::day),
            ).apply(i, ::Early)
        }

        private val CODEC: Codec<Residents> = RecordCodecBuilder.create { i ->
            i.group(
                Resident.CODEC.listOf().optionalFieldOf("people", listOf()).forGetter { it.people.values.toList() },
                Codec.INT.optionalFieldOf("next", 1).forGetter { it.next },
                EARLY.listOf().optionalFieldOf("early", listOf()).forGetter { r -> r.early.entries.sortedBy { it.key }.map { Early(it.key, it.value) } },
            ).apply(i) { people, next, early ->
                Residents(people.associateBy { it.id }.toMutableMap(), next, early.associate { it.id to it.day }.toMutableMap())
            }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "residents"), Supplier { Residents() }, CODEC)

        fun get(level: ServerLevel): Residents = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /**
         * Takes [p] in as one of their capital's people, once its places are known: the plan's resident they were
         * spawned as, with the home and bed the plan's [CityPlaces.housing] gives them, a name and their ways.
         * Townsfolk outside a capital, or that the plan never set down, are taken in with what they have.
         */
        fun adopt(level: ServerLevel, p: Townsperson) {
            val data = get(level)
            if (p.resident >= 0 && data[p.resident] != null) return
            data.byUuid[p.uuid]?.let {
                p.resident = it.id
                named(p, it)
                return
            }
            val capital = Capitals.covering(level, p.home ?: p.blockPosition())
            val places = capital?.let { CityPlaces.of(level, it) }
            // Its places are still being worked out: next time
            if (capital != null && places == null && !CityPlaces.failed(capital)) return
            val center = capital?.center ?: p.town?.center ?: Towns.at(level, p.blockPosition())?.takeIf { it.village }?.center ?: p.blockPosition()
            // Someone already living the plan's life for this spot (a second body set down for it) is someone new
            val planned = (if (places != null) places.planned(p.role, p.home ?: p.blockPosition()) else -1)
                .takeIf { it >= 0 && data.planned(center, it)?.alive != true } ?: -1
            val (lodging, bed) = if (places != null && planned >= 0) places.homeOf(planned) else (places?.lodgingAt(p.home ?: p.blockPosition()) to null)
            val id = data.newId()
            val seed = center.asLong() * 31 + id
            val woman = Names.woman(p.role, p.look, seed)
            // A household shares a name: the first grown-up in a home gives it, the children take it
            val style = capital?.style
            val kin = if (lodging != null) data.people.values.firstOrNull { it.capital == center && it.home == lodging.id && it.alive && it.role != TownRole.CHILD } else null
            val family = if (kin != null && (p.role == TownRole.CHILD || data.people.values.none { it.capital == center && it.home == lodging?.id && it.role != TownRole.CHILD && it.id != kin.id }))
                kin.family else Names.family(style, seed)
            val r = Resident(
                id, p.uuid, center, planned, Names.given(style, woman, seed), family, p.role, p.look,
                lodging?.id ?: -1, bed, false, traits(seed), true, -1L, VillageBoards.day(level),
            )
            data.add(r)
            p.resident = id
            named(p, r)
            io.github.vaspyyy.guildmark.townsfolk.talk.Witness.newcomer(level, r)
        }

        /** Gives [p] their name, shown when someone looks at them. */
        fun named(p: Townsperson, r: Resident) {
            if (p.customName?.string != r.name) p.customName = Component.literal(r.name)
        }

        /** A newcomer's ways, fixed by [seed] (see [Comings]). */
        internal fun traits(seed: Long): Int {
            val random = java.util.Random(seed xor 0x5DEECE66DL)
            var bits = 0
            // Two or three of these, never both ends of a pair
            val pairs = listOf(Trait.EARLY to Trait.LATE, Trait.DEVOUT to Trait.DRINKER, Trait.CHATTY to Trait.SHY, Trait.DILIGENT to Trait.IDLE, Trait.CHEERFUL to Trait.GRUMPY)
            for ((a, b) in pairs) when (random.nextInt(5)) {
                0 -> bits = bits or a.bit
                1 -> bits = bits or b.bit
            }
            return bits
        }

        /**
         * A body loaded with its chunk that's no one's now: one of a household away in hard times (living-world/
         * design-influence.md section 6: their bodies were taken away, or should have been), an old body of someone
         * whose body has been made anew since (back home, made at home out of sight), or of someone dead whose chunk
         * was last saved before they died: gone at once, as BuilderCrew's are. One the records don't know as theirs
         * (its chunk saved after the records last were, the game stopped between) is someone new, taken in afresh, so
         * no two bodies ever share a record; but a record saved before it had any body takes back one of its trade and
         * look. Set listening by [Comings.register].
         */
        fun onJoin(event: EntityJoinLevelEvent) {
            if (!event.loadedFromDisk()) return
            val p = event.entity as? Townsperson ?: return
            if (p.resident < 0) return
            val level = event.level as? ServerLevel ?: return
            val data = get(level)
            val r = data[p.resident]
            // Their body made after the records were last saved, the game stopped between: it's theirs
            if (r != null && r.uuid == null && r.role == p.role && r.look == p.look) {
                r.uuid = p.uuid
                data.add(r)
                named(p, r)
            }
            if (r == null || r.uuid != p.uuid && !Comings.renewed(level, r)) {
                p.resident = -1
                p.customName = null
                return
            }
            // Away, but not their body in an outlaws' camp (a raid's captive is away from home and still theirs: Captives, V10)
            val away = !r.alive && r.died < 0 && r.left >= 0 && p.captiveOf < 0
            val dead = !r.alive && r.died >= 0
            val stale = r.alive && r.uuid != p.uuid && Comings.renewed(level, r)
            // A tale that holds their day makes its own body for them, so this one goes too (tales/)
            val held = Tales.holds(r.id) && p.tale < 0
            if (away || dead || stale || held) event.isCanceled = true
        }

        /** [p] died: their life here ends (and their home and trade wait for someone new). */
        fun died(level: ServerLevel, p: Townsperson) {
            val r = get(level).of(p) ?: return
            // How, for a village's dead list (living-world/villages/design.md 11.4)
            pass(level, r, p, io.github.vaspyyy.guildmark.village.live.trouble.Deaths.cause(level, p))
        }

        /**
         * The record half of a death, which needs no body: [r]'s life here ends, the day it did is kept, their bed is free,
         * the town mourns them and, if [p] is their body, the street hears of it. What a death in a tale or a siege adds
         * goes here as one line each, so a resident who is put to rest with no body is as dead on the record as one who fell.
         * [cause] is how they died in words, if it's known ("fell in the siege"), for a village's dead list.
         */
        fun pass(level: ServerLevel, r: Resident, p: Townsperson? = null, cause: String = "") {
            r.alive = false
            r.died = VillageBoards.day(level)
            // Their bed is free for whoever comes to live there next
            r.bed?.let { Furnish.release(level, it) }
            r.bed = null
            get(level).changed()
            Fortunes.note(level, r.capital.asLong(), Noted.MOURNING, listOf(r.name))
            if (p != null) Witness.died(level, p) else Witness.passed(level, r, Witness.homeDoor(r))
            // A tale that cast them copes with it (tales/)
            Tales.died(level, r)
            // A village's dead list (living-world/villages/design.md section 11.4); a capital's people are in none
            io.github.vaspyyy.guildmark.village.live.Villages.died(level, r, cause)
            // On the disk in a second or so, not at the next autosave (m7 leftovers item 3: a killed game kept them alive with no body)
            DeathSaves.due(level)
        }
    }
}
