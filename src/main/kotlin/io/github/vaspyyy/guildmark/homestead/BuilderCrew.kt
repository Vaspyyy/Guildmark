package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import kotlin.math.abs

/**
 * The crew that builds a homestead: a few townsfolk who come up the road at sunrise while someone is about to see them,
 * and go home at sunset, when the tier is finished, or when nobody is near. They're never saved: one found in a chunk as
 * it loads goes at once ([onJoin]), and the build goes on by the clock while they're away (HomesteadWorks).
 *
 * Each builder takes a few steps of the stage in hand (HomesteadSite.claim), fetches what they're made of from the supply
 * crate, carries it over, climbs up if it goes high, and lays it with the clip for the job: digging the ground, knocking
 * in stakes, setting blocks low, at the chest or overhead, nailing timber and sawing it. They cheer each stage done.
 *
 * A crew builds at any [Worksite]: a homestead's ([HomesteadSite]) or one of a capital's town works
 * (economy/works/build). A works' crew is the town's own citizens lent to it for the day ([lend]: residents, who go
 * back to their day when they're let go, never vanishing), strangers made as a homestead's are only making up the
 * number ([Worksite.strangers]), under a master builder who calls the stages while the crew cheers ([Worksite.caller]);
 * its builders may be a trade's own ([Worksite.builder]), with more to their work than a homestead's.
 */
object BuilderCrew {
    /** Every builder wears this tag, so one saved with its chunk is known for what it is. */
    const val TAG = "guildmark.builder"

    /** How many steps in a row a builder takes in hand. */
    private const val BATCH = 6

    /** The looks that suit the work (no basket on the arm, no stoop). */
    private val LOOKS = intArrayOf(0, 1, 2, 4, 6, 7)

    class Crew(val id: Int) {
        /** The strangers made for it and the residents lent to it, at work. */
        val builders = ArrayList<Builder>()
        var nextArrival = 0L
        var arrived = false

        /** The game time one of them last spoke up, so that two don't say their piece at once (see [QUIET]). */
        var spoke = Long.MIN_VALUE / 2
    }

    /** Ticks after one of a crew speaks up before another may unasked: their lines are voiced, and a crew all talking at once is noise. */
    private const val QUIET = 60L

    private val crews = HashMap<Int, Crew>()

    /** Finds the town works' sites by their crews' keys (economy/works/build sets it); homesteads' are HomesteadWorks'. */
    fun interface Sites {
        fun live(key: Int): Worksite?
    }

    /** The town works' sites (set at setup by economy/works/build). */
    var works: Sites? = null

    /** The site the crew of [key] builds, while it's being built: a homestead's (keys from 0) or a town works' (below 0). */
    fun live(key: Int): Worksite? = if (key >= 0) HomesteadWorks.live(key) else works?.live(key)

    /**
     * Brings [site]'s crew in, one at a time, while [wanted] (someone near, in working hours), or sends them home.
     * Called every second for each homestead being built (and each town works).
     */
    fun update(level: ServerLevel, site: Worksite, wanted: Boolean) {
        val id = site.key
        val crew = crews[id] ?: if (wanted) Crew(id).also { crews[id] = it } else return
        // The lent who aren't the crew's any more (gone, stopped by a player, never handed over) are handed back
        for (b in crew.builders.toList()) if (b.lent && b.gone(level)) b.handBack()
        crew.builders.removeIf { !it.p.isAlive || it.p.isRemoved || it.p.level() !== level }
        if (wanted) {
            if (crew.builders.count { !it.lent } < site.strangers && level.gameTime >= crew.nextArrival) {
                spawn(level, site, crew)
                crew.nextArrival = level.gameTime + 30 + level.random.nextInt(40)
            }
        } else {
            for (b in crew.builders.toList()) b.leave()
            if (crew.builders.isEmpty()) crews.remove(id)
        }
    }

    /** Steps a present crew keeps for themselves to lay (the clock lays anything more at once). */
    fun reserve(id: Int): Int = crews[id]?.builders?.count { !it.leaving }?.times(BATCH + 2) ?: 0

    fun builders(id: Int): List<Builder> = crews[id]?.builders ?: emptyList()

    /** The server has stopped: the crews went with its world. */
    fun reset() {
        crews.clear()
    }

    /** Sends a crew away at once, with no walk home (the homestead is gone, or its tier finished by command). */
    fun dismiss(id: Int) {
        val crew = crews.remove(id) ?: return
        for (b in crew.builders.toList()) b.vanish()
    }

