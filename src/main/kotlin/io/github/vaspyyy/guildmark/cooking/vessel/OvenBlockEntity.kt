package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState

/**
 * The Bake oven's vessel (living-world/cook/design.md 1.6): a tray of ten items behind a door. The door swings open while
 * something is put in and for 40 ticks after; an empty hand on it shuts it, and the first shut starts the heating. The
 * Turner turns the trays and lifts a loaf, a plank of salmon or a strip out on the peel, a portion a click; a pie or a tart
 * is served with the Knife, a slice a click.
 */
class OvenBlockEntity(pos: BlockPos, state: BlockState) : VesselBlockEntity(CookRegistry.OVEN_ENTITY.get(), pos, state, Station.OVEN) {
    override fun use(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack, region: Region): Used {
        if (stack.isEmpty) return emptyHand(level, player, region)
        if (Actions.isLadle(stack) && player.isShiftKeyDown) return Serving.taste(this, level, player)
        if (Actions.isTurner(stack)) return turner(level, player, hand, stack)
        if (Actions.isKnife(stack)) return knife(level, player, hand, stack)
        if (Actions.toolOf(stack) != null) {
            say(player, "not_for.oven")
            return Used.REFUSED
        }
        if (region == Region.FIRE) {
            say(player, "not_fuel")
            return Used.REFUSED
        }
        val used = Actions.ingredient(this, level, player, stack)
        if (used == Used.DONE) openDoor(level)
        return used
    }

    /** The door swings open and stays so for 40 ticks (the view's `swirlUntil`). */
    private fun openDoor(level: ServerLevel) {
        val shut = swirlUntil <= level.gameTime
        swirl(level.gameTime + DOOR_TICKS)
        if (shut) ring(level, "cook.oven_open", 0.9f, 1.0f)
    }

    /** An empty hand on the door shuts it; on an oven still open that starts the heating. */
    override fun emptyHand(level: ServerLevel, player: ServerPlayer, region: Region): Used {
        if (region != Region.VESSEL || player.isShiftKeyDown) return super.emptyHand(level, player, region)
        if (!vessel.open) return status(player)
        val used = Actions.start(this, level, player)
        if (used == Used.DONE) {
            swirl(level.gameTime)
            ring(level, "cook.oven_shut", 1.0f, 1.0f)
        }
        return used
    }

    /** Whether a dish is cut with the Knife and not lifted with the Turner. */
    private fun sliced(kind: DishKind?): Boolean = kind == DishKind.PIE || kind == DishKind.TART

    /** The Turner: it turns the trays when a TURN is called, and lifts a portion out of a loaf, a salmon or a strip. */
    private fun turner(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack): Used {
        val v = vessel
        if (v.open) {
            say(player, "shut_first")
            return Used.REFUSED
        }
        if (v.call == Call.TURN) {
            Actions.answer(this, level, player, Call.TURN)
            openDoor(level)
            ring(level, "cook.flip", 1.0f, 1.0f)
            CookActions.play(player, CookAction.FLIP, blockPos)
            Actions.wear(player, hand, stack)
            return Used.DONE
        }
        return when (v.state) {
            Vessel.State.READY, Vessel.State.DONE, Vessel.State.SERVED -> {
                if (sliced(v.kind)) {
                    say(player, "use_knife")
                    Used.REFUSED
                } else {
                    openDoor(level)
                    val used = Serving.serve(this, level, player, CookAction.SERVE_PEEL)
                    if (used == Used.DONE) Actions.wear(player, hand, stack)
                    used
                }
            }
            Vessel.State.BURNT -> {
                say(player, "burnt")
                Used.REFUSED
            }
            else -> {
                openDoor(level)
                Used.DONE
            }
        }
    }

    /** The Knife cuts a pie or a tart, a slice a click, the pie in the oven shrinking by a sixth each time. */
    private fun knife(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack): Used {
        val v = vessel
        val dish = v.state == Vessel.State.READY || v.state == Vessel.State.DONE || v.state == Vessel.State.SERVED
        if (dish && !sliced(v.kind)) {
            say(player, "use_turner")
            return Used.REFUSED
        }
        if (dish) openDoor(level)
        val used = Serving.serve(this, level, player, CookAction.SLICE)
        if (used == Used.DONE) {
            puff(level, CookParticles.CRUMB.get(), 5, 0.2)
            Actions.wear(player, hand, stack)
        }
        return used
    }
}
