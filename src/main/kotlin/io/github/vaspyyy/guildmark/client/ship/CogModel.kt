package io.github.vaspyyy.guildmark.client.ship

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The cog: a single-masted trading ship, drawn bow toward -z in pixels (16 to a block) from the waterline amidships, y down, the
 * starboard side at -x (the renderer flips the pose stack by scale(-1, -1, 1), as for any entity model). Every part of her moves
 * that can: the yard braces round on the mast with its braces and lifts following (rods turned and stretched between their fixed
 * ends and the yard's arms), the sail comes down one row at a time (or is hauled up into the furled bundle) and each row bellies
 * with the wind, ripples when she luffs and shivers in a breeze, the pennant (three chained pieces) streams the way the wind
 * blows, the wheel turns with the rudder, the lanterns and the bell and the anchor hang plumb as she pitches and heels (and
 * sway), the bell swings and its clapper lags it, the anchor pays out its rope a length at a time and the capstan turns with it.
 *
 * The geometry comes from art/scripts/ship.py (edit it there: it writes this file's body and the texture, and its `pose()` is the
 * same animation in Python, run for the previews, so the two agree). The texture is HD: this model declares 512x512 and the PNGs
 * are painted at twice that. Her decks, sail, pennant and hatch grating are thin slabs cut to shape in the texture's alpha, so
 * she is drawn with a cutout render type that does not cull back faces ([RenderTypes.entityCutout]); the glow layer is the same
 * model drawn again with [RenderTypes.eyes] over cog_glow.png, tinted by the lanterns' brightness (it holds only what burns: the
 * lanterns' panes, the cabin's windows and door, the binnacle lamp).
 */
