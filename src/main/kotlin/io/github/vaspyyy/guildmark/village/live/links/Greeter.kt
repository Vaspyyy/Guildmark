package io.github.vaspyyy.guildmark.village.live.links

import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.market.Pedlar
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The folk of the village [key] called out to its road end ([Greeters.call]) to meet a cart or a wagon, or to see one off, standing
 * about [at] and facing the road at [road]: what they wait on is [watch]'s to say. [quiet] when the carters are there and have the
 * words (the folk only wave and count); otherwise the first grown-up among them says them.
 */
class Gathering internal constructor(
    val key: Long, val at: BlockPos, val road: Vec3, val why: GreetWhy, internal val watch: Watch, internal val quiet: Boolean, val started: Long,
) {
    internal val folk = ArrayList<Greeter>()
    internal var greeted = false
    internal var counted = false

    /** Everyone has gone back to their day (or been taken off it by something else: a customer, a fright, gone from the world). */
    val done: Boolean get() = folk.all { it.gone || it.p.isRemoved || it.p.director !== it }

    /** Everyone back to their day at once. */
    fun release() {
        for (g in folk) g.release()
    }
}

/**
 * One of a [Gathering]: walks out to [spot], waits facing the road (a look down it now and then), and as the wagon comes in sight
 * waves it in (the first grown-up: "The wagon's in sight!" or "Here it comes!", heard 32 blocks off), cheers or claps; when it is in,
 * counts the sacks ("Count them twice.", "That's the lot."), when it sets out waves it off, and when it never comes looks down the
 * road, sighs ("No wagon again.") and shrugs. Then back to their day. [delay] staggers them, so they do not all move as one.
 *
 * They go at once (hurt, set on, nobody near for [SendOff.AWAY] ticks, the switch off, or [MOST] ticks gone) and nothing waits on them.
 */
