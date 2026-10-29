package io.github.vaspyyy.guildmark.cooking

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * `/guildmark cook anim <clip> [loop]` (living-world/cook/design.md 10.3, Part E; operators only): plays one of the seventeen
 * clips of 11.3 on you, sent as the real action is, so everyone who sees you sees it too. Aimed at a block within reach, the
 * body turns to it as it would to a station; aimed at nothing, it plays where you stand. `loop` sends it again as each one
 * nears its end until `cook anim stop`, a log-out or the server's stop. Brigadier joins this branch to the root that
 * [CookCommand] makes, so neither edits the other.
 */
object CookAnimCommand {
    /** A clip sent again and again for [player]: [action] at [pos], next at server tick [next]. */
    private class Loop(val action: CookAction, val pos: BlockPos, var next: Int)

    private val loops = HashMap<UUID, Loop>()

    /** The seventeen clips' names, as `cook anim` takes them. */
    private val NAMES: List<String> = CookAction.entries.map { CookActions.clip(it) }

    /** How many players have a clip looping now (for `cook perf`). */
    fun looping(): Int = loops.size

    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { build(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { loops.remove(it.entity.uuid) }
        Cooking.onForget { loops.clear() }
    }

    private fun build(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("anim")
                                .then(Commands.literal("stop").executes { stop(it) })
                                .then(
                                    Commands.argument("clip", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(NAMES, builder) }
                                        .executes { play(it, false) }
                                        .then(Commands.literal("loop").executes { play(it, true) }),
                                ),
                        ),
                ),
        )
    }

    private fun play(context: CommandContext<CommandSourceStack>, loop: Boolean): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player can be shown cooking.")
        if (!Cooking.enabled()) return fail(source, "Hearthcraft is off, so no cook clip plays.")
        val name = StringArgumentType.getString(context, "clip").lowercase()
        val action = CookActions.byClip(name) ?: return fail(source, "No cook clip is called $name. The clips: ${NAMES.joinToString(", ")}.")
        val pos = aimed(player)
        val now = player.level().server.tickCount
        if (loop) loops[player.uuid] = Loop(action, pos, now + every(action)) else loops.remove(player.uuid)
        CookActions.play(player, action, pos)
        val where = if (pos == player.blockPosition()) "where you stand" else "at the block you look at (${pos.x}, ${pos.y}, ${pos.z})"
        say(
            source,
            Component.literal(
                if (loop) "Looping $name $where, for you and everyone who sees you; `cook anim stop` ends it."
                else "Playing $name $where, for you and everyone who sees you (${CookActions.length(action)} ticks).",
            ).withStyle(ChatFormatting.YELLOW),
        )
        return 1
    }

    private fun stop(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player can be shown cooking.")
        val was = loops.remove(player.uuid) ?: return fail(source, "No cook clip is looping on you.")
        say(source, Component.literal("Stopped looping ${CookActions.clip(was.action)}: the one under way plays out.").withStyle(ChatFormatting.YELLOW))
        return 1
    }

    /** The block [player] looks at within reach, or where they stand (the body then plays the clip without turning). */
    private fun aimed(player: ServerPlayer): BlockPos {
        val hit = player.pick(player.blockInteractionRange(), 1.0f, false)
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) return hit.blockPos.immutable()
        return player.blockPosition()
    }

    /** Ticks between two sends of a looping [action]: as each nears its end, so the next follows on (the shiver's every 40, as Part F sends it). */
    private fun every(action: CookAction): Int =
        if (action == CookAction.SHIVER) CookActions.length(action) else maxOf(4, CookActions.length(action) - 3)

    /** Sends each loop that is due; nothing to do (and nothing looked at) while none runs. */
    private fun tick(server: MinecraftServer) {
        if (loops.isEmpty()) return
        val now = server.tickCount
        val gone = ArrayList<UUID>(0)
        for ((id, loop) in loops) {
            if (now < loop.next) continue
            val player = server.playerList.getPlayer(id)
            if (player == null || !Cooking.enabled()) {
                gone.add(id)
                continue
            }
            loop.next = now + every(loop.action)
            CookActions.play(player, loop.action, loop.pos)
        }
        for (id in gone) loops.remove(id)
    }

    private fun say(source: CommandSourceStack, text: Component): Int {
        source.sendSuccess({ text }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, text: String): Int {
        source.sendFailure(Component.literal(text))
        return 0
    }
}
