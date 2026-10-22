package io.github.vaspyyy.guildmark.tales

import net.minecraft.server.level.ServerLevel

/**
 * What reaches a tale from the systems it listens to (living-world/stories/design.md 10.0 and 10.1): [tell] hands an event to the
 * open tale it concerns, and [listening] says whether any open tale would hear one, so a hot hook (every kill, every deal) can skip
 * building the event when none would. Which events a step hears is its `hears`: empty means every event of its capital, which is
 * every kind but the four that name no capital ([ALONE]); those reach a step only if it names them.
 */
internal object TaleEvents {
    /** The kinds that name no capital, so no tale is theirs by place: a step hears them only by name. */
    private val ALONE = setOf(Event.Kind.LAIR, Event.Kind.KILL, Event.Kind.KINDLED, Event.Kind.LANTERNS)

    /** Whether the step [step] (null: none) hears events of [kind]. */
    fun listens(step: Step?, kind: Event.Kind): Boolean {
        if (step == null || kind == Event.Kind.BACK) return false
        return if (step.hears.isEmpty()) kind !in ALONE else kind in step.hears
    }

    /**
     * Tells [e] to the tale it concerns: the one it names (a contract's chain, an escort's, a script's), else the open tale of the
     * capital it came from, else the tale that has the resident it is about in its cast, else (a kill, a lair, a fire, the lanterns)
     * every open tale that names the kind. A template hears it in `heard`, guarded; the clock set back is not told (the engine moves
     * the days itself, 2.3).
     */
    fun tell(level: ServerLevel, e: Event) {
        if (!Tales.live || e.kind == Event.Kind.BACK) return
        val data = TaleData.get(level)
        for (t in targets(data, e)) {
            val template = Templates.find(t.template) ?: continue
            // An event that names its tale was made for it; one that comes from the capital is heard by the steps that listen
            if (e.tale < 0 && !listens(template.steps.getOrNull(t.step), e.kind)) continue
            val c = TaleEngine.ctx(level, t.capital) ?: continue
            TaleSteps.hear(c, t, template, e)
        }
    }

    /** Whether any open tale would hear an event of [kind] now (not for one that names its tale: that is the sender's to know). */
    fun listening(level: ServerLevel, kind: Event.Kind): Boolean {
        if (!Tales.live) return false
        for (t in TaleEngine.openTales(level)) {
            val template = Templates.find(t.template) ?: continue
            if (listens(template.steps.getOrNull(t.step), kind)) return true
        }
        return false
    }

    private fun targets(data: TaleData, e: Event): List<Tale> {
        if (e.tale >= 0) return listOfNotNull(data[e.tale]?.takeIf { it.open })
        return when (e) {
            is Event.Day -> listOfNotNull(data.openIn(e.capital))
            is Event.Wagon -> listOfNotNull(data.openIn(e.capital))
            is Event.Works -> listOfNotNull(data.openIn(e.view.capital))
            is Event.Household -> listOfNotNull(data.openIn(e.household.capital))
            is Event.Died -> listOfNotNull(data.openIn(e.resident.capital.asLong()))
            is Event.Captive -> listOfNotNull(data.castIn(e.resident))
            is Event.Bought -> listOfNotNull(data.castIn(e.keeper))
            else -> data.all().filter { it.open }.sortedBy { it.id }
        }
    }
}
