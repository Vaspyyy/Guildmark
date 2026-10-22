package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Phase
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.Ways
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The hungry at the alms table (living-world/design-influence.md section 5): while a capital's alms table is serving
 * (set up at noon by the well in a lean spell, its pot not empty), the hungry of the town come to it at their lunch
 * ([join], from [io.github.vaspyyy.guildmark.townsfolk.Acts]' lunch: a third of the citizens' households each day, half
 * in hard times, children with them) and passers-by near it join them ([look]). They wait in a line straight out from
 * the table's front, the children saying they're hungry; one at a time they step up and hold out their hands (HOLD_OUT)
 * while the keeper ladles (LADLE), take the bowl with a small bow and a word of thanks (TAKE_BOWL), carry it a few
 * steps off to the side, and eat it standing (EAT_BOWL), then go on with their day.
 *
 * **The way in, for Part D2** (the table, its keeper, its pot and the giving): set [source]. [Source.table] says where
 * a capital's table stands and whether it's serving now, [Source.ladle] has its keeper ladle a bowl for whoever's
 * holding out their hands at it, and [Source.taken] says a bowl's been taken (for the pot's level). Until D2 sets it
 * there's no table, and nobody comes, but for `/guildmark capital scene alms`'s stand-in. Everything here is on
 * [Fortunes.enabled], with the streets' other scenes.
 */
object Alms {
    /**
     * A capital's alms table: the block its trestle stands on (its middle), the way its front faces (the hungry line
     * up straight out from it; the keeper stands behind it, on the other side), and whether it's serving now (set up,
     * the keeper at it and the pot not empty).
     */
    class Table(val pos: BlockPos, val front: Direction, val serving: Boolean)

    /** The alms tables, as Part D2 has them. */
    interface Source {
        /** The alms table of the capital whose heart is [capital], if one's set up today. Asked at most once a second a player. */
        fun table(level: ServerLevel, capital: Long): Table?

        /**
         * The keeper ladles a bowl for [to], who's holding out their hands at the table (LADLE, the pot's level down):
         * true if the keeper's there to do it. False lets whoever stands behind the table do it.
         */
        fun ladle(level: ServerLevel, capital: Long, to: Townsperson): Boolean = false

        /** [by] has taken their bowl from the table at [capital]. */
        fun taken(level: ServerLevel, capital: Long, by: Townsperson) {}
    }

    /** Where the alms tables stand: Part D2's to set, once at setup. */
    @JvmStatic
    var source: Source? = null

    /** The most in a line, the most drawn in from the street, how near the table they must pass, and how far the hungry come for it at lunch. */
    private const val ROOM = 8
    private const val DRAWN = 6
    private const val PULL = 20.0
    private const val FAR = 72.0

    /**
     * How near a player must be to the table, the fewest ticks between one passer-by joining and the next, and how
     * many come over at once when a player first comes by (the line that's been there since noon, caught up with).
     */
    private const val SEEN = 48.0
    private const val PULL_EVERY = 160L
    private const val FIRST = 3

    /** The longest anyone waits, and the longest the one at the table is waited for before the next steps up. */
    private const val PATIENCE = 2400
    private const val AT_TABLE = 400L

    private class Line(val capital: Long, var table: Table, val queue: Queue) {
        var pulled = Long.MIN_VALUE / 2
        var looked = Long.MIN_VALUE

        /** The one at the table now, and since when: the next steps up once they've taken their bowl away. */
        var serving: Townsperson? = null
        var servedAt = Long.MIN_VALUE / 2

        /** Where those with their bowls stand eating. */
        val eating = HashSet<BlockPos>()

        fun clear(now: Long): Boolean {
            val who = serving ?: return true
            val gone = !who.isAlive || who.isRemoved || now - servedAt > AT_TABLE || who.routine.task !is Bowl ||
                who.blockPosition().distSqr(queue.head) > 6
            if (gone) serving = null
            return gone
        }
    }

    private val lines = HashMap<Long, Line>()

    /** `/guildmark capital scene alms`'s stand-in tables, by capital, until a game time. */
    private val standIns = HashMap<Long, Pair<Table, Long>>()

