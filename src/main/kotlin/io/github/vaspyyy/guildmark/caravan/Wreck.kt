package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * A waylaid wagon's wreck by the road (design-road.md section 1): the hull on its side in the verge, a wheel off,
 * the shafts torn away, its lantern out in the grass, the band's arrows in its boards, the crates and sacks it spilled
 * lying round it (its lots, each taken by using it: see [WreckLot]) and, if the horse was cut down, the horse dead in
 * its harness beside it. It stands for one of Part A's wrecks ([wreck]), and shows what's left of it.
 *
 * Its own space is the standing wagon's (client/caravan's WagonMesh): it lies where the wagon stood, turned [yRot],
 * over to its right (or, [FLAG_MIRROR], its left). A prop: never saved, never hit.
 */
class Wreck(type: EntityType<out Wreck>, level: Level) : Entity(type, level) {
    /** The good showing on lot [lot] (WagonMesh.WRECK_LOTS, 8 to 11 heaped on 0 to 3), or null once taken or never there. */
    fun lot(lot: Int): Good? = good(if (lot < 8) entityData.get(LOTS_A) else entityData.get(LOTS_B), lot % 8)

    /** [FLAG_MIRROR] and its kin, the horse's coat from bit [COAT], and the arrows from bit [ARROWS]. */
    val flags: Int get() = entityData.get(FLAGS)

    fun has(flag: Int): Boolean = flags and flag != 0

    val coat: Int get() = (flags shr COAT) and 3
    val arrows: Int get() = (flags shr ARROWS) and 63

    /** What this client keeps to draw it; null on the server. */
    @JvmField
    var look: Any? = null

