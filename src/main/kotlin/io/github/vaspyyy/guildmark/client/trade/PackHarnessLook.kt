package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.trade.carry.PackFollow
import io.github.vaspyyy.guildmark.trade.carry.PackKit
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.DonkeyRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.entity.state.LlamaRenderState
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.util.context.ContextKey
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.animal.equine.Llama
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.joml.Vector3f
import kotlin.math.max

/** The three animals a Pack Harness fits, each with a mesh of its own in X2's art (a donkey and a mule are the same renderer and not the same size). */
internal enum class HarnessKind {
    DONKEY,
    MULE,
    LLAMA,
    ;

    companion object {
        fun of(animal: AbstractChestedHorse): HarnessKind = when {
            animal is Llama -> LLAMA
            animal.type == EntityTypes.MULE -> MULE
            else -> DONKEY
        }
    }
}

/** A good that shows on top of the panniers: its model made ready to draw, and where its middle is and how big it is, to set it down by its middle at the size wanted. */
internal class ShownGood(val model: ItemStackRenderState, val middle: Vector3f, val extent: Float)

/**
 * How one pack animal's harness stands at this moment, as the layer draws it (build part D1): the colour of its blanket, how full the
 * panniers are (between steps while they change, with a squash as they take the change), how its weight is swinging, where its bell is
 * and what shows on top.
 */
internal class HarnessFrame(
    val kind: HarnessKind,
    val colour: Int,
    val fill: Float,
    val pop: Float,
    val pitch: Float,
    val roll: Float,
    val bounce: Float,
    val dangle: Float,
    val bell: Float,
    val trot: Float,
    val goods: List<ShownGood>,
)

/**
 * What this client keeps of one pack animal's harness, worked out only while it is drawn and stepped once for each tick that has passed
 * since: the panniers settling to how full they are, their weight and the straps' ends swinging as the animal speeds up, slows down, turns
 * and lands, and the bell on the breast strap, which is swung by each stride the animal walks (a stride is the length the server rings the
 * bell for, [PackFollow.WALK_STRIDE] walking and [PackFollow.TROT_STRIDE] trotting, so the swing and the ring come together), hangs still
 * while the animal stands, and is hushed (nearly) when the kit says so.
 */
internal class HarnessLook : Look() {
    private val motion = Motion()
    private val pitch = Hinge(PULL, DRAG, 0.30f)
    private val roll = Hinge(PULL, DRAG, 0.30f)
    private val bounce = Hinge(0.45f, 0.40f, 2.0f)
    private val dangle = Hinge(0.14f, 0.08f, 0.8f)
    private val bell = Hinge(0.22f, 0.06f, 0.9f)

    private var colour = 0
    private var fill = 0f
    private var fillO = 0f
    private var lastFill = 0
    private var pop = 0f
    private var popO = 0f
    private var trot = 0f
    private var trotO = 0f
    private var walked = 0f
    private var side = 1f
    private var hush = 1f

    /** The ids of the goods that show, and the models made of them. */
    private var ids: List<Int> = emptyList()
    private var goods: List<ShownGood> = emptyList()

    /** Steps what is kept of [e] up to this tick; [kit] is what it wears. */
    fun update(e: AbstractChestedHorse, kit: PackKit) {
        colour = kit.colour.coerceIn(0, PackKit.COLOURS - 1)
        hush = if (kit.bell) 1f else HUSHED
        if (kit.shown != ids) {
            ids = kit.shown
            goods = shownGoods(e, ids)
        }
        val steps = motion.advance(e)
        if (steps < 0) {
            pitch.rest()
            roll.rest()
            bounce.rest()
            dangle.rest()
            bell.rest()
            fill = kit.fill.toFloat()
            fillO = fill
            lastFill = kit.fill
            pop = 0f
            popO = 0f
            trot = 0f
            trotO = 0f
            walked = 0f
            return
        }
        for (i in 0 until steps) step(kit, i == 0)
    }

    private fun step(kit: PackKit, fresh: Boolean) {
        // The panniers fill (or empty) a step at a time, squashing a little as they take it
        fillO = fill
        fill = Mth.approach(fill, kit.fill.toFloat(), 1f / SETTLE)
        popO = pop
        if (kit.fill != lastFill) {
            lastFill = kit.fill
            pop = 1f
        } else {
            pop = max(0f, pop - 1f / POP)
        }
        trotO = trot
        trot += ((if (motion.moved > PackFollow.TROT_SPEED) 1f else 0f) - trot) * 0.2f
        // The bell: knocked by every stride that is walked, and swung by the animal's own starts and stops
        if (motion.moved >= STANDING) {
            walked += motion.moved
            val stride = if (motion.moved > PackFollow.TROT_SPEED) PackFollow.TROT_STRIDE else PackFollow.WALK_STRIDE
            if (walked >= stride) {
                walked = 0f
                side = -side
                bell.kick(side * (if (motion.moved > PackFollow.TROT_SPEED) TROT_KICK else WALK_KICK) * hush)
            }
        }
        val forward = if (fresh) motion.forward else 0f
        val left = if (fresh) motion.left else 0f
        val up = if (fresh) motion.up else 0f
        bell.step(BELL_PUSH * forward * hush)
        pitch.step(PUSH_PITCH * forward)
        roll.step(PUSH_ROLL * left)
        bounce.step(-PUSH_BOUNCE * up)
        dangle.step(PUSH_DANGLE * forward + PUSH_DANGLE * 0.8f * left - 0.9f * up)
    }

