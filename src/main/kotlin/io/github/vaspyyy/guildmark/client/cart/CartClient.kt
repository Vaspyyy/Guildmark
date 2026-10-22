package io.github.vaspyyy.guildmark.client.cart

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.client.townsfolk.LivingSchedule
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderState
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderer
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The client half of the handcarts: their renderer, what each client works out for itself to draw them (the
 * shafts coming up into the hands or down onto the ground, the wheels rolling by how far each went, the load
 * settling on and lifting off), and the puller turned to their cart, the shafts behind them. A household's bundle
 * heaved up onto the bed from the tail (PACK) passes from the packer's hands to the cart as the move sets it down.
 */
object CartClient {
    /** Which cart each puller is drawn turned to, by their entity id, while the shafts are in (or leaving) their hands. */
    private val pulled = Int2ObjectOpenHashMap<Handcart>()

    /** The bundle each packer has just heaved up onto a cart (PACK), by their entity id: the one in their hands goes. */
    private val packed = Int2ObjectOpenHashMap<Packed>()

    /** Registers the renderer, the cart's model layers and the puller's turn; called once from the client's setup. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(Handcarts.HANDCART.get(), ::HandcartRenderer)
            event.registerEntityRenderer(Handcarts.PLAYER_CART.get(), ::HandcartRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(HandcartModel.LAYER, HandcartMesh::cart)
            event.registerLayerDefinition(HandcartModel.LOAD, HandcartMesh::load)
        }
        modBus.addListener(RegisterRenderStateModifiersEvent::class.java) { event ->
            event.registerEntityModifier<Townsperson, TownsfolkRenderState>(TownsfolkRenderer::class.java) { p, state ->
                turn(p, state)
                packing(p, state)
            }
        }
        Handcart.clientTick = ::tick
        Handcart.clientRemoved = ::removed
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            pulled.clear()
            packed.clear()
        }
    }

    /** What this client keeps to draw [cart]. */
    fun look(cart: Handcart): Look = cart.look as? Look ?: Look(cart.held).also { cart.look = it }

    /**
     * Which of [HandcartMesh.PIECES] shows [stack]: a sack of grain or of flour, a crate, a basket of loaves, a box of
     * fish; or a household's chest, cooking pot, bundle, hen in her coop, rolled mattress or chair.
     */
    fun piece(stack: ItemStack): Int = when {
        // A household's goods (Handcarts.household), each as itself
        stack.`is`(Items.CHEST) -> CHEST
        stack.`is`(Items.DECORATED_POT) -> POT
        stack.`is`(Items.BUNDLE) -> BUNDLE
        stack.`is`(Items.IRON_BARS) -> CAGE
        stack.`is`(Items.BED.pick(DyeColor.WHITE)) -> MATTRESS
        stack.`is`(Items.OAK_STAIRS) -> CHAIR
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
        if (!packed.isEmpty()) {
            val now = cart.level().gameTime
            packed.values.removeIf { it.cart.isRemoved || now - it.at > PACKED_LINGER }
        }
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
                // A bundle heaved up onto the bed (PACK) lies as the move set it down: no settling, and heard as it's set
                if (!first && now.`is`(Items.BUNDLE) && packedBy(cart, s)) {
                    look.since[s] = SETTLE
                    bundle(cart)
                }
            } else if (look.since[s] < SETTLE) {
                look.since[s]++
                // A household's things, heard as each comes down on the bed
                if (look.since[s] == SETTLE - 1 && Handcarts.household(look.shown[s])) bundle(cart)
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
        rattle(cart, look)
    }

    private fun removed(cart: Handcart) {
        val look = cart.look as? Look ?: return
        if (look.puller >= 0 && pulled.get(look.puller) === cart) pulled.remove(look.puller)
        (look.rattle as? Rattle)?.end()
        look.rattle = null
    }

    // ---- A household's load, heard ----

    /** A bundle set down on [cart] (streets.bundle, heard 16 blocks off). */
    private fun bundle(cart: Handcart) {
        val level = cart.level()
        level.playLocalSound(cart.x, cart.y + 0.9, cart.z, SET_DOWN, SoundSource.NEUTRAL, 0.7f, 0.92f + level.random.nextFloat() * 0.16f, false)
    }

    /**
     * A household's load rattling on [cart] as it rolls (streets.handcart_goods, a loop heard 24 blocks off): faster
     * and higher the faster it goes, fading out as it stops; begun as it sets off, and let go once it has stood a while.
     */
    private fun rattle(cart: Handcart, look: Look) {
        var loaded = false
        for (s in 0 until Handcart.SLOTS) if (!look.shown[s].isEmpty && Handcarts.household(look.shown[s])) loaded = true
        val speed = if (loaded) hypot(cart.x - cart.xo, cart.z - cart.zo) * 20.0 else 0.0
        var loop = look.rattle as? Rattle
        if (loop == null || loop.isStopped) {
            if (speed < RATTLE_FROM) return
            loop = Rattle(cart)
            look.rattle = loop
            Minecraft.getInstance().soundManager.play(loop)
        }
        loop.speed = speed
    }

