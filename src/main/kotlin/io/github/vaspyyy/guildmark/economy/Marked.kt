package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min

/** Goods off a waylaid wagon: the capital they were bound for ([capital], its heart), the caravan, and the capital's [name]. */
data class WagonMark(val capital: Long, val caravan: Long, val name: String) {
    companion object {
        val CODEC: Codec<WagonMark> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(WagonMark::capital),
                Codec.LONG.optionalFieldOf("caravan", -1L).forGetter(WagonMark::caravan),
                Codec.STRING.optionalFieldOf("name", "").forGetter(WagonMark::name),
            ).apply(i, ::WagonMark)
        }
    }
}

/**
 * Salvage (design-road.md section 4): the goods taken from a wreck or a broken band's loot pile come marked as off the
 * wagon they were bound for ("Off the wagon to Hollowmere"). Brought back to any keeper in that capital they're
 * taken in and paid for at a finder's fee of [FEE] of their price now, below what a stall pays, with supplier standing
 * at the full rate and the crier's thanks the next noon; the goods go back to the market. Kept, the home capital's
 * keepers won't buy them and its orders won't take them (another capital's will, remarking on the mark), and after
 * [KEEP] what wasn't brought back costs standing there and a week of cool keepers.
 */
object Marked {
    /** Of a good's price now, what bringing it back pays. */
    const val FEE = 0.4

    /** Clock ticks salvage may be kept before keeping it costs, and how long the keepers are cool after. */
    const val KEEP = 4 * 24000L
    const val COOL = 7 * 24000L

    /** Supplier points a kept lot costs at home, and the most a player loses so at once. */
    private const val COST = 5.0
    private const val COST_MOST = 20.0

    /** [count] of [good], marked as off the wagon [mark] names: their own items, with the mark on them for all to see. */
    fun stack(good: Good, count: Int, mark: WagonMark): ItemStack {
        val item = good.item ?: return ItemStack.EMPTY
        val stack = ItemStack(item, count)
        stack.set(ModDataComponents.MARKED.get(), mark)
        val name = mark.name.ifEmpty { "?" }
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.marked", name).withStyle(ChatFormatting.GOLD))))
        return stack
    }

    /** The wagon [stack] came off, if it's salvage. */
    fun of(stack: ItemStack): WagonMark? = if (stack.isEmpty) null else stack.get(ModDataComponents.MARKED.get())

    /** Whether [stack] is salvage off a wagon bound for the capital whose heart is [capital]. */
    fun home(stack: ItemStack, capital: BlockPos): Boolean = of(stack)?.capital == capital.asLong()

    /** Whether [player] carries anything off a wagon bound for [capital]. */
    fun carries(player: ServerPlayer, capital: BlockPos): Boolean =
        (0 until player.inventory.containerSize).any { home(player.inventory.getItem(it), capital) }

    /** Whether [player] carries salvage off another capital's wagon among their [good] (a keeper elsewhere remarks on the mark). */
    fun foreign(player: ServerPlayer, capital: BlockPos, good: Good): Boolean = (0 until player.inventory.containerSize).any { slot ->
        val stack = player.inventory.getItem(slot)
        val mark = of(stack)
        mark != null && mark.capital != capital.asLong() && good.item?.let { stack.`is`(it) } == true
    }

    /** What a player brought back to a keeper: the goods and how many of each, the fee paid in whole Marks, and the dearest good among them. */
    class Returned(val goods: List<Pair<Good, Int>>, val pay: Int, val dearest: Good)

