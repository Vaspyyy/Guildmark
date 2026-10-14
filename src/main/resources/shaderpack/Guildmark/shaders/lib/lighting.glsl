// Lighting one surface: the sun or moon, the sky's fill, the warm light of fires, and what glows of itself.
// Shared by deferred (most of the world) and the programs drawn after it (water, glass, particles, rain).

struct Surface {
    vec3 albedo;    // linear
    vec3 normal;    // world space
    vec2 light;     // block light, sky light: 0..1
    int material;
    float emission; // 0..1
    float sss;      // how much light passes through (leaves, grass)
    float wet;      // 0..1, rain on it
    vec3 playerPos; // camera-relative world position
};

const vec3 FIRE_COLOR = vec3(1.0, 0.50, 0.19);

/** Fire light from the lightmap: bright near the flame, falling off fast, so torches leave pools of light. */
vec3 blockLight(float level, vec3 worldPos) {
    float d = (1.0 - level) * 15.0;
    float intensity = 2.2 / (1.0 + 0.45 * d * d) * smoothstep(0.0, 0.22, level);
#ifdef TORCH_FLICKER
    float t = frameTimeCounter;
    vec3 p = floor(worldPos * 0.25);
    float flicker = 1.0 + 0.07 * sin(t * 11.0 + hash13(p) * 40.0) + 0.05 * sin(t * 17.3 + hash13(p + 3.0) * 20.0);
    intensity *= flicker;
#endif
    return FIRE_COLOR * intensity * TORCH_STRENGTH;
}

/** A light carried in hand: a torch held up lights what's around the camera. */
vec3 heldLight(vec3 playerPos, vec3 normal) {
#ifdef HELD_LIGHT
    float level = float(max(heldBlockLightValue, heldBlockLightValue2));
    if (level <= 0.0) return vec3(0.0);
    float dist = length(playerPos);
    float reach = level;
    float intensity = level / 15.0 * 1.2 / (1.0 + 0.22 * dist * dist) * smoothstep(reach, reach * 0.5, dist);
    float facing = dot(normal, -playerPos / max(dist, 1e-3)) * 0.65 + 0.35;
    return FIRE_COLOR * intensity * max(facing, 0.0) * TORCH_STRENGTH;
#else
    return vec3(0.0);
#endif
}

/** The least light anywhere: cave dark, never quite black. */
vec3 minimumLight() {
    vec3 floorLight = vec3(0.0030, 0.0034, 0.0046) * exp2(-(CAVE_DARKNESS - 1.0) * 1.5);
    return floorLight + vec3(0.10, 0.11, 0.12) * nightVision;
}

float ggx(float NdotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

/**
 * The lit colour of s. visibility is the sun's reach (sunVisibility, already times the cloud shadow),
 * occlusion the corner shading on top of the vertex ambient occlusion in albedo.
 */
vec3 shade(Surface s, Atmos a, vec3 viewDir, vec3 visibility, float occlusion) {
    vec3 n = s.normal;
    vec3 l = a.lightDir;
    float NdotL = dot(n, l);
    float diffuse = saturate(NdotL);
    if (s.material == MAT_LEAVES) diffuse = saturate((NdotL + 0.45) / 1.45);
    if (s.material == MAT_PLANT) diffuse = 0.55 + 0.45 * saturate(abs(NdotL));

    vec3 direct = a.direct * diffuse * visibility;
#ifdef FOLIAGE_SSS
    if (s.sss > 0.0) {
        float through = hgPhase(dot(viewDir, l), 0.55) * 2.2 + 0.25;
        direct += a.direct * visibility * s.sss * through * 0.55 * (1.0 - diffuse * 0.5);
    }
#endif
    // Fill light from the open sky: it fades fast toward the inside of buildings and caves
    float skyReach = pow(s.light.y, 2.4);
    float facingUp = 0.68 + 0.32 * n.y;
    vec3 ambient = a.ambient * skyReach * facingUp * occlusion;
    vec3 fire = blockLight(s.light.x, s.playerPos + cameraPosition) * mix(0.55, 1.0, occlusion);
    fire += heldLight(s.playerPos, n);
    vec3 light = direct + ambient + fire + minimumLight() * occlusion;

    vec3 color = s.albedo * light;

    // Wet stone and soil shine under the sun and mirror the sky a little
    if (s.wet > 0.01) {
        vec3 v = -viewDir;
        vec3 h = normalize(v + l);
        float NdotH = saturate(dot(n, h));
        float NdotV = saturate(dot(n, v));
        float rough = mix(0.45, 0.10, s.wet);
        float fres = 0.03 + 0.97 * pow(1.0 - NdotV, 5.0);
        float spec = ggx(NdotH, rough) * diffuse * 0.25;
        color += a.direct * visibility * spec * fres * s.wet * 4.0;
        vec3 r = reflect(viewDir, n);
        color += skyRadiance(r, a, false) * fres * s.wet * skyReach * 0.85;
    }

    color += s.albedo * s.emission * 4.0 * EMISSIVE_STRENGTH;
    return color;
}
