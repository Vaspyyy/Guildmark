package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksArt
import io.github.vaspyyy.guildmark.homestead.Materials
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
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
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * The town works' own blocks (living-world/design-influence.md sections 3 and 8): the works post with its drawing and
 * seals, the patron's plaque, the yard's crate and its heaps of what's there (one block for each material), the mortar
 * tub, the hoist, the tower's beacon, and a stall of one's own's counter and awning; and the sounds of a works going up
 * (living-world/works-audio.md, listed in sounds.json). None has an item, drops anything or can be broken by a
 * player: the yard's things go when the works does. Their ids, properties and models are Part F's
 * (art/scripts/works_art.py: models/block/works/, every one facing north in its model but the plaque, authored facing
 * south, and its blockstate turning it by facing); the crate wears the homestead's supply crate, and the pitch and the
 * awning are the building's own. The renderers that heap, seal, flare and roll them are in client/works.
 */
object WorksBlocks {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)

    // ---- The blocks ----

    /** The proposal post at a works' site: its drawing on a parchment and a red seal for each tenth pledged; using it opens the works' screen. */
    val POST: DeferredBlock<WorksPostBlock> = BLOCKS.registerBlock("works_post", ::WorksPostBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion() })

    /** The patron's bronze plaque on the finished works' wall, [WorksPlaqueBlock.COVERED] by its cloth until the opening. */
    val PLAQUE: DeferredBlock<WorksPlaqueBlock> = BLOCKS.registerBlock("works_plaque", ::WorksPlaqueBlock, fixed(MapColor.COLOR_ORANGE, SoundType.COPPER) { it.noOcclusion().noCollision() })

    /** The yard's crate: it takes only what the bill still wants, and using it opens the works' screen. */
    val CRATE: DeferredBlock<WorksCrateBlock> = BLOCKS.registerBlock("works_crate", ::WorksCrateBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion() })

    /** The yard's heaps, one for each paid material: timber stacked, stone piled, glass in straw crates, iron bars, wool bales. */
    val YARD_TIMBER: DeferredBlock<WorksYardBlock> = yard(YardStuff.TIMBER, MapColor.WOOD, SoundType.WOOD)
    val YARD_STONE: DeferredBlock<WorksYardBlock> = yard(YardStuff.STONE, MapColor.STONE, SoundType.STONE)
    val YARD_GLASS: DeferredBlock<WorksYardBlock> = yard(YardStuff.GLASS, MapColor.WOOD, SoundType.GLASS)
    val YARD_IRON: DeferredBlock<WorksYardBlock> = yard(YardStuff.IRON, MapColor.METAL, SoundType.METAL)
    val YARD_WOOL: DeferredBlock<WorksYardBlock> = yard(YardStuff.WOOL, MapColor.WOOL, SoundType.WOOL)

    /** The yard's heap of [stuff]. */
    fun yard(stuff: YardStuff): DeferredBlock<WorksYardBlock> = when (stuff) {
        YardStuff.TIMBER -> YARD_TIMBER
        YardStuff.STONE -> YARD_STONE
        YardStuff.GLASS -> YARD_GLASS
        YardStuff.IRON -> YARD_IRON
        YardStuff.WOOL -> YARD_WOOL
    }

    /** The mortar tub by the yard, the mortar in it stirred and spread. */
    val TUB: DeferredBlock<MortarTubBlock> = BLOCKS.registerBlock("works_mortar_tub", ::MortarTubBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion() })

    /** A hoist: the granary's, over its loading door (its builders send its fittings up by it). */
    val HOIST: DeferredBlock<WorksHoistBlock> = BLOCKS.registerBlock("works_hoist", ::WorksHoistBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion() })

    /** The watchtower's beacon: out by day, burning from dusk, flaring when a strike is seen. */
    val BEACON: DeferredBlock<WorksBeaconBlock> = BLOCKS.registerBlock("works_beacon", ::WorksBeaconBlock, fixed(MapColor.METAL, SoundType.CHAIN) {
        it.noOcclusion().lightLevel { s -> s.getValue(WorksBeaconBlock.FIRE).light }
    })

    /** A stall of one's own's counter: using it opens the stall's screen, its owner's to stock. */
    val PITCH: DeferredBlock<StallPitchBlock> = BLOCKS.registerBlock("works_pitch", ::StallPitchBlock, fixed(MapColor.WOOD, SoundType.WOOD) { it.noOcclusion() })

    /** A stall of one's own's awning in its patron's colour: drawn up while it trades, let down at night. */
    val AWNING: DeferredBlock<WorksAwningBlock> = BLOCKS.registerBlock("works_awning", ::WorksAwningBlock, fixed(MapColor.WOOL, SoundType.WOOL) { it.noOcclusion().noCollision() })

    val POST_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<WorksPostBlockEntity>> =
        BLOCK_ENTITIES.register("works_post") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::WorksPostBlockEntity), POST.get()) }
    val PLAQUE_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<WorksPlaqueBlockEntity>> =
        BLOCK_ENTITIES.register("works_plaque") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::WorksPlaqueBlockEntity), PLAQUE.get()) }
    val YARD_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<WorksYardBlockEntity>> =
        BLOCK_ENTITIES.register("works_yard") { ->
            BlockEntityType(BlockEntityType.BlockEntitySupplier(::WorksYardBlockEntity), YARD_TIMBER.get(), YARD_STONE.get(), YARD_GLASS.get(), YARD_IRON.get(), YARD_WOOL.get())
        }
    val TUB_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<MortarTubBlockEntity>> =
        BLOCK_ENTITIES.register("works_mortar_tub") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::MortarTubBlockEntity), TUB.get()) }
    val HOIST_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<WorksHoistBlockEntity>> =
        BLOCK_ENTITIES.register("works_hoist") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::WorksHoistBlockEntity), HOIST.get()) }
    val BEACON_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<WorksBeaconBlockEntity>> =
        BLOCK_ENTITIES.register("works_beacon") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::WorksBeaconBlockEntity), BEACON.get()) }
    val AWNING_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<WorksAwningBlockEntity>> =
        BLOCK_ENTITIES.register("works_awning") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::WorksAwningBlockEntity), AWNING.get()) }

    private fun yard(stuff: YardStuff, color: MapColor, sound: SoundType): DeferredBlock<WorksYardBlock> =
        BLOCKS.registerBlock("works_yard_${stuff.name.lowercase()}", { props -> WorksYardBlock(stuff, props) }, fixed(color, sound) { it.noOcclusion() })

    // ---- The sounds (art/audio, living-world/works-audio.md: each heard as far as its range) ----

    private fun sound(name: String, range: Float): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> SoundEvent.createFixedRangeEvent(id(name), range) }

    /** A trowel's scrape along a course of mortar, and its tap settling a stone. */
    val TROWEL = sound("works.trowel", 16f)
    /** Mallet on chisel, dressing a stone. */
    val CHISEL = sound("works.chisel", 24f)
    /** The hoe through the mortar in the tub. */
    val MORTAR = sound("works.mortar", 16f)
    /** The hoist's rope creaking through its pulley under a load. */
    val HOIST_SOUND = sound("works.hoist", 24f)
    /** A beam set down, two to it. */
    val BEAM_DOWN = sound("works.beam_down", 32f)
    /** A dressed stone bedded on its mortar. */
    val STONE_SET = sound("works.stone_set", 20f)
    /** A stake knocked in, and the post. */
    val STAKE = sound("works.stake", 24f)
    /** The crew and the onlookers cheering a stage. */
    val CHEER = sound("works.cheer", 48f)
    /** Cups knocked together, to the town. */
    val TOAST = sound("works.toast", 20f)
    /** The cloth pulled off the patron's plaque. */
    val UNVEIL = sound("works.unveil", 24f)
    /** The master builder's handbell, calling the town to the opening. */
    val HANDBELL = sound("works.handbell", 48f)
    /** The watchtower's horn: riders on the road. */
    val HORN = sound("works.horn", 128f)
    /** The beacon catching, or flaring up. */
    val BEACON_FLARE = sound("works.beacon_flare", 48f)
    /** The beacon burning (a loop, played by the client while it's lit). */
    val BEACON_LOOP = sound("works.beacon_loop", 24f)
    /** The granary's double doors swung open or shut. */
    val GRANARY_DOORS = sound("works.granary_doors", 32f)
    /** Marks pledged at the post: coins into the box. */
    val PLEDGE = sound("works.pledge", 12f)
    /** A seal dropping out of its slit on the post's parchment to the end of its cord (the client's, as each new one lands). */
    val SEAL = sound("works.seal", 12f)

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    /** Its blocks, entities and sounds into the mod's registries (from [WorksBuild.register]). */
    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        BLOCK_ENTITIES.register(modBus)
        SOUNDS.register(modBus)
    }

    /** Whether [state] is one of the works' own blocks (never the town's, never anything a player made). */
    fun ours(state: BlockState): Boolean = state.block is WorksOwnBlock

    /** The properties of a works' block: no loot, never pushed, and unbreakable but by the works' own coming and going. */
    private fun fixed(color: MapColor, sound: SoundType, more: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> {
        more(it.mapColor(color).strength(-1f, 3_600_000f).noLootTable().sound(sound).pushReaction(PushReaction.IMMOVEABLE).isValidSpawn { _, _, _, _ -> false })
    }

    /** Plays [event] at [pos] for everyone in its range, at the audio's own level and pitch (living-world/works-audio.md: 1.0, 0.95 to 1.05, NEUTRAL). */
    fun play(level: ServerLevel, pos: BlockPos, event: DeferredHolder<SoundEvent, SoundEvent>, volume: Float = 1f, pitch: Float = 0.95f + level.random.nextFloat() * 0.1f) {
        level.playSound(null, pos, event.get(), SoundSource.NEUTRAL, volume, pitch)
    }

    /** Sends [be]'s data to the clients watching it. */
    internal fun sync(be: BlockEntity) {
        be.setChanged()
        val level = be.level ?: return
        if (!level.isClientSide()) level.sendBlockUpdated(be.blockPos, be.blockState, be.blockState, Block.UPDATE_CLIENTS)
    }
}

