package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksClient
import io.github.vaspyyy.guildmark.economy.works.build.WorksLedger
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * The works screen's payloads (living-world/design-influence.md sections 2 to 4, and open question 10). The server
 * sends a capital's works as they stand to whoever has its screen open ([WorksLedgerPayload]), again whenever
 * anything on it changes, to everyone looking; each works' drawing once ([WorksDrawingPayload]); and a close when the
 * screen may no longer be used ([WorksClosePayload]). The client asks for a works' drawing, turns its pages, pledges
 * and takes its pledges back ([WorksActionPayload]). The server decides everything: the screen only shows what it's
 * sent, and every ask is checked again there (WorksLedger.act).
 */
object WorksPayloads {
    /** The most of each list a payload carries. */
    const val MAX_WORKS = 24
    const val MAX_PLEDGES = 64
    const val MAX_SHIPS = 16
    const val MAX_NAMES = 8
    const val MAX_DAYS = 32
    const val MAX_REASONS = 24
    const val MAX_COUNTS = 8
    const val MAX_CELLS = 64 * 96
    const val MAX_ENTRIES = 16384

    /** Registers the payloads (WorksLedger.register adds this to the mod's bus). */
    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .playToClient(WorksLedgerPayload.TYPE, WorksLedgerPayload.STREAM_CODEC, WorksLedgerPayload::handle)
            .playToClient(WorksDrawingPayload.TYPE, WorksDrawingPayload.STREAM_CODEC, WorksDrawingPayload::handle)
            .playToClient(WorksClosePayload.TYPE, WorksClosePayload.STREAM_CODEC, WorksClosePayload::handle)
            .playToServer(WorksActionPayload.TYPE, WorksActionPayload.STREAM_CODEC, WorksActionPayload::handle)
    }

    internal fun <T : CustomPacketPayload> type(name: String): CustomPacketPayload.Type<T> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))
}

private fun RegistryFriendlyByteBuf.ints(a: IntArray) {
    writeVarInt(a.size)
    for (v in a) writeVarInt(v)
}

private fun RegistryFriendlyByteBuf.ints(most: Int): IntArray = IntArray(readVarInt().coerceIn(0, most)) { readVarInt() }

private fun RegistryFriendlyByteBuf.longs(a: LongArray) {
    writeVarInt(a.size)
    for (v in a) writeLong(v)
}

private fun RegistryFriendlyByteBuf.longs(most: Int): LongArray = LongArray(readVarInt().coerceIn(0, most)) { readLong() }

private fun RegistryFriendlyByteBuf.text(c: Component) = ComponentSerialization.STREAM_CODEC.encode(this, c)

private fun RegistryFriendlyByteBuf.text(): Component = ComponentSerialization.STREAM_CODEC.decode(this)

private inline fun <T> RegistryFriendlyByteBuf.list(items: List<T>, most: Int, write: (T) -> Unit) {
    val shown = if (items.size > most) items.subList(0, most) else items
    writeVarInt(shown.size)
    for (item in shown) write(item)
}

private inline fun <T> RegistryFriendlyByteBuf.list(most: Int, read: () -> T): List<T> = List(readVarInt().coerceIn(0, most)) { read() }

/** One pledger of a works: their name, the Marks they've pledged in all, the market day of their first, and whether it's the reader. */
class WorksPledgeLine(val name: String, val marks: Int, val day: Long, val mine: Boolean)

/**
 * A wagon on its way in with a works' materials: its caravan, its road (in words), what it carries for the works (by
 * Stuff.PAID), the hour on the clock it's due in, the clock ticks until then, how far along the road it is (0 to 1),
 * and how it's faring ([WAITING] at the gate, [ROLLING], or [STRUCK] by a band).
 */
class WorksShipLine(val caravan: Long, val road: Component, val stuff: IntArray, val clock: Int, val ticks: Long, val along: Float, val phase: Int) {
    companion object {
        const val WAITING = 0
        const val ROLLING = 1
        const val STRUCK = 2
    }
}

