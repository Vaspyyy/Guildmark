package io.github.vaspyyy.guildmark.outlaw

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.field.FieldBosses
import io.github.vaspyyy.guildmark.field.TrollCamp
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.quest.Bounties
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.region.Regions
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.RandomStrollGoal
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.decoration.Cushion
import net.minecraft.world.entity.monster.illager.Vindicator
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.raid.Raid
import net.minecraft.world.entity.raid.Raider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.AABB
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** What one of a band does: leads it, keeps watch from the tower, stands guard at the way in, or sits about the fire. */
enum class OutlawRole(val id: String) {
    CAPTAIN("captain"),
    LOOKOUT("lookout"),
    SENTRY("sentry"),
    HAND("hand");

    companion object {
        fun of(id: String): OutlawRole = entries.firstOrNull { it.id == id } ?: HAND
    }
}

/** One of a band: its entity, and what it does. */
data class Outlaw(val id: UUID, val role: OutlawRole) {
    companion object {
        val CODEC: Codec<Outlaw> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Outlaw::id),
                Codec.STRING.xmap(OutlawRole::of, OutlawRole::id).fieldOf("role").forGetter(Outlaw::role),
            ).apply(i) { id, role -> Outlaw(id, role) }
        }
    }
}

/** What a band does to the land round it, as its wanted posters and the news tell it. */
enum class Crime(val id: String) {
    CARAVAN("caravan"),
    FARM("farm"),
    BARN("barn"),
    TRAVELLER("traveller"),
    TITHE("tithe"),
    PATROL("patrol");

    companion object {
        fun of(id: String): Crime? = entries.firstOrNull { it.id == id }
    }
}

/** An outlaw band and its camp: where it is, who leads it, what's on the captain's head and what they've done. */
class OutlawBand(
    val id: Int,
    /** The camp's fire, at the height you'd stand at. Until built, only x and z mean anything. */
    var pos: BlockPos,
    /** From the fire towards the captain's tent (see OutlawCamp). */
    var facing: Direction = Direction.NORTH,
    var built: Boolean = false,
    /** The captain has fallen: the band is broken for good. */
    var slain: Boolean = false,
    /** The captain's name and epithet, as indices (see Outlaws). */
    var name: Int = 0,
    var epithet: Int = 0,
    /** The price on the captain's head, in Guild Marks; every crime puts it up. */
    var bounty: Int = 0,
    /** What they've done lately, the latest first (crime ids). */
    val crimes: MutableList<String> = mutableListOf(),
    val members: MutableList<Outlaw> = mutableListOf(),
    /** The land the camp is in, by name. */
    var region: String = "",
    /** The day the band last took someone on. */
    var recruited: Long = -1,
) {
    val tier: Int get() = QuestGenerator.tierAt(pos.x, pos.z)

    fun captain(): Outlaw? = members.firstOrNull { it.role == OutlawRole.CAPTAIN }

    fun title(): Component = Outlaws.title(name, epithet)

    /** The captain's name as notes and contracts keep it (see quest/Labels). */
    val label: String get() = Outlaws.label(name, epithet)

    /** The latest thing they're wanted for, if anything yet. */
    fun latestCrime(): Crime? = crimes.firstNotNullOfOrNull(Crime::of)

    companion object {
        val CODEC: Codec<OutlawBand> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(OutlawBand::id),
                BlockPos.CODEC.fieldOf("pos").forGetter(OutlawBand::pos),
                Direction.CODEC.optionalFieldOf("facing", Direction.NORTH).forGetter(OutlawBand::facing),
                Codec.BOOL.optionalFieldOf("built", false).forGetter(OutlawBand::built),
                Codec.BOOL.optionalFieldOf("slain", false).forGetter(OutlawBand::slain),
                Codec.INT.optionalFieldOf("name", 0).forGetter(OutlawBand::name),
                Codec.INT.optionalFieldOf("epithet", 0).forGetter(OutlawBand::epithet),
                Codec.INT.optionalFieldOf("bounty", 0).forGetter(OutlawBand::bounty),
                Codec.STRING.listOf().optionalFieldOf("crimes", listOf()).forGetter { it.crimes },
                Outlaw.CODEC.listOf().optionalFieldOf("members", listOf()).forGetter { it.members },
                Codec.STRING.optionalFieldOf("region", "").forGetter(OutlawBand::region),
                Codec.LONG.optionalFieldOf("recruited", -1L).forGetter(OutlawBand::recruited),
            ).apply(i) { id, pos, facing, built, slain, name, epithet, bounty, crimes, members, region, recruited ->
                OutlawBand(id, pos, facing, built, slain, name, epithet, bounty, crimes.toMutableList(), members.toMutableList(), region, recruited)
            }
        }
    }
}

/**
 * Outlaw bands camped out in the wild. Like the trolls' camps, the overworld is split into cells; the first time a
 * player comes near one it's settled whether a band camps there and roughly where, and the camp is built and its band
 * mustered once a player comes within sight of it.
 *
 * A camp keeps its own hours while anyone is near: by day the captain sits before the big tent, the lookout watches
 * from the tower, the sentries stand at the way in and the rest sit about the fire on their cushions, now and then
 * getting up to see to the stew pot, the loot or the horses, or all laughing at once; by night most of them turn in and
 * one keeps watch by the fire. Once one of them has a player in their sights the horn goes up, everyone's on their feet
 * and the captain's name goes up as a bar; at half strength the captain calls out two more from the tents. A quiet
 * spell with no one about and they go back to what they were doing. The band takes someone new on now and then to make
 * up its losses, and every day it may do something that puts the price on the captain's head up. When the captain
 * falls the band breaks and runs, and the bounty is paid to whoever holds a contract on them.
 */
