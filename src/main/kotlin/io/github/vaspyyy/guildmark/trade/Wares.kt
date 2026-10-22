package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One thing the market hall's counter sells to a trader (living-world/trader/design.md, Part 3's Carriers section): a harness, a
 * donkey, a cart, the Warrant, a Wagon Bill, a hired guard. Each part that has one registers it from its own `register` with [Wares.add].
 *
 * - [id] is what the screen and the commands name it by; its words are `ware.guildmark.<id>` (and `.tip` for the tooltip).
 * - [price] is in Marks, taken whole before [action] runs.
 * - [gate] answers null if [player] may buy it, or the lang key of what is missing (a rung not reached, a Warrant not held, the
 *   standing not yet Trusted), which the screen shows greyed under the ware.
 * - [action] hands over what was bought; it answers false if it could not (nothing was taken, or it is given back).
 * - [scene] plays the counter scene, with the merchant at the counter (the harness taken from its peg, the donkey brought round), after
 *   [action] has run; null for a ware with none.
 */
class Ware(
    val id: String,
    val price: Int,
    val gate: (ServerPlayer) -> String? = { null },
    val action: (ServerPlayer) -> Boolean = { true },
    val scene: ((ServerPlayer, Townsperson) -> Unit)? = null,
)

/**
 * The hall counter's list of [Ware]s, in the order they were added. Nothing is added here: each part adds its own (Part 3 the
 * harness, the whistle, the donkey, the cart and the Wagon Bill, Part 4 the guard, Part 5 the Warrant), and the Carriers section of the
 * market screen (part D1) shows the list. With none added the list is empty and the section has nothing to show.
 */
object Wares {
    /** The lang key of why nothing can be bought: the trader's life is switched off (and with it every purchase). */
    const val OFF = "gui.guildmark.trade.off"

    /** The lang key of why nothing can be bought when it is the capital markets that are switched off (the trader's life needs them). */
    const val OFF_MARKETS = "gui.guildmark.trade.off.markets"

    private val wares = CopyOnWriteArrayList<Ware>()

    /** Adds [ware] to the counter, once, at setup. A second ware with the same id is a mistake and is refused. */
    fun add(ware: Ware) {
        require(wares.none { it.id == ware.id }) { "Two wares at the hall's counter are called ${ware.id}" }
        wares.add(ware)
    }

    /** Every ware, in the order added. */
    fun all(): List<Ware> = wares

    fun byId(id: String): Ware? = wares.firstOrNull { it.id == id }

    /**
     * What stands between [player] and [ware]: null if nothing does, or the lang key of what does ([OFF_MARKETS] when the capital markets are
     * switched off, [OFF] when it is the trader's life that is, else the ware's own gate). A ware's own gate is only asked once the trade is on.
     */
    fun missing(player: ServerPlayer, ware: Ware): String? = when {
        !Markets.enabled() -> OFF_MARKETS
        !TradeConfig.life() -> OFF
        else -> ware.gate(player)
    }
}
