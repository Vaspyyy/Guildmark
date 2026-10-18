// Written by art/scripts/living_anims.py (write_schedule) from its clips and art/scripts/living_props.py: run
// the script rather than editing this by hand.
package io.github.vaspyyy.guildmark.client.townsfolk

/**
 * What the Living World's clips show of their props, and of the goods someone carries, tick by tick (the previews
 * of art/scripts/living_anims.py show the same). [LivingProps] shows and hides the props and runs their tracks;
 * [LivingGoodsLayer] draws the goods.
 */
internal object LivingSchedule {
    /** On to the end of the clip, and held there. */
    const val END = Float.MAX_VALUE

    /** A prop shown from tick [from] up to [to]. */
    class Shown(val part: String, val from: Float, val to: Float)

    /**
     * Goods shown from tick [from] up to [to], [size] px at their largest: between the hands and [x] px further
     * forward (HANDS), at ([x], [y], [z]) in [part]'s own space (PART), at ([x], [y], [z]) in the clip's space
     * (GROUND: on the ground, on a counter), or thrown from there to ([tx], [ty], [tz]) in [toPart]'s space,
     * rising [arc] px on the way (TOSS).
     */
    class Goods(
        val from: Float, val to: Float, val how: How, val part: String, val x: Float, val y: Float, val z: Float,
        val toPart: String, val tx: Float, val ty: Float, val tz: Float, val arc: Float, val size: Float,
    )

    enum class How { HANDS, PART, GROUND, TOSS }

    /** A moment of a clip: what happens ([name]), on which tick, and where: ([x], [y], [z]) in the clip's space. */
    class Moment(val name: String, val tick: Float, val x: Float, val y: Float, val z: Float)

    /** The clips these are for. */
    val CLIPS: Set<String> = setOf("sit_down", "stand_up", "nod", "shrug", "point", "bow", "yawn", "stretch", "knock", "climb", "climb_top", "eat", "sit_eat", "wash", "pick_up", "put_down", "hand_over", "receive", "pay", "browse", "set_up", "sow", "carry_basket", "gather", "scythe", "grind", "chop", "pour_sack", "ring_bell", "announce", "draw_water", "carry_pole", "light_lamp", "snuff_lamp")

    /** Each prop's own part, and the part it hangs on. */
    val PARENT: Map<String, String> = mapOf(
        "loaf" to "right_arm",
        "apple" to "right_arm",
        "bowl" to "left_arm",
        "spoon" to "right_arm",
        "bucket" to "right_arm",
        "haul_rope_right" to "right_arm",
        "haul_rope_left" to "left_arm",
        "tail_rope_right" to "right_arm",
        "tail_rope_left" to "left_arm",
        "curb_rope" to "body",
        "well_rope" to "body",
        "well_bucket" to "body",
        "coin" to "right_arm",
        "seed_bag" to "body",
        "scythe" to "right_arm",
        "axe" to "right_arm",
        "handbell" to "right_arm",
        "scroll" to "left_arm",
        "lamp_pole" to "right_arm",
        "basket_hip" to "body",
        "sack_held" to "right_arm",
        "sack_empty" to "right_arm",
        "crank" to "right_arm",
    )

    /** By clip: the props it shows, and when. */
    val SHOWN: Map<String, List<Shown>> = mapOf(
        "eat" to listOf(
            Shown("loaf", 0f, END),
        ),
        "sit_eat" to listOf(
            Shown("bowl", 0f, END),
            Shown("spoon", 0f, END),
        ),
        "pay" to listOf(
            Shown("coin", 7f, 17f),
            Shown("coin_purse", 0f, END),
        ),
        "browse" to listOf(
            Shown("apple", 14f, 36f),
        ),
        "sow" to listOf(
            Shown("seed_bag", 0f, END),
        ),
        "carry_basket" to listOf(
            Shown("basket_hip", 0f, END),
        ),
        "gather" to listOf(
            Shown("basket_hip", 0f, END),
        ),
        "scythe" to listOf(
            Shown("scythe", 0f, END),
        ),
        "grind" to listOf(
            Shown("crank", 0f, END),
        ),
        "chop" to listOf(
            Shown("axe", 0f, END),
        ),
        "pour_sack" to listOf(
            Shown("sack_empty", 30f, END),
            Shown("sack_held", 0f, 30f),
        ),
        "ring_bell" to listOf(
            Shown("handbell", 0f, END),
        ),
        "announce" to listOf(
            Shown("handbell", 0f, END),
            Shown("scroll", 0f, END),
        ),
        "draw_water" to listOf(
            Shown("bucket", 48f, END),
            Shown("curb_rope", 0f, 48f),
            Shown("haul_rope_left", 4f, 13f),
            Shown("haul_rope_left", 20f, 29f),
            Shown("haul_rope_left", 36f, 49f),
            Shown("haul_rope_right", 12f, 21f),
            Shown("haul_rope_right", 28f, 37f),
            Shown("tail_rope_left", 4f, 13f),
            Shown("tail_rope_left", 21f, 29f),
            Shown("tail_rope_left", 37f, 48f),
            Shown("tail_rope_right", 13f, 21f),
            Shown("tail_rope_right", 29f, 37f),
            Shown("well_bucket", 0f, 48f),
            Shown("well_rope", 0f, 30.5f),
        ),
        "carry_pole" to listOf(
            Shown("lamp_pole", 0f, END),
        ),
        "light_lamp" to listOf(
            Shown("lamp_pole", 0f, END),
        ),
        "snuff_lamp" to listOf(
            Shown("lamp_pole", 0f, END),
        ),
    )

