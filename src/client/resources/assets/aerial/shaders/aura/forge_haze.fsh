// Crystal Aura -- forge haze (port of the competitor's `heat_haze`).
//
// The aura hammers one block up to fifteen times a second. This is the air above that block: a
// world-anchored heat plume that shoves the scene around the way hot air does, so the spam site
// reads as a heat source rather than as a marker sitting on the ground.
//
// The technique is the original's, from
// `rockstar-client-src/src/main/resources/assets/rockstar/shaders/core/heat_haze/fragment.fsh`:
// the `hash`/`vnoise` value noise, the mostly-horizontal wobble with a slow vertical breath, and
// the vertical chromatic split that sells hot glass. Two things are ours:
//
//   1. **Where the heat comes from.** The original reads a flame buffer in screen space. We have
//      no such buffer, so the heat field is built from up to MAX_SITES world-anchored sites: each
//      is a camera-relative point plus its current heat, and its field is the perpendicular
//      distance from the site to this pixel's view ray -- the silhouette field of a sphere, the
//      same one `blast_dome.fsh` uses. That is projection-correct at any FOV and needs no
//      projected screen position.
//
//   2. **How "heat rises" is integrated.** The original walks DOWN the screen collecting flame
//      from below the pixel. Down the screen is only vertical by accident, so instead the site is
//      walked UP the world: sampling the field against `centre + (0, k * rise, 0)` asks "is there
//      a site k blocks below this pixel", which is the same question, world-anchored, and costs
//      one dot product per step instead of an unprojection.
//
// Everything fades continuously. There is no early `return` that would leave a border: the front
// test, the depth occlusion and the plume's own falloff are smoothsteps folded into one coverage
// value, which is also the premultiplied output alpha -- at coverage 0 the pixel written is the
// pixel already there. This is `blast_dome.fsh`'s arrangement and it exists for the same reason.
//
// SceneSampler is a *copy* of the finished world (see `scene_copy.fsh`) -- this pass writes into
// the main colour target, so it cannot read it. DepthSampler is the main target's depth
// attachment, bound as a texture; the pass is created colour-only so that is not a feedback loop.
#version 330 core

#define MAX_SITES 4

// Steps of the rise integration. The k = 0 step is the core and is handled separately.
#define RISE_STEPS 6

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D SceneSampler; // snapshot of the finished world
uniform sampler2D DepthSampler; // the main target's depth attachment

layout(std140) uniform AuraForgeData {
    // inverse of projection * modelView for this frame -- unprojects straight to camera-relative
    mat4 invProjView;
    // x = live site count, y = 1 when the device clips depth to [0,1] instead of [-1,1],
    // z = global strength, w = time in seconds
    vec4 control;
    // x = aspect (width / height), y = site radius in blocks, z = rise height in blocks,
    // w = peak displacement in UV units
    vec4 shape;
    // rgb = the hot colour the plume tints towards, a = how much of it to add at full heat
    vec4 hot;
    // xyz = site centre relative to the camera, w = its heat, 0..1
    vec4 sites[MAX_SITES];
};

// Never let the plume fight the surface its site is standing on.
const float OCCLUDE_BIAS = 0.05;

// Where the lobe of one site starts and finishes falling off, in radii.
const float LOBE_INNER = 0.45;
const float LOBE_OUTER = 1.0;

// How much wider the lobe gets per unit of rise, and how much dimmer at the top of the plume.
const float LOBE_SPREAD = 0.85;
const float LOBE_DECAY = 0.85;

// The core mask: how hard the effect is damped on the site itself, so the forge stays crisp.
const float CORE_MASK_IN = 0.20;
const float CORE_MASK_OUT = 0.85;

// Below this the contribution is invisible and the pixel is left exactly as it was.
const float MIN_COVER = 0.002;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

// Smooth value noise -- verbatim from the original.
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Perpendicular distance from `centre` to the ray leaving the camera along `rayDir`.
float perpDistance(vec3 centre, vec3 rayDir) {
    float b = dot(rayDir, centre);
    return sqrt(max(0.0, dot(centre, centre) - b * b));
}

// How strongly a site covers this ray: 1 on the axis, 0 past `radius * radiusScale`, smooth between.
float lobe(vec3 centre, vec3 rayDir, float radius, float radiusScale) {
    float d = perpDistance(centre, rayDir) / (radius * radiusScale);
    return 1.0 - smoothstep(LOBE_INNER, LOBE_OUTER, d);
}

