package io.github.vaspyyy.guildmark.lair

import io.github.vaspyyy.guildmark.homestead.Homesteads
import io.github.vaspyyy.guildmark.progression.Milestones
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.github.vaspyyy.guildmark.story.Courtier
import io.github.vaspyyy.guildmark.story.MainQuest
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One monster lair: where it is, what kind, how tough, and whether it has woken or been cleared. */
data class Lair(
    val id: Int,
    val pos: BlockPos,
    val theme: LairTheme,
    val rank: Int,
    var awake: Boolean = false,
    var cleared: Boolean = false,
    var boss: UUID? = null,
    /** A main-story shard vault: which shard it holds (see Courtier), or -1 for an ordinary lair. */
    val shard: Int = -1,
    /** Whether its Wardfire has been raised (or tried for). */
    var fire: Boolean = false,
    /** Dug as a dungeon underground; [pos] is then the way in on the surface. */
    val delve: Boolean = false,
    /** The dungeon's layout, once a player has come close enough for it to be dug. */
    var dungeon: DungeonState? = null,
) {
    val courtier: Courtier? get() = Courtier.forShard(shard)

    companion object {
        private val THEME_CODEC: Codec<LairTheme> = Codec.STRING.xmap({ id -> LairTheme.entries.first { it.id == id } }, LairTheme::id)

        val CODEC: Codec<Lair> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Lair::id),
                BlockPos.CODEC.fieldOf("pos").forGetter(Lair::pos),
                THEME_CODEC.fieldOf("theme").forGetter(Lair::theme),
                Codec.INT.fieldOf("rank").forGetter(Lair::rank),
                Codec.BOOL.optionalFieldOf("awake", false).forGetter(Lair::awake),
                Codec.BOOL.optionalFieldOf("cleared", false).forGetter(Lair::cleared),
                UUIDUtil.CODEC.optionalFieldOf("boss").forGetter { Optional.ofNullable(it.boss) },
                Codec.INT.optionalFieldOf("shard", -1).forGetter(Lair::shard),
                Codec.BOOL.optionalFieldOf("fire", false).forGetter(Lair::fire),
                Codec.BOOL.optionalFieldOf("delve", false).forGetter(Lair::delve),
                DungeonState.CODEC.optionalFieldOf("dungeon").forGetter { Optional.ofNullable(it.dungeon) },
            ).apply(i) { id, pos, theme, rank, awake, cleared, boss, shard, fire, delve, dungeon ->
                Lair(id, pos, theme, rank, awake, cleared, boss.orElse(null), shard, fire, delve, dungeon.orElse(null))
            }
        }
    }
}

/**
 * Monster lairs in a dimension. Most are dug as dungeons: a stairwell down from the surface into halls
 * built a room at a time as players come near, guards rising in each, and the boss waiting in a hall at
 * the end behind a mist gate. The rest (and lairs from before dungeons) are a ruined ring on the surface
 * that wakes when someone comes close. Killing the boss clears the lair and leaves a chest of loot.
 */
class Lairs(val lairs: MutableList<Lair> = mutableListOf()) : SavedData() {
    private val bars = HashMap<Int, ServerBossEvent>()
    private val fights = HashMap<Int, BossState>()
    private val layouts = HashMap<Int, DungeonLayout>()

    fun lair(id: Int): Lair? = lairs.firstOrNull { it.id == id }

    /** Find open ground 120 to 220 blocks from [near] and build a lair of the right rank there. */
    fun create(level: ServerLevel, near: BlockPos, rank: Int, theme: LairTheme = LairTheme.forRank(rank)): Lair? = build(level, near, theme, rank, -1, 120, 100)

    /** A courtier's vault for the main story, a long walk (300 to 500 blocks) from [near]. */
    fun createVault(level: ServerLevel, near: BlockPos, courtier: Courtier): Lair? =
        build(level, near, courtier.theme, courtier.vaultRank, courtier.shard, 300, 200)

