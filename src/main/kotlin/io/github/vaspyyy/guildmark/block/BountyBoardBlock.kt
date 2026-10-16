package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * The bounty board: an iron-bound notice board the town watch keeps, not the villagers. It posts its own notices (see
 * BountyBoards): the price on the head of every outlaw captain at large in reach, then the great beasts about.
 */
class BountyBoardBlock(properties: BlockBehaviour.Properties) : NoticeBoardBlock(properties) {
    override fun fill(level: Level, anchor: BlockPos, facing: Direction) {
        if (level is ServerLevel) BountyBoards.post(level, anchor, facing, fresh = true)
    }

    override fun tick(level: ServerLevel, anchor: BlockPos, state: BlockState) = BountyBoards.tick(level, anchor, state)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BountyBoardBlockEntity(pos, state)
}

/** One cell of a bounty board and its notice. */
class BountyBoardBlockEntity(pos: BlockPos, state: BlockState) : NoticeBoardBlockEntity(ModBlockEntities.BOUNTY_BOARD.get(), pos, state)
