package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import io.github.vaspyyy.guildmark.trade.scenes.ReliefCheer
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import kotlin.math.abs

/**
 * The town's answer to a delivery (living-world/trader/design.md, Part 6; build part H): the one place that hears of a ticket filled
 * ([Hauls.Listener.delivered]) and makes it show in the places a player already looks. Nothing here pays a Mark, touches the pool or
 * changes the ticket; it only makes the town better for what was brought, and says so:
 *
 * - **The town's page** ([Fortunes]): a delivery of 64 units or more notes a great haul from afar for three days, with the way it came by
 *   (the side of the town the goods were bought on); a relief ticket notes the relief the town was sent for five days instead, worded for a
 *   trader; and every ticket filled notes the town supplied by whoever filled it, for it was posted because the town was short.
 * - **The street's cheer** ([ReliefCheer]): a relief ticket, a feast ticket or 128 units or more brought in person.
 * - **What is said and remembered** ([Witness.hauled]): the shop's own think the better of whoever brought it, and a consignment or a relief is
 *   talked of and travels as a rumour, told first by the shop's own and by those who stood in the cheer.
 * - **The guild's news**: "A trader, [name], brought relief to [town]." for a relief, once a game day a town.
 *
 * A House's carter's delivery makes the page, the talk and the news, in its owner's name, and no cheer: there is no one in the square to see it
 * come. With `tradeWorld` off none of it happens ([answer] says so and does nothing), and the delivery pays as it did; `tradeCheer` off leaves
 * only the cheer out. Nothing is saved but what the page, the talk and the news save as they always do. A step that fails is logged and the
 * others still go on, and a failure never reaches the ticket (A's listeners are told one at a time, too).
 *
 * [Trade.register] calls [register]; the operators' `trade witness` and `trade cheer` ([WorldCommand]) play [answer] and the cheer without a ticket.
 */
object WorldAnswers : Hauls.Listener {
    /** The key of the guild news a relief delivered chalks: "A trader, [name], brought relief to [town]." */
    const val NEWS_RELIEF = "news.guildmark.trade.relief"

    /** What the town did for a delivery, for the operators' `trade witness` to say. */
    class Answered(
        /** Why nothing at all was done (the switch is off, no market is open there), else null. */
        val skipped: String? = null,
        /** What was noted on the town's page, in words ("a great haul by the east road (+1 for 3 days), supplied by Ransom (+1 for 3 days)"). */
        val page: String = "",
        /** The cheer: what it did, or why it did not. Null if it was not looked at. */
        val cheer: ReliefCheer.Played? = null,
        /** The talk and warmth: the shop's own warmed, the rumour started or heated, and how many knew first. Null if it failed. */
        val talk: Witness.Hauled? = null,
        /** The guild's news: what was chalked, or why not. Null if the delivery was not a relief. */
        val news: String? = null,
    )

    private var registered = false

    /** Hears the tickets filled, once, at setup; and sets the operators' commands going. */
    fun register() {
        if (registered) return
        registered = true
        Hauls.listen(this)
        WorldCommand.init()
    }

    override fun delivered(level: ServerLevel, haul: Haul, delivery: Delivery) {
        try {
            answer(level, haul, delivery)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("The town's answer to a delivery failed", e)
        }
    }

    /**
     * A ticket the town no longer wants was taken off the board at dawn. Nothing of the town's answer waits on a ticket (a delivery is answered
     * when it is made, and the cheer and the talk are over by dawn), so there is nothing to take back, and nobody to thank for goods that never came.
     */
    override fun withdrawn(level: ServerLevel, haul: Haul) {}

    /**
     * The town sees [delivery] of [haul] (a booking brought in full) and answers: the page, the cheer, the talk and, for a relief, the news,
     * in that order, for the talk wants the cheer's crowd to say who saw it. [bringer] is the name to say it by (the owner's, as the world
     * knows it, if not given). Does nothing, and says why, with `tradeWorld` off or no market open at the capital. Called from [delivered] for a
     * ticket, and by `trade witness` for a delivery made up to see the answer.
     */
    fun answer(level: ServerLevel, haul: Haul, delivery: Delivery, bringer: String? = null): Answered {
        if (!TradeConfig.world()) return Answered("the town's answer is switched off (setting: The Town Answers, or Trader's Life)")
        val entry = Markets.get(level)[delivery.capital] ?: return Answered("no market is open at ${delivery.capital.x} ${delivery.capital.z}")
        val owner = delivery.taker.owner
        val name = bringer ?: Say.name(level, owner) ?: "A trader"
        val page = attempt("the town's page") { note(level, delivery, owner, name) } ?: ""
        val cheer = attempt("the cheer") { cheer(level, delivery, name) }
        val talk = attempt("the talk") { Witness.hauled(level, entry, owner, name, haul, delivery.units, cheer?.crowd.orEmpty()) }
        val news = if (delivery.kind == HaulKind.RELIEF) attempt("the news") { news(level, delivery.capital, name) } else null
        return Answered(null, page, cheer, talk, news)
    }

