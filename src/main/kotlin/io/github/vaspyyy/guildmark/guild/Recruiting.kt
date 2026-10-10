package io.github.vaspyyy.guildmark.guild

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.ai.memory.MemoryModuleType

/**
 * Founding a guild and recruiting villagers into it. A guild is founded at a guild hall desk; its leader
 * recruits by sneaking and using a grown villager in a village that trusts them, paying the recruit's
 * oath fee in Guild Marks. How many recruits they can lead grows with their adventurer rank.
 */
object Recruiting {
    const val FOUNDING_FEE = 50
    const val RECRUIT_FEE = 20
    val MIN_FOUNDING_RANK = AdventurerRank.E

    /** Found a guild for [player]. Sends a message and returns false if they can't. */
    fun found(level: ServerLevel, player: Player, rawName: String, colorId: Int): Boolean {
        val name = rawName.trim()
        val guilds = Guilds.get(level)
        val problem = when {
            guilds.ledBy(player.uuid) != null -> "message.guildmark.guild_already"
            Progression.get(player).adventurerRank < MIN_FOUNDING_RANK.ordinal -> "message.guildmark.guild_rank"
            name.length !in 3..24 -> "message.guildmark.guild_name_length"
            guilds.nameTaken(name) -> "message.guildmark.guild_name_taken"
            marks(player) < FOUNDING_FEE -> "message.guildmark.guild_fee"
            else -> null
        }
        if (problem != null) {
            player.sendSystemMessage(Component.translatable(problem, MIN_FOUNDING_RANK.letter, FOUNDING_FEE))
            return false
        }
        pay(player, FOUNDING_FEE)
        val color = DyeColor.byId(colorId)
        val guild = guilds.found(name, color, player.uuid)
        val banner = ItemStack(Blocks.BANNER.pick(color).asItem())
        banner.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("item.guildmark.guild_banner", guild.name))
        player.inventory.placeItemBackInInventory(banner, Prediction.SERVER_ONLY)
        player.sendSystemMessage(Component.translatable("message.guildmark.guild_founded", guild.name))
        level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f)
        GuildNews.add(level, "news.guildmark.guild_founded", player.name.string, guild.name)
        return true
    }

    /** Sneak-use on a villager: try to swear it into the player's guild. */
    fun onInteract(level: ServerLevel, player: Player, villager: Villager, hand: InteractionHand): InteractionResult? {
        if (!player.isSecondaryUseActive || villager is GuildMember || villager.isBaby) return null
        if (villager.hasData(ModAttachments.RECEPTIONIST) || villager.hasData(ModAttachments.TRAFFIC) || villager.hasData(ModAttachments.CHARACTER)) return null
        val guild = Guilds.get(level).ledBy(player.uuid) ?: return null
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS

        val cap = Guilds.memberCap(Progression.get(player).adventurerRank)
        val board = VillageBoards.villageBoard(level, villager.blockPosition())
        val problem = when {
            guild.members.size >= cap -> Component.translatable("message.guildmark.recruit_cap", cap)
            board == null || Standing.tier(level, board, player) < StandingTier.TRUSTED ->
                Component.translatable("message.guildmark.recruit_trust", StandingTier.TRUSTED.title)
            marks(player) < RECRUIT_FEE -> Component.translatable("message.guildmark.recruit_fee", RECRUIT_FEE)
            else -> null
        }
        if (problem != null) {
            player.sendOverlayMessage(problem)
            villager.playSound(SoundEvents.VILLAGER_NO, 1.0f, 1.0f)
            return InteractionResult.SUCCESS
        }
        pay(player, RECRUIT_FEE)
        // Free its bed and workstation for the rest of the village
        for (memory in listOf(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE, MemoryModuleType.MEETING_POINT)) {
            villager.releasePoi(memory)
        }

        val member = ModEntities.GUILD_MEMBER.get().create(level, EntitySpawnReason.CONVERSION) ?: return InteractionResult.SUCCESS
        member.snapTo(villager.x, villager.y, villager.z, villager.yRot, villager.xRot)
        member.villagerData = villager.villagerData
        member.customName = (villager.customName ?: Component.translatable("entity.guildmark.guild_member.named", QuestGenerator.nameFor(villager.uuid.leastSignificantBits), guild.name))
            .copy().withColor(guild.color.textColor)
        member.isCustomNameVisible = true
        member.leader = player.uuid
        member.guild = guild.id
        member.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(if (Progression.get(player).adventurerRank >= AdventurerRank.C.ordinal) Items.IRON_SWORD else Items.STONE_SWORD))
        member.setPersistenceRequired()
        villager.discard()
        level.addFreshEntity(member)
        Guilds.get(level).addMember(guild, member.uuid)

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, member.x, member.y + 1.2, member.z, 20, 0.5, 0.6, 0.5, 0.0)
        member.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0f, 1.0f)
        player.sendSystemMessage(Component.translatable("message.guildmark.recruited", member.name, guild.name, guild.members.size, cap))
        return InteractionResult.SUCCESS
    }

    /** A guild member died: strike them from the rolls and tell their leader. */
    fun onMemberDeath(level: ServerLevel, member: GuildMember) {
        Guilds.get(level).removeMember(member.uuid)
        member.leaderPlayer()?.sendSystemMessage(Component.translatable("message.guildmark.member_died", member.name))
    }

    private fun marks(player: Player): Int =
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { it.`is`(ModItems.GUILD_MARK.get()) }, Int.MAX_VALUE, true)

    private fun pay(player: Player, amount: Int) {
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { it.`is`(ModItems.GUILD_MARK.get()) }, amount, false)
    }
}
