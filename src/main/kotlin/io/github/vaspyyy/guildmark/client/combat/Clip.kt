package io.github.vaspyyy.guildmark.client.combat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.Mth
import net.minecraft.util.profiling.ProfilerFiller
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * One animation, baked by art/scripts/animations.py into samples [rate] times a tick. Each bone holds a
 * rotation (a quaternion, relative to its parent in the combat skeleton) and an offset in model pixels.
 */
class Clip(val length: Float, val rate: Int, val blendIn: Float, val blendOut: Float, val bones: Map<String, Track>) {
    class Track(
        /** Added on top of the pose the game gives the bone (the head keeps looking where the player looks). */
        val add: Boolean,
        private val rot: FloatArray?,
        private val pos: FloatArray?,
    ) {
        fun rotation(clip: Clip, time: Float, out: Quaternionf): Quaternionf {
            val r = rot ?: return out.identity()
            val count = r.size / 4
            val at = clip.sample(time, count)
            val i = at.toInt()
            val f = at - i
            val j = minOf(i + 1, count - 1)
            out.set(r[i * 4], r[i * 4 + 1], r[i * 4 + 2], r[i * 4 + 3])
            if (f > 0f && j != i) out.slerp(NEXT.set(r[j * 4], r[j * 4 + 1], r[j * 4 + 2], r[j * 4 + 3]), f)
            return out
        }

        fun position(clip: Clip, time: Float, out: Vector3f): Vector3f {
            val p = pos ?: return out.zero()
            val count = p.size / 3
            val at = clip.sample(time, count)
            val i = at.toInt()
            val f = at - i
            val j = minOf(i + 1, count - 1)
            return out.set(
                Mth.lerp(f, p[i * 3], p[j * 3]),
                Mth.lerp(f, p[i * 3 + 1], p[j * 3 + 1]),
                Mth.lerp(f, p[i * 3 + 2], p[j * 3 + 2]),
            )
        }

        val hasPosition: Boolean get() = pos != null

        private companion object {
            /** The sample after, for blending toward (the render thread only, so one will do). */
            val NEXT = Quaternionf()
        }
    }

    /** Where [time] falls among [count] samples: the whole part is the sample before it, the rest how far toward the next. */
    private fun sample(time: Float, count: Int): Float = (time * rate).coerceIn(0f, (count - 1).toFloat())

    /** How strongly the clip shows at [time]: it eases in from the pose before and out to the pose after. */
    fun weight(time: Float): Float {
        val fadeIn = if (blendIn > 0f) (time / blendIn).coerceIn(0f, 1f) else 1f
        val fadeOut = if (blendOut > 0f) ((length - time) / blendOut).coerceIn(0f, 1f) else 1f
        return smooth(fadeIn) * smooth(fadeOut)
    }

    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    companion object {
        fun parse(json: JsonObject): Clip {
            val bones = HashMap<String, Track>()
            json.getAsJsonObject("bones")?.entrySet()?.forEach { (name, element) ->
                val bone = element.asJsonObject
                val rot = bone.getAsJsonArray("rot")?.let { array -> FloatArray(array.size()) { array[it].asFloat } }
                val pos = bone.getAsJsonArray("pos")?.let { array -> FloatArray(array.size()) { array[it].asFloat } }
                bones[name] = Track(bone.get("mode")?.asString == "add", rot, pos)
            }
            return Clip(
                json.get("length").asFloat,
                json.get("rate")?.asInt ?: 2,
                json.get("blend_in")?.asFloat ?: 1.5f,
                json.get("blend_out")?.asFloat ?: 3f,
                bones,
            )
        }
    }
}

/** Every clip under assets/guildmark/animations/, reloaded with the resource packs (F3+T). */
object Clips : SimplePreparableReloadListener<Map<String, Clip>>() {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "combat_animations")
    private var clips: Map<String, Clip> = emptyMap()

    /** The clip at animations/[name].json, for example "player/light_1". */
    fun get(name: String): Clip? = clips[name]

    override fun prepare(manager: ResourceManager, profiler: ProfilerFiller): Map<String, Clip> {
        val loaded = HashMap<String, Clip>()
        val found = manager.listResources("animations") { it.namespace == Guildmark.MOD_ID && it.path.endsWith(".json") }
        for ((id, resource) in found) {
            val name = id.path.removePrefix("animations/").removeSuffix(".json")
            try {
                resource.openAsReader().use { reader -> loaded[name] = Clip.parse(JsonParser.parseReader(reader).asJsonObject) }
            } catch (e: Exception) {
                Guildmark.LOGGER.warn("Couldn't read animation {}: {}", id, e.toString())
            }
        }
        return loaded
    }

    override fun apply(prepared: Map<String, Clip>, manager: ResourceManager, profiler: ProfilerFiller) {
        clips = prepared
    }
}
