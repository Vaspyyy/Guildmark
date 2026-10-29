package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.plan.Axis
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Buildings
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.Frame
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.STREET_LAMP
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.homestead.plan.PieceKind
import io.github.vaspyyy.guildmark.homestead.plan.PlanCanvas
import io.github.vaspyyy.guildmark.homestead.plan.Stage

/**
 * The trader's fittings in a drawing (build part F2): each a marker ([WorksDrawing.MARK] on a material no drawing otherwise marks) that
 * TownWorksSite lays as its own block ([io.github.vaspyyy.guildmark.trade.TradeBlocks.marked]), facing the way the marker does.
 */
object TradeMarks {
    /** The clerk's scale on its bench (store_scale; the block above it is left free for its post and beam). */
    val SCALE = B(M.GRINDSTONE, Dir.N, v = WorksDrawing.MARK)

    /** The owner's name board on its bracket (store_name_board in a Store, house_name_board in a Counting House). */
    val NAME_BOARD = B(M.BANNER, Dir.N, v = WorksDrawing.MARK)

    /** The open and shut board by the door (store_door_board). */
    val DOOR_BOARD = B(M.SHUTTER, Dir.N, v = WorksDrawing.MARK)
}

/**
 * The Carriers' Store (living-world/trader/design.md, Part 5; build part F2): a trader's warehouse in a capital, raised by the town's
 * builders from the trader's Marks as a stall of one's own is. Nine across and eight deep on its lot, its front facing the square (the
 * drawing's north): a stone ground floor under a timber loft, a steep roof with its gable to the street, and a yard before it.
 *
 * Seven across and six deep inside its walls (x 1..7, z 1..6). The way in is a door in the middle of the front (x 4); above it a pair of
 * loading doors into the loft, and over the porch a hoist beam on a bracket, its rope falling to the ground before the door (the hoist
 * lifts what is bonded in and out through the loft doors), and under it the owner's name board on its bracket; the plaque on one
 * side of the door and the open and shut board on the other. Inside, the clerk's counter with the scale on its bench in the middle of it and the
 * writing desk behind, crates and sacks along the back, a ladder to the loft; before the door a lamp post either side, and at the front
 * right a flagpole for the owner's pennant (the clerk runs it up when the Store opens: trade/StoreLife).
 *
 * The stages are the works': the stakes and the ground swept, the stone footings and the planks of the floor, the walls course by course
 * with the loft floor and the hoist's bracket, the scaffolding, the roof with its gable ends, the bough and the hoist beam, the doors and
 * the windows with the lanterns hung, the counter, the scale and the stores, and last the porch's flags, the lamp posts and the pole.
 *
 * As the buyer sees it (the front at the foot, x growing to the buyer's left), the ground floor:
 * ```
 *        x 1     x 2     x 3     x 4     x 5     x 6     x 7
 *  z 6   wall    barrel  CHEST   barrel  CHEST   ladder  wall      (the back wall; windows over x 3, x 5)
 *  z 5   wall    .       .       .       .       .       wall
 *  z 4   wall    .       CLERK   desk    .       hay     wall      (the clerk behind the counter, facing the front)
 *  z 3   wall    counter SCALE   counter .       hay     wall      (the scale on its bench, the counter either side)
 *  z 2   wall    barrel  .       .       .       hay     wall
 *  z 1   wall    window  post    DOOR    post    window  wall      (the front wall, the porch before it at z 0)
 * ```
 */
class Store(y0: Int, private val palette: Int, val patron: Dye) : WorksDrawing(WorksKind.STORE, y0) {
    override val lot = LOT
    override val low = -1
    override val high = 12

    /** The lot and a block round it: the scaffolding may stand just outside. */
    override val bounds = Rect(-1, -1, 9, 8)

    /** Its heart: the clerk's place behind the counter, facing the front. */
    override val heart = CLERK
    override val door = DOOR

    /** The works' post while it is wanted: a step out at the front corners of the lot. */
    override val posts = listOf(Pin(0, 0, 0), Pin(1, 0, 0), Pin(0, 0, -1))

    /** Its crate and heaps down either flank, clear of the porch and the loading ground before it. */
    override val yard = listOf(
        Pin(0, 0, 2, Dir.E), Pin(0, 0, 3, Dir.E), Pin(0, 0, 4, Dir.E), Pin(0, 0, 5, Dir.E),
        Pin(8, 0, 2, Dir.W), Pin(8, 0, 3, Dir.W), Pin(8, 0, 4, Dir.W), Pin(8, 0, 5, Dir.W),
    )
    override val plaque = Pin(3, 1, 0, Dir.N)

