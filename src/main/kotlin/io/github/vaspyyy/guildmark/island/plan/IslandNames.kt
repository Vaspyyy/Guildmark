package io.github.vaspyyy.guildmark.island.plan

/**
 * Names for islands, from an island's own name roll ([IslandSpec.name]), what it is, its lie and its sea, so the same
 * island always has the same name and nothing has to be saved with it. Each kind has its own words: "Harrow Light"
 * for a lighthouse, "the Gull Stacks" and "the Shrieking Needles" for a rookery, "Coffin Reef" for a wreck, "Saltwick"
 * for a fishing haven; a warm sea's isle may be "Conch Key", a cold one's "Sealholm".
 */
object IslandNames {
    private class Pool(
        /** What it is: "Isle", "Reef". */
        val nouns: List<String>,
        /** What it's like: "Lonely", "Hungry". */
        val adjectives: List<String>,
        /** The front and back of a one-word name: "Gull" and "holm". */
        val roots: List<String>,
        val tails: List<String>,
    )

    /** Old names an island may be "of", or someone's: the Isle of Maren, Widow Agna's Light. */
    private val OLD_NAMES = listOf(
        "Maren", "Saint Corrow", "Haldis", "Queen Ebba", "the Drowned Bride", "Captain Vell", "Old Teague", "Aldwin",
        "Saint Elowen", "the Seven Sisters", "Morrow", "Brannagh", "the Grey Abbot", "Ysmay", "Corvin", "Old Mother Salt",
        "the Last Fleet", "Gwenna", "Harald Oarsbane", "Saint Piran's Daughter", "the Lantern Queen", "Tobrin", "Aune",
    )

    /** Roots any island of a warm or cold sea may take, over its kind's own. */
    private val WARM_ROOTS = listOf("Coral", "Conch", "Pearl", "Turtle", "Palm", "Sun", "Amber", "Parrot", "Ray", "Cowrie")
    private val COLD_ROOTS = listOf("Seal", "Skua", "Auk", "Rime", "Frost", "Storm", "Whale", "Sleet", "Grey", "Ice")

