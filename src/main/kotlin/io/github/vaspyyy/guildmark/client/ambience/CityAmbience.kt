package io.github.vaspyyy.guildmark.client.ambience

import io.github.vaspyyy.guildmark.ambience.District
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.ambience.Surroundings.Companion.smoothstep
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.ModConfigSpec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sounds of towns and cities, made on this player's side alone: bells striking the hour, the crowd and
 * the market criers by day, smiths at their anvils, the harbour's water and gulls, the wind high on the walls,
 * and the watch calling the hours at night. All of it follows what's around the player (Surroundings) and the
 * time of day, and Guildmark's settings say how loud each part is. A capital can say more about where the
 * player stands (CityDistricts, sent from the server), which colours the rest.
 */
object CityAmbience {
    private val around = Surroundings()
    private val bells = Bells()
    private val random = RandomSource.create()
    private var level: ClientLevel? = null
    private var ticks = 0

    private val loops = HashMap<Identifier, AmbienceLoop>()
    /** A bed that wouldn't start (no free channel) isn't tried again until then. */
    private val loopRetry = HashMap<Identifier, Int>()
    /** One-shots still sounding, so a busy moment can't crowd out everything else. */
    private val sounding = ArrayList<SoundInstance>()

    /** When each kind of one-shot may next sound, in ambience ticks. */
    private val due = HashMap<String, Int>()
    private val stationDue = HashMap<BlockPos, Int>()
    /** Where each smithy is in its round of work: anvil phrases left in this heat, or a step of the reheat (below 0). */
    private val heats = HashMap<BlockPos, Int>()
    private var watchCall: Pair<Int, Identifier>? = null
    private var watchHour = -1

    /** What the server last said of where the player stands: in a capital at all, and in which district. */
    private var capital = false
    private var district = District.NONE

    // How the place sounds just now, 0 to 1 each, before the settings' volumes
    private var harbour = 0.0
    private var wind = 0.0
    private var bustle = 0.0

    fun hint(capital: Boolean, district: District) {
        this.capital = capital
        // A village is told with capital = false; its district is kept so the town's crowd is turned down in it (VillageAmbience has the rest)
        this.district = if (capital || district == District.VILLAGE || district == District.VILLAGE_MARKET) district else District.NONE
        VillageAmbience.hint(capital, district)
    }

    /** Whether the player stands in a village, where the town's crowd is thin, with no carts rattling and no criers but on its market day. */
    private val country: Boolean get() = district == District.VILLAGE || district == District.VILLAGE_MARKET

    fun reset() {
        val manager = Minecraft.getInstance().soundManager
        for (loop in loops.values) manager.stop(loop)
        loops.clear()
        loopRetry.clear()
        sounding.clear()
        due.clear()
        stationDue.clear()
        heats.clear()
        watchCall = null
        watchHour = -1
        around.reset()
        bells.reset()
        level = null
        capital = false
        district = District.NONE
        harbour = 0.0
        wind = 0.0
        bustle = 0.0
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
        val master = share(GuildmarkClientConfig.CITY_SOUNDS)
        // Nowhere a town keeps hours (the Nether, the End), or nobody wants to hear it
        if (current.dimensionType().hasFixedTime() || master <= 0f || minecraft.options.getFinalSoundSourceVolume(SoundSource.AMBIENT) <= 0f) {
            hush()
            return
        }
        around.tick(current, player, ticks)
        if (around.sweeps == 0) return
        val hour = clock(current)
        bells.tick(current, player, ticks, master * share(GuildmarkClientConfig.CITY_BELLS) * BELL_LEVEL, capital)
        if (ticks % 5 == 0) beds(current, player, hour, master)
        sounding.removeIf { !minecraft.soundManager.isActive(it) }
        crowd(current, player, hour, master * share(GuildmarkClientConfig.CITY_CROWDS))
        workshops(player, hour, master * share(GuildmarkClientConfig.CITY_WORKSHOPS))
        waterside(player, hour, master * share(GuildmarkClientConfig.CITY_HARBOUR))
        walls(current, player, master * share(GuildmarkClientConfig.CITY_WIND))
    }

    // ---- The beds ----

