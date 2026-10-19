package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.caravan.Bodies
import io.github.vaspyyy.guildmark.caravan.Crew
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The works' freight carried from the wagon stand to the works' yard (living-world/design-influence.md section 2,
 * "Materials on the wagons"). The record lands a wagon's freight in its works' yard the moment the wagon gets in
 * (WorksFreight); where the wagon's bodies are there to be seen pulling up (caravan/Unloading, with a player near),
 * the yard's heaps show it coming in: two of the town's porters (its carters first, then citizens with nothing on
 * hand) make a few trips from the stand to the works' crate, a load in their arms each trip, two to a beam for the
 * timber (one walking behind the other), and each load joins its heap as it's set down. The master builder points
 * the wagon in. What's being carried is only held back from the heaps' look ([carrying]): the record has it already,
 * and anything the porters don't get to (nobody near any more, the porters called away, the carrying gone on too
 * long) is simply there.
 *
 * The town granary's goods come the same way (section 3, "The first use"): its first filling on its six wagons, and
 * what the good trade brings it after, carried from the stand in sacks on the shoulder and tipped in at its doors
 * (POUR_SACK), the doors standing open while they come ([filling]).
 */
internal object Unloads : Caravans.Listener {
    /** How near the stand or the yard a player must be for the carrying to go on. */
    private const val SEEN = 72.0

    /** The furthest a yard may be from the stand to be carried to (a tower out on its road isn't). */
    private const val FAR = 112.0

    /** Ticks landed freight waits for the wagon's bodies to pull up at the stand before it's simply in the yard. */
    private const val WAIT = 200L

    /** The longest the carrying may take, and the longest a porter is kept from their day. */
    private const val MOST = 4800L

    /** About how many units a trip carries, the most trips for one material, and for one wagon's freight. */
    private const val PER_TRIP = 40
    private const val TRIPS_EACH = 3
    private const val TRIPS = 6

    /** How far round the stand porters are looked for. */
    private const val PORTERS = 64.0

    /** How far behind the beam's front the one at its back walks. */
    private const val BEHIND = 2.0

    /** The tick of PICK_UP the load comes into the arms, and of PUT_DOWN it leaves them (as Unloading has it); of POUR_SACK a sack's tipped (as Trades has it). */
    private const val PICK_AT = 8
    private const val PUT_AT = 11
    private const val TIPPED = 12

    /** One load: [units] of material [stuff] (Stuff.PAID's order; [SACK] a sack of the granary's), as a beam on two shoulders or in one porter's arms. */
    private class Load(val stuff: Int, val units: Int, val beam: Boolean)

    /** A load that's a sack of grain for the granary. */
    private const val SACK = -1

    /** Trips the granary's goods take from one wagon: its first filling, and the good trade's little after. */
    private const val FILLING_TRIPS = 4
    private const val TRADE_TRIPS = 2

    /** One wagon's freight for one works, carried (or waiting for the wagon's bodies to pull up: not [started]); [sacks] the granary's goods. */
    private class Job(val caravan: Long, val works: Int, val made: Long, val sacks: Boolean = false) {
        val loads = ArrayDeque<Load>()
        val porters = ArrayList<Porter>()
        var started = false
        var startedAt = 0L
        var stand: Vec3 = Vec3.ZERO
        var unseen = 0

        /** Units of material [i] still on their way: in the queue and in the porters' arms (a beam once). */
        fun remaining(i: Int): Int =
            loads.filter { it.stuff == i }.sumOf { it.units } + porters.mapNotNull { it.load }.distinct().filter { it.stuff == i }.sumOf { it.units }
    }

    private val jobs = ArrayList<Job>()

    /** Each open works' yard ([WorksRec.here]) as last seen, to tell what a wagon just landed. */
    private val seen = HashMap<Int, IntArray>()

    /** Each capital's town granary's store as last seen (all its goods), to tell what a wagon just brought it. */
    private val granaries = HashMap<Long, Double>()

    /** Units of material [i] (Stuff.PAID's order) still on their way from the wagon to works [works]'s yard. */
    fun carrying(works: Int, i: Int): Int = jobs.filter { it.works == works && !it.sacks }.sumOf { it.remaining(i) }

    /** Whether sacks are on their way from a wagon to granary [works]'s doors (or waiting for its bodies to pull up). */
    fun filling(works: Int): Boolean = jobs.any { it.works == works && it.sacks }

    fun reset() {
        for (j in jobs) for (p in j.porters.toList()) p.release()
        jobs.clear()
        seen.clear()
        granaries.clear()
    }

    // ---- The wagon in ----

