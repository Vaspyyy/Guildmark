package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Tier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One line of what a recipe command prints: a [label], the [text] after it and, for a line the ladle speaks, the [said]
 * sentences that follow (an empty list is a ladle with nothing to say).
 */
class ReportRow(val label: String, val text: String, val said: List<Sentence>? = null)

/** A typed list of items turned into foods and how many of each, or [error] in words. */
class ParsedItems(val parts: List<Pair<Food, Int>>, val error: String?)

/** What `ideal ... sample` found over [n] made-up worlds: the book's bill against the best mix, as mean, 10th and 90th percentile and mean mix score. */
class IdealSample(val n: Int, val mean: Double, val p10: Double, val p90: Double, val mix: Double)

/**
 * What the recipe commands print (`cook roles`, `ideal`, `rate`, `quality`, `xp`), worked out from numbers and text alone so
 * that the very same lines can be produced outside the game: the commands in [RecipeCommand] only turn a [ReportRow] into a
 * coloured line. Every number is the one the game's own functions give ([Quality], [Ideal], [Recognise], [Hints], [CookXp]).
 */
object RecipeReport {
    private val ROLE_NAMES = Role.entries.map { it.name.lowercase(Locale.ROOT) }

    /** The COOKING levels `rate` shows the ladle at, besides the player's own. */
    val HINT_LEVELS = listOf(Hints.LEVEL_CLOSE, Hints.LEVEL_ROLE, Hints.LEVEL_AMOUNT, Hints.LEVEL_TIMING, Hints.LEVEL_FORECAST)

    /**
     * What `cook_roles.py` prints (design 2.3, `roles_out.txt`) for the mean distance of each kind's standard bill from the ideal
     * over 2,000 worlds; `ideal ... sample` is held against it, and must be within [TOLERANCE].
     */
    val MODEL_MEAN: Map<DishKind, Double> = mapOf(
        DishKind.POTTAGE to 0.113, DishKind.STEW to 0.119, DishKind.CHOWDER to 0.120, DishKind.BROTH to 0.112,
        DishKind.CAKES to 0.102, DishKind.ROOTS to 0.102, DishKind.PIE to 0.113, DishKind.TART to 0.106,
        DishKind.ROAST to 0.108, DishKind.FISH to 0.107, DishKind.LOAF to 0.105, DishKind.STRIPS to 0.103,
    )

    /** How near the sampled mean must be to the model's. */
    const val TOLERANCE = 0.01

    /** The design wants the mean mix score of the standard bill between these (to two decimals). */
    const val MIX_LOW = 0.70
    const val MIX_HIGH = 0.75

