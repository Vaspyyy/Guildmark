package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Heap
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Pull
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Walker
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A caravan at its stand (design-road.md section 1, "Arrival"): the driver hauls on the brake, gets down, gives the
 * horse its nosebag and climbs onto the tailboard; the crates and sacks come off the bed one by one, handed down to
 * a guard who carries each to the stack by the back wheel; two of the town's carters come with their handcarts,
 * load from the stack and take it to the market hall (the flour to the bakery). Then the hour's standing, the horse
 * at its nosebag, the exports heaped on the bed, and at its end the crew back aboard to drive out.
 */
internal class Unloading(val show: Show) {
    private enum class Stage { ARRIVING, UNLOADING, STANDING, BOARDING }

    private val level: ServerLevel get() = show.level
    private var stage = Stage.STANDING
    private var t = 0

    /** Whether the tailboard's let down for it. */
    var open = false
        private set

    /** What's set down on the stack by the back wheel (Wagon.STACK places). */
    private val stack = arrayOfNulls<Good>(Wagon.SLOTS)

    /** The guards who take the load as it's handed down. */
    private val takers = ArrayList<Hand>()

    // The piece coming down: its place on the bed, who takes it, and the ticks into hand_down (-1: waiting for a taker)
    private var slot = -1
    private var taker: Hand? = null
    private var handT = -1
    private var good: Good? = null
    private var calling: Hand? = null

    private val carters = ArrayList<Carter>()
    private var lookedForCarters = -1000
    private var exported = false
    private var unloaded = false
    private var driverDown = false

    // ---- Beginning ----

    /** Pulled up at the stand: [fresh] with the load still on (else come upon standing, the load long gone). */
    fun start(fresh: Boolean) {
        if (!fresh) {
            stage = Stage.STANDING
            unloaded = true
            driverDown = true
            return
        }
        stage = Stage.ARRIVING
        val d = show.driver ?: return
        d.clear()
        // The brake hauled on, down, the nosebag from under the bench, over to the horse with it
        d.then(Play(Activity.DRIVE_BRAKE))
        d.then(JumpDown(byHand = true))
        d.then(Go({ listOf(show.ground(-22f, -50f)) }, 0.1, { show.yaw(-90f) }))
        d.then(Play(Activity.PICK_UP)).then { show.wagon?.flag(Wagon.FLAG_NOSEBAG, true) }
        d.then(Go({ listOf(nosebagSpot().first) }, 0.1, { nosebagSpot().second }))
        d.then(Clip(Activity.HANG, listOf<Pair<Int, (Hand) -> Unit>>(NOSEBAG_ON to { _ -> nosebag(true) }), NOSEBAG_TICKS))
        // Then onto the tailboard, to hand the load down
        d.then(Go({ show.around(it, it.beside(Wagon.TAIL, ClimbUp.CLIMB_RIGHT, -ClimbUp.CLIMB_BEHIND, ClimbUp.CLIMB_TURN).first) }, 0.11))
        d.then(ClimbUp(Wagon.TAIL, Activity.NONE)).then(Slide(Wagon.STAND)).then {
            it.p.act(Activity.NONE)
            stage = Stage.UNLOADING
        }
        // The guards down, and round to the back to take it
        val guards = show.hands.filter { !it.driver }
        for ((k, g) in guards.withIndex()) {
            g.clear()
            when (g.seat) {
                Wagon.BENCH -> g.then(Until(80) { show.driver?.seat != Wagon.DRIVER }).then(Slide(Wagon.DRIVER)).then(JumpDown(byHand = true))
                Wagon.OFF -> g.then(Wait(10))
                else -> g.then(Wait(24)).then(JumpDown(byHand = true))
            }
            if (k < 2) {
                takers.add(g)
                g.then(Go({ show.around(it, home(k).first) }, 0.1, { home(k).second })).then { it.p.act(Activity.NONE) }
            } else {
                g.then(Go({ show.around(it, guardPost(k).first) }, 0.1, { guardPost(k).second })).then { it.p.act(Activity.STAND_GUARD) }
            }
        }
        open = true
        show.tailboard()
        borrowCarters()
        // The works' freight on it, carried over to their yards by porters of its own (economy/works/build/Unloads)
        io.github.vaspyyy.guildmark.economy.works.build.Unloads.arrived(level, show.id, show.wagonAt())
    }

