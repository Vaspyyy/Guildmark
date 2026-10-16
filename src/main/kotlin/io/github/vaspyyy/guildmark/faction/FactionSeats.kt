package io.github.vaspyyy.guildmark.faction

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Building
import io.github.vaspyyy.guildmark.city.plan.Buildings
import io.github.vaspyyy.guildmark.city.plan.Canvas
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.city.plan.Frame
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.Loot
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.NullCanvas
import io.github.vaspyyy.guildmark.city.plan.PropKind
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Spawn
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Town
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.function.Supplier

/**
 * Where a faction sits in one capital, in the world: its hall's door, where its leader keeps their post (on
 * [leaderSeat] if they sit), and the places its members work. Worked out from the city's plan, so it's the same
 * every time; see [FactionSeats].
 */
class Seat(
    val faction: Faction,
    /** The capital's centre: which capital this is. */
    val capital: BlockPos,
    /** Just inside the hall's front door, and just outside it, [front] being the way out. */
    val door: BlockPos,
    val outside: BlockPos,
    val front: Direction,
    val leader: Spot,
    val leaderSeat: BlockPos?,
    /** Where members live; their beds, if they sleep in one, are found near. */
    val homes: List<BlockPos>,
    /** What members work at: the lecterns and the map table, the grindstone and anvil, the attic's table. */
    val stations: List<BlockPos>,
    /** Chairs for the members. */
    val seats: List<BlockPos>,
    /** Places to stand: practice posts to drill at (knights), barrels to count coin on (thieves). */
    val spots: List<Spot>,
    /** The knights' sparring rings (each middle, and the way across it), the door's guard and the dawn vigil's row. */
    val rings: List<Spot> = listOf(),
    val guards: List<Spot> = listOf(),
    val vigil: List<Spot> = listOf(),
    /** Out of doors: the plaza before the library (mages, the ward-lights) or the market (the thieves' nights). */
    val square: BlockPos,
)

/** A heist's building in one capital: the box it fills, the chest the prize goes in, the way in, and where watchers walk. */
class Site(val heist: Heist, val capital: BlockPos, val box: BoundingBox, val chest: BlockPos, val entrance: BlockPos, val posts: List<BlockPos>) {
    fun contains(pos: Vec3): Boolean =
        pos.x >= box.minX() && pos.x < box.maxX() + 1 && pos.z >= box.minZ() && pos.z < box.maxZ() + 1 && pos.y >= box.minY() - 0.5 && pos.y < box.maxY() + 1
}

/** One capital's faction seats and heist sites. */
class SeatLayout(val capital: CapitalPiece, val seats: Map<Faction, Seat>, val sites: Map<Heist, Site>, val town: Town)

/**
 * The factions' seats in each grand capital: the Starwatch in the library on the plaza, the Hearthsworn in the
 * barracks and its training yard, the Quiet Hand in the attic of the plaza's tavern. Everything is worked out from
 * the capital's plan (the buildings' own frames, as GreatBuildings draws them), so it holds for capitals built before
 * the factions came. A seat is filled with its leader and members the first time a player comes near it, and the
 * attic and the library get the few things they need then; which seats are filled is kept with the world.
 */
object FactionSeats {
    private const val STORY = Buildings.STORY
    /** How near a player comes before a seat is filled. */
    private const val NEAR = 80.0
    /** How near anyone may be for a lost leader's place to be filled. */
    private const val WATCHED = 24.0
    private val MEMBERS = mapOf(Faction.STARWATCH to 4, Faction.HEARTHSWORN to 4, Faction.QUIET_HAND to 3)

    private val layouts = HashMap<BlockPos, SeatLayout?>()
    /** Seats whose leader wasn't about at the last look, by [SeatData.key]. */
    private val unseen = HashSet<String>()

    fun forget() {
        layouts.clear()
        unseen.clear()
    }

