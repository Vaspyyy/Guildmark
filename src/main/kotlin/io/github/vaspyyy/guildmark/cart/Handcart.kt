package io.github.vaspyyy.guildmark.cart

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.InterpolationHandler
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.SteppedInterpolationHandler
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A carter's handcart: two big spoked wheels on an axle, a plank bed with low sides and two shafts out front. A
 * townsperson ([owner]) pulls it along with the shafts in their hands, the bed trailing behind at the shafts'
 * length and swinging round after them on a turn, or lets it stand, tipped forward onto its shafts. What it
 * carries ([goods]) is heaped on the bed.
 *
 * A prop, not a vehicle: no one rides it, pushes it, breaks it or picks it up, nothing bumps into it, and it isn't
 * saved with the world ([Handcarts] brings one round again when a carter wants it). The server places it each tick
 * ([Handcarts] after everyone has moved); clients draw it a few ticks behind, exactly as they draw the townsfolk,
 * and turn its wheels by how far it went (client/cart).
 *
 * Open for one subclass, the Chapman's Cart ([PlayerCart]): the player's own, saved, pulled by a player and kept by the
 * trader's code and not by [Handcarts]. It answers [keep], [through] and [lost] for itself and leaves the rest as it is.
 */
open class Handcart(type: EntityType<out Handcart>, level: Level) : Entity(type, level) {

    // ---- What every client sees ----

    /** The entity id of whoever has it in hand, or -1 while it stands on its shafts. */
    val pullerId: Int get() = entityData.get(PULLER)

    /** Whether someone has the shafts in hand. */
    val held: Boolean get() = pullerId >= 0

    /** Degrees it leans over, its right side up: one wheel stands higher than the other. */
    val roll: Float get() = entityData.get(ROLL)

    /** What's heaped on place [slot] of the bed (see client/cart's HandcartMesh.SLOTS): empty where nothing is. */
    fun goods(slot: Int): ItemStack = entityData.get(GOODS[slot])

    // The handcart tale (living-world/stories/design.md 8.8): a family's cart with its near wheel off, a made figure's prop, never saved
    /** Whether its near (right) wheel is off: drawn leaning against the bed, the bed down on that corner. Set on the server. */
    var broken: Boolean
        get() = entityData.get(BROKEN)
        set(value) = entityData.set(BROKEN, value)

    /** What this client keeps to draw it (client/cart); null on the server. */
    @JvmField
    var look: Any? = null

    // ---- Kept by the server ----

    /** Whose cart it is: the townsperson it follows, held or not. Null for one nobody wants any more. */
    var owner: Townsperson? = null
        internal set

    /** Waiting where it stands for its owner to come and take up the shafts (see [Handcarts.hitch]). */
    var waiting = false
        internal set

    // Where each wheel stands (the ground's height under it), eased over steps and edges
    private var groundR = Double.NaN
    private var groundL = Double.NaN

    // Where the puller was last tick, to see how far they went (and whether they were snapped somewhere else)
    private var lastX = Double.NaN
    private var lastY = 0.0
    private var lastZ = 0.0

