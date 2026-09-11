/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.aerial.client.features.impl.combat.crystalaura.config;

import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.NumberProperty;

/**
 * Render settings of the detonation overlay: which of its parts are drawn and how.
 *
 * <p>The parts are independent — the boxes are the plan, the ring is the target's kill zone, the
 * shockwave is the history of our own attacks, and the text is the numbers behind the decision.
 * Every part is animated, and {@link #animationSpeed} scales all of it at once.
 *
 * <p>HOW they are drawn is the second block, from {@link #soft} down: angle-weighted faces, graded
 * fills, feathered outlines and a distance falloff, all of it plain geometry. Those change only the
 * look, never which elements exist, and {@link #soft} switches the whole block back to flat boxes
 * and one-pixel outlines.
 *
 * <p>Colours are not settings here. Aerial has no colour property, so the palette lives as constants
 * in {@code render.CrystalColors}, with accent-derived tones taken from the client theme.
 */
public final class RenderSettings {
    /** Draw the planned placement as a box. */
    public final BooleanProperty box = new BooleanProperty("Box", true);

    /**
     * Draw the blast ring on the ground at the target's feet: a band at the kill-zone radius, the
     * largest distance at which a fully exposed crystal would still deal at least the configured
     * minimum damage to this target. Nothing is drawn when even a point-blank crystal misses that
     * threshold.
     */
    public final BooleanProperty blastRing = new BooleanProperty("BlastRing", true);

    /**
     * Cut the blast ring into one arc per crystal the target still survives,
     * {@code ceil((health + absorption) / damage)}, at most 12. A single remaining crystal makes the
     * whole ring pulse.
     */
    public final BooleanProperty killSegments = new BooleanProperty("KillSegments", true);

    /** Fill the sector of the ring in which the target's raised shield eats our crystals entirely. */
    public final BooleanProperty shieldWedge = new BooleanProperty("ShieldWedge", true);

    /** Expanding ground ripple at every crystal whose attack packet really went out. */
    public final BooleanProperty shockwave = new BooleanProperty("Shockwave", true);

    /**
     * Extra flourish on every ripple: a ring of short vertical ticks orbiting the wave. Off by
     * default; the plain ripple is the quieter read.
     */
    public final BooleanProperty orbit = new BooleanProperty("Orbit", false);

    /** Target damage and self damage of the planned placement, drawn over its block. */
    public final BooleanProperty damageText = new BooleanProperty("DamageText", true);

    /**
     * Divides the duration of EVERY tween of the overlay: 2.0 makes the whole overlay settle in half
     * the time, 0.5 doubles it. It is applied when a tween is read, so moving this while an animation
     * is running affects that animation too.
     */
    public final NumberProperty animationSpeed = new NumberProperty("AnimationSpeed", 1, 0.25, 3, 0.25);

    /**
     * Draw the whole overlay through the soft geometry path: angle-weighted faces, graded fills,
     * feathered outlines and a distance falloff.
     *
     * <p>Turning it off restores the original look exactly — flat one-tone box faces and one-pixel
     * line outlines — which is what every setting below stops affecting.
     */
    public final BooleanProperty soft = new BooleanProperty("Soft", true);

    /** Width of the feathered outline, in blocks. */
    public final NumberProperty outlineWidth = new NumberProperty("OutlineWidth", 0.02, 0.005, 0.08, 0.005);

    /** How far the fill fades out towards a face edge. */
    public final NumberProperty feather = new NumberProperty("Feather", 0.5, 0, 1, 0.01);

    /** Strength of the view-angle rim brightening. */
    public final NumberProperty fresnel = new NumberProperty("Fresnel", 1.4, 0, 4, 0.05);

    /** Floor for the face-angle weighting, so a face seen edge-on never vanishes entirely. */
    public final NumberProperty faceMin = new NumberProperty("FaceMin", 0.15, 0, 1, 0.01);

    /** Vertical gradient across a filled face. */
    public final NumberProperty gradient = new NumberProperty("Gradient", 0.35, 0, 2, 0.05);

    /** Distance at which the overlay starts fading, in blocks. */
    public final NumberProperty fadeStart = new NumberProperty("FadeStart", 24, 0, 128, 1);

    /** Distance at which the overlay has faded out completely, in blocks. */
    public final NumberProperty fadeEnd = new NumberProperty("FadeEnd", 64, 0, 256, 1);

    /**
     * Soft additive halo around everything the module draws: the geometry is rendered a second time
     * into an offscreen target, blurred and composited underneath itself. This is what makes the
     * overlay read as light rather than as a decal.
     */
    public final BooleanProperty glow = new BooleanProperty("Glow", true);

    /** Halo reach, 0..1 — drives how deep the blur pyramid goes and how wide it is upsampled. */
    public final NumberProperty glowRadius = new NumberProperty("GlowRadius", 0.55, 0, 1, 0.01);

    /** Halo brightness. Higher blows the rim out to white; 0 leaves the sharp geometry alone. */
    public final NumberProperty glowStrength = new NumberProperty("GlowStrength", 1.35, 0, 4, 0.05);

    /** Exponent on the blurred coverage: around 1 is air and haze, around 3 is a thin bright rim. */
    public final NumberProperty glowFalloff = new NumberProperty("GlowFalloff", 1.2, 0.2, 4, 0.05);

