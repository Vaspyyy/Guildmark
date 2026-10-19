package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Sit
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.scenes.ToppingOut
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import kotlin.math.abs

/**
 * The master builder of a works going up (living-world/design-influence.md section 3, "The crew" and "Ceremonies"):
 * the town's own master builder, the same man (by his name and his looks) at every works of his capital, with the
 * crew while a player is near and its materials have begun to come in, in working hours. Never saved: one found in a
 * chunk as it loads goes at once ([onJoin]); he's brought in afresh when he's wanted.
 *
 * He comes in through the town's gate the first day of a works, and on the days after from the inn's door (or down
 * the street), and keeps his post by the works ([WorksDrawing.master]), the drawing in his hands or his arms folded,
 * his eyes on the work. Now and then he's off on a round of his own, one at a time and each only now and then: the
 * lot's corners surveyed while it's staked out and its footings go in, a plumb line held to the newest stone, a call
 * for mortar at the tub when it runs low, a word to the two carrying a beam, a look in the crate and a word when the
 * yard has run out, the wagon pointed in when its freight comes. His lines are the master's own and the homestead's
 * (the stages, the shortages, hello), said one every [QUIET] ticks at most but for his calls.
 *
 * He calls each stage as it's finished (the crew cheers the walls, the roof and the topping out with him: BuilderCrew
 * hears him through [body]); at the topping out he raises his cup to the town with the crew, and at the tower its
 * beacon has its first fire. At the end of the day he goes to the inn for a drink, and is gone once nobody's about to
 * see him. When the works stands he opens it: his handbell rung by the patron's plaque, the cloth pulled off it, a word
 * to the patron if they're there to hear it (or to the town), and off he goes. With nobody near, the plaque goes up
 * uncovered and nobody opens anything. A ceremony takes [CEREMONY] ticks at most: every opening ends.
 */
internal object MasterBuilder {
    /** He wears this tag, so a master saved with his chunk is known for what he is. */
    const val TAG = "guildmark.master_builder"

    /** His name's key ("Master %s"): the client knows him by it (for his own handbell, client/works/WorksMoments). */
    const val NAME = "entity.guildmark.master_builder"

    /** Ticks between one line of his and the next unasked. */
    private const val QUIET = 400L

    /** How near a player must be to him for him to stay about off his works (at the inn, walking off). */
    private const val SEEN = 48.0

    /** The longest an opening takes. */
    private const val CEREMONY = 2400L

    private const val SPEED = 0.55

    private val heads = HashMap<Int, Head>()

    /** When each works' opening began (game time), while it's going on. */
    private val openings = HashMap<Int, Long>()

    fun onJoin(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (event.loadedFromDisk() && entity is Townsperson && TAG in entity.entityTags()) event.isCanceled = true
    }

    fun reset() {
        heads.clear()
        openings.clear()
    }

    /** Works [id]'s master gone from the world (nobody near, the works switched off or done with him); one walking off for good goes by himself. */
    fun away(level: ServerLevel, id: Int) {
        val h = heads[id] ?: return
        if (h.leaving && h.alive(level)) return
        h.vanish()
    }

    /** Works [id]'s master, while he's about at his works (not off at the inn, nor walking off). */
    fun body(level: ServerLevel, id: Int): Townsperson? = live(level, id)?.takeIf { !it.leaving && !it.inn }?.p

    private fun live(level: ServerLevel, id: Int): Head? {
        val h = heads[id] ?: return null
        if (h.alive(level)) return h
        heads.remove(id)
        return null
    }

    // ---- Each second ----

    /** Each second while [w] is built (its land loaded): its master there while [present] (a player near and the materials in), in working hours. */
    fun tick(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, present: Boolean) {
        val workday = site.workday()
        val h = live(level, w.id)
        if (h == null) {
            if (!present || !workday || site.finished) return
            spawn(level, w, rec, site, capital, null)
            return
        }
        h.see(w, rec, site, capital)
        if (h.leaving) return
        if (!workday) {
            h.evening(level)
            return
        }
        if (h.inn) h.morning()
        if (!present) {
            // Nobody about to see him any more: gone, as the crew is
            if (level.getNearestPlayer(h.p, SEEN) == null) h.vanish()
            return
        }
        h.think(level)
    }

