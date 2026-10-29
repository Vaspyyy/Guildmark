package io.github.vaspyyy.guildmark.trade.hunt

import io.github.vaspyyy.guildmark.caravan.Waylayers
import io.github.vaspyyy.guildmark.companion.CompanionEntity
import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.util.LandRandomPos
import net.minecraft.world.entity.monster.illager.Pillager
import net.minecraft.world.entity.raid.Raid
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mob walked by hand at a pace of its own: the speed it is asked for is a number of blocks a second, and the modifier it walks at is steered
 * towards that from how far it has really gone in the last half second, so a walk looks like a walk whatever its kind's speed is and a blocked
 * one is not made to run. The Hunt's outlaws walk so, and the hired guard keeps step with the trader so.
 */
internal class Stride(private val mob: Mob) {
    /** The speed modifier it walks at, and the blocks a second it was last seen to make. */
    var mod = 0.7
        private set
    var measured = 0.0
        private set

    private var markAt: Vec3 = mob.position()
    private var markTick = Long.MIN_VALUE
    private var goal: Vec3? = null
    private var repathAt = 0L

    /** The modifier that should make about [bps] blocks a second, going by how a mob's walk is worked out (its speed times its modifier, squared, and the ground's drag). */
    private fun guess(bps: Double): Double = sqrt(bps / WALK_FACTOR) / mob.getAttributeValue(Attributes.MOVEMENT_SPEED).coerceAtLeast(0.05)

    /** Steers [mod] towards a walk of [bps] blocks a second: looked at every half second. */
    fun steer(bps: Double, now: Long) {
        if (markTick == Long.MIN_VALUE) {
            markTick = now
            markAt = mob.position()
            mod = guess(bps)
            return
        }
        val dt = now - markTick
        if (dt < 10) return
        val here = mob.position()
        measured = hypot(here.x - markAt.x, here.z - markAt.z) / (dt / 20.0)
        markAt = here
        markTick = now
        // Standing or held up: start from the guess again; moving: nudge by how far off the pace it is (the speed goes with the square of the modifier)
        mod = if (measured < 0.5) guess(bps) else (mod * (bps / measured).pow(0.5).coerceIn(0.85, 1.2)).coerceIn(0.2, 2.0)
    }

    /** Walks towards [to] at about [bps] blocks a second, looking for the way again every [every] ticks (or at once if [to] has moved on more than [moved] blocks). */
    fun go(to: Vec3, bps: Double, now: Long, every: Int = 10, moved: Double = 3.0) {
        steer(bps, now)
        val last = goal
        if (last == null || now >= repathAt || last.distanceToSqr(to) > moved * moved) {
            goal = to
            repathAt = now + every
            val aim = within(to)
            mob.navigation.moveTo(aim.x, aim.y, aim.z, mod)
        } else {
            mob.navigation.setSpeedModifier(mod)
        }
    }

    /** Stands, and forgets where it was going. */
    fun halt() {
        goal = null
        markTick = Long.MIN_VALUE
        measured = 0.0
        mob.navigation.stop()
    }

    /** [to] if it is a path's length away; else a spot on the way to it, since a mob finds its way only so far. */
    private fun within(to: Vec3): Vec3 {
        if (mob.distanceToSqr(to) <= FAR * FAR) return to
        val walker = mob as? PathfinderMob ?: return to
        return LandRandomPos.getPosTowards(walker, 24, 8, to) ?: to
    }

    companion object {
        /** Blocks a second a walker makes for each unit of (speed x modifier) squared on level ground: 20 ticks over the ground's drag of 0.546. */
        private const val WALK_FACTOR = 44.0

        /** How far away a goal can be and still be asked for whole. */
        private const val FAR = 40.0
    }
}

/**
 * One of a party: an illager of the Raids' look (a vindicator or a pillager, [Waylayers.spawn] made it) that the Hunt walks about by hand ([Stride])
 * until a blow makes the fight real.
 */
internal class Member(val mob: Mob, val slot: Int, val startAt: Long, val jitter: Double) {
    /** The leader: the first, in the band's banner. */
    val leader: Boolean get() = slot == 0

