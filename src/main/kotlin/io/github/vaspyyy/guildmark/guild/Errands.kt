package io.github.vaspyyy.guildmark.guild

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Prediction
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.npc.villager.VillagerData
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.AABB
import java.util.UUID
import java.util.function.Supplier

/** A kind of errand a guild member can be sent on: how long it takes, how dangerous it is, and what it pays. */
enum class ErrandKind(val id: String, val days: Int, val risk: Float, val minPay: Int, val maxPay: Int, val cacheChance: Float, val cache: Rarity) {
    PATROL("patrol", 1, 0.06f, 8, 14, 0.0f, Rarity.COMMON),
    NEST("nest", 2, 0.15f, 18, 28, 0.3f, Rarity.COMMON),
    RUIN("ruin", 3, 0.25f, 30, 45, 0.6f, Rarity.UNCOMMON);

    val title: Component get() = Component.translatable("errand.guildmark.$id")

    companion object {
        fun byId(id: String): ErrandKind? = entries.firstOrNull { it.id == id }
    }
}

/** A member away on an errand: everything needed to bring them back as they left. */
data class Errand(
    val guild: Int,
    val leader: UUID,
    val member: UUID,
    val name: Component,
    val villagerData: VillagerData,
    val veteran: Int,
    val weapon: ItemStack,
    val kind: ErrandKind,
    val returnDay: Long,
) {
    companion object {
        private val KIND_CODEC: Codec<ErrandKind> = Codec.STRING.xmap({ id -> ErrandKind.byId(id) ?: ErrandKind.PATROL }, ErrandKind::id)

        val CODEC: Codec<Errand> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("guild").forGetter(Errand::guild),
                UUIDUtil.CODEC.fieldOf("leader").forGetter(Errand::leader),
                UUIDUtil.CODEC.fieldOf("member").forGetter(Errand::member),
                ComponentSerialization.CODEC.fieldOf("name").forGetter(Errand::name),
                VillagerData.CODEC.fieldOf("villager").forGetter(Errand::villagerData),
                Codec.INT.optionalFieldOf("veteran", 0).forGetter(Errand::veteran),
                ItemStack.OPTIONAL_CODEC.optionalFieldOf("weapon", ItemStack.EMPTY).forGetter(Errand::weapon),
                KIND_CODEC.fieldOf("kind").forGetter(Errand::kind),
                Codec.LONG.fieldOf("return_day").forGetter(Errand::returnDay),
            ).apply(i, ::Errand)
        }
    }
}

/**
 * Guild members sent away on errands: patrols, nest clearing and ruin raids. They're gone for a day
 * or more, come back with marks (and sometimes a relic cache) and a little more experience, or don't
 * come back at all. Veterans are tougher and survive more often. Leaders manage them with /guild.
 */