    /** A stage is done: the whole crew downs tools and cheers (a works' master builder calls it, if it has one about: see [Worksite.caller]). */
    fun celebrate(level: ServerLevel, site: Worksite, stage: Stage) {
        val crew = crews[site.key] ?: return
        val loud = stage == Stage.WALLS || stage == Stage.ROOF || stage == Stage.TOPPING
        if (crew.builders.isEmpty()) return
        val master = site.caller()
        if (loud) {
            // One of them calls the stage and the rest cheer it (their lines are voiced: all at once they'd talk over each other)
            val caller = if (master == null) crew.builders.random() else null
            for (b in crew.builders) b.cheer(stage, b === caller)
            val first = crew.builders.first().p
            level.playSound(null, first.blockPosition(), site.cheer(), SoundSource.NEUTRAL, 1.2f, 1f)
        } else if (stage == Stage.GROUNDWORK || stage == Stage.FOOTINGS) {
            if (master == null) crew.builders.random().p.say("homestead_stage_${stage.key}", force = true)
        }
    }

    /**
     * [p], a resident lent to [site] for the day (see townsfolk/Labour), joins its crew: the director they're to work
     * under, or null if the site has no work for them now (no one near to see it, or out of working hours).
     */
    fun lend(level: ServerLevel, site: Worksite, p: Townsperson, wanted: Boolean): Director? {
        if (!wanted || site.finished) return null
        val crew = crews.getOrPut(site.key) { Crew(site.key) }
        crew.builders.firstOrNull { it.p === p }?.let { return it }
        val builder = site.builder(p, site.road(), true)
        builder.since = level.gameTime
        crew.builders.add(builder)
        return builder
    }

    /** [p], lent to a works' crew, is let go (their stretch is over, they were hurt, a player stopped them): whatever they had of the site's is given back. */
    fun released(p: Townsperson) {
        for (crew in crews.values) {
            val b = crew.builders.firstOrNull { it.p === p } ?: continue
            b.handBack()
            return
        }
    }

    /** The crew of [id] downs tools and plays [clip] with whoever leads it (a works' toast: the master raises his cup and they drink), then goes back to work. */
    fun join(id: Int, clip: Activity) {
        val crew = crews[id] ?: return
        for (b in crew.builders) b.join(clip)
    }

    /** The tier is finished: they cheer, and then they go. */
    fun finished(level: ServerLevel, id: Int) {
        val crew = crews[id] ?: return
        val caller = crew.builders.randomOrNull()
        for (b in crew.builders) b.cheer(null, b === caller)
    }

    private fun spawn(level: ServerLevel, site: Worksite, crew: Crew) {
        val road = site.road()
        val look = LOOKS[level.random.nextInt(LOOKS.size)]
        val p = Townsfolk.create(level, road, TownRole.CITIZEN, look, site.out.opposite.toYRot(), EntitySpawnReason.EVENT) ?: return
        p.setPermanentlyInvulnerable(true)
        // Nothing can hurt them, so they don't run off the scaffolding from a passing monster (Townsperson.FleeGoal)
        p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
        p.addTag(TAG)
        p.setDropChance(EquipmentSlot.MAINHAND, 0f)
        p.setDropChance(EquipmentSlot.OFFHAND, 0f)
        val builder = site.builder(p, road, false)
        p.director = builder
        if (!level.addFreshEntity(p)) return
        crew.builders.add(builder)
        // The first of the morning rings the crew's handbell coming up the road
        if (!crew.arrived) {
            crew.arrived = true
            site.arrive(p, road)
        }
    }

    /** A builder from a chunk saved with them in it: gone at once (the crew is brought in afresh when they're wanted). */
    fun onJoin(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (event.loadedFromDisk() && entity is Townsperson && TAG in entity.entityTags()) event.isCanceled = true
    }

    /**
     * One of the crew, at work: the director of their townsperson while they're on the job. A stranger made for it
     * goes home at the end; one [lent] (a resident) is handed back to their day ([handBack]). A trade's own builders
     * (a works', see [Worksite.builder]) add to the work through the open hooks: something to do before a step is
     * laid and after ([dress], [finish]), what laying it sounds like ([laid]), and a job of their own between steps
     * ([chore]).
     */
    open class Builder(val p: Townsperson, protected val id: Int, private val road: BlockPos, val lent: Boolean = false) : Director {
        private enum class S { ARRIVE, IDLE, FETCH, TAKE, GO, CLIMB, WORK, CHEER, LEAVE, CHORE }

        /** When one lent was given to the crew, and whether they've been in its charge since (their first tick under it). */
        internal var since = 0L
        private var started = false

        /** The drop chances of a lent one's hands before the crew had them (what they carry is the site's, not theirs to drop). */
        private var dropMain = -1f
        private var dropOff = -1f

        private var s = S.ARRIVE
        private var timer = 0
        private var batch = IntArray(0)
        private var k = 0
        private var spot: BlockPos? = null
        private var still = 0
        private var last = Vec3.ZERO
        private var pathAt = -100
        /** Off the ground (on scaffolding, a wall, a roof): held up, not falling. */
        private var up = false
        private var climbFrom = Vec3.ZERO
        private var climbTo = Vec3.ZERO
        private var climbLength = 1
        private var afterClimb = S.WORK
        /** What they last fetched from the crate, and how many batches it has done. */
        private var carrying: Stuff? = null
        private var trips = 0
        /** Where they are in laying the step in hand: 0 sawing, 1 laying, 2 nailing. */
        private var phase = 1
        private var nails = 0
        private var cheerAfter = S.IDLE

