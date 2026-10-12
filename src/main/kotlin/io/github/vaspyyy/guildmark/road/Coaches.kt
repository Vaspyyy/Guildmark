package io.github.vaspyyy.guildmark.road

import com.mojang.brigadier.CommandDispatcher
import kotlin.math.abs
import java.util.UUID
import net.minecraft.world.phys.Vec3
import net.minecraft.world.entity.Entity
import io.github.vaspyyy.guildmark.quest.Expeditions
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
 * Advance screen) lists where its roads lead and the fare; riding takes you and your following guild
 * members to the far end, and nearby party members get a button to ride after you on their own fare.
 * A traveller under escort can't ride: they walk. Nor can a mount: get down first.
 */
object Coaches {
    private const val BLOCKS_PER_MARK = 60
    private const val MIN_FARE = 3
    /** Party members this close are offered a seat on the next coach. */
    private const val BOARDING_RANGE = 8.0
    /** How long that offer stands, and how far from where the coach left they may wander meanwhile. */
    private const val FOLLOW_TICKS = 1200L
    private const val FOLLOW_RANGE = 24.0

    /** A road out of the village: where it ends, the village there if anyone has found it, and the fare. */
    class Stop(val road: Road, val from: BlockPos, val to: BlockPos, val name: Component, val fare: Int, val village: BlockPos?)

    /** A party member's offer to ride after their mate: where the coach left from, and where it sets down. */
    private class Boarding(val origin: BlockPos, val drop: BlockPos, val name: Component, val fare: Int, val expires: Long)

    private val boardings = HashMap<UUID, Boarding>()

    private fun horizontal(a: BlockPos, b: BlockPos): Double = Expeditions.horizontalDistance(a, b)

