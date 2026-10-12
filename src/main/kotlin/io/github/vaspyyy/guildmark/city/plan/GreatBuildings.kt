package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.sqrt

/**
 * The city's great buildings, each drawn facing north in its own frame: the guild hall, temple, tavern,
 * library, market hall, counting house, smithy and bakery round the plaza, and the keep, barracks and
 * stables in the keep's ward. The plan gives each a lot of a set size and each is drawn to fit it.
 */
class GreatBuildings(private val f: Frame, private val b: Building, private val r: Random) {
    private val w = f.w
    private val d = f.d

    fun draw() {
        when (b.kind) {
            Kind.GUILD_HALL -> guildHall()
            Kind.TEMPLE -> temple()
            Kind.TAVERN -> tavern()
            Kind.LIBRARY -> library()
            Kind.MARKET_HALL -> marketHall()
            Kind.COUNTING_HOUSE -> countingHouse()
            Kind.SMITHY -> smithy()
            Kind.BAKERY -> bakery()
            Kind.KEEP -> keep()
            Kind.BARRACKS -> barracks()
            Kind.STABLES -> stables()
            // Townhouses and the landmarks are drawn elsewhere
            else -> {}
        }
    }

    // ---- The guild hall ----

    /**
     * A tall stone hall with its gable to the plaza: long tables down the hall under hanging lanterns,
     * the guild's banners on the pilasters, and the receptionist's counter across the far end in front
     * of the guild's shelves.
     */
    private fun guildHall() {
        val top = 9
        val door = w / 2 - 1
        val counter = d - 5
        clear(top)
        walls(0, top, STONE)
        ring(0, DARK)
        floorAt(-1, B(M.PAVE_PLAZA, v = 1))
        floorAt(top, B(M.PLANKS, v = 1))
        // Pilasters at the corners and every fourth block down the sides
        for (v in (0 until d step 4) + (d - 1)) for (y in 1..top) {
            f.set(0, y, v, TRIM)
            f.set(w - 1, y, v, TRIM)
        }
        // Tall arched windows in the bays down the hall and either side of the door
        for (v in 1 until counter) if (v % 4 != 0) for (y in arch(v % 4 == 2, 2, 6)) {
            f.set(0, y, v, WINDOW)
            f.set(w - 1, y, v, WINDOW)
        }
        for (u in 1..3) for (y in arch(u == 2, 2, 6)) {
            f.set(u, y, 0, WINDOW)
            f.set(w - 1 - u, y, 0, WINDOW)
        }
        // Double doors under a fanlight and a lintel, a lamp either side
        f.door(door, 0, 0, hingeRight = true)
        f.door(door + 1, 0, 0)
        for (u in door..door + 1) f.set(u, 2, 0, B(M.GLASS))
        for (u in door - 1..door + 2) f.set(u, 3, 0, TRIM)
        for (u in listOf(door - 1, door + 2)) {
            f.set(u, 0, -1, B(M.WALL_POST))
            f.set(u, 1, -1, B(M.LANTERN))
        }
        Buildings.roof(f, w, d, 0, top, ROOF_SLATE, true, STONE)
        // The gable: a round window framed in trim, the guild's banners under the eaves
        for (u in door..door + 1) {
            f.set(u, top + 1, 0, TRIM)
            f.set(u, top + 4, 0, TRIM)
            for (y in top + 2..top + 3) f.set(u, y, 0, B(M.GLASS))
        }
        for (y in top + 2..top + 3) {
            f.set(door - 1, y, 0, TRIM)
            f.set(door + 2, y, 0, TRIM)
        }
        for (u in listOf(2, w - 3)) f.set(u, top - 2, -1, B(M.BANNER, Dir.N, color = GUILD_COLOR))

        // Inside: a runner to the counter, long tables under hanging lanterns, banners on the pilasters
        for (v in 1 until counter) for (u in door..door + 1) f.set(u, 0, v, B(M.CARPET, color = GUILD_COLOR))
        for (u in listOf(2, w - 3)) {
            for (v in 3..counter - 3) {
                f.set(u, 0, v, TABLE)
                if (v % 2 == 1) {
                    f.set(u - 1, 0, v, chair(Dir.E))
                    f.set(u + 1, 0, v, chair(Dir.W))
                }
            }
            for (v in listOf(4, 8)) chandelier(u, v, top, 3)
        }
        for (v in listOf(4, 8)) {
            f.set(1, 5, v, B(M.BANNER, Dir.E, color = GUILD_COLOR))
            f.set(w - 2, 5, v, B(M.BANNER, Dir.W, color = GUILD_COLOR))
        }
        // The counter across the far end with a way round it, the shelves and the guild's colours behind
        for (u in 1..w - 3) f.set(u, 0, counter, B(M.PLANKS, v = 1))
        f.set(2, 1, counter, B(M.LANTERN))
        f.set(w - 4, 1, counter, B(M.CANDLE))
        for (u in 1..w - 2) for (y in 0..2) if (u !in door..door + 1) f.set(u, y, d - 2, B(M.BOOKSHELF))
        for (u in door..door + 1) f.set(u, 3, d - 2, B(M.BANNER, Dir.N, color = GUILD_COLOR))
        f.set(2, 0, counter + 1, B(M.LECTERN, Dir.N))
        f.chest(1, 0, d - 3, Dir.E, Loot.MARKET)
        f.spawn(Spawn.RECEPTIONIST, door, 0, d - 3, Dir.N)
    }

    // ---- The temple ----

