package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.ArcherPostBlock
import io.github.vaspyyy.guildmark.block.BountyBoardBlock
import io.github.vaspyyy.guildmark.block.MarketBoardBlock
import io.github.vaspyyy.guildmark.block.MistGateBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.WardfireBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

object ModBlocks {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    val QUEST_BOARD: DeferredBlock<QuestBoardBlock> = BLOCKS.registerBlock("quest_board", ::QuestBoardBlock, UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.IMMOVEABLE)
    })

    /** The town watch's iron-bound board of wanted notices; villages with outlaws about put one up, and it can be crafted. */
    val BOUNTY_BOARD: DeferredBlock<BountyBoardBlock> = BLOCKS.registerBlock("bounty_board", ::BountyBoardBlock, UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.COLOR_BROWN).strength(3.0f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.IMMOVEABLE)
    })

    /** A capital's market board in its market square, where the crier chalks up the prices and pins up the news. */
    val MARKET_BOARD: DeferredBlock<MarketBoardBlock> = BLOCKS.registerBlock("market_board", ::MarketBoardBlock, UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.COLOR_BLACK).strength(2.5f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.IMMOVEABLE)
    })

    /** Rest points out in the world. Found, never crafted, and can't be mined (creative players can break them). */
    val WARDFIRE: DeferredBlock<WardfireBlock> = BLOCKS.registerBlock("wardfire", ::WardfireBlock, UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.STONE).instrument(NoteBlockInstrument.BASEDRUM).strength(-1.0f, 3600000.0f).noLootTable()
            .sound(SoundType.STONE).noOcclusion().pushReaction(PushReaction.IMMOVEABLE).lightLevel(WardfireBlock::light)
    })

    /** Across a dungeon boss hall's door; placed by dungeons, can't be mined, glows faintly in the dark. */
    val MIST_GATE: DeferredBlock<MistGateBlock> = BLOCKS.registerBlock("mist_gate", ::MistGateBlock, UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.SNOW).strength(-1.0f, 3600000.0f).noLootTable().sound(SoundType.WOOL).noOcclusion()
            .pushReaction(PushReaction.IMMOVEABLE).lightLevel { 7 }.emissiveRendering { true }
            .isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }.isRedstoneConductor { _, _, _ -> false }
    })

    /** Placed by the Archer Tower advance; not craftable. */
    val ARCHER_POST: DeferredBlock<ArcherPostBlock> = BLOCKS.registerBlock("archer_post", ::ArcherPostBlock, UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD)
    })
}
