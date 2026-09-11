#version 330

in vec3 vWorldDir;

layout(std140) uniform SkyInfo {
    vec4 ResolutionTimeMode;
    vec4 AuroraColorQuality;
    vec4 StarsNightSaturation;
};

#define Time ResolutionTimeMode.z
#define AuroraColor AuroraColorQuality.xyz
#define Quality AuroraColorQuality.w
#define Night StarsNightSaturation.y
#define Saturation StarsNightSaturation.z

out vec4 OutColor;

vec3 applySaturation(vec3 color, float saturation) {
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    return mix(vec3(gray), color, saturation);
}

#define TAU 6.28318530718
#define PI 3.14159265
#define MAX_ITER 5
#define INTENSITY 0.005

void main() {
    vec3 ray = normalize(vWorldDir);
    vec2 uv = vec2(atan(ray.x, ray.z) / TAU, asin(clamp(ray.y, -1.0, 1.0)) / PI) * Quality;

    vec2 p = mod(uv * TAU, TAU) - 250.0;

    vec2 iterPos = p;
    float value = 1.0;
    float timeOffset = Time * 0.5 + 23.0;

    for (int n = 0; n < MAX_ITER; n++) {
        float iterTime = timeOffset * (1.0 - (3.5 / float(n + 1)));

        iterPos = p + vec2(
        cos(iterTime - iterPos.x) + sin(iterTime + iterPos.y),
        sin(iterTime - iterPos.y) + cos(iterTime + iterPos.x)
        );

        float sinX = sin(iterPos.x + iterTime);
        float cosY = cos(iterPos.y + iterTime);

        float compX = p.x * INTENSITY / max(abs(sinX), 0.001);
        float compY = p.y * INTENSITY / max(abs(cosY), 0.001);
        float dist = sqrt(compX * compX + compY * compY);

        value += 1.0 / dist;
    }

    value = 1.17 - pow(value / float(MAX_ITER), 1.4);

    float pwr = value * value;
    pwr = pwr * pwr;
    pwr = pwr * pwr;

    vec3 baseColor = vec3(abs(pwr));
    vec3 enhancedColor = clamp(baseColor + vec3(0.0, 0.35, 0.5), 0.0, 1.0);
    float luminance = dot(enhancedColor, vec3(0.299, 0.587, 0.114));

    vec3 col = vec3(luminance) * AuroraColor * 1.4;
    OutColor = vec4(applySaturation(col, Saturation), 1.0);
}
