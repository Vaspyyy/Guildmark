package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Memorials
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * The Brother Under the Hill's scenes (living-world/stories/design.md 8.12), each built from the sets' own moves (Tales B):
 *
 *  - **At the forge** (S1's variant, the seed scene by day): the smith sets the work down, looks off toward the gate on the mine's side and
 *    wrings their hands; a passer-by comes up with a word (`tale.set.comfort`) and the smith answers with who is missing (`tale.brother.passing`).
 *  - **The calling** (S2, the seed scene from 15:30): the smith calls the name at the gate, a neighbour comes and asks, and the captain points
 *    down the road toward the old workings.
 *  - **The rise** (C): in the mine, the one below sees the fire's glow, makes for it with the dark lamp wavering along the corridor, holds the
 *    lamp to the flame, and turns to whoever lit it (`tale.brother.rise`).
 *  - **The homecoming** (S6 at the forge door, A and C): the smith looks out, runs to them and they embrace; the smith thanks whoever brought
 *    them home; the street claps.
 *  - **The Return** (B, at first light): the party comes up the street with them between two delvers, torches still lit; the first delver
 *    tells the smith (`tale.brother.delver_back`), the smith runs to them, the delvers salute and go.
 *  - **The laying** (S5 at the stone by the forge door, W): the smith kneels and lays a posy, the priest prays, a neighbour embraces them, and
 *    the smith's own word to the stone (`tale.brother.cairn`) ends it.
 *
 * The seed scenes are the tale's own (the engine asks [Brother.scene]); [BrotherWatch] plays the rise where it stands (no capital's stage
 * reaches the mine) and queues the homecoming, the Return and the laying on the capital's stage.
 */
internal object BrotherScenes {
    /** The market day each tale's smith was last seen at the forge and at the gate this run: once a day each is enough for the street. */
    private val forged = HashMap<Int, Long>()
    private val called = HashMap<Int, Long>()

    private fun eye(b: Townsperson): () -> Vec3? = { b.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }

    private fun eye(p: ServerPlayer?, or: Townsperson): () -> Vec3? =
        { p?.takeIf { it.isAlive && !it.isRemoved }?.eyePosition ?: or.eyePosition.add(or.lookAngle.scale(2.0)) }

    /** How near the gate's front the smith must be for the calling, and the captain and the helper to be in it. */
    private const val AT_GATE = 8.0
    private const val NEAR_GATE = 40.0

    /** How near their work the smith must be to be seen at the forge. */
    private const val AT_FORGE = 24.0

    // ---- The seed scenes ----

    /** The tale's seed scene while they are below: the calling at the gate from 15:30 to 19:00, else the forge from 07:00 to 15:30. */
    fun waiting(c: Ctx, t: Tale): Stage? {
        val now = Brother.clock(c.level)
        if (now in Brother.CALL_FROM until Brother.CALL_TO) return calling(c, t)
        if (now in Brother.FORGE_FROM until Brother.CALL_FROM) return forge(c, t)
        return null
    }

    /** Where down the road the smith looks: twenty blocks out on the mine's side, at the height of a calling voice. */
    private fun road(c: Ctx, t: Tale): Vec3 {
        val front = Brother.front(c, t)
        val gate = Brother.gate(t)
        val far = WayOut.outward(c.level, c.here, front, 20).lastOrNull()
            ?: gate?.let { front.relative(c.here.capital.direction(it), 16) }
            ?: Brother.lair(c.level, t)?.pos
            ?: front
        return Vec3.atCenterOf(far).add(0.0, 1.0, 0.0)
    }