/** One of the works' own blocks (see [WorksBlocks.ours]). */
interface WorksOwnBlock

/** A works' block that turns to face the way its drawing has it (FACING). */
abstract class WorksFacingBlock(properties: BlockBehaviour.Properties) : Block(properties), WorksOwnBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    companion object {
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING
    }
}

/** A block entity that sends all it keeps to the clients, for its renderer. */
abstract class WorksBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : BlockEntity(type, pos, state) {
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)
}

// ---- The post ----

/**
 * The works post: a timber post with the drawing pinned to its board. Using it opens the works' screen; materials
 * brought to it go into the yard (while the yard has no crate, the post takes them).
 */
class WorksPostBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SOLID.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WorksPostBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (!level.isClientSide() || type != WorksBlocks.POST_ENTITY.get()) return null
        return BlockEntityTicker { _, _, _, entity -> clientTick?.invoke(entity as WorksPostBlockEntity) }
    }

    override fun useItemOn(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        if (Materials.of(stack) == null) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        if (!WorksYard.bring(level, player, pos, stack)) WorksLedger.openAt(level, player, pos)
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) WorksLedger.openAt(level, player, pos)
        return InteractionResult.SUCCESS
    }

    companion object {
        // Part F's model, facing north: the post at the back up to its cap, the notice board on its front under the drip
        // hood, the pledge box below; a body is stopped by it as high as by a fence
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(
            Shapes.or(box(6.5, 0.0, 10.0, 9.5, 29.2, 13.0), box(1.0, 11.0, 9.0, 15.0, 26.0, 10.0), box(0.4, 26.0, 6.4, 15.6, 26.8, 10.6), box(4.5, 5.0, 6.0, 11.5, 9.5, 10.0)),
        )
        private val SOLID: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(
            Shapes.or(box(6.5, 0.0, 10.0, 9.5, 24.0, 13.0), box(1.0, 11.0, 9.0, 15.0, 24.0, 10.0), box(4.5, 5.0, 6.0, 11.5, 9.5, 10.0)),
        )

        /** The client's look at a post each tick (client/works sets it): each new seal heard as its drop lands. */
        var clientTick: ((WorksPostBlockEntity) -> Unit)? = null
    }
}

