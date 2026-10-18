package io.github.vaspyyy.guildmark.client.townsfolk

import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.sin

/**
 * The props of the Living World's clips (a loaf to eat, the well's rope and bucket, a scythe, a crier's bell and
 * scroll, a lamplighter's pole) and the goods someone carries, shown and hidden tick by tick as [LivingSchedule]
 * says (art/scripts/living_anims.py writes it from the clips, and its previews show the same). Hands busy with a
 * clip's work let go of what they carry about anyway (a smith's hammer, a lantern after dark); props that move on
 * their own (the well's rope running over the curb, the bucket coming up the shaft, a grindstone's crank) follow
 * their clip's "prop:" tracks, and a bucket or an emptied sack hangs straight down from the fist. A climb plays by
 * height, so hands and feet keep to the rungs at whatever speed someone goes up or down.
 */
internal object LivingProps {
    /** What a Living World clip shows this frame (kept on the render state: render data set while extracting is cleared after). */
    class State {
        /** The clip playing, when it is one of the Living World's or one that carries goods; null otherwise. */
        var clip: Clip? = null
        var name = ""
        var time = 0f
        /** The Living World props showing (by part name). */
        val shown = HashSet<String>()
        /** The goods in hand, for [LivingGoodsLayer], while [span] says where they are. */
        val goods = ItemStackRenderState()
        var span: LivingSchedule.Goods? = null
        /** The middle of the goods' model and its largest extent (in the item's own space, blocks). */
        val middle = Vector3f()
        var extent = 1f
    }

    // What a free hand carries about anyway (Props.held), let go of while the hand is busy
    private val RIGHT_CARRIED = setOf("hammer", "spear", "hoe", "rod", "cane", "staff")
    private val LEFT_CARRIED = setOf("lantern", "fan", "book", "basket")

    /** Work for the right hand only (the left keeps a lantern or a book): every other Living World clip uses both. */
    private val RIGHT_ONLY = setOf(Activity.KNOCK, Activity.POINT, Activity.YAWN)
    private val LEFT_ONLY = setOf(Activity.CARRY_BASKET)
    private val FREE = setOf(Activity.NOD)

    private val defaults = HashMap<String, ItemStack>()

    /** Fills in [state]'s Living World half, after the rest of it (TownsfolkRenderer.extractRenderState). */
    fun extract(p: Townsperson, state: TownsfolkRenderState, partial: Float) {
        val living = state.living
        living.clip = null
        living.name = ""
        living.span = null
        living.shown.clear()
        val first = state.frame ?: return
        val frame = climbing(p, first, partial)
        state.frame = frame
        val activity = p.shownActivity
        val name = TownsfolkClient.clipName(p, activity)
        val ours = name in LivingSchedule.CLIPS
        val spans = LivingSchedule.GOODS[name]
        if (!ours && spans == null) return
        val time = frame.time
        living.clip = frame.clip
        living.name = name
        living.time = time
        var held = state.held
        if (ours) {
            held = letGo(held, activity !in LEFT_ONLY && activity !in FREE, activity !in RIGHT_ONLY && activity !in FREE)
            for (s in LivingSchedule.SHOWN[name].orEmpty()) {
                if (time < s.from || time >= s.to && s.to != LivingSchedule.END) continue
                if (s.part in Props.ALL) held = held + s.part else living.shown += s.part
            }
        }
        if (spans != null) goods(p, living, name, spans, time)?.let { span ->
            LivingSchedule.GOODS_REPLACE[name]?.let { replaced ->
                held = held - replaced
                living.shown -= replaced
            }
            // Goods in a hand take the place of what that hand carries about
            when {
                span.how == LivingSchedule.How.PART && span.part == "right_arm" -> held = letGo(held, right = true, left = false)
                span.how == LivingSchedule.How.PART && span.part == "left_arm" -> held = letGo(held, right = false, left = true)
                span.how == LivingSchedule.How.HANDS -> held = letGo(held, right = true, left = true)
                else -> {}
            }
        }
        state.held = held
        if (living.shown.any { it in LivingSchedule.GLOW }) state.glows = true
    }

    private fun letGo(held: Set<String>, right: Boolean, left: Boolean): Set<String> {
        if (!right && !left) return held
        return held.filterTo(HashSet()) { !(right && it in RIGHT_CARRIED) && !(left && it in LEFT_CARRIED) }
    }