    /** Opacity of the whole halo layer; 0 skips the offscreen target entirely. */
    public final NumberProperty glowAlpha = new NumberProperty("GlowAlpha", 0.85, 0, 1, 0.01);

    /**
     * Extra alpha the geometry is drawn with WHEN IT IS ROUTED INTO THE HALO, and only then.
     *
     * <p>The halo is derived from the colours of the geometry handed to it: the silhouette is blurred
     * and composited back, so it is only ever as bright as what was drawn into it. That is a problem
     * for this module specifically, because two of its own defaults conspire against it — the face
     * colour is alpha 40 of 255, and {@link #faceMin} then scales a face seen head-on down to another
     * 15 percent of that. The blur therefore receives something very close to black and the halo has
     * nothing to work with.
     *
     * <p>This multiplies the alpha of the glow pass ONLY: the world pass keeps drawing at exactly the
     * alpha chosen, while the halo gets a source it can actually blur. Raising the fill alpha instead
     * would work too, but it would also make the geometry itself heavier — which is the look the soft
     * path exists to avoid.
     */
    public final NumberProperty glowGain = new NumberProperty("GlowGain", 3, 1, 8, 0.5);

    /**
     * Screen-space shockwave: a world-anchored dome that REFRACTS the scene behind it instead of
     * drawing a ring, occluded by real geometry through the depth buffer. Costs one full-screen pass
     * per frame while a dome is live.
     */
    public final BooleanProperty dome = new BooleanProperty("Dome", true);

    /** Overall displacement multiplier; 0 leaves the scene untouched. */
    public final NumberProperty domeStrength = new NumberProperty("DomeStrength", 1, 0, 3, 0.05);

    /** Peak displacement of the refraction, in screen fractions. */
    public final NumberProperty domeAmplitude = new NumberProperty("DomeAmplitude", 0.026, 0, 0.12, 0.002);

    /** How many concentric waves fill the dome. */
    public final NumberProperty domeRipples = new NumberProperty("DomeRipples", 3, 0.5, 8, 0.5);

    /**
     * The forge: the block the aura is spamming crystals on, drawn as something being worked rather
     * than as a marker. Every detonation adds heat to that site, the heat decays over about a second,
     * and everything the effect draws is scaled by it — so one crystal is a flicker and a sustained
     * cycle lights the site up.
     *
     * <p>This is the world-space half: an ADDITIVE pool on the ground, its feathered rim, three slowly
     * turning spokes and a crown of rising embers. Additive because it has to read at a glance
     * whatever the fill colour is, which by default is alpha 40.
     */
    public final BooleanProperty forge = new BooleanProperty("Forge", true);

    /**
     * The screen-space half: a world-anchored heat plume over the site that displaces the scene the
     * way hot air does, with a vertical channel split for hot glass. This is the expensive one — two
     * full-screen passes per frame while any site is warm — so it has its own toggle; the pool stays.
     */
    public final BooleanProperty forgeHaze = new BooleanProperty("ForgeHaze", true);

    /** Displacement and tint of the plume. 0 skips the passes entirely and leaves the pool on its own. */
    public final NumberProperty forgeStrength = new NumberProperty("ForgeStrength", 1, 0, 3, 0.05);

    /** Footprint of a site in blocks: the pool's radius, and the plume's width before it spreads upwards. */
    public final NumberProperty forgeRadius = new NumberProperty("ForgeRadius", 2.5, 0.5, 8, 0.5);

    private final GroupProperty group;

    public RenderSettings() {
        outlineWidth.hideIf(() -> !soft.getValue());
        feather.hideIf(() -> !soft.getValue());
        fresnel.hideIf(() -> !soft.getValue());
        faceMin.hideIf(() -> !soft.getValue());
        gradient.hideIf(() -> !soft.getValue());
        fadeStart.hideIf(() -> !soft.getValue());
        fadeEnd.hideIf(() -> !soft.getValue());
        glowRadius.hideIf(() -> !glow.getValue());
        glowStrength.hideIf(() -> !glow.getValue());
        glowFalloff.hideIf(() -> !glow.getValue());
        glowAlpha.hideIf(() -> !glow.getValue());
        glowGain.hideIf(() -> !glow.getValue());
        domeStrength.hideIf(() -> !dome.getValue());
        domeAmplitude.hideIf(() -> !dome.getValue());
        domeRipples.hideIf(() -> !dome.getValue());
        forgeHaze.hideIf(() -> !forge.getValue());
        forgeStrength.hideIf(() -> !forge.getValue());
        forgeRadius.hideIf(() -> !forge.getValue());
        this.group = new GroupProperty("Render",
                box, blastRing, killSegments, shieldWedge, shockwave, orbit, damageText,
                animationSpeed, soft, outlineWidth, feather, fresnel, faceMin, gradient, fadeStart, fadeEnd,
                glow, glowRadius, glowStrength, glowFalloff, glowAlpha, glowGain,
                dome, domeStrength, domeAmplitude, domeRipples,
                forge, forgeHaze, forgeStrength, forgeRadius);
    }

    public GroupProperty get() {
        return group;
    }
}
