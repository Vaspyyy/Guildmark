package io.github.vaspyyy.guildmark.client.season

import io.github.vaspyyy.guildmark.client.region.RegionParticles
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.SeasonPhase
import io.github.vaspyyy.guildmark.season.SeasonRegistry
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.server.level.ParticleStatus
import net.minecraft.tags.BlockTags
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.FogType
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The seasons in the air round the player: blossom petals drifting down from the trees in spring, leaves of every
 * colour turning as they fall in autumn, breath on the winter air from everyone about, and frost glinting on the
 * grass on a still winter night. Each is spawned only near the camera, a few a tick, and only in lands that feel the
 * seasons.
 */
object SeasonParticles {
    fun registerProviders(event: RegisterParticleProvidersEvent) {
        for (style in SeasonMote.Style.entries) event.registerSpriteSet(style.type.get()) { sprites -> SeasonMote.Provider(sprites, style) }
    }

    private val random = RandomSource.create()
    private val cursor = BlockPos.MutableBlockPos()

    /** Every client tick in the overworld. */
    fun tick(minecraft: Minecraft, level: ClientLevel) {
        if (minecraft.options.particles().get() == ParticleStatus.MINIMAL) return
        val camera = minecraft.gameRenderer.mainCamera()
        if (!camera.isInitialized) return
        val fluid = camera.fluidInCamera
        if (fluid != FogType.NONE && fluid != FogType.ATMOSPHERIC) return
        val player = minecraft.player ?: return
        val date = SeasonClient.date
        val here = player.blockPosition()
        val weight = Seasons.temperate(level.getBiome(here).value())
        val fewer = if (minecraft.options.particles().get() == ParticleStatus.DECREASED) 0.5f else 1f
        when (date.season) {
            Season.SPRING -> if (weight > 0.3f) {
                // Most in the middle of spring, when the trees are in flower
                val bloom = when (date.phase) {
                    SeasonPhase.EARLY -> 0.55f
                    SeasonPhase.MID -> 1f
                    SeasonPhase.LATE -> 0.45f
                }
                fromLeaves(minecraft, level, player, (3 * bloom * fewer * weight), SeasonMote.Style.PETAL)
            }
            Season.AUTUMN -> if (weight > 0.3f) {
                val fall = when (date.phase) {
                    SeasonPhase.EARLY -> 0.5f
                    SeasonPhase.MID -> 1f
                    SeasonPhase.LATE -> 1.3f
                }
                fromLeaves(minecraft, level, player, (3 * fall * fewer * weight) * (if (level.isRaining) 1.5f else 1f), SeasonMote.Style.LEAF)
            }
            Season.WINTER -> {
                breath(minecraft, level, player)
                if (weight > 0.5f && !level.isRaining) frost(level, player, fewer)
            }
            Season.SUMMER -> {}
        }
    }

