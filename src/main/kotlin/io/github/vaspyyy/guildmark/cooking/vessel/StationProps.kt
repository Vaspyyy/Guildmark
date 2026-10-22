package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty

/**
 * The block state properties the cooking blocks share (living-world/cook/design.md 11.1, Appendix C.1): the heat [NOTCH]
 * (0 to 2: the Hob's and the oven's damper, the Spit's height), whether a fire is [LIT], and the way a block [FACING]
 * faces. The names are contracts: the blockstate files (M1's) are written against them.
 */
object StationProps {
    val NOTCH: IntegerProperty = IntegerProperty.create("notch", 0, 2)
    val LIT: BooleanProperty = BlockStateProperties.LIT
    val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING
}
