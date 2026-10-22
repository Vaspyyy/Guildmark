package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Route
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Phase
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Ways
import io.github.vaspyyy.guildmark.townsfolk.Work
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The bread queue (living-world/design-influence.md section 5): with bread short of a morning while the ovens are
 * baking or flour is on its way (the market's record: no loaves on the shelves, or some of the town gone without
 * today), the shoppers who'd have gone for a loaf line up at their bakery's door instead ([join], from
 * [io.github.vaspyyy.guildmark.townsfolk.Acts.shop]), and passers-by out for nothing much join them. They wait their
 * turn grumbling ("There'd better be bread left when I get in."), and as the loaves come out (the shelves stocked
 * again) the one at the head goes in and buys one over the counter, the baker handing it over (HAND_OVER), and takes
 * it home. When the morning's gone with no bread, the line breaks up with a shrug. One line to a bakery.
 */
internal object BreadQueue {
    /** The most in one line, the most drawn in from the street, and how near the door a passer-by must pass. */
    private const val ROOM = 8
    private const val DRAWN = 5
    private const val PULL = 24.0

    /**
     * How near a player must be to the bakery, the fewest ticks between one passer-by joining and the next, and how
     * many come over at once when a player first comes by (a line that's been forming all morning, caught up with).
     */
    private const val SEEN = 48.0
    private const val PULL_EVERY = 200L
    private const val FIRST = 3

    /** How near the player the scene command's bakery must be. */
    private const val SCENE = 64.0

    /** The longest anyone waits (a little more for some), and the longest the one being served is waited for. */
    private const val PATIENCE = 2400
    private const val IN_SHOP = 600L

    private class Line(val bakery: Int, val door: CityPlaces.Door, val box: BoundingBox, val queue: Queue) {
        var pulled = Long.MIN_VALUE / 2
        var looked = Long.MIN_VALUE

        /** For `/guildmark capital scene queue`: when the loaves come out whatever the shelves say; -1 for the record's way. */
        var opens = -1L

        /** The one gone in to be served, and when: one in the shop at a time, the next going in once they're out. */
        var inside: Townsperson? = null
        var wentIn = Long.MIN_VALUE / 2

        /** Whether the last one served is out of the shop again (or long enough gone in that they're not coming out this way). */
        fun clear(now: Long): Boolean {
            val who = inside ?: return true
            if (!who.isAlive || who.isRemoved || now - wentIn > IN_SHOP) {
                inside = null
                return true
            }
            if (box.isInside(who.blockPosition()) || who.blockPosition().distSqr(door.pos) <= 4) return false
            inside = null
            return true
        }
    }

    private val lines = HashMap<Long, Line>()

    private val BAKERIES = setOf(Kind.BAKERY.name)

    /** The clock's hour, 0 at midnight. */
    private fun clock(level: ServerLevel): Int = Math.floorMod(Math.floorDiv(level.overworldClockTime, 1000L) + 6, 24L).toInt()

    private fun market(level: ServerLevel, places: TownPlaces): Market? =
        if (Markets.enabled()) Markets.find(level, places.centre.asLong())?.market else null

    /** Whether bread is short at [m] this morning while the ovens are baking or flour is on its way: the queue's cause. */
    fun short(level: ServerLevel, m: Market): Boolean {
        val h = clock(level)
        if (h < 6 || h > 10) return false
        val bread = m.stock(Good.BREAD) < 1.0 || m.today(Tally.SHORT, Good.BREAD) > 0.0
        val coming = m.busy(Maker.OVENS) || m.busy(Maker.MILL) || m.carted[Route.FLOUR.ordinal] > 0.0
        return bread && coming
    }

    /** The bakery [p] buys their bread at: the one nearest home (nearest them, for a visitor), as Trades.loaf has it. */
    private fun bakery(p: Townsperson, life: Life): CityPlaces.Lodging? =
        life.places.nearest(life.home?.box?.center ?: p.blockPosition(), BAKERIES).firstOrNull { it.front != null }

    /** The line at [bakery]'s door, laid out along its front if there's none yet. */
    private fun line(level: ServerLevel, bakery: CityPlaces.Lodging): Line? {
        val door = bakery.front ?: return null
        val key = door.pos.asLong()
        lines[key]?.let { return it }
        val spots = Queue.lay(level, door.outside, door.inward.opposite, ROOM, open = false) ?: return null
        val toward = Vec3(door.pos.x + 0.5, door.pos.y + 1.4, door.pos.z + 0.5)
        return Line(bakery.id, door, bakery.box, Queue(door.outside, spots, toward)).also { lines[key] = it }
    }

