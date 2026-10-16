package io.github.vaspyyy.guildmark.client.island

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.ambience.AmbienceLoop
import io.github.vaspyyy.guildmark.island.IslandRegistry
import io.github.vaspyyy.guildmark.island.LighthouseLampBlock
import io.github.vaspyyy.guildmark.island.plan.SoundSpot
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How an island sounds, worked out on each client from where the player is and what the island has on it (the server
 * says which island, and where its own sounds come from). The sea breaks on its shores wherever water meets land near
 * the player: laps running up a beach, waves bursting on rocks, the boom of the sea in a cave, each with its foam or its
 * spray thrown up just as the sound breaks. The wind blows over the high open ground, gusting harder in rain. And each
 * thing on the island makes its own sounds in its own time: a lighthouse turning its lamp all night and sounding its
 * horn in rain and murk, a colony's clamour, a pier's ropes and its boats, a wreck's timbers, a smugglers' den, the
 * chimes and whispers of a drowned shrine.
 */
object IslandAmbience {
    private val random = RandomSource.create()
    private var level: ClientLevel? = null
    private var ticks = 0
    private var visit: IslandClient.Visit? = null

    private val loops = HashMap<String, SpotLoop>()
    private var wind: AmbienceLoop? = null
    /** A bed that wouldn't start (no free channel) isn't tried again until then. */
    private val loopRetry = HashMap<String, Int>()
    /** One-shots still sounding, so the island can't crowd out everything else. */
    private val sounding = ArrayList<SoundInstance>()
    /** When each kind of sound may next sound, in ambience ticks. */
    private val due = HashMap<String, Int>()
    /** Foam and spray waiting for their wave to break. */
    private val effects = ArrayList<Effect>()

    private class Effect(val at: Int, val until: Int, val kind: Int, val x: Double, val y: Double, val z: Double, val dx: Double, val dz: Double)

    fun reset() {
        val manager = Minecraft.getInstance().soundManager
        for (loop in loops.values) manager.stop(loop)
        wind?.let { manager.stop(it) }
        loops.clear()
        wind = null
        loopRetry.clear()
        sounding.clear()
        due.clear()
        effects.clear()
        visit = null
        level = null
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val current = minecraft.level
        val player = minecraft.player
        if (current == null || player == null) {
            if (level != null) reset()
            return
        }
        if (current !== level) {
            reset()
            level = current
        }
        if (minecraft.isPaused) return
        ticks++
        runEffects(current)
        val island = IslandClient.current
        if (island !== visit) {
            // A new island's sounds start afresh; the old one's beds die away on their own
            for (loop in loops.values) loop.target = 0f
            loops.clear()
            due.clear()
            visit = island
        }
        val master = if (minecraft.options.getFinalSoundSourceVolume(SoundSource.AMBIENT) > 0f) share() else 0f
        if (island == null || master <= 0f) {
            for (loop in loops.values) loop.target = 0f
            wind?.target = 0f
            return
        }
        sounding.removeIf { !minecraft.soundManager.isActive(it) }
        surf(current, player, island, master)
        winds(current, player, island, master)
        spots(current, player, island, master)
    }

    // ---- The sea on the shore ----

