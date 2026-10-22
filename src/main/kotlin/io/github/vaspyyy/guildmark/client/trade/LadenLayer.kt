package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.Model
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the laden look gets its art (living-world/trader/design.md, Part 3, "Your back"; build parts D1 and X2): the three back-packs of
 * art stream X2's `LadenPackMesh` (generated, as the caravans' `WagonMesh` is) with their textures under `textures/entity/trade/`.
 *
 * **What the animation reads of a pack's mesh.** Each pack is a model of its own, built in the player model's own space (pixels, y down,
 * the origin at the neck, the body's back at z = 2; the layer follows the body part, so the pack leans with a crouch, a swim and a
 * combat move, and stands a pixel off a chestplate). Every part below is optional and found by its name wherever it is in the tree; the
 * names are the same in each of the three:
 *
 * - `pack`: the whole load, pivoting where it hangs from the shoulders; it leans with the speeding up and slowing down, rolls and twists
 *   with the hips and lifts with each step. (Without one the whole model is moved instead.)
 * - `swing_0` to `swing_7`: things hung on it (a pot, the lantern, a coil of rope), each pivoting at its hook; they swing, out of step
 *   with one another, more than the pack does, and a sprint rattles them.
 * - `flap_0` to `flap_7`: loose cloth and strap ends, pivoting at where they are fixed; they ripple with the stride.
 *
 * **X2's packs pose themselves.** `LadenPackMesh` names its parts its own way (`pack_N` and `straps_N` under a `body`, and a `left_leg`; every
 * sack, tail, cup, lantern, pot and lid a part of its own) and brings its own motion with it, [LadenPackMesh.Rig]: the stride, the sprint's
 * rattle, the sneak's slide and fold, and the layer's springs (what [LadenLook] keeps of the starts, stops, turns and landings) added to them
 * and held where the pack leaves no room. A model with those parts is posed by the rig alone ([LadenModel.rigged]) and the generic motion
 * above is not used on it.
 */
internal object LadenArt {
    /** One back-pack: its mesh, the texture painted on it, and the texture of what glows on it (drawn lit over the first, or null for none). */
    class Pack(val mesh: () -> LayerDefinition, val texture: Identifier, val glow: Identifier? = null)

    private val PACK = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/trade/laden_pack.png")
    private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/trade/laden_pack_glow.png")

    /**
     * THE ONE CALL (stream X2): the three back-packs in order (a rolled blanket and a sack; a frame with two sacks and a hooked lantern, whose
     * glass is lit; a tall frame with sacks, a small barrel and pots), or null for none. With null nothing at all is done: no layer is added
     * to anyone, no sound is played, and the game looks as it did.
     */
    fun table(): List<Pack>? = listOf(
        Pack(LadenPackMesh::level1, PACK),
        Pack(LadenPackMesh::level2, PACK, GLOW),
        Pack(LadenPackMesh::level3, PACK),
    )

    /** The packs as baked for the layers added the last time the renderers were made (null for none: nothing is drawn). */
    var packs: List<LadenModel>? = null
        private set

    /** Adds the layer to every player renderer, if the packs' art is in. */
    fun addTo(event: EntityRenderersEvent.AddLayers) {
        packs = null
        val table = table() ?: return
        val models = table.mapIndexed { i, pack -> LadenModel(pack.mesh().bakeRoot(), i + 1) }
        val textures = table.map { it.texture }
        val glows = table.map { it.glow }
        for (skin in event.skins) {
            val renderer = event.getPlayerRenderer<AvatarRenderer<AbstractClientPlayer>>(skin) ?: continue
            renderer.addLayer(LadenLayer(renderer, models, textures, glows))
        }
        packs = models
    }
}

/**
 * What X2's rig poses a pack from ([LadenPackMesh.Rig.pose]): the walk the body is in, and the springs of [LadenLook] without any of the stride's
 * own terms (the rig has the stride inside it). The angles are in radians, [bob] in pixels, down.
 */
internal class LadenRigInput(
    val walkPos: Float,
    val walkSpeed: Float,
    val sprint: Float,
    val appear: Float,
    val tilt: Float,
    val thigh: Float,
    val pitch: Float,
    val roll: Float,
    val bob: Float,
    val dangle: Float,
)

