package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.phys.Vec3

/**
 * The mending after a lost siege (living-world/villages/design.md 11.6; 16.1 moment 18). From the morning [VillageDamage.lost] set
 * (the next working morning, the second in hard times), in the working hours, two to four of the village's folk with no other duty
 * that day (citizens and smiths first) carry the broken logs and lanterns out from a pile at the green: a pick-up at the pile, the
 * walk with the logs on the shoulder (`CARRY_BLOCK`), and each laid low, at the waist or high (`LAY_LOW`, `LAY`, `LAY_HIGH`) into its
 * gap, the bottom courses first and the lanterns last, with the block's own sound and a puff of dust; a word now and then
 * (`village_mend_*`), and "That's mended" at the end. A gap that something else has filled since is left as it is. Nothing is paid
 * for: the logs are the village's own.
 *
 * This is a crew of its own and not one of V9's building sites (a village has one project at a time, and a mend must not end one).
 * With no player near the village the record puts the blocks back at the crew's pace, as the lamps are kept; three days after the
 * mend set out whatever is left is put back as soon as nobody sees it, so a village with nobody to lend is mended too. When the last
 * is in, the chronicle notes it ("mended"), and the village is counted again (its lamps and towers as they stand now).
 */
object Mend {
    /** The working hours of the mend. */
    private const val FROM = 7.0
    private const val TO = 17.75

    /** Days after the mend set out that whatever is left is put back by the record. */
    private const val MOST_DAYS = 3L

    /** The crew: at least, at most, and one more for every this many blocks. */
    private const val CREW_MIN = 2
    private const val CREW_MAX = 4
    private const val CREW_PER = 80

    /** Blocks carried in one trip from the pile. */
    private const val CARRY = 4

    /** A player within this of the village sees the crew at work; one within [UNSEEN] of a gap sees it filled. */
    private const val NEAR = 32.0
    private const val UNSEEN = 48.0

    /** How far from the pile a member of the crew is fetched. */
    private const val FETCH = 96.0

    /** The ticks of the lay clips the block meets the wall (as `BuilderCrew`'s, from art/scripts/homestead_anims.py). */
    private const val LAY_AT = 11
    private const val LAY_LOW_AT = 14
    private const val LAY_HIGH_AT = 12

    /** The mends under way with a crew, by the village's key: memory only. */
    private val crews = HashMap<Long, Crew>()

    /** A village's crew at work: where the pile is, who is in it and which gaps they have taken. */
    private class Crew(val key: Long, val pile: BlockPos) {
        val members = ArrayList<Mender>()
        /** Gaps someone is carrying to, and gaps no one could reach today (packed positions). */
        val claimed = HashSet<Long>()
        val skipped = HashSet<Long>()
        var begun = false
        var lastWord = 0L
    }

    // ---- The look (every five seconds, from Trouble) ----

