package io.github.vaspyyy.guildmark.mill

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.plan.MillSite
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * The capitals' mills, as the server keeps them: every mill hub loaded (see [MillBlockEntity]), each looked at again
 * every few seconds while a player is near enough to see it, its block set to what the mill is doing ([MillWork]) only
 * when that changes, so a client hears of a mill only when its sails start, stop or break. The sails turn while the
 * capital's market has its mill grinding (the market's word: [Maker.MILL] busy this hour), braked in a storm or with
 * something standing where they turn, one of the windmills with a sail hanging broken while the mill is broken; the
 * wheel turns with its stream all the time, harder while the mill grinds. With no market to ask (the markets switched
 * off, or a capital no player has been to yet) the mills go by the daylight. Where the sails are is worked out by each
 * client on its own (see [MillBlockEntity.clientTick]).
 */
object Mills {
    /** How near a player has to be for a mill to be looked at. */
    private const val NEAR = 128.0

    /** How far from where it's asked about a mill's hub can be, for [turning]: a windmill's stands well out over its door. */
    private const val ABOUT = 16.0

    /** Server ticks between passes over the mills, and passes between looks at any one of them. */
    private const val TICK = 20
    private const val EVERY = 5

    /** The clock's hours that count as daytime, for mills with no market to ask. */
    private val DAY = 6..17

    private val hubs = HashMap<ResourceKey<Level>, LinkedHashMap<Long, MillBlockEntity>>()

