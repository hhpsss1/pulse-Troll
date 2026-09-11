#version 330

in vec2 texCoord;
out vec4 fragColor;

layout(std140) uniform SkyInfo {
    vec4 ResolutionTimeMode;
    vec4 AuroraColorQuality;
    vec4 StarsNightSaturation;
    vec4 PulseCameraFov;
};

#define uResolution ResolutionTimeMode.xy
#define uCameraDir PulseCameraFov.xy
#define uColor AuroraColorQuality.xyz
#define uTime PulseCameraFov.w
#define uAlpha 1.0
#define uSpeed 1.0
#define uScale 5.0
#define uIntensity 0.02
#define uFov PulseCameraFov.z

mat3 rotX(float a) {
    float c = cos(a), s = sin(a);
    return mat3(1.0, 0.0, 0.0, 0.0, c, s, 0.0, -s, c);
}
mat3 rotY(float a) {
    float c = cos(a), s = sin(a);
    return mat3(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c);
}

float hash(float n) { return fract(sin(n) * 43758.5453123); }

float noise(vec3 x) {
    vec3 p = floor(x);
    vec3 f = fract(x);
    f = f*f*(3.0-2.0*f);
    float n = p.x + p.y*157.0 + 113.0*p.z;
    return mix(mix(mix(hash(n+0.0), hash(n+1.0), f.x),
                   mix(hash(n+157.0), hash(n+158.0), f.x), f.y),
               mix(mix(hash(n+113.0), hash(n+114.0), f.x),
                   mix(hash(n+270.0), hash(n+271.0), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    vec3 shift = vec3(100.0);
    for (int i = 0; i < 4; ++i) {
        v += a * noise(p);
        p = p * 2.0 + shift;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    vec2 sp = uv * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    float tanV = tan(radians(uFov) * 0.5);
    vec3 rayV = normalize(vec3(sp.x * tanV * aspect, sp.y * tanV, 1.0));
    vec3 rayW = rotY(uCameraDir.x) * rotX(uCameraDir.y) * rayV;

    vec2 skyUV = vec2(atan(rayW.z, rayW.x), rayW.y / (length(rayW.xz) + 1e-5));

    vec3 cloudPos = rayW * uScale;
    cloudPos.y += uTime * uSpeed * 0.12;
    cloudPos.x += uTime * uSpeed * 0.06;
    float cloudNoise = fbm(cloudPos);

    vec3 baseBg = uColor * 0.08;
    vec3 cloudColor = mix(baseBg, uColor * 0.32, cloudNoise);

    float timeScale = uTime * 1.3;
    float strikeInterval = floor(timeScale);
    float strikeTime = fract(timeScale);

    float hasStrike = step(0.3, hash(strikeInterval * 19.45));
    float strikeX = (hash(strikeInterval * 37.89) - 0.5) * 3.14159 * 1.3;

    float flash = 0.0;
    if (hasStrike > 0.5) {
        if (strikeTime < 0.08) flash = strikeTime / 0.08;
        else if (strikeTime < 0.16) flash = 1.0 - (strikeTime - 0.08) / 0.08;
        else if (strikeTime < 0.22) flash = 0.0;
        else if (strikeTime < 0.32) flash = (strikeTime - 0.22) / 0.1;
        else flash = 1.0 - (strikeTime - 0.32) / 0.68;
    }
    flash = clamp(flash, 0.0, 1.0) * uIntensity * 14.0;

    vec3 boltColor = vec3(0.0);
    if (flash > 0.05 && skyUV.y > -0.3) {
        float boltJagged = (fbm(vec3(skyUV.y * 3.5, strikeInterval, 0.0)) - 0.5) * 0.7;
        float branch = (fbm(vec3(skyUV * 5.5, strikeInterval + 2.0)) - 0.5) * 0.3;
        float distToBolt = abs(skyUV.x - strikeX - boltJagged - branch);

        float core = exp(-180.0 * distToBolt);
        float glow = exp(-18.0 * distToBolt);
        float verticalFade = smoothstep(-0.4, 0.1, skyUV.y) * smoothstep(3.2, 0.6, skyUV.y);

        boltColor = vec3(0.92, 0.95, 1.0) * (core * 3.5 + glow * 1.3) * flash * verticalFade;
    }

    vec3 finalColor = cloudColor + (uColor * flash * 0.5 * (1.0 - cloudNoise * 0.6));
    finalColor += boltColor;
    finalColor = mix(vec3(0.003, 0.003, 0.01), finalColor, uAlpha);

    fragColor = vec4(finalColor, uAlpha);
}
