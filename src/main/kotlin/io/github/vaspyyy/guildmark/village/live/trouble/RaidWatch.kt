package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Lamplighter
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.VillageSounds
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import io.github.vaspyyy.guildmark.village.live.links.Greeter
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.raid.Raid
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Raids on a village of the registry, and its captives (living-world/villages/design.md 11.3).
 *
 * An outlaws' raid (`Raids`) calls in as it starts ([started]: the folk to their doors, the bell rung hard), is beaten off
 * ([beaten]: the fortunes' HELD, the chronicle, the cheer), takes one of the folk ([taken]: a resident is put away, their home and bed
 * kept, and the same resident is the body in the camp), and ends ([over]: the all-clear). The freed captive walks home: at the board
 * their household runs to them and holds them, the neighbours gather and cheer, the bell rings once ([homecoming]), and once home they
 * are their own record again ([home]). One the band no longer holds (its camp gone) is put to rest as taken and never found ([daily]).
 *
 * The Bad Omen raid (vanilla's own): while a village is LIVE its bell is asked once a second for a raid; one seen active calls the
 * shelter and arms every raider against the townsfolk, as a siege's horde is ([SiegeHorde.goal]), again as each wave comes; at its end
 * the all-clear, and on a victory the fortunes' HELD. A village whose folk are not kept (KEEP mode) leaves it to vanilla.
 *
 * A capital's raids, a bell no village has, a tale's captive and the switch off: nothing here, and `Raids` and `Captives` run as before
 * (with the switch off a resident already away still comes home as themselves, so nobody is lost to it).
 */
object RaidWatch {
    /** An outlaws' raid on a village of the registry: whence it came, its raiders, and how it went. */
    private class Foray(val key: Long, val bell: BlockPos, val raidBell: BlockPos, val raiders: Set<UUID>) {
        var outcome: Shelter.Outcome? = null
        var over = false
    }

    /** A vanilla raid at a village: the raiders armed so far, and whether its end is seen to. */
    private class Omen(val key: Long, val raid: Raid) {
        val armed = HashSet<UUID>()
    }

    private val forays = HashMap<Long, Foray>()
    private val omens = HashMap<Long, Omen>()
    private val scenes = ArrayList<Welcome>()

    // ---- An outlaws' raid ----

    /** The raid on the village round [bell], coming from [from] with [raiders], has sounded its horn. */
    fun started(level: ServerLevel, bell: BlockPos, from: BlockPos, raiders: Collection<UUID>) {
        val rec = Trouble.village(level, bell) ?: return
        forays[rec.key] = Foray(rec.key, BlockPos.of(rec.key), bell, raiders.toSet())
        Shelter.call(level, rec.key, BlockPos.of(rec.key), rec.radius + 8, Shelter.Why.RAID, from)
        Trouble.log(level, rec, "a raid: ${raiders.size} raiders")
    }

    /** Every raider down at the village round [bell]: beaten off. */
    fun beaten(level: ServerLevel, bell: BlockPos) {
        val rec = Trouble.village(level, bell) ?: return
        val f = forays[rec.key]
        f?.outcome = Shelter.Outcome.BEATEN
        val fighters = level.players().filter { !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(bell)) < 64.0 * 64.0 }.map { it.name.string }
        Fortunes.note(level, rec.key, Noted.HELD, listOf("raid") + fighters)
        Villages.note(level, rec.key, "raid", "beaten")
        Shelter.end(level, rec.key, Shelter.Outcome.BEATEN)
        Trouble.log(level, rec, "the raid beaten off")
    }

    /**
     * The raiders of [band] got clear of the village round [bell] with [taken]: if they are one of its residents, their record is put
     * away (not alive, not dead, home and bed kept) and its id given back, for the captive in the camp to be them; else -1, as before.
     */
    fun taken(level: ServerLevel, bell: BlockPos, taken: Mob, band: OutlawBand): Int {
        val rec = Trouble.village(level, bell) ?: return -1
        val p = taken as? Townsperson ?: return -1
        val r = Trouble.residentOf(level, p, rec.key)?.takeIf { it.alive } ?: return -1
        val day = VillageBoards.day(level)
        Residents.get(level).away(r.id, day)
        val data = TroubleData.get(level)
        val kept = data.of(rec.key)
        kept.taken.removeIf { it.id == r.id }
        kept.taken.add(TroubleData.Taken(r.id, r.name, day, band.id))
        data.setDirty()
        forays[rec.key]?.outcome = Shelter.Outcome.TAKEN
        Villages.note(level, rec.key, "raid", "taken")
        Villages.note(level, rec.key, "taken", r.name)
        Trouble.log(level, rec, "${r.name} carried off by the band")
        return r.id
    }

    /** The raid on the village round [bell] is over, however (memory only: [tick] gives the all-clear). */
    fun over(bell: BlockPos) {
        for (f in forays.values) if (f.raidBell == bell) f.over = true
    }

    /** Whether a raid's raiders are still about the village [key] (within its reach and a little more). */
    fun on(level: ServerLevel, key: Long): Boolean {
        val centre = Vec3.atCenterOf(BlockPos.of(key))
        val reach = (Villages.byKey(level, key)?.radius ?: 48) + 40.0
        val ids = (forays[key]?.raiders.orEmpty()) + (omens[key]?.armed.orEmpty())
        return ids.any { id -> (level.getEntity(id) as? Mob)?.let { it.isAlive && it.distanceToSqr(centre) < reach * reach } == true }
    }

    // ---- The tick ----

    /** Each tick: the raids' ends seen to; once a second the bells asked for a Bad Omen raid; the homecomings. */
    fun tick(level: ServerLevel) {
        if (forays.isNotEmpty()) {
            for (f in forays.values.toList()) {
                if (!f.over) continue
                forays.remove(f.key)
                val how = f.outcome ?: Shelter.Outcome.QUIET
                if (f.outcome == null) Villages.note(level, f.key, "raid", "over")
                Shelter.end(level, f.key, how)
            }
        }
        if (scenes.isNotEmpty()) scenes.removeIf { it.tick(level) }
        if (level.gameTime % 20L == 0L) omens(level)
    }

    /** Once a second: each LIVE village's bell asked for a vanilla raid; one under way is answered, and one ended seen to. */
    private fun omens(level: ServerLevel) {
        for (rec in Villages.awake(level)) {
            if (rec.state != Live.LIVE) continue
            val bell = BlockPos.of(rec.key)
            if (!level.isLoaded(bell)) continue
            val raid = level.getRaidAt(bell)
            val o = omens[rec.key]
            if (o != null && (raid !== o.raid || o.raid.isOver)) {
                omens.remove(rec.key)
                ended(level, rec, o)
                continue
            }
            if (raid == null || raid.isOver || !raid.isActive) continue
            // A village whose folk are not kept leaves the raid to vanilla
            if (!VillageAdopt.adopting(rec)) continue
            val now = o ?: Omen(rec.key, raid).also {
                omens[rec.key] = it
                Shelter.call(level, rec.key, bell, rec.radius + 8, Shelter.Why.RAID, raid.center)
                Trouble.log(level, rec, "a raid of the Bad Omen")
            }
            for (raider in raid.allRaiders) {
                if (!raider.isAlive || !now.armed.add(raider.uuid)) continue
                SiegeHorde.goal(raider, rec.key)
            }
            if (Shelter.of(rec.key)?.ending != null || Shelter.of(rec.key) == null) {
                // A wave after the all-clear (they came out between waves): back in
                if (raid.allRaiders.any { it.isAlive && it.distanceToSqr(Vec3.atCenterOf(bell)) < (rec.radius + 40.0) * (rec.radius + 40.0) }) {
                    Shelter.call(level, rec.key, bell, rec.radius + 8, Shelter.Why.RAID, raid.center)
                }
            }
        }
    }

    private fun ended(level: ServerLevel, rec: VillageRec, o: Omen) {
        val won = o.raid.isVictory
        if (won) {
            val fighters = level.players().filter { !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(BlockPos.of(rec.key))) < 96.0 * 96.0 }.map { it.name.string }
            Fortunes.note(level, rec.key, Noted.HELD, listOf("raid") + fighters)
            Villages.note(level, rec.key, "raid", "beaten")
        } else {
            Villages.note(level, rec.key, "raid", "over")
        }
        Shelter.end(level, rec.key, if (won) Shelter.Outcome.BEATEN else if (o.raid.isLoss) Shelter.Outcome.LOST else Shelter.Outcome.QUIET)
        Trouble.log(level, rec, "the Bad Omen raid ended: " + if (won) "won" else if (o.raid.isLoss) "lost" else "over")
    }

    // ---- The captives ----

    /** The village [p] was taken from, and its record of them, if a raid took them as one of its residents. */
    private fun takenFrom(level: ServerLevel, p: Townsperson): Pair<Long, TroubleData.Taken>? {
        if (p.resident < 0) return null
        val data = TroubleData.peek(level) ?: return null
        for ((key, kept) in data.villages) kept.taken.firstOrNull { it.id == p.resident }?.let { return key to it }
        return null
    }

    /** [p], freed, is home and thanked (`Captives.release`): the resident a raid took is themselves again, as they were. */
    fun home(level: ServerLevel, p: Townsperson) {
        val (key, t) = takenFrom(level, p) ?: return
        val data = TroubleData.get(level)
        data.peek(key)?.taken?.removeIf { it.id == t.id }
        data.setDirty()
        val residents = Residents.get(level)
        val r = residents[t.id]
        if (r == null || r.died >= 0) {
            // Put to rest meanwhile: whoever came home is someone of the village with no record, as a freed villager is
            p.resident = -1
            return
        }
        residents.restore(t.id, p)
        p.home = r.bed ?: p.home
        Villages.byKey(level, key)?.let { Trouble.log(level, it, "${t.name} home again") }
    }

    /** [p], freed, is back at their village's board (`Captives.homecoming`): their household runs to them, the neighbours cheer, the bell rings once. */
    fun homecoming(level: ServerLevel, p: Townsperson, home: BlockPos) {
        val (key, t) = takenFrom(level, p) ?: return
        val rec = Villages.byKey(level, key) ?: return
        Villages.note(level, key, "rescued", t.name)
        Trouble.log(level, rec, "${t.name} brought home")
        if (!Trouble.on()) return
        val bell = BlockPos.of(key)
        Trouble.ring(level, bell, 1, 1, after = 20)
        VillageSounds.play(level, p.position().add(0.0, 1.0, 0.0), VillageSounds.CHEER)
        val residents = Residents.get(level)
        val homeId = residents[t.id]?.home ?: -1
        val near = level.getEntitiesOfClass(Townsperson::class.java, AABB(p.blockPosition()).inflate(32.0, 8.0, 32.0)) { other ->
            other !== p && other.isAlive && other.resident >= 0 && other.captiveOf < 0 && !other.isPassenger && free(other) &&
                residents[other.resident]?.let { it.capital.asLong() == key && it.alive } == true
        }
        val household = near.filter { homeId >= 0 && residents[it.resident]?.home == homeId }
        val neighbours = (near - household.toSet()).sortedBy { it.distanceToSqr(p) }.take(NEIGHBOURS)
        val scene = Welcome(p)
        for ((k, h) in household.withIndex()) scene.add(level, h, Activity.EMBRACE, k, household.size, 1.2, if (k == 0) "shelter_allclear_1" else null)
        for ((k, n) in neighbours.withIndex()) scene.add(level, n, if (k % 2 == 0) Activity.CHEER else Activity.CLAP, k, neighbours.size, 3.0, null)
        if (scene.crew.isNotEmpty()) scenes += scene
    }

    /** [p], a captive, died (`Captives.onDeath`): no longer taken (the death itself is the resident's, held captive). */
    fun died(level: ServerLevel, p: Townsperson) {
        val (key, t) = takenFrom(level, p) ?: return
        val data = TroubleData.get(level)
        data.peek(key)?.taken?.removeIf { it.id == t.id }
        data.setDirty()
    }

    /**
     * The close of [rec]'s day: each of its folk a raid took whom no band holds any more (the camp broken up, the captive gone) is put to
     * rest as taken and never found.
     */
    fun daily(level: ServerLevel, rec: VillageRec, day: Long) {
        val kept = TroubleData.peek(level)?.peek(rec.key) ?: return
        if (kept.taken.isEmpty()) return
        val camps = OutlawCamps.get(level)
        val residents = Residents.get(level)
        val gone = kept.taken.filter { t -> camps.bands.none { b -> b.captives.any { it.resident == t.id } } }
        for (t in gone) {
            kept.taken.remove(t)
            val r = residents[t.id] ?: continue
            if (r.alive || r.died >= 0) continue
            Residents.pass(level, r, null, Deaths.LOST)
            Villages.note(level, rec.key, "lost", r.name)
            Trouble.log(level, rec, "${r.name}, taken on day ${t.day}, never found")
        }
        if (gone.isNotEmpty()) TroubleData.get(level).setDirty()
    }

    /** Who a raid holds of the village [key], for `village watch`. */
    fun describe(level: ServerLevel, key: Long): List<String> {
        val out = ArrayList<String>()
        forays[key]?.let { out += "an outlaws' raid under way" + (it.outcome?.let { o -> ", ${o.name.lowercase()}" } ?: "") }
        omens[key]?.let { out += "a Bad Omen raid under way, ${it.armed.size} raiders armed against the folk" }
        TroubleData.peek(level)?.peek(key)?.taken?.forEach { out += "${it.name} held by band #${it.band} since day ${it.day}" }
        return out
    }

    fun forget() {
        forays.clear()
        omens.clear()
        for (s in scenes) s.end()
        scenes.clear()
    }

    private const val NEIGHBOURS = 5

    /** Whether [p] can be taken off their day for a moment of the welcome. */
    private fun free(p: Townsperson): Boolean {
        val d = p.director
        return d == null || d is Lamplighter || d is Greeter || d is BuilderCrew.Builder
    }

    /** The homecoming at the board: those who come to [returned], each with their beat. */
    private class Welcome(val returned: Townsperson) {
        val crew = ArrayList<Beat>()
        private var age = 0

        fun add(level: ServerLevel, p: Townsperson, act: Activity, k: Int, n: Int, r: Double, line: String?) {
            when (val d = p.director) {
                is Greeter -> d.release()
                is BuilderCrew.Builder -> d.handBack()
                else -> {}
            }
            if (p.isSleeping) p.stopSleeping()
            p.standUp()
            p.routine.interrupt()
            p.navigation.stop()
            val centre = returned.position()
            val a = Math.atan2(p.z - centre.z, p.x - centre.x) + (k - (n - 1) / 2.0) * 0.5
            val spot = BlockPos.containing(centre.x + Math.cos(a) * r, centre.y + 0.5, centre.z + Math.sin(a) * r)
            val b = Beat(this, p, GroundNear.stand(level, spot) ?: returned.blockPosition(), act, line)
            crew += b
            p.director = b
        }

        /** Each tick; true once everyone is done (or after half a minute). */
        fun tick(level: ServerLevel): Boolean {
            age++
            crew.removeIf { it.done }
            if (crew.isEmpty()) return true
            if (age > 600) {
                end()
                return true
            }
            return false
        }

        fun end() {
            for (b in crew.toList()) b.finish()
            crew.clear()
        }
    }

    /** One of the welcome: off to [spot] at a run, a turn to the returned, the beat ([act]), a word, and back to the day. */
    private class Beat(val scene: Welcome, val p: Townsperson, val spot: BlockPos, val act: Activity, val line: String?) : Director {
        var done = false
        private val walker = Walker(p)
        private var started = false
        private var at = -1
        private var lastTick = -1
        private var from = 0

        override fun tick(p: Townsperson) {
            if (done) return
            if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0) return finish()
            lastTick = p.tickCount
            if (!started) {
                started = true
                from = p.tickCount
                walker.go(spot, Jobs.RUN, near = 0.8)
            }
            if (at < 0) {
                if (walker.tick() == Walker.State.WALKING && p.tickCount - from < 300) return
                at = p.tickCount
                p.face(p.yawTo(scene.returned.position()))
                p.act(act)
                line?.let { Trouble.say(p, it) }
                return
            }
            p.face(p.yawTo(scene.returned.position()))
            if (p.actDone || p.tickCount - at > 80) finish()
        }

        fun finish() {
            if (done) return
            done = true
            if (p.director === this) p.director = null
            p.act(Activity.NONE)
            p.navigation.stop()
            p.routine.interrupt()
        }
    }
}
