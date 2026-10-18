package io.github.vaspyyy.guildmark.lamps

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.neoforge.common.ItemAbilities
import net.neoforged.neoforge.common.ItemAbility

/**
 * A capital's street lamp: a lantern standing on its post, lit at dusk and put out at dawn ([StreetLamps]) by the
 * town's lamplighters on their rounds, or by the clock where nobody walks them. Lit, it is the game's own lantern (or
 * soul lantern, [soul]) and gives the same light; out, its glass is dark and its flame gone, and it gives none.
 * Just lit, the flame takes a moment to grow ([SETTLING]: dim, then bright); just put out, the wick glows on a moment
 * and smokes. A player lights one with flint and steel (or a fire charge) and puts one out by hand. Broken, it gives
 * the lantern it was; picked in creative, a lantern too. The out and settling looks are drawn over the game's own
 * lantern model by art/scripts/lamp_art.py (blockstates/street_lamp.json lays them on).
 */
class StreetLampBlock(properties: Properties, val soul: Boolean) : Block(properties), SimpleWaterloggedBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(LIT, true).setValue(SETTLING, false).setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LIT, SETTLING, WATERLOGGED)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val state = defaultBlockState().setValue(WATERLOGGED, context.level.getFluidState(context.clickedPos).`is`(Fluids.WATER))
        return state.takeIf { it.canSurvive(context.level, context.clickedPos) }
    }

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean = canSupportCenter(level, pos.below(), Direction.UP)

    override fun updateShape(
        state: BlockState, level: LevelReader, ticks: ScheduledTickAccess, pos: BlockPos, directionToNeighbour: Direction,
        neighbourPos: BlockPos, neighbourState: BlockState, random: RandomSource,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        return if (directionToNeighbour == Direction.DOWN && !state.canSurvive(level, pos)) Blocks.AIR.defaultBlockState()
        else super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
    }

    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    // A flame just caught or a wick just put out settles a moment later
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        if (state.getValue(SETTLING) && state != oldState) level.scheduleTick(pos, this, if (state.getValue(LIT)) CATCH_TICKS else DIE_TICKS)
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (!state.getValue(SETTLING)) return
        level.setBlock(pos, state.setValue(SETTLING, false), Block.UPDATE_ALL)
        // The last of the wick's smoke
        if (!state.getValue(LIT)) level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 0.55, pos.z + 0.5, 2, 0.04, 0.05, 0.04, 0.005)
    }

    /** Put out by hand (with nothing in it), as a candle is. */
    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (!stack.isEmpty || !state.getValue(LIT) || !player.abilities.mayBuild) return super.useItemOn(stack, state, level, pos, player, hand, hit)
        if (level is ServerLevel) {
            putOut(level, pos, show = true, volume = 0.7f)
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        }
        return InteractionResult.SUCCESS
    }

    /** Lit with flint and steel or a fire charge (which sound and spend themselves): it catches as a lamplighter's does. */
    override fun getToolModifiedState(state: BlockState, context: UseOnContext, itemAbility: ItemAbility, simulate: Boolean): BlockState? {
        if (itemAbility != ItemAbilities.FIRESTARTER_LIGHT || state.getValue(LIT) || state.getValue(WATERLOGGED)) {
            return super.getToolModifiedState(state, context, itemAbility, simulate)
        }
        val level = context.level
        if (!simulate && level is ServerLevel) catchEffects(level, context.clickedPos, 0.5f)
        return state.setValue(LIT, true).setValue(SETTLING, true)
    }

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean, player: Player): ItemStack =
        ItemStack(if (soul) Items.SOUL_LANTERN else Items.LANTERN)

    companion object {
        val LIT: BooleanProperty = BlockStateProperties.LIT

        /** A flame just caught (lit: small and dim yet) or a wick just put out (not lit: glowing, smoking). */
        val SETTLING: BooleanProperty = BooleanProperty.create("settling")
        val WATERLOGGED: BooleanProperty = BlockStateProperties.WATERLOGGED

        /** Ticks a flame just caught takes to grow to its full height, and a wick just put out glows on. */
        const val CATCH_TICKS = 6
        const val DIE_TICKS = 16

        private val SHAPE: VoxelShape = Shapes.or(Block.column(4.0, 7.0, 9.0), Block.column(6.0, 0.0, 7.0))

        /** The light a lamp gives: a lantern's lit (a soul lantern's for [soul]), less while it settles, none out. */
        fun brightness(state: BlockState, soul: Boolean): Int {
            val lit = state.getValue(LIT)
            val settling = state.getValue(SETTLING)
            return when {
                lit && !settling -> if (soul) 10 else 15
                lit -> if (soul) 6 else 9
                settling -> if (soul) 2 else 3
                else -> 0
            }
        }

        fun isLamp(state: BlockState): Boolean = state.block is StreetLampBlock

        /** Whether [state] is a street lamp burning (or catching). */
        fun burning(state: BlockState): Boolean = isLamp(state) && state.getValue(LIT)

        /**
         * Lights the lamp at [pos] if it's out: it catches, a moment dim and then bright. [show]: with a soft whoomp and a
         * little rush of flame, for a lamp catching by itself in sight of a player (a lamplighter's makes its own, see
         * LivingMoments). False if there's no lamp there or it was burning already.
         */
        fun light(level: ServerLevel, pos: BlockPos, show: Boolean): Boolean {
            val state = level.getBlockState(pos)
            if (!isLamp(state) || state.getValue(LIT)) return false
            level.setBlock(pos, state.setValue(LIT, true).setValue(SETTLING, true), Block.UPDATE_ALL)
            if (show) catchEffects(level, pos, 0.35f)
            return true
        }

        /**
         * Puts out the lamp at [pos] if it's burning: its wick glows a moment and smokes. [show]: with the flame's hiss
         * ([volume]) and a wisp of smoke from its top, for a lamp going out by itself in sight of a player or put out by
         * hand (a lamplighter's snuffer makes its own, see LivingMoments). False if there's no lamp or it was out.
         */
        fun putOut(level: ServerLevel, pos: BlockPos, show: Boolean, volume: Float = 0.3f): Boolean {
            val state = level.getBlockState(pos)
            if (!isLamp(state) || !state.getValue(LIT)) return false
            level.setBlock(pos, state.setValue(LIT, false).setValue(SETTLING, true), Block.UPDATE_ALL)
            if (show) {
                level.playSound(null, pos.x + 0.5, pos.y + 0.35, pos.z + 0.5, HISS_SOUND, SoundSource.BLOCKS, volume, 0.95f + level.random.nextFloat() * 0.1f)
                level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 4, 0.06, 0.08, 0.06, 0.008)
            }
            return true
        }

        /** Sets the lamp at [pos] lit or out at once, settled, nothing to see: for lamps out of sight and newly loaded. */
        fun set(level: ServerLevel, pos: BlockPos, lit: Boolean): Boolean {
            val state = level.getBlockState(pos)
            if (!isLamp(state) || state.getValue(LIT) == lit && !state.getValue(SETTLING)) return false
            level.setBlock(pos, state.setValue(LIT, lit).setValue(SETTLING, false), Block.UPDATE_ALL)
            return true
        }

        /** The flame catching: its sound and a little rush of flame up out of the lantern. */
        private fun catchEffects(level: ServerLevel, pos: BlockPos, volume: Float) {
            level.playSound(null, pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, LIGHT_SOUND, SoundSource.BLOCKS, volume, 0.95f + level.random.nextFloat() * 0.1f)
            level.sendParticles(ParticleTypes.FLAME, pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, 3, 0.05, 0.04, 0.05, 0.008)
            level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 0.55, pos.z + 0.5, 2, 0.04, 0.03, 0.04, 0.006)
        }

        private val LIGHT_SOUND: Holder<SoundEvent> =
            Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.lamp.light")))
        private val HISS_SOUND: Holder<SoundEvent> =
            Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.lamp.hiss")))
    }
}
