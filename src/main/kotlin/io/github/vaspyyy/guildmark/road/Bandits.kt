package io.github.vaspyyy.guildmark.road

import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bandits who waylay travellers and caravans on the roads. A pack of illagers turns up a short way off
 * and goes for the traveller (illagers hunt villagers and traders on their own); the traveller holds
 * still until every bandit is dead or gone. Defending a caravan pays the guards who were there.
 */
object Bandits {
    /** Players this close hear about an ambush and share the reward for beating it. */
    private const val WITNESS_RANGE = 48.0

    /** Spring an ambush on [victim]: a pack sized by how far from spawn the road runs. */
    fun ambush(level: ServerLevel, victim: PathfinderMob) {
        val tier = QuestGenerator.tierAt(victim.blockX, victim.blockZ)
        val count = 1 + tier + level.random.nextInt(2)
        val bandits = (0 until count).mapNotNull { spawnBandit(level, victim, tier) }
        if (bandits.isEmpty()) return
        victim.setData(ModAttachments.AMBUSHERS, bandits.map { it.uuid })
        level.playSound(null, victim.blockPosition(), SoundEvents.PILLAGER_CELEBRATE, SoundSource.HOSTILE, 1.5f, 0.9f)
        val message = Component.translatable("message.guildmark.ambush", victim.name)
        for (player in witnesses(level, victim)) player.sendSystemMessage(message)
    }

    /**
     * Called every check while [victim] travels. Returns true while an ambush is still on; when the last
     * bandit falls it pays any caravan guards and the traveller moves on.
     */
    fun tick(level: ServerLevel, victim: PathfinderMob): Boolean {
        val ambushers = victim.getData(ModAttachments.AMBUSHERS)
        if (ambushers.isEmpty()) return false
        val alive = ambushers.filter { id -> level.getEntity(id)?.isAlive == true }
        if (alive.size == ambushers.size) return true
        victim.setData(ModAttachments.AMBUSHERS, alive)
        if (alive.isNotEmpty()) return true

        val guards = witnesses(level, victim)
        val caravan = victim.hasData(ModAttachments.TRAFFIC)
        val marks = 2 + ambushers.size * 2
        for (player in guards) {
            if (caravan) {
                val paid = Progression.reward(player, marks)
                player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), paid), Prediction.SERVER_ONLY)
                Progression.addXp(player, marks * Progression.XP_PER_MARK)
                player.sendSystemMessage(Component.translatable("message.guildmark.ambush_defended_paid", victim.name, paid))
            } else {
                player.sendSystemMessage(Component.translatable("message.guildmark.ambush_defended", victim.name))
            }
        }
        level.playSound(null, victim.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f)
        if (guards.isNotEmpty()) GuildNews.add(level, "news.guildmark.ambush", guards.joinToString(", ") { it.name.string }, victim.name.string)
        return false
    }

    /** A traveller or merchant died mid-ambush: tell whoever was there. */
    fun onDeath(level: ServerLevel, victim: PathfinderMob) {
        if (victim.getData(ModAttachments.AMBUSHERS).isEmpty()) return
        val message = Component.translatable("message.guildmark.ambush_lost", victim.name)
        for (player in witnesses(level, victim)) player.sendSystemMessage(message)
    }

    /** Is a player close enough to see an ambush? Bandits wait for an audience. */
    fun anyoneWatching(level: ServerLevel, victim: PathfinderMob): Boolean = witnesses(level, victim).isNotEmpty()

    private fun witnesses(level: ServerLevel, victim: PathfinderMob): List<Player> =
        level.players().filter { it.distanceTo(victim) <= WITNESS_RANGE && !it.isSpectator }

    private fun spawnBandit(level: ServerLevel, victim: PathfinderMob, tier: Int): Mob? {
        // Further out, more of the pack are axe-wielding vindicators instead of crossbow pillagers
        val type: EntityType<out Mob> = if (level.random.nextInt(4) < tier) EntityTypes.VINDICATOR else EntityTypes.PILLAGER
        val angle = level.random.nextDouble() * Math.PI * 2
        val reach = 12.0 + level.random.nextInt(6)
        val x = victim.blockX + (cos(angle) * reach).toInt()
        val z = victim.blockZ + (sin(angle) * reach).toInt()
        if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) return null
        val spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
        if (!level.getFluidState(spot.below()).isEmpty) return null

        val bandit = type.create(level, EntitySpawnReason.EVENT) ?: return null
        bandit.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        bandit.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
        bandit.customName = Component.translatable("entity.guildmark.bandit")
        bandit.target = victim
        level.addFreshEntity(bandit)
        return bandit
    }
}
