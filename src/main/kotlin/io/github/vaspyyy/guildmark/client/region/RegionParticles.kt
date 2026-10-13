package io.github.vaspyyy.guildmark.client.region

import io.github.vaspyyy.guildmark.client.region.RegionMote.Style
import io.github.vaspyyy.guildmark.region.RegionKind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ParticleStatus
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FogType
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The motes in the air of the land around the player (see RegionMote): ash falling over the ashen highlands
 * and embers rising off the ground, wisps over the marsh water at night and gnats over it by day, snow blown
 * off the frozen peaks and the high crags, glints on the walls of the crystal deeps, pollen over the heartland
 * and the steppe, spores rising on the mushroom isles, sand skipping over the desert, dust in the caves and
 * bits of leaf blowing through the woods. They come and go with the land's air (RegionAtmosphere), keep to
 * fair weather where rain would knock them down, and thin out or stop with the game's particle setting.
 */
object RegionParticles {
    /** The wind the light motes go with: it wanders slowly round the compass and comes in gusts. */
    var windX = 0.0
        private set
    var windZ = 0.0
        private set
    /** How hard the wind blows, from near still to a gust of about 1.2. */
    private var gust = 0.0

    private val random = RandomSource.create()

    // Their colours over the pale paint
    private val ASH = floatArrayOf(0.95f, 0.94f, 0.92f)
    private val ASH_DARK = floatArrayOf(0.8f, 0.79f, 0.77f)
    private val WISP = floatArrayOf(0.86f, 1f, 0.6f)
    private val WISP_DEEP = floatArrayOf(0.7f, 1f, 0.5f)
    private val SNOW = floatArrayOf(0.95f, 0.97f, 1f)
    private val MEADOW_POLLEN = floatArrayOf(1f, 0.98f, 0.9f)
    private val STEPPE_POLLEN = floatArrayOf(1f, 0.86f, 0.56f)
    private val SPORE = floatArrayOf(1f, 0.86f, 0.96f)
    private val SPORE_VIOLET = floatArrayOf(0.86f, 0.78f, 1f)
    private val SAND = floatArrayOf(1f, 0.97f, 0.92f)
    private val CAVE = floatArrayOf(0.86f, 0.83f, 0.78f)
    private val DEEP = floatArrayOf(0.62f, 0.78f, 0.95f)
    private val CRYSTAL = floatArrayOf(0.84f, 0.74f, 1f)
    private val DRY_LEAF = floatArrayOf(0.64f, 0.46f, 0.24f)
    private val PALE_LEAF = floatArrayOf(0.86f, 0.87f, 0.82f)

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event ->
            for (style in Style.entries) event.registerSpriteSet(style.type.get()) { sprites -> RegionMote.Provider(sprites, style) }
        }
    }

    /** Each client tick while the game runs: the wind, and the motes of the land [kind], its air at [strength] (0 to 1). */
    fun tick(kind: RegionKind?, strength: Float) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        blow(level.gameTime)
        if (kind == null || strength <= 0f) return
        if (minecraft.options.particles().get() == ParticleStatus.MINIMAL) return
        val camera = minecraft.gameRenderer.mainCamera()
        if (!camera.isInitialized) return
        val fluid = camera.fluidInCamera
        if (fluid != FogType.NONE && fluid != FogType.ATMOSPHERIC) return
        val at = camera.position()
        val fair = level.getRainLevel(1f) < 0.2f
        val night = level.isDarkOutside()
        when (kind) {
            RegionKind.HEARTLAND -> if (fair && !night) repeat(count(0.14f * strength)) { pollen(level, at, MEADOW_POLLEN) }
            RegionKind.STEPPE -> if (fair && !night) repeat(count(0.12f * strength)) { pollen(level, at, STEPPE_POLLEN) }
            RegionKind.WILDWOOD -> repeat(count(0.1f * (0.4f + gust.toFloat()) * strength)) { leaf(level, at) }
            RegionKind.JUNGLE -> repeat(count(0.08f * (0.4f + gust.toFloat()) * strength)) { leaf(level, at) }
            RegionKind.MARSH -> if (night) repeat(count(0.03f * strength)) { wisp(level, at) }
                else if (fair) repeat(count(0.012f * strength)) { gnats(level, at) }
            RegionKind.FROZEN -> repeat(count(0.6f * (0.3f + gust.toFloat()) * strength)) { snow(level, at) }
            // Up high the crags' wind carries snow off the tops
            RegionKind.CRAGS -> if (at.y > 120.0) repeat(count(0.35f * gust.toFloat() * strength)) { snow(level, at) }
            RegionKind.ASHEN -> {
                repeat(count(0.4f * strength)) { ash(level, at) }
                repeat(count(0.025f * strength)) { ember(level, at) }
            }
            RegionKind.DUNES -> if (fair && !night) repeat(count(0.35f * (0.2f + gust.toFloat()) * strength)) { sand(level, at) }
            RegionKind.MUSHROOM -> repeat(count(0.25f * strength)) { spore(level, at) }
            RegionKind.CAVERN -> repeat(count(0.18f * strength)) { mote(level, at, CAVE, 0) }
            RegionKind.DEEP -> repeat(count(0.12f * strength)) { mote(level, at, DEEP, 5) }
            RegionKind.CRYSTAL -> {
                repeat(count(0.5f * strength)) { glint(level, at) }
                repeat(count(0.08f * strength)) { mote(level, at, CRYSTAL, 7) }
            }
            RegionKind.SEA -> {}
        }
    }

    /** The wind for game tick [time]: a slow swing round the compass, and gusts on top. */
    private fun blow(time: Long) {
        val t = time.toDouble()
        val angle = 0.6 + sin(t / 2400.0) * 1.1 + sin(t / 590.0 + 2.0) * 0.4
        gust = (0.55 + sin(t / 230.0 + 1.3) * 0.3 + sin(t / 47.0) * 0.15).coerceIn(0.05, 1.2)
        windX = cos(angle) * gust
        windZ = sin(angle) * gust
    }

    /** How many to spawn this tick for [rate] a tick: its whole part, and one more by chance for the rest. */
    private fun count(rate: Float): Int {
        val whole = floor(rate).toInt()
        return whole + if (random.nextFloat() < rate - whole) 1 else 0
    }

    /** A mote of [style] at ([x], [y], [z]), unless the game's particle setting thins it out. */
    private fun spawn(style: Style, x: Double, y: Double, z: Double): RegionMote? {
        val minecraft = Minecraft.getInstance()
        if (minecraft.options.particles().get() == ParticleStatus.DECREASED && random.nextInt(3) == 0) return null
        return minecraft.particleEngine.createParticle(style.type.get(), x, y, z, 0.0, 0.0, 0.0) as? RegionMote
    }

    private fun RegionMote.tint(colour: FloatArray): RegionMote = tint(colour[0], colour[1], colour[2])

    private fun around(from: Double, reach: Double): Double = from + (random.nextDouble() * 2 - 1) * reach

    /** The height of the first open block over the land (or water) at [x], [z]. */
    private fun ground(level: ClientLevel, x: Double, z: Double): Int =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING, floor(x).toInt(), floor(z).toInt())

    /**
     * The ground in the open at [x], [z], or null where it's roofed over well above the camera at [at] (a
     * cave, a house, a canopy) or drops away far below it.
     */
    private fun openGround(level: ClientLevel, at: Vec3, x: Double, z: Double): Int? {
        val ground = ground(level, x, z)
        return if (ground > at.y + 6 || ground < at.y - 24) null else ground
    }

    private fun overWater(level: ClientLevel, x: Double, ground: Int, z: Double): Boolean =
        !level.getFluidState(BlockPos.containing(x, ground - 1.0, z)).isEmpty

    private fun ash(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 18.0)
        val z = around(at.z, 18.0)
        val ground = openGround(level, at, x, z) ?: return
        val y = max(ground.toDouble(), at.y - 6) + 2 + random.nextDouble() * 10
        spawn(Style.ASH, x, y, z)?.tint(if (random.nextBoolean()) ASH else ASH_DARK)
    }

    private fun ember(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 12.0)
        val z = around(at.z, 12.0)
        val ground = openGround(level, at, x, z) ?: return
        if (overWater(level, x, ground, z)) return
        spawn(Style.EMBER, x, ground + 0.1, z)
    }

    private fun snow(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 16.0)
        val z = around(at.z, 16.0)
        val ground = openGround(level, at, x, z) ?: return
        val y = max(ground.toDouble(), at.y - 4) + 0.5 + random.nextDouble() * 6
        spawn(Style.SNOW, x, y, z)?.tint(SNOW)
    }

    private fun pollen(level: ClientLevel, at: Vec3, colour: FloatArray) {
        val x = around(at.x, 14.0)
        val z = around(at.z, 14.0)
        val ground = openGround(level, at, x, z) ?: return
        val y = ground + 0.3 + random.nextDouble() * 3
        if (y > at.y + 8 || y < at.y - 8) return
        spawn(Style.POLLEN, x, y, z)?.tint(colour)
    }

    private fun sand(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 16.0)
        val z = around(at.z, 16.0)
        val ground = openGround(level, at, x, z) ?: return
        if (overWater(level, x, ground, z)) return
        val y = ground + 0.1 + random.nextDouble()
        if (y > at.y + 8 || y < at.y - 8) return
        spawn(Style.SAND, x, y, z)?.tint(SAND)
    }

    private fun spore(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 14.0)
        val z = around(at.z, 14.0)
        val ground = openGround(level, at, x, z) ?: return
        if (overWater(level, x, ground, z)) return
        val y = ground + 0.2 + random.nextDouble() * 2
        if (y > at.y + 8 || y < at.y - 8) return
        spawn(Style.SPORE, x, y, z)?.tint(if (random.nextInt(3) == 0) SPORE_VIOLET else SPORE)
    }

    /** A wisp hovering low, most often over the water. */
    private fun wisp(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 16.0)
        val z = around(at.z, 16.0)
        val ground = openGround(level, at, x, z) ?: return
        if (!overWater(level, x, ground, z) && random.nextInt(3) != 0) return
        val y = ground + 0.4 + random.nextDouble() * 1.6
        spawn(Style.WISP, x, y, z)?.home(x, y, z)?.tint(if (random.nextBoolean()) WISP else WISP_DEEP)
    }

    /** A swarm of gnats about a spot over the ground or the water. */
    private fun gnats(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 10.0)
        val z = around(at.z, 10.0)
        val ground = openGround(level, at, x, z) ?: return
        val y = ground + 0.8 + random.nextDouble() * 1.2
        repeat(5 + random.nextInt(5)) {
            spawn(Style.GNAT, around(x, 0.4), around(y, 0.3), around(z, 0.4))?.home(x, y, z)
        }
    }

    /**
     * A bit of leaf blowing along under the trees, the colour of the leaves overhead: some green, some gone
     * dry and brown, and pale under the pale oaks.
     */
    private fun leaf(level: ClientLevel, at: Vec3) {
        val x = around(at.x, 14.0)
        val z = around(at.z, 14.0)
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)
        val bare = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
        // Only under leaves, between the canopy and the ground
        if (top <= bare) return
        val y = at.y + 0.5 + random.nextDouble() * 4
        if (y >= top - 1 || y <= bare + 0.3) return
        val pos = BlockPos.containing(x, y, z)
        if (!level.getBlockState(pos).isAir) return
        val canopy = BlockPos(bx, top - 1, bz)
        if (!level.getBlockState(canopy).`is`(BlockTags.LEAVES)) return
        val mote = spawn(Style.LEAF, x, y, z) ?: return
        if (level.getBiome(pos).`is`(Biomes.PALE_GARDEN)) {
            mote.tint(PALE_LEAF)
            return
        }
        val colour = level.getClientLeafTintColor(canopy)
        var r = if (colour == -1) 0.42f else ((colour shr 16) and 0xFF) / 255f
        var g = if (colour == -1) 0.56f else ((colour shr 8) and 0xFF) / 255f
        var b = if (colour == -1) 0.26f else (colour and 0xFF) / 255f
        if (random.nextInt(10) < 3) {
            r += (DRY_LEAF[0] - r) * 0.7f
            g += (DRY_LEAF[1] - g) * 0.7f
            b += (DRY_LEAF[2] - b) * 0.7f
        }
        mote.tint(r, g, b)
    }

    /** A spot in the open air of a cave about the camera at [at], well under the ground above, or null. */
    private fun caveAir(level: ClientLevel, at: Vec3, reach: Double): BlockPos? {
        val x = around(at.x, reach)
        val z = around(at.z, reach)
        val y = at.y - 3 + random.nextDouble() * 8
        if (y >= ground(level, x, z) - 3) return null
        val pos = BlockPos.containing(x, y, z)
        return if (level.getBlockState(pos).isAir) pos else null
    }

    private fun mote(level: ClientLevel, at: Vec3, colour: FloatArray, glow: Int) {
        val pos = caveAir(level, at, 12.0) ?: return
        val mote = spawn(Style.MOTE, pos.x + random.nextDouble(), pos.y + random.nextDouble(), pos.z + random.nextDouble()) ?: return
        mote.tint(colour)
        if (glow > 0) mote.lit(glow)
    }

    /** A glint on a cave wall: on the face of a solid block beside a spot of cave air. */
    private fun glint(level: ClientLevel, at: Vec3) {
        val pos = caveAir(level, at, 10.0) ?: return
        val side = Direction.entries[random.nextInt(Direction.entries.size)]
        if (!level.getBlockState(pos.relative(side)).isSolidRender) return
        val x = pos.x + 0.5 + side.stepX * 0.48 + (if (side.stepX == 0) random.nextDouble() - 0.5 else 0.0) * 0.8
        val y = pos.y + 0.5 + side.stepY * 0.48 + (if (side.stepY == 0) random.nextDouble() - 0.5 else 0.0) * 0.8
        val z = pos.z + 0.5 + side.stepZ * 0.48 + (if (side.stepZ == 0) random.nextDouble() - 0.5 else 0.0) * 0.8
        spawn(Style.GLINT, x, y, z)
    }
}
