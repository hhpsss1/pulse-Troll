#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 vWorldDir;

void main() {
    vec2 ndc = Position.xy;
    vec4 clip = vec4(ndc, 1.0, 1.0);
    vec4 view = inverse(ProjMat) * clip;
    vec3 dirView = normalize(view.xyz / max(abs(view.w), 1.0e-5));
    vWorldDir = normalize((inverse(ModelViewMat) * vec4(dirView, 0.0)).xyz);
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
