package io.github.vaspyyy.guildmark.client.model

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.RigAnimator
import io.github.vaspyyy.guildmark.enemy.DuskRider
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import org.joml.Quaternionf

/** What the Dusk Rider's model needs to pose it. */
class DuskRiderRenderState : LivingEntityRenderState() {
    /** Blocks the steed has gone (its gaits play by distance) and how fast it's going, in blocks a tick. */
    var stride = 0f
    var gaitSpeed = 0f
    /** The attack it's seen doing, how far into it and how strongly it shows. */
    var attack: Clip? = null
    var attackTime = 0f
    var attackWeight = 0f
    /** The attack the current one cut short, fading out under it. */
    var cut: Clip? = null
    var cutTime = 0f
    var cutWeight = 0f
    /** Ticks into its death, or -1. */
    var dying = -1f
    /** Past half its health: cold fire in its steed's mane, tail and hooves. */
    var ignited = false
    /** How much of it is left as it fades at dawn: 1 whole, 0 gone. */
    var fade = 1f
}

/**
 * The Dusk Rider: a hollow knight in blackened plate on a black destrier, a halberd in one hand and a lantern
 * of cold light in the other. The steed walks and gallops by how far it has gone, blended by its speed; the
 * attacks and its death play over that; the lantern always hangs straight down from the fist. Geometry comes
 * from art/scripts/dusk_rider.py and the clips from art/scripts/dusk_rider_anims.py; edit them there.
 */
class DuskRiderModel(root: ModelPart) : EntityModel<DuskRiderRenderState>(root) {
    private val rig = RigAnimator(root, PARTS)
    private val flames: List<ModelPart> = root.createPartLookup().let { lookup -> FLAMES.map { lookup.apply(it)!! } }
    private val arm = Quaternionf()
    private val look = Quaternionf()
    private val swung = Quaternionf()