    /** The sack it carries off, as goods and units, until it is dropped by its fall or handed to the camp. */
    val carry = ArrayList<Pair<Good, Int>>()

    /** Struck as the party ran for the camp with the sacks: it turned to fight whoever struck it (the others run on). */
    var turned = false

    private val stride = Stride(mob)

    /** The blocks a second it was last seen to make (0 while it stands). */
    val measured: Double get() = stride.measured

    val alive: Boolean get() = mob.isAlive && !mob.isRemoved

    /** Walks towards [to] at about [bps] blocks a second, looking for the way again every [every] ticks (or at once if [to] has moved on). */
    fun go(to: Vec3, bps: Double, now: Long, every: Int = 10) = stride.go(to, bps, now, every)

    /** Stands, and forgets where it was going. */
    fun halt() = stride.halt()
}

/**
 * The two to four outlaws of a Hunt (design Part 4, "The party"): made together at the spot the horn came from, the first in the band's banner,
 * torches in the off hand from dusk, and walked by the Hunt in a loose line, closing in on the trader, searching their pack, running for the camp with
 * a sack each, or breaking and scattering when they lose. They are vanilla illagers and so have not a townsperson's clips: the leader's
 * stoop over the pack is a bent head, a swing of the arm and a clink; the fight is the illagers' own.
 */
internal class HuntParty(private val level: ServerLevel, private val tag: String) {
    val members = ArrayList<Member>()

    /** How many there were at the start: it does not fall as they do. */
    val size: Int get() = members.size

    fun alive(): List<Member> = members.filter { it.alive }

    /** Whoever is first of those still on their feet: the one who speaks for them. */
    fun speaker(): Member? = members.firstOrNull { it.alive }

    /** The leader himself, if he is on his feet. */
    fun leader(): Member? = members.firstOrNull { it.leader && it.alive }

    fun of(entity: Entity): Member? = members.firstOrNull { it.mob === entity }

    // ---- Making them ----

