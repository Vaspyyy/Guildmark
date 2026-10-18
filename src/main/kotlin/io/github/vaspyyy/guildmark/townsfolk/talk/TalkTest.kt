package io.github.vaspyyy.guildmark.townsfolk.talk

import com.mojang.brigadier.CommandDispatcher
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.network.TalkChoice
import io.github.vaspyyy.guildmark.network.TalkClosePayload
import io.github.vaspyyy.guildmark.network.TalkOpenPayload
import io.github.vaspyyy.guildmark.network.TalkPart
import io.github.vaspyyy.guildmark.network.TalkSayPayload
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

/**
 * `/guildmark talk test [stranger|heard|knows|wary]`: a scripted talk to try the talk screen with, needing nothing
 * else (living-world/design-voices.md section 7). The nearest townsperson within 12 blocks says it aloud, or nobody
 * does and the screen shows it alone; the word after it says how the plate shows they know the player. It has every
 * kind of part: a line with a detail, a long detail that wraps, a joined pair, and a part with no take; and six
 * answers, one of them asked already, each answered from the script, until Goodbye. While it's open the talk screen's
 * picks and its end from that player come here instead of to [Talk].
 *
 * It ends at Goodbye or when the screen closes; beyond 12 blocks, with the townsperson gone, after a minute with no
 * pick or four minutes in all, or with townsfolk talk switched off; and when the player leaves. Nothing of it is saved.
 */
object TalkTest {
    /** How near the townsperson who says it must be, and stay. */
    private const val REACH = 12.0

    /** How near a player must be for the townsperson to say it aloud (they always are: this is only say's own check). */
    private const val HEARING = 24.0

    /** Ticks with no pick before the test leaves off, and the most it lasts. */
    private const val IDLE = 1200
    private const val LONGEST = 4800

    /** Ticks after the last words before the test stops waiting for the screen's end. */
    private const val LINGER = 200

    /** Ticks between one part's take and the next, as the screen waits them. */
    private const val PART_GAP = 6

    /** How long the townsperson stops for the player at a time; it's kept up while the test runs. */
    private const val ATTEND = 60

    /** A line in Speech's form, and how long its take lasts in ticks (the longest timbre's; 0: it has none). */
    private class Line(val speech: String, val ticks: Int)

    /** One player's test talk, with entity [speaker] (-1: nobody), from server tick [started]. */
    private class Test(val player: UUID, val speaker: Int, val started: Int) {
        var serial = 0
        var lastPick = started
        val asked = HashSet<String>()

        /** The parts still to be said aloud, and the server tick each is due. */
        val aloud = ArrayDeque<Pair<Int, Line>>()

        /** The last words are out: the test waits for the screen's end until this tick (-1: still talking). */
        var over = -1
    }

    private val tests = HashMap<UUID, Test>()

    /** The answers, in the order the screen shows them. */
    private val CHOICES = listOf("line", "long", "pair", "quiet", "asked", "bye")

    /** What each answer gets: every kind of part. */
    private val ANSWERS = mapOf(
        // A line with a detail, whose arguments are a lang key and a number
        "line" to listOf(Line("gossip_6~talk.test.prices|item.minecraft.bread|3", 36)),
        // A detail long enough to wrap and roll the lines up
        "long" to listOf(Line("gossip_9~talk.test.long", 48)),
        // Two voiced parts joined, the second with a name in its detail
        "pair" to listOf(Line("gossip_2", 40), Line("gossip_7~talk.test.cousin|=Tam Miller", 38)),
        // A voiced part, then one with no take: the pen ticks for it
        "quiet" to listOf(Line("reply_4", 25), Line("talk.test.unrecorded_0~talk.test.unrecorded", 0)),
        "asked" to listOf(Line("reply_0~talk.test.asked", 25)),
    )
    private val BYE = listOf(Line("greet_5", 30))
    private val IDLE_WORDS = listOf(Line("reply_1~talk.test.idle", 18))
    private val UNKNOWN = listOf(Line("reply_2~talk.test.unknown", 22))

