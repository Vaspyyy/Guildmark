package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The cookshop, a works of the town (living-world/cook/design.md 6.4): its opening and closing, its three selling hours,
 * its keeper and its free fire. Part H's; until it lands there is no such works and these answer "none". The works
 * functions are `internal` because [WorksRec] is.
 */
object CookShops {
    /** The cooking level a cook must have before the town offers a cookshop. */
    const val LEVEL = 25

    /** The portions the shops of [entry]'s market sold in [hour]; the ledger's `Kitchens.step` asks at 8:00, 12:00 and 16:00 (stub: 0). */
    fun sellHour(level: ServerLevel, entry: Markets.Entry, hour: Long): Double = 0.0

    /** A cookshop has been built and opens (from `Works.open`; stub: nothing). */
    internal fun open(level: ServerLevel, works: WorksRec) {}

    /** A cookshop has been taken down (from `Works.close`; stub: nothing). */
    internal fun close(level: ServerLevel, works: WorksRec) {}

    /** Whether [p] keeps a cookshop, so that no market shop is made for them (stub: no). */
    fun keeps(p: Townsperson): Boolean = false

    /** Where a cookshop's keeper stands, behind the counter (stub: nowhere). */
    internal fun post(level: ServerLevel, works: WorksRec): BlockPos? = null

    /** Whether the fire at [pos] is a cookshop's, burning no fuel while it is open (stub: no). */
    fun freeAt(level: ServerLevel, pos: BlockPos): Boolean = false

    /** Whether [works] has something to sell, for its awning (stub: no). */
    internal fun stocked(level: ServerLevel, works: WorksRec): Boolean = false

    /** What `works` says of a standing cookshop (stub: nothing to say yet). */
    internal fun describe(level: ServerLevel, works: WorksRec): String = "a cookshop with nothing recorded yet"

    /** The works screen's line for a standing cookshop: the dishes in its larder and the Marks in its till (stub: none). */
    fun doing(level: ServerLevel, capital: Long, owner: UUID?): Component = Component.empty()

    /** Part H's registration: the keeper's click and the `cook shop` command (stub: nothing). */
    fun register() {}
}
