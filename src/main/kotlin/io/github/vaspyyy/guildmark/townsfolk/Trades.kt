package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.city.plan.FarmKind
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Route
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.mill.Mills
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * The town's goods in its people's hands, the way its market ([Markets]) has them move: the carters loading their
 * handcarts a sack at a time and pulling them from the barns to the mill's hopper, flour from the mill to the bakery
 * and the farms' yield to the stall that sells it (a sack on the shoulder where there's no cart); the miller at the stones while they grind; the baker carrying the loaves out to the counter, or at a loss
 * by a cold oven when there's no flour; the farmer's full basket taken in to the barn and the fisher's to the
 * fishmonger; and the shopper who finds a stall sold out. Where nothing is known of the market (the markets are
 * switched off) they go about it all the same, as an ordinary day would have it. Who the carters and millers are
 * is in [Hands].
 */
object Trades {
    private fun level(p: Townsperson): ServerLevel = p.level() as ServerLevel
    private fun r(p: Townsperson, n: Int): Int = p.random.nextInt(n)

    /** How fast someone goes with a sack on their shoulder or a full basket on their arm. */
    const val LADEN = 0.42

    /** How many crops a farmer gathers into the basket, or catches a fisher lands, before taking them in. */
    const val BASKET = 4

    /** The ticks goods leave the hands as put_down sets them down, and as a sack is tipped or poured out. */
    private const val SET_DOWN = 11
    private const val TIPPED = 12

    /** [life]'s capital's market, if the markets are on and it keeps one yet. */
    fun market(p: Townsperson, life: Life): Markets.Entry? =
        if (Markets.enabled()) Markets.get(level(p))[life.places.capital.center] else null

    /** The clock's hour, 0 at midnight. */
    private fun clock(level: ServerLevel): Int = Math.floorMod(Math.floorDiv(level.overworldClockTime, 1000L) + 6, 24L).toInt()

    // --- At work ---

    /** Their trade's part in the town's goods just now, if it has one; otherwise they're at their trade ([Jobs.work]). */
    fun work(p: Townsperson, life: Life): Task? = when (p.role) {
        TownRole.FARMER -> milling(p, life) ?: basket(p, life)
        TownRole.BAKER -> bakery(p, life)
        TownRole.FISHER -> landing(p, life)
        else -> null
    }

    /** One more crop in [p]'s basket (or catch in the fisher's), to be taken in once it's full. */
    fun gathered(p: Townsperson, goods: Item) {
        val life = p.life ?: return
        life.basket++
        life.gathered = goods
    }

    // --- Carrying ---

    /** Lifts the lid of the chest or barrel at [pos] while [p] is at it (see [Townsperson.opened]). */
    private fun open(level: ServerLevel, p: Townsperson, pos: BlockPos) {
        val box = level.getBlockEntity(pos) as? Container ?: return
        p.opened = pos
        box.startOpen(p)
    }

    /** Lets the lid of the chest or barrel at [pos] fall again. */
    private fun close(level: ServerLevel, p: Townsperson, pos: BlockPos) {
        if (p.opened == pos) p.opened = null
        (level.getBlockEntity(pos) as? Container)?.stopOpen(p)
    }

    /** Where to stand at the store at [pos], facing it: beside it, or beside what it stands on. */
    private fun at(level: ServerLevel, pos: BlockPos, from: BlockPos): Spot? = Places.beside(level, pos, from) ?: Places.beside(level, pos.below(), from)

    /**
     * A barrel or a chest in one of [among] (the first few of them, picked from at random), loaded, and where to
     * stand at it; none of [not] (the mill's hopper, when it's the flour that's wanted).
     */
    private fun store(p: Townsperson, among: List<CityPlaces.Lodging>, from: BlockPos, not: Set<BlockPos> = setOf()): Pair<BlockPos, Spot>? {
        val level = level(p)
        val choices = among.filter { l -> l.stores.any { it !in not && level.isLoaded(it) } }.take(3)
        if (choices.isEmpty()) return null
        val lodging = choices[r(p, choices.size)]
        val door = lodging.front?.inside ?: from
        for (pos in lodging.stores.filter { it !in not && level.isLoaded(it) }.shuffled(Random(p.random.nextLong()))) {
            at(level, pos, door)?.let { return pos to it }
        }
        return null
    }

