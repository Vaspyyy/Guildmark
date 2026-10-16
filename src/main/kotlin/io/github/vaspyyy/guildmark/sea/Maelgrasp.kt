package io.github.vaspyyy.guildmark.sea

import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.lair.LoreBooks
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.network.BossCuePayload
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.ship.Cog
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.entity.PartEntity
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Maelgrasp: a colossal horror of the deep sea that comes up under a ship far out over the deeps, at night or in
 * a storm, and takes hold of her.
 *
 * First the omens: the wind dies and the sea goes still and wrong, something vast groans far below, bubbles well up
 * round her hull, and her bell rings by itself. A great shadow circles under her keel. Then four arms burst up round
 * her and seize her, and she can't sail a yard while they hold her ([MaelgraspArm]): they rear and slam down across
 * her deck, sweep along it, snatch her crew and hurl them into the sea, drape over her rail and crush her timbers.
 * Hacked at, they bleed it; hacked through, they're severed.
 *
 * Half dead, it comes up itself beside her: a mantle like a hill of wet stone, one enormous eye, and a black beak that
 * bites into her rail. Struck in the eye, it's hurt most. It spews ink over her deck, sends up fresh arms on her other
 * side, and once, badly hurt, it dives under her keel and comes up on her far side. Slain, it sinks into the dark, and
 * what it leaves (its beak, a heart of the sea, a sealed cache of relics and a page of the old lore) is found in her hold.
 *
 * Nobody aboard for a while, the ship gone, or the fight gone on far too long, and it lets her go and sinks away.
 */
class Maelgrasp(type: EntityType<out Maelgrasp>, level: Level) : Monster(type, level) {
    enum class Phase { OMEN, RISE, ARMS, SURFACE, EYE, DIVE, DEATH, LEAVE }

    /**
     * A place round a ship an arm rises at, in her own frame (blocks to starboard and forward of her middle), the
     * degrees it faces turned from her heading (to face her middle), which side of her it's on (1 starboard, -1 port,
     * 0 at the bow or stern) and the way out from her there.
     */
    class Slot(val x: Double, val z: Double, val turn: Float, val side: Int, val ox: Double, val oz: Double)

    // ---- On the server ----

    private var shipId = -1
    private var phaseTicks = 0
    private var fightTicks = 0
    private var tier = 1
    /** How much harder its blows land out in the wilds than near home (tier by tier). */
    var damageScale = 1f
        private set
    private var healthScale = 1f
    /** Each place round the ship: the entity id of the arm risen there, or -1. */
    private val armAt = IntArray(SLOTS.size) { -1 }
    /** The side of the ship its head comes up on (1 starboard, -1 port). */
    private var headSide = 1
    private var dived = false
    private var circle = 0f
    private var nextMove = 0
    private var nextBite = 0
    private var nextInk = 0
    private var nextArm = 0
    private var biting = -1
    private var inking = -1
    private var crushAt = 0
    private var lonely = 0
    /** Everyone who's been aboard while it held her: they share in the slaying, and won't meet it again for a while. */
    private val crew = HashSet<UUID>()
    private var reloaded = false

    private val bossEvent = ServerBossEvent(
        Mth.createInsecureUUID(random), Component.translatable("entity.guildmark.maelgrasp"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10,
    )

    /** Its eye: struck there, it's hurt the most. */
    private val eye = SeaPart(this, EYE_SIZE, EYE_SIZE) { _, level, source, amount -> eyeStruck(level, source, amount) }
    private val parts: Array<SeaPart> = arrayOf(eye)

    // ---- On a client ----

    var riseO = 0f
        private set
    var riseN = 0f
        private set
    var lookYawO = 0f
        private set
    var lookYawN = 0f
        private set
    var lookPitchO = 0f
        private set
    var lookPitchN = 0f
        private set
    /** The tick it last began a blink, and when it'll next blink. */
    var blinkAt = -100
        private set
    private var nextBlink = 40

    init {
        xpReward = 0
        isNoGravity = true
        noPhysics = true
        setPersistenceRequired()
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_PHASE, Phase.OMEN.ordinal.toByte())
        builder.define(DATA_RISE, 0f)
        builder.define(DATA_LOOK, -1)
        builder.define(DATA_BITE, -1)
        builder.define(DATA_INK, -1)
        builder.define(DATA_ROAR, -1)
        builder.define(DATA_FLINCH, -1)
        builder.define(DATA_RAGE, 0f)
    }

    var phase: Phase
        get() = PHASES[entityData.get(DATA_PHASE).toInt().coerceIn(0, PHASES.size - 1)]
        private set(value) {
            if (value != phase) entityData.set(DATA_PHASE, value.ordinal.toByte())
            phaseTicks = 0
        }

    /** Whether there's a fight on: from the moment its arms come up till it's slain or gone. */
    val fighting: Boolean get() = phase.ordinal in Phase.RISE.ordinal..Phase.DIVE.ordinal && isAlive

    /** How far its head is up out of the sea, 0 to 1. */
    val rise: Float get() = entityData.get(DATA_RISE)

    /** The entity it's watching with its eye, or -1. */
    val lookingAt: Int get() = entityData.get(DATA_LOOK)

    /** The game time (low bits) it began its last bite, ink, roar, and was struck in the eye; -1 if never. */
    val biteAt: Int get() = entityData.get(DATA_BITE)
    val inkAt: Int get() = entityData.get(DATA_INK)
    val roarAt: Int get() = entityData.get(DATA_ROAR)
    val flinchAt: Int get() = entityData.get(DATA_FLINCH)

    /** 0 to 1: its fury, rising as it's hurt in the second half of the fight. */
    val rage: Float get() = entityData.get(DATA_RAGE)

    /** Ticks since [at] (one of the times above), with [partial]; or -1 when it hasn't happened. */
    fun since(at: Int, partial: Float): Float {
        if (at < 0) return -1f
        val now = (level().gameTime and 0x3FFFFFFF).toInt()
        val d = now - at
        return if (d < 0) -1f else d + partial
    }

