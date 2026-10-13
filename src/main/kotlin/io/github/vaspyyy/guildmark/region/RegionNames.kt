package io.github.vaspyyy.guildmark.region

/**
 * Names for the regions, from the seed, the cell and the region's place in it, so the same land always
 * has the same name and nothing has to be saved. Each kind of land has its own words: "the Drowned Fens"
 * and "Murkmere" for a marsh, "the Cinder Wastes" for the ashen highlands, "Rimecrown" for the peaks.
 */
object RegionNames {
    private class Pool(
        /** What the land is called: "Fens", "Highlands". */
        val nouns: List<String>,
        /** What it's like: "Drowned", "Ashen". */
        val adjectives: List<String>,
        /** The front and back of a one-word name: "Murk" and "mere". */
        val roots: List<String>,
        val tails: List<String>,
    )

    /** Old names a land may be "of": the Fens of Varn. */
    private val OLD_NAMES = listOf(
        "Varn", "Old Harrow", "Saint Ivo", "Maldrec", "Ostrand", "Kell", "Aldric", "Ysolde", "Grimwald", "Hesketh",
        "Orrin", "Tamsin", "Bryn", "the Lost King", "the Seven Bells", "the First Guild", "Arden", "Morwen", "Caddoc",
        "Elsbeth", "Hollin", "the Old Road", "Saint Wyn", "Corvan", "Thane Ulric", "the Long Watch",
    )

