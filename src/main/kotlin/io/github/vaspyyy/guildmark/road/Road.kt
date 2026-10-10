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
) {
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
    fun connects(a: BlockPos, b: BlockPos, slack: Double): Boolean {
        fun near(p: BlockPos, q: BlockPos) = p.distToLowCornerSqr(q.x.toDouble(), p.y.toDouble(), q.z.toDouble()) <= slack * slack
        return (near(from, a) && near(to, b)) || (near(from, b) && near(to, a))
    }

    companion object {
        val CODEC: Codec<Road> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Road::id),
                BlockPos.CODEC.fieldOf("from").forGetter(Road::from),
                BlockPos.CODEC.fieldOf("to").forGetter(Road::to),
                BlockPos.CODEC.listOf().fieldOf("points").forGetter(Road::points),
                Codec.LONG.listOf().fieldOf("unpaved").forGetter { it.unpaved.toList() },
            ).apply(i) { id, from, to, points, unpaved -> Road(id, from, to, points, unpaved.toMutableSet()) }
        }
    }
}