    /**
     * [goods] taken up at the store at [pos] from [spot]: walked to empty-handed, the lid lifted, and out they
     * come; [got] hears the moment they're in hand.
     */
    private fun take(p: Townsperson, life: Life, goods: ItemStack, pos: BlockPos, spot: Spot, got: (() -> Unit)? = null): Task {
        val level = level(p)
        val taking = Work(p, spot, Jobs.top(pos, 0.9), listOf(Step(Activity.PICK_UP), Step(Activity.NONE, 8)), pos, onTick = { s, t ->
            if (s.activity == Activity.PICK_UP) {
                if (t == 1) open(level, p, pos)
                // In hand as pick_up starts to lift
                if (t == 8) {
                    p.carried = goods.copy()
                    close(level, p, pos)
                    got?.invoke()
                }
            }
        })
        return Acts.via(p, life, spot.pos, taking)
    }

    /**
     * What's in [p]'s hands carried (laden, [overlay] showing how: a sack on the shoulder, a crate, a basket) to
     * [spot] and left there with [drop] (put away in a barrel, tipped into the hopper, handed over a counter),
     * looking at [look]; [handed] hears the moment it leaves their hands, or for a hand-over the moment it begins (so
     * whoever takes it reaches out as they do, the goods passing at [Acts.PASS]).
     */
    private fun carry(
        p: Townsperson, life: Life, overlay: Activity, spot: Spot, look: Vec3, drop: Activity, store: BlockPos? = null,
        handed: (() -> Unit)? = null,
    ): Task {
        val level = level(p)
        val release = when (drop) {
            Activity.PUT_DOWN -> SET_DOWN
            Activity.HAND_OVER -> Acts.PASS
            else -> TIPPED
        }
        val leaving = Work(p, spot, look, listOf(Step(drop), Step(Activity.NONE, 15)), store, onTick = { s, t ->
            if (s.activity == drop) {
                if (t == 0 && drop == Activity.HAND_OVER) handed?.invoke()
                if (t == 1 && store != null) open(level, p, store)
                if (t == release) {
                    p.carried = ItemStack.EMPTY
                    if (store != null) close(level, p, store)
                    if (drop != Activity.HAND_OVER) handed?.invoke()
                }
            }
        })
        return Ways.to(p, life.places, spot.pos, LADEN, near = 1.0, overlay = overlay).andThen(leaving).andThen(Acts.hold(p, ItemStack.EMPTY))
    }

    // --- The carters ---

    /** The farms' buildings that keep what they yield until it's carted off. */
    private val STORES = setOf(FarmKind.BARN.name, FarmKind.FARMHOUSE.name)
    private val HERDS = setOf(FarmKind.BARN.name)
    private val HENS = setOf(FarmKind.COOP.name, FarmKind.BARN.name)

    /** The mills, and the bakeries their flour goes to. */
    val MILLS = setOf(FarmKind.WINDMILL.name, FarmKind.MILL.name)
    private val BAKERIES = setOf(Kind.BAKERY.name, FarmKind.MILL.name)

    private fun keeps(good: Good): Set<String> = when (good) {
        Good.BEEF, Good.PORK, Good.MUTTON, Good.LEATHER, Good.WOOL -> HERDS
        Good.CHICKEN, Good.FEATHER -> HENS
        else -> STORES
    }

    /** What a unit of [good] looks like on its way in from the farms: the butcher's meat still raw. */
    private fun raw(good: Good): ItemStack = when (good) {
        Good.BEEF -> ItemStack(Items.BEEF)
        Good.PORK -> ItemStack(Items.PORKCHOP)
        Good.MUTTON -> ItemStack(Items.MUTTON)
        Good.CHICKEN -> ItemStack(Items.CHICKEN)
        else -> good.item?.let { ItemStack(it) } ?: ItemStack.EMPTY
    }

    /** The fewest and the most sacks or crates on a cartload. */
    private const val LOAD_LEAST = 2
    private const val LOAD_MOST = 6

    /** What the farms send most of when there's no market to go by. */
    private val YIELD = listOf(Good.WHEAT, Good.WHEAT, Good.CARROT, Good.POTATO, Good.BEETROOT, Good.APPLE, Good.WOOL, Good.BEEF, Good.CHICKEN)

    /**
     * A carter's haul, one of the ways the market's carts went last hour (picked by how much went each way): grain
     * from a barn to the mill's hopper, flour from the mill to the bakery, the farms' yield to the stall that sells
     * it (handed over the counter) or the market hall, or grain from the hall out to the mill when the fields send
     * none. A cartload on their handcart where they can have one ([deliver]), the more the busier the carts were;
     * otherwise a sack on the shoulder. Null when the carts went nowhere (nothing to fetch), or the way there isn't
     * loaded.
     */
    fun haul(p: Townsperson, life: Life): Task? {
        val entry = market(p, life)
        val n = cartload(p, entry)
        return when (route(p, entry?.market?.carted)) {
            Route.GRAIN -> grain(p, life, n)
            Route.FLOUR -> flour(p, life, n)
            Route.MARKET -> produce(p, life, good(p, entry?.market?.carts), n)
            Route.GRANARY -> granary(p, life, n)
            null -> null
        }
    }