    /** S2 at the gate: once a day, with the smith held at its front. */
    private fun calling(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (Brother.askerOf(t) != Brother.SMITH || called[t.id] == c.day) return null
        val smith = TaleBodies.body(level, t, Brother.SMITH)?.takeIf { it.tale == t.id } ?: return null
        val front = Brother.front(c, t)
        val at = Vec3.atCenterOf(front)
        if (smith.distanceToSqr(at) > AT_GATE * AT_GATE) return null
        val helper = TaleBodies.body(level, t, Brother.HELPER)?.takeIf { it !== smith && it.distanceToSqr(at) < NEAR_GATE * NEAR_GATE }
        val captain = TaleBodies.body(level, t, Brother.CAPTAIN)?.takeIf { it !== smith && it !== helper && it.distanceToSqr(at) < NEAR_GATE * NEAR_GATE }
        val stage = callingAt(smith, Brother.given(Brother.brotherName(t)), front, road(c, t), helper, captain) ?: return null
        stage.then = { played -> if (played) called[t.id] = c.day }
        return stage
    }

    /**
     * The calling itself ([Sets.calling]): [smith] calls [name] down the road toward [down], [helper] (a neighbour) comes and asks which way,
     * and [captain], standing by, points down it as the smith does. Null if there is nowhere to stand at [front].
     */
    fun callingAt(smith: Townsperson, name: String, front: BlockPos, down: Vec3, helper: Townsperson?, captain: Townsperson?): Stage? {
        val base = Sets.calling(smith, name, front, down, listOfNotNull(helper), hours = null) ?: return null
        if (captain == null || base.parts.size >= TaleLimits.STAGE_PARTS) return base
        val look = { down }
        val m = ArrayList<Move>()
        m += Turn(eye(smith))
        if (helper != null) {
            // While the neighbour asks the way, the captain looks on; as the smith points, so does the captain
            m += Stage.Await("asked", 1500, Activity.STAND_GUARD, eye(smith))
        } else {
            m += Wait({ _, tick -> tick >= 240 }, most = 260, pose = Activity.STAND_GUARD, at = eye(smith))
        }
        m += Turn(look)
        m += Play(Activity.POINT, at = look)
        m += Play(Activity.NOD, at = eye(smith))
        m += Play(Activity.STAND_GUARD, 80, at = look)
        val parts = base.parts + Stage.Part(Brother.CAPTAIN, captain, essential = false, moves = m)
        return Stage("brother_calling", parts, null, Vec3.atCenterOf(front), extras = base.extras)
    }

    /** S1 at the forge: once a day, the smith near their work. */
    private fun forge(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (Brother.askerOf(t) != Brother.SMITH || forged[t.id] == c.day) return null
        val smith = TaleBodies.body(level, t, Brother.SMITH) ?: return null
        val door = Brother.forgeOr(c, t)
        if (smith.distanceToSqr(Vec3.atCenterOf(door)) > AT_FORGE * AT_FORGE) return null
        val stage = forgeAt(smith, road(c, t))
        stage.then = { played -> if (played) forged[t.id] = c.day }
        return stage
    }

    /**
     * The smith at the forge, where they stand ([Stage.Part.station]): they set the work down (PAUSE_WORK), look off toward [toward] (the gate
     * on the mine's side) and wring their hands; a passer-by of the street comes up with a word (`tale.set.comfort`) and the smith answers
     * (`tale.brother.passing`), sighs, and goes back to the work. About twenty-five seconds.
     */
    fun forgeAt(smith: Townsperson, toward: Vec3): Stage {
        val level = smith.level() as ServerLevel
        val off = { toward }
        val passer = Sets.passerBy(smith, r = 24.0)
        val by = passer?.let { Sets.beside(level, smith.position(), it.position(), 1.6) }
        val s = ArrayList<Move>()
        s += Play(Activity.PAUSE_WORK)
        s += Turn(off, 20)
        s += Play(Activity.LOOK_ABOUT, at = off)
        s += Play(Activity.WRING_HANDS, at = off)
        val parts = ArrayList<Stage.Part>()
        if (passer != null && by != null) {
            s += Stage.Barrier("meet", 500, Activity.WRING_HANDS, eye(passer))
            s += Turn(eye(passer))
            s += Stage.Barrier("said", 300, Activity.LISTEN, eye(passer))
            s += Stage.Say("tale.brother.passing", gesture = Activity.SIGH, at = eye(passer))
            s += Stage.Barrier("answered", 200, at = eye(passer))
            val p = ArrayList<Move>()
            p += GoTo({ by }, Jobs.STROLL, near = 1.2, most = 500)
            p += Turn(eye(smith))
            p += Stage.Barrier("meet", 500, at = eye(smith))
            p += Stage.Say("tale.set.comfort", gesture = Activity.TALK, at = eye(smith))
            p += Stage.Barrier("said", 300, at = eye(smith))
            p += Stage.Barrier("answered", 300, Activity.LISTEN, eye(smith))
            p += Play(Activity.NOD, at = eye(smith))
            parts += Stage.Part("passer", passer, essential = false, moves = p)
        } else {
            s += Play(Activity.SIGH, at = off)
        }
        s += Turn(off)
        s += Play(Activity.LOOK_ABOUT, at = off)
        parts.add(0, Stage.Part(Brother.SMITH, smith, moves = s, station = true))
        return Stage("brother_forge", parts, null, smith.position())
    }

