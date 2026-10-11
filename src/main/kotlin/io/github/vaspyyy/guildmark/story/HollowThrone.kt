package io.github.vaspyyy.guildmark.story

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.Filterable
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.WrittenBookContent
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Hollow Throne, where the King was first crowned and the only place his shards can be broken. It's
 * raised far from spawn the first time anyone needs it: a black ring of seven soul-fire pillars round a
 * dais, the throne at its north edge and the Shattering Stone (a lodestone) at its heart. Using the
 * stone with all seven shards wakes the King.
 */
class HollowThrone(var altar: Optional<BlockPos> = Optional.empty(), var defeated: Int = 0) : SavedData() {
    /** The King currently walking, if any. Not saved: the entity saves itself. */
    private var king: UUID? = null

    /** Where the King rises, just in front of the throne. */
    private fun seat(altar: BlockPos): BlockPos = altar.offset(0, 0, -2)

    /** The altar position, building the throne first if nobody has needed it yet. */
    fun ensure(level: ServerLevel): BlockPos {
        if (altar.isPresent) return altar.get()
        val site = findSite(level)
        build(level, site)
        altar = Optional.of(site)
        setDirty()
        return site
    }

    private fun findSite(level: ServerLevel): BlockPos {
        var fallback = BlockPos.ZERO
        repeat(16) {
            val angle = level.random.nextDouble() * Math.PI * 2
            val distance = 900 + level.random.nextInt(400)
            val x = (cos(angle) * distance).toInt()
            val z = (sin(angle) * distance).toInt()
            for (dx in listOf(-RADIUS, RADIUS)) for (dz in listOf(-RADIUS, RADIUS)) level.getChunk((x + dx) shr 4, (z + dz) shr 4)
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            fallback = top
            if (level.getFluidState(top.below()).isEmpty && level.getFluidState(top).isEmpty) return top.above()
        }
        return fallback.above()
    }

