package io.github.vaspyyy.guildmark.magic

import io.github.vaspyyy.guildmark.client.magic.MagicClient
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * A staff or a censer: what sorceries and prayers are cast with. Right-click casts the spell picked on the
 * HUD, from whichever hand holds a catalyst of its school (blocks and creatures are used first, as ever).
 * Its [power] multiplies every spell cast with it.
 */
class CatalystItem(val school: School, val power: Float, properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        // The cast is the client's to start (see client/magic/MagicClient); the server checks it as a move
        if (level.isClientSide) return if (MagicClient.onUse(player)) InteractionResult.CONSUME else InteractionResult.PASS
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        builder.accept(Component.translatable("item.guildmark.catalyst.${school.id}").withColor(school.color))
        builder.accept(Component.translatable("item.guildmark.catalyst.power", "${(power * 100).roundToInt()}%").withStyle(ChatFormatting.GRAY))
    }

    companion object {
        /** The catalyst of [school] [player] would cast with: the main hand's, else the off hand's. */
        fun handFor(player: Player, school: School): InteractionHand? = when {
            (player.mainHandItem.item as? CatalystItem)?.school == school -> InteractionHand.MAIN_HAND
            (player.offhandItem.item as? CatalystItem)?.school == school -> InteractionHand.OFF_HAND
            else -> null
        }

        fun held(player: Player, school: School): CatalystItem? =
            handFor(player, school)?.let { player.getItemInHand(it).item as? CatalystItem }

        /** Whether [player] holds a catalyst at all, in either hand. */
        fun holdsAny(player: Player): Boolean = player.mainHandItem.item is CatalystItem || player.offhandItem.item is CatalystItem
    }
}

/**
 * A scroll that teaches a spell. Read (right-click) and it's learned, and attuned too if a slot is free.
 * A scroll that names no spell teaches the next of its school the reader doesn't know.
 */
class SpellScrollItem(val school: School, properties: Properties) : Item(properties) {
    fun spellOf(stack: ItemStack): Spell? = stack.get(MagicRegistry.SPELL.get())?.let(Spell::byId)?.takeIf { it.school == school }

    /** A scroll of [spell]. */
    fun of(spell: Spell): ItemStack = ItemStack(this).also { it.set(MagicRegistry.SPELL.get(), spell.id) }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val spell = spellOf(stack) ?: Spellbook.nextUnknown(player, school)
        val known = spell == null || Spellbook.get(player).knows(spell)
        if (player !is ServerPlayer) return if (known) InteractionResult.FAIL else InteractionResult.SUCCESS
        if (spell == null) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.spell.all_known.${school.id}").withColor(school.color))
            return InteractionResult.FAIL
        }
        if (!Spellbook.learn(player, spell)) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.spell.known", spell.title).withColor(school.color))
            return InteractionResult.FAIL
        }
        stack.consume(1, player)
        level.playSound(null, player.x, player.y, player.z, MagicRegistry.LEARN.get(), SoundSource.PLAYERS, 0.9f, 1f)
        val attuned = Spellbook.get(player).attuned(spell)
        player.sendSystemMessage(
            Component.translatable(if (attuned) "message.guildmark.spell.learned" else "message.guildmark.spell.learned_full", spell.title).withColor(school.color),
        )
        return InteractionResult.SUCCESS_SERVER
    }

    override fun getName(stack: ItemStack): Component =
        spellOf(stack)?.let { Component.translatable("item.guildmark.${school.id}_scroll.named", it.title) } ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        val spell = spellOf(stack)
        if (spell == null) {
            builder.accept(Component.translatable("item.guildmark.spell_scroll.next.${school.id}").withStyle(ChatFormatting.GRAY))
            return
        }
        builder.accept(spell.description.copy().withStyle(ChatFormatting.GRAY))
        builder.accept(Component.translatable("item.guildmark.spell_scroll.cost", spell.move.focus.roundToInt()).withColor(0x86A9E6))
        builder.accept(Component.translatable("item.guildmark.spell_scroll.needs", spell.school.stat.title, spell.need).withColor(spell.school.stat.color))
    }
}