/**
 * How a pack is posed for one drawing, in radians and pixels: the lean of the load, its roll and twist, the lift of the stride, the swing of
 * what hangs on it, and the rattle of a sprint (see [LadenModel]); or, for a pack that [LadenModel.rigged] poses itself, [rig].
 */
internal class LadenPose(
    val pitch: Float,
    val roll: Float,
    val twist: Float,
    val bob: Float,
    val dangle: Float,
    val shake: Float,
    val phase: Float,
    val gait: Float,
    val age: Float,
    val rig: LadenRigInput? = null,
)

/**
 * A back-pack of X2's, posed by [LadenPose] when it is drawn (see [LadenArt] for the parts it moves). The game queues a model to be drawn and
 * poses it just before it is, with the pose it was queued with, so everything a pose needs is in [LadenPose] and nothing is read from the player.
 *
 * [level] (1 to 3) is the pack's; a model with a `pack_<level>` and a `straps_<level>` under its `body` and a `left_leg` is X2's, and is posed by
 * [LadenPackMesh.Rig] (it shows and scales the pack itself as it comes up onto the back).
 */
internal class LadenModel(root: ModelPart, level: Int) : Model<LadenPose>(root, RenderTypes::entityCutout) {
    private val lookup = root.createPartLookup()
    private val pack: ModelPart? = lookup.apply("pack")
    private val swingers: List<ModelPart> = named("swing_")
    private val flappers: List<ModelPart> = named("flap_")
    private val rig: LadenPackMesh.Rig? =
        if (root.hasChild("body") && root.hasChild("left_leg") && lookup.apply("pack_$level") != null && lookup.apply("straps_$level") != null) {
            LadenPackMesh.Rig(root, level)
        } else {
            null
        }

    /** Whether X2's rig poses this pack: it then wants [LadenPose.rig], and brings the pack up onto the back itself. */
    val rigged: Boolean get() = rig != null

    private fun named(prefix: String): List<ModelPart> = (0 until MOST).mapNotNull { lookup.apply(prefix + it) }

    override fun setupAnim(pose: LadenPose) {
        resetPose()
        val input = pose.rig
        if (rig != null && input != null) {
            rig.pose(
                input.walkPos, input.walkSpeed, pose.age, input.sprint, input.appear, input.tilt, input.thigh,
                input.pitch, input.roll, input.bob, input.dangle,
            )
            return
        }
        val load = pack ?: root
        load.xRot += pose.pitch
        load.zRot += pose.roll
        load.yRot += pose.twist
        load.y += pose.bob
        for ((i, part) in swingers.withIndex()) {
            // Each hangs a little out of step with the rest, and is shaken more by a sprint
            part.xRot += pose.dangle * cos(i * 1.3f) + pose.shake * sin(i * 2.1f + 1f)
            part.zRot += pose.dangle * sin(i * 1.3f + 0.8f) * 0.7f + pose.shake * cos(i * 1.7f)
        }
        for ((i, part) in flappers.withIndex()) {
            // Cloth ripples along with the stride, and stirs a little on its own
            part.xRot += sin(pose.phase * 2f - i * 0.9f) * 0.18f * pose.gait + sin(pose.age * 0.15f + i * 1.9f) * 0.05f
        }
    }

    private companion object {
        const val MOST = 8
    }
}

/**
 * The pack on a trader's back (design Part 3, "Your back"; build part D1): it is drawn in the body's own space, so it goes wherever the body
 * does, and it sways with the stride (the hips' roll, the shoulders' twist, a lift with every step), leans as the wearer speeds up and slows
 * down and turns ([LadenLook]), and is shaken by a sprint. It comes up onto the back when the load reaches a level, goes down off it when
 * the load is gone, and pops onto it when the load grows into the next size. The level 2 pack's lantern is drawn a second time, lit, so that its
 * glass glows in the dark ([glows]). Nothing is drawn for a spectator, a glider or a player lying in a bed ([LadenClient.shows]), and nothing at
 * all while [LadenArt.table] gives no packs.
 */