        /** The clip they're cheering with (a hurrah, or a works' toast), for how long they're at it. */
        private var cheerClip = Activity.HURRAH
        var leaving = false
            private set

        /** Ticks into the step's dressing or finish ([dress], [finish]), and the clip playing for it. */
        private var extra: Activity? = null

        override fun tick(p: Townsperson) {
            val level = p.level() as? ServerLevel ?: return
            if (!started) {
                started = true
                if (lent) {
                    dropMain = p.dropChances.byEquipment(EquipmentSlot.MAINHAND)
                    dropOff = p.dropChances.byEquipment(EquipmentSlot.OFFHAND)
                    p.setDropChance(EquipmentSlot.MAINHAND, 0f)
                    p.setDropChance(EquipmentSlot.OFFHAND, 0f)
                }
            }
            timer++
            val site = live(id)
            // The site gone (finished, removed): they finish any cheer first, and then go
            if (site == null && s != S.LEAVE && s != S.CHEER) leave()
            if (handedBack) return
            when (s) {
                S.ARRIVE -> arrive(level, site ?: return)
                S.IDLE -> idle(level, site ?: return)
                S.FETCH -> fetch(level, site ?: return)
                S.TAKE -> take(level, site ?: return)
                S.GO -> go(level, site ?: return)
                S.CLIMB -> climb()
                S.WORK -> work(level, site ?: return)
                S.CHEER -> if (timer >= cheerClip.ticks) {
                    if (cheerAfter == S.LEAVE) leave() else into(cheerAfter)
                }
                S.LEAVE -> home(level)
                S.CHORE -> if (site == null || !chore(level, site, timer)) into(S.IDLE)
            }
            // Whoever stands on scaffolding or a roof stays put there, held up
            p.isNoGravity = up || s == S.CLIMB
            if (up || s == S.CLIMB) p.deltaMovement = Vec3.ZERO
        }

        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            val level = p.level() as? ServerLevel ?: return false
            val site = live(id) ?: return false
            p.lookAt(player)
            p.say("homestead_hello_${1 + level.random.nextInt(4)}", force = true)
            player.sendOverlayMessage(site.progress())
            return true
        }

        private fun into(next: S) {
            s = next
            timer = 0
            still = 0
        }

        // ---- Coming and going ----

        private fun arrive(level: ServerLevel, site: Worksite) {
            val gate = site.gate()
            hold(ItemStack.EMPTY)
            p.act(Activity.NONE)
            if (walk(level, gate, 2.5) || timer > 600) into(S.IDLE)
        }

        fun leave() {
            if (s == S.LEAVE) return
            leaving = true
            live(id)?.release(p.id)
            batch = IntArray(0)
            if (up) {
                puff(p.level() as ServerLevel)
                val ground = groundUnder(p.level() as ServerLevel, p.blockPosition())
                p.teleportTo(p.x, ground.toDouble(), p.z)
                up = false
            }
            hold(ItemStack.EMPTY)
            p.act(Activity.NONE)
            // A resident goes back to their own day from here; a stranger walks off down the road
            if (lent) {
                handBack()
                return
            }
            into(S.LEAVE)
        }

        /** Whether this one, lent, has been handed back to their day. */
        private var handedBack = false

        /** Whether this one, lent, isn't the crew's any more: gone, taken in charge by something else, or never handed over. */
        internal fun gone(level: ServerLevel): Boolean = !p.isAlive || p.isRemoved || p.level() !== level ||
            if (started) p.director !== this else level.gameTime - since > 100

        /**
         * A resident lent to the crew, handed back to their day: what they had in hand given back to the site, down off
         * the scaffold, their hands empty, and theirs again from the next tick.
         */
        fun handBack() {
            if (handedBack) return
            handedBack = true
            leaving = true
            live(id)?.release(p.id)
            released()
            batch = IntArray(0)
            val level = p.level() as? ServerLevel
            if ((up || s == S.CLIMB) && level != null) {
                puff(level)
                p.teleportTo(p.x, groundUnder(level, p.blockPosition()).toDouble(), p.z)
            }
            up = false
            p.isNoGravity = false
            hold(ItemStack.EMPTY)
            if (dropMain >= 0f) p.setDropChance(EquipmentSlot.MAINHAND, dropMain)
            if (dropOff >= 0f) p.setDropChance(EquipmentSlot.OFFHAND, dropOff)
            crews[id]?.builders?.remove(this)
            if (p.director === this) {
                p.director = null
                if (p.activity != Activity.NONE && !p.activity.overlay) p.act(Activity.NONE)
            }
        }

        private fun home(level: ServerLevel) {
            val there = Vec3.atBottomCenterOf(road)
            val seen = level.getNearestPlayer(p, 40.0) != null
            if (walk(level, there, 2.0) || timer > 500 || !seen) vanish()
        }

