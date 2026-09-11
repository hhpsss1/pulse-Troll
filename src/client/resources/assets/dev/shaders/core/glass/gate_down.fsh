#version 330

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D MaskSampler;

layout(std140) uniform GateData {
    vec4 params; // x=sourceWidth, y=sourceHeight, z=radiusTexels, w=vertical
};

void main() {
    vec2 texel = 1.0 / params.xy;

    // One texel here stands for an 8x8 block of screen pixels. The offsets land on the seams
    // between pixel pairs, so each bilinear tap averages a 2x2 quad and sixteen of them cover
    // the whole block — a single covered pixel still comes out well above the gate threshold.
    float covered = 0.0;
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec2 offset = (vec2(float(x), float(y)) * 2.0 - 3.0) * texel;
            covered = max(covered, texture(MaskSampler, texCoord + offset).r);
        }
    }

    fragColor = vec4(covered, 0.0, 0.0, 1.0);
}
