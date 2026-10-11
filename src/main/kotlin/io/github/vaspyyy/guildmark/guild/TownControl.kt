package io.github.vaspyyy.guildmark.guild

import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.network.OpenAdvancesPayload
import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.ContainerHelper
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.RotationSegment
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.roundToInt

/**
 * Guilds holding villages. A guild leader with Honored standing in a village that has a guild hall can
 * raise their banner there. Held villages pay tribute when the leader visits, pay the leader more for
 * contracts, and call the guild's members to their defence when besieged. Neglect and lost sieges drain
 * loyalty; at zero the banner comes down.
 */
object TownControl {
    const val CLAIM_FEE = 60
    private const val START_LOYALTY = 60
    private const val TRIBUTE_DAYS_CAP = 7
    private const val NEGLECT_DAYS = 7
    private const val VISIT_RANGE = 64.0
    private const val RALLY_RANGE = 160.0
    private const val CONTRACT_BONUS_PERCENT = 10

    enum class ClaimStatus(val key: String) { OK("ok"), NO_GUILD("no_guild"), NO_HALL("no_hall"), STANDING("standing"), CAP("cap"), FEE("fee"), YOURS("yours"), HELD("held") }

    /** How many villages a guild led by an adventurer of this rank index can hold. */
    fun holdingCap(rank: Int): Int = 1 + rank / 2

    fun holderName(level: ServerLevel, guild: Int): String =
        RivalGuild.byId(guild)?.displayName ?: Guilds.get(level).byId(guild)?.name ?: "?"

    fun holderColor(level: ServerLevel, guild: Int): DyeColor =
        RivalGuild.byId(guild)?.color ?: Guilds.get(level).byId(guild)?.color ?: DyeColor.WHITE

    fun status(level: ServerLevel, player: Player, anchor: BlockPos): ClaimStatus {
        val guild = Guilds.get(level).ledBy(player.uuid) ?: return ClaimStatus.NO_GUILD
        val holding = Holdings.get(level).at(anchor)
        if (holding?.guild == guild.id) return ClaimStatus.YOURS
        if (holding != null) return ClaimStatus.HELD
        val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: return ClaimStatus.NO_HALL
        if (board.advanceIndex <= Advance.GUILD_HALL.ordinal) return ClaimStatus.NO_HALL
        if (Standing.tier(level, anchor, player) < StandingTier.HONORED) return ClaimStatus.STANDING
        if (Holdings.get(level).of(guild.id).size >= holdingCap(Progression.get(player).adventurerRank)) return ClaimStatus.CAP
        if (marks(player) < CLAIM_FEE) return ClaimStatus.FEE
        return ClaimStatus.OK
    }

    /** Sneak-use on a board: the server sends the village's advances along with who holds it. */
    fun openAdvances(level: ServerLevel, player: ServerPlayer, anchor: BlockPos) {
        val holding = Holdings.get(level).at(anchor)
        PacketDistributor.sendToPlayer(player, OpenAdvancesPayload(
            anchor,
            VillageNames.of(anchor),
            holding?.let { holderName(level, it.guild) } ?: "",
            holding?.let { holderColor(level, it.guild).textColor } ?: 0,
            holding?.loyalty ?: 0,
            status(level, player, anchor).ordinal,
            CLAIM_FEE,
            holdingCap(Progression.get(player).adventurerRank),
        ))
    }

    fun claim(level: ServerLevel, player: Player, anchor: BlockPos) {
        val status = status(level, player, anchor)
        if (status != ClaimStatus.OK) {
            player.sendSystemMessage(Component.translatable("message.guildmark.claim.${status.key}", CLAIM_FEE, holdingCap(Progression.get(player).adventurerRank)))
            return
        }
        val guild = Guilds.get(level).ledBy(player.uuid) ?: return
        pay(player, CLAIM_FEE)
        take(level, anchor, guild.id)
        val name = VillageNames.of(anchor)
        player.sendSystemMessage(Component.translatable("message.guildmark.claimed", name, guild.name).withStyle { it.withColor(guild.color.textColor) })
        Milestones.award(player, Milestones.BANNER)
    }

    /** [guild] takes the village at [anchor]: banners go up, loyalty starts fresh. Used by rivals too. */
    fun take(level: ServerLevel, anchor: BlockPos, guild: Int) {
        val holdings = Holdings.get(level)
        holdings.at(anchor)?.let { lowerBanners(level, it) }
        val today = VillageBoards.day(level)
        val holding = Holding(anchor, guild, today, today, today, START_LOYALTY)
        raiseBanners(level, holding, holderColor(level, guild))
        holdings.put(holding)
        val name = VillageNames.of(anchor)
        val center = bell(level, anchor) ?: anchor
        level.playSound(null, center, SoundEvents.RAID_HORN.value(), SoundSource.NEUTRAL, 3.0f, 1.4f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x + 0.5, center.y + 1.5, center.z + 0.5, 30, 3.0, 1.0, 3.0, 0.0)
        GuildNews.add(level, "news.guildmark.claimed", holderName(level, guild), name)
    }

    /** The village throws off its banner. [reason] is a message key suffix: neglect, siege or rival. */
    fun release(level: ServerLevel, holding: Holding, reason: String) {
        val name = VillageNames.of(holding.anchor)
        val holder = holderName(level, holding.guild)
        lowerBanners(level, holding)
        Holdings.get(level).remove(holding.anchor)
        Guilds.get(level).byId(holding.guild)?.let { guild ->
            level.server.playerList.getPlayer(guild.leader)?.sendSystemMessage(
                Component.translatable("message.guildmark.lost_village.$reason", name, holder).withStyle { it.withColor(0xE05A4A) }
            )
        }
        GuildNews.add(level, "news.guildmark.lost_village", holder, name)
    }

