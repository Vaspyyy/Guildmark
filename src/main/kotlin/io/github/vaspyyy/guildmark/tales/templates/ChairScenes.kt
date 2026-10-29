package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.townsfolk.streets.Streets
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Memorials
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.TaleSounds
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3

/**
 * What the Empty Chair shows (living-world/stories/design.md 8.2, 4.3):
 *
 *  - **the doorstep** (step 0, 17:00 to 20:00, the mourner held on their doorstep, seated where there is a seat): S1 with the mourner's own
 *    clips, SIT (GRIEVE standing) and SIGH, a neighbour who knew them (else the nearest free passer-by) walking up with a comforting word
 *    (`tale.set.comfort`), and the mourner answering it (`tale.chair.passing`). About 40 seconds.
 *  - **the Laying** (step 4, at dusk, 17:00 to 19:30, on the laying's day, and as the ending's scene when it was not seen): S5 at the stone
 *    ([Sets.laying]) with, for an A, the three friends coming up one after another to set a candle each on the stone's ledge and light it
 *    (PUT_DOWN, the candle's flame, `tale.candle_light`), the temple's bell tolling three times while the priest prays (`tale.chair.laying`,
 *    the dead's name in grey) if a bell is near, the mourner kneeling and laying the posy, a lantern set beside it at dusk, the first friend
 *    embracing the mourner, and the mourner turning to the player to thank them (`tale.chair.thanks`). A B has those who said a word; a C the
 *    mourner and the priest. The candles not lit by a friend are set and lit as the posy goes down.
 *
 * The stone is Part F's ([Chair.memorial]); nothing here sets it but the posy, the candles and the lantern, and when no laying is seen they are
 * set at once ([atOnce]) as F's "with nobody by" says. W has no scene.
 */
internal object ChairScenes {
    /** The doorstep's hours, the laying's (at dusk), and the hours the ending's laying may still be played in when it was not seen. */
    val DOOR: ClosedFloatingPointRange<Double> = 17.0..20.0
    val LAYING: ClosedFloatingPointRange<Double> = 17.0..19.5
    private val ENDED: ClosedFloatingPointRange<Double> = 6.0..22.0

    /** How near the stone the cast must be for the laying to take them, and the mourner (held there) for it to begin. */
    private const val NEAR_STONE = 40.0
    private const val AT_STONE = 12.0

    /** How far off a friend who comes may be when the laying begins: they walk up to it from there (about thirty seconds at the most). */
    private const val FRIENDS_REACH = 64.0

    /** How near the stone a player must be for the ending's laying to be played (`TaleLook.queueEnding`'s reach). */
    private const val SEEN = TaleLook.NEAR

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    private fun top(stone: BlockPos): Vec3 = Vec3(stone.x + 0.5, stone.y + 1.05, stone.z + 0.5)

    // ---- The tale's asks ----

    /** The scene due now ([Chair.scene]): the doorstep at step 0, the laying at step 4 at dusk on its day, the ending's laying once ended. */
    fun scene(c: Ctx, t: Tale): Stage? {
        if (!t.open) return ending(c, t)
        return when (t.step) {
            Chair.AT_ASK -> doorstep(c, t)
            Chair.AT_LAY -> laying(c, t)
            else -> null
        }
    }

