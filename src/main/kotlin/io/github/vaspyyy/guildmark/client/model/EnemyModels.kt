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
    /** Something carried for one attack only, like the boulder a hill troll throws. */
    var holding = false
    /** Which of a family of looks to show, like each land's Hollow (RegionHollowModel.MIRE and the rest). */
    var variant = 0
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
 * The wild lands' Hollows: the soldier's model with every land's pieces on it, each showing only its own: weed
 * hanging from the brim and the shoulder plates on the Mire Hollow, icicles in the same places on the Rime Hollow,
 * crystal through the hat, a shoulder, the back and an arm on the Glimmer Hollow (the Cinder Hollow has none).
 * Geometry comes from art/scripts/region_hollows.py (RegionHollowMesh); edit the model there.
 */
class RegionHollowModel(root: ModelPart) : HumanoidModel<EnemyRenderState>(root) {
    private val tabard: ModelPart = body.getChild("tabard")
    private val fringe: ModelPart = head.getChild("fringe")
    private val fringeCards = listOf("fringe_front", "fringe_right", "fringe_back", "fringe_left").map { fringe.getChild(it) }
    private val shoulderFringes = listOf(rightArm.getChild("fringe_r"), leftArm.getChild("fringe_l"))
    private val crystals = listOf(head.getChild("crown"), body.getChild("growth"), rightArm.getChild("shards_r"), leftArm.getChild("shards_l"))

    override fun setupAnim(state: EnemyRenderState) {
        super.setupAnim(state)
        CombatAnimator.pose(this, state, state.frame)
        val stride = max(abs(rightLeg.xRot), abs(leftLeg.xRot))
        tabard.xRot = -body.xRot * 0.8f - stride * 0.5f
        val hanging = state.variant == MIRE || state.variant == RIME
        fringe.visible = hanging
        for (part in shoulderFringes) part.visible = hanging
        if (hanging) {
            // Weed sways out as it walks and stirs as it stands; ice hangs stiff
            val sway = if (state.variant == MIRE) 0.05f + stride * 0.35f + Mth.sin(state.ageInTicks * 0.09) * 0.04f else 0f
            for (card in fringeCards) card.xRot = -sway
            for (part in shoulderFringes) part.xRot = -sway
        }
        for (part in crystals) part.visible = state.variant == GLIMMER
    }

    companion object {
        const val MIRE = 0
        const val CINDER = 1
        const val RIME = 2
        const val GLIMMER = 3
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "region_hollow"), "main")
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

/**
 * A hill troll: hunched and moss-backed, with tusks, a hide loincloth, a shackle still on one wrist and a
 * club cut from a tree. Drawn more than half again a man's size. Geometry comes from
 * art/scripts/hill_troll.py; edit the model there.
 */
class HillTrollModel(root: ModelPart) : HumanoidModel<EnemyRenderState>(root) {
    private val club: ModelPart = rightArm.getChild("club")
    private val boulder: ModelPart = leftArm.getChild("boulder")
    private val chain: ModelPart = leftArm.getChild("chain")
    private val loincloth: ModelPart = body.getChild("loincloth")
    private val loinclothBack: ModelPart = body.getChild("loincloth_back")