    /** Every 200 ticks for each player: a leader near one of their villages collects its tribute. */
    fun visit(level: ServerLevel, player: Player) {
        val guild = Guilds.get(level).ledBy(player.uuid) ?: return
        val holdings = Holdings.get(level)
        val today = VillageBoards.day(level)
        for (holding in holdings.of(guild.id)) {
            if (!player.blockPosition().closerThan(holding.anchor, VISIT_RANGE)) continue
            holding.lastVisit = today
            val days = (today - holding.lastTribute).coerceIn(0L, TRIBUTE_DAYS_CAP.toLong()).toInt()
            if (days > 0) {
                val built = (level.getBlockEntity(holding.anchor) as? QuestBoardBlockEntity)?.advanceIndex ?: 0
                val amount = days * (3 + built * 2)
                holding.lastTribute = today
                holding.loyalty = (holding.loyalty + days * 4).coerceAtMost(Holding.MAX_LOYALTY)
                player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), amount), Prediction.SERVER_ONLY)
                player.sendSystemMessage(Component.translatable("message.guildmark.tribute", VillageNames.of(holding.anchor), amount, holding.loyalty).withStyle { it.withColor(0xE8C26A) })
                level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f)
            }
            holdings.changed()
        }
    }

    /** Overworld tick: once a day, neglected villages lose loyalty, and rivals make their moves. */
    fun tick(level: ServerLevel) {
        if (level.gameTime % 200L != 0L) return
        val holdings = Holdings.get(level)
        val today = VillageBoards.day(level)
        if (holdings.lastDay == today) return
        holdings.lastDay = today
        for (holding in holdings.holdings.toList()) {
            if (RivalGuild.byId(holding.guild) != null) continue
            if (Guilds.get(level).byId(holding.guild) == null) {
                release(level, holding, "neglect")
                continue
            }
            if (today - holding.lastVisit > NEGLECT_DAYS) {
                holding.loyalty -= 8
                if (holding.loyalty <= 0) release(level, holding, "neglect")
            }
        }
        holdings.changed()
        Rivals.daily(level, today)
    }

    /** Extra pay for a guild leader turning in a contract at one of their own villages. */
    fun bonus(level: ServerLevel, anchor: BlockPos, player: Player, reward: Int): Int {
        val guild = Guilds.get(level).ledBy(player.uuid) ?: return 0
        if (Holdings.get(level).at(anchor)?.guild != guild.id) return 0
        return (reward * CONTRACT_BONUS_PERCENT / 100.0).roundToInt().coerceAtLeast(1)
    }

    /** A held village is besieged: its guild's members march to the bell and take up arms. */
    fun onSiegeStart(level: ServerLevel, anchor: BlockPos, center: BlockPos) {
        val holding = Holdings.get(level).at(anchor) ?: return
        val guild = Guilds.get(level).byId(holding.guild)
        if (guild == null) {
            Rivals.onSiegeStart(level, holding, center)
            return
        }
        val garrison = level.getEntitiesOfClass(GuildMember::class.java, AABB(center).inflate(RALLY_RANGE)) { it.guild == guild.id }
        for (member in garrison) {
            member.following = false
            member.post = center
            member.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 6000, 0))
            member.addEffect(MobEffectInstance(MobEffects.STRENGTH, 6000, 0))
        }
        level.server.playerList.getPlayer(guild.leader)?.sendSystemMessage(
            Component.translatable("message.guildmark.garrison_rallies", VillageNames.of(anchor), garrison.size).withStyle { it.withColor(guild.color.textColor) }
        )
    }

    /** A siege at a held village ended: loyalty follows the result. */
    fun onSiegeEnd(level: ServerLevel, anchor: BlockPos, won: Boolean) {
        val holding = Holdings.get(level).at(anchor) ?: return
        holding.loyalty = (holding.loyalty + if (won) 15 else -40).coerceAtMost(Holding.MAX_LOYALTY)
        Holdings.get(level).changed()
        if (holding.loyalty <= 0) release(level, holding, "siege")
    }

    fun bell(level: ServerLevel, anchor: BlockPos): BlockPos? =
        level.poiManager.findClosest({ it.`is`(PoiTypes.MEETING) }, anchor, 32, PoiManager.Occupancy.ANY).orElse(null)

    /** Banners in the holder's colour on four sides of the village bell. */
    private fun raiseBanners(level: ServerLevel, holding: Holding, color: DyeColor) {
        val bell = bell(level, holding.anchor) ?: holding.anchor
        for (direction in Direction.Plane.HORIZONTAL) {
            for (distance in 2..4) {
                val column = bell.relative(direction, distance)
                val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column)
                if (!level.getBlockState(top).canBeReplaced() || !level.getBlockState(top.above()).canBeReplaced()) continue
                if (!level.getBlockState(top.below()).isFaceSturdy(level, top.below(), Direction.UP)) continue
                val state = Blocks.BANNER.pick(color).defaultBlockState().setValue(BannerBlock.ROTATION, RotationSegment.convertToSegment(direction))
                level.setBlock(top, state, Block.UPDATE_ALL)
                holding.banners.add(top)
                break
            }
        }
    }

    private fun lowerBanners(level: ServerLevel, holding: Holding) {
        for (pos in holding.banners) {
            if (level.isLoaded(pos) && level.getBlockState(pos).block is BannerBlock) {
                level.destroyBlock(pos, false)
            }
        }
        holding.banners.clear()
    }

    fun marks(player: Player): Int =
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { it.`is`(ModItems.GUILD_MARK.get()) }, Int.MAX_VALUE, true)

    fun pay(player: Player, amount: Int) {
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { it.`is`(ModItems.GUILD_MARK.get()) }, amount, false)
    }
}
