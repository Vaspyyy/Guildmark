package io.github.vaspyyy.guildmark.island.plan

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything drawn on an island, worked out once from its spec: the lie of the land ([terrain]), what the ground is
 * made of column by column, the trees, bushes, boulders and drift logs on it, what's built on it, and who lives
 * there. [draw] then puts any share of it into a canvas, a chunk at a time; nothing in it changes once it's made, so
 * any number of chunks can draw from one plan at once.
 */
class IslandPlan(val spec: IslandSpec) {
    val terrain = Terrain(spec)
    val sea = spec.sea
    val climate = spec.climate
    private val size = terrain.size
    private val bands = spec.rock.bands

    private val sWoods = Noise.seed(spec.seed, 20)
    private val sLush = Noise.seed(spec.seed, 21)
    private val sFlowers = Noise.seed(spec.seed, 22)
    private val sPatch = Noise.seed(spec.seed, 23)
    private val sShingle = Noise.seed(spec.seed, 24)
    private val sDune = Noise.seed(spec.seed, 25)
    private val sSoil = Noise.seed(spec.seed, 26)
    private val sBandWarp = Noise.seed(spec.seed, 27)
    private val sSpeck = Noise.seed(spec.seed, 28)
    private val sTree = Noise.seed(spec.seed, 29)
    private val sShrub = Noise.seed(spec.seed, 30)
    private val sRock = Noise.seed(spec.seed, 31)
    private val sDrift = Noise.seed(spec.seed, 32)
    private val sCover = Noise.seed(spec.seed, 33)
    private val sVine = Noise.seed(spec.seed, 34)
    private val sTop = Noise.seed(spec.seed, 35)
    private val sUnder = Noise.seed(spec.seed, 36)

    private val warm = climate == Climate.WARM || climate == Climate.LUKEWARM
    private val cold = climate == Climate.COLD || climate == Climate.FROZEN

    /** Which way the trees lean: away from the wind. */
    private val downwind = terrain.windFrom + PI

    /** The band of [bands] each height falls in (from [bandBase] up), in layers two to four blocks thick. */
    private val bandBase = sea - 96
    private val bandOf = IntArray(192).also {
        val dice = Dice(spec.seed xor 0xB4EDL)
        var band = dice.int(bands.size)
        var y = 0
        while (y < it.size) {
            val thick = dice.int(2, 4)
            for (k in 0 until thick) if (y + k < it.size) it[y + k] = band
            y += thick
            band = (band + 1 + if (dice.chance(0.25)) 1 else 0) % bands.size
        }
    }

    /**
     * Where the woods begin on the woods noise: set so that about the share of the land this climate likes (more or
     * less, island to island) is wooded, wherever the noise happens to lie over this island.
     */
    private val woodsLine: Double = run {
        val dice = Dice(spec.seed xor 0x3D00DL)
        val want = when (climate) {
            Climate.WARM -> 0.3
            Climate.LUKEWARM -> 0.45
            Climate.TEMPERATE -> 0.55
            Climate.COLD -> 0.65
            Climate.FROZEN -> 0.3
        } * dice.range(0.65, 1.25) * (if (spec.radius < 16) 0.6 else 1.0)
        val samples = ArrayList<Double>()
        for (i in 0 until size step 3) for (j in 0 until size step 3) {
            val x = terrain.x0 + i
            val z = terrain.z0 + j
            if (terrain.dry(x, z)) samples.add(woodsNoise(x, z))
        }
        if (samples.isEmpty()) 0.5 else samples.sorted()[((1 - want.coerceIn(0.0, 1.0)) * (samples.size - 1)).toInt()]
    }

    private fun woodsNoise(x: Int, z: Int): Double = Noise.fbm(x / 38.0, z / 38.0, sWoods, 3)

    /** Columns kept free of trees, rocks and drift logs: where something's built. */
    private val claimed = BooleanArray(size * size)

    /** What every column's ground is (Ground ordinals). */
    private val ground = ByteArray(size * size)

    /** What's built here. */
    val landmarks: List<Landmark> = landmarksFor(this)