/**
 * What the post shows: the works ([works], its [kind]'s drawing), how many tenths of its price are pledged ([seals],
 * a red seal each), whether it's [stamped] (paid for), when it was knocked in ([raised], game time, for the client's
 * settling of it), when its seals last changed ([sealedAt]: from [was] seals, the new ones dropping out one after
 * another [SEAL_GAP] ticks apart, or those taken back drawn up), when the stamp comes down on it ([stampedAt]: as the
 * last new seal lands) and when it was last pledged to at the post ([pledgedAt], the coins' drop into its box).
 */
class WorksPostBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(WorksBlocks.POST_ENTITY.get(), pos, state) {
    var works: Int = -1
        private set
    var kind: String = ""
        private set
    var seals: Int = 0
        private set
    var stamped: Boolean = false
        private set
    var raised: Long = 0L
        private set
    var sealedAt: Long = 0L
        private set
    var pledgedAt: Long = 0L
        private set
    var was: Int = 0
        private set
    var stampedAt: Long = 0L
        private set

    /** The client's: the game time it last looked at this post (the stamp is heard as it comes down while it looks). */
    var heard: Long = Long.MIN_VALUE

    /** The client's: the seals it has heard land, or found down already (bit k for seal k), each heard once. */
    var sealsHeard: Int = 0

