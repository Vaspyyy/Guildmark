package io.github.vaspyyy.guildmark.lair

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.core.particles.BlockParticleOption
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
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-boss fight state: seconds until the next ability, whether it has been driven into a rage, and how
 * long its hall has stood empty (dungeon bosses reset after a while alone).
 */
class BossState(var cooldown: Int = 6, var enraged: Boolean = false, var idle: Int = 0)

/**
 * What lair bosses do besides hitting you. Every few seconds a boss uses its theme's ability; below half
 * health it flies into a rage once, calling its minions and speeding up.
 */
object BossAbilities {
    /** Called once a second for each awake boss, with the players in the fight and the hall it's fought in, if any. */
    fun tick(level: ServerLevel, boss: Mob, theme: LairTheme, rank: Int, players: List<Player>, state: BossState, hall: Box? = null) {
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
            LairTheme.OLD_MINE -> {
                // Strikes the props: the roof comes down in a rain of rubble round whoever it's after
                val at = target.blockPosition()
                level.sendParticles(BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState()), target.x, target.y + 4.0, target.z, 40, 2.0, 0.5, 2.0, 0.0)
                repeat(3 + rank / 2) {
                    val x = at.x + level.random.nextInt(5) - 2
                    val z = at.z + level.random.nextInt(5) - 2
                    // Up as far as the roof, but no more than six blocks
                    var y = at.y + 1
                    while (y < at.y + 6 && level.getBlockState(BlockPos(x, y + 1, z)).isAir) y++
                    val pos = BlockPos(x, y, z)
                    if (y < at.y + 3 || !level.getBlockState(pos).isAir) return@repeat
                    val rock = FallingBlockEntity.fall(level, pos, (if (level.random.nextBoolean()) Blocks.GRAVEL else Blocks.COBBLESTONE).defaultBlockState())
                    rock.setHurtsEntities(1.5f, 6)
                    // It shatters where it lands rather than piling up
                    rock.disableDrop()
                }
                level.playSound(null, boss.blockPosition(), SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 1.6f, 0.5f)
                level.playSound(null, at, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.4f, 0.6f)
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
                level.sendParticles(ParticleTypes.LARGE_SMOKE, boss.x, boss.y + 1.0, boss.z, 20, 0.3, 0.6, 0.3, 0.02)
                blink(level, boss, hall)
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
                    // Sent to the player's own client too, or the tide never drags them
                    player.syncVelocity = true
                    player.addEffect(MobEffectInstance(MobEffects.MINING_FATIGUE, 20 * 6, 1))
                }
                summon(level, boss, Identifier.withDefaultNamespace("drowned"))
                level.sendParticles(ParticleTypes.SPLASH, boss.x, boss.y + 1.0, boss.z, 60, 3.0, 0.5, 3.0, 0.1)
                level.playSound(null, boss.blockPosition(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.8f, 1.2f)
            }
            LairTheme.TURNCOAT_CAMP -> {
                // Harl fights like the shield-brother he was: guard up while his raiders are called in on the one
                // he's after, or a firepot flung at their feet
                if (level.random.nextBoolean()) {
                    boss.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 20 * 3, 1))
                    for (minion in level.getEntitiesOfClass(Mob::class.java, boss.boundingBox.inflate(16.0)) { it != boss && it is net.minecraft.world.entity.monster.illager.AbstractIllager }) {
                        minion.addEffect(MobEffectInstance(MobEffects.SPEED, 20 * 5, 0))
                        minion.target = target
                    }
                    level.playSound(null, boss.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1.4f, 0.7f)
                    level.playSound(null, boss.blockPosition(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.HOSTILE, 1.4f, 0.8f)
                } else {
                    val from = boss.eyePosition
                    val to = target.position().add(0.0, 0.3, 0.0)
                    for (i in 1..8) {
                        val k = i / 8.0
                        val p = from.lerp(to, k).add(0.0, sin(k * Math.PI) * 1.5, 0.0)
                        level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0)
                    }
                    level.sendParticles(ParticleTypes.FLAME, to.x, to.y, to.z, 30, 0.8, 0.2, 0.8, 0.04)
                    level.sendParticles(ParticleTypes.LARGE_SMOKE, to.x, to.y + 0.4, to.z, 8, 0.6, 0.3, 0.6, 0.02)
                    for (player in players) if (player.distanceToSqr(to) < 4.0) player.igniteForSeconds(4f)
                    level.playSound(null, target.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 1.4f, 0.8f)
                }
            }
            LairTheme.ASHE_ARCHIVE -> {
                // Corvin reads from a stolen page: loose pages whirl up round whoever he's after and their sight
                // goes dark, and he's gone between the shelves
                level.sendParticles(ParticleTypes.ENCHANT, target.x, target.y + 1.2, target.z, 60, 0.8, 0.8, 0.8, 0.6)
                target.addEffect(MobEffectInstance(MobEffects.DARKNESS, 20 * 4, 0))
                target.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 20 * 2, 0))
                level.sendParticles(ParticleTypes.SOUL, boss.x, boss.y + 1.0, boss.z, 16, 0.3, 0.6, 0.3, 0.02)
                blink(level, boss, hall)
                level.playSound(null, target.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.HOSTILE, 1.6f, 0.6f)
                level.playSound(null, boss.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE, 1.2f, 0.9f)
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

    /**
     * Up to five blocks away in a blink: somewhere clear on firm ground, in sight of where the boss stood
     * (never through a wall) and inside its [hall] if it has one. Stays put if there's nowhere like that.
     */
    private fun blink(level: ServerLevel, boss: Mob, hall: Box?) {
        repeat(12) {
            val angle = level.random.nextDouble() * Math.PI * 2
            val reach = 3.5 + level.random.nextDouble() * 2.0
            for (rise in intArrayOf(0, 1, -1)) {
                val to = Vec3(boss.x + cos(angle) * reach, boss.y + rise, boss.z + sin(angle) * reach)
                if (hall != null && !hall.contains(to.x, to.y, to.z)) continue
                val box = boss.boundingBox.move(to.subtract(boss.position()))
                if (!level.noCollision(boss, box) || level.containsAnyLiquid(box)) continue
                val floor = BlockPos.containing(to.x, to.y - 0.5, to.z)
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue
                val eye = Vec3(0.0, boss.eyeHeight.toDouble(), 0.0)
                val sight = ClipContext(boss.position().add(eye), to.add(eye), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, boss)
                if (level.clip(sight).type != HitResult.Type.MISS) continue
                boss.teleportTo(to.x, to.y, to.z)
                return
            }
        }
    }

    /** One more minion, a few blocks from the boss: somewhere it fits, or at the boss's side at a pinch. */
    fun summon(level: ServerLevel, boss: Mob, type: Identifier): Mob? {
        val mob = BuiltInRegistries.ENTITY_TYPE.getValue(type).create(level, EntitySpawnReason.MOB_SUMMONED) as? Mob ?: return null
        val yaw = level.random.nextFloat() * 360f
        var spot = boss.position()
        for (attempt in 0 until 8) {
            val angle = level.random.nextDouble() * Math.PI * 2
            val at = Vec3(boss.x + cos(angle) * 2.5, boss.y, boss.z + sin(angle) * 2.5)
            mob.snapTo(at.x, at.y, at.z, yaw, 0f)
            if (level.noCollision(mob)) {
                spot = at
                break
            }
        }
        mob.snapTo(spot.x, spot.y, spot.z, yaw, 0f)
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