/**
 * One works as its screen shows it. [kind] and [state] are ordinals of WorksKind and WorksState; [road] is its road
 * in words (a tower's) and [owner] its owner's name (a stall's); [parts] its price's parts by their keys; [patron] the
 * index of its patron among [pledges] (-1: none yet); [why] what made it wanted; [wanted], [begun] and [done] market
 * days (-1 until then); [stage] a Stage ordinal (-1 before its first step), with [laid] of [total] steps laid and
 * each stage's first step ([starts], by Stage ordinal, and the end); [bill], [here], [coming] and [used] by
 * Stuff.PAID, [ships] the wagons bringing the rest; [sited] whether it has its site, and [status] what's to say of
 * it (no room, a place in its way); [waiting] what holds the work up now, if anything ([alarm]: badly enough to show
 * red); its [master] builder and where he is ([masterAt]: 0 not come, 1 at the works, 2 away from it), the
 * labourers it wants ([crew]), those at it now ([working]), today's from the town ([names]) and the strangers making
 * up the rest; [eta] the clock's daylight ticks of work left (-1 not building); the build as it stands ([step] laid
 * in order, and [done] the steps beyond it already laid, a bit each from [step]); its drawing ([drawing], a hash:
 * 0 for none sent); the Marks the reader has pledged toward it ([mine]); what it does now it stands ([doing]); and
 * when it last eased each thing ([eased]: "mill", "bread", "band" to a market day).
 */
class WorksLine(
    val id: Int, val kind: Int, val road: Component, val owner: String, val state: Int,
    val price: Int, val parts: List<Pair<String, Int>>, val pledges: List<WorksPledgeLine>, val patron: Int,
    val why: Component, val wanted: Long, val begun: Long, val done: Long,
    val stage: Int, val laid: Int, val total: Int, val starts: IntArray,
    val bill: IntArray, val here: IntArray, val coming: IntArray, val used: IntArray, val ships: List<WorksShipLine>,
    val sited: Boolean, val status: Component, val waiting: Component, val alarm: Boolean,
    val master: Component, val masterAt: Int, val crew: Int, val working: Int, val names: List<String>, val strangers: Int,
    val eta: Int, val step: Int, val doneBits: LongArray, val drawing: Int, val mine: Int, val doing: Component,
    val eased: List<Pair<String, Long>>,
) {
    /** All the Marks pledged toward it. */
    val pledged: Int get() = pledges.sumOf { it.marks }

    /** Whether step [i] of its build is laid. */
    fun isDone(i: Int): Boolean {
        if (i < step) return true
        val k = i - step
        val word = k ushr 6
        return word < doneBits.size && (doneBits[word] ushr (k and 63)) and 1L == 1L
    }

    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeVarInt(id)
        buf.writeVarInt(kind)
        buf.text(road)
        buf.writeUtf(owner)
        buf.writeVarInt(state)
        buf.writeVarInt(price)
        buf.list(parts, 8) { (k, v) ->
            buf.writeUtf(k)
            buf.writeVarInt(v)
        }
        buf.list(pledges, WorksPayloads.MAX_PLEDGES) {
            buf.writeUtf(it.name)
            buf.writeVarInt(it.marks)
            buf.writeVarLong(it.day + 1)
            buf.writeBoolean(it.mine)
        }
        buf.writeVarInt(patron + 1)
        buf.text(why)
        buf.writeVarLong(wanted + 1)
        buf.writeVarLong(begun + 1)
        buf.writeVarLong(done + 1)
        buf.writeVarInt(stage + 1)
        buf.writeVarInt(laid)
        buf.writeVarInt(total)
        buf.ints(starts)
        buf.ints(bill)
        buf.ints(here)
        buf.ints(coming)
        buf.ints(used)
        buf.list(ships, WorksPayloads.MAX_SHIPS) {
            buf.writeVarLong(it.caravan)
            buf.text(it.road)
            buf.ints(it.stuff)
            buf.writeVarInt(it.clock)
            buf.writeVarLong(it.ticks.coerceAtLeast(0L))
            buf.writeFloat(it.along)
            buf.writeVarInt(it.phase)
        }
        buf.writeBoolean(sited)
        buf.text(status)
        buf.text(waiting)
        buf.writeBoolean(alarm)
        buf.text(master)
        buf.writeVarInt(masterAt)
        buf.writeVarInt(crew)
        buf.writeVarInt(working)
        buf.list(names, WorksPayloads.MAX_NAMES) { buf.writeUtf(it) }
        buf.writeVarInt(strangers)
        buf.writeVarInt(eta + 1)
        buf.writeVarInt(step)
        buf.longs(doneBits)
        buf.writeInt(drawing)
        buf.writeVarInt(mine)
        buf.text(doing)
        buf.list(eased, 4) { (what, day) ->
            buf.writeUtf(what)
            buf.writeVarLong(day + 1)
        }
    }

    companion object {
        fun read(buf: RegistryFriendlyByteBuf): WorksLine = WorksLine(
            buf.readVarInt(), buf.readVarInt(), buf.text(), buf.readUtf(), buf.readVarInt(),
            buf.readVarInt(), buf.list(8) { buf.readUtf() to buf.readVarInt() },
            buf.list(WorksPayloads.MAX_PLEDGES) { WorksPledgeLine(buf.readUtf(), buf.readVarInt(), buf.readVarLong() - 1, buf.readBoolean()) },
            buf.readVarInt() - 1,
            buf.text(), buf.readVarLong() - 1, buf.readVarLong() - 1, buf.readVarLong() - 1,
            buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(), buf.ints(32),
            buf.ints(16), buf.ints(16), buf.ints(16), buf.ints(16),
            buf.list(WorksPayloads.MAX_SHIPS) {
                WorksShipLine(buf.readVarLong(), buf.text(), buf.ints(16), buf.readVarInt(), buf.readVarLong(), buf.readFloat(), buf.readVarInt())
            },
            buf.readBoolean(), buf.text(), buf.text(), buf.readBoolean(),
            buf.text(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.list(WorksPayloads.MAX_NAMES) { buf.readUtf() }, buf.readVarInt(),
            buf.readVarInt() - 1, buf.readVarInt(), buf.longs(4096), buf.readInt(), buf.readVarInt(), buf.text(),
            buf.list(4) { buf.readUtf() to buf.readVarLong() - 1 },
        )
    }
}

