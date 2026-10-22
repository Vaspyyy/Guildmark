package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.lair.Lair
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
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

    /**
     * Sets [live] to [enabled]. Part A calls it at server start, once a second, and at the top of every [day], and adds
     * [pause] when it finds the switch gone off.
     */
    fun refresh() {
        live = enabled()
    }

    /** The switch went off: the stages end and the tale bodies are let go, and nothing is written to any tale (Part A, B; design 11.5). */
    fun pause(level: ServerLevel) {}

    /** At setup, after the streets: the listeners (Part A), and each of B to F adds a block of its own. Registers nothing yet. */
    fun register(modBus: IEventBus) {}

    // ---- The clock ----

    /** From `Markets.step`, after `Fortunes.day`: [entry]'s capital's close, once a day. The engine settles, seeds and starts tales here (Part A). */
    fun day(level: ServerLevel, entry: Markets.Entry) {}

    /** From `Comings.Listener.back`, once the clock is set back [days] market days: every tale's days go back with it (Part A). */
    override fun back(level: ServerLevel, days: Long) {}

    // ---- The scene ----

    /** From `Scenes.tick`, slot 19, each second for a player in a capital ([here]): the scene due, the motes, the tidy (Part B). */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {}

    /** From `Scenes.forget`, as the server stops: every stage, figure and mote let go (Part B). */
    fun forget() {}

    // ---- The people ----

    /** Whether resident [id]'s day is a tale's for now (they are held), so their day body is not made. From `Residents.onJoin` and `Comings.leavers`. */
    fun holds(id: Int): Boolean = false

    /** From `Residents.pass`, after the record half of a death: [r] died, or was put to rest, and a tale that cast them copes (Part A). */
    fun died(level: ServerLevel, r: Resident) {}

    /** From `Guildmark.onLivingDeath`: [killer] (or their companion) killed [victim]; the handcart's camp counts it (T2). */
    fun onKill(level: ServerLevel, victim: LivingEntity, killer: Player) {}

    /** From `Wardfires.kindle`: [player] kindled the Wardfire at [pos]; the handcart's camp and the mine's fire hear it (T2, T5). */
    fun kindled(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {}

    // ---- What a tale hears, placed by the tale that needs it ----

    /**
     * From `Witness.dealt` (T1 places the call): [player] dealt [lots] lots of [good] at [keeper]'s stall of [entry]'s
     * market, [buying] or selling, for [marks] Marks as `dealt` reckons them. The Marlow note counts what is bought at its debtor's stall.
     */
    fun bought(level: ServerLevel, keeper: Townsperson, player: ServerPlayer, entry: Markets.Entry, good: Good, buying: Boolean, lots: Int, marks: Int) {}

    /** From `Lairs.onBossDeath` (T5 places the call): [lair] fell, with [heroes] about. */
    fun lairCleared(level: ServerLevel, lair: Lair, heroes: List<ServerPlayer>) {}

    /** From `Captives.homecoming` (T3 places the call): captive [p] is home. */
    fun captiveHome(level: ServerLevel, p: Townsperson) {}

    /** From `Captives.onDeath` (T3 places the call): captive [p] died. */
    fun captiveDied(level: ServerLevel, p: Townsperson) {}

    /** From `FestivalSite.lanterns` (T2 places the call): the Lanternrise's lanterns are about to go, at the festival's [centre]. */
    fun lanternStart(level: ServerLevel, centre: Vec3) {}

    /** From `FestivalSite.lanterns` (T2 places the call), the moment the lanterns are let go at [centre]. */
    fun lanternRelease(level: ServerLevel, centre: Vec3) {}

    /** From `Contracts.turnIn` (E places the call): [player] handed in [note] (its chain begins `tale:`) in [state]. */
    fun contractDone(level: ServerLevel, player: Player, note: QuestNote, state: ContractState) {}

    /** From `Contracts.turnIn` (E places the call): [player] handed in [note] after its deadline. */
    fun contractExpired(level: ServerLevel, player: Player, note: QuestNote, state: ContractState) {}

    // ---- What a tale shows, placed by the part that shows it ----

    /** From `Crier.news` (E places the call): what [entry]'s crier cries of tales at [c]'s noon, [at] being the game time. None yet. */
    fun cries(level: ServerLevel, entry: Markets.Entry, c: Conditions, at: Long): List<Crier.Cry> = listOf()

    /** From `Streets.wants` (F places the call): whether the memorial at [pos] in [column] is still wanted by the record of [run]. */
    fun wants(level: ServerLevel, column: Long, pos: BlockPos, run: Long): Boolean = false

    /** From `ShutStall.bare` (T1 places the call): whether the stall at [key] is sealed by a tale at game time [gameTime]. */
    fun sealed(key: Long, gameTime: Long): Boolean = false

    /** From `Jobs.bard` (T1 places the call): whether [p], a bard, plays no music for a tale's sake. */
    fun silent(p: Townsperson): Boolean = false

    /** From `Jobs.bard` (T1 places the call): what a bard who [silent] does instead of playing. */
    fun quiet(p: Townsperson): Task? = null

    /** From `WorksBuild.labourers` (T1 places the call): the ids of [capital]'s residents the works take on first. Part A keeps the set. */
    fun volunteers(capital: Long): Set<Int> = setOf()

    /** [get], or true before the world's config is read (as every switch answers). */
    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }
}
