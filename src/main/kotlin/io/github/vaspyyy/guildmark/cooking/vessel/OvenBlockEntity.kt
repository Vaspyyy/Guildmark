package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** The Bake oven's vessel (living-world/cook/design.md 1.6). Part B's; until it lands it is a cold, empty oven. */
class OvenBlockEntity(pos: BlockPos, state: BlockState) : StationBlockEntity(CookRegistry.OVEN_ENTITY.get(), pos, state, Station.OVEN)
