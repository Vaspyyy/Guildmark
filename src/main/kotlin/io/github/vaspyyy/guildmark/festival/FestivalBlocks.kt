package io.github.vaspyyy.guildmark.festival

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** How solid a piece of festival trim is. */
enum class TrimShape { NONE, POST, LOW, FULL }

/**
 * What a piece of festival trim looks like: a vanilla block's look (blockstates/festival_trim.json points each at the
 * vanilla model, so it meshes and lights like the real thing) or one of the festival's poles. None of them is the
 * real block: nothing can be taken from them, they can't be broken in survival, and each is put back to what stood
 * there before once the festival is over.
 */
enum class TrimLook(private val id: String, val shape: TrimShape, val light: Int) : StringRepresentable {
    POLE_BIRCH("pole_birch", TrimShape.POST, 0),
    POLE_OAK("pole_oak", TrimShape.POST, 0),
    POLE_SPRUCE("pole_spruce", TrimShape.POST, 0),
    HAY("hay", TrimShape.FULL, 0),
    PUMPKIN("pumpkin", TrimShape.FULL, 0),
    CARVED("carved", TrimShape.FULL, 0),
    JACK("jack", TrimShape.FULL, 15),
    MELON("melon", TrimShape.FULL, 0),
    TARGET("target", TrimShape.FULL, 0),
    BRAZIER("brazier", TrimShape.LOW, 15),
    CANDLES("candles", TrimShape.NONE, 12),
    BLOSSOM("blossom", TrimShape.NONE, 0),

    // The streets in good times and hard (townsfolk/streets, M6 Part D): the ladder a resident sets against a wall to
    // hang the dressing, the flower pots on the doorsteps, and the "To let" board by the door of a home left empty
    LADDER("ladder", TrimShape.NONE, 0),
    POT_POPPY("pot_poppy", TrimShape.NONE, 0),
    POT_DANDELION("pot_dandelion", TrimShape.NONE, 0),
    POT_BLUE_ORCHID("pot_blue_orchid", TrimShape.NONE, 0),
    POT_ALLIUM("pot_allium", TrimShape.NONE, 0),
    POT_AZURE_BLUET("pot_azure_bluet", TrimShape.NONE, 0),
    POT_RED_TULIP("pot_red_tulip", TrimShape.NONE, 0),
    POT_ORANGE_TULIP("pot_orange_tulip", TrimShape.NONE, 0),
    POT_WHITE_TULIP("pot_white_tulip", TrimShape.NONE, 0),
    POT_PINK_TULIP("pot_pink_tulip", TrimShape.NONE, 0),
    POT_OXEYE_DAISY("pot_oxeye_daisy", TrimShape.NONE, 0),
    POT_CORNFLOWER("pot_cornflower", TrimShape.NONE, 0),
    POT_LILY_OF_THE_VALLEY("pot_lily_of_the_valley", TrimShape.NONE, 0),
    TO_LET("to_let", TrimShape.POST, 0);

    override fun getSerializedName(): String = id

    /** A flower pot on a doorstep. */
    val pot: Boolean get() = name.startsWith("POT_")

    companion object {
        /** The doorsteps' flower pots. */
        val POTS: List<TrimLook> = entries.filter { it.pot }
    }
}

