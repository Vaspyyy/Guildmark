package io.github.vaspyyy.guildmark.cart

import io.github.vaspyyy.guildmark.trade.carry.CartHitch
import io.github.vaspyyy.guildmark.trade.carry.ChapmansCartItem
import io.github.vaspyyy.guildmark.trade.carry.PackKit
import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.core.UUIDUtil
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Entity.RemovalReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.SlotAccess
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.ContainerEntity
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max

/**
 * The Chapman's Cart (living-world/trader/design.md, Part 3; build part D2): a handcart that is a player's own. It looks and rolls
 * as a carter's does (it is one, the client draws it with the same model and turns its wheels the same way), but it is saved with the
 * world, it holds 27 stacks (a chest's: vanilla's [ContainerEntity]), and it is kept by the trader's code ([CartHitch]), not by [Handcarts]:
 * the registry never steps it, clears it or counts it, so `/guildmark cart clear` and the Handcarts setting take the carters' carts and
 * never this.
 *
 * It is the player's alone: [ownerId] pulls it, opens it and breaks it; a party mate opens it; anyone else is told whose it is. Fire,
 * explosions, lava and other players do not harm it, and it goes only when its owner breaks it (sneaking, hitting), as an item with its
 * contents beside it.
 *
 * What it keeps (all optional when read, so a cart saved without any of it loads as a parked cart of nobody's, with a default pennant):
 * its [ownerId] and their name (for the line a stranger is told), its [colour] (the pennant's), whether its canvas is [covered], what it
 * was doing when it was saved ([state]; a cart loads parked whatever it was) and its 27 stacks. The server places it each tick, after
 * everyone has moved ([CartHitch]), the way [Handcarts] places a carter's.
 */
class PlayerCart(type: EntityType<out PlayerCart>, level: Level) : Handcart(type, level), ContainerEntity {

    /** What it is doing. */
    enum class State {
        /** Standing where it was left, tipped onto its shafts. */
        PARKED,

        /** Someone has the shafts in hand. */
        HELD,

        /** Rolling to its owner, called by the whistle. */
        CALLED,
    }

    // ---- What every client sees ----

    /** The pennant's colour (an RGB int; the owner's own, one of [DYES], unless the stack it was set down from was dyed another). */
    val colour: Int get() = entityData.get(COLOUR)

    /** Whether the canvas is pulled over the load (else it is folded back at the front). */
    val covered: Boolean get() = entityData.get(COVERED)

    // ---- Kept by the server ----

    /** Whose cart it is; null for one nobody has claimed (the first to use it claims it). */
    var ownerId: UUID? = null
        internal set

    /** Their name when it was set down or last used by them, for the line "That is Ransom's cart.". */
    var ownerName: String = ""
        internal set

    /** What it is doing (saved, but a cart always loads [State.PARKED]). */
    var state: State = State.PARKED
        internal set

    private var stacks: NonNullList<ItemStack> = NonNullList.withSize(SIZE, ItemStack.EMPTY)

    /** What is in it changed since the load on the bed was last worked out. */
    internal var dirty = true