    /** Trees, bushes, boulders and drift logs, in the order they're grown. */
    val features = ArrayList<Feature>()

    /** Who's here when the island's made: gulls, turtles, the island's own folk. */
    val spawns = ArrayList<Spawn>()

    class Spawn(val what: String, val x: Double, val y: Double, val z: Double, val yaw: Float)

    /** Where the island's own sounds come from: everything built on it, worked out (once) if it isn't yet. */
    fun sounds(): List<Build.Sound> = landmarks.flatMap { it.made.sounds }

    init {
        for (l in landmarks) l.lay()
        for (i in 0 until size) for (j in 0 until size) ground[i * size + j] = classify(terrain.x0 + i, terrain.z0 + j).ordinal.toByte()
        for (l in landmarks) l.paint()
        scatter()
        populate()
    }

    private fun index(x: Int, z: Int): Int = (x - terrain.x0) * size + (z - terrain.z0)

    fun groundAt(x: Int, z: Int): Ground = if (terrain.inside(x, z)) Ground.entries[ground[index(x, z)].toInt()] else Ground.UNDER_SAND

    /** Gives a column a ground of its own (a landmark's path or yard). */
    fun paint(x: Int, z: Int, g: Ground) {
        if (terrain.inside(x, z)) ground[index(x, z)] = g.ordinal.toByte()
    }

    /** Keeps trees, rocks and drift logs off a rectangle of columns. */
    fun claim(xa: Int, za: Int, xb: Int, zb: Int) {
        for (x in xa..xb) for (z in za..zb) if (terrain.inside(x, z)) claimed[index(x, z)] = true
    }

    fun claimed(x: Int, z: Int): Boolean = terrain.inside(x, z) && claimed[index(x, z)]

    // ---- the ground

    private fun classify(x: Int, z: Int): Ground {
        val t = terrain
        val h = t.h(x, z)
        val top = t.top(x, z)
        val d = t.distance(x, z)
        val c = t.cliffAt(x, z)
        if (top <= sea) {
            // The rock the stacks stand on
            if (spec.form == Form.STACKS && h > -2.5) return if (Noise.unit(x, 1, z, sPatch) < 0.3) Ground.UNDER_GRAVEL else Ground.UNDER_ROCK
            if (t.inLagoon(x, z)) return if (Noise.fbm(x / 9.0, z / 9.0, sPatch + 5, 2) > 0.66) Ground.UNDER_CLAY else Ground.LAGOON
            if (spec.form == Form.REEF && d > -4) return Ground.REEF
            if (spec.form == Form.ATOLL && d > -6 && d < 0) return Ground.REEF
            if (c > 0.6 && d > -5) return Ground.UNDER_ROCK
            val n = Noise.fbm(x / 10.0, z / 10.0, sUnder, 2)
            if (cold) return if (n < 0.62) Ground.UNDER_GRAVEL else Ground.UNDER_ROCK
            if (spec.rock == Rock.SANDSTONE || warm) return if (n > 0.8) Ground.UNDER_GRAVEL else Ground.UNDER_SAND
            if (n > 0.72) return Ground.UNDER_GRAVEL
            if (n < 0.2 && d < -6) return Ground.UNDER_CLAY
            return Ground.UNDER_SAND
        }
        val drop = t.drop(x, z)
        val rise = t.rise(x, z)
        // Halfway down a cliff: a ledge of bare rock
        if (drop >= 3 && rise >= 2) return Ground.BARE
        // At a cliff's foot: what's fallen from it
        if (rise >= 4 && h < 5) return Ground.SCREE
        if (spec.form == Form.STACKS) return if (h > 6 && Noise.unit(x, 0, z, sPatch) < 0.75) Ground.GRASS else Ground.BARE
        if (t.onBar(x, z)) return if (cold) Ground.SHINGLE else Ground.SAND
        val beachWidth = 4.0 + 6.0 * Noise.fbm(x / 17.0, z / 17.0, sShingle + 1, 2)
        val atollScrub = spec.form == Form.ATOLL && d >= 4 && Noise.fbm(x / 9.0, z / 9.0, sDune + 3, 2) > 0.38
        if (h < 2.9 && c < 0.55 && (d < beachWidth || spec.form == Form.ATOLL) && !atollScrub) {
            val shingle = Noise.fbm(x / 21.0, z / 21.0, sShingle, 2)
            return when {
                spec.rock == Rock.SANDSTONE || warm -> Ground.SAND
                cold -> if (shingle < 0.7) Ground.SHINGLE else Ground.SAND
                else -> if (shingle > 0.6) Ground.SHINGLE else Ground.SAND
            }
        }
        if (warm && h < 4.6 && d < 15 && c < 0.4 && Noise.fbm(x / 13.0, z / 13.0, sDune, 2) > 0.47) return Ground.DUNE
        // Wind-scoured rock on the cliffs' tops
        if (c > 0.7 && d < 4 && drop >= 2) return Ground.BARE
        if (climate == Climate.FROZEN && h > 9 + 3 * Noise.fbm(x / 11.0, z / 11.0, sPatch + 2, 2)) return Ground.SNOWCAP
        val patch = Noise.fbm(x / 12.0, z / 12.0, sPatch, 3)
        val woods = woods(x, z)
        return when (climate) {
            Climate.WARM -> if (patch > 0.62) Ground.COARSE else Ground.GRASS
            Climate.LUKEWARM -> if (patch > 0.68) Ground.COARSE else Ground.GRASS
            Climate.TEMPERATE -> when {
                patch > 0.7 && d < 18 -> Ground.COARSE
                patch < 0.28 && woods > 0.5 -> Ground.MOSS
                else -> Ground.GRASS
            }
            Climate.COLD, Climate.FROZEN -> when {
                woods > 0.55 && patch > 0.45 -> Ground.PODZOL
                patch < 0.3 -> Ground.MOSS
                patch > 0.72 -> Ground.COARSE
                else -> Ground.GRASS
            }
        }
    }