    /**
     * A pale stone temple: a steeple with an open belfry and a slate spire over the porch, three-light
     * windows of stained glass down the nave, pews either side of the aisle, and the altar on a dais
     * under a rose window.
     */
    private fun temple() {
        val top = 11
        val aisle = w / 2 - 1
        val altar = d - 3
        clear(top)
        walls(0, top, FINE)
        ring(0, DARK)
        floorAt(-1, B(M.PAVE_PLAZA, v = 1))
        floorAt(top, B(M.PLANKS, v = 1))
        for (v in (0 until d step 4) + (d - 1)) for (y in 1..top) {
            f.set(0, y, v, PILLAR)
            f.set(w - 1, y, v, PILLAR)
        }
        for (bay in 6 until d - 1 step 4) for (dv in -1..1) for (y in arch(dv == 0, 2, 8)) {
            val color = GLASS_COLORS[Math.floorMod(bay / 4 + y + dv, GLASS_COLORS.size)]
            f.set(0, y, bay + dv, B(M.STAINED, color = color))
            f.set(w - 1, y, bay + dv, B(M.STAINED, color = color))
        }
        Buildings.roof(f, w, d, 0, top, ROOF_SLATE, true, FINE)
        // A rose window over the altar
        for ((du, dy) in ROSE) {
            f.set(aisle + du, top + dy, d - 1, B(M.STAINED, color = if (dy == 3 && du in 0..1) Dye.YELLOW else Dye.RED))
        }

        // The steeple over the porch: corner shafts, an open belfry, a slate spire
        val t0 = aisle - 1
        val t1 = aisle + 2
        val belfry = top + 6
        val eaves = top + 10
        for (u in t0..t1) for (v in 0..3) {
            val rimU = u == t0 || u == t1
            val rimV = v == 0 || v == 3
            for (y in 0 until eaves) {
                val block = when {
                    rimU && rimV -> PILLAR
                    !rimU && !rimV -> if (y == 4 || y == belfry - 1 || y == eaves - 1) B(M.PLANKS, v = 1) else AIR
                    // Through from the porch into the nave under an arch
                    v == 3 && y <= 2 -> AIR
                    y in belfry..belfry + 2 -> AIR
                    else -> FINE
                }
                f.set(u, y, v, block)
            }
        }
        spire(t0 - 1, -1, t1 + 1, 4, eaves, ROOF_SLATE)
        f.set(aisle, belfry + 2, 1, B(M.LANTERN, top = true))
        f.set(aisle + 1, belfry + 2, 2, B(M.LANTERN, top = true))
        f.door(aisle, 0, 0, hingeRight = true)
        f.door(aisle + 1, 0, 0)
        for (u in aisle..aisle + 1) f.set(u, 2, 0, B(M.STAINED, color = Dye.YELLOW))
        f.set(aisle, 3, 1, B(M.LANTERN, top = true))

        // Inside: a runner up the aisle, pews either side, the altar on its dais
        for (v in 4 until altar - 1) for (u in aisle..aisle + 1) f.set(u, 0, v, B(M.CARPET, color = Dye.RED))
        for (v in 5..altar - 3 step 2) for (u in listOf(2, 3, 4, w - 5, w - 4, w - 3)) f.set(u, 0, v, B(M.PLANK_STAIRS, Dir.N))
        for (u in 1..w - 2) for (v in altar - 1..d - 2) f.set(u, 0, v, B(M.FINE_SLAB))
        for (u in aisle - 1..aisle + 2) f.set(u, 0, altar, FINE)
        f.set(aisle - 1, 1, altar, B(M.CANDLE))
        f.set(aisle + 2, 1, altar, B(M.CANDLE))
        f.set(aisle, 1, altar, B(M.GOLD))
        f.set(aisle + 1, 1, altar, B(M.BREWING_STAND))
        for (v in listOf(7, 11)) chandelier(aisle, v, top, 4)
        // The priest's lectern at the head of the aisle, read from the nave
        f.set(aisle, 0, altar - 2, B(M.LECTERN, Dir.N))
        f.chest(w - 2, 0, d - 2, Dir.W, Loot.TEMPLE)
        f.spawn(Spawn.VILLAGER, aisle + 1, 0, altar - 4, Dir.S)
        f.resident(Folk.PRIEST, aisle, 0, altar - 3, work = f.at(aisle, 0, altar - 2), facing = Dir.S, home = f.at(2, 0, 2))
    }

    // ---- The tavern ----

    /**
     * A big timbered inn three floors high, its upper floors out over the street on brackets: the
     * taproom below with its tables, the bar and a fire in the wall; rooms off a passage upstairs, and
     * the innkeeper's own floor under the roof.
     */
    private fun tavern() {
        val floors = 3
        val top = floors * STORY - 1
        val door = w / 2 - 1
        val sign = TAVERN_COLORS[r.nextInt(TAVERN_COLORS.size)]
        clear(top)
        f.fill(1, STORY - 1, 0, w - 2, top, 0, AIR)
        floorAt(-1, B(M.PLANKS, v = 1))
        for (k in 1 until floors) f.fill(1, k * STORY - 1, 0, w - 2, k * STORY - 1, d - 2, B(M.PLANKS))
        f.fill(1, top, 0, w - 2, top, d - 2, B(M.PLANKS))
        walls(0, STORY - 2, STONE)
        val posts = setOf(0, door - 1, door + 2, w - 1)
        timberStorey(STORY - 1, top, v0 = -1, frontPosts = posts)
        for (u in posts) f.set(u, STORY - 2, -1, B(M.PLANK_STAIRS, Dir.S, top = true))
        // Windows with shutters on the jutting floors, a window to each bay down the sides
        for (k in 1 until floors) {
            val y = k * STORY
            for (u in listOf(2, door, door + 1, w - 3)) for (h in 0..1) f.set(u, y + h, -1, WINDOW)
            for (u in listOf(1, 3, w - 4, w - 2)) for (h in 0..1) f.set(u, y + h, -2, B(M.SHUTTER, Dir.N, open = true))
            for (v in 1 until d - 1 step 4) for (h in 0..1) {
                f.set(0, y + h, v, WINDOW)
                f.set(w - 1, y + h, v, WINDOW)
            }
        }
        for (u in listOf(1, 2, w - 3, w - 2)) for (y in 1..2) f.set(u, y, 0, WINDOW)
        for (v in listOf(3, 4, d - 7, d - 6)) for (y in 1..2) f.set(0, y, v, WINDOW)
        f.door(door, 0, 0, hingeRight = true)
        f.door(door + 1, 0, 0)
        for (u in listOf(door - 2, door + 3)) f.set(u, 2, -1, B(M.BANNER, Dir.N, color = sign))
        Buildings.roof(f, w, d, -1, top, ROOF_RED, true, PLASTER)

        // The taproom: tables lit by candles, the bar across the back, barrels behind it
        val bar = d - 5
        for (u in 1..w - 4) f.set(u, 0, bar, B(M.PLANKS, v = 1))
        f.set(2, 1, bar, B(M.LANTERN))
        f.set(w - 5, 1, bar, B(M.CANDLE))
        for (u in 1..w - 6) for (y in 0..1) f.set(u, y, d - 2, B(M.BARREL, Dir.N))
        f.set(w - 4, 0, d - 2, B(M.SMOKER, Dir.N))
        for ((u, v) in listOf(3 to 4, 7 to 4, 3 to 8, 7 to 8)) {
            f.set(u, 0, v, TABLE)
            f.set(u, 1, v, B(M.CANDLE))
            f.set(u - 1, 0, v, chair(Dir.E))
            f.set(u + 1, 0, v, chair(Dir.W))
        }
        for (v in listOf(4, 8)) f.set(5, STORY - 2, v, B(M.LANTERN, top = true))
        // The fire in the east wall, its chimney up through the roof
        val hearth = 6
        for (y in 0..2) {
            f.set(w - 2, y, hearth - 1, STONE)
            f.set(w - 2, y, hearth + 1, STONE)
        }
        f.set(w - 2, 2, hearth, STONE)
        f.set(w - 2, 0, hearth, B(M.CAMPFIRE))
        for (y in 0..top + 5) f.set(w - 1, y, hearth, STONE)
        f.set(w - 1, top + 6, hearth, B(M.CAMPFIRE))
        f.spawn(Spawn.VILLAGER, 4, 0, d - 3)
        // The innkeeper behind the bar, with a room upstairs
        f.resident(Folk.INNKEEPER, door, 0, d - 4, facing = Dir.N, home = f.at(2, STORY, 7))
        // Stairs up the east side to each floor
        for (k in 0 until floors - 1) stairs(w - 2, k * STORY, 1 + k * 5)

        // Rooms off a passage upstairs: a bed, a chest and a lamp in each
        val y1 = STORY
        val ceiling = 2 * STORY - 1
        for (v in 0..d - 2) for (y in y1 until ceiling) f.set(4, y, v, B(M.PLANKS))
        for (wall in listOf(4, 10)) for (u in 1..3) for (y in y1 until ceiling) f.set(u, y, wall, B(M.PLANKS))
        for ((i, room) in listOf(0..3, 5..9, 11..d - 2).withIndex()) {
            f.door(4, y1, room.first + 2, inward = Dir.W)
            f.bed(2, y1, room.first + 1, Dir.W, Buildings.BED_COLORS[i])
            f.chest(1, y1, room.last, Dir.E, Loot.TAVERN)
            f.set(2, ceiling - 1, room.first + 2, B(M.LANTERN, top = true))
        }
        f.set(7, ceiling - 1, 4, B(M.LANTERN, top = true))
        // The innkeeper's floor under the roof
        val y2 = 2 * STORY
        f.bed(2, y2, 8, Dir.W, Dye.BROWN)
        f.chest(1, y2, 10, Dir.E, Loot.TAVERN)
        for (v in 1..4) for (u in 1..2) f.set(u, y2, v, B(M.HAY))
        for (u in 5..8) f.set(u, y2, d - 2, B(M.BARREL, Dir.N))
        f.set(1, y2, 6, B(M.BOOKSHELF))
        f.set(5, top - 1, 8, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, 4, y2, 8)
    }