    /** By clip: the props its file turns, places and stretches itself tick by tick ("prop:<part>" bones). */
    val TRACKED: Map<String, List<String>> = mapOf(
        "grind" to listOf("crank"),
        "draw_water" to listOf("bucket", "curb_rope", "haul_rope_left", "haul_rope_right", "tail_rope_left", "tail_rope_right", "well_bucket", "well_rope"),
        "snuff_lamp" to listOf("lamp_pole"),
    )

    /** Props that hang straight down from the hand holding them (where no track runs them). */
    val HANG: Set<String> = setOf("bucket", "sack_empty")

    /** Props with a flame that glows in the dark. */
    val GLOW: Set<String> = setOf("lamp_pole")

    /** By clip: where the goods someone carries are, and when. */
    val GOODS: Map<String, List<Goods>> = mapOf(
        "pick_up" to listOf(
            Goods(0f, 8.5f, How.GROUND, "", 0f, 19.5f, -9.5f, "", 0f, 0f, 0f, 0f, 8f),
            Goods(8.5f, END, How.HANDS, "", 0f, 0f, 0f, "", 0f, 0f, 0f, 0f, 8f),
        ),
        "put_down" to listOf(
            Goods(0f, 11f, How.HANDS, "", 0f, 0f, 0f, "", 0f, 0f, 0f, 0f, 8f),
            Goods(11f, END, How.GROUND, "", 0f, 19.5f, -9.5f, "", 0f, 0f, 0f, 0f, 8f),
        ),
        "hand_over" to listOf(
            Goods(0f, 16f, How.HANDS, "", 2f, 0f, 0f, "", 0f, 0f, 0f, 0f, 8f),
        ),
        "receive" to listOf(
            Goods(16f, END, How.HANDS, "", 2f, 0f, 0f, "", 0f, 0f, 0f, 0f, 8f),
        ),
        "pay" to listOf(
            Goods(7f, 17f, How.PART, "right_arm", -1f, 10.5f, -3f, "", 0f, 0f, 0f, 0f, 3f),
        ),
        "carry" to listOf(
            Goods(0f, END, How.HANDS, "", 0f, 0f, 0f, "", 0f, 0f, 0f, 0f, 8f),
        ),
        "set_up" to listOf(
            Goods(9f, 20f, How.PART, "left_arm", 1f, 11f, -2.5f, "", 0f, 0f, 0f, 0f, 5f),
            Goods(20f, 31f, How.GROUND, "", 2f, 5f, -12f, "", 0f, 0f, 0f, 0f, 5f),
        ),
        "carry_basket" to listOf(
            Goods(0f, END, How.PART, "body", 5.6f, 7.8f, -4f, "", 0f, 0f, 0f, 0f, 3.5f),
        ),
        "gather" to listOf(
            Goods(13f, 21f, How.PART, "right_arm", -1f, 11.4f, -1f, "", 0f, 0f, 0f, 0f, 3f),
            Goods(21f, 24f, How.TOSS, "", 4.93f, 12.742f, -6.577f, "body", 5.6f, 7.8f, -4f, 2f, 3f),
            Goods(24f, END, How.PART, "body", 5.6f, 7.8f, -4f, "", 0f, 0f, 0f, 0f, 3f),
        ),
    )

    /** By clip: the prop the goods take the place of while they show. */
    val GOODS_REPLACE: Map<String, String> = mapOf("carry" to "crate", "pay" to "coin")

