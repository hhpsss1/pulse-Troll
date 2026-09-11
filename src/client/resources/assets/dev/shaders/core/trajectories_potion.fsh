#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// A flat outline with pixel-sized antialiasing, without a glow or radial falloff.
void main() {
    float dist = length((texCoord0 - 0.5) * 2.0);
    float edgeDistance = abs(dist - 0.97);
    float aa = max(fwidth(dist) * 0.5, 0.0001);
    float ring = 1.0 - smoothstep(0.011 - aa, 0.011 + aa, edgeDistance);
    if (dist > 1.0 || ring <= 0.002) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, ring * 0.9 * vertexColor.a) * ColorModulator;
}
