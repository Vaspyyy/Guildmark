package io.github.vaspyyy.guildmark.atlas

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/**
 * A random id made once for each world and kept in its save. A client keeps its world map per server
 * and world id, so a server that wipes its world, or worlds behind one address, never share a map.
 */
class WorldId(val id: String) : SavedData() {
    companion object {
        private val CODEC: Codec<WorldId> = RecordCodecBuilder.create { i ->
            i.group(Codec.STRING.fieldOf("id").forGetter(WorldId::id)).apply(i, ::WorldId)
        }

        private val TYPE = SavedDataType(
            Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "world_id"),
            Supplier { WorldId(UUID.randomUUID().toString()).apply { setDirty() } },
            CODEC,
        )

        fun of(server: MinecraftServer): String = server.overworld().dataStorage.computeIfAbsent(TYPE).id
    }
}