    override fun drawWorks(p: Pen) {
        for ((x, z) in STAKES) p.stake(x, z)
        p.piece(PieceKind.HOUSE, HOUSE)
        // The lot swept, and the loading ground before the door
        p.stage(Stage.GROUNDWORK)
        for (x in LOT.x0..LOT.x1) for (z in LOT.z0..LOT.z1) if ((x to z) !in STAKES) p.air(x, 0, z)
        for (x in 3..5) p.air(x, 0, -1)
        // The footings under the walls, the planks of the floor, the porch laid in stone
        p.stage(Stage.FOOTINGS)
        for (x in HOUSE.x0..HOUSE.x1) for (z in HOUSE.z0..HOUSE.z1) {
            val ring = x == HOUSE.x0 || x == HOUSE.x1 || z == HOUSE.z0 || z == HOUSE.z1
            p.over(x, -1, z, if (ring) STONE else B(M.PLANKS))
        }
        for (x in 2..6) p.over(x, -1, 0, STONE)
        // The walls course by course (stone to the loft floor, then the timber loft), the loft floor with its hatch for the ladder,
        // and the bracket the hoist stands on, over the porch
        p.stage(Stage.WALLS)
        walls(p)
        for (x in HOUSE.x0 + 1 until HOUSE.x1) for (z in HOUSE.z0 + 1 until HOUSE.z1) if ((x to z) != HATCH) p.set(x, 4, z, B(M.PLANKS))
        p.set(HOIST_AT.x, HOIST_AT.y - 1, HOIST_AT.z, TIMBER)
        // Its roof, the gable to the street
        p.stage(Stage.ROOF)
        Buildings.roof(p.frame(HOUSE), 7, 6, 0, 5, palette, true, BOARDS)
        // The hoist beam over the porch, with the bough
        p.stage(Stage.TOPPING)
        p.set(HOIST_AT.x, HOIST_AT.y, HOIST_AT.z, HOIST)
        // The door and the loading doors, the windows, the lanterns hung inside
        p.stage(Stage.OPENINGS)
        p.door(FRONT_DOOR.x, 0, FRONT_DOOR.z, Dir.S)
        p.door(LOFT_DOORS[0].x, LOFT_DOORS[0].y, LOFT_DOORS[0].z, Dir.S)
        p.door(LOFT_DOORS[1].x, LOFT_DOORS[1].y, LOFT_DOORS[1].z, Dir.S, hingeRight = true)
        for ((x, z) in WINDOWS) p.set(x, 2, z, WINDOW)
        p.set(4, 3, 4, B(M.LANTERN, top = true))
        p.set(6, 3, 2, B(M.LANTERN, top = true))
        p.set(NAME_BOARD_AT.x, NAME_BOARD_AT.y, NAME_BOARD_AT.z, TradeMarks.NAME_BOARD)
        p.set(DOOR_BOARD_AT.x, DOOR_BOARD_AT.y, DOOR_BOARD_AT.z, TradeMarks.DOOR_BOARD)
        // The counter and the scale on it, the writing desk, the stores along the back and by the door, the ladder, the loft's goods
        p.stage(Stage.FITTING)
        for (x in COUNTER_X) if (x != SCALE_AT.x) p.set(x, 0, 3, COUNTER)
        p.set(SCALE_AT.x, SCALE_AT.y, SCALE_AT.z, TradeMarks.SCALE)
        p.set(DESK_AT.x, DESK_AT.y, DESK_AT.z, B(M.LECTERN, DESK_AT.facing))
        for (y in 0..1) {
            p.set(2, y, 5, B(M.BARREL))
            p.set(4, y, 5, B(M.BARREL))
        }
        p.set(2, 0, 2, B(M.BARREL))
        p.set(3, 0, 5, B(M.CHEST, Dir.N))
        p.set(5, 0, 5, B(M.CHEST, Dir.N))
        p.set(5, 1, 5, B(M.CHEST, Dir.N))
        for (z in 2..3) p.set(6, 0, z, B(M.HAY))
        p.set(6, 1, 2, B(M.HAY))
        for (y in 0..4) p.set(LADDER.x, y, LADDER.z, B(M.LADDER, LADDER.facing))
        for (z in 2..3) {
            p.set(2, 5, z, B(M.HAY))
            p.set(6, 5, z, B(M.HAY))
        }
        p.set(2, 6, 2, B(M.HAY))
        p.set(2, 5, 5, B(M.BARREL))
        p.set(3, 5, 5, B(M.BARREL))
        p.set(5, 5, 5, B(M.CHEST, Dir.N))
        // Last the porch: a lamp post either side of the way in, and the owner's pole at the front right
        p.stage(Stage.GROUNDS)
        for (lamp in LAMPS) {
            p.set(lamp.x, 0, lamp.z, B(M.WALL_POST))
            p.set(lamp.x, 1, lamp.z, B(M.FENCE))
            p.set(lamp.x, 2, lamp.z, B(M.FENCE))
            p.set(lamp.x, lamp.y, lamp.z, B(M.LANTERN, v = STREET_LAMP))
        }
        // The pole: a post in a stone foot and a slim staff of fence rails up from it, which the pennant's halyard runs up
        p.set(POLE.x, 0, POLE.z, B(M.WALL_POST))
        for (y in 1..POLE_TOP + 1) p.set(POLE.x, y, POLE.z, B(M.FENCE))
    }

