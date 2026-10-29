package io.github.vaspyyy.guildmark.village.live.build

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks
import io.github.vaspyyy.guildmark.economy.works.build.WorksFacingBlock
import io.github.vaspyyy.guildmark.economy.works.build.WorksYardBlock
import io.github.vaspyyy.guildmark.economy.works.build.WorksYardBlockEntity
import io.github.vaspyyy.guildmark.economy.works.build.YardStuff
import io.github.vaspyyy.guildmark.homestead.Build
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.Laid
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.ToppingOut
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import it.unimi.dsi.fastutil.ints.IntList
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The villages' projects going up (living-world/villages/design.md 12.3 to 12.5): each a [VillageBuild] saved with the overworld as
 * `village_builds`, laid by its crew as a [VillageSite].
 *
 * A project funded at a village's board is built by its builders (advance/AdvanceBuilders, guild/GuildHall, advance/GrandBuilders)
 * into a recording ([BuildSink]) that [accept] makes a site: its yard set out by the bell toward the work (a crate and heaps of what
 * it is made of), its blocks put in their order ([VillageOrder]), what lies deep underground laid at once, and the crew to come at
 * the next sunrise. Each second, for each site whose land is loaded, the work clock is wound on; with a player within [NEAR] blocks
 * in working hours the crew is about it ([BuilderCrew]), and whatever the clock allows beyond what they keep in hand is laid each
 * tick, as a capital's works are. When the last block is in, what the builders left to the end is done (the hall's receptionist, the
 * market's stall-keepers), the crew cheers and goes, the yard is cleared away, and the village is counted again.
 *
 * The fallbacks (12.4): a site whose crew has had three times its days is finished out of sight at the next night with nobody within
 * [UNSEEN] blocks; one whose village is lost, or with the switches off, is finished at once, out of sight, as its land loads; a
 * project funded while the village's last is still going up has the last finished first. With `villageBuilds` off (or Living Villages
 * off) no recording is opened, the builders build at once as they always did, and nothing is written here.
 */
object VillageBuilds {
    /** How near a player must be for the crew to come, and how near the residents lent to it must be to count. */
    const val NEAR = 72.0
    private const val BODY_NEAR = 96.0

    /** Nobody within this many blocks: an overdue site may be finished out of sight. */
    private const val UNSEEN = 48.0

    /** The most steps the clock lays in a tick beyond what the crew keeps in hand. */
    private const val CATCH_UP = 48

    /** How far from the bell the yard may be set out, and the most people who gather round a topping out, and how many come each second. */
    private const val YARD_NEAR = 8
    private const val YARD_FAR = 16
    private const val GATHER_MOST = 12
    private const val GATHER_EACH = 2

    /** The saved sites, and the id the next is given. */
    class Data(val sites: MutableMap<Int, VillageBuild> = LinkedHashMap(), var next: Int = 1) : SavedData() {
        companion object {
            val CODEC: Codec<Data> = RecordCodecBuilder.create { i ->
                i.group(
                    VillageBuild.LIST.optionalFieldOf("sites", listOf()).forGetter { d -> d.sites.values.toList() },
                    Codec.INT.optionalFieldOf("next", 1).forGetter(Data::next),
                ).apply(i) { list, next -> Data(list.associateByTo(LinkedHashMap()) { it.id }, max(next, (list.maxOfOrNull { it.id } ?: 0) + 1)) }
            }

            val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_builds"), Supplier { Data() }, CODEC)
        }
    }

    /** The overworld's sites, whichever level asks. */
    fun get(level: ServerLevel): Data = level.server.overworld().dataStorage.computeIfAbsent(Data.TYPE)

    /** The sites made from their records this session, by id, and the closures their builders left to the end (not saved). */
    private val live = HashMap<Int, VillageSite>()
    private val closures = HashMap<Int, List<() -> Unit>>()

    /** The sites whose land is loaded (the clock lays only those), and those with a player near. */
    private val loaded = HashSet<Int>()
    private val watched = HashSet<Int>()

    /** Whether the villages' projects are built by a crew: Living Villages and `villageBuilds` on. */
    fun enabled(): Boolean = Villages.enabled() && try {
        GuildmarkConfig.VILLAGE_BUILDS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** At setup: the tick, the crews' hook to find the sites, and the commands. */
    fun register() {
        BuilderCrew.sites.add(BuilderCrew.Sites { key -> if (key <= VillageSite.KEY) live[VillageSite.KEY - key] else null })
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> reset() }
        VillageBuildCommand.init()
    }

