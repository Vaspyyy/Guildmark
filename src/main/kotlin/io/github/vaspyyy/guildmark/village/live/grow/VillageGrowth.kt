package io.github.vaspyyy.guildmark.village.live.grow

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkLooks
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Town
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillagePerf
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Dead
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Home
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Person
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Picture
import io.github.vaspyyy.guildmark.village.live.talk.VillageCry
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import io.github.vaspyyy.guildmark.economy.fortune.Times as FortuneTimes

/**
 * A village's growth (living-world/villages/design.md 5.5 and 5.6, part V13): births, coming of age, newcomers off the road and
 * households leaving, by its record, once a day at its close; and the world made to match, out of sight or played out where a
 * player can see it.
 *
 * **The close.** Each village of the registry whose folk are taken in ([VillageAdopt.adopting]) and that is not lost closes its day
 * with its market at two in the morning ([Villages.onDay]); one with no market open (Village Markets off, or none yet) is closed by
 * the clock here an hour later ([fallback]). The rules are [GrowthRules]'s, on a picture of the record: its homes and beds from the
 * census, its people from [Residents], its vanilla villagers as last counted while it was loaded ([count]), fed by its market's
 * hungry days (with no market, no day is known to have been hungry), hard by its fortunes, and its last lost siege from its board.
 * What comes of it: a child born ([Births]), children grown up ([Births.grow]), a household set coming at the next dusk or leaving
 * the next morning ([Newcomers]).
 *
 * **The bodies.** A resident made by the record (a toddler three days after birth, a grown-up, a household come with nobody to see)
 * has their body made just inside their front door, with their home's land loaded, nobody within [Furnish.UNSEEN] blocks of the
 * house and nobody in sight of the spot ([bodies]); never one for a village whose folk are given back, nor while one of its villagers
 * is being swapped ([VillageAdopt.swapping]). An old body that is nobody's now (a child grown up while away, a household gone) is
 * dropped as it loads ([joined]), and one about in the world goes as soon as nobody can see it ([vanish]).
 *
 * **The switches.** With villageGrowth off ([enabled]) nobody is born, grows up, comes or goes: the dead stay dead and the village
 * shrinks only by what happens to it. The bodies of people already on the record are still made, and old bodies still dropped,
 * so nothing is left half done. A capital's people are never touched: only the residents of a village's record are.
 */
object VillageGrowth {
    /** Server ticks between looks at what the world owes the records, between the clock's own closes, and between counts of the villagers. */
    private const val STEP = 40L
    private const val FALLBACK = 200L
    private const val COUNT = 600L

    /** The most bodies made at home in one village in a look. */
    private const val BODIES = 2

    /** How far from a player a village's villagers are counted; days an old body is looked out for. */
    private const val COUNT_NEAR = 160
    private const val STALE_DAYS = 120L

    /** Meals gone wanting in a day past which it was a hungry one (as `market` counts them). */
    private const val HUNGRY = 0.5

    // ---- The switches ----

