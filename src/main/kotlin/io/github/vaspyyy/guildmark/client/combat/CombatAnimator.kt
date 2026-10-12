package io.github.vaspyyy.guildmark.client.combat

import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.combat.Move
import io.github.vaspyyy.guildmark.combat.Stance
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.util.context.ContextKey
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.LivingEntity
import net.neoforged.fml.common.asm.enumextension.EnumProxy
import net.neoforged.neoforge.client.IArmPoseTransformer
import net.neoforged.neoforge.client.event.RenderLivingEvent
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.exp
import kotlin.math.sin

/**
 * The arm pose a player is given while a combat move plays. Its transformer hands the whole model to
 * [CombatAnimator], which poses every part from the move's clip. Kept apart so the arm pose enum can
 * build it without loading the animator.
 */
object CombatArmPose {
    @JvmField
    val PROXY: EnumProxy<HumanoidModel.ArmPose> = EnumProxy(
        HumanoidModel.ArmPose::class.java,
        true,
        true,
        IArmPoseTransformer { model, state, _ -> CombatAnimator.pose(model, state) },
    )
}

/**
 * Plays combat clips on humanoid models. Clips move a small skeleton: the root (the whole body, turning
 * about the hips), the torso (bending at the hips, carrying the arms and head with it), the head, and the
 * arms and legs. Vanilla's model has no such hierarchy, so the parts are placed here by hand.
 */
object CombatAnimator {
    /**
     * What the model should show this frame, worked out while the render state is filled in. [from] is
     * the move this one cut short, which the model eases out of while this one eases in.
     */
    class Frame(val clip: Clip, val time: Float, val weight: Float, val yaw: Float, val tremble: Float, val seed: Float, val from: Frame?)

