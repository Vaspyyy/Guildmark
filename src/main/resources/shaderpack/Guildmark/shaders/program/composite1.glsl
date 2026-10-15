// Light shafts, at half size (scale.composite1): sunlight caught in the mist, walked along each view ray
// through the shadow map, so trees, walls and hills cut beams out of it.
// Out: colortex3 = the light the mist scatters toward the eye, and the distance it was walked over.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/space.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/fog.glsl"
#ifndef WORLD_NETHER
#include "/lib/shadows.glsl"
#endif
#include "/lib/water.glsl"

#ifdef VSH
out vec2 texcoord;
void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}
#endif

#ifdef FSH
uniform sampler2D depthtex0;
#ifdef DISTANT_HORIZONS
uniform sampler2D dhDepthTex0;
uniform mat4 dhProjectionInverse;
#endif

in vec2 texcoord;

/* RENDERTARGETS: 3 */
layout(location = 0) out vec4 outShafts;

#if defined SUN_RAYS && !defined WORLD_NETHER
/** Whether the sky shows at uv: nothing near (or far, with Distant Horizons) stands in front of it. */
float openSky(vec2 uv) {
    float open = step(1.0, texture(depthtex0, uv).r);
#ifdef DISTANT_HORIZONS
    open *= step(1.0, texture(dhDepthTex0, uv).r);
#endif
    return open;
}

/**
 * Rays from the sun (or moon) across the screen, where it shines between trees, walls and hills: how much open sky
 * lies on the way from this pixel toward the light, 1 where nothing stands in between. Off screen it stays 1.
 */
float sunRays(vec2 uv, Atmos a, float noise) {
    vec3 lv = mat3(gbufferModelView) * a.lightDir;
    if (lv.z > -0.05) return 1.0;
    vec4 clip = gbufferProjection * vec4(lv, 1.0);
    vec2 lightUv = clip.xy / clip.w * 0.5 + 0.5;
    // Fade out as the light leaves the screen
    vec2 outside = max(abs(lightUv - 0.5) - 0.5, 0.0);
    float shown = exp(-dot(outside, outside) * 6.0) * smoothstep(-0.05, -0.25, lv.z);
    if (shown < 0.01) return 1.0;
    vec2 delta = lightUv - uv;
    const int N = 24;
    float sum = 0.0;
    float total = 0.0;
    float w = 1.0;
    for (int i = 0; i < N; i++) {
        vec2 p = uv + delta * ((float(i) + noise) / float(N));
        if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) break;
        sum += openSky(p) * w;
        total += w;
        w *= 0.93;
    }
    float open = total > 0.0 ? sum / total : 1.0;
    return mix(1.0, open, shown);
}
#endif

void main() {
    vec2 uv = texcoord;
    float depth = texture(depthtex0, uv).r;
    vec3 view = vec3(0.0);
    bool sky = false;
    if (depth >= 1.0) {
        sky = true;
#ifdef DISTANT_HORIZONS
        float dd = texture(dhDepthTex0, uv).r;
        if (dd < 1.0) {
            sky = false;
            view = screenToView(uv, dd, dhProjectionInverse);
        }
#endif
        if (sky) view = screenToView(uv, 1.0, gbufferProjectionInverse);
    } else {
        view = screenToView(uv, depth, gbufferProjectionInverse);
    }
    vec3 player = viewToPlayer(view);
    float dist = sky ? 600.0 : length(player);
    vec3 dir = normalize(player);

#if defined LIGHT_SHAFTS && !defined WORLD_NETHER
    Atmos a = atmosphere();
    float strength = max(a.direct.r, max(a.direct.g, a.direct.b));
    if (strength < 1e-5 || depth < 0.56) {
        outShafts = vec4(0.0, 0.0, 0.0, dist);
        return;
    }
    float noise = dither(gl_FragCoord.xy, frameCounter);
    float cosT = dot(dir, a.lightDir);
    // Mostly forward: beams glow looking toward the sun and fade looking away, without drowning the view
    float phase = 0.10 + hgPhase(cosT, 0.80) * PI * 0.08;
    float camY = cameraPosition.y;
    float marchEnd = min(dist, shadowDistance * 0.9);
    float stepLen = marchEnd / float(SHAFT_STEPS);
    float sunCloud = cloudShadow(cameraPosition, a);
    vec3 scatter = vec3(0.0);

    if (isEyeInWater == 1) {
        // Under water: beams through the murk, the light bluing and greening with depth
        vec3 absorb = waterAbsorption();
        float density = 0.035;
        for (int i = 0; i < SHAFT_STEPS; i++) {
            float t = (float(i) + noise) * stepLen;
            vec3 p = dir * t;
            float lit = shadowTap(p);
            scatter += exp(-absorb * t) * lit * density * stepLen;
        }
        scatter *= a.direct * phase * sunCloud * vec3(0.55, 0.85, 0.80) * SHAFT_STRENGTH;
        outShafts = vec4(scatter, dist);
        return;
    }

    Mist m = mistAmounts(a);
    float litShare = 0.0;
    float sum = 0.0;
    for (int i = 0; i < SHAFT_STEPS; i++) {
        float t = (float(i) + noise) * stepLen;
        vec3 p = dir * t;
        float y = p.y + camY;
        float rho = mistDensityAt(y, m);
        float od = mistDepth(camY, y, t, m);
        float lit = shadowTap(p);
        litShare += lit;
        sum += rho * exp(-od) * lit * mistSunlight(y, a.lightDir.y, m) * stepLen;
    }
    litShare /= float(SHAFT_STEPS);
    // Past the shadow map, the mist is taken as lit about as much as the walked part was. Toward the open sky
    // only a little: the sky's own colour already holds the air's glow
    if (dist > marchEnd) {
        float od0 = mistDepth(camY, camY + dir.y * marchEnd, marchEnd, m);
        float od1 = mistDepth(camY, camY + dir.y * dist, dist, m);
        float yFar = camY + dir.y * mix(marchEnd, dist, 0.35);
        sum += (exp(-od0) - exp(-od1)) * mix(litShare, 1.0, 0.5) * mistSunlight(yFar, a.lightDir.y, m) * (sky ? 0.2 : 1.0);
    }
    float glow = hgPhase(cosT, 0.80) * PI;
#ifdef SUN_RAYS
    // Toward the light, the glow streams out between the trees and walls in front of it and is cut by them; near
    // ground is left to the walk above, which already knows what shades it
    float open = sunRays(uv, a, noise);
    float reach = sky ? 1.0 : smoothstep(0.5, 1.0, dist / max(marchEnd, 1.0));
    float mistiness = 1.0 - exp(-mistDensityAt(camY, m) * 220.0);
    phase = 0.10 + glow * 0.08 * mix(1.0, mix(0.3, 1.0, open), reach);
    scatter = a.direct * phase * sum;
    scatter += a.direct * glow * 0.05 * open * mistiness * reach;
#else
    scatter = a.direct * phase * sum;
#endif
    scatter *= sunCloud * SHAFT_STRENGTH;
    outShafts = vec4(scatter, dist);
#else
    outShafts = vec4(0.0, 0.0, 0.0, dist);
#endif
}
#endif