    // ---- A bundle heaved up onto the cart from its tail (PACK) ----

    /**
     * Whether a bundle just come onto [cart]'s place [slot] was heaved up there by someone at its tail, at their move's
     * set (Comings plays it so the move sets the bundle down just where the cart has it): if so the cart's own lies
     * there from now on, and the one in their hands goes ([packing]).
     */
    private fun packedBy(cart: Handcart, slot: Int): Boolean {
        val level = cart.level()
        var by: Townsperson? = null
        var best = Double.MAX_VALUE
        for (p in level.getEntitiesOfClass(Townsperson::class.java, cart.boundingBox.inflate(PACK_REACH))) {
            if (p.shownActivity != Activity.PACK) continue
            val into = p.tickCount - p.shownStart
            if (into < PACK_SET - PACK_EARLY || into > PACK_SET + PACK_LATE) continue
            val d = p.distanceToSqr(cart)
            if (d < best) {
                by = p
                best = d
            }
        }
        if (by == null) return false
        packed.put(by.id, Packed(cart, slot, level.gameTime))
        return true
    }

    /** [p] heaving a bundle up onto a cart (PACK): once the cart shows it in its place, the one in their hands goes. */
    private fun packing(p: Townsperson, state: TownsfolkRenderState) {
        if (packed.isEmpty()) return
        val set = packed.get(p.id) ?: return
        if (p.shownActivity != Activity.PACK || set.cart.isRemoved) {
            packed.remove(p.id)
            return
        }
        if (look(set.cart).shown[set.slot].`is`(Items.BUNDLE)) state.living.shown.remove(PACK_BUNDLE)
    }

    /** A bundle [cart] shows on place [slot], heaved up there at the game time [at]. */
    private class Packed(val cart: Handcart, val slot: Int, val at: Long)

    /** The rattle of a household's load on one cart. */
    private class Rattle(private val cart: Handcart) : AbstractTickableSoundInstance(GOODS, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        /** Blocks a second the cart is rolling. */
        var speed = 0.0
        private var still = 0

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0f
            x = cart.x
            y = cart.y + 0.6
            z = cart.z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (cart.isRemoved) return stop()
            x = cart.x
            y = cart.y + 0.6
            z = cart.z
            val target = (speed / RATTLE_FULL).toFloat().coerceIn(0f, 1f) * RATTLE_VOLUME
            volume += (target - volume) * if (target > volume) 0.25f else 0.12f
            if (volume < 0.003f && target <= 0f) volume = 0f
            pitch = (speed / RATTLE_PITCH).toFloat().coerceIn(0.8f, 1.25f)
            still = if (volume <= 0f) still + 1 else 0
            if (still > RATTLE_LINGER) stop()
        }

        fun end() = stop()
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

        /** A household's load rattling as it rolls, while it's heard. */
        var rattle: Any? = null
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
    /** A household's chest (Handcarts.CHEST); and the rest of a move: its cooking pot, a bundle, the hen in her coop, a rolled mattress, a chair. */
    const val CHEST = 5
    const val POT = 6
    const val BUNDLE = 7
    const val CAGE = 8
    const val MATTRESS = 9
    const val CHAIR = 10

    /** Ticks a piece takes to settle onto the bed. */
    const val SETTLE = 7
    /** Ticks a piece takes to be lifted off it. */
    const val LIFT = 8

    /** Ticks the shafts take to come up into the hands, or down onto the ground. */
    private const val LIFT_TICKS = 8f

    // A household's load: set down on the bed, and rattling as it rolls (blocks a second it starts at, is loudest at
    // and is at its own pitch at; how loud at most; ticks it lingers silent before it's let go)
    private val SET_DOWN: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "streets.bundle"))
    private val GOODS: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "streets.handcart_goods"))
    private const val RATTLE_FROM = 0.4
    private const val RATTLE_FULL = 1.6
    private const val RATTLE_PITCH = 2.05
    private const val RATTLE_VOLUME = 0.6f
    private const val RATTLE_LINGER = 40

    // A bundle heaved up from the tail (PACK, art/scripts/street_clips.py): its prop in the packer's hands; the move's set
    // (LivingSchedule.MOMENTS); how many ticks before or after the set the cart may show it (the two are sent apart);
    // how far from the cart the packer stands (blocks); ticks a packer is kept in mind at most
    private const val PACK_BUNDLE = "bundle"
    private val PACK_SET: Float = LivingSchedule.MOMENTS["pack"]?.firstOrNull { it.name == "set" }?.tick ?: 25f
    private const val PACK_EARLY = 4
    private const val PACK_LATE = 10
    private const val PACK_REACH = 2.5
    private const val PACKED_LINGER = 60L

    private const val ROLL_EASE = 0.35f
    /** Px a tick a piece on top settles down (or up). */
    private const val REST_SPEED = 0.9f
    /** Degrees a puller's head may stay turned from their body. */
    private const val HEAD_TURN = 70f
    private const val TAU = (Math.PI * 2).toFloat()
    private const val WRAP = 2000f
}