/** A works the town doesn't want yet ([kind] a WorksKind ordinal; [road] a tower's road in words), and what would make it wanted. */
class WorksNotYetLine(val kind: Int, val road: Component, val text: Component)

/** A capital's fortunes on market day [day]: its score and the times it made (a Times ordinal). */
class WorksDayLine(val day: Long, val score: Int, val times: Int)

/** Something that weighs on the town today: its kind's name, it in a sentence, its points, the market days it has left (-1: while it holds; 0: today only), and whether it's a works' help. */
class WorksReasonLine(val label: Component, val text: Component, val points: Int, val left: Int, val eased: Boolean)

/** A day one of the town's works eased something, and what, in words (a mark on the chart). */
class WorksEasedLine(val day: Long, val text: Component)

/**
 * The town's fortunes for the screen's chart: whether they're worked out at all ([on]); the times now (a Times
 * ordinal), its score, today's market day, the day the times last moved ([since], -1 never) and what from; the last
 * days, oldest first; today's reasons, biggest first; and the days the town's works eased something.
 */
class WorksFortuneView(
    val on: Boolean, val times: Int, val score: Int, val today: Long, val since: Long, val from: Int,
    val days: List<WorksDayLine>, val reasons: List<WorksReasonLine>, val eased: List<WorksEasedLine>,
) {
    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeBoolean(on)
        buf.writeVarInt(times)
        buf.writeVarInt(score + 100)
        buf.writeVarLong(today + 1)
        buf.writeVarLong(since + 1)
        buf.writeVarInt(from)
        buf.list(days, WorksPayloads.MAX_DAYS) {
            buf.writeVarLong(it.day + 1)
            buf.writeVarInt(it.score + 100)
            buf.writeVarInt(it.times)
        }
        buf.list(reasons, WorksPayloads.MAX_REASONS) {
            buf.text(it.label)
            buf.text(it.text)
            buf.writeVarInt(it.points + 100)
            buf.writeVarInt(it.left + 1)
            buf.writeBoolean(it.eased)
        }
        buf.list(eased, WorksPayloads.MAX_REASONS) {
            buf.writeVarLong(it.day + 1)
            buf.text(it.text)
        }
    }

    companion object {
        val NONE = WorksFortuneView(false, 2, 0, -1L, -1L, 2, listOf(), listOf(), listOf())

        fun read(buf: RegistryFriendlyByteBuf): WorksFortuneView = WorksFortuneView(
            buf.readBoolean(), buf.readVarInt(), buf.readVarInt() - 100, buf.readVarLong() - 1, buf.readVarLong() - 1, buf.readVarInt(),
            buf.list(WorksPayloads.MAX_DAYS) { WorksDayLine(buf.readVarLong() - 1, buf.readVarInt() - 100, buf.readVarInt()) },
            buf.list(WorksPayloads.MAX_REASONS) { WorksReasonLine(buf.text(), buf.text(), buf.readVarInt() - 100, buf.readVarInt() - 1, buf.readBoolean()) },
            buf.list(WorksPayloads.MAX_REASONS) { WorksEasedLine(buf.readVarLong() - 1, buf.text()) },
        )
    }
}