    /** Clips that show goods only when something is carried (and their own props otherwise). */
    val GOODS_ONLY_CARRIED: Set<String> = setOf("carry", "pay")

    /** By clip: the item shown when nothing is carried (a crop pulled up is a carrot). */
    val GOODS_DEFAULT: Map<String, String> = mapOf("gather" to "minecraft:carrot")

    /** By clip: its moments, the ticks things happen on (a knock, a bite, a bell rung), for their sounds and dust. */
    val MOMENTS: Map<String, List<Moment>> = mapOf(
        "sit_down" to listOf(
            Moment("glance", 3f, -0.751f, 4.364f, -15.083f),
            Moment("down", 12f, 0f, 21.941f, -0.784f),
        ),
        "stand_up" to listOf(
            Moment("feet", 5f, -1.9f, 31.502f, -14.947f),
            Moment("up", 10f, 0f, 20.003f, -13.798f),
        ),
        "nod" to listOf(
            Moment("nod", 3.5f, 0f, 3.089f, -2.799f),
            Moment("nod", 10.5f, 0f, 3.05f, -2.674f),
        ),
        "shrug" to listOf(
            Moment("shrug", 6.5f, 0.238f, 2.903f, -2.105f),
        ),
        "point" to listOf(
            Moment("point", 7f, -0.621f, 3.106f, -2.787f),
            Moment("jab", 12.5f, -0.623f, 3.163f, -2.962f),
            Moment("jab", 16f, -0.617f, 3.149f, -2.921f),
        ),
        "bow" to listOf(
            Moment("bow", 12f, 0f, 7.563f, -6.1f),
        ),
        "yawn" to listOf(
            Moment("yawn", 12f, 0.446f, 2.782f, -1.443f),
        ),
        "stretch" to listOf(
            Moment("stretch", 19f, 0f, 2.703f, -0.899f),
        ),
        "knock" to listOf(
            Moment("knock", 7f, -1.909f, -5.634f, -7.38f),
            Moment("knock", 10f, -1.909f, -5.634f, -7.38f),
            Moment("knock", 13f, -1.909f, -5.634f, -7.38f),
        ),
        "climb" to listOf(
            Moment("grab", 0f, 5.039f, -7.447f, -6f),
            Moment("step", 4f, -2.181f, 18.451f, -6.796f),
            Moment("grab", 8f, -4.98f, -7.447f, -6f),
            Moment("step", 12f, 2.181f, 18.451f, -6.796f),
        ),
        "climb_top" to listOf(
            Moment("step", 4f, -2.181f, 18.428f, -6.787f),
            Moment("palm", 9.5f, -4.306f, -4.855f, -8.375f),
            Moment("step", 12f, 2.19f, 18.357f, -6.755f),
            Moment("palm", 19f, 4.2f, -1.282f, -9.6f),
            Moment("step", 20f, -2.186f, 18.463f, -6.801f),
            Moment("step", 28f, 2.185f, 18.454f, -6.797f),
            Moment("step", 36f, -2.188f, 18.539f, -6.827f),
            Moment("step", 44f, 2.192f, 18.681f, -6.866f),
            Moment("step", 52f, -2.195f, 18.808f, -6.888f),
            Moment("ledge", 60f, 2.188f, 21.582f, -10.708f),
        ),
        "eat" to listOf(
            Moment("bite", 12f, -0.273f, -1.242f, -6.323f),
        ),
        "sit_eat" to listOf(
            Moment("spoon", 16f, 0.129f, 9.183f, -7.955f),
            Moment("spoon", 46f, -0.128f, 9.29f, -8.356f),
        ),
        "wash" to listOf(
            Moment("scoop", 12f, 0f, 10.441f, -12.419f),
            Moment("splash", 18f, 0f, -1.284f, -8.755f),
            Moment("shake", 27f, 0f, 13.637f, -3.232f),
        ),
        "pick_up" to listOf(
            Moment("grab", 8.5f, 0f, 19.302f, -9.453f),
        ),
        "put_down" to listOf(
            Moment("set_down", 11f, 0f, 19.5f, -9.5f),
        ),
        "hand_over" to listOf(
            Moment("handoff", 16f, 0f, 3f, -16.38f),
        ),
        "receive" to listOf(
            Moment("handoff", 16f, 0f, 3.001f, -16.066f),
        ),
        "pay" to listOf(
            Moment("clink", 11f, -1.058f, 4.909f, -13.39f),
            Moment("clink", 14f, -0.576f, 4.914f, -13.452f),
            Moment("clink", 17f, -0.184f, 4.932f, -13.348f),
        ),
        "browse" to listOf(
            Moment("pick", 14f, -3.976f, 5.649f, -12.157f),
            Moment("look", 25f, -0.647f, 1.028f, -9.742f),
            Moment("put back", 36f, -3.976f, 5.649f, -12.158f),
            Moment("feel", 48f, 3.033f, 5.547f, -11.354f),
        ),
        "set_up" to listOf(
            Moment("take", 9f, 14.733f, 12.269f, -1.014f),
            Moment("place", 20f, 2f, 5f, -12f),
            Moment("tug", 38f, -0.078f, -5.283f, -5.019f),
            Moment("tug", 42f, -0.07f, -5.269f, -5.084f),
        ),
        "sow" to listOf(
            Moment("seed", 11f, -12.863f, -1.322f, -5.305f),
            Moment("seed", 31f, -12.321f, 0.077f, -6.611f),
        ),
        "gather" to listOf(
            Moment("pull", 13f, -1.978f, 18.709f, -10.17f),
            Moment("toss", 21f, 4.191f, 11.277f, -6.059f),
            Moment("in_basket", 24f, 6.226f, 13.836f, -2.591f),
        ),
        "scythe" to listOf(
            Moment("swish", 7f, -19.187f, 23.4f, -19.587f),
            Moment("cut", 10f, -7.808f, 23.4f, -25.798f),
        ),
        "grind" to listOf(
            Moment("grind", 10f, 0f, 19f, -16f),
            Moment("grind", 30f, 0f, 19f, -16f),
        ),
        "chop" to listOf(
            Moment("chop", 18f, 0f, 6.4f, -20.5f),
        ),
        "pour_sack" to listOf(
            Moment("swing", 4f, -3.427f, -0.679f, -4.352f),
            Moment("pour", 11f, 0f, 8.5f, -16f),
            Moment("shake", 27f, 0f, 8.5f, -16f),
            Moment("empty", 30f, 1.416f, 7.566f, -15.5f),
        ),
        "ring_bell" to listOf(
            Moment("ring", 4f, -7.04f, -12.713f, -4.103f),
            Moment("ring", 11f, -8.232f, -7.167f, -11.687f),
            Moment("ring", 19f, -7.02f, -12.712f, -4.88f),
            Moment("ring", 26f, -8.212f, -7.166f, -12.465f),
        ),
        "announce" to listOf(
            Moment("sweep", 18f, -16.521f, 5.345f, -9.684f),
            Moment("beat", 33f, -8.064f, 5.845f, -14.661f),
            Moment("beat", 37f, -8.047f, 5.845f, -15.003f),
            Moment("show", 48f, -17.444f, 2.157f, -8.917f),
            Moment("ring", 60f, -8.594f, -10.011f, -8.82f),
            Moment("ring", 64f, -8.595f, -10.011f, -8.894f),
        ),
        "draw_water" to listOf(
            Moment("pull", 8f, -3.335f, 7.395f, -4.358f),
            Moment("pull", 16f, -2.224f, 8.083f, -2.581f),
            Moment("pull", 24f, -3.504f, 8.021f, -2.72f),
            Moment("pull", 32f, -2.224f, 8.083f, -2.581f),
            Moment("pull", 40f, -3.045f, 10.572f, -0.837f),
            Moment("knock", 29f, -0.6f, 6.342f, -26.2f),
            Moment("scrape", 33f, -0.816f, 5.6f, -21.621f),
            Moment("grab", 48f, -1.073f, 1.696f, -12.659f),
            Moment("slosh", 52f, -1.174f, 4.271f, -12.71f),
            Moment("slosh", 60f, -5.492f, 14.218f, -4.589f),
        ),
        "carry_pole" to listOf(
            Moment("smoke", 12f, -3.3f, -27.944f, -20.013f),
            Moment("smoke", 32f, -4.095f, -27.978f, -20.013f),
        ),
        "light_lamp" to listOf(
            Moment("reach", 21f, 0.008f, -27.56f, -12.619f),
            Moment("lit", 24f, 0f, -27.5f, -16f),
        ),
        "snuff_lamp" to listOf(
            Moment("clink", 18.5f, 0f, -26.6f, -13f),
            Moment("hiss", 23f, 0f, -27.5f, -16f),
            Moment("smoke", 25.5f, 0f, -27.1f, -12.4f),
        ),
    )
}
