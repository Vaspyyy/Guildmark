package io.github.vaspyyy.guildmark.progression

import net.minecraft.network.chat.Component
import kotlin.math.cos
import kotlin.math.sin

/** A node's size and weight in the tree. */
enum class NodeKind(val size: Int) {
    ROOT(26),
    /** A small step: a little of one or two bonuses. */
    MINOR(14),
    /** A named node with a stronger bonus. */
    NOTABLE(22),
    /** The tip of a branch: a big, build-shaping bonus, often with a price. */
    KEYSTONE(30),
}

/**
 * One node of the skill tree, at ([x], [y]) in tree units from the root (y grows downward). A node is
 * learned with one tree point when it touches a learned node and its [skill] has reached [requires]
 * (or the adventurer [rank] is reached, for the guild's branch).
 */
class Node(
    val id: String,
    val kind: NodeKind,
    val x: Float,
    val y: Float,
    val effects: List<Effect>,
    /** Sprite under guildmark:tree/ drawn in the socket, like "icon/blades" or "glyph/damage". */
    val icon: String,
    /** The branch it belongs to ("blades", "guild"), or "ring", "gate", "bridge", "root". */
    val branch: String,
    val skill: Skill? = null,
    val requires: Int = 0,
    val rank: AdventurerRank? = null,
    /** Has a name and a line of its own in the lang file; the rest are named after their icon. */
    val named: Boolean = false,
) {
    val links = ArrayList<Node>()

    private val langId: String get() = id.replace('/', '.')

    val title: Component
        get() = when {
            named || kind == NodeKind.ROOT -> Component.translatable("tree.guildmark.$langId")
            else -> Component.translatable("tree.guildmark.family.${icon.substringAfter('/')}")
        }

    /** A named node's line of flavour, under its effects. */
    val flavour: Component? get() = if (named || kind == NodeKind.ROOT) Component.translatable("tree.guildmark.$langId.desc") else null
}

/** Why a node can't be learned right now. */
enum class Blocked(val key: String) {
    LEARNED("learned"),
    NOT_CONNECTED("not_connected"),
    NO_POINTS("no_points"),
    NEEDS_SKILL("needs_skill"),
    NEEDS_RANK("needs_rank"),
}

/**
 * The skill tree: a root in the middle with four roads out to a ring, and from the ring twenty slots of
 * branches running outward, one per skill (plus the guild's), each opening further as its skill grows.
 * Bridges join neighbouring branches halfway out. Built once from [TreeContent]; node ids are saved in
 * players' sheets, so they must never change.
 */
object SkillTree {
    const val ROOT = "root"

    /** Tree units from the root to the ring, to a branch's first node, and between a branch's rows and sides. */
    const val GATE_RADIUS = 88f
    const val RING_RADIUS = 170f
    const val BRANCH_START = 250f
    const val ROW = 32f
    const val SIDE = 26f
    const val SLOTS = 20

    val nodes: Map<String, Node>
    val root: Node

    init {
        val builder = TreeBuilder()
        TreeContent.build(builder)
        nodes = builder.nodes
        root = nodes.getValue(ROOT)
    }

    fun node(id: String): Node? = nodes[id]

    /** Slot [i]'s angle in radians: slot 0 at the top, going clockwise. */
    fun slotAngle(i: Int): Double = Math.toRadians(-90.0 + 360.0 / SLOTS * i)

    /** Tree points earned: one a level past the first, and one for each skill milestone. */
    fun earned(progress: GuildProgress, skills: SkillState): Int = (progress.level - 1) + skills.milestones()

    fun available(progress: GuildProgress, skills: SkillState, sheet: CharacterSheet): Int =
        earned(progress, skills) - sheet.nodes.size

    /** Whether [node] touches the root or a node in [learned]. */
    fun touches(node: Node, learned: Collection<String>): Boolean =
        node.links.any { it.id == ROOT || it.id in learned }

    /** Whether [node]'s skill or rank allows it. */
    fun meetsRequirement(node: Node, progress: GuildProgress, skills: SkillState): Boolean {
        node.skill?.let { if (skills.level(it) < node.requires) return false }
        node.rank?.let { if (progress.adventurerRank < it.ordinal) return false }
        return true
    }

