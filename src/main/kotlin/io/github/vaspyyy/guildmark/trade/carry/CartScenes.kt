package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.cart.PlayerCart
import io.github.vaspyyy.guildmark.economy.Serving
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.trade.Rung
import io.github.vaspyyy.guildmark.trade.Rungs
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import io.github.vaspyyy.guildmark.trade.Ware
import io.github.vaspyyy.guildmark.trade.Wares
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Chapman's Cart at the hall's counter (living-world/trader/design.md, Part 3, "A buying scene, not a menu"; build part D2): the ware that
 * sells it, and the scene the hall's merchant and the town's carter play behind the sale.
 *
 * The cart is a Chapman's (the rung is the gate) and costs [PRICE] Marks. The sale is made before any scene starts, and the cart is the player's
 * from that moment: it is a [PlayerCart] of theirs in the yard behind the hall with the town's carter at its shafts (out of the player's sight if
 * there is any such place within reach, as the donkey's yard is chosen), the merchant points toward it and says her line, and the carter sets off
 * with the cart rolling after him as the carters' own do ([Handcart.trail] with a carter for its puller; a carter's hands carry it through a
 * wall that its swing cannot clear, a player's do not), comes up to the player, lets go of the shafts so that it tips onto them, says his line,
 * touches his hat and goes back the way he came. If any of it cannot run, or stops half way, the cart is set down beside the player ready to take
 * up: [Delivery.finish] always ends with the carter gone, the cart not in his hands, and the cart near the player. With no way round to bring it by,
 * it is set down beside them at once; with no room for it anywhere, it is given as the item. Nothing is saved: a carter found in a chunk as it
 * loads goes at once ([CounterScenes.TAG]).
 */
object CartScenes {
    /** The ware's id: its words are `ware.guildmark.chapmans_cart`, `.line` and `.tip`; its icon is the item of the same name. */
    const val ID = "chapmans_cart"

    /** What it costs, in Marks (design Part 3, "Rules and numbers"). */
    const val PRICE = 60

    /** The lang key of why it is not for sale to someone who has not climbed the ladder far enough. */
    const val GATE_CHAPMAN = "gui.guildmark.trade.carriers.gate.chapman"

    private val LOOKS = intArrayOf(0, 1, 2, 4, 6, 7)

    /** How far from the counter the yard is looked for, and the nearest it may be. */
    private const val YARD_FAR = 18
    private const val YARD_NEAR = 9

    /** The longest a delivery may take, in ticks. */
    private const val TIMEOUT = 1300

    /** Ticks the merchant's pointing takes before the carter sets off, and the carter's hand-over takes. */
    private const val WAIT = 40
    private const val HANDING = 32

    /** How near the carter comes to the player, and the carter's pace (a little under a walk, with a cart behind). */
    private const val CLOSE = 3.4
    private const val PACE = 0.5

    /** The longest the carter goes without getting nearer the player, and the longest he is on his way back (ticks). */
    private const val STUCK = 160
    private const val LEAVE_MOST = 240

    /** How far the cart may be left from a player before it is brought to them. */
    private const val NEAR_ENOUGH = 8f

    private val deliveries = ArrayList<Delivery>()

    /** Puts the cart on the hall's counter and hooks the scene's ticking (once, at setup). */
    fun register() {
        Wares.add(Ware(ID, PRICE, gate = ::gate, action = ::buy, scene = ::scene))
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { onLogout(it) }
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent::class.java) { stopping(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> deliveries.clear() }
    }

    /** What stands between [player] and the cart: carrying switched off, or a rung not yet climbed; null if nothing does. */
    private fun gate(player: ServerPlayer): String? = when {
        !TradeConfig.carry() -> CarryShop.GATE_OFF
        !Rungs.reached(player, Rung.CHAPMAN) -> GATE_CHAPMAN
        else -> null
    }

    // ---- The sale ----

