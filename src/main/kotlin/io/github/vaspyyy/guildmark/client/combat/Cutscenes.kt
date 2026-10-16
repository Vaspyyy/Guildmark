package io.github.vaspyyy.guildmark.client.combat

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.enemy.FallenWarden
import io.github.vaspyyy.guildmark.enemy.DuskRider
import io.github.vaspyyy.guildmark.enemy.HillTroll
import io.github.vaspyyy.guildmark.dragon.Dragon
import io.github.vaspyyy.guildmark.dragon.DragonRegistry
import io.github.vaspyyy.guildmark.faction.FactionRegistry
import io.github.vaspyyy.guildmark.faction.Renegade
import net.minecraft.world.entity.LivingEntity
import io.github.vaspyyy.guildmark.network.BossCuePayload
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.Music
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.util.context.ContextKey
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent
import net.neoforged.neoforge.client.event.RenderHandEvent
import net.neoforged.neoforge.client.event.RenderLivingEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.event.SelectMusicEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import net.neoforged.neoforge.client.gui.GuiLayer
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Boss moments told on screen. The server cues each one (Halvard waking, the crown's fire taking him, his
 * fall) and the scene plays out here: black bars, a camera that leaves the player to frame him, his voice
 * with captions, then the title card as the fight begins or the banner as it ends. The camera work can be
 * turned off in the client config and skipped with Esc or jump; his words and the cards always play.
 * His music lives here too, and the quieter camera that settles over the player's shoulder while a
 * character talks (see [talk]).
 */
