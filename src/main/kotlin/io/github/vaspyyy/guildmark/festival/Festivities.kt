package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.season.SeasonDate
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.abs

/**
 * The four grand festivals ([FestivalKind]): one on the middle day of each season ([FestivalCalendar]), held in every
 * capital (round its fountain) and every village (round its bell) a player is in that day. Each town's festival is a
 * [FestivalSite], made when a player first comes to the town on the day and kept while anyone is about.
 *
 * For the Living World and anything else that wants to know:
 * - [grandDay] says whether today is a festival day at all;
 * - [isOn] whether a festival is going on round a place, and [sceneAt] the festival itself ([FestivalScene]): which
 *   one, what part of the day, whether the band is playing, and where anyone should stand and what they should do;
 * - [FestivalCalendar] says when they fall.
 *
 * The festivals can be switched off (GuildmarkConfig: festivals); any going on then come down at once.
 */
object Festivities {
    /** The festivals going on, by town (a capital's centre, or a village's board, packed). */
    private val sites = HashMap<Long, FestivalSite>()

    /** Festivals already held (or called off) today, by town and run, which aren't put on again. */
    private val held = HashSet<Pair<Long, Long>>()

    /** The town each player was last found in, and when (so it's only looked up again when they've moved on). */
    private class Found(val chunk: Long, val at: Long, val town: TownSpot?)

    private val found = HashMap<UUID, Found>()

    /** Who's been told what (that a festival is coming, or on today), by player, town and day. */
    private val told = HashSet<String>()
    private var toldDay = Long.MIN_VALUE

    /** A town a festival can be held in. */
    class TownSpot(val key: Long, val name: String, val capital: CapitalPiece?, val bell: BlockPos?, val centre: BlockPos)

