package io.github.vaspyyy.guildmark.village.live.folk

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Talking
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.inventory.MerchantMenu
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * "Their old wares": the talk choice that opens the vanilla trading screen with the old offers of the villager an adopted resident
 * was (living-world/villages/design.md section 6.4). The keepsake is opened as a [KeptVillager] at the resident's feet, the screen is
 * opened through it, and every trade is kept at once, so what was bought, the xp and a new level are in the keepsake before the
 * screen closes, and a player who is cut off with the screen open loses nothing.
 *
 * One player at a time for each resident: while the screen is open the resident stands still facing the player ([Browsing]) and a
 * second player is told they are busy. The screen closes as vanilla's does when the player walks more than a few blocks from
 * the resident (the kept villager is where the resident stood when the screen opened, and the resident does not move meanwhile).
 */
object OldWares {
    /** A screen open: the [kept] villager, the [player] it is open for and the [body] of the resident, and the time gossip last faded in it. */
    private class Session(val resident: Int, val player: UUID, val body: UUID, val kept: KeptVillager, var decay: Long)

    private val sessions = HashMap<Int, Session>()

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { gone(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> sessions.clear() }
    }

    /** Whether the talk with [p] has "Their old wares" to show: an adopted resident whose kept villager has something to sell. */
    fun offered(level: ServerLevel, p: Townsperson): Boolean = p.resident >= 0 && VillageKeepsakes.wares(level, p.resident)

    /** Whether [resident] has a screen open now. */
    fun browsing(resident: Int): Boolean = sessions.containsKey(resident)

