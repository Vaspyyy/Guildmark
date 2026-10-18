package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.city.plan.M
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.phys.AABB
import kotlin.math.abs

/**
 * What a capital's people do in each stretch of their day ([Days]): the bed upstairs and the walk down the ladder
 * in the morning, a wash at the basin or the well, meals at the table at home, the trade at its bench or stall,
 * a stall's wares looked over and paid for and the basket carried home, water drawn and poured into the basin,
 * a parcel taken to a neighbour's door, a visit, a prayer on the temple's benches, an evening at the tavern or by
 * the fire. Every place is a real one from [CityPlaces]; nothing is a random spot in the street.
 */
object Acts {
    private fun level(p: Townsperson): ServerLevel = p.level() as ServerLevel
    private fun r(p: Townsperson, n: Int): Int = p.random.nextInt(n)

    /** One part of a stretch: [make] gives its task when its turn comes (null: nothing to do); only [laden] parts start with goods in hand. */
    class Part(val laden: Boolean = false, val make: () -> Task?)

    /** The parts of a stretch of [doing], in order. */
    fun script(p: Townsperson, life: Life, doing: Doing): List<Part> = when (doing) {
        Doing.SLEEP -> listOf(Part { sleep(p, life) })
        Doing.RISE -> listOf(Part { rise(p) }, Part { wash(p, life) })
        Doing.BREAKFAST, Doing.SUPPER -> listOf(Part { goHome(p, life) }, Part { meal(p, life, life.home, 500 + r(p, 500)) })
        Doing.LUNCH -> listOf(Part { lunch(p, life) })
        Doing.WORK -> listOf(Part { setUp(p, life) })
        Doing.WATCH, Doing.PLAY, Doing.CART, Doing.CRY -> listOf()
        Doing.MARKET -> listOf(Part { shop(p, life) }, Part(laden = true) { putAway(p, life) })
        Doing.WATER -> listOf(Part { drawWater(p, life) }, Part(laden = true) { putAway(p, life) })
        Doing.ERRAND -> listOf(Part { errand(p, life) })
        Doing.VISIT -> listOf(Part { visit(p, life) })
        Doing.WORSHIP -> listOf(Part { worship(p, life) })
        Doing.STROLL -> listOf(Part { stroll(p, life) }, Part { stroll(p, life) })
        Doing.LESSONS -> listOf(Part { lessons(p, life) })
        Doing.TAVERN -> listOf(Part { tavern(p, life) })
        Doing.EVENING -> listOf(Part { goHome(p, life) }, Part { evening(p, life) })
        Doing.BED -> listOf(Part { goHome(p, life) }, Part { gesture(p, Activity.YAWN) }, Part { sleep(p, life) })
    }

    /** What fills the rest of a stretch once its parts are done. */
    fun filler(p: Townsperson, life: Life, doing: Doing): Task = when (doing) {
        Doing.WORK -> Trades.work(p, life) ?: Jobs.work(p)?.let { onFloor(p, life, it) } ?: chores(p, life)
        Doing.CART -> Trades.haul(p, life) ?: Trades.wait(p, life)
        Doing.CRY -> Crier.round(p, life) ?: chores(p, life)
        Doing.WATCH -> Jobs.guard(p)
        Doing.PLAY -> Leisure.child(p)
        Doing.TAVERN -> tavern(p, life) ?: atHome(p, life)
        Doing.STROLL -> stroll(p, life) ?: atHome(p, life)
        Doing.LESSONS -> lessons(p, life) ?: Leisure.child(p)
        Doing.SLEEP, Doing.BED -> sleep(p, life)
        Doing.WORSHIP -> worship(p, life) ?: atHome(p, life)
        else -> atHome(p, life)
    }

    // --- Getting about ---

    /** Where home is: just inside its front door. */
    private fun homeSpot(p: Townsperson, life: Life): BlockPos = life.home?.front?.inside ?: Jobs.home(p)

