package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.sqrt

/** Sits on top of an archer tower and shoots arrows at monsters near the village. */
class ArcherPostBlock(properties: Properties) : Block(properties), EntityBlock {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ArcherPostBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide()) return null
        return BlockEntityTicker { tickLevel, pos, _, _ -> if (tickLevel is ServerLevel) tick(tickLevel, pos) }
    }

    private companion object {
        const val RANGE = 28.0
        const val INTERVAL = 25L
        /** Blocks per tick; faster than a skeleton's 1.6 so the arc stays flat. */
        const val SPEED = 2.5
        /** Arrow gravity per tick, as in AbstractArrow. */
        const val GRAVITY = 0.05

        fun tick(level: ServerLevel, pos: BlockPos) {
            if ((level.gameTime + pos.asLong()) % INTERVAL != 0L) return
            val muzzle = Vec3(pos.x + 0.5, pos.y + 1.3, pos.z + 0.5)
            val target = level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos).inflate(RANGE)) {
                it.isAlive && it.type.category == MobCategory.MONSTER && it.distanceToSqr(muzzle) <= RANGE * RANGE
            }.filter { canSee(level, muzzle, it) }.minByOrNull { it.distanceToSqr(muzzle) } ?: return

            val arrow = Arrow(level, muzzle.x, muzzle.y, muzzle.z, ItemStack(Items.ARROW), null)
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED
            arrow.setBaseDamage(4.0)
            // Lead the target by its velocity, then aim above it by how far the arrow drops on the way
            val aim = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
            var flightTicks = sqrt(aim.distanceToSqr(muzzle)) / SPEED
            val led = aim.add(target.deltaMovement.x * flightTicks, 0.0, target.deltaMovement.z * flightTicks)
            flightTicks = sqrt(led.distanceToSqr(muzzle)) / SPEED
            val drop = 0.5 * GRAVITY * flightTicks * flightTicks
            arrow.shoot(led.x - muzzle.x, led.y - muzzle.y + drop, led.z - muzzle.z, SPEED.toFloat(), 1.0f)
            level.addFreshEntity(arrow)
            level.playSound(null, pos, SoundEvents.ARROW_SHOOT, SoundSource.BLOCKS, 1.0f, 1.0f)
        }

        fun canSee(level: ServerLevel, from: Vec3, target: LivingEntity): Boolean =
            level.clip(ClipContext(from, target.eyePosition, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).type == HitResult.Type.MISS
    }
}

class ArcherPostBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.ARCHER_POST.get(), pos, state)
