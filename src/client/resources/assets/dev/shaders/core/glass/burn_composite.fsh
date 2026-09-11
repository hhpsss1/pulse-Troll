#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D MaskSampler;   // hand mask (r channel)
uniform sampler2D GlowSampler;   // burn_glow output
uniform sampler2D TrailSampler;  // current trail

layout(std140) uniform BurnData {
    vec4 res;        // x=width, y=height, z=time(sec), w=fillMode
    vec4 fire;       // x=radiusPx, y=strength, z=flameSpeed, w=colorMix
    vec4 trailP;     // x=decay, y=flameHeightPx, z=flowSpeed, w=trailStrength
    vec4 fireColor;  // rgb=fire color, w=glowStrength
};

float sampleMask(vec2 coord) {
    return clamp(texture(MaskSampler, coord).r, 0.0, 1.0);
}

void main() {
    vec2 TexelSize = 1.0 / res.xy;
    float FillMode = res.w;
    float TrailStrength = trailP.w;
    float GlowStrength = fireColor.w;

    vec4 glow = texture(GlowSampler, texCoord);
    vec4 trail = texture(TrailSampler, texCoord);

    // Judged on the buffers themselves rather than the coverage gate: the smoke drifts on its
    // own schedule, and gating it on a coarse grid would clip the fade into visible steps.
    if (glow.a < 0.002 && max(trail.r, max(trail.g, trail.b)) < 0.004) {
        discard;
    }

    float handMask = sampleMask(texCoord);
    float outsideMask = 1.0 - smoothstep(0.015, 0.20, handMask);
    // In burn-through mode the gating goes away — fire applies uniformly over the
    // whole frame; in overlay mode we keep the edge-only behavior.
    float fireGate = mix(outsideMask, 1.0, clamp(FillMode, 0.0, 1.0));
    float neighborAlpha = 0.0;
    neighborAlpha = max(neighborAlpha, sampleMask(texCoord + vec2(TexelSize.x, 0.0)));
    neighborAlpha = max(neighborAlpha, sampleMask(texCoord - vec2(TexelSize.x, 0.0)));
    neighborAlpha = max(neighborAlpha, sampleMask(texCoord + vec2(0.0, TexelSize.y)));
    neighborAlpha = max(neighborAlpha, sampleMask(texCoord - vec2(0.0, TexelSize.y)));
    float objectEdge = clamp(max(glow.a * 1.12, neighborAlpha) * fireGate, 0.0, 1.0);

    // additive pass: output only what gets added on top of the scene
    vec3 addition = vec3(0.0);
    float trailVisibility = clamp(0.88 + objectEdge * 0.32, 0.0, 1.0) * fireGate;
    addition += trail.rgb * trailVisibility * TrailStrength * 0.86;

    float glowAlpha = clamp(glow.a * (0.94 + objectEdge * 0.32) * fireGate, 0.0, 0.76);
    addition += glow.rgb * glowAlpha * (0.72 + GlowStrength * 0.26);

    fragColor = vec4(clamp(addition, 0.0, 1.0), 1.0);
}
