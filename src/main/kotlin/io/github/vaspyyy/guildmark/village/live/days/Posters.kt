package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.homestead.HomesteadRegistry
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Work
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * Someone who can pin a note on a board (living-world/villages/design.md 6.5): a vanilla villager, or a village's resident. [name] is
 * what the note is signed with ("Maren Brook the Farmer"), [craft] the profession it is written as (an id such as "minecraft:farmer",
 * or "" for none) and [land] the land the village is in ("plains", "desert", ...), or null for plains.
 */
class Poster(val entity: Mob, val name: Component, val craft: String, val land: String?) {
    /** Whether this is one of a village's residents (a townsperson), not a vanilla villager. */
    val resident: Boolean get() = entity is Townsperson

    /** [craft] as an id, or null for none. */
    val profession: Identifier? get() = craft.takeIf { it.isNotEmpty() }?.let { Identifier.tryParse(it) }
}

/**
 * Who pins the village's note on the board (design 5.4 and 6.5): once a day one resident with an errand walks to the board with a
 * note and nails it up. Which resident it is the day's seed says, the same all day: one of the grown-up folk who are not of the watch,
 * the holder of the bell last (they have their own errand at dusk). The errand is a stretch of the day in one of four slots a day
 * ([slots]); a resident who has not pinned by the afternoon and a board with room on it are sent to it by [send]. The `Poster` of a
 * resident keeps the day they pinned in the record ([Resident.pinned]); a villager's is the attachment it always was.
 */
object Posters {
    /** The slots of the errand, as hours (from, to): a weekday's, and the rest day's. */
    private val SLOTS = listOf(9.33 to 10.0, 14.0 to 14.75, 15.4 to 16.15, 10.2 to 10.95)
    private val REST_SLOTS = listOf(14.0 to 14.75, 15.4 to 16.15, 10.4 to 11.15)

    /** How long an errand to the board counts as under way, in ticks. */
    private const val GOING = 3000L

    /** Who is on the way to the board, by the village's key and the tick they set out. */
    private val going = WeakHashMap<Townsperson, Pair<Long, Long>>()

    // --- Who ---

    /**
     * The resident id who pins the note of the village at [centre] on town day [day], or null if it has no one to: one of the
     * grown-up folk who are not of the watch, in the order the day's seed gives them, the holder of the bell last.
     */
    fun pick(level: ServerLevel, centre: BlockPos, day: Long): Int? = pick(Residents.get(level).byCentre(centre), centre, day)

    fun pick(folk: List<Resident>, centre: BlockPos, day: Long): Int? {
        val key = centre.asLong()
        val bell = BellKeeper.list(folk, centre, day).firstOrNull()?.id
        val able = folk.filter { it.alive && it.id >= 0 && it.role.adult && it.role != TownRole.GUARD }
        return able.sortedWith(compareBy<Resident>({ if (it.id == bell) 1 else 0 }, { BellKeeper.rank(day, key xor 0x5DEECE66DL, it.id) }, { it.id })).firstOrNull()?.id
    }

    /** The slots of the day's errand to the board, the day's own first and the others after it in turn. */
    fun slots(centre: BlockPos, day: Long, rest: Boolean): List<Pair<Double, Double>> {
        val all = if (rest) REST_SLOTS else SLOTS
        val first = Math.floorMod(BellKeeper.rank(day, centre.asLong() xor 0x1F123BB5L, 0), all.size.toLong()).toInt()
        return all.indices.map { all[(first + it) % all.size] }
    }

    // --- The posters ---

    /** The village record whose board is at [anchor], if the villages are on and their folk with them. */
    private fun village(level: ServerLevel, anchor: BlockPos): VillageRec? {
        if (!Villages.enabled() || !GuildmarkConfig.VILLAGE_FOLK.get()) return null
        return Villages.at(level, anchor)?.takeIf { it.board == anchor.asLong() }
    }

    /** The board (anchor cell) of the village at [centre], if its record has one. */
    private fun anchorOf(level: ServerLevel, centre: BlockPos): BlockPos? =
        Villages.byKey(level, centre.asLong())?.board?.takeIf { it != -1L }?.let { BlockPos.of(it) }

