package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * The Prep Board's heap (living-world/cook/design.md 1.1, 11.1). Part B's; until it lands it holds nothing. It is a
 * [StationBlockEntity] because it is synced like one, but it is a helper and not a fire, so it has no [io.github.vaspyyy.guildmark.cooking.Station].
 */
class PrepBoardBlockEntity(pos: BlockPos, state: BlockState) : StationBlockEntity(CookRegistry.PREP_BOARD_ENTITY.get(), pos, state, null)
