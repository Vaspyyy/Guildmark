package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.trade.carry.PackKit
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.Model
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.DonkeyRenderer
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.LlamaRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.animal.equine.Llama
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the pack animals' harness gets its art (living-world/trader/design.md, Part 3, "A pack animal"; build parts D1 and X2): art
 * stream X2's `PackHarnessMesh` (generated, as the caravans' `WagonMesh` is), one mesh each for a donkey, a mule and a llama, with the
 * blanket's six colours as six textures under `textures/entity/trade/`.
 *
 * **What the animation reads of a harness mesh.** Each is built in the animal's own model space (the space of `DonkeyModel` and
 * `LlamaModel`'s meshes: pixels, y down, the animal facing -z, the harness sitting where it sits on that animal at rest); the layer follows
 * the animal's body part from there, so the harness rears with the animal. Every part below is optional and found by its name wherever it is
 * in the tree:
 *
 * - `pannier_l` and `pannier_r`: the baskets, each pivoting at the strap they hang from; they swing with the stride and as the animal
 *   speeds up, slows down and turns, less the fuller they are, and bulge as they fill.
 * - `fill_0` to `fill_3`: if the baskets come in four fullnesses, one group for each; only the one the panniers are at shows (and the
 *   baskets above are then left alone).
 * - `bell`: the brass bell on the breast strap, pivoting at the strap; it swings with every stride and hangs still when the animal does.
 * - `flap_0` to `flap_7`: tassels, strap ends and the blanket's corners, pivoting where they are fixed; they ripple with the stride.
 * - `goods_0` to `goods_2`: empty parts (no cubes) at the root of the tree, marking where the goods that show on top of the panniers
 *   (up to three, [PackKit.SHOWN]) are set; without them they are set above the game's own two chest boxes.
 */
internal object PackHarnessArt {
    /** One animal's harness: its mesh, and the texture of each blanket colour (0 to [PackKit.COLOURS] - 1). */
    class Harness(val mesh: () -> LayerDefinition, val texture: (Int) -> Identifier)

    /** The three harnesses, and whether the game's own chest boxes are taken off the animal because the baskets stand in their place. */
    class Table(val donkey: Harness, val mule: Harness, val llama: Harness, val hidesChests: Boolean = true)

    /**
     * THE ONE CALL (stream X2): the harness for a donkey, a mule and a llama, or null while `PackHarnessMesh` has not landed on main. With
     * null nothing at all is done: no layer is added to any animal, its chest boxes stay and the game looks as it did. When it lands this is
     * the one line to change, to a `Table(Harness(PackHarnessMesh::<its function for the donkey>) { colour -> <the texture of that colour> }, ...)`.
     */
    fun table(): Table? = null

    /** The table the layers were added with the last time the renderers were made (null for none: nothing is drawn). */
    var baked: Table? = null
        private set

    /** Adds the layer to the donkey's, the mule's and the llama's renderers, if the harness's art is in. */
    fun addTo(event: EntityRenderersEvent.AddLayers) {
        baked = null
        val table = table() ?: return
        var added = false
        event.getRenderer<AbstractChestedHorse, DonkeyRenderer<AbstractChestedHorse>>(EntityTypes.DONKEY)?.let {
            attach(it, HarnessKind.DONKEY, table.donkey)
            added = true
        }
        event.getRenderer<AbstractChestedHorse, DonkeyRenderer<AbstractChestedHorse>>(EntityTypes.MULE)?.let {
            attach(it, HarnessKind.MULE, table.mule)
            added = true
        }
        event.getRenderer<Llama, LlamaRenderer>(EntityTypes.LLAMA)?.let {
            attach(it, HarnessKind.LLAMA, table.llama)
            added = true
        }
        if (added) baked = table
    }

    private fun <S : LivingEntityRenderState, M : EntityModel<in S>> attach(renderer: LivingEntityRenderer<*, S, M>, kind: HarnessKind, harness: Harness) {
        renderer.addLayer(PackHarnessLayer(renderer, kind, PackHarnessModel(harness.mesh().bakeRoot()), harness.texture))
    }
}

/**
 * How a harness is posed for one drawing, in radians: how the baskets are swinging (forward and back, and to the side), how full they are
 * and how they squash as they take a change, how the bell hangs, and the stride the animal is in.
 */
