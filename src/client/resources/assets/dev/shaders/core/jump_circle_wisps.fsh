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

void main() {
    vec2 p = (texCoord0 - 0.5) * 2.0;
    float r = length(p);
    if (r < 0.43 || r > 0.88) discard;

    float angle = atan(p.y, p.x);
    float warp = sin(angle * 3.0 + 0.8) * 0.010
               + sin(angle * 8.0 - 1.4) * 0.005;

    // The reference is a luminous ribbon rather than one mathematically
    // perfect line: one bright spine, two translucent neighbouring traces,
    // and a broad low-alpha bloom over the blocks.
    float dMain = abs(r - (0.650 + warp));
    float dInner = abs(r - (0.620 + warp + sin(angle * 5.0) * 0.006));
    float dOuter = abs(r - (0.682 + warp + sin(angle * 6.0 + 2.0) * 0.007));

    float mainCore = 1.0 - smoothstep(0.006, 0.020, dMain);
    float mainGlow = exp(-dMain * dMain * 330.0) * 0.58;
    float innerRibbon = (1.0 - smoothstep(0.004, 0.024, dInner))
                      * (0.42 + 0.20 * sin(angle * 3.0 - 0.5));
    float outerRibbon = (1.0 - smoothstep(0.004, 0.027, dOuter))
                      * (0.36 + 0.18 * sin(angle * 4.0 + 1.3));
    float wideBloom = exp(-dMain * dMain * 58.0) * 0.24;
    float energy = clamp(mainCore * 0.78 + mainGlow + innerRibbon + outerRibbon + wideBloom, 0.0, 1.0);

    float alpha = energy * vertexColor.a;
    if (alpha <= 0.004) discard;

    vec3 color = vertexColor.rgb * (0.72 + energy * 0.34);
    fragColor = vec4(color, alpha) * ColorModulator;
}
