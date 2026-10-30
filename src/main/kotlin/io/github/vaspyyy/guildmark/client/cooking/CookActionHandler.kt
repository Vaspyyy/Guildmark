package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_BURNT
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_COOKING
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_DONE
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_EMPTY
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_FILLING
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_READY
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_SERVED
import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActionPayload
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.dish.DishEating
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.vessel.Actions
import io.github.vaspyyy.guildmark.cooking.vessel.Call
import io.github.vaspyyy.guildmark.cooking.vessel.CookTool
import io.github.vaspyyy.guildmark.cooking.vessel.CookToolItem
import io.github.vaspyyy.guildmark.cooking.vessel.Prep
import io.github.vaspyyy.guildmark.cooking.vessel.Region
import io.github.vaspyyy.guildmark.cooking.vessel.StationBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import io.github.vaspyyy.guildmark.cooking.vessel.StationRegions
import io.github.vaspyyy.guildmark.cooking.vessel.StationView
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlock
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotStyle
import io.github.vaspyyy.guildmark.cooking.vessel.Vessel
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import kotlin.math.abs

/**
 * What each cook in sight is doing, on this client (living-world/cook/design.md 1.8, 3.3, 4.3): the clip each player plays
 * now, the one it eases out of, and the same action again when it is asked for before the last has ended. [CookPose] draws
 * it on the body and [CookHands] in first person; nothing here draws.
 *
 * Three things start a clip. A [CookActionPayload] from the server, for anyone. The local player's own click on a station,
 * at once, ahead of the server: [onClick] works out from what the client can see (the station's view, the region clicked,
 * the hand, the sneak) the action the server will come to, and the server's payload only confirms it; a payload with a
 * different action means the guess was wrong and its clip takes over, and a guess the server never confirms eases away.
 * And eating, from the use state every client already has: whoever is using a dish plays its clip, timed by the use.
 *
 * A clip asked for again while it plays (a held click stirs again and again) waits for the one playing to near its end and
 * follows on from it; a different action takes over at once, easing in over the one cut short. A station clip eases away
 * when its cook walks off, the shiver as soon as they stir. All times are this client's own ticks, counted only while a
 * world is open and the game runs, plus the frame's partial tick.
 */
object CookActionHandler {
    /** The fewest ticks a clip takes to come in, whatever its own blend says: [MIN_IN] from rest, [CUT_IN] over another clip it cuts short. */
    private const val MIN_IN = 2f
    private const val CUT_IN = 4f

    /** Ticks a clip cut short (a guess the server refused, a cook who walked off) takes to ease back to the pose under it. */
    const val FADE = 5f

    /** Ticks the server has to confirm a guessed clip before it eases away. */
    private const val CONFIRM = 15L

    /** Ticks after the local player last ate within which the server's word on their meal comes late and is let go. */
    private const val LATE = 40L

    /** The shiver: how long it takes to come and to go, and how long one of the server's beats (every 40 ticks) keeps it. */
    private const val LOOP_IN = 10f
    private const val LOOP_OUT = 6f
    private const val LOOP_HOLD = 50f

    /** Blocks a tick: a station clip ends when its cook walks faster than a sneak; the shiver at any step or fall. */
    private const val WALK = 0.1
    private const val STILL = 0.01
    private const val DROP = 0.05

    /** How long the local taste, chop and tip-out rules last (Part B's: Calls.TASTE_TICKS, PrepBoardBlockEntity.CHOP_TICKS, TIP_CONFIRM_TICKS). */
    private const val TASTE_TICKS = 120L
    private const val CHOP_TICKS = 6L
    private const val TIP_TICKS = 80L

    /** A click the local player made, and the action it was guessed to be, at client tick [at]. */
    class Guess(val action: CookAction, val at: Long)