    // ---- The rise (C) ----

    /**
     * The one below ([b]) sees the glow of the [fire] a player lit: they look about, turn to it, and make for it along the corridor with the
     * dark lamp held before them (CARRY, so the lamp is seen moving in the dark), at a stroll; by the fire they hold the lamp to the flame and
     * it is lit (the small flame), turn to [player] (whoever is near) and say `tale.brother.rise`, and sigh. Twenty to forty seconds. Null if
     * there is nowhere to stand by the fire.
     */
    fun rise(b: Townsperson, fire: BlockPos, player: ServerPlayer?): Stage? {
        val level = b.level() as ServerLevel
        val flame = Vec3.atCenterOf(fire)
        val spot = Sets.beside(level, flame, b.position(), 1.8) ?: return null
        val glow = { flame.add(0.0, 0.4, 0.0) }
        val you = eye(player, b)
        val m = ArrayList<Move>()
        m += Stage.Hold(Sets.prop("miners_lamp_dark"))
        m += Play(Activity.LOOK_ABOUT, at = glow)
        m += Turn(glow, 20)
        m += GoTo({ spot }, Jobs.STROLL, near = 1.0, overlay = Activity.CARRY, most = 900)
        m += Turn(glow)
        m += Play(Activity.HOLD_OUT, at = glow)
        m += Stage.Hold(Sets.prop("miners_lamp_lit"))
        m += Stage.Fx(Stage.Fx.Kind.CANDLE, { p -> p.position().add(p.lookAngle.x * 0.6, 1.1, p.lookAngle.z * 0.6) })
        m += Play(Activity.CATCH_BREATH, 60, at = glow)
        m += Turn(you)
        m += Stage.Say("tale.brother.rise", at = you)
        m += Play(Activity.SIGH, at = you)
        m += Wait({ _, _ -> false }, most = 60, pose = Activity.CARRY, at = glow)
        return Stage("brother_rise", listOf(Stage.Part(Brother.BROTHER, b, moves = m)), null, b.position(), most = 2400)
    }

    // ---- The homecoming (S6, A and C) ----

