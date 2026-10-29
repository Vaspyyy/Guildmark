package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.village.live.VillageSounds
import io.github.vaspyyy.guildmark.village.live.links.GreetWhy
import io.github.vaspyyy.guildmark.village.live.links.Greeters
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * A village's pedlar for one day, in the body [Pedlars] gave him (the villages design, section 7.5): a function of the clock, so that
 * someone who walks in at noon finds him where the day has him.
 *
 * He comes in from the road end pulling his handcart (the goods the call will bring heaped on it), parks it at the green, lets go
 * of the shafts and sets up beside it, rings his hand bell and calls out "Fresh from the road!" (the call of the market's record is made
 * at nine, and the cart is made to show what it really brought). Then he minds the cart: tidies the goods, calls to anyone near,
 * browses and haggles with the folk who come to shop, and a player who uses him gets the counter ([Pedlars.counter]: the market hall's
 * screen at the village's own market, so what he sells out of is out for the village). At four he packs up: the cart is
 * emptied and loaded with what the call took away, he takes up the shafts, waves, and walks out by the road end. Out of sight he goes
 * (the crowd's rule, [io.github.vaspyyy.guildmark.townsfolk.Crowds.maybeLeave]); never in view.
 *
 * While a customer is at the counter [io.github.vaspyyy.guildmark.economy.Serving] has charge of him; [Pedlars] gives him back to this
 * when it lets go. With carts off ([Handcarts.enabled]) he does without: he stands at the green with his goods all the same.
 */
class Pedlar(
    val bell: Long,
    val person: PedlarRoster.Person,
    val day: Long,
    /** Where the cart stands at the green. */
    val bay: BlockPos,
    /** The road end he leaves by (and came in by). */
    private var exit: BlockPos,
    start: Stage,
    /** He came after half past nine: the roads were bad. */
    private val late: Boolean,
) : Director {
    /** The order of his day. */
    enum class Stage {
        /** Set down at the cart already (nobody saw him come): the cart is brought round and parked, and he stands by it. */
        SETTLE,

        /** At the road end: the cart brought round behind him, the goods heaped on it. */
        HITCH,
        WALK_IN,
        STOP,
        SIDE,
        SET_UP,
        BELL,

        /** The cart made to show what the call brought, a piece at a time. */
        UNPACK,
        MIND,

        /** Four o'clock: the stall comes down. */
        PACK,
        UNLOAD,
        LOAD,
        FETCH,
        TAKE,
        WAVE,
        WALK_OUT,
    }

    var stage: Stage = start
        private set

    private var walker: Walker? = null
    private var goal: BlockPos? = null
    private var goalSpeed = Jobs.WALK
    private var goalNear = 1.5
    private var goalExact = false
    private var wait = 0
    private var tries = 0
    private var stuck = 0
    private var fetches = 0

    /** The goods shown on the cart now, in the order they are heaped. */
    private var shown: MutableList<ItemStack> = ArrayList()
    private var wanted: List<ItemStack> = emptyList()

    /** The beat of his minding that is under way, and the tick (his own) it ends. */
    private var mine = Activity.NONE
    private var beatEnds = 0

    /** Players he has greeted, and when (game time). */
    private val greeted = HashMap<UUID, Long>()
    private var greeting: ServerPlayer? = null
    private var greetUntil = 0L
    private var nextCall = 0L

    /** Whether he has started out on the walk to the road end, and the legs of it walked. */
    private var outbound = false
    private var legs = 0

    /** The counter is open: he is at the cart, ringing, arranging or minding it. */
    val open: Boolean get() = stage == Stage.BELL || stage == Stage.UNPACK || stage == Stage.MIND

    /** He is going: packing up or on the road out. */
    val leaving: Boolean get() = stage >= Stage.PACK

    /** In a few words, what he is at now (for `village cart`). */
    val doing: String
        get() = when (stage) {
            Stage.SETTLE, Stage.HITCH -> "arriving"
            Stage.WALK_IN, Stage.STOP -> "coming in with the cart"
            Stage.SIDE, Stage.SET_UP, Stage.BELL, Stage.UNPACK -> "setting up at the green"
            Stage.MIND -> "at the cart on the green"
            Stage.PACK, Stage.UNLOAD, Stage.LOAD, Stage.FETCH, Stage.TAKE, Stage.WAVE -> "packing up"
            Stage.WALK_OUT -> "leaving by the road"
        }

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val now = level.gameTime
        val h = Pedlars.clock(level)
        // The day is over, or he is not wanted any more (the switch off): home by the road
        if (stage < Stage.PACK && (Days.dayOf(level.overworldClockTime) != day || !Pedlars.wanted())) {
            leave(p)
        } else if (stage < Stage.PACK && h >= PedlarRoster.PACK_UP) {
            if (stage >= Stage.BELL) pack(p) else leave(p)
        }
        walker?.let { w ->
            if (Handcarts.pulling(p)) p.act(Handcarts.PULL)
            when (w.tick()) {
                Walker.State.WALKING -> {
                    if (stage == Stage.WALK_IN) lateWord(p, level)
                    return
                }
                Walker.State.ARRIVED -> {
                    w.stop()
                    walker = null
                    tries = 0
                    stuck = 0
                }
                Walker.State.FAILED -> {
                    w.stop()
                    walker = null
                    // In view and cannot get there: try again; if it goes on, let him slip away when nobody looks
                    if (++stuck >= STUCK) p.leaveAt = now
                    goal?.let { go(p, it, goalSpeed, goalNear, goalExact) }
                    return
                }
            }
        }
        if (wait > 0) {
            wait--
            return
        }
        when (stage) {
            Stage.SETTLE -> settle(p, level)
            Stage.HITCH -> {
                if (carts() && hitch(p) == null) return retry(p)
                tries = 0
                wanted = Pedlars.pieces(level, bell, day)
                shown = ArrayList(wanted)
                if (carts()) Handcarts.load(p, shown)
                p.act(Handcarts.PULL)
                stage = Stage.WALK_IN
                Greeters.call(level, bell, exit, GreetWhy.PEDLAR)
                go(p, bay, Trades.LADEN, near = 1.0)
            }
            Stage.WALK_IN -> {
                // At the bay: stop, then let go of the shafts
                p.act(Activity.NONE)
                wait = 15
                stage = Stage.STOP
            }
            Stage.STOP -> {
                if (carts()) Handcarts.unhitch(p)
                p.act(Activity.NONE)
                wait = 20
                stage = Stage.SIDE
            }
            Stage.SIDE -> {
                stage = Stage.SET_UP
                Handcarts.side(p, p.position())?.let { go(p, it, Jobs.WALK, exact = true) }
            }
            Stage.SET_UP -> {
                faceBed(p)
                p.act(Activity.SET_UP)
                wait = Activity.SET_UP.ticks
                stage = Stage.BELL
            }
            Stage.BELL -> {
                Pedlars.openStall(level, bell, p)
                faceBed(p)
                p.act(Activity.RING_BELL)
                // The pedlar's own small brass bell, from the server for everyone near (the client rings no crier's bell for them)
                VillageSounds.play(level, p.position(), VillageSounds.PEDLAR_BELL)
                p.say("pedlar_arrive", force = true, hearing = SPEAK, ticks = 70)
                wait = 2 * Activity.RING_BELL.ticks + 10
                stage = Stage.UNPACK
            }
            Stage.UNPACK -> unpack(p, level, h)
            Stage.MIND -> mind(p, level, now)
            Stage.PACK -> {
                faceBed(p)
                p.act(Activity.PACK)
                wait = Activity.PACK.ticks
                stage = Stage.UNLOAD
            }
            Stage.UNLOAD -> unload(p)
            Stage.LOAD -> load(p)
            Stage.FETCH -> {
                tries = 0
                if (++fetches > FETCHES) {
                    // The cart will not be taken up (stuck, or somebody's in the way of it): he leaves it, and it goes once no one looks
                    if (carts()) Handcarts.leave(p)
                    stage = Stage.WAVE
                    return
                }
                stage = Stage.TAKE
                Handcarts.fetch(p)?.let { go(p, it, 0.3, exact = true) }
            }
            Stage.TAKE -> take(p)
            Stage.WAVE -> {
                p.act(Activity.WAVE)
                p.say("pedlar_pack", force = true, hearing = SPEAK, ticks = 60)
                wait = Activity.WAVE.ticks + 6
                stage = Stage.WALK_OUT
            }
            Stage.WALK_OUT -> out(p, level)
        }
    }

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean = Pedlars.counter(p.level() as ServerLevel, player, p) == true

    /** Sent away at once (the switch off, a command): he goes the way he came. */
    fun dismiss(p: Townsperson) {
        if (stage < Stage.PACK) leave(p)
    }

    // ---- Arriving ----

    /** Set down at the cart: the cart brought round and let go, he beside it, the goods shown as they are. */
    private fun settle(p: Townsperson, level: ServerLevel) {
        if (carts()) {
            if (hitch(p) == null) return retry(p)
        }
        tries = 0
        wanted = Pedlars.pieces(level, bell, day)
        shown = ArrayList(wanted)
        if (carts()) {
            Handcarts.load(p, shown)
            Handcarts.unhitch(p)
            Handcarts.side(p, p.position())?.let { p.snapTo(it.x + 0.5, it.y.toDouble(), it.z + 0.5, p.yRot, 0f) }
        }
        faceBed(p)
        Pedlars.openStall(level, bell, p)
        p.act(Activity.NONE)
        beatEnds = 0
        stage = Stage.MIND
    }

    /** One try more at the cart; none left and he goes (there is no room for his cart here today). */
    private fun retry(p: Townsperson) {
        wait = 40
        if (++tries > TRIES) {
            Pedlars.giveUp(this, p)
        }
    }

    /** "Sorry to keep you waiting! Setting up now." as he comes up to the green after half past nine. */
    private fun lateWord(p: Townsperson, level: ServerLevel) {
        if (!late || level.gameTime % 20L != 0L) return
        if (p.blockPosition().distSqr(bay) > 12.0 * 12.0) return
        p.say("pedlar_late", hearing = SPEAK, ticks = 70)
    }

    // ---- At the cart ----

    /** The cart made to show what the call really brought, a piece set down or lifted off at a time. */
    private fun unpack(p: Townsperson, level: ServerLevel, h: Double) {
        val called = Pedlars.called(level, bell, day)
        // The call is made at nine in the market's record; if its hours are behind he waits a little for it
        if (!called && h < PedlarRoster.SET_UP + 0.5) {
            p.act(Activity.ARRANGE)
            wait = 20
            return
        }
        wanted = Pedlars.pieces(level, bell, day)
        val extra = shown.indexOfLast { s -> wanted.none { it.item == s.item } }
        if (extra >= 0) {
            faceBed(p)
            p.act(Activity.PICK_UP)
            shown.removeAt(extra)
            if (carts()) Handcarts.load(p, shown)
            wait = Activity.PICK_UP.ticks + 6
            return
        }
        val missing = wanted.firstOrNull { w -> shown.none { it.item == w.item } }
        if (missing != null) {
            faceBed(p)
            p.act(Activity.PUT_DOWN)
            shown.add(missing)
            if (carts()) Handcarts.load(p, shown)
            wait = Activity.PUT_DOWN.ticks + 6
            return
        }
        p.act(Activity.NONE)
        beatEnds = 0
        stage = Stage.MIND
    }

    /** One more beat of minding the cart. */
    private fun mind(p: Townsperson, level: ServerLevel, now: Long) {
        greet(p, level, now)
        val tick = p.tickCount
        if (tick < beatEnds) {
            // Somebody else's gesture through him (a shopper's sale handed across) holds the next beat off
            if (p.activity != mine && p.activity != Activity.NONE) beatEnds = maxOf(beatEnds, tick + 20)
            return
        }
        val near = level.getNearestPlayer(p, CALL_TO)
        val shopper = shopper(level, p)
        val roll = p.random.nextInt(100)
        val next = when {
            shopper != null && roll < 60 -> Activity.HAGGLE
            near != null && now >= nextCall && roll < 35 -> Activity.CALL_OUT
            roll < 62 -> Activity.ARRANGE
            roll < 80 -> Activity.BROWSE
            else -> Activity.NONE
        }
        mine = next
        when (next) {
            Activity.HAGGLE -> {
                faceBed(p)
                p.lookAt(shopper!!)
                p.say("pedlar_haggle", hearing = SPEAK)
                beatEnds = tick + next.ticks + 10
            }
            Activity.CALL_OUT -> {
                p.say(Pedlars.cry(level, bell, day, p.random.nextInt(4)), hearing = SPEAK, ticks = 70)
                nextCall = now + CALL_GAP + p.random.nextInt(CALL_GAP.toInt())
                beatEnds = tick + next.ticks + 10
            }
            Activity.ARRANGE, Activity.BROWSE -> {
                faceBed(p)
                beatEnds = tick + 80 + p.random.nextInt(70)
            }
            else -> beatEnds = tick + 60 + p.random.nextInt(80)
        }
        p.act(next)
    }

    /** A word and a glance for a player who comes up to the cart (once in a while for each). */
    private fun greet(p: Townsperson, level: ServerLevel, now: Long) {
        greeting?.let { who ->
            if (now > greetUntil || !who.isAlive || who.level() !== level) greeting = null else p.lookAt(who)
        }
        if (now % 10L != 0L) return
        val player = level.getNearestPlayer(p, GREET_AT) as? ServerPlayer ?: return
        if (player.isSpectator || greeted[player.uuid]?.let { now - it < GREET_AGAIN } == true) return
        greeted[player.uuid] = now
        if (greeted.size > 32) greeted.entries.removeIf { now - it.value >= GREET_AGAIN }
        greeting = player
        greetUntil = now + 80
        p.act(Activity.WAVE)
        mine = Activity.WAVE
        beatEnds = p.tickCount + Activity.WAVE.ticks + 20
        p.say("pedlar_hello", hearing = SPEAK, ticks = 70)
    }

    /** A villager come to shop at the cart, if one stands near it. */
    private fun shopper(level: ServerLevel, p: Townsperson): Townsperson? {
        if (p.tickCount % 10 != 0) return null
        val box = p.boundingBox.inflate(SHOP_REACH, 1.0, SHOP_REACH)
        return level.getEntitiesOfClass(Townsperson::class.java, box) { it !== p && it.isAlive && it.director == null && !it.crowd }.firstOrNull()
    }

    // ---- Packing up and going ----

    /** Four o'clock: the stall comes down and the cart is emptied and loaded for the road. */
    private fun pack(p: Townsperson) {
        Pedlars.closeStall(bell)
        walker?.stop()
        walker = null
        mine = Activity.NONE
        wait = 0
        stage = Stage.PACK
    }

    private fun unload(p: Townsperson) {
        if (shown.isEmpty()) {
            wanted = Pedlars.pieces(p.level() as ServerLevel, bell, day, taken = true)
            stage = Stage.LOAD
            return
        }
        faceBed(p)
        p.act(Activity.PICK_UP)
        shown.removeAt(shown.size - 1)
        if (carts()) Handcarts.load(p, shown)
        wait = Activity.PICK_UP.ticks + 4
    }

    /** What the call took away, set on the cart a piece at a time. */
    private fun load(p: Townsperson) {
        if (shown.size < wanted.size) {
            faceBed(p)
            p.act(Activity.PUT_DOWN)
            shown.add(wanted[shown.size])
            if (carts()) Handcarts.load(p, shown)
            wait = Activity.PUT_DOWN.ticks + 6
            return
        }
        p.act(Activity.NONE)
        wait = 10
        stage = Stage.FETCH
    }

    /** Back to the cart, the shafts taken up. */
    private fun take(p: Townsperson) {
        if (!carts()) {
            stage = Stage.WAVE
            return
        }
        Handcarts.parked(p)?.let { p.face(it.yRot) }
        hitch(p)
        if (Handcarts.pulling(p)) {
            p.act(Handcarts.PULL)
            stage = Stage.WAVE
        } else if (++tries > 4) {
            stage = Stage.FETCH
        } else {
            wait = 10
        }
    }

    /** Not wanted at the green any more: if the cart stands parked he fetches it first, then goes. */
    private fun leave(p: Townsperson) {
        Pedlars.closeStall(bell)
        walker?.stop()
        walker = null
        wait = 0
        tries = 0
        outbound = false
        mine = Activity.NONE
        stage = if (carts() && Handcarts.parked(p) != null) Stage.FETCH else Stage.WALK_OUT
    }

    /** The walk out by the road end, leg by leg, until he is out of sight and the crowd's rule takes him. */
    private fun out(p: Townsperson, level: ServerLevel) {
        if (!outbound) {
            outbound = true
            if (carts() && !Handcarts.pulling(p)) hitch(p)
            // Give him time to be out of the green before anyone may see him go (the crowd's rule: out of sight, then gone)
            p.leaveAt = level.gameTime + LEAVE_AFTER
            p.act(Handcarts.PULL)
            go(p, exit, Trades.LADEN, near = 3.0)
            return
        }
        // At the road end and still in sight of someone: on, further out along the same road
        val to = Pedlars.beyond(level, exit, bay) ?: run {
            p.act(Activity.NONE)
            wait = 100
            return
        }
        if (++legs > LEGS) {
            p.act(Activity.NONE)
            wait = 200
            return
        }
        exit = to
        p.act(Handcarts.PULL)
        go(p, exit, Trades.LADEN, near = 3.0)
    }

    // ---- Small things ----

    private fun carts(): Boolean = Handcarts.enabled()

    // ---- Villages (V12a): the pedlar's own cart
    /** [Handcarts.hitch], the cart marked as a pedlar's: drawn with its striped hood, its pots and its lantern (client/cart's PedlarCartRenderer). */
    private fun hitch(p: Townsperson): Handcart? = Handcarts.hitch(p)?.also { it.pedlar = true }
    // ---- end Villages (V12a)

    private fun go(p: Townsperson, to: BlockPos, speed: Double, near: Double = 1.5, exact: Boolean = false) {
        goal = to
        goalSpeed = speed
        goalNear = near
        goalExact = exact
        walker = Walker(p).also { it.go(to, speed, near, exact) }
    }

    /** Turns to the middle of the cart's bed, to look at what he is arranging. */
    private fun faceBed(p: Townsperson) {
        val bed = Handcarts.bed(p) ?: return
        p.face(p.yawTo(bed))
        p.lookControl.setLookAt(bed.x, bed.y, bed.z)
    }

    private companion object {
        /** How far (blocks) he is heard calling out, greets a player and sees a shopper. */
        const val SPEAK = 32.0
        const val CALL_TO = 24.0
        const val GREET_AT = 7.0
        const val SHOP_REACH = 3.5

        /** Game ticks between his calls to the street, at least; a player is greeted again after [GREET_AGAIN]. */
        const val CALL_GAP = 240L
        const val GREET_AGAIN = 2400L

        /** Ticks from setting out until the crowd's rule may take him once nobody looks. */
        const val LEAVE_AFTER = 400L

        /** Tries at the cart before he gives up; failed walks in view before he is let go at the next unseen moment; legs further on. */
        const val TRIES = 8
        const val STUCK = 6
        const val FETCHES = 3
        const val LEGS = 6
    }
}
