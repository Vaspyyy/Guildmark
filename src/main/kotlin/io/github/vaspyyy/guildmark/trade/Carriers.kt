package io.github.vaspyyy.guildmark.trade

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Something a trader carries goods in besides their own pack (living-world/trader/design.md, Part 3): a pack animal's panniers, a
 * cart, a wagon. [holdings] answers the containers of this kind that are [player]'s and within [radius] blocks of them now: none if
 * they have none there.
 */
fun interface Carrier {
    fun holdings(player: ServerPlayer, radius: Double): List<Container>
}

/**
 * The registry of carriers. The laden look, delivering a ticket, goods lost on the road and the Hunt's share all read the goods "within
 * eight blocks" from here, so a new kind of carrier is added once, from its own `register`, and every one of them sees it. The player's own
 * pack is the first entry; the rest come with the parts that make them (Part 3: the pack animal, the cart, the wagon).
 */
object Carriers {
    /** How far from a trader, in blocks, a carrier's goods still count as theirs. */
    const val REACH = 8.0

    private val kinds = CopyOnWriteArrayList<Carrier>().apply {
        add(Carrier { player, _ -> listOf(player.inventory) })
    }

    /** Adds [carrier], once, at setup. */
    fun add(carrier: Carrier) {
        kinds.add(carrier)
    }

    /** Every container of [player]'s within [radius] blocks that holds their goods: their pack first, then each kind of carrier in the order added. */
    fun holdings(player: ServerPlayer, radius: Double = REACH): List<Container> = kinds.flatMap { it.holdings(player, radius) }
}
