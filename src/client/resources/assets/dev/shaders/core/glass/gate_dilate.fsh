#version 330

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D InputSampler;

layout(std140) uniform GateData {
    vec4 params; // x=gateWidth, y=gateHeight, z=radiusTexels, w=vertical
};

const int MAX_RADIUS = 40;

void main() {
    vec2 texel = 1.0 / params.xy;
    vec2 step = params.w > 0.5 ? vec2(0.0, texel.y) : vec2(texel.x, 0.0);
    int radius = int(params.z);

    // Separable max dilation: grows the covered region by the flame radius so a lookup at any
    // pixel answers "is there silhouette within reach of me" with one tap.
    float covered = texture(InputSampler, texCoord).r;
    for (int i = 1; i <= MAX_RADIUS; i++) {
        if (i > radius) break;
        covered = max(covered, texture(InputSampler, texCoord + step * float(i)).r);
        covered = max(covered, texture(InputSampler, texCoord - step * float(i)).r);
    }

    fragColor = vec4(covered, 0.0, 0.0, 1.0);
}
