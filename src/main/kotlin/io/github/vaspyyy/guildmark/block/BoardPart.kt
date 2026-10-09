package io.github.vaspyyy.guildmark.block

import net.minecraft.util.StringRepresentable

/** One cell of the 3x2 quest board. [col] runs left to right as seen from the front, [row] bottom to top. */
enum class BoardPart(private val id: String, val col: Int, val row: Int) : StringRepresentable {
    BOTTOM_LEFT("bottom_left", -1, 0),
    BOTTOM_MIDDLE("bottom_middle", 0, 0),
    BOTTOM_RIGHT("bottom_right", 1, 0),
    TOP_LEFT("top_left", -1, 1),
    TOP_MIDDLE("top_middle", 0, 1),
    TOP_RIGHT("top_right", 1, 1);

    override fun getSerializedName(): String = id

    companion object {
        /** The cell the player places; it drops the board item. */
        val ANCHOR = BOTTOM_MIDDLE

        fun at(col: Int, row: Int): BoardPart? = entries.firstOrNull { it.col == col && it.row == row }
    }
}
