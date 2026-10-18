package io.github.vaspyyy.guildmark.client.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the townsfolk model needs: the look they wear, what they hold, and the clip to pose them in. */
class TownsfolkRenderState : HumanoidRenderState() {
    var texture: Identifier = TownsfolkRenderer.FALLBACK
    /** The look pieces they wear (hair, hats, aprons, tabards...). */
    var pieces: Set<String> = emptySet()
    /** The tools and things they hold (see [Props]). */
    var held: Set<String> = emptySet()
    var child = false
    /** Hot iron in the tongs or a lantern lit: the glow layer draws. */
    var glows = false
    /** The clip to show (kept here: render data set while extracting is cleared after). */
    var frame: CombatAnimator.Frame? = null

    // ---- Living World (LivingProps.kt): its clips' props, the goods in hand ----
    internal val living = LivingProps.State()
    // ---- end Living World ----
}

/**
 * One humanoid for every townsperson: each look shows its own pieces (art/scripts/townsfolk.py builds the
 * geometry into [TownsfolkMesh] and lists the looks in [TownsfolkLooks]), tools come and go with what they're
 * doing, and the clips of art/scripts/townsfolk_anims.py pose it through the [CombatAnimator]. A fishing line
 * and a lantern hang straight down whichever way the arm holding them is turned; aprons, tabards and capes hang
 * straight as the body bends and swing with the stride. Children have big heads.
 */
class TownsfolkModel(root: ModelPart) : HumanoidModel<TownsfolkRenderState>(root) {
    private val find = root.createPartLookup()
    private val pieces: List<Pair<String, ModelPart>> = TownsfolkLooks.OPTIONAL.mapNotNull { name -> find.apply(name)?.let { name to it } }
    private val tools: List<Pair<String, ModelPart>> = Props.ALL.mapNotNull { name -> find.apply(name)?.let { name to it } }
    private val rod: ModelPart? = find.apply("rod")
    private val line: ModelPart? = find.apply("line")
    private val lantern: ModelPart? = find.apply("lantern")

    // Cloth that hangs from its top edge: the bend of the body is undone so it hangs straight, and a stride kicks it
    private val apron: ModelPart? = find.apply("apron")
    private val tabard: ModelPart? = find.apply("tabard")
    private val tabardBack: ModelPart? = find.apply("tabard_back")
    private val cape: ModelPart? = find.apply("cape")

    private val q = Quaternionf()
    private val step = Quaternionf()
    private val euler = Vector3f()

    // ---- Living World (LivingProps.kt): its clips' props ----
    private val living = LivingProps.Parts(root)
    // ---- end Living World ----

    override fun setupAnim(state: TownsfolkRenderState) {
        super.setupAnim(state)
        for ((name, part) in pieces) part.visible = name in state.pieces
        for ((name, part) in tools) part.visible = name in state.held
        CombatAnimator.pose(this, state, state.frame)
        if (state.child) {
            head.xScale = CHILD_HEAD
            head.yScale = CHILD_HEAD
            head.zScale = CHILD_HEAD
        }
        val stride = min(max(abs(rightLeg.xRot), abs(leftLeg.xRot)), 1.2f) * 0.5f
        apron?.let { it.xRot = it.initialPose.xRot - body.xRot * 0.8f - stride }
        tabard?.let { it.xRot = it.initialPose.xRot - body.xRot * 0.8f - stride }
        tabardBack?.let { it.xRot = it.initialPose.xRot - body.xRot * 0.8f + stride * 0.6f }
        // A cape streams out behind as they walk, and stirs while they stand
        cape?.let { it.xRot = it.initialPose.xRot - body.xRot * 0.8f + state.walkAnimationSpeed * 0.5f + sin(state.ageInTicks * 0.1f) * 0.03f }
        // A lantern swings a little as they walk; a line only stirs
        val swing = sin(state.walkAnimationPos * 0.6662f) * state.walkAnimationSpeed * 0.25f
        hang(lantern, sin(state.ageInTicks * 0.12f) * 0.05f + swing, root(), leftArm)
        hang(line, sin(state.ageInTicks * 0.07f) * 0.03f, root(), rightArm, rod)
        // ---- Living World (LivingProps.kt): its clips' props shown, tracked and hung ----
        living.pose(this, state)
        LivingProps.after?.invoke(this, state)
        // ---- end Living World ----
    }

