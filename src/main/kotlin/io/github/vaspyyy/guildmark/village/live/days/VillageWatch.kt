package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Climb
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Idle
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Stretch
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walk
import io.github.vaspyyy.guildmark.townsfolk.Ways
import io.github.vaspyyy.guildmark.townsfolk.Work
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * The watch a village's guards keep (living-world/villages/design.md 5.5): at a tower where it has one, up its ladder to the
 * platform and the Archer Post there, looking out from the parapet, and down again when the watch is over; on the ground, the
 * beat the capital's guards walk ([Jobs.guard]), where there is no tower, no way up it or no tower for them.
 *
 * Whose tower it is is plain from who the guards are: of the guards on the same watch (the day's, or the night's), ordered by their
 * ids, the first keeps the first tower, the second the second, and so on; the guards left over walk the beat. No state is kept for
 * it, so a guard who dies hands the tower on by the next day's count. A tower whose ladder does not reach a platform that can be
 * stood on (a ladder with a gap in it, a shaft blocked, a roof not loaded) is watched from its foot like the beat; a guard who
 * could not get up twice in a day is not sent again that day.
 *
 * The mechanics are [io.github.vaspyyy.guildmark.townsfolk.Lookout]'s (the capital's road towers), played here on the village's
 * own: [Climb] up and down the ladder's exits, a platform spot beside an Archer Post facing out from the village, and the
 * lookout clip. The beacon, the riders' call and the works are the capital's, and are not here.
 */
object VillageWatch {
    /** How long a tower's platform is known before it is looked over again. */
    private const val SURVEYED = 1200L

    /** The most platform spots kept for a tower. */
    private const val SPOTS = 8

    private class Deck(val tower: CityPlaces.Lodging, val ladder: CityPlaces.Ladder?, val bottom: Int, val top: Int, val spots: List<Spot>, val at: Long)

    private val surveyed = HashMap<Long, Deck>()

    /** Someone up on a platform: which tower, its ladder and heights, and when they last ate up there. */
    private class Aloft(val tower: Int, val ladder: CityPlaces.Ladder, val top: Int, val bottom: Int, val box: net.minecraft.world.level.levelgen.structure.BoundingBox) {
        var tries = 0
        var ate = Long.MIN_VALUE
    }

    private val aloft = WeakHashMap<Townsperson, Aloft>()

    /** Times a guard could not get up a tower today, by resident id. */
    private val balked = HashMap<Int, Pair<Long, Int>>()

    /** Whether [p] is up on [up]'s platform (not fallen, not knocked off it). */
    private fun there(p: Townsperson, up: Aloft): Boolean =
        abs(p.y - up.top) < 2.0 && p.x >= up.box.minX() - 1 && p.x <= up.box.maxX() + 2 && p.z >= up.box.minZ() - 1 && p.z <= up.box.maxZ() + 2

    /** Whether [p] is up on a village tower's platform. */
    fun up(p: Townsperson): Boolean = aloft[p]?.let { there(p, it) } == true

    /** The tower [life] keeps in the watch they are on, if the village has one for them. */
    fun towerOf(level: ServerLevel, life: Life): CityPlaces.Lodging? {
        if (life.resident.role != TownRole.GUARD) return null
        val towers = life.places.lodgings.values.filter { it.kind == "TOWER" && it.ladders.isNotEmpty() }.sortedBy { it.id }
        if (towers.isEmpty()) return null
        val night = Hands.of(level, life.places).night
        val same = Residents.get(level).byCentre(life.places.centre)
            .filter { it.alive && it.role == TownRole.GUARD && (it.id in night) == life.nightWatch }.sortedBy { it.id }
        val i = same.indexOfFirst { it.id == life.resident.id }
        return towers.getOrNull(i)
    }

