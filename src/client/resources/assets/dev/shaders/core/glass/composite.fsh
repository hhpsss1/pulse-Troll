#version 330

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D SceneSampler;
uniform sampler2D BlurSampler;
uniform sampler2D MaskSampler;
uniform sampler2D ItemSampler;  // the frame with the hands already drawn into it
uniform sampler2D GateSampler;  // mask, downscaled 8x and dilated by the effect's reach

layout(std140) uniform GlassData {
    vec4 resolution;   // x=width, y=height, z=saturation, w=chaosSize(px)
    vec4 tintColor;    // rgb tint, a unused
    vec4 settings;     // x=tintIntensity, y=overlay, z=edgeSoftness, w=reflectAmount
    vec4 iceParams;    // x=time(sec), y=distortStrength, z=distortWidth(px), w=chaos
    vec4 smokeParams;  // x=smokeAmount, y=smokeScale, z=smokeSpeed, w=smokeReach(px)
};

// ---------- noise helpers ----------
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        v += amp * vnoise(p);
        p *= 2.02;
        amp *= 0.5;
    }
    return v;
}


vec3 adjustSaturation(vec3 color, float saturation) {
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    return mix(vec3(gray), color, saturation);
}

// soft halo: how close an outside pixel is to the hand edge (0..1)
float edgeHalo(vec2 uv, float reachPx) {
    vec2 px = reachPx / resolution.xy;
    float halo = 0.0;
    for (int r = 1; r <= 3; r++) {
        float rf = float(r) / 3.0;
        for (int a = 0; a < 8; a++) {
            float ang = float(a) * 0.7853981634; // 2pi/8
            vec2 off = vec2(cos(ang), sin(ang)) * px * rf;
            float m = texture(MaskSampler, uv + off).r;
            halo = max(halo, m * (1.0 - rf * 0.85));
        }
    }
    return clamp(halo, 0.0, 1.0);
}

// smoothed mask — averaged disk, used both for the anti-aliased silhouette
// and to build the refraction normal. 12 directions × 2 radii = soft gradient.
float softMask(vec2 uv, float r) {
    vec2 px = r / resolution.xy;
    float s = texture(MaskSampler, uv).r;
    float w = 1.0;
    for (int i = 0; i < 12; i++) {
        float a = float(i) * 0.5235987756; // 2pi/12
        vec2 d = vec2(cos(a), sin(a));
        s += texture(MaskSampler, uv + d * px).r * 0.6;
        s += texture(MaskSampler, uv + d * px * 0.55).r * 0.9;
        w += 1.5;
    }
    return s / w;
}

// wide multi-ring blur of the mask — rounds off the blocky silhouette so the
// glass edge is smooth (no stair-stepping). Run only near the hand.
float edgeCoverage(vec2 uv, float r) {
    vec2 px = r / resolution.xy;
    float s = texture(MaskSampler, uv).r;
    float w = 1.0;
    for (int ring = 1; ring <= 4; ring++) {
        float rr = float(ring) / 4.0;
        float wr = 1.0 - rr * 0.45;
        for (int i = 0; i < 12; i++) {
            float a = float(i) * 0.5235987756 + rr * 0.6; // stagger angles per ring
            vec2 d = vec2(cos(a), sin(a)) * px * rr;
            s += texture(MaskSampler, uv + d).r * wr;
            w += wr;
        }
    }
    return s / w;
}

// fire mist source: how much of the hand silhouette lies below this pixel,
// with a slight sideways wobble so the rising column isn't perfectly straight.
float smokeSource(vec2 uv, float reachPx, float t, float spd) {
    float src = 0.0;
    for (int i = 0; i <= 8; i++) {
        float f = float(i) / 8.0;
        float wob = (vnoise(vec2(uv.x * 40.0, t * spd * 1.5 + f * 4.0)) - 0.5)
                  * reachPx * 0.30;
        vec2 off = vec2(wob / resolution.x, -(f * reachPx) / resolution.y);
        float m = texture(MaskSampler, uv + off).r;
        src = max(src, m * (1.0 - f * 0.80));
    }
    return src;
}