    private val POOLS: Map<IslandKind, Pool> = mapOf(
        IslandKind.ISLE to Pool(
            listOf("Isle", "Holm", "Skerry", "Islet", "Rock"),
            listOf("Lonely", "Green", "Windward", "Leeward", "Little", "Long", "Quiet", "Bright", "Far", "Forgotten", "Sleeping", "Lost", "Hidden", "Gentle"),
            listOf("Gull", "Tern", "Gannet", "Kelp", "Salt", "Thrift", "Heather", "Bracken", "Thorn", "Rowan", "Broom", "Wrack", "Shell", "Drift", "Otter", "Curlew", "Plover", "Sorrel"),
            listOf("holm", "ey", "ay", "skerry", "sey"),
        ),
        IslandKind.LIGHTHOUSE to Pool(
            listOf("Light", "Lamp", "Beacon"),
            listOf("Mercy", "Faithful", "Watchful", "Lonely", "Steadfast", "Last", "Widow's", "Keeper's", "Patient", "Wakeful"),
            listOf("Harrow", "Wick", "Gannet", "Saltmark", "Fairway", "Warden", "Lantern", "Hale", "Morrow", "Kestle", "Tarrow", "Brack", "Hope", "Seaward", "Guiding"),
            listOf("head", "ness", "point", "holm", "rock", "stone"),
        ),
        IslandKind.WRECK to Pool(
            listOf("Reef", "Shoals", "Rocks", "Teeth", "Ledges", "Breakers"),
            listOf("Grinding", "Hungry", "Drowning", "Wrecking", "Cruel", "Black", "Weeping", "Broken", "Sorrowing", "Lurking", "Patient"),
            listOf("Coffin", "Widow", "Rib", "Bone", "Shiver", "Keel", "Splinter", "Grave", "Gallows", "Mourning", "Hulk", "Rack"),
            listOf("reef", "shoal", "scar"),
        ),
        IslandKind.COVE to Pool(
            listOf("Cove", "Inlet", "Hole", "Creek"),
            listOf("Hidden", "Quiet", "Secret", "Dark", "Silent", "Crooked", "Hushed", "Shadowed", "Narrow", "Moonless"),
            listOf("Black", "Lantern", "Hush", "Brandy", "Owl", "Shadow", "Smoke", "Fox", "Cutter", "Moon", "Whisper", "Velvet", "Tallow", "Rook", "Gin"),
            listOf("water", "cove", "hole", "creek", "mouth", "gully"),
        ),
        IslandKind.SHRINE to Pool(
            listOf("Isle", "Rock", "Holm", "Strand"),
            listOf("Drowned", "Weeping", "Whispering", "Sunken", "Hollow", "Pale", "Mournful", "Murmuring", "Bell-haunted", "Silent", "Brine-dark"),
            listOf("Tide", "Brine", "Deep", "Mourn", "Hush", "Pale", "Bell", "Choir", "Ink", "Drown", "Salt", "Murmur"),
            listOf("call", "mourn", "hallow", "fast", "holm", "song", "keep"),
        ),
        IslandKind.STACKS to Pool(
            listOf("Stacks", "Needles", "Pillars", "Chimneys", "Spires", "Sentinels", "Brothers", "Fingers"),
            listOf("White", "Shrieking", "Screaming", "Crying", "Clamouring", "Old", "Grey", "Broken", "Weathered", "Squalling", "Restless"),
            listOf("Gull", "White", "Kittiwake", "Salt", "Grey", "Tern", "Gannet", "Storm", "Feather", "Guillemot", "Fulmar"),
            listOf("stacks", "needles", "pillars", "spires"),
        ),
        IslandKind.HAVEN to Pool(
            listOf("Haven", "Harbour", "Landing", "Strand"),
            listOf("Snug", "Quiet", "Little", "Kindly", "Safe", "Sheltered", "Old", "Fair"),
            listOf("Herring", "Cod", "Net", "Creel", "Salt", "Kelp", "Mussel", "Shell", "Tide", "Smoke", "Gull", "Anchor", "Brine", "Oar", "Skiff", "Weir"),
            listOf("wick", "by", "mouth", "haven", "hythe", "stead", "port", "sands", "ford"),
        ),
    )

    /** The name of [spec]'s island. */
    fun of(spec: IslandSpec): String = name(spec.name, spec.kind, spec.form, spec.climate)

