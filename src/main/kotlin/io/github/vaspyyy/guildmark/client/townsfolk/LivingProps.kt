package io.github.vaspyyy.guildmark.client.townsfolk

import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.client.cooking.TownPlates
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.sin

/**
 * The props of the Living World's clips (a loaf to eat, the well's rope and bucket, a scythe, a crier's bell and
 * scroll, a lamplighter's pole) and the goods someone carries, shown and hidden tick by tick as [LivingSchedule]
 * says (art/scripts/living_anims.py writes it from the clips, and its previews show the same). Hands busy with a
 * clip's work let go of what they carry about anyway (a smith's hammer, a lantern after dark); props that move on
 * their own (the well's rope running over the curb, the bucket coming up the shaft, a grindstone's crank) follow
 * their clip's "prop:" tracks, and a bucket or an emptied sack hangs straight down from the fist. A climb plays by
 * height, so hands and feet keep to the rungs at whatever speed someone goes up or down, and over the last two blocks
 * under a ledge the hands go onto the ledge and the climber steps up onto it.
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
        // ---- Hearthcraft (TownPlates.kt): the plate in front of a tavern diner, for TownPlateLayer ----
        var plate: TownPlates.Plate? = null
        // ---- end Hearthcraft ----
        /** The middle of the goods' model and its largest extent (in the item's own space, blocks). */
        val middle = Vector3f()
        var extent = 1f
        /** Whether the wick at the top of a lamplighter's pole burns: lit to light the lamps, out to put them out. */
        var wick = true
    }

    // What a free hand carries about anyway (Props.held), let go of while the hand is busy
    private val RIGHT_CARRIED = setOf("hammer", "spear", "hoe", "rod", "cane", "staff")
    private val LEFT_CARRIED = setOf("lantern", "fan", "book", "basket")

    /** Work for the right hand only (the left keeps a lantern or a book): every other Living World clip uses both. */
    private val RIGHT_ONLY = setOf(
        Activity.KNOCK, Activity.POINT, Activity.YAWN,
        // The talking gestures that use one hand, the other hanging or walking
        Activity.GOSSIP, Activity.SHUSH, Activity.WAVE_OFF, Activity.TIP_HAT, Activity.INTRODUCE, Activity.WAVE_WALK, Activity.NOD_WALK,
        // The watch (a lantern kept in the left at night), the beam on the right shoulder, the newcomers shown in: the left swings free
        Activity.LOOKOUT, Activity.CARRY_BEAM, Activity.SHOW_IN,
    )
    private val LEFT_ONLY = setOf(Activity.CARRY_BASKET)
    private val FREE = setOf(Activity.NOD, Activity.SIGH)

    private val defaults = HashMap<String, ItemStack>()

    /** Fills in [state]'s Living World half, after the rest of it (TownsfolkRenderer.extractRenderState). */
    fun extract(p: Townsperson, state: TownsfolkRenderState, partial: Float) {
        val living = state.living
        living.clip = null
        living.name = ""
        living.span = null
        living.plate = null
        living.shown.clear()
        // The pole's wick: burning on the way to light the lamps (the lantern flag stands for it), out to snuff them
        living.wick = when (p.shownActivity) {
            Activity.SNUFF_LAMP -> false
            Activity.CARRY_POLE -> p.lantern
            else -> true
        }
        val first = state.frame ?: return
        var frame = climbing(p, first, partial)
        // Put on a seat or off it: no easing in from the last clip, which was drawn from the other place
        val from = frame.from
        if (from != null && moved(p)) frame = CombatAnimator.Frame(frame.clip, frame.time, 1f, frame.yaw, frame.tremble, frame.seed, null)
        state.frame = frame
        val activity = p.shownActivity
        val name = TownsfolkClient.clipName(p, activity)
        val ours = name in LivingSchedule.CLIPS
        // ---- Hearthcraft (TownPlates.kt): a served dish in the hand of someone eating out of doors at lunch ----
        val spans = LivingSchedule.GOODS[name] ?: TownPlates.inHand(p, name)
        // ---- end Hearthcraft ----
        // ---- Hearthcraft (TownPlates.kt): a tavern diner's plate, and the spoon where the mug was (here, before the clips that are not the Living World's return, and after the cached held set is looked up) ----
        TownPlates.eat(p, state)
        // ---- end Hearthcraft ----
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
            // Hearthcraft (TownPlates.kt): a lunch dish takes the place of the loaf of `eat`
            (LivingSchedule.GOODS_REPLACE[name] ?: TownPlates.replaces(name))?.let { replaced ->
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
        if (living.wick && living.shown.any { it in LivingSchedule.GLOW }) state.glows = true
    }

    private fun letGo(held: Set<String>, right: Boolean, left: Boolean): Set<String> {
        if (!right && !left) return held
        // Nothing in a busy hand to let go of: the same set, not a copy of it
        if (held.none { right && it in RIGHT_CARRIED || left && it in LEFT_CARRIED }) return held
        return held.filterTo(HashSet()) { !(right && it in RIGHT_CARRIED) && !(left && it in LEFT_CARRIED) }
    }

    /** The goods [p] has in hand at [time] of [name]'s clip, made ready to draw: the span they're in, or null if none show. */
    private fun goods(p: Townsperson, living: State, name: String, spans: List<LivingSchedule.Goods>, time: Float): LivingSchedule.Goods? {
        // Hearthcraft (TownPlates.kt): a served baked dish in place of what they carry, for a lunch-eater who is one of the diners
        var stack = TownPlates.shown(p, p.carried)
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

    // ---- Onto a seat and off it ----

    /**
     * Whether [p] was just put somewhere new along with their move: onto the seat as SIT_DOWN starts, or onto the floor
     * in front of it as whatever follows STAND_UP starts (sit_down starts, and stand_up ends, standing on that floor).
     */
    private fun moved(p: Townsperson): Boolean = p.shownActivity == Activity.SIT_DOWN || p.lastActivity == Activity.STAND_UP ||
        // The caravan crew: onto the wagon's seat as climb_up starts, onto the road as jump_down and cut_loose end
        p.shownActivity == Activity.CLIMB_UP || p.lastActivity == Activity.JUMP_DOWN || p.lastActivity == Activity.CUT_LOOSE

    /** Run after the props are posed (TownsfolkModel.setupAnim): client/caravan's reins and nosebag. */
    var after: ((TownsfolkModel, TownsfolkRenderState) -> Unit)? = null

    /**
     * Each client tick (from LivingMoments.tick): someone put onto a seat or off it is put straight there, not slid
     * there over the next few ticks as a move from the server usually is, since their clip already carries them over.
     */
    fun settle(p: Townsperson) {
        if (!moved(p) || p.tickCount - p.changedAt > 1) return
        val handler = p.interpolation ?: return
        if (!handler.hasActiveInterpolation()) return
        val target = handler.target() ?: return
        val at = target.position()
        val yaw = target.yRot()
        val pitch = target.xRot()
        handler.cancel()
        p.snapTo(at, yaw, pitch)
        p.setYBodyRot(yaw)
        p.setYHeadRot(yaw)
        p.yBodyRotO = yaw
        p.yHeadRotO = yaw
    }

    // ---- Climbing ----

    /** Where in the climb clip someone [y] blocks up is: 2 ticks a px of height (the clip rises 0.5 px a tick), so a hand or foot on a rung stays on it. */
    fun climbTime(y: Double): Float = Mth.positiveModulo(y * CLIMB_TICKS_PER_BLOCK, Activity.CLIMB.ticks.toDouble()).toFloat()

    /**
     * Where in [TOP_OUT] someone [y] blocks up is, by height as a climb's time is (0 two blocks under the top, its
     * length with the feet level with it), or -1 if they aren't topping out: the climbable blocks they're on end within
     * two blocks over their feet, at a ledge ahead to step up onto (a block solid on top, with room over it).
     */
    fun topOutTime(p: Townsperson, y: Double): Float {
        val level = p.level()
        val x = Mth.floor(p.x)
        val z = Mth.floor(p.z)
        val feet = Mth.floor(y)
        val pos = BlockPos.MutableBlockPos()
        var top = feet + TOP_OUT_BLOCKS + 1
        for (k in 0..TOP_OUT_BLOCKS) {
            if (!level.getBlockState(pos.set(x, feet + k, z)).`is`(BlockTags.CLIMBABLE)) {
                top = feet + k
                break
            }
        }
        val under = top - y
        if (under > TOP_OUT_BLOCKS) return -1f
        val ahead = Direction.fromYRot(p.yBodyRot.toDouble())
        pos.set(x + ahead.stepX, top - 1, z + ahead.stepZ)
        if (!level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) return -1f
        pos.move(Direction.UP)
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return -1f
        return ((TOP_OUT_BLOCKS - under) * CLIMB_TICKS_PER_BLOCK).toFloat().coerceIn(0f, TOP_OUT_TICKS)
    }

    /** Which climb clip showed someone last and where in it (a climb eased out of is held there). */
    private class Climb(var top: Boolean, var time: Float)

    private val climbs = WeakHashMap<Townsperson, Climb>()

    /**
     * [frame] with a climb's time going by [p]'s height, the last two blocks under a ledge shown by [TOP_OUT] (the
     * hands onto the ledge, the step up onto it); a climb eased out of, stepping off at the top or the bottom, is held
     * where it was left.
     */
    private fun climbing(p: Townsperson, frame: CombatAnimator.Frame, partial: Float): CombatAnimator.Frame {
        val climbing = p.shownActivity == Activity.CLIMB
        val from = frame.from
        val wasClimbing = from != null && p.lastActivity == Activity.CLIMB
        if (climbing) {
            val y = Mth.lerp(partial.toDouble(), p.yo, p.y)
            val top = topOutTime(p, y)
            val climb = climbs.getOrPut(p) { Climb(false, 0f) }
            climb.top = top >= 0f
            climb.time = if (climb.top) top else climbTime(y)
            val clip = climbClip(climb, frame.clip)
            val held = if (wasClimbing && from != null) CombatAnimator.Frame(clip, climb.time, from.weight, from.yaw, from.tremble, from.seed, null) else from
            return CombatAnimator.Frame(clip, climb.time, frame.weight, frame.yaw, frame.tremble, frame.seed, held)
        }
        if (!wasClimbing || from == null) return frame
        val climb = climbs[p] ?: Climb(false, climbTime(p.y))
        val held = CombatAnimator.Frame(climbClip(climb, from.clip), climb.time, from.weight, from.yaw, from.tremble, from.seed, null)
        return CombatAnimator.Frame(frame.clip, frame.time, frame.weight, frame.yaw, frame.tremble, frame.seed, held)
    }

    private fun climbClip(climb: Climb, clip: Clip): Clip = if (climb.top) Clips.get(TOP_OUT) ?: clip else clip

    /** Ticks of the climb clip a block of height takes (16 px a block, 2 ticks a px). */
    const val CLIMB_TICKS_PER_BLOCK = 32.0

    /** The clip of a climb's last blocks under a ledge (no Activity's own: played in the climb's place), how many blocks it takes and its length. */
    const val TOP_OUT = "townsfolk/climb_top"
    const val TOP_OUT_BLOCKS = 2
    const val TOP_OUT_TICKS = 64f

    // ---- On the model ----

    /** The Living World's props on the townsfolk model, posed after the rest of it (TownsfolkModel.setupAnim). */
    class Parts(root: ModelPart) {
        private val find = root.createPartLookup()
        private val all: List<Pair<String, ModelPart>> = LivingSchedule.PARENT.keys.mapNotNull { name -> find.apply(name)?.let { name to it } }
        private val byName: Map<String, ModelPart> = all.toMap()
        private val cloth: ModelPart? = find.apply("basket_hip_cloth")
        private val flame: ModelPart? = find.apply("lamp_flame")

        /** Each prop's tracks' names in a clip ("prop:" and its name, and its stretch's), made once rather than every frame. */
        private val trackKeys = HashMap<String, String>()
        private val scaleKeys = HashMap<String, String>()

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
            // The pole's flame, only while its wick burns
            flame?.visible = living.wick
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
            val bone = clip.bones[trackKeys.getOrPut(name) { "prop:$name" }] ?: return
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
            clip.bones[scaleKeys.getOrPut(name) { "prop:$name:scale" }]?.let { stretch ->
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