    private fun doorstep(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!Stage.within(level, DOOR)) return null
        val door = Chair.door(t, Chair.MOURNER) ?: return null
        if (t.cast(Chair.MOURNER)?.held != true) return null
        val mourner = TaleBodies.body(level, t, Chair.MOURNER)?.takeIf { it.distanceToSqr(Vec3.atBottomCenterOf(door)) <= 4.0 * 4.0 } ?: return null
        // One who knew them, if one is about; else whoever passes
        val passer = Chair.FRIENDS.firstNotNullOfOrNull { slot ->
            TaleBodies.body(level, t, slot)?.takeIf { it !== mourner && it.tale < 0 && it.distanceToSqr(mourner) <= 24.0 * 24.0 }
        } ?: Sets.passerBy(mourner)
        return count(mourner, passer, DOOR)
    }

    private fun laying(c: Ctx, t: Tale): Stage? {
        val level = c.level
        val forced = ChairWatch.forced(t)
        if (!forced && (c.day != Chair.layDay(t) || !Stage.within(level, LAYING))) return null
        val letter = Chair.letter(t)
        if (letter == Ending.W) return null
        val gather = Chair.place(t) ?: return null
        val mourner = TaleBodies.body(level, t, Chair.MOURNER)?.takeIf { it.distanceToSqr(Vec3.atCenterOf(gather)) <= AT_STONE * AT_STONE } ?: return null
        // The stone is set as they come (Part F): it stands before the laying kneels to it
        val rec = Chair.memorial(c, t)
        val stone = rec?.pos ?: gather
        val stage = build(c, t, mourner, stone, rec, letter, if (forced) null else LAYING) ?: return null
        val id = t.id
        val capital = t.capital
        stage.then = { played ->
            if (played && "laid" in stage.passed) {
                val tale = TaleData.get(level)[id]
                val cc = TaleEngine.ctx(level, capital)
                if (tale != null && tale.open && cc != null) Chair.laid(cc, tale)
            }
        }
        ChairWatch.playing(t, stage)
        return stage
    }

    /** The ending's laying, for an ending not seen at dusk (the close, `tale end`): null for W, for one already seen, or with the mourner not by. */
    private fun ending(c: Ctx, t: Tale): Stage? {
        if (Chair.flag(t, Chair.LAID) || t.ending == Ending.W || t.ending.isEmpty()) return null
        val level = c.level
        val rec = record(c, t)
        val stone = rec?.pos ?: Chair.stoneAt(c, t) ?: return null
        val mourner = TaleBodies.body(level, t, Chair.MOURNER)?.takeIf { it.distanceToSqr(Vec3.atCenterOf(stone)) <= NEAR_STONE * NEAR_STONE } ?: return null
        val stage = build(c, t, mourner, stone, rec, t.ending, ENDED) ?: return null
        val id = t.id
        val capital = t.capital
        // Not taken, or broken off before the posy: what the laying leaves is set all the same
        stage.then = { played ->
            if (!played || "laid" !in stage.passed) {
                val tale = TaleData.get(level)[id]
                val cc = TaleEngine.ctx(level, capital)
                if (tale != null && cc != null) atOnce(cc, tale, record(cc, tale), tale.ending)
            }
        }
        return stage
    }

    /** The stone of [t] on record, if it has one. */
    private fun record(c: Ctx, t: Tale): TaleData.Memorial? = Memorials.of(c, t).firstOrNull { it.names == listOf(Chair.dead(t)) }

    /**
     * Whether the ending's laying of [t] can be seen: scenes on, within its hours (a stage asked outside them is dropped without a word, and
     * what it would set with it), the mourner by the stone and a player near enough to see it.
     */
    fun willPlay(c: Ctx, t: Tale, rec: TaleData.Memorial?): Boolean {
        if (!Tales.scenes() || !Stage.within(c.level, ENDED)) return false
        val stone = rec?.pos ?: Chair.stoneAt(c, t) ?: return false
        val at = Vec3.atCenterOf(stone)
        val mourner = TaleBodies.body(c.level, t, Chair.MOURNER) ?: return false
        if (mourner.distanceToSqr(at) > NEAR_STONE * NEAR_STONE) return false
        return c.level.getNearestPlayer(at.x, at.y, at.z, SEEN, false) != null
    }

    /** The laying of [t] with ending [letter]: who comes by the letter, the candles from the record (three for an A), the nearest player thanked. */
    private fun build(
        c: Ctx, t: Tale, mourner: Townsperson, stone: BlockPos, rec: TaleData.Memorial?, letter: String, hours: ClosedFloatingPointRange<Double>?,
    ): Stage? {
        val level = c.level
        val at = Vec3.atCenterOf(stone)
        fun near(slot: String, r: Double): Townsperson? =
            TaleBodies.body(level, t, slot)?.takeIf { it !== mourner && it.distanceToSqr(at) <= r * r }
        val priest = near(Chair.PRIEST, NEAR_STONE)
        // Only one friend is held by the stone (a tale holds three): the others come from wherever they are, at their own pace
        val friends = Chair.attending(t, letter).mapNotNull { near(it, FRIENDS_REACH) }
        val candles = if (letter == Ending.A) Chair.ALL else t.n[Chair.N_CANDLES]
        val player = level.getNearestPlayer(at.x, at.y, at.z, 24.0, false)
        val id = t.id
        val capital = t.capital
        val steps = {
            val tale = TaleData.get(level)[id]
            val cc = TaleEngine.ctx(level, capital)
            if (tale != null && cc != null) cc.why(tale, "chair.steps")
        }
        return laying(level, mourner, stone, rec, priest, friends, candles, Chair.dead(t), player, hours, steps)
    }

    // ---- S1, the doorstep ----

    /**
     * The doorstep: [mourner] in their seat (or standing at the door) sits and sighs, grieving; [passer] (when there is one) walks up,
     * nods and says a comforting word; the mourner turns to them and answers that the house is very quiet, and goes back to grieving.
     */
    fun count(mourner: Townsperson, passer: Townsperson?, hours: ClosedFloatingPointRange<Double>?): Stage {
        val level = mourner.level() as ServerLevel
        val seated = mourner.seat != null
        val grieve = Sets.fit(mourner, Activity.GRIEVE)
        val spot = passer?.let { Sets.beside(level, mourner.position(), it.position(), 2.0) }
        val k = ArrayList<Move>()
        k += Play(if (seated) Activity.SIT else Activity.GRIEVE, 100)
        k += Play(Activity.SIGH)
        k += Play(grieve, 80)
        val parts = ArrayList<Stage.Part>()
        if (passer != null && spot != null) {
            k += Barrier("meet", most = 260, at = eye(passer))
            k += Turn(eye(passer))
            k += Barrier("said", most = 300, pose = if (seated) Activity.SIT else Activity.LISTEN, at = eye(passer))
            k += Say("tale.chair.passing", gesture = Activity.SIGH, at = eye(passer))
            k += Barrier("answered", most = 200, at = eye(passer))
            // The walk up is timed to end as the first round does (about eight ticks a block at a stroll)
            val delay = (220 - 8 * passer.distanceTo(mourner)).toInt().coerceAtLeast(0)
            val p = ArrayList<Move>()
            p += Wait({ _, tick -> tick >= delay }, most = delay + 1)
            p += GoTo({ spot }, Jobs.STROLL, near = 1.2, most = 500)
            p += Turn(eye(mourner))
            p += Barrier("meet", most = 260, at = eye(mourner))
            p += Play(Activity.NOD, at = eye(mourner))
            p += Say("tale.set.comfort", gesture = Activity.TALK, at = eye(mourner))
            p += Barrier("said", most = 300, at = eye(mourner))
            p += Barrier("answered", most = 260, pose = Activity.LISTEN, at = eye(mourner))
            p += Play(Activity.NOD, at = eye(mourner))
            parts += Stage.Part("passer", passer, false, p)
        }
        k += Play(Activity.SIGH)
        k += Play(grieve, 80)
        parts.add(0, Stage.Part("mourner", mourner, true, k, station = seated || mourner.routine.anchored, seated = seated))
        return Stage("chair_count", parts, hours, mourner.position())
    }

    // ---- S5, the Laying ----

    /**
     * The Laying at [stone] ([rec] the memorial there, or null for a stand-in that sets nothing): [Sets.laying] with [priest] praying
     * (`tale.chair.laying`, [dead] in grey) and [friends] about, and to it the chair's own: the first [candles] of the friends each set a
     * candle on the stone and light it before the ring forms (the rest are set and lit as the posy goes down), the town's bell tolls if one
     * is near the stone, and the mourner thanks [player] at the end. [onSteps] hears that the posy went on the steps (no room for the stone);
     * [front] is the side the mourner kneels on, as [Sets.laying] takes it. Null if there is nowhere to stand by the stone.
     */
    fun laying(
        level: ServerLevel, mourner: Townsperson, stone: BlockPos, rec: TaleData.Memorial?, priest: Townsperson?, friends: List<Townsperson>,
        candles: Int, dead: String, player: Player?, hours: ClosedFloatingPointRange<Double>?, onSteps: (() -> Unit)? = null,
        front: Direction? = null,
    ): Stage? {
        val prayer = Sets.Line("tale.chair.laying", TaleLines.facts("talk.detail.tale.chair.laying", TaleLines.lit(dead)))
        val lighters = friends.take(candles.coerceIn(0, friends.size))
        val base = Sets.laying(
            mourner, stone, friends, priest, prayer, rec, front,
            onLaid = { _, onSteps2 ->
                if (candles > 0) light(level, rec, stone, candles, sound = candles > lighters.size)
                if (onSteps2) onSteps?.invoke()
            },
            hours = hours,
        ) ?: return null
        val top = top(stone)
        val bell = if (priest != null) Sets.bellNear(level, stone, 24) else null
        val parts = base.parts.map { part ->
            val body = part.body
            val i = lighters.indexOf(body)
            val moves: List<Move> = when {
                body === mourner -> part.moves + thanks(player)
                i >= 0 -> lit(level, body, i, stone, rec) + part.moves
                body === priest && bell != null -> tolled(level, part.moves, Vec3.atCenterOf(bell), top)
                else -> part.moves
            }
            Stage.Part(part.slot, body, part.essential, moves, part.station, part.seated)
        }
        return Stage("chair_laying", parts, base.hours, base.anchor(), base.most + 400, base.extras)
    }

    /** Friend number [i] of the lighters walks up to the stone, sets a candle on its ledge and lights it (the [i]+1th), and bows. */
    private fun lit(level: ServerLevel, body: Townsperson, i: Int, stone: BlockPos, rec: TaleData.Memorial?): List<Move> {
        val top = top(stone)
        val spot = Sets.beside(level, Vec3.atCenterOf(stone), body.position(), 1.4)
        return listOf(
            Hold(ItemStack(Items.CANDLE)),
            Wait({ _, tick -> tick >= 14 * i }, most = 14 * i + 1),
            GoTo({ spot }, Jobs.WALK, near = 0.7, most = 700),
            Turn({ top }),
            Beat(Activity.PUT_DOWN, at = { top }) { p, tick ->
                if (tick == 10) {
                    Stage.give(p, null)
                    light(level, rec, stone, i + 1, sound = true)
                }
            },
            Play(Activity.BOW, at = { top }),
        )
    }

    /** [moves] with the town's bell at [bell] tolling three times, slowly, as the ring has formed and the priest begins to pray. */
    private fun tolled(level: ServerLevel, moves: List<Move>, bell: Vec3, top: Vec3): List<Move> {
        val ring = moves.indexOfFirst { it is Barrier && it.name == "ring" }
        if (ring < 0) return moves
        val toll = Beat(Activity.PRAY, 90, at = { top }) { _, tick ->
            if (tick == 0 || tick == 40 || tick == 80) Fx.sound(level, SoundEvents.BELL_BLOCK, bell, 1.4f, 0.75f)
        }
        return moves.subList(0, ring + 1) + toll + moves.subList(ring + 1, moves.size)
    }

    /** The mourner turns to [player] and thanks them (A to C), if one stands by. */
    private fun thanks(player: Player?): List<Move> {
        if (player == null) return listOf()
        val eye = { player.takeIf { !it.isRemoved }?.eyePosition }
        return listOf(Turn(eye), Say("tale.chair.thanks", gesture = Activity.THANK, at = eye))
    }

    /**
     * Candles on the ledge of [rec]'s stone until [count] stand, lit until dawn (Part F's [Memorials.candles], which plays the lighting for the
     * first), with the candle's small flame; with [sound] the lighting is heard for each one (`tale.candle_light`). Only the flame and the
     * sound for a stand-in stone, or with the memorials' blocks off.
     */
    private fun light(level: ServerLevel, rec: TaleData.Memorial?, stone: BlockPos, count: Int, sound: Boolean) {
        val top = top(stone)
        Fx.play(level, Fx.Kind.CANDLE, top)
        val standing = rec != null && rec.id >= 0 && Tales.memorials() && Memorials.candles(level, rec.id, count, true)
        if (sound && (!standing || count > 1)) TaleSounds.at(level, top, TaleSounds.Sound.CANDLE_LIGHT)
    }

    // ---- Nobody by ----

    /**
     * What a laying leaves, set at once because no laying was seen (Part F's "with nobody by"): the posy on the stone, the candles (three for
     * an A), and for an A, or a stone at the household's own door, a lit lantern beside it. Nothing for W, or with the memorials' blocks off.
     */
    fun atOnce(c: Ctx, t: Tale, rec: TaleData.Memorial?, ending: String) {
        if (rec == null || ending == Ending.W || !Tales.memorials()) return
        val level = c.level
        Memorials.posy(level, rec.id)
        val candles = if (ending == Ending.A) Chair.ALL else t.n[Chair.N_CANDLES]
        if (candles > 0) Memorials.candles(level, rec.id, candles, true)
        if (ending == Ending.A || t.n[Chair.N_PLACE] == Chair.DOOR) lantern(level, rec)
    }

    /** A vanilla lantern in the cell before [rec]'s stone (the way it faces), through the streets' record as the memorial's own. */
    private fun lantern(level: ServerLevel, rec: TaleData.Memorial) {
        val pos = rec.pos ?: return
        if (!rec.placed || !level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return
        val cell = pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING))
        val lamp = Blocks.LANTERN.defaultBlockState()
        if (!lamp.canSurvive(level, cell)) return
        if (Streets.place(level, cell, lamp, Streets.column(rec.capital), Streets.TALES, rec.id.toLong(), solid = true)) Fx.play(level, Fx.Kind.CANDLE, Vec3.atCenterOf(cell))
    }
}