    private fun atHomeNow(p: Townsperson, life: Life): Boolean = life.home?.contains(p.blockPosition()) == true

    /**
     * [task], with the way there by ladder first when [target] is on another floor than they are ([Work], [Sit]
     * and [Sleep] walk the last of the way themselves).
     */
    fun via(p: Townsperson, life: Life, target: BlockPos, task: Task): Task {
        val here = p.blockPosition()
        val from = life.places.lodgingAt(here)?.takeIf { it.contains(here) }
        val into = life.places.lodgingAt(target)?.takeIf { it.contains(target) }
        val sameFloor = into === from && abs(target.y - here.y) <= 1
        val climb = !sameFloor && (from != null && Ways.upstairs(from, here.y) || into != null && Ways.upstairs(into, target.y))
        return if (climb) Ways.to(p, life.places, target, near = 1.2).andThen(task) else task
    }

    private fun onFloor(p: Townsperson, life: Life, task: Task): Task = if (task is Work) via(p, life, task.target, task) else task

    private fun goHome(p: Townsperson, life: Life): Task? {
        if (atHomeNow(p, life)) return null
        val overlay = if (Schedule.dark(p)) Activity.LANTERN else Activity.NONE
        return Ways.to(p, life.places, homeSpot(p, life), Jobs.WALK, near = 1.5, overlay = overlay)
    }

    /** A move made on the spot: a yawn, a shrug. */
    fun gesture(p: Townsperson, what: Activity): Task = Idle(p, what.ticks, what, chatty = false)

    /** Something done to the world (or to what's in their hands) at once, between two tasks. */
    private class Now(p: Townsperson, private val what: () -> Unit) : Task(p) {
        override val instant: Boolean get() = true
        override fun start() = what()
        override fun run(): Boolean = false
    }

    /** [item] in their hands from now on (empty to put down what they held). */
    fun hold(p: Townsperson, item: ItemStack): Task = Now(p) { p.carried = item }

    // --- Seats ---

    /** A free seat in [lodging], one at a table if [table] and there is one; the nearest to them of a few. */
    private fun seatIn(level: ServerLevel, lodging: CityPlaces.Lodging, p: Townsperson, table: Boolean): Spot? {
        val free = lodging.seats.filter {
            level.isLoaded(it.pos) && Places.isSeat(level, level.getBlockState(it.pos), it.pos) && Claims.free(level, it.pos, p)
        }
        if (free.isEmpty()) return null
        val atTable = if (table) free.filter { seat -> lodging.tables.any { it.distManhattan(seat.pos.relative(seat.facing)) <= 1 } } else listOf()
        val pick = atTable.ifEmpty { free }.sortedBy { it.pos.distSqr(p.blockPosition()) }.take(3)
        return pick[r(p, pick.size)]
    }

    private fun sitAt(p: Townsperson, life: Life, seat: Spot, ticks: Int, moods: List<Activity>): Task {
        val level = level(p)
        val access = Places.access(level, seat.pos, level.getBlockState(seat.pos)) ?: seat.pos
        return via(p, life, access, Sit(p, seat.pos, ticks, moods))
    }

    /** At home: a sit at the table, or about the house. */
    private fun atHome(p: Townsperson, life: Life): Task {
        val home = life.home
        if (home == null || !atHomeNow(p, life)) return goHome(p, life) ?: Idle(p, 200 + r(p, 200))
        val seat = seatIn(level(p), home, p, table = r(p, 2) == 0)
        if (seat != null && r(p, 3) != 0) return sitAt(p, life, seat, 400 + r(p, 600), listOf(Activity.SIT, Activity.SIT, Activity.SIT_TALK))
        return Idle(p, 200 + r(p, 300))
    }

    // --- Night and morning ---