    /** [rate] tries a tick at the underside of leaves round the camera, each spawning a mote of [style] if it finds one. */
    private fun fromLeaves(minecraft: Minecraft, level: ClientLevel, player: Player, rate: Float, style: SeasonMote.Style) {
        var tries = rate.toInt() + if (random.nextFloat() < rate - rate.toInt()) 1 else 0
        while (tries-- > 0) {
            val x = player.blockX + random.nextInt(41) - 20
            val z = player.blockZ + random.nextInt(41) - 20
            val top = player.blockY + 14
            // Down from above the camera to the first leaves with air under them
            for (y in top downTo top - 26) {
                cursor.set(x, y, z)
                val state = level.getBlockState(cursor)
                if (!state.`is`(BlockTags.LEAVES)) continue
                if (state.`is`(Blocks.CHERRY_LEAVES) || state.`is`(Blocks.AZALEA_LEAVES) || state.`is`(Blocks.FLOWERING_AZALEA_LEAVES)) break
                if (style == SeasonMote.Style.LEAF && (state.`is`(Blocks.SPRUCE_LEAVES) || state.`is`(Blocks.PALE_OAK_LEAVES))) break
                cursor.set(x, y - 1, z)
                if (!level.getBlockState(cursor).isAir) break
                val px = x + random.nextDouble()
                val py = y - 0.05
                val pz = z + random.nextDouble()
                val mote = minecraft.particleEngine.createParticle(style.type.get(), px, py, pz, 0.0, 0.0, 0.0) as? SeasonMote ?: break
                if (style == SeasonMote.Style.LEAF) {
                    cursor.set(x, y, z)
                    // The leaf's own colour as the season has turned it
                    val rgb = minecraft.blockColors.getTintSource(state, 0)?.colorInWorld(state, level, cursor) ?: -1
                    if (rgb != -1) mote.tint((rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f)
                }
                break
            }
        }
    }

    /** Every other tick, so the look round for who's breathing is half as often. */
    private var breathScan = 0

    /** Breath from everyone close by: players, villagers and townsfolk, every few seconds each. */
    private fun breath(minecraft: Minecraft, level: ClientLevel, player: Player) {
        // In the lands that feel the seasons, or where it's always cold
        val time = level.gameTime
        if (++breathScan % 2 != 0) return
        val box = AABB(player.blockPosition()).inflate(16.0)
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, box) { it is Player || it is AbstractVillager || it is Townsperson }) {
            if (entity.isUnderWater || entity.isInvisible || !entity.isAlive || entity.isSleeping) continue
            val cold = Seasons.temperate(level.getBiome(entity.blockPosition()).value())
            if (cold < 0.5f && level.getBiome(entity.blockPosition()).value().baseTemperature >= 0.15f) continue
            // A breath about every three seconds, out of step from one to the next
            val period = 64L
            if (Math.floorMod(time + entity.id * 23L, period) !in 0L..1L) continue
            if (entity === player && minecraft.options.cameraType == CameraType.FIRST_PERSON) continue
            val look = entity.getViewVector(1f)
            val eye = entity.getEyePosition(1f)
            val mx = eye.x + look.x * 0.32
            val my = eye.y - 0.12 * entity.scale + look.y * 0.2
            val mz = eye.z + look.z * 0.32
            repeat(2) {
                val mote = minecraft.particleEngine.createParticle(
                    SeasonMote.Style.BREATH.type.get(), mx, my, mz,
                    look.x * 0.03 + (random.nextDouble() - 0.5) * 0.01, 0.004, look.z * 0.03 + (random.nextDouble() - 0.5) * 0.01,
                ) as? SeasonMote
                mote?.sized(entity.scale)
            }
        }
    }

    /** Frost glinting on open grass and leaves round the player on a clear winter night. */
    private fun frost(level: ClientLevel, player: Player, fewer: Float) {
        val time = Math.floorMod(level.overworldClockTime, 24000L)
        if (time !in 13000L..23000L || random.nextFloat() > 0.6f * fewer) return
        val x = player.blockX + random.nextInt(25) - 12
        val z = player.blockZ + random.nextInt(25) - 12
        val y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        if (abs(y - player.blockY) > 8) return
        cursor.set(x, y - 1, z)
        val below = level.getBlockState(cursor)
        if (!below.`is`(Blocks.GRASS_BLOCK) && !below.`is`(Blocks.SNOW_BLOCK) && !below.`is`(Blocks.SNOW)) return
        Minecraft.getInstance().particleEngine.createParticle(SeasonMote.Style.GLINT.type.get(), x + random.nextDouble(), y + 0.04, z + random.nextDouble(), 0.0, 0.0, 0.0)
    }
}

/**
 * One of the seasons' motes: a petal, a leaf, a breath or a glint. Petals and leaves flutter and tumble down and lie a
 * moment where they land; a breath swells and thins as it drifts; a glint swells and fades in a blink.
 */
