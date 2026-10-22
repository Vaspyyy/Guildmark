package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.network.MarketHaulPayload
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The kinds of haul ticket (living-world/trader/design.md, Part 2). The ids are what is saved, and the order is the order they were
 * added in: new kinds go at the end, and none is ever moved or taken out.
 */
enum class HaulKind(val id: String) {
    /** 16 to 48 units, for any trader. */
    PARCEL("parcel"),

    /** 64 to 128 units, for a trader with a Warrant. */
    CONSIGNMENT("consignment"),

    /** A staple the town went short of, at up to twice the usual price; for a trader with a Warrant. */
    RELIEF("relief"),

    /** Goods for a festival, posted by whatever holds one ([Hauls.postFeast]); for a trader with a Warrant. */
    FEAST("feast"),
    ;

    companion object {
        fun byId(id: String): HaulKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A ticket as the listeners hear of it: number [id] at the capital whose heart is [capital], for [units] of [good] to be brought to
 * [shop] there for [pay] whole Marks, of the given [kind], to be filled by the market hour [due]. (Part 2 may add fields at the end,
 * with defaults.)
 */
data class Haul(
    val id: Int,
    val capital: BlockPos,
    val shop: Shop,
    val good: Good,
    val units: Int,
    val pay: Int,
    val kind: HaulKind,
    val due: Long,
)

/** Who takes a ticket and fills it: a player, or a House's carter for its owner (Part 5). Either way the [owner] is who is paid, and whose standing it raises. */
sealed interface Taker {
    val owner: UUID

    data class Player(override val owner: UUID) : Taker

    data class House(override val owner: UUID) : Taker
}

/**
 * A ticket filled, as [Hauls.Listener.delivered] hears of it: [taker] brought [units] of [good] to [shop] at the capital whose heart is
 * [capital] and was paid [pay] whole Marks for a ticket of the given [kind]. [from] is where most of the goods came from (the capital whose
 * heart it was bought at), or null if that is not known.
 */
data class Delivery(
    val taker: Taker,
    val capital: BlockPos,
    val shop: Shop,
    val good: Good,
    val units: Int,
    val pay: Int,
    val kind: HaulKind,
    val from: BlockPos?,
)

/**
 * Haul tickets (living-world/trader/design.md, Part 2): what a capital short of something posts to have it brought, the bookings
 * and deliveries against it, and the hauled ledger. This is the skeleton of it that the rest of the trader's life stands on
 * (build part A0), and nothing more: the types the other parts talk in ([HaulKind], [Haul], [Taker], [Delivery]), the [Listener] they
 * subscribe through, [carterCount], and the one payload the market screen will send. No ticket is ever posted, so nothing here is
 * ever heard or done; part A fills in the rest in this file and its neighbours (`Ledger`, `HaulCommand`).
 */
object Hauls {
    /**
     * Hears a ticket's life: posted, booked, filled (with what was delivered), lapsed for want of goods, and withdrawn because the
     * town no longer wants it. Each is called on the server, with the ticket as it stood. Subscribe once, at setup, with [listen].
     */
    interface Listener {
        fun posted(level: ServerLevel, haul: Haul) {}
        fun booked(level: ServerLevel, haul: Haul) {}
        fun delivered(level: ServerLevel, haul: Haul, delivery: Delivery) {}
        fun lapsed(level: ServerLevel, haul: Haul) {}
        fun withdrawn(level: ServerLevel, haul: Haul) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        listeners.add(listener)
    }

    /**
     * How many hired carters are on the road for a trading house just now: what raises the rate tickets are posted at (half a
     * ticket a day for each, up to the ceiling). Answers 0 until the House (build part G) sets it.
     */
    var carterCount: () -> Int = { 0 }

    /** Blocks between two capitals' hearts across the ground, as the freight reckons them (see [io.github.vaspyyy.guildmark.economy.Carriage]). */
    fun distance(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return Math.sqrt(dx * dx + dz * dz)
    }

    /** Registers the market screen's payload for a ticket (see [MarketHaulPayload]); part A adds the hourly run beside it. */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1").playToServer(MarketHaulPayload.TYPE, MarketHaulPayload.STREAM_CODEC, MarketHaulPayload::handle)
        }
    }

    /** What [player] asked to do ([MarketHaulPayload]'s TAKE, DELIVER or GIVE_UP) with ticket [haul] at the shop whose screen they have open. Does nothing until part A. */
    fun act(player: ServerPlayer, haul: Int, action: Int) {}
}
