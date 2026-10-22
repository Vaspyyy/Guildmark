package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.material.MapColor
import java.util.function.UnaryOperator

/** How a Stewpot stands (Appendix C.1): over a ring of stones on three sticks at a camp, or on an iron crane in a hearth. */
enum class StewpotStyle(private val id: String) : StringRepresentable {
    CAMP("camp"),
    HEARTH("hearth");

    override fun getSerializedName(): String = id
}

/**
 * The Stewpot (living-world/cook/design.md 1.1, 11.1): a pot over a fire, in the camp style or the hearth style, lit or
 * not, burnt or not. Part B's; until it lands it is an inert block.
 */
class StewpotBlock(properties: Properties) : StationBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(STYLE, StewpotStyle.CAMP).setValue(StationProps.LIT, false).setValue(BURNT, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(STYLE, StationProps.LIT, BURNT)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = StewpotBlockEntity(pos, state)

    companion object {
        val STYLE: EnumProperty<StewpotStyle> = EnumProperty.create("style", StewpotStyle::class.java)
        val BURNT: BooleanProperty = BooleanProperty.create("burnt")

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.METAL).strength(2.5f).sound(SoundType.METAL).noOcclusion() }
    }
}
