package io.github.vaspyyy.guildmark.dragon

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.field.FieldBosses
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.quest.BossHunts
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.story.HollowThrone
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.AABB
import java.util.BitSet
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One dragon's roost: the summit it was marked on, the nest raised there, and whether its dragon lives or has been slain. */
class Roost(
    val id: Int,
    /** The summit while the nest is still to be raised (only x and z mean much then); afterwards the block its dragon lies on. */
    var pos: BlockPos,
    var built: Boolean = false,
    /** Which way the dragon lies once the nest is raised: a horizontal direction's index (see [direction]). */
    var facing: Int = 0,
    var tier: Int = 2,
    var dragon: UUID? = null,
    /** The dragon's name, for the map (an index into its names). */
    var name: Int = 0,
    var slain: Boolean = false,
    /** The day it was slain (see VillageBoards.day). */
    var slainDay: Long = 0,
    /** The game time its hoard is to be revealed in the nest, or -1 if none is waiting. */
    var hoardAt: Long = -1,
) {
    /** Seconds in a row nobody has found its dragon. Not saved. */
    internal var missing = 0

    val direction: Direction get() = Direction.from2DDataValue(facing)

    companion object {
        val CODEC: Codec<Roost> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Roost::id),
                BlockPos.CODEC.fieldOf("pos").forGetter(Roost::pos),
                Codec.BOOL.optionalFieldOf("built", false).forGetter(Roost::built),
                Codec.INT.optionalFieldOf("facing", 0).forGetter(Roost::facing),
                Codec.INT.optionalFieldOf("tier", 2).forGetter(Roost::tier),
                UUIDUtil.CODEC.optionalFieldOf("dragon").forGetter { Optional.ofNullable(it.dragon) },
                Codec.INT.optionalFieldOf("name", 0).forGetter(Roost::name),
                Codec.BOOL.optionalFieldOf("slain", false).forGetter(Roost::slain),
                Codec.LONG.optionalFieldOf("slain_day", 0L).forGetter(Roost::slainDay),
                Codec.LONG.optionalFieldOf("hoard_at", -1L).forGetter(Roost::hoardAt),
            ).apply(i) { id, pos, built, facing, tier, dragon, name, slain, slainDay, hoardAt ->
                Roost(id, pos, built, facing, tier, dragon.orElse(null), name, slain, slainDay, hoardAt)
            }
        }
    }
}

/**
 * The dragons of the high peaks and where they roost. The overworld is split into big cells; a roll from the
 * world's seed settles which of them may hold a roost at all (about one in three, and only out where the land
 * is tier 2 or more). When a player comes near a cell that's still open, the loaded part of it is looked over a
 * little at a time, never more than a few chunks a second, for its highest summit; one that stands high and
 * steep and clear of villages and the like is marked as a roost, and a cell with no such summit has none. Once a
 * player comes within sight and the land round it is loaded, the nest is built (see [DragonNest]), its dragon
 * set sleeping on it and a Wardfire raised a little way below. A slain dragon's hoard is revealed in its nest,
 * and a week of days later another dragon, with a name of its own, settles there when a player comes near.
 */