    /** Their own bed if it stands and is free, else a bed at home nobody else has. */
    private fun bed(p: Townsperson, life: Life): BlockPos? {
        val level = level(p)
        fun ok(pos: BlockPos) = level.isLoaded(pos) && level.getBlockState(pos).`is`(BlockTags.BEDS) && Claims.free(level, pos, p)
        life.resident.bed?.let { if (ok(it)) return it }
        val home = life.home ?: return null
        val others = Residents.get(level).of(life.resident.capital).filter { it.home == home.id && it.id != life.resident.id && it.alive }.mapNotNull { it.bed }.toSet()
        return home.beds.firstOrNull { it !in others && ok(it) }
    }

    private fun sleep(p: Townsperson, life: Life): Task {
        val night = { life.stretch?.doing.let { it == Doing.SLEEP || it == Doing.BED } }
        if (p.isSleeping) return Sleep(p, p.sleepingPos.orElse(p.blockPosition()), night)
        val bed = bed(p, life)
        if (bed != null) return Ways.to(p, life.places, bed, Jobs.WALK, near = 1.6).andThen(Sleep(p, bed, night))
        // Nowhere to lie down: asleep in a chair at home
        val seat = life.home?.let { seatIn(level(p), it, p, table = false) }
        if (seat != null && atHomeNow(p, life)) return sitAt(p, life, seat, 2400, listOf(Activity.SIT))
        return goHome(p, life) ?: Idle(p, 600, chatty = false)
    }

    private fun rise(p: Townsperson): Task = gesture(p, Activity.YAWN).andThen(gesture(p, Activity.STRETCH))

    private fun basin(level: ServerLevel, lodging: CityPlaces.Lodging?): BlockPos? =
        lodging?.stations?.firstOrNull { it.m == M.CAULDRON && level.isLoaded(it.pos) && level.getBlockState(it.pos).block is AbstractCauldronBlock }?.pos

    /** A wash at the basin at home, or now and then at the well. */
    private fun wash(p: Townsperson, life: Life): Task? {
        val level = level(p)
        val basin = basin(level, life.home)
        if (basin != null) {
            val spot = Places.beside(level, basin, p.blockPosition()) ?: return null
            return via(p, life, spot.pos, Work(p, spot, Jobs.top(basin, 0.9), listOf(Step(Activity.WASH), Step(Activity.NONE, 20)), basin))
        }
        if (r(p, 3) != 0) return null
        return atWell(p, life, listOf(Step(Activity.DRAW_WATER), Step(Activity.WASH), Step(Activity.NONE, 20)), bucket = false)
    }

    // --- Meals ---

    private val FOOD: List<Item> = listOf(Items.BREAD, Items.BREAD, Items.BAKED_POTATO, Items.APPLE, Items.COOKED_COD, Items.CARROT, Items.COOKIE, Items.PUMPKIN_PIE)

    private fun food(p: Townsperson): ItemStack = ItemStack(FOOD[r(p, FOOD.size)])

    /** A meal at the table in [at]: sitting to eat if there's a seat, standing at the table if not. */
    private fun meal(p: Townsperson, life: Life, at: CityPlaces.Lodging?, ticks: Int): Task? {
        val lodging = at ?: return null
        val level = level(p)
        val dish = food(p)
        val seat = seatIn(level, lodging, p, table = true)
        if (seat != null) {
            return sitAt(p, life, seat, ticks, listOf(Activity.SIT_EAT, Activity.SIT_EAT, Activity.SIT_EAT, Activity.SIT_TALK))
                .let { sit -> hold(p, dish).andThen(sit).andThen(hold(p, ItemStack.EMPTY)) }
        }
        val table = lodging.tables.minByOrNull { it.distSqr(p.blockPosition()) }
        val spot = table?.let { Places.beside(level, it, p.blockPosition()) }
        if (table != null && spot != null) {
            return hold(p, dish).andThen(via(p, life, spot.pos, Work(p, spot, Jobs.top(table, 1.0), listOf(Step(Activity.EAT, ticks)))))
                .andThen(hold(p, ItemStack.EMPTY))
        }
        return hold(p, dish).andThen(Idle(p, ticks, Activity.EAT, chatty = false)).andThen(hold(p, ItemStack.EMPTY))
    }