    /** The alms table at the capital whose heart is [capital], if one's set up: the stand-in's, else D2's. */
    fun table(level: ServerLevel, capital: Long): Table? {
        standIns[capital]?.let { (table, until) -> if (level.gameTime <= until) return table else standIns.remove(capital) }
        return try {
            source?.table(level, capital)
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Whether [life]'s household is one of those the table feeds today: a third of the citizens' (half in hard times), by household and day. */
    private fun hungry(level: ServerLevel, life: Life): Boolean {
        val role = life.resident.role
        if (role != TownRole.CITIZEN && role != TownRole.CHILD) return false
        val day = Days.dayOf(level.overworldClockTime)
        val household = (life.home?.id ?: life.resident.id).toLong()
        val hard = Fortunes.times(level, life.places.centre.asLong()) == Times.HARD
        return Math.floorMod(household * 31L + day, if (hard) 2L else 3L) == 0L
    }

    /** The line at [table], laid out straight back from its front if there's none yet (or the table's moved). */
    private fun line(level: ServerLevel, capital: Long, table: Table): Line? {
        lines[capital]?.let { line ->
            if (line.table.pos == table.pos && line.table.front == table.front) {
                line.table = table
                return line
            }
            line.queue.over = true
            lines.remove(capital)
        }
        val head = table.pos.relative(table.front)
        val spots = Queue.lay(level, head, table.front, ROOM, open = true) ?: return null
        val toward = Vec3(table.pos.x + 0.5, table.pos.y + 1.0, table.pos.z + 0.5)
        return Line(capital, table, Queue(spots.first(), spots, toward)).also { lines[capital] = it }
    }

    /**
     * From [io.github.vaspyyy.guildmark.townsfolk.Acts]' lunch: with the table serving, the hungry go to it for their
     * meal (the way there first, from across town), a place in its line; null for everyone else, and with the streets'
     * scenes off (asking nothing of the world and drawing nothing at random then).
     */
    fun join(p: Townsperson, life: Life): Task? {
        if (!Fortunes.enabled() && standIns.isEmpty()) return null
        val level = p.level() as? ServerLevel ?: return null
        val capital = life.places.centre.asLong()
        val standIn = standIns[capital]?.let { level.gameTime <= it.second } == true
        if (!standIn && !Fortunes.enabled()) return null
        val table = table(level, capital)?.takeIf { it.serving } ?: return null
        if (!standIn && !hungry(level, life)) return null
        val at = Vec3.atCenterOf(table.pos)
        if (at.distanceToSqr(p.position()) > FAR * FAR) return null
        val line = line(level, capital, table) ?: return null
        line.queue.prune()
        if (line.queue.full) return null
        line.queue.over = false
        val waiting = queueing(level, p, line)
        if (at.distanceToSqr(p.position()) < 14.0 * 14.0) return waiting
        return Ways.to(p, life.places, line.queue.head, Jobs.WALK, near = 6.0).andThen(waiting)
    }

    /** A place in [line] for [p]: up to the table when it's their turn and it's serving, for a bowl. */
    private fun queueing(level: ServerLevel, p: Townsperson, line: Line): Queueing = Queueing(
        p, line.queue, PATIENCE + p.random.nextInt(800),
        ready = {
            val table = table(level, line.capital)
            if (table == null || !table.serving) {
                line.queue.over = true
                false
            } else line.clear(level.gameTime)
        },
        served = { who ->
            line.serving = who
            line.servedAt = level.gameTime
            Bowl(who, line)
        },
        // The grown-ups wait quietly; the children say they're hungry
        words = { who -> if (who.role == TownRole.CHILD) "child.hungry" else null },
        why = "in the line at the alms table",
    )

    /**
     * Once a second for each player in a capital: its line kept (broken up once the table's done serving), and with it
     * serving, a passer-by near it now and then drawn into the line (a few at once when a player first comes by).
     */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {
        val now = level.gameTime
        val table = table(level, here.key)
        lines[here.key]?.let { line ->
            if (table == null || !table.serving) line.queue.over = true
            line.queue.prune()
            if (line.queue.members.isEmpty() && line.queue.over) lines.remove(here.key)
        }
        if (table == null || !table.serving) return
        val at = Vec3.atCenterOf(table.pos)
        if (player.distanceToSqr(at) > SEEN * SEEN) return
        val line = line(level, here.key, table) ?: return
        if (line.looked == now) return
        val first = line.looked == Long.MIN_VALUE
        line.looked = now
        line.queue.prune()
        line.queue.over = false
        if (line.queue.members.size >= DRAWN || line.queue.full || !first && now - line.pulled < PULL_EVERY) return
        // One at a time as they pass; a few at once when it's first seen
        val want = if (first) (FIRST - line.queue.members.size).coerceAtLeast(1) else 1
        val pulled = level.getEntitiesOfClass(Townsperson::class.java, AABB(table.pos).inflate(PULL, 6.0, PULL)) { pullable(level, it) }
            .sortedBy { it.distanceToSqr(at) }.take(want)
        for (p in pulled) {
            if (line.queue.full) break
            p.routine.replace(queueing(level, p, line))
        }
        if (pulled.isNotEmpty()) line.pulled = now
    }

    /** Whether [p] would come to the table: one of the hungry with time on their hands, or a passer-by down on their luck. */
    private fun pullable(level: ServerLevel, p: Townsperson): Boolean {
        if (p.role == TownRole.GUARD || p.role == TownRole.NOBLE || !Aside.free(p)) return false
        if (p.crowd) return p.life != null && p.role.adult && Math.floorMod(p.uuid.leastSignificantBits, 2L) == 0L
        val life = Days.life(p) ?: return false
        return Days.stretch(life, level.overworldClockTime).doing.phase == Phase.FREE && hungry(level, life)
    }

    /**
     * A bowl from the table, for whoever's at its head: hands held out while it's ladled, the bowl taken with a small
     * bow and a word of thanks, carried a few steps off to the side, and eaten standing; a nod to the table, and on.
     */
    private class Bowl(p: Townsperson, private val line: Line) : Task(p), Scened {
        private enum class S { HOLD, TAKE, AWAY, EAT, DONE }

        private val walker = Walker(p)
        private var s = S.HOLD
        private var t = 0
        private var eatAt: BlockPos? = null
        private val eatFor = 240 + p.random.nextInt(140)
        private val street: Vec3 = Vec3.atCenterOf(line.table.pos.relative(line.table.front, 6))

        private val top: Vec3 get() = Vec3(line.table.pos.x + 0.5, line.table.pos.y + 1.0, line.table.pos.z + 0.5)

        override fun start() {
            p.navigation.stop()
            p.act(Activity.HOLD_OUT)
            ladle()
        }

        /** The keeper's ladle: D2's, or whoever stands behind the table. */
        private fun ladle() {
            val level = level
            val done = try {
                source?.ladle(level, line.capital, p) == true
            } catch (e: RuntimeException) {
                false
            }
            if (done) return
            val behind = line.table.pos.relative(line.table.front.opposite)
            val keeper = level.getEntitiesOfClass(Townsperson::class.java, AABB(behind).inflate(1.0, 1.0, 1.0)) { it.isAlive && it !== p && !it.isSleeping }.firstOrNull()
            Acts.serve(keeper, p, Activity.LADLE)
        }

        override fun run(): Boolean {
            t++
            when (s) {
                S.HOLD -> {
                    look(top)
                    if (t >= Activity.HOLD_OUT.ticks) {
                        s = S.TAKE
                        t = 0
                        p.act(Activity.TAKE_BOWL)
                        if (p.role.adult) Words.say(p, "streets.alms_thanks", "a bowl from the alms table")
                        try {
                            source?.taken(level, line.capital, p)
                        } catch (e: RuntimeException) {
                        }
                    }
                }
                S.TAKE -> {
                    look(top)
                    if (p.actDone || t > Activity.TAKE_BOWL.ticks + 4) {
                        // Carried off in both hands, out of the way of the line
                        val to = aside()
                        eatAt = to
                        line.eating += to
                        p.carried = ItemStack(Items.MUSHROOM_STEW)
                        p.act(Activity.CARRY)
                        walker.go(to, 0.36, exact = true)
                        s = S.AWAY
                        t = 0
                    }
                }
                S.AWAY -> if (walker.tick() != Walker.State.WALKING || t > 200) {
                    walker.stop()
                    p.carried = ItemStack.EMPTY
                    p.act(Activity.EAT_BOWL)
                    s = S.EAT
                    t = 0
                }
                S.EAT -> {
                    // Eaten looking out at the street, now and then up at the table
                    look(if ((t / 80) % 3 == 2) top else street)
                    if (p.activity != Activity.EAT_BOWL && (p.activity.loop || p.actDone)) p.act(Activity.EAT_BOWL)
                    if (t >= eatFor) {
                        p.act(Activity.NOD)
                        s = S.DONE
                        t = 0
                    }
                }
                S.DONE -> {
                    look(top)
                    if (p.actDone || t > 30) return false
                }
            }
            return age < 1600
        }

        private fun look(at: Vec3) {
            p.lookAt(at)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(at), Aside.TURN))
        }

        /** A place to stand and eat: off to one side of the table, clear of the line and of the others eating. */
        private fun aside(): BlockPos {
            val table = line.table
            val sides = if (p.random.nextBoolean()) listOf(table.front.clockWise, table.front.counterClockWise) else listOf(table.front.counterClockWise, table.front.clockWise)
            for (side in sides) for (across in 3..5) for (out in intArrayOf(1, 2, 0, 3)) {
                val base = table.pos.relative(side, across).relative(table.front, out)
                for (dy in intArrayOf(0, 1, -1)) {
                    val pos = base.above(dy)
                    if (!level.isLoaded(pos) || !Places.standable(level, pos)) continue
                    if (line.queue.holds(pos) || line.eating.any { it.distManhattan(pos) <= 1 }) continue
                    return pos
                }
            }
            return p.blockPosition()
        }

        override fun stop() {
            walker.stop()
            if (line.serving === p) line.serving = null
            eatAt?.let { line.eating.remove(it) }
            if (p.carried.`is`(Items.MUSHROOM_STEW)) p.carried = ItemStack.EMPTY
            if (p.activity == Activity.EAT_BOWL || p.activity == Activity.CARRY || p.activity == Activity.HOLD_OUT) p.act(Activity.NONE)
        }
    }