/** How many count the reader something, and for what. */
class WorksRegardCount(val text: Component, val n: Int)

/**
 * The town's regard for the reader (open question 10): whether the townsfolk remember anyone ([on]); how many of its
 * residents know them how well (by Familiarity ordinal, strangers too), their standing at the board in words (empty
 * for none), and what warms those who think well of them and cools those who don't, most first.
 */
class WorksRegardView(val on: Boolean, val counts: IntArray, val standing: Component, val warm: List<WorksRegardCount>, val cool: List<WorksRegardCount>) {
    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeBoolean(on)
        buf.ints(counts)
        buf.text(standing)
        buf.list(warm, WorksPayloads.MAX_COUNTS) {
            buf.text(it.text)
            buf.writeVarInt(it.n)
        }
        buf.list(cool, WorksPayloads.MAX_COUNTS) {
            buf.text(it.text)
            buf.writeVarInt(it.n)
        }
    }

    companion object {
        val NONE = WorksRegardView(false, IntArray(7), Component.empty(), listOf(), listOf())

        /** Not worked out yet: it is once its page is turned to. */
        val PENDING = WorksRegardView(true, IntArray(0), Component.empty(), listOf(), listOf())

        fun read(buf: RegistryFriendlyByteBuf): WorksRegardView = WorksRegardView(
            buf.readBoolean(), buf.ints(16), buf.text(),
            buf.list(WorksPayloads.MAX_COUNTS) { WorksRegardCount(buf.text(), buf.readVarInt()) },
            buf.list(WorksPayloads.MAX_COUNTS) { WorksRegardCount(buf.text(), buf.readVarInt()) },
        )
    }
}

/**
 * A capital's works screen as it stands, for one reader: its [capital] and its [town]'s name; where the screen was
 * opened ([at]: it closes once the reader walks off from there); the works to show first ([selected], -1 for the
 * first); the Marks the reader carries; whether the works are on at all; its works and those not wanted yet; its
 * fortunes and its regard for the reader; what came of the reader's last ask ([notice], with its [tone], one of
 * WorksLedger's), new each time [serial] moves; and whether it opens the screen ([fresh]: the reader just asked for
 * it) or only keeps an open one up to date.
 */
data class WorksLedgerPayload(
    val capital: Long, val town: String, val at: BlockPos, val selected: Int, val purse: Int, val on: Boolean,
    val works: List<WorksLine>, val notYet: List<WorksNotYetLine>, val fortune: WorksFortuneView, val regard: WorksRegardView,
    val notice: Component, val tone: Int, val serial: Int, val fresh: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WorksLedgerPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WorksLedgerPayload> = WorksPayloads.type("works_ledger")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WorksLedgerPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeLong(p.capital)
                buf.writeUtf(p.town)
                buf.writeBlockPos(p.at)
                buf.writeVarInt(p.selected + 1)
                buf.writeVarInt(p.purse)
                buf.writeBoolean(p.on)
                buf.list(p.works, WorksPayloads.MAX_WORKS) { it.write(buf) }
                buf.list(p.notYet, WorksPayloads.MAX_WORKS) {
                    buf.writeVarInt(it.kind)
                    buf.text(it.road)
                    buf.text(it.text)
                }
                p.fortune.write(buf)
                p.regard.write(buf)
                buf.text(p.notice)
                buf.writeVarInt(p.tone)
                buf.writeVarInt(p.serial)
                buf.writeBoolean(p.fresh)
            },
            { buf ->
                WorksLedgerPayload(
                    buf.readLong(), buf.readUtf(), buf.readBlockPos(), buf.readVarInt() - 1, buf.readVarInt(), buf.readBoolean(),
                    buf.list(WorksPayloads.MAX_WORKS) { WorksLine.read(buf) },
                    buf.list(WorksPayloads.MAX_WORKS) { WorksNotYetLine(buf.readVarInt(), buf.text(), buf.text()) },
                    WorksFortuneView.read(buf), WorksRegardView.read(buf), buf.text(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                )
            },
        )

        fun handle(payload: WorksLedgerPayload, context: IPayloadContext) = WorksClient.onLedger(payload)
    }
}

