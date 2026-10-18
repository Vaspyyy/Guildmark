package io.github.vaspyyy.guildmark.homestead

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.homestead.plan.PlotShape
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level

/**
 * Testing commands (operators only):
 *
 *     /guildmark homestead charter     a charter in hand
 *     /guildmark homestead here        stakes a homestead where you stand, the plot reaching the way you face
 *                                      (as the charter would; "here force" skips the survey)
 *     /guildmark homestead tier <n>    builds it up to tier n at once (or just calls it tier n, going down)
 *     /guildmark homestead finish      finishes the tier going up at once
 *     /guildmark homestead supply      fills the supply crate with what's still to come of the build
 *     /guildmark homestead rush        the crew works ten times as fast for the rest of this tier
 *     /guildmark homestead info        how the homestead you're on (or yours) stands
 *     /guildmark homestead stranger    the land treats you as a stranger (again to stop)
 *     /guildmark homestead remove      gives up the homestead you're on (or yours)
 */
object HomesteadCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("homestead")
                        .then(Commands.literal("charter").executes { charter(it) })
                        .then(Commands.literal("here").executes { here(it, false) }.then(Commands.literal("force").executes { here(it, true) }))
                        .then(Commands.literal("tier").then(Commands.argument("n", IntegerArgumentType.integer(0, PlotShape.TOP_TIER)).executes { tier(it) }))
                        .then(Commands.literal("finish").executes { finish(it) })
                        .then(Commands.literal("supply").executes { supply(it) })
                        .then(Commands.literal("rush").executes { rush(it) })
                        .then(Commands.literal("info").executes { info(it) })
                        .then(Commands.literal("stranger").executes { stranger(it) })
                        .then(Commands.literal("remove").executes { remove(it) }),
                ),
        )
    }

    private fun overworld(source: CommandSourceStack): ServerLevel? {
        if (source.level.dimension() == Level.OVERWORLD) return source.level
        source.sendFailure(Component.translatable("commands.guildmark.homestead.overworld"))
        return null
    }

    /** The homestead [player] stands on, or else their own. */
    private fun target(source: CommandSourceStack, level: ServerLevel, player: ServerPlayer): Homestead? {
        val h = Homesteads.at(level, player.blockPosition()) ?: Homesteads.of(level, player.uuid)
        if (h == null) source.sendFailure(Component.translatable("commands.guildmark.homestead.none"))
        return h
    }

    private fun charter(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val stack = ItemStack(HomesteadRegistry.CHARTER.get())
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        context.source.sendSuccess({ Component.translatable("commands.guildmark.homestead.charter") }, false)
        return 1
    }

    private fun here(context: CommandContext<CommandSourceStack>, force: Boolean): Int {
        val source = context.source
        val player = source.playerOrException
        val level = overworld(source) ?: return 0
        val post = player.blockPosition()
        // Forced: whatever the land, and any homestead of theirs given up first
        if (force) Homesteads.of(level, player.uuid)?.let { HomesteadWorks.remove(level, it) }
        val verdict = if (force) forceClaim(level, player) else HomesteadClaims.claim(level, player, post, player.direction)
        if (!verdict.ok) {
            source.sendFailure(verdict.reason)
            return 0
        }
        val h = Homesteads.of(level, player.uuid) ?: return 0
        source.sendSuccess({ Component.translatable("commands.guildmark.homestead.here", h.post.x, h.post.y, h.post.z) }, true)
        return 1
    }

    private fun forceClaim(level: ServerLevel, player: ServerPlayer): HomesteadClaims.Verdict {
        val frame = PlotFrame(player.blockPosition(), player.direction)
        val land = HomesteadClaims.read(level, frame)
        return HomesteadClaims.claimOn(level, player, player.blockPosition(), player.direction, land)
    }

    private fun tier(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = overworld(source) ?: return 0
        val h = target(source, level, player) ?: return 0
        val n = IntegerArgumentType.getInteger(context, "n")
        if (n <= h.tier) {
            // Going down only renames it: nothing comes down
            BuilderCrew.dismiss(h.id)
            val from = h.tier
            h.tier = n
            h.build = Build(n)
            Homesteads.get(level).changed()
            Homesteads.get(level).placesChanged(h)
            HomesteadEvent.post(HomesteadEvent.TierChanged(level, h, from, n))
            HomesteadNet.syncAll(level.server)
        } else {
            while (h.tier < n) {
                if (!h.building) HomesteadWorks.begin(level, h, h.tier + 1)
                HomesteadWorks.finishNow(level, h)
            }
        }
        source.sendSuccess({ Component.translatable("commands.guildmark.homestead.tier", n) }, true)
        return 1
    }

    private fun finish(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = overworld(source) ?: return 0
        val h = target(source, level, player) ?: return 0
        if (!h.building) {
            source.sendFailure(Component.translatable("commands.guildmark.homestead.not_building"))
            return 0
        }
        HomesteadWorks.finishNow(level, h)
        source.sendSuccess({ Component.translatable("commands.guildmark.homestead.finished", h.tier) }, true)
        return 1
    }

    private fun supply(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = overworld(source) ?: return 0
        val h = target(source, level, player) ?: return 0
        val crate = level.getBlockEntity(h.crate) as? GoodsCrateBlockEntity
        if (crate == null) {
            source.sendFailure(Component.translatable("commands.guildmark.homestead.no_crate"))
            return 0
        }
        val site = HomesteadWorks.site(level, h)
        val need = site?.left() ?: HomesteadTiers.of(h.tier + 1)?.bill ?: IntArray(Stuff.PAID.size)
        val items = listOf(Items.OAK_PLANKS, Items.COBBLESTONE, Items.GLASS, Items.IRON_INGOT, Items.WOOL.pick(DyeColor.WHITE))
        for ((s, item) in items.withIndex()) {
            var left = need[s] - Materials.count(crate)[s]
            while (left > 0) {
                val n = minOf(64, left)
                val stack = ItemStack(item, n)
                val slot = (0 until crate.containerSize).firstOrNull { crate.getItem(it).isEmpty } ?: break
                crate.setItem(slot, stack)
                left -= n
            }
        }
        crate.setChanged()
        site?.survey()
        source.sendSuccess({ Component.translatable("commands.guildmark.homestead.supplied") }, true)
        return 1
    }

    private fun rush(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = overworld(source) ?: return 0
        val h = target(source, level, player) ?: return 0
        val site = HomesteadWorks.site(level, h)
        if (site == null) {
            source.sendFailure(Component.translatable("commands.guildmark.homestead.not_building"))
            return 0
        }
        site.rushed = !site.rushed
        source.sendSuccess({ Component.translatable(if (site.rushed) "commands.guildmark.homestead.rush" else "commands.guildmark.homestead.unrush") }, true)
        return 1
    }

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = overworld(source) ?: return 0
        val h = target(source, level, player) ?: return 0
        val site = HomesteadWorks.site(level, h)
        val tier = Component.translatable("gui.guildmark.homestead.tier.${HomesteadTiers.key(h.tier)}")
        source.sendSuccess({
            Component.translatable("commands.guildmark.homestead.info", h.ownerName, tier, h.post.x, h.post.y, h.post.z, h.facing.serializedName)
        }, false)
        if (site != null) {
            val stage = Component.translatable("gui.guildmark.homestead.stage.${site.stage.key}")
            val crew = BuilderCrew.builders(h.id).size
            val left = site.left().joinToString(" ") { it.toString() }
            source.sendSuccess({
                Component.translatable(
                    "commands.guildmark.homestead.info_build", site.tier.key, stage, site.laid, site.total, String.format("%.1f", site.build.work),
                    site.build.reach, crew, left, site.short?.key ?: "-", site.blocked?.toShortString() ?: "-",
                )
            }, false)
        }
        return 1
    }

    private fun stranger(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val now = if (!HomesteadProtection.strangers.remove(player.uuid)) {
            HomesteadProtection.strangers.add(player.uuid)
            true
        } else false
        context.source.sendSuccess({ Component.translatable(if (now) "commands.guildmark.homestead.stranger_on" else "commands.guildmark.homestead.stranger_off") }, false)
        return 1
    }

    private fun remove(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = overworld(source) ?: return 0
        val h = target(source, level, player) ?: return 0
        HomesteadWorks.remove(level, h)
        source.sendSuccess({ Component.translatable("commands.guildmark.homestead.removed", h.ownerName) }, true)
        return 1
    }
}
