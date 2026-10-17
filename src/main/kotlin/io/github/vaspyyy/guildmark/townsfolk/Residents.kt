package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.Capitals
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
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
    val traits: Int,
    var alive: Boolean,
    /** The day they died, or -1. */
    var died: Long,
    /** The day they came to live here. */
    val since: Long,
) {
    val name: String get() = "$given $family"

    fun has(trait: Trait): Boolean = traits and trait.bit != 0

    companion object {
        /** Someone in from outside for the day (see [Visitors]): no one the town keeps a record of. */
        fun visitor(capital: BlockPos, role: TownRole): Resident =
            Resident(-1, null, capital, -1, "", "", role, 0, -1, null, true, 0, true, -1L, 0L)

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
            ).apply(i) { id, uuid, capital, planned, given, family, role, look, home, bed, furnished, traits, alive, died, since ->
                Resident(
                    id, uuid.orElse(null), capital, planned, given, family, TownRole.byName(role) ?: TownRole.CITIZEN, look,
                    home, bed.orElse(null), furnished, traits, alive, died, since,
                )
            }
        }
    }
}

/** Every town's people (see [Resident]), kept with the overworld. */
class Residents(private val people: MutableMap<Int, Resident> = HashMap(), private var next: Int = 1) : SavedData() {
    private val byUuid = HashMap<UUID, Resident>().also { map -> for (r in people.values) r.uuid?.let { map[it] = r } }
    private val byPlan = HashMap<Pair<BlockPos, Int>, Resident>().also { map -> for (r in people.values) if (r.planned >= 0) map[r.capital to r.planned] = r }

    operator fun get(id: Int): Resident? = people[id]

    fun of(p: Townsperson): Resident? = if (p.resident >= 0) people[p.resident] else null

    /** Everyone living (or who lived) in the capital at [capital]. */
    fun of(capital: BlockPos): List<Resident> = people.values.filter { it.capital == capital }

    fun all(): Collection<Resident> = people.values

    /** The plan's resident [planned] of the capital at [capital], if they've been taken in already. */
    fun planned(capital: BlockPos, planned: Int): Resident? = byPlan[capital to planned]

    fun add(r: Resident) {
        people[r.id] = r
        r.uuid?.let { byUuid[it] = r }
        if (r.planned >= 0) byPlan[r.capital to r.planned] = r
        setDirty()
    }

    fun newId(): Int = next++.also { setDirty() }

    fun changed() = setDirty()

    companion object {
        private val CODEC: Codec<Residents> = RecordCodecBuilder.create { i ->
            i.group(
                Resident.CODEC.listOf().optionalFieldOf("people", listOf()).forGetter { it.people.values.toList() },
                Codec.INT.optionalFieldOf("next", 1).forGetter { it.next },
            ).apply(i) { people, next -> Residents(people.associateBy { it.id }.toMutableMap(), next) }
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
            val center = capital?.center ?: p.town?.center ?: p.blockPosition()
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
        }

        /** Gives [p] their name, shown when someone looks at them. */
        fun named(p: Townsperson, r: Resident) {
            if (p.customName?.string != r.name) p.customName = Component.literal(r.name)
        }

        private fun traits(seed: Long): Int {
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

        /** [p] died: their life here ends (and their home and trade wait for someone new). */
        fun died(level: ServerLevel, p: Townsperson) {
            val data = get(level)
            val r = data.of(p) ?: return
            r.alive = false
            r.died = VillageBoards.day(level)
            data.changed()
        }
    }
}
