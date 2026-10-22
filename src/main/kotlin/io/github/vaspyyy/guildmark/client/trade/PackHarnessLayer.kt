package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.trade.carry.PackKit
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.animal.equine.DonkeyModel
import net.minecraft.client.model.animal.llama.LlamaModel
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
import kotlin.math.abs

/**
 * Where the pack animals' harness gets its art (living-world/trader/design.md, Part 3, "A pack animal"; build parts D1 and X2): art
 * stream X2's `PackHarnessMesh` (generated, as the caravans' `WagonMesh` is), for a donkey, a mule and a llama, with its texture under
 * `textures/entity/trade/`.
 *
 * **How a harness is drawn.** The way the game dresses a saddled horse: as a second model of the animal's own shape, posed as the animal is.
 * Each harness mesh carries every part the vanilla model looks up by name (empty where the harness has nothing) and is built in the animal's own
 * model space (pixels, y down, the animal facing -z), so a `DonkeyModel` or a `LlamaModel` stood on it poses it as it poses the animal: the
 * neck and head, the tail, the legs and a rearing body, with the halter, the bell's collar and the crupper riding the parts they are fixed to.
 * X2's `PackHarnessMesh.Rig` then poses the harness's own parts on top of that: the panniers (they swing with the stride and with the
 * springs of [HarnessLook], less the fuller they are), the heap that grows in them in tiers, the bell and its clapper, the halter's ring.
 * The blanket is a mesh of its own (`WoolRig` poses its flaps and tassels), painted neutral and drawn tinted by the owner's colour.
 * Goods that show are set on the heaps in the frame of the basket they stand in (so they sway with it), at the places the mesh's numbers give
 * ([PackHarnessMesh.OPENING], [PackHarnessMesh.GOODS]).
 */
internal object PackHarnessArt {
    /**
     * One animal's harness: the mesh of its leather, wicker, rope, sacks and brass; the mesh of its blanket, which is drawn tinted by [tint] of the
     * colour (0 to [PackKit.COLOURS] - 1); and the texture of both for each colour (X2's is the one texture: the colour is the tint).
     */
    class Harness(
        val mesh: () -> LayerDefinition,
        val blanket: () -> LayerDefinition,
        val texture: (Int) -> Identifier,
        val tint: (Int) -> Int,
    )

    /** The three harnesses, and whether the game's own chest boxes are taken off the animal because the baskets stand in their place. */
    class Table(val donkey: Harness, val mule: Harness, val llama: Harness, val hidesChests: Boolean = true)

    /**
     * THE ONE CALL (stream X2): the harness for a donkey, a mule and a llama, or null for none. With null nothing at all is done: no layer is
     * added to any animal, its chest boxes stay and the game looks as it did. The blanket's colours are X2's [PackHarnessMesh.COLOURS], in the
     * order of the kit's colours ([PackKit.COLOURS], and the haul board's seals: red, blue, green, amber, violet, teal): a count that differs
     * stops the harness from being added at all (it would be an index off the end), and is logged.
     */
    fun table(): Table? {
        check(PackHarnessMesh.COLOURS.size == PackKit.COLOURS) {
            "The pack harness has ${PackHarnessMesh.COLOURS.size} blanket colours and the pack kit ${PackKit.COLOURS}"
        }
        val texture = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/trade/pack_harness.png")
        val tint = { colour: Int -> PackHarnessMesh.COLOURS[colour.coerceIn(0, PackHarnessMesh.COLOURS.size - 1)] }
        return Table(
            Harness(PackHarnessMesh::donkey, PackHarnessMesh::donkeyWool, { texture }, tint),
            Harness(PackHarnessMesh::mule, PackHarnessMesh::muleWool, { texture }, tint),
            Harness(PackHarnessMesh::llama, PackHarnessMesh::llamaWool, { texture }, tint),
        )
    }

    /** The table the layers were added with the last time the renderers were made (null for none: nothing is drawn). */
    var baked: Table? = null
        private set