    private fun beds(level: ClientLevel, player: Player, hour: Double, master: Float) {
        val found = around.found
        val urban = urbanity()
        val rain = level.getRainLevel(1f).toDouble()
        val storm = level.getThunderLevel(1f).toDouble()
        val under = player.isUnderWater
        // Indoors the street still comes through, softer
        val muffle = if (under) 0.0 else 0.35 + 0.65 * around.openness

        // The crowd: how many folk are about, how busy the hour is, and how big the town
        bustle = bustleAt(hour) * (1 - 0.6 * rain)
        var crowd = (around.awake.size + urban * 3 + (if (capital) 4 else 0)) * bustle
        if (district == District.MARKET) crowd += 6 * bustle
        if (district == District.QUIET) crowd *= 0.35
        if (country) crowd *= 0.4
        val light = smoothstep(0.5, 4.0, crowd) * (1 - 0.6 * smoothstep(6.0, 14.0, crowd))
        val busy = smoothstep(6.0, 16.0, crowd)
        val crowds = master * share(GuildmarkClientConfig.CITY_CROWDS) * muffle
        drive(CitySounds.CROWD_LIGHT, (light * crowds * CROWD_LEVEL).toFloat())
        drive(CitySounds.CROWD_DAY, (busy * crowds * CROWD_LEVEL).toFloat())

        // The harbour: plenty of water, a quay near, and a town on it
        val water = smoothstep(24.0, 220.0, found.waterSurface.toDouble())
        val quay = found.shore.list.minOfOrNull { it.distToCenterSqr(player.position()) }?.let { 1 - smoothstep(6.0, 30.0, sqrt(it)) } ?: 0.0
        val town = if (district == District.HARBOUR) 1.0 else smoothstep(0.12, 0.4, urban)
        harbour = water * max(quay, 0.35) * town * (if (hour < 5.5 || hour >= 20.5) 0.85 else 1.0)
        drive(CitySounds.HARBOUR_WATER, (harbour * master * share(GuildmarkClientConfig.CITY_HARBOUR) * muffle * HARBOUR_LEVEL).toFloat())

        // The wind: high over the ground round about, out under the sky, on something built
        val exposed = smoothstep(4.0, 13.0, around.drop) * around.openness * around.openness
        val walled = around.built || urban > 0.15
        wind = if (under || !walled) 0.0 else exposed
        if (district == District.WALLS && !under) wind = max(wind, 0.6 * around.openness)
        val gale = 1 + 0.5 * rain + 0.6 * storm
        drive(CitySounds.WIND_WALLS, (wind * min(1.0, 0.75 * gale) * master * share(GuildmarkClientConfig.CITY_WIND) * WIND_LEVEL).toFloat())
    }

    private fun drive(sound: Identifier, target: Float) {
        val manager = Minecraft.getInstance().soundManager
        val loop = loops[sound]
        if (loop != null && !loop.isStopped && manager.isActive(loop)) {
            loop.target = target
            return
        }
        loops.remove(sound)
        if (target < 0.01f || (loopRetry[sound] ?: 0) > ticks) return
        val fresh = AmbienceLoop(sound)
        fresh.target = target
        if (manager.play(fresh) == SoundEngine.PlayResult.NOT_STARTED) loopRetry[sound] = ticks + 100
        else loops[sound] = fresh
    }

    private fun hush() {
        for (loop in loops.values) loop.target = 0f
    }

    // ---- The folk ----

