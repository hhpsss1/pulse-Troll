// Crystal Aura -- blast dome (port of the competitor's `blast_shockwave`).
//
// A world-anchored hemisphere that REFRACTS the scene instead of drawing a ring. There is no ring,
// no colour and no geometry: every pixel the dome covers samples the scene from a slightly
// displaced place, and the displacement ripples outward. That is what makes it read as air being
// shoved around rather than as a decal pasted over the world.
//
// Per pixel:
//   1. unproject the pixel through `invProjView` to get the camera-relative view ray;
//   2. for each live dome, take the perpendicular distance from its centre to that ray -- the
//      silhouette field of a sphere -- and normalise it by the radius into `t` (0 at the centre of
//      the disc, 1 at the front);
//   3. the screen-space gradient of that field IS the radial direction on screen (the dFdx/dFdy
//      trick from the original), so the offset needs no projected centre and stays correct under
//      any perspective;
//   4. offset the colour sample by `sin(t * PI * ripples)` along it, so the whole volume of the
//      dome ripples with concentric waves instead of a single thin front;
//   5. reject what the dome cannot reach: the lower hemisphere (that is what makes it a dome and
//      not a bubble) and anything the scene depth says is in front of the shell.
//
// Everything the original decided with an early `return` is a smoothstep here instead. A hard
// reject is a hard border, and a hard border is exactly what this effect must not have -- so the
// equator, the occlusion test, the "centre is in front of us" test and the leading edge all ramp.
//
// The masks are multiplied into a single coverage `w`, which is also the output alpha under
// premultiplied blending (`dst = rgb + dst * (1 - a)`). Since the undisplaced sample equals what
// is already in the destination, coverage 0 and coverage 1 agree at the boundary: the dome has no
// edge anywhere, it only stops displacing.
//
// SceneSampler is a *copy* of the finished world (see `scene_copy.fsh`) -- this pass writes into
// the main colour target, so it cannot read it. DepthSampler is the main target's depth
// attachment, bound as a texture; the pass is created colour-only so that is not a feedback loop.
#version 330 core

#define MAX_DOMES 4

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D SceneSampler; // snapshot of the finished world
uniform sampler2D DepthSampler; // the main target's depth attachment

layout(std140) uniform AuraDomeData {
    // inverse of projection * modelView for this frame -- unprojects straight to camera-relative
    mat4 invProjView;
    // x = live dome count, y = 1 when the device clips depth to [0,1] instead of [-1,1],
    // z = global strength, w unused
    vec4 control;
    // xyz = dome centre relative to the camera, w = current radius in blocks
    vec4 domes[MAX_DOMES];
    // x = fade (0..1), y = amplitude, z = ripples, w = radial blur
    vec4 domeParams[MAX_DOMES];
};

const float PI = 3.14159265;

// Never let the shell fight the surface it is standing on.
const float OCCLUDE_BIAS = 0.05;

// Half-width of the soft equator, as a fraction of the radius.
const float EQUATOR_SOFT = 0.10;

