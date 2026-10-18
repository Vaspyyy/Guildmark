package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.RandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.illager.Pillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.raid.Raider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A caravan struck at its strike point (design-road.md section 2, "On screen"): the waylayers running in from off the
 * road, the guards down off the wagon to meet them, the driver crouched behind a wheel; the fight, played out blow
 * for parried blow while no player joins (the record's dice decide it), fought for real once one does; and after,
 * the band beaten off (the wagon going on, late) or the wagon taken: the horse cut loose or cut down, the load smashed
 * and carried off, the wagon shoved over into its wreck, the band making for its camp, the driver running for the
 * gate and the hurt guards limping after.
 */
internal class Waylay(val show: Show, private var strike: Caravans.Strike?) {
    enum class Phase { RUSH, FIGHT, BEATEN, TAKEN, RUN }

    /** One of the band in the fight. */
    private class Man(val mob: Mob, val vindicator: Boolean) {
        var spot: Vec3 = mob.position()
        var foe: Hand? = null
        var next = 0
        var charging = -1
        var thudAt = -1
        var backAt = -1
        var parryAt = -1
        var fleeing = false
        var away: Vec3? = null
        val carry = ArrayList<Pair<Good, Int>>()
        var real = false
    }

    val level: ServerLevel get() = show.level
    private val id = show.id
    private val band = ArrayList<Man>()
    var phase = Phase.RUSH
        private set
    var real = false
        private set
    private var t = 0
    private val random = Random(id * -7046029254386353131L + 77L)

    /** How long a fight nobody joins goes on before the dice's side wins. */
    private val length = FIGHT_LEAST + random.nextInt(FIGHT_MOST - FIGHT_LEAST + 1)

    /** Whether the record has heard how it came out; the waylayers killed; how many ran in. */
    private var resolved = false
    private var kills = 0
    private var size = 0

    /** Which side of the road the band came from (1 the road's right), and the verge the wagon goes over toward. */
    private var side = 1.0
    private var verge = 1.0

    /** Ticks no player has been near the wagon in a real fight; the tick each of the aftermath's steps falls on. */
    private var unwatched = 0
    private var decidedAt = 0
    private var smashAt = 0
    private var shoveAt = -1
    private var tipAt = -1
    private var swapped = false
    private var cutter: Man? = null
    private var hitAt = -1
    private var horseDead = false
    private var bolting = -1
    private var boltYaw = 0f
    private var boltSpeed = 0.0
    private var driverRunning = false
    private var atGate = -1
    private var due = false
    private var beatenDone = false
    private val limping = HashMap<Hand, Int>()

    /** Whether the fight is still on (not yet decided). */
    val fighting: Boolean get() = !resolved && (phase == Phase.RUSH || phase == Phase.FIGHT)

    // ---- The band runs in ----

    /** The strike's begun: the band runs in from off the road, the crew get down to meet it. */
    fun start() {
        val s = strike ?: return
        val w = show.wagon
        val along = show.view.strike ?: show.way.nearest(show.wagonAt().x, show.wagonAt().z)
        side = show.bandSide(along)
        verge = show.verge
        size = max(1, s.waylayers)
        val (rx, rz) = show.way.right(along)
        val mid = show.wagonAt()
        for (k in 0 until size) {
            val vind = k == 0 || random.nextFloat() < 0.6f
            val at = hidden(along, rx, rz, k, mid) ?: continue
            val mob = spawn(if (vind) EntityTypes.VINDICATOR else EntityTypes.PILLAGER, at) ?: continue
            band.add(Man(mob, vind))
        }
        if (band.isEmpty()) {
            // Nowhere for them to come from: the fight's over before it starts, the dice's way
            decide(s.waylaid, record = true)
            return
        }
        Bodies.sound(level, band[0].mob.position().add(0.0, 1.5, 0.0), "whoop", 3f, 1f)
        spots()
        // The crew: the driver down and behind a wheel, the guards down and out to meet them
        val d = show.driver
        if (d != null) {
            d.clear()
            d.p.lookAt(band[0].mob)
            d.then(Say("driver_trees", 48.0))
            if (d.seat != Wagon.OFF) d.then(JumpDown(byHand = true))
            d.then(Go({ show.around(it, cowerSpot().first) }, 0.17, { cowerSpot().second })).then { it.p.act(Activity.COWER) }
        }
        var first = true
        for (h in show.hands) {
            if (h.driver) continue
            h.clear()
            val spot = guardSpot(h)
            if (first) {
                h.then(Wait(12)).then(Say("guard_to_wagon", 48.0))
                first = false
            }
            when (h.seat) {
                // The bench's guard goes along to the driver's end once the driver's down, and jumps from there
                Wagon.BENCH -> h.then(Until(60) { show.driver?.seat != Wagon.DRIVER }).then(Slide(Wagon.DRIVER)).then(JumpDown(byHand = true))
                Wagon.OFF -> {}
                else -> h.then(Wait(6 + h.index * 4)).then(JumpDown(byHand = true))
            }
            h.then(Go({ show.around(it, spot.first) }, 0.15, { spot.second })).then { it.p.act(Activity.STAND_GUARD) }
        }
        w?.flag(Wagon.FLAG_BRAKE, true)
        // The band's arrows go into the side it faces them with: its left unless they're on its right
        w?.flag(Wagon.FLAG_MIRROR, side > 0)
    }

    /** Where the band's [k]th comes from: 24 to 32 blocks off the road on its side, out of the wagon's sight if the ground allows. */
    private fun hidden(along: Double, rx: Double, rz: Double, k: Int, mid: Vec3): Vec3? {
        var fallback: Vec3? = null
        for (tries in 0 until 8) {
            val a = along + (k - (size - 1) / 2.0) * 3.5 + (random.nextDouble() - 0.5) * 6.0
            val out = 24.0 + random.nextDouble() * 8.0
            val p = show.way.at(a)
            val x = p.x + rx * side * out
            val z = p.z + rz * side * out
            val pos = BlockPos.containing(x, p.y, z)
            if (!level.isPositionEntityTicking(pos)) continue
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z)).toDouble()
            if (y < p.y - 12 || y > p.y + 12) continue
            val at = Vec3(x, y, z)
            if (fallback == null) fallback = at
            val sight = level.clip(ClipContext(at.add(0.0, 1.6, 0.0), mid.add(0.0, 1.4, 0.0), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
            if (sight.type != HitResult.Type.MISS) return at
        }
        return fallback
    }

    /** One of the band, at [at]: dropping nothing, going for nobody until a player joins in. */
    private fun spawn(type: EntityType<out Raider>, at: Vec3): Mob? {
        val mob = type.create(level, EntitySpawnReason.EVENT) ?: return null
        mob.snapTo(at.x, at.y, at.z, random.nextFloat() * 360f, 0f)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), EntitySpawnReason.EVENT, null)
        mob.setCanJoinRaid(false)
        mob.setPersistenceRequired()
        mob.addTag(TAG)
        mob.customName = Component.translatable("entity.guildmark.outlaw")
        for (slot in EquipmentSlot.values()) mob.setDropChance(slot, 0f)
        mob.targetSelector.removeAllGoals { true }
        mob.goalSelector.removeAllGoals { g ->
            g is RandomStrollGoal || g is LookAtPlayerGoal || g is RandomLookAroundGoal ||
                g.javaClass.name.let { it.contains("Raid") || it.contains("Banner") || it.contains("Celebrat") }
        }
        mob.isAggressive = true
        return if (level.addFreshEntity(mob)) mob else null
    }

    /** Where everyone stands for the fight, the wagon still now: guards out on the band's side, each with a foe before them, the bowmen further off. */
    private fun spots() {
        val guards = show.hands.filter { !it.driver }
        var g = 0
        var b = 0
        for (m in band) {
            if (m.vindicator && guards.isNotEmpty()) {
                val foe = guards[g % guards.size]
                val (at, _) = guardSpot(foe)
                val again = g / guards.size
                val (ox, oz) = outward()
                val along = if (again == 0) 0.0 else (if (again % 2 == 1) 1.2 else -1.2)
                val (fx, fz) = forward()
                m.spot = at.add(ox * 2.3 + fx * along, 0.0, oz * 2.3 + fz * along)
                m.foe = foe
                g++
            } else {
                val z = -60f + 50f * (b % 3)
                val out = 16f * (7f + 2f * random.nextFloat())
                m.spot = show.ground(-(side.toFloat()) * out, z)
                b++
            }
            m.spot = Vec3(m.spot.x, Ground.stand(level, m.spot.x, m.spot.z, m.spot.y + 0.5), m.spot.z)
            m.next = 100 + random.nextInt(60)
            m.mob.navigation.moveTo(m.spot.x, m.spot.y, m.spot.z, RUSH_SPEED)
        }
    }

    /** Out from the wagon toward the band, and along it toward its front: units (x, z). */
    private fun outward(): Pair<Double, Double> {
        val y = Math.toRadians(show.yaw((90.0 * side).toFloat()).toDouble())
        return -sin(y) to cos(y)
    }

    private fun forward(): Pair<Double, Double> {
        val y = Math.toRadians(show.yaw(0f).toDouble())
        return -sin(y) to cos(y)
    }

    /** Guard [h]'s place in the fight, out on the band's side (px in the wagon's space), facing out. */
    private fun guardSpot(h: Hand): Pair<Vec3, Float> {
        val zs = floatArrayOf(-40f, -8f, 24f, -24f)
        val z = zs[(h.index - 1).coerceIn(0, 3)]
        val x = -(side.toFloat()) * (if (h.index == 4) 44f else 40f)
        return show.ground(x, z) to show.yaw((90.0 * side).toFloat())
    }

    /** The driver's place behind the back wheel on the far side from the band, facing it. */
    private fun cowerSpot(): Pair<Vec3, Float> =
        if (side > 0) show.ground(28f, 6f) to show.yaw(90f) else show.ground(-28f, -6f) to show.yaw(-90f)

    // ---- Each tick ----

    /** One tick; false once everything of it has gone (the band, the runner, the hurt guards). */
    fun tick(): Boolean {
        t++
        band.removeIf { it.mob.isRemoved }
        // Reared at the whistle, it stands and stamps through the fight
        show.horse?.let { h -> if (h.move == DraughtHorse.Move.REAR && h.moveTicks > REAR_TICKS) h.move(DraughtHorse.Move.IDLE) }
        when (phase) {
            Phase.RUSH -> rush()
            Phase.FIGHT -> if (real) realFight() else playedFight()
            Phase.BEATEN -> beaten()
            Phase.TAKEN -> taken()
            Phase.RUN -> {}
        }
        if (phase == Phase.TAKEN || phase == Phase.RUN || phase == Phase.BEATEN) {
            flee()
            horse()
        }
        if (phase == Phase.RUN) {
            run()
            limp()
            return band.isNotEmpty() || show.hands.isNotEmpty() || bolting >= 0
        }
        if (phase == Phase.BEATEN && beatenDone) return band.isNotEmpty()
        return true
    }

    private fun rush() {
        var there = 0
        for (m in band) {
            val d = m.mob.position().distanceTo(m.spot)
            if (d < 1.2) {
                there++
                m.mob.navigation.stop()
            } else if (t % 20 == 0) {
                m.mob.navigation.moveTo(m.spot.x, m.spot.y, m.spot.z, RUSH_SPEED)
            }
            // Out of the trees by now, where they should be: put them there, once nobody's looking
            if (t > RUSH_MOST && d > 3.0 && !watching(m.mob.position(), 48.0)) m.mob.snapTo(m.spot.x, m.spot.y, m.spot.z, m.mob.yRot, 0f)
            face(m)
        }
        val crewReady = show.hands.all { it.idle || it.p.isRemoved }
        if ((there == band.size && crewReady) || t > RUSH_MOST + 60) {
            phase = Phase.FIGHT
            t = 0
        }
    }

    /** Each fighting tick, a played fight: blows struck and parried by the pairs, the bowmen loosing at the wagon, the dice's side winning at the end. */
    private fun playedFight() {
        for (m in band) {
            if (m.fleeing) continue
            if (m.mob.position().distanceTo(m.spot) > 1.5 && t % 10 == 0 && m.backAt < 0) m.mob.navigation.moveTo(m.spot.x, m.spot.y, m.spot.z, 1.0)
            face(m)
            if (m.vindicator) exchange(m) else shoot(m)
        }
        for (h in show.hands) {
            if (h.driver || !h.idle) continue
            if ((h.p.activity == Activity.SPAR_GUARD || h.p.activity == Activity.SPAR_ATTACK || h.p.activity == Activity.STAGGER) && h.p.actTicks > 28) h.p.act(Activity.STAND_GUARD)
        }
        if (t == length / 2) show.hands.firstOrNull { !it.driver }?.then(Say("guard_together", 40.0))
        if (t >= length) {
            val s = strike ?: return
            decide(s.waylaid, record = true)
        }
    }

    /** One pair's blow: the waylayer swings and the guard parries (staggered now and then), or the guard thrusts and the waylayer steps back. */
    private fun exchange(m: Man) {
        val foe = m.foe?.takeIf { !it.p.isRemoved && it.idle } ?: return
        if (m.parryAt == t) {
            sound(foe.p.position().add(0.0, 1.2, 0.0), SoundEvents.SHIELD_BLOCK.value(), 0.7f, 0.8f + random.nextFloat() * 0.3f)
        }
        if (m.backAt == t) m.mob.navigation.moveTo(m.spot.x, m.spot.y, m.spot.z, 0.9)
        if (t < m.next) return
        m.next = t + 30 + random.nextInt(21)
        if (random.nextBoolean()) {
            m.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
            sound(m.mob.position().add(0.0, 1.3, 0.0), SoundEvents.PLAYER_ATTACK_SWEEP, 0.6f, 0.75f + random.nextFloat() * 0.2f)
            if (random.nextFloat() < 0.15f) {
                foe.p.act(Activity.STAGGER)
                sound(foe.p.position().add(0.0, 1.2, 0.0), SoundEvents.PLAYER_ATTACK_KNOCKBACK, 0.6f, 0.9f)
            } else {
                foe.p.act(Activity.SPAR_GUARD)
                m.parryAt = t + 5
            }
        } else {
            foe.p.act(Activity.SPAR_ATTACK)
            sound(foe.p.position().add(0.0, 1.3, 0.0), SoundEvents.PLAYER_ATTACK_WEAK, 0.6f, 1.0f)
            val (ox, oz) = outward()
            val back = m.spot.add(ox * 1.1, 0.0, oz * 1.1)
            m.mob.navigation.moveTo(back.x, back.y, back.z, 1.1)
            m.backAt = t + 16
        }
    }

    /** A bowman's shot: a crossbow drawn, loosed at the wagon, the bolt thudding into its boards. */
    private fun shoot(m: Man) {
        val pill = m.mob as? Pillager ?: return
        val w = show.wagon
        if (m.thudAt == t && w != null) {
            w.arrow(level)
            sound(show.wagonAt().add(0.0, 1.2, 0.0), SoundEvents.ARROW_HIT, 0.8f, 0.9f + random.nextFloat() * 0.2f)
        }
        if (m.charging > 0) {
            m.charging--
            if (m.charging == 0) {
                pill.setChargingCrossbow(false)
                sound(pill.position().add(0.0, 1.4, 0.0), SoundEvents.CROSSBOW_SHOOT, 0.9f, 1f)
                m.thudAt = t + 4
            }
            return
        }
        if (t < m.next) return
        m.next = t + 60 + random.nextInt(50)
        m.charging = CHARGE
        pill.setChargingCrossbow(true)
        sound(pill.position().add(0.0, 1.4, 0.0), SoundEvents.CROSSBOW_LOADING_START.value(), 0.6f, 1f)
    }

    /** Each band member faces what they're at: their guard, or the wagon. */
    private fun face(m: Man) {
        if (m.real && m.mob.target != null) return
        val foe = m.foe?.p?.takeIf { !it.isRemoved }
        if (foe != null) m.mob.lookControl.setLookAt(foe, 30f, 30f)
        else m.mob.lookControl.setLookAt(show.wagonAt().add(0.0, 1.2, 0.0))
    }

    // ---- A player joins ----

    /** A player has struck one of the band (or been struck at): from now on blows land both ways and the fight decides. */
    private fun goReal() {
        if (real || resolved) return
        real = true
        if (phase == Phase.RUSH) {
            phase = Phase.FIGHT
            t = 0
        }
        for (m in band) arm(m)
        for (h in show.hands) {
            if (h.driver) continue
            h.clear()
            if (h.seat != Wagon.OFF) {
                // Still up on the wagon: down they come, and in
                val (at, yaw) = h.beside(h.seat, JumpDown.JUMP_RIGHT, JumpDown.JUMP_AHEAD, JumpDown.JUMP_TURN)
                h.land(at, yaw, byHand = false)
            } else {
                h.hold(false)
            }
            h.p.act(Activity.NONE)
        }
    }

    /** [m] fights back for real: going for players and the guards. */
    private fun arm(m: Man) {
        if (m.real) return
        m.real = true
        val mob = m.mob
        mob.navigation.stop()
        (mob as? Pillager)?.setChargingCrossbow(false)
        (mob as? Raider)?.let { mob.targetSelector.addGoal(1, HurtByTargetGoal(it, Raider::class.java)) }
        mob.targetSelector.addGoal(2, NearestAttackableTargetGoal(mob, Player::class.java, true))
        mob.targetSelector.addGoal(3, NearestAttackableTargetGoal(mob, Townsperson::class.java, 10, true, false) { e, _ -> guardOf(e) })
    }

    /** Whether [e] is one of this caravan's guards still standing. */
    private fun guardOf(e: Entity): Boolean = show.hands.any { !it.driver && it.p === e && it.p.isAlive }

    /** Each tick of a real fight: the guards at the band, the band at them and the players, until half the band's down or every guard is. */
    private fun realFight() {
        val guards = show.hands.filter { !it.driver && it.p.isAlive && !it.p.isRemoved }
        if (t % 10 == 0) for (g in guards) {
            val target = g.p.target?.takeIf { it.isAlive && band.any { m -> m.mob === it } }
            if (target == null) band.filter { it.mob.isAlive }.minByOrNull { it.mob.distanceToSqr(g.p) }?.let { g.p.target = it.mob }
        }
        val near = level.players().any { !it.isSpectator && it.isAlive && flat(it.position(), show.wagonAt()) < WATCHED }
        unwatched = if (near) 0 else unwatched + 1
        val alive = band.count { it.mob.isAlive }
        when {
            kills >= ceil(size / 2.0) || alive == 0 -> decide(waylaid = false, record = true)
            guards.isEmpty() && unwatched >= UNWATCHED -> decide(waylaid = true, record = true)
        }
    }

    // ---- How it comes out ----

    /**
     * The fight's decided: [waylaid] or beaten off, told to the records ([record]) if it wasn't they who settled it.
     * The aftermath plays from here.
     */
    private fun decide(waylaid: Boolean, record: Boolean) {
        if (resolved) return
        resolved = true
        decidedAt = t
        if (record) {
            show.resolving = true
            try {
                if (real) Caravans.fought(id, waylaid, kills, strike?.horse == Caravans.Horse.CUT_DOWN)
                else Caravans.played(id)
            } finally {
                show.resolving = false
            }
        }
        for (h in show.hands) h.p.target = null
        show.hands.removeIf { !it.p.isAlive || it.p.isRemoved }
        if (waylaid) taking() else beatenOff()
    }

    /** The record settled the strike by itself (its wait for the fight ran out): its result stands, and plays from here. */
    fun settled(s: Caravans.Strike, ours: Boolean): Boolean {
        strike = s
        if (ours || resolved) return true
        decide(s.waylaid, record = false)
        return true
    }

    // ---- Beaten off ----

    private fun beatenOff() {
        phase = Phase.BEATEN
        t = 0
        for (m in band) runFor(m)
        val guards = show.hands.filter { !it.driver }
        guards.firstOrNull()?.then(Say("guard_beaten_off", 48.0))
        for ((k, g) in guards.withIndex()) {
            g.clear()
            if (!g.kinematic) g.hold(true)
            g.then(Wait(4 + k * 6)).then(Play(if (k % 2 == 0) Activity.CHEER else Activity.HURRAH)).then(Play(Activity.MOP_BROW))
            show.board(g)
        }
        val d = show.driver
        if (d != null) {
            d.clear()
            if (!d.kinematic) d.hold(true)
            d.then(Wait(30)).then { it.p.act(Activity.NONE) }
                .then(Go({ show.around(it, horseHead().first) }, 0.12, { horseHead().second }))
                .then(Say("driver_calm", 16.0)).then(Play(Activity.TALK, 60))
            show.board(d)
        }
        show.horse?.move(DraughtHorse.Move.IDLE)
    }

    /** Where the driver stands to calm the horse: by its head, on its right, facing it. */
    private fun horseHead(): Pair<Vec3, Float> {
        val h = show.horse ?: return show.ground(-20f, -90f) to show.yaw(0f)
        return show.horsePoint(-18f, -24f) to Mth.wrapDegrees(h.yRot - 70f)
    }

    private fun beaten() {
        if (beatenDone) return
        val aboard = show.hands.all { it.idle && (it.seat != Wagon.OFF || show.boardingSeat(it.index) == Wagon.OFF) }
        if ((aboard && t > 60) || t > BEATEN_MOST) {
            beatenDone = true
            // Whoever isn't up yet is put there
            for (h in show.hands) {
                val seat = show.boardingSeat(h.index)
                if (seat != Wagon.OFF && h.seat != seat) {
                    h.clear()
                    h.seatOn(seat)
                    h.p.act(show.seatedActivity(seat))
                }
            }
            show.resume(afterFight = true)
        }
    }

    // ---- Taken ----

    private fun taking() {
        phase = Phase.TAKEN
        t = 0
        val s = strike
        horseDead = s?.horse == Caravans.Horse.CUT_DOWN
        val guards = show.hands.filter { !it.driver }
        guards.firstOrNull()?.then(Say("guard_cant_hold", 48.0))
        for (g in guards) {
            g.clear()
            if (!g.kinematic) g.hold(true)
            g.then(Play(Activity.STAGGER, 40)).then { it.p.act(Activity.KNEEL) }
            limping[g] = HURT_KNEEL
        }
        for (m in band) {
            m.real = false
            m.mob.target = null
            m.mob.targetSelector.removeAllGoals { true }
        }
        // What they carry off, shared out among them
        val taken = s?.taken ?: listOf()
        if (band.isNotEmpty()) for ((k, g) in taken.withIndex()) band[k % band.size].carry.add(g)
        val d = show.driver
        if (horseDead) {
            cutter = band.firstOrNull { it.vindicator } ?: band.firstOrNull()
            cutter?.let { c ->
                val (at, _) = cutSpot(26f)
                c.spot = at
                c.mob.navigation.moveTo(at.x, at.y, at.z, 1.1)
            }
            hitAt = -1
        } else if (d != null) {
            d.clear()
            if (!d.kinematic) d.hold(true)
            d.then(Wait(20)).then { it.p.act(Activity.NONE) }
                .then(Go({ show.around(it, cutSpot(20.5f).first) }, 0.15, { cutSpot(20.5f).second }))
                .then(Clip(Activity.CUT_LOOSE, listOf<Pair<Int, (Hand) -> Unit>>(CUT to { _ -> cut() }, SLAP to { _ -> slap() })))
                .then { h ->
                    // After the slap: a step to the left of where they stood, then away
                    val y = Math.toRadians(h.p.yRot.toDouble() - 90.0)
                    val f = Math.toRadians(h.p.yRot.toDouble())
                    val x = h.p.x - sin(y) * CUT_LEFT - sin(f) * CUT_AHEAD
                    val z = h.p.z + cos(y) * CUT_LEFT + cos(f) * CUT_AHEAD
                    h.p.setPos(x, Ground.stand(level, x, z, h.p.y + 0.5), z)
                    runForGate(h)
                }
        }
        smashAt = 50
        show.wagon?.flag(Wagon.FLAG_BRAKE, true)
    }

    /** Where to stand at the horse's right shoulder, [out] px out (CrewMoves.CUT_LOOSE_X), facing it, and the yaw. */
    private fun cutSpot(out: Float): Pair<Vec3, Float> {
        val h = show.horse ?: return show.ground(-20f, -90f) to show.yaw(0f)
        return show.horsePoint(-out, CUT_Z) to Mth.wrapDegrees(h.yRot - 90f)
    }

    /** The traces cut through: the horse's loose of the wagon. */
    private fun cut() {
        show.horse?.flag(DraughtHorse.FLAG_CUT, true)
        show.wagon?.flag(Wagon.FLAG_CUT, true)
        show.harnessed = false
    }

    /** A slap on the rump: off it bolts, away down the road from the band. */
    private fun slap() {
        val h = show.horse ?: return
        bolting = 0
        boltSpeed = 0.0
        // Away from the band, on down the road the way it faced
        boltYaw = Mth.wrapDegrees(h.yRot + (25f * side).toFloat())
        h.move(DraughtHorse.Move.BOLT)
    }

    private fun taken() {
        val w = show.wagon
        // The horse cut down where it stands, the driver running at the blow
        val c = cutter
        if (horseDead && c != null && !c.mob.isRemoved && hitAt < 0) {
            val (at, yaw) = cutSpot(26f)
            if (c.mob.position().distanceTo(at) < 1.5 || t > 80) {
                c.mob.navigation.stop()
                c.mob.lookControl.setLookAt(show.horse ?: c.mob)
                c.mob.yBodyRot = yaw
                c.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                sound(c.mob.position().add(0.0, 1.3, 0.0), SoundEvents.PLAYER_ATTACK_STRONG, 0.8f, 0.8f)
                hitAt = t + 4
            } else if (t % 20 == 0) c.mob.navigation.moveTo(at.x, at.y, at.z, 1.1)
        }
        if (horseDead && hitAt > 0) {
            val h = show.horse
            when (t) {
                hitAt -> {
                    h?.move(DraughtHorse.Move.HIT, again = true)
                    show.driver?.let { d ->
                        d.clear()
                        runForGate(d)
                    }
                }
                hitAt + 16 -> h?.move(DraughtHorse.Move.COLLAPSE, again = true)
                hitAt + 80 -> {
                    h?.move(DraughtHorse.Move.LIE)
                    h?.flag(DraughtHorse.FLAG_DEAD, true)
                    h?.flag(DraughtHorse.FLAG_CUT, true)
                }
            }
        }
        // The load smashed and carried off, piece by piece, by the band at the wagon's side
        if (w != null && t >= smashAt && shoveAt < 0) {
            val k = SMASH_ORDER.firstOrNull { w.piece(it) != null }
            if (k == null || t > TAKE_MOST) {
                shoveAt = t + 30
                for ((i, m) in band.filter { it.vindicator || band.none { b -> b.vindicator } }.withIndex()) {
                    m.spot = show.ground((verge * 26.0).toFloat(), -36f + 20f * (i % 3))
                    m.mob.navigation.moveTo(m.spot.x, m.spot.y, m.spot.z, 1.0)
                }
            } else {
                smash(w, k)
                smashAt = t + 10 + random.nextInt(6)
            }
        }
        // Over it goes, shoved from the far side, and lies there a wreck
        if (w != null && shoveAt >= 0 && t == shoveAt) {
            for (m in band) if (m.vindicator) m.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
            w.flag(Wagon.FLAG_MIRROR, verge < 0)
            w.tip()
            Bodies.sound(level, show.wagonAt().add(0.0, 1.0, 0.0), "overturn", 1.6f, 1f)
            tipAt = t
        }
        if (tipAt >= 0) {
            val k = t - tipAt
            if (horseDead) lieDown(k)
            if (k == TIP) Bodies.sound(level, show.wagonAt(), "wheel_snap", 1.2f, 1f)
            if (k == TIP + 2) wrecked()
        }
        for (m in band) if (!m.fleeing && m !== cutter) face(m)
    }

    /** One piece of the load smashed open and carried off: its sound, its spill, a sack into a waylayer's hand. */
    private fun smash(w: Wagon, k: Int) {
        val good = w.piece(k) ?: return
        val left = (0 until Wagon.SLOTS).map { if (it == k) null else w.piece(it) }
        w.load(left)
        val slot = SLOT_XZ[k]
        val at = w.point(slot[0], -24f - (if (k >= 12) 8f else 0f), slot[1])
        val pool = band.filter { it.vindicator && !it.fleeing }.ifEmpty { band.filter { !it.fleeing } }
        val m = if (pool.isEmpty()) null else pool[(t / 7) % pool.size]
        if (m != null) {
            val flank = show.ground(-(side.toFloat()) * 26f, slot[1])
            if (m.mob.position().distanceTo(flank) > 1.2) m.mob.navigation.moveTo(flank.x, flank.y, flank.z, 1.0)
            m.mob.lookControl.setLookAt(at)
            m.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
        }
        Bodies.sound(level, at, kind(good), 1f, 0.9f + random.nextFloat() * 0.2f)
        good.item?.let { level.sendParticles(ItemParticleOption(ParticleTypes.ITEM, it), at.x, at.y, at.z, 10, 0.25, 0.15, 0.25, 0.08) }
        // Whoever's turn it is to carry something goes with a sack in hand
        band.firstOrNull { it.carry.isNotEmpty() && it.mob.offhandItem.isEmpty }?.mob?.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.BUNDLE))
    }

    /** The sound a piece of [g] makes broken open: crates smashed, sacks ripped, bottles breaking. */
    private fun kind(g: Good): String = when (g) {
        Good.BOTTLE, Good.HONEY -> "bottles"
        Good.WHEAT, Good.POTATO, Good.FLOUR, Good.COAL, Good.FLINT -> "rip"
        else -> "smash"
    }

    /** The dead horse dragged over with the wagon: to where it lies by the wreck, over the tip's ticks. */
    private fun lieDown(k: Int) {
        val h = show.horse ?: return
        val w = show.wagon ?: return
        if (k > TIP) return
        val m = if (verge < 0) -1f else 1f
        val to = w.point(WRECK_HORSE_X * m, 0f, WRECK_HORSE_Z)
        val toYaw = Mth.wrapDegrees(w.yRot - WRECK_HORSE_YAW * m)
        val f = k / TIP.toFloat()
        val e = f * f
        val x = Mth.lerp(e.toDouble(), h.x, to.x)
        val z = Mth.lerp(e.toDouble(), h.z, to.z)
        h.set(Vec3(x, Ground.stand(level, x, z, h.y + 0.5), z), Mth.rotLerp(e, h.yRot, toYaw))
    }

    /** The wagon's gone over: a wreck now, as the records have it, and the band makes off. */
    private fun wrecked() {
        if (swapped) return
        swapped = true
        val w = show.wagon ?: return finishTaking()
        val rec = CaravanData.get(level).wrecks.lastOrNull { it.caravan == id && !it.pile }
        if (rec != null) {
            val wreck = Bodies.WRECK.get().create(level, EntitySpawnReason.EVENT)
            if (wreck != null) {
                wreck.wreck = rec.id
                wreck.snapTo(w.x, w.y, w.z, w.yRot, 0f)
                // The arrows went into the side facing the band: gone over onto that side, they're under it now
                val arrows = if ((side > 0) == (verge > 0)) 0 else w.arrows
                var flags = Wreck.FLAG_FRESH or (if (verge < 0) Wreck.FLAG_MIRROR else 0) or (arrows shl Wreck.ARROWS)
                if (horseDead) flags = flags or Wreck.FLAG_HORSE or ((show.horse?.coat ?: 0) shl Wreck.COAT)
                wreck.set(rec.lots.map { if (it.count > 0) it.good else null }, flags)
                if (level.addFreshEntity(wreck)) WreckShows.adopt(level, rec.id, wreck)
            }
        }
        show.dropWagon(horseToo = horseDead)
        finishTaking()
    }

    private fun finishTaking() {
        for (m in band) runFor(m)
        show.running()
        phase = Phase.RUN
        t = 0
        val d = show.driver
        if (d != null && !driverRunning) {
            d.clear()
            runForGate(d)
        }
    }

    // ---- After: the band making off, the horse bolting, the driver running, the guards limping ----

    /** [m] makes for the camp (or just away, off the road on its side), gone once nobody sees. */
    private fun runFor(m: Man) {
        if (m.fleeing) return
        m.fleeing = true
        m.mob.target = null
        (m.mob as? Pillager)?.setChargingCrossbow(false)
        val camp = camp(level, id)
        val (ox, oz) = outward()
        val from = m.mob.position()
        val away = if (camp != null && camp.distSqr(BlockPos.containing(from)) < 300.0 * 300.0) Vec3.atBottomCenterOf(camp)
        else from.add(ox * 40.0, 0.0, oz * 40.0)
        m.away = away
        // Not all at once, but the band breaks
        val dir = away.subtract(from).let { if (it.length() > 24.0) it.normalize().scale(24.0) else it }
        val go = from.add(dir)
        m.mob.navigation.moveTo(go.x, go.y, go.z, FLEE_SPEED)
        if (m.vindicator) sound(from.add(0.0, 1.5, 0.0), SoundEvents.VINDICATOR_CELEBRATE, 0.7f, 0.9f + random.nextFloat() * 0.2f)
    }

    private fun flee() {
        val iter = band.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            if (!m.fleeing) continue
            if (m.real && m.mob.target != null) continue
            val mob = m.mob
            val gone = flat(mob.position(), show.wagonAt()) > FLEE_GONE && !watching(mob.position(), 64.0)
            if (gone || mob.tickCount > FLEE_MOST && !watching(mob.position(), 32.0) || !mob.isAlive) {
                if (mob.isAlive) mob.discard()
                iter.remove()
                continue
            }
            if (t % 30 == 0 || mob.navigation.isDone) {
                val away = m.away ?: continue
                val from = mob.position()
                val dir = away.subtract(from).let { if (it.length() > 24.0) it.normalize().scale(24.0) else it }
                val go = from.add(dir)
                mob.navigation.moveTo(go.x, go.y, go.z, FLEE_SPEED)
            }
        }
    }

    /** The bolting horse, galloping off down the road, gone once out of sight. */
    private fun horse() {
        val h = show.horse ?: return
        if (bolting < 0) return
        bolting++
        boltSpeed = min(BOLT_SPEED, boltSpeed + BOLT_SPEED / 4.0)
        if (bolting < 2) return
        val r = Math.toRadians(boltYaw.toDouble())
        val x = h.x - sin(r) * boltSpeed
        val z = h.z + cos(r) * boltSpeed
        val g = Ground.at(level, x, z, h.y)
        if (g.isNaN()) {
            // A wall: it veers off
            boltYaw = Mth.wrapDegrees(boltYaw + 35f * (if (random.nextBoolean()) 1f else -1f))
            return
        }
        h.set(Vec3(x, h.y + Mth.clamp(g - h.y, -0.4, 0.5), z), boltYaw)
        if (bolting % 30 == 0) boltYaw = Mth.wrapDegrees(boltYaw + (random.nextFloat() - 0.5f) * 20f)
        val far = flat(h.position(), show.wagonAt()) > BOLT_GONE
        if ((far && !watching(h.position(), 64.0)) || bolting > BOLT_MOST) {
            show.dropHorse()
            bolting = -1
        }
    }

    /** [h] runs for the gate, as fast as they can, telling it there. */
    private fun runForGate(h: Hand) {
        if (driverRunning) return
        driverRunning = true
        h.hold(false)
        h.p.act(Activity.FLEE)
        runStep(h)
    }

    private fun runStep(h: Hand) {
        val leg = show.leg
        val along = show.way.nearest(h.p.x, h.p.z)
        val to = show.way.at(min(leg.gate, along + RUN_AHEAD))
        h.p.navigation.moveTo(to.x, to.y, to.z, RUN_SPEED)
    }

    /** The running driver: on along the road to the gate (a hop ahead now and then, unseen), there telling it, then sitting out the day by it. */
    private fun run() {
        val d = show.driver ?: return
        val p = d.p
        if (p.isRemoved) {
            show.hands.remove(d)
            return
        }
        if (!driverRunning) runForGate(d)
        val leg = show.leg
        val gate = show.way.at(leg.gate)
        val there = flat(p.position(), gate) < GATE_NEAR
        if (!there) {
            if (t % 20 == 0 || p.navigation.isDone) runStep(d)
            if (p.activity != Activity.FLEE) p.act(Activity.FLEE)
            // Far behind where the record has them, and nobody near: a hop ahead
            val record = show.view.now
            val along = show.way.nearest(p.x, p.z)
            val nobody = level.players().none { !it.isSpectator && it.distanceToSqr(p) < HOP_CLEAR * HOP_CLEAR }
            if (t % 20 == 0 && nobody && (record - along > HOP_BEHIND || due)) {
                val at = show.way.at(if (due) leg.gate else record)
                p.snapTo(at.x, Ground.stand(level, at.x, at.z, at.y + 0.5), at.z, p.yRot, 0f)
                p.navigation.stop()
            }
            return
        }
        if (atGate < 0) {
            atGate = t
            p.navigation.stop()
            // Telling the guards at the gate, the road behind them
            val (hx, hz) = show.way.heading(leg.gate)
            p.face(Show.yawOf(hx, hz))
            p.act(Activity.RECOUNT)
            p.say("driver_took", force = true, hearing = 24.0)
        }
        if (t - atGate == RECOUNT_FOR) p.act(Activity.CATCH_BREATH)
        if (due) handOver(d)
    }

    /** The record has the driver at the gate now: they stay there as the gate's driver, to be spoken to. */
    private fun handOver(d: Hand) {
        val p = d.p
        val driver = Caravans.drivers(level, p.blockPosition(), 32.0).firstOrNull { it.caravan == id }
        show.hands.remove(d)
        if (driver == null) {
            p.director = null
            Bodies.stray(p)
            return
        }
        Bodies.gate(GateShow(level, driver, p))
    }

    /** The record's over: the driver is at the gate as far as it's concerned. */
    fun ended() {
        due = true
    }

    /** The hurt guards: kneeling a while, then limping in after the driver, gone once nobody sees. */
    private fun limp() {
        for (h in show.hands.toList()) {
            if (h.driver) continue
            val left = (limping[h] ?: HURT_KNEEL) - 1
            limping[h] = left
            if (h.p.isRemoved) {
                show.hands.remove(h)
                continue
            }
            if (left == 0) {
                h.clear()
                h.hold(false)
                h.p.act(Activity.WOUNDED)
            }
            if (left <= 0 && (-left) % 40 == 1) {
                val along = show.way.nearest(h.p.x, h.p.z)
                val to = show.way.at(min(show.leg.gate, along + 12.0))
                h.p.navigation.moveTo(to.x, to.y, to.z, LIMP_SPEED)
            }
            val seen = watching(h.p.position(), 40.0)
            if ((left < -200 && !seen) || left < -LIMP_MOST) {
                h.p.discard()
                show.hands.remove(h)
            }
        }
    }

    // ---- Heard from Bodies ----

    /** Whether [e] is one of this band. */
    fun owns(e: Entity): Boolean = band.any { it.mob === e }

    /** Whether [e] is one of this band still in the fight (not running for the camp). */
    fun fightingMan(e: Entity): Boolean = band.any { it.mob === e && !it.fleeing }

    /**
     * Whether [target] (one of the crew or the band) may take [source]'s blow: in a real fight, between the guards, the
     * band and the players; never the driver. A player striking one of the band makes the fight real.
     */
    fun allows(target: LivingEntity, source: DamageSource): Boolean {
        val by = source.entity
        val man = band.firstOrNull { it.mob === target }
        if (man != null) {
            if (by is Townsperson && show.hands.any { it.p === by }) return real && !resolved
            // Someone else joins in (a player, their wolf, a golem): from now on it's for real
            if (by != null && band.none { it.mob === by }) {
                if (fighting) goReal() else arm(man)
            }
            return true
        }
        val hand = show.hands.firstOrNull { it.p === target } ?: return true
        if (hand.driver) return false
        return real && !resolved && band.any { it.mob === by }
    }

    /** One of the band fell. */
    fun died(e: Entity) {
        if (band.any { it.mob === e }) kills++
    }

    /** What one of the band drops as they fall: the sack they carried off, if they had one, marked as off the wagon. */
    fun drops(e: Entity): List<ItemStack> {
        val m = band.firstOrNull { it.mob === e } ?: return listOf()
        val out = ArrayList<ItemStack>()
        for ((g, n) in m.carry) {
            val stack = Caravans.dropped(level, id, g, n)
            if (!stack.isEmpty) out.add(stack)
        }
        m.carry.clear()
        return out
    }

    /**
     * Its bodies go with nobody left near (or the server stopping, [quiet]): a fight still on is finished by the dice,
     * from the numbers still standing if a player had joined it.
     */
    fun abandon(quiet: Boolean) {
        if (!quiet && !resolved && strike != null) {
            show.resolving = true
            try {
                if (real) {
                    val b = band.count { it.mob.isAlive && !it.fleeing }.toDouble()
                    val g = show.hands.count { !it.driver && it.p.isAlive } + 0.3
                    val waylaid = b > 0 && random.nextDouble() < b / (b + 1.5 * g)
                    Caravans.fought(id, waylaid, kills, strike?.horse == Caravans.Horse.CUT_DOWN)
                } else {
                    Caravans.played(id)
                }
            } finally {
                show.resolving = false
            }
            resolved = true
        }
        for (m in band) m.mob.discard()
        band.clear()
    }

    /** Whether anyone's near enough to [at] to see it go: a player within [range] looking that way (or very near). */
    private fun watching(at: Vec3, range: Double): Boolean = level.players().any { player ->
        if (player.isSpectator) return@any false
        val to = at.subtract(player.eyePosition)
        val d = to.length()
        d < range && (d < 12.0 || player.lookAngle.dot(to.scale(1.0 / d)) > 0.45)
    }

    private fun sound(at: Vec3, event: SoundEvent, volume: Float, pitch: Float) {
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.HOSTILE, volume, pitch)
    }

    companion object {
        /** The band's tag, kept if one is ever saved by mistake, so it goes again when loaded. */
        const val TAG = "guildmark.waylayer"

        /** A fight nobody joins lasts 20 to 40 seconds. */
        private const val FIGHT_LEAST = 400
        private const val FIGHT_MOST = 800

        /** How long they have to run in, and how fast; how long a crossbow takes to draw. */
        private const val RUSH_MOST = 220
        private const val RUSH_SPEED = 1.25
        private const val CHARGE = 25

        /** In a real fight: how near a player must be to count as there, and how long without one before the band has its way. */
        private const val WATCHED = 8.0
        private const val UNWATCHED = 100

        /** The horse's rear (animations/draught_horse/rear: 44 ticks). */
        private const val REAR_TICKS = 44

        /** The longest the crew take to climb back up after beating them off. */
        private const val BEATEN_MOST = 400

        /** The longest the band smashes and takes before it shoves the wagon over; the tip's ticks (WagonMesh). */
        private const val TAKE_MOST = 220
        private const val TIP = 20

        /** The order the load comes off the bed: the top first, then the back rows forward (as the stand's unloading). */
        val SMASH_ORDER = intArrayOf(15, 14, 13, 12, 9, 10, 11, 6, 7, 8, 3, 4, 5, 0, 1, 2)

        /** WagonMesh.SLOTS' x and z (px), for the server (WagonMesh is the client's). */
        val SLOT_XZ: List<FloatArray> = listOf(
            floatArrayOf(-6.7f, -36f), floatArrayOf(0f, -36f), floatArrayOf(6.7f, -36f),
            floatArrayOf(-6.7f, -27.6f), floatArrayOf(0f, -27.6f), floatArrayOf(6.7f, -27.6f),
            floatArrayOf(-6.7f, -19.2f), floatArrayOf(0f, -19.2f), floatArrayOf(6.7f, -19.2f),
            floatArrayOf(-6.7f, -10.8f), floatArrayOf(0f, -10.8f), floatArrayOf(6.7f, -10.8f),
            floatArrayOf(-3.35f, -31.8f), floatArrayOf(3.35f, -23.4f), floatArrayOf(-3.35f, -15f), floatArrayOf(3.4f, -31.6f),
        )

        /** WagonMesh.WRECK_HORSE: where a dead horse lies by the wreck (px, the wagon's space) and its yaw. */
        private const val WRECK_HORSE_X = -30f
        private const val WRECK_HORSE_Z = -96f
        private const val WRECK_HORSE_YAW = 76f

        /** CrewMoves.CUT_LOOSE: the cut and the slap's ticks, where they stand (z, px, horse space), and the step left after. */
        private const val CUT = 20
        private const val SLAP = 39
        private const val CUT_Z = -14f
        private const val CUT_LEFT = 24.6 / 16.0
        private const val CUT_AHEAD = -1.0 / 16.0

        /** The bolting horse's speed (blocks a tick), and how far off or how long before it's gone. */
        private const val BOLT_SPEED = 0.38
        private const val BOLT_GONE = 40.0
        private const val BOLT_MOST = 600

        /** The band running off: how fast, how far before they're gone unseen, and how long at most. */
        private const val FLEE_SPEED = 1.3
        private const val FLEE_GONE = 24.0
        private const val FLEE_MOST = 1600

        /** The running driver: how far ahead along the road they make for, how fast; a hop when this far behind with nobody within [HOP_CLEAR]. */
        private const val RUN_AHEAD = 16.0
        private const val RUN_SPEED = 1.25
        private const val HOP_BEHIND = 24.0
        private const val HOP_CLEAR = 80.0
        private const val GATE_NEAR = 4.0
        private const val RECOUNT_FOR = 80

        /** The hurt guards: kneeling this long, then limping this slowly, gone after this long at most. */
        private const val HURT_KNEEL = 160
        private const val LIMP_SPEED = 0.45
        private const val LIMP_MOST = 2400

        /** A show whose bodies come upon a waylaid wagon's driver already running for the gate: just them. */
        fun runnerOnly(show: Show): Waylay {
            val w = Waylay(show, null)
            w.resolved = true
            w.phase = Phase.RUN
            show.driver?.let { w.runForGate(it) }
            return w
        }

        /** Where caravan [caravan]'s band has its camp, or null. */
        fun camp(level: ServerLevel, caravan: Long): BlockPos? {
            val c = CaravanData.get(level).caravan(caravan) ?: return null
            return OutlawCamps.get(level).band(c.band)?.pos
        }
    }
}

/** [what] played through ([ticks] long), with [marks] done on the ticks they name. */
internal class Clip(private val what: Activity, private val marks: List<Pair<Int, (Hand) -> Unit>>, private val ticks: Int = what.ticks) : Step() {
    private var t = 0
    override fun start(h: Hand) = h.p.act(what)
    override fun tick(h: Hand): Boolean {
        t++
        for ((at, block) in marks) if (at == t) block(h)
        return t >= ticks
    }
}
