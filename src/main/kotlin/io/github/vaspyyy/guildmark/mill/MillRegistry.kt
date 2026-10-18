package io.github.vaspyyy.guildmark.mill

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Function
import java.util.function.UnaryOperator

/**
 * Everything the mills add to the game's registries: the hubs of the windmills' sails and the watermills' wheels, and
 * the block entity they share. Neither has an item: the capitals build them. Their models and textures come from
 * art/scripts/mill_art.py, their sounds from art/audio/make_mill_audio.py (listed in sounds.json).
 */
object MillRegistry {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)

    /** A windmill's sails, on the hub at the end of its axle. */
    val SAILS: DeferredBlock<MillBlock> = BLOCKS.registerBlock("windmill_sails", Function { MillBlock(it, MillKind.SAILS) }, props {
        it.mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD).noOcclusion().noLootTable().pushReaction(PushReaction.IMMOVEABLE)
            .isRedstoneConductor { _, _, _ -> false }.isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
    })

    /** A watermill's wheel, on the end of the axle that comes out of the mill's wall over the stream. */
    val WHEEL: DeferredBlock<MillBlock> = BLOCKS.registerBlock("water_wheel", Function { MillBlock(it, MillKind.WHEEL) }, props {
        it.mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD).noOcclusion().noLootTable().pushReaction(PushReaction.IMMOVEABLE)
            .isRedstoneConductor { _, _, _ -> false }.isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
    })

    /** The block entity both hubs share: the capital it grinds for, and on a client how it's turning. */
    val MILL: DeferredHolder<BlockEntityType<*>, BlockEntityType<MillBlockEntity>> = BLOCK_ENTITIES.register("mill") { ->
        BlockEntityType(BlockEntityType.BlockEntitySupplier(::MillBlockEntity), SAILS.get(), WHEEL.get())
    }

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }
}