void main() {
    vec2 uv = texCoord;
    vec2 ndc = uv * 2.0 - 1.0;

    // Camera-relative ray through this pixel: the far-plane point, normalised. Deliberately not
    // divided by w -- Minecraft's far plane is effectively infinite, so w sits on top of zero and
    // for a DIRECTION only the sign of w matters.
    vec4 farRel = invProjView * vec4(ndc, 1.0, 1.0);
    vec3 rayDir = normalize(farRel.w < 0.0 ? -farRel.xyz : farRel.xyz);

    // Distance to the scene along that ray. The sky carries no geometry, so it stays effectively
    // infinite and never occludes anything.
    float sceneDist = 1.0e9;
    float depth = texture(DepthSampler, uv).r;
    if (depth < 1.0) {
        float z = control.y > 0.5 ? depth : depth * 2.0 - 1.0;
        vec4 sceneRel = invProjView * vec4(ndc, z, 1.0);
        sceneDist = length(sceneRel.xyz / max(sceneRel.w, 1e-6));
    }

    float count = control.x;
    float strength = max(control.z, 0.0);
    float radius = max(shape.y, 1e-3);
    float rise = max(shape.z, 0.0);

    float plume = 0.0;
    float core = 0.0;

    for (int i = 0; i < MAX_SITES; i++) {
        vec4 site = sites[i];
        vec3 centre = site.xyz;

        // Live at all, and how hot.
        float active = step(float(i) + 0.5, count) * clamp(site.w, 0.0, 1.0);

        // The site has to be in front of us, and not behind a wall.
        float b = dot(rayDir, centre);
        float front = smoothstep(0.0, radius * 0.35, b);
        float bias = max(OCCLUDE_BIAS, sceneDist * 0.005);
        float visible = 1.0 - smoothstep(0.0, bias * 2.0, b - (sceneDist + bias));
        float gate = active * front * visible;

        // k = 0 is the site itself: the core, which damps the effect instead of feeding it.
        core = max(core, lobe(centre, rayDir, radius, 1.0) * gate);

        // Heat rises, so a pixel is warmed by a site BELOW it: walk the site up the world and ask
        // the same question at every step, fading with height.
        for (int s = 1; s < RISE_STEPS; s++) {
            float k = float(s) / float(RISE_STEPS - 1);
            vec3 lifted = centre + vec3(0.0, k * rise, 0.0);
            float wide = 1.0 + k * LOBE_SPREAD;
            plume = max(plume, lobe(lifted, rayDir, radius, wide) * (1.0 - k * LOBE_DECAY) * gate);
        }
    }

    float mask = 1.0 - smoothstep(CORE_MASK_IN, CORE_MASK_OUT, core);
    float cover = clamp(plume * mask, 0.0, 1.0);
    if (cover < MIN_COVER) {
        discard;
    }

    // Noise in aspect-corrected space, scrolling upwards over time -- the original's three octaves.
    vec2 p = uv * vec2(shape.x, 1.0);
    float t = control.w;
    float n1 = vnoise(p * 22.0 + vec2(t * 0.60, -t * 1.6));
    float n2 = vnoise(p * 41.0 + vec2(-t * 0.45, -t * 2.7));
    float n3 = vnoise(p * 11.0 + vec2(t * 0.20, -t * 0.9));

    // Mostly horizontal shimmer plus a slow vertical breath.
    float wobX = (n1 - 0.5) * 2.0 + (n2 - 0.5);
    float wobY = (n3 - 0.5) * 0.6;

    float amp = cover * strength * max(shape.w, 0.0);
    vec2 duv = clamp(uv + vec2(wobX, wobY) * amp, vec2(0.0), vec2(1.0));

    // A light vertical channel split: hot glass refracts unevenly.
    float chroma = amp * 0.25;
    float r = textureLod(SceneSampler, clamp(duv + vec2(0.0, chroma), vec2(0.0), vec2(1.0)), 0.0).r;
    float g = textureLod(SceneSampler, duv, 0.0).g;
    float b = textureLod(SceneSampler, clamp(duv - vec2(0.0, chroma), vec2(0.0), vec2(1.0)), 0.0).b;
    vec3 col = vec3(r, g, b) + hot.rgb * (max(hot.a, 0.0) * strength * cover);

    // Premultiplied: the displaced colour fades in over whatever the destination already holds,
    // which at coverage 0 is the very same pixel -- no border, ever. At strength 0 both the offset
    // and the tint vanish, so the pass writes the scene back unchanged.
    fragColor = vec4(col * cover, cover);
}