    /**
     * [size] of [band]'s men come to be at [at] (the first, in the banner) in a loose line facing [toward], the way the trader lies (a unit vector across the
     * ground): the leader in front, the rest back and to the side, one starting a moment after the last. Gives whether any was made.
     */
    fun spawn(at: Vec3, toward: Vec3, size: Int, now: Long): Boolean {
        val yaw = yawOf(toward)
        val across = Vec3(-toward.z, 0.0, toward.x)
        val dark = Math.floorMod(level.overworldClockTime, 24000L) >= 12000L
        for (k in 0 until size) {
            val x = at.x - toward.x * k * DEPTH + across.x * LATERAL[k.coerceAtMost(3)]
            val z = at.z - toward.z * k * DEPTH + across.z * LATERAL[k.coerceAtMost(3)]
            val spot = ground(level, x, z) ?: at
            val type = if (k == 0 || level.random.nextFloat() < 0.6f) EntityTypes.VINDICATOR else EntityTypes.PILLAGER
            val mob = Waylayers.spawn(level, type, spot, yaw, tag) ?: if (k == 0) return false else continue
            // Walking in, not yet in a rage: the arms come up when the fight is real
            mob.isAggressive = false
            mob.getAttribute(Attributes.FOLLOW_RANGE)?.baseValue = FOLLOW
            if (k == 0) {
                mob.setItemSlot(EquipmentSlot.HEAD, Raid.getOminousBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)))
                mob.setDropChance(EquipmentSlot.HEAD, 0f)
            }
            if (dark) {
                mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.TORCH))
                mob.setDropChance(EquipmentSlot.OFFHAND, 0f)
            }
            members.add(Member(mob, k, now + k * STAGGER, 0.94 + level.random.nextDouble() * 0.14))
        }
        return members.isNotEmpty()
    }

    // ---- Coming down ----

    /**
     * One tick of the approach to [player]: each, once its turn to set off has come, walks at [pace] blocks a second (for how far it still is) at its
     * own few hundredths quicker or slower, towards a spot three blocks or so from the trader on the side it comes from, fanned out as it closes in.
     */
    fun come(player: ServerPlayer, now: Long, pace: (Double) -> Double) {
        for (m in members) {
            if (!m.alive) continue
            if (now < m.startAt) {
                m.halt()
                m.mob.lookControl.setLookAt(player.eyePosition)
                continue
            }
            val d = m.mob.distanceTo(player).toDouble()
            m.go(ring(player, m, d), pace(d) * m.jitter, now)
            m.mob.lookControl.setLookAt(player.eyePosition)
        }
    }

    /** Where [m] makes for: [STAND] blocks (a little more for the later ones) from [player], on the line from them to [m], turned aside by the member's share of a fan as it nears. */
    private fun ring(player: ServerPlayer, m: Member, d: Double): Vec3 {
        val from = m.mob.position()
        var ux = from.x - player.x
        var uz = from.z - player.z
        val len = hypot(ux, uz).coerceAtLeast(0.001)
        ux /= len
        uz /= len
        val a = ANGLE[m.slot.coerceAtMost(3)] * ((14.0 - d) / 8.0).coerceIn(0.0, 1.0)
        val rx = ux * cos(a) - uz * sin(a)
        val rz = ux * sin(a) + uz * cos(a)
        val r = STAND + 0.6 * m.slot
        return Vec3(player.x + rx * r, player.y, player.z + rz * r)
    }

    // ---- The search ----

    /**
     * One tick of the search, [since] ticks in: they stand where they have stopped round the trader, the leader with his head bent over the pack (looking
     * at their waist), an arm working and a clink of coin every little while; the second at [work] (a pack animal that stood its ground, or a cart), cutting
     * at its straps or turning back its canvas, or at the pack if there is neither; the rest watching the road. A late one is still closing in.
     */
    fun search(player: ServerPlayer, now: Long, since: Long, dearest: Item?, work: Vec3?) {
        for (m in members) {
            if (!m.alive) continue
            val d = m.mob.distanceTo(player).toDouble()
            if (m.slot == 1 && work != null) {
                // To the animal's flank or the cart's side, and at it
                val off = m.mob.position().distanceTo(work)
                if (off > 1.9) {
                    m.go(work, 3.6 * m.jitter, now)
                    m.mob.lookControl.setLookAt(work.add(0.0, 0.6, 0.0))
                    continue
                }
                m.halt()
                m.mob.lookControl.setLookAt(work.add(0.0, 0.5, 0.0))
                if (since % 14L == 6L) {
                    work(m)
                    HuntVoice.sound(level, work.add(0.0, 0.8, 0.0), "trade.pack_clink", 0.8f, 0.9f + level.random.nextFloat() * 0.2f)
                }
                continue
            }
            if (d > STAND + 2.5) {
                m.go(ring(player, m, d), 4.2 * m.jitter, now)
                continue
            }
            m.halt()
            val at = m.mob.position().add(0.0, 1.5, 0.0)
            when (m.slot) {
                0 -> {
                    m.mob.lookControl.setLookAt(player.position().add(0.0, 0.35, 0.0))
                    if (since % 12L == 3L) {
                        work(m)
                        if (dearest != null) tossed(dearest, player.position().add(0.0, 0.9, 0.0))
                    }
                    if (since % 17L == 5L) HuntVoice.clink(level, at, 0.9f + level.random.nextFloat() * 0.3f)
                }
                1 -> {
                    m.mob.lookControl.setLookAt(player.position().add(0.0, 1.0, 0.0))
                    if (since % 14L == 6L) {
                        work(m)
                        HuntVoice.sound(level, at, "trade.pack_clink", 0.8f, 0.9f + level.random.nextFloat() * 0.2f)
                    }
                }
                else -> {
                    // Heads up, turning on the road
                    val turn = (now / 25 + m.slot * 3) % 4L
                    val away = Vec3(m.mob.x - player.x, 0.0, m.mob.z - player.z).normalize()
                    val side = if (turn < 2L) 1.0 else -1.0
                    m.mob.lookControl.setLookAt(m.mob.x + away.x * 8.0 - away.z * 6.0 * side, m.mob.eyeY, m.mob.z + away.z * 8.0 + away.x * 6.0 * side)
                }
            }
        }
    }

    /** An arm swings: a hand going through a pack, a knife at a strap. */
    private fun work(m: Member) {
        m.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
    }

    /** A few bits of the dearest thing in the pack, tossed up as the hand goes through it. */
    private fun tossed(item: Item, at: Vec3) {
        level.sendParticles(ItemParticleOption(ParticleTypes.ITEM, item), at.x, at.y, at.z, 4, 0.2, 0.15, 0.2, 0.05)
    }

    // ---- Taking it away ----

    /** Each of [sacks] to one of the party (the leader first), in the off hand as the dearest good in it: the torch gives way to it. */
    fun giveSacks(sacks: List<List<Pair<Good, Int>>>) {
        for ((i, m) in members.withIndex()) {
            m.carry.clear()
            val sack = sacks.getOrNull(i) ?: continue
            m.carry.addAll(sack)
            val dearest = sack.maxByOrNull { it.first.base * it.second }?.first?.item ?: continue
            m.mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(dearest))
            m.mob.setDropChance(EquipmentSlot.OFFHAND, 0f)
        }
    }

    /** Makes for [goal] with the sacks, at [pace] blocks a second, one behind the other; none of them stops to fight. */
    fun leave(goal: Vec3, now: Long, pace: Double) {
        for (m in members) if (m.alive && !m.turned) m.go(goal, pace * m.jitter, now, 20)
    }

    /**
     * Breaks and scatters: each runs off on its own bearing, [spread] radians apart, away from [from] (or towards [home] if there is somewhere to go
     * back to), at [pace] blocks a second, a rout and not a march. Their torches go out.
     */
    fun scatter(from: Vec3, home: Vec3?, now: Long, pace: Double) {
        for (m in members) {
            if (!m.alive) continue
            val base = if (home != null) atan2(home.z - m.mob.z, home.x - m.mob.x) else atan2(m.mob.z - from.z, m.mob.x - from.x)
            val a = base + (m.slot - (members.size - 1) / 2.0) * SPREAD
            val goal = Vec3(m.mob.x + cos(a) * 60.0, m.mob.y, m.mob.z + sin(a) * 60.0)
            m.go(goal, pace * m.jitter, now, 15)
            m.mob.lookControl.setLookAt(goal)
        }
    }

    /** Their torches go out, with a puff of smoke. */
    fun snuff() {
        for (m in members) {
            if (!m.alive || !m.mob.offhandItem.`is`(Items.TORCH)) continue
            m.mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY)
            level.sendParticles(ParticleTypes.SMOKE, m.mob.x, m.mob.y + 1.4, m.mob.z, 6, 0.15, 0.1, 0.15, 0.02)
        }
    }

    /** The torches burning: a flame and a wisp of smoke at the off hand now and then. */
    fun burn() {
        for (m in members) {
            if (!m.alive || !m.mob.offhandItem.`is`(Items.TORCH) || level.random.nextInt(3) != 0) continue
            val side = Math.toRadians(m.mob.yBodyRot.toDouble() + 60.0)
            level.sendParticles(ParticleTypes.FLAME, m.mob.x - sin(side) * 0.45, m.mob.y + 1.35, m.mob.z + cos(side) * 0.45, 1, 0.02, 0.04, 0.02, 0.005)
            level.sendParticles(ParticleTypes.SMOKE, m.mob.x - sin(side) * 0.45, m.mob.y + 1.5, m.mob.z + cos(side) * 0.45, 1, 0.03, 0.05, 0.03, 0.01)
        }
    }

    // ---- The fight ----

    /**
     * The fight is real: each goes for players, for whoever hurt one of its own, for the townsfolk [foe] names (the hired guards of the one hunted) and for
     * their sworn companion; for nobody else, and never for a villager.
     */
    fun arm(foe: (Entity) -> Boolean, owner: UUID, only: List<Member> = alive()) {
        for (m in only) {
            if (!m.alive) continue
            m.halt()
            Waylayers.arm(m.mob, foe)
            m.mob.targetSelector.addGoal(3, NearestAttackableTargetGoal(m.mob, CompanionEntity::class.java, 10, true, false) { e, _ -> (e as? CompanionEntity)?.ownerId == owner })
            m.mob.isAggressive = true
        }
    }

    /** The fight is over, for them: no target, no wish for one, and the arms down. */
    fun disarm() {
        for (m in alive()) {
            m.mob.target = null
            m.mob.targetSelector.removeAllGoals { true }
            m.mob.navigation.stop()
            m.mob.isAggressive = false
            (m.mob as? Pillager)?.setChargingCrossbow(false)
        }
    }

    // ---- Going ----

    /** Lets all of them go, in a puff of dust, wherever they are. */
    fun discard() {
        for (m in members) {
            if (m.mob.isRemoved) continue
            if (m.alive) level.sendParticles(ParticleTypes.POOF, m.mob.x, m.mob.y + 1.0, m.mob.z, 6, 0.25, 0.4, 0.25, 0.02)
            m.mob.discard()
        }
    }

    companion object {
        /** How far from the trader the leader stops, and the later ones' extra. */
        const val STAND = 3.0

        /** How far a member can find a way (its follow range, blocks), so that a party coming from 48 blocks off finds the trader. */
        private const val FOLLOW = 64.0

        /** The line they come in: each this far behind the one before, and this far to one side or the other of the leader's track. */
        private const val DEPTH = 2.4
        private val LATERAL = doubleArrayOf(0.0, -3.0, 3.5, -6.0)

        /** The fan they close in as: the angle off the straight line each ends at, radians. */
        private val ANGLE = doubleArrayOf(0.0, 0.65, -0.65, 1.3)

        /** Ticks between one setting off and the next, and the angle between the bearings of a rout. */
        private const val STAGGER = 16L
        private const val SPREAD = 0.9

        /** The yaw of an entity facing along [v] (a vector on the ground). */
        fun yawOf(v: Vec3): Float = (atan2(v.z, v.x) * (180.0 / Math.PI)).toFloat() - 90f

        /** Open ground at [x], [z]: the top of the land there, if it is loaded and dry, else null. */
        fun ground(level: ServerLevel, x: Double, z: Double): Vec3? {
            val pos = BlockPos.containing(x, 0.0, z)
            if (!level.isLoaded(pos)) return null
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos)
            if (!level.getFluidState(top.below()).isEmpty || !level.getFluidState(top).isEmpty) return null
            if (!level.getBlockState(top).getCollisionShape(level, top).isEmpty || !level.getBlockState(top.above()).getCollisionShape(level, top.above()).isEmpty) return null
            return Vec3(x, top.y.toDouble(), z)
        }

        /**
         * Where the party comes into being for a Hunt of [player] by a band whose camp lies the way [toward] says (a unit vector on the ground): 38 to
         * 48 blocks off on that side, on open ground, out of the trader's sight if there is such a spot among those looked at, never on a height or in
         * a hollow a long way from where they stand. Null if none is loaded and dry.
         */
        fun spot(level: ServerLevel, player: ServerPlayer, toward: Vec3): Vec3? {
            val eye = player.eyePosition
            var best: Vec3? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (turn in TURNS) for (distance in DISTANCES) {
                val c = cos(turn)
                val s = sin(turn)
                val dx = toward.x * c - toward.z * s
                val dz = toward.x * s + toward.z * c
                val spot = ground(level, player.x + dx * distance, player.z + dz * distance) ?: continue
                if (abs(spot.y - player.y) > 18.0) continue
                val seen = level.clip(ClipContext(eye, spot.add(0.0, 1.6, 0.0), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS
                val score = (if (seen) 0.0 else 100.0) - abs(turn) * 8.0 - abs(spot.y - player.y) * 0.5 + distance * 0.1
                if (score > bestScore) {
                    bestScore = score
                    best = spot
                }
            }
            return best
        }

        private val TURNS = doubleArrayOf(0.0, 0.3, -0.3, 0.6, -0.6, 0.9, -0.9)
        private val DISTANCES = doubleArrayOf(46.0, 42.0, 38.0)
    }
}