    /**
     * A wagon in (heard from the records): what it landed in each of its capital's works' yards ([seen] then and now,
     * or the freight still on it if this is heard first), held back from the heaps' look while its bodies are there
     * to pull up at the stand with someone near to see.
     */
    override fun arrived(level: ServerLevel, caravan: Caravans.View) {
        if (!Works.enabled()) return
        val capital = caravan.capital.asLong()
        val record = CaravanData.get(level).caravans.firstOrNull { it.id == caravan.id }
        val shown = Bodies.shows().any { it.id == caravan.id }
        for (w in WorksData.get(level).of(capital)) {
            if (!w.open) continue
            // Never seen before (the server just up): only what the record says the wagon carries
            val was = seen[w.id]
            val got = IntArray(Stuff.PAID.size) { i -> if (was == null) 0 else (w.here[i] - was[i]).coerceAtLeast(0) }
            // Heard before the freight landed: what the wagon still has on it for the works, as far as its bill wants it
            record?.freight?.filter { it.works == w.id }?.forEach { f ->
                for (i in got.indices) got[i] = maxOf(got[i], min(f.stuff[i], (w.bill[i] - w.here[i] - w.used[i]).coerceAtLeast(0)))
            }
            seen[w.id] = w.here.copyOf()
            if (!shown || got.sum() <= 0) continue
            val job = Job(caravan.id, w.id, level.gameTime)
            for (load in loads(got)) job.loads.add(load)
            jobs.add(job)
        }
        // The town granary's goods: what its store rose by as the wagon landed (or what's still on the wagon for it)
        val was = granaries[capital]
        val now = store(level, capital)
        if (now != null) granaries[capital] = now
        val granary = granary(level, capital) ?: return
        val owed = record?.freight?.any { it.goods.any { g -> g > 1e-6 } } == true
        if (!shown || !(owed || was != null && now != null && now > was + 1e-6)) return
        val first = granary.fill.any { it > 1e-6 } || WorksBuildData.get(level)[granary.id]?.firstUse == false
        val job = Job(caravan.id, granary.id, level.gameTime, sacks = true)
        repeat(if (first) FILLING_TRIPS else TRADE_TRIPS) { job.loads.add(Load(SACK, 1, false)) }
        jobs.add(job)
    }

    /** [capital]'s town granary standing, whose doors its goods go in at (the granary's, which the great granary's are behind). */
    private fun granary(level: ServerLevel, capital: Long) =
        WorksData.get(level).of(capital).filter { it.state == WorksState.DONE && it.kind == WorksKind.GRANARY }.minByOrNull { it.id }

    /** All of [capital]'s town granary holds, or null with no market for it. */
    private fun store(level: ServerLevel, capital: Long): Double? {
        val m = Markets.get(level)[BlockPos.of(capital)]?.market ?: return null
        var n = 0.0
        for (g in Good.entries) n += m[Site.GRANARY, g]
        return n
    }

    /** Where [job]'s loads go: before the works' crate, or (sacks) before the granary's doors; and what's set down faces. */
    private fun drop(level: ServerLevel, job: Job): Pair<Vec3, Vec3>? {
        val site = WorksBuild.site(job.works) ?: return null
        if (job.sacks) {
            val (stand, inside) = WorksAtWork.intake(level, job.works) ?: return null
            return Vec3(stand.x + 0.5, stand.y.toDouble(), stand.z + 0.5) to inside
        }
        return site.crateFront() to Vec3.atCenterOf(site.crate)
    }

    /** [got] split into a few trips: a load of about [PER_TRIP] units, up to [TRIPS_EACH] of each material and [TRIPS] in all, the timber as beams. */
    private fun loads(got: IntArray): List<Load> {
        val trips = IntArray(got.size) { i -> if (got[i] <= 0) 0 else ceil(got[i] / PER_TRIP.toDouble()).toInt().coerceIn(1, TRIPS_EACH) }
        // Too many trips in all: the material with the most has one fewer, heavier
        while (trips.sum() > TRIPS) {
            val k = trips.indices.maxBy { trips[it] }
            if (trips[k] <= 1) break
            trips[k]--
        }
        val timber = Stuff.PAID.indexOf(Stuff.TIMBER)
        val out = ArrayList<Load>()
        for (i in got.indices) {
            val n = got[i]
            for (k in 0 until trips[i]) out.add(Load(i, n / trips[i] + if (k < n % trips[i]) 1 else 0, i == timber))
        }
        return out
    }

