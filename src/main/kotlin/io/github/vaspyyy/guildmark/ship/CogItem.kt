package io.github.vaspyyy.guildmark.ship

import net.minecraft.ChatFormatting
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.stats.Stats
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.HitResult
import java.util.function.Consumer

/**
 * A cog packed away. Used looking at open water, it's set afloat there, bow away from you (or turned to
 * whatever heading fits, if that one doesn't), with whatever was in its hold and the name it was given.
 */
class CogItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY)
        if (hit.type != HitResult.Type.BLOCK) return InteractionResult.PASS
        val pos = hit.blockPos
        val fluid = level.getFluidState(pos)
        if (!fluid.`is`(FluidTags.WATER)) {
            if (!level.isClientSide) player.sendOverlayMessage(Component.translatable("message.guildmark.ship.on_water"))
            return InteractionResult.FAIL
        }
        val cog = ShipRegistry.COG.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE) ?: return InteractionResult.FAIL
        val surface = pos.y + fluid.getHeight(level, pos).toDouble()
        val look = player.yRot
        // Her stern towards you a little out from where you're looking; failing that, turned or moved out further
        for (ahead in AHEAD) {
            for (turn in TURNS) {
                val yaw = look + turn
                val cx = hit.location.x + Cog.forwardX(look) * ahead
                val cz = hit.location.z + Cog.forwardZ(look) * ahead
                cog.snapTo(cx, surface, cz, yaw, 0f)
                if (!cog.canLieAt(level)) continue
                if (level is ServerLevel) {
                    cog.launchFrom(stack, player)
                    level.addFreshEntity(cog)
                    level.gameEvent(player, GameEvent.ENTITY_PLACE, hit.location)
                    level.playSound(null, cog.x, cog.y, cog.z, ShipRegistry.LAUNCH.get(), SoundSource.NEUTRAL, 1.2f, 1f)
                    level.sendParticles(ParticleTypes.SPLASH, cog.x, cog.y + 0.2, cog.z, 50, 1.5, 0.2, 3.5, 0.3)
                    level.sendParticles(ParticleTypes.BUBBLE, cog.x, cog.y - 0.3, cog.z, 30, 1.5, 0.2, 3.5, 0.1)
                    if (player is ServerPlayer) Ships.berthed(player, cog)
                    stack.consume(1, player)
                }
                player.awardStat(Stats.ITEM_USED.get(this))
                return InteractionResult.SUCCESS
            }
        }
        if (!level.isClientSide) player.sendOverlayMessage(Component.translatable("message.guildmark.ship.no_room"))
        return InteractionResult.FAIL
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, adder: Consumer<Component>, flag: TooltipFlag) {
        stack.get(ShipRegistry.HULL.get())?.let {
            adder.accept(Component.translatable("item.guildmark.cog.hull", (it / Cog.MAX_HULL * 100f).toInt()).withStyle(ChatFormatting.GRAY))
        }
        adder.accept(Component.translatable("item.guildmark.cog.tip").withStyle(ChatFormatting.DARK_GRAY))
    }

    private companion object {
        /** Blocks out from where you look her middle is tried at, and the headings tried there (from the way you face). */
        val AHEAD = doubleArrayOf(4.0, 5.5, 3.0, 7.0)
        val TURNS = floatArrayOf(0f, 30f, -30f, 60f, -60f, 90f, -90f, 135f, -135f, 180f)
    }
}