    // Ticks it has stood since it was let go, and ticks with no player near
    private var resting = 0
    private var lonely = 0

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(PULLER, -1)
        builder.define(ROLL, 0f)
        for (slot in GOODS) builder.define(slot, ItemStack.EMPTY)
        builder.define(BROKEN, false)
    }

    // Drawn the same few ticks behind the server as the townsfolk are, so the shafts stay in their hands
    override fun createInterpolationHandler(): InterpolationHandler = SteppedInterpolationHandler.create(this)

    override fun tick() {
        // Nothing of a mob's or an item's: the server places it (Handcarts), the client animates it (client/cart)
        if (level().isClientSide) clientTick?.invoke(this)
        else if (!keep()) discard()
    }

    /** Whether the game keeps this cart: the carters' registry knows it (anything else, a stray copy, goes). A [PlayerCart] is its own keeper. */
    protected open fun keep(): Boolean = Handcarts.owns(this)

    override fun onClientRemoval() {
        super.onClientRemoval()
        clientRemoved?.invoke(this)
    }

    override fun onRemovedFromLevel() {
        super.onRemovedFromLevel()
        if (!level().isClientSide) Handcarts.forget(this)
    }

    // ---- A prop: no riding, pushing, hitting or bumping ----

    override fun isPickable(): Boolean = false
    override fun isPushable(): Boolean = false
    override fun canBeCollidedWith(entity: Entity?): Boolean = false
    override fun canBeHitByProjectile(): Boolean = false
    override fun isAttackable(): Boolean = false
    override fun skipAttackInteraction(attacker: Entity): Boolean = true
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false
    override fun ignoreExplosion(explosion: Explosion): Boolean = true
    override fun isIgnoringBlockTriggers(): Boolean = true
    override fun isPushedByFluid(): Boolean = false
    override fun canUsePortal(allowPassengers: Boolean): Boolean = false
    override fun push(x: Double, y: Double, z: Double) {}
    override fun shouldBeSaved(): Boolean = false
    override fun readAdditionalSaveData(input: ValueInput) {}
    override fun addAdditionalSaveData(output: ValueOutput) {}

    // ---- The server's side of it ----

    /** One tick, after everyone has moved: follows its puller, or stands, and goes away once it isn't wanted. */
    internal fun step(level: ServerLevel) {
        val p = owner
        if (p == null) {
            // Nobody's: it goes once no one is looking
            if (!seen(level)) discard()
            return
        }
        if (!p.isAlive || p.isRemoved || p.level() !== level) {
            discard()
            return
        }
        when {
            // Someone sitting down or going to bed with it still in hand lets go of it
            held && (p.isSleeping || p.seat != null || p.isPassenger) -> letGo(level)
            held -> trail(level, p)
            waiting && reachable(p) -> take(p)
            waiting && !seen(level) && !seen(level, p) && place(level, p) -> take(p)
            else -> stand(level)
        }
    }

    /** [p] takes up the shafts: it follows them from now on. */
    internal fun take(p: Townsperson) {
        owner = p
        waiting = false
        hold(p)
    }

    /** [p], whoever they are, takes up the shafts: it follows them from now on. */
    internal fun hold(p: LivingEntity) {
        lonely = 0
        resting = 0
        lastX = p.x
        lastY = p.y
        lastZ = p.z
        entityData.set(PULLER, p.id)
    }

    /** Whether it may pass through a wall that no swing of the shafts clears for [p] (a carter's cart, a prop, does; a player's does not). */
    protected open fun through(p: LivingEntity): Boolean = true

    /** [p] was snapped somewhere the cart cannot be brought behind: a carter's cart is gone, a player's lets go. */
    protected open fun lost(level: ServerLevel, p: LivingEntity) {
        discard()
    }

    /** Let go: it stays where it stands and tips forward onto its shafts. */
    internal fun letGo(level: ServerLevel) {
        waiting = false
        resting = 0
        lastX = Double.NaN
        entityData.set(PULLER, -1)
        settle(level)
    }

    /** One tick standing: it settles onto its shafts, and goes once neither a player nor its carter has been near for a while. */
    private fun stand(level: ServerLevel) {
        resting++
        if (resting <= SETTLE_TICKS || (level.gameTime + id) % 20 == 0L) settle(level)
        if ((level.gameTime + id) % 20 == 0L) {
            // Its carter loading it or unloading it keeps it, seen or not
            val minded = owner?.let { it.distanceToSqr(this) < MINDED * MINDED } == true
            lonely = if (minded || level.getNearestPlayer(x, y, z, NEAR, false) != null) 0 else lonely + 20
            if (lonely >= LONELY) discard()
        }
    }

    /** Follows [p] another tick: the shafts in their hands, the bed at the shafts' length behind them. */
    internal fun trail(level: ServerLevel, p: LivingEntity) {
        val px = p.x
        val py = p.y
        val pz = p.z
        var moved = 0.0
        if (!lastX.isNaN()) {
            moved = hypot(px - lastX, pz - lastZ)
            // Snapped somewhere out of sight (a walk nobody would see walked): the cart goes with them
            if (moved > SNAP || abs(py - lastY) > SNAP) {
                if (place(level, p)) hold(p) else lost(level, p)
                return
            }
        }
        lastX = px
        lastY = py
        lastZ = pz
        // The way back from their hands to the axle: the bed swings round behind them, it doesn't turn on the spot
        var ux = x - px
        var uz = z - pz
        val len = hypot(ux, uz)
        if (len < 1.0e-4) {
            val a = Math.toRadians(p.yBodyRot.toDouble())
            ux = sin(a)
            uz = -cos(a)
        } else {
            ux /= len
            uz /= len
        }
        val reach = reach(py + HAND_UP - (y + AXLE_UP))
        val dist = reach + HAND_BACK
        // Into a wall that way: swung round them to where it's clear, the nearer way first (or through it, a prop)
        if (!clear(level, px + ux * dist, pz + uz * dist, ux, uz, y)) {
            var free = false
            search@ for (k in 1..SWINGS) for (side in SIDES) {
                val a = Math.toRadians(SWING * k * side)
                val vx = ux * cos(a) - uz * sin(a)
                val vz = ux * sin(a) + uz * cos(a)
                if (clear(level, px + vx * dist, pz + vz * dist, vx, vz, y)) {
                    ux = vx
                    uz = vz
                    free = true
                    break@search
                }
            }
            // Nowhere clear: it stands where it is (the one that may not go through)
            if (!free && !through(p)) return
        }
        var nx = px + ux * dist
        var nz = pz + uz * dist
        // No faster than they went, and a little: taken up from off to one side, it rolls round to them
        val mx = nx - x
        val mz = nz - z
        val m = hypot(mx, mz)
        val cap = moved + CATCH_UP
        if (m > cap) {
            nx = x + mx * cap / m
            nz = z + mz * cap / m
        }
        val tx = px - nx
        val tz = pz - nz
        val flat = hypot(tx, tz)
        val yaw = if (flat > 1.0e-3) Math.toDegrees(atan2(-tx, tz)).toFloat() else yRot
        wheels(level, nx, nz, yaw, ease = true)
        val ny = (groundR + groundL) / 2
        // The bed tips up or down to bring the shafts to their hands
        val rise = Math.toDegrees(atan2(py + HAND_UP - (ny + AXLE_UP), max(flat - HAND_BACK, 0.1)))
        setPos(nx, ny, nz)
        yRot = yaw
        xRot = (SHAFT_RISE - rise).toFloat()
        lean()
    }

    /**
     * Puts it fresh behind [p], ready to take up: straight behind them if there's room, otherwise as near that as
     * there is. False (and it isn't moved) if there's nowhere for it.
     */
    internal fun place(level: ServerLevel, p: LivingEntity): Boolean {
        val body = p.yBodyRot.toDouble()
        val dist = reach(HAND_UP - AXLE_UP) + HAND_BACK
        for (k in 0..6) for (side in if (k == 0) ONE else SIDES) {
            // (sin, -cos) of their yaw is straight back from them
            val a = Math.toRadians(body + 30.0 * k * side)
            val ux = sin(a)
            val uz = -cos(a)
            val nx = p.x + ux * dist
            val nz = p.z + uz * dist
            if (!clear(level, nx, nz, ux, uz, p.y)) continue
            val yaw = Math.toDegrees(atan2(ux, -uz)).toFloat()
            val (fx, fz) = forward(yaw)
            val gr = wheel(level, nx - fz * HALF_TRACK, nz + fx * HALF_TRACK, fx, fz, p.y)
            val gl = wheel(level, nx + fz * HALF_TRACK, nz - fx * HALF_TRACK, fx, fz, p.y)
            if (abs(gr - p.y) > STEP || abs(gl - p.y) > STEP) continue
            groundR = gr
            groundL = gl
            val ny = (gr + gl) / 2
            val rise = Math.toDegrees(atan2(p.y + HAND_UP - (ny + AXLE_UP), dist - HAND_BACK))
            setPos(nx, ny, nz)
            yRot = yaw
            yRotO = yaw
            xRot = (SHAFT_RISE - rise).toFloat()
            lean()
            return true
        }
        return false
    }

    /** Settles it standing: each wheel on the ground under it, tipped forward until the shafts' tips touch down. */
    internal fun settle(level: ServerLevel) {
        wheels(level, x, z, yRot, ease = true)
        val ny = (groundR + groundL) / 2
        val (fx, fz) = forward(yRot)
        val tip = probe(level, x + fx * TIP_REACH, z + fz * TIP_REACH, ny).let { if (it.isNaN()) ny else it }
        // How far the tips come down from the axle to rest on the ground
        val drop = (ny + AXLE_UP - tip) / TIP_LENGTH
        xRot = Math.toDegrees(asin(drop.coerceIn(-0.9, 0.98)) + TIP_ANGLE).toFloat().coerceIn(-15f, 60f)
        setPos(x, ny, z)
        lean()
    }

    /** Sets each wheel on the ground under it at ([cx], [cz]) facing [yaw]: [ease]d up a step or over an edge. */
    private fun wheels(level: ServerLevel, cx: Double, cz: Double, yaw: Float, ease: Boolean) {
        val (fx, fz) = forward(yaw)
        // The cart's right is (-fz, fx)
        val gr = wheel(level, cx - fz * HALF_TRACK, cz + fx * HALF_TRACK, fx, fz, if (groundR.isNaN()) y else groundR)
        val gl = wheel(level, cx + fz * HALF_TRACK, cz - fx * HALF_TRACK, fx, fz, if (groundL.isNaN()) y else groundL)
        groundR = if (groundR.isNaN() || !ease) gr else approach(groundR, gr)
        groundL = if (groundL.isNaN() || !ease) gl else approach(groundL, gl)
    }

    /** Leans it over by the wheels' heights. */
    private fun lean() {
        val degrees = Math.toDegrees(atan2(groundR - groundL, 2 * HALF_TRACK))
        // In half degrees: a wheel a hair higher doesn't need telling every client
        entityData.set(ROLL, (Math.round(degrees * 2) / 2.0).toFloat())
    }

    /** Whether it would stand clear of walls with its axle at ([nx], [nz]) and its shafts toward -([ux], [uz]). */
    internal fun clear(level: ServerLevel, nx: Double, nz: Double, ux: Double, uz: Double, ref: Double): Boolean {
        // The cart's right is (uz, -ux) when it faces -u
        return !probe(level, nx + uz * HALF_TRACK, nz - ux * HALF_TRACK, ref).isNaN() &&
            !probe(level, nx - uz * HALF_TRACK, nz + ux * HALF_TRACK, ref).isNaN() &&
            !probe(level, nx + ux * TAIL, nz + uz * TAIL, ref).isNaN() &&
            !probe(level, nx - ux * NOSE, nz - uz * NOSE, ref).isNaN()
    }

    /** Whether [p] can take it up from where they are: at its shafts, or just ahead of it (it rolls up to them). */
    internal fun reachable(p: LivingEntity): Boolean = within(p, TAKE) || ahead(p, ROLL_UP)

    /** Whether [p] stands within [d] of where they'd hold the shafts. */
    private fun within(p: LivingEntity, d: Double): Boolean {
        val g = grip()
        return hypot(p.x - g.x, p.z - g.z) <= d && abs(p.y - y) <= STEP
    }

    /** Whether [p] is within [d] ahead of it, near enough straight ahead for it to roll up to them. */
    private fun ahead(p: LivingEntity, d: Double): Boolean {
        val dx = p.x - x
        val dz = p.z - z
        val dist = hypot(dx, dz)
        if (dist > d + GRIP_FLAT || abs(p.y - y) > STEP || dist < 1.0e-3) return false
        val (fx, fz) = forward(yRot)
        return (dx * fx + dz * fz) / dist >= AHEAD_COS
    }

    /** Where someone stands to take up the shafts: their hands on the grips, as when pulling on the flat. */
    fun grip(): Vec3 {
        val (fx, fz) = forward(yRot)
        return Vec3(x + fx * GRIP_FLAT, y, z + fz * GRIP_FLAT)
    }

    /** Whether a player is near enough to see it (or [p], if given) happen. */
    internal fun seen(level: ServerLevel, p: Entity? = null): Boolean {
        val at = p ?: this
        return level.getNearestPlayer(at.x, at.y, at.z, SEEN, false) != null
    }

    /** Heaps [goods] (in order) on the bed: what's already there stays put, the rest goes in the free places in turn. */
    internal fun heap(goods: List<ItemStack>) {
        val want = goods.filter { !it.isEmpty }.take(SLOTS)
        val now = Array(SLOTS) { goods(it) }
        val placed = arrayOfNulls<ItemStack>(SLOTS)
        // The longest run the old and new loads share, in order, keeps its places (the rest are lifted off)
        val full = (0 until SLOTS).filter { !now[it].isEmpty }
        val n = full.size
        val m = want.size
        val common = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            common[i][j] = if (same(now[full[i]], want[j])) common[i + 1][j + 1] + 1 else max(common[i + 1][j], common[i][j + 1])
        }
        val kept = BooleanArray(m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                same(now[full[i]], want[j]) -> {
                    placed[full[i]] = now[full[i]]
                    kept[j] = true
                    i++
                    j++
                }
                common[i + 1][j] >= common[i][j + 1] -> i++
                else -> j++
            }
        }
        var free = 0
        for (k in 0 until m) {
            if (kept[k]) continue
            while (free < SLOTS && placed[free] != null) free++
            if (free < SLOTS) placed[free] = want[k].copyWithCount(1)
        }
        for (slot in 0 until SLOTS) {
            val stack = placed[slot] ?: ItemStack.EMPTY
            if (!ItemStack.matches(stack, now[slot])) entityData.set(GOODS[slot], stack)
        }
    }

    /** Lays [heap] on the bed place by place (empty where nothing goes): each where it's given, nothing moved round. */
    internal fun lay(heap: Array<ItemStack>) {
        for (slot in 0 until SLOTS) {
            val stack = heap.getOrNull(slot)?.takeUnless { it.isEmpty }?.copyWithCount(1) ?: ItemStack.EMPTY
            if (!ItemStack.matches(stack, goods(slot))) entityData.set(GOODS[slot], stack)
        }
    }

    companion object {
        /** Places on the bed. */
        const val SLOTS = 8

        // The cart's measures (art/scripts/handcart.py), in blocks: the axle stands a wheel's radius over the ground,
        // the wheels stand out from the middle, the hands hold the shafts ahead of the axle and a little above it
        /** The wheels' radius: the axle stands this high over the ground. */
        const val AXLE_UP = 10.0 / 16
        /** Each wheel's middle, out from the cart's middle. */
        const val HALF_TRACK = 9.0 / 16
        private const val GRIP_AHEAD = 24.5 / 16
        private const val GRIP_UP = 3.0 / 16
        // The shafts' tips, their lower edge
        private const val TIP_AHEAD = 26.0 / 16
        private const val TIP_UP = 2.25 / 16
        // The bed's ends, from the axle: the tail board behind, the front board's corner ahead
        private const val TAIL = 0.68
        private const val NOSE = 0.9

        // Where a puller's hands are as they pull (the clip pull_cart, art/scripts/handcart_anims.py)
        /** Blocks behind their feet. */
        const val HAND_BACK = 2.5 / 16
        /** Blocks over the ground. */
        const val HAND_UP = 13.0 / 16

        private val SHAFT = hypot(GRIP_AHEAD, GRIP_UP)
        /** Degrees the shafts rise from the bed toward the hands. */
        val SHAFT_RISE: Double = Math.toDegrees(atan2(GRIP_UP, GRIP_AHEAD))
        private val TIP_LENGTH = hypot(TIP_AHEAD, TIP_UP)
        private val TIP_ANGLE = atan2(TIP_UP, TIP_AHEAD)
        /** How far ahead of the axle the shafts' tips touch down when it's let go on the flat. */
        private const val TIP_REACH = 1.5

        /** From the axle to the puller's feet, pulling on the flat. */
        val GRIP_FLAT: Double = sqrt(SHAFT * SHAFT - (HAND_UP - AXLE_UP) * (HAND_UP - AXLE_UP)) + HAND_BACK

        // Taking up the shafts: right there, or a short roll up to someone standing ahead of it
        private const val TAKE = 1.6
        private const val ROLL_UP = 4.0
        private val AHEAD_COS = cos(Math.toRadians(60.0))
        /** Blocks a cart rolls each tick beyond what its puller went, catching up to them. */
        private const val CATCH_UP = 0.15
        /** A puller further than this in one tick was snapped somewhere else. */
        private const val SNAP = 3.0
        /** Up or down this far from its puller and it can't stand behind them. */
        private const val STEP = 1.5

        // Swinging clear of a wall: this many degrees at a time, this many times each way
        private const val SWING = 12.0
        private const val SWINGS = 6
        private val ONE = intArrayOf(1)
        private val SIDES = intArrayOf(1, -1)

        // The ground under a wheel: what its rim touches, ahead and behind, each spot as far below the axle as the rim
        // is there; up a step (a half or whole block) it eases up so much a tick, over an edge it drops a little faster
        private val PATCH = doubleArrayOf(-0.55, -0.3, 0.0, 0.3, 0.55)
        private val RIM = DoubleArray(PATCH.size) { AXLE_UP - sqrt(AXLE_UP * AXLE_UP - PATCH[it] * PATCH[it]) }
        private const val RISE = 0.25
        private const val FALL = 0.4
        private const val CLIMB = 1.25
        private const val DROP = 3.0

        /** Ticks it settles every tick after it's let go (then once a second). */
        private const val SETTLE_TICKS = 10
        /** Blocks a player can be off and still see it. */
        private const val SEEN = 80.0
        /** Blocks a player must be within for a standing cart to stay; and how long it waits with none (ticks). */
        private const val NEAR = 48.0
        private const val LONELY = 600
        /** Blocks its carter must be within for a standing cart to stay with no player near. */
        private const val MINDED = 24.0

        private val PULLER: EntityDataAccessor<Int> = SynchedEntityData.defineId(Handcart::class.java, EntityDataSerializers.INT)
        private val ROLL: EntityDataAccessor<Float> = SynchedEntityData.defineId(Handcart::class.java, EntityDataSerializers.FLOAT)
        private val GOODS: Array<EntityDataAccessor<ItemStack>> =
            Array(SLOTS) { SynchedEntityData.defineId(Handcart::class.java, EntityDataSerializers.ITEM_STACK) }
        private val BROKEN: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(Handcart::class.java, EntityDataSerializers.BOOLEAN)

        /** Run on the client each tick for each cart (client/cart: its shafts, its wheels, its load as drawn). */
        @JvmStatic
        var clientTick: ((Handcart) -> Unit)? = null

        /** Run on the client when a cart goes. */
        @JvmStatic
        var clientRemoved: ((Handcart) -> Unit)? = null

        private val cursor = BlockPos.MutableBlockPos()

        /** Which way a cart facing [yaw] heads, on the ground: (x, z). */
        fun forward(yaw: Float): Pair<Double, Double> {
            val a = Math.toRadians(yaw.toDouble())
            return Pair(-sin(a), cos(a))
        }

        /** How far the shafts reach out level from the axle when the hands are [dh] blocks above it. */
        private fun reach(dh: Double): Double = sqrt(max(SHAFT * SHAFT - dh * dh, SHAFT * SHAFT * 0.16))

        private fun approach(from: Double, to: Double): Double =
            if (to > from) from + minOf(to - from, RISE) else from - minOf(from - to, FALL)

        private fun same(a: ItemStack, b: ItemStack): Boolean = ItemStack.isSameItemSameComponents(a, b)

        /**
         * The ground a wheel stands on at ([cx], [cz]), rolling along ([fx], [fz]): as high as its rim lets it, so it
         * starts up a step before it gets there, as a wheel does.
         */
        private fun wheel(level: ServerLevel, cx: Double, cz: Double, fx: Double, fz: Double, ref: Double): Double {
            var best = Double.NEGATIVE_INFINITY
            for (i in PATCH.indices) {
                val g = probe(level, cx + fx * PATCH[i], cz + fz * PATCH[i], ref) - RIM[i]
                if (!g.isNaN() && g > best) best = g
            }
            return if (best == Double.NEGATIVE_INFINITY) ref else best
        }

        /**
         * The height of the ground at ([x], [z]) near [ref]: the top of whatever stands there (a slab, a stair's step,
         * a path) at most a step up, else down to a drop's depth. NaN where something taller than a step stands (a
         * wall, a fence post) or the chunk isn't loaded; [ref] less the drop where there's nothing to stand on.
         */
        private fun probe(level: ServerLevel, x: Double, z: Double, ref: Double): Double {
            val bx = Mth.floor(x)
            val bz = Mth.floor(z)
            val top = Mth.floor(ref + CLIMB)
            val bottom = Mth.floor(ref - DROP)
            cursor.set(bx, top, bz)
            if (!level.isLoaded(cursor)) return Double.NaN
            val fx = x - bx
            val fz = z - bz
            for (by in top downTo bottom) {
                cursor.setY(by)
                val state = level.getBlockState(cursor)
                if (state.isAir) continue
                val shape = state.getCollisionShape(level, cursor)
                if (shape.isEmpty) continue
                // A shape's top over this very spot: across the y axis the first is z, the second x
                val h = shape.max(Direction.Axis.Y, fz, fx)
                if (h == Double.NEGATIVE_INFINITY) continue
                val g = by + h
                return if (g > ref + CLIMB) Double.NaN else g
            }
            return ref - DROP
        }
    }
}
