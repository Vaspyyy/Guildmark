package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.festival.FestivalRestores
import io.github.vaspyyy.guildmark.festival.Festivities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** Which of the alms table's three blocks this is, from the keeper's side: its left end, its middle (the pot's) or its right end. */
enum class TablePart(private val id: String) : StringRepresentable {
    LEFT("left"),
    MIDDLE("middle"),
    RIGHT("right");

    override fun getSerializedName(): String = id

    /** Blocks along from the middle (the way the keeper's right hand is: see [AlmsTableBlock.along]). */
    val offset: Int get() = ordinal - 1
}

/**
 * The alms table (M6 Part D2, see [AlmsTable]): a trestle three blocks long, its front toward [FACING] (the hungry
 * line up straight out from it, the keeper stands behind it), set up by the market's well on a hungry day and taken
 * down again in the afternoon. Its middle holds the soup pot and the bowls ([AlmsTableBlockEntity], drawn by
 * client/streets/StreetsClient), steaming while there's soup in it. A player with food in hand gives it at the table
 * (the whole stack while sneaking); with none, the keeper has a word for them. It can't be broken or moved, has no
 * item, and is put back to what stood there as it comes down ([FestivalRestores.Restorable]); a stray one (after a
 * crash, say) goes on its next random tick.
 */
class AlmsTableBlock(properties: Properties) : Block(properties), EntityBlock, FestivalRestores.Restorable {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, TablePart.MIDDLE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, PART)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(PART)).getValue(state.getValue(FACING))

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SOLID.getValue(state.getValue(PART)).getValue(state.getValue(FACING))

    override fun propagatesSkylightDown(state: BlockState): Boolean = true

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(PART) == TablePart.MIDDLE) AlmsTableBlockEntity(pos, state) else null

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        // The client's half only: the pot's simmer, heard near it (client/streets)
        if (!level.isClientSide() || state.getValue(PART) != TablePart.MIDDLE || type != StreetsRegistry.ALMS_TABLE_ENTITY.get()) return null
        return BlockEntityTicker { _, _, _, entity -> AlmsTableBlockEntity.clientTick?.invoke(entity as AlmsTableBlockEntity) }
    }

    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hitResult: BlockHitResult,
    ): InteractionResult {
        // A cook's dish is not a Good: it has its own way in, counted by the kitchens' ledger (Hearthcraft, AlmsTable.giveDish)
        if (stack.item is DishItem && Cooking.enabled()) {
            if (level is ServerLevel && player is ServerPlayer) AlmsTable.giveDish(level, middle(pos, state), player, hand)
            return InteractionResult.SUCCESS
        }
        val food = Good.of(stack.item)?.takeIf { it.food > 0.0 } ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level is ServerLevel && player is ServerPlayer) AlmsTable.give(level, middle(pos, state), player, hand, food)
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) AlmsTable.spoken(level, middle(pos, state), player)
        return InteractionResult.SUCCESS
    }

    // Nobody wants it any more (left from a crash, say): put back as it was (see Festivities.checkPiece)
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) = Festivities.checkPiece(level, pos)

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack = ItemStack.EMPTY

    /** Steam off the pot while there's soup in it. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (state.getValue(PART) != TablePart.MIDDLE) return
        val table = level.getBlockEntity(pos) as? AlmsTableBlockEntity ?: return
        if (!table.pot || table.soup <= 0) return
        val full = table.soup.toFloat() / table.cap.coerceAtLeast(1)
        val at = pot(state.getValue(FACING), pos)
        for (k in 0 until 1 + random.nextInt(2)) {
            if (random.nextFloat() > 0.45f + 0.4f * full) continue
            val x = at.x + (random.nextDouble() - 0.5) * 0.28
            val z = at.z + (random.nextDouble() - 0.5) * 0.28
            level.addParticle(ParticleTypes.WHITE_SMOKE, x, at.y + 0.05, z, (random.nextDouble() - 0.5) * 0.004, 0.018 + random.nextDouble() * 0.012, (random.nextDouble() - 0.5) * 0.004)
        }
    }

    companion object {
        /** The way its front faces (to the hungry), and which of its blocks this is. */
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING
        val PART: EnumProperty<TablePart> = EnumProperty.create("part", TablePart::class.java)

        /** The table top's height, and the pot's rim (blocks over the table's own block). */
        const val TOP = 13.0 / 16.0
        const val POT_RIM = 18.0 / 16.0

        /** The pot's middle on the middle block, px, as the trestle's model has it facing north (client/works/WorksArt's POT_X and POT_Z): on the keeper's right, toward them. */
        private const val POT_X = 11.5
        private const val POT_Z = 11.0

        /** The way along the table to its keeper's right, for a table whose front faces [front]. */
        fun along(front: Direction): Direction = front.clockWise

        /** Over the middle of the pot's rim on the middle block at [pos] of a table whose front faces [front] (its steam and its simmer come from here). */
        fun pot(front: Direction, pos: BlockPos): Vec3 {
            val right = along(front)
            val back = front.opposite
            val dx = (POT_X - 8.0) / 16.0
            val dz = (POT_Z - 8.0) / 16.0
            return Vec3(pos.x + 0.5 + right.stepX * dx + back.stepX * dz, pos.y + POT_RIM, pos.z + 0.5 + right.stepZ * dx + back.stepZ * dz)
        }

        /** The middle block of the table whose [part] at [pos] faces [state]'s way. */
        fun middle(pos: BlockPos, state: BlockState): BlockPos {
            if (state.block !is AlmsTableBlock) return pos
            return pos.relative(along(state.getValue(FACING)), -state.getValue(PART).offset)
        }

        /** One of its blocks' states. */
        fun state(front: Direction, part: TablePart): BlockState =
            StreetsRegistry.ALMS_TABLE.get().defaultBlockState().setValue(FACING, front).setValue(PART, part)

        // Authored facing north (the front to the north, the keeper's right hand to the east), and turned for each facing
        private val TOP_SHAPE: VoxelShape = box(0.0, 11.0, 1.0, 16.0, 13.0, 15.0)
        private val LEFT_LEGS: VoxelShape = box(1.5, 0.0, 2.0, 3.5, 11.0, 14.0)
        private val RIGHT_LEGS: VoxelShape = box(12.5, 0.0, 2.0, 14.5, 11.0, 14.0)
        /** The pot on its middle (client/works/WorksArt's POT_SHAPE). */
        private val POT: VoxelShape = box(6.4, 13.0, 6.5, 16.0, 18.0, 15.5)

        private val SHAPES: Map<TablePart, Map<Direction, VoxelShape>> = mapOf(
            TablePart.LEFT to Shapes.rotateHorizontal(Shapes.or(TOP_SHAPE, LEFT_LEGS)),
            TablePart.MIDDLE to Shapes.rotateHorizontal(Shapes.or(TOP_SHAPE, POT)),
            TablePart.RIGHT to Shapes.rotateHorizontal(Shapes.or(TOP_SHAPE, RIGHT_LEGS)),
        )

        /** What bumps into it: the whole table, as high as a fence so nobody jumps up onto it, the pot with it. */
        private val SOLID: Map<TablePart, Map<Direction, VoxelShape>> = TablePart.entries.associateWith {
            Shapes.rotateHorizontal(box(0.0, 0.0, 1.0, 16.0, 24.0, 15.0))
        }
    }
}