/**
 * A works' drawing, sent once to each reader (again only if it changes, by [hash]): its front as an elevation, a
 * [width] by [height] grid of cells seen from before its front (left to right, from the bottom row up), each holding
 * the blocks behind one another there, front first ([steps]: the build's step that lays each; [gones]: the step that
 * takes it down again, a scaffold pole's, a stake's or the bough's, or -1; [colors]: its colour, with how it's drawn
 * in its top byte), from [starts] (one more than there are cells). The world's side: its frame ([origin], [front] as a 2D direction
 * value), the drawing's x that's the grid's left column ([x1]: the drawing's x runs right to left seen from the
 * front), the world height of its bottom row ([bottom]) and the row its ground is ([ground]), for where its crew
 * stands on it.
 */
data class WorksDrawingPayload(
    val id: Int, val hash: Int, val origin: BlockPos, val front: Int, val x1: Int, val z0: Int, val z1: Int, val bottom: Int, val ground: Int,
    val width: Int, val height: Int, val starts: IntArray, val steps: IntArray, val gones: IntArray, val colors: IntArray,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WorksDrawingPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WorksDrawingPayload> = WorksPayloads.type("works_drawing")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WorksDrawingPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.id)
                buf.writeInt(p.hash)
                buf.writeBlockPos(p.origin)
                buf.writeVarInt(p.front)
                buf.writeVarInt(p.x1 + 512)
                buf.writeVarInt(p.z0 + 512)
                buf.writeVarInt(p.z1 + 512)
                buf.writeVarInt(p.bottom + 4096)
                buf.writeVarInt(p.ground)
                buf.writeVarInt(p.width)
                buf.writeVarInt(p.height)
                val cells = p.width * p.height
                for (c in 0 until cells) buf.writeVarInt(p.starts[c + 1] - p.starts[c])
                for (k in 0 until p.starts[cells]) {
                    buf.writeVarInt(p.steps[k])
                    buf.writeVarInt(p.gones[k] + 1)
                    buf.writeInt(p.colors[k])
                }
            },
            { buf ->
                val id = buf.readVarInt()
                val hash = buf.readInt()
                val origin = buf.readBlockPos()
                val front = buf.readVarInt()
                val x1 = buf.readVarInt() - 512
                val z0 = buf.readVarInt() - 512
                val z1 = buf.readVarInt() - 512
                val bottom = buf.readVarInt() - 4096
                val ground = buf.readVarInt()
                val width = buf.readVarInt().coerceIn(0, 64)
                val height = buf.readVarInt().coerceIn(0, 96)
                val cells = width * height
                val starts = IntArray(cells + 1)
                for (c in 0 until cells) starts[c + 1] = starts[c] + buf.readVarInt().coerceIn(0, 128)
                val n = starts[cells].coerceAtMost(WorksPayloads.MAX_ENTRIES)
                val steps = IntArray(n)
                val gones = IntArray(n)
                val colors = IntArray(n)
                for (k in 0 until n) {
                    steps[k] = buf.readVarInt()
                    gones[k] = buf.readVarInt() - 1
                    colors[k] = buf.readInt()
                }
                for (c in 0..cells) starts[c] = starts[c].coerceAtMost(n)
                WorksDrawingPayload(id, hash, origin, front, x1, z0, z1, bottom, ground, width, height, starts, steps, gones, colors)
            },
        )

        fun handle(payload: WorksDrawingPayload, context: IPayloadContext) = WorksClient.onDrawing(payload)
    }
}

/** The works screen of [capital] may no longer be used (walked off, the works switched off): it closes. */
data class WorksClosePayload(val capital: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WorksClosePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WorksClosePayload> = WorksPayloads.type("works_close")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WorksClosePayload> = StreamCodec.of(
            { buf, p -> buf.writeLong(p.capital) },
            { buf -> WorksClosePayload(buf.readLong()) },
        )

        fun handle(payload: WorksClosePayload, context: IPayloadContext) = WorksClient.onClose(payload)
    }
}

/** Something asked on the works screen: [action] one of WorksLedger's, of works [id], with its [arg] (the Marks pledged, the page turned to). */
data class WorksActionPayload(val id: Int, val action: Int, val arg: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WorksActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WorksActionPayload> = WorksPayloads.type("works_action")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WorksActionPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.id)
                buf.writeVarInt(p.action)
                buf.writeVarInt(p.arg + 1)
            },
            { buf -> WorksActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1) },
        )

        fun handle(payload: WorksActionPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            WorksLedger.act(player, payload.id, payload.action, payload.arg)
        }
    }
}
