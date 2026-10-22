package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Cast
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Planned
import io.github.vaspyyy.guildmark.tales.Seeding
import io.github.vaspyyy.guildmark.tales.Step
import io.github.vaspyyy.guildmark.tales.Gone
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.Template
import io.github.vaspyyy.guildmark.tales.Verb
import io.github.vaspyyy.guildmark.tales.View
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import net.minecraft.core.BlockPos
import java.util.UUID

/**
 * The probe tale (living-world/stories/design.md 14.5): a bench made of nothing, so that the engine, the telling, the talk, the
 * following and the pay can be tried before any real tale exists, and kept afterwards as the engine's regression test. One step
 * of the verbs a first tale needs, in turn: ASK (any grown person with a home asks), CHOOSE (two replies), GIVE (3 Marks), SCENE
 * (S1 and then S7, once the telling has them), WAIT (a day) and DO (a plain note of no value); and four endings that do nothing
 * but leave a `Why`, a chronicle line and, once pay and memory have them, the pay, the reason and the telling. It is not in the
 * draw and not in `tale seeds`: only `tale start probe` makes it, and only when tales are ready ([Templates.PROBE]). Its cast is the
 * asker, who is essential, and a helper, who is standing: killing the one shows the tale ending at W, killing the other a stand-in.
 *
 * Nothing here needs a system that may be off, so [needs] is always true. What the other parts will fill in is said where it
 * goes: the scene (B), the talk (C) and the note of the DO step (the talk and pay, 4.6).
 */
object Probe : Template {
    override val id: String = "probe"
    override val size: Int = 1

    /** The days are D1, D2, D3, D3, D4 and D6: the longest route is six days and no step lasts more than two. */
    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 1),
        Step(Verb.CHOOSE, by = 2, real = true),
        Step(Verb.GIVE, by = 3, real = true, hears = setOf(Event.Kind.GAVE)),
        Step(Verb.SCENE, by = 3),
        Step(Verb.WAIT, by = 4),
        Step(Verb.DO, by = 6, real = true, hears = setOf(Event.Kind.CONTRACT)),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "kept"),
        Ending(Ending.B, "mostly"),
        Ending(Ending.C, "barely"),
        Ending(Ending.W, "lapsed"),
    )

    /** `n[0]` the reply chosen (0 none yet, 1 or 2), `n[1]` the Marks asked for, `n[2]` the Marks given so far. */
    override val numbers: List<String> = listOf("reply", "sum", "given")

    /** The helper is any one of their trade: if they die or go, the engine casts another (5.6) and says so. */
    override val standing: Set<String> = setOf("helper")

    override fun needs(): Boolean = true

    override fun seed(c: Seeding): Planned? {
        val pool = TaleCast.pool(c.level, c.here.capital.center)
        if (pool.isEmpty()) return c.none("no grown person with a home is free to ask")
        val asker = pool[c.random.nextInt(pool.size)]
        // The helper is another grown person, of a trade somebody else has too if there is one, so that a stand-in can be found
        val others = pool.filter { it.id != asker.id }
        val shared = others.filter { r -> others.count { it.role == r.role } > 1 }.ifEmpty { others }
        val helper = if (shared.isEmpty()) null else shared[c.random.nextInt(shared.size)]
        return Planned(
            cast = listOfNotNull(Cast("asker", asker.id, asker.name), helper?.let { Cast("helper", it.id, it.name) }),
            why = Why("probe.start", listOf(asker.name)),
            step = 0,
            n = listOf(0, SUM, 0),
        )
    }

    /** Nothing is prepared: the probe has no scene to queue and no note of its own to hand yet. */
    override fun enter(c: Ctx, t: Tale) {}

    override fun heard(c: Ctx, t: Tale, e: Event) {
        when (e) {
            is Event.Gave -> if (t.step == AT_GIVE) {
                t.partFor(e.player.uuid)?.let { it.did = it.did or Part.GAVE }
                t.n[2] += e.marks
                if (t.n[2] >= t.n[1]) c.next(t)
            }
            is Event.Contract -> if (t.step == AT_DO && !e.expired) {
                t.partFor(e.player.uuid)?.let { it.did = it.did or Part.HANDED or Part.DECIDED }
                c.end(t, Ending.A)
            }
            else -> {}
        }
    }

    /** The probe's scenes are the telling's (B): S1 then S7 once it has them. */
    override fun scene(c: Ctx, t: Tale): Stage? = null

    /** The probe's talk is the talk's (C). */
    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    /** The world's way: every step moves on (the reply defaults to the first); the last one ends the tale as far as the parts got. */
    override fun settle(c: Ctx, t: Tale) {
        c.why(t, "probe.settled", t.step.toString())
        when (t.step) {
            AT_CHOOSE -> if (t.n[0] == 0) t.n[0] = 1
            AT_DO -> {
                c.end(t, endingOf(t))
                return
            }
        }
        c.next(t)
    }

    /** The asker is essential: if they go the tale is over (the engine has said why). The helper, with no stand-in to be found, is only missed. */
    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        if (slot == "helper") {
            c.why(t, "probe.helper_gone", t.cast(slot)?.name.orEmpty())
        } else {
            c.end(t, Ending.W)
        }
    }

    /** Nothing but what the engine leaves (a `Why`, the chronicle line); the rest is pay and memory's. */
    override fun end(c: Ctx, t: Tale, ending: String) {}

    /** The probe has no door or camp of its own, so its row points at its town's heart: the pin, the compass mark and the arrow can be tried on it. */
    override fun view(t: Tale, player: UUID): View = View(
        title = TaleKeys.title(id),
        status = TaleKeys.status(id, t.step),
        target = BlockPos.of(t.capital),
    )

    /** Where the parts got: A for a note handed in, B for a gift or a choice, C for a part taken up, W for nothing. */
    private fun endingOf(t: Tale): String = when {
        t.parts.any { it.did(Part.HANDED) } -> Ending.A
        t.parts.any { it.did(Part.GAVE) || it.did(Part.CHOSE) } -> Ending.B
        t.parts.any { it.status == Part.Status.IN || it.status == Part.Status.DONE } -> Ending.C
        else -> Ending.W
    }

    private const val SUM = 3
    private const val AT_CHOOSE = 1
    private const val AT_GIVE = 2
    private const val AT_DO = 5
}
