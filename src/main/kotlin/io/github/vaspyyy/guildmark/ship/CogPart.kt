package io.github.vaspyyy.guildmark.ship

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.entity.PartEntity

/**
 * One of the boxes along a cog's hull that can be clicked and struck: a cog is far longer than any one
 * box. Everything done to it is done to the ship. [forward] is how far forward of the ship's middle it
 * sits, in blocks; it's never saved and never sent on its own (the ship places it every tick).
 */
class CogPart(val cog: Cog, val forward: Double, width: Float, height: Float) : PartEntity<Cog>(cog) {
    // Read by the entity's own constructor before this class's fields are set, hence the null check below
    private val size: EntityDimensions? = EntityDimensions.scalable(width, height)

    init {
        refreshDimensions()
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {}
    override fun readAdditionalSaveData(input: ValueInput) {}
    override fun addAdditionalSaveData(output: ValueOutput) {}

    override fun isPickable(): Boolean = !cog.isRemoved
    override fun canRiderInteract(): Boolean = true
    override fun getPickResult(): ItemStack? = cog.pickResult
    override fun shouldBeSaved(): Boolean = false
    override fun `is`(other: Entity): Boolean = this === other || cog === other
    override fun getDimensions(pose: Pose): EntityDimensions = size ?: super.getDimensions(pose)

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean =
        !isInvulnerableToBase(source) && cog.hurtServer(level, source, amount)

    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult =
        cog.interactAt(player, hand, position().add(location))
}
