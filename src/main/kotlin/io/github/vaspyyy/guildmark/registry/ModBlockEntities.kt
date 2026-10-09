package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object ModBlockEntities {
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)

    val QUEST_BOARD: DeferredHolder<BlockEntityType<*>, BlockEntityType<QuestBoardBlockEntity>> = BLOCK_ENTITIES.register("quest_board") { ->
        BlockEntityType(BlockEntityType.BlockEntitySupplier(::QuestBoardBlockEntity), ModBlocks.QUEST_BOARD.get())
    }
}
