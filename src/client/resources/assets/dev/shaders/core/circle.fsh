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
    float width = 1.0 / max(abs(dFdx(texCoord0.x)), 0.0001);
    float height = 1.0 / max(abs(dFdy(texCoord0.y)), 0.0001);
    vec2 local = (texCoord0 - 0.5) * vec2(width, height);
    float radius = min(width, height) * 0.5;
    float distance = length(local) - radius;
    float alpha = 1.0 - smoothstep(-1.0, 1.0, distance);
    if (alpha <= 0.001) {
        discard;
    }

    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}
