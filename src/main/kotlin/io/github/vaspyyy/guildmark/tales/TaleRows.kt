package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.Optional
import java.util.UUID

/**
 * One tale as one player's tracker shows it (living-world/stories/design.md 5.1): the tale's [id] and [template] and the [step]
 * it is at, the language keys and arguments of its short [title] and of the next thing to do ([status]), the ink the status is
 * written in ([tone], one of the constants below), the [target] the map and the compass point at in [dimension] (empty for none),
 * and how many [days] the step has left, counting today (1 is the last). The list of them rides the `TALES` attachment, synced to
 * its own player and never saved; the client draws it and keeps nothing else about a tale.
 */
data class TaleRow(
    val id: Int,
    val template: String,
    val step: Int,
    val title: String,
    val titleArgs: List<String>,
    val status: String,
    val statusArgs: List<String>,
    val tone: Int,
    val target: Optional<BlockPos>,
    val dimension: ResourceKey<Level>,
    val days: Int,
) {
    /** The place the row points at, or null for none. */
    val place: BlockPos? get() = target.orElse(null)

    companion object {
        /** The ink a status is written in: amber by default, red on the step's last two days, green when only the telling is left. */
        const val AMBER = 0
        const val RED = 1
        const val GREEN = 2

        val STREAM_CODEC: StreamCodec<ByteBuf, TaleRow> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TaleRow::id,
            ByteBufCodecs.STRING_UTF8, TaleRow::template,
            ByteBufCodecs.VAR_INT, TaleRow::step,
            ByteBufCodecs.STRING_UTF8, TaleRow::title,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TaleRow::titleArgs,
            ByteBufCodecs.STRING_UTF8, TaleRow::status,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TaleRow::statusArgs,
            ByteBufCodecs.VAR_INT, TaleRow::tone,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), TaleRow::target,
            ResourceKey.streamCodec(Registries.DIMENSION), TaleRow::dimension,
            ByteBufCodecs.VAR_INT, TaleRow::days,
            ::TaleRow,
        )
    }
}

/**
 * The tracker's rows (living-world/stories/design.md 5.1, Part D): for each player who is IN a tale, at most two [TaleRow]s made
 * from the record and set on the player's `TALES` attachment, so the tracker, the map and the compass can follow the tale. The record
 * is the truth and the rows are rebuilt from it every [EVERY] ticks, at once when a part is taken up, a step turns or a tale ends, on
 * login and after a respawn, and are set only if they differ from what the player already holds, so a list is sent once for each
 * change and never otherwise. Nothing is saved. With tales off nothing is built, and the rows are emptied as they go off.
 *
 * It is also the following's ear for the tales' [Tales.Listener] (the rows, and the banner that marks a part taken up and a step
 * turned), and where `tale rows` and `tale banner` are added to the command.
 */
internal object TaleRows : Tales.Listener {
    /** Game ticks between two builds of the rows: two seconds. */
    private const val EVERY = 40

    /** The players whose tracker holds a row now, so that with no tale open only they are looked at. */
    private val shown = HashSet<UUID>()

    /** How many times each player's rows have been set (and so sent) since they joined; for `tale rows`. */
    private val sent = HashMap<UUID, Int>()

    /** The tales whose template threw in `view`, told to the log once each. */
    private val warned = HashSet<Int>()

