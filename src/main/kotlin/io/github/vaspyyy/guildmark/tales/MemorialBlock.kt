package io.github.vaspyyy.guildmark.tales

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Function
import java.util.function.UnaryOperator

/** The two looks of the memorial block (living-world/stories/design.md 6.4, 13.2.1): an upright carved stone, and a pile of fieldstone. */
enum class MemorialKind(val id: String) : StringRepresentable {
    STONE("stone"),
    CAIRN("cairn");

    override fun getSerializedName(): String = id

    companion object {
        /** The kind called [id], the stone for any other word. */
        fun of(id: String?): MemorialKind = entries.firstOrNull { it.id == id } ?: STONE
    }
}

/** One name cut in a memorial: who, the market [day] they died (unknown: [TaleData.NEVER]) and how, in a few words ("" if not said). */
data class MemorialName(val name: String, val day: Long = TaleData.NEVER, val cause: String = "") {
    companion object {
        val CODEC: Codec<MemorialName> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("name").forGetter(MemorialName::name),
                Codec.LONG.optionalFieldOf("day", TaleData.NEVER).forGetter(MemorialName::day),
                Codec.STRING.optionalFieldOf("cause", "").forGetter(MemorialName::cause),
            ).apply(i) { name, day, cause -> MemorialName(name, day, cause) }
        }
    }
}

/**
 * What the memorial adds to the registries (living-world/stories/design.md 14.10): the block, its item (a stone in the creative hand;
 * the cairn is the same item with a block-state `kind` of `cairn`, or a custom model data string `cairn`, as Part G's item definition
 * reads them) and its block entity. Like the alms table it has no loot and cannot be pushed, and an explosion cannot take it: only a
 * hand can ([MemorialBlock.playerWillDestroy]).
 */
object MemorialRegistry {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)

    val MEMORIAL: DeferredBlock<MemorialBlock> = BLOCKS.registerBlock("memorial", ::MemorialBlock, props {
        it.mapColor(MapColor.STONE).strength(2.0f, 3_600_000f).sound(SoundType.STONE).noOcclusion().noLootTable()
            .pushReaction(PushReaction.IMMOVEABLE).isValidSpawn { _, _, _, _ -> false }.isRedstoneConductor { _, _, _ -> false }
            .isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
            .lightLevel { state -> MemorialBlock.light(state) }
    })

    val ITEM: DeferredItem<MemorialItem> = ITEMS.registerItem("memorial", Function { MemorialItem(MEMORIAL.get(), it) }, UnaryOperator<Item.Properties> { it.useBlockDescriptionPrefix() })

    val ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<MemorialBlockEntity>> =
        BLOCK_ENTITIES.register("memorial") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::MemorialBlockEntity), MEMORIAL.get()) }

    /** The item for a memorial of [kind] (for the creative hand, and the block's pick): the cairn is the stone's item carrying `kind=cairn`. */
    fun stack(kind: MemorialKind): ItemStack {
        val stack = ItemStack(ITEM.get())
        if (kind != MemorialKind.STONE) {
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(MemorialBlock.KIND, kind))
            stack.set(DataComponents.ITEM_NAME, Component.translatable("block.guildmark.memorial.${kind.id}"))
        }
        return stack
    }

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }
}

/** The memorial's item: a block item that also places the cairn when its custom model data says `cairn` (Part G's item definition picks the picture by the same word). */
class MemorialItem(block: Block, properties: Properties) : BlockItem(block, properties) {
    override fun getPlacementState(context: BlockPlaceContext): BlockState? {
        val state = super.getPlacementState(context) ?: return null
        val strings = context.itemInHand.get(DataComponents.CUSTOM_MODEL_DATA)?.strings().orEmpty()
        return if (MemorialKind.CAIRN.id in strings) state.setValue(MemorialBlock.KIND, MemorialKind.CAIRN) else state
    }
}

/**
 * The memorial (living-world/stories/design.md 6.4, 13.2.1): one block in two looks, a carved [MemorialKind.STONE] (an upright stone
 * with a ledge at its foot, two blocks high in a one-cell block) and a [MemorialKind.CAIRN] (a pile of fieldstone, three blocks across
 * at the foot, with a flagstone step in front). Part G's models draw it all from the state: `facing` (the way its carved face looks,
 * which is where a mourner kneels), `candles` (0 to 4 stand on the ledge or the step), `lit` (their flames), `mossy` (set once it is ten
 * market days old), `posy` (a bunch of flowers laid on the ledge by the Laying) and `wilted` (that posy some days on: dried, its heads down,
 * a petal now and then drifting from it, until the wind takes it).
 *
 * What it holds is in [MemorialBlockEntity]: the names, the template and lead of the tale it is for, the day it was laid, and the
 * village's key when a village keeps it (every field optional, so the villages' memorials are the same block). A right-click reads the
 * inscription as a chat line; a candle goes on the ledge, flint and steel lights the candles, and bare fingers pinch them out. Broken
 * by a survival player it is a slight to the household and a rumour ([Memorials.defaced]); in creative, or by an explosion (it cannot
 * be), nothing. It is not one of the festivals' restorable pieces, so no decoration can ever take its place.
 */
class MemorialBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(
            stateDefinition.any().setValue(KIND, MemorialKind.STONE).setValue(FACING, Direction.NORTH).setValue(CANDLES, 0)
                .setValue(LIT, false).setValue(MOSSY, false).setValue(POSY, false).setValue(WILTED, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(KIND, FACING, CANDLES, LIT, MOSSY, POSY, WILTED)
    }

    /** Set down by hand it faces the one who placed it, as a stone set for a visitor to read does. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** What the cursor sees: the part in the block's own cell (a ray finds a block only through its cell). */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        (if (state.getValue(KIND) == MemorialKind.CAIRN) CAIRN_OUTLINE else STONE_OUTLINE).getValue(state.getValue(FACING))

    /** What stops a body: the stone as tall as it looks (higher than a fence, so nobody climbs on its ledge), the cairn's cell and its crown. */
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        (if (state.getValue(KIND) == MemorialKind.CAIRN) CAIRN_SOLID else STONE_SOLID).getValue(state.getValue(FACING))

    override fun propagatesSkylightDown(state: BlockState): Boolean = true

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = MemorialBlockEntity(pos, state)

    /** Asked only while there is something to wait for: the moss not grown yet, a posy lying that will wither, or flames to put out should their tick be lost. */
    override fun isRandomlyTicking(state: BlockState): Boolean = !state.getValue(MOSSY) || state.getValue(POSY) || state.getValue(LIT)

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) = Memorials.age(level, pos, state)

    /** The candles burn out when their time comes ([Memorials.burn] schedules it). */
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) = Memorials.burnt(level, pos, state)

    /** Reads the inscription. */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        if (player.isShiftKeyDown && state.getValue(LIT)) {
            Memorials.pinch(level, pos, state, player)
            return InteractionResult.SUCCESS
        }
        Memorials.read(level, pos, state, player)
        return InteractionResult.SUCCESS
    }

    /** A candle in hand goes on the ledge; flint and steel or a fire charge lights what stands there. Anything else reads the stone. */
    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hitResult: BlockHitResult,
    ): InteractionResult {
        val candle = stack.`is`(ItemTags.CANDLES)
        val flame = stack.`is`(Items.FLINT_AND_STEEL) || stack.`is`(Items.FIRE_CHARGE)
        if (!candle && !flame) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        val done = if (candle) Memorials.addCandle(level, pos, state, player, stack) else Memorials.kindle(level, pos, state, player, stack, hand)
        return if (done) InteractionResult.SUCCESS else InteractionResult.TRY_WITH_EMPTY_HAND
    }

    /** A breaker is a slight, unless they are in creative or spectating (survival players only, 6.4). */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (level is ServerLevel && player is ServerPlayer && !player.isCreative && !player.isSpectator) Memorials.defaced(level, pos, state, player)
        return super.playerWillDestroy(level, pos, state, player)
    }

    /** It is gone, however: the town's record of the stone goes with it ([Memorials.gone]). */
    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        Memorials.gone(level, pos)
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
    }

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean, player: Player): ItemStack =
        MemorialRegistry.stack(state.getValue(KIND))

    /**
     * A petal now and then from a posy that has withered; a thread of smoke from each candle that is lit (the flame itself is in the model,
     * so nothing is added to it).
     */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val facing = state.getValue(FACING)
        val kind = state.getValue(KIND)
        val posy = state.getValue(POSY)
        if (posy && state.getValue(WILTED) && random.nextInt(SHED) == 0) {
            val at = posyAt(kind, facing, pos)
            level.addParticle(
                ParticleTypes.CHERRY_LEAVES, at.x + (random.nextDouble() - 0.5) * 0.12, at.y + 0.06, at.z + (random.nextDouble() - 0.5) * 0.12, 0.0, 0.0, 0.0,
            )
        }
        if (!state.getValue(LIT)) return
        for (i in 0 until state.getValue(CANDLES)) {
            if (random.nextInt(4) != 0) continue
            val tip = tip(kind, i, facing, pos, posy)
            level.addParticle(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 0.0, 0.0, 0.0)
        }
        // The wick's small crackle, as a vanilla candle makes it
        if (random.nextFloat() < 0.12f) {
            level.playLocalSound(
                pos.x + 0.5, pos.y + 0.4, pos.z + 0.5, SoundEvents.CANDLE_AMBIENT, SoundSource.BLOCKS,
                0.6f + random.nextFloat() * 0.5f, random.nextFloat() * 0.7f + 0.3f, false,
            )
        }
    }

    companion object {
        /** Which look, and the way its carved face looks (also where a mourner kneels). */
        val KIND: EnumProperty<MemorialKind> = EnumProperty.create("kind", MemorialKind::class.java)
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING

        /**
         * The candles that stand on the ledge (0 to 4) and whether their flames burn; moss grown at the foot; a posy laid on the ledge and
         * whether it has withered (only ever with a posy: the models draw the one or the other, and the candles stand clear of it).
         */
        val CANDLES: IntegerProperty = IntegerProperty.create("candles", 0, MAX_CANDLES)
        val LIT: BooleanProperty = BlockStateProperties.LIT
        val MOSSY: BooleanProperty = BooleanProperty.create("mossy")
        val POSY: BooleanProperty = BooleanProperty.create("posy")
        val WILTED: BooleanProperty = BooleanProperty.create("wilted")

        /** A withered posy sheds a petal on one in this many of the client's visits to the block. */
        private const val SHED = 5

        /** The state of a memorial of [kind] facing [facing]: no candles, no moss, no posy. */
        fun state(kind: MemorialKind, facing: Direction): BlockState =
            MemorialRegistry.MEMORIAL.get().defaultBlockState().setValue(KIND, kind).setValue(FACING, facing)

        /** The light a lit memorial gives: three for each candle, as a candle's own. */
        fun light(state: BlockState): Int =
            if (state.getValue(LIT)) minOf(MAX_LIGHT, 3 * state.getValue(CANDLES)) else 0

        private const val MAX_LIGHT = 12

        // Authored facing north, as Part G's models are (the front, the carved face and the ledge toward -z), and turned for each facing
        private val STONE_OUTLINE: Map<Direction, VoxelShape> =
            Shapes.rotateHorizontal(Shapes.or(box(0.75, 0.0, 0.5, 15.25, 5.5, 13.5), box(2.0, 5.5, 3.5, 14.0, 16.0, 12.5)))
        private val STONE_SOLID: Map<Direction, VoxelShape> =
            Shapes.rotateHorizontal(Shapes.or(box(0.75, 0.0, 0.5, 15.25, 24.0, 13.5), box(2.5, 24.0, 4.0, 13.5, 32.0, 12.0)))
        private val CAIRN_OUTLINE: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0))
        private val CAIRN_SOLID: Map<Direction, VoxelShape> =
            Shapes.rotateHorizontal(Shapes.or(box(0.0, 0.0, 0.0, 16.0, 24.0, 16.0), box(2.0, 24.0, 2.0, 14.0, 32.0, 14.0)))

        /**
         * Where each of the four candles' flames burns, in sixteenths of the cell, facing north: `candle_tip` in `art/scripts/tale_blocks.py`
         * (which lays the candles out) gives the same numbers, and `python3 art/scripts/tale_blocks.py --numbers` writes them as they are
         * written here (the same for [TIPS_POSY] and [POSY_AT]): they are copied, and the script says so. With a posy lying on the ledge the
         * candles stand in other places, so there are two sets.
         */
        private val TIPS: Map<MemorialKind, List<Vec3>> = mapOf(
            MemorialKind.STONE to listOf(Vec3(8.6, 11.6, 2.1), Vec3(5.6, 10.6, 1.9), Vec3(11.6, 12.1, 1.9), Vec3(3.2, 10.1, 2.2)),
            MemorialKind.CAIRN to listOf(Vec3(8.6, 14.7, -10.8), Vec3(5.6, 13.7, -11.4), Vec3(11.6, 15.2, -11.4), Vec3(3.2, 13.2, -10.8)),
        )
        private val TIPS_POSY: Map<MemorialKind, List<Vec3>> = mapOf(
            MemorialKind.STONE to listOf(Vec3(6.6, 11.6, 2.0), Vec3(4.25, 10.6, 2.2), Vec3(14.1, 12.1, 2.0), Vec3(1.9, 10.1, 2.1)),
            MemorialKind.CAIRN to listOf(Vec3(6.2, 14.7, -11.8), Vec3(3.6, 13.7, -11.7), Vec3(6.4, 15.2, -9.4), Vec3(3.9, 13.2, -9.35)),
        )

        /**
         * Where the posy lies, in sixteenths of the cell, facing north: about 2.8 to the left of the mourner (who stands before the carved
         * face) of the stone's middle, 1.8 in from the plinth's front edge, on the ledge; on the cairn's step the same way (`POSY_AT` in
         * `art/scripts/tale_blocks.py` lays the flowers there, and these are copied from it). The stone's is half a pixel nearer the middle
         * than the clip sets the posy down (10.8): the ledge is 14.5 wide and holds four candles as well.
         */
        private val POSY_AT: Map<MemorialKind, Vec3> = mapOf(
            MemorialKind.STONE to Vec3(10.3, 5.5, 2.3),
            MemorialKind.CAIRN to Vec3(10.8, 8.6, -11.2),
        )

        /** Over the flame of candle [i] (0 to 3) of a memorial of [kind] facing [facing] at [pos], in the world; [posy]: one lies on the ledge, so the candles stand clear of it. */
        fun tip(kind: MemorialKind, i: Int, facing: Direction, pos: BlockPos, posy: Boolean = false): Vec3 =
            turned((if (posy) TIPS_POSY else TIPS).getValue(kind)[i.coerceIn(0, MAX_CANDLES - 1)], facing, pos)

        /** On the posy that lies on a memorial of [kind] facing [facing] at [pos], in the world. */
        fun posyAt(kind: MemorialKind, facing: Direction, pos: BlockPos): Vec3 = turned(POSY_AT.getValue(kind), facing, pos)

        /** [p] (sixteenths of the cell, facing north) as the block faces [facing] at [pos], in the world. */
        private fun turned(p: Vec3, facing: Direction, pos: BlockPos): Vec3 {
            // Turned about the cell's middle as the blockstate's `y` turns the model: north is as authored, then a quarter turn clockwise each
            var x = p.x
            var z = p.z
            repeat(facing.get2DDataValue().let { (it + 2) % 4 }) {
                val tx = 16.0 - z
                z = x
                x = tx
            }
            return Vec3(pos.x + x / 16.0, pos.y + p.y / 16.0, pos.z + z / 16.0)
        }

        /** The most candles that stand. */
        const val MAX_CANDLES = 4
    }
}