class OutlawCamps(
    val bands: MutableList<OutlawBand> = mutableListOf(),
    private val decided: MutableSet<Long> = HashSet(),
    /** The last day the bands' doings were reckoned. */
    private var reckoned: Long = -1,
) : SavedData() {
    /** How each band is getting on just now; not saved, so a band forgets a fight over a restart. */
    private val moods = HashMap<Int, Mood>()

    fun band(id: Int): OutlawBand? = bands.firstOrNull { it.id == id }

    /** The living bands within [range] of [pos], nearest first. */
    fun wanted(pos: BlockPos, range: Double): List<OutlawBand> = bands
        .filter { it.built && !it.slain && it.captain() != null && horizontal(pos, it.pos) < range }
        .sortedBy { horizontal(pos, it.pos) }

    /** Every 10 ticks, in the overworld. */
    fun tick(level: ServerLevel) {
        if (level.dimension() != Level.OVERWORLD || !Outlaws.enabled()) return
        val players = level.players().filter { !it.isSpectator }
        if (level.gameTime % 40L == 0L) {
            for (player in players) {
                val cx = floor(player.x / CELL).toInt()
                val cz = floor(player.z / CELL).toInt()
                for (dx in -1..1) for (dz in -1..1) decide(level, cx + dx, cz + dz)
            }
            for (band in bands.toList()) {
                if (band.built || band.slain) continue
                val near = players.any { horizontal(it.x, it.z, band.pos) < BUILD_RANGE }
                if (near && loaded(level, band.pos, SEARCH + OutlawCamp.REACH + 1)) build(level, band)
            }
            reckon(level)
        }
        for (band in bands) {
            if (!band.built || (band.slain && band.members.isEmpty())) {
                moods.remove(band.id)?.bar?.removeAllPlayers()
                continue
            }
            val near = players.filter { horizontal(it.x, it.z, band.pos) < LIVE_RANGE }
            if (near.isEmpty() || !level.isPositionEntityTicking(band.pos)) {
                // No one about: the fight, if there was one, is over
                moods[band.id]?.let { calm(it) }
                continue
            }
            live(level, band, near)
        }
    }

    // ---- Where bands camp ----

    /** Settles, once, whether the cell at [cx], [cz] holds a band and roughly where. */
    private fun decide(level: ServerLevel, cx: Int, cz: Int) {
        val key = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
        if (!decided.add(key)) return
        setDirty()
        val random = java.util.Random(level.seed xor (cx * 341873128712L + cz * 132897987541L) xor SALT)
        if (random.nextFloat() >= CHANCE) return
        val x = cx * CELL + MARGIN + random.nextInt(CELL - 2 * MARGIN)
        val z = cz * CELL + MARGIN + random.nextInt(CELL - 2 * MARGIN)
        // The land round where players start is kept quiet
        if (horizontal(x + 0.5, z + 0.5, level.respawnData.pos()) < QUIET_SPAWN) return
        bands.add(OutlawBand(nextId(), BlockPos(x, 0, z)))
    }

    private fun nextId(): Int = (bands.maxOfOrNull { it.id } ?: 0) + 1

    /** Finds the spot nearest the band's mark that suits a camp and builds it there; or gives the band up. */
    private fun build(level: ServerLevel, band: OutlawBand) {
        val bells = bells(level, band.pos, VILLAGE_GAP + SEARCH)
        val spot = OFFSETS.asSequence()
            .map { (dx, dz) -> ground(level, band.pos.x + dx, band.pos.z + dz) }
            .firstOrNull { it != null && suits(level, it) && apart(level, it, bells) }
        if (spot == null) {
            bands.remove(band)
            setDirty()
            return
        }
        raise(level, band, spot)
    }

    /** Builds [band]'s camp at [spot], names its captain, puts a price on their head and musters the band. */
    private fun raise(level: ServerLevel, band: OutlawBand, spot: BlockPos) {
        val random = level.random
        val facing = Direction.Plane.HORIZONTAL.getRandomDirection(random)
        OutlawCamp.build(level, spot, facing, random)
        band.pos = spot
        band.facing = facing
        band.built = true
        band.region = Regions.regionAt(level, spot)?.name.orEmpty()
        // A name no other band at large goes by, if there's one left
        val taken = bands.filter { it !== band && it.built && !it.slain }
        for (attempt in 0 until 40) {
            band.name = random.nextInt(Outlaws.NAMES)
            band.epithet = random.nextInt(Outlaws.EPITHETS)
            if (taken.none { it.name == band.name || it.epithet == band.epithet }) break
        }
        // Wanted already for a thing or two before anyone came this way
        band.bounty = Outlaws.baseBounty(band.tier)
        repeat(1 + random.nextInt(2)) {
            band.crimes.add(0, Crime.entries[random.nextInt(Crime.entries.size)].id)
            band.bounty = raised(band)
        }
        band.recruited = VillageBoards.day(level)
        muster(level, band)
        GuildNews.add(level, "news.guildmark.outlaw.camp", "#${Outlaws.NAME_KEY}.${band.name}", "#${Outlaws.EPITHET_KEY}.${band.epithet}", where(band))
        // Far enough out that someone who falls here and wakes there isn't still in the fight
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
     * Whether a camp can stand at [pos]: dry, natural ground that's near enough flat, open (no trees anywhere in the
     * clearing: the tents and the tower want the room) and nothing anyone has built anywhere it would reach.
     */
    private fun suits(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.getFluidState(pos).isEmpty || !level.getFluidState(pos.below()).isEmpty) return false
        val floor = level.getBlockState(pos.below())
        if (!(floor.`is`(BlockTags.SUBSTRATE_OVERWORLD) || floor.`is`(BlockTags.BASE_STONE_OVERWORLD) || floor.`is`(BlockTags.SNOW))) return false
        val r = OutlawCamp.RADIUS
        var even = 0
        var columns = 0
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            columns++
            val top = ground(level, pos.x + dx, pos.z + dz) ?: return false
            if (!level.getFluidState(top).isEmpty || !level.getFluidState(top.below()).isEmpty) return false
            if (top.y > pos.y + 3) return false
            if (abs(top.y - pos.y) <= 1) even++
        }
        if (even < columns * 0.8) return false
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            for (dy in 0..5) if (level.getBlockState(pos.offset(dx, dy, dz)).`is`(BlockTags.LOGS)) return false
        }
        val reach = OutlawCamp.REACH
        for (dx in -reach..reach) for (dz in -reach..reach) {
            if (dx * dx + dz * dz > (reach + 0.5) * (reach + 0.5)) continue
            for (dy in -3..6) if (!TrollCamp.wild(level.getBlockState(pos.offset(dx, dy, dz)))) return false
        }
        return true
    }

    /** Whether [pos] is well away from villages ([bells]), lairs, the trolls' camps and other bands. */
    private fun apart(level: ServerLevel, pos: BlockPos, bells: List<BlockPos>): Boolean {
        if (Lairs.get(level).lairs.any { it.pos.closerThan(pos, LAIR_GAP) }) return false
        if (FieldBosses.get(level).sites.any { it.built && it.pos.closerThan(pos, TROLL_GAP) }) return false
        if (bands.any { it.built && it.pos.closerThan(pos, BAND_GAP) }) return false
        return bells.none { it.closerThan(pos, VILLAGE_GAP.toDouble()) }
    }

    /** Village bells within [radius] of [pos], in land that's loaded. */
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

    // ---- The band ----

    /** The captain and the band, each put straight in their place (seated, at their post, or abed by night). */
    private fun muster(level: ServerLevel, band: OutlawBand) {
        val layout = OutlawCamp.layout(band.pos, band.facing)
        spawnCaptain(level, band, layout.chair)
        val size = 2 + band.tier
        val roles = buildList {
            add(OutlawRole.LOOKOUT)
            add(OutlawRole.SENTRY)
            if (size >= 5) add(OutlawRole.SENTRY)
            while (this.size < size) add(OutlawRole.HAND)
        }
        roles.forEachIndexed { k, role -> spawnOutlaw(level, band, role, layout.doors[k % layout.doors.size]) }
        val places = places(band, layout, night(level))
        for (member in band.members) {
            val mob = level.getEntity(member.id) as? Mob ?: continue
            val place = places[member.id] ?: continue
            put(level, band, mob, place)
        }
    }

    /** Sets [mob] straight into [place], as if it had always been there. */
    private fun put(level: ServerLevel, band: OutlawBand, mob: Mob, place: Place) {
        mob.snapTo(place.pos.x + 0.5, place.pos.y.toDouble() + if (place is Seat) 0.5 else 0.0, place.pos.z + 0.5, mob.yRot, 0f)
        when (place) {
            is Seat -> {
                val cushion = OutlawCamp.cushion(level, place.pos)
                if (cushion != null && !cushion.isVehicle && mob.startRiding(cushion, true, false)) face(mob, band.pos)
            }
            is Bed -> if (mob.startSleeping(place.pos)) mob.isNoAi = true
            is Stand -> face(mob, band.pos)
        }
    }

    /** The band's captain, named for the band, with their banner on their head and something worth having on them. */
    private fun spawnCaptain(level: ServerLevel, band: OutlawBand, at: BlockPos): Mob? {
        val captain = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.STRUCTURE) ?: return null
        captain.snapTo(at.x + 0.5, at.y + 0.5, at.z + 0.5, level.random.nextFloat() * 360f, 0f)
        captain.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.STRUCTURE, null)
        captain.setPersistenceRequired()
        captain.setCanJoinRaid(false)
        captain.customName = band.title()
        // The band's banner worn as a raid captain wears it: only a patrol leader is a raid's captain, so it calls no omen
        captain.setItemSlot(EquipmentSlot.HEAD, Raid.getOminousBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)))
        captain.setDropChance(EquipmentSlot.HEAD, 0f)
        val tier = band.tier
        Relics.armChampion(level, captain, tier)
        captain.getAttribute(Attributes.MAX_HEALTH)?.addPermanentModifier(AttributeModifier(CAPTAIN_HEALTH, 1.5 + tier, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        captain.getAttribute(Attributes.ATTACK_DAMAGE)?.addPermanentModifier(AttributeModifier(CAPTAIN_DAMAGE, 0.3 * tier, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        captain.getAttribute(Attributes.ARMOR)?.addPermanentModifier(AttributeModifier(CAPTAIN_ARMOR, 2.0 + 2.0 * tier, AttributeModifier.Operation.ADD_VALUE))
        captain.health = captain.maxHealth
        captain.setData(ModAttachments.OUTLAW, band.id)
        level.addFreshEntity(captain)
        band.members.add(0, Outlaw(captain.uuid, OutlawRole.CAPTAIN))
        setDirty()
        return captain
    }

    /** One of the band: lookouts carry crossbows, sentries axes, and the rest whichever came to hand. */
    private fun spawnOutlaw(level: ServerLevel, band: OutlawBand, role: OutlawRole, at: BlockPos, type: EntityType<out Raider>? = null): Raider? {
        val kind: EntityType<out Raider> = type ?: when (role) {
            OutlawRole.LOOKOUT -> EntityTypes.PILLAGER
            OutlawRole.SENTRY, OutlawRole.CAPTAIN -> EntityTypes.VINDICATOR
            OutlawRole.HAND -> if (level.random.nextBoolean()) EntityTypes.PILLAGER else EntityTypes.VINDICATOR
        }
        val mob = kind.create(level, EntitySpawnReason.STRUCTURE) ?: return null
        mob.snapTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5, level.random.nextFloat() * 360f, 0f)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.STRUCTURE, null)
        mob.setPersistenceRequired()
        mob.setCanJoinRaid(false)
        mob.customName = Component.translatable("entity.guildmark.outlaw")
        mob.setData(ModAttachments.OUTLAW, band.id)
        level.addFreshEntity(mob)
        band.members.add(Outlaw(mob.uuid, role))
        setDirty()
        return mob
    }

    /** Someone new for the band, once a day at most and never while anyone's near enough to see them turn up. */
    private fun recruit(level: ServerLevel, band: OutlawBand, layout: OutlawCamp.Layout, players: List<ServerPlayer>) {
        val day = VillageBoards.day(level)
        if (band.recruited >= day) return
        if (players.any { horizontal(it.x, it.z, band.pos) < RECRUIT_UNSEEN }) return
        val captain = band.captain()
        if (captain != null && band.members.size - 1 >= 2 + band.tier) return
        band.recruited = day
        // A captain lost some other way than in a fight (one has to lead them) comes back to their seat
        if (captain == null) spawnCaptain(level, band, layout.chair)
        else {
            val role = when {
                band.members.none { it.role == OutlawRole.LOOKOUT } -> OutlawRole.LOOKOUT
                band.members.none { it.role == OutlawRole.SENTRY } -> OutlawRole.SENTRY
                else -> OutlawRole.HAND
            }
            spawnOutlaw(level, band, role, layout.doors[level.random.nextInt(layout.doors.size)])
        }
        setDirty()
    }

    // ---- Life in the camp ----

    /** Where one of the band belongs just now. */
    private sealed class Place(val pos: BlockPos)
    private class Seat(pos: BlockPos) : Place(pos)
    private class Stand(pos: BlockPos) : Place(pos)
    private class Bed(pos: BlockPos) : Place(pos)

    /** How a band is getting on: at peace or fighting, who's off doing what, who's laughing. */
    private class Mood {
        var alarm = false
        /** Half-seconds since the last sign of trouble, in a fight. */
        var quiet = 0
        var rallied = false
        val bar = ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10)
        /** Half-seconds each has been trying to get to their place. */
        val stuck = HashMap<UUID, Int>()
        /** Half-seconds each has been nowhere to be found while someone's near. */
        val missing = HashMap<UUID, Int>()
        var errand: UUID? = null
        var errandAt: BlockPos = BlockPos.ZERO
        var errandUntil = 0L
        /** Who's laughing, until when. */
        val cheering = HashMap<UUID, Long>()
        /** Half-seconds since the band last looked about for strays of theirs. */
        var census = 0
    }

    /** Where each of [band] belongs at this hour (see the camp's [layout]). */
    private fun places(band: OutlawBand, layout: OutlawCamp.Layout, night: Boolean): Map<UUID, Place> {
        val out = HashMap<UUID, Place>()
        val seats = ArrayDeque(layout.seats)
        val beds = ArrayDeque(layout.beds.drop(1))
        val posts = ArrayDeque(layout.posts)
        var watch = false
        var ring = 0
        // More of them than there are stools: they stand about the fire
        fun seat(): Place = seats.removeFirstOrNull()?.let { Seat(it) }
            ?: Stand(band.pos.relative(Direction.from2DDataValue(ring++ % 4), 2))
        fun bed(): Place = beds.removeFirstOrNull()?.let { Bed(it) } ?: seat()
        // The watch by night: a sentry if there is one, else the first of the rest
        val watcher = band.members.firstOrNull { it.role == OutlawRole.SENTRY } ?: band.members.firstOrNull { it.role == OutlawRole.HAND }
        for (member in band.members) {
            out[member.id] = when {
                member.role == OutlawRole.CAPTAIN -> if (night) Bed(layout.beds[0]) else Seat(layout.chair)
                !night && member.role == OutlawRole.LOOKOUT -> Stand(layout.lookout)
                !night && member.role == OutlawRole.SENTRY -> posts.removeFirstOrNull()?.let { Stand(it) } ?: seat()
                !night -> seat()
                member == watcher && !watch -> {
                    watch = true
                    seat()
                }
                else -> bed()
            }
        }
        return out
    }

    /** Half a second of [band]'s life with someone near. */
    private fun live(level: ServerLevel, band: OutlawBand, players: List<ServerPlayer>) {
        val mood = moods.getOrPut(band.id) { Mood() }
        val mobs = gather(level, band, mood)
        if (band.slain) {
            scatter(level, band, mobs, players)
            return
        }
        val layout = OutlawCamp.layout(band.pos, band.facing)
        // Trouble: one of them has a player in their sights
        val intruder = mobs.firstNotNullOfOrNull { (_, mob) -> (mob.target as? Player)?.takeIf(::foe) }
        if (intruder != null && !mood.alarm) alarm(level, band, mood, mobs, intruder)
        if (mood.alarm) {
            fight(level, band, mood, layout, mobs, players, intruder != null)
            return
        }
        recruit(level, band, layout, players)
        settle(level, band, mood, layout, mobs, players)
    }

    /** The band's members that are here, losing track of any that have been gone too long and taking in strays. */
    private fun gather(level: ServerLevel, band: OutlawBand, mood: Mood): List<Pair<Outlaw, Mob>> {
        if (++mood.census >= CENSUS) {
            mood.census = 0
            val known = band.members.mapTo(HashSet()) { it.id }
            level.getEntitiesOfClass(Mob::class.java, AABB(band.pos).inflate(STRAY_RANGE)) {
                it.isAlive && it.hasData(ModAttachments.OUTLAW) && it.getData(ModAttachments.OUTLAW) == band.id && it.uuid !in known
            }
                .forEach {
                    band.members.add(Outlaw(it.uuid, OutlawRole.HAND))
                    setDirty()
                }
        }
        val out = ArrayList<Pair<Outlaw, Mob>>()
        for (member in band.members.toList()) {
            val mob = level.getEntity(member.id) as? Mob
            if (mob == null || !mob.isAlive) {
                if (mood.missing.merge(member.id, 1, Int::plus)!! > MISSING_LIMIT) {
                    band.members.remove(member)
                    mood.missing.remove(member.id)
                    setDirty()
                }
                continue
            }
            mood.missing.remove(member.id)
            out.add(member to mob)
        }
        return out
    }

    /** The horn: everyone up, out of bed and at whoever it is. */
    private fun alarm(level: ServerLevel, band: OutlawBand, mood: Mood, mobs: List<Pair<Outlaw, Mob>>, intruder: Player) {
        mood.alarm = true
        mood.quiet = 0
        mood.errand = null
        level.playSound(null, band.pos, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 4.0f, 1.15f)
        for ((_, mob) in mobs) rouse(mob, intruder)
    }

    /** Someone hurt one of a band: the band's roused at once, whoever's watching. */
    fun onHurt(level: ServerLevel, victim: LivingEntity, source: DamageSource) {
        val id = victim.getData(ModAttachments.OUTLAW)
        if (id < 0) return
        val band = band(id) ?: return
        if (!band.built || band.slain) return
        val attacker = (source.entity as? Player)?.takeIf(::foe) ?: return
        val mood = moods.getOrPut(band.id) { Mood() }
        if (mood.alarm) {
            mood.quiet = 0
            return
        }
        val mobs = band.members.mapNotNull { member -> (level.getEntity(member.id) as? Mob)?.takeIf { it.isAlive }?.let { member to it } }
        alarm(level, band, mood, mobs, attacker)
    }

    private fun fight(level: ServerLevel, band: OutlawBand, mood: Mood, layout: OutlawCamp.Layout, mobs: List<Pair<Outlaw, Mob>>, players: List<ServerPlayer>, troubled: Boolean) {
        val foes = players.filter(::foe)
        for ((_, mob) in mobs) {
            if (mob.isSleeping || mob.isNoAi || mob.isPassenger) rouse(mob, null)
            (mob as? Raider)?.let { if (it.isCelebrating) it.isCelebrating = false }
            if (mob.target == null) foes.filter { it.distanceToSqr(mob) < JOIN_RANGE * JOIN_RANGE }.minByOrNull { it.distanceToSqr(mob) }?.let { mob.target = it }
        }
        mood.cheering.clear()
        val captain = mobs.firstOrNull { it.first.role == OutlawRole.CAPTAIN }?.second
        if (captain != null) {
            mood.bar.name = band.title()
            mood.bar.progress = (captain.health / captain.maxHealth).coerceIn(0f, 1f)
            val watching = players.filter { it.distanceToSqr(captain) < BAR_RANGE * BAR_RANGE }
            for (player in mood.bar.players.toList()) if (player !in watching) mood.bar.removePlayer(player)
            for (player in watching) mood.bar.addPlayer(player)
            if (!mood.rallied && captain.health < captain.maxHealth * 0.5f) rally(level, band, mood, layout, captain, foes)
        } else mood.bar.removeAllPlayers()
        // It's over once no one's been near for a while
        val near = troubled || foes.any { horizontal(it.x, it.z, band.pos) < CALM_RANGE }
        if (near) mood.quiet = 0 else if (++mood.quiet >= CALM_AFTER) calm(mood)
    }

    /** Half strength: the captain blows the horn again and calls two more out of the tents. */
    private fun rally(level: ServerLevel, band: OutlawBand, mood: Mood, layout: OutlawCamp.Layout, captain: Mob, foes: List<ServerPlayer>) {
        mood.rallied = true
        level.playSound(null, captain.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 4.0f, 0.8f)
        captain.playSound(SoundEvents.VINDICATOR_CELEBRATE, 2.0f, 0.7f)
        val foe = (captain.lastHurtByMob as? Player)?.takeIf(::foe) ?: foes.minByOrNull { it.distanceToSqr(captain) }
        for (player in foes) {
            if (player.distanceToSqr(captain) < BAR_RANGE * BAR_RANGE) player.sendOverlayMessage(Component.translatable("message.guildmark.outlaw_rally", band.title()))
        }
        val room = MAX_BAND - (band.members.size - 1)
        val doors = layout.doors.shuffled()
        for (k in 0 until minOf(2, room)) {
            val door = doors[k % doors.size]
            val mob = spawnOutlaw(level, band, OutlawRole.HAND, door, EntityTypes.VINDICATOR) ?: continue
            level.sendParticles(ParticleTypes.POOF, mob.x, mob.y + 0.8, mob.z, 8, 0.3, 0.4, 0.3, 0.02)
            if (foe != null) mob.target = foe
        }
    }

    private fun calm(mood: Mood) {
        mood.alarm = false
        mood.quiet = 0
        mood.rallied = false
        mood.bar.removeAllPlayers()
        mood.stuck.clear()
    }

    /** Everyone to their place, the odd errand, the odd laugh. */
    private fun settle(level: ServerLevel, band: OutlawBand, mood: Mood, layout: OutlawCamp.Layout, mobs: List<Pair<Outlaw, Mob>>, players: List<ServerPlayer>) {
        val night = night(level)
        val now = level.gameTime
        // An errand ends, or someone gets up to do something
        if (mood.errand != null && (night || now >= mood.errandUntil || mobs.none { it.second.uuid == mood.errand })) mood.errand = null
        if (!night && mood.errand == null && level.random.nextInt(ERRAND_ODDS) == 0) {
            val idle = mobs.filter { it.first.role == OutlawRole.HAND && it.second.target == null }
            if (idle.isNotEmpty()) {
                mood.errand = idle[level.random.nextInt(idle.size)].second.uuid
                mood.errandAt = layout.chores[level.random.nextInt(layout.chores.size)]
                mood.errandUntil = now + ERRAND_MIN + level.random.nextInt(ERRAND_MIN)
            }
        }
        val places = places(band, layout, night)
        for ((member, mob) in mobs) {
            prune(mob)
            val target = mob.target
            if (target != null) {
                // Seeing to a zombie or a wandering villager: on their feet for it; anything else is forgotten
                if (target.isAlive && (target !is Player || foe(target)) && mob.distanceToSqr(target) < FORGET * FORGET) {
                    if (mob.isSleeping) rouse(mob, null)
                    if (mob.isPassenger) mob.stopRiding()
                    continue
                }
                mob.target = null
            }
            val place = if (member.id == mood.errand) Stand(mood.errandAt) else places[member.id] ?: continue
            go(level, band, mood, mob, place, players)
        }
        cheer(level, mood, mobs, night)
    }

    /** [mob] makes its way to [place], and sits, lies down or stands there once it's arrived. */
    private fun go(level: ServerLevel, band: OutlawBand, mood: Mood, mob: Mob, place: Place, players: List<ServerPlayer>) {
        // Turfed out of a bed that's gone: awake for good
        if (mob.isNoAi && !mob.isSleeping) mob.isNoAi = false
        when (place) {
            is Bed -> {
                if (mob.isSleeping) {
                    if (mob.sleepingPos.orElse(null) == place.pos) return
                    rouse(mob, null)
                }
                if (mob.isPassenger) mob.stopRiding()
                if (close(mob, place.pos, BED_REACH)) {
                    mood.stuck.remove(mob.uuid)
                    val state = level.getBlockState(place.pos)
                    if (state.block is BedBlock && !state.getValue(BedBlock.OCCUPIED) && mob.startSleeping(place.pos)) {
                        mob.navigation.stop()
                        mob.isNoAi = true
                    }
                    return
                }
                walk(mood, mob, place.pos, players)
            }
            is Seat -> {
                val cushion = OutlawCamp.cushion(level, place.pos)
                if (cushion != null && mob.vehicle === cushion) return
                if (mob.isSleeping) rouse(mob, null)
                if (mob.isPassenger) mob.stopRiding()
                if (close(mob, place.pos, SEAT_REACH)) {
                    mood.stuck.remove(mob.uuid)
                    // Its cushion gone (or someone in it): stands by the stool instead
                    if (cushion != null && !cushion.isVehicle && mob.startRiding(cushion, true, false)) {
                        mob.navigation.stop()
                        face(mob, band.pos)
                    }
                    return
                }
                walk(mood, mob, place.pos, players)
            }
            is Stand -> {
                if (mob.isSleeping) rouse(mob, null)
                if (mob.isPassenger) mob.stopRiding()
                if (close(mob, place.pos, STAND_REACH)) {
                    mood.stuck.remove(mob.uuid)
                    if (!mob.navigation.isDone) mob.navigation.stop()
                    return
                }
                walk(mood, mob, place.pos, players)
            }
        }
    }

    /** On towards [pos]; if they've been at it a long while with no one looking, they're simply there. */
    private fun walk(mood: Mood, mob: Mob, pos: BlockPos, players: List<ServerPlayer>) {
        val tries = mood.stuck.merge(mob.uuid, 1, Int::plus)!!
        if (tries > STUCK_LIMIT && players.none { it.distanceToSqr(mob) < UNSEEN * UNSEEN && it.hasLineOfSight(mob) }) {
            mob.navigation.stop()
            mob.teleportTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
            mood.stuck.remove(mob.uuid)
            return
        }
        if (mob.navigation.isDone || tries % 8 == 0) mob.navigation.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, WALK_SPEED)
    }

    /** Something said by the fire sets them laughing, and the laugh dies down. */
    private fun cheer(level: ServerLevel, mood: Mood, mobs: List<Pair<Outlaw, Mob>>, night: Boolean) {
        val now = level.gameTime
        mood.cheering.entries.removeIf { (id, until) ->
            if (now < until) return@removeIf false
            (level.getEntity(id) as? Raider)?.isCelebrating = false
            true
        }
        if (night || mood.cheering.isNotEmpty() || level.random.nextInt(CHEER_ODDS) != 0) return
        for ((_, mob) in mobs) {
            if (mob.vehicle !is Cushion || mob.target != null || level.random.nextInt(3) == 0) continue
            val raider = mob as? Raider ?: continue
            raider.isCelebrating = true
            mood.cheering[mob.uuid] = now + 30 + level.random.nextInt(40)
            val laugh = if (mob is Vindicator) SoundEvents.VINDICATOR_CELEBRATE else SoundEvents.PILLAGER_CELEBRATE
            mob.playSound(laugh, 0.9f, 0.85f + level.random.nextFloat() * 0.3f)
        }
    }

    // ---- The captain's fall ----

    /** One of a band died: struck off its roll. If it was the captain, the band is finished and the bounty is earned. */
    fun onDeath(level: ServerLevel, victim: LivingEntity) {
        val id = victim.getData(ModAttachments.OUTLAW)
        if (id < 0) return
        val band = band(id) ?: return
        val member = band.members.firstOrNull { it.id == victim.uuid } ?: return
        band.members.remove(member)
        setDirty()
        if (member.role != OutlawRole.CAPTAIN || band.slain) return
        band.slain = true
        moods[band.id]?.let { calm(it) }
        val tier = band.tier
        if (level.gameRules.get(GameRules.MOB_DROPS)) {
            victim.spawnAtLocation(level, Relics.roll(level, tier + 2, if (tier >= 3) Rarity.RARE else Rarity.UNCOMMON))
            victim.spawnAtLocation(level, ItemStack(ModItems.GUILD_MARK.get(), 8 + tier * 6 + level.random.nextInt(6)))
            if (level.random.nextFloat() < 0.3f) victim.spawnAtLocation(level, Relics.cache(Rarity.UNCOMMON))
        }
        val heroes = level.players().filter { it.isAlive && it.distanceToSqr(victim) < HERO_RANGE * HERO_RANGE }
        for (player in heroes) {
            player.sendSystemMessage(Component.translatable("message.guildmark.outlaw_slain", band.title()))
            player.sendOverlayMessage(Component.translatable("message.guildmark.outlaw_rout", band.title()))
        }
        Bounties.onSlain(heroes, band.id)
        GuildNews.add(level, "news.guildmark.outlaw_slain", heroes.joinToString(", ") { it.name.string }.ifEmpty { "?" },
            "#${Outlaws.NAME_KEY}.${band.name}", "#${Outlaws.EPITHET_KEY}.${band.epithet}", where(band))
    }

    /** With the captain dead the band breaks: each runs from whoever's nearest, and is gone once out of their sight. */
    private fun scatter(level: ServerLevel, band: OutlawBand, mobs: List<Pair<Outlaw, Mob>>, players: List<ServerPlayer>) {
        for ((member, mob) in mobs) {
            if (mob.isSleeping || mob.isNoAi || mob.isPassenger) rouse(mob, null)
            if (routed.add(mob)) {
                mob.targetSelector.removeAllGoals { true }
                (mob as? Raider)?.isCelebrating = false
            }
            mob.target = null
            val nearest = players.minByOrNull { it.distanceToSqr(mob) }
            val distance = nearest?.let { sqrt(it.distanceToSqr(mob)) } ?: Double.MAX_VALUE
            if (nearest == null || distance > GONE_FAR || (distance > GONE_UNSEEN && !nearest.hasLineOfSight(mob))) {
                mob.discard()
                band.members.remove(member)
                setDirty()
                continue
            }
            if (mob.navigation.isDone && mob is PathfinderMob) {
                DefaultRandomPos.getPosAway(mob, 16, 7, nearest.position())?.let { mob.navigation.moveTo(it.x, it.y, it.z, FLEE_SPEED) }
            }
        }
    }

    // ---- What they get up to ----

    /** Once a day: each band at large may do something that puts the price on its captain's head up. */
    private fun reckon(level: ServerLevel) {
        val day = VillageBoards.day(level)
        if (day == reckoned) return
        val first = reckoned < 0
        reckoned = day
        setDirty()
        if (first) return
        for (band in bands) {
            if (!band.built || band.slain || band.captain() == null) continue
            if (level.random.nextFloat() >= CRIME_CHANCE) continue
            commit(level, band, Crime.entries[level.random.nextInt(Crime.entries.size)])
        }
    }

    /** [band] does [crime]: it's on their posters, the bounty goes up and the news has it. */
    fun commit(level: ServerLevel, band: OutlawBand, crime: Crime) {
        band.crimes.add(0, crime.id)
        while (band.crimes.size > KEEP_CRIMES) band.crimes.removeLast()
        band.bounty = raised(band)
        if (band.region.isEmpty()) band.region = Regions.regionAt(level, band.pos)?.name.orEmpty()
        GuildNews.add(level, "news.guildmark.outlaw.${crime.id}", "#${Outlaws.NAME_KEY}.${band.name}", "#${Outlaws.EPITHET_KEY}.${band.epithet}", where(band))
        setDirty()
    }

    /** The bounty after one more crime: up by a fifth of what it started at, to at most two and a half times that. */
    private fun raised(band: OutlawBand): Int {
        val base = Outlaws.baseBounty(band.tier)
        return (band.bounty + base / 5).coerceAtMost(base * 5 / 2)
    }

    /** The land the band's in, for the news: its name, or the wilds while it has none yet. */
    private fun where(band: OutlawBand): String = band.region.ifEmpty { "#${Outlaws.WILDS}" }

    // ---- For testing ----

    /** A camp 24 to 36 blocks from [near], built at once on the first ground that suits it. */
    fun createNear(level: ServerLevel, near: BlockPos): OutlawBand? {
        if (level.dimension() != Level.OVERWORLD) return null
        val random = level.random
        repeat(60) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 24 + random.nextInt(13)
            val x = near.x + (kotlin.math.cos(angle) * distance).toInt()
            val z = near.z + (kotlin.math.sin(angle) * distance).toInt()
            if (!loaded(level, BlockPos(x, 0, z), OutlawCamp.REACH + 1)) return@repeat
            val spot = ground(level, x, z) ?: return@repeat
            if (!suits(level, spot)) return@repeat
            val band = OutlawBand(nextId(), spot)
            bands.add(band)
            raise(level, band, spot)
            return band
        }
        return null
    }

    /** The nearest band at large to [pos], built or not. */
    fun nearest(pos: BlockPos): OutlawBand? = bands.filter { it.built && !it.slain }.minByOrNull { horizontal(pos, it.pos) }

    companion object {
        /** The side of a cell, in blocks; each holds at most one band. */
        private const val CELL = 768
        private const val MARGIN = 96
        private const val CHANCE = 0.5f
        private const val SALT = 0x0B7A3C29L
        private const val BUILD_RANGE = 112.0
        private const val SEARCH = 32
        private const val QUIET_SPAWN = 400.0
        private const val VILLAGE_GAP = 192
        private const val LAIR_GAP = 96.0
        private const val TROLL_GAP = 160.0
        private const val BAND_GAP = 384.0
        private const val FIRE_MIN = 48
        private const val FIRE_MAX = 60
        /** How near someone must be for a camp to go about its day. */
        private const val LIVE_RANGE = 96.0
        /** How far off someone must be for a band to take someone new on unseen. */
        private const val RECRUIT_UNSEEN = 48.0
        /** The most a band ever numbers, besides its captain. */
        private const val MAX_BAND = 7
        private const val HERO_RANGE = 48.0
        private const val BAR_RANGE = 48.0
        /** How near one of the band must be to a foe to go for them when the horn goes up. */
        private const val JOIN_RANGE = 40.0
        /** How near anyone must be to keep a fight going, and how long it takes to die down once they're gone. */
        private const val CALM_RANGE = 48.0
        private const val CALM_AFTER = 60
        /** Beyond this they forget whoever they were after. */
        private const val FORGET = 48.0
        private const val SEAT_REACH = 1.6
        private const val BED_REACH = 2.6
        private const val STAND_REACH = 1.3
        private const val WALK_SPEED = 0.75
        private const val FLEE_SPEED = 1.3
        /** Half-seconds of trying before they're set in their place, if no one within [UNSEEN] can see them. */
        private const val STUCK_LIMIT = 80
        private const val UNSEEN = 40.0
        /** Half-seconds one can be missing with someone near before they're taken as gone for good. */
        private const val MISSING_LIMIT = 240
        /** Half-seconds between looks about for strays, and how far they look. */
        private const val CENSUS = 20
        private const val STRAY_RANGE = 48.0
        /** A routed outlaw is gone once this far from the nearest player, or this far and out of their sight. */
        private const val GONE_FAR = 40.0
        private const val GONE_UNSEEN = 18.0
        /** One in this many half-seconds someone gets up to do something, for this many ticks or up to twice that. */
        private const val ERRAND_ODDS = 30
        private const val ERRAND_MIN = 160
        private const val CHEER_ODDS = 70
        private const val CRIME_CHANCE = 0.4f
        private const val KEEP_CRIMES = 3

        private val CAPTAIN_HEALTH = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "outlaw_captain_health")
        private val CAPTAIN_DAMAGE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "outlaw_captain_damage")
        private val CAPTAIN_ARMOR = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "outlaw_captain_armor")

        private val OFFSETS: List<Pair<Int, Int>> = buildList {
            add(0 to 0)
            for (r in intArrayOf(12, 24, 32)) for (k in 0 until 8) {
                val a = Math.PI * 2 * k / 8
                add((kotlin.math.cos(a) * r).toInt() to (kotlin.math.sin(a) * r).toInt())
            }
        }

        /** Mobs whose own wanderings were stopped, so the camp says where they go (new each time one is loaded). */
        private val pruned: MutableSet<Mob> = Collections.newSetFromMap(WeakHashMap())
        /** The routed, whose minds are on nothing but getting away. */
        private val routed: MutableSet<Mob> = Collections.newSetFromMap(WeakHashMap())

        private fun prune(mob: Mob) {
            if (pruned.add(mob)) mob.goalSelector.removeAllGoals { it is RandomStrollGoal }
        }

        /** Up, awake and on their feet, and at [foe] if given. */
        private fun rouse(mob: Mob, foe: Player?) {
            if (mob.isSleeping) mob.stopSleeping()
            mob.isNoAi = false
            if (mob.isPassenger) mob.stopRiding()
            mob.navigation.stop()
            if (foe != null && mob.target == null && mob.distanceToSqr(foe) < JOIN_RANGE * JOIN_RANGE) mob.target = foe
        }

        /** Someone the band will fight. */
        private fun foe(player: Player): Boolean = player.isAlive && !player.isSpectator && !player.isCreative

        private fun night(level: ServerLevel): Boolean = Math.floorMod(level.overworldClockTime, 24000L) in 12600L..23400L

        private fun face(mob: Mob, at: BlockPos) {
            val yaw = (Mth.atan2(at.z + 0.5 - mob.z, at.x + 0.5 - mob.x) * (180.0 / Math.PI) - 90.0).toFloat()
            mob.yRot = yaw
            mob.yBodyRot = yaw
            mob.yHeadRot = yaw
        }

        private fun close(mob: Mob, pos: BlockPos, reach: Double): Boolean =
            horizontal(mob.x, mob.z, pos) < reach && abs(mob.y - pos.y) < 2.5

        private fun horizontal(x: Double, z: Double, pos: BlockPos): Double {
            val dx = x - (pos.x + 0.5)
            val dz = z - (pos.z + 0.5)
            return sqrt(dx * dx + dz * dz)
        }

        private fun horizontal(a: BlockPos, b: BlockPos): Double = horizontal(a.x + 0.5, a.z + 0.5, b)

        private val CODEC: Codec<OutlawCamps> = RecordCodecBuilder.create { i ->
            i.group(
                OutlawBand.CODEC.listOf().optionalFieldOf("bands", listOf()).forGetter { it.bands },
                Codec.LONG.listOf().optionalFieldOf("decided", listOf()).forGetter { it.decided.toList() },
                Codec.LONG.optionalFieldOf("reckoned", -1L).forGetter { it.reckoned },
            ).apply(i) { bands, decided, reckoned -> OutlawCamps(bands.toMutableList(), decided.toHashSet(), reckoned) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "outlaw_camps"), Supplier { OutlawCamps() }, CODEC)

        /** The bands are kept with the overworld, whichever level asks. */
        fun get(level: ServerLevel): OutlawCamps = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
