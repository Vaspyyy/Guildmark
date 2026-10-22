package io.github.vaspyyy.guildmark.cooking.town

import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotMode
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LevelAccessor

/** How a retrofit run goes (living-world/cook/design.md 7.2): [PREVIEW] only lists what it would do, [SEEN] does it with a puff and a sound, [FULL] also tries the Spit. */
enum class FitMode { PREVIEW, SEEN, FULL }

/** What a retrofit run came to, as `Furnish.Report` says it for beds: what was [made], [kept] as it was, left for [noRoom] and still [waiting], and where. */
class FitReport {
    var made = 0
    var kept = 0
    var noRoom = 0
    var waiting = 0
    val placed = ArrayList<BlockPos>()
}

/**
 * The retrofit round (living-world/cook/design.md 7.2): gives an old capital the house pot, the guest pot, the bake oven
 * and the slate where the plan's own blocks still stand and nobody has touched them. Part I1's; until it lands it fits nothing.
 */
object KitchenFit {
    /** Part I1's registration: the round's tick and the `cook fit` command (stub: nothing). */
    fun register() {}

    /** Runs the retrofit once on the capital with centre [capital] in [mode] (stub: nothing found, nothing done). */
    fun run(level: ServerLevel, capital: Long, mode: FitMode): FitReport = FitReport()

    /**
     * The plan's own pots are the town's (7.1): the pot a new capital's plan draws in the tavern's hearth is the innkeeper's
     * (HOUSE), the camp pot in its back corner the guests' (GUEST). Called by the city's canvas as it draws one, after the block is
     * placed; the pot's block entity is, in a chunk still being made, only a note of one, which [LevelAccessor.getBlockEntity]
     * turns into the real one, so the mode is set on it before the chunk is saved.
     */
    fun potDrawn(level: LevelAccessor, pos: BlockPos, guest: Boolean) {
        (level.getBlockEntity(pos) as? StewpotBlockEntity)?.mode = if (guest) StewpotMode.GUEST else StewpotMode.HOUSE
    }
}
