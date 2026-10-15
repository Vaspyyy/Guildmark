package io.github.vaspyyy.guildmark.client.combat

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatMob
import io.github.vaspyyy.guildmark.combat.CombatState
import io.github.vaspyyy.guildmark.combat.Move
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.combat.Weapons
import io.github.vaspyyy.guildmark.network.DrinkFlaskPayload
import io.github.vaspyyy.guildmark.network.MovePayload
import io.github.vaspyyy.guildmark.network.ReleaseChargePayload
import io.github.vaspyyy.guildmark.network.StaggerPayload
import io.github.vaspyyy.guildmark.network.StartMovePayload
import io.github.vaspyyy.guildmark.network.StrikePayload
import io.github.vaspyyy.guildmark.network.VitalsPayload
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.ClientInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.lang.reflect.Field
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The client half of the new combat. It reads the player's buttons, plays their moves at once (slashes,
 * charges, rolls) and tells the server, which checks and applies them. It also follows the moves other
 * players are seen doing and which creatures are staggered, for the animations and the HUD.
 */
object CombatClient {
    val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "combat"))
    val DODGE = KeyMapping("key.guildmark.dodge", InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT, CATEGORY)
    val LOCK_ON = KeyMapping("key.guildmark.lock_on", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_MIDDLE, CATEGORY)
    val SKILL = KeyMapping("key.guildmark.weapon_skill", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY)
    val FLASK = KeyMapping("key.guildmark.flask", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, CATEGORY)

    /** Presses wait this long for the move under way to reach the point where they can follow. */
    private const val BUFFER_TICKS = 8
    /** A light attack this soon after the last one continues the combo. */
    private const val COMBO_GRACE = 8

    /** A move under way: the local player's own, or one another player was seen doing. */
    class Action(val move: Move, val seq: Int, val startTick: Long, val tempo: Float, val yaw: Float) {
        /** Ticks into the move at its own speed; [prev] is the value one tick earlier, for smooth drawing. */
        var t = 0f
        var prev = 0f
        /** The attack button is still held on a charge move. */
        var charging = false
        /** Ticks spent held at the charge point. */
        var charge = 0
        /** Ticks the move stays frozen on impact. */
        var hitstop = 0
        val struck = IntOpenHashSet()
        /** A riposte or backstab's victim. */
        var target = -1
        var landed = false
        /** The move this one cut short, held where it stopped, so the animations can ease out of it. */
        var from: Move? = null
        var fromTime = 0f
        var fromYaw = 0f

        fun time(partial: Float): Float = Mth.lerp(partial, prev, t)
        fun done(): Boolean = t >= move.length
        /** How much of the full charge was built up, 0 to 1. */
        fun chargeFraction(): Float = if (move.maxCharge > 0) charge.toFloat() / move.maxCharge else 0f

        /** Notes [old] as the move this one cut short, if it was still under way. */
        fun replacing(old: Action?): Action {
            if (old != null && !old.done()) {
                from = old.move
                fromTime = old.t
                fromYaw = old.yaw
            }
            return this
        }

        /** One tick further. Returns false while the move is paused (charging at its peak or frozen on impact). */
        fun advance(): Boolean {
            prev = t
            if (hitstop > 0) {
                hitstop--
                return false
            }
            if (charging && move.chargeAt >= 0 && t >= move.chargeAt) {
                if (charge < move.maxCharge) charge++
                return false
            }
            t += tempo
            if (charging && move.chargeAt >= 0) t = min(t, move.chargeAt.toFloat())
            return true
        }
    }

    private class Stagger(val start: Long, val ticks: Int, val kind: Int)
    private class Pending(val seq: Int, val stamina: Float, val focus: Float)
    private enum class Press { LIGHT, HEAVY, SKILL, DODGE, DRINK }

    /** Ticks this client has run with a world loaded and unpaused. */
    var ticks = 0L
        private set

    /** The local player's move under way, or null. */
    var action: Action? = null
        private set
    private val remote = Int2ObjectOpenHashMap<Action>()
    private val staggers = Int2ObjectOpenHashMap<Stagger>()
    /** When the local player last struck each creature. */
    private val engaged = Int2LongOpenHashMap()

    // What the server last said about our stamina and focus, less the moves it hasn't counted yet
    var stamina = CombatState.MAX_STAMINA
        private set
    var maxStamina = CombatState.MAX_STAMINA
        private set
    var focus = CombatState.MAX_FOCUS
        private set
    var maxFocus = CombatState.MAX_FOCUS
        private set
    private var vitalsFlags = 0
    private val pending = ArrayList<Pending>()
    /** Last tick a move failed for want of stamina, so the HUD can flash the bar. */
    var exhaustedAt = -1000L
        private set
    /** Sprinting stops when stamina runs dry and only starts again once some is back. */
    private var winded = false

    private var seq = 0
    private var buffered: Press? = null
    private var bufferedAt = 0L
    private var comboStep = 0
    private var comboUntil = 0L
    private var lastBlockTick = -1000L
    /** Whether the attack button belongs to combat this tick (otherwise vanilla mines and punches). */
    private var ownsAttack = false
    private var player: LocalPlayer? = null

    private val moveVector: Field by lazy {
        ClientInput::class.java.getDeclaredField("moveVector").apply { isAccessible = true }
    }

    /** True when the world we're in plays the new combat. */
    fun active(): Boolean {
        val minecraft = Minecraft.getInstance()
        if (minecraft.player == null || minecraft.level == null) return false
        return GuildmarkConfig.SPEC.isLoaded && GuildmarkConfig.COMBAT.get()
    }

    fun shownStamina(): Float = (stamina - pending.sumOf { it.stamina.toDouble() }.toFloat()).coerceAtLeast(0f)
    fun shownFocus(): Float = (focus - pending.sumOf { it.focus.toDouble() }.toFloat()).coerceAtLeast(0f)
    fun inCombat(): Boolean = vitalsFlags and VitalsPayload.IN_COMBAT != 0

    /** The move [entity] is doing right now, if it's a player we know about. */
    fun actionOf(entity: Entity): Action? {
        val local = player
        if (local != null && entity === local) return action?.takeIf { !it.done() }
        return remote.get(entity.id)?.takeIf { !it.done() }
    }

    /** Ticks into [entity]'s current stagger and its total length, or null when it isn't staggered. */
    fun staggerOf(entity: Entity, partial: Float = 0f): Pair<Float, Int>? {
        val s = staggers.get(entity.id) ?: return null
        val into = ticks - s.start + partial
        return if (into < s.ticks) into to s.ticks else null
    }

    fun staggerKind(entity: Entity): Int = staggers.get(entity.id)?.kind ?: -1

    /** The local player struck this creature in the last ten seconds. */
    fun engagedRecently(id: Int): Boolean = engaged.containsKey(id) && ticks - engaged.get(id) < 200

    /** Open to a riposte: its stance broke or a parry knocked it off balance, and no one's finishing it yet. */
    fun isOpen(entity: Entity): Boolean {
        val s = staggers.get(entity.id) ?: return false
        return ticks - s.start < s.ticks && (s.kind == Stance.BROKEN || s.kind == Stance.PARRIED)
    }

    // ---- Every tick ----

    fun onTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Pre) {
        val minecraft = Minecraft.getInstance()
        val local = minecraft.player
        if (local == null || minecraft.level == null) {
            if (player != null) reset()
            return
        }
        if (local !== player) {
            reset()
            player = local
        }
        if (minecraft.isPaused) return
        ticks++
        if (!active()) {
            action = null
            ownsAttack = false
            // Without the new combat the flask key is a quick draught, no drinking to sit through
            while (FLASK.consumeClick()) if (minecraft.gui.screen() == null && !Cutscenes.holdsCamera()) sip(local)
            drain()
            return
        }
        if (local.isUsingItem && local.useItem.has(DataComponents.BLOCKS_ATTACKS)) lastBlockTick = ticks

        // Buttons pressed since last tick (none count while a cutscene has the camera)
        val screenOpen = minecraft.gui.screen() != null
        if (Cutscenes.holdsCamera()) {
            drain()
            buffered = null
            ownsAttack = false
            while (minecraft.options.keyAttack.consumeClick()) Unit
        } else {
            while (LOCK_ON.consumeClick()) if (!screenOpen) LockOn.toggle(local)
            while (DODGE.consumeClick()) if (!screenOpen) press(Press.DODGE)
            while (SKILL.consumeClick()) if (!screenOpen) press(Press.SKILL)
            while (FLASK.consumeClick()) if (!screenOpen) press(Press.DRINK)
            ownsAttack = !screenOpen && ownsAttack(local)
            if (ownsAttack) {
                val heavy = minecraft.options.keyShift.isDown
                while (minecraft.options.keyAttack.consumeClick()) press(if (heavy) Press.HEAVY else Press.LIGHT)
            }
        }

        LockOn.tick(local)
        action?.let { tickAction(local, it) }
        if (action?.done() == true) action = null
        startBuffered(local)
        tickRemote()
        CameraFx.tick()
        CombatHud.tick(local, ticks)
    }

    private fun drain() {
        while (DODGE.consumeClick()) Unit
        while (SKILL.consumeClick()) Unit
        while (FLASK.consumeClick()) Unit
        while (LOCK_ON.consumeClick()) Unit
        LockOn.release()
    }

    private fun reset() {
        action = null
        remote.clear()
        staggers.clear()
        engaged.clear()
        CombatHud.reset()
        pending.clear()
        buffered = null
        stamina = CombatState.MAX_STAMINA
        maxStamina = CombatState.MAX_STAMINA
        focus = CombatState.MAX_FOCUS
        maxFocus = CombatState.MAX_FOCUS
        vitalsFlags = 0
        winded = false
        player = null
        LockOn.release()
    }

    /**
     * Whether a click of the attack button is a combat move. With a weapon in hand it is, unless the
     * crosshair is on a block and no foe is near: then the sword still cuts cobwebs and breaks bamboo.
     * Riding, gliding, or striking something that isn't a creature (a boat, an item frame) is the plain
     * vanilla swing.
     */
    private fun ownsAttack(player: LocalPlayer): Boolean {
        if (player.isSpectator || Weapons.classOf(player.mainHandItem) == null) return action?.let { !it.done() } ?: false
        if (action?.done() == false) return true
        if (player.isPassenger || player.isFallFlying) return false
        if (LockOn.target != null) return true
        val hit = Minecraft.getInstance().hitResult
        if (hit is EntityHitResult && hit.entity !is LivingEntity) return false
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) return foeNear(player, 5.0)
        return true
    }

    private fun foeNear(player: LocalPlayer, range: Double): Boolean =
        player.level().getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(range)) {
            it !== player && it.isAlive && isFoe(it) && Combat.angleTo(player.yRot, it.x - player.x, it.z - player.z) < 70f
        }.isNotEmpty()

    /** A creature that's out to fight: monsters, and anything angry enough to be attacking. */
    fun isFoe(entity: LivingEntity): Boolean =
        entity is Enemy || (entity as? Mob)?.isAggressive == true || entity is CombatMob || isOpen(entity)

    private fun press(press: Press) {
        val local = player ?: return
        if (!tryStart(local, press)) {
            buffered = press
            bufferedAt = ticks
        } else {
            buffered = null
        }
    }

    private fun startBuffered(local: LocalPlayer) {
        val press = buffered ?: return
        if (ticks - bufferedAt > BUFFER_TICKS) {
            buffered = null
            return
        }
        if (tryStart(local, press)) buffered = null
    }

    // ---- Starting moves ----

    /** Starts the move [press] stands for if it may start now. False means try again shortly (it's buffered). */
    private fun tryStart(local: LocalPlayer, press: Press): Boolean {
        val move = choose(local, press) ?: return true
        if (!canStart(local, move)) return false
        if (!local.isCreative && (move.stamina > 0f && shownStamina() < 1f || move.focus > 0f && shownFocus() < Talents.focusCost(local, move))) {
            if (ticks - exhaustedAt > 10) {
                local.playSound(ModSounds.OUT_OF_STAMINA.get(), 0.6f, 1f)
            }
            exhaustedAt = ticks
            return true
        }
        start(local, move)
        return true
    }

    private fun choose(local: LocalPlayer, press: Press): Move? {
        val armed = Weapons.classOf(local.mainHandItem) != null
        return when (press) {
            Press.DODGE -> if (moveKeys().let { it.first != 0f || it.second != 0f }) Weapons.rollFor(local) else Move.BACKSTEP
            Press.SKILL -> if (armed) Move.SKILL else null
            Press.DRINK -> if (flaskReady(local)) Move.DRINK else null
            Press.HEAVY -> if (armed) Move.HEAVY else null
            Press.LIGHT -> when {
                !armed -> null
                vitalsFlags and VitalsPayload.GUARD_COUNTER != 0 && ticks - lastBlockTick < 12 -> Move.GUARD_COUNTER
                local.isSprinting && local.onGround() -> Move.RUNNING
                !local.onGround() && !local.isInWater && !local.abilities.flying && local.deltaMovement.y < 0.3 -> Move.JUMP
                else -> {
                    val step = if (ticks <= comboUntil) comboStep % 3 + 1 else 1
                    when (step) {
                        2 -> Move.LIGHT_2
                        3 -> Move.LIGHT_3
                        else -> Move.LIGHT_1
                    }
                }
            }
        }
    }

    private fun canStart(local: LocalPlayer, move: Move): Boolean {
        if (!local.isAlive || local.isSpectator || local.isPassenger || local.isFallFlying || local.isSleeping) return false
        if (move.isRoll && (!local.onGround() || local.isInWater || local.abilities.flying)) return false
        val current = action ?: return true
        if (current.done()) return true
        if (!current.move.requestable && current.move != Move.PARRY) return false
        if (current.charging) return false
        return current.t >= (if (move.isRoll) current.move.dodgeFrom else current.move.chainFrom)
    }

    /** Forward and sideways input from the movement keys, each -1, 0 or 1. */
    private fun moveKeys(): Pair<Float, Float> {
        val options = Minecraft.getInstance().options
        val forward = (if (options.keyUp.isDown) 1f else 0f) - (if (options.keyDown.isDown) 1f else 0f)
        val strafe = (if (options.keyLeft.isDown) 1f else 0f) - (if (options.keyRight.isDown) 1f else 0f)
        return forward to strafe
    }

    private fun start(local: LocalPlayer, requested: Move) {
        val minecraft = Minecraft.getInstance()
        var move = requested
        var flags = 0
        var target: LivingEntity? = null
        if (move.canBecomeCritical) {
            guessCritical(local)?.let { (victim, crit) ->
                target = victim
                move = crit
                flags = flags or (if (crit == Move.RIPOSTE) StartMovePayload.GUESS_RIPOSTE else StartMovePayload.GUESS_BACKSTAB)
            }
        }
        val holding = move.chargeAt >= 0 && minecraft.options.keyAttack.isDown
        if (holding) flags = flags or StartMovePayload.HOLDING

        // Which way a roll goes: the movement keys, turned by where the player faces
        var yaw = local.yRot
        if (move == Move.ROLL || move == Move.ROLL_HEAVY) {
            val (forward, strafe) = moveKeys()
            val r = Math.toRadians(local.yRot.toDouble())
            val dx = strafe * cos(r) - forward * sin(r)
            val dz = forward * cos(r) + strafe * sin(r)
            yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        }

        seq++
        val tempo = if (move.isAttack) Weapons.tempo(local) else 1f
        val next = Action(move, seq, ticks, tempo, yaw)
        next.charging = holding
        next.target = target?.id ?: -1
        // The first tick of the move plays at once
        next.t = tempo
        action = next.replacing(action)
        pending.add(Pending(seq, if (local.isCreative) 0f else Talents.staminaCost(local, move), if (local.isCreative) 0f else Talents.focusCost(local, move)))
        ClientPacketDistributor.sendToServer(StartMovePayload(seq, requested.ordinal, yaw, flags))

        when {
            move.isLight() -> {
                comboStep = when (move) {
                    Move.LIGHT_2 -> 2
                    Move.LIGHT_3 -> 3
                    Move.RUNNING, Move.JUMP, Move.GUARD_COUNTER -> 1
                    else -> 1
                }
                comboUntil = ticks + (move.length / tempo).toLong() + COMBO_GRACE
            }
            move.isAttack -> comboUntil = 0L
        }
        if (move.isAttack || move.isRoll || move.isCritical || move == Move.DRINK) {
            if (local.isUsingItem) minecraft.gameMode?.releaseUsingItem(local)
            if (move != Move.RUNNING) local.isSprinting = false
        }
        if (move.isRoll) {
            applyDodge(local, next, 0)
            CameraFx.onDodge(move, yaw - local.yRot)
        }
        if (move.isCritical) target?.let { face(local, it) }
        moveSound(move)?.let { local.playSound(it, 0.9f, 0.9f + local.random.nextFloat() * 0.2f) }
    }

    /**
     * Whether there's a draught in the flask to drink. An empty flask says so instead; with no flask at
     * all the key does nothing.
     */
    private fun flaskReady(local: LocalPlayer): Boolean {
        val flask = local.getExistingDataOrNull(ModAttachments.FLASK) ?: return false
        if (!flask.owned) return false
        if (flask.charges > 0 || local.isCreative) return true
        emptyFlask(local)
        return false
    }

    private fun emptyFlask(local: LocalPlayer) {
        local.sendOverlayMessage(Component.translatable("message.guildmark.flask.empty").withColor(0xE05A4A))
        local.playSound(SoundEvents.BOTTLE_EMPTY, 0.5f, 0.8f)
    }

    /** The flask key with the new combat off: the server drinks at once, if the flask isn't resting. */
    private fun sip(local: LocalPlayer) {
        val flask = local.getExistingDataOrNull(ModAttachments.FLASK) ?: return
        if (!flask.owned) return
        if (flask.charges <= 0 && !local.isCreative) return emptyFlask(local)
        ClientPacketDistributor.sendToServer(DrinkFlaskPayload())
    }

    private fun Move.isLight(): Boolean =
        this == Move.LIGHT_1 || this == Move.LIGHT_2 || this == Move.LIGHT_3 || this == Move.RUNNING || this == Move.JUMP || this == Move.GUARD_COUNTER

    /** The same test the server makes: a staggered foe in front, or an unready one from behind. */
    private fun guessCritical(local: LocalPlayer): Pair<LivingEntity, Move>? {
        if (!local.onGround()) return null
        var best: Pair<LivingEntity, Move>? = null
        var bestDistance = Double.MAX_VALUE
        for (entity in local.level().getEntitiesOfClass(LivingEntity::class.java, local.boundingBox.inflate(3.0))) {
            if (entity === local || !entity.isAlive || entity is Player) continue
            val dx = entity.x - local.x
            val dz = entity.z - local.z
            val distance = sqrt(dx * dx + dz * dz) - entity.bbWidth / 2
            if (distance > 2.2 || kotlin.math.abs(entity.y - local.y) > 1.2) continue
            if (Combat.angleTo(local.yRot, dx, dz) > 50f) continue
            val move = when {
                isOpen(entity) -> Move.RIPOSTE
                canBackstab(local, entity) -> Move.BACKSTAB
                else -> continue
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = entity to move
            }
        }
        return best
    }

    private fun canBackstab(local: LocalPlayer, entity: LivingEntity): Boolean {
        if (entity !is Mob || entity.bbHeight !in 0.5f..2.6f) return false
        if (entity !is Enemy && entity !is CombatMob) return false
        if ((entity as? CombatMob)?.canBeBackstabbed() == false) return false
        if (staggers.containsKey(entity.id) && staggerOf(entity) != null) return false
        return Combat.angleTo(entity.yBodyRot, local.x - entity.x, local.z - entity.z) > 125f
    }

    private fun face(local: LocalPlayer, target: Entity) {
        val yaw = Math.toDegrees(atan2(-(target.x - local.x), target.z - local.z)).toFloat()
        local.yRot = yaw
        local.yHeadRot = yaw
    }

    // ---- Moves under way ----

    private fun tickAction(local: LocalPlayer, a: Action) {
        val move = a.move
        val minecraft = Minecraft.getInstance()
        if (a.charging && (!minecraft.options.keyAttack.isDown || a.charge >= move.maxCharge || !local.isAlive)) {
            a.charging = false
            ClientPacketDistributor.sendToServer(ReleaseChargePayload(a.seq, a.charge))
        }
        val before = a.t
        val wasCharging = a.charging && a.t >= move.chargeAt && a.charge == 0
        a.advance()
        if (wasCharging && a.charge == 1) local.playSound(ModSounds.CHARGE.get(), 0.7f, 1f)
        val now = a.t

        if (move.isRoll) {
            val tick = (ticks - a.startTick).toInt()
            applyDodge(local, a, tick)
        }
        if (move.lunge > 0f && before < move.lungeAt && now >= move.lungeAt) lunge(local, a)
        if (move.isCritical) {
            val target = local.level().getEntity(a.target)
            if (target != null && target.isAlive) face(local, target)
            val hits = if (move == Move.RIPOSTE) Move.RIPOSTE_HITS else intArrayOf(move.hitFrom)
            for ((i, at) in hits.withIndex()) {
                if (before < at && now >= at) {
                    a.hitstop = if (i == hits.size - 1) 4 else 2
                    CameraFx.shake(if (i == hits.size - 1) 0.7f else 0.35f)
                }
            }
            return
        }
        if (move.isAttack && now >= move.hitFrom && before <= move.hitTo) strike(local, a)
    }

    /** The move's forward push: towards the locked target, stopping short of it. */
    private fun lunge(local: LocalPlayer, a: Action) {
        var push = a.move.lunge.toDouble()
        var direction = Vec3.directionFromRotation(0f, local.yRot)
        LockOn.target?.let { target ->
            val dx = target.x - local.x
            val dz = target.z - local.z
            val gap = sqrt(dx * dx + dz * dz) - target.bbWidth / 2 - local.bbWidth / 2
            push = min(push, max(0.0, (gap - 0.8) * 0.45))
            direction = Vec3(dx, 0.0, dz).normalize()
        }
        if (!local.onGround()) push *= 0.4
        local.deltaMovement = local.deltaMovement.add(direction.scale(push))
    }

    private fun applyDodge(local: LocalPlayer, a: Action, tick: Int) {
        val speeds = Move.dodgeSpeeds(a.move)
        if (tick !in speeds.indices) return
        val yaw = if (a.move == Move.BACKSTEP) local.yRot + 180f else a.yaw
        val direction = Vec3.directionFromRotation(0f, yaw)
        val speed = speeds[tick].toDouble() * (if (local.onGround()) 1.0 else 0.6)
        local.deltaMovement = Vec3(direction.x * speed, local.deltaMovement.y, direction.z * speed)
    }

    /** Finds what the swing reaches this tick and tells the server; the first hit freezes the swing for a moment. */
    private fun strike(local: LocalPlayer, a: Action) {
        val move = a.move
        val minecraft = Minecraft.getInstance()
        val crosshair = minecraft.crosshairPickEntity
        val locked = LockOn.target
        val reach = move.reach
        val found = ArrayList<LivingEntity>()
        for (entity in local.level().getEntitiesOfClass(LivingEntity::class.java, local.boundingBox.inflate(reach + 1.5))) {
            if (entity === local || !entity.isAlive || entity.isSpectator || a.struck.contains(entity.id)) continue
            if (entity === local.vehicle || entity.vehicle === local) continue
            if (entity is OwnableEntity && entity.owner === local) continue
            val chosen = entity === crosshair || entity === locked
            if (!chosen && (entity is Player || !isFoe(entity))) continue
            val dx = entity.x - local.x
            val dz = entity.z - local.z
            val flat = sqrt(dx * dx + dz * dz) - entity.bbWidth / 2
            if (flat > reach) continue
            if (entity.boundingBox.maxY < local.y - 1.0 || entity.boundingBox.minY > local.eyeY + 1.0) continue
            if (move.arc < 180f && flat > 0.5 && Combat.angleTo(local.yRot, dx, dz) > move.arc) continue
            if (flat > 1.0 && !local.hasLineOfSight(entity)) continue
            found.add(entity)
        }
        if (found.isEmpty()) return
        found.sortBy { it.distanceToSqr(local) }
        val ids = found.take(8).map { it.id }
        ids.forEach {
            a.struck.add(it)
            engaged.put(it, ticks)
        }
        ClientPacketDistributor.sendToServer(StrikePayload(a.seq, ids))
        if (!a.landed) {
            a.landed = true
            val first = found[0]
            val sound = if (first.armorValue >= 6 || first is AbstractSkeleton || first is CombatMob) ModSounds.HIT_ARMOR.get() else ModSounds.HIT_FLESH.get()
            local.level().playLocalSound(first.x, first.getY(0.6), first.z, sound, SoundSource.PLAYERS, 1f, 0.9f + local.random.nextFloat() * 0.2f, false)
            val heavy = move == Move.HEAVY || move == Move.SKILL || move == Move.GUARD_COUNTER || move == Move.JUMP
            a.hitstop = when {
                a.chargeFraction() > 0.9f -> 4
                heavy -> 3
                else -> 2
            }
            CameraFx.shake(if (heavy) 0.3f + 0.3f * a.chargeFraction() else 0.15f)
        }
    }

    private fun tickRemote() {
        val level = Minecraft.getInstance().level ?: return
        val iterator = remote.int2ObjectEntrySet().iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val a = entry.value
            val entity = level.getEntity(entry.intKey)
            // Give up on a charge whose release never came
            if (a.charging && ticks - a.startTick > a.move.chargeAt / a.tempo + a.move.maxCharge + 10) a.charging = false
            a.advance()
            if (entity == null || a.done()) iterator.remove()
        }
        if (ticks % 100 == 0L) engaged.int2LongEntrySet().removeIf { ticks - it.longValue > 200 }
        val staggerIterator = staggers.int2ObjectEntrySet().iterator()
        while (staggerIterator.hasNext()) {
            val s = staggerIterator.next().value
            if (ticks - s.start > s.ticks + 20) staggerIterator.remove()
        }
    }

    private fun moveSound(move: Move) = when (move) {
        Move.LIGHT_1, Move.LIGHT_2, Move.LIGHT_3, Move.RUNNING, Move.JUMP -> ModSounds.SWING_LIGHT.get()
        Move.HEAVY, Move.GUARD_COUNTER -> ModSounds.SWING_HEAVY.get()
        Move.SKILL -> ModSounds.WHIRL.get()
        Move.ROLL, Move.ROLL_HEAVY -> ModSounds.ROLL.get()
        Move.BACKSTEP -> ModSounds.BACKSTEP.get()
        else -> null
    }

    // ---- Input hooks ----

    /** While a move plays, walking slows to the move's pace and jumping, crouching and sprinting wait. */
    fun onMovementInput(event: MovementInputUpdateEvent) {
        val local = event.entity as? LocalPlayer ?: return
        if (local !== player) return
        val input = event.input
        // A cutscene has the camera: the keys do nothing, not even a crouch or a sprint the shots would show
        if (Cutscenes.holdsCamera()) {
            input.keyPresses = Input.EMPTY
            moveVector.set(input, Vec2.ZERO)
            local.isSprinting = false
            return
        }
        if (!active()) return
        val a = action?.takeIf { !it.done() }
        if (a != null) {
            val keys = input.keyPresses
            input.keyPresses = Input(keys.forward(), keys.backward(), keys.left(), keys.right(), false, false, false)
            val scale = if (a.charging) 0.2f else a.move.moveSpeed
            val vector = input.moveVector
            moveVector.set(input, Vec2(vector.x * scale, vector.y * scale))
            if (a.move != Move.RUNNING) local.isSprinting = false
        }
        // Out of breath: no sprinting until a fifth of the stamina is back
        val shown = shownStamina()
        if (shown < 1f && inCombat()) winded = true
        if (winded && (shown >= maxStamina * 0.2f || !inCombat())) winded = false
        if (winded && !local.isCreative) {
            val keys = input.keyPresses
            input.keyPresses = Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), false)
            local.isSprinting = false
        }
    }

    /** Vanilla's swing and mining give way to our moves, and a shield can't come up mid-move. */
    fun onInteraction(event: InputEvent.InteractionKeyMappingTriggered) {
        if (Cutscenes.holdsCamera()) {
            event.isCanceled = true
            event.setSwingHand(false)
            return
        }
        val local = player ?: return
        if (!active()) return
        val a = action?.takeIf { !it.done() }
        when {
            event.isAttack && ownsAttack -> {
                event.isCanceled = true
                event.setSwingHand(false)
            }
            event.isUseItem && a != null && (a.move.isCritical || a.t < a.move.chainFrom) -> {
                event.isCanceled = true
                event.setSwingHand(false)
            }
            event.isPickBlock && LOCK_ON.same(Minecraft.getInstance().options.keyPickItem) && Weapons.classOf(local.mainHandItem) != null -> {
                event.isCanceled = true
                event.setSwingHand(false)
            }
        }
    }

    // ---- From the server ----

    fun onVitals(payload: VitalsPayload) {
        stamina = payload.stamina
        maxStamina = payload.maxStamina
        focus = payload.focus
        maxFocus = payload.maxFocus
        vitalsFlags = payload.flags
        pending.removeIf { it.seq <= payload.ackSeq }
    }

    fun onMove(payload: MovePayload) {
        val minecraft = Minecraft.getInstance()
        val local = minecraft.player ?: return
        val move = Move.byId(payload.move) ?: return
        if (payload.entityId == local.id) {
            onOwnMove(local, move, payload)
            return
        }
        val entity = minecraft.level?.getEntity(payload.entityId) as? Player ?: return
        if (payload.flags and MovePayload.RELEASE != 0) {
            remote.get(payload.entityId)?.let { if (it.seq == payload.seq) it.charging = false }
            return
        }
        // An attack carries its mover's tempo: their stats and skills aren't known on this side
        val tempo = if (!move.isAttack) 1f else if (payload.extra > 0f) payload.extra else Weapons.tempo(entity)
        val a = Action(move, payload.seq, ticks, tempo, if (move.isAttack) 0f else payload.extra)
        a.charging = payload.flags and MovePayload.CHARGING != 0
        a.t = tempo
        remote.put(payload.entityId, a.replacing(remote.get(payload.entityId)))
    }

    private fun onOwnMove(local: LocalPlayer, move: Move, payload: MovePayload) {
        val current = action
        when {
            payload.flags and MovePayload.REJECT != 0 -> {
                pending.removeIf { it.seq == payload.seq }
                if (current != null && current.seq == payload.seq) action = null
            }
            payload.flags and MovePayload.OVERRIDE != 0 -> {
                if (current == null || current.seq != payload.seq) return
                val target = payload.target
                if (current.move == move) {
                    current.target = target
                    return
                }
                // The server saw it differently: same moment, the move it decided on
                val tempo = if (move.isAttack) Weapons.tempo(local) else 1f
                val elapsed = (ticks - current.startTick + 1).toFloat()
                val replaced = Action(move, current.seq, current.startTick, tempo, current.yaw)
                replaced.t = elapsed * tempo
                replaced.prev = replaced.t - tempo
                replaced.target = target
                replaced.from = current.from
                replaced.fromTime = current.fromTime
                replaced.fromYaw = current.fromYaw
                action = replaced
            }
            payload.flags and MovePayload.FORCED != 0 -> {
                buffered = null
                val forced = Action(move, -1, ticks, 1f, local.yRot)
                forced.t = 1f
                action = forced.replacing(current)
                when (move) {
                    Move.PARRY -> CameraFx.shake(0.25f)
                    Move.GUARD_BREAK -> CameraFx.shake(0.6f)
                    Move.STAGGER -> CameraFx.shake(0.45f)
                    else -> {}
                }
            }
        }
    }

    fun onStagger(payload: StaggerPayload) {
        if (payload.ticks <= 0) {
            staggers.remove(payload.entityId)
            return
        }
        staggers.put(payload.entityId, Stagger(ticks, payload.ticks, payload.kind))
    }
}