    private fun stamp(key: EntityDataAccessor<Int>) {
        entityData.set(key, (level().gameTime and 0x3FFFFFFF).toInt())
    }

    // ---- Parts ----

    override fun isMultipartEntity(): Boolean = true
    override fun getParts(): Array<out PartEntity<*>> = parts

    override fun recreateFromPacket(packet: ClientboundAddEntityPacket) {
        super.recreateFromPacket(packet)
        for (i in parts.indices) parts[i].setId(packet.id + i + 1)
    }

    /** Where its eye is in the world, as it is now. */
    fun eyePoint(): Vec3 = Tentacle.toWorld(0f, EYE_UP, EYE_FORWARD, position(), yRot)

    /** Where the tip of its beak is in the world, as it is now. */
    fun beakPoint(): Vec3 = Tentacle.toWorld(0f, BEAK_UP, BEAK_FORWARD, position(), yRot)

    // ---- Set going ----

    /** Begins its coming for [ship]: with the omens first, or (for testing) straight up out of the sea. */
    private fun comeFor(level: ServerLevel, ship: Cog, omen: Boolean) {
        shipId = ship.id
        tier = QuestGenerator.tierAt(ship.blockX, ship.blockZ)
        val extra = (tier - 1).coerceAtLeast(0)
        healthScale = 1f + 0.35f * extra
        damageScale = 1f + 0.15f * extra
        if (extra > 0) getAttribute(Attributes.MAX_HEALTH)?.addOrReplacePermanentModifier(AttributeModifier(TIER_HEALTH, 0.35 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        health = maxHealth
        headSide = if (random.nextBoolean()) 1 else -1
        circle = random.nextFloat() * Mth.TWO_PI
        phase = if (omen) Phase.OMEN else Phase.RISE
        for (player in Seas.crew(ship)) meet(level, player)
        SeaHunts.scatterFrom(level, ship)
        for (player in Seas.crew(ship)) {
            (player as? ServerPlayer)?.sendOverlayMessage(Component.translatable(if (omen) "message.guildmark.sea.omen" else "message.guildmark.sea.rising"))
        }
    }

    /** [player] has met it: they're in the fight, and it won't come for them again for a while. */
    private fun meet(level: ServerLevel, player: Player) {
        if (crew.add(player.uuid)) player.setData(SeaRegistry.MAELGRASP_MET, level.gameTime)
    }

    // ---- Every tick ----

    override fun tick() {
        super.tick()
        val level = level()
        if (level is ServerLevel) serverTick(level) else clientTick()
        eye.placeAt(eyePoint())
    }

    private fun clientTick() {
        riseO = riseN
        riseN += (rise - riseN) * 0.3f
        lookYawO = lookYawN
        lookPitchO = lookPitchN
        // Its eye follows whoever it's watching, as far as its socket lets it turn
        val watched = level().getEntity(lookingAt)
        var yaw = 0f
        var pitch = 0f
        if (watched != null && isAlive) {
            val from = eyePoint()
            val to = watched.getEyePosition(1f)
            val local = Tentacle.toLocal(to, from, yRot)
            val flat = kotlin.math.sqrt(local.x * local.x + local.z * local.z)
            yaw = (Mth.atan2(local.x, local.z) * Mth.RAD_TO_DEG).toFloat().coerceIn(-LOOK_YAW, LOOK_YAW)
            pitch = (Mth.atan2(local.y, flat) * Mth.RAD_TO_DEG).toFloat().coerceIn(-LOOK_PITCH, LOOK_PITCH)
        }
        lookYawN += (yaw - lookYawN) * 0.2f
        lookPitchN += (pitch - lookPitchN) * 0.2f
        // It blinks now and then; struck in the eye, at once
        if (--nextBlink <= 0 || since(flinchAt, 0f) in 0f..0.5f) {
            blinkAt = tickCount
            nextBlink = 60 + random.nextInt(90)
        }
        clientEffects?.invoke(this)
    }

    private fun serverTick(level: ServerLevel) {
        if (reloaded) {
            discard()
            return
        }
        phaseTicks++
        if (isDeadOrDying) return
        val ship = level.getEntity(shipId) as? Cog
        if (ship == null || ship.isRemoved) {
            if (phase != Phase.LEAVE) leave()
            leaving(level, null)
            return
        }
        val aboard = Seas.crew(ship)
        for (player in aboard) meet(level, player)
        if (phase != Phase.LEAVE) {
            fightTicks++
            lonely = if (aboard.isEmpty()) lonely + 1 else 0
            if (lonely > LONELY_TICKS || fightTicks > FIGHT_TICKS) leave()
        }
        if (fighting) {
            // Seized: she makes no way while it has her
            ship.holdFast(5)
            if (tickCount % 10 == 0) updateBossBar(level, ship)
            bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
            updateRage()
        }
        when (phase) {
            Phase.OMEN -> omen(level, ship)
            Phase.RISE -> rising(level, ship)
            Phase.ARMS -> arms(level, ship, aboard)
            Phase.SURFACE -> surfacing(level, ship)
            Phase.EYE -> eyeToEye(level, ship, aboard)
            Phase.DIVE -> diving(level, ship)
            Phase.LEAVE -> leaving(level, ship)
            Phase.DEATH -> {}
        }
        if (fighting) crush(level, ship)
    }

    // ---- The omens ----

    private fun omen(level: ServerLevel, ship: Cog) {
        // The wind dies away, and the sea goes still
        ship.becalm((phaseTicks / CALM_TICKS.toFloat()).coerceAtMost(1f), 5)
        // A shadow circling under her keel
        circle += CIRCLE_SPEED
        val at = ship.localToWorld(cos(circle) * 5.0, -DEEP, sin(circle) * 6.0)
        val ahead = ship.localToWorld(cos(circle + 0.2f) * 5.0, -DEEP, sin(circle + 0.2f) * 6.0)
        placeAt(at, yawToward(at, ahead))
        if (phaseTicks % OMEN_GROAN == 20) {
            level.playSound(null, x, ship.y - 4.0, z, SeaRegistry.OMEN.get(), SoundSource.HOSTILE, 3f, 0.85f + random.nextFloat() * 0.25f)
        }
        if (phaseTicks % OMEN_BUBBLES == 50) {
            level.playSound(null, ship.x, ship.y, ship.z, SeaRegistry.BUBBLES.get(), SoundSource.HOSTILE, 1.6f, 0.9f + random.nextFloat() * 0.2f)
        }
        if (phaseTicks % 4 == 0) bubblesRound(level, ship, 6 + phaseTicks / 40)
        // Her bell rings, with no hand on it
        if (phaseTicks == BELL_FIRST || phaseTicks == BELL_SECOND) ship.strikeBell(level)
        if (phaseTicks >= OMEN_TICKS) phase = Phase.RISE
    }

    private fun bubblesRound(level: ServerLevel, ship: Cog, count: Int) {
        repeat(count) {
            val a = random.nextFloat() * Mth.TWO_PI
            val r = 2.5 + random.nextDouble() * 4.0
            val at = ship.localToWorld(cos(a) * r, 0.0, sin(a) * r * 1.6)
            val surface = Seas.surface(level, at.x, at.z) ?: ship.y
            level.sendParticles(ParticleTypes.BUBBLE_POP, at.x, surface + 0.05, at.z, 3, 0.3, 0.0, 0.3, 0.02)
            level.sendParticles(ParticleTypes.BUBBLE, at.x, surface - 1.0, at.z, 4, 0.3, 0.6, 0.3, 0.05)
        }
    }

    // ---- The arms come up ----

    private fun rising(level: ServerLevel, ship: Cog) {
        val at = ship.localToWorld(0.0, -DEEP + 1.0, 0.0)
        placeAt(at, ship.yRot)
        if (phaseTicks == 1) {
            level.playSound(null, ship.x, ship.y, ship.z, SeaRegistry.SURGE.get(), SoundSource.HOSTILE, 3f, 0.9f)
            level.sendParticles(ParticleTypes.SPLASH, ship.x, ship.y + 0.2, ship.z, 120, 4.0, 0.2, 5.0, 0.5)
        }
        // One after another round her, a heartbeat apart
        for (i in 0 until FIRST_ARMS) if (phaseTicks == 4 + i * ARM_STAGGER) raiseArm(level, ship, i)
        if (phaseTicks == RISE_ROAR) {
            stamp(DATA_ROAR)
            level.playSound(null, ship.x, ship.y - 6.0, ship.z, SeaRegistry.ROAR.get(), SoundSource.HOSTILE, 3f, 0.7f)
        }
        if (phaseTicks >= RISE_TICKS) {
            phase = Phase.ARMS
            nextMove = 20
        }
    }

    private fun raiseArm(level: ServerLevel, ship: Cog, slot: Int) {
        val arm = SeaRegistry.MAELGRASP_ARM.get().create(level, EntitySpawnReason.EVENT) ?: return
        val at = ship.localToWorld(SLOTS[slot].x, 0.0, SLOTS[slot].z)
        arm.snapTo(at.x, ship.y, at.z, ship.yRot + SLOTS[slot].turn, 0f)
        arm.riseFor(this, ship, slot, tier)
        if (level.addFreshEntity(arm)) armAt[slot] = arm.id
    }

    /** Its arms risen and still on it, by place. */
    private fun liveArms(level: ServerLevel): List<MaelgraspArm> {
        val out = ArrayList<MaelgraspArm>(SLOTS.size)
        for (i in armAt.indices) {
            val arm = level.getEntity(armAt[i]) as? MaelgraspArm
            if (arm == null || arm.isRemoved || !arm.isAlive || arm.mode == MaelgraspArm.Mode.WITHDRAW) armAt[i] = -1 else out.add(arm)
        }
        return out
    }

    // ---- Holding her ----

    private fun arms(level: ServerLevel, ship: Cog, aboard: List<Player>) {
        placeAt(ship.localToWorld(0.0, -DEEP + 1.0, 0.0), ship.yRot + sin(fightTicks * 0.01f) * 20f)
        val arms = liveArms(level)
        direct(level, ship, arms, aboard, MOVE_GAP, MOVE_SPREAD)
        if (health <= maxHealth * SURFACE_AT || arms.isEmpty()) {
            // It comes up on the side it has fewer arms on, letting go there as it comes
            val starboard = arms.count { it.spec.side > 0 }
            val port = arms.count { it.spec.side < 0 }
            headSide = when {
                starboard < port -> 1
                port < starboard -> -1
                else -> headSide
            }
            for (arm in arms) if (arm.spec.side == headSide) arm.withdraw()
            phase = Phase.SURFACE
        }
    }

    /** Sends its arms at the ship, a few at a time: never more than two striking at once, nor two holding on. */
    private fun direct(level: ServerLevel, ship: Cog, arms: List<MaelgraspArm>, aboard: List<Player>, gap: Int, spread: Int) {
        if (--nextMove > 0) return
        nextMove = gap + random.nextInt(spread)
        if (arms.count { it.mode.attacking } >= MAX_STRIKING) return
        val ready = arms.filter { it.ready }
        if (ready.isEmpty()) return
        val onDeck = Seas.aboard(ship)
        val swimmers = level.getEntitiesOfClass(Player::class.java, ship.boundingBox.inflate(SWIMMER_RANGE)) { it.isAlive && !it.isSpectator && it.vehicle == null && it.isInWater }
        val holding = arms.count { it.mode.holding }
        val anyCaught = arms.any { it.mode == MaelgraspArm.Mode.LIFT || it.mode == MaelgraspArm.Mode.SQUEEZE || it.mode == MaelgraspArm.Mode.REACH }
        var roll = random.nextInt(100)
        // Slams across her deck
        if (onDeck.isNotEmpty() && roll < 38) {
            val victim = onDeck[random.nextInt(onDeck.size)]
            val z = (ship.stationOf(victim)?.z ?: ship.worldToLocal(victim.position()).z).coerceIn(-4.0, 3.8)
            ready.minByOrNull { reachAlong(it.spec, z) }?.slam(z)
            return
        }
        roll -= 38
        // A sweep along her deck from one end
        if (onDeck.isNotEmpty() && roll < 18) {
            val sweepers = ready.filter { it.spec.side != 0 }
            if (sweepers.isNotEmpty()) {
                sweepers[random.nextInt(sweepers.size)].sweep()
                return
            }
        }
        roll -= 18
        // Snatching someone up: off the deck, or out of the water
        if (!anyCaught && roll < 24) {
            val choices = onDeck + swimmers
            if (choices.isNotEmpty()) {
                val victim = choices[random.nextInt(choices.size)]
                ready.minByOrNull { it.distanceToSqr(victim) }?.grab(victim)
                return
            }
        }
        // Over her rail, holding on
        if (holding < MAX_HOLDING) {
            ready[random.nextInt(ready.size)].hold()
            return
        }
        if (onDeck.isNotEmpty()) {
            val victim = onDeck[random.nextInt(onDeck.size)]
            val z = (ship.stationOf(victim)?.z ?: 0.0).coerceIn(-4.0, 3.8)
            ready.minByOrNull { reachAlong(it.spec, z) }?.slam(z)
        }
    }

    /** How far an arm at [slot] is from a blow at [z] along her deck. */
    private fun reachAlong(slot: Slot, z: Double): Double = if (slot.side != 0) abs(slot.z - z) else abs(slot.z - z) * 0.6

    /** Two or more arms draped over her rail haul her over and crush her timbers. */
    private fun crush(level: ServerLevel, ship: Cog) {
        var list = 0f
        var holders = 0
        for (id in armAt) {
            val arm = level.getEntity(id) as? MaelgraspArm ?: continue
            if (!arm.holding) continue
            holders++
            val s = arm.spec
            // Heeled toward the side it hangs on (to port above 0); one at her bow or stern only weighs her down
            list += -s.side * LIST_PER_ARM
        }
        if (holders == 0) return
        ship.haulOver(list.coerceIn(-MAX_LIST, MAX_LIST), 5)
        if (holders < 2) return
        if (--crushAt > 0) return
        crushAt = CRUSH_EVERY
        ship.strain(level, CRUSH_HULL * damageScale)
        level.playSound(null, ship.x, ship.y + 0.6, ship.z, SeaRegistry.CRUSH.get(), SoundSource.HOSTILE, 2f, 0.8f + random.nextFloat() * 0.3f)
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState()), ship.x, ship.y + 1.0, ship.z, 16, 1.2, 0.4, 2.4, 0.08)
    }

