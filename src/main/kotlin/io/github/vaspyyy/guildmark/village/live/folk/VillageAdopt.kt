package io.github.vaspyyy.guildmark.village.live.folk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.homestead.Homesteads
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.HeldBeds
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Town
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageBudget
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillagePerf
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiRecord
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.AABB
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

/**
 * The village folk (living-world/villages/design.md sections 6.2 to 6.7): a village's vanilla villagers become its townsfolk at night,
 * out of sight, each kept as a keepsake ([VillageKeepsakes]) that "Their old wares" ([OldWares]) opens as the vanilla trade screen, and
 * are given back as villagers when `village release` says so or `villageFolk` (or the master switch) is off.
 *
 * **When.** One village is looked at every half second, in turn (the LIVE ones when folk are taken in; every one that has folk when
 * they are given back). A villager is taken in only if all of these hold: it is night (13000 to 23000 into the day) or it is asleep;
 * nobody is within 48 blocks of it and nobody who can see it is within 128; the budget has a ticket (two a second for a village, eight
 * in all, [VillageBudget.adoptOk]); the village has beds free for them and room for more folk; and none of the exemptions of 6.2 hold
 * ([Why]). `village adopt force` ignores the hour and the eyes.
 *
 * **How, in two steps, so that a crash loses nothing.** The first writes the keepsake (and, for someone new, the person they will be, in
 * the keepsake's `gm_res`) and asks for the world's saved data to be written. The second, once that is on disk, makes the body where the
 * villager stands and discards the villager in the same tick, so there is never a moment with both or with neither. A stop or crash
 * between them leaves the villager standing and the keepsake written, which the next pass takes up again ([resume]); one after the body is
 * made leaves a body whose record is [joined] from the keepsake.
 *
 * **Beds and job blocks.** The villager's bed stays taken in the world's reckoning of homes ([Furnish.hold], [HeldBeds]) so no vanilla
 * villager moves in, and so does its job block; both are handed back to the villager when it is given back, or let go when the resident
 * dies ([reap]). The villager's meeting point and the job block it only thought of are let go at once.
 */
object VillageAdopt {
    /** Blocks within which nobody may be for a villager to be taken in; and how far a player who can see it is looked for. */
    const val NEAR = 48.0
    const val SIGHT = 128.0

    /** Ticks between looks at a village, and the tick of that period they are on (off the multiples of 10 that the registry uses). */
    private const val EVERY = 10L
    private const val AT = 7L

    /** Ticks between writes of the world's saved data while someone waits on one. */
    private const val FLUSH = 40L

    /** How long a swap waits for its write and for nobody to be looking, in ticks, before it is given up. */
    private const val WAIT = 2400L

    /** The most bodies made in one tick. */
    private const val SWAPS = 4

