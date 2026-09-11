// Crystal Aura -- soft glow composite.
//
// Same shape as the Halo ESP combine pass (`shaders/esphalo/halo.fsh`): the module's geometry is
// drawn once into an offscreen silhouette, that silhouette is blurred through a dual-Kawase
// pyramid, and this pass adds the two back together over the scene.
//
// SharpSampler = the sharp silhouette, full resolution, exactly as the module drew it.
// BlurSampler = level 0 of the blur pyramid, i.e. the same silhouette spread out.
//
// Two contributions, both continuous, neither with a border:
//   - the halo: the blurred coverage raised to `falloff` and scaled by `strength`, damped where
//     the sharp source already covers the pixel so the core does not wash out into a white blob;
//   - the sharp source itself, composited *over* the halo so the geometry stays readable.
//
// The result is a premultiplied "over":
//   out = rgb + scene * (1 - a)
// with `a` carrying only the sharp source's own alpha. The halo therefore lands additively (a = 0
// outside the silhouette) while the interior blends normally -- one pass, both behaviours, and
// every term varies smoothly with coverage so nothing can pop between frames.
//
// LiquidBounce gets that blend from the pipeline's blend state; a post-chain pass has no blend
// state to set, it replaces its output. So the world is copied aside first and SceneSampler holds
// that copy: the same equation, spelled out here instead of handed to fixed-function hardware.
// Where neither layer reaches a pixel this discards instead, which leaves the world untouched and
// is the same answer for less work.
//
// Colour handling is deliberately *not* the halo's hue normalisation: the silhouette target is
// cleared to transparent and drawn into with a straight over-blend, so its rgb is already
// premultiplied by coverage and can be used as-is. That preserves the module's actual colours
// (a dim blue stays dim) instead of forcing every tint to full brightness.
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D SharpSampler; // sharp silhouette
uniform sampler2D BlurSampler; // blurred silhouette
uniform sampler2D SceneSampler; // snapshot of the finished world, which this pass writes over

layout(std140) uniform AuraGlowData {
    // x = glow strength, y = glow falloff, z = overall alpha, w = interior damping (0..1)
    vec4 params;
};

float maxChan(vec3 c) {
    return max(c.r, max(c.g, c.b));
}

// How much of a texel the silhouette covers.
//
// Alpha is the real mask for anything drawn with an over-blend; the colour channels are folded in
// so additively drawn geometry (which leaves alpha at zero) still registers as coverage.
float coverage(vec4 c) {
    return clamp(max(c.a, maxChan(c.rgb)), 0.0, 1.0);
}

void main() {
    vec4 sharp = textureLod(SharpSampler, texCoord, 0.0);
    vec4 blurred = textureLod(BlurSampler, texCoord, 0.0);

    float sc = coverage(sharp);
    float bc = coverage(blurred);

    // nothing of either layer reaches this pixel
    if (sc < 0.0015 && bc < 0.0015) {
        discard;
    }

    float alpha = clamp(params.z, 0.0, 1.0);
    float strength = max(params.x, 0.0);
    float falloff = max(params.y, 0.05);
    float damp = clamp(params.w, 0.0, 1.0);

    // Recover the blurred colour from its premultiplied form, then re-apply coverage through the
    // falloff curve. Dividing by `bc` (which is at least the largest colour channel) can never
    // amplify past the original colour, so the 8-bit tail of the blur cannot bloom into a rim.
    vec3 blurTint = blurred.rgb / max(bc, 1e-4);
    vec3 glow = blurTint * (pow(bc, falloff) * strength);

    // The halo is at full power out in the open air and fades back inside the silhouette, where
    // the sharp source is about to be drawn on top of it anyway.
    glow *= mix(1.0, damp, sc);

    // Premultiplied "over" for the sharp layer: rgb is already colour * coverage.
    float coreA = clamp(sharp.a, 0.0, 1.0) * alpha;
    vec3 rgb = (sharp.rgb + glow) * alpha;

    if (maxChan(rgb) < 0.002 && coreA < 0.002) {
        discard;
    }

    vec4 scene = texture(SceneSampler, texCoord);
    fragColor = vec4(rgb + scene.rgb * (1.0 - coreA), coreA + scene.a * (1.0 - coreA));
}
