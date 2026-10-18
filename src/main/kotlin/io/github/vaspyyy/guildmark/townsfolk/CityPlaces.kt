package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.Census
import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.city.plan.Housing
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.M
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.concurrent.CompletableFuture

/**
 * A capital's places in the world, as its people know them: every building with its doors, ladders, beds, seats,
 * tables, work blocks and stores; the market's stalls; wells, lamps and benches; the fields; and where each of the
 * plan's residents lives (see [Census] and [Housing], which work it out in the city's own frame). Worked out off
 * the server thread the first time anyone asks, and kept for the session.
 */
class CityPlaces(val capital: CapitalPiece, val census: Census, val housing: Housing) {
    /** A door's lower half; [inside] and [outside] are the blocks to stand on either side of it. */
    class Door(val pos: BlockPos, val inward: Direction) {
        val inside: BlockPos get() = pos.relative(inward)
        val outside: BlockPos get() = pos.relative(inward.opposite)
    }

    /** A ladder's column at [x], [z] from [bottom] up to [top], its rungs facing [facing] (the climber faces the wall). */
    class Ladder(val x: Int, val z: Int, val bottom: Int, val top: Int, val facing: Direction) {
        fun at(y: Int): BlockPos = BlockPos(x, y, z)

        /** Where to step off at the top (or on at the bottom): in front of the rungs. */
        fun landing(y: Int): BlockPos = BlockPos(x + facing.stepX, y, z + facing.stepZ)
    }

    class Station(val pos: BlockPos, val m: M)

    /** One building: [id] as in [Census.lodges]; [floors] are the heights people stand at, from the ground up. */
    class Lodging(
        val id: Int, val kind: String, val box: BoundingBox, val y: Int, val floors: List<Int>,
        val doors: List<Door>, val ladders: List<Ladder>, val beds: List<BlockPos>, val seats: List<Spot>,
        val tables: List<BlockPos>, val stations: List<Station>, val stores: List<BlockPos>,
    ) {
        fun contains(pos: BlockPos): Boolean = box.isInside(pos)

        /** Which of [floors] someone standing at [y] is on. */
        fun floorOf(y: Int): Int = floors.minByOrNull { kotlin.math.abs(it - y) } ?: this.y

        val farm: Boolean get() = id >= Census.FARM
        val front: Door? get() = doors.minByOrNull { it.pos.y }

        override fun toString(): String = "$kind#$id"
    }

    /** A market stall: where its keeper stands and faces, where a customer stands, its counter and the goods on it. */
    class Stall(val index: Int, val keeper: Spot, val customer: Spot, val counter: List<BlockPos>, val goods: List<BlockPos>, val trade: M)

    /** A field: its crops' blocks, and the farm building it belongs to. */
    class Field(val index: Int, val crops: List<BlockPos>, val box: BoundingBox, val lodging: Int?)

    private fun world(a: At): BlockPos = capital.world(a.x, a.y, a.z)
    private fun dir(d: io.github.vaspyyy.guildmark.city.plan.Dir): Direction = capital.direction(d)

    val lodgings: Map<Int, Lodging> = census.lodges.mapValues { (_, l) ->
        val a = capital.world(l.lot.x0, minOf(l.bottom, l.y), l.lot.z0)
        val b = capital.world(l.lot.x1, maxOf(l.top, l.y + 2), l.lot.z1)
        val box = BoundingBox(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z), maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        val y0 = capital.world(0, 0, 0).y
        Lodging(
            l.id, l.kind, box, y0 + l.y, l.floors.map { y0 + it },
            l.doors.map { Door(world(it.at), dir(it.inward)) },
            l.ladders.map { ld ->
                val p = capital.world(ld.x, ld.bottom, ld.z)
                Ladder(p.x, p.z, y0 + ld.bottom, y0 + ld.top, dir(ld.facing))
            },
            l.beds.map { world(it.head) },
            l.seats.map { Spot(world(it.at), dir(it.facing)) },
            l.tables.map { world(it) },
            l.stations.map { Station(world(it.at), it.m) },
            l.stores.map { world(it.at) },
        )
    }

    val stalls: List<Stall> = census.stalls.mapIndexed { i, s ->
        Stall(i, Spot(world(s.keeper), dir(s.facing)), Spot(world(s.customer), dir(s.facing.opposite)), s.counter.map(::world), s.goods.map(::world), s.trade)
    }

    /** Where the merchants who keep no market stall stand to serve, facing their customers: at the quay's stalls and the like. */
    val counters: List<Spot> = census.residents.filter { it.folk == Folk.MERCHANT && it.post != null }
        .map { Spot(world(it.post!!), dir(it.facing ?: io.github.vaspyyy.guildmark.city.plan.Dir.N)) }
        .filter { s -> stalls.none { it.keeper.pos == s.pos } }