    /** A lair of [theme] a short walk (28 to 40 blocks) from [near], for testing. */
    fun createNear(level: ServerLevel, near: BlockPos, theme: LairTheme): Lair? = build(level, near, theme, theme.minRank, -1, 28, 12)

    /**
     * Where to stand to face a lair's way in, and the way to look, for testing: on firm ground before a
     * dungeon's marker, or at the edge of a surface ring.
     */
    fun doorstep(level: ServerLevel, lair: Lair): Pair<Vec3, Float> {
        val dungeon = lair.dungeon ?: return Vec3(lair.pos.x + 0.5, lair.pos.y + 1.0, lair.pos.z + 4.5) to 180f
        val forward = Direction.from2DDataValue(dungeon.heading)
        for (back in 6..10) for (y in lair.pos.y - 3..lair.pos.y + 4) {
            val pos = lair.pos.relative(forward.opposite, back).atY(y)
            if (level.getBlockState(pos).isAir && level.getBlockState(pos.above()).isAir &&
                level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
            ) return Vec3(pos.x + 0.5, y.toDouble(), pos.z + 0.5) to forward.toYRot()
        }
        val pos = lair.pos.relative(forward.opposite, 6)
        return Vec3(pos.x + 0.5, lair.pos.y + 1.0, pos.z + 0.5) to forward.toYRot()
    }

    private fun build(level: ServerLevel, near: BlockPos, theme: LairTheme, rank: Int, shard: Int, minDistance: Int, spread: Int): Lair? {
        repeat(10) {
            val angle = level.random.nextDouble() * Math.PI * 2
            val distance = minDistance + level.random.nextInt(spread)
            val x = near.x + (cos(angle) * distance).toInt()
            val z = near.z + (sin(angle) * distance).toInt()
            // Never dug into anyone's homestead, nor right up against it
            if (Homesteads.near(level, x, z, RADIUS + HOME_GAP) != null) return@repeat
            // Generate the land if nobody has been there yet
            for (dx in listOf(-RADIUS - 1, RADIUS + 1)) for (dz in listOf(-RADIUS - 1, RADIUS + 1)) level.getChunk((x + dx) shr 4, (z + dz) shr 4)
            var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            // Down off any tree trunk to the ground itself
            while (top.y > level.minY && level.getBlockState(top.below()).let { it.`is`(BlockTags.LOGS) || it.`is`(BlockTags.LEAVES) || it.canBeReplaced() }) top = top.below()
            if (!level.getFluidState(top.below()).isEmpty || !level.getFluidState(top).isEmpty) return@repeat
            // Dug underground where there's rock enough below; otherwise the old ring on the surface
            val delve = theme.style != null && dungeonsEnabled() && top.y - level.minY >= MIN_ROCK
            val lair = Lair((lairs.maxOfOrNull { it.id } ?: 0) + 1, top, theme, rank, shard = shard, delve = delve)
            lairs.add(lair)
            // A dungeon's way in is built now, while the land is loaded; its rooms wait for someone to come
            if (delve) dig(level, lair) else buildArena(level, lair)
            setDirty()
            return lair
        }
        return null
    }

