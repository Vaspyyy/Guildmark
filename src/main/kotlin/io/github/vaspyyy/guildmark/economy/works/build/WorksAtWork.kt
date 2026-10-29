package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.cooking.shop.CookShops
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.Route
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.works.Ovens
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.streets.Streets
import io.github.vaspyyy.guildmark.trade.StoreLife
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The works at their work (living-world/design-influence.md section 3, "Afterwards"), each second of a standing works
 * whose land is loaded, from the records as they really are: the second oven's flue alight while it bakes (the ovens
 * turning out more this hour than the plan's own could: the same test the works' easing has); the granary's doors
 * swung open while it gives or fills (its bins' store moving), with one of the town's carters at them with a sack;
 * the tower's beacon out by day, lit at dusk by its watch (or by the clock once it's dark, with nobody up there to
 * do it), and flaring with the horn when its watch sees a strike; a stall's awning drawn up while it trades (by day,
 * with something on it to sell) and let down at night. Then each works' first use, once: the baker handing out the
 * new oven's first loaves at the bakery, the granary's first filling coming in on its six wagons (carried in by
 * [Unloads]' porters, its sacks tipped at the doors), the tower's watch posted, a stall's keeper setting out its
 * first wares. And each morning the mending: whatever of its drawing is gone is laid again into air, a labourer
 * bringing the first few blocks over by hand where a player is near to see it.
 *
 * Everything that changes a block changes it at the moment its reason changes, and leaves it alone between (a
 * player may shut the granary's doors or put the flue out; the next change sets it right). What's in hand here is
 * kept in memory only: after a restart each thing is found and set as it should be, without a sound.
 */
internal object WorksAtWork {
    /** How long the granary's doors stand open after its store last moved (a market hour and a bit). */
    private const val DOORS_OPEN = 1100L

    /** How long a beacon flares when its watch sees a strike, and at its first lighting. */
    private const val FLARE = 600L
    private const val FIRST_FLARE = 100L

    /** SOUND_HORN's blow (its tick 12), when the horn's heard; and how far round a tower's beacon its lookouts stand. */
    private const val HORN_BLOW = 12L
    private const val DECK = 4.0

    /** The clock: the beacon put out in the morning, lit at dusk by its watch, and by the clock once it's dark. */
    private const val MORNING = 6.0
    private const val DUSK = 17.5
    private const val DARK = 19.5

    /** A stall trades through its keeper's working day: from seven until half past five. */
    private const val TRADES_FROM = 7.0
    private const val TRADES_TO = 17.5

    /** The mending's morning: from half past six until eleven. */
    private const val MEND_FROM = 6.5
    private const val MEND_TO = 11.0

    /** How many of the morning's blocks a labourer brings over by hand (the rest are laid as he goes). */
    private const val MEND_BY_HAND = 3

    /** How near a player must be for the town's people to be sent on the works' errands (to be seen). */
    private const val SEEN = 48.0

    /** How far round the works the town's people are looked for to send. */
    private const val SEEK = 64.0

    /** A first use nobody was near to see is done with after this long (three days). */
    private const val UNSEEN_FIRST = 72000L

    /** The tick of PUT_DOWN a block leaves the arms, and of POUR_SACK the sack is tipped (as Trades has them). */
    private const val LAY_AT = 11
    private const val TIPPED = 12
    private const val PICKED = 8

    /** What of a works' drawing does its work, found once for each build: its flue's fire, its doors, its beacon, its awning, its counters. */
    private class Fittings(val planned: WorksPlans.Planned) {
        val campfires = ArrayList<BlockPos>()

        /** The doors' lower halves, and where to stand outside each. */
        val doors = ArrayList<BlockPos>()
        val doorFronts = ArrayList<BlockPos>()

        /** The ovens' mouths (their smoker and furnace). */
        val mouths = ArrayList<BlockPos>()
        var beacon: BlockPos? = null
        var awning: BlockPos? = null
        val pitches = ArrayList<BlockPos>()

        init {
            val f = planned.frame
            for (s in planned.plan.steps) {
                if (s.kind != StepKind.BLOCK) continue
                val b = s.b
                when {
                    b.m == M.CAMPFIRE -> campfires.add(f.world(s.x, s.y, s.z))
                    b.m == M.DOOR && b.part == 0 -> {
                        doors.add(f.world(s.x, s.y, s.z))
                        doorFronts.add(f.world(s.x, s.y, s.z - 1))
                    }
                    b.m == M.SMOKER || b.m == M.FURNACE -> mouths.add(f.world(s.x, s.y, s.z))
                    b.v == WorksDrawing.MARK && b.m == M.CAULDRON -> beacon = f.world(s.x, s.y, s.z)
                    b.v == WorksDrawing.MARK && b.m == M.WOOL -> awning = f.world(s.x, s.y, s.z)
                    b.v == WorksDrawing.MARK && b.m == M.CRATE -> pitches.add(f.world(s.x, s.y, s.z))
                }
            }
        }
    }

    private val fittings = HashMap<Int, Fittings>()

    /** What each standing works shows as last set (the flue lit, the doors open, the awning up): changed only when its reason changes. */
    private val shown = HashMap<Int, Boolean>()

    /** Each granary's store as last seen, and until when its doors stand open. */
    private val stores = HashMap<Int, Double>()
    private val doorsUntil = HashMap<Int, Long>()

    /** Each tower's beacon: flaring until when; and the towers lit at their topping out, since when (still going up). */
    private val flares = HashMap<Int, Long>()
    private val firsts = HashMap<Int, Long>()

    /** The town's people on a works' errand (by works id and errand): who, and their part. */
    private class Errand(val p: Townsperson, val aside: Aside)

    private val errands = HashMap<Long, Errand>()
    private const val FIRST_USE = 0
    private const val DOORS = 1
    private const val AWNING = 2
    private const val MENDING = 3

    private fun key(id: Int, errand: Int): Long = id * 8L + errand

    fun reset() {
        for (e in errands.values) e.aside.end(e.p, drop = false)
        errands.clear()
        fittings.clear()
        shown.clear()
        stores.clear()
        doorsUntil.clear()
        flares.clear()
        firsts.clear()
        horns.clear()
    }

    private fun fittings(id: Int, planned: WorksPlans.Planned): Fittings {
        fittings[id]?.let { if (it.planned === planned) return it }
        return Fittings(planned).also { fittings[id] = it }
    }

    /** Works [id]'s fittings as its record has it now, if it's sited. */
    private fun fittings(level: ServerLevel, id: Int): Fittings? {
        val rec = WorksBuildData.get(level)[id]?.takeIf { it.sited && !it.removed } ?: return null
        return fittings(id, WorksPlans.of(rec))
    }

    private fun hour(level: ServerLevel): Double = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0

    // ---- The towers' beacons, for the lookouts ----

    object Beacon : Lookout.Beacon {
        /** Where a standing tower's brazier is, while its land is loaded and it stands there. */
        override fun at(level: ServerLevel, w: WorksView): BlockPos? {
            if (w.kind != WorksKind.TOWER || w.state != WorksState.DONE) return null
            val pos = fittings(level, w.id)?.beacon ?: return null
            if (!level.isLoaded(pos) || !level.getBlockState(pos).`is`(WorksBlocks.BEACON.get())) return null
            return pos
        }

        override fun lit(level: ServerLevel, w: WorksView): Boolean {
            val pos = at(level, w) ?: return false
            return level.getBlockState(pos).getValue(WorksBeaconBlock.FIRE) != BeaconFire.OUT
        }

        /** The lookout's torch goes in (LIGHT_BEACON's flare is the client's: its moment plays works.beacon_flare). */
        override fun light(level: ServerLevel, w: WorksView, by: Townsperson) {
            val pos = at(level, w) ?: return
            fire(level, pos, BeaconFire.LIT, sound = false)
        }
    }

    /** Sets the beacon at [pos] to [to] (if it isn't), with its sound if [sound]: the flare's roar, a hiss going out. */
    private fun fire(level: ServerLevel, pos: BlockPos, to: BeaconFire, sound: Boolean) {
        val state = level.getBlockState(pos)
        if (!state.`is`(WorksBlocks.BEACON.get())) return
        val was = state.getValue(WorksBeaconBlock.FIRE)
        if (was == to) return
        level.setBlock(pos, state.setValue(WorksBeaconBlock.FIRE, to), Block.UPDATE_ALL)
        val be = level.getBlockEntity(pos) as? WorksBeaconBlockEntity
        when (to) {
            BeaconFire.FLARE -> {
                be?.flare()
                if (was == BeaconFire.OUT) be?.lit()
                if (sound) WorksBlocks.play(level, pos, WorksBlocks.BEACON_FLARE)
                level.sendParticles(ParticleTypes.LAVA, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, 8, 0.3, 0.2, 0.3, 0.0)
            }
            BeaconFire.LIT -> {
                if (was == BeaconFire.OUT) {
                    be?.lit()
                    if (sound) level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.5f, 0.9f)
                }
            }
            BeaconFire.OUT -> {
                if (sound) level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 0.9f)
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, 6, 0.2, 0.2, 0.2, 0.01)
            }
        }
    }

    /** The tower's beacon has its first fire at its topping out: flaring a moment before them all if [near], then burning. */
    fun firstFire(level: ServerLevel, rec: BuildRec, site: TownWorksSite, near: Boolean) {
        val pos = fittings(rec.id, site.planned).beacon ?: return
        if (!level.isLoaded(pos)) return
        val now = level.gameTime
        if (near) {
            flares[rec.id] = now + FIRST_FLARE
            fire(level, pos, BeaconFire.FLARE, sound = true)
        } else {
            fire(level, pos, BeaconFire.LIT, sound = false)
        }
        firsts[rec.id] = now
    }

    /**
     * A strike seen from [w], a standing tower (caravan/Watchmen, as its watch rides out): one of its lookouts up there
     * turns to [at] (the wagon) and blows the horn (SOUND_HORN, heard from its blow at [HORN_BLOW] to 128 blocks), the
     * beacon flaring with it; and as the horn's done they all point and call ([Lookout.riders]). With nobody up there
     * the horn's heard from its deck at once.
     */
    fun strike(level: ServerLevel, w: WorksView, at: BlockPos?) {
        if (horns.any { it.w.id == w.id }) return
        val pos = Beacon.at(level, w)
        val by = pos?.let { blower(level, it) }
        if (pos == null || by == null) {
            blow(level, w, pos, null)
            Lookout.riders(level, w.capital, w.road, at)
            return
        }
        val towards = at?.let { Vec3.atCenterOf(it) } ?: Vec3.atCenterOf(pos)
        by.navigation.stop()
        by.face(by.yawTo(towards))
        by.lookAt(towards)
        by.act(Activity.SOUND_HORN)
        horns += Horn(by, level.gameTime, w, at)
    }

    /** Who's to blow the horn at the tower whose beacon is at [beacon]: the nearest of its lookouts up there. */
    private fun blower(level: ServerLevel, beacon: BlockPos): Townsperson? =
        level.getEntitiesOfClass(Townsperson::class.java, AABB(beacon).inflate(DECK, 3.0, DECK)) { it.isAlive && Lookout.up(it) }
            .minByOrNull { it.distanceToSqr(Vec3.atCenterOf(beacon)) }

    /** The horn heard, from [by] or the tower's deck, and its beacon (at [pos]) flaring. */
    private fun blow(level: ServerLevel, w: WorksView, pos: BlockPos?, by: Townsperson?) {
        flares[w.id] = level.gameTime + FLARE
        val from = by?.blockPosition()?.above() ?: pos?.above() ?: w.site?.origin?.above(8)
        if (from != null) WorksBlocks.play(level, from, WorksBlocks.HORN)
        if (pos != null) fire(level, pos, BeaconFire.FLARE, sound = true)
    }

    /** A lookout's horn being blown: who, since when (game time), for which tower, at what. */
    private class Horn(val p: Townsperson, val start: Long, val w: WorksView, val at: BlockPos?) {
        var blown = false
    }

    private val horns = ArrayList<Horn>()

    /** The horns being blown: each heard at SOUND_HORN's blow, and the watch pointing as it's done (or cut short). */
    private fun horns(level: ServerLevel) {
        val now = level.gameTime
        val each = horns.iterator()
        while (each.hasNext()) {
            val h = each.next()
            val t = now - h.start
            val cut = !h.p.isAlive || h.p.level() !== level || h.p.activity != Activity.SOUND_HORN
            if (!h.blown && (t >= HORN_BLOW || cut)) {
                h.blown = true
                blow(level, h.w, Beacon.at(level, h.w), h.p.takeIf { it.isAlive && it.level() === level })
            }
            if (t >= Activity.SOUND_HORN.ticks || cut) {
                each.remove()
                Lookout.riders(level, h.w.capital, h.w.road, h.at)
            }
        }
    }

    /** The towers lit at their topping out while they're still going up: out the next morning, and back from a flare. */
    fun everyTick(level: ServerLevel) {
        if (horns.isNotEmpty()) horns(level)
        if (level.gameTime % 20L != 3L || firsts.isEmpty()) return
        val now = level.gameTime
        val hour = hour(level)
        val data = WorksData.get(level)
        for ((id, since) in firsts.entries.toList()) {
            val w = data.works[id]
            val pos = fittings(level, id)?.beacon
            if (w == null || pos == null || w.state == WorksState.DONE) {
                firsts.remove(id)
                continue
            }
            if (!level.isLoaded(pos)) continue
            val flaring = (flares[id] ?: 0L) > now
            val day = hour >= MORNING && hour < DUSK
            when {
                flaring -> {}
                day && now - since > 2400L -> {
                    fire(level, pos, BeaconFire.OUT, sound = WorksBuild.watched(id))
                    firsts.remove(id)
                }
                else -> fire(level, pos, BeaconFire.LIT, sound = false)
            }
        }
    }

    // ---- Each second of a standing works ----

    /** Each second of a standing works whose land is loaded: its work, its first use, and the morning's mending. */
    fun second(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, near: Boolean) {
        val f = fittings(w.id, site.planned)
        val hour = hour(level)
        when (w.kind) {
            WorksKind.OVEN -> flue(level, w, f)
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> doors(level, w, site, f, near)
            WorksKind.TOWER -> beacon(level, w, f, hour)
            WorksKind.STALL, WorksKind.COOKSHOP -> awning(level, w, site, f, hour, near)
            WorksKind.STORE, WorksKind.COUNTING_HOUSE -> StoreLife.second(level, w, rec, site, hour, near)
        }
        if (w.kind == WorksKind.COOKSHOP) CookShops.second(level, w, site, capital, hour)
        if (!rec.firstUse) firstUse(level, w, rec, site, f, hour, near)
        mend(level, w, rec, site, capital, hour, near)
    }

    /** Whether [w]'s thing (its flue, its doors, its awning) should show [want] now: true the moment it changes (and the first time it's seen, [first]). */
    private fun changed(w: WorksRec, want: Boolean, first: (Boolean) -> Unit): Boolean {
        val was = shown[w.id]
        shown[w.id] = want
        if (was == null) {
            first(want)
            return false
        }
        return was != want
    }

    /** The second oven's flue: its fire lit while the ovens bake more than the plan's own could (the second oven at work). */
    private fun flue(level: ServerLevel, w: WorksRec, f: Fittings) {
        if (f.campfires.isEmpty()) return
        val entry = Markets.get(level)[BlockPos.of(w.capital)] ?: return
        val m = entry.market
        val tools = if (m.stock(Good.TOOLS) >= 1.0) 1.0 else Economy.WORN
        val baking = Works.enabled() && m.busy(Maker.OVENS) && m.worked[Maker.OVENS.ordinal] > Ovens.bake(m, entry.town, added = false) * tools + 1e-6
        val set = { lit: Boolean, sound: Boolean ->
            for (pos in f.campfires) {
                if (!level.isLoaded(pos)) continue
                val state = level.getBlockState(pos)
                if (state.block !is CampfireBlock || state.getValue(CampfireBlock.LIT) == lit) continue
                level.setBlock(pos, state.setValue(CampfireBlock.LIT, lit), Block.UPDATE_ALL)
                if (sound) level.playSound(null, pos, if (lit) SoundEvents.FIRECHARGE_USE else SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4f, 1f)
            }
        }
        if (changed(w, baking) { set(it, false) }) set(baking, true)
    }

    /** The granary's doors: swung open while its store moves (it gives to the mill, or fills from the wagons), shut after. */
    private fun doors(level: ServerLevel, w: WorksRec, site: TownWorksSite, f: Fittings, near: Boolean) {
        if (f.doors.isEmpty()) return
        val entry = Markets.get(level)[BlockPos.of(w.capital)] ?: return
        val m = entry.market
        var held = 0.0
        for (g in Good.entries) held += m[Site.GRANARY, g]
        val now = level.gameTime
        val was = stores.put(w.id, held)
        if (was != null && abs(held - was) > 1e-6) doorsUntil[w.id] = now + DOORS_OPEN
        // Open too while a wagon's sacks are being carried in to them
        val carried = Unloads.filling(w.id)
        val open = Works.enabled() && (now < (doorsUntil[w.id] ?: 0L) || carried)
        val giving = was != null && held < was - 1e-6 || m.carted[Route.RESERVE.ordinal] > 1e-6
        if (!changed(w, open) { swing(level, f, it, sound = false) }) return
        swing(level, f, open, sound = true)
        if (open && near && !carried) carter(level, w, site, f, giving)
    }

    /** Works [id]'s door as it was drawn (Works.door): the granary's first door's foot, or the oven's first mouth; null with neither. */
    fun door(level: ServerLevel, id: Int): BlockPos? {
        val f = fittings(level, id) ?: return null
        return f.doors.firstOrNull() ?: f.mouths.firstOrNull()
    }

    /** Where a granary's sacks are tipped in: the stand before its first door, and the doorway (Unloads' porters). */
    fun intake(level: ServerLevel, id: Int): Pair<BlockPos, Vec3>? {
        val f = fittings(level, id) ?: return null
        if (f.doors.isEmpty()) return null
        val stand = f.doorFronts[0].takeIf { Places.standable(level, it) } ?: WorksBuild.site(id)?.let { BlockPos.containing(it.gate()) } ?: return null
        return stand to Vec3.atCenterOf(f.doors[0]).add(0.0, 0.2, 0.0)
    }

    /** All of [f]'s doors set [open] (the other half of each follows, as a door's halves do), with the granary's creak once. */
    private fun swing(level: ServerLevel, f: Fittings, open: Boolean, sound: Boolean) {
        var any: BlockPos? = null
        for (pos in f.doors) {
            if (!level.isLoaded(pos)) continue
            val state = level.getBlockState(pos)
            if (state.block !is DoorBlock || state.getValue(DoorBlock.OPEN) == open) continue
            level.setBlock(pos, state.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS or Block.UPDATE_IMMEDIATE)
            if (any == null) any = pos
        }
        if (sound && any != null) WorksBlocks.play(level, any, WorksBlocks.GRANARY_DOORS)
    }

    /** One of the town's carters at the open doors: a sack tipped in (filling), or one taken up and carried off (giving). */
    private fun carter(level: ServerLevel, w: WorksRec, site: TownWorksSite, f: Fittings, giving: Boolean) {
        val k = key(w.id, DOORS)
        if (errands[k]?.aside?.over == false) return
        val i = f.doors.indices.firstOrNull { level.isLoaded(f.doorFronts[it]) } ?: return
        val door = f.doors[i]
        val stand = f.doorFronts[i].takeIf { Places.standable(level, it) } ?: BlockPos.containing(site.gate())
        val inside = Vec3.atCenterOf(door).add(0.0, 0.2, 0.0)
        val p = level.getEntitiesOfClass(Townsperson::class.java, AABB(door).inflate(SEEK, 24.0, SEEK)) {
            it.life?.carter == true && WorksYard.sendable(level, it)
        }.minByOrNull { it.distanceToSqr(inside) } ?: return
        val away = site.road()
        val sack = ItemStack(Items.WHEAT)
        val moves: List<Move> = if (giving) listOf(
            GoTo({ stand }, Jobs.WALK, 0.8, Activity.NONE, 900),
            Turn({ inside }),
            Handle(Activity.PICK_UP, inside, PICKED) { it.carried = sack.copy() },
            GoTo({ away }, Jobs.WALK * 0.85, 2.0, Activity.CARRY_SACK, 600),
        ) else listOf(
            Now { it.carried = sack.copy() },
            GoTo({ stand }, Jobs.WALK * 0.85, 0.8, Activity.CARRY_SACK, 900),
            Turn({ inside }),
            Handle(Activity.POUR_SACK, inside, TIPPED) { it.carried = ItemStack.EMPTY },
            Wait({ _, _ -> false }, 20, Activity.NONE) { inside },
        )
        val aside = Aside(moves, reach = SEEN + 16.0, most = 1800)
        aside.onEnd = { p.carried = ItemStack.EMPTY }
        WorksYard.send(p, aside)
        errands[k] = Errand(p, aside)
    }

    /** The beacon: out by day, lit by its watch at dusk (or by the clock once it's dark), flaring a while at a strike. */
    private fun beacon(level: ServerLevel, w: WorksRec, f: Fittings, hour: Double) {
        val pos = f.beacon ?: return
        if (!level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        if (!state.`is`(WorksBlocks.BEACON.get())) return
        firsts.remove(w.id)
        val burning = state.getValue(WorksBeaconBlock.FIRE)
        val flaring = Works.enabled() && (flares[w.id] ?: 0L) > level.gameTime
        val night = hour >= DARK || hour < MORNING
        val dusk = hour >= DUSK && hour < DARK
        val want = when {
            flaring -> BeaconFire.FLARE
            !Works.enabled() -> burning
            night -> BeaconFire.LIT
            // At dusk it's the watch's to light: as they left it
            dusk -> if (burning == BeaconFire.OUT) BeaconFire.OUT else BeaconFire.LIT
            else -> BeaconFire.OUT
        }
        if (want == burning) return
        fire(level, pos, want, sound = WorksBuild.watched(w.id))
    }

    /** A stall's or a cookshop's awning: drawn up while it trades (its keeper's working day, with something on it to sell), let down at night. */
    private fun awning(level: ServerLevel, w: WorksRec, site: TownWorksSite, f: Fittings, hour: Double, near: Boolean) {
        val pos = f.awning ?: return
        val owner = w.owner ?: return
        val stall = OwnStalls.get(level).find(w.capital)?.pitches?.get(owner)
        val stocked = if (w.kind == WorksKind.COOKSHOP) CookShops.stocked(level, w) else stall != null && stall.stock.any { it > 1e-6 }
        val trades = Works.enabled() && stocked && hour >= TRADES_FROM && hour < TRADES_TO
        val set = { up: Boolean, sound: Boolean ->
            if (level.isLoaded(pos)) {
                val state = level.getBlockState(pos)
                if (state.`is`(WorksBlocks.AWNING.get()) && state.getValue(WorksAwningBlock.OPEN) != up) {
                    level.setBlock(pos, state.setValue(WorksAwningBlock.OPEN, up), Block.UPDATE_CLIENTS)
                    val be = level.getBlockEntity(pos) as? WorksAwningBlockEntity
                    be?.color(site.patron.ordinal)
                    if (sound) {
                        be?.moved()
                        Streets.sound(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, "streets.awning", 24f, 1f, 0.95f + level.random.nextFloat() * 0.1f)
                    }
                }
            }
        }
        if (!changed(w, trades) { set(it, false) }) return
        // Its keeper reaches up to it if they're at the stall, else it's drawn by itself
        if (near && keeperReach(level, w, site, pos, trades) { set(trades, true) }) return
        set(trades, true)
    }

    /** [w]'s keeper, at the stall, draws the awning up (SET_UP) or lets it down (SHUT_STALL), [moved] at the pull. True if they will. */
    private fun keeperReach(level: ServerLevel, w: WorksRec, site: TownWorksSite, awning: BlockPos, up: Boolean, moved: () -> Unit): Boolean {
        val k = key(w.id, AWNING)
        if (errands[k]?.aside?.over == false) return false
        val p = body(level, w.hired)?.takeIf { it.distanceToSqr(Vec3.atCenterOf(awning)) < 12.0 * 12.0 && WorksYard.sendable(level, it) } ?: return false
        val behind = keeperStand(site)
        val look = Vec3.atCenterOf(awning)
        val clip = if (up) Activity.SET_UP else Activity.SHUT_STALL
        val aside = Aside(listOf(GoTo({ behind }, Jobs.WALK, 0.6, Activity.NONE, 300), Turn({ look }), Handle(clip, look, clip.ticks / 2) { moved() }), reach = SEEN, most = 600)
        // However it ends, the awning's where it should be
        aside.onEnd = { moved() }
        WorksYard.send(p, aside)
        errands[k] = Errand(p, aside)
        return true
    }

    /** Where a stall's keeper stands: behind its display, facing the buyers. */
    private fun keeperStand(site: TownWorksSite): BlockPos = site.frame.world(site.drawing.heart.x, site.drawing.y0, site.drawing.heart.z + 1)

    // ---- The first use ----

    /** [w]'s first use, once: the first loaves, the first filling, the first watch, the first wares. */
    private fun firstUse(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, f: Fittings, hour: Double, near: Boolean) {
        val done = {
            rec.firstUse = true
            WorksBuildData.get(level).changed()
        }
        val since = level.gameTime - rec.finishedAt
        when (w.kind) {
            // The first filling comes on its wagons (their sacks carried in by Unloads' porters): it's used once it's all in
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> if (w.fill.all { it <= 1e-6 }) done()
            // Its watch is posted as soon as it stands (the lookouts' own)
            WorksKind.TOWER -> done()
            // The clerk takes the key, opens up, the first crate goes in and the pennant up (trade/StoreLife)
            WorksKind.STORE, WorksKind.COUNTING_HOUSE -> StoreLife.firstUse(level, w, rec, site, hour, near, done)
            WorksKind.OVEN -> {
                val k = key(w.id, FIRST_USE)
                val e = errands[k]
                if (e != null) {
                    if (e.aside.over) errands.remove(k)
                    return
                }
                if (since > UNSEEN_FIRST) return done()
                if (!near || hour < 7.0 || hour >= 16.0) return
                firstLoaves(level, w, site, done)
            }
            WorksKind.COOKSHOP -> {
                val k = key(w.id, FIRST_USE)
                val e = errands[k]
                if (e != null) {
                    if (e.aside.over) errands.remove(k)
                    return
                }
                if (since > UNSEEN_FIRST || w.hired < 0 && since > UNSEEN_FIRST / 3) return done()
                if (!near || hour < TRADES_FROM || hour >= TRADES_TO) return
                firstPlates(level, w, site, done)
            }
            WorksKind.STALL -> {
                val k = key(w.id, FIRST_USE)
                val e = errands[k]
                if (e != null) {
                    if (e.aside.over) errands.remove(k)
                    return
                }
                if (since > UNSEEN_FIRST || w.hired < 0 && since > UNSEEN_FIRST / 3) return done()
                if (!near || hour < TRADES_FROM || hour >= TRADES_TO) return
                firstWares(level, w, site, done)
            }
        }
    }

    /** The baker hands out the new oven's first loaves at the bakery: "Fresh from the new oven!" */
    private fun firstLoaves(level: ServerLevel, w: WorksRec, site: TownWorksSite, done: () -> Unit) {
        val at = site.gate()
        val baker = body(level, w.hired)?.takeIf { it.distanceToSqr(at) < SEEK * SEEK && WorksYard.sendable(level, it) }
            ?: level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(at, SEEK * 2, 32.0, SEEK * 2)) {
                it.role == TownRole.BAKER && Days.life(it) != null && WorksYard.sendable(level, it)
            }.minByOrNull { it.distanceToSqr(at) } ?: return
        val counter = baker.post?.takeIf { it.distToCenterSqr(at) < 24.0 * 24.0 } ?: BlockPos.containing(at)
        val folk = { p: Townsperson -> who(level, p) }
        val loaf = ItemStack(Items.BREAD)
        val aside = Aside(
            listOf(
                GoTo({ counter }, Jobs.WALK, 0.8, Activity.NONE, 900),
                Now { it.carried = loaf.copy() },
                Turn({ folk(baker) }),
                Play(Activity.HAND_OVER, at = { folk(baker) }) { Words.say(it, "streets.first_bread", "the new oven's first loaves", force = true) },
                Wait({ _, _ -> false }, 30, Activity.NONE) { folk(baker) },
                Now { it.carried = loaf.copy() },
                Turn({ folk(baker) }),
                Play(Activity.HAND_OVER, at = { folk(baker) }),
                Now { it.carried = ItemStack.EMPTY },
                Wait({ _, _ -> false }, 40, Activity.NONE) { folk(baker) },
            ),
            reach = SEEN + 16.0, most = 1800,
        )
        aside.onEnd = {
            baker.carried = ItemStack.EMPTY
            done()
        }
        WorksYard.send(baker, aside)
        errands[key(w.id, FIRST_USE)] = Errand(baker, aside)
    }

    /** The stall's keeper sets out its first wares on its display (SET_UP, then ARRANGE). */
    private fun firstWares(level: ServerLevel, w: WorksRec, site: TownWorksSite, done: () -> Unit) {
        val behind = keeperStand(site)
        val keeper = body(level, w.hired)?.takeIf { it.distanceToSqr(Vec3.atCenterOf(behind)) < SEEK * SEEK && WorksYard.sendable(level, it) } ?: return
        val display = site.frame.centre(site.drawing.heart.x.toDouble(), site.drawing.y0 + 0.6, site.drawing.heart.z.toDouble())
        val crate = ItemStack(Items.APPLE)
        val aside = Aside(
            listOf(
                GoTo({ behind }, Jobs.WALK, 0.6, Activity.NONE, 900),
                Turn({ display }),
                Now { it.carried = crate.copy() },
                Play(Activity.SET_UP, at = { display }),
                Now { it.carried = ItemStack.EMPTY },
                Play(Activity.ARRANGE, 60, at = { display }),
            ),
            reach = SEEN + 16.0, most = 1200,
        )
        aside.onEnd = {
            keeper.carried = ItemStack.EMPTY
            done()
        }
        WorksYard.send(keeper, aside)
        errands[key(w.id, FIRST_USE)] = Errand(keeper, aside)
    }

    /** The cookshop's keeper sets its first plate on the counter and hands it to whoever is by: "Fresh from the new oven!" */
    private fun firstPlates(level: ServerLevel, w: WorksRec, site: TownWorksSite, done: () -> Unit) {
        val behind = keeperStand(site)
        val keeper = body(level, w.hired)?.takeIf { it.distanceToSqr(Vec3.atCenterOf(behind)) < SEEK * SEEK && WorksYard.sendable(level, it) } ?: return
        val counter = site.frame.centre(site.drawing.heart.x.toDouble(), site.drawing.y0 + 1.2, site.drawing.heart.z.toDouble())
        val folk = { p: Townsperson -> who(level, p) }
        val plate = CookShops.firstPlate(level, w)
        val aside = Aside(
            listOf(
                GoTo({ behind }, Jobs.WALK, 0.6, Activity.NONE, 900),
                Turn({ counter }),
                Now { it.carried = plate.copy() },
                Play(Activity.ARRANGE, 40, at = { counter }),
                Turn({ folk(keeper) }),
                Play(Activity.HAND_OVER, at = { folk(keeper) }) { CookShops.firstWords(it) },
                Wait({ _, _ -> false }, 30, Activity.NONE) { folk(keeper) },
                Now { it.carried = ItemStack.EMPTY },
            ),
            reach = SEEN + 16.0, most = 1200,
        )
        aside.onEnd = {
            keeper.carried = ItemStack.EMPTY
            done()
        }
        WorksYard.send(keeper, aside)
        errands[key(w.id, FIRST_USE)] = Errand(keeper, aside)
    }

    /** Who [p] hands to: the nearest player, else the nearest of the town about, else the street. */
    private fun who(level: ServerLevel, p: Townsperson): Vec3 {
        level.getNearestPlayer(p, 12.0)?.let { return it.eyePosition }
        val near = level.getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(6.0, 2.0, 6.0)) { it !== p && it.isAlive }.minByOrNull { it.distanceToSqr(p) }
        return near?.eyePosition ?: p.eyePosition.add(p.lookAngle.scale(2.0))
    }

    // ---- The mending ----

    /**
     * Each morning, whatever of [site]'s drawing is gone is laid again into air: the first few brought over by hand by
     * one of the town's spare hands where a player is near to see (the rest laid as they go), else all at once.
     */
    private fun mend(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, hour: Double, near: Boolean) {
        val day = Days.dayOf(level.overworldClockTime)
        if (rec.mended == day || hour < MEND_FROM || hour >= MEND_TO || !Works.enabled()) return
        val k = key(w.id, MENDING)
        val e = errands[k]
        if (e != null) {
            if (!e.aside.over) return
            errands.remove(k)
        }
        val missing = site.missing()
        val finish = {
            for (i in site.missing()) site.mend(i, fx = WorksBuild.watched(w.id))
            rec.mended = day
            WorksBuildData.get(level).changed()
        }
        if (missing.isEmpty() || !near) return finish()
        val hand = labourer(level, capital, site) ?: return finish()
        val moves = ArrayList<Move>()
        for (i in missing.take(MEND_BY_HAND)) {
            val pos = site.at(i)
            val block = site.state(site.step(i).b, pos).block
            val item = ItemStack(block.asItem())
            val stand = standFor(level, pos, hand.blockPosition()) ?: continue
            val look = Vec3.atCenterOf(pos)
            moves.add(Now { it.carried = item.copy() })
            moves.add(GoTo({ stand }, Jobs.WALK, 1.0, Activity.CARRY_BLOCK, 900))
            moves.add(Turn({ look }))
            moves.add(Handle(Activity.PUT_DOWN, look, LAY_AT) {
                it.carried = ItemStack.EMPTY
                site.mend(i, fx = true)
            })
        }
        if (moves.isEmpty()) return finish()
        val aside = Aside(moves, reach = SEEN + 16.0, most = 2400)
        aside.onEnd = {
            hand.carried = ItemStack.EMPTY
            finish()
        }
        WorksYard.send(hand, aside)
        errands[k] = Errand(hand, aside)
    }

    /** One of [capital]'s spare hands (citizens with no trade or duty) about [site] and free to come, or any citizen free nearby. */
    private fun labourer(level: ServerLevel, capital: CapitalPiece, site: TownWorksSite): Townsperson? {
        val c = Vec3.atCenterOf(site.box.center)
        val places = CityPlaces.of(level, capital)
        val people = Residents.get(level)
        if (places != null) {
            for (id in Hands.spare(level, places)) {
                val p = people[id]?.uuid?.let { level.getEntity(it) } as? Townsperson ?: continue
                if (p.distanceToSqr(c) < SEEK * SEEK && Aside.free(p, seated = true)) return p
            }
        }
        return level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(c, SEEK * 2, 32.0, SEEK * 2)) {
            it.role == TownRole.CITIZEN && Days.life(it) != null && Aside.free(it, seated = true)
        }.minByOrNull { it.distanceToSqr(c) }
    }

    /** Where to stand to put a block back at [pos]: beside it, or on the ground under it, nearest [from]. */
    private fun standFor(level: ServerLevel, pos: BlockPos, from: BlockPos): BlockPos? {
        Places.beside(level, pos, from)?.let { return it.pos }
        for (down in 1..20) {
            val best = Direction.Plane.HORIZONTAL.map { BlockPos(pos.x + it.stepX, pos.y - down, pos.z + it.stepZ) }
                .filter { Places.standable(level, it) }
                .minByOrNull { it.distSqr(from) }
            if (best != null) return best
        }
        return null
    }

    /** [resident]'s body in the world, if it's about. */
    private fun body(level: ServerLevel, resident: Int): Townsperson? {
        if (resident < 0) return null
        val uuid = Residents.get(level)[resident]?.uuid ?: return null
        return level.getEntity(uuid) as? Townsperson
    }

    // ---- A stall's counter ----

    /** [player] uses a stall's counter at [pos]: its stall's screen (StallDesk.openPitch), its owner's to stock; or why not yet. */
    fun usePitch(level: ServerLevel, player: ServerPlayer, pos: BlockPos) {
        if (!Works.ready()) return
        for (w in WorksData.get(level).works.values) {
            if (w.kind != WorksKind.STALL) continue
            val f = fittings(level, w.id) ?: continue
            if (pos !in f.pitches) continue
            if (w.state != WorksState.DONE) {
                player.sendOverlayMessage(Component.translatable("message.guildmark.works.pitch_building"))
                return
            }
            val capital = CaravanRoutes.capital(level, w.capital) ?: return
            val owner = w.owner ?: return
            if (!StallDesk.openPitch(level, player, capital, owner)) player.sendOverlayMessage(Component.translatable("message.guildmark.works.pitch_shut"))
            return
        }
    }

    /** [activity] played facing [at], with [what] done on its tick [mark] (a sack tipped, a block laid, the awning pulled). */
    private class Handle(private val activity: Activity, private val at: Vec3, private val mark: Int, private val what: (Townsperson) -> Unit) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(activity)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            p.lookAt(at)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(at), Aside.TURN))
            if (t == mark) what(p)
            return t < activity.ticks
        }
    }
}
