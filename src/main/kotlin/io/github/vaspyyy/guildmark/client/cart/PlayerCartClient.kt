package io.github.vaspyyy.guildmark.client.cart

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.cart.PlayerCart
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.network.CartReleasePayload
import io.github.vaspyyy.guildmark.network.CartTakePayload
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.context.ContextKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.common.asm.enumextension.EnumProxy
import net.neoforged.neoforge.client.IArmPoseTransformer
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Chapman's Cart, seen and heard (living-world/trader/design.md, Part 3; build part D2): the client's own entry for this part, beside
 * [io.github.vaspyyy.guildmark.client.GuildmarkClient] and [io.github.vaspyyy.guildmark.client.trade.CarryClient], so that the start-up code of the
 * client is left as it was. It registers the cart's renderer ([PlayerCartRenderer]) and its dressing's layer ([PlayerCartArt]), the arm pose of whoever
 * holds the shafts, and the two things the client asks of the server with an empty hand ([CartReleasePayload], [CartTakePayload]).
 */
@Mod(value = Guildmark.MOD_ID, dist = [Dist.CLIENT])
class PlayerCartClientMod(modBus: IEventBus) {
    init {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(Handcarts.PLAYER_CART.get(), ::PlayerCartRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(PlayerCartArt.LAYER) { PlayerCartArt.active().mesh() }
        }
        modBus.addListener(RegisterRenderStateModifiersEvent::class.java) { event ->
            event.registerAvatarEntityModifier(PlayerCartClient.AVATAR)
        }
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered::class.java, PlayerCartClient::onUse)
    }
}

/**
 * The arm pose of whoever holds a cart's shafts. Its transformer is [PlayerCartClient.pose]; kept apart so that the arm pose enum can build it without
 * loading the client's code (as [io.github.vaspyyy.guildmark.client.combat.CombatArmPose] is).
 */
object CartArmPose {
    @JvmField
    val PROXY: EnumProxy<HumanoidModel.ArmPose> = EnumProxy(
        HumanoidModel.ArmPose::class.java,
        false,
        false,
        IArmPoseTransformer { model, state, arm -> PlayerCartClient.pose(model, state, arm) },
    )
}

/**
 * What each client works out for the Chapman's Cart besides what it works out for every handcart ([CartClient]): the canvas folding, the bounce when the
 * shafts drop, the load rocking, the pennant rippling, the creak of the axle, rain beading on the canvas, and the arms of whoever holds the shafts.
 * Called from [CartClient.tick] (once a tick, on the cart the client has) and [CartClient.removed].
 */
object PlayerCartClient {
    /** What a client keeps to draw one Chapman's Cart: springs (degrees), the canvas's fold, the pennant's phase, and the axle's creak. */
    class Feel {
        var fresh = true

        /** 0 the canvas folded back, 1 over the load; and last tick's. */
        var cover = 0f
        var coverO = 0f

        /** Degrees its nose is up from tipping onto the shafts, its speed, and last tick's. */
        var bounce = 0f
        var bounceO = 0f
        var bounceV = 0f

        /** Degrees the load rocks sideways, its speed, and last tick's; and fore and aft. */
        var swayR = 0f
        var swayRO = 0f
        var swayRV = 0f
        var swayP = 0f
        var swayPO = 0f
        var swayPV = 0f

        /** The pennant's ripple: radians gone through (and last tick's) and how far it swings. */
        var wind = 0f
        var windO = 0f
        var flutter = 0.1f

        /** 0 dry to 1 beaded with rain: how wet the canvas is, and last tick's; and whether rain fell on it at the last look. */
        var wet = 0f
        var wetO = 0f
        var rained = false

        /** Blocks a second last tick, and its vertical step. */
        var speed = 0.0
        var rise = 0.0

        /** The axle's creak while it is heard. */
        var creak: Creak? = null
    }

    fun feel(cart: PlayerCart): Feel = cart.feel as? Feel ?: Feel().also { cart.feel = it }

    // ---- Each tick ----

