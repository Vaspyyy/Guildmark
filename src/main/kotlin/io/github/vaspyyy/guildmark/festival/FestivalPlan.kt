package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.Dir
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.FarmlandBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where someone stands for the festival, and which way they face. */
data class Spot(val x: Double, val y: Double, val z: Double, val yaw: Float) {
    val pos: Vec3 get() = Vec3(x, y, z)
}

/**
 * One decoration: what goes where ([data] for an ornament), whether it's solid (so nobody may be standing there), and
 * which decorated post it's part of ([group], or -1), so a helper can be sent to put the post up.
 */
class Piece(val pos: BlockPos, val state: BlockState, val data: OrnamentData?, val solid: Boolean, val group: Int = -1)

/**
 * A festival's layout in one town, worked out once when it begins: the dance's ring round the centre (a capital's
 * fountain, or an open spot near a village's bell), where the band, the host and the crowd stand, and every
 * decoration in the order they go up (the centrepiece first, then the decorated posts round the square, the trim,
 * then what the day's contest needs). Each spot is checked against the world: decorations only go where there's room
 * (see [FestivalRestores.free]) and people only where they can stand.
 */
class FestivalPlan(
    val kind: FestivalKind,
    val capital: Boolean,
    /** The middle of the dance, at the height feet stand at. */
    val centre: Vec3,
    /** The dance, or null where there's no room for one. */
    val dance: FestivalDance?,
    val crowd: List<Spot>,
    val band: List<Spot>,
    val host: Spot,
    val pieces: List<Piece>,
    /** The centrepiece's crown, the decorated posts' tops, the hidden ribbons, the butts and the riddle lanterns. */
    val crown: BlockPos?,
    val posts: List<BlockPos>,
    val ribbons: List<BlockPos>,
    val targets: List<BlockPos>,
    val riddles: List<BlockPos>,
    /** Where rockets go up from at dusk. */
    val fireworks: List<Vec3>,
    /** How far from the centre the square of the festival reaches, where its crowd and its music are. */
    val square: Int,
    /** How far from the centre any of its decorations are. */
    val reach: Int,
    /** Where the plates stand on Sheafmoot's trestle for the Harvest Table (a capital's only; none where there's no room). */
    val trestle: List<BlockPos> = listOf(),
) {
    val floor: Int get() = floor(centre.y).toInt()
    val centrePos: BlockPos get() = BlockPos.containing(centre)

    companion object {
        /** A capital's festival, round the fountain in its plaza. */
        fun capital(level: ServerLevel, capital: CapitalPiece, kind: FestivalKind, seed: Long): FestivalPlan {
            val plan = capital.plan
            val fountain = capital.standing(plan, plan.fountain)
            val rim = plan.fountainRadius.toDouble()
            val survey = Survey(level)
            val floor = fountain.y
            val cx = fountain.x + 0.5
            val cz = fountain.z + 0.5
            val south = capital.direction(Dir.S)
            val bandAngle = atan2(south.stepZ.toDouble(), south.stepX.toDouble())

            // The ring runs between the rim (out to rim + 0.5) and the benches round the fountain (from rim + 2.5)
            val ring = rim + 1.6
            val reachIn = 0.6
            val dancers = if (survey.ringClear(cx, cz, floor, ring - reachIn - 0.35, ring + reachIn + 0.35)) 8 else 0
            val dance = if (dancers > 0) FestivalDance(kind, dancers, ring, reachIn, bandAngle + PI / dancers) else null

            val builder = Builder(level, survey, kind, cx, cz, floor)
            // The centrepiece rises out of the fountain's top bowl, clear of its water
            builder.pole(BlockPos(fountain.x, floor + 5, fountain.z), 7, ring, ribbons = dance != null)
            builder.posts(8, rim + 4.5, rim + 7.0, bandAngle)
            builder.rimTrim(fountain, plan.fountainRadius)
            val band = survey.band(cx, cz, floor, bandAngle, listOf(rim + 3.4, rim + 4.4, rim + 5.2), 3, builder.taken)
            builder.avoid(band)
            val host = survey.onRim(cx, cz, floor, bandAngle, rim) ?: survey.near(cx, cz, floor, bandAngle + 0.5, rim + 2.6, builder.taken)
                ?: Spot(cx, floor.toDouble(), cz + rim + 2.0, 0f)
            builder.avoid(listOf(host))
            builder.contest(seed, bandAngle, host, rim + 5.0, 10, 34)
            val crowd = survey.crowd(cx, cz, floor, listOf(rim + 3.5, rim + 4.6), bandAngle, 14, builder.taken)
            // Last, so the crowd and the rest stand just as they would without it: the Harvest Table's trestle
            if (kind == FestivalKind.SHEAFMOOT && io.github.vaspyyy.guildmark.cooking.Cooking.cookOff()) builder.trestle(host, crowd, dance)
            return builder.build(true, dance, crowd, band, host, square = (rim + 12).roundToInt(), around = ring + 3.0)
        }

        /**
         * A village's festival: the ring on the flattest open ground near the bell (as wide as the ground allows),
         * the band on the bell's side of it. With nowhere flat enough to dance, it's held round the bell itself.
         */
        fun village(level: ServerLevel, bell: BlockPos, kind: FestivalKind, seed: Long): FestivalPlan {
            val survey = Survey(level)
            val found = survey.villageRing(bell)
            val centre = found?.first ?: survey.groundNear(bell)
            val ring = found?.second ?: 0.0
            val floor = centre.y
            val cx = centre.x + 0.5
            val cz = centre.z + 0.5
            val toBell = if (found != null) atan2(bell.z + 0.5 - cz, bell.x + 0.5 - cx) else 0.0
            val reachIn = 0.9
            val dance = if (ring > 0) FestivalDance(kind, 6, ring, reachIn, toBell + PI / 6) else null

            val builder = Builder(level, survey, kind, cx, cz, floor)
            val inner = if (ring > 0) ring else 2.5
            if (found != null) {
                builder.pole(BlockPos(centre.x, floor, centre.z), 6, ring, ribbons = dance != null)
                if (ring >= 3.5) builder.baseTrim(centre)
            }
            builder.posts(6, inner + 3.0, inner + 5.5, toBell)
            val band = survey.band(cx, cz, floor, toBell, listOf(inner + 2.2, inner + 3.2), 2, builder.taken)
            builder.avoid(band)
            val host = survey.near(cx, cz, floor, toBell + 0.9, inner + 1.7, builder.taken) ?: survey.near(cx, cz, floor, toBell - 0.9, inner + 1.7, builder.taken)
                ?: Spot(cx + inner + 1.6, floor.toDouble(), cz, 90f)
            builder.avoid(listOf(host))
            builder.contest(seed, toBell, host, inner + 5.0, 8, 26)
            val crowd = survey.crowd(cx, cz, floor, listOf(inner + 2.3, inner + 3.1), toBell, 8, builder.taken)
            return builder.build(false, dance, crowd, band, host, square = (inner + 14).roundToInt(), around = inner + 2.0)
        }
    }

    /** Puts the decorations together, in the order they go up. */
    private class Builder(val level: ServerLevel, val survey: Survey, val kind: FestivalKind, val cx: Double, val cz: Double, val floor: Int) {
        val pieces = ArrayList<Piece>()
        val taken = ArrayList<Vec3>()
        var crown: BlockPos? = null
        val posts = ArrayList<BlockPos>()
        val ribbons = ArrayList<BlockPos>()
        val targets = ArrayList<BlockPos>()
        val riddles = ArrayList<BlockPos>()
        val fireworks = ArrayList<Vec3>()
        val trestle = ArrayList<BlockPos>()
        private val used = HashSet<Long>()

        private fun add(pos: BlockPos, state: BlockState, data: OrnamentData? = null, solid: Boolean = true, group: Int = -1): Boolean {
            if (!used.add(pos.asLong())) return false
            pieces.add(Piece(pos, state, data, solid, group))
            return true
        }

        private fun free(pos: BlockPos): Boolean = pos.asLong() !in used && FestivalRestores.free(level, pos, false)

        private val poleLook: TrimLook = when (kind) {
            FestivalKind.RIBBONWAKE -> TrimLook.POLE_BIRCH
            FestivalKind.LANTERNRISE -> TrimLook.POLE_SPRUCE
            else -> TrimLook.POLE_OAK
        }

        fun avoid(spots: List<Spot>) {
            for (s in spots) taken.add(s.pos)
        }

        /** The centrepiece: a pole [height] blocks tall from [base], its crown on top. */
        fun pole(base: BlockPos, height: Int, ring: Double, ribbons: Boolean) {
            val cells = (0 until height).map { base.above(it) }
            val top = base.above(height)
            if (cells.any { !free(it) } || !free(top)) return
            for (cell in cells) add(cell, FestivalTrimBlock.state(poleLook))
            val drop = top.y + 0.5 - floor
            val light = when (kind) {
                FestivalKind.LANTERNRISE -> 15
                FestivalKind.SUNHEARTH -> 13
                else -> 10
            }
            add(top, FestivalOrnamentBlock.state(light, false), OrnamentData(OrnamentKind.CROWN, kind, ring = if (ribbons) ring.toFloat() else 0f, drop = drop.toFloat()), solid = false)
            crown = top
            taken.add(Vec3(base.x + 0.5, floor.toDouble(), base.z + 0.5))
        }

        /**
         * [count] decorated posts round the centre between [near] and [far] blocks out, as evenly spread as the
         * ground lets them be, the band's side ([from]) between two of them: each a pole with a lantern, a banner on
         * every other one, a string of pennants up to the crown and a garland on to the next.
         */
        fun posts(count: Int, near: Double, far: Double, from: Double) {
            val found = ArrayList<BlockPos>()
            for (k in 0 until count) {
                var best: BlockPos? = null
                // This post's own angle first, then a little either side of it
                for (shift in doubleArrayOf(0.0, 0.25, -0.25, 0.45, -0.45)) {
                    val angle = from + (k + 0.5 + shift) * 2 * PI / count
                    var r = near
                    while (r <= far && best == null) {
                        val x = floor(cx + cos(angle) * r).toInt()
                        val z = floor(cz + sin(angle) * r).toInt()
                        best = postAt(x, z)?.takeIf { p -> found.none { hypot((it.x - p.x).toDouble(), (it.z - p.z).toDouble()) < 3.0 } }
                        r += 0.5
                    }
                    if (best != null) break
                }
                best?.let { found.add(it) }
            }
            if (found.isEmpty()) return
            // In order round the ring, so each garland runs on to the next
            found.sortBy { Math.IEEEremainder(atan2(it.z + 0.5 - cz, it.x + 0.5 - cx) - from - PI, 2 * PI) }
            val tops = found.map { it.above(3) }
            val riddle = kind == FestivalKind.LANTERNRISE
            for ((i, base) in found.withIndex()) {
                val top = tops[i]
                val next = tops[(i + 1) % tops.size]
                val crown = crown
                val string = if (crown != null && survey.clearLine(top, crown)) offset(top, crown) else null
                val garland = if (tops.size >= 3 && next != top) offset(top, next).takeIf { it.length() < 14.0 && survey.clearLine(top, next) } else null
                for (y in 0..2) add(base.above(y), FestivalTrimBlock.state(poleLook), group = i)
                val data = OrnamentData(if (riddle) OrnamentKind.RIDDLE else OrnamentKind.POST, kind, i, string, garland, banner = i % 2 == 0)
                add(top, FestivalOrnamentBlock.state(if (riddle) 13 else 14, riddle), data, solid = false, group = i)
                posts.add(top)
                if (riddle) riddles.add(top)
                taken.add(Vec3(base.x + 0.5, floor.toDouble(), base.z + 0.5))
                // The Sheafmoot heaps a pumpkin at the foot of each post, on the side away from the dance
                if (kind == FestivalKind.SHEAFMOOT) {
                    val out = outward(base)
                    if (free(out) && survey.solidBelow(out) && survey.open(out)) {
                        add(out, FestivalTrimBlock.state(if (i % 3 == 0) TrimLook.CARVED else TrimLook.PUMPKIN, facingIn(out)), group = i)
                    }
                }
            }
            // Fireworks go up between the posts
            val every = max(1, found.size / 4)
            for (i in found.indices step every) {
                val a = found[i]
                val b = found[(i + 1) % found.size]
                fireworks.add(Vec3((a.x + b.x) / 2.0 + 0.5, floor + 0.2, (a.z + b.z) / 2.0 + 0.5))
            }
        }

        private fun offset(from: BlockPos, to: BlockPos): Vec3 = Vec3((to.x - from.x).toDouble(), (to.y - from.y).toDouble(), (to.z - from.z).toDouble())

        /** A post's foot at ([x], [z]): ground there, and room for its pole and the ornament on top. */
        private fun postAt(x: Int, z: Int): BlockPos? {
            val y = survey.groundY(x, z, floor, 1) ?: return null
            val base = BlockPos(x, y, z)
            for (h in 0..4) if (!free(base.above(h))) return null
            for (h in 0..3) if (!survey.open(base.above(h))) return null
            if (taken.any { hypot(it.x - (x + 0.5), it.z - (z + 0.5)) < 1.6 }) return null
            return base
        }

        /** The cell beside [base] away from the centre. */
        private fun outward(base: BlockPos): BlockPos {
            val dx = base.x + 0.5 - cx
            val dz = base.z + 0.5 - cz
            return if (abs(dx) > abs(dz)) base.offset(if (dx > 0) 1 else -1, 0, 0) else base.offset(0, 0, if (dz > 0) 1 else -1)
        }

        private fun facingIn(pos: BlockPos): Direction {
            val dx = cx - (pos.x + 0.5)
            val dz = cz - (pos.z + 0.5)
            return if (abs(dx) > abs(dz)) (if (dx > 0) Direction.EAST else Direction.WEST) else (if (dz > 0) Direction.SOUTH else Direction.NORTH)
        }

        private fun facingOut(pos: BlockPos): Direction = facingIn(pos).opposite

        /** Something on the fountain's rim at its four diagonals: blossom, fire baskets, sheaves and lanterns, or candles. */
        fun rimTrim(fountain: BlockPos, radius: Int) {
            for (q in 0 until 4) {
                val a = PI / 4 + q * PI / 2
                // The rim cell nearest the diagonal: within half a block of the rim's line
                var cell: BlockPos? = null
                for ((ox, oz) in listOf(0 to 0, 1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
                    val x = (cos(a) * radius).roundToInt() + ox
                    val z = (sin(a) * radius).roundToInt() + oz
                    val d = sqrt((x * x + z * z).toDouble())
                    if (d <= radius - 0.5 || d > radius + 0.5) continue
                    val at = BlockPos(fountain.x + x, floor, fountain.z + z)
                    // The rim block stands at the feet's height: the trim goes on top of it
                    if (survey.open(at) || !survey.solidBelow(at.above()) || !free(at.above())) continue
                    cell = at.above()
                    break
                }
                val on = cell ?: continue
                when (kind) {
                    FestivalKind.RIBBONWAKE -> add(on, FestivalTrimBlock.state(TrimLook.BLOSSOM), solid = false)
                    FestivalKind.SUNHEARTH -> add(on, FestivalTrimBlock.state(TrimLook.BRAZIER))
                    FestivalKind.SHEAFMOOT -> {
                        add(on, FestivalTrimBlock.state(TrimLook.HAY))
                        if (free(on.above())) add(on.above(), FestivalTrimBlock.state(TrimLook.JACK, facingOut(on)))
                    }
                    FestivalKind.LANTERNRISE -> add(on, FestivalTrimBlock.state(TrimLook.CANDLES), solid = false)
                }
            }
        }

        /** Round a village's pole: the harvest stack, the fire baskets, the blossom or the candles. */
        fun baseTrim(centre: BlockPos) {
            when (kind) {
                FestivalKind.SHEAFMOOT -> {
                    for (dx in -1..1) for (dz in -1..1) {
                        if (dx == 0 && dz == 0) continue
                        val at = centre.offset(dx, 0, dz)
                        if (!free(at) || !survey.solidBelow(at)) continue
                        add(at, FestivalTrimBlock.state(TrimLook.HAY))
                        if (dx != 0 && dz != 0 && free(at.above())) {
                            add(at.above(), FestivalTrimBlock.state(if (dx + dz == 0) TrimLook.JACK else TrimLook.CARVED, facingOut(at)))
                        }
                    }
                }
                FestivalKind.SUNHEARTH -> for ((dx, dz) in DIAGONALS) {
                    val at = centre.offset(dx, 0, dz)
                    if (free(at) && survey.solidBelow(at)) add(at, FestivalTrimBlock.state(TrimLook.BRAZIER))
                }
                FestivalKind.RIBBONWAKE -> for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
                    val at = centre.offset(dx, 0, dz)
                    if (free(at) && survey.solidBelow(at)) add(at, FestivalTrimBlock.state(TrimLook.BLOSSOM), solid = false)
                }
                FestivalKind.LANTERNRISE -> for ((dx, dz) in DIAGONALS) {
                    val at = centre.offset(dx, 0, dz)
                    if (free(at) && survey.solidBelow(at)) add(at, FestivalTrimBlock.state(TrimLook.CANDLES), solid = false)
                }
            }
        }

        /**
         * What the day's contest needs: ribbons tied about the town between [huntNear] and [huntFar] blocks out for
         * the Ribbonwake, three butts across the square from the band for the Sunhearth, a show bench of the harvest
         * beside the judge for the Sheafmoot (the Lanternrise's riddles hang on its posts).
         */
        fun contest(seed: Long, bandAngle: Double, host: Spot, buttsAt: Double, huntNear: Int, huntFar: Int) {
            val random = RandomSource.create(seed)
            when (kind) {
                FestivalKind.RIBBONWAKE -> {
                    var tries = 0
                    while (ribbons.size < RIBBONS && tries++ < 200) {
                        val angle = random.nextDouble() * 2 * PI
                        val dist = huntNear + random.nextDouble() * (huntFar - huntNear)
                        val x = floor(cx + cos(angle) * dist).toInt()
                        val z = floor(cz + sin(angle) * dist).toInt()
                        val y = survey.groundY(x, z, floor, 4) ?: continue
                        val at = BlockPos(x, y, z)
                        if (!free(at) || !level.canSeeSky(at) || ribbons.any { it.distSqr(at) < 36.0 }) continue
                        add(at, FestivalOrnamentBlock.state(0, true), OrnamentData(OrnamentKind.HUNT_RIBBON, kind, ribbons.size), solid = false)
                        ribbons.add(at)
                    }
                }
                FestivalKind.SUNHEARTH -> {
                    // Three butts in a row across the square from the band, each a target on a short post, facing in
                    val across = bandAngle + PI
                    search@ for (shift in doubleArrayOf(0.0, 0.35, -0.35, 0.7, -0.7, 1.1, -1.1)) {
                        var r = buttsAt
                        while (r <= buttsAt + 3.0) {
                            val a = across + shift
                            val mx = cx + cos(a) * r
                            val mz = cz + sin(a) * r
                            // Along the row: square to the line from the centre
                            val tx = -sin(a)
                            val tz = cos(a)
                            val bases = (-1..1).mapNotNull { k ->
                                val x = floor(mx + tx * 2.0 * k).toInt()
                                val z = floor(mz + tz * 2.0 * k).toInt()
                                survey.groundY(x, z, floor, 1)?.let { y -> BlockPos(x, y, z) }
                            }
                            val fits = bases.size == 3 && bases.distinct().size == 3 &&
                                bases.all { free(it) && free(it.above()) && free(it.above(2)) && survey.open(it.above(2)) } &&
                                bases.none { b -> taken.any { hypot(it.x - (b.x + 0.5), it.z - (b.z + 0.5)) < 1.5 } }
                            if (fits) {
                                for (b in bases) {
                                    add(b, FestivalTrimBlock.state(TrimLook.POLE_OAK))
                                    add(b.above(), FestivalTrimBlock.state(TrimLook.TARGET, facingIn(b)))
                                    targets.add(b.above())
                                    taken.add(Vec3(b.x + 0.5, floor.toDouble(), b.z + 0.5))
                                }
                                break@search
                            }
                            r += 0.5
                        }
                    }
                }
                FestivalKind.SHEAFMOOT -> {
                    // The show bench: two bales beside the judge with the best of the harvest heaped on them
                    val a = atan2(host.z - cz, host.x - cx)
                    val r = max(1.0, hypot(host.x - cx, host.z - cz))
                    for (k in intArrayOf(1, 2)) {
                        val side = a + k * 1.1 / r
                        val x = floor(cx + cos(side) * r).toInt()
                        val z = floor(cz + sin(side) * r).toInt()
                        val y = survey.groundY(x, z, floor, 1) ?: continue
                        val at = BlockPos(x, y, z)
                        if (!free(at) || taken.any { hypot(it.x - (x + 0.5), it.z - (z + 0.5)) < 0.9 }) continue
                        add(at, FestivalTrimBlock.state(TrimLook.HAY))
                        if (free(at.above())) add(at.above(), FestivalTrimBlock.state(if (k == 1) TrimLook.PUMPKIN else TrimLook.MELON, facingIn(at)))
                        taken.add(Vec3(x + 0.5, floor.toDouble(), z + 0.5))
                    }
                }
                FestivalKind.LANTERNRISE -> {}
            }
        }

        /**
         * Sheafmoot's trestle for the Harvest Table (Hearthcraft Part L): up to [TRESTLE] cells on the show bench's arc beyond its
         * two bales, each a top oak slab with a Plate on it, by the bench's own tests (ground, [free], clear of [taken]), and clear
         * of every place someone stands there: the [crowd]'s, the second row behind it (1.3 blocks out from each, where the
         * festival puts anyone more and a full ring's dancers: FestivalSite.watchSpots) and the [dance]'s ring at its own
         * height. A capital's only, so the village pedlar's stand (VillageFeast.cartSpot) is never near one. None if there's
         * no room, and then the table simply has no plates.
         */
        fun trestle(host: Spot, crowd: List<Spot>, dance: FestivalDance?) {
            val a = atan2(host.z - cz, host.x - cx)
            val r = max(1.0, hypot(host.x - cx, host.z - cz))
            val standing = crowd.flatMap { s ->
                val d = hypot(s.x - cx, s.z - cz).coerceAtLeast(0.01)
                listOf(s.pos, Vec3(s.x + (s.x - cx) / d * 1.3, s.y, s.z + (s.z - cz) / d * 1.3))
            }
            for (k in 3..8) {
                if (trestle.size >= TRESTLE) break
                val side = a + k * 1.1 / r
                val x = floor(cx + cos(side) * r).toInt()
                val z = floor(cz + sin(side) * r).toInt()
                val y = survey.groundY(x, z, floor, 1) ?: continue
                val at = BlockPos(x, y, z)
                val top = at.above()
                if (!free(at) || !free(top) || taken.any { hypot(it.x - (x + 0.5), it.z - (z + 0.5)) < 0.9 }) continue
                if (standing.any { hypot(it.x - (x + 0.5), it.z - (z + 0.5)) < 1.1 }) continue
                if (dance != null && y <= floor) {
                    val out = hypot(x + 0.5 - cx, z + 0.5 - cz)
                    if (out > dance.radius - dance.reach - 1.1 && out < dance.radius + dance.reach + 1.1) continue
                }
                add(at, FestivalTrimBlock.state(TrimLook.TRESTLE))
                add(top, io.github.vaspyyy.guildmark.cooking.CookRegistry.PLATE.get().defaultBlockState(), solid = false)
                trestle.add(top)
                taken.add(Vec3(x + 0.5, floor.toDouble(), z + 0.5))
            }
        }

        fun build(capital: Boolean, dance: FestivalDance?, crowd: List<Spot>, band: List<Spot>, host: Spot, square: Int, around: Double): FestivalPlan {
            // Somewhere for the rockets even with no posts up
            if (fireworks.isEmpty()) for (q in 0 until 4) {
                val a = PI / 4 + q * PI / 2
                fireworks.add(Vec3(cx + cos(a) * around, floor + 0.2, cz + sin(a) * around))
            }
            var reach = square
            for (p in pieces) reach = max(reach, ceil(hypot(p.pos.x + 0.5 - cx, p.pos.z + 0.5 - cz)).toInt() + 1)
            return FestivalPlan(
                kind, capital, Vec3(cx, floor.toDouble(), cz), dance, crowd, band, host, pieces, crown, posts, ribbons, targets, riddles, fireworks, square, reach,
                trestle,
            )
        }

        companion object {
            private val DIAGONALS = listOf(1 to 1, -1 to 1, -1 to -1, 1 to -1)
            /** The most plates on the Harvest Table's trestle. */
            private const val TRESTLE = 6
        }
    }

    /** Looking at the ground round the festival. */
    private class Survey(val level: ServerLevel) {
        private val cursor = BlockPos.MutableBlockPos()

        /** Nothing in the way of a body at [pos], and no water. */
        fun open(pos: BlockPos): Boolean {
            val state = level.getBlockState(pos)
            return state.getCollisionShape(level, pos).isEmpty && state.fluidState.isEmpty
        }

        /** Something solid to stand on under [pos] (not farmland: nobody dances on the crops). */
        fun solidBelow(pos: BlockPos): Boolean {
            val below = pos.below()
            val state = level.getBlockState(below)
            val shape = state.getCollisionShape(level, below)
            return !shape.isEmpty && shape.max(Direction.Axis.Y) >= 0.5 && state.block !is FarmlandBlock
        }

        /** The height feet stand at in column ([x], [z]), within [range] of [near]. */
        fun groundY(x: Int, z: Int, near: Int, range: Int): Int? {
            if (!level.hasChunk(x shr 4, z shr 4)) return null
            for (y in near + range downTo near - range) {
                val pos = BlockPos(x, y, z)
                if (open(pos) && open(pos.above()) && solidBelow(pos)) return y
            }
            return null
        }

        /** Can a body stand at ([x], [z]) at height [y], all of it (it's 0.6 wide)? */
        fun standable(x: Double, y: Int, z: Double): Boolean {
            val middle = BlockPos(floor(x).toInt(), y, floor(z).toInt())
            if (!level.hasChunk(middle.x shr 4, middle.z shr 4) || !solidBelow(middle)) return false
            for ((ox, oz) in CORNERS) {
                cursor.set(floor(x + ox).toInt(), y, floor(z + oz).toInt())
                if (!open(cursor) || !open(cursor.above())) return false
            }
            return true
        }

        /** Whether the whole band from [r0] to [r1] round ([cx], [cz]) is open, level ground at [y]. */
        fun ringClear(cx: Double, cz: Double, y: Int, r0: Double, r1: Double): Boolean {
            var r = r0
            while (r <= r1 + 1e-6) {
                val steps = max(12, (2 * PI * r / 0.5).toInt())
                for (k in 0 until steps) {
                    val a = 2 * PI * k / steps
                    if (!standable(cx + cos(a) * r, y, cz + sin(a) * r)) return false
                }
                r += 0.4
            }
            return true
        }

        /** Whether a string from [a] to [b] would pass only through open air (a garland sags: look below the line too). */
        fun clearLine(a: BlockPos, b: BlockPos): Boolean {
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val dz = (b.z - a.z).toDouble()
            val steps = (sqrt(dx * dx + dy * dy + dz * dz) / 0.5).toInt()
            for (s in 1 until steps) {
                val t = s.toDouble() / steps
                val sag = if (dy == 0.0) 0.9 * 4 * t * (1 - t) else 0.0
                cursor.set(floor(a.x + 0.5 + dx * t).toInt(), floor(a.y + 0.5 + dy * t - sag).toInt(), floor(a.z + 0.5 + dz * t).toInt())
                if (cursor == a || cursor == b) continue
                val state = level.getBlockState(cursor)
                if (!state.isAir && !FestivalRestores.ours(state) && !state.canBeReplaced()) return false
            }
            return true
        }

        /**
         * The band: [count] musicians in a row on the [angle] side, square to the line from the centre, on the first
         * of [radii] that has room (sliding along a little if something's in the way); failing that, whoever fits.
         */
        fun band(cx: Double, cz: Double, y: Int, angle: Double, radii: List<Double>, count: Int, taken: List<Vec3>): List<Spot> {
            for (r in radii) for (slide in doubleArrayOf(0.0, 0.7, -0.7, 1.4, -1.4, 2.1, -2.1)) {
                row(cx, cz, y, angle + slide / r, r, count, 1.4, taken)?.let { return it }
            }
            val out = ArrayList<Spot>()
            for (k in 0 until count) {
                val spot = near(cx, cz, y, angle + (k - (count - 1) / 2.0) * 0.25, radii.first(), taken + out.map { it.pos }) ?: continue
                out.add(spot)
            }
            return out
        }

        private fun row(cx: Double, cz: Double, y: Int, angle: Double, r: Double, count: Int, gap: Double, taken: List<Vec3>): List<Spot>? {
            val out = ArrayList<Spot>()
            for (k in 0 until count) {
                val a = angle + (k - (count - 1) / 2.0) * gap / r
                val x = cx + cos(a) * r
                val z = cz + sin(a) * r
                val gy = groundY(floor(x).toInt(), floor(z).toInt(), y, 1) ?: return null
                if (!standable(x, gy, z) || taken.any { hypot(it.x - x, it.z - z) < 1.1 }) return null
                out.add(Spot(x, gy.toDouble(), z, FestivalDance.yawOf(cx - x, cz - z)))
            }
            return out
        }

        /** A spot near [angle], [r] out, looking a little either way round for one, facing the centre. */
        fun near(cx: Double, cz: Double, y: Int, angle: Double, r: Double, taken: List<Vec3>): Spot? {
            for (shift in doubleArrayOf(0.0, 0.15, -0.15, 0.3, -0.3, 0.5, -0.5, 0.8, -0.8)) {
                for (dr in doubleArrayOf(0.0, 0.5, -0.5, 1.0)) {
                    val a = angle + shift
                    val x = cx + cos(a) * (r + dr)
                    val z = cz + sin(a) * (r + dr)
                    if (taken.any { hypot(it.x - x, it.z - z) < 1.1 }) continue
                    val gy = groundY(floor(x).toInt(), floor(z).toInt(), y, 1) ?: continue
                    if (standable(x, gy, z)) return Spot(x, gy.toDouble(), z, FestivalDance.yawOf(cx - x, cz - z))
                }
            }
            return null
        }

        /** The host's stage: up on the fountain's rim near the band, facing out over the square. */
        fun onRim(cx: Double, cz: Double, y: Int, angle: Double, rim: Double): Spot? {
            for (shift in doubleArrayOf(0.32, -0.32, 0.5, -0.5, 0.7, -0.7)) {
                val a = angle + shift
                val x = floor(cx + cos(a) * rim).toInt()
                val z = floor(cz + sin(a) * rim).toInt()
                val top = BlockPos(x, y + 1, z)
                if (open(top.below()) || !open(top) || !open(top.above()) || !solidBelow(top)) continue
                val sx = x + 0.5
                val sz = z + 0.5
                return Spot(sx, y + 1.0, sz, FestivalDance.yawOf(sx - cx, sz - cz))
            }
            return null
        }

        /**
         * Up to [count] spots for the crowd on the rows [rows] round the centre, spread all round it but leaving the
         * band's side to the band, and none on top of anything in [taken].
         */
        fun crowd(cx: Double, cz: Double, y: Int, rows: List<Double>, bandAngle: Double, count: Int, taken: List<Vec3>): List<Spot> {
            val found = ArrayList<Pair<Double, Spot>>()
            for ((row, r) in rows.withIndex()) {
                val steps = max(8, (2 * PI * r / 1.3).toInt())
                for (k in 0 until steps) {
                    val a = 2 * PI * (k + 0.5 * row) / steps
                    if (abs(Math.IEEEremainder(a - bandAngle, 2 * PI)) < 0.55) continue
                    val x = cx + cos(a) * r
                    val z = cz + sin(a) * r
                    if (taken.any { hypot(it.x - x, it.z - z) < 1.2 }) continue
                    val gy = groundY(floor(x).toInt(), floor(z).toInt(), y, 1) ?: continue
                    if (!standable(x, gy, z)) continue
                    found.add(Math.IEEEremainder(a - bandAngle, 2 * PI) to Spot(x, gy.toDouble(), z, FestivalDance.yawOf(cx - x, cz - z)))
                }
            }
            if (found.size <= count) return found.map { it.second }
            // Spread all round: every so many of them, going round
            found.sortBy { it.first }
            val step = found.size.toDouble() / count
            return (0 until count).map { found[(it * step).toInt()].second }
        }

        /** Somewhere to stand by the bell, when there's nowhere flat to dance. */
        fun groundNear(bell: BlockPos): BlockPos {
            for (r in 2..6) for (k in 0 until 8) {
                val a = 2 * PI * k / 8
                val x = floor(bell.x + 0.5 + cos(a) * r).toInt()
                val z = floor(bell.z + 0.5 + sin(a) * r).toInt()
                val y = groundY(x, z, bell.y, 3) ?: continue
                return BlockPos(x, y, z)
            }
            return bell
        }

        /**
         * The flattest open ground near a village's bell big enough for a ring: the middle of it, and the ring's
         * radius (as big as fits, 4.5 blocks down to 2.5). The ground is looked at once, over 41 by 41 columns.
         */
        fun villageRing(bell: BlockPos): Pair<BlockPos, Double>? {
            val half = 20
            val size = half * 2 + 1
            val heights = IntArray(size * size) { Int.MIN_VALUE }
            for (dx in -half..half) for (dz in -half..half) {
                val x = bell.x + dx
                val z = bell.z + dz
                if (!level.hasChunk(x shr 4, z shr 4)) continue
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                cursor.set(x, y, z)
                if (abs(y - bell.y) > 4 || !solidBelow(cursor)) continue
                if (!open(cursor) || !open(cursor.above()) || !open(cursor.above(2))) continue
                heights[(dx + half) * size + dz + half] = y
            }
            fun h(dx: Int, dz: Int): Int = if (abs(dx) > half || abs(dz) > half) Int.MIN_VALUE else heights[(dx + half) * size + dz + half]
            val candidates = ArrayList<Pair<Int, Int>>()
            for (dx in -14..14) for (dz in -14..14) if (dx * dx + dz * dz in 9..196) candidates.add(dx to dz)
            // Nearest a few steps from the bell first
            candidates.sortBy { (dx, dz) -> abs(sqrt((dx * dx + dz * dz).toDouble()) - 6.0) }
            for (ring in doubleArrayOf(4.5, 3.5, 2.5)) {
                val edge = ring + 1.4
                val e = ceil(edge).toInt()
                for ((ox, oz) in candidates) {
                    val y0 = h(ox, oz)
                    if (y0 == Int.MIN_VALUE) continue
                    var ok = true
                    loop@ for (dx in -e..e) for (dz in -e..e) {
                        if (dx * dx + dz * dz > edge * edge) continue
                        if (h(ox + dx, oz + dz) != y0) {
                            ok = false
                            break@loop
                        }
                    }
                    if (ok) return BlockPos(bell.x + ox, y0, bell.z + oz) to ring
                }
            }
            return null
        }

        companion object {
            private val CORNERS = listOf(-0.3 to -0.3, 0.3 to -0.3, -0.3 to 0.3, 0.3 to 0.3)
        }
    }
}

/** How many ribbons the Ribbon Hunt hides. */
const val RIBBONS = 8
