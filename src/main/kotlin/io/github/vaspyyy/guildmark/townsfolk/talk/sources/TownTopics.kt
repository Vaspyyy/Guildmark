package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.monster.Monster
import kotlin.math.sqrt

/**
 * What's happened in the speaker's streets (section 1's events): a neighbour buried, a new family in the street, a
 * monster in the streets last night, an outlaw band camped near the town, and what the crier cried at noon. Each from
 * the world as it stands: the town's record of its people, the monsters a player saw inside the walls, the bands at
 * large, and the market's news the crier cries.
 */
object TownTopics : TopicSource {
    override val id = "town"

    private val OCCASIONS = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** How near another's home must be for them to count as of the speaker's street (blocks, between the homes' middles). */
    private const val STREET = 48.0

    /** How far out a band's camp is still talked of in town (blocks from its heart). */
    private const val BAND = 400.0

    /** A monster seen inside a town's walls on the night of [day] (town days, from six in the morning): what it was. */
    class Sighting(val day: Long, val what: String)

    /** The last night a monster was seen in each town, by the town's heart, packed; kept in memory only. */
    private val sightings = HashMap<Long, Sighting>()

    /** Every five seconds after dark: any monster inside the walls of a town a player is in is noted. */
    fun watch(level: ServerLevel) {
        if (!Days.enabled() || Say.time(level) < 12000) return
        val day = Say.day(level)
        for (player in level.players()) {
            if (player.isSpectator) continue
            val capital = Capitals.covering(level, player.blockPosition()) ?: continue
            if (!capital.inTown(player.blockX, player.blockZ)) continue
            val key = capital.center.asLong()
            if (sightings[key]?.day == day) continue
            val monster = level.getEntitiesOfClass(Monster::class.java, player.boundingBox.inflate(32.0, 12.0, 32.0)) {
                it.isAlive && capital.inTown(it.blockX, it.blockZ)
            }.firstOrNull() ?: continue
            sightings[key] = Sighting(day, monster.type.descriptionId)
        }
    }

    fun forget() = sightings.clear()

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS) return
        val r = ask.resident ?: return
        val level = ask.level
        val today = Say.day(level)
        val time = Say.time(level)
        val bark = ask.occasion == Occasion.BARK

        // A monster in the streets last night, talked of through the next day
        if (Days.enabled()) sightings[r.capital.asLong()]?.takeIf { it.day == today - 1 && time < 12000 }?.let { s ->
            add(ask, out, "town.monster", "town.monster.${s.day}", if (time < 6000) 0.64 else 0.4, -1, Speech.of("talk.detail.town.monster", s.what))
        }
        if (bark || Say.child(ask.speaker)) return

        val places = ask.life?.places
        val people = Residents.get(level).of(r.capital)
        val mine = places?.lodgings?.get(r.home)?.box?.center
        fun street(other: Resident): Boolean {
            val theirs = places?.lodgings?.get(other.home)?.box?.center ?: return false
            return mine != null && theirs.distSqr(mine) <= STREET * STREET
        }

        if (Days.enabled()) {
            // A neighbour buried, a day or two ago (their own household's grief is the household's)
            people.filter { !it.alive && it.home != r.home && today - it.died in 1L..2L && (street(it) || it.role == r.role && it.role != TownRole.CITIZEN) }
                .maxByOrNull { it.died }?.let { dead ->
                    val role = ROLES[dead.role]
                    val detail = if (role != null) Speech.of("talk.detail.town.death", "=${dead.name}", Say.WORD + "role.$role") else Speech.of("talk.detail.town.death.plain", "=${dead.name}")
                    add(ask, out, "town.death", "town.death.${dead.id}", 0.56, -1, detail, Activity.SIGH)
                }
            // A new family in the street
            people.filter { it.alive && it.home >= 0 && it.home != r.home && street(it) && HouseholdTopics.newcomer(it, people, today) }
                .maxByOrNull { it.since }?.let { n ->
                    add(ask, out, "town.newcomer", "town.newcomer.${n.home}", 0.4, 0, Speech.of("talk.detail.town.newcomer", "=${n.family}"))
                }
        }

