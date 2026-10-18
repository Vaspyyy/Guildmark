package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.function.Supplier

/**
 * The households coming to each capital and leaving it (living-world/design-influence.md section 6), kept with the
 * overworld as `comings`: those due (why, when, the trades they bring and the home they're for), those away (who, since
 * when, and the home they left), the last day one left, and what [Comings] needs to work each day out once and only
 * once: the last day worked out, the dead already seen to, the bodies still to be made, and where the newcomers work.
 * Every field reads back with a default, so a world saved before any of it starts empty.
 */
class ComingsData(
    private val towns: MutableMap<Long, Town> = HashMap(),
    private var next: Int = 1,
    /** The newcomer each works sent for (see [Comings.hire]): its id to theirs, -1 while they're still due. */
    val hired: MutableMap<Int, Int> = HashMap(),
) : SavedData() {

    /**
     * A household due: [reason] "replace" (for the [dead]), "grow" (good times) or "trade" (sent for by the [works]),
     * one of each of [roles] (grown-ups first), each taking the plan's place given in [planned] (-1 for none), bound
     * for lodging [home] (-1: found as they come). It's due [wait] market days after [day] in steady times (see
     * [Comings]: longer in hard times, sooner in good).
     */
    class Due(
        val id: Int,
        val reason: String,
        val roles: List<TownRole>,
        val planned: List<Int>,
        var home: Int,
        val day: Long,
        val wait: Int,
        val dead: List<Int>,
        val works: Int,
    ) {
        companion object {
            val CODEC: Codec<Due> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("id").forGetter(Due::id),
                    Codec.STRING.optionalFieldOf("reason", "replace").forGetter(Due::reason),
                    ROLE.listOf().optionalFieldOf("roles", listOf()).forGetter(Due::roles),
                    Codec.INT.listOf().optionalFieldOf("planned", listOf()).forGetter(Due::planned),
                    Codec.INT.optionalFieldOf("home", -1).forGetter(Due::home),
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Due::day),
                    Codec.INT.optionalFieldOf("wait", 0).forGetter(Due::wait),
                    Codec.INT.listOf().optionalFieldOf("dead", listOf()).forGetter(Due::dead),
                    Codec.INT.optionalFieldOf("works", -1).forGetter(Due::works),
                ).apply(i) { id, reason, roles, planned, home, day, wait, dead, works -> Due(id, reason, roles, planned, home, day, wait, dead, works) }
            }
        }
    }

    /** A household that left, or came: its [members] (resident ids), the [home] it left or came to, the market [day] and why. */
    class Moved(val id: Int, val members: List<Int>, val home: Int, val day: Long, val reason: String) {
        companion object {
            val CODEC: Codec<Moved> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("id").forGetter(Moved::id),
                    Codec.INT.listOf().optionalFieldOf("members", listOf()).forGetter(Moved::members),
                    Codec.INT.optionalFieldOf("home", -1).forGetter(Moved::home),
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Moved::day),
                    Codec.STRING.optionalFieldOf("reason", "").forGetter(Moved::reason),
                ).apply(i) { id, members, home, day, reason -> Moved(id, members, home, day, reason) }
            }
        }
    }

    /** Where resident [id] works, as their body is set down: the block they work at, their post and its facing, their evening's haunt. */
    class Post(val id: Int, val work: BlockPos?, val post: BlockPos?, val facing: Direction?, val evening: BlockPos?) {
        companion object {
            val CODEC: Codec<Post> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("id").forGetter(Post::id),
                    BlockPos.CODEC.optionalFieldOf("work").forGetter { Optional.ofNullable(it.work) },
                    BlockPos.CODEC.optionalFieldOf("post").forGetter { Optional.ofNullable(it.post) },
                    Direction.CODEC.optionalFieldOf("facing").forGetter { Optional.ofNullable(it.facing) },
                    BlockPos.CODEC.optionalFieldOf("evening").forGetter { Optional.ofNullable(it.evening) },
                ).apply(i) { id, work, post, facing, evening -> Post(id, work.orElse(null), post.orElse(null), facing.orElse(null), evening.orElse(null)) }
            }
        }
    }

    /** One capital's comings and goings. */
    class Town(
        /** The last market day worked out ([NEVER] before the first: that day is only noted, and the next is the first worked out). */
        var closed: Long = NEVER,
        /** The last market day a household left, or -1. */
        var left: Long = -1L,
        /** The last market day a household came to grow it. */
        var grown: Long = NEVER,
        /** The last market day a household came in (one a day at most). */
        var came: Long = NEVER,
        /** The market day a household is to leave on (after five hard days running), or [NEVER]. */
        var leaving: Long = NEVER,
        val due: MutableList<Due> = ArrayList(),
        val away: MutableList<Moved> = ArrayList(),
        /** Those come in (and come back), oldest first, for [LOG] days. */
        val log: MutableList<Moved> = ArrayList(),
        /** The dead already seen to (replaced, or not to be). */
        val seen: MutableSet<Int> = HashSet(),
        /** Those in by the record whose bodies are still to be made, at home out of sight. */
        val bodies: MutableSet<Int> = HashSet(),
        /** Those whose bodies were made anew: any other body of theirs is someone gone, and is dropped as it loads. */
        val renewed: MutableSet<Int> = HashSet(),
        val posts: MutableMap<Int, Post> = HashMap(),
        /** The last word on why nobody came or went when someone might have (for `/guildmark comings`). */
        var why: String = "",
    ) {
        companion object {
            private val INTS: Codec<MutableSet<Int>> = Codec.INT.listOf().xmap<MutableSet<Int>>({ it.toHashSet() }, { it.sorted() })

            val CODEC: Codec<Pair<Long, Town>> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("capital").forGetter { it.first },
                    Codec.LONG.optionalFieldOf("closed", NEVER).forGetter { it.second.closed },
                    Codec.LONG.optionalFieldOf("left", -1L).forGetter { it.second.left },
                    Codec.LONG.optionalFieldOf("grown", NEVER).forGetter { it.second.grown },
                    Codec.LONG.optionalFieldOf("came", NEVER).forGetter { it.second.came },
                    Codec.LONG.optionalFieldOf("leaving", NEVER).forGetter { it.second.leaving },
                    Due.CODEC.listOf().optionalFieldOf("due", listOf()).forGetter { it.second.due },
                    Moved.CODEC.listOf().optionalFieldOf("away", listOf()).forGetter { it.second.away },
                    Moved.CODEC.listOf().optionalFieldOf("log", listOf()).forGetter { it.second.log },
                    INTS.optionalFieldOf("seen", HashSet()).forGetter { it.second.seen },
                    INTS.optionalFieldOf("bodies", HashSet()).forGetter { it.second.bodies },
                    INTS.optionalFieldOf("renewed", HashSet()).forGetter { it.second.renewed },
                    Post.CODEC.listOf().optionalFieldOf("posts", listOf()).forGetter { it.second.posts.values.sortedBy { p -> p.id } },
                    Codec.STRING.optionalFieldOf("why", "").forGetter { it.second.why },
                ).apply(i) { capital, closed, left, grown, came, leaving, due, away, log, seen, bodies, renewed, posts, why ->
                    capital to Town(
                        closed, left, grown, came, leaving, due.toMutableList(), away.toMutableList(), log.toMutableList(),
                        seen.toHashSet(), bodies.toHashSet(), renewed.toHashSet(), posts.associateBy { it.id }.toMutableMap(), why,
                    )
                }
            }
        }
    }

    /** The record of the capital whose heart is [capital], if it has one. */
    fun find(capital: Long): Town? = towns[capital]

    /** The record of the capital whose heart is [capital], begun if it has none. */
    fun town(capital: Long): Town = towns.getOrPut(capital) {
        setDirty()
        Town()
    }

    /** Every capital with a record, by its heart. */
    fun all(): Map<Long, Town> = towns

    /** A new household's id. */
    fun newId(): Int = next++.also { setDirty() }

    fun changed() = setDirty()

    companion object {
        /** No day yet. */
        const val NEVER = Long.MIN_VALUE / 4

        /** Market days the households come in are kept for (`Comings.arrived`, `/guildmark comings`). */
        const val LOG = 30

        private val ROLE: Codec<TownRole> = Codec.STRING.xmap({ TownRole.byName(it) ?: TownRole.CITIZEN }, { it.id })

        private val HIRED: Codec<Pair<Int, Int>> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("works").forGetter { it.first },
                Codec.INT.optionalFieldOf("resident", -1).forGetter { it.second },
            ).apply(i) { works, resident -> works to resident }
        }

        private val CODEC: Codec<ComingsData> = RecordCodecBuilder.create { i ->
            i.group(
                Town.CODEC.listOf().optionalFieldOf("towns", listOf()).forGetter { d -> d.towns.entries.sortedBy { it.key }.map { it.key to it.value } },
                Codec.INT.optionalFieldOf("next", 1).forGetter { it.next },
                HIRED.listOf().optionalFieldOf("hired", listOf()).forGetter { d -> d.hired.entries.sortedBy { it.key }.map { it.key to it.value } },
            ).apply(i) { towns, next, hired -> ComingsData(towns.toMap(HashMap()), next, hired.toMap(HashMap())) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "comings"), Supplier { ComingsData() }, CODEC)

        fun get(level: ServerLevel): ComingsData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
