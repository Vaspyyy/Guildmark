package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.plan.Axis
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Buildings
import io.github.vaspyyy.guildmark.city.plan.CityBuilder
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.Frame
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Wares
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.homestead.plan.BuildOrder
import io.github.vaspyyy.guildmark.homestead.plan.Drawing
import io.github.vaspyyy.guildmark.homestead.plan.Packed
import io.github.vaspyyy.guildmark.homestead.plan.PieceKind
import io.github.vaspyyy.guildmark.homestead.plan.PlanCanvas
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Step
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.TierPlan
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.Vec3

/**
 * Where a works stands in the world: its drawing's own frame (x across its front, z from its front to its back, its
 * front facing the frame's north) laid with (0, 0) at [origin] and its front toward [front]. A drawing's heights are
 * the world's.
 */
class WorksFrame(val origin: BlockPos, val front: Direction) {
    /** The turn that takes the drawing (its front to the north) to the world. */
    val rotation: Rotation = when (front) {
        Direction.EAST -> Rotation.CLOCKWISE_90
        Direction.SOUTH -> Rotation.CLOCKWISE_180
        Direction.WEST -> Rotation.COUNTERCLOCKWISE_90
        else -> Rotation.NONE
    }

    private fun dx(x: Int, z: Int): Int = when (rotation) {
        Rotation.NONE -> x
        Rotation.CLOCKWISE_90 -> -z
        Rotation.CLOCKWISE_180 -> -x
        Rotation.COUNTERCLOCKWISE_90 -> z
    }

    private fun dz(x: Int, z: Int): Int = when (rotation) {
        Rotation.NONE -> z
        Rotation.CLOCKWISE_90 -> x
        Rotation.CLOCKWISE_180 -> -z
        Rotation.COUNTERCLOCKWISE_90 -> -x
    }

    fun worldX(x: Int, z: Int): Int = origin.x + dx(x, z)
    fun worldZ(x: Int, z: Int): Int = origin.z + dz(x, z)
    fun world(x: Int, y: Int, z: Int): BlockPos = BlockPos(worldX(x, z), y, worldZ(x, z))

    /** The middle of the drawing's block ([x], [z]) (or any point of it), at height [y]. */
    fun centre(x: Double, y: Double, z: Double): Vec3 {
        val wx = when (rotation) {
            Rotation.NONE -> x
            Rotation.CLOCKWISE_90 -> -z
            Rotation.CLOCKWISE_180 -> -x
            Rotation.COUNTERCLOCKWISE_90 -> z
        }
        val wz = when (rotation) {
            Rotation.NONE -> z
            Rotation.CLOCKWISE_90 -> x
            Rotation.CLOCKWISE_180 -> -z
            Rotation.COUNTERCLOCKWISE_90 -> -x
        }
        return Vec3(origin.x + 0.5 + wx, y, origin.z + 0.5 + wz)
    }

    /** The drawing's x, z of the world column [wx], [wz]. */
    fun local(wx: Int, wz: Int): Pair<Int, Int> {
        val ax = wx - origin.x
        val az = wz - origin.z
        return when (rotation) {
            Rotation.NONE -> ax to az
            Rotation.CLOCKWISE_90 -> az to -ax
            Rotation.CLOCKWISE_180 -> -ax to -az
            Rotation.COUNTERCLOCKWISE_90 -> -az to ax
        }
    }

    /** A direction in the drawing, in the world. */
    fun direction(d: Dir): Direction = rotation.rotate(CityStyle.direction(d))

    /** The world's columns the drawing's [r] covers, from [y0] to [y1]. */
    fun box(r: Rect, y0: Int, y1: Int): BoundingBox {
        val ax = worldX(r.x0, r.z0)
        val az = worldZ(r.x0, r.z0)
        val bx = worldX(r.x1, r.z1)
        val bz = worldZ(r.x1, r.z1)
        return BoundingBox(minOf(ax, bx), minOf(y0, y1), minOf(az, bz), maxOf(ax, bx), maxOf(y0, y1), maxOf(az, bz))
    }
}

/** A place pinned on a works' drawing, x across its front and z back from it, at its height y over the works' ground ([WorksDrawing.y0]). */
class Pin(val x: Int, val y: Int, val z: Int, val facing: Dir = Dir.N)

/**
 * A works' drawing (living-world/design-influence.md section 3's stage table): what it builds, stage by stage, in
 * its own frame ([WorksFrame]), standing on ground at [y0] (a world height). Drawn once, for the one tier there is
 * (1), and turned into steps by BuildOrder, so a works comes out the same every time its record is read back.
 *
 * Besides its blocks a drawing knows: its [lot] (what it stands on, from [low] to [high] over its ground), the
 * places in it where its block goes in over the town's own ([replaces]: a bakery's floor that its flue goes up
 * through; the paving a stall's boards go down on), the town's things it moves out of its way ([moves], carried with
 * whatever is in them), and where its post, yard, plaque and front door stand.
 */