    /** [stage] of [site] is done, someone near to hear it called. */
    fun calls(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, stage: Stage) {
        live(level, w.id)?.takeIf { !it.leaving && !it.inn }?.call(level, stage)
    }

    /** [w] is topped out: its toast with the crew (and at the tower, its beacon's first fire), if someone's near to see it. */
    fun toppedOut(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, near: Boolean) {
        if (rec.toasted) return
        rec.toasted = true
        WorksBuildData.get(level).changed()
        val h = live(level, w.id)?.takeIf { !it.leaving && !it.inn }
        if (near && h != null) h.toast(level, site, rec, w.kind == WorksKind.TOWER)
        else if (w.kind == WorksKind.TOWER) WorksAtWork.firstFire(level, rec, site, near)
    }

    /** Freight for works [id] has come in on the wagon at [at]: he points it in. */
    fun wagon(level: ServerLevel, id: Int, at: Vec3) {
        live(level, id)?.takeIf { !it.leaving && !it.inn }?.wagon(level, at)
    }

    // ---- The opening ----

    /**
     * [w] stands finished: its opening, true once it's over. Its plaque goes up on its wall, covered if someone is
     * [near] to see it opened (then he rings his bell beside it and pulls the cloth off), uncovered at once if nobody
     * is. A works with no plaque (a stall) or no room for one has nothing to open.
     */
    fun opening(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, near: Boolean): Boolean {
        val pin = site.drawing.plaque ?: return true
        val pos = site.frame.world(pin.x, site.drawing.y0 + pin.y, pin.z)
        val facing = site.frame.direction(pin.facing)
        if (!level.getBlockState(pos).`is`(WorksBlocks.PLAQUE.get())) {
            val here = level.getBlockState(pos)
            if (!(here.isAir || here.canBeReplaced() && here.fluidState.isEmpty && !here.hasBlockEntity())) {
                // Something stands where it hangs: opened without it
                openings.remove(w.id)
                return true
            }
            level.setBlock(pos, WorksBlocks.PLAQUE.get().defaultBlockState().setValue(WorksFacingBlock.FACING, facing).setValue(WorksPlaqueBlock.COVERED, near), Block.UPDATE_ALL)
            rec.plaque = pos
            WorksBuildData.get(level).changed()
        }
        words(level, w, capital, pos)
        if (!level.getBlockState(pos).getValue(WorksPlaqueBlock.COVERED)) {
            openings.remove(w.id)
            return true
        }
        val now = level.gameTime
        val began = openings.getOrPut(w.id) { now }
        if (!near || now - began !in 0..CEREMONY) {
            // Nobody to see it (they've gone), or it's taken long enough: the cloth comes off by itself
            uncover(level, pos, sound = near)
            openings.remove(w.id)
            live(level, w.id)?.leave(level)
            return true
        }
        val h = live(level, w.id) ?: spawn(level, w, rec, site, capital, site.road()) ?: run {
            uncover(level, pos, sound = true)
            openings.remove(w.id)
            return true
        }
        h.see(w, rec, site, capital)
        if (!h.opening && !h.leaving) h.open(level, w, pos, facing)
        return false
    }

    /** The plaque's words: the works, its patron (who pledged most, as the works has it), how many gave with them, the town, and the day it opened. */
    private fun words(level: ServerLevel, w: WorksRec, capital: CapitalPiece, pos: BlockPos) {
        val be = level.getBlockEntity(pos) as? WorksPlaqueBlockEntity ?: return
        val v = Works.get(level, w.id) ?: return
        val patron = v.pledges.firstOrNull { it.player == v.patron }
        val others = (v.pledges.size - if (patron != null) 1 else 0).coerceAtLeast(0)
        val place = VillageNames.of(capital.inWorld(level).boardAnchor)
        be.set(w.kind.id, patron?.name ?: "", others, place, w.done)
    }