    /**
     * The walls round the house: stone from the ground to the loft floor with a timber post at each corner, over it a loft wall of
     * boards between posts, and a beam along the top; the doorway, the loading doors and the windows left open for their blocks.
     */
    private fun walls(p: Pen) {
        for (x in HOUSE.x0..HOUSE.x1) for (z in HOUSE.z0..HOUSE.z1) {
            val front = z == HOUSE.z0
            val back = z == HOUSE.z1
            val side = x == HOUSE.x0 || x == HOUSE.x1
            if (!front && !back && !side) continue
            val corner = (front || back) && side
            val post = corner || if (front || back) (x - HOUSE.x0) % 2 == 0 else (z - HOUSE.z0) % 2 == 0
            for (y in 0..5) {
                if (front && x == FRONT_DOOR.x && y <= 1) continue
                if (front && y == 5 && LOFT_DOORS.any { it.x == x }) continue
                if (y == 2 && (x to z) in WINDOWS) continue
                p.set(x, y, z, when {
                    corner -> TIMBER
                    y <= 3 -> STONE
                    y == 4 -> if (post) TIMBER else BOARDS
                    else -> B(M.TIMBER, axis = if (front || back) Axis.X else Axis.Z)
                })
            }
        }
    }

    companion object {
        /** Its lot: nine across and eight deep, its front on the square. */
        val LOT = Rect(0, 0, 8, 7)

        /** Where the town comes to it, and where the hoist's rope hangs: before the porch. */
        val DOOR = Pin(4, 0, -1, Dir.S)

        /** Its house: seven across (x 1..7) and six deep (z 1..6). */
        val HOUSE = Rect(1, 1, 7, 6)

        /** The four stakes that mark it out. */
        private val STAKES = listOf(0 to 1, 8 to 1, 0 to 6, 8 to 6)

        /** The way in (the door's lower half; it opens inward, to the south). */
        val FRONT_DOOR = Pin(4, 0, 1, Dir.S)

        /** The two leaves of the loading doors in the loft (each lower half; they open inward). */
        val LOFT_DOORS = listOf(Pin(3, 5, 1, Dir.S), Pin(4, 5, 1, Dir.S))

        /** The hoist, standing on its bracket over the porch, its rope falling to the ground before the door ([DOOR]). */
        val HOIST_AT = Pin(4, 5, 0, Dir.N)

        /** The clerk's place behind the counter, facing the buyers. */
        val CLERK = Pin(3, 0, 4, Dir.N)

        /** The counter across the front of the clerk's end, and the scale on its bench in the middle of it (the clerk's side its back). */
        val COUNTER_X = 2..4
        val SCALE_AT = Pin(3, 0, 3, Dir.N)

        /** The owner's name board on its bracket under the hoist, and the open and shut board beside the door (the plaque is on its other side). */
        val NAME_BOARD_AT = Pin(4, 3, 0, Dir.N)
        val DOOR_BOARD_AT = Pin(5, 1, 0, Dir.N)

        /** Where the clerk stands to haul the hoist's rope, and to watch the hook: beside the loading ground, out of the swing. */
        val HAULER = Pin(5, 0, -1, Dir.N)

        /** The writing desk at the clerk's elbow (its front to the clerk). */
        val DESK_AT = Pin(4, 0, 4, Dir.W)

        /** The ladder to the loft, and the hatch it comes up through (x, z). */
        val LADDER = Pin(6, 0, 5, Dir.N)
        private val HATCH = 6 to 5

        /** The lanterns on the lamp posts either side of the way in (the street lamps the clerk lights at dusk). */
        val LAMPS = listOf(Pin(2, 3, 0), Pin(6, 3, 0))

        /** The owner's flagpole at the front right: its foot, the staff's height under its top rail, and which way the pennant flies from it (away from the house). */
        val POLE = Pin(8, 0, 0)
        const val POLE_TOP = 8
        val PENNANT_FACING = Dir.E

        /** The windows of the ground floor (x, z). */
        private val WINDOWS = listOf(2 to 1, 6 to 1, 3 to 6, 5 to 6, 1 to 3, 7 to 3, 1 to 4, 7 to 4)

        /** The counter's planks, the dark ground-floor ones. */
        private val COUNTER = B(M.PLANKS, v = 1)
    }
}

