// The grade: bloom laid in, the eye's adjustment, a filmic curve, then the dark fantasy colour: muted, olive
// rather than green, cold in the shade and warm in the light, heavier at the edges.
// Out: colortex0 as display colour (gamma), for FXAA in final.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/space.glsl"
#include "/lib/atmosphere.glsl"

#ifdef VSH
out vec2 texcoord;
flat out float exposure;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
    // How bright the scene around the eye is likely to be, from the hour and how open the sky is above
    Atmos a = atmosphere();
    float outside = pow(float(eyeBrightnessSmooth.y) / 240.0, 1.5);
    float open = luma(a.direct) * 0.35 + luma(a.ambient) * 1.4;
#if defined WORLD_NETHER
    open = 0.08;
    outside = 1.0;
#elif defined WORLD_END
    open = 0.10;
    outside = 1.0;
#endif
    float scene = mix(0.030, open, outside) + 0.004;
    float adapt = 1.0;
#ifdef AUTO_EXPOSURE
    // Only part of the way: nights and caves stay dark, just not blind
    adapt = clamp(pow(0.30 / scene, 0.38), 0.6, 1.6);
#endif
    exposure = EXPOSURE * adapt;
}
#endif

#ifdef FSH
uniform sampler2D colortex0;
uniform sampler2D colortex5;

in vec2 texcoord;
flat in float exposure;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

vec3 hable(vec3 x) {
    const float A = 0.22, B = 0.30, C = 0.10, D = 0.20, E = 0.01, F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

vec3 filmic(vec3 c) {
    const float white = 9.0;
    return hable(c * 2.2) / hable(vec3(white));
}

vec3 bloomAt(vec2 uv) {
    vec2 px = 1.0 / vec2(viewWidth, viewHeight);
    vec2 h = clamp(uv * 0.5, px, vec2(0.5) - px);
    return texture(colortex5, h).rgb;
}

void main() {
    vec2 uv = texcoord;
    vec3 color = texture(colortex0, uv).rgb;
#ifdef BLOOM
    vec3 bloom = bloomAt(uv);
    color += bloom * 0.22 * BLOOM_STRENGTH;
#endif
    color *= exposure;
    color = filmic(color);

    // Muted: less saturation overall, and Minecraft's bright greens pulled toward olive and moss
    float l = luma(color);
    float greenness = saturate((color.g - max(color.r, color.b)) / max(color.g, 1e-4) * 3.0);
    color = mix(vec3(l), color, SATURATION * (1.0 - greenness * GREEN_MUTE * 0.45));
    color.g = mix(color.g, (color.r + color.g) * 0.5, greenness * GREEN_MUTE * 0.25);

    // Cold shade, warm light
    float shadowAmt = 1.0 - smoothstep(0.0, 0.35, l);
    float lightAmt = smoothstep(0.35, 0.9, l);
    color *= mix(vec3(1.0), vec3(0.90, 0.98, 1.10), shadowAmt * 0.55 * SPLIT_TONE);
    color *= mix(vec3(1.0), vec3(1.04, 1.00, 0.93), lightAmt * 0.5 * SPLIT_TONE);

    color = toGamma(saturate(color));
    color = saturate((color - 0.5) * CONTRAST + 0.5 + (CONTRAST - 1.0) * 0.08);

    // Darker toward the edges, as if seen by firelight
    vec2 d = uv - 0.5;
    float v = 1.0 - dot(d, d) * 1.6 * VIGNETTE;
    color *= mix(1.0, smoothstep(0.0, 1.0, v), 0.9);
    outColor = vec4(color, 1.0);
}
#endif
