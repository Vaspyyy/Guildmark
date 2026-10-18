package io.github.vaspyyy.guildmark.road

import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.siege.Defenders
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
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bandits who waylay travellers and caravans on the roads. A pack of illagers turns up a short way off
 * and goes for the traveller (illagers hunt villagers and traders on their own); the traveller holds
 * still until every bandit is dead or gone. Defending a caravan pays the guards who fought the bandits off.
 */
object Bandits {
    /** Players this close hear about an ambush. */
    private const val WITNESS_RANGE = 48.0

    /** Spring an ambush on [victim]: a pack sized by how far from spawn the road runs. */
    fun ambush(level: ServerLevel, victim: PathfinderMob) {
        val tier = QuestGenerator.tierAt(victim.blockX, victim.blockZ)
        val count = 1 + tier + level.random.nextInt(2)
        val bandits = (0 until count).mapNotNull { spawnBandit(level, victim, tier) }
        if (bandits.isEmpty()) return
        victim.setData(ModAttachments.AMBUSHERS, bandits.map { it.uuid })
        victim.removeData(ModAttachments.AMBUSH_FIGHTERS)
        level.playSound(null, victim.blockPosition(), SoundEvents.PILLAGER_CELEBRATE, SoundSource.HOSTILE, 1.5f, 0.9f)
        val message = Component.translatable("message.guildmark.ambush", victim.name)
        for (player in witnesses(level, victim)) player.sendSystemMessage(message)
    }

    /**
     * Called every check while [victim] travels. Returns true while an ambush is still on; when the last
     * bandit falls a caravan pays those who fought the bandits off, and the traveller moves on.
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
        // Paid and named in the news: those who fought the bandits off, not anyone who stood and watched
        val fighters = Defenders.who(level, victim.getData(ModAttachments.AMBUSH_FIGHTERS))
        victim.removeData(ModAttachments.AMBUSH_FIGHTERS)
        for (player in guards) if (player !in fighters) {
            val key = if (caravan) "message.guildmark.ambush_defended_unpaid" else "message.guildmark.ambush_defended"
            player.sendSystemMessage(Component.translatable(key, victim.name))
        }
        for (player in fighters) {
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
        if (fighters.isNotEmpty()) GuildNews.add(level, "news.guildmark.ambush", fighters.joinToString(", ") { it.name.string }, victim.name.string)
        return false
    }

    /** [player] dealt [damage] to [mob]: if it's a bandit, they're fighting for the traveller it waylaid. */
    fun onAttackerHurt(level: ServerLevel, mob: LivingEntity, player: Player, damage: Float) {
        if (!mob.hasData(ModAttachments.WAYLAYS)) return
        val victim = level.getEntity(mob.getData(ModAttachments.WAYLAYS)) as? PathfinderMob ?: return
        if (mob.uuid !in victim.getData(ModAttachments.AMBUSHERS)) return
        val fought = victim.getData(ModAttachments.AMBUSH_FIGHTERS)
        victim.setData(ModAttachments.AMBUSH_FIGHTERS, fought + (player.uuid to (fought[player.uuid] ?: 0f) + damage))
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
        bandit.setData(ModAttachments.WAYLAYS, victim.uuid)
        level.addFreshEntity(bandit)
        return bandit
    }
}