abstract class WorksDrawing(val kind: WorksKind, val y0: Int) : Drawing {
    /** What it stands on. */
    abstract val lot: Rect

    /** How far below and above its ground its blocks reach. */
    abstract val low: Int
    abstract val high: Int

    override val bounds: Rect get() = lot

    /** Its ground stands at [y0] all over: a works is sited on level ground, or levels it first (the tower). */
    override fun groundHeight(x: Int, z: Int): Int = y0

    /** Its heart: where WorksSite.origin is (the second oven's mouth, a stall's display, the tower's door, a granary's corner). */
    abstract val heart: Pin

    /** Where the town comes to it: the bakery's door, the granary's ramp, the tower's door, before a stall's counter. */
    abstract val door: Pin

    /** Where its post may stand while it's wanted, the first that's free. */
    abstract val posts: List<Pin>

    /** Its crate, then its heaps of each material and its mortar tub, in that order, where there's room. */
    abstract val yard: List<Pin>

    /** Its patron's plaque, on its wall facing out (its block is in front of the wall); none for a stall. */
    abstract val plaque: Pin?

    /** Where its master builder keeps his post while it goes up, facing it: beside its door, a step out. */
    open val master: Pin get() = Pin(door.x + 2, door.y, door.z - 1, Dir.S)

    /**
     * The stages whose homestead line ("Walls are up!", "Roof's on!", "Bough on the ridge!", "Doors and windows in!")
     * doesn't fit what it puts up then: they're cheered without words (TownWorksSite.stageLine).
     */
    open val unsaid: Set<Stage> get() = emptySet()

    /** Packed places (the drawing's x, the world's y, z) where its block goes in over the town's own, kept to put back if it's ever taken down. */
    val replaces = HashSet<Long>()

    /** The town's things it moves out of its way: each place it sets one down, to the place it carries it from (both packed). The bakery's barrels and chest, with what's in them. */
    val moves = HashMap<Long, Long>()

    /** The places a stake stands at (fence and banner) and is pulled up from at the end. */
    val stakes = ArrayList<Pair<Int, Int>>()

    /** Packs [x], [y] (over the ground), [z]. */
    fun at(x: Int, y: Int, z: Int): Long = Packed.of(x, y0 + y, z)

    override fun draw(tier: Int, c: PlanCanvas) {
        if (tier != 1) return
        replaces.clear()
        moves.clear()
        stakes.clear()
        drawWorks(Pen(c))
    }

    protected abstract fun drawWorks(p: Pen)

    /** What a drawing draws with: a works' own frame over the canvas, its heights over the ground. */
    inner class Pen(val c: PlanCanvas) {
        fun stage(stage: Stage) = c.stage(stage)
        fun piece(kind: PieceKind, lot: Rect, y: Int = 0) = c.piece(kind, lot, y0 + y)
        fun set(x: Int, y: Int, z: Int, b: B) = c.set(x, y0 + y, z, b)
        fun air(x: Int, y: Int, z: Int) = c.set(x, y0 + y, z, B.AIR)

        /** [b] set over the town's own block. */
        fun over(x: Int, y: Int, z: Int, b: B) {
            replaces.add(at(x, y, z))
            set(x, y, z, b)
        }

        fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, b: B) {
            for (x in minOf(x0, x1)..maxOf(x0, x1)) for (z in minOf(z0, z1)..maxOf(z0, z1)) for (y in minOf(y0, y1)..maxOf(y0, y1)) set(x, y, z, b)
        }

        fun door(x: Int, y: Int, z: Int, inward: Dir, hingeRight: Boolean = false) {
            set(x, y, z, B(M.DOOR, inward, part = 0, v = if (hingeRight) 1 else 0))
            set(x, y + 1, z, B(M.DOOR, inward, part = 1, v = if (hingeRight) 1 else 0))
        }

        fun wares(x: Int, y: Int, z: Int, wares: Wares, facing: Dir) = c.wares(x, y0 + y, z, wares, facing)

        /** A stake at [x], [z]: a post with the town's banner on it, knocked in first and pulled up last. */
        fun stake(x: Int, z: Int) {
            stakes.add(x to z)
            c.piece(PieceKind.STAKE, Rect(x, z, x, z), y0)
            set(x, 0, z, B(M.FENCE))
            set(x, 1, z, B(M.BANNER, color = CityBuilder.BANNER_COLOR))
        }

        /** A frame over the canvas for one building on [r] of the drawing (Buildings.roof draws in one). */
        fun frame(r: Rect): Frame = Frame(c, r, Dir.N, y0)
    }

    companion object {
        /** A marker block's [B.v]: a block of the works' own (see TownWorksSite.state): the hoist, the brazier, a stall's counter, its awning. */
        const val MARK = 900

        val HOIST = B(M.HOOK, Dir.N, v = MARK)
        val BRAZIER = B(M.CAULDRON, v = MARK)
        val PITCH = B(M.CRATE, Dir.N, v = MARK)
        val AWNING = B(M.WOOL, Dir.N, v = MARK)

        val STONE = B(M.STONE)
        val TIMBER = B(M.TIMBER)
        val BOARDS = B(M.PLANKS, v = 1)
        val WINDOW = B(M.WINDOW)
        val TABLE = B(M.PLANK_SLAB, top = true)
    }
}