internal class HarnessPose(
    val pitch: Float,
    val roll: Float,
    val dangle: Float,
    val bell: Float,
    val fill: Float,
    val pop: Float,
    val phase: Float,
    val gait: Float,
    val age: Float,
)

/** A harness of X2's, posed by [HarnessPose] when it is drawn (see [PackHarnessArt] for the parts it moves). */
internal class PackHarnessModel(root: ModelPart) : Model<HarnessPose>(root, RenderTypes::entityCutout) {
    private val lookup = root.createPartLookup()
    private val panniers: List<ModelPart> = listOfNotNull(lookup.apply("pannier_l"), lookup.apply("pannier_r"))
    private val fills: List<ModelPart?> = (0..3).map { lookup.apply("fill_$it") }
    private val stepped = fills.any { it != null }
    private val bell: ModelPart? = lookup.apply("bell")
    private val flappers: List<ModelPart> = (0 until MOST).mapNotNull { lookup.apply("flap_$it") }

    /** Where the mesh sets the goods that show (as `goods_0`...), in the animal's model space in pixels; none if it does not say. */
    fun anchors(): List<Vector3f> = (0 until PackKit.SHOWN).mapNotNull { lookup.apply("goods_$it") }.map { Vector3f(it.initialPose.x(), it.initialPose.y(), it.initialPose.z()) }

    override fun setupAnim(pose: HarnessPose) {
        resetPose()
        if (stepped) {
            // Four fullnesses: the one the panniers are at, squashed as it is taken
            val shown = pose.fill.roundToInt().coerceIn(0, 3)
            for ((i, group) in fills.withIndex()) {
                if (group == null) continue
                group.visible = i == shown
                if (i == shown) squash(group, pose.pop)
            }
        }
        for (basket in panniers) {
            // Hung from a strap at the top: the whole basket swings, and the fuller it is the heavier it hangs
            basket.xRot += pose.pitch * 1.2f
            basket.zRot += pose.roll * 1.4f
            if (!stepped) {
                val bulge = 1f + 0.055f * pose.fill
                basket.xScale = bulge + 0.04f * pose.pop
                basket.zScale = bulge + 0.04f * pose.pop
                basket.yScale = 1f + 0.035f * pose.fill - 0.03f * pose.pop
            }
        }
        bell?.let {
            it.xRot += pose.bell
            it.zRot += pose.bell * 0.35f
        }
        for ((i, part) in flappers.withIndex()) {
            part.xRot += sin(pose.phase * 2f - i * 0.9f) * 0.2f * pose.gait + sin(pose.age * 0.13f + i * 1.7f) * 0.05f + pose.dangle * 0.5f
        }
    }

    private fun squash(part: ModelPart, pop: Float) {
        part.xScale = 1f + 0.04f * pop
        part.zScale = 1f + 0.04f * pop
        part.yScale = 1f - 0.03f * pop
    }

    private companion object {
        const val MOST = 8
    }
}

/**
 * The harness on a pack animal (design Part 3, "A pack animal"; build part D1): a blanket and wicker panniers that follow the animal's
 * body, swing with its stride and with its starts, stops and turns, bulge in four steps as the chest fills, with a squash when a step is
 * taken; a bell on the breast strap that is knocked by every stride and hangs still when the animal stands; and up to three of the goods in
 * the chest set on top, bobbing with the animal. Nothing is drawn while [PackHarnessArt.table] gives no harness.
 */
