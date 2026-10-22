package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Idle
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Sit
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Work
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * The town's shoppers at a cookshop (living-world/cook/design.md 6.4), modelled on the stall's ([io.github.vaspyyy.guildmark.economy.StallShoppers]).
 * What the shop sold at a selling hour waits in its record ([ShopRec.waiting]) until it is fetched: while a player is near enough to
 * see it, one of the capital's folk about the square comes up to the counter, looks the plates over, points, the keeper prepares
 * the dish (slices, flips, ladles or carves it, turned to the station they do it at: [CookshopScene.Serve]), the shopper pays, the keeper
 * hands the dish across, and the shopper takes it to a bench or stands to eat it; then the next. With nobody about to see it, or the
 * keeper not at the counter, what has been sold simply goes, and if no one comes for a long while it goes all the same. Nothing here
 * touches what the shop sells or earns: that was all settled at the selling hour.
 */
internal object CookShoppers {
    /** Ticks between looks at the shops. */
    private const val LOOK = 40

    /** How near a shop's counter a player has to be for shoppers to come to it; how far off a shopper may come from. */
    private const val WATCH = 32.0
    private const val FROM = 20.0

    /** Ticks a shopper may take over it before they give up, and ticks what has been sold waits for one before it simply goes. */
    private const val PATIENCE = 1200L
    private const val LINGER = 2400L

    /** How near its post the keeper must be to serve; how far from the counter the shopper looks for a seat to eat on. */
    private const val POST = 3.0
    private const val SEAT_FROM = 14

    /** Who shops: the folk with a household to keep. */
    private val SHOPPERS = setOf(TownRole.CITIZEN, TownRole.FARMER, TownRole.FISHER, TownRole.NOBLE)

    /** The ticks after a sale that came to nothing (the shopper could not get to the counter, or the keeper was called away) before another is tried. */
    private const val RETRY = 200L

    /** A shop's shopper, if one is on their way or at the counter, and the game time before which no other is sent. */
    private class Watch {
        var shopper: Townsperson? = null
        var shopping: Shopping? = null
        var retryAt = 0L
    }

    private val watched = HashMap<Int, Watch>()

    // What `cook perf` reads (CookStats): the sales at the counter since the server started
    private var handed = 0L
    private var gaveUp = 0L

