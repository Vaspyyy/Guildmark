package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.lair.Lair
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.ComingsData
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus

/**
 * Tales, the story chains that grow out of a capital's records (living-world/stories/design.md): the one object every
 * hook calls. Each hook in another file is one line into one of the functions below (design 10.0), so no other system
 * learns what a tale is. Part 0 leaves each a no-op or a plain "no", and the game plays exactly as it does without tales;
 * the parts that follow fill them in, as the table at design 14.4 says: A the engine (the record, the close, the draw,
 * the steps), B the telling (the stage, the bodies), C the talk, D the following (tracker, map, banner), E pay and
 * memory, F the memorials, T1 to T5 the twelve tales. The names and the types here are contracts: a part fills a body,
 * and changes a signature only together with the call that uses it.
 *
 * The switches: [READY] holds everything back until the lead flips [SHIPPED] (a dev run with `-Pwith_tales=true` flips
 * it for that run), then townTales ([enabled]), townTaleScenes ([scenes]), townTalePace and townTaleMemorials, whose
 * readers the engine and the memorials add. `/guildmark tale` shows it working ([TaleCommand]).
 */
object Tales : Comings.Listener {
    /**
     * Whether tales are on for everyone. False until all twelve are in; the lead flips it then, as `Fortunes.READY`
     * was (living-world/build-plan.md section 4), not after a pilot play.
     */
    private const val SHIPPED = false

    /** A dev run started with `-Pwith_tales=true`: build.gradle sets the property `guildmark.tales`, a shipped jar never does. */
    private val tester: Boolean = System.getProperty("guildmark.tales") == "true"

    /** Whether tales are wired in at all: shipped, or a tester's run. While false nothing here does anything. */
    val READY: Boolean = SHIPPED || tester

    /** Whether tales run: [READY], townTales, and what every tale stands on (the markets, the capitals' living days, the talk). */
    fun enabled(): Boolean =
        READY && setting { GuildmarkConfig.TOWN_TALES.get() } && Markets.enabled() && Days.enabled() && Talk.enabled()

    /**
     * The switch as the hooks read it: [enabled] as of the last [refresh], one volatile read. A hook that finds it false
     * returns at once. Nothing calls [refresh] yet, so it stays false until the engine does (Part A).
     */
    @Volatile
    var live: Boolean = false
        private set

    /** Whether the street scenes of tales play (townTaleScenes), on top of [live]: the stage, the made figures and the motes ask. */
    fun scenes(): Boolean = live && setting { GuildmarkConfig.TOWN_TALE_SCENES.get() }

    /** The pace (townTalePace): a capital draws `min(1, TaleLimits.TALE_CHANCE x pace)` each day (9.2). 1.0 before the config is read. */
    fun pace(): Double = try {
        GuildmarkConfig.TOWN_TALE_PACE.get()
    } catch (e: IllegalStateException) {
        1.0
    }

    /** Whether memorials are set in the world (townTaleMemorials), on top of [live]: the street job queue asks. Off, they are recorded and not placed (11.5). */
    fun memorials(): Boolean = live && setting { GuildmarkConfig.TOWN_TALE_MEMORIALS.get() }

    /**
     * What wants to know how a tale goes, besides the templates: the following (banner, sound), the telling (a stage that
     * is due), the milestones. Add one with [listen] from [register]. Every call is guarded, so one that throws is logged
     * and the rest still hear; none is called while tales are off.
     */
    interface Listener {
        /** A tale was seeded in [level]'s capital (its cast chosen, the first step entered). */
        fun started(level: ServerLevel, t: Tale) {}

        /** [t] moved to another step (not the first one it began at). */
        fun stepped(level: ServerLevel, t: Tale) {}

        /** [player] took [t] up: their part is IN (the begin banner and its chime, 5.3). */
        fun joined(level: ServerLevel, t: Tale, player: ServerPlayer) {}

        /** [t] ended and every stage of the finish has run, or been tried: stage 10 (6.7). */
        fun ended(level: ServerLevel, t: Tale) {}

        /** Tales went off (11.5): the stages end, the bodies are let go, the rows are emptied, and nothing is written to any tale. */
        fun paused(level: ServerLevel) {}
    }

    private val listeners = ArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /** Tells every [Listener] by [what], each guarded. */
    internal fun hear(what: (Listener) -> Unit) {
        for (l in listeners.toList()) {
            try {
                what(l)
            } catch (e: Exception) {
                Guildmark.LOGGER.warn("Something listening to the tales failed", e)
            }
        }
    }

    /**
     * Sets [live] to [enabled]. Part A calls it at server start, once a second, and at the top of every [day], and adds
     * [pause] when it finds the switch gone off.
     */
    fun refresh() {
        val was = live
        live = enabled()
        if (was && !live) TaleEngine.paused()
    }

