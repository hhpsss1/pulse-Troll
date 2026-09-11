#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

uniform sampler2D DepthSampler;

#define MAX_CIRCLES 12

layout(std140) uniform JumpCircleInfo {
    vec4 ThemeCount;
    vec4 GlobalParams;
    vec4 Circles[MAX_CIRCLES];
    vec4 Effects[MAX_CIRCLES];
};

in vec2 texCoord;
out vec4 fragColor;

vec3 reconstructCameraRelativePosition(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = inverse(ProjMat) * clip;
    view /= max(abs(view.w), 1.0e-6);
    return (inverse(ModelViewMat) * vec4(view.xyz, 1.0)).xyz;
}

void main() {
    float rawDepth = texture(DepthSampler, texCoord).r;
    if (rawDepth >= 0.999999) discard;

    vec3 worldRelative = reconstructCameraRelativePosition(texCoord, rawDepth);
    int count = clamp(int(ThemeCount.w + 0.5), 0, MAX_CIRCLES);
    float combinedAlpha = 0.0;
    float combinedEnergy = 0.0;

    for (int index = 0; index < MAX_CIRCLES; index++) {
        if (index >= count) break;
        vec4 circle = Circles[index];
        vec4 effect = Effects[index];
        vec3 delta = worldRelative - circle.xyz;

        // A spherical wave intersects a flat floor as a circle and naturally
        // bends upward where that same wave reaches a wall, slab or irregular
        // visible surface. No per-block seams or hand-built side geometry.
        float distanceToShell = abs(length(delta) - circle.w);
        float core = 1.0 - smoothstep(effect.y * 0.45, effect.y * 1.65, distanceToShell);
        float halo = exp(-distanceToShell * distanceToShell /
            max(effect.z * effect.z, 1.0e-5));

        float angle = atan(delta.z, delta.x);
        float flow = 0.92 + sin(angle * 5.0 - GlobalParams.y * 0.65 + effect.w * 4.0) * 0.08;
        float energy = clamp((core * 0.82 + halo * 0.48) * flow, 0.0, 1.0);
        float alpha = clamp(energy * effect.x, 0.0, 1.0);
        combinedAlpha = 1.0 - (1.0 - combinedAlpha) * (1.0 - alpha);
        combinedEnergy = max(combinedEnergy, energy);
    }

    if (combinedAlpha <= 0.002) discard;
    vec3 color = ThemeCount.rgb * (0.76 + combinedEnergy * 0.30);
    fragColor = vec4(color, combinedAlpha) * ColorModulator;
}
