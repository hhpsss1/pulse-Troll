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

#define SRGB_TO_LINEAR(c) pow((c), vec3(2.2))
#define LINEAR_TO_SRGB(c) pow((c), vec3(1.0 / 2.2))
#define SRGB(r, g, b) SRGB_TO_LINEAR(vec3(float(r), float(g), float(b)) / 255.0)

const vec3 COLOR0 = SRGB(255, 0, 114);
const vec3 COLOR1 = SRGB(197, 255, 80);

float gradientNoise(vec2 uv) {
    const vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(magic.z * fract(dot(uv, magic.xy)));
}

float roundedBoxDistance(vec2 point, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(point) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

void main() {
    float width = 1.0 / max(abs(dFdx(texCoord0.x)), 0.0001);
    float height = 1.0 / max(abs(dFdy(texCoord0.y)), 0.0001);
    float radius = floor(texCoord0.y) / 256.0;
    float rotation = radians(floor(texCoord0.x) / 256.0);
    vec2 localUv = vec2(fract(texCoord0.x), fract(texCoord0.y));
    vec2 local = (localUv - 0.5) * vec2(width, height);
    vec2 halfSize = vec2(width, height) * 0.5;

    float distance = roundedBoxDistance(local, halfSize, radius);
    float alpha = 1.0 - smoothstep(-1.0, 1.0, distance);
    if (alpha <= 0.001) {
        discard;
    }

    vec2 gradientPoint = localUv - 0.5;
    vec2 direction = vec2(cos(rotation), sin(rotation));
    float t = smoothstep(0.0, 1.0, clamp(dot(gradientPoint, direction) + 0.5, 0.0, 1.0));
    vec3 color = LINEAR_TO_SRGB(mix(COLOR0, COLOR1, t));
    vec2 pixel = localUv * vec2(width, height);
    color += (1.0 / 255.0) * gradientNoise(pixel) - (0.5 / 255.0);

    fragColor = vec4(color, vertexColor.a * alpha) * ColorModulator;
}