    /**
     * The wagon [caravan]'s bodies have pulled up at the stand at [at] with its load still on (caravan/Unloading's
     * start): its works' freight is carried over now, by two porters borrowed from about the stand, with the master
     * builder pointing it in. With nobody to carry it, or nobody near, it's in the yard already.
     */
    fun arrived(level: ServerLevel, caravan: Long, at: Vec3) {
        if (!Works.enabled()) return
        for (job in jobs.filter { it.caravan == caravan && !it.started }) {
            val drop = drop(level, job)?.first
            if (drop == null || flat(drop, at) > FAR || !seen(level, at, drop)) {
                jobs.remove(job)
                continue
            }
            job.started = true
            job.startedAt = level.gameTime
            job.stand = at
            borrow(level, job, at)
            if (job.porters.isEmpty()) {
                jobs.remove(job)
                continue
            }
            if (!job.sacks) MasterBuilder.wagon(level, job.works, at)
        }
    }

    /** Up to two porters for [job] from about the stand at [at]: the town's carters first, then citizens with nothing on hand. */
    private fun borrow(level: ServerLevel, job: Job, at: Vec3) {
        val box = AABB(at, at).inflate(PORTERS, 16.0, PORTERS)
        val found = level.getEntitiesOfClass(Townsperson::class.java, box) { able(level, it) }
            .sortedWith(compareBy({ if (it.life?.carter == true) 0 else 1 }, { it.distanceToSqr(at) }))
        for (p in found) {
            if (job.porters.size >= 2) break
            val porter = Porter(p, job)
            p.standUp()
            p.routine.interrupt()
            p.navigation.stop()
            p.director = porter
            job.porters.add(porter)
        }
    }

    private fun able(level: ServerLevel, p: Townsperson): Boolean {
        if (!p.isAlive || p.isRemoved || p.director != null || p.captiveOf >= 0 || p.isLeader || p.watcher || p.crowd || p.isPassenger) return false
        if (p.attending != null || p.activity == Activity.FLEE || Crew.of(p) || !level.isPositionEntityTicking(p.blockPosition())) return false
        if (p.life?.carter == true) return true
        return p.role == TownRole.CITIZEN && Aside.free(p)
    }

    // ---- Each tick ----

    /** The carrying looked over once a second: done, gone on too long, or nobody near any more, it's in the yard. */
    fun tick(level: ServerLevel) {
        val now = level.gameTime
        if (now % 20L != 11L) return
        if (!Works.enabled()) {
            if (jobs.isNotEmpty()) reset()
            return
        }
        for (w in WorksData.get(level).works.values) if (w.open) seen[w.id] = w.here.copyOf() else seen.remove(w.id)
        for (capital in granaries.keys.toList()) store(level, capital)?.let { granaries[capital] = it }
        for (w in WorksData.get(level).works.values) {
            if (w.kind == WorksKind.GRANARY && w.state == WorksState.DONE && w.capital !in granaries) store(level, w.capital)?.let { granaries[w.capital] = it }
        }
        for (job in jobs.toList()) {
            val over = when {
                !job.started -> now - job.made > WAIT
                job.loads.isEmpty() && job.porters.none { it.load != null } -> true
                job.porters.isEmpty() -> true
                now - job.startedAt > MOST -> true
                else -> {
                    val drop = drop(level, job)?.first
                    if (drop == null || !seen(level, job.stand, drop)) job.unseen++ else job.unseen = 0
                    job.unseen >= 10
                }
            }
            if (!over) continue
            for (p in job.porters.toList()) p.release()
            jobs.remove(job)
            if (!job.sacks) sync(level, job.works)
        }
    }

    /** A player near the stand at [a] or the yard at [b]. */
    private fun seen(level: ServerLevel, a: Vec3, b: Vec3): Boolean =
        level.players().any { !it.isSpectator && (it.distanceToSqr(a) < SEEN * SEEN || it.distanceToSqr(b) < SEEN * SEEN) }

    /** Works [id]'s heaps shown again (a load just set down). */
    private fun sync(level: ServerLevel, id: Int) {
        val w = WorksData.get(level).works[id] ?: return
        val rec = WorksBuildData.get(level)[id] ?: return
        val site = WorksBuild.site(id) ?: return
        WorksYard.sync(level, w, rec, site)
    }

    private fun flat(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    /** What a load looks like in the arms (a sack on the shoulder). */
    private fun item(stuff: Int): ItemStack = ItemStack(
        if (stuff == SACK) Items.WHEAT else when (Stuff.PAID[stuff]) {
            Stuff.TIMBER -> Items.OAK_PLANKS
            Stuff.STONE -> Items.COBBLESTONE
            Stuff.GLASS -> Items.GLASS
            Stuff.IRON -> Items.IRON_BARS
            Stuff.WOOL -> Items.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE)
            else -> Items.BARREL
        },
    )