/**
 * The Counting House (living-world/trader/design.md, Part 5; build part F2 draws it, part G fills it): a trader's house of business in
 * the capital where they already keep a Store. Two storeys of stone and plaster with the gable to the street, and before it a yard walled
 * with a gate for the wagons and a bell on a post. The same lot as the Store: nine across and eight deep.
 *
 * The yard is the front three rows (z 0..2), paved, walled to the front and the flanks (the double gate at x 4 and x 5, a lamp post
 * either side of it); the bell on its post at the left, the hitching post and the barrels at the right. The house is x 1..7, z 3..7:
 * seven across and five deep, its door in the middle of the front. Inside, the counting room: the high desk with the counting frame on
 * it, the scribe behind (facing the door), the strongbox against the right wall, and on the back wall the map of the roads; the ladder
 * in the back left corner to the owner's room above, with its shelves, table and lamp.
 *
 * As the buyer sees it (the front at the foot, x growing to the buyer's left), the counting room:
 * ```
 *        x 1     x 2     x 3     x 4     x 5     x 6     x 7
 *  z 6   wall    ladder  SCRIBE  .       .       STRONG- wall      (the map on the back wall over x 3..5, y 2..3)
 *  z 5   wall    .       desk+   desk    .       BOX     wall      (the counting frame on the desk)
 *                        FRAME
 *  z 4   wall    .       .       .       .       .       wall
 *  z 3   wall    window  .       DOOR    .       window  wall      (the front wall)
 * ```
 */
class CountingHouse(y0: Int, private val palette: Int, val patron: Dye) : WorksDrawing(WorksKind.COUNTING_HOUSE, y0) {
    override val lot = LOT
    override val low = -1
    override val high = 15

    /** The lot and two blocks round it: the scaffolding may stand just outside. */
    override val bounds = Rect(-2, -1, 10, 9)

    /** Its heart: the scribe's place behind the high desk, facing the door. */
    override val heart = SCRIBE
    override val door = DOOR

    /** The works' post while it is wanted: a step out at the front corners of the lot. */
    override val posts = listOf(Pin(0, 0, -1), Pin(8, 0, -1), Pin(1, 0, -1))

    /** Its crate and heaps down either flank of the house, clear of the yard where the wagons stand. */
    override val yard = listOf(
        Pin(0, 0, 3, Dir.E), Pin(0, 0, 4, Dir.E), Pin(0, 0, 5, Dir.E), Pin(0, 0, 6, Dir.E),
        Pin(8, 0, 3, Dir.W), Pin(8, 0, 4, Dir.W), Pin(8, 0, 5, Dir.W), Pin(8, 0, 6, Dir.W),
    )
    override val plaque = Pin(4, 3, 2, Dir.N)