internal class PackHarnessLayer<S : LivingEntityRenderState, M : EntityModel<in S>>(
    parent: RenderLayerParent<S, M>,
    private val kind: HarnessKind,
    private val model: PackHarnessModel,
    private val textureOf: (Int) -> Identifier,
) : RenderLayer<S, M>(parent) {
    private val body: ModelPart? = parentModel.root().createPartLookup().apply("body")
    private val anchors: List<Vector3f> = model.anchors().ifEmpty { chestAnchors() }
    private val turn = Quaternionf()
    private val rest = Quaternionf()

    override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: S, yRot: Float, xRot: Float) {
        val frame = state.getRenderData(PackHarnessClient.FRAME) ?: return
        if (frame.kind != kind) return
        val gait = state.walkAnimationSpeed.coerceIn(0f, 1f) * (1f + TROT_GAIT * frame.trot)
        val phase = state.walkAnimationPos * STRIDE
        // The fuller the baskets, the heavier they hang
        val heavy = 1f - 0.1f * frame.fill
        val pose = HarnessPose(
            pitch = (frame.pitch + sin(phase * 2f) * LIFT * gait) * heavy,
            roll = (frame.roll + cos(phase) * SHIFT * gait) * heavy,
            dangle = frame.dangle + sin(phase * 2f + 0.7f) * HANG * gait,
            bell = frame.bell,
            fill = frame.fill,
            pop = frame.pop,
            phase = phase,
            gait = gait,
            age = state.ageInTicks,
        )
        val overlay = LivingEntityRenderer.getOverlayCoords(state, 0f)
        poseStack.pushPose()
        follow(poseStack)
        collector.submitModel(model, pose, poseStack, textureOf(frame.colour), light, overlay, state.outlineColor)
        for ((i, good) in frame.goods.withIndex()) {
            val at = anchors.getOrNull(i) ?: break
            poseStack.pushPose()
            // On top of the basket, bobbing with the animal, turned out to the side it is on so that it shows from there
            poseStack.translate(at.x / 16f, (at.y + frame.bounce * 0.6f) / 16f, at.z / 16f)
            // The model's y runs down: turned over, the item stands the right way up
            poseStack.rotate(Axis.ZP, Math.PI.toFloat())
            poseStack.rotate(Axis.YP, if (at.x > 0f) -HALF_PI else HALF_PI)
            poseStack.rotate(Axis.ZP, (i - 1) * 0.22f + pose.pitch * 0.5f)
            val scale = GOOD_SIZE / 16f / good.extent
            poseStack.scale(scale, scale, scale)
            poseStack.translate(-good.middle.x, -good.middle.y, -good.middle.z)
            good.model.submit(poseStack, collector, light, overlay, state.outlineColor)
            poseStack.popPose()
        }
        poseStack.popPose()
    }

    /**
     * Into the place the animal's body is in now, from the one it is built for (the body at rest): moved as far as it has moved and turned as
     * far as it has turned about where it hangs, so the harness rears with the animal. The harness is built in the model's own space.
     */
    private fun follow(poseStack: PoseStack) {
        val part = body ?: return
        val base = part.initialPose
        turn.rotationZYX(part.zRot, part.yRot, part.xRot)
        rest.rotationZYX(base.zRot(), base.yRot(), base.xRot())
        turn.mul(rest.invert())
        poseStack.translate(part.x / 16f, part.y / 16f, part.z / 16f)
        poseStack.rotate(turn)
        poseStack.translate(-base.x() / 16f, -base.y() / 16f, -base.z() / 16f)
    }

    /**
     * Where the goods are set when the mesh does not say: a little above the game's own two chest boxes (which hang from the animal's body
     * or stand at the root of its model), and one between them on the blanket. None if the model has no chests.
     */
    private fun chestAnchors(): List<Vector3f> {
        val lookup = parentModel.root().createPartLookup()
        val parent = lookup.apply("body")
        val at = ArrayList<Vector3f>(2)
        for (name in CHESTS) {
            val chest = lookup.apply(name) ?: return emptyList()
            val pose = chest.initialPose
            val place = Vector3f(pose.x(), pose.y() - ABOVE, pose.z())
            if (parent != null && parent !== chest && parent.hasChild(name)) {
                val hung = parent.initialPose
                Quaternionf().rotationZYX(hung.zRot(), hung.yRot(), hung.xRot()).transform(place)
                place.add(hung.x(), hung.y(), hung.z())
            }
            at.add(place)
        }
        // The one between them first: the goods are listed dearest first, and that one is the one that shows from both sides
        return listOf(Vector3f(at[0]).add(at[1]).mul(0.5f).sub(0f, MIDDLE_UP, 0f), at[0], at[1])
    }

    private companion object {
        val CHESTS = listOf("left_chest", "right_chest")

        /** The walk animation's step in radians per unit of its position, as the legs swing. */
        const val STRIDE = 0.6662f

        /** How much more of the stride's sway a trot gives. */
        const val TROT_GAIT = 0.5f

        /** The lift of the baskets twice a stride, the shift of the body over the planted feet and the swing of the tassels, in radians. */
        const val LIFT = 0.03f
        const val SHIFT = 0.05f
        const val HANG = 0.1f

        /** How far above a chest box's top the goods are set, how much higher the one between them stands, and how big they are (pixels). */
        const val ABOVE = 1.5f
        const val MIDDLE_UP = 1.5f
        const val GOOD_SIZE = 6f

        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}