class CogModel(root: ModelPart) : EntityModel<CogRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The yard turns on the mast; the braces (yard arm to the castle's rail) and the lifts (yard arm to the masthead) follow its arms
    private val yard = part("yard")
    private val braces = arrayOf(part("brace_s"), part("brace_p"))
    private val lifts = arrayOf(part("lift_s"), part("lift_p"))

    // The sail: the furled bundle on the yard, and five rows, each hung from the one above with a side panel to either hand of it
    private val furl = part("furl")
    private val rows = Array(5) { part("sail${it + 1}") }
    private val lefts = Array(5) { part("sail${it + 1}l") }
    private val rights = Array(5) { part("sail${it + 1}r") }

    // The pennant, three pieces each hung from the one before
    private val pennants = Array(3) { part("pennant${it + 1}") }

    // The helm, and what hangs: the lanterns, the bell and its clapper, the anchor and the seven lengths of its rope, the capstan
    private val wheel = part("wheel")
    private val rudder = part("rudder")
    private val sternLantern = part("lantern_stern")
    private val bowLantern = part("lantern_bow")
    private val bell = part("bell")
    private val clapper = part("bell_clapper")
    private val anchor = part("anchor")
    private val ropes = Array(ROPE_SEGS) { part(if (it == 0) "anchor_rope" else "anchor_rope${it + 1}") }
    private val capstan = part("capstan")

    // Scratch for the sail (the part of each row let down, and how far aft each seam between rows lies): nothing allocated per frame
    private val down = FloatArray(5)
    private val seam = FloatArray(6)

    override fun setupAnim(state: CogRenderState) {
        super.setupAnim(state)
        val t = state.time
        val wind = state.wind.coerceIn(0f, 1f)
        // A blow against her hull shudders what hangs and flogs the canvas
        val shake = (state.hurtTime / 10f).coerceIn(0f, 1f)
        turnYard(state.brace)
        setSail(state, t, wind, shake)
        streamPennant(state, t, wind)
        wheel.zRot = -state.wheel * DEG
        rudder.yRot = -state.rudder * DEG
        hang(state, t, wind, shake)
    }

    /** The yard turns on the mast (above 0 its starboard arm swings aft); each brace and lift is turned and stretched to its arm. */
    private fun turnYard(brace: Float) {
        val theta = brace * DEG
        yard.yRot = theta
        val c = cos(theta)
        val s = sin(theta)
        for (side in 0..1) {
            val sigma = if (side == 0) -1f else 1f
            follow(braces[side], BRACE_FIX, side * 3, sigma * YARD_TIP, 0f, 0f, BRACE_LEN, c, s)
            follow(lifts[side], LIFT_FIX, side * 3, sigma * YARD_LIFT, LIFT_DY, LIFT_DZ, LIFT_LEN, c, s)
        }
    }

    /**
     * Turns [line], a rod pivoting on its fixed end at [fix] ([at] picks which end's three numbers) and running along its own y, to the
     * point ([x], [y], [z]) of the yard's own frame (the yard turned by the sine [s] and cosine [c] of its brace) and stretches it to reach,
     * [rest] being the length the rod was built to.
     */
    private fun follow(line: ModelPart, fix: FloatArray, at: Int, x: Float, y: Float, z: Float, rest: Float, c: Float, s: Float) {
        val dx = YARD_X + x * c + z * s - fix[at]
        val dy = YARD_Y + y - fix[at + 1]
        val dz = YARD_Z - x * s + z * c - fix[at + 2]
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        line.xRot = asin(dz / length)
        line.yRot = 0f
        line.zRot = atan2(-dx, dy)
        line.yScale = length / rest
    }

    /**
     * The sail. [CogRenderState.sail] lets the rows down one after the other (the last one to come down is drawn shorter until it is
     * all the way); the furled bundle shrinks away as they come. A full sail ([CogRenderState.fill] above 0) bellies forward, most
     * in the middle of what is let down, its side panels folding at the seams; taken aback (below 0) it is pressed back against the
     * mast. Luffing, each row nods and its side panels flap, the foot most; and in any wind the canvas shivers a little.
     */
    private fun setSail(state: CogRenderState, t: Float, wind: Float, shake: Float) {
        val n = state.sail.coerceIn(0f, 1f) * 5f
        var total = 0f
        for (k in 0..4) {
            down[k] = ROW_H[k] * (n - k).coerceIn(0f, 1f)
            total += down[k]
        }
        val furled = 1f - n / 5f
        furl.visible = furled > 0.02f
        val bundle = max(furled, 0.12f)
        furl.yScale = bundle
        furl.zScale = bundle
        val fill = state.fill.coerceIn(-1f, 1f)
        val luff = max(state.luff, shake * 0.5f).coerceIn(0f, 1f)
        // How far aft the seams between the rows lie: negative bellies forward
        val deep = (if (fill >= 0f) -BELLY_FWD * fill else -BELLY_AFT * fill) * (total / SAIL_H)
        var run = 0f
        for (k in 0..5) {
            val s = if (total > 0f) run / total else 0f
            seam[k] = deep * sin(PI_F * s.pow(0.85f))
            if (k < 5) run += down[k]
        }
        val wt = t * (0.42f + 0.5f * wind)
        var before = 0f
        run = 0f
        for (k in 0..4) {
            val row = rows[k]
            if (down[k] <= 0.3f) {
                row.visible = false
                continue
            }
            row.visible = true
            row.yScale = down[k] / ROW_H[k]
            // Each row is a child of the one above: it turns by the difference of their slopes
            var tilt = asin(((seam[k + 1] - seam[k]) / down[k]).coerceIn(-0.7f, 0.7f))
            val free = 0.55f + 0.45f * k / 4f
            tilt += luff * RIPPLE_X * free * sin(wt * 1.6f - 0.9f * k) + wind * SHIVER_X * free * sin(wt * 0.9f - 1.3f * k + 1f)
            row.xRot = tilt - before
            row.yRot = luff * 0.07f * sin(wt * 1.1f + 1.1f * k)
            before = tilt
            val middle = (run + down[k] / 2f) / total
            run += down[k]
            val fold = SIDE_BELLY * fill * (0.3f + 0.7f * sin(PI_F * middle))
            lefts[k].yRot = -(fold + luff * RIPPLE_Y * free * sin(wt * 1.9f + 1.7f * k + 0.6f) + wind * SHIVER_Y * free * sin(wt * 1.3f + 2.1f * k))
            rights[k].yRot = fold + luff * RIPPLE_Y * free * sin(wt * 1.9f + 1.7f * k + 2.4f) + wind * SHIVER_Y * free * sin(wt * 1.3f + 2.1f * k + 1.7f)
        }
    }

    /** The pennant streams the way the wind blows ([CogRenderState.windYaw]), drooping in a calm and rippling along its length. */
    private fun streamPennant(state: CogRenderState, t: Float, wind: Float) {
        val ft = t * (0.55f + 0.55f * wind + 0.3f * state.speed)
        val droop = (1f - wind).pow(1.6f)
        for (i in 0..2) {
            val piece = pennants[i]
            piece.xRot = -droop * (0.18f + 0.12f * i) + 0.07f * wind * sin(ft * 1.3f - 1.3f * i + 0.5f)
            piece.yRot = (if (i == 0) (state.windYaw + 180f) * DEG else 0f) + (0.10f + 0.20f * wind) * (1f + 0.5f * i) * sin(ft - 1.15f * i)
            piece.zRot = 0.10f * (0.4f + wind) * sin(ft * 0.8f - i)
        }
    }

    /**
     * What hangs stays nearly plumb as she pitches and heels (the renderer leans the whole of her about x by the pitch and about z by
     * the heel and flips x and y, so a part turned by +pitch about x and -heel about z undoes it) and sways in the swell: the two
     * lanterns, the bell (and its clapper, lagging it), the anchor on its cable and the capstan that turns as the cable pays out.
     */
    private fun hang(state: CogRenderState, t: Float, wind: Float, shake: Float) {
        val sway = 0.02f + 0.025f * wind + 0.03f * state.speed + 0.05f * shake
        val kick = shake * state.hurtDir
        val px = state.pitch * DEG * TILT_HANG
        val pz = -state.heel * DEG * TILT_HANG
        sternLantern.xRot = px + sway * sin(t * 0.19f + 0.4f) + 0.1f * kick * sin(t * 1.1f)
        sternLantern.zRot = pz + 0.8f * sway * sin(t * 0.15f + 2f)
        bowLantern.xRot = px + sway * sin(t * 0.21f + 2.5f)
        bowLantern.zRot = pz + 0.8f * sway * sin(t * 0.17f + 4.1f) + 0.1f * kick * sin(t * 1.3f)
        // The bell, struck: it swings fore and aft and settles, the clapper a beat behind it
        var swing = 0f
        var clap = 0f
        val b = state.bell
        if (b >= 0f) {
            val ring = 0.5f * exp(-b / 20f)
            swing = ring * sin(b * 0.45f)
            clap = -1.3f * ring * sin(b * 0.45f - 0.8f)
        }
        bell.xRot = swing + px + 0.3f * sway * sin(t * 0.23f)
        bell.zRot = pz * 0.5f
        clapper.xRot = clap
        // The anchor hangs from the cathead's hook on its cable, d paid out; each length of the rope is shown as far as it is out
        val d = state.anchor.coerceIn(0f, 1f) * ANCHOR_DROP
        val ax = px + 0.7f * sway * sin(t * 0.13f + 1f)
        val az = pz + 0.7f * sway * sin(t * 0.11f + 3f)
        anchor.x = ANCHOR_X - d * cos(ax) * sin(az)
        anchor.y = ANCHOR_Y + d * cos(ax) * cos(az)
        anchor.z = ANCHOR_Z + d * sin(ax)
        anchor.xRot = ax
        anchor.zRot = az
        ropes[0].xRot = ax
        ropes[0].zRot = az
        for (i in 0 until ROPE_SEGS) {
            val out = d - ROPE_SEG * i
            ropes[i].visible = out > 0.01f
            ropes[i].yScale = (out / ROPE_SEG).coerceIn(0f, 1f)
        }
        capstan.yRot = d / 4f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cog"), "main")

        private const val DEG = 0.017453292f
        private const val PI_F = 3.1415927f

        // The yard's pivot on the mast, the sail's rows (px down) and all of it
        private const val YARD_X = 0f
        private const val YARD_Y = -152f
        private const val YARD_Z = -11f
        private val ROW_H = floatArrayOf(18f, 18f, 17.5f, 17.5f, 17f)
        private const val SAIL_H = 88f

        // How the sail bellies and flogs: px forward / back at the deepest, radians the side panels fold, the rows and panels flogging, the canvas shivering
        private const val BELLY_FWD = 9f
        private const val BELLY_AFT = 2.4f
        private const val SIDE_BELLY = 0.297f
        private const val RIPPLE_X = 0.24f
        private const val RIPPLE_Y = 0.34f
        private const val SHIVER_X = 0.035f
        private const val SHIVER_Y = 0.05f

        // The braces and the lifts: the fixed end of the starboard one then the port one (x, y, z), how far along the yard each is made fast, the lift's offset up and aft, how long each rod was built
        private val BRACE_FIX = floatArrayOf(-23.1f, -44f, 50f, 23.1f, -44f, 50f)
        private val LIFT_FIX = floatArrayOf(-1.6f, -183f, -9f, 1.6f, -183f, -9f)
        private const val YARD_TIP = 54f
        private const val YARD_LIFT = 50f
        private const val LIFT_DY = -1.2f
        private const val LIFT_DZ = -0.6f
        private const val BRACE_LEN = 128f
        private const val LIFT_LEN = 57f

        // What hangs: how nearly plumb against her lean; the anchor's hook, how far it drops, the lengths of its rope
        private const val TILT_HANG = 0.9f
        private const val ANCHOR_X = -20f
        private const val ANCHOR_Y = -18f
        private const val ANCHOR_Z = -56f
        private const val ANCHOR_DROP = 56f
        private const val ROPE_SEG = 8f
        private const val ROPE_SEGS = 7

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            buildHull1(root)
            buildHull2(root)
            buildStem(root)
            buildDecks(root)
            buildMast(root)
            buildYard(root)
            buildRigging(root)
            buildFittings(root)
            buildCargo(root)
            buildLines(root)
            return LayerDefinition.create(mesh, 512, 512)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHull1(root: PartDefinition) {
            root.addOrReplaceChild("hs0_00", CubeListBuilder.create().texOffs(164, 307).addBox(-5.25f, -3f, 0f, 10.5f, 6f, 2f), PartPose.offsetAndRotation(-3.399f, -21.169f, -67.462f, -0.242f, 0.866f, -0.291f))
            root.addOrReplaceChild("hs0_01", CubeListBuilder.create().texOffs(106, 315).addBox(-5f, -2.75f, 0f, 10f, 5.5f, 2f), PartPose.offsetAndRotation(-9.107f, -19.574f, -59.929f, -0.216f, 0.943f, -0.251f))
            root.addOrReplaceChild("hs0_02", CubeListBuilder.create().texOffs(462, 332).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-14.247f, -18.476f, -51.986f, -0.193f, 1.032f, -0.165f))
            root.addOrReplaceChild("hs0_03", CubeListBuilder.create().texOffs(50, 322).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-18.76f, -18.051f, -43.573f, -0.118f, 1.123f, -0.012f))
            root.addOrReplaceChild("hs0_04", CubeListBuilder.create().texOffs(242, 322).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-22.454f, -18.029f, -34.757f, -0.153f, 1.228f, 0f))
            root.addOrReplaceChild("hs0_05", CubeListBuilder.create().texOffs(100, 323).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-25.184f, -18.031f, -25.595f, -0.188f, 1.336f, 0f))
            root.addOrReplaceChild("hs0_06", CubeListBuilder.create().texOffs(125, 323).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-26.94f, -18.033f, -16.196f, -0.212f, 1.438f, 0f))
            root.addOrReplaceChild("hs0_07", CubeListBuilder.create().texOffs(202, 323).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-27.811f, -18.034f, -6.669f, -0.224f, 1.522f, 0f))
            root.addOrReplaceChild("hs0_08", CubeListBuilder.create().texOffs(267, 323).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-28.057f, -18.034f, 2.899f, -0.227f, 1.568f, 0f))
            root.addOrReplaceChild("hs0_09", CubeListBuilder.create().texOffs(421, 324).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-28.069f, -18.034f, 12.47f, -0.227f, 1.571f, 0f))
            root.addOrReplaceChild("hs0_10", CubeListBuilder.create().texOffs(0, 325).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-28.025f, -18.034f, 22.042f, 2.915f, 1.562f, -3.142f))
            root.addOrReplaceChild("hs0_11", CubeListBuilder.create().texOffs(292, 325).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-27.633f, -18.034f, 31.604f, 2.919f, 1.498f, -3.142f))
            root.addOrReplaceChild("hs0_12", CubeListBuilder.create().texOffs(317, 325).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-26.597f, -18.033f, 41.117f, 2.929f, 1.427f, -3.142f))
            root.addOrReplaceChild("hs0_13", CubeListBuilder.create().texOffs(446, 325).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-24.897f, -18.032f, 50.533f, 2.946f, 1.358f, -3.142f))
            root.addOrReplaceChild("hs0_14", CubeListBuilder.create().texOffs(471, 325).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(-22.564f, -18.03f, 59.813f, 2.968f, 1.292f, -3.142f))
            root.addOrReplaceChild("hs0_15", CubeListBuilder.create().texOffs(486, 332).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-19.693f, -18.03f, 68.926f, 2.973f, 1.239f, -3.142f))
            root.addOrReplaceChild("hp0_00", CubeListBuilder.create().texOffs(114, 307).addBox(-5.25f, -3f, 0f, 10.5f, 6f, 2f), PartPose.offsetAndRotation(3.399f, -21.169f, -67.462f, -0.242f, -0.866f, 0.291f))
            root.addOrReplaceChild("hp0_01", CubeListBuilder.create().texOffs(361, 313).addBox(-5f, -2.75f, 0f, 10f, 5.5f, 2f), PartPose.offsetAndRotation(9.107f, -19.574f, -59.929f, -0.216f, -0.943f, 0.251f))
            root.addOrReplaceChild("hp0_02", CubeListBuilder.create().texOffs(75, 326).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(14.247f, -18.476f, -51.986f, -0.193f, -1.032f, 0.165f))
            root.addOrReplaceChild("hp0_03", CubeListBuilder.create().texOffs(199, 171).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(18.76f, -18.051f, -43.573f, -0.118f, -1.123f, 0.012f))
            root.addOrReplaceChild("hp0_04", CubeListBuilder.create().texOffs(274, 316).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(22.454f, -18.029f, -34.757f, -0.153f, -1.228f, 0f))
            root.addOrReplaceChild("hp0_05", CubeListBuilder.create().texOffs(0, 318).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(25.184f, -18.031f, -25.595f, -0.188f, -1.336f, 0f))
            root.addOrReplaceChild("hp0_06", CubeListBuilder.create().texOffs(299, 318).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(26.94f, -18.033f, -16.196f, -0.212f, -1.438f, 0f))
            root.addOrReplaceChild("hp0_07", CubeListBuilder.create().texOffs(324, 318).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(27.811f, -18.034f, -6.669f, -0.224f, -1.522f, 0f))
            root.addOrReplaceChild("hp0_08", CubeListBuilder.create().texOffs(468, 318).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(28.057f, -18.034f, 2.899f, -0.227f, -1.568f, 0f))
            root.addOrReplaceChild("hp0_09", CubeListBuilder.create().texOffs(75, 319).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(28.069f, -18.034f, 12.47f, -0.227f, -1.571f, 0f))
            root.addOrReplaceChild("hp0_10", CubeListBuilder.create().texOffs(152, 321).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(28.025f, -18.034f, 22.042f, 2.915f, -1.562f, 3.142f))
            root.addOrReplaceChild("hp0_11", CubeListBuilder.create().texOffs(177, 321).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(27.633f, -18.034f, 31.604f, 2.919f, -1.498f, 3.142f))
            root.addOrReplaceChild("hp0_12", CubeListBuilder.create().texOffs(349, 321).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(26.597f, -18.033f, 41.117f, 2.929f, -1.427f, 3.142f))
            root.addOrReplaceChild("hp0_13", CubeListBuilder.create().texOffs(374, 321).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(24.897f, -18.032f, 50.533f, 2.946f, -1.358f, 3.142f))
            root.addOrReplaceChild("hp0_14", CubeListBuilder.create().texOffs(25, 322).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2f), PartPose.offsetAndRotation(22.564f, -18.03f, 59.813f, 2.968f, -1.292f, 3.142f))
            root.addOrReplaceChild("hp0_15", CubeListBuilder.create().texOffs(342, 328).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(19.693f, -18.03f, 68.926f, 2.973f, -1.239f, 3.142f))
            root.addOrReplaceChild("hs1_00", CubeListBuilder.create().texOffs(24, 310).addBox(-5f, -3f, 0f, 10f, 6f, 2f), PartPose.offsetAndRotation(-3.45f, -15.711f, -65.967f, -0.108f, 0.895f, -0.203f))
            root.addOrReplaceChild("hs1_01", CubeListBuilder.create().texOffs(130, 315).addBox(-5f, -2.75f, 0f, 10f, 5.5f, 2f), PartPose.offsetAndRotation(-8.979f, -14.69f, -58.608f, -0.106f, 0.944f, -0.162f))
            root.addOrReplaceChild("hs1_02", CubeListBuilder.create().texOffs(73, 333).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-14.128f, -14.017f, -50.841f, -0.109f, 1.023f, -0.095f))
            root.addOrReplaceChild("hs1_03", CubeListBuilder.create().texOffs(147, 333).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-18.656f, -13.78f, -42.605f, -0.078f, 1.115f, -0.002f))
            root.addOrReplaceChild("hs1_04", CubeListBuilder.create().texOffs(321, 335).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-22.351f, -13.777f, -33.953f, -0.118f, 1.222f, 0f))
            root.addOrReplaceChild("hs1_05", CubeListBuilder.create().texOffs(345, 335).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-25.087f, -13.779f, -24.95f, -0.153f, 1.332f, 0f))
            root.addOrReplaceChild("hs1_06", CubeListBuilder.create().texOffs(24, 336).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-26.847f, -13.781f, -15.704f, -0.178f, 1.435f, 0f))
            root.addOrReplaceChild("hs1_07", CubeListBuilder.create().texOffs(48, 336).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-27.719f, -13.781f, -6.328f, -0.192f, 1.522f, 0f))
            root.addOrReplaceChild("hs1_08", CubeListBuilder.create().texOffs(204, 336).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-27.962f, -13.782f, 3.091f, -0.197f, 1.568f, 0f))
            root.addOrReplaceChild("hs1_09", CubeListBuilder.create().texOffs(369, 336).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-27.974f, -13.782f, 12.514f, -0.197f, 1.571f, 0f))
            root.addOrReplaceChild("hs1_10", CubeListBuilder.create().texOffs(97, 337).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-27.931f, -13.782f, 21.937f, 2.945f, 1.562f, -3.142f))
            root.addOrReplaceChild("hs1_11", CubeListBuilder.create().texOffs(121, 337).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-27.538f, -13.781f, 31.35f, 2.95f, 1.497f, -3.142f))
            root.addOrReplaceChild("hs1_12", CubeListBuilder.create().texOffs(171, 337).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-26.498f, -13.781f, 40.712f, 2.963f, 1.424f, -3.142f))
            root.addOrReplaceChild("hs1_13", CubeListBuilder.create().texOffs(228, 337).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-24.793f, -13.78f, 49.975f, 2.981f, 1.354f, -3.142f))
            root.addOrReplaceChild("hs1_14", CubeListBuilder.create().texOffs(393, 338).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-22.458f, -13.778f, 59.101f, 3.003f, 1.288f, -3.142f))
            root.addOrReplaceChild("hs1_15", CubeListBuilder.create().texOffs(0, 339).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(-19.589f, -13.778f, 68.057f, 3.007f, 1.234f, -3.142f))
            root.addOrReplaceChild("hp1_00", CubeListBuilder.create().texOffs(0, 310).addBox(-5f, -3f, 0f, 10f, 6f, 2f), PartPose.offsetAndRotation(3.45f, -15.711f, -65.967f, -0.108f, -0.895f, 0.203f))
            root.addOrReplaceChild("hp1_01", CubeListBuilder.create().texOffs(385, 313).addBox(-5f, -2.75f, 0f, 10f, 5.5f, 2f), PartPose.offsetAndRotation(8.979f, -14.69f, -58.608f, -0.106f, -0.944f, 0.162f))
            root.addOrReplaceChild("hp1_02", CubeListBuilder.create().texOffs(366, 328).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(14.128f, -14.017f, -50.841f, -0.109f, -1.023f, 0.095f))
            root.addOrReplaceChild("hp1_03", CubeListBuilder.create().texOffs(25, 329).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(18.656f, -13.78f, -42.605f, -0.078f, -1.115f, 0.002f))
            root.addOrReplaceChild("hp1_04", CubeListBuilder.create().texOffs(49, 329).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(22.351f, -13.777f, -33.953f, -0.118f, -1.222f, 0f))
            root.addOrReplaceChild("hp1_05", CubeListBuilder.create().texOffs(225, 329).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(25.087f, -13.779f, -24.95f, -0.153f, -1.332f, 0f))
            root.addOrReplaceChild("hp1_06", CubeListBuilder.create().texOffs(390, 329).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(26.847f, -13.781f, -15.704f, -0.178f, -1.435f, 0f))
            root.addOrReplaceChild("hp1_07", CubeListBuilder.create().texOffs(99, 330).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(27.719f, -13.781f, -6.328f, -0.192f, -1.522f, 0f))
            root.addOrReplaceChild("hp1_08", CubeListBuilder.create().texOffs(123, 330).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(27.962f, -13.782f, 3.091f, -0.197f, -1.568f, 0f))
            root.addOrReplaceChild("hp1_09", CubeListBuilder.create().texOffs(182, 330).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(27.974f, -13.782f, 12.514f, -0.197f, -1.571f, 0f))
            root.addOrReplaceChild("hp1_10", CubeListBuilder.create().texOffs(249, 330).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(27.931f, -13.782f, 21.937f, 2.945f, -1.562f, 3.142f))
            root.addOrReplaceChild("hp1_11", CubeListBuilder.create().texOffs(414, 331).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(27.538f, -13.781f, 31.35f, 2.95f, -1.497f, 3.142f))
            root.addOrReplaceChild("hp1_12", CubeListBuilder.create().texOffs(0, 332).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(26.498f, -13.781f, 40.712f, 2.963f, -1.424f, 3.142f))
            root.addOrReplaceChild("hp1_13", CubeListBuilder.create().texOffs(273, 332).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(24.793f, -13.78f, 49.975f, 2.981f, -1.354f, 3.142f))
            root.addOrReplaceChild("hp1_14", CubeListBuilder.create().texOffs(297, 332).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(22.458f, -13.778f, 59.101f, 3.003f, -1.288f, 3.142f))
            root.addOrReplaceChild("hp1_15", CubeListBuilder.create().texOffs(438, 332).addBox(-5f, -2.5f, 0f, 10f, 5f, 2f), PartPose.offsetAndRotation(19.589f, -13.778f, 68.057f, 3.007f, -1.234f, 3.142f))
            root.addOrReplaceChild("hs2_00", CubeListBuilder.create().texOffs(149, 233).addBox(-4.75f, -3.75f, 0f, 9.5f, 7.5f, 3f), PartPose.offsetAndRotation(-3.925f, -9.557f, -64.117f, 0.105f, 0.919f, -0.108f))
            root.addOrReplaceChild("hs2_01", CubeListBuilder.create().texOffs(252, 232).addBox(-5f, -3.75f, 0f, 10f, 7.5f, 3f), PartPose.offsetAndRotation(-9.275f, -9.057f, -56.87f, 0.183f, 0.941f, -0.079f))
            root.addOrReplaceChild("hs2_02", CubeListBuilder.create().texOffs(472, 245).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-14.362f, -8.752f, -49.245f, 0.216f, 1.022f, -0.038f))
            root.addOrReplaceChild("hs2_03", CubeListBuilder.create().texOffs(385, 247).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-18.8f, -8.66f, -41.174f, 0.241f, 1.114f, 0f))
            root.addOrReplaceChild("hs2_04", CubeListBuilder.create().texOffs(78, 248).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-22.425f, -8.659f, -32.695f, 0.225f, 1.22f, 0f))
            root.addOrReplaceChild("hs2_05", CubeListBuilder.create().texOffs(411, 250).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-25.124f, -8.657f, -23.873f, 0.205f, 1.329f, 0f))
            root.addOrReplaceChild("hs2_06", CubeListBuilder.create().texOffs(437, 250).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-26.872f, -8.656f, -14.809f, 0.183f, 1.433f, 0f))
            root.addOrReplaceChild("hs2_07", CubeListBuilder.create().texOffs(147, 251).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-27.747f, -8.655f, -5.613f, 0.165f, 1.52f, 0f))
            root.addOrReplaceChild("hs2_08", CubeListBuilder.create().texOffs(173, 253).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-27.991f, -8.654f, 3.631f, 0.157f, 1.569f, 0f))
            root.addOrReplaceChild("hs2_09", CubeListBuilder.create().texOffs(199, 253).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-28f, -8.654f, 12.884f, 0.157f, 1.571f, 0f))
            root.addOrReplaceChild("hs2_10", CubeListBuilder.create().texOffs(225, 253).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-27.949f, -8.654f, 22.137f, -2.984f, 1.56f, -3.142f))
            root.addOrReplaceChild("hs2_11", CubeListBuilder.create().texOffs(251, 253).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-27.527f, -8.655f, 31.37f, -2.977f, 1.491f, -3.142f))
            root.addOrReplaceChild("hs2_12", CubeListBuilder.create().texOffs(277, 253).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-26.45f, -8.655f, 40.549f, -2.966f, 1.418f, -3.142f))
            root.addOrReplaceChild("hs2_13", CubeListBuilder.create().texOffs(333, 253).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-24.728f, -8.656f, 49.63f, -2.957f, 1.349f, -3.142f))
            root.addOrReplaceChild("hs2_14", CubeListBuilder.create().texOffs(359, 253).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-22.406f, -8.656f, 58.576f, -2.952f, 1.285f, -3.142f))
            root.addOrReplaceChild("hs2_15", CubeListBuilder.create().texOffs(0, 254).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(-19.559f, -8.656f, 67.36f, -2.961f, 1.229f, -3.142f))
            root.addOrReplaceChild("hp2_00", CubeListBuilder.create().texOffs(124, 233).addBox(-4.75f, -3.75f, 0f, 9.5f, 7.5f, 3f), PartPose.offsetAndRotation(3.925f, -9.557f, -64.117f, 0.105f, -0.919f, 0.108f))
            root.addOrReplaceChild("hp2_01", CubeListBuilder.create().texOffs(472, 224).addBox(-5f, -3.75f, 0f, 10f, 7.5f, 3f), PartPose.offsetAndRotation(9.275f, -9.057f, -56.87f, 0.183f, -0.941f, 0.079f))
            root.addOrReplaceChild("hp2_02", CubeListBuilder.create().texOffs(174, 233).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(14.362f, -8.752f, -49.245f, 0.216f, -1.022f, 0.038f))
            root.addOrReplaceChild("hp2_03", CubeListBuilder.create().texOffs(200, 233).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(18.8f, -8.66f, -41.174f, 0.241f, -1.114f, 0f))
            root.addOrReplaceChild("hp2_04", CubeListBuilder.create().texOffs(359, 233).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(22.425f, -8.659f, -32.695f, 0.225f, -1.22f, 0f))
            root.addOrReplaceChild("hp2_05", CubeListBuilder.create().texOffs(52, 234).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(25.124f, -8.657f, -23.873f, 0.205f, -1.329f, 0f))
            root.addOrReplaceChild("hp2_06", CubeListBuilder.create().texOffs(472, 235).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(26.872f, -8.656f, -14.809f, 0.183f, -1.433f, 0f))
            root.addOrReplaceChild("hp2_07", CubeListBuilder.create().texOffs(385, 237).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(27.747f, -8.655f, -5.613f, 0.165f, -1.52f, 0f))
            root.addOrReplaceChild("hp2_08", CubeListBuilder.create().texOffs(98, 238).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(27.991f, -8.654f, 3.631f, 0.157f, -1.569f, 0f))
            root.addOrReplaceChild("hp2_09", CubeListBuilder.create().texOffs(411, 240).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(28f, -8.654f, 12.884f, 0.157f, -1.571f, 0f))
            root.addOrReplaceChild("hp2_10", CubeListBuilder.create().texOffs(437, 240).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(27.949f, -8.654f, 22.137f, -2.984f, -1.56f, 3.142f))
            root.addOrReplaceChild("hp2_11", CubeListBuilder.create().texOffs(171, 243).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(27.527f, -8.655f, 31.37f, -2.977f, -1.491f, 3.142f))
            root.addOrReplaceChild("hp2_12", CubeListBuilder.create().texOffs(197, 243).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(26.45f, -8.655f, 40.549f, -2.966f, -1.418f, 3.142f))
            root.addOrReplaceChild("hp2_13", CubeListBuilder.create().texOffs(223, 243).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(24.728f, -8.656f, 49.63f, -2.957f, -1.349f, 3.142f))
            root.addOrReplaceChild("hp2_14", CubeListBuilder.create().texOffs(249, 243).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(22.406f, -8.656f, 58.576f, -2.952f, -1.285f, 3.142f))
            root.addOrReplaceChild("hp2_15", CubeListBuilder.create().texOffs(275, 243).addBox(-5f, -3.5f, 0f, 10f, 7f, 3f), PartPose.offsetAndRotation(19.559f, -8.656f, 67.36f, -2.961f, -1.229f, 3.142f))
            root.addOrReplaceChild("hs3_00", CubeListBuilder.create().texOffs(349, 293).addBox(-5f, -3.25f, 0f, 10f, 6.5f, 2.5f), PartPose.offsetAndRotation(-3.043f, -3.104f, -61.763f, 0.041f, 0.954f, -0.03f))
            root.addOrReplaceChild("hs3_01", CubeListBuilder.create().texOffs(0, 284).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-8.36f, -2.979f, -53.949f, 0.203f, 0.981f, -0.019f))
            root.addOrReplaceChild("hs3_02", CubeListBuilder.create().texOffs(26, 284).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-13.333f, -2.923f, -45.777f, 0.278f, 1.063f, -0.004f))
            root.addOrReplaceChild("hs3_03", CubeListBuilder.create().texOffs(359, 284).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-17.584f, -2.917f, -37.183f, 0.317f, 1.158f, 0f))
            root.addOrReplaceChild("hs3_04", CubeListBuilder.create().texOffs(472, 284).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-20.972f, -2.918f, -28.201f, 0.326f, 1.262f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildHull2(root: PartDefinition) {
            root.addOrReplaceChild("hs3_05", CubeListBuilder.create().texOffs(100, 287).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-23.402f, -2.917f, -18.906f, 0.311f, 1.369f, 0f))
            root.addOrReplaceChild("hs3_06", CubeListBuilder.create().texOffs(385, 287).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-24.861f, -2.914f, -9.404f, 0.283f, 1.47f, 0f))
            root.addOrReplaceChild("hs3_07", CubeListBuilder.create().texOffs(126, 289).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-25.439f, -2.912f, 0.201f, 0.261f, 1.553f, 0f))
            root.addOrReplaceChild("hs3_08", CubeListBuilder.create().texOffs(152, 289).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-25.526f, -2.911f, 9.838f, 0.255f, 1.571f, 0f))
            root.addOrReplaceChild("hs3_09", CubeListBuilder.create().texOffs(178, 289).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-25.515f, -2.911f, 19.478f, -2.887f, 1.569f, -3.142f))
            root.addOrReplaceChild("hs3_10", CubeListBuilder.create().texOffs(204, 289).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-25.149f, -2.912f, 29.098f, -2.875f, 1.498f, -3.142f))
            root.addOrReplaceChild("hs3_11", CubeListBuilder.create().texOffs(411, 289).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-24.07f, -2.915f, 38.661f, -2.852f, 1.421f, -3.142f))
            root.addOrReplaceChild("hs3_12", CubeListBuilder.create().texOffs(437, 289).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-22.303f, -2.916f, 48.124f, -2.837f, 1.353f, -3.142f))
            root.addOrReplaceChild("hs3_13", CubeListBuilder.create().texOffs(230, 290).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-19.936f, -2.916f, 57.454f, -2.837f, 1.292f, -3.142f))
            root.addOrReplaceChild("hs3_14", CubeListBuilder.create().texOffs(50, 291).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-17.063f, -2.913f, 66.632f, -2.863f, 1.241f, -3.142f))
            root.addOrReplaceChild("hp3_00", CubeListBuilder.create().texOffs(0, 293).addBox(-5f, -3.25f, 0f, 10f, 6.5f, 2.5f), PartPose.offsetAndRotation(3.043f, -3.104f, -61.763f, 0.041f, -0.954f, 0.03f))
            root.addOrReplaceChild("hp3_01", CubeListBuilder.create().texOffs(50, 275).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(8.36f, -2.979f, -53.949f, 0.203f, -0.981f, 0.019f))
            root.addOrReplaceChild("hp3_02", CubeListBuilder.create().texOffs(472, 275).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(13.333f, -2.923f, -45.777f, 0.278f, -1.063f, 0.004f))
            root.addOrReplaceChild("hp3_03", CubeListBuilder.create().texOffs(98, 276).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(17.584f, -2.917f, -37.183f, 0.317f, -1.158f, 0f))
            root.addOrReplaceChild("hp3_04", CubeListBuilder.create().texOffs(383, 277).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(20.972f, -2.918f, -28.201f, 0.326f, -1.262f, 0f))
            root.addOrReplaceChild("hp3_05", CubeListBuilder.create().texOffs(124, 280).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(23.402f, -2.917f, -18.906f, 0.311f, -1.369f, 0f))
            root.addOrReplaceChild("hp3_06", CubeListBuilder.create().texOffs(150, 280).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(24.861f, -2.914f, -9.404f, 0.283f, -1.47f, 0f))
            root.addOrReplaceChild("hp3_07", CubeListBuilder.create().texOffs(176, 280).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(25.439f, -2.912f, 0.201f, 0.261f, -1.553f, 0f))
            root.addOrReplaceChild("hp3_08", CubeListBuilder.create().texOffs(202, 280).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(25.526f, -2.911f, 9.838f, 0.255f, -1.571f, 0f))
            root.addOrReplaceChild("hp3_09", CubeListBuilder.create().texOffs(228, 280).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(25.515f, -2.911f, 19.478f, -2.887f, -1.569f, 3.142f))
            root.addOrReplaceChild("hp3_10", CubeListBuilder.create().texOffs(409, 280).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(25.149f, -2.912f, 29.098f, -2.875f, -1.498f, 3.142f))
            root.addOrReplaceChild("hp3_11", CubeListBuilder.create().texOffs(435, 280).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(24.07f, -2.915f, 38.661f, -2.852f, -1.421f, 3.142f))
            root.addOrReplaceChild("hp3_12", CubeListBuilder.create().texOffs(74, 283).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(22.303f, -2.916f, 48.124f, -2.837f, -1.353f, 3.142f))
            root.addOrReplaceChild("hp3_13", CubeListBuilder.create().texOffs(254, 283).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(19.936f, -2.916f, 57.454f, -2.837f, -1.292f, 3.142f))
            root.addOrReplaceChild("hp3_14", CubeListBuilder.create().texOffs(280, 283).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(17.063f, -2.913f, 66.632f, -2.863f, -1.241f, 3.142f))
            root.addOrReplaceChild("hs4_00", CubeListBuilder.create().texOffs(189, 307).addBox(-5f, -2.75f, 0f, 10f, 5.5f, 2.5f), PartPose.offsetAndRotation(-2.475f, 2.343f, -59.495f, 0.107f, 1.058f, -0.001f))
            root.addOrReplaceChild("hs4_01", CubeListBuilder.create().texOffs(191, 298).addBox(-5f, -3f, 0f, 10f, 6f, 2.5f), PartPose.offsetAndRotation(-6.847f, 2.321f, -51.383f, 0.397f, 1.073f, -0.001f))
            root.addOrReplaceChild("hs4_02", CubeListBuilder.create().texOffs(472, 293).addBox(-5f, -3.25f, 0f, 10f, 6.5f, 2.5f), PartPose.offsetAndRotation(-10.981f, 2.3f, -43.077f, 0.543f, 1.135f, 0f))
            root.addOrReplaceChild("hs4_03", CubeListBuilder.create().texOffs(152, 270).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-14.575f, 2.283f, -34.511f, 0.628f, 1.207f, 0f))
            root.addOrReplaceChild("hs4_04", CubeListBuilder.create().texOffs(177, 270).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-17.519f, 2.275f, -25.691f, 0.668f, 1.288f, 0f))
            root.addOrReplaceChild("hs4_05", CubeListBuilder.create().texOffs(202, 270).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-19.721f, 2.274f, -16.65f, 0.673f, 1.376f, 0f))
            root.addOrReplaceChild("hs4_06", CubeListBuilder.create().texOffs(227, 270).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-21.124f, 2.278f, -7.445f, 0.654f, 1.465f, 0f))
            root.addOrReplaceChild("hs4_07", CubeListBuilder.create().texOffs(408, 270).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-21.687f, 2.281f, 1.851f, 0.637f, 1.557f, 0f))
            root.addOrReplaceChild("hs4_08", CubeListBuilder.create().texOffs(433, 270).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-21.753f, 2.282f, 11.192f, 0.634f, 1.571f, 0f))
            root.addOrReplaceChild("hs4_09", CubeListBuilder.create().texOffs(252, 273).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-21.721f, 2.282f, 20.534f, -2.507f, 1.564f, -3.142f))
            root.addOrReplaceChild("hs4_10", CubeListBuilder.create().texOffs(277, 273).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-21.227f, 2.279f, 29.835f, -2.495f, 1.472f, -3.142f))
            root.addOrReplaceChild("hs4_11", CubeListBuilder.create().texOffs(333, 273).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-19.982f, 2.276f, 39.073f, -2.478f, 1.403f, -3.142f))
            root.addOrReplaceChild("hs4_12", CubeListBuilder.create().texOffs(358, 273).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-18.172f, 2.275f, 48.221f, -2.476f, 1.349f, -3.142f))
            root.addOrReplaceChild("hs4_13", CubeListBuilder.create().texOffs(0, 274).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-15.926f, 2.279f, 57.271f, -2.495f, 1.305f, -3.142f))
            root.addOrReplaceChild("hs4_14", CubeListBuilder.create().texOffs(25, 274).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(-13.349f, 2.29f, 66.227f, -2.545f, 1.273f, -3.142f))
            root.addOrReplaceChild("hp4_00", CubeListBuilder.create().texOffs(139, 307).addBox(-5f, -2.75f, 0f, 10f, 5.5f, 2.5f), PartPose.offsetAndRotation(2.475f, 2.343f, -59.495f, 0.107f, -1.058f, 0.001f))
            root.addOrReplaceChild("hp4_01", CubeListBuilder.create().texOffs(166, 298).addBox(-5f, -3f, 0f, 10f, 6f, 2.5f), PartPose.offsetAndRotation(6.847f, 2.321f, -51.383f, 0.397f, -1.073f, 0.001f))
            root.addOrReplaceChild("hp4_02", CubeListBuilder.create().texOffs(25, 293).addBox(-5f, -3.25f, 0f, 10f, 6.5f, 2.5f), PartPose.offsetAndRotation(10.981f, 2.3f, -43.077f, 0.543f, -1.135f, 0f))
            root.addOrReplaceChild("hp4_03", CubeListBuilder.create().texOffs(253, 263).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(14.575f, 2.283f, -34.511f, 0.628f, -1.207f, 0f))
            root.addOrReplaceChild("hp4_04", CubeListBuilder.create().texOffs(278, 263).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(17.519f, 2.275f, -25.691f, 0.668f, -1.288f, 0f))
            root.addOrReplaceChild("hp4_05", CubeListBuilder.create().texOffs(333, 263).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(19.721f, 2.274f, -16.65f, 0.673f, -1.376f, 0f))
            root.addOrReplaceChild("hp4_06", CubeListBuilder.create().texOffs(358, 263).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(21.124f, 2.278f, -7.445f, 0.654f, -1.465f, 0f))
            root.addOrReplaceChild("hp4_07", CubeListBuilder.create().texOffs(0, 264).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(21.687f, 2.281f, 1.851f, 0.637f, -1.557f, 0f))
            root.addOrReplaceChild("hp4_08", CubeListBuilder.create().texOffs(25, 264).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(21.753f, 2.282f, 11.192f, 0.634f, -1.571f, 0f))
            root.addOrReplaceChild("hp4_09", CubeListBuilder.create().texOffs(50, 264).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(21.721f, 2.282f, 20.534f, -2.507f, -1.564f, 3.142f))
            root.addOrReplaceChild("hp4_10", CubeListBuilder.create().texOffs(472, 265).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(21.227f, 2.279f, 29.835f, -2.495f, -1.472f, 3.142f))
            root.addOrReplaceChild("hp4_11", CubeListBuilder.create().texOffs(102, 266).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(19.982f, 2.276f, 39.073f, -2.478f, -1.403f, 3.142f))
            root.addOrReplaceChild("hp4_12", CubeListBuilder.create().texOffs(383, 267).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(18.172f, 2.275f, 48.221f, -2.476f, -1.349f, 3.142f))
            root.addOrReplaceChild("hp4_13", CubeListBuilder.create().texOffs(75, 268).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(15.926f, 2.279f, 57.271f, -2.495f, -1.305f, 3.142f))
            root.addOrReplaceChild("hp4_14", CubeListBuilder.create().texOffs(127, 270).addBox(-5f, -3.5f, 0f, 10f, 7f, 2.5f), PartPose.offsetAndRotation(13.349f, 2.29f, 66.227f, -2.545f, -1.273f, 3.142f))
            root.addOrReplaceChild("hs5_00", CubeListBuilder.create().texOffs(50, 308).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2.5f), PartPose.offsetAndRotation(-1.725f, 6.948f, -56.897f, 0.109f, 1.227f, 0f))
            root.addOrReplaceChild("hs5_01", CubeListBuilder.create().texOffs(88, 303).addBox(-5.25f, -2.75f, 0f, 10.5f, 5.5f, 2.5f), PartPose.offsetAndRotation(-4.821f, 6.901f, -47.799f, 0.538f, 1.229f, 0f))
            root.addOrReplaceChild("hs5_02", CubeListBuilder.create().texOffs(256, 292).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(-7.857f, 6.855f, -38.677f, 0.754f, 1.257f, 0f))
            root.addOrReplaceChild("hs5_03", CubeListBuilder.create().texOffs(26, 254).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(-10.607f, 6.827f, -29.467f, 0.865f, 1.298f, 0f))
            root.addOrReplaceChild("hs5_04", CubeListBuilder.create().texOffs(52, 254).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(-12.935f, 6.813f, -20.14f, 0.918f, 1.352f, 0f))
            root.addOrReplaceChild("hs5_05", CubeListBuilder.create().texOffs(278, 232).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(-14.703f, 6.808f, -10.688f, 0.935f, 1.419f, 0f))
            root.addOrReplaceChild("hs5_06", CubeListBuilder.create().texOffs(333, 232).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(-15.672f, 6.808f, -1.133f, 0.935f, 1.52f, 0f))
            root.addOrReplaceChild("hs5_07", CubeListBuilder.create().texOffs(104, 254).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(-15.915f, 6.809f, 8.496f, 0.932f, 1.571f, 0f))
            root.addOrReplaceChild("hs5_08", CubeListBuilder.create().texOffs(472, 255).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(-15.914f, 6.809f, 18.149f, -2.21f, 1.571f, -3.142f))
            root.addOrReplaceChild("hs5_09", CubeListBuilder.create().texOffs(0, 233).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(-15.482f, 6.808f, 27.754f, -2.208f, 1.481f, -3.142f))
            root.addOrReplaceChild("hs5_10", CubeListBuilder.create().texOffs(26, 233).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(-14.272f, 6.809f, 37.296f, -2.21f, 1.408f, -3.142f))
            root.addOrReplaceChild("hs5_11", CubeListBuilder.create().texOffs(385, 257).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(-12.536f, 6.814f, 46.769f, -2.228f, 1.37f, -3.142f))
            root.addOrReplaceChild("hs5_12", CubeListBuilder.create().texOffs(78, 258).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(-10.525f, 6.825f, 56.189f, -2.27f, 1.349f, -3.142f))
            root.addOrReplaceChild("hs5_13", CubeListBuilder.create().texOffs(437, 260).addBox(-5.25f, -3.5f, 0f, 10.5f, 7f, 2.5f), PartPose.offsetAndRotation(-8.376f, 6.845f, 65.58f, -2.347f, 1.339f, -3.142f))
            root.addOrReplaceChild("hp5_00", CubeListBuilder.create().texOffs(214, 307).addBox(-5.25f, -2.5f, 0f, 10.5f, 5f, 2.5f), PartPose.offsetAndRotation(1.725f, 6.948f, -56.897f, 0.109f, -1.227f, 0f))
            root.addOrReplaceChild("hp5_01", CubeListBuilder.create().texOffs(472, 302).addBox(-5.25f, -2.75f, 0f, 10.5f, 5.5f, 2.5f), PartPose.offsetAndRotation(4.821f, 6.901f, -47.799f, 0.538f, -1.229f, 0f))
            root.addOrReplaceChild("hp5_02", CubeListBuilder.create().texOffs(333, 283).addBox(-5.25f, -3.25f, 0f, 10.5f, 6.5f, 2.5f), PartPose.offsetAndRotation(7.857f, 6.855f, -38.677f, 0.754f, -1.257f, 0f))
            root.addOrReplaceChild("hp5_03", CubeListBuilder.create().texOffs(333, 243).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(10.607f, 6.827f, -29.467f, 0.865f, -1.298f, 0f))
            root.addOrReplaceChild("hp5_04", CubeListBuilder.create().texOffs(359, 243).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(12.935f, 6.813f, -20.14f, 0.918f, -1.352f, 0f))
            root.addOrReplaceChild("hp5_05", CubeListBuilder.create().texOffs(101, 225).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(14.703f, 6.808f, -10.688f, 0.935f, -1.419f, 0f))
            root.addOrReplaceChild("hp5_06", CubeListBuilder.create().texOffs(398, 226).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(15.672f, 6.808f, -1.133f, 0.935f, -1.52f, 0f))
            root.addOrReplaceChild("hp5_07", CubeListBuilder.create().texOffs(0, 244).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(15.915f, 6.809f, 8.496f, 0.932f, -1.571f, 0f))
            root.addOrReplaceChild("hp5_08", CubeListBuilder.create().texOffs(26, 244).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(15.914f, 6.809f, 18.149f, -2.21f, -1.571f, 3.142f))
            root.addOrReplaceChild("hp5_09", CubeListBuilder.create().texOffs(424, 229).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(15.482f, 6.808f, 27.754f, -2.208f, -1.481f, 3.142f))
            root.addOrReplaceChild("hp5_10", CubeListBuilder.create().texOffs(226, 232).addBox(-5.25f, -4f, 0f, 10.5f, 8f, 2.5f), PartPose.offsetAndRotation(14.272f, 6.809f, 37.296f, -2.21f, -1.408f, 3.142f))
            root.addOrReplaceChild("hp5_11", CubeListBuilder.create().texOffs(52, 244).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(12.536f, 6.814f, 46.769f, -2.228f, -1.37f, 3.142f))
            root.addOrReplaceChild("hp5_12", CubeListBuilder.create().texOffs(124, 244).addBox(-5.25f, -3.75f, 0f, 10.5f, 7.5f, 2.5f), PartPose.offsetAndRotation(10.525f, 6.825f, 56.189f, -2.27f, -1.349f, 3.142f))
            root.addOrReplaceChild("hp5_13", CubeListBuilder.create().texOffs(411, 260).addBox(-5.25f, -3.5f, 0f, 10.5f, 7f, 2.5f), PartPose.offsetAndRotation(8.376f, 6.845f, 65.58f, -2.347f, -1.339f, 3.142f))
            root.addOrReplaceChild("hcs0_00", CubeListBuilder.create().texOffs(0, 302).addBox(-5.5f, -3f, 0f, 11f, 6f, 2f), PartPose.offsetAndRotation(-26.713f, -22.656f, 40.498f, 2.949f, 1.435f, -3.142f))
            root.addOrReplaceChild("hcs0_01", CubeListBuilder.create().texOffs(268, 302).addBox(-5.5f, -3f, 0f, 11f, 6f, 2f), PartPose.offsetAndRotation(-24.997f, -22.656f, 50.419f, 2.956f, 1.364f, -3.142f))
            root.addOrReplaceChild("hcs0_02", CubeListBuilder.create().texOffs(294, 302).addBox(-5.5f, -3f, 0f, 11f, 6f, 2f), PartPose.offsetAndRotation(-22.595f, -22.655f, 60.199f, 2.964f, 1.296f, -3.142f))
            root.addOrReplaceChild("hcs0_03", CubeListBuilder.create().texOffs(388, 305).addBox(-5.25f, -3f, 0f, 10.5f, 6f, 2f), PartPose.offsetAndRotation(-19.646f, -22.656f, 69.812f, 2.962f, 1.25f, -3.142f))
            root.addOrReplaceChild("hcp0_00", CubeListBuilder.create().texOffs(216, 299).addBox(-5.5f, -3f, 0f, 11f, 6f, 2f), PartPose.offsetAndRotation(26.713f, -22.656f, 40.498f, 2.949f, -1.435f, 3.142f))
            root.addOrReplaceChild("hcp0_01", CubeListBuilder.create().texOffs(48, 300).addBox(-5.5f, -3f, 0f, 11f, 6f, 2f), PartPose.offsetAndRotation(24.997f, -22.656f, 50.419f, 2.956f, -1.364f, 3.142f))
            root.addOrReplaceChild("hcp0_02", CubeListBuilder.create().texOffs(242, 301).addBox(-5.5f, -3f, 0f, 11f, 6f, 2f), PartPose.offsetAndRotation(22.595f, -22.655f, 60.199f, 2.964f, -1.296f, 3.142f))
            root.addOrReplaceChild("hcp0_03", CubeListBuilder.create().texOffs(363, 305).addBox(-5.25f, -3f, 0f, 10.5f, 6f, 2f), PartPose.offsetAndRotation(19.646f, -22.656f, 69.812f, 2.962f, -1.25f, 3.142f))
            root.addOrReplaceChild("hcs1_00", CubeListBuilder.create().texOffs(250, 344).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(-26.918f, -26.668f, 39.575f, 2.822f, 1.443f, -3.142f))
            root.addOrReplaceChild("hcs1_01", CubeListBuilder.create().texOffs(146, 346).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(-25.637f, -26.667f, 47.665f, 2.825f, 1.385f, -3.142f))
            root.addOrReplaceChild("hcs1_02", CubeListBuilder.create().texOffs(272, 346).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(-23.898f, -26.667f, 55.67f, 2.829f, 1.329f, -3.142f))
            root.addOrReplaceChild("hcs1_03", CubeListBuilder.create().texOffs(294, 346).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(-21.722f, -26.667f, 63.567f, 2.833f, 1.275f, -3.142f))
            root.addOrReplaceChild("hcs1_04", CubeListBuilder.create().texOffs(76, 348).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(-19.317f, -26.666f, 71.369f, 2.834f, 1.268f, -3.142f))
            root.addOrReplaceChild("hcp1_00", CubeListBuilder.create().texOffs(265, 178).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(26.918f, -26.668f, 39.575f, 2.822f, -1.443f, 3.142f))
            root.addOrReplaceChild("hcp1_01", CubeListBuilder.create().texOffs(83, 214).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(25.637f, -26.667f, 47.665f, 2.825f, -1.385f, 3.142f))
            root.addOrReplaceChild("hcp1_02", CubeListBuilder.create().texOffs(349, 214).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(23.898f, -26.667f, 55.67f, 2.829f, -1.329f, 3.142f))
            root.addOrReplaceChild("hcp1_03", CubeListBuilder.create().texOffs(52, 284).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(21.722f, -26.667f, 63.567f, 2.833f, -1.275f, 3.142f))
            root.addOrReplaceChild("hcp1_04", CubeListBuilder.create().texOffs(172, 344).addBox(-4.5f, -2f, 0f, 9f, 4f, 2f), PartPose.offsetAndRotation(19.317f, -26.666f, 71.369f, 2.834f, -1.268f, 3.142f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildStem(root: PartDefinition) {
            root.addOrReplaceChild("stem0", CubeListBuilder.create().texOffs(424, 298).addBox(-2.5f, -3.25f, 0f, 5f, 6.5f, 4.5f), PartPose.offsetAndRotation(0f, 7.05f, -60.828f, 0.423f, 0f, 0f))
            root.addOrReplaceChild("stem1", CubeListBuilder.create().texOffs(385, 0).addBox(-2.5f, -4f, 0f, 5f, 8f, 4.5f), PartPose.offsetAndRotation(0f, 1.25f, -63.438f, 0.401f, 0f, 0f))
            root.addOrReplaceChild("stem2", CubeListBuilder.create().texOffs(346, 302).addBox(-2.25f, -4f, 0f, 4.5f, 8f, 4f), PartPose.offsetAndRotation(0f, -5.5f, -66f, 0.349f, 0f, 0f))
            root.addOrReplaceChild("stem3", CubeListBuilder.create().texOffs(258, 310).addBox(-2f, -4f, 0f, 4f, 8f, 4f), PartPose.offsetAndRotation(0f, -12.5f, -68.545f, 0.349f, 0f, 0f))
            root.addOrReplaceChild("stem4", CubeListBuilder.create().texOffs(443, 313).addBox(-2f, -3.5f, 0f, 4f, 7f, 4f), PartPose.offsetAndRotation(0f, -19f, -70.908f, 0.349f, 0f, 0f))
            root.addOrReplaceChild("stem5", CubeListBuilder.create().texOffs(130, 174).addBox(-2f, -2.5f, 0f, 4f, 5f, 3.5f), PartPose.offsetAndRotation(0f, -24f, -72.4f, 0.197f, 0f, 0f))
            root.addOrReplaceChild("stem6", CubeListBuilder.create().texOffs(496, 322).addBox(-1.75f, -3f, 0f, 3.5f, 6f, 3.5f), PartPose.offsetAndRotation(0f, -28.5f, -73.3f, 0.197f, 0f, 0f))
            root.addOrReplaceChild("keel", CubeListBuilder.create().texOffs(472, 0).addBox(-2.5f, 0f, -0.75f, 5f, 131f, 1.5f), PartPose.offsetAndRotation(0f, 9.35f, -59f, 1.571f, 0f, 0f))
            root.addOrReplaceChild("sternpost", CubeListBuilder.create().texOffs(58, 186).addBox(-2f, -12f, 72f, 4f, 22f, 4f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("sternpost_heel", CubeListBuilder.create().texOffs(98, 212).addBox(-2.25f, 7f, 68f, 4.5f, 3.5f, 8f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("tr0", CubeListBuilder.create().texOffs(154, 131).addBox(-17f, -2.5f, -2f, 34f, 5f, 2f), PartPose.offsetAndRotation(0f, -18.375f, 73.417f, -0.219f, 0f, 0f))
            root.addOrReplaceChild("tr1", CubeListBuilder.create().texOffs(226, 163).addBox(-17f, -2.5f, -2f, 34f, 5f, 2f), PartPose.offsetAndRotation(0f, -14.125f, 72.472f, -0.219f, 0f, 0f))
            root.addOrReplaceChild("tr2", CubeListBuilder.create().texOffs(385, 79).addBox(-16.5f, -3.25f, -2f, 33f, 6.5f, 2f), PartPose.offsetAndRotation(0f, -9f, 71.73f, -0.09f, 0f, 0f))
            root.addOrReplaceChild("tr3", CubeListBuilder.create().texOffs(224, 170).addBox(-14.75f, -3f, -2f, 29.5f, 6f, 2f), PartPose.offsetAndRotation(0f, -3.25f, 71.213f, -0.09f, 0f, 0f))
            root.addOrReplaceChild("tr4", CubeListBuilder.create().texOffs(185, 121).addBox(-11.5f, -2.75f, -2f, 23f, 5.5f, 2f), PartPose.offsetAndRotation(0f, 2f, 70.74f, -0.09f, 0f, 0f))
            root.addOrReplaceChild("tr5", CubeListBuilder.create().texOffs(394, 298).addBox(-6.5f, -2.5f, -2f, 13f, 5f, 2f), PartPose.offsetAndRotation(0f, 6.6f, 70.326f, -0.09f, 0f, 0f))
            root.addOrReplaceChild("tr6", CubeListBuilder.create().texOffs(154, 163).addBox(-17f, -2.75f, -2f, 34f, 5.5f, 2f), PartPose.offsetAndRotation(0f, -23f, 74.444f, -0.219f, 0f, 0f))
            root.addOrReplaceChild("tr7", CubeListBuilder.create().texOffs(226, 131).addBox(-17f, -1.75f, -2f, 34f, 3.5f, 2f), PartPose.offsetAndRotation(0f, -27f, 75.333f, -0.219f, 0f, 0f))
            root.addOrReplaceChild("bottom", CubeListBuilder.create().texOffs(0, 0).addBox(-14f, 7f, -58f, 28f, 1.5f, 129f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("caps00", CubeListBuilder.create().texOffs(51, 343).addBox(-6f, -2f, 0f, 12f, 4f, 1.5f), PartPose.offsetAndRotation(-3.546f, -25.5f, -65.583f, 1.84f, -0.86f, 2.793f))
            root.addOrReplaceChild("caps01", CubeListBuilder.create().texOffs(201, 343).addBox(-5.5f, -2f, 0f, 11f, 4f, 1.5f), PartPose.offsetAndRotation(-10.061f, -23.349f, -56.75f, 1.804f, -0.965f, 2.86f))
            root.addOrReplaceChild("caps02", CubeListBuilder.create().texOffs(226, 344).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(-15.341f, -22.249f, -47.917f, 1.665f, -1.081f, 3.035f))
            root.addOrReplaceChild("caps03", CubeListBuilder.create().texOffs(201, 349).addBox(-5f, -2f, 0f, 10f, 4f, 1.5f), PartPose.offsetAndRotation(-19.464f, -22f, -39.083f, 1.571f, -1.187f, 3.142f))
            root.addOrReplaceChild("caps04", CubeListBuilder.create().texOffs(386, 351).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-22.518f, -22f, -30.25f, 1.571f, -1.291f, 3.142f))
            root.addOrReplaceChild("caps05", CubeListBuilder.create().texOffs(408, 351).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-24.602f, -22f, -21.417f, 1.571f, -1.389f, 3.142f))
            root.addOrReplaceChild("caps06", CubeListBuilder.create().texOffs(120, 352).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-25.837f, -22f, -12.583f, 1.571f, -1.476f, 3.142f))
            root.addOrReplaceChild("caps07", CubeListBuilder.create().texOffs(268, 352).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-26.378f, -22f, -3.75f, 1.571f, -1.544f, 3.142f))
            root.addOrReplaceChild("caps08", CubeListBuilder.create().texOffs(290, 352).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-26.499f, -22f, 5.083f, 1.571f, -1.571f, 3.142f))
            root.addOrReplaceChild("caps09", CubeListBuilder.create().texOffs(67, 354).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-26.5f, -22f, 13.917f, -1.571f, -1.571f, 0f))
            root.addOrReplaceChild("caps10", CubeListBuilder.create().texOffs(45, 355).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-26.451f, -22f, 22.75f, -1.571f, -1.56f, 0f))
            root.addOrReplaceChild("caps11", CubeListBuilder.create().texOffs(201, 355).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(-26.088f, -22f, 31.583f, -1.571f, -1.5f, 0f))
            root.addOrReplaceChild("ccaps00", CubeListBuilder.create().texOffs(416, 345).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(-25.169f, -30.2f, 40.41f, -1.571f, -1.437f, 0f))
            root.addOrReplaceChild("ccaps01", CubeListBuilder.create().texOffs(0, 346).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(-23.491f, -30.2f, 50.229f, -1.571f, -1.367f, 0f))
            root.addOrReplaceChild("ccaps02", CubeListBuilder.create().texOffs(122, 346).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(-21.105f, -30.2f, 60.048f, -1.571f, -1.299f, 0f))
            root.addOrReplaceChild("ccaps03", CubeListBuilder.create().texOffs(97, 344).addBox(-5.5f, -2f, 0f, 11f, 4f, 1.5f), PartPose.offsetAndRotation(-18.12f, -30.2f, 69.868f, -1.571f, -1.253f, 0f))
            root.addOrReplaceChild("capp00", CubeListBuilder.create().texOffs(24, 343).addBox(-6f, -2f, 0f, 12f, 4f, 1.5f), PartPose.offsetAndRotation(3.546f, -25.5f, -65.583f, -1.84f, -0.86f, 0.349f))
            root.addOrReplaceChild("capp01", CubeListBuilder.create().texOffs(221, 223).addBox(-5.5f, -2f, 0f, 11f, 4f, 1.5f), PartPose.offsetAndRotation(10.061f, -23.349f, -56.75f, -1.804f, -0.965f, 0.282f))
            root.addOrReplaceChild("capp02", CubeListBuilder.create().texOffs(239, 213).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(15.341f, -22.249f, -47.917f, -1.665f, -1.081f, 0.106f))
            root.addOrReplaceChild("capp03", CubeListBuilder.create().texOffs(45, 349).addBox(-5f, -2f, 0f, 10f, 4f, 1.5f), PartPose.offsetAndRotation(19.464f, -22f, -39.083f, -1.571f, -1.187f, 0f))
            root.addOrReplaceChild("capp04", CubeListBuilder.create().texOffs(364, 349).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(22.518f, -22f, -30.25f, -1.571f, -1.291f, 0f))
            root.addOrReplaceChild("capp05", CubeListBuilder.create().texOffs(98, 350).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(24.602f, -22f, -21.417f, -1.571f, -1.389f, 0f))
            root.addOrReplaceChild("capp06", CubeListBuilder.create().texOffs(224, 350).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(25.837f, -22f, -12.583f, -1.571f, -1.476f, 0f))
            root.addOrReplaceChild("capp07", CubeListBuilder.create().texOffs(246, 350).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(26.378f, -22f, -3.75f, -1.571f, -1.544f, 0f))
            root.addOrReplaceChild("capp08", CubeListBuilder.create().texOffs(439, 350).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(26.499f, -22f, 5.083f, -1.571f, -1.571f, 0f))
            root.addOrReplaceChild("capp09", CubeListBuilder.create().texOffs(461, 350).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(26.5f, -22f, 13.917f, -1.571f, -1.571f, 0f))
            root.addOrReplaceChild("capp10", CubeListBuilder.create().texOffs(315, 351).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(26.451f, -22f, 22.75f, 1.571f, -1.56f, 3.142f))
            root.addOrReplaceChild("capp11", CubeListBuilder.create().texOffs(337, 351).addBox(-4.75f, -2f, 0f, 9.5f, 4f, 1.5f), PartPose.offsetAndRotation(26.088f, -22f, 31.583f, 1.571f, -1.5f, 3.142f))
            root.addOrReplaceChild("ccapp00", CubeListBuilder.create().texOffs(317, 345).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(25.169f, -30.2f, 40.41f, 1.571f, -1.437f, 3.142f))
            root.addOrReplaceChild("ccapp01", CubeListBuilder.create().texOffs(341, 345).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(23.491f, -30.2f, 50.229f, 1.571f, -1.367f, 3.142f))
            root.addOrReplaceChild("ccapp02", CubeListBuilder.create().texOffs(392, 345).addBox(-5.25f, -2f, 0f, 10.5f, 4f, 1.5f), PartPose.offsetAndRotation(21.105f, -30.2f, 60.048f, 1.571f, -1.299f, 3.142f))
            root.addOrReplaceChild("ccapp03", CubeListBuilder.create().texOffs(367, 343).addBox(-5.5f, -2f, 0f, 11f, 4f, 1.5f), PartPose.offsetAndRotation(18.12f, -30.2f, 69.868f, 1.571f, -1.253f, 3.142f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildDecks(root: PartDefinition) {
            root.addOrReplaceChild("deck_main", CubeListBuilder.create().texOffs(185, 0).addBox(-28f, -12f, -51f, 56f, 1.5f, 87.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deck_fore", CubeListBuilder.create().texOffs(154, 138).addBox(-28f, -20f, -73f, 56f, 1.5f, 23.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deck_castle", CubeListBuilder.create().texOffs(0, 131).addBox(-28f, -30f, 35.5f, 56f, 1.5f, 41.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("hold_floor", CubeListBuilder.create().texOffs(266, 89).addBox(-20f, 3f, -4f, 40f, 1f, 48f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("coaming_f", CubeListBuilder.create().texOffs(312, 342).addBox(-13f, -13.6f, 9f, 26f, 1.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("coaming_b", CubeListBuilder.create().texOffs(52, 126).addBox(-13f, -13.6f, 29.5f, 26f, 1.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("coaming_l", CubeListBuilder.create().texOffs(227, 57).addBox(11.5f, -13.6f, 10.5f, 1.5f, 1.5f, 19f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("coaming_r", CubeListBuilder.create().texOffs(426, 58).addBox(-13f, -13.6f, 10.5f, 1.5f, 1.5f, 19f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("grating", CubeListBuilder.create().texOffs(185, 0).addBox(-11.5f, -12.9f, 10.5f, 23f, 1f, 19f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("fore_break", CubeListBuilder.create().texOffs(394, 125).addBox(-13.779f, -20.2f, -51.5f, 27.5f, 8.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("fore_step1", CubeListBuilder.create().texOffs(88, 296).addBox(-6.5f, -16f, -49.5f, 13f, 4f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("fore_step2", CubeListBuilder.create().texOffs(142, 341).addBox(-6.5f, -14f, -46.5f, 13f, 2f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("cabin_front", CubeListBuilder.create().texOffs(0, 0).addBox(-25.982f, -28.5f, 36f, 52f, 16.5f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("door_lintel", CubeListBuilder.create().texOffs(115, 212).addBox(-5.6f, -29f, 34.8f, 11f, 1.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("door_jamb_l", CubeListBuilder.create().texOffs(261, 57).addBox(4.2f, -27.6f, 34.8f, 1.5f, 15.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("door_jamb_r", CubeListBuilder.create().texOffs(18, 189).addBox(-5.7f, -27.6f, 34.8f, 1.5f, 15.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("step0", CubeListBuilder.create().texOffs(0, 96).addBox(13f, -16.55f, 24f, 10.5f, 4.5f, 12f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("step1", CubeListBuilder.create().texOffs(243, 107).addBox(12.85f, -21.1f, 27f, 10.5f, 9f, 9f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("step2", CubeListBuilder.create().texOffs(0, 131).addBox(12.7f, -25.65f, 30f, 10.5f, 13.5f, 6f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("step3", CubeListBuilder.create().texOffs(472, 158).addBox(12.55f, -30.2f, 33f, 10.5f, 18f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("stringer_a", CubeListBuilder.create().texOffs(195, 337).addBox(-0.75f, 0f, -0.75f, 1.5f, 23f, 1.5f), PartPose.offsetAndRotation(12f, -11.5f, 22.5f, 0.63f, 0f, 3.142f))
            root.addOrReplaceChild("stringer_b", CubeListBuilder.create().texOffs(490, 339).addBox(-0.75f, 0f, -0.75f, 1.5f, 23f, 1.5f), PartPose.offsetAndRotation(23f, -11.5f, 22.5f, 0.63f, 0f, 3.142f))
            root.addOrReplaceChild("rps0", CubeListBuilder.create().texOffs(235, 356).addBox(-25.802f, -42.5f, 37.7f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rps1", CubeListBuilder.create().texOffs(241, 356).addBox(-25.026f, -42.5f, 43.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rps2", CubeListBuilder.create().texOffs(247, 356).addBox(-23.926f, -42.5f, 49.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rps3", CubeListBuilder.create().texOffs(253, 356).addBox(-22.563f, -42.5f, 55.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rps4", CubeListBuilder.create().texOffs(259, 356).addBox(-20.935f, -42.5f, 61.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rps5", CubeListBuilder.create().texOffs(438, 356).addBox(-19.044f, -42.5f, 67.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rps6", CubeListBuilder.create().texOffs(444, 356).addBox(-16.889f, -42.5f, 73.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rcs0", CubeListBuilder.create().texOffs(115, 215).addBox(-3f, -1.25f, 0f, 6f, 2.5f, 1.5f), PartPose.offsetAndRotation(-24.614f, -44f, 41.25f, -1.571f, -1.431f, 0f))
            root.addOrReplaceChild("rms0", CubeListBuilder.create().texOffs(259, 192).addBox(-3f, -0.5f, 0f, 6f, 1f, 1f), PartPose.offsetAndRotation(-24.614f, -38f, 41.25f, -1.571f, -1.431f, 0f))
            root.addOrReplaceChild("rcs1", CubeListBuilder.create().texOffs(193, 206).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-23.676f, -44f, 47f, -1.571f, -1.39f, 0f))
            root.addOrReplaceChild("rms1", CubeListBuilder.create().texOffs(133, 198).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(-23.676f, -38f, 47f, -1.571f, -1.39f, 0f))
            root.addOrReplaceChild("rcs2", CubeListBuilder.create().texOffs(127, 266).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-22.445f, -44f, 53f, -1.571f, -1.347f, 0f))
            root.addOrReplaceChild("rms2", CubeListBuilder.create().texOffs(148, 198).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(-22.445f, -38f, 53f, -1.571f, -1.347f, 0f))
            root.addOrReplaceChild("rcs3", CubeListBuilder.create().texOffs(423, 317).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-20.949f, -44f, 59f, -1.571f, -1.306f, 0f))
            root.addOrReplaceChild("rms3", CubeListBuilder.create().texOffs(163, 198).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(-20.949f, -38f, 59f, -1.571f, -1.306f, 0f))
            root.addOrReplaceChild("rcs4", CubeListBuilder.create().texOffs(98, 206).addBox(-3.5f, -1.25f, 0f, 7f, 2.5f, 1.5f), PartPose.offsetAndRotation(-19.19f, -44f, 65f, -1.571f, -1.265f, 0f))
            root.addOrReplaceChild("rms4", CubeListBuilder.create().texOffs(401, 77).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(-19.19f, -38f, 65f, -1.571f, -1.265f, 0f))
            root.addOrReplaceChild("rcs5", CubeListBuilder.create().texOffs(133, 206).addBox(-3.5f, -1.25f, 0f, 7f, 2.5f, 1.5f), PartPose.offsetAndRotation(-17.166f, -44f, 71f, -1.571f, -1.226f, 0f))
            root.addOrReplaceChild("rms5", CubeListBuilder.create().texOffs(0, 113).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(-17.166f, -38f, 71f, -1.571f, -1.226f, 0f))
            root.addOrReplaceChild("rpp0", CubeListBuilder.create().texOffs(381, 355).addBox(24.202f, -42.5f, 37.7f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rpp1", CubeListBuilder.create().texOffs(95, 356).addBox(23.426f, -42.5f, 43.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rpp2", CubeListBuilder.create().texOffs(101, 356).addBox(22.326f, -42.5f, 49.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rpp3", CubeListBuilder.create().texOffs(107, 356).addBox(20.963f, -42.5f, 55.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rpp4", CubeListBuilder.create().texOffs(113, 356).addBox(19.335f, -42.5f, 61.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rpp5", CubeListBuilder.create().texOffs(223, 356).addBox(17.444f, -42.5f, 67.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rpp6", CubeListBuilder.create().texOffs(229, 356).addBox(15.289f, -42.5f, 73.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rcp0", CubeListBuilder.create().texOffs(457, 10).addBox(-3f, -1.25f, 0f, 6f, 2.5f, 1.5f), PartPose.offsetAndRotation(24.614f, -44f, 41.25f, 1.571f, -1.431f, 3.142f))
            root.addOrReplaceChild("rmp0", CubeListBuilder.create().texOffs(32, 113).addBox(-3f, -0.5f, 0f, 6f, 1f, 1f), PartPose.offsetAndRotation(24.614f, -38f, 41.25f, 1.571f, -1.431f, 3.142f))
            root.addOrReplaceChild("rcp1", CubeListBuilder.create().texOffs(161, 202).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(23.676f, -44f, 47f, 1.571f, -1.39f, 3.142f))
            root.addOrReplaceChild("rmp1", CubeListBuilder.create().texOffs(66, 124).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(23.676f, -38f, 47f, 1.571f, -1.39f, 3.142f))
            root.addOrReplaceChild("rcp2", CubeListBuilder.create().texOffs(193, 202).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(22.445f, -44f, 53f, 1.571f, -1.347f, 3.142f))
            root.addOrReplaceChild("rmp2", CubeListBuilder.create().texOffs(453, 181).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(22.445f, -38f, 53f, 1.571f, -1.347f, 3.142f))
            root.addOrReplaceChild("rcp3", CubeListBuilder.create().texOffs(161, 206).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(20.949f, -44f, 59f, 1.571f, -1.306f, 3.142f))
            root.addOrReplaceChild("rmp3", CubeListBuilder.create().texOffs(118, 198).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(20.949f, -38f, 59f, 1.571f, -1.306f, 3.142f))
            root.addOrReplaceChild("rcp4", CubeListBuilder.create().texOffs(98, 202).addBox(-3.5f, -1.25f, 0f, 7f, 2.5f, 1.5f), PartPose.offsetAndRotation(19.19f, -44f, 65f, 1.571f, -1.265f, 3.142f))
            root.addOrReplaceChild("rmp4", CubeListBuilder.create().texOffs(202, 76).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(19.19f, -38f, 65f, 1.571f, -1.265f, 3.142f))
            root.addOrReplaceChild("rcp5", CubeListBuilder.create().texOffs(133, 202).addBox(-3.5f, -1.25f, 0f, 7f, 2.5f, 1.5f), PartPose.offsetAndRotation(17.166f, -44f, 71f, 1.571f, -1.226f, 3.142f))
            root.addOrReplaceChild("rmp5", CubeListBuilder.create().texOffs(385, 77).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(17.166f, -38f, 71f, 1.571f, -1.226f, 3.142f))
            root.addOrReplaceChild("rs0", CubeListBuilder.create().texOffs(450, 356).addBox(-18.8f, -42.5f, 73.6f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rs1", CubeListBuilder.create().texOffs(456, 356).addBox(-12.8f, -42.5f, 73.6f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rs2", CubeListBuilder.create().texOffs(462, 356).addBox(-6.8f, -42.5f, 73.6f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rs3", CubeListBuilder.create().texOffs(468, 356).addBox(-0.8f, -42.5f, 73.6f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rs4", CubeListBuilder.create().texOffs(474, 356).addBox(5.2f, -42.5f, 73.6f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rs5", CubeListBuilder.create().texOffs(162, 357).addBox(11.2f, -42.5f, 73.6f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rs6", CubeListBuilder.create().texOffs(168, 357).addBox(17.2f, -42.5f, 73.6f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rsc0", CubeListBuilder.create().texOffs(25, 318).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-15f, -44f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsm0", CubeListBuilder.create().texOffs(42, 206).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(-15f, -38f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsc1", CubeListBuilder.create().texOffs(206, 330).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-9f, -44f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsm1", CubeListBuilder.create().texOffs(256, 206).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(-9f, -38f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsc2", CubeListBuilder.create().texOffs(317, 357).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-3f, -44f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsm2", CubeListBuilder.create().texOffs(296, 206).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(-3f, -38f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsc3", CubeListBuilder.create().texOffs(333, 357).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(3f, -44f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsm3", CubeListBuilder.create().texOffs(225, 207).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(3f, -38f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsc4", CubeListBuilder.create().texOffs(387, 357).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(9f, -44f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsm4", CubeListBuilder.create().texOffs(349, 220).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(9f, -38f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsc5", CubeListBuilder.create().texOffs(403, 357).addBox(-3.25f, -1.25f, 0f, 6.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(15f, -44f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rsm5", CubeListBuilder.create().texOffs(46, 229).addBox(-3.25f, -0.5f, 0f, 6.5f, 1f, 1f), PartPose.offsetAndRotation(15f, -38f, 74.4f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rf0", CubeListBuilder.create().texOffs(291, 170).addBox(-25.8f, -42.5f, 36.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rf1", CubeListBuilder.create().texOffs(306, 285).addBox(-17.8f, -42.5f, 36.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rf2", CubeListBuilder.create().texOffs(483, 349).addBox(-9.8f, -42.5f, 36.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rf3", CubeListBuilder.create().texOffs(150, 352).addBox(-1.8f, -42.5f, 36.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rf4", CubeListBuilder.create().texOffs(156, 352).addBox(5.2f, -42.5f, 36.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rfc0", CubeListBuilder.create().texOffs(428, 201).addBox(-4.25f, -1.25f, 0f, 8.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-21f, -44f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rfm0", CubeListBuilder.create().texOffs(0, 57).addBox(-4.25f, -0.5f, 0f, 8.5f, 1f, 1f), PartPose.offsetAndRotation(-21f, -38f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rfc1", CubeListBuilder.create().texOffs(256, 202).addBox(-4.25f, -1.25f, 0f, 8.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-13f, -44f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rfm1", CubeListBuilder.create().texOffs(19, 57).addBox(-4.25f, -0.5f, 0f, 8.5f, 1f, 1f), PartPose.offsetAndRotation(-13f, -38f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rfc2", CubeListBuilder.create().texOffs(104, 248).addBox(-4.25f, -1.25f, 0f, 8.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-5f, -44f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rfm2", CubeListBuilder.create().texOffs(38, 57).addBox(-4.25f, -0.5f, 0f, 8.5f, 1f, 1f), PartPose.offsetAndRotation(-5f, -38f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rfc3", CubeListBuilder.create().texOffs(394, 202).addBox(-3.75f, -1.25f, 0f, 7.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(2.5f, -44f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rfm3", CubeListBuilder.create().texOffs(185, 76).addBox(-3.75f, -0.5f, 0f, 7.5f, 1f, 1f), PartPose.offsetAndRotation(2.5f, -38f, 37f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("rf_stair_b", CubeListBuilder.create().texOffs(89, 354).addBox(23.7f, -42.5f, 36.2f, 1.5f, 12.5f, 1.5f), PartPose.offset(0f, 0f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildMast(root: PartDefinition) {
            root.addOrReplaceChild("mast0", CubeListBuilder.create().texOffs(448, 39).addBox(-2.5f, -8.25f, -2.5f, 5f, 16.5f, 5f), PartPose.offset(0f, -20f, -8f))
            root.addOrReplaceChild("mast0b", CubeListBuilder.create().texOffs(154, 138).addBox(-2.5f, -8.25f, -2.5f, 5f, 16.5f, 5f), PartPose.offsetAndRotation(0f, -19.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast1", CubeListBuilder.create().texOffs(448, 183).addBox(-2.5f, -8.25f, -2.5f, 5f, 16.5f, 5f), PartPose.offset(0f, -36f, -8f))
            root.addOrReplaceChild("mast1b", CubeListBuilder.create().texOffs(333, 186).addBox(-2.5f, -8.25f, -2.5f, 5f, 16.5f, 5f), PartPose.offsetAndRotation(0f, -35.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast2", CubeListBuilder.create().texOffs(0, 189).addBox(-2.25f, -8.25f, -2.25f, 4.5f, 16.5f, 4.5f), PartPose.offset(0f, -52f, -8f))
            root.addOrReplaceChild("mast2b", CubeListBuilder.create().texOffs(353, 193).addBox(-2.25f, -8.25f, -2.25f, 4.5f, 16.5f, 4.5f), PartPose.offsetAndRotation(0f, -51.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast3", CubeListBuilder.create().texOffs(472, 193).addBox(-2.25f, -8.25f, -2.25f, 4.5f, 16.5f, 4.5f), PartPose.offset(0f, -68f, -8f))
            root.addOrReplaceChild("mast3b", CubeListBuilder.create().texOffs(490, 193).addBox(-2.25f, -8.25f, -2.25f, 4.5f, 16.5f, 4.5f), PartPose.offsetAndRotation(0f, -67.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast4", CubeListBuilder.create().texOffs(46, 208).addBox(-2f, -8.25f, -2f, 4f, 16.5f, 4f), PartPose.offset(0f, -84f, -8f))
            root.addOrReplaceChild("mast4b", CubeListBuilder.create().texOffs(296, 208).addBox(-2f, -8.25f, -2f, 4f, 16.5f, 4f), PartPose.offsetAndRotation(0f, -83.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast5", CubeListBuilder.create().texOffs(333, 208).addBox(-2f, -8.25f, -2f, 4f, 16.5f, 4f), PartPose.offset(0f, -100f, -8f))
            root.addOrReplaceChild("mast5b", CubeListBuilder.create().texOffs(30, 212).addBox(-2f, -8.25f, -2f, 4f, 16.5f, 4f), PartPose.offsetAndRotation(0f, -99.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast6", CubeListBuilder.create().texOffs(85, 20).addBox(-1.75f, -8.25f, -1.75f, 3.5f, 16.5f, 3.5f), PartPose.offset(0f, -116f, -8f))
            root.addOrReplaceChild("mast6b", CubeListBuilder.create().texOffs(298, 163).addBox(-1.75f, -8.25f, -1.75f, 3.5f, 16.5f, 3.5f), PartPose.offsetAndRotation(0f, -115.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast7", CubeListBuilder.create().texOffs(299, 186).addBox(-1.75f, -8.25f, -1.75f, 3.5f, 16.5f, 3.5f), PartPose.offset(0f, -132f, -8f))
            root.addOrReplaceChild("mast7b", CubeListBuilder.create().texOffs(87, 221).addBox(-1.75f, -8.25f, -1.75f, 3.5f, 16.5f, 3.5f), PartPose.offsetAndRotation(0f, -131.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast8", CubeListBuilder.create().texOffs(498, 242).addBox(-1.5f, -8.25f, -1.5f, 3f, 16.5f, 3f), PartPose.offset(0f, -148f, -8f))
            root.addOrReplaceChild("mast8b", CubeListBuilder.create().texOffs(498, 262).addBox(-1.5f, -8.25f, -1.5f, 3f, 16.5f, 3f), PartPose.offsetAndRotation(0f, -147.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast9", CubeListBuilder.create().texOffs(498, 282).addBox(-1.5f, -8.25f, -1.5f, 3f, 16.5f, 3f), PartPose.offset(0f, -164f, -8f))
            root.addOrReplaceChild("mast9b", CubeListBuilder.create().texOffs(76, 292).addBox(-1.5f, -8.25f, -1.5f, 3f, 16.5f, 3f), PartPose.offsetAndRotation(0f, -163.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("mast10", CubeListBuilder.create().texOffs(249, 57).addBox(-1.5f, -7.25f, -1.5f, 3f, 14.5f, 3f), PartPose.offset(0f, -179f, -8f))
            root.addOrReplaceChild("mast10b", CubeListBuilder.create().texOffs(298, 89).addBox(-1.5f, -7.25f, -1.5f, 3f, 14.5f, 3f), PartPose.offsetAndRotation(0f, -178.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hoop0", CubeListBuilder.create().texOffs(26, 302).addBox(-3.25f, -0.75f, -3.25f, 6.5f, 1.5f, 6.5f), PartPose.offset(0f, -17f, -8f))
            root.addOrReplaceChild("hoop0b", CubeListBuilder.create().texOffs(320, 302).addBox(-3.25f, -0.75f, -3.25f, 6.5f, 1.5f, 6.5f), PartPose.offsetAndRotation(0f, -16.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hoop1", CubeListBuilder.create().texOffs(299, 310).addBox(-3f, -0.75f, -3f, 6f, 1.5f, 6f), PartPose.offset(0f, -34f, -8f))
            root.addOrReplaceChild("hoop1b", CubeListBuilder.create().texOffs(323, 310).addBox(-3f, -0.75f, -3f, 6f, 1.5f, 6f), PartPose.offsetAndRotation(0f, -33.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hoop2", CubeListBuilder.create().texOffs(472, 310).addBox(-3f, -0.75f, -3f, 6f, 1.5f, 6f), PartPose.offset(0f, -62f, -8f))
            root.addOrReplaceChild("hoop2b", CubeListBuilder.create().texOffs(82, 311).addBox(-3f, -0.75f, -3f, 6f, 1.5f, 6f), PartPose.offsetAndRotation(0f, -61.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hoop3", CubeListBuilder.create().texOffs(380, 159).addBox(-2.75f, -0.75f, -2.75f, 5.5f, 1.5f, 5.5f), PartPose.offset(0f, -92f, -8f))
            root.addOrReplaceChild("hoop3b", CubeListBuilder.create().texOffs(445, 174).addBox(-2.75f, -0.75f, -2.75f, 5.5f, 1.5f, 5.5f), PartPose.offsetAndRotation(0f, -91.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hoop4", CubeListBuilder.create().texOffs(78, 241).addBox(-2.5f, -0.75f, -2.5f, 5f, 1.5f, 5f), PartPose.offset(0f, -122f, -8f))
            root.addOrReplaceChild("hoop4b", CubeListBuilder.create().texOffs(150, 244).addBox(-2.5f, -0.75f, -2.5f, 5f, 1.5f, 5f), PartPose.offsetAndRotation(0f, -121.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hoop5", CubeListBuilder.create().texOffs(385, 13).addBox(-2.25f, -0.75f, -2.25f, 4.5f, 1.5f, 4.5f), PartPose.offset(0f, -153f, -8f))
            root.addOrReplaceChild("hoop5b", CubeListBuilder.create().texOffs(454, 101).addBox(-2.25f, -0.75f, -2.25f, 4.5f, 1.5f, 4.5f), PartPose.offsetAndRotation(0f, -152.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hoop6", CubeListBuilder.create().texOffs(298, 131).addBox(-2f, -0.75f, -2f, 4f, 1.5f, 4f), PartPose.offset(0f, -176f, -8f))
            root.addOrReplaceChild("hoop6b", CubeListBuilder.create().texOffs(403, 188).addBox(-2f, -0.75f, -2f, 4f, 1.5f, 4f), PartPose.offsetAndRotation(0f, -175.9f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("top_floor", CubeListBuilder.create().texOffs(385, 0).addBox(-9.5f, -0.75f, -9.5f, 19f, 1.5f, 19f), PartPose.offset(0f, -160f, -8f))
            root.addOrReplaceChild("top_tree_a", CubeListBuilder.create().texOffs(432, 90).addBox(-1.2f, -159.25f, -16.5f, 2.5f, 2.5f, 17f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_tree_b", CubeListBuilder.create().texOffs(0, 167).addBox(-8.5f, -159.25f, -9.2f, 17f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post0", CubeListBuilder.create().texOffs(461, 280).addBox(2.691f, -166.5f, -0.655f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post1", CubeListBuilder.create().texOffs(464, 287).addBox(7.345f, -166.5f, -5.309f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post2", CubeListBuilder.create().texOffs(282, 292).addBox(7.345f, -166.5f, -11.891f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post3", CubeListBuilder.create().texOffs(286, 292).addBox(2.691f, -166.5f, -16.545f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post4", CubeListBuilder.create().texOffs(298, 292).addBox(-3.891f, -166.5f, -16.545f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post5", CubeListBuilder.create().texOffs(302, 292).addBox(-8.545f, -166.5f, -11.891f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post6", CubeListBuilder.create().texOffs(341, 292).addBox(-8.545f, -166.5f, -5.309f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_post7", CubeListBuilder.create().texOffs(345, 292).addBox(-3.891f, -166.5f, -0.655f, 1f, 6.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("top_rail0", CubeListBuilder.create().texOffs(107, 126).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(5.618f, -166.5f, -2.382f, -1.571f, 0.785f, 0f))
            root.addOrReplaceChild("top_mid0", CubeListBuilder.create().texOffs(16, 113).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(5.618f, -163.3f, -2.382f, -1.571f, 0.785f, 0f))
            root.addOrReplaceChild("top_rail1", CubeListBuilder.create().texOffs(290, 159).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(7.945f, -166.5f, -8f, -1.571f, 1.571f, 0f))
            root.addOrReplaceChild("top_mid1", CubeListBuilder.create().texOffs(50, 124).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(7.945f, -163.3f, -8f, -1.571f, 1.571f, 0f))
            root.addOrReplaceChild("top_rail2", CubeListBuilder.create().texOffs(394, 206).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(5.618f, -166.5f, -13.618f, 1.571f, 0.785f, 3.142f))
            root.addOrReplaceChild("top_mid2", CubeListBuilder.create().texOffs(271, 127).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(5.618f, -163.3f, -13.618f, 1.571f, 0.785f, 3.142f))
            root.addOrReplaceChild("top_rail3", CubeListBuilder.create().texOffs(413, 213).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(0f, -166.5f, -15.945f, 1.571f, 0f, 3.142f))
            root.addOrReplaceChild("top_mid3", CubeListBuilder.create().texOffs(287, 127).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(0f, -163.3f, -15.945f, 1.571f, 0f, 3.142f))
            root.addOrReplaceChild("top_rail4", CubeListBuilder.create().texOffs(434, 216).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(-5.618f, -166.5f, -13.618f, 1.571f, -0.785f, 3.142f))
            root.addOrReplaceChild("top_mid4", CubeListBuilder.create().texOffs(172, 182).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(-5.618f, -163.3f, -13.618f, 1.571f, -0.785f, 3.142f))
            root.addOrReplaceChild("top_rail5", CubeListBuilder.create().texOffs(144, 219).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(-7.945f, -166.5f, -8f, -1.571f, -1.571f, 0f))
            root.addOrReplaceChild("top_mid5", CubeListBuilder.create().texOffs(78, 189).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(-7.945f, -163.3f, -8f, -1.571f, -1.571f, 0f))
            root.addOrReplaceChild("top_rail6", CubeListBuilder.create().texOffs(391, 223).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(-5.618f, -166.5f, -2.382f, -1.571f, -0.785f, 0f))
            root.addOrReplaceChild("top_mid6", CubeListBuilder.create().texOffs(403, 194).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(-5.618f, -163.3f, -2.382f, -1.571f, -0.785f, 0f))
            root.addOrReplaceChild("top_rail7", CubeListBuilder.create().texOffs(408, 237).addBox(-3.5f, -0.75f, 0f, 7f, 1.5f, 1f), PartPose.offsetAndRotation(0f, -166.5f, -0.055f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("top_mid7", CubeListBuilder.create().texOffs(102, 198).addBox(-3.5f, -0.5f, 0f, 7f, 1f, 1f), PartPose.offsetAndRotation(0f, -163.3f, -0.055f, -1.571f, 0f, 0f))
            val masthead = root.addOrReplaceChild("masthead", CubeListBuilder.create().texOffs(42, 200).addBox(-2f, -1.5f, -2f, 4f, 1.5f, 4f).texOffs(250, 11).addBox(-0.5f, -6f, -0.5f, 1f, 4.5f, 1f).texOffs(469, 133).addBox(-1f, -7f, -1f, 2f, 1f, 2f), PartPose.offset(0f, -186f, -8f))
            val pennant1 = masthead.addOrReplaceChild("pennant1", CubeListBuilder.create().texOffs(284, 94).addBox(-0.25f, -4f, 0f, 0.5f, 8f, 13f), PartPose.offset(0f, -3f, 1f))
            val pennant2 = pennant1.addOrReplaceChild("pennant2", CubeListBuilder.create().texOffs(366, 159).addBox(-0.25f, -3.25f, 0f, 0.5f, 6.5f, 13f), PartPose.offset(0f, 0f, 13f))
            pennant2.addOrReplaceChild("pennant3", CubeListBuilder.create().texOffs(442, 0).addBox(-0.25f, -2.5f, 0f, 0.5f, 5f, 14f), PartPose.offset(0f, 0f, 13f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildYard(root: PartDefinition) {
            val yard = root.addOrReplaceChild("yard", CubeListBuilder.create().texOffs(235, 125).addBox(-54f, -1f, -1f, 10f, 2f, 2f).texOffs(154, 315).addBox(-44f, -1.5f, -1.5f, 12f, 3f, 3f).texOffs(206, 192).addBox(-32f, -1.75f, -1.75f, 14f, 3.5f, 3.5f).texOffs(185, 78).addBox(-18f, -2.25f, -2.25f, 36f, 4.5f, 4.5f).texOffs(183, 263).addBox(18f, -1.75f, -1.75f, 14f, 3.5f, 3.5f).texOffs(45, 316).addBox(32f, -1.5f, -1.5f, 12f, 3f, 3f).texOffs(127, 225).addBox(44f, -1f, -1f, 10f, 2f, 2f).texOffs(273, 107).addBox(-55.5f, -2f, -2f, 1.5f, 4f, 4f).texOffs(116, 96).addBox(54f, -2f, -2f, 1.5f, 4f, 4f).texOffs(32, 349).addBox(-50.5f, -2.5f, -2.5f, 1.5f, 5f, 5f).texOffs(19, 349).addBox(49f, -2.5f, -2.5f, 1.5f, 5f, 5f).texOffs(163, 347).addBox(-9f, -2.5f, -2.5f, 1.5f, 5f, 5f).texOffs(422, 107).addBox(7.5f, -2.5f, -2.5f, 1.5f, 5f, 5f).texOffs(74, 200).addBox(-50f, -1f, -2.6f, 100f, 1f, 1f).texOffs(75, 95).addBox(-3f, -3f, -1.4f, 6f, 6f, 3f), PartPose.offset(0f, -152f, -11f))
            yard.addOrReplaceChild("yard0b", CubeListBuilder.create().texOffs(144, 215).addBox(-54f, -1f, -1f, 10f, 2f, 2f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.785f, 0f, 0f))
            yard.addOrReplaceChild("yard1b", CubeListBuilder.create().texOffs(184, 315).addBox(-44f, -1.5f, -1.5f, 12f, 3f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.785f, 0f, 0f))
            yard.addOrReplaceChild("yard2b", CubeListBuilder.create().texOffs(148, 263).addBox(-32f, -1.75f, -1.75f, 14f, 3.5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.785f, 0f, 0f))
            yard.addOrReplaceChild("yard3b", CubeListBuilder.create().texOffs(0, 115).addBox(-18f, -2.25f, -2.25f, 36f, 4.5f, 4.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.785f, 0f, 0f))
            yard.addOrReplaceChild("yard4b", CubeListBuilder.create().texOffs(218, 263).addBox(18f, -1.75f, -1.75f, 14f, 3.5f, 3.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.785f, 0f, 0f))
            yard.addOrReplaceChild("yard5b", CubeListBuilder.create().texOffs(214, 317).addBox(32f, -1.5f, -1.5f, 12f, 3f, 3f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.785f, 0f, 0f))
            yard.addOrReplaceChild("yard6b", CubeListBuilder.create().texOffs(357, 355).addBox(44f, -1f, -1f, 10f, 2f, 2f), PartPose.offsetAndRotation(0f, 0f, 0f, 0.785f, 0f, 0f))
            val sail1 = yard.addOrReplaceChild("sail1", CubeListBuilder.create().texOffs(0, 20).addBox(-20f, 0f, -0.25f, 40f, 18f, 0.5f), PartPose.offset(0f, 2f, -2f))
            sail1.addOrReplaceChild("sail1l", CubeListBuilder.create().texOffs(385, 39).addBox(0f, 0f, -0.25f, 30f, 18f, 0.5f), PartPose.offset(20f, 0f, 0f))
            sail1.addOrReplaceChild("sail1r", CubeListBuilder.create().texOffs(185, 57).addBox(-30f, 0f, -0.25f, 30f, 18f, 0.5f), PartPose.offset(-20f, 0f, 0f))
            val sail2 = sail1.addOrReplaceChild("sail2", CubeListBuilder.create().texOffs(185, 20).addBox(-20f, 0f, -0.25f, 40f, 18f, 0.5f), PartPose.offset(0f, 18f, 0f))
            sail2.addOrReplaceChild("sail2l", CubeListBuilder.create().texOffs(385, 58).addBox(0f, 0f, -0.25f, 29.5f, 18f, 0.5f), PartPose.offset(20f, 0f, 0f))
            sail2.addOrReplaceChild("sail2r", CubeListBuilder.create().texOffs(0, 59).addBox(-29.5f, 0f, -0.25f, 29.5f, 18f, 0.5f), PartPose.offset(-20f, 0f, 0f))
            val sail3 = sail2.addOrReplaceChild("sail3", CubeListBuilder.create().texOffs(385, 21).addBox(-20f, 0f, -0.25f, 40f, 17.5f, 0.5f), PartPose.offset(0f, 18f, 0f))
            sail3.addOrReplaceChild("sail3l", CubeListBuilder.create().texOffs(60, 59).addBox(0f, 0f, -0.25f, 29f, 17.5f, 0.5f), PartPose.offset(20f, 0f, 0f))
            sail3.addOrReplaceChild("sail3r", CubeListBuilder.create().texOffs(60, 77).addBox(-29f, 0f, -0.25f, 29f, 17.5f, 0.5f), PartPose.offset(-20f, 0f, 0f))
            val sail4 = sail3.addOrReplaceChild("sail4", CubeListBuilder.create().texOffs(0, 39).addBox(-20f, 0f, -0.25f, 40f, 17.5f, 0.5f), PartPose.offset(0f, 17.5f, 0f))
            sail4.addOrReplaceChild("sail4l", CubeListBuilder.create().texOffs(0, 78).addBox(0f, 0f, -0.25f, 28f, 17.5f, 0.5f), PartPose.offset(20f, 0f, 0f))
            sail4.addOrReplaceChild("sail4r", CubeListBuilder.create().texOffs(185, 89).addBox(-28f, 0f, -0.25f, 28f, 17.5f, 0.5f), PartPose.offset(-20f, 0f, 0f))
            val sail5 = sail4.addOrReplaceChild("sail5", CubeListBuilder.create().texOffs(185, 39).addBox(-20f, 0f, -0.25f, 40f, 17f, 0.5f), PartPose.offset(0f, 17.5f, 0f))
            sail5.addOrReplaceChild("sail5l", CubeListBuilder.create().texOffs(242, 89).addBox(0f, 0f, -0.25f, 27f, 17f, 0.5f), PartPose.offset(20f, 0f, 0f))
            sail5.addOrReplaceChild("sail5r", CubeListBuilder.create().texOffs(394, 89).addBox(-27f, 0f, -0.25f, 27f, 17f, 0.5f), PartPose.offset(-20f, 0f, 0f))
            yard.addOrReplaceChild("furl", CubeListBuilder.create().texOffs(290, 138).addBox(-50f, -2f, -2.25f, 100f, 4f, 4.5f).texOffs(290, 147).addBox(-34f, -3f, -3f, 68f, 6f, 6f), PartPose.offset(0f, 1.5f, -3.5f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildRigging(root: PartDefinition) {
            root.addOrReplaceChild("forestay", CubeListBuilder.create().texOffs(499, 0).addBox(-0.6f, 0f, -0.6f, 1f, 173f, 1f), PartPose.offsetAndRotation(0f, -184f, -10.2f, -0.556f, 0f, 0f))
            root.addOrReplaceChild("backstay_s", CubeListBuilder.create().texOffs(468, 158).addBox(-0.6f, 0f, -0.6f, 1f, 158.5f, 1f), PartPose.offsetAndRotation(-0.8f, -182f, -6.8f, 0.506f, 0f, 0.132f))
            root.addOrReplaceChild("backstay_p", CubeListBuilder.create().texOffs(507, 0).addBox(-0.6f, 0f, -0.6f, 1f, 158.5f, 1f), PartPose.offsetAndRotation(0.8f, -182f, -6.8f, 0.506f, 0f, -0.132f))
            root.addOrReplaceChild("bobstay", CubeListBuilder.create().texOffs(508, 160).addBox(-0.5f, 0f, -0.5f, 1f, 50.5f, 1f), PartPose.offsetAndRotation(0f, -34.6f, -101f, 0.791f, 0f, 0f))
            root.addOrReplaceChild("sprit_shroud_s", CubeListBuilder.create().texOffs(119, 57).addBox(-0.45f, 0f, -0.45f, 1f, 38f, 1f), PartPose.offsetAndRotation(-0.8f, -35f, -101f, 1.3f, 0.305f, 1.571f))
            root.addOrReplaceChild("sprit_shroud_p", CubeListBuilder.create().texOffs(468, 19).addBox(-0.45f, 0f, -0.45f, 1f, 38f, 1f), PartPose.offsetAndRotation(0.8f, -35f, -101f, 1.842f, 0.305f, 1.571f))
            root.addOrReplaceChild("halyard_block", CubeListBuilder.create().texOffs(199, 178).addBox(-1.6f, -184.5f, -13f, 3f, 4f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("halyard_tye", CubeListBuilder.create().texOffs(463, 240).addBox(-0.5f, 0f, -0.5f, 1f, 27f, 1f), PartPose.offsetAndRotation(0f, -180.5f, -11.8f, 0.019f, 0f, 0f))
            root.addOrReplaceChild("halyard_fall", CubeListBuilder.create().texOffs(503, 0).addBox(-0.45f, 0f, -0.45f, 1f, 166f, 1f), PartPose.offsetAndRotation(0f, -181f, -5.1f, 0.004f, 0f, 0f))
            root.addOrReplaceChild("shroud_s0", CubeListBuilder.create().texOffs(321, 159).addBox(-0.55f, 0f, -0.55f, 1f, 139.5f, 1f), PartPose.offsetAndRotation(-28.191f, -21f, -14f, 0.019f, 0f, -2.998f))
            root.addOrReplaceChild("chain_s0", CubeListBuilder.create().texOffs(176, 350).addBox(-28.191f, -22f, -15.6f, 1f, 9f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deadeye_s0", CubeListBuilder.create().texOffs(458, 158).addBox(-29.491f, -22f, -15.3f, 2.5f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("shroud_s1", CubeListBuilder.create().texOffs(325, 159).addBox(-0.55f, 0f, -0.55f, 1f, 139.5f, 1f), PartPose.offsetAndRotation(-28.766f, -21f, -6f, -0.006f, 0f, -2.994f))
            root.addOrReplaceChild("chain_s1", CubeListBuilder.create().texOffs(184, 350).addBox(-28.766f, -22f, -7.6f, 1f, 9f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deadeye_s1", CubeListBuilder.create().texOffs(458, 163).addBox(-30.066f, -22f, -7.3f, 2.5f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("shroud_s2", CubeListBuilder.create().texOffs(329, 159).addBox(-0.55f, 0f, -0.55f, 1f, 139.5f, 1f), PartPose.offsetAndRotation(-28.9f, -21f, 2f, -0.032f, 0f, -2.993f))
            root.addOrReplaceChild("chain_s2", CubeListBuilder.create().texOffs(430, 351).addBox(-28.9f, -22f, 0.4f, 1f, 9f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deadeye_s2", CubeListBuilder.create().texOffs(185, 174).addBox(-30.2f, -22f, 0.7f, 2.5f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rat_s0_0", CubeListBuilder.create().texOffs(476, 179).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(-26.177f, -34f, -13.728f, 1.503f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s0_1", CubeListBuilder.create().texOffs(291, 186).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(-26.694f, -34f, -6.091f, 1.555f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s1_0", CubeListBuilder.create().texOffs(295, 186).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(-24.308f, -47f, -13.476f, 1.507f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s1_1", CubeListBuilder.create().texOffs(353, 186).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(-24.771f, -47f, -6.175f, 1.556f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s2_0", CubeListBuilder.create().texOffs(74, 202).addBox(-0.4f, 0f, -0.4f, 1f, 7f, 1f), PartPose.offsetAndRotation(-22.438f, -60f, -13.223f, 1.512f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s2_1", CubeListBuilder.create().texOffs(78, 202).addBox(-0.4f, 0f, -0.4f, 1f, 7f, 1f), PartPose.offsetAndRotation(-22.847f, -60f, -6.259f, 1.557f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s3_0", CubeListBuilder.create().texOffs(455, 216).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(-20.568f, -73f, -12.971f, 1.517f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s3_1", CubeListBuilder.create().texOffs(464, 223).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(-20.924f, -73f, -6.343f, 1.558f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s4_0", CubeListBuilder.create().texOffs(83, 229).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(-18.699f, -86f, -12.718f, 1.523f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s4_1", CubeListBuilder.create().texOffs(78, 234).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(-19.001f, -86f, -6.427f, 1.56f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s5_0", CubeListBuilder.create().texOffs(164, 174).addBox(-0.4f, 0f, -0.4f, 1f, 6f, 1f), PartPose.offsetAndRotation(-16.829f, -99f, -12.465f, 1.529f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s5_1", CubeListBuilder.create().texOffs(472, 214).addBox(-0.4f, 0f, -0.4f, 1f, 6f, 1f), PartPose.offsetAndRotation(-17.077f, -99f, -6.512f, 1.561f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s6_0", CubeListBuilder.create().texOffs(455, 310).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(-14.959f, -112f, -12.213f, 1.536f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s6_1", CubeListBuilder.create().texOffs(409, 313).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(-15.154f, -112f, -6.596f, 1.563f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s7_0", CubeListBuilder.create().texOffs(508, 316).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(-13.09f, -125f, -11.96f, 1.544f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s7_1", CubeListBuilder.create().texOffs(439, 317).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(-13.23f, -125f, -6.68f, 1.565f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s8_0", CubeListBuilder.create().texOffs(290, 147).addBox(-0.4f, 0f, -0.4f, 1f, 5f, 1f), PartPose.offsetAndRotation(-11.22f, -138f, -11.708f, 1.553f, 0f, 1.571f))
            root.addOrReplaceChild("rat_s8_1", CubeListBuilder.create().texOffs(241, 192).addBox(-0.4f, 0f, -0.4f, 1f, 5f, 1f), PartPose.offsetAndRotation(-11.307f, -138f, -6.764f, 1.567f, 0f, 1.571f))
            root.addOrReplaceChild("shroud_p0", CubeListBuilder.create().texOffs(495, 0).addBox(-0.55f, 0f, -0.55f, 1f, 139.5f, 1f), PartPose.offsetAndRotation(28.191f, -21f, -14f, 0.019f, 0f, 2.998f))
            root.addOrReplaceChild("chain_p0", CubeListBuilder.create().texOffs(288, 212).addBox(27.191f, -22f, -15.6f, 1f, 9f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deadeye_p0", CubeListBuilder.create().texOffs(489, 147).addBox(26.891f, -22f, -15.3f, 2.5f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("shroud_p1", CubeListBuilder.create().texOffs(313, 159).addBox(-0.55f, 0f, -0.55f, 1f, 139.5f, 1f), PartPose.offsetAndRotation(28.766f, -21f, -6f, -0.006f, 0f, 2.994f))
            root.addOrReplaceChild("chain_p1", CubeListBuilder.create().texOffs(460, 268).addBox(27.766f, -22f, -7.6f, 1f, 9f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deadeye_p1", CubeListBuilder.create().texOffs(0, 151).addBox(27.466f, -22f, -7.3f, 2.5f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("shroud_p2", CubeListBuilder.create().texOffs(317, 159).addBox(-0.55f, 0f, -0.55f, 1f, 139.5f, 1f), PartPose.offsetAndRotation(28.9f, -21f, 2f, -0.032f, 0f, 2.993f))
            root.addOrReplaceChild("chain_p2", CubeListBuilder.create().texOffs(504, 339).addBox(27.9f, -22f, 0.4f, 1f, 9f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("deadeye_p2", CubeListBuilder.create().texOffs(0, 156).addBox(27.6f, -22f, 0.7f, 2.5f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("rat_p0_0", CubeListBuilder.create().texOffs(189, 107).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(26.177f, -34f, -13.728f, 1.638f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p0_1", CubeListBuilder.create().texOffs(435, 110).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(26.694f, -34f, -6.091f, 1.587f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p1_0", CubeListBuilder.create().texOffs(38, 174).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(24.308f, -47f, -13.476f, 1.634f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p1_1", CubeListBuilder.create().texOffs(472, 179).addBox(-0.4f, 0f, -0.4f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(24.771f, -47f, -6.175f, 1.586f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p2_0", CubeListBuilder.create().texOffs(443, 193).addBox(-0.4f, 0f, -0.4f, 1f, 7f, 1f), PartPose.offsetAndRotation(22.438f, -60f, -13.223f, 1.63f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p2_1", CubeListBuilder.create().texOffs(292, 200).addBox(-0.4f, 0f, -0.4f, 1f, 7f, 1f), PartPose.offsetAndRotation(22.847f, -60f, -6.259f, 1.585f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p3_0", CubeListBuilder.create().texOffs(452, 205).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(20.568f, -73f, -12.971f, 1.624f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p3_1", CubeListBuilder.create().texOffs(0, 210).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(20.924f, -73f, -6.343f, 1.583f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p4_0", CubeListBuilder.create().texOffs(23, 212).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(18.699f, -86f, -12.718f, 1.619f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p4_1", CubeListBuilder.create().texOffs(62, 212).addBox(-0.4f, 0f, -0.4f, 1f, 6.5f, 1f), PartPose.offsetAndRotation(19.001f, -86f, -6.427f, 1.582f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p5_0", CubeListBuilder.create().texOffs(372, 159).addBox(-0.4f, 0f, -0.4f, 1f, 6f, 1f), PartPose.offsetAndRotation(16.829f, -99f, -12.465f, 1.612f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p5_1", CubeListBuilder.create().texOffs(503, 167).addBox(-0.4f, 0f, -0.4f, 1f, 6f, 1f), PartPose.offsetAndRotation(17.077f, -99f, -6.512f, 1.581f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p6_0", CubeListBuilder.create().texOffs(26, 302).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(14.959f, -112f, -12.213f, 1.605f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p6_1", CubeListBuilder.create().texOffs(340, 302).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(15.154f, -112f, -6.596f, 1.579f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p7_0", CubeListBuilder.create().texOffs(508, 302).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(13.09f, -125f, -11.96f, 1.597f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p7_1", CubeListBuilder.create().texOffs(508, 309).addBox(-0.4f, 0f, -0.4f, 1f, 5.5f, 1f), PartPose.offsetAndRotation(13.23f, -125f, -6.68f, 1.577f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p8_0", CubeListBuilder.create().texOffs(0, 131).addBox(-0.4f, 0f, -0.4f, 1f, 5f, 1f), PartPose.offsetAndRotation(11.22f, -138f, -11.708f, 1.588f, 0f, 1.571f))
            root.addOrReplaceChild("rat_p8_1", CubeListBuilder.create().texOffs(27, 131).addBox(-0.4f, 0f, -0.4f, 1f, 5f, 1f), PartPose.offsetAndRotation(11.307f, -138f, -6.764f, 1.575f, 0f, 1.571f))
            root.addOrReplaceChild("sprit0", CubeListBuilder.create().texOffs(450, 229).addBox(-2.25f, 0f, -2.25f, 4.5f, 6.5f, 4.5f), PartPose.offsetAndRotation(0f, -24.8f, -61f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit1", CubeListBuilder.create().texOffs(441, 339).addBox(-2.108f, 0f, -2.108f, 4f, 6.5f, 4f), PartPose.offsetAndRotation(0f, -26.4f, -66.857f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit2", CubeListBuilder.create().texOffs(457, 339).addBox(-1.967f, 0f, -1.967f, 4f, 6.5f, 4f), PartPose.offsetAndRotation(0f, -28f, -72.714f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit3", CubeListBuilder.create().texOffs(442, 0).addBox(-1.825f, 0f, -1.825f, 3.5f, 6.5f, 3.5f), PartPose.offsetAndRotation(0f, -29.6f, -78.571f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit4", CubeListBuilder.create().texOffs(457, 0).addBox(-1.683f, 0f, -1.683f, 3.5f, 6.5f, 3.5f), PartPose.offsetAndRotation(0f, -31.2f, -84.429f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit5", CubeListBuilder.create().texOffs(0, 96).addBox(-1.542f, 0f, -1.542f, 3f, 6.5f, 3f), PartPose.offsetAndRotation(0f, -32.8f, -90.286f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit6", CubeListBuilder.create().texOffs(302, 115).addBox(-1.4f, 0f, -1.4f, 3f, 6.5f, 3f), PartPose.offsetAndRotation(0f, -34.4f, -96.143f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit_lash0", CubeListBuilder.create().texOffs(423, 309).addBox(-2.9f, 0f, -2.9f, 6f, 2f, 6f), PartPose.offsetAndRotation(0f, -28.832f, -75.76f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit_lash1", CubeListBuilder.create().texOffs(250, 11).addBox(-2.7f, 0f, -2.7f, 5.5f, 2f, 5.5f), PartPose.offsetAndRotation(0f, -29.504f, -78.22f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit_lash2", CubeListBuilder.create().texOffs(432, 147).addBox(-1.8f, 0f, -1.8f, 3.5f, 2f, 3.5f), PartPose.offsetAndRotation(0f, -34.88f, -97.9f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("sprit_cap", CubeListBuilder.create().texOffs(96, 174).addBox(-1.7f, 0f, -1.7f, 3.5f, 3f, 3.5f), PartPose.offsetAndRotation(0f, -35.736f, -101.035f, -1.571f, -0.267f, -1.571f))
            root.addOrReplaceChild("bow_arm_a", CubeListBuilder.create().texOffs(81, 115).addBox(-0.6f, 0f, -0.6f, 1f, 5f, 1f), PartPose.offsetAndRotation(0f, -27f, -73.3f, -1.571f, 0f, -1.571f))
            root.addOrReplaceChild("bow_arm_b", CubeListBuilder.create().texOffs(448, 61).addBox(-0.7f, 0f, -0.7f, 1.5f, 4.5f, 1.5f), PartPose.offsetAndRotation(0f, -26.8f, -70.5f, -1.571f, -0.044f, -1.571f))
            root.addOrReplaceChild("bitt_s", CubeListBuilder.create().texOffs(413, 305).addBox(-7.4f, -41f, -65.2f, 2.5f, 17f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_strap_s", CubeListBuilder.create().texOffs(500, 218).addBox(-7.7f, -31f, -65.5f, 3f, 1f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_collar_s", CubeListBuilder.create().texOffs(172, 184).addBox(-7.7f, -41.7f, -65.5f, 3f, 1f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_head_s", CubeListBuilder.create().texOffs(62, 174).addBox(-8f, -44.5f, -65.8f, 3.5f, 3f, 3.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_lash_s", CubeListBuilder.create().texOffs(231, 178).addBox(-8f, -33f, -65.8f, 3.5f, 5f, 3.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_p", CubeListBuilder.create().texOffs(498, 302).addBox(5f, -41f, -65.2f, 2.5f, 17f, 2.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_strap_p", CubeListBuilder.create().texOffs(50, 186).addBox(4.7f, -31f, -65.5f, 3f, 1f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_collar_p", CubeListBuilder.create().texOffs(259, 125).addBox(4.7f, -41.7f, -65.5f, 3f, 1f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_head_p", CubeListBuilder.create().texOffs(24, 174).addBox(4.4f, -44.5f, -65.8f, 3.5f, 3f, 3.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_lash_p", CubeListBuilder.create().texOffs(411, 174).addBox(4.4f, -33f, -65.8f, 3.5f, 5f, 3.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_beam", CubeListBuilder.create().texOffs(148, 328).addBox(-7.4f, -32f, -65f, 15f, 3f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bitt_sprit_lash", CubeListBuilder.create().texOffs(473, 339).addBox(-2.7f, -29.8f, -65.6f, 5.5f, 6.5f, 3f), PartPose.offset(0f, 0f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildFittings(root: PartDefinition) {
            val wheel = root.addOrReplaceChild("wheel", CubeListBuilder.create().texOffs(455, 79).addBox(-2.8f, -2.8f, -1.6f, 5.5f, 5.5f, 3f).texOffs(380, 166).addBox(-2.2f, -2.2f, -2.6f, 4.5f, 4.5f, 1f).texOffs(446, 39).addBox(-1.2f, -1.2f, 1.6f, 2.5f, 2.5f, 1f), PartPose.offset(0f, -45f, 47f))
            wheel.addOrReplaceChild("wheel_r0", CubeListBuilder.create().texOffs(211, 213).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offset(0f, 0f, 0f))
            wheel.addOrReplaceChild("wheel_r1", CubeListBuilder.create().texOffs(385, 213).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.393f))
            wheel.addOrReplaceChild("wheel_r2", CubeListBuilder.create().texOffs(227, 323).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            wheel.addOrReplaceChild("wheel_r3", CubeListBuilder.create().texOffs(119, 358).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.178f))
            wheel.addOrReplaceChild("wheel_r4", CubeListBuilder.create().texOffs(265, 358).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.571f))
            wheel.addOrReplaceChild("wheel_r5", CubeListBuilder.create().texOffs(278, 358).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.963f))
            wheel.addOrReplaceChild("wheel_r6", CubeListBuilder.create().texOffs(291, 358).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 2.356f))
            wheel.addOrReplaceChild("wheel_r7", CubeListBuilder.create().texOffs(16, 359).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 2.749f))
            wheel.addOrReplaceChild("wheel_r8", CubeListBuilder.create().texOffs(29, 359).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 3.142f))
            wheel.addOrReplaceChild("wheel_r9", CubeListBuilder.create().texOffs(347, 359).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 3.534f))
            wheel.addOrReplaceChild("wheel_r10", CubeListBuilder.create().texOffs(260, 222).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 3.927f))
            wheel.addOrReplaceChild("wheel_r11", CubeListBuilder.create().texOffs(165, 223).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 4.32f))
            wheel.addOrReplaceChild("wheel_r12", CubeListBuilder.create().texOffs(193, 223).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 4.712f))
            wheel.addOrReplaceChild("wheel_r13", CubeListBuilder.create().texOffs(363, 223).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 5.105f))
            wheel.addOrReplaceChild("wheel_r14", CubeListBuilder.create().texOffs(347, 314).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 5.498f))
            wheel.addOrReplaceChild("wheel_r15", CubeListBuilder.create().texOffs(244, 317).addBox(-2.1f, -10f, -1.2f, 4f, 2f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 5.89f))
            wheel.addOrReplaceChild("wheel_s0", CubeListBuilder.create().texOffs(33, 131).addBox(-0.8f, -13f, -1f, 1.5f, 26f, 2f).texOffs(195, 187).addBox(-1.3f, -15.2f, -1.3f, 2.5f, 2.5f, 2.5f).texOffs(195, 192).addBox(-1.3f, 12.8f, -1.3f, 2.5f, 2.5f, 2.5f), PartPose.offset(0f, 0f, 0f))
            wheel.addOrReplaceChild("wheel_s1", CubeListBuilder.create().texOffs(304, 229).addBox(-0.8f, -13f, -1f, 1.5f, 26f, 2f).texOffs(277, 212).addBox(-1.3f, -15.2f, -1.3f, 2.5f, 2.5f, 2.5f).texOffs(76, 224).addBox(-1.3f, 12.8f, -1.3f, 2.5f, 2.5f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 0.785f))
            wheel.addOrReplaceChild("wheel_s2", CubeListBuilder.create().texOffs(304, 257).addBox(-0.8f, -13f, -1f, 1.5f, 26f, 2f).texOffs(134, 298).addBox(-1.3f, -15.2f, -1.3f, 2.5f, 2.5f, 2.5f).texOffs(252, 309).addBox(-1.3f, 12.8f, -1.3f, 2.5f, 2.5f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 1.571f))
            wheel.addOrReplaceChild("wheel_s3", CubeListBuilder.create().texOffs(461, 296).addBox(-0.8f, -13f, -1f, 1.5f, 26f, 2f).texOffs(317, 310).addBox(-1.3f, -15.2f, -1.3f, 2.5f, 2.5f, 2.5f).texOffs(76, 312).addBox(-1.3f, 12.8f, -1.3f, 2.5f, 2.5f, 2.5f), PartPose.offsetAndRotation(0f, 0f, 0f, 0f, 0f, 2.356f))
            root.addOrReplaceChild("wheel_post_f", CubeListBuilder.create().texOffs(498, 222).addBox(-2.2f, -48f, 42.4f, 4.5f, 18f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("wheel_post_b", CubeListBuilder.create().texOffs(116, 0).addBox(-2.2f, -48f, 49.4f, 4.5f, 18f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("wheel_axle", CubeListBuilder.create().texOffs(250, 0).addBox(-1f, -46f, 42.4f, 2f, 2f, 9f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("wheel_post_cap_f", CubeListBuilder.create().texOffs(127, 229).addBox(-2.8f, -49.2f, 42f, 5.5f, 1f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("wheel_post_cap_b", CubeListBuilder.create().texOffs(493, 214).addBox(-2.8f, -49.2f, 49f, 5.5f, 1f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("wheel_foot", CubeListBuilder.create().texOffs(444, 126).addBox(-3.6f, -31.4f, 41.4f, 7f, 1.5f, 10.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("binnacle", CubeListBuilder.create().texOffs(448, 61).addBox(10.5f, -40f, 47f, 6f, 10f, 6f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("binnacle_lamp", CubeListBuilder.create().texOffs(234, 107).addBox(11.25f, -43.5f, 47.75f, 4.5f, 3.5f, 4.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("binnacle_cap", CubeListBuilder.create().texOffs(393, 322).addBox(10.5f, -44.5f, 47f, 6f, 1f, 6f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("binnacle_foot", CubeListBuilder.create().texOffs(164, 174).addBox(10f, -31f, 46.5f, 7f, 1f, 7f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bell_post_a", CubeListBuilder.create().texOffs(459, 205).addBox(6.1f, -46f, 37.1f, 2f, 16f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bell_post_b", CubeListBuilder.create().texOffs(496, 339).addBox(12.1f, -46f, 37.1f, 2f, 16f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("bell_beam", CubeListBuilder.create().texOffs(76, 278).addBox(5.5f, -46.4f, 36.9f, 9f, 2f, 2f), PartPose.offset(0f, 0f, 0f))
            val bell = root.addOrReplaceChild("bell", CubeListBuilder.create().texOffs(90, 95).addBox(-0.8f, 0f, -0.8f, 1.5f, 1.5f, 1.5f).texOffs(138, 188).addBox(-1.8f, 1.5f, -1.8f, 3.5f, 1.5f, 3.5f).texOffs(418, 159).addBox(-2.5f, 3f, -2.5f, 5f, 2.5f, 5f).texOffs(368, 296).addBox(-3.3f, 5.5f, -3.3f, 6.5f, 2f, 6.5f), PartPose.offset(10f, -44f, 38f))
            bell.addOrReplaceChild("bell_clapper", CubeListBuilder.create().texOffs(333, 159).addBox(-0.5f, 0f, -0.5f, 1f, 6f, 1f).texOffs(120, 114).addBox(-1f, 5.6f, -1f, 2f, 2f, 2f), PartPose.offset(0f, 2f, 0f))
            root.addOrReplaceChild("lantern_stern", CubeListBuilder.create().texOffs(489, 152).addBox(-0.75f, -2f, -0.75f, 1.5f, 2f, 1.5f).texOffs(183, 213).addBox(-1.75f, 0f, -1.75f, 3.5f, 1f, 3.5f).texOffs(294, 339).addBox(-3f, 1f, -3f, 6f, 1f, 6f).texOffs(443, 298).addBox(-2.25f, 2f, -2.25f, 4.5f, 7f, 4.5f).texOffs(270, 339).addBox(-3f, 9f, -3f, 6f, 1f, 6f).texOffs(235, 121).addBox(-1f, 10f, -1f, 2f, 1f, 2f).texOffs(0, 174).addBox(-2.85f, 2f, -2.85f, 1f, 7f, 1f).texOffs(4, 174).addBox(1.85f, 2f, -2.85f, 1f, 7f, 1f).texOffs(76, 174).addBox(-2.85f, 2f, 1.85f, 1f, 7f, 1f).texOffs(74, 189).addBox(1.85f, 2f, 1.85f, 1f, 7f, 1f), PartPose.offset(0f, -52f, 77f))
            root.addOrReplaceChild("lantern_bow", CubeListBuilder.create().texOffs(169, 138).addBox(-0.75f, -2f, -0.75f, 1.5f, 2f, 1.5f).texOffs(225, 202).addBox(-1.75f, 0f, -1.75f, 3.5f, 1f, 3.5f).texOffs(417, 338).addBox(-3f, 1f, -3f, 6f, 1f, 6f).texOffs(454, 89).addBox(-2.25f, 2f, -2.25f, 4.5f, 7f, 4.5f).texOffs(252, 337).addBox(-3f, 9f, -3f, 6f, 1f, 6f).texOffs(120, 118).addBox(-1f, 10f, -1f, 2f, 1f, 2f).texOffs(333, 171).addBox(-2.85f, 2f, -2.85f, 1f, 7f, 1f).texOffs(337, 171).addBox(1.85f, 2f, -2.85f, 1f, 7f, 1f).texOffs(357, 171).addBox(-2.85f, 2f, 1.85f, 1f, 7f, 1f).texOffs(361, 171).addBox(1.85f, 2f, 1.85f, 1f, 7f, 1f), PartPose.offset(0f, -27f, -76f))
            root.addOrReplaceChild("stern_lamp_post", CubeListBuilder.create().texOffs(311, 357).addBox(-0.7f, 0f, -0.7f, 1.5f, 10.5f, 1.5f), PartPose.offsetAndRotation(0f, -44f, 74.4f, 0f, 0f, 3.142f))
            root.addOrReplaceChild("stern_lamp_arm", CubeListBuilder.create().texOffs(49, 96).addBox(-0.7f, 0f, -0.7f, 1.5f, 4f, 1.5f), PartPose.offsetAndRotation(0f, -54f, 73.7f, 1.571f, 0f, 1.571f))
            root.addOrReplaceChild("stern_lamp_brace", CubeListBuilder.create().texOffs(186, 187).addBox(-0.5f, 0f, -0.5f, 1f, 7.5f, 1f), PartPose.offsetAndRotation(0f, -46f, 74.9f, 0.301f, 0f, 3.142f))
            root.addOrReplaceChild("rudder", CubeListBuilder.create().texOffs(185, 0).addBox(-1.3f, 0f, -0.5f, 2.5f, 14f, 5f).texOffs(99, 9).addBox(-1.5f, 14f, -0.5f, 3f, 22f, 11f).texOffs(78, 340).addBox(-1.8f, -0.8f, -0.9f, 3.5f, 1.5f, 6f).texOffs(33, 96).addBox(-1.95f, 17f, -1.3f, 4f, 1.5f, 8f).texOffs(282, 292).addBox(-1.95f, 25.5f, -1.3f, 4f, 1.5f, 8f).texOffs(325, 292).addBox(-1.95f, 33f, -1.3f, 4f, 1.5f, 8f), PartPose.offset(0f, -26f, 74f))
            val anchor = root.addOrReplaceChild("anchor", CubeListBuilder.create().texOffs(85, 50).addBox(-1.75f, 0f, -0.5f, 3.5f, 0.5f, 1f).texOffs(90, 40).addBox(-1.75f, 2.8f, -0.5f, 3.5f, 0.5f, 1f).texOffs(0, 0).addBox(-1.75f, 0.7f, -0.5f, 0.5f, 2f, 1f).texOffs(107, 0).addBox(1.05f, 0.7f, -0.5f, 0.5f, 2f, 1f).texOffs(371, 193).addBox(-0.75f, 3.5f, -0.75f, 1.5f, 15f, 1.5f).texOffs(182, 187).addBox(-0.6f, 5.5f, -5.5f, 1f, 1f, 11f).texOffs(435, 188).addBox(-1.75f, 17f, -1.25f, 3.5f, 2.5f, 2.5f), PartPose.offset(-20f, -18f, -56f))
            anchor.addOrReplaceChild("anchor_arm_p", CubeListBuilder.create().texOffs(85, 40).addBox(-0.7f, -8f, -0.7f, 1.5f, 8f, 1.5f).texOffs(394, 107).addBox(-0.4f, -10.5f, -2f, 1f, 3.5f, 4f), PartPose.offsetAndRotation(1.2f, 18.6f, 0f, 0f, 0f, 0.908f))
            anchor.addOrReplaceChild("anchor_arm_s", CubeListBuilder.create().texOffs(114, 42).addBox(-0.7f, -8f, -0.7f, 1.5f, 8f, 1.5f).texOffs(461, 110).addBox(-0.4f, -10.5f, -2f, 1f, 3.5f, 4f), PartPose.offsetAndRotation(-1.2f, 18.6f, 0f, 0f, 0f, -0.908f))
            val anchor_rope = root.addOrReplaceChild("anchor_rope", CubeListBuilder.create().texOffs(200, 0).addBox(-0.6f, 0f, -0.6f, 1f, 8f, 1f), PartPose.offset(-20f, -18f, -56f))
            val anchor_rope2 = anchor_rope.addOrReplaceChild("anchor_rope2", CubeListBuilder.create().texOffs(200, 9).addBox(-0.6f, 0f, -0.6f, 1f, 8f, 1f), PartPose.offset(0f, 8f, 0f))
            val anchor_rope3 = anchor_rope2.addOrReplaceChild("anchor_rope3", CubeListBuilder.create().texOffs(81, 20).addBox(-0.6f, 0f, -0.6f, 1f, 8f, 1f), PartPose.offset(0f, 8f, 0f))
            val anchor_rope4 = anchor_rope3.addOrReplaceChild("anchor_rope4", CubeListBuilder.create().texOffs(468, 58).addBox(-0.6f, 0f, -0.6f, 1f, 8f, 1f), PartPose.offset(0f, 8f, 0f))
            val anchor_rope5 = anchor_rope4.addOrReplaceChild("anchor_rope5", CubeListBuilder.create().texOffs(310, 89).addBox(-0.6f, 0f, -0.6f, 1f, 8f, 1f), PartPose.offset(0f, 8f, 0f))
            val anchor_rope6 = anchor_rope5.addOrReplaceChild("anchor_rope6", CubeListBuilder.create().texOffs(310, 98).addBox(-0.6f, 0f, -0.6f, 1f, 8f, 1f), PartPose.offset(0f, 8f, 0f))
            anchor_rope6.addOrReplaceChild("anchor_rope7", CubeListBuilder.create().texOffs(185, 107).addBox(-0.6f, 0f, -0.6f, 1f, 8f, 1f), PartPose.offset(0f, 8f, 0f))
            root.addOrReplaceChild("cathead", CubeListBuilder.create().texOffs(274, 310).addBox(-20.5f, -26f, -57.5f, 12.5f, 3f, 3f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("cathead_sheave", CubeListBuilder.create().texOffs(481, 129).addBox(-22.5f, -26.5f, -58f, 2.5f, 4f, 4f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("cathead_hook", CubeListBuilder.create().texOffs(33, 96).addBox(-20.7f, -23f, -56.7f, 1.5f, 5f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("cathead_brace", CubeListBuilder.create().texOffs(499, 174).addBox(-1f, 0f, -1f, 2f, 10f, 2f), PartPose.offsetAndRotation(-11f, -17f, -56f, 0f, 0f, 2.202f))
            val capstan = root.addOrReplaceChild("capstan", CubeListBuilder.create().texOffs(333, 171).addBox(-4f, -7f, -4f, 8f, 7f, 8f).texOffs(272, 115).addBox(-5f, -9f, -5f, 10f, 2f, 10f).texOffs(0, 124).addBox(-12f, -8.6f, -0.9f, 24f, 2f, 2f).texOffs(57, 33).addBox(-0.9f, -8.6f, -12f, 2f, 2f, 24f).texOffs(385, 233).addBox(-1.5f, -10f, -1.5f, 3f, 1f, 3f).texOffs(106, 202).addBox(-4.5f, -4.5f, -4.5f, 9f, 1f, 9f), PartPose.offset(0f, -12f, -34f))
            capstan.addOrReplaceChild("capstan_b", CubeListBuilder.create().texOffs(0, 174).addBox(-4f, -7f, -4f, 8f, 7f, 8f).texOffs(32, 174).addBox(-5f, -9f, -5f, 10f, 2f, 10f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildCargo(root: PartDefinition) {
            root.addOrReplaceChild("chest_s", CubeListBuilder.create().texOffs(333, 159).addBox(-22f, -17f, 2f, 16f, 5f, 7f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("chest_s_lid", CubeListBuilder.create().texOffs(145, 188).addBox(-22.25f, -19f, 1.75f, 16.5f, 2f, 7.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("chest_p", CubeListBuilder.create().texOffs(81, 114).addBox(6f, -17f, 2f, 16f, 5f, 7f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("chest_p_lid", CubeListBuilder.create().texOffs(97, 188).addBox(5.75f, -19f, 1.75f, 16.5f, 2f, 7.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("locker", CubeListBuilder.create().texOffs(185, 107).addBox(-24f, -34.5f, 59.5f, 20f, 4.5f, 9f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("locker_lid", CubeListBuilder.create().texOffs(438, 147).addBox(-24.25f, -36f, 59.25f, 20.5f, 1.5f, 9.5f), PartPose.offset(0f, 0f, 0f))
            val barrel_a = root.addOrReplaceChild("barrel_a", CubeListBuilder.create().texOffs(190, 213).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(104, 174).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(256, 212).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(-21f, -12f, 15.5f, 0f, 0.349f, 0f))
            barrel_a.addOrReplaceChild("barrel_a_b", CubeListBuilder.create().texOffs(218, 213).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(138, 174).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(162, 213).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val barrel_b = root.addOrReplaceChild("barrel_b", CubeListBuilder.create().texOffs(62, 214).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(385, 174).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(364, 213).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offset(-21.2f, -12f, 25f))
            barrel_b.addOrReplaceChild("barrel_b_b", CubeListBuilder.create().texOffs(472, 214).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(419, 174).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(392, 213).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val cask = root.addOrReplaceChild("cask", CubeListBuilder.create().texOffs(404, 201).addBox(-4f, -3.5f, -4f, 8f, 3.5f, 8f).texOffs(0, 151).addBox(-5f, -9.5f, -5f, 10f, 6f, 10f).texOffs(18, 200).addBox(-4f, -13f, -4f, 8f, 3.5f, 8f), PartPose.offsetAndRotation(-12.5f, -12f, -17f, 0f, 0.14f, 0f))
            cask.addOrReplaceChild("cask_b", CubeListBuilder.create().texOffs(74, 202).addBox(-4f, -3.5f, -4f, 8f, 3.5f, 8f).texOffs(428, 158).addBox(-5f, -9.5f, -5f, 10f, 6f, 10f).texOffs(268, 200).addBox(-4f, -13f, -4f, 8f, 3.5f, 8f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("crate_a", CubeListBuilder.create().texOffs(86, 95).addBox(-5f, -8.5f, -5f, 10f, 8.5f, 10f), PartPose.offsetAndRotation(20.5f, -12f, -27f, 0f, 0.209f, 0f))
            root.addOrReplaceChild("crate_b", CubeListBuilder.create().texOffs(72, 174).addBox(-4f, -7f, -4f, 8f, 7f, 8f), PartPose.offsetAndRotation(20f, -20.5f, -27.5f, 0f, -0.157f, 0f))
            root.addOrReplaceChild("crate_c", CubeListBuilder.create().texOffs(393, 159).addBox(-4f, -6f, -4.5f, 8f, 6f, 9f), PartPose.offsetAndRotation(21f, -12f, -12.5f, 0f, -0.07f, 0f))
            root.addOrReplaceChild("sack_a", CubeListBuilder.create().texOffs(382, 188).addBox(-3f, -5f, -4.5f, 6f, 5f, 9f), PartPose.offsetAndRotation(14f, -12f, -33f, 0f, 0.436f, 0f))
            root.addOrReplaceChild("sack_b", CubeListBuilder.create().texOffs(142, 202).addBox(-2.75f, -4.5f, -4f, 5.5f, 4.5f, 8f), PartPose.offsetAndRotation(23f, -12f, -38f, 0f, -0.262f, 0f))
            val coil_mast = root.addOrReplaceChild("coil_mast", CubeListBuilder.create().texOffs(114, 298).addBox(-3.25f, -2f, -3.25f, 6.5f, 2f, 6.5f), PartPose.offset(0f, -12f, -2.2f))
            coil_mast.addOrReplaceChild("coil_mast_b", CubeListBuilder.create().texOffs(140, 298).addBox(-3.25f, -2f, -3.25f, 6.5f, 2f, 6.5f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val coil_fore = root.addOrReplaceChild("coil_fore", CubeListBuilder.create().texOffs(241, 192).addBox(-3f, -2f, -3f, 6f, 2f, 6f), PartPose.offset(-9.5f, -20f, -59f))
            coil_fore.addOrReplaceChild("coil_fore_b", CubeListBuilder.create().texOffs(234, 309).addBox(-3f, -2f, -3f, 6f, 2f, 6f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val coil_castle = root.addOrReplaceChild("coil_castle", CubeListBuilder.create().texOffs(74, 191).addBox(-3.5f, -2f, -3.5f, 7f, 2f, 7f), PartPose.offset(15.5f, -30f, 65f))
            coil_castle.addOrReplaceChild("coil_castle_b", CubeListBuilder.create().texOffs(123, 257).addBox(-3.5f, -2f, -3.5f, 7f, 2f, 7f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val hold_a = root.addOrReplaceChild("hold_a", CubeListBuilder.create().texOffs(434, 219).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(205, 178).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(123, 215).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(-6.5f, 3f, 14.5f, 0f, 0.175f, 0f))
            hold_a.addOrReplaceChild("hold_a_b", CubeListBuilder.create().texOffs(239, 222).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(239, 178).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(413, 216).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val hold_b = root.addOrReplaceChild("hold_b", CubeListBuilder.create().texOffs(0, 223).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(357, 179).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(267, 222).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(5.5f, 3f, 14f, 0f, 0.698f, 0f))
            hold_b.addOrReplaceChild("hold_b_b", CubeListBuilder.create().texOffs(144, 223).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(472, 179).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(342, 222).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val hold_c = root.addOrReplaceChild("hold_c", CubeListBuilder.create().texOffs(429, 205).addBox(-3.75f, -3.5f, -3.75f, 7.5f, 3.5f, 7.5f).texOffs(85, 42).addBox(-4.75f, -9f, -4.75f, 9.5f, 5.5f, 9.5f).texOffs(233, 202).addBox(-3.75f, -12.5f, -3.75f, 7.5f, 3.5f, 7.5f), PartPose.offset(-3f, 3f, 24f))
            hold_c.addOrReplaceChild("hold_c_b", CubeListBuilder.create().texOffs(0, 212).addBox(-3.75f, -3.5f, -3.75f, 7.5f, 3.5f, 7.5f).texOffs(432, 110).addBox(-4.75f, -9f, -4.75f, 9.5f, 5.5f, 9.5f).texOffs(371, 202).addBox(-3.75f, -12.5f, -3.75f, 7.5f, 3.5f, 7.5f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            val hold_d = root.addOrReplaceChild("hold_d", CubeListBuilder.create().texOffs(370, 223).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(24, 186).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(172, 223).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(7.5f, 3f, 25.5f, 0f, 0.436f, 0f))
            hold_d.addOrReplaceChild("hold_d_b", CubeListBuilder.create().texOffs(55, 224).addBox(-3.5f, -3f, -3.5f, 7f, 3f, 7f).texOffs(265, 186).addBox(-4.25f, -8f, -4.25f, 8.5f, 5f, 8.5f).texOffs(200, 223).addBox(-3.5f, -11f, -3.5f, 7f, 3f, 7f), PartPose.offsetAndRotation(0f, 0.1f, 0f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("hold_e", CubeListBuilder.create().texOffs(46, 95).addBox(-4.5f, -9f, -5.5f, 9f, 9f, 11f), PartPose.offsetAndRotation(-15f, 3f, 20f, 0f, 0.087f, 0f))
            root.addOrReplaceChild("hold_f", CubeListBuilder.create().texOffs(394, 107).addBox(-4.5f, -8f, -5f, 9f, 8f, 10f), PartPose.offsetAndRotation(15f, 3f, 18f, 0f, -0.14f, 0f))
            root.addOrReplaceChild("hold_g", CubeListBuilder.create().texOffs(412, 188).addBox(-3.75f, -5f, -4f, 7.5f, 5f, 8f), PartPose.offsetAndRotation(15f, -5f, 18f, 0f, 0.209f, 0f))
            root.addOrReplaceChild("pinpost_s0", CubeListBuilder.create().texOffs(8, 352).addBox(-9.4f, -22f, -14.4f, 2f, 10f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pinpost_s1", CubeListBuilder.create().texOffs(142, 352).addBox(-9.4f, -22f, -3.4f, 2f, 10f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pinrail_s", CubeListBuilder.create().texOffs(181, 171).addBox(-9.5f, -23.4f, -14.4f, 2f, 1.5f, 14f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_s0", CubeListBuilder.create().texOffs(25, 223).addBox(-8.9f, -25.4f, -12.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_s1", CubeListBuilder.create().texOffs(388, 296).addBox(-8.9f, -25.4f, -9.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_s2", CubeListBuilder.create().texOffs(160, 298).addBox(-8.9f, -25.4f, -6.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_s3", CubeListBuilder.create().texOffs(320, 300).addBox(-8.9f, -25.4f, -3.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pinpost_p0", CubeListBuilder.create().texOffs(504, 351).addBox(7.6f, -22f, -14.4f, 2f, 10f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pinpost_p1", CubeListBuilder.create().texOffs(0, 352).addBox(7.6f, -22f, -3.4f, 2f, 10f, 2f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pinrail_p", CubeListBuilder.create().texOffs(273, 170).addBox(7.5f, -23.4f, -14.4f, 2f, 1.5f, 14f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_p0", CubeListBuilder.create().texOffs(95, 189).addBox(8.1f, -25.4f, -12.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_p1", CubeListBuilder.create().texOffs(99, 189).addBox(8.1f, -25.4f, -9.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_p2", CubeListBuilder.create().texOffs(0, 223).addBox(8.1f, -25.4f, -6.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("pin_p3", CubeListBuilder.create().texOffs(21, 223).addBox(8.1f, -25.4f, -3.9f, 1f, 5.5f, 1f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("collar", CubeListBuilder.create().texOffs(169, 202).addBox(-4f, -14.4f, -12f, 8f, 2.5f, 8f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("collar_b", CubeListBuilder.create().texOffs(201, 202).addBox(-4f, -1.2f, -4f, 8f, 2.5f, 8f), PartPose.offsetAndRotation(0f, -13.1f, -8f, 0f, 0.785f, 0f))
            root.addOrReplaceChild("frp_s0", CubeListBuilder.create().texOffs(263, 0).addBox(-8.8f, -25f, -51.8f, 1.5f, 6f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("frp_s1", CubeListBuilder.create().texOffs(266, 78).addBox(-13.8f, -25f, -51.8f, 1.5f, 6f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("frc_s", CubeListBuilder.create().texOffs(377, 193).addBox(-2.75f, -1.25f, 0f, 5.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(-10.5f, -26.5f, -51f, 1.571f, 0f, 3.142f))
            root.addOrReplaceChild("frm_s", CubeListBuilder.create().texOffs(138, 193).addBox(-2.75f, -0.5f, 0f, 5.5f, 1f, 1f), PartPose.offsetAndRotation(-10.5f, -23f, -51f, 1.571f, 0f, 3.142f))
            root.addOrReplaceChild("frp_p0", CubeListBuilder.create().texOffs(110, 0).addBox(7.2f, -25f, -51.8f, 1.5f, 6f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("frp_p1", CubeListBuilder.create().texOffs(250, 0).addBox(12.2f, -25f, -51.8f, 1.5f, 6f, 1.5f), PartPose.offset(0f, 0f, 0f))
            root.addOrReplaceChild("frc_p", CubeListBuilder.create().texOffs(442, 10).addBox(-2.75f, -1.25f, 0f, 5.5f, 2.5f, 1.5f), PartPose.offsetAndRotation(10.5f, -26.5f, -51f, -1.571f, 0f, 0f))
            root.addOrReplaceChild("frm_p", CubeListBuilder.create().texOffs(33, 106).addBox(-2.75f, -0.5f, 0f, 5.5f, 1f, 1f), PartPose.offsetAndRotation(10.5f, -23f, -51f, -1.571f, 0f, 0f))
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildLines(root: PartDefinition) {
            root.addOrReplaceChild("brace_s", CubeListBuilder.create().texOffs(489, 0).addBox(-0.5f, 0f, -0.5f, 1f, 128f, 1f), PartPose.offsetAndRotation(-23.1f, -44f, 50f, -0.497f, 0f, 2.863f))
            root.addOrReplaceChild("lift_s", CubeListBuilder.create().texOffs(123, 42).addBox(-0.45f, 0f, -0.45f, 1f, 57f, 1f), PartPose.offsetAndRotation(-1.6f, -183f, -9f, -0.046f, 0f, 1.019f))
            root.addOrReplaceChild("brace_p", CubeListBuilder.create().texOffs(485, 0).addBox(-0.5f, 0f, -0.5f, 1f, 128f, 1f), PartPose.offsetAndRotation(23.1f, -44f, 50f, -0.497f, 0f, -2.863f))
            root.addOrReplaceChild("lift_p", CubeListBuilder.create().texOffs(268, 19).addBox(-0.45f, 0f, -0.45f, 1f, 57f, 1f), PartPose.offsetAndRotation(1.6f, -183f, -9f, -0.046f, 0f, -1.019f))
        }
    }
}
