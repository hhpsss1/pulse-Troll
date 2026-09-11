#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 sampled = texture(Sampler0, texCoord0);
    vec4 color = sampled * vertexColor * ColorModulator;
    if (color.a <= 0.001) {
        discard;
    }
    fragColor = color;
}
