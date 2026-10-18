package io.github.vaspyyy.guildmark.townsfolk.streets

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/**
 * The streets' signs as the world keeps them (living-world/design-influence.md section 5), with the overworld as the
 * saved data `streets`: for each capital, by its column (its heart's x and z, as the signs' records name it, see
 * [Streets.column]), the dressing (its run, whether it's up or coming down, and since when the times have been good or
 * fallen back), the "To let" boards (each empty home and the day its board went up), and the day's alms table (where it
 * stands, its pot, who gave and who ate). A world from before has none, and every field reads back with its default.
 */
class StreetsData(private val towns: MutableMap<Long, Town> = HashMap()) : SavedData() {
    /** One capital's signs. */
    class Town(val column: Long) {
        // ---- The dressing (see Dressing) ----

        /** The last dressing's run (0: never dressed): its pieces' records carry it, so a stray from an earlier one is known. */
        var run = 0L

        /** The dressing's state ([NONE], [UP] or [DOWN], coming down), and the lantern strings'. */
        var dress = NONE
        var lanterns = NONE

        /** The market day the good times began, as last worked out (-1: not good). */
        var goodFrom = -1L

        /** The market day the times fell back below good while the town was dressed, and below thriving while the lanterns hung (-1: they haven't). */
        var fellOn = -1L
        var dimmedOn = -1L

        /** The market day the dressing began to come down (-1: it isn't). */
        var downFrom = -1L

        /** The streets command's dressing, whatever the times: [FORCE_NONE], [FORCE_GOOD], [FORCE_THRIVING] or [FORCE_DOWN]. */
        var force = FORCE_NONE

        // ---- The "To let" boards (see Signs) ----

        /** Each empty home with a board (its lodging id), and the clock day the board went up. */
        val boards = HashMap<Int, Long>()

        // ---- The alms table (see AlmsTable) ----

        /** The clock day the table was decided for (-1: never), and how it stands that day ([ALMS_NONE] to [ALMS_CLEARED]). */
        var almsDay = -1L
        var almsState = ALMS_NONE

        /** Where its middle stands, and the way its front faces (the hungry line up straight out from it). */
        var almsPos: BlockPos? = null
        var almsFront: Direction = Direction.NORTH

        /** Bowls of soup in the pot, how many it holds, how many have been served, and the meals players gave. */
        var soup = 0
        var cap = 0
        var served = 0
        var given = 0.0

        /** Why it's set up today, in a few words for the streets command. */
        var almsWhy = ""

        /** The players who gave at it today, and the residents who ate there (for Part E's FED favour). */
        val givers = ArrayList<UUID>()
        val fed = ArrayList<Int>()

        companion object {
            private class Dress(
                val run: Long, val dress: Int, val lanterns: Int, val goodFrom: Long, val fellOn: Long, val dimmedOn: Long,
                val downFrom: Long, val force: Int,
            )

            private val DRESS: Codec<Dress> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.optionalFieldOf("run", 0L).forGetter(Dress::run),
                    Codec.INT.optionalFieldOf("dress", NONE).forGetter(Dress::dress),
                    Codec.INT.optionalFieldOf("lanterns", NONE).forGetter(Dress::lanterns),
                    Codec.LONG.optionalFieldOf("good_from", -1L).forGetter(Dress::goodFrom),
                    Codec.LONG.optionalFieldOf("fell_on", -1L).forGetter(Dress::fellOn),
                    Codec.LONG.optionalFieldOf("dimmed_on", -1L).forGetter(Dress::dimmedOn),
                    Codec.LONG.optionalFieldOf("down_from", -1L).forGetter(Dress::downFrom),
                    Codec.INT.optionalFieldOf("force", FORCE_NONE).forGetter(Dress::force),
                ).apply(i, ::Dress)
            }

            private class Board(val home: Int, val day: Long)

