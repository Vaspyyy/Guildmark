package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Sit
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Work
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The keeper shutting the stall (living-world/design-influence.md section 5, "The slate"): a stall with none of its
 * goods in stock (the market's record, as the shoppers find it: [Trades.wares]) has its keeper reach up and let the
 * awning's flap down (SHUT_STALL), tidy the bare counter (ARRANGE) with a word for whoever's passing ("Nothing
 * today, I'm afraid."), and sit by it (on a seat near it, or standing arms folded behind it), looking up now and then,
 * until there's something to sell again: then the awning goes up (SET_UP) and the day's trade goes on. It's the
 * keeper's working day ([keep], from [io.github.vaspyyy.guildmark.townsfolk.Acts]' work), and seen sooner near a player
 * ([look]). A keeper only just come into the world (the land loading as a player comes by) at a stall that's sold out
 * finds it shut already, as the record has it, and sits by it: the awning isn't let down before the player's eyes.
 *
 * **The way in, for Part D2** (the slate on the display, and the awning's look): [isShut] says whether a stall's
 * keeper has let its awning down; set [awning] to hear the moment they let it down and put it up again (told too when
 * it's found shut as the land loads). Kept in memory: after a restart it's found shut again the first time its keeper
 * is at the stall with nothing to sell. Everything here is on [Fortunes.enabled], with the streets' other scenes.
 */
object ShutStall {
    /** The awning's look, as Part D2 has it: told when a keeper lets it down and puts it up again. */
    interface Awning {
        fun down(level: ServerLevel, stall: CityPlaces.Stall, by: Townsperson) {}
        fun up(level: ServerLevel, stall: CityPlaces.Stall, by: Townsperson) {}
    }

    /** Part D2's awnings, if it has them. */
    @JvmStatic
    var awning: Awning? = null

    /** How near a player a stall is looked at for a keeper to shut it at once. */
    private const val SEEN = 32.0

    /** How many ticks a keeper's body has been in the world (loaded or come in) and still finds their stall as it was left. */
    private const val LOADED = 200

    /**
     * The tick of SHUT_STALL (counted as Work counts a step's, from 0 a tick after the move starts) the awning comes
     * down on: the move's pull on the cord gives at its 7th tick, and D2's flap unrolls from then in its own renderer.
     */
    private const val PULL_AT = 6

    /** The stalls shut (by their keeper's place), and since when. */
    private val shut = HashMap<Long, Long>()

    /** For `/guildmark capital scene shut`: stalls (by their keeper's place) bare until a game time, whatever the record says. */
    private val forced = HashMap<Long, Long>()

    /** Whether [stall]'s keeper has let its awning down: none of its goods to sell. */
    @JvmStatic
    fun isShut(stall: CityPlaces.Stall): Boolean = stall.keeper.pos.asLong() in shut

    /** The stall [p] keeps: the one whose keeper's place is their post. */
    fun stallOf(p: Townsperson, life: Life): CityPlaces.Stall? {
        val post = p.post ?: return null
        return life.places.stalls.minByOrNull { it.keeper.pos.distSqr(post) }?.takeIf { it.keeper.pos.distSqr(post) <= 4.0 }
    }

    /** Whether [stall] has none of its goods to sell (never, with nothing known of the market). */
    private fun bare(level: ServerLevel, p: Townsperson, life: Life, stall: CityPlaces.Stall): Boolean {
        val key = stall.keeper.pos.asLong()
        forced[key]?.let { if (level.gameTime <= it) return true else forced.remove(key) }
        if (!Fortunes.enabled()) return false
        val entry = Trades.market(p, life) ?: return false
        return Trades.wares(entry, stall.trade).isEmpty()
    }

    /**
     * For a merchant's working day: at a stall with nothing to sell, the awning let down and the counter tidied, then
     * a sit by it; with something to sell again, the awning put back up. Null otherwise (their trade as ever), and
     * with the streets' scenes off (asking nothing of the world then).
     */
    fun keep(p: Townsperson, life: Life): Task? {
        if (!Fortunes.enabled() && forced.isEmpty() && shut.isEmpty()) return null
        if (p.role != TownRole.MERCHANT) return null
        val level = p.level() as? ServerLevel ?: return null
        val stall = stallOf(p, life) ?: return null
        val key = stall.keeper.pos.asLong()
        val bare = bare(level, p, life, stall)
        return when {
            bare && key in shut -> sitBy(p, life, stall)
            bare && p.tickCount < LOADED -> foundShut(level, p, life, stall)
            bare -> shutUp(p, life, stall).andThen(sitBy(p, life, stall))
            key in shut -> openUp(p, life, stall)
            else -> null
        }
    }

    /** [stall]'s awning is down from now (D2's [awning] told), let down by [by]. */
    private fun down(level: ServerLevel, stall: CityPlaces.Stall, by: Townsperson) {
        shut[stall.keeper.pos.asLong()] = level.gameTime
        try {
            awning?.down(level, stall, by)
        } catch (e: RuntimeException) {
        }
    }

    /** Come into the world at a stall sold out a while since: it's shut already, and they sit by it. */
    private fun foundShut(level: ServerLevel, p: Townsperson, life: Life, stall: CityPlaces.Stall): Task {
        down(level, stall, p)
        return sitBy(p, life, stall)
    }

    /** Where the keeper looks to reach the awning: up over the counter's middle. */
    private fun awningAt(stall: CityPlaces.Stall): Vec3 {
        val middle = stall.counter.getOrNull(stall.counter.size / 2) ?: stall.keeper.pos.relative(stall.keeper.facing)
        return Jobs.top(middle, 1.0).add(0.0, 1.2, 0.0)
    }

    /** The awning let down, the bare counter tidied, and a word for whoever's passing. */
    private fun shutUp(p: Townsperson, life: Life, stall: CityPlaces.Stall): Task {
        val level = p.level() as ServerLevel
        val counter = stall.counter.getOrNull(stall.counter.size / 2)?.let { Jobs.top(it, 1.0) } ?: awningAt(stall)
        val shutting = Step(Activity.SHUT_STALL)
        val tidying = Step(Activity.ARRANGE, 60)
        val steps = listOf(shutting, tidying, Step(Activity.ARRANGE, 61), Step(Activity.NONE, 20))
        return Acts.via(p, life, stall.keeper.pos, Work(p, Spot(stall.keeper.pos, stall.keeper.facing), awningAt(stall), { i -> steps.getOrNull(i) }, onTick = { s, t ->
            if (s === shutting && t == PULL_AT) down(level, stall, p)
            if (s === tidying && t == 0) {
                p.lookAt(counter)
                Words.say(p, "streets.shut", "a stall shut with nothing to sell")
            }
        }))
    }

    /**
     * A sit by the shut stall a while: on a seat near it if there's one free, else standing behind it arms folded,
     * looking out at the street and now and then up at the market.
     */
    private fun sitBy(p: Townsperson, life: Life, stall: CityPlaces.Stall): Task {
        val level = p.level() as ServerLevel
        val r = p.random
        Places.seat(level, stall.keeper.pos, 4, p)?.let { seat ->
            val access = Places.access(level, seat, level.getBlockState(seat)) ?: seat
            return Acts.via(p, life, access, Sit(p, seat, 600 + r.nextInt(600), listOf(Activity.SIT, Activity.SIT, Activity.SIT, Activity.SIGH)))
        }
        val street = Vec3.atCenterOf(stall.customer.pos.relative(stall.customer.facing.opposite, 4)).add(0.0, 1.0, 0.0)
        return Acts.via(p, life, stall.keeper.pos, Work(p, Spot(stall.keeper.pos, stall.keeper.facing), street, { i ->
            when {
                i >= 8 -> null
                i % 3 == 2 -> Step(if (r.nextInt(2) == 0) Activity.SIGH else Activity.NONE, 40)
                else -> Step(Activity.ARMS_FOLDED, Activity.ARMS_FOLDED.ticks * (1 + r.nextInt(2)))
            }
        }))
    }

    /** Something to sell again: the awning back up, and the counter set out. */
    private fun openUp(p: Townsperson, life: Life, stall: CityPlaces.Stall): Task {
        val level = p.level() as ServerLevel
        val opening = Step(Activity.SET_UP)
        val steps = listOf(opening, Step(Activity.ARRANGE, 60))
        return Acts.via(p, life, stall.keeper.pos, Work(p, Spot(stall.keeper.pos, stall.keeper.facing), awningAt(stall), { i -> steps.getOrNull(i) }, onTick = { s, t ->
            if (s === opening && t == 20 && shut.remove(stall.keeper.pos.asLong()) != null) {
                try {
                    awning?.up(level, stall, p)
                } catch (e: RuntimeException) {
                }
            }
        }))
    }

    /**
     * Once a second for each player in a capital: a keeper at a stall near them that's just sold out shuts it now,
     * rather than when their present round at it is done.
     */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {
        if (!Markets.enabled()) return
        val entry = Markets.get(level)[here.places.capital.center] ?: return
        for (stall in here.places.stalls) {
            if (stall.keeper.pos.distSqr(player.blockPosition()) > SEEN * SEEN) continue
            if (isShut(stall) || Trades.wares(entry, stall.trade).isNotEmpty()) continue
            val keeper = Acts.keeper(level, stall) as? Townsperson ?: continue
            if (keeper.role != TownRole.MERCHANT || keeper.director != null || keeper.routine.task is Scened) continue
            val life = Days.life(keeper) ?: continue
            if (Days.stretch(life, level.overworldClockTime).doing != Doing.WORK || keeper.routine.windingDown) continue
            if (stallOf(keeper, life) !== stall) continue
            keeper.routine.replace(if (keeper.tickCount < LOADED) foundShut(level, keeper, life, stall) else shutUp(keeper, life, stall).andThen(sitBy(keeper, life, stall)))
            return
        }
    }

    /**
     * `/guildmark capital scene shut`: the stall nearest [player] is bare for two minutes: its keeper (or, with none
     * at it, whoever's free nearest) lets the awning down, tidies and sits by it, and puts it back up after. True if
     * someone's at it.
     */
    fun play(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): Boolean {
        val stall = here.places.stalls.minByOrNull { it.keeper.pos.distSqr(player.blockPosition()) } ?: return false
        if (stall.keeper.pos.distSqr(player.blockPosition()) > 48.0 * 48.0) return false
        val key = stall.keeper.pos.asLong()
        forced[key] = level.gameTime + 2400
        val at = Vec3.atCenterOf(stall.keeper.pos)
        val keeper = (Acts.keeper(level, stall) as? Townsperson)?.takeIf { it.life != null }
            ?: level.getEntitiesOfClass(Townsperson::class.java, AABB(stall.keeper.pos).inflate(24.0, 6.0, 24.0)) {
                it.role.adult && it.role != TownRole.GUARD && it.life != null && Aside.free(it, seated = true)
            }.minByOrNull { it.distanceToSqr(at) }
            ?: return false
        val life = keeper.life ?: return false
        if (keeper.seat != null) keeper.standUp()
        // A sit by it, and back to put the awning up again once the sit's over (the stall's own stock as it is)
        keeper.routine.replace(shutUp(keeper, life, stall).andThen(sitBy(keeper, life, stall)).andThen(Later(keeper) {
            forced.remove(key)
            if (isShut(stall)) openUp(keeper, life, stall) else null
        }))
        return true
    }

    fun forget() {
        shut.clear()
        forced.clear()
    }
}
