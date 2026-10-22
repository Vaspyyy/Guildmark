package io.github.vaspyyy.guildmark.trade.scenes

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.talk.Street
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The street's cheer for a trader (living-world/trader/design.md, Part 6; build part H): when goods a town was short of are brought in, the
 * people near the shop notice, turn, walk over and cheer, the children run to the trader's pack animal or cart, the shop's keeper bows and says
 * her word to the street, and they all go back to their day. [at] plays it; `WorldAnswers` calls it for a relief ticket, a feast ticket or a
 * consignment of 128 units or more that a player brought in person (a House's carter makes the town's page and its talk, and no cheer), and
 * `trade cheer` plays it at the operator's feet.
 *
 * It is meant to read as people, not a script. Nobody is told "now": each notices at their own moment, the nearest first, a head turning while
 * they are still walking, and stops a few ticks after (their own few); walks over at their own pace to their own place, no nearer than they like
 * (a shy one stays back at five blocks, a cheerful one comes right up) and not on top of anyone; cheers in their own way, which their ways
 * ([Trait]) and work decide (a cheerful one whoops, a grumpy one nods and claps once, a devout one gives thanks, a drinker raises a cup after,
 * a guard salutes); lingers as long as they like, with a second clap or a nod between; and goes, some with a wave. The first cheers roll into
 * the rest, and when the third is up the street's own swell is heard and the keeper answers it. A voice is raised by at most four of them (the
 * town's talk switches decide whether any is), and the children say theirs, with what was brought in grey under it.
 *
 * Every part is an [Aside], the scene system's leash: it breaks off by itself when their day moves on, when they are hurt, when a word is
 * spoken to them, when no player is near, and after [MOST] ticks at the longest, and its end is always the same: they are free and their
 * day picks up afresh. Nobody not free is taken (the busy, a guard on post, a keeper at a sale), and the keeper is taken at her post only if
 * she is not talking with anyone, as the hall's merchant is for a Warrant; if she is, she says her word and the drawer shuts and nothing else of
 * her changes. Nothing is saved: a stopped world forgets the scene, and the hour a town last cheered.
 */
object ReliefCheer {
    // ---- The numbers ----

    /** How far from the shop the people who join are, and how near a player must be for the scene to be played at all. */
    private const val NEAR = 24.0
    private const val SEEN = 48.0

    /** A town cheers at most once in this many ticks (a game hour). */
    private const val HOUR = 1000L

    /** The ticks between the delivery and the town noticing it, for the player to have closed the counter's screen and looked up. */
    private const val NOTICE = 30L

    /** The furthest from the player the grown-ups stand to cheer, in blocks. */
    private const val STAND = 5.0

    /** The most who join: grown-ups and children for a big cheer (a relief or a feast), and for the smaller (a consignment of 128 units). */
    private const val ADULTS = 6
    private const val ADULTS_SMALL = 4
    private const val CHILDREN = 2
    private const val CHILDREN_SMALL = 1

    /** How far from the trader their pack animal or cart is for the children to run to it, and how far the voices carry. */
    private const val CARRIER = 8.0
    private const val HEARD = 32.0

    /** The most grown-ups who raise their voices, and the most ticks into the scene the street's peak waits for the third cheer. */
    private const val VOICES = 4
    private const val LATEST = 260L

    /** The longest any part runs, and how far from a player it is still played. */
    private const val MOST = 900
    private const val REACH = SEEN + 16.0

    /** The cries, as `trade_cheer_<n>` has them: a warm one ("Hurrah!", "Bravo!") or a measured one ("Well done!"). */
    private const val NO_CRY = -1
    private const val WARM = 0
    private const val WELL = 1

    /** The tick of each clip the cheer itself happens on (the arms up, the hands met), for the sound, the sparks and the voice. */
    private val CUE: Map<Activity, Int> = mapOf(
        Activity.HURRAH to 8, Activity.CHEER to 6, Activity.CLAP to 6, Activity.THANK to 12, Activity.NOD to 4, Activity.SALUTE to 6,
    )

    // ---- What it answers ----