    /** 0 to 1: how wooded the land is here. */
    fun woods(x: Int, z: Int): Double {
        val w = smooth(woodsLine - 0.05, woodsLine + 0.09, woodsNoise(x, z)) * when (climate) {
            Climate.WARM -> 0.6
            Climate.LUKEWARM -> 0.8
            Climate.FROZEN -> 0.5
            else -> 1.0
        }
        // The wind keeps the windward shore bare
        return w * shelter(x, z)
    }

    /** 0 to 1: how sheltered from the wind off the sea a column is. */
    private fun shelter(x: Int, z: Int): Double {
        val facing = atan2((z - spec.cz).toDouble(), (x - spec.cx).toDouble())
        val windward = max(0.0, cos(facing - terrain.windFrom))
        val d = terrain.distance(x, z)
        return 1 - 0.75 * windward * (1 - smooth(5.0, 22.0, d))
    }

    /** The heading out to the nearest sea from a column, in radians. */
    private fun seaward(x: Int, z: Int): Double {
        val t = terrain
        return atan2(t.distance(x, z - 1) - t.distance(x, z + 1), t.distance(x - 1, z) - t.distance(x + 1, z))
    }

    /** The island's rock at a block: its bands, warped and tilted, with a fleck of the others here and there. */
    fun rockAt(x: Int, y: Int, z: Int): Bk {
        val tilt = 0.035 * ((x - spec.cx) * cos(terrain.angle + 0.6) + (z - spec.cz) * kotlin.math.sin(terrain.angle + 0.6))
        val warp = 1.7 * (Noise.fbm(x / 13.0, z / 13.0, sBandWarp, 2) * 2 - 1) + tilt
        val at = (y + warp).roundToInt() - bandBase
        var band = bandOf[at.coerceIn(0, bandOf.size - 1)]
        if (Noise.unit(x, y, z, sSpeck) < 0.05) band = (band + 2) % bands.size
        return Bk.of(bands[band])
    }