    /** Lunch: at home if it's near, else out of the hand on a bench near work. */
    private fun lunch(p: Townsperson, life: Life): Task? {
        val home = life.home
        val here = p.blockPosition()
        val far = home == null || home.box.center.distSqr(here) > 40.0 * 40.0
        val eatsAtWork = p.role == TownRole.MERCHANT || p.role == TownRole.INNKEEPER || p.role == TownRole.GUARD || p.role == TownRole.FARMER
        if (!far && !eatsAtWork) return meal(p, life, home, 400 + r(p, 300))
        val level = level(p)
        val dish = food(p)
        val bench = life.places.benches.filter { it.pos.distSqr(here) < 20.0 * 20.0 && Claims.free(level, it.pos, p) && Places.isSeat(level, level.getBlockState(it.pos), it.pos) }
            .minByOrNull { it.pos.distSqr(here) }
        val eat = if (bench != null) sitAt(p, life, bench, 400 + r(p, 300), listOf(Activity.SIT_EAT, Activity.SIT_EAT, Activity.SIT))
        else Idle(p, 300 + r(p, 200), Activity.EAT, chatty = false)
        return hold(p, dish).andThen(eat).andThen(hold(p, ItemStack.EMPTY))
    }

    // --- Work ---

    /** The first thing at work: a merchant sets out the stall. */
    private fun setUp(p: Townsperson, life: Life): Task? {
        if (p.role != TownRole.MERCHANT) return null
        val post = p.post ?: return null
        val facing = p.postFacing ?: Direction.NORTH
        val counter = Jobs.top(post.relative(facing), 1.0)
        return Work(p, Spot(post, facing), counter, listOf(Step(Activity.SET_UP, 60 * (2 + r(p, 2))), Step(Activity.ARRANGE, 60)))
    }

    /** Work about the house for those with no trade of their own. */
    private fun chores(p: Townsperson, life: Life): Task {
        val level = level(p)
        val home = life.home
        when (r(p, 4)) {
            0 -> Leisure.sweep(p)?.let { return it }
            1 -> home?.stores?.filter { level.isLoaded(it) }?.randomOrNull()?.let { store ->
                Places.beside(level, store, p.blockPosition())?.let { spot ->
                    return via(p, life, spot.pos, Work(p, spot, Jobs.top(store, 0.9), listOf(Step(Activity.ARRANGE, 60 * (2 + r(p, 3)))), store))
                }
            }
            2 -> home?.stations?.firstOrNull { it.m == M.FURNACE && level.isLoaded(it.pos) }?.let { fire ->
                Places.beside(level, fire.pos, p.blockPosition())?.let { spot ->
                    return via(p, life, spot.pos, Work(p, spot, Jobs.top(fire.pos, 0.5), listOf(Step(Activity.TEND, 40 * (3 + r(p, 3)))), fire.pos))
                }
            }
        }
        return errand(p, life) ?: atHome(p, life)
    }

    // --- The market, the well, errands ---

    /** Whoever keeps [stall] just now, townsperson or villager. */
    fun keeper(level: ServerLevel, stall: CityPlaces.Stall): Entity? {
        val box = AABB(stall.keeper.pos).inflate(1.2, 1.0, 1.2)
        return level.getEntitiesOfClass(Townsperson::class.java, box) { it.isAlive && !it.isSleeping }.firstOrNull()
            ?: level.getEntitiesOfClass(Villager::class.java, box) { it.isAlive && !it.isSleeping }.firstOrNull()
    }