    /** The seats and sites of [capital], worked out once and remembered (a capital that has none, as none). */
    fun layout(capital: CapitalPiece): SeatLayout? {
        synchronized(layouts) {
            if (layouts.containsKey(capital.center)) return layouts[capital.center]
            val layout = try {
                build(capital)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Couldn't work out the faction seats of the capital at {}", capital.center, e)
                null
            }
            layouts[capital.center] = layout
            return layout
        }
    }

    /** The layout of the capital round [pos], if there is one. */
    fun layoutAt(level: ServerLevel, pos: BlockPos): SeatLayout? {
        synchronized(layouts) { layouts.values.firstOrNull { it != null && it.town.contains(pos) }?.let { return it } }
        return Capitals.covering(level, pos)?.let { layout(it) }
    }

    /** [faction]'s seat in the capital round [pos]. */
    fun seat(level: ServerLevel, faction: Faction, pos: BlockPos): Seat? = layoutAt(level, pos)?.seats?.get(faction)

    /** [heist]'s building in the capital round [pos]. */
    fun site(level: ServerLevel, heist: Heist, pos: BlockPos): Site? = layoutAt(level, pos)?.sites?.get(heist)

    /** The seat of the faction [p] belongs to, in the capital they live in; null for a member living anywhere else. */
    fun seatOf(p: Townsperson): Seat? {
        val faction = Faction.of(p.role) ?: return null
        val level = p.level() as? ServerLevel ?: return null
        val center = p.town?.center ?: return null
        val known = synchronized(layouts) { if (layouts.containsKey(center)) layouts[center] else null }
        if (known != null) return known.seats[faction]
        // Not worked out yet this session: only now and then, it's not nothing
        if ((p.tickCount + p.id) % 200 != 0) return null
        return layoutAt(level, center)?.seats?.get(faction)
    }

    // ---- Filling the seats ----

    /**
     * Every couple of seconds per player: fills the seats near them that have never been filled, and gives a seat
     * whose leader is gone for good (killed, lost to the void) a new one.
     */
    fun tick(level: ServerLevel, player: ServerPlayer) {
        if (player.isSpectator) return
        val capital = Capitals.covering(level, player.blockPosition()) ?: return
        val layout = layout(capital) ?: return
        val data = SeatData.get(level)
        for (seat in layout.seats.values) {
            if (player.distanceToSqr(Vec3.atCenterOf(seat.door)) > NEAR * NEAR || !Places.loaded(level, seat.door, 24)) continue
            if (!data.isFilled(seat)) fill(level, layout, seat, data)
            else if (lost(level, seat, data) && unwatched(level, seat)) seatLeader(level, layout, seat)?.let { data.filled(seat, it.uuid) }
        }
    }

    /**
     * Whether [seat]'s leader is gone: not about at two looks running, with the land round their post loaded and the
     * people in it too (they come in a little after the land does).
     */
    private fun lost(level: ServerLevel, seat: Seat, data: SeatData): Boolean {
        val key = data.key(seat)
        val id = data.leader(seat)
        val about = id != null && level.getEntity(id)?.isAlive == true || leaderPresent(level, seat) ||
            !level.areEntitiesLoaded(ChunkPos.pack(seat.leader.pos))
        if (about) {
            unseen.remove(key)
            return false
        }
        if (unseen.add(key)) return false
        unseen.remove(key)
        return true
    }

    /** Nobody is close enough to [seat]'s post to see a new leader walk in out of nowhere. */
    private fun unwatched(level: ServerLevel, seat: Seat): Boolean {
        val post = Vec3.atCenterOf(seat.leader.pos)
        return level.players().none { it.distanceToSqr(post) < WATCHED * WATCHED }
    }

    /** Furnishes [seat]'s hall where it needs it and brings in its leader and members. */
    fun fill(level: ServerLevel, layout: SeatLayout, seat: Seat, data: SeatData = SeatData.get(level)) {
        furnish(level, layout.capital, seat)
        val leader = seatLeader(level, layout, seat) ?: return
        repeat(MEMBERS[seat.faction] ?: 3) { i ->
            val home = seat.homes[i % seat.homes.size]
            val member = spawn(level, layout, seat, home, 1 + i % (seat.faction.role.looks - 1), home) ?: return@repeat
            member.work = seat.stations.getOrNull(i % seat.stations.size.coerceAtLeast(1))
        }
        data.filled(seat, leader.uuid)
    }

