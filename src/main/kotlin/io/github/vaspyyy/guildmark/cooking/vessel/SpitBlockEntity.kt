package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The Spit's vessel (living-world/cook/design.md 1.6): six joints on a rod. [jack] is the Manor tier's clockwork turner (6.7),
 * which answers the TURN calls on its own; [turning] is a display flag the keeper's roast scene (7.5) sets while the innkeeper turns
 * the rod, and the view also says "turning" while a jack turns a lit spit.
 */
class SpitBlockEntity(pos: BlockPos, state: BlockState) : VesselBlockEntity(CookRegistry.SPIT_ENTITY.get(), pos, state, Station.SPIT) {
    /** Whether a clockwork jack turns this spit. Saved; set by the homestead's fit-out. */
    var jack: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            vessel.dirty = true
            setChanged()
            (level as? ServerLevel)?.let { settle() }
        }

    /** Whether the spit is shown turning on its own (a scene's, not saved). */
    var turning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            vessel.dirty = true
            (level as? ServerLevel)?.let { settle() }
        }

    override val jackTurns: Boolean get() = jack

    override val viewTurning: Boolean get() = turning || (jack && vessel.lit && vessel.cooking)

    override fun saveExtra(output: ValueOutput) {
        if (jack) output.putBoolean("jack", true)
    }

    override fun loadExtra(input: ValueInput) {
        jack = input.getBooleanOr("jack", false)
    }
}
