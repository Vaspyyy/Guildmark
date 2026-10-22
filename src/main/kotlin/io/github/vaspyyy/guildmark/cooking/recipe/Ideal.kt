package io.github.vaspyyy.guildmark.cooking.recipe

import com.google.gson.JsonParser
import com.mojang.logging.LogUtils
import com.mojang.serialization.JsonOps
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.server.level.ServerLevel
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max

/**
 * The best mix of each kind in this world (living-world/cook/design.md 2.3): worked out from the world seed, the kind's
 * id and [VERSION], never saved, the same for everyone in a world and the same in any world of the same seed.
 *
 * **The formula is written here once and never changes**, because changing any of it would move every cook's target in
 * a world already played (design 9.1). It is the rule of `cook_roles.ideal`, with `java.util.Random` for the draws:
 *
 *  1. `h` is the 64-bit FNV-1a hash of the kind's id (its UTF-8 bytes; offset basis 0xcbf29ce484222325, prime 0x100000001b3).
 *  2. `seed = mix(mix(worldSeed) xor h xor VERSION)`, where `mix(x)` is the finalizer of splitmix64 applied to `x + 0x9E3779B97F4A7C15`
 *     (`z = (z xor (z ushr 30)) * 0xbf58476d1ce4e5b9; z = (z xor (z ushr 27)) * 0x94d049bb133111eb; z xor (z ushr 31)`).
 *     The world seed is the overworld's, so every dimension of a world agrees.
 *  3. `random = java.util.Random(seed)`; for each [Role] in order, `move = random.nextGaussian() * 0.055` ([SIGMA]);
 *     a role whose share in the kind's standard bill is 0 may only move up, by half the size of its draw (`abs(move) / 2`);
 *     `value = max(0, standard share + move)`.
 *  4. The seven values are divided by their sum.
 *
 * The standard shares are those of the kind's bill (Appendix B.3) in the book's own role data, [Standard]. What a
 * `java.util.Random` draws from a seed is fixed by the Java specification, so the same seed gives the same ideal on any
 * machine and any Java.
 */
object Ideal {
    /** Frozen at 1: it is part of the seed of every ideal. */
    const val VERSION = 1

    /** The spread of the move of each role's share (a normal draw's standard deviation). */
    const val SIGMA = 0.055

    /** How many ideals [of] has worked out since the game started, for `cook perf`. */
    var made = 0L
        private set

    private val ROLES = Role.entries.size

    /** The share of each role, in [Role] order and summing to 1, that [kind] is best made with in [level]'s world. */
    fun of(level: ServerLevel, kind: DishKind): DoubleArray {
        made++
        return make(worldSeed(level), kind)
    }

    /** The seed of [level]'s world: the overworld's, so that every dimension of a world has the same ideals. */
    fun worldSeed(level: ServerLevel): Long = level.server.overworld().seed

    /** The ideal of [kind] in a world of [worldSeed] (a pure function of the two and [VERSION]). */
    fun make(worldSeed: Long, kind: DishKind): DoubleArray = make(worldSeed, kind.id, Standard.raw(kind))

    /** The same for a kind of id [kindId] whose standard bill makes the shares [base] (in [Role] order). */
    fun make(worldSeed: Long, kindId: String, base: DoubleArray): DoubleArray {
        val random = java.util.Random(seedOf(worldSeed, kindId))
        val out = DoubleArray(ROLES)
        for (i in 0 until ROLES) {
            val standard = base.getOrElse(i) { 0.0 }
            var move = random.nextGaussian() * SIGMA
            if (standard <= 0.0) move = abs(move) * 0.5
            out[i] = max(0.0, standard + move)
        }
        val total = out.sum()
        if (total <= 0.0) return base.copyOf(ROLES)
        for (i in 0 until ROLES) out[i] /= total
        return out
    }

    /** The seed of the draws for [kindId] in a world of [worldSeed] (steps 1 and 2 of the formula). */
    fun seedOf(worldSeed: Long, kindId: String): Long {
        var h = FNV_BASIS
        for (b in kindId.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xffL)) * FNV_PRIME
        return mix(mix(worldSeed) xor h xor VERSION.toLong())
    }

    private const val FNV_PRIME = 0x100000001b3L
    private val FNV_BASIS = 0xcbf29ce484222325uL.toLong()
    private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
    private val MUL_A = 0xbf58476d1ce4e5b9uL.toLong()
    private val MUL_B = 0x94d049bb133111ebuL.toLong()

    private fun mix(x: Long): Long {
        var z = x + GOLDEN
        z = (z xor (z ushr 30)) * MUL_A
        z = (z xor (z ushr 27)) * MUL_B
        return z xor (z ushr 31)
    }
}

/**
 * The book's standard bills (design 2.1, Appendix B.3) as shares of the seven roles: what a dish's hidden ideal is moved
 * from and what a pot is taken for. They are worked out from the roles file the mod ships, `guildmark:base`, read from
 * the mod itself and not from the datapacks, so that a pack that changes what a carrot is worth in a pot cannot move
 * the target of a world already played (design 9.1). Only if the mod's own file cannot be read are the loaded foods
 * used (and then nothing is kept, so the next reload is looked at again).
 */
object Standard {
    private val LOGGER = LogUtils.getLogger()
    private const val BASE = "/data/guildmark/cook_roles/base.json"

    @Volatile
    private var cached: Map<DishKind, DoubleArray>? = null

    /** The number of items in [kind]'s standard bill. */
    fun count(kind: DishKind): Int = kind.bill.values.sum()

    /** The least number of items a pot may hold and still be taken for [kind]'s station's smallest dish (half the standard count, rounded up). */
    fun half(kind: DishKind): Int = (count(kind) + 1) / 2

    /** The share of each role, in [Role] order and summing to 1, in [kind]'s standard bill (all zero if the foods could not be read). */
    fun shares(kind: DishKind): DoubleArray = raw(kind).copyOf()

    /** As [shares], without a copy: the caller must not change it. */
    internal fun raw(kind: DishKind): DoubleArray = (cached ?: build())[kind] ?: DoubleArray(Role.entries.size)

    private fun build(): Map<DishKind, DoubleArray> {
        val frozen = readBase()
        val table = frozen ?: Roles.current()
        val out = HashMap<DishKind, DoubleArray>()
        for (kind in DishKind.entries) {
            val parts = ArrayList<Pair<Food, Int>>()
            for ((name, n) in kind.bill) {
                val food = table.named(name) ?: break
                parts.add(food to n)
            }
            if (parts.size == kind.bill.size) out[kind] = RoleTable.mix(parts)
        }
        if (frozen != null) cached = out
        return out
    }

    /** The mod's own `cook_roles/base.json`, merged on its own; null if it cannot be read. */
    fun readBase(): RoleTable? = try {
        Standard::class.java.getResourceAsStream(BASE)?.use { stream ->
            val json = JsonParser.parseReader(InputStreamReader(stream, Charsets.UTF_8))
            val file = FoodFile.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null)
            if (file == null) null else RoleTable.merge(listOf("guildmark:base" to file.foods), { true }) { LOGGER.warn("{}", it) }
        }
    } catch (e: Exception) {
        LOGGER.error("The standard bills could not be read from {}: {}", BASE, e.toString())
        null
    }
}