/**
 * The second oven in its bay (section 3): the bakery's other back corner, the mirror of its own oven. The bay's hay,
 * barrels, compost bin and chest are carried out to the front by the left window (a capital drawn since there were
 * works, Layout.VERSION 2, has them there already, and the flue's footing laid); the hearth stones go down, the oven
 * goes up course by course, its flue up through both floors and the roof with its fire on top (the first fire), and
 * last the smoker's mouth, the peel rack and the kneading table lengthened to serve both ovens. In the bakery's own
 * frame (GreatBuildings.bakery: twelve by twelve, its front to the market).
 */
class OvenBay(y0: Int) : WorksDrawing(WorksKind.OVEN, y0) {
    override val lot = Rect(1, 7, 4, 10)
    override val low = -1
    override val high = 14

    /** The whole bakery: what the bay carries out stands at its front. */
    override val bounds = Rect(0, 0, 11, 11)

    override val heart = Pin(2, 0, 7, Dir.N)
    override val door = Pin(5, 0, -1, Dir.S)
    override val posts = listOf(Pin(3, 0, -1), Pin(4, 0, -1), Pin(2, 0, -1), Pin(1, 0, -1))
    override val yard = listOf(Pin(0, 0, -1), Pin(1, 0, -1), Pin(2, 0, -1), Pin(0, 0, -2), Pin(1, 0, -2), Pin(2, 0, -2), Pin(3, 0, -2))
    override val plaque = Pin(3, 1, -1, Dir.N)

    /** Inside, between the two ovens at the back, facing the bay (the baker's own place is a step nearer the front). */
    override val master = Pin(5, 0, 9, Dir.W)

    /** Its flue up through the bakery's roof, and the first fire on top. */
    override val unsaid: Set<Stage> get() = setOf(Stage.ROOF, Stage.TOPPING)

    override fun drawWorks(p: Pen) {
        p.piece(PieceKind.PROP, lot)
        // The corner cleared: its hay, barrels, bin and chest carried out to the front, and the lantern taken down
        p.stage(Stage.GROUNDWORK)
        p.set(1, 0, 1, B(M.HAY))
        p.set(1, 0, 2, B(M.HAY))
        p.set(1, 1, 1, B(M.BARREL, Dir.E))
        p.set(1, 1, 2, B(M.BARREL, Dir.E))
        p.set(1, 0, 3, B(M.COMPOSTER))
        p.set(2, 0, 1, B(M.CHEST, Dir.S))
        p.set(2, 0, 2, B(M.BARREL, Dir.E))
        moves[at(1, 1, 1)] = at(1, 1, 7)
        moves[at(1, 1, 2)] = at(1, 1, 8)
        moves[at(2, 0, 2)] = at(1, 1, 9)
        moves[at(2, 0, 1)] = at(1, 0, 10)
        for (z in 7..9) {
            p.over(1, 1, z, B.AIR)
            p.over(1, 0, z, B.AIR)
        }
        p.over(1, 0, 10, B.AIR)
        p.over(2, 0, 10, B.AIR)
        p.over(4, 2, 7, B.AIR)
        // The hearth stones, and the flue's footing through the floor
        p.stage(Stage.FOOTINGS)
        p.over(2, -1, 10, STONE)
        for (x in 1..4) for (z in 7..10) if (z != 7 || (x != 2 && x != 3)) p.set(x, 0, z, STONE)
        // The oven, course by course
        p.stage(Stage.WALLS)
        for (y in 1..2) for (x in 1..4) for (z in 7..10) p.set(x, y, z, STONE)
        // Its flue, up through the upper floor, the loft floor and the roof (clear of the bed upstairs): whatever of
        // the bakery's stands in its column is the town's, kept to put back
        p.stage(Stage.ROOF)
        for (y in 3..13) p.over(2, y, 10, STONE)
        // The first fire, on the flue's top
        p.stage(Stage.TOPPING)
        p.set(2, 14, 10, B(M.CAMPFIRE))
        // The mouths, the peel rack in the corner, the lantern hung again, the kneading table lengthened
        p.stage(Stage.FITTING)
        p.set(3, 0, 7, B(M.SMOKER, Dir.N))
        p.set(2, 0, 7, B(M.FURNACE, Dir.N))
        p.set(1, 1, 6, B(M.BARS))
        p.set(1, 2, 6, B(M.BARS))
        p.set(5, 2, 7, B(M.LANTERN, top = true))
        p.set(5, 0, 6, TABLE)
    }
}

