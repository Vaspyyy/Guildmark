package io.github.vaspyyy.guildmark.cooking.feast

import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.DishTooltip
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.Plate
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlock
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlockEntity
import io.github.vaspyyy.guildmark.cooking.recipe.Tastes
import io.github.vaspyyy.guildmark.festival.FestivalContest
import io.github.vaspyyy.guildmark.festival.FestivalPlan
import io.github.vaspyyy.guildmark.festival.FestivalRestores
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.season.Season
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * The Harvest Table at Sheafmoot (living-world/cook/design.md 6.8, Part L): the cook-off beside the Produce Show, held at a
 * capital's Sheafmoot only. What a dish is worth ([score]) is worked out here, and the trestle's plates are dressed here
 * ([show], [clear]); the entries are the festival contest's own (`FestivalContest.taste`, `cookResults`), in memory only.
 *
 * The trestle is a few top oak slabs beside the show bench, each with a Plate (Part D's) on it, put up and taken down with the
 * festival's other decorations (`FestivalPlan`'s trestle, through `FestivalRestores`). Its plates are always laid for show, the
 * town's ([Plate.show]): nothing on them can be taken, eaten or broken off, and a player's dish is never laid there.
 */
object HarvestTable {
    /** The language key of the table's name, "Harvest Table". */
    const val TITLE = "festival.guildmark.harvest_table"

    // =============================================================================================== what a dish is worth

    /** A tier's points before its kind and the season: Plain 4, Sound 8, Fine 13, Hallmarked 20; Spoilt nothing. */
    fun tierPoints(tier: Tier): Int = when (tier) {
        Tier.SPOILT -> 0
        Tier.PLAIN -> 4
        Tier.SOUND -> 8
        Tier.FINE -> 13
        Tier.HALLMARKED -> 20
    }

    /** What a kind asks of its cook, as a factor: 1 + 0.15 for each step of its complexity (2.5), pottage 1 to the roast's 1.6. */
    fun kindFactor(kind: DishKind): Double = 1.0 + KIND_STEP * kind.complexity

    /** Whether autumn favours [kind] (the season table, 2.8: stew, roots, pie, tart and roast), so it scores [AUTUMN] times more at Sheafmoot. */
    fun autumn(kind: DishKind): Boolean = Tastes.season(kind, Season.AUTUMN) > 1.0

    /** What a dish of [kind] at [tier] scores: the tier, the kind and the autumn, rounded. A Hallmarked roast is 38, a Fine pie 23. */
    fun score(kind: DishKind, tier: Tier): Int =
        Math.round(tierPoints(tier) * kindFactor(kind) * (if (autumn(kind)) AUTUMN else 1.0)).toInt()

    /** The tier a dish of [kind] was marked at, from its [points] (each tier of a kind scores differently). */
    fun tierOf(kind: DishKind, points: Int): Tier = Tier.entries.lastOrNull { it != Tier.SPOILT && score(kind, it) <= points } ?: Tier.PLAIN

    /** How a dish's points are reached, as the host and `cook feast score` tell it: "Hallmarked 20 × Spit Roast 1.6 × autumn 1.2". */
    fun working(kind: DishKind, tier: Tier): Component = Component.translatable(
        if (autumn(kind)) "message.guildmark.feast.working.autumn" else "message.guildmark.feast.working",
        DishTooltip.tierWord(tier), tierPoints(tier), dishName(kind), factor(kind),
    )

    /** The kind's own name, "Spit Roast". */
    fun dishName(kind: DishKind): MutableComponent = Component.translatable("item.guildmark.dish_${kind.id}")