    /**
     * S6 The Reunion at the forge door, the brother's way: [smith] looks out for [home] (LOOKOUT); as they come near (hobbling, the walk of one
     * hurt below) the smith starts, runs to them and they embrace, the smith with `tale.set.home`, they with `tale.brother.home`; then the
     * smith turns to [player], if anyone brought them home, and thanks them (`tale.brother.thanks`, THANK) and bows. The [watchers] (up to three
     * neighbours) clap. About twenty-five seconds. Null if there is nowhere to stand.
     */
    fun homecoming(smith: Townsperson, home: Townsperson, player: ServerPlayer?, watchers: List<Townsperson>): Stage? {
        val level = smith.level() as ServerLevel
        if (Sets.standable(level, home.blockPosition()) == null && Sets.standable(level, smith.blockPosition()) == null) return null
        val joy = { p: Townsperson -> Stage.Fx.play(level, Stage.Fx.Kind.HAPPY, p.position().add(0.0, 1.5, 0.0)) }
        val you = { player?.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }
        val w = ArrayList<Move>()
        w += Turn(eye(home))
        w += Wait({ p, _ -> home.isAlive && home.distanceTo(p) < 14.0 }, most = 1500, pose = Activity.LOOKOUT, at = eye(home))
        w += Play(Activity.JUMP, at = eye(home))
        w += GoTo({ Sets.beside(level, home.position(), smith.position(), 1.4) }, Jobs.BRISK, near = 1.1, most = 400)
        w += Turn(eye(home))
        w += Stage.Barrier("hug", most = 300, at = eye(home))
        var wSaid = false
        w += Stage.Beat(Activity.EMBRACE, at = eye(home)) { p, tick ->
            if (!wSaid && tick >= 4) wSaid = Words.sayWith(p, "tale.set.home", why = "a homecoming") != null
            if (tick % 12 == 0) joy(p)
        }
        w += Stage.Barrier("hugged", most = 160, at = eye(home))
        if (player != null) {
            w += Turn(you)
            w += Stage.Say("tale.brother.thanks", gesture = Activity.THANK, at = you)
            w += Play(Activity.BOW, at = you)
        }
        val r = ArrayList<Move>()
        r += Stage.Hold(ItemStack.EMPTY)
        r += GoTo(
            { if (home.distanceTo(smith) > 10.0) Sets.beside(level, smith.position(), home.position(), 8.0) else null },
            Jobs.STROLL, near = 1.0, overlay = Activity.HOBBLE, most = 1200,
        )
        r += Turn(eye(smith))
        r += Stage.Barrier("hug", most = 1800, at = eye(smith))
        var rSaid = false
        r += Stage.Beat(Activity.EMBRACE, at = eye(smith)) { p, tick ->
            if (!rSaid && tick >= 24) rSaid = Words.sayWith(p, "tale.brother.home", why = "a homecoming") != null
            if (tick % 12 == 6) joy(p)
        }
        r += Stage.Barrier("hugged", most = 160, at = eye(smith))
        r += Play(Activity.CATCH_BREATH, 60, at = eye(smith))
        val parts = listOf(Stage.Part(Brother.SMITH, smith, moves = w), Stage.Part(Brother.BROTHER, home, moves = r))
        val extras = ArrayList<Stage.Part>()
        for ((i, o) in watchers.filter { it !== smith && it !== home }.take(3).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(eye(home))
            m += Stage.Await("hug", 2400, Activity.GAZE, eye(home))
            m += Wait({ _, tick -> tick >= 5 * i }, most = 20)
            m += Play(Activity.CLAP, at = eye(home))
            m += Play(if (i == 0) Activity.CHEER else Activity.NOD, at = eye(home))
            extras += Stage.Part("watcher${i + 1}", o, essential = false, moves = m)
        }
        return Stage("brother_home", parts, null, smith.position(), extras = extras)
    }

    // ---- The Return (B) ----

