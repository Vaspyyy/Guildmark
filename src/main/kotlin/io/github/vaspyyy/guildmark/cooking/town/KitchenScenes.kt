package io.github.vaspyyy.guildmark.cooking.town

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.cooking.market.Lot
import io.github.vaspyyy.guildmark.cooking.vessel.OvenBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.SpitBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.Stations
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotMode
import io.github.vaspyyy.guildmark.cooking.vessel.Vessel
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/** The four scenes of a town's kitchen (living-world/cook/design.md 7.3, 7.5): the innkeeper at the pot, the baker with the peel, the baker slicing, the rest-day roast. */
enum class KitchenSceneKind { TAVERN, PEEL, SLICE, ROAST }

/**
 * The kitchen scenes (living-world/cook/design.md 7.3, 7.5, 9.5): once every 20 ticks, for each capital a player is on the
 * ground of, it looks at its tavern and its bakery that a player is within reach of, and when the keeper's cool-down is over and
 * they can be spared it takes them off their day for a [KitchenScene]: the innkeeper to the house pot (from the pot's simmering
 * at ten, every two and a half minutes of real time at the meal hours and five at others), the baker to the Bake oven and the
 * counter (every three), the innkeeper to the Spit on a rest day from half past ten to one until the roast is carved at noon.
 *
 * It takes only a keeper who is at work and at their post (the clock says so, they are awake, not hurt, running, led, a leader,
 * talking, sitting, riding or already under a director), with a player within reach all the while, and never from a capital's
 * unloaded land. The keeper is found through the building's box once in a while and kept, never by looking through the residents; the
 * pot, the spit and the oven are found through [Stations.near], which looks through the stations already loaded, and are checked
 * again whenever they are used. Everything is decided here, on the server; nothing is sent to anyone.
 *
 * Besides the scenes it keeps the house pot as a display: what the inn is serving tonight tints its surface (the best pot dish a
 * player sold the inn, else the house's own pottage), and the bowls a scene sets on its shelf are taken one by one, as if by
 * the tavern's patrons. With Town Kitchens off nothing starts, a running scene ends at its next second, and the shelf is
 * cleared: the pot is the still display it was.
 *
 * Part I2's. `/guildmark cook scene tavern|peel|slice|roast|stop` plays one now ([SceneCommand]); [take] is what it calls.
 */
object KitchenScenes {
    /** Ticks between the checks. */
    private const val CHECK = 20L

    /** How near, in blocks, a player must be to a tavern (and the roast), and to a bakery, for its keeper to be taken. */
    private const val TAVERN_RANGE = 40.0
    private const val BAKER_RANGE = 32.0

    /** How far the keeper may be from a player when a scene begins. */
    private const val KEEPER_RANGE = 48.0

    /** Ticks a capital stays on the books after the last player has gone from it. */
    private const val KEEP = 1200L

    /** The cool-downs, in ticks: 2.5 minutes at the meal hours and five at others (the innkeeper), three (the baker), twenty seconds between turns of the roast. */
    private const val MEAL_COOL = 3000L
    private const val OTHER_COOL = 6000L
    private const val BAKER_COOL = 3600L
    private const val ROAST_COOL = 400L

    /** Ticks a bowl stays on the shelf before a patron takes it. */
    private const val BOWL_TAKEN = 500L

    /** Ticks between looking for a keeper again, for the stations again and for what the inn serves tonight. */
    private const val KEEPER_LOOK = 100L
    private const val STATIONS_LOOK = 200L
    private const val TINT_LOOK = 100L

    // ---- What is kept ----

