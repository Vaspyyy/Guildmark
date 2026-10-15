package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The trees of the country, drawn in the frame of their prop: broad oaks, tall poplars and firs by the roads and
 * in the hedgerows, and the lower, rounder fruit trees of the orchards, in blossom or bearing fruit.
 */
internal class FarmTrees(private val f: Frame, private val r: Random) {
    fun draw(p: FarmProp) {
        f.set(0, -1, 0, B(M.SOIL))
        if (p.kind == FarmPropKind.FRUIT_TREE) fruitTree(p.v) else tree(p.v)
    }

    /**
     * A fruit tree, a short trunk under a dome of leaves five across: by [variant], 1 in blossom (pink and white all
     * over), the others bearing apples (0), oranges (2) or pears (4), among the leaves of the two broad layers.
     */
    private fun fruitTree(variant: Int) {
        val blossom = variant == 1
        val fruit = if (blossom) null else FRUITS[Math.floorMod(variant / 2, FRUITS.size)]
        val trunk = 2 + r.nextInt(2)
        for (y in 0..trunk + 1) f.set(0, y, 0, LOG)
        // The two broad layers on the trunk, the ragged edge of the lower hung with fruit, then a dome over them
        for (y in trunk..trunk + 1) for (u in -2..2) for (v in -2..2) {
            val edge = Math.abs(u) == 2 || Math.abs(v) == 2
            if (u == 0 && v == 0 || (Math.abs(u) == 2 && Math.abs(v) == 2) || (edge && r.nextInt(6) == 0)) continue
            f.set(u, y, v, leaf(blossom, if (edge) fruit else null))
        }
        for (u in -1..1) for (v in -1..1) f.set(u, trunk + 2, v, leaf(blossom, null))
        for ((u, v) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) if (u == 0 && v == 0 || r.nextInt(4) != 0) f.set(u, trunk + 3, v, leaf(blossom, null))
    }

    /** A leaf, or [fruit] among the leaves one time in four; in [blossom] four leaves in five flower. */
    private fun leaf(blossom: Boolean, fruit: Dye?): B {
        if (fruit != null && r.nextInt(4) == 0) return B(M.WOOL, color = fruit)
        return B(M.LEAVES, v = if (blossom && r.nextInt(5) != 0) 1 else 0)
    }

    /** A tree of the roadside, by [variant]: a broad oak, a tall poplar, or a fir. */
    private fun tree(variant: Int) {
        when (Math.floorMod(variant, 3)) {
            0 -> crown(4 + r.nextInt(2), 3.0, 3.0)
            1 -> crown(3, 1.8, 5.5)
            else -> fir()
        }
    }

    /**
     * A trunk [trunk] high under a ragged crown of leaves [rx] out and [ry] up from its middle, which stands with its
     * lower part round the trunk's top; two boughs reach out under it.
     */
    private fun crown(trunk: Int, rx: Double, ry: Double) {
        val reach = Math.ceil(rx).toInt()
        val up = Math.ceil(ry).toInt()
        val middle = trunk + up - 1
        for (u in -reach..reach) for (v in -reach..reach) for (dy in -up..up) {
            if (u == 0 && v == 0 && dy <= 0) continue
            val far = (u * u + v * v) / (rx * rx) + (dy * dy) / (ry * ry)
            if (far <= 1.1 - 0.3 * r.nextDouble()) f.set(u, middle + dy, v, LEAVES)
        }
        for (y in 0..middle) f.set(0, y, 0, LOG)
        repeat(2) {
            val toward = Dir.entries[r.nextInt(4)]
            f.set(toward.dx, trunk, toward.dz, B(M.LOG, axis = axisOf(toward)))
        }
    }

    /** A fir: a trunk and tiers of needles, broad below and narrowing to a point. */
    private fun fir() {
        val tiers = intArrayOf(3, 2, 3, 2, 2, 1, 1, 0)
        val trunk = 2
        for (y in 0 until trunk + tiers.size - 2) f.set(0, y, 0, LOG)
        for ((k, radius) in tiers.withIndex()) {
            for (u in -radius..radius) for (v in -radius..radius) {
                if (u == 0 && v == 0 || u * u + v * v > radius * radius + 1) continue
                f.set(u, trunk + k, v, LEAVES)
            }
        }
        f.set(0, trunk + tiers.size, 0, LEAVES)
        f.set(0, trunk + tiers.size + 1, 0, LEAVES)
    }

    companion object {
        val LOG = B(M.LOG)
        val LEAVES = B(M.LEAVES)

        /** The colours of the fruit among the leaves: apples, oranges, pears. */
        val FRUITS = listOf(Dye.RED, Dye.ORANGE, Dye.LIME)
    }
}