    /**
     * The Return, at first light: [home] comes up the street hobbling with the three delvers about them, [lead] in front with a torch, [two]
     * and [three] (a torch, a coil of rope) a step behind on either side, all at the one slow pace; [smith] waits at the door looking out.
     * When they are there the lead tells the smith (`tale.brother.delver_back`, EXPLAIN), the smith runs to them and they embrace, the smith
     * thanks [player] if one paid and is near; the delvers salute and walk off toward [away] (they are let go there, out by the gate). About
     * forty seconds. Null if there is nowhere to stand.
     */
    fun ret(
        smith: Townsperson, home: Townsperson, lead: Townsperson, two: Townsperson?, three: Townsperson?, player: ServerPlayer?, away: BlockPos,
    ): Stage? {
        val level = smith.level() as ServerLevel
        val door = smith.position()
        val from = home.position()
        var ax = from.x - door.x
        var az = from.z - door.z
        val len = kotlin.math.sqrt(ax * ax + az * az).coerceAtLeast(0.1)
        ax /= len
        az /= len
        val rx = -az
        val rz = ax
        fun spot(along: Double, across: Double): BlockPos? =
            Sets.standable(level, BlockPos.containing(door.x + ax * along + rx * across, door.y, door.z + az * along + rz * across))
        val homeAt = spot(2.0, 0.0) ?: return null
        val leadAt = spot(2.2, 1.6) ?: homeAt
        val twoAt = spot(3.4, -1.4) ?: homeAt
        val threeAt = spot(3.8, 1.2) ?: homeAt
        val you = { player?.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }
        val joy = { p: Townsperson -> Stage.Fx.play(level, Stage.Fx.Kind.HAPPY, p.position().add(0.0, 1.5, 0.0)) }
        // The smith
        val s = ArrayList<Move>()
        s += Turn(eye(home))
        s += Stage.Barrier("in", 1800, Activity.LOOKOUT, eye(home))
        s += Play(Activity.JUMP, at = eye(home))
        s += Stage.Barrier("told", 400, Activity.LISTEN, eye(lead))
        s += GoTo({ Sets.beside(level, home.position(), smith.position(), 1.4) }, Jobs.BRISK, near = 1.1, most = 300)
        s += Turn(eye(home))
        s += Stage.Barrier("hug", 300, at = eye(home))
        var sSaid = false
        s += Stage.Beat(Activity.EMBRACE, at = eye(home)) { p, tick ->
            if (!sSaid && tick >= 4) sSaid = Words.sayWith(p, "tale.set.home", why = "the delvers' return") != null
            if (tick % 12 == 0) joy(p)
        }
        s += Stage.Barrier("hugged", 160, at = eye(home))
        if (player != null) {
            s += Turn(you)
            s += Stage.Say("tale.brother.thanks", gesture = Activity.THANK, at = you)
            s += Play(Activity.BOW, at = you)
        }
        // The one brought home
        val h = ArrayList<Move>()
        h += Stage.Hold(ItemStack.EMPTY)
        h += GoTo({ homeAt }, Jobs.STROLL, near = 1.0, overlay = Activity.HOBBLE, most = 1800)
        h += Turn(eye(smith))
        h += Stage.Barrier("in", 1800, at = eye(smith))
        h += Stage.Barrier("told", 400, Activity.CATCH_BREATH, eye(smith))
        h += Stage.Barrier("hug", 300, at = eye(smith))
        var hSaid = false
        h += Stage.Beat(Activity.EMBRACE, at = eye(smith)) { p, tick ->
            if (!hSaid && tick >= 24) hSaid = Words.sayWith(p, "tale.brother.home", why = "the delvers' return") != null
            if (tick % 12 == 6) joy(p)
        }
        h += Stage.Barrier("hugged", 160, at = eye(smith))
        h += Play(Activity.NOD, at = eye(smith))
        // The lead delver
        val l = ArrayList<Move>()
        l += Stage.Hold(ItemStack(Items.TORCH))
        l += GoTo({ leadAt }, Jobs.STROLL, near = 1.0, overlay = Activity.CARRY, most = 1800)
        l += Turn(eye(smith))
        l += Stage.Barrier("in", 1800, Activity.CARRY, eye(smith))
        l += Stage.Say("tale.brother.delver_back", gesture = Activity.EXPLAIN, at = eye(smith))
        l += Stage.Barrier("told", 400, Activity.CARRY, eye(smith))
        l += Turn(eye(home))
        l += Stage.Barrier("hugged", 900, Activity.CARRY, eye(home))
        l += Play(Activity.SALUTE, at = eye(smith))
        l += GoTo({ away }, Jobs.WALK, near = 1.5, overlay = Activity.CARRY, most = 500)
        val parts = ArrayList<Stage.Part>()
        parts += Stage.Part(Brother.SMITH, smith, moves = s)
        parts += Stage.Part(Brother.BROTHER, home, moves = h)
        parts += Stage.Part(Brother.DELVER, lead, moves = l)
        for ((k, d) in listOfNotNull(two, three).withIndex()) {
            val at = if (k == 0) twoAt else threeAt
            val m = ArrayList<Move>()
            m += Stage.Hold(if (k == 0) ItemStack(Items.TORCH) else ItemStack(Items.LEAD))
            m += GoTo({ at }, Jobs.STROLL, near = 1.0, overlay = Activity.CARRY, most = 1800)
            m += Turn(eye(smith))
            m += Stage.Barrier("in", 1800, Activity.CARRY, eye(smith))
            m += Turn(eye(home))
            m += Stage.Barrier("hugged", 900, Activity.CARRY, eye(home))
            m += Wait({ _, tick -> tick >= 6 * (k + 1) }, most = 20)
            m += Play(Activity.SALUTE, at = eye(smith))
            m += GoTo({ away }, Jobs.WALK, near = 2.0, overlay = Activity.CARRY, most = 500)
            parts += Stage.Part(if (k == 0) Brother.DELVER2 else Brother.DELVER3, d, essential = false, moves = m)
        }
        return Stage("brother_return", parts, null, door)
    }