        // An outlaw band camped out near the town
        if (Outlaws.enabled()) OutlawCamps.get(level).wanted(r.capital, BAND).firstOrNull { !it.slain }?.let { band ->
            val dx = (band.pos.x - r.capital.x).toDouble()
            val dz = (band.pos.z - r.capital.z).toDouble()
            val near = 1.0 - (sqrt(dx * dx + dz * dz) / BAND).coerceIn(0.0, 1.0)
            val detail = Speech.of("talk.detail.town.outlaws", "${Outlaws.NAME_KEY}.${band.name}", Say.wayKey(r.capital, band.pos))
            val toward = BlockPos(band.pos.x, ask.speaker.blockY + 1, band.pos.z)
            add(ask, out, "town.outlaws", "town.outlaws.${band.id}", 0.8 * (0.5 + 0.5 * near), -1, detail, Activity.POINT, toward)
        }

        // What the crier cried at noon
        cried(ask, r)?.let { (line, tone) -> add(ask, out, "town.crier", "town.crier.$today.$line", 0.5, tone, line) }
    }

    private fun add(
        ask: Ask, out: MutableList<Topic>, family: String, id: String, weight: Double, tone: Int, detail: String?,
        gesture: Activity? = null, toward: BlockPos? = null,
    ) {
        val say = Say.line(ask, family, detail) ?: return
        out.add(Topic(id, Kind.EVENT, weight, say, tone = tone, gesture = gesture, toward = toward))
    }

    /**
     * What [ask]'s town crier cried at noon today, first of all, as its line ("crier_blight_wheat"), and whether it was
     * good news; null before the crier has been and gone, if they haven't cried today, or if it was only that all's well.
     * The crier themselves doesn't ask whether you heard them.
     */
    private fun cried(ask: Ask, r: Resident): Pair<String, Int>? {
        val life = ask.life ?: return null
        val entry = Say.market(ask) ?: return null
        val level = ask.level
        val hour = Say.hour(level)
        if (hour < 12.5 || hour >= 21.0 || !Days.enabled()) return null
        val crierId = Hands.of(level, life.places).crier ?: return null
        if (crierId == r.id) return null
        val crier = Residents.get(level)[crierId]?.uuid?.let { level.getEntity(it) } as? Townsperson ?: return null
        val c = Conditions(Markets.hourOf(level))
        if (crier.life?.cried != c.day) return null
        val m = entry.market
        // The noon's cries open with the market's news since the noon before (see Crier.news)
        val noon = c.day * 24 + 6
        for (n in m.news) {
            if (n.hour <= noon - 24 || n.hour > noon) continue
            val over = n.key.startsWith(OVER)
            if (!over && !n.key.startsWith(EVENT)) continue
            val kind = EventKind.byId(n.key.substringAfterLast('.')) ?: continue
            if (over) return "crier_over_${kind.id}" to 1
            val crop = n.good?.takeIf { kind == EventKind.BLIGHT || kind == EventKind.BUMPER }
            return (if (crop != null) "crier_${kind.id}_${crop.id}" else "crier_${kind.id}") to if (kind.trouble) -1 else 1
        }
        if ((m.hungry.lastOrNull() ?: 0.0) >= maxOf(3.0, entry.town.people * Economy.MEALS * 0.05)) return "crier_hungry" to -1
        return null
    }

    private const val EVENT = "news.guildmark.market.event."
    private const val OVER = "news.guildmark.market.over."

    /** How a death notice names someone's trade ("Tam Miller, the baker."), by the word's key; none for a plain citizen. */
    private val ROLES: Map<TownRole, String> = mapOf(
        TownRole.SMITH to "smith", TownRole.BAKER to "baker", TownRole.MERCHANT to "merchant", TownRole.GUARD to "guard",
        TownRole.FARMER to "farmer", TownRole.FISHER to "fisher", TownRole.CHILD to "child", TownRole.NOBLE to "noble",
        TownRole.INNKEEPER to "innkeeper", TownRole.BARD to "bard", TownRole.PRIEST to "priest", TownRole.MAGE to "mage",
        TownRole.KNIGHT to "knight", TownRole.THIEF to "thief",
    )
}