    // ---- The library ----

    /** Stone below and timber above: walls of shelves, lecterns and a map table, the stacks upstairs. */
    private fun library() {
        val top = 2 * STORY - 1
        val door = w / 2 - 1
        clear(top)
        walls(0, STORY - 2, STONE)
        timberStorey(STORY - 1, top)
        floorAt(-1, B(M.PLANKS, v = 1))
        floorAt(STORY - 1, B(M.PLANKS))
        floorAt(top, B(M.PLANKS))
        f.door(door, 0, 0, hingeRight = true)
        f.door(door + 1, 0, 0)
        for (u in listOf(1, 2, w - 3, w - 2)) for (y in 1..2) f.set(u, y, 0, WINDOW)
        for (u in listOf(2, door, door + 1, w - 3)) for (y in STORY..STORY + 1) f.set(u, y, 0, WINDOW)
        for (v in 2 until d - 1 step 4) for (y in listOf(1, STORY, STORY + 1)) {
            f.set(0, y, v, WINDOW)
            f.set(w - 1, y, v, WINDOW)
        }
        Buildings.roof(f, w, d, 0, top, r.nextInt(3), true, PLASTER)
        // Shelves round the walls, broken by the windows; lecterns, the map table and a reading table
        for (v in 2..d - 2) for (y in 0..2) {
            if (y == 1 && v % 4 == 2) continue
            f.set(1, y, v, B(M.BOOKSHELF))
            f.set(w - 2, y, v, B(M.BOOKSHELF))
        }
        for (u in 2..w - 3) for (y in 0..2) if (u !in door..door + 1) f.set(u, y, d - 2, B(M.BOOKSHELF))
        f.set(door - 1, 0, 5, B(M.LECTERN, Dir.S))
        f.set(door + 2, 0, 5, B(M.LECTERN, Dir.S))
        f.set(door, 0, 9, B(M.CARTOGRAPHY_TABLE))
        for (u in door - 1..door + 2) f.set(u, 0, 12, TABLE)
        f.set(door, 1, 12, B(M.CANDLE))
        for (u in listOf(door - 1, door + 2)) f.set(u, 0, 13, chair(Dir.N))
        f.set(door, STORY - 2, 7, B(M.LANTERN, top = true))
        // A ladder up to the stacks
        for (y in 0 until STORY) f.set(w - 3, y, 3, B(M.LADDER, Dir.W))
        val y1 = STORY
        for (v in listOf(5, 9, 13)) for (u in (2..4) + (w - 5..w - 3)) for (y in y1..y1 + 1) f.set(u, y, v, B(M.BOOKSHELF))
        f.chest(w - 2, y1, d - 2, Dir.W, Loot.LIBRARY)
        f.set(door, y1, d - 3, B(M.LECTERN, Dir.N))
        f.set(door, top - 1, 8, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, door, 0, 7)
        // A scholar at the map table
        f.resident(Folk.CITIZEN, door + 1, 0, 8, work = f.at(door, 0, 9))
    }

    // ---- The market hall ----