    private fun route(p: Townsperson, carted: DoubleArray?): Route? {
        val weights = carted ?: doubleArrayOf(4.0, 2.0, 4.0, 0.0)
        var pick = p.random.nextDouble() * weights.sum()
        if (pick <= 0.0) return null
        for (route in Route.entries) {
            pick -= weights[route.ordinal]
            if (pick < 0.0) return route
        }
        return null
    }

    private fun good(p: Townsperson, carts: DoubleArray?): Good {
        val total = carts?.sum() ?: 0.0
        if (carts != null && total > 0.0) {
            var pick = p.random.nextDouble() * total
            for (g in Good.entries) {
                pick -= carts[g.ordinal]
                if (pick < 0.0) return g
            }
        }
        return YIELD[r(p, YIELD.size)]
    }

    /** How many sacks or crates go on the cart this time: a full cart when the carts had all they could carry last hour, a couple when little went. */
    private fun cartload(p: Townsperson, entry: Markets.Entry?): Int {
        val busy = entry?.let { e -> (e.market.carted.sum() / (e.town.carters * Economy.CART)).coerceIn(0.0, 1.0) } ?: 0.5
        return (LOAD_LEAST + Math.round(busy * (LOAD_MOST - LOAD_LEAST)).toInt() + r(p, 3) - 1).coerceIn(LOAD_LEAST, LOAD_MOST)
    }

    /** The barrel on a mill's millstone, which the grain is tipped into: the stone, and where to stand at it. */
    private fun hopper(p: Townsperson, mill: CityPlaces.Lodging, from: BlockPos): Pair<BlockPos, Spot>? {
        val level = level(p)
        val stone = mill.stations.firstOrNull { it.m == M.GRINDSTONE && level.isLoaded(it.pos) }?.pos ?: return null
        return Places.beside(level, stone, from)?.let { stone to it }
    }

    /** Grain to the mill, tipped into the hopper: from [from] (the granary), or from a barn on the farms. */
    private fun grain(p: Townsperson, life: Life, n: Int, from: Pair<BlockPos, Spot>? = null): Task? {
        val level = level(p)
        val places = life.places
        val (barn, at) = from ?: store(p, places.nearest(p.blockPosition(), STORES), p.blockPosition()) ?: return null
        val mill = places.nearest(barn, MILLS).firstOrNull() ?: return null
        val (stone, by) = hopper(p, mill, mill.front?.inside ?: barn) ?: return null
        val to = Drop(by, Jobs.top(stone, 1.5), Activity.POUR_SACK, bay = { mill.front?.let { Handcarts.bay(level, it, p) } })
        return deliver(p, life, barn to at, to, ItemStack(Items.WHEAT), Handcarts.stack(Good.WHEAT), Activity.CARRY_SACK, n)
    }

    /** The granary's grain to the mill, when the fields send none: from the market hall's store. */
    private fun granary(p: Townsperson, life: Life, n: Int): Task? {
        val hall = store(p, life.places.nearest(p.blockPosition(), setOf(Kind.MARKET_HALL.name)).take(1), p.blockPosition()) ?: return null
        return grain(p, life, n, hall)
    }

    /** Flour from the mill's store to the bakery's bin (or by the oven, where it keeps none). */
    private fun flour(p: Townsperson, life: Life, n: Int): Task? {
        val level = level(p)
        val places = life.places
        val mills = places.nearest(p.blockPosition(), MILLS)
        val mill = mills.take(2).randomOrNull(Random(p.random.nextLong())) ?: return null
        val stone = mill.stations.firstOrNull { it.m == M.GRINDSTONE }?.pos
        val sacks = store(p, listOf(mill), mill.front?.inside ?: p.blockPosition(), setOfNotNull(stone?.above())) ?: return null
        val bakery = places.nearest(mill.box.center, BAKERIES).firstOrNull { b -> b !== mill && b.stations.any { it.m == M.SMOKER || it.m == M.FURNACE } }
            ?: return null
        val oven = bakery.stations.first { it.m == M.SMOKER || it.m == M.FURNACE }.pos
        val bin = store(p, listOf(bakery), oven)
            ?: (if (Places.loaded(level, oven, 1)) Places.beside(level, oven, bakery.front?.inside ?: oven)?.let { oven to it } else null)
            ?: return null
        val store = bin.first.takeIf { it != oven }
        val to = Drop(bin.second, Jobs.top(bin.first, 0.9), Activity.PUT_DOWN, store, bay = { bakery.front?.let { Handcarts.bay(level, it, p) } })
        return deliver(p, life, sacks, to, ItemStack.EMPTY, Handcarts.FLOUR, Activity.CARRY_SACK, n)
    }

