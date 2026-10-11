package io.github.vaspyyy.guildmark.road

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.guild.Holdings
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB

/**
 * Coaches run along finished Trade Roads. At a village with a road, `/coach` (or the button on the
 * Advance screen) lists where its roads lead and the fare; riding takes you, nearby party members and
 * your following guild members to the far end. A traveller under escort can't ride: they walk.
 */
object Coaches {
    private const val BLOCKS_PER_MARK = 60
    private const val MIN_FARE = 3
    /** Party members this close board the coach too. */
    private const val BOARDING_RANGE = 8.0

    class Stop(val road: Road, val from: BlockPos, val to: BlockPos, val name: Component, val fare: Int)

    /** The coach stops reachable from the village the player is standing in, or null if they're not in one. */
    fun stops(level: ServerLevel, near: BlockPos): List<Stop>? {
        if (level.dimension() != Level.OVERWORLD) return null
        val anchor = VillageBoards.villageBoard(level, near) ?: return null
        val center = Advances.villageCenter(level, anchor)
        val known = Holdings.get(level).known
        return RoadNetwork.get(level).roads.filter { it.finished && it.touches(center) }.map { road ->
            val end = road.otherEnd(center)
            val name: Component = known.filter { it.closerThan(end, 64.0) }.minByOrNull { it.distSqr(end) }?.let { Component.literal(VillageNames.of(it)) }
                ?: Component.translatable("message.guildmark.coach.unknown", end.x, end.z)
            val distance = Math.sqrt(center.distSqr(end))
            Stop(road, center, end, name, (distance / BLOCKS_PER_MARK).toInt().coerceAtLeast(MIN_FARE))
        }
    }

    fun list(player: ServerPlayer) {
        val level = player.level()
        val stops = stops(level, player.blockPosition())
        if (stops == null || stops.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.guildmark.coach.none"))
            return
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.coach.header").withStyle { it.withColor(0xE8C26A) })
        for (stop in stops) {
            val ride = Component.translatable("message.guildmark.coach.ride_button").withStyle {
                it.withColor(0x7FD87F).withUnderlined(true)
                    .withClickEvent(ClickEvent.RunCommand("/coach ride ${stop.to.x} ${stop.to.z}"))
                    .withHoverEvent(HoverEvent.ShowText(Component.translatable("message.guildmark.coach.ride_hover", stop.name, stop.fare)))
            }
            player.sendSystemMessage(Component.translatable("message.guildmark.coach.stop", stop.name, stop.fare, ride))
        }
    }

    fun ride(player: ServerPlayer, x: Int, z: Int): Boolean {
        val level = player.level()
        val stop = stops(level, player.blockPosition())?.firstOrNull { it.to.x == x && it.to.z == z }
            ?: return fail(player, "gone")
        val escorting = (0 until player.inventory.containerSize).any { slot ->
            player.inventory.getItem(slot).get(ModDataComponents.QUEST_NOTE.get())?.type == QuestType.ESCORT &&
                player.inventory.getItem(slot).has(ModDataComponents.CONTRACT_STATE.get())
        }
        if (escorting) return fail(player, "escort")
        if (TownControl.marks(player) < stop.fare) return fail(player, "fare", stop.fare)
        TownControl.pay(player, stop.fare)

        // Set down a little way up the road from the far village's centre
        val path = stop.road.pointsFrom(stop.from)
        val drop = path[(path.size - 4).coerceAtLeast(0)]
        level.getChunk(drop.x shr 4, drop.z shr 4)
        val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, drop)

        val members = level.getEntitiesOfClass(GuildMember::class.java, AABB(player.blockPosition()).inflate(16.0)) { it.leader == player.uuid && it.following }
        val riders = listOf(player) + Parties.nearbyMates(player, BOARDING_RANGE)
        level.playSound(null, player.blockPosition(), SoundEvents.HORSE_GALLOP, SoundSource.NEUTRAL, 1.0f, 0.9f)
        for ((i, rider) in riders.withIndex()) {
            val spot = ground.offset(i % 2, 0, i / 2)
            rider.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false))
            rider.teleportTo(level, spot.x + 0.5, ground.y.toDouble(), spot.z + 0.5, setOf(), rider.yRot, rider.xRot, true)
            rider.sendSystemMessage(Component.translatable("message.guildmark.coach.arrived", stop.name).withStyle { it.withColor(0xE8C26A) })
        }
        for (member in members) member.teleportTo(level, ground.x + 0.5, ground.y.toDouble(), ground.z - 1.5, setOf(), member.yRot, member.xRot, false)
        level.playSound(null, ground, SoundEvents.HORSE_GALLOP, SoundSource.NEUTRAL, 1.0f, 0.9f)
        return true
    }

    private fun fail(player: ServerPlayer, key: String, vararg args: Any): Boolean {
        player.sendSystemMessage(Component.translatable("message.guildmark.coach.$key", *args).withStyle { it.withColor(0xE05A4A) })
        return false
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("coach")
                .executes { context ->
                    list(context.source.playerOrException)
                    1
                }
                .then(Commands.literal("ride").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes { context ->
                    if (ride(context.source.playerOrException, IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z"))) 1 else 0
                })))
        )
    }
}