    /** The plaque's cloth off: now (the master's pull, or by itself). */
    private fun uncover(level: ServerLevel, pos: BlockPos, sound: Boolean) {
        val state = level.getBlockState(pos)
        if (!state.`is`(WorksBlocks.PLAQUE.get()) || !state.getValue(WorksPlaqueBlock.COVERED)) return
        level.setBlock(pos, state.setValue(WorksPlaqueBlock.COVERED, false), Block.UPDATE_ALL)
        (level.getBlockEntity(pos) as? WorksPlaqueBlockEntity)?.unveil()
        if (sound) WorksBlocks.play(level, pos, WorksBlocks.UNVEIL)
    }

    // ---- His coming ----

    /**
     * The master brought in for [w]: from [from], or (the first day of a works) the town's gate nearest it, or else the
     * inn's door, or down the street; then over to his post.
     */
    private fun spawn(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, from: BlockPos?): Head? {
        val first = !rec.came
        val start = from ?: (if (first) gate(level, capital, site) else innDoor(level, capital, site)) ?: site.road()
        if (!level.isLoaded(start)) return null
        val seed = rec.capital * 31L + 17L
        val look = if (Math.floorMod(seed, 2L) == 0L) 1 else 3
        val yaw = Mth.wrapDegrees((Math.toDegrees(Math.atan2(site.gate().z - start.z, site.gate().x - start.x)) - 90.0).toFloat())
        val p = Townsfolk.create(level, start, TownRole.CITIZEN, look, yaw, EntitySpawnReason.EVENT) ?: return null
        p.setPermanentlyInvulnerable(true)
        // Nothing can hurt him, so he doesn't run off from a passing monster (Townsperson.FleeGoal)
        p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
        p.addTag(TAG)
        p.setDropChance(EquipmentSlot.MAINHAND, 0f)
        p.setDropChance(EquipmentSlot.OFFHAND, 0f)
        val style = CityStyle.entries[rec.style.coerceIn(0, CityStyle.entries.size - 1)]
        p.customName = Component.translatable(NAME, Names.given(style, false, seed) + " " + Names.family(style, seed / 7))
        p.isCustomNameVisible = false
        val h = Head(w.id, p)
        h.see(w, rec, site, capital)
        p.director = h
        if (!level.addFreshEntity(p)) return null
        heads[w.id] = h
        h.arrive(first)
        if (first) {
            rec.came = true
            WorksBuildData.get(level).changed()
        }
        return h
    }

    /** Inside the town's gate on the side nearest the works. */
    private fun gate(level: ServerLevel, capital: CapitalPiece, site: TownWorksSite): BlockPos? {
        val plan = capital.inWorld(level).plan
        val c = capital.center
        val to = site.box.center
        // The harbour's gate is the quay's: he comes by road
        val side = plan.outerGates.filter { it != plan.layout.harbour }.ifEmpty { plan.outerGates }.maxByOrNull { d ->
            val dir = capital.direction(d)
            dir.stepX * (to.x - c.x).toDouble() + dir.stepZ * (to.z - c.z).toDouble()
        } ?: return null
        val pos = capital.standing(plan, plan.sidePos(side, 0, plan.bandOut - 3))
        return if (level.isLoaded(pos) && Places.standable(level, pos)) pos else null
    }

    /** Out of the inn's front door (the inn nearest the works), where he spent the evening. */
    private fun innDoor(level: ServerLevel, capital: CapitalPiece, site: TownWorksSite): BlockPos? {
        val places = CityPlaces.of(level, capital) ?: return null
        val inn = places.nearest(site.box.center, CityPlaces.TAVERNS).firstOrNull() ?: return null
        val out = inn.front?.outside ?: return null
        return if (level.isLoaded(out) && Places.standable(level, out)) out else null
    }

    // ---- The master himself ----

    /** One step of something he does (see [Head]): begun once, ticked until it's done. */
    private abstract class Beat {
        open fun start(h: Head) {}

        /** Tick [t]; false once it's done. */
        abstract fun tick(h: Head, t: Int): Boolean
        open fun stop(h: Head) {}
    }

    /** A walk to [to], arrived within [near]; out of sight it's simply done (Walker). */
    private class Go(private val to: BlockPos, private val near: Double = 1.2, private val most: Int = 900) : Beat() {
        private var walker: Walker? = null

