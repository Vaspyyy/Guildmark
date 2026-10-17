package io.github.vaspyyy.guildmark.homestead.plan

import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.M

/**
 * The stages a homestead's tier goes up in, in order: every block of the tier's plan falls in one of them (see
 * [Staging.stage]), and the builders finish one stage, celebrate it, and start the next. [key] names it in lang
 * files (gui.guildmark.homestead.stage.<key>) and in the builders' lines.
 */
enum class Stage(val key: String) {
    /** The corner stakes moved out to the new bounds. */
    STAKES("stakes"),
    /** Clearing growth and levelling the ground under the lots. */
    GROUNDWORK("groundwork"),
    /** Stone footings and the floors laid over them. */
    FOOTINGS("footings"),
    /** The walls, course by course, up to the eaves. */
    WALLS("walls"),
    /** Scaffolding put up round anything with a roof out of reach from the ground. */
    SCAFFOLD("scaffold"),
    /** Gables, the roof from the eaves to the ridge, and the chimneys. */
    ROOF("roof"),
    /** A green bough set on the highest ridge; the crew cheers and a rocket goes up. */
    TOPPING("topping"),
    /** The scaffolding taken down again. */
    UNSCAFFOLD("unscaffold"),
    /** Doors, windows, shutters and the lamps on the walls. */
    OPENINGS("openings"),
    /** The rooms furnished: beds, tables, hearths, chests (empty), shelves. */
    FITTING("fitting"),
    /** Paths, fences and gates, gardens and fields, the well and the yard's things, trees, and then the animals. */
    GROUNDS("grounds");

    companion object {
        fun byId(id: Int): Stage = entries.getOrElse(id) { GROUNDS }
    }
}

/** What a block is made of, as far as the bill goes. [FREE] is earth, plants, water and the like: no one pays for those. */
enum class Stuff(val key: String) {
    TIMBER("timber"), STONE("stone"), GLASS("glass"), IRON("iron"), WOOL("wool"), FREE("free");

    companion object {
        /** The kinds that are paid for, in the order the ledger lists them. */
        val PAID = listOf(TIMBER, STONE, GLASS, IRON, WOOL)
    }
}

/** What drew a block: a building, the things about the yard, a garden, fences, paths, fields, a pen. */
enum class PieceKind { HOUSE, PROP, TREE, GARDEN, FENCE, PATH, FIELD, PEN, YARD, STAKE }

/** One thing the layout drew, as the recording knows it: what it is, its lot and the height it stands at. */
class PieceInfo(val kind: PieceKind, val lot: io.github.vaspyyy.guildmark.city.plan.Rect, val y: Int) {
    /** For a building: the lowest course of its roof, worked out once everything is drawn (Int.MAX_VALUE: no roof). */
    var roofBase: Int = Int.MAX_VALUE
}

/**
 * Sorting a plan into [Stage]s and [Stuff]: what a block is decides most of it (a roof tile is roofing, a bed is
 * fitting out), and where it is in the building that drew it decides the rest (a plank at floor level is a floor,
 * above the eaves it's a gable).
 */
object Staging {
    /** The stage a block [b] at height [y] goes up in, drawn as part of [piece]. */
    fun stage(b: B, y: Int, piece: PieceInfo): Stage {
        val m = b.m
        return when (piece.kind) {
            PieceKind.STAKE -> Stage.STAKES
            PieceKind.PROP, PieceKind.TREE, PieceKind.GARDEN, PieceKind.FENCE, PieceKind.PATH, PieceKind.FIELD,
            PieceKind.PEN, PieceKind.YARD -> if (m == M.AIR) Stage.GROUNDWORK else Stage.GROUNDS
            PieceKind.HOUSE -> house(b, y, piece)
        }.let { if (m == M.AIR && it != Stage.STAKES) Stage.GROUNDWORK else it }
    }