    private fun soilDepth(g: Ground, x: Int, z: Int): Int {
        val n = Noise.unit(x, 0, z, sSoil)
        val drop = terrain.drop(x, z)
        return when (g) {
            Ground.GRASS, Ground.COARSE, Ground.PODZOL, Ground.MOSS, Ground.PATH -> max(1, 2 + (n * 3).toInt() - (if (drop >= 4) 1 else 0) - (if (drop >= 7) 1 else 0))
            Ground.SAND -> 3 + (n * 3).toInt()
            Ground.DUNE -> 5 + (n * 2).toInt()
            Ground.SHINGLE -> 2 + (n * 2).toInt()
            Ground.SCREE -> 2
            Ground.SNOWCAP -> 1
            Ground.UNDER_SAND, Ground.LAGOON -> 3
            Ground.UNDER_GRAVEL, Ground.UNDER_CLAY -> 2
            Ground.REEF -> 1 + (n * 2).toInt()
            Ground.BUILT -> 2
            Ground.BARE, Ground.UNDER_ROCK -> 0
        }
    }

    /** The block [depth] down from the top of a column of [g] ground with [soil] blocks of soil. */
    private fun groundBlock(g: Ground, depth: Int, soil: Int, x: Int, y: Int, z: Int): Bk {
        if (depth >= soil && depth < 7 && y > sea + 3 && terrain.cliffAt(x, z) > 0.9 && (spec.form == Form.STACKS || terrain.distance(x, z) < 0)) {
            // Where the gulls nest, the rock under their ledges is streaked white
            val streak = Noise.value(x * 0.9 + z * 0.37, y / 3.0, sSpeck + 9)
            if (streak > 0.62 && Noise.unit(x, y, z, sSpeck + 10) < 0.7) return Bk.of(if (Noise.unit(x, y, z, sSpeck + 11) < 0.6) "calcite" else "diorite")
        }
        if (depth >= soil) {
            // Sand lies on sandstone a block or two before the rock
            val sandy = g == Ground.SAND || g == Ground.DUNE || g == Ground.UNDER_SAND || g == Ground.LAGOON
            if (sandy && depth < soil + 2 && (warm || spec.rock == Rock.SANDSTONE)) return Bk.of("sandstone")
            if (g == Ground.BARE && depth == 0) {
                val n = Noise.unit(x, y, z, sTop)
                if (!warm && n < 0.07) return Bk.of("moss_block")
                if (n < 0.15) return Bk.of(if (cold) "cobblestone" else "mossy_cobblestone")
            }
            return rockAt(x, y, z)
        }
        val n = Noise.unit(x, y, z, sTop)
        return when (g) {
            Ground.GRASS -> if (depth == 0) Bk.of("grass_block") else Bk.of("dirt")
            Ground.COARSE -> if (depth == 0) Bk.of(if (n < 0.2) "grass_block" else "coarse_dirt") else Bk.of("dirt")
            Ground.PODZOL -> if (depth == 0) Bk.of(if (n < 0.15) "coarse_dirt" else "podzol") else Bk.of("dirt")
            Ground.MOSS -> if (depth == 0) Bk.of(if (n < 0.25) "grass_block" else "moss_block") else Bk.of("dirt")
            Ground.PATH -> if (depth == 0) Bk.of(if (n < 0.12) "coarse_dirt" else "dirt_path") else Bk.of("dirt")
            Ground.SAND, Ground.DUNE, Ground.UNDER_SAND, Ground.LAGOON -> Bk.of("sand")
            Ground.SHINGLE -> when {
                depth > 0 -> Bk.of("gravel")
                n < 0.08 -> Bk.of("cobblestone")
                n < 0.14 -> rockAt(x, y, z)
                else -> Bk.of("gravel")
            }
            Ground.SCREE -> when {
                depth > 0 -> Bk.of("gravel")
                n < 0.35 -> Bk.of("gravel")
                n < 0.6 -> Bk.of("cobblestone")
                n < 0.7 && !warm -> Bk.of("mossy_cobblestone")
                else -> rockAt(x, y, z)
            }
            Ground.SNOWCAP -> Bk.of("snow_block")
            Ground.UNDER_GRAVEL -> Bk.of("gravel")
            Ground.UNDER_CLAY -> if (depth == 0 || n < 0.5) Bk.of("clay") else Bk.of("sand")
            Ground.REEF -> if (climate == Climate.WARM) {
                when {
                    depth > 0 -> Bk.of("sand")
                    n < 0.14 -> Bk.of("brain_coral_block")
                    n < 0.28 -> Bk.of("tube_coral_block")
                    n < 0.4 -> Bk.of("horn_coral_block")
                    n < 0.5 -> Bk.of("fire_coral_block")
                    n < 0.58 -> Bk.of("bubble_coral_block")
                    else -> Bk.of("sand")
                }
            } else when {
                n < 0.35 -> Bk.of("gravel")
                n < 0.5 -> Bk.of(if (warm) "sand" else "mossy_cobblestone")
                else -> rockAt(x, y, z)
            }
            Ground.BUILT -> Bk.of("dirt")
            Ground.BARE, Ground.UNDER_ROCK -> rockAt(x, y, z)
        }
    }