        override fun start(h: Head) {
            h.hold(ItemStack.EMPTY)
            if (!h.p.activity.overlay) h.p.act(Activity.NONE)
            walker = Walker(h.p).also { it.go(to, SPEED, near) }
        }

        override fun tick(h: Head, t: Int): Boolean = t < most && walker?.tick() == Walker.State.WALKING

        override fun stop(h: Head) {
            walker?.stop()
        }
    }

    /** A turn of the body to what [at] gives, the eyes there first. */
    private class Face(private val at: () -> Vec3?) : Beat() {
        override fun tick(h: Head, t: Int): Boolean {
            val there = at() ?: return false
            val p = h.p
            p.lookAt(there)
            val yaw = p.yawTo(there)
            if (abs(Mth.degreesDifference(p.yBodyRot, yaw)) <= 6f || t >= 12) {
                p.face(yaw)
                return false
            }
            p.face(Mth.approachDegrees(p.yBodyRot, yaw, 18f))
            return true
        }
    }

    /** [clip] played (a loop for [ticks]; a one-shot once through), facing what [at] gives; [first] on its first tick, [on] each tick. */
    private class Clip(
        private val clip: Activity, private val ticks: Int = clip.ticks, private val at: (() -> Vec3?)? = null,
        private val hand: ItemStack = ItemStack.EMPTY, private val first: (() -> Unit)? = null, private val on: ((Int) -> Unit)? = null,
    ) : Beat() {
        override fun start(h: Head) {
            h.p.navigation.stop()
            h.hold(hand)
            h.p.act(clip)
            first?.invoke()
        }

        override fun tick(h: Head, t: Int): Boolean {
            at?.invoke()?.let { there ->
                h.p.lookAt(there)
                h.p.face(Mth.approachDegrees(h.p.yBodyRot, h.p.yawTo(there), 18f))
            }
            on?.invoke(t)
            if (clip.loop && h.p.activity != clip && (h.p.activity.loop || h.p.actDone)) h.p.act(clip)
            return if (clip.loop) t < ticks else t < clip.ticks
        }
    }

    /** Something done at once. */
    private class Act(private val what: () -> Unit) : Beat() {
        override fun start(h: Head) = what()

        override fun tick(h: Head, t: Int): Boolean = false
    }

    /** A moment standing, looking at what [at] gives. */
    private class Pause(private val ticks: Int, private val at: (() -> Vec3?)? = null) : Beat() {
        override fun start(h: Head) {
            h.p.navigation.stop()
            if (!h.p.activity.overlay && h.p.activity.loop) h.p.act(Activity.NONE)
        }

        override fun tick(h: Head, t: Int): Boolean {
            at?.invoke()?.let { there ->
                h.p.lookAt(there)
                h.p.face(Mth.approachDegrees(h.p.yBodyRot, h.p.yawTo(there), 18f))
            }
            return t < ticks
        }
    }

    /** An evening on a seat at the inn (the townsfolk's own [Sit]), sipping. */
    private class Seat(private val at: BlockPos) : Beat() {
        private var sit: Sit? = null

        override fun start(h: Head) {
            h.hold(ItemStack.EMPTY)
            sit = Sit(h.p, at, 24000, listOf(Activity.SIT_DRINK, Activity.SIT_DRINK, Activity.SIT)).also { it.start() }
        }

        override fun tick(h: Head, t: Int): Boolean = sit?.tick() == true

        override fun stop(h: Head) {
            sit?.stop()
            sit = null
        }
    }

    /**
     * The master at his works: the director of his townsperson while he's about. What he does is a run of [Beat]s;
     * with none in hand he keeps his post.
     */
    private class Head(val works: Int, val p: Townsperson) : Director {
        var w: WorksRec? = null
        var rec: BuildRec? = null
        var site: TownWorksSite? = null
        var capital: CapitalPiece? = null

        /** His post and the way he faces at it. */
        var post: BlockPos = p.blockPosition()
        var postYaw = p.yRot

        private val beats = ArrayDeque<Beat>()
        private var beat: Beat? = null
        private var t = 0
        private var idle = 0

        /** Off for good (the works opened); off for the evening; a call, the toast or the opening in hand; opening it. */
        var leaving = false
        var inn = false
        var ceremony = false
        var opening = false