    override fun drawWorks(p: Pen) {
        for ((x, z) in STAKES) p.stake(x, z)
        p.piece(PieceKind.HOUSE, HOUSE)
        // The lot swept, the way to the gate left clear
        p.stage(Stage.GROUNDWORK)
        for (x in LOT.x0..LOT.x1) for (z in LOT.z0..LOT.z1) if ((x to z) !in STAKES) p.air(x, 0, z)
        for (x in 3..6) p.air(x, 0, -1)
        // The footings under the walls, the planks of the counting room, the yard paved
        p.stage(Stage.FOOTINGS)
        for (x in HOUSE.x0..HOUSE.x1) for (z in HOUSE.z0..HOUSE.z1) {
            val ring = x == HOUSE.x0 || x == HOUSE.x1 || z == HOUSE.z0 || z == HOUSE.z1
            p.over(x, -1, z, if (ring) STONE else B(M.PLANKS))
        }
        for (x in 1..7) for (z in 0..2) p.over(x, -1, z, B(M.PAVE_YARD))
        // The walls course by course (stone to the first floor, then the plastered storey), the upper floor with its hatch for the ladder
        p.stage(Stage.WALLS)
        walls(p)
        for (x in HOUSE.x0 + 1 until HOUSE.x1) for (z in HOUSE.z0 + 1 until HOUSE.z1) if ((x to z) != HATCH) p.set(x, 4, z, B(M.PLANKS))
        // Its roof, the gable to the street, cut off at the back of the lot (the ridge does not reach over the lane behind)
        p.stage(Stage.ROOF)
        Buildings.roof(roofFrame(p), 7, 5, 0, 7, palette, true, BOARDS)
        // The door, the windows upstairs and down, the lantern in the counting room
        p.stage(Stage.OPENINGS)
        p.door(FRONT_DOOR.x, 0, FRONT_DOOR.z, Dir.S)
        for ((x, z) in WINDOWS) {
            p.set(x, 2, z, WINDOW)
            p.set(x, 5, z, WINDOW)
        }
        for ((x, z) in UPPER_WINDOWS) p.set(x, 5, z, WINDOW)
        p.set(4, 3, 5, B(M.LANTERN, top = true))
        // The counting room: the high desk with the counting frame, the scribe's lectern, the strongbox, the map, the ladder; upstairs
        // the owner's room
        p.stage(Stage.FITTING)
        for (x in DESK_X) p.set(x, 0, 5, DESK)
        for (x in 3..4) p.set(x, 0, 4, B(M.CARPET, color = patron))
        for (x in listOf(3, 5)) p.set(x, 3, 2, B(M.BANNER, Dir.N, color = patron))
        p.set(FRAME_AT.x, FRAME_AT.y, FRAME_AT.z, FRAME)
        p.set(LEDGER_AT.x, LEDGER_AT.y, LEDGER_AT.z, B(M.LECTERN, LEDGER_AT.facing))
        p.set(STRONGBOX_AT.x, STRONGBOX_AT.y, STRONGBOX_AT.z, STRONGBOX)
        p.set(MAP_AT.x, MAP_AT.y, MAP_AT.z, MAP)
        for (y in 0..4) p.set(LADDER.x, y, LADDER.z, B(M.LADDER, LADDER.facing))
        for (x in listOf(4, 6)) for (y in 5..6) p.set(x, y, 6, B(M.BOOKSHELF))
        p.set(5, 5, 4, B(M.CRAFTING_TABLE))
        p.set(5, 6, 4, B(M.LANTERN))
        p.set(3, 5, 4, B(M.LECTERN, Dir.S))
        p.set(2, 5, 5, B(M.CHEST, Dir.E))
        // Last the yard: its wall with the double gate, the lamp posts either side, the bell on its post, the hitching post and the barrels
        p.stage(Stage.GROUNDS)
        for (x in 0..8) {
            if (x == GATE_X || x == GATE_X + 1) continue
            p.set(x, 0, 0, STONE)
            p.set(x, 1, 0, B(M.FENCE))
        }
        for (z in 1..2) for (x in listOf(0, 8)) {
            p.set(x, 0, z, STONE)
            p.set(x, 1, z, B(M.FENCE))
        }
        for (x in GATE_X..GATE_X + 1) p.set(x, 0, 0, B(M.FENCE_GATE, Dir.S))
        for (lamp in LAMPS) {
            p.set(lamp.x, 0, lamp.z, B(M.WALL_POST))
            p.set(lamp.x, 1, lamp.z, B(M.FENCE))
            p.set(lamp.x, 2, lamp.z, B(M.FENCE))
            p.set(lamp.x, lamp.y, lamp.z, B(M.LANTERN, v = STREET_LAMP))
        }
        for (y in 0..2) p.set(BELL_POST.x, y, BELL_POST.z, B(M.FENCE))
        p.set(BELL_AT.x, BELL_AT.y, BELL_AT.z, BELL)
        p.set(7, 0, 1, B(M.FENCE))
        p.set(7, 0, 2, B(M.BARREL))
        p.set(1, 0, 2, B(M.BARREL))
        p.set(1, 1, 2, B(M.BARREL))
    }

