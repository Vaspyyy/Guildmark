package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.feast.HarvestTable
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The day's contest, one for each festival, open through the fair (until the crier calls everyone for the prizes):
 *
 * - The Ribbon Hunt (Ribbonwake): eight ribbons are tied about the town; whoever finds the most (soonest) wins.
 * - The Butts (Sunhearth): shoot at the three targets from ten blocks or more; your best five arrows count, ten for
 *   the gold down to two for the edge.
 * - The Produce Show (Sheafmoot): show the judge the best of your harvest (they only look; you keep it). Each kind
 *   counts for more the more of it you bring, and your best six kinds make your score.
 * - The Lantern Riddles (Lanternrise): each lantern on the posts has a riddle; read one, then show the host its
 *   answer (again, only to look at). Every riddle solved counts.
 *
 * A capital's Sheafmoot has a second table beside its Produce Show, the Harvest Table (living-world/cook/design.md 6.8;
 * [taste], [cookResults]): a cook shows the host a dish they cooked themselves, fresh, and their three best kinds make
 * their score; the town's inn and bakery enter a dish each ([addTown]). [capital] says whether it can be held here.
 *
 * Every player who took part gets something at the prize-giving (see [Festivities.award]). Every entry, the Harvest
 * Table's too, is kept in memory only, for the day: a restart in the middle of the fair loses them.
 */
class FestivalContest(val kind: FestivalKind, private val riddleSeed: Long, val capital: Boolean = false) {
    /** Someone's standing: their score and when they reached it (the earlier wins a tie); [npc] for the town's own entries at the Harvest Table. */
    class Entry(val id: UUID, var name: String, var score: Int = 0, var at: Long = 0L, val npc: Boolean = false)

    private val entries = LinkedHashMap<UUID, Entry>()
    private val ribbonsFound = HashMap<UUID, MutableSet<Int>>()
    private val arrows = HashMap<UUID, MutableList<Int>>()
    private val shown = HashMap<UUID, MutableMap<Item, Int>>()
    private val solved = HashMap<UUID, MutableSet<Int>>()
    private val reading = HashMap<UUID, Int>()
    /** The Harvest Table's: the best points of each kind each cook showed, and the cooks' standings (the town's among them). */
    private val dishes = HashMap<UUID, MutableMap<DishKind, Int>>()
    private val cooks = LinkedHashMap<UUID, Entry>()

    /** Whether entries are taken now. */
    var open = false

    val title: Component get() = Component.translatable("festival.guildmark.contest.${kind.id}")

