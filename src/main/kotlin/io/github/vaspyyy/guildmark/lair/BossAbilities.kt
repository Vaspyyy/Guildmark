package io.github.vaspyyy.guildmark.lair

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Per-boss fight state: seconds until the next ability, and whether it has been driven into a rage. */
class BossState(var cooldown: Int = 6, var enraged: Boolean = false)

/**
 * What lair bosses do besides hitting you. Every few seconds a boss uses its theme's ability; below half
 * health it flies into a rage once, calling its minions and speeding up.
 */
object BossAbilities {
    /** Called once a second for each awake boss, with the players in the fight. */
    fun tick(level: ServerLevel, boss: Mob, theme: LairTheme, rank: Int, players: List<Player>, state: BossState) {
        if (players.isEmpty()) return
        if (!state.enraged && boss.health <= boss.maxHealth / 2) {
            state.enraged = true
            boss.addEffect(MobEffectInstance(MobEffects.SPEED, 20 * 60, 0))
            boss.addEffect(MobEffectInstance(MobEffects.STRENGTH, 20 * 60, 0))
            level.playSound(null, boss.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.5f, 0.8f)
            val message = Component.translatable("message.guildmark.boss_enraged", boss.name)
            for (player in players) player.sendOverlayMessage(message)
            repeat(2 + rank / 2) { summon(level, boss, theme.minionIds()[level.random.nextInt(theme.minions.size)]) }
            state.cooldown = 3
            return
        }
        if (--state.cooldown > 0) return
        state.cooldown = (if (state.enraged) 5 else 8) + level.random.nextInt(3)
        val target = players[level.random.nextInt(players.size)]
        when (theme) {
            LairTheme.SPIDER_DEN -> {
                // The brood hatches, and webs spring up around a victim
                repeat(2) { summon(level, boss, Identifier.withDefaultNamespace("cave_spider")) }
                for (dx in -1..1) for (dz in -1..1) {
                    val pos = target.blockPosition().offset(dx, 0, dz)
                    if (level.random.nextInt(3) == 0 && level.getBlockState(pos).isAir) level.setBlockAndUpdate(pos, Blocks.COBWEB.defaultBlockState())
                }
                level.playSound(null, boss.blockPosition(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.6f)
            }
            LairTheme.BANDIT_CAMP -> {
                // Blows the horn: the gang gets its blood up, and a smoke bomb lands at someone's feet
                for (minion in level.getEntitiesOfClass(Mob::class.java, boss.boundingBox.inflate(16.0)) { it != boss && it is net.minecraft.world.entity.monster.illager.AbstractIllager }) {
                    minion.addEffect(MobEffectInstance(MobEffects.SPEED, 20 * 6, 0))
                    minion.addEffect(MobEffectInstance(MobEffects.STRENGTH, 20 * 6, 0))
                    minion.target = target
                }
                target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 20 * 3, 0))
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, target.x, target.y + 0.5, target.z, 20, 1.0, 0.5, 1.0, 0.02)
                level.playSound(null, boss.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.2f, 1.3f)
            }
            // Halvard fights his own fight (FallenWarden); the lair never calls on him
            LairTheme.WARDENS_WATCH -> {}
            LairTheme.BARROW -> {
                // The dead climb out of the floor, and the lord's bones harden
                repeat(2) { summon(level, boss, Identifier.withDefaultNamespace(if (level.random.nextBoolean()) "skeleton" else "zombie")) }
                boss.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 20 * 4, 1))
                level.sendParticles(ParticleTypes.SOUL, boss.x, boss.y + 1.0, boss.z, 30, 2.0, 0.5, 2.0, 0.02)
                level.playSound(null, boss.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 1.0f, 0.5f)
            }
            LairTheme.WITCH_HOLLOW -> {
                // Blinks away in a puff of smoke and curses whoever is closest
                val angle = level.random.nextDouble() * Math.PI * 2
                level.sendParticles(ParticleTypes.LARGE_SMOKE, boss.x, boss.y + 1.0, boss.z, 20, 0.3, 0.6, 0.3, 0.02)
                boss.teleportTo(boss.x + cos(angle) * 5, boss.y, boss.z + sin(angle) * 5)
                target.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 20 * 4, 1))
                target.addEffect(MobEffectInstance(MobEffects.WEAKNESS, 20 * 6, 0))
                level.playSound(null, boss.blockPosition(), SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.5f, 0.8f)
            }
            LairTheme.ASHEN_PIT -> {
                // Spits fire at everyone in reach
                for (player in players) {
                    val from = boss.eyePosition
                    val direction = player.eyePosition.subtract(from).normalize()
                    val fireball = SmallFireball(level, boss, direction)
                    fireball.setPos(from.x + direction.x, from.y, from.z + direction.z)
                    level.addFreshEntity(fireball)
                }
                level.playSound(null, boss.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.5f, 0.7f)
            }
            LairTheme.SUNKEN_SHRINE -> {
                // The tide drags its prey in close
                for (player in players) {
                    if (player.distanceTo(boss) > 16) continue
                    val pull = boss.position().subtract(player.position()).normalize().scale(1.1)
                    player.push(pull.x, 0.4, pull.z)
                    player.needsSync = true
                    player.addEffect(MobEffectInstance(MobEffects.MINING_FATIGUE, 20 * 6, 1))
                }
                summon(level, boss, Identifier.withDefaultNamespace("drowned"))
                level.sendParticles(ParticleTypes.SPLASH, boss.x, boss.y + 1.0, boss.z, 60, 3.0, 0.5, 3.0, 0.1)
                level.playSound(null, boss.blockPosition(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.8f, 1.2f)
            }
            LairTheme.RIME_VAULT -> {
                // A frost nova: everyone close freezes up
                for (player in players) {
                    if (player.distanceTo(boss) > 10) continue
                    player.ticksFrozen = player.ticksRequiredToFreeze + 60
                    player.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 20 * 4, 2))
                }
                level.sendParticles(ParticleTypes.SNOWFLAKE, boss.x, boss.y + 1.0, boss.z, 80, 4.0, 1.0, 4.0, 0.05)
                level.playSound(null, boss.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5f, 0.5f)
            }
        }
    }

    /** One more minion, a few blocks from the boss. */
    fun summon(level: ServerLevel, boss: Mob, type: Identifier): Mob? {
        val mob = BuiltInRegistries.ENTITY_TYPE.getValue(type).create(level, EntitySpawnReason.MOB_SUMMONED) as? Mob ?: return null
        val angle = level.random.nextDouble() * Math.PI * 2
        val spot = Vec3(boss.x + cos(angle) * 2.5, boss.y, boss.z + sin(angle) * 2.5)
        mob.snapTo(spot.x, spot.y, spot.z, level.random.nextFloat() * 360f, 0f)
        // Illagers summoned as anything but an event can roll a raid captain (banner, Ominous Bottle)
        val reason = if (mob is PatrollingMonster) EntitySpawnReason.EVENT else EntitySpawnReason.MOB_SUMMONED
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spot)), reason, null)
        mob.setPersistenceRequired()
        boss.target?.let { mob.target = it }
        level.addFreshEntity(mob)
        level.sendParticles(ParticleTypes.POOF, spot.x, spot.y + 0.5, spot.z, 8, 0.3, 0.3, 0.3, 0.02)
        return mob
    }
}