    /**
     * One clip on one body: [action]'s [clip], begun at client tick [start] (a float, to the partial tick), at [pos], the body
     * turned to [target] (null: it plays where it stands). [held] is what the hand held as an ADD or a FEED began, shown until
     * the clip lets it go; [hand] the hand that does the work (the off hand only when a dish is eaten from it).
     */
    class Play(val action: CookAction, val clip: Clip, var start: Float, val pos: BlockPos, val target: Vec3?, val held: ItemStack, val hand: InteractionHand) {
        /** The guess this clip was started for, until the server confirms it. */
        var guess: Guess? = null

        /** Set when it began to ease away (a refused guess, a walk, the end of the meal): the tick, and its time then, where it stays. */
        var fadeAt = Float.NaN
        var frozen = 0f

        /** The shiver loops until this tick unless another beat keeps it. */
        var until = 0f

        /** Timed by the use state (a dish being eaten), which [start] follows. */
        var eating = false

        /**
         * Ticks of the clip a tick plays: 1, but for a meal that takes longer than its clip (Gruel), which plays it slower to the
         * use's length (the eating sets it; [start] stays the tick the use began, so nothing drifts and nothing is re-synced).
         */
        var rate = 1f

        /** The fewest ticks it takes to come in: longer over another clip, so the one cut short hands over gently. */
        var ease = MIN_IN

        val loop: Boolean get() = action == CookAction.SHIVER
        val fading: Boolean get() = !fadeAt.isNaN()

        /** Ticks into the clip at [now]: where it stopped once it eases away, round and round for the shiver. */
        fun time(now: Float): Float = when {
            fading -> frozen
            loop -> (now - start).mod(clip.length)
            else -> ((now - start) * rate).coerceIn(0f, clip.length)
        }

        /** The tick at which it is [into] ticks into the clip. */
        fun at(into: Float): Float = start + into / rate

        /** How strongly it shows at [now]: in over at least [ease] ticks, out by the clip's own blend, and away by [FADE]. */
        fun weight(now: Float): Float {
            val base = if (loop) {
                smooth((now - start) / LOOP_IN) * smooth((until - now) / LOOP_OUT)
            } else {
                val t = time(now)
                clip.weight(t) * smooth(t / ease)
            }
            return if (fading) base * (1f - smooth((now - fadeAt) / FADE)) else base
        }

        /** Whether it is over and shows nothing more: once it eases away, only when that is done (it may have been near its end). */
        fun over(now: Float): Boolean = when {
            fading -> now - fadeAt >= FADE
            loop -> now >= until
            else -> (now - start) * rate >= clip.length
        }

        /** Begins easing away at [now], held where it is. */
        fun fade(now: Float) {
            if (fading) return
            frozen = time(now)
            fadeAt = now
        }
    }

    /** What a combat move [by] cut short: [action]'s clip held where it stopped, for the move to ease out of, and the station the body was turned to. */
    class Cut(val action: CookAction, val clip: Clip, val time: Float, val weight: Float, val mirror: Boolean, val target: Vec3?, val by: CombatClient.Action?)

    /** A cook's body: the clip it plays, the one it eases out of, the next of the same, and what a combat move cut short. */
    class Body {
        var play: Play? = null
        var from: Play? = null
        var next: Play? = null
        var cut: Cut? = null

        fun idle(): Boolean = play == null && from == null && next == null && cut == null
    }

    private val bodies = HashMap<Int, Body>()
    private val guesses = ArrayDeque<Guess>()

    /** Station by station (block positions), when the local player last tasted, chopped and was asked to tip out; for the guesses. */
    private val tasted = HashMap<Long, Long>()
    private val chopped = HashMap<Long, Long>()
    private val tipAsked = HashMap<Long, Long>()

    /** This client's ticks, counted while a world is open and not paused; [ticking] between a tick's start and end. */
    var ticks = 0L
        private set
    private var ticking = false

    /** The tick the local player was last seen eating a dish: the server's word on that meal is not needed. */
    private var ateAt = Long.MIN_VALUE / 2
    private var level: ClientLevel? = null

