package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The Plate (living-world/cook/design.md 3.5, 11.1): a flat plate or board on a table that holds one dish, to show or to
 * eat. With a dish in hand, using it lays the dish; with an empty hand, using it eats what is on it (the game's own
 * eating, so it is held down for a second and a half, and let go of early the dish goes back); sneaking with an empty
 * hand takes the dish back into the pack. A plate a part of the town dresses (the cookshop's counter) shows its dish
 * and nothing more. The dish and the rest are [PlateBlockEntity]'s.
 */
class PlateBlock(properties: Properties) : Block(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PlateBlockEntity(pos, state)

    /** A plate lies on something: any block under it will do (a table is a slab, a plank, a fence post, a barrel...). */
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean = !level.getBlockState(pos.below()).isAir

    override fun updateShape(
        state: BlockState, level: LevelReader, ticks: ScheduledTickAccess, pos: BlockPos, direction: Direction,
        neighborPos: BlockPos, neighborState: BlockState, random: RandomSource,
    ): BlockState =
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)) Blocks.AIR.defaultBlockState()
        else super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random)

    /** A dish in hand is laid on the plate if it is bare; anything else is left to the plate's empty-handed use. */
    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val item = stack.item as? DishItem ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val plate = level.getBlockEntity(pos) as? PlateBlockEntity ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val refusal = when {
            item.scorched -> "message.guildmark.cook.plate_ruined"
            plate.forShow -> "message.guildmark.cook.plate_show"
            !plate.dish.isEmpty -> "message.guildmark.cook.plate_occupied"
            else -> null
        }
        if (refusal != null) {
            if (!level.isClientSide) player.sendOverlayMessage(Component.translatable(refusal))
            return InteractionResult.CONSUME
        }
        if (level is ServerLevel) {
            if (!plate.lay(stack)) return InteractionResult.CONSUME
            if (!player.hasInfiniteMaterials()) stack.shrink(1)
            sound(level, pos, 1.0f)
        }
        return InteractionResult.SUCCESS
    }

    /** With an empty hand: eat what is on the plate, or (sneaking) take it back. */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (!player.mainHandItem.isEmpty) return InteractionResult.PASS
        val plate = level.getBlockEntity(pos) as? PlateBlockEntity ?: return InteractionResult.PASS
        if (plate.dish.isEmpty) return InteractionResult.PASS
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        if (plate.forShow) {
            player.sendOverlayMessage(plate.dish.hoverName)
            return InteractionResult.CONSUME
        }
        return if (player.isSecondaryUseActive) takeBack(level, player, plate, pos) else eat(level, player, plate, pos)
    }

    private fun takeBack(level: ServerLevel, player: ServerPlayer, plate: PlateBlockEntity, pos: BlockPos): InteractionResult {
        val portion = plate.take(level)
        if (portion.isEmpty) return InteractionResult.PASS
        player.inventory.placeItemBackInInventory(portion, Prediction.SERVER_ONLY)
        sound(level, pos, 0.85f)
        return InteractionResult.SUCCESS
    }

    /**
     * Lifts the dish into the empty hand and starts the game's own eating of it, so that everything that belongs to
     * eating a dish (its food at how it has kept, the talents' extra on it, the bowl back, the effect of the meal, the
     * pose and the mouthful's sound) is exactly what eating it from the pack does. It is the game that says whether the
     * player may eat (a dish with a meal may be eaten with the food bar full, a sweet one waits for an appetite): if it
     * will not have it, the dish goes back and they are told. Once the meal has begun the pack is sent to the player at
     * once (the call the game makes to put a hand right): in a tick the "using" flag leaves with the entity data, ahead
     * of the player's own inventory, and a client that had the flag before the dish in its hand would not raise the arm.
     */
    private fun eat(level: ServerLevel, player: ServerPlayer, plate: PlateBlockEntity, pos: BlockPos): InteractionResult {
        val portion = plate.take(level)
        if (portion.isEmpty) return InteractionResult.PASS
        player.setItemInHand(InteractionHand.MAIN_HAND, portion)
        portion.use(level, player, InteractionHand.MAIN_HAND)
        if (!player.isUsingItem) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
            plate.lay(portion)
            player.sendOverlayMessage(Component.translatable("message.guildmark.cook.plate_sated"))
            return InteractionResult.CONSUME
        }
        PlateEating.begin(player, level, pos)
        player.inventoryMenu.sendAllDataToRemote()
        return InteractionResult.SUCCESS
    }

    private fun sound(level: ServerLevel, pos: BlockPos, pitch: Float) {
        level.playSound(null, pos, CookSounds.get("cook.plate"), SoundSource.BLOCKS, 0.7f, pitch + level.random.nextFloat() * 0.1f)
    }

    companion object {
        private val SHAPE: VoxelShape = Block.box(2.0, 0.0, 2.0, 14.0, 2.0, 14.0)

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.TERRACOTTA_WHITE).strength(0.5f).sound(SoundType.STONE).noOcclusion() }
    }
}
