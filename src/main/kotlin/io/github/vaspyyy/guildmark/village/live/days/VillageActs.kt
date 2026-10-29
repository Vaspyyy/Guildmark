package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Idle
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Leisure
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Mingle
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Play
import io.github.vaspyyy.guildmark.townsfolk.Sit
import io.github.vaspyyy.guildmark.townsfolk.Social
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Stretch
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Ways
import io.github.vaspyyy.guildmark.townsfolk.Work
import io.github.vaspyyy.guildmark.townsfolk.scenes.Cold
import io.github.vaspyyy.guildmark.townsfolk.talk.Street
import io.github.vaspyyy.guildmark.village.live.market.VillageTimes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * What a village's people do in each stretch of their day (living-world/villages/design.md 5.4, 5.5): what a capital's people do
 * ([Acts]) on the village's own places, and in the places where a village is not a capital, its own turns.
 *
 * - A keeper at their stall ([VillageDays.STALL]): the stall set out, then kept, calling to those who come by.
 * - Market day about the green ([VillageDays.GREEN]): a round of the stalls, then a bench, a word with a neighbour or a lunch on
 *   the green; the children play there.
 * - The evening, with no tavern to go to: the green's benches and a word on the green in the warm for those whose night for the
 *   tavern it is, and for a third of the others by turns ([outTonight]); the hearth in the cold, the rain and the winter.
 * - A keeper of the lamps ([VillageDays.LAMPS], [VillageDays.SNUFF]): the round itself is their lamplighter's own, which takes them off their
 *   day when the clock has it begin (townsfolk/Lamplighters); the evening before and after it is at home, and the morning they rise,
 *   wash at the well and breakfast when it is done.
 * - The service, with no temple: a prayer out by the bell.
 * - Someone with no home that stands: meals and the evening on the green, the night under a roof (a seat in a barn or another
 *   building, else just inside its door; a bench only where the village has no building with a door) until a bed is free; never
 *   out in the street.
 *
 * [Days] asks here, in place of [Acts], for a village's people.
 */
object VillageActs {
    private fun level(p: Townsperson): ServerLevel = p.level() as ServerLevel
    private fun r(p: Townsperson, n: Int): Int = p.random.nextInt(n)

    /** The parts of [stretch] for [life] (a village's). */
    fun script(p: Townsperson, life: Life, stretch: Stretch): List<Acts.Part> {
        release(p, life, stretch)
        when (stretch.tag) {
            VillageDays.STALL -> return listOf(Acts.Part { setUp(p, life) })
            VillageDays.GREEN -> return if (stretch.doing == Doing.PLAY) listOf() else Acts.script(p, life, Doing.MARKET)
            VillageDays.BELL -> return listOf(Acts.Part { BellKeeper.errand(p, life) })
            VillageDays.BOARD -> return listOf(Acts.Part { Posters.errand(p, life) })
            VillageDays.LAMPS -> return listOf()
            VillageDays.SNUFF -> return Acts.script(p, life, Doing.RISE)
        }
        if (life.home == null) {
            when (stretch.doing) {
                Doing.BREAKFAST, Doing.SUPPER -> return Acts.script(p, life, Doing.LUNCH)
                Doing.EVENING -> return listOf(Acts.Part { green(p, life) })
                Doing.BED, Doing.SLEEP -> return listOf(Acts.Part { shelter(p, life) })
                else -> {}
            }
        }
        return when (stretch.doing) {
            Doing.WORSHIP -> listOf(Acts.Part { worship(p, life) })
            Doing.TAVERN -> if (p.role == TownRole.BARD) Acts.script(p, life, stretch.doing) else listOf(Acts.Part { green(p, life) })
            Doing.EVENING -> if (outTonight(p, life)) listOf(Acts.Part { green(p, life) }) else Acts.script(p, life, stretch.doing)
            else -> Acts.script(p, life, stretch.doing)
        }
    }

    /**
     * Whether [life] spends tonight's evening out on the green (a third of the grown-up folk, by turns, fewer in hard times and more in
     * good: VillageTimes; in the warm; the hearth otherwise).
     */
    private fun outTonight(p: Townsperson, life: Life): Boolean =
        life.resident.role.adult && life.resident.role != TownRole.GUARD &&
            VillageTimes.outTonight(level(p), life.places.centre.asLong(), life.resident.id, Days.dayOf(level(p).overworldClockTime))

