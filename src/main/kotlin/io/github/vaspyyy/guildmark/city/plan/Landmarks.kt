package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The great buildings of a grand capital: the cathedral on the old town's square, the palace keep on the
 * citadel, and the manors of the noble quarter. Each is drawn in its own frame, facing north (see [Frame]).
 */
class Landmarks(private val f: Frame, private val b: Building, private val r: Random) {
    fun draw() {
        when (b.kind) {
            Kind.CATHEDRAL -> cathedral()
            Kind.PALACE -> palace()
            Kind.MANOR -> manor()
            else -> {}
        }
    }

    // Placeholders until the real designs are in: a hollow block the size of the lot
    private fun cathedral() = shell(24)
    private fun palace() = shell(20)
    private fun manor() = shell(10)

    private fun shell(height: Int) {
        for (u in 0 until f.w) for (v in 0 until f.d) {
            val edge = u == 0 || v == 0 || u == f.w - 1 || v == f.d - 1
            for (y in 0 until height) f.set(u, y, v, if (edge) B(M.STONE) else B.AIR)
            f.set(u, height, v, B(M.ROOF))
        }
    }
}
