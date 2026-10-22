package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.LarderBlockEntity
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.cooking.market.Owed
import io.github.vaspyyy.guildmark.cooking.vessel.VesselBlockEntity
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.build.TownWorksSite
import io.github.vaspyyy.guildmark.economy.works.build.WorksBuildData
import io.github.vaspyyy.guildmark.economy.works.build.WorksPlans
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.Containers
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CampfireBlock
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.IdentityHashMap
import java.util.UUID

/**
 * The cookshop, a works of the town (living-world/cook/design.md 6.4): its record and the Larder that is a view of it, its opening
 * and closing, how it looks (its plates, its slate, its chimney and its fires), its keeper and its free fire. Part H's. The works
 * functions are `internal` because [WorksRec] is.
 *
 * The shop is its owner's record in the ledger ([ShopRec], one for each owner in each capital), not anything in the world: the
 * Larder block is given the record's stock as its container ([ShopStock]), and the plates and the slate are drawn from it, so
 * breaking a block loses nothing and the builders' mending of it in the morning needs nothing but the next look ([second]). It is open
 * from seven until half past five, a merchant's day and a stall's; its Hob and Bake oven are lit, and burn no fuel, while it is.
 * With `cookShops` off the shop stands as it was, shut, and its stock stays.
 */
object CookShops {
    /** The cooking level a cook must have before the town offers a cookshop. */
    const val LEVEL = 25

    /** The hours a cookshop is open: from seven until half past five. */
    const val OPEN = 7.0
    const val SHUT = 17.5

    /** Ticks between the rebuilds of the set of keepers' residents. */
    private const val KEEPERS_EVERY = 100L

    /** What the shops' Larders are given (by record), where their parts stand (by works id), and the fires that are theirs (by position). */
    private val views = IdentityHashMap<ShopRec, ShopStock>()
    private val layouts = HashMap<Int, ShopLayout>()
    private val stations = HashMap<Long, Int>()

    /** What each shop was last dressed for (the day, the hour and whether it was open). */
    private val dressed = HashMap<Int, Long>()

    /** The residents who keep a cookshop, as last looked (so the market hall's screen is never opened on them). */
    private var keepers: Set<Int> = emptySet()
    private var keepersAt = Long.MIN_VALUE / 2

    private fun forget() {
        views.clear()
        layouts.clear()
        stations.clear()
        dressed.clear()
        keepers = emptySet()
        keepersAt = Long.MIN_VALUE / 2
    }

    // ---- The clock ----

    /** The hour of the town's day, in hours from midnight (7.5 is half past seven). */
    internal fun hour(level: ServerLevel): Double = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0

    /** Whether shops keep their hours now: cookshops are on and it is between seven and half past five. */
    internal fun isOpen(level: ServerLevel): Boolean = Cooking.shops() && Works.enabled() && hour(level).let { it >= OPEN && it < SHUT }

    // ---- Records ----

    /** [works]'s shop, if its owner has one in the capital's record. */
    internal fun shopOf(level: ServerLevel, works: WorksRec): ShopRec? {
        val owner = works.owner ?: return null
        return Kitchens.get(level).rec(works.capital)?.shops?.get(owner)
    }

    /** [works]'s shop, started if it has none (the works stands, so the shop is not closed). */
    internal fun ensure(level: ServerLevel, works: WorksRec): ShopRec? {
        val owner = works.owner ?: return null
        val kitchens = Kitchens.get(level)
        val shops = kitchens.rec(works.capital)?.shops ?: kitchens.shops(works.capital)
        val shop = shops.getOrPut(owner) {
            kitchens.changed()
            ShopRec.empty(owner)
        }
        if (shop.closed) {
            shop.closed = false
            kitchens.changed()
        }
        return shop
    }

    /** The Larder's container for [shop]: its stock, as the block sees it. */
    fun view(level: ServerLevel, shop: ShopRec): ShopStock = views.getOrPut(shop) { ShopStock(level, shop) }

    /** Whether [player] may open a shop's Larder: its owner, and the owner's party. */
    fun mayOpen(level: ServerLevel, shop: ShopRec, player: Player): Boolean {
        if (player.uuid == shop.owner) return true
        return Parties.get(level.server).of(shop.owner)?.members?.contains(player.uuid) == true
    }

