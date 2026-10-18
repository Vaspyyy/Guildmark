package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/** What the builders take as each paid material, and how many units one of it is worth. */
object Materials {
    fun of(stack: ItemStack): Pair<Stuff, Int>? = when {
        stack.isEmpty -> null
        stack.`is`(ItemTags.LOGS) -> Stuff.TIMBER to 4
        stack.`is`(ItemTags.PLANKS) -> Stuff.TIMBER to 1
        stack.`is`(HomesteadRegistry.STONE) -> Stuff.STONE to 1
        stack.`is`(HomesteadRegistry.GLASS) -> Stuff.GLASS to 1
        stack.`is`(HomesteadRegistry.IRON_BLOCKS) -> Stuff.IRON to 9
        stack.`is`(HomesteadRegistry.IRON) -> Stuff.IRON to 1
        stack.`is`(ItemTags.WOOL) -> Stuff.WOOL to 1
        else -> null
    }

    /** Units of each paid material in [container] ([Stuff.PAID] order). */
    fun count(container: Container): IntArray {
        val n = IntArray(Stuff.PAID.size)
        for (i in 0 until container.containerSize) {
            val stack = container.getItem(i)
            val (stuff, units) = of(stack) ?: continue
            n[stuff.ordinal] += units * stack.count
        }
        return n
    }

    /**
     * Takes one item of [stuff] out of [container], the cheapest that will do (a plank before a log), and returns the
     * units it was worth, or 0 if there's none.
     */
    fun take(container: Container, stuff: Stuff): Int {
        var best = -1
        var bestUnits = Int.MAX_VALUE
        for (i in 0 until container.containerSize) {
            val (s, units) = of(container.getItem(i)) ?: continue
            if (s == stuff && units < bestUnits) {
                best = i
                bestUnits = units
            }
        }
        if (best < 0) return 0
        container.removeItem(best, 1)
        container.setChanged()
        return bestUnits
    }
}

/**
 * The Charter Post at a homestead's gate. Its owner's pennant flies from it and a lantern hangs from its arm
 * (CharterPostRenderer); using it opens the homestead's ledger to its owner and their party, and tells anyone else
 * whose land this is. It can't be broken: it goes when the charter is given up.
 */
class CharterPostBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CharterPostBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) HomesteadLedger.openAt(level, player, pos)
        return InteractionResult.SUCCESS
    }

    companion object {
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING
        private val SHAPE: VoxelShape = box(5.0, 0.0, 5.0, 11.0, 16.0, 11.0)
    }
}

/** The post's colour and owner, which its pennant and plaque show. */
class CharterPostBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(HomesteadRegistry.CHARTER_POST_ENTITY.get(), pos, state) {
    var color: Int = 14
        private set
    var owner: String = ""
        private set
    var tier: Int = 0
        private set

    fun set(color: Int, owner: String, tier: Int) {
        if (this.color == color && this.owner == owner && this.tier == tier) return
        this.color = color
        this.owner = owner
        this.tier = tier
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("color", color)
        output.putString("owner", owner)
        output.putInt("tier", tier)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        color = input.getIntOr("color", 14)
        owner = input.getStringOr("owner", "")
        tier = input.getIntOr("tier", 0)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)
}

/**
 * A crate that only takes one sort of thing: the supply crate by a homestead's post (what the builders build with)
 * or a shipping crate in its yard (the farm's goods). Only the homestead's people may open either.
 */
class GoodsCrateBlock(properties: Properties, val kind: Kind) : Block(properties), EntityBlock {
    enum class Kind(val key: String) {
        SUPPLY("supply_crate"), SHIPPING("shipping_crate");

        fun accepts(stack: ItemStack): Boolean = when (this) {
            SUPPLY -> Materials.of(stack) != null
            SHIPPING -> stack.`is`(HomesteadRegistry.GOODS)
        }

        fun menu(): MenuType<GoodsMenu> = when (this) {
            SUPPLY -> HomesteadRegistry.SUPPLY_MENU.get()
            SHIPPING -> HomesteadRegistry.SHIPPING_MENU.get()
        }
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = GoodsCrateBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        if (!HomesteadProtection.mayUse(level, player, pos)) {
            HomesteadProtection.refuse(level, player, pos, "crate")
            return InteractionResult.SUCCESS
        }
        val crate = level.getBlockEntity(pos) as? GoodsCrateBlockEntity ?: return InteractionResult.SUCCESS
        player.openMenu(crate)
        level.playSound(null, pos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.6f, 0.9f + level.random.nextFloat() * 0.2f)
        return InteractionResult.SUCCESS
    }

    companion object {
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING
    }
}

/** A goods crate's 27 slots, which take only what its [GoodsCrateBlock.Kind] allows (hoppers included). */
class GoodsCrateBlockEntity(pos: BlockPos, state: BlockState) : BaseContainerBlockEntity(HomesteadRegistry.GOODS_CRATE_ENTITY.get(), pos, state) {
    private var items: NonNullList<ItemStack> = NonNullList.withSize(SIZE, ItemStack.EMPTY)

    val kind: GoodsCrateBlock.Kind get() = (blockState.block as? GoodsCrateBlock)?.kind ?: GoodsCrateBlock.Kind.SHIPPING

    override fun getContainerSize(): Int = SIZE
    override fun getItems(): NonNullList<ItemStack> = items
    override fun setItems(items: NonNullList<ItemStack>) {
        this.items = items
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = kind.accepts(stack)

    override fun getDefaultName(): Component = Component.translatable("container.guildmark.${kind.key}")

    override fun createMenu(id: Int, inventory: Inventory): AbstractContainerMenu = GoodsMenu(kind.menu(), id, inventory, this, kind)

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        net.minecraft.world.ContainerHelper.saveAllItems(output, items)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY)
        net.minecraft.world.ContainerHelper.loadAllItems(input, items)
    }

    companion object {
        const val SIZE = 27
    }
}

/** A chest's menu over a goods crate, its slots taking only what the crate takes. */
class GoodsMenu(type: MenuType<*>, id: Int, inventory: Inventory, container: Container, val kind: GoodsCrateBlock.Kind) :
    ChestMenu(type, id, inventory, container, 3) {
    init {
        for (i in 0 until GoodsCrateBlockEntity.SIZE) {
            val old = slots[i]
            val slot = object : Slot(container, i, old.x, old.y) {
                override fun mayPlace(stack: ItemStack): Boolean = kind.accepts(stack)
            }
            slot.index = i
            slots[i] = slot
        }
    }

    companion object {
        fun client(kind: GoodsCrateBlock.Kind, id: Int, inventory: Inventory): GoodsMenu =
            GoodsMenu(kind.menu(), id, inventory, SimpleContainer(GoodsCrateBlockEntity.SIZE), kind)
    }
}