    fun tick(cart: PlayerCart, look: CartClient.Look) {
        val f = feel(cart)
        val first = f.fresh
        f.fresh = false
        val dx = cart.x - cart.xo
        val dz = cart.z - cart.zo
        val rise = cart.y - cart.yo
        val speed = hypot(dx, dz) * 20.0
        val turn = Mth.wrapDegrees(cart.yRot - cart.yRotO)

        // The canvas goes over, or back, in three steps across a second
        f.coverO = f.cover
        val goal = if (cart.covered) 1f else 0f
        f.cover = if (first) goal else Mth.approach(f.cover, goal, 1f / FOLD_TICKS)
        if (first) f.coverO = f.cover

        // Tipping onto the shafts: the nose bounces up and settles
        f.bounceO = f.bounce
        if (!first && look.liftO > 0f && look.lift <= 0f) f.bounceV = BOUNCE_KICK
        f.bounceV = (f.bounceV - BOUNCE_SPRING * f.bounce) * BOUNCE_DAMP
        f.bounce += f.bounceV
        if (first) {
            f.bounce = 0f
            f.bounceO = 0f
        }

        // The load rocks: it leans out of a turn and back from a start, and a step in the ground (a change in the axle's rise) shakes it
        f.swayRO = f.swayR
        f.swayPO = f.swayP
        if (!first) {
            val lurch = ((speed - f.speed) * 3.0).toFloat()
            val step = (rise - f.rise).toFloat()
            val shake = if (abs(step) > STEP_FROM && speed > 0.3) step * STEP_KICK else 0f
            val side = if (cart.tickCount % 2 == 0) 1f else -1f
            val outward = (-turn * min(speed, 4.0).toFloat() * 0.18f).coerceIn(-ROCK_MAX, ROCK_MAX)
            f.swayRV = (f.swayRV + (outward - f.swayR) * ROCK_SPRING + shake * side) * ROCK_DAMP
            f.swayPV = (f.swayPV + (-lurch.coerceIn(-ROCK_MAX, ROCK_MAX) - f.swayP) * ROCK_SPRING + shake * 0.7f) * ROCK_DAMP
            f.swayR = (f.swayR + f.swayRV).coerceIn(-ROCK_MAX, ROCK_MAX)
            f.swayP = (f.swayP + f.swayPV).coerceIn(-ROCK_MAX, ROCK_MAX)
        } else {
            f.swayR = 0f
            f.swayP = 0f
            f.swayRO = 0f
            f.swayPO = 0f
        }
        f.speed = speed
        f.rise = rise

        // The pennant streams the harder the faster it goes
        f.windO = f.wind
        f.wind += 0.28f + (speed * 0.22).toFloat()
        if (f.wind > WIND_WRAP) {
            f.wind -= WIND_WRAP
            f.windO -= WIND_WRAP
        }
        f.flutter += (0.1f + min(speed / 3.7, 1.0).toFloat() * 0.45f - f.flutter) * 0.15f

        creak(cart, look, f, speed)
        wetness(cart, f, first)
        rain(cart, f)
    }

    fun removed(cart: PlayerCart) {
        feel(cart).creak?.end()
        feel(cart).creak = null
    }

    // ---- The creak of the axle, and rain on the canvas ----

    /**
     * The axle's creak (trade.cart_creak: a seamless loop of eight wheel turns, heard 24 blocks off) while it rolls: at the pitch of its speed over the
     * pulled walk's (3.67 blocks a second, so each wheel creaks once a turn), louder the more is on the bed, fading out as it stops; begun as it
     * sets off and let go once it has stood a while.
     */
    private fun creak(cart: PlayerCart, look: CartClient.Look, f: Feel, speed: Double) {
        var loop = f.creak
        if (loop == null || loop.isStopped) {
            if (speed < CREAK_FROM) return
            var laden = 0
            for (s in 0 until Handcart.SLOTS) if (!look.shown[s].isEmpty) laden++
            loop = Creak(cart)
            f.creak = loop
            Minecraft.getInstance().soundManager.play(loop)
            loop.laden = laden / Handcart.SLOTS.toFloat()
        }
        loop.speed = speed
        if (cart.tickCount % 10 == 0) {
            var laden = 0
            for (s in 0 until Handcart.SLOTS) if (!look.shown[s].isEmpty) laden++
            loop.laden = laden / Handcart.SLOTS.toFloat()
        }
    }

