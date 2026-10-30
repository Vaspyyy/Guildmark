package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Sit
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.MoverType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a cookshop's keeper does besides sell (living-world/cook/design.md 6.4): while a player is near and the shop is open, now
 * and then, they step along the alley to the Hob and flip, to the Bake oven and draw the peel, to the Prep Board and slice, and back
 * to the counter, or cry their wares to the square; at a counter with nothing to sell they shrug ("Sold out, I'm afraid.", the moment
 * the last dish is fetched, or when a player comes by a bare counter) and sit when there is a seat by. Each is a short part of the
 * town's scene kind ([Aside]) played from the keeper's post: they set the work down, do it, and take it up again, and a word from a
 * player ends it (a part broken off at a station has them step back to the counter at the next look: [Glide]). The clips the parts name
 * (`FLIP`, `SLICE`, `PEEL`) are Part I3's and M3's, and their sounds and puffs of steam and crumbs are played on each client at the
 * clips' own moments (client/cooking/TownKitchen.moment). The words are [ShopLines]'.
 *
 * Nothing here touches what the shop sells or earns, and nothing is scanned: a look every second at the few cookshops there are, and
 * only for the one a player is near. [Sale] and [Serve] are the keeper's part of a sale at the counter, which [CookShoppers] plays
 * with the shopper.
 */
internal object CookshopScene {
    /** Ticks between looks at the shops. */
    private const val EVERY = 20

    /** How near a shop's counter a player must be for its keeper to play: scenes are for being seen. */
    private const val SEEN = 28.0

    /** How near their post a keeper must be to be taken for a scene. */
    private const val POST = 3.0

    /** Ticks between one of a keeper's parts and the next, at least and as much more again, and between the shrugs at a bare counter. */
    private const val COOL = 900
    private const val COOL_MORE = 700
    private const val BARE_EVERY = 3600L

    /** How long after the last dish was fetched the keeper still shrugs at it. */
    private const val SOLD_FOR = 600L

    /** How far from their post a keeper looks for a seat. */
    private const val SIT_FROM = 6

    /** Per works: the game time of the keeper's next part, of their last shrug, and of the moment the last dish was fetched with none to follow. */
    private val next = HashMap<Int, Long>()
    private val shrugged = HashMap<Int, Long>()
    private val fetched = HashMap<Int, Long>()

    // What `cook perf` reads (CookStats): the parts keepers have played since the server started
    private var parts = 0L

