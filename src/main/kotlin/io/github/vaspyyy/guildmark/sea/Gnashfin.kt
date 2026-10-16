package io.github.vaspyyy.guildmark.sea

import io.github.vaspyyy.guildmark.ship.Cog
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A Gnashfin: a pike-like hunter of the open sea, as long as a man is tall again, with a jaw of needle fangs. They run
 * in packs (see [SeaHunts]) and hunt whoever is out on the water: the pack closes in and circles, dorsal fins cutting
 * the surface, then one after another they leap clean across a ship's deck to snap at whoever's on it and knife back
 * in on the far side. Lying still, a ship has them at her hull, gnawing at her planks below the waterline.
 *
 * Struck in mid-leap, one comes down on the deck and flounders there, easy prey, until it flops back overboard.
 * Hurt badly, or with half its pack dead, it turns and flees into the deep. A ship with a Maelgrasp's beak on her
 * stem, they leave alone.
 *
 * The server swims it and moves it through its leaps; clients pose it from its mode and the leap's start.
 */
class Gnashfin(type: EntityType<out Gnashfin>, level: Level) : Monster(type, level) {
    enum class Mode { SWIM, CIRCLE, RUN_UP, LEAP, STRANDED, GNAW, BITE, FLEE }

    // ---- On the server ----

    private var packId: UUID? = null
    private var victimId: UUID? = null
    private var modeTicks = 0
    /** Its place on the ring round its quarry: the angle (radians), how far out, and which way round it swims. */
    private var ringAngle = 0f
    private var ringOut = 0f
    private var ringTurn = 1f
    /** A leap: where it leaves the water and comes down, in the ship's frame (or the world's), and how high it goes. */
    private var leapShipId = -1
    private var leapFrom = Vec3.ZERO
    private var leapTo = Vec3.ZERO
    private var leapTop = 0.0
    private var leapStart = 0L
    private var bitten = false
    /** Where on a ship it lies floundering, or gnaws, in her frame, and which way it faces against her. */
    private var spot = Vec3.ZERO
    private var spotYaw = 0f
    private var nextFlop = 0
    private var nextBite = 0
    private var finSoundAt = 0
    private var reloaded = false

    // ---- On a client: how it moves, eased from tick to tick (last tick's and this one's) ----

    var swimO = 0f
        private set
    var swim = 0f
        private set
    var bendO = 0f
        private set
    var bend = 0f
        private set
    var jawO = 0f
        private set
    var jaw = 0f
        private set
    var strandedO = 0f
        private set
    var stranded = 0f
        private set
    var gnawO = 0f
        private set
    var gnaw = 0f
        private set
    var glowO = 0f
        private set
    var glow = 0f
        private set

