package io.github.vaspyyy.guildmark.wardfire

import io.github.vaspyyy.guildmark.atlas.Atlas
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.block.WardfireBlock
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.companion.Deed
import io.github.vaspyyy.guildmark.faction.Trials
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.network.OpenWardfirePayload
import io.github.vaspyyy.guildmark.network.WardfireActionPayload
import io.github.vaspyyy.guildmark.network.WardfireTravelledPayload
import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.registry.ModParticles
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.github.vaspyyy.guildmark.road.Coaches
import io.github.vaspyyy.guildmark.story.HollowKing
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.clock.ClockTimeMarkers
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.Tags
import net.neoforged.neoforge.network.PacketDistributor
import java.util.WeakHashMap

/**
 * What a Wardfire does. Using a cold one kindles it and puts it on your map; using a lit one sits you
 * down to rest: you're healed and cleansed, your stamina and flask are refilled, foes nearby mend too,
 * and it's where you wake after a fall. From its menu you can wait for morning or nightfall when you're alone in the world. From
 * the map you can travel to any Wardfire you've kindled in this dimension, while no foe is on you.
 */
object Wardfires {
    /** How far from a lit Wardfire the player can be and still work its menu. */
    private const val REACH = 6.0
    /** A creature this close with you as its target keeps you from resting or travelling. */
    private const val FOE_RANGE = 16.0
    /** Ticks after a blow, given or taken, before you can rest or travel. */
    private const val CALM_TICKS = 100
    /** How far around a Wardfire resting sets the foes back. */
    private const val RESET_RANGE = 48.0
    /** Ticks between menu or travel requests; faster ones are ignored. */
    private const val THROTTLE = 10

    private val lastRequest = WeakHashMap<ServerPlayer, Long>()

    fun isLit(state: BlockState): Boolean = state.block is WardfireBlock && state.getValue(WardfireBlock.LIT)

    /** [player] is close enough to the lit Wardfire at [pos] to work its menu. */
    fun near(player: ServerPlayer, pos: BlockPos): Boolean {
        val level = player.level()
        return level.isLoaded(pos) && isLit(level.getBlockState(pos)) && player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH
    }

    fun use(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer) {
        if (!player.isAlive || player.isSpectator) return
        if (!state.getValue(WardfireBlock.LIT)) kindle(level, pos, state, player) else rest(level, pos, player)
    }

    private fun kindle(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer) {
        level.setBlock(pos, state.setValue(WardfireBlock.LIT, true), Block.UPDATE_ALL)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        level.playSound(null, pos, ModSounds.WARDFIRE_KINDLE.get(), SoundSource.BLOCKS, 1f, 1f)
        level.sendParticles(ModParticles.WARD_EMBER.get(), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 40, 0.3, 0.3, 0.3, 0.04)
        level.sendParticles(ParticleTypes.FLAME, pos.x + 0.5, pos.y + 0.4, pos.z + 0.5, 12, 0.18, 0.15, 0.18, 0.02)
        know(level, pos, player)
        Milestones.award(player, Milestones.WARDFIRE)
        WardensFlask.onKindle(player)
        // A cold fire lit again: Ysolde's chapter counts it, and those who keep the roads are glad of it
        Trials.credit(player, "kindle")
        Companions.deed(player, Deed.KINDLE)
    }

    /** Puts the Wardfire at [pos] on the player's map, if it isn't already. */
    private fun know(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        Atlas.note(player, AtlasPlace(AtlasPlace.WARDFIRE, level.dimension(), pos, found = level.gameTime))
    }

