package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.festival.FestivalDance
import io.github.vaspyyy.guildmark.festival.FestivalPart
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.TownKind
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.village.live.days.VillageLife
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The village's folk at a feast (living-world/villages/design.md 10.3, part V8): what a village's own people add to a grand festival
 * and to its market day, beside the troupe, the band and the dancers the festival calls in from the folk about its square.
 *
 * - [drift]: the free folk of the village who are not of the festival's cast are sent, one in three each scan, to a place in the second
 *   row of the crowd round the ring ([FestivalSite.spotFor], the festival's own answer to where a townsperson who is not directed by it
 *   stands), as a vanilla villager is sent to the green. There they face the ring and clap along with the band, and at the end of
 *   each set, and at the prize-giving, they cheer. They are a part of a scene ([Aside]), not directed by the festival: a word from a
 *   player, a monster, or the day's next stretch lets them go back to their day.
 * - [cheer]: the prize-giving's cheer, for the same folk.
 * - [market]: on a village's market day, one of the folk about the bell in eight, now and then, cheers or claps and is in good spirits, as
 *   a vanilla villager is.
 * - [cartSpot]: where the pedlar's cart parks on a grand festival's day.
 *
 * A capital's festival is the festival's own, as it was; none of this is asked of one.
 */
object VillageFeast {
    /** The longest a villager in the crowd stays at it before they may go back to their day (and be asked again). */
    private const val STAY = 3000

    /** One in this many of the free folk is sent to the crowd each scan. */
    private const val ONE_IN = 3

    /** Who stays at their work through a fair day: the watch and those who keep shop. */
    private val AT_WORK = setOf(TownRole.GUARD, TownRole.KNIGHT, TownRole.MERCHANT, TownRole.INNKEEPER)

    /** The game time each festival's prize-giving last cheered (by its site's key). */
    private val cheered = HashMap<Long, Long>()

    // ---- The crowd ----

    /**
     * Every scan of a village's festival: the free folk about its square who are not in [taken] (the cast's entity ids) are sent to the
     * second row of the crowd, one in [ONE_IN].
     */
    fun drift(level: ServerLevel, site: FestivalSite, taken: Collection<Int>) {
        if (site.plan.capital || !site.live || site.phase.ordinal > FestivalPhase.DUSK.ordinal || !VillageLife.enabled()) return
        val c = site.centre
        val reach = site.plan.square + 16.0
        val row = site.plan.crowd.size.coerceAtLeast(1)
        for (p in level.getEntitiesOfClass(Townsperson::class.java, AABB(c.x, c.y, c.z, c.x, c.y, c.z).inflate(reach, 8.0, reach)) { eligible(it, taken) }) {
            if (level.random.nextInt(ONE_IN) != 0) continue
            // The second row: a place behind the cast's own, the same one all day for the same person
            val index = row + Math.floorMod(p.resident, row)
            val there = { site.spotFor(index, FestivalPart.WATCH)?.pos?.let { BlockPos.containing(it) } }
            Aside.take(p, Aside(listOf(GoTo(there, Jobs.STROLL, near = 1.0, most = 900), Crowd(site, index)), most = STAY, fresh = true))
        }
    }

    private fun eligible(p: Townsperson, taken: Collection<Int>): Boolean =
        p.isAlive && p.resident >= 0 && !p.crowd && !p.isSleeping && p.role !in AT_WORK && p.id !in taken && p.life?.places?.kind == TownKind.VILLAGE

    /** The prize-giving: the folk in the crowd cheer. */
    fun cheer(level: ServerLevel, site: FestivalSite) {
        if (site.plan.capital) return
        if (cheered.size > 64) cheered.clear()
        cheered[site.key] = level.gameTime
    }

    /** Their part: a place in the crowd, facing the ring, clapping along with the band, a cheer when a set ends and at the prize-giving. */
    private class Crowd(private val site: FestivalSite, private val index: Int) : Move() {
        private var lastBar = -1
        private var cheerTo = 0L
        private var heard = Long.MIN_VALUE

        override fun start(p: Townsperson, a: Aside) {
            heard = cheered[site.key] ?: Long.MIN_VALUE
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            if (!site.live || site.phase.ordinal > FestivalPhase.DUSK.ordinal) return false
            val level = p.level() as? ServerLevel ?: return false
            val spot = site.spotFor(index, FestivalPart.WATCH) ?: return false
            // Moved off (carried away, shoved a long way): their part is over
            if (p.distanceToSqr(spot.pos) > 16.0) return false
            val now = level.gameTime
            val bar = site.bar
            var cheering = false
            if (lastBar >= FestivalDance.SET_BARS - 2 && bar < 0) cheering = true
            lastBar = bar
            val finale = cheered[site.key]
            if (finale != null && finale > heard) {
                heard = finale
                cheering = true
            }
            if (cheering) {
                cheerTo = now + Activity.CHEER.ticks
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y + 2.1, p.z, 4, 0.3, 0.2, 0.3, 0.0)
            }
            val want = if (now < cheerTo) Activity.CHEER else spot.activity
            p.navigation.stop()
            p.lookAt(site.centre)
            p.face(Mth.approachDegrees(p.yBodyRot, spot.yaw, Aside.TURN))
            if (p.activity != want && (p.activity.loop || p.actDone || p.activity == Activity.NONE)) p.act(want)
            return true
        }

        override fun stop(p: Townsperson) {
            if (p.activity == Activity.CLAP_ALONG || p.activity == Activity.CHEER) p.act(Activity.NONE)
        }
    }

    // ---- The market day ----

    /** Every hundred ticks on a village's market day with a player by: one of its folk about [bell] in eight cheers or claps, in good spirits. */
    fun market(level: ServerLevel, bell: BlockPos, random: RandomSource) {
        if (!VillageLife.enabled()) return
        for (p in level.getEntitiesOfClass(Townsperson::class.java, AABB(bell).inflate(24.0)) { it.isAlive && !it.isSleeping && it.resident >= 0 && it.life?.places?.centre == bell }) {
            if (random.nextInt(8) != 0) continue
            // Only those with nothing in hand and nothing to do: a keeper at the counter is left to the stall
            if (!Aside.take(p, Aside(listOf(Play(if (random.nextBoolean()) Activity.CHEER else Activity.CLAP)), fresh = false))) continue
            level.playSound(null, p.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.5f, 0.9f + random.nextFloat() * 0.3f)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y + 2.1, p.z, 3, 0.3, 0.2, 0.3, 0.0)
        }
    }

    // ---- The pedlar's cart ----

    /**
     * Where the pedlar's cart parks at the village with this [bell] on the day of a grand festival, in place of the green: the ring's
     * road side, [PARK] blocks out from the square's edge, towards [from] (where the road comes in; away from the bell if not given).
     * Null when no festival is on at that village, or it is nearly over: the cart parks on the green as ever.
     */
    fun cartSpot(level: ServerLevel, bell: BlockPos, from: BlockPos? = null): BlockPos? {
        val site = Festivities.sceneAt(level, bell) as? FestivalSite ?: return null
        if (site.plan.capital || !site.live || site.phase.ordinal > FestivalPhase.DUSK.ordinal) return null
        val c = site.centre
        val towards = from ?: bell
        val away = if (from != null) 1.0 else -1.0
        val a = atan2((towards.z + 0.5 - c.z) * away, (towards.x + 0.5 - c.x) * away)
        val out = site.plan.square + PARK
        val x = c.x + cos(a) * out
        val z = c.z + sin(a) * out
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(x, c.y, z))
    }

    /** Blocks out from the square's edge the cart parks. */
    private const val PARK = 4
}
