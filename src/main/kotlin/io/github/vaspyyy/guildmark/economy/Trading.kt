package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import kotlin.math.floor

/**
 * Why a good's price is well off its usual, as the stall keeper would put it ("Bread is dear: the ovens stood cold
 * for want of flour"). [dear] is which way it's off; [key] takes the good's name.
 */
enum class Why(val id: String, val dear: Boolean, val season: Boolean = false) {
    /** Trouble or luck under way (see [Quote.event], which names it). */
    EVENT_DEAR("event", true),
    EVENT_CHEAP("event", false),
    /** Players have bought a lot of it lately, or sold a lot. */
    BOUGHT_UP("bought_up", true),
    SOLD_IN("sold_in", false),
    /** The bakehouse ran out of flour, or the mill out of grain. */
    NO_FLOUR("no_flour", true),
    NO_GRAIN("no_grain", true),
    /** Fewer hands make it than the town was built for. */
    SHORT_HANDED("short_handed", true),
    /** The ovens rest on the seventh day. */
    REST_DAY("rest_day", true),
    /** The fields have it, but the carts haven't brought it in. */
    CARTS_BEHIND("carts_behind", true),
    /** The town's tools are worn out, so everything comes slower. */
    WORN_TOOLS("worn_tools", true),
    /** The town can't make enough of it, so most of it comes by caravan. */
    FROM_AFAR("from_afar", true),
    /** A festival day: the whole town is feasting. */
    FEAST("feast", true, true),
    /** Every hearth burns more coal in the cold, and few are lit in the warm days. */
    COLD("cold", true, true),
    WARM("warm", false, true),
    /** The season has little coming in from where it's made, or plenty (see [Seasonal.Yield]). */
    FIELDS_LEAN("fields_lean", true, true),
    FIELDS_FULL("fields_full", false, true),
    ORCHARD_LEAN("orchard_lean", true, true),
    ORCHARD_FULL("orchard_full", false, true),
    HERDS_LEAN("herds_lean", true, true),
    HERDS_FULL("herds_full", false, true),
    FLEECE_LEAN("fleece_lean", true, true),
    FLEECE_FULL("fleece_full", false, true),
    COOPS_LEAN("coops_lean", true, true),
    COOPS_FULL("coops_full", false, true),
    /** Nothing in particular: there's simply little of it about, or plenty. */
    SCARCE("scarce", true),
    PLENTY("plenty", false);

    val key: String get() = if (this == EVENT_DEAR || this == EVENT_CHEAP) "market.guildmark.why.event" else "market.guildmark.why.$id"
}

/**
 * What a shop can tell a player about one of its goods: whole units in stock, a unit's price now and as it usually
 * goes, the lot a player can buy now and the one the market will buy, how many more it takes, which way the price
 * is going (1 up, -1 down, against yesterday's close), and why it's well off its usual, if it is.
 */
class Quote(
    val good: Good,
    val stock: Int,
    val price: Double,
    val usual: Double,
    val buy: Lot?,
    val sell: Lot?,
    val wants: Int,
    val trend: Int,
    val why: Why?,
    /** The trouble or luck behind [why], if that's what it is. */
    val event: EventKind?,
    /**
     * The waylaid wagon behind [why], if it's that (see [Caravans]): the WAYLAID event itself, for where it was taken
     * and what it carried, when [event] is WAYLAID, or when the mill or the bakehouse stood idle for the grain or
     * flour it carried.
     */
    val trouble: Event? = null,
) {
    /** Where on the road the waylaid wagon behind its price was taken, if one was ("ford"; see [Caravans.PLACES]). */
    val place: String? get() = trouble?.place?.takeIf { it.isNotEmpty() }
}

/**
 * What came of a player's offer: [lot] is what changed hands, or what would have; [own] is how many of the goods sold
 * had come off the market's own stalls lately (see [Suppliers.sold]).
 */
class Deal(val outcome: Outcome, val lot: Lot? = null, val own: Int = 0) {
    enum class Outcome {
        DONE,
        /** The market has none to sell, or wants no more. */
        NONE,
        /** Not enough Guild Marks for the lot. */
        POOR,
        /** Not enough of the goods for the lot. */
        LACKING,
        /** All they have of it is salvage off the capital's own waylaid wagon (see [Marked]): its keepers won't buy that back. */
        OWN_WAGON,
    }
}

/**
 * Players' trade with a capital's market: quotes for its goods, and buying and selling a lot at a time for Guild
 * Marks (see [Lots] for how lots are priced). Everything comes from and goes into the market's own stock, and
 * every lot moves the price.
 */