    private fun crowd(level: ClientLevel, player: Player, hour: Double, volume: Float) {
        if (volume <= 0f) return
        val rain = level.getRainLevel(1f)
        val busy = bustle * smoothstep(1.0, 8.0, around.awake.size + urbanity() * 4)
        // Criers calling their wares across the square, from one of the folk about (in a village only on its market day)
        if (ready("crier", 200, 480) && hour in 7.5..17.5 && busy > 0.25 && rain < 0.4f && district != District.QUIET && district != District.VILLAGE) {
            if (district == District.MARKET) due["crier"] = ticks + range(120, 300)
            folkBetween(player, 5.0, 28.0)?.let { emit(CitySounds.HAWKER, it.eyePosition, volume * 0.85f * outside(), pitch(0.04f)) }
        }
        // Coin changing hands at a stall
        if (ready("coins", 160, 400) && hour in 7.0..18.0 && busy > 0.2) {
            folkBetween(player, 2.0, 14.0)?.let { emit(CitySounds.COINS, it.position().add(0.0, 1.0, 0.0), volume * 0.6f, pitch(0.05f)) }
        }
        // A cart rattling down a street somewhere near
        if (ready("cart", 700, 1600) && hour in 7.0..19.0 && urbanity() > 0.5 && rain < 0.6f && district != District.QUIET && !country) {
            ground(level, player, 12.0, 26.0)?.let { emit(CitySounds.CART, it, volume * 0.8f * outside(), pitch(0.05f)) }
        }
        // The watch calls the hour through the night, a little after it turns: always at midnight, otherwise
        // only now and then (a Minecraft hour is under a minute)
        val clockHour = hour.toInt()
        if (clockHour != watchHour) {
            if (watchHour >= 0 && (urbanity() > 0.3 || capital) && (clockHour == 0 || random.nextFloat() < WATCH_CHANCE)) {
                CitySounds.WATCH[clockHour]?.let { watchCall = ticks + range(60, 400) to it }
            }
            watchHour = clockHour
        }
        watchCall?.let { (at, sound) ->
            if (ticks >= at) {
                watchCall = null
                ground(level, player, 18.0, 34.0)?.let { emit(sound, it, volume * outside()) }
            }
        }
    }

    // ---- The workshops ----

