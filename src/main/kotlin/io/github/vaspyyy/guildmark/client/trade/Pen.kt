package io.github.vaspyyy.guildmark.client.trade

/**
 * A picture file that is drawn from in pieces: where it is under `assets/guildmark/` and how many texels it is across and down
 * (the Tally's pictures are two texels to a screen pixel).
 */
class Tex(val path: String, val w: Int, val h: Int)

/**
 * What the Tally is drawn with (living-world/trader/design.md, Part 1). The book's pages are written against this and not against the
 * game's graphics, so that one drawing serves the screen ([GuiPen]) and the preview sheets the art is checked by. It is small on
 * purpose: boxes, letters, pictures and a stack of moves, and whatever else a page wants (a ring, a rope, a hand-ruled line) it makes of those.
 * Every position is a whole screen pixel; a move may be fractional, and the picture follows it.
 */
interface Pen {
    /** How wide [text] is in the book's ordinary letters. */
    fun width(text: String): Int

    /** [text] in ordinary letters, its top at [y]: with a dark [shade] under it, or set slanting ([italic], a hand's note in the margin). */
    fun text(text: String, x: Int, y: Int, color: Int, shade: Boolean = false, italic: Boolean = false)

    /** How wide [text] is in the carved capitals the mod's headings are lettered in. */
    fun headingWidth(text: String): Int

    /** [text] in the carved capitals, their tops at [y]. */
    fun heading(text: String, x: Int, y: Int, color: Int)

    /** A box of one colour (alpha counts). */
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int)

    /** The sprite [name] (under `textures/gui/sprites/`, as the game's sprites are named) drawn to {[w] x [h]}, tinted by [color]. */
    fun sprite(name: String, x: Int, y: Int, w: Int, h: Int, color: Int = WHITE)

    /** The piece {[uw] x [vh]} of [tex] at {[u], [v]} (texels) drawn to {[w] x [h]}, tinted by [color]. */
    fun tex(tex: Tex, x: Int, y: Int, w: Int, h: Int, u: Int, v: Int, uw: Int, vh: Int, color: Int = WHITE)

    /** The item of the good [id] (see `economy/Goods.kt`), 16 by 16, at [x], [y]. */
    fun good(id: String, x: Int, y: Int)

    /** Starts a move: everything drawn until the matching [restore] is moved, scaled and turned as asked. */
    fun save()

    fun restore()

    fun translate(x: Float, y: Float)

    fun scale(x: Float, y: Float)

    /** Turns what is drawn from now on by [radians], clockwise, about the origin as it has been moved to. */
    fun rotate(radians: Float)

    /** Draws only inside the box, until [unclip]. Boxes nest. */
    fun clip(x0: Int, y0: Int, x1: Int, y1: Int)

    fun unclip()

    companion object {
        const val WHITE = -1
    }
}