    /** Null when [node] can be learned next on top of [learned] (by default the sheet's), or why not. */
    fun blocked(
        node: Node, progress: GuildProgress, skills: SkillState, sheet: CharacterSheet,
        learned: Collection<String> = sheet.nodes, points: Int = available(progress, skills, sheet),
    ): Blocked? = when {
        node.id == ROOT || node.id in learned -> Blocked.LEARNED
        !touches(node, learned) -> Blocked.NOT_CONNECTED
        node.skill != null && skills.level(node.skill) < node.requires -> Blocked.NEEDS_SKILL
        node.rank != null && progress.adventurerRank < node.rank.ordinal -> Blocked.NEEDS_RANK
        points <= 0 -> Blocked.NO_POINTS
        else -> null
    }

    /** Whether every node in [learned] still reaches the root through learned nodes. */
    fun connected(learned: Collection<String>): Boolean {
        val owned = learned.toHashSet()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Node>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            for (next in at.links) if (next.id in owned && seen.add(next.id)) queue.add(next)
        }
        return seen.size == owned.size
    }

    /** Every effect of the nodes in [learned]. */
    fun effects(learned: Collection<String>): List<Effect> = learned.mapNotNull { nodes[it] }.flatMap { it.effects }
}

/** A notable or keystone in a branch: its id within the branch, what it gives and (optionally) its own icon. */
class Named(val id: String, val effects: List<Effect>, val icon: String? = null)

/**
 * One branch's contents for [TreeBuilder.branch]. The three kinds of minor node repeat along the branch:
 * [a] at its start and up the left side, [b] up the right side, [c] in the middle further out.
 */
class BranchSpec(val id: String, val slot: Int, val skill: Skill?) {
    var a: List<Effect> = listOf()
    var b: List<Effect> = listOf()
    var c: List<Effect> = listOf()
    val notables = ArrayList<Named>()
    var keystone: Named? = null
    /** For the guild's branch: the adventurer rank each place needs, in place of a skill level. */
    var ranks: Map<String, AdventurerRank>? = null
    var ring: List<Effect> = listOf()
}

/** Lays out the nodes: positions follow from slots and places in a branch, so the content only says what goes where. */
class TreeBuilder {
    val nodes = LinkedHashMap<String, Node>()
    private val branches = arrayOfNulls<BranchSpec>(SkillTree.SLOTS)
    private val rings = arrayOfNulls<Node>(SkillTree.SLOTS)

    fun add(node: Node): Node {
        require(node.id !in nodes) { "Duplicate tree node ${node.id}" }
        nodes[node.id] = node
        return node
    }

    fun link(a: Node, b: Node) {
        if (b !in a.links) a.links.add(b)
        if (a !in b.links) b.links.add(a)
    }

    fun node(id: String): Node = nodes.getValue(id)

    /** Where a node sits [u] rows out along slot [slot]'s branch and [v] sides across it (positive toward the next slot). */
    fun at(slot: Int, u: Float, v: Float): Pair<Float, Float> {
        val angle = SkillTree.slotAngle(slot)
        val r = SkillTree.BRANCH_START + SkillTree.ROW * u
        val ox = cos(angle)
        val oy = sin(angle)
        return Pair((ox * r - oy * SkillTree.SIDE * v).toFloat(), (oy * r + ox * SkillTree.SIDE * v).toFloat())
    }

    fun root() = add(Node(SkillTree.ROOT, NodeKind.ROOT, 0f, 0f, listOf(), "root", "root"))

    /** A ring node at [slot], linked to its neighbours once all are made. */
    fun ring(slot: Int, id: String, effects: List<Effect>) {
        val angle = SkillTree.slotAngle(slot)
        rings[slot] = add(Node("ring/$id", NodeKind.MINOR, (cos(angle) * SkillTree.RING_RADIUS).toFloat(), (sin(angle) * SkillTree.RING_RADIUS).toFloat(),
            effects, glyphOf(effects), "ring"))
    }

