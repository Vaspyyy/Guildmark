package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.MarketTown
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Stalls
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillagePerf
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The pedlars alive in the world (the villages design, section 7.5): their bodies, made when a player is near a village he comes to
 * today and unmade when the day, the players or the switch say so. The day itself is a record ([VillageMarkets.decide], and the call
 * the market makes at nine); a body is only the record's body for the day, so nothing here is saved, and a village with no player
 * near has none.
 *
 * - **At most [MAX] at once, one to a village**, the nearest to a player first; one outside the budget is skipped for the day (his call
 *   still happens in the record).
 * - **Never in view.** A body is made only where no player could see it ([unseen]): at the road end, from which he walks in with his
 *   cart (late, with a word for it, if the day is already on), or, once nine has gone, at the cart itself if the green is out of sight.
 *   He is a passer-by of the crowds' kind ([Townsperson.crowd]), so he is never kept when a chunk is saved and goes when nobody looks,
 *   and is made only within [KEPT] blocks of a player, whom the crowds' rule keeps him for.
 * - **A killed pedlar** leaves his place empty for a week and another of the region's four is named ([VillageMarkets.slain]).
 *
 * The pedlar's director is given back to him whenever a customer's [io.github.vaspyyy.guildmark.economy.Serving] lets go, every tick.
 */
object Pedlars {
    /** Pedlars alive in the world at once. */
    const val MAX = 4

    /** How far from a player (blocks) a village is looked at for its pedlar. */
    private const val WATCH = 110

    /** Ticks between looks for a village that wants a body. */
    private const val SWEEP = 20

    /** A body is made only this near a player, so the crowds' rule keeps it (it keeps what is within 72). */
    private const val KEPT = 66.0

    /** Where the road end is, in blocks from the green, the one tried first and then nearer and further. */
    private val ROAD = intArrayOf(50, 44, 56, 62)

    /** Rounds of the road end's bearing tried (in eighths of a turn from the first). */
    private val TURNS = intArrayOf(0, 1, -1, 2, -2, 3, -3, 4)

    /** Ticks a village waits for a body after one vanished or gave up. */
    private const val COOL = 200L

    /** The body of a village's pedlar. */
    private class Body(val bell: Long, val day: Long, val p: Townsperson, val pedlar: Pedlar)

    private val bodies = HashMap<Long, Body>()
    private val cooling = HashMap<Long, Long>()

