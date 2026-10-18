package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.Census
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.Look
import io.github.vaspyyy.guildmark.city.plan.Wares
import io.github.vaspyyy.guildmark.network.StallPennant
import io.github.vaspyyy.guildmark.network.StallPennantsPayload
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The market hall's tables in the world, for players' own stalls (see [OwnStalls]): where each stands, worked out from
 * the capital's plan (the hall's displays on its ground floor, in the order the plan sets them out), so a capital built
 * before stalls were let has the same tables as one built now. While a table is let, its display heaps its renter's
 * own goods in place of the market's, the three they have most of, in a display that suits the first of them (a
 * basket for roots, sacks for grain, a rack for meat), and once it's let go it goes back to the plan's. Every client
 * is told where the let tables are and in whose colour, for the pennant over each (client/economy/StallPennants).
 */
object StallTables {
    /** One of a hall's tables: its [index] in the hall, its display's spot, its front (the buyers' side) and the plan's display there. */
    class Table(val capital: CapitalPiece, val index: Int, val display: BlockPos, val front: Direction, val plan: Census.Display) {
        /** What a player may use to step up to it: the display, the table under it, and the slab and lantern beside them. */
        val blocks: List<BlockPos> = listOf(display, display.below()).flatMap { listOf(it, it.relative(front.clockWise), it.relative(front.counterClockWise)) }

        /** The middle of the display, to measure reach from. */
        val middle: Vec3 get() = Vec3.atCenterOf(display)
    }

    /** Ticks between looks at what the displays show and whom the pennants are for, and between looking for players' capitals. */
    private const val SYNC = 20
    private const val SCAN = 100

    /** Of a good on a stall, how many units make its lane of the display full to the rim; twice as many heap it over. */
    const val FULL_UNITS = 32

    /** Each capital's tables (by its centre), once its places are worked out; and which table each of their blocks belongs to. */
    private val known = HashMap<BlockPos, List<Table>>()
    private val blocks = HashMap<Long, Table>()

    /** The pennants every client was last told of. */
    private var told: List<StallPennant> = listOf()

    /** Starts the looks with the server's ticks, tells each player of the pennants as they join, and forgets it all when the server stops. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            (event.entity as? ServerPlayer)?.let { PacketDistributor.sendToPlayer(it, StallPennantsPayload(told)) }
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            known.clear()
            blocks.clear()
            told = listOf()
        }
    }

    /** [capital]'s market hall tables; null while its places are being worked out, none if it has no hall. */
    fun of(level: ServerLevel, capital: CapitalPiece): List<Table>? {
        known[capital.center]?.let { return it }
        val places = CityPlaces.of(level, capital) ?: return null
        val hall = places.lodgings.values.firstOrNull { it.kind == Kind.MARKET_HALL.name }
        val inside = if (hall == null) listOf() else places.census.displays.filter { hall.contains(WaresPlacer.spot(capital, it)) }
        val ground = inside.minOfOrNull { it.at.y }
        val tables = inside.filter { it.at.y == ground }.take(OwnStalls.TABLES).mapIndexed { i, d ->
            Table(capital, i, WaresPlacer.spot(capital, d), capital.direction(d.facing), d)
        }
        known[capital.center] = tables
        for (t in tables) for (b in t.blocks) blocks[b.asLong()] = t
        return tables
    }

    /** The table [pos] is a part of, among those of the capitals players have been in. */
    fun at(pos: BlockPos): Table? = blocks[pos.asLong()]