class Errands(val errands: MutableList<Errand> = mutableListOf()) : SavedData() {
    companion object {
        const val MAX_VETERAN = 5
        private const val SEND_RANGE = 32.0

        private val CODEC: Codec<Errands> = RecordCodecBuilder.create { i ->
            i.group(Errand.CODEC.listOf().fieldOf("errands").forGetter { it.errands }).apply(i) { Errands(it.toMutableList()) }
        }
        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "errands"), Supplier { Errands() }, CODEC)

        fun get(level: ServerLevel): Errands = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        private fun risk(kind: ErrandKind, veteran: Int): Float = (kind.risk - 0.03f * veteran).coerceAtLeast(0.02f)

        /** The leader's members close by, and the ones away. */
        fun list(player: ServerPlayer) {
            val level = player.level()
            val guild = Guilds.get(level).ledBy(player.uuid) ?: return fail(player, "no_guild")
            val near = level.getEntitiesOfClass(GuildMember::class.java, AABB(player.blockPosition()).inflate(SEND_RANGE)) { it.leader == player.uuid }
            val away = get(level).errands.filter { it.leader == player.uuid }
            if (near.isEmpty() && away.isEmpty()) return fail(player, "no_members")
            player.sendSystemMessage(Component.translatable("message.guildmark.errand.header", Component.literal(guild.name).withColor(guild.color.textColor)).withStyle { it.withColor(0xE8C26A) })
            for (member in near) {
                val buttons = Component.empty()
                for (kind in ErrandKind.entries) {
                    buttons.append(" ").append(Component.literal("[").append(kind.title).append("]").withStyle {
                        it.withColor(0x7FD87F).withUnderlined(true)
                            .withClickEvent(ClickEvent.RunCommand("/guild send ${member.uuid} ${kind.id}"))
                            .withHoverEvent(HoverEvent.ShowText(Component.translatable("message.guildmark.errand.hover", kind.title, kind.days,
                                (risk(kind, member.veteran) * 100).toInt(), kind.minPay, kind.maxPay)))
                    })
                }
                player.sendSystemMessage(Component.translatable("message.guildmark.errand.member", member.name, member.veteran, buttons))
            }
            val today = VillageBoards.day(level)
            for (errand in away) {
                player.sendSystemMessage(Component.translatable("message.guildmark.errand.away", errand.name, errand.kind.title, (errand.returnDay - today).coerceAtLeast(0)))
            }
        }

        fun send(player: ServerPlayer, memberId: String, kindId: String): Boolean {
            val level = player.level()
            val kind = ErrandKind.byId(kindId) ?: return false
            val uuid = runCatching { UUID.fromString(memberId) }.getOrNull() ?: return false
            val member = level.getEntity(uuid) as? GuildMember
            if (member == null || member.leader != player.uuid || member.distanceTo(player) > SEND_RANGE || !member.isAlive) {
                fail(player, "gone")
                return false
            }
            val errand = Errand(member.guild, player.uuid, member.uuid, member.customName ?: member.name, member.villagerData, member.veteran,
                member.getItemBySlot(EquipmentSlot.MAINHAND).copy(), kind, VillageBoards.day(level) + kind.days)
            get(level).errands.add(errand)
            get(level).setDirty()
            level.sendParticles(ParticleTypes.CLOUD, member.x, member.y + 0.5, member.z, 12, 0.3, 0.4, 0.3, 0.02)
            member.discard()
            player.sendSystemMessage(Component.translatable("message.guildmark.errand.sent", errand.name, kind.title, kind.days))
            return true
        }

        /** Every 200 ticks per player: members whose errand is over come back to their leader, or don't. */
        fun tick(level: ServerLevel, player: ServerPlayer) {
            val errands = get(level)
            val today = VillageBoards.day(level)
            val due = errands.errands.filter { it.leader == player.uuid && today >= it.returnDay }
            if (due.isEmpty()) return
            errands.errands.removeAll(due)
            errands.setDirty()
            val guilds = Guilds.get(level)
            for (errand in due) {
                val guild = guilds.byId(errand.guild)
                if (guild == null || guild.leader != player.uuid) continue
                if (level.random.nextFloat() < risk(errand.kind, errand.veteran)) {
                    guilds.removeMember(errand.member)
                    player.sendSystemMessage(Component.translatable("message.guildmark.errand.lost", errand.name, errand.kind.title).withStyle { it.withColor(0xE05A4A) })
                    continue
                }
                val member = ModEntities.GUILD_MEMBER.get().create(level, EntitySpawnReason.EVENT) ?: continue
                member.snapTo(player.x, player.y, player.z, player.yRot, 0f)
                member.villagerData = errand.villagerData
                member.customName = errand.name
                member.isCustomNameVisible = true
                member.leader = player.uuid
                member.guild = errand.guild
                member.veteran = (errand.veteran + 1).coerceAtMost(MAX_VETERAN)
                member.health = member.maxHealth
                member.setItemSlot(EquipmentSlot.MAINHAND, errand.weapon)
                member.setPersistenceRequired()
                level.addFreshEntity(member)
                guild.members.remove(errand.member)
                guilds.addMember(guild, member.uuid)

                val pay = ((errand.kind.minPay + level.random.nextInt(errand.kind.maxPay - errand.kind.minPay + 1)) * (1.0f + 0.1f * errand.veteran)).toInt()
                player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), pay), Prediction.SERVER_ONLY)
                val cache = level.random.nextFloat() < errand.kind.cacheChance
                if (cache) player.inventory.placeItemBackInInventory(Relics.cache(errand.kind.cache), Prediction.SERVER_ONLY)
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, member.x, member.y + 1.2, member.z, 12, 0.4, 0.5, 0.4, 0.0)
                member.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0f, 1.0f)
                player.sendSystemMessage(Component.translatable(if (cache) "message.guildmark.errand.back_cache" else "message.guildmark.errand.back",
                    errand.name, errand.kind.title, pay, member.veteran).withStyle { it.withColor(0x7FD87F) })
            }
        }

        private fun fail(player: ServerPlayer, key: String) {
            player.sendSystemMessage(Component.translatable("message.guildmark.errand.$key").withStyle { it.withColor(0xE05A4A) })
        }

        fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
            dispatcher.register(
                Commands.literal("guild")
                    .executes { context ->
                        list(context.source.playerOrException)
                        1
                    }
                    .then(Commands.literal("send").then(Commands.argument("member", StringArgumentType.word()).then(Commands.argument("errand", StringArgumentType.word()).executes { context ->
                        if (send(context.source.playerOrException, StringArgumentType.getString(context, "member"), StringArgumentType.getString(context, "errand"))) 1 else 0
                    })))
            )
        }
    }
}
