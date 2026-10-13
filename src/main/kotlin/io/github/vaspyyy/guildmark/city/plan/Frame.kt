package io.github.vaspyyy.guildmark.city.plan

/**
 * A building's own frame, drawn as if it faced north: u runs east across the front, v south from the
 * front wall to the back, and (0, 0) is the north-west corner. [front] is the way it really faces;
 * positions and facings are turned to match, so every building is written once, facing north.
 */
class Frame(val canvas: Canvas, val lot: Rect, val front: Dir) {
    /** Across the front and from front to back. */
    val w: Int = if (front.alongX) lot.depth else lot.width
    val d: Int = if (front.alongX) lot.width else lot.depth

    fun x(u: Int, v: Int): Int = when (front) {
        Dir.N -> lot.x0 + u
        Dir.E -> lot.x1 - v
        Dir.S -> lot.x1 - u
        Dir.W -> lot.x0 + v
    }

    fun z(u: Int, v: Int): Int = when (front) {
        Dir.N -> lot.z0 + v
        Dir.E -> lot.z0 + u
        Dir.S -> lot.z1 - v
        Dir.W -> lot.z1 - u
    }

    /** A direction in this frame, turned to the city's. */
    fun turn(local: Dir): Dir = when (front) {
        Dir.N -> local
        Dir.E -> local.cw
        Dir.S -> local.opposite
        Dir.W -> local.ccw
    }

    fun turn(axis: Axis): Axis = if (axis == Axis.Y || !front.alongX) axis else if (axis == Axis.X) Axis.Z else Axis.X

    fun set(u: Int, y: Int, v: Int, b: B) {
        val dir = b.dir
        val turned = if (dir == null && b.axis == Axis.Y) b else B(b.m, dir?.let { turn(it) }, b.top, turn(b.axis), b.color, b.v, b.part, b.open)
        canvas.set(x(u, v), y, z(u, v), turned)
    }

    fun fill(u0: Int, y0: Int, v0: Int, u1: Int, y1: Int, v1: Int, b: B) {
        for (u in minOf(u0, u1)..maxOf(u0, u1)) for (v in minOf(v0, v1)..maxOf(v0, v1)) for (y in minOf(y0, y1)..maxOf(y0, y1)) set(u, y, v, b)
    }

    fun chest(u: Int, y: Int, v: Int, facing: Dir, loot: Loot) = canvas.chest(x(u, v), y, z(u, v), turn(facing), loot)

    fun spawn(what: Spawn, u: Int, y: Int, v: Int, facing: Dir = Dir.N) = canvas.spawn(what, x(u, v), y, z(u, v), turn(facing))

    /** Where [u], [y], [v] in this frame is in the city's. */
    fun at(u: Int, y: Int, v: Int): At = At(x(u, v), y, z(u, v))

    /** One of the townsfolk standing at [u], [y], [v]; with [facing], that's their post, facing that way. */
    fun resident(folk: Folk, u: Int, y: Int, v: Int, work: At? = null, facing: Dir? = null, home: At? = null) {
        val here = at(u, y, v)
        canvas.resident(folk, here, work, if (facing != null) here else null, facing?.let { turn(it) }, home)
    }

    /** A door two blocks high at [u], [v], opening inward (toward +v unless [inward] says otherwise). */
    fun door(u: Int, y: Int, v: Int, inward: Dir = Dir.S, hingeRight: Boolean = false) {
        set(u, y, v, B(M.DOOR, inward, part = 0, v = if (hingeRight) 1 else 0))
        set(u, y + 1, v, B(M.DOOR, inward, part = 1, v = if (hingeRight) 1 else 0))
    }

    /** A bed with its foot at [u], [v] and its head toward [toward]. */
    fun bed(u: Int, y: Int, v: Int, toward: Dir, color: Dye) {
        set(u, y, v, B(M.BED, toward, color = color, part = 0))
        set(u + toward.dx, y, v + toward.dz, B(M.BED, toward, color = color, part = 1))
    }
}
