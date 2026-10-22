package io.github.vaspyyy.guildmark.cooking.vessel

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `/guildmark cook view <state> [seconds]` (living-world/cook/design.md 10.3, Appendix C.4): a made-up state shown on the
 * station you are looking at, to everyone near it, for the renderers, the sounds and the screenshots, without cooking
 * anything. The states are `empty`, `filling`, `heating`, `cooking`, `call <stick|boil|skim|flare|turn|season>`, `ready`,
 * `done` and `burnt`; the time is a minute unless given. `view clear` takes every one of them off at once.
 *
 * A state is not a still picture but the moment it names, played the way the table of 1.5 and 1.6 says: `filling` pours
 * the water, drops in the items one by one and lights the fire; `heating` is the first stir (the scrape, the shut door, the
 * crank); a `call` is cued and then, round after round, let go (the missed sign) and answered in turn; `ready` is the
 * window over and over, the chime at its start and the ting at its centre; `done` and `burnt` come in with their hiss.
 * The sounds and the puffs the server plays for the real thing are played here at the same moments, since a made-up view
 * has no vessel to play them, and the client does the rest from the view, as it does for a real station.
 *
 * It is a picture and nothing is cooked: no vessel, clock, fuel or call is touched, nothing is saved (the view is sent but
 * never written), and when the time is up, the block is broken or the server stops, the station is its real self again.
 * The one thing it does change, for as long as it shows, is the lit and burnt look of the block (the lit shells carry their
 * own flames), which [VesselBlockEntity.settleNow] puts back.
 */
object ViewCommand {
    private const val DEFAULT_SECONDS = 60
    private const val MAX_SECONDS = 3600

    /** Ticks before the first thing happens, and between two items going in (a board's piles are quicker). */
    private const val LEAD = 8L
    private const val ADD_GAP = 22L
    private const val BOARD_GAP = 10L

    /** Ticks between two chops of the knife, and how many pieces a board is shown with. */
    private const val CHOP_GAP = 22L
    private const val BOARD_ITEMS = 8

    /** Ticks a board rests, empty, before the heap is laid again. */
    private const val BOARD_REST = 30L

    /** Ticks from a call being cued to it being answered (inside the full-credit time), and the whole round of a call. */
    private const val ANSWER_AT = 60L
    private const val CALL_ROUND = Calls.LATE_TICKS + 50L

    /** The colour a stew's liquid comes to once the pot is full of it (the mean of the meat, green and sweet liquids). */
    private const val STEW_RGB = 0xA08540

    /** The calls by the short words of the command. */
    private val CALLS: Map<String, Call> = linkedMapOf(
        "stick" to Call.STICK, "boil" to Call.BOIL_OVER, "skim" to Call.SKIM, "flare" to Call.FLARE, "turn" to Call.TURN, "season" to Call.SEASON,
    )

    /** What a show is of: the words of the command. */
    private enum class Moment(val word: String) {
        EMPTY("empty"), FILLING("filling"), HEATING("heating"), COOKING("cooking"), CALL("call"), READY("ready"), DONE("done"), BURNT("burnt"),
    }

