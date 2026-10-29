package io.github.vaspyyy.guildmark.cooking.dish

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.DishData
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ProblemReporter
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.ContainerUser
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.ContainerOpenersCounter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The Larder's 18 slots (living-world/cook/design.md 1.1, 3.2, 6.4): a chest that takes dishes only, and keeps them twice
 * as long. Part D's.
 *
 * **The half-rate ageing is lazy, like all of the dishes' ageing.** Nothing ticks. The Larder notes, for each slot, the
 * day its stack was last settled ([entered]); whenever a stack is looked at or taken out ([settle]) it has spent
 * `today - entered` days inside, and half of them (rounded down; an odd day is carried to the next look) move the dish's
 * day forward, so it comes out as old as the half it really aged. A stack put in is noted that day; the days only ever
 * move a dish's `day`, and never past today, so everything that reads a dish (the tooltip, a meal, a sale) needs no knowledge
 * of the Larder. With `dishSpoilage` off nothing settles and nothing is written: the days stay noted, and when it is turned
 * back on they count then. Opening the door settles everything and brings each stack's looks (and food) up to date.
 *
 * **[backing]** is the container the slots really are: null is the Larder's own 18, and a cookshop gives its Larder a view
 * of the shop's record (Part H, 6.4) instead. Every container method then goes to it, the Larder keeps no days of its own
 * (the shop's stock is the shop's), who may open it is what its `stillValid` says, and breaking the block drops nothing.
 *
 * The door opens for as long as anyone has the chest open (the barrel's openers counter, with its own sound), and the three
 * kinds the slots hold first are sent to clients ([shown]) for the door's renderer to show through the slats.
 */
class LarderBlockEntity(pos: BlockPos, state: BlockState) : BaseContainerBlockEntity(CookRegistry.LARDER_ENTITY.get(), pos, state) {
    /** The container the slots really are, or null for the Larder's own 18 (see above). */
    var backing: Container? = null

    private var items: NonNullList<ItemStack> = NonNullList.withSize(SIZE, ItemStack.EMPTY)

    /** For each slot, the day its stack was last settled; [UNSET] for an empty slot or one that has not been noted. */
    private val entered = LongArray(SIZE) { UNSET }

    /**
     * What the door shows: one of each of the first [SHOWN_MAX] kinds the slots hold, dressed as they look today. The
     * client's copy of it is what the server last sent; the renderer reads it. Empty for a bare Larder.
     */
    var shown: List<ItemStack> = emptyList()
        private set

    private val openers = object : ContainerOpenersCounter() {
        override fun onOpen(level: Level, pos: BlockPos, state: BlockState) {
            level.playSound(null, pos, CookSounds.get("cook.larder"), SoundSource.BLOCKS, 0.6f, 0.95f + level.random.nextFloat() * 0.1f)
            setOpen(level, pos, state, true)
        }

        // The door swings to first (the count below tells the clients at once) and the block shuts [SHUT_TICKS] later, from the
        // block's tick, so the swinging door is never drawn over a shell that has its shut door already
        override fun onClose(level: Level, pos: BlockPos, state: BlockState) {
            level.playSound(null, pos, CookSounds.get("cook.larder"), SoundSource.BLOCKS, 0.5f, 0.78f + level.random.nextFloat() * 0.1f)
            shutAt = level.gameTime + SHUT_TICKS
            level.scheduleTick(pos, state.block, SHUT_TICKS)
        }

        override fun openerCountChanged(level: Level, pos: BlockPos, state: BlockState, previous: Int, current: Int) {
            level.blockEvent(pos, state.block, DOOR_EVENT, current)
        }

        override fun isOwnContainer(player: Player): Boolean = (player.containerMenu as? ChestMenu)?.container === this@LarderBlockEntity
    }

    /** The game time the block may shut its door at, a moment after the last one closed it (the server's; not saved). */
    private var shutAt = 0L

    /**
     * How many have the door open, as the server last said (the client's copy, from [DOOR_EVENT]); -1 until it has said. The
     * renderer swings its door to as soon as this is 0, while the block still says open.
     */
    var openersSeen = -1
        private set

    override fun triggerEvent(id: Int, param: Int): Boolean {
        if (id != DOOR_EVENT) return super.triggerEvent(id, param)
        openersSeen = param
        return true
    }

    private fun setOpen(level: Level, pos: BlockPos, state: BlockState, open: Boolean) {
        if (state.block is LarderBlock) level.setBlockAndUpdate(pos, state.setValue(LarderBlock.OPEN, open))
    }

    // ---- The container ----

    override fun getContainerSize(): Int = backing?.containerSize ?: SIZE

    override fun getItems(): NonNullList<ItemStack> = items

    override fun setItems(items: NonNullList<ItemStack>) {
        this.items = items
        entered.fill(UNSET)
    }

    override fun getDefaultName(): Component = Component.translatable("block.guildmark.larder")

    override fun createMenu(id: Int, inventory: Inventory): AbstractContainerMenu = LarderMenu(LarderMenus.LARDER.get(), id, inventory, this)

    /** Dishes only (and, for a cookshop's Larder, what the shop's stock takes). Hoppers and shift-clicks ask this too. */
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = stack.item is DishItem && (backing?.canPlaceItem(slot, stack) ?: true)

    /** Whether [player] may open this Larder at all: any player, for a Larder of one's own; for a cookshop's, what the shop's stock says (its owner and their party). */
    fun mayOpen(player: Player): Boolean = backing?.stillValid(player) ?: true

    override fun stillValid(player: Player): Boolean {
        val source = backing
        return (source == null || source.stillValid(player)) && super.stillValid(player)
    }

    override fun isEmpty(): Boolean {
        val source = backing
        return if (source != null) source.isEmpty else super.isEmpty()
    }

    override fun getItem(slot: Int): ItemStack {
        val source = backing
        if (source != null) return source.getItem(slot)
        if (slot !in 0 until SIZE) return ItemStack.EMPTY
        settle(slot)
        return items[slot]
    }

    override fun removeItem(slot: Int, count: Int): ItemStack {
        val source = backing
        if (source != null) return source.removeItem(slot, count)
        if (slot !in 0 until SIZE) return ItemStack.EMPTY
        settle(slot)
        val taken = super.removeItem(slot, count)
        if (items[slot].isEmpty) entered[slot] = UNSET
        return dressed(taken)
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val source = backing
        if (source != null) return source.removeItemNoUpdate(slot)
        if (slot !in 0 until SIZE) return ItemStack.EMPTY
        settle(slot)
        entered[slot] = UNSET
        return dressed(super.removeItemNoUpdate(slot))
    }

    /** What the 2-argument `setItem` goes through: a stack put in is noted as going in today. */
    override fun setItem(slot: Int, stack: ItemStack, quiet: Boolean) {
        val source = backing
        if (source != null) {
            source.setItem(slot, stack)
            if (!quiet) setChanged()
            return
        }
        if (slot !in 0 until SIZE) return
        super.setItem(slot, stack, quiet)
        entered[slot] = if (stack.isEmpty) UNSET else today()
    }

    override fun clearContent() {
        val source = backing
        if (source != null) {
            source.clearContent()
            return
        }
        super.clearContent()
        entered.fill(UNSET)
    }

    override fun setChanged() {
        super.setChanged()
        backing?.setChanged()
        refresh()
    }

    /** A cookshop's Larder drops nothing when it is broken: its stock is the shop's record (6.4). Its own 18 are dropped, with their days settled. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        if (backing == null) super.preRemoveSideEffects(pos, state)
    }

    // ---- The door ----

    override fun startOpen(user: ContainerUser) {
        backing?.startOpen(user)
        if (isRemoved || user.livingEntity.isSpectator) return
        tidy()
        val level = level ?: return
        openers.incrementOpeners(user.livingEntity, level, blockPos, blockState, user.containerInteractionRange)
    }

    override fun stopOpen(user: ContainerUser) {
        backing?.stopOpen(user)
        if (isRemoved || user.livingEntity.isSpectator) return
        val level = level ?: return
        openers.decrementOpeners(user.livingEntity, level, blockPos, blockState)
    }

    override fun getEntitiesWithContainerOpen(): List<ContainerUser> {
        val level = level ?: return emptyList()
        return openers.getEntitiesWithContainerOpen(level, blockPos)
    }

    /**
     * From the block's scheduled tick while anyone has it open, and once more after the last one closed it: counts again, and
     * shuts the door when nobody has had it open for [SHUT_TICKS].
     */
    fun recheckOpen() {
        if (isRemoved) return
        val level = level ?: return
        openers.recheckOpeners(level, blockPos, blockState)
        val state = blockState
        if (openers.openerCount > 0 || state.block !is LarderBlock || !state.getValue(LarderBlock.OPEN)) return
        val wait = shutAt - level.gameTime
        if (wait > 0) level.scheduleTick(blockPos, state.block, wait.toInt()) else setOpen(level, blockPos, state, false)
    }

    // ---- The days ----

    /** The town's day it is, or [UNSET] while the Larder has no level yet. */
    private fun today(): Long = level?.let { Freshness.today(it) } ?: UNSET

    /**
     * Settles the days [slot]'s stack has spent inside (see the head of this class). Server side, and only for the
     * Larder's own slots. Cheap when there is nothing to settle: a few compares, so looking at a stack costs nothing.
     */
    private fun settle(slot: Int) {
        val level = level as? ServerLevel ?: return
        if (backing != null) return
        val stack = items[slot]
        if (stack.isEmpty) {
            entered[slot] = UNSET
            return
        }
        val today = Freshness.today(level)
        val since = entered[slot]
        if (since == UNSET || since > today) {
            entered[slot] = today
            return
        }
        if (!Cooking.spoilage()) return
        val inside = today - since
        if (inside < 2) return
        val credit = inside / 2
        val data = stack.get(CookRegistry.DISH.get())
        if (data != null && data.day < DishData.UNDATED) {
            val day = minOf(data.day + credit, maxOf(data.day, today))
            if (day != data.day) stack.set(CookRegistry.DISH.get(), data.copy(day = day))
        }
        entered[slot] = since + credit * 2
        Freshness.sync(level, stack)
        setChanged()
    }

    /** The door has opened and what is inside is looked at: each stack's days are settled and its looks and food made right. */
    private fun tidy() {
        val level = level as? ServerLevel ?: return
        val source = backing
        for (slot in 0 until (source?.containerSize ?: SIZE)) {
            if (source == null) settle(slot)
            val stack = source?.getItem(slot) ?: items[slot]
            if (!stack.isEmpty) Freshness.sync(level, stack)
        }
    }

    /** [stack], just taken out, brought up to date (a held stack does this by its own tick, but not for ten seconds). */
    private fun dressed(stack: ItemStack): ItemStack {
        val level = level as? ServerLevel
        if (level != null && !stack.isEmpty) Freshness.sync(level, stack)
        return stack
    }

    // ---- What clients are told ----

    /** One of each of the first [SHOWN_MAX] kinds the slots hold, dressed as they look in [level] today. */
    private fun looks(level: Level): List<ItemStack> {
        val source = backing
        val out = ArrayList<ItemStack>(SHOWN_MAX)
        for (slot in 0 until (source?.containerSize ?: SIZE)) {
            val stack = source?.getItem(slot) ?: items[slot]
            if (stack.isEmpty || out.any { it.item === stack.item }) continue
            out.add(Freshness.looking(level, stack))
            if (out.size == SHOWN_MAX) break
        }
        return out
    }

    /**
     * Works out what the door shows now and tells clients if it changed: the container calls it itself whenever it
     * changes, and Part H calls it when a cookshop's stock changes under the Larder (6.4). Server side; cheap.
     */
    fun refresh() {
        val level = level as? ServerLevel ?: return
        val now = looks(level)
        if (now.size == shown.size && now.indices.all { ItemStack.matches(now[it], shown[it]) }) return
        shown = now
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries)
        output.store("shown", SHOWN, level?.let { looks(it) } ?: shown)
        return output.buildResult()
    }

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    // ---- What is saved ----

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (backing != null) return
        ContainerHelper.saveAllItems(output, items)
        output.store("entered", DAYS, entered.toList())
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(input, items)
        val days = input.read("entered", DAYS).orElse(emptyList())
        for (slot in 0 until SIZE) entered[slot] = if (items[slot].isEmpty) UNSET else days.getOrElse(slot) { UNSET }
        input.read("shown", SHOWN).ifPresent { list -> shown = list.filter { !it.isEmpty } }
    }

    companion object {
        /** The slots of a Larder's own. */
        const val SIZE = 18

        /** The kinds the door shows through its slats. */
        const val SHOWN_MAX = 3

        /** The block event that tells clients how many have the door open (the chest's 1). */
        const val DOOR_EVENT = 1

        /** How long the door takes to swing to (the renderer's swing, about six ticks) before the block shuts. */
        const val SHUT_TICKS = 7

        private const val UNSET = Long.MIN_VALUE

        private val DAYS: Codec<List<Long>> = Codec.LONG.listOf()
        private val SHOWN: Codec<List<ItemStack>> = ItemStack.OPTIONAL_CODEC.listOf()
    }
}
