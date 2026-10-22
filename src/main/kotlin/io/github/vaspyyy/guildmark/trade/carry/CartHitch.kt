package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.cart.PlayerCart
import io.github.vaspyyy.guildmark.network.CartReleasePayload
import io.github.vaspyyy.guildmark.network.CartTakePayload
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.trade.Carrier
import io.github.vaspyyy.guildmark.trade.Carriers
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.Containers
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The Chapman's Cart in the world (living-world/trader/design.md, Part 3; build part D2): taking up its shafts, placing it each tick
 * after everyone has moved, who may do what to it, letting go, the whistle that calls it, and the drag it puts on whoever pulls it. The
 * entity ([PlayerCart]) only holds what is saved and drawn; every decision is made here, on the server.
 *
 * - **Taking up the shafts.** A right-click with an empty hand on the shafts' end ([use]) takes them up: the shafts lift, a wooden knock,
 *   and [PlayerCart.follow] has the cart follow the player's hands from then on, at the shafts' length behind them, as [Handcart]'s own
 *   code has a carter's. A click on the shafts again, or on nothing at all with an empty hand ([release], sent by the client), lets go; so
 *   does sitting down, riding, going to bed, dying, logging out or leaving the dimension. A click on the bed opens its 27 slots; sneaking with
 *   an empty hand on the bed pulls the canvas over the load or folds it back.
 * - **Never a trap.** It does not go through walls, up more than a block, or into water or leaves. A cart that has not followed for
 *   [CAUGHT_AFTER] ticks while its puller is more than [FAR] blocks off (or [HIGH] above or below it) lets go by itself, with a clatter and a
 *   line in the action bar that says what it caught on ([caught]). Nothing is lost: the cart stands where it is, and it is taken up again,
 *   called with the whistle, or brought by `trade carry recall`.
 * - **The drag.** While the shafts are in hand the player's `movement_speed` has the transient modifier `guildmark:cart_drag`,
 *   [TradeConfig.cartSlow] (15%) less; it is taken off on letting go however that comes about, and it is never saved.
 * - **Whose.** The owner takes the shafts, opens it and breaks it (sneaking, hitting); a party mate opens it; an operator in creative opens it;
 *   anyone else is told "That is Ransom's cart.". With carrying switched off none of this is looked at: it does not follow, drags no one and
 *   opens for anyone, as every container does.
 *
 * The carts that are loaded are kept in [carts] (from [PlayerCart.onAddedToLevel]); nothing scans the world for them.
 */
object CartHitch {
    /** The id of the drag: `guildmark:cart_drag`. */
    val DRAG: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cart_drag")

    /** A cart that has not followed for this many ticks (three seconds) while its puller is more than [FAR] blocks off lets go by itself. */
    const val CAUGHT_AFTER = 60

    /** How far off the puller may be, flat, and how far above or below, before the cart counts as not following. */
    const val FAR = 5.0
    const val HIGH = 3.0

    /** How far out along the cart, from its axle, a click is on the shafts and not on the bed (the bed's front board is 0.9). */
    private const val SHAFTS_FROM = 0.75

    /** How far from the grip a player may be and still take the shafts by the client's say-so. */
    private const val REACH = 3.6

    /** A called cart rolls at this many blocks a tick (a walking pace), turns this many degrees a tick, and stops this far from the player. */
    private const val CALL_SPEED = 0.2
    private const val CALL_TURN = 6.0
    private const val CALL_STOP = 3.0

    /** The longest a called cart rolls (ticks), and how long it may be blocked before it gives up. */
    private const val CALL_MOST = 20 * 90
    private const val BLOCKED_MOST = 30

    /** Ticks a let-go cart settles every tick, and ticks from letting go to the shafts' tips knocking on the ground. */
    private const val SETTLE_TICKS = 10
    private const val KNOCK_AFTER = 6

    /** The whistle may not be blown at a cart more often than this (ticks); the whistle's own cooldown is the item's. */
    private const val WHISTLE_GAP = 20L

    /** Every loaded cart, in the order they were loaded. */
    private val carts = LinkedHashSet<PlayerCart>()

    /** The cart each player has in hand, by the player. */
    private val hitched = HashMap<UUID, PlayerCart>()

    /** A line that waits a tick to be said, so it is not overwritten by the whistle's own. */
    private val saying = HashMap<UUID, Component>()
    private val whistled = HashMap<UUID, Long>()

