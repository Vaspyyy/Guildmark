package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.streets.Dressing
import io.github.vaspyyy.guildmark.townsfolk.streets.Dressing.Kind
import io.github.vaspyyy.guildmark.townsfolk.streets.Dressing.Piece
import io.github.vaspyyy.guildmark.townsfolk.streets.Streets
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A village's good times shown on its green (living-world/villages/design.md 10.4, part V8b): after two market days of GOOD times
 * its folk go round with ladders and dress it, and two market days after the times fall back below good they take it down again;
 * in thriving times lantern strings go up between the lamp posts too, lit as the lamps are lit. It is the capital's dressing
 * ([Dressing]: the same pieces, the same rounds of residents up ladders by day, the same child who points and cheers, and the same
 * record of what stood where, under a site key of the village's, so whatever happens it is all put back), looked at for a village
 * once a couple of seconds while a player is within 96 blocks of its bell, with its pieces laid out here from its places:
 *
 * - a garland over the door of each of the [HOMES] homes nearest the bell, from a ladder set beside it, and a flower pot on the step;
 * - pennants from the bell to up to [PENNANTS] lamp posts within [PENNANT_REACH] blocks of it, spread round the green, each tied on
 *   by a resident up a ladder against the lamp post;
 * - in thriving times, lantern strings from lamp post to lamp post, none longer than [STRING_MAX] blocks, [STRINGS] at most, lit with
 *   the lamps.
 *
 * Where a home has nobody living in it, its door is left bare. What the times are, and so when it all goes up, comes from the village's
 * fortunes (the same days-of-good-times rule as a capital's, [Dressing.wanted]); a village without a market has none, so the dressing
 * waits on them, and `village dress` ([VillageDressCommand]) puts it up or takes it down whatever they say, for the session.
 *
 * A village whose record has no folk of its own (Village Folk off, or not adopted) is not dressed by the times: there is nobody to
 * hang it. Nor is one whose times are off (Village Markets or Village Fortunes off: every village reads steady, [times]). With Living Villages or Village Dressing off, none is put up and what is up is taken down as the pieces' chunks load
 * ([allows]). A capital's dressing is not touched.
 */
object VillageDressing {
    /** Whether the Village Dressing switch is on; on until the settings are loaded. */
    fun on(): Boolean = try {
        GuildmarkConfig.VILLAGE_DRESSING.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** Whether a village is dressed at all: Living Villages and Village Dressing both on. */
    fun working(): Boolean = Villages.enabled() && on()

    /** At setup: the village's dressing looked at, its command and its line of `village info`. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        VillageDressCommand.init()
    }

    // ---- Whether a column's dressing is a village's, and wanted ----

    private var byColumn: Map<Long, Long> = mapOf()
    private var counted = -1
    private var builtAt = Long.MIN_VALUE / 2

    /**
     * The village whose bell is in [column], if one is. The records' columns are looked up afresh whenever their number changes,
     * and in any case every [RECHECK] ticks, so a record made or forgotten is known within seconds.
     */
    fun recOf(level: ServerLevel, column: Long): VillageRec? {
        val records = VillageData.get(level).records
        val now = level.gameTime
        if (records.size != counted || now - builtAt !in 0..RECHECK) {
            counted = records.size
            builtAt = now
            byColumn = records.keys.associateBy { Streets.column(it) }
        }
        return byColumn[column]?.let { records[it] }
    }

    /**
     * Whether the dressing about [column] may stand: always for a capital's (not a village's column), for a village's while Living Villages
     * and Village Dressing are on and the village has not been given up. Asked wherever the streets ask whether a piece is wanted.
     */
    fun allows(level: ServerLevel, column: Long): Boolean {
        val rec = recOf(level, column) ?: return true
        return working() && rec.state != Live.LOST
    }

    // ---- Once in a while, a live village ----

    /** The places each village's pieces were last laid out from, and the villages counted afresh since whose pieces await a round's end to be laid again. */
    private val laid = HashMap<Long, TownPlaces>()
    private val stale = HashSet<Long>()
    private var warned = false

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        if (level.gameTime % LOOK != PHASE) return
        if (!working()) return
        val now = level.gameTime
        for (rec in VillageLamps.live(level)) {
            val places = Villages.places(level, rec.key) ?: continue
            try {
                look(level, rec, places, now)
            } catch (e: RuntimeException) {
                if (!warned) Guildmark.LOGGER.warn("The dressing of the village at {} went wrong", BlockPos.of(rec.key), e)
                warned = true
            }
        }
    }

    /** [rec]'s dressing seen to: laid out from its places (again when they were counted afresh), worked out from its times, its pieces seen to. */
    private fun look(level: ServerLevel, rec: VillageRec, places: TownPlaces, now: Long) {
        val column = Streets.column(rec.key)
        val before = laid.put(column, places)
        if (before != null && before !== places) stale += column
        if (column in stale && Dressing.relay(column)) stale -= column
        val folk = VillageLamps.folk(level, rec.key)
        Dressing.look(level, column, places.centre, now, may = folk && times(), hands = folk) { lay(level, places, column) }
    }

    /** Whether a village has good times and hard to be dressed for: its market, and Village Fortunes, on (off, every village reads steady). */
    fun times(): Boolean = VillageMarkets.fortunes()

    fun forget() {
        laid.clear()
        stale.clear()
        byColumn = mapOf()
        counted = -1
        builtAt = Long.MIN_VALUE / 2
        warned = false
    }

    // ---- Laying it out ----

    /**
     * The pieces of the dressing of the town with these [places] ([column]'s): null if its bell's ground is not loaded yet. Worked
     * out from the places alone and what stands there now: a door with no room for a ladder beside it is passed over for the next
     * nearest home, a lamp post that cannot be climbed is left out. The dressing's own pieces standing there already (it is up, and
     * is laid out again after a restart or a count) are no hindrance: each is laid out where it stands.
     */
    internal fun lay(level: ServerLevel, places: TownPlaces, column: Long): List<Piece>? {
        val bell = places.bell
        if (!level.isLoaded(bell)) return null
        val g = Ground(level, column)
        val out = ArrayList<Piece>()
        homes(g, places, out)
        val posts = posts(level, places)
        pennants(g, bell, posts, out)
        lanterns(g, bell, posts, out)
        return out
    }

    /** The garlands over, and the pots by, the doors of the [HOMES] homes nearest the bell. */
    private fun homes(g: Ground, places: TownPlaces, out: MutableList<Piece>) {
        val bell = places.bell
        val doors = places.lodgings.values
            .filter { !it.farm && it.beds.isNotEmpty() }
            .mapNotNull { l -> l.front?.takeIf { it.inward.axis.isHorizontal }?.let { l to it } }
            .sortedBy { (_, d) -> d.outside.distSqr(bell) }
        var n = 0
        for ((l, d) in doors) {
            if (n >= HOMES) break
            if (door(g, l, d, out)) n++
        }
    }

    /** One home's garland and pot; false (and nothing) if its door has no room for them. */
    private fun door(g: Ground, l: CityPlaces.Lodging, d: CityPlaces.Door, out: MutableList<Piece>): Boolean {
        val level = g.level
        val outside = d.outside
        if (!level.isLoaded(outside) || !g.free(outside) || !g.free(outside.above())) return false
        val inward = d.inward
        val outward = inward.opposite
        val garland = outside.above(2)
        if (!g.open(garland) || level.getBlockState(garland.relative(inward)).isAir) return false
        for (side in listOf(inward.clockWise, inward.counterClockWise)) {
            val ladder = outside.relative(side)
            if (!g.ladderOk(ladder, outward, 1) || !g.standable(ladder.relative(outward))) continue
            val seed = d.pos.hashCode()
            val tie = Vec3(0.5 + inward.stepX * Dressing.TIE_IN - side.stepX * Dressing.GARLAND_HALF, 0.32, 0.5 + inward.stepZ * Dressing.TIE_IN - side.stepZ * Dressing.GARLAND_HALF)
            val span = Vec3(side.stepX * Dressing.GARLAND_HALF * 2, 0.0, side.stepZ * Dressing.GARLAND_HALF * 2)
            out += Piece(
                Kind.GARLAND, garland, l.id, ladder.relative(outward), ladder, outward, 1,
                Vec3.atLowerCornerOf(garland).add(tie).add(span.scale(0.5)).add(0.0, -0.2, 0.0), tie, span, null, seed,
            )
            // The doorstep's pot, on the other side of the door from the ladder
            val pot = outside.relative(side.opposite)
            if (g.free(pot) && Places.floor(level, pot.below())) {
                out += Piece(Kind.POT, pot, l.id, outside, null, side.opposite, 0, Vec3.atCenterOf(pot).add(0.0, -0.3, 0.0), null, null, null, seed * 31 + 7)
            }
            return true
        }
        return false
    }

    /** A lamp post that can be climbed: the lamp, the fence below it (its lowest block [ground]), the ladder's rise, and where it lies from the bell. */
    private class Post(val lamp: BlockPos, val ground: BlockPos, val rise: Int, val angle: Double, val range: Double)

    /** The lamp posts within [REACH] of the bell, nearest first, whose ground is loaded and whose post is two to four fences tall. */
    private fun posts(level: ServerLevel, places: TownPlaces): List<Post> {
        val bell = places.bell
        val out = ArrayList<Post>()
        for (lamp in places.lamps) {
            val dx = (lamp.x - bell.x).toDouble()
            val dz = (lamp.z - bell.z).toDouble()
            val range = sqrt(dx * dx + dz * dz)
            if (range > REACH || !level.isLoaded(lamp)) continue
            var h = 0
            while (h <= MAX_POST && post(level.getBlockState(lamp.below(h + 1)))) h++
            if (h < 2 || h > MAX_POST) continue
            out += Post(lamp, lamp.below(h), h, atan2(dz, dx), range)
        }
        return out.sortedBy { it.range }
    }

    private fun post(state: BlockState): Boolean = state.`is`(BlockTags.FENCES) || state.`is`(BlockTags.WALLS)

    /** Pennants from the bell to up to [PENNANTS] posts within [PENNANT_REACH], spread round it where they can be; each tied on at the post. */
    private fun pennants(g: Ground, bell: BlockPos, posts: List<Post>, out: MutableList<Piece>) {
        val end = Vec3(bell.x + 0.5, bell.y + 0.6, bell.z + 0.5)
        val chosen = ArrayList<Post>()
        for (apart in listOf(SPREAD, 0.0)) {
            for (post in posts) {
                if (chosen.size >= PENNANTS || post.range > PENNANT_REACH) break
                if (post in chosen || chosen.any { turn(it.angle, post.angle) < apart }) continue
                out += pennant(g, bell, post, end) ?: continue
                chosen += post
            }
        }
    }

    /**
     * The pennant tied to [post]'s side towards the bell, from a ladder against the post on that side (a block short of the lamp, so that the
     * block the pennant is tied in is free), running to [end] at the bell.
     */
    private fun pennant(g: Ground, bell: BlockPos, post: Post, end: Vec3): Piece? {
        val lamp = post.lamp
        val toBell = way(bell.x - lamp.x, bell.z - lamp.z)
        val pos = lamp.relative(toBell)
        if (!g.open(pos)) return null
        val rise = post.rise - 1
        val ladder = post.ground.relative(toBell)
        if (rise < 1 || !g.ladderOk(ladder, toBell, rise) || !g.standable(ladder.relative(toBell))) return null
        val into = toBell.opposite
        val start = Vec3(pos.x + 0.5 + into.stepX * LAMP_TIE, pos.y + 0.6, pos.z + 0.5 + into.stepZ * LAMP_TIE)
        val span = end.subtract(start)
        if (span.length() !in 2.0..Dressing.LONGEST) return null
        return Piece(
            Kind.BUNTING, pos, -1, ladder.relative(toBell), ladder, toBell, rise, start.add(0.0, -0.1, 0.0),
            start.subtract(Vec3.atLowerCornerOf(pos)), span, null, pos.hashCode() * 17 + 3,
        )
    }

    /** Lantern strings from post to post: each post (in turn round the green) to the nearest it is not strung to yet, within [STRING_MAX], [STRINGS] at most. */
    private fun lanterns(g: Ground, bell: BlockPos, posts: List<Post>, out: MutableList<Piece>) {
        val strung = HashSet<Pair<BlockPos, BlockPos>>()
        var n = 0
        for (a in posts.sortedBy { it.angle }) {
            if (n >= STRINGS) break
            val b = posts.filter { it !== a && Pair(a.lamp, it.lamp) !in strung && Pair(it.lamp, a.lamp) !in strung }
                .filter { dist(a.lamp, it.lamp) in 2.0..STRING_MAX }
                .minByOrNull { dist(a.lamp, it.lamp) } ?: continue
            val top = a.lamp.above()
            if (!g.open(top)) continue
            // The ladder on the post's far side from the bell, where the pennant's is not
            val toBell = way(bell.x - a.lamp.x, bell.z - a.lamp.z)
            val faces = listOf(toBell.opposite, toBell.clockWise, toBell.counterClockWise, toBell)
            val up = faces.firstNotNullOfOrNull { f ->
                val ladder = a.ground.relative(f)
                if (g.ladderOk(ladder, f, a.rise) && g.standable(ladder.relative(f))) ladder to f else null
            } ?: continue
            val span = Vec3(b.lamp.x - a.lamp.x.toDouble(), b.lamp.y - a.lamp.y.toDouble(), b.lamp.z - a.lamp.z.toDouble())
            out += Piece(
                Kind.LANTERNS, top, -1, up.first.relative(up.second), up.first, up.second, a.rise,
                Vec3(top.x + 0.5, top.y + 0.1, top.z + 0.5), Vec3(0.5, 0.1, 0.5), span, a.lamp, a.lamp.hashCode(),
            )
            strung += Pair(a.lamp, b.lamp)
            n++
        }
    }

    // ---- What the ground allows ----

    /** The way to step from a post towards the bell, [dx] and [dz] away. */
    private fun way(dx: Int, dz: Int): Direction =
        if (abs(dx) >= abs(dz)) (if (dx > 0) Direction.EAST else Direction.WEST) else (if (dz > 0) Direction.SOUTH else Direction.NORTH)

    /** How far apart two bearings are (0 to pi). */
    private fun turn(a: Double, b: Double): Double {
        val d = abs(a - b) % (2 * Math.PI)
        return if (d > Math.PI) 2 * Math.PI - d else d
    }

    private fun dist(a: BlockPos, b: BlockPos): Double =
        sqrt((a.x - b.x).toDouble() * (a.x - b.x) + (a.y - b.y).toDouble() * (a.y - b.y) + (a.z - b.z).toDouble() * (a.z - b.z))

    /** The ground about a village's green as [column]'s dressing finds it: what is in the way of a piece or a ladder, and what is not. */
    private class Ground(val level: ServerLevel, val column: Long) {
        /** Whether [pos] is one of this dressing's own pieces, standing there now. */
        fun ours(pos: BlockPos): Boolean = Streets.mine(level, pos, column, Streets.DRESSING)

        /** Whether [pos] is loaded and empty (or a plant that anything may build over, or a piece of this dressing's own). */
        fun open(pos: BlockPos): Boolean {
            if (!level.isLoaded(pos)) return false
            val state = level.getBlockState(pos)
            return (state.isAir || state.canBeReplaced()) && state.fluidState.isEmpty || ours(pos)
        }

        /** Whether a body fits at [pos]: loaded, with nothing to bump into (but a piece of this dressing's own). */
        fun free(pos: BlockPos): Boolean = level.isLoaded(pos) && (Places.free(level, pos) || ours(pos))

        /** Whether somebody can stand at [pos]: room for a body, and ground under it. */
        fun standable(pos: BlockPos): Boolean = free(pos) && free(pos.above()) && Places.floor(level, pos.below())

        /** Whether a ladder can stand from [bottom] up [rise] blocks facing [facing]: room all the way, and a wall (a post) behind it all the way. */
        fun ladderOk(bottom: BlockPos, facing: Direction, rise: Int): Boolean {
            for (k in 0..rise) {
                val pos = bottom.above(k)
                if (!open(pos) || level.getBlockState(pos.relative(facing.opposite)).isAir) return false
            }
            return true
        }
    }

    // ---- Numbers ----

    /** The homes dressed: the nearest to the bell. */
    const val HOMES = 8

    /** The pennants from the bell: how many posts at most, how far from the bell they may be, and how far apart round it they are first chosen. */
    const val PENNANTS = 4
    const val PENNANT_REACH = 14.0
    private const val SPREAD = Math.PI / 4

    /** The lantern strings: how many at most, how long the longest, and which posts they are looked for among (blocks from the bell). */
    const val STRINGS = 6
    const val STRING_MAX = 12.0
    private const val REACH = 28.0

    /** The tallest post a worker climbs, in fences below the lamp. */
    private const val MAX_POST = 4

    /** How far from the middle of the block beside a lamp a pennant is tied (towards the lamp). */
    private const val LAMP_TIE = 0.4

    /** The longest the villages' columns are known without being looked up again (ticks). */
    private const val RECHECK = 200L

    /** Ticks between looks at a live village's dressing, and the tick in them it is done. */
    private const val LOOK = 40L
    private const val PHASE = 13L
}