    override fun setupAnim(state: EnemyRenderState) {
        super.setupAnim(state)
        // A slow, rolling gait: short steps on short legs, a sway from side to side, the arms hanging loose
        val stride = Mth.cos(state.walkAnimationPos * 0.6662) * state.walkAnimationSpeed
        rightLeg.xRot *= 0.75f
        leftLeg.xRot *= 0.75f
        rightArm.xRot *= 0.3f
        leftArm.xRot *= 0.55f
        body.zRot = stride * 0.07f
        head.zRot = -stride * 0.04f
        // It breathes heavily, shoulders rising and falling
        val breath = Mth.sin(state.ageInTicks * 0.07) * 0.5f
        rightArm.y += breath
        leftArm.y += breath
        head.y += breath * 0.6f
        val swing = rightArm.xRot
        CombatAnimator.pose(this, state, state.frame)
        // At rest it drags its club behind it, fist turned back; the clips hold it square to the fist,
        // and it swings round the outside of the arm between the two
        val clip = state.frame?.weight ?: 0f
        club.xRot = Mth.lerp(clip, CLUB_REST + swing, -1.571f)
        club.yRot = Mth.lerp(clip, Mth.PI, 0f)
        boulder.visible = state.holding
        // The loincloth hangs straight as the body bends and kicks out with each step; the chain swings
        val legs = max(abs(rightLeg.xRot), abs(leftLeg.xRot))
        loincloth.xRot = -body.xRot * 0.85f - legs * 0.6f
        loinclothBack.xRot = -body.xRot * 0.85f + legs * 0.5f
        chain.xRot = -leftArm.xRot * 0.8f - body.xRot * 0.5f
        chain.zRot = -leftArm.zRot * 0.8f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hill_troll"), "main")
        /** The club's tilt at rest, fist turned back: trailing behind, its head on the ground. */
        private const val CLUB_REST = -1.31f

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(42, 23).addBox(-4f, -7f, -4f, 8f, 7f, 8f).texOffs(28, 64).addBox(-4.5f, -2f, -5f, 9f, 3f, 7f).texOffs(0, 84).addBox(-4.5f, -6f, -5f, 9f, 2f, 1f).texOffs(94, 75).addBox(-1.5f, -4.5f, -6.5f, 3f, 3f, 2f).texOffs(44, 75).addBox(-2.5f, -8f, -2.5f, 5f, 1f, 5f), PartPose.offset(0f, -5f, -3.5f))
            head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.offset(0f, 0f, 0f))
            head.addOrReplaceChild("tusk_r", CubeListBuilder.create().texOffs(104, 75).addBox(-0.5f, -2.5f, -0.5f, 1f, 3f, 1f), PartPose.offsetAndRotation(-3f, -2f, -4.4f, -0.14f, 0f, -0.175f))
            head.addOrReplaceChild("tusk_l", CubeListBuilder.create().texOffs(108, 75).addBox(-0.5f, -2.5f, -0.5f, 1f, 3f, 1f), PartPose.offsetAndRotation(3f, -2f, -4.4f, -0.14f, 0f, 0.175f))
            head.addOrReplaceChild("ear_r", CubeListBuilder.create().texOffs(20, 84).addBox(-4f, -1f, -0.5f, 4f, 2f, 1f), PartPose.offsetAndRotation(-4f, -4f, -0.5f, 0f, 0.436f, -0.524f))
            head.addOrReplaceChild("ear_l", CubeListBuilder.create().texOffs(30, 84).mirror().addBox(0f, -1f, -0.5f, 4f, 2f, 1f).mirror(false), PartPose.offsetAndRotation(4f, -4f, -0.5f, 0f, -0.436f, 0.524f))
            val body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(72, 0).addBox(-7f, -5f, -4f, 14f, 11f, 8f).texOffs(0, 23).addBox(-6f, 5f, -5f, 12f, 7f, 9f).texOffs(56, 39).addBox(-6.5f, 11f, -4f, 13f, 4f, 8f).texOffs(0, 52).addBox(-5f, -9f, -2f, 10f, 5f, 7f), PartPose.offset(0f, 0f, 0f))
            body.addOrReplaceChild("loincloth", CubeListBuilder.create().texOffs(28, 75).addBox(-3.5f, 0f, -0.5f, 7f, 6f, 1f), PartPose.offset(0f, 14f, -4.2f))
            body.addOrReplaceChild("loincloth_back", CubeListBuilder.create().texOffs(64, 75).addBox(-4f, 0f, -0.5f, 8f, 5f, 1f), PartPose.offset(0f, 14f, 4.2f))
            body.addOrReplaceChild("trophy", CubeListBuilder.create().texOffs(40, 84).addBox(-0.5f, 0f, -0.5f, 1f, 2f, 1f).texOffs(82, 75).addBox(-1.5f, 2f, -1.5f, 3f, 3f, 3f), PartPose.offsetAndRotation(5.8f, 13.5f, -3.6f, 0f, 0f, -0.105f))
            val right_arm = root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -2f, -3f, 6f, 17f, 6f).texOffs(86, 52).addBox(-4.5f, -3f, -3.5f, 7f, 4f, 7f, CubeDeformation(0.2f)).texOffs(0, 39).addBox(-4.5f, 14f, -3.5f, 7f, 6f, 7f), PartPose.offset(-9f, -2f, 0f))
            right_arm.addOrReplaceChild("club", CubeListBuilder.create().texOffs(34, 52).addBox(-1.5f, -3f, -1.5f, 3f, 9f, 3f).texOffs(48, 0).addBox(-3f, 6f, -3f, 6f, 14f, 6f).texOffs(52, 84).addBox(-4f, 10f, -0.5f, 1f, 1f, 1f).texOffs(56, 84).addBox(3f, 13f, 0.5f, 1f, 1f, 1f).texOffs(60, 84).addBox(-0.5f, 16f, -4f, 1f, 1f, 1f).texOffs(64, 84).addBox(0.5f, 9f, 3f, 1f, 1f, 1f).texOffs(112, 75).addBox(-1.5f, 18.5f, -1f, 3f, 2f, 2f), PartPose.offsetAndRotation(-1f, 18f, -0.5f, -1.571f, 0f, 0f))
            val left_arm = root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(24, 0).addBox(-2f, -2f, -3f, 6f, 17f, 6f).texOffs(0, 64).addBox(-2.5f, -3f, -3.5f, 7f, 4f, 7f, CubeDeformation(0.2f)).texOffs(28, 39).addBox(-2.5f, 14f, -3.5f, 7f, 6f, 7f).texOffs(0, 75).addBox(-2.5f, 11.5f, -3.5f, 7f, 2f, 7f, CubeDeformation(0.3f)), PartPose.offset(9f, -2f, 0f))
            left_arm.addOrReplaceChild("chain", CubeListBuilder.create().texOffs(44, 84).addBox(-0.5f, 0f, -0.5f, 1f, 2f, 1f).texOffs(48, 84).addBox(-0.5f, 2f, -0.5f, 1f, 2f, 1f), PartPose.offset(1f, 13.5f, -3.6f))
            left_arm.addOrReplaceChild("boulder", CubeListBuilder.create().texOffs(74, 23).addBox(-3.5f, -1f, -3.5f, 7f, 7f, 7f), PartPose.offset(1f, 19f, -0.5f))
            root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(46, 52).addBox(-2.5f, 0f, -2.5f, 5f, 7f, 5f).texOffs(60, 64).addBox(-3f, 6f, -4f, 6f, 3f, 7f), PartPose.offset(-3.4f, 15f, 0f))
            root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(66, 52).addBox(-2.5f, 0f, -2.5f, 5f, 7f, 5f).texOffs(86, 64).addBox(-3f, 6f, -4f, 6f, 3f, 7f), PartPose.offset(3.4f, 15f, 0f))
            return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