    /** The parts the keepers have played (a cry, a flip, a peel, a slice, a shrug, a sigh) since the server started. */
    fun readout(): String = "$parts parts played by keepers"

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % EVERY == 0) look(it.server) }
        Cooking.onForget {
            next.clear()
            shrugged.clear()
            fetched.clear()
        }
    }

    /** The last dish a shopper fetched from works [id] was the last the shop had: the keeper shrugs at it. */
    fun lastFetched(id: Int, now: Long) {
        fetched[id] = now
    }

    private fun look(server: MinecraftServer) {
        if (!Cooking.shops() || !Works.enabled()) return
        val level = server.overworld()
        if (!CookShops.isOpen(level)) return
        val now = level.gameTime
        for (works in WorksData.get(level).works.values) {
            if (works.kind != WorksKind.COOKSHOP || works.state != WorksState.DONE) continue
            val lay = CookShops.layout(level, works.id) ?: continue
            val stand = lay.stand
            if (!level.isLoaded(stand) || !level.hasNearbyAlivePlayer(stand.x + 0.5, stand.y + 0.5, stand.z + 0.5, SEEN)) continue
            val waiting = CookShops.shopOf(level, works)?.waiting?.isNotEmpty() == true
            val keeper = CookShops.keeperOf(level, works) ?: continue
            if (keeper.isSleeping || keeper.distanceToSqr(Vec3.atCenterOf(stand)) > POST * POST || CookShoppers.serving(works.id)) continue
            if (!Aside.free(keeper, station = true)) continue
            // Left at a station (a part broken off by a word or a fright) or pushed along the alley: back to the counter first
            if (away(keeper, lay)) {
                Aside.take(keeper, Aside(listOf(Glide(home(lay), lay.front.toYRot(), sight(lay.door, 1.4))), reach = SEEN + 20.0, most = 200), station = true)
                continue
            }
            // Portions sold and not yet fetched: the keeper is wanted at the counter for the shoppers, whose sales are the scene
            if (waiting) continue
            val moves = choose(level, works, lay, keeper, now) ?: continue
            if (Aside.take(keeper, Aside(moves, reach = SEEN + 20.0, most = 600), station = true)) {
                next[works.id] = now + COOL + keeper.random.nextInt(COOL_MORE)
                parts++
            }
        }
    }

    /** What [keeper] does now, if anything: null for nothing yet (or already done another way). */
    private fun choose(level: ServerLevel, works: WorksRec, lay: ShopLayout, keeper: Townsperson, now: Long): List<Move>? {
        val stocked = CookShops.stocked(level, works)
        val last = fetched[works.id]
        if (last != null) {
            fetched.remove(works.id)
            if (!stocked && now - last <= SOLD_FOR) {
                shrugged[works.id] = now
                return shrug(lay)
            }
        }
        if (now < (next[works.id] ?: 0L)) return null
        if (!stocked) {
            if (now - (shrugged[works.id] ?: (Long.MIN_VALUE / 2)) >= BARE_EVERY) {
                shrugged[works.id] = now
                return shrug(lay)
            }
            if (keeper.random.nextInt(3) == 0 && sit(level, keeper, lay)) {
                next[works.id] = now + COOL + keeper.random.nextInt(COOL_MORE)
                return null
            }
            return idle(lay, keeper)
        }
        val order = when (keeper.random.nextInt(10)) {
            in 0..3 -> listOf(Beat.CRY, Beat.FLIP, Beat.PEEL, Beat.SLICE)
            in 4..5 -> listOf(Beat.FLIP, Beat.PEEL, Beat.SLICE, Beat.CRY)
            in 6..7 -> listOf(Beat.PEEL, Beat.SLICE, Beat.CRY, Beat.FLIP)
            else -> listOf(Beat.SLICE, Beat.CRY, Beat.FLIP, Beat.PEEL)
        }
        for (beat in order) {
            val moves = when (beat) {
                Beat.CRY -> cry(lay, keeper)
                Beat.FLIP -> lay.hob?.let { flip(level, lay, it) }
                Beat.PEEL -> lay.oven?.let { peel(level, lay, it) }
                Beat.SLICE -> lay.prep?.let { slice(level, lay, it) }
            }
            if (moves != null) return moves
        }
        return null
    }

    private enum class Beat { CRY, FLIP, PEEL, SLICE }

    // ---- The parts ----

    /** Where a keeper looks to see [pos]: the middle of the block, [up] blocks above its bottom. */
    private fun sight(pos: BlockPos?, up: Double = 1.0): () -> Vec3? = { pos?.let { Jobs.top(it, up) } }

    /** The cry: a turn to the square and a call, in the shop's words. Only where a cry would be heard and not talk over anyone. */
    private fun cry(lay: ShopLayout, keeper: Townsperson): List<Move>? {
        if (!canSpeak(keeper)) return null
        val at = sight(lay.door, 1.5)
        return listOf(Turn(at), Do(Activity.CALL_OUT, at, mapOf<Int, (Townsperson) -> Unit>(4 to { p: Townsperson -> speak(p, ShopLines.CRY) })))
    }

    /** The shrug at a bare counter, with "Sold out, I'm afraid." */
    private fun shrug(lay: ShopLayout): List<Move> {
        val at = sight(lay.door, 1.4)
        return listOf(Turn(at), Do(Activity.SHRUG, at, mapOf<Int, (Townsperson) -> Unit>(2 to { p: Townsperson -> speak(p, ShopLines.SOLD) })))
    }

    /** Nothing to sell and nothing to do: a sigh, a look at the square. */
    private fun idle(lay: ShopLayout, keeper: Townsperson): List<Move> {
        val at = sight(lay.door, 1.4)
        return listOf(Turn(at), Do(if (keeper.random.nextBoolean()) Activity.SIGH else Activity.ARMS_FOLDED, at, emptyMap(), ticks = 80))
    }

    // The flip, the peel and the slice are heard and seen at their clips' own moments, on each client that sees the keeper
    // (client/cooking/TownKitchen.moment): the sizzle, the oven door and its steam, the loaves set down, the knife and its
    // crumbs. The server adds none of its own, so none is heard twice or out of step with her hands.

    /** A step along the alley to the Hob, two flips of whatever is on it, and back. */
    private fun flip(level: ServerLevel, lay: ShopLayout, hob: BlockPos): List<Move> {
        val at = sight(hob, 1.0)
        return there(level, lay, hob, at, listOf(Do(Activity.FLIP, at, emptyMap()), Do(Activity.FLIP, at, emptyMap())))
    }

    /** A step to the Bake oven, its door opened and the peel drawn out, the loaves set down, and back. */
    private fun peel(level: ServerLevel, lay: ShopLayout, oven: BlockPos): List<Move> {
        val at = sight(oven, 0.7)
        return there(level, lay, oven, at, listOf(Do(Activity.PEEL, at, emptyMap())))
    }

    /** Two steps to the Prep Board, two cuts, and back. */
    private fun slice(level: ServerLevel, lay: ShopLayout, board: BlockPos): List<Move> {
        val at = sight(board, 1.0)
        return there(level, lay, board, at, listOf(Do(Activity.SLICE, at, emptyMap())))
    }

    /**
     * [work] done at [station]: a walk along the alley to the cell before it, turned to it, and a walk back to the counter after
     * (the Larder, behind the counter's middle, is only turned to); where that cell cannot be stood on, a turn to it from the counter.
     */
    private fun there(level: ServerLevel, lay: ShopLayout, station: BlockPos, at: () -> Vec3?, work: List<Move>): List<Move> {
        val cell = before(level, lay, station) ?: return listOf<Move>(Turn(at)) + work
        return listOf<Move>(Glide(spot(lay, station, cell), lay.front.opposite.toYRot(), at)) + work + Glide(home(lay), lay.front.toYRot(), sight(lay.door, 1.4))
    }

    // ---- Steps along the alley ----

    /** Ticks of a walk of [d] blocks along the alley: the step off and the step in, and eight ticks a block between them. */
    private fun walkTicks(d: Double): Int = if (d < 0.05) 0 else (6.0 + d * 8.0).roundToInt()

    /** The most ticks a walk of [d] blocks takes, with the turn at the end of it. */
    private fun walkMost(d: Double): Int = walkTicks(d) + TURN_MOST

    /** How far from the end of a walk they turn from the way they go to the way they are to face. */
    private const val TURN_IN = 0.45

    private fun flat(a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    /** The keeper's place: the middle of the cell behind the counter's middle, on its floor. */
    private fun home(lay: ShopLayout): Vec3 = Vec3.atBottomCenterOf(lay.stand)

    /** Whether [p] is off their place behind the counter (and still along the alley, where a walk takes them back to it). */
    private fun away(p: Townsperson, lay: ShopLayout): Boolean {
        val d = flat(p.position(), home(lay))
        return d > 0.15 && d < POST && abs(p.y - lay.stand.y) < 0.6
    }

    /**
     * Where the keeper stands to work at [station]: the alley's cell before it (the counter's middle itself for the Larder behind
     * it), if it and the alley's cells between it and the counter's middle can be stood on; null where not (a turn to it does).
     */
    private fun before(level: ServerLevel, lay: ShopLayout, station: BlockPos): BlockPos? {
        val cell = station.relative(lay.front)
        val stand = lay.stand
        val dx = cell.x - stand.x
        val dz = cell.z - stand.z
        if (cell.y != stand.y || dx != 0 && dz != 0 || abs(dx) + abs(dz) > 2) return null
        val steps = abs(dx) + abs(dz)
        for (k in 1..steps) if (!Places.standable(level, stand.offset(dx / steps * k, 0, dz / steps * k))) return null
        return cell
    }

    /** How far toward a station from the middle of the cell before it the keeper stands: Part I2's kitchens' hand's breadth (KitchenScene.Stands). */
    private const val TOWARD = 0.12

    /**
     * Where in [cell] the keeper stands to work at [station]: [TOWARD] the station, where the clips have their work (art/scripts/cook_clips.py,
     * NEAR), except at the Hob, whose clip is made from the middle of the cell, and at the counter's own middle (the Larder behind it).
     */
    private fun spot(lay: ShopLayout, station: BlockPos, cell: BlockPos): Vec3 {
        val mid = Vec3.atBottomCenterOf(cell)
        if (station == lay.hob || cell == lay.stand) return mid
        val d = lay.front.opposite
        return mid.add(d.stepX * TOWARD, 0.0, d.stepZ * TOWARD)
    }

    /**
     * A step or two along the alley behind the counter, to [to] (the middle of a cell), and a turn to face [yaw]: eased off and in at
     * walking pace, the body turned the way they go and then the way they are to face, the eyes on what [look] gives. They are moved
     * as anyone is ([MoverType.SELF]), so the counter and the stations stop them: nothing is walked through. Done when there and
     * turned, or a turn's ticks after they should have been. With nowhere to go it is only the turn.
     */
    private class Glide(private val to: Vec3, private val yaw: Float, private val look: () -> Vec3?) : Move() {
        private var from = to
        private var ticks = 0

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            if (!p.activity.overlay) p.act(Activity.NONE)
            from = p.position()
            ticks = walkTicks(flat(from, to))
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            if (t < ticks) {
                val f = (t + 1).toDouble() / ticks
                val e = f * f * (3.0 - 2.0 * f)
                val dx = from.x + (to.x - from.x) * e - p.x
                val dz = from.z + (to.z - from.z) * e - p.z
                if (dx * dx + dz * dz > 1.0E-6) p.move(MoverType.SELF, Vec3(dx, 0.0, dz))
            }
            val left = flat(p.position(), to)
            val heading = if (t < ticks && left > TURN_IN) (atan2(to.z - p.z, to.x - p.x) * (180.0 / Math.PI)).toFloat() - 90f else yaw
            p.face(Mth.approachDegrees(p.yBodyRot, heading, Aside.TURN))
            look()?.let { p.lookAt(it) }
            if (t >= ticks && (abs(Mth.degreesDifference(p.yBodyRot, yaw)) <= 4f || t >= ticks + TURN_MOST)) {
                p.face(yaw)
                return false
            }
            return true
        }
    }

    /** A sit on a seat near the post (a shop with nothing to sell), as a stall's keeper sits by theirs; false if there is none free. */
    private fun sit(level: ServerLevel, keeper: Townsperson, lay: ShopLayout): Boolean {
        val life = Days.life(keeper) ?: return false
        val seat = Places.seat(level, lay.stand, SIT_FROM, keeper) ?: return false
        val access = Places.access(level, seat, level.getBlockState(seat)) ?: seat
        keeper.routine.replace(
            Acts.via(keeper, life, access, Sit(keeper, seat, 700 + keeper.random.nextInt(500), listOf(Activity.SIT, Activity.SIT, Activity.SIT, Activity.SIGH))),
        )
        return true
    }

    // ---- Words and sounds ----

    /** Whether [p] can call out now: the street talk is on, nobody near (or [p] themselves) is saying something. */
    private fun canSpeak(p: Townsperson): Boolean = Words.on() && p.speech.isEmpty() && !Words.over(p)

    /** [p] says [family] in the shop's words, if it can be said without talking over anyone. */
    fun speak(p: Townsperson, family: String) {
        if (canSpeak(p)) ShopLines.say(p, family)
    }

    private fun sound(level: ServerLevel, pos: BlockPos, name: String, volume: Float) {
        level.playSound(null, pos, CookSounds.get(name), SoundSource.NEUTRAL, volume, 0.95f + level.random.nextFloat() * 0.1f)
    }

    /**
     * [activity] played facing what [at] gives, for its own length (or [ticks] if it loops), with [cues]: things done at a tick of it
     * (a sound, a puff of steam, a word).
     */
    private class Do(
        private val activity: Activity, private val at: () -> Vec3?, private val cues: Map<Int, (Townsperson) -> Unit>, private val ticks: Int = activity.ticks,
    ) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(activity)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            at()?.let { there ->
                p.lookAt(there)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            }
            cues[t]?.invoke(p)
            if (activity.loop && p.activity != activity && (p.activity.loop || p.actDone)) p.act(activity)
            return t < ticks
        }
    }

    // ---- A sale at the counter, the keeper's part ----

    /** What the keeper does to a dish while the shopper waits, and where: the station they turn to. */
    enum class Prep(val activity: Activity, val at: (ShopLayout) -> BlockPos?) {
        SLICE(Activity.SLICE, { it.prep }),
        FLIP(Activity.FLIP, { it.hob }),
        LADLE(Activity.LADLE, { it.larder }),
        CARVE(Activity.CARVE, { it.prep }),
        ;

        companion object {
            /** The preparation for a dish of [kind]: the pot's is ladled, the hob's flipped, the spit's carved and the oven's sliced. */
            fun of(kind: DishKind?): Prep = when (kind?.station) {
                Station.STEWPOT -> LADLE
                Station.HOB -> FLIP
                Station.SPIT -> CARVE
                else -> SLICE
            }
        }
    }

    /**
     * One sale at a cookshop's counter, shared by the shopper's walk ([CookShoppers]) and the keeper's moves ([Serve]): the stage it has
     * come to, which each of them goes by. The keeper is taken when the shopper is sent, so they are ready at the counter before it
     * is reached.
     */
    class Sale(val level: ServerLevel, val works: WorksRec, val lay: ShopLayout, val dish: ItemStack) {
        val prep: Prep = Prep.of(Dishes.kindOf(dish))

        /** Where the keeper stands to prepare it: in the alley's cell before its station; null for a turn to it from the counter. */
        internal val point: Vec3? = prep.at(lay)?.let { station -> before(level, lay, station)?.let { spot(lay, station, it) } }

        /**
         * The ticks the shopper waits while the keeper prepares it: the walk to the station and the turn to it, the clip, the walk back
         * and the turn to the shopper, and a breath; each walk at its longest, so the keeper is back at the counter for the coin.
         */
        val wait: Int = 2 * walkMost(point?.let { flat(home(lay), it) } ?: 0.0) + prep.activity.ticks + 10

        /** The shopper, once chosen. */
        var shopper: Townsperson? = null

        /** The stage the sale has come to (the `Sale.` constants). */
        var stage = COMING
            private set

        /** Whether the sale is over, however it ended. */
        var ended = false
            private set

        /** The game time the stage began. */
        var since = 0L
            private set

        fun at(next: Int) {
            if (ended) return
            stage = next
            since = level.gameTime
        }

        fun end() {
            ended = true
        }

        companion object {
            const val COMING = 0
            const val LOOKING = 1
            const val POINTING = 2
            const val PREPARING = 3
            const val PAYING = 4
            const val HANDING = 5
            const val PARTING = 6
        }
    }

    /** The most ticks the keeper takes to turn to a station (at the end of a walk to it, or from the counter) before the clip begins. */
    private const val TURN_MOST = 12

    /** The tick of the ladle's clip its pour is heard at: the clip's `pour` moment is 12.5 (client/townsfolk/LivingSchedule.MOMENTS). */
    private const val LADLE_POUR = 13

    /** The longest the keeper's part of one sale lasts, whatever becomes of the shopper; and the most it may run on after that, to walk back to the counter. */
    private const val SERVE_MOST = 1500
    private const val HOMEWARD = 60

    /**
     * The keeper's part of [sale], one move in the town's scene kind: they face the shopper, nod at their point, walk along the alley to
     * the station and prepare the dish there (the clip and its sounds and puffs), walk back, take the coin, hand the dish across and nod
     * at the parting. They go by the stage the shopper's walk has come to, so the two stay together, and a walk under way is walked to
     * its end before the next stage is met (nothing is handed across from a station); when the sale is over, or the shopper has gone,
     * or the longest has passed, they walk back to the counter if a station had them and the move is done, and they take up their work.
     */
    fun serve(sale: Sale): Move = Serve(sale)

    private class Serve(private val sale: Sale) : Move() {
        private var seen = -1
        private var prepAt = -1
        private var phase = READY
        private var walk: Glide? = null
        private var walkAt = 0
        private var homing = false

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(Activity.NONE)
            // Not at the counter's middle (a part broken off at a station): back there first, to meet the shopper
            if (away(p, sale.lay)) walk(p, a, 0, home(sale.lay), sale.lay.front.toYRot(), sight(sale.lay.door, 1.4))
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            val shopper = sale.shopper
            if (sale.ended || t > SERVE_MOST || shopper != null && (!shopper.isAlive || shopper.isRemoved)) return homeward(p, a, t)
            if (walking(p, a, t)) return true
            val stage = sale.stage
            if (stage != seen) {
                seen = stage
                if (shopper != null) when (stage) {
                    Sale.POINTING -> Acts.serve(p, shopper, Activity.NOD)
                    Sale.PAYING -> Acts.serve(p, shopper, Activity.RECEIVE, ItemStack(ModItems.GUILD_MARK.get()))
                    Sale.HANDING -> Acts.serve(p, shopper, Activity.HAND_OVER, sale.dish)
                    Sale.PARTING -> Acts.serve(p, shopper, Activity.NOD)
                    else -> {}
                }
            }
            if (stage == Sale.PREPARING && phase != DONE) {
                prepare(p, a, t)
                return true
            }
            val there = shopper?.eyePosition ?: Jobs.top(sale.lay.door, 1.4)
            p.lookAt(there)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            return true
        }

        /**
         * The walk to the station (or the turn to it), the clip there, and the walk back. The slice, the carve and the flip are heard
         * and seen at the clips' own moments (client/cooking/TownKitchen.moment); the ladle's clip has no sound of its own, so the pour
         * is played here, at the clip's `pour` (12.5, the tick after).
         */
        private fun prepare(p: Townsperson, a: Aside, t: Int) {
            val lay = sale.lay
            val station = sale.prep.at(lay)
            val sight = station?.let { Jobs.top(it, 1.0) }
            when (phase) {
                READY -> {
                    phase = OUT
                    val point = sale.point
                    when {
                        point != null -> walk(p, a, t, point, lay.front.opposite.toYRot()) { sight }
                        sight != null -> walk(p, a, t, p.position(), p.yawTo(sight)) { sight }
                        else -> arrived(p, t)
                    }
                }
                WORKING -> {
                    sight?.let {
                        p.lookAt(it)
                        p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(it), Aside.TURN))
                    }
                    val at = station ?: lay.stand
                    val level = sale.level
                    if (sale.prep == Prep.LADLE && t - prepAt == LADLE_POUR) {
                        sound(level, at, "cook.pour", 0.8f)
                        level.sendParticles(CookParticles.STEAM.get(), at.x + 0.5, at.y + 1.0, at.z + 0.5, 3, 0.12, 0.05, 0.12, 0.01)
                    }
                    if (t - prepAt >= sale.prep.activity.ticks) {
                        phase = BACK
                        walk(p, a, t, home(lay), lay.front.toYRot()) { sale.shopper?.eyePosition ?: Jobs.top(lay.door, 1.4) }
                    }
                }
                else -> {}
            }
        }

        /** A walk along the alley begun at tick [t] (its first tick played at once); when it is over at once, it has arrived. */
        private fun walk(p: Townsperson, a: Aside, t: Int, to: Vec3, yaw: Float, look: () -> Vec3?) {
            val w = Glide(to, yaw, look)
            w.start(p, a)
            walk = w
            walkAt = t
            if (!w.tick(p, a, 0)) {
                walk = null
                arrived(p, t)
            }
        }

        /** Whether a walk is under way (and is walked on); at its end, what follows it begins. */
        private fun walking(p: Townsperson, a: Aside, t: Int): Boolean {
            val w = walk ?: return false
            if (t == walkAt) return true
            if (w.tick(p, a, t - walkAt)) return true
            walk = null
            arrived(p, t)
            return false
        }

        /** At the end of a walk: at the station the clip begins; back at the counter the dish is ready. */
        private fun arrived(p: Townsperson, t: Int) {
            when (phase) {
                OUT -> {
                    phase = WORKING
                    prepAt = t
                    p.act(sale.prep.activity)
                }
                BACK -> phase = DONE
                else -> {}
            }
        }

        /** The sale is over: back to the counter's middle if a station had them (whatever walk was under way turned for it), then done. */
        private fun homeward(p: Townsperson, a: Aside, t: Int): Boolean {
            if (t > SERVE_MOST + HOMEWARD) return false
            if (!homing) {
                homing = true
                walk = null
                phase = DONE
                if (!away(p, sale.lay)) return false
                walk(p, a, t, home(sale.lay), sale.lay.front.toYRot(), sight(sale.lay.door, 1.4))
                return walk != null
            }
            val w = walk ?: return false
            return w.tick(p, a, t - walkAt)
        }

        override fun stop(p: Townsperson) {
            p.carried = ItemStack.EMPTY
            p.carriedUntil = 0
        }

        companion object {
            // The keeper's part of the preparation: not begun, walking to the station, at the clip, walking back, done
            private const val READY = 0
            private const val OUT = 1
            private const val WORKING = 2
            private const val BACK = 3
            private const val DONE = 4
        }
    }
}
