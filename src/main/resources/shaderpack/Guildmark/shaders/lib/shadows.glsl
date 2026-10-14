// The shadow map: drawn distorted (sharp near the player, coarse at the edge) and read back with soft,
// contact-hardening filtering. shadowtex1 holds only opaque casters; shadowtex0 adds the translucent ones,
// whose colour shadowcolor0 keeps.

uniform sampler2D shadowtex0;
uniform sampler2D shadowtex1;
uniform sampler2D shadowcolor0;

#define SHADOW_DISTORT 0.85
#define SHADOW_DEPTH_SCALE 0.25

float shadowDistortFactor(vec2 clipXY) {
    return length(clipXY) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
}

vec3 distortShadow(vec3 clip) {
    return vec3(clip.xy / shadowDistortFactor(clip.xy), clip.z * SHADOW_DEPTH_SCALE);
}

/** Shadow clip space (undistorted) of a player-space position. */
vec3 shadowClip(vec3 playerPos) {
    vec4 v = shadowModelView * vec4(playerPos, 1.0);
    return (shadowProjection * v).xyz;
}

/** One manual bilinear comparison: how much of the light at uv reaches depth z, 0..1. */
float shadowCompare(sampler2D tex, vec2 uv, float z) {
    vec2 p = uv * float(shadowMapResolution) - 0.5;
    vec2 i = floor(p);
    vec2 f = p - i;
    ivec2 c = clamp(ivec2(i), ivec2(0), ivec2(shadowMapResolution - 2));
    float s00 = step(z, texelFetch(tex, c, 0).r);
    float s10 = step(z, texelFetch(tex, c + ivec2(1, 0), 0).r);
    float s01 = step(z, texelFetch(tex, c + ivec2(0, 1), 0).r);
    float s11 = step(z, texelFetch(tex, c + ivec2(1, 1), 0).r);
    return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
}

vec2 vogelDisk(int i, int n, float phi) {
    float r = sqrt((float(i) + 0.5) / float(n));
    float theta = float(i) * 2.39996323 + phi * TAU;
    return r * vec2(cos(theta), sin(theta));
}

#if SHADOW_FILTER == 1
#define SHADOW_TAPS 6
#elif SHADOW_FILTER == 3
#define SHADOW_TAPS 16
#else
#define SHADOW_TAPS 10
#endif

/**
 * How much of the sun (or moon) reaches a surface, with its colour after stained glass or water.
 * normal is the surface's world normal; fallback stands in past the edge of the map (a guess from sky light).
 */
vec3 sunVisibility(vec3 playerPos, vec3 normal, float NdotL, bool thin, float noise, float fallback) {
    vec3 clip = shadowClip(playerPos);
    float edge = max(abs(clip.x), abs(clip.y));
    if (edge >= 1.0 || clip.z <= -1.0 || clip.z >= 1.0) return vec3(fallback);
    float f = shadowDistortFactor(clip.xy);
    // Push the lookup off the surface by about a shadow texel, more where the light grazes it
    float texel = 2.0 * shadowDistance / float(shadowMapResolution) * f * f / (1.0 - SHADOW_DISTORT);
    vec3 offset = thin ? vec3(0.0) : normal * texel * (0.9 + 1.4 * (1.0 - saturate(abs(NdotL))));
    clip = shadowClip(playerPos + offset);
    f = shadowDistortFactor(clip.xy);
    vec3 sc = vec3(clip.xy / f, clip.z * SHADOW_DEPTH_SCALE) * 0.5 + 0.5;
    float z = sc.z - (thin ? 0.0006 : 0.00004);

    // How far the casters stand off: near ones give hard edges, far ones soft
    float uvPerBlock = (1.0 - SHADOW_DISTORT) / (2.0 * shadowDistance * f * f);
    float searchRadius = 3.0 * uvPerBlock * SHADOW_SOFTNESS;
    float blockers = 0.0;
    float blockerDepth = 0.0;
    for (int i = 0; i < 6; i++) {
        vec2 o = vogelDisk(i, 6, noise) * searchRadius;
        float d = texture(shadowtex0, sc.xy + o).r;
        if (d < z) {
            blockers += 1.0;
            blockerDepth += d;
        }
    }
    float minRadius = 1.0 / float(shadowMapResolution);
    float radius = minRadius;
    if (blockers > 0.0) {
        float gap = (z - blockerDepth / blockers) / (SHADOW_DEPTH_SCALE * 0.5) * 128.0;
        radius = clamp(gap * 0.035 * SHADOW_SOFTNESS * uvPerBlock, minRadius, searchRadius * 1.5);
    }

    float lit = 0.0;
    for (int i = 0; i < SHADOW_TAPS; i++) {
        vec2 o = vogelDisk(i, SHADOW_TAPS, noise) * radius;
        lit += shadowCompare(shadowtex1, sc.xy + o, z);
    }
    lit /= float(SHADOW_TAPS);

    vec3 vis = vec3(lit);
#ifdef COLORED_SHADOWS
    // Light that only stained glass or water stood in front of takes their colour: the nearest caster here is
    // translucent when the opaque-only map has nothing as near
    float d0 = texture(shadowtex0, sc.xy).r;
    float d1 = texture(shadowtex1, sc.xy).r;
    if (lit > 0.0 && d0 < z && d0 < d1 - 0.0004) {
        vec4 tint = texture(shadowcolor0, sc.xy);
        vis *= toLinear(tint.rgb);
    }
#endif
    float fade = smoothstep(0.86, 0.98, edge);
    return mix(vis, vec3(fallback), fade);
}

/** One cheap tap, opaque casters only, for light shafts: 1 lit, 0 in shadow. */
float shadowTap(vec3 playerPos) {
    vec3 clip = shadowClip(playerPos);
    if (max(abs(clip.x), abs(clip.y)) >= 1.0) return 1.0;
    vec3 sc = distortShadow(clip) * 0.5 + 0.5;
    return step(sc.z - 0.0002, texture(shadowtex1, sc.xy).r);
}
