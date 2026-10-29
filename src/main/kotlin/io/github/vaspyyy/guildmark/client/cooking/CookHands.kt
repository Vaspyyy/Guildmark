package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.Cooking
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemDisplayContext
import net.neoforged.neoforge.client.event.RenderHandEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * The cook's hands in first person (living-world/cook/design.md 1.8, 11.3; the option Cooking in First Person): the ten
 * first-person clips move what the hands hold in view, as the combat moves do (`FirstPersonCombat`, whose file this does
 * not touch: this is its own listener, after it, built like its flask's drink). The working hand's item follows the clip's
 * `item` bone, the other hand's its `offhand` bone, each turned about the grip M3 worked out for it ([CookGrips]; a dish or
 * anything else is held by its middle, [CookGrips.FLAT]). What a hand shows is what it shows in third person ([CookPose]):
 * the tool, the bowl held out to be served into, nothing in the spoon hand. A clip with no first-person side, and a hand
 * its clip leaves alone, keep the game's own swing; so does everything with the option off. A combat move wins at once.
 */
object CookHands {
    private val itemState = ItemStackRenderState()
    private val rotation = Quaternionf()
    private val sample = Quaternionf()
    private val offset = Vector3f()
    private val goal = Vector3f()

    fun register() {
        NeoForge.EVENT_BUS.addListener(RenderHandEvent::class.java, ::onRenderHand)
    }

    private fun optionOn(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.COOK_HANDS.get()

    private fun clip(action: CookAction): Clip? = Clips.get("first_person/" + CookActions.clip(action))

    private fun onRenderHand(event: RenderHandEvent) {
        if (!optionOn() || !Cooking.enabled()) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val body = CookActionHandler.body(player.id) ?: return
        val now = CookActionHandler.now(event.partialTick)
        if (CombatClient.active()) {
            val move = CombatClient.action
            if (move != null && !move.done()) {
                CookActionHandler.cut(player, now, move)
                return
            }
        }
        val play = body.play?.takeIf { !it.over(now) }
        val from = body.from?.takeIf { !it.over(now) }
        val main = play ?: from ?: return
        val clip = clip(main.action) ?: return
        val working = event.hand == main.hand
        val bone = if (working) "item" else "offhand"
        val track = clip.bones[bone] ?: return
        val time = main.time(now)
        val stack = (if (working) CookPose.working(player, main, time) else CookPose.other(main)) ?: event.itemStack
        if (stack.isEmpty) {
            // The spoon hand of a meal holds nothing to draw: what it really holds is put away for the clip. An empty working hand is the game's.
            if (!working) event.isCanceled = true
            return
        }
        event.isCanceled = true

        // Out of the clip this one cut short (if the same hand worked it), into this one
        offset.zero()
        rotation.identity()
        if (play != null && from != null && from.hand == main.hand) {
            val fromClip = clip(from.action)
            val fromTrack = fromClip?.bones?.get(bone)
            if (fromClip != null && fromTrack != null) {
                val w = from.weight(now)
                sample(fromTrack, fromClip, from.time(now))
                offset.set(goal).mul(w)
                rotation.slerp(sample, w)
            }
        }
        val weight = main.weight(now)
        sample(track, clip, time)
        offset.lerp(goal, weight)
        rotation.slerp(sample, weight)

        val arm = if (event.hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
        if (side < 0f) rotation.set(rotation.x, -rotation.y, -rotation.z, rotation.w)

        val grip = CookGrips.grip(stack.item) ?: CookGrips.FLAT
        val pose = event.poseStack
        pose.pushPose()
        pose.translate(side * 0.56f, -0.52f, -0.72f)
        pose.translate(side * offset.x, offset.y, offset.z)
        pose.translate(side * grip.x, grip.y, grip.z)
        pose.rotate(rotation)
        pose.translate(-side * grip.x, -grip.y, -grip.z)
        val context = if (arm == HumanoidArm.RIGHT) ItemDisplayContext.FIRST_PERSON_RIGHT_HAND else ItemDisplayContext.FIRST_PERSON_LEFT_HAND
        minecraft.itemModelResolver.updateForTopItem(itemState, stack, context, player.level(), player, player.id + context.ordinal)
        itemState.submit(pose, event.submitNodeCollector, event.packedLight, OverlayTexture.NO_OVERLAY, 0)
        pose.popPose()
    }

    /** [track] at [time] into [goal] (the grip's offset from the game's hold, blocks) and [sample] (the turn about it). */
    private fun sample(track: Clip.Track, clip: Clip, time: Float) {
        track.position(clip, time, goal)
        track.rotation(clip, time, sample)
    }
}
