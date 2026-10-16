package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.townsfolk.Places
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent

/**
 * The Trial of Arms: a sworn knight waits at a sparring ring in the Hearthsworn's training yard. Step into the ring
 * and they salute and come at you; the first to fall below a third of their strength yields. Nobody dies of it: a
 * blow that would bring the player that low ends the bout instead. Step out of the ring and the bout is off. A lost or
 * abandoned bout can be fought again at once; a won one is the chapter done.
 */
class Spar(player: ServerPlayer) : TrialRun(player) {
    private var knight: SwornKnight? = null
    private var ring: Vec3? = null
    private var start: BlockPos? = null
    private var yaw = 0f
    /** The player must step out of the ring before another bout (after one ends). */
    private var rearm = false
    private var won = false
    private var told = false

    override fun tick(level: ServerLevel, stack: ItemStack, note: QuestNote, state: ContractState): Boolean {
        val centre = ring ?: state.destination?.let { find(level, it) } ?: return true
        val far = player.distanceToSqr(centre)
        if (far > HIDE * HIDE) {
            dismiss()
            return !won
        }
        val knight = knight?.takeIf { it.isAlive && !it.isRemoved }
        if (knight == null) {
            this.knight = null
            if (won || far > SHOW * SHOW || !Places.loaded(level, BlockPos.containing(centre), 8)) return true
            this.knight = SwornKnight.create(level, start ?: BlockPos.containing(centre), yaw, SwornKnight.Mode.SPAR, player.uuid)
            if (!told) {
                told = true
                player.sendSystemMessage(Component.translatable("message.guildmark.spar_wait"))
            }
            return true
        }
        if (won) return true
        val inside = inside(centre)
        if (!knight.inBout) {
            if (!inside) rearm = false
            else if (!rearm && player.isAlive && !player.isSpectator) begin(level, knight)
            return true
        }
        when {
            !player.isAlive || player.isSpectator -> off(level, knight, null)
            !inside -> off(level, knight, "message.guildmark.spar_off")
            knight.health <= knight.maxHealth * YIELD -> win(level, knight, stack)
            // A knight chasing out of the ring is brought back to it
            knight.position().distanceToSqr(centre.x, knight.y, centre.z) > RING * RING -> knight.navigation.moveTo(centre.x, centre.y, centre.z, 1.0)
        }
        return true
    }

    /** The ring nearest Corra in the yard round [near], and where the knight stands in it. */
    private fun find(level: ServerLevel, near: BlockPos): Vec3? {
        val seat = FactionSeats.seat(level, Faction.HEARTHSWORN, near) ?: return null
        val spot = seat.rings.minByOrNull { it.pos.distSqr(near) } ?: return null
        val centre = Vec3.atBottomCenterOf(spot.pos)
        ring = centre
        start = spot.pos.relative(spot.facing.opposite, 2)
        yaw = spot.facing.toYRot()
        return centre
    }

    private fun inside(centre: Vec3): Boolean {
        val dx = player.x - centre.x
        val dz = player.z - centre.z
        return dx * dx + dz * dz <= RING * RING && player.y > centre.y - 1.5 && player.y < centre.y + 2.5
    }

    private fun begin(level: ServerLevel, knight: SwornKnight) {
        knight.reset()
        knight.inBout = true
        knight.target = player
        knight.cooldown = 30
        knight.salute(level)
        player.sendSystemMessage(Component.translatable("message.guildmark.spar_begin", knight.displayName))
    }

    /** The bout ends without a winner (stepped out, or worse); [key] says why, if there's anything to say. */
    private fun off(level: ServerLevel, knight: SwornKnight, key: String?) {
        knight.reset()
        rearm = true
        if (key != null) player.sendSystemMessage(Component.translatable(key))
        level.playSound(null, knight.x, knight.y, knight.z, FactionRegistry.SPAR_YIELD.get(), SoundSource.NEUTRAL, 0.6f, 1.1f)
    }

    /** The player is brought too low: they yield, and the knight helps them up. */
    fun lost(level: ServerLevel, knight: SwornKnight) {
        if (!knight.inBout) return
        knight.reset()
        rearm = true
        knight.salute(level)
        player.sendSystemMessage(Component.translatable("message.guildmark.spar_lost", knight.displayName))
        level.playSound(null, player.x, player.y, player.z, FactionRegistry.SPAR_YIELD.get(), SoundSource.PLAYERS, 1f, 0.9f)
    }

    private fun win(level: ServerLevel, knight: SwornKnight, stack: ItemStack) {
        knight.reset()
        won = true
        knight.salute(level)
        player.sendSystemMessage(Component.translatable("message.guildmark.spar_won", knight.displayName))
        level.playSound(null, knight.x, knight.y, knight.z, FactionRegistry.SPAR_YIELD.get(), SoundSource.NEUTRAL, 1f, 1f)
        Trials.complete(player, stack)
    }

    private fun dismiss() {
        knight?.let { if (it.isAlive) it.discard() }
        knight = null
    }

    override fun stop(level: ServerLevel) = dismiss()

    companion object {
        /** Blocks from the ring's middle that are in it. */
        private const val RING = 3.4
        /** Below this share of their strength, a knight yields. */
        private const val YIELD = 0.3f
        private const val SHOW = 40.0
        private const val HIDE = 64.0

        /** Sets up a newly taken spar: the ring to go to. Null if the yard can't be found. */
        fun begin(level: ServerLevel, player: Player, state: ContractState): ContractState? {
            val seat = FactionSeats.seat(level, Faction.HEARTHSWORN, state.boardPos) ?: return null
            val ring = seat.rings.minByOrNull { it.pos.distSqr(seat.leader.pos) } ?: return null
            return state.copy(destination = ring.pos)
        }

        /** The sparring knight's blow on [player]: one that would bring them too low ends the bout as theirs lost. */
        fun onBlow(player: ServerPlayer, knight: SwornKnight, event: LivingIncomingDamageEvent) {
            val floor = maxOf(2f, player.maxHealth * YIELD)
            if (player.health - event.amount > floor) return
            event.isCanceled = true
            Trials.running(player, Spar::class.java)?.lost(player.level(), knight) ?: knight.reset()
        }
    }
}