    /**
     * Some of the farms' [good] (crates of roots, sacks of grain or fleece, the butcher's meat) to the stall that
     * sells it, handed over the counter to its keeper; or to the market hall's store if no stall does.
     */
    private fun produce(p: Townsperson, life: Life, good: Good, n: Int): Task? {
        val level = level(p)
        val places = life.places
        val here = p.blockPosition()
        val from = store(p, places.nearest(here, keeps(good)), here) ?: store(p, places.nearest(here, STORES), here) ?: return null
        val goods = raw(good)
        val overlay = if (good == Good.WHEAT || good == Good.WOOL) Activity.CARRY_SACK else Activity.CARRY
        val stall = places.stalls.filter { s ->
            Shop.of(s.trade)?.goods?.contains(good) == true && level.isLoaded(s.customer.pos) && Claims.free(level, s.customer.pos, p)
        }.minByOrNull { it.customer.pos.distSqr(from.first) }
        val to = if (stall != null) {
            val middle = stall.counter.getOrNull(stall.counter.size / 2) ?: stall.keeper.pos
            // The cart stands back from the counter, out of the buyers' way
            Drop(stall.customer, Jobs.top(middle, 1.0), Activity.HAND_OVER, bay = { Handcarts.bay(level, stall.customer.pos, stall.customer.facing.opposite, p) }) {
                Acts.serve(Acts.keeper(level, stall), p, Activity.RECEIVE, p.carried)
            }
        } else {
            val hall = places.nearest(from.first, setOf(Kind.MARKET_HALL.name)).take(1)
            val (store, at) = store(p, hall, from.first) ?: return null
            Drop(at, Jobs.top(store, 0.9), Activity.PUT_DOWN, store, bay = { hall.firstOrNull()?.front?.let { Handcarts.bay(level, it, p) } })
        }
        val piece = if (goods.isEmpty) Handcarts.stack(good) else goods
        return deliver(p, life, from, to, goods, piece, overlay, n)
    }

    /**
     * Where a load goes, a piece at a time: carried to [spot] and left there with [drop], looking at [look] (put
     * away in [store] if it's a barrel or a chest); [handed] hears each one leave their hands. A cart waits at
     * [bay] meanwhile (worked out when it's wanted; null if there's no room for one).
     */
    private class Drop(
        val spot: Spot, val look: Vec3, val drop: Activity, val store: BlockPos? = null, val bay: () -> BlockPos?, val handed: (() -> Unit)? = null,
    )

    /**
     * Goods from the store at [from] to [to]: a cartload of [n] ([Cartload]) where carts are on and there's room
     * for one by both doors, otherwise one piece carried on the shoulder. [held] is how a piece looks in their
     * hands, [piece] how it looks heaped on the cart, and [overlay] how they carry it.
     */
    private fun deliver(
        p: Townsperson, life: Life, from: Pair<BlockPos, Spot>, to: Drop, held: ItemStack, piece: ItemStack, overlay: Activity, n: Int,
    ): Task {
        val shoulder = { take(p, life, held, from.first, from.second).andThen(carry(p, life, overlay, to.spot, to.look, to.drop, to.store, to.handed)) }
        if (!Handcarts.enabled()) return shoulder()
        val door = life.places.lodgingAt(from.first)?.front ?: return shoulder()
        val start = Handcarts.bay(level(p), door, p) ?: return shoulder()
        val end = to.bay() ?: return shoulder()
        return Cartload(p, life, from, door, start, to, end, held, piece, overlay, n, shoulder)
    }