    // What the hitch keeps of it between ticks (CartHitch); none of it is saved
    internal var stuck = 0
    internal var rested = 0
    internal var calledFor = 0
    internal var blockedFor = 0
    internal var rolling = 0.0
    internal var knock = -1

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(COLOUR, DYES[0])
        builder.define(COVERED, false)
    }

    /** Sets the pennant's colour. */
    fun setColour(rgb: Int) {
        entityData.set(COLOUR, rgb and 0xFFFFFF)
    }

    /** Pulls the canvas over the load, or folds it back. */
    fun setCovered(on: Boolean) {
        entityData.set(COVERED, on)
    }

    /** The nearest of the booker's six colours to the pennant's: what a table of six textures is read by. */
    val colourIndex: Int
        get() {
            val rgb = colour
            var best = 0
            var gap = Int.MAX_VALUE
            for ((i, dye) in DYES.withIndex()) {
                val dr = (rgb shr 16 and 0xFF) - (dye shr 16 and 0xFF)
                val dg = (rgb shr 8 and 0xFF) - (dye shr 8 and 0xFF)
                val db = (rgb and 0xFF) - (dye and 0xFF)
                val d = dr * dr + dg * dg + db * db
                if (d < gap) {
                    gap = d
                    best = i
                }
            }
            return best
        }

    // ---- The Handcart's five assumptions of a carter's cart, turned off ----

    /** Its own keeper: it is never discarded for not being in the carters' registry. */
    override fun keep(): Boolean = true

    /** A player's cart does not go through a wall that no swing of the shafts clears: it stays where it is, and the hitch lets go when it cannot follow. */
    override fun through(p: LivingEntity): Boolean = p !is Player

    /**
     * One tick pulled by [p], after everyone has moved: placed behind them as a carter's cart is, except that it does not follow into water, onto
     * leaves or through a wall; where it cannot go it stays put, and the hitch lets go of it when it has not followed for a while.
     */
    internal fun follow(level: ServerLevel, p: LivingEntity) {
        val bx = x
        val by = y
        val bz = z
        val yaw = yRot
        val pitch = xRot
        trail(level, p)
        if (held && p is Player && rutted(level, x, z, yRot)) {
            setPos(bx, by, bz)
            yRot = yaw
            xRot = pitch
        }
        // Its box moves with the way it faces
        setPos(x, y, z)
    }

    /** Whether the axle, or a wheel, with the axle at ([ax], [az]) facing [yaw], would stand in water (or lava) or on leaves: it does not go there. */
    internal fun rutted(level: ServerLevel, ax: Double, az: Double, yaw: Float): Boolean {
        val (fx, fz) = forward(yaw)
        for (k in 0..2) {
            val wx = ax + if (k == 1) -fz * HALF_TRACK else if (k == 2) fz * HALF_TRACK else 0.0
            val wz = az + if (k == 1) fx * HALF_TRACK else if (k == 2) -fx * HALF_TRACK else 0.0
            if (!level.getFluidState(BlockPos.containing(wx, y + 0.15, wz)).isEmpty) return true
            if (level.getBlockState(BlockPos.containing(wx, y - 0.15, wz)).`is`(BlockTags.LEAVES)) return true
        }
        return false
    }

    /** Snapped somewhere it cannot be brought behind: the shafts slip from the puller's hands. */
    override fun lost(level: ServerLevel, p: LivingEntity) {
        CartHitch.caught(this, level)
    }

    override fun shouldBeSaved(): Boolean = removalReason?.shouldSave() ?: true

    override fun addAdditionalSaveData(output: ValueOutput) {
        addChestVehicleSaveData(output)
        ownerId?.let { output.store("Owner", UUIDUtil.CODEC, it) }
        output.putString("OwnerName", ownerName)
        output.putInt("Colour", colour)
        output.putBoolean("Covered", covered)
        output.putString("State", state.name)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        readChestVehicleSaveData(input)
        ownerId = input.read("Owner", UUIDUtil.CODEC).orElse(null)
        ownerName = input.getStringOr("OwnerName", "")
        setColour(input.getIntOr("Colour", ownerId?.let { colourFor(it) } ?: DYES[0]))
        setCovered(input.getBooleanOr("Covered", false))
        // Whatever it was doing, it stands where it was left: nobody has the shafts after a reload
        state = State.PARKED
        dirty = true
    }

    override fun onAddedToLevel() {
        super.onAddedToLevel()
        if (!level().isClientSide) CartHitch.loaded(this)
    }

    override fun onRemovedFromLevel() {
        super.onRemovedFromLevel()
        if (!level().isClientSide) CartHitch.unloaded(this)
    }

    /** Worked out as the game kills it (the operators' kill, the void): what is in it drops, as a chest on a boat's does. Breaking it by hand empties it first. */
    override fun remove(reason: RemovalReason) {
        if (!level().isClientSide && reason.shouldDestroy()) {
            Containers.dropContents(level(), this, this)
            clearContent()
        }
        super.remove(reason)
    }

    /** Fell out of the world: back beside its owner if they are here, else up to the bottom of the world (nothing is lost). */
    override fun onBelowWorld() {
        CartHitch.rescue(this)
    }

    // ---- Its shape for a hand: the shafts count, so the box reaches forward ----

    override fun isPickable(): Boolean = true
    override fun isAttackable(): Boolean = true

    /** The reach a click has past its box: a little more standing, when it is the shafts' tips that are aimed at. */
    override fun getPickRadius(): Float = if (held) 0.2f else 0.55f

    /** The box is the bed and a third of the way out along the shafts, so aiming at the shafts finds the cart (the pick radius does the rest). */
    override fun makeBoundingBox(pos: Vec3): AABB {
        val (fx, fz) = forward(yRot)
        return super.makeBoundingBox(pos.add(fx * BOX_AHEAD, 0.0, fz * BOX_AHEAD))
    }

    /** A hit: only its owner can do anything with it, sneaking (CartHitch.hit); nothing is hurt. */
    override fun skipAttackInteraction(attacker: Entity): Boolean {
        if (attacker is Player && !level().isClientSide) CartHitch.hit(this, attacker)
        return true
    }

    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult = CartHitch.use(this, player, hand, location)

    // ---- Its 27 stacks: a chest's ----

    override fun getContainerSize(): Int = SIZE
    override fun getItem(slot: Int): ItemStack = getChestVehicleItem(slot)
    override fun removeItem(slot: Int, count: Int): ItemStack = removeChestVehicleItem(slot, count)
    override fun removeItemNoUpdate(slot: Int): ItemStack = removeChestVehicleItemNoUpdate(slot)
    override fun setItem(slot: Int, stack: ItemStack) = setChestVehicleItem(slot, stack)
    override fun clearContent() = clearChestVehicleContent()
    override fun getSlot(slot: Int): SlotAccess? = if (slot in 0 until SIZE) getChestVehicleSlot(slot) else super<Handcart>.getSlot(slot)

    /** Someone put something in or took something out: the load on the bed is worked out again at the next tick. */
    override fun setChanged() {
        dirty = true
    }

    override fun stillValid(player: Player): Boolean = isChestVehicleStillValid(player)

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu? =
        if (player.isSpectator) null else ChestMenu.threeRows(containerId, inventory, this)

    override fun getItemStacks(): NonNullList<ItemStack> = stacks
    override fun clearItemStacks() {
        stacks = NonNullList.withSize(SIZE, ItemStack.EMPTY)
    }

    // No loot table: a cart is only ever filled by its owner
    override fun getContainerLootTable(): ResourceKey<LootTable>? = null
    override fun setContainerLootTable(lootTable: ResourceKey<LootTable>?) {}
    override fun getContainerLootTableSeed(): Long = 0L
    override fun setContainerLootTableSeed(seed: Long) {}

    /** What is on the bed: up to eight of the stacks, one piece each, so as many pieces as the cart is full (all eight at a third full or more). */
    internal fun showLoad() {
        dirty = false
        val used = stacks.count { !it.isEmpty }
        val show = if (used == 0) 0 else max(1, Math.round(used * SLOTS / SIZE.toDouble()).toInt()).coerceAtMost(SLOTS)
        // One of each kind first, so a mixed load shows what is in it, then the rest in the order they lie
        val kinds = HashSet<Any>()
        val first = ArrayList<ItemStack>()
        val more = ArrayList<ItemStack>()
        for (stack in stacks) {
            if (stack.isEmpty) continue
            if (kinds.add(stack.item)) first.add(stack) else more.add(stack)
        }
        heap((first + more).take(show))
    }

    /** How many of the 27 slots hold something. */
    fun used(): Int = stacks.count { !it.isEmpty }

    /** The item that is this cart set down again, in the pennant's colour. */
    fun asItem(): ItemStack = ChapmansCartItem.stack(colour)

    companion object {
        /** Its slots: a chest's three rows. */
        const val SIZE = 27

        /** How far out along the shafts, in blocks, the middle of its box is moved. */
        private const val BOX_AHEAD = 0.3

        /**
         * The booker's six colours as the Chapman's Cart item takes them in its `dyed_color` (art stream X1, `trade_cart_dyes.png`): red, blue,
         * green, amber, violet, teal. The colour a cart's pennant has is the owner's own, the same one the blanket of their pack animal is.
         */
        val DYES: IntArray = intArrayOf(12858416, 3695806, 4101708, 14063144, 8669374, 2922130)

        /** The colour of [owner]'s pennant. */
        fun colourFor(owner: UUID): Int = DYES[PackKit.colourOf(owner).coerceIn(0, DYES.size - 1)]

        private val COLOUR: EntityDataAccessor<Int> = SynchedEntityData.defineId(PlayerCart::class.java, EntityDataSerializers.INT)
        private val COVERED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(PlayerCart::class.java, EntityDataSerializers.BOOLEAN)
    }
}
