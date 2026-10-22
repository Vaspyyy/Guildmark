package io.github.vaspyyy.guildmark.cooking.home

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.DishTooltip
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.Freshness
import io.github.vaspyyy.guildmark.cooking.dish.LarderBlockEntity
import io.github.vaspyyy.guildmark.cooking.market.MenuBoards
import io.github.vaspyyy.guildmark.cooking.market.Owed
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel

/**
 * What a homestead's pantry holds, chalked as the lines of the Menu board by the farmhouse door (living-world/cook/design.md
 * 6.7): the dishes in the larders Part K fitted, counted as they are at this moment: "3 Drover's Stew, Fine", each kind and tier
 * on a line (a stale lot says so, in amber), the three that most are listed and the rest folded into "and 4 more portions",
 * then the dish that will turn soonest, and how many have gone off. A bare pantry says so. Every number is read from the
 * stacks as the larders hold them, never from a note: the days are the dishes' own, settled by the larder as it settles them
 * for anyone who looks, and with `dishSpoilage` off nothing turns, so those two lines are left out.
 */
internal object HomePantry {
    /** The most dish lots a board lists before the rest are folded into one line, so a board is at most six lines. */
    const val LOTS = 3

    /** The Larder keeps a dish for twice as long (its half-rate ageing, `LarderBlockEntity`): a dish-day inside is this many town days. */
    private const val SLOWER = 2

    /** One line of the board: what is counted together. A lot is a kind at a tier, fresh or stale; Gruel and mess have no tier. */
    private class Lot(val kindId: String, val tier: Tier?, val stale: Boolean, val name: Component, var portions: Int, val order: Int)

    /** The dish that will turn first: in how many dish-days, which one and what it turns to. */
    private class Soonest(val ahead: Long, val kindId: String, val name: Component, val toOff: Boolean, val order: Int)

    /**
     * The lines of the board for the larders at [cells] and the dish kind of each ("" for none), or null while one of the
     * larders is in a chunk that is not loaded (it would be counted as empty, which is not so; the board stays as it is).
     */
    fun chalk(level: ServerLevel, cells: List<BlockPos>): MenuBoards.Chalk? {
        for (pos in cells) if (!level.isLoaded(pos)) return null
        val spoils = Cooking.spoilage()
        val today = Freshness.today(level)
        val lots = LinkedHashMap<Triple<Any, Int, Boolean>, Lot>()
        var gone = 0
        var soonest: Soonest? = null
        for (pos in cells) {
            val larder = level.getBlockEntity(pos) as? LarderBlockEntity ?: continue
            for (slot in 0 until LarderBlockEntity.SIZE) {
                val stack = larder.getItem(slot)
                if (stack.isEmpty) continue
                val item = stack.item as? DishItem ?: continue
                val data = Dishes.dataOf(stack) ?: continue
                val kind = item.kind
                val dated = spoils && !item.scorched && data.day != DishData.UNDATED
                val stage = if (dated) Freshness.stageAt(today, kind, data) else Stage.FRESH
                if (stage == Stage.GONE) {
                    gone += stack.count
                    continue
                }
                val stale = stage == Stage.STALE
                val tier = if (kind != null) data.tier else null
                val order = kind?.ordinal ?: Int.MAX_VALUE
                val name = if (kind != null) Owed.dish(kind) else stack.hoverName
                val lot = lots.getOrPut(Triple(item, tier?.ordinal ?: -1, stale)) { Lot(kind?.id ?: "", tier, stale, name, 0, order) }
                lot.portions += stack.count
                if (dated) {
                    val limit = Freshness.limits(kind, data.keep)
                    val ahead = (if (stale) limit[1] else limit[0]) + 1 - Freshness.age(today, data)
                    val best = soonest
                    if (best == null || ahead < best.ahead || (ahead == best.ahead && order < best.order)) {
                        soonest = Soonest(ahead, kind?.id ?: "", name, stale, order)
                    }
                }
            }
        }
        val lines = ArrayList<Component>()
        val icons = ArrayList<String>()
        if (lots.isEmpty() && gone == 0) {
            lines.add(Component.translatable("gui.guildmark.cook.home.pantry.bare"))
            icons.add("")
            return MenuBoards.Chalk(lines, icons)
        }
        lines.add(Component.translatable("gui.guildmark.cook.home.pantry"))
        icons.add("")
        val ordered = lots.values.sortedWith(
            compareByDescending<Lot> { it.tier?.ordinal ?: -1 }.thenBy { it.stale }.thenByDescending { it.portions }.thenBy { it.order },
        )
        val shown = if (ordered.size > LOTS) LOTS - 1 else ordered.size
        for (lot in ordered.take(shown)) {
            lines.add(lineOf(lot))
            icons.add(lot.kindId)
        }
        val rest = ordered.drop(shown).sumOf { it.portions }
        if (rest > 0) {
            lines.add(Component.translatable(if (rest == 1) "gui.guildmark.cook.home.pantry.more.one" else "gui.guildmark.cook.home.pantry.more", rest.toString()))
            icons.add("")
        }
        soonest?.let { next ->
            val key = if (next.toOff) "gui.guildmark.cook.home.pantry.next.off" else "gui.guildmark.cook.home.pantry.next.stale"
            lines.add(Component.translatable(key, next.name, (next.ahead * SLOWER).toString()).withStyle(ChatFormatting.GRAY))
            icons.add(next.kindId)
        }
        if (gone > 0) {
            val text = Component.translatable(if (gone == 1) "gui.guildmark.cook.home.pantry.gone.one" else "gui.guildmark.cook.home.pantry.gone", gone.toString())
            lines.add(text.withColor(DishTooltip.stageColor(Stage.GONE)))
            icons.add("")
        }
        return MenuBoards.Chalk(lines, icons)
    }

    /** "3 Drover's Stew, Fine" (stale lots end "(stale)" and are amber); Gruel and mess, which have no tier, are "4 Gruel". */
    private fun lineOf(lot: Lot): Component {
        val tier = lot.tier
        val line = if (tier != null) {
            Component.translatable(
                if (lot.stale) "gui.guildmark.cook.home.pantry.line.stale" else "gui.guildmark.cook.home.pantry.line",
                lot.portions.toString(), lot.name, DishTooltip.tierWord(tier),
            )
        } else {
            Component.translatable(if (lot.stale) "gui.guildmark.cook.home.pantry.plain.stale" else "gui.guildmark.cook.home.pantry.plain", lot.portions.toString(), lot.name)
        }
        return if (lot.stale) line.withColor(DishTooltip.stageColor(Stage.STALE)) else line
    }
}