    /** A number with [digits] decimals, always with a point. */
    fun f(x: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", x)

    /** A mix as percents by role: "body 22.5, meat 37.5, ...". */
    fun percent(share: DoubleArray): String =
        Role.entries.joinToString(", ") { "${ROLE_NAMES[it.ordinal]} ${f(100.0 * share.getOrElse(it.ordinal) { 0.0 }, 1)}" }

    /** A standard bill as "beef 3, carrot 2". */
    fun bill(kind: DishKind): String = kind.bill.entries.joinToString(", ") { "${it.key} ${it.value}" }

    private fun lower(tier: Tier): String = tier.name.lowercase(Locale.ROOT)

    /** "1 item", "2 items". */
    private fun plural(n: Int, one: String): String = "$n ${if (n == 1) one else one + "s"}"

    // ---- roles ----

    /** Every food of the merged table, one line each: its roles, the items and the tags that are it. */
    fun table(table: RoleTable): List<ReportRow> {
        val out = ArrayList<ReportRow>()
        out.add(ReportRow("Foods: ", "${table.foods.size}, from ${table.files} files (${table.itemCount} items, ${table.tagCount} tags)"))
        for (food in table.foods.sortedBy { it.name }) {
            val items = table.itemsOf(food.name)
            val shown = items.take(6).joinToString(", ") + if (items.size > 6) " and ${items.size - 6} more" else ""
            val tags = table.tagsOf(food.name).joinToString(", ") { "#$it" }
            val back = if (food.returns == null) "" else "; gives back ${food.returns}"
            out.add(ReportRow("${food.name}: ", "${roleList(food)} (${listOf(shown, tags).filter { it.isNotEmpty() }.joinToString("; ")}$back)"))
        }
        return out
    }

    private fun roleList(food: Food): String = food.roles.entries.joinToString(", ") { "${it.key.name.lowercase(Locale.ROOT)} ${f(100.0 * it.value, 0)}%" }

    /** What the item [id] is in a pot: its [food] in the merged table (null: not for the pot) and what it [returns]. */
    fun roles(id: String, food: Food?, returns: String?): List<ReportRow> {
        if (food == null) return listOf(ReportRow("", "That's not for the pot."))
        return listOf(
            ReportRow("Food: ", "${food.name} (from ${food.file})"),
            ReportRow("Roles: ", roleList(food)),
            ReportRow("Gives back: ", returns ?: "nothing"),
        )
    }

    // ---- ideal ----

    /** The best mix [best] of [kind] in a world of [seed], and how far the book's bill is from it. */
    fun ideal(kind: DishKind, seed: Long, best: DoubleArray): List<ReportRow> {
        val book = Standard.shares(kind)
        val tv = Quality.distance(book, best)
        return listOf(
            ReportRow("Kind: ", "${kind.id}, made at the ${kind.station.name.lowercase(Locale.ROOT)}; ideal version ${Ideal.VERSION}; world seed $seed"),
            ReportRow("Best mix (percent): ", percent(best)),
            ReportRow("The book's bill (${bill(kind)}): ", percent(book)),
            ReportRow("The bill is from the best mix by: ", "${f(tv, 3)}, a mix score of ${f(Quality.mixOf(tv), 2)}"),
        )
    }

    /** The book's bill against the ideal of [kind] over worlds of the seeds 0 until [n]. */
    fun sample(kind: DishKind, n: Int): IdealSample {
        val book = Standard.shares(kind)
        val away = DoubleArray(n) { Quality.distance(book, Ideal.make(it.toLong(), kind)) }
        var mix = 0.0
        for (d in away) mix += Quality.mixOf(d)
        val mean = away.average()
        away.sort()
        return IdealSample(n, mean, away[(n * 0.1).toInt()], away[min(n - 1, (n * 0.9).toInt())], mix / n)
    }

    /** Whether the sampled mean is within [TOLERANCE] of `cook_roles.py`'s. */
    fun meanHolds(kind: DishKind, s: IdealSample): Boolean = abs(s.mean - MODEL_MEAN.getValue(kind)) <= TOLERANCE

    /** Whether the mean mix score is the 0.70 to 0.75 the design wants, to two decimals. */
    fun mixHolds(s: IdealSample): Boolean = s.mix >= MIX_LOW - 0.005 && s.mix < MIX_HIGH + 0.005

    private fun yes(ok: Boolean): String = if (ok) "yes" else "NO"

    fun sampleRows(kind: DishKind, s: IdealSample): List<ReportRow> {
        val model = MODEL_MEAN.getValue(kind)
        return listOf(
            ReportRow("Made-up worlds: ", "${s.n}, the seeds 0 to ${s.n - 1}"),
            ReportRow("Distance of the book's bill (${bill(kind)}): ", "mean ${f(s.mean, 3)}, 10th to 90th percentile ${f(s.p10, 3)} to ${f(s.p90, 3)}"),
            ReportRow("Mean mix score of the book's bill: ", "${f(s.mix, 3)} (the design wants ${f(MIX_LOW, 2)} to ${f(MIX_HIGH, 2)}: ${yes(mixHolds(s))})"),
            ReportRow("Against cook_roles.py: ", "it prints a mean of ${f(model, 3)}; this is ${f(abs(s.mean - model), 3)} off, within ${f(TOLERANCE, 2)}: ${yes(meanHolds(kind, s))}"),
        )
    }

    // ---- rate ----

    /** Reads "beef:3 carrot:2 minecraft:honey_bottle:1 herb" into foods and counts; [itemFood] answers for an item id. */
    fun parseItems(text: String, table: RoleTable, itemFood: (String) -> Food?): ParsedItems {
        val parts = ArrayList<Pair<Food, Int>>()
        for (token in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var name = token
            var count = 1
            val colon = token.lastIndexOf(':')
            if (colon > 0) {
                val n = token.substring(colon + 1).toIntOrNull()
                if (n != null) {
                    name = token.substring(0, colon)
                    count = n
                }
            }
            if (count < 1 || count > 64) return ParsedItems(parts, "'$token' wants a count from 1 to 64.")
            val food = table.named(name) ?: itemFood(name)
                ?: return ParsedItems(parts, "'$name' is not a food of the pot (see /guildmark cook roles for the foods, or name an item like minecraft:honey_bottle).")
            parts.add(food to count)
        }
        return ParsedItems(parts, null)
    }

    /** What the vessel takes a pot of [pick]'s count for, in words, when the cook asked about [kind]. */
    private fun taken(kind: DishKind, pick: Pick, level: Int): String {
        val station = kind.station.name.lowercase(Locale.ROOT)
        val got = pick.kind
        if (got != null) {
            val name = if (got == kind) got.id else "${got.id}, not ${kind.id}"
            return "$name, ${plural(pick.portions, "portion")} (${f(pick.tv, 3)} from its standard bill, within the ${f(Recognise.RECOGNISED_TV, 2)} a pot may be)"
        }
        val least = DishKind.entries.filter { it.station == kind.station }.minOf { Standard.half(it) }
        val near = pick.nearest
        return when {
            pick.n <= 0 -> "nothing: the pot is empty"
            pick.n < least -> "gruel, ${plural(pick.portions, "portion")}: only ${plural(pick.n, "item")} in it and the $station needs $least for any dish, so there is not enough in it"
            near == null -> "gruel, ${plural(pick.portions, "portion")}: no dish of the $station is open at COOKING $level"
            else -> "gruel, ${plural(pick.portions, "portion")}: the nearest is ${near.id}, ${f(pick.tv, 3)} from its standard bill, more than ${f(Recognise.RECOGNISED_TV, 2)}"
        }
    }

    /**
     * A pot of [parts] rated as a [kind] by a cook of COOKING [level] whose bonus to the quality is [bonus]: the mix, its
     * distance from the best mix ([idealOf] gives the ideal of any kind in the world asked about), what the pot is taken for,
     * what the ladle says at each of its levels and the quality it can reach.
     */
    fun rate(kind: DishKind, parts: List<Pair<Food, Int>>, idealOf: (DishKind) -> DoubleArray, level: Int, bonus: Double): List<ReportRow> {
        val n = parts.sumOf { it.second }
        val share = RoleTable.mix(parts)
        val ideal = idealOf(kind)
        val tv = Quality.distance(share, ideal)
        val mix = Quality.mixOf(tv)
        val rows = ArrayList<ReportRow>()
        rows.add(ReportRow("The pot, ${plural(n, "item")} (${parts.joinToString(", ") { "${it.first.name} ${it.second}" }}): ", percent(share)))
        rows.add(ReportRow("Best mix of ${kind.id} here: ", percent(ideal)))
        rows.add(ReportRow("Distance: ", "${f(tv, 3)}, a mix score of ${f(mix, 2)}"))
        val pick = Recognise.decide(kind.station, n, share, level)
        rows.add(ReportRow("At COOKING $level the ${kind.station.name.lowercase(Locale.ROOT)} takes it for: ", taken(kind, pick, level)))
        val got = pick.kind
        rows.add(ReportRow("Your ladle (COOKING $level), for what it is taken for: ", "", Hints.say(level, got, share, got?.let(idealOf), null, 1.0, bonus, kind.station)))
        val before = kind.time - 6.0
        for (lv in HINT_LEVELS) {
            val b = Quality.bonus(lv, Quality.craftAt(lv))
            rows.add(ReportRow("Ladle at COOKING $lv, as ${kind.id}: ", "", Hints.say(lv, kind, share, ideal, null, 1.0, b)))
            if (lv >= Hints.LEVEL_TIMING) rows.add(ReportRow("  while it cooks, 6 s before its time: ", "", Hints.say(lv, kind, share, ideal, before, 1.0, b)))
        }
        val best = Quality.q(mix, 1.0, 1.0, 1.0, bonus)
        val steady = Quality.q(mix, 0.9, 0.9, 0.9, bonus)
        val middling = Quality.q(mix, 0.7, 0.7, 0.7, bonus)
        val cook = "at COOKING $level (bonus +${f(bonus, 4)}): "
        rows.add(
            ReportRow(
                if (got == null) "Were it taken for ${kind.id} (it is gruel, so it will not be), the quality $cook" else "Quality $cook",
                "perfect hands q ${f(best, 3)} ${lower(Quality.tier(best))}; steady hands (0.9) q ${f(steady, 3)} ${lower(Quality.tier(steady))}; " +
                    "middling hands (0.7) q ${f(middling, 3)} ${lower(Quality.tier(middling))}",
            ),
        )
        return rows
    }

    // ---- quality ----

    /** q and the tier of a batch of the given scores for a cook of COOKING [level] whose tree gives [craft]; [whose] says where the craft comes from. */
    fun quality(mix: Double, timing: Double, attention: Double, care: Double, level: Int, craft: Double, whose: String): List<ReportRow> {
        val byLevel = Quality.levelBonus(level)
        val bonus = byLevel + craft
        val q = Quality.q(mix, timing, attention, care, bonus)
        val tier = Quality.tier(q)
        val next = Tier.entries.getOrNull(tier.ordinal + 1)
        return listOf(
            ReportRow("Bonus at COOKING $level: ", "+${f(byLevel, 4)} from the level, +${f(craft, 4)} from COOK_CRAFT ($whose) = +${f(bonus, 4)}"),
            ReportRow(
                "q: ",
                "${f(Quality.W_MIX, 2)} x ${f(mix, 2)} + ${f(Quality.W_TIMING, 2)} x ${f(timing, 2)} + ${f(Quality.W_ATTENTION, 2)} x ${f(attention, 2)} + " +
                    "${f(Quality.W_CARE, 2)} x ${f(care, 2)} + ${f(bonus, 4)} = ${f(q, 4)}",
            ),
            ReportRow(
                "Tier: ",
                "${lower(tier)} (from q ${f(tier.q, 2)})" + if (next == null) "" else "; ${lower(next)} from ${f(next.q, 2)}, ${f(next.q - q, 4)} to go",
            ),
        )
    }

    // ---- xp ----

    /** What a batch of [portions] of [kind] at [tier] pays a cook who has already earned [earned] XP today. */
    fun xp(kind: DishKind, tier: Tier, portions: Int, earned: Int): List<ReportRow> {
        val batch = CookXp.batch(kind, tier, portions)
        val paid = CookXp.pay(batch, earned)
        val room = (CookXp.COOK_XP_SOFT_CAP - earned).coerceAtLeast(0).toDouble()
        val full = min(batch, room)
        val rest = batch - full
        val past = "${f(100.0 * CookXp.COOK_XP_PAST_CAP, 0)}%"
        val how = when {
            rest <= 0.0 -> "all at the full rate"
            full <= 0.0 -> "all past the cap, at $past"
            else -> "${f(full, 1)} at the full rate and ${f(rest, 1)} past the cap at $past"
        }
        return listOf(
            ReportRow("Batch: ", "$portions x (3 + 2 x ${kind.complexity}) x ${f(tier.xp, 1)} = ${f(batch, 1)} XP"),
            ReportRow("Today: ", "$earned XP earned of the ${CookXp.COOK_XP_SOFT_CAP} paid at the full rate"),
            ReportRow("It pays: ", "${paid.roundToInt()} XP ($how)"),
        )
    }
}