    private fun rest(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        // A fire someone else kindled goes on your map the first time you come to it
        know(level, pos, player)
        if (threatened(level, player)) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.wardfire.foes").withColor(RED))
            return
        }
        restore(player)
        resetFoes(level, pos)
        Companions.onRest(level, pos, player)
        player.setRespawnPosition(ServerPlayer.RespawnConfig(LevelData.RespawnData(GlobalPos.of(level.dimension(), pos), 0f, 0f), false), false)
        Atlas.refresh(player)
        level.playSound(null, pos, ModSounds.WARDFIRE_REST.get(), SoundSource.PLAYERS, 0.9f, 1f)
        level.sendParticles(ModParticles.WARD_EMBER.get(), player.x, player.y + 0.8, player.z, 14, 0.3, 0.5, 0.3, 0.01)
        val flags = if (canWait(level)) OpenWardfirePayload.CAN_WAIT else 0
        PacketDistributor.sendToPlayer(player, OpenWardfirePayload(pos, flags))
    }

    /** Resting: whole again, the poisons and curses gone, stamina and flask full. */
    private fun restore(player: ServerPlayer) {
        player.health = player.maxHealth
        player.activeEffects.filter { it.effect.value().category == MobEffectCategory.HARMFUL }.map { it.effect }.forEach { player.removeEffect(it) }
        player.clearFire()
        player.airSupply = player.maxAirSupply
        Combat.refill(player)
        WardensFlask.refill(player)
        Talents.onRest(player)
    }

    /**
     * Resting gives the land its strength back as well as you: foes around the fire mend their wounds
     * and lose your trail. Bosses keep to their own rules.
     */
    private fun resetFoes(level: ServerLevel, pos: BlockPos) {
        val near = level.getEntitiesOfClass(Mob::class.java, AABB(pos).inflate(RESET_RANGE)) {
            it.isAlive && it is Enemy && it !is HollowKing && !it.typeHolder().`is`(Tags.EntityTypes.BOSSES)
        }
        for (mob in near) {
            mob.health = mob.maxHealth
            mob.target = null
        }
    }

    /** Something is fighting the player, or was a moment ago. Creative players are never kept from a fire. */
    fun threatened(level: ServerLevel, player: ServerPlayer): Boolean {
        if (player.isCreative) return false
        val now = level.gameTime
        if (now - Combat.state(player).lastCombat < CALM_TICKS) return true
        if (player.lastHurtByMob != null && player.tickCount - player.lastHurtByMobTimestamp < CALM_TICKS) return true
        return level.getEntitiesOfClass(Mob::class.java, player.boundingBox.inflate(FOE_RANGE)) { it.isAlive && it.target === player }.isNotEmpty()
    }

    /** Waiting moves everyone's clock, so it's only offered when nobody else is in the world. */
    private fun canWait(level: ServerLevel): Boolean =
        level.server.playerCount <= 1 && level.dimensionType().defaultClock().isPresent && level.gameRules.get(GameRules.ADVANCE_TIME)

    private fun allow(player: ServerPlayer): Boolean {
        val now = player.level().gameTime
        val before = lastRequest[player]
        if (before != null && now - before in 0 until THROTTLE) return false
        lastRequest[player] = now
        return true
    }

    fun onAction(player: ServerPlayer, pos: BlockPos, action: Int) {
        if (!allow(player) || !player.isAlive) return
        val level = player.level()
        if (!near(player, pos)) return
        when (action) {
            WardfireActionPayload.WAIT -> {
                if (!canWait(level) || threatened(level, player)) return
                val clock = level.dimensionType().defaultClock().orElse(null) ?: return
                val marker = if (level.isDarkOutside) ClockTimeMarkers.DAY else ClockTimeMarkers.NIGHT
                level.server.clockManager().moveToTimeMarker(clock, marker)
                level.playSound(null, pos, ModSounds.WARDFIRE_REST.get(), SoundSource.PLAYERS, 0.7f, 0.8f)
            }
        }
    }

    /** Travel to the kindled Wardfire at [to]: arrive beside it facing the flames, with your followers. */
    fun travel(player: ServerPlayer, to: BlockPos) {
        if (!allow(player)) return
        val level = player.level()
        val reason = when {
            !player.isAlive || player.isSpectator -> "gone"
            Atlas.get(player).find(AtlasPlace.WARDFIRE, level.dimension(), to) == null -> "unknown"
            player.isPassenger -> "mounted"
            Coaches.escorting(player) -> "escort"
            threatened(level, player) -> "foes"
            else -> null
        }
        if (reason != null) return fail(player, reason)
        // Wake the far chunk so the fire can be checked and a spot found beside it
        level.getChunk(to.x shr 4, to.z shr 4)
        if (!isLit(level.getBlockState(to))) {
            Atlas.forget(player, AtlasPlace.WARDFIRE, level.dimension(), to)
            return fail(player, "gone")
        }
        val spot = RespawnAnchorBlock.findStandUpPosition(EntityTypes.PLAYER, level, to).orElse(null) ?: return fail(player, "blocked")
        val origin = player.blockPosition()
        val followers = level.getEntitiesOfClass(GuildMember::class.java, AABB(origin).inflate(16.0)) { it.leader == player.uuid && it.following && !it.isPassenger }

        // The traveller already hears the journey from their own fade; these are for anyone watching
        level.playSound(player, origin, ModSounds.WARDFIRE_TRAVEL.get(), SoundSource.PLAYERS, 0.9f, 1f)
        level.sendParticles(ModParticles.WARD_EMBER.get(), player.x, player.y + 1.0, player.z, 30, 0.3, 0.7, 0.3, 0.03)
        val yaw = facing(spot, to)
        player.teleportTo(level, spot.x, spot.y, spot.z, setOf(), yaw, 0f, true)
        for (member in followers) {
            val at = RespawnAnchorBlock.findStandUpPosition(member.type, level, to).orElse(spot)
            member.teleportTo(level, at.x, at.y, at.z, setOf(), member.yRot, member.xRot, false)
        }
        Companions.onTravel(player, spot)
        level.playSound(player, to, ModSounds.WARDFIRE_TRAVEL.get(), SoundSource.PLAYERS, 0.9f, 1.1f)
        PacketDistributor.sendToPlayer(player, WardfireTravelledPayload(true))
    }

    private fun facing(from: Vec3, at: BlockPos): Float {
        val dx = at.x + 0.5 - from.x
        val dz = at.z + 0.5 - from.z
        return Mth.wrapDegrees((Mth.atan2(dz, dx) * Mth.RAD_TO_DEG).toFloat() - 90f)
    }

    private fun fail(player: ServerPlayer, key: String) {
        player.sendSystemMessage(Component.translatable("message.guildmark.wardfire.travel.$key").withColor(RED))
        PacketDistributor.sendToPlayer(player, WardfireTravelledPayload(false))
    }

    private const val RED = 0xE05A4A
}