/** A piece of festival trim (see [TrimLook]). */
class FestivalTrimBlock(properties: Properties) : Block(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(LOOK, TrimLook.POLE_OAK).setValue(FACING, Direction.SOUTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LOOK, FACING)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = when (state.getValue(LOOK)) {
        TrimLook.CANDLES -> CANDLES_SHAPE
        TrimLook.BLOSSOM -> BLOSSOM_SHAPE
        TrimLook.LADDER -> ladder(state.getValue(FACING))
        else -> if (state.getValue(LOOK).pot) POT_SHAPE else collision(state)
    }

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        if (state.getValue(LOOK).pot) POT_SHAPE else collision(state)

    private fun collision(state: BlockState): VoxelShape = when (state.getValue(LOOK).shape) {
        TrimShape.NONE -> Shapes.empty()
        TrimShape.POST -> POST_SHAPE
        TrimShape.LOW -> LOW_SHAPE
        TrimShape.FULL -> Shapes.block()
    }

    /** A ladder's outline: the rungs against the wall behind it, facing [facing] (as the vanilla ladder's). */
    private fun ladder(facing: Direction): VoxelShape = when (facing) {
        Direction.SOUTH -> LADDER_SOUTH
        Direction.WEST -> LADDER_WEST
        Direction.EAST -> LADDER_EAST
        else -> LADDER_NORTH
    }

    override fun propagatesSkylightDown(state: BlockState): Boolean = state.getValue(LOOK).shape != TrimShape.FULL

    // A piece nobody has a use for any more goes (see Festivities.checkPiece): an orphan after a crash, say
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) = Festivities.checkPiece(level, pos)

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack = ItemStack.EMPTY

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        when (state.getValue(LOOK)) {
            TrimLook.BRAZIER -> {
                if (random.nextInt(3) == 0) {
                    level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, pos.x + 0.5 + random.nextDouble() / 3.0 * (if (random.nextBoolean()) 1 else -1),
                        pos.y + 0.6, pos.z + 0.5 + random.nextDouble() / 3.0 * (if (random.nextBoolean()) 1 else -1), 0.0, 0.05, 0.0)
                }
                if (random.nextInt(6) == 0) level.addParticle(ParticleTypes.LAVA, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, random.nextFloat() / 2.0, 5.0E-5, random.nextFloat() / 2.0)
                if (random.nextInt(10) == 0) level.playLocalSound(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.5f + random.nextFloat(), random.nextFloat() * 0.7f + 0.6f, false)
            }
            TrimLook.CANDLES -> {
                // The vanilla three-candle cluster's wicks
                for ((x, y, z) in CANDLE_WICKS) {
                    if (random.nextFloat() < 0.3f) level.addParticle(ParticleTypes.SMALL_FLAME, pos.x + x, pos.y + y, pos.z + z, 0.0, 0.0, 0.0)
                }
            }
            else -> {}
        }
    }

    companion object {
        val LOOK: EnumProperty<TrimLook> = EnumProperty.create("look", TrimLook::class.java)
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING

        private val POST_SHAPE: VoxelShape = box(5.0, 0.0, 5.0, 11.0, 16.0, 11.0)
        private val LOW_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 7.0, 16.0)
        private val CANDLES_SHAPE: VoxelShape = box(5.0, 0.0, 6.0, 11.0, 6.0, 11.0)
        private val BLOSSOM_SHAPE: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 14.0, 14.0)
        /** The vanilla flower pot's, and the vanilla ladder's for each way it faces. */
        private val POT_SHAPE: VoxelShape = box(5.0, 0.0, 5.0, 11.0, 6.0, 11.0)
        private val LADDER_NORTH: VoxelShape = box(0.0, 0.0, 13.0, 16.0, 16.0, 16.0)
        private val LADDER_SOUTH: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 16.0, 3.0)
        private val LADDER_WEST: VoxelShape = box(13.0, 0.0, 0.0, 16.0, 16.0, 16.0)
        private val LADDER_EAST: VoxelShape = box(0.0, 0.0, 0.0, 3.0, 16.0, 16.0)
        /** The vanilla three-candle cluster's wicks (CandleBlock's own offsets). */
        private val CANDLE_WICKS = listOf(Triple(0.5, 0.3125, 0.625), Triple(0.375, 0.4375, 0.5), Triple(0.5625, 0.5, 0.4375))

        fun state(look: TrimLook, facing: Direction = Direction.SOUTH): BlockState =
            FestivalRegistry.TRIM.get().defaultBlockState().setValue(LOOK, look).setValue(FACING, facing)
    }
}

/** What an ornament is. Each draws itself (client/festival/FestivalOrnamentRenderer) and stirs in the wind. */
enum class OrnamentKind(val id: String) {
    /** The top of a decorated post: its lantern and banner, a string of pennants to the centrepiece, a garland to the next post. */
    POST("post"),
    /** The centrepiece's crown on top of its pole: ribbons, the sun wheel, the harvest crown or the star. */
    CROWN("crown"),
    /** A ribbon tied somewhere about the town for the Ribbon Hunt. */
    HUNT_RIBBON("hunt_ribbon"),
    /** A Lanternrise post whose lantern carries a riddle. */
    RIDDLE("riddle"),

    // The streets' dressing in good times (townsfolk/streets/Dressing, M6 Part D1), in the festivals' colours: each
    // hangs from [OrnamentData.from] along [OrnamentData.garland] or [OrnamentData.string]
    /** A garland swagged over a door. */
    STREET_GARLAND("street_garland"),
    /** A string of pennants from an eave across the street. */
    STREET_BUNTING("street_bunting"),
    /** A string of little lanterns over the market, from one lamp to the next: lit (the block's light) while its lamp burns. */
    STREET_LANTERNS("street_lanterns"),

    /**
     * A market stall's awning let down over its front, the keeper's shop shut (townsfolk/streets/Awnings, M6 Part D2):
     * [OrnamentData.string] is half its width along the front, [OrnamentData.drop] how far it hangs, [OrnamentData.index]
     * the way its face looks (a 2D value) and [OrnamentData.colors] its stripes.
     */
    AWNING_FLAP("awning_flap");

    /** One of the streets' (M6 Part D), not a festival's. */
    val street: Boolean get() = this == STREET_GARLAND || this == STREET_BUNTING || this == STREET_LANTERNS || this == AWNING_FLAP

    companion object {
        fun byId(id: String): OrnamentKind = entries.firstOrNull { it.id == id } ?: POST
    }
}

