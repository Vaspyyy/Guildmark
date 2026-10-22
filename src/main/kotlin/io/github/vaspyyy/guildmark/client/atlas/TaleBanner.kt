package io.github.vaspyyy.guildmark.client.atlas

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.TaleTracker
import io.github.vaspyyy.guildmark.client.combat.Cutscenes
import io.github.vaspyyy.guildmark.client.region.RegionTitle
import io.github.vaspyyy.guildmark.client.season.SeasonBanner
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.network.TaleBannerPayload
import io.github.vaspyyy.guildmark.tales.TaleSounds
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.GuiLayer
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A tale's beginning, its turns and its end across the upper screen (living-world/stories/design.md 5.3, Part D), set as a place's
 * name is ([PlaceBanner] is the model): the line in the carved capitals at twice the size, the rule and its diamond, the small
 * line under it. It comes in eased (the words rise into place as they fade up, the rule draws out from the middle, the diamond
 * settles with a little overshoot, the small line follows a breath after, a glint runs once along the capitals) and goes the other
 * way, and the four have their own length, colour and sound: a tale begins (5.5 s), a step turns (3.5 s, smaller and quieter), it
 * ends well (6 s) or ill (6 s).
 *
 * What the server sends ([TaleBannerPayload]) waits here, unseen, until nothing else is across the screen: a place's name, a
 * region's card or a season's, a screen or a cutscene, the hidden HUD. A banner that has waited ten seconds behind another is
 * dropped (the tracker has changed already), one that is up when another comes yields to it and, if it was a beginning or an end,
 * comes back after, and a step that turns while an older one waits replaces it, so two are never across the screen at once and no
 * stale one is shown. The client switch is `taleBanners` (Guildmark Settings > Interface).
 */