    /** A sunken arena: themed floor, a broken ring wall, four pillars and an altar in the middle. */
    private fun buildArena(level: ServerLevel, lair: Lair) {
        val theme = lair.theme
        val center = lair.pos
        val random = level.random
        fun set(pos: BlockPos, block: Block) = level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL)
        for (dx in -RADIUS - 1..RADIUS + 1) for (dz in -RADIUS - 1..RADIUS + 1) {
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (r > RADIUS + 1.5) continue
            val column = center.offset(dx, 0, dz)
            for (dy in 0..8) set(column.above(dy), Blocks.AIR)
            // Fill down so the floor never floats
            var y = -1
            while (y > -8) {
                val pos = column.above(y)
                val state = level.getBlockState(pos)
                if (y < -1 && !state.canBeReplaced() && level.getFluidState(pos).isEmpty) break
                set(pos, if (y == -1) theme.floor() else theme.wall())
                y--
            }
            if (r >= RADIUS - 0.5) {
                // A ruined ring wall, broken in places
                val height = random.nextInt(4)
                for (dy in 0 until height) set(column.above(dy), theme.wall())
            }
        }
        for (i in 0 until 4) {
            val angle = Math.PI / 2 * i + Math.PI / 4
            val pillar = center.offset((cos(angle) * 5).toInt(), 0, (sin(angle) * 5).toInt())
            for (dy in 0..3) set(pillar.above(dy), theme.wall())
        }
        repeat(10) {
            val dx = random.nextInt(RADIUS * 2 - 1) - RADIUS + 1
            val dz = random.nextInt(RADIUS * 2 - 1) - RADIUS + 1
            if (dx * dx + dz * dz in 5..(RADIUS - 2) * (RADIUS - 2)) {
                val accent = theme.accent()
                // Lanterns and webs sit on the floor; blocks like magma replace it
                if (accent == Blocks.COBWEB || accent == Blocks.SOUL_LANTERN || accent == Blocks.CAMPFIRE) set(center.offset(dx, 0, dz), accent)
                else set(center.offset(dx, -1, dz), accent)
            }
        }
        set(center.below(), theme.wall())
        if (lair.shard >= 0) {
            // A vault: crying obsidian caps the pillars and soul fire burns around the altar
            for (i in 0 until 4) {
                val angle = Math.PI / 2 * i + Math.PI / 4
                set(center.offset((cos(angle) * 5).toInt(), 4, (sin(angle) * 5).toInt()), Blocks.CRYING_OBSIDIAN)
                set(center.offset((cos(angle + Math.PI / 4) * 3).toInt(), -1, (sin(angle + Math.PI / 4) * 3).toInt()), Blocks.SOUL_SOIL)
                set(center.offset((cos(angle + Math.PI / 4) * 3).toInt(), 0, (sin(angle + Math.PI / 4) * 3).toInt()), Blocks.SOUL_FIRE)
            }
        }
    }

    /** Every 20 ticks: wake lairs players walk up to, and keep each awake boss's bar up to date. */
    fun tick(level: ServerLevel) {
        for (lair in lairs) {
            if (lair.delve) {
                tickDungeon(level, lair)
                continue
            }
            if (lair.cleared) continue
            if (level.chunkSource.getChunkNow(lair.pos.x shr 4, lair.pos.z shr 4) == null) {
                // Everyone's gone far off (or fell and woke at home): nobody keeps the bar
                bars[lair.id]?.removeAllPlayers()
                continue
            }
            val near = level.players().filter { it.blockPosition().closerThan(lair.pos, BAR_RANGE) && !it.isSpectator }
            if (!lair.fire && level.players().any { it.blockPosition().closerThan(lair.pos, FIRE_RANGE) }) raiseFire(level, lair)
            if (!lair.awake && near.any { it.blockPosition().closerThan(lair.pos, WAKE_RANGE) }) wake(level, lair, lair.pos, near)
            if (!lair.awake) continue
            // A boss of our own keeps its own bar and fights its own fight
            if (lair.theme.ownBoss) continue

            val bar = bars.getOrPut(lair.id) {
                ServerBossEvent(UUID.randomUUID(), bossName(lair), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10)
            }
            val boss = lair.boss?.let { level.getEntity(it) } as? Mob
            if (boss != null) {
                bar.progress = (boss.health / boss.maxHealth).coerceIn(0.0f, 1.0f)
                val fighters = near.filter { !it.isCreative && it.blockPosition().closerThan(lair.pos, WAKE_RANGE * 1.5) }
                BossAbilities.tick(level, boss, lair.theme, lair.rank, fighters, fights.getOrPut(lair.id) { BossState() })
            }
            for (player in bar.players.toList()) if (player !in near) bar.removePlayer(player)
            for (player in near) if (player is ServerPlayer && player !in bar.players) bar.addPlayer(player)
        }
    }

    /**
     * A Wardfire on the way in, beyond where the lair wakes. It's raised as the first player comes near
     * (lairs from before Wardfires get one too), so the land round the lair is already there.
     */
    private fun raiseFire(level: ServerLevel, lair: Lair) {
        lair.fire = true
        setDirty()
        if (WardfireSites.enabled() && !WardfireSites.near(level, lair.pos, 40)) WardfireSites.placeNear(level, lair.pos, 24, 32)
    }

    // ---- Dungeons ----

    private fun dungeonsEnabled(): Boolean = try {
        GuildmarkConfig.DUNGEONS.get()
    } catch (e: IllegalStateException) {
        true
    }

    private fun layout(lair: Lair, state: DungeonState): DungeonLayout =
        layouts.getOrPut(lair.id) { DungeonLayout(state, state.plan ?: DungeonPlanner.plan(state.seed, lair.rank, lair.shard >= 0)) }

    /**
     * A dungeon lair, once a second: build the rooms players are coming up on, raise each room's guards as
     * someone steps in, and run the fight in the boss hall. (A dungeon not yet dug is dug when someone
     * comes near.)
     */
    private fun tickDungeon(level: ServerLevel, lair: Lair) {
        val state = lair.dungeon
        if (state == null) {
            if (lair.cleared) return
            if (level.players().none { !it.isSpectator && it.blockPosition().closerThan(lair.pos, DIG_RANGE) }) return
            if (!loaded(level, lair.pos, 12)) return
            dig(level, lair)
            return
        }
        val layout = layout(lair, state)
        val reach = layout.bounds.grow(BUILD_RANGE)
        val players = level.players().filter { !it.isSpectator && reach.contains(it) }
        if (players.isEmpty()) {
            // Everyone's gone far off (or fell and woke at home): nobody keeps the bar
            bars[lair.id]?.removeAllPlayers()
            return
        }
        buildNext(level, lair, layout, players)
        if (lair.cleared) return
        for (room in 1 until layout.boss) {
            if (!state.isBuilt(room) || state.isGuarded(room)) continue
            val box = layout.box(room).grow(2)
            val intruders = players.filter { box.contains(it) }
            if (intruders.isNotEmpty()) guards(level, lair, layout, room, intruders)
        }
        arena(level, lair, layout, players)
    }

    /** All the land within [reach] of [pos] is loaded. */
    private fun loaded(level: ServerLevel, pos: BlockPos, reach: Int): Boolean {
        for (cx in ((pos.x - reach) shr 4)..((pos.x + reach) shr 4)) for (cz in ((pos.z - reach) shr 4)..((pos.z + reach) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) return false
        }
        return true
    }

    /** Lay the dungeon out under the lair and build its way in: the stairwell, the entrance hall and a Wardfire. */
    private fun dig(level: ServerLevel, lair: Lair) {
        val major = lair.shard >= 0
        val seed = level.random.nextLong()
        val heading = level.random.nextInt(4)
        val plan = DungeonPlanner.plan(seed, lair.rank, major)
        val floors = DungeonLayout.survey(level, plan, lair.pos, heading, major)
        val state = DungeonState(seed, heading, BlockPos(lair.pos.x, floors[0], lair.pos.z), floors, built = 1L, plan = plan)
        lair.dungeon = state
        // Its Wardfire waits at the foot of the stairs
        lair.fire = true
        setDirty()
        DungeonBuilder(level, layout(lair, state), lair).buildEntrance()
    }

    /** Build the nearest room someone is coming up on whose way in is already there. One a second at most. */
    private fun buildNext(level: ServerLevel, lair: Lair, layout: DungeonLayout, players: List<ServerPlayer>) {
        val state = layout.state
        var best = -1
        var nearest = BUILD_RANGE.toDouble() * BUILD_RANGE
        for (room in 1 until layout.plan.rooms.size) {
            if (state.isBuilt(room) || !state.isBuilt(layout.parent(room))) continue
            val c = layout.center(room)
            val distance = players.minOf { it.distanceToSqr(c.x + 0.5, c.y + 1.0, c.z + 0.5) }
            if (distance >= nearest || !layout.loaded(level, room)) continue
            best = room
            nearest = distance
        }
        if (best < 0) return
        DungeonBuilder(level, layout, lair).buildRoom(best)
        state.built = state.built or (1L shl best)
        setDirty()
    }

    /** Someone stepped into a room: what lurks there rises, away from them, and comes for them. */
    private fun guards(level: ServerLevel, lair: Lair, layout: DungeonLayout, room: Int, intruders: List<ServerPlayer>) {
        val state = layout.state
        state.guarded = state.guarded or (1L shl room)
        setDirty()
        val minions = lair.theme.minionIds()
        if (minions.isEmpty()) return
        val kind = layout.plan.rooms[room].kind
        val count = 1 + lair.rank / 2 + level.random.nextInt(2) + (if (kind == RoomKind.TREASURE) 1 else 0) + (if (lair.shard >= 0) 1 else 0)
        val box = layout.box(room)
        repeat(count) {
            val type = if (lair.theme.style == DungeonStyle.CRYPT && level.random.nextInt(4) == 0) HOLLOW_SOLDIER else minions[level.random.nextInt(minions.size)]
            val spot = guardSpot(level, box, intruders) ?: return@repeat
            val mob = BuiltInRegistries.ENTITY_TYPE.getValue(type).create(level, EntitySpawnReason.EVENT) as? Mob ?: return@repeat
            mob.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, level.random.nextFloat() * 360f, 0f)
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
            mob.setPersistenceRequired()
            mob.target = intruders.minBy { it.distanceToSqr(mob) }
            level.addFreshEntity(mob)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, spot.x + 0.5, spot.y + 0.6, spot.z + 0.5, 12, 0.3, 0.5, 0.3, 0.01)
        }
        val c = layout.center(room)
        level.playSound(null, c, ModSounds.DUNGEON_STIR.get(), SoundSource.HOSTILE, 1.4f, 0.9f + level.random.nextFloat() * 0.2f)
    }

    /** Somewhere on a room's floor a guard can stand, a little way from anyone there; anywhere will do at a pinch. */
    private fun guardSpot(level: ServerLevel, box: Box, intruders: List<ServerPlayer>): BlockPos? {
        var fallback: BlockPos? = null
        repeat(24) {
            val pos = BlockPos(box.minX + level.random.nextInt(box.maxX - box.minX + 1), box.minY, box.minZ + level.random.nextInt(box.maxZ - box.minZ + 1))
            if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.above()).isAir) return@repeat
            if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return@repeat
            if (intruders.all { it.distanceToSqr(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5) > 25.0 }) return pos
            if (fallback == null) fallback = pos
        }
        return fallback
    }

    /**
     * The boss hall: its master wakes when someone steps in, and fights whoever is in the hall. If nobody
     * has been in it for a while (they fell, or never came back) it heals and goes back to the middle.
     */
    private fun arena(level: ServerLevel, lair: Lair, layout: DungeonLayout, players: List<ServerPlayer>) {
        val hall = layout.boss
        if (!layout.state.isBuilt(hall)) return
        // Down past the floor too, for anyone sunk in a snowdrift or swimming in a channel
        val box = layout.box(hall).let { it.copy(minY = it.minY - 2) }
        val inside = players.filter { box.contains(it) }
        if (!lair.awake) {
            if (inside.isEmpty()) return
            wake(level, lair, layout.center(hall), players.filter { box.grow(8).contains(it) })
        }
        val watchers = players.filter { box.grow(4).contains(it) }
        val bar = bars.getOrPut(lair.id) {
            ServerBossEvent(UUID.randomUUID(), bossName(lair), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10)
        }
        val boss = lair.boss?.let { level.getEntity(it) } as? Mob
        if (boss != null && boss.isAlive) {
            bar.progress = (boss.health / boss.maxHealth).coerceIn(0.0f, 1.0f)
            val fighters = inside.filter { !it.isCreative }
            val fight = fights.getOrPut(lair.id) { BossState() }
            if (fighters.isEmpty()) {
                if (++fight.idle == RESET_SECONDS) reset(lair, layout, boss)
            } else {
                fight.idle = 0
                BossAbilities.tick(level, boss, lair.theme, lair.rank, fighters, fight, layout.box(hall))
            }
        }
        for (player in bar.players.toList()) if (player !in watchers) bar.removePlayer(player)
        for (player in watchers) if (player !in bar.players) bar.addPlayer(player)
    }

    /** The fight's over without a winner: the boss heals, calms down and goes back to the middle of its hall. */
    private fun reset(lair: Lair, layout: DungeonLayout, boss: Mob) {
        val c = layout.center(layout.boss)
        boss.removeAllEffects()
        boss.health = boss.maxHealth
        boss.target = null
        boss.navigation.stop()
        boss.teleportTo(c.x + 0.5, c.y.toDouble(), c.z + 0.5)
        fights[lair.id] = BossState(idle = RESET_SECONDS + 1)
    }

    /**
     * A player used a mist gate: step them through it. In is always open; out is barred while the hall's
     * master is awake and alive (creative players go where they like).
     */
    fun passGate(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer) {
        val facing = state.getValue(HorizontalDirectionalBlock.FACING)
        var bottom = pos
        while (level.getBlockState(bottom.below()).`is`(state.block)) bottom = bottom.below()
        val ahead = (player.x - (pos.x + 0.5)) * facing.stepX + (player.z - (pos.z + 0.5)) * facing.stepZ
        val inward = ahead < 0
        if (!inward && !player.isCreative) {
            val lair = lairs.firstOrNull { it.dungeon?.let { d -> pos in layout(it, d).bossDoor } == true }
            val boss = lair?.boss?.let { level.getEntity(it) } as? LivingEntity
            if (lair != null && !lair.cleared && lair.awake && boss != null && boss.isAlive) {
                player.sendOverlayMessage(Component.translatable("message.guildmark.mist_sealed"))
                level.playSound(null, pos, ModSounds.MIST_DENY.get(), SoundSource.BLOCKS, 1.0f, 1.0f)
                return
            }
        }
        val way = if (inward) facing else facing.opposite
        val to = bottom.relative(way)
        level.sendParticles(ParticleTypes.WHITE_SMOKE, player.x, player.y + 1.0, player.z, 16, 0.3, 0.6, 0.3, 0.02)
        player.teleportTo(level, to.x + 0.5, to.y.toDouble(), to.z + 0.5, setOf(), way.toYRot(), player.xRot, true)
        level.playSound(null, pos, ModSounds.MIST_PASS.get(), SoundSource.BLOCKS, 1.0f, 1.0f)
    }

    /** A courtier's own title, a story lair's master by name ("Harl Voss, the Turncoat"), or the master of its kind of den. */
    private fun bossName(lair: Lair): Component =
        lair.courtier?.title ?: if (lair.theme.storyOnly) lair.theme.bossTitle
        else Component.translatable("lair.guildmark.boss_name", lair.theme.bossTitle, lair.theme.title)

    /** Someone came close: the minions pour out round [center] and the master shows itself. */
    private fun wake(level: ServerLevel, lair: Lair, center: BlockPos, audience: List<ServerPlayer>) {
        lair.awake = true
        val count = if (lair.theme.guards >= 0) lair.theme.guards else 3 + lair.rank
        val minions = lair.theme.minionIds()
        if (minions.isNotEmpty()) repeat(count) {
            spawn(level, center, minions[level.random.nextInt(minions.size)], 3 + level.random.nextInt(4))
        }
        val boss = spawn(level, center, lair.theme.bossId(), 0)
        if (boss != null) {
            if (lair.theme.ownBoss) {
                // He kneels facing whoever came
                level.getNearestPlayer(boss, 48.0)?.let { player ->
                    val yaw = Math.toDegrees(atan2(-(player.x - boss.x), player.z - boss.z)).toFloat()
                    boss.yRot = yaw
                    boss.yBodyRot = yaw
                    boss.yHeadRot = yaw
                }
                boss.setData(ModAttachments.LAIR_BOSS, lair.id)
            } else makeBoss(boss, lair)
            lair.boss = boss.uuid
        }
        setDirty()
        level.playSound(null, center, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6f, 1.4f)
        val message = lair.courtier?.let { Component.translatable("message.guildmark.vault_awake", it.title) }
            ?: Component.translatable("message.guildmark.lair_awake", lair.theme.title)
        for (player in audience) player.sendSystemMessage(message)
    }

    private fun spawn(level: ServerLevel, center: BlockPos, type: Identifier, reach: Int): Mob? {
        val mob = BuiltInRegistries.ENTITY_TYPE.getValue(type).create(level, EntitySpawnReason.EVENT) as? Mob ?: return null
        val angle = level.random.nextDouble() * Math.PI * 2
        val spot = center.offset((cos(angle) * reach).toInt(), 0, (sin(angle) * reach).toInt())
        mob.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
        mob.setPersistenceRequired()
        level.addFreshEntity(mob)
        return mob
    }

    /** Tougher with every rank: more health, harder hits, armour, and a helmet against the sun. */
    private fun makeBoss(boss: Mob, lair: Lair) {
        boss.customName = bossName(lair)
        boss.isCustomNameVisible = true
        boss.getAttribute(Attributes.MAX_HEALTH)?.addPermanentModifier(
            AttributeModifier(BOSS_HEALTH, 3.0 + lair.rank * 1.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
        )
        boss.getAttribute(Attributes.ATTACK_DAMAGE)?.addPermanentModifier(
            AttributeModifier(BOSS_DAMAGE, 0.3 * (1 + lair.rank), AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
        )
        boss.getAttribute(Attributes.ARMOR)?.addPermanentModifier(AttributeModifier(BOSS_ARMOR, 2.0 + lair.rank * 2, AttributeModifier.Operation.ADD_VALUE))
        // Bosses loom over their minions; courtiers loom more
        boss.getAttribute(Attributes.SCALE)?.addPermanentModifier(
            AttributeModifier(BOSS_SCALE, if (lair.shard >= 0) 0.6 else 0.3, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
        )
        boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.addPermanentModifier(AttributeModifier(BOSS_KNOCKBACK, 0.6, AttributeModifier.Operation.ADD_VALUE))
        if (lair.shard >= 0) {
            boss.getAttribute(Attributes.MAX_HEALTH)?.addPermanentModifier(AttributeModifier(VAULT_HEALTH, 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
            if (lair.theme == LairTheme.SUNKEN_SHRINE) boss.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.TRIDENT))
        }
        if (lair.theme == LairTheme.OLD_MINE) boss.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_PICKAXE))
        boss.health = boss.maxHealth
        boss.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.GOLDEN_HELMET))
        boss.setData(ModAttachments.LAIR_BOSS, lair.id)
    }

    /** The boss fell: the lair is cleared and its hoard sits on the altar. */
    fun onBossDeath(level: ServerLevel, lairId: Int) {
        val lair = lair(lairId) ?: return
        if (lair.cleared) return
        lair.cleared = true
        setDirty()
        bars.remove(lair.id)?.removeAllPlayers()
        fights.remove(lair.id)

        // In a dungeon the hoard waits in the boss hall, the mist lifts, and a Wardfire stands by
        val layout = lair.dungeon?.let { layout(lair, it) }
        val chestPos = layout?.center(layout.boss) ?: lair.pos
        if (layout != null) {
            for (pos in layout.bossDoor) if (level.getBlockState(pos).`is`(ModBlocks.MIST_GATE.get())) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            level.playSound(null, layout.bossDoor.firstOrNull() ?: chestPos, ModSounds.MIST_PASS.get(), SoundSource.BLOCKS, 1.4f, 0.8f)
            val fire = listOf(layout.forward, layout.right, layout.forward.opposite, layout.right.opposite)
                .map { chestPos.relative(it, 3) }
                .firstOrNull { level.getBlockState(it).isAir && level.getBlockState(it.above()).isAir && level.getBlockState(it.below()).isFaceSturdy(level, it.below(), Direction.UP) }
            if (fire != null) level.setBlock(fire, ModBlocks.WARDFIRE.get().defaultBlockState(), Block.UPDATE_ALL)
        }
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL)
        (level.getBlockEntity(chestPos) as? ChestBlockEntity)?.let { chest ->
            chest.setItem(0, lair.courtier?.let { LoreBooks.fragment(it.lore) } ?: LoreBooks.random(level.random))
            chest.setItem(1, ItemStack(ModItems.GUILD_MARK.get(), 5 + lair.rank * 5))
            chest.setItem(2, Relics.roll(level, lair.rank + 1, if (lair.shard >= 0) Rarity.RARE else Rarity.COMMON))
            if (lair.shard >= 0) chest.setItem(3, Relics.cache(Rarity.UNCOMMON))
            chest.setLootTable(lair.theme.loot, level.random.nextLong())
        }
        level.playSound(null, chestPos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.HOSTILE, 1.0f, 1.0f)
        val message = Component.translatable(if (layout != null) "message.guildmark.dungeon_cleared" else "message.guildmark.lair_cleared", lair.theme.title)
        val hall = layout?.box(layout.boss)?.grow(12)
        val heroes = level.players().filter { if (hall != null) hall.contains(it) else it.blockPosition().closerThan(lair.pos, BAR_RANGE) }
        for (player in heroes) {
            player.sendSystemMessage(message)
            Milestones.award(player, Milestones.LAIR)
        }
        Contracts.onLairCleared(heroes, lair.id)
        if (lair.shard >= 0) MainQuest.onVaultCleared(level, lair, heroes, chestPos.above())
        GuildNews.add(level, "news.guildmark.lair", heroes.joinToString(", ") { it.name.string }.ifEmpty { "?" }, "#lair.guildmark.${lair.theme.id}", lair.pos.x.toString(), lair.pos.z.toString())
    }

    companion object {
        const val RADIUS = 8

        /** How far clear of a homestead's land a lair keeps, past its own [RADIUS]. */
        private const val HOME_GAP = 24
        /** How close the first player comes before the lair's Wardfire is raised. */
        private const val FIRE_RANGE = 80.0
        /** How close the first player comes before a dungeon is dug and its entrance built. */
        private const val DIG_RANGE = 64.0
        /** How close a player comes to a room before it's built. */
        private const val BUILD_RANGE = 56
        /** Seconds a boss waits with nobody in its hall before it heals and goes back to the middle. */
        private const val RESET_SECONDS = 10
        /** Blocks of world below a lair's ground it takes to dig a dungeon under it. */
        private const val MIN_ROCK = 48
        private val HOLLOW_SOLDIER = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hollow_soldier")
        private const val WAKE_RANGE = 20.0
        private const val BAR_RANGE = 48.0
        private val BOSS_HEALTH = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lair_boss_health")
        private val BOSS_DAMAGE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lair_boss_damage")
        private val BOSS_ARMOR = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lair_boss_armor")
        private val BOSS_SCALE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lair_boss_scale")
        private val BOSS_KNOCKBACK = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lair_boss_knockback")
        private val VAULT_HEALTH = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "vault_boss_health")

        private val CODEC: Codec<Lairs> = RecordCodecBuilder.create { i ->
            i.group(Lair.CODEC.listOf().fieldOf("lairs").forGetter { it.lairs })
                .apply(i) { lairs -> Lairs(lairs.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "lairs"), Supplier { Lairs() }, CODEC)

        fun get(level: ServerLevel): Lairs = level.dataStorage.computeIfAbsent(TYPE)
    }
}
