package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Lamplighter
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.links.Greeter
import io.github.vaspyyy.guildmark.village.live.talk.VillageCry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BellBlock
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * A village's trouble (living-world/villages/design.md section 11, part V10): the switch, the one tick that drives the rest, the close
 * of each village's day, the bell rung by itself, and the voices the trouble's lines are said in.
 *
 * With `villageTrouble` off (or Living Villages) nothing here runs, nothing is written and every hook in the sieges, raids and captives
 * does as it did; a shelter called before the switch went off is ended at once, so nobody stays indoors for it. The sieges and raids of
 * a capital, or of a bell that is no village of the registry, never reach any of it ([village] answers null for them).
 *
 * Each village's day closes here, once, at the first look after dawn ([daily]: the town day turns at six in the morning, as the
 * residents' days and deaths are kept), whether or not its market runs: the watch is reckoned, the candles due are set for the morning,
 * the mend is opened and those taken and never found are put to rest. What is done with bodies is done in the hours it belongs to, by
 * the tick: the stone and the candles at dawn, the mend in working hours, the watch and the golems at night.
 */
object Trouble {
    /** Ticks between the looks at the villages' days, the stone, the mend, the watch and the golems. */
    private const val LOOK = 100

    /** Whether trouble is worked out: Living Villages and `villageTrouble` on. */
    fun on(): Boolean = Villages.enabled() && try {
        GuildmarkConfig.VILLAGE_TROUBLE.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** Whether the golems are kept up: Living Villages and `villageGolems` on. */
    fun golemsOn(): Boolean = Villages.enabled() && try {
        GuildmarkConfig.VILLAGE_GOLEMS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /**
     * The village whose bell is [bell] (or whose ground takes it in, for a second bell), if trouble is worked out for it: a village of
     * the registry, not lost, in the overworld. Null for a capital's bell, any other, and with the switch off.
     */
    fun village(level: ServerLevel, bell: BlockPos): VillageRec? {
        if (!on() || level.dimension() != Level.OVERWORLD) return null
        return Villages.byKey(level, bell.asLong())?.takeIf { it.state != Live.LOST } ?: Villages.at(level, bell)
    }

    /** The village of key [key], as [village] has it. */
    fun village(level: ServerLevel, key: Long): VillageRec? {
        if (!on() || level.dimension() != Level.OVERWORLD) return null
        return Villages.byKey(level, key)?.takeIf { it.state != Live.LOST }
    }

    /** At setup (from [Villages.register]): the tick, the lines for `village info`. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        Memorial.register()
        VillageInfoCommand.section(VillageInfoCommand.Section { level, rec -> Deaths.lines(level, rec) })
    }

    private fun forget() {
        Shelter.forget()
        SiegeHorde.forget()
        RaidWatch.forget()
        Memorial.forget()
        bells.clear()
        logs.clear()
        looked = 0L
    }

    private var looked = 0L

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        if (!on()) {
            // Turned off: whoever is sheltering comes out now
            if (Shelter.any()) Shelter.endAll(level)
            if (Memorial.any()) Memorial.endAll()
            return
        }
        guarded("the shelter") { Shelter.tick(level) }
        guarded("the bells") { strike(level) }
        guarded("the raids") { RaidWatch.tick(level) }
        val now = level.gameTime
        if (now - looked < LOOK && now >= looked) return
        looked = now
        val day = VillageBoards.day(level)
        for (rec in Villages.awake(level)) {
            guarded("the day of ${rec.name}") { if (TroubleData.peek(level)?.peek(rec.key)?.day != day) daily(level, rec, day) }
            guarded("the memorial of ${rec.name}") { Memorial.look(level, rec, day) }
        }
    }

    /** Runs [body]; one that fails is logged and costs only itself. */
    private inline fun guarded(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("A village's trouble ({}) failed", what, e)
        }
    }

    /**
     * The close of [rec]'s town day [day] (the first look after dawn): the watch reckoned and sent for, the candles due set for this
     * morning, the mend opened, and those taken and never found put to rest. Once a day; written only if something is to be kept.
     */
    internal fun daily(level: ServerLevel, rec: VillageRec, day: Long) {
        RaidWatch.daily(level, rec, day)
        Memorial.daily(level, rec, day)
        val kept = TroubleData.peek(level)?.peek(rec.key) ?: return
        kept.day = day
        TroubleData.get(level).setDirty()
    }

    // ---- The bell, by itself ----

    /** A stroke of a village's bell to come: at [at] (game time), from [from]. */
    private class Stroke(val bell: BlockPos, val at: Long, val from: Direction)

    private val bells = ArrayList<Stroke>()

    /**
     * Rings the bell at [bell] [strokes] times, [every] ticks apart, the first [after] ticks from now: hard (six close together) for the
     * alarm, slow for the all-clear. Nobody is at the rope (the bellman's own ringing is [Shelter]'s, with `greatBell`).
     */
    fun ring(level: ServerLevel, bell: BlockPos, strokes: Int, every: Int, after: Int = 0) {
        val now = level.gameTime
        val sides = Direction.Plane.HORIZONTAL.toList()
        for (k in 0 until strokes) bells += Stroke(bell, now + after + k.toLong() * every, sides[k % sides.size])
    }

    private fun strike(level: ServerLevel) {
        if (bells.isEmpty()) return
        val now = level.gameTime
        val due = bells.filter { it.at <= now }
        if (due.isEmpty()) return
        bells.removeAll(due.toSet())
        for (s in due) {
            if (!level.isLoaded(s.bell)) continue
            (level.getBlockState(s.bell).block as? BellBlock)?.attemptToRing(level, s.bell, s.from)
        }
    }

    // ---- What happened ----

    /** The last things that happened in each village's trouble, for `village watch`: memory only. */
    private val logs = HashMap<Long, ArrayDeque<String>>()

    /** Something happened in [rec]'s trouble: kept for `village watch` (the last [LOGGED]), with the clock's time. */
    fun log(level: ServerLevel, rec: VillageRec, what: String) {
        val q = logs.getOrPut(rec.key) { ArrayDeque() }
        q.addLast("${Days.hourOf(Days.offsetOf(level.overworldClockTime))} $what")
        while (q.size > LOGGED) q.removeFirst()
    }

    /** What [log] kept for the village [key], the oldest first. */
    fun logged(key: Long): List<String> = logs[key]?.toList().orEmpty()

    private const val LOGGED = 10

    /** Whether [rec] has a wall standing (its palisade or more), so the lines that name a wall are said there only. */
    fun walled(level: ServerLevel, rec: VillageRec): Boolean {
        val board = VillageCry.board(level, rec) ?: return false
        return VillageCry.walled(level, rec, board)
    }

    // ---- Who is whose ----

    /** [p]'s record, if they are one of the village [key]'s residents. */
    fun residentOf(level: ServerLevel, p: Townsperson, key: Long): Resident? {
        if (p.resident < 0) return null
        val r = Residents.get(level)[p.resident] ?: return null
        return r.takeIf { it.capital.asLong() == key }
    }

    /** Whether [p] is of the village [key]: one of its residents, or someone of the town in its ground for the day (a visitor, the pedlar). */
    fun folkOf(level: ServerLevel, p: Townsperson, key: Long): Boolean {
        if (p.resident >= 0) return residentOf(level, p, key) != null
        return p.town?.center?.asLong() == key
    }

    /**
     * Whether [p] can be lent to one of trouble's scenes that is not the shelter (the candle, the mend, the wall): about and well, not a
     * captive, a faction's own, a tale's, a passenger or in a festival's crowd, and under no director but a lamplighter's, a greeter's or
     * a builder's (who are let go: [borrow]).
     */
    internal fun free(p: Townsperson): Boolean {
        if (!p.isAlive || p.isRemoved || p.captiveOf >= 0 || p.isLeader || p.isPassenger || p.tale >= 0 || p.crowd || p.watcher || p.hurtTime > 0) return false
        val d = p.director
        return d == null || d is Lamplighter || d is Greeter || d is BuilderCrew.Builder
    }

    /** [p] off their day for one of trouble's scenes: the greeter or the builder they were let go, awake, on their feet. */
    internal fun borrow(p: Townsperson) {
        when (val d = p.director) {
            is Greeter -> d.release()
            is BuilderCrew.Builder -> d.handBack()
            else -> {}
        }
        if (p.isSleeping) p.stopSleeping()
        p.standUp()
        p.routine.interrupt()
        p.navigation.stop()
    }

    /** [p] back to their day after one of trouble's scenes, if [d] still has them. */
    internal fun giveBack(p: Townsperson, d: Director) {
        if (p.director === d) p.director = null
        p.act(Activity.NONE)
        p.navigation.stop()
        p.routine.interrupt()
    }

    /** The hour on the villages' clock (0 at midnight, with its fraction). */
    fun hour(level: ServerLevel): Double = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0

    /** Whether it is night by the clock: from nine in the evening to five in the morning. */
    fun night(level: ServerLevel): Boolean = hour(level).let { it >= 21.0 || it < 5.0 }

    // ---- Voices ----

    /**
     * The voices each shelter line was recorded in (17.4; `SHELTER_TIMBRES` in art/audio/make_townsfolk_voices.py): the client plays a
     * line in the speaker's own voice, an old resident borrowing the man's and a child the woman's where theirs is missing (and saying
     * it as text only if neither is there). A child here says only what a child was recorded saying.
     */
    private val VOICES: Map<String, Set<String>> = mapOf(
        "shelter_alert_1" to setOf("man", "woman"), "shelter_alert_2" to setOf("man", "woman", "old"), "shelter_alert_3" to setOf("man", "woman"),
        "shelter_run_1" to setOf("man", "woman", "child"), "shelter_run_2" to setOf("man", "woman"), "shelter_knock" to setOf("man", "woman"),
        "shelter_open" to setOf("man", "woman", "old"), "shelter_hush_1" to setOf("woman", "old"), "shelter_hush_2" to setOf("man", "woman"),
        "shelter_hush_3" to setOf("woman", "old"), "shelter_hush_4" to setOf("man", "old"), "shelter_child_1" to setOf("child"),
        "shelter_child_2" to setOf("child"), "shelter_allclear_1" to setOf("man", "woman", "old"), "shelter_allclear_2" to setOf("man", "woman", "old"),
        "shelter_lost" to setOf("man", "woman", "old"),
    )

    /** The watch's lines and the village's own (the mend, the candle) are the man's, the woman's and (for the village's) the old man's. */
    private val GROWN = setOf("man", "woman", "old")

    /** Whether [p] has a voice for [line]: their own, or the one the client borrows for them (an old resident the man's). */
    fun fits(p: Townsperson, line: String): Boolean {
        val voices = VOICES[line] ?: if (line.startsWith("watch_")) setOf("man", "woman") else GROWN
        val timbre = Say.timbre(p)
        if (timbre == "child" || p.role == TownRole.CHILD) return "child" in voices
        return timbre in voices || timbre == "old" && "man" in voices
    }

    /**
     * [p] says [line] (a key under say.guildmark.) for as long as its take lasts, to players within [hearing] (32 or more for the horn's
     * and the wall's shouts, which carry); false if they have no voice for it or nobody is near enough to hear.
     */
    fun say(p: Townsperson, line: String, hearing: Double = HEARD, force: Boolean = true): Boolean {
        if (!fits(p, line)) return false
        val ticks = TalkLines.ticks(line).toLong().takeIf { it > 0 } ?: Townsperson.SPEECH_TICKS
        return p.say(line, force = force, hearing = hearing, ticks = ticks + 10L)
    }

    /** How far the trouble's ordinary lines are heard; the shouts carry [SHOUT]. */
    const val HEARD = 20.0
    const val SHOUT = 32.0
}