    /** Turns [part] so it hangs straight down, undoing the turns of the parts it hangs from ([chain], outermost first). */
    private fun hang(part: ModelPart?, sway: Float, vararg chain: ModelPart?) {
        if (part == null || !part.visible) return
        q.identity()
        for (link in chain) if (link != null) q.mul(step.rotationZYX(link.zRot, link.yRot, link.xRot))
        q.conjugate().getEulerAnglesZYX(euler)
        part.xRot = euler.x + sway
        part.yRot = euler.y
        part.zRot = euler.z
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk"), "main")

        /** How much bigger a child's head is drawn, on a body drawn at [io.github.vaspyyy.guildmark.townsfolk.Townsperson.CHILD_SCALE]. */
        const val CHILD_HEAD = 1.3f
    }
}

/**
 * What townsfolk hold: the tools of what they're doing (a smith's tongs at the anvil, a mug at the tavern, a
 * crate on an errand), and in a free hand what they carry about anyway (the smith's hammer, the guard's spear,
 * the priest's book, a lantern after dark, a noble's cane or fan).
 */
object Props {
    /** Every tool the code shows and hides; their own pieces (the lute's head, the line) follow them. */
    val ALL = listOf(
        "hammer", "tongs", "hot_iron", "peel", "spear", "lantern", "hoe", "rod", "line", "cane", "fan", "rag", "mug",
        "jug", "lute", "lute_slung", "book", "crate", "sack", "broom", "basket",
        "staff", "quill", "sword", "shield", "whetstone", "dice_cup", "coin_purse", "lockpick",
    )

    // Which hand each is held in (the rest hang on the body)
    private val RIGHT = setOf("hammer", "peel", "spear", "hoe", "rod", "cane", "mug", "broom", "staff", "quill", "sword", "dice_cup", "lockpick")
    private val LEFT = setOf("tongs", "lantern", "fan", "rag", "jug", "book", "basket", "shield", "whetstone", "coin_purse")

    /** Things a look may carry in hand (named among its pieces). */
    private val CARRIED_LOOKS = listOf("cane", "fan", "basket")

    private val OWN: Map<Activity, Set<String>> = mapOf(
        Activity.HAMMER to setOf("hammer", "tongs", "hot_iron"),
        Activity.TEND to setOf("hammer", "tongs"),
        Activity.QUENCH to setOf("hammer", "tongs", "hot_iron"),
        Activity.OVEN to setOf("peel"),
        Activity.PATROL to setOf("spear"),
        Activity.STAND_GUARD to setOf("spear"),
        Activity.HOE to setOf("hoe"),
        Activity.CAST to setOf("rod"),
        Activity.FISH to setOf("rod", "line"),
        Activity.REEL to setOf("rod", "line"),
        Activity.DRINK to setOf("mug"),
        Activity.CHEER to setOf("mug"),
        Activity.SIT_DRINK to setOf("mug"),
        Activity.WIPE to setOf("rag", "mug"),
        Activity.POUR to setOf("jug", "mug"),
        Activity.LUTE to setOf("lute"),
        Activity.SWEEP to setOf("broom"),
        Activity.CARRY to setOf("crate"),
        Activity.CARRY_SACK to setOf("sack"),
        Activity.LANTERN to setOf("lantern"),
        Activity.SCRIBE to setOf("quill"),
        Activity.CONJURE to setOf("staff"),
        Activity.LECTURE to setOf("staff"),
        Activity.SPAR_ATTACK to setOf("sword", "shield"),
        Activity.SPAR_GUARD to setOf("sword", "shield"),
        Activity.DRILL to setOf("sword", "shield"),
        Activity.SHARPEN to setOf("sword", "whetstone"),
        Activity.KNEEL to setOf("sword"),
        Activity.DICE to setOf("dice_cup"),
        Activity.COUNT to setOf("coin_purse"),
        Activity.PICK_LOCK to setOf("lockpick"),
        // The festivals: the band's lutes, and the lanterns carried in the Lanternrise's ring and let go at dusk
        Activity.MINSTREL to setOf("lute"),
        Activity.LANTERN_WALK to setOf("lantern"),
        Activity.LANTERN_RAISE to setOf("lantern"),
        Activity.RELEASE to setOf("lantern"),
    )