/**
 * The second oven as a lean-to against the bakery's side, where its bay isn't as drawn: an open-fronted bake-house
 * five along the wall and four out, a three-block oven at its back against the bakery, its flue up beside the
 * bakery's eaves, a roof of one pitch falling to the front. Its own frame faces out from the bakery's side wall,
 * which stands just behind it (z 4).
 */
class OvenLeanTo(y0: Int) : WorksDrawing(WorksKind.OVEN, y0) {
    override val lot = Rect(-1, -1, 5, 3)
    override val low = -1
    override val high = 10
    override val heart = Pin(2, 0, 1, Dir.N)
    override val door = Pin(2, 0, -1, Dir.S)
    override val posts = listOf(Pin(-2, 0, -2), Pin(6, 0, -2), Pin(2, 0, -3))
    override val yard = listOf(Pin(6, 0, 0), Pin(6, 0, 1), Pin(6, 0, 2), Pin(7, 0, 0), Pin(7, 0, 1), Pin(-2, 0, 0), Pin(-2, 0, 1), Pin(-2, 0, 2))
    override val plaque = Pin(0, 1, -1, Dir.N)

    /** The first fire on its flue, and its lantern. */
    override val unsaid: Set<Stage> get() = setOf(Stage.TOPPING, Stage.OPENINGS)

    override fun drawWorks(p: Pen) {
        p.piece(PieceKind.PROP, lot)
        p.stage(Stage.GROUNDWORK)
        for (x in 0..4) for (z in 0..3) p.air(x, 0, z)
        // The hearth
        p.stage(Stage.FOOTINGS)
        for (x in 1..3) for (z in 1..3) if (z != 1 || x == 1) p.set(x, 0, z, STONE)
        // The oven, the corner posts and the side walls up under the roof
        p.stage(Stage.WALLS)
        for (y in 1..2) for (x in 1..3) for (z in 1..3) p.set(x, y, z, STONE)
        for (x in listOf(0, 4)) {
            for (y in 0..1) p.set(x, y, 0, TIMBER)
            for (z in 1..3) {
                val top = if (z == 3) 3 else 2
                for (y in 0..top) p.set(x, y, z, if (z == 3) TIMBER else BOARDS)
            }
        }
        // One pitch, high against the bakery, low at the front; the flue up through it
        p.stage(Stage.ROOF)
        for (x in -1..5) {
            for (z in -1..0) p.set(x, 2, z, B(M.ROOF_STAIRS, Dir.S))
            for (z in 1..2) if (x != 2 || z != 2) p.set(x, 3, z, B(M.ROOF_STAIRS, Dir.S))
            p.set(x, 4, 3, B(M.ROOF_STAIRS, Dir.S))
        }
        for (y in 3..9) p.set(2, y, 2, STONE)
        p.stage(Stage.TOPPING)
        p.set(2, 10, 2, B(M.CAMPFIRE))
        p.stage(Stage.OPENINGS)
        p.set(3, 1, 0, B(M.LANTERN, top = true))
        p.stage(Stage.FITTING)
        p.set(2, 0, 1, B(M.FURNACE, Dir.N))
        p.set(3, 0, 1, B(M.SMOKER, Dir.N))
    }
}

/**
 * The town granary (section 3): a timber granary raised on staddle stones, eleven across and twelve deep on its lot
 * (the generator's south of the bakery, or one found for it), its front half the granary and its back half left for
 * the great granary. Staked out, its ground swept, the staddles and the floor laid, the frame raised course by course
 * with a loft over the bins, scaffolded, roofed with its gable to the street, the bough and the hoist beam set on it,
 * the double doors and the loading door hung, the bins and sacks set out, and last the ramp and the cart stand.
 */
class Granary(y0: Int, private val palette: Int) : WorksDrawing(WorksKind.GRANARY, y0) {
    override val lot = LOT
    override val low = -1
    override val high = 14

    /** The lot and a block round it: the scaffolding may stand just outside. */
    override val bounds = Rect(-1, -1, 11, 12)

    override val heart = Pin(0, 0, 0, Dir.N)
    override val door = DOOR
    override val posts = listOf(Pin(10, 0, 0), Pin(9, 0, 0), Pin(10, 0, -1))
    override val yard = listOf(Pin(1, 0, 0, Dir.N), Pin(0, 0, 2, Dir.E), Pin(0, 0, 3, Dir.E), Pin(0, 0, 4, Dir.E), Pin(0, 0, 5, Dir.E), Pin(0, 0, 6, Dir.E), Pin(10, 0, 3, Dir.W))
    override val plaque = Pin(3, 2, 1, Dir.N)

