package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.client.combat.CombatArmPose
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.vessel.CookTool
import io.github.vaspyyy.guildmark.cooking.vessel.CookToolItem
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.util.Mth
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The cook's body in motion (living-world/cook/design.md 1.8, 3.3, 4.3): the avatar modifier that plays what
 * [CookActionHandler] says each player is doing, through the combat animator's frame, as the ship's helm does. It is
 * registered after the combat animator's, the cutscenes', the ship's and the sea's, and gives way at once to a frame one
 * of them filled; a combat move that cuts a clip short eases out of the clip's last pose instead of snapping from it.
 *
 * A clip eases in from the pose the body had and out to it again: from standing, or from a crouch (the game's own
 * crouch is laid over the arms after the clip has posed them, so while a clip plays the crouch is drawn as a pose under
 * it, [CROUCH], and the body rises out of it and sinks back as the clip comes and goes). While it plays, the hand that
 * works shows the tool the clip is made for (the real one when it is in the hand), the other hand a bowl to serve into
 * or nothing when the clip has it busy, and the game's own stacks are back the frame the clip is over.
 *
 * **Lining up with the station.** A clip was made against a stand-in station a set distance ahead and a little to the
 * right of the body (art/scripts/cook_anims.py). The body turns, by as much as the clip shows, so that the point of the
 * station the clip works at (the pot's mouth, the firebox, the chain, the crank) lies to the right by the clip's own
 * lateral offset: the cook faces the station, a pot's middle off the right hand. The head keeps looking where the player
 * looks. Nothing moves the body nearer or further: from further off than the stand-in, a stir works the air in front of
 * the pot. Eating and the shiver turn to nothing.
 */
object CookPose {
    private val armPose: HumanoidModel.ArmPose by lazy { CombatArmPose.PROXY.value }

    /** The most the body turns to a station, and the most the head may then be turned from it, degrees. */
    private const val MAX_TURN = 75f
    private const val HEAD_MAX = 70f

    /** Nearer than this (blocks) the station's bearing means nothing, and the body does not turn. */
    private const val NEAR = 0.3

    /** Ticks a clip takes, at least, to lift the body out of a crouch. */
    private const val CROUCH_IN = 4f

    /** Ticks into the add and the feed at which the hand lets go of what it held (M3's marks). */
    private const val ADD_RELEASE = 4.9f
    private const val FEED_RELEASE = 5.4f

    private val ladle: ItemStack by lazy { ItemStack(CookRegistry.TOOLS.getValue(CookTool.LADLE).get()) }
    private val knife: ItemStack by lazy { ItemStack(CookRegistry.TOOLS.getValue(CookTool.KNIFE).get()) }
    private val turner: ItemStack by lazy { ItemStack(CookRegistry.TOOLS.getValue(CookTool.TURNER).get()) }
    private val bowl: ItemStack by lazy { ItemStack(Items.BOWL) }
    private val bread: ItemStack by lazy { ItemStack(Items.BREAD) }
    private val spoon: ItemStack by lazy { ItemStack(CookRegistry.WOODEN_SPOON.get()) }

    val AVATAR: AvatarRenderStateModifier = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
            val body = CookActionHandler.body(entity.id) ?: return
            val player = entity as? Player ?: return
            if (!Cooking.enabled()) return
            val now = CookActionHandler.now(state.partialTick)
            val filled = state.getRenderData(CombatAnimator.FRAME)
            if (filled != null) {
                combat(player, state, body, filled, now)
                return
            }
            pose(player, state, body, now)
        }
    }

    // ---- A frame someone else filled ----

    /**
     * The frame is filled already. The ship's and the sea's are theirs to keep. A combat move's takes the body: the cook clip
     * stops, and while the move comes in it eases out of the clip's last pose, the body turning back from the station as it does.
     */
    private fun combat(player: Player, state: AvatarRenderState, body: CookActionHandler.Body, filled: CombatAnimator.Frame, now: Float) {
        val move = if (CombatClient.active()) CombatClient.actionOf(player) else null
        if (move == null) return
        if (body.play != null) CookActionHandler.cut(player, now, move)
        val cut = body.cut ?: return
        if (cut.by !== move || filled.from != null || filled.time >= filled.clip.blendIn) return
        val from = CombatAnimator.Frame(cut.clip, cut.time, cut.weight, 0f, 0f, 0f, null, false, cut.mirror)
        state.setRenderData(
            CombatAnimator.FRAME,
            CombatAnimator.Frame(filled.clip, filled.time, filled.weight, filled.yaw, filled.tremble, filled.seed, from, filled.offHandFull, filled.mirror),
        )
        cut.target?.let { turn(player, state, it, side(cut.action), cut.mirror, cut.weight * (1f - filled.weight)) }
    }

    // ---- The cook's own frame ----

    private fun pose(player: Player, state: AvatarRenderState, body: CookActionHandler.Body, now: Float) {
        val play = body.play?.takeIf { !it.over(now) }
        val from = body.from?.takeIf { !it.over(now) }
        val main = play ?: from ?: return
        val under = if (play != null && from != null) from.weight(now) else 0f
        val time = main.time(now)
        val crouching = state.isCrouching
        // Rising out of a crouch is more of a move than coming out of standing: it takes a little longer
        val rise = if (crouching && under <= 0.001f && !main.loop) CookActionHandler.smooth(time / CROUCH_IN) else 1f
        val w = main.weight(now) * rise
        if (w <= 0.001f && under <= 0.001f) return
        val mirror = CookActionHandler.mirror(player, main)
        val fromFrame = when {
            under > 0.001f && from != null -> CombatAnimator.Frame(shape(from), from.time(now), under, 0f, 0f, 0f, null, false, CookActionHandler.mirror(player, from))
            crouching -> CROUCH_FRAME
            else -> null
        }
        state.setRenderData(CombatAnimator.FRAME, CombatAnimator.Frame(shape(main), time, w, 0f, 0f, state.ageInTicks, fromFrame, false, mirror))
        state.rightArmPose = armPose
        state.leftArmPose = armPose
        state.swingAnimation = 0f
        // The crouch is the frame's own pose under the clip now (CROUCH), not the game's laid over it
        state.isCrouching = false

        val target = main.target
        // A screen draws the local player face on (the inventory's figure): turned to a station in the world, it would twist
        if (target != null && !(player === Minecraft.getInstance().player && Minecraft.getInstance().gui.screen() != null)) {
            val share = if (fromFrame != null && fromFrame !== CROUCH_FRAME && from?.target != null) w + under * (1f - w) else w
            turn(player, state, target, side(main.action), mirror, share)
        }
        items(player, state, main, time, mirror)
    }

    /** [play]'s clip as drawn: a meal keeps the legs the game gives, so a cook who walks while eating walks. */
    private fun shape(play: CookActionHandler.Play): Clip = if (CookActionHandler.isEating(play.action)) walking(play.clip) else play.clip

    private val legless = HashMap<Clip, Clip>()

    private fun walking(clip: Clip): Clip {
        legless[clip]?.let { return it }
        // Reloaded clips are new objects: the old ones are let go
        if (legless.size > 16) legless.clear()
        val made = Clip(clip.length, clip.rate, clip.blendIn, clip.blendOut, clip.bones - "right_leg" - "left_leg")
        legless[clip] = made
        return made
    }

    // ---- Turning to the station ----

    /** How far right of the body's middle the station's working point sits in [action]'s clip, pixels (cook_anims.py's stand-in stations). */
    private fun side(action: CookAction): Float = when (action) {
        CookAction.CHOP, CookAction.SLICE, CookAction.FLIP -> 3f
        CookAction.SERVE_PEEL -> 5f
        CookAction.CARVE -> 1f
        CookAction.TURN -> 3.5f
        else -> 4.5f
    }

    /**
     * Turns the body, by [share] of the way, so that [target] lies [px] pixels to its right (to its left, [mirror]ed), and
     * turns the head back by as much, so it still looks where it looked.
     */
    private fun turn(player: Player, state: AvatarRenderState, target: Vec3, px: Float, mirror: Boolean, share: Float) {
        if (share <= 0f) return
        val at = player.getPosition(state.partialTick)
        val dx = target.x - at.x
        val dz = target.z - at.z
        val dist = sqrt(dx * dx + dz * dz)
        if (dist < NEAR) return
        val bearing = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val lean = Math.toDegrees(asin((px / 16.0 / dist).coerceAtMost(0.9))).toFloat()
        val goal = if (mirror) bearing + lean else bearing - lean
        var delta = Mth.wrapDegrees(goal - state.bodyRot).coerceIn(-MAX_TURN, MAX_TURN)
        delta = delta.coerceIn(state.yRot - HEAD_MAX, state.yRot + HEAD_MAX)
        val d = delta * share
        state.bodyRot += d
        state.yRot -= d
    }

    // ---- What the hands hold ----

    /**
     * What the working hand shows at [time] into [play], or null for what it really holds: the tool the clip is made for (the
     * real one if it is in the hand), what an add or a feed lets go of until it does, the dish (a bowl or a loaf stands in
     * when the hand has none yet, as at a Plate), the Wooden Spoon for a meal eaten with one (the dish is in the other hand:
     * [other]), and nothing while the cook hugs themselves.
     */
    fun working(player: Player, play: CookActionHandler.Play, time: Float): ItemStack? {
        val real = player.getItemInHand(play.hand)
        return when (play.action) {
            CookAction.STIR, CookAction.TASTE, CookAction.SKIM, CookAction.SERVE -> if (isTool(real, CookTool.LADLE)) null else ladle
            CookAction.CHOP, CookAction.CARVE, CookAction.SLICE -> if (isTool(real, CookTool.KNIFE)) null else knife
            CookAction.FLIP, CookAction.SERVE_PEEL -> if (isTool(real, CookTool.TURNER)) null else turner
            CookAction.ADD -> if (time < ADD_RELEASE && !ItemStack.matches(play.held, real)) play.held else null
            CookAction.FEED -> if (time < FEED_RELEASE && !ItemStack.matches(play.held, real)) play.held else null
            CookAction.EAT_SPOON -> spoon
            CookAction.DRINK -> if (real.item is DishItem) null else bowl
            CookAction.EAT_BITE -> if (real.item is DishItem) null else bread
            CookAction.SHIVER -> ItemStack.EMPTY
            CookAction.EASE, CookAction.TURN -> null
        }
    }

    /**
     * What the other hand shows during [play], or null for what it really holds: the bowl a serve fills, the dish a meal is
     * spooned from (the one in the working hand, which shows the spoon; a bowl stands in when that hand has none, as at a
     * Plate), and nothing while the clip has that hand busy (on the chain, the crank or the food, round the bowl, hugging).
     */
    fun other(player: Player, play: CookActionHandler.Play): ItemStack? = when (play.action) {
        CookAction.SERVE -> bowl
        CookAction.EAT_SPOON -> player.getItemInHand(play.hand).takeIf { it.item is DishItem } ?: bowl
        CookAction.EASE, CookAction.CHOP, CookAction.CARVE, CookAction.SLICE, CookAction.TURN, CookAction.DRINK, CookAction.SHIVER -> ItemStack.EMPTY
        else -> null
    }

    private fun isTool(stack: ItemStack, want: CookTool): Boolean {
        val tool = (stack.item as? CookToolItem)?.tool ?: return false
        return if (want == CookTool.LADLE) tool == CookTool.LADLE || tool == CookTool.GILDED_LADLE else tool == want
    }

    private fun items(player: Player, state: AvatarRenderState, play: CookActionHandler.Play, time: Float, mirror: Boolean) {
        working(player, play, time)?.let { hold(player, state, !mirror, it) }
        other(player, play)?.let { hold(player, state, mirror, it) }
    }

    /** Shows [stack] in the [right] (else left) hand for this frame. */
    private fun hold(player: Player, state: AvatarRenderState, right: Boolean, stack: ItemStack) {
        val shown = if (right) state.rightHandItemState else state.leftHandItemState
        if (stack.isEmpty) {
            shown.clear()
        } else {
            val context = if (right) ItemDisplayContext.THIRD_PERSON_RIGHT_HAND else ItemDisplayContext.THIRD_PERSON_LEFT_HAND
            Minecraft.getInstance().itemModelResolver.updateForLiving(shown, stack, context, player)
        }
        if (right) state.rightHandItemStack = stack else state.leftHandItemStack = stack
    }

    // ---- The crouch, as a pose under the clip ----

    /**
     * The game's crouch (HumanoidModel.setupAnim: the body leant 0.5 radian and dropped 3.2 pixels, the arms dropped as far
     * and swung 0.4 radian, the legs back 4, the head down 4.2, and the whole model 2 pixels down) as the combat skeleton's
     * pose: the torso leant about the hips, the arms' pivots put back where the game puts them, the legs back, the root down.
     * The body and the head share a pivot here, so each is half a pixel from where the game draws it.
     */
    private val CROUCH: Clip by lazy {
        val lean = 0.5f
        val still = floatArrayOf(0f, 0f, 0f, 1f)
        Clip(
            1f, 1, 0f, 0f,
            mapOf(
                // Leant about the hips, then moved so the shoulders come down 3.7 pixels (the game: the body 3.2, the head 4.2)
                "torso" to Clip.Track(false, floatArrayOf(sin(lean / 2f), 0f, 0f, cos(lean / 2f)), floatArrayOf(0f, 2.2308f, 5.7531f)),
                // The torso's lean, less a tenth, on the game's swing: its 0.4; the pivot where the game drops it, 3.2 down
                "right_arm" to Clip.Track(true, floatArrayOf(sin(-0.05f), 0f, 0f, cos(-0.05f)), floatArrayOf(0f, -0.6835f, -0.7192f)),
                "left_arm" to Clip.Track(true, floatArrayOf(sin(-0.05f), 0f, 0f, cos(-0.05f)), floatArrayOf(0f, -0.6835f, -0.7192f)),
                "right_leg" to Clip.Track(true, still, floatArrayOf(0f, 0f, 4f)),
                "left_leg" to Clip.Track(true, still, floatArrayOf(0f, 0f, 4f)),
                // The game drops a crouching player 2/16 of a block; the model is drawn at 0.9375 of a pixel to the pixel
                "root" to Clip.Track(false, still, floatArrayOf(0f, 2f / 0.9375f, 0f)),
            ),
        )
    }

    private val CROUCH_FRAME: CombatAnimator.Frame by lazy { CombatAnimator.Frame(CROUCH, 0f, 1f, 0f, 0f, 0f, null) }
}