    /** The name an island of [kind], lying as [form] in a sea of [climate], gets for name roll [roll]. */
    fun name(roll: Int, kind: IslandKind, form: Form, climate: Climate): String {
        var h = mix(roll.toLong(), kind.ordinal, form.ordinal, climate.ordinal)
        fun next(bound: Int): Int {
            h = h * 6364136223846793005L + 1442695040888963407L
            return Math.floorMod((h ushr 33).toInt(), bound)
        }
        fun pick(list: List<String>): String = list[next(list.size)]
        val pool = POOLS.getValue(kind)
        val warm = climate == Climate.WARM || climate == Climate.LUKEWARM
        val cold = climate == Climate.COLD || climate == Climate.FROZEN
        /** A root from the kind's own words, or now and then from the sea's. */
        fun root(): String = when {
            warm && next(3) == 0 -> pick(WARM_ROOTS)
            cold && next(3) == 0 -> pick(COLD_ROOTS)
            else -> pick(pool.roots)
        }
        fun compound(tails: List<String> = pool.tails): String {
            val front = root()
            var tail = pick(tails)
            // No doubled letters at the seam ("Gullllholm"), no echo ("Reef" + "reef")
            if (front.last().lowercaseChar() == tail.first() || front.lowercase().endsWith(tail)) tail = tails[(tails.indexOf(tail) + 1) % tails.size]
            return front + tail
        }
        fun old(): String = pick(OLD_NAMES)
        /** Someone's, with the "the" of "the Drowned Bride" kept lower case after the possessive. */
        fun owned(noun: String): String {
            val who = old()
            return if (who.endsWith("s")) "$who' $noun" else "$who's $noun"
        }
        val roll10 = next(10)
        val name = when (kind) {
            IslandKind.ISLE -> when (form) {
                Form.ATOLL -> when {
                    roll10 < 4 -> "${root()} Atoll"
                    roll10 < 7 -> "The ${pick(pool.adjectives)} Ring"
                    else -> "The Lagoon of ${old()}"
                }
                Form.TWIN -> when {
                    roll10 < 4 -> "The ${root()} Sisters"
                    roll10 < 7 -> "The ${pick(pool.adjectives)} Twins"
                    else -> "The Twin Isles of ${old()}"
                }
                Form.RIDGE -> when {
                    roll10 < 4 -> compound(listOf("back", "spine", "ridge", "rigg"))
                    roll10 < 7 -> "The Long ${pick(listOf("Isle", "Holm", "Spine", "Back"))}"
                    else -> "${root()} Ridge"
                }
                else -> when {
                    roll10 < 4 -> compound()
                    roll10 < 6 -> "${root()} ${if (warm && next(2) == 0) "Key" else pick(pool.nouns)}"
                    roll10 < 8 -> "The ${pick(pool.adjectives)} ${pick(pool.nouns)}"
                    else -> if (next(2) == 0) "The Isle of ${old()}" else owned("Isle")
                }
            }
            IslandKind.LIGHTHOUSE -> when {
                roll10 < 4 -> "${root()} ${pick(pool.nouns)}"
                roll10 < 6 -> "${compound()} Light"
                roll10 < 8 -> "The ${pick(pool.adjectives)} Light"
                else -> owned(if (next(2) == 0) "Light" else "Lamp")
            }
            IslandKind.WRECK -> when {
                roll10 < 5 -> "${root()} ${pick(pool.nouns)}"
                roll10 < 8 -> "The ${pick(pool.adjectives)} ${pick(pool.nouns)}"
                roll10 < 9 -> compound()
                else -> "The Teeth of ${old()}"
            }
            IslandKind.COVE -> when {
                roll10 < 4 -> "${root()} Cove"
                roll10 < 6 -> "The ${pick(pool.adjectives)} ${pick(pool.nouns)}"
                roll10 < 8 -> compound()
                else -> owned("Cove")
            }
            IslandKind.SHRINE -> when {
                roll10 < 4 -> "The ${pick(pool.adjectives)} ${pick(pool.nouns)}"
                roll10 < 7 -> "The Isle of ${pick(listOf("Whispers", "Bells", "Sorrows", "Tides", "the Drowned", "the Deep", "Murmurs", "the Bell"))}"
                else -> compound()
            }
            IslandKind.STACKS -> when {
                roll10 < 4 -> "The ${root()} ${pick(pool.nouns)}"
                roll10 < 7 -> "The ${pick(pool.adjectives)} ${pick(pool.nouns)}"
                roll10 < 9 -> "The ${pick(pool.nouns)} of ${old()}"
                else -> owned(pick(pool.nouns))
            }
            IslandKind.HAVEN -> when {
                roll10 < 5 -> compound()
                roll10 < 8 -> "${root()} ${pick(pool.nouns)}"
                else -> "${pick(pool.adjectives)} ${pick(pool.nouns)}"
            }
        }
        // "the Drowned Bride's Light" opens a sentence as "The Drowned Bride's Light"
        return name.replaceFirstChar { it.uppercaseChar() }
    }

    private fun mix(seed: Long, a: Int, b: Int, c: Int): Long {
        var h = seed xor (a * -0x61c8864680b583ebL) xor (b * 0x5851F42D4C957F2DL) xor (c * 0x2545F4914F6CDD1DL) xor 0x15A1DL
        h = (h xor (h ushr 31)) * -0x4b6d499041670d8dL
        h = (h xor (h ushr 29)) * -0x6b2fb644ecceee15L
        return h xor (h ushr 32)
    }
}
