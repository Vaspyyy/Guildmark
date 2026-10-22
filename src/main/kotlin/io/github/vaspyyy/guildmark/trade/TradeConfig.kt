package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.works.Works
import net.neoforged.neoforge.common.ModConfigSpec
import kotlin.math.min

/**
 * The trader's switches and numbers (living-world/trader/design.md section 9), read the way the other systems' are: here, with
 * the guard every config read in the mod has, so a test that never loaded the config gets the defaults.
 *
 * Each reader answers for the system, not for the raw switch: [life] is the master (the markets running, and "Trader's Life"),
 * and every system's reader folds it in with whatever else that system needs (the table in section 9), so a part asks one
 * question, `TradeConfig.hauls()`, and never has to remember that the tickets need the markets. [setting] is the raw switch,
 * for a command that wants to say which of a system's switches is the one that is off.
 *
 * Part 0's six (the herd stop, the carry's ease and the owed Trade XP) are not here: they belong to the economy and are read
 * where they work (see Economy.herdStop, Market.settle, Suppliers.settle).
 */
object TradeConfig {
    /** The most carters a House may hire, and the capitals that earn one (`tradeCarters` left at -1: a quarter of those open, at most six). */
    const val CARTERS_MOST = 6
    private const val CAPITALS_PER_CARTER = 4

    private inline fun <T> read(default: T, get: () -> T): T = try {
        get()
    } catch (e: IllegalStateException) {
        default
    }

    /** [value]'s setting, or [default] if the config has not been loaded (the raw switch, with nothing it needs folded in). */
    fun setting(value: ModConfigSpec.BooleanValue, default: Boolean = true): Boolean = read(default) { value.get() }

    // ---- The trader's life, and each system of it ----

    /** The trader's life as a whole (tradeLife; the markets running). Off, no ticket, board, Word, Hunt or new House trip, and nothing to buy; records stay. */
    fun life(): Boolean = Markets.enabled() && setting(GuildmarkConfig.TRADE_LIFE)

    /** The Word: the snapshots of the markets, the boards and the Tally's sights (tradeWord). Off, no snapshots; the boards are blank. */
    fun word(): Boolean = life() && setting(GuildmarkConfig.TRADE_WORD)

    /** Hours old the news of another town is before the road adds to it (tradeWordBase). */
    fun wordBase(): Double = read(12.0) { GuildmarkConfig.TRADE_WORD_BASE.get() }

    /** Hours the road adds to the age of the news for each block between two towns (tradeWordPerBlock). */
    fun wordPerBlock(): Double = read(0.024) { GuildmarkConfig.TRADE_WORD_PER_BLOCK.get() }

    /** Haul tickets, the dealer's share and the haul room (tradeHauls). Off, no ticket is posted, open ones lapse unpaid, and the stalls are as they were. */
    fun hauls(): Boolean = life() && setting(GuildmarkConfig.TRADE_HAULS)

    /** The most tickets a capital posts in a day (tradeHaulRate), and the most that rises to as carriers join (tradeHaulRateMax). */
    fun haulRate(): Double = read(2.5) { GuildmarkConfig.TRADE_HAUL_RATE.get() }
    fun haulRateMax(): Double = read(4.0) { GuildmarkConfig.TRADE_HAUL_RATE_MAX.get() }

    /** The share of a ticket's pay held at booking (tradeHaulDeposit). */
    fun haulDeposit(): Double = read(0.1) { GuildmarkConfig.TRADE_HAUL_DEPOSIT.get() }

    /** The share of the price a stall pays for hauled goods (tradeDealerShare; 0.6 is the ordinary share, so no edge). */
    fun dealerShare(): Double = read(0.8) { GuildmarkConfig.TRADE_DEALER_SHARE.get() }

    /** How far, in blocks, a good must have come to count as hauled (tradeHaulMin), and the days of the caravans' load added to a stall's room for it (tradeHaulRoomDays). */
    fun haulMin(): Int = read(600) { GuildmarkConfig.TRADE_HAUL_MIN.get() }
    fun haulRoomDays(): Int = read(2) { GuildmarkConfig.TRADE_HAUL_ROOM_DAYS.get() }

