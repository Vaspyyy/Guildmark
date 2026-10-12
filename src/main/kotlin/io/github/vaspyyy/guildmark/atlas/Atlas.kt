package io.github.vaspyyy.guildmark.atlas

import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.enemy.HillTroll
import io.github.vaspyyy.guildmark.field.FieldBosses
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.guild.Guilds
import io.github.vaspyyy.guildmark.guild.Holdings
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.network.AtlasMark
import io.github.vaspyyy.guildmark.network.AtlasPayload
import io.github.vaspyyy.guildmark.network.AtlasRoad
import io.github.vaspyyy.guildmark.network.PartyMark
import io.github.vaspyyy.guildmark.network.PartyPositionsPayload
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModVillagers
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.story.Characters
import io.github.vaspyyy.guildmark.story.HollowThrone
import io.github.vaspyyy.guildmark.story.MainQuest
import io.github.vaspyyy.guildmark.village.StandingTier
import io.github.vaspyyy.guildmark.wardfire.Wardfires
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.PacketDistributor
import java.util.WeakHashMap

/**
 * The places each player has found, for the compass and the map. Villages go in once you come within
 * sight of their board, lairs and field bosses' camps once you're near, the Hollow Throne once you see
 * it, a Wardfire once you kindle it or rest at it (see Wardfires.note). A village's size and
 * your standing there are noted on each visit, and its holder and roads are read fresh, since those
 * are kept with the overworld.
 */
object Atlas {
    private const val VILLAGE_SIGHT = 64
    private const val BELL_RANGE = 48
    private const val LAIR_SIGHT = 64.0
    private const val THRONE_SIGHT = 96.0
    private const val WARDFIRE_SIGHT = 32.0
    private const val CAMP_SIGHT = 64.0
    /** How far from a board a road's end can be and still be that village's road. */
    private const val ROAD_SLACK = 96.0

    /** What each player was sent last, so the atlas only goes out again when something on it changed. */
    private val sent = WeakHashMap<ServerPlayer, AtlasPayload>()

    fun get(player: Player): AtlasState = player.getData(ModAttachments.ATLAS)

    /** Every two seconds: note what the player has come upon, and bring their copy up to date. */
    fun tick(level: ServerLevel, player: ServerPlayer) {
        val found = discover(level, player)
        send(level, player, found, force = false)
    }

    /** Send the whole atlas again: on joining, respawning or changing dimension. */
    fun resend(player: ServerPlayer) {
        send(player.level(), player, listOf(), force = true)
    }

    /** Send the atlas if anything on it has changed, without waiting for the next tick. */
    fun refresh(player: ServerPlayer) {
        send(player.level(), player, listOf(), force = false)
    }

    /** Puts [place] in the player's atlas if it isn't there yet, and tells their client straight away. */
    fun note(player: ServerPlayer, place: AtlasPlace) {
        val atlas = get(player)
        if (atlas.find(place.kind, place.dimension, place.pos) != null) return
        player.setData(ModAttachments.ATLAS, AtlasState(atlas.places + place))
        send(player.level(), player, listOf(place), force = false)
    }

    /** Strikes a place off the player's atlas, as when the Wardfire there has gone. */
    fun forget(player: ServerPlayer, kind: Int, dimension: ResourceKey<Level>, pos: BlockPos) {
        val atlas = get(player)
        if (atlas.find(kind, dimension, pos) == null) return
        player.setData(ModAttachments.ATLAS, AtlasState(atlas.places.filterNot { it.kind == kind && it.dimension == dimension && it.pos == pos }))
        refresh(player)
    }

