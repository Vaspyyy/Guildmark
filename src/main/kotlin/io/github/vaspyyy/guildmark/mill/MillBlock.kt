package io.github.vaspyyy.guildmark.mill

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/** Which kind of mill a hub turns: a windmill's sails or a watermill's wheel. */
enum class MillKind { SAILS, WHEEL }

/**
 * What a mill's hub is doing, as the server last worked it out (see [Mills]) and every client draws it. [IDLE]: the sails
 * braked with a sail upright, the wheel turning slowly with the stream. [WORKING]: the sails or the wheel turning the
 * millstones. [BROKEN]: a windmill with a sail broken and hanging, braked (a wheel never shows broken).
 */
enum class MillWork(private val id: String) : StringRepresentable {
    IDLE("idle"),
    WORKING("working"),
    BROKEN("broken");

    override fun getSerializedName(): String = id
}

/**
 * A mill's hub: the windmill's sails or the watermill's wheel ([kind]), facing the way the sails or the wheel look out,
 * drawn turning by the game (client/mill) as its [WORK] says. Placed by the capitals' plans, and swapped in for an older
 * capital's block-built sails and wheels (see [MillRefit]); its block entity keeps the capital it grinds for. The block
 * itself is only the axle where it passes through: the sails and the wheel are only drawn, so nothing knocks into them
 * or stands on them.
 */
class MillBlock(properties: Properties, val kind: MillKind) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(WORK, MillWork.IDLE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WORK)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        val alongX = state.getValue(FACING).axis == Direction.Axis.X
        return when (kind) {
            MillKind.SAILS -> if (alongX) SHAFT_X else SHAFT_Z
            MillKind.WHEEL -> if (alongX) NAVE_X else NAVE_Z
        }
    }

    // The block entity's renderer draws all of it
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = MillBlockEntity(pos, state)

    // Only a client ticks one, to ease the sails or the wheel along; the server looks at its mills on its own round (see Mills)
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (!level.isClientSide() || type != MillRegistry.MILL.get()) return null
        return BlockEntityTicker { tickLevel, _, tickState, entity -> (entity as MillBlockEntity).clientTick(tickLevel, tickState) }
    }

    companion object {
        /** The way the sails or the wheel look out: away from the mill. */
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING

        /** What the mill is doing, as the server last worked it out. */
        val WORK: EnumProperty<MillWork> = EnumProperty.create("work", MillWork::class.java)

        // The sails' axle and the wheel's nave where they pass through the hub's block, along the way it faces
        private val SHAFT_Z: VoxelShape = box(4.0, 4.0, 0.0, 12.0, 12.0, 16.0)
        private val SHAFT_X: VoxelShape = box(0.0, 4.0, 4.0, 16.0, 12.0, 12.0)
        private val NAVE_Z: VoxelShape = box(1.0, 1.0, 0.0, 15.0, 15.0, 16.0)
        private val NAVE_X: VoxelShape = box(0.0, 1.0, 1.0, 16.0, 15.0, 15.0)
    }
}