    /** The coach stops reachable from the village the player is standing in, or null if they're not in one. */
    fun stops(level: ServerLevel, near: BlockPos): List<Stop>? {
        if (level.dimension() != Level.OVERWORLD) return null
        val anchor = VillageBoards.villageBoard(level, near) ?: return null
        val center = Advances.villageCenter(level, anchor)
        val known = Holdings.get(level).known
        return RoadNetwork.get(level).roads.filter { it.finished && it.touches(center) }.map { road ->
            // Road ends are stored at y 0, so compare on the map only
            val end = road.otherEnd(center)
            val village = known.filter { horizontal(it, end) < 64.0 }.minByOrNull { horizontal(it, end) }
            val name: Component = village?.let { Component.literal(VillageNames.of(it)) }
                ?: Component.translatable("message.guildmark.coach.unknown", end.x, end.z)
            Stop(road, center, end, name, (horizontal(center, end) / BLOCKS_PER_MARK).toInt().coerceAtLeast(MIN_FARE), village)
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

    private fun escorting(player: ServerPlayer): Boolean = (0 until player.inventory.containerSize).any { slot ->
        val stack = player.inventory.getItem(slot)
        stack.get(ModDataComponents.QUEST_NOTE.get())?.type == QuestType.ESCORT && stack.has(ModDataComponents.CONTRACT_STATE.get())
    }

    /** Why [player] can't board right now, as a message key, or null if they can. */
    private fun cantBoard(player: ServerPlayer, fare: Int): String? = when {
        !player.isAlive -> "gone"
        player.isPassenger -> "mounted"
        escorting(player) -> "escort"
        TownControl.marks(player) < fare -> "fare"
        else -> null
    }

    /** A clear spot for [entity] at or a few blocks around the column [at], standing on something and out of water. */
    private fun landing(level: ServerLevel, entity: Entity, at: BlockPos): Vec3? {
        for (r in 0..3) for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(abs(dx), abs(dz)) != r) continue
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.offset(dx, 0, dz))
            if (level.getBlockState(top.below()).getCollisionShape(level, top.below()).isEmpty || !level.getFluidState(top).isEmpty) continue
            val spot = Vec3(top.x + 0.5, top.y.toDouble(), top.z + 0.5)
            if (level.noCollision(entity, entity.type.getSpawnAABB(spot.x, spot.y, spot.z))) return spot
        }
        return null
    }

    private fun travel(player: ServerPlayer, spot: Vec3, name: Component) {
        player.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false))
        player.teleportTo(player.level(), spot.x, spot.y, spot.z, setOf(), player.yRot, player.xRot, true)
        player.sendSystemMessage(Component.translatable("message.guildmark.coach.arrived", name).withStyle { it.withColor(0xE8C26A) })
    }

    fun ride(player: ServerPlayer, x: Int, z: Int): Boolean {
        val level = player.level()
        val stop = stops(level, player.blockPosition())?.firstOrNull { it.to.x == x && it.to.z == z }
            ?: return fail(player, "gone")
        cantBoard(player, stop.fare)?.let { return fail(player, it, stop.fare) }

        // Set down a little way up the road from the far village's centre
        val path = stop.road.pointsFrom(stop.from)
        if (path.size < 2) return fail(player, "gone")
        val drop = path[maxOf(path.size - 4, path.size / 2)]
        level.getChunk(drop.x shr 4, drop.z shr 4)
        val spot = landing(level, player, drop) ?: return fail(player, "blocked")
        TownControl.pay(player, stop.fare)

        val origin = player.blockPosition()
        val members = level.getEntitiesOfClass(GuildMember::class.java, AABB(origin).inflate(16.0)) { it.leader == player.uuid && it.following && !it.isPassenger }
        level.playSound(null, origin, SoundEvents.HORSE_GALLOP, SoundSource.NEUTRAL, 1.0f, 0.9f)
        travel(player, spot, stop.name)
        for ((i, member) in members.withIndex()) {
            val at = landing(level, member, drop.offset(i % 3 - 1, 0, -2 - i / 3)) ?: spot
            member.teleportTo(level, at.x, at.y, at.z, setOf(), member.yRot, member.xRot, false)
        }
        level.playSound(null, BlockPos.containing(spot), SoundEvents.HORSE_GALLOP, SoundSource.NEUTRAL, 1.0f, 0.9f)

        // Party members nearby can ride after them, on their own fare
        for (mate in Parties.nearbyMates(player, BOARDING_RANGE)) {
            boardings[mate.uuid] = Boarding(origin, drop, stop.name, stop.fare, level.gameTime + FOLLOW_TICKS)
            val button = Component.translatable("message.guildmark.coach.follow_button").withStyle {
                it.withColor(0x7FD87F).withUnderlined(true)
                    .withClickEvent(ClickEvent.RunCommand("/coach follow"))
                    .withHoverEvent(HoverEvent.ShowText(Component.translatable("message.guildmark.coach.ride_hover_alone", stop.name, stop.fare)))
            }
            mate.sendSystemMessage(Component.translatable("message.guildmark.coach.follow", player.displayName, stop.name, button).withStyle { it.withColor(0xE8C26A) })
        }
        return true
    }

    /** A party member takes up the offer to ride after their mate. */
    fun follow(player: ServerPlayer): Boolean {
        val level = player.level()
        val boarding = boardings[player.uuid]
        if (boarding == null || level.gameTime > boarding.expires || level.dimension() != Level.OVERWORLD ||
            horizontal(player.blockPosition(), boarding.origin) > FOLLOW_RANGE) {
            boardings.remove(player.uuid)
            return fail(player, "missed")
        }
        cantBoard(player, boarding.fare)?.let { return fail(player, it, boarding.fare) }
        level.getChunk(boarding.drop.x shr 4, boarding.drop.z shr 4)
        val spot = landing(level, player, boarding.drop.offset(1, 0, 0)) ?: return fail(player, "blocked")
        boardings.remove(player.uuid)
        TownControl.pay(player, boarding.fare)
        level.playSound(null, player.blockPosition(), SoundEvents.HORSE_GALLOP, SoundSource.NEUTRAL, 1.0f, 0.9f)
        travel(player, spot, boarding.name)
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
                .then(Commands.literal("follow").executes { context ->
                    if (follow(context.source.playerOrException)) 1 else 0
                })
        )
    }
}