    /** A road from the root to the ring node at [slot]. */
    fun gate(slot: Int, id: String, icon: String, effects: List<Effect>) {
        val angle = SkillTree.slotAngle(slot)
        val gate = add(Node("gate/$id", NodeKind.NOTABLE, (cos(angle) * SkillTree.GATE_RADIUS).toFloat(), (sin(angle) * SkillTree.GATE_RADIUS).toFloat(),
            effects, icon, "gate", named = true))
        link(node(SkillTree.ROOT), gate)
        link(gate, rings[slot] ?: error("Gate before ring at slot $slot"))
    }

    /**
     * A branch of fourteen nodes on slot [spec].slot:
     *
     *     e - a1 - N1 - a3 - m4 - a5 - N3 - m7 - K
     *      \- b1 - b2 - N2 -/    \- b5 - N4 -/
     *
     * with the skill levels each needs rising outward (see [REQUIRES]).
     */
    fun branch(spec: BranchSpec) {
        branches[spec.slot] = spec
        val (n1, n2, n3, n4) = spec.notables
        val keystone = spec.keystone ?: error("Branch ${spec.id} has no keystone")
        val icon = "icon/${spec.id}"
        fun place(key: String, kind: NodeKind, u: Float, v: Float, effects: List<Effect>, nodeIcon: String, named: Boolean, name: String = key): Node {
            val (x, y) = at(spec.slot, u, v)
            val rank = spec.ranks?.get(key)
            return add(Node("${spec.id}/$name", kind, x, y, effects, nodeIcon, spec.id,
                skill = spec.skill, requires = if (spec.skill != null) REQUIRES.getValue(key) else 0, rank = rank, named = named))
        }
        fun minor(key: String, u: Float, v: Float, effects: List<Effect>) = place(key, NodeKind.MINOR, u, v, effects, glyphOf(effects), false)
        fun notable(key: String, u: Float, v: Float, named: Named) = place(key, NodeKind.NOTABLE, u, v, named.effects, named.icon ?: icon, true, named.id)

        val e = minor("e", 0f, 0f, spec.a)
        val a1 = minor("a1", 1f, -1f, spec.a)
        val b1 = minor("b1", 1f, 1f, spec.b)
        val a2 = notable("a2", 2f, -1.3f, n1)
        val b2 = minor("b2", 2f, 1.3f, spec.b)
        val a3 = minor("a3", 3f, -1f, spec.a)
        val b3 = notable("b3", 3f, 1.3f, n2)
        val m4 = minor("m4", 4f, 0f, spec.c)
        val a5 = minor("a5", 5f, -1f, spec.c)
        val b5 = minor("b5", 5f, 1f, spec.a)
        val a6 = notable("a6", 6f, -1.3f, n3)
        val b6 = notable("b6", 6f, 1.3f, n4)
        val m7 = minor("m7", 7f, 0f, spec.c)
        val k = place("k", NodeKind.KEYSTONE, 8.1f, 0f, keystone.effects, keystone.icon ?: icon, true, keystone.id)
        for ((x, y) in listOf(e to a1, e to b1, a1 to a2, b1 to b2, a2 to a3, b2 to b3, a3 to m4, b3 to m4,
            m4 to a5, m4 to b5, a5 to a6, b5 to b6, a6 to m7, b6 to m7, m7 to k)) link(x, y)
        link(rings[spec.slot] ?: error("Branch before ring at slot ${spec.slot}"), e)
    }

    /** Joins the ring into a loop and puts a bridge between every two neighbouring branches. */
    fun finish() {
        for (i in 0 until SkillTree.SLOTS) {
            val here = rings[i] ?: error("No ring node at slot $i")
            val next = rings[(i + 1) % SkillTree.SLOTS] ?: error("No ring node at slot ${i + 1}")
            link(here, next)
        }
        for (i in 0 until SkillTree.SLOTS) {
            val left = branches[i] ?: continue
            val right = branches[(i + 1) % SkillTree.SLOTS] ?: continue
            val from = node("${left.id}/b5")
            val to = node("${right.id}/a5")
            val half = (left.a + right.a).map { Effect(it.bonus, it.amount / 2, it.skill, it.group) }
            val effects = merge(half)
            val x = (from.x + to.x) / 2
            val y = (from.y + to.y) / 2
            val bridge = add(Node("bridge/${left.id}-${right.id}", NodeKind.MINOR, x, y, effects, glyphOf(effects), "bridge"))
            link(from, bridge)
            link(bridge, to)
        }
    }