    /** Carrying: the Carriers section, harnesses, carts and wagons (tradeCarry). Off, the section is greyed and nothing follows, but every container opens. */
    fun carry(): Boolean = life() && setting(GuildmarkConfig.TRADE_CARRY)

    /** The Carrier's Wagon alone (tradeWagon; needs carrying). */
    fun wagon(): Boolean = carry() && setting(GuildmarkConfig.TRADE_WAGON)

    /** How much a cart drags whoever pulls it (tradeCartSlow), and how many blocks ahead a wagon waits (tradeWagonRein). */
    fun cartSlow(): Double = read(0.15) { GuildmarkConfig.TRADE_CART_SLOW.get() }
    fun wagonRein(): Int = read(20) { GuildmarkConfig.TRADE_WAGON_REIN.get() }

    /** The Hunt (tradeHunt; needs the roads' ambushes, which need the caravans and the outlaws). Off, no band hunts anyone. */
    fun hunt(): Boolean = life() && Caravans.ambushes() && setting(GuildmarkConfig.TRADE_HUNT)

    /** The Hunt's chance factor (tradeHuntChance), the least value of goods worth a strike in Marks (tradeHuntLaden) and how far a camp sees (tradeHuntReach, blocks). */
    fun huntChance(): Double = read(1.0) { GuildmarkConfig.TRADE_HUNT_CHANCE.get() }
    fun huntLaden(): Int = read(25) { GuildmarkConfig.TRADE_HUNT_LADEN.get() }
    fun huntReach(): Int = read(160) { GuildmarkConfig.TRADE_HUNT_REACH.get() }

    /** Whether a party may kill (tradeHuntKills), and the purse in Marks for each outlaw downed (tradeHuntPurse). */
    fun huntKills(): Boolean = setting(GuildmarkConfig.TRADE_HUNT_KILLS, false)
    fun huntPurse(): Int = read(2) { GuildmarkConfig.TRADE_HUNT_PURSE.get() }

    /** A hired guard's wage, in Marks a day (tradeGuardWage). */
    fun guardWage(): Double = read(2.5) { GuildmarkConfig.TRADE_GUARD_WAGE.get() }

    /** The ladder's gates (tradeRungs). Off, every gate passes. */
    fun rungs(): Boolean = setting(GuildmarkConfig.TRADE_RUNGS)

    /** The Carriers' Store and the Counting House (tradeStores; needs town works). Off, neither is offered; built ones stand. */
    fun stores(): Boolean = life() && Works.enabled() && setting(GuildmarkConfig.TRADE_STORES)

    /** Hired carters (tradeHouse; needs the Stores and the tickets). Off, none is hired and no new trip is set out. */
    fun house(): Boolean = stores() && hauls() && setting(GuildmarkConfig.TRADE_HOUSE)

    /**
     * The most carters a House may hire while [open] capitals are open (tradeCarters): the setting, or, left at -1, a quarter
     * of them and at most [CARTERS_MOST] (0 is none). It does not look at [house]: that is the switch for the House itself.
     */
    fun carters(open: Int): Int {
        val set = read(-1) { GuildmarkConfig.TRADE_CARTERS.get() }
        return if (set >= 0) min(set, CARTERS_MOST) else min(CARTERS_MOST, open / CAPITALS_PER_CARTER)
    }

    /** A hired carter's wage in Marks a day (tradeCarterWage), and the days a House goes on without its owner (tradeHouseOffline). */
    fun carterWage(): Int = read(3) { GuildmarkConfig.TRADE_CARTER_WAGE.get() }
    fun houseOffline(): Int = read(7) { GuildmarkConfig.TRADE_HOUSE_OFFLINE.get() }

    /** The town's answer to a delivery: the Fortunes' reason, favours, rumours (tradeWorld). Off, deliveries still pay. */
    fun world(): Boolean = life() && setting(GuildmarkConfig.TRADE_WORLD)

    /** The cheering of a delivery in the square, the scene alone (tradeCheer; needs the town's answer). */
    fun cheer(): Boolean = world() && setting(GuildmarkConfig.TRADE_CHEER)
}