    fun set(works: Int, kind: String, seals: Int, stamped: Boolean) {
        val n = seals.coerceIn(0, 10)
        if (this.works == works && this.kind == kind && this.seals == n && this.stamped == stamped) return
        val now = level?.gameTime ?: 0L
        if (this.works != works) {
            // A post put up (or put to another works) shows what's pledged as it is
            raised = now
            was = n
            stampedAt = if (stamped) now - STAMP_SHOWN else 0L
        } else if (n != this.seals) {
            was = this.seals
            sealedAt = now
        }
        if (this.works == works && stamped && !this.stamped) {
            stampedAt = if (sealedAt == now && n > was) now + (n - was - 1) * SEAL_GAP + WorksArt.SEAL_DROP.toLong() else now
        }
        this.works = works
        this.kind = kind
        this.seals = n
        this.stamped = stamped
        WorksBlocks.sync(this)
    }

    /** Marks pledged at the post just now. */
    fun pledged() {
        pledgedAt = level?.gameTime ?: 0L
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("works", works)
        output.putString("kind", kind)
        output.putInt("seals", seals)
        output.putBoolean("stamped", stamped)
        output.putLong("raised", raised)
        output.putLong("sealed_at", sealedAt)
        output.putLong("pledged_at", pledgedAt)
        output.putInt("was", was)
        output.putLong("stamped_at", stampedAt)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        works = input.getIntOr("works", -1)
        kind = input.getStringOr("kind", "")
        seals = input.getIntOr("seals", 0)
        stamped = input.getBooleanOr("stamped", false)
        raised = input.getLongOr("raised", 0L)
        sealedAt = input.getLongOr("sealed_at", 0L)
        pledgedAt = input.getLongOr("pledged_at", 0L)
        was = input.getIntOr("was", seals)
        stampedAt = input.getLongOr("stamped_at", if (stamped) sealedAt else 0L)
    }

    companion object {
        /** Ticks between one new seal's drop and the next's. */
        const val SEAL_GAP = 5L

        /** How long ago a stamp already on a post put up was pressed: long enough to show settled. */
        private const val STAMP_SHOWN = 1000L
    }
}

// ---- The plaque ----