    /** [seat]'s leader, brought in to their post. */
    private fun seatLeader(level: ServerLevel, layout: SeatLayout, seat: Seat): Townsperson? {
        val leader = spawn(level, layout, seat, seat.leaderSeat ?: seat.leader.pos, 0, seat.leader.pos) ?: return null
        leader.isLeader = true
        leader.post = seat.leader.pos
        leader.postFacing = seat.leader.facing
        leader.customName = seat.faction.leader.title
        leader.face(seat.leader.facing)
        return leader
    }

    private fun spawn(level: ServerLevel, layout: SeatLayout, seat: Seat, at: BlockPos, look: Int, home: BlockPos): Townsperson? {
        val stand = if (Places.standable(level, at)) at else standNear(level, at) ?: at
        val person = Townsfolk.create(level, stand, seat.faction.role, look, seat.front.toYRot(), EntitySpawnReason.EVENT) ?: return null
        person.home = home
        person.town = layout.town
        person.setPersistenceRequired()
        level.addFreshEntity(person)
        return person
    }

    /** The nearest place to stand round [pos]. */
    fun standNear(level: ServerLevel, pos: BlockPos, r: Int = 3): BlockPos? =
        Places.nearest(level, pos, r, 2) { _, at -> Places.standable(level, at) }

    /** Whether [seat]'s leader is about within a little way of their post (when its chunk is loaded). */
    fun leaderPresent(level: ServerLevel, seat: Seat): Boolean =
        level.getEntitiesOfClass(Townsperson::class.java, BoundingBox(seat.leader.pos).inflatedBy(24).let { net.minecraft.world.phys.AABB.of(it) }) {
            it.isLeader && it.isAlive && it.role == seat.faction.role
        }.isNotEmpty()

    /** The few things a hall lacks: chairs round the library's table; a table, chairs and beds in the tavern's attic. */
    private fun furnish(level: ServerLevel, capital: CapitalPiece, seat: Seat) {
        val plan = capital.plan
        val f = when (seat.faction) {
            Faction.STARWATCH -> frame(plan.buildings.firstOrNull { it.kind == Kind.LIBRARY } ?: return)
            Faction.QUIET_HAND -> frame(plan.buildings.firstOrNull { it.kind == Kind.TAVERN } ?: return)
            Faction.HEARTHSWORN -> return
        }
        fun put(u: Int, y: Int, v: Int, b: B) {
            val at = f.at(u, y, v)
            val pos = capital.world(at.x, at.y, at.z)
            if (!level.getBlockState(pos).isAir) return
            val dir = b.dir
            val turned = if (dir == null) b else B(b.m, f.turn(dir), b.top, b.axis, b.color, b.v, b.part, b.open)
            level.setBlock(pos, capital.style.state(turned, 0).rotate(capital.turn), Block.UPDATE_ALL)
        }
        when (seat.faction) {
            Faction.STARWATCH -> {
                val door = f.w / 2 - 1
                // Two more chairs at the reading table, on its near side
                for (u in door..door + 1) put(u, 0, 11, chair(Dir.S))
            }
            Faction.QUIET_HAND -> {
                val y = 2 * STORY
                val tu = f.w / 2
                // The Widow's table, lit by a candle, with her chair at its head facing the stairs
                put(tu, y, 4, TABLE)
                put(tu, y, 5, TABLE)
                put(tu, y + 1, 4, B(M.CANDLE))
                put(tu, y, 3, chair(Dir.S))
                put(tu - 1, y, 4, chair(Dir.E))
                put(tu + 1, y, 5, chair(Dir.W))
                put(tu, y, 6, chair(Dir.N))
                // Two cots along the east wall, away from the stairs
                for (v in listOf(1, 3)) {
                    put(f.w - 2, y, v, B(M.BED, Dir.N, color = Dye.PURPLE, part = 0))
                    put(f.w - 2, y, v - 1, B(M.BED, Dir.N, color = Dye.PURPLE, part = 1))
                }
            }
            Faction.HEARTHSWORN -> {}
        }
    }

