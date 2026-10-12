package io.github.vaspyyy.guildmark.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.enemy.HillTroll
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** The kinds of field boss that keep a place of their own in the world. */
enum class FieldBossKind(val id: String) {
    HILL_TROLL("hill_troll");

    companion object {
        fun of(id: String): FieldBossKind = entries.firstOrNull { it.id == id } ?: HILL_TROLL
    }
}

/** One field boss's place: where its camp stands, whether it's been built and whether its boss has fallen. */
class FieldSite(
    val id: Int,
    val kind: FieldBossKind,
    /** The camp's heart (its fire), at the height you'd stand at. Until built, only x and z mean anything. */
    var pos: BlockPos,
    var built: Boolean = false,
    var boss: UUID? = null,
    var slain: Boolean = false,
    /** The boss's name, for the map (an index into its kind's names). */
    var name: Int = 0,
) {
    companion object {
        val CODEC: Codec<FieldSite> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(FieldSite::id),
                Codec.STRING.xmap(FieldBossKind::of, FieldBossKind::id).fieldOf("kind").forGetter(FieldSite::kind),
                BlockPos.CODEC.fieldOf("pos").forGetter(FieldSite::pos),
                Codec.BOOL.optionalFieldOf("built", false).forGetter(FieldSite::built),
                UUIDUtil.CODEC.optionalFieldOf("boss").forGetter { Optional.ofNullable(it.boss) },
                Codec.BOOL.optionalFieldOf("slain", false).forGetter(FieldSite::slain),
                Codec.INT.optionalFieldOf("name", 0).forGetter(FieldSite::name),
            ).apply(i) { id, kind, pos, built, boss, slain, name -> FieldSite(id, kind, pos, built, boss.orElse(null), slain, name) }
        }
    }
}

/**
 * Field bosses that keep a place of their own out in the wild: for now, hill trolls asleep by their fires.
 * The overworld is split into cells; the first time a player comes near a cell, it's settled whether one
 * keeps a camp there and roughly where. The camp is built, its troll set by the fire and a Wardfire raised
 * nearby once a player comes within sight and the land there is loaded. A slain boss stays slain.
 */