    /**
     * Makes the cart [player] has bought: in the yard with the carter at its shafts if there is a way round to the counter, else set down beside
     * them, else (nowhere to put it) given as an item. Always true: there is always a cart.
     */
    private fun buy(player: ServerPlayer): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val merchant = CarryShop.counter(player)
        val yard = if (merchant != null) yardFor(level, merchant, player) else null
        if (yard != null) {
            val delivery = try {
                Delivery.start(level, player, yard)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The carter's cart could not be set up", e)
                null
            }
            if (delivery != null) {
                deliveries.add(delivery)
                return true
            }
        }
        val cart = setDownBeside(level, player)
        if (cart != null) {
            level.sendParticles(ParticleTypes.CLOUD, cart.x, cart.y + 0.5, cart.z, 6, 0.3, 0.3, 0.3, 0.01)
            return true
        }
        // Nowhere to stand one: it comes as the item, to be set down where there is room
        val stack = ChapmansCartItem.stack(PlayerCart.colourFor(player.uuid))
        if (!player.inventory.add(stack)) player.spawnAtLocation(level, stack)
        return true
    }

    /** A cart of [player]'s set down a few paces from them, its shafts toward them, or null if there is no room. */
    private fun setDownBeside(level: ServerLevel, player: ServerPlayer): PlayerCart? {
        for (k in 0..6) for (side in if (k == 0) intArrayOf(1) else intArrayOf(1, -1)) {
            val a = Math.toRadians(player.yRot + 180.0 + 35.0 * k * side)
            val at = Vec3(player.x - sin(a) * 2.6, player.y, player.z + cos(a) * 2.6)
            CartHitch.setDown(level, player, at, player.position(), null)?.let { return it }
        }
        return null
    }

    /** The merchant's part, with [merchant] at the counter: she points toward the yard and says her line. */
    private fun scene(player: ServerPlayer, merchant: Townsperson) {
        val serving = merchant.director as? Serving ?: return
        val line = "trade_sell_cart_${merchant.random.nextInt(3)}"
        val delivery = deliveries.lastOrNull { it.player == player.uuid && it.merchant == null }
        if (delivery == null) {
            // The cart is beside the player already: a word and a nod will do
            serving.gesture(merchant, Activity.NOD, line)
            return
        }
        delivery.merchant = merchant
        serving.gesture(merchant, Activity.POINT, line)
    }

    /**
     * A place for the carter to bring the cart from: out in the open and 9 to 18 blocks from the merchant, with a way from there to the counter,
     * hidden from [player]'s eyes if any such place is among those looked at. Null if none has a way.
     */
    private fun yardFor(level: ServerLevel, merchant: Townsperson, player: ServerPlayer): BlockPos? {
        val eye = player.eyePosition
        var best: BlockPos? = null
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(8) {
            val pos = Places.street(level, merchant.blockPosition(), YARD_FAR, level.random, YARD_NEAR, 4) ?: return@repeat
            val path = merchant.navigation.createPath(pos, 1)
            if (path == null || !path.canReach()) return@repeat
            val centre = Vec3.atCenterOf(pos)
            val seen = level.clip(ClipContext(eye, centre.add(0.0, 1.0, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS
            val distance = Math.sqrt(player.distanceToSqr(centre))
            val score = (if (seen) 0.0 else 100.0) - abs(distance - 14.0)
            if (score > bestScore) {
                bestScore = score
                best = pos
            }
        }
        return best
    }

    // ---- Ticking ----

    private fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (deliveries.isEmpty()) return
        for (delivery in deliveries.filter { it.player == player.uuid }) {
            delivery.cleanup(player)
            deliveries.remove(delivery)
        }
    }

    /** The server is stopping: every delivery is put right while the players are still there, so no carter is saved holding a cart. */
    private fun stopping(server: MinecraftServer) {
        for (delivery in deliveries.toList()) delivery.cleanup(server.playerList.getPlayer(delivery.player))
        deliveries.clear()
    }

    private fun tick(server: MinecraftServer) {
        if (deliveries.isEmpty()) return
        deliveries.removeAll { delivery ->
            try {
                !delivery.tick(server)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The carter's delivery of a cart failed", e)
                delivery.cleanup(server.playerList.getPlayer(delivery.player))
                true
            }
        }
    }

    /** The carter's day while he is bringing a cart: nothing of his own, the delivery drives him. */
    private object Hauling : Director {
        override fun tick(p: Townsperson) {
            // A one-shot (the hat, the nod) settles back
            if (p.actDone) p.act(Activity.NONE)
        }

        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            p.lookAt(player)
            p.attend(player, 40)
            p.act(Activity.NOD_WALK)
            return true
        }
    }

    /** One cart being brought round by one carter to one player. */
    private class Delivery(
        val player: UUID,
        private val cart: PlayerCart,
        private val carter: Townsperson,
        private val yard: BlockPos,
    ) {
        /** The merchant at the counter, once the scene is told of her. */
        var merchant: Townsperson? = null

        private enum class Stage { WAIT, COMING, HAND_OVER, LEAVING }

        private val walker = Walker(carter)
        private var stage = Stage.WAIT
        private var age = 0
        private var stageAge = 0
        private var best = Double.MAX_VALUE
        private var bestAt = 0
        private var gone = false

        companion object {
            /** Makes the carter and the cart in the yard at [yard], the shafts in his hands. Null if either could not be made. */
            fun start(level: ServerLevel, owner: ServerPlayer, yard: BlockPos): Delivery? {
                val toward = Vec3.atCenterOf(yard)
                val yaw = (atan2(owner.z - toward.z, owner.x - toward.x) * (180.0 / Math.PI)).toFloat() - 90f
                val carter = Townsfolk.create(level, yard, TownRole.CITIZEN, LOOKS[level.random.nextInt(LOOKS.size)], yaw, EntitySpawnReason.EVENT) ?: return null
                carter.setPermanentlyInvulnerable(true)
                // Nothing can hurt him, so he does not drop the shafts and run from a passing monster
                carter.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
                carter.addTag(CounterScenes.TAG)
                carter.setDropChance(EquipmentSlot.MAINHAND, 0f)
                carter.setDropChance(EquipmentSlot.OFFHAND, 0f)
                carter.director = Hauling
                // The cart straight behind him, the shafts in his hands
                val back = Handcart.GRIP_FLAT
                val a = Math.toRadians(yaw.toDouble())
                val at = Vec3(carter.x + sin(a) * back, carter.y, carter.z - cos(a) * back)
                val cart = CartHitch.setDown(level, owner, at, carter.position(), null)
                if (cart == null) {
                    carter.discard()
                    return null
                }
                try {
                    if (!level.addFreshEntity(carter)) {
                        cart.discard()
                        return null
                    }
                    CartHitch.handTo(cart, carter)
                    carter.act(Handcarts.PULL)
                    level.sendParticles(ParticleTypes.POOF, carter.x, carter.y + 0.6, carter.z, 6, 0.3, 0.4, 0.3, 0.02)
                    return Delivery(owner.uuid, cart, carter, yard)
                } catch (e: RuntimeException) {
                    carter.discard()
                    cart.discard()
                    throw e
                }
            }
        }

        /** One tick of the delivery; false once it is over (and put right). */
        fun tick(server: MinecraftServer): Boolean {
            val level = carter.level() as? ServerLevel
            val owner = server.playerList.getPlayer(player)
            if (level == null || owner == null || owner.level() !== level) {
                finish(owner)
                return false
            }
            val carterLost = !gone && (carter.isRemoved || !carter.isAlive)
            if (carterLost || cart.isRemoved || ++age > TIMEOUT) {
                finish(owner)
                return false
            }
            stageAge++
            when (stage) {
                Stage.WAIT -> if (stageAge >= WAIT) {
                    level.playSound(null, carter.x, carter.y + 0.6, carter.z, TradeSounds.get("trade.shafts_lift"), SoundSource.NEUTRAL, 0.7f, 1.0f)
                    next(Stage.COMING)
                }
                Stage.COMING -> if (!coming(owner)) {
                    finish(owner)
                    return false
                }
                Stage.HAND_OVER -> handOver(owner)
                Stage.LEAVING -> leaving(owner)
            }
            if (gone) {
                finish(owner)
                return false
            }
            return true
        }

        /** Round to the player with the cart behind him. False if the carter is getting nowhere, or the cart has been let go. */
        private fun coming(owner: ServerPlayer): Boolean {
            if (!cart.held) return false
            if (carter.activity != Handcarts.PULL) carter.act(Handcarts.PULL)
            val toPlayer = carter.distanceTo(owner).toDouble()
            if (toPlayer <= CLOSE) {
                walker.stop()
                next(Stage.HAND_OVER)
                return true
            }
            val goal = owner.blockPosition()
            val at = walker.target
            if (at == null || at.distSqr(goal) > 9.0) walker.go(goal, PACE, CLOSE - 0.6)
            if (walker.tick() == Walker.State.FAILED) return false
            if (toPlayer < best - 1.0) {
                best = toPlayer
                bestAt = age
            }
            return age - bestAt < STUCK
        }

        /** He turns to the player, lets go of the shafts so that the cart tips onto them, and says his line. */
        private fun handOver(owner: ServerPlayer) {
            carter.navigation.stop()
            carter.lookControl.setLookAt(owner, 30f, 30f)
            carter.face(Mth.approachDegrees(carter.yBodyRot, carter.yawTo(owner.position()), 24f))
            if (stageAge == 1) {
                (carter.level() as? ServerLevel)?.let { CartHitch.letGo(cart, it) }
                carter.act(Activity.NOD)
                carter.say("trade_cartman_${carter.random.nextInt(2)}", force = true)
            }
            if (stageAge >= HANDING) next(Stage.LEAVING)
        }

        /** He touches his hat and goes back the way he came, and is gone once he is out of the way. */
        private fun leaving(owner: ServerPlayer) {
            if (gone) return
            if (stageAge == 1) carter.act(Activity.TIP_HAT)
            val hat = Activity.TIP_HAT.ticks
            if (stageAge == hat + 1) walker.go(yard, PACE * 1.5, 1.5)
            val arrived = stageAge > hat + 1 && walker.tick() != Walker.State.WALKING
            val away = carter.distanceTo(owner)
            if (arrived || away > 24f || away > 8f && !owner.hasLineOfSight(carter) || stageAge > LEAVE_MOST) vanish()
        }

        private fun next(to: Stage) {
            stage = to
            stageAge = 0
        }

        /** The carter is gone in a puff. */
        private fun vanish() {
            if (gone) return
            gone = true
            if (carter.isRemoved) return
            (carter.level() as? ServerLevel)?.sendParticles(ParticleTypes.POOF, carter.x, carter.y + 0.6, carter.z, 8, 0.3, 0.4, 0.3, 0.02)
            carter.carried = ItemStack.EMPTY
            carter.director = null
            carter.discard()
        }

        /**
         * Puts everything right: the shafts out of his hands (the cart tipped onto them), the carter gone, and, if the cart is far from [owner]
         * (it is theirs and must not be left in a yard), the cart beside them as the carriers' recall would have it.
         */
        fun finish(owner: ServerPlayer?) {
            if (!cart.isRemoved) {
                (cart.level() as? ServerLevel)?.let { level ->
                    if (cart.held) CartHitch.letGo(cart, level)
                    if (owner != null && owner.isAlive && cart.level() === owner.level() && cart.distanceTo(owner) > NEAR_ENOUGH) CartHitch.bring(cart, owner)
                }
            }
            vanish()
        }

        /** [finish] for a delivery that is stopped from outside (the player leaves, the server stops, something went wrong). */
        fun cleanup(owner: ServerPlayer?) {
            finish(owner)
        }
    }
}