    private fun surf(level: ClientLevel, player: Player, island: IslandClient.Visit, master: Float) {
        val rain = level.getRainLevel(1f)
        val storm = level.getThunderLevel(1f)
        // Rougher in rain, roughest in a storm
        val rough = 1f + 0.6f * rain + 0.8f * storm
        if (!ready("surf", 18, 40, 1.0 / rough)) return
        if (sounding.count { it.identifier.path.startsWith("island.surf") } >= 3) return
        val sea = island.sea
        val pos = BlockPos.MutableBlockPos()
        repeat(12) {
            val a = random.nextDouble() * Math.PI * 2
            val r = 4 + random.nextDouble() * 22
            val x = Mth.floor(player.x + cos(a) * r)
            val z = Mth.floor(player.z + sin(a) * r)
            if (!level.hasChunkAt(pos.set(x, sea, z))) return@repeat
            if (!level.getFluidState(pos.set(x, sea - 1, z)).`is`(FluidTags.WATER) || !level.getBlockState(pos.set(x, sea, z)).isAir) return@repeat
            // The land it breaks on: the nearest side of it with solid ground at or over the surface
            var landX = 0
            var landZ = 0
            var high = false
            for ((dx, dz) in SIDES) {
                val solid = !level.getBlockState(pos.set(x + dx, sea - 1, z + dz)).let { it.isAir || it.fluidState.`is`(FluidTags.WATER) } ||
                    level.getBlockState(pos.set(x + dx, sea, z + dz)).isSolid
                if (!solid) continue
                landX = dx
                landZ = dz
                high = level.getBlockState(pos.set(x + dx, sea + 2, z + dz)).isSolid
                break
            }
            if (landX == 0 && landZ == 0) return@repeat
            val roof = (1..4).any { level.getBlockState(pos.set(x, sea + it, z)).isSolid }
            val (sound, volume, kind) = when {
                roof -> Triple(IslandRegistry.SURF_CAVE, 1f, CAVE)
                high || storm > 0.3f && random.nextFloat() < 0.4f -> Triple(IslandRegistry.SURF_CRASH, 0.75f + 0.25f * min(1f, rough - 1f), CRASH)
                else -> Triple(IslandRegistry.SURF_LAP, 0.55f + 0.2f * rain, LAP)
            }
            val at = Vec3(x + 0.5 + landX * 0.5, sea - 0.1, z + 0.5 + landZ * 0.5)
            val instance = emit(sound, at, volume * master, 0.92f + random.nextFloat() * 0.16f) ?: return
            // Its foam or spray, just as it breaks: where in the sound that falls is known for each recording
            val marks = MARKS[instance.sound?.location?.path] ?: return
            if (at.distanceToSqr(player.position()) > EFFECTS_RANGE * EFFECTS_RANGE) return
            val first = (marks[0] * 20).toInt()
            val last = if (kind == LAP && marks.size > 1) (marks[1] * 20).toInt() else first + 2
            effects.add(Effect(ticks + first, ticks + last, kind, at.x, at.y, at.z, landX.toDouble(), landZ.toDouble()))
            return
        }
    }