/** The patron's plaque, on the works' wall facing out ([FACING], the wall behind it), [COVERED] by its cloth until the opening. */
class WorksPlaqueBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COVERED, true))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, COVERED)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        (if (state.getValue(COVERED)) VEILED else BARE).getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WorksPlaqueBlockEntity(pos, state)

    companion object {
        val COVERED: BooleanProperty = BooleanProperty.create("covered")

        // Facing north, the wall is to its south: the plate hangs on that side of its block, under its velvet till the opening
        private val BARE: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(2.0, 3.0, 15.0, 14.0, 13.0, 16.0))
        private val VEILED: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(1.0, 1.7, 14.7, 15.0, 14.3, 16.0))
    }
}

/**
 * The plaque's words: the works' [kind], its [patron] (who pledged most), how many [others] pledged with them, the
 * capital's name ([place]) and the market [day] it opened; and when it was unveiled ([unveiled], game time, for the
 * cloth's fall on the client).
 */
class WorksPlaqueBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(WorksBlocks.PLAQUE_ENTITY.get(), pos, state) {
    var kind: String = ""
        private set
    var patron: String = ""
        private set
    var others: Int = 0
        private set
    var place: String = ""
        private set
    var day: Long = 0L
        private set
    var unveiled: Long = 0L
        private set

    /** The client's: its words as last lettered (client/works keeps them here). */
    var words: Any? = null

    fun set(kind: String, patron: String, others: Int, place: String, day: Long) {
        if (this.kind == kind && this.patron == patron && this.others == others && this.place == place && this.day == day) return
        this.kind = kind
        this.patron = patron
        this.others = others
        this.place = place
        this.day = day
        WorksBlocks.sync(this)
    }

    /** The cloth comes off: now. */
    fun unveil() {
        unveiled = level?.gameTime ?: 0L
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("kind", kind)
        output.putString("patron", patron)
        output.putInt("others", others)
        output.putString("place", place)
        output.putLong("day", day)
        output.putLong("unveiled", unveiled)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        kind = input.getStringOr("kind", "")
        patron = input.getStringOr("patron", "")
        others = input.getIntOr("others", 0)
        place = input.getStringOr("place", "")
        day = input.getLongOr("day", 0L)
        unveiled = input.getLongOr("unveiled", 0L)
    }
}

// ---- The yard ----

/**
 * The works' crate in its yard: it takes what's brought toward the bill (Works.bring, as much as the bill still wants,
 * counted the homestead's way) and holds nothing itself, what's brought going into the works' record; used empty-handed,
 * it opens the works' screen. It wears the homestead's supply crate, authored facing south.
 */
class WorksCrateBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties) {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun useItemOn(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        if (Materials.of(stack) == null) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        if (!WorksYard.bring(level, player, pos, stack)) WorksLedger.openAt(level, player, pos)
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) {
            level.playSound(null, pos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5f, 1f)
            WorksLedger.openAt(level, player, pos)
        }
        return InteractionResult.SUCCESS
    }

    companion object {
        // The supply crate's model fills its block
        private val SHAPE: VoxelShape = Shapes.block()
    }
}

/** What a yard heap holds: one of the paid materials (Stuff.PAID's order). */
enum class YardStuff(val stuff: Stuff) : StringRepresentable {
    TIMBER(Stuff.TIMBER), STONE(Stuff.STONE), GLASS(Stuff.GLASS), IRON(Stuff.IRON), WOOL(Stuff.WOOL);

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        fun of(stuff: Stuff): YardStuff? = entries.firstOrNull { it.stuff == stuff }
    }
}

/** A heap of [stuff] in the yard (one block for each material, sharing one block entity's type): as much as the works' record has there. */
class WorksYardBlock(val stuff: YardStuff, properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WorksYardBlockEntity(pos, state)

    override fun useItemOn(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        if (Materials.of(stack) == null) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        if (!WorksYard.bring(level, player, pos, stack)) WorksLedger.openAt(level, player, pos)
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) WorksLedger.openAt(level, player, pos)
        return InteractionResult.SUCCESS
    }

    companion object {
        private val SHAPE: VoxelShape = box(0.5, 0.0, 0.5, 15.5, 12.0, 15.5)
    }
}

