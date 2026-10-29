package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.homestead.HomesteadRegistry
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * The handcart tale's scenes (living-world/stories/design.md 8.8): the neighbour at the empty house (S1's variant), *The Wheel* at the camp,
 * the bundle packed at first light, and the homecoming at the door after the end (the loaf for A, the thanks for B and C). Each is a [Stage]
 * of the scenes' own moves; the ones at the camp are played by [CartWatch] (no capital's stage reaches the road), the S1 variant by the
 * tale's own `scene`, and the homecoming through the capital's stage.
 */
internal object CartScenes {
    /** The tales whose S1 variant has been played this run (once is enough: the empty house does not need its washing hung twice). */
    private val waited = HashSet<Int>()

    private fun eye(b: Townsperson): () -> Vec3? = { b.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }

    private fun eye(p: ServerPlayer?, or: Townsperson): () -> Vec3? = { p?.takeIf { it.isAlive && !it.isRemoved }?.eyePosition ?: or.eyePosition.add(or.lookAngle.scale(2.0)) }

    // ---- S1: the neighbour at the empty house ----

    /**
     * The neighbour, by day (10:00 to 18:00), with an armful of washing, walks to two blocks out from the empty house's door, stands looking
     * at it and sighs. A passer-by of the street comes up and says a comforting word (`tale.set.comfort`), and the neighbour answers with what
     * they miss (`tale.cart.passing`) and sighs again. About thirty seconds. Played once a run; null if the neighbour or the door is not about.
     */
    fun waiting(c: Ctx, t: Tale): Stage? {
        if (t.id in waited) return null
        val level = c.level
        val m = Cart.moved(level, t) ?: return null
        val door = Cart.door(c.here.places, m.home) ?: return null
        val neighbour = TaleBodies.body(level, t, Cart.NEIGHBOUR) ?: return null
        val doorAt = Vec3.atCenterOf(door.pos).add(0.0, 0.6, 0.0)
        if (neighbour.distanceToSqr(doorAt) > FROM_DOOR * FROM_DOOR) return null
        val spot = Sets.standable(level, door.pos.relative(door.inward.opposite, 2)) ?: return null
        val spotAt = Vec3.atBottomCenterOf(spot)
        val passer = Sets.passerBy(neighbour, r = 24.0)
        val by = passer?.let { Sets.beside(level, spotAt, it.position(), 1.6) }
        val n = ArrayList<Move>()
        n += Stage.Hold(Sets.prop("washing"))
        n += GoTo({ spot }, Jobs.STROLL, near = 0.8, overlay = Activity.CARRY, most = 600)
        n += Stage.Stand({ spotAt }, { doorAt }, 30, Activity.CARRY)
        n += Play(Activity.CARRY, 80, at = { doorAt })
        n += Play(Activity.SIGH, at = { doorAt })
        val parts = ArrayList<Stage.Part>()
        if (passer != null && by != null) {
            n += Stage.Barrier("sighed", 900, Activity.CARRY, { doorAt })
            n += Stage.Barrier("meet", 500, Activity.CARRY, eye(passer))
            n += Turn(eye(passer))
            n += Stage.Barrier("said", 300, Activity.LISTEN, eye(passer))
            n += Stage.Say("tale.cart.passing", gesture = Activity.CARRY, at = eye(passer))
            n += Stage.Barrier("answered", 200, Activity.CARRY, eye(passer))
            val p = ArrayList<Move>()
            p += Stage.Barrier("sighed", 900)
            p += GoTo({ by }, Jobs.STROLL, near = 1.2, most = 500)
            p += Turn(eye(neighbour))
            p += Stage.Barrier("meet", 500, at = eye(neighbour))
            p += Play(Activity.NOD, at = eye(neighbour))
            p += Stage.Say("tale.set.comfort", gesture = Activity.TALK, at = eye(neighbour))
            p += Stage.Barrier("said", 300, at = eye(neighbour))
            p += Stage.Barrier("answered", 300, Activity.LISTEN, eye(neighbour))
            p += Play(Activity.NOD, at = eye(neighbour))
            parts += Stage.Part("passer", passer, essential = false, moves = p)
        } else {
            n += Play(Activity.CARRY, 60, at = { doorAt })
        }
        n += Play(Activity.SIGH, at = { doorAt })
        n += Stage.Hold(ItemStack.EMPTY)
        parts.add(0, Stage.Part(Cart.NEIGHBOUR, neighbour, moves = n))
        val stage = Stage("cart_waiting", parts, 10.0..18.0, spotAt)
        stage.then = { played -> if (played) waited += t.id }
        return stage
    }

