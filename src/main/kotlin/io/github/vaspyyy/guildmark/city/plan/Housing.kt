package io.github.vaspyyy.guildmark.city.plan

/**
 * Where everyone a city's plan sets down lives and sleeps (see [Census]). Most live where the plan put them, but
 * a stall's keeper, the watch at the gates, the farmers by their compost heaps, the priests and the scholar were
 * given nowhere to sleep: they're found a home, their own trade's building if it has a bed free, else the nearest
 * empty house (the farmers, the nearest cottage or farmhouse), a few to a house. Beds go to the grown-ups first;
 * anyone left over sleeps in a bed still to be made up in their home ([Home.bed] null). The same plan always
 * gives the same answer.
 */
class Housing(val census: Census) {
    /** Where one of the plan's residents lives ([lodge], a [Census.lodges] id) and the bed that's theirs, if one stands ready. */
    class Home(val lodge: Int, val bed: Census.Bed?)

    /** Each of [Census.residents], in order. */
    val homes: List<Home>

    /** Lodges people were moved into, and how many each took in. */
    val lodgers = HashMap<Int, Int>()

    init {
        val n = census.residents.size
        val lodgeOf = IntArray(n) { census.residents[it].lodge }
        val taken = HashMap<Int, Int>()
        for (i in 0 until n) if (lodgeOf[i] >= 0) taken.merge(lodgeOf[i], 1, Int::plus)

        // Empty houses: nobody planned in them and no villager either
        val villagerLodges = census.villagers.mapNotNull { census.lodgeAt(it.x, it.z)?.id }.toHashSet()
        val empty = census.lodges.values.filter { it.kind == Kind.HOUSE.name && it.id !in taken && it.id !in villagerLodges }.toMutableList()

        fun nearest(from: At, among: List<Census.Lodge>): Census.Lodge? = among.minByOrNull {
            val cx = (it.lot.x0 + it.lot.x1) / 2 - from.x
            val cz = (it.lot.z0 + it.lot.z1) / 2 - from.z
            cx.toLong() * cx + cz.toLong() * cz + 16L * kotlin.math.abs(it.y - from.y)
        }

        for (i in 0 until n) {
            val r = census.residents[i]
            val own = lodgeOf[i].takeIf { it >= 0 }?.let { census.lodges[it] }
            val where = r.post ?: r.work ?: r.at
            val needsHome = when {
                own == null -> true
                own.kind in NOT_HOMES -> own.beds.size < (taken[own.id] ?: 0)
                else -> false
            }
            if (!needsHome) continue
            val lodge = when (r.folk) {
                Folk.FARMER -> nearest(where, census.lodges.values.filter { it.kind == FarmKind.COTTAGE.name || it.kind == FarmKind.FARMHOUSE.name })
                else -> null
            } ?: nearest(where, empty.filter { (lodgers[it.id] ?: 0) < SHARE }) ?: continue
            if (own != null) taken.merge(own.id, -1, Int::plus)
            lodgeOf[i] = lodge.id
            taken.merge(lodge.id, 1, Int::plus)
            lodgers.merge(lodge.id, 1, Int::plus)
        }

        // Beds: grown-ups first, in plan order
        val beds = HashMap<Int, ArrayDeque<Census.Bed>>()
        val result = arrayOfNulls<Home>(n)
        val order = (0 until n).sortedBy { if (census.residents[it].folk == Folk.CHILD) 1 else 0 }
        for (i in order) {
            val id = lodgeOf[i]
            if (id < 0) {
                result[i] = Home(-1, null)
                continue
            }
            val free = beds.getOrPut(id) { ArrayDeque(census.lodges[id]?.beds ?: listOf()) }
            result[i] = Home(id, free.removeFirstOrNull())
        }
        homes = result.map { it!! }
    }

    companion object {
        /** Buildings people work in but don't live in, unless there's a bed for them there. */
        val NOT_HOMES = setOf(Kind.MARKET_HALL.name, Kind.LIBRARY.name, Kind.CATHEDRAL.name, Kind.TEMPLE.name, Kind.GUILD_HALL.name, Kind.WORKSHOP.name)

        /** How many people moved into one empty house at most. */
        const val SHARE = 3
    }
}
