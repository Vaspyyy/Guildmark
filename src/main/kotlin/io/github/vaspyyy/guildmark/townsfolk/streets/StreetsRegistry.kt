package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * What the streets add to the registries (M6 Part D2): the alms table, a trestle of three blocks set up by the market's
 * well on a hungry day, its pot and bowls drawn by its middle block's renderer (client/streets/StreetsClient). Like the
 * festivals' decorations it has no item, no drop and no recipe, can't be broken or moved, and is taken down again,
 * whatever stood there put back (it goes up through [io.github.vaspyyy.guildmark.festival.FestivalRestores]). The
 * dressing and the "To let" boards are the festivals' own blocks in new looks; the slate is drawn on the displays.
 */
object StreetsRegistry {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)

    /** The alms table: its two ends and its middle, where the pot stands (see [AlmsTableBlock]). */
    val ALMS_TABLE: DeferredBlock<AlmsTableBlock> = BLOCKS.registerBlock("alms_table", ::AlmsTableBlock, props {
        it.mapColor(MapColor.WOOD).strength(-1f, 3600000f).noLootTable().noOcclusion().randomTicks()
            .pushReaction(PushReaction.IMMOVEABLE).sound(SoundType.WOOD)
            .isValidSpawn { _, _, _, _ -> false }.isRedstoneConductor { _, _, _ -> false }
            .isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
    })

    val ALMS_TABLE_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<AlmsTableBlockEntity>> =
        BLOCK_ENTITIES.register("alms_table") { ->
            BlockEntityType(BlockEntityType.BlockEntitySupplier(::AlmsTableBlockEntity), ALMS_TABLE.get())
        }

    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        BLOCK_ENTITIES.register(modBus)
    }

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }
}