    // ---- Opening and closing ----

    /** A cookshop has been built and opens (from `Works.open`): its record is made, and its owner is told. */
    internal fun open(level: ServerLevel, works: WorksRec) {
        val owner = works.owner ?: return
        val shop = ensure(level, works) ?: return
        val on = level.server.playerList.getPlayer(owner)
        val name = on?.name?.string ?: works.pledges.lastOrNull { it.player == owner }?.name
        if (name != null) shop.name = name
        shop.touched = true
        Kitchens.get(level).changed()
        on?.sendSystemMessage(Component.translatable("message.guildmark.cook.shop.open").withStyle(ChatFormatting.GOLD))
    }

    /**
     * A cookshop has been taken down (from `Works.close`): its stock goes into the record's returns and its till into what the owner is
     * owed, and both are handed over at once if the owner is on, else at their next login; then the record goes. What the keeper was
     * owed is the town's.
     */
    internal fun close(level: ServerLevel, works: WorksRec) {
        val owner = works.owner ?: return
        val kitchens = Kitchens.get(level)
        val rec = kitchens.rec(works.capital) ?: return
        val shop = rec.shops[owner] ?: return
        for (slot in shop.stock.indices) {
            val stack = shop.stock[slot]
            if (stack.isEmpty) continue
            shop.returns.add(stack)
            shop.stock[slot] = ItemStack.EMPTY
        }
        shop.owed += shop.till
        shop.till = 0
        shop.wages = 0.0
        shop.waiting.clear()
        shop.closed = true
        views.remove(shop)
        forget(works.id)
        kitchens.changed()
        val on = level.server.playerList.getPlayer(owner)
        if (on != null && on.isAlive) deliver(level, on, shop)
        if (shop.owed <= 0 && shop.returns.isEmpty()) rec.shops.remove(owner)
        kitchens.changed()
    }

    /** What is held for works [id] in memory goes. */
    private fun forget(id: Int) {
        layouts.remove(id)
        dressed.remove(id)
        stations.values.removeIf { it == id }
    }

    /**
     * Hands [player] what their closed shop owes them: its till as Marks and its stock back (what does not fit in the pack drops at their
     * feet), and says so. Taken off the record in the same step, so it is handed over only once. False if there was nothing.
     */
    internal fun deliver(level: ServerLevel, player: ServerPlayer, shop: ShopRec): Boolean {
        if (shop.owner != player.uuid || shop.owed <= 0 && shop.returns.isEmpty()) return false
        val marks = shop.owed
        val stacks = shop.returns.toList()
        shop.owed = 0
        shop.returns.clear()
        Kitchens.get(level).changed()
        if (marks > 0) Trading.give(player, ModItems.GUILD_MARK.get(), marks)
        var dishes = 0
        for (stack in stacks) {
            dishes += stack.count
            player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        }
        val said = Component.translatable("message.guildmark.cook.shop.closed")
        if (marks > 0) said.append(" ").append(Component.translatable("message.guildmark.cook.shop.paid", Owed.marks(marks)))
        if (dishes > 0) said.append(" ").append(Component.translatable("message.guildmark.cook.shop.back", dishes))
        player.sendSystemMessage(said.withStyle(ChatFormatting.GOLD))
        return true
    }

    /** At [player]'s login: whatever a shop of theirs that was taken down while they were away owes them. */
    private fun login(player: ServerPlayer) {
        val level = player.level().server.overworld()
        val kitchens = Kitchens.get(level)
        for (rec in kitchens.all()) {
            val shop = rec.shops[player.uuid] ?: continue
            if (shop.owed <= 0 && shop.returns.isEmpty()) continue
            deliver(level, player, shop)
            if (shop.closed && shop.owed <= 0 && shop.returns.isEmpty()) rec.shops.remove(player.uuid)
            kitchens.changed()
        }
    }

    // ---- What it is ----

    /** Whether [stack] is a dish a shop can sell (Gruel, Scorched mess and Spoilt dishes are not). */
    internal fun sellable(stack: ItemStack): Boolean =
        !stack.isEmpty && Dishes.kindOf(stack) != null && Dishes.dataOf(stack)?.tier != Tier.SPOILT

