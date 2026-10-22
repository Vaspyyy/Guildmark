package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.trade.Carriers
import io.github.vaspyyy.guildmark.trade.TradeBooks
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min

/**
 * What the other players see of a trader's load (living-world/trader/design.md, Part 3, "The laden look"): the [level] of the
 * pack on their back, 0 for none to 3 for the tall frame, and the [marks] it is worth, which sets how loudly and how high the
 * pots in it clink. It is the synced attachment [Carry.LADEN]: set only while the level is above 0 (a player with no load has no
 * attachment at all) and never saved, since it is worked out again within two seconds of anyone arriving.
 */
data class LadenState(val level: Int = 0, val marks: Int = 0) {
    companion object {
        val NONE = LadenState()

        val STREAM_CODEC: StreamCodec<ByteBuf, LadenState> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LadenState::level,
            ByteBufCodecs.VAR_INT, LadenState::marks,
            ::LadenState,
        )
    }
}

/**
 * The laden look: the Marks value of the trade goods a trader carries within eight blocks of them (their pack, and what each
 * kind of carrier in [Carriers] holds of theirs), each unit at its good's base price, hauled goods at [HAULED_FACTOR] times that;
 * counted every [PERIOD] ticks for a player who has taken up the trade (who holds a Tally: [TradeBooks.Book.started]) and shown
 * to everyone as a pack on their back in four steps ([level]), which the client's `LadenLayer` draws and the Hunt (build part E)
 * reads through [of] and [level] as well.
 *
 * Only what the market trades counts ([Good.of]); tools, weapons, armour and Marks never do. Nothing is saved, and nothing is
 * worked out for a player who has not started or while carrying is switched off ([TradeConfig.carry]): then the look is simply not
 * there, as it never was. `/guildmark trade laden <marks>` pins a value for a minute so the look (and the Hunt, which reads the
 * same number) can be tried without filling a pack.
 */
object Laden {
    /** The least value, in Marks, that shows as a pack on the back (level 1), and where the frame (level 2) and the tall frame (level 3) begin. */
    const val SHOWN = 25
    const val FRAME = 100
    const val TALL = 250

    /** What a hauled unit is worth against its base price (Part 2's ledger says which units were hauled). */
    const val HAULED_FACTOR = 1.25

    /** Ticks between two counts of one trader's load, and how long `trade laden` holds a pinned value. */
    const val PERIOD = 40
    const val PIN_TICKS = 1200L

    /** The most a pinned value may be, in Marks. */
    const val PIN_MOST = 100000

    /**
     * How many units of [Good] [ServerPlayer] carries that were hauled (carried [TradeConfig.haulMin] blocks or more from where
     * they were bought; Part 2's ledger). Answers 0 until build part A's ledger sets it, so that hauled goods count at their base
     * price and nothing here waits on it.
     */
    var hauled: (ServerPlayer, Good) -> Int = { _, _ -> 0 }

    /** A value pinned by `trade laden`: [marks] until the game time [until]. */
    private class Pin(val marks: Int, val until: Long)

    /** A load as last counted: its value and the game time it was counted at. */
    private class Counted(val marks: Int, val at: Long)

    private val pins = HashMap<UUID, Pin>()
    private val counted = HashMap<UUID, Counted>()

    /** The level a load worth [marks] shows at: 0 under [SHOWN], 1 up to [FRAME], 2 up to [TALL], 3 from there. */
    fun level(marks: Int): Int = when {
        marks >= TALL -> 3
        marks >= FRAME -> 2
        marks >= SHOWN -> 1
        else -> 0
    }

    /**
     * What [player] carries, in Marks: the value pinned for them just now if there is one, else the count of their load (made at most
     * [PERIOD] ticks ago, or now if there is none yet), and 0 for anyone who has not taken up the trade.
     */
    fun of(player: ServerPlayer): Int {
        val now = player.level().gameTime
        pinned(player)?.let { return it }
        if (!started(player)) return 0
        val last = counted[player.uuid]
        if (last != null && now - last.at < PERIOD) return last.marks
        val marks = count(player)
        counted[player.uuid] = Counted(marks, now)
        return marks
    }

