package io.github.vaspyyy.guildmark.guild

import net.minecraft.world.level.Level
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.Entity
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import io.github.vaspyyy.guildmark.Guildmark
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * What the rival guilds do. Each day they may raise their banner over a free village someone has found,
 * and lean on villages players hold until loyalty slips and they send a challenge: answer it by beating
 * their captain at the village bell within two days, or lose the village. Players can take a rival's
 * village the same way. Rival adventurers hang around boards, taking notes off the ones their guild holds.
 */
object Rivals {
    private const val FIRST_DAY = 3
    private const val CHALLENGE_DAYS = 2
    private const val CHALLENGE_LOYALTY = 40
    /** Ticks before a challenger's captain comes back after leaving. */
    private const val CAPTAIN_COOLDOWN = 2400L
    private val captainCooldown = HashMap<BlockPos, Long>()
    private val CAPTAIN_HEALTH = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "captain_health")
    private val CAPTAIN_DAMAGE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "captain_damage")
    private val CAPTAIN_ARMOR = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "captain_armor")

    private val NAMES = listOf(
        "Brann", "Vessa", "Corin", "Mags", "Aldo", "Hesk", "Tamsin", "Orrin", "Pell", "Rook", "Sabine", "Dace",
        "Ilka", "Jory", "Neve", "Quill", "Bastian", "Petra", "Hollis", "Wynn",
    )

    fun captainName(rival: RivalGuild): Component = Component.translatable("rival.guildmark.${rival.key}.captain")

    fun daily(level: ServerLevel, today: Long) {
        val holdings = Holdings.get(level)
        if (!GuildmarkConfig.RIVALS.get()) {
            // Rivals switched off: drop challenges nobody could answer any more
            for (holding in holdings.holdings) holding.challenger = -1
            holdings.changed()
            return
        }
        val random = level.random
        // Challenges nobody answered: the rival takes the village
        for (holding in holdings.holdings.toList()) {
            if (holding.challenger < 0 || today <= holding.challengeDeadline) continue
            val rival = RivalGuild.byId(holding.challenger)
            holding.challenger = -1
            TownControl.release(level, holding, "rival")
            if (rival != null) TownControl.take(level, holding.anchor, rival.id)
        }
        if (today < FIRST_DAY) return

        // Expansion into free villages
        val rivalHeld = holdings.holdings.count { RivalGuild.byId(it.guild) != null }
        val free = holdings.known.filter { holdings.at(it) == null }
        if (free.isNotEmpty() && rivalHeld < max(1, holdings.known.size / 3) && random.nextFloat() < 0.35f) {
            val rival = RivalGuild.entries[random.nextInt(RivalGuild.entries.size)]
            TownControl.take(level, free[random.nextInt(free.size)], rival.id)
        }

        // Pressure on villages players hold
        for (holding in holdings.holdings.toList()) {
            if (RivalGuild.byId(holding.guild) != null || holding.challenger >= 0 || random.nextFloat() >= 0.3f) continue
            holding.loyalty -= 6
            if (holding.loyalty < CHALLENGE_LOYALTY) {
                val rival = RivalGuild.entries[random.nextInt(RivalGuild.entries.size)]
                holding.challenger = rival.id
                holding.challengeDeadline = today + CHALLENGE_DAYS
                val name = VillageNames.of(holding.anchor)
                Guilds.get(level).byId(holding.guild)?.let { guild ->
                    level.server.playerList.getPlayer(guild.leader)?.sendSystemMessage(
                        Component.translatable("message.guildmark.rival_challenge", rival.displayName, name, captainName(rival), CHALLENGE_DAYS)
                            .withStyle { it.withColor(0xE05A4A) }
                    )
                }
                GuildNews.add(level, "news.guildmark.rival_challenge", rival.displayName, name)
            }
        }
        holdings.changed()
    }

    /** Keeps a guild leader's tracker showing the challenges against their villages. */
    fun syncChallenges(level: ServerLevel, player: Player) {
        val guild = Guilds.get(level).ledBy(player.uuid)
        val today = VillageBoards.day(level)
        val challenges = guild?.let { g ->
            Holdings.get(level).of(g.id).filter { it.challenger >= 0 }.mapNotNull { holding ->
                val rival = RivalGuild.byId(holding.challenger) ?: return@mapNotNull null
                RivalChallenge(holding.anchor, VillageNames.of(holding.anchor), rival.key, (holding.challengeDeadline - today).toInt().coerceAtLeast(0))
            }
        } ?: listOf()
        if (player.getData(ModAttachments.CHALLENGES) != challenges) player.setData(ModAttachments.CHALLENGES, challenges)
    }

    /** Every 200 ticks for each player: rivals turn up at the village they're in. */
    fun tickPlayer(level: ServerLevel, player: Player) {
        if (!GuildmarkConfig.RIVALS.get() || level.dimension() != Level.OVERWORLD) return
        val anchor = VillageBoards.villageBoard(level, player.blockPosition()) ?: return
        val holdings = Holdings.get(level)
        holdings.remember(anchor)
        val holding = holdings.at(anchor)
        val bell = TownControl.bell(level, anchor) ?: anchor
        val near = level.getEntitiesOfClass(RivalAdventurer::class.java, AABB(bell).inflate(64.0))

        // A challenged leader comes home: the challenger's captain is waiting at the bell
        val guild = Guilds.get(level).ledBy(player.uuid)
        if (holding != null && holding.challenger >= 0 && guild?.id == holding.guild && near.none { it.captain }) {
            val rival = RivalGuild.byId(holding.challenger) ?: return
            // Waits for the leader to come to the bell, and doesn't come straight back after leaving
            if (!player.blockPosition().closerThan(bell, 24.0) || level.gameTime < (captainCooldown[anchor] ?: 0L)) return
            captainCooldown[anchor] = level.gameTime + CAPTAIN_COOLDOWN
            spawnCaptain(level, rival, anchor, guild.id, defending = false)?.shout(level, "arrive")
            return
        }

        val rivalHere = holding?.let { RivalGuild.byId(it.guild) }
        if (rivalHere != null) {
            if (near.count { !it.captain && it.rival == rivalHere } < 2) spawnAdventurer(level, rivalHere, anchor, bell, 4800)
        } else if (near.isEmpty() && level.random.nextInt(24) == 0) {
            spawnAdventurer(level, RivalGuild.entries[level.random.nextInt(RivalGuild.entries.size)], anchor, bell, 2400)
        }
    }

    /** A player leader challenges the rival holding the village at [anchor]. */
    fun challenge(level: ServerLevel, player: Player, anchor: BlockPos) {
        val status = TownControl.status(level, player, anchor)
        if (status != TownControl.ClaimStatus.RIVAL) {
            player.sendSystemMessage(Component.translatable("message.guildmark.claim.${status.key}", TownControl.CLAIM_FEE, TownControl.holdingCap(Progression.get(player).adventurerRank)))
            return
        }
        val guild = Guilds.get(level).ledBy(player.uuid) ?: return
        val rival = Holdings.get(level).at(anchor)?.let { RivalGuild.byId(it.guild) } ?: return
        val bell = TownControl.bell(level, anchor) ?: anchor
        if (level.getEntitiesOfClass(RivalAdventurer::class.java, AABB(bell).inflate(64.0)) { it.captain }.isNotEmpty()) {
            player.sendSystemMessage(Component.translatable("message.guildmark.captain_waiting", captainName(rival)))
            return
        }
        spawnCaptain(level, rival, anchor, guild.id, defending = true)?.shout(level, "accept")
    }

    fun spawnAdventurer(level: ServerLevel, rival: RivalGuild, anchor: BlockPos, near: BlockPos, lifespan: Int): RivalAdventurer? {
        val spot = groundNear(level, near, 3, 10) ?: return null
        val adventurer = ModEntities.RIVAL.get().create(level, EntitySpawnReason.EVENT) ?: return null
        adventurer.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, level.random.nextFloat() * 360f, 0f)
        adventurer.guildKey = rival.key
        adventurer.anchor = anchor
        adventurer.leaveAt = level.gameTime + lifespan
        adventurer.setHomeTo(near, 16)
        adventurer.customName = Component.translatable("rival.guildmark.member", NAMES[level.random.nextInt(NAMES.size)], rival.displayName)
        adventurer.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(if (level.random.nextInt(3) == 0) Items.IRON_AXE else Items.IRON_SWORD))
        adventurer.setDropChance(EquipmentSlot.MAINHAND, 0.05f)
        level.addFreshEntity(adventurer)
        return adventurer
    }

    private fun spawnCaptain(level: ServerLevel, rival: RivalGuild, anchor: BlockPos, foe: Int, defending: Boolean): RivalAdventurer? {
        val bell = TownControl.bell(level, anchor) ?: anchor
        val spot = groundNear(level, bell, 2, 6) ?: return null
        val captain = ModEntities.RIVAL.get().create(level, EntitySpawnReason.EVENT) ?: return null
        captain.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, level.random.nextFloat() * 360f, 0f)
        captain.guildKey = rival.key
        captain.captain = true
        captain.anchor = anchor
        captain.foe = foe
        captain.defending = defending
        captain.home = bell
        captain.customName = Component.translatable("rival.guildmark.captain_of", captainName(rival), rival.displayName)
        captain.isCustomNameVisible = true
        captain.getAttribute(Attributes.MAX_HEALTH)?.addPermanentModifier(AttributeModifier(CAPTAIN_HEALTH, 120.0, AttributeModifier.Operation.ADD_VALUE))
        captain.getAttribute(Attributes.ATTACK_DAMAGE)?.addPermanentModifier(AttributeModifier(CAPTAIN_DAMAGE, 5.0, AttributeModifier.Operation.ADD_VALUE))
        captain.getAttribute(Attributes.ARMOR)?.addPermanentModifier(AttributeModifier(CAPTAIN_ARMOR, 8.0, AttributeModifier.Operation.ADD_VALUE))
        captain.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.baseValue = 0.6
        captain.health = captain.maxHealth
        captain.setItemSlot(EquipmentSlot.MAINHAND, meleeRelic(level))
        captain.setGuaranteedDrop(EquipmentSlot.MAINHAND)
        level.addFreshEntity(captain)
        level.playSound(null, bell, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 4.0f, 1.2f)
        return captain
    }

    /** A rare or better melee relic for a captain to wield (and drop). */
    private fun meleeRelic(level: ServerLevel): ItemStack {
        repeat(12) {
            val relic = Relics.roll(level, 4, Rarity.RARE)
            if (relic.get(DataComponents.EQUIPPABLE) == null && !relic.`is`(Items.BOW) && !relic.`is`(Items.CROSSBOW)) return relic
        }
        return ItemStack(Items.DIAMOND_SWORD)
    }

    fun onCaptainDefeated(level: ServerLevel, captain: RivalAdventurer, killerEntity: Entity?) {
        val holdings = Holdings.get(level)
        val holding = holdings.at(captain.anchor)
        val name = VillageNames.of(captain.anchor)
        val rival = captain.rival
        val foe = Guilds.get(level).byId(captain.foe)
        val leader = foe?.let { level.server.playerList.getPlayer(it.leader) }
        // Only the foe's side wins the fight: its leader, the leader's party, or its sworn members
        val killer = (killerEntity as? Projectile)?.owner ?: killerEntity
        val parties = Parties.get(level.server)
        val won = foe != null && when (killer) {
            is Player -> killer.uuid == foe.leader || parties.of(foe.leader)?.let { killer.uuid in it.members } == true
            is GuildMember -> killer.guild == foe.id
            else -> false
        }
        if (!won) return
        when {
            // Taking a rival's village (the leader must still have room for it)
            captain.defending && holding != null && holding.guild == rival.id && foe != null &&
                (leader == null || holdings.of(foe.id).size < TownControl.holdingCap(Progression.get(leader).adventurerRank)) -> {
                TownControl.take(level, captain.anchor, foe.id)
                leader?.sendSystemMessage(Component.translatable("message.guildmark.rival_village_taken", name, rival.displayName, foe.name).withStyle { it.withColor(foe.color.textColor) })
                leader?.let { Milestones.award(it, Milestones.TAKEOVER) }
            }
            // Answering a rival's challenge
            !captain.defending && holding != null && holding.guild == captain.foe && holding.challenger == rival.id -> {
                holding.challenger = -1
                holding.loyalty = (holding.loyalty + 30).coerceAtMost(Holding.MAX_LOYALTY)
                holdings.changed()
                leader?.sendSystemMessage(Component.translatable("message.guildmark.rival_repelled", rival.displayName, name, holding.loyalty).withStyle { it.withColor(0x7FD87F) })
                leader?.let { Milestones.award(it, Milestones.REPELLED) }
                GuildNews.add(level, "news.guildmark.rival_repelled", foe?.name ?: "?", rival.displayName, name)
            }
        }
        (killer as? Player)?.inventory?.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), 20), Prediction.SERVER_ONLY)
        // The captain's people leave with them
        for (adventurer in level.getEntitiesOfClass(RivalAdventurer::class.java, AABB(captain.blockPosition()).inflate(64.0)) { it.rival == rival && !it.captain }) {
            adventurer.leaveAt = level.gameTime + 100
        }
    }

    /** A rival-held village under siege gets the rival's adventurers as defenders. */
    fun onSiegeStart(level: ServerLevel, holding: Holding, center: BlockPos) {
        val rival = RivalGuild.byId(holding.guild) ?: return
        repeat(3) { spawnAdventurer(level, rival, holding.anchor, center, 9000) }
    }

    private fun groundNear(level: ServerLevel, around: BlockPos, min: Int, max: Int): BlockPos? {
        val random = level.random
        repeat(30) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = min + random.nextInt(max - min + 1)
            val column = around.offset((cos(angle) * distance).toInt(), 0, (sin(angle) * distance).toInt())
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column)
            val ground = top.below()
            if (level.getFluidState(ground).isEmpty && level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP) &&
                level.getBlockState(top).canBeReplaced() && level.getBlockState(top.above()).canBeReplaced()
            ) return top
        }
        return null
    }
}
