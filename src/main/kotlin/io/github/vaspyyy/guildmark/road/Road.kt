package io.github.vaspyyy.guildmark.road

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A road between two village centres. [points] are waypoints a few blocks apart, from [from] to [to];
 * [unpaved] holds the chunks the road crosses that haven't been loaded and paved yet.
 */
class Road(
    val id: Int,
    val from: BlockPos,
    val to: BlockPos,
    val points: List<BlockPos>,
    val unpaved: MutableSet<Long>,
    /** Being built all at once by a Trade Road project: chunks get loaded for it rather than waited on. */
    var underConstruction: Boolean = false,
) {
    val finished: Boolean get() = unpaved.isEmpty()

    /** Does this road start or end at this village centre? */
    fun touches(center: BlockPos, slack: Double = 64.0): Boolean = near(from, center, slack) || near(to, center, slack)

    /** The far end of the road, seen from [center]. */
    fun otherEnd(center: BlockPos): BlockPos = if (center.distSqr(from) <= center.distSqr(to)) to else from

    /** Waypoints in walking order, starting from the end nearest [start]. */
    fun pointsFrom(start: BlockPos): List<BlockPos> = if (start.distSqr(from) <= start.distSqr(to)) points else points.reversed()

    /** Road columns (x, z) grouped by chunk, three blocks wide. Worked out once per session. */
    val columnsByChunk: Map<Long, List<Pair<Int, Int>>> by lazy {
        val columns = LinkedHashSet<Pair<Int, Int>>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dx = (b.x - a.x).toDouble()
            val dz = (b.z - a.z).toDouble()
            val length = max(1.0, Math.sqrt(dx * dx + dz * dz))
            // Unit step along the segment and the sideways offset for the road's width
            val sx = -dz / length
            val sz = dx / length
            val steps = (length * 2).toInt()
            for (s in 0..steps) {
                val t = s / steps.toDouble().coerceAtLeast(1.0)
                val x = a.x + dx * t
                val z = a.z + dz * t
                for (w in -1..1) columns.add(Pair((x + sx * w).roundToInt(), (z + sz * w).roundToInt()))
            }
        }
        columns.groupBy { (x, z) -> ChunkPos.pack(x shr 4, z shr 4) }
    }

    /** Is this road between these two places (in either direction)? */
    fun connects(a: BlockPos, b: BlockPos, slack: Double): Boolean =
        (near(from, a, slack) && near(to, b, slack)) || (near(from, b, slack) && near(to, a, slack))

    private fun near(p: BlockPos, q: BlockPos, slack: Double): Boolean {
        val dx = (p.x - q.x).toDouble()
        val dz = (p.z - q.z).toDouble()
        return dx * dx + dz * dz <= slack * slack
    }

    companion object {
        val CODEC: Codec<Road> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Road::id),
                BlockPos.CODEC.fieldOf("from").forGetter(Road::from),
                BlockPos.CODEC.fieldOf("to").forGetter(Road::to),
                BlockPos.CODEC.listOf().fieldOf("points").forGetter(Road::points),
                Codec.LONG.listOf().fieldOf("unpaved").forGetter { it.unpaved.toList() },
                Codec.BOOL.optionalFieldOf("under_construction", false).forGetter(Road::underConstruction),
            ).apply(i) { id, from, to, points, unpaved, building -> Road(id, from, to, points, unpaved.toMutableSet(), building) }
        }
    }
}