    private fun chair(looks: Dir): B = B(M.PLANK_STAIRS, looks.opposite)
    private val TABLE = B(M.PLANK_SLAB, top = true)

    private fun frame(b: Building): Frame = Frame(NullCanvas, b.lot, b.front, b.y)

    // ---- Working out the seats ----

    private fun build(capital: CapitalPiece): SeatLayout? {
        val plan = capital.plan
        fun world(a: At): BlockPos = capital.world(a.x, a.y, a.z)
        fun dir(f: Frame, local: Dir): Direction = capital.direction(f.turn(local))
        fun spot(f: Frame, u: Int, y: Int, v: Int, facing: Dir): Spot = Spot(world(f.at(u, y, v)), dir(f, facing))
        val seats = HashMap<Faction, Seat>()
        val sites = HashMap<Heist, Site>()
        val market = plan.market.let { m -> capital.standing(plan, io.github.vaspyyy.guildmark.city.plan.CityPlan.Pos((m.x0 + m.x1) / 2, (m.z0 + m.z1) / 2)) }

        // The Starwatch: the library on the plaza
        plan.buildings.firstOrNull { it.kind == Kind.LIBRARY }?.let { b ->
            val f = frame(b)
            val door = f.w / 2 - 1
            val chair = world(f.at(door - 1, 0, 13))
            seats[Faction.STARWATCH] = Seat(
                Faction.STARWATCH, capital.center, world(f.at(door, 0, 1)), world(f.at(door, 0, -1)), dir(f, Dir.N),
                leader = Spot(chair, dir(f, Dir.N)), leaderSeat = chair,
                homes = listOf(world(f.at(door, 0, 7)), world(f.at(door + 1, 0, 3)), world(f.at(door - 1, 0, 3)), world(f.at(door + 2, 0, 7))),
                stations = listOf(world(f.at(door - 1, 0, 5)), world(f.at(door + 2, 0, 5)), world(f.at(door, STORY, f.d - 3)), world(f.at(door, 0, 9))),
                seats = listOf(world(f.at(door + 2, 0, 13)), world(f.at(door, 0, 11)), world(f.at(door + 1, 0, 11))),
                spots = listOf(),
                square = world(f.at(door, 0, -6)),
            )
        }

        // The Hearthsworn: the barracks, and the training yard across the avenue
        plan.buildings.firstOrNull { it.kind == Kind.BARRACKS }?.let { b ->
            val f = frame(b)
            val door = f.w / 2 - 1
            val rings = plan.props.filter { it.kind == PropKind.RING }.map { p ->
                Spot(capital.world(p.x, plan.level(p.x, p.z), p.z), capital.direction(p.facing.cw))
            }
            val posts = plan.props.filter { it.kind == PropKind.TARGET }.map { p ->
                Spot(capital.world(p.x + p.facing.dx, plan.level(p.x, p.z), p.z + p.facing.dz), capital.direction(p.facing.opposite))
            }
            val outside = world(f.at(door, 0, -1))
            // Corra stands beside the way into the ring nearest the barracks door, watching it
            val ring = plan.props.filter { it.kind == PropKind.RING }.minByOrNull {
                val w = capital.world(it.x, 0, it.z)
                (w.x - outside.x).toDouble() * (w.x - outside.x) + (w.z - outside.z).toDouble() * (w.z - outside.z)
            }
            val leader = if (ring != null) {
                val side = ring.facing.cw
                val x = ring.x + ring.facing.dx * 4 + side.dx * 2
                val z = ring.z + ring.facing.dz * 4 + side.dz * 2
                Spot(capital.world(x, plan.level(x, z), z), capital.direction(ring.facing.opposite))
            } else spot(f, door - 2, 0, -3, Dir.N)
            seats[Faction.HEARTHSWORN] = Seat(
                Faction.HEARTHSWORN, capital.center, world(f.at(door, 0, 1)), outside, dir(f, Dir.N),
                leader = leader, leaderSeat = null,
                homes = listOf(1, 4, 7, f.w - 3).map { world(f.at(it, STORY, f.d - 4)) },
                stations = listOf(world(f.at(1, 0, 3)), world(f.at(1, 0, 5))),
                seats = (5..f.w - 6).filter { it % 2 == 1 }.flatMap { listOf(world(f.at(it, 0, 5)), world(f.at(it, 0, 7))) },
                spots = posts,
                rings = rings,
                guards = listOf(spot(f, door - 1, 0, -2, Dir.N), spot(f, door + 2, 0, -2, Dir.N)),
                vigil = (0..3).map { spot(f, door - 1 + it, 0, -4, Dir.S) },
                square = rings.firstOrNull()?.pos ?: outside,
            )
            sites[Heist.RELIQUARY] = Site(
                Heist.RELIQUARY, capital.center, box(capital, b.lot, b.y, b.y + 2 * STORY - 1), world(f.at(f.w - 2, STORY, f.d - 2)), world(f.at(door, 0, 1)),
                listOf(
                    world(f.at(door, 0, 3)), world(f.at(door + 1, 0, 8)), world(f.at(3, 0, 9)), world(f.at(f.w - 4, 0, 3)),
                    world(f.at(3, STORY, f.d - 4)), world(f.at(f.w - 4, STORY, f.d - 4)), world(f.at(f.w / 2, STORY, 4)),
                ),
            )
        }

        // The Quiet Hand: the attic of the tavern on the plaza (the first tavern the plan lays out)
        plan.buildings.firstOrNull { it.kind == Kind.TAVERN }?.let { b ->
            val f = frame(b)
            val y = 2 * STORY
            val tu = f.w / 2
            val chair = world(f.at(tu, y, 3))
            val door = f.w / 2 - 1
            seats[Faction.QUIET_HAND] = Seat(
                Faction.QUIET_HAND, capital.center, world(f.at(door, 0, 1)), world(f.at(door, 0, -1)), dir(f, Dir.N),
                leader = Spot(chair, dir(f, Dir.S)), leaderSeat = chair,
                homes = listOf(world(f.at(3, y, 9)), world(f.at(f.w - 3, y, 2)), world(f.at(f.w - 3, y, 4))),
                stations = listOf(world(f.at(tu, y, 4))),
                seats = listOf(world(f.at(tu - 1, y, 4)), world(f.at(tu + 1, y, 5)), world(f.at(tu, y, 6))),
                spots = (5..8 step 3).map { spot(f, it, y, f.d - 3, Dir.S) },
                square = market,
            )
            sites[Heist.DEN] = Site(
                Heist.DEN, capital.center, box(capital, b.lot, b.y + y, b.y + 3 * STORY - 2), world(f.at(1, y, 10)), world(f.at(f.w - 2, y, 10)),
                listOf(world(f.at(4, y, 8)), world(f.at(f.w - 4, y, 4)), world(f.at(f.w - 4, y, f.d - 4)), world(f.at(3, y, 11)), world(f.at(tu, y, 8))),
            )
        }

        // House Varn's manor in the noble quarter, and the palace's treasury: drawn into a recorder for their chests
        plan.buildings.firstOrNull { it.kind == Kind.MANOR }?.let { b -> site(capital, b, Heist.MANOR) }?.let { sites[Heist.MANOR] = it }
        plan.buildings.firstOrNull { it.kind == Kind.PALACE }?.let { b -> site(capital, b, Heist.VAULT) }?.let { sites[Heist.VAULT] = it }

        if (seats.isEmpty()) return null
        return SeatLayout(capital, seats, sites, capital.town(plan))
    }