    // The ranges "unseen" means: close enough to be seen at all, far enough off to be too small to notice, and the cone and clear line between
    private const val CLOSE = 24.0
    private const val SEEN = 96.0
    private const val VIEW = 0.35

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { VillagePerf.timed(VillagePerf.Piece.PEDLARS) { tick(it.server) } }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            bodies.clear()
            cooling.clear()
            VillageStall.forget()
        }
    }

    /** Whether pedlars are to be about at all: the switch (and the markets it needs). */
    fun wanted(): Boolean = VillageMarkets.pedlars()

    /** The clock now, in hours from 2:00 to 26:00 (the scale of [PedlarRoster]'s hours). */
    fun clock(level: ServerLevel): Double = Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0

    // ---- Each tick ----

    private fun tick(server: MinecraftServer) {
        val sweep = server.tickCount % SWEEP == 7
        if (bodies.isEmpty() && !sweep) return
        val level = server.overworld()
        try {
            keep(level)
            if (sweep) look(level)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("A village pedlar could not be seen to", e)
        }
    }

    /** Every pedlar alive: his director given back if a customer's screen let go of him, the gone retired, and all sent off if the switch is off. */
    private fun keep(level: ServerLevel) {
        if (bodies.isEmpty()) return
        val on = wanted()
        for (body in bodies.values.toList()) {
            val p = body.p
            if (p.isRemoved) {
                retire(level, body)
                continue
            }
            if (!p.isAlive) continue
            if (p.director == null) {
                p.routine.interrupt()
                p.director = body.pedlar
            }
            if (!on) body.pedlar.dismiss(p)
        }
    }

    /** A body that is gone: the stall taken down, the village's place filled again or, if he was killed, left empty for a week. */
    private fun retire(level: ServerLevel, body: Body) {
        bodies.remove(body.bell)
        VillageStall.cart(body.bell, null)
        if (body.p.removalReason == Entity.RemovalReason.KILLED) {
            VillageMarkets.slain(level, body.bell, Days.dayOf(level.overworldClockTime))
        } else {
            cooling[body.bell] = level.gameTime + COOL
        }
    }

    /** Once a second: the villages near a player that have a pedlar today and no body for him yet get one, one a look at the most. */
    private fun look(level: ServerLevel) {
        if (!wanted() || bodies.size >= MAX) return
        val players = level.players().filter { it.isAlive && !it.isSpectator }
        if (players.isEmpty()) return
        val h = clock(level)
        // From a few minutes before the half hour he walks in (the road end is half a minute's walk from the green) until a quarter hour before he packs up
        if (h < PedlarRoster.WALK_IN - 0.1 || h >= PedlarRoster.PACK_UP - 0.25) return
        val day = Days.dayOf(level.overworldClockTime)
        val near = LinkedHashMap<Long, Pair<VillageRec, Double>>()
        for (player in players) {
            for (rec in Villages.near(level, player.blockPosition(), WATCH)) {
                val d = Villages.flat(player.x, player.z, rec.key)
                val have = near[rec.key]
                if (have == null || d < have.second) near[rec.key] = rec to d
            }
        }
        for ((rec, _) in near.values.sortedBy { it.second }) {
            if (bodies.size >= MAX) return
            if (rec.state != Live.LIVE || bodies.containsKey(rec.key)) continue
            if (cooling[rec.key]?.let { level.gameTime < it } == true) continue
            val places = Villages.places(level, rec.key) ?: continue
            val v = VillageMarkets.rec(level, rec.key) ?: continue
            // His day is decided once the market's hours have caught up with the clock; until then it is the record's alone
            if (Markets.hourOf(level) - v.entry.market.hour > 1) continue
            if (!VillageMarkets.decide(level, v, day).comes) continue
            if (spawn(level, rec, places, v, day, h)) return
        }
    }

    // ---- Making a body ----

    private fun spawn(level: ServerLevel, rec: VillageRec, places: VillagePlaces, v: VillageMarkets.Rec, day: Long, h: Double): Boolean {
        val green = places.market
        val person = PedlarRoster.of(rec.key, v.cart.slain)
        val angle = bearing(level, rec)
        val toward = Direction.getApproximateNearest(cos(angle), 0.0, sin(angle))
        // On a grand festival's day the cart stands at the ring's road side, not on the green (living-world/villages/design.md 10.3, part V8)
        val fair = io.github.vaspyyy.guildmark.village.live.VillageFeast.cartSpot(level, BlockPos.of(rec.key), angle)
        val bay = (fair?.let { bay(level, it, toward) } ?: bay(level, green, toward))?.takeIf { level.isPositionEntityTicking(it) } ?: return false
        val exit = approach(level, green, angle, hidden = false) ?: return false
        // Nine has gone and the green is out of sight: he is at his cart already, as the day has him
        if (h >= PedlarRoster.SET_UP + 0.1 && unseen(level, bay) && kept(level, bay)) {
            return make(level, rec, places, person, day, bay, exit, Pedlar.Stage.SETTLE, bay, late = false)
        }
        val start = approach(level, green, angle, hidden = true) ?: return false
        return make(level, rec, places, person, day, bay, exit, Pedlar.Stage.HITCH, start, late = h >= PedlarRoster.SET_UP + 0.5)
    }

    private fun make(
        level: ServerLevel, rec: VillageRec, places: VillagePlaces, person: PedlarRoster.Person, day: Long, bay: BlockPos, exit: BlockPos,
        stage: Pedlar.Stage, at: BlockPos, late: Boolean,
    ): Boolean {
        // Facing the way he is going: to the cart's bay, from the road end if he is set down at it already
        val from = if (stage == Pedlar.Stage.SETTLE) exit else at
        val yaw = Math.toDegrees(atan2(bay.z - from.z + 0.0, bay.x - from.x + 0.0)).toFloat() - 90f
        val p = Townsfolk.create(level, at, TownRole.MERCHANT, person.look, yaw, EntitySpawnReason.EVENT) ?: return false
        p.customName = Component.literal(person.name)
        // A passer-by of the crowds' kind: never kept when a chunk is saved, gone when nobody looks, and nobody's neighbour
        p.crowd = true
        p.leaveAt = Long.MAX_VALUE
        p.home = at
        p.life = Life(Resident.visitor(BlockPos.of(rec.key), TownRole.MERCHANT), places, null)
        val pedlar = Pedlar(rec.key, person, day, bay, exit, stage, late)
        p.director = pedlar
        if (!level.addFreshEntity(p)) return false
        bodies[rec.key] = Body(rec.key, day, p, pedlar)
        return true
    }

    /** The bearing of the road end from the green (radians): toward the village's capital if it is linked, else a way of the bell's own. */
    private fun bearing(level: ServerLevel, rec: VillageRec): Double {
        // Villages (V7): the links as VillageLinks keeps them (none with Village Wagons off)
        val link = io.github.vaspyyy.guildmark.village.live.links.VillageLinks.linked(level, rec.key).firstOrNull()
        if (link != null) {
            val to = BlockPos.of(link.capital)
            val x = BlockPos.getX(rec.key)
            val z = BlockPos.getZ(rec.key)
            if (to.x != x || to.z != z) return atan2((to.z - z).toDouble(), (to.x - x).toDouble())
        }
        return Random(rec.key * -0x61c8864680b583ebL).nextDouble() * 2.0 * Math.PI
    }

    /** Where the cart stands at the green: a bay near the middle of it, on the road's side first. */
    private fun bay(level: ServerLevel, green: BlockPos, toward: Direction): BlockPos? {
        for (out in listOf(toward, toward.clockWise, toward.counterClockWise, toward.opposite)) {
            Handcarts.bay(level, green, out)?.let { return it }
        }
        return stand(level, green)
    }

    /**
     * A road end: ground to stand on [ROAD] blocks out from [green] along the bearing [angle] (or a turn either side of it, when that
     * is water or unloaded). Where [hidden], one nobody could see and within the crowds' keeping of a player; otherwise anywhere that is
     * ground, for him to leave by.
     */
    private fun approach(level: ServerLevel, green: BlockPos, angle: Double, hidden: Boolean): BlockPos? {
        for (turn in TURNS) {
            val a = angle + turn * Math.PI / 4.0
            for (r in ROAD) {
                val at = ground(level, green.x + (cos(a) * r).roundToInt(), green.z + (sin(a) * r).roundToInt(), green.y) ?: continue
                if (!level.isPositionEntityTicking(at)) continue
                if (hidden && !(kept(level, at) && unseen(level, at))) continue
                return at
            }
        }
        return null
    }

    /** Ground to stand on at [x], [z], within a few blocks of [y]; null if the chunk isn't loaded, or it's water or steep. */
    private fun ground(level: ServerLevel, x: Int, z: Int, y: Int): BlockPos? {
        if (!level.hasChunk(x shr 4, z shr 4)) return null
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        for (dy in intArrayOf(0, 1, -1, 2, -2)) {
            val at = BlockPos(x, top + dy, z)
            if (Math.abs(at.y - y) > 14) return null
            if (Places.standable(level, at) && level.getBlockState(at.below()).fluidState.isEmpty) return at
        }
        return null
    }

    private fun stand(level: ServerLevel, at: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1, 2, -2)) {
            val pos = at.above(dy)
            if (Places.standable(level, pos)) return pos
        }
        return null
    }

    /** Further on along the road from [exit], away from [bay], for a pedlar still in sight at the road end. */
    fun beyond(level: ServerLevel, exit: BlockPos, bay: BlockPos): BlockPos? {
        val dx = (exit.x - bay.x).toDouble()
        val dz = (exit.z - bay.z).toDouble()
        val len = hypot(dx, dz).coerceAtLeast(1.0)
        for (r in intArrayOf(30, 20, 40, 12)) {
            ground(level, exit.x + (dx / len * r).roundToInt(), exit.z + (dz / len * r).roundToInt(), exit.y)?.let { return it }
        }
        return null
    }

    /** Whether nobody could see [at] now: no player within [CLOSE] blocks, nor one within [SEEN] looking its way with a clear line to it. */
    private fun unseen(level: ServerLevel, at: BlockPos): Boolean {
        val spot = Vec3.atCenterOf(at).add(0.0, 0.5, 0.0)
        for (player in level.players()) {
            val d = player.distanceToSqr(spot)
            if (d < CLOSE * CLOSE) return false
            if (d > SEEN * SEEN) continue
            val eye = player.eyePosition
            if (player.getViewVector(1f).dot(spot.subtract(eye).normalize()) < VIEW) continue
            if (level.clip(ClipContext(eye, spot, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS) return false
        }
        return true
    }

    /** Whether a player is near enough [at] for the crowds' rule to keep a body made there. */
    private fun kept(level: ServerLevel, at: BlockPos): Boolean {
        val spot = Vec3.atCenterOf(at)
        return level.players().any { it.isAlive && !it.isSpectator && it.distanceToSqr(spot) < KEPT * KEPT }
    }

    // ---- What the bodies ask ----

    /**
     * The goods heaped on a pedlar's cart on [day]: the call's own ([taken]: what it took away), the greatest first, a piece for each good
     * and no more than the bed holds on its floor. Before the call is made at nine, the load it will bring.
     */
    fun pieces(level: ServerLevel, bell: Long, day: Long, taken: Boolean = false): List<ItemStack> {
        val v = VillageMarkets.rec(level, bell) ?: return emptyList()
        val made = v.cart.called == day
        val goods: Map<String, Double> = when {
            taken -> if (made) v.cart.took else emptyMap()
            made -> v.cart.brought
            else -> {
                val inn = VillageMarkets.preview(v, Markets.hourOf(level))
                Good.entries.filter { inn[it.ordinal] > 0.0 }.associate { it.id to inn[it.ordinal] }
            }
        }
        return goods.entries.sortedByDescending { it.value }.mapNotNull { e -> Good.byId(e.key)?.let { Handcarts.stack(it) } }
            .filter { !it.isEmpty }.distinctBy { it.item }.take(Handcarts.ON_TOP)
    }

    /**
     * What the pedlar cries to the street ([pick] a number from 0 to 3): two of the goods the day's call brought that the village still
     * has, the most first ("Candles and coal, fresh from the road!"), else one, else a cry that names no good; so a good is cried only
     * while it is really on the cart. Before the call is made, and when it brought nothing, the cries that name none.
     */
    fun cry(level: ServerLevel, bell: Long, day: Long, pick: Int): String {
        val v = VillageMarkets.rec(level, bell)
        val m = v?.entry?.market
        val goods = if (v == null || m == null || v.cart.called != day) emptyList() else v.cart.brought.entries.sortedByDescending { it.value }
            .mapNotNull { Good.byId(it.key) }.filter { m.stock(it) >= 1.0 }
        fun named(g: Good) = g.key + ".many"
        return when {
            pick < 2 && goods.size >= 2 -> Speech.of("pedlar_call_goods", named(goods[0]), named(goods[1]))
            pick < 2 && goods.size == 1 -> Speech.of("pedlar_call_good", named(goods[0]))
            pick <= 2 -> "pedlar_call_look"
            else -> "pedlar_call_road"
        }
    }

    /** Whether the market's record has made the day's call yet. */
    fun called(level: ServerLevel, bell: Long, day: Long): Boolean = VillageMarkets.rec(level, bell)?.cart?.called == day

    /** Sets up the pedlar's stall at the green of [bell]: his spot at the cart's side, the customers' across the bed, the cart the counter. */
    fun openStall(level: ServerLevel, bell: Long, p: Townsperson) {
        val keeper = p.blockPosition()
        val bed = Handcarts.bed(p)
        val toward = if (bed != null) Direction.getApproximateNearest(bed.x - p.x, 0.0, bed.z - p.z) else p.direction
        val counter = if (bed != null) BlockPos.containing(bed.x, p.y + 0.2, bed.z) else keeper.relative(toward)
        val across = counter.relative(toward)
        val customer = listOf(across, across.above(), across.below(), keeper.relative(toward.opposite)).firstOrNull { Places.standable(level, it) } ?: across
        p.post = keeper
        p.postFacing = toward
        // A stall of the market hall's kind: a cartography table, which is no shop's of its own (Shop.of), is the hall's
        VillageStall.cart(
            bell,
            CityPlaces.Stall(VillageStall.CART, Spot(keeper, toward), Spot(customer, toward.opposite), listOf(counter), listOf(counter.above()), M.CARTOGRAPHY_TABLE),
        )
    }

    fun closeStall(bell: Long) {
        VillageStall.cart(bell, null)
    }

    /**
     * A player used [p]. Null if he is no pedlar; false if he is one and nothing opens (the stalls are off); true if it was dealt with: the
     * market hall's screen at the village's own market while he minds his cart, and a word, as he is walking in or packing up.
     */
    fun counter(level: ServerLevel, player: ServerPlayer, p: Townsperson): Boolean? {
        val body = bodies.values.firstOrNull { it.p === p } ?: return null
        if (!Markets.enabled() || player.isSpectator) return false
        if (!body.pedlar.open) {
            p.attend(player, 60)
            p.say(if (body.pedlar.leaving) "pedlar_pack" else "pedlar_hello", force = true)
            return true
        }
        val entry = VillageMarkets.find(level, body.bell) ?: return false
        val places = Villages.places(level, body.bell) ?: return false
        Stalls.open(player, Shop.HALL, entry, MarketTown.of(places), p)
        return true
    }

    /** He cannot do what he came for (no room for his cart): off, and the village's place is filled again a little later. */
    fun giveUp(pedlar: Pedlar, p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        p.leaveAt = level.gameTime
        if (!unseen(level, p.blockPosition())) return
        p.discard()
    }

    // ---- For the commands ----

    /** The pedlar of the village [bell] now: who he is and what he is doing, if he has a body. */
    fun present(bell: Long): Pair<PedlarRoster.Person, String>? =
        bodies[bell]?.takeIf { !it.p.isRemoved }?.let { it.pedlar.person to it.pedlar.doing }

    /** How many pedlars are alive. */
    fun alive(): Int = bodies.values.count { !it.p.isRemoved }

    /** Sends every pedlar off (the command `village cart clear`): they go the way they came. */
    fun dismissAll() {
        for (body in bodies.values) if (!body.p.isRemoved) body.pedlar.dismiss(body.p)
    }
}
