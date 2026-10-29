package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlockEntity
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks
import io.github.vaspyyy.guildmark.economy.works.build.WorksFacingBlock
import io.github.vaspyyy.guildmark.economy.works.build.TradeMarks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * The Carriers' Store's fittings as blocks (living-world/trader/design.md, Part 5; build part F2): the clerk's scale on its bench, the
 * name board over the door with the owner's pennant on the pole beside it, and the open and shut board by the door. Their ids,
 * models and blockstates are art stream X3's (`art/scripts/store_art.py`), each facing north in its model and turned by `facing`; the
 * renderers that move them are `client/trade` ([io.github.vaspyyy.guildmark.client.trade.TradeBlocksClient]). Like the works' own blocks
 * they have no item, drop nothing and cannot be broken by a player: the builders lay them (the drawing's markers, `TownWorksSite`) and
 * they go when the works does ([TradeMarks] are their markers). Registered from [Trade.register] (the F slot), on the pattern of
 * [io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks].
 */
object TradeBlocks {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)

    /** The clerk's scale: an oak bench with a post, its beam and its two pans (two blocks high: the block above stays free). */
    val SCALE: DeferredBlock<StoreScaleBlock> = BLOCKS.registerBlock("store_scale", ::StoreScaleBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion() })

    /** The Store's name board, on its bracket over the door. */
    val STORE_BOARD: DeferredBlock<NameBoardBlock> =
        BLOCKS.registerBlock("store_name_board", ::NameBoardBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion().noCollision() })

    /** The open and shut board by the door. */
    val DOOR_BOARD: DeferredBlock<DoorBoardBlock> =
        BLOCKS.registerBlock("store_door_board", ::DoorBoardBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion().noCollision() })

    val SCALE_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<StoreScaleBlockEntity>> =
        BLOCK_ENTITIES.register("store_scale") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::StoreScaleBlockEntity), SCALE.get()) }
    val BOARD_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<NameBoardBlockEntity>> =
        BLOCK_ENTITIES.register("trade_name_board") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::NameBoardBlockEntity), STORE_BOARD.get()) }
    val DOOR_BOARD_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<DoorBoardBlockEntity>> =
        BLOCK_ENTITIES.register("store_door_board") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::DoorBoardBlockEntity), DOOR_BOARD.get()) }

    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        BLOCK_ENTITIES.register(modBus)
    }

    /** The properties of a works' fitting: no loot, never pushed, and unbreakable but by the works' own coming and going. */
    private fun fixed(color: MapColor, sound: SoundType, more: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> {
        more(it.mapColor(color).strength(-1f, 3_600_000f).noLootTable().sound(sound).pushReaction(PushReaction.IMMOVEABLE).isValidSpawn { _, _, _, _ -> false })
    }

    /**
     * The fitting a drawing's marker on [m] stands for in a works of [kind] (economy/works/build/TradeDrawings' [TradeMarks]), or null
     * for none of the trader's.
     */
    fun marked(m: M, kind: WorksKind): Block? = when (m) {
        M.GRINDSTONE -> SCALE.get()
        M.BANNER -> if (kind == WorksKind.STORE) STORE_BOARD.get() else null
        M.SHUTTER -> DOOR_BOARD.get()
        else -> null
    }

    /** Hears a player using one of the Store's fittings (the scale: the clerk's desk); set by the Store's desk, nothing until then. */
    internal var used: (ServerLevel, ServerPlayer, BlockPos) -> Boolean = { _, _, _ -> false }
}

/** The clerk's scale on its bench: using it is using the clerk's desk. */
class StoreScaleBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = StoreScaleBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        return if (TradeBlocks.used(level, player, pos)) InteractionResult.SUCCESS else InteractionResult.PASS
    }

    companion object {
        // X3's model facing north: the bench (its top at 12 px) across the block, the post and the beam over it
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(0.5, 0.0, 2.0, 15.5, 12.0, 14.0))
    }
}

/**
 * The clerk's scale at work: a sack set on the goods pan at [weighed] (game time; 0 never), the three weights set on the other pan one
 * after another by the clock (client/trade's StoreScaleRenderer), and the sack taken off again at [cleared] (0 or before [weighed]: not
 * yet; the renderer takes it off by itself after a while, so a weighing broken off never leaves the beam down).
 */
class StoreScaleBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(TradeBlocks.SCALE_ENTITY.get(), pos, state) {
    var weighed: Long = 0L
        private set
    var cleared: Long = 0L
        private set

    /** A sack set down on the goods pan now. */
    fun weigh() {
        weighed = level?.gameTime ?: 0L
        cleared = 0L
        WorksBlocks.sync(this)
    }

    /** The sack taken off now. */
    fun clear() {
        if (weighed == 0L || cleared > weighed) return
        cleared = level?.gameTime ?: 0L
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putLong("weighed", weighed)
        output.putLong("cleared", cleared)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        weighed = input.getLongOr("weighed", 0L)
        cleared = input.getLongOr("cleared", 0L)
    }
}

/** A trader's name board on its bracket (the Store's, the House's): its lettering and its swing are the renderer's. */
class NameBoardBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = NameBoardBlockEntity(pos, state)

    companion object {
        // The plate on the wall, the arm out from it and the board hanging under the crossbar
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(0.0, 4.0, 2.3, 16.0, 16.0, 16.0))
    }
}

/**
 * What a name board shows: [name] (its owner's, lettered on both faces) and [color] (their dye id); and, for a Store's, its owner's
 * pennant on the pole at [pole] (the pole's foot, null for none), [top] blocks up it, flying toward [fly]: run up it at [raised]
 * (game time; 0, not yet: furled at the foot).
 */
class NameBoardBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(TradeBlocks.BOARD_ENTITY.get(), pos, state) {
    var name: String = ""
        private set
    var color: Int = 14
        private set
    var pole: BlockPos? = null
        private set
    var top: Int = 0
        private set
    var fly: Direction = Direction.WEST
        private set
    var raised: Long = 0L
        private set

    /** The renderer's own store of what it worked out from [name] (its lettering), kept with the board it is for. */
    @JvmField
    var words: Any? = null

    /** The board's owner and colour; true if that changed anything. */
    fun set(name: String, color: Int): Boolean {
        if (this.name == name && this.color == color) return false
        this.name = name
        this.color = color
        WorksBlocks.sync(this)
        return true
    }

    /** The pole the owner's pennant flies from. */
    fun pole(pole: BlockPos?, top: Int, fly: Direction) {
        if (this.pole == pole && this.top == top && this.fly == fly) return
        this.pole = pole
        this.top = top
        this.fly = fly
        WorksBlocks.sync(this)
    }

    /** The pennant run up its pole now (or, [now] false, as if long since: there already when first seen). */
    fun raise(now: Boolean) {
        raised = if (now) level?.gameTime ?: 1L else 1L
        WorksBlocks.sync(this)
    }

    /** The pennant taken down (furled at the pole's foot). */
    fun lower() {
        if (raised == 0L) return
        raised = 0L
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("name", name)
        output.putInt("color", color)
        pole?.let { output.putLong("pole", it.asLong()) }
        output.putInt("top", top)
        output.putInt("fly", fly.get2DDataValue())
        output.putLong("raised", raised)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        name = input.getStringOr("name", "")
        color = input.getIntOr("color", 14)
        pole = input.getLong("pole").map { BlockPos.of(it) }.orElse(null)
        top = input.getIntOr("top", 0)
        fly = Direction.from2DDataValue(input.getIntOr("fly", Direction.WEST.get2DDataValue()))
        raised = input.getLongOr("raised", 0L)
    }
}

/** The little open and shut board on its hook by the door. */
class DoorBoardBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = DoorBoardBlockEntity(pos, state)

    companion object {
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(5.5, 7.0, 12.0, 10.5, 14.0, 16.0))
    }
}

/** Which face the door board shows ([open]: OPEN, else SHUT), turned round to it at [turned] (game time; 0: always so). */
class DoorBoardBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(TradeBlocks.DOOR_BOARD_ENTITY.get(), pos, state) {
    var open: Boolean = false
        private set
    var turned: Long = 0L
        private set

    /** Turned to show [open] (now, or with [show] false at once, as if long since); true if it changed. */
    fun turn(open: Boolean, show: Boolean): Boolean {
        if (this.open == open) return false
        this.open = open
        turned = if (show) level?.gameTime ?: 0L else 0L
        WorksBlocks.sync(this)
        return true
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putBoolean("open", open)
        output.putLong("turned", turned)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        open = input.getBooleanOr("open", false)
        turned = input.getLongOr("turned", 0L)
    }
}