    /**
     * A cartload (see [deliver]): the carter's handcart fetched (or brought out) and pulled to [start] by [door],
     * [n] pieces taken out of the store at [from] one at a time and heaped on it, pulled across town to [to]'s
     * bay ([end] if there's none free by then), and lifted off and carried in to [to] one at a time. With no cart
     * to be had after all, a piece on the shoulder instead ([shoulder]). Broken off, they let go of the cart where
     * it stands.
     */
    private class Cartload(
        p: Townsperson, private val life: Life, private val from: Pair<BlockPos, Spot>, door: CityPlaces.Door, private val start: BlockPos,
        private val to: Drop, private val end: BlockPos, private val held: ItemStack, private val piece: ItemStack, private val overlay: Activity,
        private val n: Int, private val shoulder: () -> Task,
    ) : Task(p) {
        private enum class Stage { THERE, FETCH, OUT, LOAD, ACROSS, UNLOAD, BRING, DONE }

        private val door = Vec3.atBottomCenterOf(door.outside)
        private val heap = ArrayList<ItemStack>()
        private var stage = Stage.THERE
        private var inner: Task? = null
        private var pull: Pull? = null
        private var heaping: Heap? = null
        private var holding = false
        private var handed = false

        override val anchored: Boolean get() = inner?.anchored == true

        override fun start() {
            // Whatever was left on it last time goes along with this load
            Handcarts.parked(p)?.let { cart ->
                for (slot in 0 until Handcart.SLOTS) cart.goods(slot).takeIf { !it.isEmpty }?.let { heap.add(it.copy()) }
            }
            // Standing by the door already, with more to go on: in to the store for the next piece
            if (Pull.standing(p, start) && heap.size < n) {
                go(fetch())
                return
            }
            // Out to the cart first (down from upstairs and out of doors), even if it stands loaded and ready; with none
            // standing by, to where it's wanted, to bring one out
            val at = Handcarts.fetch(p) ?: start
            go(Ways.to(p, life.places, at, Jobs.WALK, near = 1.0, exact = at != start).andThen(Later(p) { pulling(start) }))
        }

        private fun go(task: Task?) {
            inner = task
            task?.start()
        }

        private fun pulling(to: BlockPos): Task = Pull(p, to, heap).also { pull = it }

        override fun run(): Boolean {
            val current = inner ?: return false
            if (current.tick()) return true
            current.stop()
            // The rest of a run (the way in, then the work there), unless the way failed
            val then = current.then
            go(then ?: next())
            return inner != null
        }

        /** The next stage's first task, now that the last one's over; null once it's all done. */
        private fun next(): Task? = when (stage) {
            // No cart for it after all: one on the shoulder
            Stage.THERE -> if (pull?.failed != false) over(shoulder()) else if (heap.size >= n) across() else fetch()
            // Nothing more to be had from the store: off with what's on, if anything
            Stage.FETCH -> if (holding) load() else if (heap.isEmpty()) over() else out()
            // Couldn't get back out to it: it stands where it is, loaded, for next time
            Stage.OUT -> over()
            Stage.LOAD -> {
                holding = false
                if (heaping?.done != true) over() else if (heap.size >= n) across() else fetch()
            }
            Stage.ACROSS -> if (pull?.failed != false) over() else unload()
            Stage.UNLOAD -> if (heaping?.done != true) over() else bring()
            // Couldn't get it in: the rest stays on the cart
            Stage.BRING -> if (!handed) over() else unload()
            Stage.DONE -> null
        }

        private fun over(last: Task? = null): Task? {
            stage = Stage.DONE
            return last
        }

        /** In to the store for the next piece. */
        private fun fetch(): Task {
            stage = Stage.FETCH
            return take(p, life, held, from.first, from.second) { holding = true }
        }

        /** Out to the cart with it, and onto the heap. */
        private fun load(): Task {
            stage = Stage.LOAD
            return atCart(Heap(p, heap, piece, from = door, overlay = overlay), door, overlay)
        }

        /** Back out to the cart empty-handed (down from upstairs and out of doors), and off across town with what's on it. */
        private fun out(): Task {
            stage = Stage.OUT
            val at = Handcarts.fetch(p) ?: start
            return Ways.to(p, life.places, at, Jobs.WALK, near = 1.0, exact = at != start).andThen(Later(p) { across() })
        }

        /** Loaded: the shafts taken up and off across town. */
        private fun across(): Task {
            stage = Stage.ACROSS
            return pulling(to.bay() ?: end)
        }

        /** The next piece lifted off, or with none left, done. */
        private fun unload(): Task? {
            if (heap.isEmpty()) return over()
            stage = Stage.UNLOAD
            val toward = Vec3.atBottomCenterOf(to.spot.pos)
            return atCart(Heap(p, heap, null, held, from = toward), toward, Activity.NONE)
        }

        /** Out to the cart's side (down the ladder first if they're upstairs), on the side nearer [from], for [heap] there. */
        private fun atCart(heap: Heap, from: Vec3, overlay: Activity): Task {
            heaping = heap
            val side = Handcarts.side(p, from) ?: return heap
            return Ways.to(p, life.places, side, LADEN, near = 1.0, overlay = overlay).andThen(heap)
        }

        /** The piece in their hands carried in and left. */
        private fun bring(): Task {
            stage = Stage.BRING
            handed = false
            return carry(p, life, overlay, to.spot, to.look, to.drop, to.store) {
                handed = true
                to.handed?.invoke()
            }
        }

        override fun stop() {
            inner?.stop()
            inner = null
        }
    }

    /** Sends carter [p] off on a haul now (for the commands): true if with their cart, null if they're no carter, or there's nothing to haul. */
    fun haulNow(p: Townsperson): Boolean? {
        val life = Days.life(p) ?: return null
        if (!life.carter) return null
        val task = haul(p, life) ?: return null
        p.routine.replace(task)
        return task is Cartload
    }

