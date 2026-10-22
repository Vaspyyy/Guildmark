package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Idle
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Schedule
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Ways
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB

/**
 * Shivering (living-world/design-influence.md section 5): with the homes short of coal while they're heating (coal
 * wanted and not to be had today, or yesterday with none in since, and the season burning it as usual or more), folk
 * out of doors stop now and then to hug themselves and stamp their feet (SHIVER) before going on, and those out for
 * a turn about the town or at play hurry home early ([home], from [io.github.vaspyyy.guildmark.townsfolk.Acts]' stroll
 * and play) and stay in by the fire. A few at a time near each player, one more every few seconds.
 */
internal object Cold {
    /** How near a player folk must be to be seen shivering, the most at it at once in a capital, and the gap between one starting and the next. */
    private const val NEAR = 24.0
    private const val MOST = 4
    private const val EVERY = 60L

    /** How long a capital's cold is kept before the record's looked at again. */
    private const val LOOKED = 200L

    private class Known(val at: Long, val cold: Boolean)

    private val known = HashMap<Long, Known>()

    /** For `/guildmark capital scene shiver`: the cold felt in a capital until a game time, whatever the record says. */
    private val forced = HashMap<Long, Long>()

    private class Shivering {
        val members = LinkedHashSet<Townsperson>()
        var last = Long.MIN_VALUE / 2
    }

    private val shivering = HashMap<Long, Shivering>()

    /** Whether the cold bites in [life]'s capital (see [biting]). */
    fun biting(p: Townsperson, life: Life): Boolean {
        if (forced.isEmpty() && !Fortunes.enabled()) return false
        val level = p.level() as? ServerLevel ?: return false
        return biting(level, life.places.centre.asLong())
    }

    /**
     * Whether the cold bites in the capital whose heart is [capital]: the streets' scenes on, and its homes short of
     * coal while they're heating. Asks nothing of the world, and draws nothing at random, with the streets' scenes off.
     */
    fun biting(level: ServerLevel, capital: Long): Boolean {
        if (forced.isNotEmpty()) forced[capital]?.let { if (level.gameTime <= it) return true else forced.remove(capital) }
        if (!Fortunes.enabled() || !Markets.enabled()) return false
        val now = level.gameTime
        known[capital]?.let { if (now - it.at in 0 until LOOKED) return it.cold }
        val entry = Markets.find(level, capital)
        val cold = entry != null && Markets.felt(entry).heating >= 1.0 && entry.market.let { m ->
            m.today(Tally.SHORT, Good.COAL) > 0.0 || m.yesterday(Tally.SHORT, Good.COAL) > 0.0 && m.stock(Good.COAL) < 1.0
        }
        if (known.size > 64) known.clear()
        known[capital] = Known(now, cold)
        return cold
    }

    /** Whether [p] is out of doors (in no building of the capital's). */
    private fun outdoors(p: Townsperson, places: TownPlaces): Boolean {
        val pos = p.blockPosition()
        return places.lodgingAt(pos)?.contains(pos) != true
    }

    /**
     * Home now, out of the cold: a shiver where they stand, then home at a brisk walk. Null if the cold doesn't bite,
     * they've no home, or they're in already. For [io.github.vaspyyy.guildmark.townsfolk.Acts]' turn about the town
     * and children's play.
     */
    fun home(p: Townsperson, life: Life): Task? {
        if (!biting(p, life)) return null
        val home = life.home ?: return null
        val door = home.front ?: return null
        if (home.contains(p.blockPosition())) return null
        val overlay = if (Schedule.dark(p)) Activity.LANTERN else Activity.NONE
        val way = Ways.to(p, life.places, door.inside, Jobs.BRISK, near = 1.5, overlay = overlay)
        return InScene(p, if (outdoors(p, life.places)) Idle(p, 50 + p.random.nextInt(30), Activity.SHIVER, chatty = false).andThen(way) else way)
    }

    /** Once a second for each player in a capital where the cold bites: someone out of doors near them stops to shiver. */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {
        if (!biting(level, here.key)) return
        start(level, player, here, force = false)
    }

    /** Someone out of doors near [player] stops to shiver, if anyone's free to; with [force], whatever the count. Who did. */
    fun start(level: ServerLevel, player: ServerPlayer, here: Scenes.Here, force: Boolean): Townsperson? {
        val now = level.gameTime
        val group = shivering.getOrPut(here.key) { Shivering() }
        group.members.removeIf { !it.isAlive || it.isRemoved || it.director == null }
        if (!force && (group.members.size >= MOST || now - group.last < EVERY)) return null
        val near = level.getEntitiesOfClass(Townsperson::class.java, AABB(player.blockPosition()).inflate(NEAR, 8.0, NEAR)) {
            it.role != TownRole.GUARD && it !in group.members && Aside.free(it) && outdoors(it, here.places)
        }
        if (near.isEmpty()) return null
        val p = near[level.random.nextInt(near.size)]
        val r = p.random
        val moves = ArrayList<Move>()
        moves += Play(Activity.SHIVER, 80 + r.nextInt(50))
        if (r.nextInt(3) == 0) moves += Play(if (p.role == TownRole.CHILD) Activity.JUMP else Activity.SIGH)
        val aside = Aside(moves, most = 400)
        if (!Aside.take(p, aside)) return null
        group.members += p
        group.last = now
        aside.onEnd = {
            group.members.remove(p)
            // Out for a turn about the town or at play: home now, rather than on with it
            val life = Days.life(p)
            if (life != null && p.isAlive && p.hurtTime == 0) {
                val doing = Days.stretch(life, level.overworldClockTime).doing
                if (doing == Doing.STROLL || doing == Doing.PLAY) home(p, life)?.let { p.routine.replace(it) }
            }
        }
        return p
    }

    /** For the scene command: the cold bites in [capital] for the next [ticks], whatever the record says. */
    fun force(level: ServerLevel, capital: Long, ticks: Long) {
        forced[capital] = level.gameTime + ticks
    }

    /** Whether the scene command has the cold biting anywhere just now (so [look] runs whatever the switches say). */
    fun forcing(): Boolean = forced.isNotEmpty()

    fun forget() {
        known.clear()
        forced.clear()
        shivering.clear()
    }
}
