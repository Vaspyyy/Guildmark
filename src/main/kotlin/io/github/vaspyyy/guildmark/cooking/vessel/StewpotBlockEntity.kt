package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** The Stewpot's vessel (living-world/cook/design.md 1.2). Part B's; until it lands it is a cold, empty pot. */
class StewpotBlockEntity(pos: BlockPos, state: BlockState) : StationBlockEntity(CookRegistry.STEWPOT_ENTITY.get(), pos, state, Station.STEWPOT)