    // ---- what grows and lies on it

    private fun scatter() {
        val t = terrain
        // Trees, on a jittered grid of four-block cells: the same ones whichever chunk asks
        cells(4, sTree) { x, z, roll, pick ->
            val g = groundAt(x, z)
            val d = t.distance(x, z)
            var p = when (g) {
                Ground.GRASS, Ground.PODZOL, Ground.MOSS, Ground.COARSE -> (0.04 + 0.8 * woods(x, z)) * smooth(1.5, 7.0, d)
                Ground.SAND, Ground.DUNE -> if (warm) 0.26 * smooth(1.0, 3.0, d) * (1 - smooth(12.0, 18.0, d)) else 0.0
                else -> 0.0
            }
            if (spec.radius < 16) p *= 0.6
            if (spec.kind == IslandKind.LIGHTHOUSE) p *= 0.35
            val steep = max(t.drop(x, z), t.rise(x, z))
            if (steep > 2) p *= 0.3 else if (steep == 2) p *= 0.7
            if (roll < p) {
                val lean = downwind + (pick - 0.5) * 0.9
                features.add(treeFor(climate, x, t.top(x, z), z, cellSeed(x, z, 1), d, lean, seaward(x, z), pick))
            }
        }
        // Bushes, thickest at the woods' edges
        cells(3, sShrub) { x, z, roll, pick ->
            val g = groundAt(x, z)
            if (g != Ground.GRASS && g != Ground.COARSE && g != Ground.MOSS && g != Ground.PODZOL) return@cells
            val w = woods(x, z)
            val edge = 1 - kotlin.math.abs(w - 0.4) / 0.4
            val p = (0.025 + 0.12 * max(0.0, edge)) * smooth(1.0, 4.0, t.distance(x, z))
            if (roll < p && t.drop(x, z) <= 1) {
                val kind = when (climate) {
                    Climate.WARM -> if (pick < 0.6) "jungle" else "azalea"
                    Climate.LUKEWARM -> if (pick < 0.5) "azalea" else "oak"
                    Climate.TEMPERATE -> if (pick < 0.7) "oak" else "azalea"
                    else -> "spruce"
                }
                features.add(Shrub(x, t.top(x, z), z, cellSeed(x, z, 2), kind))
            }
        }
        // Boulders, and rock fallen at the cliffs' feet
        cells(7, sRock) { x, z, roll, _ ->
            val p = when (groundAt(x, z)) {
                Ground.SCREE -> 0.35
                Ground.SHINGLE -> 0.12
                Ground.GRASS, Ground.COARSE, Ground.MOSS -> if (cold) 0.12 else 0.05
                Ground.PODZOL, Ground.SNOWCAP -> 0.08
                else -> 0.0
            }
            if (roll < p) {
                val stone = bands[(Noise.unit(x, 1, z, sRock) * bands.size).toInt().coerceAtMost(bands.size - 1)]
                features.add(Boulder(x, t.top(x, z), z, cellSeed(x, z, 3), if (stone == "terracotta") "sandstone" else stone))
            }
        }
        // Drift logs at the tide line
        cells(9, sDrift) { x, z, roll, pick ->
            val g = groundAt(x, z)
            if ((g == Ground.SAND || g == Ground.SHINGLE) && t.distance(x, z) < 4 && roll < 0.4) {
                features.add(Driftwood(x, t.top(x, z), z, cellSeed(x, z, 4), Dir.entries[(pick * 4).toInt().coerceAtMost(3)]))
            }
        }
    }

