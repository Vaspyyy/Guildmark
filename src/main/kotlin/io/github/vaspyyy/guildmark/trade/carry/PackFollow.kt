package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import java.util.UUID

/**
 * The pack animals that are loaded just now, and what they do for their owners (living-world/trader/design.md, Part 3, "The pack
 * animal"). An animal is a pack animal while it wears a [PackKit]; it is found here from the moment it joins the level (goals are not
 * saved, so every load adopts it again) until it leaves. The server is the only place any of it is decided.
 *
 * With carrying switched off ([TradeConfig.carry]) nothing here acts: the animals stand as animals do and their chests are
 * nobody's laden goods, but every chest still opens.
 */
object PackFollow {
    /** How far a whistle reaches, in blocks. */
    const val WHISTLE_RANGE = 64.0

    /** One loaded pack animal. */
    class Rig(val animal: AbstractChestedHorse)

    private val rigs = LinkedHashMap<UUID, Rig>()

    /** Hooks the level: an animal with a kit is adopted as it joins and let go as it leaves. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { event ->
            val animal = event.entity as? AbstractChestedHorse
            if (animal != null && !event.level.isClientSide && PackKit.of(animal) != null) adopt(animal)
        }
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent::class.java) { event ->
            val animal = event.entity as? AbstractChestedHorse
            if (animal != null && !event.level.isClientSide) release(animal)
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> rigs.clear() }
    }

    /** Makes [animal], which wears a kit, a pack animal of this session. */
    fun adopt(animal: AbstractChestedHorse) {
        rigs[animal.uuid] = Rig(animal)
    }

    /** Lets go of [animal]: it is no longer a pack animal of this session (its kit, if it still has one, is the harness's to take off). */
    fun release(animal: AbstractChestedHorse) {
        val rig = rigs[animal.uuid]
        if (rig != null && rig.animal === animal) rigs.remove(animal.uuid)
    }

    /** Whether [animal] is one of the loaded pack animals. */
    fun isRigged(animal: AbstractChestedHorse): Boolean = rigs[animal.uuid]?.animal === animal

    /** The loaded pack animals that are [player]'s, in the player's level and within [radius] blocks of them, nearest first. */
    fun owned(player: ServerPlayer, radius: Double): List<AbstractChestedHorse> {
        if (rigs.isEmpty()) return listOf()
        val limit = radius * radius
        return rigs.values.map { it.animal }
            .filter { it.isAlive && it.level() === player.level() && it.ownerReference?.uuid == player.uuid && it.distanceToSqr(player) <= limit }
            .sortedBy { it.distanceToSqr(player) }
    }

    /**
     * The panniers of [player]'s pack animals within [radius] blocks of them: the pack animal's [io.github.vaspyyy.guildmark.trade.Carrier].
     * None while carrying is off, so that goods in an animal's chest are not counted as the trader's.
     */
    fun holdings(player: ServerPlayer, radius: Double): List<Container> {
        if (rigs.isEmpty() || !TradeConfig.carry()) return listOf()
        return owned(player, radius).filter { it.hasChest() }.map { it.getInventory() }
    }

    /**
     * Blows the whistle for [player]: every pack animal of theirs within [WHISTLE_RANGE] blocks is called to follow, or, if [stay], told
     * to stay where it stands. Answers how many heard it.
     */
    fun whistle(player: ServerPlayer, stay: Boolean): Int {
        val level = player.level() as? ServerLevel ?: return 0
        level.playSound(null, player.x, player.y, player.z, TradeSounds.get(if (stay) "trade.whistle_stay" else "trade.whistle_come"), SoundSource.PLAYERS, 1.0f, 1.0f)
        var heard = 0
        for (animal in owned(player, WHISTLE_RANGE)) {
            val kit = PackKit.of(animal) ?: continue
            val now = if (stay) kit.copy(mode = PackKit.Mode.STAY, anchor = animal.blockPosition()) else kit.copy(mode = PackKit.Mode.FOLLOW, anchor = player.blockPosition())
            if (now != kit) animal.setData(Carry.PACK_KIT, now)
            heard++
        }
        val word = when {
            heard == 0 -> if (stay) "none_stay" else "none_come"
            heard == 1 -> if (stay) "one_stay" else "one_come"
            else -> if (stay) "many_stay" else "many_come"
        }
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.whistle.$word", heard))
        return heard
    }
}
