package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleGuard
import io.github.vaspyyy.guildmark.tales.TaleHold
import io.github.vaspyyy.guildmark.tales.TaleLeave
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.ComingsBodies
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import io.github.vaspyyy.guildmark.wardfire.Wardfires
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The handcart tale's once-a-second look at the world (living-world/stories/design.md 8.8), which no step of the table can be: the camp on the
 * road is made when a player comes near and found when one comes up to it, the Wheel is played when the parent is free to, the guard stands at
 * the gate while the watch is to be asked, the night ends at first light, and the family packs up and walks in. Everything here is memory: the
 * record ([Cart]'s numbers and flags) is the truth, and the camp's layout, the bodies' marks and how far the going has got are worked out
 * again from it after a restart (a going cut short by one is the record's: the comings bring the family home).
 *
 * - **The camp.** Laid out once the land round the place on the road is loaded: beside the wardfire if one stands within [Cart.CLEARING]
 *   blocks (three blocks from it toward the road), else three blocks off the road on its more level side. The cart stands square to the
 *   road with its broken near wheel toward it; the parent crouches at the wheel, the first grown kin keeps a lookout toward the road, a second
 *   folds their arms on the far side, and the young sit with their blanket behind the cart (they shiver in the dark until the fire is lit).
 * - **The find.** The first player within [MEET] blocks of the parent takes the tale up, and the parent calls out to them.
 * - **The Wheel.** Once the timber is given and the parent has done talking, *The Wheel* is played ([CartScenes.wheel]); the cart is drawn
 *   broken until the felloe is nailed on.
 * - **The going** (step 5, at first light): the watch comes up the road and salutes (B), the parent sends the player on ahead, packs the last
 *   bundle and takes up the shafts, and the family walks the road home in a file. Once nobody sees them (or nobody is near, or it has taken
 *   too long) their figures are gone and the comings bring the household home through its gate ([handOff]): the tale ends at their door.
 * - **The homecoming**, after the end (A, B and C): at the door, the loaf from the neighbour and the parent's thanks.
 */
internal object CartWatch {
    /**
     * The camp's layout: its [centre] (the cart's axle, on the ground), [r] the unit step from it toward the road (the cart's right, where the
     * broken wheel is), [f] the cart's forward (toward its shafts), and the wardfire it is beside, if any.
     */
    internal class Camp(val centre: Vec3, val r: Vec3, val f: Vec3, val fire: BlockPos?) {
        /** [along] blocks toward the road and [side] toward the shafts from the centre, [up] above the ground. */
        fun at(along: Double, side: Double, up: Double = 0.0): Vec3 =
            Vec3(centre.x + r.x * along + f.x * side, centre.y + up, centre.z + r.z * along + f.z * side)

        /** The cart's yaw. */
        val yaw: Float = yawOf(f)

        /** Where the off wheel lies, propped against the bed (client/cart's HandcartModel: the wheel's hub when broken). */
        val wheel: Vec3 get() = at(0.88, 0.66, 0.62)
    }

    private val camps = HashMap<Int, Camp>()
    private val fronts = HashMap<Int, BlockPos>()

    /** The bodies set exactly on their marks once (the hold keeps them within a block and a half of it, not on it). */
    private val snapped = HashSet<UUID>()

    /** The `tale:slot`s this run holds at the gate. */
    private val placed = HashSet<String>()

    /** The Wheel to be played after the mending, by tale: asked for at [since] by [mender]; its stage once begun; whether the wheel is on. */
    private class Wheel(val since: Long, val mender: UUID) {
        var stage: Stage? = null
        var fixed = false
    }

    private val wheels = HashMap<Int, Wheel>()

    /** How far the going at first light has got, by tale. */
    private class Going(val at: Long) {
        var phase = Phase.SAY
        var since = at
        var stage: Stage? = null
        var said = false
        var walkAt = -1L
        var way: List<BlockPos>? = null
        val sent = HashSet<String>()
        val started = HashSet<String>()
        val done = HashSet<String>()
    }

    private enum class Phase { WATCH, SAY, PACK, FETCH, WALK }

    private val goings = HashMap<Int, Going>()

    /** The homecomings to be played at the door after the end, by tale: the ending, and when it ended (game time). */
    private class Homecoming(val ending: String, val since: Long) {
        var stage: Stage? = null
    }

    private val homecomings = HashMap<Int, Homecoming>()

    /** How near a player must be to the camp for it to be made, and how near the parent for the find. */
    private const val CAMP_SEEN = 80.0
    private const val MEET = 7.0

    /** How near the gate the guard's day body must be to be held there, if a player could see them go. */
    private const val GATE_NEAR = 44.0

    /** Ticks the Wheel waits for the parent to be free before the wheel is simply on. */
    private const val WHEEL_WAITS = 400L

    /** The going: how near a player must be for it to be played, how long the watch has to come up, the parent to speak, pack and fetch. */
    private const val GOING_SEEN = 64.0
    private const val WATCH_FOR = 900L
    private const val SAY_FOR = 80L
    private const val PACK_FOR = 600L
    private const val FETCH_FOR = 400L

    /** The walk: ticks between one walker and the next, its longest, and when it is cut short with nobody near or at all. */
    private const val STAGGER = 30L
    private const val WALK_MOST = 3600
    private const val HIDDEN_AFTER = 200L
    private const val LONELY_AFTER = 1800L
    private const val LONELY = 24.0
    private const val GOING_MOST = 3600L + 2400L

    /** The walk home stops this far short of the gate's front: the comings set the household down on the road and walk it in. */
    private const val SHORT_OF_GATE = 30.0

    /** Whether a body is seen: a player this near, or within [SIGHT] looking its way with a clear line to it. */
    private const val CLOSE = 12.0
    private const val SIGHT = 96.0
    private const val LOOKING = 0.35

    /** How far down the road toward the town the watch is made (out of sight), and how long the family's walk on is if they go on (W). */
    private const val WATCH_FROM = 24.0
    private const val WATCH_TO = 60.0
    private const val OUTWARD = 48

    /** How long after the end the homecoming may still be played, and how near a player must be to the parent for it. */
    private const val HOME_FOR = 6000L
    private const val HOME_SEEN = 20.0

    // ---- For the template ----

    /** Where the camp is, once laid out. */
    fun campAt(t: Tale): Vec3? = camps[t.id]?.centre

    /** Where the off wheel lies, once the camp is laid out. */
    fun wheelAt(t: Tale): Vec3? = camps[t.id]?.wheel

    /** The front of the gate the household left by. */
    fun frontOf(t: Tale): BlockPos? {
        fronts[t.id]?.let { return it }
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return null
        val c = TaleEngine.ctx(level, t.capital) ?: return null
        return frontOf(c, t)
    }

    private fun frontOf(c: Ctx, t: Tale): BlockPos = fronts.getOrPut(t.id) { Cart.front(c, t) }

    /** Whether the family is setting off just now (the kills no longer count). */
    fun going(tale: Int): Boolean = tale in goings

    /** The wheel is mended: the Wheel is played as soon as the parent is free to. */
    fun mend(c: Ctx, t: Tale, player: ServerPlayer) {
        wheels[t.id] = Wheel(c.level.gameTime, player.uuid)
    }

    /** The road is kept: the parent says so. */
    fun safe(c: Ctx, t: Tale) {
        val body = figure(c.level, t, Cart.PARENT) ?: return
        c.level.getNearestPlayer(body, 24.0)?.let { body.lookAt(it.eyePosition) }
        body.act(Activity.NOD)
        Words.sayWith(body, "tale.cart.safe", why = "a family on the road")
    }

    /** The watch is paid: the guard goes back to their day until the watch rides. */
    fun paid(c: Ctx, t: Tale) {
        placed.remove(key(t, Cart.GUARD))
        Tales.release(c.level, t, Cart.GUARD)
    }

    /** The household is at its door: the going is over. */
    fun atDoor(c: Ctx, t: Tale) {
        goings.remove(t.id)
    }

    /** The guard's slot changed hands: the new one is held at the gate from the next second. */
    fun forgetGuard(t: Tale) {
        placed.remove(key(t, Cart.GUARD))
    }

    // ---- Once a second ----

    /** From the tick, once a second: each open cart tale is looked at, guarded; then the homecomings of those that ended. */
    fun watch(server: MinecraftServer) {
        if (!Tales.live) return
        val level = server.overworld()
        for (t in TaleEngine.openTales(level)) {
            if (t.template != Cart.id) continue
            val c = TaleEngine.ctx(level, t.capital) ?: continue
            TaleGuard.run(c, t, "the camp on the road") { see(c, t) }
        }
        if (homecomings.isNotEmpty()) TaleEngine.guarded("the cart's homecoming") { homecomings(level) }
    }

    private fun see(c: Ctx, t: Tale) {
        val level = c.level
        if (!record(c, t) || !t.open) return
        guard(c, t)
        if (t.step == Cart.AT_ROAD && Cart.ready(t) && Cart.firstLight(level, t)) c.go(t, Cart.AT_HOME)
        if (!t.open) return
        if (t.step == Cart.AT_HOME) return going(c, t)
        if (Tales.scenes()) camp(c, t)
    }

    /**
     * The household as the record has it, which the tale must follow: home already (the comings' party walking in is left to tell the tale at
     * the door; one that is there ends it, A, B or C as the way was made if the tale sent them, C if they came on their own); gone from the
     * record (no one left to fetch: W); the parent's record gone with the rest still away (the next grown one takes the shafts). False if the
     * tale has nothing more to do this second.
     */
    private fun record(c: Ctx, t: Tale): Boolean {
        val level = c.level
        val residents = Residents.get(level)
        val parent = residents[Cart.parentId(t)]
        val family = Cart.family(t)
        if (Cart.moved(level, t) == null) {
            if (parent != null && parent.alive && parent.left < 0) {
                if (ComingsBodies.busy(parent.id)) return false
                if (Cart.flag(t, Cart.HOME)) {
                    c.end(t, Cart.route(t))
                    return false
                }
                Cart.set(t, Cart.OWN)
                c.why(t, "cart.own", family)
                c.data.changed()
                c.end(t, Ending.C)
                return false
            }
            c.why(t, "cart.noone", family)
            c.end(t, Ending.W)
            return false
        }
        if (Cart.flag(t, Cart.HOME)) return false
        if (parent == null || parent.alive || parent.died >= 0 || parent.left < 0) {
            val old = Cart.familySlots(t)
            if (!Cart.nextParent(level, t)) {
                c.why(t, "cart.noone", family)
                c.end(t, Ending.W)
                return false
            }
            // The figures are made again with their names
            for (slot in old) TaleBodies.release(level, t, slot)
            c.data.changed()
        }
        return true
    }

    // ---- The gate ----

    /** The guard stands at the gate while the watch is to be paid; once it is, or the night is over, they go back to their day. */
    private fun guard(c: Ctx, t: Tale) {
        val level = c.level
        val cast = t.cast(Cart.GUARD) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        val key = key(t, Cart.GUARD)
        val wanted = Tales.scenes() && t.step == Cart.AT_ROAD && Cart.flag(t, Cart.WATCH) && !Cart.flag(t, Cart.PAID)
        if (!wanted) {
            if (key in placed || cast.held) {
                placed.remove(key)
                Tales.release(level, t, Cart.GUARD)
            }
            return
        }
        if (key in placed) return
        val front = frontOf(c, t)
        val body = TaleBodies.body(level, t, Cart.GUARD)
        if (body != null && body.tale != t.id && body.distanceToSqr(Vec3.atCenterOf(front)) > GATE_NEAR * GATE_NEAR && !Comings.unseen(level, body.blockPosition())) return
        if (Tales.hold(level, t, Cart.GUARD, front, Activity.STAND_GUARD)) placed += key
    }

    // ---- The camp ----

    /** The camp, while a player is within [CAMP_SEEN] blocks of it: laid out, the family at their marks, the cart, the fire, the find, the Wheel. */
    private fun camp(c: Ctx, t: Tale) {
        val level = c.level
        val camp = layout(c, t) ?: return
        if (level.getNearestPlayer(camp.centre.x, camp.centre.y, camp.centre.z, CAMP_SEEN, false) == null) return
        lit(c, t, camp)
        family(level, t, camp)
        cart(level, t, camp)
        meet(c, t)
        wheel(level, t, camp)
    }

    /**
     * Lays the camp out once the land within the clearing's reach of the place on the road is loaded (a wardfire is looked for in loaded land
     * only): see [Camp].
     */
    private fun layout(c: Ctx, t: Tale): Camp? {
        camps[t.id]?.let { return it }
        val level = c.level
        val spot = Cart.road(t)
        val reach = Cart.CLEARING + 4
        for (cx in ((spot.x - reach) shr 4)..((spot.x + reach) shr 4)) for (cz in ((spot.z - reach) shr 4)..((spot.z + reach) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) return null
        }
        val fire = WardfireSites.nearest(level, spot, Cart.CLEARING)
        val run = WayOut.run(level, c.here, spot)
        val across = Vec3(-run.z, 0.0, run.x)
        val r: Vec3
        val at: Vec3
        if (fire != null) {
            val dx = spot.x - fire.x.toDouble()
            val dz = spot.z - fire.z.toDouble()
            val len = sqrt(dx * dx + dz * dz)
            r = if (len < 1.0) across.scale(-1.0) else Vec3(dx / len, 0.0, dz / len)
            at = Vec3(fire.x + 0.5 + r.x * FROM_FIRE, fire.y.toDouble(), fire.z + 0.5 + r.z * FROM_FIRE)
        } else {
            val side = levelSide(level, spot, across)
            r = side.scale(-1.0)
            at = Vec3(spot.x + 0.5 + side.x * OFF_ROAD, spot.y.toDouble(), spot.z + 0.5 + side.z * OFF_ROAD)
        }
        val ground = Sets.standable(level, BlockPos.containing(at)) ?: Sets.standable(level, spot)
        val centre = Vec3(at.x, (ground ?: spot).y.toDouble(), at.z)
        val camp = Camp(centre, r, Vec3(r.z, 0.0, -r.x), fire)
        camps[t.id] = camp
        return camp
    }

    /** Blocks from the wardfire to the camp's centre, and from the road with no fire. */
    private const val FROM_FIRE = 3.0
    private const val OFF_ROAD = 3.0

    /** The side of the road ([across] or its opposite) whose ground three blocks off is nearer the road's own height. */
    private fun levelSide(level: ServerLevel, spot: BlockPos, across: Vec3): Vec3 {
        fun height(side: Vec3): Int {
            val at = BlockPos.containing(spot.x + 0.5 + side.x * OFF_ROAD, spot.y.toDouble(), spot.z + 0.5 + side.z * OFF_ROAD)
            return Sets.standable(level, at)?.let { kotlin.math.abs(it.y - spot.y) } ?: 99
        }
        val back = across.scale(-1.0)
        return if (height(back) < height(across)) back else across
    }

    /** The camp's wardfire is lit (by anyone, before the tale or since): three kills keep the road. */
    private fun lit(c: Ctx, t: Tale, camp: Camp) {
        if (Cart.flag(t, Cart.LIT)) return
        val fire = camp.fire ?: return
        if (!c.level.isLoaded(fire) || !Wardfires.isLit(c.level.getBlockState(fire))) return
        Cart.set(t, Cart.LIT)
        c.data.changed()
    }

    /** One of the family's places at the camp: the slot, the exact spot and the way they face, the clip, who they are, and the blanket. */
    private class Mark(val slot: String, val at: Vec3, val yaw: Float, val clip: Activity?, val role: TownRole, val look: Int, val blanket: Boolean)

    /**
     * Where each of the family is at the camp: the parent at the off wheel (crouched over it until it is mended); the first grown kin on the
     * lookout toward the road; a second with folded arms on the far side; the young (and a third grown one) behind the cart with the
     * blanket, shivering in the dark until the fire is lit.
     */
    private fun marks(level: ServerLevel, t: Tale, camp: Camp): List<Mark> {
        val cold = Cart.dark(level) && !Cart.flag(t, Cart.LIT)
        val out = ArrayList<Mark>()
        val p = Cart.member(level, t, Cart.PARENT)
        val toWheel = yawOf(camp.r.scale(-1.0))
        out += Mark(Cart.PARENT, camp.at(1.9, 0.66), toWheel, if (Cart.flag(t, Cart.MENDED)) null else Activity.COWER, p?.role ?: TownRole.CITIZEN, p?.look ?: -1, false)
        var adults = 0
        var young = 0
        for (slot in Cart.KIN) {
            if (t.cast(slot)?.name.isNullOrBlank()) continue
            val r = Cart.member(level, t, slot)
            val role = r?.role ?: TownRole.CITIZEN
            val look = r?.look ?: -1
            if (role.adult && adults < 2) {
                out += if (adults == 0) Mark(slot, camp.at(1.0, 2.4), yawOf(camp.r), Activity.LOOKOUT, role, look, false)
                else Mark(slot, camp.at(0.8, -2.3), yawOf(camp.f), if (cold) Activity.SHIVER else Activity.ARMS_FOLDED, role, look, false)
                adults++
            } else {
                out += Mark(slot, camp.at(-1.5, 0.6 - 1.2 * young), yawOf(camp.r), if (cold) Activity.SHIVER else Activity.CARRY, role, look, !cold)
                young++
            }
        }
        return out
    }

    /** The family made at their marks (figures with the record's names and looks), set exactly on them once there, the blanket in the arms. */
    private fun family(level: ServerLevel, t: Tale, camp: Camp) {
        for (m in marks(level, t, camp)) {
            TaleBodies.figure(level, t, m.slot, BlockPos.containing(m.at), m.role, m.look, m.clip, m.yaw)
            val body = figure(level, t, m.slot) ?: continue
            if (body.director !is TaleHold || body.attending != null) continue
            if (body.uuid !in snapped && flat(body.position(), m.at) < SNAP) {
                snapped += body.uuid
                body.snapTo(m.at.x, body.y, m.at.z, m.yaw, 0f)
                body.yHeadRot = m.yaw
                body.yBodyRot = m.yaw
            }
            val blanket = Sets.prop("blanket")
            if (m.blanket && body.carried.isEmpty) {
                body.carried = blanket
                body.carriedUntil = 0
            } else if (!m.blanket && ItemStack.isSameItem(body.carried, blanket)) {
                body.carried = ItemStack.EMPTY
                body.carriedUntil = 0
            }
        }
    }

    /** How near their mark a body must be to be set exactly on it. */
    private const val SNAP = 1.4

    /**
     * The family's cart, the parent's (a real handcart, never saved): brought round and set square to the road at the camp's centre, heaped
     * with the household's goods but the last bundle (packed at first light), drawn with its near wheel off until the Wheel puts it on.
     */
    private fun cart(level: ServerLevel, t: Tale, camp: Camp) {
        if (!Handcarts.enabled()) return
        val parent = figure(level, t, Cart.PARENT) ?: return
        if (Handcarts.pulling(parent)) return
        val cart = Handcarts.parked(parent) ?: run {
            val made = Handcarts.hitch(parent) ?: return
            Handcarts.unhitch(parent)
            made.setPos(camp.centre.x, camp.centre.y, camp.centre.z)
            made.yRot = camp.yaw
            made.yRotO = camp.yaw
            made.settle(level)
            Handcarts.heap(parent, load(t, packed = false))
            made
        }
        val broken = broken(t)
        if (cart.broken != broken) cart.broken = broken
    }

    /** Whether the wheel is off: until it is mended, and while the Wheel is still putting it on. */
    private fun broken(t: Tale): Boolean = !Cart.flag(t, Cart.MENDED) || wheels[t.id]?.fixed == false

    /** The household's goods on the cart: all of them once [packed], else all but the last bundle. */
    private fun load(t: Tale, packed: Boolean): List<Handcarts.Placed> =
        Handcarts.household(Cart.familySlots(t).count { !t.cast(it)?.name.isNullOrBlank() }).filter { packed || it.place != CartScenes.BUNDLE_PLACE }

    /** The first player within [MEET] blocks of the parent takes the tale up: the camp is found, and the parent calls out. */
    private fun meet(c: Ctx, t: Tale) {
        if (Cart.flag(t, Cart.MET)) return
        val level = c.level
        val body = figure(level, t, Cart.PARENT) ?: return
        val who = level.players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(body) <= MEET * MEET }.minByOrNull { it.distanceToSqr(body) } ?: return
        Cart.set(t, Cart.MET)
        c.data.changed()
        Tales.join(level, t, who)
        c.why(t, "cart.found", who.gameProfile.name, Cart.family(t))
        body.lookAt(who.eyePosition)
        body.act(Activity.WAVE_OFF)
        Words.sayWith(body, "tale.cart.found", why = "a family on the road")
    }

    /** The Wheel, once the timber is given: played as soon as the parent is free; not begun in twenty seconds, the wheel is on all the same. */
    private fun wheel(level: ServerLevel, t: Tale, camp: Camp) {
        val w = wheels[t.id] ?: return
        val st = w.stage
        if (st != null) {
            if (!st.finished) return
            figure(level, t, Cart.PARENT)?.let { hands(it) }
            if (!w.fixed) fix(level, t, camp, w, shown = true)
            wheels.remove(t.id)
            return
        }
        if (level.gameTime - w.since > WHEEL_WAITS) {
            fix(level, t, camp, w, shown = true)
            wheels.remove(t.id)
            return
        }
        val parent = figure(level, t, Cart.PARENT) ?: return
        if (parent.attending != null || parent.director !is TaleHold) return
        val kin = Cart.KIN.mapNotNull { slot -> figure(level, t, slot)?.takeIf { it.attending == null && it.director is TaleHold } }
        val player = (level.getPlayerByUUID(w.mender) as? ServerPlayer)?.takeIf { it.distanceToSqr(parent) < 24.0 * 24.0 }
            ?: level.getNearestPlayer(parent, 24.0) as? ServerPlayer
        val stage = CartScenes.wheel(level, camp.wheel, parent, kin, player) { fix(level, t, camp, w, shown = false) }
        if (!stage.start(level)) return
        TaleLook.note(stage)
        w.stage = stage
    }

    /** The wheel is on: the cart is whole again ([shown]: with the dust and the thunk here, the Wheel having not played them). */
    private fun fix(level: ServerLevel, t: Tale, camp: Camp, w: Wheel, shown: Boolean) {
        w.fixed = true
        figure(level, t, Cart.PARENT)?.let { Handcarts.parked(it)?.broken = false }
        if (shown) {
            Stage.Fx.play(level, Stage.Fx.Kind.MEND, camp.wheel)
            Stage.Fx.play(level, Stage.Fx.Kind.HAPPY, camp.wheel)
        }
    }

    /** The tools out of the hands. */
    private fun hands(b: Townsperson) {
        b.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY)
        b.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY)
    }

    // ---- The going ----

    /**
     * Step 5: through the rest of the night the camp is as it was; at first light the family sets off ([Phase]). With nobody near enough to
     * see it, or no camp to set off from, the comings bring them home now.
     */
    private fun going(c: Ctx, t: Tale) {
        val level = c.level
        if (!Cart.firstLight(level, t)) {
            if (Tales.scenes()) camp(c, t)
            return
        }
        val now = level.gameTime
        var g = goings[t.id]
        if (g == null) {
            if (t.why.none { it.kind == "cart.dawn" }) c.why(t, "cart.dawn", Cart.family(t))
            val camp = camps[t.id]
            val parent = figure(level, t, Cart.PARENT)
            if (!Tales.scenes() || camp == null || parent == null || !near(level, camp.centre, GOING_SEEN)) return handOff(c, t)
            g = Going(now)
            g.phase = if (Cart.flag(t, Cart.PAID) && t.cast(Cart.GUARD) != null) Phase.WATCH else Phase.SAY
            goings[t.id] = g
        }
        val camp = camps[t.id] ?: return handOff(c, t)
        val bodies = walkers(level, t)
        if (bodies.isEmpty() || bodies.none { near(level, it.second.position(), GOING_SEEN) } || now - g.at > GOING_MOST) return handOff(c, t)
        when (g.phase) {
            Phase.WATCH -> watchUp(c, t, g, camp)
            Phase.SAY -> say(c, t, g)
            Phase.PACK -> pack(level, t, g)
            Phase.FETCH -> fetch(level, t, g, camp)
            Phase.WALK -> walk(c, t, g, camp)
        }
    }

    private fun next(g: Going, phase: Phase, now: Long) {
        g.phase = phase
        g.since = now
        g.stage = null
    }

    /** The bodies of the going: the watch (once made), the parent, the kin, in the order they walk. */
    private fun walkers(level: ServerLevel, t: Tale): List<Pair<String, Townsperson>> =
        (Cart.WATCH_SLOTS + Cart.familySlots(t)).mapNotNull { slot -> figure(level, t, slot)?.let { slot to it } }

    /**
     * B: two of the watch are made down the road toward the town, out of sight, and walk up to the camp's road side; there they salute, and
     * the parent speaks. Not there in [WATCH_FOR] ticks, the parent speaks without them.
     */
    private fun watchUp(c: Ctx, t: Tale, g: Going, camp: Camp) {
        val level = c.level
        val now = level.gameTime
        val face = yawOf(camp.r.scale(-1.0))
        var there = 0
        for ((i, slot) in Cart.WATCH_SLOTS.withIndex()) {
            val body = figure(level, t, slot)
            val mark = BlockPos.containing(camp.at(3.4, if (i == 0) 1.2 else -1.2))
            if (body == null) {
                if (slot in g.sent) continue
                val from = watchFrom(c, t, camp) ?: continue
                TaleBodies.figure(level, t, slot, from, TownRole.GUARD, -1, null, face)
                continue
            }
            if (slot !in g.sent) {
                g.sent += slot
                TaleBodies.figure(level, t, slot, mark, TownRole.GUARD, -1, null, face)
            }
            if (flat(body.position(), Vec3.atBottomCenterOf(mark)) < 2.0) there++
        }
        if (there >= Cart.WATCH_SLOTS.size || now - g.since > WATCH_FOR) {
            for (slot in Cart.WATCH_SLOTS) figure(level, t, slot)?.let {
                it.lookAt(camp.wheel)
                it.act(Activity.SALUTE)
            }
            next(g, Phase.SAY, now)
        }
    }

    /** A place on the road toward the town [WATCH_FROM] to [WATCH_TO] blocks from the camp that no player sees, for the watch to come from. */
    private fun watchFrom(c: Ctx, t: Tale, camp: Camp): BlockPos? {
        val level = c.level
        val way = WayOut.homeward(level, c.here, Cart.road(t))
        var walked = 0.0
        var last = Cart.road(t)
        var fallback: BlockPos? = null
        for (p in way) {
            walked += flat(Vec3.atCenterOf(last), Vec3.atCenterOf(p))
            last = p
            if (walked < WATCH_FROM) continue
            if (walked > WATCH_TO) break
            if (fallback == null) fallback = p
            if (Comings.unseen(level, p)) return p
        }
        return fallback
    }

    /** The parent turns to the player, points to the town and sends them on ahead (`tale.cart.go_ahead`); four seconds later, the pack. */
    private fun say(c: Ctx, t: Tale, g: Going) {
        val level = c.level
        val now = level.gameTime
        val parent = figure(level, t, Cart.PARENT) ?: return next(g, Phase.PACK, now)
        if (!g.said) {
            g.said = true
            if (parent.attending == null && parent.director is TaleHold) {
                val front = Vec3.atCenterOf(frontOf(c, t)).add(0.0, 1.0, 0.0)
                parent.lookAt(front)
                parent.face(parent.yawTo(front))
                parent.act(Activity.POINT)
                Words.sayWith(parent, "tale.cart.go_ahead", why = "a family on the road")
            }
            // Up off the ground for the going: the clips of the camp are let go
            for ((slot, b) in walkers(level, t)) TaleBodies.figure(level, t, slot, b.blockPosition(), TownRole.CITIZEN, -1, null, null)
        }
        if (now - g.since >= SAY_FOR) next(g, Phase.PACK, now)
    }

    /** The last bundle onto the cart ([CartScenes.pack]); with no cart, or not played in [PACK_FOR] ticks, the household is heaped on all the same. */
    private fun pack(level: ServerLevel, t: Tale, g: Going) {
        val now = level.gameTime
        val parent = figure(level, t, Cart.PARENT) ?: return next(g, Phase.WALK, now)
        val st = g.stage
        if (st == null) {
            if (Handcarts.parked(parent) == null) return next(g, Phase.FETCH, now)
            if (parent.attending != null && now - g.since < PACK_FOR) return
            val stage = CartScenes.pack(parent) { Handcarts.heap(parent, load(t, packed = true)) }
            if (stage == null || !stage.start(level)) {
                Handcarts.heap(parent, load(t, packed = true))
                return next(g, Phase.FETCH, now)
            }
            TaleLook.note(stage)
            g.stage = stage
            return
        }
        if (!st.finished && now - g.since < PACK_FOR) return
        if (!st.finished) st.cancel(level)
        Stage.give(parent, null)
        Handcarts.heap(parent, load(t, packed = true))
        next(g, Phase.FETCH, now)
    }

    /** The parent goes round to the shafts and takes them up; not done in [FETCH_FOR] ticks, they walk without the cart (it goes when no one looks). */
    private fun fetch(level: ServerLevel, t: Tale, g: Going, camp: Camp) {
        val now = level.gameTime
        val parent = figure(level, t, Cart.PARENT) ?: return next(g, Phase.WALK, now)
        if (Handcarts.pulling(parent)) {
            next(g, Phase.WALK, now)
            g.walkAt = now
            return
        }
        val grip = Handcarts.fetch(parent)
        if (grip == null || now - g.since > FETCH_FOR) {
            next(g, Phase.WALK, now)
            g.walkAt = now
            return
        }
        TaleBodies.figure(level, t, Cart.PARENT, grip, TownRole.CITIZEN, -1, null, camp.yaw)
        if (flat(parent.position(), Vec3.atBottomCenterOf(grip)) < 1.5) Handcarts.hitch(parent)
    }

    /**
     * The walk home: each of them in turn ([STAGGER] ticks apart, the watch first) walks the road toward the town, the parent with the shafts
     * in hand (PULL_CART), the young with the blanket (CARRY); a walk broken off by a word is taken up again from where they stand, and each
     * figure's mark follows its body so that one whose walk is done holds where it is. Once nobody sees any of them, or nobody is near, or it has
     * gone on long enough, the record brings them home ([handOff]).
     */
    private fun walk(c: Ctx, t: Tale, g: Going, camp: Camp) {
        val level = c.level
        val now = level.gameTime
        if (g.walkAt < 0) g.walkAt = now
        val front = frontOf(c, t)
        val way = g.way ?: shortOf(WayOut.homeward(level, c.here, BlockPos.containing(camp.centre)), front).also { g.way = it }
        if (way.isEmpty()) return handOff(c, t)
        val bodies = walkers(level, t)
        for ((k, pair) in bodies.withIndex()) {
            val (slot, b) = pair
            if (b.director !is RoadIn && b.attending == null && slot !in g.done && now >= g.walkAt + k * STAGGER) {
                val rest = if (slot in g.started) from(way, b.blockPosition()) else way
                if (rest.isEmpty()) {
                    g.done += slot
                } else {
                    val overlay = when {
                        slot == Cart.PARENT && Handcarts.pulling(b) -> Activity.PULL_CART
                        !b.carried.isEmpty -> Activity.CARRY
                        else -> Activity.NONE
                    }
                    b.navigation.stop()
                    b.director = RoadIn(rest, WALK_MOST, overlay) { g.done += slot }
                    g.started += slot
                }
            }
            if (slot in g.started) TaleBodies.figure(level, t, slot, b.blockPosition(), TownRole.CITIZEN, -1, null, null)
        }
        val walked = now - g.walkAt
        if (walked >= HIDDEN_AFTER && bodies.all { hidden(level, it.second) }) return handOff(c, t)
        if (walked >= LONELY_AFTER && bodies.none { level.getNearestPlayer(it.second, LONELY) != null }) return handOff(c, t)
        if (walked >= WALK_MOST) return handOff(c, t)
    }

    /** [way] without its last points within [SHORT_OF_GATE] blocks of the gate's [front] (always at least its first). */
    private fun shortOf(way: List<BlockPos>, front: BlockPos): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        for (p in way) {
            if (out.isNotEmpty() && flat(Vec3.atCenterOf(p), Vec3.atCenterOf(front)) < SHORT_OF_GATE) break
            out += p
        }
        return out
    }

    /** The rest of [way] from the point nearest [at]. */
    private fun from(way: List<BlockPos>, at: BlockPos): List<BlockPos> {
        val i = way.indices.minByOrNull { way[it].distSqr(at) } ?: return listOf()
        return way.subList(minOf(i + 1, way.size), way.size)
    }

    /** Whether no player sees [b]: none within [CLOSE] blocks, nor within [SIGHT] looking its way with a clear line to it. */
    private fun hidden(level: ServerLevel, b: Townsperson): Boolean {
        for (p in level.players()) {
            if (!p.isAlive || p.isSpectator) continue
            val d = p.distanceTo(b).toDouble()
            if (d < CLOSE) return false
            if (d > SIGHT) continue
            val to = b.eyePosition.subtract(p.eyePosition).normalize()
            if (p.lookAngle.dot(to) < LOOKING) continue
            val hit = level.clip(ClipContext(p.eyePosition, b.eyePosition, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p))
            if (hit.type == HitResult.Type.MISS) return false
        }
        return true
    }

    /**
     * The record brings the family home (the comings' own return, through the gate they left by): the camp's figures and the watch are gone,
     * the tale marks them in the comings' hands, and it ends at their door (`Comings.Listener.returned`). With no house for them they go on
     * down the road (W); one no longer away is home already.
     */
    fun handOff(c: Ctx, t: Tale) {
        if (!t.open || Cart.flag(t, Cart.HOME)) return
        val level = c.level
        goings.remove(t.id)
        wheels.remove(t.id)
        for (slot in Cart.WATCH_SLOTS + Cart.familySlots(t)) {
            val b = figure(level, t, slot)
            TaleBodies.release(level, t, slot)
            b?.discard()
        }
        val away = Cart.moved(level, t)
        Cart.set(t, Cart.HOME)
        c.why(t, "cart.going", Cart.family(t))
        c.data.changed()
        if (away != null && c.bringHome(t, away, frontOf(c, t))) return
        if (!t.open) return
        if (Cart.moved(level, t) == null) return c.end(t, Cart.route(t))
        Cart.clear(t, Cart.HOME)
        c.why(t, "cart.nohouse", Cart.family(t))
        c.end(t, Ending.W)
    }

    // ---- The end ----

    /**
     * The tale is ending. W: the family goes on down the road away from the town, the parent saying why, the cart taken along if it was
     * mended (left by the road if not). A, B and C: whatever is left of the camp goes, and the homecoming waits for the parent at the door.
     */
    fun ending(c: Ctx, t: Tale, ending: String) {
        val level = c.level
        goings.remove(t.id)
        wheels.remove(t.id)
        placed.remove(key(t, Cart.GUARD))
        if (ending == Ending.W) depart(c, t) else {
            for (slot in Cart.WATCH_SLOTS + Cart.familySlots(t)) TaleBodies.release(level, t, slot)
            homecomings[t.id] = Homecoming(ending, level.gameTime)
        }
        forget(t.id)
    }

    /** W: the family's figures walk on down the road away from the town and are gone out of sight (the parent with the cart if it rolls). */
    private fun depart(c: Ctx, t: Tale) {
        val level = c.level
        val bodies = Cart.familySlots(t).mapNotNull { slot -> figure(level, t, slot)?.let { slot to it } }
        if (bodies.isEmpty()) return
        bodies.firstOrNull { it.first == Cart.PARENT }?.second?.let { parent ->
            val detail = TaleLines.facts("talk.detail.tale.cart.moved", TaleLines.lit(Cart.family(t)), WayOut.sideKey(Cart.side(t)))
            parent.act(Activity.SHRUG)
            Words.sayWith(parent, "tale.cart.moved", detail = detail, why = "a family on the road")
        }
        val out = WayOut.outward(level, c.here, Cart.road(t), OUTWARD)
        for ((slot, b) in bodies) {
            val parent = slot == Cart.PARENT
            if (parent && !Cart.flag(t, Cart.MENDED)) Handcarts.leave(b)
            TaleBodies.release(level, t, slot)
            if (b.isRemoved || out.isEmpty()) continue
            var way = out
            if (parent && Cart.flag(t, Cart.MENDED) && Handcarts.hitch(b) != null && !Handcarts.pulling(b)) Handcarts.fetch(b)?.let { way = listOf(it) + out }
            b.navigation.stop()
            b.director = TaleLeave(way) { it.discard() }
        }
    }

    /**
     * Once a second, the homecomings of the tales that ended with the family home: once the parent's own body is about, the comings done with
     * it, and a player within [HOME_SEEN] blocks, the homecoming is queued on the capital's stage; given up after [HOME_FOR] ticks.
     */
    private fun homecomings(level: ServerLevel) {
        val now = level.gameTime
        val data = TaleData.get(level)
        for ((id, h) in homecomings.toList()) {
            val t = data[id]
            if (t == null || now - h.since > HOME_FOR || !Tales.scenes()) {
                homecomings.remove(id)
                continue
            }
            if (h.stage?.finished == false) continue
            val parent = Residents.get(level)[Cart.parentId(t)]?.takeIf { it.alive && it.left < 0 } ?: run {
                homecomings.remove(id)
                null
            } ?: continue
            if (ComingsBodies.busy(parent.id)) continue
            val body = Comings.body(level, parent) ?: continue
            if (body.director != null || body.attending != null) continue
            val player = level.getNearestPlayer(body, HOME_SEEN) as? ServerPlayer ?: continue
            val loaf = h.ending == Ending.A
            val neighbour = if (loaf) TaleBodies.body(level, t, Cart.NEIGHBOUR)?.takeIf { it.distanceToSqr(body) < 32.0 * 32.0 } else null
            val stage = CartScenes.homecoming(body, neighbour, player, loaf) ?: continue
            stage.then = { played -> if (played) homecomings.remove(id) else h.stage = null }
            if (TaleLook.queue(level, t.capital, stage, urgent = true, hears = true)) h.stage = stage
        }
    }

    // ---- Helpers ----

    /** The tale's own figure in [slot], if about. */
    private fun figure(level: ServerLevel, t: Tale, slot: String): Townsperson? = TaleBodies.body(level, t, slot)?.takeIf { it.tale == t.id && it.resident < 0 }

    private fun near(level: ServerLevel, at: Vec3, r: Double): Boolean = level.getNearestPlayer(at.x, at.y, at.z, r, false) != null

    private fun flat(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    /** The yaw that faces the way of [d] (a body's or a cart's: south is 0). */
    private fun yawOf(d: Vec3): Float = Math.toDegrees(atan2(-d.x, d.z)).toFloat()

    private fun key(t: Tale, slot: String): String = "${t.id}:$slot"

    // ---- For `tale cart` ----

    /** What this run keeps of [t], in words: the camp's layout, the Wheel, the going, the homecoming. */
    fun describe(t: Tale): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val camp = camps[t.id]
        out += "The camp" to (camp?.let { c ->
            val at = BlockPos.containing(c.centre)
            "${at.x} ${at.y} ${at.z}, " + (c.fire?.let { "by the wardfire at ${it.x} ${it.y} ${it.z}" } ?: "off the road (no wardfire within ${Cart.CLEARING} blocks)")
        } ?: "not laid out yet (once a player is near and the land round the place is loaded)")
        wheels[t.id]?.let { w -> out += "The Wheel" to (if (w.stage == null) "waiting for the parent to be free" else if (w.fixed) "the wheel is on" else "playing") }
        goings[t.id]?.let { g -> out += "The going" to "${g.phase.name.lowercase()}, ${(g.since - g.at) / 20} s in" + if (g.walkAt >= 0) ", walking" else "" }
        homecomings[t.id]?.let { h -> out += "The homecoming" to "${h.ending}: waiting for the parent at the door" + if (h.stage != null) " (queued)" else "" }
        return out
    }

    // ---- Forgetting ----

    /** A tale is over: what this kept of it is let go (its homecoming is kept until it is played). */
    fun forget(tale: Int) {
        camps.remove(tale)
        fronts.remove(tale)
        wheels.remove(tale)
        goings.remove(tale)
        placed.removeIf { it.startsWith("$tale:") }
        CartScenes.forget(tale)
    }

    /** The server stopped: all of it forgotten. */
    fun forgetAll() {
        camps.clear()
        fronts.clear()
        snapped.clear()
        placed.clear()
        wheels.clear()
        goings.clear()
        homecomings.clear()
        CartScenes.forgetAll()
    }
}
