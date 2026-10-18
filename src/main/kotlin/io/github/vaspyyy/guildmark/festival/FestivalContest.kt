package io.github.vaspyyy.guildmark.festival

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
 * Every player who took part gets something at the prize-giving (see [Festivities.award]).
 */
class FestivalContest(val kind: FestivalKind, private val riddleSeed: Long) {
    /** Someone's standing: their score and when they reached it (the earlier wins a tie). */
    class Entry(val id: UUID, var name: String, var score: Int = 0, var at: Long = 0L)

    private val entries = LinkedHashMap<UUID, Entry>()
    private val ribbonsFound = HashMap<UUID, MutableSet<Int>>()
    private val arrows = HashMap<UUID, MutableList<Int>>()
    private val shown = HashMap<UUID, MutableMap<Item, Int>>()
    private val solved = HashMap<UUID, MutableSet<Int>>()
    private val reading = HashMap<UUID, Int>()

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
        return when {
            !open -> Component.translatable("message.guildmark.festival.contest_closed", title)
            best == null -> Component.translatable("message.guildmark.festival.hint.${kind.id}")
            best.id == player.uuid -> Component.translatable("message.guildmark.festival.leading", title, mine)
            else -> Component.translatable("message.guildmark.festival.standing", title, mine, best.name, best.score)
        }
    }

    companion object {
        /** How far off an archer has to stand for an arrow to count, and how many of their arrows count. */
        const val MIN_RANGE = 10.0
        const val BEST_ARROWS = 5
        /** How many kinds of produce count. */
        const val BEST_KINDS = 6

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
