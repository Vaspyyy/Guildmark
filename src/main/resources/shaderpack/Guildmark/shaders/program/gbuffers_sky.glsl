// Minecraft's own sky, sun, moon, stars and clouds: all left out. Deferred paints Guildmark's sky behind the
// world instead, with its own clouds.

#include "/lib/settings.glsl"

#ifdef VSH
void main() {
    gl_Position = ftransform();
}
#endif

#ifdef FSH
/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
    discard;
}
#endif