object Trading {
    /** How far off its usual a price has to be for the stall to say why. */
    private const val OFF = 1.2

    /** Of what the town likes to keep, how much players have to buy or sell for it to be the reason. */
    private const val A_LOT = 0.25

    /** How far the season has to slow what comes in of a good (or speed it) for the season to be the reason. */
    private const val LEAN = 0.8
    private const val FULL = 1.2

    /** What [entry]'s market says of [good], the lot it buys paying [share] of its price (a supplier's standing lifts it, see [Suppliers.share]). */
    fun quote(entry: Markets.Entry, good: Good, share: Double = Lots.SELL_SHARE): Quote {
        val m = entry.market
        val town = entry.town
        val price = m.price(town, good)
        val usual = good.base * m.freight(good)
        val (why, event) = why(entry, good, price / usual)
        val trouble = when {
            event == EventKind.WAYLAID -> m.waylaid()
            // The mill or the bakehouse idle for the grain or flour a waylaid wagon carried
            why == Why.NO_GRAIN || why == Why.NO_FLOUR -> m.waylaid()?.takeIf { Good.WHEAT in it.goods || Good.FLOUR in it.goods }
            else -> null
        }
        return Quote(
            good, floor(m.stock(good) + 1e-6).toInt(), price, usual, Lots.buy(m, town, good), Lots.sell(m, town, good, share),
            Lots.wants(m, town, good), m.trend(town, good), why, event, trouble,
        )
    }

    /**
     * The lang key for why [q]'s price stands where it does (it takes the good's name): the reason, the trouble or luck
     * behind it, and for a waylaid wagon where on the road it was taken ("market.guildmark.why.event.waylaid.ford").
     */
    fun reasonKey(q: Quote): String {
        val why = q.why ?: return ""
        val event = q.event ?: return why.key
        val place = q.place?.takeIf { event == EventKind.WAYLAID && it in Caravans.PLACES && it != "road" }
        return "${why.key}.${event.id}" + (place?.let { ".$it" } ?: "")
    }

    /** [player] buys a lot of [good]: their Marks for the goods, which the capital remembers they bought there (see [Suppliers]). */
    fun buy(player: ServerPlayer, entry: Markets.Entry, good: Good): Deal {
        val item = good.item ?: return Deal(Deal.Outcome.NONE)
        val lot = Lots.buy(entry.market, entry.town, good) ?: return Deal(Deal.Outcome.NONE)
        if (TownControl.marks(player) < lot.marks) return Deal(Deal.Outcome.POOR, lot)
        TownControl.pay(player, lot.marks)
        give(player, item, lot.count)
        Lots.bought(entry.market, good, lot)
        Markets.get(player.level()).changed()
        Suppliers.purchased(player.level(), entry.capital, player.uuid, good, lot.count)
        return Deal(Deal.Outcome.DONE, lot)
    }

    /**
     * [player] sells the market a lot of [good]: the goods for its Marks, at the share their standing there earns them,
     * and half a point of standing for each Mark (none for goods that came off its own stalls lately).
     */
    fun sell(player: ServerPlayer, entry: Markets.Entry, good: Good): Deal {
        val item = good.item ?: return Deal(Deal.Outcome.NONE)
        val level = player.level()
        val lot = Lots.sell(entry.market, entry.town, good, Suppliers.share(level, entry.capital, player.uuid)) ?: return Deal(Deal.Outcome.NONE)
        // Salvage off its own waylaid wagons it won't buy back: that's to be handed in (see Marked)
        val free = count(player.inventory, item, entry.capital)
        if (free < lot.count) {
            return Deal(if (free == 0 && count(player.inventory, item) > 0) Deal.Outcome.OWN_WAGON else Deal.Outcome.LACKING, lot)
        }
        take(player.inventory, item, lot.count, entry.capital)
        give(player, ModItems.GUILD_MARK.get(), lot.marks)
        Lots.sold(entry.market, good, lot)
        Markets.get(level).changed()
        val own = Suppliers.sold(level, entry.capital, player.uuid, good, lot.count, lot.marks)
        return Deal(Deal.Outcome.DONE, lot, own)
    }

    /** How many of [item] [inventory] holds. */
    fun count(inventory: Inventory, item: Item): Int =
        ContainerHelper.clearOrCountMatchingItems(inventory, { it.`is`(item) }, Int.MAX_VALUE, true)

    /** How many of [item] [inventory] holds that the capital whose heart is [capital] will take: none off its own waylaid wagons (see [Marked]). */
    fun count(inventory: Inventory, item: Item, capital: BlockPos): Int =
        ContainerHelper.clearOrCountMatchingItems(inventory, { it.`is`(item) && !Marked.home(it, capital) }, Int.MAX_VALUE, true)