    /** Note the places around [player], returning any found for the first time. */
    private fun discover(level: ServerLevel, player: ServerPlayer): List<AtlasPlace> {
        val dimension = level.dimension()
        val here = player.blockPosition()
        val now = level.gameTime
        val places = get(player).places.toMutableList()
        val fresh = ArrayList<AtlasPlace>()
        var changed = false
        fun found(place: AtlasPlace) {
            places.add(place)
            fresh.add(place)
            changed = true
        }

        // Villages: a board standing near a bell (a board someone crafted out in the wilds isn't a village)
        val anchors = LinkedHashSet<BlockPos>()
        level.poiManager.findAll({ it.`is`(ModVillagers.GUILDMASTER_POI.key!!) }, { true }, here, VILLAGE_SIGHT, PoiManager.Occupancy.ANY)
            .forEach { pos ->
                val state = level.getBlockState(pos)
                if (state.block is QuestBoardBlock) anchors.add(QuestBoardBlock.anchorPos(pos, state).immutable())
            }
        for (anchor in anchors) {
            val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: continue
            if (level.poiManager.findClosest({ it.`is`(PoiTypes.MEETING) }, anchor, BELL_RANGE, PoiManager.Occupancy.ANY).isEmpty) continue
            val size = board.advanceIndex
            val standing = StandingTier.of(board.standingOf(player.uuid)).ordinal
            val index = places.indexOfFirst { it.kind == AtlasPlace.VILLAGE && it.dimension == dimension && it.pos == anchor }
            if (index < 0) {
                found(AtlasPlace(AtlasPlace.VILLAGE, dimension, anchor, size = size, standing = standing, found = now))
            } else if (places[index].size != size || places[index].standing != standing) {
                places[index] = places[index].copy(size = size, standing = standing)
                changed = true
            }
        }
        // A village whose board has gone is struck off
        if (places.removeIf {
                it.kind == AtlasPlace.VILLAGE && it.dimension == dimension && it.pos.closerThan(here, VILLAGE_SIGHT.toDouble()) &&
                    level.isLoaded(it.pos) && it.pos !in anchors && level.getBlockEntity(it.pos) !is QuestBoardBlockEntity
            }) changed = true

        // A Wardfire that has gone, or gone out, is struck off once you're there to see it
        if (places.removeIf {
                it.kind == AtlasPlace.WARDFIRE && it.dimension == dimension && it.pos.closerThan(here, WARDFIRE_SIGHT) &&
                    level.isLoaded(it.pos) && !Wardfires.isLit(level.getBlockState(it.pos))
            }) changed = true

        val lairs = Lairs.get(level)
        for (lair in lairs.lairs) {
            if (!lair.pos.closerThan(here, LAIR_SIGHT)) continue
            if (places.any { it.kind == AtlasPlace.LAIR && it.dimension == dimension && it.ref == lair.id }) continue
            found(AtlasPlace(AtlasPlace.LAIR, dimension, lair.pos, ref = lair.id, found = now))
        }

        if (dimension == Level.OVERWORLD) {
            val altar = HollowThrone.get(level).altar.orElse(null)
            if (altar != null && altar.closerThan(here, THRONE_SIGHT) && places.none { it.kind == AtlasPlace.THRONE && it.dimension == dimension }) {
                found(AtlasPlace(AtlasPlace.THRONE, dimension, altar, found = now))
            }
            // A field boss's camp, once you're near enough to see its fire
            for (site in FieldBosses.get(level).sites) {
                if (!site.built || !site.pos.closerThan(here, CAMP_SIGHT)) continue
                if (places.any { it.kind == AtlasPlace.FIELD_BOSS && it.dimension == dimension && it.ref == site.id }) continue
                found(AtlasPlace(AtlasPlace.FIELD_BOSS, dimension, site.pos, ref = site.id, found = now))
            }
        }

        if (changed) player.setData(ModAttachments.ATLAS, AtlasState(places))
        return fresh
    }

    private fun send(level: ServerLevel, player: ServerPlayer, fresh: List<AtlasPlace>, force: Boolean) {
        val payload = build(level, player, fresh)
        val last = sent[player]
        if (!force && fresh.isEmpty() && last != null && last.dimension == payload.dimension && last.marks == payload.marks && last.roads == payload.roads) return
        sent[player] = payload
        PacketDistributor.sendToPlayer(player, payload)
    }