        private var spoke = Long.MIN_VALUE / 2
        private var nextThink = 0L
        private val last = HashMap<String, Long>()
        private var gone = false

        /** When he last set off back to his post, and how many times running he hasn't got there. */
        private var homeward = Long.MIN_VALUE / 2
        private var astray = 0

        fun alive(level: ServerLevel): Boolean = p.isAlive && !p.isRemoved && p.level() === level && p.director === this

        fun see(w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece) {
            this.w = w
            this.rec = rec
            this.capital = capital
            if (this.site === site) return
            this.site = site
            val pin = site.drawing.master
            val at = site.frame.world(pin.x, site.drawing.y0 + pin.y, pin.z)
            post = if (site.level.isLoaded(at) && Places.standable(site.level, at)) at else BlockPos.containing(site.gate())
            postYaw = site.frame.direction(pin.facing).toYRot()
        }

        override fun tick(p: Townsperson) {
            val level = p.level() as? ServerLevel ?: return
            while (true) {
                val b = beat
                if (b != null) {
                    if (b.tick(this, t++)) return
                    b.stop(this)
                    beat = null
                }
                val next = beats.removeFirstOrNull() ?: break
                beat = next
                t = 0
                next.start(this)
            }
            if (leaving) {
                vanish()
                return
            }
            if (!inn) keep(level)
        }

        /** A player has a word: hello, and the works' screen. */
        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            val level = p.level() as? ServerLevel ?: return false
            p.lookAt(player)
            if (!ceremony) speak("homestead_hello_${1 + p.random.nextInt(4)}", force = true)
            WorksLedger.open(level, player, works)
            return true
        }

