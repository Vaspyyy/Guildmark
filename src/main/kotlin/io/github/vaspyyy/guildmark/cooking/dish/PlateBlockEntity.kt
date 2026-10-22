package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ProblemReporter
import net.minecraft.world.Containers
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The dish on a Plate (living-world/cook/design.md 3.5): one portion, the stack itself, with its tier, its day and its
 * cook, or nothing. Two kinds of plate share the block:
 *
 *  - **a player's**, which anyone may lay a dish on and take it back from, or eat it from (the block's use), and which
 *    gives the dish up when it is broken;
 *  - **one a part of the town dresses** ([forShow]: the cookshop's counter, the Harvest Table; see [Plate.show]), which
 *    shows what it is given and has nothing to take, eat or drop, and which a player cannot lay a dish on.
 *
 * A dish lying on a plate ages as every dish does, from the day it was cooked, and what is drawn is worked out from
 * that day ([look]), so a dish left a week on a plate is seen to have gone off. The portion and the flag are sent to
 * clients for the renderer (Part C) and saved with the plate; both are optional, so a plate from before is simply bare.
 */
class PlateBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(CookRegistry.PLATE_ENTITY.get(), pos, state) {
    /** The portion on the plate (a stack of one), or empty. */
    var dish: ItemStack = ItemStack.EMPTY
        private set

    /** Whether a part of the town dresses this plate (see above): then it holds nothing a player can take. */
    var forShow: Boolean = false
        private set

    /** The dish as it looks in [level]'s world today (its cosmetic stage worked out from its day), for drawing; empty when the plate is bare. Either side of the wire. */
    fun look(level: Level): ItemStack = if (dish.isEmpty) ItemStack.EMPTY else Freshness.looking(level, dish)

    /** Lays [stack] (one portion of it is kept) on a bare plate that is a player's. False when the plate is taken or is the town's, or [stack] is not a dish that can be laid. */
    fun lay(stack: ItemStack): Boolean {
        if (forShow || !dish.isEmpty || !fits(stack)) return false
        dish = stack.copyWithCount(1)
        changed()
        return true
    }

    /** Takes the portion off the plate and brings it up to date, leaving the plate bare. Empty if there was none, or the plate is the town's. */
    fun take(level: ServerLevel): ItemStack {
        if (forShow || dish.isEmpty) return ItemStack.EMPTY
        val portion = dish
        dish = ItemStack.EMPTY
        Freshness.sync(level, portion)
        changed()
        return portion
    }

    /**
     * Dresses the plate with [stack] for show: it is the town's from now on. An empty [stack] bares it (and it stays the
     * town's). False when this plate holds a player's dish, which is never overwritten or lost, or [stack] is not a dish.
     */
    fun show(stack: ItemStack): Boolean {
        if (!forShow && !dish.isEmpty) return false
        if (!stack.isEmpty && !fits(stack)) return false
        val shown = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        if (forShow && ItemStack.matches(shown, dish)) return true
        forShow = true
        dish = shown
        changed()
        return true
    }

    /** A dish that is a dish: any of the fourteen but Scorched mess, which is not fit for a plate. */
    private fun fits(stack: ItemStack): Boolean = (stack.item as? DishItem)?.scorched == false

    /** Saves, and tells the clients who see the plate. */
    private fun changed() {
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /** A player's plate gives up its dish when it is broken; the town's has nothing to give. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        super.preRemoveSideEffects(pos, state)
        val level = level as? ServerLevel ?: return
        if (!forShow && !dish.isEmpty) Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.2, pos.z + 0.5, dish)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries)
        write(output)
        return output.buildResult()
    }

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        write(output)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        dish = input.read(DISH, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY)
        forShow = input.getBooleanOr(SHOW, false)
    }

    private fun write(output: ValueOutput) {
        if (!dish.isEmpty) output.store(DISH, ItemStack.OPTIONAL_CODEC, dish)
        if (forShow) output.putBoolean(SHOW, true)
    }

    private companion object {
        const val DISH = "dish"
        const val SHOW = "show"
    }
}