    /** Everyone within [within] blocks of the board at [board] who could pin a note on it: the villagers, and the village's residents. */
    fun near(level: ServerLevel, board: BlockPos, within: Int): List<Poster> {
        val box = AABB(board).inflate(within.toDouble())
        val out = ArrayList<Poster>()
        for (v in level.getEntitiesOfClass(Villager::class.java, box) { it.isAlive && !it.isBaby && !it.isSleeping }) out += of(v)
        val rec = village(level, board) ?: return out
        for (p in level.getEntitiesOfClass(Townsperson::class.java, box) { it.isAlive && !it.isSleeping }) of(level, p, board, rec.key)?.let { out += it }
        return out
    }

    /** A vanilla villager as a poster. */
    fun of(v: Villager): Poster {
        val craft = v.villagerData.profession().unwrapKey().orElse(null)?.identifier()
        return Poster(v, Component.literal(VillageBoards.posterName(v, craft)), craft?.toString() ?: "", v.villagerData.type().unwrapKey().orElse(null)?.identifier()?.path)
    }

    /** The profession a resident's notes are written as when they kept none from before: by their trade. */
    private fun craftOf(r: Resident): String {
        if (r.craft.isNotEmpty()) return r.craft
        return when (r.role) {
            TownRole.FARMER -> "minecraft:farmer"
            TownRole.FISHER -> "minecraft:fisherman"
            TownRole.SMITH -> listOf("minecraft:toolsmith", "minecraft:armorer", "minecraft:weaponsmith")[r.id % 3]
            TownRole.BAKER -> "minecraft:butcher"
            TownRole.PRIEST -> "minecraft:cleric"
            TownRole.MERCHANT -> listOf("minecraft:shepherd", "minecraft:leatherworker", "minecraft:fletcher")[r.id % 3]
            TownRole.CITIZEN -> listOf("minecraft:mason", "minecraft:librarian", "minecraft:cartographer")[r.id % 3]
            else -> ""
        }
    }

    /** [p] as a poster at the board at [board] of the village whose key is [key], if they are one of its grown-up residents. */
    fun of(level: ServerLevel, p: Townsperson, board: BlockPos, key: Long): Poster? {
        if (p.resident < 0 || p.crowd || p.watcher || p.captiveOf >= 0) return null
        val r = Residents.get(level).of(p)?.takeIf { it.alive && it.role.adult && it.capital.asLong() == key } ?: return null
        val craft = craftOf(r)
        val title = QuestGenerator.title(craft.takeIf { it.isNotEmpty() }?.let { Identifier.tryParse(it) })
        val land = VillagerType.byBiome(level.getBiome(board)).identifier().path
        return Poster(p, Component.literal("${r.name} the $title"), craft, land)
    }

    /** Whether [p] has pinned their note on town day [day] (as the boards count them: [VillageBoards.day]). */
    fun pinnedToday(p: Poster, day: Long): Boolean {
        val e = p.entity
        if (e is Townsperson) {
            val level = e.level() as? ServerLevel ?: return false
            return Residents.get(level).of(e)?.pinned == day
        }
        return e.getData(ModAttachments.LAST_PINNED_DAY) == day
    }

    /** [p] has pinned their note on [day]. */
    fun mark(p: Poster, day: Long) {
        val e = p.entity
        if (e is Townsperson) {
            val level = e.level() as? ServerLevel ?: return
            val residents = Residents.get(level)
            residents.of(e)?.let {
                it.pinned = day
                residents.changed()
            }
        } else {
            e.setData(ModAttachments.LAST_PINNED_DAY, day)
        }
    }

    // --- The errand ---

    /** Where to stand to pin on [part] of the board at [anchor]: in front of its column, on the ground; null if there is no such place. */
    private fun standFor(level: ServerLevel, anchor: BlockPos, facing: net.minecraft.core.Direction, part: BoardPart, who: Townsperson): BlockPos? {
        val bottom = BoardPart.at(part.col, 0) ?: return null
        val front = NoticeBoardBlock.cellPos(anchor, facing, bottom).relative(facing)
        return listOf(0, -1, 1).map { front.above(it) }.firstOrNull { Places.standable(level, it) && Claims.free(level, it, who) }
    }

    /** The cells of the board at [anchor], in the order of [BoardPart.entries]. */
    private fun cells(level: ServerLevel, anchor: BlockPos, facing: net.minecraft.core.Direction): List<QuestBoardBlockEntity> =
        BoardPart.entries.mapNotNull { level.getBlockEntity(NoticeBoardBlock.cellPos(anchor, facing, it)) as? QuestBoardBlockEntity }

