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
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

/** What a rival model needs to know: whose guild, and whether it's their captain. */
class RivalRenderState : HumanoidRenderState() {
    var guild: String = ""
    var captain: Boolean = false
}

/**
 * Rival guild adventurers: a humanoid in a tabard of the guild's colours with the guild's own headgear
 * (the Iron Hounds' plumed helms, the Scarlet Ledger's berets and ledgers, the Gilded Stag's antlered
 * circlets, the Ashen Crows' hoods and beak masks). Captains add a cape and pauldrons.
 * Geometry is generated with art/scripts/rival.py (art/models/rival_adventurer.bbmodel).
 */
class RivalModel(root: ModelPart) : HumanoidModel<RivalRenderState>(root) {
    private val pieces: Map<String, List<ModelPart>> = mapOf(
        "iron_hounds" to listOf(head.getChild("hound_helm")),
        "scarlet_ledger" to listOf(head.getChild("ledger_cap"), body.getChild("ledger_book")),
        "gilded_stag" to listOf(head.getChild("stag_crown")),
        "ashen_crows" to listOf(head.getChild("crow_hood")),
    )
    private val cape: ModelPart = body.getChild("cape")
    private val pauldrons: List<ModelPart> = listOf(rightArm.getChild("r_paul"), leftArm.getChild("l_paul"))

    override fun setupAnim(state: RivalRenderState) {
        super.setupAnim(state)
        for ((guild, parts) in pieces) for (part in parts) part.visible = guild == state.guild
        hat.visible = false
        cape.visible = state.captain
        for (part in pauldrons) part.visible = state.captain
        cape.xRot = 0.1f + state.walkAnimationSpeed * 0.7f + Mth.sin(state.ageInTicks * 0.09) * 0.04f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "rival_adventurer"), "main")

        @Suppress("LocalVariableName")
        fun createBodyLayer(): LayerDefinition {
        val mesh = MeshDefinition()
        val root = mesh.root
        val head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(94, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(0, 21).addBox(-4f, -8f, -4f, 8f, 8f, 8f, CubeDeformation(0.5f)), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("hound_helm", CubeListBuilder.create().texOffs(22, 0).addBox(-4.5f, -8.5f, -4.5f, 9f, 9f, 9f).texOffs(16, 37).addBox(-0.5f, -11f, -3.5f, 1f, 3f, 8f), PartPose.offset(0f, 0f, 0f))
        val ledger_cap = head.addOrReplaceChild("ledger_cap", CubeListBuilder.create().texOffs(34, 37).addBox(-4.5f, -9.5f, -4.5f, 9f, 2f, 9f).texOffs(70, 37).addBox(-5f, -8f, -5f, 10f, 1f, 10f), PartPose.offset(0f, 0f, 0f))
        ledger_cap.addOrReplaceChild("quill", CubeListBuilder.create().texOffs(84, 52).addBox(0f, -5f, 0f, 1f, 5f, 1f), PartPose.offsetAndRotation(3.5f, -9f, 1f, 0f, 0f, 0.436f))
        val stag_crown = head.addOrReplaceChild("stag_crown", CubeListBuilder.create().texOffs(0, 52).addBox(-4.5f, -7f, -4.5f, 9f, 1f, 9f), PartPose.offset(0f, 0f, 0f))
        stag_crown.addOrReplaceChild("antler_r", CubeListBuilder.create().texOffs(76, 52).addBox(-0.5f, -6f, -0.5f, 1f, 6f, 1f).texOffs(114, 52).addBox(-3f, -5f, -0.5f, 3f, 1f, 1f).texOffs(106, 52).addBox(-2f, -8f, -0.5f, 1f, 2f, 1f), PartPose.offsetAndRotation(-3f, -7f, 0f, 0f, 0f, -0.436f))
        stag_crown.addOrReplaceChild("antler_l", CubeListBuilder.create().texOffs(80, 52).addBox(-0.5f, -6f, -0.5f, 1f, 6f, 1f).texOffs(0, 62).addBox(0f, -5f, -0.5f, 3f, 1f, 1f).texOffs(110, 52).addBox(1f, -8f, -0.5f, 1f, 2f, 1f), PartPose.offsetAndRotation(3f, -7f, 0f, 0f, 0f, 0.436f))
        head.addOrReplaceChild("crow_hood", CubeListBuilder.create().texOffs(58, 0).addBox(-4.5f, -8.5f, -4.5f, 9f, 9f, 9f).texOffs(96, 52).addBox(-1f, -4f, -7.5f, 2f, 2f, 3f), PartPose.offset(0f, 0f, 0f))
        val body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(32, 21).addBox(-4f, 0f, -2f, 8f, 12f, 4f), PartPose.offset(0f, 0f, 0f))
        body.addOrReplaceChild("tabard", CubeListBuilder.create().texOffs(0, 37).addBox(-3.5f, 1f, -3f, 7f, 14f, 1f), PartPose.offset(0f, 0f, 0f))
        body.addOrReplaceChild("ledger_book", CubeListBuilder.create().texOffs(88, 52).addBox(4f, 8f, -1.5f, 1f, 3f, 3f), PartPose.offset(0f, 0f, 0f))
        body.addOrReplaceChild("cape", CubeListBuilder.create().texOffs(0, 0).addBox(-5f, 0f, 0f, 10f, 20f, 1f), PartPose.offsetAndRotation(0f, 0f, 2f, 0.105f, 0f, 0f))
        val right_arm = root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(56, 21).addBox(-3f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(-5f, 2f, 0f))
        right_arm.addOrReplaceChild("r_paul", CubeListBuilder.create().texOffs(36, 52).addBox(-3.5f, -2.5f, -2.5f, 5f, 4f, 5f), PartPose.offset(0f, 0f, 0f))
        val left_arm = root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(72, 21).addBox(-1f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(5f, 2f, 0f))
        left_arm.addOrReplaceChild("l_paul", CubeListBuilder.create().texOffs(56, 52).addBox(-1.5f, -2.5f, -2.5f, 5f, 4f, 5f), PartPose.offset(0f, 0f, 0f))
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(88, 21).addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(-1.9f, 12f, 0f))
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(104, 21).addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(1.9f, 12f, 0f))
        return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