    private fun workshops(player: Player, hour: Double, volume: Float) {
        if (volume <= 0f || ticks % 10 != 0 || hour < 6.0 || hour >= 19.5) return
        val found = around.found
        val villagers = around.awake.filter { it.type == EntityTypes.VILLAGER }
        if (villagers.isEmpty()) return
        // A workshop sounds only while one of vanilla's villagers is at it (Guildmark's own townsfolk make their
        // own work sounds), and only the two nearest at once
        fun manned(pos: BlockPos) = villagers.any { abs(it.x - pos.x - 0.5) < 5 && abs(it.z - pos.z - 0.5) < 5 && abs(it.y - pos.y) < 3 }
        val near = player.position()
        val stations = (found.smithies.list + found.grindstones.list + found.carpentry.list)
            .filter { it.distToCenterSqr(near) < WORKSHOP_REACH * WORKSHOP_REACH && manned(it) }
            .sortedBy { it.distToCenterSqr(near) }
            .take(2)
        stationDue.keys.retainAll(stations.toSet())
        heats.keys.retainAll(stations.toSet())
        for (pos in stations) {
            val next = stationDue.getOrPut(pos) { ticks + range(10, 80) }
            if (ticks < next) continue
            val work = when (pos) {
                // The steady sounds sit a little under the anvil's ring, which is louder for its peaks
                in found.grindstones.list -> Work(CitySounds.GRINDSTONE, range(400, 800), 0.78f, 0.04f)
                in found.carpentry.list ->
                    if (random.nextBoolean()) Work(CitySounds.SAW, range(200, 400), 0.9f, 0.04f)
                    else Work(CitySounds.WOOD_KNOCK, range(120, 300), 0.9f, 0.05f)
                else -> smithy(pos)
            }
            stationDue[pos] = ticks + work.wait
            work.sound?.let { emit(it, Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), volume * 0.9f * work.level, pitch(work.spread)) }
        }
    }

    /** What a workshop does next: a sound (or none, for a pause), the ticks until it's at work again, how loud and how far its pitch may stray. */
    private class Work(val sound: Identifier?, val wait: Int, val level: Float, val spread: Float)

    /**
     * A smith's round: a heat's worth of work at the anvil (three to six phrases a few seconds apart), then
     * sometimes the hiss of the quench, and back into the fire with a pump or two of the bellows.
     */
    private fun smithy(pos: BlockPos): Work {
        val left = heats.getOrPut(pos) { range(3, 6) }
        return when {
            left > 0 -> {
                heats[pos] = left - 1
                // The anvils' own pitches differ already, so only a little more
                Work(CitySounds.ANVIL_WORK, range(80, 160), 1f, 0.03f)
            }
            left == 0 && random.nextBoolean() -> {
                heats[pos] = -1
                Work(CitySounds.QUENCH, range(100, 200), 0.8f, 0.04f)
            }
            left == 0 || left == -1 -> {
                heats[pos] = -2
                Work(CitySounds.BELLOWS, range(140, 300), 0.78f, 0.04f)
            }
            else -> {
                heats[pos] = range(3, 6)
                Work(if (random.nextBoolean()) CitySounds.BELLOWS else null, range(160, 300), 0.78f, 0.04f)
            }
        }
    }

    // ---- The waterside ----

    private fun waterside(player: Player, hour: Double, volume: Float) {
        if (volume <= 0f || harbour < 0.15) return
        val found = around.found
        val level = level ?: return
        val rain = level.getRainLevel(1f)
        val near = player.position()
        // Gulls wheeling over the water by day
        if (ready("gull", 160, 500, 1.5 / (0.5 + harbour)) && hour in 5.5..19.5 && rain < 0.5f) {
            val over = (found.openWater.pick(random) ?: found.shore.pick(random))
            if (over != null && horizontal(over, near) > 8) {
                // The gulls are the densest of the harbour's sounds, so they play softer than the rest
                emit(CitySounds.GULL, Vec3.atCenterOf(over).add(0.0, 6.0 + random.nextDouble() * 10, 0.0), volume * 0.55f, pitch(0.05f))
            }
        }
        // A wave slapping at the quay, oftener in rough weather
        if (ready("splash", 200, 600, 1 / (1.0 + rain)) && harbour > 0.25) {
            found.shore.list.filter { it.distToCenterSqr(near) < 16.0 * 16.0 }.randomOrNull(random)?.let {
                emit(CitySounds.SPLASH, Vec3.atCenterOf(it), volume * 0.8f, pitch(0.06f))
            }
        }
        // Moorings and jetty timbers creaking
        if (ready("creak", 140, 320)) {
            found.piers.list.filter { it.distToCenterSqr(near) < 18.0 * 18.0 }.randomOrNull(random)?.let {
                emit(CitySounds.CREAK, Vec3.atCenterOf(it).add(0.0, 1.0, 0.0), volume * 0.7f, pitch(0.08f))
            }
        }
        // Now and then a ship's bell out in the harbour
        if (ready("ship_bell", 1400, 3200) && harbour > 0.5 && urbanity() > 0.4 && hour in 6.0..21.0) {
            found.openWater.list.filter { horizontal(it, near) > 16 }.randomOrNull(random)?.let {
                emit(CitySounds.SHIP_BELL, Vec3.atCenterOf(it).add(0.0, 2.0, 0.0), volume * 0.75f)
            }
        }
    }

    // ---- The walls ----

    private fun walls(level: ClientLevel, player: Player, volume: Float) {
        if (volume <= 0f) return
        val gale = 1 + 0.5 * level.getRainLevel(1f) + 0.6 * level.getThunderLevel(1f)
        // Gusts sweeping over the battlements from somewhere upwind
        if (ready("gust", 300, 800, 1 / gale) && wind > 0.3) {
            val a = random.nextDouble() * Math.PI * 2
            // A gust is louder than the wind under it, so it's kept a little down
            emit(CitySounds.GUST, player.position().add(cos(a) * 10, 2.0, sin(a) * 10), volume * (0.5f + 0.3f * wind.toFloat()), pitch(0.05f))
        }
        // Banners cracking in the wind
        if (ready("flag", 40, 140, 1 / gale) && (wind > 0.15 || district == District.WALLS)) {
            around.found.banners.list.filter { it.distToCenterSqr(player.position()) < 16.0 * 16.0 }.randomOrNull(random)?.let {
                emit(CitySounds.FLAG, Vec3.atCenterOf(it), volume * (0.5f + 0.5f * max(wind, 0.3).toFloat()), pitch(0.06f))
            }
        }
    }

    // ---- Helpers ----

    private fun urbanity(): Double = if (capital) max(around.urbanity, 0.75) else around.urbanity

    /** Whether a kind of one-shot is due; if so, when it's next due is drawn from [min] to [max] ticks on (times [scale]). */
    private fun ready(kind: String, min: Int, max: Int, scale: Double = 1.0): Boolean {
        val at = due[kind]
        if (at == null) {
            // Not all at once on arriving somewhere
            due[kind] = ticks + (range(min, max) * scale * random.nextDouble()).toInt()
            return false
        }
        if (ticks < at) return false
        due[kind] = ticks + (range(min, max) * scale).toInt()
        return true
    }

    private fun emit(sound: Identifier, at: Vec3, volume: Float, pitch: Float = 1f) {
        if (sounding.size >= MAX_SOUNDING) return
        CitySounds.play(sound, at, volume, pitch)?.let { sounding.add(it) }
    }

    private fun range(min: Int, max: Int): Int = min + random.nextInt(max - min + 1)

    private fun pitch(spread: Float): Float = 1f + (random.nextFloat() * 2 - 1) * spread

    /** Positional sounds from out in the street come through a little softer indoors. */
    private fun outside(): Float = (0.5 + 0.5 * around.openness).toFloat()

    private fun horizontal(pos: BlockPos, from: Vec3): Double {
        val dx = pos.x + 0.5 - from.x
        val dz = pos.z + 0.5 - from.z
        return sqrt(dx * dx + dz * dz)
    }

    /** One of vanilla's villagers or traders [from] to [to] blocks off: Guildmark's own townsfolk and characters call out for themselves. */
    private fun folkBetween(player: Player, from: Double, to: Double) =
        around.awake.filter { plain(it) && it.distanceToSqr(player) in from * from..to * to }.randomOrNull(random)

    private fun plain(entity: LivingEntity) = entity.type == EntityTypes.VILLAGER || entity.type == EntityTypes.WANDERING_TRADER

    /** Somewhere on the ground [from] to [to] blocks off in a random direction, at head height. */
    private fun ground(level: ClientLevel, player: Player, from: Double, to: Double): Vec3? {
        val a = random.nextDouble() * Math.PI * 2
        val r = from + random.nextDouble() * (to - from)
        val x = player.x + cos(a) * r
        val z = player.z + sin(a) * r
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
        if (abs(y - player.y) > 24) return null
        return Vec3(x, y + 1.6, z)
    }

    private fun <T> List<T>.randomOrNull(random: RandomSource): T? = if (isEmpty()) null else this[random.nextInt(size)]

    private fun share(value: ModConfigSpec.IntValue): Float = try {
        value.get() / 100f
    } catch (e: IllegalStateException) {
        1f
    }

    /** The hour by the clock, 0 to 24: Minecraft's day begins at six in the morning. */
    private fun clock(level: ClientLevel): Double = ((level.overworldClockTime % 24000L) / 1000.0 + 6.0) % 24.0

    fun hourOf(time: Long): Int = ((time % 24000L) / 1000L + 6).toInt() % 24

    /** How busy the streets are at an hour: empty in the small hours, full from mid-morning to late afternoon. */
    private fun bustleAt(hour: Double): Double {
        val points = BUSTLE
        for (i in 1 until points.size) {
            val (h1, v1) = points[i]
            val (h0, v0) = points[i - 1]
            if (hour <= h1) return v0 + (v1 - v0) * (hour - h0) / (h1 - h0)
        }
        return points.last().second
    }

    private val BUSTLE = listOf(0.0 to 0.05, 5.0 to 0.05, 7.0 to 0.6, 9.0 to 1.0, 17.0 to 1.0, 20.0 to 0.45, 22.0 to 0.1, 24.0 to 0.05)

    /** How loud each bed and the bells play at full settings, so they sit under the game rather than on it. */
    private const val CROWD_LEVEL = 0.8
    private const val HARBOUR_LEVEL = 0.75
    private const val WIND_LEVEL = 0.8
    private const val BELL_LEVEL = 1.0f
    private const val WORKSHOP_REACH = 24.0
    private const val WATCH_CHANCE = 0.5f
    private const val MAX_SOUNDING = 8
}