    /**
     * From [io.github.vaspyyy.guildmark.townsfolk.Acts.shop]: with bread short this morning, half the shoppers join the
     * line at their bakery's door, to buy a loaf when it's their turn; null otherwise (the streets' scenes off, bread to
     * be had, the line full). Asks nothing of the world, and draws nothing at random, with the streets' scenes off.
     */
    fun join(p: Townsperson, life: Life): Task? {
        if (!Fortunes.enabled()) return null
        val level = p.level() as? ServerLevel ?: return null
        val m = market(level, life.places) ?: return null
        if (!short(level, m) || p.random.nextInt(2) != 0) return null
        val bakery = bakery(p, life) ?: return null
        val line = line(level, bakery) ?: return null
        line.queue.prune()
        if (line.queue.full) return null
        line.queue.over = false
        return queueing(level, p, life, line, home = false)
    }

    /**
     * A place in [line] for [p]: their turn when the loaves are out (the shelves stocked again) and the one before them
     * is out of the shop, the loaf bought over the counter then ([Trades.loaf]: the baker hands it over), and (if
     * [home]) taken home after.
     */
    private fun queueing(level: ServerLevel, p: Townsperson, life: Life, line: Line, home: Boolean): Queueing = Queueing(
        p, line.queue, PATIENCE + p.random.nextInt(1200),
        ready = {
            line.clear(level.gameTime) &&
                if (line.opens >= 0) level.gameTime >= line.opens else market(level, life.places)?.let { it.stock(Good.BREAD) >= 1.0 } ?: true
        },
        served = { who ->
            // The scene command's line buys at its own bakery, the loaves out whatever the shelves say
            val buy = if (line.opens >= 0) bought(level, who, life, line) else Trades.loaf(who, life)
            if (buy != null) {
                line.inside = who
                line.wentIn = level.gameTime
            }
            buy?.let { InScene(who, if (home) it.andThen(Later(who) { home(who, life) }) else it) }
        },
        words = { who -> if (who.role == TownRole.CHILD) "child.hungry" else "streets.queue" },
        why = "in the bread queue",
    )

    /**
     * For the scene command's line (whoever was free nearby, whatever bakery they'd go to, with the loaves out
     * whatever the shelves say): a loaf bought at [line]'s own bakery, as [Trades.loaf] buys one. In at the door to
     * the counter, a look at the shelf, the coin across, and the loaf handed over by the baker if they're in.
     */
    private fun bought(level: ServerLevel, p: Townsperson, life: Life, line: Line): Task? {
        val door = line.door
        val counter = counter(level, door)
        val step = counter?.relative(door.inward.opposite) ?: door.inside.relative(door.inward)
        val stand = if (level.isLoaded(step) && Places.standable(level, step)) step else door.inside
        if (!level.isLoaded(stand) || !Claims.free(level, stand, p)) return null
        val shelf = Jobs.top(counter ?: stand.relative(door.inward, 2), 1.0)
        var baker: Townsperson? = null
        val steps = listOf(Step(Activity.BROWSE, 40), Step(Activity.POINT), Step(Activity.PAY), Step(Activity.RECEIVE), Step(Activity.NOD))
        val buying = Work(p, Spot(stand, door.inward), shelf, steps, onTick = { s, t ->
            if (t == 0) when (s.activity) {
                Activity.POINT -> {
                    baker = level.getEntitiesOfClass(Townsperson::class.java, AABB.of(line.box)) { it.role == TownRole.BAKER && it.isAlive && !it.isSleeping }.firstOrNull()
                    Acts.serve(baker, p, Activity.NOD)
                }
                Activity.PAY -> Acts.serve(baker, p, Activity.RECEIVE, ItemStack(ModItems.GUILD_MARK.get()))
                Activity.RECEIVE -> Acts.serve(baker, p, Activity.HAND_OVER, ItemStack(Items.BREAD))
                else -> {}
            }
            if (s.activity == Activity.RECEIVE && t == Acts.PASS) p.carried = ItemStack(Items.BREAD)
        })
        return Ways.to(p, life.places, stand, Jobs.WALK, near = 1.0).andThen(buying)
    }

    /** The bakery's counter, as Trades has it: a block a few steps in from [door] with room to stand either side. */
    private fun counter(level: ServerLevel, door: CityPlaces.Door): BlockPos? {
        for (k in 2..4) {
            val c = door.inside.relative(door.inward, k)
            if (!level.isLoaded(c)) return null
            if (Places.free(level, c, feet = true)) continue
            return c.takeIf { Places.standable(level, it.relative(door.inward.opposite)) && Places.standable(level, it.relative(door.inward)) }
        }
        return null
    }