    /** Sets the mills going: their blocks, their round of looks, the swapping of older capitals' mills, and the testing command. */
    fun register(modBus: IEventBus) {
        MillRegistry.BLOCKS.register(modBus)
        MillRegistry.BLOCK_ENTITIES.register(modBus)
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> hubs.clear() }
        MillRefit.register()
        MillCommand.init()
    }

    /** Whether the mills turn at all (the Turning Mills setting). */
    fun enabled(): Boolean = try {
        GuildmarkConfig.TURNING_MILLS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /**
     * Whether the mill at or about [pos] (its millstone, its hub, the miller at work in it) is grinding just now as players
     * see it: its sails or its wheel turning at work. Asked of the nearest mill hub within a few blocks; with none loaded
     * there (an older capital's mill not yet swapped for a turning one), or with Turning Mills switched off, it goes by the
     * market of the capital at [pos] alone ([Maker.MILL] busy this hour), so a miller works whenever the mill grinds.
     */
    fun turning(level: ServerLevel, pos: BlockPos): Boolean {
        if (enabled()) near(level, pos)?.let { return it.blockState.getValue(MillBlock.WORK) == MillWork.WORKING }
        if (!Markets.enabled()) return false
        val capital = Furnish.capitalAt(level, pos) ?: return false
        return Markets.get(level)[capital.center]?.market?.busy(Maker.MILL) == true
    }

    /** What the mill whose hub is nearest [pos], within a few blocks, is doing; null if none is loaded there. */
    fun work(level: ServerLevel, pos: BlockPos): MillWork? = near(level, pos)?.blockState?.getValue(MillBlock.WORK)

    /** The loaded mill hub nearest [pos], within [ABOUT] blocks. */
    fun near(level: ServerLevel, pos: BlockPos): MillBlockEntity? {
        var best: MillBlockEntity? = null
        var bestD = ABOUT * ABOUT
        for (be in hubs[level.dimension()]?.values ?: return null) {
            if (be.isRemoved) continue
            val d = be.blockPos.distSqr(pos)
            if (d <= bestD) {
                best = be
                bestD = d
            }
        }
        return best
    }

    /** Every mill hub loaded in [level]. */
    fun loaded(level: ServerLevel): Collection<MillBlockEntity> = hubs[level.dimension()]?.values ?: emptyList()

    internal fun add(level: ServerLevel, be: MillBlockEntity) {
        hubs.getOrPut(level.dimension()) { LinkedHashMap() }[be.blockPos.asLong()] = be
    }

    internal fun remove(level: ServerLevel, be: MillBlockEntity) {
        val map = hubs[level.dimension()] ?: return
        if (map[be.blockPos.asLong()] === be) map.remove(be.blockPos.asLong())
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % TICK != 0 || !enabled()) return
        val pass = server.tickCount / TICK
        for (level in server.allLevels) {
            val map = hubs[level.dimension()] ?: continue
            if (map.isEmpty() || level.players().isEmpty()) continue
            for (be in map.values.toList()) {
                // Each on a pass of its own, so a capital's mills don't all look at once
                val pos = be.blockPos.asLong()
                if (Math.floorMod(pass + (pos xor (pos ushr 29)).toInt(), EVERY) != 0) continue
                check(level, be)
            }
        }
    }

    /** Works out again what [be]'s mill is doing and sets its block to that if it has changed, if a player is near enough to see it (or [always]). */
    fun check(level: ServerLevel, be: MillBlockEntity, always: Boolean = false) {
        if (be.isRemoved || !level.isLoaded(be.blockPos)) return
        if (!always && !watched(level, be.blockPos)) return
        val state = level.getBlockState(be.blockPos)
        if (state.block !is MillBlock) return
        val want = why(level, be).work
        if (state.getValue(MillBlock.WORK) != want) level.setBlock(be.blockPos, state.setValue(MillBlock.WORK, want), Block.UPDATE_CLIENTS)
    }

    private fun watched(level: ServerLevel, pos: BlockPos): Boolean =
        level.players().any { it.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) < NEAR * NEAR }

    /** What a mill should be doing, and why, in words for the testing command. */
    class Why(val work: MillWork, val reason: String)

    /** What [be]'s mill should be doing now, and why. */
    fun why(level: ServerLevel, be: MillBlockEntity): Why {
        if (!enabled()) return Why(MillWork.IDLE, "the mills are switched off (setting: Turning Mills)")
        val windmill = be.kind == MillKind.SAILS
        val capital = be.capital
        val entry = if (capital != null && Markets.enabled()) Markets.get(level)[capital] else null
        val m = entry?.market
        val broken = m?.events?.firstOrNull { it.kind == EventKind.MILL_BROKEN }
        if (windmill && m != null && broken != null) {
            val mine = be.count <= 0 || be.rank == Math.floorMod(broken.since, be.count.toLong()).toInt()
            val mended = "mended in ${broken.until - m.hour} hours"
            return if (mine) Why(MillWork.BROKEN, "a sail is broken ($mended)")
            else Why(MillWork.IDLE, "braked: another of the windmills has a broken sail, and the mill stands ($mended)")
        }
        if (windmill) {
            inWay(level, be)?.let {
                val what = BuiltInRegistries.BLOCK.getKey(level.getBlockState(it).block)
                return Why(MillWork.IDLE, "braked: $what at ${it.x} ${it.y} ${it.z} stands where the sails turn")
            }
            if (level.isThundering) return Why(MillWork.IDLE, "braked for the storm")
        }
        if (m == null) {
            val day = Conditions(Markets.hourOf(level)).clock in DAY
            val none = when {
                capital == null -> "it knows no capital (placed by hand)"
                !Markets.enabled() -> "the markets are switched off"
                else -> "its capital has no market yet"
            }
            return Why(if (day) MillWork.WORKING else MillWork.IDLE, "$none, so it goes by the daylight: " + if (day) "turning by day" else "still at night")
        }
        if (m.busy(Maker.MILL)) return Why(MillWork.WORKING, "grinding: the market's mill worked in the hour last worked out")
        val c = Conditions(m.hour)
        val town = entry.town
        val idle = when {
            broken != null -> "the mill is broken (mended in ${broken.until - m.hour} hours)"
            c.restDay -> "it's the seventh day, and the trades rest"
            !Economy.milling(c.clock) -> "it's ${c.clock}:00, outside the milling hours (7 to 12 and 13 to 18)"
            town.mills == 0 -> "the town's count has no mill in it"
            town.farmers == 0 -> "there's no farmer to work it"
            town.bakers == 0 -> "there are no bakers to grind for"
            town.use(Good.FLOUR) * 3.0 <= m[Site.MILL, Good.FLOUR] + m[Site.BAKEHOUSE, Good.FLOUR] -> "the flour stores are full"
            m[Site.MILL, Good.WHEAT] <= 0.0 -> "there's no grain at the mill"
            else -> "nothing was ground in the hour last worked out (for up to an hour after the world loads the market can't say)"
        }
        return Why(MillWork.IDLE, if (windmill) "braked: $idle" else "turning slowly with the stream: $idle")
    }

    /**
     * The first block standing where [be]'s sails turn, if there is one: anything in the row of blocks in front of the hub
     * that reaches into the slice of it they sweep (the windmill's lamps on their posts stand clear in front of them). The
     * sails are braked while it's there. Blocks whose land isn't loaded aren't looked at.
     */
    fun inWay(level: ServerLevel, be: MillBlockEntity): BlockPos? {
        if (be.kind != MillKind.SAILS) return null
        val facing = be.blockState.getValue(MillBlock.FACING)
        val right = facing.clockWise
        val slice = SLICES[facing.get2DDataValue()]
        val front = be.blockPos.relative(facing)
        val cursor = BlockPos.MutableBlockPos()
        val reach = MillSite.SAIL_REACH.toInt() + 1
        for (du in -reach..reach) for (dy in -reach..reach) {
            val nu = max(0.0, abs(du) - 0.5)
            val ny = max(0.0, abs(dy) - 0.5)
            if (nu * nu + ny * ny >= MillSite.SAIL_REACH * MillSite.SAIL_REACH) continue
            cursor.set(front.x + right.stepX * du, front.y + dy, front.z + right.stepZ * du)
            if (!level.isLoaded(cursor)) continue
            val state = level.getBlockState(cursor)
            if (state.isAir) continue
            if (Shapes.joinIsNotEmpty(state.getShape(level, cursor), slice, BooleanOp.AND)) return cursor.immutable()
        }
        return null
    }

    /**
     * The slice of a block in front of a hub that its sails sweep, by the way the hub faces (south, west, north, east):
     * from half a pixel to four and a half out from the hub's face.
     */
    private val SLICES: Array<VoxelShape> = arrayOf(
        Block.box(0.0, 0.0, 0.25, 16.0, 16.0, 4.75),
        Block.box(11.25, 0.0, 0.0, 15.75, 16.0, 16.0),
        Block.box(0.0, 0.0, 11.25, 16.0, 16.0, 15.75),
        Block.box(0.25, 0.0, 0.0, 4.75, 16.0, 16.0),
    )
}