    /** Adds the layer to the donkey's, the mule's and the llama's renderers, if the harness's art is in. */
    fun addTo(event: EntityRenderersEvent.AddLayers) {
        baked = null
        val table = table() ?: return
        var added = false
        event.getRenderer<AbstractChestedHorse, DonkeyRenderer<AbstractChestedHorse>>(EntityTypes.DONKEY)?.let {
            attach(it, HarnessKind.DONKEY, table.donkey) { root -> DonkeyModel(root) }
            added = true
        }
        event.getRenderer<AbstractChestedHorse, DonkeyRenderer<AbstractChestedHorse>>(EntityTypes.MULE)?.let {
            attach(it, HarnessKind.MULE, table.mule) { root -> DonkeyModel(root) }
            added = true
        }
        event.getRenderer<Llama, LlamaRenderer>(EntityTypes.LLAMA)?.let {
            attach(it, HarnessKind.LLAMA, table.llama) { root -> LlamaModel(root) }
            added = true
        }
        if (added) baked = table
    }

    /** [stand] makes the animal's own kind of model on a root: the harness's meshes are built so that it can stand on them. */
    private fun <S : LivingEntityRenderState, M : EntityModel<in S>> attach(
        renderer: LivingEntityRenderer<*, S, M>,
        kind: HarnessKind,
        harness: Harness,
        stand: (ModelPart) -> EntityModel<S>,
    ) {
        renderer.addLayer(PackHarnessLayer(renderer, kind, harness, stand))
    }
}

/**
 * A model of the animal's own kind ([stand]: the game's `DonkeyModel` or `LlamaModel`, built on the harness's root) that is posed as the animal
 * is, and then by [after]. The game does not pose a model when it is submitted: it queues it with its render state and poses it just before it
 * draws it, so this is where the harness is posed, from the state it is drawn with (the harness's frame is in it).
 */
internal class StoodModel<S : LivingEntityRenderState>(
    private val stand: EntityModel<S>,
    private val after: (S) -> Unit,
) : EntityModel<S>(stand.root(), RenderTypes::entityCutout) {
    override fun setupAnim(state: S) {
        stand.setupAnim(state)
        after(state)
    }
}

/**
 * The harness on a pack animal (design Part 3, "A pack animal"; build part D1): a blanket in the owner's colour and wicker panniers that move
 * with the animal's body as the game poses it, swing with its stride and with its starts, stops and turns, bulge in four steps as the chest
 * fills, with a squash when a step is taken; a bell on the breast strap that is knocked by every stride and hangs still when the animal stands;
 * and up to three of the goods in the chest set on top of the heaps, swaying with the baskets. Nothing is drawn while [PackHarnessArt.table]
 * gives no harness.
 */