    /**
     * [player] has stepped up to [keeper] at [entry]'s capital carrying salvage off its wagons: the keeper takes every
     * marked thing for that capital out of their pack, pays the finder's fee (what doesn't make a whole Mark is kept
     * owing for next time), and the goods go back to the market. Null if they carried none.
     */
    fun back(level: ServerLevel, player: ServerPlayer, keeper: Townsperson, entry: Markets.Entry): Returned? {
        val capital = entry.capital
        val counts = LinkedHashMap<Good, Int>()
        val inv = player.inventory
        for (slot in 0 until inv.containerSize) {
            val stack = inv.getItem(slot)
            if (!home(stack, capital)) continue
            val good = Good.entries.firstOrNull { g -> g.item?.let { stack.`is`(it) } == true } ?: continue
            counts[good] = (counts[good] ?: 0) + stack.count
            inv.setItem(slot, ItemStack.EMPTY)
        }
        if (counts.isEmpty()) return null
        val m = entry.market
        var worth = 0.0
        for ((g, n) in counts) {
            worth += n * m.price(entry.town, g)
            m.add(Economy.site(g), g, n.toDouble())
        }
        val data = CaravanData.get(level)
        val cap = data.capital(capital.asLong(), m.hour)
        val owed = (cap.owed[player.uuid] ?: 0.0) + worth * FEE
        val pay = floor(owed + 1e-6).toInt()
        cap.owed[player.uuid] = owed - pay
        if (pay > 0) Trading.give(player, ModItems.GUILD_MARK.get(), pay)
        // Off the record of what they took, the oldest first
        for ((g, n) in counts) {
            var left = n
            for (t in data.takes.filter { it.player == player.uuid && it.capital == capital.asLong() && it.good == g }.sortedBy { it.at }) {
                if (left <= 0) break
                val k = min(left, t.count)
                t.count -= k
                left -= k
            }
        }
        data.takes.removeIf { it.count <= 0 }
        cap.cool.remove(player.uuid)
        // Standing at the full rate, as an order's Marks count; and the crier's thanks the next noon
        if (Suppliers.enabled()) Suppliers.gain(level, capital, player.uuid, worth)
        val news = cap.news.lastOrNull { it.kind == "salvage" && it.hour == m.hour }
        if (news != null) {
            if (player.name.string !in news.names) {
                cap.news.remove(news)
                cap.news.add(RoadNews(news.hour, news.kind, news.road, news.names + player.name.string))
            }
        } else {
            RoadPeril.tell(data, capital.asLong(), "salvage", -1, m.hour, listOf(player.name.string))
        }
        data.changed()
        Markets.get(level).changed()
        Days.life(keeper)?.resident?.let { Witness.salvage(level, it, player, brought = true) }
        val goods = counts.entries.map { it.key to it.value }
        val list = Component.empty()
        goods.forEachIndexed { k, (g, n) ->
            if (k > 0) list.append(", ")
            list.append(Component.translatable("supplier.guildmark.what", n, Component.translatable(g.key + ".many")))
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.salvage.back", list, cap.name.ifEmpty { "?" }, pay).withStyle(ChatFormatting.YELLOW))
        return Returned(goods, pay, counts.keys.maxBy { it.base * (counts[it] ?: 0) })
    }

    /** Whether [capital]'s keepers are cool on [player] for salvage they kept. */
    fun cool(level: ServerLevel, capital: BlockPos, player: UUID): Boolean {
        val until = CaravanData.get(level).capitalOrNull(capital.asLong())?.cool?.get(player) ?: return false
        return level.overworldClockTime < until
    }

    /**
     * What was taken and not brought back in [KEEP]: [COST] supplier points a lot at home ([COST_MOST] at most), a
     * week of cool keepers, and the town's talk of it. Settled while the player is on, so they hear of it.
     */
    fun lapse(level: ServerLevel, data: CaravanData, t: Long) {
        val due = data.takes.filter { it.count > 0 && t - it.at >= KEEP }
        if (due.isEmpty()) return
        for ((key, takes) in due.groupBy { it.player to it.capital }) {
            val player = level.server.playerList.getPlayer(key.first) ?: continue
            data.takes.removeAll(takes)
            val capital = BlockPos.of(key.second)
            val cap = data.capital(key.second, Markets.hourOf(level))
            cap.cool[key.first] = t + COOL
            val cost = min(COST_MOST, COST * takes.size)
            if (Suppliers.enabled()) Suppliers.gain(level, capital, key.first, -cost)
            player.sendSystemMessage(Component.translatable("message.guildmark.salvage.kept", cap.name.ifEmpty { "?" }, cost.toInt()).withStyle(ChatFormatting.GRAY))
            Residents.get(level).of(capital).firstOrNull { it.alive && it.role == TownRole.MERCHANT }?.let { Witness.salvage(level, it, player, brought = false) }
            data.changed()
        }
        data.takes.removeIf { it.count <= 0 }
    }
}