    /** The wreck in the records it stands for. */
    var wreck = -1L

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(LOTS_A, 0L)
        builder.define(LOTS_B, 0L)
        builder.define(FLAGS, 0)
    }

    override fun tick() {
        if (!level().isClientSide && !Bodies.owns(this)) discard()
    }

    internal fun set(lots: List<Good?>, flags: Int) {
        entityData.set(LOTS_A, pack(lots, 0))
        entityData.set(LOTS_B, pack(lots, 8))
        entityData.set(FLAGS, flags)
    }

    internal fun lots(lots: List<Good?>) {
        entityData.set(LOTS_A, pack(lots, 0))
        entityData.set(LOTS_B, pack(lots, 8))
    }

    internal fun flag(flag: Int, on: Boolean) {
        val f = flags
        val next = if (on) f or flag else f and flag.inv()
        if (next != f) entityData.set(FLAGS, next)
    }

    /** Where lot [lot] lies in the world (on top of the one under it for 8 to 11). */
    fun lotAt(lot: Int): Vec3 {
        val place = PLACES[lot % 8]
        val mirror = if (has(FLAG_MIRROR)) -1f else 1f
        // The wreck's space: px, the front -z, the right -x, as WagonMesh is
        val px = place[0] * mirror
        val pz = place[1]
        val r = Math.toRadians(180.0 - yRot)
        // Turned as the renderer turns it: y by 180 - yaw, then x and y flipped
        val lx = -px / 16.0
        val lz = pz / 16.0
        val wx = lx * cos(r) + lz * sin(r)
        val wz = -lx * sin(r) + lz * cos(r)
        val up = if (lot >= 8) 0.3 else 0.0
        return Vec3(x + wx, y + up, z + wz)
    }

    // ---- A prop ----

    override fun isPickable(): Boolean = false
    override fun isPushable(): Boolean = false
    override fun canBeCollidedWith(entity: Entity?): Boolean = false
    override fun canBeHitByProjectile(): Boolean = false
    override fun isAttackable(): Boolean = false
    override fun skipAttackInteraction(attacker: Entity): Boolean = true
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false
    override fun ignoreExplosion(explosion: Explosion): Boolean = true
    override fun isIgnoringBlockTriggers(): Boolean = true
    override fun isPushedByFluid(): Boolean = false
    override fun canUsePortal(allowPassengers: Boolean): Boolean = false
    override fun push(x: Double, y: Double, z: Double) {}
    override fun shouldBeSaved(): Boolean = false
    override fun readAdditionalSaveData(input: ValueInput) {}
    override fun addAdditionalSaveData(output: ValueOutput) {}

    companion object {
        private val LOTS_A: EntityDataAccessor<Long> = SynchedEntityData.defineId(Wreck::class.java, EntityDataSerializers.LONG)
        private val LOTS_B: EntityDataAccessor<Long> = SynchedEntityData.defineId(Wreck::class.java, EntityDataSerializers.LONG)
        private val FLAGS: EntityDataAccessor<Int> = SynchedEntityData.defineId(Wreck::class.java, EntityDataSerializers.INT)

        /** It lies over to its left; the horse lies dead by it; it went over just now (its wheels still spin); the coat and the arrows. */
        const val FLAG_MIRROR = 1
        const val FLAG_HORSE = 2
        const val FLAG_FRESH = 4
        const val COAT = 4
        const val ARROWS = 8

        /** How many lots it shows at most (Part A's LOTS). */
        const val LOTS = 12

        /** WagonMesh.WRECK_LOTS: x, z (px, the wreck's space) and yaw of each place, in the order they fill. */
        val PLACES: List<FloatArray> = listOf(
            floatArrayOf(-38f, 24f, 20f), floatArrayOf(-55f, 19f, -30f), floatArrayOf(-72f, 4f, 50f), floatArrayOf(-74f, -16f, -15f),
            floatArrayOf(-66f, -34f, 35f), floatArrayOf(-24f, 30f, 70f), floatArrayOf(-56f, 35f, -60f), floatArrayOf(-84f, -4f, 10f),
        )

        internal fun pack(goods: List<Good?>, from: Int): Long {
            var out = 0L
            for (k in 0 until 8) {
                val g = goods.getOrNull(from + k) ?: continue
                out = out or ((g.ordinal + 1).toLong() shl (k * 8))
            }
            return out
        }

        internal fun good(bits: Long, k: Int): Good? {
            val v = ((bits shr (k * 8)) and 0xFF).toInt()
            return if (v == 0) null else Good.entries.getOrNull(v - 1)
        }
    }
}

/**
 * One lot of a [Wreck] (a crate, a sack) to take: an invisible box over it, so using it takes that one. Goes once
 * it's taken, or with its wreck.
 */
class WreckLot(type: EntityType<out WreckLot>, level: Level) : Entity(type, level) {
    /** Its wreck in the records, and which of its lots it is. */
    var wreck = -1L
    var lot = -1

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {}

    override fun tick() {
        if (!level().isClientSide && !Bodies.owns(this)) discard()
    }

    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        val level = level()
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        WreckShows.take(level, player, this)
        return InteractionResult.SUCCESS
    }

    override fun isPickable(): Boolean = !isRemoved
    override fun isPushable(): Boolean = false
    override fun canBeCollidedWith(entity: Entity?): Boolean = false
    override fun canBeHitByProjectile(): Boolean = false
    override fun isAttackable(): Boolean = false
    override fun skipAttackInteraction(attacker: Entity): Boolean = true
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false
    override fun ignoreExplosion(explosion: Explosion): Boolean = true
    override fun isIgnoringBlockTriggers(): Boolean = true
    override fun isPushedByFluid(): Boolean = false
    override fun canUsePortal(allowPassengers: Boolean): Boolean = false
    override fun push(x: Double, y: Double, z: Double) {}
    override fun shouldBeSaved(): Boolean = false
    override fun readAdditionalSaveData(input: ValueInput) {}
    override fun addAdditionalSaveData(output: ValueOutput) {}
}

/**
 * A broken band's loot pile by its camp (design-road.md section 3): marked crates and sacks under a tarp, roped
 * down. Using it takes the next of its lots (marked as off the wagons they were taken from); it shows fewer as they
 * go, the tarp coming off with the first.
 */