    /** The goods [p] has in hand at [time] of [name]'s clip, made ready to draw: the span they're in, or null if none show. */
    private fun goods(p: Townsperson, living: State, name: String, spans: List<LivingSchedule.Goods>, time: Float): LivingSchedule.Goods? {
        var stack = p.carried
        if (stack.isEmpty) {
            if (name in LivingSchedule.GOODS_ONLY_CARRIED) return null
            stack = LivingSchedule.GOODS_DEFAULT[name]?.let { id ->
                defaults.getOrPut(id) { ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))) }
            } ?: return null
            if (stack.isEmpty) return null
        }
        val span = spans.firstOrNull { time >= it.from && time < it.to } ?: return null
        Minecraft.getInstance().itemModelResolver.updateForLiving(living.goods, stack, ItemDisplayContext.FIXED, p)
        if (living.goods.isEmpty) return null
        val box = living.goods.modelBoundingBox
        living.middle.set(((box.minX + box.maxX) * 0.5).toFloat(), ((box.minY + box.maxY) * 0.5).toFloat(), ((box.minZ + box.maxZ) * 0.5).toFloat())
        living.extent = maxOf(box.xsize, box.ysize, box.zsize).toFloat().coerceAtLeast(1e-3f)
        living.span = span
        return span
    }

    // ---- Climbing ----

    /** Where in the climb clip someone [y] blocks up is: 2 ticks a px of height (the clip rises 0.5 px a tick), so a hand or foot on a rung stays on it. */
    fun climbTime(y: Double): Float = Mth.positiveModulo(y * CLIMB_TICKS_PER_BLOCK, Activity.CLIMB.ticks.toDouble()).toFloat()

    /** [frame] with a climb's time (and that of a climb it eases out of) going by [p]'s height. */
    private fun climbing(p: Townsperson, frame: CombatAnimator.Frame, partial: Float): CombatAnimator.Frame {
        val climbing = p.shownActivity == Activity.CLIMB
        val from = frame.from
        val wasClimbing = from != null && p.lastActivity == Activity.CLIMB
        if (!climbing && !wasClimbing) return frame
        val t = climbTime(Mth.lerp(partial.toDouble(), p.yo, p.y))
        val newFrom = if (wasClimbing && from != null) CombatAnimator.Frame(from.clip, t, from.weight, from.yaw, from.tremble, from.seed, null) else from
        return CombatAnimator.Frame(frame.clip, if (climbing) t else frame.time, frame.weight, frame.yaw, frame.tremble, frame.seed, newFrom)
    }

    /** Ticks of the climb clip a block of height takes (16 px a block, 2 ticks a px). */
    const val CLIMB_TICKS_PER_BLOCK = 32.0

    // ---- On the model ----

    /** The Living World's props on the townsfolk model, posed after the rest of it (TownsfolkModel.setupAnim). */
    class Parts(root: ModelPart) {
        private val find = root.createPartLookup()
        private val all: List<Pair<String, ModelPart>> = LivingSchedule.PARENT.keys.mapNotNull { name -> find.apply(name)?.let { name to it } }
        private val byName: Map<String, ModelPart> = all.toMap()
        private val cloth: ModelPart? = find.apply("basket_hip_cloth")

        private val qRoot = Quaternionf()
        private val qParent = Quaternionf()
        private val qModel = Quaternionf()
        private val qLocal = Quaternionf()
        private val v = Vector3f()
        private val euler = Vector3f()

        fun pose(model: TownsfolkModel, state: TownsfolkRenderState) {
            val living = state.living
            for ((name, part) in all) part.visible = name in living.shown
            // The basket's cloth covers it while nothing is in it
            cloth?.visible = living.span == null
            val clip = living.clip ?: return
            val tracked = LivingSchedule.TRACKED[living.name].orEmpty()
            for (name in tracked) {
                val part = byName[name] ?: continue
                if (part.visible) track(model, clip, living.time, name, part)
            }
            for (name in LivingSchedule.HANG) {
                if (name in tracked) continue
                val part = byName[name] ?: continue
                if (part.visible) hang(model, part, name, sin(state.ageInTicks * 0.12f) * 0.04f)
            }
        }

        private fun parentOf(model: TownsfolkModel, name: String): ModelPart? = when (LivingSchedule.PARENT[name]) {
            "right_arm" -> model.rightArm
            "left_arm" -> model.leftArm
            "body" -> model.body
            "head" -> model.head
            else -> null
        }

        /**
         * Turns, places and stretches [part] as [clip]'s "prop:" track has it at [time]: the track's turn and place are
         * in the model's own space (where the root is placed), so the root's and the parent's are undone.
         */
        private fun track(model: TownsfolkModel, clip: Clip, time: Float, name: String, part: ModelPart) {
            val bone = clip.bones["prop:$name"] ?: return
            val parent = parentOf(model, name) ?: return
            val root = model.root()
            qRoot.rotationZYX(root.zRot, root.yRot, root.xRot)
            qParent.rotationZYX(parent.zRot, parent.yRot, parent.xRot)
            bone.rotation(clip, time, qModel)
            qLocal.set(qRoot).mul(qParent).conjugate().mul(qModel)
            qLocal.getEulerAnglesZYX(euler)
            part.xRot = euler.x
            part.yRot = euler.y
            part.zRot = euler.z
            if (bone.hasPosition) {
                bone.position(clip, time, v)
                v.sub(root.x, root.y, root.z)
                qRoot.conjugate().transform(v)
                v.sub(parent.x, parent.y, parent.z)
                qParent.conjugate().transform(v)
                part.x = v.x
                part.y = v.y
                part.z = v.z
            }
            clip.bones["prop:$name:scale"]?.let { stretch ->
                stretch.position(clip, time, v)
                part.xScale = v.x
                part.yScale = v.y
                part.zScale = v.z
            }
        }

        /** Turns [part] to hang straight down whichever way the root and the arm holding it are turned, stirring by [sway]. */
        private fun hang(model: TownsfolkModel, part: ModelPart, name: String, sway: Float) {
            val parent = parentOf(model, name) ?: return
            val root = model.root()
            qLocal.rotationZYX(root.zRot, root.yRot, root.xRot).mul(qParent.rotationZYX(parent.zRot, parent.yRot, parent.xRot)).conjugate()
            qLocal.getEulerAnglesZYX(euler)
            part.xRot = euler.x + sway
            part.yRot = euler.y
            part.zRot = euler.z
        }
    }
}