    override fun drawWorks(p: Pen) {
        val house = Rect(1, 2, 9, 6)
        for ((x, z) in listOf(0 to 1, 10 to 1, 0 to 7, 10 to 7)) p.stake(x, z)
        p.piece(PieceKind.HOUSE, house, 2)
        p.stage(Stage.GROUNDWORK)
        for (x in 1..9) for (z in 2..6) p.air(x, 0, z)
        for (x in 4..5) for (z in 0..1) p.air(x, 0, z)
        // The staddle stones, and the floor over them
        p.stage(Stage.FOOTINGS)
        for (x in 1..9 step 2) for (z in 2..6 step 2) p.set(x, 0, z, B(M.WALL_POST))
        p.fill(1, 1, 2, 9, 1, 6, B(M.PLANKS))
        // The frame, course by course, and the loft over the bins
        p.stage(Stage.WALLS)
        frameWalls(p, house, setOf(4 to 2, 5 to 2), setOf(1 to 3, 1 to 5, 9 to 3, 9 to 5, 3 to 6, 7 to 6), skipFront = false)
        p.fill(2, 6, 3, 8, 6, 5, B(M.PLANKS))
        // Its roof, the gable to the street
        p.stage(Stage.ROOF)
        Buildings.roof(p.frame(house), 9, 5, 0, 6, palette, true, BOARDS)
        // The hoist beam out over the loading door, with the bough
        p.stage(Stage.TOPPING)
        p.set(5, 9, 1, HOIST)
        // The double doors, the loading door, the windows, a lantern under each front eave
        p.stage(Stage.OPENINGS)
        p.door(4, 2, 2, Dir.S)
        p.door(5, 2, 2, Dir.S, hingeRight = true)
        p.door(5, 7, 2, Dir.S)
        for ((x, z) in listOf(1 to 3, 1 to 5, 9 to 3, 9 to 5, 3 to 6, 7 to 6)) p.set(x, 3, z, WINDOW)
        p.set(1, 6, 1, B(M.LANTERN, top = true))
        p.set(9, 6, 1, B(M.LANTERN, top = true))
        // The bins along the back wall, sacks by the doors
        p.stage(Stage.FITTING)
        for (x in listOf(2, 3, 7, 8)) p.wares(x, 2, 5, Wares.RESERVE_GRAIN, Dir.N)
        p.set(2, 2, 3, B(M.HAY))
        p.set(8, 2, 3, B(M.HAY))
        // The ramp up to the doors, and the stand for the carters' cart beside it
        p.stage(Stage.GROUNDS)
        for (x in 4..5) {
            p.set(x, 0, 0, B(M.STONE_STAIRS, Dir.S))
            p.set(x, 0, 1, STONE)
            p.set(x, 1, 1, B(M.STONE_STAIRS, Dir.S))
        }
        for (x in 6..8) for (z in 0..1) p.set(x, 0, z, B(M.PLANK_SLAB))
    }

    companion object {
        /** Its lot (the great granary's back half with it), and before its doors: what the siting looks for. */
        val LOT = Rect(0, 0, 10, 11)
        val DOOR = Pin(4, 0, -1, Dir.S)

        /**
         * A timber frame's walls round [house] from 2 to 6 over the ground: posts at the corners and every other block
         * along, a beam along the top, boards between; [openings] left for the doors (two high) and [windows] (one,
         * at 3), and the front wall left out with [skipFront] (another works' wall stands there).
         */
        fun frameWalls(p: WorksDrawing.Pen, house: Rect, openings: Set<Pair<Int, Int>>, windows: Set<Pair<Int, Int>>, skipFront: Boolean) {
            for (x in house.x0..house.x1) for (z in house.z0..house.z1) {
                val front = z == house.z0
                val back = z == house.z1
                val side = x == house.x0 || x == house.x1
                if (!front && !back && !side) continue
                if (front && skipFront) continue
                for (y in 2..6) {
                    if ((x to z) in openings && y <= 3) continue
                    if ((x to z) in windows && y == 3) continue
                    val corner = (front || back) && side
                    val post = if (front || back) (x - house.x0) % 2 == 0 && !side || corner else (z - house.z0) % 2 == 0
                    p.set(x, y, z, when {
                        corner || post -> TIMBER
                        y == 6 -> B(M.TIMBER, axis = if (front || back) Axis.X else Axis.Z)
                        else -> BOARDS
                    })
                }
            }
        }
    }
}

/**
 * The great granary (section 3): the granary's back half built on as a second bay under one long roof, with a stone
 * cellar under it for the roots. The cellar is dug in its groundwork and lined in its footings; the rest goes up as
 * the granary did, through a door in the wall the two bays share. In the granary's own frame, on its lot.
 */
class GreatGranary(y0: Int, private val palette: Int) : WorksDrawing(WorksKind.GREAT_GRANARY, y0) {
    override val lot = Rect(0, 6, 10, 11)
    override val low = -4
    override val high = 14

    /** Its own half and a block round it, but not the granary's bay: no scaffolding stands in there. */
    override val bounds = Rect(-1, 7, 11, 12)