/**
 * How much of its material a heap shows: the units [here] of the [bill]'s, and what it showed before ([was]) and
 * since when ([since], game time), so the client can heap it up or take it down a piece at a time.
 */
class WorksYardBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(WorksBlocks.YARD_ENTITY.get(), pos, state) {
    var here: Int = 0
        private set
    var bill: Int = 1
        private set
    var was: Int = 0
        private set
    var since: Long = 0L
        private set

    /** The material it heaps. */
    val stuff: YardStuff get() = (blockState.block as? WorksYardBlock)?.stuff ?: YardStuff.TIMBER

    /** The share of its bill it holds, 0 to 1. */
    val share: Float get() = if (bill <= 0) 0f else (here.toFloat() / bill).coerceIn(0f, 1f)

    fun set(here: Int, bill: Int) {
        if (this.here == here && this.bill == bill) return
        was = this.here
        since = level?.gameTime ?: 0L
        this.here = here.coerceAtLeast(0)
        this.bill = bill.coerceAtLeast(1)
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("here", here)
        output.putInt("bill", bill)
        output.putInt("was", was)
        output.putLong("since", since)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        here = input.getIntOr("here", 0)
        bill = input.getIntOr("bill", 1)
        was = input.getIntOr("was", 0)
        since = input.getLongOr("since", 0L)
    }
}

/**
 * The mortar tub: MIX_MORTAR is played standing behind it (the block on the side it doesn't face), facing it. Its
 * [MORTAR] is how full it is (its block entity's count), so the mortar's top stands that high in its model and reads
 * true from across the yard: raised a round of the hoe at a time, lowered a trowel's worth at a time.
 */
class MortarTubBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MORTAR, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, MORTAR)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = MortarTubBlockEntity(pos, state)

    companion object {
        val MORTAR: IntegerProperty = IntegerProperty.create("mortar", 0, MortarTubBlockEntity.FULL)
        private val SHAPE: VoxelShape = box(1.0, 0.0, 1.0, 15.0, 7.0, 15.0)
    }
}

/** The mortar in the tub: how full it is ([mortar], 0 to [FULL]), when it was last stirred ([stirred]) and last scooped from ([scooped], game times). */
class MortarTubBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(WorksBlocks.TUB_ENTITY.get(), pos, state) {
    var mortar: Int = 0
        private set
    var stirred: Long = 0L
        private set
    var scooped: Long = 0L
        private set

    /** A turn of the hoe through it: fuller, up to full. */
    fun mix(by: Int = FULL) {
        mortar = (mortar + by).coerceAtMost(FULL)
        stirred = level?.gameTime ?: 0L
        show()
        WorksBlocks.sync(this)
    }

    /** A trowel's worth taken out: true if there was any. */
    fun scoop(): Boolean {
        if (mortar <= 0) return false
        mortar--
        scooped = level?.gameTime ?: 0L
        show()
        WorksBlocks.sync(this)
        return true
    }

    /** The tub's block shows as much mortar as it has. */
    private fun show() {
        val level = level ?: return
        if (level.isClientSide()) return
        val state = level.getBlockState(worldPosition)
        if (!state.`is`(WorksBlocks.TUB.get()) || state.getValue(MortarTubBlock.MORTAR) == mortar) return
        level.setBlock(worldPosition, state.setValue(MortarTubBlock.MORTAR, mortar.coerceIn(0, FULL)), Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("mortar", mortar)
        output.putLong("stirred", stirred)
        output.putLong("scooped", scooped)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        mortar = input.getIntOr("mortar", 0)
        stirred = input.getLongOr("stirred", 0L)
        scooped = input.getLongOr("scooped", 0L)
    }

    companion object {
        /** Trowels' worth of mortar in a full tub. */
        const val FULL = 8
    }
}

/** A hoist: a beam out from the wall with a pulley at its end and a rope down from it. */
class WorksHoistBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SOLID.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WorksHoistBlockEntity(pos, state)

