// Moving between Iris's spaces: screen (uv, depth) -> view -> player (camera-relative world) -> world.

vec3 screenToView(vec2 uv, float depth, mat4 projectionInverse) {
    vec4 ndc = vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    vec4 v = projectionInverse * ndc;
    return v.xyz / v.w;
}

vec3 viewToPlayer(vec3 view) {
    return mat3(gbufferModelViewInverse) * view + gbufferModelViewInverse[3].xyz;
}

vec3 playerToView(vec3 player) {
    return mat3(gbufferModelView) * player + gbufferModelView[3].xyz;
}

vec3 viewToScreen(vec3 view) {
    vec4 clip = gbufferProjection * vec4(view, 1.0);
    return clip.xyz / clip.w * 0.5 + 0.5;
}

/** A view-space direction (sunPosition and the like) turned to world space and normalised. */
vec3 viewDirToWorld(vec3 v) {
    return normalize(mat3(gbufferModelViewInverse) * v);
}