/**
 * The alms table's middle (see [AlmsTableBlock]): the soup pot and the bowls set out by it, as the server's [AlmsTable]
 * has them, kept with the chunk and sent to the clients: how much [soup] is in the pot of the [cap] it holds (its level
 * shows it), whether the [pot] is on the table yet, how many [bowls] are stacked by it, and when the keeper last started
 * ladling and a gift was last tipped in (for the ladle taken up, the dip's ripple and the splash).
 */
class AlmsTableBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(StreetsRegistry.ALMS_TABLE_ENTITY.get(), pos, state) {
    var soup = 0
        private set
    var cap = AlmsTable.POT
        private set
    var pot = false
        private set
    var bowls = 0
        private set
    var ladled = 0L
        private set
    var given = 0L
        private set

    /** Client side: the soup's level as drawn (easing to [soup]), and the pot's simmer while it's heard (client/streets). */
    @JvmField
    var shown = -1f

    @JvmField
    var loop: Any? = null

    @JvmField
    var loopRetry = 0L

    /** Client side: the bowls as last seen, and when the last was set out (it's seen set down on the table). */
    @JvmField
    var bowlsSeen = -1

    @JvmField
    var bowlAt = Long.MIN_VALUE / 2

    /** Client side: the pot as last seen (-1 not yet, 0 off the table, 1 on it), and when it was seen set down. */
    @JvmField
    var potSeen = -1

    @JvmField
    var potAt = Long.MIN_VALUE / 2

    /**
     * Client side: the last ladling and gift seen ([ladled] and [given] as they came), when each was seen to start (by
     * this client's clock, so in step with the keeper's move), and the bowls set out as the ladling started (the keeper
     * has lifted one off the stack until the server's count has it).
     */
    @JvmField
    var ladleSeen = Long.MIN_VALUE

    @JvmField
    var ladleAt = Long.MIN_VALUE / 2

    @JvmField
    var bowlsAtLadle = Int.MAX_VALUE

    @JvmField
    var giftSeen = Long.MIN_VALUE

    @JvmField
    var giftAt = Long.MIN_VALUE / 2

    /** Server side: the pot and bowls as they are now, telling the clients if that changes anything. */
    fun set(soup: Int, cap: Int, pot: Boolean, bowls: Int) {
        val s = soup.coerceIn(0, cap)
        val b = bowls.coerceIn(0, BOWLS)
        if (s == this.soup && cap == this.cap && pot == this.pot && b == this.bowls) return
        this.soup = s
        this.cap = cap
        this.pot = pot
        this.bowls = b
        sync()
    }

    /** Server side: the keeper starts ladling now (taking up the ladle and a bowl off the stack; the dip's a little later). */
    fun ladle(now: Long) {
        ladled = now
        sync()
    }

    /** Server side: a gift tipped into the pot now. */
    fun gift(now: Long) {
        given = now
        sync()
    }

    private fun sync() {
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("soup", soup)
        output.putInt("cap", cap)
        output.putBoolean("pot", pot)
        output.putInt("bowls", bowls)
        output.putLong("ladled", ladled)
        output.putLong("given", given)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        cap = input.getIntOr("cap", AlmsTable.POT).coerceAtLeast(1)
        soup = input.getIntOr("soup", 0).coerceIn(0, cap)
        pot = input.getBooleanOr("pot", false)
        bowls = input.getIntOr("bowls", 0).coerceIn(0, BOWLS)
        ladled = input.getLongOr("ladled", 0L)
        given = input.getLongOr("given", 0L)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** The most bowls set out on the table at once: a stack of three (client/works/WorksArt's BOWLS_MAX), the keeper lifting the top one off. */
        const val BOWLS = 3

        /** Client side: each tick of a table in view (the pot's simmer), set by client/streets/StreetsClient. */
        @JvmStatic
        var clientTick: ((AlmsTableBlockEntity) -> Unit)? = null
    }
}