    private fun reset() {
        told.clear()
        live.clear()
        closures.clear()
        loaded.clear()
        watched.clear()
    }

    /** Something of a site has changed: the file is written at the next save. */
    fun changed(level: ServerLevel) {
        get(level).setDirty()
    }

    // ---- The sink's questions ----

    /** The village a build at [key] (its bell, or the board's place if there is no bell) is for: its record, if it is one of the registry's. */
    fun villageOf(level: ServerLevel, key: Long): VillageRec? =
        Villages.byKey(level, key)?.takeIf { it.state != Live.LOST } ?: Villages.at(level, BlockPos.of(key))

    /** Whether a build for [key] is recorded for a crew: the switches on, in the overworld, for a village of the registry. */
    fun canRecord(level: ServerLevel, key: Long): Boolean =
        enabled() && level.dimension() == Level.OVERWORLD && villageOf(level, key) != null

    /** A new project for [key] is about to be built: the village's last, if it is still going up, is finished first (what is loaded at once, the rest as it loads). */
    fun beforeRecord(level: ServerLevel, key: Long) {
        val rec = villageOf(level, key) ?: return
        val data = get(level)
        for (b in data.sites.values.toList()) {
            if (b.village != rec.key) continue
            b.rushing = true
            data.setDirty()
            val site = siteOf(level, b)
            rush(level, data, b, site, partly = true)
        }
    }

    /** Whether the village at [village] has a project going up: its record. */
    fun recFor(level: ServerLevel, village: Long): VillageBuild? = get(level).sites.values.firstOrNull { it.village == village && !it.rushing }

    /** The site of the village at [village] going up, made from its record, if its land is loaded. */
    fun siteFor(level: ServerLevel, village: Long): VillageSite? {
        val b = recFor(level, village) ?: return null
        return live[b.id]?.takeIf { b.id in loaded }
    }

    /** All the sites, for the commands. */
    fun all(level: ServerLevel): List<VillageBuild> = get(level).sites.values.toList()

    /** Whether a player is near site [id] (its crew is about it then). */
    fun watched(id: Int): Boolean = id in watched

    /** Whether site [id]'s land is loaded. */
    fun isLoaded(id: Int): Boolean = id in loaded

    /** Site [id] made from its record (kept while the record is the same). */
    fun site(level: ServerLevel, id: Int): VillageSite? = get(level).sites[id]?.let { siteOf(level, it) }

    // ---- A recording becomes a site ----

    /**
     * What the builders of a village project recorded ([r]) becomes a site for a crew to lay: its yard, its order, its saved record, and
     * what is to be done at its end. One with nothing to lay is done at once; one too big to record, or with no village, is laid at once.
     */
    fun accept(level: ServerLevel, r: BuildSink.Recording) {
        val rec = villageOf(level, r.key)
        if (rec == null || r.size > BuildSink.CAP) {
            layAtOnce(level, r)
            return
        }
        val project = Advance.entries[r.project.coerceIn(0, Advance.entries.size - 1)]
        val bell = BlockPos.of(rec.key)
        val box = boxOf(r)
        val yard = yardFor(level, rec, bell, box, project, r)
        val plan = VillageOrder.plan(level, project, r, bell, BlockPos.of(yard.crate))
        // What lies deep under the ground (a path laid far below where it should be) is laid at once, out of sight
        for ((pos, state) in plan.buried) level.setBlock(BlockPos.of(pos), state, VillageSite.FLAGS)
        if (plan.steps.isEmpty()) {
            done(level, r.after, r.closures)
            return
        }
        val data = get(level)
        val id = data.next++
        val b = recordOf(id, rec.key, project, level.gameTime, opens(level), plan, yard, r.after)
        data.sites[id] = b
        data.setDirty()
        if (r.closures.isNotEmpty()) closures[id] = r.closures.toList()
        // Said after the funding's own news (Advances), at the next tick
        told.add(bell to Component.translatable("message.guildmark.village_build.planned", project.title, nameOf(rec)))
    }

