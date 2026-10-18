package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Work
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.floor

/**
 * The town's shoppers at players' own stalls. What a stall sells in a market hour (see [OwnStalls.sell]) stays heaped
 * on its display until it's fetched ([OwnStall.waiting]): while a player is near enough to see it, one of the
 * capital's folk about the hall comes up to the table, looks the goods over, points, pays and goes off with them, and
 * the heap goes down as they take it; then the next. With nobody about to see it, what's been sold simply goes, and
 * if no one comes for a long while it goes all the same. Nothing here touches what a stall sells or earns: that's
 * all settled in the market's hour.
 */
object StallShoppers {
    /** Ticks between looks at the let tables. */
    private const val LOOK = 40

    /** How near a table a player has to be for shoppers to come to it; how far off a shopper may come from. */
    private const val WATCH = 32.0
    private const val FROM = 20.0

    /** Ticks a shopper may take over it before they give up, and ticks what's been sold waits for one before it simply goes. */
    private const val PATIENCE = 1200L
    private const val LINGER = 2400L

    /** The most of a good one shopper takes away. */
    private const val BASKET = 12.0

    /** Who shops: the folk with a household to keep. */
    private val SHOPPERS = setOf(TownRole.CITIZEN, TownRole.FARMER, TownRole.FISHER, TownRole.NOBLE)

    /** A table's shopper, if one's on their way or at it, and since when what its stall sold has waited. */
    private class Watch {
        var shopper: Townsperson? = null
        var shopping: Shopping? = null
        var since = -1L
    }

    private val watched = HashMap<BlockPos, Watch>()

    /** Starts the looks with the server's ticks, and forgets them when it stops. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % LOOK == 0) look(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> watched.clear() }
    }

    private fun look(server: MinecraftServer) {
        if (!OwnStalls.enabled()) return
        val level = server.overworld()
        val data = OwnStalls.get(level)
        val now = level.gameTime
        val let = HashSet<BlockPos>()
        for (hall in data.all()) for (stall in hall.stalls) {
            if (stall == null) continue
            val t = StallTables.of(stall) ?: continue
            let.add(t.display)
            if (!level.isLoaded(t.display)) continue
            val watch = watched.getOrPut(t.display) { Watch() }
            val waiting = stall.waiting.sum()
            if (waiting < 1.0) {
                watch.since = -1L
                continue
            }
            val seen = level.hasNearbyAlivePlayer(t.middle.x, t.middle.y, t.middle.z, WATCH)
            if (!seen || watch.since >= 0 && now - watch.since > LINGER) {
                // Nobody to see them fetched, or nobody came: they're gone all the same
                gone(level, t, stall, watch)
                continue
            }
            if (watch.since < 0) watch.since = now
            val busy = watch.shopper?.let { it.isAlive && it.director === watch.shopping } ?: false
            if (!busy && send(level, t, stall, watch)) watch.since = now
        }
        watched.keys.retainAll(let)
    }

    /** What [stall] sold goes without anyone seen fetching it, and its display shows what's left on it. */
    private fun gone(level: ServerLevel, t: StallTables.Table, stall: OwnStall, watch: Watch) {
        stall.waiting.fill(0.0)
        watch.since = -1L
        StallTables.sync(level, t, stall)
    }

    /**
     * Sends one of the capital's folk about the hall to [t] for what [stall] has sold; false if nobody's free or
     * there's nowhere to stand.
     */
    private fun send(level: ServerLevel, t: StallTables.Table, stall: OwnStall, watch: Watch): Boolean {
        val good = Good.TRADED.filter { it.item != null && stall.waiting[it.ordinal] > 0.0 }.maxByOrNull { stall.waiting[it.ordinal] } ?: return false
        val spot = spot(level, t) ?: return false
        val centre = t.capital.center
        val p = level.getEntitiesOfClass(Townsperson::class.java, AABB(t.middle, t.middle).inflate(FROM, 5.0, FROM)) {
            it.isAlive && !it.isSleeping && it.director == null && it.role in SHOPPERS && !it.crowd && !it.bound && it.seat == null &&
                it.attending == null && Days.life(it)?.places?.capital?.center == centre && Claims.free(level, spot.pos, it)
        }.minByOrNull { it.distanceToSqr(t.middle) } ?: return false
        val shopping = Shopping(level, t, stall, good, spot, p, level.gameTime + PATIENCE)
        // Whatever they were at ends here, as when they're called away from it
        p.routine.interrupt()
        p.navigation.stop()
        p.act(Activity.NONE)
        p.director = shopping
        watch.shopper = p
        watch.shopping = shopping
        return true
    }

    /** Where a shopper stands at [t]: on the floor in front of it, or beside that, facing the table. */
    private fun spot(level: ServerLevel, t: StallTables.Table): Spot? {
        val front = t.display.below().relative(t.front)
        val tries = listOf(front, front.relative(t.front.clockWise), front.relative(t.front.counterClockWise), t.display.relative(t.front))
        val at = tries.firstOrNull { level.isLoaded(it) && Places.standable(level, it) } ?: return null
        return Spot(at, t.front.opposite)
    }

    /**
     * A shopper at [t] for [good]: up to the table, a look over the goods, a point, the coin and the goods handed
     * across (the heap going down as they're taken), a nod, and back to their day.
     */
    private class Shopping(
        private val level: ServerLevel, private val t: StallTables.Table, private val stall: OwnStall, private val good: Good,
        spot: Spot, p: Townsperson, private val until: Long,
    ) : Director {
        private val work = Work(
            p, spot, Jobs.top(t.display, 0.6),
            listOf(Step(Activity.BROWSE, 50 + p.random.nextInt(40)), Step(Activity.POINT), Step(Activity.PAY), Step(Activity.RECEIVE), Step(Activity.NOD)),
            onTick = { step, ticks -> act(p, step.activity, ticks) },
        )
        private var started = false

        override fun tick(p: Townsperson) {
            if (!started) {
                started = true
                work.start()
            }
            if (level.gameTime > until || !work.tick()) done(p)
        }

        private fun act(p: Townsperson, activity: Activity, ticks: Int) {
            when {
                activity == Activity.PAY && ticks == 6 -> level.playSound(null, t.display, StallDesk.TILL, SoundSource.NEUTRAL, 0.45f, 1.3f + p.random.nextFloat() * 0.2f)
                activity == Activity.RECEIVE && ticks == 12 -> take(p)
            }
        }

        /** The goods handed across: [p] has them, and the stall's display shows that many fewer. */
        private fun take(p: Townsperson) {
            val item = good.item ?: return
            p.carried = ItemStack(item)
            val waiting = stall.waiting[good.ordinal]
            stall.waiting[good.ordinal] = if (waiting <= BASKET) 0.0 else waiting - floor(BASKET)
            // A crumb of a good is fetched along with the rest
            if (stall.waiting.sum() < 1.0) stall.waiting.fill(0.0)
            StallTables.sync(level, t, OwnStalls.get(level).find(t.capital.center.asLong())?.stalls?.getOrNull(t.index))
        }

        /** Done, or given up: back to their day. */
        private fun done(p: Townsperson) {
            work.stop()
            if (p.director === this) p.director = null
            p.routine.interrupt()
            p.navigation.stop()
        }
    }
}