    /** [rec]'s mend on town day [day]: the crew out in working hours, the record's share where nobody sees, the end. */
    internal fun look(level: ServerLevel, rec: VillageRec, day: Long) {
        val data = TroubleData.peek(level) ?: return
        val kept = data.peek(rec.key) ?: return
        if (kept.mendFrom == TroubleData.NONE) return
        if (kept.damage.isEmpty()) return done(level, rec, kept)
        if (day < kept.mendFrom) return
        if (Shelter.of(rec.key) != null) return stop(rec.key)
        if (kept.mendBegun == TroubleData.NONE) {
            kept.mendBegun = day
            data.setDirty()
            Trouble.log(level, rec, "the mend sets out: ${kept.damage.size} blocks to put back")
        }
        // Three days on, whatever is left goes back as soon as nobody sees it, at any hour
        if (day - kept.mendFrom >= MOST_DAYS) {
            stop(rec.key)
            byRecord(level, rec, kept, Int.MAX_VALUE)
            if (kept.damage.isEmpty()) done(level, rec, kept)
            return
        }
        val hour = Trouble.hour(level)
        if (hour < FROM || hour >= TO) return stop(rec.key)
        val bell = Vec3.atCenterOf(BlockPos.of(rec.key))
        val reach = rec.radius + NEAR
        val watched = level.players().any { !it.isSpectator && it.distanceToSqr(bell) < reach * reach }
        if (!watched) {
            // Nobody near: the record works at the crew's pace (a mend of a few hundred blocks in about half a working day)
            stop(rec.key)
            byRecord(level, rec, kept, maxOf(2, (kept.damage.size + kept.mended + 53) / 54))
            if (kept.damage.isEmpty()) done(level, rec, kept)
            return
        }
        val crew = crews.getOrPut(rec.key) { Crew(rec.key, pile(level, rec)) }
        crew.members.removeIf { it.done }
        val want = (CREW_MIN + kept.damage.size / CREW_PER).coerceIn(CREW_MIN, CREW_MAX)
        if (crew.members.size < want) hire(level, rec, crew, want - crew.members.size)
        if (crew.members.isEmpty()) {
            // Nobody to lend: the gaps nobody sees go back by the record, the rest wait
            byRecord(level, rec, kept, 2)
            return
        }
        // The gaps no one could get to: the record's, where nobody sees them
        if (crew.skipped.isNotEmpty()) {
            val left = kept.damage.filter { it.pos in crew.skipped }
            for (b in left) if (unseen(level, BlockPos.of(b.pos))) put(level, rec, kept, b, seen = false, by = null)
        }
        if (!crew.begun) {
            crew.begun = true
            crew.members.firstOrNull()?.let { Trouble.say(it.p, "village_mend_start") }
        } else if (level.gameTime - crew.lastWord > 900L && crew.members.isNotEmpty()) {
            val m = crew.members[Math.floorMod(level.gameTime / 900L, crew.members.size.toLong()).toInt()]
            if (Trouble.say(m.p, if ((level.gameTime / 900L) % 2L == 0L) "village_mend_1" else "village_mend_2", force = false)) crew.lastWord = level.gameTime
        }
    }

    /** Where the crew's pile is: the green's edge nearest the most of the damage, on open ground; the green itself if none. */
    private fun pile(level: ServerLevel, rec: VillageRec): BlockPos {
        val green = Villages.places(level, rec.key)?.market ?: BlockPos.of(rec.key)
        val damage = TroubleData.peek(level)?.peek(rec.key)?.damage.orEmpty()
        if (damage.isEmpty()) return green
        val mx = damage.sumOf { BlockPos.getX(it.pos).toDouble() } / damage.size
        val mz = damage.sumOf { BlockPos.getZ(it.pos).toDouble() } / damage.size
        val a = Math.atan2(mz - green.z, mx - green.x)
        for (r in intArrayOf(5, 4, 6, 3, 7)) {
            val at = BlockPos.containing(green.x + Math.cos(a) * r, green.y.toDouble(), green.z + Math.sin(a) * r)
            GroundNear.stand(level, at)?.let { return it }
        }
        return green
    }

    /** Takes [n] more of [rec]'s folk into [crew]: free today, citizens and smiths first, grown, no guard, near the pile. */
    private fun hire(level: ServerLevel, rec: VillageRec, crew: Crew, n: Int) {
        val places = Villages.places(level, rec.key) ?: return
        val day = Hands.of(level, places)
        val busy = HashSet<Int>().apply {
            addAll(day.carters); addAll(day.keepers.keys); addAll(day.lamps); addAll(day.builders); addAll(day.wagon); addAll(day.night)
            day.bellman?.let { add(it) }
            day.poster?.let { add(it) }
        }
        val pile = Vec3.atCenterOf(crew.pile)
        val order = Residents.get(level).byCentre(BlockPos.of(rec.key))
            .filter { it.alive && it.role.adult && it.role != TownRole.GUARD && it.id !in busy && crew.members.none { m -> m.p.resident == it.id } }
            .sortedWith(compareBy({ if (it.role == TownRole.CITIZEN || it.role == TownRole.SMITH) 0 else 1 }, { it.id }))
        var hired = 0
        for (r in order) {
            if (hired >= n) return
            val p = Comings.body(level, r) ?: continue
            if (!Trouble.free(p) || p.distanceToSqr(pile) > FETCH * FETCH || !level.isPositionEntityTicking(p.blockPosition())) continue
            Trouble.borrow(p)
            val m = Mender(crew, p)
            crew.members += m
            p.director = m
            hired++
        }
    }