    /** An open arcade on stone piers with a timber hall above: produce stalls underneath, the merchants' store upstairs. */
    private fun marketHall() {
        val top = 2 * STORY - 1
        f.fill(0, -1, 0, w - 1, -1, d - 1, B(M.PAVE_PLAZA, v = 2))
        f.fill(0, 0, 0, w - 1, top, d - 1, AIR)
        val piersU = setOf(0, 3, w - 4, w - 1)
        val piersV = setOf(0, 3, d - 4, d - 1)
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            if (u in piersU && v in piersV) for (y in 0..STORY - 2) f.set(u, y, v, STONE)
            else f.set(u, STORY - 2, v, B(M.STONE_SLAB, top = true))
        }
        f.fill(0, STORY - 1, 0, w - 1, STORY - 1, d - 1, B(M.PLANKS))
        timberStorey(STORY, top)
        floorAt(top, B(M.PLANKS))
        for (u in listOf(2, w / 2 - 1, w / 2, w - 3)) for (y in STORY..STORY + 1) {
            f.set(u, y, 0, WINDOW)
            f.set(u, y, d - 1, WINDOW)
        }
        Buildings.roof(f, w, d, 0, top, ROOF_RED, false, PLASTER)
        // The stalls underneath
        val goods = listOf(B(M.HAY), B(M.COMPOSTER), B(M.BARREL, Dir.N), B(M.SMOKER, Dir.N))
        for ((i, spot) in listOf(2 to 4, w - 4 to 4, 2 to d - 5, w - 4 to d - 5).withIndex()) {
            val (u, v) = spot
            f.set(u, 0, v, TABLE)
            f.set(u + 1, 0, v, TABLE)
            f.set(u, 0, v + 1, goods[i])
            f.set(u + 1, 1, v, B(M.LANTERN))
        }
        f.set(w / 2, STORY - 2, d / 2, B(M.LANTERN, top = true))
        for (y in 0..STORY) f.set(1, y, 3, B(M.LADDER, Dir.E))
        // Upstairs: the merchants' store
        val y1 = STORY
        for (u in 2..w - 2) f.set(u, y1, d - 2, B(M.BARREL, Dir.N))
        for (u in 3..5) f.set(u, y1, 1, B(M.HAY))
        f.chest(w - 2, y1, 1, Dir.W, Loot.MARKET)
        f.set(w / 2, top - 1, d / 2, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, w / 2, 0, d / 2)
        f.spawn(Spawn.VILLAGER, w / 2 - 1, 0, d / 2 + 2)
        // Townsfolk keep two of the stalls, from behind their tables
        f.resident(Folk.MERCHANT, 3, 0, 5, facing = Dir.N)
        f.resident(Folk.MERCHANT, w - 3, 0, d - 4, facing = Dir.N)
    }

    // ---- The counting house ----

    /** A moneylender's house of dressed stone under a hipped roof: clerks' desks, a strongroom behind bars. */
    private fun countingHouse() {
        val top = 2 * STORY - 1
        val door = w / 2 - 1
        clear(top)
        walls(0, top, STONE)
        ring(0, DARK)
        ring(STORY - 1, TRIM)
        ring(top, TRIM)
        floorAt(-1, B(M.PAVE_PLAZA, v = 2))
        floorAt(STORY - 1, B(M.PLANKS, v = 1))
        floorAt(top, B(M.PLANKS, v = 1))
        for (u in listOf(2, 3, w - 4, w - 3)) for (y in listOf(1, 2, STORY, STORY + 1)) f.set(u, y, 0, WINDOW)
        for (v in listOf(3, d - 4)) for (y in listOf(1, 2, STORY, STORY + 1)) {
            f.set(0, y, v, WINDOW)
            f.set(w - 1, y, v, WINDOW)
        }
        f.door(door, 0, 0, hingeRight = true)
        f.door(door + 1, 0, 0)
        for (u in door..door + 1) f.set(u, 2, 0, TRIM)
        pyramid(-1, -1, w, d, top, ROOF_SLATE)
        // Desks for the clerks, the strongroom behind iron bars
        for (u in listOf(2, w - 4)) {
            f.set(u, 0, 4, TABLE)
            f.set(u + 1, 0, 4, TABLE)
            f.set(u, 1, 4, B(M.CANDLE))
            f.set(u + 1, 0, 5, chair(Dir.N))
        }
        val bars = d - 4
        for (u in 1..w - 2) for (y in 0..2) f.set(u, y, bars, if (u in door..door + 1 && y < 2) AIR else B(M.BARS))
        f.chest(2, 0, d - 2, Dir.N, Loot.TREASURY)
        f.chest(w - 3, 0, d - 2, Dir.N, Loot.MARKET)
        for (u in door..door + 1) f.set(u, 0, d - 2, B(M.GOLD))
        f.set(door, STORY - 2, 5, B(M.LANTERN, top = true))
        for (y in 0..STORY) f.set(1, y, 1, B(M.LADDER, Dir.S))
        f.set(w - 2, 0, 2, B(M.CARTOGRAPHY_TABLE))
        // Upstairs: the moneylender's room
        val y1 = STORY
        f.bed(w - 3, y1, d - 3, Dir.S, Dye.PURPLE)
        for (u in 2..4) f.set(u, y1, d - 2, B(M.BOOKSHELF))
        f.chest(w - 2, y1, 2, Dir.W, Loot.LIBRARY)
        f.set(door, top - 1, 5, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, door, 0, 6)
        f.resident(Folk.NOBLE, door + 1, 0, 2)
    }

    // ---- The smithy ----

    /**
     * The forge out front, open to the street under the shop's roof running on down over it: anvil,
     * grindstone, smithing table, a lava trough and the blast furnace with its chimney. The smith's shop
     * and room behind.
     */
    private fun smithy() {
        val shop = 7
        val top = 2 * STORY - 1
        val hearth = w / 2
        // The forge yard: low side walls, posts and a beam along the front
        f.fill(0, -1, 0, w - 1, -1, shop - 1, B(M.PAVE_STREET, v = 1))
        f.fill(0, 0, 0, w - 1, top + 6, shop - 1, AIR)
        for (v in 1 until shop) for (y in 0..1) {
            f.set(0, y, v, STONE)
            f.set(w - 1, y, v, STONE)
        }
        for (u in listOf(0, w / 2 - 2, w / 2 + 1, w - 1)) for (y in 0..2) f.set(u, y, 0, B(M.TIMBER))
        for (u in 0 until w) f.set(u, 3, 0, B(M.TIMBER, axis = Axis.X))
        // The shop: stone below, timber above
        f.fill(1, 0, shop + 1, w - 2, top, d - 2, AIR)
        for (u in 0 until w) for (v in shop until d) {
            if (u == 0 || u == w - 1 || v == shop || v == d - 1) for (y in 0..STORY - 2) f.set(u, y, v, STONE)
        }
        timberStorey(STORY - 1, top, v0 = shop)
        f.fill(1, -1, shop + 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        f.fill(1, STORY - 1, shop + 1, w - 2, STORY - 1, d - 2, B(M.PLANKS))
        f.fill(1, top, shop + 1, w - 2, top, d - 2, B(M.PLANKS))
        Buildings.roof(f, w, d, shop, top, ROOF_RED, false, PLASTER)
        // Its front slope runs on down over the forge in half steps
        for (v in -1 until shop - 1) {
            val n = shop - 2 - v
            for (u in -1..w) f.set(u, top - 1 - n / 2, v, B(M.ROOF_SLAB, v = ROOF_RED, top = n % 2 == 0))
        }
        f.door(2, 0, shop)
        for (u in w - 4..w - 3) f.set(u, 1, shop, WINDOW)
        for (v in listOf(shop + 2, d - 3)) for (y in listOf(1, STORY, STORY + 1)) {
            f.set(0, y, v, WINDOW)
            f.set(w - 1, y, v, WINDOW)
        }
        // The furnace and its hood against the shop wall, the chimney up past the ridge
        for (u in hearth - 1..hearth + 1) for (y in 0..2) f.set(u, y, shop - 1, STONE)
        f.set(hearth, 0, shop - 1, B(M.BLAST_FURNACE, Dir.N))
        for (y in 3..top + 5) f.set(hearth, y, shop - 1, STONE)
        f.set(hearth, top + 6, shop - 1, B(M.CAMPFIRE))
        f.set(hearth - 2, 0, shop - 1, B(M.CAULDRON, v = 1))
        f.set(hearth + 2, 0, shop - 1, B(M.CAULDRON, v = 2))
        f.set(2, 0, 3, B(M.ANVIL, Dir.E))
        f.set(4, 0, 2, B(M.GRINDSTONE, Dir.N))
        f.set(w - 3, 0, 2, B(M.SMITHING_TABLE))
        f.set(w - 2, 0, shop - 1, B(M.BARREL, Dir.W))
        f.chest(1, 0, shop - 1, Dir.E, Loot.SMITH)
        for (u in listOf(3, w - 4)) f.set(u, 4, 2, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, 4, 0, 4)
        f.spawn(Spawn.VILLAGER, w - 4, 0, 4)
        // The smith at the anvil, the quench and the hearth to hand, living in the shop behind
        f.resident(Folk.SMITH, 2, 0, 4, work = f.at(2, 0, 3), home = f.at(w / 2, 0, shop + 3))
        // The shop and the smith's room over it
        f.chest(1, 0, d - 2, Dir.E, Loot.SMITH)
        f.set(w - 2, 0, d - 2, B(M.FURNACE, Dir.W))
        for (u in 3..5) f.set(u, 0, d - 2, B(M.BARREL, Dir.N))
        for (y in 0..STORY) f.set(w - 2, y, shop + 1, B(M.LADDER, Dir.S))
        f.set(w / 2, STORY - 2, shop + 4, B(M.LANTERN, top = true))
        f.bed(2, STORY, d - 3, Dir.S, Dye.GRAY)
        f.chest(1, STORY, shop + 1, Dir.E, Loot.HOUSE)
        f.set(w / 2, top - 1, shop + 4, B(M.LANTERN, top = true))
    }

    // ---- The bakery ----

    /** A timbered bakehouse round a great oven: sacks and barrels, bread on the counter, the baker's room upstairs. */
    private fun bakery() {
        val top = 2 * STORY - 1
        val door = w / 2 - 1
        clear(top)
        walls(0, STORY - 2, STONE)
        timberStorey(STORY - 1, top)
        floorAt(-1, B(M.PLANKS, v = 1))
        floorAt(STORY - 1, B(M.PLANKS))
        floorAt(top, B(M.PLANKS))
        f.door(door, 0, 0, hingeRight = true)
        f.door(door + 1, 0, 0)
        for (u in listOf(1, 2, w - 3, w - 2)) for (y in 1..2) f.set(u, y, 0, WINDOW)
        for (u in listOf(2, door, door + 1, w - 3)) for (y in STORY..STORY + 1) f.set(u, y, 0, WINDOW)
        for (v in 2 until d - 1 step 4) for (y in listOf(1, STORY, STORY + 1)) {
            f.set(0, y, v, WINDOW)
            f.set(w - 1, y, v, WINDOW)
        }
        for (u in listOf(door - 1, door + 2)) f.set(u, 2, -1, B(M.BANNER, Dir.N, color = Dye.YELLOW))
        Buildings.roof(f, w, d, 0, top, r.nextInt(3), true, PLASTER)
        // The oven: a block of stone with fire in its mouth, its flue up through the roof
        for (u in w - 5..w - 2) for (v in d - 5..d - 2) for (y in 0..2) f.set(u, y, v, STONE)
        f.set(w - 4, 0, d - 5, B(M.SMOKER, Dir.N))
        f.set(w - 3, 0, d - 5, B(M.FURNACE, Dir.N))
        for (y in 3..top + 6) f.set(w - 3, y, d - 3, STONE)
        f.set(w - 3, top + 7, d - 3, B(M.CAMPFIRE))
        // A table by the oven to knead the dough on
        f.set(w - 6, 0, d - 6, TABLE)
        // The counter, sacks of flour and barrels, the compost bin out back
        for (u in 2..door + 1) f.set(u, 0, 4, B(M.PLANKS, v = 1))
        f.set(3, 1, 4, B(M.HAY))
        f.set(door, 1, 4, B(M.CANDLE))
        for (v in 7..10) f.set(1, 0, v, B(M.HAY))
        for (v in 7..9) f.set(1, 1, v, B(M.BARREL, Dir.E))
        f.set(2, 0, d - 2, B(M.COMPOSTER))
        f.chest(1, 0, d - 2, Dir.E, Loot.HOUSE)
        f.set(4, STORY - 2, 7, B(M.LANTERN, top = true))
        for (y in 0..STORY) f.set(w - 2, y, 5, B(M.LADDER, Dir.W))
        f.bed(2, STORY, d - 4, Dir.S, Dye.YELLOW)
        f.set(3, top - 1, 6, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, 4, 0, 6)
        f.spawn(Spawn.CAT, 3, 0, 9)
        f.resident(Folk.BAKER, w - 3, 0, d - 6, work = f.at(w - 3, 0, d - 5), home = f.at(door, 0, 8))
    }

    // ---- The keep ----

    /**
     * The keep: a great block of stone three floors high with round turrets on its corners under slate
     * cones. The great hall below has pillars, a long runner and the throne on its dais; above are the
     * lord's floor and the war room, and the roof is a fighting platform under the city's flag.
     */
    private fun keep() {
        val floor = 5
        val deck = 3 * floor - 1
        val door = w / 2
        clear(deck)
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            for (y in 0..deck + 1) {
                f.set(u, y, v, when {
                    y == 0 -> DARK
                    y < deck && y % floor == floor - 1 -> TRIM
                    else -> WALL
                })
            }
            if ((u + v) % 2 == 0) f.set(u, deck + 2, v, WALL)
        }
        floorAt(-1, B(M.PAVE_PLAZA, v = 1))
        for (level in 1..2) floorAt(level * floor - 1, B(M.PLANKS, v = 1))
        floorAt(deck, WALL)
        // Narrow windows on every floor
        for (level in 0..2) for (h in 1..2) {
            val y = level * floor + h
            for (u in listOf(3, 6, w - 7, w - 4)) {
                f.set(u, y, 0, WINDOW)
                f.set(u, y, d - 1, WINDOW)
            }
            for (v in listOf(4, 8, 11)) {
                f.set(0, y, v, WINDOW)
                f.set(w - 1, y, v, WINDOW)
            }
        }
        // The great doorway: three wide under an arch, the portcullis drawn up
        for (u in door - 1..door + 1) for (y in 0..3) {
            f.set(u, y, 0, if (y == 3 && u != door) B(M.WALL_STAIRS, if (u < door) Dir.W else Dir.E, top = true) else AIR)
        }
        f.set(door, 3, 0, B(M.BARS))
        for (u in listOf(door - 2, door + 2)) {
            f.set(u, 2, -1, B(M.TORCH, Dir.N))
            f.set(u, 6, -1, B(M.BANNER, Dir.N, color = CityBuilder.BANNER_COLOR))
        }

        // The great hall: two rows of pillars, a runner to the dais, the throne
        for (u in listOf(5, w - 6)) for (v in listOf(3, 6, 9, 12)) for (y in 0 until floor - 1) f.set(u, y, v, PILLAR)
        for (v in 1..10) for (u in door - 1..door + 1) f.set(u, 0, v, B(M.CARPET, color = Dye.RED))
        for (u in door - 4..door + 4) {
            f.set(u, 0, 11, B(M.WALL_STAIRS, Dir.S))
            for (v in 12..d - 2) f.set(u, 0, v, TRIM)
        }
        f.set(door, 1, 13, B(M.FINE_STAIRS, Dir.S))
        f.set(door - 1, 1, 14, B(M.GOLD))
        f.set(door + 1, 1, 14, B(M.GOLD))
        for (u in door - 2..door + 2) f.set(u, 3, d - 2, B(M.BANNER, Dir.N, color = if (u == door) Dye.YELLOW else CityBuilder.BANNER_COLOR))
        for (u in listOf(2, w - 3)) for (v in 3..9) {
            f.set(u, 0, v, TABLE)
            if (v % 2 == 1) f.set(if (u < door) u + 1 else u - 1, 0, v, chair(if (u < door) Dir.W else Dir.E))
        }
        for (u in listOf(5, door, w - 6)) for (v in listOf(4, 8)) f.set(u, floor - 2, v, B(M.LANTERN, top = true))
        f.spawn(Spawn.GOLEM, door - 3, 0, 8)
        f.spawn(Spawn.GOLEM, door + 3, 0, 8)
        // Lords of the court
        for (u in listOf(door - 2, door + 2)) f.resident(Folk.NOBLE, u, 0, 4)

        // Stairs up the west wall to the lord's floor
        for (k in 0 until floor) {
            val v = d - 4 - k
            for (y in 0 until k) f.set(1, y, v, WALL)
            f.set(1, k, v, B(M.WALL_STAIRS, Dir.N))
            if (k < floor - 1) f.set(1, floor - 1, v, AIR)
        }
        // The lord's floor: a bedchamber, the treasury chests, a long table, the armoury
        val y1 = floor
        f.bed(w - 4, y1, 12, Dir.S, Dye.RED)
        f.chest(w - 5, y1, d - 2, Dir.N, Loot.TREASURY)
        f.chest(w - 6, y1, d - 2, Dir.N, Loot.TREASURY)
        for (u in w - 6..w - 2) f.set(u, y1, 10, B(M.BOOKSHELF))
        for (u in 6..w - 7) f.set(u, y1, 6, TABLE)
        for (u in 6..w - 7 step 2) {
            f.set(u, y1, 5, chair(Dir.S))
            f.set(u, y1, 7, chair(Dir.N))
        }
        for (u in listOf(5, door, w - 6)) f.set(u, 2 * floor - 2, 6, B(M.LANTERN, top = true))
        f.set(3, y1, 3, B(M.BLAST_FURNACE, Dir.S))
        f.set(4, y1, 3, B(M.GRINDSTONE, Dir.S))
        f.spawn(Spawn.VILLAGER, door, y1, 9)
        // A ladder from the lord's floor up through the war room to the roof
        val ladder = w - 5
        for (y in y1 until deck) f.set(ladder, y, 1, B(M.LADDER, Dir.S))
        f.set(ladder, deck, 1, B(M.TRAPDOOR, Dir.S, top = true))
        val y2 = 2 * floor
        f.set(door, y2, 7, B(M.CARTOGRAPHY_TABLE))
        for (u in door - 2..door + 2) f.set(u, y2 + 1, d - 2, B(M.BANNER, Dir.N, color = CityBuilder.BANNER_COLOR))
        f.chest(3, y2, 1, Dir.S, Loot.BARRACKS)
        f.set(door, deck - 1, 7, B(M.LANTERN, top = true))
        // The city's flag over the roof
        for (y in deck + 1..deck + 6) f.set(door, y, 7, B(M.FENCE))
        for (u in door + 1..door + 3) for (y in deck + 4..deck + 5) f.set(u, y, 7, B(M.WOOL, color = CityBuilder.BANNER_COLOR))

        // Round turrets on the corners, rising above the roof under slate cones
        for (cu in listOf(0, w - 1)) for (cv in listOf(0, d - 1)) turret(cu, cv, deck, deck + 5)
    }

    /** A round turret on a corner of the keep: solid up to the roof, a room above it opening onto the roof, a cone. */
    private fun turret(cu: Int, cv: Int, deck: Int, eaves: Int) {
        val radius = 2.5
        for (du in -3..3) for (dv in -3..3) {
            val dist = sqrt((du * du + dv * dv).toDouble())
            if (dist > radius) continue
            val u = cu + du
            val v = cv + dv
            val shell = dist > 1.5
            for (y in 0 until eaves) {
                f.set(u, y, v, when {
                    y == 0 -> DARK
                    y <= deck -> WALL
                    !shell -> AIR
                    // Doorways in from the roof
                    insideKeep(u, v) && y <= deck + 2 -> AIR
                    y == deck + 3 && (du == 0 || dv == 0) -> WINDOW
                    else -> WALL
                })
            }
        }
        f.set(cu, eaves - 1, cv, B(M.LANTERN, top = true))
        cone(cu, cv, radius, eaves, 7, ROOF_SLATE)
    }

    private fun insideKeep(u: Int, v: Int): Boolean = u in 1..w - 2 && v in 1..d - 2

    // ---- The barracks ----

    /** Long and low in stone: the armoury and the mess below, bunks upstairs along the back wall. */
    private fun barracks() {
        val top = 2 * STORY - 1
        val door = w / 2 - 1
        clear(top)
        walls(0, top, WALL)
        ring(0, DARK)
        ring(STORY - 1, TRIM)
        floorAt(-1, B(M.PAVE_PLAZA, v = 2))
        floorAt(STORY - 1, B(M.PLANKS, v = 1))
        floorAt(top, B(M.PLANKS, v = 1))
        for (u in listOf(2, 3, w - 4, w - 3)) for (y in listOf(1, 2, STORY, STORY + 1)) f.set(u, y, 0, WINDOW)
        for (v in listOf(3, d - 4)) for (y in listOf(1, 2, STORY, STORY + 1)) {
            f.set(0, y, v, WINDOW)
            f.set(w - 1, y, v, WINDOW)
        }
        f.door(door, 0, 0, hingeRight = true)
        f.door(door + 1, 0, 0)
        for (u in door..door + 1) f.set(u, 2, 0, TRIM)
        for (u in listOf(door - 1, door + 2)) f.set(u, 2, -1, B(M.BANNER, Dir.N, color = CityBuilder.BANNER_COLOR))
        Buildings.roof(f, w, d, 0, top, ROOF_SLATE, false, WALL)
        // The armoury at one end, the smiths' tools at the other, the mess table between
        f.set(1, 0, 3, B(M.GRINDSTONE, Dir.E))
        f.set(1, 0, 5, B(M.ANVIL, Dir.N))
        f.chest(1, 0, 7, Dir.E, Loot.BARRACKS)
        f.set(1, 0, 9, B(M.BARREL, Dir.E))
        f.set(w - 2, 0, 3, B(M.BLAST_FURNACE, Dir.W))
        f.set(w - 2, 0, 5, B(M.SMITHING_TABLE))
        f.chest(w - 2, 0, 7, Dir.W, Loot.BARRACKS)
        f.set(w - 2, 0, 9, B(M.BARREL, Dir.W))
        for (u in 5..w - 6) {
            f.set(u, 0, 6, TABLE)
            if (u % 2 == 1) {
                f.set(u, 0, 5, chair(Dir.S))
                f.set(u, 0, 7, chair(Dir.N))
            }
        }
        for (u in listOf(5, w - 6)) f.set(u, STORY - 2, 6, B(M.LANTERN, top = true))
        val ladder = w - 5
        for (y in 0..STORY) f.set(ladder, y, d - 2, B(M.LADDER, Dir.N))
        // Bunks upstairs, each with a chest at its head
        val y1 = STORY
        for (u in listOf(1, 4, 7, w - 3)) {
            f.bed(u, y1, d - 3, Dir.S, Dye.BLUE)
            f.chest(u + 1, y1, d - 2, Dir.N, Loot.BARRACKS)
        }
        for (u in listOf(4, w - 5)) f.set(u, top - 1, 5, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, door, 0, 3)
        f.spawn(Spawn.VILLAGER, door + 1, 0, 8)
        f.spawn(Spawn.GOLEM, door, 0, -3, Dir.N)
    }

    // ---- The stables ----

    /** An open-fronted timber stable: stalls with hay and water and horses in them, a hayloft above. */
    private fun stables() {
        val top = STORY + 1
        f.fill(0, -1, 0, w - 1, -1, d - 1, B(M.PAVE_LANE, v = 1))
        f.fill(1, 0, 0, w - 2, top, d - 2, AIR)
        for (u in 0 until w) for (v in 0 until d) {
            val side = u == 0 || u == w - 1
            val back = v == d - 1
            if (!side && !back) continue
            for (y in 0..top) {
                f.set(u, y, v, when {
                    y == 0 -> STONE
                    (side && back) || (side && v % 4 == 0) || (back && u % 4 == 0) -> B(M.TIMBER)
                    y == top -> B(M.TIMBER, axis = if (back) Axis.X else Axis.Z)
                    else -> B(M.PLANKS)
                })
            }
        }
        // The open front: posts and a beam, the loft floor over the stalls
        f.fill(1, top - 1, 0, w - 2, top - 1, d - 2, B(M.PLANKS))
        for (u in 0 until w) f.set(u, top, 0, B(M.TIMBER, axis = Axis.X))
        for (u in listOf(0, 4, 8, w - 1)) for (y in 0 until top) f.set(u, y, 0, B(M.TIMBER))
        Buildings.roof(f, w, d, 0, top, ROOF_WOOD, false, B(M.PLANKS))
        // Stalls along the back: fenced sides, a gate, hay and a water trough, a horse in each
        val stallV = d - 7
        for (v in stallV until d - 1) for (u in listOf(4, 8)) f.set(u, 0, v, B(M.FENCE))
        for (u in 1..w - 2) if (u != 4 && u != 8) f.set(u, 0, stallV, if (u in listOf(2, 6, 10)) B(M.FENCE_GATE, Dir.N) else B(M.FENCE))
        for (stall in listOf(1..3, 5..7, 9..w - 2)) {
            f.set(stall.first, 0, d - 2, B(M.HAY))
            f.set(stall.last, 0, d - 2, B(M.CAULDRON, v = 2))
            f.spawn(Spawn.HORSE, (stall.first + stall.last) / 2, 0, d - 4)
        }
        // The hayloft, up a ladder by the door
        for (u in 1..w - 2) f.set(u, top, d - 3, B(M.HAY))
        for (y in 0 until top) f.set(1, y, 1, B(M.LADDER, Dir.E))
        f.set(w / 2, top - 2, 2, B(M.LANTERN, top = true))
        f.chest(w - 2, 0, 1, Dir.W, Loot.STABLES)
    }

    // ---- Pieces ----

    private fun onRing(u: Int, v: Int): Boolean = u == 0 || u == w - 1 || v == 0 || v == d - 1

    /** Everything inside the walls cleared from the ground up to [top]. */
    private fun clear(top: Int) = f.fill(1, 0, 1, w - 2, top, d - 2, AIR)

    /** The outer walls from [y0] up to [y1]. */
    private fun walls(y0: Int, y1: Int, b: B) {
        for (u in 0 until w) for (v in 0 until d) if (onRing(u, v)) for (y in y0..y1) f.set(u, y, v, b)
    }

    private fun ring(y: Int, b: B) = walls(y, y, b)

    /** A floor (or a ceiling) right across the inside at [y]. */
    private fun floorAt(y: Int, b: B) = f.fill(1, y, 1, w - 2, y, d - 2, b)

    /**
     * Timber framing and plaster from [y0] up to [y1] round the footprint, its front wall at [v0] (a
     * block out over the street when the upper floors jut). Posts stand at the corners and every fourth
     * block, or at [frontPosts] across the front; beams run along at each floor.
     */
    private fun timberStorey(y0: Int, y1: Int, v0: Int = 0, frontPosts: Set<Int>? = null) {
        for (u in 0 until w) for (v in v0 until d) {
            val onFront = v == v0 || v == d - 1
            val onSide = u == 0 || u == w - 1
            if (!onFront && !onSide) continue
            val post = (onFront && (frontPosts?.contains(u) ?: (u % 4 == 0))) || (onSide && (v - v0) % 4 == 0)
            for (y in y0..y1) {
                f.set(u, y, v, when {
                    onFront && onSide -> B(M.TIMBER)
                    (y + 1) % STORY == 0 -> B(M.TIMBER, axis = if (onFront) Axis.X else Axis.Z)
                    post -> B(M.TIMBER)
                    else -> PLASTER
                })
            }
        }
    }

    /** A flight of stairs at [u] climbing south from [v0], one storey up from [y0], through a hole in the floor above. */
    private fun stairs(u: Int, y0: Int, v0: Int) {
        for (k in 0 until STORY) {
            val v = v0 + k
            for (y in y0 until y0 + k) f.set(u, y, v, B(M.PLANKS, v = 1))
            f.set(u, y0 + k, v, B(M.PLANK_STAIRS, Dir.S))
            if (k < STORY - 1) f.set(u, y0 + STORY - 1, v, AIR)
        }
    }

    /** Each cell round the edge of [a0], [b0] to [a1], [b1], with the way in from it. */
    private inline fun edge(a0: Int, b0: Int, a1: Int, b1: Int, action: (Int, Int, Dir) -> Unit) {
        for (u in a0..a1) {
            action(u, b0, Dir.S)
            action(u, b1, Dir.N)
        }
        for (v in b0 + 1 until b1) {
            action(a0, v, Dir.E)
            action(a1, v, Dir.W)
        }
    }

    /** A hipped roof over [u0], [v0] to [u1], [v1], sloping in from every side from [y]. */
    private fun pyramid(u0: Int, v0: Int, u1: Int, v1: Int, y: Int, palette: Int) {
        var k = 0
        while (true) {
            val a0 = u0 + k
            val a1 = u1 - k
            val b0 = v0 + k
            val b1 = v1 - k
            if (a1 - a0 <= 1 || b1 - b0 <= 1) {
                for (u in a0..a1) for (v in b0..b1) {
                    f.set(u, y + k, v, B(M.ROOF, v = palette))
                    f.set(u, y + k + 1, v, B(M.ROOF_SLAB, v = palette))
                }
                return
            }
            edge(a0, b0, a1, b1) { u, v, inward -> f.set(u, y + k, v, B(M.ROOF_STAIRS, inward, v = palette)) }
            k++
        }
    }

    /**
     * A spire over [u0], [v0] to [u1], [v1], its eaves overhanging the tower under it: slate steps in
     * ring by ring to a slender point, with a light on a pole at the top.
     */
    private fun spire(u0: Int, v0: Int, u1: Int, v1: Int, y: Int, palette: Int) {
        var k = 0
        while (true) {
            val a0 = u0 + k
            val a1 = u1 - k
            val b0 = v0 + k
            val b1 = v1 - k
            val at = y + k
            if (a1 - a0 <= 1 || b1 - b0 <= 1) {
                for (u in a0..a1) for (v in b0..b1) for (h in 0..1) f.set(u, at + h, v, B(M.ROOF, v = palette))
                edge(a0, b0, a1, b1) { u, v, inward -> f.set(u, at + 2, v, B(M.ROOF_STAIRS, inward, v = palette)) }
                f.set(a0, at + 3, b0, B(M.FENCE))
                f.set(a0, at + 4, b0, B(M.LANTERN))
                return
            }
            edge(a0, b0, a1, b1) { u, v, inward -> f.set(u, at, v, B(M.ROOF_STAIRS, inward, v = palette)) }
            k++
        }
    }

    /** A round cone of slate over a turret of [radius], overhanging it by a block, with a pole on top. */
    private fun cone(cu: Int, cv: Int, radius: Double, y: Int, height: Int, palette: Int) {
        val base = radius + 1
        val reach = base.toInt() + 1
        for (du in -reach..reach) for (dv in -reach..reach) {
            val dist = sqrt((du * du + dv * dv).toDouble())
            for (k in 0..height) {
                val rk = base - k * (base / height)
                if (dist > rk + 0.01) break
                if (k == 0 || dist > rk - 1.3) {
                    f.set(cu + du, y + k, cv + dv, if (k == 0 && dist > radius) B(M.ROOF_SLAB, v = palette) else B(M.ROOF, v = palette))
                }
            }
        }
        f.set(cu, y + height + 1, cv, B(M.FENCE))
        f.set(cu, y + height + 2, cv, B(M.FENCE))
    }

    /** A lantern hanging from the ceiling at [ceiling], on a chain [drop] blocks long. */
    private fun chandelier(u: Int, v: Int, ceiling: Int, drop: Int) {
        for (y in ceiling - drop until ceiling) f.set(u, y, v, B(M.CHAIN))
        f.set(u, ceiling - drop - 1, v, B(M.LANTERN, top = true))
    }

    /** A seat, looking toward [looks]. */
    private fun chair(looks: Dir): B = B(M.PLANK_STAIRS, looks.opposite)

    /** The heights of a window from [lo] to [hi], or a block shorter at each end beside the middle of an arched one. */
    private fun arch(middle: Boolean, lo: Int, hi: Int): IntRange = if (middle) lo..hi else lo + 1 until hi

    companion object {
        const val STORY = Buildings.STORY
        const val ROOF_SLATE = 0
        const val ROOF_RED = 1
        const val ROOF_WOOD = 2

        val AIR = B.AIR
        val STONE = B(M.STONE)
        val WALL = B(M.WALL)
        val TRIM = B(M.WALL_TRIM)
        val DARK = B(M.WALL_DARK)
        val FINE = B(M.FINE)
        val PILLAR = B(M.FINE_PILLAR)
        val PLASTER = B(M.PLASTER)
        val WINDOW = B(M.WINDOW)
        val TABLE = B(M.PLANK_SLAB, top = true)
        val GUILD_COLOR = Dye.RED
        val TAVERN_COLORS = listOf(Dye.ORANGE, Dye.GREEN, Dye.YELLOW, Dye.PURPLE)
        val GLASS_COLORS = listOf(Dye.BLUE, Dye.RED, Dye.YELLOW, Dye.PURPLE, Dye.LIGHT_BLUE, Dye.ORANGE)
        /** The rose window's panes: across from the aisle and up from the eaves. */
        val ROSE = listOf(0 to 2, 1 to 2, -1 to 3, 0 to 3, 1 to 3, 2 to 3, 0 to 4, 1 to 4)
    }
}
