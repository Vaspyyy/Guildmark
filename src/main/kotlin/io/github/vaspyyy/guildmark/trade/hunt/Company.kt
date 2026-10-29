package io.github.vaspyyy.guildmark.trade.hunt

import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.economy.Serving
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.Rungs
import io.github.vaspyyy.guildmark.trade.TradeBooks
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.Ware
import io.github.vaspyyy.guildmark.trade.Wares
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Company on the road (living-world/trader/design.md, Part 4, "Company"): the hired guard. One is hired at the market hall's counter (the
 * Carriers section) for one to three days at [TradeConfig.guardWage] Marks a day, paid up front, as many at once as the ladder allows
 * (`Rungs.guardLimit`, a travelling companion counting as one of them). The hall's guard comes round to the trader from out of sight, greets
 * them and walks three or four blocks behind and to one side ([GuardDirector]). In a Hunt they step in front at the leader's call and, once a
 * blow makes it a fight, fight the party for real; anywhere else they stay out of trouble and come to no harm.
 *
 * The contract (days left, the look, the name's seed) is the trader's, kept in [TradeBooks] (`guards`, one compound each); the body is never
 * kept (one saved with a chunk goes again as it loads) and is made again beside the trader whenever it is missing: after a restart, a
 * return to the game, a death, or a long way left behind. The days run on the world's clock while the trader is in the game (a night slept
 * through counts), and stand still while they are away and while the Hunt is switched off, when no guard walks. When the days are up the guard
 * says so and walks off towards the nearest town, gone once nobody sees them; brought down in a fight, the contract ends and the trader is told.
 */
internal object Company {
    /** Every guard body carries this tag, so one saved with a chunk by mistake goes again as it loads. */
    const val TAG = "guildmark.guard"

    /** The most days one hire is for, and the counter's wares (`hired_guard_1` to `_3`). */
    const val MOST_DAYS = 3
    const val WARE = "hired_guard_"

    /** The most guards an operator's command hires for one player, whatever the ladder says. */
    const val COMMAND_MOST = 3

    /** A day of the world's clock, and a second of ticks. */
    private const val DAY = 24000L
    private const val SECOND = 20L

    /** How near the trader a guard must be to count as company (the chance's followers, the run for the camp). */
    private const val NEAR = 24.0

    /** A guard further off than this (or out of the trader's sight further than [LOST_SEEN]) is lost and made again beside them. */
    private const val LOST = 64.0
    private const val LOST_SEEN = 40.0

    /** How long a guard has to come up to the trader before they are made again at their side, and the longest one walks off for. */
    private const val COMING_MOST = 1200L
    private const val LEAVING_MOST = 1800L

    /** The lang keys of what stands in the way of a hire. */
    const val GATE_OFF = "gui.guildmark.trade.guard.gate.off"
    const val GATE_RUNG = "gui.guildmark.trade.guard.gate.hawker"
    const val GATE_FULL = "gui.guildmark.trade.guard.gate.full"

    /** What a guard is doing, as the [GuardDirector] plays it. */
    enum class State { COMING, FOLLOW, HOLD, LEAVING }

    /** One guard body walking with [owner] for the contract [id], and how it stands. Not saved. */
    internal class Guard(val owner: UUID, val id: Long, val p: Townsperson, var slot: Int, state: State, now: Long) {
        var state = state
            private set
        var since = now
            private set
        val stride = Stride(p)

        /** The Hunt's leader was heard: where from, and until when the raised hand ([Activity.HALT]) holds them where they are. */
        var holdFrom: Vec3? = null
        var haltUntil = 0L

        /** Stood still since then (-1 walking), the next thing done to pass the time, the blocks walked since the spear's butt last rang. */
        var stillSince = -1L
        var idleAt = 0L
        var walked = 0.0
        var lastX = p.x
        var lastZ = p.z

        /** The stair they sit on while the trader sits (rides, sleeps). */
        var sat: BlockPos? = null

        fun to(next: State, now: Long) {
            state = next
            since = now
        }
    }