    private fun runEffects(level: ClientLevel) {
        if (effects.isEmpty()) return
        val iterator = effects.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (ticks < e.at) continue
            if (ticks > e.until) {
                iterator.remove()
                continue
            }
            // Across the shore at right angles to the land, and toward it
            val sx = -e.dz
            val sz = e.dx
            when (e.kind) {
                LAP -> {
                    // The wave breaks in a line of foam and runs up the beach until it stalls
                    val run = (ticks - e.at).toDouble() / max(1, e.until - e.at)
                    val n = if (ticks == e.at) 8 else 2
                    for (k in 0 until n) {
                        val s = random.nextGaussian() * 1.6
                        val up = run * 1.2 + random.nextDouble() * 0.3
                        level.addParticle(ParticleTypes.FISHING, e.x + sx * s + e.dx * (up - 0.6), e.y, e.z + sz * s + e.dz * (up - 0.6), e.dx * 0.03, 0.0, e.dz * 0.03)
                        if (ticks == e.at && k % 2 == 0) level.addParticle(ParticleTypes.SPLASH, e.x + sx * s, e.y + 0.1, e.z + sz * s, 0.0, 0.05, 0.0)
                    }
                }
                CRASH -> {
                    // It bursts on the rock: spray thrown up and back, and a drift of fine mist
                    for (k in 0 until 26) {
                        val s = random.nextGaussian() * 1.1
                        level.addParticle(
                            ParticleTypes.SPLASH, e.x + sx * s - e.dx * 0.3, e.y + 0.2 + random.nextDouble() * 0.6, e.z + sz * s - e.dz * 0.3,
                            -e.dx * 0.15 + random.nextGaussian() * 0.05, 0.25 + random.nextDouble() * 0.35, -e.dz * 0.15 + random.nextGaussian() * 0.05,
                        )
                    }
                    for (k in 0 until 5) {
                        val s = random.nextGaussian()
                        level.addParticle(ParticleTypes.CLOUD, e.x + sx * s - e.dx * 0.4, e.y + 0.8 + random.nextDouble(), e.z + sz * s - e.dz * 0.4, -e.dx * 0.03, 0.03, -e.dz * 0.03)
                    }
                }
                CAVE -> {
                    // The sea booms in the cave and breathes spray and mist out of its mouth
                    for (k in 0 until 14) {
                        level.addParticle(
                            ParticleTypes.SPLASH, e.x + random.nextGaussian() * 0.8, e.y + 0.2 + random.nextDouble() * 0.5, e.z + random.nextGaussian() * 0.8,
                            -e.dx * 0.2, 0.15 + random.nextDouble() * 0.2, -e.dz * 0.2,
                        )
                    }
                    for (k in 0 until 6) {
                        level.addParticle(ParticleTypes.CLOUD, e.x + random.nextGaussian() * 0.6, e.y + 0.5 + random.nextDouble() * 0.8, e.z + random.nextGaussian() * 0.6, -e.dx * 0.06, 0.01, -e.dz * 0.06)
                    }
                }
            }
        }
    }

    // ---- The wind over the island ----

    private fun winds(level: ClientLevel, player: Player, island: IslandClient.Visit, master: Float) {
        val rain = level.getRainLevel(1f)
        val storm = level.getThunderLevel(1f)
        // On the land, out under the sky; aboard a ship her own rigging has the wind
        val ground = player.vehicle == null && !player.isInWater && player.y > island.sea + 0.5
        val height = smooth(2.0, 20.0, player.y - island.sea).toFloat()
        val open = if (level.canSeeSky(BlockPos.containing(player.eyePosition))) 1f else 0.25f
        val exposure = if (ground) (0.25f + 0.75f * height) * open else 0f
        val gale = min(1f, 0.55f + 0.35f * rain + 0.4f * storm)
        driveWind(exposure * gale * master * WIND_LEVEL)
        if (exposure < 0.2f || !ready("gust", 240, 700, 1.0 / (1 + rain + storm))) return
        val a = random.nextDouble() * Math.PI * 2
        val at = player.eyePosition.add(cos(a) * 10, 3.0, sin(a) * 10)
        emit(IslandRegistry.WIND_GUST, at, (0.45f + 0.4f * gale) * exposure * master, 0.9f + random.nextFloat() * 0.2f)
    }

    private fun driveWind(target: Float) {
        val manager = Minecraft.getInstance().soundManager
        val loop = wind
        if (loop != null && !loop.isStopped && manager.isActive(loop)) {
            loop.target = target
            return
        }
        wind = null
        if (target < 0.01f || (loopRetry["wind"] ?: 0) > ticks) return
        val fresh = AmbienceLoop(IslandRegistry.WIND_BED.get().location())
        fresh.target = target
        if (manager.play(fresh) == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) loopRetry["wind"] = ticks + 100 else wind = fresh
    }

    // ---- What's on the island ----

    private fun spots(level: ClientLevel, player: Player, island: IslandClient.Visit, master: Float) {
        val night = isNight(level)
        val rain = level.getRainLevel(1f)
        val storm = level.getThunderLevel(1f)
        val eye = player.eyePosition
        for ((i, s) in island.spots.withIndex()) {
            val spot = SoundSpot.entries.getOrNull(s.spot) ?: continue
            val at = Vec3(s.x + 0.5, s.y + 0.5, s.z + 0.5)
            val d = sqrt(at.distanceToSqr(eye))
            val key = "$i"
            when (spot) {
                SoundSpot.LIGHTHOUSE -> {
                    val lamp = level.getBlockState(BlockPos(s.x, s.y, s.z))
                    val lit = lamp.`is`(IslandRegistry.LIGHTHOUSE_LAMP.get()) && lamp.getValue(LighthouseLampBlock.LIT)
                    drive("turn$key", IslandRegistry.LIGHTHOUSE_TURN, at, if (lit && d < 40) 0.7f * master else 0f)
                    // The horn through rain and murk by night, and now and then on a clear one; the fog bell by day in rain
                    if (lit && d < 160 && ready("horn$key", if (rain > 0.2f) 500 else 1400, if (rain > 0.2f) 900 else 2600)) {
                        emit(IslandRegistry.LIGHTHOUSE_HORN, at, master, 0.98f + random.nextFloat() * 0.04f)
                    }
                    if (!lit && rain > 0.3f && d < 80 && ready("fogbell$key", 300, 600)) emit(IslandRegistry.LIGHTHOUSE_BELL, at, 0.8f * master, 1f)
                }
                SoundSpot.COLONY -> {
                    drive("colony$key", IslandRegistry.GULL_COLONY, at, if (d < 64) (if (night) 0.25f else 0.9f) * (1f - 0.4f * rain) * master else 0f)
                    if (!night && d < 40 && ready("squabble$key", 160, 420)) emit(IslandRegistry.GULL_SQUABBLE, around(at, 5.0), 0.6f * master, 0.9f + random.nextFloat() * 0.2f)
                }
                SoundSpot.PIER -> if (d < 24 && ready("rope$key", 120, 300, 1.0 / (1 + rain))) emit(IslandRegistry.HAVEN_ROPE, at, 0.5f * master, 0.9f + random.nextFloat() * 0.2f)
                SoundSpot.MOORING -> if (d < 28 && ready("boats$key", 160, 380, 1.0 / (1 + 0.5 * rain))) emit(IslandRegistry.HAVEN_BOATS, around(at, 2.0), 0.55f * master, 0.9f + random.nextFloat() * 0.2f)
                SoundSpot.HULL -> if (d < 36 && ready("hull$key", 100, 240, 1.0 / (1 + storm))) emit(IslandRegistry.WRECK_CREAK, around(at, 3.0), 0.7f * master, 0.85f + random.nextFloat() * 0.25f)
                SoundSpot.SAIL -> if (d < 28 && ready("sail$key", 80, 220, 1.0 / (1 + rain + storm))) emit(IslandRegistry.WRECK_SAIL, at, 0.6f * master, 0.9f + random.nextFloat() * 0.2f)
                SoundSpot.SHIP_BELL -> if (d < 50 && ready("bell$key", 400, 1000, if (night) 0.6 else 1.0)) emit(IslandRegistry.WRECK_BELL, at, 0.7f * master, 0.95f + random.nextFloat() * 0.1f)
                SoundSpot.CAVE -> if (d < 22 && ready("drip$key", 30, 110)) emit(IslandRegistry.COVE_DRIP, around(at, 4.0), (0.4f + 0.3f * random.nextFloat()) * master, 0.85f + random.nextFloat() * 0.3f)
                // The den's folk, while there are any about it
                SoundSpot.CAMP -> if (d < 18 && ready("camp$key", 100, 220) && folkNear(level, at)) emit(IslandRegistry.COVE_CAMP, at, 0.6f * master, 1f)
                SoundSpot.LOOKOUT -> if (night && d < 40 && ready("signal$key", 600, 1400) && folkNear(level, at)) emit(IslandRegistry.COVE_SIGNAL, at, 0.7f * master, 0.95f + random.nextFloat() * 0.1f)
                SoundSpot.SHRINE -> {
                    drive("drone$key", IslandRegistry.SHRINE_DRONE, at, if (d < 36) (if (night) 0.8f else 0.45f) * master else 0f)
                    if (d < 28 && ready("chime$key", 160, 420)) emit(IslandRegistry.SHRINE_CHIME, around(at, 2.0), 0.6f * master, 0.95f + random.nextFloat() * 0.1f)
                    if (night && d < 14 && ready("whisper$key", 300, 800)) emit(IslandRegistry.SHRINE_WHISPER, around(at, 3.0), 0.7f * master, 1f)
                }
                SoundSpot.BUBBLES -> {
                    if (d < 24 && ready("bubbles$key", 60, 200)) emit(IslandRegistry.SHRINE_BUBBLES, at, 0.6f * master, 0.9f + random.nextFloat() * 0.2f)
                    // And there they are, rising and popping at the surface
                    if (d < 32 && random.nextFloat() < 0.5f) {
                        val bx = at.x + random.nextGaussian() * 0.8
                        val bz = at.z + random.nextGaussian() * 0.8
                        level.addParticle(ParticleTypes.BUBBLE_COLUMN_UP, bx, at.y - 2 + random.nextDouble() * 2, bz, 0.0, 0.1, 0.0)
                        if (random.nextFloat() < 0.4f) level.addParticle(ParticleTypes.BUBBLE_POP, bx, (visit?.sea ?: s.y) - 0.05, bz, 0.0, 0.02, 0.0)
                    }
                }
            }
        }
    }

    // ---- Helpers ----

    private fun folkNear(level: ClientLevel, at: Vec3): Boolean =
        level.getEntitiesOfClass(Monster::class.java, AABB.ofSize(at, 32.0, 16.0, 32.0)) { it.isAlive }.isNotEmpty()

    private fun around(at: Vec3, spread: Double): Vec3 = at.add(random.nextGaussian() * spread * 0.5, random.nextGaussian() * spread * 0.2, random.nextGaussian() * spread * 0.5)

    private fun drive(key: String, sound: Holder<SoundEvent>, at: Vec3, target: Float) {
        val manager = Minecraft.getInstance().soundManager
        val loop = loops[key]
        if (loop != null && !loop.isStopped && manager.isActive(loop)) {
            loop.target = target
            return
        }
        loops.remove(key)
        if (target < 0.01f || (loopRetry[key] ?: 0) > ticks) return
        val fresh = SpotLoop(sound.value(), at)
        fresh.target = target
        if (manager.play(fresh) == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) loopRetry[key] = ticks + 100 else loops[key] = fresh
    }

    private fun emit(sound: Holder<SoundEvent>, at: Vec3, volume: Float, pitch: Float): SimpleSoundInstance? {
        if (volume < 0.01f || sounding.size >= MAX_SOUNDING) return null
        val instance = SimpleSoundInstance(sound.value(), SoundSource.AMBIENT, volume, pitch, SoundInstance.createUnseededRandom(), at.x, at.y, at.z)
        Minecraft.getInstance().soundManager.play(instance)
        sounding.add(instance)
        return instance
    }

    /** Whether a kind of sound is due; if so, when it's next due is drawn from [min] to [max] ticks on (times [scale]). */
    private fun ready(kind: String, min: Int, max: Int, scale: Double = 1.0): Boolean {
        val at = due[kind]
        if (at == null) {
            // Not all at once on arriving
            due[kind] = ticks + ((min + random.nextInt(max - min + 1)) * scale * random.nextDouble()).toInt()
            return false
        }
        if (ticks < at) return false
        due[kind] = ticks + ((min + random.nextInt(max - min + 1)) * scale).toInt()
        return true
    }

    private fun isNight(level: ClientLevel): Boolean {
        val time = level.overworldClockTime % 24000L
        return time in 12800L..23200L
    }

    private fun share(): Float = try {
        GuildmarkClientConfig.ISLAND_SOUNDS.get() / 100f
    } catch (e: IllegalStateException) {
        1f
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private const val LAP = 0
    private const val CRASH = 1
    private const val CAVE = 2
    private const val MAX_SOUNDING = 10
    private const val WIND_LEVEL = 0.5f
    /** Foam and spray are only thrown up this close to the player (blocks). */
    private const val EFFECTS_RANGE = 40.0

    /**
     * When in each surf recording its wave breaks (seconds): a lap's break and where its wash stalls on the beach, a
     * crash's and a cave's impact. From art/audio/make_island_audio.py.
     */
    private val MARKS: Map<String, DoubleArray> = mapOf(
        "island/surf_lap_1" to doubleArrayOf(0.409, 1.694),
        "island/surf_lap_2" to doubleArrayOf(0.343, 1.298),
        "island/surf_lap_3" to doubleArrayOf(0.472, 2.046),
        "island/surf_lap_4" to doubleArrayOf(0.384, 1.607),
        "island/surf_lap_5" to doubleArrayOf(0.428, 1.775),
        "island/surf_lap_6" to doubleArrayOf(0.5, 2.014),
        "island/surf_crash_1" to doubleArrayOf(0.742),
        "island/surf_crash_2" to doubleArrayOf(0.549),
        "island/surf_crash_3" to doubleArrayOf(0.895),
        "island/surf_crash_4" to doubleArrayOf(0.699),
        "island/surf_crash_5" to doubleArrayOf(0.75),
        "island/surf_crash_6" to doubleArrayOf(0.795),
        "island/surf_cave_1" to doubleArrayOf(0.655),
        "island/surf_cave_2" to doubleArrayOf(0.562),
        "island/surf_cave_3" to doubleArrayOf(0.741),
        "island/surf_cave_4" to doubleArrayOf(0.654),
    )
}
