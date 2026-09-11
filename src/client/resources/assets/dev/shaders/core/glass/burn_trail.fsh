#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D TrailSampler;  // previous frame trail (ping-pong)
uniform sampler2D GlowSampler;   // burn_glow output
uniform sampler2D HandSampler;   // scene incl. hands
uniform sampler2D MaskSampler;   // hand mask (r channel)

layout(std140) uniform BurnData {
    vec4 res;        // x=width, y=height, z=time(sec), w=fillMode
    vec4 fire;       // x=radiusPx, y=strength, z=flameSpeed, w=colorMix
    vec4 trailP;     // x=decay, y=flameHeightPx, z=flowSpeed, w=trailStrength
    vec4 fireColor;  // rgb=fire color, w=glowStrength
};

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
        mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}

vec4 sampleTrail(vec2 coord) {
    return texture(TrailSampler, coord);
}

void main() {
    vec2 TexelSize = 1.0 / res.xy;
    float Time = res.z;
    float ColorMix = fire.w;
    vec3 FireColor = fireColor.rgb;
    float Decay = trailP.x;
    float FlameHeight = trailP.y;
    float FlowSpeed = trailP.z;

    // Deliberately judged on what the buffer actually holds, never on the coverage gate. This is
    // a feedback buffer: cutting it on a 1/8-resolution grid builds a wall the smoke piles up
    // against, and the fade ends in visible 8x8 steps. Reading the trail itself costs a few more
    // taps and stops exactly where the smoke has genuinely faded out.
    vec4 glowProbe = texture(GlowSampler, texCoord);
    float maskProbe = texture(MaskSampler, texCoord).r;
    if (glowProbe.a < 0.002 && maskProbe < 0.004) {
        vec2 reach = TexelSize * 9.0;
        vec4 c = texture(TrailSampler, texCoord);
        c = max(c, texture(TrailSampler, texCoord + vec2(reach.x, 0.0)));
        c = max(c, texture(TrailSampler, texCoord - vec2(reach.x, 0.0)));
        c = max(c, texture(TrailSampler, texCoord + vec2(0.0, reach.y)));
        c = max(c, texture(TrailSampler, texCoord - vec2(0.0, reach.y)));
        if (max(c.a, max(c.r, max(c.g, c.b))) < 0.004) {
            fragColor = vec4(0.0);
            return;
        }
    }

    float flowTime = Time * FlowSpeed;
    float curlA = noise(texCoord * vec2(4.8, 7.2) + vec2(flowTime * 0.18, -flowTime * 0.28));
    float curlB = noise(texCoord * vec2(10.0, 13.0) + vec2(-flowTime * 0.22, flowTime * 0.16));
    float angle = (curlA * 2.0 - 1.0) * 2.15 + sin((texCoord.y + curlB) * 12.0 + flowTime * 1.7) * 0.74;
    float lift = mix(0.42, 1.28, clamp(FlameHeight / 56.0, 0.0, 1.0));
    vec2 flow = vec2(cos(angle) * 0.86, -lift + sin(angle) * 0.42) * TexelSize * FlowSpeed;

    vec4 previous = sampleTrail(texCoord + flow) * 0.58;
    previous += sampleTrail(texCoord + flow + vec2(TexelSize.x, 0.0) * 1.35) * 0.12;
    previous += sampleTrail(texCoord + flow - vec2(TexelSize.x, 0.0) * 1.35) * 0.12;
    previous += sampleTrail(texCoord + flow + vec2(0.0, TexelSize.y) * 1.15) * 0.09;
    previous += sampleTrail(texCoord + flow - vec2(0.0, TexelSize.y) * 1.15) * 0.09;
    previous.rgb *= Decay;
    previous.a *= Decay;

    vec4 glow = glowProbe;
    vec3 handRgb = texture(HandSampler, texCoord).rgb;
    float handMask = clamp(maskProbe, 0.0, 1.0);
    float outsideMask = 1.0 - smoothstep(0.02, 0.24, handMask);

    float broken = noise(texCoord * vec2(16.0, 25.0) + vec2(flowTime * 0.35, -flowTime * 0.82));
    float ribbon = sin((texCoord.x - texCoord.y * 0.34) * 34.0 + flowTime * 4.2 + curlA * 5.5) * 0.5 + 0.5;
    float curlCut = smoothstep(0.20, 0.90, broken) * 0.55 + smoothstep(0.34, 0.98, ribbon) * 0.45;
    float flameAlpha = glow.a * outsideMask * (0.28 + curlCut * 0.42);
    float textureAlpha = handMask * (0.42 + glow.a * 0.18);
    vec3 handTinted = mix(handRgb, FireColor, clamp(ColorMix, 0.0, 1.0));
    vec3 sourceRgb = handTinted * textureAlpha + glow.rgb * flameAlpha;
    float sourceAlpha = clamp(max(textureAlpha, flameAlpha), 0.0, 0.74);

    float outAlpha = 1.0 - (1.0 - previous.a) * (1.0 - clamp(sourceAlpha, 0.0, 0.68));
    vec3 outRgb = previous.rgb + sourceRgb * (1.0 - previous.a * 0.38);
    fragColor = vec4(clamp(outRgb, 0.0, 1.0), clamp(outAlpha, 0.0, 0.88));
}