    /** How the harness stands [partial] of the way through this tick. */
    fun frame(kind: HarnessKind, partial: Float): HarnessFrame = HarnessFrame(
        kind = kind, colour = colour, fill = Mth.lerp(partial, fillO, fill), pop = Mth.lerp(partial, popO, pop),
        pitch = pitch.at(partial), roll = roll.at(partial), bounce = bounce.at(partial), dangle = dangle.at(partial),
        bell = bell.at(partial), trot = Mth.lerp(partial, trotO, trot), goods = goods,
    )

    private companion object {
        const val PULL = 0.24f
        const val DRAG = 0.32f

        /** How hard speeding up, slowing, turning and landing push the panniers' weight. */
        const val PUSH_PITCH = 0.8f
        const val PUSH_ROLL = 0.9f
        const val PUSH_BOUNCE = 4.0f
        const val PUSH_DANGLE = 2.0f

        /** How hard the animal's own speeding up swings the bell, and how hard a stride knocks it, walking and trotting (radians of speed). */
        const val BELL_PUSH = 1.4f
        const val WALK_KICK = 0.12f
        const val TROT_KICK = 0.20f

        /** What is left of the bell's swing when it is hushed, and the least a tick's walk that counts as walking (blocks). */
        const val HUSHED = 0.12f
        const val STANDING = 0.02f

        /** Ticks for the panniers to settle one step of fullness, and for the squash of a change to die away. */
        const val SETTLE = 8f
        const val POP = 7f

        /** Makes the models of the goods whose item ids are [ids] (up to three), skipping any this version does not know. */
        fun shownGoods(e: LivingEntity, ids: List<Int>): List<ShownGood> = ids.mapNotNull { id ->
            val item = Item.byId(id)
            if (item === Items.AIR) return@mapNotNull null
            val model = ItemStackRenderState()
            Minecraft.getInstance().itemModelResolver.updateForLiving(model, ItemStack(item), ItemDisplayContext.FIXED, e)
            if (model.isEmpty) return@mapNotNull null
            val box = model.modelBoundingBox
            ShownGood(
                model,
                Vector3f(((box.minX + box.maxX) * 0.5).toFloat(), ((box.minY + box.maxY) * 0.5).toFloat(), ((box.minZ + box.maxZ) * 0.5).toFloat()),
                max(box.xsize, max(box.ysize, box.zsize)).toFloat().coerceAtLeast(1e-3f),
            )
        }
    }
}

/**
 * The client's side of the pack animals' harnesses: the render-state modifiers that give each harnessed donkey, mule and llama its frame
 * (registered for the donkey renderer, which draws mules too, and for the llama's), and hide the game's own chest boxes under the
 * wicker panniers. Nothing here does anything until the harness's art is in ([PackHarnessArt.table]); [PackHarnessArt.baked] says when it is.
 */
internal object PackHarnessClient {
    val FRAME: ContextKey<HarnessFrame> = ContextKey(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "harness_frame"))

    private val looks = Looks { HarnessLook() }

    /** For donkeys and mules: the frame, and the two chest boxes taken off when the panniers cover them. */
    fun donkey(animal: AbstractChestedHorse, state: DonkeyRenderState) {
        val frame = frame(animal, state) ?: return
        state.setRenderData(FRAME, frame)
        if (PackHarnessArt.baked?.hidesChests == true) state.hasChest = false
    }

    /** For llamas: the same. */
    fun llama(animal: Llama, state: LlamaRenderState) {
        val frame = frame(animal, state) ?: return
        state.setRenderData(FRAME, frame)
        if (PackHarnessArt.baked?.hidesChests == true) state.hasChest = false
    }

    private fun frame(animal: AbstractChestedHorse, state: LivingEntityRenderState): HarnessFrame? {
        state.setRenderData(FRAME, null)
        if (PackHarnessArt.baked == null || state.isBaby) return null
        val kit = PackKit.of(animal) ?: return null
        val look = looks.of(animal)
        look.update(animal, kit)
        return look.frame(HarnessKind.of(animal), state.partialTick)
    }

    /** Lets go of everything kept (on leaving a world). */
    fun reset() {
        looks.clear()
    }
}