    /**
     * Opens the wares of [p] for [player], who has just chosen them in a talk that is over: the kept villager is made from the
     * keepsake, its gossip of the player is what their standing in the village makes it and fades if a day has gone, it restocks if
     * vanilla's rule lets it, and the screen opens. True if the choice was dealt with (the screen, or a word of why not).
     */
    fun open(level: ServerLevel, player: ServerPlayer, p: Townsperson): Boolean {
        val id = p.resident
        val resident = Residents.get(level)[id] ?: return false
        val tag = VillageKeepsakes.get(level, id) ?: return false
        val name = Component.literal(resident.name)
        // Someone else has the screen, or the resident is at something that is not a talk
        val busy = sessions[id]
        if (busy != null && busy.kept.tradingPlayer != null || p.director != null && p.director !is Talking) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.village.wares_busy", name))
            return true
        }
        if (busy != null) sessions.remove(id)
        val kept = KeptVillager(EntityTypes.VILLAGER, level)
        kept.fill(level, tag.copy())
        kept.resident = id
        kept.snapTo(p.x, p.y, p.z, p.yRot, 0f)
        val now = level.gameTime
        // The gossip a villager's own ticks would have faded by now: once in a day
        var decay = tag.getLongOr(VillageKeepsakes.DECAY, now)
        if (now < decay) decay = now
        if (now - decay >= 24000L) {
            kept.gossips.decay()
            decay = now
        }
        // What the village thinks of this player: the good word their standing gives, as a living villager would hold it
        val board = Villages.byKey(level, resident.capital.asLong())?.board ?: -1L
        val tier = if (board >= 0L) Standing.tier(level, BlockPos.of(board), player) else StandingTier.STRANGER
        VillageKeepsakes.setTier(kept.gossips, player.uuid, tier.gossip)
        // Restocking as vanilla's rule has it: twice a day at most, and only what has been bought from
        if (kept.shouldRestock(level)) kept.restock()
        if (kept.offers.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.village.wares_none", name))
            return true
        }
        val s = Session(id, player.uuid, p.uuid, kept, decay)
        kept.traded = Runnable { save(level, s) }
        kept.closed = Runnable {
            save(level, s)
            finished(id, level)
        }
        sessions[id] = s
        take(p, id, player.uuid)
        kept.openFor(player, Component.translatable("gui.guildmark.village.wares_title", name, kept.trade()))
        if (player.containerMenu !is MerchantMenu) {
            // The screen did not open (another mod's say, or a menu that could not be left): the resident goes back to their day
            kept.setTradingPlayer(null)
            return true
        }
        player.awardStat(Stats.TALKED_TO_VILLAGER)
        save(level, s)
        return true
    }

    /** The keepsake of the session's resident is written from the kept villager as it is now. */
    private fun save(level: ServerLevel, s: Session) {
        val old = VillageKeepsakes.get(level, s.resident)
        val tag = s.kept.saved(level)
        VillageKeepsakes.carry(old, tag)
        tag.putLong(VillageKeepsakes.DECAY, s.decay)
        VillageKeepsakes.put(level, s.resident, tag)
    }

    /** The resident [id]'s screen is over: the session ends and their body goes back to their day. */
    private fun finished(id: Int, level: ServerLevel) {
        val s = sessions.remove(id) ?: return
        val body = level.getEntity(s.body) as? Townsperson ?: return
        if (body.director is Browsing) release(body)
    }

    /** [p] stops what they were at to show their wares (as a keeper does to serve): [Browsing] has charge of them until the screen closes. */
    private fun take(p: Townsperson, id: Int, player: UUID) {
        p.routine.interrupt()
        p.director = Browsing(id, player)
        p.navigation.stop()
        if (p.seat != null) p.standUp()
        p.act(Activity.NONE)
    }

    /** [p] goes back to their day, whatever they were at starting afresh. */
    internal fun release(p: Townsperson) {
        if (p.director is Browsing) p.director = null
        p.act(Activity.NONE)
        p.attending?.let { p.stopListening(it) }
        p.routine.interrupt()
    }

    /** The player of [resident]'s open screen, for [Browsing]. */
    internal fun customer(resident: Int, level: ServerLevel): ServerPlayer? {
        val s = sessions[resident] ?: return null
        val who = level.getPlayerByUUID(s.player) as? ServerPlayer ?: return null
        return if (s.kept.tradingPlayer === who) who else null
    }

    // ---- Each tick ----

    private fun tick(server: MinecraftServer) {
        if (sessions.isEmpty()) return
        val level = server.overworld()
        for (s in sessions.values.toList()) {
            val player = server.playerList.getPlayer(s.player)
            when {
                // Gone: a player who quit has their menu left open, and nothing calls its `removed`
                player == null || player.isRemoved || !player.isAlive -> s.kept.setTradingPlayer(null)
                s.kept.tradingPlayer == null -> sessions.remove(s.resident)
                player.containerMenu !is MerchantMenu -> s.kept.setTradingPlayer(null)
                // What a villager does a tick after a trade
                else -> if (s.kept.afterTrade(level)) save(level, s)
            }
        }
    }

    private fun gone(player: UUID) {
        for (s in sessions.values.toList()) if (s.player == player) s.kept.setTradingPlayer(null)
    }

    /** Forgets the screens (a world is closing): each trade was kept as it was made. */
    fun forget() = sessions.clear()
}

/**
 * The resident while their wares are open: they stand where they are facing the player, as a keeper does at the counter, and
 * nothing moves them on. If something happens to them (they are hurt, a monster comes near) the screen is closed for the player,
 * which keeps the trade and frees them; a second player who comes up is told they are busy.
 */
class Browsing(private val resident: Int, private val player: UUID) : Director {
    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val who = OldWares.customer(resident, level)
        if (who == null) {
            OldWares.release(p)
            return
        }
        if (p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE || p.tickCount % 10 == 0 && p.threat() != null) {
            who.closeContainer()
            OldWares.release(p)
            return
        }
        p.navigation.stop()
        p.nextSpeech = level.gameTime + 100
        p.lookControl.setLookAt(who, 30f, 30f)
        p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(who.position()), TURN))
        p.attend(who, 40)
        if (p.activity != Activity.NONE && p.actDone) p.act(Activity.NONE)
    }

    // The screen is the player's: anyone else is told, and not let talk over it
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        if (player.uuid == this.player) return true
        val name = (p.level() as? ServerLevel)?.let { Residents.get(it)[resident]?.name } ?: return true
        player.sendOverlayMessage(Component.translatable("message.guildmark.village.wares_busy", Component.literal(name)))
        return true
    }

    private companion object {
        /** Degrees a tick they turn toward the player. */
        const val TURN = 20f
    }
}
