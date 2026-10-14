// Opaque things drawn before deferred: terrain, entities, block entities, the hand, and a few overlays.
// They write what deferred lights them from: colour, normal and light levels, and what they're made of.
//   colortex0  albedo (as the texture has it)
//   colortex1  normal (octahedral, world space), block light, sky light
//   colortex2  material id / 255, emission, light passing through, wetness

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"

#ifdef VSH

#ifdef PROGRAM_TERRAIN
#include "/lib/wind.glsl"
in vec4 mc_Entity;
in vec2 mc_midTexCoord;
#endif

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
#ifdef PROGRAM_TERRAIN
    blockId = int(mc_Entity.x + 0.5);
    playerPos += waving(blockId, playerPos + cameraPosition, gl_MultiTexCoord0.xy, mc_midTexCoord);
    gl_Position = gl_ProjectionMatrix * (gbufferModelView * vec4(playerPos, 1.0));
#else
    blockId = 0;
    gl_Position = gl_ProjectionMatrix * vec4(view, 1.0);
#endif
}

#endif

#ifdef FSH

uniform sampler2D gtexture;
uniform vec4 entityColor;
uniform int blockEntityId;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 tint;
in vec3 normal;
in vec3 playerPos;
flat in int blockId;

#if defined PROGRAM_DAMAGED || defined PROGRAM_GLINT
/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outAlbedo;
#else
/* RENDERTARGETS: 0,1,2 */
layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outNormalLight;
layout(location = 2) out vec4 outMaterial;
#endif

void main() {
#ifdef PROGRAM_LIGHTNING
    vec4 albedo = tint;
#else
    vec4 albedo = texture(gtexture, texcoord) * tint;
#endif

#if defined PROGRAM_DAMAGED
    outAlbedo = albedo;
#elif defined PROGRAM_GLINT
    outAlbedo = vec4(albedo.rgb * 0.55, 0.0);
#else
    if (albedo.a < 0.1) discard;
    int material = MAT_DEFAULT;
    float emission = 0.0;
    float sss = 0.0;
    float wet = 0.0;
    vec3 n = normalize(normal);

#ifdef PROGRAM_TERRAIN
    int id = blockId;
    float bright = max(albedo.r, max(albedo.g, albedo.b));
    float chroma = bright - min(albedo.r, min(albedo.g, albedo.b));
    if (id == ID_LEAVES) {
        material = MAT_LEAVES;
        sss = 0.75;
    } else if (id == ID_PLANT || id == ID_TALL_LOWER || id == ID_TALL_UPPER || id == ID_HANGING) {
        material = MAT_PLANT;
        sss = 0.55;
        // Cards face every way: light them as if they faced up
        n = vec3(0.0, 1.0, 0.0);
    } else if (id == ID_TORCH) {
        material = MAT_TORCH;
        emission = smoothstep(0.62, 0.90, bright) * smoothstep(0.08, 0.30, chroma + 0.1);
    } else if (id == ID_GLOW) {
        material = MAT_GLOW;
        emission = 0.35 + 0.65 * smoothstep(0.30, 0.85, bright);
    } else if (id == ID_LAVA) {
        material = MAT_LAVA;
        emission = 0.7 + 0.3 * smoothstep(0.4, 0.9, bright);
    } else if (id == ID_WARDFIRE) {
        material = MAT_WARDFIRE;
        emission = smoothstep(0.55, 0.85, bright) * smoothstep(0.18, 0.40, chroma);
    } else if (id == ID_CRYSTAL) {
        material = MAT_CRYSTAL;
        emission = smoothstep(0.30, 0.85, bright) * 0.75;
    } else if (id == ID_SNOW) {
        material = MAT_SNOW;
    } else if (id == ID_METAL) {
        material = MAT_METAL;
    }
#ifdef RAIN_WETNESS
    if (wetness > 0.01 && material != MAT_LEAVES && material != MAT_PLANT && emission < 0.01) {
        float exposed = smoothstep(0.86, 0.97, lmcoord.y);
        vec2 wp = (playerPos + cameraPosition).xz;
        float puddle = smoothstep(0.52, 0.66, noise2(wp * 0.18) * 0.7 + noise2(wp * 0.7) * 0.3) * smoothstep(0.92, 0.99, n.y);
        wet = wetness * exposed * mix(0.45 * saturate(n.y * 0.7 + 0.3), 1.0, puddle);
    }
#endif
#endif

#ifdef PROGRAM_ENTITIES
    albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
    material = MAT_ENTITY;
#endif
#ifdef PROGRAM_HAND
    material = MAT_HAND;
#endif
#ifdef PROGRAM_SPIDEREYES
    material = MAT_GLOW;
    emission = 1.0;
#endif

#if defined PROGRAM_BEACON || defined PROGRAM_LIGHTNING
    // Unlit: already the colour it should be, in linear light
#ifdef PROGRAM_LIGHTNING
    outAlbedo = vec4(toLinear(albedo.rgb) * 12.0, albedo.a);
#else
    outAlbedo = vec4(toLinear(albedo.rgb) * 3.0 * EMISSIVE_STRENGTH, albedo.a);
#endif
    outNormalLight = vec4(encodeNormal(n), lmcoord);
    outMaterial = vec4(float(MAT_UNLIT) / 255.0, 0.0, 0.0, 1.0);
#else
    outAlbedo = vec4(albedo.rgb, albedo.a);
    outNormalLight = vec4(encodeNormal(n), lmcoord);
    outMaterial = vec4(float(material) / 255.0, emission, sss, wet);
#endif
#endif
}

#endif
