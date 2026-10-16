package io.github.vaspyyy.guildmark.ship

/**
 * Where on a cog someone can be: at the wheel, at the bow on lookout, or sat on a bench or the stern
 * locker. Positions are in blocks from the middle of the ship at its waterline: [x] out to starboard,
 * [y] up, [z] forward (see art/scripts/ship.py, which builds the deck round them). A standing station's
 * point is where the feet go; a seat's is the top of what they sit on.
 */
enum class CogStation(val x: Double, val y: Double, val z: Double, val standing: Boolean) {
    HELM(0.0, 1.875, -3.375, true),
    LOOKOUT(0.0, 1.25, 3.5, true),
    STARBOARD_BENCH(0.875, 1.1875, -0.375, false),
    PORT_BENCH(-0.875, 1.1875, -0.375, false),
    STERN_SEAT(0.875, 2.25, -4.0, false);

    companion object {
        /** The order a player boarding with nowhere in mind takes them in: the helm first if it's free. */
        val FOR_PLAYERS = listOf(HELM, STARBOARD_BENCH, PORT_BENCH, LOOKOUT, STERN_SEAT)

        /** The order anyone else aboard (a companion) takes them in. They never take the helm. */
        val FOR_OTHERS = listOf(PORT_BENCH, STARBOARD_BENCH, STERN_SEAT, LOOKOUT)
    }
}
