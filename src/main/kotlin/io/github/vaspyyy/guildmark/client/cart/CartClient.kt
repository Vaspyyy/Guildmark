package io.github.vaspyyy.guildmark.client.cart

import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderState
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderer
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The client half of the handcarts: their renderer, what each client works out for itself to draw them (the
 * shafts coming up into the hands or down onto the ground, the wheels rolling by how far each went, the load
 * settling on and lifting off), and the puller turned to their cart, the shafts behind them.
 */
object CartClient {
    /** Which cart each puller is drawn turned to, by their entity id, while the shafts are in (or leaving) their hands. */
    private val pulled = Int2ObjectOpenHashMap<Handcart>()

    /** Registers the renderer, the cart's model layers and the puller's turn; called once from the client's setup. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(Handcarts.HANDCART.get(), ::HandcartRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(HandcartModel.LAYER, HandcartMesh::cart)
            event.registerLayerDefinition(HandcartModel.LOAD, HandcartMesh::load)
        }
        modBus.addListener(RegisterRenderStateModifiersEvent::class.java) { event ->
            event.registerEntityModifier<Townsperson, TownsfolkRenderState>(TownsfolkRenderer::class.java) { p, state -> turn(p, state) }
        }
        Handcart.clientTick = ::tick
        Handcart.clientRemoved = ::removed
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> pulled.clear() }
    }

    /** What this client keeps to draw [cart]. */
    fun look(cart: Handcart): Look = cart.look as? Look ?: Look(cart.held).also { cart.look = it }

    /** Which of [HandcartMesh.PIECES] shows [stack]: a sack of grain or of flour, a crate, a basket of loaves, a box of fish. */
    fun piece(stack: ItemStack): Int = when {
        stack.`is`(Items.SUGAR) -> FLOUR
        stack.`is`(Items.WHEAT) || stack.`is`(Items.WHEAT_SEEDS) || stack.`is`(Items.BEETROOT_SEEDS) || stack.`is`(Items.HAY_BLOCK) -> SACK
        stack.`is`(Items.BREAD) || stack.`is`(Items.COOKIE) || stack.`is`(Items.PUMPKIN_PIE) || stack.`is`(Items.CAKE) -> BASKET
        stack.`is`(ItemTags.FISHES) -> FISH
        else -> CRATE
    }

