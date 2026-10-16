package io.github.vaspyyy.guildmark.client.sea

import io.github.vaspyyy.guildmark.client.combat.CameraFx
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.client.combat.CombatArmPose
import io.github.vaspyyy.guildmark.sea.Gnashfin
import io.github.vaspyyy.guildmark.sea.Maelgrasp
import io.github.vaspyyy.guildmark.sea.MaelgraspArm
import io.github.vaspyyy.guildmark.sea.SeaRegistry
import io.github.vaspyyy.guildmark.sea.Seas
import io.github.vaspyyy.guildmark.sea.Tentacle
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * The client half of the sea monsters: their renderers and models, and the sea round them, worked out on each client
 * from what it sees of them. A Gnashfin's dorsal fin cuts a wake through the surface as it circles, water streams off
 * it in a leap and its lure glints in the dark water; the sea churns where an arm breaks the surface, water runs off
 * it as it towers over the ship, spray flies off it in a sweep and the deck shakes under a slam; a length hacked off
 * one goes into the sea with a splash; water cascades off the head as it comes up, its siphon jets ink, and its roar
 * and its bite are felt underfoot. Whoever an arm has caught hangs struggling in its coils.
 */
object SeaClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(SeaRegistry.GNASHFIN.get(), ::GnashfinRenderer)
            event.registerEntityRenderer(SeaRegistry.MAELGRASP_ARM.get(), ::MaelgraspArmRenderer)
            event.registerEntityRenderer(SeaRegistry.MAELGRASP.get(), ::MaelgraspRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(GnashfinModel.LAYER, GnashfinModel::createBodyLayer)
            event.registerLayerDefinition(MaelgraspArmModel.LAYER, MaelgraspArmModel::createBodyLayer)
            event.registerLayerDefinition(MaelgraspModel.LAYER, MaelgraspModel::createBodyLayer)
        }
        Gnashfin.clientEffects = ::gnashfin
        MaelgraspArm.clientEffects = ::arm
        Maelgrasp.clientEffects = ::maelgrasp
    }

    // ---- Gnashfins ----

    private fun gnashfin(fish: Gnashfin) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isPaused || !near(minecraft, fish, SEE)) return
        val level = fish.level()
        val random = level.random
        if (!fish.isAlive) return
        val yaw = fish.yRot * Mth.DEG_TO_RAD
        val fx = -sin(yaw).toDouble()
        val fz = cos(yaw).toDouble()
        when (fish.mode) {
            Gnashfin.Mode.SWIM, Gnashfin.Mode.CIRCLE, Gnashfin.Mode.RUN_UP, Gnashfin.Mode.FLEE -> {
                val surface = Seas.surface(level, fish.x, fish.z) ?: return
                // Its fin breaks the surface: a wake of foam peeling off it, and a spray of droplets as it runs in fast
                if (fish.y + FIN_TOP < surface) return
                val speed = fish.swim
                if (random.nextFloat() < 0.4f + 0.6f * speed) {
                    val back = random.nextDouble() * 0.6
                    level.addParticle(ParticleTypes.FISHING, fish.x - fx * back, surface, fish.z - fz * back, -fz * 0.03 * (random.nextDouble() - 0.5), 0.0, fx * 0.03 * (random.nextDouble() - 0.5))
                }
                if (fish.mode == Gnashfin.Mode.RUN_UP || random.nextFloat() < 0.15f * speed) {
                    level.addParticle(ParticleTypes.SPLASH, fish.x + fx * 0.3, surface + 0.1, fish.z + fz * 0.3, fx * 0.1, 0.05, fz * 0.1)
                }
                if (random.nextInt(4) == 0) level.addParticle(ParticleTypes.BUBBLE, fish.x - fx * 1.1, fish.y + 0.2, fish.z - fz * 1.1, 0.0, 0.02, 0.0)
            }
            Gnashfin.Mode.LEAP -> {
                // Water streaming off it through the air
                repeat(2) {
                    val along = (random.nextDouble() - 0.5) * 2.0
                    level.addParticle(ParticleTypes.FALLING_WATER, fish.x + fx * along, fish.y + 0.25, fish.z + fz * along, 0.0, 0.0, 0.0)
                }
                if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.SPLASH, fish.x, fish.y + 0.3, fish.z, fish.deltaMovement.x * 0.3, 0.05, fish.deltaMovement.z * 0.3)
            }
            Gnashfin.Mode.STRANDED -> {
                // Wet on the boards, slapping water off itself as it thrashes
                if (random.nextInt(6) == 0) level.addParticle(ParticleTypes.SPLASH, fish.x + (random.nextDouble() - 0.5) * 1.6, fish.y + 0.25, fish.z + (random.nextDouble() - 0.5) * 1.6, 0.0, 0.08, 0.0)
                if (random.nextInt(5) == 0) level.addParticle(ParticleTypes.DRIPPING_WATER, fish.x + (random.nextDouble() - 0.5), fish.y + 0.45, fish.z + (random.nextDouble() - 0.5), 0.0, 0.0, 0.0)
            }
            Gnashfin.Mode.GNAW -> {
                if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.BUBBLE, fish.x + fx * 1.0, fish.y + 0.7, fish.z + fz * 1.0, (random.nextDouble() - 0.5) * 0.1, 0.05, (random.nextDouble() - 0.5) * 0.1)
            }
            else -> {}
        }
        // The lure under its chin glints in the dark water
        if (fish.glow > 0.5f && fish.isInWater && random.nextInt(LURE_EVERY) == 0) {
            level.addParticle(ParticleTypes.GLOW, fish.x + fx * 1.0, fish.y + 0.1, fish.z + fz * 1.0, 0.0, 0.01, 0.0)
        }
    }

    // ---- The Maelgrasp's arms ----

    /** What's been seen of an arm already: its burst up out of the sea, and the length cut off it going into the water. */
    private class ArmSeen {
        var burst = false
        var splashed = false
    }

    private val armsSeen = WeakHashMap<MaelgraspArm, ArmSeen>()

    private fun arm(arm: MaelgraspArm) {
        val minecraft = Minecraft.getInstance()
        val mode = arm.mode
        val before = arm.seenMode
        arm.seenMode = mode
        if (minecraft.isPaused) return
        val player = minecraft.player ?: return
        val level = arm.level()
        val random = level.random
        val seen = armsSeen.getOrPut(arm) { ArmSeen() }
        val far = arm.distanceTo(player)
        // The deck shakes as it slams down across it, and as it bursts up out of the sea
        if (far < FELT) {
            val near = 1f - far / FELT.toFloat()
            if (before == MaelgraspArm.Mode.SLAM && mode == MaelgraspArm.Mode.LINGER) CameraFx.shake(SLAM_SHAKE * near)
            if (!seen.burst && mode == MaelgraspArm.Mode.RISE && arm.tickCount < BURST_SEEN) CameraFx.shake(RISE_SHAKE * near)
        }
        seen.burst = true
        if (far > SEE) return
        val origin = arm.position()
        val yaw = arm.armYaw
        val joints = arm.shape.joints
        val cut = arm.cut
        if (arm.isAlive && mode != MaelgraspArm.Mode.LIMP) {
            // The sea churning white round it where it breaks the surface
            if (random.nextInt(2) == 0) {
                val a = random.nextFloat() * Mth.TWO_PI
                val r = Tentacle.RADII[2] + 0.2f + random.nextFloat() * 0.5f
                level.addParticle(ParticleTypes.FISHING, origin.x + cos(a) * r, origin.y + 0.05, origin.z + sin(a) * r, cos(a) * 0.04, 0.0, sin(a) * 0.04)
            }
            if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.BUBBLE, origin.x + (random.nextDouble() - 0.5) * 1.6, origin.y - 0.6, origin.z + (random.nextDouble() - 0.5) * 1.6, 0.0, 0.05, 0.0)
        }
        // Water running off every length of it that's up out of the sea; streaming off as it comes up
        val drips = when (mode) {
            MaelgraspArm.Mode.RISE -> 4
            MaelgraspArm.Mode.WITHDRAW, MaelgraspArm.Mode.LIMP -> 0
            else -> if (random.nextInt(2) == 0) 1 else 0
        }
        repeat(drips) {
            val i = 1 + random.nextInt(cut.coerceAtLeast(1))
            if (joints[i * 3 + 1] > 0.3f) {
                val at = Tentacle.toWorld(joints[i * 3] + (random.nextFloat() - 0.5f) * 0.6f, joints[i * 3 + 1] - Tentacle.RADII[i] * 0.8f, joints[i * 3 + 2] + (random.nextFloat() - 0.5f) * 0.6f, origin, yaw)
                level.addParticle(ParticleTypes.FALLING_WATER, at.x, at.y, at.z, 0.0, 0.0, 0.0)
            }
        }
        // Spray flung off its tip as it sweeps the deck, or lashes down
        if (mode == MaelgraspArm.Mode.SWEEP || mode == MaelgraspArm.Mode.SLAM || mode == MaelgraspArm.Mode.FLING) {
            val tip = Tentacle.toWorld(joints[Tentacle.SEGMENTS * 3], joints[Tentacle.SEGMENTS * 3 + 1], joints[Tentacle.SEGMENTS * 3 + 2], origin, yaw)
            repeat(3) { level.addParticle(ParticleTypes.SPLASH, tip.x + (random.nextDouble() - 0.5), tip.y, tip.z + (random.nextDouble() - 0.5), 0.0, 0.1, 0.0) }
        }
        // The length hacked off it: into the sea with a great splash, then bubbles as it sinks
        val severed = arm.severed ?: return
        val since = (arm.tickCount - arm.severedAt).toFloat()
        val hit = SeveredArm.hitAt(severed, cut)
        val mid = SeveredArm.middle(severed, cut, since)
        val at = Tentacle.toWorld(mid[0], mid[1], mid[2], origin, yaw)
        if (!seen.splashed && since >= hit) {
            seen.splashed = true
            val surface = Seas.surface(level, at.x, at.z) ?: origin.y
            repeat(40) {
                level.addParticle(ParticleTypes.SPLASH, at.x + (random.nextDouble() - 0.5) * 3.0, surface + 0.1, at.z + (random.nextDouble() - 0.5) * 3.0, 0.0, 0.2, 0.0)
            }
            repeat(12) { level.addParticle(ParticleTypes.CLOUD, at.x + (random.nextDouble() - 0.5) * 2.0, surface + 0.2, at.z + (random.nextDouble() - 0.5) * 2.0, 0.0, 0.04, 0.0) }
            level.playLocalSound(at.x, surface, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.6f, 0.55f + random.nextFloat() * 0.1f, false)
        } else if (seen.splashed && random.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.BUBBLE, at.x + (random.nextDouble() - 0.5) * 1.5, at.y, at.z + (random.nextDouble() - 0.5) * 1.5, 0.0, 0.08, 0.0)
        }
    }

    // ---- The Maelgrasp ----

    /** The stamps of its roars and bites already felt, so each shakes the deck once. */
    private class HeadSeen {
        var roar = -1
        var bite = -1
        var up = false
    }

    private val headsSeen = WeakHashMap<Maelgrasp, HeadSeen>()

    private fun maelgrasp(boss: Maelgrasp) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isPaused) return
        val player = minecraft.player ?: return
        val level = boss.level()
        val random = level.random
        val seen = headsSeen.getOrPut(boss) { HeadSeen() }
        val far = boss.distanceTo(player)
        val near = 1f - (far / FELT_HEAD.toFloat()).coerceIn(0f, 1f)
        // Its roar and its bite are felt through the deck
        val roar = boss.since(boss.roarAt, 0f)
        if (boss.roarAt != seen.roar && roar in 0f..3f) {
            seen.roar = boss.roarAt
            CameraFx.shake(ROAR_SHAKE * near)
        }
        val bite = boss.since(boss.biteAt, 0f)
        if (boss.biteAt != seen.bite && bite >= Maelgrasp.BITE_LAND && bite < Maelgrasp.BITE_LAND + 3f) {
            seen.bite = boss.biteAt
            CameraFx.shake(BITE_SHAKE * near)
        }
        // Breaking the surface
        val up = boss.riseN > 0.6f
        if (up && !seen.up && boss.isAlive) CameraFx.shake(SURFACE_SHAKE * near)
        seen.up = up
        if (far > SEE) return
        val rise = boss.riseN
        val surface = Seas.surface(level, boss.x, boss.z) ?: return
        if (rise > 0.15f) {
            // Water cascading off its mantle; pouring off it as it comes up or goes down
            val moving = boss.riseN != boss.riseO
            repeat(if (moving) 8 else 2) {
                val a = random.nextFloat() * Mth.TWO_PI
                val r = 1.6 + random.nextDouble() * 1.0
                val h = random.nextDouble() * (boss.y + MANTLE_TOP * rise - surface).coerceAtLeast(0.5)
                level.addParticle(ParticleTypes.FALLING_WATER, boss.x + cos(a) * r, surface + h, boss.z + sin(a) * r, 0.0, 0.0, 0.0)
            }
            // The sea heaving white round it at the waterline
            repeat(if (moving) 4 else 1) {
                val a = random.nextFloat() * Mth.TWO_PI
                val r = 2.5 + random.nextDouble() * 0.8
                level.addParticle(ParticleTypes.FISHING, boss.x + cos(a) * r, surface + 0.05, boss.z + sin(a) * r, cos(a) * 0.05, 0.0, sin(a) * 0.05)
            }
            if (moving && random.nextInt(2) == 0) level.addParticle(ParticleTypes.SPLASH, boss.x + (random.nextDouble() - 0.5) * 5.0, surface + 0.2, boss.z + (random.nextDouble() - 0.5) * 5.0, 0.0, 0.3, 0.0)
        }
        // Ink jetting from its siphon over the deck
        val ink = boss.since(boss.inkAt, 0f)
        if (rise > 0.5f && ink >= Maelgrasp.INK_SPRAY_FROM && ink <= Maelgrasp.INK_SPRAY_TO) {
            val siphon = local(boss, SIPHON_SIDE, SIPHON_UP, SIPHON_FORWARD)
            val toward = local(boss, 0.0, SIPHON_UP + 1.0, SIPHON_FORWARD + 6.0).subtract(siphon).normalize()
            repeat(6) {
                val s = 0.5 + random.nextDouble() * 0.5
                level.addParticle(
                    ParticleTypes.SQUID_INK, siphon.x, siphon.y, siphon.z,
                    toward.x * s + (random.nextDouble() - 0.5) * 0.15, toward.y * s + random.nextDouble() * 0.1, toward.z * s + (random.nextDouble() - 0.5) * 0.15,
                )
            }
        }
        // Struck in the eye, it bleeds dark into the sea
        val flinch = boss.since(boss.flinchAt, 0f)
        if (flinch in 0f..6f) {
            val eye = boss.eyePoint()
            repeat(3) { level.addParticle(ParticleTypes.SQUID_INK, eye.x, eye.y - 0.3, eye.z, (random.nextDouble() - 0.5) * 0.2, -0.05, (random.nextDouble() - 0.5) * 0.2) }
        }
    }

    /** A point of [boss]'s own frame (blocks to its right, up from its waterline, forward toward the ship) in the world. */
    private fun local(boss: Maelgrasp, right: Double, up: Double, forward: Double): Vec3 = Tentacle.toWorld(right.toFloat(), up.toFloat(), forward.toFloat(), boss.position(), boss.yRot)

    private fun near(minecraft: Minecraft, entity: Entity, range: Double): Boolean {
        val player = minecraft.player ?: return false
        return player.distanceToSqr(entity) < range * range
    }

    // ---- Caught ----

    private val armPose: HumanoidModel.ArmPose by lazy { CombatArmPose.PROXY.value }

    /**
     * Whoever an arm has caught hangs in its coils, kicking and prying at them, and shudders as it squeezes. Registered
     * after the combat animator's, which clears the frame.
     */
    val AVATAR = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
            val arm = entity.vehicle as? MaelgraspArm ?: return
            state.isPassenger = false
            val clip = Clips.get(SEIZED_CLIP) ?: return
            val squeezing = arm.mode == MaelgraspArm.Mode.SQUEEZE
            // Its squeezes come every 20 ticks from the moment it caught them, and so do the clip's: kept in step
            val partial = state.ageInTicks - entity.tickCount
            val held = if (arm.caughtAt >= 0) arm.tickCount - arm.caughtAt + partial else state.ageInTicks + entity.id * 13f
            val time = (held + SQUEEZE_PHASE) % clip.length
            state.setRenderData(CombatAnimator.FRAME, CombatAnimator.Frame(clip, time, 1f, 0f, if (squeezing) 0.9f else 0.35f, state.ageInTicks, null))
            state.rightArmPose = armPose
            state.leftArmPose = armPose
            state.swingAnimation = 0f
        }
    }

    const val SEIZED_CLIP = "sea/seized"
    /**
     * Ticks into the seized clip it is as an arm catches someone: its squeezes bite at 10.5, 30.5 and 50.5, and the
     * arm's land 19 ticks after the catch and every 20 after that (MaelgraspArm.SQUEEZE_EVERY).
     */
    private const val SQUEEZE_PHASE = 11.5f

    /** Blocks away the sea about them is seen, a slam is felt, and the head's roar. */
    private const val SEE = 64.0
    private const val FELT = 24.0
    private const val FELT_HEAD = 40.0
    /** How hard the deck shakes. */
    private const val SLAM_SHAKE = 0.6f
    private const val RISE_SHAKE = 0.3f
    private const val ROAR_SHAKE = 0.45f
    private const val BITE_SHAKE = 0.65f
    private const val SURFACE_SHAKE = 0.5f
    /** An arm first seen this many ticks into its life has only just burst up out of the sea. */
    private const val BURST_SEEN = 6
    /** How high a Gnashfin's fin stands over the bottom of its box. */
    private const val FIN_TOP = 0.75
    /** One tick in this many, its lure glints. */
    private const val LURE_EVERY = 14
    /** Blocks above its waterline the head's mantle rises, surfaced. */
    private const val MANTLE_TOP = 5.0
    /** Its siphon, in its own frame: to its right, up from its waterline and forward. */
    private const val SIPHON_SIDE = 2.3
    private const val SIPHON_UP = 2.2
    private const val SIPHON_FORWARD = 0.4
}
