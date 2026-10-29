package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.equipment.EquipmentAssets
import net.minecraft.world.item.equipment.Equippable
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.UnaryOperator

/**
 * What the grand festivals add to the registries: the decorations (a trim block and an ornament block; neither has
 * an item, a drop or a recipe, and every one is taken down again), the four prizes, the sky lanterns of the
 * Lanternrise, the prize-giving's fanfare and the record each chunk keeps of what stood where a decoration went up.
 * The art comes from art/scripts/festival_art.py, the fanfare from art/audio/make_festival_music.py.
 */
object FestivalRegistry {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    // ---- Decorations ----

    /** Poles, hay, pumpkins, braziers, candles and butts: see [TrimLook]. Can't be broken in survival or moved. */
    val TRIM: DeferredBlock<FestivalTrimBlock> = BLOCKS.registerBlock("festival_trim", ::FestivalTrimBlock, props {
        it.mapColor(MapColor.WOOD).strength(-1f, 3600000f).noLootTable().noOcclusion().randomTicks()
            .pushReaction(PushReaction.IMMOVEABLE).sound(SoundType.WOOD)
            .lightLevel { state -> state.getValue(FestivalTrimBlock.LOOK).light }
            .isValidSpawn { _, _, _, _ -> false }.isRedstoneConductor { _, _, _ -> false }
            .isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
    })

    /** Lanterns, pennants, garlands, ribbons and the centrepieces' crowns, drawn by their block entity's renderer. */
    val ORNAMENT: DeferredBlock<FestivalOrnamentBlock> = BLOCKS.registerBlock("festival_ornament", ::FestivalOrnamentBlock, props {
        it.mapColor(MapColor.NONE).strength(-1f, 3600000f).noLootTable().noOcclusion().noCollision().randomTicks()
            .pushReaction(PushReaction.IMMOVEABLE).sound(SoundType.WOOL).noTerrainParticles()
            .lightLevel { state -> state.getValue(FestivalOrnamentBlock.LIGHT) }
            .isValidSpawn { _, _, _, _ -> false }.isRedstoneConductor { _, _, _ -> false }
            .isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _, _ -> false }
    })

    val ORNAMENT_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<FestivalOrnamentBlockEntity>> =
        BLOCK_ENTITIES.register("festival_ornament") { ->
            BlockEntityType(BlockEntityType.BlockEntitySupplier(::FestivalOrnamentBlockEntity), ORNAMENT.get())
        }

    // ---- Prizes ----

    /** The Ribbonwake's prize: a crown of blossom with ribbons streaming behind, worn on the head. */
    val RIBBON_CROWN: DeferredItem<Item> = ITEMS.registerSimpleItem("ribbon_crown", UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.RARE).component(
            DataComponents.EQUIPPABLE,
            Equippable.builder(EquipmentSlot.HEAD).setAsset(ResourceKey.create(EquipmentAssets.ROOT_ID, id("ribbon_crown")))
                .setEquipSound(SoundEvents.ARMOR_EQUIP_LEATHER).build(),
        )
    })

    /** The Sunhearth's prize: a gold medal with the sun on it, on a red ribbon. */
    val SUNHEARTH_MEDAL: DeferredItem<Item> = ITEMS.registerSimpleItem("sunhearth_medal", UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.RARE).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    })

    /** The Sheafmoot's prize: a sheaf of wheat dipped in gold and tied with a russet bow. */
    val GILDED_SHEAF: DeferredItem<Item> = ITEMS.registerSimpleItem("gilded_sheaf", UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.RARE).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    })

    /** The Lanternrise's prize: a little paper lantern of red and gold that never goes out. */
    val RIDDLERS_LANTERN: DeferredItem<Item> = ITEMS.registerSimpleItem("riddlers_lantern", UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.RARE).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    })

    /** Each festival's prize, in the festivals' order. */
    fun prize(kind: FestivalKind): Item = when (kind) {
        FestivalKind.RIBBONWAKE -> RIBBON_CROWN.get()
        FestivalKind.SUNHEARTH -> SUNHEARTH_MEDAL.get()
        FestivalKind.SHEAFMOOT -> GILDED_SHEAF.get()
        FestivalKind.LANTERNRISE -> RIDDLERS_LANTERN.get()
    }

    /** The Harvest Table's prize (Sheafmoot's cook-off, living-world/cook/design.md 6.8): the Gilded Ladle, Hearthcraft's own. */
    fun cookPrize(): Item = io.github.vaspyyy.guildmark.cooking.CookRegistry.TOOLS.getValue(io.github.vaspyyy.guildmark.cooking.vessel.CookTool.GILDED_LADLE).get()

    // ---- Effects ----

    /** A paper lantern let go at the Lanternrise's dusk, rising and drifting with a candle in it. */
    val SKY_LANTERN: DeferredHolder<ParticleType<*>, SimpleParticleType> = PARTICLES.register("sky_lantern") { -> SimpleParticleType(true) }

    /** The prize-giving's fanfare. */
    val FANFARE: DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register("festival.fanfare") { -> SoundEvent.createVariableRangeEvent(id("festival.fanfare")) }

    // ---- What stood where ----

    /** The decorations put up in a chunk, each with what stood there before (see [FestivalRestores]). */
    val PIECES: DeferredHolder<AttachmentType<*>, AttachmentType<FestivalRestores.Pieces>> = ATTACHMENTS.register("festival_pieces") { ->
        AttachmentType.builder { -> FestivalRestores.Pieces() }.serialize(FestivalRestores.Pieces.CODEC.fieldOf("pieces")) { it.records.isNotEmpty() }.build()
    }

    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        BLOCK_ENTITIES.register(modBus)
        PARTICLES.register(modBus)
        SOUNDS.register(modBus)
        ATTACHMENTS.register(modBus)
    }

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }
}
