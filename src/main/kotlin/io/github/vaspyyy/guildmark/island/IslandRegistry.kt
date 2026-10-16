package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * Everything islands add to the game's registries. Where islands generate lives in data/guildmark/worldgen. The gull's
 * model and textures and the nest's and the lamp's art come from art/scripts/island_gull.py and island_art.py, the
 * sounds and the isles' theme from art/audio/make_island_audio.py and make_island_music.py (listed in sounds.json).
 */
object IslandRegistry {
    val STRUCTURE_TYPES: DeferredRegister<StructureType<*>> = DeferredRegister.create(Registries.STRUCTURE_TYPE, Guildmark.MOD_ID)
    val PIECE_TYPES: DeferredRegister<StructurePieceType> = DeferredRegister.create(Registries.STRUCTURE_PIECE, Guildmark.MOD_ID)
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)

    val ISLAND: DeferredHolder<StructureType<*>, StructureType<IslandStructure>> =
        STRUCTURE_TYPES.register("island") { -> StructureType { IslandStructure.CODEC } }

    val ISLAND_PIECE: DeferredHolder<StructurePieceType, StructurePieceType> =
        PIECE_TYPES.register("island") { -> StructurePieceType { _, tag -> IslandPiece(tag) } }

    // ---- The gulls ----

    /** A gull: a box about its body, its wings reaching well past it in flight. */
    val GULL: DeferredHolder<EntityType<*>, EntityType<Gull>> = ENTITIES.register("gull") { ->
        EntityType.Builder.of(::Gull, MobCategory.AMBIENT)
            .sized(0.5f, 0.5f)
            .eyeHeight(0.4f)
            .clientTrackingRange(10)
            // It wheels and plunges, and its takeoffs, landings and dives keep time with its model: sent every tick
            .updateInterval(1)
            .build(key("gull"))
    }

    /** A gull's nest on a ledge, holding up to three eggs. */
    val GULL_NEST: DeferredBlock<GullNestBlock> = BLOCKS.registerBlock("gull_nest", ::GullNestBlock, props {
        it.mapColor(MapColor.WOOD).strength(0.3f).sound(SoundType.GRASS).noOcclusion().randomTicks().pushReaction(PushReaction.POPPED)
            .ignitedByLava().isValidSpawn { _, _, _, _ -> false }
    })

    /** A lighthouse's great lens in its brass frame: it lights at dusk and turns its beams out over the sea all night. */
    val LIGHTHOUSE_LAMP: DeferredBlock<LighthouseLampBlock> = BLOCKS.registerBlock("lighthouse_lamp", ::LighthouseLampBlock, props {
        it.mapColor(MapColor.GOLD).strength(3f, 6f).sound(SoundType.COPPER).noOcclusion().requiresCorrectToolForDrops()
            .lightLevel { state -> if (state.getValue(LighthouseLampBlock.LIT)) 15 else 3 }
            .isRedstoneConductor { _, _, _ -> false }.isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
    })

    val LIGHTHOUSE_LAMP_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<LighthouseLampBlockEntity>> =
        BLOCK_ENTITIES.register("lighthouse_lamp") { ->
            BlockEntityType(BlockEntityType.BlockEntitySupplier(::LighthouseLampBlockEntity), LIGHTHOUSE_LAMP.get())
        }

    val GULL_NEST_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("gull_nest", GULL_NEST)
    val LIGHTHOUSE_LAMP_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("lighthouse_lamp", LIGHTHOUSE_LAMP)

    /** A gull's egg, speckled blue-green and grey: good for nothing raw but baking, seared it's a meal. */
    val GULL_EGG: DeferredItem<Item> = ITEMS.registerSimpleItem("gull_egg", UnaryOperator<Item.Properties> { it.stacksTo(16) })

    val SEARED_GULL_EGG: DeferredItem<Item> = ITEMS.registerSimpleItem("seared_gull_egg", UnaryOperator<Item.Properties> {
        it.food(FoodProperties.Builder().nutrition(5).saturationModifier(0.7f).build())
    })

    // ---- Sounds ----

    /** A sound heard out to [range] blocks whatever its loudness, or as far as its loudness carries it. */
    private fun sound(name: String, range: Float? = null): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> if (range != null) SoundEvent.createFixedRangeEvent(id(name), range) else SoundEvent.createVariableRangeEvent(id(name)) }

    val SURF_LAP = sound("island.surf.lap", 24f)
    val SURF_CRASH = sound("island.surf.crash", 32f)
    val SURF_CAVE = sound("island.surf.cave", 32f)
    val WIND_BED = sound("island.wind.bed")
    val WIND_GUST = sound("island.wind.gust", 32f)
    val GULL_CALL = sound("island.gull.call", 64f)
    val GULL_CRY = sound("island.gull.cry", 48f)
    val GULL_ALARM = sound("island.gull.alarm", 48f)
    val GULL_FLAP = sound("island.gull.flap", 24f)
    val GULL_SQUABBLE = sound("island.gull.squabble", 40f)
    val GULL_SPLASH = sound("island.gull.splash", 24f)
    val GULL_COLONY = sound("island.gull.colony")
    val LIGHTHOUSE_TURN = sound("island.lighthouse.turn")
    val LIGHTHOUSE_HORN = sound("island.lighthouse.horn", 128f)
    val LIGHTHOUSE_BELL = sound("island.lighthouse.bell", 64f)
    val WRECK_CREAK = sound("island.wreck.creak", 32f)
    val WRECK_SAIL = sound("island.wreck.sail", 24f)
    val WRECK_BELL = sound("island.wreck.bell", 48f)
    val COVE_DRIP = sound("island.cove.drip", 16f)
    val COVE_SIGNAL = sound("island.cove.signal", 32f)
    val COVE_CAMP = sound("island.cove.camp", 16f)
    val SHRINE_DRONE = sound("island.shrine.drone")
    val SHRINE_CHIME = sound("island.shrine.chime", 24f)
    val SHRINE_BUBBLES = sound("island.shrine.bubbles", 24f)
    val SHRINE_WHISPER = sound("island.shrine.whisper", 16f)
    val HAVEN_BOATS = sound("island.haven.boats", 24f)
    val HAVEN_ROPE = sound("island.haven.rope", 16f)
    val DISCOVER = sound("island.discover")
    val MUSIC = sound("music.isles")

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    private fun key(name: String): ResourceKey<EntityType<*>> = ResourceKey.create(Registries.ENTITY_TYPE, id(name))

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }
}