    // ---- The page ----

    /**
     * Notes what [delivery] did for the town's page, under [owner]'s name, and says it in words: a great haul (64 units or more) by the way it came, a
     * relief ticket's relief instead, and the town supplied. Each is the one note a day for the same player and the same way (the page's own rule),
     * so a trader at it all day does not stack the page. Nothing while the fortunes are off.
     */
    private fun note(level: ServerLevel, delivery: Delivery, owner: UUID, name: String): String {
        if (!Fortunes.enabled()) return "nothing, Town Fortunes is off"
        val capital = delivery.capital.asLong()
        val side = sideOf(delivery.capital, delivery.from)
        val parts = ArrayList<String>()
        if (delivery.kind == HaulKind.RELIEF) {
            Fortunes.note(level, capital, Noted.RELIEF, listOf(side, "hauled"), owner)
            parts += "relief by ${way(side)} (+${Noted.RELIEF.points} for ${Noted.RELIEF.days} days)"
        } else if (delivery.units >= Witness.HAULED_UNITS) {
            Fortunes.note(level, capital, Noted.HAULED, listOf(side), owner)
            parts += "a great haul by ${way(side)} (+${Noted.HAULED.points} for ${Noted.HAULED.days} days)"
        }
        // A ticket is posted because the town is short: filling one supplies it, as filling an order does (the stock is already raised when this is heard, so asking if it is still dear would not do)
        Fortunes.note(level, capital, Noted.SUPPLIED, listOf(name), owner)
        parts += "supplied by $name (+${Noted.SUPPLIED.points} for ${Noted.SUPPLIED.days} days)"
        return parts.joinToString(", ")
    }

    /**
     * Which side of the town at [capital] the goods came from, as the road news names the sides: "n", "e", "s" or "w" by the larger of the two
     * offsets from the town to [from] (north is up the map, -z), or "any" if it is not known where from or it is no way off.
     */
    fun sideOf(capital: BlockPos, from: BlockPos?): String {
        if (from == null) return "any"
        val dx = from.x - capital.x
        val dz = from.z - capital.z
        if (dx == 0 && dz == 0) return "any"
        return if (abs(dx) >= abs(dz)) (if (dx > 0) "e" else "w") else (if (dz > 0) "s" else "n")
    }

    /** [side] as the page says it: "the east road", or "the road" not knowing. */
    fun way(side: String): String = when (side) {
        "n" -> "the north road"
        "e" -> "the east road"
        "s" -> "the south road"
        "w" -> "the west road"
        else -> "the road"
    }

    // ---- The cheer ----

    /**
     * The street cheers if [delivery] was a relief ticket's, a feast ticket's or of 128 units or more, and was made in person by a player who is on
     * (what [ReliefCheer.at] decides the rest of: the hour, whether anyone is near and free). A relief and a feast are the bigger cheer. The
     * play says why when there was none.
     */
    private fun cheer(level: ServerLevel, delivery: Delivery, name: String): ReliefCheer.Played {
        val relief = delivery.kind == HaulKind.RELIEF
        val big = relief || delivery.kind == HaulKind.FEAST
        if (!big && delivery.units < Witness.HAULED_GREAT) {
            return ReliefCheer.Played("it was a delivery of ${delivery.units} units, and a cheer is for a relief, a feast or ${Witness.HAULED_GREAT} units or more")
        }
        if (delivery.taker !is Taker.Player) return ReliefCheer.Played("a House's carter brought it, and the street cheers a trader it can see")
        val player = level.server.playerList.getPlayer(delivery.taker.owner) ?: return ReliefCheer.Played("the trader is not on")
        val at = Orders.get(level)[delivery.capital]?.shops?.get(delivery.shop) ?: player.blockPosition()
        return ReliefCheer.at(level, at, player, big, delivery.shop, ReliefCheer.Delivered(delivery.good, delivery.units, name, relief))
    }

    // ---- The news ----

    /**
     * The guild's board chalks "A trader, [name], brought relief to [town]." once a game day a town: by what the board already says, so a restart does
     * not make it say it twice. Says what it did.
     */
    private fun news(level: ServerLevel, capital: BlockPos, name: String): String {
        val town = Witness.townOf(level, capital)
        if (town.isEmpty()) return "not chalked, the town's name is not known"
        val today = VillageBoards.day(level)
        if (GuildNews.get(level).items.any { it.key == NEWS_RELIEF && it.day == today && it.args.getOrNull(1) == town }) {
            return "not chalked again, $town's relief is on the board today"
        }
        GuildNews.add(level, NEWS_RELIEF, name, town)
        return "chalked: A trader, $name, brought relief to $town."
    }

    // ---- When a step fails ----

    private fun <T> attempt(what: String, run: () -> T): T? = try {
        run()
    } catch (e: RuntimeException) {
        Guildmark.LOGGER.warn("The town's answer to a delivery failed at $what", e)
        null
    }
}