/** What an ornament shows, set once when it's put up. */
data class OrnamentData(
    val kind: OrnamentKind,
    val festival: FestivalKind,
    /** Which post, ribbon or riddle it is. */
    val index: Int = 0,
    /** From this block's middle to the far end of its string of pennants, if it has one. */
    val string: Vec3? = null,
    /** From this block's middle to the far end of its garland, if it has one. */
    val garland: Vec3? = null,
    /** A crown: the dancers' ring's radius (its ribbons reach them) and how far below it the ring's floor is. */
    val ring: Float = 0f,
    val drop: Float = 0f,
    /** A post: whether it flies a banner. */
    val banner: Boolean = false,
    /** The streets' pieces: where what hangs is tied on, from the block's corner (null: the block's middle). */
    val from: Vec3? = null,
    /** An awning's stripes: the stripe's dye id and the other's, each plus one, in the low and next bytes (0: unknown). */
    val colors: Int = 0,
    /** The streets' pieces: 0 while they're up, 1 once they're coming down (an awning rolling up, a string being untied). */
    val phase: Int = 0,
    /** The streets' pieces: the game time [phase] last changed (it went up, or began to come down); 0 for "there already". */
    val since: Long = 0L,
)

/**
 * A festival ornament: a lantern, pennants, ribbons and the rest, drawn and animated by the block entity's renderer
 * (the block itself has no model). The block has no collision. A hunt ribbon or a riddle lantern can be used.
 */
class FestivalOrnamentBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(LIGHT, 0).setValue(USABLE, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LIGHT, USABLE)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        if (state.getValue(USABLE)) USABLE_SHAPE else Shapes.empty()

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = Shapes.empty()

    override fun propagatesSkylightDown(state: BlockState): Boolean = true

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = FestivalOrnamentBlockEntity(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (!state.getValue(USABLE)) return InteractionResult.PASS
        if (level is ServerLevel && player is ServerPlayer) Festivities.useOrnament(level, pos, player)
        return InteractionResult.SUCCESS
    }

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) = Festivities.checkPiece(level, pos)

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack = ItemStack.EMPTY

    companion object {
        /** How much light it gives (a lantern 14, a riddle lantern 12, a ribbon none). */
        val LIGHT: IntegerProperty = IntegerProperty.create("light", 0, 15)
        /** A hunt ribbon or a riddle lantern, which can be used (and so can be aimed at). */
        val USABLE: BooleanProperty = BooleanProperty.create("usable")

        private val USABLE_SHAPE: VoxelShape = box(3.0, 1.0, 3.0, 13.0, 15.0, 13.0)

        fun state(light: Int, usable: Boolean): BlockState =
            FestivalRegistry.ORNAMENT.get().defaultBlockState().setValue(LIGHT, light.coerceIn(0, 15)).setValue(USABLE, usable)
    }
}

/** An ornament's block entity: what it shows ([data]), kept with the chunk and sent to clients. */
class FestivalOrnamentBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(FestivalRegistry.ORNAMENT_ENTITY.get(), pos, state) {
    var data: OrnamentData = OrnamentData(OrnamentKind.POST, FestivalKind.RIBBONWAKE)
        private set

    /**
     * Client side, for the streets' lantern strings (client/festival/FestivalOrnamentRenderer): whether it was last seen
     * lit (-1 not yet seen), and the game time that changed, so its lanterns catch one after another along the string.
     */
    @JvmField
    var lit = -1

    @JvmField
    var litAt = 0L

    /** Server side: set what it shows and tell the clients. */
    fun setup(data: OrnamentData) {
        this.data = data
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val d = data
        output.putString("kind", d.kind.id)
        output.putString("festival", d.festival.id)
        output.putInt("index", d.index)
        output.storeNullable("string", Vec3.CODEC, d.string)
        output.storeNullable("garland", Vec3.CODEC, d.garland)
        output.putFloat("ring", d.ring)
        output.putFloat("drop", d.drop)
        output.putBoolean("banner", d.banner)
        // The streets' pieces only (a festival's leave them at their defaults, and write nothing new)
        output.storeNullable("from", Vec3.CODEC, d.from)
        if (d.colors != 0) output.putInt("colors", d.colors)
        if (d.phase != 0) output.putInt("phase", d.phase)
        if (d.since != 0L) output.putLong("since", d.since)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        data = OrnamentData(
            OrnamentKind.byId(input.getStringOr("kind", "post")),
            FestivalKind.byId(input.getStringOr("festival", "ribbonwake")) ?: FestivalKind.RIBBONWAKE,
            input.getIntOr("index", 0),
            input.read("string", Vec3.CODEC).orElse(null),
            input.read("garland", Vec3.CODEC).orElse(null),
            input.getFloatOr("ring", 0f),
            input.getFloatOr("drop", 0f),
            input.getBooleanOr("banner", false),
            input.read("from", Vec3.CODEC).orElse(null),
            input.getIntOr("colors", 0),
            input.getIntOr("phase", 0),
            input.getLongOr("since", 0L),
        )
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)
}