void main() {
    // Out of reach of the hands this pass would write the scene back over itself unchanged.
    // Discarding skips the read, the write and the twenty-five mask taps of the coverage blur —
    // and the framebuffer already holds exactly what would have been written.
    if (texture(GateSampler, texCoord).r < 0.004) {
        discard;
    }

    vec4 scene = texture(SceneSampler, texCoord);
    float maskValue = texture(MaskSampler, texCoord).r;

    float time = iceParams.x;
    float chaos = iceParams.w;
    float smokeAmount = smokeParams.x;
    float smokeScale = max(0.5, smokeParams.y);
    float smokeSpeed = smokeParams.z;
    float smokeReach = smokeParams.w;

    float saturation = resolution.z;
    float chaosSize = max(4.0, resolution.w);   // width of one swell, in screen pixels
    float tintIntensity = settings.x;

    float refractStrength = iceParams.y;          // how hard the edges bend
    float refractReach = max(2.0, iceParams.z);   // width of the distortion band (px)

    // edge-smoothing radius in px, driven from Java (settings.z).
    // 0 = silhouette copies the item exactly, larger = molten glass rim.
    float edgeSmoothPx = max(0.0, settings.z);

    float reflectAmount = clamp(settings.w, 0.0, 1.0);  // strength of the reflection laid on top
    float overlayMode = settings.y;

    // Radius the refraction normal is measured over — this *is* the distortion width. Below a
    // few pixels the gradient is mask noise rather than a surface, hence the floor.
    float gradPx = max(edgeSmoothPx, refractReach);

    // ---- cheap early-out: skip work for pixels well away from the hand ----
    float distorting = (refractStrength > 0.001 || chaos > 0.001) ? 1.0 : 0.0;
    // The chaos term is gone from here along with the displaced mask read: coverage is now
    // decided at texCoord, so nothing outside the plain silhouette can be reached.
    float dilatePx = (distorting > 0.5 ? gradPx : max(edgeSmoothPx, 1.0)) + 2.0;
    vec2 dpx = vec2(dilatePx) / resolution.xy;
    float md = maskValue;
    md = max(md, texture(MaskSampler, texCoord + vec2(dpx.x, 0.0)).r);
    md = max(md, texture(MaskSampler, texCoord - vec2(dpx.x, 0.0)).r);
    md = max(md, texture(MaskSampler, texCoord + vec2(0.0, dpx.y)).r);
    md = max(md, texture(MaskSampler, texCoord - vec2(0.0, dpx.y)).r);

    float fireSrc = 0.0;
    if (smokeAmount > 0.001) {
        fireSrc = smokeSource(texCoord, smokeReach, time, smokeSpeed);
    }

    // Discard rather than write the scene back. Writing it replaces the finished frame with the
    // capture taken before the hands were drawn, which erases anything the mask did not catch —
    // and since the gate above skips this pass entirely further out, the two behaviours met
    // along the gate's 8-pixel grid and left a stepped edge. Leaving the pixel alone is both
    // correct and free.
    if (md < 0.01 && fireSrc < 0.004) {
        discard;
    }

    // ---------------- liquid glass ----------------
    // Nothing here paints anything: chaos is displacement only, so what you see is the image
    // behind the pane sliding around, the way it does through moving glass. Colouring the field
    // instead would just lay blotches over the item.
    //
    // The field is a domain warp — fbm sampled once, then sampled again at coordinates the first
    // pass pushed around. That second pass is what turns even noise into slow rolling lobes; a
    // single fbm on its own only shimmers in place. Time drifts both passes, at different rates,
    // so the lobes travel and fold instead of pulsing.
    vec2 chaosPx = vec2(0.0);   // extra displacement, in screen pixels
    if (chaos > 0.001) {
        float aspect = resolution.x / resolution.y;
        // One noise unit is exactly `chaosSize` screen pixels, so the dial reads as the width of
        // a single swell. Independent of the refraction band — that one is about the rim, this
        // one is about the liquid across the whole pane.
        float freq = resolution.y / chaosSize;
        vec2 fuv = vec2(texCoord.x * aspect, texCoord.y) * freq;
        float t = time * 0.35;

        // Domain warp: fbm evaluated at coordinates a first fbm has already pushed around.
        //
        // The field this builds is smooth everywhere, and that is the entire point. The voronoi
        // droplets that stood here could not be — the vector to a cell's seed flips direction at
        // every wall, so the displacement jumped across those lines and the image tore along
        // them into duplicated slabs instead of flowing. A warped fbm has no such seams: the
        // picture rolls in rounded swells and folds back into itself.
        // Two octaves of plain value noise, and deliberately no more.
        //
        // `fbm` stacks five octaves down to a thirty-second of the swell, and the warped-fbm
        // field that stood here fed its own output back in twice on top of that — every one of
        // those terms is a small wiggle, which is what put the fine curly striations in the
        // glass. Value noise on its own is smoothstep-interpolated between lattice points: one
        // rounded lobe per unit, C1 continuous, nothing finer anywhere in it.
        //
        // The second octave is *lower* in frequency, not higher, so it varies the lenses across
        // the pane instead of adding detail inside them.
        vec2 flow = vec2(
            vnoise(fuv + vec2(0.0, t * 0.50))
                + 0.5 * vnoise(fuv * 0.5 + vec2(3.1, 7.4) + vec2(0.0, t * 0.22)),
            vnoise(fuv + vec2(4.7, 2.3) - vec2(t * 0.35, 0.0))
                + 0.5 * vnoise(fuv * 0.5 + vec2(9.6, 1.8) - vec2(t * 0.16, 0.0))
        ) / 1.5;

        // Travel is on the order of a whole lens. The pane is filled with a blurred image, so a
        // small shift moves nothing the eye can catch — the lens has to drag its own width for
        // the boundary between two colour fields to read as rolling glass.
        chaosPx = (flow - 0.5) * 2.0 * chaos * chaosSize * 0.9;
        chaosPx /= vec2(aspect, 1.0);
    }

    // silhouette coverage (0 = scene, 1 = full glass).
    // tiny radius -> 1px anti-alias only, so corners stay corners.
    //
    // Read at texCoord exactly, never through the flow. Sampling the mask at a displaced
    // coordinate moved the whole pane off the hand — the further the liquid travelled, the
    // further the glass sat from the item it belongs to. The liquid moves what is *inside* the
    // pane; where the pane is, is the item's business alone.
    float cov = edgeSmoothPx <= 0.75
              ? softMask(texCoord, 1.0)
              : edgeCoverage(texCoord, edgeSmoothPx);
    float coverage = smoothstep(0.45, 0.55, cov);

    // Fully outside the silhouette the blend below resolves to the scene as it already is, so
    // there is nothing to write — and this skips the refraction gradient and the blur read.
    if (coverage < 0.001 && smokeAmount <= 0.001) {
        discard;
    }

    // ---------------- refractive glass (lens) ----------------
    // gradient of the smoothed coverage -> the surface normal of the glass.
    // large near the rim (strong bend), ~0 in the centre (clear glass).
    // skipped entirely without refraction — that is 4 wide taps per pixel saved
    vec2 grad = vec2(0.0);
    if (refractStrength > 0.001) {
        vec2 px = vec2(gradPx) / resolution.xy;
        float sx = edgeCoverage(texCoord + vec2(px.x, 0.0), gradPx)
                 - edgeCoverage(texCoord - vec2(px.x, 0.0), gradPx);
        float sy = edgeCoverage(texCoord + vec2(0.0, px.y), gradPx)
                 - edgeCoverage(texCoord - vec2(0.0, px.y), gradPx);
        grad = vec2(sx, sy);
    }
    // The gradient is measured over `gradPx`, so a wider band spreads the bend over more of the
    // silhouette; scaling by it keeps the sampled offset growing with the band rather than
    // flattening out as the gradient itself gets shallower.
    vec2 disp = grad * refractStrength * 0.06 * (refractReach / 25.0);
    disp += chaosPx / resolution.xy;

    // The pane samples straight through itself. It used to point-reflect the coordinate about
    // the centre of the screen first (`center - offset * 0.3 + offset`), which pulled in a
    // squashed copy of the far side of the frame — the ghost image sitting under the item. That
    // is not a reflection any glass would make, and it rode along with the Distortion toggle.
    vec2 blurUV = texCoord + disp;

    vec4 blur = texture(BlurSampler, blurUV);
    vec3 glassColor = blur.rgb;

    glassColor = adjustSaturation(glassColor, saturation);

    if (tintIntensity > 0.001) {
        glassColor = mix(glassColor, tintColor.rgb, tintIntensity);
    }

    // soft glassy highlight along the refracting rim
    float rim = smoothstep(0.0, 1.0, length(grad) * refractStrength * 1.5);
    glassColor += rim * 0.12 * vec3(0.85, 0.92, 1.0);

    // Overlaid, the item is the base and the glass goes on as a reflection over it.
    //
    // Not a linear mix — that was the mistake. Mixing in even a fifth of a blurred, flowing
    // world replaces a fifth of every pixel of the blade with something soft and moving, and
    // the hand reads as smeared no matter how far the dial is turned down. A screen blend only
    // ever *adds* light: the item's own texture survives at full strength underneath and the
    // pane contributes highlights, tint and the moving reflection on top of it, which is what
    // glass laid over a solid object actually does.
    // Where it lands matters as much as how it blends. A screen blend at a flat strength puts
    // the whole reflected world over every pixel of the hand at once, and an even veil over
    // everything is exactly what reads as the hand having gone see-through. Real glass reflects
    // in *places*: hard at grazing angles near the rim, along whatever the surface is doing, and
    // more of what is bright behind you than of what is dark. Those three terms gate it here, so
    // the middle of the blade stays its own colour and the reflection lives in moving patches.
    if (overlayMode > 0.5) {
        vec3 item = texture(ItemSampler, texCoord).rgb;

        // grazing angle: 0 deep inside the silhouette, 1 approaching its edge
        float inner = softMask(texCoord, 14.0);
        float grazing = 1.0 - smoothstep(0.45, 0.95, inner);

        // where the liquid is actually tilting the surface this frame
        float tilt = clamp(length(chaosPx) / max(1.0, chaosSize * 0.45), 0.0, 1.0);

        // bright things reflect, dark things barely do
        float lum = dot(glassColor, vec3(0.299, 0.587, 0.114));

        float local = clamp(grazing * 0.85 + tilt * 0.75, 0.0, 1.0) * (0.30 + 0.70 * lum);
        vec3 reflection = glassColor * reflectAmount * local;
        glassColor = 1.0 - (1.0 - item) * (1.0 - reflection);
    }

    glassColor = clamp(glassColor, vec3(0.0), vec3(1.0));

    // feathered blend background <-> glass: smooth rounded edge, no jaggies
    fragColor = vec4(mix(scene.rgb, glassColor, coverage), 1.0);

    // ---------------- fire mist (rising smoke) ----------------
    if (smokeAmount > 0.001) {
        // mist also licks over the item itself, not only above it
        float src = max(fireSrc, maskValue * 0.55);
        if (src > 0.004) {
            float aspect = resolution.x / resolution.y;
            vec2 nuv = vec2(texCoord.x * aspect, texCoord.y);
            float t1 = time * smokeSpeed;

            // two fbm layers scrolling up at different speeds = live, curling wisps
            float n1 = fbm(nuv * smokeScale + vec2(0.0, -t1 * 1.4));
            float n2 = fbm(nuv * smokeScale * 2.1 + vec2(4.7, -t1 * 2.4));
            float wisp = clamp(n1 * 0.62 + n2 * 0.55, 0.0, 1.0);
            wisp = smoothstep(0.28, 0.88, wisp);

            float mist = smokeAmount * src * wisp;

            // hot core near the silhouette, cooler tinted haze further away
            vec3 haze = mix(vec3(0.80, 0.88, 1.00), tintColor.rgb, 0.65);
            vec3 core = mix(vec3(1.0), tintColor.rgb, 0.35);
            vec3 mistCol = mix(haze, core, clamp(src * src, 0.0, 1.0));

            fragColor.rgb = clamp(fragColor.rgb + mistCol * mist, 0.0, 1.0);
        }
    }
}
