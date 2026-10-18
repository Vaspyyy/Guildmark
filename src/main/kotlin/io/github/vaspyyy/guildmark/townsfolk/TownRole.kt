package io.github.vaspyyy.guildmark.townsfolk

/**
 * What a townsperson does for a living, which decides their look, their tools, their day and what they say.
 * The order is the network and save id; add new roles at the end. [looks] is how many looks the role's
 * textures have (art/scripts/townsfolk.py paints them).
 */
enum class TownRole(val looks: Int, val health: Double = 20.0) {
    /** Ordinary folk: errands, carrying, sweeping, gossip, and a craft at a workbench if they have one. */
    CITIZEN(8),
    /** Hammers hot iron at an anvil, works the forge and quenches in a cauldron. */
    SMITH(3),
    /** Kneads dough at a table and works the oven with a peel. */
    BAKER(3),
    /** Keeps a market stall: tidies the goods and calls out to passers-by. */
    MERCHANT(4),
    /** The city watch: patrols with a spear, stands guard, fights off monsters. Some keep the night watch. */
    GUARD(4, 30.0),
    /** Hoes and harvests the fields round a composter. */
    FARMER(3),
    /** Casts from the bank or a pier from before dawn. */
    FISHER(3),
    /** Plays in the streets by day: tag, hops, twirls, and following adventurers about. */
    CHILD(6, 12.0),
    /** Strolls hands behind the back, admires the sights and talks with other nobles. */
    NOBLE(4),
    /** Works the tavern's bar from the afternoon to midnight: wiping mugs and pouring ale. */
    INNKEEPER(2),
    /** Plays the lute in the square by day and the tavern by night. */
    BARD(2),
    /** Prays at the altar or lectern and blesses passers-by. */
    PRIEST(2),
    /** A Starwatch mage: reads and writes in the library, practises conjuring in the plaza, lectures. Look 0 is Idris. */
    MAGE(4),
    /** A Hearthsworn knight: spars, drills and stands at the barracks, kneels at dawn. Look 0 is Corra. */
    KNIGHT(4, 30.0),
    /** One of the Quiet Hand: dice and coin in the tavern attic by day, lurking and picking locks at night. Look 0 is Odile. */
    THIEF(4),
    ;

    /** The role's name in commands, textures and line keys. */
    val id: String = name.lowercase()

    /** Whether they're grown up enough to drink, gossip and keep an evening at the tavern. */
    val adult: Boolean get() = this != CHILD

    companion object {
        fun byId(id: Int): TownRole = entries.getOrElse(id) { CITIZEN }
        fun byName(name: String): TownRole? = entries.firstOrNull { it.id == name }
    }
}

/**
 * What a townsperson is seen doing; the client plays the clip of the same name from
 * assets/guildmark/animations/townsfolk/ (art/scripts/townsfolk_anims.py). The order is the network id.
 * One-shots last exactly [ticks]; loops repeat every [ticks]. An [overlay] only moves the arms (and maybe the
 * head), so it plays while walking; anything else is done standing still.
 */