    /** The axle's creak on one cart. */
    class Creak(private val cart: PlayerCart) : AbstractTickableSoundInstance(TradeSounds.get("trade.cart_creak"), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        /** Blocks a second it is rolling, and how much of the bed is full (0 to 1). */
        var speed = 0.0
        var laden = 0f
        private var still = 0

        init {
            looping = true
            volume = 0f
            x = cart.x
            y = cart.y + 0.5
            z = cart.z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (cart.isRemoved) return stop()
            x = cart.x
            y = cart.y + 0.5
            z = cart.z
            val moving = (speed / CREAK_FULL).toFloat().coerceIn(0f, 1f)
            val target = moving * (CREAK_QUIET + (CREAK_LOUD - CREAK_QUIET) * laden)
            volume += (target - volume) * if (target > volume) 0.25f else 0.12f
            if (volume < 0.003f && target <= 0f) volume = 0f
            pitch = (speed / PULLED_WALK).toFloat().coerceIn(CREAK_LOW, CREAK_HIGH)
            still = if (volume <= 0f) still + 1 else 0
            if (still > CREAK_LINGER) stop()
        }

        fun end() = stop()
    }

    /**
     * How wet the canvas is: it beads up over [WET_TICKS] while rain falls on the cart (looked at every [RAIN_LOOK] ticks: raining in the world and on
     * the cart's own spot, so not under a roof or in a dry land) and dries over [DRY_TICKS] once it stops. A cart first seen in the rain is wet already.
     */
    private fun wetness(cart: PlayerCart, f: Feel, first: Boolean) {
        f.wetO = f.wet
        if (first || cart.tickCount % RAIN_LOOK == 0) {
            val level = cart.level()
            f.rained = level.isRaining && level.isRainingAt(cart.blockPosition().above(2))
        }
        f.wet = when {
            first -> if (f.rained) 1f else 0f
            f.rained -> min(1f, f.wet + 1f / WET_TICKS)
            else -> max(0f, f.wet - 1f / DRY_TICKS)
        }
        if (first) f.wetO = f.wet
    }

    /** Beads of rain on a canvas that is over the load: a splash now and then, where it is raining on it. */
    private fun rain(cart: PlayerCart, f: Feel) {
        if (f.cover < 1f || !cart.covered) return
        val level = cart.level()
        if (!level.isRaining || level.random.nextInt(RAIN_ONE_IN) != 0) return
        if (!level.isRainingAt(cart.blockPosition().above(2))) return
        val a = Math.toRadians(cart.yRot.toDouble())
        val along = (level.random.nextDouble() - 0.5) * 1.3
        val across = (level.random.nextDouble() - 0.5) * 0.75
        val x = cart.x - sin(a) * along + cos(a) * across
        val z = cart.z + cos(a) * along + sin(a) * across
        level.addParticle(ParticleTypes.SPLASH, x, cart.y + CANVAS_UP, z, 0.0, 0.0, 0.0)
    }

    // ---- The arms of whoever holds the shafts ----

