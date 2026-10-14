// The shadow pass: everything that casts a shadow, seen from the sun (or moon), distorted so the land near the
// player gets most of the map. Stained glass and water leave their colour in shadowcolor0.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/shadows.glsl"

#ifdef VSH
#include "/lib/wind.glsl"

in vec4 mc_Entity;
in vec2 mc_midTexCoord;

out vec2 texcoord;
out vec4 tint;
out vec3 worldPos;
flat out int blockId;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    tint = gl_Color;
    blockId = int(mc_Entity.x + 0.5);
    vec3 view = (gl_ModelViewMatrix * gl_Vertex).xyz;
    vec3 player = mat3(shadowModelViewInverse) * view + shadowModelViewInverse[3].xyz;
    player += waving(blockId, player + cameraPosition, gl_MultiTexCoord0.xy, mc_midTexCoord);
    worldPos = player + cameraPosition;
    vec4 clip = gl_ProjectionMatrix * (shadowModelView * vec4(player, 1.0));
    clip.xyz = distortShadow(clip.xyz);
    gl_Position = clip;
}
#endif

#ifdef FSH
uniform sampler2D gtexture;

in vec2 texcoord;
in vec4 tint;
in vec3 worldPos;
flat in int blockId;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
    if (blockId == ID_WATER) {
        // Light through water: tinted, rippling where the waves gather it
        vec2 p = worldPos.xz * 0.55 + frameTimeCounter * vec2(0.22, 0.13);
        float ridge = 1.0 - abs(noise2(p) * 2.0 - 1.0);
        float ridge2 = 1.0 - abs(noise2(p * 1.9 + 3.7 - frameTimeCounter * 0.17) * 2.0 - 1.0);
        float caustic = pow(ridge * ridge2, 3.0);
        outColor = vec4(vec3(0.62, 0.86, 0.80) * (0.62 + 0.38 * caustic), 1.0);
        return;
    }
    vec4 albedo = texture(gtexture, texcoord) * tint;
    if (albedo.a < 0.1) discard;
    outColor = albedo;
}
#endif
