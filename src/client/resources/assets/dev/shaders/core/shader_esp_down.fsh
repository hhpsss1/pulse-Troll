#version 330

uniform sampler2D InputSampler;

layout(std140) uniform EspKawaseInfo {
    vec4 KawaseParams; // halfPixelX, halfPixelY, offset, unused
};

in vec2 texCoord;

out vec4 fragColor;

// Dual-Kawase downsample: 5 taps, weights 4/1/1/1/1.
void main() {
    vec2 hp = KawaseParams.xy;

    vec4 sum = texture(InputSampler, texCoord) * 4.0;
    sum += texture(InputSampler, texCoord - hp);
    sum += texture(InputSampler, texCoord + hp);
    sum += texture(InputSampler, texCoord + vec2(hp.x, -hp.y));
    sum += texture(InputSampler, texCoord - vec2(hp.x, -hp.y));

    fragColor = vec4((sum * 0.125).rgb, 1.0);
}
