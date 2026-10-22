package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.dish.Journal
import io.github.vaspyyy.guildmark.cooking.recipe.Ideal
import io.github.vaspyyy.guildmark.cooking.recipe.Quality
import io.github.vaspyyy.guildmark.cooking.recipe.Recognise
import io.github.vaspyyy.guildmark.cooking.recipe.Roles
import io.github.vaspyyy.guildmark.party.Parties
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A fire with a vessel on it (living-world/cook/design.md 1.1 to 1.8): the Stewpot, the Hob, the Bake oven and the Spit are
 * each one of these. It holds the [vessel], works it up to date when it is ticked (only with a player within 16 blocks, every
 * second tick) or touched, plays what the vessel reports (sounds, smoke, the cue of a call), keeps the block state's `lit`,
 * `notch` and `burnt` as the vessel has them, and sends the clients a compact [StationView] and nothing else: the hidden
 * distance from the ideal, the contributors and the clocks stay on the server and in the save. What a click does is the
 * station's own ([use]); this class is what they share: the fire, the heat control, the calls' sounds, the sync.
 */
open class VesselBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState, station: Station) :
    StationBlockEntity(type, pos, state, station), StationUse {

    companion object {
        // ---- The numbers (1.5, 1.6, 1.8, 9.5) ----

        /** Ticks the oven door stays open after a click or an item put in (1.6: "shuts ... after 40 ticks"). */
        const val DOOR_TICKS = 40L

        /** Ticks the surface swirls after a stir or a scrape. */
        const val STIR_TICKS = 30L

        /** Ticks the spit turns after a quarter turn of the crank. */
        const val CRANK_TICKS = 24L

        /** Ticks one sneak-use waits for the second that tips out a dish worth keeping (4 s). */
        const val TIP_CONFIRM_TICKS = 80L

        /** The vessel is worked up to date every this many game ticks while a player is near (1.2: every second tick). */
        const val TICK_EVERY = 2L

        /** How often the station looks for a player near it, ticks (once a second, 9.5). */
        const val NEAR_CHECK_TICKS = 20L

        /** A station is awake when a player is this near, blocks (1.2). */
        const val NEAR_BLOCKS = 16.0

        /** The colour of plain water in the pot, which the soup's colour is blended toward (1.5 row 3). */
        const val WATER_RGB = 0x4A7FBF

        /** The colour of the liquid of each role, in [Role] order (BODY, MEAT, SEA, GREEN, SWEET, RICH, SAVOUR). */
        val ROLE_RGB: IntArray = intArrayOf(0xD8C690, 0x9A5A3C, 0x8FB2B8, 0x6F9A45, 0xD79A3E, 0xE6D49A, 0x7B5A36)

        /** The colour a pot with items that no role names takes (a broth). */
        const val BROTH_RGB = 0xB08850

        /** How many items it takes to colour the liquid fully. */
        const val TINT_ITEMS = 6.0
    }

    /** The vessel this fire has under it. */
    val vessel: Vessel = Vessel(station)

    /** Who set the station down (for private pots); saved. */
    var placer: UUID? = null
        set(value) {
            field = value
            if (level != null) setChanged()
        }

    /** The last player to touch it: a call goes to them if they are about as near as anyone. */
    protected var lastTouch: UUID? = null

    /** The game time until which a stir, an open door or a crank turn is shown (the view's `swirlUntil`); saved. */
    var swirlUntil = 0L
        protected set

    /** The last item put in (its raw id) and a counter that goes up with each, so every client plays a plop for each new one. */
    protected var added = 0
    protected var addedCount = 0

    /** The vessel shows a stir, an open door or a turning crank until game time [until]. */
    internal fun swirl(until: Long) {
        swirlUntil = until
        vessel.dirty = true
    }

    /** An item went in: every client plays a plop for it (the view's `added` and its counter). */
    internal fun noteAdded(stack: ItemStack) {
        added = BuiltInRegistries.ITEM.getId(stack.item)
        addedCount++
        vessel.dirty = true
    }

    /** Whether the fire is free as last asked; not saved, asked again at most once a second while lit (1.3). */
    protected var freeFire = false
    private var freeAsked = -1_000_000L

    /** Whether a player was near as last looked, and when. */
    private var near = false
    private var nearAsked = -1_000_000L

    /** The client's copy of the view (a client block entity has nothing else), and whether the tag being written is the clients'. */
    private var clientView: StationView? = null
    private var clientTag = false

    /** Who asked to tip out a dish worth keeping, and until when the second sneak-use is waited for. */
    protected var tipBy: UUID? = null
    protected var tipUntil = 0L

    // ---- What the vessel needs of the world ----

    /** Whether a clockwork jack turns this station's rod (only the Spit has one). */
    protected open val jackTurns: Boolean get() = false

    internal val env: Vessel.Env = object : Vessel.Env {
        override val random: RandomSource get() = this@VesselBlockEntity.level?.random ?: RandomSource.create()

        override fun free(): Boolean = freeFire

        override val jack: Boolean get() = jackTurns

        override fun callee(now: Long): UUID? = this@VesselBlockEntity.callee(now)
    }

    /** The player a call goes to at game time [now]: the nearest within 16 blocks who has no call to answer; whoever touched it last counts as two blocks nearer. */
    private fun callee(now: Long): UUID? {
        val level = level as? ServerLevel ?: return null
        val x = blockPos.x + 0.5
        val y = blockPos.y + 0.5
        val z = blockPos.z + 0.5
        val limit = Calls.NEAR_BLOCKS * Calls.NEAR_BLOCKS
        var best: ServerPlayer? = null
        var bestD = Double.MAX_VALUE
        for (p in level.players()) {
            if (!p.isAlive || p.isSpectator) continue
            var d = p.distanceToSqr(x, y, z)
            if (d > limit) continue
            if (Calls.isBusy(p.uuid, now)) continue
            if (p.uuid == lastTouch) {
                val r = max(0.0, sqrt(d) - 2.0)
                d = r * r
            }
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        return best?.uuid
    }

    // ---- What the station shows the clients ----

    /** The mode a view reports: 0 a player's, 1 the house's, 2 a guest's (only the Stewpot is anything but 0). */
    protected open val viewMode: Int get() = 0

    /** Bowls set out, as a view reports them (only the house pot's shelf has any). */
    protected open val viewBowls: Int get() = 0

    /** Whether a view reports the spit as turning on its own (the jack's turning, or a keeper's). */
    protected open val viewTurning: Boolean get() = false

    /** Whether the fire is shown lit (the house pot shows it by the hour and not by its vessel). */
    protected open val shownLit: Boolean get() = vessel.lit

    /** What a client sees now, from the vessel. */
    protected open fun buildView(level: Level?): StationView {
        val v = vessel
        val now = level?.gameTime ?: 0L
        val shown = min(v.items.size, StationView.MAX_ITEMS)
        val ids = IntArray(shown) { BuiltInRegistries.ITEM.getId(v.items[it].item) }
        val call = v.call
        return StationView(
            v.station, v.state.ordinal, v.notch, v.lit, v.state == Vessel.State.BURNT, v.water, ids, tint(), added, addedCount,
            call?.wire ?: 0, if (call != null) v.callAt + Calls.LATE_TICKS else 0L,
            v.readyFrom(now, freeFire), v.readyTo(now, freeFire), viewMode, swirlUntil, viewBowls, viewTurning,
        )
    }

    override fun realView(): StationView =
        if (level?.isClientSide() == true) clientView ?: StationView.cold(vessel.station) else buildView(level)

    /** The liquid's colour: the roles' colours by their share in the mix, blended into water as items go in (0 is plain water). */
    private fun tint(): Int {
        val items = vessel.items
        if (items.isEmpty()) return 0
        val share = Roles.share(items)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var sum = 0.0
        for (i in Role.entries.indices) {
            val w = if (i < share.size) share[i] else 0.0
            if (w <= 0.0) continue
            r += w * (ROLE_RGB[i] shr 16 and 0xFF)
            g += w * (ROLE_RGB[i] shr 8 and 0xFF)
            b += w * (ROLE_RGB[i] and 0xFF)
            sum += w
        }
        if (sum <= 0.0) {
            r = (BROTH_RGB shr 16 and 0xFF).toDouble()
            g = (BROTH_RGB shr 8 and 0xFF).toDouble()
            b = (BROTH_RGB and 0xFF).toDouble()
        } else {
            r /= sum
            g /= sum
            b /= sum
        }
        val amount = min(1.0, items.size / TINT_ITEMS)
        val wr = (WATER_RGB shr 16 and 0xFF) * (1 - amount) + r * amount
        val wg = (WATER_RGB shr 8 and 0xFF) * (1 - amount) + g * amount
        val wb = (WATER_RGB and 0xFF) * (1 - amount) + b * amount
        return (wr.roundToInt() shl 16) or (wg.roundToInt() shl 8) or wb.roundToInt()
    }

    // ---- Where it is registered, and the save ----

    override fun onLoad() {
        super.onLoad()
        if (level is ServerLevel) Stations.add(this)
    }

    override fun setRemoved() {
        Stations.remove(this)
        super.setRemoved()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (clientTag) {
            buildView(level).write(output.child("view"))
            return
        }
        vessel.save(output.child("vessel"))
        placer?.let { output.putString("placer", it.toString()) }
        output.putInt("added", added)
        output.putInt("addedCount", addedCount)
        output.putLong("swirlUntil", swirlUntil)
        saveExtra(output)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val view = input.child("view")
        if (view.isPresent) clientView = StationView.read(view.get(), vessel.station)
        val saved = input.child("vessel")
        if (saved.isPresent) vessel.load(saved.get())
        placer = try {
            UUID.fromString(input.getStringOr("placer", ""))
        } catch (e: IllegalArgumentException) {
            null
        }
        added = input.getIntOr("added", 0)
        addedCount = input.getIntOr("addedCount", 0)
        swirlUntil = input.getLongOr("swirlUntil", 0L)
        loadExtra(input)
    }

    /** What a subclass keeps besides the vessel (the Stewpot's mode, the Spit's jack). */
    protected open fun saveExtra(output: ValueOutput) {}

    /** Reads what [saveExtra] wrote. */
    protected open fun loadExtra(input: ValueInput) {}

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        clientTag = true
        try {
            return super.getUpdateTag(registries)
        } finally {
            clientTag = false
        }
    }

    /** Keeps the block state's `lit`, `notch` and `burnt` as the vessel has them, and tells the clients if the vessel changed anything they see. */
    protected fun settle() {
        val level = level ?: return
        if (level.isClientSide()) return
        refreshBlockState(level)
        if (vessel.dirty) {
            vessel.dirty = false
            sync()
        }
    }

    private fun refreshBlockState(level: Level) {
        val v = vessel
        var s = blockState
        if (s.hasProperty(StationProps.LIT)) s = s.setValue(StationProps.LIT, shownLit)
        if (s.hasProperty(StationProps.NOTCH)) s = s.setValue(StationProps.NOTCH, v.notch)
        if (s.hasProperty(StewpotBlock.BURNT)) s = s.setValue(StewpotBlock.BURNT, v.state == Vessel.State.BURNT)
        if (s != blockState) level.setBlock(blockPos, s, Block.UPDATE_ALL)
    }

    // ---- Time ----

    /** Brings the vessel up to the game time now, plays what happened and tells the clients. A no-op while Hearthcraft is off (the time that passes is applied when it is on again). */
    fun catchUp(level: ServerLevel) {
        if (!Cooking.enabled()) return
        val now = level.gameTime
        askFree(level, now, true)
        vessel.advance(now, env)
        playEvents(level, now)
        settle()
    }

    private fun askFree(level: ServerLevel, now: Long, force: Boolean) {
        if (!force) {
            if (!vessel.lit) return
            if (now - freeAsked in 0 until Fuel.FREE_TICKS) return
        }
        freeAsked = now
        val free = Fuel.free(level, blockPos)
        if (free != freeFire) {
            freeFire = free
            vessel.dirty = true
        }
    }

    /** Whether the station has something that moves with time (a fire, a house display) and so must be ticked. */
    protected open fun busy(now: Long): Boolean = vessel.lit

    /**
     * What a subclass does in a tick instead of the vessel's own work; answers whether the vessel is to be worked up to
     * date now as well (the house pot, which is a display and not a vessel, answers no).
     */
    protected open fun tickVessel(level: ServerLevel, now: Long): Boolean = true

    override fun serverTick(level: ServerLevel) {
        val now = level.gameTime
        if ((now + blockPos.asLong()) % TICK_EVERY != 0L) return
        if (!busy(now)) return
        if (!Cooking.enabled()) return
        if (now - nearAsked !in 0 until NEAR_CHECK_TICKS) {
            nearAsked = now
            near = level.hasNearbyAlivePlayer(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5, NEAR_BLOCKS)
        }
        if (!near) return
        if (!tickVessel(level, now)) return
        val started = System.nanoTime()
        askFree(level, now, false)
        vessel.advance(now, env)
        playEvents(level, now)
        settle()
        Stations.advances++
        Stations.nanos += System.nanoTime() - started
    }

    // ---- What happened ----

    /** Plays what the vessel reported in its last advance: the cue of a call, the sounds of ready, burnt and out. */
    internal fun playEvents(level: ServerLevel, now: Long) {
        val v = vessel
        if (v.events.isEmpty()) return
        val heard = v.attendedLast
        for (e in v.events) {
            when (e.what) {
                Vessel.Happened.CALLED -> cue(level, e)
                Vessel.Happened.MISSED -> if (heard) missed(level, e)
                Vessel.Happened.JACKED -> if (heard) {
                    swirlUntil = now + CRANK_TICKS
                    ring(level, "cook.crank", 0.7f, 1.0f)
                }
                Vessel.Happened.HEATED -> {}
                Vessel.Happened.READY -> if (heard) ring(level, "cook.ready", 0.8f, 1.0f)
                Vessel.Happened.RIPE -> if (heard) ring(level, "cook.ripe", 0.6f, 1.0f)
                Vessel.Happened.DONE -> if (heard) {
                    ring(level, "cook.sizzle", 0.6f, 0.8f)
                    smoke(level, 4)
                }
                Vessel.Happened.BURNT -> burnt(level, heard)
                Vessel.Happened.OUT -> if (heard) level.playSound(null, blockPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.2f)
            }
        }
        v.events.clear()
    }

    /** The knock and the glint of a call, and (until the signs are drawn) the line of it to the player it goes to. */
    private fun cue(level: ServerLevel, e: Vessel.Event) {
        ring(level, "cook.call", 1.0f, 1.0f)
        puff(level, CookParticles.CALL.get(), 3, 0.2)
        val call = e.call ?: return
        if (!Calls.SAY_CALLS) return
        val to = e.who?.let { level.getPlayerByUUID(it) } as? ServerPlayer ?: return
        say(to, callKey("call", call))
    }

    /** The sign gets worse: dark smoke for a catching pot, foam and a hiss for a boil-over, a flare for the fat. */
    private fun missed(level: ServerLevel, e: Vessel.Event) {
        val call = e.call ?: return
        when (call) {
            Call.STICK -> {
                ring(level, "cook.crackle", 0.8f, 0.9f)
                smoke(level, 6)
            }
            Call.BOIL_OVER -> {
                ring(level, "cook.boil_over", 1.0f, 1.0f)
                puff(level, CookParticles.FOAM.get(), 14, 0.35)
                puff(level, CookParticles.STEAM.get(), 8, 0.3)
            }
            Call.FLARE -> {
                ring(level, "cook.flare", 1.0f, 1.0f)
                puff(level, CookParticles.SIZZLE.get(), 10, 0.3)
                smoke(level, 4)
            }
            else -> ring(level, "cook.crackle", 0.5f, 1.1f)
        }
        val to = e.who?.let { level.getPlayerByUUID(it) } as? ServerPlayer ?: return
        if (Calls.SAY_CALLS) say(to, callKey("missed", call))
    }

    /** The key of the line for [call] under [head] (`call` or `missed`): the pan and the spit have their own wording for some. */
    private fun callKey(head: String, call: Call): String {
        val name = call.name.lowercase()
        return when {
            head == "call" && call == Call.STICK && vessel.station == Station.HOB -> "call.stick.pan"
            head == "call" && call == Call.TURN && vessel.station == Station.SPIT -> "call.turn.spit"
            else -> "$head.$name"
        }
    }

    /** The dish has caught: black smoke and a hiss, and a subclass hears of it (a burnt pot at the inn is gossip). */
    private fun burnt(level: ServerLevel, heard: Boolean) {
        if (heard) {
            ring(level, "cook.burnt", 1.0f, 1.0f)
            smoke(level, 18)
        }
        onBurnt(level)
    }

    /** A subclass hears of a burnt dish (the Stewpot's guest pot tells the town). */
    protected open fun onBurnt(level: ServerLevel) {}

    internal fun ring(level: ServerLevel, name: String, volume: Float, pitch: Float) {
        level.playSound(null, blockPos, CookSounds.get(name), SoundSource.BLOCKS, volume, pitch)
    }

    internal fun puff(level: ServerLevel, particle: ParticleOptions, count: Int, spread: Double) {
        level.sendParticles(particle, blockPos.x + 0.5, blockPos.y + 1.05, blockPos.z + 0.5, count, spread, 0.1, spread, 0.02)
    }

    internal fun smoke(level: ServerLevel, count: Int) {
        puff(level, CookParticles.SMOKE.get(), count, 0.3)
    }

    /** Says `message.guildmark.cook.<key>` to [player] in the action bar. */
    internal fun say(player: ServerPlayer, key: String, vararg args: Any) {
        player.sendOverlayMessage(Component.translatable("message.guildmark.cook.$key", *args))
    }

    // ---- A click ----

    /** The way the station faces (north for the ones that have no facing). */
    private fun facing(): Direction =
        if (blockState.hasProperty(StationProps.FACING)) blockState.getValue(StationProps.FACING) else Direction.NORTH

    /** The region of the station that [hit] is in, the player's eye where it is. */
    internal fun regionOf(player: Player, hit: BlockHitResult): Region {
        val hearth = blockState.hasProperty(StewpotBlock.STYLE) && blockState.getValue(StewpotBlock.STYLE) == StewpotStyle.HEARTH
        return StationRegions.of(vessel.station, hearth, facing(), blockPos, player.eyePosition, hit.location)
    }

    /** The lighter in the hand: flint and steel or a fire charge (1.3: "nothing else lights it"). */
    internal fun lighter(stack: ItemStack): Boolean = stack.`is`(Items.FLINT_AND_STEEL) || stack.`is`(Items.FIRE_CHARGE)

    override fun predicts(player: Player, hand: InteractionHand, stack: ItemStack, hit: BlockHitResult): Boolean {
        if (!Cooking.enabled()) return false
        if (stack.isEmpty) return true
        // Only what the client itself can tell: the cook's data (Roles) is the server's, and the server answers with this same
        // function, so a swing is never missing or doubled. An ingredient the guess misses is swung by the server instead.
        return lighter(stack) || stack.item is CookToolItem || stack.`is`(Items.WATER_BUCKET) || (stack.has(DataComponents.COOKING_FUEL) && !stack.isDamageableItem) ||
            stack.has(DataComponents.FOOD) || Prep.preparable(stack) || Actions.isAccent(stack)
    }

    override fun interact(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, hit: BlockHitResult): InteractionResult {
        val level = level as? ServerLevel ?: return InteractionResult.PASS
        if (!Cooking.enabled()) {
            // Off: the station stands and says so, and a block in the hand goes down beside it as it always did (10.1)
            if (stack.item is BlockItem) return InteractionResult.PASS
            say(player, "off")
            return InteractionResult.CONSUME
        }
        catchUp(level)
        lastTouch = player.uuid
        val region = regionOf(player, hit)
        if (!gate(level, player, stack, region)) return InteractionResult.CONSUME
        if (!player.getData(CookRegistry.COOK_BOOK_GIVEN)) Journal.give(player)
        val used = when {
            lighter(stack) -> light(level, player, hand, stack)
            isFuel(level, stack, region) -> feed(level, player, stack)
            else -> use(level, player, hand, stack, region)
        }
        settle()
        return when (used) {
            Used.DONE -> if (predicts(player, hand, stack, hit)) InteractionResult.SUCCESS else InteractionResult.SUCCESS_SERVER
            Used.REFUSED -> InteractionResult.CONSUME
            Used.PASS -> InteractionResult.PASS
        }
    }

    /**
     * Whether [stack] is something to feed the fire: it burns in a furnace, and it is not a tool, a weapon or a piece of armour
     * (a click on a station never burns a wooden axe, enchanted or not, though a furnace would take it).
     */
    internal fun fuelItem(level: ServerLevel, stack: ItemStack): Boolean =
        !stack.isEmpty && !stack.isDamageableItem && Fuel.burnTicks(level, stack) > 0

    /** Whether the item is fed to the fire: it is a fuel, and it is not something the pot would take from the vessel's side. */
    private fun isFuel(level: ServerLevel, stack: ItemStack, region: Region): Boolean {
        if (!fuelItem(level, stack)) return false
        return !(region == Region.VESSEL && Roles.of(stack) != null)
    }

    /**
     * Whether [player] may use the station at all (private pots, the guest pot's standing, the house pot): if not, the line
     * that says why is shown and this answers false. A subclass that has modes adds its own.
     */
    protected open fun gate(level: ServerLevel, player: ServerPlayer, stack: ItemStack, region: Region): Boolean {
        if (!Cooking.potsPrivate()) return true
        val owner = placer ?: return true
        if (owner == player.uuid) return true
        val party = Parties.get(level.server).of(owner)
        if (party != null && player.uuid in party.members) return true
        // A pot kept private still takes fuel and a light from anyone (1.7: "others can only add fuel")
        if (lighter(stack) || fuelItem(level, stack)) return true
        say(player, "private")
        return false
    }

    // ---- Light and fuel (1.3) ----

    /** Lights the fire with flint and steel or a fire charge. */
    private fun light(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack): Used {
        val v = vessel
        if (v.lit) {
            say(player, "burning")
            return Used.REFUSED
        }
        if (v.fuel <= 0.0 && !freeFire) {
            say(player, "nothing_to_burn")
            return Used.REFUSED
        }
        v.lit = true
        v.dirty = true
        if (stack.`is`(Items.FIRE_CHARGE)) {
            if (!player.hasInfiniteMaterials()) stack.shrink(1)
        } else {
            stack.hurtAndBreak(1, player, hand)
        }
        ring(level, "cook.ignite", 1.0f, 1.0f)
        level.playSound(null, blockPos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.6f, 1.0f)
        CookActions.play(player, CookAction.FEED, blockPos)
        return Used.DONE
    }

    /** Feeds a fuel to the fire: one item a click, its seconds of mid-heat to the store. A refusal takes nothing. */
    private fun feed(level: ServerLevel, player: ServerPlayer, stack: ItemStack): Used {
        val seconds = Fuel.seconds(level, stack)
        when (vessel.addFuel(seconds)) {
            Vessel.FuelResult.TOO_MUCH -> {
                say(player, "fuel_too_much")
                return Used.REFUSED
            }
            Vessel.FuelResult.FULL -> {
                say(player, "fuel_full")
                return Used.REFUSED
            }
            Vessel.FuelResult.OK -> {}
        }
        val left = remainderOf(stack)
        if (!player.hasInfiniteMaterials()) stack.shrink(1)
        if (!left.isEmpty) give(level, player, left)
        ring(level, "cook.fuel", 1.0f, 1.0f)
        CookActions.play(player, CookAction.FEED, blockPos)
        say(player, "fuel", clock(vessel.fuel))
        return Used.DONE
    }

    /** What an item leaves behind when it is used up as an ingredient or a fuel: its use remainder, else its crafting remainder. */
    internal fun remainderOf(stack: ItemStack): ItemStack {
        stack.get(DataComponents.USE_REMAINDER)?.convertInto()?.let { return it.create() }
        return stack.item.craftingRemainder?.create() ?: ItemStack.EMPTY
    }

    /** Puts [stack] in the pack of [player], and what does not fit at the station's foot (a full pack never strands a portion, 9.6). */
    internal fun give(level: ServerLevel, player: ServerPlayer, stack: ItemStack) = Actions.give(level, blockPos, player, stack)

    /** Minutes and seconds as "3:20". */
    internal fun clock(seconds: Double): String {
        val s = seconds.roundToInt().coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // ---- What the station does with the rest ----

    /**
     * A click that is neither a light nor a fuel: what the station makes of [stack] in [hand] (empty or not) on [region]. The
     * four stations override it; until one does, an empty hand works the heat and anything else is not for the vessel.
     */
    protected open fun use(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, stack: ItemStack, region: Region): Used {
        if (stack.isEmpty) return emptyHand(level, player, region)
        say(player, "not_for." + vessel.station.name.lowercase())
        return Used.REFUSED
    }

    /**
     * An empty hand: the heat control on the heat region; a sneaking one on the vessel or the fire tips it out; else the
     * readout of the fire. The oven's door and the spit's crank take their own clicks first.
     */
    protected open fun emptyHand(level: ServerLevel, player: ServerPlayer, region: Region): Used = when {
        region == Region.HEAT -> heat(level, player)
        player.isShiftKeyDown && (region == Region.VESSEL || region == Region.FIRE) -> tipOut(level, player)
        else -> status(player)
    }

    /** What the fire is doing, in the action bar. */
    protected fun status(player: ServerPlayer): Used {
        say(player, if (vessel.lit) "fire_lit" else "fire_cold", clock(vessel.fuel))
        return Used.DONE
    }

    /**
     * The heat control with an empty hand: a click eases it one notch (the pot rises on its chain, the slide closes), and a
     * sneaking click raises it one. A pending BOIL_OVER or FLARE is answered by the ease, and the heat then stays where it was put.
     */
    protected fun heat(level: ServerLevel, player: ServerPlayer): Used {
        val v = vessel
        val old = v.notch
        val pending = v.call
        val answers = pending == Call.BOIL_OVER || pending == Call.FLARE
        if (player.isShiftKeyDown && !answers) {
            if (old >= 2) {
                say(player, "heat_max")
                return Used.REFUSED
            }
            v.setHeat(old + 1)
        } else {
            if (old <= 0 && !answers) {
                say(player, "heat_min")
                return Used.REFUSED
            }
            v.setHeat(old - 1)
        }
        ring(level, "cook.ease", 1.0f, 0.9f + 0.1f * v.notch)
        CookActions.play(player, CookAction.EASE, blockPos)
        if (answers) v.resolve(level.gameTime, player.uuid, player.name.string, level.random)
        say(player, "heat." + v.notch)
        return Used.DONE
    }

    // ---- Tipping out (1.5 row 21, 9.6) ----

    /**
     * Sneak and an empty hand: the vessel is upended. A pot that burnt is emptied at once and Scorched mess falls; one still
     * open gives back what was put in; a dish that is cooking, ready or being served would be lost, so it asks once more
     * within four seconds before it goes.
     */
    protected fun tipOut(level: ServerLevel, player: ServerPlayer): Used {
        val v = vessel
        if (v.state == Vessel.State.EMPTY && v.items.isEmpty() && v.water <= 0) {
            say(player, "tip_empty")
            return Used.REFUSED
        }
        val now = level.gameTime
        if (v.started && v.state != Vessel.State.BURNT && (tipBy != player.uuid || now > tipUntil)) {
            tipBy = player.uuid
            tipUntil = now + TIP_CONFIRM_TICKS
            say(player, "tip_sure")
            return Used.REFUSED
        }
        tipBy = null
        emptyOut(level)
        CookActions.play(player, CookAction.ADD, blockPos)
        say(player, "tipped")
        return Used.DONE
    }

    /**
     * Empties the vessel as a tip-out does (`cook vessel tip` too): what that drops falls at the station's foot, and the dish
     * in the making is lost; the fire, its fuel and the heat stay as they were.
     */
    fun emptyOut(level: ServerLevel) {
        val drops = Serving.tipped(level, vessel)
        vessel.empty()
        for (stack in drops) Serving.drop(level, blockPos.above(), stack)
        ring(level, "cook.pour", 0.8f, 0.7f)
        puff(level, ParticleTypes.SPLASH, 12, 0.3)
        settle()
    }

    // ---- Taken apart ----

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        super.preRemoveSideEffects(pos, state)
        val level = level as? ServerLevel ?: return
        vessel.dropHold()
        dropContents(level, pos)
    }

    /**
     * What the station held falls at its foot when it is broken: the raw items of a vessel not yet served from (it never
     * became a dish), Scorched mess for a burnt one, and the portions still in one that was served from.
     */
    protected open fun dropContents(level: ServerLevel, pos: BlockPos) {
        for (stack in Serving.broken(level, vessel)) Serving.drop(level, pos, stack)
        vessel.items.clear()
    }

    // ---- For `cook vessel` (10.3) ----

    /** Tells the clients and the save what the vessel now is: for the commands, which change it from outside. */
    fun settleNow() = settle()

    /** Where the vessel stands, in lines for an operator: the state, what is in it, what it was taken for, the clocks, the call and the credit, and who has done what. */
    fun describe(level: ServerLevel, cookLevel: Int, bonus: Double = 0.0): List<String> {
        val v = vessel
        val now = level.gameTime
        val out = ArrayList<String>()
        val water = if (v.station == Station.STEWPOT) ", water " + v.water + "/" + Vessel.WATER_FULL else ""
        val free = if (freeFire) ", free fire" else ""
        out += v.station.name.lowercase() + ": " + v.state.name.lowercase() + ", heat " + v.notch + ", " + (if (v.lit) "lit" else "cold") +
            ", fuel " + clock(v.fuel) + " (" + v.fuel.roundToInt() + " s of mid heat)" + free + water
        if (v.items.isNotEmpty()) {
            val what = v.items.groupingBy { it.hoverName.string }.eachCount().entries.joinToString(", ") { it.value.toString() + " " + it.key }
            out += "in it: " + what + " (" + v.items.size + " of " + v.capacity + ")"
        }
        if (v.started) {
            out += "taken for: " + (v.kind?.id ?: "gruel") + ", " + v.portions + " portions, distance " + "%.3f".format(v.distance)
            out += "dish-clock " + "%.1f".format(v.p) + " of " + "%.0f".format(v.cookTime) + " dish-seconds (ready from " + "%.0f".format(v.cookTime - Vessel.WINDOW) +
                " to " + "%.0f".format(v.cookTime + Vessel.WINDOW) + "), timing " + "%.2f".format(v.timing()) + ", heated " + "%.1f".format(v.heated) + " of " +
                Vessel.HEAT_SECONDS.roundToInt() + " s"
            val kind = v.kind
            if (kind != null && v.tier == null) {
                // What the first serve would freeze, with the viewer's own bonus (2.5: 0.45 mix + 0.17 timing + 0.17 attention + 0.11 care + bonus)
                val mix = Quality.mix(v.share, Ideal.of(level, kind))
                val care = Prep.care(v.items, v.attention())
                val q = Quality.q(mix, v.timing(), v.attention(), care, bonus)
                out += "if served now: mix " + "%.2f".format(mix) + ", timing " + "%.2f".format(v.timing()) + ", attention " + "%.2f".format(v.attention()) +
                    ", care " + "%.2f".format(care) + ", bonus " + "%.3f".format(bonus) + ", q " + "%.3f".format(q) + " = " + Quality.tier(q).name.lowercase()
            }
        } else if (v.items.size >= 2) {
            val guess = Recognise.run(level, v.station, v.items, cookLevel)
            out += "would be taken for: " + (guess.kind?.id ?: "gruel") + ", " + guess.portions + " portions, distance " + "%.3f".format(guess.distance) +
                " (as a cook of level " + cookLevel + ")"
        }
        val call = v.call
        if (call != null) {
            val who = v.callWho?.let { level.server.playerList.getPlayer(it)?.name?.string } ?: "nobody"
            out += "call: " + call.name.lowercase() + " to " + who + ", " + "%.1f".format(max(0L, v.callAt + Calls.LATE_TICKS - now) / 20.0) + " s left"
        } else if (v.cooking) {
            out += "no call due; the next in " + "%.1f".format(max(0.0, v.callGap - v.callClock)) + " s of fuelled cooking"
        }
        out += "calls " + v.calls + ", credit " + "%.1f".format(v.credit) + ", attention " + "%.2f".format(v.attention()) + "; missed: stick " + v.sticks +
            ", boil " + v.boils + ", flare " + v.scorches + "; seasoned " + v.seasons
        if (!v.who.isEmpty) {
            out += "contributors: " + v.who.all.joinToString(", ") { it.name + " " + it.adds + " added, " + it.answers + " answered" }
        }
        val tier = v.tier
        if (tier != null) {
            out += "served: " + tier.name.lowercase() + ", " + v.left + " of " + v.total + " left, cook " + v.cookName + ", day " + v.day + ", keeps " + v.keep + "% longer"
        }
        return out
    }
}
