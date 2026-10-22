package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_BURNT
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_COOKING
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_DONE
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_EMPTY
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_FILLING
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_READY
import io.github.vaspyyy.guildmark.client.cooking.StationAnim.Companion.STATE_SERVED
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.vessel.Actions
import io.github.vaspyyy.guildmark.cooking.vessel.Call
import io.github.vaspyyy.guildmark.cooking.vessel.CookTool
import io.github.vaspyyy.guildmark.cooking.vessel.CookToolItem
import io.github.vaspyyy.guildmark.cooking.vessel.Prep
import io.github.vaspyyy.guildmark.cooking.vessel.Region
import io.github.vaspyyy.guildmark.cooking.vessel.StationRegions
import io.github.vaspyyy.guildmark.cooking.vessel.StationView
import io.github.vaspyyy.guildmark.cooking.vessel.Vessel
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.GuiLayer
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The crosshair hints (living-world/cook/design.md 1.1, 1.8): with the crosshair on a station, a small picture and a few words
 * beside it say what a click would do with what is in the hand, where on the station it would land: "Add to the pot" over its
 * mouth, "Feed the fire" at its foot, "Lower the pot" on its chain. The picture is the pot (the vessel: anything the pot, the pan,
 * the oven's door or the spit's rod takes), the flame (the fire: a light, a log) or the chain (the heat, and the spit's crank).
 *
 * The words are worked from what the client itself knows: the station's view, which region the crosshair meets
 * ([StationRegions], the same boxes the server reads a click by), the item in the main hand and whether the player is sneaking.
 * Where the answer would turn on the server's own data (whether an item is an ingredient: the table of roles is the server's) it
 * goes by what any client can tell (a food, a kind of item the Prep Board takes), and a click that the server refuses says why in
 * the action bar as it always did. Nothing is said for a click that would do nothing, for the innkeeper's pot (a display), or
 * while a sneaking click would not reach the station at all (the game then uses the item on the world).
 *
 * It moves: the words slide in from the right as they come and fade, change over with a lift when the click would do another
 * thing (and only after the new answer has held for two ticks, so a crosshair on the line between two regions does not
 * flicker), and the picture is alive (the pot bobs, the chain sways, the flame, which the atlas animates, breathes) and swells
 * as the click lands. The option Cooking Hints (`cookHints`) turns it off: it is gone at once. Only the client loads it.
 */
