package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenRumours
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * Whose a Stewpot is (living-world/cook/design.md 7.3, 6.5): [PLAYER] any ordinary pot, [GUEST] the tavern's back pot, a
 * real vessel that only a player the capital knows may use and whose fire is the inn's, and [HOUSE] the innkeeper's own,
 * which is a display and not a vessel. The ordinal is what a [StationView.mode] sends: 0 player, 1 house, 2 guest.
 */
enum class StewpotMode { PLAYER, HOUSE, GUEST }

/**
 * The Stewpot's vessel (living-world/cook/design.md 1.2). Besides the vessel it has a [mode] and, in [StewpotMode.HOUSE],
 * a display the innkeeper's scene drives through [showHouse].
 */
class StewpotBlockEntity(pos: BlockPos, state: BlockState) : VesselBlockEntity(CookRegistry.STEWPOT_ENTITY.get(), pos, state, Station.STEWPOT) {
    companion object {
        /** The house pot simmers from this hour of the town's clock... */
        const val HOUSE_FROM = 10.0

        /** ...to this one. */
        const val HOUSE_TO = 24.0

        /** The house pot's surface when it starts simmering, in steps of the surface, and when the evening ends. */
        const val HOUSE_FULL = 8
        const val HOUSE_LOW = 3

        /** The house pot's own pottage, as a colour, until a dish of a player's is the one the inn serves. */
        const val HOUSE_RGB = 0xB89A58

        private val FROM = Days.clock(HOUSE_FROM)
        private val TO = Days.clock(HOUSE_TO)
    }

    /** Whose pot this is. A setter marks it for the clients and the save. */
    var mode: StewpotMode = StewpotMode.PLAYER
        set(value) {
            if (field == value) return
            field = value
            vessel.dirty = true
            setChanged()
            (level as? ServerLevel)?.let { settle() }
        }

    /** The bowls on the house pot's shelf and the colour of what the inn is serving (0 is the house pottage), as the scene last set them. */
    private var houseBowls = 0
    private var houseTint = 0

    /** What the house display last showed, so it is sent only when it changes. */
    private var houseShown = 0L

    /**
     * The innkeeper's scene sets what the house pot shows (7.3): the surface swirls until game time [swirlUntil], and
     * [bowls] stand on the shelf. A pot that is not in [StewpotMode.HOUSE] ignores it.
     */
    fun showHouse(swirlUntil: Long, bowls: Int) {
        if (mode != StewpotMode.HOUSE) return
        val b = bowls.coerceIn(0, 8)
        if (this.swirlUntil == swirlUntil && houseBowls == b) return
        this.swirlUntil = swirlUntil
        houseBowls = b
        vessel.dirty = true
        setChanged()
        (level as? ServerLevel)?.let { settle() }
    }

    /** What the inn is serving tonight, as a colour (the dish a player sold, else 0 for the house's own pottage). */
    fun showHouseTint(rgb: Int) {
        if (houseTint == rgb) return
        houseTint = rgb
        vessel.dirty = true
        setChanged()
        (level as? ServerLevel)?.let { settle() }
    }

    // ---- The house display ----

    override val viewMode: Int get() = mode.ordinal

    override val viewBowls: Int get() = if (mode == StewpotMode.HOUSE) houseBowls else 0

    /** Whether the house pot is simmering now: from [HOUSE_FROM] to [HOUSE_TO] on the town's clock. */
    private fun simmering(level: Level?): Boolean {
        if (level == null) return false
        val offset = Days.offsetOf(level.overworldClockTime)
        return offset >= FROM && offset < TO
    }

    /** The house pot's surface: from full down to [HOUSE_LOW] through the evening. */
    private fun houseWater(level: Level?): Int {
        if (level == null) return HOUSE_FULL
        val span = (TO - FROM).toDouble().coerceAtLeast(1.0)
        val done = ((Days.offsetOf(level.overworldClockTime) - FROM).toDouble() / span).coerceIn(0.0, 1.0)
        return (HOUSE_FULL - Math.round(done * (HOUSE_FULL - HOUSE_LOW)).toInt()).coerceIn(HOUSE_LOW, HOUSE_FULL)
    }

    override val shownLit: Boolean get() = if (mode == StewpotMode.HOUSE) simmering(level) else vessel.lit

    override fun buildView(level: Level?): StationView {
        if (mode != StewpotMode.HOUSE) return super.buildView(level)
        val on = simmering(level)
        return StationView(
            Station.STEWPOT, if (on) Vessel.State.COOKING.ordinal else Vessel.State.EMPTY.ordinal, 0, on, false, if (on) houseWater(level) else 0,
            IntArray(0), if (houseTint != 0) houseTint else HOUSE_RGB, 0, 0, 0, 0L, 0L, 0L, 1, swirlUntil, houseBowls, false,
        )
    }

    override fun busy(now: Long): Boolean = mode == StewpotMode.HOUSE || super.busy(now)

