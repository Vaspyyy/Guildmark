package io.github.vaspyyy.guildmark.siege

import io.github.vaspyyy.guildmark.road.Bandits
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import java.util.UUID

/**
 * Who really fought off a siege or a bandit ambush, so the pay for beating it goes to them and not to everyone who
 * happened to be standing about: a player who has dealt the attackers [ENOUGH] damage, by their own blows, shots and
 * spells or through a tamed beast of theirs.
 */
object Defenders {
    /** Two hearts: a real blow or two, so a single tap from someone looking on doesn't count. */
    const val ENOUGH = 4.0f

    fun onDamaged(event: LivingDamageEvent.Post) {
        val level = event.entity.level() as? ServerLevel ?: return
        if (event.inflictedDamage <= 0f) return
        val player = playerBehind(event.source) ?: return
        Sieges.onAttackerHurt(event.entity, player, event.inflictedDamage)
        Bandits.onAttackerHurt(level, event.entity, player, event.inflictedDamage)
    }

    /** The players in [fought] (the damage each dealt) who dealt [ENOUGH] and are in [level] to be paid. */
    fun who(level: ServerLevel, fought: Map<UUID, Float>): List<Player> =
        fought.filterValues { it >= ENOUGH }.keys.mapNotNull { level.getPlayerByUUID(it) }.filter { !it.isSpectator }

    private fun playerBehind(source: DamageSource): Player? = when (val entity = source.entity) {
        is Player -> entity
        is OwnableEntity -> entity.rootOwner as? Player
        else -> null
    }
}
