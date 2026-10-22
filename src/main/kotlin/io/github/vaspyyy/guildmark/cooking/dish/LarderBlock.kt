package io.github.vaspyyy.guildmark.cooking.dish

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult
import java.util.function.UnaryOperator

/**
 * The Larder (living-world/cook/design.md 1.1, 3.2, 11.1): a slatted cupboard on feet with a door, 18 slots, that takes
 * dishes only and keeps them twice as long (the days are [LarderBlockEntity]'s). Using it opens the chest screen; its
 * door is open for as long as anyone has it open (the barrel's counter, and the sound of it), and a comparator reads
 * how full it is. A cookshop's Larder (Part H) is the same block over the shop's stock: it opens for the shop's owner
 * and their party only, and breaking it drops nothing.
 */
class LarderBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(OPEN, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(OPEN)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = LarderBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val larder = level.getBlockEntity(pos) as? LarderBlockEntity ?: return InteractionResult.PASS
        if (!larder.mayOpen(player)) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.cook.larder_owned"))
            return InteractionResult.SUCCESS
        }
        if (!LarderMenus.registered) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.cook.larder_stuck"))
            return InteractionResult.SUCCESS
        }
        player.openMenu(larder)
        return InteractionResult.SUCCESS
    }

    /** The door shuts when the last person who had it open has walked away or closed it (the barrel's scheduled look). */
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        (level.getBlockEntity(pos) as? LarderBlockEntity)?.recheckOpen()
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        Containers.updateNeighboursAfterDestroy(state, level, pos)
    }

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos, direction: Direction): Int =
        AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos))

    companion object {
        /** Whether a player has the door open. */
        val OPEN: BooleanProperty = BlockStateProperties.OPEN

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD) }
    }
}