internal class LadenLayer(
    parent: RenderLayerParent<AvatarRenderState, PlayerModel>,
    private val packs: List<LadenModel>,
    private val textures: List<Identifier>,
    private val glows: List<Identifier?>,
) : RenderLayer<AvatarRenderState, PlayerModel>(parent) {
    override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: AvatarRenderState, yRot: Float, xRot: Float) {
        val frame = state.getRenderData(LadenClient.FRAME) ?: return
        if (!LadenClient.shows(state)) return
        val model = packs.getOrNull(frame.level - 1) ?: return
        val texture = textures.getOrNull(frame.level - 1) ?: return
        val gait = state.walkAnimationSpeed.coerceIn(0f, 1f) * (1f + SPRINT_GAIT * frame.sprint)
        val phase = state.walkAnimationPos * STRIDE
        val crouch = if (state.isCrouching) 1f else 0f
        val pose = LadenPose(
            pitch = frame.pitch + (LEAN + LIFT * cos(phase * 2f)) * gait + CROUCH_LEAN * crouch,
            roll = frame.roll + cos(phase) * HIPS * gait,
            twist = sin(phase) * SHOULDERS * gait,
            bob = frame.bounce + abs(cos(phase)) * STEP * gait,
            dangle = frame.dangle + sin(phase * 2f + 0.6f) * HANG * gait,
            shake = frame.sprint * (sin(state.ageInTicks * 2.9f) * 0.05f + sin(state.ageInTicks * 4.3f + 1.3f) * 0.03f),
            phase = phase,
            gait = gait,
            age = state.ageInTicks,
            // X2's rig has the stride inside it and takes the springs alone. [LadenFrame.bounce] is positive for a lift (a landing pushes it
            // negative), the rig's bob is pixels down; the thigh is read now, off the body as it stands for this player (the model is shared)
            rig = if (model.rigged) {
                LadenRigInput(
                    walkPos = state.walkAnimationPos, walkSpeed = state.walkAnimationSpeed, sprint = frame.sprint, appear = frame.appear,
                    tilt = frame.tilt, thigh = parentModel.leftLeg.xRot, pitch = frame.pitch, roll = frame.roll, bob = -frame.bounce,
                    dangle = frame.dangle,
                )
            } else {
                null
            },
        )
        poseStack.pushPose()
        // The body's own place: it leans, crouches and swims, and the pack with it
        parentModel.body.translateAndRotate(poseStack)
        if (!state.chestEquipment.isEmpty) poseStack.translate(0f, 0f, ARMOUR)
        // X2's rig brings its pack up onto the back itself (it falls onto it as it grows); a pack without one is scaled whole
        val grow = frame.grow
        if (!model.rigged && grow != 1f) poseStack.scale(grow, grow, grow)
        val overlay = LivingEntityRenderer.getOverlayCoords(state, 0f)
        collector.submitModel(model, pose, poseStack, texture, light, overlay, state.outlineColor)
        // What glows on it is the same model in the same pose with the lit texture, drawn as a spider's eyes are (after everything else, not
        // darkened by the light about), tinted by the flame's flicker
        glows.getOrNull(frame.level - 1)?.let { glow ->
            collector.order(1).submitModel(
                model, pose, poseStack, RenderTypes.eyes(glow), light, overlay, LadenPackMesh.glowColour(state.ageInTicks), null, state.outlineColor,
            )
        }
        poseStack.popPose()
    }

    private companion object {
        /** The walk animation's step in radians per unit of its position, as the legs swing. */
        const val STRIDE = 0.6662f

        /** How much more of the stride's sway a sprint gives. */
        const val SPRINT_GAIT = 0.55f

        /** The load's steady lean forward when walking, and the lift of it twice a stride, in radians. */
        const val LEAN = 0.03f
        const val LIFT = 0.02f

        /** The extra lean over a crouch, the roll of the hips, the twist of the shoulders and the swing of what hangs on it, in radians. */
        const val CROUCH_LEAN = 0.05f
        const val HIPS = 0.05f
        const val SHOULDERS = 0.045f
        const val HANG = 0.09f

        /** The lift of each step, in pixels. */
        const val STEP = 0.55f

        /** A chestplate stands a pixel off the body. */
        const val ARMOUR = 1f / 16f
    }
}