class Greeter internal constructor(
    private val gathering: Gathering, val p: Townsperson, private val spot: BlockPos, private val speaks: Boolean, private val delay: Int,
) : Director {
    private enum class Stage { GO, WAIT, SEEN, DONE }

    private val moves = Moves(p)
    private var stage = Stage.GO
    private var away = 0L
    var gone = false
        private set

    override fun tick(p: Townsperson) {
        if (gone) return
        val level = p.level() as? ServerLevel ?: return
        if (!p.isAlive || p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE || moves.stuck) {
            release()
            return
        }
        val now = level.gameTime
        if (now % 20L == 0L && !still(level, now)) {
            release()
            return
        }
        if (moves.tick()) return
        when (stage) {
            Stage.GO -> {
                stage = Stage.WAIT
                moves.go(spot, Jobs.WALK, near = 1.0)
            }
            Stage.WAIT, Stage.SEEN -> wait(level)
            Stage.DONE -> release()
        }
    }

    /** A nod for whoever speaks to them: they are watching the road. */
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        p.lookAt(player)
        return true
    }

    /** Whether they stay out: not too long, somebody near, and the wagons (or the pedlar) still on. */
    private fun still(level: ServerLevel, now: Long): Boolean {
        if (now - gathering.started > MOST) return false
        if (gathering.why == GreetWhy.WAGON && !VillageLinks.enabled()) return false
        if (gathering.why == GreetWhy.PEDLAR && !VillageMarkets.pedlars()) return false
        if (SendOff.near(level, gathering.at)) {
            away = 0L
            return true
        }
        if (away == 0L) away = now
        return now - away <= SendOff.AWAY
    }

    private fun wait(level: ServerLevel) {
        val what = gathering.watch.what(level)
        val at = gathering.watch.wagon(level) ?: gathering.road
        when (what) {
            What.COMING -> idle(at, waving = false)
            What.SIGHT -> {
                if (stage == Stage.WAIT) {
                    stage = Stage.SEEN
                    greet(at, seen = true)
                } else {
                    idle(at, waving = true)
                }
            }
            What.OFF -> {
                if (delay > 0) moves.beats += Beat(Activity.NONE, delay, at)
                moves.beats += Beat(if (p.random.nextBoolean()) Activity.WAVE else Activity.CHEER, Activity.WAVE.ticks, at)
                moves.beats += Beat(Activity.WAVE, Activity.WAVE.ticks, at)
                stage = Stage.DONE
            }
            What.IN -> {
                // Out of sight on the road, the cheer is for its coming in
                if (stage == Stage.WAIT) greet(at, seen = false)
                count()
                stage = Stage.DONE
            }
            What.LATE -> {
                if (delay > 0) moves.beats += Beat(Activity.NONE, delay, at)
                moves.beats += Beat(Activity.LOOK_ABOUT, Activity.LOOK_ABOUT.ticks, at)
                val line = if (speaks && !gathering.quiet) "cart_late" else null
                moves.beats += if (line != null || p.random.nextBoolean()) Beat(Activity.SIGH, Activity.SIGH.ticks, at, line) else Beat(Activity.SHRUG, Activity.SHRUG.ticks, at)
                stage = Stage.DONE
            }
            What.NONE -> {
                moves.beats += Beat(Activity.SHRUG, Activity.SHRUG.ticks, at)
                stage = Stage.DONE
            }
        }
    }

    /** Facing the road while it is not here yet: a look down it now and then (once it is in sight, a wave now and then). */
    private fun idle(at: Vec3, waving: Boolean) {
        SendOff.face(p, at)
        val roll = p.random.nextInt(if (waving) 4 else 8)
        if (roll == 0) {
            moves.beats += Beat(if (waving) Activity.WAVE else Activity.LOOK_ABOUT, if (waving) Activity.WAVE.ticks else Activity.LOOK_ABOUT.ticks, at)
        } else {
            p.act(Activity.NONE)
            moves.wait = 20
        }
    }

    /** Waved in: the first grown-up calls it out, the rest cheer, clap or wave, each in their turn. */
    private fun greet(at: Vec3, seen: Boolean) {
        if (delay > 0) moves.beats += Beat(Activity.NONE, delay, at)
        if (speaks && !gathering.greeted) {
            gathering.greeted = true
            val line = if (gathering.why == GreetWhy.WAGON && seen) "cart_greet_2" else "cart_greet_1"
            moves.beats += Beat(Activity.WAVE, Activity.WAVE.ticks, at, line)
            return
        }
        moves.beats += when (p.random.nextInt(3)) {
            0 -> Beat(Activity.CHEER, Activity.CHEER.ticks, at)
            1 -> Beat(Activity.CLAP, Activity.CLAP.ticks, at)
            else -> Beat(Activity.WAVE, Activity.WAVE.ticks, at)
        }
    }

    /** The sacks counted where they are heaped (the cart at the road end), a nod or a clap, and back. */
    private fun count() {
        val cart = Vec3.atCenterOf(gathering.at)
        val words = speaks && !gathering.quiet && !gathering.counted
        if (words) gathering.counted = true
        moves.beats += Beat(Activity.COUNT, Activity.COUNT.ticks, cart, if (words) "cart_count_2" else null)
        moves.beats += Beat(Activity.COUNT, Activity.COUNT.ticks, cart)
        moves.beats += if (words) Beat(Activity.NOD, Activity.NOD.ticks, cart, "cart_count_1")
        else if (p.random.nextBoolean()) Beat(Activity.NOD, Activity.NOD.ticks, cart) else Beat(Activity.CLAP, Activity.CLAP.ticks, cart)
    }

    /** Back to their day. */
    internal fun release() {
        if (gone) return
        gone = true
        moves.stop()
        p.carried = ItemStack.EMPTY
        if (p.director === this) p.director = null
        p.act(Activity.NONE)
        p.navigation.stop()
        p.routine.interrupt()
    }

    private companion object {
        /** The longest anyone stays out (game ticks). */
        const val MOST = 3600L
    }
}

/**
 * The pedlar coming in at the road end [at] (called at [since]): in sight once their body is within [SEEN] blocks of it, gone by
 * [PASSED] ticks later; nothing by [WAIT] ticks and the folk go back.
 */
class PedlarWatch(private val at: BlockPos, private val since: Long) : Watch {
    private var sighted = Long.MIN_VALUE
    private var looked = Long.MIN_VALUE
    private var body: Townsperson? = null

    override fun what(level: ServerLevel): What {
        val now = level.gameTime
        if (sighted != Long.MIN_VALUE) return if (now - sighted > PASSED) What.OFF else What.SIGHT
        if (now - since > WAIT) return What.NONE
        if (now - looked >= 10L) {
            looked = now
            body = level.getEntitiesOfClass(Townsperson::class.java, AABB(at).inflate(SEEN, 6.0, SEEN)) { it.isAlive && it.director is Pedlar }.firstOrNull()
            if (body != null) sighted = now
        }
        return if (sighted != Long.MIN_VALUE) What.SIGHT else What.COMING
    }

    override fun wagon(level: ServerLevel): Vec3? = body?.takeIf { it.isAlive }?.position()?.add(0.0, 1.2, 0.0)

    private companion object {
        const val SEEN = 20.0
        const val PASSED = 160L
        const val WAIT = 1800L
    }
}