    /** One tavern or bakery of a capital a player has been near: the cool-downs, the keeper and the blocks found, the shelf's bowls. */
    private class Rec(val capital: BlockPos, val lodging: CityPlaces.Lodging, val tavern: Boolean) {
        var keeper: Townsperson? = null
        var keeperAt = Long.MIN_VALUE / 2
        var stationsAt = Long.MIN_VALUE / 2
        var pot: BlockPos? = null
        var spit: BlockPos? = null
        var oven: BlockPos? = null
        var counter: BlockPos? = null
        var readyAt = 0L
        var roastReadyAt = 0L
        var scene: KitchenScene? = null
        var bowls = 0
        var bowlsAt = 0L
        var tint = -1
        var tintAt = Long.MIN_VALUE / 2
        val roast = RoastDay()
        var fresh = true
    }

    /** The recs of one capital, and when a player was last on its ground. */
    private class Site(val recs: List<Rec>) {
        var seen = 0L
    }

    /** A scene running: whose it is, which keeper has it. */
    private class Run(val rec: Rec?, val scene: KitchenScene, val keeper: Townsperson)

    private val sites = HashMap<BlockPos, Site>()
    private val known = LinkedHashMap<BlockPos, CapitalPiece>()
    private val running = ArrayList<Run>()

    // What has been done since the server began, for `cook perf`
    private val begun = IntArray(KitchenSceneKind.entries.size)
    private val ending = IntArray(Ending.entries.size)
    private var byHand = 0
    private var checks = 0L
    private var nanos = 0L
    private var worst = 0L
    private var faults = 0