    /** The switch went off: the stages end and the tale bodies are let go, and nothing is written to any tale (Part A, B; design 11.5). */
    fun pause(level: ServerLevel) {
        TaleEngine.pause(level)
    }

    /** At setup, after the streets: the listeners (Part A), and each of B to F adds a block of its own. Registers nothing yet. */
    fun register(modBus: IEventBus) {
        TaleEngine.register()
        TaleLook.register()
        TaleLedger.register()
        TaleRows.register(modBus)
        TaleTalk.register()
        NoteStall.register()
        Memorials.register(modBus)
        // T1b: the Silent Lute's `tale lute` and its scenes (after the scene command has its own, so they are added to them)
        io.github.vaspyyy.guildmark.tales.templates.LuteCommands.register()
        // T1b: the Locket's `tale locket` and its scenes
        io.github.vaspyyy.guildmark.tales.templates.LocketCommands.register()
        // T2b: the lantern's `tale tradition` and `tale lantern`, its scenes, and the open lantern tales seen to each second
        io.github.vaspyyy.guildmark.tales.templates.LanternFestival.register()
        // T2a: the child's and the cart's watch and `tale protect`
        TalesT2a.register()
        // T4: the moot's `tale moot` and its scenes, and the open moots seen to each second
        io.github.vaspyyy.guildmark.tales.templates.MootSquare.register()
        // T4: the chair's `tale chair` and its scenes, and the open chairs seen to each second
        io.github.vaspyyy.guildmark.tales.templates.ChairWatch.register()
    }

    // ---- The clock ----

    /** From `Markets.step`, after `Fortunes.day`: [entry]'s capital's close, once a day. The engine settles, seeds and starts tales here (Part A). */
    fun day(level: ServerLevel, entry: Markets.Entry) {
        TaleClose.day(level, entry)
    }

    /** From `Scenes.tick` or the engine's own second: a player is in the capital whose heart is [capital] today (`seen`, 2.2 step 4). Part A keeps it itself each second, so a caller need not. */
    fun see(level: ServerLevel, capital: Long) {
        if (live) TaleEngine.see(level, capital)
    }

    /** From `Comings.Listener.back`, once the clock is set back [days] market days: every tale's days go back with it (Part A). */
    override fun back(level: ServerLevel, days: Long) {
        if (!live) return
        try {
            TaleKeep.back(TaleData.get(level), days)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The tales' days did not go back with the clock", e)
        }
    }

    /** `Comings.Listener`: a household came in, went, or came home. A cast member's going is the engine's to note (5.6); the tale hears of each (10.1). */
    override fun arrived(level: ServerLevel, h: Household) = TaleListeners.household(level, "arrived", h)

    override fun left(level: ServerLevel, h: Household) = TaleListeners.household(level, "left", h)

    override fun returned(level: ServerLevel, h: Household) = TaleListeners.household(level, "returned", h)

    // ---- The scene ----

