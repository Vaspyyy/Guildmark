package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * Draws the small things of the country onto [canvas]: lamps, wells, carts, stacks, shrines, trees. Each is drawn in
 * a frame of its own turned to its facing (ahead is north, so -v, and to the right is +u), from a random generator
 * seeded by the city's [seed] and the spot, so that every chunk that sees it draws it the same. [streetLamps]: the
 * lamp posts' lanterns are street lamps (see [STREET_LAMP]), as a capital's country has them.
 */
class FarmProps(private val canvas: Canvas, private val seed: Long, private val streetLamps: Boolean = false) {
    fun draw(p: FarmProp) {
        val f = Frame(canvas, Rect(p.x, p.z, p.x, p.z), p.facing, p.y)
        val r = Random(mix(p))
        when (p.kind) {
            FarmPropKind.LAMP, FarmPropKind.WELL, FarmPropKind.SCARECROW, FarmPropKind.WAYSTONE, FarmPropKind.SHRINE ->
                FarmWayside(f, r, streetLamps).draw(p)
            FarmPropKind.CART, FarmPropKind.HAYSTACK, FarmPropKind.TROUGH, FarmPropKind.WOODPILE, FarmPropKind.COMPOST ->
                FarmYard(f, r).draw(p)
            FarmPropKind.TREE, FarmPropKind.FRUIT_TREE -> FarmTrees(f, r).draw(p)
            FarmPropKind.WHEEL -> FarmWheel(f).draw(p)
        }
    }

    /** A number that looks random but is fixed for this spot in this city. */
    private fun mix(p: FarmProp): Long {
        var h = seed xor (p.x * 0x9E3779B97F4A7C15uL.toLong()) xor (p.z * 0xC2B2AE3D27D4EB4FuL.toLong()) xor
            (p.kind.ordinal * 0x165667B19E3779F9L)
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        return h xor (h ushr 31)
    }
}

/**
 * The things beside the roads and out in the fields: lamp posts, wells, scarecrows, waystones and the wayside
 * shrines of the hamlets. The waystone and the shrines face the road and keep to the ground behind its verge.
 */
internal class FarmWayside(private val f: Frame, private val r: Random, private val streetLamps: Boolean = false) {
    fun draw(p: FarmProp) {
        when (p.kind) {
            FarmPropKind.LAMP -> lamp()
            FarmPropKind.WELL -> well()
            FarmPropKind.SCARECROW -> scarecrow()
            FarmPropKind.WAYSTONE -> waystone()
            else -> shrine(p.v)
        }
    }

    /** A lamp post: a base of stone or timber, a fence post, a lantern on top (a soul lantern, now and then). */
    private fun lamp() {
        f.set(0, 0, 0, if (r.nextInt(3) != 0) B(M.WALL_POST) else FENCE)
        f.set(0, 1, 0, FENCE)
        f.set(0, 2, 0, FENCE)
        f.set(0, 3, 0, B(if (r.nextInt(8) == 0) M.SOUL_LANTERN else M.LANTERN, v = if (streetLamps) STREET_LAMP else 0))
    }

    /**
     * A well: a curb of stone round deep water, a lantern hung over it on a chain, and either four posts under a
     * little pointed roof or an arch of two posts and a beam. The curb goes down with the water, so that the
     * water stays where it is whatever the ground is round about.
     */
    private fun well() {
        val curb = if (r.nextInt(3) == 0) FOOTING else STONE
        val palette = ROOFS[r.nextInt(ROOFS.size)]
        val roofed = r.nextInt(3) != 0
        for (u in -1..1) for (v in -1..1) {
            if (u == 0 && v == 0) continue
            for (y in -2..0) if (y == 0 || u == 0 || v == 0) f.set(u, y, v, curb)
        }
        f.set(0, -1, 0, B(M.WATER))
        f.set(0, -2, 0, B(M.WATER))
        f.set(0, -3, 0, STONE)
        f.set(0, 0, 0, AIR)
        if (roofed) {
            for (u in intArrayOf(-1, 1)) for (v in intArrayOf(-1, 1)) for (y in 1..2) f.set(u, y, v, FENCE)
            pyramid(3, palette)
        } else {
            for (u in intArrayOf(-1, 1)) for (y in 1..2) f.set(u, y, 0, FENCE)
            f.fill(-1, 3, 0, 1, 3, 0, B(M.TIMBER, axis = Axis.X))
        }
        f.set(0, 2, 0, B(M.CHAIN))
        f.set(0, 1, 0, B(M.LANTERN, top = true))
    }

    /** A pyramid roof over the 3 by 3 round the spot at [y]: stairs sloping up to a block in the middle. */
    private fun pyramid(y: Int, palette: Int) {
        for (u in -1..1) for (v in -1..1) {
            if (u == 0 && v == 0) continue
            val toward = if (u != 0) (if (u < 0) Dir.E else Dir.W) else (if (v < 0) Dir.S else Dir.N)
            f.set(u, y, v, B(M.ROOF_STAIRS, toward, v = palette))
        }
        f.set(0, y, 0, B(M.ROOF, v = palette))
    }

    /**
     * A scarecrow on its pole, arms out, in an old shirt of hay or cloth, a pumpkin for a head (carved to face
     * ahead, a lit one now and then) and sometimes a hat.
     */
    private fun scarecrow() {
        val shirt = if (r.nextBoolean()) B(M.HAY) else B(M.WOOL, color = SHIRTS[r.nextInt(SHIRTS.size)])
        val head = when (r.nextInt(10)) {
            0 -> B(M.PUMPKIN, Dir.N, v = 2)
            in 1..6 -> B(M.PUMPKIN, Dir.N, v = 1)
            else -> B(M.HAY)
        }
        f.set(0, 0, 0, FENCE)
        f.set(0, 1, 0, shirt)
        f.set(-1, 1, 0, FENCE)
        f.set(1, 1, 0, FENCE)
        f.set(0, 2, 0, head)
        if (r.nextInt(3) == 0) f.set(0, 3, 0, B(M.PLANK_SLAB))
    }