class LootPile(type: EntityType<out LootPile>, level: Level) : Entity(type, level) {
    /** How many of its six pieces still show (the last gone, the pile with it). */
    val pieces: Int get() = entityData.get(PIECES).toInt()

    /** The pile in the records it stands for. */
    var pile = -1L

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(PIECES, 6)
    }

    internal fun pieces(n: Int) = entityData.set(PIECES, n.coerceIn(0, 6).toByte())

    override fun tick() {
        if (!level().isClientSide && !Bodies.owns(this)) discard()
    }

    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        val level = level()
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        WreckShows.takePile(level, player, this)
        return InteractionResult.SUCCESS
    }

    override fun isPickable(): Boolean = !isRemoved
    override fun isPushable(): Boolean = false
    override fun canBeCollidedWith(entity: Entity?): Boolean = false
    override fun canBeHitByProjectile(): Boolean = false
    override fun isAttackable(): Boolean = false
    override fun skipAttackInteraction(attacker: Entity): Boolean = true
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false
    override fun ignoreExplosion(explosion: Explosion): Boolean = true
    override fun isIgnoringBlockTriggers(): Boolean = true
    override fun isPushedByFluid(): Boolean = false
    override fun canUsePortal(allowPassengers: Boolean): Boolean = false
    override fun push(x: Double, y: Double, z: Double) {}
    override fun shouldBeSaved(): Boolean = false
    override fun readAdditionalSaveData(input: ValueInput) {}
    override fun addAdditionalSaveData(output: ValueOutput) {}

    companion object {
        private val PIECES: EntityDataAccessor<Byte> = SynchedEntityData.defineId(LootPile::class.java, EntityDataSerializers.BYTE)
    }
}

/**
 * The wrecks and loot piles in the world while a player is near one (Part A keeps them in its records): each one's
 * body made when someone comes within [NEAR] of it and gone when nobody's been within [FAR] for a while, its lots
 * kept as the records have them, and taking a lot handed to the records ([Caravans.take]).
 */
internal object WreckShows {
    private class Shown(val id: Long, val pile: Boolean, val wreck: Wreck?, val heap: LootPile?, val lots: MutableList<WreckLot?>) {
        var unseen = 0
    }

    private val shown = HashMap<Long, Shown>()

    /** How near a player brings a wreck's body, and how far off it goes again (after [LINGER] ticks). */
    private const val NEAR = 96.0
    private const val FAR = 128.0
    private const val LINGER = 200

    /** Ticks a wreck made by a wagon going over counts as just gone over. */
    private const val FRESH_TICKS = 160

    /** Every 20 ticks, from Bodies.scan: the wrecks and piles near [players]. */
    fun scan(level: ServerLevel, players: List<ServerPlayer>) {
        val seen = HashSet<Long>()
        for (player in players) {
            val pos = player.blockPosition()
            for (w in Caravans.wrecks(level, pos, NEAR)) {
                seen.add(w.id)
                val s = shown[w.id] ?: (if (Bodies.wreckHeld(level, w.id)) null else make(level, w, pile = false)) ?: continue
                sync(level, s, w)
            }
            for (w in Caravans.piles(level, pos, NEAR)) {
                seen.add(w.id)
                val s = shown[w.id] ?: make(level, w, pile = true) ?: continue
                sync(level, s, w)
            }
        }
        // Gone from the records, or nobody near for a while
        for (s in shown.values.toList()) {
            val at = s.wreck?.position() ?: s.heap?.position() ?: Vec3.ZERO
            val near = players.any { flat(it.position(), at) <= FAR }
            if (s.id !in seen) {
                if (!near || !stillThere(level, s)) drop(s)
                continue
            }
            s.unseen = if (near) 0 else s.unseen + 20
            if (s.unseen >= LINGER || s.wreck?.isRemoved == true || s.heap?.isRemoved == true) drop(s)
        }
    }