    /** Where the driver stands to put the nosebag on: by the horse's head, on its right, facing it. */
    private fun nosebagSpot(): Pair<Vec3, Float> {
        val h = show.horse ?: return show.ground(-20f, -100f) to show.yaw(0f)
        return show.horsePoint(-16f, -30f) to net.minecraft.util.Mth.wrapDegrees(h.yRot - 70f)
    }

    /** The nosebag on the horse (or off it again, into the driver's hand). */
    private fun nosebag(on: Boolean) {
        val h = show.horse
        val w = show.wagon
        w?.flag(Wagon.FLAG_NOSEBAG, !on)
        h?.flag(DraughtHorse.FLAG_NOSEBAG, on)
        h?.move(if (on) DraughtHorse.Move.NOSEBAG else DraughtHorse.Move.IDLE)
    }

    /** Where the takers wait: one just off the tailboard to take the next piece, the other a step back. */
    private fun home(k: Int): Pair<Vec3, Float> =
        if (k == 0) show.ground(0f, RECEIVE_Z) to show.yaw(0f) else show.ground(-18f, RECEIVE_Z + 8f) to show.yaw(0f)

    private fun guardPost(k: Int): Pair<Vec3, Float> =
        if (k % 2 == 0) show.ground(-30f, -12f) to show.yaw(90f) else show.ground(30f, -12f) to show.yaw(-90f)

    // ---- Each tick ----

    /** One tick; false once the crew are back aboard and the record has the wagon leaving. */
    fun tick(): Boolean {
        t++
        val leaving = show.view.state != Caravans.State.STANDING || Caravans.view(show.id) == null
        when (stage) {
            Stage.ARRIVING -> if (leaving && !holdOn()) finish()
            Stage.UNLOADING -> {
                handDown()
                if (leaving && !holdOn()) finish()
            }
            Stage.STANDING -> {
                standing()
                if (leaving && (stackEmpty() || !holdOn())) board()
            }
            Stage.BOARDING -> {
                if (!holdOn()) {
                    // Waited as long as it can: whoever isn't up yet is put there
                    seatEveryone()
                    return false
                }
                if (aboard()) {
                    if (!exported) show.loadExports()
                    return false
                }
            }
        }
        for (c in carters.toList()) if (!c.tick()) carters.remove(c)
        if (stage != Stage.BOARDING && carters.size < 2 && t - lookedForCarters > CARTER_LOOK && !stackEmpty()) borrowCarters()
        // Nobody's carters, and nobody near to see it go: the stack's taken off
        if (unloaded && carters.isEmpty() && !stackEmpty() && !show.watched(40.0)) {
            stack.fill(null)
            show.wagon?.stack(stack.toList())
        }
        if (stage == Stage.STANDING && !exported && unloaded && stackEmpty() && level.players().none { !it.isSpectator && flat(it.position(), show.wagonAt()) < 32.0 }) {
            exported = true
            show.loadExports()
        }
        val wasOpen = open
        open = stage == Stage.UNLOADING || stage == Stage.ARRIVING && show.driver?.seat == Wagon.TAIL
        if (open != wasOpen) show.tailboard()
        return true
    }

    /** The record wants the wagon gone: it waits a while longer for the crew. */
    private fun holdOn(): Boolean = show.holdLeaving()

