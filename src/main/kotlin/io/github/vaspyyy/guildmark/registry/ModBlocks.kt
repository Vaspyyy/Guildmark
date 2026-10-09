package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

object ModBlocks {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    val QUEST_BOARD: DeferredBlock<QuestBoardBlock> = BLOCKS.registerBlock("quest_board", ::QuestBoardBlock, UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)
    })
}
