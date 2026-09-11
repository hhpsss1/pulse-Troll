#version 330

layout(std140) uniform BlockOverlayInfo {
    vec4 BaseColor;
    vec4 SecondaryColor;
    vec4 Motion;
    vec4 Appearance;
};

#define color BaseColor.rgb
#define color2 SecondaryColor.rgb
#define time Motion.x
#define speed Motion.y
#define scale Motion.z
#define outline Motion.w
#define glow Appearance.x
#define fill Appearance.y
#define alpha Appearance.z

in vec2 texCoord0;
out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 5; i++) {
        value += noise(p) * amplitude;
        p = p * 2.02 + vec2(8.4, 5.7);
        amplitude *= 0.5;
    }
    return value;
}

float ridged(vec2 p) {
    float value = 0.0;
    float amplitude = 0.55;
    for (int i = 0; i < 4; i++) {
        float r = 1.0 - abs(noise(p) * 2.0 - 1.0);
        value += r * amplitude;
        p = p * 2.18 + vec2(3.1, 9.2);
        amplitude *= 0.52;
    }
    return value;
}

void main() {
    // Use model UV coordinates for smooth model-space mapping
    vec2 uv = texCoord0 * 4.0;

    float t = time * max(speed, 0.001);
    vec2 flow = uv * mix(1.2, 3.2, clamp(scale / 3.0, 0.0, 1.0));
    vec2 drift = vec2(t * 0.20, -t * 0.15);

    vec2 warp = vec2(
        fbm(flow * 0.90 + drift * 0.75 + vec2(0.0, 4.1)),
        fbm(flow * 0.78 - drift * 0.48 + vec2(3.7, 1.8))
    );
    vec2 q = flow + (warp - 0.5) * 1.8;

    float mist = fbm(q * 0.72 - drift * 0.24 + vec2(4.2, 8.1));
    float veins = ridged(q * 1.85 + vec2(mist * 2.5, mist * 1.6) - drift * 0.55);
    veins = pow(clamp(veins, 0.0, 1.0), 2.4);

    float stripeA = 1.0 - abs(sin((q.x * 1.08 + q.y * 0.42) * 1.7 + time * 0.85 + mist * 4.3));
    float stripeB = 1.0 - abs(sin((q.x * -0.58 + q.y * 1.12) * 1.45 - time * 0.65 - mist * 2.9));
    stripeA = pow(clamp(stripeA, 0.0, 1.0), 4.8);
    stripeB = pow(clamp(stripeB, 0.0, 1.0), 5.4);

    float energy = clamp(mist * 0.22 + veins * 0.88 + stripeA * 0.55 + stripeB * 0.32, 0.0, 1.0);
    float core = smoothstep(0.18, 0.98, energy);
    float accent = pow(clamp(max(veins, stripeA), 0.0, 1.0), 1.25);

    vec3 shaderColor = mix(color, color2, clamp(core * 0.75 + stripeB * 0.25, 0.0, 1.0));
    float fillStrength = fill * (0.26 + core * 0.82 + accent * 0.28);
    float edgeStrength = glow * 0.12;

    vec3 rgb = shaderColor * fillStrength + color * edgeStrength;
    float outAlpha = clamp(alpha * (fillStrength * 0.92), 0.0, 1.0);

    if (outAlpha <= 0.001) discard;
    fragColor = vec4(rgb, outAlpha);
}
