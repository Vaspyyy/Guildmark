package io.github.vaspyyy.guildmark.city.plan

/** How a market display looks: the block it is ([id]), and so how it holds what it shows (see economy/WaresLayout). */
enum class Look(val id: String) {
    /** A low wicker basket of produce. */
    BASKET("produce_basket"),

    /** A crate of fish laid out on ice. */
    FISH("fish_crate"),

    /** Smoked meat hung from hooks over a board. */
    MEAT("meat_rack"),

    /** A trestle board of folded cloth, hides and string. */
    CLOTH("cloth_board"),

    /** A bin of arrows and a basket of feathers, flint in a tray before them. */
    ARROWS("arrow_rack"),

    /** A stepped tray of candles and bottles. */
    CANDLES("candle_tray"),

    /** A baker's shelf of loaves. */
    BREAD("bread_shelf"),

    /** Open sacks of grain. */
    SACKS("grain_sacks"),
}

/**
 * What a market display shows, as the plan sets it out: how it looks and which of the market's goods it holds, one to
 * three of them by their ids (economy/Goods.kt). The first of a look is what one put down by hand shows.
 */
enum class Wares(val look: Look, vararg goods: String) {
    GRAIN(Look.SACKS, "wheat"),
    ROOTS(Look.BASKET, "carrot", "potato", "beetroot"),
    APPLES(Look.BASKET, "apple"),
    FISH(Look.FISH, "cod", "salmon"),
    BEEF_PORK(Look.MEAT, "beef", "pork"),
    MUTTON_CHICKEN(Look.MEAT, "mutton", "chicken"),
    WOOL(Look.CLOTH, "wool"),
    HIDES(Look.CLOTH, "leather", "string"),
    FLETCHING(Look.ARROWS, "arrow", "feather", "flint"),
    CHANDLERY(Look.CANDLES, "candle", "honey", "bottle"),
    BREAD(Look.BREAD, "bread");

    /** Its goods by id, a lane of the display each (economy/WaresLayout), the first on the buyer's left or at the front. */
    val goods: List<String> = goods.toList()

    companion object {
        /** What a display of [look] put down by hand shows. */
        fun of(look: Look): Wares = entries.first { it.look == look }
    }
}
