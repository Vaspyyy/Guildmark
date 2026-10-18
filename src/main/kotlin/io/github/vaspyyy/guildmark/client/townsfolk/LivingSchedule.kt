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
    val CLIPS: Set<String> = setOf("sit_down", "stand_up", "nod", "shrug", "point", "bow", "yawn", "stretch", "knock", "climb", "climb_top", "eat", "sit_eat", "wash", "pick_up", "put_down", "hand_over", "receive", "pay", "browse", "set_up", "sow", "carry_basket", "gather", "scythe", "grind", "chop", "pour_sack", "ring_bell", "announce", "draw_water", "explain", "gossip", "shush", "wave_off", "sigh", "think", "tip_hat", "introduce", "arms_folded", "recoil", "wave_walk", "nod_walk", "sit_gossip", "sit_laugh", "pause_work", "carry_pole", "light_lamp", "snuff_lamp", "drive", "drive_urge", "drive_brake", "ride_tail", "jump_down", "climb_up", "cut_loose", "hand_down", "cower", "catch_breath", "recount", "wounded", "grumble", "mix_mortar", "trowel", "hoist", "carry_beam", "chisel", "plumb", "survey", "unveil", "toast", "lookout", "light_beacon", "sound_horn", "queue", "shiver", "empty_basket", "haggle", "ladle", "hold_out", "eat_bowl", "take_bowl", "pack", "embrace", "look_about", "show_in", "shut_stall")

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
        "driver_knife" to "right_arm",
        "rein_right_1" to "right_arm",
        "rein_right_2" to "right_arm",
        "rein_right_3" to "right_arm",
        "rein_left_1" to "left_arm",
        "rein_left_2" to "left_arm",
        "rein_left_3" to "left_arm",
        "spear_knees" to "right_arm",
        "loot_sack" to "right_arm",
        "nosebag_held" to "left_arm",
        "trowel" to "right_arm",
        "hawk" to "left_arm",
        "mortar_hoe" to "right_arm",
        "mallet" to "right_arm",
        "chisel" to "left_arm",
        "plumb_line" to "right_arm",
        "sighting_staff" to "left_arm",
        "beam" to "body",
        "cup" to "right_arm",
        "ladle" to "right_arm",
        "ladle_full" to "right_arm",
        "soup_pour" to "right_arm",
        "bowl_empty" to "left_arm",
        "torch" to "right_arm",
        "torch_flame" to "right_arm",
        "horn" to "body",
        "horn_baldric" to "body",
        "bundle" to "body",
        "veil_1" to "body",
        "veil_2" to "body",
        "veil_3" to "body",
        "basket_turned" to "left_arm",
        "flap_rail" to "body",
        "flap_cloth" to "body",
        "flap_batten" to "body",
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
        "drive" to listOf(
            Shown("rein_left_1", 0f, END),
            Shown("rein_left_2", 0f, END),
            Shown("rein_left_3", 0f, END),
            Shown("rein_right_1", 0f, END),
            Shown("rein_right_2", 0f, END),
            Shown("rein_right_3", 0f, END),
        ),
        "drive_urge" to listOf(
            Shown("rein_left_1", 0f, END),
            Shown("rein_left_2", 0f, END),
            Shown("rein_left_3", 0f, END),
            Shown("rein_right_1", 0f, END),
            Shown("rein_right_2", 0f, END),
            Shown("rein_right_3", 0f, END),
        ),
        "drive_brake" to listOf(
            Shown("rein_left_1", 0f, END),
            Shown("rein_left_2", 0f, END),
            Shown("rein_left_3", 0f, END),
            Shown("rein_right_1", 0f, END),
            Shown("rein_right_2", 0f, END),
            Shown("rein_right_3", 0f, END),
        ),
        "ride_tail" to listOf(
            Shown("spear_knees", 0f, END),
        ),
        "jump_down" to listOf(
            Shown("rein_left_1", 0f, 1.5f),
            Shown("rein_left_2", 0f, 1.5f),
            Shown("rein_left_3", 0f, 1.5f),
            Shown("rein_right_1", 0f, 1.5f),
            Shown("rein_right_2", 0f, 1.5f),
            Shown("rein_right_3", 0f, 1.5f),
        ),
        "climb_up" to listOf(
            Shown("rein_left_1", 17f, END),
            Shown("rein_left_2", 17f, END),
            Shown("rein_left_3", 17f, END),
            Shown("rein_right_1", 17f, END),
            Shown("rein_right_2", 17f, END),
            Shown("rein_right_3", 17f, END),
        ),
        "cut_loose" to listOf(
            Shown("driver_knife", 0f, 24.5f),
        ),
        "mix_mortar" to listOf(
            Shown("mortar_hoe", 0f, END),
        ),
        "trowel" to listOf(
            Shown("hawk", 0f, END),
            Shown("trowel", 0f, END),
        ),
        "hoist" to listOf(
            Shown("curb_rope", 0f, END),
            Shown("haul_rope_right", 0f, 0.5f),
            Shown("haul_rope_right", 9.5f, 10.5f),
            Shown("haul_rope_right", 19.5f, 20.5f),
            Shown("haul_rope_right", 29.5f, 30.5f),
            Shown("haul_rope_right", 39.5f, END),
            Shown("well_rope", 0f, END),
        ),
        "carry_beam" to listOf(
            Shown("beam", 0f, END),
        ),
        "chisel" to listOf(
            Shown("chisel", 0f, END),
            Shown("mallet", 0f, END),
        ),
        "plumb" to listOf(
            Shown("plumb_line", 0f, END),
        ),
        "survey" to listOf(
            Shown("sighting_staff", 0f, END),
        ),
        "unveil" to listOf(
            Shown("veil_1", 9f, 36f),
            Shown("veil_2", 9f, 36f),
            Shown("veil_3", 9f, 36f),
        ),
        "toast" to listOf(
            Shown("cup", 0f, END),
        ),
        "lookout" to listOf(
            Shown("horn", 0f, END),
            Shown("horn_baldric", 0f, END),
        ),
        "light_beacon" to listOf(
            Shown("horn", 0f, END),
            Shown("horn_baldric", 0f, END),
            Shown("torch", 0f, END),
            Shown("torch_flame", 0f, END),
        ),
        "sound_horn" to listOf(
            Shown("horn", 0f, END),
            Shown("horn_baldric", 0f, END),
        ),
        "empty_basket" to listOf(
            Shown("basket_turned", 0f, END),
        ),
        "ladle" to listOf(
            Shown("bowl", 13.5f, 21f),
            Shown("bowl_empty", 3.5f, 13.5f),
            Shown("ladle", 0f, 8f),
            Shown("ladle", 14f, END),
            Shown("ladle_full", 8f, 14f),
            Shown("soup_pour", 12.5f, 14f),
            Shown("spoon", 13.5f, 21f),
        ),
        "hold_out" to listOf(
            Shown("bowl", 21f, END),
            Shown("spoon", 21f, END),
        ),
        "eat_bowl" to listOf(
            Shown("bowl", 0f, END),
            Shown("spoon", 0f, END),
        ),
        "take_bowl" to listOf(
            Shown("bowl", 0f, END),
            Shown("spoon", 0f, END),
        ),
        "pack" to listOf(
            Shown("bundle", 0f, END),
        ),
        "shut_stall" to listOf(
            Shown("flap_batten", 0f, END),
            Shown("flap_cloth", 0f, END),
            Shown("flap_rail", 0f, END),
            Shown("well_rope", 0f, END),
        ),
    )

    /** By clip: the props its file turns, places and stretches itself tick by tick ("prop:<part>" bones). */
    val TRACKED: Map<String, List<String>> = mapOf(
        "grind" to listOf("crank"),
        "draw_water" to listOf("bucket", "curb_rope", "haul_rope_left", "haul_rope_right", "tail_rope_left", "tail_rope_right", "well_bucket", "well_rope"),
        "snuff_lamp" to listOf("lamp_pole"),
        "ride_tail" to listOf("spear_knees"),
        "drive" to listOf("rein_left_1", "rein_left_2", "rein_left_3", "rein_right_1", "rein_right_2", "rein_right_3"),
        "drive_urge" to listOf("rein_left_1", "rein_left_2", "rein_left_3", "rein_right_1", "rein_right_2", "rein_right_3"),
        "drive_brake" to listOf("rein_left_1", "rein_left_2", "rein_left_3", "rein_right_1", "rein_right_2", "rein_right_3"),
        "jump_down" to listOf("rein_left_1", "rein_left_2", "rein_left_3", "rein_right_1", "rein_right_2", "rein_right_3"),
        "climb_up" to listOf("rein_left_1", "rein_left_2", "rein_left_3", "rein_right_1", "rein_right_2", "rein_right_3"),
        "hoist" to listOf("curb_rope", "haul_rope_right", "well_rope"),
        "plumb" to listOf("plumb_line"),
        "survey" to listOf("sighting_staff"),
        "unveil" to listOf("veil_1", "veil_2", "veil_3"),
        "toast" to listOf("cup"),
        "light_beacon" to listOf("torch", "torch_flame"),
        "sound_horn" to listOf("horn"),
        "empty_basket" to listOf("basket_turned"),
        "ladle" to listOf("bowl", "bowl_empty", "ladle", "ladle_full", "soup_pour", "spoon"),
        "hold_out" to listOf("bowl", "spoon"),
        "eat_bowl" to listOf("bowl"),
        "take_bowl" to listOf("bowl", "spoon"),
        "pack" to listOf("bundle"),
        "shut_stall" to listOf("flap_batten", "flap_cloth", "flap_rail", "well_rope"),
    )

    /** Props that hang straight down from the hand holding them (where no track runs them). */
    val HANG: Set<String> = setOf("bucket", "sack_empty", "loot_sack", "nosebag_held", "plumb_line")

    /** Props with a flame that glows in the dark. */
    val GLOW: Set<String> = setOf("lamp_pole", "torch", "torch_flame")

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
        "hand_down" to listOf(
            Goods(7f, 16f, How.HANDS, "", 2f, 0f, 0f, "", 0f, 0f, 0f, 0f, 8f),
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
        "explain" to listOf(
            Moment("beat", 20f, -0.101f, 3.197f, -3.122f),
            Moment("count", 30f, 0.435f, 3.253f, -3.262f),
            Moment("count", 35f, 0.765f, 3.234f, -3.186f),
            Moment("count", 40f, 0.65f, 3.235f, -3.202f),
            Moment("nod", 45f, 0.078f, 3.281f, -3.351f),
        ),
        "gossip" to listOf(
            Moment("glance", 16.5f, 0.24f, 4.002f, -2.937f),
            Moment("glance", 38.5f, 0.273f, 4.092f, -2.967f),
            Moment("nod", 45.5f, 0.381f, 4.37f, -3.051f),
        ),
        "shush" to listOf(
            Moment("shush", 6.5f, 0f, 3.256f, -3.575f),
            Moment("look", 13.5f, 0.212f, 3.173f, -3.337f),
            Moment("look", 18.5f, -0.159f, 3.173f, -3.341f),
        ),
        "wave_off" to listOf(
            Moment("flick", 6.5f, -0.212f, 2.882f, -1.866f),
        ),
        "sigh" to listOf(
            Moment("sigh", 12f, 0f, 2.958f, -2.343f),
        ),
        "think" to listOf(
            Moment("tap", 16f, 0.801f, 3.076f, -2.689f),
            Moment("tap", 20f, 0.802f, 3.081f, -2.706f),
            Moment("tap", 24f, 0.803f, 3.084f, -2.717f),
            Moment("ah", 37.5f, 0.37f, 2.945f, -2.264f),
        ),
        "tip_hat" to listOf(
            Moment("touch", 7f, -0.094f, 3.069f, -2.735f),
        ),
        "introduce" to listOf(
            Moment("chest", 8.5f, 0.082f, 2.958f, -2.341f),
            Moment("nod", 18.5f, 0.135f, 3.825f, -3.383f),
            Moment("offer", 27f, 0f, 3.1f, -2.694f),
        ),
        "arms_folded" to listOf(
            Moment("tap", 23f, 1.102f, 3.164f, -1.468f),
            Moment("tap", 27f, 1.061f, 3.171f, -1.509f),
            Moment("tap", 31f, 1.011f, 3.169f, -1.518f),
            Moment("hmph", 52f, 0.773f, 3.076f, -1.179f),
        ),
        "recoil" to listOf(
            Moment("start", 2f, 0f, 3.008f, -0.491f),
        ),
        "wave_walk" to listOf(
            Moment("wave", 10f, 0f, 3f, -2.5f),
        ),
        "nod_walk" to listOf(
            Moment("touch", 5.5f, 0f, 3f, -2.5f),
            Moment("nod", 7.5f, 0f, 3f, -2.5f),
        ),
        "sit_gossip" to listOf(
            Moment("glance", 19f, -0.167f, 13.794f, -4.527f),
            Moment("glance", 41f, -0.166f, 13.788f, -4.52f),
            Moment("nod", 47.5f, -0.175f, 14.105f, -4.904f),
        ),
        "sit_laugh" to listOf(
            Moment("slap", 12.5f, 0f, 13.209f, -3.728f),
            Moment("slap", 21.5f, 0f, 13.015f, -3.427f),
        ),
        "pause_work" to listOf(
            Moment("set_down", 2.5f, 0f, 4.505f, -3.844f),
            Moment("wipe", 12f, 0f, 3.402f, -2.96f),
            Moment("wipe", 16f, 0f, 3.366f, -2.927f),
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
        "drive_urge" to listOf(
            Moment("click", 3f, 0f, 7.135f, -4.875f),
            Moment("flick", 5f, 1.052f, 18.784f, -24.594f),
        ),
        "drive_brake" to listOf(
            Moment("haul", 6f, -2.406f, 17.639f, -0.335f),
            Moment("brake", 11f, -5.9f, 18.632f, -0.795f),
        ),
        "jump_down" to listOf(
            Moment("push", 5f, -3.813f, 23.187f, 0.998f),
            Moment("land", 10f, -14f, 51.5f, -1f),
        ),
        "climb_up" to listOf(
            Moment("grab", 3f, -5.53f, 23.854f, 4.607f),
            Moment("step", 4f, -7.7f, 41.1f, 13.5f),
            Moment("sit", 13f, 0f, 24f, 0f),
        ),
        "cut_loose" to listOf(
            Moment("saw", 5f, -2.554f, 3.649f, -13.471f),
            Moment("saw", 9f, -2.554f, 3.649f, -13.471f),
            Moment("saw", 13f, -2.554f, 3.649f, -13.471f),
            Moment("saw", 17f, -2.554f, 3.649f, -13.471f),
            Moment("cut", 20f, -2.554f, 3.649f, -13.471f),
            Moment("sheathe", 24.5f, -4.621f, 10.692f, -1.074f),
            Moment("slap", 39f, 21.6f, 2.5f, -11.7f),
        ),
        "hand_down" to listOf(
            Moment("lift", 7f, -0.065f, 20.967f, 8.484f),
            Moment("handoff", 16f, 0f, 22.005f, -7.999f),
        ),
        "cower" to listOf(
            Moment("flinch", 30.5f, 0.705f, 11.267f, -7.405f),
        ),
        "catch_breath" to listOf(
            Moment("gasp", 1.5f, -0.047f, 5.149f, -12.371f),
            Moment("gasp", 17.5f, -0.001f, 5.517f, -12.572f),
            Moment("cough", 30f, 0.297f, 7.695f, -13.686f),
        ),
        "recount" to listOf(
            Moment("point", 6f, -2.407f, -0.618f, 13.49f),
            Moment("rush", 21f, -0.2f, 0.142f, -14.134f),
            Moment("sweep", 38f, -7.467f, 5.548f, -6.145f),
            Moment("clap", 47f, 0.017f, 8.568f, -9.179f),
            Moment("clap", 50f, 0.104f, 8.423f, -9.209f),
        ),
        "wounded" to listOf(
            Moment("wince", 4f, -0.055f, -0.549f, -4.888f),
            Moment("wince", 24f, -0.062f, -0.814f, -4.851f),
        ),
        "grumble" to listOf(
            Moment("huff", 7f, 1.073f, -2.482f, -3.492f),
            Moment("sweep", 27f, -7.804f, 6.757f, -10.871f),
        ),
        "mix_mortar" to listOf(
            Moment("stir", 3f, 0.805f, 21.624f, -14.124f),
            Moment("stir", 23f, -0.601f, 21.632f, -14.128f),
        ),
        "trowel" to listOf(
            Moment("scoop", 4f, 7.227f, 5.163f, -5.586f),
            Moment("spread", 11f, -4.838f, 7.738f, -11.552f),
            Moment("tap", 20.5f, 0.824f, 7.362f, -11.646f),
            Moment("tap", 23.5f, -1.78f, 7.369f, -11.47f),
            Moment("cut", 28f, 1.56f, 8.355f, -9.268f),
        ),
        "hoist" to listOf(
            Moment("heave", 0.5f, -0.025f, 1.232f, -4.798f),
            Moment("heave", 10.5f, 0.025f, 1.232f, -4.798f),
            Moment("heave", 20.5f, -0.025f, 1.232f, -4.798f),
            Moment("heave", 30.5f, 0.025f, 1.232f, -4.798f),
        ),
        "carry_beam" to listOf(
            Moment("hitch", 22f, -6.804f, -4.612f, -6.713f),
        ),
        "chisel" to listOf(
            Moment("strike", 6f, 1.167f, 7.902f, -9.12f),
            Moment("strike", 16f, -0.032f, 7.894f, -9.1f),
            Moment("strike", 26f, -1.225f, 7.887f, -9.08f),
        ),
        "plumb" to listOf(
            Moment("true", 42f, -0.013f, -3.131f, -5.911f),
        ),
        "survey" to listOf(
            Moment("plant", 10f, 6.4f, 24f, -10f),
            Moment("wave", 27f, -9.477f, -0.703f, -9.867f),
            Moment("wave", 32f, -9.748f, -0.857f, -9.311f),
            Moment("there", 38f, -2.576f, 2.365f, -10.91f),
        ),
        "unveil" to listOf(
            Moment("pull", 9f, 0.026f, 6.318f, -6.933f),
            Moment("present", 17f, 4.342f, -3.615f, -8.145f),
        ),
        "toast" to listOf(
            Moment("raise", 9f, -4.128f, -10.847f, -7.584f),
            Moment("drink", 21f, 0f, -3.303f, -2.625f),
        ),
        "lookout" to listOf(
            Moment("stare", 52f, -1.636f, -3.097f, -5.491f),
        ),
        "light_beacon" to listOf(
            Moment("dip", 11f, 0f, 11f, -16f),
            Moment("flare", 14f, 0f, 11f, -16f),
        ),
        "sound_horn" to listOf(
            Moment("blow", 12f, 0.708f, -2.755f, -2.858f),
        ),
        "queue" to listOf(
            Moment("murmur", 46f, -0.152f, -1.726f, -4.394f),
        ),
        "shiver" to listOf(
            Moment("stamp", 14f, -1.9f, 24f, -1f),
            Moment("stamp", 34f, 1.9f, 24f, -1f),
            Moment("puff", 21f, 0f, -0.908f, -5.758f),
        ),
        "empty_basket" to listOf(
            Moment("shake", 13f, 0.6f, 6.041f, -8.8f),
            Moment("shake", 16f, 0.6f, 6.041f, -8.8f),
        ),
        "haggle" to listOf(
            Moment("count", 10f, 1.344f, 3.645f, -10.2f),
            Moment("count", 15f, 1.837f, 3.804f, -10.303f),
            Moment("count", 20f, 2.328f, 3.857f, -10.331f),
            Moment("no", 30.5f, -8.306f, 4.635f, -7.111f),
        ),
        "ladle" to listOf(
            Moment("take", 3.5f, 3.996f, 8.627f, -13.042f),
            Moment("dip", 7.5f, -3.293f, 9.3f, -13.226f),
            Moment("pour", 12.5f, 0.899f, 3.638f, -13.402f),
            Moment("give", 21f, 0.373f, 3.995f, -16.043f),
        ),
        "hold_out" to listOf(
            Moment("offer", 10f, 0f, 6.641f, -10.396f),
            Moment("take", 21f, -0.4f, 4f, -16f),
        ),
        "eat_bowl" to listOf(
            Moment("spoon", 16f, 1.243f, 0.502f, -7.902f),
            Moment("spoon", 46f, 0.144f, 0.656f, -8.305f),
        ),
        "take_bowl" to listOf(
            Moment("bow", 9.5f, 0f, -1.903f, -7.5f),
        ),
        "pack" to listOf(
            Moment("lift", 10f, 3f, 20.946f, -6.8f),
            Moment("set", 25f, 2.2f, 7.479f, -16.756f),
        ),
        "embrace" to listOf(
            Moment("pat", 19f, -3.722f, 3.92f, -15.449f),
            Moment("pat", 22.5f, -3.722f, 3.92f, -15.449f),
            Moment("pat", 26f, -3.722f, 3.92f, -15.449f),
        ),
        "look_about" to listOf(
            Moment("look", 24f, -0.072f, -4.809f, -0.724f),
            Moment("look", 58f, -0.976f, -4.417f, -1.696f),
        ),
        "show_in" to listOf(
            Moment("show", 12f, -11.785f, -0.821f, -7.007f),
        ),
        "shut_stall" to listOf(
            Moment("pull", 6.5f, 0f, -21f, -8f),
            Moment("hook", 17.5f, 0f, 7.2f, -8f),
        ),
    )
}