    /** What was brought in, for the words the keeper and the children say over it (the operator's cheer has none). */
    class Delivered(val good: Good, val units: Int, val name: String, val relief: Boolean)

    /**
     * What [at] did: [why] it did nothing, or null if the town turned; then how many grown-ups and children will join, the role of the keeper
     * who will answer, what the children run to, who is in the crowd (resident ids, for the talk to say who saw it) and about how many seconds
     * it will take.
     */
    class Played(
        val why: String? = null,
        val adults: Int = 0,
        val children: Int = 0,
        val keeper: TownRole? = null,
        val carrier: String? = null,
        val crowd: List<Int> = listOf(),
        val seconds: Int = 0,
    ) {
        val played: Boolean get() = why == null
    }

    /** Something a trader's goods ride on, for the children to run to: a pack animal, a cart. */
    fun interface Carrying {
        /** The animals and carts of [player]'s within [radius] blocks of them. */
        fun near(player: ServerPlayer, radius: Double): List<Entity>
    }

    // ---- What is kept (nothing is saved) ----

    private class Step(val due: Long, val run: () -> Unit)

    private val steps = ArrayList<Step>()
    private val last = HashMap<Long, Long>()
    private val carrying = CopyOnWriteArrayList<Carrying>()
    private var started = false

    /**
     * Sets the scene going: its steps run each tick and are forgotten as the server stops. Once, at setup (`Scenes.init`). With no pack animal
     * or cart made yet, the children run to a tame donkey, mule or llama of the player's, and to a `player_cart` when there is one; a part that makes
     * the trader's animals and carts adds its own with [addCarrying].
     */
    fun init() {
        if (started) return
        started = true
        carrying.add(Carrying { player, radius -> beasts(player, radius) })
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    /** Adds a kind of thing the children run to (a pack animal that is not a donkey, a cart or wagon of the trader's), once, at setup. */
    fun addCarrying(kind: Carrying) {
        carrying.add(kind)
    }

    fun forget() {
        steps.clear()
        last.clear()
    }

    /** How many ticks it is until [capital] may cheer again; 0 if it may. */
    fun cooling(level: ServerLevel, capital: Long): Long {
        val before = last[capital] ?: return 0L
        val gone = level.gameTime - before
        return if (gone in 0 until HOUR) HOUR - gone else 0L
    }

    /** Forgets when each town last cheered (for `trade cheer reset`); how many it knew of. */
    fun reset(): Int {
        val n = last.size
        last.clear()
        return n
    }

    private fun later(due: Long, run: () -> Unit) {
        steps.add(Step(due, run))
    }

    private fun tick(server: MinecraftServer) {
        if (steps.isEmpty()) return
        val now = server.overworld().gameTime
        val due = steps.filter { it.due <= now }
        if (due.isEmpty()) return
        steps.removeAll(due.toSet())
        for (step in due) {
            try {
                step.run()
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A step of the town's cheer failed", e)
            }
        }
    }

    // ---- The scene ----

    /**
     * The town cheers for [player], who brought goods in at the shop at [shopPos] (of [shop], when it is known: its keeper bows). [big] is a relief
     * or a feast (up to six grown-ups and two children, most of them with their arms right up), else a consignment of 128 units or more (up to four
     * and one). [what] is what was brought, for the grey under the keeper's and the children's words. With [force] (the operator's `trade cheer`)
     * the switches and the hour between two cheers in a town are not asked, and the hour is not started.
     *
     * Nothing is played, and [Played.why] says why, when the cheer is off (`tradeCheer`, or every scene), when [player] is not in a town or is
     * more than 48 blocks from the shop, when the town cheered less than a game hour ago, or when no one free is near.
     */
    fun at(
        level: ServerLevel, shopPos: BlockPos, player: ServerPlayer, big: Boolean,
        shop: Shop? = null, what: Delivered? = null, force: Boolean = false,
    ): Played {
        if (!started) init()
        if (!force && !TradeConfig.cheer()) return Played("the cheer is switched off (setting: Cheer a Delivery, or The Town Answers, or Trader's Life)")
        if (!force && !Scenes.any()) return Played("every scene is switched off")
        if (level.dimension() != Level.OVERWORLD) return Played("it is played in the overworld's towns")
        if (!player.isAlive || player.isRemoved || player.level() !== level) return Played("there is no one to see it")
        val here = Scenes.here(level, player) ?: return Played("the player is not in a town")
        val centre = Vec3.atCenterOf(shopPos)
        if (player.distanceToSqr(centre) > SEEN * SEEN) return Played("nobody is near enough to the shop to see it")
        val now = level.gameTime
        if (!force) {
            val cooling = cooling(level, here.key)
            if (cooling > 0) return Played("this town cheered less than a game hour ago: another may in ${(cooling + 19) / 20} seconds")
        }
        val keeper = keeperOf(level, shopPos, shop)
        val folk = level.getEntitiesOfClass(Townsperson::class.java, AABB(shopPos).inflate(NEAR, 8.0, NEAR)) {
            it !== keeper && it.resident >= 0 && Aside.free(it, seated = true)
        }
        val adults = folk.filter { it.role.adult }.sortedBy { it.distanceToSqr(player) }.take(if (big) ADULTS else ADULTS_SMALL)
        val kids = folk.filter { !it.role.adult }.sortedBy { it.distanceToSqr(player) }.take(if (big) CHILDREN else CHILDREN_SMALL)
        if (adults.isEmpty() && kids.isEmpty() && keeper == null) return Played("no one free is near the shop")
        if (!force) {
            if (last.size > 64) last.entries.removeIf { now - it.value !in 0 until HOUR }
            last[here.key] = now
        }
        val carrier = carrierOf(player)
        val s = Scene(level, player, big, what, carrier, now + NOTICE)
        s.keeper = keeper
        s.peakAt = minOf(3, adults.size)
        var longest = 0L
        adults.forEachIndexed { rank, p -> longest = maxOf(longest, plan(s, p, rank, adults.size)) }
        kids.forEachIndexed { k, p -> longest = maxOf(longest, planChild(s, p, k, kids.size)) }
        // The street's peak is the third cheer up, or (if the cheering is slow to come, or no one is to) when it has waited long enough
        later(s.t0 + if (adults.isEmpty()) 40L else LATEST) { if (adults.isEmpty() || s.cheering > 0) peak(s) }
        return Played(
            null, adults.size, kids.size, keeper?.role, carrier?.type?.description?.string,
            (adults + kids).map { it.resident }, ((longest + NOTICE + 19) / 20).toInt(),
        )
    }

    /** One playing of the scene: who it is for, what is kept of it while it runs, and where it has got to. */
    private class Scene(
        val level: ServerLevel, val player: ServerPlayer, val big: Boolean, val what: Delivered?, val carrier: Entity?, val t0: Long,
    ) {
        var keeper: Townsperson? = null

        /** How many have raised their cheer, how many it takes for the street's peak, and whether that has come. */
        var cheering = 0
        var peakAt = 0
        var peaked = false

        /** How many voices have been raised, which of the warm cries is next, and whether the animal's bell has rung. */
        var voices = 0
        var warm = 0
        var belled = false

        /** The places taken to stand in, so no two stand on one. */
        val spots = ArrayList<BlockPos>()

        /** Where they look: the player's eyes, while there is a player. */
        val eye: () -> Vec3? = { player.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }

        /**
         * A place for [p] to stand [dist] blocks from [centre] (a little either side of the straight line from where they are), on the ground and
         * free of the others; null if they are near enough already (within [near] of the centre) or there is no such place.
         */
        fun spotAround(p: Townsperson, centre: Vec3, dist: Double, near: Double, spread: Double = 0.9): BlockPos? {
            if (p.distanceToSqr(centre) <= near * near) return null
            val base = atan2(p.z - centre.z, p.x - centre.x)
            val y = floor(centre.y).toInt()
            repeat(8) { k ->
                val a = base + (p.random.nextDouble() - 0.5) * (spread + k * 0.18)
                val d = dist - 0.3 + p.random.nextDouble() * 0.6
                val x = floor(centre.x + cos(a) * d).toInt()
                val z = floor(centre.z + sin(a) * d).toInt()
                for (dy in intArrayOf(0, 1, -1, 2, -2)) {
                    val pos = BlockPos(x, y + dy, z)
                    if (Places.standable(level, pos) && spots.none { it.distSqr(pos) < 1.5 }) {
                        spots += pos
                        return pos
                    }
                }
            }
            return null
        }
    }

    // ---- The grown-ups ----

    /** How one grown-up takes it. */
    private class Style(
        /** The cheer itself, and what follows it (a laugh, a second clap, a raised cup). */
        val first: Activity, val then: Activity?,
        /** The cry they raise with it, if any ([WARM], [WELL]), and whether green sparks go up over their head. */
        val cry: Int, val sparks: Boolean,
        /** How near the player they come, how fast they walk, how long they take to draw breath on arriving and how long they stay on after. */
        val stand: Double, val pace: Double, val slow: Int, val linger: Int,
        /** How they stand while they stay, and whether they wave as they go. */
        val rest: Activity, val wave: Boolean,
    )

    /** [p]'s way of cheering: by their work first (a guard salutes), then by their ways, then by chance, so that no two cheer alike. */
    private fun style(level: ServerLevel, p: Townsperson, big: Boolean): Style {
        val r = Residents.get(level).of(p)
        val rnd = p.random
        fun has(t: Trait) = r?.has(t) == true
        fun within(a: Double, b: Double) = a + rnd.nextDouble() * (b - a)
        fun ticks(a: Int, b: Int) = a + rnd.nextInt(b - a + 1)
        val jitter = 0.9 + rnd.nextDouble() * 0.2
        return when {
            p.role == TownRole.GUARD ->
                Style(Activity.SALUTE, Activity.CLAP, NO_CRY, false, within(3.6, 4.6), Jobs.WALK * jitter, 4, ticks(90, 120), Activity.NONE, false)
            p.role == TownRole.PRIEST ->
                Style(Activity.THANK, null, NO_CRY, true, within(3.4, 4.4), Jobs.STROLL * jitter, 8, ticks(130, 160), Activity.GAZE, false)
            p.role == TownRole.NOBLE ->
                Style(Activity.CLAP, Activity.NOD, NO_CRY, false, within(4.2, STAND), Jobs.STROLL * jitter, 10, ticks(110, 140), Activity.ARMS_FOLDED, false)
            // A grumpy one nods, claps once and says it was well done, if they say anything
            has(Trait.GRUMPY) ->
                Style(Activity.NOD, Activity.CLAP, if (rnd.nextInt(3) == 0) WELL else NO_CRY, false, within(4.2, STAND), Jobs.STROLL * jitter, 8, ticks(80, 120), Activity.ARMS_FOLDED, false)
            // A shy one stays back and claps, and says nothing
            has(Trait.SHY) ->
                Style(Activity.CLAP, null, NO_CRY, true, within(4.4, STAND), Jobs.STROLL * jitter, 6, ticks(100, 140), Activity.NONE, false)
            // A cheerful one comes right up, whoops, laughs and waves as they go
            has(Trait.CHEERFUL) ->
                Style(
                    if (big || rnd.nextInt(5) < 3) Activity.HURRAH else Activity.CHEER, Activity.LAUGH, WARM, true,
                    within(2.4, 3.6), Jobs.BRISK * jitter, 0, ticks(150, 190), Activity.GAZE, true,
                )
            // A drinker cheers, then raises a cup to the carrier
            has(Trait.DRINKER) ->
                Style(Activity.CHEER, Activity.TOAST, WARM, true, within(2.8, 4.0), Jobs.WALK * jitter, 4, ticks(120, 160), Activity.NONE, true)
            // A devout one gives thanks, quietly
            has(Trait.DEVOUT) ->
                Style(Activity.THANK, Activity.CLAP, NO_CRY, true, within(3.0, 4.2), Jobs.WALK * jitter, 6, ticks(120, 150), Activity.GAZE, false)
            // A chatty one waves to the rest and stays the longest
            has(Trait.CHATTY) ->
                Style(Activity.CHEER, Activity.WAVE, if (rnd.nextBoolean()) WELL else WARM, true, within(3.2, 4.6), Jobs.WALK * jitter, 2, ticks(170, 230), Activity.GAZE, true)
            // A diligent one cheers once and gets back to their work
            has(Trait.DILIGENT) ->
                Style(Activity.CHEER, null, WELL, true, within(3.4, 4.6), Jobs.BRISK * jitter, 4, ticks(60, 90), Activity.NONE, false)
            // An idle one claps without hurry and lingers with folded arms
            has(Trait.IDLE) ->
                Style(Activity.CLAP, null, NO_CRY, false, within(3.8, STAND), Jobs.STROLL * jitter, 10, ticks(170, 220), Activity.ARMS_FOLDED, false)
            else -> {
                val first = when {
                    big && rnd.nextInt(3) == 0 -> Activity.HURRAH
                    rnd.nextInt(5) == 0 -> Activity.CLAP
                    else -> Activity.CHEER
                }
                Style(
                    first, if (rnd.nextInt(3) == 0) Activity.CLAP else null, if (first == Activity.CLAP) NO_CRY else if (rnd.nextBoolean()) WARM else WELL, true,
                    within(3.0, 4.8), Jobs.WALK * jitter, 3, ticks(130, 170), if (rnd.nextBoolean()) Activity.GAZE else Activity.NONE, rnd.nextInt(3) == 0,
                )
            }
        }
    }

    /**
     * Sets [p]'s part going, [rank]th nearest of [n]: they notice at their own moment (the nearest first, all inside a second), turning their head
     * while they carry on, and a few ticks after they are taken. How many ticks the part will take at the longest, from now.
     */
    private fun plan(s: Scene, p: Townsperson, rank: Int, n: Int): Long {
        val st = style(s.level, p, s.big)
        val rnd = p.random
        val noticed = s.t0 + rank * 20L / n.coerceAtLeast(1) + rnd.nextInt(6)
        val react = 6 + st.slow + rnd.nextInt(8)
        later(noticed) {
            if (p.isAlive && !p.isRemoved && s.player.isAlive && Aside.free(p, seated = true)) p.glance(s.player, react + 8)
        }
        later(noticed + react) { join(s, p, st) }
        val walk = ((p.distanceTo(s.player) - st.stand).coerceAtLeast(0.0) / (st.pace * 0.4)).toLong()
        return noticed - s.t0 + react + walk + st.slow + st.first.ticks + (st.then?.ticks ?: 0) + st.linger + 40
    }

    /** [p] is taken for their part, if they are still free: turn, walk over, draw breath, cheer, stay, go. */
    private fun join(s: Scene, p: Townsperson, st: Style) {
        if (!p.isAlive || p.isRemoved || !s.player.isAlive || s.player.isRemoved) return
        val eye = s.eye
        val moves = ArrayList<Move>()
        moves += Turn(eye)
        // To a place of their own, no further off than five blocks; if there is none to be found, or the way was blocked, as near as they like
        val close = minOf(st.stand + 0.8, STAND)
        moves += GoTo({ s.spotAround(p, s.player.position(), st.stand, close) }, st.pace, near = 0.9, most = 400)
        moves += GoTo({ s.player.blockPosition().takeIf { p.distanceToSqr(s.player.position()) > (close + 0.7) * (close + 0.7) } }, st.pace, near = st.stand, most = 200)
        moves += Turn(eye)
        moves += Wait({ _, t -> t >= st.slow }, st.slow + 1, at = eye)
        val cue = CUE[st.first] ?: 6
        moves += Beat(st.first, st.first.ticks, eye) { who, t -> if (t == cue) cheered(s, who, st) }
        st.then?.let { moves += Beat(it, it.ticks, eye) }
        // Staying on, in the warmth of it: a while, a second clap or a nod, a while
        val rnd = p.random
        val before = st.linger * 2 / 5 + rnd.nextInt(10)
        moves += Beat(st.rest, before, eye)
        val between = if (rnd.nextBoolean()) Activity.CLAP else Activity.NOD
        moves += Beat(between, between.ticks, eye)
        moves += Beat(st.rest, (st.linger - before).coerceAtLeast(10), eye)
        if (st.wave) moves += Now { who -> who.act(Activity.WAVE_WALK) }
        Aside.take(p, Aside(moves, reach = REACH, most = MOST, fresh = true), seated = true)
    }

    /** [who] raises their cheer (the clip is at its cue): the villagers' celebrating, green sparks, a cry; and the street's peak if this is the third. */
    private fun cheered(s: Scene, who: Townsperson, st: Style) {
        val level = s.level
        s.cheering++
        val rnd = who.random
        if (st.first == Activity.CHEER || st.first == Activity.HURRAH) {
            level.playSound(null, who.x, who.y + 1.2, who.z, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.55f, 0.92f + rnd.nextFloat() * 0.2f)
        }
        if (st.sparks) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, who.x, who.y + who.bbHeight + 0.15, who.z, 3 + rnd.nextInt(3), 0.25, 0.12, 0.25, 0.0)
        if (st.cry != NO_CRY && s.voices < VOICES && Words.on()) {
            // The warm cries take turns, "Hurrah!" and "Bravo!", from a start of the scene's own
            val key = if (st.cry == WELL) "trade_cheer_1" else "trade_cheer_${if ((s.warm++ + s.t0.toInt()) % 2 == 0) 0 else 2}"
            if (who.say(key, force = true, hearing = HEARD, ticks = Talk.ticks(who, key) + 10L)) s.voices++
        }
        if (s.cheering >= s.peakAt) peak(s)
    }

