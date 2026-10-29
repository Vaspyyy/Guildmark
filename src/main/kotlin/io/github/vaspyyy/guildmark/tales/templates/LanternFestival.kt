package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity.Slip
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalRegistry
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Crier
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * What the Lanternrise and the lantern tale share (living-world/stories/design.md 8.9, 6.5 and 10.1), Part T2b: the festival's two
 * hooks ([start] and [release], from `Tales.lanternStart` and `Tales.lanternRelease`, which `FestivalSite.lanterns` calls), the
 * traditions a town keeps ([traditions], [add], [clear]), the crier's festival cry for them ([cries]) and the once-a-second care of
 * the open lantern tales ([register]).
 *
 * **The tradition.** An ending A or B leaves one in `TaleData.towns[capital].traditions`: `(lantern, <the name>, lanternrise, <the
 * year>, kept)`. At every later Lanternrise, at the moment the town's lanterns leave their hands, one more sky lantern rises for each
 * from the temple's front door (beside the fountain if the town has no temple), and on the festival's day the crier adds one cry: the
 * custom kept tonight, and the names in grey. A Lanternrise by the calendar counts as one kept (`kept`); the engine lets a tradition go
 * after ten (`TaleLimits.TRADITION_FESTIVALS`) and keeps six at most. One forced with `festival start` shows it and does not count.
 * With Town Tales off none of this is done.
 */
internal object LanternFestival {
    /** The kind of tradition the lantern leaves, and the festival it belongs to. */
    const val KIND = "lantern"
    val FESTIVAL: String = FestivalKind.LANTERNRISE.id

    /** The crier's line for a custom kept, and the grey words after it (one name, or several). */
    private const val CRY = "crier_tale_custom"
    private const val CRY_ONE = "say.guildmark.crier_tale_custom.lantern"
    private const val CRY_MORE = "say.guildmark.crier_tale_custom.lanterns"
    private const val SLIP = "board.guildmark.slip.tale.lantern.custom"

