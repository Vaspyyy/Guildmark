package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.Bodies
import io.github.vaspyyy.guildmark.caravan.DraughtHorse
import io.github.vaspyyy.guildmark.caravan.Ground
import io.github.vaspyyy.guildmark.caravan.Path
import io.github.vaspyyy.guildmark.caravan.Show
import io.github.vaspyyy.guildmark.caravan.Train
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.network.WagonSyncPayload
import io.github.vaspyyy.guildmark.network.WagonUsePayload
import io.github.vaspyyy.guildmark.trade.Carrier
import io.github.vaspyyy.guildmark.trade.Carriers
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import io.github.vaspyyy.guildmark.trade.scenes.ReliefCheer
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.ContainerUser
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Carrier's Wagon in the world (living-world/trader/design.md, Part 3; build part D3): its bodies made from its record ([TradeWagons]) while
 * someone is near and let go when nobody has been for a while, and the wagon led behind its owner along the way they walked. The bodies are
 * the caravans' own wagon and draught horse (props, never saved); [Bodies.owns] asks [owns] too, so the caravans' cleanup never takes them for
 * strays, and there is only ever one pair a wagon. Every decision is made here, on the server.
 *
 * - **Led.** A hand on the horse's head (the client aims it: [used]) takes the lead; again lets go. The way the owner walks is kept as a
 *   trail of points half a block apart ([Trail], 128 of them, in memory only): a way that turns no tighter than a wagon can (its loops 2.5
 *   blocks round, the caravans' own turning bulbs being 1.75 to 3), so it follows a corner as it was walked, and a loop walked is cut out.
 *   The horse walks it at the owner's pace, [GAP] behind them along it (the wagon eight to ten blocks back), and the wagon is placed along it
 *   as the caravans' are ([Train] over the [Path]): the horse on it, the front axle and the back axle each their length behind, straight.
 *   Footsteps beside the wagon (going round to the tailboard) are not the way; nor does it move while its owner stands at its horse's nose.
 * - **Never a trap.** Each point is looked at as it is laid: a step of more than a block, a wall at the wagon's height or its sides, a roof, deep
 *   water, treetops or ground nobody can see are a place it cannot go, and the horse stops short of the first one and waits ("It waits;
 *   come back to the horse to lead it round", once). Coming back to the horse forgets the way ahead. More than [TradeConfig.wagonRein]
 *   blocks off (20), the rein goes slack, the horse slows to a stop, the brake goes on and "The wagon is waiting.": it is parked. Its
 *   owner offline, in another dimension, gone in a blink (a teleport) or dead: parked where it stood. Whatever it is, its record says where it
 *   is, its goods are in the record, and the Wagon Bill always calls it home.
 * - **Its hold.** The tailboard opens its 54 slots (a six-row chest; [WagonHold] over the record's own stacks, so nothing is ever in two
 *   places): the board falls open on its chains while it is open and shuts after. Only its owner (or an operator in creative) opens it, and it
 *   opens with carrying switched off too, so nothing is ever locked in. The load is heaped on the bed from what it really holds.
 *
 * The rollout at the yard is [WagonBill]'s scene, played on these bodies ([Scene]).
 */
internal object LedShow {
    /** The parts of a wagon a hand can be put on ([WagonUsePayload]). */
    const val HEAD = 0
    const val TAIL = 1

    /** Bodies are made for a wagon within [NEAR] of a player (its chunk loaded), and let go of with nobody within [FAR] for [LINGER] ticks; looked at every [SCAN]. */
    private const val NEAR = 96.0
    private const val FAR = 128.0
    private const val LINGER = 200
    private const val SCAN = 20

    /** Its owner further off than this: the wagon is parked where it stands. A move further than [JUMP] in a tick is no walk: the lead drops. */
    private const val OFF_LEAD = 160.0
    private const val JUMP = 8.0

    /** The trail: [RING] points [STEP] apart; the way is laid until it is within [LAG] of the owner. */
    const val RING = 128
    private const val STEP = 0.5
    private const val LAG = 0.75

    /** The tightest the way may turn: radians a step, its loops 2.5 blocks round. */
    private const val TURN = STEP / 2.5

    /** How far along the way the owner keeps ahead of the horse's middle; how fast the horse goes, gathers pace and slows (blocks a tick), and how hard it closes up. */
    private const val GAP = 4.5
    private const val TOP = 0.23
    private const val ACCEL = 0.009
    private const val DECEL = 0.022
    private const val PULL = 0.15

    /** Blocks from the horse's middle to its head; nobody stands closer than [NOSE] to it unless it waits; within [BESIDE] of the wagon's line its owner is beside it. */
    private const val HEAD_AHEAD = 1.4
    private const val NOSE = 2.0
    private const val BESIDE = 2.6

    /** The horse's middle stops this far short of a place the wagon cannot go; a loop walked is cut where the way comes back within [LOOP] of itself. */
    private const val CLEAR = 1.7
    private const val LOOP = 1.2

    /** Ticks a led wagon stands before its brake goes on. */
    private const val BRAKE_AFTER = 60

    /** How near a hand must be to the head or the tailboard, and how near the tailboard whoever has the hold open must stay. */
    private const val REACH = 4.5
    private const val HOLD_REACH = 8.0

    /** Ticks between two syncs of what a wagon shows, and how far off they are sent (its bodies' tracking range). */
    private const val SYNC_EVERY = 100L
    private const val SEEN = 160.0

    /** What a point of the way may be: a step up or down, how far out its sides go, and where its height is looked at for walls and roofs. */
    private const val STEP_MOST = 1.05
    private const val SIDE = 0.85

    /** The lantern lit from dusk to dawn (the caravans' hours). */
    private const val DUSK = 12500L
    private const val DAWN = 23500L

    /** One wagon's bodies while they are out. */
    internal class Shown(val owner: UUID, val level: ServerLevel, val wagon: Wagon, val horse: DraughtHorse?) {
        val trail = Trail()

        /** How far along the trail the horse's middle is, and the back axle; blocks a tick it goes. */
        var s = 0.0
        var rearS = 0.0
        var v = 0.0

        /** Who has its lead (its owner), or nobody: it stands. */
        var holder: UUID? = null

        /** The owner's place last tick, and the way they have walked that is not laid yet. */
        var lastX = 0.0
        var lastZ = 0.0
        var budget = 0.0

        /** Pulling up at the rein's end; held up by a place it cannot go (and told so); ticks it has stood while led. */
        var halting = false
        var heldUp = false
        var toldHeld = false
        var still = 0
        var taut = false
        var lastHorseY = Double.NaN

        /** The game time its pennant ran up (-1 furled, as in the yard; 0 long since). */
        var raised = 0L

        /** How many have its hold open, and whether its tailboard is down. */
        var openers = 0
        var tailDown = false

        var unseen = 0
        var heap = true
        var laden = 0f
        var synced = Long.MIN_VALUE / 2
        var sendNow = true

        /** A scene being played on it (the rollout), which has charge of it till it ends. */
        var scene: Scene? = null

        val harnessed: Boolean get() = horse != null && !horse.isRemoved && wagon.horseId == horse.id
        val led: Boolean get() = holder != null
    }

    /** A short scene played on a wagon's bodies (the rollout at the yard), ticked after everyone has moved: false once it is done. */
    internal interface Scene {
        fun tick(sh: Shown): Boolean

        /** Puts everything right at once (the bodies are going, the server stopping, something went wrong). */
        fun stop(sh: Shown)
    }

    private val shown = LinkedHashMap<UUID, Shown>()
    private val holds = HashMap<UUID, WagonHold>()
    private val lastUse = HashMap<UUID, Long>()

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToServer(WagonUsePayload.TYPE, WagonUsePayload.STREAM_CODEC, WagonUsePayload::handle)
                .playToClient(WagonSyncPayload.TYPE, WagonSyncPayload.STREAM_CODEC, WagonSyncPayload::handle)
        }
        val bus = NeoForge.EVENT_BUS
        bus.addListener(LevelTickEvent.Post::class.java) { event -> (event.level as? ServerLevel)?.let { tick(it) } }
        bus.addListener(ServerTickEvent.Post::class.java) { event -> if (event.server.tickCount % SCAN == 0) scan(event.server) }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        bus.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        bus.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        bus.addListener(LivingDeathEvent::class.java) { (it.entity as? ServerPlayer)?.let { p -> letGoOf(p) } }
        bus.addListener(ServerStoppedEvent::class.java) { _ ->
            for (sh in shown.values) sh.scene?.let { runCatching { it.stop(sh) } }
            shown.clear()
            holds.clear()
            lastUse.clear()
        }
        WagonBill.register()
        WagonCommand.init()
        Carriers.add(Carrier { player, radius -> holdings(player, radius) })
        Carry.reports.add(Carry.Report { player -> report(player) })
        Carry.recalls.add(Carry.Bring { player -> if (bringBeside(player, led = false)) 1 else 0 })
        Carry.unsticks.add(Carry.Bring { player -> if (bringBeside(player, led = true)) 1 else 0 })
        // The town's children run to the trader's wagon when a relief delivery is cheered
        ReliefCheer.addCarrying(ReliefCheer.Carrying { player, radius ->
            val sh = shown[player.uuid]
            if (sh == null || sh.wagon.isRemoved || sh.level !== player.level() || bedDistance(sh, player.position()) > radius) listOf() else listOf(sh.wagon)
        })
    }

    // ---- Who's whose ----

    /** Whether [e] is a body of a trader's wagon that is out now (for [Bodies.owns]: not a stray). */
    fun owns(e: net.minecraft.world.entity.Entity): Boolean {
        if (shown.isEmpty()) return false
        for (sh in shown.values) if (sh.wagon === e || sh.horse === e) return true
        return false
    }

    /** [owner]'s wagon's bodies, if they are out. */
    fun of(owner: UUID): Shown? = shown[owner]?.takeIf { !it.wagon.isRemoved }

    /** Every wagon's bodies out now, for the commands. */
    fun all(): Collection<Shown> = shown.values

    fun recOf(sh: Shown): WagonRec? = TradeWagons.peek(sh.level)?.get(sh.owner)

    // ---- Making and letting go of the bodies ----

    /** Every [SCAN] ticks: bodies made for each wagon someone has come near, and let go of where nobody has been for a while. */
    private fun scan(server: MinecraftServer) {
        val data = TradeWagons.peek(server.overworld())
        if (data == null) {
            if (shown.isNotEmpty()) for (sh in shown.values.toList()) drop(sh)
            return
        }
        val clock = server.overworld().overworldClockTime
        for (sh in shown.values.toList()) {
            val rec = data[sh.owner]
            val gone = rec == null || sh.wagon.isRemoved || (sh.horse != null && sh.horse.isRemoved) || rec.at == null ||
                rec.dimension != sh.level.dimension() || (rec.state == WagonRec.State.YARD && !rec.arrived(clock)) || (rec.horse != (sh.horse != null))
            if (gone) {
                drop(sh)
                continue
            }
            if (sh.led) {
                sh.unseen = 0
                continue
            }
            val at = sh.wagon.position()
            if (anyNear(sh.level, at, FAR)) sh.unseen = 0 else {
                sh.unseen += SCAN
                if (sh.unseen >= LINGER) drop(sh)
            }
        }
        for (rec in data.all()) {
            if (shown.containsKey(rec.owner)) continue
            val at = rec.at ?: continue
            if (rec.state == WagonRec.State.YARD && !rec.arrived(clock)) continue
            val level = server.getLevel(rec.dimension) ?: continue
            if (!level.isLoaded(BlockPos.containing(at)) || !anyNear(level, at, NEAR)) continue
            try {
                make(level, rec)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A trader's wagon could not be shown", e)
                shown.remove(rec.owner)?.let { discardBodies(it) }
            }
        }
    }

    private fun anyNear(level: ServerLevel, at: Vec3, range: Double): Boolean =
        level.players().any { !it.isSpectator && it.distanceToSqr(at) <= range * range }

    /**
     * [rec]'s bodies made in [level] where it stands: the wagon (its back axle at the record's place, heading its way), and the horse in its shafts,
     * or, in the yard, tethered beside it with its nosebag on. Null if they could not be made.
     */
    fun make(level: ServerLevel, rec: WagonRec): Shown? {
        shown.remove(rec.owner)?.let { drop(it) }
        val at = rec.at ?: return null
        val wagon = Bodies.WAGON.get().create(level, EntitySpawnReason.EVENT) ?: return null
        val horse = if (rec.horse) Bodies.DRAUGHT_HORSE.get().create(level, EntitySpawnReason.EVENT) else null
        horse?.coat(rec.coat)
        val sh = Shown(rec.owner, level, wagon, horse)
        val yard = rec.state == WagonRec.State.YARD
        // Known as ours before they join the level, so the caravans' cleanup never takes them for strays
        shown[rec.owner] = sh
        if (horse != null && !yard) {
            wagon.horse(horse)
            horse.wagon(wagon)
        }
        horse?.flag(DraughtHorse.FLAG_REINS_TIED, true)
        val (fx, fz) = forward(rec.yaw)
        val rear = Vec3(at.x, Ground.stand(level, at.x, at.z, at.y + 0.6), at.z)
        val k = Vec3(rear.x + fx * Train.WHEELBASE, 0.0, rear.z + fz * Train.WHEELBASE).let { Vec3(it.x, Ground.stand(level, it.x, it.z, rear.y + 0.6), it.z) }
        val h = Vec3(k.x + fx * Train.AHEAD, 0.0, k.z + fz * Train.AHEAD).let { Vec3(it.x, Ground.stand(level, it.x, it.z, k.y + 0.6), it.z) }
        seed(sh, rear, k, h)
        place(sh, snap = true)
        if (horse != null && yard) tether(sh, snap = true)
        wagon.flag(Wagon.FLAG_BRAKE, true)
        sh.raised = if (yard) -1L else 0L
        lantern(sh, rec)
        heap(sh, rec)
        if (!level.addFreshEntity(wagon)) {
            shown.remove(rec.owner)
            horse?.discard()
            return null
        }
        if (horse != null && !level.addFreshEntity(horse)) {
            shown.remove(rec.owner)
            wagon.discard()
            return null
        }
        if (rec.ownerName.isEmpty()) level.server.playerList.getPlayer(rec.owner)?.let { rec.ownerName = it.name.string }
        sh.sendNow = true
        return sh
    }

    /** Lets [sh]'s bodies go (nobody near, the wagon sent home, the record gone): a scene on them is put right, and the record keeps where it stood. */
    fun drop(sh: Shown) {
        sh.scene?.let { scene ->
            sh.scene = null
            try {
                scene.stop(sh)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A wagon's scene could not be put right", e)
            }
        }
        if (sh.led) {
            val rec = recOf(sh)
            if (rec != null && rec.state == WagonRec.State.LED) keep(sh, rec, WagonRec.State.PARKED)
            sh.holder = null
        }
        if (shown[sh.owner] === sh) shown.remove(sh.owner)
        tellGone(sh)
        discardBodies(sh)
    }

    private fun discardBodies(sh: Shown) {
        if (!sh.wagon.isRemoved) sh.wagon.discard()
        if (sh.horse != null && !sh.horse.isRemoved) sh.horse.discard()
    }

    /** [owner]'s wagon's bodies let go of now (it is sent home, or moved), with a puff of dust where they stood if [puff]. */
    fun dropOf(owner: UUID, puff: Boolean) {
        val sh = shown[owner] ?: return
        if (puff) dust(sh.level, sh.wagon.point(0f, -20f, -16f), 14)
        drop(sh)
    }

    fun dust(level: ServerLevel, at: Vec3, n: Int) {
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, n, 0.9, 0.5, 0.9, 0.01)
        level.sendParticles(ParticleTypes.POOF, at.x, at.y - 0.6, at.z, n / 2, 0.8, 0.2, 0.8, 0.02)
    }

    // ---- Each tick, after everyone has moved ----

    private fun tick(level: ServerLevel) {
        if (shown.isEmpty()) return
        for (sh in shown.values.toList()) {
            if (sh.level !== level) continue
            if (sh.wagon.isRemoved) continue
            try {
                step(sh)
            } catch (e: RuntimeException) {
                // Whatever went wrong, the wagon stands where it is and nobody is left holding it
                Guildmark.LOGGER.warn("A trader's wagon could not be placed", e)
                if (sh.led) recOf(sh)?.let { park(sh, it, said = false) }
                sh.scene?.let { scene ->
                    sh.scene = null
                    runCatching { scene.stop(sh) }
                }
            }
        }
    }

    private fun step(sh: Shown) {
        val rec = recOf(sh) ?: return
        val now = sh.level.gameTime
        val scene = sh.scene
        if (scene != null && !scene.tick(sh)) sh.scene = null
        if (sh.led) lead(sh, rec, now)
        if (now % 100L == (sh.wagon.id % 100).toLong()) lantern(sh, rec)
        if ((now + sh.wagon.id) % 40L == 0L && !sh.led && sh.scene == null && sh.v == 0.0) settle(sh, rec)
        tailboard(sh)
        if (sh.heap) heap(sh, rec)
        if (sh.sendNow || now - sh.synced >= SYNC_EVERY) send(sh, rec, now)
    }

    /** A wagon standing: settled onto the ground under it again now and then (the ground may have changed under its wheels). */
    private fun settle(sh: Shown, rec: WagonRec) {
        place(sh, snap = false)
        if (rec.state == WagonRec.State.YARD && sh.horse != null && !sh.harnessed) tether(sh, snap = false)
    }

    // ---- The trail ----

    /**
     * The way [sh] is driven along: the points owners walked, half a block apart, each with how far along it lies and whether the wagon can go
     * there; past either end it runs on straight the way that end heads. A ring of [RING] points; the oldest go as the back axle leaves them.
     */
    internal class Trail : Path {
        private val xs = DoubleArray(RING)
        private val ys = DoubleArray(RING)
        private val zs = DoubleArray(RING)
        private val al = DoubleArray(RING)
        private val bl = BooleanArray(RING)
        private var first = 0

        var size = 0
            private set

        /** Which way its end heads on the ground: a unit (x, z). */
        var hx = 0.0
        var hz = 1.0

        private fun idx(i: Int): Int = (first + i) % RING

        fun x(i: Int): Double = xs[idx(i)]
        fun y(i: Int): Double = ys[idx(i)]
        fun z(i: Int): Double = zs[idx(i)]
        fun along(i: Int): Double = al[idx(i)]
        fun blocked(i: Int): Boolean = bl[idx(i)]

        val full: Boolean get() = size == RING
        val endAlong: Double get() = if (size == 0) 0.0 else along(size - 1)

        fun clear() {
            first = 0
            size = 0
        }

        fun add(x: Double, y: Double, z: Double, blocked: Boolean) {
            if (full) dropOldest()
            val along = if (size == 0) 0.0 else endAlong + hypot(x - x(size - 1), z - z(size - 1))
            val k = idx(size)
            xs[k] = x
            ys[k] = y
            zs[k] = z
            al[k] = along
            bl[k] = blocked
            size++
        }

        fun dropOldest() {
            if (size == 0) return
            first = (first + 1) % RING
            size--
        }

        /** Keeps the first [n] points, forgetting the rest. */
        fun keep(n: Int) {
            size = n.coerceIn(0, size)
        }

        /** How far along the first point the wagon cannot go lies, past [from]; infinity if there is none. */
        fun firstBlocked(from: Double): Double {
            for (i in 0 until size) if (bl[idx(i)] && al[idx(i)] > from) return al[idx(i)]
            return Double.POSITIVE_INFINITY
        }

        override fun at(s: Double): Vec3 {
            if (size == 0) return Vec3.ZERO
            if (size == 1) return Vec3(x(0), y(0), z(0))
            if (s <= along(0)) return straight(0, 1, s - along(0), y(0))
            if (s >= endAlong) return straight(size - 2, size - 1, s - endAlong, y(size - 1))
            var lo = 0
            var hi = size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (along(mid) <= s) lo = mid else hi = mid - 1
            }
            val i = lo.coerceAtMost(size - 2)
            val span = along(i + 1) - along(i)
            val t = if (span < 1e-9) 0.0 else ((s - along(i)) / span).coerceIn(0.0, 1.0)
            return Vec3(x(i) + (x(i + 1) - x(i)) * t, y(i) + (y(i + 1) - y(i)) * t, z(i) + (z(i + 1) - z(i)) * t)
        }

        /** [past] blocks on from point [b] (back from [a] when below 0) the way [a] to [b] heads, at height [y]. */
        private fun straight(a: Int, b: Int, past: Double, y: Double): Vec3 {
            val dx = x(b) - x(a)
            val dz = z(b) - z(a)
            val len = hypot(dx, dz)
            val ux = if (len < 1e-9) hx else dx / len
            val uz = if (len < 1e-9) hz else dz / len
            val from = if (past < 0.0) a else b
            return Vec3(x(from) + ux * past, y, z(from) + uz * past)
        }
    }

    /** [sh]'s trail laid afresh along the wagon as it stands: from a block and a half behind its back axle, through its front axle, to its horse's middle. */
    private fun seed(sh: Shown, rear: Vec3, kingpin: Vec3, horse: Vec3) {
        val t = sh.trail
        t.clear()
        val dx = kingpin.x - rear.x
        val dz = kingpin.z - rear.z
        val len = hypot(dx, dz).coerceAtLeast(1e-6)
        val back = Vec3(rear.x - dx / len * 1.5, rear.y, rear.z - dz / len * 1.5)
        t.add(back.x, back.y, back.z, false)
        line(t, back, rear)
        line(t, rear, kingpin)
        line(t, kingpin, horse)
        val hx = horse.x - kingpin.x
        val hz = horse.z - kingpin.z
        val hl = hypot(hx, hz)
        if (hl > 1e-6) {
            t.hx = hx / hl
            t.hz = hz / hl
        } else {
            t.hx = dx / len
            t.hz = dz / len
        }
        sh.s = t.endAlong
        sh.v = 0.0
    }

    /** Points every half block from [a] (already laid) to [b]. */
    private fun line(t: Trail, a: Vec3, b: Vec3) {
        val d = hypot(b.x - a.x, b.z - a.z)
        val n = max(1, kotlin.math.ceil(d / STEP).toInt())
        for (k in 1..n) {
            val f = k / n.toDouble()
            t.add(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f, false)
        }
    }

    /** The trail laid afresh along [sh]'s bodies as they stand now (after a scene has moved them, too). */
    fun reseed(sh: Shown) {
        val w = sh.wagon
        val rear = w.position()
        val k = w.point(0f, 0f, -WHEELBASE_PX).let { Vec3(it.x, Ground.stand(sh.level, it.x, it.z, rear.y + 0.6), it.z) }
        val horse = sh.horse
        val h = if (horse != null && sh.harnessed) horse.position() else {
            val dx = k.x - rear.x
            val dz = k.z - rear.z
            val len = hypot(dx, dz).coerceAtLeast(1e-6)
            Vec3(k.x + dx / len * Train.AHEAD, k.y, k.z + dz / len * Train.AHEAD)
        }
        seed(sh, rear, k, h)
    }

    /** The way the owner has walked since last tick, laid as far as it may be: toward them, turning no tighter than a wagon can. */
    private fun grow(sh: Shown, owner: ServerPlayer) {
        val t = sh.trail
        if (t.size == 0) return
        val lag = hypot(owner.x - t.x(t.size - 1), owner.z - t.z(t.size - 1))
        // Fallen behind (a run, a step out from beside the wagon): it catches up a little faster than they walk
        if (lag > 2.0) sh.budget += (lag - 2.0) * 0.25
        sh.budget = min(sh.budget, 6.0)
        var laid = 0
        while (sh.budget >= STEP && laid < 12) {
            val ex = t.x(t.size - 1)
            val ez = t.z(t.size - 1)
            val ey = t.y(t.size - 1)
            val dx = owner.x - ex
            val dz = owner.z - ez
            if (hypot(dx, dz) < LAG) break
            val want = atan2(dz, dx)
            val have = atan2(t.hz, t.hx)
            var turn = want - have
            while (turn > Math.PI) turn -= 2 * Math.PI
            while (turn < -Math.PI) turn += 2 * Math.PI
            val a = have + turn.coerceIn(-TURN, TURN)
            val hx = cos(a)
            val hz = sin(a)
            val nx = ex + hx * STEP
            val nz = ez + hz * STEP
            val (ny, blocked) = point(sh.level, nx, nz, ey, hx, hz)
            if (!cutLoop(sh, nx, nz, hx, hz) && t.full && t.along(1) > sh.rearS - 0.5) {
                // The way ahead is longer than it can keep: it waits rather than lose it
                lost(sh, owner)
                return
            }
            t.hx = hx
            t.hz = hz
            t.add(nx, ny, nz, blocked)
            sh.budget -= STEP
            laid++
        }
    }

    /**
     * A loop walked: the new point [x], [z] (heading [hx], [hz]) comes back within [LOOP] of the way ahead of the horse, going on more or less the
     * way it went there, so the way is cut back to there and the loop is never driven. True if it was.
     */
    private fun cutLoop(sh: Shown, x: Double, z: Double, hx: Double, hz: Double): Boolean {
        val t = sh.trail
        for (i in 1 until t.size - 4) {
            if (t.along(i) <= sh.s + 1.0) continue
            if (hypot(t.x(i) - x, t.z(i) - z) >= LOOP) continue
            // Doubling back on itself is no loop: the wagon turns round, as it must
            val dx = t.x(i) - t.x(i - 1)
            val dz = t.z(i) - t.z(i - 1)
            if (dx * hx + dz * hz < 0.0) continue
            t.keep(i + 1)
            return true
        }
        return false
    }

    /**
     * The ground at [x], [z] for a point of the way after one on the ground at [prevY] heading ([hx], [hz]), and whether the wagon can go
     * there: not if nobody can see it, it is more than a block up or down, a wall stands at its height or its sides, a roof over it, the
     * water is deep, or it is the top of a tree. The ground is looked for from the way's own height, not the owner's, so a jump or a
     * climb they make is not a step the wagon takes.
     */
    fun point(level: ServerLevel, x: Double, z: Double, prevY: Double, hx: Double, hz: Double): Pair<Double, Boolean> {
        val ref = prevY + 0.2
        val g = Ground.at(level, x, z, ref)
        if (g.isNaN() || g <= ref - 2.95) return prevY to true
        if (abs(g - prevY) > STEP_MOST) return g to true
        for (up in UP) if (Ground.solid(level, x, g + up, z)) return g to true
        val rx = -hz * SIDE
        val rz = hx * SIDE
        for (side in intArrayOf(-1, 1)) for (up in SIDE_UP) if (Ground.solid(level, x + rx * side, g + up, z + rz * side)) return g to true
        val wet = BlockPos.containing(x, g + 0.45, z)
        if (!level.getFluidState(wet).isEmpty) return g to true
        val under = level.getBlockState(BlockPos.containing(x, g - 0.05, z))
        if (under.`is`(BlockTags.LEAVES)) return g to true
        return g to false
    }

    /** Heights over the ground looked at for a roof (the canvas's hoops are under 2.7), and at its sides for a wall (a wheel's hub, the bed, the canvas). */
    private val UP = doubleArrayOf(1.2, 2.2, 2.7)
    private val SIDE_UP = doubleArrayOf(0.6, 1.1, 2.0)

    /** The wagon's front axle from its back axle, px (WagonMesh.WHEELBASE). */
    private const val WHEELBASE_PX = 32f

    // ---- Led ----

    /** One tick led: the way laid on as the owner walks it, the horse along it at their pace, the wagon behind it; the rein's reach. */
    private fun lead(sh: Shown, rec: WagonRec, now: Long) {
        val level = sh.level
        val owner = sh.holder?.let { level.server.playerList.getPlayer(it) }
        val horse = sh.horse
        if (owner == null || horse == null || !sh.harnessed || owner.level() !== level || !owner.isAlive || owner.isSpectator || owner.isSleeping || !TradeConfig.wagon()) {
            return park(sh, rec, said = owner != null && owner.level() === level && owner.isAlive)
        }
        val jump = hypot(owner.x - sh.lastX, owner.z - sh.lastZ)
        if (jump > JUMP || hypot(owner.x - horse.x, owner.z - horse.z) > OFF_LEAD) {
            park(sh, rec, said = false)
            owner.sendOverlayMessage(Component.translatable("message.guildmark.trade.wagon.left"))
            return
        }
        sh.lastX = owner.x
        sh.lastZ = owner.z
        val beside = beside(sh, owner)
        val (hfx, hfz) = forward(horse.yRot)
        val head = Vec3(horse.x + hfx * HEAD_AHEAD, horse.y, horse.z + hfz * HEAD_AHEAD)
        val nose = hypot(owner.x - head.x, owner.z - head.z)
        // Come back to the horse while it is held up: the way ahead is forgotten, and it follows them anew from here
        if (sh.heldUp && nose < REACH) {
            reseed(sh)
            sh.heldUp = false
            sh.toldHeld = false
        } else if (nose < NOSE && sh.v == 0.0 && sh.trail.endAlong - sh.s > 1.0) {
            // At its nose with it standing: whatever way it had ahead (a turn back to it, a loop) is forgotten
            reseed(sh)
        }
        if (beside || sh.halting) sh.budget = 0.0 else {
            sh.budget += jump
            grow(sh, owner)
            if (!sh.led) return
        }
        // The rein's end: it goes slack, the horse slows to a stop and the brake goes on
        val reach = hypot(owner.x - horse.x, owner.z - horse.z)
        if (!sh.halting && reach > TradeConfig.wagonRein()) {
            sh.halting = true
            level.playSound(null, head.x, head.y + 1.4, head.z, TradeSounds.get("trade.rein_slack"), SoundSource.NEUTRAL, 0.9f, 0.95f + level.random.nextFloat() * 0.1f)
        }
        val t = sh.trail
        val ownerAlong = t.endAlong + hypot(owner.x - t.x(t.size - 1), owner.z - t.z(t.size - 1))
        val blockedAt = t.firstBlocked(sh.s - 0.01)
        val most = min(blockedAt - CLEAR, t.endAlong)
        val crowded = beside || nose < NOSE || sh.openers > 0 || sh.scene != null
        val want = if (sh.halting || crowded) 0.0 else ((ownerAlong - GAP - sh.s) * PULL).coerceIn(0.0, TOP)
        val was = sh.v
        sh.v = if (want > sh.v) min(want, sh.v + ACCEL) else max(want, sh.v - DECEL)
        if (sh.s + sh.v > most) sh.v = max(0.0, most - sh.s)
        sh.s += sh.v
        // Held up short of a place it cannot go, while its owner would have it on: said once
        val heldUp = blockedAt.isFinite() && most - sh.s < 0.05 && ownerAlong - GAP - sh.s > 1.0 && !sh.halting
        if (heldUp && !sh.toldHeld) {
            sh.toldHeld = true
            owner.sendOverlayMessage(Component.translatable("message.guildmark.trade.wagon.held_up"))
        }
        sh.heldUp = heldUp
        place(sh, snap = false)
        forget(sh)
        // The horse walks, leaning into the collar to start it and up a rise; stands; and the brake
        val climbing = !sh.lastHorseY.isNaN() && horse.y - sh.lastHorseY > 0.02
        sh.lastHorseY = horse.y
        horse.move(if (sh.v > 0.006) DraughtHorse.Move.WALK else DraughtHorse.Move.IDLE)
        horse.flag(DraughtHorse.FLAG_STRAIN, sh.v > 0.006 && ((sh.v > was && sh.v < 0.1) || climbing))
        if (sh.v > 0.0) {
            sh.still = 0
            if (sh.wagon.has(Wagon.FLAG_BRAKE)) sh.wagon.flag(Wagon.FLAG_BRAKE, false)
        } else if (++sh.still == BRAKE_AFTER) brake(sh)
        val taut = !sh.halting && (sh.v > 0.04 || reach > GAP + 3.0)
        if (taut != sh.taut) {
            sh.taut = taut
            sh.sendNow = true
        }
        if (now % 20L == 0L) keep(sh, rec, WagonRec.State.LED)
        // Stopped at the rein's end: it waits
        if (sh.halting && sh.v <= 0.0) {
            park(sh, rec, said = false)
            owner.sendOverlayMessage(Component.translatable("message.guildmark.trade.wagon.waiting"))
        }
    }

    /** Whether [owner] is beside [sh] (within [BESIDE] of its line, back axle to the horse's head), going round to its tailboard or looking it over. */
    private fun beside(sh: Shown, owner: ServerPlayer): Boolean {
        val w = sh.wagon
        val rear = w.position()
        val k = w.point(0f, 0f, -WHEELBASE_PX)
        val horse = sh.horse ?: return segment(owner.x, owner.z, rear, k) < BESIDE
        val (fx, fz) = forward(horse.yRot)
        val head = Vec3(horse.x + fx * HEAD_AHEAD, horse.y, horse.z + fz * HEAD_AHEAD)
        return segment(owner.x, owner.z, rear, k) < BESIDE || segment(owner.x, owner.z, k, head) < BESIDE
    }

    /** The old points the back axle has left behind go. */
    private fun forget(sh: Shown) {
        val t = sh.trail
        while (t.size > 2 && t.along(1) < sh.rearS - 3.0) t.dropOldest()
    }

    /** The way ahead is longer than [RING] points: it waits rather than lose it. */
    private fun lost(sh: Shown, owner: ServerPlayer) {
        val rec = recOf(sh) ?: return
        park(sh, rec, said = false)
        owner.sendOverlayMessage(Component.translatable("message.guildmark.trade.wagon.lost"))
    }

    /** [sh] along its trail as it is now: the horse at [Shown.s], the front and back axles behind it (or, with no horse in the shafts, straight). */
    fun place(sh: Shown, snap: Boolean) {
        val p = sh.trail
        if (p.size == 0) return
        val h = p.at(sh.s)
        val ks = Train.back(p, sh.s, Train.AHEAD)
        val k = p.at(ks)
        sh.rearS = Train.back(p, ks, Train.WHEELBASE)
        val r = p.at(sh.rearS)
        val horse = sh.horse
        if (sh.harnessed && horse != null) {
            sh.wagon.place(sh.level, r, k, h, r.y, k.y, snap)
            horse.place(sh.level, h, yawOf(h.x - k.x, h.z - k.z), h.y, snap)
        } else {
            val dx = k.x - r.x
            val dz = k.z - r.z
            val len = hypot(dx, dz).coerceAtLeast(1e-6)
            sh.wagon.place(sh.level, r, k, Vec3(k.x + dx / len * Train.AHEAD, k.y, k.z + dz / len * Train.AHEAD), r.y, k.y, snap)
        }
    }

    /** A horse not in the shafts (in the yard) stands tethered beside the wagon's right, its nosebag on. */
    private fun tether(sh: Shown, snap: Boolean) {
        val horse = sh.horse ?: return
        val w = sh.wagon
        val at = w.point(TETHER_X, 0f, TETHER_Z)
        horse.place(sh.level, Vec3(at.x, at.y, at.z), w.yRot, w.y, snap)
        horse.move(DraughtHorse.Move.NOSEBAG)
        horse.flag(DraughtHorse.FLAG_NOSEBAG, true)
    }

    /** Where the horse stands tethered in the yard: px of the wagon's space, beside its right side by the middle of the bed. */
    const val TETHER_X = -40f
    const val TETHER_Z = -14f

    /** The brake goes on: the lever hauled, the squeal. */
    fun brake(sh: Shown) {
        if (sh.wagon.has(Wagon.FLAG_BRAKE)) return
        sh.wagon.flag(Wagon.FLAG_BRAKE, true)
        val at = sh.wagon.point(-11f, -24f, -40f)
        sh.level.playSound(null, at.x, at.y, at.z, TradeSounds.get("trade.wagon_brake"), SoundSource.NEUTRAL, 0.8f, 0.95f + sh.level.random.nextFloat() * 0.1f)
    }

    /** The record brought up to date with where [sh] stands, in [state]. */
    fun keep(sh: Shown, rec: WagonRec, state: WagonRec.State) {
        val w = sh.wagon
        val at = w.position()
        val moved = rec.at?.let { it.distanceToSqr(at) > 0.25 } ?: true
        if (!moved && rec.state == state && rec.dimension == sh.level.dimension()) return
        rec.at = at
        rec.yaw = w.yRot
        rec.dimension = sh.level.dimension()
        rec.state = state
        TradeWagons.peek(sh.level)?.changed()
    }

    // ---- Taking the lead, and letting go ----

    /** [player] takes [sh]'s lead: the brake off, the way laid afresh from where it stands, and it follows them. */
    fun take(sh: Shown, player: ServerPlayer, rec: WagonRec): Boolean {
        if (sh.level !== player.level() || !sh.harnessed || sh.scene != null) return false
        if (player.isSleeping || player.isSpectator || !player.isAlive) return false
        reseed(sh)
        sh.holder = player.uuid
        sh.lastX = player.x
        sh.lastZ = player.z
        sh.budget = 0.0
        sh.halting = false
        sh.heldUp = false
        sh.toldHeld = false
        sh.still = 0
        sh.taut = false
        sh.sendNow = true
        sh.wagon.flag(Wagon.FLAG_BRAKE, false)
        keep(sh, rec, WagonRec.State.LED)
        rec.ownerName = player.name.string
        val head = headPoint(sh)
        Bodies.sound(sh.level, head, "reins_flick", 0.6f, 1.0f)
        sh.horse?.let { h ->
            h.flag(DraughtHorse.FLAG_NOSEBAG, false)
            if (h.move != DraughtHorse.Move.IDLE) h.move(DraughtHorse.Move.IDLE)
        }
        return true
    }

    /** [sh] stops where it is: nobody holds its lead, its brake goes on, its record says it is parked here. [said]: its owner is told it waits. */
    fun park(sh: Shown, rec: WagonRec, said: Boolean) {
        val owner = sh.holder?.let { sh.level.server.playerList.getPlayer(it) }
        sh.holder = null
        sh.halting = false
        sh.heldUp = false
        sh.v = 0.0
        sh.taut = false
        sh.sendNow = true
        sh.horse?.let {
            it.move(DraughtHorse.Move.IDLE)
            it.flag(DraughtHorse.FLAG_STRAIN, false)
        }
        brake(sh)
        keep(sh, rec, WagonRec.State.PARKED)
        if (said) owner?.sendOverlayMessage(Component.translatable("message.guildmark.trade.wagon.let_go"))
    }

    /** [player] is leaving, dying or being taken elsewhere: their wagon is parked where it stands, without a word. */
    private fun letGoOf(player: ServerPlayer) {
        val sh = shown[player.uuid] ?: return
        if (!sh.led) return
        val rec = recOf(sh) ?: return
        park(sh, rec, said = false)
    }

    // ---- A hand on it ----

    /** Where [sh]'s horse's head is (or, with no horse in the shafts, its shafts' tips). */
    fun headPoint(sh: Shown): Vec3 {
        val h = sh.horse
        if (h != null && !h.isRemoved) {
            val (fx, fz) = forward(h.yRot)
            return Vec3(h.x + fx * HEAD_AHEAD, h.y + 1.7, h.z + fz * HEAD_AHEAD)
        }
        return sh.wagon.point(0f, -12f, -86f)
    }

    /** Where [sh]'s tailboard is, its middle. */
    fun tailPoint(sh: Shown): Vec3 = sh.wagon.point(0f, -16f, 12f)

    /**
     * The client says [player] used [part] of the wagon or horse [id]. In reach and theirs: the head (an empty hand) takes the lead or lets go,
     * the tailboard opens its hold. Someone else's: "That is Ransom's wagon.".
     */
    fun used(player: ServerPlayer, id: Int, part: Int) {
        if (player.isSpectator) return
        val sh = shown.values.firstOrNull { it.wagon.id == id || it.horse?.id == id } ?: return
        if (sh.wagon.isRemoved || sh.level !== player.level()) return
        val rec = recOf(sh) ?: return
        val at = if (part == HEAD) headPoint(sh) else tailPoint(sh)
        if (player.eyePosition.distanceTo(at) > REACH + 1.0) return
        val now = player.level().gameTime
        if (now - (lastUse[player.uuid] ?: Long.MIN_VALUE / 2) < 4L) return
        lastUse[player.uuid] = now
        if (!mayOpen(player, rec)) {
            player.sendOverlayMessage(notYours(rec))
            return
        }
        if (rec.ownerName != player.name.string && rec.owner == player.uuid) rec.ownerName = player.name.string
        when (part) {
            HEAD -> head(sh, player, rec)
            TAIL -> openHold(sh, player, rec)
        }
    }

    /** A hand on the horse's head: its lead taken up, or let go of. */
    private fun head(sh: Shown, player: ServerPlayer, rec: WagonRec) {
        if (!player.mainHandItem.isEmpty) return
        if (sh.holder == player.uuid) {
            park(sh, rec, said = true)
            Bodies.sound(sh.level, headPoint(sh), "reins_haul", 0.5f, 1.0f)
            return
        }
        val why = when {
            rec.owner != player.uuid -> "message.guildmark.trade.wagon.not_yours_lead"
            !TradeConfig.wagon() -> "message.guildmark.trade.wagon.off"
            sh.scene != null -> "message.guildmark.trade.wagon.busy"
            rec.state == WagonRec.State.YARD -> "message.guildmark.trade.wagon.in_yard"
            !sh.harnessed -> "message.guildmark.trade.wagon.no_horse"
            player.isPassenger -> "message.guildmark.trade.wagon.mounted"
            else -> null
        }
        if (why != null) {
            player.sendOverlayMessage(Component.translatable(why))
            return
        }
        if (take(sh, player, rec)) player.sendOverlayMessage(Component.translatable("message.guildmark.trade.wagon.led"))
    }

    /** Whether [player] may open or lead [rec]'s wagon: its owner, or an operator in creative. */
    private fun mayOpen(player: ServerPlayer, rec: WagonRec): Boolean {
        if (rec.owner == player.uuid) return true
        val server = player.level().server
        return player.isCreative && server.playerList.isOp(player.nameAndId())
    }

    private fun notYours(rec: WagonRec): Component =
        if (rec.ownerName.isEmpty()) Component.translatable("message.guildmark.trade.wagon.not_yours_unknown")
        else Component.translatable("message.guildmark.trade.wagon.not_yours", rec.ownerName)

    // ---- Its hold ----

    /** [owner]'s wagon's hold, over its record's stacks (one for each record, as long as the server runs). */
    fun holdOf(level: ServerLevel, owner: UUID): WagonHold? {
        val data = TradeWagons.peek(level) ?: return null
        if (data[owner] == null) return null
        val have = holds[owner]
        if (have != null && have.data === data) return have
        return WagonHold(owner, data).also { holds[owner] = it }
    }

    /** Opens [sh]'s 54 slots to [player]: the tailboard falls open on its chains. */
    private fun openHold(sh: Shown, player: ServerPlayer, rec: WagonRec) {
        val hold = holdOf(sh.level, rec.owner) ?: return
        val title = Component.translatable("gui.guildmark.trade.wagon.hold", rec.ownerName.ifEmpty { player.name.string })
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> ChestMenu.sixRows(id, inventory, hold) }, title))
    }

    /** Whether [player] can keep [owner]'s hold open: its bodies out in their level and its tailboard within reach. */
    fun inReach(owner: UUID, player: Player): Boolean {
        val sh = of(owner) ?: return false
        if (sh.level !== player.level() || !player.isAlive) return false
        if (player.uuid != owner && !player.isCreative) return false
        return player.position().distanceTo(tailPoint(sh)) <= HOLD_REACH
    }

    /** Someone opened (+1) or shut (-1) [owner]'s hold. */
    fun opened(owner: UUID, by: Int) {
        val sh = shown[owner] ?: return
        sh.openers = max(0, sh.openers + by)
    }

    /** What's in [owner]'s wagon changed: it is heaped afresh on the bed. */
    fun loadChanged(owner: UUID) {
        shown[owner]?.heap = true
    }

    /** The tailboard down while the hold is open (or someone rides on it), shut otherwise: it falls open on its chains, and is put up again. */
    private fun tailboard(sh: Shown) {
        val down = sh.openers > 0 || sh.wagon.seat(2) == Wagon.TAIL || sh.wagon.seat(3) == Wagon.TAIL
        if (down == sh.tailDown) return
        sh.tailDown = down
        sh.wagon.flag(Wagon.FLAG_TAILBOARD_SHUT, !down)
        val at = tailPoint(sh)
        sh.level.playSound(null, at.x, at.y, at.z, TradeSounds.get("trade.tailboard"), SoundSource.NEUTRAL, 0.8f, if (down) 1.0f else 1.1f)
    }

    /** The load heaped on the bed from what the hold really has: a full hold heaps the bed as full as a caravan's. */
    private fun heap(sh: Shown, rec: WagonRec) {
        sh.heap = false
        val full = DoubleArray(Good.entries.size)
        for (stack in rec.stacks) {
            if (stack.isEmpty) continue
            val good = Good.of(stack.item) ?: continue
            full[good.ordinal] += stack.count.toDouble() / stack.maxStackSize.coerceAtLeast(1)
        }
        val load = Good.entries.filter { full[it.ordinal] > 0.0 }.map { it to max(1, (full[it.ordinal] * PIECES_FULL / TradeWagons.SLOTS).roundToInt()) }
        sh.wagon.load(Show.slots(load))
        val laden = rec.used() / TradeWagons.SLOTS.toFloat()
        if (abs(laden - sh.laden) > 0.01f) {
            sh.laden = laden
            sh.sendNow = true
        }
    }

    /** The units [Show.slots] heaps on a full bed (its sixteen places of four). */
    private const val PIECES_FULL = 64.0

    /** The lantern lit at dusk and in a storm, once it has been rolled out. */
    fun lantern(sh: Shown, rec: WagonRec) {
        val day = Math.floorMod(sh.level.overworldClockTime, 24000L)
        sh.wagon.flag(Wagon.FLAG_LANTERN, rec.state != WagonRec.State.YARD && sh.raised >= 0L && (day in DUSK..DAWN || sh.level.isThundering))
    }

    /** Whether it is dark enough for a lantern. */
    fun dusk(level: ServerLevel): Boolean = Math.floorMod(level.overworldClockTime, 24000L) in DUSK..DAWN || level.isThundering

    // ---- What the clients are told ----

    private fun send(sh: Shown, rec: WagonRec, now: Long) {
        sh.sendNow = false
        sh.synced = now
        val holder = sh.holder?.let { sh.level.server.playerList.getPlayer(it) }?.takeIf { it.level() === sh.level }?.id ?: -1
        val payload = WagonSyncPayload(
            sh.wagon.id, sh.horse?.id ?: -1, rec.ownerName, Dye.entries[rec.colour.coerceIn(0, Dye.entries.size - 1)].rgb, holder, sh.taut,
            sh.raised, sh.laden, true,
        )
        for (p in sh.level.players()) if (p.distanceToSqr(sh.wagon) <= SEEN * SEEN) PacketDistributor.sendToPlayer(p, payload)
    }

    private fun tellGone(sh: Shown) {
        val payload = WagonSyncPayload(sh.wagon.id, sh.horse?.id ?: -1, "", 0, -1, false, 0L, 0f, false)
        for (p in sh.level.players()) if (p.distanceToSqr(sh.wagon) <= SEEN * SEEN) PacketDistributor.sendToPlayer(p, payload)
    }

    // ---- For the carriers ----

    /** The hold of [player]'s wagon if it is within [radius] blocks of them (of its bed), in their level: what [Carriers] counts. */
    private fun holdings(player: ServerPlayer, radius: Double): List<Container> {
        if (shown.isEmpty() || !TradeConfig.carry()) return listOf()
        val sh = of(player.uuid) ?: return listOf()
        if (sh.level !== player.level() || bedDistance(sh, player.position()) > radius) return listOf()
        return listOfNotNull(holdOf(sh.level, player.uuid))
    }

    /** How far [p] is from [sh]'s bed (its line from the back axle to the front, and up or down). */
    fun bedDistance(sh: Shown, p: Vec3): Double {
        val rear = sh.wagon.position()
        val front = sh.wagon.point(0f, 0f, -WHEELBASE_PX)
        return hypot(segment(p.x, p.z, rear, front), max(0.0, abs(p.y - rear.y) - 1.5))
    }

    /**
     * `trade carry recall` and `unstick`: [player]'s wagon set down beside them, harnessed if it has its horse, its brake on; wherever it was (a yard,
     * a roadside, another dimension), and led too for the unstick ([led]). Beside them if there is room, behind them if not, else by their feet.
     */
    fun bringBeside(player: ServerPlayer, led: Boolean): Boolean {
        val level = player.level()
        val data = TradeWagons.peek(level) ?: return false
        val rec = data[player.uuid] ?: return false
        val sh = shown[player.uuid]
        if (sh != null && sh.led && !led) return false
        val from = sh?.takeIf { !it.wagon.isRemoved }?.let { it.wagon.point(0f, -20f, -16f) to it.level }
        val (rear, yaw) = besideOf(level, player)
        sh?.let { drop(it) }
        rec.state = WagonRec.State.PARKED
        rec.at = rear
        rec.yaw = yaw
        rec.dimension = level.dimension()
        rec.due = 0L
        data.changed()
        from?.let { (at, l) -> dust(l, at, 10) }
        val made = make(level, rec) ?: return true
        dust(level, made.wagon.point(0f, -20f, -16f), 14)
        return true
    }

    /** Where a wagon set down beside [player] stands: its back axle and its heading, the way they face, on their right, their left, behind them, else by their feet. */
    fun besideOf(level: ServerLevel, player: ServerPlayer): Pair<Vec3, Float> {
        val yaw = player.yRot
        val (fx, fz) = forward(yaw)
        val rx = -fz
        val rz = fx
        val half = Train.LENGTH / 2.0
        val tries = listOf(
            Vec3(player.x + rx * 3.0 - fx * half, player.y, player.z + rz * 3.0 - fz * half),
            Vec3(player.x - rx * 3.0 - fx * half, player.y, player.z - rz * 3.0 - fz * half),
            Vec3(player.x - fx * (Train.LENGTH + 2.5), player.y, player.z - fz * (Train.LENGTH + 2.5)),
        )
        for (rear in tries) if (clear(level, rear, fx, fz)) return Vec3(rear.x, Ground.stand(level, rear.x, rear.z, player.y + 0.6), rear.z) to yaw
        // Nowhere clear: it is set down by their feet all the same, for nothing is ever lost to a wall
        val rear = Vec3(player.x - fx * 1.0, player.y, player.z - fz * 1.0)
        return rear to yaw
    }

    /** Whether a wagon and its horse could stand straight from [rear] heading ([fx], [fz]): every point along them one the way could go through. */
    fun clear(level: ServerLevel, rear: Vec3, fx: Double, fz: Double): Boolean {
        var prev = Ground.at(level, rear.x, rear.z, rear.y + 0.2)
        if (prev.isNaN()) return false
        var d = -1.0
        while (d <= Train.LENGTH + HEAD_AHEAD) {
            val x = rear.x + fx * d
            val z = rear.z + fz * d
            val (y, blocked) = point(level, x, z, prev, fx, fz)
            if (blocked) return false
            prev = y
            d += STEP
        }
        return true
    }

    // ---- Words ----

    /** `trade carry status`: where [player]'s wagon is and what it is doing. */
    private fun report(player: ServerPlayer): List<Component> {
        val rec = TradeWagons.peek(player.level())?.get(player.uuid) ?: return listOf(Component.literal("No Carrier's Wagon of yours.").withStyle(ChatFormatting.GRAY))
        return listOf(statusLine(player, rec))
    }

    /** One line on [rec]'s wagon for [player]: where, how far, what it's doing, what it holds and its horse. */
    fun statusLine(player: ServerPlayer, rec: WagonRec): Component {
        val at = rec.at
        val where = at?.let { "%d %d %d".format(Locale.ROOT, it.x.toInt(), it.y.toInt(), it.z.toInt()) } ?: "nowhere yet"
        val here = rec.dimension == player.level().dimension()
        val far = if (at != null && here) "%.0f blocks".format(Locale.ROOT, at.distanceTo(player.position())) else if (at != null) "another dimension" else "-"
        val clock = player.level().server.overworld().overworldClockTime
        val doing = when (rec.state) {
            WagonRec.State.LED -> "on your lead"
            WagonRec.State.PARKED -> "parked"
            WagonRec.State.YARD -> if (rec.arrived(clock)) "in the yard" else "on its way to the yard (${(rec.due - clock).coerceAtLeast(0) / 20}s)"
        }
        val horse = if (rec.horse) "its horse ${rec.horseName.ifEmpty { "unnamed" }}" else "no horse"
        val load = "%.1f".format(Locale.ROOT, Laden.worth(holdOf(player.level(), rec.owner) ?: return Component.empty()))
        val shownNow = if (of(rec.owner) != null) ", shown" else ""
        val line: MutableComponent = Component.empty()
        line.append(Component.literal("Carrier's Wagon: ").withStyle(ChatFormatting.YELLOW))
        line.append(Component.literal("$where ($far), $doing$shownNow; ${rec.used()}/${TradeWagons.SLOTS} slots, $load Marks at base prices; $horse").withStyle(ChatFormatting.WHITE))
        return line
    }

    // ---- Small sums ----

    /** Minecraft's yaw for a heading: 0 south, 90 west. */
    fun yawOf(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

    /** The unit heading of [yaw]. */
    fun forward(yaw: Float): Pair<Double, Double> {
        val r = Math.toRadians(yaw.toDouble())
        return -sin(r) to cos(r)
    }

    /** How far [x], [z] is from the segment [a] to [b], across the ground. */
    fun segment(x: Double, z: Double, a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = dx * dx + dz * dz
        val t = if (len < 1e-9) 0.0 else (((x - a.x) * dx + (z - a.z) * dz) / len).coerceIn(0.0, 1.0)
        return hypot(a.x + dx * t - x, a.z + dz * t - z)
    }
}

