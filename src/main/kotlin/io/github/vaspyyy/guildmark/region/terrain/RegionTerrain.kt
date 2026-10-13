package io.github.vaspyyy.guildmark.region.terrain

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.DoubleHighBlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Function
import java.util.function.UnaryOperator

/**
 * The wild lands' own ground: what grows, falls and forms in the marsh, on the ashen highlands, in the frozen
 * peaks and in the crystal deeps, as blocks of their own and the features that lay them in newly explored land
 * (TerrainFeatures). Textures, models and blockstates: art/scripts/region_terrain.py. Where the features run:
 * data/guildmark/worldgen/placed_feature/region_*.json and the region_terrain biome modifiers.
 */
object RegionTerrain {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val FEATURE_TYPES: DeferredRegister<MapCodec<out Feature>> = DeferredRegister.create(Registries.FEATURE_TYPE, Guildmark.MOD_ID)

    // The marsh: rotting leaves over the mud, bog lanterns, cattails in the shallows
    val BOG_ROT: DeferredBlock<CarpetBlock> = BLOCKS.registerBlock("bog_rot", ::CarpetBlock, props {
        it.mapColor(MapColor.TERRACOTTA_GREEN).strength(0.1f).sound(SoundType.MOSS_CARPET).ignitedByLava().pushReaction(PushReaction.POPPED)
    })
    val BOG_LANTERN: DeferredBlock<BogLanternBlock> = BLOCKS.registerBlock("bog_lantern", ::BogLanternBlock, props {
        it.mapColor(MapColor.COLOR_LIGHT_GREEN).noCollision().instabreak().sound(SoundType.FUNGUS).lightLevel { 3 }
            .offsetType(BlockBehaviour.OffsetType.XZ).ignitedByLava().pushReaction(PushReaction.POPPED)
    })
    val CATTAILS: DeferredBlock<CattailBlock> = BLOCKS.registerBlock("cattails", ::CattailBlock, props {
        it.mapColor(MapColor.PLANT).noCollision().instabreak().sound(SoundType.WET_GRASS).offsetType(BlockBehaviour.OffsetType.XZ)
            .pushReaction(PushReaction.POPPED)
    })

    // The ashen highlands: ash fallen in drifts, burnt trees, vents still glowing underneath
    /** Layers like snow's, but it never melts: no random ticks. */
    val ASH: DeferredBlock<SnowLayerBlock> = BLOCKS.registerBlock("ash", ::SnowLayerBlock, props {
        it.mapColor(MapColor.COLOR_LIGHT_GRAY).replaceable().forceSolidOff().strength(0.1f).sound(SoundType.SAND)
            .isViewBlocking { state, _, _, _ -> state.getValue(SnowLayerBlock.LAYERS) >= 8 }.pushReaction(PushReaction.POPPED)
    })
    val CHARRED_LOG: DeferredBlock<RotatedPillarBlock> = BLOCKS.registerBlock("charred_log", ::RotatedPillarBlock, props {
        it.mapColor(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASS).strength(2f).sound(SoundType.WOOD)
    })
    val EMBER_VENT: DeferredBlock<EmberVentBlock> = BLOCKS.registerBlock("ember_vent", ::EmberVentBlock, props {
        it.mapColor(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.25f, 4.2f)
            .sound(SoundType.BASALT).lightLevel { 3 }.isValidSpawn { _, _, _, type -> type.fireImmune() }
    })