    /**
     * Registers this part's payloads, events and commands, and adds the cart to the carriers, to `trade carry status|recall|unstick` and to
     * the hall's counter. Called once from the trader's setup (`Trade.register`).
     */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToServer(CartReleasePayload.TYPE, CartReleasePayload.STREAM_CODEC, CartReleasePayload::handle)
                .playToServer(CartTakePayload.TYPE, CartTakePayload.STREAM_CODEC, CartTakePayload::handle)
        }
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event -> (event.level as? ServerLevel)?.let { tick(it) } }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (saying.isNotEmpty()) say(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickItem::class.java) { onWhistle(it) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            carts.clear()
            hitched.clear()
            saying.clear()
            whistled.clear()
        }
        Carriers.add(Carrier { player, radius -> holdings(player, radius) })
        Carry.reports.add(Carry.Report { player -> report(player) })
        Carry.recalls.add(Carry.Bring { player -> bringAll(player, held = false) })
        Carry.unsticks.add(Carry.Bring { player -> bringAll(player, held = true) })
        CartScenes.register()
        CartCommand.init()
    }

    // ---- Which carts are about ----

    /** [cart] has come into a level (made, or loaded with its chunk). */
    internal fun loaded(cart: PlayerCart) {
        carts.add(cart)
    }

    /** [cart] has gone from its level (broken, or its chunk unloaded): nobody holds it any more. */
    internal fun unloaded(cart: PlayerCart) {
        carts.remove(cart)
        val holder = hitched.entries.firstOrNull { it.value === cart } ?: return
        hitched.remove(holder.key)
        (cart.level() as? ServerLevel)?.server?.playerList?.getPlayer(holder.key)?.let { dropDrag(it) }
    }

    /** Every loaded cart of [owner]'s, in whatever level. */
    fun cartsOf(owner: UUID): List<PlayerCart> = carts.filter { it.ownerId == owner && !it.isRemoved }

    /** The cart [player] has in hand, if they have one. */
    fun inHand(player: Player): PlayerCart? = hitched[player.uuid]?.takeIf { !it.isRemoved && it.pullerId == player.id }

    /** The containers of [player]'s carts within [radius] blocks of them in the level they are in (what [Carriers] counts). */
    private fun holdings(player: ServerPlayer, radius: Double): List<net.minecraft.world.Container> {
        if (carts.isEmpty() || !TradeConfig.carry()) return listOf()
        return cartsOf(player.uuid).filter { it.level() === player.level() && it.distanceToSqr(player) <= radius * radius }
    }

    // ---- Each tick, after everyone has moved ----

    private fun tick(level: ServerLevel) {
        if (carts.isEmpty()) return
        for (cart in carts.toList()) {
            if (cart.isRemoved) {
                carts.remove(cart)
                continue
            }
            if (cart.level() !== level) continue
            try {
                step(level, cart)
            } catch (e: RuntimeException) {
                // Whatever went wrong, the player is not left holding it
                Guildmark.LOGGER.warn("A Chapman's Cart could not be placed", e)
                if (cart.held) letGo(cart, level, sound = false)
            }
        }
    }

    private fun step(level: ServerLevel, cart: PlayerCart) {
        val on = TradeConfig.carry()
        if (cart.held) {
            val puller = level.getEntity(cart.pullerId) as? LivingEntity
            val gone = puller == null || !puller.isAlive || puller.isRemoved || puller.level() !== level || puller.isSleeping || puller.isPassenger ||
                (puller is Player && puller.isSpectator)
            if (!on || gone) {
                letGo(cart, level, sound = on)
            } else {
                follow(level, cart, puller)
                return
            }
        }
        if (cart.state == PlayerCart.State.CALLED) {
            if (!on) stopRolling(cart) else if (roll(level, cart)) return
        }
        stand(level, cart)
    }

    /** One tick pulled: placed behind its puller, watched for being caught, and its load shown. */
    private fun follow(level: ServerLevel, cart: PlayerCart, puller: LivingEntity) {
        cart.follow(level, puller)
        // It may have let go in following (snapped somewhere it cannot be brought behind)
        if (!cart.held || cart.pullerId != puller.id) return
        if (puller is ServerPlayer) {
            val far = hypot(puller.x - cart.x, puller.z - cart.z) > FAR || abs(puller.y - cart.y) > HIGH
            cart.stuck = if (far) cart.stuck + 1 else 0
            if (cart.stuck >= CAUGHT_AFTER) return caught(cart, level)
            // A change to the setting is felt at once, and nothing else can have taken the drag off
            if (puller.tickCount % 20 == 0) addDrag(puller)
        }
        if (cart.dirty) cart.showLoad()
    }

    /** One tick standing: it settles onto its shafts (every tick for a moment after it is let go, then once a second). */
    private fun stand(level: ServerLevel, cart: PlayerCart) {
        cart.rested++
        if (cart.rested <= SETTLE_TICKS || (level.gameTime + cart.id) % 20 == 0L) cart.settle(level)
        // The shafts' tips come down on the ground a moment after it is let go
        if (cart.knock >= 0 && --cart.knock < 0) {
            val g = cart.grip()
            level.playSound(null, g.x, cart.y, g.z, TradeSounds.get("trade.shafts_drop"), SoundSource.NEUTRAL, 0.8f, 0.95f + level.random.nextFloat() * 0.1f)
        }
        if (cart.dirty) cart.showLoad()
    }

    // ---- Taking up the shafts, and letting go ----

    /**
     * [player] takes up [cart]'s shafts: it follows them from now on, and they are slowed for as long as they hold them. False if they cannot (it is
     * in another dimension, someone has it, they are not on their feet). Whatever cart they had in hand is let go of first.
     */
    fun take(cart: PlayerCart, player: ServerPlayer): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (cart.level() !== level || cart.isRemoved || cart.held) return false
        if (player.isSleeping || player.isPassenger || player.isSpectator || !player.isAlive) return false
        hitched[player.uuid]?.let { other -> if (other !== cart && !other.isRemoved) letGo(other, other.level() as ServerLevel, sound = false) }
        if (cart.ownerId == null) claim(cart, player)
        stopRolling(cart)
        cart.hold(player)
        cart.state = PlayerCart.State.HELD
        cart.stuck = 0
        cart.knock = -1
        hitched[player.uuid] = cart
        addDrag(player)
        val g = cart.grip()
        level.playSound(null, g.x, cart.y + 0.4, g.z, TradeSounds.get("trade.shafts_lift"), SoundSource.NEUTRAL, 0.9f, 0.95f + level.random.nextFloat() * 0.1f)
        return true
    }

    /** A carter takes up [cart]'s shafts (the counter's scene brings it round): it follows him as a carter's own does, and nobody is slowed. */
    internal fun handTo(cart: PlayerCart, puller: LivingEntity) {
        cart.hold(puller)
        cart.state = PlayerCart.State.HELD
        cart.stuck = 0
        cart.knock = -1
    }

    /**
     * Whoever has [cart]'s shafts lets go: it tips forward onto them where it stands. [sound] is whether the shafts' knock is heard (the
     * clatter of a catch is played by [caught] instead).
     */
    fun letGo(cart: PlayerCart, level: ServerLevel, sound: Boolean = true) {
        val holder = hitched.entries.firstOrNull { it.value === cart }?.key
        if (holder != null) {
            hitched.remove(holder)
            level.server.playerList.getPlayer(holder)?.let { dropDrag(it) }
        }
        if (cart.held) cart.letGo(level)
        if (cart.state == PlayerCart.State.HELD) cart.state = PlayerCart.State.PARKED
        cart.stuck = 0
        cart.rested = 0
        cart.knock = if (sound) KNOCK_AFTER else -1
    }

    /** [player] lets go of the cart in their hand, if they have one, and whatever else is of the cart's on them is taken off. */
    fun release(player: ServerPlayer) {
        val cart = inHand(player)
        if (cart != null) letGo(cart, cart.level() as ServerLevel)
        dropDrag(player)
    }

    /** As [release], without a sound: the player is leaving, dying or being taken elsewhere. */
    private fun letGoOf(player: ServerPlayer) {
        val cart = hitched[player.uuid]
        if (cart != null && !cart.isRemoved) letGo(cart, cart.level() as ServerLevel, sound = false)
        hitched.remove(player.uuid)
        dropDrag(player)
    }

    /**
     * [cart] cannot follow: the shafts drop out of its puller's hands with a clatter and a puff of dust, and a line in the action bar says what it
     * caught on. It waits where it is.
     */
    fun caught(cart: PlayerCart, level: ServerLevel) {
        val who = level.getEntity(cart.pullerId) as? ServerPlayer
        val why = when {
            !level.getFluidState(cart.blockPosition()).isEmpty -> "water"
            who != null && abs(who.y - cart.y) > HIGH -> "step"
            else -> "stone"
        }
        letGo(cart, level, sound = false)
        val g = cart.grip()
        level.playSound(null, g.x, cart.y + 0.3, g.z, TradeSounds.get("trade.shafts_drop"), SoundSource.NEUTRAL, 1.1f, 0.7f)
        level.sendParticles(ParticleTypes.POOF, g.x, cart.y + 0.15, g.z, 7, 0.35, 0.05, 0.35, 0.02)
        val under = level.getBlockState(BlockPos.containing(cart.x, cart.y - 0.2, cart.z))
        if (!under.isAir) level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, under), g.x, cart.y + 0.1, g.z, 10, 0.4, 0.05, 0.4, 0.05)
        who?.sendOverlayMessage(Component.translatable("message.guildmark.trade.cart.caught.$why"))
    }

    private fun claim(cart: PlayerCart, player: ServerPlayer) {
        cart.ownerId = player.uuid
        cart.ownerName = player.name.string
        cart.setColour(PlayerCart.colourFor(player.uuid))
    }

    // ---- The drag ----

    /** Puts the drag on [player]'s walking speed, or brings it up to date if the setting has changed. */
    private fun addDrag(player: ServerPlayer) {
        val speed = player.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        val want = -TradeConfig.cartSlow()
        val have = speed.getModifier(DRAG)
        if (have != null && have.amount() == want) return
        if (have != null) speed.removeModifier(DRAG)
        if (want != 0.0) speed.addTransientModifier(AttributeModifier(DRAG, want, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
    }

    /** Takes the drag off [player]. */
    private fun dropDrag(player: ServerPlayer) {
        player.getAttribute(Attributes.MOVEMENT_SPEED)?.removeModifier(DRAG)
    }

    // ---- A hand on it ----

    /**
     * [player] used [cart] in [hand], at [hit] (relative to the cart's position). Everything is decided here: the client's answer is a guess that
     * the click will be taken (so the hand swings and nothing else is tried), the server's is the truth.
     */
    internal fun use(cart: PlayerCart, player: Player, hand: InteractionHand, hit: Vec3): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        if (player.level().isClientSide) return InteractionResult.SUCCESS
        val who = player as? ServerPlayer ?: return InteractionResult.PASS
        // Off, it is a chest on a cart like any container: it opens for anyone, and does nothing else
        if (!TradeConfig.carry()) return open(cart, who)
        if (cart.ownerId == null) claim(cart, who)
        if (cart.ownerId != who.uuid) {
            if (!mayOpen(who, cart)) {
                who.sendOverlayMessage(notYours(cart))
                return InteractionResult.CONSUME
            }
            // A party mate or an operator looks in, and cannot take the shafts
            return open(cart, who)
        }
        if (cart.ownerName != who.name.string) cart.ownerName = who.name.string
        val (fx, fz) = Handcart.forward(cart.yRot)
        val onShafts = hit.x * fx + hit.z * fz > SHAFTS_FROM
        val empty = who.mainHandItem.isEmpty
        val mine = cart.held && cart.pullerId == who.id
        return when {
            // A carter has it just now (the counter's scene): look in, nothing else
            cart.held && !mine -> open(cart, who)
            empty && onShafts -> {
                if (mine) letGo(cart, who.level() as ServerLevel) else take(cart, who)
                InteractionResult.SUCCESS
            }
            empty && who.isShiftKeyDown -> {
                fold(cart)
                InteractionResult.SUCCESS
            }
            else -> open(cart, who)
        }
    }

    /** Opens its 27 slots to [player]. */
    private fun open(cart: PlayerCart, player: ServerPlayer): InteractionResult = cart.interactWithContainerVehicle(player)

    /** Pulls the canvas over the load, or folds it back (in three steps, on the client). */
    private fun fold(cart: PlayerCart) {
        val level = cart.level() as? ServerLevel ?: return
        cart.setCovered(!cart.covered)
        level.playSound(null, cart.x, cart.y + 0.8, cart.z, TradeSounds.get("trade.canvas_flap"), SoundSource.NEUTRAL, 0.8f, 0.9f + level.random.nextFloat() * 0.2f)
    }

    /** [attacker] hit [cart]: sneaking, its owner takes it apart; no one and nothing else can harm it. */
    internal fun hit(cart: PlayerCart, attacker: Player) {
        val who = attacker as? ServerPlayer ?: return
        if (cart.ownerId != null && cart.ownerId != who.uuid && !(who.isCreative && who.level().server?.playerList?.isOp(who.nameAndId()) == true)) {
            who.sendOverlayMessage(notYours(cart))
            return
        }
        if (!who.isShiftKeyDown) {
            val now = who.level().gameTime
            if (now - (whistled[who.uuid] ?: Long.MIN_VALUE / 2) >= 40L) {
                whistled[who.uuid] = now
                who.sendOverlayMessage(Component.translatable("message.guildmark.trade.cart.break_hint"))
            }
            return
        }
        breakCart(cart)
    }

    /** [cart] is taken apart: it drops as its item, with what was in it in a cluster of item entities beside it. */
    fun breakCart(cart: PlayerCart) {
        val level = cart.level() as? ServerLevel ?: return
        if (cart.held) letGo(cart, level, sound = false)
        level.playSound(null, cart.x, cart.y + 0.5, cart.z, SoundEvents.WOOD_BREAK, SoundSource.NEUTRAL, 1.0f, 0.9f)
        level.sendParticles(ParticleTypes.POOF, cart.x, cart.y + 0.6, cart.z, 8, 0.4, 0.3, 0.4, 0.02)
        Containers.dropContents(level, cart, cart)
        cart.clearContent()
        cart.spawnAtLocation(level, cart.asItem())
        cart.discard()
    }

    /** Whether [player] may open [cart]: its owner, a member of its owner's party, or an operator in creative. */
    private fun mayOpen(player: ServerPlayer, cart: PlayerCart): Boolean {
        val owner = cart.ownerId ?: return true
        if (owner == player.uuid) return true
        val server = player.level().server ?: return true
        if (Parties.get(server).of(owner)?.members?.contains(player.uuid) == true) return true
        return player.isCreative && server.playerList.isOp(player.nameAndId())
    }

    /** The line a stranger is told: "That is Ransom's cart.". */
    private fun notYours(cart: PlayerCart): Component =
        if (cart.ownerName.isEmpty()) Component.translatable("message.guildmark.trade.cart.not_yours_unknown")
        else Component.translatable("message.guildmark.trade.cart.not_yours", cart.ownerName)

    // ---- What the client may ask ----

    /** The client says [player] used an empty hand on nothing: they let go of the shafts. */
    internal fun releaseBy(player: ServerPlayer) {
        if (!TradeConfig.carry()) return
        val cart = inHand(player) ?: return
        letGo(cart, cart.level() as ServerLevel)
    }

    /**
     * The client says [player] aimed at the shafts of the cart [id] (a thin stick is easy to miss) and used an empty hand: they take them up,
     * if the cart is theirs, parked, and the shafts are within reach.
     */
    internal fun takeBy(player: ServerPlayer, id: Int) {
        if (!TradeConfig.carry() || !player.mainHandItem.isEmpty) return
        val cart = player.level().getEntity(id) as? PlayerCart ?: return
        if (cart.ownerId != null && cart.ownerId != player.uuid) return
        if (cart.held) return
        val g = cart.grip()
        if (player.distanceToSqr(g.x, cart.y, g.z) > REACH * REACH) return
        take(cart, player)
    }

    // ---- The whistle ----

    /**
     * The Drover's Whistle was used: every parked cart of the player's within the whistle's range rolls to them at a walking pace if the way is clear
     * (or, sneaking, is told to stay where it is). Done beside the whistle's own call of the pack animals and said a tick after it, so it is
     * not drowned by that one's line.
     */
    private fun onWhistle(event: PlayerInteractEvent.RightClickItem) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.hand != InteractionHand.MAIN_HAND || event.itemStack.item != io.github.vaspyyy.guildmark.registry.ModItems.DROVERS_WHISTLE.get()) return
        if (!TradeConfig.carry() || carts.isEmpty()) return
        val level = player.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (now - (whistled[player.uuid] ?: Long.MIN_VALUE / 2) < WHISTLE_GAP) return
        val stay = player.isShiftKeyDown
        var heard = 0
        for (cart in cartsOf(player.uuid)) {
            if (cart.level() !== level || cart.held || cart.distanceToSqr(player) > PackFollow.WHISTLE_RANGE * PackFollow.WHISTLE_RANGE) continue
            if (stay) {
                if (cart.state == PlayerCart.State.CALLED) stopRolling(cart)
            } else {
                cart.state = PlayerCart.State.CALLED
                cart.calledFor = 0
                cart.blockedFor = 0
                cart.rolling = 0.0
            }
            heard++
        }
        if (heard == 0) return
        whistled[player.uuid] = now
        saying[player.uuid] = Component.translatable("message.guildmark.trade.cart.whistle." + (if (stay) "stay" else "come") + (if (heard == 1) ".one" else ".many"), heard)
    }

    private fun say(server: net.minecraft.server.MinecraftServer) {
        for ((id, line) in saying) server.playerList.getPlayer(id)?.sendOverlayMessage(line)
        saying.clear()
    }

    /** One tick rolling to its owner; false once it has stopped (arrived, blocked, or its owner is not about). */
    private fun roll(level: ServerLevel, cart: PlayerCart): Boolean {
        val owner = cart.ownerId?.let { level.server.playerList.getPlayer(it) }
        if (owner == null || owner.level() !== level || !owner.isAlive || owner.isSpectator) {
            stopRolling(cart)
            return false
        }
        val dx = owner.x - cart.x
        val dz = owner.z - cart.z
        if (hypot(dx, dz) <= CALL_STOP || ++cart.calledFor > CALL_MOST || abs(owner.y - cart.y) > HIGH + 1) {
            stopRolling(cart)
            return false
        }
        // It swings round to the player a little at a time, and rolls once it is nearly facing them
        val want = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val turn = Mth.wrapDegrees(want - cart.yRot)
        val yaw = cart.yRot + Mth.clamp(turn, -CALL_TURN.toFloat(), CALL_TURN.toFloat())
        cart.rolling = if (abs(turn) < 40f) min(cart.rolling + 0.012, CALL_SPEED) else maxOf(cart.rolling - 0.03, 0.0)
        val (fx, fz) = Handcart.forward(yaw)
        var nx = cart.x
        var nz = cart.z
        if (cart.rolling > 0.0) {
            nx += fx * cart.rolling
            nz += fz * cart.rolling
            if (!cart.clear(level, nx, nz, -fx, -fz, cart.y) || cart.rutted(level, nx, nz, yaw)) {
                cart.rolling = 0.0
                nx = cart.x
                nz = cart.z
                if (++cart.blockedFor > BLOCKED_MOST) {
                    stopRolling(cart)
                    (owner as? ServerPlayer)?.sendOverlayMessage(Component.translatable("message.guildmark.trade.cart.blocked"))
                    return false
                }
            } else {
                cart.blockedFor = 0
            }
        }
        cart.yRot = yaw
        cart.setPos(nx, cart.y, nz)
        cart.settle(level)
        cart.setPos(cart.x, cart.y, cart.z)
        if (cart.dirty) cart.showLoad()
        return true
    }

    private fun stopRolling(cart: PlayerCart) {
        if (cart.state == PlayerCart.State.CALLED) cart.state = PlayerCart.State.PARKED
        cart.rolling = 0.0
        cart.calledFor = 0
        cart.blockedFor = 0
        cart.rested = 0
    }

    // ---- Making one, and bringing one ----

    /**
     * A new cart for [owner] (or nobody) set down at [at], its shafts toward [toward] (or the way [owner] faces), in [colour] (or the owner's own).
     * Null if there is no room for it there.
     */
    fun setDown(level: ServerLevel, owner: ServerPlayer?, at: Vec3, toward: Vec3?, colour: Int?): PlayerCart? {
        val cart = Handcarts.PLAYER_CART.get().create(level, EntitySpawnReason.EVENT) ?: return null
        if (owner != null) {
            cart.ownerId = owner.uuid
            cart.ownerName = owner.name.string
        }
        cart.setColour(colour ?: owner?.let { PlayerCart.colourFor(it.uuid) } ?: PlayerCart.DYES[0])
        val yaw = when {
            toward != null -> Math.toDegrees(atan2(-(toward.x - at.x), toward.z - at.z)).toFloat()
            owner != null -> owner.yRot + 180f
            else -> 0f
        }
        cart.snapTo(at.x, at.y, at.z, yaw, 0f)
        val (fx, fz) = Handcart.forward(yaw)
        if (!cart.clear(level, at.x, at.z, -fx, -fz, at.y) || cart.rutted(level, at.x, at.z, yaw)) return null
        cart.settle(level)
        cart.setPos(cart.x, cart.y, cart.z)
        return if (level.addFreshEntity(cart)) cart else null
    }

    /**
     * Sets [cart] down beside [player], its shafts toward them and ready to take up, wherever it was (in their level): behind them if there
     * is room, else as near as there is, else right by their feet. True if it was brought (it is not, across dimensions).
     */
    fun bring(cart: PlayerCart, player: ServerPlayer): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (cart.level() !== level || cart.isRemoved) return false
        if (cart.held) letGo(cart, level, sound = false)
        stopRolling(cart)
        val from = cart.position()
        if (!cart.place(level, player)) {
            // Nowhere clear: it is put down by their feet all the same, for nothing is ever lost to a wall
            val (bx, bz) = Handcart.forward(player.yRot + 180f)
            val yaw = player.yRot
            cart.snapTo(player.x + bx * 1.8, player.y, player.z + bz * 1.8, yaw, 0f)
        }
        cart.settle(level)
        cart.setPos(cart.x, cart.y, cart.z)
        cart.rested = 0
        cart.knock = KNOCK_AFTER
        level.sendParticles(ParticleTypes.CLOUD, from.x, from.y + 0.5, from.z, 4, 0.3, 0.3, 0.3, 0.01)
        level.sendParticles(ParticleTypes.CLOUD, cart.x, cart.y + 0.5, cart.z, 6, 0.3, 0.3, 0.3, 0.01)
        return true
    }

    /** `trade carry recall` and `unstick`: every loaded cart of [player]'s in their level beside them ([held] too, for the unstick); how many. */
    private fun bringAll(player: ServerPlayer, held: Boolean): Int {
        var n = 0
        for (cart in cartsOf(player.uuid)) {
            if (cart.held && !held) continue
            if (bring(cart, player)) n++
        }
        return n
    }

    /** [cart] fell out of the world: back beside its owner if they are here, else up to the bottom of the world. Nothing is lost. */
    internal fun rescue(cart: PlayerCart) {
        val level = cart.level() as? ServerLevel ?: return
        val owner = cart.ownerId?.let { level.server.playerList.getPlayer(it) }
        if (owner != null && owner.level() === level && bring(cart, owner)) return
        if (cart.held) letGo(cart, level, sound = false)
        cart.setPos(cart.x, level.minY + 1.0, cart.z)
        cart.setDeltaMovement(Vec3.ZERO)
    }

    // ---- Words ----

    /** `trade carry status`: where each loaded cart of [player]'s is and what it is doing. */
    private fun report(player: ServerPlayer): List<Component> {
        val mine = cartsOf(player.uuid)
        if (mine.isEmpty()) return listOf(Component.literal("No loaded Chapman's Cart of yours.").withStyle(ChatFormatting.GRAY))
        return mine.map { cart ->
            val where = "%d %d %d".format(Locale.ROOT, cart.blockX, cart.blockY, cart.blockZ)
            val far = if (cart.level() === player.level()) "%.0f blocks".format(Locale.ROOT, cart.distanceTo(player)) else "another dimension"
            val doing = when (cart.state) {
                PlayerCart.State.HELD -> if (cart.pullerId == player.id) "in your hands" else "in someone's hands"
                PlayerCart.State.CALLED -> "rolling to you"
                PlayerCart.State.PARKED -> "parked"
            }
            val load = "%.1f".format(Locale.ROOT, Laden.worth(cart))
            val line: MutableComponent = Component.empty()
            line.append(Component.literal("Chapman's Cart: ").withStyle(ChatFormatting.YELLOW))
            line.append(Component.literal("$where ($far), $doing; ${cart.used()}/${PlayerCart.SIZE} slots, $load Marks at base prices, canvas ${if (cart.covered) "over" else "folded"}").withStyle(ChatFormatting.WHITE))
            line
        }
    }
}