    override fun setupAnim(state: DuskRiderRenderState) {
        super.setupAnim(state)
        rig.begin()
        // Under everything, it breathes and shifts; over that its gaits, by how fast it's going
        Clips.get(IDLE)?.let { rig.layer(it, state.ageInTicks % it.length, 1f) }
        val galloping = smoothstep(0.15f, 0.24f, state.gaitSpeed)
        val walking = smoothstep(0.02f, 0.06f, state.gaitSpeed) * (1f - galloping)
        Clips.get(WALK)?.let { rig.layer(it, (state.stride / DuskRider.WALK_STRIDE * it.length) % it.length, walking) }
        Clips.get(GALLOP)?.let { rig.layer(it, (state.stride / DuskRider.GALLOP_STRIDE * it.length) % it.length, galloping) }
        state.cut?.let { rig.layer(it, state.cutTime, state.cutWeight) }
        state.attack?.let { rig.layer(it, state.attackTime, state.attackWeight) }
        var alive = 1f
        if (state.dying >= 0f) Clips.get(DEATH)?.let {
            val t = state.dying.coerceAtMost(it.length)
            alive = 1f - it.weight(t)
            rig.layer(it, t, it.weight(t))
        }

        // The knight looks where it's looking
        val yaw = Mth.clamp(state.yRot, -55f, 55f) * Mth.DEG_TO_RAD * alive
        val pitch = Mth.clamp(state.xRot, -25f, 35f) * Mth.DEG_TO_RAD * alive
        rig.rotation("rider_head").mul(look.rotationZYX(0f, yaw, pitch))

        // The lantern hangs from the fist whatever the arm does, swinging back as the steed goes
        val drag = Mth.clamp(state.gaitSpeed * 120f, 0f, 40f) + 5f * Mth.sin(state.ageInTicks * 0.13)
        val sway = 4f * Mth.sin(state.ageInTicks * 0.09)
        rig.world(ARM, arm).conjugate()
        val lantern = rig.rotation("lantern")
        swung.set(lantern).mul(look.rotationZYX(sway * Mth.DEG_TO_RAD, 0f, drag * Mth.DEG_TO_RAD))
        lantern.set(arm).mul(swung)
        rig.finish()

        // Cold fire once it's wounded past half, flickering
        for ((i, flame) in flames.withIndex()) {
            flame.visible = state.ignited
            val flicker = 1f + 0.12f * Mth.sin(state.ageInTicks * 0.9 + i * 1.7) + 0.06f * Mth.sin(state.ageInTicks * 2.3 + i)
            flame.xScale = 2f - flicker
            flame.yScale = flicker
            flame.zScale = 2f - flicker
        }
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val k = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dusk_rider"), "main")
        private const val IDLE = "dusk_rider/idle"
        private const val WALK = "dusk_rider/walk"
        private const val GALLOP = "dusk_rider/gallop"
        private const val DEATH = "dusk_rider/death"
        /** The parts the clips move. */
        private val PARTS = listOf(
            "steed", "neck", "horse_head", "mane", "tail", "tail_end", "rider", "rider_body", "rider_head", "plume", "cloak",
            "rider_right_arm", "halberd", "rider_left_arm", "lantern", "rider_right_leg", "r_shin", "rider_left_leg", "l_shin",
            "fr_leg", "fr_shin", "fl_leg", "fl_shin", "hr_leg", "hr_shin", "hl_leg", "hl_shin",
        )
        /** From the steed down to the lantern's fist. */
        private val ARM = listOf("steed", "rider", "rider_body", "rider_left_arm")
        private val FLAMES = listOf("mane_fire", "tail_fire", "fr_fire", "fl_fire", "hr_fire", "hl_fire")

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val steed = root.addOrReplaceChild("steed", CubeListBuilder.create().texOffs(74, 0).addBox(-5f, -6f, -11f, 10f, 10f, 22f).texOffs(16, 55).addBox(-4.5f, -5f, -13.5f, 9f, 9f, 3f).texOffs(156, 37).addBox(-5.5f, -6.5f, 5.5f, 11f, 7f, 6.5f).texOffs(80, 55).addBox(-4f, -8f, -3f, 8f, 2f, 9f).texOffs(90, 86).addBox(-3.5f, -10f, 5f, 7f, 2f, 1.5f).texOffs(146, 86).addBox(-2f, -9.5f, -4f, 4f, 1.5f, 1f).texOffs(178, 0).addBox(-6f, -5.5f, -12f, 1f, 12f, 9f).texOffs(198, 0).addBox(5f, -5.5f, -12f, 1f, 12f, 9f).texOffs(138, 0).addBox(-6.3f, -6.3f, 4f, 1f, 13f, 9f).texOffs(158, 0).addBox(5.3f, -6.3f, 4f, 1f, 13f, 9f).texOffs(200, 55).addBox(-5.5f, -5.5f, -14f, 11f, 9f, 1f), PartPose.offset(0f, 7f, 0f))
            val neck = steed.addOrReplaceChild("neck", CubeListBuilder.create().texOffs(218, 0).addBox(-2.5f, -13f, -3.5f, 5f, 14f, 7f), PartPose.offsetAndRotation(0f, -4f, -9f, 0.349f, 0f, 0f))
            val horse_head = neck.addOrReplaceChild("horse_head", CubeListBuilder.create().texOffs(40, 55).addBox(-3f, -3f, -6f, 6f, 5f, 7f).texOffs(160, 68).addBox(-2.5f, -2f, -11f, 5f, 4f, 5f).texOffs(114, 55).addBox(-3f, -3.4f, -11.3f, 6f, 2f, 9f, CubeDeformation(0.1f)), PartPose.offsetAndRotation(0f, -12.5f, -1.5f, 0.489f, 0f, 0f))
            horse_head.addOrReplaceChild("ear_r", CubeListBuilder.create().texOffs(32, 86).addBox(-0.5f, -3f, -0.5f, 1f, 3f, 2f), PartPose.offsetAndRotation(-2f, -3f, 0f, -0.175f, 0f, -0.262f))
            horse_head.addOrReplaceChild("ear_l", CubeListBuilder.create().texOffs(38, 86).addBox(-0.5f, -3f, -0.5f, 1f, 3f, 2f), PartPose.offsetAndRotation(2f, -3f, 0f, -0.175f, 0f, 0.262f))
            horse_head.addOrReplaceChild("horn", CubeListBuilder.create().texOffs(108, 86).addBox(-0.5f, -3f, -0.5f, 1f, 3f, 1f), PartPose.offsetAndRotation(0f, -3.6f, -7f, -0.611f, 0f, 0f))
            horse_head.addOrReplaceChild("forelock", CubeListBuilder.create().texOffs(44, 86).addBox(-1.5f, -0.5f, -4f, 3f, 1f, 4f), PartPose.offsetAndRotation(0f, -3f, -1f, -0.349f, 0f, 0f))
            neck.addOrReplaceChild("mane", CubeListBuilder.create().texOffs(0, 37).addBox(-1f, 0f, 0f, 2f, 15f, 2.5f), PartPose.offset(0f, -14f, 2.5f))
            neck.addOrReplaceChild("mane_fire", CubeListBuilder.create().texOffs(242, 0).addBox(-1.5f, -2f, -0.5f, 3f, 17f, 4f), PartPose.offset(0f, -14f, 2.5f))
            val tail = steed.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(224, 55).addBox(-1.5f, -0.5f, -1f, 3f, 7f, 3f), PartPose.offsetAndRotation(0f, -5f, 11.5f, 0.96f, 0f, 0f))
            tail.addOrReplaceChild("tail_end", CubeListBuilder.create().texOffs(66, 55).addBox(-2f, 0f, -1.5f, 4f, 9f, 3f), PartPose.offsetAndRotation(0f, 6.5f, 0f, -0.524f, 0f, 0f))
            tail.addOrReplaceChild("tail_fire", CubeListBuilder.create().texOffs(192, 37).addBox(-2f, -0.5f, -1.5f, 4f, 9f, 4f), PartPose.offset(0f, 0f, 0f))
            val rider = steed.addOrReplaceChild("rider", CubeListBuilder.create(), PartPose.offset(0f, -8f, 1f))
            val rider_body = rider.addOrReplaceChild("rider_body", CubeListBuilder.create().texOffs(10, 37).addBox(-4f, -12f, -2f, 8f, 12f, 4f, CubeDeformation(0.4f)).texOffs(68, 78).addBox(-4f, -3f, -2f, 8f, 3f, 4f, CubeDeformation(0.8f)), PartPose.offset(0f, 0f, 0f))
            val rider_head = rider_body.addOrReplaceChild("rider_head", CubeListBuilder.create().texOffs(34, 37).addBox(-4f, -8f, -4f, 8f, 8f, 8f).texOffs(236, 55).addBox(-0.5f, -10f, -3.5f, 1f, 2f, 8f), PartPose.offset(0f, -12f, 0f))
            rider_head.addOrReplaceChild("plume", CubeListBuilder.create().texOffs(144, 55).addBox(-1.5f, -0.5f, -0.5f, 3f, 1.5f, 9f), PartPose.offsetAndRotation(0f, -9.5f, 3.5f, -0.663f, 0f, 0f))
            val rider_right_arm = rider_body.addOrReplaceChild("rider_right_arm", CubeListBuilder.create().texOffs(66, 37).addBox(-3f, -2f, -2f, 4f, 12f, 4f).texOffs(196, 68).addBox(-3f, 6f, -2f, 4f, 4f, 4f, CubeDeformation(0.4f)).texOffs(0, 68).addBox(-4.5f, -3f, -3f, 6f, 4f, 6f), PartPose.offsetAndRotation(-5f, -10f, 0f, -0.314f, 0f, 0.14f))
            rider_right_arm.addOrReplaceChild("halberd", CubeListBuilder.create().texOffs(0, 0).addBox(-0.75f, -0.75f, -27f, 1.5f, 1.5f, 35f).texOffs(58, 86).addBox(-1f, -1f, -29f, 2f, 2f, 3f).texOffs(208, 37).addBox(-0.5f, 1f, -33f, 1f, 5f, 8f).texOffs(68, 86).addBox(-0.5f, -4f, -31f, 1f, 3f, 2f).texOffs(92, 78).addBox(-0.5f, -0.5f, -38f, 1f, 1f, 6f).texOffs(112, 86).addBox(-1f, -1f, 7f, 2f, 2f, 1.5f), PartPose.offsetAndRotation(-1f, 10f, 0f, -1.571f, 0f, 0f))
            val rider_left_arm = rider_body.addOrReplaceChild("rider_left_arm", CubeListBuilder.create().texOffs(82, 37).addBox(-1f, -2f, -2f, 4f, 12f, 4f).texOffs(212, 68).addBox(-1f, 6f, -2f, 4f, 4f, 4f, CubeDeformation(0.4f)).texOffs(24, 68).addBox(-1.5f, -3f, -3f, 6f, 4f, 6f), PartPose.offsetAndRotation(5f, -10f, 0f, -0.524f, 0f, -0.105f))
            val lantern = rider_left_arm.addOrReplaceChild("lantern", CubeListBuilder.create().texOffs(120, 86).addBox(-0.5f, 0f, -0.5f, 1f, 3f, 1f), PartPose.offset(1f, 10f, 0f))
            lantern.addOrReplaceChild("lamp", CubeListBuilder.create().texOffs(180, 68).addBox(-2f, 1f, -2f, 4f, 5f, 4f).texOffs(198, 78).addBox(-2.5f, 0f, -2.5f, 5f, 1f, 5f).texOffs(106, 78).addBox(-1.5f, 1.5f, -1.5f, 3f, 4f, 3f).texOffs(74, 86).addBox(-2f, 6f, -2f, 4f, 1f, 4f), PartPose.offset(0f, 3f, 0f))
            rider_body.addOrReplaceChild("cloak", CubeListBuilder.create().texOffs(98, 37).addBox(-5f, 0f, 0f, 10f, 15f, 1f).texOffs(124, 86).addBox(-4.5f, -1f, -0.5f, 9f, 2f, 2f), PartPose.offsetAndRotation(0f, -11.5f, 2.4f, 0.628f, 0f, 0f))
            val rider_right_leg = rider.addOrReplaceChild("rider_right_leg", CubeListBuilder.create().texOffs(168, 55).addBox(-2f, 0f, -2f, 4f, 7f, 4f, CubeDeformation(0.2f)), PartPose.offsetAndRotation(-2.4f, -1f, 0f, -0.593f, 0f, 0.454f))
            rider_right_leg.addOrReplaceChild("r_shin", CubeListBuilder.create().texOffs(48, 68).addBox(-2f, 0f, -2f, 4f, 6f, 4f, CubeDeformation(0.2f)).texOffs(228, 68).addBox(-2f, 5f, -4.5f, 4f, 1.5f, 6f), PartPose.offsetAndRotation(0f, 6.5f, 0f, 0.698f, 0f, 0f))
            val rider_left_leg = rider.addOrReplaceChild("rider_left_leg", CubeListBuilder.create().texOffs(184, 55).addBox(-2f, 0f, -2f, 4f, 7f, 4f, CubeDeformation(0.2f)), PartPose.offsetAndRotation(2.4f, -1f, 0f, -0.593f, 0f, -0.454f))
            rider_left_leg.addOrReplaceChild("l_shin", CubeListBuilder.create().texOffs(64, 68).addBox(-2f, 0f, -2f, 4f, 6f, 4f, CubeDeformation(0.2f)).texOffs(0, 78).addBox(-2f, 5f, -4.5f, 4f, 1.5f, 6f), PartPose.offsetAndRotation(0f, 6.5f, 0f, 0.698f, 0f, 0f))
            val fr_leg = steed.addOrReplaceChild("fr_leg", CubeListBuilder.create().texOffs(226, 37).addBox(-2f, -1f, -2f, 4f, 9f, 4f), PartPose.offset(-3f, 3f, -8f))
            val fr_shin = fr_leg.addOrReplaceChild("fr_shin", CubeListBuilder.create().texOffs(20, 78).addBox(-1.5f, 0f, -1.5f, 3f, 4.5f, 3f).texOffs(218, 78).addBox(-2f, 4.5f, -2f, 4f, 1.5f, 4f).texOffs(118, 78).addBox(-2.5f, 3f, -2.5f, 5f, 2f, 5f), PartPose.offset(0f, 8f, 0f))
            fr_shin.addOrReplaceChild("fr_fire", CubeListBuilder.create().texOffs(80, 68).addBox(-2.5f, -4f, -2.5f, 5f, 5f, 5f), PartPose.offset(0f, 4f, 0f))
            val fl_leg = steed.addOrReplaceChild("fl_leg", CubeListBuilder.create().texOffs(0, 55).addBox(-2f, -1f, -2f, 4f, 9f, 4f), PartPose.offset(3f, 3f, -8f))
            val fl_shin = fl_leg.addOrReplaceChild("fl_shin", CubeListBuilder.create().texOffs(32, 78).addBox(-1.5f, 0f, -1.5f, 3f, 4.5f, 3f).texOffs(234, 78).addBox(-2f, 4.5f, -2f, 4f, 1.5f, 4f).texOffs(138, 78).addBox(-2.5f, 3f, -2.5f, 5f, 2f, 5f), PartPose.offset(0f, 8f, 0f))
            fl_shin.addOrReplaceChild("fl_fire", CubeListBuilder.create().texOffs(100, 68).addBox(-2.5f, -4f, -2.5f, 5f, 5f, 5f), PartPose.offset(0f, 4f, 0f))
            val hr_leg = steed.addOrReplaceChild("hr_leg", CubeListBuilder.create().texOffs(120, 37).addBox(-2f, -2f, -2.5f, 4f, 10f, 5f), PartPose.offset(-3f, 3f, 8f))
            val hr_shin = hr_leg.addOrReplaceChild("hr_shin", CubeListBuilder.create().texOffs(44, 78).addBox(-1.5f, 0f, -1.5f, 3f, 4.5f, 3f).texOffs(0, 86).addBox(-2f, 4.5f, -2f, 4f, 1.5f, 4f).texOffs(158, 78).addBox(-2.5f, 3f, -2.5f, 5f, 2f, 5f), PartPose.offset(0f, 8f, 0.5f))
            hr_shin.addOrReplaceChild("hr_fire", CubeListBuilder.create().texOffs(120, 68).addBox(-2.5f, -4f, -2.5f, 5f, 5f, 5f), PartPose.offset(0f, 4f, 0f))
            val hl_leg = steed.addOrReplaceChild("hl_leg", CubeListBuilder.create().texOffs(138, 37).addBox(-2f, -2f, -2.5f, 4f, 10f, 5f), PartPose.offset(3f, 3f, 8f))
            val hl_shin = hl_leg.addOrReplaceChild("hl_shin", CubeListBuilder.create().texOffs(56, 78).addBox(-1.5f, 0f, -1.5f, 3f, 4.5f, 3f).texOffs(16, 86).addBox(-2f, 4.5f, -2f, 4f, 1.5f, 4f).texOffs(178, 78).addBox(-2.5f, 3f, -2.5f, 5f, 2f, 5f), PartPose.offset(0f, 8f, 0.5f))
            hl_shin.addOrReplaceChild("hl_fire", CubeListBuilder.create().texOffs(140, 68).addBox(-2.5f, -4f, -2.5f, 5f, 5f, 5f), PartPose.offset(0f, 4f, 0f))
            return LayerDefinition.create(mesh, 256, 128)
        }
    }
}