    /** The dishes handed across the counter and the sales given up since the server started. */
    fun readout(): String = "$handed sales at the counter, $gaveUp given up"

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % LOOK == 0) look(it.server) }
        Cooking.onForget { watched.clear() }
    }

    /** Whether a shopper is on their way to the counter of works [id] or being served at it. */
    fun serving(id: Int): Boolean {
        val watch = watched[id] ?: return false
        val p = watch.shopper ?: return false
        return p.isAlive && p.director === watch.shopping
    }

    private fun look(server: MinecraftServer) {
        if (!Cooking.shops() || !Works.enabled()) return
        val level = server.overworld()
        val open = CookShops.isOpen(level)
        val now = level.gameTime
        for (works in WorksData.get(level).works.values) {
            if (works.kind != WorksKind.COOKSHOP || works.state != WorksState.DONE) continue
            val shop = CookShops.shopOf(level, works) ?: continue
            if (shop.waiting.isEmpty()) continue
            val lay = CookShops.layout(level, works.id)
            val seen = open && lay != null && level.isLoaded(lay.stand) && level.hasNearbyAlivePlayer(lay.stand.x + 0.5, lay.stand.y + 0.5, lay.stand.z + 0.5, WATCH)
            if (lay == null || !seen || now - shop.waitingSince > LINGER) {
                // Nobody to see them fetched, or nobody came: what was sold has gone all the same
                shop.waiting.clear()
                continue
            }
            if (serving(works.id)) continue
            val watch = watched.getOrPut(works.id) { Watch() }
            if (now >= watch.retryAt) send(level, works, shop, lay, watch)
        }
    }

    /**
     * Sends one of the capital's folk about the square to [lay]'s counter for the dish [shop] has waiting, and takes the keeper for their
     * part of the sale; false if nobody is free, the keeper is not at the counter and free, or there is nowhere to stand.
     */
    private fun send(level: ServerLevel, works: WorksRec, shop: ShopRec, lay: ShopLayout, watch: Watch): Boolean {
        val dish = shop.waiting.firstOrNull() ?: return false
        if (!level.isLoaded(lay.door) || !Places.standable(level, lay.door)) return false
        val keeper = CookShops.keeperOf(level, works) ?: return false
        if (keeper.isSleeping || keeper.distanceToSqr(Vec3.atCenterOf(lay.stand)) > POST * POST || !Aside.free(keeper, station = true)) return false
        val spot = Spot(lay.door, lay.front.opposite)
        val at = Vec3.atCenterOf(lay.door)
        val centre = BlockPos.of(works.capital)
        val p = level.getEntitiesOfClass(Townsperson::class.java, AABB(at, at).inflate(FROM, 5.0, FROM)) {
            it.isAlive && !it.isSleeping && it.director == null && it.role in SHOPPERS && !it.crowd && !it.bound && it.seat == null &&
                it.attending == null && Days.life(it)?.places?.centre == centre && Claims.free(level, lay.door, it)
        }.minByOrNull { it.distanceToSqr(at) } ?: return false
        val sale = CookshopScene.Sale(level, works, lay, dish.copy())
        sale.shopper = p
        // The keeper is at the counter, ready, before the shopper has walked to it
        val aside = Aside(listOf(CookshopScene.serve(sale)), reach = WATCH + 16.0, most = 1700)
        aside.onEnd = { sale.end() }
        if (!Aside.take(keeper, aside, station = true)) return false
        val shopping = Shopping(sale, shop, watch, spot, p, level.gameTime + PATIENCE)
        // Whatever they were at ends here, as when they are called away from it
        p.routine.interrupt()
        p.navigation.stop()
        p.act(Activity.NONE)
        p.director = shopping
        watch.shopper = p
        watch.shopping = shopping
        return true
    }

    /**
     * A shopper at the counter for [sale]'s dish: up to the counter, a look over the plates, a point, a wait while the keeper prepares
     * it, the coin and the dish handed across, a nod, and off with it to eat. Each stage is told to [sale], which the keeper goes by.
     */
    private class Shopping(
        private val sale: CookshopScene.Sale, private val shop: ShopRec, private val watch: Watch, spot: Spot, p: Townsperson, private val until: Long,
    ) : Director {
        private val level = sale.level
        private var taken = false
        private var started = false
        private val work = Work(
            p, spot, Jobs.top(sale.lay.plates.firstOrNull() ?: sale.lay.stand, 0.6),
            listOf(
                Step(Activity.BROWSE, 50 + p.random.nextInt(40)),
                Step(Activity.POINT),
                Step(Activity.NONE, CookshopScene.Sale.wait(sale.prep)),
                Step(Activity.PAY),
                Step(Activity.RECEIVE),
                Step(Activity.NOD),
            ),
            onTick = { step, ticks -> act(p, step.activity, ticks) },
        )

        override fun tick(p: Townsperson) {
            if (!started) {
                started = true
                work.start()
            }
            if (level.gameTime > until || sale.ended || !work.tick()) done(p)
        }

        /** A player's word comes first: the sale is given up (the keeper is told), and the talk takes them up from their day. */
        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            done(p)
            return false
        }

        private fun act(p: Townsperson, activity: Activity, ticks: Int) {
            if (ticks == 0) when (activity) {
                Activity.BROWSE -> sale.at(CookshopScene.Sale.LOOKING)
                Activity.POINT -> sale.at(CookshopScene.Sale.POINTING)
                Activity.NONE -> sale.at(CookshopScene.Sale.PREPARING)
                Activity.PAY -> sale.at(CookshopScene.Sale.PAYING)
                Activity.RECEIVE -> sale.at(CookshopScene.Sale.HANDING)
                Activity.NOD -> sale.at(CookshopScene.Sale.PARTING)
                else -> {}
            }
            when {
                activity == Activity.PAY && ticks == 6 -> level.playSound(null, sale.lay.door, StallDesk.TILL, SoundSource.NEUTRAL, 0.45f, 1.3f + p.random.nextFloat() * 0.2f)
                activity == Activity.RECEIVE && ticks == Acts.PASS -> take(p)
            }
        }

        /** The dish handed across: [p] has it, and the shop has one fewer waiting; the last one fetched from a shop with nothing left makes its keeper shrug. */
        private fun take(p: Townsperson) {
            taken = true
            handed++
            p.carried = sale.dish.copy()
            shop.waiting.removeFirstOrNull()
            // A dish fetched: the rest are given the same time again to be
            shop.waitingSince = level.gameTime
            if (shop.waiting.isEmpty() && !CookShops.stocked(level, sale.works)) CookshopScene.lastFetched(sale.works.id, level.gameTime)
        }

        /** Done, or given up: the keeper is told it is over, and they take the dish off to eat it, or go back to their day. */
        private fun done(p: Townsperson) {
            work.stop()
            if (p.director === this) p.director = null
            sale.end()
            if (!taken) {
                watch.retryAt = level.gameTime + RETRY
                gaveUp++
            }
            p.routine.interrupt()
            p.navigation.stop()
            if (taken) eating(p)?.let { p.routine.replace(it) }
        }

        /** A seat near the counter if there is one free, the dish in their hands, else eating it standing: then back to their day. */
        private fun eating(p: Townsperson): Task? {
            val life = Days.life(p) ?: return null
            val r = p.random
            val seat = Places.seat(level, sale.lay.door, SEAT_FROM, p)
            val meal: Task = if (seat != null) {
                val access = Places.access(level, seat, level.getBlockState(seat)) ?: seat
                Acts.via(p, life, access, Sit(p, seat, 500 + r.nextInt(300), listOf(Activity.SIT_EAT, Activity.SIT_EAT, Activity.SIT_EAT, Activity.SIT_TALK)))
            } else {
                Idle(p, 300 + r.nextInt(200), Activity.EAT, chatty = false)
            }
            return Acts.hold(p, sale.dish.copy()).andThen(meal).andThen(Acts.hold(p, ItemStack.EMPTY))
        }
    }
}