    // ---- The porters ----

    /**
     * One porter borrowed for [job]: to the stand, a load up into the arms (or a beam onto the shoulder with the
     * other), over to the works' crate, set down there (the heap the more for it), and back for the next; then back
     * to their day.
     */
    private class Porter(val p: Townsperson, val job: Job) : Director {
        var load: Load? = null
        var mate: Porter? = null
        var leads = false
        var phase = 0
        private var t = 0
        private var mark = 0
        private var waited = 0
        private val walker = Walker(p)
        private var gone = false

        /** Where they're walking to, since when; where they pick up at the stand. */
        private var going: BlockPos? = null
        private var walkFrom = 0
        private var spot: BlockPos? = null

        override fun tick(p: Townsperson) {
            if (gone) return
            val level = p.level() as? ServerLevel ?: return
            t++
            if (!p.isAlive || p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE) {
                release()
                return
            }
            step(level)
        }

        /** Busy: a nod for whoever speaks to them, and on with it. */
        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            p.lookAt(player)
            return true
        }

        private fun step(level: ServerLevel) {
            when (phase) {
                // Free: the next load, a beam with the other when they're free too
                0 -> next()
                // To the stand
                1 -> {
                    val at = spot ?: pickup(level).also { spot = it }
                    if (walk(at, Jobs.WALK, 0.8)) return
                    phase = 2
                    mark = t
                }
                // At the stand: up into the arms (a beam waits for the other end)
                2 -> {
                    face(job.stand)
                    val m = mate
                    if (load?.beam == true && m != null && m.phase < 2 && t - mark < 300) return
                    phase = 3
                    mark = t
                    p.act(Activity.PICK_UP)
                }
                3 -> {
                    val l = load ?: return free()
                    if (t - mark == PICK_AT && (!l.beam || mate == null)) p.carried = item(l.stuff)
                    if (t - mark < Activity.PICK_UP.ticks) return
                    phase = 4
                    mark = t
                    p.act(overlay(l))
                }
                // Over to the crate (the back of a beam keeping behind its front)
                4 -> carry(level)
                // Set down at the crate (the heap the more for it), or tipped in at the granary's doors
                5 -> {
                    val l = load
                    val sack = job.sacks
                    if (t - mark == (if (sack) TIPPED else PUT_AT) && l != null) {
                        p.carried = ItemStack.EMPTY
                        if (!l.beam || leads || mate == null) setDown(level, l)
                    }
                    if (t - mark < (if (sack) Activity.POUR_SACK.ticks else Activity.PUT_DOWN.ticks)) return
                    free()
                }
            }
        }

        /** The next load: a beam with the other porter if they're free too (or alone after a while), else whatever's first. */
        private fun next() {
            if (load != null) return
            val first = job.loads.firstOrNull()
            if (first == null) {
                // Nothing left: done, once the other's done too (they go back together)
                if (job.porters.none { it !== this && it.load != null }) release()
                return
            }
            if (first.beam) {
                val other = job.porters.firstOrNull { it !== this && !it.gone && it.load == null && it.phase == 0 }
                if (other == null && job.porters.any { it !== this && !it.gone } && waited++ < 200) return
                job.loads.removeFirst()
                take(first, other)
                return
            }
            job.loads.removeFirst()
            take(first, null)
        }

        private fun take(l: Load, other: Porter?) {
            load = l
            mate = other
            leads = true
            waited = 0
            phase = 1
            mark = t
            spot = null
            going = null
            if (other != null) {
                other.load = l
                other.mate = this
                other.leads = false
                other.waited = 0
                other.phase = 1
                other.mark = other.t
                other.spot = null
                other.going = null
            }
        }

        /** Where to stand at the stand: by the wagon's back, the back of a beam a step behind its front. */
        private fun pickup(level: ServerLevel): BlockPos {
            val base = BlockPos.containing(job.stand.x, job.stand.y + 0.5, job.stand.z)
            val near = if (leads) base else base.offset(if (job.porters.indexOf(this) == 1) 2 else -2, 0, 0)
            for (r in 0..2) for (dx in -r..r) for (dz in -r..r) for (dy in intArrayOf(0, 1, -1)) {
                val pos = near.offset(dx, dy, dz)
                if (Places.standable(level, pos)) return pos
            }
            return base
        }