    /** What a guard does on a watch at a tower: out to it and up its ladder, or up there keeping watch; null for the beat. */
    fun keep(p: Townsperson, life: Life): Task? {
        val level = p.level() as? ServerLevel ?: return null
        val tower = towerOf(level, life) ?: return null
        val up = aloft[p]?.takeIf { there(p, it) }
        if (up == null) aloft.remove(p)
        if (up != null && up.tower != tower.id) return climbDown(p, up)
        val deck = survey(level, life, tower) ?: return null
        if (up != null) return onDeck(level, p, deck)
        val ladder = deck.ladder ?: return null
        val day = Days.dayOf(level.overworldClockTime)
        if (balked[life.resident.id]?.let { it.first == day && it.second >= 2 } == true) return null
        val landing = ladder.landing(deck.bottom)
        val climb = Later(p) {
            if (p.blockPosition().distSqr(landing) > 4.0) {
                failed(level, life)
                null
            } else {
                Walk(p, landing, 0.45, exact = true).andThen(Climb(p, ladder, deck.bottom, deck.top)).andThen(Arrive(p, life, tower, ladder, deck))
            }
        }
        return Ways.to(p, life.places, landing, Jobs.WALK, near = 1.5).andThen(climb)
    }

    private fun failed(level: ServerLevel, life: Life) {
        val day = Days.dayOf(level.overworldClockTime)
        val was = balked[life.resident.id]?.takeIf { it.first == day }?.second ?: 0
        balked[life.resident.id] = day to was + 1
        if (balked.size > 256) balked.entries.removeIf { it.value.first != day }
    }

    /** Off the top of the ladder: on the platform now (if they got there; if not, it counts against the climb). */
    private class Arrive(p: Townsperson, private val life: Life, private val tower: CityPlaces.Lodging, private val ladder: CityPlaces.Ladder, private val deck: Deck) : Task(p) {
        override val instant: Boolean get() = true

        override fun start() {
            val up = Aloft(tower.id, ladder, deck.top, deck.bottom, tower.box)
            if (there(p, up)) aloft[p] = up else failed(level, life)
        }

        override fun run(): Boolean = false
    }

    // --- The tower ---

    /** [tower] looked over (again, every [SURVEYED] ticks); null while its land is not loaded. */
    private fun survey(level: ServerLevel, life: Life, tower: CityPlaces.Lodging): Deck? {
        val key = BlockPos(tower.box.minX(), tower.box.minY(), tower.box.minZ()).asLong() xor (tower.id.toLong() shl 50)
        val now = level.gameTime
        surveyed[key]?.let { if (now - it.at in 0 until SURVEYED) return it }
        val box = tower.box
        if (!level.isLoaded(BlockPos(box.minX(), box.minY(), box.minZ())) || !level.isLoaded(BlockPos(box.maxX(), box.maxY(), box.maxZ()))) return null
        val roof = tower.floors.lastOrNull() ?: tower.y
        // The tallest ladder whose exits run from the ground to the roof's height, with the shaft clear between
        var best: Triple<CityPlaces.Ladder, Int, Int>? = null
        for (ladder in tower.ladders.sortedByDescending { it.top - it.bottom }) {
            val exits = Ways.exits(level, ladder)
            if (exits.size < 2) continue
            val bottom = exits.first()
            val top = exits.last()
            if (top - bottom < 3 || abs(top - roof) > 1 || !Ways.clear(level, ladder, bottom, top)) continue
            best = Triple(ladder, bottom, top)
            break
        }
        val ladder = best?.first
        val bottom = best?.second ?: tower.y
        val top = best?.third ?: roof
        val spots = if (ladder != null) spots(level, life, tower, ladder, top) else listOf()
        return Deck(tower, ladder?.takeIf { spots.isNotEmpty() }, bottom, top, spots, now).also { surveyed[key] = it }
    }

