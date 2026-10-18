package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.cart.Handcarts
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * Their handcart (see [Handcarts]) taken up and pulled to [target], and let go of there: taken up where it stands
 * if they're at its shafts, walked to first if it's standing by in someone's sight, or brought round if they've
 * none. [goods] is what's heaped on it, told to the cart each time it's taken up (a fresh one comes empty). The
 * pulling move plays all the while it's in hand, walking or standing; the way keeps to the streets (no doors opened
 * on it); and whatever stops them, they let go. Already standing within a few blocks of [target], it isn't moved.
 * [failed] if there was no cart to be had (carts are off, there's no room for one) or it went from their hands.
 */
class Pull(p: Townsperson, private val target: BlockPos, private val goods: List<ItemStack>) : Task(p) {
    private enum class Stage { FETCH, TAKE, PULL, PARK, DONE }

    private val walker = Walker(p)
    private var stage = Stage.TAKE
    private var wait = 0
    private var tries = 0

    /** No cart was to be had, or it went: nothing more was pulled. */
    var failed = false
        private set

    override fun start() {
        if (standing(p, target)) {
            stage = Stage.DONE
            return
        }
        hitch()
    }

    /** The shafts taken up, or the way to them while the cart waits where it stands. */
    private fun hitch() {
        if (Handcarts.hitch(p) == null) return fail()
        if (Handcarts.pulling(p)) return pull()
        val at = Handcarts.fetch(p) ?: return fail()
        stage = Stage.FETCH
        walker.go(at, Jobs.WALK, exact = true)
    }

    private fun pull() {
        Handcarts.load(p, goods)
        stage = Stage.PULL
        p.navigation.setCanOpenDoors(false)
        p.act(Handcarts.PULL)
        walker.go(target, Trades.LADEN, near = NEAR)
    }

    private fun fail() {
        failed = true
        stage = Stage.DONE
    }

    override fun run(): Boolean {
        when (stage) {
            Stage.FETCH -> {
                // Taken up the moment they reach it
                if (Handcarts.pulling(p)) {
                    walker.stop()
                    pull()
                } else if (walker.tick() != Walker.State.WALKING) {
                    walker.stop()
                    stage = Stage.TAKE
                }
            }
            Stage.TAKE -> {
                if (wait > 0) {
                    wait--
                    return true
                }
                // At the shafts: facing the way it points, and up they come
                Handcarts.parked(p)?.let { p.face(it.yRot) }
                if (Handcarts.hitch(p) == null) fail()
                else if (Handcarts.pulling(p)) pull()
                else if (++tries > TRIES) {
                    Handcarts.unhitch(p)
                    fail()
                } else wait = 10
            }
            Stage.PULL -> {
                if (!Handcarts.pulling(p)) {
                    // Gone from their hands: carts switched off, or it was let go of
                    walker.stop()
                    p.navigation.setCanOpenDoors(true)
                    if (p.activity == Handcarts.PULL) p.act(Activity.NONE)
                    fail()
                    return false
                }
                if (p.activity != Handcarts.PULL) p.act(Handcarts.PULL)
                if (walker.tick() != Walker.State.WALKING) park()
            }
            Stage.PARK -> {
                p.navigation.stop()
                if (--wait <= 0) stage = Stage.DONE
            }
            Stage.DONE -> return false
        }
        return stage != Stage.DONE
    }

    /** Let go of where they stopped: the shafts come down, and a moment's breath. */
    private fun park() {
        walker.stop()
        p.navigation.setCanOpenDoors(true)
        Handcarts.unhitch(p)
        p.act(Activity.NONE)
        stage = Stage.PARK
        wait = SETTLE
    }

    override fun stop() {
        walker.stop()
        p.navigation.setCanOpenDoors(true)
        // Whatever stops them, they let go: it stays where it stands
        Handcarts.unhitch(p)
        if (p.activity == Handcarts.PULL) p.act(Activity.NONE)
    }

    companion object {
        /** Standing this near where it's wanted, it isn't moved. */
        private const val THERE = 3.0
        /** How near the stop they pull up. */
        private const val NEAR = 1.0
        /** Goes at taking up the shafts before giving up on it. */
        private const val TRIES = 4
        /** Ticks the shafts take to come down when it's let go. */
        private const val SETTLE = 20

        /** Whether [p]'s cart stands near enough [target] already that a [Pull] there leaves it where it is. */
        fun standing(p: Townsperson, target: BlockPos): Boolean {
            val parked = Handcarts.parked(p) ?: return false
            return parked.position().distanceToSqr(Vec3.atBottomCenterOf(target)) < THERE * THERE
        }
    }
}

/**
 * At their parked cart's side, facing its bed: [piece] heaped on it from their hands (put down, and gone from
 * them), or with no piece the last thing on it lifted off into their hands as [taken]. [goods] is what's on it,
 * kept in step. They stand on the side nearer [from] (the door they come and go by), walking there with [overlay]
 * playing (the sack on their shoulder). [missed] if their cart wasn't there to load or unload, or there was
 * nothing on it to lift off.
 */
class Heap(
    p: Townsperson, private val goods: MutableList<ItemStack>, private val piece: ItemStack?, private val taken: ItemStack = ItemStack.EMPTY,
    private val from: Vec3? = null, private val overlay: Activity = Activity.NONE,
) : Task(p) {
    private val walker = Walker(p)
    private val move = if (piece != null) Activity.PUT_DOWN else Activity.PICK_UP
    private val swap = if (piece != null) PUT_AT else TAKE_AT

    // Ticks into putting it down or lifting it off; -1 on the way to the cart's side
    private var t = -1

    /** The cart wasn't there to load or unload (or had nothing on it to lift off). */
    var missed = false
        private set

    /** The piece is on the cart, or in their hands. */
    val done: Boolean get() = t >= swap

    override fun start() {
        val side = Handcarts.side(p, from)
        if (side == null || piece == null && goods.isEmpty()) {
            missed = true
            return
        }
        p.act(overlay)
        walker.go(side, Trades.LADEN, exact = true)
    }

    override fun run(): Boolean {
        if (missed) return false
        if (t < 0) {
            if (overlay != Activity.NONE && p.activity != overlay && (p.activity.loop || p.actDone)) p.act(overlay)
            when (walker.tick()) {
                Walker.State.WALKING -> return true
                Walker.State.FAILED -> {
                    missed = true
                    return false
                }
                Walker.State.ARRIVED -> {
                    walker.stop()
                    t = 0
                    p.act(move)
                }
            }
        }
        val bed = Handcarts.bed(p)
        if (bed != null) {
            p.face(p.yawTo(bed))
            p.lookAt(bed)
        }
        t++
        if (t == swap) {
            if (piece != null) {
                goods.add(piece.copy())
                p.carried = ItemStack.EMPTY
            } else if (goods.isNotEmpty()) {
                goods.removeAt(goods.size - 1)
                p.carried = taken.copy()
            }
            Handcarts.load(p, goods)
        }
        return t < move.ticks + 8
    }

    override fun stop() {
        walker.stop()
        if (overlay != Activity.NONE && p.activity == overlay) p.act(Activity.NONE)
    }

    private companion object {
        /** The ticks of the moves the piece leaves their hands (as put_down sets it down) or comes into them (as pick_up lifts it). */
        const val PUT_AT = 11
        const val TAKE_AT = 8
    }
}
