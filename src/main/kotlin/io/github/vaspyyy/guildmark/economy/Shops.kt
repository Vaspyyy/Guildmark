package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos

/**
 * The shops of a capital's market and the goods each deals in: the stalls by the trade they're set up round (field
 * crops at a composter, fish and fruit from barrels, the butcher's smoked meat, cloth and hides at a loom, the
 * fletcher's stock, the chandler's candles, honey and bottles at a cauldron), the bakery's bread, the smithy's iron
 * and coal, the fishmongers on the quay, and the market hall, which deals in everything. All of them sell from and
 * buy into the one market (see [Market.stock]), so what one sells out of, the others are out of too. The inn sells
 * nothing over a counter: its keeper's screen holds only the inn's orders (see [Orders]).
 */
enum class Shop(val id: String, val goods: List<Good>) {
    PRODUCE("produce", listOf(Good.WHEAT, Good.CARROT, Good.POTATO, Good.BEETROOT)),
    FISH_AND_FRUIT("fish_and_fruit", listOf(Good.COD, Good.SALMON, Good.APPLE)),
    BUTCHER("butcher", listOf(Good.BEEF, Good.PORK, Good.MUTTON, Good.CHICKEN)),
    CLOTH("cloth", listOf(Good.WOOL, Good.STRING, Good.LEATHER)),
    FLETCHER("fletcher", listOf(Good.ARROW, Good.FEATHER, Good.FLINT)),
    CHANDLER("chandler", listOf(Good.CANDLE, Good.HONEY, Good.BOTTLE)),
    BAKERY("bakery", listOf(Good.BREAD)),
    SMITHY("smithy", listOf(Good.IRON, Good.COAL)),
    QUAY("quay", listOf(Good.COD, Good.SALMON)),
    HALL("hall", Good.TRADED),
    INN("inn", listOf());

    /** The lang key of its name ("The Bakery"). */
    val key: String get() = "shop.guildmark.$id"

    companion object {
        fun byId(id: String): Shop? = entries.firstOrNull { it.id == id }

        /** The stall set up round a [trade] block, if it's one of the market's. */
        fun of(trade: M): Shop? = when (trade) {
            M.COMPOSTER -> PRODUCE
            M.BARREL -> FISH_AND_FRUIT
            M.SMOKER -> BUTCHER
            M.LOOM -> CLOTH
            M.FLETCHING_TABLE -> FLETCHER
            M.CAULDRON -> CHANDLER
            else -> null
        }

        /** The shop [p] keeps, if they keep one: the stall they stand at, the bakery, the smithy, the quay, the hall or the inn. */
        fun kept(p: Townsperson): Shop? {
            val places = p.life?.places ?: return null
            return when (p.role) {
                TownRole.BAKER -> BAKERY
                TownRole.SMITH -> SMITHY
                TownRole.INNKEEPER -> INN
                TownRole.MERCHANT -> at(places, p.post ?: p.work ?: p.blockPosition())
                else -> null
            }
        }

        /** The shop kept from [pos]: the stall whose keeper stands there, the market hall, or else the fish market on the quay. */
        fun at(places: CityPlaces, pos: BlockPos): Shop {
            places.stalls.firstOrNull { it.keeper.pos.distSqr(pos) <= NEAR }?.let { stall -> of(stall.trade)?.let { return it } }
            if (places.lodgingAt(pos)?.kind == Kind.MARKET_HALL.name) return HALL
            return if (places.harbour) QUAY else HALL
        }

        /** How near a stall's keeper spot (squared) someone has to stand to keep it. */
        private const val NEAR = 4.0
    }
}