    /**
     * [p]'s errand to the village's board (the poster's stretch): paper in hand, out to the board, in front of a bare cell, two blows of
     * the hammer on the nail with the note pinned at the second, and a moment after. Null when the village has no board standing, they
     * have pinned today, the board has no bare cell or there is no place in front of one to stand ([now]: whether they have or not).
     */
    fun errand(p: Townsperson, life: Life, now: Boolean = false): Task? {
        val level = p.level() as? ServerLevel ?: return null
        val key = life.places.centre.asLong()
        val anchor = anchorOf(level, life.places.centre) ?: return null
        if (!level.isLoaded(anchor)) return null
        val state = level.getBlockState(anchor)
        if (state.block !is QuestBoardBlock) return null
        val facing = state.getValue(NoticeBoardBlock.FACING)
        val poster = of(level, p, anchor, key) ?: return null
        val day = VillageBoards.day(level)
        if (!now && pinnedToday(poster, day)) return null
        // A bare cell, the one whose place to stand is nearest
        val bare = cells(level, anchor, facing).filter { it.note == null }
            .mapNotNull { cell ->
                val part = cell.blockState.getValue(NoticeBoardBlock.PART)
                standFor(level, anchor, facing, part, p)?.let { Triple(part, it, it.distSqr(p.blockPosition())) }
            }.minByOrNull { it.third } ?: return null
        val (part, stand, _) = bare
        var done = false
        val nailing = Step(Activity.NAIL, STRIKES)
        val work = Work(p, Spot(stand, facing.opposite), Vec3.atCenterOf(NoticeBoardBlock.cellPos(anchor, facing, part)), listOf(nailing, Step(Activity.NONE, 20)), onTick = { s, t ->
            if (s === nailing) {
                if (t == FIRST || t == SECOND) level.playSound(null, stand, HomesteadRegistry.HAMMER.get(), SoundSource.NEUTRAL, 0.8f, 1.0f)
                if (t == SECOND && !done) {
                    done = true
                    pin(level, poster, anchor, facing, part, day)
                }
            }
        })
        going[p] = key to level.gameTime
        return Acts.hold(p, ItemStack(Items.PAPER)).andThen(work).andThen(Acts.hold(p, ItemStack.EMPTY)).andThen(Later(p) {
            going.remove(p)
            null
        })
    }

    /** The note, pinned on [part] if that is still bare, else on another bare cell; the day marked either way. */
    private fun pin(level: ServerLevel, poster: Poster, anchor: BlockPos, facing: net.minecraft.core.Direction, part: BoardPart, day: Long) {
        // Someone else's pin (the board's own tick) has done it already
        if (pinnedToday(poster, day)) return
        val all = cells(level, anchor, facing)
        val cell = all.firstOrNull { it.note == null && it.blockState.getValue(NoticeBoardBlock.PART) == part } ?: all.filter { it.note == null }.randomOrNull()
        if (cell == null) {
            mark(poster, day)
            return
        }
        VillageBoards.pinFor(level, poster, cell, facing, all, quiet = true)
    }

    /** Ticks of the nailing, and the two blows of the hammer. */
    private const val STRIKES = 32
    private const val FIRST = 5
    private const val SECOND = 21

    /** Whether anyone is on the way to the village's board (set out within the last [GOING] ticks). */
    private fun busy(level: ServerLevel, key: Long): Boolean =
        going.entries.any { (p, at) -> p.isAlive && p.level() === level && at.first == key && level.gameTime - at.second in 0 until GOING }

    /**
     * The board at [anchor] has room and nobody has come: the first resident near it who has not pinned today and is free to go is
     * sent to it now. Called by the board's own tick in the afternoon, so a day whose poster could not go still gets its note.
     */
    fun send(level: ServerLevel, anchor: BlockPos): Boolean {
        val rec = village(level, anchor) ?: return false
        if (busy(level, rec.key)) return false
        val day = VillageBoards.day(level)
        for (poster in near(level, anchor, SEND_WITHIN)) {
            val p = poster.entity as? Townsperson ?: continue
            if (pinnedToday(poster, day) || !Aside.free(p)) continue
            val life = Days.life(p) ?: continue
            val task = errand(p, life) ?: continue
            p.routine.replace(task)
            return true
        }
        return false
    }

    /** How far from the board a resident is sent from. */
    private const val SEND_WITHIN = 40
}