    /** Nothing to haul just now: a word with someone near, or a while by a barn door waiting on the next load. */
    fun wait(p: Townsperson, life: Life): Task {
        Social.chat(p)?.let { return it }
        val idle = Idle(p, 300 + r(p, 300))
        val barn = life.places.nearest(p.blockPosition(), STORES).firstOrNull { it.front != null } ?: return idle
        return Ways.to(p, life.places, barn.front!!.outside, Jobs.WALK, near = 2.0).andThen(idle)
    }

    // --- The miller, the farmer's basket, the fisher's catch ---

    /**
     * The miller at the stones while the mill grinds, as its sails or its wheel show it ([Mills.turning]: the
     * market's mill turning out flour this hour, and not braked for a storm): watching the grind and tending it, and
     * now and then a sack fetched from the store and tipped into the hopper first. Null while the mill stands, when
     * they're out in the fields like any farmer.
     */
    private fun milling(p: Townsperson, life: Life): Task? {
        val mill = life.mill ?: return null
        val level = level(p)
        val (stone, spot) = hopper(p, mill, mill.front?.inside ?: p.blockPosition()) ?: return null
        // With neither a market nor a turning mill to go by, the stones turn by day
        val grinding = if (market(p, life) == null && (!Mills.enabled() || Mills.near(level, stone) == null)) Economy.milling(clock(level)) else Mills.turning(level, stone)
        if (!grinding) return null
        val turns = 4 + r(p, 4)
        val grind = Work(p, spot, Jobs.top(stone, 0.8), listOf(Step(Activity.GRIND, Activity.GRIND.ticks * turns), Step(Activity.NONE, 20 + r(p, 30))), stone, onTick = { s, t ->
            if (s.activity == Activity.GRIND && t % Activity.GRIND.ticks == 10) {
                level.playSound(null, stone, SoundEvents.GRINDSTONE_USE, SoundSource.NEUTRAL, 0.3f, 0.55f + p.random.nextFloat() * 0.1f)
            }
        })
        if (r(p, 3) == 0) {
            val sacks = store(p, listOf(mill), stone, setOf(stone.above()))
            if (sacks != null) {
                return take(p, life, ItemStack(Items.WHEAT), sacks.first, sacks.second)
                    .andThen(carry(p, life, Activity.CARRY_SACK, spot, Jobs.top(stone, 1.5), Activity.POUR_SACK))
                    .andThen(grind)
            }
        }
        return Acts.via(p, life, spot.pos, grind)
    }

    /** A full basket taken in from the field to the farm's store: the barn or the farmhouse nearest. */
    private fun basket(p: Townsperson, life: Life): Task? {
        if (life.basket < BASKET) return null
        life.basket = 0
        val from = p.work ?: p.blockPosition()
        val (store, at) = store(p, life.places.nearest(from, STORES), from) ?: return null
        val goods = ItemStack(life.gathered ?: Items.WHEAT)
        return Acts.hold(p, goods).andThen(carry(p, life, Activity.CARRY_BASKET, at, Jobs.top(store, 0.9), Activity.PUT_DOWN, store))
    }

    /** A basket of the catch carried up to the fishmonger: a stall on the quay, or a stall that sells fish, handed over its counter. */
    private fun landing(p: Townsperson, life: Life): Task? {
        if (life.basket < BASKET) return null
        life.basket = 0
        val level = level(p)
        val here = p.blockPosition()
        val counters = life.places.counters.map { it to it.pos.relative(it.facing, 2) } +
            life.places.stalls.filter { Shop.of(it.trade)?.goods?.contains(Good.COD) == true }.map { it.keeper to it.customer.pos }
        val (keeper, stand) = counters.filter { level.isLoaded(it.second) && it.second.distSqr(here) < FAR * FAR && Places.standable(level, it.second) }
            .minByOrNull { it.second.distSqr(here) } ?: return null
        val goods = ItemStack(life.gathered ?: Items.COD)
        val spot = Spot(stand, keeper.facing.opposite)
        return Acts.hold(p, goods).andThen(carry(p, life, Activity.CARRY_BASKET, spot, Jobs.top(keeper.pos.relative(keeper.facing), 1.0), Activity.HAND_OVER) {
            val them = level.getEntitiesOfClass(Townsperson::class.java, AABB(keeper.pos).inflate(1.2, 1.0, 1.2)) { it.isAlive && !it.isSleeping }.firstOrNull()
            Acts.serve(them, p, Activity.RECEIVE, p.carried)
        })
    }

    /** How far a fisher goes with a basket at most. */
    private const val FAR = 96.0

