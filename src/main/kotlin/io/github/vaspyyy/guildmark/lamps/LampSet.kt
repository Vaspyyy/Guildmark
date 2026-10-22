package io.github.vaspyyy.guildmark.lamps

/**
 * What a [LampTown] is made of: a town's lamps, and the lamplighters' rounds among them (each round's lamps numbered by their
 * place in [lamps]). A capital's is its [LampPlan], worked out from the city's plan; a village's is its [VillageLampPlan], from the
 * posts its census found. Either way the lamps and the rounds are worked out from the same lamps alone, so they never change.
 */
interface LampSet {
    /** Every lamp, numbered from 0 in this order. */
    val lamps: List<LampPlan.Lamp>

    /** The lamplighters' rounds among the street lamps (those with [LampPlan.Lamp.city]). */
    val rounds: List<LampPlan.Round>
}
