package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.TagKey
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * Everything homesteads add to the game's registries: the charter, the Charter Post and the crates, their menus and
 * the sounds of a homestead going up (art/audio/make_homestead_audio.py, listed in sounds.json). The blocks' and the
 * charter's art come from art/scripts/homestead_art.py.
 */
object HomesteadRegistry {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Guildmark.MOD_ID)
    val MENUS: DeferredRegister<MenuType<*>> = DeferredRegister.create(Registries.MENU, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)

    // ---- The charter and what it puts down ----

    /** A homestead charter: used on open land, it stakes a plot there for its holder (see HomesteadClaims). */
    val CHARTER: DeferredItem<CharterItem> = ITEMS.registerItem("homestead_charter", ::CharterItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.UNCOMMON)
    })

    /**
     * The Charter Post at a homestead's gate: its owner's pennant flies from it, and using it opens the homestead's
     * ledger. Nobody can break it; it goes when the charter is given up.
     */
    val CHARTER_POST: DeferredBlock<CharterPostBlock> = BLOCKS.registerBlock("charter_post", ::CharterPostBlock, props {
        it.mapColor(MapColor.WOOD).strength(-1f, 3_600_000f).noLootTable().sound(SoundType.WOOD).noOcclusion()
            .pushReaction(PushReaction.IMMOVEABLE).lightLevel { 9 }.isValidSpawn { _, _, _, _ -> false }
    })

    /** The supply crate beside the post: what the builders build with is left in it, and they take it out as they go. */
    val SUPPLY_CRATE: DeferredBlock<GoodsCrateBlock> = BLOCKS.registerBlock("supply_crate", { GoodsCrateBlock(it, GoodsCrateBlock.Kind.SUPPLY) }, props {
        it.mapColor(MapColor.WOOD).strength(-1f, 3_600_000f).noLootTable().sound(SoundType.WOOD).pushReaction(PushReaction.IMMOVEABLE)
    })

    /** A shipping crate in a homestead's yard: it takes only the farm's goods, for whoever comes to buy them. */
    val SHIPPING_CRATE: DeferredBlock<GoodsCrateBlock> = BLOCKS.registerBlock("shipping_crate", { GoodsCrateBlock(it, GoodsCrateBlock.Kind.SHIPPING) }, props {
        it.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).ignitedByLava()
    })

    val SHIPPING_CRATE_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("shipping_crate", SHIPPING_CRATE)

    // ---- The builders' tools, seen in their hands (not sold, not dropped) ----

    val HAMMER_ITEM: DeferredItem<Item> = ITEMS.registerSimpleItem("builders_hammer", UnaryOperator<Item.Properties> { it.stacksTo(1) })
    val SAW_ITEM: DeferredItem<Item> = ITEMS.registerSimpleItem("builders_saw", UnaryOperator<Item.Properties> { it.stacksTo(1) })
    val MALLET_ITEM: DeferredItem<Item> = ITEMS.registerSimpleItem("builders_mallet", UnaryOperator<Item.Properties> { it.stacksTo(1) })

    val CHARTER_POST_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<CharterPostBlockEntity>> =
        BLOCK_ENTITIES.register("charter_post") { -> BlockEntityType(BlockEntityType.BlockEntitySupplier(::CharterPostBlockEntity), CHARTER_POST.get()) }

    val GOODS_CRATE_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<GoodsCrateBlockEntity>> =
        BLOCK_ENTITIES.register("goods_crate") { ->
            BlockEntityType(BlockEntityType.BlockEntitySupplier(::GoodsCrateBlockEntity), SUPPLY_CRATE.get(), SHIPPING_CRATE.get())
        }

    val SUPPLY_MENU: DeferredHolder<MenuType<*>, MenuType<GoodsMenu>> =
        MENUS.register("supply_crate") { -> MenuType({ id, inventory -> GoodsMenu.client(GoodsCrateBlock.Kind.SUPPLY, id, inventory) }, FeatureFlags.VANILLA_SET) }

    val SHIPPING_MENU: DeferredHolder<MenuType<*>, MenuType<GoodsMenu>> =
        MENUS.register("shipping_crate") { -> MenuType({ id, inventory -> GoodsMenu.client(GoodsCrateBlock.Kind.SHIPPING, id, inventory) }, FeatureFlags.VANILLA_SET) }

    // ---- What goes in the crates ----

    /** What a shipping crate takes: the farm's own goods. */
    val GOODS: TagKey<Item> = TagKey.create(Registries.ITEM, id("homestead_goods"))

    /** Stone for the builders, a block of it a unit: cobble, rough and dressed stone, bricks. */
    val STONE: TagKey<Item> = TagKey.create(Registries.ITEM, id("homestead_stone"))

    /** Glass for the windows, a block of it a unit. */
    val GLASS: TagKey<Item> = TagKey.create(Registries.ITEM, id("homestead_glass"))

    /** Iron for the fittings: an ingot a unit (a block of iron is nine). */
    val IRON: TagKey<Item> = TagKey.create(Registries.ITEM, id("homestead_iron"))
    val IRON_BLOCKS: TagKey<Item> = TagKey.create(Registries.ITEM, id("homestead_iron_blocks"))

    // ---- Sounds ----

    private fun sound(name: String, range: Float? = null): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> if (range != null) SoundEvent.createFixedRangeEvent(id(name), range) else SoundEvent.createVariableRangeEvent(id(name)) }

    /** The charter's seal pressed and the stakes going in: a plot is claimed. */
    val CLAIM = sound("homestead.claim", 32f)
    /** A stake knocked in with a mallet. */
    val STAKE = sound("homestead.stake", 24f)
    /** The polite little shake of the head when a claim or a door is refused. */
    val REFUSE = sound("homestead.refuse", 12f)
    /** A builder's hammer on a nail. */
    val HAMMER = sound("homestead.hammer", 24f)
    /** A saw through a plank. */
    val SAW = sound("homestead.saw", 24f)
    /** A shovel biting into the ground. */
    val DIG = sound("homestead.dig", 20f)
    /** The crew's handbell as they come up the road in the morning. */
    val ARRIVE = sound("homestead.arrive", 48f)
    /** The crew's cheer when a stage is done. */
    val CHEER = sound("homestead.cheer", 40f)
    /** The bough on the ridge: a cheer and the rocket. */
    val TOPPING = sound("homestead.topping", 64f)
    /** A tier finished: the crew's fanfare. */
    val FINISHED = sound("homestead.finished", 64f)
    /** Materials going into the supply crate. */
    val DELIVER = sound("homestead.deliver", 16f)
    /** The ledger opened at the post. */
    val LEDGER = sound("homestead.ledger", 12f)
    /** Scaffolding poles clattering up or down. */
    val SCAFFOLD = sound("homestead.scaffold", 24f)
    /** The carter's handcart on its way, its wheels knocking and creaking (art/audio/make_stall_audio.py, as the rest of shipping's). */
    val CARTER_WHEELS = sound("homestead.carter.wheels", 24f)
    /** A sack heaped onto the carter's cart. */
    val CARTER_LOAD = sound("homestead.carter.load", 16f)
    /** The Marks in the Charter Post's purse, counted out into its owner's hand. */
    val PURSE = sound("homestead.purse", 12f)

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    // Explicit UnaryOperator: a bare lambda is ambiguous with the Supplier overload in Kotlin.
    private fun props(set: (BlockBehaviour.Properties) -> BlockBehaviour.Properties) = UnaryOperator<BlockBehaviour.Properties> { set(it) }
}