    /** A box over [lot] from [y0] to [y1] in the city's frame, in the world. */
    private fun box(capital: CapitalPiece, lot: Rect, y0: Int, y1: Int): BoundingBox {
        val a = capital.world(lot.x0, y0, lot.z0)
        val b = capital.world(lot.x1, y1, lot.z1)
        return BoundingBox(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z), maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
    }

    /** A manor's or the palace's heist: its chests and residents, from drawing it into a [Recorder]. */
    private fun site(capital: CapitalPiece, b: Building, heist: Heist): Site? {
        val plan = capital.plan
        val recorder = Recorder(b.lot.grow(24))
        Buildings(plan, recorder).draw(b)
        val f = frame(b)
        val door = f.at(b.door, 0, 0)
        fun far(a: At): Int = (a.x - door.x) * (a.x - door.x) + (a.z - door.z) * (a.z - door.z) + (a.y - door.y) * (a.y - door.y) * 4
        val chests = recorder.chests.filter { (at, _, _) -> b.lot.contains(at.x, at.z) }
        val best = when (heist) {
            Heist.VAULT -> chests.filter { it.third == Loot.TREASURY }.maxByOrNull { far(it.first) } ?: chests.maxByOrNull { far(it.first) }
            else -> chests.filter { it.third == Loot.HOUSE || it.third == Loot.LIBRARY || it.third == Loot.TREASURY }.maxByOrNull { far(it.first) }
                ?: chests.maxByOrNull { far(it.first) }
        } ?: return null
        val posts = ArrayList<BlockPos>()
        for (at in recorder.residents) if (b.lot.contains(at.x, at.z)) posts.add(capital.world(at.x, at.y, at.z))
        for ((at, facing, _) in chests) posts.add(capital.world(at.x + facing.dx, at.y, at.z + facing.dz))
        val top = if (recorder.top == Int.MIN_VALUE) b.y + 3 * STORY else recorder.top
        val entrance = capital.world(f.at(b.door, 0, 1).x, b.y, f.at(b.door, 0, 1).z)
        posts.add(entrance)
        return Site(heist, capital.center, box(capital, b.lot, b.y - 1, top), capital.world(best.first.x, best.first.y, best.first.z), entrance, posts.distinct())
    }

