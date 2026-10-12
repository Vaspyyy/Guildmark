package io.github.vaspyyy.guildmark.client.model

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
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
import kotlin.math.abs
import kotlin.math.max

/** What the enemy models need beyond a humanoid's pose. */
class EnemyRenderState : HumanoidRenderState() {
    var guarding = false
    var phaseTwo = false
    /** The attack or held pose to show, if any (kept here: render data set while extracting is cleared after). */
    var frame: CombatAnimator.Frame? = null
}

/**
 * A soldier of the fallen army: a dented kettle helm, mail under a torn tabard, and arrows still in its
 * back. Its sword and shield are real items, drawn in its hands. Geometry comes from
 * art/scripts/hollow_soldier.py; edit the model there.
 */
class HollowSoldierModel(root: ModelPart) : HumanoidModel<EnemyRenderState>(root) {
    private val tabard: ModelPart = body.getChild("tabard")

    override fun setupAnim(state: EnemyRenderState) {
        super.setupAnim(state)
        CombatAnimator.pose(this, state, state.frame)
        // The tabard hangs straight as the body bends, and kicks out with the stride
        tabard.xRot = -body.xRot * 0.8f - max(abs(rightLeg.xRot), abs(leftLeg.xRot)) * 0.5f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hollow_soldier"), "main")

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f).texOffs(48, 16).addBox(-4.5f, -9f, -4.5f, 9f, 3f, 9f).texOffs(100, 16).addBox(-3.5f, -10f, -3.5f, 7f, 1f, 7f).texOffs(0, 16).addBox(-6f, -6f, -6f, 12f, 1f, 12f), PartPose.offset(0f, 0f, 0f))
            head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.offset(0f, 0f, 0f))
            val body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(32, 0).addBox(-4f, 0f, -2f, 8f, 12f, 4f).texOffs(86, 29).addBox(-4f, 9f, -2f, 8f, 1f, 4f, CubeDeformation(0.35f)), PartPose.offset(0f, 0f, 0f))
            body.addOrReplaceChild("tabard", CubeListBuilder.create().texOffs(84, 16).addBox(-3.5f, 0f, -1f, 7f, 9f, 1f), PartPose.offset(0f, 10f, -2.2f))
            body.addOrReplaceChild("arrow", CubeListBuilder.create().texOffs(40, 29).addBox(-0.5f, -0.5f, -1f, 1f, 1f, 6f), PartPose.offsetAndRotation(1.5f, 3.5f, 2f, 0.489f, 0.314f, 0f))
            body.addOrReplaceChild("arrow_stub", CubeListBuilder.create().texOffs(110, 29).addBox(-0.5f, -0.5f, -1f, 1f, 1f, 4f), PartPose.offsetAndRotation(-2.5f, 7f, 2f, 0.175f, -0.279f, 0f))
            root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(88, 0).addBox(-2.5f, -2f, -1.5f, 3f, 12f, 3f).texOffs(0, 29).addBox(-3.5f, -2.5f, -2.5f, 5f, 3f, 5f), PartPose.offset(-5f, 2f, 0f))
            root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(100, 0).addBox(-0.5f, -2f, -1.5f, 3f, 12f, 3f).texOffs(20, 29).addBox(-1.5f, -2.5f, -2.5f, 5f, 3f, 5f), PartPose.offset(5f, 2f, 0f))
            root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(56, 0).addBox(-2.1f, 0f, -2f, 4f, 12f, 4f).texOffs(54, 29).addBox(-2.1f, 9f, -2f, 4f, 3f, 4f, CubeDeformation(0.25f)), PartPose.offset(-1.9f, 12f, 0f))
            root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(72, 0).addBox(-1.9f, 0f, -2f, 4f, 12f, 4f).texOffs(70, 29).addBox(-1.9f, 9f, -2f, 4f, 3f, 4f, CubeDeformation(0.3f)), PartPose.offset(1.9f, 12f, 0f))
            return LayerDefinition.create(mesh, 128, 64)
        }
    }
}

/**
 * Halvard, the Fallen Warden: battered plate with tarnished gold, a horned great helm, a long cloak and a
 * greatsword that's part of the model. Drawn half again a man's size. Geometry comes from
 * art/scripts/fallen_warden.py; edit the model there.
 */
class FallenWardenModel(root: ModelPart) : HumanoidModel<EnemyRenderState>(root) {
    private val cloak: ModelPart = body.getChild("cloak")
    private val greatsword: ModelPart = rightArm.getChild("greatsword")

