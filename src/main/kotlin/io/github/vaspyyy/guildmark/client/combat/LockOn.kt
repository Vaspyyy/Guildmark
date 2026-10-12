package io.github.vaspyyy.guildmark.client.combat

import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderFrameEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Lock-on: the camera follows one creature so the player can circle it and roll around its blows.
 * Flicking the mouse sideways switches to the next creature on that side.
 */
object LockOn {
    private const val RANGE = 22.0
    /** Degrees of mouse movement in a quick flick that switch targets. */
    private const val FLICK = 28f

    var target: LivingEntity? = null
        private set
    private var unseen = 0
    private var lastYaw = Float.NaN
    private var flick = 0f
    private var lastFrame = 0L

    fun toggle(player: LocalPlayer) {
        if (target != null) {
            release()
            return
        }
        best(player)?.let { lock(player, it) }
    }

    fun release() {
        target = null
        lastYaw = Float.NaN
        flick = 0f
    }

    private fun lock(player: LocalPlayer, entity: LivingEntity) {
        target = entity
        unseen = 0
        flick = 0f
        lastYaw = Float.NaN
        player.playSound(ModSounds.LOCK_ON.get(), 0.5f, 1f)
    }

    private fun candidates(player: LocalPlayer): List<LivingEntity> =
        player.level().getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(RANGE)) {
            it !== player && it.isAlive && !it.isSpectator && !it.isInvisible && it !is ArmorStand &&
                (it !is OwnableEntity || it.owner !== player) && it.distanceTo(player) <= RANGE && player.hasLineOfSight(it)
        }

    /** The creature nearest the crosshair, favouring foes and closer ones. */
    private fun best(player: LocalPlayer): LivingEntity? {
        val look = player.getViewVector(1f)
        val eye = player.eyePosition
        var best: LivingEntity? = null
        var bestScore = Double.MAX_VALUE
        for (entity in candidates(player)) {
            val to = entity.boundingBox.center.subtract(eye)
            val distance = to.length()
            val angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(look), -1.0, 1.0)))
            if (angle > 50.0) continue
            var score = angle + distance * 1.2
            if (!CombatClient.isFoe(entity)) score += if (entity is Player) 25.0 else 40.0
            if (score < bestScore) {
                bestScore = score
                best = entity
            }
        }
        return best
    }

    /** Moves the lock to the nearest creature to the [side] (1 right, -1 left) of the current one. */
    private fun switchTarget(player: LocalPlayer, side: Int) {
        val current = target ?: return
        val currentAngle = Mth.wrapDegrees(yawTo(player.position(), current.position()) - player.yRot)
        var best: LivingEntity? = null
        var bestGap = Float.MAX_VALUE
        for (entity in candidates(player)) {
            if (entity === current) continue
            val angle = Mth.wrapDegrees(yawTo(player.position(), entity.position()) - player.yRot)
            val gap = (angle - currentAngle) * side
            if (gap <= 2f || abs(angle) > 100f) continue
            val score = gap + entity.distanceTo(player) * 0.8f + (if (CombatClient.isFoe(entity)) 0f else 30f)
            if (score < bestGap) {
                bestGap = score
                best = entity
            }
        }
        best?.let { lock(player, it) }
    }

    fun tick(player: LocalPlayer) {
        val current = target ?: return
        unseen = if (player.hasLineOfSight(current)) 0 else unseen + 1
        val lost = !current.isAlive || current.isRemoved || current.level() !== player.level() ||
            current.distanceTo(player) > RANGE + 6 || unseen > 40 || !player.isAlive
        if (!lost) return
        release()
        // A felled foe hands the lock to the next one close by, so a fight flows on
        if (!current.isAlive && player.isAlive) {
            candidates(player).filter { CombatClient.isFoe(it) && it.distanceTo(player) < 10f }
                .minByOrNull { it.distanceTo(player) }?.let { lock(player, it) }
        }
    }

    private fun yawTo(from: Vec3, to: Vec3): Float = Math.toDegrees(atan2(-(to.x - from.x), to.z - from.z)).toFloat()

    /** Every frame: turn the view towards the target, smoothly, and read mouse flicks as target switches. */
    fun onFrame(@Suppress("UNUSED_PARAMETER") event: RenderFrameEvent.Pre) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val current = target ?: return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = now
        if (minecraft.gui.screen() != null || minecraft.isPaused) {
            lastYaw = player.yRot
            return
        }
        if (!lastYaw.isNaN()) {
            flick = flick * exp(-dt * 10f) + Mth.wrapDegrees(player.yRot - lastYaw)
            if (abs(flick) > FLICK) {
                switchTarget(player, sign(flick).toInt())
                flick = 0f
            }
        }
        val aim = target ?: current
        val partial = minecraft.deltaTracker.getGameTimeDeltaPartialTick(true)
        val tx = Mth.lerp(partial.toDouble(), aim.xo, aim.x)
        val ty = Mth.lerp(partial.toDouble(), aim.yo, aim.y) + aim.bbHeight * 0.55
        val tz = Mth.lerp(partial.toDouble(), aim.zo, aim.z)
        val px = Mth.lerp(partial.toDouble(), player.xo, player.x)
        val py = Mth.lerp(partial.toDouble(), player.yo, player.y) + player.eyeHeight
        val pz = Mth.lerp(partial.toDouble(), player.zo, player.z)
        val dx = tx - px
        val dz = tz - pz
        val flat = sqrt(dx * dx + dz * dz)
        if (flat < 0.3) {
            lastYaw = player.yRot
            return
        }
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val thirdPerson = minecraft.options.cameraType != CameraType.FIRST_PERSON
        val pitch = (Math.toDegrees(-atan2(ty - py, flat)).toFloat() + if (thirdPerson) 10f else 0f).coerceIn(-35f, 50f)
        val k = 1f - exp(-dt * 16f)
        val turnYaw = Mth.wrapDegrees(yaw - player.yRot) * k
        val turnPitch = (pitch - player.xRot) * k
        player.turn(turnYaw / 0.15, turnPitch / 0.15)
        lastYaw = player.yRot
    }
}
