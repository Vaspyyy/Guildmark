package io.github.vaspyyy.guildmark.client.combat

import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.combat.Move
import io.github.vaspyyy.guildmark.combat.WeaponClass
import io.github.vaspyyy.guildmark.combat.Weapons
import io.github.vaspyyy.guildmark.magic.CatalystItem
import io.github.vaspyyy.guildmark.magic.School
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import kotlin.math.abs
import kotlin.math.cos
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
    /**
     * How a class's weapon sits in first person: where its grip is relative to vanilla's hand position (clips
     * turn the item about it), and the turn that lines its blade up with a sword's. The moves every weapon shares
     * were made for the sword and are turned to fit; each class's own moves were made for its weapon.
     * Worked out in art/scripts/weaponkit.py.
     */
    private class Hold(val grip: Vector3f, val fromSword: Quaternionf)

    private val SWORD = Hold(Vector3f(0.0706f, -0.0678f, -0.0371f), Quaternionf())
    private val MACE = Hold(Vector3f(0f, -0.1294f, -0.0654f), Quaternionf())
    private val HOLDS = mapOf(
        WeaponClass.HAMMER to MACE,
        WeaponClass.SPEAR to Hold(Vector3f(0.1956f, -0.072f, 0.0429f), Quaternionf(0.2651f, 0f, 0f, 0.9642f).normalize()),
    )

    private val itemState = ItemStackRenderState()
    private val flask: ItemStack by lazy { ItemStack(ModItems.WARDENS_FLASK.get()) }
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
        if (action.move == Move.DRINK && event.hand == InteractionHand.OFF_HAND) {
            event.isCanceled = true
            drink(event, player, action)
            return
        }
        val stack = event.itemStack
        if (stack.isEmpty) return
        val clip = Clips.get("first_person/" + action.move.anim) ?: return
        val main = event.hand == InteractionHand.MAIN_HAND
        // A cast moves the catalyst's hand by the clip's item track (mirrored in the off hand); the other hand rests
        val castHand = if (action.move.isSpell) (if (action.offhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND) else null
        if (castHand != null && event.hand != castHand) return
        val casting = castHand != null
        val bone = if (main || casting) "item" else "offhand"
        val track = clip.bones[bone] ?: return
        event.isCanceled = true
        val hold = when {
            // A staff sits in the hand like a sword, a censer like a mace (art/scripts/weaponkit.py)
            casting -> if ((stack.item as? CatalystItem)?.school == School.PRAYER) MACE else SWORD
            main -> Weapons.classOf(stack)?.let { HOLDS[it] } ?: SWORD
            else -> SWORD
        }
        // Casts and each class's own moves were made for what they're held with; the shared moves for the sword
        val fit = if (casting) null else hold.takeIf { WeaponClass.of(action.move) == null }

        val time = action.time(event.partialTick)
        val arm = if (event.hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
        // From the rest pose, or from the move this one cut short, into this move
        offset.zero()
        rotation.identity()
        val fromMove = action.from
        // Out of what the hand was doing (an off-hand cast's hand was doing nothing)
        if (time < clip.blendIn && fromMove != null && !(casting && action.offhand)) {
            val from = Clips.get("first_person/" + fromMove.anim)
            val fromTrack = from?.bones?.get(bone)
            if (from != null && fromTrack != null) {
                val w = from.weight(action.fromTime)
                sample(fromTrack, from, action.fromTime, if (fromMove.isSpell) null else hold.takeIf { WeaponClass.of(fromMove) == null })
                offset.set(goal).mul(w)
                rotation.slerp(sample, w)
            }
        }
        val weight = clip.weight(time)
        sample(track, clip, time, fit)
        offset.lerp(goal, weight)
        rotation.slerp(sample, weight)
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
        val grip = hold.grip
        pose.translate(side * grip.x, grip.y, grip.z)
        pose.rotate(rotation)
        pose.translate(-side * grip.x, -grip.y, -grip.z)
        val context = if (arm == HumanoidArm.RIGHT) ItemDisplayContext.FIRST_PERSON_RIGHT_HAND else ItemDisplayContext.FIRST_PERSON_LEFT_HAND
        minecraft.itemModelResolver.updateForTopItem(itemState, stack, context, player.level(), player, player.id + context.ordinal)
        itemState.submit(pose, event.submitNodeCollector, event.packedLight, OverlayTexture.NO_OVERLAY, 0)
        pose.popPose()
    }

    /** [track] at [time] into [goal] and [sample]; a sword's clip turned to fit [fit]'s weapon, when that's another. */
    private fun sample(track: Clip.Track, clip: Clip, time: Float, fit: Hold?) {
        track.position(clip, time, goal)
        track.rotation(clip, time, sample)
        if (fit == null || fit === SWORD) return
        // The weapon's grip goes where the sword's would, its blade along the sword's
        goal.add(SWORD.grip).sub(fit.grip)
        sample.mul(fit.fromSword)
    }

    /**
     * The Warden's Flask in the off hand, whatever the hand held: it comes up from below, tips to the
     * lips the way vanilla drinks a potion, bobs while the draught goes down, and drops away again.
     */
    private fun drink(event: RenderHandEvent, player: LocalPlayer, action: CombatClient.Action) {
        val time = action.time(event.partialTick)
        val length = Move.DRINK.length.toFloat()
        val raised = smooth(time / RAISE) * smooth((length - time) / LOWER)
        if (raised <= 0f) return
        val arm = player.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
        val pose = event.poseStack
        pose.pushPose()
        // Gulps while the draught goes down
        if (time > RAISE && time < Move.DRINK_AT + 4) pose.translate(0f, abs(cos(time / 4f * Mth.PI) * 0.1f), 0f)
        pose.translate(raised * 0.6f * side, raised * -0.5f, 0f)
        pose.rotateDegrees(Axis.YP, side * raised * 90f)
        pose.rotateDegrees(Axis.XP, raised * 10f)
        pose.rotateDegrees(Axis.ZP, side * raised * 30f)
        pose.translate(side * 0.56f, -0.52f - (1f - raised) * 0.6f, -0.72f)
        val context = if (arm == HumanoidArm.RIGHT) ItemDisplayContext.FIRST_PERSON_RIGHT_HAND else ItemDisplayContext.FIRST_PERSON_LEFT_HAND
        minecraft().itemModelResolver.updateForTopItem(itemState, flask, context, player.level(), player, player.id + context.ordinal)
        itemState.submit(pose, event.submitNodeCollector, event.packedLight, OverlayTexture.NO_OVERLAY, 0)
        pose.popPose()
    }

    private fun minecraft(): Minecraft = Minecraft.getInstance()

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Ticks the flask takes to come up, and to go down at the end. */
    private const val RAISE = 7f
    private const val LOWER = 7f
}