        fun hold(stack: ItemStack) {
            if (!ItemStack.isSameItem(p.mainHandItem, stack) && !(p.mainHandItem.isEmpty && stack.isEmpty)) p.setItemSlot(EquipmentSlot.MAINHAND, stack)
            if (!p.offhandItem.isEmpty) p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY)
        }

        private fun queue(vararg next: Beat) {
            beats.addAll(next)
        }

        /** What he was at dropped (for a call, a ceremony, the evening). */
        private fun cut() {
            beat?.stop(this)
            beat = null
            beats.clear()
        }

        /** Says [line] (a master's line or one of the homestead's) if a player's in hearing: one every [QUIET] ticks unless [force]d. */
        fun speak(line: String, force: Boolean = false): Boolean {
            val now = p.level().gameTime
            if (!force && now - spoke < QUIET) return false
            val ticks = TalkLines.ticks(line).takeIf { it > 0 } ?: 50
            if (!p.say(line, force = true, ticks = ticks + 20L)) return false
            spoke = now
            return true
        }

        private fun due(key: String, every: Long): Boolean {
            val now = p.level().gameTime
            if (now - (last[key] ?: Long.MIN_VALUE / 2) < every) return false
            last[key] = now
            return true
        }

        /** The top of the works, to look up at. */
        private fun top(): Vec3? = site?.box?.let { b -> Vec3((b.minX() + b.maxX() + 1) / 2.0, b.maxY() - 1.0, (b.minZ() + b.maxZ() + 1) / 2.0) }

        // ---- Coming and going ----

        /** Just come: over to his post, and the first day a word as he gets there. */
        fun arrive(first: Boolean) {
            queue(Go(post, 1.0, 1800))
            if (first) queue(Act { speak("homestead_arrive_${1 + p.random.nextInt(3)}", force = true) })
        }

        /** The working day's over: to the inn for the evening (gone once nobody's near). */
        fun evening(level: ServerLevel) {
            if (inn) {
                if (level.getNearestPlayer(p, SEEN) == null) vanish()
                return
            }
            if (ceremony) return
            inn = true
            cut()
            val seat = seat(level)
            if (seat == null) {
                leave(level)
                return
            }
            queue(Seat(seat))
        }

        /** Morning, and still at the inn (someone kept him company all night): back to his post. */
        fun morning() {
            inn = false
            cut()
        }

        /** A free seat at the inn nearest the works. */
        private fun seat(level: ServerLevel): BlockPos? {
            val s = site ?: return null
            val places = capital?.let { CityPlaces.of(level, it) } ?: return null
            val inn = places.nearest(s.box.center, CityPlaces.TAVERNS).firstOrNull() ?: return null
            return inn.seats.map { it.pos }.shuffled().firstOrNull { level.isLoaded(it) && Places.isSeat(level, level.getBlockState(it), it) && Claims.free(level, it, p) }
        }

        /** Off for good, out at the town's gate (or down the street), and gone: at once with nobody to see him go. */
        fun leave(level: ServerLevel) {
            if (leaving) return
            leaving = true
            ceremony = false
            cut()
            val s = site
            val out = s?.let { st -> capital?.let { gate(level, it, st) } ?: st.road() }
            if (out == null || level.getNearestPlayer(p, SEEN) == null) {
                vanish()
                return
            }
            queue(Go(out, 2.0, 1800))
        }

        /** Gone, with a puff of dust. */
        fun vanish() {
            if (gone) return
            gone = true
            cut()
            val level = p.level() as? ServerLevel
            level?.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.8, p.z, 10, 0.3, 0.5, 0.3, 0.02)
            if (p.director === this) p.director = null
            p.discard()
            if (heads[works] === this) heads.remove(works)
        }

        // ---- At his post ----

        /** Nothing in hand: at his post, the drawing in his hands a while, then his arms folded, his eyes on the work. */
        private fun keep(level: ServerLevel) {
            val there = Vec3.atBottomCenterOf(post)
            val now = level.gameTime
            if (p.position().distanceToSqr(there) > 2.25) {
                // A way to his post he can't find (something set down across it) isn't tried every tick: a while
                // where he stands, and after a third go his post is before the door
                if (now - homeward >= 100) {
                    homeward = now
                    if (++astray > 3) {
                        val door = BlockPos.containing(site?.gate() ?: p.position())
                        post = BlockPos(door.x, ground(level, door), door.z)
                        astray = 0
                    }
                    queue(Go(post, 0.8))
                    return
                }
            } else astray = 0
            p.navigation.stop()
            val s = site
            val look = s?.let { WorksBuilder.newest(it.key, level.gameTime)?.let(Vec3::atCenterOf) } ?: top()
            if (look != null) p.lookAt(look)
            p.face(Mth.approachDegrees(p.yBodyRot, postYaw, 6f))
            val phase = idle++ % 400
            when {
                phase == 0 -> {
                    hold(ItemStack(Items.PAPER))
                    p.act(Activity.PLANS)
                }
                phase == 220 -> {
                    hold(ItemStack.EMPTY)
                    p.act(Activity.ARMS_FOLDED)
                }
                // Back to it after a nod or a word
                p.activity != Activity.PLANS && p.activity != Activity.ARMS_FOLDED && (p.activity.loop || p.actDone) ->
                    if (phase < 220) {
                        hold(ItemStack(Items.PAPER))
                        p.act(Activity.PLANS)
                    } else {
                        hold(ItemStack.EMPTY)
                        p.act(Activity.ARMS_FOLDED)
                    }
            }
        }

        /** Each second at his works: a round of his own now and then, the first that wants doing. */
        fun think(level: ServerLevel) {
            if (ceremony || inn || leaving || beat != null || beats.isNotEmpty()) return
            val s = site ?: return
            val now = level.gameTime
            if (now < nextThink) return
            nextThink = now + 100 + p.random.nextInt(200)
            need(level, s) || mortar(level, s) || beam(s) || plumb(level, s) || survey(level, s)
        }

        /** The yard run out: a look in the crate, and a word. */
        private fun need(level: ServerLevel, s: TownWorksSite): Boolean {
            val short = s.short ?: return false
            if (s.reach > 0 || !due("need", 2400)) return false
            val crate = Vec3.atCenterOf(s.crate)
            queue(
                Go(BlockPos.containing(s.crateFront()), 1.0), Face { crate },
                Clip(Activity.MOP_BROW, at = { crate }, first = { speak("homestead_need_${short.key}") }), Pause(30) { crate },
            )
            return true
        }

        /** The tub running low and nobody at it: over to it, pointing, calling for mortar. */
        private fun mortar(level: ServerLevel, s: TownWorksSite): Boolean {
            val tub = WorksYard.tub(level, s.rec) ?: return false
            val be = level.getBlockEntity(tub) as? MortarTubBlockEntity ?: return false
            if (be.mortar >= MortarTubBlockEntity.FULL / 2 || WorksBuilder.mixing(s.key) || !due("mortar", 1800)) return false
            val at = Vec3.atCenterOf(tub)
            queue(
                Go(tub, 3.0), Face { at },
                Clip(Activity.POINT, at = { at }, first = { speak("works_master_mortar_${1 + p.random.nextInt(2)}", force = true) }), Pause(30) { at },
            )
            return true
        }

        /** Two of them carrying a beam across: a word to mind it, and his eyes on it. */
        private fun beam(s: TownWorksSite): Boolean {
            val carrier = WorksBuilder.beam(s.key) ?: return false
            if (!due("beam", 1200)) return false
            val at = { carrier.position().add(0.0, 1.5, 0.0) }
            queue(Face(at), Act { speak("works_master_beam_${1 + p.random.nextInt(2)}", force = true) }, Pause(60, at))
            return true
        }

        /** The newest stone set: his plumb line held to it from the ground below, and a word for it. */
        private fun plumb(level: ServerLevel, s: TownWorksSite): Boolean {
            val stone = WorksBuilder.newest(s.key, level.gameTime) ?: return false
            if (!due("plumb", 1800)) return false
            val foot = BlockPos(stone.x, ground(level, stone), stone.z)
            val stand = listOf(foot.relative(s.out), foot.relative(s.out, 2), foot.relative(s.out.clockWise), foot.relative(s.out.counterClockWise))
                .firstOrNull { Places.standable(level, it) } ?: return false
            val at = Vec3.atCenterOf(stone)
            queue(
                Go(stand, 0.8), Face { at },
                Clip(Activity.PLUMB, at = { at }, on = { t -> if (t == PLUMB_TRUE) speak("works_master_plumb_${1 + p.random.nextInt(2)}") }), Pause(20) { at },
            )
            return true
        }

        /** The lot staked out and its footings going in: off to one of its corners with his staff, and his directions. */
        private fun survey(level: ServerLevel, s: TownWorksSite): Boolean {
            if (s.stage.ordinal > Stage.FOOTINGS.ordinal || !due("survey", 900)) return false
            val b = s.box
            val y = s.drawing.y0
            val corners = listOf(BlockPos(b.minX() - 1, y, b.minZ() - 1), BlockPos(b.maxX() + 1, y, b.minZ() - 1), BlockPos(b.minX() - 1, y, b.maxZ() + 1), BlockPos(b.maxX() + 1, y, b.maxZ() + 1))
                .filter { level.isLoaded(it) && Places.standable(level, it) }
            if (corners.isEmpty()) return false
            val corner = corners[p.random.nextInt(corners.size)]
            val middle = Vec3((b.minX() + b.maxX() + 1) / 2.0, y + 0.5, (b.minZ() + b.maxZ() + 1) / 2.0)
            queue(
                Go(corner, 0.8), Face { middle },
                Clip(Activity.SURVEY, at = { middle }, first = { speak("works_master_survey_${1 + p.random.nextInt(2)}") }), Pause(20) { middle },
            )
            return true
        }

        /** The wagon's freight come in at [at]: he turns and points them to it. */
        fun wagon(level: ServerLevel, at: Vec3) {
            if (ceremony || inn || leaving || !due("wagon", 1200)) return
            cut()
            queue(Face { at }, Clip(Activity.POINT, at = { at }, first = { speak("works_master_wagon_${1 + p.random.nextInt(2)}", force = true) }), Pause(30) { at })
        }

        // ---- His calls and ceremonies ----

        /** [stage] is finished: he calls it, a hurrah for the walls, the roof and the topping out (the crew and the town cheer with him). */
        fun call(level: ServerLevel, stage: Stage) {
            if (ceremony) return
            cut()
            ceremony = true
            val loud = stage == Stage.WALLS || stage == Stage.ROOF || stage == Stage.TOPPING
            val up = { top() }
            queue(
                Face(up),
                Clip(if (loud) Activity.HURRAH else Activity.POINT, at = up, first = {
                    speak("homestead_stage_${stage.key}", force = true)
                    if (stage == Stage.TOPPING) ToppingOut.cheer(level, works)
                }),
                Act { ceremony = false },
            )
        }

        /** The topping out's toast: his cup raised to the town, the crew's with it, the cups meeting once; at the tower, its beacon's first fire. */
        fun toast(level: ServerLevel, s: TownWorksSite, rec: BuildRec, tower: Boolean) {
            ceremony = true
            val up = { top() }
            queue(
                // The crew's cheer for the topping out over first (BuilderCrew: a HURRAH), so they all drink with him
                Pause(TOAST_AFTER, up), Face(up),
                Clip(Activity.TOAST, at = up, first = {
                    speak("works_master_toast_${1 + p.random.nextInt(2)}", force = true)
                    BuilderCrew.join(s.key, Activity.TOAST)
                }, on = { t ->
                    if (t == TOAST_RAISE) {
                        WorksBlocks.play(level, p.blockPosition(), WorksBlocks.TOAST)
                        ToppingOut.cheer(level, works)
                    }
                }),
                Act {
                    if (tower) WorksAtWork.firstFire(level, rec, s, true)
                    ceremony = false
                },
            )
        }

        /**
         * The opening: to the plaque at [plaque] (on the wall facing [facing]), his handbell rung for the town, the
         * cloth pulled off it on the pull, a word to the patron if they're in sight (or to the town), and off.
         */
        fun open(level: ServerLevel, w: WorksRec, plaque: BlockPos, facing: Direction) {
            opening = true
            ceremony = true
            inn = false
            cut()
            val face = Vec3.atCenterOf(plaque)
            val front = plaque.relative(facing, 2)
            val near = plaque.relative(facing)
            val stand = listOf(BlockPos(front.x, ground(level, front), front.z), BlockPos(near.x, ground(level, near), near.z))
                .firstOrNull { level.isLoaded(it) && Places.standable(level, it) } ?: BlockPos.containing(site?.gate() ?: p.position())
            val patron = Works.get(level, w.id)?.patron?.let { level.server.playerList.getPlayer(it) }
                ?.takeIf { it.level() === level && !it.isSpectator && it.distanceToSqr(face) < SEEN * SEEN }
            val listener = { (patron ?: level.getNearestPlayer(p, SEEN))?.eyePosition ?: face }
            queue(
                Go(stand, 0.8, 900), Face { face },
                Clip(Activity.RING_BELL, BELL, at = listener, first = { WorksBlocks.play(level, p.blockPosition(), WorksBlocks.HANDBELL) }),
                Pause(20, listener), Face { face },
                Clip(Activity.UNVEIL, at = { face }, on = { t -> if (t == UNVEIL_PULL) uncover(level, plaque, sound = false) }),
                Face(listener),
                Act { speak(if (patron != null) "works_master_plaque_1" else "works_master_opening_1", force = true) },
                Pause(TalkLines.ticks(if (patron != null) "works_master_plaque_1" else "works_master_opening_1") + 30, listener),
                Act { leave(level) },
            )
        }
    }

    // ---- Helpers ----

    /** The height one would stand at on the ground under [pos]. */
    private fun ground(level: ServerLevel, pos: BlockPos): Int {
        val cursor = pos.mutable()
        var n = 0
        while (n++ < 40 && cursor.y > level.minY) {
            val below = cursor.below()
            val state = level.getBlockState(below)
            if (!state.getCollisionShape(level, below).isEmpty && !state.`is`(Blocks.SCAFFOLDING)) return cursor.y
            cursor.move(Direction.DOWN)
        }
        return pos.y
    }

    // The clips' moments (art/scripts/works_clips.py): the plumb bob still, the cups raised, the cloth's pull
    private const val PLUMB_TRUE = 42
    private const val TOAST_RAISE = 9
    private const val UNVEIL_PULL = 9

    /** Ticks after the topping out's call before the toast: the crew's HURRAH (40) played out. */
    private const val TOAST_AFTER = 16

    /** Ticks of his handbell (two rounds of RING_BELL, the works' handbell's take). */
    private const val BELL = 64
}