enum class Activity(val clip: String, val ticks: Int, val loop: Boolean, val overlay: Boolean = false) {
    NONE("idle", 80, true, true),
    HAMMER("hammer", 24, true),
    TEND("tend", 40, true),
    QUENCH("quench", 50, false),
    KNEAD("knead", 30, true),
    OVEN("oven", 60, false),
    CALL_OUT("call_out", 40, false),
    ARRANGE("arrange", 60, true),
    BECKON("beckon", 30, false),
    PATROL("patrol", 40, true, true),
    STAND_GUARD("stand_guard", 120, true),
    SALUTE("salute", 30, false),
    HOE("hoe", 32, true),
    HARVEST("harvest", 40, false),
    CAST("cast", 30, false),
    FISH("fish", 80, true),
    REEL("reel", 40, false),
    PLAY("play", 16, true, true),
    JUMP("jump", 16, false),
    SPIN("spin", 24, false),
    WAVE("wave", 30, false),
    CLAP("clap", 24, false),
    STROLL("stroll", 40, true, true),
    ADMIRE("admire", 100, true),
    TALK("talk", 60, true),
    LISTEN("listen", 70, true),
    LAUGH("laugh", 30, false),
    DRINK("drink", 80, true),
    CHEER("cheer", 30, false),
    SIT("sit", 100, true),
    SIT_DRINK("sit_drink", 80, true),
    SIT_TALK("sit_talk", 60, true),
    WIPE("wipe", 40, true),
    POUR("pour", 40, false),
    LUTE("lute", 20, true),
    PRAY("pray", 120, true),
    SWEEP("sweep", 30, true),
    CARRY("carry", 40, true, true),
    CARRY_SACK("carry_sack", 40, true, true),
    FLEE("flee", 12, true, true),
    STAGGER("stagger", 60, true, true),
    LANTERN("lantern", 40, true, true),
    READ("read", 120, true),
    SCRIBE("scribe", 60, true),
    CONJURE("conjure", 80, true),
    LECTURE("lecture", 60, true),
    SPAR_ATTACK("spar_attack", 40, true),
    SPAR_GUARD("spar_guard", 40, true),
    DRILL("drill", 32, true),
    SHARPEN("sharpen", 40, true),
    KNEEL("kneel", 120, true),
    DICE("dice", 50, true),
    COUNT("count", 60, true),
    LURK("lurk", 100, true),
    PICK_LOCK("pick_lock", 60, true),
    WHISPER("whisper", 50, true),
    SHOUT("shout", 30, false),
    SEARCH("search", 40, true),
    BOUND("bound", 120, true),
    PLEAD("plead", 40, false),
    FREED("freed", 50, false),
    THANK("thank", 40, false),
    // A day with real places in it, and goods that change hands (the Living World)
    SIT_DOWN("sit_down", 16, false),
    STAND_UP("stand_up", 14, false),
    YAWN("yawn", 40, false),
    STRETCH("stretch", 40, false),
    CLIMB("climb", 16, true),
    EAT("eat", 60, true),
    SIT_EAT("sit_eat", 60, true),
    WASH("wash", 50, false),
    DRAW_WATER("draw_water", 80, false),
    PICK_UP("pick_up", 20, false),
    PUT_DOWN("put_down", 20, false),
    HAND_OVER("hand_over", 30, false),
    RECEIVE("receive", 30, false),
    PAY("pay", 30, false),
    BOW("bow", 30, false),
    NOD("nod", 20, false),
    SHRUG("shrug", 24, false),
    POINT("point", 30, false),
    BROWSE("browse", 60, true),
    SET_UP("set_up", 60, true),
    SOW("sow", 40, true, true),
    SCYTHE("scythe", 32, true),
    GATHER("gather", 40, false),
    CARRY_BASKET("carry_basket", 40, true, true),
    GRIND("grind", 40, true),
    POUR_SACK("pour_sack", 40, false),
    CHOP("chop", 30, true),
    RING_BELL("ring_bell", 30, true),
    ANNOUNCE("announce", 80, true),
    LIGHT_LAMP("light_lamp", 50, false),
    KNOCK("knock", 24, false),
    PULL_CART("pull_cart", 40, true, true),
    // A homestead's builders (art/scripts/homestead_anims.py; the work lands on the ticks in BuilderCrew.Builder)
    HAUL("haul", 40, true, true),
    CARRY_BLOCK("carry_block", 40, true, true),
    TAKE("take", 24, false),
    LAY("lay", 20, false),
    LAY_LOW("lay_low", 24, false),
    LAY_HIGH("lay_high", 22, false),
    NAIL("nail", 16, true),
    SAW("saw", 20, true),
    DIG("dig", 24, true),
    STAKE("stake", 20, true),
    CLAMBER("clamber", 16, true),
    PLANS("plans", 80, true),
    MOP_BROW("mop_brow", 40, false),
    HURRAH("hurrah", 40, false),
    // The grand festivals' dances, band and crowd (festival/FestivalDance, art/scripts/festival_anims.py)
    RING_STEP("fest_ring_step", 40, false),
    RING_STEP_L("fest_ring_step_l", 40, false),
    RING_IN("fest_ring_in", 40, false),
    COUPLE_TURN("fest_couple_turn", 40, false),
    TWIRL("fest_twirl", 40, false),
    FEST_BOW("fest_bow", 40, false),
    FEST_HURRAH("fest_hurrah", 40, false),
    CLAP_ALONG("fest_clap", 40, true),
    LANTERN_WALK("fest_lantern_walk", 40, false),
    LANTERN_RAISE("fest_lantern_raise", 40, false),
    MINSTREL("fest_minstrel", 40, true),
    HANG("fest_hang", 40, true),
    RELEASE("fest_release", 40, false),
    GAZE("fest_gaze", 80, true),
    // Caravans on the road (living-world/design-road.md; art/scripts/road_clips.py): the wagon's crew, a waylaying,
    // the run to the gate and the town's grumbling
    DRIVE("drive", 40, true),
    DRIVE_URGE("drive_urge", 20, false),
    DRIVE_BRAKE("drive_brake", 30, false),
    RIDE_TAIL("ride_tail", 60, true),
    JUMP_DOWN("jump_down", 16, false),
    CLIMB_UP("climb_up", 20, false),
    CUT_LOOSE("cut_loose", 50, false),
    COWER("cower", 40, true),
    HAND_DOWN("hand_down", 30, false),
    CATCH_BREATH("catch_breath", 40, true),
    RECOUNT("recount", 80, true),
    WOUNDED("wounded", 40, true, true),
    GRUMBLE("grumble", 40, false),
    ;

    /** Seated activities keep the townsperson on their seat (a wagon's bench and tailboard among them). */
    val seated: Boolean get() = this == SIT || this == SIT_DRINK || this == SIT_TALK || this == SCRIBE || this == DICE || this == SIT_EAT ||
        this == DRIVE || this == DRIVE_URGE || this == DRIVE_BRAKE || this == RIDE_TAIL

    companion object {
        fun byId(id: Int): Activity = entries.getOrElse(id) { NONE }
    }
}