    // --- The bakery ---

    /**
     * The baker's part: at a loss while the ovens stand cold for want of flour (a look in the empty bin, a shrug,
     * then a while at the door watching for the cart); otherwise now and then the loaves just out of the oven
     * carried out to the counter. Null for the oven and the kneading as usual.
     */
    private fun bakery(p: Townsperson, life: Life): Task? {
        val level = level(p)
        val oven = p.work?.takeIf { level.isLoaded(it) && Jobs.oven(level.getBlockState(it)) } ?: return null
        val bakery = life.places.lodgingAt(oven)?.takeIf { it.contains(oven) } ?: return null
        val door = bakery.front
        val m = market(p, life)?.market
        val baking = Economy.baking(clock(level), Economy.ahead(Days.dayOf(level.overworldClockTime)))
        if (m != null && baking && !m.busy(Maker.OVENS) && m[Site.BAKEHOUSE, Good.FLOUR] < Economy.BAKE_FLOUR * 4) {
            val bin = store(p, listOf(bakery), oven)
            val looking = bin?.let { (pos, spot) ->
                Acts.via(p, life, spot.pos, Work(p, spot, Jobs.top(pos, 0.9), listOf(Step(Activity.SEARCH, 80), Step(Activity.SHRUG), Step(Activity.NONE, 20)), pos, onTick = { s, t ->
                    if (s.activity == Activity.SEARCH && t == 1) open(level, p, pos)
                    if (s.activity == Activity.SHRUG && t == 0) close(level, p, pos)
                }))
            } ?: Acts.gesture(p, Activity.SHRUG)
            val watch = if (door != null) Ways.to(p, life.places, door.inside, Jobs.WALK, near = 1.0).andThen(Idle(p, 300 + r(p, 300)))
            else Idle(p, 300 + r(p, 300))
            return looking.andThen(watch)
        }
        if (r(p, 3) != 0) return null
        val (top, behind) = counter(level, bakery) ?: return null
        val ovenSpot = Places.beside(level, oven, p.blockPosition()) ?: return null
        val out = Work(p, ovenSpot, Jobs.top(oven, 0.5), listOf(Step(Activity.OVEN), Step(Activity.PICK_UP)), oven, onTick = { s, t ->
            if (s.activity == Activity.PICK_UP && t == 8) p.carried = ItemStack(Items.BREAD)
        })
        return Acts.via(p, life, ovenSpot.pos, out).andThen(carry(p, life, Activity.CARRY_BASKET, behind, Jobs.top(top, 1.0), Activity.PUT_DOWN))
    }

    /**
     * The bakery's counter: something standing a few steps in from the front door with room to stand either side,
     * the customer's toward the door; the block, and where the baker stands behind it facing the shop.
     */
    private fun counter(level: ServerLevel, bakery: CityPlaces.Lodging): Pair<BlockPos, Spot>? {
        val door = bakery.front ?: return null
        for (k in 2..4) {
            val c = door.inside.relative(door.inward, k)
            if (!level.isLoaded(c)) return null
            if (Places.free(level, c, feet = true)) continue
            val front = c.relative(door.inward.opposite)
            val back = c.relative(door.inward)
            if (Places.standable(level, front) && Places.standable(level, back)) return c to Spot(back, door.inward.opposite)
            return null
        }
        return null
    }

    // --- Shopping ---

    /** What a stall of [trade] has to sell now: its shop's goods the market has any of (all of them with no market to go by). */
    fun wares(m: Markets.Entry?, trade: M): List<Item> {
        val shop = Shop.of(trade) ?: return listOf(Items.BREAD)
        return shop.goods.filter { m == null || m.market.stock(it) >= 1.0 }.mapNotNull { it.item }
    }

