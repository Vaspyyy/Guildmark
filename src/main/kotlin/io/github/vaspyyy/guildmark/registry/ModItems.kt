package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.item.ContractItem
import io.github.vaspyyy.guildmark.item.DuskBridleItem
import io.github.vaspyyy.guildmark.item.RelicCacheItem
import io.github.vaspyyy.guildmark.item.WardensFlaskItem
import io.github.vaspyyy.guildmark.tales.KeepsakeItem
import io.github.vaspyyy.guildmark.trade.WarrantItem
import io.github.vaspyyy.guildmark.trade.carry.DroversWhistleItem
import io.github.vaspyyy.guildmark.trade.carry.PackHarnessItem
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.ToolMaterial
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.component.Tool
import net.minecraft.world.item.component.Weapon
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

object ModItems {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)

    // Quest currency
    val GUILD_MARK: DeferredItem<Item> = ITEMS.registerSimpleItem("guild_mark", UnaryOperator<Item.Properties> {
        it.rarity(Rarity.UNCOMMON)
    })

    val CONTRACT: DeferredItem<ContractItem> = ITEMS.registerItem("contract", ::ContractItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1)
    })

    /** One of the seven shards of the Hollow Crown; which one is in its SHARD component. */
    val CROWN_SHARD: DeferredItem<Item> = ITEMS.registerSimpleItem("crown_shard", UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
    })

    /** A sealed cache that opens into a relic; its minimum rarity is in its CACHE_TIER component. */
    val RELIC_CACHE: DeferredItem<RelicCacheItem> = ITEMS.registerItem("relic_cache", ::RelicCacheItem, UnaryOperator<Item.Properties> {
        it.stacksTo(16).rarity(Rarity.RARE)
    })

    val QUEST_BOARD: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("quest_board", ModBlocks.QUEST_BOARD)
    val BOUNTY_BOARD: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("bounty_board", ModBlocks.BOUNTY_BOARD)
    val MARKET_BOARD: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("market_board", ModBlocks.MARKET_BOARD)

    val WARDFIRE: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("wardfire", ModBlocks.WARDFIRE)

    /** Handed over at the first Wardfire you kindle; its draughts are kept on the player (see WardensFlask). */
    val WARDENS_FLASK: DeferredItem<WardensFlaskItem> = ITEMS.registerItem("wardens_flask", ::WardensFlaskItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.RARE).fireResistant()
    })

    /** Taken from a fallen Dusk Rider: calls a spectral steed to ride. */
    val DUSK_BRIDLE: DeferredItem<DuskBridleItem> = ITEMS.registerItem("dusk_bridle", ::DuskBridleItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
    })

    /** Only what a Dusk Rider's lantern wisp looks like in flight; never given out. */
    val LANTERN_WISP: DeferredItem<Item> = ITEMS.registerSimpleItem("lantern_wisp")

    /** The Carrier's Warrant a Chapman is given at the hall's counter (living-world/trader/design.md, Part 5); the trader's book is what counts, this is its proof. */
    val CARRIERS_WARRANT: DeferredItem<WarrantItem> = ITEMS.registerItem("carriers_warrant", ::WarrantItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.UNCOMMON)
    })

    // Trade: carrying (build part D1)

    /** Bought at the market hall's counter; used on a tame donkey, mule or llama it makes a pack animal that follows. */
    val PACK_HARNESS: DeferredItem<PackHarnessItem> = ITEMS.registerItem("pack_harness", ::PackHarnessItem, UnaryOperator<Item.Properties> {
        it.stacksTo(16)
    })

    /** Comes with the harness: blown, it calls every pack animal of yours; blown while sneaking, it has them stay. */
    val DROVERS_WHISTLE: DeferredItem<DroversWhistleItem> = ITEMS.registerItem("drovers_whistle", ::DroversWhistleItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1)
    })

    // Spawn eggs for the new combat's creatures
    val HOLLOW_SOLDIER_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("hollow_soldier_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.HOLLOW_SOLDIER.get())
    })
    val FALLEN_WARDEN_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("fallen_warden_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.FALLEN_WARDEN.get())
    })
    val HILL_TROLL_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("hill_troll_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.HILL_TROLL.get())
    })
    val DUSK_RIDER_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("dusk_rider_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.DUSK_RIDER.get())
    })
    val MIRE_HOLLOW_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("mire_hollow_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.MIRE_HOLLOW.get())
    })
    val CINDER_HOLLOW_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("cinder_hollow_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.CINDER_HOLLOW.get())
    })
    val RIME_HOLLOW_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("rime_hollow_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.RIME_HOLLOW.get())
    })
    val GLIMMER_HOLLOW_SPAWN_EGG: DeferredItem<SpawnEggItem> = ITEMS.registerItem("glimmer_hollow_spawn_egg", ::SpawnEggItem, UnaryOperator<Item.Properties> {
        it.spawnEgg(ModEntities.GLIMMER_HOLLOW.get())
    })

    // Weapons for the classes vanilla has none of (see combat/Weapons): iron and diamond made at the crafting
    // table, netherite at the smithing table. Damage counts the fist's 1; speed is attacks a second.

    /** Greatswords: 9, 10, 11 damage at 0.8. */
    val IRON_GREATSWORD = blade("iron_greatsword", ToolMaterial.IRON, 6f, -3.2f)
    val DIAMOND_GREATSWORD = blade("diamond_greatsword", ToolMaterial.DIAMOND, 6f, -3.2f)
    val NETHERITE_GREATSWORD = blade("netherite_greatsword", ToolMaterial.NETHERITE, 6f, -3.2f)

    /** Daggers: 4, 5, 6 damage at 2.2. */
    val IRON_DAGGER = blade("iron_dagger", ToolMaterial.IRON, 1f, -1.8f)
    val DIAMOND_DAGGER = blade("diamond_dagger", ToolMaterial.DIAMOND, 1f, -1.8f)
    val NETHERITE_DAGGER = blade("netherite_dagger", ToolMaterial.NETHERITE, 1f, -1.8f)

    /** Sabres: 5, 6, 7 damage at 1.8. */
    val IRON_SABRE = blade("iron_sabre", ToolMaterial.IRON, 2f, -2.2f)
    val DIAMOND_SABRE = blade("diamond_sabre", ToolMaterial.DIAMOND, 2f, -2.2f)
    val NETHERITE_SABRE = blade("netherite_sabre", ToolMaterial.NETHERITE, 2f, -2.2f)

    /** War hammers: 10, 11, 12 damage at 0.65. */
    val IRON_WAR_HAMMER = warHammer("iron_war_hammer", ToolMaterial.IRON)
    val DIAMOND_WAR_HAMMER = warHammer("diamond_war_hammer", ToolMaterial.DIAMOND)
    val NETHERITE_WAR_HAMMER = warHammer("netherite_war_hammer", ToolMaterial.NETHERITE)

    /** Every weapon above, in the order the creative tab shows them. */
    val WEAPONS: List<DeferredItem<Item>> = listOf(
        IRON_GREATSWORD, DIAMOND_GREATSWORD, NETHERITE_GREATSWORD,
        IRON_DAGGER, DIAMOND_DAGGER, NETHERITE_DAGGER,
        IRON_SABRE, DIAMOND_SABRE, NETHERITE_SABRE,
        IRON_WAR_HAMMER, DIAMOND_WAR_HAMMER, NETHERITE_WAR_HAMMER,
    )

    /** A blade made like vanilla's swords: it cuts cobwebs, takes sword enchantments, and [damage] and [speed] are vanilla's baselines. */
    private fun blade(name: String, material: ToolMaterial, damage: Float, speed: Float): DeferredItem<Item> =
        ITEMS.registerSimpleItem(name, UnaryOperator<Item.Properties> { tiered(material, it.sword(material, damage, speed)) })

    /** A hammer made like the mace, without its smash: all its weight goes into the swing. */
    private fun warHammer(name: String, material: ToolMaterial): DeferredItem<Item> =
        ITEMS.registerSimpleItem(name, UnaryOperator<Item.Properties> {
            val attributes = ItemAttributeModifiers.builder()
                .add(
                    Attributes.ATTACK_DAMAGE,
                    AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, 7.0 + material.attackDamageBonus(), AttributeModifier.Operation.ADD_VALUE),
                    EquipmentSlotGroup.MAINHAND,
                )
                .add(
                    Attributes.ATTACK_SPEED,
                    AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -3.35, AttributeModifier.Operation.ADD_VALUE),
                    EquipmentSlotGroup.MAINHAND,
                )
                .build()
            tiered(material, it.durability(material.durability()).repairable(material.repairItems()).enchantable(material.enchantmentValue())
                .component(DataComponents.TOOL, Tool(listOf(), 1f, 2, false))
                .attributes(attributes)
                .component(DataComponents.WEAPON, Weapon(1)))
        })

    /** Netherite doesn't burn. */
    private fun tiered(material: ToolMaterial, properties: Item.Properties): Item.Properties =
        if (material == ToolMaterial.NETHERITE) properties.fireResistant() else properties

    // ---- Tales (living-world/stories/design.md 7.6, 13.2): in no creative tab and no loot ----

    /** What a tale leaves a player who helped: one item for sixteen looks, its story in its TALE_KEEPSAKE component (see tales/TaleKeepsake). */
    val KEEPSAKE: DeferredItem<KeepsakeItem> = ITEMS.registerItem("keepsake", ::KeepsakeItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.UNCOMMON)
    })

    /** Only what a person in a tale's scene holds in a hand, drawn by its custom model data; never given out (see tales/TaleArt). */
    val TALE_PROP: DeferredItem<Item> = ITEMS.registerSimpleItem("tale_prop", UnaryOperator<Item.Properties> {
        it.stacksTo(1)
    })

    /** The little quill the tracker's tale rows, the three advancements and the Handbook page are marked with; never given out. */
    val TALE_QUILL: DeferredItem<Item> = ITEMS.registerSimpleItem("tale_quill", UnaryOperator<Item.Properties> {
        it.stacksTo(1)
    })
}