    private val POOLS: Map<RegionKind, Pool> = mapOf(
        RegionKind.HEARTLAND to Pool(
            listOf("Vale", "Downs", "Fields", "Meads", "Weald", "Dales", "Lowlands", "Shires", "Greens", "Leas", "Reach", "Wolds"),
            listOf("Golden", "Gentle", "Green", "Quiet", "Amber", "Sunlit", "Rolling", "Bright", "Kindly", "Merry", "Honeyed", "Hearthward"),
            listOf("Amber", "Barley", "Wheat", "Bram", "Hay", "Lark", "Meadow", "Clover", "Fallow", "Hearth", "Mill", "Rye", "Sheaf", "Thistle", "Yarrow", "Elder", "Apple", "Dove"),
            listOf("vale", "down", "mead", "dale", "field", "ley", "wold", "combe", "hurst", "stead"),
        ),
        RegionKind.WILDWOOD to Pool(
            listOf("Wood", "Woods", "Weald", "Holt", "Forest", "Thickets", "Boughs", "Pines", "Deepwood", "Glades"),
            listOf("Whispering", "Tangled", "Hushed", "Elder", "Shrouded", "Old", "Dim", "Deep", "Black", "Pale", "Ancient", "Brooding", "Silent", "Grey"),
            listOf("Black", "Wolf", "Thorn", "Raven", "Elder", "Yew", "Owl", "Moss", "Shade", "Bramble", "Hart", "Gloam", "Briar", "Wych", "Fern", "Nettle", "Hollow"),
            listOf("wood", "holt", "shaw", "hurst", "grove", "weald", "glade", "brake", "den"),
        ),
        RegionKind.MARSH to Pool(
            listOf("Fens", "Marshes", "Mire", "Bog", "Morass", "Sloughs", "Wetlands", "Moss", "Mere", "Quags", "Flats", "Carrs"),
            listOf("Drowned", "Weeping", "Rotting", "Sunken", "Grey", "Stagnant", "Fevered", "Murky", "Whispering", "Mourning", "Sodden", "Mouldering", "Festering", "Forsaken", "Lost"),
            listOf("Murk", "Mire", "Rot", "Gall", "Black", "Wych", "Sedge", "Reed", "Silt", "Bog", "Leech", "Hag", "Fester", "Dank", "Gloom", "Toad", "Gallow", "Mourn", "Sour", "Drown"),
            listOf("mere", "fen", "moss", "water", "slough", "marsh", "mire", "ditch", "pool", "bottom", "carr", "wash"),
        ),
        RegionKind.ASHEN to Pool(
            listOf("Highlands", "Wastes", "Barrens", "Uplands", "Badlands", "Reaches", "Scarps", "Mesas", "Embers", "Cinders"),
            listOf("Ashen", "Burnt", "Smouldering", "Scorched", "Cinder", "Charred", "Blackened", "Red", "Smoking", "Ember", "Withered", "Sunburnt"),
            listOf("Ash", "Cinder", "Ember", "Soot", "Char", "Brand", "Pyre", "Slag", "Flint", "Rust", "Scald", "Smoke", "Kiln", "Coal", "Blaze", "Scar"),
            listOf("fell", "moor", "reach", "scar", "ridge", "heath", "barrow", "waste", "rise", "spur", "shelf"),
        ),
        RegionKind.FROZEN to Pool(
            listOf("Peaks", "Wastes", "Snows", "Heights", "Fells", "Glaciers", "Icefields", "Expanse", "Reaches", "Spires"),
            listOf("Frozen", "Silent", "White", "Bitter", "Howling", "Pale", "Frostbitten", "Shivering", "Endless", "Rimebound", "Glittering", "Winter"),
            listOf("Frost", "Rime", "Ice", "Snow", "Hail", "Sleet", "Winter", "White", "Chill", "Shiver", "Bitter", "Hoar", "Storm", "Wolf"),
            listOf("fell", "peak", "spire", "crown", "horn", "fang", "tooth", "reach", "drift", "mark", "hold"),
        ),
        RegionKind.CRAGS to Pool(
            listOf("Crags", "Heights", "Tors", "Hills", "Fells", "Ridges", "Spires", "Uplands", "Scarps", "Highmoor"),
            listOf("Windswept", "Grey", "Broken", "Howling", "Stony", "Jagged", "Lonely", "Iron", "Stormy", "High", "Shattered", "Bleak"),
            listOf("Gale", "Storm", "Grey", "Iron", "Stone", "Hawk", "Eagle", "Crow", "Flint", "Thunder", "Wind", "Raven", "Cairn", "Slate", "Tor"),
            listOf("crag", "tor", "fell", "ridge", "peak", "scar", "spire", "edge", "head", "pike", "knoll"),
        ),
        RegionKind.DUNES to Pool(
            listOf("Sands", "Dunes", "Wastes", "Expanse", "Desert", "Barrens", "Drifts", "Flats"),
            listOf("Shifting", "Endless", "Golden", "Sunscorched", "Whispering", "Burning", "Silent", "Thirsting", "Bleached", "Singing", "Forgotten"),
            listOf("Sun", "Sand", "Dust", "Bone", "Salt", "Gold", "Glass", "Thirst", "Scarab", "Amber", "Ochre", "Sere"),
            listOf("reach", "waste", "drift", "sea", "hollow", "basin", "flats", "dune", "mark", "pan"),
        ),
        RegionKind.STEPPE to Pool(
            listOf("Steppe", "Plains", "Grasslands", "Wilds", "Expanse", "Reaches", "Range", "Veld"),
            listOf("Golden", "Wide", "Endless", "Windblown", "Sunlit", "Tawny", "Thundering", "Open", "Dry", "Amber"),
            listOf("Lion", "Thunder", "Tawny", "Wind", "Long", "Far", "Acacia", "Sun", "Hoof", "Grass", "Wild", "Amber", "Horn"),
            listOf("veld", "plain", "reach", "run", "range", "field", "march", "sweep", "lea"),
        ),
        RegionKind.JUNGLE to Pool(
            listOf("Jungle", "Wilds", "Tangle", "Canopy", "Rainwood", "Thickets", "Greenwood"),
            listOf("Emerald", "Verdant", "Steaming", "Tangled", "Lost", "Serpent", "Overgrown", "Humming", "Sweltering", "Choking", "Deep"),
            listOf("Vine", "Serpent", "Jade", "Emerald", "Tiger", "Ocelot", "Orchid", "Fern", "Monsoon", "Liana", "Parrot", "Moss"),
            listOf("wilds", "tangle", "wood", "reach", "green", "deep", "maze", "hollow", "basin"),
        ),
        RegionKind.SEA to Pool(
            listOf("Sea", "Deep", "Waters", "Gulf", "Sound", "Main", "Expanse", "Bay", "Straits"),
            listOf("Grey", "Restless", "Sunken", "Silver", "Endless", "Shining", "Wailing", "Stormy", "Quiet", "Drowned", "Glass"),
            listOf("Gull", "Salt", "Storm", "Pearl", "Kelp", "Tide", "Wave", "Whale", "Coral", "Brine", "Silver", "Wreck", "Gale"),
            listOf("water", "deep", "mere", "sound", "gulf", "reach", "main"),
        ),
        RegionKind.MUSHROOM to Pool(
            listOf("Isles", "Isle", "Shores", "Groves", "Meadows", "Caps"),
            listOf("Dreaming", "Strange", "Violet", "Whispering", "Hazy", "Drowsy", "Hidden", "Fey", "Blooming"),
            listOf("Spore", "Cap", "Moon", "Puff", "Fey", "Dream", "Gill", "Velvet", "Toad", "Bloom"),
            listOf("isle", "holm", "shore", "cap", "garden", "grove", "rest"),
        ),
        RegionKind.DEEP to Pool(
            listOf("Deep", "Halls", "Dark", "Underhalls", "Depths", "Abyss", "Vaults"),
            listOf("Silent", "Sculking", "Hushed", "Ancient", "Buried", "Lightless", "Forgotten", "Breathless"),
            listOf("Hush", "Sculk", "Gloom", "Echo", "Dread", "Still", "Night"),
            listOf("deep", "hall", "vault", "hollow", "pit"),
        ),
        RegionKind.CRYSTAL to Pool(
            listOf("Deeps", "Halls", "Hollows", "Caverns", "Grottoes", "Vaults", "Galleries", "Spires", "Gardens"),
            listOf("Glimmering", "Crystal", "Singing", "Shining", "Prismatic", "Glittering", "Ringing", "Starlit", "Amethyst", "Sapphire", "Rose"),
            listOf("Shard", "Glimmer", "Gleam", "Prism", "Star", "Spark", "Glint", "Shine", "Geode", "Quartz"),
            listOf("hollow", "deep", "hall", "vault", "grotto", "heart", "crown"),
        ),
    )