    /** Adds up effects on the same bonus and target. */
    private fun merge(effects: List<Effect>): List<Effect> {
        val out = LinkedHashMap<Triple<Bonus, Skill?, SkillGroup?>, Double>()
        for (e in effects) out.merge(Triple(e.bonus, e.skill, e.group), e.amount, Double::plus)
        return out.map { (k, v) -> Effect(k.first, v, k.second, k.third) }
    }

    companion object {
        /** Skill level each place in a branch needs. */
        val REQUIRES = mapOf(
            "e" to 0, "a1" to 0, "b1" to 0, "a2" to 10, "b2" to 5, "a3" to 10, "b3" to 20, "m4" to 15,
            "a5" to 25, "b5" to 25, "a6" to 35, "b6" to 45, "m7" to 50, "k" to 65,
        )

        /** The small mark a minor node shows, from what its first effect is about. */
        fun glyphOf(effects: List<Effect>): String = "glyph/" + when (effects.firstOrNull()?.bonus) {
            Bonus.MAX_HEALTH, Bonus.HEALTH_PCT, Bonus.REGEN -> "health"
            Bonus.STAMINA, Bonus.STAMINA_REGEN, Bonus.ATTACK_COST, Bonus.ROLL_COST, Bonus.SPRINT_COST, Bonus.DODGE_STAMINA, Bonus.PARRY_STAMINA -> "stamina"
            Bonus.FOCUS, Bonus.FOCUS_REGEN, Bonus.FOCUS_ON_HIT, Bonus.SKILL_COST, Bonus.SPELL_COST, Bonus.SPELL_SLOTS -> "focus"
            Bonus.ARMOR, Bonus.TOUGHNESS, Bonus.POISE, Bonus.BLOCK_COST, Bonus.DAMAGE_TAKEN, Bonus.KNOCKBACK_RESIST, Bonus.ROLL_LOAD -> "defence"
            Bonus.MAGIC_TAKEN, Bonus.FIRE_TAKEN, Bonus.BLAST_TAKEN, Bonus.PROJECTILE_TAKEN, Bonus.BURN_TIME -> "resist"
            Bonus.MOVE_SPEED, Bonus.SNEAK_SPEED, Bonus.SWIM_SPEED, Bonus.JUMP, Bonus.TEMPO, Bonus.DRAW_SPEED, Bonus.SURE_FOOTED, Bonus.SAFE_FALL,
            Bonus.CAST_SPEED -> "speed"
            Bonus.CRAFT_DURABILITY, Bonus.SMELT_EXTRA, Bonus.ANVIL_COST, Bonus.POTION_DURATION, Bonus.POTION_POTENCY,
            Bonus.FOOD_SATURATION, Bonus.FOOD_HEAL, Bonus.COOK_EXTRA -> "craft"
            Bonus.STONE_SPEED, Bonus.ORE_DOUBLE, Bonus.LOG_SPEED, Bonus.LOG_DOUBLE, Bonus.DIRT_SPEED, Bonus.BREAK_SPEED,
            Bonus.CROP_EXTRA, Bonus.TWINS, Bonus.FISH_EXTRA, Bonus.FISH_TREASURE, Bonus.UNDERWATER_MINING -> "gather"
            Bonus.CONTRACT_MARKS, Bonus.TRADE_REFUND, Bonus.STANDING, Bonus.DEADLINE_DAYS -> "coin"
            Bonus.GUILD_XP, Bonus.XP_ORBS, Bonus.ENCHANT_REFUND -> "xp"
            Bonus.SKILL_XP -> "skill"
            Bonus.FLASK_HEAL, Bonus.FLASK_CHARGES -> "flask"
            Bonus.STEALTH, Bonus.BACKSTAB_DAMAGE, Bonus.AMBUSH_DAMAGE -> "stealth"
            Bonus.LUCK -> "luck"
            Bonus.BLOCK_REACH, Bonus.ENTITY_REACH -> "reach"
            else -> "damage"
        }
    }
}
