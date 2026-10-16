package io.github.vaspyyy.guildmark.ship

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.HasCustomInventoryScreen
import net.minecraft.world.entity.InterpolationHandler
import net.minecraft.world.entity.LinearInterpolationHandler
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.SlotAccess
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.ContainerEntity
import net.minecraft.world.entity.vehicle.DismountHelper
import net.minecraft.world.entity.vehicle.VehicleEntity
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.Vec3
import net.minecraft.util.Prediction
import net.minecraft.world.entity.ContainerUser
import net.neoforged.neoforge.entity.PartEntity
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A cog: a single-masted ship with a square sail, a stern castle with the wheel on it, and a hold under
 * its deck. Whoever stands at the helm sails it (forward lets the sail down, back hauls it up and, once
 * it's furled, pushes her off astern; left and right put the helm over; jump lets go or weighs the anchor;
 * sprint rings the bell). It goes by the [Wind]: dead ahead it can't sail at all, and its best is with the
 * wind on the quarter (see [Sailing]). Others aboard take the benches, the stern locker or the bow, and
 * pointing at another place on deck walks you there; the hatch, or the inventory key aboard, opens the
 * hold. Crouching and striking it packs it away into its item, hold and all; players' blows never harm it.
 *
 * The server sails it and every client follows its moves; the sail, the yard, the helm and the swell are
 * worked out on both sides from the same few synced values, so they cost nothing to send.
 */
class Cog(type: EntityType<out Cog>, level: Level) : VehicleEntity(type, level), HasCustomInventoryScreen, ContainerEntity {
    // ---- The hull's boxes, bow to stern (the ship's own box covers its middle) ----
    private val parts: Array<CogPart> = arrayOf(
        CogPart(this, 3.3, 2.2f, 1.8f),
        CogPart(this, 1.7, 3.2f, 1.7f),
        CogPart(this, -1.7, 3.4f, 1.7f),
        CogPart(this, -3.5, 3.0f, 2.9f),
    )

    // ---- The hold ----
    private var itemStacks: NonNullList<ItemStack> = NonNullList.withSize(HOLD_SIZE, ItemStack.EMPTY)
    private var lootTable: ResourceKey<LootTable>? = null
    private var lootTableSeed = 0L

    /** Who put it on the water: they (or an operator) can pack it away. */
    var owner: UUID? = null

    // ---- Sailing, on both sides ----

    /** How far the sail is let down, 0 to 1, and last tick's. */
    var sail = 0f
        private set
    var sailO = 0f
        private set

    /** Where the helm is, -1 hard to port to 1 hard to starboard, and last tick's. */
    var rudder = 0f
        private set
    var rudderO = 0f
        private set

    // ---- Sailing, on the server ----

    /** Blocks a tick through the water: below 0 she's going astern. */
    private var speed = 0.0
    private var yawRate = 0f
    private var jumpHeld = false
    private var sprintHeld = false
    private var bumpCooldown = 0
    private var hoistCooldown = 0
    private var lastOrder = 0f
    private var bellCooldown = 0
    /** Which half of the day it was last tick (the bell is rung as each begins). */
    private var watch = Long.MIN_VALUE

    /** The station a player boarding by clicking asked for. */
    private var wanted: CogStation? = null

    // ---- What the clients see, worked out on their side ----

    /** Degrees the yard is braced, how the sail fills and luffs, and the heel, each with last tick's. */
    var brace = 0f; private set
    var braceO = 0f; private set
    var fill = 0f; private set
    var fillO = 0f; private set
    var luff = 0f; private set
    var luffO = 0f; private set
    var heel = 0f; private set
    var heelO = 0f; private set

    /** 0 stowed to 1 down, and last tick's. */
    var anchorDrop = 0f; private set
    var anchorDropO = 0f; private set

    /** Blocks a tick, from how far it moved (smoothed), and last tick's. */
    var seenSpeed = 0f; private set
    var seenSpeedO = 0f; private set

    init {
        // Nothing can be built where she lies
        blocksBuilding = true
    }

    // ---- Synced values ----

    var sailOrder: Float
        get() = entityData.get(DATA_SAIL_ORDER)
        private set(value) = entityData.set(DATA_SAIL_ORDER, value)

    /** Which way the helmsman is putting the helm over: -1, 0 or 1. */
    val helm: Int get() = entityData.get(DATA_HELM).toInt()

    var anchored: Boolean
        get() = entityData.get(DATA_ANCHORED)
        private set(value) = entityData.set(DATA_ANCHORED, value)

    var hull: Float
        get() = entityData.get(DATA_HULL)
        private set(value) = entityData.set(DATA_HULL, value.coerceIn(0f, MAX_HULL))

