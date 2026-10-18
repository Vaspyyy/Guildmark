package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkClient
import io.github.vaspyyy.guildmark.economy.Stalls
import io.github.vaspyyy.guildmark.faction.Factions
import io.github.vaspyyy.guildmark.faction.Pickpocket
import io.github.vaspyyy.guildmark.faction.SwornKnight
import io.github.vaspyyy.guildmark.outlaw.Captives
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.ContainerUser
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.OpenDoorGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.npc.Npc
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.entity.ContainerOpenersCounter
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import java.util.UUID
import kotlin.math.atan2

/**
 * A townsperson: a smith at the anvil, a baker at the oven, a guard on patrol, children at play. They keep a
 * daily round ([Routine]): work by day, the tavern or the square in the evening, home to bed at night. What
 * they're doing ([activity]) is sent to the client, which plays the matching clip, sounds and sparks.
 *
 * Residents live in a town for good. Crowd folk ([crowd]) are passers-by the [Crowds] bring in near players to
 * keep the streets busy; they slip away again out of sight, and are never kept when their chunk is saved.
 * They're an [Npc], so the city ambience counts them into its crowd chatter.
 */
class Townsperson(type: EntityType<out Townsperson>, level: Level) : PathfinderMob(type, level), Npc, ContainerUser {
    var role: TownRole
        get() = TownRole.byId(entityData.get(ROLE).toInt())
        private set(value) = entityData.set(ROLE, value.ordinal.toByte())

    /** Which of the role's looks they wear. */
    var look: Int
        get() = entityData.get(LOOK).toInt()
        set(value) = entityData.set(LOOK, value.toByte())

    /** What they're doing now; the client plays its clip. */
    val activity: Activity get() = Activity.byId(entityData.get(ACTION) and 0xFF)

    /** Goes up each time an activity starts, so the client restarts a one-shot done twice in a row. */
    val actionSerial: Int get() = entityData.get(ACTION) ushr 8

    /** The line they're saying (a key under say.guildmark.), or empty. */
    val speech: String get() = entityData.get(SPEECH)

    /** The goods in their hands (a loaf being handed over, a sack on its way to the mill), drawn by the client; empty if none. */
    var carried: ItemStack
        get() = entityData.get(CARRIED)
        set(value) = entityData.set(CARRIED, value)

    /** The tick [carried] leaves their hands by itself (goods passed across a counter to someone); 0 to keep it. */
    var carriedUntil = 0

    /** Carrying a lantern (after dark). */
    var lantern: Boolean
        get() = entityData.get(FLAGS).toInt() and FLAG_LANTERN != 0
        set(value) {
            val flags = entityData.get(FLAGS).toInt()
            entityData.set(FLAGS, (if (value) flags or FLAG_LANTERN else flags and FLAG_LANTERN.inv()).toByte())
        }

    /**
     * One of the faction leaders (look 0 of a mage, a knight or a thief): Idris, Corra or Odile. They keep their
     * post at every hour, can't be hurt, never run, and using them opens their conversation (see Factions).
     */
    var isLeader: Boolean
        get() = entityData.get(FLAGS).toInt() and FLAG_LEADER != 0
        set(value) {
            val flags = entityData.get(FLAGS).toInt()
            entityData.set(FLAGS, (if (value) flags or FLAG_LEADER else flags and FLAG_LEADER.inv()).toByte())
        }

    /** A leader nodding off at their post in the small hours: using them wakes them with a grumble. */
    val dozing: Boolean get() = isLeader && Schedule.phase(this) == Phase.SLEEP

    /** A watcher set to keep an eye on a heist's building while it's on (see Heists); never kept when saved. */
    var watcher = false

    /** Who a watcher has their eye on, while they're suspicious of someone. */
    var suspect: Player? = null

    /** The day their purse was lifted (or someone tried), so it's once a day each (see Pickpocket). */
    var pursedDay = -1L

    /** The outlaw band holding them captive (see Captives), or -1. */
    var captiveOf = -1

    /** Whoever cut a captive loose and is seeing them home. */
    var rescuer: UUID? = null

    /** Tied up in an outlaw camp, waiting for someone to free them. */
    val bound: Boolean get() = captiveOf >= 0 && rescuer == null