    /** How far from the empty house's door the neighbour may be for S1 to be played. */
    private const val FROM_DOOR = 48.0

    // ---- The Wheel ----

    /**
     * *The Wheel* (8.8): the parent takes up the timber from the ground by the off wheel (TAKE, the planks in the hand), saws a felloe from it
     * (SAW, a stroke heard every second), nails it on (NAIL, a blow and sparks every sixteen ticks), and the wheel is on again ([fixed], a
     * thunk and dust); then they turn to the player and say so (`tale.cart.mended`), and a little green rises. The kin watch, and cheer as
     * it is done (HURRAH for the grown, JUMP for the young, then all CLAP). About fourteen seconds. [wheel] is where the off wheel lies; with
     * [stand] the parent walks to that spot by it first.
     */
    fun wheel(
        level: ServerLevel, wheel: Vec3, parent: Townsperson, kin: List<Townsperson>, player: ServerPlayer?, stand: Vec3? = null, fixed: () -> Unit,
    ): Stage {
        val at = { wheel }
        val you = eye(player, parent)
        val p = ArrayList<Move>()
        // Played away from a camp (`tale scene wheel`): they walk up to the wheel first
        if (stand != null) {
            p += GoTo({ BlockPos.containing(stand) }, Jobs.WALK, near = 0.8, most = 400)
            p += Stage.Stand({ stand }, at, 40)
        }
        p += Turn(at)
        p += Stage.Beat(Activity.TAKE, at = at) { b, i -> if (i == TAKE_GRAB) b.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.OAK_PLANKS, 4)) }
        p += Now { b ->
            b.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(HomesteadRegistry.SAW_ITEM.get()))
            b.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.OAK_PLANKS))
        }
        p += Stage.Beat(Activity.SAW, SAW_TICKS, at = at) { _, i ->
            if (i % SAW_EVERY == SAW_STROKE) Stage.Fx.sound(level, HomesteadRegistry.SAW.get(), wheel, 0.7f, 0.9f + level.random.nextFloat() * 0.2f)
        }
        p += Now { b ->
            b.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(HomesteadRegistry.HAMMER_ITEM.get()))
            b.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY)
        }
        p += Stage.Beat(Activity.NAIL, NAIL_TICKS, at = at) { _, i ->
            if (i % Activity.NAIL.ticks == NAIL_HIT) {
                Stage.Fx.sound(level, HomesteadRegistry.HAMMER.get(), wheel, 0.8f, 0.95f + level.random.nextFloat() * 0.1f)
                Stage.Fx.play(level, Stage.Fx.Kind.SPARK, wheel)
            }
        }
        p += Now { b ->
            b.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY)
            b.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY)
            fixed()
        }
        p += Stage.Fx(Stage.Fx.Kind.MEND, { wheel })
        p += Stage.Barrier("mended", 40, at = at)
        p += Turn(you)
        p += Stage.Say("tale.cart.mended", gesture = Activity.NOD, at = you)
        p += Stage.Fx(Stage.Fx.Kind.HAPPY, { wheel })
        val parts = ArrayList<Stage.Part>()
        parts += Stage.Part(Cart.PARENT, parent, moves = p)
        for ((i, k) in kin.take(KIN_IN_SCENES).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(at)
            m += Stage.Barrier("mended", 500, at = at)
            m += Play(if (k.role.adult) Activity.HURRAH else Activity.JUMP, at = you)
            m += Play(Activity.CLAP, at = you)
            parts += Stage.Part("kin${i + 1}", k, essential = false, moves = m)
        }
        return Stage("wheel", parts, null, wheel, most = WHEEL_MOST)
    }

    /** The clip ticks of the Wheel: the grab of TAKE, three strokes of the saw, four blows of the hammer (each clip's own beat). */
    private const val TAKE_GRAB = 12
    private const val SAW_TICKS = 60
    private const val SAW_EVERY = 20
    private const val SAW_STROKE = 6
    private const val NAIL_TICKS = 64
    private const val NAIL_HIT = 5
    private const val WHEEL_MOST = 900

    /** How many of the kin are in the Wheel (all of them: the camp has three at most). */
    private const val KIN_IN_SCENES = 3

    // ---- The pack at first light ----

    /**
     * The last bundle onto the cart (the household's own heap, the move's way): the parent carries it to the cart's tail, stands square behind
     * it, sets it down (PUT_DOWN) and heaves it up onto its place (PACK, on at tick 25), and [packed] heaps the whole household on. Null with
     * no cart parked or nowhere to stand at its tail.
     */
    fun pack(parent: Townsperson, packed: () -> Unit): Stage? {
        val tail = Handcarts.tail(parent, BUNDLE_PLACE) ?: return null
        val ahead = { Handcarts.ahead(parent, tail) }
        val m = ArrayList<Move>()
        m += Stage.Hold(Handcarts.BUNDLE)
        m += GoTo({ BlockPos.containing(tail) }, Jobs.WALK, near = 0.8, overlay = Activity.CARRY, most = 300)
        m += Stage.Stand({ tail }, ahead, 40, Activity.CARRY)
        m += Stage.Beat(Activity.PUT_DOWN, at = ahead) { b, i -> if (i == Activity.PUT_DOWN.ticks - 1) Stage.give(b, null) }
        m += Stage.Beat(Activity.PACK, at = ahead) { _, i -> if (i == PACK_SET) packed() }
        return Stage("cart_pack", listOf(Stage.Part(Cart.PARENT, parent, moves = m)), null, tail, most = PACK_MOST)
    }

    /** The place on the bed the last bundle goes onto (the left of the two at the back, where PACK sets it), and the tick it is set there. */
    const val BUNDLE_PLACE = 5
    private const val PACK_SET = 25
    private const val PACK_MOST = 600

    // ---- The homecoming ----

    /**
     * After the end, at the door (A, B and C): for A the neighbour brings the loaf they baked in case they came (`tale.cart.loaf`, HAND_OVER
     * as the parent RECEIVEs it, the loaf passing at the clips' beat); then the parent turns to the player and thanks them (`tale.cart.thanks`,
     * THANK). About fifteen seconds with the loaf, five without.
     */
    fun homecoming(parent: Townsperson, neighbour: Townsperson?, player: ServerPlayer, loaf: Boolean): Stage? {
        val level = parent.level() as? ServerLevel ?: return null
        val you = eye(player, parent)
        val p = ArrayList<Move>()
        val parts = ArrayList<Stage.Part>()
        if (loaf && neighbour != null && neighbour !== parent) {
            val spot = Sets.beside(level, parent.position(), neighbour.position(), 1.2) ?: return null
            val n = ArrayList<Move>()
            n += Stage.Hold(ItemStack(Items.BREAD))
            n += GoTo({ spot }, Jobs.WALK, near = 0.9, most = 400)
            n += Turn(eye(parent))
            n += Stage.Barrier("hand", 400, at = eye(parent))
            n += Stage.Say("tale.cart.loaf", gesture = Activity.TALK, at = eye(parent))
            n += Stage.Barrier("said", 200, at = eye(parent))
            n += Stage.Beat(Activity.HAND_OVER, at = eye(parent)) { b, i -> if (i == HAND_GIVE) Stage.give(b, null) }
            n += Stage.Barrier("handed", 100, at = eye(parent))
            n += Play(Activity.NOD, at = eye(parent))
            parts += Stage.Part(Cart.NEIGHBOUR, neighbour, moves = n)
            p += Stage.Barrier("hand", 600, at = eye(neighbour))
            p += Stage.Barrier("said", 300, Activity.LISTEN, eye(neighbour))
            p += Stage.Beat(Activity.RECEIVE, at = eye(neighbour)) { b, i -> if (i == HAND_TAKE) Stage.give(b, ItemStack(Items.BREAD)) }
            p += Stage.Barrier("handed", 100, at = eye(neighbour))
            p += Play(Activity.NOD, at = eye(neighbour))
        }
        p += Turn(you)
        p += Stage.Say("tale.cart.thanks", gesture = Activity.THANK, at = you)
        p += Stage.Hold(ItemStack.EMPTY)
        parts.add(0, Stage.Part(Cart.PARENT, parent, moves = p))
        return Stage("cart_home", parts, null, parent.position())
    }

    /** The beats the loaf passes at: let go at tick 16 of HAND_OVER, taken at tick 12 of RECEIVE. */
    private const val HAND_GIVE = 16
    private const val HAND_TAKE = 12

    // ---- Forgetting ----

    fun forget(tale: Int) {
        waited.remove(tale)
    }

    fun forgetAll() = waited.clear()
}