internal class PackHarnessLayer<S : LivingEntityRenderState, M : EntityModel<in S>>(
    parent: RenderLayerParent<S, M>,
    private val kind: HarnessKind,
    private val harness: PackHarnessArt.Harness,
    stand: (ModelPart) -> EntityModel<S>,
) : RenderLayer<S, M>(parent) {
    private val llama = kind == HarnessKind.LLAMA
    private val leatherRoot: ModelPart = harness.mesh().bakeRoot()
    private val clothRoot: ModelPart = harness.blanket().bakeRoot()
    private val rig = PackHarnessMesh.Rig(leatherRoot, llama)
    private val clothRig = PackHarnessMesh.WoolRig(clothRoot, llama)
    private val lookup = leatherRoot.createPartLookup()
    private val bell: ModelPart? = lookup.apply("bell")
    private val clapper: ModelPart? = lookup.apply("bell_clapper")

    /** The llama's collar goes with her head, and the rig turns her bell back by the head's pitch so that it hangs plumb. */
    private val head: ModelPart? = if (llama) lookup.apply("head") else null

    /**
     * The leather (straps, saddle, baskets, heap, bell, halter) and the blanket: each posed as the animal is and then by its rig, from the frame
     * in the state. The rig takes the springs of [HarnessLook] as they are, except two signs: the bell's swing is forward only, so what knocks it
     * either way is a knock; and the rig's baskets read [HarnessFrame.pitch], [HarnessFrame.roll] and [HarnessFrame.dangle] as the look gives
     * them (positive: lagging back, swinging to the animal's right, trailing back).
     */
    private val leather: StoodModel<S> = StoodModel(stand(leatherRoot)) { state ->
        state.getRenderData(PackHarnessClient.FRAME)?.let {
            rig.pose(it.fill, state.walkAnimationPos, state.walkAnimationSpeed, state.ageInTicks, it.trot, it.pitch, it.roll, abs(it.bell), it.pop)
            if (it.ring < 1f) quieten(it.ring)
        }
    }
    private val cloth: StoodModel<S> = StoodModel(stand(clothRoot)) { state ->
        state.getRenderData(PackHarnessClient.FRAME)?.let {
            clothRig.pose(state.walkAnimationPos, state.walkAnimationSpeed, state.ageInTicks, it.trot, it.dangle)
        }
    }

    /** What the baskets hang from: the donkey's body (the chests are its children, so they rear with it), the llama's root. */
    private val host: ModelPart? = if (llama) null else leatherRoot.getChild("body")
    private val chests: List<ModelPart> = listOf("left_chest", "right_chest").map { (host ?: leatherRoot).getChild(it) }

    /**
     * A hushed bell hangs nearly still. The rig swings the bell with the stride itself, so what it swung it by is taken back here, by [ring]
     * ([HarnessFrame.ring]): all of it but the turn that keeps a llama's bell plumb under her head. Its clapper is quietened with it.
     */
    private fun quieten(ring: Float) {
        val plumb = -(head?.xRot ?: 0f)
        bell?.let { it.xRot = plumb + (it.xRot - plumb) * ring }
        clapper?.let {
            it.xRot *= ring
            it.zRot *= ring
        }
    }

    override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: S, yRot: Float, xRot: Float) {
        val frame = state.getRenderData(PackHarnessClient.FRAME) ?: return
        if (frame.kind != kind) return
        val texture = harness.texture(frame.colour)
        val overlay = LivingEntityRenderer.getOverlayCoords(state, 0f)
        collector.submitModel(leather, state, poseStack, texture, light, overlay, state.outlineColor)
        collector.submitModel(cloth, state, poseStack, cloth.renderType(texture), light, overlay, harness.tint(frame.colour), null, state.outlineColor)
        if (frame.goods.isEmpty()) return
        // The goods stand in the baskets' own frames, which are where the baskets are swinging now: posed here as they will be when drawn
        leather.setupAnim(state)
        val opening = PackHarnessMesh.OPENING
        val heap = PackHarnessMesh.heapTop(frame.fill)
        val seat = PackHarnessMesh.GOODS_SIZE * 8f - SINK
        for (side in 0..1) {
            // The right basket's places are the left's mirror
            val sg = if (side == 0) 1f else -1f
            poseStack.pushPose()
            leatherRoot.translateAndRotate(poseStack)
            host?.translateAndRotate(poseStack)
            chests[side].translateAndRotate(poseStack)
            for ((i, good) in frame.goods.withIndex()) {
                val place = PackHarnessMesh.GOODS.getOrNull(i) ?: break
                poseStack.pushPose()
                // On the heap's top (a good's middle is half its size over it, sunk a little in), and a little lower when the animal lands
                poseStack.translate(
                    sg * (opening[0] + place.x) / 16f,
                    (opening[1] - heap - seat - frame.bounce * BOUNCE) / 16f,
                    (opening[2] + place.z) / 16f,
                )
                poseStack.rotate(Axis.YP, sg * place.yaw * YAW * RAD)
                // The model's y runs down: turned over, the item stands the right way up, and then out to the side it is on so that it shows from there
                poseStack.rotate(Axis.ZP, Math.PI.toFloat())
                poseStack.rotate(Axis.YP, -sg * HALF_PI)
                poseStack.rotate(Axis.ZP, sg * place.tilt * RAD)
                val scale = PackHarnessMesh.GOODS_SIZE / good.extent
                poseStack.scale(scale, scale, scale)
                poseStack.translate(-good.middle.x, -good.middle.y, -good.middle.z)
                good.model.submit(poseStack, collector, light, overlay, state.outlineColor)
                poseStack.popPose()
            }
            poseStack.popPose()
        }
    }

    private companion object {
        /** How far a good sinks into the heap it rests on (pixels), and how much of the landing's knock it takes (it is not strapped down). */
        const val SINK = 0.45f
        const val BOUNCE = 0.6f

        /** How much of the places' yaw is used: a flat good turned the whole way would be seen edge-on from the side it faces. */
        const val YAW = 0.5f

        const val RAD = 0.017453292f
        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}