    // ---- Its head ----

    private fun surfacing(level: ServerLevel, ship: Cog) {
        val u = phaseTicks / SURFACE_TICKS.toFloat()
        val yaw = Mth.wrapDegrees(ship.yRot - 90f * headSide)
        if (phaseTicks <= SURFACE_SWIM) {
            // Out from under her keel to her side, still deep
            val k = ease(phaseTicks / SURFACE_SWIM.toFloat()).toDouble()
            placeAt(ship.localToWorld(headSide * HEAD_OUT * k, -DEEP + 1.0, 0.0), yaw)
        } else {
            val k = ease((phaseTicks - SURFACE_SWIM) / (SURFACE_TICKS - SURFACE_SWIM).toFloat())
            placeAt(head(ship, (-DEEP + 1.0) * (1.0 - k)), yaw)
            entityData.set(DATA_RISE, k)
        }
        if (phaseTicks == SURFACE_SWIM) {
            level.playSound(null, ship.x, ship.y, ship.z, SeaRegistry.RISE.get(), SoundSource.HOSTILE, 3f, 0.9f)
        }
        if (phaseTicks == SURFACE_TICKS - 12) splash(level, 160)
        if (phaseTicks == SURFACE_TICKS - 2) roar(level, 1f)
        if (u >= 1f) {
            phase = Phase.EYE
            entityData.set(DATA_RISE, 1f)
            nextBite = 60
            nextInk = 160
            nextArm = NEW_ARM_EVERY / 2
        }
    }

