package io.github.vaspyyy.guildmark.client.ambience

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.ambience.District
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.ambience.Surroundings.Companion.smoothstep
import io.github.vaspyyy.guildmark.client.season.SeasonClient
import io.github.vaspyyy.guildmark.townsfolk.Days
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.ModConfigSpec
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sounds of a village, made on this player's side alone, a sibling of [CityAmbience] that shares its [AmbienceLoop] (the
 * beds) and [CitySounds.play] (the one-shots) and counts the folk about as [Surroundings] does
 * (living-world/villages/design.md 16.1 and 17.3). It is quieter and sparser than the capital's: fewer folk, more country.
 *
 * - **By day** the breeze in the dry stalks with hens, sparrows and a cart going by on the lane in it
 *   (`ambience.village_day`), birdsong over it (`village_birds`: loudest in the morning chorus and again toward evening, thin in
 *   winter, silent in the rain), and now and then a hammer (not on the rest day) or a child calling, heard far across the
 *   fields (`hammer_far`, `child_far`). They come as one-shots, placed somewhere new each time, so they never come round on
 *   the bed's beat.
 * - **By night** the air over the thatch with an owl in it (`village_night`), crickets (`village_crickets`, only in the warm
 *   seasons, and not in the rain), and a dog far off now and then (`dog_far`). Day and night cross over through the twilights,
 *   dusk with the hens going in and dawn with the first birds.
 * - **On the village's market day**, from the stalls' opening to their closing, a small crowd's murmur with a coin, a bell and a
 *   barrow's squeak in it (`village_market`), as loud as the folk about the player are many: with nobody near, it stays quiet.
 *   The city's crier calls there on that day too (CityAmbience).
 *
 * The server says where the player stands (village/live/VillageVisits, by [CityAmbience.hint]): in a village or not, and whether
 * it is the market. Nothing here starts until the first word that the player is in one, so with Living Villages off nothing
 * runs. Guildmark's settings say how loud it all is (`villageSounds`), and the city's crowd under it is turned down while the
 * player is in a village (CityAmbience).
 */
object VillageAmbience {
    private fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ambience.$name")

    // Beds, played unpositioned under everything
    private val DAY = id("village_day")
    private val NIGHT = id("village_night")
    private val BIRDS = id("village_birds")
    private val CRICKETS = id("village_crickets")
    private val MARKET = id("village_market")

    // One-shots, heard far off across the fields
    private val HAMMER_FAR = id("hammer_far")
    private val CHILD_FAR = id("child_far")
    private val DOG_FAR = id("dog_far")

    private val random = RandomSource.create()
    private var level: ClientLevel? = null
    private var ticks = 0
    private var armed = false

    private val loops = HashMap<Identifier, AmbienceLoop>()
    /** A bed that wouldn't start (no free channel) isn't tried again until then. */
    private val loopRetry = HashMap<Identifier, Int>()
    /** One-shots still sounding, so a busy moment can't crowd out everything else. */
    private val sounding = ArrayList<SoundInstance>()
    /** When each kind of one-shot may next sound, in ambience ticks. */
    private val due = HashMap<String, Int>()

    /** What the server last said of where the player stands: in a village at all, and whether it is open for its market. */
    private var here = false
    private var market = false

    /** The folk awake within [FOLK_NEAR] blocks, counted once a second, and how much open sky there is about the player's head. */
    private var folk = 0
    private var openness = 1.0

    /** The server's word on where the player stands (see CityAmbience.hint): the first that a village is round them starts the sounds. */
    fun hint(capital: Boolean, district: District) {
        here = !capital && (district == District.VILLAGE || district == District.VILLAGE_MARKET)
        market = here && district == District.VILLAGE_MARKET
        if (here) arm()
    }

    /** Whether the player is in a village, as the server last said. */
    fun inVillage(): Boolean = here

    /** Hooks the tick up, once, the first time a village is told; a world with no village in it, or with Living Villages off, never does. */
    private fun arm() {
        if (armed) return
        armed = true
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
    }

