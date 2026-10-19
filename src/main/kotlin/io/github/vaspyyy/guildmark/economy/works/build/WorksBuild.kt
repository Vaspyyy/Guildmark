package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksKinds
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.HomesteadProtection
import io.github.vaspyyy.guildmark.homestead.Laid
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.ToppingOut
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.CustomClickActionEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.min

/**
 * The town works going up (living-world/design-influence.md section 3), and standing (section 3's "Afterwards"): the
 * building's side of M6, wired in at the mod's setup by [register].
 *
 * Every second each works is seen to, a few apart: one with no site has one looked for ([WorksSiting]); a wanted works
 * has its post up ([WorksYard]); a paid-for one its yard laid out and its build ([TownWorksSite]) worked, the clock
 * winding through daylight whether anyone is there or not, its crew (the town's labourers lent by Hands, strangers
 * making up the number: [BuilderCrew]) and its master builder ([MasterBuilder]) there only while a player is within
 * [NEAR] blocks, and whatever the clock has allowed beyond what the crew keeps in hand laid at once, [CATCH_UP] steps
 * a tick, whenever its land is loaded. Each stage is reported to the works (Works.staged) from the first step laid,
 * each stage's end celebrated, and the finish reported (Works.finished); then the ceremonies, the works at its work
 * ([WorksAtWork]) and, each morning, the mending. A works taken down or called off has its blocks taken down to what
 * its drawing found there, as soon as its land is loaded.
 *
 * Nothing runs until Part A is wired in (Works.ready), and nothing but the clock's standing still while the works
 * are switched off ([Works.enabled]): a switch turned off sends the crew home, and turning it on goes on from there.
 */
object WorksBuild {
    /** Steps the clock lays in a tick at most, catching up. */
    private const val CATCH_UP = 48

    /** How near a player must be for the crew to be there (and the build to be watched). */
    const val NEAR = 72.0

    /** How near the land must be loaded round the lot, and a body be, to count as at the works. */
    private const val BODY_NEAR = 96.0

    /** The builds going up now, by works id (BuilderCrew finds them by their keys). */
    private val sites = HashMap<Int, TownWorksSite>()

    /** The standing works' builds, for the mending and their work. */
    private val standing = HashMap<Int, TownWorksSite>()

    /** Builds whose land is loaded (the clock may lay there), and those a player is near. */
    private val loaded = HashSet<Int>()
    private val watched = HashSet<Int>()

    /** Each capital's works its labourers go to just now, and where they report (the crate's front), as last worked out. */
    private class Report(val works: Int, val at: BlockPos?, val time: Long)

    private val reports = HashMap<Long, Report>()

    /** The blocks each built works put up, by works id, kept while its build is in hand (for HomesteadProtection). */
    private class Built(val laid: Int, val positions: LongOpenHashSet)

    private val built = HashMap<Int, Built>()

