package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * The Larder's 18 slots (living-world/cook/design.md 3.2, 6.4). Part D's; until it lands a larder holds nothing.
 * [backing] is the container the slots really are: null is the larder's own 18, and a cookshop gives its larder a view
 * of the shop's record (Part H) instead.
 */
class LarderBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(CookRegistry.LARDER_ENTITY.get(), pos, state) {
    var backing: Container? = null
}
