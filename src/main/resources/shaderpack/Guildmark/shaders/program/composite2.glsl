// The mist over everything: how much of the world shows through, the sky's light the mist itself gives off,
// and the light shafts from composite1 brought back up to full size. Under water, lava and snow get their own.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/space.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/fog.glsl"
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
uniform sampler2D colortex3;
uniform sampler2D depthtex0;
#ifdef DISTANT_HORIZONS
uniform sampler2D dhDepthTex0;
uniform mat4 dhProjectionInverse;
#endif

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

/** composite1's half-size shafts, taken from the neighbours that lie at about this pixel's distance. */
vec3 upsampleShafts(vec2 uv, float dist) {
    vec2 halfSize = vec2(viewWidth, viewHeight) * 0.5;
    vec2 p = uv * halfSize - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 cell = clamp(base + vec2(x, y), vec2(0.0), halfSize - 1.0);
            vec4 s = texelFetch(colortex3, ivec2(cell), 0);
            float bilinear = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
            float similar = 1.0 / (1e-3 + abs(s.a - dist) / max(dist, 1.0) * 20.0);
            float wgt = bilinear * similar + 1e-5;
            sum += s.rgb * wgt;
            weights += wgt;
        }
    }
    return sum / weights;
}

void main() {
    vec2 uv = texcoord;
    vec3 color = texture(colortex0, uv).rgb;
    float depth = texture(depthtex0, uv).r;
    bool sky = depth >= 1.0;
    vec3 view;
    if (sky) {
        view = screenToView(uv, 1.0, gbufferProjectionInverse);
#ifdef DISTANT_HORIZONS
        float dd = texture(dhDepthTex0, uv).r;
        if (dd < 1.0) {
            sky = false;
            view = screenToView(uv, dd, dhProjectionInverse);
        }
#endif
    } else {
        view = screenToView(uv, depth, gbufferProjectionInverse);
    }
    vec3 player = viewToPlayer(view);
    float dist = sky ? 600.0 : length(player);
    vec3 dir = normalize(player);
    Atmos a = atmosphere();

    // The hand is never in the mist
    if (depth < 0.56) {
        outColor = vec4(color, 1.0);
        return;
    }

    vec3 shafts = upsampleShafts(uv, dist);

    if (isEyeInWater == 1) {
        float sky0 = float(eyeBrightnessSmooth.y) / 240.0;
        vec3 t = exp(-waterAbsorption() * min(dist, 96.0) * 0.9);
        color = color * t + waterScatter(a, sky0) * (1.0 - t) + shafts;
    } else if (isEyeInWater == 2) {
        float t = exp(-dist * 1.4);
        color = mix(vec3(1.0, 0.30, 0.04) * 1.5, color, t);
    } else if (isEyeInWater == 3) {
        float t = exp(-dist * 0.9);
        color = mix(vec3(0.55, 0.62, 0.70) * (a.ambient * 2.0 + 0.02), color, t);
    } else {
        Mist m = mistAmounts(a);
        float camY = cameraPosition.y;
        // The sky's colour already holds the far air's haze: over it, only the mist within sight
        float reach = sky ? far : dist;
        float od = mistDepth(camY, camY + dir.y * reach, reach, m);
        float t = exp(-od);
        // The mist glows with the sky's light toward the horizon in this direction, a little dimmer than the sky
        // itself, so far hills stand grey against it
        vec3 haze = mix(a.mist, skyRadiance(normalize(vec3(dir.x, max(dir.y, 0.0) * 0.35, dir.z)), a, false) * 0.85, 0.45);
#if defined WORLD_NETHER || defined WORLD_END
        haze = a.mist;
#endif
        color = color * t + haze * (1.0 - t) + shafts;
    }
    if (blindness > 0.0 || darknessFactor > 0.0) {
        float closeIn = exp(-dist * (blindness * 0.35 + darknessFactor * 0.06));
        color *= closeIn;
    }
    outColor = vec4(color, 1.0);
}
#endif