    /** A canvas that keeps only what a heist needs: the chests, the residents' places and how high the building goes. */
    private class Recorder(override val clip: Rect) : Canvas {
        val chests = ArrayList<Triple<At, Dir, Loot>>()
        val residents = ArrayList<At>()
        var top = Int.MIN_VALUE

        override fun set(x: Int, y: Int, z: Int, b: B) {
            if (b.m != M.AIR && y > top) top = y
        }

        override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {}
        override fun clearing(x: Int, z: Int, y: Int) {}
        override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {
            chests.add(Triple(At(x, y, z), facing, loot))
        }

        override fun board(x: Int, y: Int, z: Int, facing: Dir) {}
        override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {}
        override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) {
            residents.add(at)
        }
    }
}

/** Which faction seats in which capitals have been filled, and by whom, kept with the world. */
class SeatData(val filled: MutableMap<String, UUID> = HashMap()) : SavedData() {
    fun key(seat: Seat): String = "${seat.capital.x},${seat.capital.y},${seat.capital.z}:${seat.faction.id}"

    fun isFilled(seat: Seat): Boolean = key(seat) in filled

    fun filled(seat: Seat, leader: UUID) {
        filled[key(seat)] = leader
        setDirty()
    }

    /** Lets [seat] be filled again (its leader was lost). */
    fun empty(seat: Seat) {
        if (filled.remove(key(seat)) != null) setDirty()
    }

    fun leader(seat: Seat): UUID? = filled[key(seat)]

    companion object {
        private val CODEC: Codec<SeatData> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC).optionalFieldOf("filled", mapOf()).forGetter { it.filled },
            ).apply(i) { filled -> SeatData(HashMap(filled)) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "faction_seats"), Supplier { SeatData() }, CODEC)

        fun get(level: ServerLevel): SeatData = level.dataStorage.computeIfAbsent(TYPE)
    }
}