    /** Eases 0 to 1 in and out. */
    fun ease(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    // ---- Each tick ----

    private fun tick(cart: Handcart) {
        val look = look(cart)
        val first = look.fresh
        look.fresh = false
        // The shafts come up into the puller's hands, or down onto the ground
        look.liftO = look.lift
        look.lift = if (first) (if (cart.held) 1f else 0f) else Mth.approach(look.lift, if (cart.held) 1f else 0f, 1f / LIFT_TICKS)
        if (first) look.liftO = look.lift
        val who = when {
            cart.held -> cart.pullerId
            look.lift > 0f -> look.puller
            else -> -1
        }
        if (who != look.puller) {
            if (look.puller >= 0 && pulled.get(look.puller) === cart) pulled.remove(look.puller)
            if (who >= 0) pulled.put(who, cart)
            look.puller = who
        }
        // The wheels roll as far as each went: on a turn the outer one further
        look.spinRO = look.spinR
        look.spinLO = look.spinL
        if (first) {
            look.spinR = (cart.id * 1.7f) % TAU
            look.spinL = look.spinR + 0.9f
            look.spinRO = look.spinR
            look.spinLO = look.spinL
        } else {
            val a = Math.toRadians(cart.yRot.toDouble())
            val ahead = (cart.x - cart.xo) * -sin(a) + (cart.z - cart.zo) * cos(a)
            val turn = Math.toRadians(Mth.wrapDegrees(cart.yRot - cart.yRotO).toDouble())
            look.spinR += ((ahead - turn * Handcart.HALF_TRACK) / Handcart.AXLE_UP).toFloat()
            look.spinL += ((ahead + turn * Handcart.HALF_TRACK) / Handcart.AXLE_UP).toFloat()
            if (look.spinR > WRAP || look.spinR < -WRAP) {
                val k = TAU * Math.floor(look.spinR / TAU.toDouble()).toFloat()
                look.spinR -= k
                look.spinRO -= k
            }
            if (look.spinL > WRAP || look.spinL < -WRAP) {
                val k = TAU * Math.floor(look.spinL / TAU.toDouble()).toFloat()
                look.spinL -= k
                look.spinLO -= k
            }
        }
        // Leaning over, eased
        look.rollO = look.roll
        look.roll = if (first) cart.roll else look.roll + (cart.roll - look.roll) * ROLL_EASE
        if (first) look.rollO = look.roll
        // The load: what comes settles onto the bed, what goes is lifted off
        for (s in 0 until Handcart.SLOTS) {
            val now = cart.goods(s)
            if (!ItemStack.isSameItemSameComponents(now, look.shown[s])) {
                if (!look.shown[s].isEmpty && !first) {
                    look.leaving[s] = look.shown[s]
                    look.gone[s] = 0
                }
                look.shown[s] = now.copy()
                look.since[s] = if (first) SETTLE else 0
            } else if (look.since[s] < SETTLE) {
                look.since[s]++
            }
            if (!look.leaving[s].isEmpty && ++look.gone[s] > LIFT) look.leaving[s] = ItemStack.EMPTY
        }
        // A piece on top rests on whatever is under it, and settles down if that's taken away
        for (s in 0 until Handcart.SLOTS) {
            look.restO[s] = look.rest[s]
            var target = 0f
            for (k in HandcartMesh.SLOTS[s].under) {
                if (!look.shown[k].isEmpty) target = max(target, HandcartMesh.PIECES[piece(look.shown[k])].height)
            }
            look.rest[s] = if (first) target else Mth.approach(look.rest[s], target, REST_SPEED)
            if (first) look.restO[s] = look.rest[s]
        }
    }

    private fun removed(cart: Handcart) {
        val look = cart.look as? Look ?: return
        if (look.puller >= 0 && pulled.get(look.puller) === cart) pulled.remove(look.puller)
    }

    // ---- The puller, turned to the cart ----

    /**
     * Turns [p] (body, not head) to face away from the cart they're pulling, the shafts straight back from their
     * hands, however they're walking: blended in and out as the shafts come up and go down.
     */
    private fun turn(p: Townsperson, state: TownsfolkRenderState) {
        if (pulled.isEmpty()) return
        val cart = pulled.get(p.id) ?: return
        if (cart.isRemoved) return
        val look = cart.look as? Look ?: return
        val lift = ease(Mth.lerp(state.partialTick, look.liftO, look.lift))
        if (lift <= 0f) return
        val partial = state.partialTick.toDouble()
        val dx = state.x - Mth.lerp(partial, cart.xo, cart.x)
        val dz = state.z - Mth.lerp(partial, cart.zo, cart.z)
        if (dx * dx + dz * dz < 0.04) return
        val away = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val head = state.bodyRot + state.yRot
        val body = state.bodyRot + Mth.wrapDegrees(away - state.bodyRot) * lift
        state.bodyRot = body
        state.yRot = Mth.clamp(Mth.wrapDegrees(head - body), -HEAD_TURN, HEAD_TURN)
    }

    /** What a client keeps to draw one cart. */
    class Look(held: Boolean) {
        /** Not yet ticked: the first tick takes things as they are, nothing animates in. */
        var fresh = true

        /** 0 standing on its shafts, 1 in someone's hands; last tick's too. */
        var lift = if (held) 1f else 0f
        var liftO = lift

        /** The puller it's drawn turned to, or -1. */
        var puller = -1

        /** Radians the right wheel has turned, rolling forward; and last tick's. */
        var spinR = 0f
        var spinRO = 0f

        /** Radians the left wheel has turned; and last tick's. */
        var spinL = 0f
        var spinLO = 0f

        /** Degrees it leans, eased; and last tick's. */
        var roll = 0f
        var rollO = 0f

        /** How it stood last (yaw, pitch): the shafts come up from there, or go down to it. */
        var restYaw = 0f
        var restPitch = 0f

        /** How it was last held (yaw, pitch): the shafts' other end. */
        var heldYaw = 0f
        var heldPitch = 0f

        /** What's on each place on the bed. */
        val shown = Array<ItemStack>(Handcart.SLOTS) { ItemStack.EMPTY }

        /** Ticks since each was set down there. */
        val since = IntArray(Handcart.SLOTS) { SETTLE }

        /** What's being lifted off each place. */
        val leaving = Array<ItemStack>(Handcart.SLOTS) { ItemStack.EMPTY }

        /** Ticks since each was lifted. */
        val gone = IntArray(Handcart.SLOTS)

        /** Px a piece rests up off the floor (on what's under it), eased; and last tick's. */
        val rest = FloatArray(Handcart.SLOTS)
        val restO = FloatArray(Handcart.SLOTS)
    }

    /** A sack of grain: the first of HandcartMesh.PIECES. */
    const val SACK = 0
    /** A sack of flour. */
    const val FLOUR = 1
    /** A crate, its goods showing. */
    const val CRATE = 2
    /** A basket of loaves. */
    const val BASKET = 3
    /** A box of fish on ice. */
    const val FISH = 4

    /** Ticks a piece takes to settle onto the bed. */
    const val SETTLE = 7
    /** Ticks a piece takes to be lifted off it. */
    const val LIFT = 8

    /** Ticks the shafts take to come up into the hands, or down onto the ground. */
    private const val LIFT_TICKS = 8f
    private const val ROLL_EASE = 0.35f
    /** Px a tick a piece on top settles down (or up). */
    private const val REST_SPEED = 0.9f
    /** Degrees a puller's head may stay turned from their body. */
    private const val HEAD_TURN = 70f
    private const val TAU = (Math.PI * 2).toFloat()
    private const val WRAP = 2000f
}
