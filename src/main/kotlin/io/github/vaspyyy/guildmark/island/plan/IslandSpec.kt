package io.github.vaspyyy.guildmark.island.plan

/** The sea an island stands in, from the ocean it was found in: what grows on it, and what its beaches are made of. */
enum class Climate {
    /** Warm seas: white sand, palms, coral on the reefs. */
    WARM,
    LUKEWARM,
    TEMPERATE,

    /** Cold seas: shingle beaches, pines, moss and heather. */
    COLD,

    /** Frozen seas: the snow and ice settle on it as the world finishes the chunk. */
    FROZEN,
}

/** What an island's cliffs are made of: the bands of stone that show wherever the sea has cut into it. */
enum class Rock(val bands: List<String>) {
    GRANITE(listOf("stone", "granite", "stone", "andesite", "stone", "granite", "diorite", "stone")),
    LIMESTONE(listOf("calcite", "calcite", "diorite", "calcite", "stone", "calcite", "tuff")),
    BASALT(listOf("smooth_basalt", "basalt", "tuff", "smooth_basalt", "deepslate", "basalt", "tuff")),
    SLATE(listOf("deepslate", "tuff", "andesite", "deepslate", "stone", "tuff", "andesite")),
    SANDSTONE(listOf("sandstone", "smooth_sandstone", "sandstone", "sandstone", "terracotta", "smooth_sandstone")),
}

/** What's on an island beyond its own wild land, if anything. */
enum class IslandKind {
    /** Wild land: beaches, cliffs, woods and meadows, and nothing else. */
    ISLE,

    /** A tall light on a rock, its keeper's cottage at its foot. */
    LIGHTHOUSE,

    /** A reef with a ship broken on it. */
    WRECK,

    /** A smugglers' den in a sea cave at the back of a cove. */
    COVE,

    /** A drowned temple to the thing in the deep, half under the sea. */
    SHRINE,

    /** Sea stacks thick with nesting gulls. */
    STACKS,

    /** A fishing hamlet round a sheltered bay. */
    HAVEN,
}

/** The lie of an island's land. */
enum class Form {
    /** One body of land, its coast cut by bays and headlands. */
    BLOB,

    /** Long and narrow, with a spine of hills along it. */
    RIDGE,

    /** Two bodies of land joined by a bar of sand. */
    TWIN,

    /** A ring of low sandy land round a shallow lagoon, open to the sea on one side (warm seas only). */
    ATOLL,

    /** Pillars of rock standing out of the sea, one or two joined by an arch. */
    STACKS,

    /** Mostly shallows over rock, a few rocks breaking the surface. */
    REEF,

    /** A body of land with a deep, cliff-walled cove cut into one side and a sea cave at its back. */
    COVE,

    /** A body of land with a wide sheltered bay on one side, beached for boats. */
    BAY,
}

/**
 * Everything an island is drawn from: kept with its structure piece, so every chunk draws the same island. All the
 * rest (which way it lies, where the wind comes from, its cliffs and coves) is worked out from [seed]. [radius] is how
 * far its land reaches along its length, in blocks; [sea] the height of the sea's surface (the first block of air
 * over the water); [name] picks its name (see IslandNames).
 */
data class IslandSpec(
    val seed: Long,
    val cx: Int,
    val cz: Int,
    val sea: Int,
    val kind: IslandKind,
    val climate: Climate,
    val form: Form,
    val rock: Rock,
    val radius: Int,
    val name: Int,
    val version: Int = VERSION,
) {
    companion object {
        /** Bumped whenever an island's drawing changes, so a half-drawn island saved earlier is finished the old way. */
        const val VERSION = 1

        /**
         * The island a start at [cx], [cz] in a sea of [climate] gets: its kind, its lie and its size, all from [seed].
         * [kind] forces the kind (for commands); otherwise wild land is the commonest.
         */
        fun roll(seed: Long, cx: Int, cz: Int, sea: Int, climate: Climate, kind: IslandKind? = null): IslandSpec {
            val dice = Dice(seed)
            val k = kind ?: pickKind(dice, climate)
            val form = when (k) {
                IslandKind.ISLE -> pickForm(dice, climate)
                IslandKind.LIGHTHOUSE -> Form.BLOB
                IslandKind.WRECK -> Form.REEF
                IslandKind.COVE -> Form.COVE
                IslandKind.SHRINE -> if (dice.chance(0.5)) Form.TWIN else Form.BLOB
                IslandKind.STACKS -> Form.STACKS
                IslandKind.HAVEN -> Form.BAY
            }
            val radius = when (k) {
                IslandKind.ISLE -> {
                    val r = dice.next()
                    when {
                        form == Form.ATOLL -> dice.int(30, 46)
                        r < 0.35 -> dice.int(12, 20)
                        r < 0.80 -> dice.int(22, 34)
                        else -> dice.int(36, 50)
                    }
                }
                IslandKind.LIGHTHOUSE -> dice.int(11, 16)
                IslandKind.WRECK -> dice.int(18, 26)
                IslandKind.COVE -> dice.int(28, 38)
                IslandKind.SHRINE -> dice.int(22, 30)
                IslandKind.STACKS -> dice.int(16, 24)
                IslandKind.HAVEN -> dice.int(30, 42)
            }
            return IslandSpec(seed, cx, cz, sea, k, climate, form, pickRock(dice, climate), radius, dice.int(1 shl 20))
        }

        private fun pickKind(dice: Dice, climate: Climate): IslandKind {
            val weights = linkedMapOf(
                IslandKind.ISLE to 50.0, IslandKind.LIGHTHOUSE to 8.0, IslandKind.WRECK to 8.0, IslandKind.COVE to 7.0,
                IslandKind.SHRINE to 6.0, IslandKind.STACKS to 9.0, IslandKind.HAVEN to if (climate == Climate.FROZEN) 0.0 else 8.0,
            )
            return weighted(dice, weights)
        }

        private fun pickForm(dice: Dice, climate: Climate): Form {
            val warm = climate == Climate.WARM || climate == Climate.LUKEWARM
            val weights = linkedMapOf(Form.BLOB to 45.0, Form.RIDGE to 20.0, Form.TWIN to 15.0, Form.ATOLL to if (warm) 22.0 else 0.0)
            return weighted(dice, weights)
        }

        private fun pickRock(dice: Dice, climate: Climate): Rock {
            val weights = when (climate) {
                Climate.WARM -> linkedMapOf(Rock.SANDSTONE to 35.0, Rock.LIMESTONE to 30.0, Rock.BASALT to 20.0, Rock.GRANITE to 15.0)
                Climate.LUKEWARM -> linkedMapOf(Rock.LIMESTONE to 35.0, Rock.GRANITE to 30.0, Rock.SANDSTONE to 20.0, Rock.BASALT to 15.0)
                Climate.TEMPERATE -> linkedMapOf(Rock.GRANITE to 35.0, Rock.LIMESTONE to 30.0, Rock.SLATE to 20.0, Rock.BASALT to 15.0)
                Climate.COLD -> linkedMapOf(Rock.SLATE to 45.0, Rock.GRANITE to 30.0, Rock.BASALT to 25.0)
                Climate.FROZEN -> linkedMapOf(Rock.SLATE to 50.0, Rock.BASALT to 30.0, Rock.GRANITE to 20.0)
            }
            return weighted(dice, weights)
        }

        private fun <T> weighted(dice: Dice, weights: Map<T, Double>): T {
            var r = dice.next() * weights.values.sum()
            for ((k, w) in weights) {
                if (r < w) return k
                r -= w
            }
            return weights.keys.first { weights.getValue(it) > 0 }
        }
    }
}
