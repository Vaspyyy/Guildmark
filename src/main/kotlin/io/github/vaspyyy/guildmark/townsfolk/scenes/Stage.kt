package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * A scene with several people in it (living-world/stories/design.md 4.2): a set of named [parts], each an ordinary [Aside], taken
 * all at once or not at all. Every way an [Aside] ends still applies to each part (danger, a new stretch of the day, no player
 * near, [most] ticks, a player's word); when one part ends before its part is played through, the stage lets the others go at
 * once and they carry on with their day. The moves that tie the parts together are [Barrier] (wait for one another), [Say]
 * (a tale's line, with its grey detail, and wait for it to be done) and [Fx] (particles and a sound); [Beat] and [Hold] are
 * a clip with something done on the beat, and a prop in a hand. A scene is scenery: the tale's state is the record, never "the
 * scene played" (the stage is memory, and nothing of it is saved).
 *
 * A stage is made by a set ([io.github.vaspyyy.guildmark.tales.Sets]) for the template whose `scene` asks, and played by
 * [io.github.vaspyyy.guildmark.tales.TaleLook]: one at a time in a capital, forty seconds apart, ten tries to take its parts and
 * then [then] hears `false`. Outside a capital's tales (`tale scene`) [start] is called directly.
 *
 * [hours] are the clock hours (a 24-hour clock, as the days keep it) the set may play in, or null for any; [at] is where it
 * happens (the nearest player's reach to it is what [io.github.vaspyyy.guildmark.tales.TaleLook] asks), by default where the first
 * part stands.
 */
class Stage(
    val name: String,
    val parts: List<Part>,
    val hours: ClosedFloatingPointRange<Double>? = null,
    private val at: Vec3? = null,
    val most: Int = TaleLimits.STAGE_TICKS,
) {
    /**
     * One person's part, named by its [slot]: [body] plays [moves]. An [essential] part must be taken or the stage is not played;
     * one that is not may be left out when its body is not free. With [station] a worker at their post is played where they
     * stand and goes back to it ([Aside.take]); with [seated] someone sitting may be taken, and stays in their seat if [station].
     */
    class Part(
        val slot: String,
        val body: Townsperson,
        val essential: Boolean = true,
        val moves: List<Move>,
        val station: Boolean = false,
        val seated: Boolean = false,
    )

    private val asides = LinkedHashMap<Part, Aside>()
    private var closing = false

    /** The barriers each part has among its moves: a barrier waits only for the parts that have it. */
    private val named: Map<Part, Set<String>> = parts.associateWith { part -> part.moves.filterIsInstance<Barrier>().map { it.name }.toSet() }

    /** Whether its parts were taken, and whether it is over. */
    var started = false
        private set
    var finished = false
        private set

    /** The game times it began and ended. */
    var since = 0L
        private set
    var endedAt = 0L
        private set

    /** Heard by [io.github.vaspyyy.guildmark.tales.TaleLook] on the server's tick once the stage is over: true if it was played (broken off or not), false if it could not be taken. */
    var then: ((Boolean) -> Unit)? = null

    /** Barriers every part has come to (or been let go from), so a part that comes late does not wait for the others who have gone on. */
    internal val passed = HashSet<String>()

    /** Props put in hands for the scene, taken out again as it ends. */
    internal val props = HashMap<Townsperson, ItemStack>()

    /** The parts that were taken. */
    fun taken(): List<Part> = asides.keys.toList()

    /** Where it happens. */
    fun anchor(): Vec3 = at ?: parts.first().body.position()

    /** Whether the parts are still playing. */
    val playing: Boolean get() = started && !finished

    /** Whether the hour of the day suits the set. */
    fun inHours(level: ServerLevel): Boolean {
        val h = hours ?: return true
        val now = Days.offsetOf(level.overworldClockTime)
        val from = Days.clock(h.start)
        val to = Days.clock(h.endInclusive)
        return if (from <= to) now in from..to else now >= from || now <= to
    }

    /**
     * Takes every part with [Aside.take]: all of the essential ones or none (each is checked free before any is taken, so
     * nobody is got up for a stage that will not play). True if the stage is on; false if a part could not be taken (the
     * others untouched), when it may be tried again.
     */
    fun start(level: ServerLevel): Boolean {
        if (started || parts.isEmpty() || parts.size > TaleLimits.STAGE_PARTS) return false
        val going = ArrayList<Part>()
        for (part in parts) {
            val b = part.body
            val ok = b.isAlive && !b.isRemoved && b.level() === level && Aside.free(b, part.seated, part.station)
            if (ok) going += part else if (part.essential) return false
        }
        if (going.isEmpty() || going.map { it.body }.toSet().size != going.size) return false
        for (part in going) {
            val aside = Aside(part.moves, most = most)
            aside.stage = this
            aside.onEnd = { partEnded(aside) }
            if (!Aside.take(part.body, aside, part.seated, part.station)) {
                closing = true
                for ((q, a) in asides) a.cancel(q.body)
                closing = false
                asides.clear()
                return false
            }
            asides[part] = aside
        }
        started = true
        since = level.gameTime
        return true
    }

    /** Lets everyone go now, back to their day, wherever they stand (the switch went off, the server stops, a body is gone). */
    fun cancel(level: ServerLevel) {
        if (finished) return
        closing = true
        for ((q, a) in asides) a.end(q.body, drop = false)
        closing = false
        finish(level.gameTime)
    }

    /** Whether the stage can no longer play: a body of a part still playing is gone, or it has gone on too long. */
    fun stale(level: ServerLevel): Boolean {
        if (!started || finished) return false
        if (level.gameTime - since > most + 100) return true
        return asides.any { (q, a) -> !a.over && (q.body.isRemoved || !q.body.isAlive || q.body.level() !== level) }
    }

    /** Whether every part that has the barrier [name] among its moves has come to it (or is over). */
    internal fun reached(name: String): Boolean = asides.all { (part, aside) -> aside.over || name !in named[part].orEmpty() || aside.barrier == name }

    private fun partEnded(aside: Aside) {
        if (finished) return
        // Broken off, not played through: the others go back to their day at once
        if (!closing && started && !aside.completed) {
            closing = true
            for ((q, a) in asides) if (a !== aside) a.end(q.body, drop = false)
            closing = false
        }
        if (!closing && asides.values.all { it.over }) finish(asides.keys.firstOrNull()?.body?.level()?.gameTime ?: since)
    }

    private fun finish(now: Long) {
        if (finished) return
        finished = true
        endedAt = now
        for ((p, stack) in props) {
            if (ItemStack.isSameItemSameComponents(p.carried, stack)) {
                p.carried = ItemStack.EMPTY
                p.carriedUntil = 0
            }
        }
        props.clear()
    }

    companion object {
        /** [stack] put in [p]'s hands for the scene, taken out again as the stage ends (null, or empty: taken out now). */
        fun give(p: Townsperson, stack: ItemStack?) {
            val props = (p.director as? Aside)?.stage?.props
            if (stack == null || stack.isEmpty) {
                p.carried = ItemStack.EMPTY
                p.carriedUntil = 0
                props?.remove(p)
                return
            }
            val given = stack.copy()
            p.carried = given
            p.carriedUntil = 0
            props?.put(p, given)
        }
    }

    // ---- The moves that tie the parts together ----

    /**
     * Waits until every part that has a barrier of this [name] among its moves is at it, or has ended, [most] ticks at the
     * longest, and then goes on regardless: the whole of the synchronising (a handing waits for the taker, a hug for the
     * runner). A part that does not name the barrier is not waited for. They wait in [pose], looking at what [at] gives.
     */
    class Barrier(
        internal val name: String, private val most: Int = 200, private val pose: Activity = Activity.NONE,
        private val at: (() -> Vec3?)? = null,
    ) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            if (pose != Activity.NONE || !p.activity.overlay) p.act(pose)
            a.barrier = name
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            val stage = a.stage ?: return false
            if (name in stage.passed) return false
            if (stage.reached(name) || t >= most) {
                stage.passed += name
                a.barrier = null
                return false
            }
            at?.invoke()?.let { there ->
                p.lookAt(there)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            }
            if (p.activity != pose && (p.activity.loop || p.actDone)) p.act(pose)
            return true
        }

        override fun stop(p: Townsperson) {
            (p.director as? Aside)?.barrier = null
        }
    }

    /**
     * Says a take of the tale family [family] (`tale.<template>.<beat>`) in the part's voice with [args] and the grey [detail],
     * [gesture] playing meanwhile, and waits until the line is done, so the next speaker comes after. It goes through [Words]:
     * with the street talk off, for a family that is not recorded yet, or with no player in hearing it says nothing and goes on
     * at once (the clips still play); with someone near talking it waits its turn, [patience] ticks at the longest.
     */
    class Say(
        private val family: String, private val detail: String? = null, private val args: List<String> = listOf(),
        private val gesture: Activity = Activity.NONE, private val at: (() -> Vec3?)? = null, private val patience: Int = 60,
    ) : Move() {
        private var length = -1
        private var said = 0

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            if (gesture != Activity.NONE) p.act(gesture)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            at?.invoke()?.let { there ->
                p.lookAt(there)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            }
            if (length < 0) {
                if (!Words.can(p, family)) return false
                val line = Words.sayWith(p, family, args, detail, "a tale: $family")
                if (line == null) return t < patience
                length = line.ticks + 20
                said = t
            }
            if (gesture.loop && p.activity != gesture && (p.activity.loop || p.actDone)) p.act(gesture)
            return t - said < length && p.speech.isNotEmpty()
        }
    }

    /**
     * A clip played like [Play] for [ticks], with [on] heard at each tick of it (counted from 0): goods passed across on the
     * beat of a hand-over, an effect at the moment of a clip.
     */
    class Beat(
        private val activity: Activity, private val ticks: Int = activity.ticks, private val at: (() -> Vec3?)? = null,
        private val on: (Townsperson, Int) -> Unit,
    ) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(activity)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            at?.invoke()?.let { there ->
                p.lookAt(there)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            }
            if (activity.loop && p.activity != activity && (p.activity.loop || p.actDone)) p.act(activity)
            on(p, t)
            return if (activity.loop) t < ticks else t < activity.ticks
        }
    }

    /**
     * [stack] put in the hands for the scene (drawn by the client in the clips whose hands show goods), or, with [stack]
     * empty, taken out of them; whatever is still there as the stage ends is taken out for good.
     */
    class Hold(private val stack: ItemStack) : Move() {
        override fun start(p: Townsperson, a: Aside) = give(p, stack)

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean = false
    }

    /**
     * Particles and a sound of [kind] at the part, or at what [at] gives, sent only to the players near; with [ticks] it goes
     * on that long (a flame that burns for as long as the clip does).
     */
    class Fx(private val kind: Kind, private val at: ((Townsperson) -> Vec3?)? = null, private val ticks: Int = 0) : Move() {
        /** SPARK a smith's sparks, SMOKE a puff, PETALS falling, HAPPY the villagers' green, BURN flame, smoke and ash, CANDLE a small flame, MEND dust and a thunk. */
        enum class Kind { SPARK, SMOKE, PETALS, HAPPY, BURN, CANDLE, MEND }

        override fun start(p: Townsperson, a: Aside) = emit(p, 0)

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            if (ticks <= 0) return false
            if (t > 0 && t % STEP == 0) emit(p, t)
            return t < ticks
        }

        private fun emit(p: Townsperson, t: Int) {
            val level = p.level() as? ServerLevel ?: return
            play(level, kind, at?.invoke(p) ?: p.position().add(0.0, 1.0, 0.0), t)
        }

        companion object {
            /** Ticks between one burst and the next of an effect that goes on. */
            const val STEP = 4

            /** How near a player must be to see it. */
            const val RANGE = 40.0

            /** [kind] at [pos], tick [t] of it (an effect that goes on gives the first its sound). */
            fun play(level: ServerLevel, kind: Kind, pos: Vec3, t: Int = 0) {
                val r = level.random
                when (kind) {
                    Kind.SPARK -> repeat(5) {
                        send(level, Townsfolk.FORGE_SPARK.get(), pos, 0, (r.nextDouble() - 0.5) * 0.3, 0.15 + r.nextDouble() * 0.15, (r.nextDouble() - 0.5) * 0.3, 1.0)
                    }
                    Kind.SMOKE -> {
                        send(level, ParticleTypes.SMOKE, pos, 5, 0.12, 0.05, 0.12, 0.01)
                        send(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos, 0, 0.0, 0.07, 0.0, 1.0)
                    }
                    Kind.PETALS -> send(level, ParticleTypes.CHERRY_LEAVES, pos.add(0.0, 1.2, 0.0), 12, 1.4, 0.4, 1.4, 0.02)
                    Kind.HAPPY -> send(level, ParticleTypes.HAPPY_VILLAGER, pos, 6, 0.4, 0.35, 0.4, 0.0)
                    Kind.BURN -> {
                        send(level, ParticleTypes.FLAME, pos, 0, 0.0, 0.02, 0.0, 1.0)
                        send(level, ParticleTypes.SMALL_FLAME, pos.add(0.05, 0.05, 0.0), 0, 0.0, 0.03, 0.0, 1.0)
                        send(level, ParticleTypes.SMOKE, pos.add(0.0, 0.15, 0.0), 0, 0.01, 0.05, 0.0, 1.0)
                        send(level, ParticleTypes.ASH, pos.add(0.0, 0.1, 0.0), 2, 0.2, 0.15, 0.2, 0.01)
                        if (t == 0) sound(level, SoundEvents.FIRECHARGE_USE, pos, 0.6f, 1.3f)
                        else if (t % 16 == 0) sound(level, SoundEvents.FIRE_AMBIENT, pos, 0.5f, 1.0f)
                    }
                    Kind.CANDLE -> {
                        send(level, ParticleTypes.SMALL_FLAME, pos, 0, 0.0, 0.02, 0.0, 1.0)
                        if (t == 0) sound(level, SoundEvents.FLINTANDSTEEL_USE, pos, 0.4f, 1.4f)
                    }
                    Kind.MEND -> {
                        send(level, ParticleTypes.POOF, pos, 5, 0.4, 0.1, 0.4, 0.02)
                        send(level, ParticleTypes.CLOUD, pos, 3, 0.3, 0.05, 0.3, 0.01)
                        if (t == 0) sound(level, SoundEvents.WOOD_PLACE, pos, 1.0f, 0.8f)
                    }
                }
            }

            /** [type] at [pos] for each player within [RANGE] of it (the per-player form, so only those near are sent it). */
            fun send(level: ServerLevel, type: ParticleOptions, pos: Vec3, count: Int, dx: Double, dy: Double, dz: Double, speed: Double) {
                for (player in level.players()) {
                    if (player.isSpectator || player.distanceToSqr(pos) > RANGE * RANGE) continue
                    level.sendParticles(player, type, false, false, pos.x, pos.y, pos.z, count, dx, dy, dz, speed)
                }
            }

            /** [sound] at [pos], heard by those near. */
            fun sound(level: ServerLevel, sound: SoundEvent, pos: Vec3, volume: Float = 1f, pitch: Float = 1f) {
                level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.NEUTRAL, volume, pitch)
            }
        }
    }
}