    /**
     * Something outside their day's round that has charge of them for now (a builder's job on a homestead, a part
     * in a festival): while set, it runs each tick in place of the round. Never saved; whoever sets it clears it.
     */
    var director: Director? = null

    /** Where they live: their beds are found near here. */
    var home: BlockPos? = null

    /** The block they work at (an anvil, an oven, a lectern, a composter...), if any. */
    var work: BlockPos? = null

    /** Where they stand to work and which way they face, when that's set rather than found (a stall, a bar, a gate). */
    var post: BlockPos? = null
    var postFacing: Direction? = null

    /** Where they spend the evening (a point inside the tavern), if known. */
    var evening: BlockPos? = null

    /** The town they live in, if known. */
    var town: Town? = null

    /** A passer-by brought in by the crowds, who leaves again. */
    var crowd = false

    /** Game time a passer-by starts looking for a quiet moment to leave. */
    var leaveAt = -1L

    /** Their own offset to the day's timetable, so a street doesn't all get up at once. */
    var shift = 0

    /** A guard who keeps the night watch rather than the day's. */
    var nightWatch = false

    /** Who they are among their town's people (see [Residents]), or -1 until they've been taken in. */
    var resident = -1

    /** Their life in a capital, with its days (see [Days.life]); not saved, it's looked up again. */
    var life: Life? = null
    var lifeLooked = 0

    /** The chest or barrel they have open just now, taking something out or putting it away (see [Trades]); its lid stays up while this is it. */
    var opened: BlockPos? = null

    /** A guard's beat, if set; otherwise they walk the streets round their post. */
    var patrol: List<BlockPos> = listOf()

    /** Tick count the current activity started at. */
    var activityStart = 0
        private set
    private var serial = 0
    val actTicks: Int get() = tickCount - activityStart

    /** Whether a one-shot activity has played through. */
    val actDone: Boolean get() = !activity.loop && actTicks >= activity.ticks

    private var speechUntil = 0L
    var nextSpeech = 0L

    /** Who they're talking to (a player who spoke to them), until when. */
    var attending: Player? = null
        private set
    private var attendUntil = 0L

    /** The seat they're sitting on. */
    var seat: BlockPos? = null
        private set

    private var discardOnLoad = false

    val routine = Routine(this)

