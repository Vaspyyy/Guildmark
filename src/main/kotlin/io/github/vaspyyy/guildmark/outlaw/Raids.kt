package io.github.vaspyyy.guildmark.outlaw

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.raid.Raid
import net.minecraft.world.entity.raid.Raider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.sqrt

/**
 * Outlaw raids. Now and then at dusk a band goes after a village within reach of its camp, if someone's there to see
 * it: the bell rings, a horn sounds, and a handful of the band come in over the fields with torches burning, while the
 * villagers run for their doors. The raiders are out for plunder and a hostage, not blood: they fight anyone who stands
 * against them but never strike a villager. Beat them all off and the village won't forget it; hold out until they
 * give up and they make off, one of the villagers led away on a rope, and if they get clear they keep them in their
 * camp until someone brings them home (see Captives). Either way the raid goes on the captain's posters.
 */
object Raids {
    /** Marks a raider, so one left over from before a restart can be sent off. */
    const val TAG = "guildmark.raider"

    /** How far from its camp a band will go to raid. */
    private const val RANGE = 320

    /** How near a village's bell someone must be for a raid to start: raids are only ever seen. */
    private const val WATCHED = 96.0

    /** Days a band leaves between raids. */
    private const val GAP_DAYS = 3

    /** The chance, each dusk, that a band rested long enough goes raiding. */
    private const val CHANCE = 0.35f

    /** The dusk hours a raid sets out in. */
    private val DUSK = 12600L..13600L

    /** How long they raid before making off, in ticks. */
    private const val LENGTH = 3000L

    /** Where the raiders come in from: this far out from the bell, on the camp's side. */
    private const val APPROACH = 40

    /** Making off, they're clear once all of them are this far from the bell with nobody close behind. */
    private const val CLEAR = 60.0

    /** Nobody within this of the bell and the raid's given up (no one's left to see it). */
    private const val DESERTED = 160.0

    /** What beating off a raid does for each defender's standing with the village. */
    private const val STANDING_GAIN = 20

    private class Foray(
        val band: Int,
        val bell: BlockPos,
        val home: BlockPos,
        val from: BlockPos,
        val raiders: MutableList<UUID>,
        val size: Int,
        val started: Long,
        val bar: ServerBossEvent,
    ) {
        /** Game time they started making off, or -1. */
        var leaving = -1L
        /** The villager they're leading away. */
        var taken: UUID? = null
    }

    /** Raids under way, by band; not saved: a restart ends them. */
    private val raids = HashMap<Int, Foray>()

    /** The dusk each band was last thought about, so each gets one roll an evening. */
    private val rolled = HashMap<Int, Long>()

    /** Every 10 ticks, in the overworld. */
    fun tick(level: ServerLevel) {
        if (level.gameTime % 100L == 0L && Math.floorMod(level.overworldClockTime, 24000L) in DUSK) evening(level)
        for (raid in raids.values.toList()) run(level, raid)
    }

