package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** The Hob's vessel (living-world/cook/design.md 1.6): a pan that holds six items laid flat. */
class HobBlockEntity(pos: BlockPos, state: BlockState) : VesselBlockEntity(CookRegistry.HOB_ENTITY.get(), pos, state, Station.HOB)