    init {
        navigation.setCanOpenDoors(true)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(ROLE, 0)
        builder.define(LOOK, 0)
        builder.define(ACTION, 0)
        builder.define(SPEECH, "")
        builder.define(FLAGS, 0)
        builder.define(CARRIED, ItemStack.EMPTY)
    }

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key == ROLE) refreshDimensions()
        if (!level().isClientSide) return
        if (key == ACTION) {
            if (seenAction) {
                lastActivity = shownActivity
                lastStart = shownStart
                changedAt = tickCount
                shownStart = tickCount
            } else {
                // First sight of them: whatever they're at has been going a while
                seenAction = true
                shownStart = tickCount - (id and 0xFF)
            }
            shownActivity = activity
        }
        if (key == SPEECH && speech.isNotEmpty()) TownsfolkClient.speak(this, Speech.key(speech))
    }

    // What a client last heard they were doing, and what that cut short, for the clips' crossfade
    var shownActivity = Activity.NONE
        private set
    var shownStart = 0
        private set
    var lastActivity = Activity.NONE
        private set
    var lastStart = 0
        private set
    var changedAt = -1000
        private set
    private var seenAction = false

    override fun tick() {
        super.tick()
        if (level().isClientSide) TownsfolkClient.tick(this)
    }

    override fun getDefaultDimensions(pose: Pose): EntityDimensions =
        if (role == TownRole.CHILD) super.getDefaultDimensions(pose).scale(CHILD_SCALE) else super.getDefaultDimensions(pose)

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, object : MeleeAttackGoal(this, 0.8, true) {
            override fun canUse(): Boolean = fights && super.canUse()
            override fun canContinueToUse(): Boolean = fights && super.canContinueToUse()
        })
        goalSelector.addGoal(1, FleeGoal())
        goalSelector.addGoal(2, OpenDoorGoal(this, true))
        goalSelector.addGoal(3, RoutineGoal())
        targetSelector.addGoal(1, object : HurtByTargetGoal(this, Townsperson::class.java) {
            override fun canUse(): Boolean = fights && super.canUse()
        })
        targetSelector.addGoal(2, object : NearestAttackableTargetGoal<Mob>(
            this, Mob::class.java, 10, true, false, { target, _ -> target is Enemy && target !is Creeper && target !is SwornKnight },
        ) {
            override fun canUse(): Boolean = fights && super.canUse()
        })
    }

    /** The guards stand and fight, and so do the Hearthsworn's knights; their leaders keep their post. */
    val fights: Boolean get() = (role == TownRole.GUARD || role == TownRole.KNIGHT) && !isLeader && !watcher

    override fun finalizeSpawn(level: ServerLevelAccessor, difficulty: DifficultyInstance, reason: EntitySpawnReason, data: SpawnGroupData?): SpawnGroupData? {
        val result = super.finalizeSpawn(level, difficulty, reason, data)
        // Summoned by hand: someone at random
        if (!roleSet) assign(TownRole.entries[random.nextInt(TownRole.entries.size)], -1)
        if (home == null) home = blockPosition()
        if (shift == 0) shift = random.nextInt(1201) - 600
        return result
    }

    /** Set once they were given a role, so finalizeSpawn keeps it. */
    private var roleSet = false

    /** Makes them a [role] in one of its looks ([look] -1 for any), at that role's full health. */
    fun assign(role: TownRole, look: Int = -1) {
        this.role = role
        this.look = if (look in 0 until role.looks) look else random.nextInt(role.looks)
        roleSet = true
        getAttribute(Attributes.MAX_HEALTH)?.baseValue = role.health
        health = maxHealth
    }

    // --- Doing things -------------------------------------------------------------------------------------------

    /** Starts [what]; a loop that's already playing carries on where it is. */
    fun act(what: Activity) {
        if (what == activity && what.loop) return
        serial = (serial + 1) and 0xFFFFFF
        entityData.set(ACTION, (serial shl 8) or what.ordinal)
        activityStart = tickCount
    }

    /** Turns body and head to [yaw] at once. */
    fun face(yaw: Float) {
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
    }

    fun face(direction: Direction) = face(direction.toYRot())

    /** The yaw that looks from here to [target]. */
    fun yawTo(target: Vec3): Float = (atan2(target.z - z, target.x - x) * (180.0 / Math.PI)).toFloat() - 90f

    /** Looks at [target], or at whoever they're talking to. */
    fun lookAt(target: Vec3) {
        val other = attending
        if (other != null) lookControl.setLookAt(other, 30f, 30f) else lookControl.setLookAt(target.x, target.y, target.z, 30f, 30f)
    }

    fun lookAt(target: Entity) {
        val other = attending ?: target
        lookControl.setLookAt(other, 30f, 30f)
    }

    /**
     * Says [line] (a key under say.guildmark.) if a player is within [hearing] of them, showing it over their head
     * for [ticks]; [force] says it even while they've only just said something.
     */
    fun say(line: String, force: Boolean = false, hearing: Double = HEARING, ticks: Long = SPEECH_TICKS): Boolean {
        val level = level() as? ServerLevel ?: return false
        // The leaders keep their own voices for talking properly (Factions)
        if (isLeader) return false
        if (!force && (level.gameTime < nextSpeech || speech.isNotEmpty())) return false
        if (level.getNearestPlayer(this, hearing) == null) return false
        entityData.set(SPEECH, line)
        speechUntil = level.gameTime + ticks
        nextSpeech = level.gameTime + ticks + 60 + random.nextInt(200)
        return true
    }

    /** Stops to talk with [player] for a few seconds. */
    fun attend(player: Player, ticks: Int = 80) {
        attending = player
        attendUntil = level().gameTime + ticks
    }

    /** A leader turns to [player] for a conversation, which lasts until they leave it ([stopListening]). */
    fun listen(player: Player) = attend(player, LISTEN_TICKS)

    /** [player] left the conversation: back to what they were doing. */
    fun stopListening(player: Player) {
        if (attending === player) attending = null
    }

    /** Sits down on the stair at [pos], facing away from its back. */
    fun sitOn(pos: BlockPos) {
        val facing = Places.seatFacing(level().getBlockState(pos))
        seat = pos
        navigation.stop()
        setPos(pos.x + 0.5 + facing.stepX * 0.12, pos.y + 0.5, pos.z + 0.5 + facing.stepZ * 0.12)
        deltaMovement = Vec3.ZERO
        face(facing)
    }

    /** Gets up off their seat onto the floor in front of it. */
    fun standUp() {
        val pos = seat ?: return
        seat = null
        Claims.release(level(), pos, this)
        val state = level().getBlockState(pos)
        val off = (if (state.block is StairBlock) Places.access(level(), pos, state) else null) ?: pos.above()
        setPos(off.x + 0.5, off.y.toDouble(), off.z + 0.5)
    }

    // --- Ticking ----------------------------------------------------------------------------------------------

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        if (discardOnLoad) {
            discard()
            return
        }
        if (speech.isNotEmpty() && level.gameTime >= speechUntil) entityData.set(SPEECH, "")
        if (carriedUntil != 0 && tickCount >= carriedUntil) {
            carried = ItemStack.EMPTY
            carriedUntil = 0
        }
        val other = attending
        if (other != null && (!other.isAlive || other.level() !== level || level.gameTime > attendUntil || distanceToSqr(other) > 100.0)) attending = null
        seat?.let { pos ->
            // Keep to the seat; it may have been broken under them
            if (level.getBlockState(pos).block !is StairBlock) standUp()
            else if (tickCount % 20 == 0) {
                val facing = Places.seatFacing(level.getBlockState(pos))
                setPos(pos.x + 0.5 + facing.stepX * 0.12, pos.y + 0.5, pos.z + 0.5 + facing.stepZ * 0.12)
            }
        }
        if (crowd && tickCount % 40 == 0) Crowds.maybeLeave(level, this)
        suspect?.let { if (!it.isAlive || it.level() !== level || distanceToSqr(it) > 48.0 * 48.0) suspect = null }
        // A resident placed without word of their town looks it up once
        if (town == null && !townLooked && tickCount % 100 == 13) {
            townLooked = true
            town = Towns.at(level, home ?: blockPosition())
        }
        // Passers-by, captives and the named are no one's neighbours
        if (resident < 0 && !crowd && !watcher && captiveOf < 0 && customName == null && tickCount % 40 == 7) Residents.adopt(level, this)
    }

    private var townLooked = false

    // --- Goals --------------------------------------------------------------------------------------------------

    /** The day's round. Fleeing, fighting and talking to a player come first. */
    private inner class RoutineGoal : Goal() {
        init {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))
        }

        override fun canUse(): Boolean = isAlive
        override fun canContinueToUse(): Boolean = isAlive
        override fun requiresUpdateEveryTick(): Boolean = true
        override fun tick() {
            val director = director
            when {
                director != null -> director.tick(this@Townsperson)
                captiveOf >= 0 -> Captives.tick(this@Townsperson)
                else -> routine.tick()
            }
        }
        override fun stop() = routine.interrupt()
    }

    /** Runs from monsters and from whoever hurt them; guards stand and fight instead. */
    private inner class FleeGoal : Goal() {
        private var from: LivingEntity? = null
        private var until = 0

        init {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))
        }

        override fun canUse(): Boolean {
            if (fights || isLeader || isSleeping || bound || tickCount % 5 != 0) return false
            from = threat() ?: return false
            return true
        }

        override fun canContinueToUse(): Boolean = tickCount < until && from?.isAlive == true

        override fun start() {
            until = tickCount + 100
            act(Activity.FLEE)
            say(Lines.flee(this@Townsperson), force = true)
            run()
        }

        override fun tick() {
            if (navigation.isDone) run()
        }

        private fun run() {
            val threat = from ?: return
            val away = DefaultRandomPos.getPosAway(this@Townsperson, 16, 7, threat.position()) ?: return
            navigation.moveTo(away.x, away.y, away.z, if (role == TownRole.CHILD) 1.0 else 0.9)
        }

        override fun stop() {
            from = null
            navigation.stop()
            act(Activity.NONE)
        }
    }

    /** Whoever hurt them lately, or a monster close by in sight. */
    fun threat(): LivingEntity? {
        lastHurtByMob?.let { if (it.isAlive && tickCount - lastHurtByMobTimestamp < 100 && distanceToSqr(it) < 256.0) return it }
        return level().getEntitiesOfClass(Mob::class.java, boundingBox.inflate(8.0, 4.0, 8.0)) { it is Enemy && it.isAlive && it !is Townsperson && it !is SwornKnight }
            .filter { hasLineOfSight(it) }
            .minByOrNull { distanceToSqr(it) }
    }

    // --- Being a person -----------------------------------------------------------------------------------------

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        val level = level()
        // Whoever has charge of them for now may answer for them
        val director = director
        if (director != null && level is ServerLevel && player is ServerPlayer && director.interact(this, player)) return InteractionResult.SUCCESS
        // A captive: cut loose, or asked to keep close
        if (captiveOf >= 0) {
            if (level is ServerLevel && player is ServerPlayer) Captives.interact(level, player, this)
            return InteractionResult.SUCCESS
        }
        // A faction's leader talks properly
        if (isLeader) {
            if (level is ServerLevel && player is ServerPlayer) Factions.talk(level, player, this)
            return InteractionResult.SUCCESS
        }
        // Crouched behind them: a hand in their purse (face to face, it's only a word)
        if (player.isShiftKeyDown && !watcher && (level !is ServerLevel || Pickpocket.tryLift(level, player, this))) return InteractionResult.SUCCESS
        if (level is ServerLevel && player is ServerPlayer && Stalls.interact(level, player, this)) return InteractionResult.SUCCESS
        // A talk: a greeting that knows them, what's on their mind, then the player's choices (asleep, a mumble; busy, a word over the shoulder)
        if (level is ServerLevel && player is ServerPlayer && Talk.open(level, player, this)) return InteractionResult.SUCCESS
        if (isSleeping) return InteractionResult.PASS
        if (level is ServerLevel) {
            attend(player)
            say(Lines.talk(this, player), force = true)
        }
        return InteractionResult.SUCCESS
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        // The leaders are beyond harm: the factions need them
        if (isLeader && !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false
        val hurt = super.hurtServer(level, source, amount)
        if (hurt) {
            if (seat != null) standUp()
            if (isSleeping) stopSleeping()
            (source.entity as? LivingEntity)?.let { Social.alarm(level, this, it) }
        }
        return hurt
    }

    override fun getTypeName(): Component = Component.translatable("entity.guildmark.townsperson.${role.id}")

    override fun isPushable(): Boolean = !isSleeping && seat == null && !routine.anchored && !isLeader && !bound
    // Passers-by leave on their own when nobody's looking (Crowds.maybeLeave); the game only clears them once far off
    override fun removeWhenFarAway(distance: Double): Boolean = crowd && distance > 96.0 * 96.0
    override fun requiresCustomPersistence(): Boolean = !crowd && !watcher

    override fun remove(reason: Entity.RemovalReason) {
        seat?.let { Claims.release(level(), it, this) }
        routine.interrupt()
        super.remove(reason)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putByte("role", role.ordinal.toByte())
        output.putByte("look", look.toByte())
        output.storeNullable("home", BlockPos.CODEC, home)
        output.storeNullable("work", BlockPos.CODEC, work)
        output.storeNullable("post", BlockPos.CODEC, post)
        output.storeNullable("post_facing", Direction.CODEC, postFacing)
        output.storeNullable("evening", BlockPos.CODEC, evening)
        town?.let {
            output.store("town", BlockPos.CODEC, it.center)
            output.putInt("town_radius", it.radius)
            output.store("square", BlockPos.CODEC, it.square)
            output.storeNullable("town_harbour", BoundingBox.CODEC, it.harbour)
        }
        output.putBoolean("crowd", crowd)
        output.putInt("shift", shift)
        output.putBoolean("night_watch", nightWatch)
        if (resident >= 0) output.putInt("resident", resident)
        if (patrol.isNotEmpty()) output.store("patrol", BlockPos.CODEC.listOf(), patrol)
        if (isLeader) output.putBoolean("leader", true)
        if (watcher) output.putBoolean("watcher", true)
        if (pursedDay >= 0) output.putLong("pursed_day", pursedDay)
        if (captiveOf >= 0) output.putInt("captive_of", captiveOf)
        output.storeNullable("rescuer", UUIDUtil.CODEC, rescuer)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        // Health and its maximum were read with the rest of the body
        role = TownRole.byId(input.getByteOr("role", 0).toInt())
        roleSet = true
        look = input.getByteOr("look", 0).toInt()
        home = input.read("home", BlockPos.CODEC).orElse(null)
        work = input.read("work", BlockPos.CODEC).orElse(null)
        post = input.read("post", BlockPos.CODEC).orElse(null)
        postFacing = input.read("post_facing", Direction.CODEC).orElse(null)
        evening = input.read("evening", BlockPos.CODEC).orElse(null)
        town = input.read("town", BlockPos.CODEC).orElse(null)?.let { center ->
            Town(
                center, input.getIntOr("town_radius", 48), input.read("square", BlockPos.CODEC).orElse(center),
                input.read("town_harbour", BoundingBox.CODEC).orElse(null),
            )
        }
        crowd = input.getBooleanOr("crowd", false)
        // Passers-by only ever live while someone is near to see them
        if (crowd) discardOnLoad = true
        shift = input.getIntOr("shift", 0)
        nightWatch = input.getBooleanOr("night_watch", false)
        resident = input.getIntOr("resident", -1)
        patrol = input.read("patrol", BlockPos.CODEC.listOf()).orElse(listOf())
        isLeader = input.getBooleanOr("leader", false)
        // A heist's watchers only keep watch while it's on
        if (input.getBooleanOr("watcher", false)) discardOnLoad = true
        pursedDay = input.getLongOr("pursed_day", -1L)
        captiveOf = input.getIntOr("captive_of", -1)
        rescuer = input.read("rescuer", UUIDUtil.CODEC).orElse(null)
        // Saved halfway up a ladder: they come down it again
        isNoGravity = false
    }

    override fun die(source: DamageSource) {
        val level = level()
        if (captiveOf >= 0 && level is ServerLevel) Captives.onDeath(level, this)
        if (resident >= 0 && level is ServerLevel) Residents.died(level, this)
        super.die(source)
    }

    override fun hasContainerOpen(counter: ContainerOpenersCounter, pos: BlockPos): Boolean = opened == pos

    override fun getContainerInteractionRange(): Double = 4.0

    companion object {
        private val ROLE: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Townsperson::class.java, EntityDataSerializers.BYTE)
        private val LOOK: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Townsperson::class.java, EntityDataSerializers.BYTE)
        private val ACTION: EntityDataAccessor<Int> = SynchedEntityData.defineId(Townsperson::class.java, EntityDataSerializers.INT)
        private val SPEECH: EntityDataAccessor<String> = SynchedEntityData.defineId(Townsperson::class.java, EntityDataSerializers.STRING)
        private val FLAGS: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Townsperson::class.java, EntityDataSerializers.BYTE)
        private val CARRIED: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(Townsperson::class.java, EntityDataSerializers.ITEM_STACK)
        private const val FLAG_LANTERN = 1
        private const val FLAG_LEADER = 2

        /** How long a leader keeps their eyes on someone talking to them before giving up on them. */
        private const val LISTEN_TICKS = 2400

        /** Children are drawn and sized this much smaller. */
        const val CHILD_SCALE = 0.6f

        /** How long a line stays up, and how near a player must be for anyone to bother saying it. */
        const val SPEECH_TICKS = 60L
        const val HEARING = 20.0

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5)
            .add(Attributes.ATTACK_DAMAGE, 5.0)
            .add(Attributes.FOLLOW_RANGE, 48.0)
    }
}

/** Takes charge of a townsperson in place of their day's round (see [Townsperson.director]). */
interface Director {
    /** Each tick, in place of the round. */
    fun tick(p: Townsperson)

    /** A player used them; true if this dealt with it (otherwise they talk as usual). */
    fun interact(p: Townsperson, player: ServerPlayer): Boolean = false
}