class DragonRoosts(
    val roosts: MutableList<Roost> = mutableListOf(),
    private val decided: MutableSet<Long> = HashSet(),
    private var counter: Int = 0,
) : SavedData() {
    private var tick = 0
    private var faults = 0

    /** What the loaded land of each open cell has shown so far. Not saved: it is all found again by looking. */
    private val surveys = HashMap<Long, Survey>()

    /** Summits marked and then found unfit for a nest (built over since, a village come near); they aren't marked again. Not saved. */
    private val unfit = ArrayList<BlockPos>()

    /** What has been looked at in one cell, and the highest points seen. */
    private class Survey(val cx: Int, val cz: Int) {
        /** The cell's chunks that have been looked at. */
        val seen = BitSet(CELL_CHUNKS * CELL_CHUNKS)
        var count = 0
        var samples = 0
        var total = 0L
        val tops = ArrayList<Summit>()
        /** New land was looked at since the summits were last judged. */
        var fresh = false
        /** A summit is waiting for the land round it to load. */
        var waiting = false

        val mean: Double get() = if (samples == 0) 0.0 else total.toDouble() / samples

        /** Takes a coarse reading of the chunk at [chunkX], [chunkZ]: sixteen heights, the highest kept if it stands out. */
        fun look(chunk: ChunkAccess, chunkX: Int, chunkZ: Int) {
            var best = Int.MIN_VALUE
            var bestX = 0
            var bestZ = 0
            for (i in 0 until 4) for (j in 0 until 4) {
                val lx = 2 + i * 4
                val lz = 2 + j * 4
                val h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz)
                samples++
                total += h
                if (h > best) {
                    best = h
                    bestX = (chunkX shl 4) + lx
                    bestZ = (chunkZ shl 4) + lz
                }
            }
            count++
            if (best >= MIN_SEEN) note(bestX, bestZ, best)
        }

        /** Notes a high point, merged with one already known within [MERGE] blocks (the higher stands), keeping only the best few. */
        private fun note(x: Int, z: Int, h: Int) {
            for (known in tops) {
                if (abs(known.x - x) > MERGE || abs(known.z - z) > MERGE) continue
                if (!known.rejected && h > known.h) {
                    known.x = x
                    known.z = z
                    known.h = h
                    known.refined = false
                }
                return
            }
            tops.add(Summit(x, z, h))
            if (tops.size > KEEP_TOPS) tops.remove(tops.minBy { it.h })
        }
    }

    /** A high point in a cell: [h] is the height of its ground; [refined] once it has been moved to the true top, [rejected] once it's found unfit. */
    private class Summit(var x: Int, var z: Int, var h: Int) {
        var refined = false
        var rejected = false
    }

    fun roost(id: Int): Roost? = if (id < 0) null else roosts.firstOrNull { it.id == id }

    /** The nearest roost's position to [from] (nest, or summit while it's yet to be built), or null if there are none. */
    fun nearest(level: ServerLevel, from: BlockPos): BlockPos? =
        roosts.minByOrNull { horizontal(from.x + 0.5, from.z + 0.5, it.pos) }?.pos

    private fun nextId(): Int = ++counter

    private fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    // ---- Every second ----

    /** Called every tick in the overworld; the work is done once a second. */
    fun tick(level: ServerLevel) {
        if (level.dimension() != Level.OVERWORLD || ++tick % 20 != 0) return
        try {
            pass(level)
        } catch (e: RuntimeException) {
            // Whatever it was, the world carries on without it; say so a few times, not every second
            if (faults++ < 3) Guildmark.LOGGER.error("The dragons' roosts failed to keep", e)
        }
    }

    private fun pass(level: ServerLevel) {
        // A slain dragon's hoard is owed whatever else is switched off
        for (roost in roosts.toList()) {
            if (roost.hoardAt in 0..level.gameTime) reveal(level, roost)
        }
        if (!enabled() || level.difficulty == Difficulty.PEACEFUL) return
        val players = level.players().filter { it.isAlive && !it.isSpectator }
        if (players.isEmpty()) return
        survey(level, players)
        var raised = false
        for (roost in roosts.toList()) {
            if (roost.built) {
                keep(level, roost, players)
            } else if (!raised && players.any { horizontal(it.x, it.z, roost.pos) < BUILD_RANGE }) {
                raised = raise(level, roost)
            }
        }
    }

    // ---- Finding summits ----

    /** Looks over the loaded land of every open cell a player is near that might hold a roost, and marks a roost on any summit that suits. */
    private fun survey(level: ServerLevel, players: List<ServerPlayer>) {
        var budget = SAMPLE_BUDGET
        val touched = LinkedHashSet<Survey>()
        for (player in players) {
            for (cz in Math.floorDiv(player.blockZ - SURVEY_RANGE, CELL)..Math.floorDiv(player.blockZ + SURVEY_RANGE, CELL)) {
                for (cx in Math.floorDiv(player.blockX - SURVEY_RANGE, CELL)..Math.floorDiv(player.blockX + SURVEY_RANGE, CELL)) {
                    val key = key(cx, cz)
                    if (key in decided || !possible(level, cx, cz, key)) continue
                    val survey = surveys.getOrPut(key) { Survey(cx, cz) }
                    if (budget > 0) {
                        val looked = look(level, survey, player, budget)
                        budget -= looked
                        if (looked > 0) survey.fresh = true
                    }
                    if (survey.fresh || survey.waiting) touched.add(survey)
                }
            }
        }
        for (survey in touched) judge(level, survey)
    }

    /**
     * Whether the cell at [cx], [cz] may hold a roost, settled from the world's seed alone (so the same in every
     * visit, and never by what happens to be loaded). A cell that may not is struck off.
     */
    private fun possible(level: ServerLevel, cx: Int, cz: Int, key: Long): Boolean {
        val random = java.util.Random(level.seed xor (cx * 341873128712L + cz * 132897987541L) xor SALT)
        val roll = random.nextFloat()
        val farX = if (abs(cx * CELL) > abs(cx * CELL + CELL - 1)) cx * CELL else cx * CELL + CELL - 1
        val farZ = if (abs(cz * CELL) > abs(cz * CELL + CELL - 1)) cz * CELL else cz * CELL + CELL - 1
        if (roll < CHANCE && QuestGenerator.tierAt(farX, farZ) >= MIN_TIER) return true
        decided.add(key)
        setDirty()
        return false
    }

    /** Takes a coarse reading of up to [budget] of the cell's chunks that are loaded, near [player] and not yet looked at; returns how many. */
    private fun look(level: ServerLevel, survey: Survey, player: ServerPlayer, budget: Int): Int {
        val firstX = survey.cx * CELL_CHUNKS
        val firstZ = survey.cz * CELL_CHUNKS
        val fromX = max(firstX, (player.blockX - SURVEY_RANGE) shr 4)
        val toX = min(firstX + CELL_CHUNKS - 1, (player.blockX + SURVEY_RANGE) shr 4)
        val fromZ = max(firstZ, (player.blockZ - SURVEY_RANGE) shr 4)
        val toZ = min(firstZ + CELL_CHUNKS - 1, (player.blockZ + SURVEY_RANGE) shr 4)
        var done = 0
        scan@ for (chunkZ in fromZ..toZ) for (chunkX in fromX..toX) {
            val bit = (chunkZ - firstZ) * CELL_CHUNKS + (chunkX - firstX)
            if (survey.seen[bit]) continue
            val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
            survey.look(chunk, chunkX, chunkZ)
            survey.seen.set(bit)
            if (++done >= budget) break@scan
        }
        return done
    }

    /**
     * Goes through the cell's high points, highest first, and marks a roost on the first that suits. A point is
     * judged only once the land round it is loaded; a higher one still waiting for its land holds back any much
     * lower (so the cell's highest summit is the one chosen when it can be). It must be 140 or more up, or at
     * least 104 and clearly the cell's peak (40 over its average, with a good part of the cell seen); then steep
     * (30 or more above ground within 48 blocks), dry, on land of tier 2 or more and apart from everything else.
     * A cell with no such point stays open, as more of it may load.
     */
    private fun judge(level: ServerLevel, survey: Survey) {
        survey.fresh = false
        survey.waiting = false
        var holdBelow = Int.MIN_VALUE
        for (summit in survey.tops.sortedByDescending { it.h }) {
            if (summit.rejected) continue
            if (unfit.any { abs(it.x - summit.x) <= MERGE && abs(it.z - summit.z) <= MERGE }) {
                summit.rejected = true
                continue
            }
            if (summit.h < holdBelow) break
            if (!DragonNest.loaded(level, summit.x, summit.z, READY)) {
                survey.waiting = true
                holdBelow = max(holdBelow, summit.h - SLACK)
                continue
            }
            if (!summit.refined && !refine(level, summit)) {
                summit.rejected = true
                continue
            }
            val clearPeak = holdBelow == Int.MIN_VALUE && summit.h >= CLEAR_PEAK && summit.h >= survey.mean + CLEAR_OVER && survey.count >= CLEAR_CHUNKS
            if (summit.h < PEAK && !clearPeak) continue
            if (!suits(level, summit)) {
                summit.rejected = true
                continue
            }
            mark(survey, summit)
            return
        }
    }

    /** Marks a roost on [summit], and the cell as settled. */
    private fun mark(survey: Survey, summit: Summit) {
        val roost = Roost(nextId(), BlockPos(summit.x, summit.h, summit.z), tier = QuestGenerator.tierAt(summit.x, summit.z))
        roosts.add(roost)
        val key = key(survey.cx, survey.cz)
        decided.add(key)
        surveys.remove(key)
        setDirty()
        Guildmark.LOGGER.info("A dragon's roost is marked on the summit at {}, {}, {} (roost {})", summit.x, summit.h, summit.z, roost.id)
    }

    /**
     * Moves [summit] to the top of the hill it's on, by hill-climbing the real ground (trees stepped off) in steps of
     * 4, 2 and 1 blocks, and takes its true height. False if its ground can't be read.
     */
    private fun refine(level: ServerLevel, summit: Summit): Boolean {
        var x = summit.x
        var z = summit.z
        var y = DragonNest.ground(level, x, z) ?: return false
        for (step in intArrayOf(4, 2, 1)) {
            var moved = true
            var moves = 0
            while (moved && moves++ < 24) {
                moved = false
                var bestX = x
                var bestZ = z
                var best = y
                for (dz in -step..step step step) for (dx in -step..step step step) {
                    if (dx == 0 && dz == 0) continue
                    val g = DragonNest.ground(level, x + dx, z + dz) ?: continue
                    if (g > best) {
                        best = g
                        bestX = x + dx
                        bestZ = z + dz
                    }
                }
                if (best > y) {
                    x = bestX
                    z = bestZ
                    y = best
                    moved = true
                }
            }
        }
        summit.x = x
        summit.z = z
        summit.h = y
        summit.refined = true
        return true
    }

    /** Whether a dragon might nest on [summit]: tier 2 land, steep, dry, on rock or earth, and apart from everything else. */
    private fun suits(level: ServerLevel, summit: Summit): Boolean {
        if (QuestGenerator.tierAt(summit.x, summit.z) < MIN_TIER) return false
        val pos = BlockPos(summit.x, summit.h, summit.z)
        if (relief(level, summit) < RELIEF || DragonNest.wet(level, pos)) return false
        val top = level.getBlockState(pos)
        if (top.`is`(Blocks.POWDER_SNOW) || !top.fluidState.isEmpty) return false
        return apart(level, pos, null)
    }

    /** How far [summit] stands over the lowest ground in a ring of eight points at 16, 32 and 48 blocks out. */
    private fun relief(level: ServerLevel, summit: Summit): Int {
        var low = summit.h
        for (radius in intArrayOf(16, 32, 48)) for (k in 0 until 8) {
            val angle = k * PI / 4
            val g = DragonNest.ground(level, summit.x + (cos(angle) * radius).roundToInt(), summit.z + (sin(angle) * radius).roundToInt()) ?: continue
            low = min(low, g)
        }
        return summit.h - low
    }

    /** Whether [pos] is well away from other roosts, lairs, camps, the Hollow Throne, villages and capitals ([except] aside). */
    private fun apart(level: ServerLevel, pos: BlockPos, except: Roost?): Boolean {
        val x = pos.x + 0.5
        val z = pos.z + 0.5
        if (roosts.any { it !== except && horizontal(x, z, it.pos) < ROOST_GAP }) return false
        if (Lairs.get(level).lairs.any { horizontal(x, z, it.pos) < LAIR_GAP }) return false
        if (FieldBosses.get(level).sites.any { it.built && horizontal(x, z, it.pos) < CAMP_GAP }) return false
        val altar = HollowThrone.get(level).altar.orElse(null)
        if (altar != null && horizontal(x, z, altar) < THRONE_GAP) return false
        return !villageNear(level, pos) && !capitalNear(level, pos)
    }

    /** Whether a village bell stands within [VILLAGE_GAP] of [pos], in the land that's loaded (looking further would read it from disk). */
    private fun villageNear(level: ServerLevel, pos: BlockPos): Boolean {
        for (cx in ((pos.x - VILLAGE_GAP) shr 4)..((pos.x + VILLAGE_GAP) shr 4)) for (cz in ((pos.z - VILLAGE_GAP) shr 4)..((pos.z + VILLAGE_GAP) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) continue
            val found = level.poiManager.getInChunk({ it.`is`(PoiTypes.MEETING) }, ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
                .anyMatch { horizontal(pos.x + 0.5, pos.z + 0.5, it.pos) < VILLAGE_GAP }
            if (found) return true
        }
        return false
    }

    /** Whether a capital's walls and clearing, and [CAPITAL_GAP] beyond, reach [pos]; only capitals whose start chunk is loaded are looked at. */
    private fun capitalNear(level: ServerLevel, pos: BlockPos): Boolean {
        if (!Capitals.enabled()) return false
        val reach = CityPlan.CLEAR + CAPITAL_GAP
        return Capitals.around(level, pos.x - reach, pos.z - reach, pos.x + reach, pos.z + reach) { x, z ->
            level.chunkSource.getChunkNow(x shr 4, z shr 4) != null
        }.any { abs(pos.x - it.center.x) <= reach && abs(pos.z - it.center.z) <= reach }
    }

    // ---- Nests ----

    /**
     * Builds [roost]'s nest once the land round it is loaded, or gives the roost up if the summit no longer suits
     * (something has been built there, it's wet, a village has turned up). True if it built.
     */
    private fun raise(level: ServerLevel, roost: Roost): Boolean {
        val x = roost.pos.x
        val z = roost.pos.z
        if (!DragonNest.loaded(level, x, z, DragonNest.SPAN + 1)) return false
        val plan = DragonNest.plan(level, x, z)
        if (plan == null || !apart(level, roost.pos, roost)) {
            Guildmark.LOGGER.info("Dragon roost {} at {}, {} is given up: the summit doesn't suit a nest", roost.id, x, z)
            roosts.remove(roost)
            // Its cell may take another summit, but never this one again
            unfit.add(roost.pos)
            decided.remove(key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)))
            setDirty()
            return false
        }
        return build(level, roost, plan)
    }

    /** Builds [roost]'s nest to [plan], raises its Wardfire and sets its dragon on it. False (and the roost gone) if the building failed. */
    private fun build(level: ServerLevel, roost: Roost, plan: DragonNest.Plan): Boolean {
        try {
            DragonNest.build(level, plan, level.seed xor (plan.x * 3129871L) xor (plan.z * 116129781L) xor SALT)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.error("Dragon roost {} couldn't be built, so it is given up", roost.id, e)
            roosts.remove(roost)
            setDirty()
            return false
        }
        roost.pos = plan.feet
        roost.facing = plan.facing.get2DDataValue()
        roost.built = true
        setDirty()
        raiseFire(level, roost)
        spawn(level, roost)
        return true
    }

    /**
     * A Wardfire a little way below [roost]'s nest, a base camp for whoever comes for its dragon: 28 to 52 blocks off
     * and 4 to 60 lower, on the first spot that suits one, unless one stands near. Steep land has little flat ground,
     * so many spots are tried, and if none is flat enough a bench is cut for it on the first slope that can take one.
     */
    private fun raiseFire(level: ServerLevel, roost: Roost) {
        if (!WardfireSites.enabled() || WardfireSites.near(level, roost.pos, FIRE_MAX)) return
        val random = level.random
        val slopes = ArrayList<BlockPos>()
        repeat(FIRE_TRIES) {
            val angle = random.nextDouble() * PI * 2
            val distance = FIRE_MIN + random.nextInt(FIRE_MAX - FIRE_MIN + 1)
            val x = roost.pos.x + (cos(angle) * distance).roundToInt()
            val z = roost.pos.z + (sin(angle) * distance).roundToInt()
            if (!DragonNest.loaded(level, x, z, 6)) return@repeat
            val ground = DragonNest.ground(level, x, z) ?: return@repeat
            if (ground > roost.pos.y - FIRE_DROP_MIN || ground < roost.pos.y - FIRE_DROP_MAX) return@repeat
            val spot = BlockPos(x, ground + 1, z)
            if (!WardfireSites.fits(level, spot)) {
                slopes.add(spot)
                return@repeat
            }
            WardfireSites.build(level, spot, random)
            return
        }
        for (spot in slopes) {
            if (!DragonNest.bench(level, spot)) continue
            if (WardfireSites.fits(level, spot)) WardfireSites.build(level, spot, random)
            return
        }
    }

    // ---- The dragons ----

    /**
     * Keeps [roost]: its hoard waits until its land loads, a slain dragon is replaced a week of days after, and a
     * dragon that has gone missing is replaced too. Only while a player is near and the nest's land is loaded.
     */
    private fun keep(level: ServerLevel, roost: Roost, players: List<ServerPlayer>) {
        val feet = roost.pos
        if (players.none { horizontal(it.x, it.z, feet) < ACTIVE_RANGE } || !DragonNest.loaded(level, feet.x, feet.z, DragonNest.SPAN)) return
        if (roost.slain) {
            // Its hoard is revealed first
            if (roost.hoardAt >= 0) return
            val day = VillageBoards.day(level)
            if (day < roost.slainDay) roost.slainDay = day
            if (day - roost.slainDay < REGROW_DAYS) return
            roost.slain = false
            roost.dragon = null
            roost.missing = 0
            DragonNest.cover(level, feet, roost.direction)
            setDirty()
        }
        // Whatever dragon of this roost is about is the one: never a second
        val present = dwellers(level, roost)
        if (present.isNotEmpty()) {
            val keeper = present.firstOrNull { it.uuid == roost.dragon } ?: present[0]
            if (roost.dragon != keeper.uuid) {
                roost.dragon = keeper.uuid
                roost.name = keeper.nameIndex
                setDirty()
            }
            for (extra in present) if (extra !== keeper) extra.discard()
            roost.missing = 0
            return
        }
        if (roost.dragon != null) {
            // It was here once: only gone if everywhere it could be is in view, and for a few seconds running
            if (!inView(level, feet) || ++roost.missing < GRACE) return
            roost.dragon = null
            setDirty()
        }
        spawn(level, roost)
    }

    /** The living dragons of [roost] in the land near its nest. */
    private fun dwellers(level: ServerLevel, roost: Roost): List<Dragon> {
        val box = AABB(
            (roost.pos.x - SEEK).toDouble(), level.minY.toDouble(), (roost.pos.z - SEEK).toDouble(),
            (roost.pos.x + SEEK).toDouble(), level.maxY.toDouble(), (roost.pos.z + SEEK).toDouble(),
        )
        return level.getEntitiesOfClass(Dragon::class.java, box) { it.roost == roost.id && it.isAlive }
    }

    /** Whether the nest is where the world is running and the land all round it (as far as a dragon strays) is loaded. */
    private fun inView(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.isPositionEntityTicking(pos)) return false
        for (k in 0 until 8) {
            val angle = k * PI / 4
            val x = pos.x + (cos(angle) * SEEK).roundToInt()
            val z = pos.z + (sin(angle) * SEEK).roundToInt()
            if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) return false
        }
        return true
    }

    /** Sets a new dragon, asleep if it's night, on [roost]'s nest; only where the world is running and there's room. */
    private fun spawn(level: ServerLevel, roost: Roost): Dragon? {
        val feet = roost.pos
        if (!level.isPositionEntityTicking(feet)) return null
        val dragon = DragonRegistry.DRAGON.get().create(level, EntitySpawnReason.EVENT) ?: return null
        val yaw = roost.direction.toYRot()
        dragon.snapTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, yaw, 0f)
        if (!level.noCollision(dragon)) return null
        dragon.yBodyRot = yaw
        dragon.yHeadRot = yaw
        dragon.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), EntitySpawnReason.EVENT, null)
        dragon.settleAt(feet, yaw, !level.isBrightOutside)
        dragon.scaleTo(roost.tier)
        dragon.roost = roost.id
        level.addFreshEntity(dragon)
        roost.dragon = dragon.uuid
        roost.name = dragon.nameIndex
        roost.missing = 0
        setDirty()
        return dragon
    }

    /** A dragon fell: its roost is marked slain, its hoard is to be revealed in its nest, and the guild halls hear of it. */
    fun onSlain(level: ServerLevel, dragon: Dragon) {
        val roost = roost(dragon.roost)
        val tier = roost?.tier ?: QuestGenerator.tierAt(dragon.blockX, dragon.blockZ)
        val drops = level.gameRules.get(GameRules.MOB_DROPS)
        if (roost != null) {
            roost.slain = true
            roost.slainDay = VillageBoards.day(level)
            roost.dragon = null
            roost.missing = 0
            // After it has crashed down and crumbled away
            if (drops) roost.hoardAt = level.gameTime + Dragon.DEATH_TICKS + HOARD_DELAY
            setDirty()
        } else if (drops) {
            for (stack in DragonNest.loot(level, tier).flatten()) dragon.spawnAtLocation(level, stack)
        }
        val at = roost?.pos ?: dragon.blockPosition()
        val heroes = level.players().filter { it.isAlive && it.distanceToSqr(dragon) < HERO_RANGE * HERO_RANGE }
        for (player in heroes) {
            player.sendSystemMessage(
                when {
                    !drops -> Component.translatable("message.guildmark.dragon_slain_bare", dragon.title)
                    roost != null -> Component.translatable("message.guildmark.dragon_slain", dragon.title, at.x, at.z)
                    else -> Component.translatable("message.guildmark.dragon_slain_here", dragon.title)
                },
            )
        }
        if (roost != null) BossHunts.onSlain(heroes, BossHunts.DRAGON, roost.id)
        GuildNews.add(level, "news.guildmark.dragon_slain", heroes.joinToString(", ") { it.name.string }.ifEmpty { "?" },
            "#${Dragon.NAME_KEY}.${dragon.nameIndex}", at.x.toString(), at.z.toString())
    }

    /** Reveals [roost]'s hoard in its nest once the land there is loaded. */
    private fun reveal(level: ServerLevel, roost: Roost) {
        val feet = roost.pos
        if (!DragonNest.loaded(level, feet.x, feet.z, REVEAL_REACH)) return
        roost.hoardAt = -1
        setDirty()
        DragonNest.hoard(level, feet, roost.direction, roost.tier)
    }

    // ---- For testing ----

    /**
     * Forces a roost on the best summit within about 100 blocks of [player] whose land is loaded and suits a nest (tier,
     * height and the like aside), builds it at once and sets its dragon on it. Returns where the dragon lies, or null.
     */
    fun createNear(level: ServerLevel, player: ServerPlayer): BlockPos? {
        if (level.dimension() != Level.OVERWORLD || level.difficulty == Difficulty.PEACEFUL) return null
        val peaks = ArrayList<Summit>()
        for (dz in -TEST_REACH..TEST_REACH step TEST_STEP) for (dx in -TEST_REACH..TEST_REACH step TEST_STEP) {
            val x = player.blockX + dx
            val z = player.blockZ + dz
            val h = DragonNest.ground(level, x, z) ?: continue
            peaks.add(Summit(x, z, h))
        }
        peaks.sortByDescending { it.h }
        val tried = ArrayList<Summit>()
        for (peak in peaks) {
            if (tried.size >= TEST_TRIES) break
            if (tried.any { abs(it.x - peak.x) < TEST_APART && abs(it.z - peak.z) < TEST_APART }) continue
            tried.add(peak)
            if (!refine(level, peak) || roosts.any { horizontal(peak.x + 0.5, peak.z + 0.5, it.pos) < TEST_GAP }) continue
            if (!DragonNest.loaded(level, peak.x, peak.z, DragonNest.SPAN + 1)) continue
            val plan = DragonNest.plan(level, peak.x, peak.z) ?: continue
            val roost = Roost(nextId(), plan.feet, tier = QuestGenerator.tierAt(peak.x, peak.z))
            roosts.add(roost)
            decided.add(key(Math.floorDiv(peak.x, CELL), Math.floorDiv(peak.z, CELL)))
            setDirty()
            return if (build(level, roost, plan)) roost.pos else null
        }
        return null
    }

    /** Forces a dragon, awake, a few blocks in front of [player] with its home there and no roost; null if there's no room. */
    fun spawnHere(level: ServerLevel, player: ServerPlayer): Dragon? {
        if (level.difficulty == Difficulty.PEACEFUL) return null
        val dragon = DragonRegistry.DRAGON.get().create(level, EntitySpawnReason.COMMAND) ?: return null
        // Facing the one who called it
        val yaw = player.yRot + 180f
        for (distance in intArrayOf(7, 9, 5, 11, 3)) for (turn in intArrayOf(0, 35, -35, 70, -70)) {
            val angle = Math.toRadians((player.yRot + turn).toDouble())
            val x = player.x - sin(angle) * distance
            val z = player.z + cos(angle) * distance
            for (dy in 3 downTo -6) {
                dragon.snapTo(x, floor(player.y) + dy, z, yaw, 0f)
                // On the ground: room for it here, and none a hair lower
                if (!level.noCollision(dragon) || level.noCollision(dragon, dragon.boundingBox.move(0.0, -0.1, 0.0))) continue
                val pos = dragon.blockPosition()
                dragon.yBodyRot = yaw
                dragon.yHeadRot = yaw
                dragon.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.COMMAND, null)
                dragon.settleAt(pos, yaw, false)
                dragon.scaleTo(QuestGenerator.tierAt(pos.x, pos.z))
                level.addFreshEntity(dragon)
                return dragon
            }
        }
        return null
    }

    companion object {
        /** The side of a cell, in blocks and in chunks; each holds at most one roost. */
        private const val CELL = 768
        private const val CELL_CHUNKS = CELL / 16
        /** The chance a cell may hold a roost. */
        private const val CHANCE = 1f / 3f
        private const val SALT = 0x5D2A6E91L
        /** The least land tier a roost may stand on. */
        private const val MIN_TIER = 2

        /** How near a player is to a cell before it's looked over, and how many chunks are read in a second. */
        private const val SURVEY_RANGE = 200
        private const val SAMPLE_BUDGET = 96
        /** The lowest coarse height worth noting as a high point; high points within [MERGE] blocks are one; only [KEEP_TOPS] are kept. */
        private const val MIN_SEEN = 96
        private const val MERGE = 40
        private const val KEEP_TOPS = 24
        /** A summit must be this high, or [CLEAR_PEAK] and [CLEAR_OVER] over the cell's average with [CLEAR_CHUNKS] of its chunks seen. */
        private const val PEAK = 140
        private const val CLEAR_PEAK = 104
        private const val CLEAR_OVER = 40
        private const val CLEAR_CHUNKS = 280
        /** The least it must stand over the ground within 48 blocks. */
        private const val RELIEF = 30
        /** The land this far round a summit must be loaded before it's judged; a higher summit still waiting for its land holds back any more than [SLACK] lower. */
        private const val READY = 64
        private const val SLACK = 16

        /** How near a player comes before a nest is built and kept, in blocks. */
        private const val BUILD_RANGE = 128.0
        private const val ACTIVE_RANGE = 128.0
        /** How far from other things a roost keeps, in blocks. */
        private const val ROOST_GAP = 300.0
        private const val LAIR_GAP = 96.0
        private const val CAMP_GAP = 96.0
        private const val THRONE_GAP = 192.0
        private const val VILLAGE_GAP = 128
        private const val CAPITAL_GAP = 96
        /** How far from a nest its Wardfire stands, and how far below the nest. */
        private const val FIRE_MIN = 28
        private const val FIRE_MAX = 52
        private const val FIRE_DROP_MIN = 4
        private const val FIRE_DROP_MAX = 60
        private const val FIRE_TRIES = 120

        /** Days from a dragon's slaying to the next one's settling. */
        private const val REGROW_DAYS = 7
        /** Seconds in a row a dragon must be missing, with all the land round in view, before it's replaced. */
        private const val GRACE = 5
        /** How far from its nest a dragon is looked for, in blocks (it strays 112 at most). */
        private const val SEEK = 140
        /** Ticks after the dragon has gone before its hoard appears, and how much land round the nest must be loaded for it. */
        private const val HOARD_DELAY = 40
        private const val REVEAL_REACH = 8
        private const val HERO_RANGE = 80.0

        // For the testing commands
        private const val TEST_REACH = 96
        private const val TEST_STEP = 6
        private const val TEST_TRIES = 12
        private const val TEST_APART = 24
        private const val TEST_GAP = 48.0

        /** Whether dragons take new roosts and come back to empty ones (the dragons setting). */
        fun enabled(): Boolean = try {
            GuildmarkConfig.DRAGONS.get()
        } catch (e: IllegalStateException) {
            true
        }

        private fun horizontal(x: Double, z: Double, pos: BlockPos): Double {
            val dx = x - (pos.x + 0.5)
            val dz = z - (pos.z + 0.5)
            return sqrt(dx * dx + dz * dz)
        }

        private val CODEC: Codec<DragonRoosts> = RecordCodecBuilder.create { i ->
            i.group(
                Roost.CODEC.listOf().optionalFieldOf("roosts", listOf()).forGetter { it.roosts },
                Codec.LONG.listOf().optionalFieldOf("decided", listOf()).forGetter { it.decided.toList() },
                Codec.INT.optionalFieldOf("counter", 0).forGetter { it.counter },
            ).apply(i) { roosts, decided, counter -> DragonRoosts(roosts.toMutableList(), decided.toHashSet(), counter) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dragon_roosts"), Supplier { DragonRoosts() }, CODEC)

        /** Roosts are kept with the overworld, whichever level asks. */
        fun get(level: ServerLevel): DragonRoosts = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