    /**
     * Visits one jittered point in every [cell]-block square of the grid that lands on the island's own ground and
     * isn't claimed, with two numbers in [0, 1) fixed for that point: whether something stands there, and which.
     */
    private inline fun cells(cell: Int, seed: Int, visit: (x: Int, z: Int, roll: Double, pick: Double) -> Unit) {
        val t = terrain
        val n = size / cell + 1
        for (ci in 0 until n) for (cj in 0 until n) {
            val h = Noise.hash(ci, 7, cj, seed)
            val x = t.x0 + ci * cell + Math.floorMod(h, cell)
            val z = t.z0 + cj * cell + Math.floorMod(h ushr 8, cell)
            if (!t.inside(x, z) || nearClaim(x, z, cell) || !t.dry(x, z)) continue
            visit(x, z, Noise.unit(ci, 1, cj, seed), Noise.unit(ci, 2, cj, seed))
        }
    }

    /** Is anything built within [r] columns of [x], [z]? (Then nothing grows there to reach over it.) */
    private fun nearClaim(x: Int, z: Int, r: Int): Boolean {
        for (dx in -r..r) for (dz in -r..r) if (claimed(x + dx, z + dz)) return true
        return false
    }

    private fun cellSeed(x: Int, z: Int, use: Int): Long = spec.seed * 31 + Noise.hash(x, use, z, 0x7EE).toLong()

    /** Who's on the island when it's made. */
    private fun populate() {
        val t = terrain
        val dice = Dice(spec.seed xor 0x5EA51DEL)
        // Gulls on the cliff tops and the stacks, a few on the beaches
        val want = when (spec.form) {
            Form.STACKS -> dice.int(8, 14)
            Form.REEF -> dice.int(2, 4)
            else -> dice.int(2, 5) + (spec.radius / 15)
        }
        var tries = 0
        var placed = 0
        while (placed < want && tries++ < 400) {
            val x = t.x0 + dice.int(size)
            val z = t.z0 + dice.int(size)
            if (!t.dry(x, z) || claimed(x, z)) continue
            val g = groundAt(x, z)
            val good = when (g) {
                Ground.BARE, Ground.GRASS, Ground.SHINGLE, Ground.SAND, Ground.SCREE -> true
                else -> false
            }
            val perch = t.cliffAt(x, z) > 0.5 || spec.form == Form.STACKS || t.distance(x, z) < 4
            if (!good || !perch) continue
            spawns.add(Spawn("guildmark:gull", x + 0.5, t.top(x, z).toDouble(), z + 0.5, dice.range(0.0, 360.0).toFloat()))
            placed++
        }
        // Turtles hauled out on the warm beaches
        if (climate == Climate.WARM || climate == Climate.LUKEWARM) {
            var turtles = if (dice.chance(0.6)) dice.int(1, 3) else 0
            tries = 0
            while (turtles > 0 && tries++ < 200) {
                val x = t.x0 + dice.int(size)
                val z = t.z0 + dice.int(size)
                if (groundAt(x, z) != Ground.SAND || claimed(x, z) || t.distance(x, z) > 5) continue
                spawns.add(Spawn("minecraft:turtle", x + 0.5, t.top(x, z).toDouble(), z + 0.5, dice.range(0.0, 360.0).toFloat()))
                turtles--
            }
        }
        // Rabbits in the meadows, goats on the cold heights
        val beast = when (climate) {
            Climate.COLD, Climate.FROZEN -> if (spec.radius >= 22 && dice.chance(0.5)) "minecraft:goat" else null
            Climate.TEMPERATE, Climate.LUKEWARM -> if (spec.radius >= 18 && dice.chance(0.55)) "minecraft:rabbit" else null
            Climate.WARM -> null
        }
        if (beast != null && spec.kind == IslandKind.ISLE) {
            var n = dice.int(1, 3)
            tries = 0
            while (n > 0 && tries++ < 200) {
                val x = t.x0 + dice.int(size)
                val z = t.z0 + dice.int(size)
                val g = groundAt(x, z)
                if (!t.dry(x, z) || claimed(x, z) || (g != Ground.GRASS && g != Ground.BARE && g != Ground.MOSS)) continue
                if (beast == "minecraft:goat" && t.h(x, z) < 6) continue
                spawns.add(Spawn(beast, x + 0.5, t.top(x, z).toDouble(), z + 0.5, dice.range(0.0, 360.0).toFloat()))
                n--
            }
        }
    }

