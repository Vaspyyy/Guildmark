package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.economy.Town
import io.github.vaspyyy.guildmark.outlaw.Captives
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.village.live.Size
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.phys.AABB

/**
 * The [Town] a village's market serves, counted from the census and the folk (the villages design, section 7.2): everyone who
 * eats, by trade, and the barns, coops and orchards its country was found with. The people are the village's residents who
 * live, and, for every villager not adopted (one that is exempt or confined, or any at all while `villageFolk` is off), the
 * vanilla villagers and babies standing in the village's box, so a village's market runs the same whichever of the two it has.
 *
 * Nothing here loads a chunk. The villagers are read only when every chunk of the village's box is loaded; with a part of it
 * unloaded the count is not made (null), and the market keeps the town it had, which is better than a market that thinks half
 * its people have gone.
 */
object VillageTown {
    /** What a count found of the people, before it is made a [Town]. */
    class Folk {
        var people = 0
        var children = 0
        var farmers = 0
        var fishers = 0
        var bakers = 0
        var smiths = 0
        var fletchers = 0
        var guards = 0

        /** Adds one person of [role] ([fletcher] if their craft is the fletcher's, [child] if a baby). */
        fun add(role: TownRole, fletcher: Boolean = false, child: Boolean = false) {
            people++
            if (child || role == TownRole.CHILD) {
                children++
                return
            }
            when (role) {
                TownRole.FARMER -> farmers++
                TownRole.FISHER -> fishers++
                TownRole.BAKER -> bakers++
                TownRole.SMITH -> smiths++
                TownRole.GUARD -> guards++
                else -> {}
            }
            if (fletcher) fletchers++
        }
    }

    /** The orchard trees a village is taken to have, by its size (apples are a minor good; section 7.2). */
    fun trees(size: Size): Int = when (size) {
        Size.HAMLET -> 2
        Size.VILLAGE -> 4
        Size.TOWN -> 6
        Size.CITY -> 8
    }

    /** Whether [craft] (a vanilla profession's id, "minecraft:fletcher") is the fletcher's. */
    fun fletcher(craft: String?): Boolean = craft != null && craft.substringAfter(':') == "fletcher"

    /**
     * The town of [rec] as it stands: [folk] and what [places] found, with [planned] (the first town counted, or null if this is
     * the first). Its ovens are one for each smoker or furnace its bakers use, one at least.
     */
    fun town(rec: VillageRec, places: VillagePlaces, folk: Folk, planned: Town?): Town {
        val blocks = places.jobs.count { it.m == M.SMOKER || it.m == M.FURNACE }
        val ovens = maxOf(1, if (folk.bakers > 0) minOf(blocks, folk.bakers) else 0)
        return Town(
            people = folk.people, children = folk.children, farmers = folk.farmers, fishers = folk.fishers, bakers = folk.bakers,
            smiths = folk.smiths, fletchers = folk.fletchers, innkeepers = 0, guards = folk.guards, barns = places.barns,
            coops = places.coops, mills = 0, trees = trees(rec.size), planned = planned, ovens = ovens, granary = 0,
        )
    }

    /** [town] with [planned] as the town it was built for. */
    fun planning(town: Town, planned: Town?): Town = Town(
        town.people, town.children, town.farmers, town.fishers, town.bakers, town.smiths, town.fletchers, town.innkeepers, town.guards,
        town.barns, town.coops, town.mills, town.trees, planned, town.ovens, town.granary,
    )

    /**
     * The town of the village [rec] counted now, or null if its box is not all loaded (so the villagers in it can't all be seen; with [needLoaded] false it is counted from what is).
     * [planned] is the first town counted, if there is one.
     */
    fun count(level: ServerLevel, rec: VillageRec, places: VillagePlaces, planned: Town?, needLoaded: Boolean = true): Town? {
        val bell = BlockPos.of(rec.key)
        val reach = maxOf(rec.radius, VillageRec.RADIUS_LEAST)
        if (needLoaded && !loaded(level, bell, reach)) return null
        val folk = Folk()
        for (r in Residents.get(level).byCentre(bell)) {
            if (!r.alive) continue
            folk.add(r.role, fletcher(r.craft))
        }
        val box = AABB(
            (bell.x - reach).toDouble(), (bell.y - VERTICAL).toDouble(), (bell.z - reach).toDouble(),
            (bell.x + reach + 1).toDouble(), (bell.y + VERTICAL + 1).toDouble(), (bell.z + reach + 1).toDouble(),
        )
        for (v in level.getEntitiesOfClass(Villager::class.java, box)) {
            if (!v.isAlive) continue
            val craft: Identifier? = v.villagerData.profession().unwrapKey().orElse(null)?.identifier()
            folk.add(Captives.roleFor(craft), fletcher(craft?.toString()), child = v.isBaby)
        }
        return town(rec, places, folk, planned)
    }

    /** How far above and below the bell a villager is counted as the village's. */
    private const val VERTICAL = 32

    /** Whether every chunk of the square of [reach] blocks round [bell] is loaded. */
    private fun loaded(level: ServerLevel, bell: BlockPos, reach: Int): Boolean {
        for (cx in ((bell.x - reach) shr 4)..((bell.x + reach) shr 4)) {
            for (cz in ((bell.z - reach) shr 4)..((bell.z + reach) shr 4)) {
                if (!level.hasChunk(cx, cz)) return false
            }
        }
        return true
    }
}