    /** The load handed down, piece by piece: the driver lifts one off the bed, a guard takes it and carries it to the stack. */
    private fun handDown() {
        val w = show.wagon ?: return
        val d = show.driver ?: return
        if (d.seat != Wagon.STAND) return
        if (handT >= 0) {
            handT++
            val tk = taker
            when (handT) {
                LIFT -> {
                    val g = w.piece(slot)
                    good = g
                    if (g != null) {
                        w.load((0 until Wagon.SLOTS).map { if (it == slot) null else w.piece(it) })
                        d.p.carried = Handcarts.stack(g)
                    }
                }
                HANDOFF -> {
                    d.p.carried = ItemStack.EMPTY
                    val g = good
                    if (g != null && tk != null) tk.p.carried = Handcarts.stack(g)
                }
                Activity.HAND_DOWN.ticks -> {
                    d.p.act(Activity.NONE)
                    val g = good
                    if (tk != null && g != null) carry(tk, g)
                    handT = -1
                    taker = null
                }
            }
            return
        }
        val next = Waylay.SMASH_ORDER.firstOrNull { w.piece(it) != null }
        if (next == null) {
            if (!unloaded) doneUnloading()
            return
        }
        // Whoever's free comes to take it
        val free = takers.firstOrNull { !it.p.isRemoved && it.idle && it.p.carried.isEmpty } ?: return
        val (spot, yaw) = home(0)
        if (flat(free.p.position(), spot) > 0.4) {
            if (calling !== free) {
                calling = free
                free.then(Go({ listOf(home(0).first) }, 0.1, { home(0).second }))
            }
            return
        }
        calling = null
        free.p.face(yaw)
        free.p.lookAt(d.p)
        d.p.lookAt(free.p)
        slot = next
        taker = free
        handT = 0
        d.p.act(Activity.HAND_DOWN)
        free.p.act(Activity.RECEIVE)
    }

    /** [h] carries [g] to the stack and sets it down there, then comes back for the next. */
    private fun carry(h: Hand, g: Good) {
        val j = stack.indices.firstOrNull { stack[it] == null && pending[it] == null } ?: return h.p.let { it.carried = ItemStack.EMPTY }
        pending[j] = g
        val place = Wagon.STACK_XZ[j % 8]
        val (spot, yaw) = show.ground(place[0] - PUT_OUT, place[1]) to show.yaw(-90f)
        val sack = sack(g)
        h.then { it.p.act(if (sack) Activity.CARRY_SACK else Activity.CARRY) }
        h.then(Go({ listOf(spot) }, 0.09, { yaw }))
        h.then(Clip(Activity.PUT_DOWN, listOf<Pair<Int, (Hand) -> Unit>>(PUT_AT to { it ->
            it.p.carried = ItemStack.EMPTY
            stack[j] = pending[j]
            pending[j] = null
            show.wagon?.stack(stack.toList())
            Bodies.sound(level, show.ground(place[0], place[1]), "crate_down", 0.8f, 0.9f + level.random.nextFloat() * 0.2f)
        })))
        h.then(Go({ listOf(home(1).first) }, 0.1, { home(1).second })).then { it.p.act(Activity.NONE) }
    }

    /** Pieces on their way to the stack (in a guard's arms), by the place they'll go. */
    private val pending = arrayOfNulls<Good>(Wagon.SLOTS)

    private fun sack(g: Good): Boolean = g == Good.WHEAT || g == Good.POTATO || g == Good.FLOUR || g == Good.COAL || g == Good.FLINT

    private fun doneUnloading() {
        unloaded = true
        stage = Stage.STANDING
        val d = show.driver ?: return
        // Down off the tailboard, and over by the horse for the hour
        d.clear()
        d.then(JumpDown(byHand = true))
        d.then(Go({ show.around(it, nosebagSpot().first) }, 0.1, { nosebagSpot().second })).then { it.p.act(Activity.NONE) }
        driverDown = true
        for ((k, g) in takers.withIndex()) {
            g.then(Go({ show.around(it, guardPost(k).first) }, 0.1, { guardPost(k).second })).then { it.p.act(Activity.STAND_GUARD) }
        }
    }

    /** The hour's standing: the crew about the wagon, the driver by the horse, a word for whoever comes by. */
    private fun standing() {
        val d = show.driver ?: return
        if (!d.idle) return
        val near = level.getNearestPlayer(d.p, 10.0)
        if (near != null && !near.isSpectator) d.p.lookAt(near)
        else if (t % 200 == 0) show.horse?.let { d.p.lookAt(it.position().add(0.0, 1.8, 0.0)) }
    }

