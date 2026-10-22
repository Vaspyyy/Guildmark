package io.github.vaspyyy.guildmark.cooking

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.dish.CookBookItem
import io.github.vaspyyy.guildmark.cooking.dish.CookJournal
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.LarderBlock
import io.github.vaspyyy.guildmark.cooking.dish.LarderBlockEntity
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlock
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlockEntity
import io.github.vaspyyy.guildmark.cooking.home.HerbBlock
import io.github.vaspyyy.guildmark.cooking.market.MenuBoardBlock
import io.github.vaspyyy.guildmark.cooking.market.MenuBoardBlockEntity
import io.github.vaspyyy.guildmark.cooking.meal.CookEffect
import io.github.vaspyyy.guildmark.cooking.recipe.CookXpDay
import io.github.vaspyyy.guildmark.cooking.vessel.CookTool
import io.github.vaspyyy.guildmark.cooking.vessel.CookToolItem
import io.github.vaspyyy.guildmark.cooking.vessel.HobBlock
import io.github.vaspyyy.guildmark.cooking.vessel.HobBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.OvenBlock
import io.github.vaspyyy.guildmark.cooking.vessel.OvenBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.PrepBoardBlock
import io.github.vaspyyy.guildmark.cooking.vessel.PrepBoardBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.SpitBlock
import io.github.vaspyyy.guildmark.cooking.vessel.SpitBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlock
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.UnaryOperator

/**
 * Everything Hearthcraft adds to the game's registries (living-world/cook/design.md 12.3, Appendix C.1): nine blocks and the
 * eight block items of the stations and helpers, the eight block entity types, twenty items (the dishes, the tools, the
 * book, the sprig), the five effects, the four data components and the three attachments. Every id is a contract, so the
 * art, a recipe or a language line can name it before the code behind it exists; the classes behind them are each part's
 * own stubs, which the part fills in, and how each block and item is made (hardness, stack size, durability) is in the
 * `PROPERTIES` of the block's or item's own class, so no part edits this file. Sounds are in [CookSounds] and particles in
 * [CookParticles].
 */
