package io.github.vaspyyy.guildmark.client.dragon

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.RigAnimator
import io.github.vaspyyy.guildmark.dragon.Dragon
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import org.joml.Quaternionf

/** What the dragon's model needs to pose it. */
class DragonRenderState : LivingEntityRenderState() {
    /** Blocks it has walked (its walk plays by distance) and how fast it's walking, in blocks a tick. */
    var stride = 0f
    var gaitSpeed = 0f
    /** 0 on its feet, 1 on the wing; and on the wing, how much each of its flight loops shows, and how far ahead of its age they run. */
    var airBlend = 0f
    var airPhase = 0f
    var hover = 1f
    var flap = 0f
    var glide = 0f
    /** 0 awake, 1 curled up asleep on its nest. */
    var sleepBlend = 0f
    /** Degrees nose down and banked to its right as it flies: the renderer leans the whole of it by these. */
    var flightPitch = 0f
    var flightRoll = 0f
    /** The attack it's seen doing, how far into it and how strongly it shows. */
    var attack: Clip? = null
    var attackTime = 0f
    var attackWeight = 0f
    /** The attack the current one (or a stagger) cut short, fading out under it. */
    var cut: Clip? = null
    var cutTime = 0f
    var cutWeight = 0f
    /** Reeling with its stance broken: the stagger clip, stretched over however long the stagger lasts. */
    var stagger: Clip? = null
    var staggerTime = 0f
    var staggerWeight = 0f
    /** Ticks into its death, or -1. */
    var dying = -1f
    /** Degrees further down its head points as it breathes fire on the ground, to follow its aim. */
    var aimDown = 0f
    /** Eyelids down: asleep, waking, or dead. */
    var lidsShut = false
    /** How brightly its eyes burn (dimming as it dies), and how much the breath's and the rage's glow show. */
    var eyes = 1f
    var breathGlow = 0f
    var rage = 0f
}

/**
 * The dragon: a six-limbed wyrm of the high peaks. On foot it breathes and looks about, walks by how far it
 * has gone, or lies curled asleep; on the wing it hovers, beats its way along or glides, blended by its speed
 * and climb. Its attacks, a stagger or its death play over that, and between attacks its neck turns its head
 * toward whatever it's watching. Its wings' membranes are flat, painted on both faces, so it's drawn with
 * back faces culled: each side shows its own. Geometry comes from art/scripts/dragon.py and the clips from
 * art/scripts/dragon_anims.py; edit them there.
 */
class DragonModel(root: ModelPart) : EntityModel<DragonRenderState>(root, RenderTypes::entityCutoutCull) {
    private val rig = RigAnimator(root, PARTS)
    private val lids: ModelPart = root.createPartLookup().apply("lids")!!
    private val turn = Quaternionf()
    /** The state it was last posed for: the body and each of its glows ask for the same pose in a frame. */
    private var posed: DragonRenderState? = null