    /** The game time (low bits) the bell was last struck, or -1. */
    val bellStruck: Int get() = entityData.get(DATA_BELL)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_SAIL_ORDER, 0f)
        builder.define(DATA_HELM, 0.toByte())
        builder.define(DATA_ANCHORED, false)
        builder.define(DATA_HULL, MAX_HULL)
        builder.define(DATA_BELL, -1)
        for (station in DATA_STATIONS) builder.define(station, -1)
    }

    override fun createInterpolationHandler(): InterpolationHandler = LinearInterpolationHandler.create(this, 3)

    // ---- Parts ----

    override fun isMultipartEntity(): Boolean = true
    override fun getParts(): Array<out PartEntity<*>> = parts

    override fun recreateFromPacket(packet: ClientboundAddEntityPacket) {
        super.recreateFromPacket(packet)
        for (i in parts.indices) parts[i].setId(packet.id + i + 1)
    }

    private fun placeParts() {
        val fx = forwardX(yRot)
        val fz = forwardZ(yRot)
        for (part in parts) {
            part.setOldPosAndRot()
            part.setPos(x + fx * part.forward, y - PART_DROP, z + fz * part.forward)
        }
    }

    // ---- Being a vehicle ----

    override fun isPickable(): Boolean = !isRemoved
    override fun canRiderInteract(): Boolean = true
    override fun getPickResult(): ItemStack = ItemStack(ShipRegistry.COG_ITEM.get())
    override fun getDropItem(): Item = ShipRegistry.COG_ITEM.get()

    override fun canAddPassenger(passenger: Entity): Boolean =
        passenger !is Cog && passengers.size < CogStation.entries.size && CogStation.entries.any { isFree(it) }

    override fun addPassenger(passenger: Entity) {
        super.addPassenger(passenger)
        if (level().isClientSide) return
        val order = if (passenger is Player) CogStation.FOR_PLAYERS else CogStation.FOR_OTHERS
        val station = wanted?.takeIf { isFree(it) && (passenger is Player || it != CogStation.HELM) } ?: order.firstOrNull { isFree(it) }
        wanted = null
        if (station != null) {
            setOccupant(station, passenger.id)
            if (passenger is ServerPlayer) hint(passenger, station)
        }
    }

    override fun removePassenger(passenger: Entity) {
        super.removePassenger(passenger)
        if (level().isClientSide) return
        for (station in CogStation.entries) if (occupantId(station) == passenger.id) setOccupant(station, -1)
        if (passenger is ServerPlayer) Ships.left(passenger, this)
    }

    /** The station [entity] is at aboard, if they're aboard. */
    fun stationOf(entity: Entity): CogStation? {
        val id = entity.id
        for (station in CogStation.entries) if (entityData.get(DATA_STATIONS[station.ordinal]) == id) return station
        return null
    }

    /** Who's at [station], if anyone. */
    fun occupant(station: CogStation): Entity? {
        val id = occupantId(station)
        if (id < 0) return null
        return passengers.firstOrNull { it.id == id }
    }

    private fun occupantId(station: CogStation): Int = entityData.get(DATA_STATIONS[station.ordinal])
    private fun setOccupant(station: CogStation, id: Int) = entityData.set(DATA_STATIONS[station.ordinal], id)
    private fun isFree(station: CogStation): Boolean = occupant(station) == null

    /** Takes [entity] aboard to a free seat (never the helm), if there's one. For companions following their player. */
    fun takeAboard(entity: Entity): Boolean {
        if (level().isClientSide || !canAddPassenger(entity)) return false
        wanted = CogStation.FOR_OTHERS.firstOrNull { isFree(it) } ?: return false
        if (!entity.startRiding(this, true, true)) {
            wanted = null
            return false
        }
        return true
    }

    /** Walks someone aboard from where they are to [station]. */
    private fun moveTo(entity: Entity, station: CogStation) {
        for (s in CogStation.entries) if (occupantId(s) == entity.id) setOccupant(s, -1)
        setOccupant(station, entity.id)
        level().playSound(null, entity.x, entity.y, entity.z, Blocks.SPRUCE_PLANKS.defaultBlockState().soundType.stepSound, SoundSource.PLAYERS, 0.6f, 0.9f)
        if (entity is ServerPlayer) hint(entity, station)
    }

    /** Tells a player what they can do from where they've just taken their place aboard, in their own keys. */
    private fun hint(player: ServerPlayer, station: CogStation) {
        player.sendOverlayMessage(
            if (station == CogStation.HELM) {
                Component.translatable("message.guildmark.ship.helm", key("key.forward"), key("key.back"), key("key.left"), key("key.right"), key("key.jump"), key("key.sprint"))
            } else {
                Component.translatable("message.guildmark.ship.deck", key("key.inventory"), key("key.sneak"))
            },
        )
    }

    private fun key(name: String): Component = Component.keybind(name).withStyle(ChatFormatting.GOLD)

    override fun positionRider(passenger: Entity, moveFunction: MoveFunction) {
        val station = stationOf(passenger)
        if (station == null) {
            super.positionRider(passenger, moveFunction)
            return
        }
        val at = deckPoint(station.x, station.y, station.z, 1f)
        if (station.standing) {
            moveFunction.accept(passenger, at.x, at.y, at.z)
        } else {
            val seat = passenger.getVehicleAttachmentPoint(this)
            moveFunction.accept(passenger, at.x - seat.x, at.y - seat.y, at.z - seat.z)
        }
        // Everyone aboard turns with her, facing forward, free to look about
        val turned = Mth.wrapDegrees(yRot - yRotO)
        if (passenger.isLocalInstanceAuthoritative && turned != 0f) {
            passenger.yRot = passenger.yRot + turned
            passenger.yHeadRot = passenger.yHeadRot + turned
        }
        clampRotation(passenger)
    }

    private fun clampRotation(passenger: Entity): Float {
        passenger.setYBodyRot(yRot)
        if (passenger is Mob) {
            passenger.yRot = yRot
            passenger.clampHeadRotationToBody()
            return 0f
        }
        val delta = Mth.wrapDegrees(passenger.yRot - yRot)
        val held = Mth.clamp(delta, -LOOK_ROUND, LOOK_ROUND)
        passenger.yRot = passenger.yRot + held - delta
        return held - delta
    }

    override fun onPassengerTurned(passenger: Entity) {
        passenger.yRotO += clampRotation(passenger)
    }

    /**
     * Somewhere to step off to: dry land within a few blocks of where they were, or failing that the water
     * just outside the hull on their side.
     */
    override fun getDismountLocationForPassenger(passenger: LivingEntity): Vec3 {
        val station = stationOf(passenger) ?: CogStation.STARBOARD_BENCH
        val side = if (station.x >= 0.0) 1.0 else -1.0
        val z = station.z.coerceIn(-3.6, 3.4)
        val level = level()
        // Land: along their side first, then the other, then off the bow and stern
        for (reach in doubleArrayOf(2.6, 3.4, 4.2, 5.0)) {
            for (s in doubleArrayOf(side, -side)) {
                for (dz in doubleArrayOf(0.0, 1.5, -1.5, 3.0, -3.0)) {
                    land(level, passenger, localToWorld(s * reach, 0.0, (z + dz).coerceIn(-4.5, 4.4)))?.let { return it }
                }
            }
            land(level, passenger, localToWorld(0.0, 0.0, 4.6 + reach - 2.6))?.let { return it }
            land(level, passenger, localToWorld(0.0, 0.0, -5.0 - (reach - 2.6)))?.let { return it }
        }
        // The water beside her
        val water = localToWorld(side * 2.4, 0.0, z)
        return Vec3(water.x, y - 0.5, water.z)
    }

    /** A place to stand on solid ground near [at] (a column a little above and below the waterline), if there is one. */
    private fun land(level: Level, passenger: LivingEntity, at: Vec3): Vec3? {
        val base = BlockPos.containing(at.x, y + 2.0, at.z)
        for (dy in 0..4) {
            val pos = base.below(dy)
            if (level.isWaterAt(pos)) return null
            val floor = level.getBlockFloorHeight(pos)
            if (!DismountHelper.isBlockFloorValid(floor)) continue
            val spot = Vec3(at.x, pos.y + floor, at.z)
            if (DismountHelper.canDismountTo(level, spot, passenger, net.minecraft.world.entity.Pose.STANDING)) return spot
        }
        return null
    }

    // ---- Where things are on her ----

    /**
     * The world position of a point on deck, given in blocks from the middle of the ship at its waterline
     * ([sx] to starboard, [sy] up, [sz] forward), rocked by the swell and heeled as she was [partial] of the way
     * from last tick to this one (1 for now: where her passengers are put each tick, which the renderer matches).
     */
    fun deckPoint(sx: Double, sy: Double, sz: Double, partial: Float): Vec3 {
        val (pitch, roll, bob) = lean(partial)
        val r = roll * Mth.DEG_TO_RAD
        val p = pitch * Mth.DEG_TO_RAD
        // Heeled over to port: the starboard side rises
        val x1 = sx * cos(r) - sy * sin(r)
        val y1 = sy * cos(r) + sx * sin(r)
        // Pitched nose up: the bow rises
        val y2 = y1 * cos(p) + sz * sin(p)
        val z2 = sz * cos(p) - y1 * sin(p)
        val yaw = Mth.rotLerp(partial, yRotO, yRot)
        val px = Mth.lerp(partial.toDouble(), xo, x)
        val py = Mth.lerp(partial.toDouble(), yo, y)
        val pz = Mth.lerp(partial.toDouble(), zo, z)
        return Vec3(
            px + rightX(yaw) * x1 + forwardX(yaw) * z2,
            py + y2 + bob,
            pz + rightZ(yaw) * x1 + forwardZ(yaw) * z2,
        )
    }

    /** A point of the ship's own frame (no swell) in the world, as she is now. */
    fun localToWorld(sx: Double, sy: Double, sz: Double): Vec3 =
        Vec3(x + rightX(yRot) * sx + forwardX(yRot) * sz, y + sy, z + rightZ(yRot) * sx + forwardZ(yRot) * sz)

    /** A world point in the ship's own frame: (to starboard, up, forward). */
    fun worldToLocal(at: Vec3): Vec3 {
        val dx = at.x - x
        val dz = at.z - z
        return Vec3(dx * rightX(yRot) + dz * rightZ(yRot), at.y - y, dx * forwardX(yRot) + dz * forwardZ(yRot))
    }

    /**
     * How she lies in the water, [partial] of the way from last tick to this one: degrees nose up, degrees heeled
     * to port, and blocks lifted by the swell. The swell keeps time by her own age, so no two ships rock together,
     * and rises with the weather.
     */
    fun lean(partial: Float): Triple<Float, Float, Float> {
        val t = tickCount - 1 + partial
        val level = level()
        val rough = level.getRainLevel(partial) * 0.6f + level.getThunderLevel(partial) * 0.8f
        val moving = (Mth.lerp(partial, seenSpeedO, seenSpeed) / 0.3f).coerceIn(0f, 1.2f)
        val pitch = (0.7f + 1.6f * rough + 0.5f * moving) * (sin(t * 0.069f) * 0.7f + sin(t * 0.153f + 1.1f) * 0.3f)
        val roll = (1.0f + 2.4f * rough) * (sin(t * 0.048f + 0.6f) * 0.75f + sin(t * 0.117f + 2.3f) * 0.25f)
        val bob = (0.04f + 0.08f * rough) * (sin(t * 0.083f + 0.3f) * 0.7f + sin(t * 0.19f) * 0.3f)
        val heeled = Mth.lerp(partial, heelO, heel)
        return Triple(pitch, roll + heeled, bob)
    }

    // ---- Ticking ----

    override fun tick() {
        if (hurtTime > 0) setHurtTime(hurtTime - 1)
        super.tick()
        val level = level()
        if (level is ServerLevel) serverTick(level) else clientTick(level)
        placeParts()
    }

    private fun serverTick(level: ServerLevel) {
        if (bumpCooldown > 0) bumpCooldown--
        if (hoistCooldown > 0) hoistCooldown--
        if (bellCooldown > 0) bellCooldown--

        // The helmsman's orders
        val helmsman = occupant(CogStation.HELM) as? ServerPlayer
        val input = helmsman?.lastClientInput ?: Input.EMPTY
        var order = sailOrder
        if (helmsman != null) {
            if (input.forward() && !input.backward()) order = (order + ORDER_RATE).coerceAtMost(1f)
            if (input.backward() && !input.forward()) order = (order - ORDER_RATE).coerceAtLeast(0f)
            if (input.jump() && !jumpHeld) setAnchor(level, !anchored)
            if (input.sprint() && !sprintHeld && bellCooldown == 0) strikeBell(level)
        }
        jumpHeld = input.jump()
        sprintHeld = input.sprint()
        if (order != sailOrder) {
            // Hands on the halyard: a rope running through its blocks as the sail goes up or down
            if (hoistCooldown == 0 || (order - lastOrder) * (sailOrder - lastOrder) < 0f) {
                level.playSound(null, x, y + 7.0, z, ShipRegistry.HOIST.get(), SoundSource.NEUTRAL, 0.8f, 0.9f + random.nextFloat() * 0.2f)
                hoistCooldown = 30
            }
            lastOrder = sailOrder
            sailOrder = order
        }
        val turnTo = if (helmsman == null) 0 else (if (input.right()) 1 else 0) - (if (input.left()) 1 else 0)
        if (turnTo != helm) entityData.set(DATA_HELM, turnTo.toByte())
        followOrders()

        // Ringing the watches at dawn and dusk, with a hand at the helm
        val watchNow = Math.floorDiv(level.overworldClockTime, 12000L)
        if (helmsman != null && watch != Long.MIN_VALUE && watchNow != watch) strikeBell(level)
        watch = watchNow

        // The wind in her sail
        val windFrom = Wind.from(level)
        val strength = Wind.strength(level)
        val rel = Mth.wrapDegrees(windFrom - yRot)
        val pushingOff = helmsman != null && sail < 0.05f && input.backward() && !anchored
        val target = when {
            anchored || helmsman == null -> 0.0
            pushingOff -> -PUSH_OFF
            else -> Sailing.speed(rel, sail, strength)
        }
        speed += (target - speed) * (if (abs(target) > abs(speed)) ACCEL else DECEL)
        if (anchored) speed *= 0.9
        if (abs(speed) < 1.0e-4) speed = 0.0

        // The helm bites harder the faster she goes; at anchor she swings her bow round into the wind
        val turn = if (anchored) {
            Mth.clamp(Mth.wrapDegrees(windFrom - yRot) * 0.004f, -0.25f, 0.25f) * anchorDrop
        } else {
            rudder * (TURN_SLOW + TURN_FAST * min(1.0, abs(speed) / 0.25).toFloat()) * (if (speed < 0) -1f else 1f)
        }
        yawRate += (turn - yawRate) * 0.25f

        // Leeway: the wind sets her a little to leeward with her sail set
        val leeway = if (anchored || helmsman == null) 0.0 else LEEWAY * strength * sail * sin(rel * Mth.DEG_TO_RAD)
        val yawNew = yRot + yawRate
        val dx = forwardX(yawNew) * speed - rightX(yawNew) * leeway
        val dz = forwardZ(yawNew) * speed - rightZ(yawNew) * leeway
        moveHull(level, dx, dz, yawNew)
        floatOnWater(level)

        // Mostly for the seat she's at: anyone aboard who's dropped dead or gone is let go
        if (tickCount % 20 == 0) for (station in CogStation.entries) {
            val id = occupantId(station)
            if (id >= 0 && passengers.none { it.id == id }) setOccupant(station, -1)
        }
    }

    /** The sail and the helm follow the orders: let down or hauled up hand over hand, the helm put over and eased back. */
    private fun followOrders() {
        sailO = sail
        val order = sailOrder
        sail = approach(sail, order, if (order > sail) LET_DOWN else HAUL_UP)
        rudderO = rudder
        val to = helm.toFloat()
        rudder = approach(rudder, to, if (to == 0f) CENTRE_RATE else TURN_RATE)
    }

    private fun clientTick(level: Level) {
        followOrders()
        // The local helmsman's wheel goes over as they press, not when the server hears of it
        localHelm?.invoke(this)?.let { rudder = approach(rudderO, it.toFloat(), if (it == 0) CENTRE_RATE else TURN_RATE) }
        braceO = brace
        fillO = fill
        luffO = luff
        heelO = heel
        anchorDropO = anchorDrop
        seenSpeedO = seenSpeed
        val windFrom = Wind.from(level)
        val strength = Wind.strength(level)
        val rel = Mth.wrapDegrees(windFrom - yRot)
        // The yard is hauled round to the wind; head to wind it's swung right across
        brace = approach(brace, Sailing.brace(rel), BRACE_RATE)
        fill += (Sailing.fill(rel, sail, strength) - fill) * 0.08f
        luff += (Sailing.luff(rel, sail) - luff) * 0.1f
        anchorDrop = approach(anchorDrop, if (anchored) 1f else 0f, if (anchored) 0.035f else 0.022f)
        val moved = sqrt((x - xo) * (x - xo) + (z - zo) * (z - zo)).toFloat()
        seenSpeed += (moved - seenSpeed) * 0.3f
        // Heeled by the wind in her sail, and leaning out of a turn
        val turning = Mth.wrapDegrees(yRot - yRotO)
        heel += (Sailing.heel(rel, fill, strength) + turning * seenSpeed * 9f - heel) * 0.05f
        clientEffects?.invoke(this)
    }

    // ---- Moving her ----

    /** Moves and turns her as far as the water lets her: what can't be done isn't, and a hard knock costs the hull. */
    private fun moveHull(level: ServerLevel, dx: Double, dz: Double, yawNew: Float) {
        val moving = dx * dx + dz * dz > 1.0e-8
        val turning = yawNew != yRot
        if (!moving && !turning) return
        val nx = x + dx
        val nz = z + dz
        if (fits(level, nx, nz, yawNew)) {
            setPos(nx, y, nz)
            yRot = yawNew
            return
        }
        when {
            moving && fits(level, nx, nz, yRot) -> {
                setPos(nx, y, nz)
                yawRate = 0f
            }
            turning && fits(level, x, z, yawNew) -> {
                yRot = yawNew
                collide(level)
            }
            else -> {
                yawRate = 0f
                collide(level)
            }
        }
    }

    private fun collide(level: ServerLevel) {
        val hit = abs(speed)
        speed = -speed * 0.25
        if (hit < 0.03 || bumpCooldown > 0) return
        bumpCooldown = 16
        val bow = localToWorld(0.0, 0.2, if (speed <= 0) 4.0 else -4.4)
        level.playSound(null, bow.x, bow.y, bow.z, ShipRegistry.SCRAPE.get(), SoundSource.NEUTRAL, (0.5f + hit.toFloat() * 2f).coerceAtMost(1.2f), 0.9f + random.nextFloat() * 0.2f)
        if (hit > 0.12) level.playSound(null, bow.x, bow.y, bow.z, ShipRegistry.BUMP.get(), SoundSource.NEUTRAL, 1f, 0.8f + random.nextFloat() * 0.2f)
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState()), bow.x, bow.y, bow.z, (hit * 60).toInt().coerceIn(3, 20), 0.6, 0.3, 0.6, 0.1)
        level.sendParticles(ParticleTypes.SPLASH, bow.x, y + 0.1, bow.z, 12, 0.8, 0.1, 0.8, 0.2)
        if (hit > CRASH_SPEED) damageHull(level, ((hit - CRASH_SPEED) * 60.0).toFloat(), null)
    }

    /** Whether she'd sit at ([px], [pz]) heading [yaw] with nothing solid along her waterline. */
    private fun fits(level: Level, px: Double, pz: Double, yaw: Float): Boolean {
        val fx = forwardX(yaw)
        val fz = forwardZ(yaw)
        val rx = rightX(yaw)
        val rz = rightZ(yaw)
        var i = 0
        while (i < HULL.size) {
            val sx = HULL[i]
            val sz = HULL[i + 1]
            val wx = px + rx * sx + fx * sz
            val wz = pz + rz * sx + fz * sz
            if (solidAt(level, wx, y - 0.3, wz) || solidAt(level, wx, y + 0.6, wz)) return false
            i += 2
        }
        return true
    }

    private val cursor = BlockPos.MutableBlockPos()

    /** Something a hull can't pass through: thin things lying on the water (lily pads, carpets) don't count. */
    private fun solidAt(level: Level, x: Double, y: Double, z: Double): Boolean {
        cursor.set(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
        val state = level.getBlockState(cursor)
        if (state.isAir) return false
        val shape = state.getCollisionShape(level, cursor)
        return !shape.isEmpty && shape.max(Direction.Axis.Y) > 0.25
    }

    /** She rides at the water's surface; out of the water she settles onto what's under her. */
    private fun floatOnWater(level: Level) {
        val surface = surfaceAt(level, x, z)
        if (surface != null) {
            if (abs(surface - y) > 1.0e-3) setPos(x, y + (surface - y) * 0.35, z)
            return
        }
        if (!solidAt(level, x, y - 0.7, z)) setPos(x, y - 0.12, z)
    }

    /** Whether she'd lie afloat where she is now: water under all her waterline and nothing solid in the way. */
    fun canLieAt(level: Level): Boolean {
        if (surfaceAt(level, x, z) == null) return false
        var i = 0
        while (i < HULL.size) {
            val wx = x + rightX(yRot) * HULL[i] + forwardX(yRot) * HULL[i + 1]
            val wz = z + rightZ(yRot) * HULL[i] + forwardZ(yRot) * HULL[i + 1]
            if (surfaceAt(level, wx, wz) == null) return false
            i += 2
        }
        return fits(level, x, z, yRot)
    }

    /** The top of the water under (or around) her waterline at ([px], [pz]), if there's water there. */
    fun surfaceAt(level: Level, px: Double, pz: Double): Double? {
        val bx = floor(px).toInt()
        val bz = floor(pz).toInt()
        var by = floor(y + 1.0).toInt()
        val bottom = floor(y - 2.0).toInt()
        while (by >= bottom) {
            cursor.set(bx, by, bz)
            val fluid = level.getFluidState(cursor)
            if (fluid.`is`(FluidTags.WATER)) return by + fluid.getHeight(level, cursor).toDouble()
            by--
        }
        return null
    }

    // ---- The anchor and the bell ----

    private fun setAnchor(level: ServerLevel, down: Boolean) {
        if (down == anchored) return
        anchored = down
        // The cathead on her starboard bow
        val bow = localToWorld(1.25, 1.1, 3.5)
        level.playSound(null, bow.x, bow.y, bow.z, if (down) ShipRegistry.ANCHOR_DROP.get() else ShipRegistry.ANCHOR_RAISE.get(), SoundSource.NEUTRAL, 1f, 1f)
        if (down) {
            level.sendParticles(ParticleTypes.SPLASH, bow.x, y + 0.1, bow.z, 20, 0.3, 0.1, 0.3, 0.3)
            strikeBell(level)
        }
    }

    fun strikeBell(level: ServerLevel) {
        bellCooldown = 50
        entityData.set(DATA_BELL, (level.gameTime and 0x3FFFFFFF).toInt())
        val bell = localToWorld(-0.6, 2.75, -2.4)
        level.playSound(null, bell.x, bell.y, bell.z, ShipRegistry.BELL.get(), SoundSource.NEUTRAL, 1.2f, 1f)
    }

    // ---- Clicked on ----

    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult =
        interactAt(player, hand, position().add(location))

    /** Someone clicked on her at [at] (a point in the world, on her hull or one of her parts). */
    fun interactAt(player: Player, hand: InteractionHand, at: Vec3): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (stack.`is`(ItemTags.PLANKS) && hull < MAX_HULL) {
            if (!level().isClientSide) repair(player, stack)
            return InteractionResult.SUCCESS
        }
        if (stack.`is`(Items.NAME_TAG) && stack.has(DataComponents.CUSTOM_NAME)) {
            if (!level().isClientSide) {
                customName = stack.hoverName
                if (!player.abilities.instabuild) stack.shrink(1)
                (level() as? ServerLevel)?.let { strikeBell(it) }
            }
            return InteractionResult.SUCCESS
        }
        val local = worldToLocal(at)
        if (player.vehicle === this) {
            // Aboard: the hatch opens the hold, anywhere else walks you to the nearest free place to it
            if (nearHatch(local)) return openHold(player)
            val here = stationOf(player)
            val there = nearestFree(local, player) ?: return InteractionResult.PASS
            if (there == here) return InteractionResult.PASS
            if (!level().isClientSide) moveTo(player, there)
            return InteractionResult.SUCCESS
        }
        if (player.isSecondaryUseActive) return openHold(player)
        if (!canAddPassenger(player)) return InteractionResult.PASS
        if (!level().isClientSide) {
            wanted = nearestFree(local, player)
            if (!player.startRiding(this)) wanted = null
        }
        return InteractionResult.SUCCESS
    }

    private fun nearHatch(local: Vec3): Boolean = abs(local.x) < HATCH_HALF_WIDTH && abs(local.z - HATCH_Z) < HATCH_HALF_LENGTH && local.y < 1.6

    /** The free station nearest a point on deck (in her frame); players may take the helm, nobody else. */
    private fun nearestFree(local: Vec3, who: Entity): CogStation? {
        var best: CogStation? = null
        var bestD = Double.MAX_VALUE
        for (station in CogStation.entries) {
            if (station == CogStation.HELM && who !is Player) continue
            val mine = occupant(station) === who
            if (!mine && !isFree(station)) continue
            val dx = station.x - local.x
            val dz = station.z - local.z
            val d = dx * dx + dz * dz
            if (d < bestD) {
                bestD = d
                best = station
            }
        }
        return best
    }

    private fun repair(player: Player, stack: ItemStack) {
        hull += REPAIR_PER_PLANK
        if (!player.abilities.instabuild) stack.shrink(1)
        val level = level() as? ServerLevel ?: return
        val at = player.position()
        level.playSound(null, at.x, at.y, at.z, ShipRegistry.REPAIR.get(), SoundSource.NEUTRAL, 1f, 0.9f + random.nextFloat() * 0.2f)
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState()), x, y + 1.0, z, 8, 1.0, 0.4, 2.0, 0.05)
        player.sendOverlayMessage(Component.translatable("message.guildmark.ship.repaired", (hull / MAX_HULL * 100f).toInt()))
    }

    // ---- The hold ----

    private fun openHold(player: Player): InteractionResult {
        val result = interactWithContainerVehicle(player)
        if (result.consumesAction() && !level().isClientSide) level().playSound(null, x, y + 1.0, z, ShipRegistry.HOLD_OPEN.get(), SoundSource.NEUTRAL, 0.9f, 1f)
        return result
    }

    override fun openCustomInventoryScreen(player: Player) {
        player.openMenu(this)
        if (!player.level().isClientSide) level().playSound(null, x, y + 1.0, z, ShipRegistry.HOLD_OPEN.get(), SoundSource.NEUTRAL, 0.9f, 1f)
    }

    override fun stopOpen(containerUser: ContainerUser) {
        if (!level().isClientSide) level().playSound(null, x, y + 1.0, z, ShipRegistry.HOLD_CLOSE.get(), SoundSource.NEUTRAL, 0.9f, 1f)
    }

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu? {
        if (lootTable != null && player.isSpectator) return null
        unpackChestVehicleLootTable(inventory.player)
        return ChestMenu.threeRows(containerId, inventory, this)
    }

    // A cog can't be stowed in its own hold, nor in any other's
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = !stack.`is`(ShipRegistry.COG_ITEM.get())

    override fun clearContent() = clearChestVehicleContent()
    override fun getContainerSize(): Int = HOLD_SIZE
    override fun getItem(slot: Int): ItemStack = getChestVehicleItem(slot)
    override fun removeItem(slot: Int, count: Int): ItemStack = removeChestVehicleItem(slot, count)
    override fun removeItemNoUpdate(slot: Int): ItemStack = removeChestVehicleItemNoUpdate(slot)
    override fun setItem(slot: Int, itemStack: ItemStack) = setChestVehicleItem(slot, itemStack)
    override fun getSlot(slot: Int): SlotAccess? = getChestVehicleSlot(slot)
    override fun setChanged() {}
    override fun stillValid(player: Player): Boolean = isChestVehicleStillValid(player)
    override fun getContainerLootTable(): ResourceKey<LootTable>? = lootTable
    override fun setContainerLootTable(lootTable: ResourceKey<LootTable>?) { this.lootTable = lootTable }
    override fun getContainerLootTableSeed(): Long = lootTableSeed
    override fun setContainerLootTableSeed(lootTableSeed: Long) { this.lootTableSeed = lootTableSeed }
    override fun getItemStacks(): NonNullList<ItemStack> = itemStacks
    override fun clearItemStacks() { itemStacks = NonNullList.withSize(HOLD_SIZE, ItemStack.EMPTY) }

    /** Fills the hold from a packed cog's item. */
    fun loadHold(contents: ItemContainerContents) {
        clearItemStacks()
        contents.copyInto(itemStacks)
    }

    // ---- Struck ----

    override fun skipAttackInteraction(attacker: Entity): Boolean = false

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean {
        if (isRemoved || isInvulnerableToBase(source)) return false
        val attacker = source.entity
        if (attacker is Player) {
            // Players' blows (and arrows) never harm her: struck crouching by her owner, she's packed away
            if (source.directEntity === attacker && attacker.isShiftKeyDown) {
                if (mayPack(attacker)) pack(level, attacker)
                else attacker.sendOverlayMessage(Component.translatable("message.guildmark.ship.not_yours"))
            } else if (source.directEntity === attacker) {
                attacker.sendOverlayMessage(Component.translatable("message.guildmark.ship.pack_hint"))
                level.playSound(null, x, y + 0.8, z, ShipRegistry.BUMP.get(), SoundSource.NEUTRAL, 0.4f, 1.3f)
            }
            return false
        }
        setHurtDir(-hurtDir)
        setHurtTime(10)
        markHurt()
        level.playSound(null, x, y + 0.8, z, ShipRegistry.BUMP.get(), SoundSource.NEUTRAL, 0.9f, 0.9f + random.nextFloat() * 0.2f)
        damageHull(level, damage, source)
        return true
    }

    private fun mayPack(player: Player): Boolean =
        owner == null || owner == player.uuid || player.abilities.instabuild || (player as? ServerPlayer)?.let { it.level().server.playerList.isOp(it.nameAndId()) } == true

    private fun damageHull(level: ServerLevel, amount: Float, source: DamageSource?) {
        hull -= amount
        if (hull <= 0f) wreck(level)
    }

    /** Packs her away into her item: the sail struck, the hold and her name kept with it, given to [player]. */
    private fun pack(level: ServerLevel, player: Player) {
        val stack = ItemStack(ShipRegistry.COG_ITEM.get())
        customName?.let { stack.set(DataComponents.CUSTOM_NAME, it) }
        if (!isChestVehicleEmpty) stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks))
        if (hull < MAX_HULL) stack.set(ShipRegistry.HULL.get(), hull)
        clearItemStacks()
        ejectPassengers()
        level.playSound(null, x, y + 1.0, z, ShipRegistry.PACK.get(), SoundSource.NEUTRAL, 1f, 1f)
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.1, z, 30, 1.5, 0.1, 3.0, 0.2)
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        Ships.gone(this)
        discard()
    }

    /** Broken up: her hold spills into the water with what's left of her. */
    private fun wreck(level: ServerLevel) {
        level.playSound(null, x, y + 1.0, z, ShipRegistry.WRECK.get(), SoundSource.NEUTRAL, 1.5f, 1f)
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState()), x, y + 1.0, z, 80, 1.6, 0.8, 3.5, 0.2)
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.1, z, 60, 2.0, 0.1, 4.0, 0.3)
        ejectPassengers()
        Containers.dropContents(level, this, this)
        if (level.gameRules.get(GameRules.ENTITY_DROPS)) {
            spawnAtLocation(level, ItemStack(Items.SPRUCE_PLANKS, 10))
            spawnAtLocation(level, ItemStack(Items.STICK, 6))
            spawnAtLocation(level, ItemStack(Items.WOOL.pick(DyeColor.RED), 2))
        }
        Ships.gone(this)
        kill(level)
    }

    // ---- Saved ----

    override fun addAdditionalSaveData(output: ValueOutput) {
        addChestVehicleSaveData(output)
        owner?.let { output.store("owner", UUIDUtil.CODEC, it) }
        output.putFloat("sail", sail)
        output.putFloat("sail_order", sailOrder)
        output.putBoolean("anchored", anchored)
        output.putFloat("hull", hull)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        readChestVehicleSaveData(input)
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null)
        sail = input.getFloatOr("sail", 0f)
        sailO = sail
        sailOrder = input.getFloatOr("sail_order", 0f)
        lastOrder = sailOrder
        anchored = input.getBooleanOr("anchored", false)
        anchorDrop = if (anchored) 1f else 0f
        hull = input.getFloatOr("hull", MAX_HULL)
    }

    /** Set on the water from her item: her name, her hold and her hull come with it. */
    fun launchFrom(stack: ItemStack, placer: Player?) {
        owner = placer?.uuid
        stack.get(DataComponents.CUSTOM_NAME)?.let { customName = it }
        stack.get(DataComponents.CONTAINER)?.let { loadHold(it) }
        hull = stack.get(ShipRegistry.HULL.get()) ?: MAX_HULL
    }

    companion object {
        private val DATA_SAIL_ORDER: EntityDataAccessor<Float> = SynchedEntityData.defineId(Cog::class.java, EntityDataSerializers.FLOAT)
        private val DATA_HELM: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Cog::class.java, EntityDataSerializers.BYTE)
        private val DATA_ANCHORED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(Cog::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_HULL: EntityDataAccessor<Float> = SynchedEntityData.defineId(Cog::class.java, EntityDataSerializers.FLOAT)
        private val DATA_BELL: EntityDataAccessor<Int> = SynchedEntityData.defineId(Cog::class.java, EntityDataSerializers.INT)

        /** Who's at each station, by entity id, or -1. */
        private val DATA_STATIONS: Array<EntityDataAccessor<Int>> =
            Array(CogStation.entries.size) { SynchedEntityData.defineId(Cog::class.java, EntityDataSerializers.INT) }

        /** On a client, which way its own player is putting the helm over, if they're this ship's helmsman. */
        var localHelm: ((Cog) -> Int?)? = null

        /** On a client, the wake, the creaks and the flapping canvas, done each tick (see client/ship/CogEffects). */
        var clientEffects: ((Cog) -> Unit)? = null

        const val HOLD_SIZE = 27
        const val MAX_HULL = 100f
        const val REPAIR_PER_PLANK = 12f

        /** How fast the sail order moves while forward or back is held: about two and a half seconds from furled to full. */
        private const val ORDER_RATE = 0.02f
        /** How fast the canvas follows: let down quicker than it's hauled up. */
        private const val LET_DOWN = 0.03f
        private const val HAUL_UP = 0.022f
        /** How fast the helm goes over and comes back amidships when let go. */
        private const val TURN_RATE = 0.06f
        private const val CENTRE_RATE = 0.08f
        /** Degrees a tick she turns with the helm hard over: barely moving, and with way on her. */
        private const val TURN_SLOW = 0.4f
        private const val TURN_FAST = 1.6f
        /** How quickly she gathers way and loses it. */
        private const val ACCEL = 0.012
        private const val DECEL = 0.02
        /** Blocks a tick she's pushed off astern with the sail furled. */
        private const val PUSH_OFF = 0.05
        private const val LEEWAY = 0.012
        /** Faster than this into something solid, and she's holed a little. */
        private const val CRASH_SPEED = 0.15
        /** Degrees a tick the yard is braced round. */
        private const val BRACE_RATE = 1.2f
        /** Degrees anyone aboard can look round from straight ahead. */
        private const val LOOK_ROUND = 135f
        /** The hatch to the hold, in her frame. */
        private const val HATCH_Z = -1.25
        private const val HATCH_HALF_WIDTH = 0.85
        private const val HATCH_HALF_LENGTH = 0.75
        /** How far below the waterline the parts' boxes start. */
        private const val PART_DROP = 0.35

        /** Points round her waterline, (to starboard, forward) in blocks, that must stay clear of anything solid. */
        private val HULL = doubleArrayOf(
            0.0, 4.1,
            0.9, 3.2, -0.9, 3.2,
            1.5, 1.8, -1.5, 1.8,
            1.75, 0.0, -1.75, 0.0,
            1.7, -2.0, -1.7, -2.0,
            1.3, -3.8, -1.3, -3.8,
            0.7, -4.6, -0.7, -4.6,
        )

        // Minecraft yaw: 0 faces south (+z), 90 west (-x). Forward and starboard (the right hand) for a yaw.
        fun forwardX(yaw: Float): Double = -sin(yaw * Mth.DEG_TO_RAD).toDouble()
        fun forwardZ(yaw: Float): Double = cos(yaw * Mth.DEG_TO_RAD).toDouble()
        fun rightX(yaw: Float): Double = -cos(yaw * Mth.DEG_TO_RAD).toDouble()
        fun rightZ(yaw: Float): Double = -sin(yaw * Mth.DEG_TO_RAD).toDouble()

        fun approach(from: Float, to: Float, step: Float): Float =
            if (from < to) min(to, from + step) else kotlin.math.max(to, from - step)
    }
}