class SeasonMote(
    level: ClientLevel, x: Double, y: Double, z: Double, private val sprites: SpriteSet, private val style: Style,
) : SingleQuadParticle(level, x, y, z, sprites.first()) {
    enum class Style(val type: net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.core.particles.ParticleType<*>, SimpleParticleType>, val frames: Int) {
        PETAL(SeasonRegistry.PETAL, 8),
        LEAF(SeasonRegistry.LEAF, 8),
        BREATH(SeasonRegistry.BREATH, 4),
        GLINT(SeasonRegistry.GLINT, 4),
    }

    private val phase = random.nextFloat() * 6.2832f
    private var shape = 0
    private var tumble = 0f
    private var turn = 0f
    private var peak = 1f
    private var fadeIn = 8
    private var fadeOut = 16
    private var grow = 1f
    private var baseSize = 0.1f

    init {
        hasPhysics = false
        gravity = 0f
        friction = 0.96f
        when (style) {
            Style.PETAL -> {
                lifetime = 160 + random.nextInt(100)
                gravity = 0.012f
                friction = 0.93f
                hasPhysics = true
                baseSize = 0.05f + random.nextFloat() * 0.03f
                shape = random.nextInt(2)
                tumble = 0.12f + random.nextFloat() * 0.14f
                turn = (random.nextFloat() - 0.5f) * 0.1f
                // White, blush and pink
                val pick = random.nextInt(3)
                if (pick == 1) setColor(1f, 0.9f, 0.93f) else if (pick == 2) setColor(1f, 0.78f, 0.86f)
            }
            Style.LEAF -> {
                lifetime = 170 + random.nextInt(110)
                gravity = 0.016f
                friction = 0.94f
                hasPhysics = true
                baseSize = 0.08f + random.nextFloat() * 0.04f
                shape = random.nextInt(2)
                tumble = 0.1f + random.nextFloat() * 0.12f
                turn = (random.nextFloat() - 0.5f) * 0.12f
            }
            Style.BREATH -> {
                lifetime = 22 + random.nextInt(10)
                friction = 0.9f
                baseSize = 0.07f + random.nextFloat() * 0.03f
                grow = 2.6f
                fadeIn = 3
                fadeOut = 16
                peak = 0.42f
            }
            Style.GLINT -> {
                lifetime = 12 + random.nextInt(12)
                baseSize = 0.05f + random.nextFloat() * 0.04f
                fadeIn = 0
                fadeOut = 0
                setColor(0.86f, 0.94f, 1f)
            }
        }
        quadSize = baseSize
        if (turn != 0f) roll = random.nextFloat() * 6.2832f
        oRoll = roll
        pickSprite()
        fade()
    }

    fun tint(r: Float, g: Float, b: Float): SeasonMote {
        // Leaves fall a touch lighter than they hang, catching the light
        setColor(min(1f, r * 1.08f), min(1f, g * 1.08f), min(1f, b * 1.08f))
        return this
    }

    fun sized(k: Float): SeasonMote {
        baseSize *= k
        quadSize = baseSize
        return this
    }

    override fun getLayer(): SingleQuadParticle.Layer = SingleQuadParticle.Layer.TRANSLUCENT

    override fun getLightCoords(a: Float): Int {
        val coords = super.getLightCoords(a)
        return if (style == Style.GLINT) LightCoordsUtil.withBlock(coords, max(LightCoordsUtil.block(coords), 11)) else coords
    }

    override fun getQuadSize(a: Float): Float = when (style) {
        Style.BREATH -> baseSize * (1f + (grow - 1f) * ((age + a) / lifetime))
        Style.GLINT -> baseSize * (1f - abs((age + a) / lifetime * 2f - 1f))
        else -> quadSize
    }

    override fun tick() {
        oRoll = roll
        val wasOnGround = onGround
        when (style) {
            Style.PETAL, Style.LEAF -> {
                val push = if (style == Style.PETAL) 0.005 else 0.0065
                val s = sin(age * 0.17f + phase) * push
                xd += s * 0.7 + RegionParticles.windX * 0.0025
                zd -= s * 0.7 - RegionParticles.windZ * 0.0025
                // Checked in its fall as it swings to and fro
                yd += abs(cos(age * 0.17f + phase)) * 0.0007
            }
            Style.BREATH -> {
                yd += 0.0012
                xd += (random.nextDouble() - 0.5) * 0.002
                zd += (random.nextDouble() - 0.5) * 0.002
            }
            Style.GLINT -> {}
        }
        super.tick()
        if (!isAlive) return
        roll += turn
        if (onGround && !wasOnGround && (style == Style.PETAL || style == Style.LEAF)) {
            lifetime = min(lifetime, age + fadeOut + 30)
            turn = 0f
            tumble = 0f
        }
        pickSprite()
        fade()
    }

    private fun pickSprite() {
        if (removed) return
        val frame = when (style) {
            Style.PETAL, Style.LEAF -> shape * 4 + (((age * tumble + phase).toInt()) and 3)
            Style.BREATH -> min(3, (age * 4 / max(1, lifetime)))
            Style.GLINT -> min(3, ((1f - abs(age.toFloat() / lifetime * 2f - 1f)) * 4f).toInt())
        }
        setSprite(sprites.get(frame, style.frames - 1))
    }

    private fun fade() {
        val inn = if (fadeIn > 0) min(1f, age.toFloat() / fadeIn) else 1f
        val out = if (fadeOut > 0) min(1f, (lifetime - age).toFloat() / fadeOut) else 1f
        alpha = peak * max(0f, min(inn, out))
    }

    class Provider(private val sprites: SpriteSet, private val style: Style) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            options: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
            xAux: Double, yAux: Double, zAux: Double, random: RandomSource,
        ): Particle = SeasonMote(level, x, y, z, sprites, style).also {
            it.setParticleSpeed(xAux, yAux, zAux)
        }
    }
}