    /**
     * A waystone beside the road: a pillar of old stone on a rough footing under a pale capstone, a pennant on its face
     * to the road and a soul lantern burning on top, for travellers coming in or going out. It takes the one cell of
     * the verge and no more.
     */
    private fun waystone() {
        val stone = if (r.nextInt(3) == 0) B(M.WALL_DARK) else STONE
        val cloth = SHRINE_CLOTHS[r.nextInt(SHRINE_CLOTHS.size)]
        f.set(0, 0, 0, FOOTING)
        for (y in 1..2) f.set(0, y, 0, stone)
        f.set(0, 3, 0, B(M.WALL_TRIM))
        f.set(0, 4, 0, B(M.SOUL_LANTERN))
        f.set(0, 2, -1, B(M.BANNER, Dir.N, color = cloth))
    }

    /**
     * A wayside shrine facing the road, by [variant]: a little stone aedicule with a candle in its niche, a chapel on
     * four posts, a cairn crowned with a soul lantern, or a carved pole with a banner and a lantern. All stand in the
     * three by three that is levelled for it.
     */
    private fun shrine(variant: Int) {
        when (Math.floorMod(variant, 4)) {
            0 -> aedicule()
            1 -> chapel()
            2 -> cairn()
            else -> pole()
        }
    }

    private fun aedicule() {
        val cloth = SHRINE_CLOTHS[r.nextInt(SHRINE_CLOTHS.size)]
        for (u in -1..1) for (v in 0..1) f.set(u, 0, v, STONE)
        for (u in -1..1) for (v in 0..1) if (u != 0 || v != 0) f.set(u, 1, v, STONE)
        f.set(0, 1, 0, B(M.CANDLE))
        for (v in 0..1) {
            f.set(-1, 2, v, B(M.STONE_STAIRS, Dir.E))
            f.set(1, 2, v, B(M.STONE_STAIRS, Dir.W))
            f.set(0, 2, v, STONE)
            f.set(0, 3, v, B(M.STONE_SLAB))
        }
        f.set(0, 0, -1, B(M.STONE_SLAB))
        for (u in intArrayOf(-1, 1)) {
            f.set(u, 1, -1, B(M.BANNER, Dir.N, color = cloth))
            f.set(u, 0, -1, B(M.FLOWER_POT, v = r.nextInt(8)))
        }
    }

    private fun chapel() {
        val palette = ROOFS[r.nextInt(ROOFS.size)]
        val cloth = SHRINE_CLOTHS[r.nextInt(SHRINE_CLOTHS.size)]
        for (u in intArrayOf(-1, 1)) for (v in intArrayOf(-1, 1)) for (y in 0..2) f.set(u, y, v, FENCE)
        for (y in 0..2) f.set(0, y, 1, B(M.PLANKS, v = 1))
        pyramid(3, palette)
        f.set(0, 0, 0, STONE)
        f.set(0, 1, 0, B(M.CANDLE))
        f.set(0, 2, 0, B(M.BANNER, Dir.N, color = cloth))
        f.set(0, 0, -1, B(M.STONE_SLAB))
    }

    private fun cairn() {
        for (u in -1..1) for (v in -1..1) {
            if (u != 0 && v != 0 && r.nextInt(4) == 0) continue
            f.set(u, 0, v, if (r.nextInt(3) == 0) STONE else FOOTING)
        }
        for ((u, v) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            f.set(u, 1, v, if (u == 0 && v == 0 || r.nextInt(3) != 0) FOOTING else STONE)
        }
        f.set(0, 2, 0, STONE)
        f.set(0, 3, 0, B(M.SOUL_LANTERN))
        if (r.nextBoolean()) f.set(if (r.nextBoolean()) -1 else 1, 2, 0, B(M.CANDLE))
    }

    private fun pole() {
        val cloth = SHRINE_CLOTHS[r.nextInt(SHRINE_CLOTHS.size)]
        for (y in 0..4) f.set(0, y, 0, TIMBER)
        f.set(1, 4, 0, FENCE)
        f.set(1, 3, 0, B(M.LANTERN, top = true))
        f.set(0, 3, -1, B(M.BANNER, Dir.N, color = cloth))
        for ((u, v) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) f.set(u, 0, v, B(M.STONE_SLAB))
        f.set(-1, 1, 0, B(M.FLOWER_POT, v = r.nextInt(8)))
    }

    companion object {
        val AIR = B.AIR
        val STONE = B(M.STONE)
        val FOOTING = B(M.FOUNDATION)
        val TIMBER = B(M.TIMBER)
        val FENCE = B(M.FENCE)

        /** The roofs of the little roofed things: dark timber, slate, thatch, tile. */
        val ROOFS = intArrayOf(FarmPiece.ROOF_WOOD, FarmPiece.ROOF_SLATE, FarmPiece.ROOF_THATCH, FarmPiece.ROOF_RED)

        /** The colours of a scarecrow's shirt, and of the cloths at the shrines. */
        val SHIRTS = listOf(Dye.BROWN, Dye.GRAY, Dye.RED, Dye.GREEN, Dye.BLUE)
        val SHRINE_CLOTHS = listOf(Dye.RED, Dye.WHITE, Dye.BLACK, Dye.PURPLE, Dye.YELLOW)
    }
}