            private val BOARD: Codec<Board> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("home").forGetter(Board::home),
                    Codec.LONG.optionalFieldOf("day", 0L).forGetter(Board::day),
                ).apply(i, ::Board)
            }

            private class Alms(
                val day: Long, val state: Int, val pos: Optional<BlockPos>, val front: Direction, val soup: Int, val cap: Int,
                val served: Int, val given: Double, val why: String, val givers: List<UUID>, val fed: List<Int>,
            )

            private val ALMS: Codec<Alms> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.optionalFieldOf("day", -1L).forGetter(Alms::day),
                    Codec.INT.optionalFieldOf("state", ALMS_NONE).forGetter(Alms::state),
                    BlockPos.CODEC.optionalFieldOf("pos").forGetter(Alms::pos),
                    Direction.CODEC.optionalFieldOf("front", Direction.NORTH).forGetter(Alms::front),
                    Codec.INT.optionalFieldOf("soup", 0).forGetter(Alms::soup),
                    Codec.INT.optionalFieldOf("cap", 0).forGetter(Alms::cap),
                    Codec.INT.optionalFieldOf("served", 0).forGetter(Alms::served),
                    Codec.DOUBLE.optionalFieldOf("given", 0.0).forGetter(Alms::given),
                    Codec.STRING.optionalFieldOf("why", "").forGetter(Alms::why),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("givers", listOf()).forGetter(Alms::givers),
                    Codec.INT.listOf().optionalFieldOf("fed", listOf()).forGetter(Alms::fed),
                ).apply(i, ::Alms)
            }

            val CODEC: Codec<Town> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("column").forGetter(Town::column),
                    DRESS.optionalFieldOf("dressing").forGetter {
                        Optional.of(Dress(it.run, it.dress, it.lanterns, it.goodFrom, it.fellOn, it.dimmedOn, it.downFrom, it.force))
                    },
                    BOARD.listOf().optionalFieldOf("boards", listOf()).forGetter { t -> t.boards.map { Board(it.key, it.value) } },
                    ALMS.optionalFieldOf("alms").forGetter {
                        Optional.of(Alms(it.almsDay, it.almsState, Optional.ofNullable(it.almsPos), it.almsFront, it.soup, it.cap, it.served, it.given, it.almsWhy, it.givers.toList(), it.fed.toList()))
                    },
                ).apply(i) { column, dress, boards, alms ->
                    Town(column).also { t ->
                        dress.ifPresent { d ->
                            t.run = d.run
                            t.dress = d.dress
                            t.lanterns = d.lanterns
                            t.goodFrom = d.goodFrom
                            t.fellOn = d.fellOn
                            t.dimmedOn = d.dimmedOn
                            t.downFrom = d.downFrom
                            t.force = d.force
                        }
                        for (b in boards) t.boards[b.home] = b.day
                        alms.ifPresent { a ->
                            t.almsDay = a.day
                            t.almsState = a.state
                            t.almsPos = a.pos.orElse(null)
                            t.almsFront = if (a.front.axis.isHorizontal) a.front else Direction.NORTH
                            t.soup = a.soup
                            t.cap = a.cap
                            t.served = a.served
                            t.given = a.given
                            t.almsWhy = a.why
                            t.givers.addAll(a.givers)
                            t.fed.addAll(a.fed)
                        }
                    }
                }
            }
        }
    }

    /** [column]'s record, if it has one yet. */
    operator fun get(column: Long): Town? = towns[column]

    /** [column]'s record, started now if it has none. */
    fun town(column: Long): Town = towns.getOrPut(column) {
        setDirty()
        Town(column)
    }

    fun all(): Collection<Town> = towns.values

    fun changed() = setDirty()

    companion object {
        /** A sign's states: not up, up (or going up), coming down. */
        const val NONE = 0
        const val UP = 1
        const val DOWN = 2

        /** The streets command's dressing: none, good times' (no lanterns), thriving times' (lanterns too), or taken down now. */
        const val FORCE_NONE = 0
        const val FORCE_GOOD = 1
        const val FORCE_THRIVING = 2
        const val FORCE_DOWN = 3

        /** The alms table's day: nothing today, to be set up at noon, set up (serving while its keeper's at it), cleared away. */
        const val ALMS_NONE = 0
        const val ALMS_PLANNED = 1
        const val ALMS_UP = 2
        const val ALMS_CLEARED = 3

        private val CODEC: Codec<StreetsData> = RecordCodecBuilder.create { i ->
            i.group(
                Town.CODEC.listOf().optionalFieldOf("towns", listOf()).forGetter { it.towns.values.toList() },
            ).apply(i) { list -> StreetsData(list.associateBy { it.column }.toMutableMap()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "streets"), Supplier { StreetsData() }, CODEC)

        /** Kept with the overworld, whichever level asks. */
        fun get(level: ServerLevel): StreetsData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
