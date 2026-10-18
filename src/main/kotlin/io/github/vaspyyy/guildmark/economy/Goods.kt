package io.github.vaspyyy.guildmark.economy

import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/**
 * The goods a capital makes, moves, eats and trades. [base] is a unit's price in Guild Marks when the town holds as
 * much as it likes to ([keep] days of its own use); [food] is how many meals a unit makes (a loaf is one) and
 * [share] how much of the town's eating it is by habit; [halfLife] is how many days until half of a store has gone
 * off (0: it keeps). Goods that aren't [traded] are the town's own: flour for the ovens, ale for the taps, tools
 * for its trades.
 */
enum class Good(
    val id: String,
    val base: Double,
    val keep: Double,
    val halfLife: Double = 0.0,
    val food: Double = 0.0,
    val share: Double = 0.0,
    val traded: Boolean = true,
) {
    // The fields and the granary, the mill and the bakehouse
    WHEAT("wheat", 0.04, 10.0, halfLife = 200.0),
    FLOUR("flour", 0.05, 2.0, halfLife = 60.0, traded = false),
    BREAD("bread", 0.17, 1.2, halfLife = 5.0, food = 1.0, share = 0.34),
    CARROT("carrot", 0.06, 4.0, halfLife = 20.0, food = 0.6, share = 0.10),
    POTATO("potato", 0.05, 5.0, halfLife = 24.0, food = 0.8, share = 0.14),
    BEETROOT("beetroot", 0.04, 4.0, halfLife = 20.0, food = 0.25, share = 0.03),
    APPLE("apple", 0.10, 4.0, halfLife = 14.0, food = 0.8, share = 0.06),

    // The boats
    COD("cod", 0.12, 2.0, halfLife = 2.0, food = 1.0, share = 0.06),
    SALMON("salmon", 0.15, 2.0, halfLife = 2.0, food = 1.2, share = 0.04),

    // The barns and the coops, by way of the butcher's smoker
    BEEF("beef", 0.25, 2.0, halfLife = 3.0, food = 1.6, share = 0.06),
    PORK("pork", 0.25, 2.0, halfLife = 3.0, food = 1.6, share = 0.05),
    MUTTON("mutton", 0.20, 2.0, halfLife = 3.0, food = 1.2, share = 0.05),
    CHICKEN("chicken", 0.15, 2.0, halfLife = 2.5, food = 1.2, share = 0.06),
    HONEY("honey", 0.40, 5.0, food = 1.2, share = 0.01),

    // Cloth, hides and the fletcher's stock
    WOOL("wool", 0.25, 10.0),
    STRING("string", 0.08, 10.0),
    LEATHER("leather", 0.30, 10.0),
    ARROW("arrow", 0.10, 6.0),
    FEATHER("feather", 0.04, 8.0),
    FLINT("flint", 0.05, 8.0),

    // The chandler's
    CANDLE("candle", 0.30, 8.0),
    BOTTLE("bottle", 0.06, 8.0),

    // The forge
    IRON("iron", 0.50, 10.0),
    COAL("coal", 0.12, 10.0),
    TOOLS("tools", 1.50, 10.0, traded = false),

    // The taps
    ALE("ale", 0.08, 2.0, halfLife = 6.0, traded = false);

    /** The item a unit of it is, for trading with players; null for the town's own goods. */
    val item: Item?
        get() = when (this) {
            WHEAT -> Items.WHEAT
            BREAD -> Items.BREAD
            CARROT -> Items.CARROT
            POTATO -> Items.POTATO
            BEETROOT -> Items.BEETROOT
            APPLE -> Items.APPLE
            COD -> Items.COD
            SALMON -> Items.SALMON
            BEEF -> Items.COOKED_BEEF
            PORK -> Items.COOKED_PORKCHOP
            MUTTON -> Items.COOKED_MUTTON
            CHICKEN -> Items.COOKED_CHICKEN
            HONEY -> Items.HONEY_BOTTLE
            WOOL -> Items.WOOL.pick(DyeColor.WHITE)
            STRING -> Items.STRING
            LEATHER -> Items.LEATHER
            ARROW -> Items.ARROW
            FEATHER -> Items.FEATHER
            FLINT -> Items.FLINT
            CANDLE -> Items.CANDLE
            BOTTLE -> Items.GLASS_BOTTLE
            IRON -> Items.IRON_INGOT
            COAL -> Items.COAL
            FLOUR, TOOLS, ALE -> null
        }

    /** The lang key of its name ("Bread"); a plural for counts is the same key plus ".many". */
    val key: String get() = "good.guildmark.$id"

    companion object {
        val FOODS: List<Good> = entries.filter { it.food > 0 }
        val TRADED: List<Good> = entries.filter { it.traded }

        fun byId(id: String): Good? = entries.firstOrNull { it.id == id }

        /** The good [item] is, if the market deals in it. */
        fun of(item: Item): Good? = TRADED.firstOrNull { it.item === item }
    }
}

/** Where a capital keeps goods: the market (everything for sale), and the stores of the places that make things. */
enum class Site(val id: String) {
    /** The stalls, the bakery's shelf and the market hall's store: everything a buyer can have. */
    MARKET("market"),
    /** The farms' barns and yards: the harvest and the herds' yield, until the carters take it in. */
    FARMS("farms"),
    /** Grain waiting for the millstones, and the flour they've ground. */
    MILL("mill"),
    /** Flour for the ovens. */
    BAKEHOUSE("bakehouse"),
    /** Iron and coal for the forge. */
    SMITHY("smithy"),
    /** Grain for the brewing and ale in the cellar. */
    TAVERN("tavern");

    companion object {
        fun byId(id: String): Site? = entries.firstOrNull { it.id == id }
    }
}