    /** How the trader moves, for keeping step: where they were a tick ago, their speed (blocks a second) and heading, and since when they have stood. */
    internal class Pace(var x: Double, var z: Double) {
        var speed = 0.0
        var hx = 0.0
        var hz = 1.0
        var stillSince = 0L
        var tick = 0L
    }

    private val bodies = HashMap<Long, Guard>()
    private val paces = HashMap<UUID, Pace>()

    /** The world's clock each contract was last counted down at, while its trader is in the game. */
    private val counted = HashMap<Long, Long>()

    /** Contracts whose next body is made at the trader's side and not out of sight (the last one could not come up). */
    private val besideNext = HashSet<Long>()

    /** A counter for contract ids made in the same tick. */
    private var serial = 0L

    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java) { hurt(it) }
        NeoForge.EVENT_BUS.addListener(LivingDropsEvent::class.java) { drops(it) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> sendOff(event.entity.uuid) }
        for (days in 1..MOST_DAYS) {
            Wares.add(Ware("$WARE$days", { price(days) }, gate = ::gate, action = { hire(it, days) != null }, scene = { player, merchant -> scene(player, merchant) }))
        }
    }

    // ---- What the Hunt asks ----

    /** The trader's company for the odds: their guards within [NEAR] of them and a travelling companion beside them. */
    fun followers(player: ServerPlayer): Int {
        var n = bodies.values.count { it.owner == player.uuid && it.state != State.LEAVING && it.p.isAlive && it.p.level() === player.level() && it.p.distanceTo(player) <= NEAR }
        val companion = Companions.traveller(player)
        if (companion != null && companion.isAlive && companion.level() === player.level() && companion.distanceTo(player) <= NEAR) n++
        return n
    }

    /** The Hunt's leader called from [from]: [player]'s guards raise a hand, say so, and step between them and the party. */
    fun hold(player: ServerPlayer, from: Vec3) {
        val now = player.level().gameTime
        var spoke = false
        for (g in guardsOf(player.uuid)) {
            if (g.state == State.LEAVING) continue
            val first = g.state != State.HOLD
            if (g.p.seat != null) g.p.standUp()
            g.sat = null
            g.holdFrom = from
            g.to(State.HOLD, now)
            if (!first) continue
            g.stride.halt()
            g.p.face(g.p.yawTo(from))
            g.p.act(Activity.HALT)
            g.haltUntil = now + Activity.HALT.ticks
            if (!spoke) spoke = g.p.say("trade_guard_stay_${g.p.random.nextInt(2)}", force = true, hearing = HuntVoice.CRY.toDouble())
        }
    }

    /** Whether [entity] is a guard hired by [owner]. */
    fun isGuardOf(entity: Entity, owner: UUID): Boolean = entity is Townsperson && bodies.values.any { it.p === entity && it.owner == owner }

    /** The Hunt is over (or stood down): [owner]'s guards let go of whoever they fought and fall in behind again. */
    fun calm(owner: UUID) {
        for (g in guardsOf(owner)) {
            val now = g.p.level().gameTime
            g.p.target = null
            g.p.isAggressive = false
            if (g.state == State.HOLD) g.to(State.FOLLOW, now)
            g.holdFrom = null
        }
    }

    /** Whether one of [owner]'s guards stands within [range] of [at]. */
    fun guardNear(owner: UUID, at: Vec3, range: Double): Boolean =
        guardsOf(owner).any { it.state != State.LEAVING && it.p.isAlive && it.p.distanceToSqr(at) <= range * range }

    /** A townsperson died: if it was a guard, its contract ends there and the trader is told. */
    fun died(p: Townsperson) {
        val g = bodies.values.firstOrNull { it.p === p } ?: return
        bodies.remove(g.id)
        counted.remove(g.id)
        val level = p.level() as? ServerLevel ?: return
        val books = TradeBooks.get(level)
        books[g.owner]?.guards?.removeIf { it.getLongOr("id", 0L) == g.id }
        books.changed()
        level.server.playerList.getPlayer(g.owner)?.sendSystemMessage(Component.translatable("message.guildmark.trade.guard.fallen", p.customName ?: p.name))
    }

    /** A guard's body loaded from disk (saved with a chunk) goes again: the contract makes a new one where its trader is. */
    fun loaded(p: Townsperson, event: EntityJoinLevelEvent) {
        if (p.entityTags().contains(TAG)) event.isCanceled = true
    }

    /** Every guard body goes (the server is stopping); the contracts stay in the books. */
    fun discardAll() {
        for (g in bodies.values.toList()) vanish(g, puff = false)
        bodies.clear()
    }

    fun forget() {
        bodies.clear()
        paces.clear()
        counted.clear()
        besideNext.clear()
        serial = 0L
    }

    // ---- What the director asks ----

    /** The guard [p] is, or null for a body no contract has (it is sent off). */
    fun guardOf(p: Townsperson): Guard? = bodies.values.firstOrNull { it.p === p }

    /** How [owner] is moving, as the guards keep step with them. */
    fun paceOf(owner: UUID): Pace? = paces[owner]

    /** [g]'s days are up, or they were sent off, and they have walked out of everyone's way: gone. */
    fun gone(g: Guard) {
        bodies.remove(g.id)
        vanish(g, puff = false)
    }

    private fun guardsOf(owner: UUID): List<Guard> = bodies.values.filter { it.owner == owner }

    // ---- The hire ----

    /** A hire's price: the wage for [days] days, rounded up to a whole Mark (3, 5 and 8 at the design's 2.5). */
    fun price(days: Int): Int = ceil(TradeConfig.guardWage() * days - 1e-9).toInt().coerceAtLeast(0)

    /** The company [player] has now, as the ladder's limit counts it: their contracts and a travelling companion. */
    fun company(player: ServerPlayer): Int {
        val contracts = TradeBooks.peek(player.level())?.get(player.uuid)?.guards?.size ?: 0
        return contracts + if (Companions.traveller(player) != null) 1 else 0
    }

    /** What stands between [player] and a hire: the Hunt switched off, a rung too low, or as much company as the rung allows. */
    private fun gate(player: ServerPlayer): String? {
        if (!TradeConfig.hunt()) return GATE_OFF
        val limit = Rungs.guardLimit(player)
        return when {
            limit <= 0 -> GATE_RUNG
            company(player) >= limit -> GATE_FULL
            else -> null
        }
    }

    /**
     * [player] hires a guard for [days] days: the contract is written in their book (the Marks were taken by the counter, or nothing by the
     * command), and the body is made by the counter's scene or, failing that, by the next second's look. Gives the contract's id, or null if
     * the Hunt is switched off or they are not in the overworld.
     */
    fun hire(player: ServerPlayer, days: Int): Long? {
        val level = player.level() as? ServerLevel ?: return null
        if (!TradeConfig.hunt() || level.dimension() != Level.OVERWORLD) return null
        val books = TradeBooks.get(level)
        val book = books.of(player.uuid)
        val id = level.gameTime * 4L + (serial++ and 3L)
        val seed = level.random.nextLong()
        val tag = CompoundTag()
        tag.putLong("id", id)
        tag.putLong("seed", seed)
        tag.putInt("look", Math.floorMod(seed, TownRole.GUARD.common.toLong()).toInt())
        tag.putInt("days", days.coerceIn(1, MOST_DAYS))
        tag.putLong("left", days.coerceIn(1, MOST_DAYS) * DAY)
        tag.putLong("hired", level.overworldClockTime)
        book.guards.add(tag)
        books.changed()
        counted[id] = level.overworldClockTime
        return id
    }

    /**
     * The counter's scene: the merchant points the way out, and the hall's guard comes round from the yard, out of the trader's sight if
     * there is such a place with a way from it, else from out of sight behind them.
     */
    private fun scene(player: ServerPlayer, merchant: Townsperson) {
        (merchant.director as? Serving)?.gesture(merchant, Activity.POINT, null)
        val level = player.level() as? ServerLevel ?: return
        val tag = TradeBooks.peek(level)?.get(player.uuid)?.guards?.lastOrNull() ?: return
        val id = tag.getLongOr("id", 0L)
        if (bodies.containsKey(id)) return
        val yard = yardFor(level, merchant, player)
        if (yard != null) make(level, player, tag, yard, State.COMING)
    }

    /** A place 9 to 18 blocks from [merchant] in the open with a way to the counter, out of [player]'s sight if any such is found; null if none has a way. */
    private fun yardFor(level: ServerLevel, merchant: Townsperson, player: ServerPlayer): BlockPos? {
        var best: BlockPos? = null
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(8) {
            val pos = Places.street(level, merchant.blockPosition(), 18, level.random, 9, 4) ?: return@repeat
            val path = merchant.navigation.createPath(pos, 1)
            if (path == null || !path.canReach()) return@repeat
            val score = (if (seen(player, pos)) 0.0 else 100.0) - abs(Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(pos))) - 14.0)
            if (score > bestScore) {
                bestScore = score
                best = pos
            }
        }
        return best
    }

    /** [player]'s guards are dismissed: the contracts end now, and each guard says so and walks off. Gives how many there were. */
    fun dismiss(player: ServerPlayer): Int {
        val level = player.level() as? ServerLevel ?: return 0
        val book = TradeBooks.peek(level)?.get(player.uuid) ?: return 0
        val n = book.guards.size
        for (tag in book.guards.toList()) finish(level, player, tag, dismissed = true)
        return n
    }

    /** The guards [player] has hired, for the command: each one's name (if walking), days hired, hours left and what they are doing. */
    fun report(player: ServerPlayer): List<String> {
        val book = TradeBooks.peek(player.level())?.get(player.uuid) ?: return listOf()
        return book.guards.map { tag ->
            val id = tag.getLongOr("id", 0L)
            val g = bodies[id]
            val name = g?.p?.customName?.string ?: "(no body just now)"
            val left = tag.getLongOr("left", 0L)
            val doing = when {
                g == null -> if (TradeConfig.hunt()) "to be made beside you" else "waiting: the Hunt is switched off"
                else -> "${g.state.name.lowercase()}, ${"%.0f".format(g.p.distanceTo(player).toDouble())} blocks off"
            }
            "$name: ${tag.getIntOr("days", 1)} day(s) hired, ${left / 1000} h ${(left % 1000) * 60 / 1000} min left, $doing"
        }
    }

    // ---- The tick ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val now = level.gameTime
        if (bodies.isNotEmpty()) {
            for (owner in bodies.values.map { it.owner }.toSet()) server.playerList.getPlayer(owner)?.let { pace(it, now) }
            for (g in bodies.values.toList()) watch(level, g, now)
        }
        if (now % SECOND == 0L) contracts(level, now)
    }

    /** How [player] moves, kept up to date each tick while they have a guard walking. */
    private fun pace(player: ServerPlayer, now: Long) {
        val pace = paces.getOrPut(player.uuid) { Pace(player.x, player.z).also { it.stillSince = now } }
        val dx = player.x - pace.x
        val dz = player.z - pace.z
        val step = hypot(dx, dz)
        // A jump of many blocks is not a walk (a teleport, a respawn)
        val v = if (step > 4.0) 0.0 else step * SECOND
        pace.speed += (v - pace.speed) * 0.25
        if (step > 0.02 && step <= 4.0) {
            pace.hx += (dx / step - pace.hx) * 0.3
            pace.hz += (dz / step - pace.hz) * 0.3
            val len = hypot(pace.hx, pace.hz)
            if (len > 1e-3) {
                pace.hx /= len
                pace.hz /= len
            }
        }
        if (pace.speed > 0.4) pace.stillSince = now
        pace.x = player.x
        pace.z = player.z
        pace.tick = now
    }

    /**
     * Each tick, for each body: who it fights (in a fight of its trader's Hunt, the nearest of the party; nobody otherwise), and every half second
     * whether it should go (its trader gone from the game or the world, the body far off or lost, the switch turned off).
     */
    private fun watch(level: ServerLevel, g: Guard, now: Long) {
        val p = g.p
        if (p.isRemoved || !p.isAlive) {
            bodies.remove(g.id)
            return
        }
        val owner = level.server.playerList.getPlayer(g.owner)
        if (owner == null || !owner.isAlive || owner.level() !== p.level() || !TradeConfig.hunt()) {
            bodies.remove(g.id)
            vanish(g, puff = false)
            return
        }
        if (g.state != State.LEAVING) {
            val foe = if (Hunt.stance(g.owner) == Hunt.Stance.FIGHT) Hunt.foeFor(g.owner, p.position(), NEAR) else null
            if (p.target !== foe) {
                p.target = foe
                p.isAggressive = foe != null
                if (foe != null && p.seat != null) p.standUp()
            }
        }
        if (now % 10L != 0L) return
        val d = p.distanceTo(owner).toDouble()
        when (g.state) {
            State.LEAVING -> {
                val unseen = level.players().none { it.distanceToSqr(p) <= 64.0 * 64.0 && it.hasLineOfSight(p) }
                if ((unseen && d > 8.0) || now - g.since > LEAVING_MOST || !level.isLoaded(p.blockPosition())) gone(g)
            }
            State.COMING -> if (now - g.since > COMING_MOST || !level.isLoaded(p.blockPosition())) {
                // Could not come up in a minute: the next one is made at the trader's side
                besideNext.add(g.id)
                bodies.remove(g.id)
                vanish(g, puff = !unseenBy(owner, p))
            }
            else -> if (d > LOST || (d > LOST_SEEN && !owner.hasLineOfSight(p)) || !level.isLoaded(p.blockPosition())) {
                // Left a long way behind (a horse, a boat, a fall): they catch up by being made again behind the trader
                bodies.remove(g.id)
                vanish(g, puff = false)
            }
        }
    }

    /**
     * Once a second: each contract of each trader in the game counts down by the clock, ends when its days are up, and has its body made where
     * it has none and the trader is on their feet in the overworld. Nothing happens while the Hunt is switched off; the days wait.
     */
    private fun contracts(level: ServerLevel, now: Long) {
        if (!TradeConfig.hunt()) {
            if (bodies.isNotEmpty()) discardAll()
            counted.clear()
            return
        }
        val books = TradeBooks.peek(level) ?: return
        val clock = level.overworldClockTime
        var dirty = false
        for (player in level.server.playerList.players) {
            val book = books[player.uuid] ?: continue
            if (book.guards.isEmpty()) continue
            for (tag in book.guards.toList()) {
                val id = tag.getLongOr("id", 0L)
                val last = counted[id]
                counted[id] = clock
                var left = tag.getLongOr("left", 0L)
                if (last != null) {
                    left -= (clock - last).coerceIn(0L, DAY)
                    tag.putLong("left", left)
                    dirty = true
                }
                if (left <= 0L) {
                    finish(level, player, tag, dismissed = false)
                    dirty = true
                    continue
                }
                if (!bodies.containsKey(id) && canServe(level, player)) remake(level, player, tag)
            }
        }
        if (dirty) books.changed()
        // Contracts no longer in any book (dismissed, ended) are not counted any more
        if (counted.size > 64) counted.keys.retainAll(level.server.playerList.players.flatMap { p -> books[p.uuid]?.guards?.map { it.getLongOr("id", 0L) } ?: listOf() }.toSet())
    }

    /** Whether a body can be made for [player] now: on their feet in the overworld, a few seconds into the game, not riding or flying. */
    private fun canServe(level: ServerLevel, player: ServerPlayer): Boolean =
        player.level() === level && player.isAlive && !player.isSpectator && player.tickCount > 60 && !player.isPassenger && !player.isFallFlying &&
            !player.isSleeping && player.onGround() && !player.isInWater

    /** A contract's days are up, or it was [dismissed]: out of the book, and its guard (if walking) says so and walks off. */
    private fun finish(level: ServerLevel, player: ServerPlayer, tag: CompoundTag, dismissed: Boolean) {
        val id = tag.getLongOr("id", 0L)
        val books = TradeBooks.get(level)
        books.of(player.uuid).guards.removeIf { it.getLongOr("id", 0L) == id }
        books.changed()
        counted.remove(id)
        besideNext.remove(id)
        val g = bodies[id]
        if (g == null) {
            player.sendSystemMessage(Component.translatable(if (dismissed) "message.guildmark.trade.guard.dismissed_away" else "message.guildmark.trade.guard.ended_away"))
            return
        }
        val now = level.gameTime
        if (g.p.seat != null) g.p.standUp()
        g.p.target = null
        g.to(State.LEAVING, now)
        g.stride.halt()
        g.p.lookAt(player)
        g.p.act(Activity.SALUTE)
        g.p.say("trade_guard_end_${g.p.random.nextInt(2)}", force = true)
        player.sendSystemMessage(Component.translatable(if (dismissed) "message.guildmark.trade.guard.dismissed" else "message.guildmark.trade.guard.ended", g.p.customName ?: g.p.name))
    }

    // ---- Bodies ----

    /** A body for the contract [tag], coming up from out of sight behind [player] if there is such a place, else standing at their side. */
    private fun remake(level: ServerLevel, player: ServerPlayer, tag: CompoundTag) {
        val id = tag.getLongOr("id", 0L)
        val behind = if (id in besideNext) null else behind(level, player)
        if (behind != null) {
            make(level, player, tag, behind, State.COMING)
            return
        }
        val side = side(level, player) ?: return
        besideNext.remove(id)
        make(level, player, tag, side, State.FOLLOW)
    }

    /**
     * Makes the guard of the contract [tag] at [at], facing [player], in [state]. They are a guard of the townsfolk's looks with a name of
     * their own, never adopted by a town (they are named), run by [GuardDirector], who fight only whom the Hunt gives them and drop nothing.
     */
    private fun make(level: ServerLevel, player: ServerPlayer, tag: CompoundTag, at: BlockPos, state: State): Guard? {
        val id = tag.getLongOr("id", 0L)
        val seed = tag.getLongOr("seed", id)
        val look = tag.getIntOr("look", 0).coerceIn(0, TownRole.GUARD.looks - 1)
        val toward = Vec3.atBottomCenterOf(at)
        val yaw = (Math.toDegrees(Math.atan2(player.z - toward.z, player.x - toward.x))).toFloat() - 90f
        val p = Townsfolk.create(level, at, TownRole.GUARD, look, yaw, EntitySpawnReason.EVENT) ?: return null
        val woman = Names.woman(TownRole.GUARD, p.look, seed)
        p.customName = Component.literal(Names.given(null, woman, seed) + " " + Names.family(null, seed / 3))
        p.isCustomNameVisible = false
        p.addTag(TAG)
        p.home = at
        // No running, and no going after anything on their own: the Hunt says whom they fight
        p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
        p.targetSelector.removeAllGoals { true }
        for (slot in EquipmentSlot.entries) p.setDropChance(slot, 0f)
        p.director = GuardDirector
        if (!level.addFreshEntity(p)) return null
        val now = level.gameTime
        val slot = (0..2).firstOrNull { s -> bodies.values.none { it.owner == player.uuid && it.slot == s } } ?: bodies.values.count { it.owner == player.uuid }
        val g = Guard(player.uuid, id, p, slot, state, now)
        bodies[id] = g
        // Made at the side, they were walking with the trader all along: no greeting, as it was given when they came
        if (state == State.FOLLOW) tag.putInt("greeted", 1)
        return g
    }

    /** Whether the guard of the contract [g] has greeted its trader yet (once a contract), and marks it done. */
    fun greet(g: Guard): Boolean {
        val level = g.p.level() as? ServerLevel ?: return false
        val tag = TradeBooks.peek(level)?.get(g.owner)?.guards?.firstOrNull { it.getLongOr("id", 0L) == g.id } ?: return false
        if (tag.getIntOr("greeted", 0) != 0) return false
        tag.putInt("greeted", 1)
        TradeBooks.get(level).changed()
        return true
    }

    /** A spot 10 to 16 blocks behind [player] on open ground they cannot see, or null. */
    private fun behind(level: ServerLevel, player: ServerPlayer): BlockPos? {
        val back = Math.toRadians(player.yRot.toDouble() + 180.0)
        repeat(10) {
            val a = back + (level.random.nextDouble() - 0.5) * Math.toRadians(140.0)
            val d = 10.0 + level.random.nextDouble() * 6.0
            val pos = ground(level, player.x - sin(a) * d, player.y, player.z + cos(a) * d) ?: return@repeat
            if (!seen(player, pos)) return pos
        }
        return null
    }

    /** A spot a step or two to [player]'s side or behind them, or null if there is none. */
    private fun side(level: ServerLevel, player: ServerPlayer): BlockPos? {
        val yaw = Math.toRadians(player.yRot.toDouble())
        for (turn in listOf(150.0, -150.0, 90.0, -90.0, 180.0, 120.0, -120.0)) {
            val a = yaw + Math.toRadians(turn)
            val pos = ground(level, player.x - sin(a) * 2.2, player.y, player.z + cos(a) * 2.2) ?: continue
            return pos
        }
        return null
    }

    /** Where a body can stand at ([x], [z]), near the height [y]: the feet's block, or null. */
    private fun ground(level: ServerLevel, x: Double, y: Double, z: Double): BlockPos? {
        val base = BlockPos.containing(x, y, z)
        for (dy in intArrayOf(0, 1, -1, 2, -2, 3, -3, -4)) {
            val pos = base.offset(0, dy, 0)
            if (Places.standable(level, pos) && level.getBlockState(pos.below()).fluidState.isEmpty) return pos
        }
        return null
    }

    /** Whether [player] could see a body standing at [pos]. */
    private fun seen(player: ServerPlayer, pos: BlockPos): Boolean {
        val at = Vec3.atCenterOf(pos).add(0.0, 0.6, 0.0)
        return player.level().clip(ClipContext(player.eyePosition, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS
    }

    private fun unseenBy(player: ServerPlayer, p: Townsperson): Boolean = p.distanceTo(player) > 64f || !player.hasLineOfSight(p)

    /** Takes [g]'s body out of the world, in a puff if someone may be watching. */
    private fun vanish(g: Guard, puff: Boolean) {
        val p = g.p
        if (p.isRemoved) return
        if (p.seat != null) p.standUp()
        if (puff) (p.level() as? ServerLevel)?.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.6, p.z, 6, 0.3, 0.4, 0.3, 0.02)
        p.director = null
        p.discard()
    }

    /** [owner] left the game: their guards' bodies go, and are made again when they are back. */
    private fun sendOff(owner: UUID) {
        for (g in guardsOf(owner)) {
            bodies.remove(g.id)
            vanish(g, puff = false)
        }
        paces.remove(owner)
    }

    // ---- Events ----

    /**
     * A guard comes to no harm but in a fight of their trader's Hunt, at the hands of its party (whose blows the Hunt allows only once a blow
     * made it a fight): no monster, no player and no fall hurts them on the road. Only what passes every protection (the void, `/kill`) does.
     */
    private fun hurt(event: LivingIncomingDamageEvent) {
        val p = event.entity as? Townsperson ?: return
        if (p.level().isClientSide || !p.entityTags().contains(TAG)) return
        if (event.source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return
        val g = bodies.values.firstOrNull { it.p === p }
        val attacker = event.source.entity
        if (g != null && attacker != null && Hunt.huntedBy(attacker) == g.owner) return
        event.isCanceled = true
    }

    /** A guard drops nothing. */
    private fun drops(event: LivingDropsEvent) {
        val p = event.entity as? Townsperson ?: return
        if (p.entityTags().contains(TAG)) event.drops.clear()
    }
}