    companion object {
        // Part F's model, facing north: the sole and the post at the block's back (against the wall), the jib out from
        // the post's top a block and more; a body is stopped by the post as high as by a fence
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(
            Shapes.or(box(1.0, 0.0, 10.5, 6.0, 1.0, 16.0), box(2.0, 1.0, 12.0, 5.0, 26.0, 15.0), box(2.25, 23.0, -16.0, 4.75, 25.5, 12.0)),
        )
        private val SOLID: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(Shapes.or(box(1.0, 0.0, 10.5, 6.0, 1.0, 16.0), box(2.0, 1.0, 12.0, 5.0, 24.0, 15.0)))
    }
}

/**
 * What the hoist is lifting: [load] (an item's id, "" for nothing) from the ground [drop] blocks below it (in front of
 * it, where its falls hang), the lift begun at [from] (game time), its hauling taking [ticks] (WorksArt.ticksFor its
 * travel, so the rope runs with HOIST's heaves). The client draws the falls, the hook and the load rising, held at the
 * top while it's taken in, and the hook let back down.
 */
class WorksHoistBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(WorksBlocks.HOIST_ENTITY.get(), pos, state) {
    var load: String = ""
        private set
    var drop: Int = 4
        private set
    var from: Long = 0L
        private set
    var ticks: Int = 0
        private set

    /** Whether the lift runs backwards: the empty hook drawn up, and [load] let down from the top to the ground (a Carriers' Store's goods taken out). */
    var down: Boolean = false
        private set

    /** The load as an item stack, for drawing it. */
    val stack: ItemStack
        get() {
            if (load.isEmpty()) return ItemStack.EMPTY
            val id = Identifier.tryParse(load) ?: return ItemStack.EMPTY
            return BuiltInRegistries.ITEM.getOptional(id).map { ItemStack(it) }.orElse(ItemStack.EMPTY)
        }

    /** Lifts [stack] up from [drop] blocks below over [ticks]. */
    fun lift(stack: ItemStack, drop: Int, ticks: Int, down: Boolean = false) {
        this.down = down
        load = if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).toString()
        this.drop = drop.coerceIn(1, 24)
        this.ticks = ticks.coerceAtLeast(1)
        from = level?.gameTime ?: 0L
        WorksBlocks.sync(this)
    }

    /** The rope hanging [drop] blocks down to the ground, its hook at rest with nothing on it (a lift done, or cut short). */
    fun hang(drop: Int) {
        if (load.isEmpty() && this.drop == drop && ticks == 0) return
        load = ""
        ticks = 0
        this.drop = drop.coerceIn(1, 24)
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("load", load)
        output.putInt("drop", drop)
        output.putLong("from", from)
        output.putInt("ticks", ticks)
        if (down) output.putBoolean("down", true)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        load = input.getStringOr("load", "")
        drop = input.getIntOr("drop", 4)
        from = input.getLongOr("from", 0L)
        ticks = input.getIntOr("ticks", 0)
        down = input.getBooleanOr("down", false)
    }
}

// ---- The beacon ----

/** How the beacon burns: out (cold coals and ash), lit (aglow, flames licking up) or flaring (a pillar of fire). */
enum class BeaconFire(private val id: String, val light: Int) : StringRepresentable {
    OUT("out", 0), LIT("lit", 14), FLARE("flare", 15);

    override fun getSerializedName(): String = id
}