    /** The kind's factor as it is written: "1", "1.15", "1.6". */
    fun factor(kind: DishKind): String = BigDecimal.valueOf(kindFactor(kind)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    // =============================================================================================== each festival's table

    /** One of the town's own entries: whose door it is from, and the one dish it is laid on its plate as. */
    internal class TownDish(val inn: Boolean, val kind: DishKind, val tier: Tier)

    /** What the table keeps for one festival (by its plan): the town's dishes, and which entry each plate shows and what was laid there last. */
    private class Table {
        val town = LinkedHashMap<UUID, TownDish>()
        var onPlate: Array<UUID?> = arrayOf()
        var laid: Array<ItemStack> = arrayOf()
    }

    private val tables = HashMap<FestivalPlan, Table>()

    /** Plates dressed since the server started, for `cook perf`. */
    private var dressed = 0

    private fun table(plan: FestivalPlan): Table = tables[plan] ?: Table().also {
        // A festival that is over keeps nothing here
        if (tables.size >= 4) tables.keys.removeIf { p -> Festivities.all().none { s -> s.live && s.plan === p } }
        tables[plan] = it
    }

    /** Seeds the town's own entries when the fair opens at [site] (stub: nothing). */
    fun townEntries(level: ServerLevel, site: FestivalSite) {}

    // =============================================================================================== the trestle's plates

    /**
     * Lays the best entries of [contest] on the trestle of [plan], one to a plate: an entry keeps its plate while it stays among the
     * best, one that falls out of them gives its plate to the newcomer, and the rest stay bare. A player's plate shows their best
     * dish, a town entry's its own; each is named for its cook and their score ("Ransom: Hallmarked Spit Roast, 72 points"). With the
     * table not held here (or its switch off) every plate is bare, and it is still the town's. Plates in ground not loaded wait.
     */
    fun show(level: ServerLevel, plan: FestivalPlan, contest: FestivalContest) {
        val plates = plan.trestle
        if (plates.isEmpty()) return
        val table = table(plan)
        val n = plates.size
        if (table.onPlate.size != n) {
            table.onPlate = arrayOfNulls(n)
            table.laid = Array(n) { ItemStack.EMPTY }
        }
        val top = if (contest.cooking()) contest.cookResults().take(n) else listOf()
        val best = top.mapTo(HashSet()) { it.id }
        for (i in 0 until n) if (table.onPlate[i] !in best) table.onPlate[i] = null
        for (e in top) {
            if (e.id in table.onPlate) continue
            val free = table.onPlate.indexOfFirst { it == null }
            if (free >= 0) table.onPlate[free] = e.id
        }
        for (i in 0 until n) {
            val entry = table.onPlate[i]?.let { id -> top.firstOrNull { it.id == id } }
            lay(level, plates[i], entry?.let { plateOf(level, contest, table, it) } ?: ItemStack.EMPTY, table, i)
        }
    }

    /** Bares every plate on the trestle of [plan] (left dressed from before a restart, say); they stay the town's. */
    fun clear(level: ServerLevel, plan: FestivalPlan) {
        for (pos in plan.trestle) Plate.show(level, pos, ItemStack.EMPTY)
        tables[plan]?.let {
            it.onPlate = arrayOf()
            it.laid = arrayOf()
        }
    }

    /** Lays [stack] on the plate at [pos], the [i]th: a clink and a puff where it changes, nothing where it stays as it was. */
    private fun lay(level: ServerLevel, pos: BlockPos, stack: ItemStack, table: Table, i: Int) {
        if (!level.isLoaded(pos) || !Plate.show(level, pos, stack)) return
        val before = table.laid[i]
        if (ItemStack.matches(before, stack)) return
        table.laid[i] = stack.copy()
        dressed++
        if (stack.isEmpty && before.isEmpty) return
        level.playSound(null, pos, CookSounds.get("cook.plate"), SoundSource.BLOCKS, 0.6f, if (stack.isEmpty) 0.85f else 1.0f + level.random.nextFloat() * 0.1f)
        level.sendParticles(if (stack.isEmpty) ParticleTypes.POOF else ParticleTypes.HAPPY_VILLAGER, pos.x + 0.5, pos.y + 0.25, pos.z + 0.5, 3, 0.2, 0.1, 0.2, 0.0)
    }

    /** The dish [entry]'s plate shows: their best (a town entry's own), made up fresh today and named for them and their score. */
    private fun plateOf(level: ServerLevel, contest: FestivalContest, table: Table, entry: FestivalContest.Entry): ItemStack {
        val kind: DishKind
        val tier: Tier
        if (entry.npc) {
            val dish = table.town[entry.id] ?: return ItemStack.EMPTY
            kind = dish.kind
            tier = dish.tier
        } else {
            val (k, points) = contest.dishesOf(entry.id).maxByOrNull { it.value } ?: return ItemStack.EMPTY
            kind = k
            tier = tierOf(k, points)
        }
        val stack = Dishes.make(kind, DishData(tier, Cooking.today(level), if (entry.npc) "" else entry.name, null, 0), 1)
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("message.guildmark.feast.plate", FestivalContest.named(entry.name), stack.hoverName, entry.score))
        return stack
    }

    /** Whether [pos] holds a plate of a festival's laid for show (the trestle's): nobody breaks it, nor does a blast. */
    private fun guarded(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean =
        state.block is PlateBlock && (level.getBlockEntity(pos) as? PlateBlockEntity)?.forShow == true && FestivalRestores.recordAt(level, pos) != null

    // =============================================================================================== registration

    /** Part L's registration: its line for `cook perf`, and the trestle's plates kept whole. */
    fun register() {
        CookStats.readout("Harvest Table") { stats() }
        Cooking.onForget {
            tables.clear()
            dressed = 0
        }
        NeoForge.EVENT_BUS.addListener(BreakBlockEvent::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (event.player?.isCreative == true || !guarded(level, event.pos, event.state)) return@addListener
            event.isCanceled = true
            event.player?.sendOverlayMessage(Component.translatable("message.guildmark.cook.plate_show"))
        }
        NeoForge.EVENT_BUS.addListener(ExplosionEvent.Detonate::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (tables.isEmpty()) return@addListener
            event.affectedBlocks.removeIf { guarded(level, it, level.getBlockState(it)) }
        }
    }

    private fun stats(): String = "${tables.size} tables kept; $dressed plates dressed"

    /** Each step of a kind's complexity, and autumn's favour. */
    private const val KIND_STEP = 0.15
    const val AUTUMN = 1.2
}