    // ---- The laying (W) ----

    /**
     * S5 The Laying at the stone by the forge door ([Sets.laying]): [smith] lays a posy, [priest] prays, [helper] embraces them; then the smith
     * turns to the stone and says their word to it (`tale.brother.cairn`, the name in grey), grieving. [rec] is the stone's record (null: a
     * stand-in that sets nothing). Null if there is nowhere to stand by it.
     */
    fun laying(smith: Townsperson, stone: BlockPos, helper: Townsperson?, priest: Townsperson?, name: String, rec: TaleData.Memorial?): Stage? {
        val base = Sets.laying(smith, stone, listOfNotNull(helper), priest, prayer = null, memorial = rec, hours = null) ?: return null
        val at = { Vec3.atCenterOf(stone).add(0.0, 0.5, 0.0) }
        val detail = TaleLines.facts("talk.detail.tale.brother.cairn", TaleLines.lit(name))
        val parts = base.parts.map { part ->
            if (part.slot != "mourner") part
            else Stage.Part(part.slot, part.body, part.essential, part.moves + listOf(Turn(at), Stage.Say("tale.brother.cairn", detail, gesture = Activity.GRIEVE, at = at)), part.station, part.seated)
        }
        return Stage("brother_laying", parts, null, Vec3.atCenterOf(stone), extras = base.extras)
    }

    /** The laying of [t] now, if the smith is near the stone (and the stone is on record); null otherwise. */
    fun layingOf(c: Ctx, t: Tale): Stage? {
        val level = c.level
        val smith = TaleBodies.body(level, t, Brother.SMITH) ?: return null
        val rec = Memorials.of(c, t).firstOrNull { it.kind == "stone" }
        val stone = rec?.pos ?: Brother.forge(t) ?: return null
        if (smith.distanceToSqr(Vec3.atCenterOf(stone)) > 48.0 * 48.0) return null
        val near = Vec3.atCenterOf(stone)
        val helper = TaleBodies.body(level, t, Brother.HELPER)?.takeIf { it !== smith && it.distanceToSqr(near) < 48.0 * 48.0 }
        val priest = TaleBodies.body(level, t, Brother.PRIEST)?.takeIf { it !== smith && it !== helper && it.distanceToSqr(near) < 64.0 * 64.0 }
        return laying(smith, stone, helper, priest, Brother.brotherName(t), rec)
    }

    /** The server stopped: what was played is forgotten. */
    fun forget() {
        forged.clear()
        called.clear()
    }

    /** [id] ended: its days are forgotten. */
    fun ended(id: Int) {
        forged.remove(id)
        called.remove(id)
    }
}
