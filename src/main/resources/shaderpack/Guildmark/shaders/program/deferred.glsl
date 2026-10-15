// Deferred: lights everything the opaque passes left in the buffers, and paints the sky where they left nothing.
// Out: colortex0 as linear, unbounded light.

/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16;
const int colortex2Format = RGBA8;
const int colortex3Format = RGBA16F;
const int colortex4Format = RGBA16;
const int colortex5Format = RGBA16F;
const vec4 colortex0ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
const vec4 colortex1ClearColor = vec4(0.5, 0.5, 0.0, 0.0);
const vec4 colortex2ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex4ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/space.glsl"
#include "/lib/atmosphere.glsl"
#ifndef WORLD_NETHER
#include "/lib/shadows.glsl"
#endif
#include "/lib/lighting.glsl"

#ifdef VSH
out vec2 texcoord;
void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}
#endif

#ifdef FSH
uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;
#ifdef DISTANT_HORIZONS
uniform sampler2D dhDepthTex0;
uniform mat4 dhProjectionInverse;
#endif

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

#ifdef SSAO
/** Shade from nearby geometry the vertex shading misses: under plants, round entities, in tight corners. */
float ambientOcclusion(vec2 uv, vec3 viewPos, vec3 n, float noise) {
    vec3 t = normalize(abs(n.y) < 0.95 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    float radius = 0.7;
    float occluded = 0.0;
    const int SAMPLES = 8;
    for (int i = 0; i < SAMPLES; i++) {
        float r = sqrt((float(i) + 0.5) / float(SAMPLES));
        float th = float(i) * 2.39996323 + noise * TAU;
        vec2 d = r * vec2(cos(th), sin(th));
        float h = sqrt(max(1.0 - dot(d, d), 0.0));
        float scale = mix(0.25, 1.0, fract(noise + float(i) * 0.618));
        vec3 p = viewPos + (t * d.x + b * d.y + n * h) * radius * scale;
        vec3 s = viewToScreen(p);
        if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) continue;
        float sd = texture(depthtex0, s.xy).r;
        if (sd >= 1.0) continue;
        float sz = screenToView(s.xy, sd, gbufferProjectionInverse).z;
        float range = smoothstep(0.0, 1.0, radius / abs(viewPos.z - sz));
        occluded += step(p.z + 0.03, sz) * range;
    }
    float ao = 1.0 - occluded / float(SAMPLES);
    return pow(ao, 1.5 * SSAO_STRENGTH);
}
#endif

void main() {
    vec2 uv = texcoord;
    float depth = texture(depthtex0, uv).r;
    Atmos a = atmosphere();
    bool dh = false;
    vec3 viewPos = vec3(0.0);
    if (depth >= 1.0) {
#ifdef DISTANT_HORIZONS
        float dhDepth = texture(dhDepthTex0, uv).r;
        if (dhDepth < 1.0) {
            dh = true;
            viewPos = screenToView(uv, dhDepth, dhProjectionInverse);
        }
#endif
        if (!dh) {
            vec3 dir = normalize(mat3(gbufferModelViewInverse) * screenToView(uv, 1.0, gbufferProjectionInverse));
            outColor = vec4(skyRadiance(dir, a, true), 1.0);
            return;
        }
    } else {
        viewPos = screenToView(uv, depth, gbufferProjectionInverse);
    }

    vec4 c0 = texture(colortex0, uv);
    vec4 c1 = texture(colortex1, uv);
    vec4 c2 = texture(colortex2, uv);
    int material = int(c2.r * 255.0 + 0.5);
    if (material == MAT_UNLIT) {
        outColor = vec4(c0.rgb, 1.0);
        return;
    }

    Surface s;
    s.albedo = toLinear(c0.rgb);
    s.normal = decodeNormal(c1.xy);
    s.light = c1.zw;
    s.material = material;
    s.emission = c2.g;
    s.sss = c2.b;
    s.wet = c2.a;
    s.playerPos = viewToPlayer(viewPos);
    // Soaked ground and stone go darker
    s.albedo *= mix(1.0, 0.58, s.wet * (material == MAT_METAL ? 0.0 : 1.0));

    vec3 viewDir = normalize(s.playerPos);
    float noise = dither(gl_FragCoord.xy, frameCounter);
    float NdotL = dot(s.normal, a.lightDir);
    // Past the shadow map's edge (and in the far lands), guess from how open to the sky it is
    float fallback = smoothstep(0.78, 0.96, s.light.y);
    vec3 visibility = vec3(0.0);
#ifndef WORLD_NETHER
    if (max(a.direct.r, max(a.direct.g, a.direct.b)) > 1e-5) {
        if (material == MAT_HAND) {
            visibility = sunVisibility(vec3(0.0, -0.4, 0.0), vec3(0.0, 1.0, 0.0), 1.0, true, noise, fallback);
        } else if (dh) {
            visibility = vec3(fallback);
        } else if (NdotL > -0.02 || s.sss > 0.0) {
            visibility = sunVisibility(s.playerPos, s.normal, NdotL, s.sss > 0.0, noise, fallback);
        }
        visibility *= cloudShadow(s.playerPos + cameraPosition, a);
    }
#endif

    float occlusion = 1.0;
#ifdef SSAO
    if (!dh && material != MAT_HAND) {
        occlusion = ambientOcclusion(uv, viewPos, normalize(mat3(gbufferModelView) * s.normal), noise);
    }
#endif

    outColor = vec4(shade(s, a, viewDir, visibility, occlusion), 1.0);
}
#endif