    /** Stops every bed and forgets the timers; [all] too what the server said (the player has left the world). */
    private fun reset(all: Boolean) {
        val manager = Minecraft.getInstance().soundManager
        for (loop in loops.values) manager.stop(loop)
        loops.clear()
        loopRetry.clear()
        sounding.clear()
        due.clear()
        folk = 0
        openness = 1.0
        level = null
        if (all) {
            here = false
            market = false
        }
    }

    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val current = minecraft.level
        val player = minecraft.player
        if (current == null || player == null) {
            if (level != null) reset(true)
            return
        }
        if (current !== level) {
            reset(false)
            level = current
        }
        if (minecraft.isPaused) return
        ticks++
        val master = share(GuildmarkClientConfig.VILLAGE_SOUNDS)
        // Not in a village, nowhere a day keeps hours (the Nether, the End), or nobody wants to hear it
        if (!here || current.dimensionType().hasFixedTime() || master <= 0f || minecraft.options.getFinalSoundSourceVolume(SoundSource.AMBIENT) <= 0f) {
            hush()
            return
        }
        if (ticks % 10 == 3) feelThePlace(current, player)
        if (ticks % 20 == 7) countFolk(current, player)
        val hour = clock(current)
        if (ticks % 5 == 0) beds(current, player, hour, master)
        sounding.removeIf { !minecraft.soundManager.isActive(it) }
        oneShots(current, player, hour, master)
    }

    // ---- The beds ----

    private fun beds(level: ClientLevel, player: Player, hour: Double, master: Float) {
        val rain = level.getRainLevel(1f).toDouble()
        val storm = level.getThunderLevel(1f).toDouble()
        // Indoors the country still comes through, softer
        val muffle = if (player.isUnderWater) 0.0 else 0.35 + 0.65 * openness
        val out = master * muffle
        val warm = warmth()
        val day = daylight(hour)
        // The two cross over through the twilights with their powers adding to one, so there is no dip between them
        val byDay = sqrt(day)
        val byNight = sqrt(1.0 - day)

        // The country by day (the hens roost at dusk and are heard again at first light) and by night; a storm's wind covers them
        val wet = 1 - 0.4 * rain - 0.25 * storm
        drive(DAY, (byDay * wet * (0.85 + 0.15 * warm) * out * DAY_LEVEL).toFloat())
        drive(NIGHT, (byNight * (1 - 0.3 * rain + 0.3 * storm) * out * NIGHT_LEVEL).toFloat())

        // Birds: the morning chorus and the evening song, fewer through the cold months (sparrows stay), none in the rain
        val birds = birdsAt(hour) * (0.3 + 0.7 * smoothstep(0.2, 0.6, warm)) * (1 - 0.95 * rain) * (1 - 0.8 * storm)
        drive(BIRDS, (birds * out * BIRDS_LEVEL).toFloat())

        // Crickets: warm nights only, from dusk until the first birds, hushed by rain
        val crickets = cricketsAt(hour) * smoothstep(0.5, 0.78, warm) * (1 - 0.95 * rain)
        drive(CRICKETS, (crickets * out * CRICKETS_LEVEL).toFloat())

        // The market: its day's hours, as loud as the crowd about the player (nobody near, nothing)
        val busy = smoothstep(1.5, 9.0, folk.toDouble())
        val open = if (market) smoothstep(7.6, 8.4, hour) * (1 - smoothstep(15.6, 16.4, hour)) else 0.0
        drive(MARKET, (open * busy * (1 - 0.8 * rain) * out * MARKET_LEVEL).toFloat())
    }

    /** Whether it is day, 0 to 1: first light about five, full day by half past six, dimming from half past six in the evening to dark by half past eight. */
    private fun daylight(hour: Double): Double = smoothstep(5.0, 6.6, hour) * (1 - smoothstep(18.4, 20.4, hour))

    /**
     * The birds' share of the day, 0 to 1: they begin before the sun (the blackbird first), sing hardest from sunrise to nine, go
     * quiet through the heat of the day, take it up again toward evening, and are done by dark.
     */
    private fun birdsAt(hour: Double): Double {
        val lit = smoothstep(4.6, 6.0, hour) * (1 - smoothstep(19.0, 20.6, hour))
        val morning = exp(-((hour - 6.9) / 1.7).let { it * it })
        val evening = 0.55 * exp(-((hour - 18.2) / 1.1).let { it * it })
        return lit * (0.3 + 0.7 * max(morning, evening))
    }

    /** The crickets' share of the night, 0 to 1: from dusk (they start as the light goes) through the night, done by the first birds. */
    private fun cricketsAt(hour: Double): Double =
        if (hour >= 12.0) smoothstep(18.0, 20.0, hour) else 1 - smoothstep(4.4, 5.8, hour)

    /** How warm the year is just now, 0 (the dead of winter) to 1 (high summer); mild when the seasons are off. */
    private fun warmth(): Double {
        if (!SeasonClient.active) return 0.8
        return 0.5 + 0.5 * cos(2 * PI * (SeasonClient.date.position - 1.5) / 4.0)
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

    // ---- Heard far off ----

    private fun oneShots(level: ClientLevel, player: Player, hour: Double, master: Float) {
        val rain = level.getRainLevel(1f)
        val out = master * (0.5 + 0.5 * openness).toFloat()
        // A hammer ringing from a yard or a barn somewhere across the fields, on the days the trades work
        if (ready("hammer", 420, 1300) && hour in 7.0..17.0 && rain < 0.4f && !Days.restDay(Days.dayOf(level.overworldClockTime))) {
            ground(level, player, 26.0, 46.0)?.let { emit(HAMMER_FAR, it, out * 0.9f, pitch(0.05f)) }
        }
        // A child calling to another, from the lanes or the green past the next hedge, until supper
        if (ready("child", 600, 1700) && hour in 8.0..19.0 && rain < 0.3f) {
            ground(level, player, 26.0, 48.0)?.let { emit(CHILD_FAR, it, out * 0.85f, pitch(0.05f)) }
        }
        // A dog far off, from dusk to the first light
        if (ready("dog", 500, 1600) && (hour >= 19.5 || hour < 5.5) && rain < 0.7f) {
            ground(level, player, 28.0, 52.0)?.let { emit(DOG_FAR, it, out * 0.9f, pitch(0.06f)) }
        }
    }

    // ---- The folk and the place ----

    /** The folk awake within [FOLK_NEAR] blocks: villagers and townsfolk both. */
    private fun countFolk(level: ClientLevel, player: Player) {
        val box = AABB.ofSize(player.position(), FOLK_NEAR * 2, 16.0, FOLK_NEAR * 2)
        folk = level.getEntitiesOfClass(LivingEntity::class.java, box) { Surroundings.townsfolk(it) && !it.isSleeping && it.distanceToSqr(player) <= FOLK_NEAR * FOLK_NEAR }.size
    }

    /** How much open sky there is round the player's head, 0 under a roof to 1 in the open. */
    private fun feelThePlace(level: ClientLevel, player: Player) {
        val eye = BlockPos.containing(player.eyePosition)
        var open = if (level.canSeeSky(eye)) 1 else 0
        for (side in Direction.Plane.HORIZONTAL) if (level.canSeeSky(eye.relative(side, 3))) open++
        openness += (open / 5.0 - openness) * 0.5
    }

    // ---- Helpers ----

    /** Whether a kind of one-shot is due; if so, when it's next due is drawn from [min] to [max] ticks on. */
    private fun ready(kind: String, min: Int, max: Int): Boolean {
        val at = due[kind]
        if (at == null) {
            // Not all at once on arriving somewhere
            due[kind] = ticks + (range(min, max) * random.nextDouble()).toInt()
            return false
        }
        if (ticks < at) return false
        due[kind] = ticks + range(min, max)
        return true
    }

    private fun emit(sound: Identifier, at: Vec3, volume: Float, pitch: Float = 1f) {
        if (sounding.size >= MAX_SOUNDING) return
        CitySounds.play(sound, at, volume, pitch)?.let { sounding.add(it) }
    }

    private fun range(min: Int, max: Int): Int = min + random.nextInt(max - min + 1)

    private fun pitch(spread: Float): Float = 1f + (random.nextFloat() * 2 - 1) * spread

    /** Somewhere on the ground [from] to [to] blocks off in a random direction, at head height. */
    private fun ground(level: ClientLevel, player: Player, from: Double, to: Double): Vec3? {
        val a = random.nextDouble() * PI * 2
        val r = from + random.nextDouble() * (to - from)
        val x = player.x + cos(a) * r
        val z = player.z + sin(a) * r
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
        if (abs(y - player.y) > 24) return null
        return Vec3(x, y + 1.6, z)
    }

    private fun share(value: ModConfigSpec.IntValue): Float = try {
        value.get() / 100f
    } catch (e: IllegalStateException) {
        1f
    }

    /** The hour by the clock, 0 to 24: Minecraft's day begins at six in the morning. */
    private fun clock(level: ClientLevel): Double = ((level.overworldClockTime % 24000L) / 1000.0 + 6.0) % 24.0

    /** How loud each bed plays at full settings, so the country sits under the game rather than on it (the city's beds go at 0.8). */
    private const val DAY_LEVEL = 0.9
    private const val NIGHT_LEVEL = 0.9
    private const val BIRDS_LEVEL = 0.85
    private const val CRICKETS_LEVEL = 0.8
    private const val MARKET_LEVEL = 0.9
    /** Blocks within which the folk about count toward the market's crowd. */
    private const val FOLK_NEAR = 24.0
    private const val MAX_SOUNDING = 4
}