    init {
        xpReward = 8
        isNoGravity = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_MODE, Mode.SWIM.ordinal.toByte())
        builder.define(DATA_LEAP_AT, 0)
    }

    var mode: Mode
        get() = MODES[entityData.get(DATA_MODE).toInt().coerceIn(0, MODES.size - 1)]
        private set(value) {
            if (value != mode) {
                entityData.set(DATA_MODE, value.ordinal.toByte())
                modeTicks = 0
            }
        }

    /** How far through its leap it is, 0 to 1, or -1 when it isn't leaping (on either side). */
    fun leap(partial: Float): Float {
        if (mode != Mode.LEAP) return -1f
        val since = ((level().gameTime and 0x3FFFFFFF).toInt() - entityData.get(DATA_LEAP_AT)) + partial
        return (since / LEAP_TICKS).coerceIn(0f, 1f)
    }

    /** Toughens it for land [tier], as everything out in the wilds is: more health and a harder bite each tier past the first. */
    fun scaleTo(tier: Int) {
        val extra = (tier - 1).coerceAtLeast(0)
        if (extra == 0) return
        getAttribute(Attributes.MAX_HEALTH)?.addOrReplacePermanentModifier(AttributeModifier(TIER_HEALTH, 0.35 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        getAttribute(Attributes.ATTACK_DAMAGE)?.addOrReplacePermanentModifier(AttributeModifier(TIER_DAMAGE, 0.15 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        health = maxHealth
    }

    /** Joins it to [pack], hunting [victim]. */
    fun hunt(pack: UUID, victim: Player) {
        packId = pack
        victimId = victim.uuid
        ringAngle = random.nextFloat() * Mth.TWO_PI
        ringOut = random.nextFloat() * 3f - 1.5f
        ringTurn = if (random.nextBoolean()) 1f else -1f
        mode = Mode.SWIM
    }

    // ---- Every tick ----

    override fun tick() {
        super.tick()
        if (level().isClientSide) clientTick()
    }

    /** How it's seen to move: how hard it swims, how it bends into its turns, its jaw, its lights. */
    private fun clientTick() {
        swimO = swim
        bendO = bend
        jawO = jaw
        strandedO = stranded
        gnawO = gnaw
        glowO = glow
        val m = mode
        val dx = x - xo
        val dy = y - yo
        val dz = z - zo
        val speed = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
        val wantSwim = when (m) {
            Mode.SWIM, Mode.CIRCLE, Mode.RUN_UP, Mode.BITE, Mode.FLEE -> (speed / 0.5f).coerceIn(0.3f, 1f)
            Mode.LEAP -> 0.15f
            Mode.GNAW -> 0.4f
            Mode.STRANDED -> 0f
        }
        swim += (wantSwim - swim) * 0.15f
        // Turning to its right (its heading going up), its nose swings right
        val turn = Mth.wrapDegrees(yRot - yRotO)
        val wantBend = if (m == Mode.STRANDED || m == Mode.GNAW) 0f else (turn / BEND_TURN).coerceIn(-1f, 1f)
        bend += (wantBend - bend) * 0.25f
        val wantJaw = when (m) {
            // Gaping as it flies at whoever it's leaping for, snapping shut as it passes them
            Mode.LEAP -> {
                val u = leap(0f)
                val from = BITE_FROM.toFloat()
                val to = BITE_TO.toFloat()
                if (u < from - 0.1f) smooth((u - 0.05f) / (from - 0.15f)) else if (u < to - 0.06f) 1f else 0.05f
            }
            Mode.BITE -> 1f
            Mode.RUN_UP -> 0.55f
            Mode.FLEE -> 0.05f
            else -> 0.08f + 0.06f * sin(tickCount * 0.11f + id * 0.7f)
        }
        jaw += (wantJaw - jaw) * (if (wantJaw < jaw) 0.65f else 0.35f)
        stranded += ((if (m == Mode.STRANDED) 1f else 0f) - stranded) * 0.3f
        gnaw += ((if (m == Mode.GNAW) 1f else 0f) - gnaw) * 0.2f
        // Its lights burn by night, in a storm and in deep water; and flare as it strikes
        val level = level()
        var wantGlow = if (level.isDarkOutside) 0.8f else if (level.getThunderLevel(1f) > 0.3f) 0.65f else 0.3f
        if (m == Mode.RUN_UP || m == Mode.LEAP || m == Mode.BITE) wantGlow += 0.25f
        if (m == Mode.FLEE || isDeadOrDying) wantGlow = 0f
        glow += (wantGlow.coerceIn(0f, 1f) - glow) * 0.06f
        clientEffects?.invoke(this)
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        // A pack doesn't outlast the world being saved and loaded: it was hunting someone who may be long gone
        if (reloaded) {
            discard()
            return
        }
        modeTicks++
        // Somehow out of the water, swimming in the air: it won't last
        if (!isInWater && (mode == Mode.SWIM || mode == Mode.CIRCLE || mode == Mode.FLEE || mode == Mode.BITE || mode == Mode.RUN_UP)) {
            if (++dry > DRY_TICKS) {
                discard()
                return
            }
            deltaMovement = deltaMovement.add(0.0, -0.06, 0.0)
        } else {
            dry = 0
        }
        val victim = victim(level)
        if (victim == null && mode != Mode.FLEE && mode != Mode.LEAP && mode != Mode.STRANDED) flee()
        if (mode != Mode.FLEE && mode != Mode.STRANDED && mode != Mode.LEAP && health < maxHealth * FLEE_HEALTH) flee()
        when (mode) {
            Mode.SWIM, Mode.CIRCLE -> circle(level, victim ?: return)
            Mode.RUN_UP -> runUp(level, victim)
            Mode.LEAP -> leapOn(level)
            Mode.STRANDED -> flounder(level)
            Mode.GNAW -> gnaw(level, victim)
            Mode.BITE -> lunge(level, victim)
            Mode.FLEE -> fleeOn(level)
        }
        yBodyRot = yRot
        yHeadRot = yRot
    }

    /** Who it's hunting, while they're still out there to be hunted. */
    private fun victim(level: ServerLevel): Player? {
        val id = victimId ?: return null
        val player = level.getPlayerByUUID(id) ?: return null
        if (!player.isAlive || player.isSpectator || player.distanceToSqr(this) > GIVE_UP * GIVE_UP) return null
        if (!atSea(player)) {
            if (++ashore > ASHORE_TICKS) return null
        } else {
            ashore = 0
        }
        return player
    }

    private var ashore = 0
    private var dry = 0

    private fun atSea(player: Player): Boolean = player.vehicle is Cog || player.vehicle is AbstractBoat || player.isInWater

    // ---- Circling ----

    private fun circle(level: ServerLevel, victim: Player) {
        val ship = victim.vehicle as? Cog
        val centre = ship?.position() ?: victim.position()
        val radius = (if (ship != null) SHIP_RING else SWIMMER_RING) + ringOut
        // Closing in, then round and round, keeping pace with a ship under way
        val far = horizontal(position(), centre)
        if (far > radius + 4.0) {
            mode = Mode.SWIM
            ringAngle = atan2((z - centre.z), (x - centre.x)).toFloat()
        } else if (mode == Mode.SWIM) {
            mode = Mode.CIRCLE
        }
        ringAngle += ringTurn * CIRCLE_SPEED / radius.toFloat()
        val surface = Seas.surface(level, x, z) ?: (level.seaLevel - 0.1)
        val target = Vec3(centre.x + cos(ringAngle) * radius, surface - FIN_DEPTH, centre.z + sin(ringAngle) * radius)
        swimTo(level, target, if (mode == Mode.SWIM) CLOSE_SPEED else RING_SPEED)
        if (mode == Mode.CIRCLE && tickCount >= finSoundAt) {
            finSoundAt = tickCount + 40 + random.nextInt(60)
            level.playSound(null, x, surface, z, SeaRegistry.GNASHFIN_FIN.get(), SoundSource.HOSTILE, 0.8f, 0.9f + random.nextFloat() * 0.2f)
        }
        if (mode != Mode.CIRCLE || modeTicks < SETTLE) return
        val pack = SeaHunts.pack(packId) ?: return
        // A ship lying still has them at her planks; otherwise they take turns at whoever's aboard
        if (ship != null && SeaHunts.lyingStill(ship) && pack.mayGnaw(id)) {
            startGnaw(ship)
            return
        }
        if (!pack.mayStrike(level.gameTime, random)) return
        if (ship != null) {
            val crew = Seas.crew(ship)
            if (crew.isNotEmpty()) planLeap(ship, crew[random.nextInt(crew.size)])
        } else if (victim.vehicle is AbstractBoat) {
            planLeap(null, victim)
        } else {
            mode = Mode.BITE
        }
    }

    // ---- Leaping a deck ----

    /** Picks where to leave the water so its arc carries it over [prey], and swims for it. */
    private fun planLeap(ship: Cog?, prey: Player) {
        bitten = false
        if (ship != null) {
            val station = ship.stationOf(prey)
            val sx = station?.x ?: 0.0
            val sy = station?.y ?: 1.2
            val sz = station?.z ?: 0.0
            val here = ship.worldToLocal(position())
            val side = if (abs(here.x) > 0.5) sign(here.x) else if (random.nextBoolean()) 1.0 else -1.0
            leapShipId = ship.id
            leapFrom = Vec3(side * LEAP_OUT, -0.25, sz + random.nextDouble() * 2.0 - 1.0)
            leapTo = Vec3(-side * LEAP_OUT, -0.25, sz + random.nextDouble() * 2.0 - 1.0)
            // Over the head of whoever it's leaping at, close enough to snap at them on the way
            leapTop = sy + LEAP_CLEAR
            spot = Vec3(sx, sy, sz)
        } else {
            leapShipId = -1
            val surface = prey.y
            val away = Vec3(prey.x - x, 0.0, prey.z - z).normalize()
            leapFrom = position()
            leapTo = Vec3(prey.x + away.x * LEAP_OUT, surface - 0.3, prey.z + away.z * LEAP_OUT)
            leapTop = prey.eyeY + 0.8
        }
        mode = Mode.RUN_UP
    }

    private fun runUp(level: ServerLevel, victim: Player?) {
        val ship = level.getEntity(leapShipId) as? Cog
        if (leapShipId >= 0 && (ship == null || victim == null)) {
            mode = Mode.SWIM
            return
        }
        val start = if (ship != null) ship.localToWorld(leapFrom.x, leapFrom.y, leapFrom.z) else leapFrom
        swimTo(level, start, LUNGE_SPEED)
        if (position().distanceTo(start) < 1.6 || (ship == null && modeTicks > 2)) {
            if (ship == null) leapFrom = position()
            beginLeap(level, true)
        } else if (modeTicks > RUN_UP_TICKS) {
            mode = Mode.CIRCLE
        }
    }

    /** Off it goes along its arc: bursting up out of the water, or heaving itself off a deck. */
    private fun beginLeap(level: ServerLevel, fromWater: Boolean) {
        mode = Mode.LEAP
        noPhysics = true
        leapStart = level.gameTime
        entityData.set(DATA_LEAP_AT, (level.gameTime and 0x3FFFFFFF).toInt())
        if (!fromWater) return
        level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_LEAP.get(), SoundSource.HOSTILE, 1f, 0.9f + random.nextFloat() * 0.2f)
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.3, z, 16, 0.4, 0.1, 0.4, 0.2)
        level.sendParticles(ParticleTypes.BUBBLE, x, y, z, 8, 0.3, 0.2, 0.3, 0.05)
    }

    /** Where along its arc it is [u] (0 to 1) of the way, in the world. */
    private fun arc(level: ServerLevel, u: Double): Vec3? {
        val mid = (leapFrom.y + leapTo.y) * 0.5
        val local = Vec3(
            Mth.lerp(u, leapFrom.x, leapTo.x),
            Mth.lerp(u, leapFrom.y, leapTo.y) + 4.0 * (leapTop - mid) * u * (1.0 - u),
            Mth.lerp(u, leapFrom.z, leapTo.z),
        )
        if (leapShipId < 0) return local
        val ship = level.getEntity(leapShipId) as? Cog ?: return null
        return ship.localToWorld(local.x, local.y, local.z)
    }

    private fun leapOn(level: ServerLevel) {
        val u = ((level.gameTime - leapStart).toDouble() / LEAP_TICKS).coerceIn(0.0, 1.0)
        val at = arc(level, u)
        if (at == null) {
            endLeap(level)
            return
        }
        moveThrough(at)
        if (!bitten && u in BITE_FROM..BITE_TO) {
            // Snapping at whoever its arc carries it past
            val near = level.getEntitiesOfClass(Player::class.java, boundingBox.inflate(BITE_REACH)) { it.isAlive && !it.isSpectator && !it.isCreative }
            val prey = near.minByOrNull { it.distanceToSqr(at) }
            if (prey != null) {
                bitten = true
                doHurtTarget(level, prey)
                level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_BITE.get(), SoundSource.HOSTILE, 1f, 0.9f + random.nextFloat() * 0.2f)
            } else if (u >= 0.5) {
                bitten = true
                level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_SNAP.get(), SoundSource.HOSTILE, 0.9f, 0.9f + random.nextFloat() * 0.2f)
            }
        }
        if (u >= 1.0) endLeap(level)
    }

    private fun endLeap(level: ServerLevel) {
        noPhysics = false
        level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_DIVE.get(), SoundSource.HOSTILE, 0.9f, 0.9f + random.nextFloat() * 0.2f)
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.3, z, 14, 0.4, 0.1, 0.4, 0.2)
        deltaMovement = deltaMovement.multiply(0.4, 0.2, 0.4)
        mode = Mode.CIRCLE
    }

    // ---- Knocked down on deck ----

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean {
        val struck = if (mode == Mode.STRANDED) damage * STRANDED_HURT else damage
        val hurt = super.hurtServer(level, source, struck)
        if (!hurt || !isAlive) return hurt
        val attacker = source.entity
        if (mode == Mode.LEAP && attacker is Player) knockDown(level)
        // Struck from the deck while it gnaws, it lets go
        if (mode == Mode.GNAW && attacker is Player && random.nextFloat() < 0.5f) mode = Mode.CIRCLE
        return hurt
    }

    /** Struck in the air: over a deck it comes down on the boards; anywhere else it only falls short into the sea. */
    private fun knockDown(level: ServerLevel) {
        val ship = level.getEntity(leapShipId) as? Cog
        noPhysics = false
        if (ship == null) {
            endLeap(level)
            return
        }
        val local = ship.worldToLocal(position())
        if (abs(local.x) > Seas.beam(local.z) - 0.2 || local.z !in -4.2..3.6) {
            endLeap(level)
            return
        }
        val z = local.z.coerceIn(-3.4, 3.2)
        spot = Vec3(local.x.coerceIn(-1.2, 1.2), Seas.deckAt(z), z)
        spotYaw = random.nextFloat() * 360f
        mode = Mode.STRANDED
        nextFlop = 0
        nextBite = BITE_EVERY
        level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_FLOP.get(), SoundSource.HOSTILE, 1.2f, 0.8f)
    }

    private fun flounder(level: ServerLevel) {
        val ship = level.getEntity(leapShipId) as? Cog
        if (ship == null) {
            mode = Mode.FLEE
            return
        }
        val at = ship.deckPoint(spot.x, spot.y, spot.z, 1f)
        moveThrough(at)
        yRot = ship.yRot + spotYaw
        xRot = 0f
        if (--nextFlop <= 0) {
            nextFlop = 7 + random.nextInt(9)
            level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_FLOP.get(), SoundSource.HOSTILE, 0.9f, 0.85f + random.nextFloat() * 0.3f)
            level.sendParticles(ParticleTypes.SPLASH, x, y + 0.2, z, 4, 0.4, 0.05, 0.4, 0.05)
        }
        // Snapping at any foot within reach of its jaws
        if (--nextBite <= 0) {
            nextBite = BITE_EVERY
            val near = level.getEntitiesOfClass(Player::class.java, boundingBox.inflate(0.9)) { it.isAlive && !it.isSpectator && !it.isCreative }
            near.firstOrNull()?.let {
                it.hurtServer(level, damageSources().mobAttack(this), STRANDED_BITE)
                level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_SNAP.get(), SoundSource.HOSTILE, 1f, 1.1f)
            }
        }
        if (modeTicks >= STRANDED_TICKS) {
            // A last heave, and it's over the side
            val side = if (spot.x >= 0.0) 1.0 else -1.0
            leapFrom = spot
            leapTo = Vec3(side * (LEAP_OUT - 1.0), -0.25, spot.z)
            leapTop = spot.y + 0.8
            beginLeap(level, false)
            bitten = true
        }
    }

    // ---- At the planks ----

    private fun startGnaw(ship: Cog) {
        leapShipId = ship.id
        val here = ship.worldToLocal(position())
        val side = if (here.x >= 0.0) 1.0 else -1.0
        val z = (here.z.coerceIn(-3.0, 2.6) + random.nextDouble() - 0.5).coerceIn(-3.2, 2.8)
        spot = Vec3(side * (Seas.beam(z) + GNAW_OFF), GNAW_HEIGHT, z)
        spotYaw = if (side > 0) 90f else -90f
        mode = Mode.GNAW
        nextBite = GNAW_EVERY
    }

    private fun gnaw(level: ServerLevel, victim: Player?) {
        val ship = level.getEntity(leapShipId) as? Cog
        val pack = SeaHunts.pack(packId)
        if (ship == null || victim == null || !SeaHunts.lyingStill(ship) || modeTicks > GNAW_TICKS) {
            pack?.doneGnawing(id)
            mode = Mode.CIRCLE
            return
        }
        val at = ship.localToWorld(spot.x, spot.y, spot.z)
        if (modeTicks < 30 && position().distanceTo(at) > 0.6) {
            // Still swimming in to her side
            swimTo(level, at, LUNGE_SPEED)
            return
        }
        moveThrough(at)
        // Facing her hull, nose up into the planks, worrying at them
        yRot = Mth.wrapDegrees(ship.yRot + spotYaw)
        xRot = -GNAW_PITCH
        if (--nextBite <= 0) {
            nextBite = GNAW_EVERY + random.nextInt(10)
            ship.strain(level, GNAW_DAMAGE)
            level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_GNAW.get(), SoundSource.HOSTILE, 1f, 0.9f + random.nextFloat() * 0.2f)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, PLANKS), x, y + 0.4, z, 6, 0.2, 0.2, 0.2, 0.05)
            level.sendParticles(ParticleTypes.BUBBLE, x, y, z, 6, 0.3, 0.2, 0.3, 0.05)
        }
    }

    // ---- At a swimmer ----

    private fun lunge(level: ServerLevel, victim: Player?) {
        if (victim == null || modeTicks > 60) {
            mode = Mode.CIRCLE
            return
        }
        swimTo(level, victim.position().add(0.0, 0.4, 0.0), LUNGE_SPEED)
        if (distanceToSqr(victim) < 1.8 * 1.8) {
            doHurtTarget(level, victim)
            level.playSound(null, x, y, z, SeaRegistry.GNASHFIN_BITE.get(), SoundSource.HOSTILE, 1f, 1f)
            mode = Mode.CIRCLE
        }
    }

    // ---- Away ----

    fun flee() {
        if (mode == Mode.FLEE) return
        noPhysics = false
        if (mode == Mode.GNAW) SeaHunts.pack(packId)?.doneGnawing(id)
        mode = Mode.FLEE
        ringAngle = random.nextFloat() * Mth.TWO_PI
    }

    private fun fleeOn(level: ServerLevel) {
        val surface = Seas.surface(level, x, z) ?: level.seaLevel.toDouble()
        val away = Vec3(x + cos(ringAngle) * 20.0, surface - 5.0, z + sin(ringAngle) * 20.0)
        swimTo(level, away, CLOSE_SPEED)
        if (modeTicks > FLEE_TICKS) discard()
    }

    // ---- Swimming ----

    /** Swims toward [target], steering as a fish does, and faces the way it goes. */
    private fun swimTo(level: ServerLevel, target: Vec3, speed: Double) {
        val to = target.subtract(position())
        val far = to.length()
        val want = if (far < 1.0e-3) Vec3.ZERO else to.scale(min(speed, far * 0.3 + 0.04) / far)
        var v = deltaMovement.add(want.subtract(deltaMovement).scale(STEER))
        // Never out of the water but in a leap: its back stays under, its fin at the surface
        val surface = Seas.surface(level, x, z)
        if (surface != null && y + v.y > surface - FIN_DEPTH) v = Vec3(v.x, min(v.y, surface - FIN_DEPTH - y), v.z)
        deltaMovement = v
        move(MoverType.SELF, v)
        if (horizontalCollision) ringTurn = -ringTurn
        face(v)
    }

    /** Puts it at [at] (moving through anything on the way), facing the way it went. */
    private fun moveThrough(at: Vec3) {
        val v = at.subtract(position())
        deltaMovement = v
        setPos(at)
        face(v)
    }

    private fun face(v: Vec3) {
        val flat = sqrt(v.x * v.x + v.z * v.z)
        if (flat > 1.0e-3) yRot = Mth.rotLerp(0.35f, yRot, (atan2(v.z, v.x) * Mth.RAD_TO_DEG).toFloat() - 90f)
        if (flat > 1.0e-3 || abs(v.y) > 1.0e-3) xRot = Mth.rotLerp(0.3f, xRot, (-atan2(v.y, max(flat, 1.0e-3)) * Mth.RAD_TO_DEG).toFloat().coerceIn(-60f, 60f))
    }

    // It steers itself: no gravity, no drag but its own, no current carrying it
    override fun travel(input: Vec3) {}
    override fun isPushedByFluid(): Boolean = false
    override fun canBreatheUnderwater(): Boolean = true
    override fun causeFallDamage(fallDistance: Double, multiplier: Float, source: DamageSource): Boolean = false
    override fun isPushable(): Boolean = false
    override fun pushEntities() {}
    override fun canBeLeashed(): Boolean = false
    override fun canUsePortal(ignorePassenger: Boolean): Boolean = false
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = true

    override fun die(source: DamageSource) {
        if (isRemoved || dead) return
        super.die(source)
        if (!dead) return
        if (mode == Mode.GNAW) SeaHunts.pack(packId)?.doneGnawing(id)
        (level() as? ServerLevel)?.let { SeaHunts.lost(it, packId, this) }
    }

    override fun tickDeath() {
        // Belly up, it sinks away; on a deck, it lies where it died
        if (!level().isClientSide && mode != Mode.STRANDED && isInWater) setPos(x, y - DEAD_SINK, z)
        super.tickDeath()
    }

    override fun getHurtSound(source: DamageSource): SoundEvent = SeaRegistry.GNASHFIN_HURT.get()
    override fun getDeathSound(): SoundEvent = SeaRegistry.GNASHFIN_DEATH.get()
    override fun playStepSound(pos: BlockPos, state: BlockState) {}

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putBoolean("hunting", true)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        reloaded = input.getBooleanOr("hunting", false)
    }

    companion object {
        private val DATA_MODE: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Gnashfin::class.java, EntityDataSerializers.BYTE)
        private val DATA_LEAP_AT: EntityDataAccessor<Int> = SynchedEntityData.defineId(Gnashfin::class.java, EntityDataSerializers.INT)
        private val MODES = Mode.entries.toTypedArray()
        private val PLANKS = Blocks.SPRUCE_PLANKS.defaultBlockState()
        private val TIER_HEALTH = SeaRegistry.id("gnashfin_tier_health")
        private val TIER_DAMAGE = SeaRegistry.id("gnashfin_tier_damage")

        /** On a client, the sea round it as it goes (its fin's wake, the water streaming off it in a leap), done each tick (see client/sea/SeaClient). */
        var clientEffects: ((Gnashfin) -> Unit)? = null

        fun createAttributes(): AttributeSupplier.Builder = createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 22.0)
            .add(Attributes.ATTACK_DAMAGE, 6.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.FOLLOW_RANGE, 48.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)

        /** Ticks a leap takes, out of the water and back in. */
        const val LEAP_TICKS = 24

        /** How far under the surface it swims (its fin breaks the surface). */
        private const val FIN_DEPTH = 0.5
        /** Degrees a tick it turns at, bent as far as it bends. */
        private const val BEND_TURN = 9f
        /** How far round a ship, or a swimmer, its pack circles. */
        private const val SHIP_RING = 8.5
        private const val SWIMMER_RING = 6.5
        /** Blocks a tick it swims: round the ring, closing in, and running in to leap or bite. */
        private const val RING_SPEED = 0.42
        private const val CLOSE_SPEED = 0.5
        private const val LUNGE_SPEED = 0.6
        /** Blocks a tick it keeps round the ring. */
        private const val CIRCLE_SPEED = 0.26f
        private const val STEER = 0.2
        /** Ticks it circles before it'll strike. */
        private const val SETTLE = 60
        private const val RUN_UP_TICKS = 80
        /** Blocks out from a ship's middle it leaves the water and comes down, and how far over its quarry's feet it flies. */
        private const val LEAP_OUT = 4.6
        private const val LEAP_CLEAR = 1.4
        /** The stretch of its leap it snaps in, and how close it must pass. */
        private const val BITE_FROM = 0.38
        private const val BITE_TO = 0.64
        private const val BITE_REACH = 0.9
        /** Floundering on deck: ticks it lasts, how much harder it's hurt, and its snaps at feet. */
        private const val STRANDED_TICKS = 70
        private const val STRANDED_HURT = 1.5f
        private const val STRANDED_BITE = 3f
        private const val BITE_EVERY = 20
        /** At a ship's planks: how far out from her side, how high its middle, its tilt, its bites and how long it keeps at it. */
        private const val GNAW_OFF = 0.45
        private const val GNAW_HEIGHT = 0.15
        private const val GNAW_PITCH = 40f
        private const val GNAW_EVERY = 30
        private const val GNAW_DAMAGE = 1.5f
        private const val GNAW_TICKS = 200
        /** Hurt this badly, it flees; fleeing, it's gone after a while. */
        private const val FLEE_HEALTH = 0.35f
        private const val FLEE_TICKS = 120
        /** Blocks a tick it sinks, dead. */
        private const val DEAD_SINK = 0.03
        /** Farther than this from its quarry, or its quarry ashore this long, and it gives up. */
        private const val GIVE_UP = 80.0
        private const val ASHORE_TICKS = 200
        private const val DRY_TICKS = 60

        private fun smooth(u: Float): Float {
            val k = u.coerceIn(0f, 1f)
            return k * k * (3f - 2f * k)
        }

        private fun horizontal(a: Vec3, b: Vec3): Double {
            val dx = a.x - b.x
            val dz = a.z - b.z
            return sqrt(dx * dx + dz * dz)
        }
    }
}
