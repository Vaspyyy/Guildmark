package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Town
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.village.live.VillageFeast
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * One grand festival in one town on one day: the timeline of its day (see [FestivalPhase]), its decorations going up
 * and coming down, the townsfolk it borrows for its cast (host, band, dancers and crowd; see [FestivalDirector]), the
 * band's sets and the ring dancing to them, the contest and the prize-giving, and the fireworks at dusk.
 *
 * A festival on the calendar keeps the world's clock (local time is the time of day, 0 at six in the morning); one an
 * op started by command keeps its own, from [clockBase] at game time [clockSince]. Everything is put right each tick
 * against what the time says it should be, so a skip of the clock, a sleep, a command or a restart all come out right:
 * the decorations catch up or come down, the cast comes or goes, a set that would run over isn't started.
 */
class FestivalSite(
    /** The town's key: its capital's centre, or its village board, packed. */
    val key: Long,
    /** Which festival this is: the calendar day, or a forced run's own number. Decorations remember it. */
    val run: Long,
    override val kind: FestivalKind,
    override val townName: String,
    val plan: FestivalPlan,
    private val town: Town?,
    private val clockSince: Long,
    private var clockBase: Int,
) : FestivalScene {
    val forced: Boolean get() = clockSince >= 0

    override val centre: Vec3 get() = plan.centre
    override var phase: FestivalPhase = FestivalPhase.SETUP
        private set
    override var live = true
        private set
    override val playing: Boolean get() = setStart >= 0
    override var bar: Int = -1
        private set

    /** Stop now: the decorations come straight down (a command, the settings). */
    private var stopping = false

    // ---- Decorations ----
    private val up = BooleanArray(plan.pieces.size)
    private val tries = IntArray(plan.pieces.size)
    private val retryAt = LongArray(plan.pieces.size)
    private var cursor = 0
    private var downCursor = plan.pieces.size - 1
    private val positions: Set<Long> = plan.pieces.mapTo(HashSet()) { it.pos.asLong() }
    private val groups: Int = (plan.pieces.maxOfOrNull { it.group } ?: -1) + 1
    private val helper = IntArray(groups) { -1 }
    private val helpAsked = LongArray(groups) { -1L }
    private val helpReady = BooleanArray(groups)

    // ---- Cast ----
    private class Member(val person: Townsperson, val part: FestivalPart, val slot: Int, val spawned: Boolean) {
        var lastBar = -1
        var walkSince = -1L
        var nextPath = 0L
        var nextIdle = 0L
        var helping = -1
        var hungAt = -1L
        var arrived = false
    }

    private val members = LinkedHashMap<Int, Member>()
    val director: Director = FestivalDirector(this)
    private var spawned = 0
    private var scans = 0

    // ---- The band's sets ----
    /** Game time the set being played began, or -1. */
    var setStart = -1L
        private set
    private var nextSet = Long.MIN_VALUE

    // ---- Contest, prizes and the evening ----
    val contest = FestivalContest(kind, key xor run)
    private var ceremonyAt = -1L
    private var ceremonyStep = 0
    private var awarded = false
    private var lanternsAt = -1L
    private var nextRocket = 0L
    private var lastRocket = Long.MIN_VALUE / 2
    private var finale = false

    // ---- Who's been told ----
    private val told = HashSet<UUID>()
    private var lastSync = Long.MIN_VALUE
    /** The last game time a player was near. */
    var lastNear = 0L

    private val pose = DancePose()
    private val cx: Double get() = plan.centre.x
    private val cz: Double get() = plan.centre.z

    /** The time of the festival's day, in ticks from six in the morning. */
    fun local(level: ServerLevel): Int =
        if (forced) clockBase + (level.gameTime - clockSince).toInt() else Math.floorMod(level.overworldClockTime, 24000L).toInt()

    /** Moves a forced festival's clock to the start of [to] (testing). */
    fun jump(level: ServerLevel, to: FestivalPhase) {
        if (!forced) return
        clockBase = to.from + (if (to == FestivalPhase.FAIR) 0 else 1) - (level.gameTime - clockSince).toInt()
        if (to.ordinal < FestivalPhase.CEREMONY.ordinal) {
            ceremonyAt = -1L
            ceremonyStep = 0
        }
    }

    /** Everything comes down now. */
    fun stop() {
        stopping = true
    }

    private fun fast(level: ServerLevel, t: Int): Boolean =
        stopping || !Seasons.festivalsEnabled() || t >= FestivalPhase.OVER || (!forced && Seasons.today(level).calendarDay != run)

    private fun phaseFor(level: ServerLevel, t: Int): FestivalPhase =
        if (fast(level, t)) FestivalPhase.TEARDOWN else FestivalPhase.at(t.coerceAtLeast(0))

    /** Does this festival still want the decoration recorded as [record]? */
    fun wants(record: FestivalRestores.PieceRecord): Boolean =
        live && record.site == key && record.run == run && phase.ordinal <= FestivalPhase.DUSK.ordinal

    /** Is [pos] one of this festival's decorations? */
    fun owns(pos: BlockPos): Boolean = pos.asLong() in positions

    // =============================================================================================== ticking

    /** Each tick while a player is near. */
    fun tick(level: ServerLevel) {
        val now = level.gameTime
        val t = local(level)
        val next = phaseFor(level, t)
        if (next != phase) changePhase(level, next, now)
        decorate(level, t, now)
        if (!live) return
        if (Math.floorMod(now + key, 100L) == 0L) {
            refresh(level, now)
            recruit(level, now)
        }
        music(level, now, t)
        ceremony(level, now)
        evening(level, now, t)
        if (now - lastSync >= 100) sync(level)
        if (!plan.capital && Math.floorMod(now + key, 100L) == 50L) villagers(level, now)
    }

    /** Each tick with nobody near: only the decorations, so the teardown still happens in loaded ground. */
    fun tickAsleep(level: ServerLevel) {
        val now = level.gameTime
        val t = local(level)
        val next = phaseFor(level, t)
        if (next != phase) changePhase(level, next, now)
        if (members.isNotEmpty()) releaseAll(level)
        if (setStart >= 0) setStart = -1
        decorate(level, t, now)
        if (phase == FestivalPhase.TEARDOWN && !awarded) award(level)
    }

    private fun changePhase(level: ServerLevel, next: FestivalPhase, now: Long) {
        val was = phase
        phase = next
        when (next) {
            FestivalPhase.FAIR, FestivalPhase.DUSK -> {
                if (setStart >= 0) endSet(level, now, rest = false)
                nextSet = now + FIRST_SET
            }
            FestivalPhase.CEREMONY -> if (setStart >= 0) endSet(level, now, rest = false)
            FestivalPhase.TEARDOWN -> {
                if (setStart >= 0) endSet(level, now, rest = false)
                releaseAll(level)
                if (!awarded) award(level)
            }
            FestivalPhase.SETUP -> {}
        }
        contest.open = next == FestivalPhase.FAIR
        if (was == FestivalPhase.SETUP && next != FestivalPhase.SETUP) for (g in 0 until groups) doneHelping(g)
        lastSync = Long.MIN_VALUE
    }

    // =============================================================================================== decorations

    private fun decorate(level: ServerLevel, t: Int, now: Long) {
        val n = plan.pieces.size
        if (phase.ordinal <= FestivalPhase.DUSK.ordinal) {
            // Through the morning one at a time (helpers putting up the posts); after that, quickly, all of it
            val setup = phase == FestivalPhase.SETUP && t < SETUP_DONE
            val due = if (setup) min(n, (t - 40).coerceAtLeast(0) * n / (SETUP_DONE - 100) + 1) else n
            var budget = if (setup) 1 else 3
            var k = cursor
            while (k < due && budget > 0) {
                if (up[k] || tries[k] >= MAX_TRIES) {
                    if (k == cursor) cursor++
                    k++
                    continue
                }
                val piece = plan.pieces[k]
                // Ground nobody's near enough to have loaded waits, without counting against the decoration
                if (now < retryAt[k] || !level.isLoaded(piece.pos)) {
                    k++
                    continue
                }
                if (setup && piece.group >= 0 && !helped(level, piece.group, now)) break
                if (place(level, k)) {
                    budget--
                } else {
                    // Something's in the way (someone standing there, a block put down since): try again shortly
                    tries[k]++
                    retryAt[k] = now + 40
                }
                if (piece.group >= 0 && lastOfGroup(k)) doneHelping(piece.group)
                k++
            }
            return
        }
        // Coming down: in the evening in reverse order, one after another; straight away when it has to be
        val quick = fast(level, t)
        val dueDown = if (quick) n else min(n, (t - FestivalPhase.TEARDOWN.from).coerceAtLeast(0) * n / (FestivalPhase.OVER - FestivalPhase.TEARDOWN.from - 100) + 1)
        var budget = if (quick) 8 else 2
        var removed = n - 1 - downCursor
        while (downCursor >= 0 && removed < dueDown && budget > 0) {
            val k = downCursor
            if (up[k]) {
                val pos = plan.pieces[k].pos
                if (level.isLoaded(pos)) {
                    puff(level, pos, false)
                    FestivalRestores.restore(level, pos)
                    budget--
                }
                up[k] = false
            }
            downCursor--
            removed++
        }
        if (downCursor < 0 && live) {
            // Anything of this town's festivals left in the loaded ground (from before a restart, say); what's in
            // ground that isn't loaded is put back when it next loads (FestivalRestores.loaded)
            for (pos in FestivalRestores.recordsOf(level, key, plan.centrePos, plan.reach + 2)) FestivalRestores.restore(level, pos)
            finish(level)
        }
    }

    private fun place(level: ServerLevel, k: Int): Boolean {
        val piece = plan.pieces[k]
        if (!FestivalRestores.place(level, piece.pos, piece.state, key, run, piece.solid)) return false
        up[k] = true
        piece.data?.let { (level.getBlockEntity(piece.pos) as? FestivalOrnamentBlockEntity)?.setup(it) }
        puff(level, piece.pos, true)
        return true
    }

    /** A puff and a tap as a decoration goes up or comes down. */
    private fun puff(level: ServerLevel, pos: BlockPos, going: Boolean) {
        val state = level.getBlockState(pos)
        level.sendParticles(if (going) ParticleTypes.HAPPY_VILLAGER else ParticleTypes.POOF, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, if (going) 3 else 2, 0.3, 0.3, 0.3, 0.01)
        val sound = when {
            state.block is FestivalOrnamentBlock -> if (going) SoundEvents.WOOL_PLACE else SoundEvents.WOOL_BREAK
            else -> if (going) SoundEvents.WOOD_PLACE else SoundEvents.WOOD_BREAK
        }
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5f, 0.9f + level.random.nextFloat() * 0.3f)
    }

    /** The last decoration of each post (its group). */
    private val groupLast = IntArray(groups).also { last -> plan.pieces.forEachIndexed { i, piece -> if (piece.group >= 0) last[piece.group] = i } }

    private fun lastOfGroup(k: Int): Boolean = groupLast[plan.pieces[k].group] == k

    /**
     * Whether post [group] has its helper there, reaching up to it: one of the cast is sent to each post as its turn
     * comes, and the post goes up while they work at it. With nobody to send, it goes up after a moment anyway.
     */
    private fun helped(level: ServerLevel, group: Int, now: Long): Boolean {
        if (helpReady[group]) return true
        if (helpAsked[group] < 0) helpAsked[group] = now
        if (now - helpAsked[group] > HELP_WAIT) return true.also { helpReady[group] = true }
        if (helper[group] < 0) {
            val base = plan.pieces.first { it.group == group }.pos
            val free = members.values.filter { (it.part == FestivalPart.DANCE || it.part == FestivalPart.WATCH) && it.helping < 0 && it.person.role != TownRole.CHILD }
            val pick = free.minByOrNull { it.person.distanceToSqr(base.x + 0.5, base.y.toDouble(), base.z + 0.5) } ?: return false
            pick.helping = group
            pick.hungAt = -1L
            pick.walkSince = now
            helper[group] = pick.person.id
        }
        val m = members[helper[group]]
        if (m == null || m.helping != group) {
            helper[group] = -1
            return false
        }
        if (m.hungAt >= 0 && now - m.hungAt >= HANG_FIRST) helpReady[group] = true
        return helpReady[group]
    }

    private fun doneHelping(group: Int) {
        members[helper[group]]?.let { if (it.helping == group) it.helping = -1 }
        helper[group] = -1
    }

    /** Where a helper stands to put post [group] up: at its foot, on the side toward the middle. */
    private fun helpSpot(group: Int): Vec3 {
        val base = plan.pieces.first { it.group == group }.pos
        val dx = cx - (base.x + 0.5)
        val dz = cz - (base.z + 0.5)
        val d = hypot(dx, dz).coerceAtLeast(0.01)
        return Vec3(base.x + 0.5 + dx / d * 1.1, base.y.toDouble(), base.z + 0.5 + dz / d * 1.1)
    }

    /** How many of the decorations are up, of how many. */
    fun decorated(): Pair<Int, Int> = up.count { it } to plan.pieces.size

    private fun finish(level: ServerLevel) {
        releaseAll(level)
        live = false
        sync(level)
    }

    /**
     * Ends at once: every decoration in loaded ground down now (the rest when its ground next loads) and everyone let
     * go. For the server stopping, a festival started over this one, or one left behind by every player.
     */
    fun abandon(level: ServerLevel) {
        if (!live) return
        for (k in plan.pieces.indices) {
            if (!up[k]) continue
            FestivalRestores.restore(level, plan.pieces[k].pos)
            up[k] = false
        }
        for (pos in FestivalRestores.recordsOf(level, key, plan.centrePos, plan.reach + 2)) FestivalRestores.restore(level, pos)
        // Prizes the day had earned and not yet handed out
        if (!awarded && phase.ordinal >= FestivalPhase.CEREMONY.ordinal) award(level)
        finish(level)
    }

    // =============================================================================================== the cast

    /** Drops those who've gone (or been taken over by something else), and keeps passers-by from leaving meanwhile. */
    private fun refresh(level: ServerLevel, now: Long) {
        val iterator = members.values.iterator()
        val far = (plan.square + 24.0) * (plan.square + 24.0)
        while (iterator.hasNext()) {
            val m = iterator.next()
            val p = m.person
            val gone = p.isRemoved || !p.isAlive || p.level() !== level || p.director !== director || p.captiveOf >= 0 ||
                p.distanceToSqr(cx, p.y, cz) > far
            if (gone) {
                iterator.remove()
                if (p.director === director) loose(level, p, m.spawned)
                continue
            }
            if (p.crowd) p.leaveAt = now + 1200
        }
    }

    /** How many each part wants. */
    private fun wanted(part: FestivalPart): Int = when (part) {
        FestivalPart.HOST -> 1
        FestivalPart.PLAY -> plan.band.size
        FestivalPart.DANCE -> plan.dance?.n ?: 0
        FestivalPart.WATCH -> if (plan.capital) plan.crowd.size else min(plan.crowd.size, 8)
        FestivalPart.HELP -> 0
    }

    private fun freeSlot(part: FestivalPart): Int {
        val taken = members.values.filter { it.part == part }.map { it.slot }.toSet()
        return (0 until wanted(part)).firstOrNull { it !in taken } ?: -1
    }

    /**
     * Every few seconds: anyone about the square who's free is asked to take part (never a faction's leader, a
     * captive, a heist's watcher, someone asleep or busy talking to a player, nor the watch), and the parts nobody
     * fills are taken by players come in for the day: a troupe walks into a village, a few more folk into a capital.
     */
    private fun recruit(level: ServerLevel, now: Long) {
        if (phase.ordinal > FestivalPhase.DUSK.ordinal) return
        scans++
        val needs = FestivalPart.entries.filter { it != FestivalPart.HELP && freeSlot(it) >= 0 }
        if (needs.isEmpty()) return
        val reach = plan.square + 20.0
        val folk = level.getEntitiesOfClass(Townsperson::class.java, AABB(plan.centre, plan.centre).inflate(reach, 12.0, reach)) { eligible(it) }
            .sortedBy { it.distanceToSqr(plan.centre) }
        var taken = 0
        for (p in folk) {
            if (taken >= 4) break
            val part = partFor(p) ?: continue
            val slot = freeSlot(part)
            if (slot < 0) continue
            take(p, part, slot, spawned = false, now)
            taken++
        }
        // Players come in for the day: a village's troupe, or a capital's visitors once its own folk are all busy
        val cap = if (plan.capital) 10 else 14
        if (spawned >= cap || (plan.capital && scans < 3)) return
        var brought = 0
        for (part in listOf(FestivalPart.HOST, FestivalPart.PLAY, FestivalPart.DANCE, FestivalPart.WATCH)) {
            while (brought < 2 && spawned < cap) {
                val slot = freeSlot(part)
                if (slot < 0) break
                val p = bringIn(level, part, now) ?: return
                take(p, part, slot, spawned = true, now)
                spawned++
                brought++
            }
        }
    }

    /** Free to join in: not a faction's leader, a captive, a heist's watcher, asleep or talking to a player; not the watch, nor those who keep shop through a fair day. */
    private fun eligible(p: Townsperson): Boolean =
        p.isAlive && !p.isRemoved && p.director == null && !p.isLeader && p.captiveOf < 0 && !p.watcher && !p.isSleeping &&
            p.attending == null && p.role !in KEEP_AT_WORK && !members.containsKey(p.id)

    /** The part someone's suited to, of those still wanted. */
    private fun partFor(p: Townsperson): FestivalPart? {
        val child = p.role == TownRole.CHILD
        if (p.role == TownRole.BARD && freeSlot(FestivalPart.PLAY) >= 0) return FestivalPart.PLAY
        if (!child && freeSlot(FestivalPart.HOST) >= 0 && p.role in HOSTS) return FestivalPart.HOST
        if (!child && freeSlot(FestivalPart.DANCE) >= 0 && p.role != TownRole.PRIEST) return FestivalPart.DANCE
        if (!child && p.role != TownRole.THIEF && freeSlot(FestivalPart.PLAY) >= 0 && scans > 2) return FestivalPart.PLAY
        if (freeSlot(FestivalPart.WATCH) >= 0) return FestivalPart.WATCH
        if (!child && freeSlot(FestivalPart.HOST) >= 0 && scans > 2) return FestivalPart.HOST
        return null
    }

    private fun take(p: Townsperson, part: FestivalPart, slot: Int, spawned: Boolean, now: Long) {
        p.standUp()
        p.routine.interrupt()
        p.navigation.stop()
        p.director = director
        if (p.crowd) p.leaveAt = now + 1200
        val m = Member(p, part, slot, spawned)
        m.walkSince = now
        members[p.id] = m
    }

    /** Lets [p] go back to their day (or, a visitor, on their way). */
    private fun loose(level: ServerLevel, p: Townsperson, spawned: Boolean) {
        if (p.director === director) p.director = null
        p.routine.interrupt()
        p.navigation.stop()
        p.act(Activity.NONE)
        p.lantern = false
        if (p.crowd) p.leaveAt = if (spawned) level.gameTime else level.gameTime + 600
    }

    private fun releaseAll(level: ServerLevel) {
        for (m in members.values) loose(level, m.person, m.spawned)
        members.clear()
        for (g in 0 until groups) helper[g] = -1
    }

    /** One of the day's visitors, walking in from out of sight. */
    private fun bringIn(level: ServerLevel, part: FestivalPart, now: Long): Townsperson? {
        val random = level.random
        repeat(10) {
            val angle = random.nextDouble() * 2 * PI
            val dist = 18.0 + random.nextDouble() * 16.0
            val x = floor(cx + cos(angle) * dist).toInt()
            val z = floor(cz + sin(angle) * dist).toInt()
            if (!level.hasChunk(x shr 4, z shr 4)) return@repeat
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            if (abs(y - plan.floor) > 8) return@repeat
            val pos = BlockPos(x, y, z)
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty) return@repeat
            if (!level.getBlockState(pos.below()).fluidState.isEmpty || !level.isPositionEntityTicking(pos)) return@repeat
            val feet = Vec3.atBottomCenterOf(pos)
            // Out of sight, but near enough a player that they don't take themselves off again (Crowds.maybeLeave)
            if (level.players().none { it.distanceToSqr(feet) < 60.0 * 60.0 }) return@repeat
            if (level.players().any { sees(level, it, feet.add(0.0, 1.0, 0.0)) }) return@repeat
            val role = roleFor(part, random.nextInt(100))
            val person = Townsfolk.create(level, pos, role, reason = EntitySpawnReason.EVENT) ?: return null
            person.crowd = true
            person.town = town
            person.home = pos
            person.leaveAt = now + 2400
            level.addFreshEntity(person)
            return person
        }
        return null
    }

    /** Could [player] see [at]: close by, or ahead of them with nothing in between? */
    private fun sees(level: ServerLevel, player: ServerPlayer, at: Vec3): Boolean {
        val eye = player.eyePosition
        val to = at.subtract(eye)
        val d2 = to.lengthSqr()
        if (d2 < 12.0 * 12.0) return true
        if (d2 > 56.0 * 56.0 || player.getViewVector(1f).dot(to.normalize()) <= 0.25) return false
        return level.clip(ClipContext(eye, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS
    }

    private fun roleFor(part: FestivalPart, roll: Int): TownRole = when (part) {
        FestivalPart.HOST -> if (plan.capital) TownRole.NOBLE else TownRole.CITIZEN
        FestivalPart.PLAY -> TownRole.BARD
        FestivalPart.DANCE -> when {
            roll < 55 -> TownRole.CITIZEN
            roll < 70 -> TownRole.FARMER
            roll < 82 -> TownRole.NOBLE
            roll < 92 -> TownRole.BAKER
            else -> TownRole.MERCHANT
        }
        else -> when {
            roll < 45 -> TownRole.CITIZEN
            roll < 70 -> TownRole.CHILD
            roll < 85 -> TownRole.FARMER
            else -> TownRole.FISHER
        }
    }

    // =============================================================================================== each of the cast, each tick

    /** [p]'s tick while they're in the festival. */
    fun tickMember(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val m = members[p.id]
        if (m == null || !live) {
            loose(level, p, false)
            return
        }
        val now = level.gameTime
        p.lantern = lantern(level, m)
        if (m.helping >= 0 && phase == FestivalPhase.SETUP) {
            help(p, m, now)
            return
        }
        when (m.part) {
            FestivalPart.DANCE -> dancer(level, p, m, now)
            FestivalPart.PLAY -> musician(level, p, m, now)
            FestivalPart.HOST -> host(level, p, m, now)
            else -> watcher(level, p, m, now)
        }
    }

    private fun lantern(level: ServerLevel, m: Member): Boolean {
        // Let go into the sky, until they've lit another
        if (lanternsAt >= 0 && level.gameTime - lanternsAt in RELEASE_TICK..LANTERN_TIME) return false
        val dark = Math.floorMod(level.overworldClockTime, 24000L) in 12600L..23200L
        return when (m.part) {
            FestivalPart.PLAY -> false
            FestivalPart.DANCE -> kind == FestivalKind.LANTERNRISE
            // Not while the music plays: they clap and cheer it with both hands
            else -> setStart < 0 && (dark || kind == FestivalKind.LANTERNRISE && phase == FestivalPhase.DUSK)
        }
    }

    private fun help(p: Townsperson, m: Member, now: Long) {
        val spot = helpSpot(m.helping)
        if (!walk(p, m, spot.x, spot.y, spot.z, now, close = 0.9)) return
        val base = plan.pieces.first { it.group == m.helping }.pos
        turn(p, p.yawTo(Vec3(base.x + 0.5, p.y, base.z + 0.5)), 20f)
        if (m.hungAt < 0) {
            m.hungAt = now
            p.act(Activity.HANG)
        }
        p.lookAt(Vec3(base.x + 0.5, base.y + 3.0, base.z + 0.5))
    }

    private fun dancer(level: ServerLevel, p: Townsperson, m: Member, now: Long) {
        val dance = plan.dance
        if (dance == null || m.slot >= dance.n) return watcher(level, p, m, now)
        val y = plan.centre.y
        val dancing = setStart >= 0 && (phase == FestivalPhase.FAIR || phase == FestivalPhase.DUSK)
        if (dancing) {
            val b = (now - setStart) / FestivalDance.BAR_TICKS.toDouble()
            dance.pose(b, m.slot, cx, cz, pose)
            if (!join(p, m, pose.x, y, pose.z, now)) return
            turn(p, pose.yaw, 30f)
            val index = b.toInt()
            if (index != m.lastBar) {
                m.lastBar = index
                p.act(pose.activity)
            }
            return
        }
        m.lastBar = -1
        dance.home(m.slot, cx, cz, pose)
        if (!walk(p, m, pose.x, y, pose.z, now)) return
        val partner = if (m.slot % 2 == 0) m.slot + 1 else m.slot - 1
        val other = members.values.firstOrNull { it.part == FestivalPart.DANCE && it.slot == partner }?.person
        when {
            phase == FestivalPhase.CEREMONY -> ceremonyPose(p, m, now)
            nextSet - now in 0..GATHER -> {
                turn(p, pose.yaw, 15f)
                if (p.activity != Activity.NONE && p.actDone || p.activity.loop && p.activity != Activity.NONE) p.act(Activity.NONE)
            }
            else -> {
                // Between sets: a word with their partner
                if (other != null && other.distanceToSqr(p) < 9.0) turn(p, p.yawTo(other.position()), 10f) else turn(p, pose.yaw, 10f)
                idle(level, p, m, now, listOf(Activity.TALK, Activity.LAUGH, Activity.NONE, Activity.TALK))
            }
        }
    }

    private fun musician(level: ServerLevel, p: Townsperson, m: Member, now: Long) {
        val spot = plan.band.getOrNull(m.slot) ?: return watcher(level, p, m, now)
        if (!walk(p, m, spot.x, spot.y, spot.z, now)) return
        turn(p, spot.yaw, 12f)
        when {
            setStart >= 0 -> if (p.activity != Activity.MINSTREL) p.act(Activity.MINSTREL)
            phase == FestivalPhase.CEREMONY -> ceremonyPose(p, m, now)
            else -> idle(level, p, m, now, listOf(Activity.DRINK, Activity.TALK, Activity.NONE, Activity.LAUGH))
        }
    }

    private fun host(level: ServerLevel, p: Townsperson, m: Member, now: Long) {
        val spot = plan.host
        if (!walk(p, m, spot.x, spot.y, spot.z, now)) return
        turn(p, spot.yaw, 12f)
        when (phase) {
            FestivalPhase.CEREMONY -> return
            FestivalPhase.SETUP -> if (now >= m.nextIdle) {
                m.nextIdle = now + 160 + level.random.nextInt(120)
                p.act(Activity.CALL_OUT)
                p.say("fest_setup_${level.random.nextInt(SETUP_LINES)}")
            }
            else -> {
                if (setStart >= 0) {
                    val index = ((now - setStart) / FestivalDance.BAR_TICKS).toInt()
                    if (index != m.lastBar) {
                        m.lastBar = index
                        p.act(if (index % 8 == 7) Activity.CHEER else Activity.CLAP_ALONG)
                    }
                } else if (now >= m.nextIdle) {
                    m.lastBar = -1
                    m.nextIdle = now + 240 + level.random.nextInt(200)
                    p.act(Activity.CALL_OUT)
                    p.say(if (contest.open) "fest_${kind.id}_${level.random.nextInt(CALL_LINES)}" else "fest_evening_${level.random.nextInt(EVENING_LINES)}")
                }
            }
        }
    }

    private fun watcher(level: ServerLevel, p: Townsperson, m: Member, now: Long) {
        val spot = plan.crowd.getOrNull(m.slot % plan.crowd.size.coerceAtLeast(1)) ?: return
        if (!walk(p, m, spot.x, spot.y, spot.z, now)) return
        if (phase == FestivalPhase.CEREMONY) return ceremonyPose(p, m, now)
        val child = p.role == TownRole.CHILD
        if (setStart >= 0) {
            turn(p, spot.yaw, 12f)
            val index = ((now - setStart) / FestivalDance.BAR_TICKS).toInt()
            if (index != m.lastBar) {
                m.lastBar = index
                val roll = Math.floorMod((m.slot * 7349 + index * 1931 + key.toInt()) * 2654435761L.toInt(), 100)
                p.act(
                    when {
                        index >= FestivalDance.SET_BARS - 2 -> Activity.FEST_HURRAH
                        child -> if (roll < 35) Activity.JUMP else if (roll < 60) Activity.SPIN else Activity.CLAP_ALONG
                        roll < 42 -> Activity.CLAP_ALONG
                        roll < 58 -> Activity.CHEER
                        roll < 68 -> Activity.LAUGH
                        roll < 76 -> Activity.WAVE
                        else -> Activity.NONE
                    },
                )
            }
            return
        }
        m.lastBar = -1
        // Fireworks overhead: everyone looks up
        if (phase == FestivalPhase.DUSK && (rocketsUp(level) || lanternsAt >= 0 && now - lanternsAt < LANTERN_TIME)) {
            turn(p, spot.yaw, 6f)
            if (p.activity != Activity.GAZE && p.activity != Activity.RELEASE && (p.actDone || p.activity.loop)) p.act(Activity.GAZE)
            return
        }
        turn(p, spot.yaw, 8f)
        idle(level, p, m, now, if (child) listOf(Activity.PLAY, Activity.JUMP, Activity.SPIN, Activity.NONE) else listOf(Activity.TALK, Activity.LAUGH, Activity.DRINK, Activity.NONE, Activity.LISTEN))
    }

    /** Between things: every little while something to do from [choices]. */
    private fun idle(level: ServerLevel, p: Townsperson, m: Member, now: Long, choices: List<Activity>) {
        if (now < m.nextIdle) return
        m.nextIdle = now + 120 + level.random.nextInt(160)
        val pick = choices[level.random.nextInt(choices.size)]
        if (pick.overlay || p.activity != pick) p.act(pick)
    }

    /** During the prize-giving: everyone facing the host, cheering the winner. */
    private fun ceremonyPose(p: Townsperson, m: Member, now: Long) {
        val host = plan.host
        turn(p, p.yawTo(host.pos), 10f)
        p.lookAt(host.pos.add(0.0, 1.6, 0.0))
        if (ceremonyStep >= WINNER_STEP && m.lastBar != -WINNER_STEP) {
            m.lastBar = -WINNER_STEP
            p.act(if (p.role == TownRole.CHILD) Activity.JUMP else Activity.FEST_HURRAH)
        } else if (ceremonyStep < WINNER_STEP && p.activity.loop && p.activity != Activity.NONE) {
            p.act(Activity.NONE)
        }
        if (now % 200L == (p.id % 200).toLong() && ceremonyStep >= WINNER_STEP) p.act(Activity.CLAP)
    }

    // ---- Getting about ----

    /**
     * Walks [p] toward ([x], [y], [z]) and settles them on it; true once they're there. Someone who can't get there
     * (boxed in, say) is put there when nobody's looking.
     */
    private fun walk(p: Townsperson, m: Member, x: Double, y: Double, z: Double, now: Long, close: Double = 0.0): Boolean {
        val d = hypot(p.x - x, p.z - z)
        if (d <= 0.06 + close && abs(p.y - y) < 1.2) {
            if (!m.arrived) {
                m.arrived = true
                p.navigation.stop()
                if (close == 0.0) p.setPos(x, y, z)
            }
            return true
        }
        if (m.arrived && d < 0.6 + close && abs(p.y - y) < 1.2) {
            // Nudged off their spot: step back onto it
            p.navigation.stop()
            if (close == 0.0) glide(p, x, y, z, 0.08)
            return true
        }
        m.arrived = false
        if (m.walkSince < 0) m.walkSince = now
        if (d < 1.4 + close && abs(p.y - y) < 1.2) {
            p.navigation.stop()
            if (close > 0.0) {
                m.arrived = true
                return true
            }
            glide(p, x, y, z, 0.11)
            if (p.activity != Activity.NONE && !p.activity.overlay) p.act(Activity.NONE)
            return false
        }
        if (now >= m.nextPath) {
            m.nextPath = now + 20
            if (!p.activity.overlay) p.act(Activity.NONE)
            p.navigation.moveTo(x, y, z, if (p.role == TownRole.CHILD) 0.75 else 0.62)
        }
        if (now - m.walkSince > STUCK && !watched(p)) {
            p.navigation.stop()
            p.snapTo(x, y, z, p.yRot, 0f)
            m.walkSince = now
        }
        return false
    }

    /** Like [walk], for a dancer catching up with the ring: once close, they're kept on their step exactly. */
    private fun join(p: Townsperson, m: Member, x: Double, y: Double, z: Double, now: Long): Boolean {
        val d = hypot(p.x - x, p.z - z)
        if (d < 1.6 && abs(p.y - y) < 1.2) {
            p.navigation.stop()
            m.arrived = true
            m.walkSince = now
            if (d > 0.02) glide(p, x, y, z, 0.3) else p.setPos(x, y, z)
            p.deltaMovement = Vec3.ZERO
            return d < 0.35
        }
        return walk(p, m, x, y, z, now).also { m.arrived = false }
    }

    /** Moves [p] straight toward the spot, at most [step] blocks this tick. */
    private fun glide(p: Townsperson, x: Double, y: Double, z: Double, step: Double) {
        val dx = x - p.x
        val dz = z - p.z
        val d = hypot(dx, dz)
        if (d <= step) p.setPos(x, y, z) else p.setPos(p.x + dx / d * step, y, p.z + dz / d * step)
        p.deltaMovement = Vec3.ZERO
    }

    /** Turns [p] toward [yaw], at most [step] degrees this tick. */
    private fun turn(p: Townsperson, yaw: Float, step: Float) {
        val diff = Mth.wrapDegrees(yaw - p.yRot)
        p.face(if (abs(diff) <= step) yaw else p.yRot + step * Math.signum(diff))
    }

    private fun watched(p: Townsperson): Boolean = (p.level() as ServerLevel).let { level -> level.players().any { sees(level, it, p.eyePosition) } }

    // =============================================================================================== the player's side

    /** A player used one of the cast: the host talks about the contest, judges produce and hears riddles answered. */
    fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        val level = p.level() as? ServerLevel ?: return false
        val m = members[p.id] ?: return false
        if (m.part != FestivalPart.HOST) {
            // Dancing or playing: no stopping now; anyone else has a word about the day
            p.attend(player, 60)
            val busy = setStart >= 0 && (m.part == FestivalPart.DANCE || m.part == FestivalPart.PLAY)
            p.say(if (busy) "fest_busy_${level.random.nextInt(BUSY_LINES)}" else "fest_chat_${kind.id}_${level.random.nextInt(CHAT_LINES)}", force = true)
            return true
        }
        p.attend(player, 100)
        p.lookAt(player)
        val stack = player.mainHandItem
        val handled = when (kind) {
            FestivalKind.SHEAFMOOT -> contest.produce(level, player, stack)
            FestivalKind.LANTERNRISE -> contest.answer(level, player, stack)
            else -> false
        }
        if (!handled) {
            player.sendSystemMessage(Component.translatable("message.guildmark.festival.host", p.name, contest.hint(player)).withColor(kind.color))
            p.say("fest_host_${level.random.nextInt(HOST_LINES)}", force = true)
        }
        p.act(Activity.WAVE)
        return true
    }

    /** A player used one of the decorations: a hidden ribbon or a riddle lantern. */
    fun useOrnament(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val be = level.getBlockEntity(pos) as? FestivalOrnamentBlockEntity ?: return
        when (be.data.kind) {
            OrnamentKind.HUNT_RIBBON -> contest.ribbon(level, player, be.data.index, pos)
            OrnamentKind.RIDDLE -> contest.read(level, player, be.data.index, pos)
            else -> {}
        }
    }

    /** An arrow from [player] struck one of the butts. */
    fun arrow(level: ServerLevel, player: ServerPlayer, at: BlockPos, face: net.minecraft.core.Direction, hit: Vec3) {
        if (kind != FestivalKind.SUNHEARTH || at !in plan.targets) return
        contest.arrow(level, player, at, face, hit, hypot(player.x - (at.x + 0.5), player.z - (at.z + 0.5)))
    }

    /** Is [entity] one of the cast, or a villager at the festival? (Arrows don't hurt them while it's on.) */
    fun shelters(entity: net.minecraft.world.entity.Entity): Boolean =
        phase.ordinal <= FestivalPhase.DUSK.ordinal && (members.containsKey(entity.id) || entity is Villager && entity.distanceToSqr(plan.centre) < plan.square * plan.square.toDouble())

    // =============================================================================================== the band

    private fun music(level: ServerLevel, now: Long, t: Int) {
        val window = when (phase) {
            FestivalPhase.FAIR -> FestivalPhase.CEREMONY.from
            FestivalPhase.DUSK -> FestivalPhase.TEARDOWN.from
            else -> -1
        }
        if (setStart >= 0) {
            val b = ((now - setStart) / FestivalDance.BAR_TICKS).toInt()
            bar = b
            if (b >= FestivalDance.SET_BARS || window < 0) endSet(level, now, rest = true)
            return
        }
        bar = -1
        if (window < 0 || now < nextSet) return
        // A set only starts if it'll finish before the fair (or the evening) does; none while the lanterns go up
        if (t + SET_TICKS + 40 > window) return
        if (lanternsAt >= 0 && now - lanternsAt < LANTERN_TIME) return
        val band = members.values.count { it.part == FestivalPart.PLAY && it.arrived }
        val dancers = members.values.count { it.part == FestivalPart.DANCE && it.arrived }
        val ready = (band > 0 || plan.band.isEmpty()) && (plan.dance == null || dancers >= 2)
        if (ready || now > nextSet + 600) startSet(level, now)
    }

    private fun startSet(level: ServerLevel, now: Long) {
        setStart = now
        bar = 0
        for (m in members.values) {
            m.lastBar = -1
            // Everyone keeping time starts on the downbeat
            if (m.part == FestivalPart.PLAY) {
                m.person.act(Activity.NONE)
                m.person.act(Activity.MINSTREL)
            }
        }
        sync(level)
    }

    private fun endSet(level: ServerLevel, now: Long, rest: Boolean) {
        setStart = -1
        bar = -1
        nextSet = now + if (rest) SET_BREAK else FIRST_SET
        for (m in members.values) {
            m.lastBar = -1
            if (m.part == FestivalPart.PLAY) m.person.act(Activity.FEST_BOW)
            m.nextIdle = now + 60
        }
        sync(level)
    }

    // =============================================================================================== the prize-giving

    private fun ceremony(level: ServerLevel, now: Long) {
        if (awarded) return
        if (phase != FestivalPhase.CEREMONY && phase != FestivalPhase.DUSK) return
        if (ceremonyAt < 0) {
            ceremonyAt = now
            ceremonyStep = 0
            contest.open = false
        }
        val e = now - ceremonyAt
        val host = members.values.firstOrNull { it.part == FestivalPart.HOST }?.person
        val results = contest.results()
        val hostName: Component = host?.name ?: Component.translatable("festival.guildmark.host")
        fun announce(key: String, vararg args: Any) {
            val line = Component.translatable(key, *args).withColor(kind.color)
            for (player in nearby(level, plan.square + 24.0)) player.sendSystemMessage(Component.translatable("message.guildmark.festival.says", hostName, line))
            host?.act(Activity.CALL_OUT)
        }
        when {
            ceremonyStep == 0 && e >= 0 -> {
                ceremonyStep = 1
                announce("message.guildmark.festival.gather", kind.title)
                host?.say("fest_gather", force = true)
                level.playSound(null, BlockPos.containing(plan.host.pos), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 1.5f, 1.1f)
            }
            ceremonyStep == 1 && e >= 120 -> {
                ceremonyStep = 2
                announce("message.guildmark.festival.contest_over", contest.title, results.size)
            }
            ceremonyStep == 2 && e >= 190 -> {
                ceremonyStep = 3
                results.getOrNull(2)?.let { announce("message.guildmark.festival.third", it.name, it.score) }
            }
            ceremonyStep == 3 && e >= 250 -> {
                ceremonyStep = 4
                results.getOrNull(1)?.let { announce("message.guildmark.festival.second", it.name, it.score) }
            }
            ceremonyStep == 4 && e >= 320 -> {
                ceremonyStep = WINNER_STEP
                val first = results.firstOrNull()
                if (first != null) announce("message.guildmark.festival.winner", first.name, first.score, ItemStack(FestivalRegistry.prize(kind)).hoverName)
                else announce("message.guildmark.festival.winner_town", io.github.vaspyyy.guildmark.quest.QuestGenerator.nameFor(key xor run), townName)
                level.playSound(null, BlockPos.containing(plan.host.pos), FestivalRegistry.FANFARE.get(), SoundSource.RECORDS, 3.0f, 1f)
                host?.say("fest_winner", force = true)
                award(level)
                repeat(6) { rocket(level, it) }
                cheer(level)
            }
        }
    }

    /** Hands out the prizes (once): the winner's prize and the rest's marks, sent on to anyone not on now. */
    private fun award(level: ServerLevel) {
        if (awarded) return
        awarded = true
        contest.open = false
        io.github.vaspyyy.guildmark.townsfolk.talk.Witness.festival(level, plan.centre, kind.id, contest.results())
        FestivalPrizes.award(level, kind, townName, contest.results())
    }

    private fun cheer(level: ServerLevel) {
        for (villager in level.getEntitiesOfClass(Villager::class.java, AABB(plan.centre, plan.centre).inflate(plan.square.toDouble(), 8.0, plan.square.toDouble()))) {
            if (villager.isSleeping) continue
            level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.8f, 0.9f + level.random.nextFloat() * 0.3f)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.x, villager.y + 2.0, villager.z, 5, 0.3, 0.3, 0.3, 0.0)
            villager.jumpControl.jump()
        }
        // A village's own folk in the crowd cheer too
        VillageFeast.cheer(level, this)
    }

    // =============================================================================================== the evening

    private fun evening(level: ServerLevel, now: Long, t: Int) {
        if (phase != FestivalPhase.DUSK) return
        if (kind == FestivalKind.LANTERNRISE) lanterns(level, now, t)
        if (plan.fireworks.isEmpty() || nearby(level, 96.0).isEmpty()) return
        // Rockets once the sun's down (the Lanternrise's after its lanterns), and a last flurry before the end
        val dark = t >= FIREWORKS_AT && (kind != FestivalKind.LANTERNRISE || lanternsAt >= 0 && now - lanternsAt > RELEASE_TICK + 80)
        if (dark && now >= nextRocket) {
            nextRocket = now + 30 + level.random.nextInt(41)
            rocket(level, level.random.nextInt(1000))
        }
        if (!finale && t >= FINALE_AT && (dark || kind == FestivalKind.LANTERNRISE)) {
            finale = true
            repeat(8) { rocket(level, it) }
        }
    }

    /** Has a rocket gone up in the last few seconds? */
    private fun rocketsUp(level: ServerLevel): Boolean = level.gameTime - lastRocket < 60

    private fun rocket(level: ServerLevel, n: Int) {
        if (plan.fireworks.isEmpty()) return
        lastRocket = level.gameTime
        val random = level.random
        val spot = plan.fireworks[Math.floorMod(n, plan.fireworks.size)]
        val palette = kind.palette
        val colors = IntList.of(palette[random.nextInt(palette.size)], palette[random.nextInt(palette.size)])
        val shape = if (kind == FestivalKind.LANTERNRISE && random.nextInt(3) == 0) FireworkExplosion.Shape.STAR else SHAPES[random.nextInt(SHAPES.size)]
        val explosion = FireworkExplosion(shape, colors, IntList.of(palette[random.nextInt(palette.size)]), random.nextBoolean(), random.nextInt(3) == 0)
        val stack = ItemStack(Items.FIREWORK_ROCKET)
        stack.set(DataComponents.FIREWORKS, Fireworks(2 + random.nextInt(2), listOf(explosion)))
        level.addFreshEntity(FireworkRocketEntity(level, spot.x + random.nextDouble() - 0.5, spot.y, spot.z + random.nextDouble() - 0.5, stack))
    }

    /** The Lanternrise's lanterns are let go together at nightfall, and more rise from all round the square. */
    private fun lanterns(level: ServerLevel, now: Long, t: Int) {
        if (lanternsAt < 0) {
            // Once it's dark, at the end of a set (not in the middle of one)
            if (t < LANTERNS_AT || setStart >= 0) return
            lanternsAt = now
            for (m in members.values) {
                if (m.part == FestivalPart.PLAY) continue
                m.person.act(Activity.RELEASE)
                // Nobody wanders off to chat in the middle of it: they watch the lanterns go
                m.nextIdle = now + LANTERN_TIME
            }
            for (player in nearby(level, plan.square + 24.0)) player.sendOverlayMessage(Component.translatable("message.guildmark.festival.lanterns").withColor(kind.color))
            return
        }
        val e = now - lanternsAt
        val type = FestivalRegistry.SKY_LANTERN.get()
        if (e == RELEASE_TICK) {
            for (m in members.values) {
                val p = m.person
                if (m.part == FestivalPart.PLAY || p.activity != Activity.RELEASE) continue
                // Just where the lantern is in their hands as the clip lets go (festival_anims.py prints it), so the
                // one in the sky takes over from the one they held (which the client stops drawing then)
                val scale = if (p.role == TownRole.CHILD) Townsperson.CHILD_SCALE.toDouble() else 1.0
                val yaw = p.yBodyRot * PI / 180
                val right = LET_GO_RIGHT * scale
                val ahead = LET_GO_FORWARD * scale
                level.sendParticles(
                    type, true, true, p.x - sin(yaw) * ahead - cos(yaw) * right, p.y + LET_GO_UP * scale, p.z + cos(yaw) * ahead - sin(yaw) * right,
                    0, 0.0, 1.0, 0.0, 0.0,
                )
            }
        }
        // Their arms open after it, and then they watch it go
        if (e == Activity.RELEASE.ticks.toLong()) for (m in members.values) if (m.person.activity == Activity.RELEASE) m.person.act(Activity.GAZE)
        if (e in RELEASE_TICK..LANTERN_TIME && e % 7 == 0L) {
            val random = level.random
            val a = random.nextDouble() * 2 * PI
            val r = 3.0 + random.nextDouble() * plan.square * 0.8
            level.sendParticles(type, true, true, cx + cos(a) * r, plan.centre.y + 1.5 + random.nextDouble(), cz + sin(a) * r, 0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    // =============================================================================================== villagers

    /** Villagers come to the green and are in high spirits, as on any feast day. */
    private fun villagers(level: ServerLevel, now: Long) {
        if (phase.ordinal > FestivalPhase.DUSK.ordinal) return
        val random = level.random
        val ring = (plan.dance?.radius ?: 2.5) + 2.6
        for (villager in level.getEntitiesOfClass(Villager::class.java, AABB(plan.centre, plan.centre).inflate(plan.square + 16.0, 8.0, plan.square + 16.0))) {
            if (villager.isSleeping || villager.isBaby && random.nextInt(2) == 0) continue
            if (random.nextInt(3) == 0) {
                val a = random.nextDouble() * 2 * PI
                val to = BlockPos.containing(cx + cos(a) * (ring + random.nextDouble() * 2), plan.centre.y, cz + sin(a) * (ring + random.nextDouble() * 2))
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(to, 0.5f, 1))
            }
            if (random.nextInt(if (setStart >= 0) 3 else 6) == 0) {
                level.playSound(null, villager.blockPosition(), if (setStart >= 0) SoundEvents.VILLAGER_CELEBRATE else SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.5f, 0.9f + random.nextFloat() * 0.3f)
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.x, villager.y + 2.1, villager.z, 3, 0.3, 0.2, 0.3, 0.0)
                if (setStart >= 0 && random.nextInt(2) == 0) villager.jumpControl.jump()
            }
        }
        // And the village's own folk, who are not of the cast, drift in to the second row of the crowd
        VillageFeast.drift(level, this, members.keys)
    }

    // =============================================================================================== telling the players

    private fun nearby(level: ServerLevel, range: Double): List<ServerPlayer> =
        level.players().filter { it.distanceToSqr(cx, it.y, cz) < range * range && abs(it.y - plan.centre.y) < 64 }

    /** Tells every player near enough what's on (and those who've left, that it's gone from their ears). */
    fun sync(level: ServerLevel) {
        lastSync = level.gameTime
        val range = plan.square + MUSIC_RANGE
        val near = if (live) nearby(level, range.toDouble()) else listOf()
        val payload = payload(level)
        for (player in near) {
            FestivalPayload.send(player, payload)
            told.add(player.uuid)
        }
        val gone = told.filter { id -> near.none { it.uuid == id } }
        if (gone.isNotEmpty()) {
            val farewell = FestivalPayload.gone(key)
            for (id in gone) (level.server.playerList.getPlayer(id))?.let { FestivalPayload.send(it, farewell) }
            told.removeAll(gone.toSet())
        }
    }

    private fun payload(level: ServerLevel): FestivalPayload = FestivalPayload(
        key, if (live) kind.ordinal else -1, cx, plan.centre.y, cz, plan.square,
        if (setStart >= 0) (level.gameTime - setStart).toInt() else -1, phase.ordinal, plan.crown?.asLong() ?: Long.MIN_VALUE, townName,
    )

    /** Tells [player] it's all over (they left the world, say). */
    fun forget(player: UUID) {
        told.remove(player)
    }

    // =============================================================================================== the scene, for anyone else

    override fun spotFor(index: Int, part: FestivalPart): FestivalSpot? {
        if (!live || phase == FestivalPhase.TEARDOWN) return null
        return when (part) {
            FestivalPart.WATCH -> {
                val spots = watchSpots()
                if (spots.isEmpty()) return null
                val s = spots[Math.floorMod(index, spots.size)]
                FestivalSpot(s.pos, s.yaw, if (setStart >= 0) Activity.CLAP_ALONG else Activity.NONE, part)
            }
            FestivalPart.DANCE -> {
                // The ring is full (it's only ever as big as the square allows): dancing on the spot, in the second
                // row of the crowd, in time with the ring
                val spots = watchSpots()
                if (spots.size <= plan.crowd.size) return spotFor(index, FestivalPart.WATCH)
                val s = spots[plan.crowd.size + Math.floorMod(index, spots.size - plan.crowd.size)]
                val activity = when {
                    setStart < 0 -> Activity.NONE
                    bar >= FestivalDance.SET_BARS - 2 -> Activity.FEST_HURRAH
                    Math.floorMod(bar + index, 4) == 3 -> Activity.TWIRL
                    else -> Activity.CLAP_ALONG
                }
                FestivalSpot(s.pos, s.yaw, activity, part)
            }
            FestivalPart.PLAY -> {
                val s = plan.band.getOrNull(Math.floorMod(index, plan.band.size.coerceAtLeast(1))) ?: return spotFor(index, FestivalPart.WATCH)
                FestivalSpot(s.pos, s.yaw, if (setStart >= 0) Activity.MINSTREL else Activity.NONE, part)
            }
            FestivalPart.HOST -> FestivalSpot(plan.host.pos, plan.host.yaw, if (phase == FestivalPhase.CEREMONY) Activity.CALL_OUT else Activity.NONE, part)
            FestivalPart.HELP -> {
                if (phase != FestivalPhase.SETUP || groups == 0) return spotFor(index, FestivalPart.WATCH)
                val group = (0 until groups).firstOrNull { !helpReady[it] && helper[it] < 0 } ?: return spotFor(index, FestivalPart.WATCH)
                val at = helpSpot(group)
                val base = plan.pieces.first { it.group == group }.pos
                FestivalSpot(at, FestivalDance.yawOf(base.x + 0.5 - at.x, base.z + 0.5 - at.z), Activity.HANG, part)
            }
        }
    }

    /** The crowd's places, and a row behind them for anyone else. */
    private var extra: List<Spot>? = null

    private fun watchSpots(): List<Spot> {
        extra?.let { return it }
        val behind = plan.crowd.map { s ->
            val dx = s.x - cx
            val dz = s.z - cz
            val d = hypot(dx, dz).coerceAtLeast(0.01)
            Spot(s.x + dx / d * 1.3, s.y, s.z + dz / d * 1.3, s.yaw)
        }
        return (plan.crowd + behind).also { extra = it }
    }

    // =============================================================================================== for the command

    fun info(level: ServerLevel): List<Component> {
        val (decor, total) = decorated()
        val count = { part: FestivalPart -> members.values.count { it.part == part } }
        val lines = ArrayList<Component>()
        lines.add(Component.translatable("commands.guildmark.festival.info", kind.title, townName, phase.title, local(level), if (forced) "forced" else "calendar"))
        lines.add(Component.translatable("commands.guildmark.festival.info.cast", count(FestivalPart.HOST), count(FestivalPart.PLAY), plan.band.size,
            count(FestivalPart.DANCE), plan.dance?.n ?: 0, count(FestivalPart.WATCH), plan.crowd.size, decor, total))
        val set = if (setStart >= 0) "bar ${bar + 1}/36" else if (nextSet > level.gameTime) "next in ${(nextSet - level.gameTime) / 20}s" else "-"
        lines.add(Component.translatable("commands.guildmark.festival.info.set", set, contest.title, contest.results().take(3).joinToString(", ") { "${it.name} ${it.score}" }.ifEmpty { "-" }))
        return lines
    }

    companion object {
        /** The morning's decorating is done by this local time. */
        const val SETUP_DONE = 950
        /** A decoration that won't go up after this many tries is left out. */
        const val MAX_TRIES = 30
        /** How long a post waits for its helper, and how long the helper reaches up before it goes up. */
        const val HELP_WAIT = 200L
        const val HANG_FIRST = 25L
        /** From the start of the fair (or the evening) to the first set; between sets; a whole set. */
        const val FIRST_SET = 200L
        const val SET_BREAK = 600L
        const val SET_TICKS = FestivalDance.SET_BARS * FestivalDance.BAR_TICKS
        /** Dancers take their places this long before a set. */
        const val GATHER = 160L
        /** Someone who's walked this long without getting there is put there (when nobody's looking). */
        const val STUCK = 300L
        /** Rockets from this local time, and a last flurry at this one. */
        const val FIREWORKS_AT = 12500
        const val FINALE_AT = 13880
        /** The Lanternrise's lanterns are let go at this local time; when they leave the hands; how long more rise. */
        const val LANTERNS_AT = 13000
        const val RELEASE_TICK = 22L
        /** Where the lantern is then, in blocks from their feet: to their right, up, and ahead (fest_release). */
        const val LET_GO_RIGHT = -0.08
        const val LET_GO_UP = 1.74
        const val LET_GO_FORWARD = 0.21
        const val LANTERN_TIME = 320L
        /** How far out from the square players are told about the music. */
        const val MUSIC_RANGE = 80
        /** The prize-giving's step when the winner is called. */
        const val WINNER_STEP = 5

        /** Lines, under say.guildmark.fest_*. */
        const val SETUP_LINES = 4
        const val CALL_LINES = 3
        const val EVENING_LINES = 3
        const val HOST_LINES = 3
        const val BUSY_LINES = 2
        const val CHAT_LINES = 3

        /** Who might host; and who stays at their work all through a fair day (the watch, and those keeping shop). */
        private val HOSTS = setOf(TownRole.NOBLE, TownRole.PRIEST, TownRole.CITIZEN)
        private val SHAPES = listOf(FireworkExplosion.Shape.SMALL_BALL, FireworkExplosion.Shape.LARGE_BALL, FireworkExplosion.Shape.BURST, FireworkExplosion.Shape.STAR)
        private val KEEP_AT_WORK = setOf(TownRole.GUARD, TownRole.KNIGHT, TownRole.MERCHANT, TownRole.INNKEEPER)
    }
}

/** The festival's hold on its cast: each tick, and each word from a player, goes to the festival. */
class FestivalDirector(private val site: FestivalSite) : Director {
    override fun tick(p: Townsperson) = site.tickMember(p)

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean = site.interact(p, player)
}