    val FRAME: ContextKey<Frame> = ContextKey(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "combat_frame"))

    private val armPose: HumanoidModel.ArmPose by lazy { CombatArmPose.PROXY.value }

    /** Fills in the frame for a player doing a move. */
    val AVATAR = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
            state.setRenderData(FRAME, null)
            if (!CombatClient.active()) return
            val action = CombatClient.actionOf(entity) ?: return
            val clip = Clips.get("player/" + action.move.anim) ?: return
            val time = action.time(state.partialTick)
            val weight = clip.weight(time)
            val from = action.from?.takeIf { time < clip.blendIn }?.let { move ->
                val fromClip = Clips.get("player/" + move.anim) ?: return@let null
                val fromWeight = fromClip.weight(action.fromTime)
                if (fromWeight <= 0f) null
                else Frame(fromClip, action.fromTime, fromWeight, rollYaw(move, action.fromYaw, state.bodyRot), 0f, 0f, null)
            }
            if (weight <= 0f && from == null) return
            val tremble = if (action.charging && action.charge > 0) action.chargeFraction() else 0f
            state.setRenderData(FRAME, Frame(clip, time, weight, rollYaw(action.move, action.yaw, state.bodyRot), tremble, state.ageInTicks, from))
            state.rightArmPose = armPose
            state.leftArmPose = armPose
            state.swingAnimation = 0f
            state.isCrouching = false
            if (action.move.isRoll || from != null && action.from?.isRoll == true) state.showCape = false
        }
    }

    /** Which way a roll heads, relative to where the body faces. */
    private fun rollYaw(move: Move, yaw: Float, bodyRot: Float): Float =
        if (move == Move.ROLL || move == Move.ROLL_HEAVY) Math.toRadians(Mth.wrapDegrees(yaw - bodyRot).toDouble()).toFloat() else 0f

    /** A part's rotation and position. */
    private class Pose {
        val q = Quaternionf()
        val v = Vector3f()

        fun set(other: Pose): Pose {
            q.set(other.q)
            v.set(other.v)
            return this
        }

        /** Moves towards [to] by [w]. */
        fun mix(to: Pose, w: Float): Pose {
            if (w >= 1f) return set(to)
            q.slerp(to.q, w)
            v.lerp(to.v, w)
            return this
        }
    }

    // The parts the clips move, in this order; the root's position is an offset, its pivot added at the end
    private const val BODY = 0
    private const val RIGHT_ARM = 1
    private const val LEFT_ARM = 2
    private const val HEAD = 3
    private const val RIGHT_LEG = 4
    private const val LEFT_LEG = 5
    private const val ROOT = 6

    // Scratch values, reused frame to frame (rendering runs on one thread)
    private val vanilla = Array(7) { Pose() }
    private val goal = Array(7) { Pose() }
    private val shown = Array(7) { Pose() }
    private val qTorso = Quaternionf()
    private val qClip = Quaternionf()
    private val vTorso = Vector3f()
    private val vClip = Vector3f()
    private val hips = Vector3f()
    private val euler = Vector3f()

    /**
     * Poses the model for its frame. Each part starts from where vanilla put it, eases into the move cut
     * short (if any), then into this one.
     */
    fun pose(model: HumanoidModel<*>, state: HumanoidRenderState) {
        val frame = state.getRenderData(FRAME) ?: return
        val parts = arrayOf(model.body, model.rightArm, model.leftArm, model.head, model.rightLeg, model.leftLeg)
        hips.set(0f, model.rightLeg.initialPose.y, 0f)
        for (i in parts.indices) {
            val part = parts[i]
            vanilla[i].q.rotationZYX(part.zRot, part.yRot, part.xRot)
            vanilla[i].v.set(part.x, part.y, part.z)
        }
        vanilla[ROOT].q.identity()
        vanilla[ROOT].v.zero()
        for (i in 0 until 7) shown[i].set(vanilla[i])

        frame.from?.let { from ->
            goals(model, from)
            for (i in 0 until 7) shown[i].mix(goal[i], from.weight)
        }
        goals(model, frame)
        for (i in 0 until 7) shown[i].mix(goal[i], frame.weight)

        for (i in parts.indices) write(parts[i], shown[i].q, shown[i].v)
        // The root turns about the hips
        val root = shown[ROOT]
        vClip.set(hips)
        root.q.transform(vClip)
        write(model.root(), root.q, vClip.negate().add(hips).add(root.v))
    }

    /** Where each part would be with [frame]'s clip at full strength. */
    private fun goals(model: HumanoidModel<*>, frame: Frame) {
        val clip = frame.clip
        val t = frame.time

        // The torso bends about the hips; the body, arms and head ride on it
        val torso = clip.bones["torso"]
        if (torso != null) torso.rotation(clip, t, qTorso) else qTorso.identity()
        if (torso != null) torso.position(clip, t, vTorso) else vTorso.zero()
        if (frame.tremble > 0f) {
            val shake = frame.tremble * 0.02f
            qTorso.rotateX(sin(frame.seed * 7.1f) * shake).rotateZ(sin(frame.seed * 9.3f) * shake)
        }
        carried(BODY, model.body, null, false, frame)
        carried(RIGHT_ARM, model.rightArm, clip.bones["right_arm"], true, frame)
        carried(LEFT_ARM, model.leftArm, clip.bones["left_arm"], true, frame)
        head(model.head, clip.bones["head"], frame)
        leg(RIGHT_LEG, model.rightLeg, clip.bones["right_leg"], frame)
        leg(LEFT_LEG, model.leftLeg, clip.bones["left_leg"], frame)

        // The root turns the whole body: rolls, and a roll's direction
        val root = clip.bones["root"]
        val g = goal[ROOT]
        if (root != null) root.rotation(clip, t, g.q) else g.q.identity()
        if (frame.yaw != 0f) g.q.premul(qClip.rotationY(frame.yaw))
        if (root != null) root.position(clip, t, g.v) else g.v.zero()
    }

    /** A part carried by the torso: the body itself, or an arm with its own track. */
    private fun carried(i: Int, part: ModelPart, track: Clip.Track?, limb: Boolean, frame: Frame) {
        val g = goal[i]
        // Rotation: the torso's, then the part's own (or vanilla's when the clip leaves it alone)
        when {
            !limb -> g.q.set(qTorso)
            track == null -> g.q.set(qTorso).mul(vanilla[i].q)
            track.add -> g.q.set(qTorso).mul(vanilla[i].q).mul(track.rotation(frame.clip, frame.time, qClip))
            else -> g.q.set(qTorso).mul(track.rotation(frame.clip, frame.time, qClip))
        }
        // Pivot: where the joint sits once the torso has moved
        val initial = part.initialPose
        g.v.set(initial.x, initial.y, initial.z)
        if (track != null && track.hasPosition) g.v.add(track.position(frame.clip, frame.time, vClip))
        g.v.sub(hips)
        qTorso.transform(g.v)
        g.v.add(hips).add(vTorso)
    }

    /** The head keeps looking where the player looks unless the clip takes it over. */
    private fun head(part: ModelPart, track: Clip.Track?, frame: Frame) {
        val g = goal[HEAD]
        when {
            track == null -> g.q.set(vanilla[HEAD].q)
            track.add -> g.q.set(vanilla[HEAD].q).mul(track.rotation(frame.clip, frame.time, qClip))
            else -> g.q.set(qTorso).mul(track.rotation(frame.clip, frame.time, qClip))
        }
        val initial = part.initialPose
        g.v.set(initial.x, initial.y, initial.z).sub(hips)
        qTorso.transform(g.v)
        g.v.add(hips).add(vTorso)
    }

    /** A leg hangs from the root, not the torso; without a track it keeps vanilla's walk. */
    private fun leg(i: Int, part: ModelPart, track: Clip.Track?, frame: Frame) {
        val g = goal[i]
        if (track == null) {
            g.set(vanilla[i])
            return
        }
        if (track.add) g.q.set(vanilla[i].q).mul(track.rotation(frame.clip, frame.time, qClip))
        else track.rotation(frame.clip, frame.time, g.q)
        val initial = part.initialPose
        g.v.set(initial.x, initial.y, initial.z)
        if (track.hasPosition) g.v.add(track.position(frame.clip, frame.time, vClip))
    }

    private fun write(part: ModelPart, q: Quaternionf, v: Vector3f) {
        part.x = v.x
        part.y = v.y
        part.z = v.z
        q.getEulerAnglesZYX(euler)
        part.xRot = euler.x
        part.yRot = euler.y
        part.zRot = euler.z
    }

    // ---- Staggered creatures ----

    /** How far a staggered creature leans this frame, in degrees (positive leans back). */
    private fun lean(entity: LivingEntity, partial: Float): Float {
        val (into, total) = CombatClient.staggerOf(entity, partial) ?: return 0f
        val kind = CombatClient.staggerKind(entity)
        return when (kind) {
            // Knocked back on its heels, swaying until it finds its feet again
            Stance.BROKEN, Stance.PARRIED -> {
                val settle = (1f - into / total).coerceIn(0f, 1f)
                val rise = (into / 3f).coerceIn(0f, 1f)
                rise * (14f * settle + 5f * sin(into * 0.45f) * settle)
            }
            // Run through: doubled over the blade, then thrown off it
            Stance.HELD -> when {
                into < 12f -> 0f
                into < 22f -> -22f * ((into - 12f) / 3f).coerceIn(0f, 1f)
                else -> -22f + 40f * (1f - exp(-(into - 22f) / 3f))
            }
            Stance.HELD_BACK -> if (into < 12f) 0f else 18f * (1f - exp(-(into - 12f) / 2.5f))
            else -> 0f
        }
    }

    private val LEAN: ContextKey<Float> = ContextKey(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "stagger_lean"))
    private val pushed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<LivingEntityRenderState, Boolean>())

    /** Notes how far each staggered creature leans, while its render state is filled in. */
    fun onLivingState(entity: LivingEntity, state: LivingEntityRenderState) {
        state.setRenderData(LEAN, null)
        if (entity is Avatar || !CombatClient.active()) return
        val lean = lean(entity, state.partialTick)
        if (lean != 0f) state.setRenderData(LEAN, lean)
    }

    fun onRenderPre(event: RenderLivingEvent.Pre<*, *, *>) {
        val state = event.renderState
        val lean = state.getRenderData(LEAN) ?: return
        val pose = event.poseStack
        pose.pushPose()
        pose.rotateDegrees(Axis.YP, 180f - state.bodyRot)
        pose.rotateDegrees(Axis.XP, lean)
        pose.rotateDegrees(Axis.YP, -(180f - state.bodyRot))
        pushed.add(state)
    }

    fun onRenderPost(event: RenderLivingEvent.Post<*, *, *>) {
        if (pushed.remove(event.renderState)) event.poseStack.popPose()
    }
}
