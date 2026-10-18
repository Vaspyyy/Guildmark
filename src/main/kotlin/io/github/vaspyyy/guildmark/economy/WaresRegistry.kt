package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.plan.Look
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * The market's displays of goods in the game's registries: a block for each look (see [WaresBlock]), its item, and
 * the block entity they share; and the setting that switches them on. Their models and textures come from
 * art/scripts/wares_art.py.
 */
object WaresRegistry {
    /** The registers the displays' blocks, items and block entity go in. */
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)

    /** Each look's block, named for it ([Look.id]): wicker, boards and crates of wood, the sacks of sacking. */
    val DISPLAYS: Map<Look, DeferredBlock<WaresBlock>> = Look.entries.associateWith { look ->
        BLOCKS.registerBlock(look.id, { p -> WaresBlock(look, p) }, props {
            val sacks = look == Look.SACKS
            it.mapColor(if (sacks) MapColor.SAND else MapColor.WOOD).strength(if (sacks) 0.5f else 0.8f)
                .sound(if (sacks) SoundType.WOOL else SoundType.WOOD).noOcclusion().ignitedByLava()
        })
    }

    /** Each look's item, to put one down by hand (it shows the first of its look's goods). */
    val DISPLAY_ITEMS: Map<Look, DeferredItem<BlockItem>> = DISPLAYS.mapValues { (look, block) -> ITEMS.registerSimpleBlockItem(look.id, block) }

    /** The block entity all the displays share (see [WaresBlockEntity]). */
    val ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<WaresBlockEntity>> = BLOCK_ENTITIES.register("wares") { ->
        BlockEntityType(BlockEntityType.BlockEntitySupplier(::WaresBlockEntity), DISPLAYS.values.map { it.get() }.toSet())
    }

    /** [look]'s block. */
    fun block(look: Look): WaresBlock = DISPLAYS.getValue(look).get()

    /** Whether the capitals set out displays of their goods, and keep them as full as their markets (the setting). */
    fun enabled(): Boolean = !GuildmarkConfig.SPEC.isLoaded || GuildmarkConfig.MARKET_DISPLAYS.get()

    /** Registers it all, lists the displays in the mod's creative tab, and starts [WaresPlacer] and [WaresCommand]. */
    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        BLOCK_ENTITIES.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) for (item in DISPLAY_ITEMS.values) event.accept(item.get())
        }
        WaresPlacer.register()
        WaresCommand.init()
    }

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }
}