    /** One line in `Tales.register`: the `tale tradition` and `tale lantern` commands and scenes, and the open lantern tales seen to once a second. */
    fun register() {
        LanternCommands.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> LanternWalk.forget() }
    }

    // ---- The festival's hooks ----

    /** `Tales.lanternStart`: the Lanternrise's lanterns at [centre] are about to go up (the town plays RELEASE now). */
    fun start(level: ServerLevel, centre: Vec3) {
        if (!Tales.live) return
        Tales.tell(level, Event.Lanterns(centre, false))
    }

    /** `Tales.lanternRelease`: the lanterns at [centre] leave the town's hands; one more rises for each of the town's lantern traditions. */
    fun release(level: ServerLevel, centre: Vec3) {
        if (!Tales.live) return
        Tales.tell(level, Event.Lanterns(centre, true))
        TaleLook.guarded(level, "the traditions' lanterns") { rise(level, centre) }
    }

    /** One sky lantern for each lantern tradition of the town whose Lanternrise is at [centre], from its temple's door. */
    private fun rise(level: ServerLevel, centre: Vec3) {
        val site = LanternClock.siteAt(centre) ?: return
        val data = TaleData.get(level)
        val lanterns = data.find(site.key)?.traditions?.filter { it.kind == KIND }.orEmpty()
        if (lanterns.isEmpty()) return
        val from = templeDoor(level, site) ?: besideFountain(level, site)
        val type = FestivalRegistry.SKY_LANTERN.get()
        for ((i, tradition) in lanterns.withIndex()) {
            // A few hands apart, as several let go together
            val a = i * 2.4
            level.sendParticles(type, true, true, from.x + Math.cos(a) * 0.5 * i, from.y, from.z + Math.sin(a) * 0.5 * i, 0, 0.0, 1.0, 0.0, 0.0)
            if (!site.forced) tradition.kept++
        }
        if (!site.forced) data.changed()
    }

    /** Where a lantern let go from the temple's front door is: a head's height over the step outside it. */
    private fun templeDoor(level: ServerLevel, site: FestivalSite): Vec3? {
        val places = TaleEngine.ctx(level, site.key)?.here?.places ?: return null
        val temple = places.nearest(BlockPos.containing(site.centre), CityPlaces.TEMPLES).firstOrNull() ?: return null
        val door = temple.front?.outside ?: return null
        return Vec3.atBottomCenterOf(door).add(0.0, FestivalSite.LET_GO_UP, 0.0)
    }

    /** Beside the fountain, on its rim's far side from the crowd's first row, a head's height up. */
    private fun besideFountain(level: ServerLevel, site: FestivalSite): Vec3 {
        val places = TaleEngine.ctx(level, site.key)?.here?.places
        val rim = places?.census?.plan?.fountainRadius ?: 4
        return site.centre.add(rim + 1.5, FestivalSite.LET_GO_UP, 0.0)
    }


    // ---- The traditions ----

    /** `Tales.traditions`: the traditions [capital]'s festival keeps (none with Town Tales off). */
    fun traditions(level: ServerLevel, capital: Long): List<TaleData.Tradition> =
        if (!Tales.live) listOf() else TaleData.get(level).find(capital)?.traditions?.toList().orEmpty()

    /**
     * A lantern for [name] at every later Lanternrise in [capital] (8.9, A and B; or `tale tradition add`): false if the town keeps one
     * for that name already (the year it began stands) or [name] is blank. The oldest is let go to make room past six.
     */
    fun add(level: ServerLevel, capital: Long, name: String): Boolean {
        val clean = name.trim().take(TaleLimits.STRING_LETTERS)
        if (clean.isEmpty()) return false
        val data = TaleData.get(level)
        val town = data.town(capital)
        if (town.traditions.any { it.kind == KIND && it.name.equals(clean, ignoreCase = true) }) return false
        town.traditions.add(TaleData.Tradition(KIND, clean, FESTIVAL, Seasons.today(level).year, 0))
        while (town.traditions.size > TaleLimits.TRADITIONS) town.traditions.removeAt(0)
        data.changed()
        return true
    }

    /** Every tradition [capital] keeps let go; how many there were. */
    fun clear(level: ServerLevel, capital: Long): Int {
        val data = TaleData.get(level)
        val town = data.find(capital) ?: return 0
        val n = town.traditions.size
        if (n > 0) {
            town.traditions.clear()
            data.changed()
        }
        return n
    }

    // ---- The crier ----

    /**
     * Beside `TaleTold.cries` in `Tales.cries` (6.2): on the Lanternrise's day (by the calendar, or with one forced live in the capital),
     * a town that keeps lantern traditions has its crier say so: `crier_tale_custom` ("A custom of the town is kept tonight!") with the
     * names in grey, and a slip on the board. Nothing on another day, with no tradition, or with Town Tales off.
     */
    fun cries(level: ServerLevel, entry: Markets.Entry, c: Conditions, at: Long): List<Crier.Cry> {
        if (!Tales.live) return listOf()
        val capital = entry.market.capital
        val names = traditions(level, capital).filter { it.kind == KIND }.map { it.name }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return listOf()
        if (!lanternrise(level, capital, c)) return listOf()
        val detail = if (names.size == 1) Component.translatable(CRY_ONE, names[0]) else Component.translatable(CRY_MORE, names.joinToString(", "))
        val text = Component.translatable("say.guildmark.$CRY").append(" ").append(detail.copy().withStyle(ChatFormatting.GRAY))
        return listOf(Crier.Cry(CRY, text, Slip(Slip.Kind.TALE.id, Component.translatable(SLIP), detail, at), 1))
    }

    /** Whether [c]'s noon is on a Lanternrise for [capital]: the calendar's, or one forced and live there. */
    private fun lanternrise(level: ServerLevel, capital: Long, c: Conditions): Boolean {
        if (Festivities.all().any { it.live && it.kind == FestivalKind.LANTERNRISE && it.key == capital }) return true
        if (!Seasons.festivalsEnabled()) return false
        val (kind, days) = FestivalCalendar.next(Seasons.dateOn(level.server, Math.floorDiv(c.hour, 24L)))
        return kind == FestivalKind.LANTERNRISE && days == 0
    }

    // ---- Once a second ----

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % 20 != 13 || !Tales.live) return
        val level = server.overworld()
        if (level.players().isEmpty()) return
        for (t in TaleEngine.openTales(level).toList()) {
            if (t.template != Lantern.id || !t.open) continue
            TaleLook.guarded(level, "the lantern tale's second") { LanternWalk.second(level, t) }
        }
    }
}
