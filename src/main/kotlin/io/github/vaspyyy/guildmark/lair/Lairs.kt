package io.github.vaspyyy.guildmark.lair

import io.github.vaspyyy.guildmark.progression.Milestones
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.story.Courtier
import io.github.vaspyyy.guildmark.story.MainQuest
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
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
            ).apply(i) { id, pos, theme, rank, awake, cleared, boss, shard -> Lair(id, pos, theme, rank, awake, cleared, boss.orElse(null), shard) }
        }
    }
}

/**
 * Monster lairs in a dimension. A lair is built (a ruined ring of walls around a den floor) when a lair
 * hunt is taken; it wakes when someone comes close, spawning its minions and a boss with a boss bar.
 * Killing the boss clears it and leaves a chest of loot with a lore fragment on the altar.
 */
class Lairs(val lairs: MutableList<Lair> = mutableListOf()) : SavedData() {
    private val bars = HashMap<Int, ServerBossEvent>()
    private val fights = HashMap<Int, BossState>()

    fun lair(id: Int): Lair? = lairs.firstOrNull { it.id == id }

    /** Find open ground 120 to 220 blocks from [near] and build a lair of the right rank there. */
    fun create(level: ServerLevel, near: BlockPos, rank: Int, theme: LairTheme = LairTheme.forRank(rank)): Lair? = build(level, near, theme, rank, -1, 120, 100)

    /** A courtier's vault for the main story, a long walk (300 to 500 blocks) from [near]. */
    fun createVault(level: ServerLevel, near: BlockPos, courtier: Courtier): Lair? =
        build(level, near, courtier.theme, courtier.vaultRank, courtier.shard, 300, 200)

    private fun build(level: ServerLevel, near: BlockPos, theme: LairTheme, rank: Int, shard: Int, minDistance: Int, spread: Int): Lair? {
        repeat(10) {
            val angle = level.random.nextDouble() * Math.PI * 2
            val distance = minDistance + level.random.nextInt(spread)
            val x = near.x + (cos(angle) * distance).toInt()
            val z = near.z + (sin(angle) * distance).toInt()
            // Generate the land if nobody has been there yet
            for (dx in listOf(-RADIUS - 1, RADIUS + 1)) for (dz in listOf(-RADIUS - 1, RADIUS + 1)) level.getChunk((x + dx) shr 4, (z + dz) shr 4)
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            if (!level.getFluidState(top.below()).isEmpty || !level.getFluidState(top).isEmpty) return@repeat
            val lair = Lair((lairs.maxOfOrNull { it.id } ?: 0) + 1, top, theme, rank, shard = shard)
            buildArena(level, lair)
            lairs.add(lair)
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
            if (lair.cleared) continue
            if (level.chunkSource.getChunkNow(lair.pos.x shr 4, lair.pos.z shr 4) == null) continue
            val near = level.players().filter { it.blockPosition().closerThan(lair.pos, BAR_RANGE) && !it.isSpectator }
            if (!lair.awake && near.any { it.blockPosition().closerThan(lair.pos, WAKE_RANGE) }) wake(level, lair)
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

    private fun bossName(lair: Lair): Component =
        lair.courtier?.title ?: Component.translatable("lair.guildmark.boss_name", lair.theme.bossTitle, lair.theme.title)

    /** Someone came close: the minions pour out and the master shows itself. */
    private fun wake(level: ServerLevel, lair: Lair) {
        lair.awake = true
        val count = if (lair.theme.guards >= 0) lair.theme.guards else 3 + lair.rank
        val minions = lair.theme.minionIds()
        if (minions.isNotEmpty()) repeat(count) {
            spawn(level, lair, minions[level.random.nextInt(minions.size)], 3 + level.random.nextInt(4))
        }
        val boss = spawn(level, lair, lair.theme.bossId(), 0)
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
        level.playSound(null, lair.pos, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6f, 1.4f)
        val message = lair.courtier?.let { Component.translatable("message.guildmark.vault_awake", it.title) }
            ?: Component.translatable("message.guildmark.lair_awake", lair.theme.title)
        for (player in level.players()) if (player.blockPosition().closerThan(lair.pos, BAR_RANGE)) player.sendSystemMessage(message)
    }

    private fun spawn(level: ServerLevel, lair: Lair, type: Identifier, reach: Int): Mob? {
        val mob = BuiltInRegistries.ENTITY_TYPE.getValue(type).create(level, EntitySpawnReason.EVENT) as? Mob ?: return null
        val angle = level.random.nextDouble() * Math.PI * 2
        val spot = lair.pos.offset((cos(angle) * reach).toInt(), 0, (sin(angle) * reach).toInt())
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

        val chestPos = lair.pos
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL)
        (level.getBlockEntity(chestPos) as? ChestBlockEntity)?.let { chest ->
            chest.setItem(0, lair.courtier?.let { LoreBooks.fragment(it.lore) } ?: LoreBooks.random(level.random))
            chest.setItem(1, ItemStack(ModItems.GUILD_MARK.get(), 5 + lair.rank * 5))
            chest.setItem(2, Relics.roll(level, lair.rank + 1, if (lair.shard >= 0) Rarity.RARE else Rarity.COMMON))
            if (lair.shard >= 0) chest.setItem(3, Relics.cache(Rarity.UNCOMMON))
            chest.setLootTable(lair.theme.loot, level.random.nextLong())
        }
        level.playSound(null, chestPos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.HOSTILE, 1.0f, 1.0f)
        val message = Component.translatable("message.guildmark.lair_cleared", lair.theme.title)
        val heroes = level.players().filter { it.blockPosition().closerThan(lair.pos, BAR_RANGE) }
        for (player in heroes) {
            player.sendSystemMessage(message)
            Milestones.award(player, Milestones.LAIR)
        }
        Contracts.onLairCleared(heroes, lair.id)
        if (lair.shard >= 0) MainQuest.onVaultCleared(level, lair, heroes)
        GuildNews.add(level, "news.guildmark.lair", heroes.joinToString(", ") { it.name.string }.ifEmpty { "?" }, "#lair.guildmark.${lair.theme.id}", lair.pos.x.toString(), lair.pos.z.toString())
    }

    companion object {
        const val RADIUS = 8
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