    /** The keeper's side of a sale (or of a delivery, or of having none to sell): a look up, the goods handed over or taken, a word. */
    fun serve(keeper: Entity?, customer: Townsperson, what: Activity) {
        when (keeper) {
            is Townsperson -> {
                keeper.lookAt(customer)
                keeper.act(what)
                if (what == Activity.HAND_OVER && keeper.random.nextInt(3) == 0) keeper.say("merchant_${keeper.random.nextInt(6)}")
            }
            is Villager -> {
                keeper.lookControl.setLookAt(customer)
                if (what == Activity.HAND_OVER) keeper.playSound(SoundEvents.VILLAGER_YES, 0.6f, 1f)
                if (what == Activity.SHRUG) keeper.playSound(SoundEvents.VILLAGER_NO, 0.6f, 1f)
            }
        }
    }

    /**
     * To the market: a stall's wares looked over, pointed at, paid for and taken; now and then a loaf from the
     * bakery instead. Mostly it's a stall with something left to sell (the market's own stock: [Trades.wares]);
     * one that's sold out gets a look over its bare counter and a shrug, and they try another.
     */
    fun shop(p: Townsperson, life: Life): Task? {
        val level = level(p)
        if (level.isRaining && r(p, 3) != 0) return null
        if (r(p, 4) == 0) Trades.loaf(p, life)?.let { return it }
        return stall(p, life, null)
    }

    /** A stall's wares bought at one of the stalls but [not]; or, at one sold out, a shrug and on to the next (only the once). */
    private fun stall(p: Townsperson, life: Life, not: CityPlaces.Stall?): Task? {
        val level = level(p)
        val entry = Trades.market(p, life)
        val stalls = life.places.stalls.filter { it !== not && level.isLoaded(it.customer.pos) && Claims.free(level, it.customer.pos, p) }
        if (stalls.isEmpty()) return null
        val stocked = stalls.filter { Trades.wares(entry, it.trade).isNotEmpty() }
        // Mostly one with something left; now and then they find out the hard way, but not twice running
        val pool = if (stocked.isNotEmpty() && (not != null || r(p, 5) != 0)) stocked else stalls
        val pick = pool[r(p, pool.size)]
        val wares = Trades.wares(entry, pick.trade)
        val middle = pick.counter.getOrNull(pick.counter.size / 2) ?: pick.keeper.pos
        if (wares.isEmpty()) {
            if (not != null) return null
            val looking = Work(p, pick.customer, Jobs.top(middle, 1.0), listOf(Step(Activity.BROWSE, 60 + r(p, 40)), Step(Activity.SHRUG), Step(Activity.NONE, 10)), onTick = { step, t ->
                if (step.activity == Activity.SHRUG && t == 0) serve(keeper(level, pick), p, Activity.SHRUG)
            })
            return via(p, life, pick.customer.pos, looking).andThen(Later(p) { stall(p, life, pick) })
        }
        val goods = ItemStack(wares[r(p, wares.size)])
        val steps = listOf(
            Step(Activity.BROWSE, 60 * (2 + r(p, 2))), Step(Activity.POINT), Step(Activity.PAY), Step(Activity.RECEIVE), Step(Activity.NOD),
        )
        var keeper: Entity? = null
        val buying = Work(p, pick.customer, Jobs.top(middle, 1.0), steps, onTick = { step, t ->
            if (t == 0) when (step.activity) {
                Activity.POINT -> {
                    keeper = keeper(level, pick)
                    serve(keeper, p, Activity.NOD)
                }
                Activity.PAY -> serve(keeper, p, Activity.HAND_OVER)
                else -> {}
            }
            if (step.activity == Activity.RECEIVE && t == 12) p.carried = goods
        })
        return via(p, life, pick.customer.pos, buying)
    }

