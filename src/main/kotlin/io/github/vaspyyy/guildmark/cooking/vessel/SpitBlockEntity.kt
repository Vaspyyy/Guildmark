package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The Spit's vessel (living-world/cook/design.md 1.6): six joints on a rod. The crank, turned with an empty hand a quarter
 * turn a click, starts the clock the first time and answers a TURN call after; the Knife carves a portion off the joint.
 * [jack] is the Manor tier's clockwork turner (6.7), which answers the TURN calls on its own; [turning] is a display flag the
 * keeper's roast scene (7.5) sets while the innkeeper turns the rod, and the view also says "turning" while a jack turns a lit spit.
 * The same scene lays its joints on a spit that has nothing of its own and lights its fire ([showRoast]): a display, as the
 * house pot's is, shown lit and roasting, with none of the hints or clicks of a cook's spit, and gone when the scene ends.
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

    /** The joints a keeper's roast shows on the rod (the scene's, not saved), while [roastFire] is lit. */
    var roastJoints: Int = 0
        private set

    /** Whether a keeper's roast is shown, its fire lit (the scene's, not saved). */
    private var roastFire = false

    /** The joints laid on since the roast's fire was lit, which the view adds to its count so each comes onto the rod. */
    private var roastLaid = 0

    /**
     * Shows a keeper's roast on this spit: its fire lit or not, and [joints] on the rod (at most [ROAST_JOINTS]'s). Only an empty
     * spit with no fire of its own shows it; a cook's own spit is never touched.
     */
    fun showRoast(fire: Boolean, joints: Int) {
        val j = if (fire) joints.coerceIn(0, ROAST_JOINTS.size) else 0
        if (roastFire == fire && roastJoints == j) return
        roastLaid = if (fire) roastLaid + (j - roastJoints).coerceAtLeast(0) else 0
        roastFire = fire
        roastJoints = j
        vessel.dirty = true
        (level as? ServerLevel)?.let { settle() }
    }

    /** Whether the roast is what the spit shows: it has nothing of its own on it and no fire of its own. */
    private val roasting: Boolean
        get() = roastFire && !vessel.lit && vessel.state == Vessel.State.EMPTY && vessel.items.isEmpty()

    override val shownLit: Boolean get() = vessel.lit || roasting

    // The roast as a ready, lit spit at mid heat, the house's (mode 1: no hints, no clicks foretold), still turning as the scene says
    override fun buildView(level: Level?): StationView {
        val base = super.buildView(level)
        if (!roasting) return base
        val ids = IntArray(roastJoints) { BuiltInRegistries.ITEM.getId(ROAST_JOINTS[it]) }
        return StationView(
            Station.SPIT, Vessel.State.READY.ordinal, 1, true, false, 0, ids, 0, ids.lastOrNull() ?: 0, base.addedCount + roastLaid,
            0, 0L, 0L, 0L, 1, base.swirlUntil, 0, base.turning,
        )
    }

    // The block still says lit with nothing to show it (the server stopped in the middle of a roast): the tick puts it out
    override fun busy(now: Long): Boolean =
        super.busy(now) || blockState.let { it.hasProperty(StationProps.LIT) && it.getValue(StationProps.LIT) != shownLit }

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

    private companion object {
        /** The joints of a keeper's roast, in their places along the rod. */
        val ROAST_JOINTS: List<Item> = listOf(Items.BEEF, Items.MUTTON, Items.BEEF)
    }
}