    private fun house(b: B, y: Int, piece: PieceInfo): Stage {
        val m = b.m
        val base = piece.y
        return when (m) {
            M.ROOF, M.ROOF_STAIRS, M.ROOF_SLAB -> Stage.ROOF
            M.WINDOW, M.GLASS, M.STAINED, M.DOOR, M.SHUTTER, M.TORCH, M.BANNER -> Stage.OPENINGS
            M.LANTERN, M.SOUL_LANTERN, M.CHAIN -> if (y > piece.roofBase) Stage.ROOF else Stage.OPENINGS
            M.BED, M.CHEST, M.BARREL, M.BOOKSHELF, M.LECTERN, M.CRAFTING_TABLE, M.FURNACE, M.SMOKER, M.BLAST_FURNACE,
            M.ANVIL, M.GRINDSTONE, M.SMITHING_TABLE, M.STONECUTTER, M.CARTOGRAPHY_TABLE, M.FLETCHING_TABLE, M.LOOM,
            M.CAULDRON, M.BREWING_STAND, M.COMPOSTER, M.CARPET, M.WOOL, M.FLOWER_POT, M.CANDLE, M.BELL, M.CRATE,
            M.HAY, M.TARGET, M.GOLD -> Stage.FITTING
            // A fire on the chimney's top goes with the chimney; any lower is a hearth
            M.CAMPFIRE -> if (y > piece.roofBase) Stage.ROOF else Stage.FITTING
            M.LADDER, M.TRAPDOOR -> Stage.FITTING
            M.GRASS, M.SOIL, M.FILL, M.PAVE_PLAZA, M.PAVE_STREET, M.PAVE_LANE, M.PAVE_YARD, M.TRACK, M.FARMLAND ->
                if (y < base) Stage.FOOTINGS else Stage.GROUNDS
            M.FLOWER, M.CROP, M.LEAVES, M.WATER, M.PUMPKIN, M.FENCE_GATE -> Stage.GROUNDS
            // Fences on a building are its rails and balconies: up with the walls, or with the roof above the eaves
            M.FENCE -> if (y > piece.roofBase) Stage.ROOF else if (y <= base) Stage.GROUNDS else Stage.WALLS
            M.FOUNDATION -> if (y <= base) Stage.FOOTINGS else if (y > piece.roofBase) Stage.ROOF else Stage.WALLS
            // Planks at or under the floor are the floor; seats and tables inside are fitting out
            M.PLANKS -> if (y < base) Stage.FOOTINGS else if (y > piece.roofBase) Stage.ROOF else Stage.WALLS
            M.PLANK_STAIRS, M.PLANK_SLAB -> if (y < base) Stage.FOOTINGS else if (y > piece.roofBase) Stage.ROOF else Stage.FITTING
            else -> when {
                y < base -> Stage.FOOTINGS
                y > piece.roofBase -> Stage.ROOF
                else -> Stage.WALLS
            }
        }
    }

    /** What the plan's block [b] is made of, before the style picks the real block (the game asks the real block). */
    fun stuff(b: B, piece: PieceInfo?): Stuff {
        // Trees are planted, not built
        if (piece?.kind == PieceKind.TREE) return Stuff.FREE
        return when (b.m) {
            M.AIR, M.GRASS, M.SOIL, M.FILL, M.FARMLAND, M.CROP, M.FLOWER, M.LEAVES, M.HAY, M.WATER, M.PUMPKIN, M.TORCH,
            M.CAMPFIRE, M.CANDLE, M.TRACK, M.PAVE_YARD -> Stuff.FREE
            M.WINDOW, M.GLASS, M.STAINED -> Stuff.GLASS
            M.BARS, M.LANTERN, M.SOUL_LANTERN, M.CHAIN, M.ANVIL, M.CAULDRON, M.BELL, M.HOOK, M.BLAST_FURNACE,
            M.SMITHING_TABLE, M.GOLD -> Stuff.IRON
            M.BED, M.CARPET, M.WOOL, M.BANNER -> Stuff.WOOL
            M.TIMBER, M.PLANKS, M.PLANK_STAIRS, M.PLANK_SLAB, M.SHUTTER, M.DOOR, M.FENCE, M.FENCE_GATE, M.TRAPDOOR,
            M.LADDER, M.LOG, M.CHEST, M.BARREL, M.BOOKSHELF, M.LECTERN, M.CRAFTING_TABLE, M.CARTOGRAPHY_TABLE,
            M.FLETCHING_TABLE, M.LOOM, M.COMPOSTER, M.CRATE, M.TARGET -> Stuff.TIMBER
            M.ROOF, M.ROOF_STAIRS, M.ROOF_SLAB -> if (b.v >= 2) Stuff.TIMBER else Stuff.STONE
            else -> Stuff.STONE
        }
    }
}
