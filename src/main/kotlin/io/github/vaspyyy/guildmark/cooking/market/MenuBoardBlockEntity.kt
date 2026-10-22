package io.github.vaspyyy.guildmark.cooking.market

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The chalked lines of a Menu board (living-world/cook/design.md 5.4). The ledger writes an inn's lines ([MenuBoards]), the
 * cookshop (Part H) its stock's and the homestead's pantry (Part K) its own, with [setLines]; they are kept with the chunk,
 * sent to the clients when a player comes by and again only when they change, and drawn from here by the block's renderer
 * (Part C), so what is on a slate is always what the server has.
 *
 * Each line may have a dish [kinds] id beside it (the renderer draws that dish's small icon next to the line; an empty id is
 * a line with no icon). The inn's board has a heading, the three wanted kinds, the day's request and a line or two more; a
 * line is complete text, so a board with only [lines] read from it makes sense on its own.
 */
class MenuBoardBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(CookRegistry.MENU_BOARD_ENTITY.get(), pos, state) {
    private var shown: List<Component> = emptyList()
    private var icons: List<String> = emptyList()

    /** Whether another keeper has written on this board with [setLines], which leaves it theirs: the inn's refreshing then keeps off it. */
    var kept = false
        private set

    /** Server side, not saved: which capital's inn this board hangs at ([MenuBoards.homeOf]) and when that was last looked into. */
    @JvmField
    var home: Long = MenuBoards.UNKNOWN

    @JvmField
    var looked: Long = 0L

    /** The lines now on the board, top to bottom. */
    val lines: List<Component> get() = shown

    /** The id of the dish kind each of [lines] is about (`pie`, `stew`...), or "" for a line with none: as many as there are lines. */
    val kinds: List<String> get() = icons

    /**
     * Writes [lines] on the board (the cookshop's stock and prices, the pantry's contents), with a dish kind id for each in [kinds]
     * if they have icons. The board is theirs from then on. Nothing is sent to the clients if it already reads so.
     */
    fun setLines(lines: List<Component>, kinds: List<String> = emptyList()) {
        val first = !kept
        kept = true
        if (!chalk(lines, kinds) && first) setChanged()
    }

    /** Writes [lines] and their [kinds] and, if they differ from what is on the board, tells the clients; whether they did. */
    internal fun chalk(lines: List<Component>, kinds: List<String>): Boolean {
        val next = fit(lines.size, kinds)
        if (lines == shown && next == icons) return false
        shown = lines.toList()
        icons = next
        sync()
        return true
    }

    private fun sync() {
        setChanged()
        val level = level ?: return
        if (level.isClientSide()) return
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (shown.isNotEmpty()) output.store("lines", LINES, shown)
        if (icons.any { it.isNotEmpty() }) output.store("kinds", KINDS, icons)
        if (kept) output.putBoolean("kept", true)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        shown = input.read("lines", LINES).orElse(emptyList())
        icons = fit(shown.size, input.read("kinds", KINDS).orElse(emptyList()))
        kept = input.getBooleanOr("kept", false)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private val LINES: Codec<List<Component>> = ComponentSerialization.CODEC.listOf()
        private val KINDS: Codec<List<String>> = Codec.STRING.listOf()

        /** [kinds] made as long as the [n] lines they belong to. */
        private fun fit(n: Int, kinds: List<String>): List<String> = List(n) { kinds.getOrElse(it) { "" } }
    }
}