    /** Whether [works] has something to sell, for its awning: cookshops are on and its larder holds a dish that can be sold. */
    internal fun stocked(level: ServerLevel, works: WorksRec): Boolean {
        if (!Cooking.shops()) return false
        val shop = shopOf(level, works) ?: return false
        return shop.stock.any { sellable(it) }
    }

    /** What `works` says of a standing cookshop: its stock, its till and what its keeper is owed. */
    internal fun describe(level: ServerLevel, works: WorksRec): String {
        val shop = shopOf(level, works) ?: return "a cookshop with no record in the ledger yet"
        val stock = shop.stock.filter { !it.isEmpty }.groupBy { Dishes.kindOf(it)?.id ?: "other" }
            .map { (kind, stacks) -> "${stacks.sumOf { it.count }} $kind" }.joinToString(", ").ifEmpty { "nothing in its larder" }
        return "sells for ${shop.name.ifEmpty { "its owner" }}: $stock; ${shop.till} Marks in its till" +
            (if (shop.wages > 1.0e-6) ", its keeper owed %.2f".format(shop.wages) else "") +
            (if (!Cooking.shops()) "; selling is switched off" else "")
    }

    /** The works screen's line for a standing cookshop: the dishes in its larder and the Marks in its till. */
    fun doing(level: ServerLevel, capital: Long, owner: UUID?): Component {
        owner ?: return Component.empty()
        val shop = Kitchens.get(level).rec(capital)?.shops?.get(owner) ?: return Component.empty()
        return Component.translatable("gui.guildmark.works.doing.cookshop", view(level, shop).portions(), shop.till)
    }

    /** Where a cookshop's keeper stands: behind the middle of the counter. */
    internal fun post(level: ServerLevel, works: WorksRec): BlockPos? = layout(level, works.id)?.stand

    /** Whether [p] keeps a cookshop, so that no market shop is made for them (a click on them is a talk, not the hall's screen). */
    fun keeps(p: Townsperson): Boolean {
        if (p.role != TownRole.MERCHANT || p.resident < 0) return false
        val level = p.level() as? ServerLevel ?: return false
        val now = level.gameTime
        if (now - keepersAt !in 0 until KEEPERS_EVERY) {
            keepersAt = now
            keepers = WorksData.get(level).works.values.filter { it.kind == WorksKind.COOKSHOP && it.hired >= 0 }.map { it.hired }.toSet()
        }
        return p.resident in keepers
    }

    // ---- Where its parts are ----

    private fun layout(id: Int, planned: WorksPlans.Planned): ShopLayout {
        layouts[id]?.let { if (it.planned === planned) return it }
        return ShopLayout(planned).also { layouts[id] = it }
    }

    /** Works [id]'s parts, if it is sited. */
    private fun layout(level: ServerLevel, id: Int): ShopLayout? {
        val rec = WorksBuildData.get(level)[id]?.takeIf { it.sited && !it.removed } ?: return null
        return layout(id, WorksPlans.of(rec))
    }

    /**
     * Whether the fire at [pos] is a cookshop's Hob or Bake oven, burning no fuel while the shop is open (from `Fuel.free`, once a second
     * for each lit fire near a player). Found from the works whose ground holds it, and remembered.
     */
    fun freeAt(level: ServerLevel, pos: BlockPos): Boolean {
        if (!isOpen(level)) return false
        val key = pos.asLong()
        val known = stations[key]
        if (known != null) {
            val works = Works.record(level, known)
            if (works != null && works.kind == WorksKind.COOKSHOP && works.state == WorksState.DONE) return true
            stations.remove(key)
        }
        val view = Works.at(level, pos) ?: return false
        if (view.kind != WorksKind.COOKSHOP || view.state != WorksState.DONE) return false
        val lay = layout(level, view.id) ?: return false
        if (pos != lay.hob && pos != lay.oven) return false
        stations[key] = view.id
        return true
    }

    // ---- Each second ----

