package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock

/**
 * The base of the cooking stations' blocks (living-world/cook/design.md 1.1, 12.4 Part B): the Stewpot, the Hob, the Bake
 * oven, the Spit and the Prep Board, each with a [StationBlockEntity] that holds its vessel. Part B's: it finds the three
 * regions of a click (the vessel above, the fire below, the heat control at the side) from where the click lands, and
 * calls the vessel. Until it lands a station is a block that can be set down, broken and picked up, and does nothing
 * when it is used.
 */
abstract class StationBlock(properties: Properties) : Block(properties), EntityBlock