    /** The level [player]'s load shows at just now (see [of] and [level]). */
    fun level(player: ServerPlayer): Int = level(of(player))

    /** The value pinned for [player] just now, if one is. */
    fun pinned(player: ServerPlayer): Int? {
        val pin = pins[player.uuid] ?: return null
        if (pin.until <= player.level().gameTime) {
            pins.remove(player.uuid)
            return null
        }
        return pin.marks
    }

    /** Pins the value of [player]'s load at [marks] for a minute, and shows it at once. */
    fun pin(player: ServerPlayer, marks: Int) {
        pins[player.uuid] = Pin(marks.coerceIn(0, PIN_MOST), player.level().gameTime + PIN_TICKS)
        publish(player)
    }

    /** Lets go of [player]'s pinned value, if they have one, and shows what they really carry. */
    fun unpin(player: ServerPlayer) {
        pins.remove(player.uuid)
        publish(player)
    }

    /** Whether [player] has taken up the trade. Looks at the record without making one: asking must never write a file. */
    fun started(player: ServerPlayer): Boolean {
        val books: TradeBooks? = player.level().server.overworld().dataStorage.get(TradeBooks.TYPE)
        return books?.get(player.uuid)?.started == true
    }

    /** What the market goods in [container] come to at their base prices, hauled or not: how the commands tell the pack from a carrier. */
    fun worth(container: Container): Double {
        var sum = 0.0
        for (slot in 0 until container.containerSize) {
            val stack = container.getItem(slot)
            if (stack.isEmpty) continue
            val good = Good.of(stack.item) ?: continue
            sum += good.base * stack.count
        }
        return sum
    }

    /**
     * What [player] carries within [Carriers.REACH] blocks, counted afresh: for each unit of a good the market trades, its base
     * price, a hauled unit [HAULED_FACTOR] times that, the sum cut down to whole Marks.
     */
    fun count(player: ServerPlayer): Int {
        val units = IntArray(Good.entries.size)
        for (container in Carriers.holdings(player, Carriers.REACH)) {
            for (slot in 0 until container.containerSize) {
                val stack = container.getItem(slot)
                if (stack.isEmpty) continue
                val good = Good.of(stack.item) ?: continue
                units[good.ordinal] += stack.count
            }
        }
        var sum = 0.0
        for (good in Good.TRADED) {
            val n = units[good.ordinal]
            if (n <= 0) continue
            val far = min(n, hauled(player, good).coerceAtLeast(0))
            sum += good.base * (n - far + far * HAULED_FACTOR)
        }
        return floor(sum + 1e-6).toInt()
    }

    /**
     * Works out [player]'s look and sets it, or takes it away. Nothing is written for a player who has none, and while carrying is
     * off there is no look at all (a pinned value still answers [of], which is the number the Hunt reads, but is not drawn).
     */
    fun publish(player: ServerPlayer) {
        val shown = if (TradeConfig.carry()) of(player) else 0
        val state = if (level(shown) == 0) LadenState.NONE else LadenState(level(shown), shown)
        val have = player.getExistingDataOrNull(Carry.LADEN)
        if (state.level == 0) {
            if (have != null) player.removeData(Carry.LADEN)
        } else if (have != state) {
            player.setData(Carry.LADEN, state)
        }
    }

    /** Counts a trader's load every [PERIOD] ticks (the players a tick apart, so no tick carries them all), and forgets whoever leaves. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            val uuid = event.entity.uuid
            pins.remove(uuid)
            counted.remove(uuid)
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            pins.clear()
            counted.clear()
        }
    }

    private fun tick(server: MinecraftServer) {
        val players = server.playerList.players
        if (players.isEmpty()) return
        val now = server.tickCount
        for (player in players) if ((now + player.id) % PERIOD == 0) publish(player)
    }
}
