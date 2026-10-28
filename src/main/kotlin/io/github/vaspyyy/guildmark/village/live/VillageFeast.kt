package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.festival.FestivalDance
import io.github.vaspyyy.guildmark.festival.FestivalPart
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownKind
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.village.live.days.VillageLife
import io.github.vaspyyy.guildmark.village.live.market.VillageStall
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The village's folk at a feast (living-world/villages/design.md 10.3, part V8): what a village's own people add to a grand festival
 * and to its market day, beside the troupe, the band and the dancers the festival calls in from the folk about its square.
 *
 * - [drift]: the free folk of the village who are not of the festival's cast are sent, one in three each scan, to a place in the second
 *   row of the crowd round the ring ([FestivalSite.spotFor], the festival's own answer to where a townsperson who is not directed by it
 *   stands), as a vanilla villager is sent to the green: each to a place of their own, nobody on another's. There they face the ring
 *   and clap along with the band, and at the end of each set, and at the prize-giving, they cheer. They are a part of a scene
 *   ([Aside]), not directed by the festival: a word from a player, a monster, or the day's next stretch lets them go back to their day.
 *   Between dances, while the pedlar's cart stands open at the fair ([cartSpot]), one in [CART_ONE_IN] of those sent goes to it instead,
 *   [AT_CART] at a time, looks over the goods and haggles a little, and comes away.
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

    /** Who holds each place of the second row (its index, to the townsperson's entity id), and who is at the pedlar's cart, by festival site. */
    private val rows = HashMap<Long, HashMap<Int, Int>>()
    private val browsing = HashMap<Long, HashSet<Int>>()

    /** Of those sent, one in this many goes to the pedlar's cart instead between dances; and how many may be at it at once. */
    private const val CART_ONE_IN = 3
    private const val AT_CART = 3

    /** At setup: what is kept in memory is forgotten as the server stops. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    fun forget() {
        cheered.clear()
        rows.clear()
        browsing.clear()
    }

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
        if (rows.size > 64) rows.clear()
        if (browsing.size > 64) browsing.clear()
        // A place whose holder is gone from the world (their land unloaded with them in it) is free again
        val held = rows.getOrPut(site.key) { HashMap() }
        held.values.removeIf { id -> gone(level, id) }
        val atCart = browsing.getOrPut(site.key) { HashSet() }
        atCart.removeIf { id -> gone(level, id) }
        // Between dances, the pedlar's cart at the fair, if it stands open
        val cart = if (site.bar < 0 && site.phase == FestivalPhase.FAIR) cartAt(level, site) else null
        for (p in level.getEntitiesOfClass(Townsperson::class.java, AABB(c.x, c.y, c.z, c.x, c.y, c.z).inflate(reach, 8.0, reach)) { eligible(it, taken) }) {
            if (level.random.nextInt(ONE_IN) != 0) continue
            if (cart != null && atCart.size < AT_CART && level.random.nextInt(CART_ONE_IN) == 0 && browse(level, site, p, cart, atCart)) continue
            // The second row: a place behind the cast's own, the same one all day for the same person where nobody else has it
            val start = Math.floorMod(p.resident, row)
            val index = (0 until row).map { row + (start + it) % row }.firstOrNull { it !in held } ?: continue
            val there = { site.spotFor(index, FestivalPart.WATCH)?.pos?.let { BlockPos.containing(it) } }
            val aside = Aside(listOf(GoTo(there, Jobs.STROLL, near = 1.0, most = 900), Crowd(site, index)), most = STAY, fresh = true)
            if (!Aside.take(p, aside)) continue
            val id = p.id
            held[index] = id
            aside.onEnd = { held.remove(index, id) }
        }
    }

    /** Whether the townsperson [id] is gone from the world, or dead. */
    private fun gone(level: ServerLevel, id: Int): Boolean = (level.getEntity(id) as? Townsperson)?.let { it.isRemoved || !it.isAlive } ?: true

    /** The pedlar's cart at the village of [site], while it stands open: its counter's stall. */
    private fun cartAt(level: ServerLevel, site: FestivalSite): CityPlaces.Stall? {
        val rec = Villages.at(level, BlockPos.containing(site.centre)) ?: return null
        return VillageStall.cart(rec.key)
    }

    /**
     * [p] goes over to the pedlar's [cart] between dances: to a place of their own by it on the customers' side, looks the goods
     * over, haggles a little (or nods), and comes away; false if there is nowhere to stand or they would not go.
     */
    private fun browse(level: ServerLevel, site: FestivalSite, p: Townsperson, cart: CityPlaces.Stall, atCart: HashSet<Int>): Boolean {
        val customer = cart.customer.pos
        val keeper = cart.keeper.pos
        val counter = cart.counter.firstOrNull() ?: return false
        val near = AROUND.map { (dx, dz) -> customer.offset(dx, 0, dz) }
            .flatMap { listOf(it, it.above(), it.below()) }
            .filter { it != keeper && it.distSqr(counter) <= 9.0 && Places.standable(level, it) }
        if (near.isEmpty()) return false
        val stand = near[Math.floorMod(p.resident, near.size)]
        val look = { Vec3.atCenterOf(counter) }
        val haggle = level.random.nextInt(2) == 0
        val aside = Aside(
            listOf(
                GoTo({ stand }, Jobs.STROLL, near = 0.8, most = 600),
                Play(Activity.BROWSE, BROWSE_TICKS + level.random.nextInt(BROWSE_TICKS), look),
                Play(if (haggle) Activity.HAGGLE else Activity.NOD, at = look),
            ),
            most = BROWSE_MOST, fresh = true,
        )
        if (!Aside.take(p, aside)) return false
        val id = p.id
        atCart += id
        aside.onEnd = { atCart.remove(id) }
        return true
    }

    /** Where about the cart's customers' place the folk may stand to look its goods over. */
    private val AROUND = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to 1, 1 to -1, -1 to -1)

    /** Ticks a look over the pedlar's goods takes (and up to as long again), and the longest a turn at the cart lasts. */
    private const val BROWSE_TICKS = 120
    private const val BROWSE_MOST = 1500

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
     * road side, [PARK] blocks out beyond the square from its middle, along [road] (the bearing in radians the road comes in by, from
     * the green; away from the bell if not given). Null when no festival is on at that village, or it is nearly over: the cart parks on
     * the green as ever. The pedlar's arrival asks it (village/live/market/Pedlars).
     */
    fun cartSpot(level: ServerLevel, bell: BlockPos, road: Double? = null): BlockPos? {
        if (!VillageLife.enabled()) return null
        val site = Festivities.sceneAt(level, bell) as? FestivalSite ?: return null
        if (site.plan.capital || !site.live || site.phase.ordinal > FestivalPhase.DUSK.ordinal) return null
        val c = site.centre
        val a = road ?: atan2(c.z - (bell.z + 0.5), c.x - (bell.x + 0.5))
        val out = site.plan.square + PARK
        val x = c.x + cos(a) * out
        val z = c.z + sin(a) * out
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(x, c.y, z))
    }

    /** Blocks out from the square's edge the cart parks. */
    private const val PARK = 4
}
