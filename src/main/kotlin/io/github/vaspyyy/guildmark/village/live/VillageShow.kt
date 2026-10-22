package io.github.vaspyyy.guildmark.village.live

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.abs

/**
 * `village show`: what the last census found, drawn in coloured dust for the operator who asked, for a little while, so that a house
 * the count missed or a field it took twice is seen where it stands. Only the one who asked sees it; nothing is kept or saved, and it
 * draws no more than [MAX_DRAWN] places at a time, the nearest to the player first.
 */
internal object VillageShow {
    /** Ticks between draws (the dust lasts a little longer than that), and the tick of the cycle they are on. */
    private const val EVERY = 10
    private const val OFFSET = 7

    /** The seconds a show lasts if none is asked for, and the most that can be asked for. */
    const val SECONDS = 20
    const val LONGEST = 120

    /** How far round the player places are drawn, and how many at most in a draw. */
    const val NEAR = 64
    private const val MAX_DRAWN = 240

    /** What each colour is, in the order the legend gives them. */
    enum class Kind(val rgb: Int, val color: String, val what: String) {
        DOOR(0x3CE05A, "green", "a door of a home"),
        BED(0x4A7DFF, "blue", "a bed"),
        BUILDING(0xC8C8C8, "grey", "a door of a building that is no home"),
        WELL(0x30D5E0, "cyan", "a well"),
        LAMP(0xFFE04A, "yellow", "a lamp"),
        BENCH(0xFF9A3C, "orange", "a bench"),
        STALL(0xE04AD0, "pink", "a stall"),
        FIELD(0xA6E04A, "lime", "a corner of a field"),
        TOWER(0xFF4A4A, "red", "an Archer Post"),
        WORK(0xFFFFFF, "white", "a work block"),
        GREEN(0xA58CFF, "violet", "the middle of the green"),
    }

    private class Watch(val key: Long, val until: Long)

    private val watching = HashMap<UUID, Watch>()

    /** Starts (or restarts) a show of the village [key] for [player], for [seconds] seconds. */
    fun start(level: ServerLevel, player: ServerPlayer, key: Long, seconds: Int) {
        watching[player.uuid] = Watch(key, level.gameTime + seconds * 20L)
    }

    /** Ends the show for [player]; whether there was one. */
    fun stop(player: ServerPlayer): Boolean = watching.remove(player.uuid) != null

    /** Ends every show (the switch is off, or the server stops). */
    fun clear() = watching.clear()

    /** The legend: a line for each colour, in its own colour. */
    fun legend(): List<Component> = Kind.entries.map { k ->
        Component.literal("  ${k.color}: ${k.what}").withStyle { it.withColor(k.rgb) }
    }

    /** Draws the shows that are on (on the ticks that are a draw's). */
    fun tick(server: MinecraftServer, level: ServerLevel) {
        if (watching.isEmpty() || server.tickCount % EVERY != OFFSET) return
        val now = level.gameTime
        val shows = watching.entries.iterator()
        while (shows.hasNext()) {
            val show = shows.next()
            val player = server.playerList.getPlayer(show.key)
            if (player == null || now > show.value.until || player.level() !== level) {
                shows.remove()
                continue
            }
            val found = Villages.places(level, show.value.key)?.found ?: continue
            draw(level, player, found)
        }
    }

    private class Mark(val kind: Kind, val x: Double, val y: Double, val z: Double, val d: Double)

    private fun draw(level: ServerLevel, player: ServerPlayer, f: Found) {
        val marks = ArrayList<Mark>()
        fun add(kind: Kind, p: BlockPos, up: Double) {
            if (abs(p.x - player.x) > NEAR || abs(p.z - player.z) > NEAR) return
            val dx = p.x + 0.5 - player.x
            val dz = p.z + 0.5 - player.z
            marks.add(Mark(kind, p.x + 0.5, p.y + up, p.z + 0.5, dx * dx + dz * dz))
        }
        for (b in f.buildings) {
            if (b.kind == "TOWER") continue
            val kind = if (b.beds.isEmpty()) Kind.BUILDING else Kind.DOOR
            if (b.doors.isEmpty()) add(kind, b.door, 1.0) else for (d in b.doors) add(kind, d.pos, 1.0)
            for (bed in b.beds) add(Kind.BED, bed, 0.8)
        }
        for (p in f.wells) add(Kind.WELL, p, 1.0)
        for (p in f.lamps) add(Kind.LAMP, p, 0.5)
        for (s in f.benches) add(Kind.BENCH, s.pos, 1.0)
        for (s in f.stalls) add(Kind.STALL, s.keeper.pos, 1.0)
        for (fl in f.fields) {
            val y = fl.crops.firstOrNull()?.y ?: continue
            for (cx in intArrayOf(fl.box.minX(), fl.box.maxX())) for (cz in intArrayOf(fl.box.minZ(), fl.box.maxZ())) add(Kind.FIELD, BlockPos(cx, y, cz), 0.5)
        }
        for (p in f.towers) add(Kind.TOWER, p, 1.0)
        for (j in f.jobs) add(Kind.WORK, j.pos, 1.0)
        add(Kind.GREEN, f.green, 0.5)
        marks.sortBy { it.d }
        for (m in marks.take(MAX_DRAWN)) {
            level.sendParticles(player, DustParticleOptions(m.kind.rgb, 1.2f), true, true, m.x, m.y, m.z, 4, 0.12, 0.6, 0.12, 0.0)
        }
    }
}
