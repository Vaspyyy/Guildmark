package io.github.vaspyyy.guildmark.client.ship

import net.minecraft.client.renderer.entity.state.EntityRenderState

/**
 * What a cog's model needs to pose it. The renderer fills it in from the ship and the wind, then leans the
 * whole of it by [pitch] and [heel] and lifts it by [bob]; [CogModel] poses the parts from the rest.
 * "Starboard" is the ship's right as it faces forward, "port" its left.
 */
class CogRenderState : EntityRenderState() {
    /** Degrees: which way the ship heads (Minecraft yaw: 0 faces south, 90 west). */
    var yaw = 0f

    /** 0 furled up on the yard, 1 let all the way down. In between, the sail hangs part way. */
    var sail = 0f

    /**
     * How the wind fills the sail. Above 0 it bellies forward (the wind behind it), at 1 as full as it gets;
     * below 0 the sail is taken aback, pressed back against the mast (the wind ahead of it). Scaled by [sail].
     */
    var fill = 0f

    /** 0 to 1: how hard the sail flaps and shivers, head to wind or with the wind spilling out of it. */
    var luff = 0f

    /** Degrees the yard is turned from square across the ship, about the mast: above 0 its starboard arm swings aft. */
    var brace = 0f

    /**
     * Degrees: which way the wind blows, relative to the bow. 0 is straight ahead (a wind from astern), 90 out
     * to starboard, -90 out to port, 180 straight aft. The pennant streams this way.
     */
    var windYaw = 0f

    /** 0 to 1: how strong the wind is, gusts included (the pennant and the slack canvas flutter faster with it). */
    var wind = 0f

    /** Degrees the wheel is turned: above 0, its top swung to starboard (turning the ship to starboard). */
    var wheel = 0f

    /** Degrees the rudder is turned: above 0, the back edge of its blade swung to starboard (turning the ship to starboard). */
    var rudder = 0f

    /** 0 stowed at the cathead, 1 dropped: its rope paid out and the anchor gone down out of sight. */
    var anchor = 0f

    /** 0 to 1: how brightly the lanterns and the cabin windows burn (the glow layer is drawn this strongly). */
    var lanterns = 0f

    /** Ticks since the ship's bell was struck (it swings and settles), or -1. */
    var bell = -1f

    /** Ticks the ship has lived, with the partial tick: every flutter and sway keeps time by it. */
    var time = 0f

    /** Blocks a tick the ship is making through the water. */
    var speed = 0f

    /** Degrees nose up (the renderer's), degrees heeled over to port (the renderer's), and blocks lifted by the swell. */
    var pitch = 0f
    var heel = 0f
    var bob = 0f

    /** Ticks left of the shudder when something strikes the hull, and which way it rocks. */
    var hurtTime = 0f
    var hurtDir = 1

    /** Whether a slain Maelgrasp's beak is mounted on her stem as a figurehead. */
    var figurehead = false
}
