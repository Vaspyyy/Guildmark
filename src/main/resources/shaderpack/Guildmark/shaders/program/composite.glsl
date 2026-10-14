// Water: what lies below, bent by the waves and swallowed by the murk, and on top the sky and shore mirrored.
// gbuffers_water (and dh_water) left the surface's normal in colortex4 and nothing in colortex0, so colortex0
// still holds the lit world under the surface.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/space.glsl"
#include "/lib/atmosphere.glsl"
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
uniform sampler2D colortex0;
uniform sampler2D colortex4;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
#ifdef DISTANT_HORIZONS
uniform sampler2D dhDepthTex0;
uniform sampler2D dhDepthTex1;
uniform mat4 dhProjectionInverse;
#endif

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

/** A ray along view-space dir from p, walked across the screen against the opaque depth. Hit: uv in xy, z = 1. */
vec3 traceScreen(vec3 p, vec3 dir, float noise) {
    float stepLen = 0.35 + 0.25 * noise;
    vec3 pos = p;
    vec3 last = p;
    for (int i = 0; i < 24; i++) {
        last = pos;
        pos += dir * stepLen;
        stepLen *= 1.28;
        vec3 s = viewToScreen(pos);
        if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0 || s.z > 1.0 || pos.z > -near) return vec3(0.0);
        float d = texture(depthtex1, s.xy).r;
        if (d >= 1.0) continue;
        float sceneZ = screenToView(s.xy, d, gbufferProjectionInverse).z;
        float gap = sceneZ - pos.z;
        if (gap > 0.0 && gap < stepLen * 2.2 + 0.2) {
            // Narrow down to the crossing
            vec3 lo = last;
            vec3 hi = pos;
            for (int j = 0; j < 5; j++) {
                vec3 mid = (lo + hi) * 0.5;
                vec3 ms = viewToScreen(mid);
                float md = texture(depthtex1, ms.xy).r;
                float mz = screenToView(ms.xy, md, gbufferProjectionInverse).z;
                if (mz > mid.z) hi = mid;
                else lo = mid;
            }
            return vec3(viewToScreen(hi).xy, 1.0);
        }
    }
    return vec3(0.0);
}

void main() {
    vec2 uv = texcoord;
    vec4 w = texture(colortex4, uv);
    vec3 color = texture(colortex0, uv).rgb;
    if (w.z < 0.45) {
        outColor = vec4(color, 1.0);
        return;
    }
    float skyLight = saturate((w.z - 0.5) * 2.0);

    float d0 = texture(depthtex0, uv).r;
    float d1 = texture(depthtex1, uv).r;
    mat4 projInv = gbufferProjectionInverse;
    bool far0 = false;
#ifdef DISTANT_HORIZONS
    if (d0 >= 1.0) {
        d0 = texture(dhDepthTex0, uv).r;
        d1 = texture(dhDepthTex1, uv).r;
        projInv = dhProjectionInverse;
        far0 = true;
    }
#endif
    vec3 v0 = screenToView(uv, d0, projInv);
    vec3 p0 = viewToPlayer(v0);
    vec3 viewDir = normalize(p0);
    vec3 n = decodeNormal(w.xy);
    vec3 nv = normalize(mat3(gbufferModelView) * n);
    Atmos a = atmosphere();
    float noise = dither(gl_FragCoord.xy, frameCounter);
    bool under = isEyeInWater == 1;

    // Below the surface: bend the view by the waves, unless that would pull in something in front of the water
    float dist0 = length(v0);
    vec2 bend = nv.xy * 0.035 / (1.0 + dist0 * 0.04);
    vec2 refrUv = clamp(uv + bend, vec2(0.001), vec2(0.999));
    float dr = texture(depthtex1, refrUv).r;
    if (!far0 && dr < d0) {
        refrUv = uv;
        dr = d1;
    }
    if (far0) dr = d1;
    vec3 below = texture(colortex0, refrUv).rgb;
    vec3 v1 = screenToView(refrUv, dr, far0 ? projInv : gbufferProjectionInverse);
    float depthBelow = dr >= 1.0 ? 64.0 : max(length(v1) - dist0, 0.0);
    vec3 transmit = exp(-waterAbsorption() * depthBelow);
    vec3 murk = waterScatter(a, skyLight);
    vec3 refracted = under ? below : below * transmit + murk * (1.0 - transmit);

    // The surface mirrors the sky, and the shore where the screen shows it
    float NdotV = saturate(dot(n, -viewDir));
    float fresnel = 0.02 + 0.98 * pow(1.0 - NdotV, 5.0);
    if (under) fresnel = NdotV < 0.66 ? 1.0 : 0.05;
    vec3 r = reflect(viewDir, n);
    if (r.y < 0.0 && !under) r.y = -r.y * 0.3;
    vec3 reflection = skyRadiance(normalize(r), a, true) * mix(0.08, 1.0, pow(skyLight, 2.0));
    if (under) reflection = murk;
#ifdef WATER_REFLECTIONS
    if (!far0 && !under) {
        vec3 rv = reflect(normalize(v0), nv);
        vec3 hit = traceScreen(v0, rv, noise);
        if (hit.z > 0.5) {
            vec2 edge = smoothstep(0.0, 0.08, hit.xy) * smoothstep(1.0, 0.92, hit.xy);
            reflection = mix(reflection, texture(colortex0, hit.xy).rgb, edge.x * edge.y);
        }
    }
#endif
    vec3 water = mix(refracted, reflection, fresnel);

    // The sun's glint, where the water isn't in shadow
#ifndef WORLD_NETHER
    if (!under && max(a.direct.r, a.direct.g) > 1e-5) {
        vec3 h = normalize(a.lightDir - viewDir);
        float NdotH = saturate(dot(n, h));
        float rough = 0.045;
        float a2 = rough * rough;
        float dd = NdotH * NdotH * (a2 - 1.0) + 1.0;
        float spec = a2 / (PI * dd * dd) * saturate(dot(n, a.lightDir));
        vec3 vis = far0 ? vec3(smoothstep(0.8, 0.97, skyLight)) : vec3(shadowTap(p0 + n * 0.05));
        vis *= cloudShadow(p0 + cameraPosition, a);
        water += a.direct * vis * min(spec, 60.0) * fresnel * 0.9;
    }
#endif
    outColor = vec4(water, 1.0);
}
#endif