    private fun stillThere(level: ServerLevel, s: Shown): Boolean {
        val at = s.wreck?.blockPosition() ?: s.heap?.blockPosition() ?: return false
        return (if (s.pile) Caravans.piles(level, at, 16.0) else Caravans.wrecks(level, at, 16.0)).any { it.id == s.id }
    }

    /** The wreck the bodies just made by tipping a wagon over (Waylay): kept as any, its lots from the records. */
    fun adopt(level: ServerLevel, id: Long, wreck: Wreck) {
        shown[id]?.let { drop(it) }
        val s = Shown(id, false, wreck, null, MutableList(Wreck.LOTS) { null })
        shown[id] = s
        Caravans.wrecks(level, wreck.blockPosition(), 24.0).firstOrNull { it.id == id }?.let { sync(level, s, it) }
    }

    /** Whether [entity] is one of the wrecks', piles' or lots' bodies. */
    fun owns(entity: Entity): Boolean = when (entity) {
        is Wreck -> shown[entity.wreck]?.wreck === entity
        is LootPile -> shown[entity.pile]?.heap === entity
        is WreckLot -> shown[entity.wreck]?.lots?.getOrNull(entity.lot) === entity
        else -> false
    }

    /** The wreck [id] has been cleared away (Caravans' listener). */
    fun cleared(id: Long) {
        shown[id]?.let { drop(it) }
    }

    fun clear() {
        for (s in shown.values.toList()) drop(s)
        shown.clear()
    }

    /** A line on what's out, for the commands. */
    fun summary(): String = "${shown.values.count { !it.pile }} wreck(s) and ${shown.values.count { it.pile }} loot pile(s) shown."

    private fun drop(s: Shown) {
        shown.remove(s.id)
        s.wreck?.discard()
        s.heap?.discard()
        for (l in s.lots) l?.discard()
    }

