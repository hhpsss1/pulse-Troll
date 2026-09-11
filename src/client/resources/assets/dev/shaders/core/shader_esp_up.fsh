#version 330

uniform sampler2D InputSampler;

layout(std140) uniform EspKawaseInfo {
    vec4 KawaseParams; // halfPixelX, halfPixelY, offset, unused
};

in vec2 texCoord;

out vec4 fragColor;

// Dual-Kawase upsample: 8 taps on a diamond, the offset scales the glow radius.
void main() {
    vec2 hp = KawaseParams.xy * KawaseParams.z;

    vec4 sum = texture(InputSampler, texCoord + vec2(-hp.x * 2.0, 0.0));
    sum += texture(InputSampler, texCoord + vec2(-hp.x, hp.y)) * 2.0;
    sum += texture(InputSampler, texCoord + vec2(0.0, hp.y * 2.0));
    sum += texture(InputSampler, texCoord + vec2(hp.x, hp.y)) * 2.0;
    sum += texture(InputSampler, texCoord + vec2(hp.x * 2.0, 0.0));
    sum += texture(InputSampler, texCoord + vec2(hp.x, -hp.y)) * 2.0;
    sum += texture(InputSampler, texCoord + vec2(0.0, -hp.y * 2.0));
    sum += texture(InputSampler, texCoord + vec2(-hp.x, -hp.y)) * 2.0;

    fragColor = vec4((sum * 0.0833).rgb, 1.0);
}