    /** Sets the building up: its blocks, its screen's payloads, its hooks into the crews, the lookouts and the drops, and its ticking. */
    fun register(modBus: IEventBus) {
        WorksBlocks.register(modBus)
        WorksLedger.register(modBus)
        Works.listen(Listener)
        Works.crew = Crew
        Lookout.beacon = WorksAtWork.Beacon
        Caravans.listen(Unloads)
        BuilderCrew.works = BuilderCrew.Sites { key -> sites[-1 - key] }
        HomesteadProtection.works = HomesteadProtection.Built { level, pos -> built(level, pos) }
        val bus = NeoForge.EVENT_BUS
        bus.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        bus.addListener(EntityJoinLevelEvent::class.java, MasterBuilder::onJoin)
        bus.addListener(CustomClickActionEvent::class.java, WorksLedger::onClick)
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> (event.entity as? net.minecraft.server.level.ServerPlayer)?.let(WorksLedger::close) }
        bus.addListener(ServerStoppedEvent::class.java) { _ -> reset() }
    }

    /** The server has stopped: nothing of its world is kept. */
    private fun reset() {
        sites.clear()
        standing.clear()
        loaded.clear()
        watched.clear()
        reports.clear()
        built.clear()
        WorksPlans.reset()
        WorksSiting.reset()
        WorksYard.reset()
        MasterBuilder.reset()
        WorksBuilder.reset()
        WorksAtWork.reset()
        WorksLedger.reset()
        Unloads.reset()
    }

    /** The build of works [id] going up, while it is (for the screen and the commands). */
    fun live(id: Int): TownWorksSite? = sites[id]

    /** Works [id]'s build, going up or standing, if its record is in hand. */
    fun site(id: Int): TownWorksSite? = sites[id] ?: standing[id]

    /** Whether a player is near works [id] (its crew is there). */
    fun watched(id: Int): Boolean = id in watched

    // ---- Every tick ----

    private fun tick(server: MinecraftServer) {
        if (!Works.ready()) return
        val level = server.overworld()
        val now = level.gameTime
        val build = WorksBuildData.get(level)
        if (now % 20L == 7L) takeDowns(level, build)
        if (!Works.enabled()) {
            if (now % 20L == 0L) still(level, build)
            Unloads.tick(level)
            return
        }
        val data = WorksData.get(level)
        for (w in data.works.values.toList()) {
            if (Math.floorMod(now + w.id * 7L, 20L) == 0L) {
                try {
                    second(level, data, build, w)
                } catch (e: RuntimeException) {
                    // One works' trouble never stops the rest: it's tried again in a second
                    io.github.vaspyyy.guildmark.Guildmark.LOGGER.warn("Town works {}: {}", w.id, e.toString())
                }
            }
            val site = sites[w.id]
            if (site != null && w.id in loaded && w.open && !site.finished) catchUp(level, w, site)
        }
        Unloads.tick(level)
        WorksAtWork.everyTick(level)
    }

    /** The works switched off: the crews sent home and every clock held where it stands. */
    private fun still(level: ServerLevel, build: WorksBuildData) {
        for ((id, site) in sites) {
            BuilderCrew.update(level, site, false)
            MasterBuilder.away(level, id)
        }
        for (rec in build.recs.values) {
            val b = rec.build
            if (b.clock < 0) continue
            b.clock = level.gameTime
            b.dayTime = level.overworldClockTime
        }
    }

    // ---- Every second, for each works ----

    private fun second(level: ServerLevel, data: WorksData, build: WorksBuildData, w: WorksRec) {
        var rec = build.recs.getOrPut(w.id) { BuildRec(w.id, w.kind, w.capital).also { build.changed() } }
        if (rec.removed) return
        val capital = CaravanRoutes.capital(level, w.capital, soon = true) ?: return
        val site = w.site
        if (rec.sited && (site == null || !WorksSiting.ours(rec, site))) {
            // Its site forgotten (`works site <id>`) or set elsewhere (`works site <id> here`): it starts again there
            rec = unsite(level, data, build, w, rec)
        }
        if (site == null || !rec.sited) {
            // Done without a site (finished by command before it had one): nothing to lay. One set where an op stood
            // (`works site <id> here`) is taken, and laid at once
            if (w.state == WorksState.DONE && site == null) return
            WorksSiting.look(level, w, rec, capital)
            return
        }
        when (w.state) {
            WorksState.WANTED -> WorksYard.wanted(level, w, rec, capital)
            WorksState.FUNDED, WorksState.BUILDING -> building(level, w, rec, capital)
            WorksState.DONE -> done(level, w, rec, capital)
        }
    }

    /** [rec]'s build, made afresh when its record says something new. */
    private fun siteOf(level: ServerLevel, rec: BuildRec, into: HashMap<Int, TownWorksSite>): TownWorksSite {
        val planned = WorksPlans.of(rec)
        val have = into[rec.id]
        if (have != null && have.planned === planned && have.level === level) return have
        return TownWorksSite(level, rec, planned).also { into[rec.id] = it }
    }

    private fun building(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece) {
        standing.remove(w.id)
        val site = siteOf(level, rec, sites)
        site.wind()
        if (!site.loaded()) {
            loaded.remove(w.id)
            watched.remove(w.id)
            BuilderCrew.update(level, site, false)
            MasterBuilder.away(level, w.id)
            return
        }
        loaded.add(w.id)
        site.surveyFound()
        if (rec.layTo >= 0) {
            layTo(site, rec.layTo, near(level, site))
            rec.layTo = -1
            WorksBuildData.get(level).changed()
        }
        WorksYard.lay(level, w, rec, site, capital)
        WorksYard.sync(level, w, rec, site)
        site.survey(w.here)
        val near = near(level, site)
        if (near) watched.add(w.id) else watched.remove(w.id)
        // The work begins with the first wagon in (or the first materials brought)
        val arrived = w.here.any { it > 0 } || w.used.any { it > 0 } || site.laid > 0
        site.named = named(level, w, site)
        val wanted = near && arrived && site.workday() && !site.finished && level.isPositionEntityTicking(site.crate)
        BuilderCrew.update(level, site, wanted)
        MasterBuilder.tick(level, w, rec, site, capital, near && arrived)
        report(level, w, rec, site)
        val stage = site.finishedStage
        if (stage != null) {
            site.finishedStage = null
            staged(level, w, rec, site, stage, near)
        }
        if (site.finished) finish(level, w, rec, site, near)
    }

    /** Lays [site] free up to step [end] (`works stage`), what can't go in left out, the first few with their dust and sound. */
    private fun layTo(site: TownWorksSite, end: Int, fx: Boolean) {
        var n = 0
        for (i in site.build.step until min(end, site.total)) {
            if (site.isDone(i)) continue
            site.lay(i, fx = fx && n++ < 24, free = true)
            if (!site.isDone(i)) site.skip(i)
        }
    }

    /** Whether a player (not a spectator) is within [NEAR] of [site]. */
    fun near(level: ServerLevel, site: TownWorksSite): Boolean {
        val c = site.box.center
        return level.players().any { !it.isSpectator && it.distanceToSqr(c.x + 0.5, it.y, c.z + 0.5) < NEAR * NEAR }
    }

    /** Tells the works how far along [site] is: from the first step laid, its stage and pieces (the stage heard when it moves). */
    private fun report(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite) {
        if (site.total <= 0 || site.laid <= 0) return
        val stage = site.stage
        if (rec.reported == stage.ordinal && w.laid == site.laid && w.total == site.total) return
        Works.staged(level, w.id, stage, site.laid, site.total)
        rec.reported = stage.ordinal
        WorksBuildData.get(level).changed()
    }

    /** A stage of [site] is finished: called by the master (or one of the crew), cheered, and its ceremony if it has one. */
    private fun staged(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, stage: Stage, near: Boolean) {
        if (near) {
            // The town gathered round its topping out cheers it as the crew does, once (and the crew's own cheer is left
            // out); the crew's cheer for the stage before, as the town begins to gather, is a cheer gone up on the lot
            // already, so the gathering doesn't take it for a call of its own and cheer it again
            if (stage == Stage.TOPPING) ToppingOut.cheer(level, w.id) else ToppingOut.at(w.id)?.let { if (level.gameTime <= it.until) it.cued = level.gameTime }
            BuilderCrew.celebrate(level, site, stage)
            MasterBuilder.calls(level, w, rec, site, stage)
        }
        if (stage == Stage.TOPPING) MasterBuilder.toppedOut(level, w, rec, site, near)
    }

    /** The clock's laying: whatever is beyond what the crew keeps in hand, as fast as it may. */
    private fun catchUp(level: ServerLevel, w: WorksRec, site: TownWorksSite) {
        val reserve = BuilderCrew.reserve(site.key)
        val build = site.build
        if (build.work < 1.0 + reserve) return
        val fx = w.id in watched && build.work - reserve < 4.0
        var budget = CATCH_UP
        var i = build.step
        while (budget > 0 && build.work >= 1.0 + reserve && !site.finished) {
            if (i < build.step) i = build.step
            val end = site.plan.stageStarts[site.stage.ordinal + 1]
            while (i < end && !site.open(i)) i++
            if (i >= end) break
            when (site.lay(i, fx)) {
                Laid.DONE -> budget -= if (site.step(i).kind == StepKind.GROUND) 3 else 1
                Laid.WAIT, Laid.BLOCKED -> i++
                Laid.NEED, Laid.LATER -> break
            }
        }
    }

    /** [site] is laid to its last step: the works is told it stands, and the crew cheers it and goes. */
    private fun finish(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, near: Boolean) {
        if (rec.finishedAt < 0) {
            rec.finishedAt = level.gameTime
            WorksBuildData.get(level).changed()
        }
        if (site.total > 0) Works.staged(level, w.id, site.plan.stageOf(site.total - 1), site.total, site.total)
        Works.finished(level, w.id)
        if (near) BuilderCrew.finished(level, site.key) else BuilderCrew.dismiss(site.key)
        sites.remove(w.id)
        loaded.remove(w.id)
        standing[w.id] = site
        built.remove(w.id)
    }

    // ---- Standing ----

    private fun done(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece) {
        if (sites.remove(w.id) != null) {
            // Finished by command meanwhile: the crew goes, and what the clock hadn't laid is laid now
            BuilderCrew.dismiss(-1 - w.id)
            loaded.remove(w.id)
        }
        val site = siteOf(level, rec, standing)
        if (!site.loaded()) {
            watched.remove(w.id)
            MasterBuilder.away(level, w.id)
            return
        }
        val near = near(level, site)
        if (near) watched.add(w.id) else watched.remove(w.id)
        // `works finish` before the builders were done: the rest laid at once, as the drawing has it
        if (!site.finished) {
            site.surveyFound()
            var n = 0
            for (i in 0 until site.total) {
                if (site.isDone(i)) continue
                site.lay(i, fx = near && n++ < 24, free = true)
                if (!site.isDone(i)) site.skip(i)
            }
            if (rec.finishedAt < 0) rec.finishedAt = level.gameTime
            WorksBuildData.get(level).changed()
        }
        if (!rec.opened) {
            if (MasterBuilder.opening(level, w, rec, site, capital, near)) {
                rec.opened = true
                WorksYard.clear(level, rec)
                WorksBuildData.get(level).changed()
            }
            return
        }
        MasterBuilder.away(level, w.id)
        WorksAtWork.second(level, w, rec, site, capital, near)
    }

    /**
     * [rec]'s works sited afresh: what was laid at its old site is taken down there (now, or once its land loads, as a
     * record of its own), the materials it had used carried back to the yard, and a fresh record for the new site.
     */
    private fun unsite(level: ServerLevel, data: WorksData, build: WorksBuildData, w: WorksRec, rec: BuildRec): BuildRec {
        sites.remove(rec.id)?.let { BuilderCrew.dismiss(it.key) }
        standing.remove(rec.id)
        loaded.remove(rec.id)
        watched.remove(rec.id)
        built.remove(rec.id)
        MasterBuilder.away(level, rec.id)
        WorksSiting.forget(rec.id)
        WorksPlans.forget(rec.id)
        if (rec.surveyed || rec.post != null || rec.yard.isNotEmpty() || rec.plaque != null || rec.strings.isNotEmpty()) {
            var ghost = -1
            while (build.recs.containsKey(ghost)) ghost--
            val old = rec.copy(ghost)
            old.removed = true
            build.recs[ghost] = old
            takeDown(level, build, old)
        }
        if (w.open) {
            for (i in w.here.indices) {
                w.here[i] += w.used[i]
                w.used[i] = 0
            }
            data.changed()
        }
        val fresh = BuildRec(rec.id, rec.kind, rec.capital)
        build.recs[rec.id] = fresh
        build.changed()
        WorksLedger.changed(level, rec.id)
        return fresh
    }

    // ---- What the commands ask (WorksCommand) ----

    /** `works start`: [id] seen to now, its yard laid out and its crew called if a player is near. */
    fun start(level: ServerLevel, id: Int) {
        if (!Works.enabled()) return
        val data = WorksData.get(level)
        val w = data.works[id] ?: return
        second(level, data, WorksBuildData.get(level), w)
    }

    /**
     * `works stage`: [id] laid free up to the start of [stage] (the stages before it built, as the builders would
     * have them), now if its land is loaded, else when it is. False if it has no site to lay it on yet.
     */
    fun stageTo(level: ServerLevel, id: Int, stage: Stage): Boolean {
        val rec = WorksBuildData.get(level)[id]?.takeIf { it.sited && !it.removed } ?: return false
        val planned = WorksPlans.of(rec)
        val end = planned.plan.stageStarts[stage.ordinal]
        val site = sites[id]
        if (site != null && site.loaded()) layTo(site, end, near(level, site)) else rec.layTo = end
        WorksBuildData.get(level).changed()
        return true
    }

    /** How far works [id]'s builders are, for `works materials` (in the commands' own words). */
    fun describe(level: ServerLevel, id: Int): List<String> {
        val rec = WorksBuildData.get(level)[id] ?: return listOf("builders: not seen to yet (within a second of its being wanted)")
        if (rec.removed) return listOf("builders: taking it down, as soon as its land is loaded")
        if (!rec.sited) {
            val held = WorksSiting.inWay(id)
            return listOf(
                "builders: no site yet" +
                    (held?.let { ", held up by the block at ${it.x} ${it.y} ${it.z} (outlined in red to anyone within 32 blocks)" } ?: "; they look for one each second in loaded land"),
            )
        }
        val out = ArrayList<String>()
        val o = rec.origin
        out.add(
            "builders: sited at ${o.x} ${o.y} ${o.z} facing ${rec.front.serializedName}" + (if (rec.planned) ", on the plan's own lot" else "") +
                (rec.post?.let { "; its post at ${it.x} ${it.y} ${it.z}" } ?: "") + "; ${rec.yard.size} heaps laid out in its yard",
        )
        val plan = WorksPlans.of(rec).plan
        val b = rec.build
        val laid = min(b.step + b.done.cardinality(), plan.steps.size)
        out.add(
            "   $laid of ${plan.steps.size} steps laid, at its ${plan.stageOf(b.step).key}; %.1f steps of work in hand".format(b.work) +
                if (rec.layTo >= 0) "; laid free up to step ${rec.layTo} as soon as its land loads" else "",
        )
        val site = sites[id] ?: return out
        val crew = BuilderCrew.builders(site.key).count { !it.leaving }
        val master = when {
            MasterBuilder.body(level, id) != null -> "the master builder here"
            rec.came -> "the master builder away"
            else -> "the master builder not come yet"
        }
        out.add("   crew: $crew at work of the ${WorksKinds.crew(rec.kind)} it wants (${crewOf(level, id).size} lent by the town today), $master")
        val held = listOfNotNull(
            if (!site.loaded()) "its land isn't loaded (the clock lays when it is)" else null,
            if (site.loaded() && id !in watched) "no player within ${NEAR.toInt()} blocks (the crew comes when one is)" else null,
            if (!site.workday()) "it's not a working hour (the crew works from sunrise to sunset)" else null,
            site.short?.let { "it's out of ${it.key}" },
            site.blocked?.let { "the block at ${it.x} ${it.y} ${it.z} is in its way (the clock lays round it)" },
            if (site.awaitingTown()) "the town's gathering for its topping out (it waits a few seconds for them)" else null,
        )
        if (held.isNotEmpty()) out.add("   waiting: " + held.joinToString("; "))
        return out
    }

    // ---- Taken down ----

    /** The works heard as gone (called off, `works remove`): its blocks taken down now if its land is loaded, else when it is. */
    private object Listener : Works.Listener {
        override fun removed(level: ServerLevel, w: WorksView) {
            val build = WorksBuildData.get(level)
            val rec = build[w.id] ?: return
            rec.removed = true
            build.changed()
            sites.remove(w.id)?.let { BuilderCrew.dismiss(it.key) }
            standing.remove(w.id)
            loaded.remove(w.id)
            watched.remove(w.id)
            built.remove(w.id)
            MasterBuilder.away(level, w.id)
            WorksLedger.gone(level, w.id)
            takeDown(level, build, rec)
        }

        override fun funded(level: ServerLevel, w: WorksView) {
            WorksLedger.changed(level, w.id)
            WorksBuildData.get(level)[w.id]?.let { rec -> WorksYard.stamp(level, rec) }
        }

        override fun pledged(level: ServerLevel, w: WorksView, player: UUID, marks: Int) {
            WorksLedger.changed(level, w.id)
            WorksBuildData.get(level)[w.id]?.let { rec -> WorksYard.pledged(level, rec, player) }
        }

        override fun brought(level: ServerLevel, w: WorksView, player: UUID, units: Int) = WorksLedger.changed(level, w.id)

        override fun staged(level: ServerLevel, w: WorksView, stage: Stage) = WorksLedger.changed(level, w.id)

        override fun finished(level: ServerLevel, w: WorksView) = WorksLedger.changed(level, w.id)
    }

    /** Works taken down while their land wasn't loaded, tried again each second. */
    private fun takeDowns(level: ServerLevel, build: WorksBuildData) {
        for (rec in build.recs.values.filter { it.removed }) takeDown(level, build, rec)
    }

    private fun takeDown(level: ServerLevel, build: WorksBuildData, rec: BuildRec) {
        if (rec.sited) {
            val site = TownWorksSite(level, rec, WorksPlans.of(rec))
            if (!site.loaded()) return
            if (rec.surveyed) site.takeDown()
        }
        WorksYard.clear(level, rec, plaque = true)
        build.recs.remove(rec.id)
        build.changed()
        WorksPlans.forget(rec.id)
    }

    // ---- The labourers (Part A's stubs, filled) ----

    /**
     * The works [capital]'s labourers go to now: the oldest open works in town whose site is laid out and whose
     * materials have begun to come (a tower out on its road is built by strangers only), and where they report.
     */
    private fun current(level: ServerLevel, capital: Long): Report {
        val now = level.gameTime
        reports[capital]?.let { if (now - it.time in 0 until 20L) return it }
        val data = WorksData.get(level)
        val w = data.of(capital).filter { it.open && it.site != null && it.kind != WorksKind.TOWER && (it.here.any { n -> n > 0 } || it.used.any { n -> n > 0 }) }.minByOrNull { it.id }
        val at = w?.let { sites[it.id] }?.let { BlockPos.containing(it.crateFront()) } ?: w?.site?.crate
        return Report(w?.id ?: -1, at, now).also { reports[capital] = it }
    }

    /**
     * The residents of [capital] wanted at its works on town day [day]: as many of its spare citizens (Hands.spare)
     * as the works' crew wants, a different few each day; none while there's no works to build.
     */
    fun labourers(level: ServerLevel, capital: Long, day: Long): List<Int> {
        if (!Works.enabled()) return listOf()
        val report = current(level, capital)
        if (report.works < 0) return listOf()
        val w = WorksData.get(level).works[report.works] ?: return listOf()
        val piece = CaravanRoutes.capital(level, capital) ?: return listOf()
        val places = CityPlaces.of(level, piece) ?: return listOf()
        val spare = Hands.spare(level, places)
        if (spare.isEmpty()) return listOf()
        val want = WorksKinds.crew(w.kind).coerceAtMost(spare.size)
        val start = Math.floorMod(day * 7L + w.id * 3L, spare.size.toLong()).toInt()
        return List(want) { spare[(start + it) % spare.size] }
    }

    /** Today's labourers from the town at works [id] (for its screen): none unless it's where the town's labourers go now. */
    fun crewOf(level: ServerLevel, id: Int): List<Int> {
        val w = WorksData.get(level).works[id] ?: return listOf()
        if (w.kind == WorksKind.TOWER || current(level, w.capital).works != id) return listOf()
        return labourers(level, w.capital, Days.dayOf(level.overworldClockTime))
    }

    /** Where [capital]'s labourers report for work: before its works' crate. */
    fun workAt(level: ServerLevel, capital: Long): BlockPos? {
        if (!Works.enabled()) return null
        return current(level, capital).at
    }

    /** Today's labourers of [w] about the site, alive and near: strangers make up the rest. */
    private fun named(level: ServerLevel, w: WorksRec, site: TownWorksSite): Int {
        if (w.kind == WorksKind.TOWER || current(level, w.capital).works != w.id) return 0
        val people = Residents.get(level)
        val day = Days.dayOf(level.overworldClockTime)
        val c = Vec3.atCenterOf(site.box.center)
        return labourers(level, w.capital, day).count { id ->
            val uuid = people[id]?.uuid ?: return@count false
            val body = level.getEntity(uuid) as? Townsperson ?: return@count false
            body.isAlive && body.position().distanceTo(c) < BODY_NEAR
        }
    }

    /** Gives a labourer their work at the site: a builder of its crew, while there's work and someone near to see it. */
    private object Crew : Works.Crew {
        override fun labour(level: ServerLevel, p: Townsperson): Director? {
            val r = Residents.get(level).of(p) ?: return null
            val report = current(level, r.capital.asLong())
            if (report.works < 0) return null
            val site = sites[report.works] ?: return null
            val w = WorksData.get(level).works[report.works] ?: return null
            val wanted = w.id in watched && site.workday() && !site.finished
            val director = BuilderCrew.lend(level, site, p, wanted) ?: return null
            TownTalk.laboured(level, r, w.id)
            return director
        }

        override fun release(level: ServerLevel, p: Townsperson) {
            BuilderCrew.released(p)
        }
    }

    // ---- What the builders put up, for HomesteadProtection ----

    /** Whether [pos] is a block a works' builders put up (laid already), so it gives nothing back when broken. */
    private fun built(level: ServerLevel, pos: BlockPos): Boolean {
        if (!Works.ready()) return false
        for ((id, site) in sites.entries + standing.entries) {
            if (site.level !== level || !site.box.inflatedBy(1).isInside(pos)) continue
            val laid = site.laid
            var b = built[id]
            if (b == null || b.laid != laid) {
                val set = LongOpenHashSet()
                for (i in 0 until site.total) {
                    if (!site.isDone(i)) continue
                    val s = site.step(i)
                    if (s.kind == StepKind.BLOCK && s.b.m != io.github.vaspyyy.guildmark.city.plan.M.AIR) set.add(site.at(i).asLong())
                }
                b = Built(laid, set)
                built[id] = b
            }
            if (pos.asLong() in b.positions) return true
        }
        return false
    }
}
