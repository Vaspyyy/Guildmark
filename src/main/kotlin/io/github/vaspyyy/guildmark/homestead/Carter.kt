package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Heap
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Pull
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Walk
import io.github.vaspyyy.guildmark.townsfolk.Work
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import kotlin.math.atan2
import kotlin.math.ceil

/**
 * The carter who comes for a homestead's shipping crates of a morning while someone is near to see it (see
 * [HomesteadShipping]): up the road with their handcart, which they stand by the first crate; that crate opened and
 * the morning's sale made as they look in (what the market will take is in their hands from then on); the cart loaded
 * a sack, a crate or a basket at a time from whichever crate it came out of; and off down the road with it, gone once
 * nobody is near enough to see. With no cart to be had (carts switched off, no room for one) they come on foot and go
 * with a sack on their shoulder.
 *
 * Never saved: one found in a chunk as it loads goes at once ([onJoin]), and if they go before opening the crates the
 * clock makes the morning's sale in their place. Whatever happens, they're gone within [TIMEOUT] ticks, the sale made.
 */
class Carter private constructor(
    val p: Townsperson,
    private val id: Int,
    /** Where they came up the road from, and go back to. */
    private val road: BlockPos,
    /** The homestead's shipping crates when they set out, nearest the gate first. */
    private val crates: List<BlockPos>,
    /** Sent by the test command: the sale is made whether or not this morning's has been. */
    private val forced: Boolean,
) : Director {
    private enum class Stage { ARRIVE, OPEN, LOAD, DEPART }

    /** One piece of the load: [stack] (how it looks in their arms and on the cart) out of the crate at [crate]. */
    private class Piece(val crate: BlockPos, val stack: ItemStack)

    private var stage = Stage.ARRIVE
    private var task: Task? = null
    private var age = 0
    private var greeted = false

    /** Whether the morning's sale has been made, by them or by the clock in their place. */
    var sold = false
        private set
    private var sale: HomesteadShipping.Sale? = null

    /** What's heaped on the cart, how much of it they've heard land, and the pieces still to fetch. */
    private val heap = ArrayList<ItemStack>()
    private var landed = 0
    private val pieces = ArrayDeque<Piece>()

    // Where they were last tick, to hear the wheels only while the cart's rolling
    private var lastX = Double.NaN
    private var lastZ = 0.0

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        age++
        val h = Homesteads.get(level).byId(id)
        if (h == null || !HomesteadShipping.enabled()) return vanish()
        if (age > TIMEOUT) {
            sell(level, h)
            return vanish()
        }
        sounds(level)
        if (!greeted && stage == Stage.ARRIVE && age % 20 == 0) greeted = p.say("carter_arrive_${p.random.nextInt(2)}", force = true)
        // On their way: gone once nobody is near enough to see them go
        if (stage == Stage.DEPART && age % 10 == 0 && level.getNearestPlayer(p, GONE) == null) return vanish()
        val current = task
        if (current != null) {
            if (current.tick()) return
            current.stop()
            val then = current.then
            task = then
            if (then != null) {
                then.start()
                return
            }
        }
        next(level, h)
    }

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        val level = p.level() as? ServerLevel ?: return false
        p.lookAt(player)
        p.attend(player, 60)
        p.say("carter_hello_0", force = true)
        val place = Homesteads.get(level).byId(id)?.let { HomesteadShipping.target(level, it) }?.place ?: ""
        player.sendOverlayMessage(Component.translatable("message.guildmark.homestead.carter", HomesteadShipping.placeName(place)))
        return true
    }

    private fun run(next: Task) {
        task = next
        next.start()
    }

    /** What they're doing is done: on to the next part of the visit. */
    private fun next(level: ServerLevel, h: Homestead) {
        when (stage) {
            Stage.ARRIVE -> open(level, h)
            Stage.OPEN -> load(level, h)
            Stage.LOAD -> if (pieces.isEmpty()) depart(level) else fetch(level)
            Stage.DEPART -> vanish()
        }
    }

    /** Up the road: the cart pulled to where it'll stand by the first crate, out of the way of it, or on foot with none. */
    private fun begin(level: ServerLevel, gate: Vec3) {
        val first = crates.first()
        val stop = if (Handcarts.enabled()) Handcarts.bay(level, first, toward(first, gate), p) else null
        run(if (stop != null) Pull(p, stop, emptyList()) else Walk(p, first, Trades.LADEN, near = 2.5))
    }

    /** At the first crate: they look in, and the morning's sale is made. */
    private fun open(level: ServerLevel, h: Homestead) {
        stage = Stage.OPEN
        val crate = crates.first()
        val spot = beside(level, crate, Handcarts.bed(p) ?: p.position())
        if (spot == null) {
            sell(level, h)
            return load(level, h)
        }
        run(Work(p, spot, Jobs.top(crate, 0.9), listOf(Step(Activity.TAKE), Step(Activity.NONE, 8)), crate) { s, t ->
            if (s.activity == Activity.TAKE) {
                if (t == 1) level.playSound(null, crate, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.6f, 1f)
                if (t == LOOK_IN) sell(level, h)
                if (t == Activity.TAKE.ticks - 4) level.playSound(null, crate, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.5f, 1f)
            }
        })
    }

    /** The sale made (if it wasn't as they opened the crate): the load worked out, or with nothing to take, a word and away. */
    private fun load(level: ServerLevel, h: Homestead) {
        sell(level, h)
        stage = Stage.LOAD
        val made = sale
        if (made == null || made.shipment.nothing) {
            p.say(if (made == null || made.shipment.left.all { it <= 0 }) "carter_empty_0" else "carter_full_0", force = true)
            p.act(Activity.SHRUG)
            return depart(level)
        }
        // A piece for each good from each crate it came out of, then more of the bigger ones, while the cart has room
        val parts = ArrayList<Triple<BlockPos, Good, Int>>()
        for ((crate, out) in made.taken) for (good in Good.entries) if (out[good.ordinal] > 0) parts.add(Triple(crate, good, out[good.ordinal]))
        val most = parts.map { ceil(it.third / PIECE_UNITS).toInt().coerceAtLeast(1) }
        var round = 0
        while (pieces.size < PIECES) {
            var any = false
            for ((i, part) in parts.withIndex()) {
                if (round >= most[i] || pieces.size >= PIECES) continue
                pieces.add(Piece(part.first, Handcarts.stack(part.second)))
                any = true
            }
            if (!any) break
            round++
        }
        // No cart by the crates: one piece on the shoulder, and off with it
        if (Handcarts.parked(p) == null) {
            p.carried = pieces.first().stack.copy()
            pieces.clear()
            return depart(level)
        }
        fetch(level)
    }

    /** The next piece: out of its crate, over to the cart, and onto the heap. */
    private fun fetch(level: ServerLevel) {
        val piece = pieces.removeFirst()
        val onto = Heap(p, heap, piece.stack, from = Vec3.atCenterOf(piece.crate), overlay = Activity.CARRY_SACK)
        val spot = beside(level, piece.crate, Handcarts.bed(p) ?: p.position()) ?: return run(onto)
        val take = Work(p, spot, Jobs.top(piece.crate, 0.9), listOf(Step(Activity.PICK_UP), Step(Activity.NONE, 6)), piece.crate) { s, t ->
            if (s.activity == Activity.PICK_UP) {
                if (t == 1) level.playSound(null, piece.crate, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5f, 1.05f)
                if (t == GRAB) {
                    p.carried = piece.stack.copy()
                    level.playSound(null, piece.crate, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.45f, 1.05f)
                }
            }
        }
        run(take.andThen(onto))
    }

    /** Loaded (or nothing to load): a word, and off down the road, the cart taken up again if there is one. */
    private fun depart(level: ServerLevel) {
        stage = Stage.DEPART
        val shipment = sale?.shipment
        if (shipment != null && !shipment.nothing) {
            p.say(if (shipment.left.any { it > 0 }) "carter_full_0" else "carter_leave_${level.random.nextInt(2)}", force = true)
        }
        val carrying = !p.carried.isEmpty
        run(if (Handcarts.parked(p) != null) Pull(p, road, heap) else Walk(p, road, Trades.LADEN, near = 2.0, overlay = if (carrying) Activity.CARRY_SACK else Activity.NONE))
    }

    /** The morning's sale, once: made as they look in, or by the clock if they never got that far. */
    private fun sell(level: ServerLevel, h: Homestead) {
        if (sold) return
        sold = true
        sale = HomesteadShipping.opened(level, h, forced)
    }

    /** The cart's wheels while it rolls, and each piece landing on its bed (the first with a word). */
    private fun sounds(level: ServerLevel) {
        val moved = !lastX.isNaN() && (p.x - lastX) * (p.x - lastX) + (p.z - lastZ) * (p.z - lastZ) > 0.0016
        lastX = p.x
        lastZ = p.z
        if (moved && age % WHEELS == 0 && Handcarts.pulling(p)) {
            level.playSound(null, p.x, p.y, p.z, HomesteadRegistry.CARTER_WHEELS.get(), SoundSource.NEUTRAL, 0.55f, 0.9f + p.random.nextFloat() * 0.2f)
        }
        if (heap.size > landed) {
            landed = heap.size
            val bed = Handcarts.bed(p) ?: p.position()
            level.playSound(null, bed.x, bed.y, bed.z, HomesteadRegistry.CARTER_LOAD.get(), SoundSource.NEUTRAL, 0.7f, 0.9f + p.random.nextFloat() * 0.2f)
            if (landed == 1) p.say("carter_load_${p.random.nextInt(2)}", force = true)
        }
    }

    /** Gone: their cart goes with them. */
    private fun vanish() {
        if (visits[id] === this) visits.remove(id)
        task?.stop()
        task = null
        p.director = null
        p.discard()
    }

    companion object {
        /** Every carter wears this tag, so one saved with its chunk is known for what it is. */
        const val TAG = "guildmark.carter"

        /** The longest a visit may take, in ticks. */
        private const val TIMEOUT = 6000

        /** With no player this near, a carter on their way off is gone. */
        private const val GONE = 40.0

        /** The most pieces on a cart, and how many of a good make a piece of it. */
        private const val PIECES = 8
        private const val PIECE_UNITS = 32.0

        /** Ticks between the wheels' sounds; the tick of looking in a crate the sale's made, and of taking a piece out. */
        private const val WHEELS = 16
        private const val LOOK_IN = 10
        private const val GRAB = 10

        /** The looks that suit the work (no basket on the arm, no stoop), as the builders'. */
        private val LOOKS = intArrayOf(0, 1, 2, 4, 6, 7)

        /** How far out along the road from the gate they come from, nearest last. */
        private val ROAD = intArrayOf(40, 32, 24, 16)

        private val visits = HashMap<Int, Carter>()

        /** Whether a carter is on the way to homestead [id]'s crates and hasn't made the morning's sale yet. */
        fun coming(id: Int): Boolean {
            val carter = visits[id] ?: return false
            if (!carter.p.isAlive || carter.p.isRemoved) {
                visits.remove(id)
                return false
            }
            return !carter.sold
        }

        /** The carter at homestead [id] now, if there is one. */
        fun at(id: Int): Townsperson? = visits[id]?.p?.takeIf { it.isAlive && !it.isRemoved }

        /**
         * Sends a carter for [h]'s crates (those loaded now). False if there's no crate to come to, no road to come up
         * or one is there already; the sale is then the caller's to make.
         */
        fun send(level: ServerLevel, h: Homestead, book: Book, forced: Boolean): Boolean {
            if (at(h.id) != null) return false
            val crates = HomesteadShipping.crates(level, h, book, load = false).map { it.blockPos }
            if (crates.isEmpty()) return false
            val road = road(level, h) ?: return false
            val look = LOOKS[level.random.nextInt(LOOKS.size)]
            val p = Townsfolk.create(level, road, TownRole.CITIZEN, look, h.frame.direction(Dir.S).toYRot(), EntitySpawnReason.EVENT) ?: return false
            p.setPermanentlyInvulnerable(true)
            // Nothing can hurt them, so they don't drop the shafts and run from a passing monster (Townsperson.FleeGoal)
            p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
            p.addTag(TAG)
            p.setDropChance(EquipmentSlot.MAINHAND, 0f)
            p.setDropChance(EquipmentSlot.OFFHAND, 0f)
            val carter = Carter(p, h.id, road, crates, forced)
            p.director = carter
            if (!level.addFreshEntity(p)) return false
            visits[h.id] = carter
            carter.begin(level, h.frame.centre(0.0, h.post.y.toDouble(), 0.0))
            return true
        }

        /** Sends homestead [id]'s carter away at once (the charter given up). */
        fun dismiss(id: Int) {
            visits[id]?.vanish()
        }

        /** Every carter away at once (shipping switched off). */
        fun dismissAll() {
            if (visits.isEmpty()) return
            for (carter in visits.values.toList()) carter.vanish()
        }

        /** The server has stopped: the carters went with its world. */
        fun reset() {
            visits.clear()
        }

        /** A carter from a chunk saved with them in it: gone at once (the clock makes the sale they didn't). */
        fun onJoin(event: EntityJoinLevelEvent) {
            val entity = event.entity
            if (event.loadedFromDisk() && entity is Townsperson && TAG in entity.entityTags()) event.isCanceled = true
        }

        /** Where a carter comes up the road from: some way out from the gate, somewhere to stand. */
        private fun road(level: ServerLevel, h: Homestead): BlockPos? {
            val frame = h.frame
            for (out in ROAD) {
                val x = frame.worldX(0, -out)
                val z = frame.worldZ(0, -out)
                if (!level.hasChunkAt(x, z)) continue
                val pos = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z)
                if (Places.standable(level, pos)) return pos
            }
            return null
        }

        /** Where to stand at [crate]'s side to get at it: beside it on the side nearest [from], facing it. */
        private fun beside(level: ServerLevel, crate: BlockPos, from: Vec3): Spot? {
            var best: Spot? = null
            var bestDist = Double.MAX_VALUE
            for (side in Direction.Plane.HORIZONTAL) {
                for (dy in DY) {
                    val at = crate.relative(side).above(dy)
                    if (!Places.standable(level, at)) continue
                    val dist = Vec3.atBottomCenterOf(at).distanceToSqr(from)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = Spot(at, side.opposite)
                    }
                    break
                }
            }
            return best
        }

        /** The way from [from] toward [to], as near as the four sides go. */
        private fun toward(from: BlockPos, to: Vec3): Direction =
            Direction.fromYRot(atan2(to.z - (from.z + 0.5), to.x - (from.x + 0.5)) * (180.0 / Math.PI) - 90.0)

        private val DY = intArrayOf(0, -1, 1)
    }
}