    override val heart = Pin(0, 0, 6, Dir.N)
    override val door = Pin(4, 0, -1, Dir.S)
    override val posts = listOf(Pin(10, 0, 0), Pin(9, 0, 0), Pin(10, 0, -1))
    override val yard = listOf(Pin(1, 0, 0, Dir.N), Pin(0, 0, 2, Dir.E), Pin(0, 0, 3, Dir.E), Pin(0, 0, 4, Dir.E), Pin(0, 0, 5, Dir.E), Pin(0, 0, 6, Dir.E), Pin(10, 0, 3, Dir.W))
    override val plaque = Pin(6, 2, 1, Dir.N)

    override fun drawWorks(p: Pen) {
        val house = Rect(1, 6, 9, 10)
        for ((x, z) in listOf(0 to 7, 10 to 7, 0 to 11, 10 to 11)) p.stake(x, z)
        p.piece(PieceKind.HOUSE, house, 2)
        // The cellar dug
        p.stage(Stage.GROUNDWORK)
        for (y in -3..-1) for (x in 2..8) for (z in 7..9) p.air(x, y, z)
        // And lined, its walls up to a stone course the bay stands on (where the granary's staddles stood at the back)
        p.stage(Stage.FOOTINGS)
        for (x in 2..8) for (z in 7..9) p.set(x, -4, z, STONE)
        for (y in -3..0) for (x in 1..9) for (z in 6..10) {
            if (x != 1 && x != 9 && z != 6 && z != 10) continue
            if (y == 0 && z == 6 && x % 2 == 1) p.over(x, y, z, STONE) else p.set(x, y, z, STONE)
        }
        p.fill(1, 1, 7, 9, 1, 10, B(M.PLANKS))
        p.stage(Stage.WALLS)
        Granary.frameWalls(p, house, setOf(), setOf(1 to 8, 9 to 8, 3 to 10, 7 to 10), skipFront = true)
        p.fill(2, 6, 7, 8, 6, 9, B(M.PLANKS))
        // The roof carried on back from the granary's (whose own covers the first rows)
        p.stage(Stage.ROOF)
        Buildings.roof(p.frame(house), 9, 5, 0, 6, palette, true, BOARDS)
        p.stage(Stage.OPENINGS)
        p.door(5, 2, 6, Dir.S)
        replaces.add(at(5, 2, 6))
        replaces.add(at(5, 3, 6))
        for ((x, z) in listOf(1 to 8, 9 to 8, 3 to 10, 7 to 10)) p.set(x, 3, z, WINDOW)
        p.set(8, 1, 9, B(M.TRAPDOOR, Dir.N, top = true))
        p.stage(Stage.FITTING)
        for (y in -3..0) p.set(8, y, 9, B(M.LADDER, Dir.N))
        for (z in 7..9) p.wares(2, -3, z, Wares.RESERVE_ROOTS, Dir.E)
        for (x in listOf(2, 3, 7, 8)) p.wares(x, 2, 9, Wares.RESERVE_GRAIN, Dir.N)
        p.set(5, 0, 8, B(M.LANTERN, top = true))
        p.set(3, 0, 8, B(M.LANTERN, top = true))
    }

    /** The granary's roof and gable already stand over the first rows: what's drawn there isn't drawn again. */
    override fun draw(tier: Int, c: PlanCanvas) {
        super.draw(tier, object : PlanCanvas by c {
            override fun set(x: Int, y: Int, z: Int, b: B) {
                if (z <= 7 && y >= y0 + 6) return
                c.set(x, y, z, b)
            }
        })
    }
}

/**
 * A watchtower by a road (section 3): five by five, a stone plinth and walls up to the first floor, a timber storey
 * over it with arrow slits, a deck behind a parapet under its own roof, the beacon's brazier on the deck and a
 * ladder up the back wall (as the lookout's watchmen need it: on at the foot, off onto the deck at the top). Staked,
 * its ground levelled round it, walled, scaffolded, roofed, the bough and the beacon (lit the first time), the door
 * and the slits, the ladder, and a path out to the road (the [path]'s heights, worked out when it was sited).
 */
class Tower(y0: Int, private val palette: Int, private val path: IntArray) : WorksDrawing(WorksKind.TOWER, y0) {
    override val lot = Rect(-2, 0, 2, 4)
    override val low = -1
    override val high = 15
    override val bounds = Rect(-4, -2, 4, 6).let { if (path.isEmpty()) it else Rect(it.x0, minOf(it.z0, -path.size), it.x1, it.z1) }
    override val heart = Pin(0, 0, 0, Dir.N)
    override val door = Pin(0, 0, -1, Dir.S)
    override val posts = listOf(Pin(2, 0, -1), Pin(-2, 0, -1), Pin(3, 0, 0))
    override val yard = listOf(Pin(-1, 0, -1, Dir.N), Pin(-3, 0, 1, Dir.E), Pin(-3, 0, 2, Dir.E), Pin(-3, 0, 3, Dir.E), Pin(3, 0, 1, Dir.W), Pin(3, 0, 2, Dir.W), Pin(3, 0, 3, Dir.W))
    override val plaque = Pin(1, 1, -1, Dir.N)

