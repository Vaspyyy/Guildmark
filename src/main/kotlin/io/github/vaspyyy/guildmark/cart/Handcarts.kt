package io.github.vaspyyy.guildmark.cart

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.UUID

/**
 * The carters' handcarts (see [Handcart]): the way in for the townsfolk's jobs. A carter [hitch]es up (their cart
 * is brought round if it isn't by them), walks wherever they're going with it in hand ([pulling]; walk with
 * [PULL] over the walk, and stand with it too), [unhitch]es to leave it [parked], and says what's heaped on it
 * with [load] as they put sacks on or take them off.
 *
 * Carts go where their pullers go, a few blocks behind, so they want room: keep them to the streets and yards, and
 * let go of them outside. A carter going in at a door stops at a [bay] beside it first, so the cart stands clear
 * of the doorway; [side] is where to stand to load it and [fetch] where to take up the shafts again. Carts aren't
 * saved: after a reload, the next [hitch] brings one round. Off in the settings, [hitch] gives null (and the
 * carters carry their sacks on their shoulders).
 */
object Handcarts {
    /** The handcart's entity type, registered with the game in [register]. */
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)

    /** A handcart: never saved, never summoned. */
    val HANDCART: DeferredHolder<EntityType<*>, EntityType<Handcart>> = ENTITIES.register("handcart") { ->
        EntityType.Builder.of(::Handcart, MobCategory.MISC)
            .sized(1.4f, 1.0f)
            .noSave()
            .noSummon()
            .fireImmune()
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "handcart")))
    }

    /** The pulling move: leaning into it, both hands back on the shafts. Play it over the walk, and standing, while the cart is in hand. */
    val PULL: Activity = Activity.PULL_CART

    /** A sack of flour, as a cart shows it (flour has no item of its own). */
    val FLOUR: ItemStack get() = ItemStack(Items.SUGAR)

    // Each carter's cart, by who they are; where each is taking theirs to leave it, so nobody else picks the same spot
    // meanwhile; and carts nobody wants any more, until no one's looking
    private val carts = HashMap<UUID, Handcart>()
    private val bound = HashMap<UUID, Pair<Townsperson, BlockPos>>()
    private val strays = ArrayList<Handcart>()

    /** Registers handcarts with the game; called once from the mod's constructor. */
    fun register(modBus: IEventBus) {
        ENTITIES.register(modBus)
        // After everyone has moved, so a cart is where its puller is this very tick
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event -> (event.level as? ServerLevel)?.let { tick(it) } }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { CartDemo.register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            carts.clear()
            bound.clear()
            strays.clear()
        }
    }

    /** Whether carters pull handcarts (the Handcarts setting). */
    fun enabled(): Boolean = try {
        GuildmarkConfig.HANDCARTS.get()
    } catch (e: IllegalStateException) {
        true
    }

    // ---- For the townsfolk's jobs ----

    /**
     * [p]'s cart, brought round if they haven't one by them (the one they parked nearby, or a new one behind them),
     * following them from now on. Null if there's nowhere for one (or carts are off).
     *
     * Their parked cart is taken up where it stands when they're at its shafts (see [fetch]) or just ahead of it
     * (it rolls up to them); out of everyone's sight it's simply brought round. Otherwise, while someone could
     * see, it waits where it is until they come to it ([pulling] stays false till then).
     */
    fun hitch(p: Townsperson): Handcart? {
        if (!enabled()) return null
        val level = p.level() as? ServerLevel ?: return null
        val cart = mine(p)
        if (cart != null) {
            if (cart.held) return cart
            if (cart.reachable(p) || !cart.seen(level) && !cart.seen(level, p) && cart.place(level, p)) {
                cart.take(p)
                return cart
            }
            if (cart.distanceToSqr(p) < FAR * FAR) {
                cart.waiting = true
                return cart
            }
            // Too far off to fetch: it's left to go once no one's looking, and they bring another
            abandon(p, cart)
        }
        val made = HANDCART.get().create(level, EntitySpawnReason.EVENT) ?: return null
        if (!made.place(level, p)) return null
        made.take(p)
        carts[p.uuid] = made
        if (!level.addFreshEntity(made)) {
            carts.remove(p.uuid)
            return null
        }
        return made
    }

    /** What's heaped on [p]'s cart: [goods] in order, an empty list for nothing. What stays keeps its place. */
    fun load(p: Townsperson, goods: List<ItemStack>) {
        mine(p)?.heap(goods)
    }

    /** [p] lets go: the cart stays parked where it stands. */
    fun unhitch(p: Townsperson) {
        val cart = mine(p) ?: return
        val level = p.level() as? ServerLevel ?: return
        if (cart.held || cart.waiting) cart.letGo(level)
    }

    /** The cart [p] left parked, if it's still there. */
    fun parked(p: Townsperson): Handcart? = mine(p)?.takeIf { !it.held }

    /** Whether [p] has their cart in hand, following them. */
    fun pulling(p: Townsperson): Boolean = mine(p)?.held == true

    /** Where [p] stands to take up their parked cart's shafts (walk there, exactly, then [hitch]); null with none parked. */
    fun fetch(p: Townsperson): BlockPos? {
        val cart = parked(p) ?: return null
        val g = cart.grip()
        return standing(cart.level(), BlockPos.containing(g.x, cart.y + 0.5, g.z))
    }

    /**
     * Where [p] stands beside their parked cart to put things on it or take them off: by the middle of the bed, on
     * the side nearer [from] (the door they come and go by, say). Null with none parked or no room either side.
     */
    fun side(p: Townsperson, from: Vec3? = null): BlockPos? {
        val cart = parked(p) ?: return null
        val (fx, fz) = Handcart.forward(cart.yRot)
        val mid = Vec3(cart.x + fx * BED_MIDDLE, cart.y, cart.z + fz * BED_MIDDLE)
        // The cart's right is (-fz, fx)
        val right = Vec3(mid.x - fz * BESIDE, mid.y, mid.z + fx * BESIDE)
        val left = Vec3(mid.x + fz * BESIDE, mid.y, mid.z - fx * BESIDE)
        val sides = if (from != null && from.distanceToSqr(left) < from.distanceToSqr(right)) listOf(left, right) else listOf(right, left)
        for (spot in sides) for (along in ALONG) {
            val at = BlockPos.containing(spot.x + fx * along, cart.y + 0.5, spot.z + fz * along)
            standing(cart.level(), at)?.let { return it }
        }
        return null
    }

    /** The middle of [p]'s parked cart's bed, to look at while loading it; null with none parked. */
    fun bed(p: Townsperson): Vec3? {
        val cart = parked(p) ?: return null
        val (fx, fz) = Handcart.forward(cart.yRot)
        return Vec3(cart.x + fx * BED_MIDDLE, cart.y + Handcart.AXLE_UP + 0.3, cart.z + fz * BED_MIDDLE)
    }

    /**
     * Where to stop with a cart to leave it by the door at [door] (whose outside is toward [outward]): two blocks
     * out from the wall and three or more along it, so the cart stands clear of the doorway whichever way it came,
     * and not where someone else's cart stands already ([by]'s own doesn't count). Null if there's no room by the
     * door.
     */
    fun bay(level: Level, door: BlockPos, outward: Direction, by: Townsperson? = null): BlockPos? {
        val along = outward.clockWise
        for (step in BAY_STEPS) {
            val base = door.relative(outward, 2).relative(along, step)
            for (dy in BAY_DY) {
                val at = base.above(dy)
                if (!Places.standable(level, at)) continue
                if (taken(level, at, by)) break
                if (Places.standable(level, at.relative(along)) || Places.standable(level, at.relative(along.opposite))) return at
            }
        }
        return null
    }

    /** Where to stop with a cart to leave it by [door] (see the other [bay]). */
    fun bay(level: Level, door: CityPlaces.Door, by: Townsperson? = null): BlockPos? = bay(level, door.pos, door.inward.opposite, by)

    /** [p] is taking their cart to [at] to leave it there (null: nowhere now), so no one else's bay is picked there meanwhile. */
    fun headFor(p: Townsperson, at: BlockPos?) {
        if (at == null) bound.remove(p.uuid) else bound[p.uuid] = p to at
    }

    /** Whether a cart other than [by]'s stands near [at], or is on its way to stand there, where one stopping there would stand on it. */
    private fun taken(level: Level, at: BlockPos, by: Townsperson?): Boolean {
        val c = Vec3.atBottomCenterOf(at)
        if (carts.values.any { it.level() === level && !it.isRemoved && it.owner !== by && it.distanceToSqr(c) < TAKEN * TAKEN }) return true
        bound.values.removeIf { (p, _) -> p.isRemoved }
        return bound.values.any { (p, spot) -> p !== by && p.level() === level && spot.distSqr(at) < TAKEN * TAKEN }
    }

    /** The stack that shows [good] on a cart: its own item; flour as a flour sack ([FLOUR]). */
    fun stack(good: Good): ItemStack = when (good) {
        Good.FLOUR -> FLOUR
        Good.TOOLS -> ItemStack(Items.IRON_SHOVEL)
        Good.ALE -> ItemStack(Items.BARREL)
        else -> good.item?.let { ItemStack(it) } ?: ItemStack.EMPTY
    }

    // ---- A household moving house (townsfolk/Comings: Part D3) ----

    /**
     * A household's goods, heaped on its cart as it comes to a capital or leaves one: a chair, a chest, a bundle, a pot,
     * a cage and a rolled mattress. Each is an item of its own that no carter's load uses, so every client knows a
     * household's load for what it is ([household]) and draws each piece as itself (client/cart's CartClient.piece);
     * a bundle is heard set on the cart, and the load rattles as the cart rolls.
     */
    val CHAIR: ItemStack get() = ItemStack(Items.OAK_STAIRS)
    val CHEST: ItemStack get() = ItemStack(Items.CHEST)
    val BUNDLE: ItemStack get() = ItemStack(Items.BUNDLE)
    val POT: ItemStack get() = ItemStack(Items.DECORATED_POT)
    val CAGE: ItemStack get() = ItemStack(Items.IRON_BARS)
    val MATTRESS: ItemStack get() = ItemStack(Items.BED.pick(DyeColor.WHITE))

    /**
     * One of a household's goods, [stack], and the [place] on the bed it's heaped on (client/cart's HandcartMesh.SLOTS:
     * six on the floor, then two on top of them from [ON_TOP]).
     */
    class Placed(val place: Int, val stack: ItemStack)

    /** The first of the bed's places that rests on others (HandcartMesh.SLOTS: six on the floor, then two on top). */
    const val ON_TOP = 6

    /**
     * What a household of [people] brings, each piece with its place on the bed, in the order it's heaped on: the floor
     * first, then on top (and it's taken off the other way round). Each lies in its kind's place in client/cart's
     * HandcartMesh.HOUSEHOLD, as the previews heap a move: the chest, the pot and the hen's coop across the middle and
     * the front, the bundles at the back, the mattress and the chair riding on top. On top only over a full floor,
     * though, as with less under them they'd hang in the air: a smaller household's mattress lies across the front of
     * the bed (where a full heap has a sack), and someone on their own has their chair at the back.
     *
     * Someone on their own brings a chest, a rolled mattress, a chair and a bundle; two of them the pot and a second
     * bundle too, the chair riding on top; three or more a second mattress, on top, and the hen in her coop.
     */
    fun household(people: Int): List<Placed> = when {
        people >= 3 -> listOf(
            Placed(0, CHEST), Placed(1, POT), Placed(2, CAGE), Placed(3, MATTRESS), Placed(4, BUNDLE), Placed(5, BUNDLE),
            Placed(6, MATTRESS), Placed(7, CHAIR),
        )
        people == 2 -> listOf(Placed(0, CHEST), Placed(1, POT), Placed(3, MATTRESS), Placed(4, BUNDLE), Placed(5, BUNDLE), Placed(7, CHAIR))
        else -> listOf(Placed(0, CHEST), Placed(3, MATTRESS), Placed(4, CHAIR), Placed(5, BUNDLE))
    }

    /** What's heaped on [p]'s cart: [pieces], each on its own place, the rest of the bed bare. */
    fun heap(p: Townsperson, pieces: List<Placed>) {
        val cart = mine(p) ?: return
        cart.lay(Array(Handcart.SLOTS) { slot -> pieces.firstOrNull { it.place == slot }?.stack ?: ItemStack.EMPTY })
    }

    /** Whether [stack] is a piece of a household's load (see [household]). */
    fun household(stack: ItemStack): Boolean =
        stack.`is`(Items.OAK_STAIRS) || stack.`is`(Items.CHEST) || stack.`is`(Items.BUNDLE) || stack.`is`(Items.DECORATED_POT) ||
            stack.`is`(Items.IRON_BARS) || stack.`is`(Items.BED.pick(DyeColor.WHITE))

    /**
     * [p] is done with their cart for good (a household moved in has it emptied by the door): let go of where it
     * stands, it's nobody's from now on, and it goes once no one is looking.
     */
    fun leave(p: Townsperson) {
        val cart = mine(p) ?: return
        val level = p.level() as? ServerLevel ?: return
        if (cart.held || cart.waiting) cart.letGo(level)
        abandon(p, cart)
    }

    /** Takes every cart in [level] away (testing); how many there were. */
    fun clear(level: ServerLevel): Int {
        val all = (carts.values + strays).filter { it.level() === level && !it.isRemoved }
        all.forEach { it.discard() }
        return all.size
    }

    // ---- Keeping track ----

    /** Whether [cart] is one of ours: anything else (a stray copy, say) goes. */
    internal fun owns(cart: Handcart): Boolean {
        val p = cart.owner
        return p != null && carts[p.uuid] === cart || cart in strays
    }

    /** [cart] has gone from its level. */
    internal fun forget(cart: Handcart) {
        cart.owner?.let { if (carts[it.uuid] === cart) carts.remove(it.uuid) }
        strays.remove(cart)
    }

    private fun mine(p: Townsperson): Handcart? {
        val cart = carts[p.uuid] ?: return null
        if (cart.isRemoved || cart.level() !== p.level()) {
            carts.remove(p.uuid)
            return null
        }
        // Whoever they are this load (a townsperson unloaded and loaded again is a new one)
        cart.owner = p
        return cart
    }

    private fun abandon(p: Townsperson, cart: Handcart) {
        carts.remove(p.uuid)
        cart.owner = null
        cart.waiting = false
        strays.add(cart)
    }

    private fun tick(level: ServerLevel) {
        if (carts.isEmpty() && strays.isEmpty()) return
        // Switched off: the carts go (the carters carry their sacks instead)
        if (!enabled()) {
            clear(level)
            return
        }
        for (cart in carts.values.toList() + strays.toList()) {
            if (cart.isRemoved) forget(cart) else if (cart.level() === level) cart.step(level)
        }
    }

    /** A standable block at [at], or just above or below it. */
    private fun standing(level: Level, at: BlockPos): BlockPos? {
        for (dy in BAY_DY) {
            val pos = at.above(dy)
            if (Places.standable(level, pos)) return pos
        }
        return null
    }

    /** Further than this from its carter, a parked cart isn't fetched: they bring another. */
    private const val FAR = 32.0

    /** The bed's middle, ahead of the axle; and how far out to its side someone stands to load it. */
    private const val BED_MIDDLE = 0.15
    private const val BESIDE = 1.3
    private val ALONG = doubleArrayOf(0.0, 0.6, -0.6)

    /** Another cart this near a stop has it taken. */
    private const val TAKEN = 3.0

    private val BAY_STEPS = intArrayOf(3, -3, 4, -4, 5, -5)
    private val BAY_DY = intArrayOf(0, -1, 1)
}