    override fun setupAnim(state: EnemyRenderState) {
        super.setupAnim(state)
        // A heavy, measured walk: the arms swing less than a man's, the sword arm held a little forward
        rightArm.xRot = rightArm.xRot * 0.4f - 0.25f
        leftArm.xRot *= 0.6f
        CombatAnimator.pose(this, state, state.frame)
        // At rest the blade rides low before him; the clips hold it square to the fist
        val clip = state.frame?.weight ?: 0f
        greatsword.xRot = Mth.lerp(clip, -0.97f, -1.571f)
        // The cloak hangs from his shoulders whichever way he bends, and trails as he walks
        cloak.xRot = max(0.105f + state.walkAnimationSpeed * 0.6f, -body.xRot + 0.06f) + Mth.sin(state.ageInTicks * 0.08) * 0.03f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "fallen_warden"), "main")

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(34, 0).addBox(-4.5f, -9f, -4.5f, 9f, 9f, 9f).texOffs(64, 40).addBox(-3.5f, -10f, -3.5f, 7f, 1f, 7f).texOffs(90, 57).addBox(-4.5f, -6f, -5.5f, 9f, 1f, 1f).texOffs(98, 49).addBox(-4.5f, -4f, -5.5f, 9f, 4f, 1f), PartPose.offset(0f, 0f, 0f))
            head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.offset(0f, 0f, 0f))
            head.addOrReplaceChild("horn_r", CubeListBuilder.create().texOffs(74, 49).addBox(-1f, -1f, 0f, 2f, 2f, 4f).texOffs(52, 57).addBox(-0.5f, -0.5f, 4f, 1f, 1f, 3f), PartPose.offsetAndRotation(-4.5f, -7f, 0.5f, 0.698f, -0.611f, 0f))
            head.addOrReplaceChild("horn_l", CubeListBuilder.create().texOffs(86, 49).addBox(-1f, -1f, 0f, 2f, 2f, 4f), PartPose.offsetAndRotation(4.5f, -7f, 0.5f, 0.698f, 0.611f, 0f))
            val body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 24).addBox(-4f, 0f, -2f, 8f, 8f, 4f, CubeDeformation(0.45f)).texOffs(92, 40).addBox(-4f, 8f, -2f, 8f, 4f, 4f, CubeDeformation(0.75f)), PartPose.offset(0f, 0f, 0f))
            body.addOrReplaceChild("cloak", CubeListBuilder.create().texOffs(0, 0).addBox(-6f, 0f, 0f, 12f, 23f, 1f).texOffs(0, 57).addBox(-4f, -1.5f, -0.4f, 8f, 3f, 2f), PartPose.offsetAndRotation(0f, 0.5f, 2.5f, 0.105f, 0f, 0f))
            val right_arm = root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(70, 0).addBox(-3f, -2f, -2f, 4f, 12f, 4f).texOffs(40, 24).addBox(-3f, 5f, -2f, 4f, 5f, 4f, CubeDeformation(0.42f)), PartPose.offset(-5f, 2f, 0f))
            right_arm.addOrReplaceChild("r_pauldron", CubeListBuilder.create().texOffs(20, 57).addBox(-3.5f, -3.5f, -2f, 4f, 1f, 4f).texOffs(56, 24).addBox(-4.5f, -2.5f, -3f, 6f, 3f, 6f).texOffs(0, 49).addBox(-4.5f, 0.5f, -3f, 6f, 2f, 6f, CubeDeformation(0.35f)), PartPose.offsetAndRotation(-1f, -2f, 0f, 0f, 0f, -0.175f))
            right_arm.addOrReplaceChild("greatsword", CubeListBuilder.create().texOffs(60, 57).addBox(-2f, -5f, -2f, 2f, 2f, 2f).texOffs(66, 49).addBox(-2f, -3f, -2f, 2f, 5f, 2f).texOffs(24, 49).addBox(-2f, 2f, -4.5f, 2f, 1f, 7f).texOffs(26, 0).addBox(-1.5f, 3f, -2.5f, 1f, 20f, 3f).texOffs(68, 57).addBox(-1.5f, 23f, -2f, 1f, 2f, 2f).texOffs(110, 57).addBox(-1.5f, 25f, -1.5f, 1f, 1f, 1f), PartPose.offsetAndRotation(0f, 10f, 0f, -1.571f, 0f, 0f))
            val left_arm = root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(86, 0).addBox(-1f, -2f, -2f, 4f, 12f, 4f).texOffs(80, 24).addBox(-1f, 5f, -2f, 4f, 5f, 4f, CubeDeformation(0.42f)), PartPose.offset(5f, 2f, 0f))
            left_arm.addOrReplaceChild("l_pauldron", CubeListBuilder.create().texOffs(36, 57).addBox(-0.5f, -3.5f, -2f, 4f, 1f, 4f).texOffs(96, 24).addBox(-1.5f, -2.5f, -3f, 6f, 3f, 6f).texOffs(42, 49).addBox(-1.5f, 0.5f, -3f, 6f, 2f, 6f, CubeDeformation(0.35f)), PartPose.offsetAndRotation(1f, -2f, 0f, 0f, 0f, 0.175f))
            root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(102, 0).addBox(-2.1f, 0f, -2f, 4f, 12f, 4f).texOffs(0, 40).addBox(-2.1f, 0f, -2f, 4f, 5f, 4f, CubeDeformation(0.4f)).texOffs(74, 57).addBox(-1.6f, 4.5f, -3f, 3f, 2f, 1f).texOffs(16, 40).addBox(-2.1f, 7f, -2f, 4f, 5f, 4f, CubeDeformation(0.35f)).texOffs(114, 57).addBox(-1.6f, 11f, -3.4f, 3f, 1f, 1f), PartPose.offset(-1.9f, 12f, 0f))
            root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 24).addBox(-1.9f, 0f, -2f, 4f, 12f, 4f).texOffs(32, 40).addBox(-1.9f, 0f, -2f, 4f, 5f, 4f, CubeDeformation(0.45f)).texOffs(82, 57).addBox(-1.4f, 4.5f, -3.05f, 3f, 2f, 1f).texOffs(48, 40).addBox(-1.9f, 7f, -2f, 4f, 5f, 4f, CubeDeformation(0.4f)).texOffs(0, 62).addBox(-1.4f, 11f, -3.45f, 3f, 1f, 1f), PartPose.offset(1.9f, 12f, 0f))
            return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