    /** The loaf taken home in the basket and put down there. */
    private fun home(p: Townsperson, life: Life): Task? {
        if (p.carried.isEmpty) return null
        val door = life.home?.front ?: return Acts.hold(p, ItemStack.EMPTY)
        return Ways.to(p, life.places, door.inside, Trades.LADEN, overlay = Activity.CARRY_BASKET)
            .andThen(Acts.gesture(p, Activity.PUT_DOWN)).andThen(Acts.hold(p, ItemStack.EMPTY))
    }

    /**
     * Once a second for each player in a capital: the lines near them kept (broken up once the morning's gone with no
     * bread), and with bread short, a passer-by near a bakery's door now and then drawn into its line (a few at once
     * when a player first comes by: the line that's been forming all morning, caught up with).
     */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {
        val now = level.gameTime
        val m = market(level, here.places)
        val short = m != null && short(level, m)
        val bread = m == null || m.stock(Good.BREAD) >= 1.0
        lines.entries.removeIf { (_, line) ->
            line.queue.prune()
            if (line.queue.members.isEmpty()) line.opens = -1
            if (line.opens < 0 && !short && !bread) line.queue.over = true
            line.queue.members.isEmpty() && (!short || line.queue.over)
        }
        if (!short) return
        for (bakery in here.places.nearest(player.blockPosition(), BAKERIES).take(2)) {
            val door = bakery.front ?: continue
            if (door.pos.distSqr(player.blockPosition()) > SEEN * SEEN) continue
            val line = line(level, bakery) ?: continue
            if (line.looked == now) continue
            val first = line.looked == Long.MIN_VALUE
            line.looked = now
            line.queue.prune()
            line.queue.over = false
            if (line.queue.members.size >= DRAWN || !first && now - line.pulled < PULL_EVERY) continue
            // One at a time as they pass; a few at once when it's first seen
            val want = if (first) (FIRST - line.queue.members.size).coerceAtLeast(1) else 1
            val at = Vec3.atCenterOf(door.pos)
            var n = 0
            for (p in level.getEntitiesOfClass(Townsperson::class.java, AABB(door.pos).inflate(PULL, 6.0, PULL)) { pullable(level, it) }.sortedBy { it.distanceToSqr(at) }) {
                if (n >= want || line.queue.full) break
                val life = p.life ?: continue
                if (bakery(p, life)?.id != bakery.id) continue
                p.routine.replace(queueing(level, p, life, line, home = p.resident >= 0))
                n++
            }
            if (n > 0) line.pulled = now
        }
    }

    /** Whether [p] is out with time on their hands for a loaf: a passer-by, or someone in a free stretch of their day. */
    private fun pullable(level: ServerLevel, p: Townsperson): Boolean {
        if (!p.role.adult || p.role == TownRole.GUARD || !Aside.free(p)) return false
        if (p.crowd) return p.life != null
        val life = Days.life(p) ?: return false
        return life.home != null && Days.stretch(life, level.overworldClockTime).doing.phase == Phase.FREE
    }

    /**
     * `/guildmark capital scene queue`: a line now at the bakery nearest [player] (within [SCENE] blocks), of whoever's
     * free about it, the loaves coming out in half a minute. How many joined it; -1 with no bakery near.
     */
    fun play(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): Int {
        val bakery = here.places.nearest(player.blockPosition(), BAKERIES).firstOrNull { it.front != null } ?: return -1
        if (bakery.front!!.pos.distSqr(player.blockPosition()) > SCENE * SCENE) return -1
        val line = line(level, bakery) ?: return -1
        line.queue.over = false
        line.opens = level.gameTime + 600
        val door = bakery.front!!
        val at = Vec3.atCenterOf(door.pos)
        var n = 0
        for (p in level.getEntitiesOfClass(Townsperson::class.java, AABB(door.pos).inflate(32.0, 8.0, 32.0)) {
            it.role.adult && it.role != TownRole.GUARD && it.life != null && Aside.free(it, seated = true)
        }.sortedBy { it.distanceToSqr(at) }) {
            if (line.queue.full || n >= DRAWN) break
            if (p.seat != null) p.standUp()
            p.routine.replace(queueing(level, p, p.life!!, line, home = p.resident >= 0))
            n++
        }
        return n
    }

    fun forget() = lines.clear()
}