    override fun drawWorks(p: Pen) {
        val house = Rect(-2, 0, 2, 4)
        for ((x, z) in listOf(-3 to -1, 3 to -1, -3 to 5, 3 to 5)) p.stake(x, z)
        p.piece(PieceKind.HOUSE, house)
        // The ground levelled under it and round it
        for (x in -3..3) for (z in -1..5) p.c.ground(x, z, y0, B(M.GRASS), B(M.SOIL))
        p.stage(Stage.FOOTINGS)
        p.fill(-2, -1, 0, 2, -1, 4, STONE)
        p.stage(Stage.WALLS)
        val ring = { x: Int, z: Int -> x == -2 || x == 2 || z == 0 || z == 4 }
        val corner = { x: Int, z: Int -> (x == -2 || x == 2) && (z == 0 || z == 4) }
        // Clear of the ladder's wall (0, 4): a ladder is held by the block behind it, all the way up
        val slits = setOf(0 to 0, -2 to 2, 2 to 2, -1 to 4)
        for (y in 0..8) for (x in -2..2) for (z in 0..4) {
            if (!ring(x, z)) continue
            if (x == 0 && z == 0 && y <= 1) continue
            if ((x to z) in slits && y == 6) continue
            p.set(x, y, z, when {
                y <= 3 -> STONE
                corner(x, z) -> TIMBER
                y == 8 -> B(M.TIMBER, axis = if (z == 0 || z == 4) Axis.X else Axis.Z)
                else -> BOARDS
            })
        }
        // The deck, its ladder hole, the parapet and the posts holding up its roof
        for (x in -2..2) for (z in 0..4) if (x != 0 || z != 3) p.set(x, 9, z, B(M.PLANKS))
        for (x in -2..2) for (z in 0..4) {
            if (!ring(x, z)) continue
            if (corner(x, z)) for (y in 10..12) p.set(x, y, z, TIMBER) else p.set(x, 10, z, B(M.FENCE))
        }
        p.stage(Stage.ROOF)
        for (x in -3..3) for (z in -1..5) {
            val edge = x == -3 || x == 3 || z == -1 || z == 5
            if (!edge) {
                p.set(x, 13, z, B(M.ROOF, v = palette))
                continue
            }
            val toward = when {
                z == -1 -> Dir.S
                z == 5 -> Dir.N
                x == -3 -> Dir.E
                else -> Dir.W
            }
            p.set(x, 13, z, B(M.ROOF_STAIRS, toward, v = palette))
        }
        p.set(0, 14, 2, B(M.ROOF_SLAB, v = palette))
        for (x in -1..1) for (z in 1..3) if (x != 0 || z != 2) p.set(x, 14, z, B(M.ROOF_SLAB, v = palette))
        // The beacon, lit the first time with the bough
        p.stage(Stage.TOPPING)
        p.set(0, 10, 1, BRAZIER)
        p.stage(Stage.OPENINGS)
        p.door(0, 0, 0, Dir.S)
        for ((x, z) in slits) p.set(x, 6, z, WINDOW)
        p.set(-1, 8, 1, B(M.LANTERN, top = true))
        p.set(1, 8, 3, B(M.LANTERN, top = true))
        p.stage(Stage.FITTING)
        for (y in 0..9) p.set(0, y, 3, B(M.LADDER, Dir.N))
        // The path out to the road, each step at the land's own height
        p.stage(Stage.GROUNDS)
        for ((i, h) in path.withIndex()) {
            val z = -1 - i
            p.set(0, h - 1 - y0, z, B(M.TRACK))
            p.set(0, h - y0, z, B.AIR)
            p.set(0, h + 1 - y0, z, B.AIR)
        }
    }
}

/**
 * A stall of one's own on the square (section 3): boards over the paving, a counter either side of its display with
 * posts on them, posts at the back, its patron's awning over it all, a lantern on a back post, and the patron's banner
 * flown from the other. Its front faces the buyers; its keeper stands behind the display.
 */
class Stall(y0: Int, private val patron: Dye) : WorksDrawing(WorksKind.STALL, y0) {
    override val lot = LOT
    override val low = -1
    override val high = 4
    override val heart = Pin(0, 0, 0, Dir.N)
    override val door = DOOR
    override val posts = listOf(Pin(-2, 0, 0), Pin(2, 0, 0), Pin(-2, 0, 2))
    override val yard = listOf(Pin(2, 0, 1, Dir.W), Pin(2, 0, 2, Dir.W), Pin(-2, 0, 1, Dir.E), Pin(-2, 0, 2, Dir.E), Pin(2, 0, 3, Dir.W))
    override val plaque: Pin? = null

