package io.github.vaspyyy.guildmark.faction

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.quest.ContractState
import java.util.Optional

/**
 * The work between chapters the factions have given a player, kept on the player rather than on the note: the job each
 * has them on, so putting the note away and asking again brings back the same job, and how many jobs each gave today.
 */
data class WorkOrders(val orders: Map<String, WorkOrder> = mapOf()) {
    fun of(faction: Faction): WorkOrder = orders[faction.id] ?: WorkOrder()

    fun with(faction: Faction, order: WorkOrder): WorkOrders = WorkOrders(orders + (faction.id to order))

    companion object {
        val CODEC: Codec<WorkOrders> = Codec.unboundedMap(Codec.STRING, WorkOrder.CODEC).xmap(::WorkOrders, WorkOrders::orders)
    }
}

/**
 * One faction's work for one player. [serial] counts the jobs it has given them and names the latest on its note
 * ("<leader>:work:<serial>"; 0 while none has been, when a note from before jobs were kept here still counts once). The
 * job is [pick] of the faction's own, rated at [rank] and set up as [contract], until it's handed in or runs out;
 * [given] jobs were given on [day].
 */
data class WorkOrder(
    val serial: Int = 0,
    val pick: Int = -1,
    val rank: Int = 0,
    val contract: ContractState? = null,
    val day: Long = -1,
    val given: Int = 0,
) {
    /** Whether they're on a job still to do at [gameTime]. */
    fun open(gameTime: Long): Boolean = pick >= 0 && contract != null && !contract.isExpired(gameTime)

    /** How many jobs were given on [today]. */
    fun givenOn(today: Long): Int = if (day == today) given else 0

    /** The job handed in or given up: nothing open, though the day's count stands. */
    fun closed(): WorkOrder = copy(serial = serial.coerceAtLeast(1), pick = -1, contract = null)

    companion object {
        val CODEC: Codec<WorkOrder> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("serial", 0).forGetter(WorkOrder::serial),
                Codec.INT.optionalFieldOf("pick", -1).forGetter(WorkOrder::pick),
                Codec.INT.optionalFieldOf("rank", 0).forGetter(WorkOrder::rank),
                ContractState.CODEC.optionalFieldOf("contract").forGetter { Optional.ofNullable(it.contract) },
                Codec.LONG.optionalFieldOf("day", -1L).forGetter(WorkOrder::day),
                Codec.INT.optionalFieldOf("given", 0).forGetter(WorkOrder::given),
            ).apply(i) { serial, pick, rank, contract, day, given -> WorkOrder(serial, pick, rank, contract.orElse(null), day, given) }
        }
    }
}
