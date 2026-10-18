package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.companion.Deed
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Social
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player
import java.util.UUID
import kotlin.math.min

/**
 * Lifting a purse: crouch close behind someone in a town and use them. Everyone has one purse a day (or one go at
 * it). Come at them where they can see you and you'd only be talking to them (a thief out on a purse job is told to
 * come from behind); from the side it's a gamble, from behind a good chance, off a sleeper nearly certain. Anyone
 * else looking on spoils the odds, the Stealth bonus (the Quiet Hand's ranks among it) improves them, and every purse
 * a player lifts in a day puts the streets more on their guard. Caught, they shout, and the guards near come for
 * you. The purse holds a few Marks, more off the wealthy; a faction job counts it (Trials).
 */
object Pickpocket {
    private const val SEEN_ARC = 70.0
    private const val BEHIND_ARC = 120.0
    private const val WITNESS_RANGE = 10.0
    /** How much warier people grow with each purse a player has lifted that day, and the most it comes to. */
    private const val WARY = 0.06
    private const val WARIEST = 0.5

    /** Purses each player has lifted, and on which day. */
    private val lifted = HashMap<UUID, Pair<Long, Int>>()

    /** Tries a hand in [victim]'s purse; false if they're facing the player, who's only talking to them. */
    fun tryLift(level: ServerLevel, player: Player, victim: Townsperson): Boolean {
        if (player.isSpectator || victim.isLeader || victim.watcher) return false
        val today = VillageBoards.day(level)
        val asleep = victim.isSleeping
        val angle = Combat.angleTo(victim.yHeadRot, player.x - victim.x, player.z - victim.z).toDouble()
        if (!asleep && angle < SEEN_ARC && victim.hasLineOfSight(player)) {
            if (!Trials.wants(player, "pickpocket", "purses")) return false
            player.sendOverlayMessage(Component.translatable("message.guildmark.pickpocket_facing"))
            return true
        }
        if (!victim.role.adult || victim.pursedDay == today) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.pickpocket_empty", victim.displayName))
            return true
        }
        victim.pursedDay = today
        val already = lifted[player.uuid]?.takeIf { it.first == today }?.second ?: 0
        var chance = when {
            asleep -> 0.95
            angle > BEHIND_ARC -> 0.8
            else -> 0.45
        }
        chance += min(0.3, Talents[player, Bonus.STEALTH] * 0.5)
        chance -= 0.15 * witnesses(level, player, victim)
        chance -= min(WARIEST, WARY * already)
        if (level.random.nextDouble() < chance.coerceIn(0.05, 0.98)) {
            lifted[player.uuid] = today to already + 1
            lift(level, player, victim)
        } else {
            caught(level, player, victim)
        }
        return true
    }

    /** Others awake who'd see it: facing the player, near, with nothing between. */
    private fun witnesses(level: ServerLevel, player: Player, victim: Townsperson): Int =
        level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(WITNESS_RANGE, 3.0, WITNESS_RANGE)) {
            it !== victim && it.isAlive && it.role.adult && !it.isSleeping && !it.dozing &&
                Combat.angleTo(it.yHeadRot, player.x - it.x, player.z - it.z) < SEEN_ARC && it.hasLineOfSight(player)
        }.size

    private fun lift(level: ServerLevel, player: Player, victim: Townsperson) {
        val random = level.random
        val (low, high) = when (victim.role) {
            TownRole.NOBLE -> 6 to 12
            TownRole.MERCHANT -> 4 to 9
            TownRole.INNKEEPER, TownRole.MAGE -> 3 to 7
            TownRole.PRIEST, TownRole.SMITH, TownRole.BAKER -> 2 to 6
            TownRole.GUARD, TownRole.KNIGHT, TownRole.BARD, TownRole.CITIZEN -> 1 to 5
            TownRole.THIEF -> 0 to 2
            else -> 1 to 3
        }
        val marks = low + random.nextInt(high - low + 1)
        player.inventory.placeItemBackInInventory(PurseItem.of(marks), Prediction.SERVER_ONLY)
        level.playSound(null, player.x, player.y, player.z, FactionRegistry.PICKPOCKET_LIFT.get(), SoundSource.PLAYERS, 0.5f, 0.95f + random.nextFloat() * 0.1f)
        player.sendOverlayMessage(Component.translatable("message.guildmark.pickpocket_lifted", victim.displayName))
        Skills.gain(player, Skill.STEALTH, 4.0 + marks * 0.3)
        Trials.onPurse(player, victim)
        Companions.deed(player, Deed.THEFT)
        io.github.vaspyyy.guildmark.townsfolk.talk.Witness.robbed(level, player, victim)
    }

    private fun caught(level: ServerLevel, player: Player, victim: Townsperson) {
        if (victim.isSleeping) victim.stopSleeping()
        victim.navigation.stop()
        victim.face(victim.yawTo(player.position()))
        victim.lookControl.setLookAt(player, 30f, 30f)
        victim.act(Activity.SHOUT)
        victim.say("purse_${level.random.nextInt(2)}", force = true)
        level.playSound(null, victim.x, victim.y, victim.z, FactionRegistry.PICKPOCKET_CAUGHT.get(), SoundSource.NEUTRAL, 1f, 1f)
        player.sendOverlayMessage(Component.translatable("message.guildmark.pickpocket_caught", victim.displayName))
        Social.alarm(level, victim, player)
        io.github.vaspyyy.guildmark.townsfolk.talk.Witness.caught(level, player, victim)
    }
}