    // The frozen peaks: icicles under every ledge, hoarfrost on the stone
    val ICICLE: DeferredBlock<IcicleBlock> = BLOCKS.registerBlock("icicle", ::IcicleBlock, props {
        it.mapColor(MapColor.ICE).forceSolidOn().noOcclusion().sound(SoundType.GLASS).strength(0.5f).dynamicShape()
            .offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.POPPED).isRedstoneConductor { _, _, _ -> false }
    })
    val HOARFROST: DeferredBlock<HoarfrostBlock> = BLOCKS.registerBlock("hoarfrost", ::HoarfrostBlock, props {
        it.mapColor(MapColor.SNOW).replaceable().noCollision().strength(0.2f).sound(SoundType.POWDER_SNOW).randomTicks()
            .pushReaction(PushReaction.POPPED)
    })

    // The crystal deeps: glimmer crystal in clusters, veins and pillars
    val SMALL_GLIMMER_CLUSTER: DeferredBlock<GlimmerClusterBlock> =
        BLOCKS.registerBlock("small_glimmer_cluster", cluster(4f, 8f), clusterProps(SoundType.SMALL_AMETHYST_BUD, 1))
    val GLIMMER_CLUSTER: DeferredBlock<GlimmerClusterBlock> =
        BLOCKS.registerBlock("glimmer_cluster", cluster(8f, 10f), clusterProps(SoundType.MEDIUM_AMETHYST_BUD, 2))
    val LARGE_GLIMMER_CLUSTER: DeferredBlock<GlimmerClusterBlock> =
        BLOCKS.registerBlock("large_glimmer_cluster", cluster(13f, 12f), clusterProps(SoundType.AMETHYST_CLUSTER, 4))
    val GLIMMERSTONE: DeferredBlock<Block> = BLOCKS.registerBlock("glimmerstone", ::Block, props {
        it.mapColor(MapColor.DEEPSLATE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(3f, 6f)
            .sound(SoundType.DEEPSLATE).lightLevel { 1 }
    })
    val GLIMMER_CRYSTAL: DeferredBlock<GlimmerCrystalBlock> = BLOCKS.registerBlock("glimmer_crystal", ::GlimmerCrystalBlock, props {
        it.mapColor(MapColor.COLOR_PURPLE).strength(1.5f).sound(SoundType.AMETHYST).requiresCorrectToolForDrops().lightLevel { 6 }
            .noOcclusion().isValidSpawn { _, _, _, _ -> false }.isRedstoneConductor { _, _, _ -> false }
            .isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
    })

    /** Every block's item, in the order the creative tabs list them. */
    val BLOCK_ITEMS: List<DeferredItem<out BlockItem>> = listOf(
        ITEMS.registerSimpleBlockItem("bog_rot", BOG_ROT),
        ITEMS.registerSimpleBlockItem("bog_lantern", BOG_LANTERN),
        ITEMS.registerItem("cattails", Function { DoubleHighBlockItem(CATTAILS.get(), it) }, UnaryOperator<Item.Properties> { it.useBlockDescriptionPrefix() }),
        ITEMS.registerSimpleBlockItem("ash", ASH),
        ITEMS.registerSimpleBlockItem("charred_log", CHARRED_LOG),
        ITEMS.registerSimpleBlockItem("ember_vent", EMBER_VENT),
        ITEMS.registerSimpleBlockItem("icicle", ICICLE),
        ITEMS.registerSimpleBlockItem("hoarfrost", HOARFROST),
        ITEMS.registerSimpleBlockItem("small_glimmer_cluster", SMALL_GLIMMER_CLUSTER),
        ITEMS.registerSimpleBlockItem("glimmer_cluster", GLIMMER_CLUSTER),
        ITEMS.registerSimpleBlockItem("large_glimmer_cluster", LARGE_GLIMMER_CLUSTER),
        ITEMS.registerSimpleBlockItem("glimmerstone", GLIMMERSTONE),
        ITEMS.registerSimpleBlockItem("glimmer_crystal", GLIMMER_CRYSTAL),
    )

    val MARSH_GROWTH: DeferredHolder<MapCodec<out Feature>, MapCodec<MarshGrowth>> = FEATURE_TYPES.register("marsh_growth") { -> MarshGrowth.CODEC }
    val ASHFALL: DeferredHolder<MapCodec<out Feature>, MapCodec<Ashfall>> = FEATURE_TYPES.register("ashfall") { -> Ashfall.CODEC }
    val RIME: DeferredHolder<MapCodec<out Feature>, MapCodec<Rime>> = FEATURE_TYPES.register("rime") { -> Rime.CODEC }
    val CRYSTAL_GROWTH: DeferredHolder<MapCodec<out Feature>, MapCodec<CrystalGrowth>> =
        FEATURE_TYPES.register("crystal_growth") { -> CrystalGrowth.CODEC }

    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        FEATURE_TYPES.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key || event.tabKey == CreativeModeTabs.NATURAL_BLOCKS) {
                for (item in BLOCK_ITEMS) event.accept(item.get())
            }
        }
    }

    /** Whether newly explored land gets its ground cover (the regionTerrain setting). */
    fun enabled(): Boolean = !GuildmarkConfig.SPEC.isLoaded || GuildmarkConfig.REGION_TERRAIN.get()

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }

    private fun cluster(height: Float, width: Float) =
        Function<BlockBehaviour.Properties, GlimmerClusterBlock> { GlimmerClusterBlock(height, width, it) }

    private fun clusterProps(sound: SoundType, light: Int) = props {
        it.mapColor(MapColor.COLOR_PURPLE).forceSolidOn().noOcclusion().sound(sound).strength(1.5f).requiresCorrectToolForDrops()
            .lightLevel { light }.pushReaction(PushReaction.POPPED)
    }
}