    /** Registers the command and the test talks' ticking; called once from Townsfolk.init. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (tests.isNotEmpty()) tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            tests.remove(player.uuid)?.let { release(it, player) }
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> tests.clear() }
    }

    /** `/guildmark talk test`, and its words for how the plate shows they know the player. */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val test = Commands.literal("test").executes { start(it.source, TalkOpenPayload.KNOWS) }
        for ((word, knows) in listOf(
            "stranger" to TalkOpenPayload.STRANGER, "heard" to TalkOpenPayload.HEARD_OF,
            "knows" to TalkOpenPayload.KNOWS, "wary" to TalkOpenPayload.WARY,
        )) test.then(Commands.literal(word).executes { start(it.source, knows) })
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("talk").then(test)),
        )
    }

    /** Whether townsfolk talk is switched on. */
    private fun talkOn(): Boolean = !GuildmarkConfig.SPEC.isLoaded || GuildmarkConfig.TOWNSFOLK_TALK.get()

    // ---- From the talk screen ----

    /** [player]'s pick, if they have a test talk open (true: it's the test's, answered or let go). */
    fun picked(player: ServerPlayer, entity: Int, serial: Int, choice: String): Boolean {
        val test = tests[player.uuid] ?: return false
        // A pick for an old say, a doubled click, or after the last words: let go
        if (test.over >= 0 || entity != test.speaker || serial != test.serial) return true
        val now = player.level().server.tickCount
        test.lastPick = now
        if (choice == "bye") {
            say(player, test, BYE, true, now)
            return true
        }
        val lines = ANSWERS[choice]
        if (lines != null) test.asked += choice
        say(player, test, lines ?: UNKNOWN, false, now)
        return true
    }

    /** [player]'s talk screen with [entity] closed: true if it was their test talk's, which is over. */
    fun ended(player: ServerPlayer, entity: Int): Boolean {
        val test = tests[player.uuid] ?: return false
        // Another screen's end (a talk from before this test began): not the test's, which goes on
        if (entity != test.speaker) return false
        tests.remove(player.uuid)
        release(test, player)
        return true
    }

    // ---- The talk ----

    /** Opens a test talk for the player running the command, the plate showing [knows]; an old one of theirs ends. */
    private fun start(source: CommandSourceStack, knows: Int): Int {
        val player = source.playerOrException
        if (!talkOn()) {
            source.sendFailure(Component.literal("Townsfolk talk is switched off (townsfolkTalk in the server's config)."))
            return 0
        }
        tests.remove(player.uuid)?.let { release(it, player) }
        val level = player.level()
        val now = level.server.tickCount
        val speaker = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(REACH)) {
            it.isAlive && !it.isLeader && it.distanceToSqr(player) <= REACH * REACH
        }.minByOrNull { it.distanceToSqr(player) }
        val test = Test(player.uuid, speaker?.id ?: -1, now)
        test.asked += "asked"
        tests[player.uuid] = test
        val name: Component
        val epithet: Component
        if (speaker != null) {
            name = speaker.name
            val role = Component.translatable("entity.guildmark.townsperson.${speaker.role.id}")
            val capital = Capitals.covering(level, speaker.blockPosition())
            epithet = if (capital == null) role else Component.translatable("gui.guildmark.talk.test.epithet", role, VillageNames.of(capital.boardAnchor))
            speaker.attend(player, ATTEND)
        } else {
            name = Component.translatable("gui.guildmark.talk.test.nobody")
            epithet = Component.translatable("gui.guildmark.talk.test.alone")
        }
        PacketDistributor.sendToPlayer(player, TalkOpenPayload(test.speaker, name, epithet, knows))
        say(player, test, listOf(Line("greet_6~talk.test.opener|=${player.name.string}", 37)), false, now)
        val with = if (speaker != null) "with ${speaker.name.string}" else "with nobody (no townsperson within ${REACH.toInt()} blocks)"
        source.sendSuccess({ Component.literal("A test talk $with. Goodbye or Escape ends it.") }, false)
        return 1
    }

    /** The townsperson says [lines]: the screen gets them all at once, and they're said aloud one by one, in step. */
    private fun say(player: ServerPlayer, test: Test, lines: List<Line>, closing: Boolean, now: Int) {
        test.serial++
        test.aloud.clear()
        var at = now
        for (line in lines) {
            if (line.ticks > 0) test.aloud.addLast(at to line)
            at += line.ticks + PART_GAP
        }
        val choices = if (closing) emptyList() else CHOICES.map { TalkChoice(it, Component.translatable("gui.guildmark.talk.test.$it"), it in test.asked) }
        PacketDistributor.sendToPlayer(player, TalkSayPayload(test.speaker, test.serial, lines.map { TalkPart.of(it.speech, it.ticks) }, choices, closing))
        if (closing) test.over = at + LINGER
        speaker(player, test)?.let { aloud(test, it, now) }
    }

    /** The townsperson saying [test], while they're about; null for nobody. */
    private fun speaker(player: ServerPlayer, test: Test): Townsperson? =
        if (test.speaker < 0) null else player.level().getEntity(test.speaker) as? Townsperson

    /** The parts due now, said aloud through the townsperson's own voice. */
    private fun aloud(test: Test, speaker: Townsperson?, now: Int) {
        while (test.aloud.isNotEmpty() && test.aloud.first().first <= now) {
            val line = test.aloud.removeFirst().second
            speaker?.say(line.speech, force = true, hearing = HEARING, ticks = line.ticks + 10L)
        }
    }

    /** The townsperson goes back to what they were doing. */
    private fun release(test: Test, player: ServerPlayer) {
        speaker(player, test)?.stopListening(player)
    }

    /** Says each part aloud in its turn, keeps the townsperson turned to the player, and ends the tests that are over. */
    private fun tick(server: MinecraftServer) {
        val now = server.tickCount
        val each = tests.values.iterator()
        while (each.hasNext()) {
            val test = each.next()
            val player = server.playerList.getPlayer(test.player)
            if (player == null) {
                each.remove()
                continue
            }
            val speaker = speaker(player, test)
            aloud(test, speaker, now)
            if (test.over >= 0) {
                if (now >= test.over) {
                    release(test, player)
                    each.remove()
                }
                continue
            }
            val lost = test.speaker >= 0 && (speaker == null || !speaker.isAlive || speaker.distanceToSqr(player) > REACH * REACH)
            if (!talkOn() || !player.isAlive || lost || now - test.started >= LONGEST) {
                PacketDistributor.sendToPlayer(player, TalkClosePayload(test.speaker))
                release(test, player)
                each.remove()
                continue
            }
            if (now - test.lastPick >= IDLE) {
                say(player, test, IDLE_WORDS, true, now)
                continue
            }
            if (speaker != null && (now - test.started) % 20 == 0) speaker.attend(player, ATTEND)
        }
    }
}