    /** Takes [count] of [item] out of [inventory], none of it off the waylaid wagons of the capital whose heart is [capital]. */
    fun take(inventory: Inventory, item: Item, count: Int, capital: BlockPos) {
        ContainerHelper.clearOrCountMatchingItems(inventory, { it.`is`(item) && !Marked.home(it, capital) }, count, false)
    }

    /** Puts [count] of [item] into [player]'s inventory, a stack at a time (what doesn't fit drops at their feet). */
    fun give(player: ServerPlayer, item: Item, count: Int) {
        var left = count
        val most = ItemStack(item).maxStackSize
        while (left > 0) {
            val n = minOf(left, most)
            player.inventory.placeItemBackInInventory(ItemStack(item, n), Prediction.SERVER_ONLY)
            left -= n
        }
    }

    /** Why [good]'s price stands at [off] times its usual, if that's far enough off to say. */
    private fun why(entry: Markets.Entry, good: Good, off: Double): Pair<Why?, EventKind?> {
        val m = entry.market
        val town = entry.town
        val target = town.target(good)
        if (off >= OFF) {
            m.events(good).firstOrNull { it.factor < 1.0 || it.kind == EventKind.FEVER }?.let { return Why.EVENT_DEAR to it.kind }
            if (m.today(Tally.BOUGHT, good) + m.yesterday(Tally.BOUGHT, good) * 0.5 >= target * A_LOT) return Why.BOUGHT_UP to null
            if (good == Good.BREAD) {
                if (m.idle[Maker.MILL.ordinal] + m.idleYesterday[Maker.MILL.ordinal] > 0) return Why.NO_GRAIN to null
                if (m.idle[Maker.OVENS.ordinal] + m.idleYesterday[Maker.OVENS.ordinal] > 0) return Why.NO_FLOUR to null
                if (Conditions(m.hour).restDay) return Why.REST_DAY to null
            }
            val felt = Markets.felt(entry)
            if (felt.feast && good.food > 0) return Why.FEAST to null
            if (shortHanded(town, good)) return Why.SHORT_HANDED to null
            if (good in FARMED && m[Site.FARMS, good] >= town.use(good) * 0.5) return Why.CARTS_BEHIND to null
            if (good in MADE && m.stock(Good.TOOLS) < 1.0) return Why.WORN_TOOLS to null
            season(felt, good, dear = true)?.let { return it to null }
            if (m.freight(good) >= 1.0 + Economy.FREIGHT * 0.5) return Why.FROM_AFAR to null
            return Why.SCARCE to null
        }
        if (off <= 1.0 / OFF) {
            m.events(good).firstOrNull { it.factor > 1.0 }?.let { return Why.EVENT_CHEAP to it.kind }
            if (m.today(Tally.SOLD, good) + m.yesterday(Tally.SOLD, good) * 0.5 >= target * A_LOT) return Why.SOLD_IN to null
            season(Markets.felt(entry), good, dear = false)?.let { return it to null }
            return Why.PLENTY to null
        }
        return null to null
    }

    /** The season as the reason [good] is [dear] (or cheap), if it's pulling that way far enough: the cold or the warm for coal. */
    private fun season(felt: Conditions, good: Good, dear: Boolean): Why? {
        if (good == Good.COAL) return if (dear && felt.heating >= FULL) Why.COLD else if (!dear && felt.heating <= LEAN) Why.WARM else null
        val pace = felt.pace(good)
        if (if (dear) pace > LEAN else pace < FULL) return null
        val from = Seasonal.yieldOf(good) ?: return null
        return if (dear) from.lean else from.full
    }

    /** Whether fewer of those who make [good] are living than the town was built with. */
    private fun shortHanded(town: Town, good: Good): Boolean {
        val built = town.planned ?: return false
        return when (good) {
            Good.BREAD -> town.bakers < built.bakers
            in FARMED -> town.farmers < built.farmers
            in Economy.CATCH.keys -> town.fishers < built.fishers
            Good.ARROW -> town.fletchers < built.fletchers
            else -> false
        }
    }

    /** What the fields, barns, coops and orchards give, which the carters bring in. */
    private val FARMED = Economy.FIELD.keys + Economy.BARN.keys + Economy.COOP.keys + Good.APPLE

    /** What the town's own hands make with tools. */
    private val MADE = FARMED + Economy.CATCH.keys + listOf(Good.BREAD, Good.ARROW)
}
