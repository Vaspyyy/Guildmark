package io.github.vaspyyy.guildmark.companion

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.ship.Cog
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.faction.SwornKnight
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.story.StoryNpc
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityReference
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.animal.golem.IronGolem
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import java.util.Optional
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One of the four who travel with the player (see [CompanionKind]), drawn as a townsperson in their own look and
 * holding their own weapon. They follow, wait where they're told, sit by the fire, fight with the player's own moves
 * (see [CompanionFighting]) and speak up on the road (see [CompanionBanter]). Never saved with the world: [Companions]
 * keeps who they are and how they feel, and puts them back where they should be whenever they're missing. They never
 * die: with no health left they go down on one knee until the fight is over or the player helps them up. (Not called
 * plain Companion: Kotlin gives that name to every class's companion object.)
 */
class CompanionEntity(type: EntityType<out CompanionEntity>, level: Level) : PathfinderMob(type, level), OwnableEntity {
    /** What they're about when there's nothing to fight. */
    enum class Mode {
        /** At the player's side. */
        FOLLOW,
        /** Standing where they were told to wait (or where they were parted with). */
        WAIT,
        /** Sitting by a Wardfire. */
        CAMP,
        /** Walking up to the player's fire to meet them; they sit down across it once they've said hello. */
        ARRIVE,
        /** Walking off in anger, gone once out of sight. */
        LEAVE,
    }

    /** A gesture of the upper body (or a whole-body one, getting up), played once over whatever they're doing. */
    enum class Gesture(val clip: String, val length: Int) {
        NONE("", 0),
        WAVE("townsfolk/wave", 30),
        APPROVE("companion/approve", 24),
        DISAPPROVE("companion/disapprove", 24),
        STAND_UP("companion/stand_up", 20),
        REVIVE("companion/revive", 30),
    }

    val kind: CompanionKind get() = CompanionKind.entries.getOrElse(entityData.get(DATA_KIND).toInt()) { CompanionKind.BRAM }

    override fun getOwnerReference(): EntityReference<LivingEntity>? = entityData.get(DATA_OWNER).orElse(null)

    /** The player whose companion they are (whether or not they travel together yet). */
    val ownerId: UUID? get() = ownerReference?.uuid
    val player: Player? get() = owner as? Player

    var mode = Mode.WAIT
        private set
    /** Where they wait, or the seat by the fire they sit at. */
    var spot: BlockPos? = null
        private set
    /** The Wardfire they sit by (CAMP and ARRIVE). */
    var fire: BlockPos? = null
        private set
    /** Ticks in the current mode. */
    var modeTicks = 0
        private set

    val isDowned: Boolean get() = flag(DOWNED)
    val isSitting: Boolean get() = flag(SITTING)
    /** Drawing the bow: the arms take the vanilla bow pose. */
    val isDrawing: Boolean get() = flag(DRAWING)
    val isTalking: Boolean get() = flag(TALKING)

    // ---- The server's side of a fight (see CompanionFighting) ----

    var act: CompanionAct? = null
        private set
    var actTick = 0
        private set
    var actTarget: LivingEntity? = null
        private set
    /** Ticks before they may start another act. */
    var cooldown = 0
    /** When each act may be used again (game time), for the ones with a long rest. */
    private val readyAt = HashMap<Int, Long>()
    /** Who the blow under way has already hit. */
    val struck = IntOpenHashSet()
    /** The act the one under way leads into, if its target is still there. */
    private var chained: CompanionAct? = null
    private var actSerial = 0
    /** Holding the dagger instead of the bow (Kestrel, up close). */
    var closeIn = false
        private set

    // ---- Down, and getting up ----

    private var lethal = false
    private var downedTicks = 0
    private var calm = 0
    private var helper: UUID? = null
    private var helpTicks = 0

    // ---- Talk and gestures ----

    private var speechUntil = 0
    /** When they last said anything, so they don't talk over themselves. */
    var lastSpoke = -10000
        private set
    private var speechSerial = 0
    private var gestureSerial = 0
    private var gestureUntil = 0
    private var talkUntil = 0
    /** When the last talk began: the end of the one before it, arriving after, doesn't end it. */
    var talkOpenedAt = -100
        private set
    private var waved = -1
    /** A line to say a little later (once they've sat down, say), and a sound to go with it. */
    private var laterKey: String? = null
    private var laterTicks = 0
    private var laterAt = 0
    private var laterSound: SoundEvent? = null
    private var laterSoundAt = 0

    // ---- What the client last heard, for the animations ----

    var shownAct: CompanionAct? = null
        private set
    var shownActStart = 0
        private set
    var shownGesture = Gesture.NONE
        private set
    var shownGestureStart = -1000
        private set
    /** When they went down, sat down or stood up (client ticks), to start those clips from the top. */
    var downedSince = -1000
        private set
    var sittingSince = -1000
        private set

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_KIND, 0.toByte())
        builder.define(DATA_OWNER, Optional.empty())
        builder.define(DATA_FLAGS, 0)
        builder.define(DATA_ACT, 0)
        builder.define(DATA_GESTURE, 0)
        builder.define(DATA_SPEECH, "")
    }

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        if (!level().isClientSide) return
        when (accessor) {
            DATA_ACT -> {
                shownAct = CompanionFighting.byId(entityData.get(DATA_ACT) and 0xFF)
                shownActStart = tickCount
            }
            DATA_GESTURE -> {
                shownGesture = Gesture.entries.getOrElse(entityData.get(DATA_GESTURE) and 0xFF) { Gesture.NONE }
                shownGestureStart = tickCount
            }
            DATA_FLAGS -> {
                val flags = entityData.get(DATA_FLAGS)
                if (flags and DOWNED != 0 && seenFlags and DOWNED == 0) downedSince = tickCount
                if (flags and SITTING != 0 && seenFlags and SITTING == 0) sittingSince = tickCount
                seenFlags = flags
            }
            DATA_SPEECH -> if (entityData.get(DATA_SPEECH).isNotEmpty()) onSpeech?.invoke(this)
        }
    }

    private var seenFlags = 0

    private fun flag(bit: Int): Boolean = entityData.get(DATA_FLAGS) and bit != 0

    private fun setFlag(bit: Int, on: Boolean) {
        val flags = entityData.get(DATA_FLAGS)
        entityData.set(DATA_FLAGS, if (on) flags or bit else flags and bit.inv())
    }

    // ---- Becoming someone ----

    /** Who they are and whose; their gear and their strength at the owner's guild [level]. */
    fun setup(kind: CompanionKind, owner: Player, level: Int) {
        entityData.set(DATA_KIND, kind.ordinal.toByte())
        entityData.set(DATA_OWNER, Optional.of(EntityReference.of(owner.uuid)))
        customName = kind.title
        isCustomNameVisible = false
        getAttribute(Attributes.MAX_HEALTH)?.baseValue = kind.health
        getAttribute(Attributes.ARMOR)?.baseValue = kind.armor
        getAttribute(Attributes.ATTACK_DAMAGE)?.baseValue = kind.damage
        getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = kind.speed
        scale(level)
        health = maxHealth
        setItemSlot(EquipmentSlot.MAINHAND, CompanionFighting.weapon(kind, false))
        for (slot in EquipmentSlot.entries) setDropChance(slot, 0f)
        setPersistenceRequired()
    }

    /** Stronger as the player rises: health and blows grow with their guild [level]. */
    fun scale(level: Int) {
        val l = level.coerceAtLeast(1) - 1
        for ((attribute, share) in listOf(Attributes.MAX_HEALTH to l / 30.0, Attributes.ATTACK_DAMAGE to l / 40.0)) {
            val instance = getAttribute(attribute) ?: continue
            instance.removeModifier(LEVEL_BONUS)
            if (share > 0.0) instance.addPermanentModifier(AttributeModifier(LEVEL_BONUS, share, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        }
        if (health > maxHealth) health = maxHealth
    }

    /** Off to [mode], at [spot] (the seat or the waiting place) by [fire]. */
    fun order(mode: Mode, spot: BlockPos? = null, fire: BlockPos? = null) {
        if (isSitting && mode != Mode.CAMP) standUp()
        this.mode = mode
        this.spot = spot
        this.fire = fire
        modeTicks = 0
        navigation.stop()
    }

    private fun standUp() {
        setFlag(SITTING, false)
        gesture(Gesture.STAND_UP)
    }

    // ---- Every tick ----

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, CompanionFighting.FightGoal(this))
        goalSelector.addGoal(3, ModeGoal(this))
        goalSelector.addGoal(6, LookAtPlayerGoal(this, Player::class.java, 8f))
        goalSelector.addGoal(7, RandomLookAroundGoal(this))
    }

    /** Held in place: down, sitting, talking, or in a gesture that moves the whole body. */
    val still: Boolean get() = isDowned || isSitting || isTalking || gestureUntil > tickCount && entityData.get(DATA_GESTURE) and 0xFF >= Gesture.STAND_UP.ordinal

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        modeTicks++
        if (speechUntil in 1..tickCount) {
            speechUntil = 0
            entityData.set(DATA_SPEECH, "")
        }
        if (!isDowned) {
            laterKey?.let { if (tickCount >= laterAt) { laterKey = null; say(it, laterTicks, force = true) } }
            laterSound?.let { if (tickCount >= laterSoundAt) { laterSound = null; level.playSound(null, x, y, z, it, SoundSource.NEUTRAL, 0.8f, 1f) } }
        }
        if (isTalking && (tickCount > talkUntil || player?.let { distanceToSqr(it) > 144.0 } != false)) setFlag(TALKING, false)
        if (isDowned) {
            tickDowned(level)
            return
        }
        if (cooldown > 0) cooldown--
        val owner = player
        if (owner != null && mode == Mode.FOLLOW) keepUp(level, owner)
        if (still) {
            navigation.stop()
            if (isTalking) owner?.let { lookControl.setLookAt(it, 30f, 30f) }
            if (isSitting) faceFire()
        }
        if (tickCount % 10 == 3) CompanionFighting.pickTarget(level, this)
        CompanionFighting.tickAct(level, this)
        CompanionFighting.tickHolding(this)
    }

    /** Far behind, or left on the other side of a wall: they catch up the way a player's pet would. */
    private fun keepUp(level: ServerLevel, owner: Player) {
        if (owner.level() !== level || owner.isSpectator) return
        // Aboard ship with them, and off again when they step ashore
        val ship = owner.vehicle as? Cog
        if (ship != null) {
            if (vehicle !== ship && !isTalking && tickCount % 10 == 0) {
                stopAct()
                if (isSitting) setFlag(SITTING, false)
                ship.takeAboard(this)
            }
            return
        }
        if (vehicle is Cog) {
            stopRiding()
            if (!teleportNear(owner.blockPosition())) snapTo(owner.x, owner.y, owner.z, owner.yRot, 0f)
            return
        }
        val d2 = distanceToSqr(owner)
        val lost = d2 > TELEPORT_FAR * TELEPORT_FAR || d2 > TELEPORT_UNSEEN * TELEPORT_UNSEEN && !owner.hasLineOfSight(this)
        if (lost && !isTalking && tickCount % 10 == 0) {
            if (isSitting) setFlag(SITTING, false)
            stopAct()
            teleportNear(owner.blockPosition())
        }
    }

    private fun faceFire() {
        val fire = fire ?: return
        val yaw = (Mth.atan2(fire.z + 0.5 - z, fire.x + 0.5 - x) * Mth.RAD_TO_DEG).toFloat() - 90f
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
    }

    // ---- Acts (driven by CompanionFighting) ----

    fun startAct(next: CompanionAct, target: LivingEntity?, then: CompanionAct? = null) {
        act = next
        actTick = 0
        actTarget = target
        chained = then
        struck.clear()
        actSerial = (actSerial + 1) and 0xFFFF
        entityData.set(DATA_ACT, (actSerial shl 8) or next.id)
        if (next.rest > 0) readyAt[next.id] = level().gameTime + next.rest
        navigation.stop()
    }

    /** One tick further into the act; false once it's over. */
    fun advanceAct(): Boolean {
        val a = act ?: return false
        actTick++
        if (actTick < a.length) return true
        val then = chained
        val target = actTarget
        stopAct(false)
        if (then != null && target != null && target.isAlive && CompanionFighting.inRange(this, then, target)) startAct(then, target)
        return act != null
    }

    fun stopAct(interrupted: Boolean = true) {
        val a = act ?: return
        act = null
        actTarget = null
        chained = null
        if (isDrawing) {
            setFlag(DRAWING, false)
            stopUsingItem()
        }
        cooldown = if (interrupted) 10 else a.cooldown + random.nextInt(a.cooldown / 2 + 1)
        entityData.set(DATA_ACT, actSerial shl 8)
    }

    fun ready(act: CompanionAct): Boolean = (readyAt[act.id] ?: 0L) <= level().gameTime

    fun draw(on: Boolean) {
        if (on == isDrawing) return
        setFlag(DRAWING, on)
        if (on) startUsingItem(InteractionHand.MAIN_HAND) else stopUsingItem()
    }

    /** Kestrel swaps to the dagger up close, and back to the bow when there's room. */
    fun holdDagger(on: Boolean) {
        if (on == closeIn) return
        closeIn = on
        setItemSlot(EquipmentSlot.MAINHAND, CompanionFighting.weapon(kind, on))
        playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.5f, 1.2f)
    }

    // ---- Being hurt, and going down ----

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        // /kill and the void still take them (they'll be back beside the player)
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            lethal = true
            try {
                return super.hurtServer(level, source, amount)
            } finally {
                lethal = false
            }
        }
        if (isDowned || ignores(source)) return false
        if (isSitting) setFlag(SITTING, false)
        val hurt = super.hurtServer(level, source, amount)
        val by = source.entity as? LivingEntity
        if (hurt && by != null && !isDowned && Companions.isFoe(this, by) && (target == null || random.nextInt(3) == 0)) target = by
        return hurt
    }

    /** Their own side's blows and spells pass them by, and so do falls and walls (they follow the player anywhere). */
    private fun ignores(source: DamageSource): Boolean {
        val by = source.entity
        if (by is Player || by is CompanionEntity || by is GuildMember || by is Townsperson || by is StoryNpc || by is IronGolem || by is SwornKnight) return true
        return source.`is`(DamageTypeTags.IS_FALL) || source.`is`(DamageTypes.IN_WALL) || source.`is`(DamageTypes.CRAMMING)
    }

    override fun setHealth(health: Float) {
        if (health <= 0f && !lethal && !level().isClientSide && isAlive) {
            super.setHealth(1f)
            goDown()
            return
        }
        super.setHealth(health)
    }

    override fun canBeSeenAsEnemy(): Boolean = !isDowned && super.canBeSeenAsEnemy()

    private fun goDown() {
        if (isDowned) return
        stopAct()
        setFlag(SITTING, false)
        setFlag(DOWNED, true)
        navigation.stop()
        target = null
        downedTicks = 0
        calm = 0
        helper = null
        clearFire()
        val level = level() as? ServerLevel ?: return
        level.playSound(null, x, y, z, CompanionRegistry.DOWNED.get(), SoundSource.NEUTRAL, 1f, 1f)
        // Whatever was after them looks elsewhere
        for (mob in level.getEntitiesOfClass(Mob::class.java, boundingBox.inflate(24.0)) { it.target === this }) mob.target = null
        say("bark.downed", force = true)
    }

    private fun tickDowned(level: ServerLevel) {
        downedTicks++
        navigation.stop()
        deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
        // Downed in lava or deep water, they're brought out to the player
        if (isInLava || isUnderWater) player?.let { if (it.level() === level) teleportNear(it.blockPosition()) }
        val foes = level.getEntitiesOfClass(Mob::class.java, boundingBox.inflate(CALM_RANGE)) { Companions.isFoe(this, it) && it.isAlive }
        calm = if (foes.isEmpty()) calm + 1 else 0
        val helping = helper?.let { level.getPlayerByUUID(it) }
        if (helping != null && helping.isAlive && helping.isCrouching && helping.distanceToSqr(this) < 9.0) {
            helpTicks++
            if (helpTicks % 8 == 0) level.sendParticles(ParticleTypes.HEART, x, getY(0.8), z, 1, 0.3, 0.2, 0.3, 0.0)
            if (helpTicks >= HELP_TICKS) rise(level)
            return
        }
        helper = null
        helpTicks = 0
        if (downedTicks >= DOWNED_MIN && calm >= CALM_TICKS || downedTicks >= DOWNED_MAX) rise(level)
    }

    private fun rise(level: ServerLevel) {
        setFlag(DOWNED, false)
        helper = null
        helpTicks = 0
        health = maxHealth * 0.5f
        gesture(Gesture.REVIVE)
        level.playSound(null, x, y, z, CompanionRegistry.REVIVE.get(), SoundSource.NEUTRAL, 1f, 1f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, getY(0.6), z, 8, 0.4, 0.4, 0.4, 0.0)
        say("bark.revived", force = true)
    }

    /** Whole again, after a rest by a Wardfire. */
    fun restore() {
        if (isDowned) setFlag(DOWNED, false)
        health = maxHealth
        clearFire()
        removeAllEffects()
    }

    // ---- Talking ----

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        val level = level() as? ServerLevel ?: return InteractionResult.SUCCESS
        if (player !is ServerPlayer) return InteractionResult.PASS
        if (ownerId != player.uuid) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.companion_not_yours", kind.title))
            return InteractionResult.SUCCESS_SERVER
        }
        if (isDowned) {
            if (player.isCrouching) {
                helper = player.uuid
                helpTicks = 0
            } else {
                player.sendOverlayMessage(Component.translatable("message.guildmark.companion_help_up", kind.title))
            }
            return InteractionResult.SUCCESS_SERVER
        }
        Companions.talk(level, player, this)
        return InteractionResult.SUCCESS_SERVER
    }

    /** The dialogue is open with their player: they stop and face them. */
    fun listen(talking: Boolean) {
        setFlag(TALKING, talking)
        if (talking) {
            talkOpenedAt = tickCount
            talkUntil = tickCount + TALK_TICKS
            stopAct()
            navigation.stop()
        }
    }

    /**
     * Says [key] (a line under character.guildmark.<id>.), shown over their head and in the player's HUD, and spoken
     * in their voice where it was recorded. Nothing short of [force] cuts in on another line.
     */
    fun say(key: String, ticks: Int = BARK_TICKS, force: Boolean = false): Boolean {
        if (!force && speechUntil > tickCount) return false
        speechSerial = (speechSerial + 1) % 1000
        entityData.set(DATA_SPEECH, "$key@$speechSerial")
        speechUntil = tickCount + ticks
        lastSpoke = tickCount
        return true
    }

    /** Says [key] in [delay] ticks, unless they already have something to say then; false if so. */
    fun sayLater(key: String, delay: Int, ticks: Int = BARK_TICKS): Boolean {
        if (laterKey != null || isDowned) return false
        laterKey = key
        laterTicks = ticks
        laterAt = tickCount + delay
        return true
    }

    fun playSoundLater(sound: SoundEvent, delay: Int) {
        laterSound = sound
        laterSoundAt = tickCount + delay
    }

    /** The line they're saying now (under character.guildmark.<id>.), or "". */
    val speech: String get() = entityData.get(DATA_SPEECH).substringBefore('@')

    fun gesture(gesture: Gesture) {
        gestureSerial = (gestureSerial + 1) and 0xFFFF
        entityData.set(DATA_GESTURE, (gestureSerial shl 8) or gesture.ordinal)
        gestureUntil = tickCount + gesture.length
    }

    // ---- Moving about ----

    /** Somewhere to stand within a few blocks of [around], the way a pet finds its way back to its owner. */
    fun teleportNear(around: BlockPos): Boolean {
        for (attempt in 0 until 12) {
            val dx = random.nextIntBetweenInclusive(-3, 3)
            val dz = random.nextIntBetweenInclusive(-3, 3)
            if (abs(dx) < 2 && abs(dz) < 2) continue
            val pos = around.offset(dx, random.nextIntBetweenInclusive(-1, 1), dz)
            if (!canStandAt(pos)) continue
            arriveAt(pos)
            return true
        }
        return false
    }

    /** Appears at [pos] (a puff of embers marks the spot) and stops whatever path they were on. */
    fun arriveAt(pos: BlockPos) {
        snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yRot, xRot)
        navigation.stop()
        resetFallDistance()
    }

    fun canStandAt(pos: BlockPos): Boolean {
        if (WalkNodeEvaluator.getPathTypeStatic(this, pos) != PathType.WALKABLE) return false
        if (level().getBlockState(pos.below()).block is LeavesBlock) return false
        return level().noCollision(this, boundingBox.move(Vec3.atBottomCenterOf(pos).subtract(position())))
    }

    /** Where they keep to beside [owner]: each in their own place, the healer and the archer further back. */
    fun formation(owner: Player): Vec3 {
        val (side, back) = when (kind.style) {
            Style.BLADE -> 1.6 to 0.6
            Style.PRAYER -> -0.6 to 3.2
            Style.BOW -> -2.2 to 1.8
            Style.SORCERY -> 2.0 to 2.4
        }
        val yaw = Math.toRadians(owner.yRot.toDouble())
        val forward = Vec3(-sin(yaw), 0.0, cos(yaw))
        val right = Vec3(-cos(yaw), 0.0, -sin(yaw))
        return owner.position().add(right.scale(side)).subtract(forward.scale(back))
    }

    /** How they get around when there's nothing to fight: following, waiting, sitting by the fire, arriving, leaving. */
    private class ModeGoal(private val c: CompanionEntity) : Goal() {
        private var repath = 0

        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean = !c.still && c.act == null && c.target == null
        override fun canContinueToUse(): Boolean = canUse()
        override fun requiresUpdateEveryTick(): Boolean = true

        override fun stop() {
            c.navigation.stop()
        }

        override fun tick() {
            when (c.mode) {
                Mode.FOLLOW -> follow()
                Mode.WAIT -> c.spot?.let { if (c.distanceToSqr(Vec3.atBottomCenterOf(it)) > 4.0) walkTo(Vec3.atBottomCenterOf(it), WALK) else c.navigation.stop() }
                Mode.CAMP, Mode.ARRIVE -> toSeat()
                Mode.LEAVE -> leave()
            }
        }

        private fun follow() {
            val owner = c.player ?: return
            if (owner.level() !== c.level()) return
            val to = c.formation(owner)
            val far = c.distanceToSqr(owner) > 100.0
            if (c.distanceToSqr(to) > 6.0 || far) walkTo(to, if (owner.isSprinting || far) RUN else WALK)
            else if (c.navigation.isDone.not() && c.distanceToSqr(to) < 2.0) c.navigation.stop()
        }

        private fun walkTo(to: Vec3, speed: Double) {
            if (--repath > 0 && !c.navigation.isDone) return
            repath = 8
            c.navigation.moveTo(to.x, to.y, to.z, speed)
        }

        /** To the seat by the fire: there they sit (CAMP), or greet the player first (ARRIVE). */
        private fun toSeat() {
            val seat = c.spot ?: return
            val at = Vec3.atBottomCenterOf(seat)
            val d2 = c.position().subtract(at).horizontalDistanceSqr()
            if (c.mode == Mode.ARRIVE) {
                if (c.waved < 0 && (d2 < 2.25 || c.player?.let { c.distanceToSqr(it) < 9.0 } == true)) {
                    c.navigation.stop()
                    c.player?.let { c.lookControl.setLookAt(it, 30f, 30f) }
                    c.gesture(Gesture.WAVE)
                    c.say("meet", MEET_TICKS, force = true)
                    c.waved = c.tickCount
                } else if (c.waved >= 0) {
                    c.player?.let { c.lookControl.setLookAt(it, 30f, 30f) }
                    if (c.tickCount - c.waved > 50) c.order(Mode.CAMP, seat, c.fire)
                    return
                }
            }
            if (d2 < 0.6) {
                c.navigation.stop()
                if (c.mode == Mode.CAMP && !c.isSitting) c.sitDown(seat)
                return
            }
            // A seat they can't get to, they reach the long way round (out of the player's sight)
            if (c.modeTicks > SEAT_PATIENCE && (c.player?.hasLineOfSight(c) != true || c.modeTicks > SEAT_PATIENCE * 2)) {
                c.arriveAt(seat)
                return
            }
            if (d2 < 4.0 && c.mode == Mode.CAMP) c.moveControl.setWantedPosition(at.x, at.y, at.z, 0.6)
            else walkTo(at, WALK)
        }

        private fun leave() {
            val owner = c.player
            if (owner == null || c.modeTicks > 200 || c.modeTicks > 60 && !owner.hasLineOfSight(c)) {
                (c.level() as? ServerLevel)?.sendParticles(ParticleTypes.POOF, c.x, c.getY(0.5), c.z, 8, 0.3, 0.5, 0.3, 0.02)
                c.discard()
                return
            }
            val away = c.position().subtract(owner.position()).multiply(1.0, 0.0, 1.0)
            val dir = if (away.lengthSqr() < 0.01) Vec3(1.0, 0.0, 0.0) else away.normalize()
            walkTo(c.position().add(dir.scale(16.0)), WALK)
        }
    }

    /** Down by the fire, facing it. */
    fun sitDown(seat: BlockPos) {
        snapTo(seat.x + 0.5, seat.y.toDouble(), seat.z + 0.5, yRot, xRot)
        setFlag(SITTING, true)
        faceFire()
    }

    // ---- Housekeeping ----

    override fun removeWhenFarAway(distance: Double): Boolean = false
    override fun requiresCustomPersistence(): Boolean = true
    override fun shouldDropExperience(): Boolean = false
    override fun canBeLeashed(): Boolean = false
    override fun isPushable(): Boolean = !isSitting && !isDowned && super.isPushable()
    override fun playAmbientSound() = Unit
    override fun getHurtSound(source: DamageSource): SoundEvent = SoundEvents.PLAYER_HURT
    override fun getDeathSound(): SoundEvent = SoundEvents.PLAYER_DEATH

    override fun considersEntityAsAlly(other: Entity): Boolean =
        other is CompanionEntity || other is Player || other.uuid == ownerId || super.considersEntityAsAlly(other)

    /** Stance broken by a big hit staggers them like anyone else, but never for long. */
    override fun tick() {
        super.tick()
        if (!level().isClientSide && Stance.isStaggered(this) && act != null) stopAct()
    }

    companion object {
        private const val DOWNED = 1
        private const val SITTING = 2
        private const val DRAWING = 4
        private const val TALKING = 8

        const val WALK = 1.0
        const val RUN = 1.3
        /** Past this they catch the player up at once; past [TELEPORT_UNSEEN] if the player can't see them. */
        private const val TELEPORT_FAR = 40.0
        private const val TELEPORT_UNSEEN = 24.0
        /** Ticks a line stays up. */
        const val BARK_TICKS = 90
        private const val MEET_TICKS = 110
        private const val TALK_TICKS = 2400
        private const val SEAT_PATIENCE = 240
        /** Down at least this long, then up once nothing hostile is near for [CALM_TICKS]; never longer than [DOWNED_MAX]. */
        private const val DOWNED_MIN = 100
        private const val CALM_TICKS = 100
        private const val CALM_RANGE = 14.0
        private const val DOWNED_MAX = 900
        /** Ticks of crouching beside them to help them up. */
        const val HELP_TICKS = 40

        private val LEVEL_BONUS: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "companion_level")

        val DATA_KIND: EntityDataAccessor<Byte> = SynchedEntityData.defineId(CompanionEntity::class.java, EntityDataSerializers.BYTE)
        val DATA_OWNER: EntityDataAccessor<Optional<EntityReference<LivingEntity>>> =
            SynchedEntityData.defineId(CompanionEntity::class.java, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE)
        val DATA_FLAGS: EntityDataAccessor<Int> = SynchedEntityData.defineId(CompanionEntity::class.java, EntityDataSerializers.INT)
        /** (serial << 8) | act id; the serial makes the same act twice running still restart. */
        val DATA_ACT: EntityDataAccessor<Int> = SynchedEntityData.defineId(CompanionEntity::class.java, EntityDataSerializers.INT)
        val DATA_GESTURE: EntityDataAccessor<Int> = SynchedEntityData.defineId(CompanionEntity::class.java, EntityDataSerializers.INT)
        /** "<key>@<serial>", or "" while they're quiet. */
        val DATA_SPEECH: EntityDataAccessor<String> = SynchedEntityData.defineId(CompanionEntity::class.java, EntityDataSerializers.STRING)

        /** Set by the client: a line was just said (play their voice, show the words). */
        var onSpeech: ((CompanionEntity) -> Unit)? = null

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 40.0)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.ARMOR, 4.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.FOLLOW_RANGE, 32.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.3)
            .add(Attributes.STEP_HEIGHT, 1.0)
    }
}
