package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.homestead.plan.PieceKind
import io.github.vaspyyy.guildmark.homestead.plan.Stage

/**
 * A cookshop of one's own on the square (living-world/cook/design.md 6.4): a counter of planks across the front with five
 * plates on it and the patron's awning over it; behind it the keeper's alley, and across the back, under a slate roof that
 * rises to the back wall, the stone oven block with the Bake oven's mouth and a chimney through the roof, the Hob, the
 * Larder in the middle and a Prep Board at the end; the patron's banner on the roof, a lantern under its eave, and the Menu
 * board on a barrel at the front right. Five across and four deep, its front facing the buyers (the drawing's north), its
 * keeper standing behind the middle of the counter. It is the stall's seam: boards over the paving, the same awning block
 * (the keeper draws it up and lets it down: WorksAtWork.awning), and the same way in, from the side.
 *
 * The stages are the works': the ground cleared, boards laid (the oven's hearth in stone), the counter and the oven block
 * and the back wall, the roof with the chimney carried up through it, the banner and the chimney's first fire, the windows
 * and the lantern and the oven set into its block, and last the plates, the larder, the hob, the board and the barrel.
 *
 * As the buyer sees it (the front at the foot, x growing to the buyer's left), the stations along z 2:
 * ```
 *        x 2        x 1       x 0       x -1       x -2
 *  z 3   timber     window    window    stone      chimney     (the back wall, y 0..3)
 *  z 2   PREP BOARD HOB       LARDER    BAKE OVEN  stone       (the roof's low eave above, at y 3)
 *  z 1   (way in)   .         keeper    .          barrel, menu board
 *  z 0   counter    counter   counter   counter    counter     (a plate on each)
 * ```
 */
class Cookshop(y0: Int, private val patron: Dye) : WorksDrawing(WorksKind.COOKSHOP, y0) {
    override val lot = LOT
    override val low = -1
    override val high = 8

    /** The middle of its counter: the keeper stands one back from it. */
    override val heart = Pin(0, 0, 0, Dir.N)
    override val door = DOOR

    /** The works' post while it is wanted: a step out either side of the counter's ends. */
    override val posts = listOf(Pin(-3, 0, 0), Pin(3, 0, 0), Pin(-3, 0, -1))

    /** Its crate and heaps down either flank, clear of the way in at the alley's near end (x 2, z 1, from the side). */
    override val yard = listOf(
        Pin(-3, 0, 1, Dir.E), Pin(-3, 0, 2, Dir.E), Pin(-3, 0, 3, Dir.E), Pin(3, 0, 2, Dir.W), Pin(3, 0, 3, Dir.W),
        Pin(3, 0, -1, Dir.W), Pin(-3, 0, 4, Dir.E), Pin(3, 0, 4, Dir.W),
    )
    override val plaque: Pin? = null

    /** The banner and the chimney's cap are not a bough on the ridge. */
    override val unsaid: Set<Stage> get() = setOf(Stage.TOPPING)

    override fun drawWorks(p: Pen) {
        p.piece(PieceKind.PROP, lot)
        p.stage(Stage.GROUNDWORK)
        for (x in -2..2) for (z in 0..3) p.air(x, 0, z)
        // Boards over the paving; the oven's hearth in stone
        p.stage(Stage.FOOTINGS)
        for (x in -2..2) for (z in 0..3) p.over(x, -1, z, if (x <= -1 && z >= 2) STONE else B(M.PLANKS))
        // The counter across the front; the oven block (its hood over the oven's cell, its back, the chimney's foot);
        // the back wall, its windows left open, a timber post at the end
        p.stage(Stage.WALLS)
        for (x in -2..2) p.set(x, 0, 0, COUNTER)
        for (y in 0..2) p.set(-2, y, 2, STONE)
        for (y in 1..2) p.set(-1, y, 2, STONE)
        for (y in 0..3) {
            p.set(-2, y, 3, STONE)
            p.set(-1, y, 3, STONE)
            if (y != 2) for (x in 0..1) p.set(x, y, 3, B(M.PLANKS))
            p.set(2, y, 3, TIMBER)
        }
        // The slate roof, low at the eave over the stations and high at the back wall; the chimney carried up through it;
        // the awning's block over the alley (the keeper draws its cloth up and lets it down)
        p.stage(Stage.ROOF)
        for (x in -2..2) p.set(x, 3, 2, B(M.ROOF_STAIRS, Dir.S))
        for (x in -1..2) p.set(x, 4, 3, B(M.ROOF_STAIRS, Dir.S))
        for (y in 4..6) p.set(-2, y, 3, STONE)
        p.set(0, 3, 1, AWNING)
        // The patron's banner flown from the roof's high end, and the chimney's first fire
        p.stage(Stage.TOPPING)
        p.set(2, 5, 3, B(M.BANNER, color = patron))
        p.set(-2, 7, 3, B(M.CAMPFIRE))
        // The windows in the back wall, a lantern under the eave, and the Bake oven set into its block, its mouth to the alley
        p.stage(Stage.OPENINGS)
        for (x in 0..1) p.set(x, 2, 3, WINDOW)
        p.set(0, 2, 2, B(M.LANTERN, top = true))
        p.set(-1, 0, 2, B(M.BAKE_OVEN, Dir.N))
        // The larder, the hob and the prep board along the stations' row; the barrel at the front right with the menu board on it
        // (its slate to the buyers); the five plates on the counter
        p.stage(Stage.FITTING)
        p.set(0, 0, 2, B(M.LARDER))
        p.set(1, 0, 2, B(M.HOB))
        p.set(2, 0, 2, B(M.PREP_BOARD))
        p.set(-2, 0, 1, B(M.BARREL))
        p.set(-2, 1, 1, B(M.MENU_BOARD, Dir.N))
        for (x in -2..2) p.set(x, 1, 0, B(M.PLATE))
    }

    companion object {
        /** Its pitch: five across and four deep, its front on the square. */
        val LOT = Rect(-2, 0, 2, 3)

        /** Where its buyers stand, before the middle of the counter. */
        val DOOR = Pin(0, 0, -1, Dir.S)

        /** Where its keeper comes in to the alley, from the side (the alley's near end, x 2, z 1): ground that is to be left to walk on. */
        val WAY_IN = Pin(3, 0, 1, Dir.W)

        /** The counter's planks, the dark ground-floor ones. */
        private val COUNTER = B(M.PLANKS, v = 1)
    }
}