    // ---- drawing

    /** Draws the share of the island that falls in [canvas]'s columns. */
    fun draw(canvas: IslandCanvas) {
        val t = terrain
        val xa = max(canvas.minX, t.x0)
        val xb = min(canvas.maxX, t.x0 + size - 1)
        val za = max(canvas.minZ, t.z0)
        val zb = min(canvas.maxZ, t.z0 + size - 1)
        if (xa > xb || za > zb) return
        for (x in xa..xb) for (z in za..zb) column(canvas, x, z)
        for (l in landmarks) if (l.touches(canvas)) l.draw(canvas)
        grow(canvas)
        for (x in xa..xb) for (z in za..zb) cover(canvas, x, z)
        for (x in xa..xb) for (z in za..zb) vines(canvas, x, z)
        for (s in spawns) if (canvas.contains(kotlin.math.floor(s.x).toInt(), kotlin.math.floor(s.z).toInt())) canvas.spawn(s.what, s.x, s.y, s.z, s.yaw)
    }

    private fun column(canvas: IslandCanvas, x: Int, z: Int) {
        val t = terrain
        val top = t.top(x, z)
        val floor = canvas.floor(x, z)
        // Where the island's slope runs under the sea bed, the sea bed stays as it was
        if (top - 1 <= floor) return
        val g = groundAt(x, z)
        val soil = soilDepth(g, x, z)
        var roofed = false
        for (c in t.carves) if (x in c.minX..c.maxX && z in c.minZ..c.maxZ) roofed = true
        var openBelow = false
        for (y in floor + 1 until top) {
            if (roofed && t.carves.any { it.contains(x + 0.5, y + 0.5, z + 0.5) }) {
                // Left as it was: the sea below its surface, air above
                openBelow = true
                continue
            }
            var b = groundBlock(g, top - 1 - y, soil, x, y, z)
            if (openBelow) b = steady(b)
            canvas.set(x, y, z, b)
            openBelow = false
        }
        if (top > sea && climate == Climate.FROZEN) canvas.clearAbove(x, z, top)
    }

    /** What stays put over an opening where [b] would fall into it. */
    private fun steady(b: Bk): Bk = when (b.name) {
        "sand" -> Bk.of("sandstone")
        "red_sand" -> Bk.of("red_sandstone")
        "gravel" -> Bk.of(bands[0])
        else -> b
    }

    private fun grow(canvas: IslandCanvas) {
        val t = terrain
        val growing = features.filter { it.touches(canvas) }
        if (growing.isEmpty()) return
        val grown = growing.map { f -> Growth().also { f.grow(it) } }
        val leafed = HashSet<Long>()
        for (g in grown) for ((p, b) in g.soft) {
            val x = Growth.x(p)
            val y = Growth.y(p)
            val z = Growth.z(p)
            if (!canvas.contains(x, z) || y < t.top(x, z) || claimed(x, z)) continue
            if (!canvas.open(x, y, z)) continue
            canvas.set(x, y, z, b)
            leafed.add(p)
        }
        for ((k, g) in grown.withIndex()) {
            val buried = growing[k].buried
            for ((p, b) in g.hard) {
                val x = Growth.x(p)
                val y = Growth.y(p)
                val z = Growth.z(p)
                if (!canvas.contains(x, z)) continue
                val top = t.top(x, z)
                if (y < top - buried) continue
                if (y >= top && claimed(x, z)) continue
                if (y >= top && !leafed.contains(p) && !canvas.open(x, y, z)) continue
                canvas.set(x, y, z, b)
            }
        }
    }