    /** A frame on the house for the roof: what the roof would put over the lane behind the lot is dropped. */
    private fun roofFrame(p: Pen): Frame {
        val cut = object : PlanCanvas by p.c {
            override fun set(x: Int, y: Int, z: Int, b: B) {
                if (z <= LOT.z1) p.c.set(x, y, z, b)
            }
        }
        return Frame(cut, HOUSE, Dir.N, y0)
    }

    /** The walls: stone to the first floor, plaster over it between timber posts, a beam along the top; the doorway and the windows left open. */
    private fun walls(p: Pen) {
        for (x in HOUSE.x0..HOUSE.x1) for (z in HOUSE.z0..HOUSE.z1) {
            val front = z == HOUSE.z0
            val back = z == HOUSE.z1
            val side = x == HOUSE.x0 || x == HOUSE.x1
            if (!front && !back && !side) continue
            val corner = (front || back) && side
            val post = corner || if (front || back) (x - HOUSE.x0) % 2 == 0 else (z - HOUSE.z0) % 2 == 0
            for (y in 0..7) {
                if (front && x == FRONT_DOOR.x && y <= 1) continue
                if ((y == 2 || y == 5) && (x to z) in WINDOWS) continue
                if (y == 5 && (x to z) in UPPER_WINDOWS) continue
                p.set(x, y, z, when {
                    corner -> TIMBER
                    y <= 3 -> STONE
                    y == 4 || y == 7 -> B(M.TIMBER, axis = if (front || back) Axis.X else Axis.Z)
                    post -> TIMBER
                    else -> B(M.PLASTER)
                })
            }
        }
    }

    companion object {
        /** Its lot: nine across and eight deep, its front on the square. */
        val LOT = Rect(0, 0, 8, 7)

        /** Where the town comes to it: before the yard gate. */
        val DOOR = Pin(4, 0, -1, Dir.S)

        /** Its house: seven across (x 1..7) and five deep (z 3..7), behind the yard. */
        val HOUSE = Rect(1, 3, 7, 7)

        /** The four stakes that mark it out. */
        private val STAKES = listOf(0 to 3, 8 to 3, 0 to 6, 8 to 6)

        /** The way in to the counting room (the door's lower half; it opens inward, to the south). */
        val FRONT_DOOR = Pin(4, 0, 3, Dir.S)

        /** The yard's double gate: the left leaf's x (the right is the next). */
        const val GATE_X = 4

        /** The scribe's place behind the high desk, facing the door. */
        val SCRIBE = Pin(3, 0, 6, Dir.N)

        /** The high desk (x along z 5) with the counting frame on it, and the scribe's ledger at its end. */
        val DESK_X = 3..4
        val FRAME_AT = Pin(3, 1, 5, Dir.N)
        val LEDGER_AT = Pin(4, 1, 5, Dir.N)

        /** The strongbox against the right wall, its front to the scribe. */
        val STRONGBOX_AT = Pin(6, 0, 6, Dir.W)

        /** The map of the roads on the back wall: the middle of the bottom row of a panel three blocks wide and two high, facing the room. */
        val MAP_AT = Pin(4, 2, 6, Dir.N)

        /** The ladder up to the owner's room, and the hatch it comes up through (x, z). */
        val LADDER = Pin(2, 0, 6, Dir.N)
        private val HATCH = 2 to 6

        /** The lanterns on the lamp posts either side of the gate (the street lamps the scribe lights at dusk). */
        val LAMPS = listOf(Pin(3, 3, 0), Pin(6, 3, 0))

        /** The bell on its post in the yard, and where the post stands. */
        val BELL_POST = Pin(1, 0, 1)
        val BELL_AT = Pin(1, 3, 1, Dir.N)

        /** The windows of the ground floor (x, z); the storey over has the same, and [UPPER_WINDOWS] besides. */
        private val WINDOWS = listOf(2 to 3, 6 to 3, 1 to 5, 7 to 5)
        private val UPPER_WINDOWS = listOf(4 to 3, 3 to 7, 5 to 7)

        /** The desk's planks, the dark ground-floor ones. */
        private val DESK = B(M.PLANKS, v = 1)

        /** The counting frame, the strongbox, the map and the bell: plain blocks until the House's own stand there. */
        private val FRAME = B(M.LECTERN, Dir.N)
        private val STRONGBOX = B(M.CHEST, Dir.W)
        private val MAP = B(M.CARTOGRAPHY_TABLE)
        private val BELL = B(M.BELL, Dir.N)
    }
}
