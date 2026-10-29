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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.abs

/**
 * What a cookshop's keeper does besides sell (living-world/cook/design.md 6.4): while a player is near and the shop is open, now
 * and then, they turn to the Hob and flip, to the Bake oven and draw the peel, to the Prep Board and slice, or cry their wares to the
 * square; at a counter with nothing to sell they shrug ("Sold out, I'm afraid.", the moment the last dish is fetched, or when
 * a player comes by a bare counter) and sit when there is a seat by. Each is a short part of the town's scene kind ([Aside]) played where
 * the keeper stands: they set the work down, do it, and take it up again, and a word from a player ends it. The clips the parts name
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
            // Portions sold and not yet fetched: the keeper is wanted at the counter for the shoppers, whose sales are the scene
            if (CookShops.shopOf(level, works)?.waiting?.isNotEmpty() == true) continue
            val keeper = CookShops.keeperOf(level, works) ?: continue
            if (keeper.isSleeping || keeper.distanceToSqr(Vec3.atCenterOf(stand)) > POST * POST || CookShoppers.serving(works.id)) continue
            if (!Aside.free(keeper, station = true)) continue
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
                Beat.FLIP -> lay.hob?.let { flip(it) }
                Beat.PEEL -> lay.oven?.let { peel(it) }
                Beat.SLICE -> lay.prep?.let { slice(it) }
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

    /** A turn to the Hob and two flips of whatever is on it. */
    private fun flip(hob: BlockPos): List<Move> {
        val at = sight(hob, 1.0)
        return listOf(Turn(at), Do(Activity.FLIP, at, emptyMap()), Do(Activity.FLIP, at, emptyMap()))
    }

    /** A turn to the Bake oven, its door opened and the peel drawn out, and the loaves set down. */
    private fun peel(oven: BlockPos): List<Move> {
        val at = sight(oven, 0.7)
        return listOf(Turn(at), Do(Activity.PEEL, at, emptyMap()))
    }

    /** A turn to the Prep Board and two cuts. */
    private fun slice(board: BlockPos): List<Move> {
        val at = sight(board, 1.0)
        return listOf(Turn(at), Do(Activity.SLICE, at, emptyMap()))
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

            /** The ticks the shopper waits for the keeper to turn, prepare the dish and turn back: the turn, the clip and a breath. */
            fun wait(prep: Prep): Int = TURN_MOST + prep.activity.ticks + 10
        }
    }

    /** The most ticks the keeper takes to turn to a station before the clip begins. */
    private const val TURN_MOST = 12

    /** The tick of the ladle's clip its pour is heard at: the clip's `pour` moment is 12.5 (client/townsfolk/LivingSchedule.MOMENTS). */
    private const val LADLE_POUR = 13

    /** The longest the keeper's part of one sale lasts, whatever becomes of the shopper. */
    private const val SERVE_MOST = 1500

    /**
     * The keeper's part of [sale], one move in the town's scene kind: they face the shopper, nod at their point, turn to the station and
     * prepare the dish (the clip and its sounds and puffs), turn back, take the coin, hand the dish across and nod at the parting. They
     * go by the stage the shopper's walk has come to, so the two stay together; when the sale is over, or the shopper has gone, or
     * the longest has passed, the move is done and the keeper takes up their work.
     */
    fun serve(sale: Sale): Move = Serve(sale)

    private class Serve(private val sale: Sale) : Move() {
        private var seen = -1
        private var began = 0
        private var prepAt = -1
        private var prepOver = false

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(Activity.NONE)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            val shopper = sale.shopper
            if (sale.ended || t > SERVE_MOST || shopper != null && (!shopper.isAlive || shopper.isRemoved)) return false
            val stage = sale.stage
            if (stage != seen) {
                seen = stage
                began = t
                if (shopper != null) when (stage) {
                    Sale.POINTING -> Acts.serve(p, shopper, Activity.NOD)
                    Sale.PAYING -> Acts.serve(p, shopper, Activity.RECEIVE, ItemStack(ModItems.GUILD_MARK.get()))
                    Sale.HANDING -> Acts.serve(p, shopper, Activity.HAND_OVER, sale.dish)
                    Sale.PARTING -> Acts.serve(p, shopper, Activity.NOD)
                    else -> {}
                }
            }
            val station = sale.prep.at(sale.lay)?.let { Jobs.top(it, 1.0) }
            val there = when {
                stage == Sale.PREPARING && !prepOver && station != null -> station
                shopper != null -> shopper.eyePosition
                else -> Jobs.top(sale.lay.door, 1.4)
            }
            p.lookAt(there)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            if (stage == Sale.PREPARING && !prepOver) prepare(p, there, t)
            return true
        }

        /**
         * Turns to the station, then plays the clip, and turns back. The slice, the carve and the flip are heard and seen at the
         * clips' own moments (client/cooking/TownKitchen.moment); the ladle's clip has no sound of its own, so the pour is played
         * here, at the clip's `pour` (12.5, the tick after).
         */
        private fun prepare(p: Townsperson, there: Vec3, t: Int) {
            if (prepAt < 0) {
                if (abs(Mth.degreesDifference(p.yBodyRot, p.yawTo(there))) <= 8f || t - began >= TURN_MOST) {
                    prepAt = t
                    p.act(sale.prep.activity)
                }
                return
            }
            val level = sale.level
            val at = sale.prep.at(sale.lay) ?: sale.lay.stand
            if (sale.prep == Prep.LADLE && t - prepAt == LADLE_POUR) {
                sound(level, at, "cook.pour", 0.8f)
                level.sendParticles(CookParticles.STEAM.get(), at.x + 0.5, at.y + 1.0, at.z + 0.5, 3, 0.12, 0.05, 0.12, 0.01)
            }
            if (t - prepAt >= sale.prep.activity.ticks) prepOver = true
        }

        override fun stop(p: Townsperson) {
            p.carried = ItemStack.EMPTY
            p.carriedUntil = 0
        }
    }
}