    /** The client's copy: this dimension's places with what's known of them now. */
    private fun build(level: ServerLevel, player: ServerPlayer, fresh: List<AtlasPlace>): AtlasPayload {
        val dimension = level.dimension()
        val places = get(player).places.filter { it.dimension == dimension }
        val overworld = dimension == Level.OVERWORLD
        // Holdings, guilds and the named characters are all kept with the overworld
        val holdings = if (overworld) Holdings.get(level) else null
        val ours = Guilds.get(level).ledBy(player.uuid)?.id
        val people = if (overworld) Characters.get(level).placements.groupBy({ it.board }, { it.id }) else mapOf()
        val lairs = Lairs.get(level)
        val camps = if (overworld) FieldBosses.get(level) else null
        val network = RoadNetwork.get(level)
        val respawn = player.respawnConfig?.respawnData()?.globalPos()
        val restingAt = if (respawn != null && respawn.dimension() == dimension) respawn.pos() else null
        val marks = places.mapNotNull { place ->
            when (place.kind) {
                AtlasPlace.VILLAGE -> {
                    val holding = holdings?.at(place.pos)
                    var flags = 0
                    if (place.size > Advance.GUILD_HALL.ordinal) flags = flags or AtlasMark.HALL
                    if (holding != null) flags = flags or AtlasMark.HELD
                    if (holding != null && holding.guild == ours) flags = flags or AtlasMark.YOURS
                    if (network.roads.any { it.finished && it.touches(place.pos, ROAD_SLACK) }) flags = flags or AtlasMark.ROAD
                    AtlasMark(
                        AtlasPlace.VILLAGE, place.pos, "", place.size, flags,
                        holding?.let { TownControl.holderName(level, it.guild) } ?: "",
                        holding?.let { TownControl.holderColor(level, it.guild).textureDiffuseColor } ?: 0,
                        place.standing, people[place.pos] ?: listOf(),
                    )
                }
                AtlasPlace.LAIR -> {
                    val lair = lairs.lair(place.ref) ?: return@mapNotNull null
                    val courtier = lair.courtier
                    var flags = 0
                    if (lair.cleared) flags = flags or AtlasMark.CLEARED
                    if (lair.awake && !lair.cleared) flags = flags or AtlasMark.AWAKE
                    if (courtier != null) flags = flags or AtlasMark.VAULT
                    val label = if (courtier != null) "courtier.guildmark.${courtier.id}.vault" else "lair.guildmark.${lair.theme.id}"
                    AtlasMark(AtlasPlace.LAIR, lair.pos, label, lair.rank, flags, "", 0, 0, listOf())
                }
                AtlasPlace.THRONE -> {
                    val flags = if (MainQuest.get(player).finished) AtlasMark.CLEARED else 0
                    AtlasMark(AtlasPlace.THRONE, place.pos, "atlas.guildmark.throne", 0, flags, "", 0, 0, listOf())
                }
                AtlasPlace.WARDFIRE -> {
                    val flags = if (place.pos == restingAt) AtlasMark.RESTED else 0
                    AtlasMark(AtlasPlace.WARDFIRE, place.pos, "", 0, flags, "", 0, 0, listOf())
                }
                AtlasPlace.FIELD_BOSS -> {
                    val site = camps?.site(place.ref) ?: return@mapNotNull null
                    val flags = if (site.slain) AtlasMark.CLEARED else 0
                    // Named for its troll, whose name the label carries
                    AtlasMark(AtlasPlace.FIELD_BOSS, site.pos, "${HillTroll.NAME_KEY}.${site.name}", 0, flags, "", 0, 0, listOf())
                }
                else -> null
            }
        }
        val villages = places.filter { it.kind == AtlasPlace.VILLAGE }.map { it.pos }
        val roads = network.roads
            .filter { road -> villages.any { road.touches(it, ROAD_SLACK) } }
            .map { AtlasRoad(bends(it.points), it.finished) }
        return AtlasPayload(dimension, marks, roads, fresh.map { it.pos })
    }

    /** A road's waypoints with the straight runs folded up: only where it turns, as x, z pairs. */
    private fun bends(points: List<BlockPos>): List<Int> {
        val out = ArrayList<Int>()
        for (i in points.indices) {
            val p = points[i]
            if (i > 0 && i < points.size - 1) {
                val a = points[i - 1]
                val b = points[i + 1]
                if (p.x - a.x == b.x - p.x && p.z - a.z == b.z - p.z) continue
            }
            out.add(p.x)
            out.add(p.z)
        }
        return out
    }

    /** Every second: where the player's party mates are, so the compass and map can show those out of sight. */
    fun sendParty(level: ServerLevel, player: ServerPlayer) {
        val party = Parties.get(level.server).of(player.uuid) ?: return
        val mates = party.members.filter { it != player.uuid }
            .mapNotNull { level.server.playerList.getPlayer(it) }
            .filter { it.level() == level && !it.isSpectator }
            .map { PartyMark(it.uuid, it.blockX, it.blockZ) }
        PacketDistributor.sendToPlayer(player, PartyPositionsPayload(mates))
    }
}
