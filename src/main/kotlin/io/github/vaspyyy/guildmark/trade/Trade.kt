package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.trade.carry.Carry
import io.github.vaspyyy.guildmark.trade.carry.CartHitch
import io.github.vaspyyy.guildmark.trade.carry.Laden
import net.neoforged.bus.api.IEventBus

/**
 * The trader's life (living-world/trader/design.md): haul tickets and the dealer's lot, the Word of the other towns and the Tally,
 * the Haul Board, carrying with pack animals, carts and wagons, the Hunt on the road, the ladder from Pedlar to a trading house, the
 * Carriers' Store and the Counting House, and the town's answer to a delivery. This is where each part registers itself.
 *
 * Every part adds one line to [register], under its own comment, so that nobody else has to touch the mod's setup again and two
 * parts never write the same line. What is here ahead of them is the skeleton (build part A0): the switches ([TradeConfig]), the
 * per-player record ([TradeBooks]), the command root ([TradeCommand]), the types tickets are spoken in ([Hauls]), the hall counter's
 * list ([Wares]), the carriers' list ([Carriers]), the ladder's gates ([Rungs], a stub that lets everything through) and the market
 * screen's hook (`client/trade/MarketPanel`). None of it does anything yet, and with it the game plays exactly as it did.
 */
object Trade {
    /** Registers the trader's life: called once, at the mod's setup. */
    fun register(modBus: IEventBus) {
        // X5: the sounds (the 37 events, silent until their files land; the operators' `trade sound` comes with them)
        TradeSounds.register(modBus)
        TradeSoundCommand.init()

        // X6: the voices (the operators' `trade say`, which has a townsperson say the trade's lines to hear them)
        TradeSayCommand.init()

        // A: hauls and the dealer's lot (the ticket payload; the hourly run, bookings and the ledger come with it)
        Hauls.register(modBus)
        HaulCommand.init()

        // B: the Word and the Tally
        Word.register(modBus)

        // C: the Haul Board

        // D: carrying (the pack animal, the cart, the wagon)
        Carry.register(modBus)
        // D2: the Chapman's Cart (the shafts, the drag, the whistle, the counter's cart and `trade cart`)
        CartHitch.register(modBus)
        // A's ledger tells D's look which goods came far: bought at least `tradeHaulMin` blocks from where the trader stands now
        Laden.hauled = { player, good -> Ledger.count(player.level(), player.uuid, good, player.blockPosition()) }

        // E: the Hunt and the hired guard

        // F: the ladder, the Carriers' Store and the Counting House
        Rungs.register()

        // G: the trading house

        // H: the town answers
        WorldAnswers.register()
    }
}
