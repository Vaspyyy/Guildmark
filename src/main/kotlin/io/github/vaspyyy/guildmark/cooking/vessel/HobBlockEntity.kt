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

/**
 * The Hob's vessel (living-world/cook/design.md 1.6): a pan that holds six items laid flat. The Turner does everything the
 * ladle does at the pot: the first scrape starts the clock, a scrape answers a STICK, a flip answers a TURN, and it lifts the
 * dish out a piece at a time, with no bowl.
 */
class HobBlockEntity(pos: BlockPos, state: BlockState) : VesselBlockEntity(CookRegistry.HOB_ENTITY.get(), pos, state, Station.HOB) {
    override fun use(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack, region: Region): Used {
        if (stack.isEmpty) return emptyHand(level, player, region)
        if (Actions.isLadle(stack) && player.isShiftKeyDown) return Serving.taste(this, level, player)
        if (Actions.isTurner(stack)) return turner(level, player, hand, stack)
        if (Actions.toolOf(stack) != null) {
            say(player, "wants_turner")
            return Used.REFUSED
        }
        if (region == Region.FIRE) {
            say(player, "not_fuel")
            return Used.REFUSED
        }
        return Actions.ingredient(this, level, player, stack)
    }

    /**
     * The Turner on the pan: on one still open the first scrape starts the clock; on one cooking it answers a STICK (scrape) or a
     * TURN (flip), and scrapes for nothing otherwise; on a ready, overdone or served one it lifts a piece out.
     */
    private fun turner(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack): Used {
        val v = vessel
        if (v.open) {
            val used = Actions.start(this, level, player)
            if (used == Used.DONE) {
                scrape(level, player, CookAction.STIR)
                Actions.wear(player, hand, stack)
            }
            return used
        }
        val pending = v.call
        if (pending == Call.STICK || pending == Call.TURN) {
            Actions.answer(this, level, player, Call.STICK, Call.TURN)
            if (pending == Call.TURN) {
                ring(level, "cook.flip", 1.0f, 1.0f)
                scrape(level, player, CookAction.FLIP)
            } else {
                scrape(level, player, CookAction.STIR)
            }
            Actions.wear(player, hand, stack)
            return Used.DONE
        }
        return when (v.state) {
            Vessel.State.READY, Vessel.State.DONE, Vessel.State.SERVED -> {
                val used = Serving.serve(this, level, player, CookAction.SERVE_PEEL)
                if (used == Used.DONE) Actions.wear(player, hand, stack)
                used
            }
            Vessel.State.BURNT -> {
                say(player, "burnt")
                Used.REFUSED
            }
            else -> {
                scrape(level, player, CookAction.STIR)
                Used.DONE
            }
        }
    }

    /** The pan is scraped: the surface moves, the iron rings. */
    private fun scrape(level: ServerLevel, player: ServerPlayer, action: CookAction) {
        swirl(level.gameTime + STIR_TICKS)
        ring(level, "cook.stir", 0.8f, 1.3f)
        CookActions.play(player, action, blockPos)
    }
}
