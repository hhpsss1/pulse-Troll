#version 330

uniform sampler2D InputSampler;

layout(std140) uniform KawaseInfo {
    vec4 InputSizeOffset;
    vec4 Reserved;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 inputSize = InputSizeOffset.xy;
    float offset = InputSizeOffset.z;
    vec2 texel = offset / inputSize;

    vec4 color = vec4(0.0);
    color += texture(InputSampler, texCoord + vec2(-texel.x, -texel.y));
    color += texture(InputSampler, texCoord + vec2( texel.x, -texel.y));
    color += texture(InputSampler, texCoord + vec2(-texel.x,  texel.y));
    color += texture(InputSampler, texCoord + vec2( texel.x,  texel.y));

    fragColor = color * 0.25;
}