    /** The grass, flowers and litter on a column of ground, or the shells on a beach. */
    private fun cover(canvas: IslandCanvas, x: Int, z: Int) {
        val t = terrain
        if (!t.dry(x, z) || claimed(x, z)) return
        val g = groundAt(x, z)
        val top = t.top(x, z)
        if (!canvas.open(x, top, z) || canvas.open(x, top - 1, z)) return
        val d = t.distance(x, z)
        val roll = Noise.unit(x, top, z, sCover + 1)
        // Shells (the bleached fans of dead coral) along the warm tide line
        if (g == Ground.SAND && warm && d < 3) {
            if (roll < 0.035) {
                val fan = listOf("dead_tube_coral_fan", "dead_brain_coral_fan", "dead_horn_coral_fan", "dead_fire_coral_fan", "dead_bubble_coral_fan")
                canvas.set(x, top, z, Bk.of(fan[(Noise.unit(x, 3, z, sCover) * fan.size).toInt().coerceAtMost(fan.size - 1)], "waterlogged" to "false"))
            }
            return
        }
        // Fallen leaves under the trees
        if ((g == Ground.GRASS || g == Ground.PODZOL) && climate != Climate.WARM && climate != Climate.FROZEN) {
            val w = woods(x, z)
            if (w > 0.4 && roll < 0.3 * w) {
                val facing = Dir.entries[(Noise.unit(x, 5, z, sCover) * 4).toInt().coerceAtMost(3)]
                canvas.set(x, top, z, Bk.of("leaf_litter", "segment_amount" to "${1 + (Noise.unit(x, 6, z, sCover) * 4).toInt().coerceAtMost(3)}", "facing" to facing.id))
                return
            }
        }
        val lush = (0.35 + 0.8 * Noise.fbm(x / 20.0, z / 20.0, sLush, 3)) * (0.45 + 0.55 * shelter(x, z))
        val flowers = Noise.fbm(x / 16.0, z / 16.0, sFlowers, 2) * shelter(x, z)
        val plant = Plants.cover(climate, g, x, top, z, sCover, lush.coerceIn(0.0, 1.0), flowers, d < 3) ?: return
        if (plant.size == 2 && !canvas.open(x, top + 1, z)) return
        for ((k, b) in plant.withIndex()) canvas.set(x, top + k, z, b)
    }

    /** Vines hanging down the warmer cliffs, from the turf at their tops. */
    private fun vines(canvas: IslandCanvas, x: Int, z: Int) {
        if (cold) return
        val t = terrain
        val foot = max(t.top(x, z), sea)
        for (d in Dir.entries) {
            val wx = x + d.dx
            val wz = z + d.dz
            val wall = t.top(wx, wz)
            if (wall - foot < 4) continue
            val g = groundAt(wx, wz)
            if (g != Ground.GRASS && g != Ground.MOSS && g != Ground.COARSE && g != Ground.BARE) continue
            val roll = Noise.unit(x, d.ordinal, z, sVine)
            if (roll > 0.28) continue
            val length = 2 + (Noise.unit(x, d.ordinal + 4, z, sVine) * 6).toInt()
            var y = wall - 1
            while (y >= foot && y > wall - 1 - length && canvas.open(x, y, z)) {
                canvas.set(x, y, z, Bk.of("vine", d.id to "true"))
                y--
            }
        }
    }

    companion object {
        private val made = object : LinkedHashMap<IslandSpec, IslandPlan>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IslandSpec, IslandPlan>?): Boolean = size > 12
        }

        /** The plan for [spec], made once and kept while chunks are still drawing from it. */
        fun of(spec: IslandSpec): IslandPlan {
            synchronized(made) { made[spec]?.let { return it } }
            val plan = IslandPlan(spec)
            synchronized(made) { return made.getOrPut(spec) { plan } }
        }
    }
}