    /** Where to stand at the well at [well]: by its curb, facing the water. */
    private fun wellSpot(level: ServerLevel, well: BlockPos, from: BlockPos, p: Townsperson): Spot? {
        var best: Spot? = null
        var bestDist = Double.MAX_VALUE
        for (reach in 2 downTo 1) for (dir in Direction.Plane.HORIZONTAL) for (dy in intArrayOf(0, -1, 1)) {
            val pos = well.relative(dir, reach).above(dy)
            if (!Places.standable(level, pos) || !Claims.free(level, pos, p)) continue
            val d = pos.distSqr(from)
            if (d < bestDist) {
                bestDist = d
                best = Spot(pos, dir.opposite)
            }
        }
        return best
    }

    /** At the nearest well they can get to, going through [steps]; with a full bucket at the end if [bucket]. */
    private fun atWell(p: Townsperson, life: Life, steps: List<Step>, bucket: Boolean): Task? {
        val level = level(p)
        val here = p.blockPosition()
        for (well in life.places.wells.filter { level.isLoaded(it) }.sortedBy { it.distSqr(here) }.take(3)) {
            val spot = wellSpot(level, well, here, p) ?: continue
            val drawing = Work(p, spot, Jobs.top(well, 0.3), steps, onTick = { step, t ->
                if (step.activity == Activity.DRAW_WATER) {
                    if (t == 40) level.playSound(null, well, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 0.6f, 1f)
                    if (bucket && t == 60) p.carried = ItemStack(Items.WATER_BUCKET)
                }
            })
            return via(p, life, spot.pos, drawing)
        }
        return null
    }

    private fun drawWater(p: Townsperson, life: Life): Task? = atWell(p, life, listOf(Step(Activity.DRAW_WATER), Step(Activity.NONE, 10)), bucket = true)

    /** What's in their hands, taken home: water into the basin, anything else put away in the larder. */
    private fun putAway(p: Townsperson, life: Life): Task? {
        val goods = p.carried
        if (goods.isEmpty) return null
        val level = level(p)
        val home = life.home ?: return hold(p, ItemStack.EMPTY)
        val water = goods.`is`(Items.WATER_BUCKET)
        val basin = if (water) basin(level, home) else null
        val target = basin ?: home.stores.filter { level.isLoaded(it) }.minByOrNull { it.distSqr(homeSpot(p, life)) }
        val overlay = if (water) Activity.NONE else Activity.CARRY_BASKET
        val laden = 0.42
        if (target == null) {
            return Ways.to(p, life.places, homeSpot(p, life), laden, overlay = overlay)
                .andThen(gesture(p, Activity.PUT_DOWN)).andThen(hold(p, ItemStack.EMPTY))
        }
        val spot = Places.beside(level, target, homeSpot(p, life)) ?: return hold(p, ItemStack.EMPTY)
        val step = if (basin != null) Step(Activity.POUR) else Step(Activity.PUT_DOWN)
        val putting = Work(p, spot, Jobs.top(target, 0.9), listOf(step, Step(Activity.NONE, 20)), target, onTick = { s, t ->
            if (s === step && t == 12) {
                p.carried = ItemStack.EMPTY
                if (basin != null) fill(level, basin)
            }
        })
        return Ways.to(p, life.places, spot.pos, laden, near = 1.0, overlay = overlay).andThen(putting).andThen(hold(p, ItemStack.EMPTY))
    }