    /** Everyone's standing, the best first. */
    fun results(): List<Entry> = entries.values.filter { it.score > 0 }.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.at })

    fun entered(player: ServerPlayer): Boolean = entries[player.uuid]?.let { it.score > 0 } == true

    private fun entry(player: ServerPlayer): Entry = entries.getOrPut(player.uuid) { Entry(player.uuid, player.name.string) }.also { it.name = player.name.string }

    private fun score(level: ServerLevel, player: ServerPlayer, score: Int) {
        val e = entry(player)
        if (score != e.score) {
            e.score = score
            e.at = level.gameTime
        }
    }

    private fun closed(player: ServerPlayer): Boolean {
        if (open) return false
        player.sendOverlayMessage(Component.translatable("message.guildmark.festival.contest_closed", title).withColor(kind.color))
        return true
    }

    // ---- The Ribbon Hunt ----

    /** [player] found ribbon [index]. */
    fun ribbon(level: ServerLevel, player: ServerPlayer, index: Int, at: BlockPos) {
        if (closed(player)) return
        val found = ribbonsFound.getOrPut(player.uuid) { HashSet() }
        if (!found.add(index)) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.festival.ribbon_again", found.size, RIBBONS).withColor(kind.color))
            return
        }
        score(level, player, found.size)
        val done = found.size >= RIBBONS
        player.sendOverlayMessage(Component.translatable(if (done) "message.guildmark.festival.ribbon_all" else "message.guildmark.festival.ribbon", found.size, RIBBONS).withColor(kind.color))
        level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2f, 0.9f + found.size * 0.08f)
        level.playSound(null, at, SoundEvents.WOOL_BREAK, SoundSource.PLAYERS, 0.6f, 1.4f)
    }

    /** Has [player] found ribbon [index] already? (Their own ribbons show as found to them.) */
    fun hasRibbon(player: UUID, index: Int): Boolean = ribbonsFound[player]?.contains(index) == true

    // ---- The Butts ----

    /**
     * An arrow from [player], [distance] blocks off, struck a target's [face] at [hit] (the target's block [at]):
     * scored by how near the middle of the face it went in.
     */
    fun arrow(level: ServerLevel, player: ServerPlayer, at: BlockPos, face: Direction, hit: Vec3, distance: Double) {
        if (closed(player)) return
        if (distance < MIN_RANGE) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.festival.too_close", MIN_RANGE.toInt()).withColor(kind.color))
            return
        }
        // Across the face: the two axes square to the one it faces along
        val du: Double
        val dv: Double
        when (face.axis) {
            Direction.Axis.X -> { du = hit.y - (at.y + 0.5); dv = hit.z - (at.z + 0.5) }
            Direction.Axis.Y -> { du = hit.x - (at.x + 0.5); dv = hit.z - (at.z + 0.5) }
            Direction.Axis.Z -> { du = hit.x - (at.x + 0.5); dv = hit.y - (at.y + 0.5) }
        }
        val off = sqrt(du * du + dv * dv)
        val points = when {
            off < 0.09 -> 10
            off < 0.19 -> 8
            off < 0.29 -> 6
            off < 0.39 -> 4
            else -> 2
        }
        val list = arrows.getOrPut(player.uuid) { ArrayList() }
        list.add(points)
        val best = list.sortedDescending().take(BEST_ARROWS).sum()
        score(level, player, best)
        player.sendOverlayMessage(Component.translatable("message.guildmark.festival.arrow", points, best, min(list.size, BEST_ARROWS)).withColor(kind.color))
        val pitch = 0.6f + points * 0.1f
        level.playSound(null, at, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, pitch)
        if (points == 10) level.playSound(null, at, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.6f)
    }

    // ---- The Produce Show ----

    /** [player] shows the judge [stack]; true if it was something the judge looks at. */
    fun produce(level: ServerLevel, player: ServerPlayer, stack: ItemStack): Boolean {
        val points = PRODUCE[stack.item] ?: return false
        if (closed(player)) return true
        val kinds = shown.getOrPut(player.uuid) { HashMap() }
        val count = min(stack.count, 32)
        val value = points * (32 + count) / 32
        val before = kinds[stack.item] ?: 0
        if (value <= before) {
            player.sendSystemMessage(Component.translatable("message.guildmark.festival.produce_seen", stack.hoverName, before).withColor(kind.color))
            return true
        }
        kinds[stack.item] = value
        val total = kinds.values.sortedDescending().take(BEST_KINDS).sum()
        score(level, player, total)
        player.sendSystemMessage(Component.translatable("message.guildmark.festival.produce", stack.count, stack.hoverName, value, total, min(kinds.size, BEST_KINDS), BEST_KINDS).withColor(kind.color))
        level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8f, 1.1f)
        return true
    }

    // ---- The Harvest Table ----

    /**
     * What the host made of a dish shown for the Harvest Table: judged, its [dish] at [tier] worth [points] ([before], what that
     * kind had scored already; [total], the cook's score now, of their best [counted] kinds), or refused with the line [refusal]
     * (a language key) and its [args]. A refusal changes nothing.
     */
    class Tasted(
        val dish: DishKind?, val tier: Tier, val points: Int, val before: Int, val total: Int, val counted: Int,
        val refusal: String?, val args: List<Any> = listOf(),
    ) {
        val judged: Boolean get() = refusal == null
    }

    /** "Harvest Table". */
    val cookTitle: Component get() = Component.translatable(HarvestTable.TITLE)

    /** Whether the Harvest Table is held here today: a capital's Sheafmoot, with the switch on. */
    fun cooking(): Boolean = kind == FestivalKind.SHEAFMOOT && capital && Cooking.cookOff()

    /**
     * [player] shows the host [stack] for the Harvest Table, and the host judges it now, saying nothing yet (the host's tasting
     * says it: cooking/feast/HarvestTable): null when it is not a dish here (not a capital's Sheafmoot, or not a dish at all), so
     * the Produce Show looks at it; else what the host made of it, the entry made if it was judged (only weighed, with [record]
     * false: `cook feast score`). Nothing is taken.
     */
    fun taste(level: ServerLevel, player: ServerPlayer, stack: ItemStack, record: Boolean = true): Tasted? {
        val item = stack.item as? DishItem ?: return null
        if (kind != FestivalKind.SHEAFMOOT || !capital) return null
        if (!Cooking.cookOff()) return refused("message.guildmark.feast.off")
        if (!open) return refused("message.guildmark.festival.contest_closed", cookTitle)
        val dish = Dishes.kindOf(stack)
        val data = Dishes.dataOf(stack) ?: DishData.PLAIN
        if (dish == null || item.scorched || data.tier == Tier.SPOILT) return refused("message.guildmark.feast.not_dish")
        if (data.cookId != player.uuid) return refused("message.guildmark.feast.not_yours")
        when (Dishes.stage(level, stack)) {
            Stage.STALE -> return refused("message.guildmark.feast.stale", HarvestTable.dishName(dish))
            Stage.GONE -> return refused("message.guildmark.feast.gone", HarvestTable.dishName(dish))
            Stage.FRESH -> {}
        }
        val points = HarvestTable.score(dish, data.tier)
        val before = dishes[player.uuid]?.get(dish) ?: 0
        if (points <= before) return refused("message.guildmark.feast.seen", HarvestTable.dishName(dish), before)
        val mine = if (record) dishes.getOrPut(player.uuid) { HashMap() } else HashMap(dishesOf(player.uuid))
        mine[dish] = points
        val total = mine.values.sortedDescending().take(BEST_DISHES).sum()
        if (!record) return Tasted(dish, data.tier, points, before, total, min(mine.size, BEST_DISHES), null)
        val e = cooks.getOrPut(player.uuid) { Entry(player.uuid, player.name.string) }.also { it.name = player.name.string }
        if (total != e.score) {
            e.score = total
            e.at = level.gameTime
        }
        return Tasted(dish, data.tier, points, before, total, min(mine.size, BEST_DISHES), null)
    }

    private fun refused(line: String, vararg args: Any): Tasted = Tasted(null, Tier.SPOILT, 0, 0, 0, 0, line, args.toList())

    /**
     * [player] shows the host [stack] for the Harvest Table, judged and told at once: false if it is not a dish here (the
     * Produce Show then looks at it), true when it was judged or refused.
     */
    fun dish(level: ServerLevel, player: ServerPlayer, stack: ItemStack): Boolean {
        val tasted = taste(level, player, stack) ?: return false
        tell(player, tasted, Component.translatable("festival.guildmark.host"))
        return true
    }

    /** Tells [player] what [host] made of their dish: its points and how they were reached, or why it is not judged. */
    fun tell(player: ServerPlayer, tasted: Tasted, host: Component) {
        val dish = tasted.dish
        val line = if (dish != null && tasted.judged) {
            Component.translatable(
                "message.guildmark.feast.judged", HarvestTable.dishName(dish), tasted.points, HarvestTable.working(dish, tasted.tier),
                tasted.total, tasted.counted, BEST_DISHES,
            )
        } else {
            Component.translatable(tasted.refusal ?: "message.guildmark.feast.not_dish", *tasted.args.toTypedArray())
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.festival.host", host, line).withColor(kind.color))
    }

    /** The Harvest Table's standings, the best first (the town's entries among them), in the same order as [results]. */
    fun cookResults(): List<Entry> = cooks.values.filter { it.score > 0 }.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.at })

    /** One of the town's own entries at the Harvest Table ([name] may be a language key after a "#"): placed, never paid. */
    fun addTown(id: UUID, name: String, points: Int) {
        cooks[id] = Entry(id, name, points, 0L, npc = true)
    }

    /** Takes the town's entries off the Harvest Table (to enter them again). */
    fun clearTown() {
        cooks.values.removeIf { it.npc }
    }

    /** The best points of each kind the cook [id] has shown at the Harvest Table. */
    fun dishesOf(id: UUID): Map<DishKind, Int> = dishes[id] ?: mapOf()

    /** The cook [id]'s standing at the Harvest Table, if they have one. */
    fun cook(id: UUID): Entry? = cooks[id]

    // ---- The Lantern Riddles ----

    /** Which of the riddles the [index]th lantern carries. */
    fun riddleOf(index: Int): Int = Math.floorMod(index + riddleSeed, RIDDLES.size.toLong()).toInt()

    /** [player] reads the riddle on lantern [index]. */
    fun read(level: ServerLevel, player: ServerPlayer, index: Int, at: BlockPos) {
        val riddle = riddleOf(index)
        reading[player.uuid] = riddle
        val done = solved[player.uuid]?.contains(riddle) == true
        player.sendSystemMessage(Component.translatable("festival.guildmark.riddle.$riddle").withStyle { it.withColor(kind.color).withItalic(true) })
        player.sendOverlayMessage(Component.translatable(if (done) "message.guildmark.festival.riddle_solved" else "message.guildmark.festival.riddle_read").withColor(kind.color))
        level.playSound(null, at, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1.1f)
    }

    /** [player] shows the host [stack] as the answer to the riddle they read last; true if it was taken as one. */
    fun answer(level: ServerLevel, player: ServerPlayer, stack: ItemStack): Boolean {
        val riddle = reading[player.uuid] ?: return false
        if (stack.isEmpty) return false
        if (closed(player)) return true
        val done = solved.getOrPut(player.uuid) { HashSet() }
        if (riddle in done) {
            player.sendSystemMessage(Component.translatable("message.guildmark.festival.riddle_again").withColor(kind.color))
            return true
        }
        if (!RIDDLES[riddle](stack)) {
            player.sendSystemMessage(Component.translatable("message.guildmark.festival.riddle_wrong", stack.hoverName).withColor(kind.color))
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.7f, 1f)
            return true
        }
        done.add(riddle)
        reading.remove(player.uuid)
        score(level, player, done.size)
        player.sendSystemMessage(Component.translatable("message.guildmark.festival.riddle_right", stack.hoverName, done.size).withColor(kind.color))
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 1.2f)
        level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.8f, 1f)
        return true
    }

    /** What [player] is working on, for the host's word with them. */
    fun hint(player: ServerPlayer): Component {
        val e = entries[player.uuid]
        val mine = e?.score ?: 0
        val best = results().firstOrNull()
        val word = when {
            !open -> Component.translatable("message.guildmark.festival.contest_closed", title)
            best == null -> Component.translatable("message.guildmark.festival.hint.${kind.id}")
            best.id == player.uuid -> Component.translatable("message.guildmark.festival.leading", title, mine)
            else -> Component.translatable("message.guildmark.festival.standing", title, mine, best.name, best.score)
        }
        if (!open || !cooking()) return word
        // A capital's Sheafmoot: a word on the Harvest Table too
        val cooked = cooks[player.uuid]?.score ?: 0
        val top = cookResults().firstOrNull()
        val table = when {
            cooked <= 0 -> Component.translatable("message.guildmark.festival.hint.sheafmoot_cook")
            top == null || top.id == player.uuid -> Component.translatable("message.guildmark.festival.hint.sheafmoot_cook_leading", cooked)
            else -> Component.translatable("message.guildmark.festival.hint.sheafmoot_cook_standing", cooked, named(top.name), top.score)
        }
        return Component.empty().append(word).append(" ").append(table)
    }

    companion object {
        /** How far off an archer has to stand for an arrow to count, and how many of their arrows count. */
        const val MIN_RANGE = 10.0
        const val BEST_ARROWS = 5
        /** How many kinds of produce count. */
        const val BEST_KINDS = 6
        /** How many kinds of dish count at the Harvest Table. */
        const val BEST_DISHES = 3

        /** An entry's name as it is shown: a language key after a "#" (a town's cook whose name is not known), else the name itself. */
        fun named(name: String): Component = if (name.startsWith("#")) Component.translatable(name.substring(1)) else Component.literal(name)

        /** What the judge thinks of each kind of produce (before counting how much of it there is). */
        val PRODUCE: Map<Item, Int> = mapOf(
            Items.WHEAT to 1, Items.CARROT to 2, Items.POTATO to 2, Items.BEETROOT to 2, Items.MELON_SLICE to 1,
            Items.SWEET_BERRIES to 1, Items.GLOW_BERRIES to 2, Items.APPLE to 3, Items.PUMPKIN to 6, Items.CARVED_PUMPKIN to 6,
            Items.MELON to 6, Items.HAY_BLOCK to 9, Items.BREAD to 3, Items.BAKED_POTATO to 3, Items.COOKIE to 2,
            Items.PUMPKIN_PIE to 8, Items.BEETROOT_SOUP to 4, Items.MUSHROOM_STEW to 3, Items.HONEY_BOTTLE to 6,
            Items.HONEYCOMB to 4, Items.CAKE to 15, Items.GOLDEN_CARROT to 12, Items.GLISTERING_MELON_SLICE to 12,
            Items.GOLDEN_APPLE to 30, Items.ENCHANTED_GOLDEN_APPLE to 40, Items.SUGAR_CANE to 1, Items.COCOA_BEANS to 2,
        )

        /** The riddles' answers, in the order of their texts (festival.guildmark.riddle.<n>). */
        val RIDDLES: List<(ItemStack) -> Boolean> = listOf(
            { it.`is`(Items.CLOCK) },
            { it.`is`(ItemTags.CANDLES) },
            { it.`is`(Items.SNOWBALL) },
            { it.`is`(Items.BOOK) || it.`is`(Items.WRITABLE_BOOK) || it.`is`(Items.WRITTEN_BOOK) || it.`is`(Items.ENCHANTED_BOOK) },
            { it.`is`(Items.COMPASS) || it.`is`(Items.RECOVERY_COMPASS) },
            { it.`is`(ItemTags.EGGS) },
            { it.`is`(Items.GLASS_BOTTLE) },
            { it.`is`(Items.BUCKET) || it.`is`(Items.WATER_BUCKET) },
            { it.`is`(Items.LANTERN) || it.`is`(Items.SOUL_LANTERN) },
            { it.`is`(Items.HONEY_BOTTLE) },
        )
    }
}
