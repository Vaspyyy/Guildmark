package io.github.vaspyyy.guildmark.tales

/**
 * The twelve tales and the ones built (living-world/stories/design.md 8.0 and 14.2). [IDS] is the twelve ids in the order of the
 * design's table, so `tale seeds` can name the ones not built yet; [ALL] is the templates in the draw, one line for each.
 * A template is switched on by the one line here that its last commit uncomments, so a half-built tale is never in the draw.
 */
object Templates {
    /** The twelve ids of 8.0: note, chair, wagon, child, fence, lute, moot, cart, lantern, informer, locket, brother. */
    val IDS: List<String> = listOf(
        "note", "chair", "wagon", "child", "fence", "lute", "moot", "cart", "lantern", "informer", "locket", "brother",
    )

    /**
     * Every template built and in the draw, in the order of [IDS]. Each line is a template object in `tales/templates/`; the
     * part that builds it (the group after the id) uncomments it. None is built yet.
     */
    val ALL: List<Template> = listOf(
        io.github.vaspyyy.guildmark.tales.templates.Note, // 1  note      size 2  T1 (the pilot)
        // Chair,     // 2  chair     size 2  T4
        // Wagon,     // 3  wagon     size 3  T3
        // Child,     // 4  child     size 2  T2
        io.github.vaspyyy.guildmark.tales.templates.Fence, // 5  fence     size 1  T1
        // Lute,      // 6  lute      size 1  T1
        // Moot,      // 7  moot      size 2  T4
        // Cart,      // 8  cart      size 2  T2
        // Lantern,   // 9  lantern   size 1  T2
        // Informer,  // 10 informer  size 3  T3
        // Locket,    // 11 locket    size 2  T1
        // Brother,   // 12 brother   size 3  T5
    )

    /** The built template with this id, or null (not one of the twelve, or not built yet). */
    fun byId(id: String): Template? = ALL.firstOrNull { it.id == id }

    /** The probe tale (`tales/templates/Probe.kt`): never in the draw nor in `tale seeds`; only `tale start probe` makes it. */
    val PROBE: Template get() = io.github.vaspyyy.guildmark.tales.templates.Probe

    /** The template a tale is stepped by: a built one, or the probe. Null for an id this build does not know (a later build rolled back, 11.4). */
    fun find(id: String): Template? = byId(id) ?: PROBE.takeIf { it.id == id }
}