    fun register(modBus: IEventBus) {
        FestivalRegistry.register(modBus)
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1").playToClient(FestivalPayload.TYPE, FestivalPayload.STREAM_CODEC, FestivalPayload::handle)
        }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) {
                event.accept(FestivalRegistry.RIBBON_CROWN.get())
                event.accept(FestivalRegistry.SUNHEARTH_MEDAL.get())
                event.accept(FestivalRegistry.GILDED_SHEAF.get())
                event.accept(FestivalRegistry.RIDDLERS_LANTERN.get())
            }
        }
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() == Level.OVERWORLD) tick(level)
        }
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() != Level.OVERWORLD) return@addListener
            val chunk = event.chunk
            if (level.server.isSameThread) FestivalRestores.loaded(level, chunk) else level.server.execute { FestivalRestores.loaded(level, chunk) }
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent::class.java) { event ->
            // Everything back as it was before the world is saved
            val level = event.server.overworld()
            for (site in sites.values.toList()) site.abandon(level)
            sites.clear()
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            sites.clear()
            held.clear()
            found.clear()
            told.clear()
            toldDay = Long.MIN_VALUE
            FestivalRestores.clear()
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            FestivalPrizes.deliver(player.level().server.overworld(), player)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            found.remove(player.uuid)
            for (site in sites.values) site.forget(player.uuid)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            found.remove(player.uuid)
            for (site in sites.values) site.forget(player.uuid)
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { FestivalCommand.register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent::class.java, ::arrow)
        Seasons.onNewDay { level, _, today ->
            // Only today's count: a calendar put back (by command, or a /time set) holds that day's festivals again
            held.removeIf { it.second != today.calendarDay }
            news(level, today)
        }
    }

    // =============================================================================================== for anyone

    /** Is today one of the grand festivals (and are they on)? */
    fun grandDay(level: Level): Boolean = Seasons.festivalsEnabled() && FestivalCalendar.on(Seasons.today(level)) != null

    /** The festival going on round [pos] (within its square, give or take), if there is one. Server side. */
    fun sceneAt(level: Level, pos: BlockPos): FestivalScene? {
        if (level.dimension() != Level.OVERWORLD || level.isClientSide) return null
        return sites.values.firstOrNull { site ->
            val reach = site.plan.square + 16.0
            site.live && abs(pos.x + 0.5 - site.centre.x) <= reach && abs(pos.z + 0.5 - site.centre.z) <= reach && abs(pos.y - site.centre.y) <= 32
        }
    }

    /** Is a festival going on round [pos] right now (being set up, or under way, not coming down)? Server side. */
    fun isOn(level: Level, pos: BlockPos): Boolean = sceneAt(level, pos)?.let { it.phase != FestivalPhase.TEARDOWN } == true

    // =============================================================================================== each tick

    private fun tick(level: ServerLevel) {
        val now = level.gameTime
        if (sites.isNotEmpty()) {
            val iterator = sites.values.iterator()
            while (iterator.hasNext()) {
                val site = iterator.next()
                val near = level.players().any { abs(it.x - site.centre.x) < NEAR && abs(it.z - site.centre.z) < NEAR && abs(it.y - site.centre.y) < 96 }
                if (near) {
                    site.lastNear = now
                    site.tick(level)
                } else {
                    site.tickAsleep(level)
                    // Left behind by everyone, its ground unloaded: what's left is put back as its ground loads again
                    if (site.live && now - site.lastNear > FORGET && !level.isLoaded(site.plan.centrePos)) site.abandon(level)
                }
                if (!site.live) {
                    held.add(site.key to site.run)
                    iterator.remove()
                }
            }
        }
        FestivalRestores.tick(level) { pos, record -> wanted(level, pos, record) }
        for (player in level.players()) {
            if (Math.floorMod(now + player.id, LOOK_EVERY) == 0L) look(level, player)
        }
    }

    /** Every few seconds a player: is a festival due in the town they're in, or coming soon? */
    private fun look(level: ServerLevel, player: ServerPlayer) {
        if (!Seasons.festivalsEnabled() || player.isSpectator) return
        val date = Seasons.today(level)
        if (toldDay != date.calendarDay) {
            told.clear()
            toldDay = date.calendarDay
        }
        val (kind, ahead) = FestivalCalendar.next(date)
        if (ahead > 3) return
        val town = townOf(level, player) ?: return
        if (ahead == 0) {
            val site = sites[town.key] ?: hold(level, town, kind, date) ?: return
            if (site.live && player.distanceToSqr(site.centre) < (site.plan.square + 40.0).let { it * it } && told.add("${player.uuid}/${town.key}/0")) {
                player.sendSystemMessage(Component.translatable("message.guildmark.festival.today", site.kind.title, town.name, Component.translatable("festival.guildmark.${site.kind.id}.about")).withColor(site.kind.color))
            }
            return
        }
        if (told.add("${player.uuid}/${town.key}/$ahead")) {
            player.sendSystemMessage(Component.translatable("message.guildmark.festival.soon.$ahead", kind.title, town.name, Component.translatable("festival.guildmark.${kind.id}.about")).withColor(kind.color))
        }
    }

    /** The town [player] is in: a capital (inside its walls), or the village whose bell they're near. */
    private fun townOf(level: ServerLevel, player: ServerPlayer): TownSpot? {
        val chunk = player.chunkPosition().pack()
        val last = found[player.uuid]
        if (last != null && last.chunk == chunk && level.gameTime - last.at < 600) return last.town
        val town = townAt(level, player.blockPosition())
        found[player.uuid] = Found(chunk, level.gameTime, town)
        return town
    }

    /** The town at [pos]: a capital if it's inside one's walls (none on its farms), or else a village round a bell. */
    fun townAt(level: ServerLevel, pos: BlockPos): TownSpot? {
        val capital = Capitals.covering(level, pos)
        if (capital != null) {
            if (!capital.inTown(pos.x, pos.z)) return null
            val plan = capital.plan
            return TownSpot(capital.center.asLong(), VillageNames.of(capital.boardAnchor), capital, null, capital.standing(plan, plan.fountain))
        }
        val anchor = VillageBoards.villageBoard(level, pos) ?: return null
        val bell = TownControl.bell(level, anchor) ?: return null
        return TownSpot(anchor.asLong(), VillageNames.of(anchor), null, bell, bell)
    }

    /** Puts on today's festival in [town], once the ground round it is loaded (and if it isn't over for the day). */
    private fun hold(level: ServerLevel, town: TownSpot, kind: FestivalKind, date: SeasonDate): FestivalSite? {
        if (town.key to date.calendarDay in held) return null
        val time = Math.floorMod(level.overworldClockTime, 24000L).toInt()
        if (time >= FestivalPhase.TEARDOWN.from) return null
        if (!loadedRound(level, town.centre)) return null
        val site = FestivalSite(town.key, date.calendarDay, kind, town.name, plan(level, town, kind, town.key xor date.calendarDay * 31L), town.capital?.town, -1L, 0)
        sites[town.key] = site
        return site
    }

    private fun plan(level: ServerLevel, town: TownSpot, kind: FestivalKind, seed: Long): FestivalPlan =
        town.capital?.let { FestivalPlan.capital(level, it, kind, seed) } ?: FestivalPlan.village(level, town.bell ?: town.centre, kind, seed)

    private fun loadedRound(level: ServerLevel, centre: BlockPos): Boolean {
        val cx = centre.x shr 4
        val cz = centre.z shr 4
        for (dx in -2..2) for (dz in -2..2) if (!level.hasChunk(cx + dx, cz + dz)) return false
        return true
    }

    // =============================================================================================== the decorations

    /** A decoration's random tick: one no festival wants any more (left from a crash, say) is put back as it was. */
    fun checkPiece(level: ServerLevel, pos: BlockPos) {
        val record = FestivalRestores.recordAt(level, pos)
        if (record != null && wanted(level, pos, record)) return
        FestivalRestores.restore(level, pos)
    }

    /**
     * Whether the decoration at [pos] ([record]) is wanted still: by the running festival it belongs to, or by the
     * streets' signs and dressing (townsfolk/streets, M6 Part D), which put theirs up through the same record.
     */
    private fun wanted(level: ServerLevel, pos: BlockPos, record: FestivalRestores.PieceRecord): Boolean =
        sites[record.site]?.let { it.owns(pos) && it.wants(record) } == true ||
            io.github.vaspyyy.guildmark.townsfolk.streets.Streets.wants(level, pos, record)

    /** A player used a decoration (a hunt ribbon, a riddle lantern). */
    fun useOrnament(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        siteOwning(pos)?.useOrnament(level, pos, player)
    }

    private fun siteOwning(pos: BlockPos): FestivalSite? = sites.values.firstOrNull { it.live && it.owns(pos) }

    /** Arrows: at the Sunhearth's butts they're scored; none may hurt the cast, or a villager at a festival. */
    private fun arrow(event: ProjectileImpactEvent) {
        if (sites.isEmpty()) return
        val arrow = event.projectile as? AbstractArrow ?: return
        val level = arrow.level() as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD) return
        when (val hit = event.rayTraceResult) {
            is EntityHitResult -> if (sites.values.any { it.live && it.shelters(hit.entity) }) {
                event.isCanceled = true
                arrow.discard()
            }
            is BlockHitResult -> {
                val shooter = arrow.owner as? ServerPlayer ?: return
                siteOwning(hit.blockPos)?.arrow(level, shooter, hit.blockPos, hit.direction, hit.location)
            }
            else -> {}
        }
    }

    // =============================================================================================== news

    /** Each new day: the guild halls talk of a festival three days off, and of one today. */
    private fun news(level: ServerLevel, today: SeasonDate) {
        if (!Seasons.festivalsEnabled()) return
        val (kind, ahead) = FestivalCalendar.next(today)
        when (ahead) {
            0 -> GuildNews.add(level, "news.guildmark.festival_today", "#festival.guildmark.${kind.id}")
            3 -> GuildNews.add(level, "news.guildmark.festival_soon", "#festival.guildmark.${kind.id}", ahead.toString())
        }
    }

    // =============================================================================================== for the command

    /** Starts [kind] now in the town at [pos], in place of anything going on there; null if there's no town there. */
    fun start(level: ServerLevel, pos: BlockPos, kind: FestivalKind): FestivalSite? {
        val town = townAt(level, pos) ?: return null
        sites.remove(town.key)?.let { old ->
            old.abandon(level)
            held.add(old.key to old.run)
        }
        // Today's own festival isn't put on again afterwards
        held.add(town.key to Seasons.today(level).calendarDay)
        val run = -level.gameTime - 1
        val site = FestivalSite(town.key, run, kind, town.name, plan(level, town, kind, town.key xor level.gameTime), town.capital?.town, level.gameTime, 0)
        sites[town.key] = site
        return site
    }

    /** The festival nearest [pos] (within a long way), if any. */
    fun nearest(pos: BlockPos): FestivalSite? = sites.values.filter { it.live }.minByOrNull { it.centre.distanceToSqr(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5) }
        ?.takeIf { it.centre.distanceToSqr(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5) < 256.0 * 256.0 }

    /** Calls off a festival: its decorations come down and everyone goes home, and it isn't put on again today. */
    fun stop(site: FestivalSite) {
        site.stop()
        held.add(site.key to site.run)
    }

    /** Every festival going on. */
    fun all(): Collection<FestivalSite> = sites.values

    /** How close a player must be for a festival to be lively, and how long one left by everyone is kept. */
    private const val NEAR = 128.0
    private const val FORGET = 1200L
    private const val LOOK_EVERY = 100L
}