    /** How much of the arm pose a holder has this frame (0 to 1, as the shafts come up into their hands), or none. */
    private val HOLD: ContextKey<Float> = ContextKey(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cart_hold"))

    private val armPose: HumanoidModel.ArmPose by lazy { CartArmPose.PROXY.value }

    /**
     * Whoever holds a cart's shafts has both hands on them, at the hips and a little behind (the cart trails behind whoever pulls it): the arms are
     * brought down and back as the shafts come up into their hands, and let go as they drop. A combat move (filled in first) wins over it.
     */
    val AVATAR = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
            state.setRenderData(HOLD, null)
            val cart = CartClient.pulledBy(entity.id) as? PlayerCart ?: return
            if (state.getRenderData(CombatAnimator.FRAME) != null) return
            val look = CartClient.look(cart)
            val w = CartClient.ease(Mth.lerp(state.partialTick, look.liftO, look.lift))
            if (w <= 0f) return
            state.setRenderData(HOLD, w)
            state.rightArmPose = armPose
            state.leftArmPose = armPose
        }
    }

    /** Poses one arm for [AVATAR]: from where vanilla put it toward the grip, by how far the shafts are up. */
    fun pose(model: HumanoidModel<*>, state: HumanoidRenderState, arm: HumanoidArm) {
        val w = state.getRenderData(HOLD) ?: return
        val part = if (arm == HumanoidArm.RIGHT) model.rightArm else model.leftArm
        part.xRot = Mth.lerp(w, part.xRot, GRIP_BACK)
        part.yRot = Mth.lerp(w, part.yRot, 0f)
        part.zRot = Mth.lerp(w, part.zRot, 0f)
    }

    // ---- What an empty hand asks for ----

    /**
     * An empty hand used: with the shafts in hand and nothing in reach it lets go of them (the cart tips onto its shafts where it stands); with none, and
     * the shafts of a cart aimed at closely (they are thin, and easy to look past), it asks to take them up. The server decides both: the owner's cart, parked, in reach.
     */
    fun onUse(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem || event.hand != InteractionHand.MAIN_HAND) return
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (me.isSpectator || !me.mainHandItem.isEmpty) return
        val hit = minecraft.hitResult
        val held = CartClient.pulledBy(me.id) as? PlayerCart
        if (held != null && held.held && held.pullerId == me.id) {
            if (hit == null || hit.type == HitResult.Type.MISS) {
                ClientPacketDistributor.sendToServer(CartReleasePayload)
                event.isCanceled = true
                event.setSwingHand(false)
            }
            return
        }
        if (hit is EntityHitResult && hit.entity is PlayerCart) return
        val cart = aimedAt(me, level.getEntitiesOfClass(PlayerCart::class.java, me.boundingBox.inflate(REACH + 1.0)), hit) ?: return
        ClientPacketDistributor.sendToServer(CartTakePayload(cart.id))
        event.isCanceled = true
        event.setSwingHand(true)
    }

    /** The parked cart among [carts] whose shafts' end the look comes within [AIM] blocks of, nearer than what it hits (a block), if any. */
    private fun aimedAt(me: Player, carts: List<PlayerCart>, hit: HitResult?): PlayerCart? {
        val eye = me.eyePosition
        val dir = me.lookAngle
        val blocked = if (hit != null && hit.type == HitResult.Type.BLOCK) hit.location.distanceTo(eye) else REACH
        var best: PlayerCart? = null
        var bestAway = AIM
        for (cart in carts) {
            if (cart.held) continue
            val g = cart.grip()
            val tip = Vec3(g.x, cart.y + 0.3, g.z)
            val along = (tip.x - eye.x) * dir.x + (tip.y - eye.y) * dir.y + (tip.z - eye.z) * dir.z
            if (along < 0.2 || along > min(blocked + 0.5, REACH)) continue
            val away = tip.distanceTo(eye.add(dir.scale(along)))
            if (away < bestAway) {
                bestAway = away
                best = cart
            }
        }
        return best
    }

    // ---- Tuning ----

    /** Ticks the canvas takes to go over or back, all three steps. */
    private const val FOLD_TICKS = 18f

    /** The nose's spring when the shafts touch down: its kick (degrees a tick), stiffness and damping. */
    private const val BOUNCE_KICK = 2.4f
    private const val BOUNCE_SPRING = 0.22f
    private const val BOUNCE_DAMP = 0.78f

    /** The load's rocking: its stiffness and damping, its most (degrees), and how big a step in the ground (blocks in a tick) shakes it and how hard. */
    private const val ROCK_SPRING = 0.18f
    private const val ROCK_DAMP = 0.82f
    private const val ROCK_MAX = 5f
    private const val STEP_FROM = 0.04
    private const val STEP_KICK = 22f

    private const val WIND_WRAP = 6.2831855f * 64f

    /** How the arms hang to the shafts: radians back from straight down. */
    private const val GRIP_BACK = 0.3f

    /** The creak: the speed it begins at and is loudest at (blocks a second), the pulled walk (pitch 1), its pitch limits, its volume when quiet and loud, ticks it lingers silent. */
    private const val CREAK_FROM = 0.4
    private const val CREAK_FULL = 1.6
    private const val PULLED_WALK = 3.67
    private const val CREAK_LOW = 0.5f
    private const val CREAK_HIGH = 1.6f
    private const val CREAK_QUIET = 0.35f
    private const val CREAK_LOUD = 0.8f
    private const val CREAK_LINGER = 40

    /** The canvas's wet look: ticks it takes to bead up in the rain and to dry after, and how often the rain on it is looked at. */
    private const val WET_TICKS = 60f
    private const val DRY_TICKS = 1200f
    private const val RAIN_LOOK = 10

    /** Rain: one tick in this many a bead, and how far above its position the canvas is (blocks). */
    private const val RAIN_ONE_IN = 12
    private const val CANVAS_UP = 1.3

    /** How far a hand reaches (blocks), and how close the look must pass the shafts' end. */
    private const val REACH = 3.6
    private const val AIM = 0.45
}
