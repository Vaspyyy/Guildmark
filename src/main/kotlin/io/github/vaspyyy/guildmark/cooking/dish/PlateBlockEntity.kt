package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** The dish on a Plate (living-world/cook/design.md 3.5). Part D's; until it lands a plate holds nothing. */
class PlateBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(CookRegistry.PLATE_ENTITY.get(), pos, state)
