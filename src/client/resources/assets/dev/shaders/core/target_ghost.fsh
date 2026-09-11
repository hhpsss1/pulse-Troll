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
    if (length(p) > 1.0) {
        discard;
    }

    float radius = 0.00005;
    float glowSize = 1.05;
    float softness = 0.0005;

    float dist = length(p) - radius;
    float fillAlpha = 1.0 - smoothstep(-softness, softness, dist);
    float glow = pow(clamp(1.0 - dist / glowSize, 0.0, 1.0), 2.0);

    vec3 col = vertexColor.rgb * fillAlpha + vertexColor.rgb * 1.05 * glow;
    float alpha = max(fillAlpha, glow * 0.65);

    fragColor = vec4(col, alpha * vertexColor.a) * ColorModulator;
}