    /**
     * The record of site [id] for the village at [village]: [project]'s [plan] as it is saved (each distinct block once, in a palette),
     * its [yard], what is to be done at its [after] end, the game time it was [born] and the clock time its crew first comes ([opens]).
     */
    fun recordOf(id: Int, village: Long, project: Advance, born: Long, opens: Long, plan: VillageOrder.Result, yard: VillageBuild.Yard, after: List<BuildSink.After>): VillageBuild {
        val palette = ArrayList<BlockState>()
        val index = HashMap<BlockState, Int>()
        val pos = LongArray(plan.steps.size)
        val meta = IntArray(plan.steps.size)
        for ((k, s) in plan.steps.withIndex()) {
            pos[k] = s.pos
            val state = s.state
            val p = (state ?: Blocks.AIR.defaultBlockState()).let { st -> index.getOrPut(st) { palette.add(st); palette.size - 1 } }
            meta[k] = VillageBuild.meta(s.kind, s.swap, p)
        }
        val starts = IntArray(VillageBuild.STAGES + 1)
        var k = 0
        for (stage in Stage.entries) {
            starts[stage.ordinal] = k
            while (k < plan.steps.size && plan.steps[k].stage == stage) k++
        }
        starts[VillageBuild.STAGES] = plan.steps.size
        return VillageBuild(id, village, project.ordinal, born, opens, pos, meta, palette, starts, Build(), yard, after.toMutableList(), deps = plan.deps)
    }

    /** What is to be said to those near a village at the next tick. */
    private val told = ArrayList<Pair<BlockPos, Component>>()

    /** A recording laid at once, as a direct build would have left it, and its end done now. */
    fun layAtOnce(level: ServerLevel, r: BuildSink.Recording) {
        for (k in 0 until r.size) {
            val pos = BlockPos.of(r.order.getLong(k))
            if (level.getBlockState(pos) != r.states[k]) level.setBlock(pos, r.states[k], VillageSite.FLAGS)
        }
        done(level, r.after, r.closures)
    }

    private fun done(level: ServerLevel, after: List<BuildSink.After>, run: List<() -> Unit>) {
        for (task in after) BuildSink.revive(level, task)
        for (task in run) {
            try {
                task()
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A task left for the end of a village build failed", e)
            }
        }
    }