    /** A bucket poured into the basin at [pos]: it's full again. */
    private fun fill(level: ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        val full = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, LayeredCauldronBlock.MAX_FILL_LEVEL)
        if (state.`is`(Blocks.CAULDRON) || state.`is`(Blocks.WATER_CAULDRON) && state.getValue(LayeredCauldronBlock.LEVEL) < LayeredCauldronBlock.MAX_FILL_LEVEL) {
            level.setBlock(pos, full, Block.UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 0.6f, 1f)
        }
    }

    /** Errands go to these: a neighbour, the guild, the smith, the workshop, the counting house, the library, the bakery. */
    private val ERRANDS = listOf("HOUSE", "HOUSE", "HOUSE", "GUILD_HALL", "SMITHY", "WORKSHOP", "COUNTING_HOUSE", "LIBRARY", "BAKERY", "COTTAGE")

    /** Something taken to a door across town (a parcel, a sack), or a word at it: a knock, a wait, a hand over. */
    fun errand(p: Townsperson, life: Life): Task? {
        if (!p.role.adult) return null
        val places = life.places
        val kind = ERRANDS[r(p, ERRANDS.size)]
        val from = life.home?.box?.center ?: p.blockPosition()
        val choices = places.nearest(from, setOf(kind)).filter { it !== life.home && it.front != null }.take(8)
        if (choices.isEmpty()) return null
        val door = choices[r(p, choices.size)].front!!
        val parcel = r(p, 3) != 0
        val overlay = if (!parcel) Activity.NONE else if (r(p, 2) == 0) Activity.CARRY else Activity.CARRY_SACK
        val knocking = Work(p, Spot(door.outside, door.inward), Jobs.top(door.pos, 1.0), listOf(
            Step(Activity.KNOCK), Step(Activity.NONE, 40 + r(p, 40)), Step(if (parcel) Activity.HAND_OVER else Activity.TALK, if (parcel) Activity.HAND_OVER.ticks else 120),
            Step(Activity.NOD),
        ))
        return Ways.to(p, places, door.outside, if (parcel) 0.42 else Jobs.WALK, near = 1.2, overlay = overlay).andThen(knocking)
    }

    /** A call on a neighbour: a knock, and a sit and a talk at their table. */
    private fun visit(p: Townsperson, life: Life): Task? {
        val level = level(p)
        val home = life.home ?: return null
        val neighbours = Residents.get(level).of(life.resident.capital).asSequence()
            .filter { it.alive && it.home >= 0 && it.home != home.id }
            .mapNotNull { life.places.lodgings[it.home] }.distinct()
            .sortedBy { it.box.center.distSqr(home.box.center) }.take(8).toList()
        if (neighbours.isEmpty()) return null
        val there = neighbours[r(p, neighbours.size)]
        val door = there.front ?: return null
        val knock = Work(p, Spot(door.outside, door.inward), Jobs.top(door.pos, 1.0), listOf(Step(Activity.KNOCK), Step(Activity.NONE, 30), Step(Activity.WAVE)))
        // Asked in: a seat at their table, found once they're at the door
        val stay = Later(p) {
            val seat = seatIn(level, there, p, table = true)
            if (seat != null) sitAt(p, life, seat, 600 + r(p, 600), listOf(Activity.SIT_TALK, Activity.SIT_TALK, Activity.SIT, Activity.SIT_DRINK))
            else Walk(p, door.inside, Jobs.WALK, near = 1.0).andThen(Idle(p, 300 + r(p, 300), Activity.TALK))
        }
        return Ways.to(p, life.places, door.outside, Jobs.WALK, near = 1.2).andThen(knock).andThen(stay)
    }

    // --- Temple, strolls, lessons, evenings ---

    private fun temple(life: Life): CityPlaces.Lodging? = life.places.nearest(life.home?.box?.center ?: life.places.market, CityPlaces.TEMPLES).firstOrNull()

    /** A prayer: a while on the temple's benches, then a moment standing in prayer. */
    fun worship(p: Townsperson, life: Life): Task? {
        val level = level(p)
        val temple = temple(life) ?: return null
        val seat = seatIn(level, temple, p, table = false)
        if (seat != null) return sitAt(p, life, seat, 500 + r(p, 500), listOf(Activity.SIT)).andThen(Idle(p, 120 + r(p, 120), Activity.PRAY, chatty = false))
        val door = temple.front ?: return null
        return Ways.to(p, life.places, door.inside.relative(door.inward, 2), Jobs.STROLL, near = 2.0).andThen(Idle(p, 240 + r(p, 240), Activity.PRAY, chatty = false))
    }

    /** Children at their letters: on the temple's benches (or the library's) while the priest reads. */
    private fun lessons(p: Townsperson, life: Life): Task? {
        val level = level(p)
        val school = temple(life) ?: life.places.nearest(life.home?.box?.center ?: p.blockPosition(), setOf("LIBRARY")).firstOrNull() ?: return null
        val seat = seatIn(level, school, p, table = true) ?: return null
        return sitAt(p, life, seat, 900 + r(p, 600), listOf(Activity.SIT, Activity.SIT, Activity.SIT_TALK))
    }

    /** A turn about the town to a sight (a well, a bench, the market, the bell), and a long look, or a sit. */
    fun stroll(p: Townsperson, life: Life): Task? {
        val level = level(p)
        if (level.isRaining) return null
        val here = p.blockPosition()
        val places = life.places
        val sights = (places.wells + places.benches.map { it.pos } + listOf(places.market, places.bell))
            .filter { level.isLoaded(it) && it.distSqr(here) in 100.0..(90.0 * 90.0) }
        if (sights.isEmpty()) return null
        val sight = sights[r(p, sights.size)]
        val bench = places.benches.firstOrNull { it.pos.distSqr(sight) < 16.0 && Claims.free(level, it.pos, p) && Places.isSeat(level, level.getBlockState(it.pos), it.pos) }
        val after = if (bench != null && r(p, 3) == 0) Sit(p, bench.pos, 300 + r(p, 400), listOf(Activity.SIT))
        else Idle(p, 100 + r(p, 200), Activity.ADMIRE)
        return Ways.to(p, places, sight, Jobs.STROLL, near = 3.0, overlay = Activity.STROLL).andThen(after)
    }

    /** The tavern they go to: the nearest to home. */
    fun tavern(p: Townsperson, life: Life): Task? {
        if (p.role == TownRole.BARD) return Jobs.evening(p)
        val level = level(p)
        val inn = life.places.nearest(life.home?.box?.center ?: p.blockPosition(), CityPlaces.TAVERNS).firstOrNull { it.front != null }
            ?: return Jobs.evening(p)
        val seat = seatIn(level, inn, p, table = true)
        if (seat != null && r(p, 4) != 0) {
            return sitAt(p, life, seat, 600 + r(p, 900), listOf(Activity.SIT, Activity.SIT_DRINK, Activity.SIT_DRINK, Activity.SIT_TALK))
        }
        val door = inn.front!!
        return Ways.to(p, life.places, door.inside.relative(door.inward, 2), Jobs.BRISK, near = 2.5)
            .andThen(Mingle(p, 400 + r(p, 500), listOf(Activity.DRINK, Activity.DRINK, Activity.TALK, Activity.LISTEN, Activity.LAUGH, Activity.CHEER), tavern = true))
    }

    /** An evening at home: a book, the fire seen to, a sit at the table. */
    private fun evening(p: Townsperson, life: Life): Task? {
        val level = level(p)
        val home = life.home ?: return null
        when (r(p, 4)) {
            0 -> home.stations.firstOrNull { (it.m == M.LECTERN || it.m == M.BOOKSHELF) && level.isLoaded(it.pos) }?.let { book ->
                Places.beside(level, book.pos, p.blockPosition())?.let { spot ->
                    return via(p, life, spot.pos, Work(p, spot, Jobs.top(book.pos, 1.0), listOf(Step(Activity.READ, 120 * (2 + r(p, 3))))))
                }
            }
            1 -> home.stations.firstOrNull { it.m == M.FURNACE && level.isLoaded(it.pos) }?.let { fire ->
                Places.beside(level, fire.pos, p.blockPosition())?.let { spot ->
                    return via(p, life, spot.pos, Work(p, spot, Jobs.top(fire.pos, 0.5), listOf(Step(Activity.TEND, 40 * (2 + r(p, 3)))), fire.pos))
                }
            }
        }
        return atHome(p, life)
    }
}