    /** Beside her, its head up out of the sea: watching her crew with its eye, biting her rail, spewing ink. */
    private fun eyeToEye(level: ServerLevel, ship: Cog, aboard: List<Player>) {
        val breathe = sin(fightTicks * 0.05f) * 0.15
        placeAt(head(ship, breathe), Mth.wrapDegrees(ship.yRot - 90f * headSide))
        // Watching whoever's nearest its eye
        val eyeAt = eyePoint()
        val watched = aboard.minByOrNull { it.distanceToSqr(eyeAt) }
        val look = watched?.id ?: -1
        if (look != lookingAt) entityData.set(DATA_LOOK, look)
        // Fresh arms come up away from its head, three at most
        val arms = liveArms(level)
        if (--nextArm <= 0 && arms.size < MAX_ARMS_LATE) {
            nextArm = NEW_ARM_EVERY
            val free = SLOTS.indices.filter { armAt[it] < 0 && SLOTS[it].side != headSide }
            if (free.isNotEmpty()) raiseArm(level, ship, free[random.nextInt(free.size)])
        }
        direct(level, ship, arms, aboard, MOVE_GAP_LATE, MOVE_SPREAD_LATE)
        bite(level, ship)
        ink(level, ship)
        if (!dived && health <= maxHealth * DIVE_AT && biting < 0 && inking < 0) {
            dived = true
            for (arm in arms) if (arm.spec.side == -headSide) arm.withdraw()
            level.playSound(null, x, y + 2.0, z, SeaRegistry.DIVE.get(), SoundSource.HOSTILE, 3f, 0.9f)
            splash(level, 100)
            phase = Phase.DIVE
        }
    }

