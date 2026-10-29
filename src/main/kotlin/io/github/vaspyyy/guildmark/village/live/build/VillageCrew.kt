package io.github.vaspyyy.guildmark.village.live.build

import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.Worksite
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.days.VillageLife
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A village site's crew (living-world/villages/design.md 12.3): the village's own craftsfolk lent for the day, and strangers from
 * the road making up the number, a foreman among them.
 *
 * - [lent] names the residents lent to the village's site on a day: its citizens with no other duty that day (not a carter, the
 *   bell's holder, the day's poster, a stall's keeper or a keeper of the lamps), a different few each day, as many as the crew wants
 *   less its foreman; none on the rest day, none for a village that has been released (its folk given back), and none before the
 *   site's first morning. Their day ([io.github.vaspyyy.guildmark.village.live.days.VillageDays]) is the site's from seven to noon and
 *   from one to a quarter to six, with their dinner at home between; townsfolk/Labour hands them to the crew as it does a capital's
 *   labourers, and lets them go when the stretch is over. No body is ever made for one: a resident whose body is not about (the village
 *   asleep, their chunk unloaded, their body being swapped) is simply not there, and strangers make up the number.
 * - [Builder] is how any of the crew works at a village: they do not lay a block they cannot see from where they stand when there
 *   is anywhere else to stand, and the strangers spend the noon hour by the gate with their dinner and a word, and go back to it
 *   at one.
 */
object VillageCrew {
    /** The residents of the village at [places] lent to its site on town day [day], by resident id; [taken] are those with a duty that day. */
    fun lent(level: ServerLevel, places: TownPlaces, day: Long, taken: Set<Int>): Set<Int> {
        if (!VillageBuilds.enabled() || !VillageLife.enabled() || Days.restDay(day)) return setOf()
        val centre = places.centre
        val rec = Villages.byKey(level, centre.asLong()) ?: return setOf()
        if (!rec.folk || rec.state == Live.LOST) return setOf()
        val site = VillageBuilds.recFor(level, rec.key) ?: return setOf()
        if (site.rushing) return setOf()
        // Not before its first morning
        if (site.opens != Long.MIN_VALUE && Days.dayOf(site.opens) > day) return setOf()
        val project = io.github.vaspyyy.guildmark.advance.Advance.entries[site.project.coerceIn(0, io.github.vaspyyy.guildmark.advance.Advance.entries.size - 1)]
        val want = VillageSite.crewOf(project) - 1
        if (want <= 0) return setOf()
        val able = Residents.get(level).byCentre(centre)
            .filter { it.alive && it.id >= 0 && it.role == TownRole.CITIZEN && it.id !in taken }
            .map { it.id }.sorted()
        if (able.isEmpty()) return setOf()
        if (able.size <= want) return able.toSet()
        val start = Math.floorMod(day * 7L + site.id * 3L, able.size.toLong()).toInt()
        return List(want) { able[(start + it) % able.size] }.toSet()
    }

    /** How far a hidden place to stand is put behind one that sees the block, when the crew looks for where to stand. */
    private const val HIDDEN = 8.0

    /** One of a village site's crew: a homestead's builder, who does not work through a wall, and has their dinner at noon by the gate. */
    class Builder(p: Townsperson, key: Int, road: BlockPos, lent: Boolean) : BuilderCrew.Builder(p, key, road, lent) {
        private var spot: Vec3? = null
        private var there = false
        private var clip = Activity.NONE
        private var until = 0

        /** A block is laid from where it can be seen: a place it can't be seen from is taken only if there is nowhere better. */
        override fun hidden(level: ServerLevel, eye: Vec3, target: BlockPos): Double = if (sees(level, eye, target)) 0.0 else HIDDEN

        private fun sees(level: ServerLevel, eye: Vec3, target: BlockPos): Boolean {
            val hit = level.clip(ClipContext(eye, Vec3.atCenterOf(target), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
            if (hit.type == HitResult.Type.MISS || hit.blockPos == target) return true
            // Grazing what it stands on is not looking through a wall: a wall in the way is a block's breadth off
            return AABB(target).inflate(0.5).contains(hit.location)
        }

        /** The noon hour: a stranger on the ground downs tools for their dinner (one lent goes home for theirs: Labour lets them go). */
        override fun chores(level: ServerLevel, site: Worksite): Boolean {
            val s = site as? VillageSite ?: return false
            if (lent || aloft || !s.onBreak()) return false
            val gate = s.gate()
            spot = Vec3(gate.x + p.random.nextInt(5) - 2, gate.y, gate.z + p.random.nextInt(5) - 2)
            there = false
            clip = Activity.NONE
            until = 0
            return true
        }

        override fun chore(level: ServerLevel, site: Worksite, t: Int): Boolean {
            val s = site as? VillageSite ?: return false
            if (!s.onBreak()) {
                hold(ItemStack.EMPTY)
                p.act(Activity.NONE)
                if (s.workday() && turn()) p.say("village_build_back")
                return false
            }
            if (!there) {
                if (walkTo(level, spot ?: s.gate(), 1.2) || t > 400) {
                    there = true
                    until = t
                } else return true
            }
            if (t >= until) {
                // Their dinner first, then a word, a stretch and a yawn in the sun
                clip = when {
                    clip == Activity.NONE -> Activity.EAT
                    clip == Activity.EAT && p.random.nextInt(3) != 0 -> Activity.EAT
                    else -> listOf(Activity.TALK, Activity.STRETCH, Activity.YAWN, Activity.ARMS_FOLDED)[p.random.nextInt(4)]
                }
                hold(if (clip == Activity.EAT) ItemStack(Items.BREAD) else ItemStack.EMPTY)
                p.act(clip)
                until = t + clip.ticks * (if (clip.loop) 2 + p.random.nextInt(3) else 1)
                if (clip == Activity.TALK && p.random.nextInt(3) == 0 && turn()) p.say("village_build_dinner_${1 + p.random.nextInt(2)}")
            }
            return true
        }

        override fun released() {
            hold(ItemStack.EMPTY)
        }
    }
}
