package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import kotlin.math.min

/**
 * The Prep Board's heap (living-world/cook/design.md 1.1, 11.1): a stack of up to sixteen vegetables, roots, fruit, meat or
 * fish laid on the board, and the Cook's Knife that chops it. Each click of the knife chops one, and it leaves the board in
 * the cook's pack as a Prepared item (`guildmark:prepared`, a line in its tooltip, stacking apart from the plain one), which
 * feeds the `care` of the batch it goes into (2.5). It is a helper and not a fire: it has no vessel, no clock and no ticker, and
 * is synced like a station only so that its renderer can draw the heap.
 */
class PrepBoardBlockEntity(pos: BlockPos, state: BlockState) :
    StationBlockEntity(CookRegistry.PREP_BOARD_ENTITY.get(), pos, state, null), StationUse {
    companion object {
        /** The most items the board holds, laid in a heap (1.1). */
        const val CAPACITY = 16

        /**
         * Ticks a chop takes (1.1): one item every six. A held click repeats about every four ticks, so a chop that is early is
         * refused without a word and the next click after it lands, the knife keeping the beat of six on average.
         */
        const val CHOP_TICKS = 6L

        /** The knife loses a point of durability every this many chops (a heap of sixteen would otherwise cost it a thirteenth of its life). */
        const val CHOPS_PER_WEAR = 2

        /** The view says the knife is at work for this many ticks after a chop (its `swirlUntil`). */
        const val SHOW_TICKS = 8L
    }

    /** What is on the board, one kind of thing, at most [CAPACITY]. */
    private var heap: ItemStack = ItemStack.EMPTY

    /** The beat of the chopping: the game time the last chop was due at, and how many have been made (for the knife's wear). */
    private var lastChop = 0L
    private var chopped = 0

    /** The game time until which the view shows the knife at work. */
    private var shownUntil = 0L

    override fun realView(): StationView {
        val n = min(heap.count, StationView.MAX_ITEMS)
        val id = if (heap.isEmpty) 0 else BuiltInRegistries.ITEM.getId(heap.item)
        return StationView(null, if (heap.isEmpty) 0 else 1, 0, false, false, 0, IntArray(n) { id }, 0, 0, 0, 0, 0L, 0L, 0L, 0, shownUntil, 0, false)
    }

    // ---- The save and the sync ----

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (!heap.isEmpty) output.store("heap", ItemStack.CODEC, heap)
        if (shownUntil > 0L) output.putLong("shownUntil", shownUntil)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        heap = input.read("heap", ItemStack.CODEC).orElse(ItemStack.EMPTY)
        if (heap.count > CAPACITY) heap.count = CAPACITY
        shownUntil = input.getLongOr("shownUntil", 0L)
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        super.preRemoveSideEffects(pos, state)
        val level = level as? ServerLevel ?: return
        if (!heap.isEmpty) Serving.drop(level, pos, heap)
        heap = ItemStack.EMPTY
    }

    // ---- A click ----

    override fun serverTick(level: ServerLevel) {}

    override fun predicts(player: Player, hand: InteractionHand, stack: ItemStack, hit: BlockHitResult): Boolean =
        Cooking.enabled() && (stack.isEmpty || Actions.isKnife(stack) || Prep.preparable(stack))

    override fun interact(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, hit: BlockHitResult): InteractionResult {
        val level = level as? ServerLevel ?: return InteractionResult.PASS
        if (!Cooking.enabled()) {
            // Off: the board stands and says so, and a block in the hand goes down beside it as it always did (10.1)
            if (stack.item is BlockItem) return InteractionResult.PASS
            say(player, "off")
            return InteractionResult.CONSUME
        }
        // A block that is not food is being built beside the board and not laid on it
        if (stack.item is BlockItem && !Prep.preparable(stack)) return InteractionResult.PASS
        val used = when {
            Actions.isKnife(stack) -> chop(level, player, hand, stack)
            stack.isEmpty -> takeBack(level, player)
            Prep.preparable(stack) -> lay(level, player, stack)
            else -> {
                say(player, "not_prep")
                Used.REFUSED
            }
        }
        return when (used) {
            Used.DONE -> if (predicts(player, hand, stack, hit)) InteractionResult.SUCCESS else InteractionResult.SUCCESS_SERVER
            Used.REFUSED -> InteractionResult.CONSUME
            Used.PASS -> InteractionResult.PASS
        }
    }

    /** Lays the stack in the hand on the board, as much of it as fits (a board holds one kind of thing at a time). */
    private fun lay(level: ServerLevel, player: ServerPlayer, stack: ItemStack): Used {
        if (Prep.prepared(stack)) {
            say(player, "already_prepared")
            return Used.REFUSED
        }
        if (!heap.isEmpty && !ItemStack.isSameItemSameComponents(heap, stack)) {
            say(player, "board_busy")
            return Used.REFUSED
        }
        val room = CAPACITY - (if (heap.isEmpty) 0 else heap.count)
        if (room <= 0) {
            say(player, "board_full")
            return Used.REFUSED
        }
        val n = min(room, stack.count)
        if (heap.isEmpty) heap = stack.copyWithCount(n) else heap.grow(n)
        if (!player.hasInfiniteMaterials()) stack.shrink(n)
        level.playSound(null, blockPos, CookSounds.get("cook.plop.soft"), SoundSource.BLOCKS, 0.6f, 1.2f)
        CookActions.play(player, CookAction.ADD, blockPos)
        sync()
        return Used.DONE
    }

    /** An empty hand takes what is on the board back. */
    private fun takeBack(level: ServerLevel, player: ServerPlayer): Used {
        if (heap.isEmpty) {
            say(player, "board_empty")
            return Used.REFUSED
        }
        val all = heap
        heap = ItemStack.EMPTY
        Actions.give(level, blockPos, player, all)
        level.playSound(null, blockPos, CookSounds.get("cook.plop.soft"), SoundSource.BLOCKS, 0.5f, 0.8f)
        sync()
        return Used.DONE
    }

    /** The knife chops one item, which leaves the board Prepared and goes to the cook's pack (or, with it full, to the board's foot). */
    private fun chop(level: ServerLevel, player: ServerPlayer, hand: InteractionHand, knife: ItemStack): Used {
        if (heap.isEmpty) {
            say(player, "board_empty")
            return Used.REFUSED
        }
        val now = level.gameTime
        val since = now - lastChop
        if (since in 0 until CHOP_TICKS) return Used.REFUSED
        lastChop = if (since in 0 until 2 * CHOP_TICKS) lastChop + CHOP_TICKS else now
        val one = heap.copyWithCount(1)
        one.set(CookRegistry.PREPARED.get(), true)
        one.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.prepared").withStyle { it.withColor(0xA8A8A8).withItalic(false) })))
        heap.shrink(1)
        if (heap.isEmpty) heap = ItemStack.EMPTY
        Actions.give(level, blockPos, player, one)
        level.playSound(null, blockPos, CookSounds.get("cook.chop"), SoundSource.BLOCKS, 0.8f, 0.95f + level.random.nextFloat() * 0.1f)
        level.sendParticles(CookParticles.CRUMB.get(), blockPos.x + 0.5, blockPos.y + 0.75, blockPos.z + 0.5, 3, 0.15, 0.05, 0.15, 0.02)
        CookActions.play(player, CookAction.CHOP, blockPos)
        chopped++
        if (chopped % CHOPS_PER_WEAR == 0) Actions.wear(player, hand, knife)
        shownUntil = now + SHOW_TICKS
        sync()
        return Used.DONE
    }

    private fun say(player: ServerPlayer, key: String) {
        player.sendOverlayMessage(Component.translatable("message.guildmark.cook.$key"))
    }
}