    /** From `Scenes.tick`, slot 19, each second for a player in a capital ([here]): the scene due, the motes, the tidy (Part B). */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) = TaleLook.look(level, player, here)

    /** From `Scenes.forget`, as the server stops: every stage, figure and mote let go (Part B). */
    fun forget() = TaleLook.forget()

    // ---- The people ----

    /** Whether resident [id]'s day is a tale's for now (they are held), so their day body is not made. From `Residents.onJoin` and `Comings.leavers`. */
    fun holds(id: Int): Boolean = live && TaleEngine.holds(id)

    /** From `Residents.pass`, after the record half of a death: [r] died, or was put to rest, and a tale that cast them copes (Part A). */
    fun died(level: ServerLevel, r: Resident) {
        if (!live) return
        TaleGone.died(level, r)
        tell(level, Event.Died(r))
    }

    /** From `Guildmark.onLivingDeath`: [killer] (or their companion) killed [victim]; the handcart's camp counts it (T2). */
    fun onKill(level: ServerLevel, victim: LivingEntity, killer: Player) = TalesT2a.killed(level, victim, killer)

    /** From `Wardfires.kindle`: [player] kindled the Wardfire at [pos]; the handcart's camp and the mine's fire hear it (T2, T5). */
    fun kindled(level: ServerLevel, pos: BlockPos, player: ServerPlayer) = TalesT2a.kindled(level, pos, player)

    // ---- What a tale hears, placed by the tale that needs it ----

    /**
     * From `Witness.dealt` (T1 places the call): [player] dealt [lots] lots of [good] at [keeper]'s stall of [entry]'s
     * market, [buying] or selling, for [marks] Marks as `dealt` reckons them. The Marlow note counts what is bought at its debtor's stall.
     */
    fun bought(level: ServerLevel, keeper: Townsperson, player: ServerPlayer, entry: Markets.Entry, good: Good, buying: Boolean, lots: Int, marks: Int) {
        if (buying) NoteStall.bought(level, keeper, player, good, lots, marks)
    }

    /** From `Lairs.onBossDeath` (T5 places the call): [lair] fell, with [heroes] about. */
    fun lairCleared(level: ServerLevel, lair: Lair, heroes: List<ServerPlayer>) {}

    /** From `Captives.homecoming` (T3 places the call): captive [p] is home. */
    fun captiveHome(level: ServerLevel, p: Townsperson) = io.github.vaspyyy.guildmark.tales.templates.WagonCamp.home(level, p)

    /** From `Captives.onDeath` (T3 places the call): captive [p] died. */
    fun captiveDied(level: ServerLevel, p: Townsperson) = io.github.vaspyyy.guildmark.tales.templates.WagonCamp.died(level, p)

    /** From `FestivalSite.lanterns` (T2 places the call): the Lanternrise's lanterns are about to go, at the festival's [centre]. */
    fun lanternStart(level: ServerLevel, centre: Vec3) = io.github.vaspyyy.guildmark.tales.templates.LanternFestival.start(level, centre)

    /** From `FestivalSite.lanterns` (T2 places the call), the moment the lanterns are let go at [centre]. */
    fun lanternRelease(level: ServerLevel, centre: Vec3) = io.github.vaspyyy.guildmark.tales.templates.LanternFestival.release(level, centre)

    /** From `Contracts.turnIn` (E places the call): [player] handed in [note] (its chain begins `tale:`) in [state]. */
    fun contractDone(level: ServerLevel, player: Player, note: QuestNote, state: ContractState) = contract(level, player, note, state, false)

    /** From `Contracts.turnIn` (E places the call): [player] handed in [note] after its deadline. */
    fun contractExpired(level: ServerLevel, player: Player, note: QuestNote, state: ContractState) = contract(level, player, note, state, true)

    /** The note's chain is `tale:<tale>:<step>`; the tale it names hears that it was handed in. A chain that does not read so is none of ours. */
    private fun contract(level: ServerLevel, player: Player, note: QuestNote, state: ContractState, expired: Boolean) {
        if (!live) return
        val chain = note.chain.split(':')
        if (chain.size < 3 || chain[0] != "tale") return
        val tale = chain[1].toIntOrNull() ?: return
        val step = chain[2].toIntOrNull() ?: return
        tell(level, Event.Contract(tale, step, player, note, state, expired))
    }

    // ---- What a tale shows, placed by the part that shows it ----

    /** From `Crier.news` (E places the call): what [entry]'s crier cries of tales at [c]'s noon, [at] being the game time. None yet. */
    fun cries(level: ServerLevel, entry: Markets.Entry, c: Conditions, at: Long): List<Crier.Cry> =
        TaleTold.cries(level, entry, c, at) + io.github.vaspyyy.guildmark.tales.templates.LanternFestival.cries(level, entry, c, at) +
            io.github.vaspyyy.guildmark.tales.templates.MootSquare.cries(level, entry, c, at)

    /** From `Streets.wants` (F places the call): whether the memorial at [pos] in [column] is still wanted by the record of [run]. */
    fun wants(level: ServerLevel, column: Long, pos: BlockPos, run: Long): Boolean = Memorials.wants(level, column, pos, run)

    /** From `ShutStall.bare` (T1 places the call): whether the stall at [key] is sealed by a tale at game time [gameTime]. */
    fun sealed(key: Long, gameTime: Long): Boolean = NoteStall.sealed(key, gameTime)

    /** From `Jobs.bard` (T1 places the call): whether [p], a bard, plays no music for a tale's sake. */
    fun silent(p: Townsperson): Boolean = io.github.vaspyyy.guildmark.tales.templates.LuteSilence.silent(p)

    /** From `Jobs.bard` (T1 places the call): what a bard who [silent] does instead of playing. */
    fun quiet(p: Townsperson): Task? = io.github.vaspyyy.guildmark.tales.templates.LuteSilence.quiet(p)

    /** From `WorksBuild.labourers` (T1 places the call): the ids of [capital]'s residents the works take on first. Part A keeps the set. */
    fun volunteers(capital: Long): Set<Int> = if (live) TaleEngine.volunteers(capital) else setOf()

    // ---- The calls that need a Tale (tales A1: declared here, each a no-op or a plain "no" until its owner fills it in; design 14.4, 14.5) ----

    /** Part B (4.4 rule 2): holds the resident in [slot] of [t] away from their day, their tale body at [at] playing [clip]; false if they cannot be held (three at once, scenes off, no such slot). */
    fun hold(level: ServerLevel, t: Tale, slot: String, at: BlockPos? = null, clip: Activity? = null): Boolean = TaleBodies.hold(level, t, slot, at, clip)

    /** Part B (4.4 rule 4): lets the held resident in [slot] of [t] go back to their day. */
    fun release(level: ServerLevel, t: Tale, slot: String) {
        TaleBodies.release(level, t, slot)
    }

    /** Part A, slice 2 (6.5): sends the household of lodging [home] away for [t] (`Comings.leave` for one named household, the reason recorded as `tale <template> <id>`); false if the town is at its floor and the household stays. [why] is the `Why` kind to add when it does. */
    fun leave(c: Ctx, t: Tale, home: Int, why: String? = null): Boolean = TaleHouseholds.leave(c, t, home, why)

    /** Part A, slice 2 (6.5): brings the household [away] home through [gate] (`Comings.comeBack` with force); false if it could not. */
    fun bringHome(c: Ctx, t: Tale, away: ComingsData.Moved, gate: BlockPos? = null): Boolean = TaleHouseholds.bringHome(c, t, away, gate)

    /** Part A, slice 2 (6.5): protects the household of lodging [home] from leaving for [days] market days after [Ctx.day]. */
    fun protect(c: Ctx, t: Tale, home: Int, days: Int) = TaleHouseholds.protect(c, t, home, days)

    /** From `Comings.leavers` (Part A, 6.5): whether the household at lodging [home] of [capital], with [members] living in it, may not leave now: one of them is in the cast of an open tale, or a tale protects it. False with tales off. */
    fun stays(level: ServerLevel, capital: Long, home: Int, members: List<Resident>): Boolean = live && TaleHouseholds.stays(level, capital, home, members)

    /** Part A (6.7): the one door every ending goes through, whoever or whatever caused it: [t] ends with [ending] and the ten stages run, each guarded, each marked in [Tale.done]. */
    fun finish(c: Ctx, t: Tale, ending: String) {
        if (live) TaleFinish.finish(c, t, ending)
    }

    /** Part A (2.2 step 7): seeds [template] in [c]'s capital now and answers the tale (step 0, a `Why`, the cast held, the first step entered), or null; [forced] is `tale start`. */
    fun seed(c: Ctx, template: Template, forced: Boolean = false): Tale? = TaleSeeding.attempt(c, template, forced).tale

    /** Part A: tells the tale [e] concerns (the open tale of its capital, or the one it names) so its template can hear it. How `Event.Arrived` (B) and `Event.Gave` (C) reach the engine. */
    fun tell(level: ServerLevel, e: Event) {
        if (live) TaleEvents.tell(level, e)
    }

    /** Part A: whether any open tale would hear an event of [kind] now, so a hot hook (every kill, every deal) can skip making one when none would. False with tales off. */
    fun listening(level: ServerLevel, kind: Event.Kind): Boolean = live && TaleEvents.listening(level, kind)

    /** Part A (1.3, 3.1): [player] said yes to [t]: their part goes IN (and their party mates', 4.7), the banner and the chime follow, and an ASK step ends. How the talk (C) hands the word to the engine. */
    fun join(level: ServerLevel, t: Tale, player: ServerPlayer): Boolean = TaleSteps.join(level, t, player)

    /** Part E (6.5): changes the ways of [r], of [t]'s cast, to [to] (the opposite is cleared, the flip is logged in `TaleData.flips`); false if a limit stops it (two a tale, one a resident in forty days) and a `Why` says so. */
    fun flip(c: Ctx, t: Tale, r: Resident, to: Trait): Boolean = TaleFlips.flip(c, t, r, to)

    /** Part E (7.3): pays [player] for [t] through `Contracts.payOut`: the points of their part (7.2), less what the tale's own notes have paid, and the keepsake. */
    fun pay(level: ServerLevel, player: ServerPlayer, t: Tale) = TalePay.pay(level, player, t)

    /** Part E (7.4): pays [player] what is owed them in `TaleData.owed` (pay earned while away, escrow given back), once each; on login and when the board or a works screen opens. */
    fun owed(level: ServerLevel, player: ServerPlayer) = TalePay.owed(level, player)

    /** Part T2 (6.5, 8.9): the traditions [capital]'s festival keeps; the lantern's release and the crier's festival cry read them. None yet. */
    fun traditions(level: ServerLevel, capital: Long): List<TaleData.Tradition> = io.github.vaspyyy.guildmark.tales.templates.LanternFestival.traditions(level, capital)

    /** [get], or true before the world's config is read (as every switch answers). */
    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }
}
