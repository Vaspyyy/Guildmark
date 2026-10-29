package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The siege's mobs, as a village's folk see them (living-world/villages/design.md section 11.1), and the siege's beats in a village
 * of the registry. The name and signature of [arm] are the contract of design section 19.3.
 *
 * `Sieges` calls in at five places, each a line: the horn ([started]: the shelter called, the bell rung hard, the watch to the wall),
 * each mob of a wave ([armAt]: the horde goes after the village's townsfolk, as a waylaying band does a caravan's, with `mustSee`
 * so those behind a wall are safe), a later wave ([wave]: anyone come out goes back in), a death (a resident counts as a villager
 * does, [folkOf]), and the end ([held]: the fortunes, the chronicle, the all-clear and the cheer; [lost]: the same, the damage list
 * and the mend set). For a capital, a bell no village of the registry has, or with trouble off, every one of them does nothing, and
 * none of them asks the level's random, so the siege runs as it did.
 */
object SiegeHorde {
    /** A siege on a village of the registry, while it lasts: where it came from and the mobs it armed. */
    private class On(val key: Long, val bell: BlockPos) {
        var from: Direction? = null
        var gather: BlockPos? = null
        val mobs = HashSet<UUID>()
    }

    /** The sieges on villages under way, by the village's key; memory only, as the sieges are. */
    private val on = HashMap<Long, On>()

    /** Arms [mob], one of the siege on the village [key], with the goal of going after its townsfolk. */
    fun arm(mob: Mob, key: Long) {
        if (!Trouble.on()) return
        goal(mob, key)
        // The first wave is set down before the horn: its siege is known from its first mob
        on.getOrPut(key) { On(key, BlockPos.of(key)) }.mobs.add(mob.uuid)
    }

    /** The goal of going after the village [key]'s townsfolk, given to [mob] (a siege's, or a Bad Omen raider: [RaidWatch]); `mustSee`, so those behind a wall are safe. */
    internal fun goal(mob: Mob, key: Long) {
        val level = mob.level() as? ServerLevel ?: return
        mob.targetSelector.addGoal(3, NearestAttackableTargetGoal(mob, Townsperson::class.java, 10, true, false) { e, _ -> prey(level, e, key) })
    }

    /** One of the village [key]'s folk, out to be found: a resident (or a visitor of its town), not a faction's own, a tale's, or a captive. */
    private fun prey(level: ServerLevel, e: LivingEntity, key: Long): Boolean {
        val p = e as? Townsperson ?: return false
        if (!p.isAlive || p.isLeader || p.tale >= 0 || p.captiveOf >= 0) return false
        return Trouble.folkOf(level, p, key)
    }

    /** [mob] of the siege on the village round [bell], set down in a wave: armed against its folk, if it is a village of the registry. */
    fun armAt(level: ServerLevel, mob: Mob, bell: BlockPos) {
        val rec = Trouble.village(level, bell) ?: return
        arm(mob, rec.key)
    }

    /**
     * The horn has sounded for a siege on the village round [bell], coming from [from]: the shelter is called (the folk within its
     * radius and eight more), the bell rung hard, the watch sent to that side.
     */
    fun started(level: ServerLevel, bell: BlockPos, from: Direction) {
        val rec = Trouble.village(level, bell) ?: return
        val key = rec.key
        val centre = BlockPos.of(key)
        val gather = centre.relative(from, rec.radius + 18)
        val o = on.getOrPut(key) { On(key, centre) }
        o.from = from
        o.gather = gather
        Shelter.call(level, key, centre, rec.radius + 8, Shelter.Why.SIEGE, gather)
        Trouble.log(level, rec, "a siege from the ${from.serializedName}")
    }

    /** A later wave on the village round [bell]: anyone who came out goes back in. */
    fun wave(level: ServerLevel, bell: BlockPos) {
        val rec = Trouble.village(level, bell) ?: return
        val o = on[rec.key] ?: return
        Shelter.call(level, rec.key, o.bell, rec.radius + 8, Shelter.Why.SIEGE, o.gather)
        Trouble.log(level, rec, "another wave")
    }

    /** Whether [victim], dying in the siege round [centre], is one of that village's own folk (who count as its villagers do). */
    fun folkOf(level: ServerLevel, victim: LivingEntity, centre: BlockPos): Boolean {
        val p = victim as? Townsperson ?: return false
        val rec = Trouble.village(level, centre) ?: return false
        return Trouble.residentOf(level, p, rec.key) != null
    }

    /** The siege on the village round [bell] was held, [fighters] having fought it off: the fortunes, the chronicle, the all-clear. */
    fun held(level: ServerLevel, bell: BlockPos, fighters: Collection<Player>) {
        val rec = Trouble.village(level, bell) ?: return
        on.remove(rec.key)
        Fortunes.note(level, rec.key, Noted.HELD, listOf("siege") + fighters.map { it.name.string })
        Villages.note(level, rec.key, "siege", "held")
        Shelter.end(level, rec.key, Shelter.Outcome.HELD)
        Trouble.log(level, rec, "the siege held" + if (fighters.isEmpty()) "" else ", fought off by ${fighters.joinToString { it.name.string }}")
    }

    /** The siege on the village round [bell] was lost, [fallen] of its folk (villagers and residents) dead. */
    fun lost(level: ServerLevel, bell: BlockPos, fallen: Int) {
        val rec = Trouble.village(level, bell) ?: return
        on.remove(rec.key)
        Fortunes.note(level, rec.key, Noted.LOST, listOf(fallen.toString()))
        Villages.note(level, rec.key, "siege", "lost")
        VillageDamage.lost(level, rec)
        Shelter.end(level, rec.key, Shelter.Outcome.LOST)
        Trouble.log(level, rec, "the siege was lost, $fallen of the folk dead; ${VillageDamage.left(level, rec.key)} blocks to mend")
    }

    /** Whether the siege on the village [key] still has mobs alive near it (within its shelter's reach and a little more). */
    fun on(level: ServerLevel, key: Long): Boolean {
        val o = on[key] ?: return false
        val centre = Vec3.atCenterOf(o.bell)
        val reach = (Villages.byKey(level, key)?.radius ?: 48) + 40.0
        o.mobs.removeIf { id -> (level.getEntity(id) as? Mob)?.isAlive != true }
        if (o.mobs.isEmpty()) return false
        return o.mobs.any { id -> level.getEntity(id)?.let { it.distanceToSqr(centre) < reach * reach } == true }
    }

    /** Whether a siege is under way at the village [key], and the side it came from. */
    fun from(key: Long): Direction? = on[key]?.from

    /** The side the horde of a siege on the village round [pos] came from ("east"), if one is under way there. */
    fun sideOf(level: ServerLevel, pos: BlockPos): String? {
        val rec = Trouble.village(level, pos) ?: return null
        return on[rec.key]?.from?.serializedName
    }

    /** At a server stop. */
    fun forget() = on.clear()
}
