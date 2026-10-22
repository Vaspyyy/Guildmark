package io.github.vaspyyy.guildmark.village.live

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/**
 * The villages the world has counted, saved with the overworld as "villages": one [VillageRec] for each bell (living-world/villages/
 * design.md section 14.1). Every field is optional with a default and a record that can't be read drops only itself (see
 * [keptList]), so an older file reads and nothing is lost to one bad line.
 */
class VillageData(val records: MutableMap<Long, VillageRec> = HashMap()) : SavedData() {
    companion object {
        private val CODEC: Codec<VillageData> = RecordCodecBuilder.create { i ->
            i.group(
                keptList(VillageRec.CODEC).lenientOptionalFieldOf("villages", listOf()).forGetter { data -> data.records.values.sortedBy { it.key } },
            ).apply(i) { list -> VillageData(list.associateByTo(HashMap()) { it.key }) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "villages"), Supplier { VillageData() }, CODEC)

        /** The overworld's villages, whichever level asks. */
        fun get(level: ServerLevel): VillageData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