    /** The street at its height (once): its swell is heard, and the shop's keeper answers it a moment after. */
    private fun peak(s: Scene) {
        if (s.peaked) return
        s.peaked = true
        val player = s.player
        if (!player.isAlive || player.isRemoved) return
        s.level.playSound(
            null, player.x, player.y, player.z, TradeSounds.get("trade.cheer"), SoundSource.NEUTRAL, (0.7f + 0.05f * s.cheering).coerceAtMost(1f), 1.0f,
        )
        later(s.level.gameTime + 14L) { keeperSpeaks(s) }
    }

    // ---- The keeper ----

    /** The keeper of [shop] near it (any shop's, nearest, when it is not known). */
    private fun keeperOf(level: ServerLevel, shopPos: BlockPos, shop: Shop?): Townsperson? {
        val reach = if (shop != null) 16.0 else 12.0
        val centre = Vec3.atCenterOf(shopPos)
        return level.getEntitiesOfClass(Townsperson::class.java, AABB(shopPos).inflate(reach, 6.0, reach)) {
            it.isAlive && it.resident >= 0 && (if (shop != null) Shop.kept(it) == shop else Shop.kept(it) != null)
        }.minByOrNull { it.distanceToSqr(centre) }
    }

    /**
     * The keeper bows with her hand on her heart, the till drawer shuts with a thump and she says her word to the street a second time ("Bless
     * the road that brought it."), with what was brought in grey under it. She is taken from her counter only if she is not at a sale; if she is,
     * the drawer and the word are all there is of her part.
     */
    private fun keeperSpeaks(s: Scene) {
        val k = s.keeper ?: return
        if (!k.isAlive || k.isRemoved || !s.player.isAlive) return
        val key = "trade_street_${k.random.nextInt(2)}"
        val line = Talk.ticks(k, key)
        val word = Speech.withDetail(key, delivered(s.what))
        val moves = ArrayList<Move>()
        moves += Turn(s.eye, 16)
        moves += Beat(Activity.BOW, Activity.BOW.ticks, s.eye) { who, t ->
            if (t == 8) drawer(who)
            if (t == 10) speak(who, word, line)
        }
        // Lets the word finish before she is back at her work
        moves += Beat(Activity.NONE, (line - 20).coerceAtLeast(0), s.eye)
        if (!Aside.take(k, Aside(moves, reach = REACH, most = MOST), seated = true, station = true)) {
            drawer(k)
            speak(k, word, line)
        }
    }

