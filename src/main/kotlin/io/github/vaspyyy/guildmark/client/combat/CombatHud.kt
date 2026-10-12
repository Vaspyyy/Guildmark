package io.github.vaspyyy.guildmark.client.combat

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.LerpingBossEvent
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent
import net.neoforged.neoforge.client.gui.GuiLayer
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.Tags
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The combat HUD: health, focus and stamina as bars in the top left (in place of the hearts), bars over
 * the creatures you're fighting with the damage you've dealt, the lock-on mark, and our bosses' bars
 * along the bottom of the screen.
 */
object CombatHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "combat_hud")

    private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/$name")
    private val BAR_FRAME = sprite("bar_frame")
    private val BAR_HEALTH = sprite("bar_health")
    private val BAR_POISON = sprite("bar_poison")
    private val BAR_WITHER = sprite("bar_wither")
    private val BAR_FOCUS = sprite("bar_focus")
    private val BAR_STAMINA = sprite("bar_stamina")
    private val BAR_TRAIL = sprite("bar_trail")
    private val BOSS_FRAME = sprite("boss_frame")
    private val BOSS_FILL = sprite("boss_fill")
    private val BOSS_TRAIL = sprite("boss_trail")
    private val ENEMY_FRAME = sprite("enemy_frame")
    private val ENEMY_FILL = sprite("enemy_fill")
    private val LOCK_ON = sprite("lock_on")
    private val STANCE_BROKEN = sprite("stance_broken")
    private val ARMOR = Identifier.withDefaultNamespace("hud/armor_full")

    /** Boss bars named with a key under this prefix are drawn here, along the bottom. */
    const val BOSS_KEY_PREFIX = "boss.guildmark."

    private const val LEFT = 6
    private const val TOP = 6
    private const val ROW = 10
    private const val ENEMY_BAR = 30
    private const val DAMAGE_COLOR = 0xFFF2E6C8.toInt()
    private const val NAME_COLOR = 0xFFE8DCC0.toInt()

    /** A bar's value as shown: it eases towards the real one, and a trail lingers where it was before a loss. */
    private class Meter {
        var shown = -1f
        var trail = -1f
        private var holdUntil = 0L
        /** What was lost in the latest run of hits, and until when it's shown. */
        var damage = 0f
        var damageUntil = 0L

        fun update(value: Float, max: Float, now: Long, dt: Float) {
            if (shown < 0f) {
                shown = value
                trail = value
            }
            if (value < shown - 0.001f) {
                damage = (if (now < damageUntil) damage else 0f) + (shown - value)
                damageUntil = now + 2200
            }
            shown = if (abs(value - shown) < max * 0.002f) value else shown + (value - shown) * min(1f, dt * 0.6f)
            when {
                value >= trail -> {
                    trail = value
                    holdUntil = now + 650
                }
                now > holdUntil -> trail = max(value, trail - max * 0.7f * dt / 20f)
            }
        }
    }

    private val health = Meter()
    private val focus = Meter()
    private val stamina = Meter()

    /** A creature whose bar floats over it. */
    private class Tracked {
        val meter = Meter()
        var lastHealth = -1f
        var shownUntil = 0L
        var visible = false
    }

    private val tracked = Int2ObjectOpenHashMap<Tracked>()
    private val bosses = ArrayList<LerpingBossEvent>()
    private val bossMeters = HashMap<UUID, Meter>()

    // ---- Which parts show ----

    private fun hudEnabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.COMBAT_HUD.get()

    /** Whether the bars stand in for the hearts right now. */
    fun barsShown(): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return false
        return CombatClient.active() && hudEnabled() && minecraft.gameMode?.canHurtPlayer() == true && !player.isSpectator
    }

    /** How much of the top left the bars take up, so other panels can sit below them. */
    fun reservedHeight(): Int = if (barsShown() && !Minecraft.getInstance().debugOverlay.showDebugScreen()) 3 * ROW + 6 else 0

    /** The hearts and armor row give way to the bars. */
    fun onLayer(event: RenderGuiLayerEvent.Pre) {
        if ((event.name == VanillaGuiLayers.PLAYER_HEALTH || event.name == VanillaGuiLayers.ARMOR_LEVEL) && barsShown()) event.isCanceled = true
    }

    /** Our bosses' bars are taken out of the top of the screen and drawn along the bottom instead. */
    fun onBossBar(event: CustomizeGuiOverlayEvent.BossEventProgress) {
        val boss = event.bossEvent
        val key = (boss.name.contents as? TranslatableContents)?.key ?: return
        if (!key.startsWith(BOSS_KEY_PREFIX)) return
        event.isCanceled = true
        event.increment = 0
        bosses.add(boss)
    }

    // ---- Every tick ----

    /** Notes which creatures nearby lost health, so their bars can show. */
    fun tick(player: LocalPlayer, ticks: Long) {
        val level = player.level()
        val seen = HashSet<Int>()
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(32.0))) {
            if (entity === player || entity is Player || entity.typeHolder().`is`(Tags.EntityTypes.BOSSES)) continue
            seen.add(entity.id)
            val t = tracked.get(entity.id) ?: Tracked().also { tracked.put(entity.id, it) }
            val now = entity.health
            val locked = LockOn.target === entity
            val hurt = t.lastHealth >= 0f && now < t.lastHealth
            if (locked || CombatClient.isOpen(entity) || hurt && (CombatClient.isFoe(entity) || CombatClient.engagedRecently(entity.id))) {
                t.shownUntil = ticks + 120
            }
            t.lastHealth = now
            t.visible = entity.isAlive && ticks < t.shownUntil && player.hasLineOfSight(entity)
        }
        tracked.keys.retainAll(seen)
    }

    fun reset() {
        tracked.clear()
        bosses.clear()
        bossMeters.clear()
    }

    // ---- Drawing ----

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        if (player == null) {
            bosses.clear()
            return
        }
        if (minecraft.gui.hud.isHidden) return
        val partial = delta.getGameTimeDeltaPartialTick(false)
        val dt = delta.realtimeDeltaTicks
        val now = Util.getMillis()
        if (CombatClient.active() && hudEnabled()) {
            markers(graphics, minecraft, player, partial, now, dt)
            if (barsShown() && !minecraft.debugOverlay.showDebugScreen()) playerBars(graphics, minecraft, player, now, dt)
        }
        bossBars(graphics, minecraft, player, now, dt)
    }

    private fun playerBars(graphics: GuiGraphicsExtractor, minecraft: Minecraft, player: LocalPlayer, now: Long, dt: Float) {
        val font = minecraft.font
        var y = TOP

        // Health, tinted by poison or wither, and pulsing red when low
        val maxHealth = max(player.maxHealth, player.health)
        val healthWidth = width(60f + maxHealth * 2.5f)
        health.update(player.health, maxHealth, now, dt)
        val fill = when {
            player.hasEffect(MobEffects.POISON) -> BAR_POISON
            player.hasEffect(MobEffects.WITHER) -> BAR_WITHER
            else -> BAR_HEALTH
        }
        val low = player.health <= maxHealth * 0.25f
        val pulse = if (low) red(0.5f + 0.5f * sin(now / 140.0).toFloat()) else -1
        bar(graphics, LEFT, y, healthWidth, health, maxHealth, fill, pulse)
        var x = LEFT + healthWidth + 3
        val armor = player.armorValue
        if (armor > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARMOR, x, y, 9, 9)
            graphics.text(font, armor.toString(), x + 10, y + 1, NAME_COLOR, true)
            x += 12 + font.width(armor.toString())
        }
        val absorption = Mth.ceil(player.absorptionAmount)
        if (absorption > 0) graphics.text(font, "+$absorption", x, y + 1, 0xFFF0C850.toInt(), true)
        y += ROW

        // Focus
        val maxFocus = CombatClient.maxFocus
        focus.update(CombatClient.shownFocus(), maxFocus, now, dt)
        bar(graphics, LEFT, y, width(40f + maxFocus * 0.75f), focus, maxFocus, BAR_FOCUS, -1)
        y += ROW

        // Stamina, flashing when a move failed for want of it
        val maxStamina = CombatClient.maxStamina
        stamina.update(CombatClient.shownStamina(), maxStamina, now, dt)
        val since = CombatClient.ticks - CombatClient.exhaustedAt
        val flash = if (since < 12 && since / 2 % 2 == 0L) red(1f) else -1
        bar(graphics, LEFT, y, width(40f + maxStamina * 0.6f), stamina, maxStamina, BAR_STAMINA, flash)
    }

    private fun width(wanted: Float): Int = wanted.roundToInt().coerceIn(40, 200)

    /** A white-to-red tint: 0 is untouched, 1 fully red. */
    private fun red(amount: Float): Int {
        val gb = (255 * (1f - 0.65f * amount)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (0xFF shl 16) or (gb shl 8) or gb
    }

    private fun bar(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, meter: Meter, max: Float, fill: Identifier, frameColor: Int) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_FRAME, x, y, width, 9, frameColor)
        fills(graphics, x + 3, y + 3, width - 6, 3, meter, max, fill, BAR_TRAIL)
    }

    private fun fills(graphics: GuiGraphicsExtractor, x: Int, y: Int, inner: Int, height: Int, meter: Meter, max: Float, fill: Identifier, trail: Identifier) {
        if (max <= 0f) return
        val shownWidth = (inner * (meter.shown / max).coerceIn(0f, 1f)).roundToInt()
        val trailWidth = (inner * (meter.trail / max).coerceIn(0f, 1f)).roundToInt()
        if (trailWidth > shownWidth) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, trail, x, y, trailWidth, height)
        if (shownWidth > 0) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, fill, x, y, shownWidth, height)
    }

    /** Bars over creatures in the fight, the damage dealt to them, the stance-broken mark and the lock-on mark. */
    private fun markers(graphics: GuiGraphicsExtractor, minecraft: Minecraft, player: LocalPlayer, partial: Float, now: Long, dt: Float) {
        val level = minecraft.level ?: return
        val font = minecraft.font
        val iterator = tracked.int2ObjectEntrySet().iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val t = entry.value
            val entity = level.getEntity(entry.intKey) as? LivingEntity ?: continue
            t.meter.update(entity.health, entity.maxHealth, now, dt)
            if (!t.visible) continue
            val screen = project(minecraft, graphics, entity.getPosition(partial).add(0.0, entity.bbHeight + 0.5, 0.0)) ?: continue
            val sx = screen.first
            val sy = screen.second
            val left = sx - ENEMY_BAR / 2
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ENEMY_FRAME, left, sy, ENEMY_BAR, 5)
            fills(graphics, left + 1, sy + 1, ENEMY_BAR - 2, 3, t.meter, entity.maxHealth, ENEMY_FILL, BAR_TRAIL)
            if (now < t.meter.damageUntil && t.meter.damage >= 0.5f) {
                graphics.text(font, Mth.ceil(t.meter.damage).toString(), left + ENEMY_BAR + 3, sy - 2, DAMAGE_COLOR, true)
            }
            if (CombatClient.isOpen(entity)) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, STANCE_BROKEN, sx - 5, sy - 13, 11, 11)
        }
        val target = LockOn.target ?: return
        if (!target.isAlive) return
        val screen = project(minecraft, graphics, target.getPosition(partial).add(0.0, target.bbHeight * 0.55, 0.0)) ?: return
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LOCK_ON, screen.first - 5, screen.second - 5, 11, 11)
    }

    /** Where a point in the world lands on the HUD, or null when it's behind the camera. */
    private fun project(minecraft: Minecraft, graphics: GuiGraphicsExtractor, point: Vec3): Pair<Int, Int>? {
        val ndc = minecraft.gameRenderer.projectPointToScreen(point)
        if (ndc.z > 1.0 || ndc.z < -1.0 || abs(ndc.x) > 1.2 || abs(ndc.y) > 1.2) return null
        val x = ((ndc.x + 1.0) / 2.0 * graphics.guiWidth()).roundToInt()
        val y = ((1.0 - ndc.y) / 2.0 * graphics.guiHeight()).roundToInt()
        return x to y
    }

    /** Our bosses along the bottom, the newest lowest, each with its name and the damage just dealt. */
    private fun bossBars(graphics: GuiGraphicsExtractor, minecraft: Minecraft, player: LocalPlayer, now: Long, dt: Float) {
        if (bosses.isEmpty()) {
            bossMeters.clear()
            return
        }
        val font = minecraft.font
        val width = min(graphics.guiWidth() - 40, 260)
        val x = (graphics.guiWidth() - width) / 2
        var y = graphics.guiHeight() - 84
        val live = HashSet<UUID>()
        for (boss in bosses.asReversed()) {
            live.add(boss.id)
            val meter = bossMeters.getOrPut(boss.id) { Meter() }
            meter.update(boss.progress, 1f, now, dt)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BOSS_FRAME, x, y, width, 11)
            fills(graphics, x + 4, y + 3, width - 8, 5, meter, 1f, BOSS_FILL, BOSS_TRAIL)
            graphics.text(font, boss.name, x + 3, y - 10, NAME_COLOR, true)
            if (now < meter.damageUntil) {
                val maxHealth = bossEntity(player, boss)?.maxHealth
                if (maxHealth != null && meter.damage * maxHealth >= 0.5f) {
                    val text = Mth.ceil(meter.damage * maxHealth).toString()
                    graphics.text(font, text, x + width - 3 - font.width(text), y - 10, DAMAGE_COLOR, true)
                }
            }
            y -= 24
        }
        bossMeters.keys.retainAll(live)
        bosses.clear()
    }

    /** The creature a boss bar belongs to: the nearest one of the type its name key names. */
    private fun bossEntity(player: LocalPlayer, boss: LerpingBossEvent): LivingEntity? {
        val key = (boss.name.contents as? TranslatableContents)?.key ?: return null
        val path = key.removePrefix(BOSS_KEY_PREFIX)
        return player.level().getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(64.0)) {
            val id = BuiltInRegistries.ENTITY_TYPE.getKey(it.type)
            id.namespace == Guildmark.MOD_ID && id.path == path
        }.minByOrNull { it.distanceToSqr(player) }
    }

    /** The bar's name for a boss of ours: its key is looked up in the language file. */
    fun bossName(id: String): Component = Component.translatable(BOSS_KEY_PREFIX + id)
}
