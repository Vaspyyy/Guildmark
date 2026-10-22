package io.github.vaspyyy.guildmark.village.live.folk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.keptList
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.gossip.GossipContainer
import net.minecraft.world.entity.ai.gossip.GossipType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/** One kept villager in a shard's file: the resident's id and what is kept of the villager they were. */
private class Entry(val id: Int, val keep: CompoundTag)

private val ENTRY: Codec<Entry> = RecordCodecBuilder.create { i ->
    i.group(
        Codec.INT.fieldOf("id").forGetter(Entry::id),
        CompoundTag.CODEC.fieldOf("keep").forGetter(Entry::keep),
    ).apply(i, ::Entry)
}

/**
 * A sixteenth of the keepsakes (living-world/villages/design.md section 14.1): the kept villagers of the residents whose id is
 * this shard's number modulo 16, so that one change writes a sixteenth of them and not all. Every field is optional and an
 * entry that can't be read drops only itself.
 */
class KeepShard(val kept: MutableMap<Int, CompoundTag> = HashMap()) : SavedData() {
    companion object {
        val CODEC: Codec<KeepShard> = RecordCodecBuilder.create { i ->
            i.group(
                keptList(ENTRY).lenientOptionalFieldOf("kept", listOf()).forGetter { shard -> shard.kept.entries.sortedBy { it.key }.map { Entry(it.key, it.value) } },
            ).apply(i) { list -> KeepShard(list.associateTo(HashMap()) { it.id to it.keep }) }
        }
    }
}

/**
 * What is kept of the vanilla villager each adopted resident was (living-world/villages/design.md sections 6.3 and 6.4): its trade
 * life and nothing of its body. A keepsake is the villager's own saved tag cut down to [KEPT] (its profession and level, xp, offers,
 * gossip, inventory, restock times, food and age: no position, brain, uuid, passengers, leash or name) with a few lines of this
 * mod's own beside it (the `gm_` keys below), about a kilobyte, kept in sixteen stores by the resident's id.
 *
 * A keepsake is opened as a [KeptVillager] for "Their old wares", and rebuilt as a real villager when the resident is given back.
 */
object VillageKeepsakes {
    const val SHARDS = 16

    /** The villager's own keys that a keepsake holds. */
    private val KEPT = listOf(
        "VillagerData", "VillagerDataFinalized", "FoodLevel", "Gossips", "Xp", "LastRestock", "LastGossipDecay", "RestocksToday",
        "Offers", "Inventory", "Age", "ForcedAge", "AgeLocked",
    )

    /** The uuid of the vanilla villager this was kept from (a string): how a villager left standing by a crash is known again. */
    const val FROM = "gm_from"

    /** The day the villager was taken in. */
    const val DAY = "gm_day"

    /** Whether the kept villager has anything to sell (there is a stall to show in the talk). */
    const val WARES = "gm_wares"

    /** The job block and the bed the villager had (block positions as longs), for giving them back. */
    const val JOB = "gm_job"
    const val HOME = "gm_home"

    /** The game time gossip last faded in the keepsake: it fades once in 24,000 ticks, as a villager's does, but only when its wares are opened. */
    const val DECAY = "gm_decay"

    /** The bell of the village it was kept in. */
    const val VILLAGE = "gm_village"

    /** Set from when a keepsake is written until the body is swapped for the villager: the villager is still standing, and the resident may not be on record yet. */
    const val PENDING = "gm_pending"

    /** What a resident that is not on record yet is (their name, trade, look and home), for the swap to make them from. */
    const val RES = "gm_res"

    private val types: List<SavedDataType<KeepShard>> = List(SHARDS) { i ->
        SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_keepsakes_$i"), Supplier { KeepShard() }, KeepShard.CODEC)
    }

    private fun shard(level: ServerLevel, id: Int): KeepShard =
        level.server.overworld().dataStorage.computeIfAbsent(types[Math.floorMod(id, SHARDS)])

    /** The keepsake of resident [id], or null. It is the stored tag itself: copy it before changing it. */
    fun get(level: ServerLevel, id: Int): CompoundTag? = shard(level, id).kept[id]

    fun has(level: ServerLevel, id: Int): Boolean = shard(level, id).kept.containsKey(id)

    /** Keeps [tag] for resident [id]. */
    fun put(level: ServerLevel, id: Int, tag: CompoundTag) {
        val s = shard(level, id)
        s.kept[id] = tag
        s.setDirty()
    }

    fun remove(level: ServerLevel, id: Int) {
        val s = shard(level, id)
        if (s.kept.remove(id) != null) s.setDirty()
    }

    /** The ids of every resident who has a keepsake, in order (this reads all sixteen stores). */
    fun ids(level: ServerLevel): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until SHARDS) out.addAll(shard(level, i).kept.keys)
        out.sort()
        return out
    }

    /** Whether resident [id]'s kept villager has anything to sell. */
    fun wares(level: ServerLevel, id: Int): Boolean = get(level, id)?.getBooleanOr(WARES, false) == true

    /** [full], a villager's whole saved tag, cut down to what a keepsake holds. */
    fun cut(full: CompoundTag): CompoundTag {
        val out = CompoundTag()
        for (key in KEPT) full.get(key)?.let { out.put(key, it.copy()) }
        return out
    }

    /** This mod's own lines of [from] copied into [into]. */
    fun carry(from: CompoundTag?, into: CompoundTag) {
        if (from == null) return
        for (key in from.keySet()) if (key.startsWith("gm_")) from.get(key)?.let { into.put(key, it.copy()) }
    }

    /** The block position kept under [key], or null. */
    fun pos(tag: CompoundTag, key: String): BlockPos? = tag.getLong(key).map { BlockPos.of(it) }.orElse(null)

    // ---- Gossip ----

    /** Sets the good word [player] has with [gossips] to what a standing of [tier] (the tier's gossip) gives, as `Standing.spreadWord` does for a living villager. */
    fun setTier(gossips: GossipContainer, player: UUID, tier: Int) {
        val current = gossips.getReputation(player) { it == GossipType.MAJOR_POSITIVE } / GossipType.MAJOR_POSITIVE.weight
        when {
            tier > current -> gossips.add(player, GossipType.MAJOR_POSITIVE, tier - current)
            tier < current -> gossips.remove(player, GossipType.MAJOR_POSITIVE, current - tier)
        }
    }

    /**
     * A village's standing with [player] changed (from `Standing.spreadWord`): each kept villager of the villages whose bell is within
     * 64 blocks of [center] is told the tier's gossip, as the living villagers are. Reads only the keepsakes of those residents.
     */
    fun spreadWord(level: ServerLevel, center: BlockPos, player: Player, tier: Int) {
        val residents = Residents.get(level)
        for (rec in Villages.near(level, center, 64)) {
            for (r in residents.byCentre(BlockPos.of(rec.key))) {
                val tag = get(level, r.id) ?: continue
                val gossips = tag.read("Gossips", GossipContainer.CODEC).orElseGet { GossipContainer() }
                val before = gossips.getReputation(player.uuid) { true }
                setTier(gossips, player.uuid, tier)
                if (gossips.getReputation(player.uuid) { true } == before) continue
                tag.store("Gossips", GossipContainer.CODEC, gossips)
                shard(level, r.id).setDirty()
            }
        }
    }
}