    /** The caves' flavours: lush, dripstone, sulfur and the rest, each named its own way. */
    private val CAVERNS = listOf(
        Pool(
            listOf("Hollows", "Grottoes", "Gardens", "Caverns", "Bowers"),
            listOf("Glowing", "Verdant", "Hanging", "Dripping", "Hidden", "Lantern", "Mossy", "Sunless"),
            listOf("Lantern", "Glow", "Moss", "Root", "Berry", "Vine"), listOf("hollow", "grotto", "bower", "root"),
        ),
        Pool(
            listOf("Halls", "Caverns", "Galleries", "Vaults", "Pillars"),
            listOf("Weeping", "Dripping", "Fanged", "Echoing", "Hollow", "Endless", "Pillared"),
            listOf("Drip", "Fang", "Spire", "Echo", "Stone", "Tooth"), listOf("hall", "vault", "deep", "fang"),
        ),
        Pool(
            listOf("Galleries", "Pits", "Vents", "Caverns", "Furnaces"),
            listOf("Brimstone", "Sulfurous", "Yellow", "Choking", "Fuming", "Stinking", "Seething"),
            listOf("Brim", "Sulfur", "Reek", "Fume", "Smoke"), listOf("pit", "vent", "deep", "hole"),
        ),
        Pool(
            listOf("Caverns", "Hollows", "Halls", "Deeps"),
            listOf("Echoing", "Dark", "Lost", "Forgotten", "Winding", "Hollow"),
            listOf("Echo", "Dark", "Wind", "Bat", "Under"), listOf("hollow", "hall", "deep", "warren"),
        ),
    )

    /** The name of region [index] of cell ([cx], [cz]), a [kind] of land. */
    fun name(seed: Long, cx: Int, cz: Int, index: Int, kind: RegionKind): String =
        compose(POOLS[kind] ?: POOLS.getValue(RegionKind.HEARTLAND), mix(seed, cx, cz, index), kind.underground)

    /** The name of the caves of [flavour] (0 lush, 1 dripstone, 2 sulfur, 3 any other) under cell ([cx], [cz]). */
    fun cavern(seed: Long, cx: Int, cz: Int, flavour: Int): String =
        compose(CAVERNS[flavour.coerceIn(0, CAVERNS.size - 1)], mix(seed, cx, cz, 300 + flavour), true)

    private fun compose(pool: Pool, hash: Long, underground: Boolean): String {
        var h = hash
        fun next(bound: Int): Int {
            h = h * 6364136223846793005L + 1442695040888963407L
            return Math.floorMod((h ushr 33).toInt(), bound)
        }
        fun pick(list: List<String>): String = list[next(list.size)]
        fun compound(): String {
            val root = pick(pool.roots)
            var tail = pick(pool.tails)
            // No doubled letters at the seam ("Murkkmere") and no echo ("Mire" + "mire")
            if (root.last().lowercaseChar() == tail.first() || root.lowercase().endsWith(tail)) tail = pool.tails[(pool.tails.indexOf(tail) + 1) % pool.tails.size]
            return root + tail
        }
        val roll = next(10)
        return when {
            // Below ground the halls are mostly "the Something Halls"
            underground -> when {
                roll < 6 -> "The ${pick(pool.adjectives)} ${pick(pool.nouns)}"
                roll < 8 -> "${compound()} ${pick(pool.nouns)}"
                else -> "The ${pick(pool.nouns)} of ${pick(OLD_NAMES)}"
            }
            roll < 4 -> "The ${pick(pool.adjectives)} ${pick(pool.nouns)}"
            roll < 6 -> "${compound()} ${pick(pool.nouns)}"
            roll < 9 -> compound()
            else -> "The ${pick(pool.nouns)} of ${pick(OLD_NAMES)}"
        }
    }

    private fun mix(seed: Long, cx: Int, cz: Int, index: Int): Long {
        var h = seed xor (cx * -0x61c8864680b583ebL) xor (cz * 0x5851F42D4C957F2DL) xor (index * 0x2545F4914F6CDD1DL)
        h = (h xor (h ushr 31)) * -0x4b6d499041670d8dL
        h = (h xor (h ushr 29)) * -0x6b2fb644ecceee15L
        return h xor (h ushr 32)
    }
}