    /** The till drawer shut with a thump. */
    private fun drawer(who: Townsperson) {
        val level = who.level() as? ServerLevel ?: return
        level.playSound(null, who.x, who.y + 0.9, who.z, SoundEvents.BARREL_CLOSE, SoundSource.NEUTRAL, 0.8f, 1.25f)
    }

    private fun speak(who: Townsperson, word: String, line: Int) {
        if (Words.on()) who.say(word, force = true, hearing = HEARD, ticks = line + 20L)
    }

    /** The grey under the keeper's word: how many of what, from whom ("128 Wheat from Ransom."). */
    private fun delivered(what: Delivered?): String? =
        what?.let { Speech.of("talk.detail.trade.delivered${if (it.relief) ".relief" else ""}", it.units, it.good.key + ".many", Witness.named(it.name)) }

    // ---- The children ----

    /** The pack animal or cart of [player]'s the children run to, the nearest, if there is one within [CARRIER] blocks. */
    private fun carrierOf(player: ServerPlayer): Entity? =
        carrying.flatMap {
            try {
                it.near(player, CARRIER)
            } catch (e: RuntimeException) {
                listOf()
            }
        }.filter { it.isAlive && !it.isRemoved }.minByOrNull { it.distanceToSqr(player) }

    /** The player's tame donkeys, mules and llamas, and their carts, as the children find them until the trader's own are made. */
    private fun beasts(player: ServerPlayer, radius: Double): List<Entity> =
        player.level().getEntitiesOfClass(Entity::class.java, player.boundingBox.inflate(radius)) { e ->
            e.isAlive && when (e) {
                is AbstractChestedHorse -> e.isTamed && e.ownerReference?.uuid == player.uuid
                else -> BuiltInRegistries.ENTITY_TYPE.getKey(e.type).let { it.namespace == Guildmark.MOD_ID && it.path == "player_cart" }
            }
        }