    val wells: List<BlockPos> = census.wells.map(::world)
    val lamps: List<BlockPos> = census.lamps.map(::world)
    val benches: List<Spot> = census.benches.map { Spot(world(it.at), dir(it.facing)) }

    val fields: List<Field> = census.fields.mapIndexed { i, f ->
        val crops = f.crops.map(::world)
        var box = BoundingBox(crops.first())
        for (c in crops) box = box.encapsulate(c)
        Field(i, crops, box, f.lodge)
    }

    /** The market square's middle, at its standing height. */
    val market: BlockPos = census.plan.market.let { m -> capital.standing(census.plan, io.github.vaspyyy.guildmark.city.plan.CityPlan.Pos((m.x0 + m.x1) / 2, (m.z0 + m.z1) / 2)) }

    val bell: BlockPos = capital.bell

    /** The building whose ground [pos] is on, if any. */
    fun lodgingAt(pos: BlockPos): Lodging? {
        val (x, z) = capital.local(pos.x, pos.z)
        val l = census.lodgeAt(x, z) ?: return null
        return lodgings[l.id]
    }

    /** Every lodging of [kind], nearest [pos] first. */
    fun nearest(pos: BlockPos, kinds: Collection<String>): List<Lodging> =
        lodgings.values.filter { it.kind in kinds }.sortedBy { it.box.center.distSqr(pos) }

    /**
     * Which of the plan's residents a townsperson spawned at worldgen is: the one of their trade set down with this
     * home (their spawn spot where the plan gave them no home). -1 if none.
     */
    fun planned(role: TownRole, home: BlockPos): Int {
        val folk = folkOf(role) ?: return -1
        census.residents.forEachIndexed { i, r -> if (r.folk == folk && world(r.home ?: r.at) == home) return i }
        return -1
    }

    /** Where the plan's resident [index] lives, and the bed made up for them there if there is one. */
    fun homeOf(index: Int): Pair<Lodging?, BlockPos?> {
        val h = housing.homes.getOrNull(index) ?: return null to null
        return lodgings[h.lodge] to h.bed?.let { world(it.head) }
    }

    companion object {
        private val known = HashMap<BlockPos, CityPlaces?>()
        private val working = HashMap<BlockPos, CompletableFuture<CityPlaces?>>()

        fun forget() {
            synchronized(known) {
                known.clear()
                working.clear()
            }
        }

        /** Whether working out [capital]'s places went wrong (so nobody waits on them). */
        fun failed(capital: CapitalPiece): Boolean = synchronized(known) { known.containsKey(capital.center) && known[capital.center] == null }

        /** The places of [capital] if they're worked out; if not, starts on them and gives null for now. */
        fun of(level: ServerLevel, capital: CapitalPiece): CityPlaces? {
            synchronized(known) {
                if (known.containsKey(capital.center)) return known[capital.center]
                val job = working[capital.center]
                if (job != null) {
                    if (!job.isDone) return null
                    val done = try {
                        job.join()
                    } catch (e: RuntimeException) {
                        Guildmark.LOGGER.warn("Couldn't work out the places of the capital at {}", capital.center, e)
                        null
                    }
                    known[capital.center] = done
                    working.remove(capital.center)
                    return done
                }
                capital.inWorld(level)
                working[capital.center] = CompletableFuture.supplyAsync({
                    val census = Census(capital.plan)
                    CityPlaces(capital, census, Housing(census))
                }, Util.backgroundExecutor())
                return null
            }
        }

        fun folkOf(role: TownRole): Folk? = when (role) {
            TownRole.CITIZEN -> Folk.CITIZEN
            TownRole.SMITH -> Folk.SMITH
            TownRole.BAKER -> Folk.BAKER
            TownRole.MERCHANT -> Folk.MERCHANT
            TownRole.GUARD -> Folk.GUARD
            TownRole.NOBLE -> Folk.NOBLE
            TownRole.INNKEEPER -> Folk.INNKEEPER
            TownRole.BARD -> Folk.BARD
            TownRole.PRIEST -> Folk.PRIEST
            TownRole.CHILD -> Folk.CHILD
            TownRole.FARMER -> Folk.FARMER
            TownRole.FISHER -> Folk.FISHER
            else -> null
        }

        /** Buildings people go to of an evening for a drink. */
        val TAVERNS = setOf(Kind.TAVERN.name, "INN")
        val TEMPLES = setOf(Kind.CATHEDRAL.name, Kind.TEMPLE.name)
    }
}
