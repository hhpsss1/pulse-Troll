#version 330

uniform sampler2D InputSampler;

layout(std140) uniform GaussianInfo {
    vec4 InputSizeDirection;
    vec4 Params;
};

in vec2 texCoord;

out vec4 fragColor;

float gaussian(float x, float sigma) {
    return exp(-0.5 * (x * x) / (sigma * sigma));
}

void main() {
    vec2 inputSize = InputSizeDirection.xy;
    vec2 direction = InputSizeDirection.zw;
    int radius = int(clamp(Params.x, 1.0, 32.0) + 0.5);
    float sigma = max(Params.y, 0.5);
    vec2 texel = direction / inputSize;

    vec4 color = texture(InputSampler, texCoord) * gaussian(0.0, sigma);
    float total = gaussian(0.0, sigma);

    for (int i = 1; i <= 32; i++) {
        if (i > radius) {
            break;
        }

        float weight = gaussian(float(i), sigma);
        vec2 offset = texel * float(i);
        color += texture(InputSampler, texCoord + offset) * weight;
        color += texture(InputSampler, texCoord - offset) * weight;
        total += weight * 2.0;
    }

    fragColor = color / total;
}
