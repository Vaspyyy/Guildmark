package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

/**
 * Buckling a Pack Harness on an animal, taking it off, and who may use an animal that wears one (living-world/trader/design.md,
 * Part 3, "The pack animal"). All of it happens when a player uses something on a tame donkey, mule or llama, and is decided on the
 * server.
 *
 * The harness needs the animal's chest and the animal's own container is vanilla's, so the chest is fitted the way vanilla fits it:
 * by a use of a chest on the animal (`AbstractChestedHorse.mobInteract`, which sets the chest flag and rebuilds the container
 * keeping what was in it). `createInventory` is protected, so the alternative is an access transformer; this needs none and changes
 * nothing in the build. The player's hand holds a chest for the length of that one call and its own stack is back before the call
 * returns, so nothing is lost if it fails.
 *
 * With carrying switched off none of this is looked at and an animal that wears a harness is any other tame animal: it opens for
 * whoever vanilla lets open it.
 */
object Harness {
    /** Hooks the use of things on animals. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.EntityInteract::class.java) { onInteract(it) }
    }

    private fun onInteract(event: PlayerInteractEvent.EntityInteract) {
        val player = event.entity as? ServerPlayer ?: return
        val target = event.target as? AbstractHorse ?: return
        if (!TradeConfig.carry()) return
        val stack = event.itemStack
        if (stack.item == ModItems.PACK_HARNESS.get()) {
            event.cancellationResult = useHarness(player, target, stack, event.hand)
            event.isCanceled = true
            return
        }
        val kit = (target as? AbstractChestedHorse)?.let { PackKit.of(it) } ?: return
        if (!mayUse(player, target)) {
            player.sendOverlayMessage(notYours(kit))
            event.cancellationResult = InteractionResult.FAIL
            event.isCanceled = true
        }
    }

    /**
     * Whether [player] may open, ride and lead [animal]: anyone, if it has no owner; else its owner, a member of the owner's party, or
     * an operator in creative.
     */
    fun mayUse(player: Player, animal: AbstractHorse): Boolean {
        val owner = animal.ownerReference?.uuid ?: return true
        if (owner == player.uuid) return true
        val server = (player.level() as? ServerLevel)?.server ?: return true
        if (Parties.get(server).of(owner)?.members?.contains(player.uuid) == true) return true
        return player.isCreative && player is ServerPlayer && server.playerList.isOp(player.nameAndId())
    }

    /** The line a stranger is told: "That is Ransom's pack animal.". */
    private fun notYours(kit: PackKit): Component =
        if (kit.owner.isEmpty()) Component.translatable("message.guildmark.trade.pack.not_yours_unknown")
        else Component.translatable("message.guildmark.trade.pack.not_yours", kit.owner)

    private fun refuse(player: ServerPlayer, why: String, vararg args: Any): InteractionResult {
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.harness.$why", *args))
        return InteractionResult.FAIL
    }

    /** What using the harness in [hand] on [animal] does: buckles it on, takes it off (sneaking), or says why not. */
    private fun useHarness(player: ServerPlayer, animal: AbstractHorse, stack: ItemStack, hand: InteractionHand): InteractionResult {
        if (animal !is AbstractChestedHorse) return refuse(player, "kind")
        val kit = PackKit.of(animal)
        if (kit != null) {
            if (!mayUse(player, animal)) {
                player.sendOverlayMessage(notYours(kit))
                return InteractionResult.FAIL
            }
            if (!player.isShiftKeyDown) return refuse(player, "already")
            takeOff(animal, player)
            return InteractionResult.SUCCESS
        }
        if (animal.isBaby) return refuse(player, "baby")
        if (!animal.isTamed) return refuse(player, "untamed")
        val owner = animal.ownerReference?.uuid
        if (owner != null && owner != player.uuid) {
            val name = animal.owner?.name?.string
            return if (name == null) refuse(player, "not_yours_unknown") else refuse(player, "not_yours", name)
        }
        if (animal.isVehicle) return refuse(player, "busy")
        if (!fitChest(player, animal, hand)) return refuse(player, "failed")
        if (owner == null) animal.setOwner(player)
        fit(animal, player)
        stack.consume(1, player)
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.harness.fitted"))
        return InteractionResult.SUCCESS
    }

    /**
     * Makes sure [animal] has a chest, by the use of one on it as vanilla does it (see the class notes), and answers whether it has
     * one. An animal that has one already is left as it is, with what is in it. The player's sneaking is let go of for the call, since
     * vanilla opens the inventory instead when it is held, and put back; so is whatever they hold in [hand].
     */
    fun fitChest(player: ServerPlayer, animal: AbstractChestedHorse, hand: InteractionHand): Boolean {
        if (animal.hasChest()) return true
        val held = player.getItemInHand(hand)
        val sneaking = player.isShiftKeyDown
        try {
            if (sneaking) player.isShiftKeyDown = false
            player.setItemInHand(hand, ItemStack(Items.CHEST))
            animal.mobInteract(player, hand)
        } finally {
            player.setItemInHand(hand, held)
            if (sneaking) player.isShiftKeyDown = true
        }
        return animal.hasChest()
    }

    /**
     * Puts the kit on [animal] for [owner]: it follows, its blanket is the colour its owner's is always, its bell rings, and the owner's
     * name is kept for the line a stranger is told. Nothing is checked here (the animal needs a chest and a tame owner; see [useHarness]).
     */
    fun fit(animal: AbstractChestedHorse, owner: Player) {
        animal.setData(Carry.PACK_KIT, PackKit(PackKit.Mode.FOLLOW, PackKit.colourOf(owner.uuid), true, owner.name.string, animal.blockPosition()))
        PackFollow.adopt(animal)
        (animal.level() as? ServerLevel)?.playSound(null, animal.x, animal.y, animal.z, TradeSounds.get("trade.harness_buckle"), SoundSource.NEUTRAL, 1.0f, 1.0f)
    }

    /** Takes the harness off [animal] and puts it in [player]'s hands (or at their feet): the kit goes, the chest and what is in it stay. */
    fun takeOff(animal: AbstractChestedHorse, player: ServerPlayer) {
        unfit(animal)
        Trading.give(player, ModItems.PACK_HARNESS.get(), 1)
        (animal.level() as? ServerLevel)?.playSound(null, animal.x, animal.y, animal.z, TradeSounds.get("trade.harness_buckle"), SoundSource.NEUTRAL, 0.8f, 0.8f)
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.harness.taken_off"))
    }

    /** Takes the kit off [animal] without giving anything back (the operators' `trade pack unharness` and the harness being taken off both end here). */
    fun unfit(animal: AbstractChestedHorse) {
        PackFollow.release(animal)
        animal.removeData(Carry.PACK_KIT)
    }
}