    /** Work for both hands: nothing else is held meanwhile. */
    private val BOTH_HANDS = setOf(
        Activity.HAMMER, Activity.TEND, Activity.QUENCH, Activity.KNEAD, Activity.OVEN, Activity.CALL_OUT, Activity.ARRANGE,
        Activity.HOE, Activity.HARVEST, Activity.CAST, Activity.FISH, Activity.REEL, Activity.PLAY, Activity.JUMP, Activity.SPIN,
        Activity.CLAP, Activity.LAUGH, Activity.LISTEN, Activity.SIT, Activity.SIT_TALK, Activity.WIPE, Activity.POUR,
        Activity.LUTE, Activity.PRAY, Activity.SWEEP, Activity.CARRY,
        Activity.READ, Activity.KNEEL, Activity.COUNT, Activity.DICE, Activity.SCRIBE, Activity.CONJURE, Activity.LECTURE,
        Activity.PICK_LOCK, Activity.LURK, Activity.BOUND, Activity.PLEAD, Activity.FREED, Activity.THANK,
        Activity.PULL_CART,
        Activity.MINSTREL, Activity.CLAP_ALONG, Activity.FEST_HURRAH, Activity.TWIRL, Activity.COUPLE_TURN, Activity.RING_IN,
        Activity.HANG, Activity.RING_STEP, Activity.RING_STEP_L,
        // The caravan crew (art/scripts/road_clips.py): the reins and the brake, the spear across the knees, the climbs
        // down and up, the knife and the shaft, the load handed down, the arms over the head, the hands on the knees, the
        // telling of it, the folded arms. (wounded, an overlay, keeps the right hand's)
        Activity.DRIVE, Activity.DRIVE_URGE, Activity.DRIVE_BRAKE, Activity.RIDE_TAIL, Activity.JUMP_DOWN, Activity.CLIMB_UP,
        Activity.CUT_LOOSE, Activity.COWER, Activity.HAND_DOWN, Activity.CATCH_BREATH, Activity.RECOUNT, Activity.GRUMBLE,
    )

    /** Work for the right hand only (waving, a mug, the sack on the shoulder). */
    private val RIGHT_HAND = setOf(
        Activity.WAVE, Activity.BECKON, Activity.DRINK, Activity.CHEER, Activity.SIT_DRINK, Activity.CARRY_SACK,
        Activity.SEARCH, Activity.SHOUT, Activity.FEST_BOW,
    )

    fun held(role: TownRole, activity: Activity, lantern: Boolean, pieces: Set<String>): Set<String> {
        val out = HashSet<String>(OWN[activity] ?: emptySet())
        val both = activity in BOTH_HANDS
        val rightBusy = both || activity in RIGHT_HAND || out.any { it in RIGHT }
        val leftBusy = both || out.any { it in LEFT }
        fun carry(tool: String) {
            if (tool in RIGHT && !rightBusy || tool in LEFT && !leftBusy) out += tool
        }
        when (role) {
            TownRole.SMITH -> carry("hammer")
            TownRole.GUARD -> carry("spear")
            TownRole.FARMER -> carry("hoe")
            TownRole.FISHER -> carry("rod")
            TownRole.PRIEST -> carry("book")
            TownRole.BARD -> if (activity != Activity.LUTE && activity != Activity.MINSTREL) out += "lute_slung"
            TownRole.MAGE -> carry("staff")
            else -> {}
        }
        if (lantern) carry("lantern")
        for (thing in CARRIED_LOOKS) if (thing in pieces) carry(thing)
        return out
    }
}