    override fun setupAnim(state: DragonRenderState) {
        if (state === posed) return
        posed = state
        super.setupAnim(state)
        rig.begin()
        val air = state.airBlend
        // On its feet: breathing and looking about, walking by how far it has gone, or curled up asleep
        if (air < 1f) {
            Clips.get(IDLE)?.let { rig.layer(it, state.ageInTicks % it.length, 1f) }
            val walking = Dragon.smoothstep(0.02f, 0.06f, state.gaitSpeed) * (1f - state.sleepBlend)
            Clips.get(WALK)?.let { rig.layer(it, (state.stride / Dragon.WALK_STRIDE * it.length) % it.length, walking) }
            Clips.get(SLEEP)?.let { rig.layer(it, state.ageInTicks % it.length, state.sleepBlend) }
        }
        // On the wing, over that as it takes off or comes down: each loop as much as its share, so the three
        // together show as strongly as it's in the air
        if (air > 0f) {
            val h = air * state.hover
            val f = air * state.flap
            val g = air * state.glide
            val time = state.ageInTicks + state.airPhase
            if (h > 0f) Clips.get(HOVER)?.let { rig.layer(it, time % it.length, h / (1f - f - g)) }
            if (f > 0f) Clips.get(FLY)?.let { rig.layer(it, time % it.length, f / (1f - g)) }
            if (g > 0f) Clips.get(GLIDE)?.let { rig.layer(it, time % it.length, g) }
        }
        state.cut?.let { rig.layer(it, state.cutTime, state.cutWeight) }
        state.attack?.let { rig.layer(it, state.attackTime, state.attackWeight) }
        state.stagger?.let { rig.layer(it, state.staggerTime, state.staggerWeight) }
        var alive = 1f
        if (state.dying >= 0f) Clips.get(DEATH)?.let {
            val t = state.dying.coerceAtMost(it.length)
            alive = 1f - it.weight(t)
            rig.layer(it, t, it.weight(t))
        }

        // Between attacks its neck turns its head toward what it's watching, most of the turn low in the neck
        val free = alive * (1f - state.attackWeight) * (1f - state.cutWeight) * (1f - state.staggerWeight) * (1f - state.sleepBlend)
        if (free > 0f) {
            val yaw = Mth.clamp(state.yRot, -60f, 60f) * Mth.DEG_TO_RAD * free
            val pitch = Mth.clamp(state.xRot, -30f, 40f) * Mth.DEG_TO_RAD * free
            rig.rotation("neck1").premul(turn.rotationY(yaw * 0.45f))
            rig.rotation("neck2").premul(turn.rotationY(yaw * 0.3f))
            rig.rotation("neck3").premul(turn.rotationX(pitch * 0.4f))
            rig.rotation("head").premul(turn.rotationYXZ(yaw * 0.25f, pitch * 0.6f, 0f))
        }
        // Its fire goes where it aims, higher or lower than the clip's own stoop
        if (state.aimDown != 0f) {
            val down = state.aimDown * Mth.DEG_TO_RAD
            rig.rotation("neck3").premul(turn.rotationX(down * 0.4f))
            rig.rotation("head").premul(turn.rotationX(down * 0.6f))
        }
        rig.finish()
        lids.visible = state.lidsShut
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dragon"), "main")
        private const val IDLE = "${Dragon.ANIMATIONS}/idle"
        private const val WALK = "${Dragon.ANIMATIONS}/walk"
        private const val SLEEP = "${Dragon.ANIMATIONS}/sleep"
        private const val HOVER = "${Dragon.ANIMATIONS}/hover"
        private const val FLY = "${Dragon.ANIMATIONS}/fly"
        private const val GLIDE = "${Dragon.ANIMATIONS}/glide"
        private const val DEATH = "${Dragon.ANIMATIONS}/death"
        const val STAGGER = "${Dragon.ANIMATIONS}/stagger"

        /** Every part of it: the clips may move any of them. */
        private val PARTS = listOf(
            "body", "neck1", "neck2", "neck3", "head", "jaw", "jspike_r", "jspike_l", "jspike_r2", "jspike_l2", "lids",
            "horn_r", "horn_r2", "horn_r3", "horn_r4", "horn_l", "horn_l2", "horn_l3", "horn_l4", "chorn_r", "chorn_l",
            "brow_spike_r", "brow_spike_l", "spine_h1", "spine_h2", "spine_n5", "spine_n3", "spine_n4", "spine_n1", "spine_n2",
            "hips", "tail1", "tail2", "tail3", "tail4", "tail5", "tspike_r", "tspike_l", "tspike_r2", "tspike_l2", "spine_t7",
            "spine_t5", "spine_t6", "spine_t3", "spine_t4", "spine_t1", "spine_t2", "leg_hr", "shin_hr", "foot_hr",
            "kspike_hr", "leg_hl", "shin_hl", "foot_hl", "kspike_hl", "spine_b5", "spine_b6", "spine_b7", "leg_fr", "shin_fr",
            "foot_fr", "kspike_fr", "leg_fl", "shin_fl", "foot_fl", "kspike_fl", "wing_r", "wing_r2", "wing_r3", "wing_r_f2",
            "wing_r_f3", "wspike_r", "wing_l", "wing_l2", "wing_l3", "wing_l_f2", "wing_l_f3", "wspike_l", "sspike_r",
            "sspike_l", "spine_b1", "spine_b2", "spine_b3", "spine_b4",
        )

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-11f, -9f, -17f, 22f, 18f, 20f).texOffs(196, 116).addBox(-9f, -5.5f, -19.5f, 18f, 12f, 3f).texOffs(0, 66).addBox(-6f, 7f, -16f, 12f, 5f, 15f).texOffs(42, 59).addBox(-7f, -11f, -15.5f, 14f, 2.5f, 19f).texOffs(62, 110).addBox(-12.5f, -6f, -15f, 4f, 10f, 11f).texOffs(222, 69).addBox(8.5f, -6f, -15f, 4f, 10f, 11f), PartPose.offset(0f, 0f, 0f))
            val neck1 = body.addOrReplaceChild("neck1", CubeListBuilder.create().texOffs(0, 86).addBox(-5.5f, -5f, -9.5f, 11f, 11.5f, 10.5f), PartPose.offsetAndRotation(0f, -3f, -15f, -0.663f, 0f, 0f))
            val neck2 = neck1.addOrReplaceChild("neck2", CubeListBuilder.create().texOffs(64, 0).addBox(-4.75f, -4.75f, -9.5f, 9.5f, 9.5f, 10f), PartPose.offsetAndRotation(0f, 0f, -8f, 0.175f, 0f, 0f))
            val neck3 = neck2.addOrReplaceChild("neck3", CubeListBuilder.create().texOffs(89, 59).addBox(-4f, -4f, -9.5f, 8f, 8.5f, 10f), PartPose.offsetAndRotation(0f, 0f, -8f, 0.244f, 0f, 0f))
            val head = neck3.addOrReplaceChild("head", CubeListBuilder.create().texOffs(34, 103).addBox(-5f, -5f, -9f, 10f, 7f, 9.5f).texOffs(222, 134).addBox(-5.5f, -2.5f, -7.5f, 11f, 4.5f, 6f).texOffs(233, 145).addBox(-6f, -6.75f, -10.5f, 4.5f, 2.5f, 5.5f).texOffs(142, 98).addBox(1.5f, -6.75f, -10.5f, 4.5f, 2.5f, 5.5f).texOffs(233, 61).addBox(-3.5f, -4f, -12.5f, 7f, 4f, 4f).texOffs(64, 103).addBox(-3f, -3.5f, -15f, 6f, 3.5f, 2.5f).texOffs(222, 145).addBox(-2.75f, -3.5f, -17f, 5.5f, 3.5f, 2f).texOffs(167, 157).addBox(-1f, -5f, -15f, 2f, 1.5f, 6f).texOffs(70, 56).addBox(-2.75f, -4.5f, -16.5f, 1.5f, 1f, 1.5f).texOffs(98, 0).addBox(1.25f, -4.5f, -16.5f, 1.5f, 1f, 1.5f).texOffs(123, 152).addBox(-3.5f, -6f, -4.5f, 7f, 1.5f, 5f).texOffs(173, 125).addBox(-3.75f, 0f, -12.5f, 7.5f, 1.5f, 4f).texOffs(33, 86).addBox(-3.25f, 0f, -16.5f, 6.5f, 1.5f, 4f).texOffs(15, 0).addBox(-3.75f, 1.5f, -15.5f, 1f, 2f, 1f).texOffs(0, 0).addBox(2.75f, 1.5f, -15.5f, 1f, 2f, 1f), PartPose.offsetAndRotation(0f, 0f, -8f, 0.454f, 0f, 0f))
            val jaw = head.addOrReplaceChild("jaw", CubeListBuilder.create().texOffs(166, 140).addBox(-3.5f, 0f, -9.75f, 7f, 3f, 7.5f).texOffs(235, 153).addBox(-2.75f, 0f, -13.75f, 5.5f, 3f, 4f).texOffs(55, 131).addBox(-4.75f, -0.5f, -5.5f, 9.5f, 4f, 6.5f).texOffs(226, 153).addBox(-3.5f, -1.5f, -10f, 1f, 1.5f, 7f).texOffs(187, 97).addBox(2.5f, -1.5f, -10f, 1f, 1.5f, 7f).texOffs(246, 90).addBox(-2.75f, -1.5f, -14f, 1f, 1.5f, 4f).texOffs(0, 86).addBox(1.75f, -1.5f, -14f, 1f, 1.5f, 4f).texOffs(61, 56).addBox(-1.75f, -1.5f, -14f, 3.5f, 1.5f, 1f).texOffs(229, 122).addBox(-2f, -0.5f, -12f, 4f, 0.5f, 9f), PartPose.offset(0f, 0f, -2.5f))
            jaw.addOrReplaceChild("jspike_r", CubeListBuilder.create().texOffs(150, 163).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 4.5f), PartPose.offsetAndRotation(-4.5f, 3f, -3.5f, -0.436f, -0.524f, 0f))
            jaw.addOrReplaceChild("jspike_l", CubeListBuilder.create().texOffs(138, 163).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 4.5f), PartPose.offsetAndRotation(4.5f, 3f, -3.5f, -0.436f, 0.524f, 0f))
            jaw.addOrReplaceChild("jspike_r2", CubeListBuilder.create().texOffs(33, 92).addBox(-0.5f, -0.5f, -0.5f, 1f, 1f, 3.5f), PartPose.offsetAndRotation(-4f, 3f, -7.5f, -0.524f, -0.384f, 0f))
            jaw.addOrReplaceChild("jspike_l2", CubeListBuilder.create().texOffs(0, 92).addBox(-0.5f, -0.5f, -0.5f, 1f, 1f, 3.5f), PartPose.offsetAndRotation(4f, 3f, -7.5f, -0.524f, 0.384f, 0f))
            head.addOrReplaceChild("lids", CubeListBuilder.create().texOffs(115, 103).addBox(-5.5f, -4.25f, -9.25f, 1.5f, 2f, 3f).texOffs(115, 98).addBox(4f, -4.25f, -9.25f, 1.5f, 2f, 3f), PartPose.offset(0f, 0f, 0f))
            val horn_r = head.addOrReplaceChild("horn_r", CubeListBuilder.create().texOffs(147, 144).addBox(-1.25f, -1.25f, -1f, 2.5f, 2.5f, 8f), PartPose.offsetAndRotation(-3.5f, -5.5f, -2.5f, 0.175f, -0.314f, 0f))
            val horn_r2 = horn_r.addOrReplaceChild("horn_r2", CubeListBuilder.create().texOffs(145, 45).addBox(-1f, -1f, -0.5f, 2f, 2f, 7.5f), PartPose.offsetAndRotation(0f, 0f, 7f, -0.14f, -0.087f, 0f))
            val horn_r3 = horn_r2.addOrReplaceChild("horn_r3", CubeListBuilder.create().texOffs(141, 155).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 6.5f), PartPose.offsetAndRotation(0f, 0f, 7f, 0.279f, 0.07f, 0f))
            horn_r3.addOrReplaceChild("horn_r4", CubeListBuilder.create().texOffs(177, 157).addBox(-0.5f, -0.5f, -0.5f, 1f, 1f, 4.5f), PartPose.offsetAndRotation(0f, 0f, 6f, -0.209f, 0.052f, 0f))
            val horn_l = head.addOrReplaceChild("horn_l", CubeListBuilder.create().texOffs(235, 108).addBox(-1.25f, -1.25f, -1f, 2.5f, 2.5f, 8f), PartPose.offsetAndRotation(3.5f, -5.5f, -2.5f, 0.175f, 0.314f, 0f))
            val horn_l2 = horn_l.addOrReplaceChild("horn_l2", CubeListBuilder.create().texOffs(145, 35).addBox(-1f, -1f, -0.5f, 2f, 2f, 7.5f), PartPose.offsetAndRotation(0f, 0f, 7f, -0.14f, 0.087f, 0f))
            val horn_l3 = horn_l2.addOrReplaceChild("horn_l3", CubeListBuilder.create().texOffs(110, 155).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 6.5f), PartPose.offsetAndRotation(0f, 0f, 7f, 0.279f, -0.07f, 0f))
            horn_l3.addOrReplaceChild("horn_l4", CubeListBuilder.create().texOffs(157, 98).addBox(-0.5f, -0.5f, -0.5f, 1f, 1f, 4.5f), PartPose.offsetAndRotation(0f, 0f, 6f, -0.209f, -0.052f, 0f))
            head.addOrReplaceChild("chorn_r", CubeListBuilder.create().texOffs(171, 87).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 5.5f), PartPose.offsetAndRotation(-5.25f, -1.5f, -3f, 0.105f, -0.524f, 0f))
            head.addOrReplaceChild("chorn_l", CubeListBuilder.create().texOffs(171, 80).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 5.5f), PartPose.offsetAndRotation(5.25f, -1.5f, -3f, 0.105f, 0.524f, 0f))
            head.addOrReplaceChild("brow_spike_r", CubeListBuilder.create().texOffs(103, 163).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 4.5f), PartPose.offsetAndRotation(-5f, -6f, -6.5f, 0.314f, -0.698f, 0f))
            head.addOrReplaceChild("brow_spike_l", CubeListBuilder.create().texOffs(151, 155).addBox(-0.75f, -0.75f, -0.5f, 1.5f, 1.5f, 4.5f), PartPose.offsetAndRotation(5f, -6f, -6.5f, 0.314f, 0.698f, 0f))
            head.addOrReplaceChild("spine_h1", CubeListBuilder.create().texOffs(248, 104).addBox(-0.5f, -2.5f, -1f, 1f, 3f, 2f), PartPose.offsetAndRotation(0f, -5.75f, -1.5f, -0.698f, 0f, 0f))
            head.addOrReplaceChild("spine_h2", CubeListBuilder.create().texOffs(106, 98).addBox(-0.5f, -2f, -1f, 1f, 2.5f, 2f), PartPose.offsetAndRotation(0f, -5.5f, 1f, -0.698f, 0f, 0f))
            neck3.addOrReplaceChild("spine_n5", CubeListBuilder.create().texOffs(77, 38).addBox(-0.5f, -3f, -1f, 1f, 3.5f, 2f), PartPose.offsetAndRotation(0f, -3.75f, -4.5f, -0.436f, 0f, 0f))
            neck2.addOrReplaceChild("spine_n3", CubeListBuilder.create().texOffs(249, 54).addBox(-0.5f, -4f, -1.25f, 1f, 4.5f, 2.5f), PartPose.offsetAndRotation(0f, -4.5f, -2.5f, -0.436f, 0f, 0f))
            neck2.addOrReplaceChild("spine_n4", CubeListBuilder.create().texOffs(162, 64).addBox(-0.5f, -3.5f, -1.25f, 1f, 4f, 2.5f), PartPose.offsetAndRotation(0f, -4.5f, -7f, -0.436f, 0f, 0f))
            neck1.addOrReplaceChild("spine_n1", CubeListBuilder.create().texOffs(145, 35).addBox(-0.5f, -4.5f, -1.25f, 1f, 5f, 2.5f), PartPose.offsetAndRotation(0f, -4.75f, -2.5f, -0.436f, 0f, 0f))
            neck1.addOrReplaceChild("spine_n2", CubeListBuilder.create().texOffs(157, 45).addBox(-0.5f, -4f, -1.25f, 1f, 4.5f, 2.5f), PartPose.offsetAndRotation(0f, -4.75f, -7f, -0.436f, 0f, 0f))
            val hips = body.addOrReplaceChild("hips", CubeListBuilder.create().texOffs(84, 0).addBox(-10f, -7f, -3f, 20f, 14.5f, 20f).texOffs(171, 80).addBox(-9f, 6f, -4f, 18f, 3f, 14f).texOffs(108, 59).addBox(-6f, -8.5f, -2f, 12f, 2f, 19f).texOffs(135, 125).addBox(-7f, -6f, 15f, 14f, 11f, 5f), PartPose.offset(0f, -1f, 3f))
            val tail1 = hips.addOrReplaceChild("tail1", CubeListBuilder.create().texOffs(43, 81).addBox(-6f, -5f, -1.5f, 12f, 10f, 11.5f), PartPose.offsetAndRotation(0f, -2f, 19f, -0.349f, 0f, 0f))
            val tail2 = tail1.addOrReplaceChild("tail2", CubeListBuilder.create().texOffs(196, 97).addBox(-5f, -4.25f, -1f, 10f, 8.5f, 10.5f), PartPose.offsetAndRotation(0f, 0f, 9f, 0.087f, 0f, 0f))
            val tail3 = tail2.addOrReplaceChild("tail3", CubeListBuilder.create().texOffs(115, 98).addBox(-4f, -3.5f, -1f, 8f, 7f, 10.5f), PartPose.offsetAndRotation(0f, 0f, 9f, 0.105f, 0f, 0f))
            val tail4 = tail3.addOrReplaceChild("tail4", CubeListBuilder.create().texOffs(27, 120).addBox(-3f, -2.75f, -1f, 6f, 5.5f, 10f), PartPose.offsetAndRotation(0f, 0f, 9f, 0.14f, 0f, 0f))
            val tail5 = tail4.addOrReplaceChild("tail5", CubeListBuilder.create().texOffs(227, 90).addBox(-2.25f, -2f, -1f, 4.5f, 4f, 10f).texOffs(39, 62).addBox(-0.5f, -3.5f, 6f, 1f, 7f, 9f), PartPose.offsetAndRotation(0f, 0f, 8.5f, 0.105f, 0f, 0f))
            tail5.addOrReplaceChild("tspike_r", CubeListBuilder.create().texOffs(151, 64).addBox(-1f, -1f, -0.5f, 2f, 2f, 6.5f), PartPose.offsetAndRotation(-1.5f, 0f, 6f, 0f, -0.698f, 0f))
            tail5.addOrReplaceChild("tspike_l", CubeListBuilder.create().texOffs(151, 55).addBox(-1f, -1f, -0.5f, 2f, 2f, 6.5f), PartPose.offsetAndRotation(1.5f, 0f, 6f, 0f, 0.698f, 0f))
            tail5.addOrReplaceChild("tspike_r2", CubeListBuilder.create().texOffs(188, 140).addBox(-1f, -1f, -0.5f, 2f, 2f, 5f), PartPose.offsetAndRotation(-1.5f, 0f, 2f, 0.175f, -0.96f, 0f))
            tail5.addOrReplaceChild("tspike_l2", CubeListBuilder.create().texOffs(20, 108).addBox(-1f, -1f, -0.5f, 2f, 2f, 5f), PartPose.offsetAndRotation(1.5f, 0f, 2f, 0.175f, 0.96f, 0f))
            tail4.addOrReplaceChild("spine_t7", CubeListBuilder.create().texOffs(237, 104).addBox(-0.5f, -3f, -1f, 1f, 3.5f, 2f), PartPose.offsetAndRotation(0f, -2.5f, 3.5f, -0.785f, 0f, 0f))
            tail3.addOrReplaceChild("spine_t5", CubeListBuilder.create().texOffs(151, 55).addBox(-0.5f, -3.5f, -1f, 1f, 4f, 2f), PartPose.offsetAndRotation(0f, -3.25f, 2.5f, -0.785f, 0f, 0f))
            tail3.addOrReplaceChild("spine_t6", CubeListBuilder.create().texOffs(151, 64).addBox(-0.5f, -3f, -1f, 1f, 3.5f, 2f), PartPose.offsetAndRotation(0f, -3.25f, 7f, -0.785f, 0f, 0f))
            tail2.addOrReplaceChild("spine_t3", CubeListBuilder.create().texOffs(187, 97).addBox(-0.5f, -4f, -1.25f, 1f, 4.5f, 2.5f), PartPose.offsetAndRotation(0f, -4f, 2.5f, -0.698f, 0f, 0f))
            tail2.addOrReplaceChild("spine_t4", CubeListBuilder.create().texOffs(196, 97).addBox(-0.5f, -3.5f, -1.25f, 1f, 4f, 2.5f), PartPose.offsetAndRotation(0f, -4f, 7f, -0.698f, 0f, 0f))
            tail1.addOrReplaceChild("spine_t1", CubeListBuilder.create().texOffs(157, 35).addBox(-0.5f, -4.5f, -1.25f, 1f, 5f, 2.5f), PartPose.offsetAndRotation(0f, -4.75f, 2.5f, -0.698f, 0f, 0f))
            tail1.addOrReplaceChild("spine_t2", CubeListBuilder.create().texOffs(99, 81).addBox(-0.5f, -4f, -1.25f, 1f, 4.5f, 2.5f), PartPose.offsetAndRotation(0f, -4.75f, 7f, -0.698f, 0f, 0f))
            val leg_hr = hips.addOrReplaceChild("leg_hr", CubeListBuilder.create().texOffs(162, 97).addBox(-3.75f, -6f, -4.5f, 7.5f, 18f, 9.5f), PartPose.offsetAndRotation(-9f, 3f, 11f, -0.565f, 0f, 0.077f))
            val shin_hr = leg_hr.addOrReplaceChild("shin_hr", CubeListBuilder.create().texOffs(144, 0).addBox(-2.5f, -1.5f, -2.5f, 5f, 13.5f, 5f), PartPose.offsetAndRotation(0f, 12f, 0f, 1.233f, 0f, 0f))
            shin_hr.addOrReplaceChild("foot_hr", CubeListBuilder.create().texOffs(100, 140).addBox(-3f, -1f, -4.5f, 6f, 3.5f, 8f).texOffs(160, 73).addBox(-0.75f, -1.5f, 3f, 1.5f, 2f, 3f).texOffs(130, 161).addBox(-3.25f, 0.5f, -8.5f, 1.5f, 2f, 4.5f).texOffs(94, 158).addBox(-0.75f, 0.5f, -9f, 1.5f, 2f, 5f).texOffs(226, 162).addBox(1.75f, 0.5f, -8.5f, 1.5f, 2f, 4.5f).texOffs(227, 104).addBox(-3f, 1f, -10.5f, 1f, 1.5f, 2f).texOffs(29, 108).addBox(-0.5f, 1f, -11f, 1f, 1.5f, 2f).texOffs(35, 108).addBox(2f, 1f, -10.5f, 1f, 1.5f, 2f), PartPose.offsetAndRotation(0f, 12f, 0f, -0.666f, -0.047f, -0.06f))
            leg_hr.addOrReplaceChild("kspike_hr", CubeListBuilder.create().texOffs(198, 157).addBox(-1f, -1f, -0.5f, 2f, 2f, 5.5f), PartPose.offsetAndRotation(-1.5f, 11f, -4f, 2.616f, -0.394f, -2.967f))
            val leg_hl = hips.addOrReplaceChild("leg_hl", CubeListBuilder.create().texOffs(81, 93).addBox(-3.75f, -6f, -4.5f, 7.5f, 18f, 9.5f), PartPose.offsetAndRotation(9f, 3f, 11f, -0.565f, 0f, -0.077f))
            val shin_hl = leg_hl.addOrReplaceChild("shin_hl", CubeListBuilder.create().texOffs(0, 0).addBox(-2.5f, -1.5f, -2.5f, 5f, 13.5f, 5f), PartPose.offsetAndRotation(0f, 12f, 0f, 1.233f, 0f, 0f))
            shin_hl.addOrReplaceChild("foot_hl", CubeListBuilder.create().texOffs(79, 81).addBox(-3f, -1f, -4.5f, 6f, 3.5f, 8f).texOffs(151, 73).addBox(-0.75f, -1.5f, 3f, 1.5f, 2f, 3f).texOffs(122, 159).addBox(-3.25f, 0.5f, -8.5f, 1.5f, 2f, 4.5f).texOffs(49, 120).addBox(-0.75f, 0.5f, -9f, 1.5f, 2f, 5f).texOffs(238, 160).addBox(1.75f, 0.5f, -8.5f, 1.5f, 2f, 4.5f).texOffs(227, 90).addBox(-3f, 1f, -10.5f, 1f, 1.5f, 2f).texOffs(246, 96).addBox(-0.5f, 1f, -11f, 1f, 1.5f, 2f).texOffs(164, 98).addBox(2f, 1f, -10.5f, 1f, 1.5f, 2f), PartPose.offsetAndRotation(0f, 12f, 0f, -0.666f, 0.047f, 0.06f))
            leg_hl.addOrReplaceChild("kspike_hl", CubeListBuilder.create().texOffs(183, 157).addBox(-1f, -1f, -0.5f, 2f, 2f, 5.5f), PartPose.offsetAndRotation(1.5f, 11f, -4f, 2.616f, 0.394f, 2.967f))
            hips.addOrReplaceChild("spine_b5", CubeListBuilder.create().texOffs(50, 62).addBox(-0.5f, -5f, -1.5f, 1f, 5.5f, 3f), PartPose.offsetAndRotation(0f, -8.25f, 3f, -0.524f, 0f, 0f))
            hips.addOrReplaceChild("spine_b6", CubeListBuilder.create().texOffs(79, 81).addBox(-0.5f, -4.5f, -1.5f, 1f, 5f, 3f), PartPose.offsetAndRotation(0f, -8.25f, 8f, -0.524f, 0f, 0f))
            hips.addOrReplaceChild("spine_b7", CubeListBuilder.create().texOffs(145, 45).addBox(-0.5f, -4f, -1.25f, 1f, 4.5f, 2.5f), PartPose.offsetAndRotation(0f, -8.25f, 13f, -0.524f, 0f, 0f))
            val leg_fr = body.addOrReplaceChild("leg_fr", CubeListBuilder.create().texOffs(108, 116).addBox(-3.25f, -4f, -3.5f, 6.5f, 16.5f, 7f), PartPose.offsetAndRotation(-9.5f, 3f, -9f, 0.545f, 0f, 0.081f))
            val shin_fr = leg_fr.addOrReplaceChild("shin_fr", CubeListBuilder.create().texOffs(87, 126).addBox(-2.5f, -1f, -2.75f, 5f, 12.5f, 5.5f), PartPose.offsetAndRotation(0f, 12f, 0f, -1.261f, 0f, 0f))
            shin_fr.addOrReplaceChild("foot_fr", CubeListBuilder.create().texOffs(128, 141).addBox(-3f, -1f, -5f, 6f, 3.5f, 7.5f).texOffs(0, 165).addBox(-3.25f, 0.5f, -8.5f, 1.5f, 2f, 4f).texOffs(160, 141).addBox(-0.75f, 0.5f, -9f, 1.5f, 2f, 4.5f).texOffs(11, 165).addBox(1.75f, 0.5f, -8.5f, 1.5f, 2f, 4f).texOffs(23, 62).addBox(-3f, 1f, -10.5f, 1f, 1.5f, 2f).texOffs(29, 62).addBox(-0.5f, 1f, -11f, 1f, 1.5f, 2f).texOffs(221, 90).addBox(2f, 1f, -10.5f, 1f, 1.5f, 2f), PartPose.offsetAndRotation(0f, 11f, 0f, 0.714f, 0.053f, -0.061f))
            leg_fr.addOrReplaceChild("kspike_fr", CubeListBuilder.create().texOffs(120, 140).addBox(-1f, -1f, -0.5f, 2f, 2f, 5.5f), PartPose.offsetAndRotation(-1.5f, 10.5f, 3f, -0.504f, -0.4f, 0.163f))
            val leg_fl = body.addOrReplaceChild("leg_fl", CubeListBuilder.create().texOffs(0, 108).addBox(-3.25f, -4f, -3.5f, 6.5f, 16.5f, 7f), PartPose.offsetAndRotation(9.5f, 3f, -9f, 0.545f, 0f, -0.081f))
            val shin_fl = leg_fl.addOrReplaceChild("shin_fl", CubeListBuilder.create().texOffs(61, 38).addBox(-2.5f, -1f, -2.75f, 5f, 12.5f, 5.5f), PartPose.offsetAndRotation(0f, 12f, 0f, -1.261f, 0f, 0f))
            shin_fl.addOrReplaceChild("foot_fl", CubeListBuilder.create().texOffs(195, 140).addBox(-3f, -1f, -5f, 6f, 3.5f, 7.5f).texOffs(115, 163).addBox(-3.25f, 0.5f, -8.5f, 1.5f, 2f, 4f).texOffs(115, 59).addBox(-0.75f, 0.5f, -9f, 1.5f, 2f, 4.5f).texOffs(209, 164).addBox(1.75f, 0.5f, -8.5f, 1.5f, 2f, 4f).texOffs(145, 55).addBox(-3f, 1f, -10.5f, 1f, 1.5f, 2f).texOffs(11, 62).addBox(-0.5f, 1f, -11f, 1f, 1.5f, 2f).texOffs(17, 62).addBox(2f, 1f, -10.5f, 1f, 1.5f, 2f), PartPose.offsetAndRotation(0f, 11f, 0f, 0.714f, -0.053f, 0.061f))
            leg_fl.addOrReplaceChild("kspike_fl", CubeListBuilder.create().texOffs(0, 70).addBox(-1f, -1f, -0.5f, 2f, 2f, 5.5f), PartPose.offsetAndRotation(1.5f, 10.5f, 3f, -0.504f, 0.4f, -0.163f))
            val wing_r = body.addOrReplaceChild("wing_r", CubeListBuilder.create().texOffs(48, 157).addBox(-2f, -2f, -2f, 4f, 4f, 4f).texOffs(128, 116).addBox(-13f, -1.75f, -1.75f, 13f, 3.5f, 3.5f).texOffs(216, 27).addBox(-14.5f, 0f, 0f, 19.5f, 26.5f, 0f), PartPose.offsetAndRotation(-8f, -9.5f, -9f, 1.561f, -0.098f, 0.178f))
            val wing_r2 = wing_r.addOrReplaceChild("wing_r2", CubeListBuilder.create().texOffs(241, 69).addBox(-1.75f, -2f, -2f, 3.5f, 4f, 4f).texOffs(0, 145).addBox(-19.5f, -1.5f, -1.5f, 19.5f, 3f, 3f).texOffs(164, 31).addBox(-20f, 0f, 0f, 26f, 30.5f, 0f), PartPose.offsetAndRotation(-13f, 0f, 0f, 0.03f, -0.122f, -0.317f))
            val wing_r3 = wing_r2.addOrReplaceChild("wing_r3", CubeListBuilder.create().texOffs(16, 157).addBox(-2f, -2f, -2f, 4f, 4f, 4f).texOffs(0, 108).addBox(-1.75f, -5f, -0.75f, 1.5f, 3.5f, 1.5f).texOffs(63, 155).addBox(-25f, -0.75f, -0.75f, 25f, 1.5f, 1.5f).texOffs(33, 62).addBox(-27f, -0.5f, -0.5f, 2f, 1f, 1f).texOffs(0, 136).addBox(-27f, 0f, 0f, 27.5f, 8.5f, 0f), PartPose.offsetAndRotation(-19.5f, 0f, 0f, -0.002f, -0.086f, -0.077f))
            wing_r3.addOrReplaceChild("wing_r_f2", CubeListBuilder.create().texOffs(168, 151).addBox(-31f, -0.75f, -0.75f, 31f, 1.5f, 1.5f).texOffs(108, 80).addBox(-31f, -8.5f, 0f, 31.5f, 18f, 0f), PartPose.offsetAndRotation(0f, 0f, -0.15f, -0.066f, 0.004f, -0.607f))
            wing_r3.addOrReplaceChild("wing_r_f3", CubeListBuilder.create().texOffs(0, 154).addBox(-30f, -0.75f, -0.75f, 30f, 1.5f, 1.5f).texOffs(0, 38).addBox(-30f, -9.5f, 0f, 30.5f, 24f, 0f), PartPose.offsetAndRotation(0f, 0f, -0.3f, -0.108f, 0.004f, -1.239f))
            wing_r2.addOrReplaceChild("wspike_r", CubeListBuilder.create().texOffs(79, 158).addBox(-1f, -1f, -0.5f, 2f, 2f, 5.5f), PartPose.offsetAndRotation(0f, 0f, 1.8f, -0.694f, -0.049f, 0.643f))
            val wing_l = body.addOrReplaceChild("wing_l", CubeListBuilder.create().texOffs(32, 157).addBox(-2f, -2f, -2f, 4f, 4f, 4f).texOffs(216, 54).addBox(0f, -1.75f, -1.75f, 13f, 3.5f, 3.5f).texOffs(216, 0).addBox(-5f, 0f, 0f, 19.5f, 26.5f, 0f), PartPose.offsetAndRotation(8f, -9.5f, -9f, 1.561f, 0.098f, -0.178f))
            val wing_l2 = wing_l.addOrReplaceChild("wing_l2", CubeListBuilder.create().texOffs(0, 62).addBox(-1.75f, -2f, -2f, 3.5f, 4f, 4f).texOffs(52, 144).addBox(0f, -1.5f, -1.5f, 19.5f, 3f, 3f).texOffs(164, 0).addBox(-6f, 0f, 0f, 26f, 30.5f, 0f), PartPose.offsetAndRotation(13f, 0f, 0f, 0.03f, 0.122f, 0.317f))
            val wing_l3 = wing_l2.addOrReplaceChild("wing_l3", CubeListBuilder.create().texOffs(0, 157).addBox(-2f, -2f, -2f, 4f, 4f, 4f).texOffs(152, 106).addBox(0.25f, -5f, -0.75f, 1.5f, 3.5f, 1.5f).texOffs(167, 154).addBox(0f, -0.75f, -0.75f, 25f, 1.5f, 1.5f).texOffs(76, 56).addBox(25f, -0.5f, -0.5f, 2f, 1f, 1f).texOffs(173, 131).addBox(-0.5f, 0f, 0f, 27.5f, 8.5f, 0f), PartPose.offsetAndRotation(19.5f, 0f, 0f, -0.002f, 0.086f, 0.077f))
            wing_l3.addOrReplaceChild("wing_l_f2", CubeListBuilder.create().texOffs(0, 151).addBox(0f, -0.75f, -0.75f, 31f, 1.5f, 1.5f).texOffs(170, 62).addBox(-0.5f, -8.5f, 0f, 31.5f, 18f, 0f), PartPose.offsetAndRotation(0f, 0f, -0.15f, -0.066f, -0.004f, 0.607f))
            wing_l3.addOrReplaceChild("wing_l_f3", CubeListBuilder.create().texOffs(65, 152).addBox(0f, -0.75f, -0.75f, 30f, 1.5f, 1.5f).texOffs(84, 35).addBox(-0.5f, -9.5f, 0f, 30.5f, 24f, 0f), PartPose.offsetAndRotation(0f, 0f, -0.3f, -0.108f, -0.004f, 1.239f))
            wing_l2.addOrReplaceChild("wspike_l", CubeListBuilder.create().texOffs(64, 158).addBox(-1f, -1f, -0.5f, 2f, 2f, 5.5f), PartPose.offsetAndRotation(0f, 0f, 1.8f, -0.694f, 0.049f, -0.643f))
            body.addOrReplaceChild("sspike_r", CubeListBuilder.create().texOffs(214, 156).addBox(-1f, -1f, -0.5f, 2f, 2f, 6f), PartPose.offsetAndRotation(-11.5f, -5f, -10f, 0.302f, -1.084f, 0f))
            body.addOrReplaceChild("sspike_l", CubeListBuilder.create().texOffs(157, 155).addBox(-1f, -1f, -0.5f, 2f, 2f, 6f), PartPose.offsetAndRotation(11.5f, -5f, -10f, 0.302f, 1.084f, 0f))
            body.addOrReplaceChild("spine_b1", CubeListBuilder.create().texOffs(89, 59).addBox(-0.5f, -5f, -1.5f, 1f, 5.5f, 3f), PartPose.offsetAndRotation(0f, -10.75f, -13f, -0.524f, 0f, 0f))
            body.addOrReplaceChild("spine_b2", CubeListBuilder.create().texOffs(64, 0).addBox(-0.5f, -6f, -1.5f, 1f, 6.5f, 3f), PartPose.offsetAndRotation(0f, -10.75f, -8.5f, -0.524f, 0f, 0f))
            body.addOrReplaceChild("spine_b3", CubeListBuilder.create().texOffs(93, 0).addBox(-0.5f, -6f, -1.5f, 1f, 6.5f, 3f), PartPose.offsetAndRotation(0f, -10.75f, -4f, -0.524f, 0f, 0f))
            body.addOrReplaceChild("spine_b4", CubeListBuilder.create().texOffs(39, 62).addBox(-0.5f, -5f, -1.5f, 1f, 5.5f, 3f), PartPose.offsetAndRotation(0f, -10.75f, 0.5f, -0.524f, 0f, 0f))
            return LayerDefinition.create(mesh, 256, 256)
        }
    }
}
