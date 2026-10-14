// Distant Horizons' far water: like gbuffers_water's, its wave normal for composite, nothing in colortex0.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/uniforms.glsl"

#ifdef VSH
out vec3 normal;
out vec2 lmcoord;
out vec3 playerPos;
flat out int material;

void main() {
    gl_Position = ftransform();
    normal = normalize(mat3(gbufferModelViewInverse) * normalize(gl_NormalMatrix * gl_Normal));
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
#include "/lib/space.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/water.glsl"

in vec3 normal;
in vec2 lmcoord;
in vec3 playerPos;
flat in int material;

/* RENDERTARGETS: 4,0 */
layout(location = 0) out vec4 outWater;
layout(location = 1) out vec4 outColor;

void main() {
    float dist = length(playerPos);
    float noise = dither(gl_FragCoord.xy, frameCounter);
    if (dist < far * (0.82 + 0.12 * noise)) discard;
    vec3 n = normalize(normal);
    if (material == DH_BLOCK_WATER && n.y > 0.5) n = waveNormal(playerPos.xz + cameraPosition.xz);
    outWater = vec4(encodeNormal(n), material == DH_BLOCK_WATER ? 0.5 + 0.5 * lmcoord.y : 0.25, 1.0);
    outColor = vec4(0.0);
}
#endif
