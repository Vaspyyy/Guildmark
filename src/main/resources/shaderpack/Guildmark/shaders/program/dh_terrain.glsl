// Distant Horizons' far land: the same buffers as gbuffers_world, from DH's plain block colours. Inside
// Minecraft's own render distance it leaves the land to the real chunks.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"

#ifdef VSH
out vec4 tint;
out vec3 normal;
out vec2 lmcoord;
out vec3 playerPos;
flat out int material;

void main() {
    gl_Position = ftransform();
    tint = gl_Color;
    normal = normalize(mat3(gbufferModelViewInverse) * normalize(gl_NormalMatrix * gl_Normal));
    // The light levels' scale isn't documented for DH: 0..1, 0..15 and 0..240 all come out right here
    vec2 lm = gl_MultiTexCoord2.xy;
    float top = max(lm.x, lm.y);
    if (top > 15.5) lm /= 240.0;
    else if (top > 1.01) lm /= 15.0;
    lmcoord = clamp(lm, 0.0, 1.0);
    vec3 view = (gl_ModelViewMatrix * gl_Vertex).xyz;
    playerPos = mat3(gbufferModelViewInverse) * view + gbufferModelViewInverse[3].xyz;
    material = dhMaterialId;
}
#endif

#ifdef FSH
in vec4 tint;
in vec3 normal;
in vec2 lmcoord;
in vec3 playerPos;
flat in int material;

/* RENDERTARGETS: 0,1,2 */
layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outNormalLight;
layout(location = 2) out vec4 outMaterial;

void main() {
    float dist = length(playerPos);
    float noise = dither(gl_FragCoord.xy, frameCounter);
    if (dist < far * (0.82 + 0.12 * noise)) discard;
    vec3 wp = playerPos + cameraPosition;
    // A little grain, so the far land isn't flat colour
    vec3 albedo = tint.rgb * (0.90 + 0.18 * noise2(floor(wp.xz) * 0.37 + floor(wp.y) * 1.3));
    int mat = MAT_DEFAULT;
    float emission = 0.0;
    float sss = 0.0;
    if (material == DH_BLOCK_LEAVES) {
        mat = MAT_LEAVES;
        sss = 0.5;
    } else if (material == DH_BLOCK_LAVA) {
        mat = MAT_LAVA;
        emission = 0.9;
    } else if (material == DH_BLOCK_ILLUMINATED) {
        mat = MAT_GLOW;
        emission = 0.6;
    } else if (material == DH_BLOCK_SNOW) {
        mat = MAT_SNOW;
    }
    outAlbedo = vec4(albedo, 1.0);
    outNormalLight = vec4(encodeNormal(normalize(normal)), lmcoord);
    outMaterial = vec4(float(mat) / 255.0, emission, sss, 0.0);
}
#endif
