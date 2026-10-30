package io.github.vaspyyy.guildmark.village.live.talk

import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.village.live.VillageBudget
import io.github.vaspyyy.guildmark.village.live.VillagePerf
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.days.BellKeeper
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * A village's bellman (living-world/villages/design.md 8.2): the resident who holds its bell for the week ([BellKeeper.holder]). At noon on
 * the village's market day, and at noon on any day there is news worth the telling ([VillageCry.Word.news]), they go to the bell at
 * half past eleven, read the word over from a scroll until the hour, pull the bell itself three times (the vanilla bell's own strokes;
 * no handbell: Townsperson.greatBell), and cry the day's word a line at a time with the moves a capital's crier has, the folk free about
 * the green coming over to listen ([Crier.gather], the capital's `Hear`); a last stroke and the word is told. With a market board at the
 * Hall door ([board], learned as the board ticks) they chalk it up afterwards as a capital's crier does; without one they only cry.
 *
 * The bellman's day has the cry in it only on a day it is due ([dueOn], worked out here every ten seconds for each awake village
 * before noon): news that comes in the morning (a wagon in, the pedlar on the green, a siege in the night) puts it in their day, if it
 * is not yet half past eleven. Once a day, whoever holds the bell ([VillageCry.criedOn]).
 */
object Bellman {
    /** How often (ticks) each awake village's word is looked at, and at which tick of that. */
    private const val EVERY = 200
    private const val PHASE = 73

    /** The bellman's noon stretch (VillageDays.CRY): from half past eleven; news after that is cried the next day. */
    const val FROM = 11.5

    /** A thing cried is given its line's own length, or this for a line with no voice; and a breath after it. */
    private const val TEXT = 64
    private const val BREATH = 24

    /** The town day each village (by its centre) is due a noon cry, in memory: worked out afresh each day. */
    private val due = HashMap<Long, Long>()

    /** The market board at each village's Hall door (by its centre), as the board last ticked: its anchor and the way it faces. */
    private val boards = HashMap<Long, Spot>()

    private var started = false

    /** At setup (TalkSources.init): the look at the villages' word, and nothing kept past the server's stop. */
    fun register() {
        if (started) return
        started = true
        VillageCryCommand.init()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { VillagePerf.timed(VillagePerf.Piece.BELLMAN) { tick(it.server) } }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    private fun forget() {
        due.clear()
        boards.clear()
    }

    /** Whether the village whose centre is [centre] is due a noon cry on day [day] (as [Days] counts): for VillageDays.shape. */
    fun dueOn(centre: BlockPos, day: Long): Boolean = VillageCry.enabled() && due[centre.asLong()] == day

    /** From MarketBoards.tick: the market board whose anchor is [anchor] stands in the village [key] (the board at its Hall door). */
    fun board(key: Long, anchor: BlockPos, state: BlockState) {
        if (!VillageCry.enabled() || !state.hasProperty(NoticeBoardBlock.FACING)) return
        boards[key] = Spot(anchor.immutable(), state.getValue(NoticeBoardBlock.FACING))
    }

    /** The market board of the village [key], where the bellman chalks, if one has been seen ticking there. */
    fun boardOf(key: Long): Spot? = boards[key]

    // ---- Whether today is a day for the cry ----

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % EVERY != PHASE || !VillageCry.enabled()) return
        val level = server.overworld()
        val c = Conditions(Markets.hourOf(level))
        // The hours after midnight are the evening's day still (the day begins at two)
        val clock = hour(level).let { if (it < 2.0) it + 24.0 else it }
        if (clock >= LATEST) return
        for (rec in VillageBudget.within("bellman", Villages.awake(level), 150)) {
            val places = Villages.places(level, rec.key) ?: continue
            if (due[rec.key] == c.day) continue
            val word = word(level, rec, places, c)
            // News that comes after half past eleven is tomorrow's; a market day is cried still, late (the world just begun, say)
            if (!word.due || clock >= FROM && !word.market) continue
            due[rec.key] = c.day
            replan(level, places, c.day)
        }
    }

    /** Past this hour a cry not yet in the bellman's day waits for tomorrow's. */
    private const val LATEST = 12.5

    /** The day's word at [rec]: what the bellman would cry at its noon. */
    fun word(level: ServerLevel, rec: VillageRec, places: VillagePlaces, c: Conditions): VillageCry.Word =
        VillageCry.word(level, places, Markets.find(level, rec.key), c)

    /** The bellman's day is planned again, the cry in it now (their body, if it is about: one not about plans it when it comes). */
    private fun replan(level: ServerLevel, places: VillagePlaces, day: Long) {
        val holder = BellKeeper.holder(level, places.centre, day) ?: return
        val uuid = Residents.get(level)[holder]?.uuid ?: return
        val p = level.getEntity(uuid) as? Townsperson ?: return
        val life = p.life ?: return
        if (life.agenda?.day == day) life.agenda = null
    }

    /** The clock's hour as [Days] reckons it (11.5 is half past eleven). */
    private fun hour(level: ServerLevel): Double = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0

    // ---- The cry ----

    /**
     * [p]'s noon round as [life]'s bellman (a CRY stretch of a village's: Crier.round sends a village's here): to the bell, the word read
     * over until noon, the bell rung and the word cried, and the board chalked if the village has one; null once it's done today, while
     * the bell is gone or there's nowhere to stand by it. [now]: cry at once, whatever the hour and whether it has been cried, and
     * keep nothing as told (for testing).
     */
    fun round(p: Townsperson, life: Life, now: Boolean = false): Task? {
        if (!VillageCry.enabled()) return null
        val level = p.level() as? ServerLevel ?: return null
        val places = life.places as? VillagePlaces ?: return null
        val rec = Villages.byKey(level, places.centre.asLong()) ?: return null
        val bell = places.bell
        if (!level.isLoaded(bell) || level.getBlockState(bell).block !is BellBlock) return null
        val c = Conditions(Markets.hourOf(level))
        if (!now && VillageCry.criedOn(level, rec.key) == c.day) return null
        val stand = BellKeeper.standAt(level, bell, p.blockPosition(), p)?.pos ?: return null
        if (!now) VillageCry.crying(level, rec.key, c.day)
        val entry = Trades.market(p, life)
        val word = VillageCry.word(level, places, entry, c)
        // Beside the bell, facing out over the green (or away from the bell, where the green is the bell's own ground)
        val green = places.market
        val toward = if (green.distSqr(stand) > 4.0) green else stand.offset(stand.subtract(bell))
        val facing = Direction.getApproximateNearest((toward.x - stand.x).toDouble(), 0.0, (toward.z - stand.z).toDouble())
        val spot = Spot(stand, if (facing.axis == Direction.Axis.Y) Direction.NORTH else facing)
        val crowd = Vec3.atBottomCenterOf(stand.relative(spot.facing, 6)).add(0.0, 1.5, 0.0)
        val great = Crier.GreatBell(bell, ::length) { if (!now) VillageCry.told(level, rec.key, word, c.day) }
        val cry = Acts.via(p, life, stand, Crier.crying(p, spot, crowd, word.cries, now, great))
        val board = boardOf(rec.key)
        if (entry == null || board == null) return cry
        val name = rec.name
        return cry.andThen(Later(p) { Crier.chalk(p, life, board, { Component.literal(name) }, places.centre, noon = true, slips = word.cries.mapNotNull { it.slip }) })
    }

    /** The ticks given [cry]: its line's own length and a breath. */
    private fun length(cry: Crier.Cry): Int = (TalkLines.ticks(cry.line).takeIf { it > 0 } ?: TEXT) + BREATH
}