    /** At dusk: each band rested long enough may set out for a watched village in reach. */
    private fun evening(level: ServerLevel) {
        val camps = OutlawCamps.get(level)
        val day = VillageBoards.day(level)
        for (band in camps.bands) {
            if (!band.built || band.slain || band.captain() == null || band.id in raids) continue
            if (rolled[band.id] == day) continue
            rolled[band.id] = day
            if (band.raided >= 0 && day - band.raided < GAP_DAYS) continue
            if (level.random.nextFloat() >= CHANCE) continue
            val bell = bells(level, band.pos, RANGE)
                .filter { bell -> level.players().any { !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(bell)) < WATCHED * WATCHED } }
                .minByOrNull { it.distSqr(band.pos) } ?: continue
            start(level, band, bell)
        }
    }

    /** [band] raids the village round [bell], now. */
    fun start(level: ServerLevel, band: OutlawBand, bell: BlockPos): Boolean {
        if (band.id in raids) return false
        val from = approach(level, bell, band.pos) ?: return false
        val size = (3 + band.tier / 2).coerceAtMost(6)
        val home = Captives.homeOf(level, bell)
        val village = villageName(level, home)
        val bar = ServerBossEvent(UUID.randomUUID(), Component.translatable("event.guildmark.raid", band.title(), village), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_6)
        val raid = Foray(band.id, bell, home, from, mutableListOf(), size, level.gameTime, bar)
        for (k in 0 until size) {
            val type: EntityType<out Raider> = if (k == 0 || level.random.nextFloat() < 0.6f) EntityTypes.VINDICATOR else EntityTypes.PILLAGER
            spawn(level, band, type, from, leads = k == 0)?.let { raid.raiders.add(it.uuid) }
        }
        if (raid.raiders.isEmpty()) return false
        raids[band.id] = raid
        band.raided = VillageBoards.day(level)
        OutlawCamps.get(level).commit(level, band, Crime.RAID)
        // The alarm: the bell rung, a horn out over the fields
        (level.getBlockState(bell).block as? BellBlock)?.attemptToRing(level, bell, Direction.NORTH)
        level.playSound(null, from, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 48f, 0.85f)
        for (player in watchers(level, raid)) player.sendSystemMessage(Component.translatable("message.guildmark.raid_start", band.title(), village))
        return true
    }

    /** A raider of [band] come in at [at], a torch burning in their off hand; the first wears the band's banner. */
    private fun spawn(level: ServerLevel, band: OutlawBand, type: EntityType<out Raider>, at: BlockPos, leads: Boolean): Raider? {
        val mob = type.create(level, EntitySpawnReason.EVENT) ?: return null
        val x = at.x + 0.5 + level.random.nextInt(5) - 2
        val z = at.z + 0.5 + level.random.nextInt(5) - 2
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble()
        mob.snapTo(x, y, z, level.random.nextFloat() * 360f, 0f)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null)
        mob.setCanJoinRaid(false)
        mob.addTag(TAG)
        mob.customName = Component.translatable("entity.guildmark.raider")
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.TORCH))
        mob.setDropChance(EquipmentSlot.OFFHAND, 0f)
        if (leads) {
            mob.setItemSlot(EquipmentSlot.HEAD, Raid.getOminousBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)))
            mob.setDropChance(EquipmentSlot.HEAD, 0f)
        }
        return if (level.addFreshEntity(mob)) mob else null
    }

    private fun run(level: ServerLevel, raid: Foray) {
        val band = OutlawCamps.get(level).band(raid.band)
        val alive = raid.raiders.mapNotNull { level.getEntity(it) as? Mob }.filter { it.isAlive }
        val watching = watchers(level, raid)
        raid.bar.progress = (alive.size.toFloat() / raid.size).coerceIn(0f, 1f)
        for (player in raid.bar.players.toList()) if (player !in watching) raid.bar.removePlayer(player)
        for (player in watching) raid.bar.addPlayer(player)
        val taken = raid.taken?.let { level.getEntity(it) as? Mob }
        when {
            alive.isEmpty() -> return repelled(level, raid, band, taken)
            band == null || level.players().none { !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(raid.bell)) < DESERTED * DESERTED } -> {
                // Nobody's left to see it: the raid peters out
                alive.forEach { it.discard() }
                taken?.removeLeash()
                return end(raid)
            }
        }
        if (raid.leaving < 0 && (level.gameTime - raid.started > LENGTH || level.isBrightOutside)) leave(level, raid, alive)
        for (mob in alive) {
            // Torches burning
            if (level.random.nextInt(3) == 0) {
                val side = Math.toRadians(mob.yBodyRot.toDouble() + 60.0)
                level.sendParticles(ParticleTypes.FLAME, mob.x - Math.sin(side) * 0.45, mob.y + 1.35, mob.z + Math.cos(side) * 0.45, 1, 0.02, 0.04, 0.02, 0.005)
                level.sendParticles(ParticleTypes.SMOKE, mob.x - Math.sin(side) * 0.45, mob.y + 1.5, mob.z + Math.cos(side) * 0.45, 1, 0.03, 0.05, 0.03, 0.01)
            }
            if (mob.target != null && raid.leaving < 0) continue
            if (raid.leaving >= 0) {
                // Away towards their camp, never mind who's chasing
                mob.target = null
                if (mob.navigation.isDone || level.gameTime % 40L == 0L) mob.navigation.moveTo(raid.from.x + 0.5, raid.from.y.toDouble(), raid.from.z + 0.5, 1.05)
            } else if (mob.navigation.isDone) {
                // Prowling the lanes round the bell
                val dx = level.random.nextInt(25) - 12
                val dz = level.random.nextInt(25) - 12
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, raid.bell.x + dx, raid.bell.z + dz)
                mob.navigation.moveTo(raid.bell.x + dx + 0.5, y.toDouble(), raid.bell.z + dz + 0.5, 0.9)
            }
        }
        if (raid.leaving >= 0) {
            // The hostage stays tied to whoever's leading them
            if (taken != null && taken.isAlive && !taken.isLeashed) alive.minByOrNull { it.distanceToSqr(taken) }?.takeIf { it.distanceToSqr(taken) < 100.0 }?.let { taken.setLeashedTo(it, true) }
            // Once they're all clear with nobody close behind, they're gone
            val clear = alive.all { it.distanceToSqr(Vec3.atCenterOf(raid.bell)) > CLEAR * CLEAR } &&
                alive.none { mob -> level.players().any { !it.isSpectator && it.distanceToSqr(mob) < 24.0 * 24.0 } }
            if (clear) escaped(level, raid, band, alive, taken)
        }
    }

    /** Time's up: they make off, leading away whichever grown villager or townsperson is nearest to hand. */
    private fun leave(level: ServerLevel, raid: Foray, alive: List<Mob>) {
        raid.leaving = level.gameTime
        val band = OutlawCamps.get(level).band(raid.band)
        val victim = if (band != null && band.captives.size < Captives.MAX) {
            level.getEntitiesOfClass(Mob::class.java, AABB.ofSize(Vec3.atCenterOf(raid.bell), 80.0, 24.0, 80.0)) { takeable(it) }
                .minByOrNull { victim -> alive.minOf { it.distanceToSqr(victim) } }
        } else null
        val leader = victim?.let { v -> alive.minByOrNull { it.distanceToSqr(v) } }
        if (victim != null && leader != null) {
            victim.setLeashedTo(leader, true)
            raid.taken = victim.uuid
            for (player in watchers(level, raid)) player.sendSystemMessage(Component.translatable("message.guildmark.raid_taking", nameOf(victim)))
        }
        level.playSound(null, raid.bell, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 32f, 1.1f)
    }

    /** Every raider down: the village is safe, the hostage too, and its defenders are remembered for it. */
    private fun repelled(level: ServerLevel, raid: Foray, band: OutlawBand?, taken: Mob?) {
        taken?.removeLeash()
        val village = villageName(level, raid.home)
        level.playSound(null, raid.bell, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 2f, 1f)
        (level.getBlockState(raid.bell).block as? BellBlock)?.attemptToRing(level, raid.bell, Direction.SOUTH)
        for (player in watchers(level, raid)) {
            player.sendSystemMessage(Component.translatable("message.guildmark.raid_beaten", village))
            if (level.getBlockState(raid.home).block is QuestBoardBlock) Standing.add(level, raid.home, player, STANDING_GAIN)
        }
        if (band != null) GuildNews.add(level, "news.guildmark.outlaw.raid_beaten", "#${Outlaws.NAME_KEY}.${band.name}", village.string)
        end(raid)
    }

    /** They got clear: the hostage, if they still have them, goes to their camp, and the raiders back to the band. */
    private fun escaped(level: ServerLevel, raid: Foray, band: OutlawBand?, alive: List<Mob>, taken: Mob?) {
        val held = taken != null && taken.isAlive && alive.any { it.distanceToSqr(taken) < 16.0 * 16.0 }
        alive.forEach { it.discard() }
        if (band != null && taken != null && held) {
            val name = nameOf(taken)
            // Townsfolk keep their own looks; a villager is seen as one of the townsfolk of their trade
            val role = (taken as? Townsperson)?.role ?: Captives.roleFor((taken as? Villager)?.villagerData?.profession()?.unwrapKey()?.orElse(null)?.identifier())
            val look = (taken as? Townsperson)?.look ?: level.random.nextInt(role.looks)
            taken.removeLeash()
            taken.discard()
            Captives.take(level, band, name, raid.home, role, look)
            for (player in watchers(level, raid)) player.sendSystemMessage(Component.translatable("message.guildmark.raid_taken", band.title(), name))
            GuildNews.add(level, "news.guildmark.outlaw.raid_taken", "#${Outlaws.NAME_KEY}.${band.name}", name, villageName(level, raid.home).string)
        }
        end(raid)
    }

    private fun end(raid: Foray) {
        raid.bar.removeAllPlayers()
        raids.remove(raid.band)
    }

    /** A raider died: if they had the hostage's rope, the hostage is let go (and nobody gets a free lead). */
    fun onDeath(level: ServerLevel, victim: Entity) {
        if (!victim.entityTags().contains(TAG)) return
        for (raid in raids.values) {
            if (victim.uuid !in raid.raiders) continue
            val taken = raid.taken?.let { level.getEntity(it) as? Mob } ?: continue
            if (taken.leashHolder === victim) taken.removeLeash()
        }
    }

    /** A raider loaded back in after their raid ended (or the server stopped) is sent off. */
    fun onJoin(entity: Entity) {
        if (entity.level().isClientSide || !entity.entityTags().contains(TAG)) return
        if (raids.values.none { entity.uuid in it.raiders }) entity.discard()
    }

    /** Raiders scare villagers off; they never set on them. */
    fun spares(raider: Entity, target: Entity?): Boolean = raider.entityTags().contains(TAG) && target is AbstractVillager

    /** Players near enough the village to see the raid. */
    private fun watchers(level: ServerLevel, raid: Foray): List<ServerPlayer> =
        level.players().filter { !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(raid.bell)) < WATCHED * WATCHED }

    /** Open ground [APPROACH] blocks out from [bell] towards [camp] (or any way at all, failing that). */
    private fun approach(level: ServerLevel, bell: BlockPos, camp: BlockPos): BlockPos? {
        val dx = (camp.x - bell.x).toDouble()
        val dz = (camp.z - bell.z).toDouble()
        val length = sqrt(dx * dx + dz * dz).coerceAtLeast(1.0)
        val base = Math.atan2(dz / length, dx / length)
        for (turn in listOf(0.0, 0.4, -0.4, 0.8, -0.8, Math.PI)) {
            val x = bell.x + (Math.cos(base + turn) * APPROACH).toInt()
            val z = bell.z + (Math.sin(base + turn) * APPROACH).toInt()
            if (!level.isLoaded(BlockPos(x, 0, z))) continue
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            if (level.getFluidState(top.below()).isEmpty) return top
        }
        return null
    }

    /** Village bells within [radius] of [pos], in land that's loaded. */
    private fun bells(level: ServerLevel, pos: BlockPos, radius: Int): List<BlockPos> {
        val found = ArrayList<BlockPos>()
        for (cx in ((pos.x - radius) shr 4)..((pos.x + radius) shr 4)) for (cz in ((pos.z - radius) shr 4)..((pos.z + radius) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) continue
            level.poiManager.getInChunk({ it.`is`(PoiTypes.MEETING) }, ChunkPos(cx, cz), PoiManager.Occupancy.ANY).forEach { found.add(it.pos) }
        }
        return found.filter { it.closerThan(pos, radius.toDouble()) }
    }

    /** The bell of the village nearest [pos] within [radius], in land that's loaded. */
    fun nearestBell(level: ServerLevel, pos: BlockPos, radius: Int): BlockPos? = bells(level, pos, radius).minByOrNull { it.distSqr(pos) }

    /** Someone the raiders might carry off: a grown villager of an ordinary trade, or one of the townsfolk who isn't a guard. */
    private fun takeable(mob: Mob): Boolean = mob.isAlive && !mob.isBaby && when (mob) {
        is Villager -> mob.villagerData.profession().unwrapKey().orElse(null)?.identifier()?.namespace != Guildmark.MOD_ID
        is Townsperson -> mob.role != TownRole.GUARD && mob.role != TownRole.CHILD && !mob.isLeader && !mob.watcher && mob.captiveOf < 0
        else -> false
    }

    private fun nameOf(mob: Mob): String = mob.customName?.string
        ?: QuestGenerator.nameFor(mob.uuid.mostSignificantBits xor mob.uuid.leastSignificantBits)

    private fun villageName(level: ServerLevel, home: BlockPos): Component =
        if (level.getBlockState(home).block is QuestBoardBlock) Component.literal(VillageNames.of(home)) else Component.translatable("message.guildmark.captive_village")

    /** A villager's name, for a captive taken without a raid (one held when the camp's raised, or by command). */
    fun strangerName(level: Level): String = QuestGenerator.nameFor(level.random.nextLong())
}
