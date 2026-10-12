package io.github.vaspyyy.guildmark.death

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModParticles
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import java.util.Optional
import java.util.WeakHashMap
import kotlin.math.min

/**
 * Where a player's lost Guild Marks lie. [stamp] names their latest fall; Marks dropped at any earlier
 * one are gone. [at] and [amount] describe the Marks still waiting (none once won back), and [forfeited]
 * is how many the latest fall cost for good, for the death screen.
 */
data class PurseState(val stamp: Long = 0L, val at: Optional<GlobalPos> = Optional.empty(), val amount: Int = 0, val forfeited: Int = 0) {
    companion object {
        val CODEC: Codec<PurseState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("stamp", 0L).forGetter(PurseState::stamp),
                GlobalPos.CODEC.optionalFieldOf("at").forGetter(PurseState::at),
                Codec.INT.optionalFieldOf("amount", 0).forGetter(PurseState::amount),
                Codec.INT.optionalFieldOf("forfeited", 0).forGetter(PurseState::forfeited),
            ).apply(i, ::PurseState)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, PurseState> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, PurseState::stamp,
            ByteBufCodecs.optional(GlobalPos.STREAM_CODEC), PurseState::at,
            ByteBufCodecs.VAR_INT, PurseState::amount,
            ByteBufCodecs.VAR_INT, PurseState::forfeited,
            ::PurseState,
        )
    }
}

/**
 * Losing your Marks when you fall. Whatever Guild Marks you carry stay where you fell (or, if you fell
 * into lava or out of the world, where you last stood on firm ground), and walking back to them wins
 * them back. Fall again first and they're gone. Kept inventories don't save them: the Marks go either way.
 */
object Purses {
    /** Where each player last stood on firm, dry ground, for falls into lava or the void. */
    private val lastSafe = WeakHashMap<ServerPlayer, GlobalPos>()

    /** How close the owner has to come to take the Marks back. */
    private const val REACH = 1.6

    fun get(player: ServerPlayer): PurseState = player.getData(ModAttachments.PURSE)

    fun tick(player: ServerPlayer) {
        if (player.tickCount % 10 != 0) return
        if (player.onGround() && !player.isInLava && !player.isInWater && !player.isPassenger && !player.isFallFlying && !player.isSpectator) {
            lastSafe[player] = GlobalPos.of(player.level().dimension(), player.blockPosition())
        }
    }

    /** Runs last among the death handlers, so a fall another mod undid costs nothing. */
    fun onDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!GuildmarkConfig.LOSE_MARKS.get()) return
        val level = player.level()
        val before = get(player)
        val forfeited = if (before.at.isPresent) before.amount else 0
        val stamp = level.random.nextLong().let { if (it == 0L) 1L else it }
        val count = TownControl.marks(player)
        if (count <= 0) {
            player.setData(ModAttachments.PURSE, PurseState(stamp, Optional.empty(), 0, forfeited))
            return
        }
        TownControl.pay(player, count)
        val spot = restingSpot(level, player)
        val lost = LostMarks(ModEntities.LOST_MARKS.get(), level)
        lost.setPos(spot)
        lost.owner = player.uuid
        lost.stamp = stamp
        lost.amount = count
        level.addFreshEntity(lost)
        player.setData(ModAttachments.PURSE, PurseState(stamp, Optional.of(GlobalPos.of(level.dimension(), BlockPos.containing(spot))), count, forfeited))
    }

    /** Where the Marks come to rest: where the player fell, unless that's lava or the void. */
    private fun restingSpot(level: ServerLevel, player: ServerPlayer): Vec3 {
        val fell = player.position()
        val unsafe = player.isInLava || fell.y < level.minY
        if (unsafe) {
            val safe = lastSafe[player]
            if (safe != null && safe.dimension() == level.dimension()) return Vec3.atBottomCenterOf(safe.pos()).add(0.0, 0.5, 0.0)
        }
        // No firm ground to fall back on: as near the fall as the world allows
        var y = fell.y.coerceIn(level.minY + 1.0, level.maxY - 1.0)
        var steps = 0
        while (steps++ < 24 && !level.getFluidState(BlockPos.containing(fell.x, y, fell.z)).isEmpty) y += 1.0
        return Vec3(fell.x, y + 0.5, fell.z)
    }

    /** Lost Marks look after themselves: they go back to their owner on touch, or vanish once they've been lost for good. */
    fun tickLost(level: ServerLevel, lost: LostMarks) {
        val owner = lost.owner ?: return lost.discard()
        if (lost.amount <= 0) return lost.discard()
        val player = level.server.playerList.getPlayer(owner) ?: return
        // Fallen again since: these are gone for good
        if (get(player).stamp != lost.stamp) {
            level.sendParticles(ModParticles.WARD_EMBER.get(), lost.x, lost.y + 0.3, lost.z, 12, 0.2, 0.2, 0.2, 0.01)
            lost.discard()
            return
        }
        if (player.level() !== level || !player.isAlive || player.isSpectator) return
        val center = player.position().add(0.0, player.bbHeight / 2.0, 0.0)
        if (center.distanceToSqr(lost.position()) > (REACH + player.bbHeight / 2.0).let { it * it }) return
        recover(level, player, lost)
    }

    private fun recover(level: ServerLevel, player: ServerPlayer, lost: LostMarks) {
        var left = lost.amount
        val max = ItemStack(ModItems.GUILD_MARK.get()).maxStackSize
        while (left > 0) {
            val n = min(left, max)
            player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), n), Prediction.SERVER_ONLY)
            left -= n
        }
        player.setData(ModAttachments.PURSE, get(player).copy(at = Optional.empty(), amount = 0))
        level.playSound(null, lost.x, lost.y, lost.z, ModSounds.MARKS_RECOVER.get(), SoundSource.PLAYERS, 1f, 1f)
        level.sendParticles(ModParticles.WARD_EMBER.get(), lost.x, lost.y + 0.3, lost.z, 24, 0.3, 0.4, 0.3, 0.03)
        player.sendOverlayMessage(Component.translatable("message.guildmark.marks.recovered", lost.amount).withColor(GOLD))
        lost.discard()
    }

    private const val GOLD = 0xE8C26A
}