    /** Lets [key]'s crew go home (the hours are over, a shelter, nobody near). */
    private fun stop(key: Long) {
        val crew = crews.remove(key) ?: return
        for (m in crew.members.toList()) m.finish()
    }

    /** The record puts back up to [most] of [rec]'s gaps that nobody sees, the bottom first. */
    private fun byRecord(level: ServerLevel, rec: VillageRec, kept: TroubleData.Kept, most: Int) {
        var n = 0
        for (b in order(kept.damage)) {
            if (n >= most) return
            val pos = BlockPos.of(b.pos)
            if (!level.isLoaded(pos) || !unseen(level, pos)) continue
            put(level, rec, kept, b, seen = false, by = null)
            n++
        }
    }

    /** The gaps in the order they are filled: the lowest courses first, the lanterns (which hang from them) last. */
    private fun order(damage: List<TroubleData.Broken>): List<TroubleData.Broken> =
        damage.sortedWith(compareBy({ if (it.state.block is LanternBlock) 1 else 0 }, { BlockPos.getY(it.pos) }))

    private fun unseen(level: ServerLevel, pos: BlockPos): Boolean =
        level.getNearestPlayer(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, UNSEEN, false) == null

    /**
     * [b] put back into its gap if the gap is still open (air, or something a block replaces), else let go: off the damage list either
     * way. Seen, with the block's sound and a puff of dust; [by] laid it, or the record.
     */
    private fun put(level: ServerLevel, rec: VillageRec, kept: TroubleData.Kept, b: TroubleData.Broken, seen: Boolean, by: Townsperson?): Boolean {
        val pos = BlockPos.of(b.pos)
        if (!level.isLoaded(pos)) return false
        kept.damage.remove(b)
        val here = level.getBlockState(pos)
        val open = here.isAir || here.canBeReplaced()
        TroubleData.get(level).setDirty()
        if (!open) return false
        level.setBlock(pos, b.state, Block.UPDATE_ALL)
        kept.mended++
        if (seen) {
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, b.state), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 10, 0.3, 0.3, 0.3, 0.08)
            val sound = b.state.soundType
            level.playSound(null, pos, sound.placeSound, SoundSource.BLOCKS, (sound.volume + 1f) / 2f, sound.pitch * 0.8f)
        }
        return true
    }

    /** The last gap is filled: "That's mended", the chronicle's line, the count again, and the crew home. */
    private fun done(level: ServerLevel, rec: VillageRec, kept: TroubleData.Kept) {
        val crew = crews[rec.key]
        crew?.members?.firstOrNull { !it.done }?.let { Trouble.say(it.p, "village_stage_mended") }
        stop(rec.key)
        val n = kept.mended
        kept.mendFrom = TroubleData.NONE
        kept.mendBegun = TroubleData.NONE
        kept.mended = 0
        TroubleData.get(level).setDirty()
        if (n > 0) {
            Villages.note(level, rec.key, "mended", n.toString())
            Villages.recount(level, rec.key)
        }
        Trouble.log(level, rec, "the mend is done: $n blocks put back")
    }

    /** Everyone back to their day (the switch turned off). */
    fun endAll() {
        for (key in crews.keys.toList()) stop(key)
    }

    fun any(): Boolean = crews.isNotEmpty()

    /** At a server stop. */
    fun forget() = crews.clear()

    /** `village mend now`: [rec]'s mend set out today, at once (the hours and the morning waited for no longer). */
    fun now(level: ServerLevel, rec: VillageRec): Boolean {
        val data = TroubleData.peek(level) ?: return false
        val kept = data.peek(rec.key) ?: return false
        if (kept.damage.isEmpty()) return false
        val today = io.github.vaspyyy.guildmark.block.VillageBoards.day(level)
        kept.mendFrom = today
        if (kept.mendBegun == TroubleData.NONE) kept.mendBegun = today
        data.setDirty()
        return true
    }

    /** What `village mend` says of [rec]'s mend. */
    fun describe(level: ServerLevel, rec: VillageRec): List<String> {
        val kept = TroubleData.peek(level)?.peek(rec.key)
        val out = ArrayList<String>()
        val left = kept?.damage.orEmpty()
        if (left.isEmpty() && kept?.mendFrom == null || left.isEmpty() && kept?.mendFrom == TroubleData.NONE) {
            out += "Nothing to mend: no siege has broken anything here (or it is all put back)."
            return out
        }
        val logs = left.count { it.state.block !is LanternBlock }
        out += "To mend: ${left.size} blocks ($logs logs and the like, ${left.size - logs} lanterns), ${kept?.mended ?: 0} put back so far."
        val from = kept?.mendFrom ?: TroubleData.NONE
        out += if (from == TroubleData.NONE) "No mend set (a lost siege sets one for the next working morning)." else "The mend sets out on day $from, from seven to a quarter to six; three days on, what is left is put back by the record."
        crews[rec.key]?.let { c ->
            out += "At work now: " + c.members.filter { !it.done }.joinToString(", ") { it.p.name.string } + "; the pile at ${c.pile.toShortString()}"
        }
        left.firstOrNull()?.let { out += "The first still to do: ${BlockPos.of(it.pos).toShortString()} (${it.state.block.name.string})" }
        return out
    }

    // ---- One of the crew ----

    /**
     * One of the mending crew: to the pile and a pick-up, out to the next gaps with up to [CARRY] blocks on the shoulder, each laid into
     * its gap, and back for more, until the mend is done or the crew goes home.
     */
    private class Mender(val crew: Crew, val p: Townsperson) : Director {
        var done = false
        private val walker = Walker(p)
        private var phase = 0
        private var at = 0
        private var lastTick = -1
        private val load = ArrayList<TroubleData.Broken>()
        private var stand: BlockPos? = null
        private var clip = Activity.LAY
        private var laid = false

        override fun tick(p: Townsperson) {
            if (done) return
            if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0) return finish()
            lastTick = p.tickCount
            val level = p.level() as? ServerLevel ?: return finish()
            val rec = Villages.byKey(level, crew.key) ?: return finish()
            val kept = TroubleData.peek(level)?.peek(crew.key) ?: return finish()
            when (phase) {
                // To the pile
                0 -> {
                    if (at == 0) {
                        at = p.tickCount.coerceAtLeast(1)
                        walker.go(crew.pile, Jobs.BRISK, near = 1.6)
                    }
                    if (walker.tick() == Walker.State.WALKING && p.tickCount - at < 600) return
                    if (!take(level, kept)) return finish()
                    p.navigation.stop()
                    p.face(p.yawTo(Vec3.atCenterOf(crew.pile)))
                    p.act(Activity.PICK_UP)
                    phase = 1
                    at = p.tickCount
                }
                // The pick-up, and away with the load
                1 -> {
                    if (p.tickCount - at < Activity.PICK_UP.ticks) return
                    p.carried = ItemStack(load.first().state.block.asItem(), load.size)
                    if (!next(level)) home()
                }
                // Out to the gap, the logs on the shoulder
                2 -> {
                    if (p.activity != Activity.CARRY_BLOCK) p.act(Activity.CARRY_BLOCK)
                    val s = walker.tick()
                    if (s == Walker.State.WALKING && p.tickCount - at < 600) return
                    val gap = load.first()
                    val pos = BlockPos.of(gap.pos)
                    if (s == Walker.State.FAILED || p.position().distanceToSqr(Vec3.atCenterOf(pos)) > 5.0 * 5.0) {
                        // Could not get there: the record's, once nobody sees it
                        crew.skipped += gap.pos
                        crew.claimed.remove(gap.pos)
                        load.removeAt(0)
                        if (!next(level)) home()
                        return
                    }
                    p.navigation.stop()
                    p.face(p.yawTo(Vec3.atCenterOf(pos)))
                    p.lookAt(Vec3.atCenterOf(pos))
                    val dy = pos.y - p.blockPosition().y
                    clip = when {
                        dy <= -1 -> Activity.LAY_LOW
                        dy >= 2 -> Activity.LAY_HIGH
                        else -> Activity.LAY
                    }
                    p.act(clip)
                    laid = false
                    phase = 3
                    at = p.tickCount
                }
                // Laid into the gap at the clip's contact
                3 -> {
                    val t = p.tickCount - at
                    val contact = when (clip) {
                        Activity.LAY_LOW -> LAY_LOW_AT
                        Activity.LAY_HIGH -> LAY_HIGH_AT
                        else -> LAY_AT
                    }
                    if (!laid && t >= contact) {
                        laid = true
                        val gap = load.removeAt(0)
                        crew.claimed.remove(gap.pos)
                        put(level, rec, kept, gap, seen = true, by = p)
                        p.carried = if (load.isEmpty()) ItemStack.EMPTY else ItemStack(load.first().state.block.asItem(), load.size)
                    }
                    if (t < clip.ticks && !p.actDone) return
                    // The last of it: "That's mended", and the crew home
                    if (kept.damage.isEmpty()) return done(level, rec, kept)
                    if (!next(level)) home()
                }
            }
        }

        /** Takes the next gaps from the list (the bottom first, the nearest to the first after it), as many as one trip carries. */
        private fun take(level: ServerLevel, kept: TroubleData.Kept): Boolean {
            load.clear()
            val open = order(kept.damage).filter { it.pos !in crew.claimed && it.pos !in crew.skipped && level.isLoaded(BlockPos.of(it.pos)) }
            val first = open.firstOrNull() ?: return false
            val firstPos = BlockPos.of(first.pos)
            load += first
            // The rest of the load: the same kind of block, the nearest to the first, no higher a course than the next to fill
            val course = BlockPos.getY(first.pos)
            for (b in open.drop(1).filter { it.state.block == first.state.block && BlockPos.getY(it.pos) <= course + 1 }.sortedBy { BlockPos.of(it.pos).distSqr(firstPos) }) {
                if (load.size >= CARRY || BlockPos.of(b.pos).distSqr(firstPos) > 16.0) break
                load += b
            }
            for (b in load) crew.claimed += b.pos
            return true
        }

        /** Off to the next gap of the load, to where to stand to reach it; false once nothing of the load is left that can be reached. */
        private fun next(level: ServerLevel): Boolean {
            while (load.isNotEmpty()) {
                val gap = load.first()
                val pos = BlockPos.of(gap.pos)
                val here = stand?.takeIf { it.distSqr(pos) <= 3.0 * 3.0 && kotlin.math.abs(it.y - pos.y) <= 3 }
                val spot = here ?: standFor(level, pos)
                if (spot == null) {
                    crew.skipped += gap.pos
                    crew.claimed.remove(gap.pos)
                    load.removeAt(0)
                    continue
                }
                stand = spot
                phase = 2
                at = p.tickCount.coerceAtLeast(1)
                walker.go(spot, Jobs.WALK, near = 0.8)
                return true
            }
            return false
        }

        /** Back to the pile for more. */
        private fun home() {
            for (b in load) crew.claimed.remove(b.pos)
            load.clear()
            p.carried = ItemStack.EMPTY
            phase = 0
            at = 0
            stand = null
        }

        /** A place to stand within reach of the gap at [gap]: inside the village first (nearer its bell), on the ground below the gap. */
        private fun standFor(level: ServerLevel, gap: BlockPos): BlockPos? {
            val bell = BlockPos.of(crew.key)
            val sides = Direction.Plane.HORIZONTAL.sortedBy { gap.relative(it).distSqr(bell) }
            for (dy in intArrayOf(0, -1, -2, -3, 1)) for (d in sides) for (k in 1..2) {
                val pos = gap.relative(d, k).above(dy)
                if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
            }
            return null
        }

        fun finish() {
            if (done) return
            done = true
            for (b in load) crew.claimed.remove(b.pos)
            load.clear()
            p.carried = ItemStack.EMPTY
            Trouble.giveBack(p, this)
        }
    }
}