    /** At setup, from `Tales.register`: the listener, the clock, the login and respawn hooks, and the commands. */
    fun register(modBus: IEventBus) {
        Tales.listen(this)
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % EVERY == 0) pass(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { (it.entity as? ServerPlayer)?.let(::loggedIn) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { (it.entity as? ServerPlayer)?.let(::refresh) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { (it.entity as? ServerPlayer)?.let(::loggedOut) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        TaleFollowCommands.add()
    }

    // ---- What the record says ----

    /** Whether [p] is following their tale: they took it up and it has not left them. */
    fun following(p: Part): Boolean = p.status == Part.Status.IN || p.status == Part.Status.DONE

    /** The rows [player]'s tracker should hold now: their open tales, in the order they were seeded, at most two. */
    fun build(level: ServerLevel, player: ServerPlayer): List<TaleRow> {
        val mine = TaleEngine.openTales(level).filter { t -> t.parts.any { it.player == player.uuid && following(it) } }
        return mine.take(TaleLimits.ON_TRACKER).mapNotNull { row(level, it, player) }
    }

    /** [t] as [player]'s row: the template's view, the ink the engine settles (red on the step's last two days), and the days left. */
    private fun row(level: ServerLevel, t: Tale, player: ServerPlayer): TaleRow? {
        val template = Templates.find(t.template) ?: return null
        val view = try {
            template.view(t, player.uuid)
        } catch (e: Exception) {
            if (warned.add(t.id)) Guildmark.LOGGER.warn("The {} tale #{} could not say how its tracker row reads", t.template, t.id, e)
            return null
        }
        val days = (t.by - TaleEngine.today(level, t.capital) + 1).coerceIn(1L, 99L).toInt()
        val tone = when {
            view.tone == View.Tone.DONE -> TaleRow.GREEN
            view.tone == View.Tone.LATE || days <= 2 -> TaleRow.RED
            else -> TaleRow.AMBER
        }
        return TaleRow(t.id, t.template, t.step, view.title, view.titleArgs, view.status, view.statusArgs, tone, Optional.ofNullable(view.target), level.dimension(), days)
    }

    // ---- Setting them ----

    /** The rows of every player who is IN a tale, and the emptying of the rows of those who no longer are: once every [EVERY] ticks. */
    private fun pass(server: MinecraftServer) {
        if (!Tales.live) return
        TaleEngine.guarded("the tracker rows of the tales") {
            val level = server.overworld()
            val open = TaleEngine.openTales(level)
            if (open.isEmpty() && shown.isEmpty()) return@guarded
            val who = LinkedHashSet<UUID>()
            for (t in open) for (p in t.parts) if (following(p)) who.add(p.player)
            who.addAll(shown)
            for (id in who) {
                val player = server.playerList.getPlayer(id)
                if (player == null) {
                    shown.remove(id)
                    continue
                }
                set(player, build(level, player))
            }
        }
    }

    /** Sets [player]'s rows to what the record says now, at once (a part taken up, a step turned, a tale ended, a login, a respawn). */
    fun refresh(player: ServerPlayer) {
        if (!Tales.live) return
        TaleEngine.guarded("the tracker rows of ${player.name.string}") {
            set(player, build(player.level().server.overworld(), player))
        }
    }

    /** Refreshes the row of every player who is following [t]. */
    fun refresh(level: ServerLevel, t: Tale) {
        for (p in t.parts) if (following(p)) level.server.playerList.getPlayer(p.player)?.let(::refresh)
    }

    /** Puts [rows] on [player]'s tracker if it does not hold them already. */
    private fun set(player: ServerPlayer, rows: List<TaleRow>) {
        if (player.getData(ModAttachments.TALES) != rows) {
            player.setData(ModAttachments.TALES, rows)
            sent.merge(player.uuid, 1, Int::plus)
        }
        if (rows.isEmpty()) shown.remove(player.uuid) else shown.add(player.uuid)
    }

    /** How many times [player]'s rows have been set since they joined. */
    fun sentTo(player: ServerPlayer): Int = sent[player.uuid] ?: 0

    private fun loggedIn(player: ServerPlayer) {
        sent.remove(player.uuid)
        refresh(player)
    }

    private fun loggedOut(player: ServerPlayer) {
        shown.remove(player.uuid)
        sent.remove(player.uuid)
    }

    private fun forget() {
        shown.clear()
        sent.clear()
        warned.clear()
    }

    // ---- What the engine tells the following ----

    override fun joined(level: ServerLevel, t: Tale, player: ServerPlayer) {
        refresh(player)
    }

    override fun stepped(level: ServerLevel, t: Tale) {
        refresh(level, t)
    }

    override fun ended(level: ServerLevel, t: Tale) {
        refresh(level, t)
        warned.remove(t.id)
    }

    /** Tales went off: every tracker is emptied. */
    override fun paused(level: ServerLevel) {
        for (id in shown.toList()) {
            val player = level.server.playerList.getPlayer(id)
            if (player == null) shown.remove(id) else set(player, listOf())
        }
    }
}
