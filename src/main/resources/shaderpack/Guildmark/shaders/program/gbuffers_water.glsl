// Translucent terrain, drawn after deferred. Water leaves only its wave normal (composite does the rest);
// stained glass, ice and the like are lit here and laid over the world.
//   colortex4  normal (octahedral, world space), kind (0.5 + sky light / 2 for water, 0.25 for the rest), 1
//   colortex0  the lit colour, blended (nothing for water)

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"

#ifdef VSH
in vec4 mc_Entity;

out vec2 texcoord;
out vec2 lmcoord;
out vec4 tint;
out vec3 normal;
out vec3 playerPos;
flat out int blockId;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = lightLevels((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy);
    tint = gl_Color;
    normal = normalize(mat3(gbufferModelViewInverse) * normalize(gl_NormalMatrix * gl_Normal));
    vec3 view = (gl_ModelViewMatrix * gl_Vertex).xyz;
    playerPos = mat3(gbufferModelViewInverse) * view + gbufferModelViewInverse[3].xyz;
#ifdef PROGRAM_HAND_WATER
    blockId = 0;
#else
    blockId = int(mc_Entity.x + 0.5);
#endif
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
#include "/lib/water.glsl"

uniform sampler2D gtexture;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 tint;
in vec3 normal;
in vec3 playerPos;
flat in int blockId;

/* RENDERTARGETS: 4,0 */
layout(location = 0) out vec4 outWater;
layout(location = 1) out vec4 outColor;

void main() {
    vec3 n = normalize(normal);
    if (blockId == ID_WATER) {
        if (n.y > 0.5) n = waveNormal(playerPos.xz + cameraPosition.xz);
        if (!gl_FrontFacing) n = -n;
        outWater = vec4(encodeNormal(n), 0.5 + 0.5 * lmcoord.y, 1.0);
        outColor = vec4(0.0);
        return;
    }
    vec4 albedo = texture(gtexture, texcoord) * tint;
    if (albedo.a < 0.02) discard;
    Atmos a = atmosphere();
    Surface s;
    s.albedo = toLinear(albedo.rgb);
    s.normal = n;
    s.light = lmcoord;
    s.material = MAT_DEFAULT;
    s.emission = blockId == ID_MIST_GATE ? 0.55 : 0.0;
    s.sss = 0.0;
    s.wet = 0.0;
    s.playerPos = playerPos;
    vec3 viewDir = normalize(playerPos);
    vec3 vis = vec3(0.0);
#ifndef WORLD_NETHER
    float noise = dither(gl_FragCoord.xy, frameCounter);
    vis = sunVisibility(playerPos, n, dot(n, a.lightDir), false, noise, smoothstep(0.78, 0.96, lmcoord.y));
    vis *= cloudShadow(playerPos + cameraPosition, a);
#endif
    vec3 color = shade(s, a, viewDir, vis, 1.0);
    // Glass and ice mirror the sky a little
    float NdotV = saturate(dot(n, -viewDir));
    float fresnel = 0.04 + 0.96 * pow(1.0 - NdotV, 5.0);
    color += skyRadiance(reflect(viewDir, n), a, false) * fresnel * pow(lmcoord.y, 2.0) * 0.6;
    float alpha = blockId == ID_MIST_GATE ? albedo.a * 0.85 : mix(albedo.a, 1.0, fresnel * 0.5);
    outWater = vec4(encodeNormal(n), 0.25, 1.0);
    outColor = vec4(color, alpha);
}
#endif
