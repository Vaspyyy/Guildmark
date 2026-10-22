package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.network.TaleBannerPayload
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The following's commands under `/guildmark tale` (living-world/stories/design.md 14.8, Part D), game masters only, like the
 * rest of `tale`:
 * - `tale rows [player]` shows what a player's tracker holds against what the record says it should hold, and how many times it
 *   has been sent since they joined (once for each change);
 * - `tale banner <begin|step|good|ill> [player]` plays one of the four banners now on a player's screen, with its sound, in the
 *   words of the tale they follow (the probe's if they follow none).
 *
 * `rows` only reads: nothing is written to any tale. `banner` writes nothing at all, and sends the one banner. With tales not in
 * the build either answers in one grey line.
 */
internal object TaleFollowCommands {
    /** Appends the following's subcommands to the `tale` command (one line of `Tales.register` calls this through [TaleRows.register]). */
    fun add() {
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("rows")
                    .executes { rows(it, it.source.playerOrException) }
                    .then(Commands.argument("player", EntityArgument.player()).executes { rows(it, EntityArgument.getPlayer(it, "player")) }),
            )
            val banner = Commands.literal("banner")
            for ((word, kind) in KINDS) {
                banner.then(
                    Commands.literal(word)
                        .executes { banner(it, kind, it.source.playerOrException) }
                        .then(Commands.argument("player", EntityArgument.player()).executes { banner(it, kind, EntityArgument.getPlayer(it, "player")) }),
                )
            }
            tale.then(banner)
        }
    }

    private fun say(source: CommandSourceStack, c: Component): Int = TaleCommand.say(source, c)

    private fun labelled(label: String, text: String): MutableComponent = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    /** True, and says so, while tales are not in this build: a command then answers in one grey line and touches nothing. */
    private fun notReady(source: CommandSourceStack): Boolean {
        if (Tales.READY) return false
        say(source, TaleCommand.grey(TaleCommand.off() ?: "Tales are not ready in this build."))
        return true
    }

    // ---- tale rows ----

    /** `tale rows [player]`: the rows [player]'s tracker holds, against the rows the record would give them now, and how often they were sent. */
    private fun rows(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val source = context.source
        if (notReady(source)) return 1
        TaleCommand.off()?.let { say(source, TaleCommand.grey(it)) }
        val held = player.getData(ModAttachments.TALES)
        say(source, TaleCommand.header("Tale rows of ${player.name.string}"))
        say(source, labelled("On the tracker", if (held.size == 1) "1 row" else "${held.size} rows"))
        val times = TaleRows.sentTo(player)
        say(source, labelled("Sent since they joined", if (times == 1) "once" else "$times times") .append(TaleCommand.grey("  (once for each change)")))
        for (r in held) say(source, describe(r))
        if (!Tales.live) {
            say(source, TaleCommand.grey("Tales are off, so no row is built and none is kept."))
            return 1
        }
        val fresh = TaleRows.build(source.level.server.overworld(), player)
        if (fresh == held) {
            say(source, TaleCommand.grey("The tracker holds what the record says."))
        } else {
            say(source, TaleCommand.grey("The record says otherwise; within two seconds the tracker will hold:"))
            for (r in fresh) say(source, describe(r))
            if (fresh.isEmpty()) say(source, TaleCommand.grey("  no rows: ${player.name.string} is not IN a tale."))
        }
        return 1
    }

    /** One row on one line: the tale, its words as the viewer's own language has them, the ink, the days left and the place. */
    private fun describe(r: TaleRow): MutableComponent {
        val ink = when (r.tone) {
            TaleRow.RED -> "red"
            TaleRow.GREEN -> "green"
            else -> "amber"
        }
        val place = r.place?.let { "at ${it.x} ${it.y} ${it.z}" } ?: "no place"
        return TaleCommand.line()
            .append(TaleCommand.label("  #${r.id} ${r.template}, step ${r.step}: "))
            .append(Component.translatable(r.title, *r.titleArgs.toTypedArray()).withStyle(ChatFormatting.WHITE))
            .append(TaleCommand.grey(" / "))
            .append(Component.translatable(r.status, *r.statusArgs.toTypedArray()).withStyle(ChatFormatting.WHITE))
            .append(TaleCommand.grey("  ($ink, ${r.days} day${if (r.days == 1) "" else "s"} left, $place)"))
    }

    // ---- tale banner ----

    /** The four banners by the word the command takes. */
    private val KINDS: List<Pair<String, Int>> = listOf(
        "begin" to TaleBannerPayload.BEGIN, "step" to TaleBannerPayload.STEP, "good" to TaleBannerPayload.GOOD, "ill" to TaleBannerPayload.ILL,
    )

    /** The step of the probe a `step` banner tells when the player follows no tale. */
    private const val PROBE_STEP = 2

    /**
     * `tale banner <kind> [player]`: sends [player] one banner, marked as a test, so that it shows (and says why not, if their
     * `taleBanners` setting is off) as one a tale made would. It tells the tale they follow, the first on their tracker; with
     * none, the probe's own lines, which every build has. A beginning names the town of that tale, or of the capital they stand in.
     */
    private fun banner(context: CommandContext<CommandSourceStack>, kind: Int, player: ServerPlayer): Int {
        val source = context.source
        if (notReady(source)) return 0
        TaleCommand.off()?.let { say(source, TaleCommand.grey(it)) }
        val level = source.level.server.overworld()
        val row = player.getData(ModAttachments.TALES).firstOrNull()
        val template = row?.template ?: Templates.PROBE.id
        val shown = when (kind) {
            TaleBannerPayload.BEGIN -> TaleBannerPayload.beginning(template, town(level, player, row))
            TaleBannerPayload.STEP -> TaleBannerPayload.turning(template, row?.step ?: PROBE_STEP)
            else -> {
                val word = Templates.find(template)?.ending(if (kind == TaleBannerPayload.ILL) Ending.C else Ending.A)?.word
                    ?: return TaleCommand.fail(source, "The $template tale has no such ending to show.")
                TaleBannerPayload.ending(template, word, kind == TaleBannerPayload.GOOD)
            }
        }
        PacketDistributor.sendToPlayer(player, shown.copy(kind = shown.kind or TaleBannerPayload.TEST))
        val word = KINDS.first { it.second == kind }.first
        say(source, labelled("Banner", "$word sent to ${player.name.string}, as the ${if (row == null) "probe" else template} tale tells it").append(TaleCommand.grey("  (it waits behind a place's name, a region's card and a season's)")))
        return 1
    }

    /** The town a beginning names: the capital of the tale [row] is of, else the one [player] stands in, else none (blank). */
    private fun town(level: ServerLevel, player: ServerPlayer, row: TaleRow?): String {
        val capital = row?.let { r -> TaleEngine.openTales(level).firstOrNull { it.id == r.id }?.capital }
        if (capital != null) return TaleWords.town(level, capital)
        return Capitals.covering(player.level(), player.blockPosition())?.let { VillageNames.of(it.boardAnchor) }.orEmpty()
    }
}