/**
 * What a memorial holds (every field optional, saved only when set, so a stone put down by hand is the same block): the [names] cut
 * in it, the [template] and [lead] (a player's name) of the tale it is for, the market [day] it was laid, the [capital] it is kept in (a
 * capital's key: the market whose days age it), the [village] that keeps it (a key, for the villages' memorials), the [memorial] id in
 * [TaleData.memorials] if there is one, the game time its candles burn [litUntil], and the day its posy was laid ([posyDay]).
 */
class MemorialBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(MemorialRegistry.ENTITY.get(), pos, state) {
    var names: List<MemorialName> = listOf()
    var template: String = ""
    var lead: String = ""
    var day: Long? = null
    var capital: Long? = null
    var village: Long? = null
    var memorial: Int = -1
    var litUntil: Long = 0L
    var posyDay: Long? = null

    /** Writes what the tale gives ([names], [template], [lead], the market [day]) and where it belongs ([capital], the [memorial] record). */
    fun set(names: List<MemorialName>, template: String, lead: String, day: Long?, capital: Long?, memorial: Int) {
        this.names = names
        this.template = template
        this.lead = lead
        this.day = day
        this.capital = capital
        this.memorial = memorial
        setChanged()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("kind", blockState.getValue(MemorialBlock.KIND).id)
        if (names.isNotEmpty()) output.store("names", MemorialName.CODEC.listOf(), names)
        if (template.isNotEmpty()) output.putString("template", template)
        if (lead.isNotEmpty()) output.putString("lead", lead)
        day?.let { output.putLong("day", it) }
        capital?.let { output.putLong("capital", it) }
        village?.let { output.putLong("village", it) }
        if (memorial >= 0) output.putInt("memorial", memorial)
        output.putInt("candles", blockState.getValue(MemorialBlock.CANDLES))
        if (litUntil != 0L) output.putLong("lit_until", litUntil)
        posyDay?.let { output.putLong("posy_day", it) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        names = input.read("names", MemorialName.CODEC.listOf()).orElse(listOf())
        template = input.getStringOr("template", "")
        lead = input.getStringOr("lead", "")
        day = input.getLong("day").orElse(null)
        capital = input.getLong("capital").orElse(null)
        village = input.getLong("village").orElse(null)
        memorial = input.getIntOr("memorial", -1)
        litUntil = input.getLongOr("lit_until", 0L)
        posyDay = input.getLong("posy_day").orElse(null)
    }
}