object TaleBanner : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tale_banner")

    /** Ticks a banner waits behind a place's, a region's or a season's before it is dropped (10 s; 5.3). */
    private const val PATIENCE = 20 * 10

    /** Ticks after which a banner that has waited for a screen to close is let go (90 s): it would no longer be news. */
    private const val STALE = 20 * 90

    /** Ticks between one banner and the next. */
    private const val GAP = 8

    /** How many banners may wait at once. */
    private const val WAITING = 4

    /** The seconds a banner that yields to another takes to go. */
    private const val YIELD = 0.4f

    /** How one kind of banner looks and sounds. */
    private class Look(
        val seconds: Float,
        val fadeIn: Float,
        val fadeOut: Float,
        /** How many times the usual size the capitals are set. */
        val scale: Float,
        val title: Int,
        val rule: Int,
        /** The colour of the soft light behind the words, of the motes and of the glint, and how strong the light is (0 for none). */
        val glow: Int,
        val glowStrength: Float,
        /** How many motes drift across it, and whether they rise (a light) or fall (an ash). */
        val motes: Int,
        val rising: Boolean,
        /** Whether a glint runs once along the capitals. */
        val glint: Boolean,
        /** Its sting, one of the tales' seven (TaleSounds names them, with what stands in while the pack lacks one). */
        val sound: TaleSounds.Sound,
    )

    private fun lookOf(kind: Int): Look = when (kind) {
        TaleBannerPayload.BEGIN -> Look(
            5.5f, 0.7f, 1.2f, 2f, Ui.GOLD_BRIGHT, Ui.GOLD, 0xFFF0C0, 0.55f, 14, true, true,
            TaleSounds.Sound.BEGIN,
        )
        TaleBannerPayload.STEP -> Look(
            3.5f, 0.5f, 0.9f, 1.5f, Ui.GOLD, Ui.GOLD, 0xFFFFFF, 0f, 0, true, false,
            TaleSounds.Sound.STEP,
        )
        TaleBannerPayload.GOOD -> Look(
            6f, 0.7f, 1.2f, 2f, Ui.GOLD_BRIGHT, 0xFFB8DC96.toInt(), 0xE8FFC0, 0.5f, 16, true, true,
            TaleSounds.Sound.END_GOOD,
        )
        else -> Look(
            6f, 0.9f, 1.4f, 2f, Ui.TEXT, Ui.RED, 0xC8BBA8, 0.22f, 8, false, false,
            TaleSounds.Sound.END_ILL,
        )
    }

    /**
     * One banner: what it says, which tale it is of (the tale's name's key, to tell one tale's banners from another's), and how long
     * it has been up ([age], in seconds that stop while the game does). It is over at [end], which a banner that yields to another
     * brings forward, with [out] the seconds it then takes to go.
     */
    private class Card(
        val kind: Int,
        val title: Component,
        val sub: Component,
        val tale: String,
        val test: Boolean,
        /** The tick it was asked for, so one that waits too long can be dropped. */
        val asked: Int,
        /** True for one that comes back after yielding: it has been heard already. */
        val silent: Boolean = false,
    ) {
        val look: Look = lookOf(kind)
        var age = 0f
        var end = look.seconds
        var out = look.fadeOut

        /** Ticks it has waited behind another banner. */
        var waited = 0
        var yielded = false
    }

    private var card: Card? = null
    private val waiting = ArrayList<Card>()

    /** The ticks the client has run, not counting a paused game's: what a card's waiting is told by. */
    private var ticks = 0
    private var gap = 0

    /** The clock of the last frame, so a card ages by the time that really passed. */
    private var last = 0L

    fun enabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.TALE_BANNERS.get()

    /** At setup, from `GuildmarkClient`: the layer, the clock, and the clean slate on leaving a world (the banners', and the tracker's tales). */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterGuiLayersEvent::class.java) { event ->
            event.registerBelow(VanillaGuiLayers.TITLE, ID, TaleBanner)
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            reset()
            TaleTracker.reset()
        }
    }

    // ---- What comes ----

    /** The server's word: a banner is to be shown. It is kept until nothing else is across the screen. */
    fun receive(payload: TaleBannerPayload) {
        if (!enabled()) {
            // A banner sent by hand says why it did not show, so a tester is not left wondering
            if (payload.test) Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("gui.guildmark.tale.banner.off"), false)
            return
        }
        val kind = payload.banner
        val sub = Component.translatable(payload.subKey, *payload.subArgs.toTypedArray())
        val ended = kind == TaleBannerPayload.GOOD || kind == TaleBannerPayload.ILL
        val tale = if (kind == TaleBannerPayload.BEGIN) payload.titleKey else payload.subKey
        val next = Card(
            kind, Component.translatable(payload.titleKey, *payload.titleArgs.toTypedArray()),
            if (ended) Component.translatable("gui.guildmark.tale.banner.ended", sub) else sub, tale, payload.test, ticks,
        )
        // A turn that comes while an older one waits is the news; and a tale that is over has no steps left to tell
        if (kind == TaleBannerPayload.STEP || ended) waiting.removeAll { it.kind == TaleBannerPayload.STEP && it.tale == tale }
        waiting.add(next)
        while (waiting.size > WAITING) waiting.remove(waiting.firstOrNull { it.kind == TaleBannerPayload.STEP } ?: waiting.first())
    }

    /** Whether a tale's banner is across the screen or about to be. */
    fun showing(): Boolean = card != null || waiting.isNotEmpty()

    fun reset() {
        card = null
        waiting.clear()
        gap = 0
        ticks = 0
    }

    // ---- When ----

    /** Whether a place's name, a region's card or a season's is across the screen (or about to be): the three banners that were here first. */
    private fun others(): Boolean = PlaceBanner.showing() || RegionTitle.showing() || SeasonBanner.showing()

    /** Whether nobody could see a banner now: a screen is open (the chat's aside, which leaves the sky bare), the HUD is hidden or a cutscene has the camera. */
    private fun unseen(minecraft: Minecraft): Boolean {
        val screen = minecraft.gui.screen()
        return minecraft.gui.hud.isHidden || Cutscenes.holdsCamera() || screen != null && screen !is ChatScreen
    }

    /** Every tick: bring on the banner that waits once nothing else is across the screen, and step aside if something comes while one is up. */
    fun tick() {
        if (!enabled()) {
            if (showing()) reset()
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused) return
        ticks++
        val shown = card
        if (shown != null) {
            yieldToOthers(shown)
            return
        }
        waiting.removeAll { ticks - it.asked > STALE }
        if (gap > 0) {
            gap--
            return
        }
        val next = waiting.firstOrNull() ?: return
        if (unseen(minecraft)) return
        if (others()) {
            if (++next.waited > PATIENCE) waiting.remove(next)
            return
        }
        waiting.remove(next)
        card = next
        last = Util.getMillis()
        if (!next.silent) play(next)
    }

    /** Something else came across the screen while this was up: it goes at once, and a beginning or an end comes back after. */
    private fun yieldToOthers(shown: Card) {
        if (shown.yielded || !others()) return
        shown.yielded = true
        if (shown.end - shown.age > YIELD) {
            shown.end = shown.age + YIELD
            shown.out = YIELD
        }
    }

    /** The banner's sound: its sting through TaleSounds, the pack's own, or the stand-in while the pack lacks it (design 13.4). */
    private fun play(card: Card) {
        TaleSounds.ui(card.look.sound)
    }

    // ---- Drawing ----

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val shown = card ?: return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.hud.isHidden) return
        // It ages by the time that really passed since the last frame, and stands still while the game does
        val now = Util.getMillis()
        if (!minecraft.isPaused) shown.age += min(0.25f, (now - last) / 1000f)
        last = now
        if (shown.age >= shown.end) {
            card = null
            gap = GAP
            // What yielded was news the player has not had yet: it comes again, without its sound
            if (shown.yielded && shown.kind != TaleBannerPayload.STEP && !shown.silent) {
                waiting.add(0, Card(shown.kind, shown.title, shown.sub, shown.tale, shown.test, ticks, silent = true))
            }
            return
        }
        draw(graphics, minecraft, shown)
    }

    private fun draw(graphics: GuiGraphicsExtractor, minecraft: Minecraft, shown: Card) {
        val look = shown.look
        val time = shown.age
        val into = (time / look.fadeIn).coerceIn(0f, 1f)
        val outOf = ((time - (shown.end - shown.out)) / shown.out).coerceIn(0f, 1f)
        val alpha = easeOut(into) * (1f - smooth(outOf))
        if (alpha < 0.02f) return
        val font = minecraft.font
        val centerX = graphics.guiWidth() / 2
        val top = (graphics.guiHeight() * 0.24f).toInt()

        // The words rise into place as they fade up, and drift on a little as they go
        val lift = (1f - easeOut(into)) * 8f - easeIn(outOf) * 4f
        val title = Ui.heading(shown.title)
        val width = font.width(title)
        val scale = min(look.scale, (graphics.guiWidth() - 40f) / max(width, 1))
        val height = (Ui.HEADING_HEIGHT * scale).toInt()
        val wide = (width * scale).toInt()

        // A soft light behind them, breathing a little, and the motes that drift across it
        if (look.glowStrength > 0f) {
            val spread = min(wide + 150, graphics.guiWidth() - 20)
            val glowHeight = 62
            val breath = 0.88f + 0.12f * sin(time * 2.2f)
            graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED, Ui.GLOW, centerX - spread / 2, top + height / 2 + lift.toInt() - glowHeight / 2, spread, glowHeight,
                ARGB.color(alpha * look.glowStrength * breath, look.glow),
            )
        }
        if (look.motes > 0) drift(graphics, look, centerX, top + height / 2, min(wide + 120, graphics.guiWidth() - 20), time, alpha)

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(centerX.toFloat(), top + lift)
        pose.scale(scale, scale)
        graphics.text(font, title, -width / 2, Ui.HEADING_RISE, ARGB.color(alpha, look.title), true)
        if (look.glint) glint(graphics, font, title, width, time, alpha)
        pose.popMatrix()

        // The rule draws out from the middle and back into it, the diamond settling on it with a little overshoot
        val closing = shown.out * 0.8f
        val grown = smooth(((time - 0.12f) / 0.85f).coerceIn(0f, 1f)) * (1f - smooth(((time - (shown.end - closing)) / closing).coerceIn(0f, 1f)))
        val full = min(wide + 48, graphics.guiWidth() - 40)
        val rule = (full * grown).toInt()
        val ruleY = top + height + 5
        if (rule >= 4) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIVIDER, centerX - rule / 2, ruleY, rule, 3, ARGB.color(alpha, look.rule))
        val small = shown.kind == TaleBannerPayload.STEP
        val gem = back(((time - 0.45f) / 0.35f).coerceIn(0f, 1f)) * (1f - smooth(outOf))
        val size = (if (small) 5f else 7f) * gem
        if (size >= 1f) {
            val whole = size.toInt() or 1
            graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED, if (small) Ui.DIAMOND else Ui.DIAMOND_LARGE,
                centerX - whole / 2, ruleY + 1 - whole / 2, whole, whole, alpha,
            )
        }

        // The small line follows a breath after
        val follow = easeOut(((time - 0.35f) / 0.6f).coerceIn(0f, 1f))
        val lineAlpha = follow * (1f - smooth(outOf))
        if (lineAlpha >= 0.02f) {
            val text = font.substrByWidth(shown.sub, graphics.guiWidth() - 20)
            val y = ruleY + 7 + ((1f - follow) * 5f).toInt()
            graphics.text(font, Language.getInstance().getVisualOrder(text), centerX - font.width(text) / 2, y, ARGB.color(lineAlpha, Ui.TEXT_SOFT), true)
        }
    }

    /**
     * Light on the carved capitals: once, a breath after they are in, a narrow band of brighter gold runs along them from one end to
     * the other, as if the carving caught a lamp. Three bands, the middle one the brightest, so its edges are soft. Drawn in the
     * capitals' own (scaled) space, so [width] is theirs.
     */
    private fun glint(graphics: GuiGraphicsExtractor, font: Font, title: Component, width: Int, time: Float, alpha: Float) {
        val run = (time - 0.9f) / 0.9f
        if (run < 0f || run > 1f) return
        val at = -width / 2f - 8f + (width + 16f) * smooth(run)
        val strength = alpha * sin(run * Math.PI.toFloat())
        if (strength < 0.03f) return
        for ((from, to, share) in GLINT) {
            val a = strength * share
            // The game draws text whose alpha is almost nothing as fully opaque, so a band this faint is left out
            if (a < 0.02f) continue
            graphics.enableScissor((at + from).toInt(), -2, (at + to).toInt(), 12)
            graphics.text(font, title, -width / 2, Ui.HEADING_RISE, ARGB.color(a, 0xFFF4D8), false)
            graphics.disableScissor()
        }
    }

    /** The glint's bands: where each starts and ends from its middle, and how much of the strength it takes. */
    private val GLINT = listOf(Triple(-7, -3, 0.35f), Triple(-3, 3, 0.9f), Triple(3, 7, 0.35f))

    /** A score of small lights (or specks of ash, falling) crossing the banner on paths of their own, behind its words. */
    private fun drift(graphics: GuiGraphicsExtractor, look: Look, centerX: Int, middle: Int, span: Int, time: Float, alpha: Float) {
        val pose = graphics.pose()
        for (i in 0 until look.motes) {
            val seed = i * 0.6180339f
            val pace = 0.15f + (seed * 5.3f % 1f) * 0.12f
            val along = (time * pace + seed) % 1f
            val x = centerX - span / 2f + span * (seed * 3.1f % 1f) + sin(time * 0.9f + i * 1.7f) * 5f
            val y = if (look.rising) middle + 26f - 54f * along else middle - 26f + 54f * along
            val edge = min(1f, min(along / 0.2f, (1f - along) / 0.3f))
            val a = alpha * edge * (0.25f + 0.45f * (seed * 7.7f % 1f)) * (if (look.rising) 1f else 0.6f)
            if (a < 0.03f) continue
            val size = 4 + i % 3
            pose.pushMatrix()
            pose.translate(x, y)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.GLOW, -size / 2, -size / 2, size, size, ARGB.color(a, look.glow))
            pose.popMatrix()
        }
    }

    // ---- The motions ----

    /** Fast, then settling: how a thing arrives. */
    private fun easeOut(x: Float): Float {
        val k = 1f - x
        return 1f - k * k * k
    }

    /** Slow, then quickening: how a thing goes. */
    private fun easeIn(x: Float): Float = x * x

    /** Slow at both ends. */
    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    /** Past the mark and back: how the diamond settles. */
    private fun back(x: Float): Float {
        val c = 3.2f
        val k = x - 1f
        return 1f + (c + 1f) * k * k * k + c * k * k
    }
}