object CookRegistry {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)
    val EFFECTS: DeferredRegister<MobEffect> = DeferredRegister.create(Registries.MOB_EFFECT, Guildmark.MOD_ID)
    val COMPONENTS: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    // ---- Blocks (1.1, 11.1): five stations and three helpers, and the herb ----

    /** A pot over a fire, camp or hearth style. */
    val STEWPOT: DeferredBlock<StewpotBlock> = BLOCKS.registerBlock("stewpot", ::StewpotBlock, StewpotBlock.PROPERTIES)

    /** A brick plinth with an iron top for the pan. */
    val HOB: DeferredBlock<HobBlock> = BLOCKS.registerBlock("hob", ::HobBlock, HobBlock.PROPERTIES)

    /** A domed clay oven. */
    val BAKE_OVEN: DeferredBlock<OvenBlock> = BLOCKS.registerBlock("bake_oven", ::OvenBlock, OvenBlock.PROPERTIES)

    /** A rod turned over a fire pit. */
    val SPIT: DeferredBlock<SpitBlock> = BLOCKS.registerBlock("spit", ::SpitBlock, SpitBlock.PROPERTIES)

    /** A counter with a chopping board. */
    val PREP_BOARD: DeferredBlock<PrepBoardBlock> = BLOCKS.registerBlock("prep_board", ::PrepBoardBlock, PrepBoardBlock.PROPERTIES)

    /** A plate that holds one dish on a table. */
    val PLATE: DeferredBlock<PlateBlock> = BLOCKS.registerBlock("plate", ::PlateBlock, PlateBlock.PROPERTIES)

    /** A cupboard of 18 slots that keeps dishes twice as long. */
    val LARDER: DeferredBlock<LarderBlock> = BLOCKS.registerBlock("larder", ::LarderBlock, LarderBlock.PROPERTIES)

    /** A chalk slate that shows an inn's wants or a shop's stock. */
    val MENU_BOARD: DeferredBlock<MenuBoardBlock> = BLOCKS.registerBlock("menu_board", ::MenuBoardBlock, MenuBoardBlock.PROPERTIES)

    /** The kitchen herb, a crop of four ages planted from the sprig. */
    val HERB: DeferredBlock<HerbBlock> = BLOCKS.registerBlock("herb", ::HerbBlock, HerbBlock.PROPERTIES)

    // ---- Block entity types (1.2): under the block's own id ----

    val STEWPOT_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<StewpotBlockEntity>> =
        BLOCK_ENTITIES.register("stewpot") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::StewpotBlockEntity), STEWPOT.get()) }

    val HOB_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<HobBlockEntity>> =
        BLOCK_ENTITIES.register("hob") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::HobBlockEntity), HOB.get()) }

    val OVEN_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<OvenBlockEntity>> =
        BLOCK_ENTITIES.register("bake_oven") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::OvenBlockEntity), BAKE_OVEN.get()) }

    val SPIT_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<SpitBlockEntity>> =
        BLOCK_ENTITIES.register("spit") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::SpitBlockEntity), SPIT.get()) }

    val PREP_BOARD_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<PrepBoardBlockEntity>> =
        BLOCK_ENTITIES.register("prep_board") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::PrepBoardBlockEntity), PREP_BOARD.get()) }

    val PLATE_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<PlateBlockEntity>> =
        BLOCK_ENTITIES.register("plate") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::PlateBlockEntity), PLATE.get()) }

    val LARDER_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<LarderBlockEntity>> =
        BLOCK_ENTITIES.register("larder") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::LarderBlockEntity), LARDER.get()) }

    val MENU_BOARD_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<MenuBoardBlockEntity>> =
        BLOCK_ENTITIES.register("menu_board") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::MenuBoardBlockEntity), MENU_BOARD.get()) }

    // ---- Items: the block items, the tools, the book, the sprig and the dishes (1.1, 3.1, 6.7) ----

    val STEWPOT_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("stewpot", STEWPOT)
    val HOB_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("hob", HOB)
    val BAKE_OVEN_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("bake_oven", BAKE_OVEN)
    val SPIT_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("spit", SPIT)
    val PREP_BOARD_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("prep_board", PREP_BOARD)
    val PLATE_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("plate", PLATE)
    val LARDER_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("larder", LARDER)
    val MENU_BOARD_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("menu_board", MENU_BOARD)

    /** The Cook's Ladle, the Cook's Knife, the Turner and the Gilded Ladle, by tool (items `cooks_ladle`, `cooks_knife`, `turner`, `gilded_ladle`). */
    val TOOLS: Map<CookTool, DeferredItem<CookToolItem>> = CookTool.entries.associateWith { tool ->
        ITEMS.registerItem(tool.id, { CookToolItem(it, tool) }, CookToolItem.properties(tool))
    }

    /** The Cook's Book. */
    val COOK_BOOK: DeferredItem<CookBookItem> = ITEMS.registerItem("cook_book", ::CookBookItem, CookBookItem.PROPERTIES)

    /** A sprig of the kitchen herb; planted on farmland it is the herb. */
    val HERB_SPRIG: DeferredItem<BlockItem> =
        ITEMS.registerItem("herb_sprig", { BlockItem(HERB.get(), it) }, UnaryOperator<Item.Properties> { it })

    /** The twelve dishes, by kind (items `dish_pottage` to `dish_strips`, in the order of [DishKind]). */
    val DISHES: Map<DishKind, DeferredItem<DishItem>> = DishKind.entries.associateWith { kind ->
        ITEMS.registerItem(kind.itemId, { DishItem(it, kind) }, DishItem.properties(kind))
    }

    /** What a pot with no recipe in it makes. */
    val GRUEL: DeferredItem<DishItem> = ITEMS.registerItem("gruel", { DishItem(it, null) }, DishItem.properties(null))

    /** What a burnt pot is emptied into. */
    val SCORCHED_MESS: DeferredItem<DishItem> = ITEMS.registerItem("scorched_mess", { DishItem(it, null) }, DishItem.properties(null))

    /** Everything above, in the order the creative tab shows it: the stations and helpers, the tools, the book, the sprig, the dishes. */
    val TAB: List<DeferredItem<out Item>> = buildList<DeferredItem<out Item>> {
        add(STEWPOT_ITEM)
        add(HOB_ITEM)
        add(BAKE_OVEN_ITEM)
        add(SPIT_ITEM)
        add(PREP_BOARD_ITEM)
        add(PLATE_ITEM)
        add(LARDER_ITEM)
        add(MENU_BOARD_ITEM)
        addAll(TOOLS.values)
        add(COOK_BOOK)
        add(HERB_SPRIG)
        addAll(DISHES.values)
        add(GRUEL)
        add(SCORCHED_MESS)
    }

    // ---- Meals and the cold (4.2): the amplifier carries the tier and a variant bit ----

    /** Hot soup keeps the winter cold off (Warmth family). */
    val WARMTH: DeferredHolder<MobEffect, CookEffect> = EFFECTS.register("warmth") { -> CookEffect(MobEffectCategory.BENEFICIAL, 0xE8893C, Family.WARMTH) }

    /** Hearts that last the meal (Hearty family). */
    val HEARTY: DeferredHolder<MobEffect, CookEffect> = EFFECTS.register("hearty") { -> CookEffect(MobEffectCategory.BENEFICIAL, 0xC24B3F, Family.HEARTY) }

    /** Stamina and poise for a fight (Steady family). */
    val STEADY: DeferredHolder<MobEffect, CookEffect> = EFFECTS.register("steady") { -> CookEffect(MobEffectCategory.BENEFICIAL, 0x4F7FC4, Family.STEADY) }

    /** A long road costs half the hunger (Provisioned family). */
    val PROVISIONED: DeferredHolder<MobEffect, CookEffect> = EFFECTS.register("provisioned") { -> CookEffect(MobEffectCategory.BENEFICIAL, 0x8A6A42, Family.PROVISIONED) }

    /** The winter cold of 4.3: a little more hunger and slower stamina, never damage. */
    val CHILLED: DeferredHolder<MobEffect, CookEffect> = EFFECTS.register("chilled") { -> CookEffect(MobEffectCategory.HARMFUL, 0xA9D4EC, null) }

    // ---- The dish a stack is, and the cosmetic ints the item model reads (3.1) ----

    /** What a dish stack carries: its tier, the day it was cooked, who cooked it and how long it keeps ([DishData]). */
    val DISH: DeferredHolder<DataComponentType<*>, DataComponentType<DishData>> = COMPONENTS.registerComponentType(
        "dish",
        UnaryOperator<DataComponentType.Builder<DishData>> { it.persistent(DishData.CODEC).networkSynchronized(DishData.STREAM_CODEC) },
    )

    /** The dish's tier (0 to 4), for the item model's stamp and wash. Written with the dish; the model reads it. */
    val DISH_TIER: DeferredHolder<DataComponentType<*>, DataComponentType<Int>> = COMPONENTS.registerComponentType(
        "dish_tier",
        UnaryOperator<DataComponentType.Builder<Int>> { it.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT) },
    )

    /** The dish's stage (0 fresh, 1 stale, 2 gone off), for the item model's wash. Written by the stack's own tick. */
    val DISH_STAGE: DeferredHolder<DataComponentType<*>, DataComponentType<Int>> = COMPONENTS.registerComponentType(
        "dish_stage",
        UnaryOperator<DataComponentType.Builder<Int>> { it.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT) },
    )

    /** A flag on an ingredient that has been through the Prep Board (1.1). */
    val PREPARED: DeferredHolder<DataComponentType<*>, DataComponentType<Boolean>> = COMPONENTS.registerComponentType(
        "prepared",
        UnaryOperator<DataComponentType.Builder<Boolean>> { it.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL) },
    )

    // ---- What a player keeps (9.1) ----

    /** What the cook has learned, kept through death and synced to its player for the Cook's Book. */
    val COOK_JOURNAL: DeferredHolder<AttachmentType<*>, AttachmentType<CookJournal>> = ATTACHMENTS.register("cook_journal") { ->
        AttachmentType.builder { -> CookJournal() }
            .serialize(CookJournal.CODEC.fieldOf("journal")) { !it.isEmpty() }
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, CookJournal.STREAM_CODEC)
            .build()
    }

    /** Whether a player has been handed their Cook's Book (once, the first time they use a station). */
    val COOK_BOOK_GIVEN: DeferredHolder<AttachmentType<*>, AttachmentType<Boolean>> = ATTACHMENTS.register("cook_book_given") { ->
        AttachmentType.builder { -> false }.serialize(Codec.BOOL.fieldOf("given")) { it }.copyOnDeath().build()
    }

    /** The COOKING XP a player has earned today, for the soft cap (2.5, 5.6). */
    val COOK_XP_DAY: DeferredHolder<AttachmentType<*>, AttachmentType<CookXpDay>> = ATTACHMENTS.register("cook_xp_day") { ->
        AttachmentType.builder { -> CookXpDay() }
            .serialize(CookXpDay.CODEC.fieldOf("xp_day")) { it.xp > 0 }
            .copyOnDeath()
            .build()
    }

    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        BLOCK_ENTITIES.register(modBus)
        EFFECTS.register(modBus)
        COMPONENTS.register(modBus)
        ATTACHMENTS.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey != ModCreativeTabs.MAIN.key) return@addListener
            for (item in TAB) event.accept(item.get())
        }
    }
}
