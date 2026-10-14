// Bloom, at half size (scale.composite3): a soft glow gathered from colortex0's smaller copies, so flames,
// the low sun and bright mist bleed light into the dark around them.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"

#ifdef VSH
out vec2 texcoord;
void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}
#endif

#ifdef FSH
const bool colortex0MipmapEnabled = true;
uniform sampler2D colortex0;

in vec2 texcoord;

/* RENDERTARGETS: 5 */
layout(location = 0) out vec4 outBloom;

vec3 tent(vec2 uv, float lod) {
    vec2 px = exp2(lod) / vec2(viewWidth, viewHeight);
    vec3 c = textureLod(colortex0, uv, lod).rgb * 4.0;
    c += textureLod(colortex0, uv + vec2(px.x, 0.0), lod).rgb * 2.0;
    c += textureLod(colortex0, uv - vec2(px.x, 0.0), lod).rgb * 2.0;
    c += textureLod(colortex0, uv + vec2(0.0, px.y), lod).rgb * 2.0;
    c += textureLod(colortex0, uv - vec2(0.0, px.y), lod).rgb * 2.0;
    c += textureLod(colortex0, uv + px, lod).rgb;
    c += textureLod(colortex0, uv - px, lod).rgb;
    c += textureLod(colortex0, uv + vec2(px.x, -px.y), lod).rgb;
    c += textureLod(colortex0, uv + vec2(-px.x, px.y), lod).rgb;
    return c / 16.0;
}

void main() {
#ifdef BLOOM
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    float w = 1.0;
    for (int lod = 2; lod <= 7; lod++) {
        vec3 c = tent(texcoord, float(lod));
        // Bright parts carry it; plain daylight hardly glows
        float l = luma(c);
        c *= l / (l + 0.6);
        sum += c * w;
        weights += w;
        w *= 0.82;
    }
    outBloom = vec4(sum / weights, 1.0);
#else
    outBloom = vec4(0.0);
#endif
}
#endif
