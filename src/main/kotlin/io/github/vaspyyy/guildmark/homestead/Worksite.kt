package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Step
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.homestead.plan.TierPlan
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Somewhere a crew builds (see [BuilderCrew]): a homestead's tier going up ([HomesteadSite]), or one of a capital's
 * town works (economy/works/build/TownWorksSite). What the builders ask of it: the steps of the stage being worked
 * and laying them, the clock's allowance and how far the materials go, where the materials are kept, how the crew
 * comes and goes, and what to tell someone who asks how it's coming along.
 */
interface Worksite {
    val level: ServerLevel

    /** Its crew's key with [BuilderCrew]: a homestead's id (from 0 up); a town works' is -1 - its id. */
    val key: Int

    /** How many builders it has about while they're wanted. */
    val crew: Int

    /** How many of them are strangers made for it ([BuilderCrew] brings them up the road): all of a homestead's; a works' only make up its lent labourers' number. */
    val strangers: Int get() = crew

    /** Its steps, stage by stage (see BuildOrder). */
    val plan: TierPlan

    val total: Int
    val laid: Int
    val finished: Boolean

    /** The stage being worked on: the first with a step still to lay. */
    val stage: Stage

    /** Steps the work clock has allowed and the crew hasn't laid yet. */
    val work: Double

    /** How many of the steps still to lay the materials in hand pay for (see [short]). */
    val reach: Int

    /** The material it ran out of, if it has. */
    val short: Stuff?

    fun isDone(i: Int): Boolean
    fun step(i: Int): Step

    /** Where step [i] is in the world. */
    fun at(i: Int): BlockPos

    /** The block step [i] puts in (what a builder carries for it). */
    fun stateFor(i: Int): BlockState

    /** Whether step [i] can be taken in hand now: in the stage being worked, not done, not someone else's. */
    fun open(i: Int): Boolean

    /** Up to [most] steps for builder [who] to lay together, away from where [others] are working if there's a choice. */
    fun claim(who: Int, others: List<BlockPos>, most: Int = 6): IntArray

    /** Lets go of whatever [who] had in hand, or of step [i] only. */
    fun release(who: Int)
    fun release(who: Int, i: Int)

    /** Where the others are working (the first step each has in hand). */
    fun workingAt(except: Int): List<BlockPos>

    /** Lays step [i] if the clock and the materials allow: [fx] shows it going in; [free] lays it whatever they say. */
    fun lay(i: Int, fx: Boolean, free: Boolean = false): Laid

    /** Whether it's the crew's working hours. */
    fun workday(): Boolean

    /** Where its materials are kept, and where a builder stands to take them out. */
    val crate: BlockPos
    fun crateFront(): Vec3

    /** Where the crew first walks to as they come, and where they come from and go back to. */
    fun gate(): Vec3
    fun road(): BlockPos

    /** The way out of its front: where a builder with nowhere to stand steps back to, and the way they face coming in is its opposite. */
    val out: Direction

    /** What one of the crew tells a player who asks: what is going up, its stage and how far along it is. */
    fun progress(): Component

    /** Whoever calls its stages for the crew, if anyone but one of themselves does (a works' master builder); the crew only cheers then. */
    fun caller(): Townsperson? = null

    /** The first of the morning's crew, [p], coming up from [road]: a homestead's ring their handbell and say they're here. */
    fun arrive(p: Townsperson, road: BlockPos) {
        level.playSound(null, road, HomesteadRegistry.ARRIVE.get(), SoundSource.NEUTRAL, 1.4f, 1f)
        p.say("homestead_arrive_${1 + level.random.nextInt(3)}", force = true)
    }

    /** One of its crew for [p], come from [road] ([lent]: a resident lent to it, not a stranger): a builder, or one of a trade's own (see [BuilderCrew.Builder]). */
    fun builder(p: Townsperson, road: BlockPos, lent: Boolean): BuilderCrew.Builder = BuilderCrew.Builder(p, key, road, lent)
}