        /** Gone, with a puff of dust (a resident lent to the crew is only handed back to their day). */
        fun vanish() {
            if (lent) {
                handBack()
                return
            }
            val level = p.level() as? ServerLevel
            if (level != null) {
                live(id)?.release(p.id)
                puff(level)
            }
            p.director = null
            p.discard()
        }

        /** Downs tools and cheers [stage] (the tier, if null); the one who [calls] it says so. */
        fun cheer(stage: Stage?, calls: Boolean) {
            if (s == S.LEAVE) return
            cheerAfter = if (stage == null) S.LEAVE else if (s == S.WORK || s == S.GO || s == S.FETCH || s == S.TAKE || s == S.CHORE) S.IDLE else s
            if (s == S.WORK || s == S.GO) live(id)?.release(p.id)
            if (s == S.CHORE) released()
            batch = IntArray(0)
            hold(ItemStack.EMPTY)
            into(S.CHEER)
            cheerClip = Activity.HURRAH
            p.act(Activity.HURRAH)
            if (calls) {
                p.say(if (stage == null) "homestead_done_${1 + p.random.nextInt(2)}" else "homestead_stage_${stage.key}", force = true)
                crews[id]?.spoke = p.level().gameTime
            }
            val level = p.level() as ServerLevel
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y + 2.1, p.z, 6, 0.4, 0.3, 0.4, 0.0)
        }

        /** Downs tools and plays [clip] with the rest (a works' toast), then back to what they were at. */
        fun join(clip: Activity) {
            if (s == S.LEAVE || s == S.CHEER || handedBack) return
            cheerAfter = if (s == S.WORK || s == S.GO || s == S.FETCH || s == S.TAKE || s == S.CHORE) S.IDLE else s
            if (s == S.WORK || s == S.GO) live(id)?.release(p.id)
            if (s == S.CHORE) released()
            batch = IntArray(0)
            hold(ItemStack.EMPTY)
            into(S.CHEER)
            cheerClip = clip
            p.act(clip)
        }

        /** Whether this one may speak up now: none of the crew has for [QUIET] ticks (and then it's their turn). */
        protected fun turn(): Boolean {
            val crew = crews[id] ?: return true
            val now = p.level().gameTime
            if (now - crew.spoke < QUIET) return false
            crew.spoke = now
            return true
        }

        // ---- Between jobs ----

        private fun idle(level: ServerLevel, site: Worksite) {
            if (!site.workday()) {
                leave()
                return
            }
            if (timer % 10 == 1) {
                // A trade's own job comes first when it has one (mortar to mix, a beam to carry)
                if (chores(level, site)) {
                    into(S.CHORE)
                    return
                }
                val got = site.claim(p.id, site.workingAt(p.id), BATCH)
                if (got.isNotEmpty()) {
                    batch = got
                    k = 0
                    val stuff = got.map { site.step(it).stuff }.firstOrNull { it != Stuff.FREE }
                    val crate = Vec3.atCenterOf(site.crate)
                    val far = crate.distanceTo(Vec3.atCenterOf(site.at(got[0]))) > 40
                    if (stuff != null && !far && (stuff != carrying || trips >= 3)) {
                        carrying = stuff
                        trips = 0
                        into(S.FETCH)
                    } else {
                        trips++
                        startGo(level, site)
                    }
                    return
                }
            }
            // Nothing to hand: waiting on the crate, or on the others to finish the stage
            val short = site.short
            if (short != null && site.reach <= 0) {
                val by = site.crateFront()
                if (walk(level, by, 1.6)) {
                    p.face(p.yawTo(Vec3.atCenterOf(site.crate)))
                    if (timer % 300 == 40) {
                        if (turn()) p.say("homestead_need_${short.key}", force = true)
                        p.act(Activity.MOP_BROW)
                    }
                }
                return
            }
            if (timer % 160 == 1) {
                hold(if (p.random.nextInt(2) == 0) ItemStack(Items.PAPER) else ItemStack.EMPTY)
                p.act(if (p.mainHandItem.`is`(Items.PAPER)) Activity.PLANS else Activity.NONE)
            }
            if (timer % 400 == 200 && turn()) p.say("homestead_wait_${1 + p.random.nextInt(3)}")
        }

        private fun fetch(level: ServerLevel, site: Worksite) {
            if (batch.all(site::isDone)) {
                into(S.IDLE)
                return
            }
            hold(ItemStack.EMPTY)
            p.act(Activity.NONE)
            if (walk(level, site.crateFront(), 1.4) || timer > 400) {
                into(S.TAKE)
                p.navigation.stop()
                p.face(p.yawTo(Vec3.atCenterOf(site.crate)))
                p.act(Activity.TAKE)
                level.playSound(null, site.crate, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5f, 1f)
            }
        }

        private fun take(level: ServerLevel, site: Worksite) {
            p.face(p.yawTo(Vec3.atCenterOf(site.crate)))
            if (timer == TAKE_GRAB) hold(itemFor(site, batch.firstOrNull { !site.isDone(it) } ?: batch[0]))
            if (timer >= Activity.TAKE.ticks) {
                level.playSound(null, site.crate, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.5f, 1f)
                startGo(level, site)
            }
        }

        // ---- Getting to the work ----

        private fun startGo(level: ServerLevel, site: Worksite) {
            while (k < batch.size && site.isDone(batch[k])) k++
            if (k >= batch.size) {
                done(level, site)
                return
            }
            val target = site.at(batch[k])
            val stand = standFor(level, target)
            spot = stand
            if (stand == null) {
                // Nowhere to stand within reach: they do it from the nearest ground and make the best of it
                spot = BlockPos(target.x, groundUnder(level, target), target.z).relative(site.out)
            }
            into(S.GO)
        }

        private fun go(level: ServerLevel, site: Worksite) {
            val stand = spot ?: return into(S.IDLE)
            val item = p.mainHandItem
            // Timber on the shoulder, anything else carried before them
            p.act(
                when {
                    item.`is`(ItemTags.PLANKS) || item.`is`(ItemTags.LOGS) || item.`is`(ItemTags.WOODEN_STAIRS) || item.`is`(ItemTags.WOODEN_SLABS) -> Activity.HAUL
                    item.item is BlockItem -> Activity.CARRY_BLOCK
                    else -> Activity.NONE
                }.let { carrying(item) ?: it },
            )
            val goal = Vec3.atBottomCenterOf(stand)
            val ground = groundUnder(level, stand)
            val high = stand.y - ground >= 2
            if (up) {
                // From one high place to the next: a careful step across
                climbTo(goal, S.WORK)
                return
            }
            val foot = if (high) Vec3(goal.x, ground.toDouble(), goal.z) else goal
            if (walk(level, foot, if (high) 1.3 else 0.7) || timer > 300) {
                if (high || p.position().distanceTo(goal) > 1.5) climbTo(goal, S.WORK) else startWork()
            }
        }

        private fun climbTo(goal: Vec3, then: S) {
            p.navigation.stop()
            climbFrom = p.position()
            climbTo = goal
            val distance = climbFrom.distanceTo(goal)
            climbLength = (distance / CLIMB_SPEED).toInt().coerceIn(4, 120)
            afterClimb = then
            into(S.CLIMB)
            p.act(if (abs(goal.y - climbFrom.y) > 1.2) Activity.CLAMBER else Activity.NONE)
        }

        private fun climb() {
            val t = (timer.toDouble() / climbLength).coerceIn(0.0, 1.0)
            val e = t * t * (3 - 2 * t)
            val x = climbFrom.x + (climbTo.x - climbFrom.x) * e
            val z = climbFrom.z + (climbTo.z - climbFrom.z) * e
            // Up first and across after, going up; across and then down, coming down
            val rising = climbTo.y > climbFrom.y
            val ty = if (rising) (t * 1.4).coerceAtMost(1.0) else ((t - 0.3) / 0.7).coerceAtLeast(0.0)
            val y = climbFrom.y + (climbTo.y - climbFrom.y) * (ty * ty * (3 - 2 * ty))
            p.setPos(x, y, z)
            if (timer >= climbLength) {
                p.setPos(climbTo.x, climbTo.y, climbTo.z)
                val level = p.level() as ServerLevel
                up = climbTo.y - groundUnder(level, BlockPos.containing(climbTo)) >= 1.5
                if (afterClimb == S.WORK) startWork() else into(afterClimb)
            }
        }

        // ---- The work ----

        private fun startWork() {
            p.navigation.stop()
            into(S.WORK)
            phase = -1
        }

        private fun work(level: ServerLevel, site: Worksite) {
            while (k < batch.size && site.isDone(batch[k])) k++
            if (k >= batch.size) {
                done(level, site)
                return
            }
            val i = batch[k]
            val target = site.at(i)
            val centre = Vec3.atCenterOf(target)
            // Out of reach from here: over to it first
            if (p.eyePosition.distanceTo(centre) > REACH + 0.8) {
                startGo(level, site)
                return
            }
            p.face(p.yawTo(centre))
            p.lookAt(centre)
            val step = site.step(i)
            if (phase < 0) {
                // A new step: what it takes (a stone dressed first, at a works)
                phase = if (sawn(site, i) && p.random.nextInt(3) == 0) 0 else if (!sawn(site, i) && dress(site, i)?.also { extra = it } != null) 4 else 1
                nails = 0
                timer = 0
                begin(site, i, target)
            }
            val clip = activityFor(step.kind, target)
            when (phase) {
                0 -> {
                    // Sawing the plank to length first
                    if (beat(Activity.SAW) == SAW_STROKE) level.playSound(null, p.blockPosition(), HomesteadRegistry.SAW.get(), SoundSource.NEUTRAL, 0.8f, 0.9f + p.random.nextFloat() * 0.2f)
                    if (timer >= Activity.SAW.ticks * 2) {
                        phase = 1
                        timer = 0
                        begin(site, i, target)
                    }
                }
                1 -> if (beat(clip) == contact(clip)) {
                    when (site.lay(i, fx = true)) {
                        Laid.DONE -> {
                            if (!ownSound(site, i)) sound(level, step.kind)
                            laid(level, site, i)
                            if (p.random.nextInt(40) == 0 && turn()) p.say("homestead_work_${1 + p.random.nextInt(6)}")
                            if (sawn(site, i)) {
                                phase = 2
                                timer = 0
                                hold(ItemStack(HomesteadRegistry.HAMMER_ITEM.get()))
                                p.act(Activity.NAIL)
                            } else {
                                // The course finished off (a works' stone trowelled), or on to the next
                                val after = finish(site, i)
                                if (after != null) {
                                    extra = after
                                    phase = 5
                                    timer = 0
                                    p.act(after)
                                } else next()
                            }
                        }
                        Laid.LATER -> {
                            // The day's pace: finishing touches while the clock catches up
                            phase = 3
                            timer = 0
                            hold(ItemStack(HomesteadRegistry.HAMMER_ITEM.get()))
                            p.act(Activity.NAIL)
                        }
                        Laid.NEED -> {
                            site.release(p.id)
                            batch = IntArray(0)
                            if (turn()) p.say("homestead_need_${(site.short ?: step.stuff).key}", force = true)
                            into(S.IDLE)
                        }
                        Laid.WAIT -> {
                            site.release(p.id, i)
                            next()
                        }
                        Laid.BLOCKED -> {
                            site.release(p.id, i)
                            if (turn()) p.say("homestead_blocked", force = true)
                            next()
                        }
                    }
                } else if (timer > contact(clip) + 40) {
                    // Never stuck on one step
                    begin(site, i, target)
                }
                2 -> {
                    // Nailing it home
                    if (timer % Activity.NAIL.ticks == NAIL_HIT) {
                        level.playSound(null, target, HomesteadRegistry.HAMMER.get(), SoundSource.BLOCKS, 0.7f, 0.9f + p.random.nextFloat() * 0.25f)
                        nails++
                    }
                    if (nails >= 2 && timer % Activity.NAIL.ticks == 0) next()
                }
                3 -> {
                    if (timer % Activity.NAIL.ticks == NAIL_HIT) level.playSound(null, target, HomesteadRegistry.HAMMER.get(), SoundSource.BLOCKS, 0.5f, 1.1f)
                    if (timer >= Activity.NAIL.ticks * 2 && site.work >= 1.0) begin(site, i, target)
                }
                // A trade's dressing before the step (a stone chiselled), and its finish after (the course trowelled)
                4 -> {
                    val clip = extra ?: Activity.NONE
                    stroke(level, site, i, clip, beat(clip))
                    if (timer >= extraTicks(clip)) {
                        phase = 1
                        timer = 0
                        extra = null
                        begin(site, i, target)
                    }
                }
                5 -> {
                    val clip = extra ?: Activity.NONE
                    stroke(level, site, i, clip, beat(clip))
                    if (timer >= extraTicks(clip)) {
                        extra = null
                        next()
                    }
                }
            }
        }

        /** Starts laying step [i]: the right thing in hand and the clip for it. */
        private fun begin(site: Worksite, i: Int, target: BlockPos) {
            val step = site.step(i)
            if (phase == 0) {
                // The saw in the right hand, the length being cut held out in the left
                hold(ItemStack(HomesteadRegistry.SAW_ITEM.get()), itemFor(site, i))
                p.act(Activity.SAW)
                return
            }
            if (phase == 4) {
                // The trade's dressing: its tool in hand (a chisel and mallet), the block held to it
                hold(tool(extra) ?: ItemStack(HomesteadRegistry.MALLET_ITEM.get()), itemFor(site, i))
                extra?.let { p.act(it) }
                return
            }
            phase = 1
            timer = 0
            hold(
                when (step.kind) {
                    StepKind.GROUND, StepKind.BANK -> ItemStack(Items.IRON_SHOVEL)
                    StepKind.STAKE, StepKind.PULL_STAKE -> ItemStack(HomesteadRegistry.MALLET_ITEM.get())
                    StepKind.SPAWN -> ItemStack.EMPTY
                    else -> itemFor(site, i)
                },
            )
            p.act(activityFor(step.kind, target))
        }

        private fun next() {
            k++
            phase = -1
        }

        private fun done(level: ServerLevel, site: Worksite) {
            batch = IntArray(0)
            hold(ItemStack.EMPTY)
            if (up) {
                // Next job close by and up here too? Otherwise back down
                val got = site.claim(p.id, site.workingAt(p.id), BATCH)
                if (got.isNotEmpty()) {
                    batch = got
                    k = 0
                    val stand = standFor(level, site.at(got[0]))
                    if (stand != null && Vec3.atBottomCenterOf(stand).distanceTo(p.position()) < 6.0 && stand.y - groundUnder(level, stand) >= 2) {
                        spot = stand
                        hold(itemFor(site, got[0]))
                        climbTo(Vec3.atBottomCenterOf(stand), S.WORK)
                        return
                    }
                    site.release(p.id)
                    batch = IntArray(0)
                }
                val down = Vec3(p.x, groundUnder(level, p.blockPosition()).toDouble(), p.z)
                up = false
                climbTo(down, S.IDLE)
                return
            }
            into(S.IDLE)
        }

        private fun activityFor(kind: StepKind, target: BlockPos): Activity = when (kind) {
            StepKind.GROUND, StepKind.BANK -> Activity.DIG
            StepKind.STAKE, StepKind.PULL_STAKE -> Activity.STAKE
            StepKind.SPAWN -> Activity.BECKON
            else -> {
                val dy = target.y - p.blockPosition().y
                when {
                    dy <= -1 -> Activity.LAY_LOW
                    dy >= 2 -> Activity.LAY_HIGH
                    else -> Activity.LAY
                }
            }
        }

        /** Where [clip] is in its round. A loop already playing isn't restarted for the next step (Townsperson.act),
         *  so a dig or a knock comes round on the clip's own count, not the step's. */
        private fun beat(clip: Activity): Int = if (clip.loop) p.actTicks % clip.ticks else timer

        /** The tick of [clip] the block goes in. */
        private fun contact(clip: Activity): Int = when (clip) {
            Activity.DIG -> DIG_BITE
            Activity.STAKE -> STAKE_HIT
            Activity.BECKON -> 15
            Activity.LAY_LOW -> LAY_LOW_AT
            Activity.LAY_HIGH -> LAY_HIGH_AT
            else -> LAY_AT
        }

        private fun sound(level: ServerLevel, kind: StepKind) {
            val pos = p.blockPosition()
            when (kind) {
                StepKind.GROUND, StepKind.BANK -> level.playSound(null, pos, HomesteadRegistry.DIG.get(), SoundSource.NEUTRAL, 0.7f, 0.9f + p.random.nextFloat() * 0.2f)
                StepKind.STAKE, StepKind.PULL_STAKE -> level.playSound(null, pos, HomesteadRegistry.STAKE.get(), SoundSource.NEUTRAL, 0.8f, 1f)
                else -> {}
            }
        }

        /** Whether step [i] is timber cut to fit (planks, beams, stairs and slabs of wood, frames): sawn and nailed. */
        private fun sawn(site: Worksite, i: Int): Boolean {
            val step = site.step(i)
            if (step.kind != StepKind.BLOCK) return false
            return when (step.b.m) {
                M.PLANKS, M.PLANK_STAIRS, M.PLANK_SLAB, M.TIMBER, M.SHUTTER, M.TRAPDOOR, M.FENCE, M.FENCE_GATE, M.LADDER -> true
                else -> false
            }
        }

        /** What they carry for step [i]: the block itself where it has an item, or the hammer. */
        protected fun itemFor(site: Worksite, i: Int): ItemStack {
            val item = site.stateFor(i).block.asItem()
            return if (item == Items.AIR) ItemStack(HomesteadRegistry.HAMMER_ITEM.get()) else ItemStack(item)
        }

        // ---- A trade's own work (a works' crew): nothing, at a homestead ----

        /** The clip step [i] is dressed with before it's laid (a stone chiselled), or null to lay it as it comes. */
        protected open fun dress(site: Worksite, i: Int): Activity? = null

        /** The clip step [i] is finished with once laid (a stone's course trowelled), or null to go on to the next. */
        protected open fun finish(site: Worksite, i: Int): Activity? = null

        /** How long a dressing or finishing clip plays. */
        protected open fun extraTicks(clip: Activity): Int = clip.ticks

        /** The tool held for a dressing clip, if not the mallet. */
        protected open fun tool(clip: Activity?): ItemStack? = null

        /** Tick [beat] of a dressing or finishing [clip] at step [i]: its strokes' sounds. */
        protected open fun stroke(level: ServerLevel, site: Worksite, i: Int, clip: Activity, beat: Int) {}

        /** Step [i] has just gone in, by their hand. */
        protected open fun laid(level: ServerLevel, site: Worksite, i: Int) {}

        /** The clip they carry [item] over in, if not as a homestead's builder would (a beam on the shoulder, two to it). */
        protected open fun carrying(item: ItemStack): Activity? = null

        /** Whether they've a job of their own to start between steps (mortar to mix, a beam to carry); if so [chore] runs it. */
        protected open fun chores(level: ServerLevel, site: Worksite): Boolean = false

        /** Tick [t] of that job; false once it's over (and they look for work again). */
        protected open fun chore(level: ServerLevel, site: Worksite, t: Int): Boolean = false

        /** Their job between steps ended early (a cheer, sent home): anything it held let go. */
        protected open fun released() {}

        /** Whether step [i], just laid, made its own sound going in (a works' stake), so the homestead's dig or knock isn't played. */
        protected open fun ownSound(site: Worksite, i: Int): Boolean = false

        /** Walks toward [goal] (see [walk]), for a trade's own jobs. */
        protected fun walkTo(level: ServerLevel, goal: Vec3, near: Double): Boolean = walk(level, goal, near)

        /** Whether they're up on the scaffold or a roof. */
        protected val aloft: Boolean get() = up

        /** [stack] in the right hand and [off] in the left (nothing, but for the length being sawn). */
        protected fun hold(stack: ItemStack, off: ItemStack = ItemStack.EMPTY) {
            val was = p.offhandItem
            if (!(was.isEmpty && off.isEmpty) && !ItemStack.isSameItem(was, off)) p.setItemSlot(EquipmentSlot.OFFHAND, off)
            val now = p.mainHandItem
            if (ItemStack.isSameItem(now, stack) || now.isEmpty && stack.isEmpty) return
            p.setItemSlot(EquipmentSlot.MAINHAND, stack)
        }

        // ---- Finding the way ----

        /** Walks toward [goal]; true once within [near] of it. Stuck for long, they turn up there in a puff of dust. */
        private fun walk(level: ServerLevel, goal: Vec3, near: Double): Boolean {
            val here = p.position()
            val dx = here.x - goal.x
            val dz = here.z - goal.z
            if (dx * dx + dz * dz < near * near && abs(here.y - goal.y) < 1.6) {
                p.navigation.stop()
                return true
            }
            if (here.distanceToSqr(last) < 0.0025) still++ else still = 0
            last = here
            if (still > STUCK) {
                puff(level)
                p.teleportTo(goal.x, goal.y, goal.z)
                puff(level)
                still = 0
                return true
            }
            if (p.navigation.isDone || timer - pathAt > 40) {
                pathAt = timer
                p.navigation.moveTo(goal.x, goal.y, goal.z, SPEED)
            }
            return false
        }

        /**
         * Somewhere to stand to lay [target]: a spot within reach with room for them and something under their feet,
         * as near the block's own height as can be found, and nearest where they are.
         */
        private fun standFor(level: ServerLevel, target: BlockPos): BlockPos? {
            var best: BlockPos? = null
            var bestScore = Double.MAX_VALUE
            val here = p.position()
            val cursor = BlockPos.MutableBlockPos()
            for (dy in -3..1) for (dx in -3..3) for (dz in -3..3) {
                if (dx == 0 && dz == 0) continue
                cursor.set(target.x + dx, target.y + dy - 1, target.z + dz)
                if (!standable(level, cursor)) continue
                val eye = Vec3(cursor.x + 0.5, cursor.y + 1.6, cursor.z + 0.5)
                val reach = eye.distanceTo(Vec3.atCenterOf(target))
                if (reach > REACH) continue
                val ground = groundUnder(level, cursor)
                val high = cursor.y - ground
                val score = abs(dy + 0.5) * 1.5 + reach + here.distanceTo(Vec3.atBottomCenterOf(cursor)) * 0.15 + high * 0.6
                if (score < bestScore) {
                    bestScore = score
                    best = cursor.immutable()
                }
            }
            return best
        }

        /** Room for a builder at [pos] (feet and head) and something to stand on. */
        private fun standable(level: ServerLevel, pos: BlockPos): Boolean {
            val feet = level.getBlockState(pos)
            val head = level.getBlockState(pos.above())
            if (!feet.getCollisionShape(level, pos).isEmpty || !feet.fluidState.isEmpty) return false
            if (!head.getCollisionShape(level, pos.above()).isEmpty) return false
            val under = level.getBlockState(pos.below())
            return under.`is`(Blocks.SCAFFOLDING) || under.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP) ||
                under.block is net.minecraft.world.level.block.StairBlock || under.block is net.minecraft.world.level.block.SlabBlock
        }

        /** The height one would stand at on the ground under [pos]. */
        protected fun groundUnder(level: ServerLevel, pos: BlockPos): Int {
            val cursor = pos.mutable()
            var n = 0
            while (n++ < 40 && cursor.y > level.minY) {
                val below = cursor.below()
                val state = level.getBlockState(below)
                if (!state.getCollisionShape(level, below).isEmpty && !state.`is`(Blocks.SCAFFOLDING)) return cursor.y
                cursor.move(net.minecraft.core.Direction.DOWN)
            }
            return pos.y
        }

        protected fun puff(level: ServerLevel) {
            level.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.8, p.z, 10, 0.3, 0.5, 0.3, 0.02)
        }

        companion object {
            private const val SPEED = 0.75
            private const val REACH = 4.6
            private const val CLIMB_SPEED = 0.14
            private const val STUCK = 120

            // The ticks of the clips the tool or the block meets the work (art/scripts/homestead_anims.py)
            const val LAY_AT = 11
            const val LAY_LOW_AT = 14
            const val LAY_HIGH_AT = 12
            const val DIG_BITE = 10
            const val STAKE_HIT = 9
            const val NAIL_HIT = 5
            const val SAW_STROKE = 6
            const val TAKE_GRAB = 12
        }
    }
}