    /** The first child pats and jumps round what the goods rode in on; the next tries a sack and cannot lift it. One child alone does both. How long, from now. */
    private fun planChild(s: Scene, p: Townsperson, k: Int, n: Int): Long {
        val rnd = p.random
        val noticed = s.t0 + 14 + k * 12L + rnd.nextInt(14)
        val react = 8 + rnd.nextInt(8)
        later(noticed) {
            if (p.isAlive && !p.isRemoved && s.player.isAlive && Aside.free(p, seated = true)) p.glance(s.player, react + 6)
        }
        later(noticed + react) { joinChild(s, p, k, n) }
        val to = s.carrier ?: s.player
        val run = (p.distanceTo(to) / (Jobs.RUN * 0.4)).toLong()
        return noticed - s.t0 + react + run + 260
    }

    /** [p], the [k]th of [n] children, is taken for their part, if they are still free. */
    private fun joinChild(s: Scene, p: Townsperson, k: Int, n: Int) {
        if (!p.isAlive || p.isRemoved || !s.player.isAlive || s.player.isRemoved) return
        val target = s.carrier
        val at: () -> Vec3? = {
            if (target != null && target.isAlive && !target.isRemoved) target.position().add(0.0, 0.7, 0.0) else s.eye()
        }
        val moves = ArrayList<Move>()
        moves += Turn(at)
        // Running, arms swinging, to it (to the player, with nothing else there to run to)
        moves += GoTo({ s.spotAround(p, (target?.position() ?: s.player.position()), 1.6, 1.9, spread = 1.6) }, Jobs.RUN, near = 1.0, overlay = Activity.PLAY, most = 300)
        moves += Turn(at)
        val sack = k > 0 || n == 1
        if (k == 0) {
            // A pat on the animal's flank or the cart's side, and the bell answers; a jump for joy and a word; round it, skipping
            moves += Beat(Activity.PICK_UP, Activity.PICK_UP.ticks, at) { _, t -> if (t == 8) bell(s) }
            moves += Beat(Activity.JUMP, Activity.JUMP.ticks, at) { who, t -> if (t == 0) joy(s, who) }
            moves += Now { who -> childSays(s, who) }
            moves += Beat(Activity.PLAY, 30 + p.random.nextInt(30), at)
            moves += Beat(Activity.PICK_UP, Activity.PICK_UP.ticks, at)
        }
        if (sack) {
            // Takes hold of a sack, heaves, staggers under it, gives it up with a thump and a laugh
            val stack = ItemStack(s.what?.good?.item ?: Items.WHEAT)
            moves += Beat(Activity.PICK_UP, Activity.PICK_UP.ticks, at) { who, t -> if (t == 8) who.carried = stack.copy() }
            if (k > 0) moves += Now { who -> childSays(s, who) }
            moves += Beat(Activity.STAGGER, 34, at)
            moves += Beat(Activity.CATCH_BREATH, 44, at) { who, t ->
                if (t == 0) {
                    who.carried = ItemStack.EMPTY
                    sackDown(who)
                }
            }
            moves += Beat(Activity.LAUGH, Activity.LAUGH.ticks, at)
        }
        moves += Beat(Activity.JUMP, Activity.JUMP.ticks, at)
        moves += Beat(Activity.PLAY, 40 + p.random.nextInt(40), at)
        moves += Now { who -> who.carried = ItemStack.EMPTY }
        val aside = Aside(moves, reach = REACH, most = MOST, fresh = true)
        aside.onEnd = { p.carried = ItemStack.EMPTY }
        Aside.take(p, aside, seated = true)
    }