    /** The clock time the crew first comes: the next sunrise (at once, if the clock stands still). */
    private fun opens(level: ServerLevel): Long {
        if (!level.gameRules.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)) return Long.MIN_VALUE
        return (Math.floorDiv(level.overworldClockTime, 24000L) + 1) * 24000L
    }

    private fun boxOf(r: BuildSink.Recording): IntArray {
        var x0 = Int.MAX_VALUE; var z0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var z1 = Int.MIN_VALUE
        for (k in 0 until r.size) {
            val p = r.order.getLong(k)
            x0 = min(x0, BlockPos.getX(p)); x1 = max(x1, BlockPos.getX(p))
            z0 = min(z0, BlockPos.getZ(p)); z1 = max(z1, BlockPos.getZ(p))
        }
        return intArrayOf(x0, z0, x1, z1)
    }

    // ---- The yard ----

    /**
     * Where the crew works from: the first free ground within [YARD_NEAR] blocks of the bell toward the work (or [YARD_FAR] if none),
     * off the work and its paths, for the crate and a heap or two of what the project is made of beside it; the gate before it; and the
     * road the strangers come in by: a trade road's column about forty blocks out, or forty blocks out away from the work.
     */
    private fun yardFor(level: ServerLevel, rec: VillageRec, bell: BlockPos, box: IntArray, project: Advance, r: BuildSink.Recording): VillageBuild.Yard {
        val cx = (box[0] + box[2]) / 2.0
        val cz = (box[1] + box[3]) / 2.0
        val toward = Direction.getApproximateNearest((cx - bell.x).toFloat(), 0f, (cz - bell.z).toFloat()).let { if (it.axis.isVertical) Direction.NORTH else it }
        val written = HashSet<Long>()
        for (k in 0 until r.size) written.add(BlockPos.asLong(BlockPos.getX(r.order.getLong(k)), 0, BlockPos.getZ(r.order.getLong(k))))
        val kinds = heapsOf(r)
        fun off(x: Int, z: Int): Boolean = x < box[0] - 2 || x > box[2] + 2 || z < box[1] - 2 || z > box[3] + 2 || BlockPos.asLong(x, 0, z) !in written && !inBox(x, z, box)
        fun ground(x: Int, z: Int): BlockPos? {
            if (!level.hasChunkAt(BlockPos(x, 0, z))) return null
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            val under = level.getBlockState(top.below())
            if (under.`is`(Blocks.DIRT_PATH) || !level.getFluidState(top.below()).isEmpty || !under.isFaceSturdy(level, top.below(), Direction.UP)) return null
            if (!roomy(level, top) || !roomy(level, top.above())) return null
            return top
        }
        var found: Pair<BlockPos, Direction>? = null
        loop@ for (reach in intArrayOf(YARD_NEAR, YARD_FAR)) {
            val spots = ArrayList<BlockPos>()
            for (dx in -reach..reach) for (dz in -reach..reach) {
                val d2 = dx * dx + dz * dz
                if (d2 < 9 || d2 > reach * reach) continue
                spots.add(BlockPos(bell.x + dx, 0, bell.z + dz))
            }
            // Toward the work first, then the nearest the bell
            spots.sortWith(compareBy<BlockPos>({ -((it.x - bell.x) * toward.stepX + (it.z - bell.z) * toward.stepZ).coerceAtMost(4) }, { it.distSqr(BlockPos(bell.x, 0, bell.z)) }))
            for (s in spots) {
                if (!off(s.x, s.z)) continue
                val crate = ground(s.x, s.z) ?: continue
                // The crate faces the work; its heaps stand either side of it, and the crew stands before it
                val face = Direction.getApproximateNearest((cx - s.x).toFloat(), 0f, (cz - s.z).toFloat()).let { if (it.axis.isVertical) toward else it }
                val front = crate.relative(face)
                if (!off(front.x, front.z) || !roomy(level, front) || !roomy(level, front.above())) continue
                val sides = listOf(face.clockWise, face.counterClockWise).take(kinds.size)
                if (sides.any { d -> val h = crate.relative(d); !off(h.x, h.z) || ground(h.x, h.z)?.y != crate.y }) continue
                found = crate to face
                break@loop
            }
        }
        val out = toward.opposite
        val road = roadFor(level, bell, toward, rec)
        if (found == null) {
            // Nowhere free: the crew works from beside the bell, with nothing set out
            val at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bell.relative(toward, 2))
            return VillageBuild.Yard(at.asLong(), at.relative(out).asLong(), road.asLong(), toward.get2DDataValue())
        }
        val (crate, face) = found
        val sides = listOf(face.clockWise, face.counterClockWise).take(kinds.size)
        val gate = crate.relative(face, 2)
        return VillageBuild.Yard(crate.asLong(), gate.asLong(), road.asLong(), face.opposite.get2DDataValue(), sides.map { crate.relative(it).asLong() }, kinds)
    }

    private fun inBox(x: Int, z: Int, box: IntArray): Boolean = x in box[0]..box[2] && z in box[1]..box[3]

    private fun roomy(level: ServerLevel, pos: BlockPos): Boolean {
        val s = level.getBlockState(pos)
        return (s.isAir || s.canBeReplaced()) && s.fluidState.isEmpty && !s.hasBlockEntity()
    }

    /** The two materials the project has most of (their [YardStuff] ordinals), for its heaps. */
    private fun heapsOf(r: BuildSink.Recording): List<Int> {
        val n = IntArray(Stuff.PAID.size)
        for (s in r.states) {
            val stuff = VillageSite.stuffOf(s)
            if (stuff != Stuff.FREE) n[stuff.ordinal]++
        }
        return Stuff.PAID.indices.filter { n[it] > 0 }.sortedByDescending { n[it] }.take(2).mapNotNull { YardStuff.of(Stuff.PAID[it])?.ordinal }
    }

    /** Where the strangers come in from: a trade road's column 24 to 56 blocks from the bell, the nearest forty; else forty blocks out, away from the work. */
    private fun roadFor(level: ServerLevel, bell: BlockPos, toward: Direction, rec: VillageRec): BlockPos {
        var best: BlockPos? = null
        var bestD = Double.MAX_VALUE
        for (road in RoadNetwork.get(level).roads) {
            if (!road.touches(bell, 96.0)) continue
            for (column in road.columnsByChunk.values) for ((x, z) in column) {
                val dx = (x - bell.x).toDouble()
                val dz = (z - bell.z).toDouble()
                val d = kotlin.math.sqrt(dx * dx + dz * dz)
                if (d < 24 || d > 56) continue
                val off = abs(d - 40)
                if (off < bestD) {
                    bestD = off
                    best = BlockPos(x, bell.y, z)
                }
            }
        }
        return best ?: bell.relative(toward.opposite, 40)
    }

    /** Sets out [b]'s yard where it has fallen away (the crate and its heaps go only into air or plants). */
    private fun placeYard(level: ServerLevel, b: VillageBuild) {
        val y = b.yard
        val face = y.outDir.opposite
        val crate = BlockPos.of(y.crate)
        if (y.heaps.isEmpty() && y.kinds.isEmpty() && level.getBlockState(crate).block !== WorksBlocks.CRATE.get() && !free(level, crate)) return
        if (free(level, crate)) level.setBlock(crate, WorksBlocks.CRATE.get().defaultBlockState().setValue(WorksFacingBlock.FACING, face), Block.UPDATE_ALL)
        for ((k, h) in y.heaps.withIndex()) {
            val pos = BlockPos.of(h)
            val stuff = YardStuff.entries.getOrNull(y.kinds.getOrElse(k) { -1 }) ?: continue
            if (free(level, pos)) level.setBlock(pos, WorksBlocks.yard(stuff).get().defaultBlockState().setValue(WorksFacingBlock.FACING, face), Block.UPDATE_ALL)
        }
    }

    private fun free(level: ServerLevel, pos: BlockPos): Boolean {
        val s = level.getBlockState(pos)
        return (s.isAir || s.canBeReplaced()) && s.fluidState.isEmpty && !s.hasBlockEntity()
    }

    /** The heaps show what is left to lay of what they hold. */
    private fun syncHeaps(level: ServerLevel, b: VillageBuild, site: VillageSite) {
        if (b.yard.heaps.isEmpty()) return
        val left = site.left()
        for ((k, h) in b.yard.heaps.withIndex()) {
            val stuff = YardStuff.entries.getOrNull(b.yard.kinds.getOrElse(k) { -1 }) ?: continue
            val be = level.getBlockEntity(BlockPos.of(h)) as? WorksYardBlockEntity ?: continue
            be.set(left[stuff.stuff.ordinal], max(1, site.plan.needs[stuff.stuff.ordinal]))
        }
    }

    /** The yard taken away again (only the works' own blocks it set out). */
    private fun clearYard(level: ServerLevel, b: VillageBuild) {
        val crate = BlockPos.of(b.yard.crate)
        if (level.isLoaded(crate) && level.getBlockState(crate).`is`(WorksBlocks.CRATE.get())) level.setBlock(crate, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        for (h in b.yard.heaps) {
            val pos = BlockPos.of(h)
            if (level.isLoaded(pos) && level.getBlockState(pos).block is WorksYardBlock) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        }
    }

    // ---- Every tick ----

    private fun siteOf(level: ServerLevel, b: VillageBuild): VillageSite {
        val have = live[b.id]
        if (have != null && have.rec === b && have.level === level) return have
        return VillageSite(level, b).also { live[b.id] = it }
    }

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        if (told.isNotEmpty()) {
            for ((at, line) in told) Advances.announce(level, at, line)
            told.clear()
        }
        val data = get(level)
        if (data.sites.isEmpty()) return
        val now = level.gameTime
        for (b in data.sites.values.toList()) {
            if (Math.floorMod(now + b.id * 7L, 20L) == 0L) {
                try {
                    second(level, data, b)
                } catch (e: RuntimeException) {
                    // One site's trouble never stops the rest: it is tried again in a second
                    Guildmark.LOGGER.warn("Village build {}: {}", b.id, e.toString())
                }
            }
            val site = live[b.id] ?: continue
            if (b.id in loaded && !site.finished && !b.rushing && data.sites.containsKey(b.id) && (b.id !in watched || site.workday())) catchUp(site)
        }
    }

    private fun second(level: ServerLevel, data: Data, b: VillageBuild) {
        val rec = Villages.byKey(level, b.village)
        // With the switches off, or the village lost, it is finished at once, out of sight
        if (!enabled() || rec == null || rec.state == Live.LOST) {
            if (!b.rushing) {
                b.rushing = true
                data.setDirty()
            }
        }
        val site = siteOf(level, b)
        if (b.rushing) {
            rush(level, data, b, site, partly = false)
            return
        }
        site.wind()
        data.setDirty()
        if (!site.loaded()) {
            loaded.remove(b.id)
            watched.remove(b.id)
            BuilderCrew.update(level, site, false)
            return
        }
        loaded.add(b.id)
        placeYard(level, b)
        syncHeaps(level, b, site)
        val near = near(level, site, NEAR)
        if (near) watched.add(b.id) else watched.remove(b.id)
        // Three times its days and still not done: finished out of sight at night, with nobody about to see it
        if (site.overdue() && night(level) && !near(level, site, UNSEEN)) {
            b.rushing = true
            rush(level, data, b, site, partly = false)
            return
        }
        site.named = named(level, b, site)
        val wanted = near && site.opened() && site.workday() && !site.finished && level.isPositionEntityTicking(site.crate)
        BuilderCrew.update(level, site, wanted)
        gather(level, site, near)
        val stage = site.finishedStage
        if (stage != null) {
            site.finishedStage = null
            staged(level, site, stage, near)
        }
        if (site.finished) finish(level, data, b, site, near)
    }

    /** The clock's laying: whatever is beyond what the crew keeps in hand, as fast as it may (as a capital's works). */
    private fun catchUp(site: VillageSite) {
        val reserve = BuilderCrew.reserve(site.key)
        val build = site.build
        if (build.work < 1.0 + reserve) return
        val fx = site.id in watched && build.work - reserve < 4.0
        var budget = CATCH_UP
        var i = build.step
        while (budget > 0 && build.work >= 1.0 + reserve && !site.finished) {
            if (i < build.step) i = build.step
            val end = site.plan.stageStarts[site.stage.ordinal + 1]
            while (i < end && !site.open(i)) i++
            if (i >= end) break
            when (site.lay(i, fx)) {
                Laid.DONE -> budget--
                Laid.WAIT, Laid.BLOCKED -> i++
                Laid.NEED, Laid.LATER -> break
            }
        }
    }

    /** Lays what is left of [site] at once, out of sight, and finishes it ([partly]: what is loaded now, the rest left for when it loads). */
    private fun rush(level: ServerLevel, data: Data, b: VillageBuild, site: VillageSite, partly: Boolean) {
        if (!site.loaded()) {
            loaded.remove(b.id)
            watched.remove(b.id)
            BuilderCrew.update(level, site, false)
            if (!partly) return
        }
        site.hurry = true
        for (i in site.build.step until site.total) {
            if (site.isDone(i)) continue
            if (!level.isLoaded(site.at(i))) continue
            site.lay(i, fx = false, free = true)
            if (!site.isDone(i)) site.skip(i)
        }
        site.finishedStage = null
        if (site.finished) finish(level, data, b, site, near = false)
    }

    /** [site] is laid to its last step: what was left to the end done, the crew's cheer and goodbye, the yard cleared, the village counted again. */
    private fun finish(level: ServerLevel, data: Data, b: VillageBuild, site: VillageSite, near: Boolean) {
        data.sites.remove(b.id)
        data.setDirty()
        val run = closures.remove(b.id) ?: listOf()
        done(level, b.after, run)
        clearYard(level, b)
        if (near) BuilderCrew.finished(level, site.key) else BuilderCrew.dismiss(site.key)
        live.remove(b.id)
        loaded.remove(b.id)
        watched.remove(b.id)
        Villages.recount(level, b.village)
        val rec = Villages.byKey(level, b.village)
        val bell = BlockPos.of(b.village)
        Advances.announce(level, bell, Component.translatable("message.guildmark.village_build.done", site.project.title, nameOf(rec)))
        if (near) {
            val c = Vec3.atCenterOf(site.box.center)
            level.playSound(null, BlockPos.containing(c), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.NEUTRAL, 1.0f, 1.0f)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y + 1.5, c.z, 40, 3.0, 1.5, 3.0, 0.0)
            if (site.project in BIG) firework(level, Vec3(c.x, site.box.maxY() + 2.0, c.z), big = true)
        }
    }

    /** A stage of [site] is finished: one of the crew calls it and the rest cheer; the bough up on a big project, a rocket and the town's cheer. */
    private fun staged(level: ServerLevel, site: VillageSite, stage: Stage, near: Boolean) {
        if (!near) return
        if (stage == Stage.TOPPING) ToppingOut.cheer(level, site.key) else ToppingOut.at(site.key)?.let { if (level.gameTime <= it.until) it.cued = level.gameTime }
        BuilderCrew.celebrate(level, site, stage)
        if (stage == Stage.TOPPING) site.bough()?.let { firework(level, Vec3.atCenterOf(it).add(0.0, 1.0, 0.0), big = false) }
    }

    /**
     * The topping out of the hall, the walls and the keep: as the bough is about to go up with a player near, the village's idle folk
     * drift over and stand round the work (ToppingOut's ring), a couple a second, up to [GATHER_MOST], and cheer as the crew does.
     */
    private fun gather(level: ServerLevel, site: VillageSite, near: Boolean) {
        if (!near || site.project !in BIG || site.stage != Stage.TOPPING || site.finished) return
        val lot = site.box
        val g = ToppingOut.at(site.key)?.takeIf { level.gameTime <= it.until } ?: ToppingOut.start(level, site.key, lot)
        g.members.removeIf { !it.isAlive || it.isRemoved || it.director == null }
        if (g.members.size >= GATHER_MOST) return
        val area = AABB.of(lot).inflate(UNSEEN, 8.0, UNSEEN)
        level.getEntitiesOfClass(Townsperson::class.java, area) { !lot.isInside(it.blockPosition()) && it.role != TownRole.GUARD && Aside.free(it) }
            .sortedBy { it.distanceToSqr(g.center) }.take(min(GATHER_EACH, GATHER_MOST - g.members.size)).forEach { ToppingOut.join(level, g, it) }
    }

    private fun firework(level: ServerLevel, at: Vec3, big: Boolean) {
        val shape = if (big) FireworkExplosion.Shape.LARGE_BALL else FireworkExplosion.Shape.STAR
        val stack = ItemStack(Items.FIREWORK_ROCKET)
        stack.set(DataComponents.FIREWORKS, Fireworks(1 + level.random.nextInt(2), listOf(FireworkExplosion(shape, IntList.of(0x3F8F3F, 0xE8C26A), IntList.of(0xFFFFFF), true, big))))
        level.addFreshEntity(FireworkRocketEntity(level, at.x, at.y, at.z, stack))
    }

    /** The village's name, for what is said of it. */
    private fun nameOf(rec: VillageRec?): String = rec?.name?.takeIf { it.isNotBlank() } ?: "the village"

    /** The projects topped out with a bough, a gathering and a rocket. */
    private val BIG = setOf(Advance.GUILD_HALL, Advance.STONE_WALLS, Advance.KEEP)

    /** Whether it is night by the overworld's clock (from seven in the evening to five in the morning). */
    private fun night(level: ServerLevel): Boolean = Math.floorMod(level.overworldClockTime, 24000L) in 13000L until 23000L

    /** Whether a player (not a spectator) is within [blocks] of [site] across. */
    fun near(level: ServerLevel, site: VillageSite, blocks: Double): Boolean {
        val c = site.box.center
        return level.players().any { !it.isSpectator && it.distanceToSqr(c.x + 0.5, it.y, c.z + 0.5) < blocks * blocks }
    }

    /** Today's residents lent to [b]'s site who are about it, alive and near: the strangers make up the rest. */
    private fun named(level: ServerLevel, b: VillageBuild, site: VillageSite): Int {
        val places = Villages.places(level, b.village) ?: return 0
        val crew = Hands.of(level, places)
        if (crew.builders.isEmpty()) return 0
        val people = Residents.get(level)
        val c = Vec3.atCenterOf(site.box.center)
        return crew.builders.count { id ->
            val uuid = people[id]?.uuid ?: return@count false
            val body = level.getEntity(uuid) as? Townsperson ?: return@count false
            body.isAlive && body.position().distanceTo(c) < BODY_NEAR
        }.coerceAtMost(site.crew - 1)
    }

    // ---- The commands' ----

    /** `village build rush`: [b]'s site laid out now, out of sight (what is not loaded, as it loads). */
    fun rushNow(level: ServerLevel, b: VillageBuild): Boolean {
        val data = get(level)
        b.rushing = true
        data.setDirty()
        val site = siteOf(level, b)
        rush(level, data, b, site, partly = true)
        return !data.sites.containsKey(b.id)
    }

    /** `village build start`: the crew comes now, whatever the hour of the first morning. */
    fun startNow(level: ServerLevel, b: VillageBuild) {
        b.opens = Long.MIN_VALUE
        changed(level)
    }

}