/** The tower's beacon: its [FIRE] out, lit or flaring. Its flames and smoke are its own; its loop is the client's. */
class WorksBeaconBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FIRE, BeaconFire.OUT))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, FIRE)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WorksBeaconBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (!level.isClientSide() || type != WorksBlocks.BEACON_ENTITY.get()) return null
        return BlockEntityTicker { _, _, _, entity -> clientTick?.invoke(entity as WorksBeaconBlockEntity) }
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val fire = state.getValue(FIRE)
        if (fire == BeaconFire.OUT) {
            // Cold ash, a thread of smoke now and then
            if (random.nextInt(8) == 0) level.addParticle(ParticleTypes.SMOKE, pos.x + 0.5 + (random.nextDouble() - 0.5) * 0.3, pos.y + 0.8, pos.z + 0.5 + (random.nextDouble() - 0.5) * 0.3, 0.0, 0.01, 0.0)
            return
        }
        val flare = fire == BeaconFire.FLARE
        repeat(if (flare) 7 else 2) {
            level.addParticle(
                if (random.nextInt(3) == 0) ParticleTypes.LAVA else ParticleTypes.FLAME,
                pos.x + 0.5 + (random.nextDouble() - 0.5) * 0.5, pos.y + 0.9 + random.nextDouble() * 0.3, pos.z + 0.5 + (random.nextDouble() - 0.5) * 0.5,
                (random.nextDouble() - 0.5) * 0.02, 0.02 + random.nextDouble() * (if (flare) 0.09 else 0.03), (random.nextDouble() - 0.5) * 0.02,
            )
        }
        repeat(if (flare) 3 else 1) {
            level.addParticle(
                ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x + 0.5 + (random.nextDouble() - 0.5) * 0.4, pos.y + 1.2, pos.z + 0.5 + (random.nextDouble() - 0.5) * 0.4,
                0.0, 0.04 + random.nextDouble() * 0.03, 0.0,
            )
        }
        if (random.nextInt(5) == 0) level.playLocalSound(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.6f, 0.9f + random.nextFloat() * 0.2f, false)
    }

    companion object {
        val FIRE: EnumProperty<BeaconFire> = EnumProperty.create("fire", BeaconFire::class.java)
        private val SHAPE: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 15.0, 14.0)

        /** The client's look at a beacon each tick (client/works sets it): its loop started while it burns. */
        var clientTick: ((WorksBeaconBlockEntity) -> Unit)? = null
    }
}

/** The beacon's fire: when it was last lit ([lit]) and last flared up ([flared], game times), for the client's burst. */
class WorksBeaconBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(WorksBlocks.BEACON_ENTITY.get(), pos, state) {
    var flared: Long = 0L
        private set
    var lit: Long = 0L
        private set

    /** The client's loop sound for it while it burns (client/works keeps it here). */
    var loop: Any? = null

    fun lit() {
        lit = level?.gameTime ?: 0L
        WorksBlocks.sync(this)
    }

    fun flare() {
        flared = level?.gameTime ?: 0L
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putLong("flared", flared)
        output.putLong("lit", lit)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        flared = input.getLongOr("flared", 0L)
        lit = input.getLongOr("lit", 0L)
    }
}

// ---- A stall of one's own ----

/** The stall's counter: used, it opens the stall's screen (StallDesk.openPitch), its owner's to stock. */
class StallPitchBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties) {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) WorksAtWork.usePitch(level, player, pos)
        return InteractionResult.SUCCESS
    }

    companion object {
        private val SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 14.0, 16.0)
    }
}

/** The stall's awning over it, [OPEN] (drawn up and out) while it trades; its cloth is drawn by the client. */
class WorksAwningBlock(properties: BlockBehaviour.Properties) : WorksFacingBlock(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPEN, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, OPEN)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WorksAwningBlockEntity(pos, state)

    companion object {
        val OPEN: BooleanProperty = BlockStateProperties.OPEN
        private val SHAPE: VoxelShape = box(0.0, 13.0, 0.0, 16.0, 16.0, 16.0)
    }
}

/** The awning's cloth: its colour ([color], a dye id) and when it was last drawn up or let down ([moved], game time). */
class WorksAwningBlockEntity(pos: BlockPos, state: BlockState) : WorksBlockEntity(WorksBlocks.AWNING_ENTITY.get(), pos, state) {
    var color: Int = 14
        private set
    var moved: Long = 0L
        private set

    fun color(color: Int) {
        if (this.color == color) return
        this.color = color
        WorksBlocks.sync(this)
    }

    fun moved() {
        moved = level?.gameTime ?: 0L
        WorksBlocks.sync(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("color", color)
        output.putLong("moved", moved)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        color = input.getIntOr("color", 14)
        moved = input.getLongOr("moved", 0L)
    }
}
