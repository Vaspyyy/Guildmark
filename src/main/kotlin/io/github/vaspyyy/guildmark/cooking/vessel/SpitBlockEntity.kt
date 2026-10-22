package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The Spit's vessel (living-world/cook/design.md 1.6): six joints on a rod. The crank, turned with an empty hand a quarter
 * turn a click, starts the clock the first time and answers a TURN call after; the Knife carves a portion off the joint.
 * [jack] is the Manor tier's clockwork turner (6.7), which answers the TURN calls on its own; [turning] is a display flag the
 * keeper's roast scene (7.5) sets while the innkeeper turns the rod, and the view also says "turning" while a jack turns a lit spit.
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

    override fun use(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack, region: Region): Used {
        if (stack.isEmpty) return emptyHand(level, player, region)
        if (Actions.isLadle(stack) && player.isShiftKeyDown) return Serving.taste(this, level, player)
        if (Actions.isKnife(stack)) return carve(level, player, hand, stack)
        if (Actions.toolOf(stack) != null) {
            say(player, "wants_knife")
            return Used.REFUSED
        }
        if (region == Region.FIRE) {
            say(player, "not_fuel")
            return Used.REFUSED
        }
        return Actions.ingredient(this, level, player, stack)
    }

    /** An empty hand on the crank turns the rod a quarter turn; on the rest it is the heat control, or the tip-out, as at every station. */
    override fun emptyHand(level: ServerLevel, player: ServerPlayer, region: Region): Used {
        if (region != Region.CRANK) return super.emptyHand(level, player, region)
        val v = vessel
        if (v.open) {
            val used = Actions.start(this, level, player)
            if (used != Used.DONE) return used
        } else {
            Actions.answer(this, level, player, Call.TURN)
        }
        swirl(level.gameTime + CRANK_TICKS)
        ring(level, "cook.crank", 0.8f, 1.0f)
        CookActions.play(player, CookAction.TURN, blockPos)
        return Used.DONE
    }

    /** The Knife carves a portion off the joint once it is ready; the first carve fixes the batch. */
    private fun carve(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack): Used {
        val used = Serving.serve(this, level, player, CookAction.CARVE)
        if (used == Used.DONE) Actions.wear(player, hand, stack)
        return used
    }
}
