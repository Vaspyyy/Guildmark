package io.github.vaspyyy.guildmark.client.combat

import net.minecraft.client.model.geom.ModelPart
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Plays clips on a model whose parts nest (the Dusk Rider's), by part name. A clip's 'set' track turns a
 * part from its rest pose and moves its joint from where it rests; an 'add' track turns and moves it from
 * wherever the layers under it left it. Each layer shows as strongly as its weight. The clips come from
 * art/scripts/dusk_rider_anims.py, whose previews pose the model by the same rules.
 */
class RigAnimator(root: ModelPart, names: List<String>) {
    private class Joint(val part: ModelPart) {
        val restQ = Quaternionf()
        val restV = Vector3f()
        val q = Quaternionf()
        /** How far the joint has moved from where it rests. */
        val v = Vector3f()
    }

    private val joints: Map<String, Joint>
    private val qClip = Quaternionf()
    private val qGoal = Quaternionf()
    private val vClip = Vector3f()
    private val vGoal = Vector3f()
    private val euler = Vector3f()

    init {
        val lookup = root.createPartLookup()
        joints = names.associateWith { name ->
            Joint(lookup.apply(name) ?: error("No part $name")).also {
                val rest = it.part.initialPose
                it.restQ.rotationZYX(rest.zRot, rest.yRot, rest.xRot)
                it.restV.set(rest.x, rest.y, rest.z)
            }
        }
    }

    /** Back to the rest pose, to build this frame's on. */
    fun begin() {
        for (j in joints.values) {
            j.q.set(j.restQ)
            j.v.zero()
        }
    }

    /** [clip] at [time] over what's there, by [weight]. */
    fun layer(clip: Clip, time: Float, weight: Float) {
        if (weight <= 0f) return
        for ((name, track) in clip.bones) {
            val j = joints[name] ?: continue
            track.rotation(clip, time, qClip)
            if (track.hasPosition) track.position(clip, time, vClip) else vClip.zero()
            if (track.add) {
                qGoal.set(j.q).mul(qClip)
                vGoal.set(j.v).add(vClip)
            } else {
                qGoal.set(j.restQ).mul(qClip)
                vGoal.set(vClip)
            }
            if (weight >= 1f) {
                j.q.set(qGoal)
                j.v.set(vGoal)
            } else {
                j.q.slerp(qGoal, weight)
                j.v.lerp(vGoal, weight)
            }
        }
    }

    /** The part's rotation as it stands (relative to its parent), to read or to change. */
    fun rotation(name: String): Quaternionf = joints.getValue(name).q

    /** The rotation of the parts from the first of [chain] down to its last, in the model's frame. */
    fun world(chain: List<String>, out: Quaternionf): Quaternionf {
        out.identity()
        for (name in chain) out.mul(joints.getValue(name).q)
        return out
    }

    /** Puts the pose on the model's parts. */
    fun finish() {
        for (j in joints.values) {
            j.part.x = j.restV.x + j.v.x
            j.part.y = j.restV.y + j.v.y
            j.part.z = j.restV.z + j.v.z
            j.q.getEulerAnglesZYX(euler)
            j.part.xRot = euler.x
            j.part.yRot = euler.y
            j.part.zRot = euler.z
        }
    }
}
