package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

object ModBlocks {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)

    // Placeholder block for now; becomes a block entity holding quest notes later.
    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    val QUEST_BOARD: DeferredBlock<Block> = BLOCKS.registerSimpleBlock("quest_board", UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD)
    })
}