    /**
     * `/guildmark capital scene alms`: a stand-in table a few steps in front of [player] for a minute and a half (its
     * front to their right, so the line runs across their view), whoever's free nearest it behind it to ladle, and
     * those free about it in its line. How many joined it; -1 if there's no ground for a table.
     */
    fun play(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): Int {
        val facing = player.direction
        val ahead = player.blockPosition().relative(facing, 4)
        val pos = (0 downTo -3).asSequence().map { ahead.above(it) }.firstOrNull { level.isLoaded(it) && Places.standable(level, it) } ?: return -1
        val table = Table(pos, facing.clockWise, serving = true)
        standIns[here.key] = table to level.gameTime + 1800
        val line = line(level, here.key, table) ?: return -1
        line.queue.over = false
        val at = Vec3.atCenterOf(pos)
        val free = level.getEntitiesOfClass(Townsperson::class.java, AABB(pos).inflate(32.0, 8.0, 32.0)) {
            it.role != TownRole.GUARD && it.life != null && Aside.free(it, seated = true)
        }.sortedBy { it.distanceToSqr(at) }.toMutableList()
        // The keeper: the first grown-up of them, behind the table
        free.firstOrNull { it.role.adult }?.let { keeper ->
            free.remove(keeper)
            val behind = pos.relative(table.front.opposite)
            val head = Vec3.atCenterOf(line.queue.head).add(0.0, 1.0, 0.0)
            val aside = Aside(listOf(
                GoTo({ behind }, Jobs.WALK, near = 0.6, most = 400),
                Turn({ head }),
                Wait({ _, _ -> table(level, here.key) == null || line.queue.members.isEmpty() && line.serving == null }, 1800, at = { head }),
            ), most = 2400)
            Aside.take(keeper, aside, seated = true)
        }
        var n = 0
        for (p in free) {
            if (line.queue.full || n >= DRAWN) break
            if (p.seat != null) p.standUp()
            p.routine.replace(queueing(level, p, line))
            n++
        }
        return n
    }

    fun forget() {
        lines.clear()
        standIns.clear()
    }
}
