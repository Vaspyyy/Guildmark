package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.vessel.StationBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationRegions
import io.github.vaspyyy.guildmark.cooking.vessel.StationUse
import io.github.vaspyyy.guildmark.cooking.vessel.Region
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.sqrt

/**
 * The stations that are alive on this client (living-world/cook/design.md 1.8, 9.5): one [StationAnim] for each station near
 * enough to matter, kept up to date a tick at a time, and the things around them that are not a station's own: which one the
 * cook is looking at, the particles and the one-shot sounds they ask for, and what it all costs.
 *
 * Which stations are alive: those a renderer has asked for lately ([of]), and those a look at the chunks round the player finds
 * every second ([scan]), so a pot behind your back goes on steaming and a loop goes on humming. Each is stepped once a tick; one
 * not asked for in a few seconds, or whose block is gone, is let go. There is no look through the world each tick: the scan reads
 * the block entities of the twenty-five chunks round the player once a second, and a tick steps the few anims it holds.
 *
 * Nothing here is loaded on a dedicated server (only the client's start-up reaches it).
 */
object StationFx : StationHooks {
    /** Ticks between two looks at the chunks round the player. */
    private const val SCAN_EVERY = 20L

    /** The look reaches this many chunks each way (two: a square of twenty-five), and finds stations within [SCAN_RADIUS] blocks. */
    private const val SCAN_CHUNKS = 2
    private const val SCAN_RADIUS = 36.0

    /** An anim not asked for in this many ticks is let go. */
    private const val KEEP_TICKS = 160L

    /** The alive anims, by their block's position. */
    private val anims = HashMap<Long, StationAnim>()

    /** The anims as of the tick being stepped (a copy, so one may be let go while they are stepped). */
    private val stepping = ArrayList<StationAnim>()

    private var world: ClientLevel? = null
    private var scanAt = Long.MIN_VALUE

    /** The station the cook's crosshair is on now, and where it meets it (null when none), as the last tick saw. */
    var aimed: StationAnim? = null
        private set
    var aimHit: BlockHitResult? = null
        private set

    // ---- What `cook perf` counts ----

    @JvmField
    var ticks = 0L

    @JvmField
    var tickNanos = 0L

    @JvmField
    var drawn = 0L

    @JvmField
    var drawNanos = 0L

    @JvmField
    var scans = 0L

    /** A renderer reports a station's drawing: the nanoseconds its extraction, its submissions and its geometry took. */
    fun drew(nanos: Long) {
        drawn++
        drawNanos += nanos
    }

    /** More time a drawing took, after it was reported (the geometry a renderer wrote when the game came to draw it). */
    fun spent(nanos: Long) {
        drawNanos += nanos
    }

    /** The line `cook perf` prints for the stations as seen and heard. */
    fun readout(): String {
        val tick = if (ticks > 0) tickNanos / ticks / 1000.0 else 0.0
        val draw = if (drawn > 0) drawNanos / drawn / 1000.0 else 0.0
        return "${anims.size} alive, stepped at ${"%.1f".format(tick)} us a tick, drawn ${drawn} times at ${"%.1f".format(draw)} us each " +
            "(four in view is 4 x that, the budget is 300 us); particles: ${CookParticleProviders.count()} alive, ${CookParticleProviders.made} made, " +
            "${CookParticleProviders.refused} refused; loops: ${CookLoops.audible()} of 3 running"
    }

    // ---- Options ----

    /** Whether Cooking Sounds allows any (the option is 0 to 100; unloaded it reads as full). */
    fun soundScale(): Float =
        if (GuildmarkClientConfig.SPEC.isLoaded) GuildmarkClientConfig.COOK_SOUNDS.get() / 100f else 1f

    // ---- Finding the anim of a station ----

    /** The anim of the station [be] (made if it has none), up to date for this tick; null if the block entity is not in a client level. */
    fun of(be: StationBlockEntity): StationAnim? {
        val level = be.level as? ClientLevel ?: return null
        if (level !== world) reset(level)
        val now = level.gameTime
        val key = be.blockPos.asLong()
        var anim = anims[key]
        if (anim == null || anim.be !== be) {
            anim = StationAnim(be.blockPos.immutable(), be.station, be)
            anims[key] = anim
        }
        anim.touched = now
        if (anim.stamp != now) anim.step(now, level, this)
        return anim
    }

    /** Lets every anim go (a new level, a log-out, a dropped connection). */
    fun reset(level: ClientLevel? = null) {
        anims.clear()
        stepping.clear()
        aimed = null
        aimHit = null
        scanAt = Long.MIN_VALUE
        world = level
        CookParticleProviders.reset()
        CookLoops.reset()
    }