    /** What fills the rest of [stretch] once its parts are done. */
    fun filler(p: Townsperson, life: Life, stretch: Stretch): Task {
        when (stretch.tag) {
            VillageDays.STALL -> keep(p, life)?.let { return it }
            VillageDays.GREEN -> return if (stretch.doing == Doing.PLAY) play(p, life) else gather(p, life)
            // The bell rung (or not to be rung): home for supper
            VillageDays.BELL -> return Acts.filler(p, life, Doing.EVENING)
            // The note pinned (or nothing to pin): a turn about the village
            VillageDays.BOARD -> return Acts.filler(p, life, Doing.STROLL)
            // The round walked (or the lamplighter not yet fetched): at home of an evening; breakfast after the morning's
            VillageDays.LAMPS -> return Acts.filler(p, life, Doing.EVENING)
            VillageDays.SNUFF -> return Acts.filler(p, life, Doing.BREAKFAST)
        }
        if (life.home == null) {
            when (stretch.doing) {
                Doing.BED, Doing.SLEEP -> return shelter(p, life)
                Doing.EVENING, Doing.BREAKFAST, Doing.SUPPER -> return green(p, life) ?: shelter(p, life)
                else -> {}
            }
        }
        return when (stretch.doing) {
            // The tower's watch where there is a tower for them; the beat on the ground where not
            Doing.WATCH -> VillageWatch.keep(p, life) ?: Jobs.guard(p)
            Doing.WORSHIP -> worship(p, life) ?: Acts.filler(p, life, Doing.EVENING)
            Doing.TAVERN -> if (p.role == TownRole.BARD) Acts.filler(p, life, stretch.doing) else green(p, life) ?: Acts.filler(p, life, Doing.EVENING)
            Doing.EVENING -> if (outTonight(p, life)) green(p, life) ?: Acts.filler(p, life, stretch.doing) else Acts.filler(p, life, stretch.doing)
            else -> Acts.filler(p, life, stretch.doing)
        }
    }

    /** For the market scene: [life] keeps stall [index] from now, the stall set out and then kept. */
    fun keepNow(p: Townsperson, life: Life, index: Int): Task? {
        life.keeper = index
        life.market = true
        return setUp(p, life)?.andThen(Later(p) { keep(p, life) })
    }

    /** For the market scene: [life] is about the green as on a market day (the children playing there). */
    fun greenNow(p: Townsperson, life: Life): Task {
        life.market = true
        return if (p.role == TownRole.CHILD) play(p, life) else gather(p, life)
    }

    /** A keeper's post is theirs only while they keep the stall: when the stretch is another, they have none. */
    private fun release(p: Townsperson, life: Life, stretch: Stretch) {
        val post = p.post ?: return
        if (stretch.tag != VillageDays.STALL && life.places.stalls.any { it.keeper.pos == post }) {
            p.post = null
            p.postFacing = null
        }
    }

    // --- The stall ---

    private fun stallOf(p: Townsperson, life: Life): CityPlaces.Stall? =
        life.places.stalls.getOrNull(life.keeper)?.takeIf { level(p).isLoaded(it.keeper.pos) }

    private fun counterOf(stall: CityPlaces.Stall) = Jobs.top(stall.counter.getOrNull(stall.counter.size / 2) ?: stall.keeper.pos.relative(stall.keeper.facing), 1.0)

    /** The first thing at the stall: it is set out, and the wares arranged. */
    private fun setUp(p: Townsperson, life: Life): Task? {
        val stall = stallOf(p, life) ?: return null
        p.post = stall.keeper.pos
        p.postFacing = stall.keeper.facing
        return Work(p, stall.keeper, counterOf(stall), listOf(Step(Activity.SET_UP, 60 * (2 + r(p, 2))), Step(Activity.ARRANGE, 60)))
    }

    /** At the stall: a call to whoever comes by, the wares tidied, a pause; on market day a cry of them now and then. */
    private fun keep(p: Townsperson, life: Life): Task? {
        val stall = stallOf(p, life) ?: return null
        val level = level(p)
        val counter = counterOf(stall)
        return Work(p, stall.keeper, counter, { i ->
            val near = level.getNearestPlayer(counter.x, counter.y, counter.z, 10.0, false)
            when {
                i >= 6 -> null
                near != null && near.distanceToSqr(counter) < 36.0 && r(p, 3) == 0 -> Step(Activity.BECKON)
                near != null && r(p, 2) == 0 -> Street.calling(p, near) ?: Step(Activity.ARRANGE, 60 * (1 + r(p, 2)))
                life.market && r(p, 3) == 0 -> Step(Activity.CALL_OUT)
                r(p, 3) == 0 -> Step(Activity.NONE, 60 + r(p, 80))
                else -> Step(Activity.ARRANGE, 60 * (1 + r(p, 2)))
            }
        })
    }

    // --- The green ---

    /** A bench of the village's, free, within [reach] blocks of [near]; the nearest to [near]. */
    private fun bench(p: Townsperson, life: Life, near: net.minecraft.core.BlockPos, reach: Double): Spot? {
        val level = level(p)
        return life.places.benches.filter {
            it.pos.distSqr(near) <= reach * reach && level.isLoaded(it.pos) && Claims.free(level, it.pos, p) && Places.isSeat(level, level.getBlockState(it.pos), it.pos)
        }.minByOrNull { it.pos.distSqr(near) }
    }

    /** A place on the green to stand: a spot in the open a few blocks from its middle. */
    private fun spotOnGreen(p: Townsperson, life: Life) = Places.street(level(p), life.places.market, 6, p.random, tries = 8) ?: life.places.market

