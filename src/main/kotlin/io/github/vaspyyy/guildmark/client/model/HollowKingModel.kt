package io.github.vaspyyy.guildmark.client.model

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

/**
 * The Hollow King: a crowned skeleton in royal robes and a cape, with a greatsword in his right hand.
 * Geometry is generated from art/models/hollow_king.bbmodel's source script; edit the model there.
 */
class HollowKingModel(root: ModelPart) : HumanoidModel<HumanoidRenderState>(root) {
    private val cape: ModelPart = body.getChild("cape")

    override fun setupAnim(state: HumanoidRenderState) {
        super.setupAnim(state)
        // The cape trails out behind him as he strides
        cape.xRot = 0.1f + state.walkAnimationSpeed * 0.7f + Mth.sin(state.ageInTicks * 0.08) * 0.03f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hollow_king"), "main")

        @Suppress("LocalVariableName")
        fun createBodyLayer(): LayerDefinition {
        val mesh = MeshDefinition()
        val root = mesh.root
        val head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(58, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f).texOffs(46, 34).addBox(-3f, -1f, -4.5f, 6f, 2f, 2f), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("crown", CubeListBuilder.create().texOffs(24, 22).addBox(-4.5f, -1f, -4.5f, 9f, 2f, 9f).texOffs(30, 34).addBox(-0.5f, -5f, -4.75f, 1f, 4f, 1f).texOffs(62, 34).addBox(-3.5f, -4f, -4.75f, 1f, 3f, 1f).texOffs(66, 34).addBox(2.5f, -4f, -4.75f, 1f, 3f, 1f).texOffs(70, 34).addBox(-4.75f, -4f, -0.5f, 1f, 3f, 1f).texOffs(74, 34).addBox(3.75f, -4f, -0.5f, 1f, 3f, 1f).texOffs(78, 34).addBox(-3f, -4f, 3.75f, 1f, 3f, 1f).texOffs(82, 34).addBox(2f, -4f, 3.75f, 1f, 3f, 1f), PartPose.offset(0f, -8f, 0f))
        val body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 22).addBox(-4f, 0f, -2f, 8f, 8f, 4f).texOffs(34, 34).addBox(-1f, 8f, -1f, 2f, 3f, 2f).texOffs(30, 0).addBox(-4.5f, 8f, -2.5f, 9f, 12f, 5f).texOffs(0, 34).addBox(-5f, -0.5f, -2.5f, 10f, 2f, 5f), PartPose.offset(0f, 0f, 0f))
        body.addOrReplaceChild("cape", CubeListBuilder.create().texOffs(0, 0).addBox(-5f, 0f, 0f, 10f, 21f, 1f), PartPose.offsetAndRotation(0f, 0.5f, 2.5f, 0.105f, 0f, 0f))
        val right_arm = root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(90, 0).addBox(-1f, -2f, -1f, 2f, 12f, 2f).texOffs(88, 22).addBox(-3f, -3f, -2.5f, 5f, 3f, 5f).texOffs(92, 34).addBox(-1f, -5f, -0.5f, 1f, 2f, 1f), PartPose.offset(-5f, 2f, 0f))
        right_arm.addOrReplaceChild("sword", CubeListBuilder.create().texOffs(42, 34).addBox(-0.5f, -2f, -0.5f, 1f, 4f, 1f).texOffs(96, 34).addBox(-1f, -3f, -1f, 2f, 1f, 2f).texOffs(104, 34).addBox(-3f, 2f, -1f, 6f, 1f, 2f).texOffs(22, 0).addBox(-0.5f, 3f, -1.5f, 1f, 16f, 3f).texOffs(86, 34).addBox(-0.5f, 19f, -1f, 1f, 2f, 2f), PartPose.offsetAndRotation(0f, 9.5f, 0f, -1.222f, 0f, 0f))
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(98, 0).addBox(-1f, -2f, -1f, 2f, 12f, 2f).texOffs(108, 22).addBox(-2f, -3f, -2.5f, 5f, 3f, 5f).texOffs(120, 34).addBox(0f, -5f, -0.5f, 1f, 2f, 1f), PartPose.offset(5f, 2f, 0f))
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(106, 0).addBox(-1f, 0f, -1f, 2f, 12f, 2f).texOffs(60, 22).addBox(-1.5f, 7f, -2f, 3f, 5f, 3.5f), PartPose.offset(-2f, 12f, 0f))
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(114, 0).addBox(-1f, 0f, -1f, 2f, 12f, 2f).texOffs(74, 22).addBox(-1.5f, 7f, -2f, 3f, 5f, 3.5f), PartPose.offset(2f, 12f, 0f))
        return LayerDefinition.create(mesh, 128, 64)
        }
    }
}
