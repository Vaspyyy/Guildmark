package io.github.vaspyyy.guildmark.companion

import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * What the companion beside the player says unasked (character.guildmark.<id>.bark.*): when night falls or the rain
 * comes on out in the open, down in the deep, when the player is badly hurt, at the sight of something big, once a
 * hard fight is won, back from the dead, and now and then about nothing much. Never two within [SPACING] of each
 * other, and the same remark not again within its own while.
 */
object CompanionBanter {
    private const val SPACING = 600L
    private const val IDLE_MIN = 4800
    private const val IDLE_SPREAD = 4800
    private const val IDLE_LINES = 3
    private const val NEAR = 24.0
    private const val BOSS_SIGHT = 24.0
    /** A fight is a win worth remarking on after this many kills, or a boss. */
    private const val WIN_KILLS = 4
    /** ...once this long has passed without one, and with nothing left after them. */
    private const val CALM = 100L
    /** A fight's kills are forgotten this long after the last. */
    private const val FORGET = 1200L
    /** Below this share of their health the player is hurt enough to be told so, and above [MENDED] they're not anymore. */
    private const val HURT = 0.3f
    private const val MENDED = 0.6f
    /** Deeper than this, under rock, is the deep; back above [SURFACED] it isn't. */
    private const val DEEP = 0
    private const val SURFACED = 16

    private class Mood {
        var night: Boolean? = null
        var rain: Boolean? = null
        var deep = false
        var hurt = false
        val bosses = HashSet<UUID>()
        var kills = 0
        var bossKilled = false
        var lastKill = 0L
        var nextIdle = -1L
        var lastBark = -100000L
        var respawned = false
        val said = HashMap<String, Long>()
    }

    private val moods = HashMap<UUID, Mood>()

    private fun mood(player: Player): Mood = moods.getOrPut(player.uuid) { Mood() }

    fun forget(player: Player) {
        moods.remove(player.uuid)
    }

    fun clear() = moods.clear()

    /** Once a second for each player: has anything happened that whoever's with them would remark on? */
    fun tick(player: ServerPlayer) {
        val c = Companions.traveller(player) ?: return
        val level = player.level()
        if (c.level() !== level || c.isDowned || c.isTalking || c.distanceToSqr(player) > NEAR * NEAR) return
        val mood = mood(player)
        val now = level.gameTime
        if (mood.nextIdle < 0) mood.nextIdle = now + IDLE_MIN + level.random.nextInt(IDLE_SPREAD)
        if (mood.respawned) {
            mood.respawned = false
            if (remark(c, "bark.respawn", force = true)) return
        }
        val pos = player.blockPosition()
        val open = level.dimension() == Level.OVERWORLD && level.canSeeSky(pos.above())
        // Night and rain, as they come on, out under the sky
        val night = level.isDarkOutside
        if (mood.night == false && night && open && remark(c, "bark.night", 6000)) return
        mood.night = night
        val rain = level.isRainingAt(pos.above())
        if (mood.rain == false && rain && remark(c, "bark.rain", 6000)) return
        mood.rain = rain
        // Down in the deep
        val deep = if (mood.deep) pos.y < SURFACED else pos.y < DEEP && !open
        if (deep && !mood.deep) {
            mood.deep = true
            if (remark(c, "bark.deep", 6000)) return
        }
        mood.deep = deep
        // The player's badly hurt
        val share = player.health / player.maxHealth
        if (!mood.hurt && share < HURT && player.isAlive) {
            mood.hurt = true
            if (remark(c, "bark.hurt", 600, force = true)) return
        } else if (share > MENDED) {
            mood.hurt = false
        }
        // Something big
        val boss = level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(BOSS_SIGHT)) { isBoss(it) && it.isAlive }
            .firstOrNull { it.uuid !in mood.bosses }
        if (boss != null) {
            mood.bosses += boss.uuid
            if (mood.bosses.size > 32) mood.bosses.clear()
            if (remark(c, "bark.boss", 1200, force = true)) return
        }
        // A fight won
        if (mood.kills > 0 && now - mood.lastKill > FORGET) {
            mood.kills = 0
            mood.bossKilled = false
        }
        if ((mood.kills >= WIN_KILLS || mood.bossKilled) && now - mood.lastKill > CALM && !foesNear(player, c)) {
            mood.kills = 0
            mood.bossKilled = false
            if (remark(c, "bark.victory", 2400)) return
        }
        // Now and then, when nothing else is going on
        if (now >= mood.nextIdle) {
            mood.nextIdle = now + IDLE_MIN + level.random.nextInt(IDLE_SPREAD)
            if (now - mood.lastKill > FORGET && !foesNear(player, c) && c.mode == CompanionEntity.Mode.FOLLOW) {
                remark(c, "bark.idle.${level.random.nextInt(IDLE_LINES)}")
            }
        }
    }

    private fun isBoss(entity: LivingEntity): Boolean =
        if (entity is CombatEnemy) entity.isBoss && entity.canFight() else Talents.isBoss(entity)

    private fun foesNear(player: Player, c: CompanionEntity): Boolean =
        player.level().getEntitiesOfClass(Mob::class.java, player.boundingBox.inflate(12.0)) { it.isAlive && (it.target === player || it.target === c) }.isNotEmpty()

    /**
     * [c] says [key], unless they spoke a moment ago (or, short of [force], within [SPACING]) or said it within
     * [cooldown] ticks. True if they did.
     */
    fun remark(c: CompanionEntity, key: String, cooldown: Int = 0, force: Boolean = false): Boolean {
        val player = c.player ?: return false
        val mood = mood(player)
        val now = c.level().gameTime
        if (c.isDowned || c.isTalking) return false
        if (now - mood.lastBark < if (force) 60L else SPACING) return false
        if (cooldown > 0 && now - (mood.said[key] ?: -100000L) < cooldown) return false
        if (!c.say(key)) return false
        mood.lastBark = now
        mood.said[key] = now
        return true
    }

    /** The player died and is back: whoever travels with them says so once they've caught up. */
    fun onRespawn(player: ServerPlayer) {
        mood(player).respawned = true
    }

    /** [player] (or their companion) killed [victim]: towards a fight won. */
    fun onKill(player: Player, victim: LivingEntity, boss: Boolean) {
        if (victim !is Mob) return
        val mood = mood(player)
        mood.kills++
        if (boss) mood.bossKilled = true
        mood.lastKill = player.level().gameTime
    }
}