    private fun build(level: ServerLevel, altar: BlockPos) {
        val floor = altar.y - 2
        val random = level.random
        fun set(x: Int, y: Int, z: Int, block: Block) =
            level.setBlock(BlockPos(altar.x + x, y, altar.z + z), block.defaultBlockState(), Block.UPDATE_ALL)
        for (dx in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (r > RADIUS + 0.5) continue
            for (y in floor + 1..floor + 16) set(dx, y, dz, Blocks.AIR)
            set(dx, floor, dz, when {
                r > RADIUS - 1 -> Blocks.POLISHED_BLACKSTONE
                random.nextInt(8) == 0 -> Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS
                else -> Blocks.POLISHED_BLACKSTONE_BRICKS
            })
            // Foundations down to the ground so it never floats
            var y = floor - 1
            while (y > floor - 10 && level.getBlockState(BlockPos(altar.x + dx, y, altar.z + dz)).canBeReplaced()) {
                set(dx, y, dz, Blocks.BLACKSTONE); y--
            }
            // The dais
            if (r <= 5.5) set(dx, floor + 1, dz, when {
                r > 4.5 -> Blocks.CHISELED_POLISHED_BLACKSTONE
                random.nextInt(9) == 0 -> Blocks.GILDED_BLACKSTONE
                else -> Blocks.POLISHED_BLACKSTONE
            })
        }
        // Seven pillars, one for each shard, burning with soul fire
        for (i in 0 until MainQuestState.SHARDS) {
            val angle = Math.PI * 2 * i / MainQuestState.SHARDS
            val px = (cos(angle) * 11).toInt()
            val pz = (sin(angle) * 11).toInt()
            for (y in floor + 1..floor + 6) set(px, y, pz, Blocks.POLISHED_BLACKSTONE_BRICKS)
            set(px, floor + 7, pz, Blocks.CHISELED_POLISHED_BLACKSTONE)
            set(px, floor + 8, pz, Blocks.SOUL_CAMPFIRE)
        }
        // The throne: a seat with arms, a tall back and a golden crest
        val seatY = floor + 2
        level.setBlock(BlockPos(altar.x, seatY, altar.z - 4), Blocks.BLACKSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH), Block.UPDATE_ALL)
        set(-1, seatY, -4, Blocks.POLISHED_BLACKSTONE_SLAB)
        set(1, seatY, -4, Blocks.POLISHED_BLACKSTONE_SLAB)
        for (x in -1..1) for (y in seatY..seatY + 3) set(x, y, -5, if (x == 0 && y == seatY + 2) Blocks.CRYING_OBSIDIAN else Blocks.POLISHED_BLACKSTONE_BRICKS)
        set(0, seatY + 4, -5, Blocks.GOLD_BLOCK)
        set(-1, seatY + 4, -5, Blocks.GILDED_BLACKSTONE)
        set(1, seatY + 4, -5, Blocks.GILDED_BLACKSTONE)
        // The Shattering Stone
        level.setBlock(altar, Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_ALL)
    }

    /** Every second: Maren waits for anyone ready to end it, and fights beside them once the King is up. */
    fun tick(level: ServerLevel) {
        val altar = altar.orElse(null) ?: return
        if (level.chunkSource.getChunkNow(altar.x shr 4, altar.z shr 4) == null) return
        val living = king?.let { level.getEntity(it) } as? HollowKing
        if (king != null && living == null) king = null
        val maren = level.getEntitiesOfClass(StoryNpc::class.java, net.minecraft.world.phys.AABB(altar).inflate(40.0)) { it.character == StoryCharacter.MAREN.id }.firstOrNull()
        if (living == null) {
            val ready = level.players().any { it.blockPosition().closerThan(altar, 40.0) && MainQuest.get(it).let { s -> s.found == MainQuestState.SHARDS && !s.finished } }
            if (ready && maren == null) Characters.appear(level, StoryCharacter.MAREN, altar.offset(3, 0, 3), -1)
            return
        }
        // The Last Warden's spectral arrows: they only ever find the King
        if (maren != null && level.gameTime % 60L < 20L) {
            maren.lookControl.setLookAt(living)
            val from = maren.eyePosition
            val to = living.getEyePosition(1.0f).add(0.0, -1.0, 0.0)
            val steps = (from.distanceTo(to) * 2).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val p = from.lerp(to, i.toDouble() / steps)
                level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0)
            }
            living.hurtServer(level, level.damageSources().magic(), 7.0f)
            level.playSound(null, maren.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0f, 1.4f)
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, maren.x, maren.y + 1.5, maren.z, 6, 0.2, 0.2, 0.2, 0.02)
        }
    }

    /** Someone set the shards on the Shattering Stone. */
    fun begin(level: ServerLevel, player: Player) {
        val altar = altar.orElse(null) ?: return
        val state = MainQuest.get(player)
        if (state.finished) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.throne_done"))
            return
        }
        if (state.found < MainQuestState.SHARDS) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.throne_silent", state.found, MainQuestState.SHARDS))
            return
        }
        if (king?.let { level.getEntity(it) } != null) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.king_walks"))
            return
        }
        MainQuest.takeShards(player)
        val seat = seat(altar)
        val bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED)
        if (bolt != null) {
            bolt.snapTo(altar.x + 0.5, altar.y + 1.0, altar.z + 0.5)
            bolt.setVisualOnly(true)
            level.addFreshEntity(bolt)
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, seat.x + 0.5, seat.y + 1.5, seat.z + 0.5, 200, 1.0, 2.0, 1.0, 0.2)
        level.playSound(null, seat, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2.0f, 0.6f)
        val boss = ModEntities.HOLLOW_KING.get().create(level, EntitySpawnReason.EVENT) ?: return
        boss.snapTo(seat.x + 0.5, seat.y.toDouble(), seat.z + 0.5, 180f, 0f)
        boss.throne = seat
        boss.setPersistenceRequired()
        level.addFreshEntity(boss)
        king = boss.uuid
        for (hero in level.players()) {
            if (!hero.blockPosition().closerThan(altar, 48.0) || hero !is ServerPlayer) continue
            title(hero, Component.translatable("title.guildmark.king_rises"), Component.translatable("title.guildmark.king_rises.sub"))
        }
        MainQuest.say(player, StoryCharacter.MAREN, "main.guildmark.throne_begin")
    }

    companion object {
        private const val RADIUS = 14

        private val CODEC: Codec<HollowThrone> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.optionalFieldOf("altar").forGetter { it.altar },
                Codec.INT.optionalFieldOf("defeated", 0).forGetter { it.defeated },
            ).apply(i) { altar, defeated -> HollowThrone(altar, defeated) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hollow_throne"), Supplier { HollowThrone() }, CODEC)

        fun get(level: ServerLevel): HollowThrone = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Using the Shattering Stone. */
        fun onUseBlock(event: PlayerInteractEvent.RightClickBlock) {
            val level = event.level as? ServerLevel ?: return
            if (level.dimension() != Level.OVERWORLD) return
            val throne = get(level)
            if (throne.altar.orElse(null) != event.pos) return
            event.isCanceled = true
            throne.begin(level, event.entity)
        }

        /** The King fell. Everyone who carried the shards here becomes a Warden. */
        fun onKingDeath(level: ServerLevel, king: HollowKing) {
            val throne = get(level)
            throne.king = null
            throne.defeated++
            throne.setDirty()
            val altar = throne.altar.orElse(king.blockPosition())
            val heroes = level.players().filter { it.distanceTo(king) < 48 && !it.isSpectator }
            for (hero in heroes) {
                val state = MainQuest.get(hero)
                if (state.found == MainQuestState.SHARDS && !state.finished) crown(level, hero)
                else give(hero, ItemStack(ModItems.GUILD_MARK.get(), 64))
            }
            celebrate(level, altar)
            level.getEntitiesOfClass(StoryNpc::class.java, net.minecraft.world.phys.AABB(altar).inflate(48.0)) { it.character == StoryCharacter.MAREN.id }
                .forEach { it.leaveAt = level.gameTime + 20 * 20 }
            GuildNews.add(level, "news.guildmark.king_slain", heroes.joinToString(", ") { it.name.string }.ifEmpty { "?" })
        }

        private fun crown(level: ServerLevel, hero: Player) {
            MainQuest.finish(hero)
            MainQuest.say(hero, StoryCharacter.MAREN, "main.guildmark.finale")
            if (hero is ServerPlayer) title(hero, Component.translatable("title.guildmark.crown_broken"), Component.translatable("title.guildmark.crown_broken.sub"))
            give(hero, crownOfTheWarden(level))
            give(hero, lastPage(hero))
            repeat(5) { give(hero, ItemStack(ModItems.GUILD_MARK.get(), 64)) }
            Progression.addXp(hero, 5000)
            Progression.promote(hero, AdventurerRank.S.ordinal)
            MainQuest.say(hero, StoryCharacter.WREN, "main.guildmark.epilogue.wren")
            MainQuest.say(hero, StoryCharacter.TOBIN, "main.guildmark.epilogue.tobin")
            MainQuest.say(hero, StoryCharacter.ILSA, "main.guildmark.epilogue.ilsa")
        }

        private fun crownOfTheWarden(level: ServerLevel): ItemStack {
            val stack = ItemStack(Items.NETHERITE_HELMET)
            val enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            stack.enchant(enchantments.getOrThrow(Enchantments.PROTECTION), 4)
            stack.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3)
            stack.enchant(enchantments.getOrThrow(Enchantments.RESPIRATION), 3)
            stack.enchant(enchantments.getOrThrow(Enchantments.MENDING), 1)
            stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.guildmark.warden_crown").withStyle { it.withColor(0xE0B040).withItalic(false) })
            stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.warden_crown.lore").withStyle { it.withColor(0x9A7AB8) })))
            return stack
        }

        /** The torn last page of the Ledger of Names, with the hero's name now written under Maren's. */
        private fun lastPage(hero: Player): ItemStack {
            val stack = ItemStack(Items.WRITTEN_BOOK)
            val pages = (0 until 3).map { Filterable.passThrough<Component>(Component.translatable("lore.guildmark.last_page.page$it", hero.name.string)) }
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent(Filterable.passThrough("The Last Page"), "Maren", 3, pages, true))
            stack.set(DataComponents.CUSTOM_NAME, Component.translatable("lore.guildmark.last_page.title"))
            return stack
        }

        private fun celebrate(level: ServerLevel, altar: BlockPos) {
            level.playSound(null, altar, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 2.0f, 1.0f)
            level.sendParticles(ParticleTypes.END_ROD, altar.x + 0.5, altar.y + 2.0, altar.z + 0.5, 150, 3.0, 3.0, 3.0, 0.1)
            for (i in 0 until MainQuestState.SHARDS) {
                val angle = Math.PI * 2 * i / MainQuestState.SHARDS
                val rocket = ItemStack(Items.FIREWORK_ROCKET)
                val colors = IntList.of(0xC08CFF, 0xE0B040)
                rocket.set(DataComponents.FIREWORKS, Fireworks(1 + i % 2, listOf(FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, colors, IntList.of(0xFFFFFF), true, true))))
                level.addFreshEntity(FireworkRocketEntity(level, altar.x + 0.5 + cos(angle) * 11, altar.y + 7.0, altar.z + 0.5 + sin(angle) * 11, rocket))
            }
        }

        private fun title(player: ServerPlayer, title: Component, subtitle: Component) {
            player.connection.send(ClientboundSetSubtitleTextPacket(subtitle))
            player.connection.send(ClientboundSetTitleTextPacket(title))
        }

        private fun give(player: Player, stack: ItemStack) = player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
    }
}