void main() {
    vec2 uv = texCoord;
    vec2 ndc = uv * 2.0 - 1.0;

    // Camera-relative ray through this pixel: the far-plane point, normalised.
    //
    // Deliberately not divided by w. Minecraft builds its projection with an effectively infinite
    // far plane, so w at clip z = 1 sits on top of zero and the division is a NaN waiting to
    // happen; for a *direction* the positive scale is irrelevant, only the sign of w is.
    vec4 farRel = invProjView * vec4(ndc, 1.0, 1.0);
    vec3 rayDir = normalize(farRel.w < 0.0 ? -farRel.xyz : farRel.xyz);

    // Distance from the camera to the scene along that ray. The sky carries no geometry, so it
    // stays effectively infinite and never occludes anything.
    float sceneDist = 1.0e9;
    float depth = texture(DepthSampler, uv).r;
    if (depth < 1.0) {
        float z = control.y > 0.5 ? depth : depth * 2.0 - 1.0;
        vec4 sceneRel = invProjView * vec4(ndc, z, 1.0);
        sceneDist = length(sceneRel.xyz / max(sceneRel.w, 1e-6));
    }

    float count = control.x;
    float globalStrength = max(control.z, 0.0);

    vec2 totalOffset = vec2(0.0);
    vec2 blurDir = vec2(0.0);
    float blurAmount = 0.0;
    float cover = 0.0;

    // The loop bound is a compile-time constant and nothing inside it branches, so the derivatives
    // below stay in uniform control flow -- dFdx/dFdy of a value only some lanes computed would be
    // undefined, and the original gets away with its early returns only by luck.
    for (int i = 0; i < MAX_DOMES; i++) {
        vec4 dome = domes[i];
        vec4 par = domeParams[i];

        vec3 centre = dome.xyz;
        float radius = dome.w;
        float safeRadius = max(radius, 1e-4);

        // Perpendicular distance centre to ray: the silhouette field of the sphere.
        float b = dot(rayDir, centre);
        float cc = dot(centre, centre);
        float dPerp = sqrt(max(0.0, cc - b * b));

        // Radial direction on screen = the gradient of that field. Computed for every lane,
        // masked afterwards.
        vec2 grad = vec2(dFdx(dPerp), dFdy(dPerp));
        float glen = length(grad);
        vec2 dir = glen > 1e-6 ? grad / glen : vec2(0.0);

        // 0 at the centre of the disc, 1 at the front.
        float t = dPerp / safeRadius;

        // Where the ray meets the shell; the near root, or the tangent point when there is none.
        float inside = max(0.0, radius * radius - dPerp * dPerp);
        float tHit = b - sqrt(inside);
        tHit = tHit > 0.0 ? tHit : b;
        vec3 hit = rayDir * tHit;

        // Live at all?
        float active = step(float(i) + 0.5, count) * step(1e-4, radius);

        // The centre has to be in front of us along this ray.
        float front = smoothstep(0.0, safeRadius * 0.35, b);

        // Dome, not bubble: the lower half sinks into the ground. Soft across the equator.
        float upper = smoothstep(
            -EQUATOR_SOFT,
            EQUATOR_SOFT,
            (hit.y - centre.y) / safeRadius
        );

        // Geometry standing in front of the shell hides it.
        float bias = max(OCCLUDE_BIAS, sceneDist * 0.005);
        float visible = 1.0 - smoothstep(0.0, bias * 2.0, tHit - (sceneDist + bias));

        // The leading edge dissolves instead of ending.
        float edgeFade = smoothstep(1.02, 0.90, t);

        float w = active * front * upper * visible * edgeFade * clamp(par.x, 0.0, 1.0);

        // The whole volume ripples: concentric waves along the radius.
        float ripple = sin(t * PI * max(par.z, 0.0));
        float amp = par.y * (0.7 + 0.3 * t) * globalStrength;
        totalOffset += dir * (ripple * amp * w);

        // The strongest dome owns the radial smear direction.
        float bl = par.w * globalStrength * w;
        blurDir = bl > blurAmount ? dir : blurDir;
        blurAmount = max(blurAmount, bl);

        cover = max(cover, w);
    }

    if (cover < 0.002) {
        discard;
    }

    vec2 suv = clamp(uv + totalOffset, vec2(0.0), vec2(1.0));
    vec3 col = textureLod(SceneSampler, suv, 0.0).rgb;

    // A light radial blur over the volume of the dome, so the displaced scene smears the way hot
    // air smears rather than simply sliding.
    if (blurAmount > 1e-5) {
        col += textureLod(SceneSampler, clamp(suv + blurDir * blurAmount, vec2(0.0), vec2(1.0)), 0.0).rgb;
        col += textureLod(SceneSampler, clamp(suv - blurDir * blurAmount, vec2(0.0), vec2(1.0)), 0.0).rgb;
        col += textureLod(SceneSampler, clamp(suv + blurDir * blurAmount * 2.0, vec2(0.0), vec2(1.0)), 0.0).rgb;
        col += textureLod(SceneSampler, clamp(suv - blurDir * blurAmount * 2.0, vec2(0.0), vec2(1.0)), 0.0).rgb;
        col /= 5.0;
    }

    // Premultiplied: the refracted colour fades in over whatever the destination already holds,
    // which at coverage 0 is the very same pixel -- no border, ever.
    fragColor = vec4(col * cover, cover);
}