    /** The platform's places to stand at [top]: beside an Archer Post, else at the edge; each facing out from the village. */
    private fun spots(level: ServerLevel, life: Life, tower: CityPlaces.Lodging, ladder: CityPlaces.Ladder, top: Int): List<Spot> {
        val landing = ladder.landing(top)
        val out = ArrayList<Pair<BlockPos, Double>>()
        val posts = (life.places as? VillagePlaces)?.towers.orEmpty().filter { tower.box.isInside(it) && abs(it.y - top) <= 1 }
        for (post in posts) {
            val at = Places.beside(level, post, landing) ?: continue
            if (abs(at.pos.y - top) <= 1) out += at.pos to 0.0
        }
        if (out.isEmpty()) {
            val box = tower.box
            for (x in box.minX()..box.maxX()) for (z in box.minZ()..box.maxZ()) {
                if (x == ladder.x && z == ladder.z) continue
                val pos = BlockPos(x, top, z)
                if (pos == landing || !Places.standable(level, pos)) continue
                // The edge first: a cell with open air beside it
                val edge = Direction.Plane.HORIZONTAL.count { !Places.standable(level, pos.relative(it)) }
                if (edge == 0) continue
                out += pos to -edge.toDouble()
            }
        }
        val town = life.places.centre
        return out.sortedWith(compareBy({ it.second }, { -it.first.distSqr(town) })).map { facingOut(it.first, town) }.take(SPOTS)
    }

    /** [pos], facing away from [town]. */
    private fun facingOut(pos: BlockPos, town: BlockPos): Spot {
        val dx = (pos.x - town.x).toDouble()
        val dz = (pos.z - town.z).toDouble()
        return Spot(pos, if (abs(dx) >= abs(dz)) (if (dx >= 0) Direction.EAST else Direction.WEST) else (if (dz >= 0) Direction.SOUTH else Direction.NORTH))
    }

    // --- Up on the platform ---

    /** Up on the platform: a while at the parapet looking out, now and then a turn round to another side. */
    private fun onDeck(level: ServerLevel, p: Townsperson, deck: Deck): Task {
        if (deck.spots.isEmpty()) return Idle(p, 200, Activity.LOOKOUT, chatty = false)
        val free = deck.spots.filter { Claims.free(level, it.pos, p) }
        if (free.isEmpty()) return Idle(p, 100, Activity.LOOKOUT, chatty = false)
        val spot = if (p.random.nextInt(3) == 0) free[p.random.nextInt(free.size)] else free.first()
        val steps = listOf(
            Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * (3 + p.random.nextInt(4))),
            Step(Activity.NONE, 30 + p.random.nextInt(40)),
            Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * (1 + p.random.nextInt(2))),
        )
        return Work(p, spot, Jobs.top(spot.pos.relative(spot.facing, 24), 1.6), steps)
    }

    /** Down the ladder from [up]'s platform, and off the tower. */
    private fun climbDown(p: Townsperson, up: Aloft): Task {
        val landing = up.ladder.landing(up.top)
        return Walk(p, landing, 0.45, exact = true).andThen(Climb(p, up.ladder, up.top, up.bottom)).andThen(Acts.hold(p, ItemStack.EMPTY)).andThen(Later(p) {
            if (abs(p.y - up.bottom) < 1.5) aloft.remove(p)
            null
        })
    }

    /**
     * Before anything else of [life]'s day ([Days.next]): someone up a tower whose watch there is over comes down its ladder first; at
     * noon the day watch eats up there instead. Null for everyone else (and at once, for everyone not up a tower).
     */
    fun down(p: Townsperson, life: Life, stretch: Stretch): Task? {
        if (aloft.isEmpty()) return null
        val up = aloft[p] ?: return null
        if (!there(p, up)) {
            aloft.remove(p)
            return null
        }
        val level = p.level() as? ServerLevel ?: return null
        val mine = towerOf(level, life)?.id
        if (stretch.doing == Doing.WATCH && mine == up.tower) return null
        if (stretch.doing == Doing.LUNCH && mine == up.tower) {
            life.step = life.script.size
            val today = Days.dayOf(level.overworldClockTime)
            if (up.ate != today) {
                up.ate = today
                return Acts.hold(p, ItemStack(Items.BREAD)).andThen(Idle(p, 300 + p.random.nextInt(200), Activity.EAT, chatty = false)).andThen(Acts.hold(p, ItemStack.EMPTY))
            }
            return Idle(p, 200, Activity.LOOKOUT, chatty = false)
        }
        // Stuck up there (the shaft blocked): their day goes on from where they stand
        if (++up.tries > 3) {
            aloft.remove(p)
            return null
        }
        return climbDown(p, up)
    }
}