    /** A turn among the others on the green: a word, or a bench and a sit. */
    private fun mingle(p: Townsperson, life: Life, benches: Boolean, moods: List<Activity>): Task {
        val places = life.places
        if (benches && r(p, 3) != 0) {
            bench(p, life, places.market, 14.0)?.let { return Sit(p, it.pos, 500 + r(p, 600), listOf(Activity.SIT, Activity.SIT_TALK, Activity.SIT_TALK, Activity.SIT_GOSSIP, Activity.SIT_LAUGH)) }
        }
        val there = spotOnGreen(p, life)
        val after = if (r(p, 3) == 0) Later(p) { Social.chat(p) ?: Mingle(p, 300 + r(p, 300), moods, tavern = false) } else Mingle(p, 300 + r(p, 400), moods, tavern = false)
        return Ways.to(p, places, there, Jobs.STROLL, near = 2.0, overlay = Activity.STROLL).andThen(after)
    }

    private val TALK = listOf(Activity.TALK, Activity.TALK, Activity.GOSSIP, Activity.LISTEN, Activity.LAUGH, Activity.NOD)

    /** Market day for an adult once the stalls are seen to: about the green, among the others, and a lunch on a bench towards noon. */
    private fun gather(p: Townsperson, life: Life): Task {
        val level = level(p)
        if (level.isRaining) return Acts.filler(p, life, Doing.MARKET)
        val hour = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0
        if (hour in 11.75..13.0 && r(p, 2) == 0) {
            val bench = bench(p, life, life.places.market, 16.0)
            if (bench != null) {
                val dish = ItemStack(if (r(p, 2) == 0) Items.BREAD else Items.BAKED_POTATO)
                return Acts.hold(p, dish).andThen(Sit(p, bench.pos, 400 + r(p, 300), listOf(Activity.SIT_EAT, Activity.SIT_EAT, Activity.SIT_TALK))).andThen(Acts.hold(p, ItemStack.EMPTY))
            }
        }
        return mingle(p, life, benches = true, moods = TALK)
    }

    /** The children on market day: running about the green. */
    private fun play(p: Townsperson, life: Life): Task =
        if (level(p).isRaining || r(p, 3) == 0) Leisure.child(p) else Play(p, life.places.market)

    /**
     * The evening with no tavern: the green's benches and a word on it in the warm; the hearth when it rains, in winter and when
     * the cold bites. Null for the hearth of those who have none to go to (they find the shelter instead).
     */
    private fun green(p: Townsperson, life: Life): Task? {
        val level = level(p)
        if (level.isRaining || Seasons.today(level).season == Season.WINTER || Cold.biting(p, life)) {
            return if (life.home == null) shelter(p, life) else Acts.filler(p, life, Doing.EVENING)
        }
        return mingle(p, life, benches = true, moods = TALK)
    }

    // --- The service ---

    /** The service: a temple's benches if the village has one, else a prayer out by the bell. */
    private fun worship(p: Townsperson, life: Life): Task? {
        Acts.worship(p, life)?.let { return it }
        val level = level(p)
        if (level.isRaining) return null
        val places = life.places
        val spot = Places.street(level, places.bell, 5, p.random, tries = 8) ?: places.market
        return Ways.to(p, places, spot, Jobs.STROLL, near = 2.0).andThen(Idle(p, 300 + r(p, 240), Activity.PRAY, chatty = false))
    }

    // --- No home that stands ---

    /** The kinds of building a homeless villager shelters in, the barn first, a house last (they are not turned out of the road). */
    private val SHELTERS = listOf("BARN", "GUILD_HALL", "TEMPLE", "LIBRARY", "BAKERY", "SMITHY", "WORKSHOP", "HOUSE")

    /**
     * A night with no bed: under a roof, never in the street. A seat in a barn or whatever other building has one (asleep in a
     * chair, as the capital's people are when they have no bed), else standing just inside its door; a bench on the green only where
     * the village has no building with a door at all. They keep to it until a bed is free (their home is found again each day:
     * [VillageLife]).
     */
    private fun shelter(p: Townsperson, life: Life): Task {
        val level = level(p)
        val places = life.places
        val here = p.blockPosition()
        val roofs = places.lodgings.values.filter { it.kind in SHELTERS && it.front != null }
            .sortedWith(compareBy({ SHELTERS.indexOf(it.kind) }, { it.box.center.distSqr(here) }))
        for (roof in roofs) {
            val seat = roof.seats.filter { level.isLoaded(it.pos) && Places.isSeat(level, level.getBlockState(it.pos), it.pos) && Claims.free(level, it.pos, p) }
                .minByOrNull { it.pos.distSqr(here) } ?: continue
            val access = Places.access(level, seat.pos, level.getBlockState(seat.pos)) ?: seat.pos
            return Acts.via(p, life, access, Sit(p, seat.pos, 2400, listOf(Activity.SIT)))
        }
        val roof = roofs.firstOrNull()
        if (roof == null) {
            bench(p, life, places.market, 24.0)?.let { return Sit(p, it.pos, 2400, listOf(Activity.SIT)) }
            return Idle(p, 600, chatty = false)
        }
        return Ways.to(p, places, roof.front!!.inside, Jobs.WALK, near = 1.5).andThen(Idle(p, 2400, chatty = false))
    }
}