    /** The house pot is worked out once a second: when it begins or stops simmering, or its surface falls a step, the clients are told. */
    override fun tickVessel(level: ServerLevel, now: Long): Boolean {
        if (mode != StewpotMode.HOUSE) return true
        if (now % 20L != 0L) return false
        val on = simmering(level)
        val shown = (if (on) 1L else 0L) or (houseWater(level).toLong() shl 1)
        if (shown != houseShown) {
            houseShown = shown
            vessel.dirty = true
        }
        settle()
        return false
    }

    override fun saveExtra(output: ValueOutput) {
        if (mode != StewpotMode.PLAYER) output.putString("mode", mode.name.lowercase())
        if (houseBowls != 0) output.putInt("houseBowls", houseBowls)
        if (houseTint != 0) output.putInt("houseTint", houseTint)
    }

    override fun loadExtra(input: ValueInput) {
        val name = input.getStringOr("mode", "player")
        mode = StewpotMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: StewpotMode.PLAYER
        houseBowls = input.getIntOr("houseBowls", 0).coerceIn(0, 8)
        houseTint = input.getIntOr("houseTint", 0)
    }

    // ---- What a click does (1.5) ----

    override fun use(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack, region: Region): Used {
        if (stack.isEmpty) return emptyHand(level, player, region)
        if (stack.`is`(Items.WATER_BUCKET)) return Actions.pour(this, level, player, hand)
        if (Actions.isLadle(stack)) return ladle(level, player, hand, stack)
        if (Actions.toolOf(stack) != null) {
            say(player, "wants_ladle")
            return Used.REFUSED
        }
        if (region == Region.FIRE) {
            say(player, "not_fuel")
            return Used.REFUSED
        }
        return Actions.ingredient(this, level, player, stack)
    }

    /**
     * The ladle on the pot: sneaking it tastes; on a pot still open the first stir starts the clock; on one cooking it answers a
     * STICK (stir) or a SKIM, and stirs for nothing otherwise; on a ready, overdone or served one it serves a portion.
     */
    private fun ladle(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack): Used {
        val v = vessel
        if (player.isShiftKeyDown) return Serving.taste(this, level, player)
        if (v.open) {
            val used = Actions.start(this, level, player)
            if (used == Used.DONE) {
                stir(level, player, CookAction.STIR, 1.0f)
                Actions.wear(player, hand, stack)
            }
            return used
        }
        val pending = v.call
        if (pending == Call.STICK || pending == Call.SKIM) {
            Actions.answer(this, level, player, Call.STICK, Call.SKIM)
            if (pending == Call.SKIM) {
                ring(level, "cook.skim", 1.0f, 1.0f)
                stir(level, player, CookAction.SKIM, 0.0f)
            } else {
                stir(level, player, CookAction.STIR, 1.0f)
            }
            Actions.wear(player, hand, stack)
            return Used.DONE
        }
        return when (v.state) {
            Vessel.State.READY, Vessel.State.DONE, Vessel.State.SERVED -> {
                val used = Serving.serve(this, level, player, CookAction.SERVE)
                if (used == Used.DONE) Actions.wear(player, hand, stack)
                used
            }
            Vessel.State.BURNT -> {
                say(player, "burnt")
                Used.REFUSED
            }
            else -> {
                stir(level, player, CookAction.STIR, 1.0f)
                Used.DONE
            }
        }
    }

    /** The surface swirls (and the ladle scrapes the iron if [stirVolume] is not 0). */
    private fun stir(level: ServerLevel, player: ServerPlayer, action: CookAction, stirVolume: Float) {
        swirl(level.gameTime + STIR_TICKS)
        if (stirVolume > 0.0f) ring(level, "cook.stir", stirVolume, 1.0f)
        puff(level, CookParticles.STEAM.get(), 3, 0.2)
        CookActions.play(player, action, blockPos)
    }

    // ---- Whose pot it is ----

    override fun gate(level: ServerLevel, player: ServerPlayer, stack: ItemStack, region: Region): Boolean {
        when (mode) {
            StewpotMode.HOUSE -> {
                say(player, "house")
                return false
            }
            StewpotMode.GUEST -> if (!knows(level, player)) {
                say(player, "guest_no")
                return false
            }
            StewpotMode.PLAYER -> {}
        }
        return super.gate(level, player, stack, region)
    }

    /** Whether the capital this pot stands in knows [player] well enough to lend the back pot: Known standing or better (6.5). With standing off, or out of any capital, it lends it to anyone. */
    private fun knows(level: ServerLevel, player: ServerPlayer): Boolean {
        if (!Suppliers.enabled()) return true
        val capital = Capitals.covering(level, blockPos) ?: return true
        return Suppliers.rank(level, capital.center, player.uuid) >= SupplierRank.KNOWN.ordinal
    }

    // ---- A burnt pot at the inn is gossip ----

    override fun onBurnt(level: ServerLevel) {
        if (mode == StewpotMode.GUEST && vessel.portions >= 6) KitchenRumours.burnt(level, blockPos)
    }
}
