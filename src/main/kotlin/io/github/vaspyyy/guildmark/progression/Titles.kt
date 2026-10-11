package io.github.vaspyyy.guildmark.progression

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.event.entity.player.PlayerEvent

/**
 * Titles a player earns from milestones and can wear after their name ("Steve, Lairbreaker"), in chat,
 * the tab list and over their head. `/title` lists the ones earned; each milestone that grants one says so.
 */
enum class Title(val id: String, val milestone: String) {
    HIRELING("hireling", Milestones.PAID),
    COURIER("courier", Milestones.COURIER),
    BODYGUARD("bodyguard", Milestones.BODYGUARD),
    GIANTSBANE("giantsbane", Milestones.CHAMPION),
    BUILDER("builder", Milestones.ADVANCE_BUILT),
    ROADWRIGHT("roadwright", Milestones.TRADE_ROAD),
    CITYFATHER("cityfather", Milestones.CITY),
    LAIRBREAKER("lairbreaker", Milestones.LAIR),
    SIEGEBREAKER("siegebreaker", Milestones.SIEGE),
    RELIC_HUNTER("relic_hunter", Milestones.RELIC),
    LEGEND_BEARER("legend_bearer", Milestones.LEGENDARY),
    HERO("hero", Milestones.HERO),
    GUILDMASTER("guildmaster", Milestones.GUILD_FOUNDED),
    BANNER_BEARER("banner_bearer", Milestones.BANNER),
    USURPER("usurper", Milestones.TAKEOVER),
    STALWART("stalwart", Milestones.REPELLED),
    S_RANK("s_rank", "rank_s"),
    SHARD_SEEKER("shard_seeker", Milestones.FIRST_SHARD),
    WARDEN("warden", Milestones.HOLLOW_KING);

    val label: Component get() = Component.translatable("title.guildmark.$id")

    companion object {
        fun byId(id: String): Title? = entries.firstOrNull { it.id == id }
        fun forMilestone(milestone: String): Title? = entries.firstOrNull { it.milestone == milestone }
    }
}

object Titles {
    fun earned(player: Player): List<Title> = Title.entries.filter { Milestones.done(player, it.milestone) }

    /** Wear [title], or take the current one off with null. */
    fun wear(player: ServerPlayer, title: Title?) {
        player.setData(ModAttachments.TITLE, title?.id ?: "")
        player.refreshDisplayName()
        player.refreshTabListName()
    }

    /** A milestone just came in: if it grants a title, offer it. */
    fun onMilestone(player: ServerPlayer, milestone: String) {
        val title = Title.forMilestone(milestone) ?: return
        player.sendSystemMessage(Component.translatable("message.guildmark.title.earned", title.label.copy().withStyle(ChatFormatting.GOLD), wearButton(title)))
    }

    private fun wearButton(title: Title): Component = Component.translatable("message.guildmark.title.wear_button").withStyle {
        it.withColor(0x7FD87F).withUnderlined(true)
            .withClickEvent(ClickEvent.RunCommand("/title wear ${title.id}"))
            .withHoverEvent(HoverEvent.ShowText(Component.translatable("message.guildmark.title.wear_hover", title.label)))
    }

    fun list(player: ServerPlayer) {
        val earned = earned(player)
        if (earned.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.guildmark.title.none"))
            return
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.title.header", earned.size, Title.entries.size).withStyle { it.withColor(0xE8C26A) })
        val worn = player.getData(ModAttachments.TITLE)
        for (title in earned) {
            val line = if (title.id == worn) Component.translatable("message.guildmark.title.worn", title.label.copy().withStyle(ChatFormatting.GOLD))
            else Component.translatable("message.guildmark.title.line", title.label, wearButton(title))
            player.sendSystemMessage(line)
        }
        if (worn.isNotEmpty()) {
            player.sendSystemMessage(Component.translatable("message.guildmark.title.remove_button").withStyle {
                it.withColor(0xB0A080).withUnderlined(true).withClickEvent(ClickEvent.RunCommand("/title clear"))
            })
        }
    }

    /** Both sides: "Name, Title" wherever the game shows the player's name. */
    fun onNameFormat(event: PlayerEvent.NameFormat) {
        val title = Title.byId(event.entity.getData(ModAttachments.TITLE)) ?: return
        event.displayname = Component.translatable("title.guildmark.format", event.displayname, title.label.copy().withStyle(ChatFormatting.GOLD))
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("title")
                .executes { context ->
                    list(context.source.playerOrException)
                    1
                }
                .then(Commands.literal("wear").then(Commands.argument("title", StringArgumentType.word()).executes { context ->
                    val player = context.source.playerOrException
                    val title = Title.byId(StringArgumentType.getString(context, "title"))
                    if (title == null || title !in earned(player)) {
                        player.sendSystemMessage(Component.translatable("message.guildmark.title.locked"))
                        return@executes 0
                    }
                    wear(player, title)
                    player.sendSystemMessage(Component.translatable("message.guildmark.title.now", player.displayName))
                    1
                }))
                .then(Commands.literal("clear").executes { context ->
                    val player = context.source.playerOrException
                    wear(player, null)
                    player.sendSystemMessage(Component.translatable("message.guildmark.title.cleared"))
                    1
                })
        )
    }
}
