package io.github.vaspyyy.guildmark.client.atlas

import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.network.AtlasMark
import io.github.vaspyyy.guildmark.network.AtlasPayload
import io.github.vaspyyy.guildmark.network.AtlasRoad
import io.github.vaspyyy.guildmark.network.PartyMark
import io.github.vaspyyy.guildmark.network.PartyPositionsPayload
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The client's copy of the player's atlas (see Atlas): the places found in the dimension they're in, the
 * roads between them, and where their party mates are. Also remembers where the player last fell.
 */
object AtlasClient {
    private var dimension: ResourceKey<Level>? = null
    private var marks: List<AtlasMark> = listOf()
    private var roads: List<AtlasRoad> = listOf()
    private val party = HashMap<UUID, PartyMark>()

    /** A death point stays marked this long (in game ticks) after it's first seen, unless visited sooner. */
    private const val DEATH_SHOWN = 20 * 60 * 15
    private var death: GlobalPos? = null
    private var deathSeen = 0L
    private var deathVisited = false

    fun receive(payload: AtlasPayload) {
        dimension = payload.dimension
        marks = payload.marks
        roads = payload.roads
        payload.discovered.firstNotNullOfOrNull { pos -> payload.marks.firstOrNull { it.pos == pos } }?.let { PlaceBanner.discovered(it) }
    }

    fun receiveParty(payload: PartyPositionsPayload) {
        party.clear()
        for (mate in payload.mates) party[mate.id] = mate
    }

    fun reset() {
        dimension = null
        marks = listOf()
        roads = listOf()
        party.clear()
        death = null
        deathVisited = false
    }

    private fun here(): Boolean = Minecraft.getInstance().level?.dimension() == dimension

    /** The places found in the dimension the player is in now. */
    fun marks(): List<AtlasMark> = if (here()) marks else listOf()

    fun roads(): List<AtlasRoad> = if (here()) roads else listOf()

    fun name(mark: AtlasMark): Component =
        if (mark.kind == AtlasPlace.VILLAGE) Component.literal(VillageNames.of(mark.pos)) else Component.translatable(mark.label)

    /** Hamlet, town, lair, shard vault and so on. */
    fun kindName(mark: AtlasMark): Component = when {
        mark.kind == AtlasPlace.VILLAGE -> VillageNames.size(mark.size)
        mark.kind == AtlasPlace.THRONE -> Component.translatable("atlas.guildmark.kind.throne")
        mark.has(AtlasMark.VAULT) -> Component.translatable("atlas.guildmark.kind.vault")
        else -> Component.translatable("atlas.guildmark.kind.lair")
    }

    /**
     * Where each party mate is: their own position when they're near enough to be seen, otherwise the
     * last the server sent (party mates in another dimension have none).
     */
    fun partyMates(player: Player): List<Pair<UUID, Vec3>> = player.getData(ModAttachments.PARTY).mapNotNull { id ->
        val seen = player.level().getPlayerByUUID(id)
        val far = party[id]
        when {
            seen != null -> id to seen.position()
            far != null -> id to Vec3(far.x + 0.5, player.y, far.z + 0.5)
            else -> null
        }
    }

    fun mateName(id: UUID): String = Minecraft.getInstance().connection?.getPlayerInfo(id)?.profile?.name ?: "?"

    /** Where the player last fell, while it's still worth going back to: recent, and not yet revisited. */
    fun deathPoint(player: Player): BlockPos? {
        val last = player.lastDeathLocation.orElse(null) ?: return null
        val now = player.level().gameTime
        if (last != death) {
            death = last
            deathSeen = now
            deathVisited = false
        }
        if (deathVisited || last.dimension() != player.level().dimension() || now - deathSeen > DEATH_SHOWN) return null
        if (player.blockPosition().closerThan(last.pos(), 4.0)) {
            deathVisited = true
            return null
        }
        return last.pos()
    }
}