object Cutscenes : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cutscene")

    /** Ticks the camera takes to glide back to the player's eyes. */
    private const val RETURN = 14
    /** How long the title card and the banner stay up. */
    private const val TITLE_TICKS = 95
    private const val BANNER_TICKS = 110
    /** Ticks the talk camera takes to settle over the player's shoulder, and its field of view there. */
    private const val TALK_IN = 12
    private const val TALK_FOV = 58f
    private const val BLACK = 0xFF000000.toInt()
    private const val GOLD = 0xC9A45C
    private const val BONE = 0xF2E8D0

    private class View(val pos: Vec3, val yaw: Float, val pitch: Float, val fov: Float)

    /**
     * Camera work from [start] to [end] ticks. Where the camera sits and what it looks at are given in the
     * boss's own frame (blocks forward, right and up from his feet) as k runs from 0 to 1.
     */
    private class Shot(
        val start: Int,
        val end: Int,
        /** Close shots leave the player out, so their own body never blocks the view. */
        val hideSelf: Boolean,
        val fov: (Float) -> Float,
        val cam: (Float) -> Vec3,
        val look: (Float) -> Vec3,
    )

    private class Line(val from: Int, val until: Int, val key: String)

    private class Scene(
        val cue: Int,
        val bossId: Int,
        var bossPos: Vec3,
        val yaw: Float,
        /** 1 or -1: the camera keeps to the boss's side away from the player. */
        val side: Double,
        val player: LocalPlayer,
        val level: ClientLevel,
    ) {
        var time = 0
        var length = 0
        var shots: List<Shot> = emptyList()
        /** When the camera starts back to the player. */
        var returnAt = -1
        var lines: List<Line> = emptyList()
        var voice: SoundEvent? = null
        var voiceAt = -1
        var stinger: SoundEvent? = null
        var stingerAt = -1
        var stingerPlayed = false
        var title = -1
        var banner = -1
        var bannerText = "cutscene.guildmark.foe_vanquished"
        var shakeAt = -1
        /** Whose words and name these are (cutscene.guildmark.<speaker>). */
        var speaker = "halvard"

        var rig: CameraRig? = null
        var savedType: CameraType? = null
        var returnFrom: View? = null
        var voiceSound: SoundInstance? = null
        /** This frame's camera while it's ours, and how far the field of view has gone back to the player's. */
        var view: View? = null
        var fovBlend = 0f

        val camera: Boolean get() = shots.isNotEmpty()

        /** A point in the boss's frame, in the world. */
        fun world(local: Vec3): Vec3 {
            val a = Math.toRadians(yaw.toDouble())
            val s = sin(a)
            val c = cos(a)
            val r = local.y * side
            return Vec3(bossPos.x - s * local.x - c * r, bossPos.y + local.z, bossPos.z + c * local.x - s * r)
        }
    }

    /** The camera while it's away from the player: an entity that's never added to the world, only looked through. */
    private class CameraRig(level: ClientLevel, private val eye: Float) : Marker(EntityTypes.MARKER, level) {
        init {
            refreshDimensions()
        }

        // Its eye height matches the player's, so the view doesn't drift as the camera changes hands
        override fun getDimensions(pose: net.minecraft.world.entity.Pose): EntityDimensions =
            EntityDimensions.fixed(0f, 0f).withEyeHeight(eye)

        fun place(view: View) {
            val y = view.pos.y - eye
            setPos(view.pos.x, y, view.pos.z)
            xo = view.pos.x
            yo = y
            zo = view.pos.z
            yRot = view.yaw
            yRotO = view.yaw
            xRot = view.pitch
            xRotO = view.pitch
        }
    }

    private var scene: Scene? = null
    private var talk: Talk? = null
    private var ticks = 0
    private var silenceUntil = 0
    private var wardenId = -1
    /** The field boss nearest the player that's fighting, for its music. */
    private var fieldBossId = -1

    private val PHASE_ONE by lazy { Music(ModSounds.MUSIC_WARDEN, 0, 0, true) }
    private val PHASE_TWO by lazy { Music(ModSounds.MUSIC_WARDEN_2, 0, 0, true) }
    private val FIELD_BOSS by lazy { Music(ModSounds.MUSIC_FIELD_BOSS, 0, 0, true) }
    private val DUSK_RIDER by lazy { Music(ModSounds.MUSIC_DUSK_RIDER, 0, 0, true) }
    private val DRAGON by lazy { Music(DragonRegistry.MUSIC, 0, 0, true) }

    private fun camerasOn(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.CUTSCENES.get()

    private fun talkCameraOn(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.DIALOGUE_CAMERA.get()

    /** The camera is away from the player: no moving, fighting or HUD until it's back. */
    fun holdsCamera(): Boolean = scene?.rig != null || talk != null

    // ---- Cues ----

    fun onCue(payload: BossCuePayload) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val felled = payload.cue == BossCuePayload.FELLED
        // A field boss falling nearby doesn't cut into another boss's scene, nor take the camera from a
        // conversation: then it only sounds and shows its banner
        if (felled && scene.let { it != null && it.cue != BossCuePayload.FELLED }) return
        val talking = felled && talk != null
        val boss = level.getEntity(payload.entityId) as? LivingEntity
        end()
        if (!talking) releaseTalk()
        val pos = boss?.position() ?: player.position()
        val yaw = boss?.yBodyRot ?: 0f
        val a = Math.toRadians(yaw.toDouble())
        val right = -(player.x - pos.x) * cos(a) - (player.z - pos.z) * sin(a)
        val s = Scene(payload.cue, payload.entityId, pos, yaw, if (right > 0) -1.0 else 1.0, player, level)
        val camera = boss != null && camerasOn() && player.isAlive && !talking
        if (boss is Renegade) {
            s.speaker = "renegade"
            when (payload.cue) {
                BossCuePayload.INTRO -> renegadeIntro(s, camera)
                BossCuePayload.PHASE_TWO -> renegadePhaseTwo(s)
                BossCuePayload.DEATH -> renegadeDeath(s, camera)
                else -> return
            }
        } else when (payload.cue) {
            BossCuePayload.INTRO -> intro(s, camera)
            BossCuePayload.PHASE_TWO -> phaseTwo(s, camera)
            BossCuePayload.DEATH -> death(s, camera)
            BossCuePayload.FELLED -> felled(s, camera)
            else -> return
        }
        scene = s
        if (s.camera) takeCamera(s)
    }

    /** He wakes, speaks, rises and pulls his sword from the ground; his name as the fight begins. */
    private fun intro(s: Scene, camera: Boolean) {
        s.length = 290
        s.voice = ModSounds.WARDEN_VOICE_INTRO.get()
        s.voiceAt = 10
        // Timed to the pauses in halvard_intro.ogg (see art/audio/README.md)
        s.lines = listOf(Line(10, 66, "halvard.intro.1"), Line(66, 112, "halvard.intro.2"), Line(112, 172, "halvard.intro.3"))
        s.stinger = ModSounds.BOSS_APPEAR.get()
        s.stingerAt = 192
        s.title = 196
        if (!camera) return
        s.shots = listOf(
            // Wide and high: the knight kneeling at his post
            dolly(0, 60, false, 62f, 58f, Vec3(10.0, 7.0, 5.0), Vec3(8.0, 5.0, 3.8), Vec3(0.0, 0.0, 1.2), Vec3(0.0, 0.0, 1.4)),
            // Close and low on the bowed helm as he speaks
            dolly(60, 112, true, 50f, 46f, Vec3(2.4, 1.3, 1.0), Vec3(2.1, 0.7, 1.05), Vec3(0.3, 0.0, 1.95), Vec3(0.3, 0.0, 2.0)),
            // He rises and pulls the sword free: the camera backs away to take in his height
            dolly(112, 166, true, 60f, 64f, Vec3(5.5, -2.0, 0.5), Vec3(6.8, -2.6, 0.7), Vec3(0.0, 0.0, 1.6), Vec3(0.0, 0.0, 2.3)),
            // The roar
            dolly(166, 184, true, 56f, 48f, Vec3(4.2, 0.4, 1.4), Vec3(3.0, 0.2, 1.9), Vec3(0.2, 0.0, 2.55), Vec3(0.2, 0.0, 2.6)),
        )
        s.returnAt = 184
        s.shakeAt = 170
    }

    /** He kneels as the crown's fire takes him: a slow turn around him while he speaks. */
    private fun phaseTwo(s: Scene, camera: Boolean) {
        s.length = 96
        s.voice = ModSounds.WARDEN_VOICE_PHASE2.get()
        s.voiceAt = 6
        s.lines = listOf(Line(6, 92, "halvard.phase2"))
        if (!camera) return
        s.shots = listOf(orbit(0, 44, 55f, 50f, 3.8, 3.3, 30.0, 80.0, 0.8, 1.1, 1.9))
        s.returnAt = 44
    }

    /** He falls to a knee, says his last, and is gone; silence, then the banner. */
    private fun death(s: Scene, camera: Boolean) {
        s.length = 210
        s.voice = ModSounds.WARDEN_VOICE_DEATH.get()
        s.voiceAt = 14
        s.lines = listOf(Line(14, 100, "halvard.death"))
        s.stinger = ModSounds.FOE_VANQUISHED.get()
        s.stingerAt = 100
        s.banner = 100
        silenceUntil = max(silenceUntil, ticks + 260)
        if (!camera) return
        s.shots = listOf(orbit(0, 80, 58f, 52f, 5.2, 4.4, 20.0, 75.0, 2.2, 1.7, 1.3))
        s.returnAt = 80
    }

    /**
     * Silvan Marrow turns from his stones to whoever came, says his piece about the man who sent them, and lifts his
     * staff as the stones wake; his name as the fight begins. The server keeps the player out of it for
     * Renegade.INTRO_SCENE ticks, so the camera is back by then.
     */
    private fun renegadeIntro(s: Scene, camera: Boolean) {
        s.length = 195
        s.voice = FactionRegistry.RENEGADE_VOICE_INTRO.get()
        s.voiceAt = 6
        s.lines = listOf(Line(6, 44, "renegade.intro.1"), Line(44, 86, "renegade.intro.2"))
        s.stinger = ModSounds.BOSS_APPEAR.get()
        s.stingerAt = 92
        s.title = 96
        if (!camera) return
        s.shots = listOf(
            // Wide and high over the ring as he turns
            dolly(0, 40, false, 64f, 60f, Vec3(9.0, 4.5, 4.2), Vec3(7.6, 3.6, 3.4), Vec3(0.0, 0.0, 1.3), Vec3(0.0, 0.0, 1.5)),
            // Close on the hood while he talks of Idris
            dolly(40, 70, true, 50f, 46f, Vec3(2.3, 0.9, 1.75), Vec3(1.95, 0.7, 1.85), Vec3(0.2, 0.0, 1.85), Vec3(0.2, 0.0, 1.9)),
            // Low and backing off as the staff goes up and the stones answer
            dolly(70, 88, true, 58f, 66f, Vec3(5.2, -2.6, 0.5), Vec3(7.4, -3.4, 0.9), Vec3(0.0, 0.0, 1.8), Vec3(0.0, 0.0, 2.6)),
        )
        s.returnAt = 88
        s.shakeAt = 50
    }

    /** He cries out to the sky as the stars begin to fall: only his words, since the fight goes on around them. */
    private fun renegadePhaseTwo(s: Scene) {
        s.length = 64
        s.voice = FactionRegistry.RENEGADE_VOICE_PHASE2.get()
        s.voiceAt = 2
        s.lines = listOf(Line(2, 60, "renegade.phase2"))
    }

    /** He sinks to his knees among his stones with a word for Idris; then the banner. */
    private fun renegadeDeath(s: Scene, camera: Boolean) {
        s.length = 190
        s.voice = FactionRegistry.RENEGADE_VOICE_DEATH.get()
        s.voiceAt = 10
        s.lines = listOf(Line(10, 70, "renegade.death"))
        s.stinger = ModSounds.FOE_VANQUISHED.get()
        s.stingerAt = 76
        s.banner = 76
        silenceUntil = max(silenceUntil, ticks + 230)
        if (!camera) return
        // Until he's gone to motes (Renegade.DEATH_TICKS); the server shields those watching for Renegade.DEATH_SCENE
        s.shots = listOf(orbit(0, 64, 58f, 52f, 5.0, 4.2, 20.0, 80.0, 2.0, 1.6, 1.2))
        s.returnAt = 64
    }

    /** A field boss falls: the camera circles wide as it drops to its knees and pitches over, then the banner. */
    private fun felled(s: Scene, camera: Boolean) {
        if (s.level.getEntity(s.bossId) is Dragon) return dragonFelled(s, camera)
        s.length = 150
        s.stinger = ModSounds.FOE_VANQUISHED.get()
        s.stingerAt = 40
        s.banner = 40
        silenceUntil = max(silenceUntil, ticks + 160)
        if (!camera) return
        s.shots = listOf(orbit(0, 62, 62f, 56f, 9.0, 8.0, 25.0, 70.0, 3.4, 2.6, 1.8))
        s.returnAt = 62
    }

    /** A dragon falls: a wider, longer turn about it as it thrashes, crashes down and goes up in smoke, then its own banner. */
    private fun dragonFelled(s: Scene, camera: Boolean) {
        s.length = 170
        s.stinger = ModSounds.FOE_VANQUISHED.get()
        s.stingerAt = 60
        s.banner = 60
        s.bannerText = "cutscene.guildmark.dragon_slain"
        silenceUntil = max(silenceUntil, ticks + 180)
        if (!camera) return
        // Until it's gone to cinders (Dragon.DEATH_TICKS); the server shields those watching as long (Dragon.FELLED_SCENE)
        s.shots = listOf(orbit(0, 104, 64f, 58f, 17.0, 15.0, 20.0, 80.0, 6.0, 4.5, 2.4))
        s.returnAt = 104
    }

    private fun dolly(start: Int, end: Int, hideSelf: Boolean, fovA: Float, fovB: Float, camA: Vec3, camB: Vec3, lookA: Vec3, lookB: Vec3) =
        Shot(start, end, hideSelf, { k -> Mth.lerp(k, fovA, fovB) }, { k -> camA.lerp(camB, k.toDouble()) }, { k -> lookA.lerp(lookB, k.toDouble()) })

    /** A turn around him at a [radiusA] to [radiusB] distance, from [angleA] to [angleB] degrees off his front. */
    private fun orbit(
        start: Int, end: Int, fovA: Float, fovB: Float, radiusA: Double, radiusB: Double,
        angleA: Double, angleB: Double, heightA: Double, heightB: Double, lookHeight: Double,
    ) = Shot(start, end, true, { k -> Mth.lerp(k, fovA, fovB) }, { k ->
        val d = k.toDouble()
        val a = Math.toRadians(Mth.lerp(d, angleA, angleB))
        val r = Mth.lerp(d, radiusA, radiusB)
        Vec3(cos(a) * r, sin(a) * r, Mth.lerp(d, heightA, heightB))
    }, { Vec3(0.0, 0.0, lookHeight) })

    // ---- The camera ----

    private fun takeCamera(s: Scene) {
        val minecraft = Minecraft.getInstance()
        val rig = CameraRig(s.level, s.player.eyeHeight)
        rig.place(shotView(s, 0f))
        s.rig = rig
        s.savedType = minecraft.options.cameraType
        minecraft.options.cameraType = CameraType.FIRST_PERSON
        LockOn.release()
        minecraft.setCameraEntity(rig)
        // Jump skips the scene, and the game never uses up its presses: forget any left from the fight
        while (minecraft.options.keyJump.consumeClick()) Unit
        val player = s.player
        player.isSprinting = false
        player.deltaMovement = Vec3(0.0, player.deltaMovement.y, 0.0)
        holdStill(player)
    }

    /**
     * Keeps the player where they stand while the camera is away. The game only steers a player by their
     * keys while it looks through their eyes, so their last steps would carry on; telling the server where
     * they are is onTickStart's.
     */
    private fun holdStill(player: LocalPlayer) {
        player.xxa = 0f
        player.zza = 0f
        player.setJumping(false)
    }

    /**
     * While the camera is away, tells the server where the player is, as the game only does while it looks
     * through their eyes. Once a tick, at its start, and only if the camera is still away then: the server
     * drops a player who sends two positions in one tick ("Invalid move player packet"), and the game sends
     * its own on any tick that starts with the camera back. Nothing here hands it back mid-tick.
     */
    fun onTickStart() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isPaused) return
        val player = minecraft.player ?: return
        val camera = minecraft.cameraEntity ?: return
        if (camera !== talk?.rig && camera !== scene?.rig) return
        player.connection.send(ServerboundMovePlayerPacket.PosRot(player.position(), player.yRot, player.xRot, player.onGround(), player.horizontalCollision))
    }

    private fun releaseCamera(s: Scene) {
        val rig = s.rig ?: return
        val minecraft = Minecraft.getInstance()
        s.rig = null
        s.view = null
        if (minecraft.cameraEntity === rig) minecraft.setCameraEntity(minecraft.player)
        s.savedType?.let { minecraft.options.cameraType = it }
    }

    /** Where the camera is at [partial] into this tick. */
    private fun view(s: Scene, partial: Float): View {
        val t = s.time + partial
        if (s.returnAt < 0 || t < s.returnAt) {
            s.fovBlend = 0f
            return shotView(s, t)
        }
        // Back to the player's eyes
        val from = s.returnFrom ?: shotView(s, s.returnAt.toFloat()).also { s.returnFrom = it }
        val player = s.player
        val k = ease(((t - s.returnAt) / RETURN).coerceIn(0f, 1f))
        s.fovBlend = k
        val to = player.getEyePosition(partial)
        return View(
            from.pos.lerp(to, k.toDouble()),
            from.yaw + Mth.wrapDegrees(player.getViewYRot(partial) - from.yaw) * k,
            Mth.lerp(k, from.pitch, player.getViewXRot(partial)),
            from.fov,
        )
    }

    private fun shotView(s: Scene, t: Float): View {
        val shot = s.shots.firstOrNull { t < it.end } ?: s.shots.last()
        val k = gentle(((t - shot.start) / (shot.end - shot.start)).coerceIn(0f, 1f))
        val look = s.world(shot.look(k))
        var cam = s.world(shot.cam(k))
        // Out of walls and hillsides: pull in to just short of whatever's in the way
        val hit = s.level.clip(ClipContext(look, cam, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
        if (hit.type != HitResult.Type.MISS) cam = hit.location.add(look.subtract(hit.location).normalize().scale(0.3))
        return looking(cam, look, shot.fov(k))
    }

    private fun looking(cam: Vec3, at: Vec3, fov: Float): View {
        val d = at.subtract(cam)
        val yaw = Math.toDegrees(atan2(-d.x, d.z)).toFloat()
        val pitch = Math.toDegrees(-atan2(d.y, sqrt(d.x * d.x + d.z * d.z))).toFloat()
        return View(cam, yaw, pitch, fov)
    }

    /** Turns the player to face him, so the camera comes back looking his way. */
    private fun faceBoss(s: Scene) = face(s.player, s.bossPos.add(0.0, 1.8, 0.0))

    private fun face(player: LocalPlayer, at: Vec3) {
        val d = at.subtract(player.eyePosition)
        val yaw = player.yRot + Mth.wrapDegrees(Math.toDegrees(atan2(-d.x, d.z)).toFloat() - player.yRot)
        val pitch = Math.toDegrees(-atan2(d.y, sqrt(d.x * d.x + d.z * d.z))).toFloat().coerceIn(-30f, 30f)
        player.yRot = yaw
        player.yRotO = yaw
        player.yHeadRot = yaw
        player.yHeadRotO = yaw
        player.xRot = pitch
        player.xRotO = pitch
    }

    private fun gentle(k: Float): Float = 0.5f * k + 0.5f * k * k * (3f - 2f * k)

    private fun ease(k: Float): Float = if (k < 0.5f) 4f * k * k * k else 1f - (-2f * k + 2f).let { it * it * it } / 2f

    // ---- Talking ----

    /** The camera while a character talks, for as long as [screen] stays open. */
    private class Talk(
        val speaker: Entity,
        val player: LocalPlayer,
        val level: ClientLevel,
        var screen: Screen,
        val rig: CameraRig,
        var savedType: CameraType,
    ) {
        var time = 0
        /** When the camera started back to the player's eyes, or -1 while the talk goes on. */
        var closingAt = -1
        var from: View? = null
        var view: View? = null
        /** How far the field of view is the player's own (1) rather than the shot's (0). */
        var fovBlend = 1f
        /** The field of view's blend when the camera started back, so it eases from there. */
        var fovFrom = 0f
    }

    /**
     * Settles the camera over the player's shoulder, looking at [speaker], while [screen] (their
     * conversation) is open; it glides back to the player's eyes when the screen closes. Nothing happens
     * if a boss scene has the camera, the player is looking through something else, or it's turned off.
     */
    fun talk(speaker: Entity, screen: Screen) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val current = talk
        if (current != null && current.speaker === speaker && current.player === player) {
            // Another conversation with the same character: carry on with this camera
            current.screen = screen
            current.closingAt = -1
            current.from = null
            return
        }
        if (scene != null || current != null || !talkCameraOn() || !player.isAlive || minecraft.cameraEntity !== player) return
        face(player, speaker.eyePosition)
        val rig = CameraRig(level, player.eyeHeight)
        rig.place(View(player.eyePosition, player.yRot, player.xRot, TALK_FOV))
        talk = Talk(speaker, player, level, screen, rig, minecraft.options.cameraType)
        minecraft.options.cameraType = CameraType.FIRST_PERSON
        LockOn.release()
        minecraft.setCameraEntity(rig)
        player.isSprinting = false
        holdStill(player)
    }

    private fun tickTalk(minecraft: Minecraft) {
        val t = talk ?: return
        val player = minecraft.player
        if (player == null || player !== t.player || minecraft.level !== t.level || !player.isAlive) {
            releaseTalk()
            return
        }
        holdStill(player)
        t.time++
        val gone = !t.speaker.isAlive || t.speaker.isRemoved || t.speaker.distanceToSqr(player) > 144.0
        if (t.closingAt < 0 && (minecraft.gui.screen() !== t.screen || gone)) {
            t.closingAt = t.time
            t.from = t.view
            t.fovFrom = t.fovBlend
        }
        if (t.closingAt >= 0 && t.time >= t.closingAt + RETURN) releaseTalk()
    }

    private fun releaseTalk() {
        val t = talk ?: return
        val minecraft = Minecraft.getInstance()
        talk = null
        if (minecraft.cameraEntity === t.rig) minecraft.setCameraEntity(minecraft.player)
        minecraft.options.cameraType = t.savedType
    }

    /** From the player's eyes to over their shoulder and, once the talk is over, back again. */
    private fun talkView(t: Talk, partial: Float): View {
        val player = t.player
        val eyes = View(player.getEyePosition(partial), player.getViewYRot(partial), player.getViewXRot(partial), TALK_FOV)
        val time = t.time + partial
        if (t.closingAt >= 0) {
            val k = ease(((time - t.closingAt) / RETURN).coerceIn(0f, 1f))
            t.fovBlend = Mth.lerp(k, t.fovFrom, 1f)
            return blend(t.from ?: shoulder(t, partial), eyes, k)
        }
        val k = ease((time / TALK_IN).coerceIn(0f, 1f))
        t.fovBlend = 1f - k
        return if (k >= 1f) shoulder(t, partial) else blend(eyes, shoulder(t, partial), k)
    }

    /**
     * Behind the player and off their right shoulder, a little above their eyes, looking at the speaker's
     * chest: their face sits in the upper half of the screen, clear of the conversation panel below.
     */
    private fun shoulder(t: Talk, partial: Float): View {
        val eye = t.player.getEyePosition(partial)
        val face = t.speaker.getEyePosition(partial)
        var d = Vec3(face.x - eye.x, 0.0, face.z - eye.z)
        d = if (d.lengthSqr() < 1.0E-4) Vec3.directionFromRotation(0f, t.player.yRot).multiply(1.0, 0.0, 1.0).normalize() else d.normalize()
        val right = Vec3(-d.z, 0.0, d.x)
        var cam = eye.add(d.scale(-1.25)).add(right.scale(0.85)).add(0.0, 0.15, 0.0)
        val look = face.add(right.scale(-0.3)).add(0.0, -0.5, 0.0)
        val hit = t.level.clip(ClipContext(eye, cam, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
        if (hit.type != HitResult.Type.MISS) cam = hit.location.add(eye.subtract(hit.location).normalize().scale(0.25))
        return looking(cam, look, TALK_FOV)
    }

    private fun blend(a: View, b: View, k: Float): View = View(
        a.pos.lerp(b.pos, k.toDouble()),
        a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * k,
        Mth.lerp(k, a.pitch, b.pitch),
        Mth.lerp(k, a.fov, b.fov),
    )

    // ---- Every tick ----

    fun onTick() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isPaused) return
        ticks++
        if (ticks % 10 == 0) {
            findWarden(minecraft)
            findFieldBoss(minecraft)
        }
        tickTalk(minecraft)
        val s = scene ?: return
        val player = minecraft.player
        if (player == null || player !== s.player || minecraft.level !== s.level || !player.isAlive) {
            end()
            return
        }
        (s.level.getEntity(s.bossId) as? LivingEntity)?.let { s.bossPos = it.position() }
        if (s.rig != null && s.time < s.returnAt) {
            var skip = false
            while (minecraft.options.keyJump.consumeClick()) skip = true
            if (skip) skip(s)
        }
        if (s.rig != null) holdStill(player)
        s.time++
        val t = s.time
        if (t == s.voiceAt) s.voice?.let { s.voiceSound = play(it, SoundSource.VOICE) }
        if (!s.stingerPlayed && s.stingerAt in 0..t) {
            s.stingerPlayed = true
            s.stinger?.let { play(it, SoundSource.MUSIC) }
        }
        if (t == s.shakeAt && s.rig != null) CameraFx.shake(0.6f)
        if (t == s.returnAt && !BossCuePayload.ends(s.cue)) faceBoss(s)
        if (s.rig != null && t >= s.returnAt + RETURN) releaseCamera(s)
        if (t >= s.length) end()
    }

    /**
     * Straight to the camera's way back. What was to come after it keeps its place relative to it; what was to come
     * before it (a field boss's banner) keeps its time.
     */
    private fun skip(s: Scene) {
        s.voiceSound?.let { Minecraft.getInstance().soundManager.stop(it) }
        s.voice = null
        s.lines = emptyList()
        s.returnFrom = s.view
        val from = s.returnAt
        val jump = from - s.time
        s.returnAt = s.time
        if (s.title >= from) s.title -= jump
        if (s.banner >= from) s.banner -= jump
        if (s.stingerAt >= from) s.stingerAt -= jump
        s.length = maxOf(s.length - jump, if (s.title >= 0) s.title + TITLE_TICKS else 0, if (s.banner >= 0) s.banner + BANNER_TICKS else 0)
        if (!BossCuePayload.ends(s.cue)) faceBoss(s)
    }

    private fun end() {
        val s = scene ?: return
        s.voiceSound?.let { Minecraft.getInstance().soundManager.stop(it) }
        releaseCamera(s)
        scene = null
    }

    fun reset() {
        end()
        releaseTalk()
        silenceUntil = 0
        wardenId = -1
        fieldBossId = -1
    }

    private fun play(sound: SoundEvent, source: SoundSource): SoundInstance {
        val instance = SimpleSoundInstance(
            sound.location(), source, 1f, 1f, SoundInstance.createUnseededRandom(),
            false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
        )
        Minecraft.getInstance().soundManager.play(instance)
        return instance
    }

    private fun findWarden(minecraft: Minecraft) {
        val player = minecraft.player
        val level = minecraft.level
        wardenId = if (player == null || level == null) -1 else {
            level.getEntitiesOfClass(FallenWarden::class.java, player.boundingBox.inflate(48.0)) { it.isAlive && !it.dormant }
                .minByOrNull { it.distanceToSqr(player) }?.id ?: -1
        }
    }

    /** A field boss with a fight on: a hill troll, or a Dusk Rider that isn't fading away. */
    private fun fightingFieldBoss(entity: LivingEntity): Boolean =
        (entity is HillTroll && entity.fighting) || (entity is DuskRider && entity.fighting && !entity.fading) || (entity is Dragon && entity.fighting) ||
            (entity is Renegade && entity.fighting)

    private fun findFieldBoss(minecraft: Minecraft) {
        val player = minecraft.player
        val level = minecraft.level
        if (player == null || level == null) {
            fieldBossId = -1
            return
        }
        // The boss whose theme is playing keeps it while its fight goes on near you, so two at once don't trade themes
        val current = level.getEntity(fieldBossId) as? LivingEntity
        val keep = if (current is Dragon) DRAGON_KEEP_THEME_RANGE else KEEP_THEME_RANGE
        if (current != null && current.isAlive && fightingFieldBoss(current) && current.distanceToSqr(player) < keep * keep) return
        // A dragon's fight ranges over its peak and the sky above it, so its theme carries further
        fieldBossId = (level.getEntitiesOfClass(CombatEnemy::class.java, player.boundingBox.inflate(48.0)) { it.isAlive && fightingFieldBoss(it) }
            .minByOrNull { it.distanceToSqr(player) }
            ?: level.getEntitiesOfClass(Dragon::class.java, player.boundingBox.inflate(DRAGON_THEME_RANGE)) { it.isAlive && it.fighting }
                .minByOrNull { it.distanceToSqr(player) })?.id ?: -1
    }

    /** How near a field boss whose theme is playing has to stay for it to keep playing. */
    private const val KEEP_THEME_RANGE = 56.0
    private const val DRAGON_THEME_RANGE = 96.0
    private const val DRAGON_KEEP_THEME_RANGE = 112.0

    // ---- Hooks ----

    /** Places the camera for this frame, as the game asks where it's looking. */
    fun onAngles(event: ViewportEvent.ComputeCameraAngles) {
        talk?.let { t ->
            if (event.camera.entity() !== t.rig) return
            firstPerson { t.savedType = it }
            val v = talkView(t, event.partialTick)
            t.view = v
            t.rig.place(v)
            event.yaw = v.yaw
            event.pitch = v.pitch
            event.roll = 0f
            return
        }
        val s = scene ?: return
        val rig = s.rig ?: return
        if (event.camera.entity() !== rig) return
        // Through the rig's eyes even if the view is switched mid-scene; the choice is kept for afterwards
        firstPerson { s.savedType = it }
        val v = view(s, event.partialTick)
        s.view = v
        rig.place(v)
        event.yaw = v.yaw
        event.pitch = v.pitch
        event.roll = 0f
    }

    /** Back to looking through the camera's own eyes if the view was switched; [saved] keeps the player's choice. */
    private fun firstPerson(saved: (CameraType) -> Unit) {
        val options = Minecraft.getInstance().options
        if (options.cameraType != CameraType.FIRST_PERSON) {
            saved(options.cameraType)
            options.cameraType = CameraType.FIRST_PERSON
        }
    }

    fun onFov(event: ViewportEvent.ComputeFov) {
        talk?.let { t ->
            event.fov = Mth.lerp(t.fovBlend, TALK_FOV, event.fov)
            return
        }
        val s = scene ?: return
        val v = s.view ?: return
        if (s.rig == null) return
        event.fov = Mth.lerp(s.fovBlend, v.fov, event.fov)
    }

    fun onLayer(event: RenderGuiLayerEvent.Pre) {
        if (holdsCamera() && event.name != ID) event.isCanceled = true
    }

    fun onRenderHand(event: RenderHandEvent) {
        if (holdsCamera()) event.isCanceled = true
    }

    private val SELF: ContextKey<Boolean> = ContextKey(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cutscene_self"))

    /** Marks the player's own body, so close shots can leave it out. */
    val AVATAR = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
            state.setRenderData(SELF, if (entity === Minecraft.getInstance().player) true else null)
        }
    }

    fun onRenderPre(event: RenderLivingEvent.Pre<*, *, *>) {
        talk?.let { t ->
            // Over the shoulder the player is in the shot; only as the camera nears their eyes do they go
            val at = t.view?.pos ?: return
            if (event.renderState.getRenderData(SELF) == true && at.distanceToSqr(t.player.eyePosition) < 0.9) event.isCanceled = true
            return
        }
        val s = scene ?: return
        if (s.rig == null || event.renderState.getRenderData(SELF) != true) return
        val t = s.time.toFloat()
        val returning = s.returnAt >= 0 && t >= s.returnAt
        if (returning || (s.shots.firstOrNull { t < it.end } ?: s.shots.last()).hideSelf) event.isCanceled = true
    }

    /** Esc skips the camera work instead of pausing (a lost window still pauses). */
    fun onScreenOpening(event: ScreenEvent.Opening) {
        val s = scene ?: return
        if (event.newScreen is PauseScreen && s.rig != null && s.time < s.returnAt && Minecraft.getInstance().isWindowActive) {
            event.isCanceled = true
            skip(s)
        }
    }

    /**
     * Silence for his words, his music for his fight, and silence again when he falls. A field boss in a
     * fight has a battle theme of its own, and its fall brings the same silence.
     */
    fun onSelectMusic(event: SelectMusicEvent) {
        if (ticks < silenceUntil) {
            event.overrideMusic(null)
            return
        }
        val level = Minecraft.getInstance().level
        val warden = level?.getEntity(wardenId) as? FallenWarden
        if (warden != null && warden.isAlive && !warden.dormant) {
            event.overrideMusic(if (!warden.fighting) null else if (warden.phaseTwo) PHASE_TWO else PHASE_ONE)
            return
        }
        val field = level?.getEntity(fieldBossId) as? LivingEntity
        if (field != null && field.isAlive && fightingFieldBoss(field)) {
            event.overrideMusic(
                when (field) {
                    is DuskRider -> DUSK_RIDER
                    is Dragon -> DRAGON
                    else -> FIELD_BOSS
                },
            )
            return
        }
        val playing = event.playingMusic?.identifier
        if (playing == ModSounds.MUSIC_WARDEN.get().location() || playing == ModSounds.MUSIC_WARDEN_2.get().location() ||
            playing == ModSounds.MUSIC_FIELD_BOSS.get().location() || playing == ModSounds.MUSIC_DUSK_RIDER.get().location() ||
            playing == DragonRegistry.MUSIC.get().location()) event.overrideMusic(null)
    }

    // ---- On screen ----

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val s = scene ?: return
        val minecraft = Minecraft.getInstance()
        val font = minecraft.font
        val t = s.time + delta.getGameTimeDeltaPartialTick(true)
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()

        // Black bars while the camera is away
        val shown = when {
            !s.camera -> 0f
            s.returnAt >= 0 && t >= s.returnAt -> 1f - ease(((t - s.returnAt) / RETURN).coerceIn(0f, 1f))
            else -> ease((t / 12f).coerceIn(0f, 1f))
        }
        val bar = (height * 0.12f * shown).roundToInt()
        if (bar > 0) {
            graphics.fill(0, 0, width, bar, BLACK)
            graphics.fill(0, height - bar, width, height, BLACK)
        }

        captions(graphics, font, s, t, width, height, bar)
        if (s.title >= 0) titleCard(graphics, font, s.speaker, t - s.title, width, height)
        if (s.banner >= 0) banner(graphics, font, s.bannerText, t - s.banner, width, height)

        if (s.rig != null && t >= 20f && t < s.returnAt && bar >= 14) {
            val hint = Component.translatable("cutscene.guildmark.skip", minecraft.options.keyJump.translatedKeyMessage)
            val alpha = ((t - 20f) / 10f).coerceIn(0f, 1f) * 0.8f
            graphics.text(font, hint, width - font.width(hint) - 8, height - bar / 2 - 4, ARGB.color(alpha, 0xA0A0A0), false)
        }
    }

    /** What he's saying: his name over the line, in the lower bar or above the hotbar. */
    private fun captions(graphics: GuiGraphicsExtractor, font: Font, s: Scene, t: Float, width: Int, height: Int, bar: Int) {
        val line = s.lines.firstOrNull { t >= it.from && t < it.until } ?: return
        val alpha = min(min(1f, (t - line.from) / 4f), (line.until - t) / 4f).coerceIn(0f, 1f)
        if (alpha < 0.03f) return
        val speaker = Component.translatable("cutscene.guildmark." + s.speaker)
        val rows = font.split(Component.translatable("cutscene.guildmark." + line.key), min(width - 40, 320))
        val blockHeight = 11 + rows.size * 10
        val top = if (bar >= blockHeight + 4) height - bar / 2 - blockHeight / 2 else height - 74 - blockHeight
        if (bar < blockHeight + 4) {
            val wide = rows.maxOf { font.width(it) } + 16
            graphics.fill(width / 2 - wide / 2, top - 4, width / 2 + wide / 2, top + blockHeight + 2, ARGB.color(alpha * 0.55f, 0x000000))
        }
        graphics.text(font, speaker, width / 2 - font.width(speaker) / 2, top, ARGB.color(alpha, GOLD), true)
        for ((i, row) in rows.withIndex()) {
            graphics.text(font, row, width / 2 - font.width(row) / 2, top + 11 + i * 10, ARGB.color(alpha, 0xFFFFFF), true)
        }
    }

    /** His name, letter-spaced over a rule that opens from the middle, as the fight begins. */
    private fun titleCard(graphics: GuiGraphicsExtractor, font: Font, speaker: String, t: Float, width: Int, height: Int) {
        if (t < 0f || t > TITLE_TICKS) return
        val alpha = min(min(1f, t / 14f), (TITLE_TICKS - t) / 20f).coerceIn(0f, 1f)
        if (alpha < 0.03f) return
        val y = (height * 0.34f).roundToInt()
        val half = (110 * ease(min(1f, t / 18f))).roundToInt()
        graphics.fill(width / 2 - half, y, width / 2 + half, y + 1, ARGB.color(alpha * 0.8f, GOLD))
        val name = Component.translatable("cutscene.guildmark.$speaker").string.uppercase()
        spaced(graphics, font, name, width / 2f, y - 30f, 3f, 2, ARGB.color(alpha, BONE))
        spaced(graphics, font, Component.translatable("cutscene.guildmark.$speaker.epithet").string, width / 2f, y + 6f, 1.25f, 1, ARGB.color(alpha, GOLD))
    }

    /** The banner when he falls, across a band of shadow. */
    private fun banner(graphics: GuiGraphicsExtractor, font: Font, key: String, t: Float, width: Int, height: Int) {
        if (t < 0f || t > BANNER_TICKS) return
        val alpha = min(min(1f, t / 10f), (BANNER_TICKS - t) / 25f).coerceIn(0f, 1f)
        if (alpha < 0.03f) return
        val y = (height * 0.42f).roundToInt()
        val shade = ARGB.color(alpha * 0.6f, 0x000000)
        val clear = ARGB.color(0f, 0x000000)
        graphics.fillGradient(0, y - 30, width, y - 14, clear, shade)
        graphics.fill(0, y - 14, width, y + 14, shade)
        graphics.fillGradient(0, y + 14, width, y + 30, shade, clear)
        val text = Component.translatable(key).string.uppercase()
        val scale = 2.4f + 0.3f * (t / BANNER_TICKS)
        // A soft glow behind the letters
        spaced(graphics, font, text, width / 2f, y - 4.5f * scale * 1.06f, scale * 1.06f, 2, ARGB.color(alpha * 0.25f, 0xF5D58A))
        spaced(graphics, font, text, width / 2f, y - 4.5f * scale, scale, 2, ARGB.color(alpha, 0xE8C468))
    }

    private fun spaced(graphics: GuiGraphicsExtractor, font: Font, text: String, centre: Float, y: Float, scale: Float, spacing: Int, color: Int) {
        val widths = text.map { font.width(it.toString()) }
        val total = widths.sum() + spacing * (text.length - 1)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(centre, y)
        pose.scale(scale, scale)
        var x = -total / 2f
        for ((i, ch) in text.withIndex()) {
            graphics.text(font, ch.toString(), x.roundToInt(), 0, color, true)
            x += widths[i] + spacing
        }
        pose.popMatrix()
    }
}