/**
 * A trader's wagon's 54 slots (build part D3): a container over its record's own stacks ([WagonRec.stacks]), so what is put in is in the record
 * at once and saved with it, whether its bodies are out or not. Open while its owner stands by its tailboard ([LedShow.inReach]); its tailboard
 * is down while it is open.
 */
internal class WagonHold(val owner: UUID, val data: TradeWagons) : Container {
    private val stacks: NonNullList<ItemStack>? get() = data[owner]?.stacks

    override fun getContainerSize(): Int = TradeWagons.SLOTS

    override fun isEmpty(): Boolean = stacks?.all { it.isEmpty } ?: true

    override fun getItem(slot: Int): ItemStack = stacks?.getOrNull(slot) ?: ItemStack.EMPTY

    override fun removeItem(slot: Int, count: Int): ItemStack {
        val list = stacks ?: return ItemStack.EMPTY
        val out = ContainerHelper.removeItem(list, slot, count)
        if (!out.isEmpty) setChanged()
        return out
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val list = stacks ?: return ItemStack.EMPTY
        val out = ContainerHelper.takeItem(list, slot)
        if (!out.isEmpty) data.changed()
        return out
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        val list = stacks ?: return
        if (slot !in 0 until list.size) return
        list[slot] = stack
        stack.limitSize(getMaxStackSize(stack))
        setChanged()
    }

    override fun setChanged() {
        data.changed()
        LedShow.loadChanged(owner)
    }

    override fun stillValid(player: Player): Boolean = LedShow.inReach(owner, player)

    override fun clearContent() {
        val list = stacks ?: return
        for (i in list.indices) list[i] = ItemStack.EMPTY
        setChanged()
    }

    override fun startOpen(user: ContainerUser) {
        LedShow.opened(owner, 1)
    }

    override fun stopOpen(user: ContainerUser) {
        LedShow.opened(owner, -1)
    }
}
