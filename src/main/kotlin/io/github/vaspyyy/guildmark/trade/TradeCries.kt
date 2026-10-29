package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.townsfolk.Crier
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import java.util.Random

/**
 * What the crier adds to the noon cry for the trader (living-world/trader/design.md, Part 1 and Part 6; build part C), two calls from
 * [Crier.news]: the haul tickets up on the Haul Board ("Carriers are wanted at the Haul Board!", with what is wanted and by whom in grey
 * under it), and the town's thanks to those who brought goods in from the road since the noon before ("Our thanks to those who brought
 * goods in from the road!", the names in grey), followed by "Bells on the road!" when one of them is a Hawker or better (the rung that
 * goes with a pack animal and its bell). The tickets are cried with the haul tickets on; the thanks with the town's answer on too
 * (`tradeWorld`). Off, the cry is as it was.
 */
object TradeCries {
    /** The most tickets named under the haul cry, and the most names under the thanks. */
    private const val TICKETS = 3
    private const val NAMES = 4

    /** The haul cry at [entry]'s noon (market hour [noon]): the tickets open there now, the dearest named; null if there are none. */
    fun hauls(level: ServerLevel, entry: Markets.Entry, noon: Long, random: Random): Crier.Cry? {
        if (!TradeConfig.hauls()) return null
        val open = Hauls.open(level, entry.capital).filter { it.due > noon }
        if (open.isEmpty()) return null
        val line = "crier_hauls_${random.nextInt(3)}"
        val text = said(line)
        for ((k, h) in open.sortedByDescending { it.pay }.take(TICKETS).withIndex()) {
            val what = Component.translatable("supplier.guildmark.what", h.open, Component.translatable(h.good.key + ".many"))
            text.append(Component.literal(if (k == 0) " " else "; ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("chat.guildmark.crier.hauls", what, Orders.shopName(h.shop), Orders.marks(TallyLinks.part(h.pay, h.open, h.units))).withStyle(ChatFormatting.GRAY))
        }
        if (open.size > TICKETS) text.append(Component.literal("; ").append(Component.translatable("chat.guildmark.crier.hauls.more", open.size - TICKETS)).withStyle(ChatFormatting.GRAY))
        return Crier.Cry(line, text, null, 1)
    }

    /**
     * The town's thanks at [entry]'s noon (market hour [noon]) to those who filled its tickets since the noon before, named in grey; and, if one of them
     * holds the Hawker's rung or higher, the bells on the road after it. None if nobody filled one.
     */
    fun thanks(level: ServerLevel, entry: Markets.Entry, noon: Long): List<Crier.Cry> {
        if (!TradeConfig.hauls() || !TradeConfig.world()) return listOf()
        val who = Hauls.fills(entry.capital).filter { it.hour > noon - 24 && it.hour <= noon && it.who.isNotEmpty() }.map { it.who }.distinct()
        if (who.isEmpty()) return listOf()
        val line = "crier_hauls_thanks"
        val text = said(line).append(Component.literal(" " + who.take(NAMES).joinToString(", ") + if (who.size > NAMES) ", ..." else "").withStyle(ChatFormatting.GRAY))
        val out = arrayListOf(Crier.Cry(line, text, null, 1))
        val players = level.server.playerList
        if (who.any { name -> players.getPlayerByName(name)?.let { Rungs.of(it) >= Rung.HAWKER } == true }) {
            out.add(Crier.Cry("crier_hauls_bells", said("crier_hauls_bells"), null, 1))
        }
        return out
    }

    private fun said(line: String): MutableComponent = Component.translatable("say.guildmark.$line")
}