    // ---- Each client tick ----

    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            if (world != null) reset(null)
            return
        }
        if (level !== world) reset(level)
        CookParticleProviders.tick(level)
        if (mc.isPaused) return
        val t0 = System.nanoTime()
        val now = level.gameTime
        if (scanAt == Long.MIN_VALUE || now - scanAt >= SCAN_EVERY || now < scanAt) {
            scan(mc, level, now)
            scanAt = now
        }
        aim(mc)
        stepping.clear()
        stepping.addAll(anims.values)
        for (anim in stepping) {
            if (anim.be.isRemoved || now - anim.touched > KEEP_TICKS || level.getBlockEntity(anim.pos) !== anim.be) {
                anims.remove(anim.pos.asLong())
                continue
            }
            anim.aimed = anim === aimed && ovenDoorWanted(mc, anim)
            anim.step(now, level, this)
        }
        CookLoops.tick(level, stepping)
        tickNanos += System.nanoTime() - t0
        ticks++
    }

    /** Looks at the chunks round the player for stations, and keeps the ones it finds alive. */
    private fun scan(mc: Minecraft, level: ClientLevel, now: Long) {
        val player = mc.player ?: return
        scans++
        val here = player.blockPosition()
        val cx = here.x shr 4
        val cz = here.z shr 4
        val limit = SCAN_RADIUS * SCAN_RADIUS
        for (dx in -SCAN_CHUNKS..SCAN_CHUNKS) {
            for (dz in -SCAN_CHUNKS..SCAN_CHUNKS) {
                val chunk = level.chunkSource.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) ?: continue
                for (be in chunk.blockEntities.values) {
                    if (be !is StationBlockEntity || be.isRemoved || be.blockPos.distSqr(here) > limit) continue
                    val key = be.blockPos.asLong()
                    var anim = anims[key]
                    if (anim == null || anim.be !== be) {
                        anim = StationAnim(be.blockPos.immutable(), be.station, be)
                        anims[key] = anim
                    }
                    anim.touched = now
                }
            }
        }
    }

    /** Which station the crosshair is on. */
    private fun aim(mc: Minecraft) {
        aimed = null
        aimHit = null
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val anim = anims[hit.blockPos.asLong()] ?: return
        aimed = anim
        aimHit = hit
    }

    /**
     * Whether the oven the crosshair is on should have its door open ahead of the server (1.6: it swings open while the cursor
     * is on it with food in hand): the click would land on its door, and something in the cook's hand would do something there.
     */
    private fun ovenDoorWanted(mc: Minecraft, anim: StationAnim): Boolean {
        if (anim.kind != Station.OVEN) return false
        val player = mc.player ?: return false
        val hit = aimHit ?: return false
        val use = anim.be as? StationUse ?: return false
        val region = StationRegions.of(Station.OVEN, false, anim.facing, anim.pos, player.eyePosition, hit.location)
        if (region != Region.VESSEL) return false
        for (hand in InteractionHand.entries) {
            val stack = player.getItemInHand(hand)
            if (!stack.isEmpty && use.predicts(player, hand, stack, hit)) return true
        }
        return false
    }

    // ---- What an anim asks of the client (StationHooks) ----

    override fun particle(
        type: SimpleParticleType, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double,
        r: Float, g: Float, b: Float, size: Float, alpha: Float,
    ) {
        CookParticleProviders.spawn(type, x, y, z, xd, yd, zd, r, g, b, size, alpha)
    }

    override fun vanilla(type: ParticleOptions, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double) {
        if (!CookParticleProviders.allowed(x, y, z)) return
        Minecraft.getInstance().particleEngine.createParticle(type, x, y, z, xd, yd, zd)
    }

    override fun inRange(pos: BlockPos): Boolean {
        if (!Cooking.enabled()) return false
        if (GuildmarkClientConfig.SPEC.isLoaded && !GuildmarkClientConfig.COOK_PARTICLES.get()) return false
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        if (!camera.isInitialized) return false
        val at = camera.position()
        val dx = at.x - (pos.x + 0.5)
        val dy = at.y - (pos.y + 0.5)
        val dz = at.z - (pos.z + 0.5)
        val reach = CookParticleProviders.RANGE + 1.0
        return dx * dx + dy * dy + dz * dz <= reach * reach
    }

    override fun towardPlayer(pos: BlockPos): FloatArray {
        val level = world ?: return ZERO
        var best = Double.MAX_VALUE
        var bx = 0.0
        var bz = 0.0
        for (p in level.players()) {
            val dx = p.x - (pos.x + 0.5)
            val dz = p.z - (pos.z + 0.5)
            val d = dx * dx + dz * dz
            if (d < best && d < 64.0) {
                best = d
                bx = dx
                bz = dz
            }
        }
        if (best == Double.MAX_VALUE || best < 1.0e-6) return ZERO
        val len = sqrt(best)
        return floatArrayOf((bx / len).toFloat(), (bz / len).toFloat())
    }

    /** A one-shot the anim asks for (the drip off the spit). It is played on the Blocks channel at [volume]; Hearthcraft Sounds is applied on its way in, by [CookSoundGate], as for the server's. */
    override fun sound(pos: BlockPos, facing: Direction, px: Float, py: Float, pz: Float, name: String, volume: Float, pitch: Float) {
        if (soundScale() <= 0.01f) return
        val level = world ?: return
        val event = try {
            CookSounds.get(name)
        } catch (e: NoSuchElementException) {
            return
        }
        val at = CookGeo.world(pos, facing, px, py, pz)
        level.playLocalSound(at.x, at.y, at.z, event, SoundSource.BLOCKS, volume, pitch, false)
    }

    private val ZERO = floatArrayOf(0f, 0f)
}