    /** Whether villages grow: Living Villages and villageGrowth on. */
    fun enabled(): Boolean = Villages.enabled() && try {
        GuildmarkConfig.VILLAGE_GROWTH.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** Whether [rec] grows now: the switch on, its folk taken in (not released), and the village not lost. */
    fun grows(rec: VillageRec): Boolean = enabled() && VillageAdopt.adopting(rec) && rec.state != Live.LOST

    // ---- Setup ----

    /** Once, at setup (from `Villages.register`). */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { VillagePerf.timed(VillagePerf.Piece.GROWTH) { tick(it.server) } }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, EntityJoinLevelEvent::class.java) { joined(it) }
        Villages.onDay { level, rec, day -> close(level, rec, day - 1, day) }
        Villages.listen(object : Villages.Listener {
            override fun dropped(level: ServerLevel, rec: VillageRec) {
                val data = GrowthData.get(level)
                if (data.villages.remove(rec.key) != null) data.changed()
            }
        })
        Comings.listen(object : Comings.Listener {
            override fun back(level: ServerLevel, days: Long) = takeBack(level, days)
        })
        VillageInfoCommand.section { level, rec -> info(level, rec) }
        VillageGrowCommand.init()
    }

    private fun forget() {
        GrowScenes.forget()
        vanishing.clear()
        stale = null
    }

    // ---- The close ----

    /**
     * Closes [today] (a board day; [market] the market day beginning at this close) for [rec]: the rules run once on its record and
     * what comes of it is done. [force] closes it again (a command). Null if the village does not grow, or the day was closed already.
     */
    fun close(level: ServerLevel, rec: VillageRec, today: Long, market: Long, force: Boolean = false): GrowthRules.Out? {
        if (!grows(rec)) return null
        val data = GrowthData.get(level)
        val g = data.of(rec.key)
        if (!force && today <= g.state.closed) return null
        val out = try {
            GrowthRules.close(g.state, picture(level, rec, g, today), times(level, rec, g, market), rec.key, today)
        } finally {
            data.changed()
        }
        if (out.birth >= 0) Births.born(level, rec, g, out.birth, today)
        for (id in out.ofAge) if (id !in g.aging) g.aging += id
        out.coming?.let { Newcomers.coming(level, rec, g, it, today) }
        out.leaving?.let { Newcomers.leaving(level, rec, g, it, today) }
        data.changed()
        return out
    }

    /** The board day whose two o'clock in the night has last gone by, at the clock's [time]. */
    fun closing(time: Long): Long = Math.floorDiv(time + 4000L, 24000L) - 1L

    /**
     * The clock's own close, for a village no market closes: none open (Village Markets off, or not open yet), or a market so far
     * behind that its close has not come for more than a day. An hour after two, so a market that is there closes first.
     */
    private fun fallback(level: ServerLevel) {
        if (!enabled()) return
        val time = level.overworldClockTime
        val today = closing(time)
        if (time - (today * 24000L + 20000L) < 1000L) return
        val data = GrowthData.get(level)
        for (rec in Villages.all(level)) {
            if (!grows(rec)) continue
            val closed = data.find(rec.key)?.state?.closed ?: -1L
            if (closed >= today) continue
            if (VillageMarkets.find(level, rec.key) != null && closed >= today - 1) continue
            close(level, rec, today, today + 1)
        }
    }

    /** [rec]'s record as the rules see it on [today]. */
    fun picture(level: ServerLevel, rec: VillageRec, g: Grow, today: Long): Picture {
        val homes = rec.homes.filter { !it.gone && it.beds.isNotEmpty() }.map { Home(it.id, it.beds.size) }
        val people = Residents.get(level).byCentre(BlockPos.of(rec.key))
        val living = people.filter { it.alive }.map { Person(it.id, it.role.adult, it.home, it.since, it.role == TownRole.GUARD || Tales.holds(it.id)) }
        val dead = people.filter { !it.alive && it.died >= 0 && today - it.died in 0..GrowthRules.DEAD_DAYS.toLong() }.map { Dead(it.id, it.died, it.home) }
        // Only those who left by the growth's own doing (a captive is away too, but not to come home on their own)
        val away = people.filter { !it.alive && it.died < 0 && it.left >= 0 && it.id in g.gone }.groupBy { it.home to it.left }
            .map { (k, m) -> GrowthRules.Away(m.map { it.id }, m.count { it.role.adult }, k.first, k.second) }
        return Picture(homes, living, dead, away, g.vanilla)
    }

    /** How things stand for [rec] at the close beginning [market] (a market day). */
    fun times(level: ServerLevel, rec: VillageRec, g: Grow, market: Long): GrowthRules.Times {
        val hard = Fortunes.times(level, rec.key) == FortuneTimes.HARD
        return GrowthRules.Times(fed(level, rec), hard, Comings.hardRun(level, rec.key, market), siegeLost(level, rec, g), VillageAdopt.maxFolk()) { home ->
            playerIn(level, rec, home)
        }
    }

    /**
     * No hungry day in the last [GrowthRules.FED_DAYS] of [rec]'s market (one where more than [HUNGRY] meals went wanting, as
     * `market` counts a hungry day); with no market open, none is known to have been.
     */
    fun fed(level: ServerLevel, rec: VillageRec): Boolean {
        val m = VillageMarkets.rec(level, rec.key)?.entry?.market ?: return true
        return m.hungry.takeLast(GrowthRules.FED_DAYS).none { it > HUNGRY }
    }

    /** The day [rec] last lost a siege, as its board has it (kept for when the board is not loaded), or -1. */
    fun siegeLost(level: ServerLevel, rec: VillageRec, g: Grow): Long {
        val board = VillageCry.board(level, rec)?.lastSiegeLost?.takeIf { it >= 0L }
        if (board != null && board > g.siege) g.siege = board
        return g.siege
    }

    /** Whether a player stands in [rec]'s home [id] now. */
    private fun playerIn(level: ServerLevel, rec: VillageRec, id: Int): Boolean {
        val box = Villages.places(level, rec.key)?.lodgings?.get(id)?.box ?: return false
        return level.players().any { p ->
            p.x >= box.minX() - 1 && p.x < box.maxX() + 2 && p.z >= box.minZ() - 1 && p.z < box.maxZ() + 2 && p.y >= box.minY() - 1 && p.y < box.maxY() + 2
        }
    }

    // ---- Who they are ----

    /** The job blocks of each trade a village's newcomer or grown child may take up, in the order a tie goes (as village/live/days/VillageLife has them). */
    private val TRADES: List<Pair<TownRole, List<M>>> = listOf(
        TownRole.FARMER to listOf(M.COMPOSTER),
        TownRole.SMITH to listOf(M.ANVIL, M.GRINDSTONE, M.SMITHING_TABLE, M.BLAST_FURNACE),
        TownRole.BAKER to listOf(M.SMOKER, M.FURNACE),
        TownRole.PRIEST to listOf(M.LECTERN, M.BREWING_STAND),
        TownRole.FISHER to listOf(M.BARREL),
    )

    /**
     * The trade [rec] lacks most (5.6): the one with the most of its job blocks standing with nobody of it to work them, counting
     * [also] (newcomers being made) as of the village already; a fisher only where a barrel has water near. CITIZEN if none.
     */
    fun lacking(level: ServerLevel, rec: VillageRec, places: VillagePlaces, also: List<TownRole> = listOf()): TownRole {
        val roles = Residents.get(level).byCentre(BlockPos.of(rec.key)).filter { it.alive }.map { it.role } + also
        var best = TownRole.CITIZEN
        var most = 0
        for ((role, blocks) in TRADES) {
            val stations = places.jobs.filter { it.m in blocks }
            if (stations.isEmpty()) continue
            if (role == TownRole.FISHER && stations.none { water(level, it.pos) }) continue
            val gap = stations.size - roles.count { it == role }
            if (gap > most) {
                most = gap
                best = role
            }
        }
        return best
    }

    /** Whether there is water at the surface within 12 blocks of [pos] (in loaded land, every third block). */
    private fun water(level: ServerLevel, pos: BlockPos): Boolean {
        for (dx in -12..12 step 3) for (dz in -12..12 step 3) {
            val x = pos.x + dx
            val z = pos.z + dz
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1
            if (level.getFluidState(BlockPos(x, y, z)).`is`(FluidTags.WATER)) return true
        }
        return false
    }

    /**
     * A look of [role] for a grown-up who is a woman ([woman]) or not, fixed by [seed]: of the role's common looks, one of their
     * own voice (not an old one, for someone just grown or new in their prime), else any.
     */
    fun look(role: TownRole, woman: Boolean, seed: Long): Int {
        val looks = TownsfolkLooks.BY_ROLE[role.id].orEmpty().take(role.common)
        val want = if (woman) "woman" else "man"
        val fits = looks.indices.filter { looks[it].timbre == want }.ifEmpty { looks.indices.filter { looks[it].timbre != "old" } }.ifEmpty { looks.indices.toList() }
        if (fits.isEmpty()) return Math.floorMod(seed ushr 3, maxOf(1, role.common).toLong()).toInt()
        return fits[Math.floorMod(seed ushr 5, fits.size.toLong()).toInt()]
    }

    /** The way of naming of [rec]'s land: its biome's (read from the world's generator where the land is not loaded, which loads nothing). */
    fun style(level: ServerLevel, rec: VillageRec): CityStyle = CityStyle.forBiome(level.getBiome(BlockPos.of(rec.key)))

    /** Whether [bed] may be [rec]'s new person's: free in the world's reckoning (and held for the townsfolk now), or not loaded (held when their body is made). */
    fun holdable(level: ServerLevel, bed: BlockPos): Boolean = !Places.loaded(level, bed, 16) || Furnish.hold(level, bed)

    // ---- The bodies ----

    /** [r]'s body, made now at [at] facing [yaw]: their trade and look, their village and home, their id and name at once. Null if none could be. */
    fun body(level: ServerLevel, rec: VillageRec, places: VillagePlaces, r: Resident, at: BlockPos, yaw: Float): Townsperson? {
        val p = Townsfolk.create(level, at, r.role, r.look, yaw, EntitySpawnReason.EVENT) ?: return null
        val bell = BlockPos.of(rec.key)
        p.home = places.lodgings[r.home]?.front?.inside ?: at
        p.town = Town(bell, rec.radius, places.market, null, true)
        p.resident = r.id
        p.setPersistenceRequired()
        Residents.named(p, r)
        if (!level.addFreshEntity(p) || p.isRemoved) return null
        r.uuid = p.uuid
        Residents.get(level).add(r)
        r.bed?.let { if (Places.loaded(level, it, 16)) Furnish.hold(level, it) }
        return p
    }

    /** Facing into [home] from its front door (the way one made just inside it faces). */
    fun into(home: CityPlaces.Lodging?): Float = home?.front?.inward?.toYRot() ?: 0f

    /**
     * The bodies [g] still owes [rec]'s residents, made at home out of sight ([BODIES] a look at most): someone with no body on
     * record, whose day to have one has come, whom no scene has charge of, with the land of their home loaded, nobody within
     * [Furnish.UNSEEN] blocks of it and nobody in sight of the spot just inside its door. A resident who has a body on record by now
     * (taken up by another part), or who is dead or away, is owed none.
     */
    private fun bodies(level: ServerLevel, rec: VillageRec, places: VillagePlaces, g: Grow) {
        if (g.make.isEmpty() || !VillageAdopt.adopting(rec) || VillageAdopt.swapping(rec.key) > 0) return
        val today = VillageBoards.day(level)
        val residents = Residents.get(level)
        var made = 0
        for (m in g.make.toList()) {
            if (made >= BODIES) return
            val r = residents[m.id]
            if (r == null || !r.alive || r.capital.asLong() != rec.key || r.uuid != null) {
                g.make.remove(m)
                GrowthData.get(level).changed()
                continue
            }
            if (m.from > today || GrowScenes.holds(r.id) || Tales.holds(r.id)) continue
            val home = places.lodgings[r.home]
            val spot = if (home != null) {
                if (!Furnish.loaded(level, home) || watched(level, home)) continue
                Comings.inside(level, home) ?: continue
            } else {
                ground(level, places.market) ?: continue
            }
            if (!Comings.unseen(level, spot)) continue
            if (body(level, rec, places, r, spot, into(home)) == null) continue
            g.make.remove(m)
            GrowthData.get(level).changed()
            made++
        }
    }

    /** The ground to stand on at [pos]'s column, its land loaded; null if there is none to stand on. */
    fun ground(level: ServerLevel, pos: BlockPos): BlockPos? {
        if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) return null
        val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos)
        for (dy in intArrayOf(0, 1, -1, 2)) if (Places.standable(level, top.above(dy))) return top.above(dy)
        return null
    }

    /** Whether a player is within [Furnish.UNSEEN] blocks of [home]'s box. */
    fun watched(level: ServerLevel, home: CityPlaces.Lodging): Boolean {
        val b = home.box
        return level.players().any { p ->
            val dx = maxOf(b.minX() - p.x, 0.0, p.x - b.maxX() - 1)
            val dy = maxOf(b.minY() - p.y, 0.0, p.y - b.maxY() - 1)
            val dz = maxOf(b.minZ() - p.z, 0.0, p.z - b.maxZ() - 1)
            dx * dx + dy * dy + dz * dz < Furnish.UNSEEN * Furnish.UNSEEN
        }
    }

    // ---- Old bodies ----

    /** The old bodies looked out for as they load, by their uuid: every village's, gathered the first time it is asked for. */
    private var stale: HashSet<UUID>? = null

    /** Bodies about in the world that are nobody's now, to go as soon as nobody can see them (by uuid, with the resident they were). */
    private val vanishing = HashMap<UUID, Int>()

    private fun staleSet(level: ServerLevel): HashSet<UUID> =
        stale ?: HashSet<UUID>().also { set -> GrowthData.get(level).villages.values.forEach { g -> g.stale.forEach { set += it.uuid } }; stale = set }

    /**
     * Resident [id]'s body [uuid] is nobody's from [day] on (they grew up, or left): looked out for as it loads, so no two bodies
     * ever stand for one person, and if it is about now, gone as soon as nobody can see it.
     */
    fun retire(level: ServerLevel, g: Grow, uuid: UUID, id: Int, day: Long) {
        if (g.stale.none { it.uuid == uuid }) g.stale += Stale(uuid, id, day)
        staleSet(level) += uuid
        if (level.getEntity(uuid) is Townsperson) vanishing[uuid] = id
        GrowthData.get(level).changed()
    }

    /** Bodies that are nobody's now, gone as soon as nobody is in sight of them; those that unloaded meanwhile are dropped as they load. */
    private fun vanish(level: ServerLevel) {
        if (vanishing.isEmpty()) return
        val it = vanishing.entries.iterator()
        while (it.hasNext()) {
            val (uuid, id) = it.next()
            val p = level.getEntity(uuid) as? Townsperson
            if (p == null || p.isRemoved) {
                it.remove()
                continue
            }
            if (p.resident != id || GrowScenes.has(p)) continue
            if (!Comings.unseen(level, p.blockPosition())) continue
            p.discard()
            it.remove()
        }
    }

    /** An old body loading with its chunk: if it is one that is nobody's now, it goes at once (before the residents' own look at it). */
    private fun joined(event: EntityJoinLevelEvent) {
        if (!event.loadedFromDisk()) return
        val p = event.entity as? Townsperson ?: return
        if (p.resident < 0) return
        val level = event.level as? ServerLevel ?: return
        val set = staleSet(level)
        if (p.uuid !in set) return
        event.isCanceled = true
        set.remove(p.uuid)
        val data = GrowthData.get(level)
        for (g in data.villages.values) if (g.stale.removeIf { it.uuid == p.uuid }) data.changed()
    }

    // ---- Each tick ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val now = level.gameTime
        try {
            // Switched off (or Living Villages off): a household walking in or out, a birth or a word on one grown up, end now
            if (GrowScenes.any() && !enabled()) GrowScenes.endAll()
            GrowScenes.tick(level)
            if (now % 20L == 7L) vanish(level)
            if (now % STEP == 13L) step(level)
            if (now % FALLBACK == 29L) fallback(level)
            if (now % COUNT == 41L) count(level)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("A village's growth failed this tick", e)
        }
    }

    /** What the records are owed by the world, village by village: its scenes begun or played by the record, and its bodies made. */
    private fun step(level: ServerLevel) {
        val data = GrowthData.get(level)
        if (data.villages.isEmpty()) return
        val today = VillageBoards.day(level)
        for (g in data.villages.values.toList()) {
            val rec = Villages.byKey(level, g.key)
            if (rec == null || rec.state == Live.LOST) continue
            if (g.stale.removeIf { today - it.day > STALE_DAYS }) {
                stale = null
                data.changed()
            }
            val places = Villages.places(level, g.key)
            try {
                Newcomers.step(level, rec, places, g)
                if (places == null) continue
                Births.step(level, rec, places, g)
                bodies(level, rec, places, g)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The growth of the village at {} failed this look", BlockPos.of(g.key).toShortString(), e)
            }
        }
    }

    /** The vanilla villagers of each village near a player, counted while all its land is loaded, and its board's last lost siege read. */
    private fun count(level: ServerLevel) {
        if (!enabled()) return
        val players = level.players()
        if (players.isEmpty()) return
        val data = GrowthData.get(level)
        val seen = HashSet<Long>()
        for (player in players) {
            for (rec in Villages.near(level, player.blockPosition(), COUNT_NEAR)) {
                if (!seen.add(rec.key) || !grows(rec) || !loaded(level, rec)) continue
                val g = data.of(rec.key)
                val n = VillageAdopt.villagersOf(level, rec).size
                if (n != g.vanilla) {
                    g.vanilla = n
                    data.changed()
                }
                siegeLost(level, rec, g)
            }
        }
    }

    /** Whether all of [rec]'s box is loaded (its corners and its bell). */
    private fun loaded(level: ServerLevel, rec: VillageRec): Boolean {
        val bell = BlockPos.of(rec.key)
        val r = rec.radius
        for ((dx, dz) in listOf(0 to 0, -r to -r, -r to r, r to -r, r to r)) {
            if (!level.hasChunk((bell.x + dx) shr 4, (bell.z + dz) shr 4)) return false
        }
        return true
    }

    /** The clock set back [days] days: every day the growth keeps goes back with it. */
    private fun takeBack(level: ServerLevel, days: Long) {
        val data = GrowthData.get(level)
        if (data.villages.isEmpty()) return
        for (g in data.villages.values) g.back(days)
        data.changed()
    }

    // ---- What is said of it ----

    /** For `village info`: the people against the beds and the cap, and what is on its way. */
    private fun info(level: ServerLevel, rec: VillageRec): List<Component> {
        val g = GrowthData.get(level).find(rec.key)
        val s = summary(level, rec, g)
        val out = ArrayList<Component>()
        out += Component.literal("Growth: ").withStyle(ChatFormatting.YELLOW).append(Component.literal(s).withStyle(ChatFormatting.WHITE))
        val why = g?.state?.why.orEmpty()
        if (why.isNotEmpty()) out += Component.literal("  at the last close: $why").withStyle(ChatFormatting.GRAY)
        return out
    }

    /** One line: whether it grows, its people against its beds and cap, and who is coming or going. */
    fun summary(level: ServerLevel, rec: VillageRec, g: Grow?): String {
        val people = Residents.get(level).byCentre(BlockPos.of(rec.key)).count { it.alive }
        val beds = rec.homes.filter { !it.gone }.sumOf { it.beds.size }
        val cap = minOf(beds, VillageAdopt.maxFolk())
        val vanilla = g?.vanilla ?: -1
        val head = when {
            !enabled() -> "off (villageGrowth); "
            !VillageAdopt.adopting(rec) -> "none while its folk are not taken in; "
            rec.state == Live.LOST -> "none while the village is lost; "
            else -> ""
        }
        val v = when {
            vanilla < 0 -> ", its villagers not counted yet"
            vanilla > 0 -> " and $vanilla vanilla villager${if (vanilla == 1) "" else "s"}"
            else -> ""
        }
        val coming = g?.state?.coming?.let { ", ${it.size} coming on day ${it.day} to home ${it.home}" }.orEmpty()
        val leaving = g?.state?.leaving?.let { ", a household (home ${it.home}) to leave on day ${it.day}" }.orEmpty()
        return "$head$people people$v; $beds beds, at most $cap$coming$leaving"
    }
}
