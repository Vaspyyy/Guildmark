// Things lit as they're drawn and laid straight over the lit world: particles, rain and snow, lines and the
// odd textured overlay. Out: colortex0, blended, in linear light.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"

#ifdef VSH
out vec2 texcoord;
out vec2 lmcoord;
out vec4 tint;
out vec3 playerPos;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = lightLevels((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy);
    tint = gl_Color;
    vec3 view = (gl_ModelViewMatrix * gl_Vertex).xyz;
    playerPos = mat3(gbufferModelViewInverse) * view + gbufferModelViewInverse[3].xyz;
    gl_Position = gl_ProjectionMatrix * vec4(view, 1.0);
}
#endif

#ifdef FSH
#include "/lib/space.glsl"
#include "/lib/atmosphere.glsl"
#ifndef WORLD_NETHER
#include "/lib/shadows.glsl"
#endif
#include "/lib/lighting.glsl"

uniform sampler2D gtexture;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 tint;
in vec3 playerPos;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
#ifdef PROGRAM_BASIC
    vec4 albedo = tint;
#else
    vec4 albedo = texture(gtexture, texcoord) * tint;
#endif
    if (albedo.a < 0.01) discard;
    Atmos a = atmosphere();

#if defined PROGRAM_BASIC
    // Outlines, leads and the like: their own colour, a touch lit
    outColor = vec4(toLinear(albedo.rgb) * (a.ambient * 2.0 + a.direct * 0.2 + 0.02), albedo.a);
#elif defined PROGRAM_WEATHER
    vec3 fire = blockLight(lmcoord.x, playerPos + cameraPosition) * 0.6;
    if (albedo.b > albedo.r * 1.4) {
        // Rain: grey streaks a little brighter than the sky behind them, catching firelight near torches
        float streak = luma(albedo.rgb);
        vec3 c = (a.horizon * 1.15 + a.ambient * 0.5) * (0.75 + 0.5 * streak) + fire * 0.25 + 0.003;
        outColor = vec4(c, albedo.a * 0.30);
    } else {
        // Snow, lit like snow
        vec3 lit = a.ambient * 1.6 + a.direct * 0.20 + fire + 0.004;
        outColor = vec4(toLinear(albedo.rgb) * lit, albedo.a * 0.75);
    }
#else
    if (albedo.a < 0.1) discard;
    Surface s;
    s.albedo = toLinear(albedo.rgb);
    s.normal = -normalize(playerPos);
    s.light = lmcoord;
    // Lit from any side, like a card of grass
    s.material = MAT_PLANT;
    // Embers, flames and sparks are drawn at full brightness: let them glow
    s.emission = lmcoord.x > 0.995 ? smoothstep(0.35, 0.85, max(albedo.r, max(albedo.g, albedo.b))) * 0.8 : 0.0;
    s.sss = 0.0;
    s.wet = 0.0;
    s.playerPos = playerPos;
    vec3 vis = vec3(0.0);
#ifndef WORLD_NETHER
    vis = vec3(shadowTap(playerPos)) * cloudShadow(playerPos + cameraPosition, a);
#endif
    vec3 c = shade(s, a, normalize(playerPos), vis * 0.8, 1.0);
    outColor = vec4(c, albedo.a);
#endif
}
#endif