    /** The shows going on. Server thread only. */
    private val shows = ArrayList<Show>()

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick() }
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent::class.java) { finishAll() }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val view = Commands.literal("view").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        for (moment in Moment.entries) {
            if (moment != Moment.CALL) view.then(leaf(moment.word, moment, null))
        }
        val call = Commands.literal("call")
        for ((word, which) in CALLS) call.then(leaf(word, Moment.CALL, which))
        view.then(call)
        view.then(Commands.literal("clear").executes { clear(it) })
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("cook").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(view)),
        )
    }

    /** One state's node: the word, and an optional number of seconds. */
    private fun leaf(word: String, moment: Moment, call: Call?): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(word)
            .executes { launch(it, moment, call, DEFAULT_SECONDS) }
            .then(
                Commands.argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
                    .executes { launch(it, moment, call, IntegerArgumentType.getInteger(it, "seconds")) },
            )

    // ---- The commands ----

    private fun say(context: CommandContext<CommandSourceStack>, text: String, color: ChatFormatting = ChatFormatting.WHITE): Int {
        context.source.sendSuccess({ Component.literal(text).withStyle(color) }, false)
        return 1
    }

    private fun fail(context: CommandContext<CommandSourceStack>, text: String): Int {
        context.source.sendFailure(Component.literal(text))
        return 0
    }

    private fun launch(context: CommandContext<CommandSourceStack>, moment: Moment, call: Call?, seconds: Int): Int {
        val source = context.source
        val player = source.player ?: return fail(context, "Run this as a player, looking at a station.")
        val be = Stations.lookedAt(player) ?: return fail(context, "Look at a station (a pot, pan, oven, spit or prep board) first, within six blocks.")
        val station = be.station
        if (station == null && moment != Moment.EMPTY && moment != Moment.FILLING) {
            return fail(context, "The prep board has no fire: it can show `empty` and `filling` (the heap laid, then chopped).")
        }
        if (call != null && !asks(station, call)) {
            return fail(context, "The ${noun(station)} never asks for that. It asks for: ${CALLS.filter { asks(station, it.value) }.keys.joinToString(", ")}.")
        }
        val level = source.level
        val old = shows.firstOrNull { it.be === be }
        if (old != null) {
            old.finish()
            shows.remove(old)
        }
        val now = level.gameTime
        val show = Show(level, be, moment, call, now, now + seconds * 20L, old?.real ?: be.view())
        shows.add(show)
        show.begin()
        val what = if (call != null) "call ${CALLS.entries.first { it.value == call }.key}" else moment.word
        say(context, "Showing `$what` on the ${noun(station)} for $seconds s. ${note(moment, station)} Nothing is cooked or saved; `cook view clear` ends it.")
        if (be is VesselBlockEntity && be.vessel.lit && (moment == Moment.EMPTY || moment == Moment.FILLING)) {
            say(context, "Its fire really burns, so the block keeps its flames; `cook vessel clear` puts the fire out first.", ChatFormatting.GRAY)
        }
        if (!Cooking.enabled()) {
            say(context, "Hearthcraft is off (setting): the station is drawn, but its steam, bubbles and loops are not made.", ChatFormatting.GRAY)
        }
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val n = shows.size
        finishAll()
        return say(context, if (n == 0) "Nothing was being shown." else "Took the made-up state off $n station${if (n == 1) "" else "s"}.")
    }

    /** What a show does over its time, in a line. */
    private fun note(moment: Moment, station: Station?): String = when (moment) {
        Moment.EMPTY -> "Cold and empty."
        Moment.FILLING -> if (station == null) "The heap is laid, then chopped away, over and over." else "The items go in one by one and the fire is lit."
        Moment.HEATING -> "The first stir, and then it heats."
        Moment.COOKING -> "It cooks."
        Moment.CALL -> "The call comes and is let go (the missed sign) and then comes and is answered, round and round."
        Moment.READY -> "The ready window comes round and round."
        Moment.DONE -> "Past the window."
        Moment.BURNT -> "It has caught."
    }

    private fun noun(station: Station?): String = when (station) {
        Station.STEWPOT -> "pot"
        Station.HOB -> "pan"
        Station.OVEN -> "oven"
        Station.SPIT -> "spit"
        null -> "prep board"
    }

    /** Whether a station of this kind ever asks for [call] (1.4: the pot STICK, BOIL_OVER, SKIM, SEASON; the pan STICK, TURN, FLARE; the oven TURN, FLARE, SEASON; the spit TURN, FLARE). */
    private fun asks(station: Station?, call: Call): Boolean = when (station) {
        Station.STEWPOT -> call == Call.STICK || call == Call.BOIL_OVER || call == Call.SKIM || call == Call.SEASON
        Station.HOB -> call == Call.STICK || call == Call.TURN || call == Call.FLARE
        Station.OVEN -> call == Call.TURN || call == Call.FLARE || call == Call.SEASON
        Station.SPIT -> call == Call.TURN || call == Call.FLARE
        null -> false
    }

    // ---- The clock of the shows ----

    private fun tick() {
        if (shows.isEmpty()) return
        val each = shows.iterator()
        while (each.hasNext()) {
            val show = each.next()
            val now = show.level.gameTime
            // The block was broken, or its chunk let go: there is nothing to put back
            if (show.be.isRemoved || show.level.getBlockEntity(show.pos) !== show.be) {
                each.remove()
                continue
            }
            if (now >= show.end) {
                show.finish()
                each.remove()
                continue
            }
            show.step(now)
        }
    }

    /** Takes every show off, each station back to its real self (the server is stopping, or an operator asked). */
    private fun finishAll() {
        for (show in ArrayList(shows)) show.finish()
        shows.clear()
    }

    // ---- What is shown ----

    /** One thing to do at game time [at]: [act] runs on the show. */
    private class Beat(val at: Long, val act: Show.() -> Unit)

    /** The beats of one round of a state, and the ticks to the next round (0 when it is not repeated). */
    private class Script(val beats: List<Beat>, val length: Long = 0L)

    /** The foods laid in each kind of station for the picture: a stew's, a pan of roots, a loaf's, a roast's, a heap on the board. */
    private fun bill(station: Station?): List<Item> = when (station) {
        Station.STEWPOT -> listOf(Items.BEEF, Items.CARROT, Items.POTATO, Items.BEEF, Items.BROWN_MUSHROOM, Items.CARROT, Items.WHEAT)
        Station.HOB -> listOf(Items.CARROT, Items.POTATO, Items.CARROT, Items.BEETROOT, Items.POTATO, Items.CARROT)
        Station.OVEN -> listOf(Items.WHEAT, Items.WHEAT, Items.WHEAT, Items.WHEAT, Items.APPLE, Items.APPLE, Items.HONEY_BOTTLE)
        Station.SPIT -> listOf(Items.BEEF, Items.BEEF, Items.BEEF, Items.BEEF, Items.PORKCHOP)
        null -> List(BOARD_ITEMS) { Items.CARROT }
    }

    /** The plop of an item going in, pitched by what it mostly is (1.5 row 3). */
    private fun plopOf(item: Item): String = when (item) {
        Items.BEEF, Items.MUTTON, Items.PORKCHOP, Items.COD, Items.SALMON -> "cook.plop.meat"
        Items.WHEAT -> "cook.plop.grain"
        else -> "cook.plop.soft"
    }

    private fun script(s: Show, base: Long, round: Int): Script = when (s.moment) {
        Moment.EMPTY -> Script(listOf(Beat(base) { cold(); push() }))
        Moment.FILLING -> if (s.station == null) boardWork(base) else filling(s, base)
        Moment.HEATING -> heating(s, base)
        Moment.COOKING -> Script(listOf(Beat(base) { fill(Vessel.State.COOKING); push() }))
        Moment.CALL -> calling(s, base, round)
        Moment.READY -> ready(s, base, round)
        Moment.DONE -> Script(
            listOf(
                Beat(base) {
                    fill(Vessel.State.DONE)
                    ring("cook.sizzle", 0.6f, 0.8f)
                    smoke(4)
                    push()
                },
            ),
        )
        Moment.BURNT -> Script(
            listOf(
                Beat(base) {
                    fill(Vessel.State.BURNT)
                    ring("cook.burnt", 1.0f, 1.0f)
                    smoke(18)
                    push()
                },
            ),
        )
    }

    /** Rows 2 to 4 of the table: the water is poured, each food goes in with its plop, the logs are laid and the fire catches. */
    private fun filling(s: Show, base: Long): Script {
        val out = ArrayList<Beat>()
        var t = base
        out.add(Beat(t) { cold(); push() })
        t += LEAD
        if (s.station == Station.STEWPOT) {
            out.add(
                Beat(t) {
                    water = Vessel.WATER_FULL
                    ring("cook.pour", 1.0f, 1.0f)
                    push()
                },
            )
            t += 40L
        }
        for (item in bill(s.station)) {
            out.add(Beat(t) { put(item) })
            t += ADD_GAP
        }
        t += 14L
        out.add(Beat(t) { ring("cook.fuel", 1.0f, 1.0f) })
        t += 20L
        out.add(
            Beat(t) {
                lit = true
                ring("cook.ignite", 1.0f, 1.0f)
                push()
            },
        )
        return Script(out)
    }

    /** The prep board: the heap is laid a piece at a time, then chopped away a piece at a time, and the board rests and starts again. */
    private fun boardWork(base: Long): Script {
        val out = ArrayList<Beat>()
        var t = base
        out.add(Beat(t) { cold(); push() })
        t += LEAD
        repeat(BOARD_ITEMS) {
            out.add(Beat(t) { put(Items.CARROT) })
            t += BOARD_GAP
        }
        t += 14L
        repeat(BOARD_ITEMS) {
            out.add(Beat(t) { chop() })
            t += CHOP_GAP
        }
        return Script(out, t - base + BOARD_REST)
    }

    /** Row 6: the pot stands full and cold-started, and a moment later the first stir (the scrape, the shut door, the first turn of the crank) sets the heating going. */
    private fun heating(s: Show, base: Long): Script {
        val oven = s.station == Station.OVEN
        return Script(
            listOf(
                Beat(base) {
                    fill(Vessel.State.FILLING)
                    if (oven) {
                        swirlUntil = level.gameTime + VesselBlockEntity.DOOR_TICKS
                        ring("cook.oven_open", 0.9f, 1.0f)
                    }
                    push()
                },
                Beat(base + 30L) {
                    firstStir()
                    push()
                },
            ),
        )
    }

    /** Row 15: the ready window, with the chime as it opens and the ting at its centre, and then the next one. */
    private fun ready(s: Show, base: Long, round: Int): Script = Script(
        listOf(
            Beat(base) {
                if (round == 0) fill(Vessel.State.READY) else state = Vessel.State.READY
                readyFrom = base
                readyTo = base + 2L * half
                ring("cook.ready", 0.8f, 1.0f)
                push()
            },
            Beat(base + s.half) { ring("cook.ripe", 0.6f, 1.0f) },
        ),
        2L * s.half,
    )

    /** Rows 8 to 14: a call is cued (the knock, the glint), and then it is let go (the sign worsens) in one round and answered in the next. */
    private fun calling(s: Show, base: Long, round: Int): Script {
        val call = s.call ?: return Script(emptyList())
        val answered = round % 2 == 1
        val cue = Beat(base) {
            if (round == 0) fill(Vessel.State.COOKING)
            callWire = call.wire
            callDue = base + Calls.LATE_TICKS
            ring("cook.call", 1.0f, 1.0f)
            puff(CookParticles.CALL.get(), 3, 0.2)
            push()
        }
        val end = if (answered) {
            Beat(base + ANSWER_AT) {
                callWire = 0
                callDue = 0L
                answer(call)
                push()
            }
        } else {
            Beat(base + Calls.LATE_TICKS) {
                callWire = 0
                callDue = 0L
                miss(call)
                push()
            }
        }
        return Script(listOf(cue, end), CALL_ROUND)
    }

    /**
     * One show on one station: the picture it is making now (the fields below, which every beat changes and every push sends
     * whole), the beats still to come, and what it needs to put the real station back.
     */
    private class Show(
        val level: ServerLevel,
        val be: StationBlockEntity,
        val moment: Moment,
        val call: Call?,
        val start: Long,
        val end: Long,
        /** What the station showed before the first show (its mode, bowls and jack are kept in the picture). */
        val real: StationView,
    ) {
        val pos: BlockPos = be.blockPos
        val station: Station? = be.station

        /** The heat as the station really has it (the picture keeps it, so the damper and the pot's height stay as the operator set them). */
        private val notch: Int = if (be is VesselBlockEntity) be.vessel.notch.coerceIn(0, 2) else 0

        /** Half of the ready window, in ticks, at that heat: eight dish-seconds at its speed (1.2). */
        val half: Long = (160.0 / Vessel.SPEED[notch]).toLong()

        // ---- The picture ----

        var state: Vessel.State = Vessel.State.EMPTY
        var lit = false
        var burnt = false
        var water = 0
        private val items = ArrayList<Int>()
        private var tint = 0
        private var added = real.added
        private var addedCount = real.addedCount
        var callWire = 0
        var callDue = 0L
        var readyFrom = 0L
        var readyTo = 0L
        var swirlUntil = 0L

        // ---- The beats ----

        private val queue = ArrayDeque<Beat>()
        private var again = 0L
        private var round = 0

        fun begin() {
            load(script(this, start, 0), start)
            step(start)
        }

        private fun load(script: Script, base: Long) {
            queue.addAll(script.beats)
            again = if (script.length > 0L) base + script.length else 0L
        }

        /** Does what is due at game time [now]. */
        fun step(now: Long) {
            if (queue.isEmpty() && again > 0L && now >= again) {
                round++
                load(script(this, again, round), again)
            }
            while (true) {
                val beat = queue.firstOrNull() ?: break
                if (beat.at > now) break
                queue.removeFirst()
                beat.act(this)
            }
            // Something else (the vessel's own tick) may have put the block back as it really is
            if (now % 10L == 0L) applyBlock()
        }

        /** Takes the made-up picture off, and the block back to what the real vessel says. */
        fun finish() {
            be.clearFake()
            if (be is VesselBlockEntity) be.settleNow()
        }

        // ---- What a beat can do ----

        fun view(): StationView = StationView(
            station, if (station == null) (if (items.isEmpty()) 0 else 1) else state.ordinal, notch, lit, burnt, water, items.toIntArray(), tint,
            added, addedCount, callWire, callDue, readyFrom, readyTo, real.mode, swirlUntil, real.bowls, real.turning,
        )

        /** Sends the picture as it is now to everyone who sees the station, to last until the show's end. */
        fun push() {
            applyBlock()
            be.showFake(view(), end)
        }

        /**
         * The lit and burnt look of the block, which the shell models follow, set to the picture's. A fire that really burns keeps
         * its flames (the vessel's own tick sets the block to what is real every other tick, and the two would fight), and so does a
         * vessel that really has caught.
         */
        private fun applyBlock() {
            if (be !is VesselBlockEntity) return
            val now = level.getBlockState(pos)
            var s = now
            if (s.hasProperty(StationProps.LIT)) s = s.setValue(StationProps.LIT, lit || be.vessel.lit)
            if (s.hasProperty(StewpotBlock.BURNT)) s = s.setValue(StewpotBlock.BURNT, burnt || be.vessel.state == Vessel.State.BURNT)
            if (s != now) level.setBlock(pos, s, Block.UPDATE_ALL)
        }

        /** Cold, dry and empty, with no call and no clock. */
        fun cold() {
            state = Vessel.State.EMPTY
            lit = false
            burnt = false
            water = 0
            items.clear()
            tint = 0
            callWire = 0
            callDue = 0L
            readyFrom = 0L
            readyTo = 0L
            swirlUntil = 0L
        }

        /** The station full and lit, in [into]; a cooking one is shown with its window opening when the show ends, so the pieces brown toward it. */
        fun fill(into: Vessel.State) {
            cold()
            state = into
            lit = true
            burnt = into == Vessel.State.BURNT
            if (station != null) water = Vessel.WATER_FULL
            for (item in bill(station)) items.add(BuiltInRegistries.ITEM.getId(item))
            tint = if (station == Station.STEWPOT) tintFor(items.size) else 0
            if (into == Vessel.State.COOKING) {
                readyFrom = end
                readyTo = end + 2L * half
            }
        }

        /** A food goes in: the counter moves (every client plays its plop and ripple), the liquid tints toward it, and the server's plop is heard. */
        fun put(item: Item) {
            val id = BuiltInRegistries.ITEM.getId(item)
            items.add(id)
            added = id
            addedCount++
            if (state == Vessel.State.EMPTY) state = Vessel.State.FILLING
            if (station == null) {
                ring("cook.plop.soft", 0.6f, 1.2f)
            } else {
                ring(plopOf(item), 0.8f, 0.9f + 0.05f * (items.size % 5))
                puff(CookParticles.STEAM.get(), 2, 0.12)
            }
            tint = if (station == Station.STEWPOT) tintFor(items.size) else 0
            if (station == Station.OVEN) openDoor()
            push()
        }

        /** The oven's door swings open and stays so for 40 ticks. */
        private fun openDoor() {
            val now = level.gameTime
            val shut = swirlUntil <= now
            swirlUntil = now + VesselBlockEntity.DOOR_TICKS
            if (shut) ring("cook.oven_open", 0.9f, 1.0f)
        }

        /** The knife comes down: a piece leaves the heap in the same picture the knife is shown at work in. */
        fun chop() {
            if (items.isEmpty()) return
            items.removeAt(items.size - 1)
            swirlUntil = level.gameTime + PrepBoardBlockEntity.SHOW_TICKS
            ring("cook.chop", 0.8f, 0.95f + 0.02f * (items.size % 5))
            level.sendParticles(CookParticles.CRUMB.get(), pos.x + 0.5, pos.y + 0.75, pos.z + 0.5, 3, 0.15, 0.05, 0.15, 0.02)
            push()
        }

        /** Row 6: the first stir of a pot, the scrape of a pan, the shutting of the oven door or the first turn of the spit's crank. */
        fun firstStir() {
            val now = level.gameTime
            state = Vessel.State.HEATING
            when (station) {
                Station.STEWPOT -> {
                    swirlUntil = now + VesselBlockEntity.STIR_TICKS
                    ring("cook.stir", 0.9f, 1.0f)
                }
                Station.HOB -> {
                    swirlUntil = now + VesselBlockEntity.STIR_TICKS
                    ring("cook.stir", 0.8f, 1.3f)
                }
                Station.OVEN -> {
                    swirlUntil = now
                    ring("cook.oven_shut", 1.0f, 1.0f)
                }
                Station.SPIT -> {
                    swirlUntil = now + VesselBlockEntity.CRANK_TICKS
                    ring("cook.crank", 0.8f, 1.0f)
                }
                null -> {}
            }
        }

        /** What answering a call looks and sounds like (the sounds of the real click, 1.5 rows 9, 11 and 13, 1.6). */
        fun answer(call: Call) {
            val now = level.gameTime
            when (call) {
                Call.STICK -> {
                    swirlUntil = now + VesselBlockEntity.STIR_TICKS
                    ring("cook.stir", 0.8f, if (station == Station.HOB) 1.3f else 1.0f)
                }
                Call.BOIL_OVER, Call.FLARE -> ring("cook.ease", 1.0f, 0.9f + 0.1f * notch)
                Call.SKIM -> ring("cook.skim", 1.0f, 1.0f)
                Call.TURN -> when (station) {
                    Station.OVEN -> {
                        openDoor()
                        ring("cook.flip", 1.0f, 1.0f)
                    }
                    Station.SPIT -> {
                        swirlUntil = now + VesselBlockEntity.CRANK_TICKS
                        ring("cook.crank", 0.8f, 1.0f)
                    }
                    else -> ring("cook.flip", 1.0f, 1.0f)
                }
                Call.SEASON -> {
                    ring("cook.plop.soft", 0.7f, 1.2f)
                    puff(CookParticles.STEAM.get(), 4, 0.15)
                }
            }
        }

        /** What a call let go does at the server: the sign gets worse (1.5 row 14). */
        fun miss(call: Call) {
            when (call) {
                Call.STICK -> {
                    ring("cook.crackle", 0.8f, 0.9f)
                    smoke(6)
                }
                Call.BOIL_OVER -> {
                    ring("cook.boil_over", 1.0f, 1.0f)
                    puff(CookParticles.FOAM.get(), 14, 0.35)
                    puff(CookParticles.STEAM.get(), 8, 0.3)
                }
                Call.FLARE -> {
                    ring("cook.flare", 1.0f, 1.0f)
                    puff(CookParticles.SIZZLE.get(), 10, 0.3)
                    smoke(4)
                }
                else -> ring("cook.crackle", 0.5f, 1.1f)
            }
        }

        // ---- Sounds and puffs, played as the server plays them for the real thing ----

        fun ring(name: String, volume: Float, pitch: Float) {
            level.playSound(null, pos, CookSounds.get(name), SoundSource.BLOCKS, volume, pitch)
        }

        fun puff(particle: ParticleOptions, count: Int, spread: Double) {
            level.sendParticles(particle, pos.x + 0.5, pos.y + 1.05, pos.z + 0.5, count, spread, 0.1, spread, 0.02)
        }

        fun smoke(count: Int) {
            puff(CookParticles.SMOKE.get(), count, 0.3)
        }

        /** The liquid's colour with [n] foods in: plain water, then blended toward a stew's by the same measure the real pot uses. */
        private fun tintFor(n: Int): Int {
            if (n <= 0) return 0
            val amount = min(1.0, n / VesselBlockEntity.TINT_ITEMS)
            var rgb = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val from = VesselBlockEntity.WATER_RGB shr shift and 0xFF
                val to = STEW_RGB shr shift and 0xFF
                rgb = rgb or ((from * (1.0 - amount) + to * amount).roundToInt() shl shift)
            }
            return rgb
        }
    }
}
