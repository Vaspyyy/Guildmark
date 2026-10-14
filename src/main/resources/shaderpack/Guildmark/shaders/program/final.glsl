// Final: smooths jagged edges (FXAA) and lays a fine film grain over the picture.

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
uniform sampler2D colortex0;

in vec2 texcoord;

layout(location = 0) out vec4 outColor;

#ifdef FXAA
float lumaAt(vec2 uv) { return luma(texture(colortex0, uv).rgb); }

/** A compact FXAA: find the edge's direction from the corners, blend along it. */
vec3 fxaa(vec2 uv) {
    vec2 px = 1.0 / vec2(viewWidth, viewHeight);
    vec3 rgbM = texture(colortex0, uv).rgb;
    float lM = luma(rgbM);
    float lNW = lumaAt(uv + vec2(-1.0, -1.0) * px);
    float lNE = lumaAt(uv + vec2(1.0, -1.0) * px);
    float lSW = lumaAt(uv + vec2(-1.0, 1.0) * px);
    float lSE = lumaAt(uv + vec2(1.0, 1.0) * px);
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    if (lMax - lMin < max(0.0312, lMax * 0.125)) return rgbM;
    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));
    float reduce = max((lNW + lNE + lSW + lSE) * 0.25 * 0.125, 1.0 / 128.0);
    float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcp, vec2(-8.0), vec2(8.0)) * px;
    vec3 a = 0.5 * (texture(colortex0, uv + dir * (1.0 / 3.0 - 0.5)).rgb + texture(colortex0, uv + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 b = a * 0.5 + 0.25 * (texture(colortex0, uv - dir * 0.5).rgb + texture(colortex0, uv + dir * 0.5).rgb);
    float lB = luma(b);
    return (lB < lMin || lB > lMax) ? a : b;
}
#endif

void main() {
#ifdef FXAA
    vec3 color = fxaa(texcoord);
#else
    vec3 color = texture(colortex0, texcoord).rgb;
#endif
    // Grain, strongest in the mid-tones, never on pure black or white
    float g = hash12(gl_FragCoord.xy + fract(frameTimeCounter * 7.13) * 911.0) - 0.5;
    float l = luma(color);
    color += g * 0.045 * FILM_GRAIN * (1.0 - abs(l * 2.0 - 1.0));
    outColor = vec4(color, 1.0);
}
#endif