    /** The table [stall] stands on, if its capital's tables are known. */
    fun of(stall: OwnStall): Table? = blocks[stall.at.asLong()]

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        if (server.tickCount % SCAN == 0) for (player in level.players()) Furnish.capitalAt(level, player.blockPosition())?.let { of(level, it) }
        if (server.tickCount % SYNC != 0) return
        val data = OwnStalls.get(level)
        for ((centre, tables) in known) {
            val hall = data.find(centre.asLong())
            for (t in tables) {
                if (!level.isLoaded(t.display)) continue
                if (!level.hasNearbyAlivePlayer(t.display.x + 0.5, t.display.y + 0.5, t.display.z + 0.5, WaresBlockEntity.NEAR)) continue
                sync(level, t, hall?.stalls?.getOrNull(t.index))
            }
        }
        pennants(server, data)
    }

    /**
     * Brings [t]'s display up to date: heaped with [stall]'s goods while it's let, the plan's again once it isn't. The
     * first time a stall's goods change, they're set out empty, and the next look fills them, so they settle in one
     * after another.
     */
    fun sync(level: ServerLevel, t: Table, stall: OwnStall?) {
        if (!WaresRegistry.enabled()) return
        val display = level.getBlockEntity(t.display) as? WaresBlockEntity ?: return
        if (stall == null) {
            if (display.own != null) restore(level, t, display)
            return
        }
        val shown = Good.TRADED.filter { stall.shown(it) > 0 }.sortedByDescending { stall.shown(it) }.take(SHOWN)
        val want = if (shown.any { suits(display.look, it) }) display.look else shown.firstOrNull()?.let(::lookOf) ?: display.look
        val here = if (want != display.look) swap(level, t, display, want) ?: return else display
        val goods = shown.sortedBy { it.ordinal }.map { it.id }
        if (goods != here.own) {
            here.show(level, goods, IntArray(WaresLayout.capacity(here.look, goods).size / 2))
            return
        }
        val cap = WaresLayout.capacity(here.look, goods)
        val counts = IntArray(cap.size / 2) { i ->
            val units = Good.byId(goods[i])?.let(stall::shown) ?: 0
            if (units <= 0) 0 else max(1, WaresLayout.count(cap[i * 2], cap[i * 2 + 1], units.toDouble() / FULL_UNITS).roundToInt()).coerceAtMost(cap[i * 2 + 1])
        }
        here.show(level, goods, counts)
    }

    /** [t]'s display as the plan has it again, showing the market's goods. */
    private fun restore(level: ServerLevel, t: Table, display: WaresBlockEntity) {
        val planned = WaresPlacer.state(t.capital, t.plan, t.display)
        val here = if (planned.block != display.blockState.block) {
            level.setBlock(t.display, planned, Block.UPDATE_ALL)
            level.getBlockEntity(t.display) as? WaresBlockEntity ?: return
        } else display
        here.setUp(t.capital.center, t.plan.wares)
        here.refresh(level)
        level.sendBlockUpdated(t.display, here.blockState, here.blockState, Block.UPDATE_CLIENTS)
    }

    /** Puts a display of [look] in [display]'s place, facing the same way, set up for its capital; null if it didn't take. */
    private fun swap(level: ServerLevel, t: Table, display: WaresBlockEntity, look: Look): WaresBlockEntity? {
        val state = WaresRegistry.block(look).defaultBlockState().setValue(WaresBlock.FACING, display.blockState.getValue(WaresBlock.FACING))
        level.setBlock(t.display, state, Block.UPDATE_ALL)
        val fresh = level.getBlockEntity(t.display) as? WaresBlockEntity ?: return null
        fresh.setUp(t.capital.center, Wares.of(look))
        return fresh
    }

    /** The display that suits [good]: the look the plan shows it in, or a basket for what no stall shows. */
    private fun lookOf(good: Good): Look = Wares.entries.firstOrNull { good.id in it.goods }?.look ?: Look.BASKET

    private fun suits(look: Look, good: Good): Boolean = lookOf(good) == look

    /** Tells every player of the pennants over the let tables, when they've changed since they were last told. */
    private fun pennants(server: MinecraftServer, data: OwnStalls) {
        val now = data.all().flatMap { hall -> hall.stalls.filterNotNull().map { StallPennant(it.at, it.front.get2DDataValue(), it.color, it.name) } }
        if (now == told) return
        told = now
        val payload = StallPennantsPayload(now)
        for (player in server.playerList.players) PacketDistributor.sendToPlayer(player, payload)
    }

    /** The most goods a display heaps at once. */
    private const val SHOWN = 3
}