    /** Its beak into her rail beside it: rearing back, then lunging, crunching into her timbers and whoever's there. */
    private fun bite(level: ServerLevel, ship: Cog) {
        if (biting < 0) {
            if (--nextBite > 0) return
            nextBite = BITE_GAP + random.nextInt(BITE_SPREAD)
            biting = 0
            stamp(DATA_BITE)
            return
        }
        biting++
        if (biting == BITE_LAND) {
            val z = 0.0
            val local = Vec3(headSide * Seas.beam(z), 1.4, z)
            val at = ship.localToWorld(local.x, local.y, local.z)
            ship.strain(level, BITE_HULL * damageScale)
            level.playSound(null, at.x, at.y, at.z, SeaRegistry.BITE.get(), SoundSource.HOSTILE, 2.5f, 0.9f + random.nextFloat() * 0.2f)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState()), at.x, at.y, at.z, 40, 0.8, 0.4, 0.8, 0.2)
            for (victim in Seas.aboard(ship)) {
                val station = ship.stationOf(victim)
                val px = station?.x ?: ship.worldToLocal(victim.position()).x
                val pz = station?.z ?: ship.worldToLocal(victim.position()).z
                val dx = px - local.x
                val dz = pz - local.z
                if (dx * dx + dz * dz < BITE_REACH * BITE_REACH) victim.hurtServer(level, damageSources().mobAttack(this), BITE_DAMAGE * damageScale)
            }
        }
        if (biting >= BITE_TICKS) biting = -1
    }

    /** Black ink from its siphon over her deck: blinding, stinging. */
    private fun ink(level: ServerLevel, ship: Cog) {
        if (inking < 0) {
            if (--nextInk > 0 || biting >= 0) return
            nextInk = INK_GAP + random.nextInt(INK_SPREAD)
            inking = 0
            stamp(DATA_INK)
            val (mouth, _) = siphon()
            level.playSound(null, mouth.x, mouth.y, mouth.z, SeaRegistry.INK.get(), SoundSource.HOSTILE, 2.5f, 0.9f + random.nextFloat() * 0.2f)
            return
        }
        inking++
        if (inking in INK_SPRAY_FROM..INK_SPRAY_TO) {
            // From its siphon in an arc over her deck: the jet out of it, bending up over her rail, and the ink raining down
            val k = (inking - INK_SPRAY_FROM) / (INK_SPRAY_TO - INK_SPRAY_FROM).toDouble()
            val across = headSide * Mth.lerp(k, 1.6, -1.4)
            val (mouth, way) = siphon()
            val toDeck = ship.localToWorld(across, 2.2, 0.0).subtract(mouth).normalize()
            val jet = way.scale(0.6).add(toDeck.scale(0.8)).add(0.0, 0.45, 0.0).normalize()
            for (n in 0 until 7) {
                val speed = 0.4 + random.nextDouble() * 0.3
                level.sendParticles(
                    ParticleTypes.SQUID_INK, mouth.x, mouth.y, mouth.z, 0,
                    jet.x + random.nextGaussian() * 0.07, jet.y + random.nextGaussian() * 0.07, jet.z + random.nextGaussian() * 0.07, speed,
                )
            }
            for (dz in doubleArrayOf(-3.0, -1.5, 0.0, 1.5, 3.0)) {
                val at = ship.localToWorld(across, 2.2, dz)
                level.sendParticles(ParticleTypes.SQUID_INK, at.x, at.y, at.z, 6, 0.5, 0.4, 0.5, 0.03)
            }
        }
        if (inking == INK_SPRAY_TO) {
            for (victim in Seas.aboard(ship)) {
                (victim as? LivingEntity)?.let {
                    it.addEffect(MobEffectInstance(MobEffects.BLINDNESS, INK_BLIND, 0), this)
                    it.addEffect(MobEffectInstance(MobEffects.WEAKNESS, INK_WEAK, 0), this)
                }
            }
        }
        if (inking >= INK_TICKS) inking = -1
    }

    /** Down under her keel and up on her other side. */
    private fun diving(level: ServerLevel, ship: Cog) {
        val from = headSide
        val yaw = Mth.wrapDegrees(ship.yRot - 90f * from)
        when {
            phaseTicks <= DIVE_DOWN -> {
                val k = ease(phaseTicks / DIVE_DOWN.toFloat())
                placeAt(head(ship, (-DEEP + 1.0) * k), yaw)
                entityData.set(DATA_RISE, 1f - k)
            }
            phaseTicks <= DIVE_UNDER -> {
                val k = ease((phaseTicks - DIVE_DOWN) / (DIVE_UNDER - DIVE_DOWN).toFloat()).toDouble()
                val side = Mth.lerp(k, from.toDouble(), -from.toDouble())
                val turned = Mth.wrapDegrees(ship.yRot - 90f * from + 180f * k.toFloat())
                placeAt(ship.localToWorld(side * HEAD_OUT, -DEEP + 1.0, 0.0), turned)
                if (phaseTicks == DIVE_UNDER) headSide = -from
            }
            else -> {
                val k = ease((phaseTicks - DIVE_UNDER) / (DIVE_TICKS - DIVE_UNDER).toFloat())
                placeAt(head(ship, (-DEEP + 1.0) * (1.0 - k)), Mth.wrapDegrees(ship.yRot - 90f * headSide))
                entityData.set(DATA_RISE, k)
            }
        }
        if (phaseTicks == DIVE_UNDER + 1) level.playSound(null, ship.x, ship.y, ship.z, SeaRegistry.RISE.get(), SoundSource.HOSTILE, 3f, 1f)
        if (phaseTicks == DIVE_TICKS - 10) splash(level, 140)
        if (phaseTicks == DIVE_TICKS - 2) roar(level, 1.1f)
        if (phaseTicks >= DIVE_TICKS) {
            phase = Phase.EYE
            entityData.set(DATA_RISE, 1f)
            nextBite = 40
            nextInk = 120
        }
    }

    /** Where its head comes up beside [ship], [up] blocks above (or below) the waterline. */
    private fun head(ship: Cog, up: Double): Vec3 = ship.localToWorld(headSide * HEAD_OUT, up, 0.0)

    private fun splash(level: ServerLevel, count: Int) {
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.5, z, count, 2.2, 0.4, 2.2, 0.6)
        level.sendParticles(ParticleTypes.BUBBLE, x, y - 1.0, z, count / 2, 2.0, 1.0, 2.0, 0.2)
        level.sendParticles(ParticleTypes.CLOUD, x, y + 0.5, z, count / 8, 2.0, 0.3, 2.0, 0.05)
    }

    private fun roar(level: ServerLevel, pitch: Float) {
        stamp(DATA_ROAR)
        level.playSound(null, x, y + 3.0, z, SeaRegistry.ROAR.get(), SoundSource.HOSTILE, 4f, pitch)
    }

    private fun updateRage() {
        val half = maxHealth * SURFACE_AT
        val want = if (phase.ordinal < Phase.SURFACE.ordinal) 0f else (0.5f + 0.5f * (1f - health / half)).coerceIn(0.5f, 1f)
        if (abs(want - rage) > 0.01f) entityData.set(DATA_RAGE, want)
    }

    // ---- Letting her go ----

    private fun leave() {
        if (phase == Phase.LEAVE || phase == Phase.DEATH) return
        val level = level() as? ServerLevel
        if (level != null) for (arm in liveArms(level)) arm.withdraw()
        bossEvent.removeAllPlayers()
        entityData.set(DATA_LOOK, -1)
        phase = Phase.LEAVE
    }

    private fun leaving(level: ServerLevel, ship: Cog?) {
        val k = (phaseTicks / LEAVE_TICKS.toFloat()).coerceIn(0f, 1f)
        if (rise > 0f) entityData.set(DATA_RISE, (rise - 1f / 30f).coerceAtLeast(0f))
        setPos(x, y - 0.12, z)
        if (phaseTicks == 1) level.playSound(null, x, y + 2.0, z, SeaRegistry.DIVE.get(), SoundSource.HOSTILE, 2.5f, 0.8f)
        if (k >= 1f) discard()
    }

    // ---- Struck ----

    private fun eyeStruck(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (rise < 0.5f) return false
        val hurt = hurtServer(level, source, amount * EYE_HURT)
        if (hurt) {
            stamp(DATA_FLINCH)
            level.playSound(null, eye.x, eye.y, eye.z, SeaRegistry.BLINK.get(), SoundSource.HOSTILE, 1.5f, 0.9f)
        }
        return hurt
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean {
        if (isDeadOrDying || phase == Phase.OMEN || phase == Phase.RISE || phase == Phase.LEAVE || phase == Phase.DEATH) return false
        // It lies deep, and goes where it likes: the seabed's no wall to it, and it never drowns
        if (source.`is`(DamageTypes.IN_WALL) || source.`is`(DamageTypes.DROWN)) return false
        val attacker = source.entity
        if (attacker is MaelgraspArm || attacker is Maelgrasp) return false
        // A thing this size isn't stunned by any one blow: every one counts, however many come at once
        if (attacker is Player || source.directEntity != attacker) invulnerableTime = 0
        return super.hurtServer(level, source, damage)
    }

    /** One of its arms was hurt: so is it. */
    fun armWounded(level: ServerLevel, source: DamageSource, amount: Float) {
        wound(level, source, amount)
    }

    /** One of its arms was hacked through: it's hurt the more, and roars from the deep. */
    fun armSevered(level: ServerLevel, arm: MaelgraspArm, source: DamageSource) {
        for (i in armAt.indices) if (armAt[i] == arm.id) armAt[i] = -1
        if (phase.ordinal < Phase.SURFACE.ordinal) level.playSound(null, arm.x, arm.y - 3.0, arm.z, SeaRegistry.ROAR.get(), SoundSource.HOSTILE, 2.5f, 0.6f + random.nextFloat() * 0.1f)
        else roar(level, 0.8f)
        wound(level, source, SEVER_DAMAGE * healthScale)
    }

    private fun wound(level: ServerLevel, source: DamageSource, amount: Float) {
        if (isDeadOrDying || amount <= 0f || !fighting) return
        health -= amount
        if (health <= 0f) die(source)
    }

    override fun die(source: DamageSource) {
        if (isRemoved || dead) return
        super.die(source)
        if (!dead) return
        val level = level() as? ServerLevel ?: return
        phase = Phase.DEATH
        bossEvent.removeAllPlayers()
        entityData.set(DATA_LOOK, -1)
        for (arm in liveArms(level)) arm.goLimp()
        level.playSound(null, x, y + 2.0, z, SeaRegistry.DEATH.get(), SoundSource.HOSTILE, 4f, 1f)
        val ship = level.getEntity(shipId) as? Cog
        reward(level, ship, source)
        for (player in level.players()) {
            if (player.distanceToSqr(this) < CUE_RANGE * CUE_RANGE) {
                PacketDistributor.sendToPlayer(player, BossCuePayload(id, BossCuePayload.FELLED))
                Combat.shieldForScene(player, FELLED_SCENE)
            }
        }
    }

    /** Its spoils into the hold of the ship it held (what won't fit, to her crew), its fame to the guild's news. */
    private fun reward(level: ServerLevel, ship: Cog?, source: DamageSource) {
        val heroes = crew.mapNotNull { level.getPlayerByUUID(it) }.filter { it.isAlive && it.distanceToSqr(this) < HERO_RANGE * HERO_RANGE }
        val spoils = ArrayList<ItemStack>()
        spoils.add(ItemStack(SeaRegistry.MAELGRASP_BEAK.get()))
        spoils.add(ItemStack(Items.HEART_OF_THE_SEA))
        spoils.add(Relics.roll(level, min(tier + 3, 6), if (tier >= 3) Rarity.EPIC else Rarity.RARE))
        spoils.add(Relics.roll(level, min(tier + 2, 6), Rarity.UNCOMMON))
        spoils.add(Relics.cache(if (tier >= 3) Rarity.EPIC else Rarity.RARE))
        spoils.add(LoreBooks.fragment(LORE))
        var marks = MARKS + tier * MARKS_PER_TIER + random.nextInt(20)
        while (marks > 0) {
            val n = min(marks, 64)
            spoils.add(ItemStack(ModItems.GUILD_MARK.get(), n))
            marks -= n
        }
        spoils.add(ItemStack(Items.NAUTILUS_SHELL, 2 + random.nextInt(3)))
        spoils.add(ItemStack(Items.GLOW_INK_SAC, 4 + random.nextInt(5)))
        spoils.add(ItemStack(Items.INK_SAC, 8 + random.nextInt(7)))
        for (stack in spoils) {
            var left = ship?.stow(stack) ?: stack
            if (left.isEmpty) continue
            val hero = heroes.firstOrNull()
            if (hero != null && hero.inventory.add(left)) continue
            if (!left.isEmpty) (hero ?: this).spawnAtLocation(level, left)
        }
        val orbsAt = ship?.localToWorld(0.0, 1.6, 0.0) ?: position()
        ExperienceOrb.award(level, orbsAt, XP + tier * XP_PER_TIER)
        val shipName = ship?.customName
        val told = when {
            ship == null -> Component.translatable("message.guildmark.sea.maelgrasp_slain_lost")
            shipName != null -> Component.translatable("message.guildmark.sea.maelgrasp_slain", shipName)
            else -> Component.translatable("message.guildmark.sea.maelgrasp_slain_hold")
        }
        for (hero in heroes) (hero as? ServerPlayer)?.sendSystemMessage(told)
        val killer = source.entity as? Player
        val names = (heroes.map { it.name.string } + listOfNotNull(killer?.name?.string)).distinct()
        GuildNews.add(level, "news.guildmark.maelgrasp_slain", names.joinToString(", ").ifEmpty { "?" }, blockX.toString(), blockZ.toString())
    }

    /** It sinks into the dark: its head going under, slowly, trailing ink and bubbles. */
    override fun tickDeath() {
        deathTime++
        val level = level()
        if (level !is ServerLevel) return
        if (rise > 0f) entityData.set(DATA_RISE, (rise - 1f / DEATH_TICKS).coerceAtLeast(0f))
        setPos(x, y - SINK_RATE, z)
        if (deathTime % 3 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, x, y + 1.0, z, 10, 2.0, 1.0, 2.0, 0.1)
            level.sendParticles(ParticleTypes.SQUID_INK, x, y + 1.5, z, 4, 1.5, 0.8, 1.5, 0.02)
        }
        if (deathTime >= DEATH_TICKS && !isRemoved) remove(RemovalReason.KILLED)
    }

    // ---- The boss bar ----

    private fun updateBossBar(level: ServerLevel, ship: Cog) {
        val watchers = level.players().filter { it.isAlive && !it.isSpectator && (it.vehicle === ship || it.distanceToSqr(ship) < BAR_RANGE * BAR_RANGE) }
        for (player in bossEvent.players.toList()) if (player !in watchers) bossEvent.removePlayer(player)
        for (player in watchers) if (player !in bossEvent.players) bossEvent.addPlayer(player)
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        bossEvent.removePlayer(player)
    }

    override fun remove(reason: RemovalReason) {
        super.remove(reason)
        bossEvent.removeAllPlayers()
    }

    // ---- Moving ----

    private fun placeAt(at: Vec3, yaw: Float) {
        setPos(at.x, at.y, at.z)
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
    }

    private fun yawToward(from: Vec3, to: Vec3): Float = (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG).toFloat() - 90f

    /**
     * Where its siphon opens in the world, and the way it points: on its left flank (the model's (50, -4, 9) px from its
     * waterline under its middle), pointing out, a little back and down.
     */
    private fun siphon(): Pair<Vec3, Vec3> {
        val a = yRot * Mth.DEG_TO_RAD.toDouble()
        val left = Vec3(cos(a), 0.0, sin(a))
        val back = Vec3(sin(a), 0.0, -cos(a))
        val at = position().add(left.scale(50 / 16.0)).add(back.scale(9 / 16.0)).add(0.0, 4 / 16.0, 0.0)
        return Pair(at, left.scale(0.85).add(back.scale(0.40)).add(0.0, -0.35, 0.0).normalize())
    }

    // ---- What it isn't ----

    override fun travel(input: Vec3) {}
    override fun isPushedByFluid(): Boolean = false
    override fun canBreatheUnderwater(): Boolean = true
    override fun causeFallDamage(fallDistance: Double, multiplier: Float, source: DamageSource): Boolean = false
    override fun isPushable(): Boolean = false
    override fun pushEntities() {}
    override fun canBeLeashed(): Boolean = false
    override fun canUsePortal(ignorePassenger: Boolean): Boolean = false
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false
    override fun isAffectedByPotions(): Boolean = false

    override fun getHurtSound(source: DamageSource): SoundEvent = SeaRegistry.ARM_HURT.get()
    // Its great death cry plays in die(), however it dies; a killing blow only adds the rush of its breath going out
    override fun getDeathSound(): SoundEvent = SeaRegistry.BUBBLES.get()
    override fun playStepSound(pos: BlockPos, state: BlockState) {}

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putBoolean("hunting", true)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        // Its hunt doesn't outlast the world being saved and loaded: the ship it held may be anywhere by then
        reloaded = input.getBooleanOr("hunting", false)
    }

    companion object {
        private val DATA_PHASE: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.BYTE)
        private val DATA_RISE: EntityDataAccessor<Float> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.FLOAT)
        private val DATA_LOOK: EntityDataAccessor<Int> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.INT)
        private val DATA_BITE: EntityDataAccessor<Int> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.INT)
        private val DATA_INK: EntityDataAccessor<Int> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.INT)
        private val DATA_ROAR: EntityDataAccessor<Int> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.INT)
        private val DATA_FLINCH: EntityDataAccessor<Int> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.INT)
        private val DATA_RAGE: EntityDataAccessor<Float> = SynchedEntityData.defineId(Maelgrasp::class.java, EntityDataSerializers.FLOAT)
        private val PHASES = Phase.entries.toTypedArray()
        private val TIER_HEALTH = SeaRegistry.id("maelgrasp_tier_health")

        /**
         * Where round a ship its arms rise: off her starboard bow and quarter, her port bow and quarter (the first four,
         * that seize her), then ahead of her bow and astern of her transom.
         */
        @JvmField
        val SLOTS: List<Slot> = listOf(
            Slot(3.1, 2.4, -90f, 1, 1.0, 0.0),
            Slot(3.1, -2.2, -90f, 1, 1.0, 0.0),
            Slot(-3.1, 2.4, 90f, -1, -1.0, 0.0),
            Slot(-3.1, -2.2, 90f, -1, -1.0, 0.0),
            Slot(0.0, 7.2, 180f, 0, 0.0, 1.0),
            Slot(0.0, -6.2, 0f, 0, 0.0, -1.0),
        )

        /** On a client, the sea streaming off its head and the ink it spews, done each tick (see client/sea/SeaClient). */
        var clientEffects: ((Maelgrasp) -> Unit)? = null

        fun createAttributes(): AttributeSupplier.Builder = createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 400.0)
            .add(Attributes.ATTACK_DAMAGE, 14.0)
            .add(Attributes.ARMOR, 8.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 64.0)

        /** Sends it for [ship]: up out of the deep under her, with the omens first unless [omen] is false. */
        fun summon(level: ServerLevel, ship: Cog, omen: Boolean): Maelgrasp? {
            val boss = SeaRegistry.MAELGRASP.get().create(level, EntitySpawnReason.EVENT) ?: return null
            val at = ship.localToWorld(5.0, -DEEP, 0.0)
            boss.snapTo(at.x, at.y, at.z, ship.yRot, 0f)
            boss.comeFor(level, ship, omen)
            return if (level.addFreshEntity(boss)) boss else null
        }

        // ---- Its eye and beak, in its own frame (blocks: up from the waterline, forward toward the ship) ----
        const val EYE_UP = 2.0f
        const val EYE_FORWARD = 2.3125f
        const val BEAK_UP = 0.375f
        const val BEAK_FORWARD = 2.875f
        private const val EYE_SIZE = 1.5f
        private const val EYE_HURT = 1.75f
        /** Degrees its eye can turn in its socket, side to side and up and down. */
        private const val LOOK_YAW = 35f
        private const val LOOK_PITCH = 25f

        /** How deep under her keel it lies (its head's waterline mark), and how far out from her middle its head comes up. */
        private const val DEEP = 10.0
        private const val HEAD_OUT = 4.95

        // ---- Timing, in ticks ----
        private const val OMEN_TICKS = 500
        private const val CALM_TICKS = 200
        private const val CIRCLE_SPEED = 0.012f
        private const val OMEN_GROAN = 110
        private const val OMEN_BUBBLES = 90
        private const val BELL_FIRST = 100
        private const val BELL_SECOND = 300
        private const val RISE_TICKS = 60
        private const val FIRST_ARMS = 4
        private const val ARM_STAGGER = 10
        private const val RISE_ROAR = 46
        private const val SURFACE_TICKS = 60
        private const val SURFACE_SWIM = 20
        private const val DIVE_DOWN = 30
        private const val DIVE_UNDER = 70
        private const val DIVE_TICKS = 110
        private const val LEAVE_TICKS = 60
        const val DEATH_TICKS = 100
        private const val SINK_RATE = 0.09
        private const val LONELY_TICKS = 400
        private const val FIGHT_TICKS = 9600

        // ---- Its arms ----
        private const val MOVE_GAP = 30
        private const val MOVE_SPREAD = 30
        private const val MOVE_GAP_LATE = 22
        private const val MOVE_SPREAD_LATE = 24
        private const val MAX_STRIKING = 2
        private const val MAX_HOLDING = 2
        private const val MAX_ARMS_LATE = 3
        private const val NEW_ARM_EVERY = 200
        private const val SWIMMER_RANGE = 10.0
        private const val LIST_PER_ARM = 4f
        private const val MAX_LIST = 10f
        private const val CRUSH_EVERY = 20
        private const val CRUSH_HULL = 1.5f
        private const val SEVER_DAMAGE = 20f

        // ---- Its head ----
        private const val SURFACE_AT = 0.5f
        private const val DIVE_AT = 0.25f
        private const val BITE_GAP = 120
        private const val BITE_SPREAD = 60
        const val BITE_LAND = 22
        const val BITE_TICKS = 40
        private const val BITE_HULL = 6f
        private const val BITE_DAMAGE = 16f
        private const val BITE_REACH = 2.6
        private const val INK_GAP = 200
        private const val INK_SPREAD = 60
        const val INK_SPRAY_FROM = 6
        const val INK_SPRAY_TO = 22
        const val INK_TICKS = 46
        private const val INK_BLIND = 80
        private const val INK_WEAK = 120

        // ---- Its fall ----
        private const val CUE_RANGE = 48.0
        private const val HERO_RANGE = 64.0
        private const val BAR_RANGE = 48.0
        // The camera turns about it for 100 ticks and takes 14 to come back (client/combat/Cutscenes.maelgraspFelled)
        private const val FELLED_SCENE = 114
        private const val MARKS = 90
        private const val MARKS_PER_TIER = 25
        private const val XP = 500
        private const val XP_PER_TIER = 120
        /** Its lore book (lore.guildmark.31.*). */
        private const val LORE = 31

        private fun ease(u: Float): Float {
            val k = u.coerceIn(0f, 1f)
            return k * k * (3f - 2f * k)
        }
    }
}
