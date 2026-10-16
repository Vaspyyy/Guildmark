package io.github.vaspyyy.guildmark.sea

import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.entity.PartEntity

/**
 * One of the boxes along something too long or too oddly shaped for one box (a length of a Maelgrasp's arm, its
 * eye) that can be struck. Blows on it are passed to its owner by [struck], with the part it landed on. It's never
 * saved and never sent on its own: its owner places it every tick, on both sides.
 */
class SeaPart(
    val owner: Entity,
    width: Float,
    height: Float,
    private val struck: (SeaPart, ServerLevel, DamageSource, Float) -> Boolean,
) : PartEntity<Entity>(owner) {
    // Read by the entity's own constructor before this class's fields are set, hence the null check below
    private val size: EntityDimensions? = EntityDimensions.scalable(width, height)

    init {
        refreshDimensions()
    }

    /** Puts its middle at [at], remembering where it was for drawing in between. */
    fun placeAt(at: Vec3) {
        setOldPosAndRot()
        setPos(at.x, at.y - bbHeight / 2.0, at.z)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {}
    override fun readAdditionalSaveData(input: ValueInput) {}
    override fun addAdditionalSaveData(output: ValueOutput) {}

    override fun isPickable(): Boolean = !owner.isRemoved && owner.isAlive
    override fun getPickResult(): ItemStack? = null
    override fun shouldBeSaved(): Boolean = false
    override fun `is`(other: Entity): Boolean = this === other || owner === other
    override fun getDimensions(pose: Pose): EntityDimensions = size ?: super.getDimensions(pose)

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean =
        !isInvulnerableToBase(source) && struck(this, level, source, amount)
}