    /** The record has it leaving: the nosebag off, everyone back up, the exports on. */
    private fun board() {
        stage = Stage.BOARDING
        open = false
        // Whatever's still on the stack is the town's now: it's gone with the last cart
        if (stack.any { it != null }) {
            stack.fill(null)
            show.wagon?.stack(stack.toList())
        }
        val d = show.driver
        if (d != null) {
            d.clear()
            if (d.seat != Wagon.OFF) d.then(JumpDown(byHand = true))
            if (show.horse?.has(DraughtHorse.FLAG_NOSEBAG) == true) {
                d.then(Go({ show.around(it, nosebagSpot().first) }, 0.1, { nosebagSpot().second }))
                d.then(Clip(Activity.HANG, listOf<Pair<Int, (Hand) -> Unit>>(NOSEBAG_ON to { _ -> nosebag(false) }), NOSEBAG_TICKS))
                d.then(Go({ listOf(show.ground(-22f, -50f)) }, 0.1, { show.yaw(-90f) }))
                d.then(Play(Activity.PUT_DOWN)).then { show.wagon?.flag(Wagon.FLAG_NOSEBAG, false) }
            }
        }
        if (!exported) {
            exported = true
            show.loadExports()
        }
        for (h in show.hands) {
            if (!h.driver) h.clear()
            show.board(h)
        }
        show.tailboard()
    }

    private fun aboard(): Boolean = show.hands.all { h ->
        val seat = show.boardingSeat(h.index)
        h.idle && (seat == Wagon.OFF || h.seat == seat)
    }

    private fun seatEveryone() {
        for (h in show.hands) {
            val seat = show.boardingSeat(h.index)
            h.clear()
            if (seat != Wagon.OFF) {
                h.seatOn(seat)
                h.p.act(show.seatedActivity(seat))
            }
            h.p.carried = ItemStack.EMPTY
        }
        nosebag(false)
        show.wagon?.flag(Wagon.FLAG_NOSEBAG, false)
        stack.fill(null)
        show.wagon?.stack(stack.toList())
        if (!exported) show.loadExports()
    }

    /** The record's leaving before the load's all off (it waited as long as it could): what's left goes with the stack. */
    private fun finish() {
        val w = show.wagon
        w?.load(List(Wagon.SLOTS) { null })
        show.driver?.p?.carried = ItemStack.EMPTY
        for (h in takers) h.p.carried = ItemStack.EMPTY
        unloaded = true
        stage = Stage.STANDING
        board()
    }

    private fun stackEmpty(): Boolean = stack.all { it == null } && pending.all { it == null }

    // ---- The carters ----

    /** Two of the town's carters within reach of the stand, borrowed from their round for it. */
    private fun borrowCarters() {
        lookedForCarters = t
        val at = show.wagonAt()
        val box = AABB(at, at).inflate(CARTERS, 16.0, CARTERS)
        val found = level.getEntitiesOfClass(Townsperson::class.java, box) { able(it) }.sortedBy { it.distanceToSqr(at) }
        for (p in found) {
            if (carters.size >= 2) break
            if (carters.any { it.p === p }) continue
            val c = Carter(p)
            p.standUp()
            p.routine.interrupt()
            p.navigation.stop()
            p.director = c
            carters.add(c)
        }
    }

    private fun able(p: Townsperson): Boolean =
        p.life?.carter == true && p.isAlive && !p.isRemoved && p.director == null && p.captiveOf < 0 && !p.isLeader && !p.watcher &&
            !p.crowd && !p.isPassenger && p.attending == null && p.activity != Activity.FLEE && !Crew.of(p) &&
            level.isPositionEntityTicking(p.blockPosition())

    /** Where the carters leave their carts to load: a few blocks out from the stack. */
    private fun cartBay(): BlockPos? {
        val at = show.ground(CART_X, CART_Z)
        val base = BlockPos.containing(at.x, at.y + 0.5, at.z)
        for (r in 0..2) for (dx in -r..r) for (dz in -r..r) for (dy in intArrayOf(0, 1, -1)) {
            val p = base.offset(dx, dy, dz)
            if (Places.standable(level, p)) return p
        }
        return null
    }