    /** A body for [w] where the records have it, if its place is loaded and ticking. */
    private fun make(level: ServerLevel, w: Caravans.Wreck, pile: Boolean): Shown? {
        if (!level.isPositionEntityTicking(BlockPos.containing(w.pos))) return null
        if (pile) {
            val heap = Bodies.LOOT_PILE.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT) ?: return null
            heap.pile = w.id
            val y = Ground.stand(level, w.pos.x, w.pos.z, w.pos.y)
            heap.snapTo(w.pos.x, y, w.pos.z, w.yaw, 0f)
            val s = Shown(w.id, true, null, heap, MutableList(Wreck.LOTS) { null })
            shown[w.id] = s
            if (!level.addFreshEntity(heap)) {
                shown.remove(w.id)
                return null
            }
            return s
        }
        val wreck = Bodies.WRECK.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT) ?: return null
        wreck.wreck = w.id
        val pose = Bodies.wreckPose(level, w)
        val y = Ground.stand(level, pose.at.x, pose.at.z, w.pos.y)
        wreck.snapTo(pose.at.x, y, pose.at.z, pose.yaw, 0f)
        var flags = if (pose.mirror) Wreck.FLAG_MIRROR else 0
        if (w.horseDead) flags = flags or Wreck.FLAG_HORSE or (pose.coat shl Wreck.COAT)
        // The band's arrows, by the wreck's own number: none to see if it went over onto the side they went into
        if (!pose.arrowsUnder) flags = flags or ((Math.floorMod(w.id * 2654435761L, 64L).toInt() or 1) shl Wreck.ARROWS)
        wreck.set(w.lots.map { it?.first }, flags)
        val s = Shown(w.id, false, wreck, null, MutableList(Wreck.LOTS) { null })
        shown[w.id] = s
        if (!level.addFreshEntity(wreck)) {
            shown.remove(w.id)
            return null
        }
        return s
    }

    /** [s] shows what's left of [w]: each lot there or gone, a box over each one there to take it by. */
    private fun sync(level: ServerLevel, s: Shown, w: Caravans.Wreck) {
        val wreck = s.wreck
        if (wreck != null) {
            // Only just gone over (its wheels in the air spin on) for whoever sees it go, not for someone come upon it later
            if (wreck.has(Wreck.FLAG_FRESH) && wreck.tickCount > FRESH_TICKS) wreck.flag(Wreck.FLAG_FRESH, false)
            val goods = (0 until Wreck.LOTS).map { w.lots.getOrNull(it)?.first }
            wreck.lots(goods)
            for (k in 0 until Wreck.LOTS) {
                val has = goods[k] != null && Caravans.salvage()
                val box = s.lots[k]
                if (has && (box == null || box.isRemoved)) {
                    val made = Bodies.WRECK_LOT.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT) ?: continue
                    made.wreck = s.id
                    made.lot = k
                    val at = wreck.lotAt(k)
                    val y = Ground.stand(level, at.x, at.z, wreck.y + 0.5) + (if (k >= 8) 0.3 else 0.0)
                    made.snapTo(at.x, y, at.z, 0f, 0f)
                    s.lots[k] = made
                    if (!level.addFreshEntity(made)) s.lots[k] = null
                } else if (!has && box != null) {
                    box.discard()
                    s.lots[k] = null
                }
            }
        }
        val heap = s.heap
        if (heap != null) {
            val total = w.lots.size.coerceAtLeast(1)
            val left = w.lots.count { it != null }
            heap.pieces(if (left == 0) 0 else Math.round(6.0 * left / total).toInt().coerceIn(1, 6))
            if (left == 0) drop(s)
        }
    }

    /** [player] used [box]: its lot, if it's still there, into their hands. */
    fun take(level: ServerLevel, player: ServerPlayer, box: WreckLot) {
        val s = shown[box.wreck] ?: return
        val wreck = s.wreck ?: return
        val stack = Caravans.take(player, s.id, box.lot)
        if (stack.isEmpty) {
            Caravans.wrecks(level, wreck.blockPosition(), 24.0).firstOrNull { it.id == s.id }?.let { sync(level, s, it) }
            return
        }
        give(level, player, stack, box.position())
        Caravans.wrecks(level, wreck.blockPosition(), 24.0).firstOrNull { it.id == s.id }?.let { sync(level, s, it) }
            ?: run {
                box.discard()
                s.lots[box.lot] = null
            }
    }

    /** [player] used [heap]: the next of its lots into their hands. */
    fun takePile(level: ServerLevel, player: ServerPlayer, heap: LootPile) {
        val s = shown[heap.pile] ?: return
        val w = Caravans.piles(level, heap.blockPosition(), 16.0).firstOrNull { it.id == s.id } ?: return drop(s)
        val k = w.lots.indexOfFirst { it != null }
        if (k < 0) return drop(s)
        val stack = Caravans.take(player, s.id, k)
        if (stack.isEmpty) return
        give(level, player, stack, heap.position().add(0.0, 0.5, 0.0))
        Caravans.piles(level, heap.blockPosition(), 16.0).firstOrNull { it.id == s.id }?.let { sync(level, s, it) } ?: drop(s)
    }

    /** [stack] into [player]'s hands from [from]: a crate lifted, a sack dragged clear. */
    private fun give(level: ServerLevel, player: ServerPlayer, stack: ItemStack, from: Vec3) {
        level.playSound(null, from.x, from.y, from.z, Bodies.sound("crate_lift"), SoundSource.NEUTRAL, 0.7f, 0.9f + level.random.nextFloat() * 0.2f)
        level.sendParticles(ItemParticleOption(ParticleTypes.ITEM, stack.item), from.x, from.y + 0.3, from.z, 6, 0.2, 0.1, 0.2, 0.05)
        player.inventory.placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY)
        level.playSound(null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3f, 1.2f)
    }
}
