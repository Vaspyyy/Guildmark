package io.github.vaspyyy.guildmark.party

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import java.util.UUID
import java.util.function.Supplier

/** A band of up to four adventurers who share kills, a cut of each other's pay, and never hurt each other. */
data class Party(val id: Int, var leader: UUID, val members: MutableList<UUID>) {
    companion object {
        val CODEC: Codec<Party> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Party::id),
                UUIDUtil.CODEC.fieldOf("leader").forGetter(Party::leader),
                UUIDUtil.CODEC.listOf().fieldOf("members").forGetter { it.members.toList() },
            ).apply(i) { id, leader, members -> Party(id, leader, members.toMutableList()) }
        }
    }
}

class Parties(val parties: MutableList<Party> = mutableListOf()) : SavedData() {
    /** Pending invites: invitee to the inviting party's id. Not saved; invites lapse on restart. */
    private val invites = HashMap<UUID, Int>()

    fun of(player: UUID): Party? = parties.firstOrNull { player in it.members }

    companion object {
        const val MAX_SIZE = 4
        /** How close party members must be to share kills and pay. */
        const val SHARE_RANGE = 64.0
        private const val PAY_SHARE = 4

        private val CODEC: Codec<Parties> = RecordCodecBuilder.create { i ->
            i.group(Party.CODEC.listOf().fieldOf("parties").forGetter { it.parties }).apply(i) { Parties(it.toMutableList()) }
        }
        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "parties"), Supplier { Parties() }, CODEC)

        fun get(server: MinecraftServer): Parties = server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Online party members other than [player], in the same level and within [range]. */
        fun nearbyMates(player: Player, range: Double = SHARE_RANGE): List<ServerPlayer> {
            val server = (player.level() as? ServerLevel)?.server ?: return listOf()
            val party = get(server).of(player.uuid) ?: return listOf()
            return party.members.filter { it != player.uuid }
                .mapNotNull { server.playerList.getPlayer(it) }
                .filter { it.level() == player.level() && it.distanceTo(player) <= range && !it.isSpectator }
        }

        fun invite(server: MinecraftServer, from: ServerPlayer, to: ServerPlayer): Boolean {
            val parties = get(server)
            if (from == to) return fail(from, "self")
            if (parties.of(to.uuid) != null) return fail(from, "taken", to.displayName)
            val party = parties.of(from.uuid) ?: Party((parties.parties.maxOfOrNull { it.id } ?: 0) + 1, from.uuid, mutableListOf(from.uuid)).also {
                parties.parties.add(it)
                parties.setDirty()
                sync(server, it)
            }
            if (party.members.size >= MAX_SIZE) return fail(from, "full", MAX_SIZE)
            parties.invites[to.uuid] = party.id
            val join = Component.translatable("message.guildmark.party.join_button").withStyle {
                it.withColor(0x7FD87F).withUnderlined(true)
                    .withClickEvent(ClickEvent.RunCommand("/party join ${from.gameProfile.name}"))
                    .withHoverEvent(HoverEvent.ShowText(Component.translatable("message.guildmark.party.join_hover")))
            }
            to.sendSystemMessage(Component.translatable("message.guildmark.party.invited", from.displayName, join))
            from.sendSystemMessage(Component.translatable("message.guildmark.party.invite_sent", to.displayName))
            return true
        }

        fun join(server: MinecraftServer, player: ServerPlayer, inviter: ServerPlayer): Boolean {
            val parties = get(server)
            val party = parties.of(inviter.uuid)
            if (party == null || parties.invites[player.uuid] != party.id) return fail(player, "no_invite", inviter.displayName)
            if (parties.of(player.uuid) != null) return fail(player, "already")
            if (party.members.size >= MAX_SIZE) return fail(player, "full", MAX_SIZE)
            parties.invites.remove(player.uuid)
            party.members.add(player.uuid)
            parties.setDirty()
            sync(server, party)
            tell(server, party, Component.translatable("message.guildmark.party.joined", player.displayName))
            for (member in party.members.mapNotNull { server.playerList.getPlayer(it) }) Milestones.award(member, Milestones.PARTY)
            return true
        }

        fun leave(server: MinecraftServer, player: ServerPlayer): Boolean {
            val parties = get(server)
            val party = parties.of(player.uuid) ?: return fail(player, "none")
            remove(server, parties, party, player.uuid)
            player.sendSystemMessage(Component.translatable("message.guildmark.party.left"))
            tell(server, party, Component.translatable("message.guildmark.party.member_left", player.displayName))
            return true
        }

        fun kick(server: MinecraftServer, leader: ServerPlayer, target: UUID, name: Component): Boolean {
            val parties = get(server)
            val party = parties.of(leader.uuid) ?: return fail(leader, "none")
            if (party.leader != leader.uuid) return fail(leader, "not_leader")
            if (target !in party.members || target == leader.uuid) return fail(leader, "not_member", name)
            remove(server, parties, party, target)
            server.playerList.getPlayer(target)?.sendSystemMessage(Component.translatable("message.guildmark.party.kicked"))
            tell(server, party, Component.translatable("message.guildmark.party.member_left", name))
            return true
        }

        private fun remove(server: MinecraftServer, parties: Parties, party: Party, member: UUID) {
            party.members.remove(member)
            server.playerList.getPlayer(member)?.setData(ModAttachments.PARTY, listOf())
            if (party.members.size <= 1) {
                parties.parties.remove(party)
                for (rest in party.members) server.playerList.getPlayer(rest)?.let {
                    it.setData(ModAttachments.PARTY, listOf())
                    it.sendSystemMessage(Component.translatable("message.guildmark.party.disbanded"))
                }
                party.members.clear()
            } else {
                if (party.leader == member) party.leader = party.members.first()
                sync(server, party)
            }
            parties.setDirty()
        }

        fun list(server: MinecraftServer, player: ServerPlayer) {
            val party = get(server).of(player.uuid) ?: run {
                player.sendSystemMessage(Component.translatable("message.guildmark.party.none"))
                return
            }
            player.sendSystemMessage(Component.translatable("message.guildmark.party.header", party.members.size, MAX_SIZE))
            for (member in party.members) {
                val online = server.playerList.getPlayer(member)
                val name = online?.displayName ?: Component.literal(server.services().nameToIdCache().get(member).map { it.name }.orElse("?"))
                val key = if (member == party.leader) "message.guildmark.party.line_leader" else "message.guildmark.party.line"
                player.sendSystemMessage(Component.translatable(key, name, Component.translatable(if (online != null) "message.guildmark.party.online" else "message.guildmark.party.offline")))
            }
        }

        /** Every member's client learns who else is in the party (for the tracker's party panel). */
        fun sync(server: MinecraftServer, party: Party) {
            for (member in party.members) server.playerList.getPlayer(member)?.setData(ModAttachments.PARTY, party.members.filter { it != member })
        }

        fun onLogin(player: ServerPlayer) {
            val server = (player.level() as? ServerLevel)?.server ?: return
            val party = get(server).of(player.uuid)
            if (party == null) player.setData(ModAttachments.PARTY, listOf()) else sync(server, party)
        }

        private fun tell(server: MinecraftServer, party: Party, message: Component) {
            for (member in party.members) server.playerList.getPlayer(member)?.sendSystemMessage(message)
        }

        private fun fail(player: Player, key: String, vararg args: Any): Boolean {
            player.sendSystemMessage(Component.translatable("message.guildmark.party.error.$key", *args).withStyle { it.withColor(0xE05A4A) })
            return false
        }

        /** A kill counts for every nearby party member's contracts, not just the killer's. */
        fun onKill(killer: Player, victim: LivingEntity) {
            for (mate in nearbyMates(killer)) Contracts.onKill(mate, victim)
        }

        /** A finished contract pays nearby party members a quarter of its marks and XP on top. */
        fun sharePay(player: Player, marks: Int, xp: Int) {
            val mates = nearbyMates(player)
            if (mates.isEmpty()) return
            val shareMarks = (marks / PAY_SHARE).coerceAtLeast(1)
            val shareXp = xp / PAY_SHARE
            for (mate in mates) {
                mate.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), shareMarks), Prediction.SERVER_ONLY)
                Progression.addXp(mate, shareXp)
                mate.sendOverlayMessage(Component.translatable("message.guildmark.party.share", shareMarks, player.displayName))
            }
        }

        /** Party members can't hurt each other. */
        fun onIncomingDamage(event: LivingIncomingDamageEvent) {
            val victim = event.entity as? ServerPlayer ?: return
            val attacker = event.source.entity as? ServerPlayer ?: return
            if (attacker == victim) return
            val party = get(victim.level().server).of(victim.uuid) ?: return
            if (attacker.uuid in party.members) event.isCanceled = true
        }
    }
}
