package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Work
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap

/**
 * The market as the times go (living-world/design-influence.md section 5, "A busier market"), at the stalls: in good
 * times shoppers buy more (an armful home rather than a basket, and now and then a second stall on the way); in lean
 * times some haggle (HAGGLE: points counted off on the fingers, then a flat hand), and in hard times all of them do,
 * and one in three walks off without; and at a stall sold out, a hard-up shopper turns their empty basket over and
 * shakes it (EMPTY_BASKET) where another would shrug. The crowds' share as the times go is
 * [io.github.vaspyyy.guildmark.townsfolk.Crowds.wanted]'s.
 *
 * [io.github.vaspyyy.guildmark.townsfolk.Acts]' stall calls [at] once it's picked a stall, but only while [times] gives
 * the times: with the streets' scenes off it's null, and the stall is shopped at just as before.
 */
internal object Shopping {
    /** The times a shopper shops by for `/guildmark capital scene` (haggle, basket, plenty), whatever the record says, until a game time. */
    private val forced = WeakHashMap<Townsperson, Pair<Times, Long>>()

    /**
     * The times [p] shops by: the capital's, with the streets' scenes on; null with them off (the stall as it always
     * was). Draws nothing at random.
     */
    fun times(p: Townsperson, life: Life): Times? {
        if (forced.isNotEmpty()) forced[p]?.let { (times, until) -> if (p.level().gameTime <= until) return times else forced.remove(p) }
        if (!Fortunes.enabled()) return null
        val level = p.level() as? ServerLevel ?: return null
        return Fortunes.times(level, life.places.capital.center.asLong())
    }

    /** For the scene command: [p] shops by [times] for the next [ticks]. */
    fun force(p: Townsperson, times: Times, ticks: Long) {
        forced[p] = times to p.level().gameTime + ticks
    }

    private fun hard(times: Times): Boolean = times == Times.HARD || times == Times.LEAN

    private fun good(times: Times): Boolean = times == Times.GOOD || times == Times.THRIVING

    /**
     * [p]'s visit to [stall] (whose [wares] are what it has left) as [times] go: a purchase, with haggling in hard
     * times; or at one sold out, a look, the empty basket shaken out or a shrug, and on to another ([next]: Acts'
     * stall but this one) if this was the first stall they tried. [next] is null at the second stall (one sold out
     * then sends them home). Null if there's nothing to be done here.
     */
    fun at(p: Townsperson, life: Life, stall: CityPlaces.Stall, wares: List<Item>, times: Times, next: (() -> Task?)?): Task? {
        val level = p.level() as ServerLevel
        val r = p.random
        val middle = stall.counter.getOrNull(stall.counter.size / 2) ?: stall.keeper.pos
        if (wares.isEmpty()) {
            if (next == null) return null
            val steps = if (hard(times)) listOf(Step(Activity.BROWSE, 50 + r.nextInt(30)), Step(Activity.EMPTY_BASKET), Step(Activity.SIGH), Step(Activity.NONE, 10))
            else listOf(Step(Activity.BROWSE, 60 + r.nextInt(40)), Step(Activity.SHRUG), Step(Activity.NONE, 10))
            val looking = Work(p, stall.customer, Jobs.top(middle, 1.0), steps, onTick = { step, t ->
                // The keeper's shrug back: nothing to be had
                if (t == 0 && (step.activity == Activity.SHRUG || step.activity == Activity.EMPTY_BASKET)) Acts.serve(Acts.keeper(level, stall), p, Activity.SHRUG)
            })
            return Acts.via(p, life, stall.customer.pos, looking).andThen(Later(p) { next() })
        }
        return buy(p, life, stall, wares, times, next)
    }

    /** The purchase itself: a look, a point, a haggle in hard times (and in the hardest, no sale), the coin and the goods. */
    private fun buy(p: Townsperson, life: Life, stall: CityPlaces.Stall, wares: List<Item>, times: Times, next: (() -> Task?)?): Task {
        val level = p.level() as ServerLevel
        val r = p.random
        val middle = stall.counter.getOrNull(stall.counter.size / 2) ?: stall.keeper.pos
        val count = when (times) {
            Times.THRIVING -> 2 + r.nextInt(2)
            Times.GOOD -> 2
            else -> 1
        }
        val goods = ItemStack(wares[r.nextInt(wares.size)], count)
        val haggle = times == Times.HARD || times == Times.LEAN && r.nextInt(2) == 0
        val noSale = times == Times.HARD && r.nextInt(3) == 0
        val steps = ArrayList<Step>()
        steps += Step(Activity.BROWSE, 60 * (2 + r.nextInt(2)))
        steps += Step(Activity.POINT)
        if (haggle) steps += Step(Activity.HAGGLE)
        if (noSale) {
            steps += Step(Activity.SHRUG)
            steps += Step(Activity.SIGH)
        } else {
            steps += Step(Activity.PAY)
            steps += Step(Activity.RECEIVE)
            steps += Step(Activity.NOD)
        }
        var keeper: Entity? = null
        val buying = Work(p, stall.customer, Jobs.top(middle, 1.0), steps, onTick = { step, t ->
            if (t == 0) when (step.activity) {
                Activity.POINT -> {
                    keeper = Acts.keeper(level, stall)
                    Acts.serve(keeper, p, Activity.NOD)
                }
                // The keeper weighs the offer up; in the hardest times, and this once, they won't come down
                Activity.HAGGLE -> Acts.serve(keeper, p, Activity.THINK)
                Activity.SHRUG -> {
                    Acts.serve(keeper, p, Activity.SHRUG)
                    if (r.nextInt(2) == 0) Words.say(p, "fortune.hard", "a sale lost to the hard times")
                }
                Activity.PAY -> Acts.serve(keeper, p, Activity.RECEIVE, ItemStack(ModItems.GUILD_MARK.get()))
                Activity.RECEIVE -> Acts.serve(keeper, p, Activity.HAND_OVER, goods)
                else -> {}
            }
            if (step.activity == Activity.RECEIVE && t == Acts.PASS) p.carried = goods.copy()
        })
        val visit = Acts.via(p, life, stall.customer.pos, buying)
        // Good times: on to another stall now and then, the first lot in the basket meanwhile (and back in hand if
        // nothing more's to be had there)
        if (!noSale && good(times) && next != null && r.nextInt(2) == 0) {
            visit.andThen(Later(p) {
                val first = p.carried
                if (first.isEmpty) return@Later null
                p.carried = ItemStack.EMPTY
                val more = next()
                if (more == null) p.carried = first else p.act(Activity.CARRY_BASKET)
                more?.andThen(Then(p) { if (p.carried.isEmpty) p.carried = first })
            })
        }
        return visit
    }

    /** Something seen to at once between two tasks. */
    private class Then(p: Townsperson, private val what: () -> Unit) : Task(p) {
        override val instant: Boolean get() = true
        override fun start() = what()
        override fun run(): Boolean = false
    }

    /** Whether [goods] are an armful (good times' shopping), carried home in both arms rather than in the basket. */
    fun armful(goods: ItemStack): Boolean = goods.count >= 2
}