    /**
     * A loaf from the bakery: in at its door, a look at the shelf, and a coin for the bread from the baker if they're
     * in (from the shelf if not); or, with the shelves bare, a look and a shrug, and a shrug back from the baker.
     */
    fun loaf(p: Townsperson, life: Life): Task? {
        val level = level(p)
        val bakery = life.places.nearest(life.home?.box?.center ?: p.blockPosition(), setOf(Kind.BAKERY.name)).firstOrNull { it.front != null } ?: return null
        val door = bakery.front!!
        // At the counter if there's one to stand at, else a step in from the door
        val counter = counter(level, bakery)?.first
        val step = counter?.relative(door.inward.opposite) ?: door.inside.relative(door.inward)
        val stand = if (level.isLoaded(step) && Places.standable(level, step)) step else door.inside
        if (!level.isLoaded(stand) || !Claims.free(level, stand, p)) return null
        val shelf = Jobs.top(counter ?: stand.relative(door.inward, 2), 1.0)
        val bread = market(p, life)?.let { it.market.stock(Good.BREAD) >= 1.0 } ?: true
        val steps = if (bread) listOf(Step(Activity.BROWSE, 40), Step(Activity.POINT), Step(Activity.PAY), Step(Activity.RECEIVE), Step(Activity.NOD))
        else listOf(Step(Activity.BROWSE, 50), Step(Activity.SHRUG), Step(Activity.NONE, 10))
        var baker: Townsperson? = null
        val buying = Work(p, Spot(stand, door.inward), shelf, steps, onTick = { s, t ->
            if (t == 0) when (s.activity) {
                Activity.POINT, Activity.SHRUG -> {
                    baker = level.getEntitiesOfClass(Townsperson::class.java, AABB.of(bakery.box)) { it.role == TownRole.BAKER && it.isAlive && !it.isSleeping }.firstOrNull()
                    Acts.serve(baker, p, if (s.activity == Activity.SHRUG) Activity.SHRUG else Activity.NOD)
                }
                // The coin across the counter, then the loaf back the other way
                Activity.PAY -> Acts.serve(baker, p, Activity.RECEIVE, ItemStack(ModItems.GUILD_MARK.get()))
                Activity.RECEIVE -> Acts.serve(baker, p, Activity.HAND_OVER, ItemStack(Items.BREAD))
                else -> {}
            }
            if (s.activity == Activity.RECEIVE && t == Acts.PASS) p.carried = ItemStack(Items.BREAD)
        })
        return Ways.to(p, life.places, stand, Jobs.WALK, near = 1.0).andThen(buying)
    }
}

/**
 * Who in a capital does the carrying and the milling its market counts on: as many carters as it has carts (see
 * [io.github.vaspyyy.guildmark.economy.Town.carters]), from its citizens, those living out at the farms first and
 * then those living nearest them; for each mill, the farmer living nearest it as its miller; and of the citizens
 * left, the one living nearest the market board as the town crier (see [Crier]). Worked out from the living
 * residents now and then, so someone new takes over when a carter, a miller or the crier dies.
 */
object Hands {
    /**
     * A capital's carters (resident ids), millers (resident id to their mill's lodging id) and crier (a resident id,
     * or null if there's no one to be it), as worked out at game time [at].
     */
    class Crew(val carters: Set<Int>, val millers: Map<Int, Int>, val crier: Int?, val at: Long)

    /** How often (in game ticks) a capital's crew is worked out again. */
    private const val AGAIN = 2400L

    private val crews = HashMap<BlockPos, Crew>()

    fun of(level: ServerLevel, places: CityPlaces): Crew {
        val center = places.capital.center
        crews[center]?.let { if (level.gameTime - it.at in 0 until AGAIN) return it }
        return work(level, places).also { crews[center] = it }
    }

    fun forget() = crews.clear()

    private fun work(level: ServerLevel, places: CityPlaces): Crew {
        val center = places.capital.center
        val living = Residents.get(level).of(center).filter { it.alive }
        val farms = places.lodgings.values.filter { it.farm && it.kind in Trades.MILLS + setOf(FarmKind.BARN.name, FarmKind.FARMHOUSE.name) }
        fun home(r: Resident): BlockPos? = places.lodgings[r.home]?.box?.center
        fun out(r: Resident): Double = home(r)?.let { h -> farms.minOfOrNull { it.box.center.distSqr(h) } } ?: Double.MAX_VALUE
        val carts = Markets.get(level)[center]?.town?.carters ?: maxOf(1, Math.round(living.size / 50.0).toInt())
        val carters = living.filter { it.role == TownRole.CITIZEN }
            .sortedWith(compareBy<Resident>({ if (places.lodgings[it.home]?.farm == true) 0 else 1 }, { out(it) }, { it.id }))
            .take(carts).map { it.id }.toSet()
        val board = places.marketBoard.pos
        val crier = living.filter { it.role == TownRole.CITIZEN && it.id !in carters }
            .minWithOrNull(compareBy<Resident>({ home(it)?.distSqr(board) ?: Double.MAX_VALUE }, { it.id }))?.id
        val farmers = living.filter { it.role == TownRole.FARMER }.toMutableList()
        val millers = HashMap<Int, Int>()
        for (mill in places.lodgings.values.filter { it.kind in Trades.MILLS }.sortedBy { it.id }) {
            val miller = farmers.minWithOrNull(compareBy<Resident>({ home(it)?.distSqr(mill.box.center) ?: Double.MAX_VALUE }, { it.id })) ?: break
            farmers.remove(miller)
            millers[miller.id] = mill.id
        }
        return Crew(carters, millers, crier, level.gameTime)
    }
}