    /** Its counter and posts, the patron's banner, its lantern. */
    override val unsaid: Set<Stage> get() = setOf(Stage.WALLS, Stage.TOPPING, Stage.OPENINGS)

    override fun drawWorks(p: Pen) {
        p.piece(PieceKind.PROP, lot)
        p.stage(Stage.GROUNDWORK)
        for (x in -1..1) for (z in 0..2) p.air(x, 0, z)
        p.stage(Stage.FOOTINGS)
        for (x in -1..1) for (z in 0..2) p.over(x, -1, z, B(M.PLANKS))
        p.stage(Stage.WALLS)
        for (x in listOf(-1, 1)) {
            p.set(x, 0, 0, PITCH)
            for (y in 1..2) p.set(x, y, 0, B(M.FENCE))
            for (y in 0..2) p.set(x, y, 2, B(M.FENCE))
        }
        p.stage(Stage.ROOF)
        p.set(0, 3, 1, AWNING)
        p.stage(Stage.TOPPING)
        p.set(-1, 3, 2, B(M.BANNER, color = patron))
        p.stage(Stage.OPENINGS)
        p.set(1, 3, 2, B(M.LANTERN))
        p.stage(Stage.FITTING)
        p.wares(0, 0, 0, Wares.APPLES, Dir.N)
    }

    companion object {
        /** Its pitch, and where its buyers stand. */
        val LOT = Rect(-1, 0, 1, 2)
        val DOOR = Pin(0, 0, -1, Dir.S)
    }
}

/** The works' drawings and their builds, worked out once each and kept while the server runs. */
object WorksPlans {
    /** A works' drawing and the build it makes, its stakes pulled up last, laid in [frame]. */
    class Planned(val drawing: WorksDrawing, val plan: TierPlan, val frame: WorksFrame)

    /** Each works' build as last worked out, by id, with what it depends on. */
    private val planned = HashMap<Int, Pair<String, Planned>>()

    /** Drawings and builds by their shape alone (wherever they're laid): the siting tries one shape in many places. */
    private val shapes = object : LinkedHashMap<String, Pair<WorksDrawing, TierPlan>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<WorksDrawing, TierPlan>>?): Boolean = size > 24
    }

    /** [rec]'s drawing as its record has it. */
    fun drawing(rec: BuildRec): WorksDrawing = when (rec.kind) {
        WorksKind.OVEN -> if (rec.variant == 0) OvenBay(rec.y0) else OvenLeanTo(rec.y0)
        WorksKind.GRANARY -> Granary(rec.y0, rec.palette)
        WorksKind.GREAT_GRANARY -> GreatGranary(rec.y0, rec.palette)
        WorksKind.TOWER -> Tower(rec.y0, rec.palette, rec.path)
        WorksKind.STALL -> Stall(rec.y0, Dye.entries[rec.color.coerceIn(0, Dye.entries.size - 1)])
        WorksKind.COOKSHOP -> Cookshop(rec.y0, Dye.entries[rec.color.coerceIn(0, Dye.entries.size - 1)])
        WorksKind.STORE -> Store(rec.y0, rec.palette, Dye.entries[rec.color.coerceIn(0, Dye.entries.size - 1)])
        WorksKind.COUNTING_HOUSE -> CountingHouse(rec.y0, rec.palette, Dye.entries[rec.color.coerceIn(0, Dye.entries.size - 1)])
    }

    /** [rec]'s drawing and build: the same every time it's asked while its record says the same. */
    fun of(rec: BuildRec): Planned {
        val key = rec.signature()
        planned[rec.id]?.let { (k, p) -> if (k == key) return p }
        val (drawing, plan) = shape(rec)
        val p = Planned(drawing, plan, rec.frame)
        planned[rec.id] = key to p
        return p
    }

    /** [rec]'s drawing and build laid where its record says now, without keeping it as its build (the siting's dry run). */
    fun trial(rec: BuildRec): Planned {
        val (drawing, plan) = shape(rec)
        return Planned(drawing, plan, rec.frame)
    }

    private fun shape(rec: BuildRec): Pair<WorksDrawing, TierPlan> {
        val key = rec.shape()
        shapes[key]?.let { return it }
        val drawing = drawing(rec)
        val plan = BuildOrder.plan(drawing, 1)
        val pulls = drawing.stakes.flatMap { (x, z) ->
            listOf(
                Step(StepKind.PULL_STAKE, Stage.GROUNDS, x, drawing.y0 + 1, z),
                Step(StepKind.PULL_STAKE, Stage.GROUNDS, x, drawing.y0, z),
            )
        }
        val out = drawing to TierPlan(1, plan.steps + pulls, plan.pieces)
        shapes[key] = out
        return out
    }

    /** Forgets works [id]'s build (it's gone, or sited anew). */
    fun forget(id: Int) {
        planned.remove(id)
    }

    fun reset() {
        planned.clear()
        shapes.clear()
    }
}
