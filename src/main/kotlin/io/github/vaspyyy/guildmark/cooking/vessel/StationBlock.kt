package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookSounds
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * The base of the cooking stations' blocks (living-world/cook/design.md 1.1, 12.4 Part B): the Stewpot, the Hob, the Bake
 * oven, the Spit and the Prep Board, each with a [StationBlockEntity] that holds its vessel (or, for the board, its heap).
 * A click goes to the block entity whole: it finds the region of the station that the click landed on (the vessel above,
 * the fire below, the heat control at the side: [StationRegions]) and does what the item in the hand and the region ask.
 * The client takes the click too (it never lets the game go on to use the item on the world), and the server decides what it
 * came to.
 */
abstract class StationBlock(properties: Properties) : Block(properties), EntityBlock {
    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val use = level.getBlockEntity(pos) as? StationUse ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide()) return if (use.predicts(player, hand, stack, hit)) InteractionResult.SUCCESS else InteractionResult.CONSUME
        if (player !is ServerPlayer) return InteractionResult.PASS
        return use.interact(player, hand, stack, hit)
    }

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide()) return null
        return BlockEntityTicker { tickLevel, _, _, entity -> if (tickLevel is ServerLevel && entity is StationUse) entity.serverTick(tickLevel) }
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, by: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, by, stack)
        if (level.isClientSide()) return
        val entity = level.getBlockEntity(pos)
        if (by is ServerPlayer && entity is VesselBlockEntity) entity.placer = by.uuid
        level.playSound(null, pos, CookSounds.get("cook.place"), SoundSource.BLOCKS, 1.0f, 1.0f)
    }
}