    /** The clock at [partial] into the frame. */
    fun now(partial: Float): Float = ticks + partial

    /**
     * The tick a clip begun now starts at: a payload is read before the tick that follows is counted, a click during it, and
     * either way the first frame drawn after it is the clip's first.
     */
    private fun stamp(): Float = if (ticking) ticks.toFloat() else (ticks + 1).toFloat()

    /** [id]'s body, if it is doing anything. */
    fun body(id: Int): Body? = bodies[id]

    /** The clip of [action] under `animations/player/`, or null while M3's clips are missing. */
    fun clip(action: CookAction): Clip? = Clips.get("player/" + CookActions.clip(action))

    fun register() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre::class.java) { _ -> tickStart() }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tickEnd() }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PlayerInteractEvent.RightClickBlock::class.java) { onClick(it) }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
    }

    /** Forgets every body (a log-out, another world, Hearthcraft switched off). */
    fun reset() {
        bodies.clear()
        guesses.clear()
        tasted.clear()
        chopped.clear()
        tipAsked.clear()
        level = null
    }

    // ---- The clock, and what the tick ends ----

    private fun tickStart() {
        val mc = Minecraft.getInstance()
        val now = mc.level
        if (now !== level) {
            reset()
            level = now
        }
        if (now == null || mc.isPaused) return
        ticks++
        ticking = true
    }

    private fun tickEnd() {
        if (!ticking) return
        ticking = false
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (!Cooking.enabled()) {
            if (bodies.isNotEmpty()) reset()
            this.level = level
            return
        }
        val now = ticks.toFloat()
        // A guess the server has not confirmed in time was wrong: its clip eases away, and a follow-on it asked for is dropped
        while (guesses.isNotEmpty() && guesses.first().at < ticks - CONFIRM) refuse(guesses.removeFirst(), now)
        eating(level, now)
        if (bodies.isEmpty()) return
        val gone = ArrayList<Int>(0)
        for ((id, body) in bodies) {
            val entity = level.getEntity(id) as? Player
            if (entity == null || entity.isRemoved) {
                gone.add(id)
                continue
            }
            settle(entity, body, now)
            if (body.idle()) gone.add(id)
        }
        for (id in gone) bodies.remove(id)
    }

    /** What changes with the tick on one body: what has ended goes, a follow-on begins, a walk or a combat move ends the clip. */
    private fun settle(entity: Player, body: Body, now: Float) {
        val combat = if (CombatClient.active()) CombatClient.actionOf(entity) else null
        // A combat move has the body: the clip stops where it is, kept only for the move to ease out of (CookPose)
        if (combat != null) cut(entity, now, combat) else body.cut = null
        val next = body.next
        if (next != null && now >= next.start) {
            body.next = null
            next.start = now
            begin(body, next, now)
        }
        val play = body.play
        if (play != null) {
            if (play.over(now)) {
                // What it was easing in over (a refused guess over the clip before it) carries on as it was
                body.play = body.from?.takeIf { !it.over(now) }
                body.from = null
            } else if (!play.fading && moved(entity, play)) {
                play.fade(now)
            }
        }
        val from = body.from
        val current = body.play
        if (from != null && (from.over(now) || current == null || current.weight(now) >= 0.999f)) body.from = null
    }

    /** Whether [entity] has done something that ends [play]: walked off from a station clip, or stirred from the shiver. */
    private fun moved(entity: Player, play: Play): Boolean {
        if (entity.isSleeping || entity.isDeadOrDying || entity.isSpectator) return true
        val dx = entity.x - entity.xo
        val dz = entity.z - entity.zo
        val step = dx * dx + dz * dz
        return when {
            play.loop -> step > STILL * STILL || abs(entity.y - entity.yo) > DROP || entity.isUsingItem || entity.isPassenger ||
                entity.isSwimming || entity.isFallFlying || entity.isInWater || entity.abilities.flying
            play.eating || isEating(play.action) -> false
            else -> step > WALK * WALK || entity.isFallFlying || entity.isSwimming
        }
    }

    // ---- Starting a clip ----

    /**
     * [play] takes over [body] at [now]: whatever showed eases out under it (the more visible of the two, if one was easing
     * already), and a follow-on asked for the clip it replaces is dropped.
     */
    private fun begin(body: Body, play: Play, now: Float) {
        val current = body.play
        val from = body.from
        val showing = current?.takeIf { !it.over(now) }?.weight(now) ?: 0f
        val under = from?.takeIf { !it.over(now) }?.let { it.weight(now) * (1f - showing) } ?: 0f
        val shown = when {
            showing > 0.001f && showing >= under -> current
            under > 0.001f -> from
            else -> null
        }
        // The same clip again joins its last at rest; another takes longer to come in over the one it cuts
        if (shown != null && shown.action != play.action) play.ease = CUT_IN
        body.from = shown
        body.play = play
        body.next = null
        body.cut = null
    }

    /**
     * [entity] does [action] at [pos]: as a new clip, or, when the same clip is already playing, as the one that follows it
     * as it nears its end (once: a held click asked for many). The shiver's beat keeps the shiver going.
     */
    private fun arrive(entity: Player, action: CookAction, pos: BlockPos, guess: Guess?): Play? {
        val clip = clip(action) ?: return null
        val body = bodies.getOrPut(entity.id) { Body() }
        val now = stamp()
        val current = body.play?.takeIf { !it.over(now) && !it.fading && it.action == action }
        if (current != null) {
            if (current.loop) {
                current.until = maxOf(current.until, now + LOOP_HOLD)
                return current
            }
            val joinAt = current.at(clip.length - clip.blendOut)
            if (now < joinAt) {
                if (body.next == null) body.next = make(entity, action, clip, joinAt, pos).also { it.guess = guess }
                return body.next
            }
        }
        val play = make(entity, action, clip, now, pos)
        play.guess = guess
        if (play.loop) play.until = now + LOOP_HOLD
        begin(body, play, now)
        return play
    }

    private fun make(entity: Player, action: CookAction, clip: Clip, start: Float, pos: BlockPos): Play {
        val held = if (action == CookAction.ADD || action == CookAction.FEED) entity.mainHandItem.copy() else ItemStack.EMPTY
        val target = if (pos == entity.blockPosition()) null else target(entity, pos, action)
        return Play(action, clip, start, pos, target, held, InteractionHand.MAIN_HAND)
    }

    // ---- What the server says ----

    /** A [CookActionPayload] (the main thread): the cook's clip, or, for the local player, the answer to their guess. */
    fun onPayload(payload: CookActionPayload) {
        if (!Cooking.enabled()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val entity = level.getEntity(payload.player) as? Player ?: return
        val action = payload.action
        val body = bodies[entity.id]
        if (action == CookAction.EAT_PLATE) {
            atPlate(entity, body, payload.pos)
            return
        }
        // A meal the use state already times needs nothing more
        if (isEating(action) && body?.play?.let { it.action == action && it.eating && !it.fading } == true) return
        // The local player's own meal is timed by their own use, which began on the click; a late word changes nothing
        if (entity === mc.player && isEating(action) && ticks - ateAt < LATE) return
        if (entity === mc.player) {
            val i = guesses.indexOfFirst { it.action == action }
            if (i >= 0) {
                // The guess was right; any older one the server passed over was not
                val now = stamp()
                repeat(i) { refuse(guesses.removeFirst(), now) }
                val guess = guesses.removeFirst()
                if (body != null) {
                    if (body.play?.guess === guess) body.play?.guess = null
                    if (body.next?.guess === guess) body.next?.guess = null
                }
                return
            }
        }
        arrive(entity, action, payload.pos, null)
    }

    /**
     * A meal at a Plate ([CookAction.EAT_PLATE]): the server's word may come before the use state reaches this client or
     * after it. Before, the plate's clip starts now and [eating] times it once the use arrives; after, the bite the use
     * began is turned into the plate's clip at the same point of the meal (it eases in over the bite).
     */
    private fun atPlate(entity: Player, body: Body?, pos: BlockPos) {
        val now = stamp()
        val play = body?.play?.takeIf { it.eating && !it.fading && !it.over(now) }
        if (body == null || play == null) {
            arrive(entity, CookAction.EAT_PLATE, pos, null)
            return
        }
        if (play.action != CookAction.EAT_BITE) return
        val clip = clip(CookAction.EAT_PLATE) ?: return
        val plate = Play(CookAction.EAT_PLATE, clip, play.start, pos, null, ItemStack.EMPTY, play.hand)
        plate.rate = play.rate
        plate.eating = true
        begin(body, plate, now)
    }

    /** The server never confirmed [guess]: the clip it started eases away, and the follow-on it asked for is dropped. */
    private fun refuse(guess: Guess, now: Float) {
        val player = Minecraft.getInstance().player ?: return
        val body = bodies[player.id] ?: return
        val play = body.play
        if (play != null && play.guess === guess) play.fade(now)
        if (body.next?.guess === guess) body.next = null
    }

    // ---- Eating, from the use state ----

    /** Whoever is using a dish plays its clip, timed by the use; whoever stops before the end eases out of it. */
    private fun eating(level: ClientLevel, now: Float) {
        for (player in level.players()) {
            val body = bodies[player.id]
            val stack = if (player.isUsingItem) player.useItem else ItemStack.EMPTY
            val item = stack.item as? DishItem
            if (item == null || item.scorched) {
                val play = body?.play ?: continue
                // Stopped before the end: eased out where it was (at the end the clip has eased out by itself)
                if (play.eating && !play.fading && play.time(now) < play.clip.length - play.clip.blendOut) play.fade(now)
                continue
            }
            if (player === Minecraft.getInstance().player) ateAt = ticks
            // A bite at a Plate is the plate's clip, which the server's word started (atPlate)
            val shown = body?.play
            val action = DishEating.action(item.kind).let {
                if (it == CookAction.EAT_BITE && shown != null && shown.action == CookAction.EAT_PLATE && !shown.fading && !shown.over(now)) CookAction.EAT_PLATE else it
            }
            val clip = clip(action) ?: continue
            val duration = stack.getUseDuration(player).coerceAtLeast(1)
            // The clip over the use's whole length: as made for most dishes (their use is the clip's length), slower for a longer meal (Gruel)
            val rate = clip.length / duration
            val into = player.ticksUsingItem.toFloat() * rate
            // A use that outlasts the clip has had all of it
            if (into >= clip.length) continue
            val start = now - into / rate
            val play = body?.play
            if (play != null && play.action == action && !play.fading && !play.over(now)) {
                // Timed by the use from now on (a payload may have started it a moment before the use reached us)
                if (!play.eating || play.rate != rate || abs(play.start - start) > 2f) play.start = start
                play.rate = rate
                play.eating = true
                continue
            }
            val b = body ?: Body().also { bodies[player.id] = it }
            val fresh = Play(action, clip, start, player.blockPosition(), null, ItemStack.EMPTY, player.usedItemHand)
            fresh.rate = rate
            fresh.eating = true
            begin(b, fresh, now)
        }
    }

    // ---- The local player's click, guessed ----

    /** The local player's click on a station: their clip starts now, with what the server will most likely make of it. */
    private fun onClick(event: PlayerInteractEvent.RightClickBlock) {
        if (!event.level.isClientSide() || event.hand != InteractionHand.MAIN_HAND) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (event.entity !== player || player.isSpectator || !Cooking.enabled()) return
        val pos = event.pos
        val be = event.level.getBlockEntity(pos) as? StationBlockEntity ?: return
        // Whether the game goes on to use the block at all: the same test as its own, made right after this event
        val use = event.useBlock
        if (use.isFalse) return
        val level = event.level
        val bypass = player.mainHandItem.doesSneakBypassUse(level, pos, player) && player.offhandItem.doesSneakBypassUse(level, pos, player)
        if (!use.isTrue && player.isSecondaryUseActive && !bypass) return
        val action = guess(player, be, event.hitVec, pos) ?: return
        val guess = Guess(action, ticks)
        guesses.addLast(guess)
        arrive(player, action, pos, guess)
    }

    /** What the server will make of [player]'s click on [be] (Part B's rules, as far as the client can see them), or null for no clip. */
    private fun guess(player: Player, be: StationBlockEntity, hit: BlockHitResult, pos: BlockPos): CookAction? {
        val v = be.view()
        val held = player.mainHandItem
        val sneak = player.isShiftKeyDown
        val station = be.station ?: return board(held, v, pos)
        // The innkeeper's pot is a display
        if (v.mode == 1) return null
        val state = be.blockState
        val facing = if (state.hasProperty(StationProps.FACING)) state.getValue(StationProps.FACING) else Direction.NORTH
        val hearth = state.hasProperty(StewpotBlock.STYLE) && state.getValue(StewpotBlock.STYLE) == StewpotStyle.HEARTH
        val region = StationRegions.of(station, hearth, facing, pos, player.eyePosition, hit.location)
        val call = Call.of(v.call)
        return when {
            held.`is`(Items.FLINT_AND_STEEL) || held.`is`(Items.FIRE_CHARGE) -> if (!v.lit) CookAction.FEED else null
            held.has(DataComponents.COOKING_FUEL) && !held.isDamageableItem && !(region == Region.VESSEL && likely(held)) -> CookAction.FEED
            held.isEmpty -> bare(station, region, v, sneak, call, pos)
            else -> holding(station, region, v, sneak, call, held, pos)
        }
    }

    /** An empty hand: the heat control, the spit's crank, or, sneaking on the vessel or the fire, the tipping out. */
    private fun bare(station: Station, region: Region, v: StationView, sneak: Boolean, call: Call?, pos: BlockPos): CookAction? = when (region) {
        Region.HEAT -> {
            val answers = call == Call.BOIL_OVER || call == Call.FLARE
            if (sneak && !answers) {
                if (v.notch < 2) CookAction.EASE else null
            } else {
                if (v.notch > 0 || answers) CookAction.EASE else null
            }
        }
        Region.CRANK -> if (station != Station.SPIT) null else if (!open(v) || startable(v, station)) CookAction.TURN else null
        Region.VESSEL, Region.FIRE -> if (sneak) tip(v, pos) else null
    }

    /** A sneaking empty hand on the vessel tips it out; a dish under way asks once more first (Part B's tip_sure). */
    private fun tip(v: StationView, pos: BlockPos): CookAction? {
        if (v.state == STATE_EMPTY && v.items.isEmpty() && v.water <= 0) return null
        if (started(v) && !(v.burnt || v.state == STATE_BURNT)) {
            val key = pos.asLong()
            val asked = tipAsked[key]
            if (asked == null || ticks - asked > TIP_TICKS) {
                tipAsked[key] = ticks
                return null
            }
            tipAsked.remove(key)
        }
        return CookAction.ADD
    }

    /** Something in the hand that is neither a light nor a fuel: a tool, a bucket, or an ingredient. */
    private fun holding(station: Station, region: Region, v: StationView, sneak: Boolean, call: Call?, held: ItemStack, pos: BlockPos): CookAction? {
        if (held.`is`(Items.WATER_BUCKET)) return null
        val tool = (held.item as? CookToolItem)?.tool
        val ladle = tool == CookTool.LADLE || tool == CookTool.GILDED_LADLE
        if (ladle && sneak) return taste(v, pos)
        if (tool != null) {
            return when (station) {
                Station.STEWPOT -> if (!ladle) null else when {
                    open(v) -> if (startable(v, station)) CookAction.STIR else null
                    call == Call.STICK -> CookAction.STIR
                    call == Call.SKIM -> CookAction.SKIM
                    dish(v) -> CookAction.SERVE
                    burnt(v) -> null
                    else -> CookAction.STIR
                }
                Station.HOB -> if (tool != CookTool.TURNER) null else when {
                    open(v) -> if (startable(v, station)) CookAction.STIR else null
                    call == Call.TURN -> CookAction.FLIP
                    call == Call.STICK -> CookAction.STIR
                    dish(v) -> CookAction.SERVE_PEEL
                    burnt(v) -> null
                    else -> CookAction.STIR
                }
                Station.OVEN -> when (tool) {
                    CookTool.TURNER -> when {
                        open(v) -> null
                        call == Call.TURN -> CookAction.FLIP
                        // A pie or a tart wants the knife, which the client cannot tell: the server corrects it
                        dish(v) -> CookAction.SERVE_PEEL
                        else -> null
                    }
                    CookTool.KNIFE -> if (dish(v)) CookAction.SLICE else null
                    else -> null
                }
                Station.SPIT -> if (tool == CookTool.KNIFE && dish(v)) CookAction.CARVE else null
            }
        }
        if (region == Region.FIRE) return null
        // An ingredient goes in if it looks like one (the cook's data is the server's), or the accent a SEASON asks for
        if (call == Call.SEASON && cooking(v) && Actions.isAccent(held)) return CookAction.ADD
        if (!open(v) || v.items.size >= Vessel.capacity(station)) return null
        return if (likely(held)) CookAction.ADD else null
    }

    /** The ladle, sneaking, tastes: not a burnt vessel, not an empty one, once in six seconds a station. */
    private fun taste(v: StationView, pos: BlockPos): CookAction? {
        if (burnt(v)) return null
        if (v.items.isEmpty() && !started(v)) return null
        val key = pos.asLong()
        val last = tasted[key]
        if (last != null && ticks - last < TASTE_TICKS) return null
        tasted[key] = ticks
        return CookAction.TASTE
    }

    /** The Prep Board: the knife chops what is on it (one beat in six ticks), anything it prepares is laid on it. */
    private fun board(held: ItemStack, v: StationView, pos: BlockPos): CookAction? {
        if (Actions.isKnife(held)) {
            if (v.items.isEmpty()) return null
            val key = pos.asLong()
            val last = chopped[key]
            val since = if (last == null) Long.MAX_VALUE else ticks - last
            if (since in 0 until CHOP_TICKS) return null
            chopped[key] = if (last != null && since in 0 until 2 * CHOP_TICKS) last + CHOP_TICKS else ticks
            return CookAction.CHOP
        }
        if (held.isEmpty || !Prep.preparable(held) || Prep.prepared(held)) return null
        if (v.items.isNotEmpty() && v.items[0] != BuiltInRegistries.ITEM.getId(held.item)) return null
        return CookAction.ADD
    }

    private fun likely(held: ItemStack): Boolean = held.has(DataComponents.FOOD) || Prep.preparable(held) || Actions.isAccent(held)

    private fun open(v: StationView): Boolean = v.state == STATE_EMPTY || v.state == STATE_FILLING

    private fun started(v: StationView): Boolean = !open(v)

    private fun cooking(v: StationView): Boolean = v.state == STATE_COOKING || v.state == STATE_READY || v.state == STATE_DONE

    private fun dish(v: StationView): Boolean = v.state == STATE_READY || v.state == STATE_DONE || v.state == STATE_SERVED

    private fun burnt(v: StationView): Boolean = v.burnt || v.state == STATE_BURNT

    /** The fewest items each station starts with (the vessel's own rule, worked out once). */
    private val minItems: Map<Station, Int> by lazy { Station.entries.associateWith { Vessel.minItems(it) } }

    /** Whether a first stir or a turn of the crank would start the clock (the server checks more, and corrects the guess). */
    private fun startable(v: StationView, station: Station): Boolean =
        v.lit && (station != Station.STEWPOT || v.water > 0) && v.items.size >= (minItems[station] ?: 2)

    // ---- Where the body turns ----

    fun isEating(action: CookAction): Boolean =
        action == CookAction.EAT_SPOON || action == CookAction.EAT_BITE || action == CookAction.DRINK || action == CookAction.EAT_PLATE

    /**
     * The point of the station at [pos] that [action] works at, in the world: the vessel's middle, the firebox for a feed, the
     * heat control for an ease, the crank for a turn (the boxes of StationRegions, facing north, turned as the block is);
     * the block's middle for the Prep Board and anything that is not a station. Null for eating and the shiver, which turn to nothing.
     */
    private fun target(entity: Player, pos: BlockPos, action: CookAction): Vec3? {
        if (isEating(action) || action == CookAction.SHIVER) return null
        val level = entity.level()
        val be = level.getBlockEntity(pos) as? StationBlockEntity
        val state = level.getBlockState(pos)
        val facing = if (state.hasProperty(StationProps.FACING)) state.getValue(StationProps.FACING) else Direction.NORTH
        val station = be?.station ?: return Vec3.atCenterOf(pos)
        val region = when (action) {
            CookAction.FEED -> Region.FIRE
            CookAction.EASE -> Region.HEAT
            CookAction.TURN -> if (station == Station.SPIT) Region.CRANK else Region.VESSEL
            else -> Region.VESSEL
        }
        val p = POINTS.getValue(station)[region.ordinal]
        return CookGeo.world(pos, facing, p[0], p[1], p[2])
    }

    /** Each station's vessel, fire, heat control and crank middles, in pixels of a cell facing north (from StationRegions' boxes). */
    private val POINTS: Map<Station, Array<FloatArray>> = mapOf(
        Station.STEWPOT to arrayOf(floatArrayOf(8f, 7.25f, 8f), floatArrayOf(8f, 2f, 8f), floatArrayOf(8f, 13f, 8f), floatArrayOf(8f, 7.25f, 8f)),
        Station.HOB to arrayOf(floatArrayOf(8f, 11.75f, 8f), floatArrayOf(8f, 2.25f, 3f), floatArrayOf(8f, 7f, 2.5f), floatArrayOf(8f, 11.75f, 8f)),
        Station.OVEN to arrayOf(floatArrayOf(8f, 11.3f, 2f), floatArrayOf(8f, 2f, 3f), floatArrayOf(8f, 6f, 2f), floatArrayOf(8f, 11.3f, 2f)),
        Station.SPIT to arrayOf(floatArrayOf(7.2f, 9.1f, 8f), floatArrayOf(7.5f, 2f, 8f), floatArrayOf(13.1f, 9.45f, 8f), floatArrayOf(15.3f, 9.25f, 8f)),
    )

    // ---- Cut short by a combat move ----

    /**
     * A combat move [by] has taken [id]'s body at [now]: the cook clip stops where it is, and what showed is kept for the
     * move to ease out of (only while that move is the one playing).
     */
    fun cut(entity: Player, now: Float, by: CombatClient.Action?) {
        val body = bodies[entity.id] ?: return
        val play = body.play
        if (play != null && !play.over(now)) {
            val w = play.weight(now)
            if (w > 0.001f) body.cut = Cut(play.action, play.clip, play.time(now), w, mirror(entity, play), play.target, by)
        }
        body.play = null
        body.from = null
        body.next = null
    }

    /** Whether [play] is drawn left for right: it is worked with the left hand (a left-handed cook, or a dish eaten from the left). */
    fun mirror(entity: Player, play: Play): Boolean {
        val arm = if (play.hand == InteractionHand.MAIN_HAND) entity.mainArm else entity.mainArm.opposite
        return arm == HumanoidArm.LEFT
    }

    fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
