package io.github.vaspyyy.guildmark.island

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A lighthouse's great lens in its brass frame. It lights itself at dusk and in rain or a storm, and puts itself out at
 * dawn once the weather clears; lit, it throws its two beams out over the sea, turning all night (LighthouseLampRenderer
 * draws them), and the lighthouse turns its lamp and sounds its horn (see IslandAmbience).
 */
class LighthouseLampBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(LIT, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LIT)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = LighthouseLampBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (type != IslandRegistry.LIGHTHOUSE_LAMP_ENTITY.get()) return null
        // A client goes by its own sky for the beams: the lamp's state only changes while its chunk is near a player,
        // and from far out at sea the beams still have to come on at dusk
        return if (level.isClientSide()) BlockEntityTicker { tickLevel, _, _, entity -> (entity as LighthouseLampBlockEntity).clientTick(wantsLight(tickLevel)) }
        else BlockEntityTicker { tickLevel, pos, s, _ -> if (tickLevel is ServerLevel) serverTick(tickLevel, pos, s) }
    }

    companion object {
        val LIT: BooleanProperty = BooleanProperty.create("lit")

        private val SHAPE: VoxelShape = box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0)

        /** Ticks between looks at the sky. */
        private const val CHECK = 40L

        /** A lamp burns from dusk to dawn, and through rain and storms. */
        fun wantsLight(level: Level): Boolean = level.isDarkOutside || level.isRaining || level.isThundering

        private fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
            if (Math.floorMod(level.gameTime + pos.asLong(), CHECK) != 0L) return
            val want = wantsLight(level)
            if (state.getValue(LIT) != want) level.setBlock(pos, state.setValue(LIT, want), Block.UPDATE_ALL)
        }
    }
}

/** A lighthouse lamp's block entity: nothing kept, only how bright its beams are just now on a client, easing as it lights and goes out. */
class LighthouseLampBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(IslandRegistry.LIGHTHOUSE_LAMP_ENTITY.get(), pos, state) {
    var glowO = 0f
        private set
    var glow = 0f
        private set

    fun clientTick(lit: Boolean) {
        glowO = glow
        val target = if (lit) 1f else 0f
        // It warms up slowly as the wick takes, and dies quicker
        glow += (target - glow) * if (target > glow) 0.02f else 0.05f
        if (kotlin.math.abs(target - glow) < 0.001f) glow = target
    }
}
