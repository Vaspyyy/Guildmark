package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.Plate
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlockEntity
import io.github.vaspyyy.guildmark.cooking.market.MenuBoardBlockEntity
import io.github.vaspyyy.guildmark.cooking.market.Owed
import io.github.vaspyyy.guildmark.economy.works.build.WorksPlans
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.abs

/**
 * Where a cookshop's parts stand in the world, found once from its drawing (living-world/cook/design.md 6.4): the Larder, the
 * Hob and the Bake oven, the Prep Board, the Menu board, the five plates (the middle one first, then outward), the chimney's fire,
 * where the keeper stands behind the counter and where the buyers do before it.
 */
internal class ShopLayout(val planned: WorksPlans.Planned) {
    var larder: BlockPos? = null
        private set
    var hob: BlockPos? = null
        private set
    var oven: BlockPos? = null
        private set
    var board: BlockPos? = null
        private set
    var prep: BlockPos? = null
        private set
    var chimney: BlockPos? = null
        private set

    /** The plates on the counter, the middle one first, then the buyer's left of it, then the right, and so outward. */
    val plates: List<BlockPos>

    /** Behind the middle of the counter, where the keeper stands; and before it, where the buyers do. */
    val stand: BlockPos

    /** Where the buyers stand, and the way they face (the counter is the other way). */
    val door: BlockPos
    val front: Direction

    init {
        val f = planned.frame
        val d = planned.drawing
        val across = ArrayList<Pair<Int, BlockPos>>()
        for (s in planned.plan.steps) {
            if (s.kind != StepKind.BLOCK) continue
            val pos = f.world(s.x, s.y, s.z)
            when (s.b.m) {
                M.LARDER -> larder = pos
                M.HOB -> hob = pos
                M.BAKE_OVEN -> oven = pos
                M.MENU_BOARD -> board = pos
                M.PREP_BOARD -> prep = pos
                M.CAMPFIRE -> chimney = pos
                M.PLATE -> across.add(s.x to pos)
                else -> {}
            }
        }
        plates = across.sortedWith(compareBy<Pair<Int, BlockPos>> { abs(it.first - d.heart.x) }.thenByDescending { it.first }).map { it.second }
        stand = f.world(d.heart.x, d.y0, d.heart.z + 1)
        door = f.world(d.door.x, d.y0, d.door.z)
        front = f.front
    }
}

/**
 * How a cookshop looks (6.4): the five plates on its counter dressed with the first five kinds it sells, dearest first (bare while
 * it is shut), and the chalk of its Menu board: the owner's name, what is in the larder and what each portion brings, or why there is
 * nothing. Drawn from the shop's record, and only when something has changed (the stock, the hour, the day), so a shop costs a
 * glance at its plates each second and a rewrite of its slate a few times a day.
 */
internal object ShopLook {
    /** The rows of the slate: the dearest offers, and a line for how many more there are. */
    const val ROWS = 4

    /** [shop]'s plates and slate dressed as they should be: its offer at [prices] while it is [open], bare and shut when it is not. */
    fun dress(level: ServerLevel, shop: ShopRec, lay: ShopLayout, open: Boolean, prices: Prices) {
        val offers = if (open) offers(shop, prices) else emptyList()
        val seen = HashSet<DishKind>()
        val dishes = ArrayList<ItemStack>()
        for (o in offers) if (dishes.size < lay.plates.size && seen.add(o.kind)) dishes.add(shop.stock[o.slot].copyWithCount(1))
        for ((i, pos) in lay.plates.withIndex()) laid(level, pos, dishes.getOrNull(i) ?: ItemStack.EMPTY)
        lay.board?.let { chalk(level, it, shop, offers, open) }
    }

    /** Whether something of the dressing was lost (a plate or the slate was laid again by the builders): it is to be done afresh. */
    fun lost(level: ServerLevel, lay: ShopLayout): Boolean {
        val plate = lay.plates.firstOrNull()
        if (plate != null && level.isLoaded(plate) && (level.getBlockEntity(plate) as? PlateBlockEntity)?.forShow == false) return true
        val board = lay.board
        return board != null && level.isLoaded(board) && (level.getBlockEntity(board) as? MenuBoardBlockEntity)?.kept == false
    }

    /** One portion of [dish] laid on the plate at [pos] (bare for an empty one), with a drift of steam off it when it is a new dish. */
    private fun laid(level: ServerLevel, pos: BlockPos, dish: ItemStack) {
        if (!level.isLoaded(pos)) return
        val plate = level.getBlockEntity(pos) as? PlateBlockEntity ?: return
        val before = plate.dish.copy()
        if (!Plate.show(level, pos, dish)) return
        if (!dish.isEmpty && !ItemStack.isSameItemSameComponents(before, dish)) {
            level.sendParticles(CookParticles.STEAM.get(), pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, 3, 0.12, 0.04, 0.12, 0.01)
        }
    }

    private fun chalk(level: ServerLevel, pos: BlockPos, shop: ShopRec, offers: List<Offer>, open: Boolean) {
        if (!level.isLoaded(pos)) return
        val board = level.getBlockEntity(pos) as? MenuBoardBlockEntity ?: return
        val lines = ArrayList<Component>()
        val kinds = ArrayList<String>()
        lines.add(title(shop))
        kinds.add("")
        when {
            !open -> {
                lines.add(Component.translatable("gui.guildmark.cook.shop.menu.shut"))
                kinds.add("")
            }
            offers.isEmpty() -> {
                lines.add(Component.translatable("gui.guildmark.cook.shop.menu.empty"))
                kinds.add("")
            }
            else -> {
                for (o in offers.take(ROWS)) {
                    lines.add(line(o))
                    kinds.add(o.kind.id)
                }
                if (offers.size > ROWS) {
                    lines.add(Component.translatable("gui.guildmark.cook.shop.menu.more", offers.size - ROWS))
                    kinds.add("")
                }
            }
        }
        board.setLines(lines, kinds)
    }

    /** The slate's heading: whose cookshop it is. */
    fun title(shop: ShopRec): Component =
        if (shop.name.isEmpty()) Component.translatable("gui.guildmark.cook.shop.menu.title.none") else Component.translatable("gui.guildmark.cook.shop.menu.title", shop.name)

    /** A row of the slate: "Fine Raised Pie x6: 1.2 Marks", with "(stale)" after a dish that is going. */
    fun line(o: Offer): Component {
        val row = Component.translatable("gui.guildmark.cook.shop.menu.line", tier(o.tier), Owed.dish(o.kind), o.portions, price(o.pay))
        return if (o.stage == Stage.STALE) Component.translatable("gui.guildmark.cook.shop.menu.stale", row) else row
    }

    /** Marks to a tenth, written the same wherever the game is played. */
    fun price(marks: Double): String = String.format(Locale.ROOT, "%.1f", marks)

    /** The word for a dish's tier ("Fine"), as the keeper's action bar line has it. */
    fun tier(tier: Tier): Component = Component.translatable(
        when (tier) {
            Tier.HALLMARKED -> "message.guildmark.cook.tier.hallmarked"
            Tier.FINE -> "message.guildmark.cook.tier.fine"
            Tier.SOUND -> "message.guildmark.cook.tier.sound"
            Tier.SPOILT -> "message.guildmark.cook.tier.spoilt"
            Tier.PLAIN -> "message.guildmark.cook.tier.plain"
        },
    )
}
