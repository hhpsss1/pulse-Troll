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

uniform sampler2D Sampler0;
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
    for (int i = 0; i < 4; i++) {
        value += noise(p) * amplitude;
        p = p * 2.0 + vec2(8.4, 5.7);
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    // Sample the item texture to get its alpha (shape mask)
    vec4 texColor = texture(Sampler0, texCoord0);
    if (texColor.a <= 0.001) discard;

    // Screen-space coordinates for consistency
    vec2 uv = gl_FragCoord.xy * 0.0025;

    float t = time * max(speed, 0.001);
    vec2 flow = uv * mix(1.0, 2.5, clamp(scale / 3.0, 0.0, 1.0));
    vec2 drift = vec2(t * 0.15, -t * 0.10);

    // Domain warping for smooth plasma liquid motion
    vec2 q = flow + drift;
    vec2 r = vec2(
        fbm(q + vec2(0.0, 0.0)),
        fbm(q + vec2(5.2, 1.3))
    );
    vec2 s = vec2(
        fbm(q + 3.0 * r + vec2(1.7, 9.2) + drift * 0.3),
        fbm(q + 3.0 * r + vec2(8.3, 2.8) - drift * 0.2)
    );
    float p_val = fbm(q + 3.0 * s);

    // Define beautiful pink/purple plasma colors
    vec3 pink1 = vec3(0.98, 0.05, 0.65); // Hot Pink
    vec3 pink2 = vec3(0.55, 0.0, 0.85);  // Purple
    vec3 pink3 = vec3(1.0, 0.45, 0.85);  // Soft pink highlight

    // Blend based on noise layers
    vec3 plasmaCol = mix(pink1, pink2, clamp(length(r) * 1.2, 0.0, 1.0));
    plasmaCol = mix(plasmaCol, pink3, clamp(p_val * 1.5, 0.0, 1.0));

    // Blend with the user's custom accent color (BaseColor/SecondaryColor) for customization
    vec3 customMix = mix(color, color2, clamp(p_val, 0.0, 1.0));
    vec3 finalShaderCol = mix(plasmaCol, customMix, 0.35); // 65% plasma theme, 35% custom color

    float core = smoothstep(0.15, 0.85, p_val);
    float fillStrength = fill * (0.3 + core * 0.7);
    float edgeStrength = glow * 0.15;

    vec3 rgb = finalShaderCol * fillStrength + color * edgeStrength;
    float outAlpha = clamp(alpha * (fillStrength * 0.95) * texColor.a, 0.0, 1.0);

    if (outAlpha <= 0.001) discard;
    fragColor = vec4(rgb, outAlpha);
}
