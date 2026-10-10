package io.github.vaspyyy.guildmark.guild

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/** A player-founded guild: its name and colour, who leads it, and the villagers sworn to it. */
data class Guild(
    val id: Int,
    val name: String,
    val color: DyeColor,
    val leader: UUID,
    val members: MutableList<UUID> = mutableListOf(),
) {
    companion object {
        val CODEC: Codec<Guild> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Guild::id),
                Codec.STRING.fieldOf("name").forGetter(Guild::name),
                DyeColor.CODEC.fieldOf("color").forGetter(Guild::color),
                UUIDUtil.CODEC.fieldOf("leader").forGetter(Guild::leader),
                UUIDUtil.CODEC.listOf().optionalFieldOf("members", listOf()).forGetter { it.members.toList() },
            ).apply(i) { id, name, color, leader, members -> Guild(id, name, color, leader, members.toMutableList()) }
        }
    }
}

/** Every player guild in the world, kept with the overworld's saved data. */
class Guilds(val guilds: MutableList<Guild> = mutableListOf()) : SavedData() {
    fun ledBy(player: UUID): Guild? = guilds.firstOrNull { it.leader == player }
    fun byId(id: Int): Guild? = guilds.firstOrNull { it.id == id }
    fun nameTaken(name: String): Boolean = guilds.any { it.name.equals(name, ignoreCase = true) }

    fun found(name: String, color: DyeColor, leader: UUID): Guild {
        val guild = Guild((guilds.maxOfOrNull { it.id } ?: 0) + 1, name, color, leader)
        guilds.add(guild)
        setDirty()
        return guild
    }

    fun addMember(guild: Guild, member: UUID) {
        guild.members.add(member)
        setDirty()
    }

    fun removeMember(member: UUID) {
        if (guilds.any { it.members.remove(member) }) setDirty()
    }

    companion object {
        /** How many villagers an adventurer of this rank index can lead. */
        fun memberCap(rank: Int): Int = 1 + rank

        private val CODEC: Codec<Guilds> = RecordCodecBuilder.create { i ->
            i.group(Guild.CODEC.listOf().fieldOf("guilds").forGetter { it.guilds })
                .apply(i) { guilds -> Guilds(guilds.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "guilds"), Supplier { Guilds() }, CODEC)

        fun get(level: ServerLevel): Guilds = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
