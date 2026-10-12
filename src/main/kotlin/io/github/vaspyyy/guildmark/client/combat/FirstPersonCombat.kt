package io.github.vaspyyy.guildmark.client.combat

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemDisplayContext
import net.neoforged.neoforge.client.event.RenderHandEvent
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Combat moves in first person. The body can't be seen, so the held items carry the move: each hand's
 * item follows the "item" or "offhand" bone of the move's first-person clip, turning about the grip.
 */
object FirstPersonCombat {
    /** Where a sword's grip sits relative to vanilla's hand position; clips turn the item about it. */
    private val GRIP = Vector3f(0.0706f, -0.0678f, -0.0371f)

    private val itemState = ItemStackRenderState()
    private val rotation = Quaternionf()
    private val sample = Quaternionf()
    private val offset = Vector3f()
    private val goal = Vector3f()

    fun onRenderHand(event: RenderHandEvent) {
        if (!CombatClient.active()) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val action = CombatClient.action ?: return
        if (action.done()) return
        val stack = event.itemStack
        if (stack.isEmpty) return
        val clip = Clips.get("first_person/" + action.move.anim) ?: return
        val bone = if (event.hand == InteractionHand.MAIN_HAND) "item" else "offhand"
        val track = clip.bones[bone] ?: return
        event.isCanceled = true

        val time = action.time(event.partialTick)
        val arm = if (event.hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
        // From the rest pose, or from the move this one cut short, into this move
        offset.zero()
        rotation.identity()
        if (time < clip.blendIn) {
            val from = action.from?.let { Clips.get("first_person/" + it.anim) }
            val fromTrack = from?.bones?.get(bone)
            if (from != null && fromTrack != null) {
                val w = from.weight(action.fromTime)
                offset.set(fromTrack.position(from, action.fromTime, goal)).mul(w)
                rotation.slerp(fromTrack.rotation(from, action.fromTime, sample), w)
            }
        }
        val weight = clip.weight(time)
        offset.lerp(track.position(clip, time, goal), weight)
        rotation.slerp(track.rotation(clip, time, sample), weight)
        if (side < 0f) rotation.set(rotation.x, -rotation.y, -rotation.z, rotation.w)
        if (action.charging && action.charge > 0) {
            val shake = action.chargeFraction() * 0.012f
            val age = player.tickCount + event.partialTick
            offset.add(kotlin.math.sin(age * 5.3f) * shake, kotlin.math.sin(age * 6.7f) * shake, 0f)
        }

        val pose = event.poseStack
        pose.pushPose()
        pose.translate(side * 0.56f, -0.52f, -0.72f)
        pose.translate(side * offset.x, offset.y, offset.z)
        pose.translate(side * GRIP.x, GRIP.y, GRIP.z)
        pose.rotate(rotation)
        pose.translate(-side * GRIP.x, -GRIP.y, -GRIP.z)
        val context = if (arm == HumanoidArm.RIGHT) ItemDisplayContext.FIRST_PERSON_RIGHT_HAND else ItemDisplayContext.FIRST_PERSON_LEFT_HAND
        minecraft.itemModelResolver.updateForTopItem(itemState, stack, context, player.level(), player, player.id + context.ordinal)
        itemState.submit(pose, event.submitNodeCollector, event.packedLight, OverlayTexture.NO_OVERLAY, 0)
        pose.popPose()
    }
}
