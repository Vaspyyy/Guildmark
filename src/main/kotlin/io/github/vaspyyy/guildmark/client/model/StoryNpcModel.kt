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

/** What a story character model needs to know: who it is. */
class StoryNpcRenderState : HumanoidRenderState() {
    var character: String = ""
}

/**
 * The named characters: a humanoid with each character's own pieces (Wren's hat and map satchel,
 * Tobin's beard and pauldrons, Ilsa's hood and robe, Maren's hood, cloak and quiver, Pip's beret and lute), showing only the
 * wearer's. Drawn translucent so Maren can be a ghost. Geometry is generated with art/models/story_npc.bbmodel.
 */
class StoryNpcModel(root: ModelPart) : HumanoidModel<StoryNpcRenderState>(root, RenderTypes::entityTranslucent) {
    private val pieces: Map<String, List<ModelPart>> = mapOf(
        "wren" to listOf(head.getChild("wren_hat"), body.getChild("wren_bag")),
        "tobin" to listOf(head.getChild("tobin_beard"), rightArm.getChild("tobin_rpaul"), leftArm.getChild("tobin_lpaul")),
        "ilsa" to listOf(head.getChild("ilsa_hood"), body.getChild("ilsa_robe")),
        "maren" to listOf(head.getChild("maren_hood"), body.getChild("maren_cloak"), body.getChild("maren_quiver")),
        "pip" to listOf(head.getChild("pip_cap"), body.getChild("pip_lute")),
    )
    private val cloak: ModelPart = body.getChild("maren_cloak")

    override fun setupAnim(state: StoryNpcRenderState) {
        super.setupAnim(state)
        for ((who, parts) in pieces) for (part in parts) part.visible = who == state.character
        cloak.xRot = 0.09f + state.walkAnimationSpeed * 0.6f + Mth.sin(state.ageInTicks * 0.1) * 0.04f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "story_npc"), "main")

        @Suppress("LocalVariableName")
        fun createBodyLayer(): LayerDefinition {
        val mesh = MeshDefinition()
        val root = mesh.root
        val head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 22).addBox(-4f, -8f, -4f, 8f, 8f, 8f), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(32, 22).addBox(-4f, -8f, -4f, 8f, 8f, 8f, CubeDeformation(0.5f)), PartPose.offset(0f, 0f, 0f))
        val wren_hat = head.addOrReplaceChild("wren_hat", CubeListBuilder.create().texOffs(32, 38).addBox(-6.5f, -8.5f, -6.5f, 13f, 1f, 13f).texOffs(84, 38).addBox(-4.5f, -11.5f, -4.5f, 9f, 3f, 9f), PartPose.offset(0f, 0f, 0f))
        wren_hat.addOrReplaceChild("feather", CubeListBuilder.create().texOffs(62, 66).addBox(0f, -4f, 0f, 1f, 5f, 1f), PartPose.offsetAndRotation(4.5f, -10f, 1f, 0f, 0f, 0.349f))
        head.addOrReplaceChild("tobin_beard", CubeListBuilder.create().texOffs(40, 66).addBox(-4f, -2f, -4.75f, 8f, 6f, 1f), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("ilsa_hood", CubeListBuilder.create().texOffs(22, 0).addBox(-4.5f, -8.5f, -4.5f, 9f, 9f, 9f), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("maren_hood", CubeListBuilder.create().texOffs(58, 0).addBox(-4.6f, -8.6f, -4.6f, 9f, 9f, 9f, CubeDeformation(0.1f)).texOffs(66, 66).addBox(-2f, -8f, 4.4f, 4f, 4f, 2f), PartPose.offset(0f, 0f, 0f))
        head.addOrReplaceChild("pip_cap", CubeListBuilder.create().texOffs(0, 54).addBox(-5f, -2f, -5f, 10f, 2f, 10f).texOffs(48, 54).addBox(-4.5f, -0.5f, -4.5f, 9f, 1f, 9f).texOffs(58, 66).addBox(3.5f, -7f, 1f, 1f, 6f, 1f), PartPose.offsetAndRotation(0f, -8f, 0f, 0f, 0f, -0.209f))
        val body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(64, 22).addBox(-4f, 0f, -2f, 8f, 12f, 4f), PartPose.offset(0f, 0f, 0f))
        body.addOrReplaceChild("wren_bag", CubeListBuilder.create().texOffs(88, 54).addBox(-3f, 3f, 2f, 6f, 6f, 2f).texOffs(78, 66).addBox(-4.5f, 1.5f, 2.5f, 9f, 2f, 2f), PartPose.offset(0f, 0f, 0f))
        body.addOrReplaceChild("ilsa_robe", CubeListBuilder.create().texOffs(94, 0).addBox(-4.5f, 10f, -2.5f, 9f, 12f, 5f), PartPose.offset(0f, 0f, 0f))
        body.addOrReplaceChild("maren_cloak", CubeListBuilder.create().texOffs(0, 0).addBox(-5f, 0f, 0f, 10f, 21f, 1f), PartPose.offsetAndRotation(0f, 0f, 2f, 0.087f, 0f, 0f))
        body.addOrReplaceChild("maren_quiver", CubeListBuilder.create().texOffs(40, 54).addBox(-1f, -1f, 0f, 2f, 10f, 2f).texOffs(100, 66).addBox(-1f, -3f, 0.25f, 2f, 2f, 1.5f), PartPose.offsetAndRotation(2.5f, 1f, 3.2f, 0f, 0f, 0.436f))
        body.addOrReplaceChild("pip_lute", CubeListBuilder.create().texOffs(104, 54).addBox(-2.5f, -2f, 0f, 5f, 6f, 2f).texOffs(84, 54).addBox(-0.5f, -10f, 0.5f, 1f, 8f, 1f).texOffs(108, 66).addBox(-1f, -12f, 0.25f, 2f, 2f, 1.5f), PartPose.offsetAndRotation(0f, 7f, 2.6f, 0f, 0f, -0.611f))
        val right_arm = root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(88, 22).addBox(-3f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(-5f, 2f, 0f))
        right_arm.addOrReplaceChild("tobin_rpaul", CubeListBuilder.create().texOffs(0, 66).addBox(-3.5f, -2.5f, -2.5f, 5f, 3f, 5f), PartPose.offset(0f, 0f, 0f))
        val left_arm = root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(104, 22).addBox(-1f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(5f, 2f, 0f))
        left_arm.addOrReplaceChild("tobin_lpaul", CubeListBuilder.create().texOffs(20, 66).addBox(-1.5f, -2.5f, -2.5f, 5f, 3f, 5f), PartPose.offset(0f, 0f, 0f))
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 38).addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(-1.9f, 12f, 0f))
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16, 38).addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(1.9f, 12f, 0f))
        return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