class FieldBosses(
    val sites: MutableList<FieldSite> = mutableListOf(),
    private val decided: MutableSet<Long> = HashSet(),
) : SavedData() {
    private var tick = 0

    fun site(id: Int): FieldSite? = sites.firstOrNull { it.id == id }

    /** Every other second (it is called once a second), in the overworld. */
    fun tick(level: ServerLevel) {
        if (level.dimension() != Level.OVERWORLD || !enabled()) return
        if (++tick % 2 != 0) return
        val players = level.players().filter { !it.isSpectator }
        for (player in players) {
            val cx = floor(player.x / CELL).toInt()
            val cz = floor(player.z / CELL).toInt()
            for (dx in -1..1) for (dz in -1..1) decide(level, cx + dx, cz + dz)
        }
        for (site in sites.toList()) {
            if (site.built || site.slain) continue
            val near = players.any { horizontal(it.x, it.z, site.pos) < BUILD_RANGE }
            if (near && loaded(level, site.pos, SEARCH + TrollCamp.REACH + 1)) build(level, site)
        }
    }

    /** Settles, once, whether the cell at [cx], [cz] holds a camp and roughly where. */
    private fun decide(level: ServerLevel, cx: Int, cz: Int) {
        val key = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
        if (!decided.add(key)) return
        setDirty()
        val random = java.util.Random(level.seed xor (cx * 341873128712L + cz * 132897987541L) xor SALT)
        if (random.nextFloat() >= CHANCE) return
        val x = cx * CELL + MARGIN + random.nextInt(CELL - 2 * MARGIN)
        val z = cz * CELL + MARGIN + random.nextInt(CELL - 2 * MARGIN)
        // The land round where players start is kept quiet
        val spawn = level.respawnData.pos()
        if (horizontal(x + 0.5, z + 0.5, spawn) < QUIET_SPAWN) return
        sites.add(FieldSite(nextId(), FieldBossKind.HILL_TROLL, BlockPos(x, 0, z)))
    }

    private fun nextId(): Int = (sites.maxOfOrNull { it.id } ?: 0) + 1

    /** Finds the spot nearest the site's mark that suits a camp, builds it and wakes its keeper; or gives the site up. */
    private fun build(level: ServerLevel, site: FieldSite) {
        val bells = bells(level, site.pos, VILLAGE_GAP + SEARCH)
        val spot = OFFSETS.asSequence()
            .map { (dx, dz) -> ground(level, site.pos.x + dx, site.pos.z + dz) }
            .firstOrNull { it != null && suits(level, it) && apart(level, it, bells) }
        if (spot == null) {
            sites.remove(site)
            setDirty()
            return
        }
        raise(level, site, spot)
    }

    /** Builds [site]'s camp at [spot], puts its troll in its nest and a Wardfire a fair walk off. */
    private fun raise(level: ServerLevel, site: FieldSite, spot: BlockPos) {
        val facing = net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(level.random)
        TrollCamp.build(level, spot, facing, level.random)
        site.pos = spot
        site.built = true
        val bed = TrollCamp.bed(spot, facing)
        val troll = ModEntities.HILL_TROLL.get().create(level, EntitySpawnReason.STRUCTURE)
        if (troll != null) {
            // Asleep facing its fire
            val yaw = facing.opposite.toYRot()
            troll.snapTo(bed.x + 0.5, bed.y.toDouble(), bed.z + 0.5, yaw, 0f)
            troll.yBodyRot = yaw
            troll.yHeadRot = yaw
            troll.finalizeSpawn(level, level.getCurrentDifficultyAt(bed), EntitySpawnReason.STRUCTURE, null)
            troll.site = site.id
            troll.home = bed
            troll.homeYaw = yaw
            troll.scaleTo(QuestGenerator.tierAt(spot.x, spot.z))
            level.addFreshEntity(troll)
            site.boss = troll.uuid
            site.name = troll.nameIndex
        }
        // Far enough out that someone who falls to the troll and wakes there isn't still in its fight
        if (WardfireSites.enabled() && !WardfireSites.near(level, spot, FIRE_MAX)) WardfireSites.placeNear(level, spot, FIRE_MIN, FIRE_MAX)
        setDirty()
    }

    /** The ground at [x], [z]: the first open block above it, stepping down off trees and plants (never through water). */
    private fun ground(level: ServerLevel, x: Int, z: Int): BlockPos? {
        var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
        while (top.y > level.minY && level.getBlockState(top.below()).let {
                it.`is`(BlockTags.LOGS) || it.`is`(BlockTags.LEAVES) || (it.canBeReplaced() && it.fluidState.isEmpty)
            }) top = top.below()
        return if (top.y <= level.minY + 1) null else top
    }

    /**
     * Whether a camp can stand at [pos]: dry, natural ground that's near enough flat, with no trees in the
     * middle and nothing anyone has built anywhere it would reach.
     */
    private fun suits(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.getFluidState(pos).isEmpty || !level.getFluidState(pos.below()).isEmpty) return false
        val floor = level.getBlockState(pos.below())
        if (!(floor.`is`(BlockTags.DIRT) || floor.`is`(BlockTags.BASE_STONE_OVERWORLD) || floor.`is`(BlockTags.SNOW))) return false
        val r = TrollCamp.RADIUS
        var even = 0
        var columns = 0
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            columns++
            val top = ground(level, pos.x + dx, pos.z + dz) ?: return false
            if (!level.getFluidState(top).isEmpty || !level.getFluidState(top.below()).isEmpty) return false
            if (top.y > pos.y + 4) return false
            if (abs(top.y - pos.y) <= 2) even++
        }
        if (even < columns * 0.8) return false
        // No tree trunks in the heart of the camp
        for (dx in -5..5) for (dz in -5..5) for (dy in 0..3) {
            if (level.getBlockState(pos.offset(dx, dy, dz)).`is`(BlockTags.LOGS)) return false
        }
        // Nothing built, planted or stored anywhere the camp would reach
        val reach = r + 1
        for (dx in -reach..reach) for (dz in -reach..reach) {
            if (dx * dx + dz * dz > (reach + 0.5) * (reach + 0.5)) continue
            for (dy in -3..4) if (!TrollCamp.wild(level.getBlockState(pos.offset(dx, dy, dz)))) return false
        }
        return true
    }

    /** Whether [pos] is well away from villages ([bells]), lairs and other camps. */
    private fun apart(level: ServerLevel, pos: BlockPos, bells: List<BlockPos>): Boolean {
        if (Lairs.get(level).lairs.any { it.pos.closerThan(pos, LAIR_GAP) }) return false
        if (sites.any { it.built && it.pos.closerThan(pos, SITE_GAP) }) return false
        return bells.none { it.closerThan(pos, VILLAGE_GAP.toDouble()) }
    }

    /** Village bells within [radius] of [pos], in land that's loaded (looking any further would read it from disk). */
    private fun bells(level: ServerLevel, pos: BlockPos, radius: Int): List<BlockPos> {
        val found = ArrayList<BlockPos>()
        for (cx in ((pos.x - radius) shr 4)..((pos.x + radius) shr 4)) for (cz in ((pos.z - radius) shr 4)..((pos.z + radius) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) continue
            level.poiManager.getInChunk({ it.`is`(PoiTypes.MEETING) }, ChunkPos(cx, cz), PoiManager.Occupancy.ANY).forEach { found.add(it.pos) }
        }
        return found
    }

    private fun loaded(level: ServerLevel, pos: BlockPos, reach: Int): Boolean {
        for (cx in ((pos.x - reach) shr 4)..((pos.x + reach) shr 4)) for (cz in ((pos.z - reach) shr 4)..((pos.z + reach) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) return false
        }
        return true
    }

    // ---- The boss ----

    /** The troll noticed someone. */
    fun onWoken(level: ServerLevel, troll: HillTroll) {}

    /** A field boss fell: its place is cleared for good, and it leaves its hoard where it fell. */
    fun onSlain(level: ServerLevel, troll: HillTroll) {
        val site = site(troll.site)
        if (site != null) {
            site.slain = true
            setDirty()
        }
        val tier = QuestGenerator.tierAt(troll.blockX, troll.blockZ)
        if (level.gameRules.get(GameRules.MOB_DROPS)) {
            troll.spawnAtLocation(level, Relics.roll(level, tier + 2, if (tier >= 3) Rarity.RARE else Rarity.UNCOMMON))
            troll.spawnAtLocation(level, ItemStack(ModItems.GUILD_MARK.get(), 12 + tier * 8 + level.random.nextInt(6)))
            if (level.random.nextFloat() < 0.35f) troll.spawnAtLocation(level, Relics.cache(Rarity.UNCOMMON))
        }
        val heroes = level.players().filter { it.isAlive && it.distanceToSqr(troll) < HERO_RANGE * HERO_RANGE }
        for (player in heroes) {
            (player as? ServerPlayer)?.sendSystemMessage(Component.translatable("message.guildmark.field_boss_slain", troll.title))
        }
        GuildNews.add(level, "news.guildmark.field_boss", heroes.joinToString(", ") { it.name.string }.ifEmpty { "?" },
            "#${HillTroll.NAME_KEY}.${troll.nameIndex}", troll.blockX.toString(), troll.blockZ.toString())
    }

    /** For testing: a camp 20 to 30 blocks from [near] in the overworld, built at once on the first ground that suits it. */
    fun createNear(level: ServerLevel, near: BlockPos): FieldSite? {
        if (level.dimension() != Level.OVERWORLD) return null
        val random = level.random
        repeat(40) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 20 + random.nextInt(11)
            val x = near.x + (kotlin.math.cos(angle) * distance).toInt()
            val z = near.z + (kotlin.math.sin(angle) * distance).toInt()
            if (!loaded(level, BlockPos(x, 0, z), TrollCamp.REACH + 1)) return@repeat
            val spot = ground(level, x, z) ?: return@repeat
            if (!suits(level, spot)) return@repeat
            val site = FieldSite(nextId(), FieldBossKind.HILL_TROLL, spot)
            sites.add(site)
            raise(level, site, spot)
            return site
        }
        return null
    }

    companion object {
        /** The side of a cell, in blocks; each holds at most one camp. */
        private const val CELL = 512
        /** How far into a cell a camp's mark lies, at least. */
        private const val MARGIN = 64
        /** The chance a cell holds a camp. */
        private const val CHANCE = 0.55f
        private const val SALT = 0x6A0D17E5L
        /** How near a player comes before a camp is built. */
        private const val BUILD_RANGE = 112.0
        /** How far from its mark a camp may move to find ground that suits it. */
        private const val SEARCH = 32
        private const val QUIET_SPAWN = 320.0
        private const val VILLAGE_GAP = 160
        private const val LAIR_GAP = 96.0
        private const val SITE_GAP = 256.0
        /** How far from a camp's fire its Wardfire stands. */
        private const val FIRE_MIN = 48
        private const val FIRE_MAX = 60
        private const val HERO_RANGE = 48.0
        private val OFFSETS: List<Pair<Int, Int>> = buildList {
            add(0 to 0)
            for (r in intArrayOf(12, 24, 32)) for (k in 0 until 8) {
                val a = Math.PI * 2 * k / 8
                add((kotlin.math.cos(a) * r).toInt() to (kotlin.math.sin(a) * r).toInt())
            }
        }

        fun enabled(): Boolean = try {
            GuildmarkConfig.FIELD_BOSSES.get()
        } catch (e: IllegalStateException) {
            true
        }

        private fun horizontal(x: Double, z: Double, pos: BlockPos): Double {
            val dx = x - (pos.x + 0.5)
            val dz = z - (pos.z + 0.5)
            return sqrt(dx * dx + dz * dz)
        }

        private val CODEC: Codec<FieldBosses> = RecordCodecBuilder.create { i ->
            i.group(
                FieldSite.CODEC.listOf().optionalFieldOf("sites", listOf()).forGetter { it.sites },
                Codec.LONG.listOf().optionalFieldOf("decided", listOf()).forGetter { it.decided.toList() },
            ).apply(i) { sites, decided -> FieldBosses(sites.toMutableList(), decided.toHashSet()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "field_bosses"), Supplier { FieldBosses() }, CODEC)

        /** Field bosses are kept with the overworld, whichever level asks. */
        fun get(level: ServerLevel): FieldBosses = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