    /** The top piece of the stack, taken off it: its good, or null with nothing there. */
    private fun takeTop(): Good? {
        for (j in Wagon.SLOTS - 1 downTo 0) {
            val g = stack[j] ?: continue
            stack[j] = null
            show.wagon?.stack(stack.toList())
            return g
        }
        return null
    }

    /** Where to stand to pick the stack's top piece up, and the way to face. */
    private fun stackSide(): Pair<BlockPos, Float> {
        val j = (Wagon.SLOTS - 1 downTo 0).firstOrNull { stack[it] != null } ?: 0
        val place = Wagon.STACK_XZ[j % 8]
        val at = show.ground(place[0] - PUT_OUT, place[1])
        return BlockPos.containing(at.x, at.y + 0.5, at.z) to show.yaw(-90f)
    }

    /**
     * One borrowed carter: their cart brought to the stack, loaded from it piece by piece, pulled to the market hall
     * (or the bakery, with only flour on it) and unloaded at its door; then back to their round.
     */
    private inner class Carter(val p: Townsperson) : Director {
        private val goods = ArrayList<ItemStack>()
        private val kinds = ArrayList<Good>()
        private var task: Task? = null
        private val walker = Walker(p)
        private var step = 0
        private var wait = 0
        private var door: CityPlaces.Door? = null
        private var gone = false
        private var age = 0

        override fun tick(p: Townsperson) {}

        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            p.lookAt(player)
            p.say("carter_hello_0", force = true)
            return true
        }

        /** One tick; false once they've gone back to their round. */
        fun tick(): Boolean {
            if (gone) return false
            age++
            if (p.isRemoved || !p.isAlive || p.director !== this || age > CARTER_MOST) return release()
            val running = task
            if (running != null) {
                if (running.tick()) return true
                running.stop()
                task = null
                if ((running as? Pull)?.failed == true || (running as? Heap)?.missed == true) return release()
            }
            when (step) {
                // The cart fetched and pulled round to the stack
                0 -> {
                    val bay = cartBay() ?: return release()
                    p.say("carter_arrive_" + level.random.nextInt(2), force = false, hearing = 16.0)
                    run(Pull(p, bay, goods.toList()))
                    step = 1
                }
                // At the stack: pick up the top piece, onto the cart with it
                1 -> {
                    if (goods.size >= Handcart.SLOTS || unloaded && stack.all { it == null }) {
                        if (goods.isEmpty()) return release()
                        step = 3
                        return true
                    }
                    if (stack.all { it == null }) {
                        // Waiting for the next piece
                        if (age % 40 == 0) p.lookAt(show.wagonAt().add(0.0, 1.0, 0.0))
                        return true
                    }
                    val (at, _) = stackSide()
                    walker.go(at, Trades.LADEN, exact = true)
                    step = 2
                }
                2 -> {
                    if (wait > 0) {
                        wait--
                        if (wait == PICK_FOR - PICK_AT) {
                            val g = takeTop()
                            if (g == null) {
                                step = 1
                                wait = 0
                                p.act(Activity.NONE)
                                return true
                            }
                            kinds.add(g)
                            p.carried = Handcarts.stack(g)
                        }
                        if (wait == 0) {
                            val held = p.carried.copy()
                            val g = kinds.lastOrNull()
                            run(Heap(p, goods, held, from = show.wagonAt(), overlay = if (g != null && sack(g)) Activity.CARRY_SACK else Activity.CARRY))
                            step = 1
                        }
                        return true
                    }
                    when (walker.tick()) {
                        Walker.State.WALKING -> return true
                        Walker.State.FAILED -> return release()
                        Walker.State.ARRIVED -> {
                            walker.stop()
                            p.face(stackSide().second)
                            p.act(Activity.PICK_UP)
                            wait = PICK_FOR
                        }
                    }
                }
                // Off to the hall (or the bakery) with it
                3 -> {
                    val places = p.life?.places
                    val flour = kinds.isNotEmpty() && kinds.all { it == Good.FLOUR }
                    val kind = if (flour) Kind.BAKERY.name else Kind.MARKET_HALL.name
                    val lodging = places?.nearest(p.blockPosition(), setOf(kind))?.firstOrNull { it.front != null }
                        ?: places?.nearest(p.blockPosition(), setOf(Kind.MARKET_HALL.name, Kind.BAKERY.name))?.firstOrNull { it.front != null }
                    val d = lodging?.front
                    door = d
                    val bay = d?.let { Handcarts.bay(level, it, p) }
                    if (bay == null) {
                        // Nowhere to take it: it's off the cart and away all the same
                        goods.clear()
                        Handcarts.load(p, goods)
                        return release()
                    }
                    p.say("carter_leave_" + level.random.nextInt(2), force = false, hearing = 16.0)
                    run(Pull(p, bay, goods.toList()))
                    step = 4
                }
                // Each piece off the cart and in at the door
                4 -> {
                    val d = door ?: return release()
                    if (goods.isEmpty()) return release()
                    val last = goods.last().copy()
                    run(Heap(p, goods, null, last, from = Vec3.atCenterOf(d.outside)))
                    step = 5
                }
                5 -> {
                    val d = door ?: return release()
                    walker.go(d.outside, Trades.LADEN, near = 1.0)
                    step = 6
                }
                6 -> {
                    if (wait > 0) {
                        if (--wait == PUT_FOR - PUT_AT) p.carried = ItemStack.EMPTY
                        if (wait == 0) step = 4
                        return true
                    }
                    when (walker.tick()) {
                        Walker.State.WALKING -> return true
                        Walker.State.FAILED -> {
                            p.carried = ItemStack.EMPTY
                            step = 4
                        }
                        Walker.State.ARRIVED -> {
                            walker.stop()
                            door?.let { p.face(it.inward.toYRot()) }
                            p.act(Activity.PUT_DOWN)
                            wait = PUT_FOR
                        }
                    }
                }
            }
            return true
        }