    /** Part I2's registration: the 20-tick check, the listener that hands a keeper back for a dish held out, its line for `cook perf` and the `cook scene` command. */
    fun register() {
        Cooking.onForget { forget() }
        CookStats.readout("kitchen scenes") { stats() }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        // Before the back door's own listener (normal priority) looks at who has the keeper: a dish held out ends the scene first
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, PlayerInteractEvent.EntityInteract::class.java, ::onUseEntity)
        SceneCommand.register()
    }

    private fun forget() {
        sites.clear()
        known.clear()
        running.clear()
        begun.fill(0)
        ending.fill(0)
        byHand = 0
        checks = 0L
        nanos = 0L
        worst = 0L
        faults = 0
    }

    /** Takes [p] for the [scene] now, as the commands do; whether it began. */
    fun take(level: ServerLevel, p: Townsperson, scene: KitchenSceneKind): Boolean = attempt(level, p, scene) == null

    // ---- The check ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        if (level.gameTime % CHECK != 7L) return
        val started = System.nanoTime()
        try {
            sweep(level)
            if (Cooking.townKitchens() && Days.enabled() && level.players().isNotEmpty()) round(level, level.gameTime)
        } catch (e: RuntimeException) {
            if (faults++ < 3) Guildmark.LOGGER.warn("A kitchen scene check failed: {}", e.toString())
        }
        val took = System.nanoTime() - started
        checks++
        nanos += took
        if (took > worst) worst = took
    }

    /** Scenes whose keeper has gone (unloaded, dead or taken by something that put its own director in) are put away; with Town Kitchens off the shelves are cleared. */
    private fun sweep(level: ServerLevel) {
        if (running.isNotEmpty()) {
            for (run in running.toList()) {
                val keeper = run.keeper
                if (keeper.isRemoved || !keeper.isAlive || keeper.director !== run.scene) run.scene.abandon(level)
            }
        }
        if (!Cooking.townKitchens() && sites.isNotEmpty()) {
            for (site in sites.values) for (rec in site.recs) if (rec.bowls > 0 && rec.scene == null) clearShelf(level, rec)
        }
    }

    private fun round(level: ServerLevel, now: Long) {
        for (capital in capitalsNear(level)) {
            val site = site(level, capital) ?: continue
            site.seen = now
            for (rec in site.recs) {
                if (!near(level, rec.lodging.box, if (rec.tavern) TAVERN_RANGE else BAKER_RANGE)) continue
                visit(level, rec, now)
            }
        }
        if (sites.size > 0) sites.values.removeIf { site -> now - site.seen > KEEP && site.recs.none { it.scene != null } }
    }

    /** The capitals players are on the ground of, from the chunks already loaded. */
    private fun capitalsNear(level: ServerLevel): Collection<CapitalPiece> {
        val found = LinkedHashMap<BlockPos, CapitalPiece>()
        for (player in level.players()) {
            if (player.isSpectator) continue
            val pos = player.blockPosition()
            val capital = known.values.firstOrNull { it.reaches(pos.x, pos.z) } ?: Furnish.capitalAt(level, pos)?.also { known[it.center] = it } ?: continue
            found.putIfAbsent(capital.center, capital)
        }
        return found.values
    }

    private fun site(level: ServerLevel, capital: CapitalPiece): Site? {
        sites[capital.center]?.let { return it }
        val places = CityPlaces.of(level, capital) ?: return null
        val recs = places.lodgings.values
            .filter { !it.farm && (it.kind in CityPlaces.TAVERNS || it.kind == Kind.BAKERY.name) }
            .sortedBy { it.id }
            .map { Rec(capital.center, it, it.kind != Kind.BAKERY.name) }
        return Site(recs).also { sites[capital.center] = it }
    }

    /** Whether a player (not a spectator) is within [range] blocks of [box]. */
    private fun near(level: ServerLevel, box: BoundingBox, range: Double): Boolean {
        val most = range * range
        for (p in level.players()) {
            if (p.isSpectator) continue
            val dx = maxOf(box.minX() - p.x, 0.0, p.x - box.maxX() - 1)
            val dy = maxOf(box.minY() - p.y, 0.0, p.y - box.maxY() - 1)
            val dz = maxOf(box.minZ() - p.z, 0.0, p.z - box.maxZ() - 1)
            if (dx * dx + dy * dy + dz * dz <= most) return true
        }
        return false
    }

    /** One tavern or bakery with a player near: its blocks and its shelf looked after, and if its keeper is due at the work and can be spared, taken. */
    private fun visit(level: ServerLevel, rec: Rec, now: Long) {
        if (rec.fresh) {
            // The first sight of it: the keeper goes to the pot in a little while, not the moment a player walks in
            rec.fresh = false
            rec.readyAt = now + 400 + level.random.nextInt(800)
            rec.roastReadyAt = now + 100 + level.random.nextInt(200)
        }
        if (now - rec.stationsAt >= STATIONS_LOOK) lookStations(level, rec, now)
        if (rec.tavern) upkeep(level, rec, now)
        if (rec.scene != null) return
        val kind = candidate(level, rec, now) ?: return
        if (!Furnish.loaded(level, rec.lodging)) return
        val keeper = keeperOf(level, rec, now) ?: return
        // The cry of the roast, when a player comes within twelve blocks of her at the bar: once a day
        if (kind == KitchenSceneKind.ROAST) cry(level, rec, keeper)
        if (kind == KitchenSceneKind.ROAST && now < rec.roastReadyAt) return
        val life = Days.life(keeper) ?: return
        if (Days.stretch(life, level.overworldClockTime).doing != Doing.WORK || !keeper.routine.anchored) return
        if (reason(level, keeper) != null || level.getNearestPlayer(keeper, KEEPER_RANGE) == null) return
        val stage = stageFor(level, rec, kind, keeper) ?: return
        launch(level, rec, keeper, kind, stage, forced = false, chain = kind == KitchenSceneKind.PEEL && rec.counter != null)
    }

    /** Which scene this rec is due for now, if any: only the clock and the cool-downs are looked at. */
    private fun candidate(level: ServerLevel, rec: Rec, now: Long): KitchenSceneKind? {
        val offset = Days.offsetOf(level.overworldClockTime)
        if (rec.tavern) {
            val day = Days.dayOf(level.overworldClockTime)
            if (rec.roast.day != day) {
                rec.roast.day = day
                rec.roast.cried = false
                rec.roast.carved = false
            }
            // On a rest day, in the roast's hours, the roast is the tavern's scene until it is carved
            if (Cooking.roast() && rec.spit != null && Days.restDay(day) && offset >= ROAST_FROM && offset < ROAST_TO && !rec.roast.carved) return KitchenSceneKind.ROAST
            if (rec.pot != null && now >= rec.readyAt && offset >= SIMMER_FROM && offset < SIMMER_TO) return KitchenSceneKind.TAVERN
            return null
        }
        if (now < rec.readyAt) return null
        return if (rec.oven != null) KitchenSceneKind.PEEL else if (rec.counter != null) KitchenSceneKind.SLICE else null
    }

    private val ROAST_FROM = Days.clock(10.5)
    private val ROAST_TO = Days.clock(13.0)
    private val SIMMER_FROM = Days.clock(StewpotBlockEntity.HOUSE_FROM)
    private val SIMMER_TO = Days.clock(StewpotBlockEntity.HOUSE_TO)
    private val MEAL_NOON = Days.clock(11.5)..Days.clock(13.5)
    private val MEAL_EVENING = Days.clock(17.5)..Days.clock(21.5)

    /** The keeper of [rec]'s building: found through its box once in a while and kept; null if there is none now. */
    private fun keeperOf(level: ServerLevel, rec: Rec, now: Long): Townsperson? {
        val kept = rec.keeper
        if (kept != null && !kept.isRemoved && kept.isAlive && rec.lodging.contains(kept.blockPosition())) return kept
        if (now - rec.keeperAt < KEEPER_LOOK) return null
        rec.keeperAt = now
        val role = if (rec.tavern) TownRole.INNKEEPER else TownRole.BAKER
        val centre = Vec3.atCenterOf(rec.lodging.box.center)
        rec.keeper = level.getEntitiesOfClass(Townsperson::class.java, AABB.of(rec.lodging.box)) { it.role == role && it.resident >= 0 && it.isAlive }
            .minByOrNull { it.distanceToSqr(centre) }
        return rec.keeper
    }

    /** The cry of the rest-day roast: said once on the day, to a player who first comes within twelve blocks of the innkeeper. */
    private fun cry(level: ServerLevel, rec: Rec, keeper: Townsperson) {
        if (rec.roast.cried || keeper.director != null || keeper.isSleeping || level.getNearestPlayer(keeper, 12.0) == null) return
        if (SceneLines.say(keeper, "kitchen.scene.roast", "roast")) rec.roast.cried = true
    }

    // ---- The blocks ----

    /** The pot, spit and oven inside [inside] (or round [centre] if it is null), the nearest first. */
    private class Found {
        var pot: BlockPos? = null
        var spit: BlockPos? = null
        var oven: BlockPos? = null
    }

    private fun find(level: ServerLevel, centre: BlockPos, radius: Int, inside: BoundingBox?): Found {
        val found = Found()
        for (station in Stations.near(level, centre, radius)) {
            val pos = station.blockPos
            if (inside != null && !inside.isInside(pos)) continue
            when (station) {
                is StewpotBlockEntity -> if (found.pot == null && station.mode == StewpotMode.HOUSE) found.pot = pos
                is SpitBlockEntity -> if (found.spit == null) found.spit = pos
                is OvenBlockEntity -> if (found.oven == null) found.oven = pos
            }
        }
        return found
    }

    private fun lookStations(level: ServerLevel, rec: Rec, now: Long) {
        rec.stationsAt = now
        if (!Furnish.loaded(level, rec.lodging)) return
        val box = rec.lodging.box
        val found = find(level, box.center, maxOf(box.xSpan, box.zSpan) / 2 + 3, box)
        rec.pot = found.pot
        rec.spit = found.spit
        rec.oven = found.oven
        if (!rec.tavern) {
            val at = found.oven ?: rec.lodging.stations.firstOrNull()?.pos ?: box.center
            rec.counter = counterNear(level, at, rec.lodging.y, Vec3.atCenterOf(at), box)
        }
    }

    /** A worktop within six blocks of [at] at hip height [floorY] that a body can stand beside: the baker's counter. */
    private fun counterNear(level: ServerLevel, at: BlockPos, floorY: Int, from: Vec3, inside: BoundingBox?): BlockPos? {
        val candidates = Places.all(level, at, 6, 1, 48) { state, pos -> worktop(level, state, pos, floorY) }
            .filter { inside == null || inside.isInside(it) }
            .sortedWith(compareBy({ if (level.getBlockState(it).`is`(BlockTags.PLANKS)) 1 else 0 }, { it.distToCenterSqr(from.x, from.y, from.z) }))
        return candidates.firstOrNull { Stands.at(level, it, from) != null }
    }

    /** A worktop at hip height with room above it (as the baker's own job takes one): a crafting table, a barrel, a top slab or planks. */
    private fun worktop(level: ServerLevel, state: BlockState, pos: BlockPos, floorY: Int): Boolean {
        if (pos.y != floorY || !Places.free(level, pos.above())) return false
        return state.`is`(Blocks.CRAFTING_TABLE) || state.`is`(Blocks.BARREL) ||
            state.block is SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.TOP || state.`is`(BlockTags.PLANKS)
    }

    private fun potOf(level: ServerLevel, rec: Rec): StewpotBlockEntity? {
        val pos = rec.pot ?: return null
        if (!level.isLoaded(pos)) return null
        val pot = (level.getBlockEntity(pos) as? StewpotBlockEntity)?.takeIf { it.mode == StewpotMode.HOUSE }
        if (pot == null) rec.pot = null
        return pot
    }

    // ---- The house pot as a display ----

    /** The pot's shelf and tint, looked after while a player is near: bowls taken one by one, leftovers cleared, tonight's pot in the surface. */
    private fun upkeep(level: ServerLevel, rec: Rec, now: Long) {
        val pot = potOf(level, rec) ?: return
        if (rec.scene == null) {
            // Bowls left on the shelf by a world that was saved mid-evening are nobody's: cleared
            if (rec.bowls == 0 && pot.view().bowls > 0) clearShelf(level, rec)
            if (rec.bowls > 0 && now - rec.bowlsAt >= BOWL_TAKEN) {
                rec.bowls--
                rec.bowlsAt = now
                pot.showHouse(live(pot, now), rec.bowls)
            }
        }
        if (now - rec.tintAt >= TINT_LOOK) {
            rec.tintAt = now
            val rgb = tint(tonightsPot(level, rec.capital))
            if (rgb != rec.tint) {
                rec.tint = rgb
                pot.showHouseTint(rgb)
            }
        }
    }

    /** The pot's swirl as it stands (a swirl already over is none). */
    private fun live(pot: StewpotBlockEntity, now: Long): Long = if (pot.swirlUntil > now) pot.swirlUntil else 0L

    private fun clearShelf(level: ServerLevel, rec: Rec) {
        rec.bowls = 0
        val pot = potOf(level, rec) ?: return
        pot.showHouse(live(pot, level.gameTime), 0)
    }

    /** The pot dish a cook sold the inn that the pantry holds: the best tier, the newest of that tier; null if it holds none (the inn serves its own pottage). */
    internal fun tonightsPot(level: ServerLevel, capital: BlockPos): Lot? {
        var best: Lot? = null
        for (lot in Kitchens.get(level).lots(capital.asLong(), Door.INN)) {
            if (lot.cook == null || lot.cookName.isEmpty() || lot.portions <= 0 || lot.kind.station != Station.STEWPOT) continue
            if (best == null || lot.tier.ordinal >= best.tier.ordinal) best = lot
        }
        return best
    }

    /** The surface's colour for [lot]: the stew's brown, the broth's gold, the chowder's cream; 0 (the house's own pottage) for none and for a pottage. */
    private fun tint(lot: Lot?): Int = when (lot?.kind?.id) {
        "stew" -> 0x7B4B2A
        "broth" -> 0xC8A34F
        "chowder" -> 0xE2D6B2
        else -> 0
    }

    /** What the tasting says: 0 nothing (it is a coin, one time in two), 1 it wants salt, 2 it is as it should be. A cook's dish of Sound or better is right; a plain one wants salt; the house's own pottage wants salt until it has simmered a while. */
    private fun verdictFor(level: ServerLevel, capital: BlockPos, p: Townsperson): Int {
        if (p.random.nextInt(2) != 0) return 0
        val lot = tonightsPot(level, capital)
        val salt = if (lot != null) lot.tier.ordinal < Tier.SOUND.ordinal else Days.offsetOf(level.overworldClockTime) - SIMMER_FROM < 3000
        return if (salt) 1 else 2
    }

    // ---- Beginning a scene ----

    /** Whether [p] can be taken off their day just now, or why not (in words that follow their name: "is asleep"). */
    internal fun reason(level: ServerLevel, p: Townsperson): String? = when {
        !p.isAlive || p.isRemoved -> "is not about"
        p.isSleeping -> "is asleep"
        p.director != null -> "has something else in hand"
        p.captiveOf >= 0 || p.isLeader || p.watcher || p.crowd || p.tale >= 0 -> "is not one of the town's keepers at work"
        p.isPassenger || p.seat != null -> "is sitting or riding"
        p.attending != null -> "is talking with someone"
        p.activity == Activity.FLEE || p.hurtTime > 0 || p.lastHurtByMob != null && p.tickCount - p.lastHurtByMobTimestamp < 200 -> "is hurt or afraid"
        !level.isPositionEntityTicking(p.blockPosition()) -> "is not in a part of the town that is running"
        else -> null
    }

    /** The scene's blocks for [kind] at [rec] as they stand, or null if one is missing. */
    private fun stageFor(level: ServerLevel, rec: Rec, kind: KitchenSceneKind, p: Townsperson): Stage? {
        when (kind) {
            KitchenSceneKind.TAVERN -> {
                val pot = potOf(level, rec) ?: return null
                return Stage(rec.pot, null, null, null, pot.view().bowls, verdictFor(level, rec.capital, p))
            }
            KitchenSceneKind.ROAST -> {
                val spit = rec.spit?.takeIf { level.isLoaded(it) && level.getBlockEntity(it) is SpitBlockEntity } ?: return null
                return Stage(null, spit, null, null, 0, 0)
            }
            KitchenSceneKind.PEEL -> {
                val oven = rec.oven?.takeIf { idle(level, it) } ?: return null
                return Stage(null, null, oven, rec.counter, 0, 0)
            }
            KitchenSceneKind.SLICE -> {
                val counter = rec.counter ?: return null
                return Stage(null, null, null, counter, 0, 0)
            }
        }
    }

    /** Whether the Bake oven at [pos] is loaded and nobody's cooking in it (a keeper never works at a player's tray). */
    private fun idle(level: ServerLevel, pos: BlockPos): Boolean =
        level.isLoaded(pos) && (level.getBlockEntity(pos) as? OvenBlockEntity)?.vessel?.state == Vessel.State.EMPTY

    /** Takes [keeper] for a scene of [kind] on [stage]; the scene if it began. */
    private fun launch(level: ServerLevel, rec: Rec?, keeper: Townsperson, kind: KitchenSceneKind, stage: Stage, forced: Boolean, chain: Boolean): KitchenScene? {
        val roast = rec?.roast ?: RoastDay()
        val carveAt = if (!forced && kind == KitchenSceneKind.ROAST) Days.clock(12.0) else -1
        val scene = KitchenScene(kind, stage, forced, roast, carveAt, chain) { s, why -> ended(s, why) }
        if (!scene.start(level, keeper)) return null
        running.add(Run(rec, scene, keeper))
        rec?.scene = scene
        begun[kind.ordinal]++
        if (forced) byHand++
        return scene
    }

    /** A scene is over: its rec hears when the next may be, and what it left on the shelf. */
    private fun ended(scene: KitchenScene, why: Ending) {
        val run = running.firstOrNull { it.scene === scene } ?: return
        running.remove(run)
        ending[why.ordinal]++
        val rec = run.rec ?: return
        val level = run.keeper.level() as? ServerLevel ?: return
        val now = level.gameTime
        rec.scene = null
        if (rec.keeper === run.keeper) rec.keeperAt = now
        when (scene.kind) {
            KitchenSceneKind.TAVERN -> {
                rec.bowls = scene.bowls
                rec.bowlsAt = now
                rec.readyAt = now + cool(level, MEAL_COOL.takeIf { meal(level) } ?: OTHER_COOL, why)
            }
            KitchenSceneKind.ROAST -> rec.roastReadyAt = now + cool(level, ROAST_COOL, why)
            KitchenSceneKind.PEEL, KitchenSceneKind.SLICE -> rec.readyAt = now + cool(level, BAKER_COOL, why)
        }
    }

    /** A cool-down of about [base] ticks (a fifth either way), shorter after a scene cut off by a word, and never under a minute after one that lost its way. */
    private fun cool(level: ServerLevel, base: Long, why: Ending): Long {
        val jitter = 0.8 + 0.4 * level.random.nextDouble()
        val scale = when (why) {
            Ending.DONE, Ending.TIMEOUT -> 1.0
            Ending.LOST, Ending.TAKEN -> 0.5
            else -> 0.6
        }
        val ticks = (base * jitter * scale).toLong()
        return if (why == Ending.LOST || why == Ending.TAKEN) maxOf(ticks, 1200L) else ticks
    }

    /** Whether it is one of the meal hours (11:30 to 13:30, 17:30 to 21:30). */
    private fun meal(level: ServerLevel): Boolean {
        val offset = Days.offsetOf(level.overworldClockTime)
        return offset in MEAL_NOON || offset in MEAL_EVENING
    }

    // ---- By hand ----

    /**
     * Plays a scene of [kind] on [p] now, whatever the clock and the cool-down say: null if it began, else why not, as a sentence
     * (`/guildmark cook scene` says it). It still needs Town Kitchens on (and the roast's rule for the roast), a keeper of the
     * right trade who can be spared, a player within 40 blocks, and the blocks to work at within 20 of the keeper.
     */
    internal fun attempt(level: ServerLevel, p: Townsperson, kind: KitchenSceneKind): String? {
        val tavern = kind == KitchenSceneKind.TAVERN || kind == KitchenSceneKind.ROAST
        val noun = if (tavern) "The innkeeper" else "The baker"
        if (!Cooking.townKitchens()) return "Town Kitchens is off, so no kitchen scene plays."
        if (kind == KitchenSceneKind.ROAST && !Cooking.roast()) return "The Rest-day Roast rule is off, so there is no roast."
        if (p.role != (if (tavern) TownRole.INNKEEPER else TownRole.BAKER)) return "That is not ${if (tavern) "an innkeeper" else "a baker"}."
        reason(level, p)?.let { return "$noun $it." }
        if (level.getNearestPlayer(p, 40.0) == null) return "No player is within 40 blocks of $noun, and a scene is never played to no one."
        val rec = recOf(p)
        val here = p.blockPosition()
        val found = if (rec != null) {
            val f = Found()
            f.pot = rec.pot
            f.spit = rec.spit
            f.oven = rec.oven
            f
        } else {
            find(level, here, 20, null)
        }
        // A rec that has not looked yet, or a hand-placed pot the round has not seen: look now
        if (found.pot == null && found.spit == null && found.oven == null) find(level, here, 20, null).let {
            found.pot = it.pot
            found.spit = it.spit
            found.oven = it.oven
        }
        val stage: Stage
        when (kind) {
            KitchenSceneKind.TAVERN -> {
                val pos = found.pot ?: return "$noun has no house pot within 20 blocks (a Stewpot in house mode; `cook fit` fits one into an old tavern)."
                val pot = (level.getBlockEntity(pos) as? StewpotBlockEntity)?.takeIf { it.mode == StewpotMode.HOUSE } ?: return "The pot at ${pos.x} ${pos.y} ${pos.z} is not a house pot."
                stage = Stage(pos, null, null, null, pot.view().bowls, verdictFor(level, rec?.capital ?: here, p))
            }
            KitchenSceneKind.ROAST -> {
                val pos = found.spit ?: return "$noun has no Spit within 20 blocks."
                stage = Stage(null, pos, null, null, 0, 0)
            }
            KitchenSceneKind.PEEL -> {
                val pos = found.oven ?: return "$noun has no Bake oven within 20 blocks."
                if (!idle(level, pos)) return "The Bake oven at ${pos.x} ${pos.y} ${pos.z} is in use, and $noun does not draw a player's tray."
                stage = Stage(null, null, pos, null, 0, 0)
            }
            KitchenSceneKind.SLICE -> {
                val from = Vec3.atCenterOf(here)
                val counter = rec?.counter ?: counterNear(level, here, here.y, from, null) ?: return "$noun has no counter within 6 blocks (a crafting table, barrel, top slab or planks at hip height)."
                stage = Stage(null, null, null, counter, 0, 0)
            }
        }
        launch(level, rec, p, kind, stage, forced = true, chain = false) ?: return "$noun has no room to stand at the ${workAt(kind)}."
        return null
    }

    private fun workAt(kind: KitchenSceneKind): String = when (kind) {
        KitchenSceneKind.TAVERN -> "pot"
        KitchenSceneKind.ROAST -> "Spit"
        KitchenSceneKind.PEEL -> "oven"
        KitchenSceneKind.SLICE -> "counter"
    }

    /** The rec of the building [p] stands in, if the round has met it. */
    private fun recOf(p: Townsperson): Rec? {
        val places = Days.life(p)?.places as? CityPlaces ?: return null
        val site = sites[places.capital.center] ?: return null
        return site.recs.firstOrNull { it.lodging.contains(p.blockPosition()) }
    }

    /** Ends every scene running, handing the keepers back (for `cook scene stop`); how many. */
    internal fun stopAll(): Int {
        val all = running.toList()
        for (run in all) run.scene.handBack(run.keeper)
        return all.size
    }

    /** How many scenes are running. */
    internal fun scenesRunning(): Int = running.size

    // ---- A dish held out ----

    /**
     * A player holds a dish out to a keeper who is at a scene: the scene ends first, so that the back door (which answers "one
     * moment" to a keeper under any other director) finds the keeper free and takes the dish in the same click.
     */
    private fun onUseEntity(event: PlayerInteractEvent.EntityInteract) {
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        if (player.isSpectator || player.mainHandItem.item !is DishItem) return
        val keeper = event.target as? Townsperson ?: return
        (keeper.director as? KitchenScene)?.handBack(keeper)
    }

    // ---- What it costs ----

    private fun stats(): String {
        val kinds = KitchenSceneKind.entries.joinToString(", ") { "${it.name.lowercase()} ${begun[it.ordinal]}" }
        val ends = Ending.entries.joinToString(", ") { "${it.name.lowercase()} ${ending[it.ordinal]}" }
        val each = if (checks > 0) nanos / checks / 1000.0 else 0.0
        return "${sites.size} capitals on the books, ${running.size} scenes running; begun $kinds ($byHand by hand); ended $ends; " +
            "$checks checks at ${"%.1f".format(each)} us each, the slowest ${worst / 1000} us" +
            if (Cooking.townKitchens()) "" else " (Town Kitchens is off: none plays)"
    }
}
