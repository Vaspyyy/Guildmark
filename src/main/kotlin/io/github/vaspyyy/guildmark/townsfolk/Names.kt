package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkLooks
import java.util.Random

/**
 * Townsfolk's names, put together from the sounds of the land their city stands in: a given name to suit a man or
 * a woman, and a family name a household shares. Every name is made up here, from these pieces.
 */
object Names {
    private class Tongue(
        val manStart: List<String>, val manEnd: List<String>,
        val womanStart: List<String>, val womanEnd: List<String>,
        val familyStart: List<String>, val familyEnd: List<String>,
    )

    private val TEMPERATE = Tongue(
        listOf("Al", "Bren", "Cor", "Dun", "Ed", "Fen", "Gar", "Hal", "Jor", "Ker", "Lor", "Mar", "Ned", "Os", "Per", "Ral", "Sten", "Tam", "Wil", "Rod"),
        listOf("dric", "win", "mer", "ric", "old", "bert", "ton", "wick", "an", "ard", "ley", "mund"),
        listOf("Ada", "Bel", "Cel", "Del", "El", "Fen", "Gwen", "Hel", "Is", "Jes", "Lyn", "Mae", "Nell", "Ros", "Sel", "Tes", "Wyn", "Mir", "Ann", "Bet"),
        listOf("a", "ine", "wyn", "ette", "ra", "ly", "eth", "ia", "ys", "anne", "elle", "y"),
        listOf("Ash", "Bar", "Cold", "Dun", "Fair", "Green", "Hol", "Kettle", "Lark", "Mar", "Neth", "Oak", "Pel", "Red", "Stone", "Thorn", "Wain", "Yar", "Brook", "Mill", "Hay", "Sedge"),
        listOf("ford", "row", "well", "more", "weather", "hill", "ins", "by", "fern", "brook", "wright", "low", "ley", "stead", "wood", "mere", "gate", "field", "worth", "combe"),
    )
    private val NORTHERN = Tongue(
        listOf("Ar", "Bjor", "Ei", "Fin", "Gun", "Hal", "In", "Kjel", "Ol", "Rag", "Sig", "Tor", "Ulf", "Vid", "Yng", "Hak"),
        listOf("vald", "nar", "rik", "stein", "mund", "geir", "ar", "bjorn", "leif", "ulf", "var", "e"),
        listOf("As", "Brin", "Dag", "Ei", "Frey", "Gud", "Hil", "Ing", "Lif", "Ran", "Sol", "Tho", "Vig", "Ylv", "Ast", "Sig"),
        listOf("dis", "run", "hild", "veig", "borg", "ny", "a", "ja", "rid", "lin", "unn", "e"),
        listOf("Frost", "Iron", "Elk", "Raven", "Birch", "Snow", "Wolf", "Storm", "Pine", "Stone", "Ice", "Bear"),
        listOf("vik", "dal", "heim", "berg", "fjord", "ness", "holm", "lund", "mark", "stad"),
    )
    private val DESERT = Tongue(
        listOf("Ka", "Ra", "Za", "Ha", "Na", "Sa", "Ta", "Fa", "Ja", "Ma", "Ba", "Qa"),
        listOf("dir", "shan", "mir", "lim", "ran", "hiz", "del", "vash", "rek", "nun", "sef", "tar"),
        listOf("Le", "Ya", "Su", "Na", "Ri", "Za", "Ime", "Sha", "Da", "Ama", "Ne", "Sa"),
        listOf("ra", "na", "lia", "sha", "mira", "di", "sa", "ya", "leh", "zen", "ri", "fa"),
        listOf("Sand", "Sun", "Dune", "Palm", "Salt", "Amber", "Copper", "Date", "Spice", "Well", "Mirage", "Brass"),
        listOf("walker", "keeper", "song", "ward", "mark", "born", "reach", "weaver", "bright", "stone", "drift", "wind"),
    )
    private val SAVANNA = Tongue(
        listOf("Ba", "Ko", "Mu", "Ta", "Zu", "Ndo", "Ke", "Lo", "Sa", "Wa", "Jo", "Re"),
        listOf("ru", "nda", "mbe", "lu", "shi", "ko", "ji", "ri", "to", "wa", "bo", "ne"),
        listOf("Ama", "Ise", "Nia", "Zu", "Ka", "Tem", "Ayo", "Ni", "Wa", "Lela", "Sa", "Eshe"),
        listOf("ni", "ra", "ya", "la", "ma", "si", "di", "ka", "na", "ri", "wa", "e"),
        listOf("Lion", "Acacia", "Red", "Long", "Tall", "Grass", "River", "Sky", "Thorn", "Kite", "Ochre", "Baobab"),
        listOf("grass", "runner", "shade", "walker", "horn", "song", "ward", "field", "spear", "fire", "drum", "wind"),
    )

    private fun tongue(style: CityStyle?): Tongue = when (style) {
        CityStyle.NORTHERN -> NORTHERN
        CityStyle.DESERT -> DESERT
        CityStyle.SAVANNA -> SAVANNA
        else -> TEMPERATE
    }

    /** Whether [role]'s [look] is a woman's (a child's, from [seed]). */
    fun woman(role: TownRole, look: Int, seed: Long): Boolean {
        val l = TownsfolkLooks.BY_ROLE[role.id]?.getOrNull(look) ?: return seed and 1L == 0L
        return when (l.timbre) {
            "woman" -> true
            "man" -> false
            "old" -> "beard" !in l.parts && ("skirt_l" in l.parts || "veil" in l.parts || "widow_hat" in l.parts || "hair_bun" in l.parts)
            else -> seed and 1L == 0L
        }
    }

    fun given(style: CityStyle?, woman: Boolean, seed: Long): String {
        val t = tongue(style)
        val r = Random(seed * 6364136223846793005L + 1442695040888963407L)
        return if (woman) t.womanStart[r.nextInt(t.womanStart.size)] + t.womanEnd[r.nextInt(t.womanEnd.size)]
        else t.manStart[r.nextInt(t.manStart.size)] + t.manEnd[r.nextInt(t.manEnd.size)]
    }

    fun family(style: CityStyle?, seed: Long): String {
        val t = tongue(style)
        val r = Random(seed * 2862933555777941757L + 3037000493L)
        val start = t.familyStart[r.nextInt(t.familyStart.size)]
        var end = t.familyEnd[r.nextInt(t.familyEnd.size)]
        // Not "Millmill" or "Brookbrook"
        if (end.equals(start, ignoreCase = true)) end = t.familyEnd[(t.familyEnd.indexOf(end) + 1) % t.familyEnd.size]
        return start + end
    }
}