    /**
     * Each second of a standing cookshop whose land is loaded, once its opening is over (from `WorksAtWork.second`, after its awning):
     * the Larder is given the stock, the Hob and the Bake oven are lit while the shop is open (and put out when it shuts), the chimney's
     * fire follows them, and the plates and the slate are dressed again when the stock, the hour or the day has changed. What it learns
     * of the town (its look, its owner's name) it keeps in the record.
     */
    internal fun second(level: ServerLevel, works: WorksRec, site: TownWorksSite, capital: CapitalPiece, hour: Double) {
        val shop = ensure(level, works) ?: return
        val lay = layout(works.id, site.planned)
        val kitchens = Kitchens.get(level)
        if (shop.style != capital.style.ordinal) {
            shop.style = capital.style.ordinal
            shop.touched = true
            kitchens.changed()
        }
        level.server.playerList.getPlayer(shop.owner)?.name?.string?.let {
            if (it != shop.name) {
                shop.name = it
                shop.touched = true
                kitchens.changed()
            }
        }
        link(level, lay, shop)
        val open = Cooking.shops() && hour >= OPEN && hour < SHUT
        fires(level, lay, open)
        chimney(level, lay, open)
        val day = Cooking.today(level)
        val key = (day * 24L + hour.toInt()) * 2L + (if (open) 1L else 0L)
        if (shop.touched || dressed[works.id] != key || ShopLook.lost(level, lay)) {
            shop.touched = false
            dressed[works.id] = key
            ShopLook.dress(level, shop, lay, open, Prices.of(level, works.capital, kitchens.rec(works.capital), shop, day))
            // The dishes seen through the Larder's slats follow the stock
            lay.larder?.takeIf { level.isLoaded(it) }?.let { (level.getBlockEntity(it) as? LarderBlockEntity)?.refresh() }
        }
    }

    /**
     * Gives the Larder block the shop's stock as its container, if it has not been given it (a Larder the builders laid, or laid again,
     * or a chunk loaded again): what the block held of its own in the moment before goes into the stock, and what does not fit drops.
     */
    private fun link(level: ServerLevel, lay: ShopLayout, shop: ShopRec) {
        val pos = lay.larder ?: return
        if (!level.isLoaded(pos)) return
        val larder = level.getBlockEntity(pos) as? LarderBlockEntity ?: return
        val stock = view(level, shop)
        if (larder.backing === stock) return
        if (larder.backing == null) {
            for (slot in 0 until LarderBlockEntity.SIZE) {
                val own = larder.getItem(slot)
                if (own.isEmpty) continue
                larder.removeItemNoUpdate(slot)
                stock.insert(own)
                if (!own.isEmpty) Containers.dropItemStack(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, own)
            }
        }
        larder.backing = stock
        larder.refresh()
    }

    /** The Hob and the Bake oven lit while the shop is open (if nothing has been started in them), and brought up to date when it shuts. */
    private fun fires(level: ServerLevel, lay: ShopLayout, open: Boolean) {
        for (pos in listOfNotNull(lay.hob, lay.oven)) {
            if (!level.isLoaded(pos)) continue
            val station = level.getBlockEntity(pos) as? VesselBlockEntity ?: continue
            val vessel = station.vessel
            if (open && !vessel.lit && vessel.open) {
                vessel.lit = true
                vessel.dirty = true
                station.catchUp(level)
            } else if (!open && vessel.lit) {
                // The town's fire is out: a fire with no fuel of its own goes out with it
                station.catchUp(level)
            }
        }
    }

    /** The chimney's fire burns while the shop is open. */
    private fun chimney(level: ServerLevel, lay: ShopLayout, open: Boolean) {
        val pos = lay.chimney ?: return
        if (!level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        if (state.block !is CampfireBlock || state.getValue(CampfireBlock.LIT) == open) return
        level.setBlock(pos, state.setValue(CampfireBlock.LIT, open), Block.UPDATE_ALL)
    }

    // ---- Selling ----

    /**
     * The portions the shops of [entry]'s market sold in [hour] (a market hour, counted from the world's first): the ledger's
     * `Kitchens.step` asks at the clock hours 8, 12 and 16 and adds what is answered to what the town ate. See [ShopSales].
     */
    fun sellHour(level: ServerLevel, entry: Markets.Entry, hour: Long): Double = ShopSales.hour(level, entry, hour)

    // ---- Registration ----

    /** Part H's registration: what a closed shop owes is handed over at login, and the in-memory tables are cleared when the server stops. */
    fun register() {
        Cooking.onForget { forget() }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let { login(it) } }
    }
}
