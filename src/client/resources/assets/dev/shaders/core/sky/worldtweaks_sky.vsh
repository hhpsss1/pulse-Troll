#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 vPos;

void main() {
    // We render a fullscreen quad in clip-space.
    // Position.xy are already in [-1..1].
    vPos = Position;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
