package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * The chalked lines of a Menu board (living-world/cook/design.md 5.4). Part G's; until it lands the board is blank. The
 * ledger writes the inn's lines, the cookshop (Part H) its stock's and the homestead's pantry (Part K) its own, with
 * [setLines]; they are sent to the clients only when they change.
 */
class MenuBoardBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(CookRegistry.MENU_BOARD_ENTITY.get(), pos, state) {
    private val shown: List<Component> = emptyList()

    /** The lines now on the board, top to bottom (stub: always none). */
    val lines: List<Component> get() = shown

    /** Writes [lines] on the board (stub: nothing is written). */
    fun setLines(lines: List<Component>) {}
}
