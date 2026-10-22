package io.github.vaspyyy.guildmark.cooking.town

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark cook scene tavern|peel|slice|roast` (operators only; living-world/cook/design.md 7.3, 7.5, 10.3): plays that kitchen
 * scene now on the nearest keeper of the right trade within [SIGHT] blocks of where the command is run (the innkeeper for `tavern`
 * and `roast`, the baker for `peel` and `slice`), whatever the clock and the cool-down say. It says why when it cannot: Town
 * Kitchens (or, for the roast, the Rest-day Roast rule) off, no keeper near, a keeper who is asleep, hurt, talking or under another
 * director, no player within 40 blocks, no pot, Spit, oven or counter near the keeper, an oven with a player's tray in it.
 * `/guildmark cook scene stop` hands every keeper in a scene back to their day at once. The scenes are those of [KitchenScenes].
 */
internal object SceneCommand {
    /** How far from the one who runs the command the keeper may be (blocks). */
    private const val SIGHT = 32.0

    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(scene()),
                ),
        )
    }

    private fun scene(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("scene")
        .then(play("tavern", KitchenSceneKind.TAVERN))
        .then(play("peel", KitchenSceneKind.PEEL))
        .then(play("slice", KitchenSceneKind.SLICE))
        .then(play("roast", KitchenSceneKind.ROAST))
        .then(Commands.literal("stop").executes { stop(it) })

    private fun play(word: String, kind: KitchenSceneKind): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(word).executes { run(it, kind) }

    private fun run(context: CommandContext<CommandSourceStack>, kind: KitchenSceneKind): Int {
        val source = context.source
        val level = source.level
        val role = if (kind == KitchenSceneKind.TAVERN || kind == KitchenSceneKind.ROAST) TownRole.INNKEEPER else TownRole.BAKER
        val noun = if (role == TownRole.INNKEEPER) "innkeeper" else "baker"
        val at = source.position
        val keeper = level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(at, SIGHT * 2, 32.0, SIGHT * 2)) { it.role == role && it.isAlive }
            .filter { it.distanceToSqr(at) <= SIGHT * SIGHT }
            .minByOrNull { it.distanceToSqr(at) }
            ?: return fail(source, "No $noun within ${SIGHT.toInt()} blocks of you.")
        val why = KitchenScenes.attempt(level, keeper, kind)
        if (why != null) return fail(source, why)
        source.sendSuccess({ Component.literal(started(kind, noun, keeper)).withStyle(ChatFormatting.GREEN) }, false)
        return 1
    }

    private fun started(kind: KitchenSceneKind, noun: String, keeper: Townsperson): String {
        val where = "at ${keeper.blockPosition().x} ${keeper.blockPosition().y} ${keeper.blockPosition().z}"
        return when (kind) {
            KitchenSceneKind.TAVERN -> "The $noun $where goes to the house pot."
            KitchenSceneKind.PEEL -> "The $noun $where goes to draw a tray from the Bake oven."
            KitchenSceneKind.SLICE -> "The $noun $where goes to the counter to slice a loaf."
            KitchenSceneKind.ROAST -> "The $noun $where goes to turn the Spit."
        }
    }

    private fun stop(context: CommandContext<CommandSourceStack>): Int {
        val n = KitchenScenes.stopAll()
        context.source.sendSuccess({ Component.literal(if (n == 0) "No kitchen scene is running." else "Handed $n keeper${if (n == 1) "" else "s"} back to their day.").withStyle(ChatFormatting.GRAY) }, false)
        return n
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