        private fun run(next: Task) {
            task = next
            next.start()
        }

        /** Back to their round: the cart left where it stands, anything still in their hands put by. */
        fun release(): Boolean {
            if (gone) return false
            gone = true
            task?.stop()
            task = null
            walker.stop()
            p.carried = ItemStack.EMPTY
            if (p.director === this) p.director = null
            p.act(Activity.NONE)
            p.navigation.stop()
            p.routine.interrupt()
            return false
        }
    }

    /** Its bodies go: the carters go back to their round, the stack with the wagon. */
    fun abandon() {
        for (c in carters) c.release()
        carters.clear()
        show.driver?.p?.carried = ItemStack.EMPTY
        for (h in takers) h.p.carried = ItemStack.EMPTY
    }

    companion object {
        /** CrewMoves.HAND_DOWN: the ticks the piece is lifted off the bed and handed to the taker; the taker this far out (z, px). */
        private const val LIFT = 7
        private const val HANDOFF = 16
        private const val RECEIVE_Z = 12.5f + 24f

        /** How far out from a stack place the guard stands to set a piece down on it (px). */
        private const val PUT_OUT = 14f

        /** The tick of put_down the piece leaves the hands (as Heap has it), of pick_up it comes into them, and their lengths. */
        private const val PUT_AT = 11
        private const val PUT_FOR = 20
        private const val PICK_AT = 8
        private const val PICK_FOR = 20

        /** The tick of fest_hang the nosebag goes on (or comes off) the horse's head, and how long the driver's at it. */
        private const val NOSEBAG_ON = 20
        private const val NOSEBAG_TICKS = 40

        /** How far round the stand carters are looked for, and how often again while there's a stack to shift. */
        private const val CARTERS = 64.0
        private const val CARTER_LOOK = 200

        /** The longest a carter is kept from their round. */
        private const val CARTER_MOST = 6000

        /** Where the carters leave their carts to load, in the wagon's space (px). */
        private const val CART_X = -96f
        private const val CART_Z = 16f
    }
}