object CookHints : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cook_hint")

    /** The three pictures (32 x 32 textures shown at 16 x 16) and where each hangs from or stands on, px from the top of the 16. */
    private enum class Icon(path: String, val pivot: Float) {
        POT("cook/hint_pot", 14f),
        FLAME("cook/hint_flame", 14.5f),
        CHAIN("cook/hint_chain", 1f),
        ;

        val sprite: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, path)
    }

    /** One thing a click may be told to do: its picture and its words, `gui.guildmark.cook.hint.<key>`. */
    private class Hint(val key: String, val icon: Icon) {
        val words: Component = Component.translatable("gui.guildmark.cook.hint.$key")
    }

    private val hints = HashMap<String, Hint>()

    private fun hint(key: String, icon: Icon): Hint = hints.getOrPut(key) { Hint(key, icon) }

    // ---- Where it sits ----

    /** The picture's size, and how far from the crosshair's middle it starts and how far the words stand from the picture, px. */
    private const val SIZE = 16
    private const val START = 11
    private const val SPACE = 3

    /** How far the words slide in from the right as they come, and how far a change of words lifts, px. */
    private const val SLIDE = 7f
    private const val LIFT = 4f

    /** Ticks a new answer must hold before the words change (or go): a crosshair at the edge of two regions answers both in turn. */
    private const val HOLD = 2

    // ---- What is on the screen ----

    /** The hint on the screen (or fading away), the one it replaced and how much of it there was when it was replaced. */
    private var shown: Hint? = null
    private var gone: Hint? = null
    private var goneFrom = 0f

    /** How much of [shown] is there (0 to 1), how far the change from [gone] has gone (0 to 1), and the swell of the last click. */
    private val show = Ease()
    private val swap = Ease(1f)
    private val press = Ease()

    /** What the hint has settled on, what the crosshair asks for if that is another (and for how many ticks it has), and a count for the picture's life. */
    private var target: Hint? = null
    private var pending: Hint? = null
    private var pendingFor = 0
    private var ticks = 0L
    private var useWasDown = false

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterGuiLayersEvent::class.java) { event -> event.registerAbove(VanillaGuiLayers.CROSSHAIR, ID, this) }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
    }

    private fun reset() {
        shown = null
        gone = null
        target = null
        pending = null
        pendingFor = 0
        show.snap(0f)
        swap.snap(1f)
        press.snap(0f)
    }

    /** The option Cooking Hints (on until the options are read). */
    private fun optionOn(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.COOK_HINTS.get()

    /** Whether the options and the view let a hint be shown at all: it is a thing beside the crosshair, so only where there is one. */
    private fun allowed(mc: Minecraft): Boolean =
        optionOn() && Cooking.enabled() &&
            mc.gui.screen() == null && mc.options.cameraType == CameraType.FIRST_PERSON && !mc.gui.hud.isHidden && mc.player?.isSpectator != true

    // ---- Each tick ----

    private fun tick() {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) {
            if (shown != null) reset()
            return
        }
        if (mc.isPaused) return
        if (!optionOn()) {
            // Switched off: gone at once
            if (shown != null) reset()
            return
        }
        ticks++
        show.begin()
        swap.begin()
        press.begin()

        val want = if (allowed(mc)) decide(mc) else null
        val settled = target
        if (want === settled) {
            pending = null
            pendingFor = 0
        } else {
            if (want === pending) pendingFor++ else {
                pending = want
                pendingFor = 1
            }
            // A first hint comes at once; a change, or going, waits out a flicker
            if (pendingFor >= (if (settled == null) 1 else HOLD)) {
                retarget(want)
                pending = null
                pendingFor = 0
            }
        }

        show.ease(if (target != null) 1f else 0f, if (target != null) 0.34f else 0.3f)
        swap.ease(1f, 0.36f)
        if (gone != null && swap.cur >= 0.98f) gone = null
        if (target == null && show.cur < 0.01f) {
            shown = null
            gone = null
        }

        // A click swells the picture
        press.ease(0f, 0.3f)
        val down = mc.options.keyUse.isDown
        if (down && !useWasDown && target != null) press.set(1f)
        useWasDown = down
    }

    /** The settled answer is now [want]: new words come up (the old ones, if there are any to see, lift away as they do). */
    private fun retarget(want: Hint?) {
        val old = shown
        if (want != null) {
            if (old != null && old !== want && show.cur > 0.05f) {
                gone = old
                goneFrom = show.cur
                swap.snap(0f)
            } else if (old == null || show.cur <= 0.05f) {
                gone = null
                show.snap(0f)
                swap.snap(1f)
            }
            shown = want
        }
        target = want
    }

    // ---- What a click would do ----

    private fun decide(mc: Minecraft): Hint? {
        val player = mc.player ?: return null
        val anim = StationFx.aimed ?: return null
        val hit = StationFx.aimHit ?: return null
        val kind = anim.kind ?: return null
        val v = anim.view
        // The innkeeper's pot is a display: the click says so and does nothing else
        if (v.mode == 1) return null
        val held = player.mainHandItem
        val off = player.offhandItem
        val sneak = player.isShiftKeyDown
        // A sneaking click with something in a hand that the station does not take never reaches the station (the game uses the item on the world)
        if (sneak && (!held.isEmpty || !off.isEmpty) && !(passes(held) && passes(off))) return null
        val region = StationRegions.of(kind, anim.hearth, anim.facing, anim.pos, player.eyePosition, hit.location)
        if (v.burnt || v.state == STATE_BURNT) {
            // A burnt vessel can only be tipped out
            return if (held.isEmpty && sneak && (region == Region.VESSEL || region == Region.FIRE)) hint("tip", Icon.POT) else null
        }
        return when {
            held.isEmpty -> bare(kind, region, v, sneak)
            held.`is`(Items.FLINT_AND_STEEL) || held.`is`(Items.FIRE_CHARGE) -> if (!v.lit) hint("light", Icon.FLAME) else null
            held.item is CookToolItem -> tool(held, kind, v, sneak)
            held.`is`(Items.WATER_BUCKET) -> if (kind == Station.STEWPOT && open(v) && v.water < Vessel.WATER_FULL) hint("water", Icon.POT) else null
            held.has(DataComponents.COOKING_FUEL) && !held.isDamageableItem && !(region == Region.VESSEL && held.has(DataComponents.FOOD)) -> hint("feed", Icon.FLAME)
            else -> ingredient(held, kind, region, v)
        }
    }

    /** Whether a stack in a hand lets a sneaking click reach a station: nothing in it, or one of the cook's tools (they bypass the sneak). */
    private fun passes(stack: ItemStack): Boolean = stack.isEmpty || stack.item is CookToolItem

    private fun open(v: StationView): Boolean = v.state == STATE_EMPTY || v.state == STATE_FILLING

    private fun cooking(v: StationView): Boolean = v.state == STATE_COOKING || v.state == STATE_READY || v.state == STATE_DONE

    /** A dish that is ready to be served from (ready, overdone, or being served). */
    private fun dish(v: StationView): Boolean = v.state == STATE_READY || v.state == STATE_DONE || v.state == STATE_SERVED

    /** Whether there is anything in the vessel to taste or tip out. */
    private fun stuffIn(v: StationView): Boolean = v.state != STATE_EMPTY || v.items.isNotEmpty() || v.water > 0

    /** The fewest items each station starts with (the vessel's own rule, worked out once). */
    private val minItems: Map<Station, Int> by lazy { Station.entries.associateWith { Vessel.minItems(it) } }

    /** Whether a first stir, a shut door or a turn of the crank would start the clock: the fire is lit, a pot has water, there is enough in the vessel. */
    private fun startable(v: StationView, kind: Station): Boolean =
        v.lit && (kind != Station.STEWPOT || v.water > 0) && v.items.size >= (minItems[kind] ?: 2)

    private fun name(kind: Station): String = kind.name.lowercase()

    /** An empty hand: the heat control, the crank, the door, or, sneaking, the tipping out. */
    private fun bare(kind: Station, region: Region, v: StationView, sneak: Boolean): Hint? = when (region) {
        Region.HEAT -> heat(kind, v, sneak)
        // The crank is the spit's alone: a turn starts the clock on a vessel still open (if it would start), and answers a TURN after
        Region.CRANK -> if (kind != Station.SPIT) null else if (!open(v)) hint("crank", Icon.CHAIN) else if (startable(v, kind)) hint("start_spit", Icon.CHAIN) else null
        Region.VESSEL, Region.FIRE -> when {
            sneak -> if (stuffIn(v)) hint("tip", Icon.POT) else null
            // The oven's door, shut by an empty hand, starts the heating
            kind == Station.OVEN && region == Region.VESSEL && open(v) && startable(v, kind) -> hint("start_oven", Icon.POT)
            else -> null
        }
    }

    /** The heat control with an empty hand: a click eases it one notch, a sneaking click raises it one (a boil-over or a flare is answered by the ease, sneaking or not). */
    private fun heat(kind: Station, v: StationView, sneak: Boolean): Hint? {
        val call = Call.of(v.call)
        val answers = call == Call.BOIL_OVER || call == Call.FLARE
        return if (sneak && !answers) {
            if (v.notch < 2) hint("heat_up_" + name(kind), Icon.CHAIN) else null
        } else {
            if (v.notch > 0 || answers) hint("heat_down_" + name(kind), Icon.CHAIN) else null
        }
    }

    /** One of the cook's tools. */
    private fun tool(held: ItemStack, kind: Station, v: StationView, sneak: Boolean): Hint? {
        val tool = (held.item as CookToolItem).tool
        val ladle = tool == CookTool.LADLE || tool == CookTool.GILDED_LADLE
        // The ladle sneaking tastes, at any station
        if (ladle && sneak) return if (stuffIn(v)) hint("taste", Icon.POT) else null
        val call = Call.of(v.call)
        return when (kind) {
            Station.STEWPOT -> if (ladle) ladling(v, call) else null
            Station.HOB -> if (tool == CookTool.TURNER) turning(v, call) else null
            Station.OVEN -> when (tool) {
                CookTool.TURNER -> if (open(v)) null else if (call == Call.TURN) hint("turn_trays", Icon.POT) else if (dish(v)) hint("serve_oven", Icon.POT) else null
                CookTool.KNIFE -> if (dish(v)) hint("slice", Icon.POT) else null
                else -> null
            }
            Station.SPIT -> if (tool == CookTool.KNIFE && dish(v)) hint("carve", Icon.POT) else null
        }
    }

    /** The ladle on the pot: the first stir starts the clock; then it answers a STICK or a SKIM, serves what is ready, and stirs for nothing else. */
    private fun ladling(v: StationView, call: Call?): Hint? {
        if (open(v)) return if (startable(v, Station.STEWPOT)) hint("start_stewpot", Icon.POT) else null
        return when {
            call == Call.STICK -> hint("stir", Icon.POT)
            call == Call.SKIM -> hint("skim", Icon.POT)
            dish(v) -> hint("serve_stewpot", Icon.POT)
            else -> hint("stir", Icon.POT)
        }
    }

    /** The Turner on the pan: the first scrape starts the clock; then it answers a STICK (a scrape) or a TURN (a flip), lifts out what is ready, and scrapes for nothing else. */
    private fun turning(v: StationView, call: Call?): Hint? {
        if (open(v)) return if (startable(v, Station.HOB)) hint("start_hob", Icon.POT) else null
        return when {
            call == Call.TURN -> hint("flip", Icon.POT)
            call == Call.STICK -> hint("scrape", Icon.POT)
            dish(v) -> hint("serve_hob", Icon.POT)
            else -> hint("scrape", Icon.POT)
        }
    }

    /** Anything else in the hand: put in the vessel if it looks like an ingredient (a food, or a kind of item the board prepares), or, for a SEASON call, the accent it asks for. */
    private fun ingredient(held: ItemStack, kind: Station, region: Region, v: StationView): Hint? {
        if (region == Region.FIRE) return null
        if (Call.of(v.call) == Call.SEASON && cooking(v) && Actions.isAccent(held)) return hint("season", Icon.POT)
        if (!open(v) || v.items.size >= Vessel.capacity(kind)) return null
        val likely = held.has(DataComponents.FOOD) || Prep.preparable(held) || Actions.isAccent(held)
        return if (likely) hint("add_" + name(kind), Icon.POT) else null
    }

    // ---- Each frame ----

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val now = shown ?: return
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null || mc.gui.hud.isHidden) return
        val p = delta.getGameTimeDeltaPartialTick(true)
        val a = show.at(p).coerceIn(0f, 1f)
        val s = swap.at(p).coerceIn(0f, 1f)
        val cx = graphics.guiWidth() / 2
        val cy = graphics.guiHeight() / 2
        val font = mc.font
        val before = gone
        if (before != null) {
            // The words that were there lift away and fade as the new ones come up from below
            draw(graphics, font, before, cx, cy, goneFrom * (1f - s), -s * LIFT, 1f - s, p)
        }
        draw(graphics, font, now, cx, cy, a * s, (1f - s) * LIFT, s, p)
    }

    /**
     * One hint beside the crosshair at [alpha] of its strength, [dy] px below its place; [come] is how far it has come (it slides in
     * from the right with it).
     */
    private fun draw(graphics: GuiGraphicsExtractor, font: Font, hint: Hint, cx: Int, cy: Int, alpha: Float, dy: Float, come: Float, p: Float) {
        if (alpha < 0.02f) return
        val slide = ((1f - smooth(alpha)) * SLIDE).roundToInt()
        val lift = dy.roundToInt()
        val width = font.width(hint.words)
        val x = cx + START + slide
        val y = cy - SIZE / 2 + lift
        val textX = x + SIZE + SPACE
        val textY = y + (SIZE - font.lineHeight) / 2 + 1

        // A soft dark chip under both, so that the words read against sky, snow and fire alike (its corners cut)
        val chip = ARGB.color(alpha * 0.4f, 0x16110D)
        val x0 = x - 3
        val x1 = textX + width + 4
        val y0 = y - 2
        val y1 = y + SIZE + 2
        graphics.fill(x0 + 1, y0, x1 - 1, y1, chip)
        graphics.fill(x0, y0 + 1, x1, y1 - 1, chip)

        graphics.text(font, hint.words, textX, textY, ARGB.color(alpha, 0xFFF3DC), true)
        icon(graphics, hint.icon, x, y, alpha, come, p)
    }

    /** The picture, alive: the pot bobs on its steam, the chain sways from its top, the flame breathes; a click swells it. */
    private fun icon(graphics: GuiGraphicsExtractor, icon: Icon, x: Int, y: Int, alpha: Float, come: Float, p: Float) {
        val t = ticks + p
        var rise = 0f
        var turn = 0f
        var grow = 1f + 0.22f * press.at(p)
        when (icon) {
            Icon.POT -> rise = 0.7f * sin(t * 0.11f)
            Icon.CHAIN -> turn = 0.1f * sin(t * 0.13f) * come
            Icon.FLAME -> grow *= 1f + 0.03f * sin(t * 0.7f)
        }
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x + SIZE / 2f, y + icon.pivot + rise)
        if (turn != 0f) pose.rotate(turn)
        pose.scale(grow, grow)
        pose.translate(-SIZE / 2f, -icon.pivot)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon.sprite, 0, 0, SIZE, SIZE, ARGB.color(alpha, 0xFFFFFF))
        pose.popMatrix()
    }

    /** An ease that starts and ends gently, 0 to 1. */
    private fun smooth(u: Float): Float {
        val k = u.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

}