        /** Walks to [to]; true while still on the way. A walk that can't be made (or takes too long) is as good as there. */
        private fun walk(to: BlockPos, speed: Double, near: Double): Boolean {
            if (going != to) {
                going = to
                walkFrom = t
                walker.go(to, speed, near)
            }
            if (t - walkFrom > 600 || walker.tick() != Walker.State.WALKING) {
                walker.stop()
                going = null
                return false
            }
            return true
        }

        private fun overlay(l: Load): Activity = when {
            job.sacks -> Activity.CARRY_SACK
            l.beam && mate != null -> Activity.CARRY_BEAM
            else -> Activity.CARRY_BLOCK
        }

        private fun carry(level: ServerLevel) {
            val l = load ?: return free()
            val drop = drop(level, job)?.first ?: return free()
            val m = mate
            if (l.beam && m != null && !leads) {
                // The back of the beam: behind the front, down when it's down
                if (m.gone || m.mate !== this) {
                    putDown()
                    return
                }
                if (m.phase >= 5) {
                    p.navigation.stop()
                    putDown()
                    return
                }
                if (p.activity != Activity.CARRY_BEAM) p.act(Activity.CARRY_BEAM)
                val ahead = m.p.position()
                val away = p.position().subtract(ahead)
                val flat = Vec3(away.x, 0.0, away.z)
                val goal = if (flat.lengthSqr() < 1e-4) ahead else ahead.add(flat.normalize().scale(BEHIND))
                val gap = sqrt(flat.lengthSqr())
                when {
                    gap > 9.0 -> {
                        level.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.6, p.z, 4, 0.2, 0.3, 0.2, 0.01)
                        p.teleportTo(goal.x, ahead.y, goal.z)
                    }
                    gap > BEHIND + 0.4 && t % 5 == 0 -> p.navigation.moveTo(goal.x, ahead.y, goal.z, if (gap > 4.0) 0.95 else 0.75)
                    gap <= BEHIND -> p.navigation.stop()
                }
                p.lookAt(ahead.add(0.0, 1.4, 0.0))
                return
            }
            val overlay = overlay(l)
            if (p.activity != overlay && (p.activity.loop || p.actDone)) p.act(overlay)
            // The front of a beam waits for the back to be up with it
            if (l.beam && m != null && m.phase < 4 && t - mark < 120) return
            if (walk(BlockPos.containing(drop.x, drop.y + 0.5, drop.z), Trades.LADEN, 1.0)) return
            putDown()
        }

        private fun putDown() {
            phase = 5
            mark = t
            p.navigation.stop()
            (p.level() as? ServerLevel)?.let { level -> drop(level, job)?.let { face(it.second) } }
            p.act(if (job.sacks) Activity.POUR_SACK else Activity.PUT_DOWN)
        }

        /** [l] set down at the crate: off the way, onto its heap; a beam's thud once from between its two ends, a stone's knock. */
        private fun setDown(level: ServerLevel, l: Load) {
            val m = mate
            val at = if (l.beam && m != null) BlockPos.containing(p.position().add(m.p.position()).scale(0.5)) else p.blockPosition()
            load = null
            m?.load = null
            if (l.stuff == SACK) {
                level.sendParticles(net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, Items.WHEAT), at.x + 0.5, at.y + 1.2, at.z + 0.5, 8, 0.2, 0.1, 0.2, 0.05)
                return
            }
            val timber = Stuff.PAID[l.stuff] == Stuff.TIMBER
            when {
                timber -> WorksBlocks.play(level, at, WorksBlocks.BEAM_DOWN, if (l.beam && m != null) 1f else 0.6f)
                Stuff.PAID[l.stuff] == Stuff.STONE -> WorksBlocks.play(level, at, WorksBlocks.STONE_SET, 0.8f)
                else -> {}
            }
            sync(level, job.works)
        }

        private fun face(at: Vec3) {
            p.lookAt(at)
            p.face(p.yawTo(at))
        }

        /** Done with this load: free for the next. */
        private fun free() {
            load = null
            mate = null
            leads = false
            phase = 0
            mark = t
            going = null
            walker.stop()
            p.carried = ItemStack.EMPTY
            if (p.activity.overlay || p.activity == Activity.PUT_DOWN) p.act(Activity.NONE)
        }

        /** Back to their day, whatever was in their arms put by (it's in the yard). */
        fun release() {
            if (gone) return
            gone = true
            walker.stop()
            load = null
            mate?.let { if (it.mate === this) it.mate = null }
            mate = null
            p.carried = ItemStack.EMPTY
            if (p.director === this) p.director = null
            p.act(Activity.NONE)
            p.navigation.stop()
            p.routine.interrupt()
            job.porters.remove(this)
        }
    }
}
