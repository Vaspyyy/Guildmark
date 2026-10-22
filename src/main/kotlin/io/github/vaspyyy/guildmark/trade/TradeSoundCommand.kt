package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.hypot

/**
 * `/guildmark trade sound [name] [blocks]` (trader design 12.15, the sounds; operators only): plays the trade's sounds at you,
 * to hear them. `sound <name>` is one (`bell_step`, `trade.hunt_horn`: with or without the `trade.`; tab completion lists them),
 * and `sound` alone every one of the 37 in turn, two seconds apart, with its name in chat as it plays, so that a whole sitting
 * can be listened through; `sound stop` ends a run (a new one, named or not, ends the player's last). A named sound with a
 * number after it plays that many blocks ahead of you instead of at your feet: `sound hunt_horn 70` is the horn from the far side
 * of a valley (it carries 80), `sound whistle_come 40` a whistle that is out of range.
 *
 * Each sound plays as the code that plays it does, at the volume it plays it at (the bell on the pack animal at 0.55 and 0.7, the
 * clink of a laden back at 0.3, the keeper's stamp, seal, knocks, crate and peeling ticket at 0.7), at pitch 1; the sound event picks one of the sound's files at random, so a second run is another
 * take (the three of `bell_step` and `hunt_shout`, the four of `knock`). The cart's creak is the loop, played through once. In a run
 * a few sounds are given a longer wait than two seconds (the horn, the bell, the cart's loop and the hoist last longer than that).
 * `hoist_creak` is the granary's own hoist. Nothing is kept: it changes nothing in the world and the one run in hand; the sounds
 * are heard by whoever is in range of where they play, as the game's own are.
 *
 * [Trade.register] calls [init]. The `trade` literal is the one [TradeCommand] has, with the same permission, so Brigadier
 * merges them by name and neither file edits the other.
 */
object TradeSoundCommand {
    /** The wait after each sound of a run, in ticks: two seconds. */
    private const val GAP = 40L

    /** The sounds that last past two seconds, and how long a run waits after them, in ticks. */
    private val LONGER: Map<String, Long> = mapOf(
        "trade.cart_creak" to 190L, "trade.hunt_horn" to 120L, "trade.house_bell" to 80L, "trade.hunt_win" to 60L,
        "trade.hoist_creak" to 80L,
    )

    /** The volumes the code that exists plays a sound at, where it is not 1: what each file is levelled for. */
    private val PLAYED_AT: Map<String, Float> = mapOf(
        "trade.bell_step" to 0.55f, "trade.bell_trot" to 0.7f, "trade.pack_clink" to 0.3f,
        "trade.board_stamp" to 0.7f, "trade.seal_press" to 0.7f, "trade.knock" to 0.7f, "trade.deliver" to 0.7f, "trade.ticket_peel" to 0.7f,
    )

    /** One run: every sound of [names] in turn for [player], the next at tick [due] of the server, [blocks] ahead of them. */
    private class Run(val player: UUID, val names: List<String>, var next: Int, var due: Long, val blocks: Int)

    private val runs = HashMap<UUID, Run>()

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> runs.clear() }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("trade")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("sound")
                                .executes { sound(it, null, 0) }
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("stop") + TradeSounds.NAMES.map { it.removePrefix("trade.") }, builder) }
                                        .executes { sound(it, StringArgumentType.getString(it, "name"), 0) }
                                        .then(
                                            Commands.argument("blocks", IntegerArgumentType.integer(0, 128))
                                                .executes { sound(it, StringArgumentType.getString(it, "name"), IntegerArgumentType.getInteger(it, "blocks")) },
                                        ),
                                ),
                        ),
                ),
        )
    }

    private fun sound(context: CommandContext<CommandSourceStack>, name: String?, blocks: Int): Int {
        val source = context.source
        val player = source.playerOrException
        if (name == "stop") {
            return if (runs.remove(player.uuid) != null) tell(source, "Stopped.") else fail(source, "Nothing is playing.")
        }
        runs.remove(player.uuid)
        if (name == null) {
            val seconds = TradeSounds.NAMES.sumOf { LONGER[it] ?: GAP } / 20
            runs[player.uuid] = Run(player.uuid, TradeSounds.NAMES, 0, source.server.tickCount.toLong(), 0)
            return tell(source, "All ${TradeSounds.NAMES.size} trade sounds in turn, about $seconds seconds; the name of each in chat as it plays. /guildmark trade sound stop ends it.", TradeSounds.NAMES.size)
        }
        val full = if (name.startsWith("trade.")) name else "trade.$name"
        if (full !in TradeSounds.NAMES) {
            return fail(source, "No trade sound is called $name. Tab completion lists the ${TradeSounds.NAMES.size}.")
        }
        play(player, full, blocks)
        return 1
    }

    /** The next sound of each run when it is due; nothing to look at while there is none. */
    private fun tick(server: MinecraftServer) {
        if (runs.isEmpty()) return
        val now = server.tickCount.toLong()
        val each = runs.values.iterator()
        while (each.hasNext()) {
            val r = each.next()
            if (now < r.due) continue
            val player = server.playerList.getPlayer(r.player)
            if (player == null) {
                each.remove()
                continue
            }
            if (r.next >= r.names.size) {
                each.remove()
                player.sendSystemMessage(Component.literal("Done: ${r.names.size} sounds.").withStyle(ChatFormatting.GRAY))
                continue
            }
            val name = r.names[r.next++]
            play(player, name, r.blocks)
            r.due = now + (LONGER[name] ?: GAP)
        }
    }

    /** [name] at [player], or [blocks] ahead of them along the way they face, at the volume the game plays it at; its name in chat. */
    private fun play(player: ServerPlayer, name: String, blocks: Int) {
        val look = player.lookAngle
        val h = hypot(look.x, look.z)
        val dx = if (h < 1.0e-3) 0.0 else look.x / h
        val dz = if (h < 1.0e-3) 1.0 else look.z / h
        val volume = PLAYED_AT[name] ?: 1.0f
        player.level().playSound(null, player.x + dx * blocks, player.y + 1.0, player.z + dz * blocks, TradeSounds.get(name), SoundSource.NEUTRAL, volume, 1.0f)
        val reach = TradeSounds.RANGES[name] ?: 16f
        val how = (if (volume != 1.0f) ", at volume $volume" else "") + (if (blocks > 0) ", $blocks blocks ahead" else "")
        player.sendSystemMessage(
            Component.literal(name).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" (carries ${reach.toInt()} blocks$how)").withStyle(ChatFormatting.GRAY)),
        )
    }

    private fun tell(source: CommandSourceStack, message: String, result: Int = 1): Int {
        source.sendSuccess({ Component.literal(message).withStyle(ChatFormatting.GRAY) }, false)
        return result
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