    /** The animal's bell rings once as the first hand lands on it (a cart has none). */
    private fun bell(s: Scene) {
        val c = s.carrier ?: return
        if (s.belled || c !is AbstractHorse) return
        s.belled = true
        s.level.playSound(null, c.x, c.y + 0.9, c.z, TradeSounds.get("trade.bell_step"), SoundSource.NEUTRAL, 0.8f, 1.0f)
    }

    /** A child's whoop of joy: the villagers' celebrating, high, and a few sparks. */
    private fun joy(s: Scene, who: Townsperson) {
        s.level.playSound(null, who.x, who.y + 0.8, who.z, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.5f, 1.45f + who.random.nextFloat() * 0.15f)
        s.level.sendParticles(ParticleTypes.HAPPY_VILLAGER, who.x, who.y + who.bbHeight + 0.1, who.z, 3, 0.2, 0.1, 0.2, 0.0)
    }

    /** A sack let fall: a soft thump at their feet. */
    private fun sackDown(who: Townsperson) {
        val level = who.level() as? ServerLevel ?: return
        level.playSound(null, who.x, who.y + 0.1, who.z, SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, 0.9f, 0.75f)
    }

    /**
     * A child says what they see, with what was brought in grey under it: "The cart came!" only where it is a cart that came, else "Look at all
     * the sacks!" (the street talk's switches decide whether they say anything).
     */
    private fun childSays(s: Scene, who: Townsperson) {
        if (!Words.on()) return
        val player = Words.hearer(who) ?: return
        val cart = s.carrier != null && s.carrier !is AbstractHorse
        val base = Words.of(who, player, "trade.child", if (cart) setOf(0, 1) else setOf(1)) ?: return
        val grey = s.what?.let { Speech.of("talk.detail.trade.child", it.good.key + ".many") }
        val line = Street.Line(Speech.withDetail(base.speech, grey), base.voiced, base.ticks)
        Words.say(who, player, line, "children at a delivery's cheer", force = true)
    }

    // ---- A beat of someone's part ----

    /** One beat of a part: [activity] for [ticks], facing [at] and looking at it, with [each] heard on each tick of it (counted from 0). */
    private class Beat(
        private val activity: Activity, private val ticks: Int, private val at: () -> Vec3?,
        private val each: (Townsperson, Int) -> Unit = { _, _ -> },
    ) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(activity)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            at()?.let { there ->
                p.lookAt(there)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            }
            // Something else played meanwhile (a nod to a passer-by) settles back into the loop
            if (activity.loop && p.activity != activity && (p.activity.loop || p.actDone)) p.act(activity)
            each(p, t)
            return t < ticks
        }
    }
}