    /** The tag (an entity's `/tag`) that keeps a villager vanilla; the entity type tag of the same name does it for whole kinds of villager. */
    const val KEEP_VANILLA = "guildmark_keep_vanilla"
    val KEEP_TAG: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "keep_vanilla"))

    /** Why a villager is left as it is: the exemptions of 6.2, and the other reasons it is not taken in now. */
    enum class Why(val words: String) {
        NAMED("has a nametag"),
        LEASHED("is leashed, riding or ridden"),
        GUILD("belongs to the guild"),
        MOD("is one of the mod's own"),
        TAGGED("is tagged keep vanilla"),
        LEVEL("is trade level 3 or more"),
        TRADED("has been traded with"),
        HOMESTEAD("is on a homestead"),
        BUSY("is hurt or trading"),
        CONFINED("is confined"),
    }

    /** What one look at a village found, for the commands to say. */
    class Tally {
        var seen = 0
        var queued = 0
        var resumed = 0
        var stale = 0
        var day = 0
        var watched = 0
        var noBed = 0
        var capped = 0
        var rate = 0
        var pending = 0
        var uncounted = false
        var raid = false
        val why = EnumMap<Why, Int>(Why::class.java)

        fun exempt(): Int = why.values.sum()
    }

    /** Someone who asked for it (a command): told what came of it when the last swap of theirs is over. */
    class Job(val who: UUID?, val village: Long, val force: Boolean) {
        var queued = 0
        var swapped = 0
        val failed = ArrayList<String>()
        var told = false
    }

    /** What the pass may do: ignore the hour and the eyes ([force]), obey the budget ([rate]), and who asked ([job]). */
    class Opts(val force: Boolean, val rate: Boolean, val job: Job?)

    /** A villager on its way to being a body: the village, the resident, the villager (and where it stood), when it began and who asked. */
    private class Swap(
        val key: Long, val resident: Int, val villager: UUID, val at: Long, val where: BlockPos, val job: Job?, val fresh: Boolean, val bed: Long?,
    )

    private class Shelved(val until: Long, val why: Why)

    private enum class Out { DONE, WAIT, DROP }

    /** In memory: the swaps whose keepsake is not yet written to disk, those being written, and those written and waiting for their turn. */
    private val queued = ArrayList<Swap>()
    private var flying: List<Swap> = emptyList()
    private var flush: CompletableFuture<*>? = null
    private val waiting = ArrayList<Swap>()
    private val pending = HashMap<UUID, Swap>()

    /** The asks (commands) whose swaps are not all over. */
    private val jobs = ArrayList<Job>()

    private var cursor = Long.MIN_VALUE
    private val shelf = HashMap<UUID, Shelved>()
    private val families = HashMap<Pair<Long, Int>, String>()
    private var from: HashMap<UUID, Int>? = null
    private val water = HashMap<Long, Pair<Long, Boolean>>()

    /** Called once at setup (from `Villages.register`). */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { VillagePerf.timed(VillagePerf.Piece.ADOPT) { tick(it.server) } }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, EntityJoinLevelEvent::class.java) { joined(it) }
        Villages.listen(object : Villages.Listener {
            override fun counted(level: ServerLevel, rec: VillageRec, places: VillagePlaces) = migrate(level, rec)
        })
        VillageInfoCommand.section { level, rec -> infoLines(level, rec) }
        OldWares.register()
        VillageFolkCommand.init()
    }

    private fun forget() {
        queued.clear()
        flying = emptyList()
        flush = null
        waiting.clear()
        pending.clear()
        jobs.clear()
        cursor = Long.MIN_VALUE
        shelf.clear()
        families.clear()
        from = null
        water.clear()
        Confined.forget()
        OldWares.forget()
    }

    // ---- The switches ----

    fun folkOn(): Boolean = try {
        GuildmarkConfig.VILLAGE_FOLK.get()
    } catch (e: IllegalStateException) {
        true
    }

    fun maxFolk(): Int = try {
        GuildmarkConfig.MAX_VILLAGE_FOLK.get()
    } catch (e: IllegalStateException) {
        60
    }

    /** Whether [rec] takes folk in now: Living Villages and the folk switch on, and the village not released. */
    fun adopting(rec: VillageRec): Boolean = Villages.enabled() && folkOn() && rec.folk

    // ---- Each tick ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val now = level.gameTime
        try {
            if (queued.isNotEmpty() || flying.isNotEmpty() || waiting.isNotEmpty()) settle(level, now)
            if (jobs.isNotEmpty()) jobs.removeAll { finished(level, it) }
            if (now % EVERY == AT) look(level)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("The village folk's tick failed", e)
        }
    }

    /** Looks at the next village in turn (a lost one too: its folk are given back, design 14.5, as a released village's are). */
    private fun look(level: ServerLevel) {
        val all = Villages.all(level)
        if (all.isEmpty()) return
        val rec = all.firstOrNull { it.key > cursor } ?: all.first()
        cursor = rec.key
        val adopt = adopting(rec) && rec.state != Live.LOST
        if (adopt && rec.state != Live.LIVE) return
        reap(level, rec)
        if (!scanning(level)) return
        val started = System.nanoTime()
        try {
            if (adopt) adoptRound(level, rec, Opts(false, true, null), Tally()) else releaseRound(level, rec, Opts(false, true, null))
        } finally {
            VillageBudget.spent(System.nanoTime() - started)
        }
    }

    /** Whether it is the hours when folk are looked for: from dusk (villagers go to bed at 12000) to just after dawn. */
    private fun scanning(level: ServerLevel): Boolean {
        val t = Math.floorMod(level.overworldClockTime, 24000L)
        return t >= 12000L || t < 500L
    }

    private fun night(level: ServerLevel): Boolean = Math.floorMod(level.overworldClockTime, 24000L) in 13000L until 23000L

    // ---- Who is left alone ----

    /** The cheap exemptions of 6.2, in the order the design checks them; null if none holds. */
    fun exemptCheap(level: ServerLevel, v: Villager): Why? {
        if (v.hasCustomName()) return Why.NAMED
        if (v.isLeashed || v.isPassenger || v.isVehicle) return Why.LEASHED
        if (v is GuildMember) return Why.GUILD
        if (v.hasData(ModAttachments.RECEPTIONIST) || v.hasData(ModAttachments.CHARACTER) || v.hasData(ModAttachments.TRAFFIC) ||
            v.hasData(ModAttachments.TRAVEL_PROGRESS) || v.hasData(ModAttachments.SIEGE) || v.hasData(ModAttachments.OUTLAW) ||
            v.hasData(ModAttachments.AMBUSH_AT) || v.hasData(ModAttachments.WAYLAYS) || v.type != EntityTypes.VILLAGER
        ) return Why.MOD
        if (v.typeHolder().`is`(KEEP_TAG) || v.entityTags().contains(KEEP_VANILLA)) return Why.TAGGED
        if (v.villagerData.level() >= 3) return Why.LEVEL
        if (Homesteads.at(level, v.blockPosition()) != null) return Why.HOMESTEAD
        if (v.isTrading || v.hurtTime > 0) return Why.BUSY
        return null
    }

    /** The costlier ones: a trade made from an offer (a villager that has been bought from), and being confined. [full] is the villager's whole saved tag. */
    private fun exemptSlow(level: ServerLevel, v: Villager, full: CompoundTag): Why? {
        if (traded(full)) return Why.TRADED
        if (Confined.confined(level, v.uuid, v.blockPosition())) return Why.CONFINED
        return null
    }

    /**
     * Whether a player has bought from this villager lately. Vanilla does not keep when a villager was last traded with, so this is what
     * it does keep: an offer with uses on it, which a villager at its job block clears itself when it restocks (twice a day), so it
     * reads as "since the last restock".
     */
    private fun traded(full: CompoundTag): Boolean {
        val recipes = full.getCompoundOrEmpty("Offers").getListOrEmpty("Recipes")
        for (i in 0 until recipes.size) if (recipes.getCompoundOrEmpty(i).getIntOr("uses", 0) > 0) return true
        return false
    }

    private fun saved(level: ServerLevel, v: Villager): CompoundTag {
        val out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess())
        v.saveWithoutId(out)
        return out.buildResult()
    }

    /** Whether a player is within [NEAR] blocks of [e], or can see it from within [SIGHT]. */
    fun watched(level: ServerLevel, e: Entity): Boolean {
        for (p in level.players()) {
            if (p.isRemoved) continue
            val d = p.distanceToSqr(e)
            if (d < NEAR * NEAR) return true
            if (d < SIGHT * SIGHT && p.hasLineOfSight(e)) return true
        }
        return false
    }

    // ---- Taking folk in ----

    /** The villagers of [rec] now in loaded chunks: those inside its box that no nearer village's box takes in. */
    fun villagersOf(level: ServerLevel, rec: VillageRec): List<Villager> {
        val bell = BlockPos.of(rec.key)
        val r = rec.radius.toDouble()
        val box = AABB(bell.x - r, bell.y - 32.0, bell.z - r, bell.x + r + 1.0, bell.y + 32.0, bell.z + r + 1.0)
        return level.getEntitiesOfClass(Villager::class.java, box) { it.isAlive && it !is KeptVillager && Villages.at(level, it.blockPosition())?.key == rec.key }
            .sortedBy { it.id }
    }

    private fun homeOf(level: ServerLevel, v: Villager): BlockPos? =
        v.brain.getMemory(MemoryModuleType.HOME).filter { it.dimension() == level.dimension() }.map { it.pos() }.orElse(null)

    private fun jobOf(level: ServerLevel, v: Villager): BlockPos? =
        v.brain.getMemory(MemoryModuleType.JOB_SITE).filter { it.dimension() == level.dimension() }.map { it.pos() }.orElse(null)

    /** One look at [rec] for folk to take in: queues the first step for each villager that may be. */
    fun adoptRound(level: ServerLevel, rec: VillageRec, opts: Opts, tally: Tally) {
        val bell = BlockPos.of(rec.key)
        if (Villages.places(level, rec.key) == null) {
            tally.uncounted = true
            return
        }
        if (level.getRaidAt(bell) != null) {
            tally.raid = true
            return
        }
        val villagers = villagersOf(level, rec)
        tally.seen = villagers.size
        if (villagers.isEmpty()) return
        val now = level.gameTime
        val residents = Residents.get(level)
        val people = residents.byCentre(bell)
        var alive = people.count { it.alive }
        val cap = maxFolk()
        // The beds of the village's homes (by their heads), and which home each is in
        val bedHome = HashMap<Long, Int>()
        for (home in rec.homes) if (!home.gone) for (b in home.beds) bedHome[b] = home.id
        val taken = HashSet<Long>()
        for (r in people) if (r.alive) r.bed?.let { taken.add(it.asLong()) }
        for (s in pending.values) if (s.key == rec.key) s.bed?.let { taken.add(it) }
        // A bed another villager (one left vanilla, or not yet looked at) has as its own is not free
        val theirs = HashMap<UUID, Long>()
        for (v in villagers) homeOf(level, v)?.let { theirs[v.uuid] = it.asLong() }
        val index = fromIndex(level)
        val night = night(level)
        for (v in villagers) {
            if (pending.containsKey(v.uuid)) {
                tally.pending++
                continue
            }
            val shelved = shelf[v.uuid]
            if (shelved != null && now < shelved.until) {
                count(tally, shelved.why)
                continue
            }
            val cheap = exemptCheap(level, v)
            if (cheap != null) {
                count(tally, cheap)
                dropPending(level, v)
                continue
            }
            // Taken up again after a stop: the keepsake is there already, and a body may be, too
            val rid = index[v.uuid]?.takeIf { VillageKeepsakes.has(level, it) }
            if (rid != null) {
                if (!opts.force && watched(level, v)) {
                    tally.watched++
                    continue
                }
                when (resume(level, rec, v, rid, opts)) {
                    Resumed.STALE -> tally.stale++
                    Resumed.QUEUED -> tally.resumed++
                    Resumed.NOT -> {}
                }
                continue
            }
            if (!opts.force && !night && !v.isSleeping) {
                tally.day++
                continue
            }
            if (!opts.force && watched(level, v)) {
                tally.watched++
                continue
            }
            val known = people.firstOrNull { it.uuid == v.uuid }
            if (known == null && alive >= cap) {
                tally.capped++
                continue
            }
            if (opts.rate && !VillageBudget.fits(SLOW_MICROS)) {
                tally.rate++
                continue
            }
            val full = saved(level, v)
            val slow = exemptSlow(level, v, full)
            if (slow != null) {
                count(tally, slow)
                shelf[v.uuid] = Shelved(now + SHELVE, slow)
                if (shelf.size > 2048) shelf.clear()
                continue
            }
            val bed = chooseBed(level, v, known, bedHome.keys, taken, theirs)
            if (bed == null) {
                tally.noBed++
                continue
            }
            if (opts.rate && !VillageBudget.adoptOk(rec.key)) {
                tally.rate++
                continue
            }
            begin(level, rec, v, full, bed, bedHome[bed] ?: -1, known, opts)
            taken.add(bed)
            if (known == null) alive++
            opts.job?.let { it.queued++ }
            tally.queued++
        }
    }

    private fun count(tally: Tally, why: Why) {
        tally.why[why] = (tally.why[why] ?: 0) + 1
    }

    /** How long a villager found exempt by the costlier tests is left alone before it is looked at again, in ticks. */
    private const val SHELVE = 1200L

    /** What a costlier look at a villager is reckoned to cost, in microseconds. */
    private const val SLOW_MICROS = 300

    /**
     * The bed [v] will have as a resident: its own (its home memory, if the village's census knows it and no one else has it), else the
     * nearest bed in the village's homes that nobody has. Null if there is none: the villager stays as it is (6.2: adoption stops
     * when the free beds run out).
     */
    private fun chooseBed(
        level: ServerLevel, v: Villager, known: Resident?, beds: Set<Long>, taken: Set<Long>, theirs: Map<UUID, Long>,
    ): Long? {
        fun standing(b: Long): Boolean {
            val pos = BlockPos.of(b)
            return level.isLoaded(pos) && level.getBlockState(pos).`is`(net.minecraft.tags.BlockTags.BEDS)
        }
        val own = homeOf(level, v)?.asLong()
        if (own != null && own in beds && own !in taken && standing(own)) return own
        known?.bed?.asLong()?.let { if (it in beds && it !in taken && standing(it)) return it }
        val others = theirs.filter { it.key != v.uuid }.values.toSet()
        return beds.asSequence().filter { it !in taken && it !in others && standing(it) && Places.loaded(level, BlockPos.of(it), 16) }
            .sortedBy { BlockPos.of(it).distSqr(v.blockPosition()) }
            .firstOrNull { free(level, BlockPos.of(it)) }
    }

    /** Whether the bed with its head at [bed] has a ticket no villager holds (or is not one the world reckons, and so is free to be had). */
    private fun free(level: ServerLevel, bed: BlockPos): Boolean = poi(level, bed)?.let { it.freeTickets > 0 } ?: true

    /** The first step for [v]: its keepsake is written (with the resident it will be, if they are new) and the write of saved data is asked for. */
    private fun begin(
        level: ServerLevel, rec: VillageRec, v: Villager, full: CompoundTag, bed: Long, homeId: Int, known: Resident?, opts: Opts,
    ): Swap? {
        val residents = Residents.get(level)
        val id = known?.id ?: residents.newId()
        val tag = VillageKeepsakes.cut(full)
        tag.putString(VillageKeepsakes.FROM, v.uuid.toString())
        tag.putLong(VillageKeepsakes.DAY, VillageBoards.day(level))
        tag.putBoolean(VillageKeepsakes.WARES, hasWares(v))
        jobOf(level, v)?.let { tag.putLong(VillageKeepsakes.JOB, it.asLong()) }
        tag.putLong(VillageKeepsakes.HOME, bed)
        tag.putLong(VillageKeepsakes.DECAY, level.gameTime)
        tag.putLong(VillageKeepsakes.VILLAGE, rec.key)
        tag.putBoolean(VillageKeepsakes.PENDING, true)
        if (known == null) tag.put(VillageKeepsakes.RES, person(level, rec, v, id, bed, homeId))
        VillageKeepsakes.put(level, id, tag)
        from?.put(v.uuid, id)
        val swap = Swap(rec.key, id, v.uuid, level.gameTime, v.blockPosition(), opts.job, known == null, bed)
        pending[v.uuid] = swap
        queued.add(swap)
        return swap
    }

    /** Whether the villager has anything to sell: a trade of its own, and not a child. */
    private fun hasWares(v: Villager): Boolean {
        if (v.isBaby) return false
        val key = v.villagerData.profession().unwrapKey().orElse(null) ?: return false
        return key != VillagerProfession.NONE && key != VillagerProfession.NITWIT
    }

    /** The person [v] becomes (design 5.3): their trade, and a name, a look, ways and a family name from the village's tongue. */
    private fun person(level: ServerLevel, rec: VillageRec, v: Villager, id: Int, bed: Long, homeId: Int): CompoundTag {
        val bell = BlockPos.of(rec.key)
        val role = roleOf(level, rec, v)
        val seed = rec.key * 31 + id
        val look = Math.floorMod(seed ushr 3, role.common.toLong()).toInt()
        val style = CityStyle.forBiome(level.getBiome(bell))
        val woman = Names.woman(role, look, seed)
        val people = Residents.get(level).byCentre(bell)
        val kin = if (homeId >= 0) people.firstOrNull { it.home == homeId && it.alive && it.role != TownRole.CHILD } else null
        val slot = rec.key to homeId
        val family = when {
            kin != null && (role == TownRole.CHILD || people.none { it.home == homeId && it.alive && it.role != TownRole.CHILD && it.id != kin.id }) -> kin.family
            homeId >= 0 && families.containsKey(slot) -> families.getValue(slot)
            else -> Names.family(style, seed).also { if (homeId >= 0) families[slot] = it }
        }
        if (families.size > 512) families.clear()
        val res = CompoundTag()
        res.putString("given", Names.given(style, woman, seed))
        res.putString("family", family)
        res.putString("role", role.id)
        res.putInt("look", look)
        res.putInt("home", homeId)
        res.putLong("bed", bed)
        res.putInt("traits", Residents.traits(seed))
        res.putString("craft", v.villagerData.profession().unwrapKey().orElse(null)?.identifier()?.toString() ?: "")
        res.putLong("since", VillageBoards.day(level))
        return res
    }

    /** Their trade as a townsperson's, by 5.3: a fisher only where there is water, a child if the villager is one. */
    private fun roleOf(level: ServerLevel, rec: VillageRec, v: Villager): TownRole {
        if (v.isBaby) return TownRole.CHILD
        val path = v.villagerData.profession().unwrapKey().orElse(null)?.identifier()?.path ?: return TownRole.CITIZEN
        return when (path) {
            "farmer" -> TownRole.FARMER
            "fisherman" -> if (hasWater(level, rec)) TownRole.FISHER else TownRole.CITIZEN
            "butcher" -> TownRole.BAKER
            "armorer", "weaponsmith", "toolsmith" -> TownRole.SMITH
            "cleric", "librarian" -> TownRole.PRIEST
            else -> TownRole.CITIZEN
        }
    }

    /** Whether there is water within the village's box (sampled every six blocks): once found it is believed, and a miss is looked at again in ten minutes. */
    private fun hasWater(level: ServerLevel, rec: VillageRec): Boolean {
        val now = level.gameTime
        val old = water[rec.key]
        if (old != null && (old.second || now - old.first < 12000L)) return old.second
        val bell = BlockPos.of(rec.key)
        var found = false
        var x = -rec.radius
        while (x <= rec.radius && !found) {
            var z = -rec.radius
            while (z <= rec.radius && !found) {
                val px = bell.x + x
                val pz = bell.z + z
                if (level.hasChunk(px shr 4, pz shr 4)) {
                    val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz) - 1
                    found = level.getFluidState(BlockPos(px, y, pz)).`is`(FluidTags.WATER)
                }
                z += 6
            }
            x += 6
        }
        water[rec.key] = now to found
        return found
    }

    /** The resident that [res] (a keepsake's `gm_res`) describes, with the id [id], for the village at [bell]. */
    private fun personFrom(res: CompoundTag, id: Int, bell: BlockPos): Resident = Resident(
        id, null, bell, -1, res.getStringOr("given", "Someone"), res.getStringOr("family", ""), TownRole.byName(res.getStringOr("role", "citizen")) ?: TownRole.CITIZEN,
        res.getIntOr("look", 0), res.getIntOr("home", -1), res.getLong("bed").map { BlockPos.of(it) }.orElse(null), true, res.getIntOr("traits", 0), true, -1L,
        res.getLongOr("since", 0L), -1L, res.getStringOr("craft", ""),
    )

    // ---- Taking up again, and the second step ----

    private enum class Resumed { QUEUED, STALE, NOT }

    /**
     * [v] has a keepsake that names it ([rid]): the first step was done before a stop or a crash. If the resident's body is about, [v]
     * is a stale copy and is discarded; if not, the second step is queued again, with no wait for a write (what was written is on disk).
     */
    private fun resume(level: ServerLevel, rec: VillageRec, v: Villager, rid: Int, opts: Opts): Resumed {
        val resident = Residents.get(level)[rid]
        val body = resident?.uuid?.takeIf { it != v.uuid }?.let { level.getEntity(it) as? Townsperson }
        if (body != null && body.isAlive && body.resident == rid) {
            Guildmark.LOGGER.info("A vanilla villager at {} is a stale copy of the resident {}, whose body stands: discarded", v.blockPosition().toShortString(), rid)
            v.discard()
            from?.remove(v.uuid)
            return Resumed.STALE
        }
        val tag = VillageKeepsakes.get(level, rid) ?: return Resumed.NOT
        val bed = tag.getLong(VillageKeepsakes.HOME).orElse(null)
        val swap = Swap(rec.key, rid, v.uuid, level.gameTime, v.blockPosition(), opts.job, resident == null, bed)
        pending[v.uuid] = swap
        waiting.add(swap)
        opts.job?.let { it.queued++ }
        return Resumed.QUEUED
    }

    /** Writes the saved data when a swap is waiting on it, and makes the bodies of those whose write is done. */
    private fun settle(level: ServerLevel, now: Long) {
        val f = flush
        if (f != null && f.isDone) {
            if (f.isCompletedExceptionally) {
                Guildmark.LOGGER.warn("The world's saved data could not be written: {} villagers are left as they are", flying.size)
                for (s in flying) abort(level, s, "the saved data could not be written")
            } else {
                waiting.addAll(flying)
            }
            flying = emptyList()
            flush = null
        }
        if (flush == null && queued.isNotEmpty() && (now % FLUSH == 0L || queued.size >= 4 || queued.any { it.job != null })) {
            flying = queued.toList()
            queued.clear()
            flush = level.server.overworld().dataStorage.scheduleSave()
        }
        if (waiting.isEmpty()) return
        var made = 0
        val it = waiting.iterator()
        while (it.hasNext() && made < SWAPS) {
            val s = it.next()
            when (swap(level, s, now)) {
                Out.DONE -> {
                    it.remove()
                    made++
                }
                Out.DROP -> it.remove()
                Out.WAIT -> {}
            }
        }
    }

    /** The second step: the body is made where the villager stands, and the villager is discarded, in this tick. */
    private fun swap(level: ServerLevel, s: Swap, now: Long): Out {
        val rec = Villages.byKey(level, s.key)
        val v = level.getEntity(s.villager) as? Villager
        if (rec == null || !adopting(rec) && s.job?.force != true) {
            abort(level, s, "the folk are not being taken in now")
            return Out.DROP
        }
        if (v == null || v.isRemoved) {
            // Gone from the world: dead (its chunk is loaded and it is not in it) or unloaded (it will be taken up again when it is)
            if (level.hasChunk(s.where.x shr 4, s.where.z shr 4)) {
                abort(level, s, "the villager is gone")
                if (!s.fresh) Residents.get(level)[s.resident]?.takeIf { it.alive }?.let { Residents.pass(level, it) }
            } else {
                pending.remove(s.villager)
            }
            return Out.DROP
        }
        if (now - s.at > WAIT) {
            abort(level, s, "it waited too long to be unseen")
            return Out.DROP
        }
        val why = exemptCheap(level, v)
        if (why != null) {
            abort(level, s, why.words)
            return Out.DROP
        }
        if (s.job?.force != true && watched(level, v)) return Out.WAIT
        val tag = VillageKeepsakes.get(level, s.resident)
        if (tag == null) {
            abort(level, s, "its keepsake is missing")
            return Out.DROP
        }
        val bell = BlockPos.of(rec.key)
        val residents = Residents.get(level)
        val r = residents[s.resident] ?: personFrom(tag.getCompoundOrEmpty(VillageKeepsakes.RES), s.resident, bell)
        val body = Townsfolk.create(level, v.blockPosition(), r.role, r.look, v.yRot) ?: run {
            abort(level, s, "no body could be made")
            return Out.DROP
        }
        body.snapTo(v.x, v.y, v.z, v.yRot, 0f)
        body.yHeadRot = v.yHeadRot
        body.yBodyRot = v.yBodyRot
        // Not in a wall: the body has the villager's own room, and if it has not, it waits (a villager abed in a corner clears when it wakes)
        if (!level.noCollision(body)) return Out.WAIT
        val own = homeOf(level, v)?.asLong()
        val bed = r.bed
        if (bed != null) {
            val held = Furnish.hold(level, bed)
            if (!held) {
                if (own == bed.asLong()) HeldBeds.get(level).add(bed) else r.bed = null
            }
        }
        body.home = r.bed ?: v.blockPosition()
        body.work = VillageKeepsakes.pos(tag, VillageKeepsakes.JOB)
        val places = Villages.places(level, rec.key)
        body.town = Town(bell, rec.radius, places?.market ?: bell, null, true)
        body.resident = r.id
        body.setPersistenceRequired()
        Residents.named(body, r)
        val asleep = if (v.isSleeping) v.sleepingPos.orElse(null) else null
        level.addFreshEntityWithPassengers(body)
        if (body.isRemoved) {
            // Something refused it: the villager is as it was
            abort(level, s, "the body was refused")
            return Out.DROP
        }
        // From here the villager is gone in this tick: what it held that nothing will use now is let go, and its bed is the body's
        v.releasePoi(MemoryModuleType.MEETING_POINT)
        v.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE)
        if (v.isSleeping) v.stopSleeping()
        r.uuid = body.uuid
        residents.add(r)
        if (asleep != null && level.getBlockState(asleep).`is`(net.minecraft.tags.BlockTags.BEDS)) body.startSleeping(asleep)
        v.discard()
        tag.remove(VillageKeepsakes.PENDING)
        tag.remove(VillageKeepsakes.RES)
        VillageKeepsakes.put(level, r.id, tag)
        pending.remove(s.villager)
        s.job?.let { it.swapped++ }
        return Out.DONE
    }

    /** The swap [s] does not go on: the villager stays as it is and the keepsake is dropped (a person who was never on record is not). */
    private fun abort(level: ServerLevel, s: Swap, why: String) {
        pending.remove(s.villager)
        VillageKeepsakes.get(level, s.resident)?.let {
            if (it.getBooleanOr(VillageKeepsakes.PENDING, false)) {
                VillageKeepsakes.remove(level, s.resident)
                from?.remove(s.villager)
            }
        }
        s.job?.failed?.add(why)
    }

    /** A villager that is exempt now but has a keepsake a half-done adoption left: the keepsake is dropped (the person it was to be was never on record). */
    private fun dropPending(level: ServerLevel, v: Villager) {
        if (pending.containsKey(v.uuid)) return
        val id = from?.get(v.uuid) ?: return
        val tag = VillageKeepsakes.get(level, id) ?: return
        if (!tag.getBooleanOr(VillageKeepsakes.PENDING, false)) return
        VillageKeepsakes.remove(level, id)
        from?.remove(v.uuid)
    }

    /** Whether [job] is over: none of its swaps is in the air. Its asker, if they are still about, is told what came of it. */
    private fun finished(level: ServerLevel, job: Job): Boolean {
        if (pending.values.any { it.job === job }) return false
        val who = job.who ?: return true
        val player = level.server.playerList.getPlayer(who) ?: return true
        val rest = job.failed.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
        val said = "Folk: ${job.swapped} of ${job.queued} villagers were made townsfolk" + if (job.failed.isEmpty()) "." else "; the rest stay as they are ($rest)."
        player.sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.YELLOW))
        return true
    }

    /** A body that came back from disk with its swap half known (the record was not saved, the game stopped): the record is made from the keepsake. */
    private fun joined(event: EntityJoinLevelEvent) {
        if (!event.loadedFromDisk()) return
        val p = event.entity as? Townsperson ?: return
        if (p.resident < 0) return
        val level = event.level as? ServerLevel ?: return
        val tag = VillageKeepsakes.get(level, p.resident) ?: return
        if (!tag.getBooleanOr(VillageKeepsakes.PENDING, false)) return
        val residents = Residents.get(level)
        val bell = tag.getLong(VillageKeepsakes.VILLAGE).map { BlockPos.of(it) }.orElse(null) ?: return
        val r = residents[p.resident] ?: personFrom(tag.getCompoundOrEmpty(VillageKeepsakes.RES), p.resident, bell)
        r.uuid = p.uuid
        residents.add(r)
        Residents.named(p, r)
        tag.remove(VillageKeepsakes.PENDING)
        tag.remove(VillageKeepsakes.RES)
        VillageKeepsakes.put(level, r.id, tag)
        runCatching { UUID.fromString(tag.getStringOr(VillageKeepsakes.FROM, "")) }.getOrNull()?.let { pending.remove(it) }
    }

    /** The index of the villagers that keepsakes were made from, built from all the keepsakes the first time it is asked for. */
    private fun fromIndex(level: ServerLevel): HashMap<UUID, Int> {
        from?.let { return it }
        val map = HashMap<UUID, Int>()
        for (id in VillageKeepsakes.ids(level)) {
            val text = VillageKeepsakes.get(level, id)?.getStringOr(VillageKeepsakes.FROM, "") ?: continue
            runCatching { UUID.fromString(text) }.getOrNull()?.let { map[it] = id }
        }
        from = map
        return map
    }

    // ---- Giving them back ----

    /** One look at [rec] for folk to give back, at night and out of sight, a few at a time (KEEP mode). */
    fun releaseRound(level: ServerLevel, rec: VillageRec, opts: Opts): Int {
        val bell = BlockPos.of(rec.key)
        val night = night(level)
        var given = 0
        for (r in Residents.get(level).byCentre(bell)) {
            if (!r.alive) continue
            val body = r.uuid?.let { level.getEntity(it) as? Townsperson } ?: continue
            if (body.resident != r.id || !mayGive(body)) continue
            if (!opts.force && !night && !body.isSleeping) continue
            if (!opts.force && watched(level, body)) continue
            if (opts.rate && !VillageBudget.adoptOk(rec.key)) break
            if (giveBack(level, rec, r, body)) given++
        }
        return given
    }

    /**
     * How many of [rec]'s folk are still to be given back: those taken in who are townsfolk yet (alive, with their keepsake kept), and
     * its people an outlaws' camp holds (given back once they are freed and home). A lost village is not dropped while any are, so that
     * they are given back when their bodies are next met at night, not left townsfolk of a village nobody keeps.
     */
    fun owed(level: ServerLevel, rec: VillageRec): Int {
        val held = HashSet<Int>()
        for (band in io.github.vaspyyy.guildmark.outlaw.OutlawCamps.get(level).bands) for (c in band.captives) if (c.resident >= 0) held.add(c.resident)
        return Residents.get(level).byCentre(BlockPos.of(rec.key)).count { r -> r.alive && VillageKeepsakes.has(level, r.id) || r.died < 0 && r.id in held }
    }

    /** Whether the body is one the folk may give back: not a tale's, a captive's, a leader's or a crowd's, and not in a talk or at the wares. */
    fun mayGive(p: Townsperson): Boolean =
        p.isAlive && p.tale < 0 && p.captiveOf < 0 && !p.crowd && !p.watcher && !p.isLeader && p.director == null

    /**
     * Gives [r] back as a villager, where their body [p] stands: the keepsake's villager (a new one of their trade if there is none),
     * with the bed and job block they held, and the body is discarded. The record stays, with the villager's uuid, so that a later
     * adoption puts the same person back. True if it was done.
     */
    fun giveBack(level: ServerLevel, rec: VillageRec, r: Resident, p: Townsperson): Boolean {
        val tag = VillageKeepsakes.get(level, r.id)
        val v = villagerFor(level, r, tag, p) ?: return false
        if (!level.noCollision(v)) return false
        val residents = Residents.get(level)
        // The bed they held goes back to them with the ticket it holds; the job block the same
        val bed = r.bed
        if (bed != null) {
            if (HeldBeds.get(level).has(bed) && level.isLoaded(bed)) {
                HeldBeds.get(level).remove(bed)
                v.brain.setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed))
            } else {
                Furnish.release(level, bed)
            }
        }
        val job = tag?.let { VillageKeepsakes.pos(it, VillageKeepsakes.JOB) }
        if (job != null && level.isLoaded(job) && claim(level, job)) v.brain.setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), job))
        if (p.isSleeping) p.stopSleeping()
        r.uuid = v.uuid
        residents.add(r)
        level.addFreshEntityWithPassengers(v)
        if (v.isRemoved) {
            r.uuid = p.uuid
            residents.add(r)
            return false
        }
        p.discard()
        if (tag != null) {
            VillageKeepsakes.remove(level, r.id)
            from?.values?.remove(r.id)
        }
        return true
    }

    /** The villager for [r]: from [tag], else a fresh one of their old trade; standing where [p] does. Null if it cannot be made. */
    private fun villagerFor(level: ServerLevel, r: Resident, tag: CompoundTag?, p: Townsperson): Villager? {
        val full = if (tag != null) VillageKeepsakes.cut(tag) else CompoundTag()
        full.putString("id", "minecraft:villager")
        val pos = ListTag()
        pos.add(DoubleTag.valueOf(p.x))
        pos.add(DoubleTag.valueOf(p.y))
        pos.add(DoubleTag.valueOf(p.z))
        full.put("Pos", pos)
        val motion = ListTag()
        repeat(3) { motion.add(DoubleTag.valueOf(0.0)) }
        full.put("Motion", motion)
        val turn = ListTag()
        turn.add(FloatTag.valueOf(p.yRot))
        turn.add(FloatTag.valueOf(0f))
        full.put("Rotation", turn)
        val input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), full)
        val v = EntityType.create(EntityTypes.VILLAGER, input, level, EntitySpawnReason.LOAD).orElse(null) as? Villager ?: return null
        if (tag == null) {
            // No keepsake (a command, an old save): a new villager of the trade they had, at its first level
            var data = v.villagerData.withType(level.registryAccess(), VillagerType.byBiome(level.getBiome(p.blockPosition())))
            if (r.craft.isNotEmpty()) {
                val key = ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.parse(r.craft))
                data = data.withProfession(level.registryAccess(), key)
            }
            v.villagerData = data
        }
        return v
    }

    // ---- Tickets ----

    private fun poi(level: ServerLevel, pos: BlockPos): PoiRecord? =
        level.poiManager.getInChunk({ true }, ChunkPos.containing(pos), PoiManager.Occupancy.ANY).filter { it.pos == pos }.findFirst().orElse(null)

    /** Makes sure the point of interest at [pos] has a ticket taken (one a villager held stays taken). False if it is not one. */
    private fun claim(level: ServerLevel, pos: BlockPos): Boolean {
        val record = poi(level, pos) ?: return false
        if (record.isOccupied) return true
        level.poiManager.take({ true }, { _, at -> at == pos }, pos, 1)
        return true
    }

    /** Lets go of a ticket held for a villager that is gone; true if there was none to let go, or it was, false if its land is not loaded. */
    private fun letGo(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.isLoaded(pos)) return false
        if (poi(level, pos)?.isOccupied == true) level.poiManager.release(pos)
        return true
    }

    /** The dead of [rec]: the job block they held is let go and their keepsake dropped (once the land is loaded to let it go). */
    private fun reap(level: ServerLevel, rec: VillageRec) {
        for (r in Residents.get(level).byCentre(BlockPos.of(rec.key))) {
            if (r.alive || r.died < 0) continue
            val tag = VillageKeepsakes.get(level, r.id) ?: continue
            val job = VillageKeepsakes.pos(tag, VillageKeepsakes.JOB)
            if (job != null && !letGo(level, job)) continue
            VillageKeepsakes.remove(level, r.id)
        }
    }

    // ---- Old worlds ----

    /**
     * The first count of a village moves the people the game took in "with what they have" (townsfolk outside a capital, centred on
     * their own spot) to the village's bell, if that spot is inside its box (design 14.2): they keep their ids, homes and memory.
     * Capital people (the plan's) are never moved.
     */
    private fun migrate(level: ServerLevel, rec: VillageRec) {
        val bell = BlockPos.of(rec.key)
        val residents = Residents.get(level)
        var moved = 0
        for (r in residents.all().toList()) {
            if (r.capital == bell || r.planned >= 0) continue
            if (abs(r.capital.x - bell.x) > rec.radius || abs(r.capital.z - bell.z) > rec.radius) continue
            if (Villages.at(level, r.capital)?.key != rec.key) continue
            residents.add(
                Resident(
                    r.id, r.uuid, bell, r.planned, r.given, r.family, r.role, r.look, r.home, r.bed, r.furnished, r.traits, r.alive, r.died, r.since,
                    r.left, r.craft,
                ),
            )
            moved++
        }
        if (moved > 0) Guildmark.LOGGER.info("{} people the game had taken in near the village at {} are now that village's own", moved, bell.toShortString())
    }

    // ---- For the commands ----

    /** What a village's folk are, for `village info` and `village folk`. */
    class Summary(
        val people: Int, val adopted: Int, val loaded: Int, val vanilla: Int, val exempt: Map<Why, Int>, val pending: Int, val waiting: Int,
    )

    fun summary(level: ServerLevel, rec: VillageRec): Summary {
        val bell = BlockPos.of(rec.key)
        val alive = Residents.get(level).byCentre(bell).filter { it.alive }
        val adopted = alive.count { VillageKeepsakes.has(level, it.id) }
        val loaded = alive.count { r -> r.uuid?.let { level.getEntity(it) } is Townsperson }
        val why = EnumMap<Why, Int>(Why::class.java)
        val vanilla = villagersOf(level, rec)
        var left = 0
        for (v in vanilla) {
            val w = exemptCheap(level, v) ?: exemptSlow(level, v, saved(level, v))
            if (w != null) why[w] = (why[w] ?: 0) + 1 else left++
        }
        return Summary(alive.size, adopted, loaded, vanilla.size, why, pending.values.count { it.key == rec.key }, left)
    }

    private fun infoLines(level: ServerLevel, rec: VillageRec): List<Component> {
        val out = ArrayList<Component>()
        fun label(s: String) = Component.literal(s).withStyle(ChatFormatting.YELLOW)
        fun plain(s: String) = Component.literal(s).withStyle(ChatFormatting.WHITE)
        val mode = when {
            !Villages.enabled() -> "Living Villages is off: the folk are given back"
            !folkOn() -> "villageFolk is off: the folk are given back"
            !rec.folk -> "released: nobody is taken in until `village adopt`"
            else -> "folk are taken in at night"
        }
        out.add(Component.empty().append(label("Folk: ")).append(plain(mode)))
        if (!Villages.enabled()) return out
        val s = summary(level, rec)
        val line = StringBuilder("${s.people} people, ${s.adopted} taken in from villagers (${s.loaded} bodies about); ${s.vanilla} vanilla villagers here")
        if (s.vanilla > 0) {
            val reasons = s.exempt.entries.filter { it.value > 0 }.joinToString(", ") { "${it.value} ${it.key.words}" }
            line.append(": ${s.waiting} to be taken in")
            if (reasons.isNotEmpty()) line.append(", $reasons")
        }
        if (s.pending > 0) line.append("; ${s.pending} being swapped now")
        out.add(Component.empty().append(label("  ")).append(plain(line.toString())))
        return out
    }

    /** Asks for the folk of [rec] to be taken in now (a command): the first step for each villager that may be, the second when the write is done. */
    fun adoptNow(level: ServerLevel, rec: VillageRec, force: Boolean, who: UUID?): Pair<Job, Tally> {
        if (!rec.folk) {
            rec.folk = true
            io.github.vaspyyy.guildmark.village.live.VillageData.get(level).setDirty()
        }
        val job = Job(who, rec.key, force)
        val tally = Tally()
        adoptRound(level, rec, Opts(force, false, job), tally)
        if (who != null && job.queued > 0) jobs.add(job)
        return job to tally
    }

    /** What `village release` did: [given] back now, those already villagers, those whose body is not about ([away]), and those left as they are ([kept], busy or a tale's). */
    class Gave(var given: Int = 0, var already: Int = 0, var away: Int = 0, var kept: Int = 0)

    /** Gives back [rec]'s folk at once (a command): the village takes in no one until `village adopt`. */
    fun releaseNow(level: ServerLevel, rec: VillageRec): Gave {
        if (rec.folk) {
            rec.folk = false
            io.github.vaspyyy.guildmark.village.live.VillageData.get(level).setDirty()
        }
        val bell = BlockPos.of(rec.key)
        val gave = Gave()
        for (r in Residents.get(level).byCentre(bell)) {
            if (!r.alive) continue
            val there = r.uuid?.let { level.getEntity(it) }
            when {
                there is Villager -> gave.already++
                there !is Townsperson || there.resident != r.id -> gave.away++
                !mayGive(there) -> gave.kept++
                giveBack(level, rec, r, there) -> gave.given++
                else -> gave.kept++
            }
        }
        return gave
    }

    /** Whether any swap is in the air for [key] (the commands say so). */
    fun swapping(key: Long): Int = pending.values.count { it.key == key }
}
