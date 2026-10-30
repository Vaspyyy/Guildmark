package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.build.VillageBuilds
import io.github.vaspyyy.guildmark.village.live.days.VillageWatch
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import io.github.vaspyyy.guildmark.village.live.grow.Newcomers
import io.github.vaspyyy.guildmark.village.live.grow.VillageGrowth
import io.github.vaspyyy.guildmark.village.live.talk.VillageCry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A village's watch (living-world/villages/design.md 11.2; moments 11, 19, 22 and 23 of 16.1): its guards, where they come from, and
 * what they do when the horn sounds.
 *
 * **Raised by what the village builds.** `TownRole.GUARD` is no vanilla trade, so a village raises its watch as the works that need
 * one stand: two for the palisade (one in a hamlet of under eight folk), one for an archer tower (or a tower its folk built by hand,
 * which the census names), two for the stone walls, four, the garrison, for the keep: 2, 3, 5 and 9. At dawn and at six in the
 * evening the watch is reckoned ([reckon]); a post short is filled the night after, by a guard who walks in off the road with a bundle
 * ("I'm here for the watch." as they come in among the houses, to a player near enough to hear) (or is
 * at home by the record, with nobody near to see), a resident like any other, with a bed in the hall or a free one in a home. A
 * guard who fell is replaced three to seven days on. Never past the village's cap on folk, and only in a village whose folk are
 * townsfolk (one that keeps its vanilla villagers has its golems).
 *
 * **At the horn** ([horn], from [Shelter.call]) the guards on the ground run to the wall on the side the trouble comes from and
 * stand there (`STAND_GUARD`), the first two calling out ("To the wall!", "Hold the gate!" where a wall stands; "Stand firm!",
 * "Archers, ready!" where none does), and fight as they always have, going back to their place on the wall after; a guard up a
 * tower blows the horn (`SOUND_HORN`, the works' horn heard to 128 blocks) and calls down. At the all-clear ([allClear]) a held
 * siege is saluted ("Well fought.") and cheered ("They're running!"), a raid beaten off cheered, a lost one sighed over, and they go
 * back to their day. At night the guard up the tower says the watch is quiet to a player within 12 blocks (V10b).
 *
 * Nothing here is saved but the guards on their way ([TroubleData.Kept.due]); the wall is memory only, and a restart sends everyone
 * back to their day.
 */
object Watch {
    /** A hamlet of fewer folk than this raises one guard for its palisade, not two. */
    private const val HAMLET = 8

    /** A guard who fell is replaced this many days on, at the least, and up to [REPLACE_MORE] more. */
    private const val REPLACE_FROM = 3
    private const val REPLACE_MORE = 5

    /** A player within this of the bell at night sees the guard walk in off the road. */
    private const val SEEN = 64.0

    /** How far from the bell the guards are fetched to the wall, past the village's radius. */
    private const val FETCH = 48.0

    /** The guards at the wall, by the village's key: memory only. */
    private val posts = HashMap<Long, MutableList<OnWall>>()

    /** The tower guards' horns being blown. */
    private val horns = ArrayList<Horn>()

    /** The guards walking in off the road. */
    private val coming = ArrayList<Coming>()

    /** When each village's tower guard last said the watch was quiet (game time). */
    private val quiet = HashMap<Long, Long>()

    /** When each village's watch was last reckoned (the town day, twice: see [look]). */
    private val reckoned = HashMap<Long, Long>()

    /** The hour of the evening reckoning. */
    private const val EVENING = 18.0

    // ---- The watch a village wants ----

    /** The posts of [rec]'s watch, one entry for each guard it wants and what it is for ("palisade", "tower", "walls", "keep"). */
    fun wanted(level: ServerLevel, rec: VillageRec, places: VillagePlaces?): List<String> {
        val next = VillageCry.board(level, rec)?.advanceIndex ?: places?.found?.advances ?: 0
        val going = VillageBuilds.recFor(level, rec.key)?.project
        fun done(a: Advance) = next > a.ordinal && going != a.ordinal
        val folk = Residents.get(level).byCentre(BlockPos.of(rec.key)).count { it.alive }
        val out = ArrayList<String>()
        if (done(Advance.PALISADE)) repeat(if (folk < HAMLET) 1 else 2) { out += "palisade" }
        val tower = places?.lodgings?.values?.any { it.kind == "TOWER" } == true || places?.towers?.isNotEmpty() == true
        if (done(Advance.ARCHER_TOWER) || tower) out += "tower"
        if (done(Advance.STONE_WALLS)) repeat(2) { out += "walls" }
        if (done(Advance.KEEP)) repeat(4) { out += "keep" }
        return out
    }

    /** [rec]'s living guards. */
    private fun guards(level: ServerLevel, rec: VillageRec): List<Resident> =
        Residents.get(level).byCentre(BlockPos.of(rec.key)).filter { it.alive && it.role == TownRole.GUARD }

    /**
     * [rec]'s watch reckoned against what it wants on town day [day] (at dawn and again at six in the evening, so a work finished in
     * the day is manned the night after), and each post short sent for: tonight for a post new since, three to seven days on for a
     * guard who fell.
     */
    private fun reckon(level: ServerLevel, rec: VillageRec, day: Long) {
        if (!VillageAdopt.adopting(rec)) return
        val places = Villages.places(level, rec.key) ?: return
        val want = wanted(level, rec, places)
        if (want.isEmpty()) return
        val have = guards(level, rec).size
        val due = TroubleData.peek(level)?.peek(rec.key)?.due.orEmpty()
        val short = want.size - have - due.size
        if (short <= 0) return
        val living = Residents.get(level).byCentre(BlockPos.of(rec.key)).count { it.alive }
        val n = minOf(short, VillageAdopt.maxFolk() - living - due.size)
        if (n <= 0) return
        // Those who fell in the last week and are not yet sent for
        val fell = Residents.get(level).byCentre(BlockPos.of(rec.key))
            .count { !it.alive && it.died >= 0 && it.role == TownRole.GUARD && day - it.died in 0..7 } - due.count { it.why == "dead" }
        val data = TroubleData.get(level)
        val kept = data.of(rec.key)
        for (i in 0 until n) {
            val why = want.getOrElse(have + due.size + i) { want.last() }
            if (i < fell) {
                val wait = REPLACE_FROM + Math.floorMod(mix(rec.key, day * 31 + i), REPLACE_MORE.toLong()).toInt()
                kept.due += TroubleData.Due(day + wait, "dead")
            } else {
                kept.due += TroubleData.Due(day, why)
            }
        }
        data.setDirty()
        Trouble.log(level, rec, "the watch wants ${want.size}, has $have: $n sent for")
    }

    // ---- The look (every five seconds, from Trouble) ----

    /** [rec] on town day [day]: the watch reckoned twice a day; at night a guard due comes in, and the tower guard says the watch is quiet. */
    internal fun look(level: ServerLevel, rec: VillageRec, day: Long) {
        val stamp = day * 2 + if (Trouble.hour(level) >= EVENING) 1 else 0
        if (reckoned[rec.key] != stamp) {
            reckoned[rec.key] = stamp
            reckon(level, rec, day)
        }
        if (!Trouble.night(level) || Shelter.of(rec.key) != null) return
        val kept = TroubleData.peek(level)?.peek(rec.key)
        val due = kept?.due?.filter { it.day <= day }?.minByOrNull { it.day }
        if (due != null && coming.none { it.key == rec.key } && VillageAdopt.adopting(rec) && VillageAdopt.swapping(rec.key) == 0) arrive(level, rec, day, due)
        quiet(level, rec)
    }

    /** A guard for [due] comes to [rec]: off the road with a bundle if a player is near to see it, else at home out of sight. */
    private fun arrive(level: ServerLevel, rec: VillageRec, day: Long, due: TroubleData.Due) {
        val places = Villages.places(level, rec.key) ?: return
        val residents = Residents.get(level)
        val bell = BlockPos.of(rec.key)
        val living = residents.byCentre(bell).filter { it.alive }
        val data = TroubleData.get(level)
        val kept = data.of(rec.key)
        if (living.size >= VillageAdopt.maxFolk()) {
            // No room now: the post goes unfilled until the next reckoning
            kept.due.remove(due)
            data.setDirty()
            return
        }
        val (home, bed) = lodge(places, living)
        val id = residents.newId()
        val seed = mix(rec.key, id.toLong() * 7919L + day)
        val woman = (seed ushr 11) and 1L == 0L
        val style = VillageGrowth.style(level, rec)
        val look = VillageGrowth.look(TownRole.GUARD, woman, seed)
        val r = Resident(
            id, null, bell, -1, Names.given(style, woman, seed), Names.family(style, seed ushr 7), TownRole.GUARD, look, home?.id ?: -1, bed,
            false, Residents.traits(seed), true, -1L, day,
        )
        val door = home?.front
        val watched = level.getNearestPlayer(bell.x + 0.5, bell.y + 0.5, bell.z + 0.5, SEEN, false) != null
        val p = if (watched) {
            val start = Newcomers.roadEnd(level, rec, door?.pos ?: places.market, hidden = true) ?: return
            VillageGrowth.body(level, rec, places, r, start, yawTo(start, bell))?.also { b ->
                val walk = Coming(rec.key, b, door?.outside ?: places.market, rec.radius)
                Trouble.borrow(b)
                coming += walk
                b.director = walk
            }
        } else {
            val spot = if (home != null) {
                if (!level.isLoaded(home.box.center) || VillageGrowth.watched(level, home)) return
                Comings.inside(level, home) ?: return
            } else {
                VillageGrowth.ground(level, places.market) ?: return
            }
            if (!Comings.unseen(level, spot)) return
            VillageGrowth.body(level, rec, places, r, spot, VillageGrowth.into(home))
        } ?: return
        kept.due.remove(due)
        data.setDirty()
        Villages.note(level, rec.key, "watch", r.name)
        Trouble.log(level, rec, "${r.name} (#${p.resident}) came to keep the watch (${due.why})" + if (watched) ", off the road" else ", at home by the record")
    }

    /** Where a new guard lives: a free bed in the hall, a tower or a home (the hall first); else the hall's floor; else the biggest home. */
    private fun lodge(places: VillagePlaces, living: List<Resident>): Pair<CityPlaces.Lodging?, BlockPos?> {
        val taken = living.mapNotNullTo(HashSet()) { it.bed }
        val order = places.lodgings.values.sortedWith(compareBy<CityPlaces.Lodging> { if (it.kind == "GUILD_HALL") 0 else if (it.kind == "TOWER") 1 else 2 }.thenBy { it.id })
        for (l in order) {
            val bed = l.beds.firstOrNull { it !in taken } ?: continue
            return l to bed
        }
        val hall = places.lodgings.values.firstOrNull { it.kind == "GUILD_HALL" }
        if (hall != null) return hall to null
        return places.lodgings.values.filter { it.beds.isNotEmpty() }.maxByOrNull { it.beds.size }?.let { it to null } ?: (null to null)
    }

    /** At night, now and then, the guard up [rec]'s tower tells a player within 12 blocks that all is quiet (V10b). */
    private fun quiet(level: ServerLevel, rec: VillageRec) {
        val now = level.gameTime
        if (now - (quiet[rec.key] ?: Long.MIN_VALUE / 2) < QUIET_EVERY) return
        val bell = Vec3.atCenterOf(BlockPos.of(rec.key))
        val reach = rec.radius + 16.0
        for (player in level.players()) {
            if (player.isSpectator || player.distanceToSqr(bell) > reach * reach) continue
            val up = level.getEntitiesOfClass(Townsperson::class.java, AABB(player.blockPosition()).inflate(QUIET_NEAR)) {
                it.isAlive && it.role == TownRole.GUARD && VillageWatch.up(it) && Trouble.folkOf(level, it, rec.key) && it.distanceToSqr(player) < QUIET_NEAR * QUIET_NEAR
            }.firstOrNull() ?: continue
            if (Trouble.say(up, if ((now / QUIET_EVERY) % 2L == 0L) "watch_quiet_1" else "watch_quiet_2", hearing = QUIET_NEAR, force = false)) {
                quiet[rec.key] = now
                return
            }
        }
    }

    private const val QUIET_EVERY = 1800L
    private const val QUIET_NEAR = 12.0

    // ---- The horn ----

    /**
     * The horn has sounded at [c]'s village (from [Shelter.call]): the guards on the ground to the wall on the side of [Shelter.Called.from],
     * the guard up a tower to the horn. Called again for a later wave: anyone not at the wall goes back to it.
     */
    internal fun horn(level: ServerLevel, c: Shelter.Called) {
        val rec = Trouble.village(level, c.key) ?: return
        val bell = Vec3.atCenterOf(c.bell)
        val toward = c.toward()
        val reach = rec.radius + FETCH
        val bodies = guards(level, rec).mapNotNull { Comings.body(level, it) }
            .filter { it.distanceToSqr(bell) < reach * reach && it.captiveOf < 0 && !it.isPassenger && it.tale < 0 }
        val list = posts.getOrPut(c.key) { ArrayList() }
        list.removeIf { it.done }
        // Up a tower: the horn, and a call down
        val aloft = bodies.filter { VillageWatch.up(it) }
        aloft.firstOrNull()?.let { p -> if (horns.none { it.p === p }) blow(level, p, toward) }
        // On the ground: to the wall
        val ground = bodies.filter { p -> p !in aloft && list.none { it.p === p } && (p.director == null || Trouble.free(p) || p.director is Coming) }
        if (ground.isEmpty()) return
        val angle = atan2(toward.z - bell.z, toward.x - bell.x)
        val dist = maxOf(6.0, rec.radius - if (c.walled) 3.0 else 1.0)
        val n = ground.size + list.size
        var shouts = list.count { it.shout != null }
        for ((k0, p) in ground.sortedBy { it.distanceToSqr(toward) }.withIndex()) {
            val k = k0 + list.size
            val a = angle + (k - (n - 1) / 2.0) * (4.0 / dist)
            val spot = groundAt(level, BlockPos.containing(bell.x + cos(a) * dist, c.bell.y.toDouble(), bell.z + sin(a) * dist)) ?: continue
            val shout = if (shouts < 2 && Trouble.fits(p, "watch_wall_1")) wallLine(shouts++, c.walled) else null
            (p.director as? Coming)?.let { it.finish() }
            Trouble.borrow(p)
            val post = OnWall(c.key, p, spot, Vec3(bell.x + cos(a) * (dist + 24), bell.y + 1.6, bell.z + sin(a) * (dist + 24)), shout, k0)
            list += post
            p.director = post
        }
        Trouble.log(level, rec, "the watch: ${list.size} to the wall" + if (aloft.isNotEmpty()) ", the horn from the tower" else "")
    }

    /** The calls at the wall, the first and the second: the wall's own where one stands. */
    private fun wallLine(k: Int, walled: Boolean): String = if (walled) (if (k == 0) "watch_wall_1" else "watch_wall_2") else (if (k == 0) "watch_wall_3" else "watch_wall_4")

    /** Somewhere to stand at [at]'s column, near its height: not on the top of a wall, a fence or the palisade's logs. */
    private fun groundAt(level: ServerLevel, at: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5)) {
            val pos = at.above(dy)
            if (!level.isLoaded(pos) || !Places.standable(level, pos)) continue
            val under = level.getBlockState(pos.below())
            if (under.`is`(BlockTags.LOGS) || under.`is`(BlockTags.FENCES) || under.`is`(BlockTags.WALLS)) continue
            return pos
        }
        return null
    }

    /** The all-clear at the village [key], the trouble having ended [how]: the guards salute, cheer or sigh, a few at a time, and stand down. */
    internal fun allClear(level: ServerLevel, key: Long, how: Shelter.Outcome) {
        val list = posts[key] ?: return
        list.removeIf { it.done }
        for ((k, post) in list.withIndex()) post.stand(how, 20 + k * 15)
    }

    /** Everyone back to their day now (the switch turned off). */
    fun endAll() {
        for (list in posts.values) for (post in list.toList()) post.finish()
        posts.clear()
        for (c in coming.toList()) c.finish()
        coming.clear()
        horns.clear()
    }

    /** At a server stop. */
    fun forget() {
        posts.clear()
        coming.clear()
        horns.clear()
        quiet.clear()
        reckoned.clear()
    }

    /** Whether anyone is at the wall or on the way in. */
    fun any(): Boolean = posts.isNotEmpty() || coming.isNotEmpty() || horns.isNotEmpty()

    /** Each tick: the horns' blows; the finished posts and walks let go. */
    fun tick(level: ServerLevel) {
        if (horns.isNotEmpty()) {
            val now = level.gameTime
            val each = horns.iterator()
            while (each.hasNext()) {
                val h = each.next()
                val t = now - h.start
                val cut = !h.p.isAlive || h.p.level() !== level || h.p.activity != Activity.SOUND_HORN
                if (!h.blown && (t >= HORN_BLOW || cut)) {
                    h.blown = true
                    WorksBlocks.play(level, h.p.blockPosition().above(), WorksBlocks.HORN, 1f, 1f)
                }
                if (t >= Activity.SOUND_HORN.ticks || cut) {
                    each.remove()
                    // The tower calls down to the wall
                    if (h.p.isAlive) Trouble.say(h.p, "watch_wall_4", hearing = Trouble.SHOUT)
                }
            }
        }
        if (level.gameTime % 20L == 0L) {
            for (list in posts.values) list.removeIf { it.done }
            posts.values.removeIf { it.isEmpty() }
            coming.removeIf { it.done }
        }
    }

    /** The guard [p] up a tower blows the horn toward [toward]. */
    private fun blow(level: ServerLevel, p: Townsperson, toward: Vec3) {
        p.navigation.stop()
        p.face(p.yawTo(toward))
        p.lookAt(toward)
        p.act(Activity.SOUND_HORN)
        horns += Horn(p, level.gameTime)
    }

    /** SOUND_HORN's blow (its tick 12, as the works' lookouts blow it). */
    private const val HORN_BLOW = 12L

    private class Horn(val p: Townsperson, val start: Long) {
        var blown = false
    }

    private fun yawTo(from: BlockPos, to: BlockPos): Float =
        (atan2((to.z - from.z).toDouble(), (to.x - from.x).toDouble()) * (180.0 / Math.PI)).toFloat() - 90f

    private fun mix(a: Long, b: Long): Long {
        var z = a * -0x61c8864680b583ebL + b
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    // ---- The directors ----

    /**
     * A guard at the wall: there at a run, facing out, a call if theirs is one of the first two; a fight takes them off it as it would
     * anywhere, and they go back to their place after. At the all-clear a salute, a cheer or a sigh, and back to the day.
     */
    private class OnWall(val key: Long, val p: Townsperson, val spot: BlockPos, val out: Vec3, val shout: String?, val order: Int) : Director {
        var done = false
        private val walker = Walker(p)
        private val began = p.tickCount
        private var phase = 0
        private var at = 0
        private var lastTick = -1
        private var how: Shelter.Outcome? = null
        private var standAt = 0

        /** The all-clear came, [how]: they stand down [after] ticks from now. */
        fun stand(how: Shelter.Outcome, after: Int) {
            this.how = how
            standAt = p.tickCount + after
        }

        override fun tick(p: Townsperson) {
            if (done) return
            val gap = lastTick >= 0 && p.tickCount - lastTick > 5
            lastTick = p.tickCount
            if (p.tickCount - began > MOST || Shelter.of(key) == null && how == null) return finish()
            val centre = Vec3.atBottomCenterOf(spot)
            when (phase) {
                0 -> {
                    if (how != null) return finish()
                    if (at == 0 || gap) {
                        at = p.tickCount.coerceAtLeast(1)
                        walker.go(spot, Jobs.RUN, near = 1.2)
                    }
                    if (walker.tick() == Walker.State.WALKING && p.tickCount - at < 400) return
                    phase = 1
                    p.navigation.stop()
                    p.face(p.yawTo(out))
                    p.act(Activity.STAND_GUARD)
                    if (shout != null && !said) {
                        said = true
                        Trouble.say(p, shout, hearing = Trouble.SHOUT)
                    }
                }
                1 -> {
                    if (gap && p.position().distanceToSqr(centre) > 3.0 * 3.0) {
                        // Back from a fight: to their place on the wall again
                        phase = 0
                        at = 0
                        return
                    }
                    val h = how
                    if (h != null && p.tickCount >= standAt) {
                        phase = 2
                        at = p.tickCount
                        standDown(p, h)
                        return
                    }
                    p.face(p.yawTo(out))
                    if (p.activity != Activity.STAND_GUARD) p.act(Activity.STAND_GUARD)
                }
                else -> if (p.tickCount - at >= 50 || p.actDone) finish()
            }
        }

        private var said = false

        /** The all-clear's beat: held, the first salutes the defenders and the second cheers; beaten off, a cheer; lost, a sigh. */
        private fun standDown(p: Townsperson, h: Shelter.Outcome) {
            val level = p.level() as? ServerLevel
            val player = level?.getNearestPlayer(p, 24.0)
            when (h) {
                Shelter.Outcome.HELD -> if (order % 2 == 0) {
                    player?.let { p.face(p.yawTo(it.position())) }
                    p.act(Activity.SALUTE)
                    if (order == 0) Trouble.say(p, "watch_salute")
                } else {
                    p.act(Activity.CHEER)
                    if (order == 1) Trouble.say(p, "watch_cheer")
                }
                Shelter.Outcome.BEATEN -> {
                    p.act(Activity.CHEER)
                    if (order == 0) Trouble.say(p, "watch_cheer")
                }
                Shelter.Outcome.LOST -> p.act(Activity.SIGH)
                else -> p.act(Activity.NOD)
            }
        }

        fun finish() {
            if (done) return
            done = true
            Trouble.giveBack(p, this)
        }

        companion object {
            /** The longest anyone stays at the wall: the shelter's own longest, and a minute more. */
            const val MOST = Shelter.CAP.toInt() + 1200
        }
    }

    /**
     * A new guard walking in off the road with their bundle, to the door of where they will live; once they are in among the houses
     * (within the village's [reach] of the bell, or at the door), they say what they are here for to a player near enough to hear.
     */
    private class Coming(val key: Long, val p: Townsperson, val door: BlockPos, val reach: Int) : Director {
        var done = false
        private val walker = Walker(p)
        private var at = 0
        private var setDown = 0
        private var lastTick = -1
        /** Whether they have said "I'm here for the watch.": once, the first time a player hears it. */
        private var told = false

        override fun tick(p: Townsperson) {
            if (done) return
            if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0) return finish()
            lastTick = p.tickCount
            if (!told && (setDown > 0 || within(p))) {
                told = Trouble.say(p, "watch_arrive", force = false)
                if (told) (p.level() as? ServerLevel)?.getNearestPlayer(p, Trouble.HEARD)?.let { p.glance(it, 40) }
            }
            if (setDown > 0) {
                if (p.tickCount - setDown >= Activity.PUT_DOWN.ticks) finish()
                return
            }
            if (at == 0) {
                at = p.tickCount.coerceAtLeast(1)
                walker.go(door, Jobs.WALK, near = 1.5)
            }
            if (p.activity != Activity.CARRY_SACK) p.act(Activity.CARRY_SACK)
            val s = walker.tick()
            if (s == Walker.State.WALKING && p.tickCount - at < 2400) return
            if (s == Walker.State.FAILED || p.tickCount - at >= 2400) return finish()
            p.navigation.stop()
            p.act(Activity.PUT_DOWN)
            setDown = p.tickCount
        }

        /** Whether [p] is inside the village: the square of its reach round the bell, as [Villages] reckons a village's ground. */
        private fun within(p: Townsperson): Boolean =
            kotlin.math.abs(p.blockX - BlockPos.getX(key)) <= reach && kotlin.math.abs(p.blockZ - BlockPos.getZ(key)) <= reach

        fun finish() {
            if (done) return
            done = true
            Trouble.giveBack(p, this)
        }
    }

    // ---- For the commands ----

    /** What `village watch` says of [rec]'s watch: wanted and kept, on its way, up the tower and at the wall. */
    fun describe(level: ServerLevel, rec: VillageRec): List<String> {
        val places = Villages.places(level, rec.key)
        val want = wanted(level, rec, places)
        val have = guards(level, rec)
        val out = ArrayList<String>()
        val why = want.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
        out += "The watch: ${have.size} guard${if (have.size == 1) "" else "s"}" + if (want.isEmpty()) " (nothing built yet that wants one)" else " of ${want.size} wanted ($why)"
        if (!VillageAdopt.adopting(rec)) out += "  This village keeps its vanilla villagers: no watch is raised for it."
        for (r in have) {
            val p = Comings.body(level, r)
            val where = when {
                p == null -> "no body about"
                VillageWatch.up(p) -> "up the tower"
                posts[rec.key]?.any { it.p === p && !it.done } == true -> "at the wall"
                else -> "at ${p.blockPosition().toShortString()}"
            }
            out += "  ${r.name} (#${r.id}): $where"
        }
        val due = TroubleData.peek(level)?.peek(rec.key)?.due.orEmpty()
        if (due.isNotEmpty()) {
            val today = VillageBoards.day(level)
            out += "  On the way: " + due.joinToString(", ") { if (it.day <= today) "one for the ${it.why} tonight" else "one for the ${it.why} on day ${it.day}" }
        }
        return out
    }

    /** What `village info` adds of [rec]'s watch, in a line. */
    fun line(level: ServerLevel, rec: VillageRec): String? {
        val want = wanted(level, rec, Villages.places(level, rec.key))
        val have = guards(level, rec).size
        val due = TroubleData.peek(level)?.peek(rec.key)?.due?.size ?: 0
        if (want.isEmpty() && have == 0 && due == 0) return null
        return "$have guard${if (have == 1) "" else "s"} of ${want.size} wanted" + if (due > 0) ", $due on the way" else ""
    }
}
