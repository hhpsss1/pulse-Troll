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

package cc.aerial.client.features.impl.combat.crystalaura.render;

import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.features.impl.combat.crystalaura.config.RenderSettings;
import net.minecraft.world.phys.Vec3;

/**
 * The forge's world-space half: an additive pool on the ground at the site the aura is working, its feathered
 * rim, three slowly turning spokes, and a crown of rising embers.
 *
 * <p>Everything is scaled by the site's heat, so the whole thing grows out of nothing and shrinks back into
 * it. Additive, because it has to read at a glance whatever the fill colour is — which by default is barely
 * opaque.
 */
final class AuraForgeGeometry {
    private AuraForgeGeometry() {
    }

    /** Lift off the block below, so the additive pool does not fight the surface it lies on. */
    private static final double GROUND_LIFT = 0.06;
    private static final double HALF_BLOCK = 0.5;

    /** The pool's radius as a fraction of the setting: this much always, plus this much times the heat. */
    private static final float POOL_BASE = 0.55f;
    private static final float POOL_GAIN = 0.45f;

    /** How much dimmer the rim is than the middle. */
    private static final float RIM_RATIO = 0.4f;

    /** The pool's breath: a fixed-rate sine whose AMPLITUDE is the heat. */
    private static final long PULSE_MS = 620L;
    private static final float PULSE_AMOUNT = 0.09f;

    private static final int SPOKES = 3;
    private static final float SPOKE_INNER = 0.78f;
    private static final float SPOKE_OUTER = 1.16f;
    private static final double SPOKE_SPAN = 0.62;
    private static final long SPIN_PERIOD_MS = 5200L;

    private static final int EMBERS = 14;
    private static final int EMBER_STRIDE = 6;
    private static final long EMBER_MS = 1500L;
    private static final float EMBER_RISE = 1.4f;
    private static final float EMBER_LENGTH = 0.26f;
    private static final float EMBER_ALPHA = 0.85f;
    private static final float EMBER_WIDTH = 0.7f;
    private static final float EMBER_RING_MIN = 0.25f;
    private static final float EMBER_RING_SPAN = 0.75f;
    private static final float EMBER_RING_SEED = 7.13f;
    private static final float EMBER_SEED_STEP = 0.137f;
    private static final float GOLDEN_ANGLE = 2.3999632f;

    /** Where the colour ramp reaches the hot tone, and where it starts pulling towards white. */
    private static final float HOT_KNEE = 0.5f;
    private static final float WHITE_KNEE = 0.55f;
    private static final float WHITE_MIX = 0.85f;

    private static final double TWO_PI = Math.PI * 2.0;
    private static final float TWO_PI_F = 6.2831855f;

    /** Rebuilt every frame; kept so the ember crown allocates nothing. */
    private static final float[] EMBER_POINTS = new float[EMBERS * EMBER_STRIDE];

    /**
     * Draws the hottest site, if any. Only one is drawn: the pool is the mark of where the aura is working,
     * and several of them at once would read as clutter rather than as a focus.
     */
    static void draw(Render3DEvent event, RenderSettings settings, SoftStyle style, long nowMs) {
        if (!settings.forge.getValue()) {
            return;
        }
        float heat = AuraForgeLedger.peakHeat();
        if (heat < AuraForgeLedger.DEAD_HEAT) {
            return;
        }
        java.util.List<AuraForgeLedger.Site> sites = AuraForgeLedger.sites(nowMs);
        if (sites.isEmpty()) {
            return;
        }
        AuraForgeLedger.Site site = sites.get(0);
        Vec3 anchor = new Vec3(Math.floor(site.center().x) + HALF_BLOCK,
                Math.floor(site.center().y) - HALF_BLOCK + GROUND_LIFT,
                Math.floor(site.center().z) + HALF_BLOCK);
        drawCore(event, anchor, site.heat(), settings, style, nowMs);
    }

    /** The pool, its rim, the spokes and the embers, all additive and all scaled by the heat. */
    private static void drawCore(Render3DEvent event, Vec3 anchor, float heat, RenderSettings settings,
                                 SoftStyle style, long nowMs) {
        float radius = settings.forgeRadius.getValue().floatValue()
                * (POOL_BASE + POOL_GAIN * heat) * pulseOf(heat, nowMs);
        if (radius <= 0f) {
            return;
        }
        int ramp = rampColor(heat);
        int bright = fade(ramp, heat);
        int edge = fade(ramp, heat * RIM_RATIO);

        // A solid centre is what turns the ring into a pool: at an inner radius of zero the innermost rail
        // would otherwise be transparent and the forge would have a hole in the middle.
        SoftGeometry.drawSoftRing(event, anchor, radius, 0f, edge, bright, style, true, true);
        SoftGeometry.drawSoftOutlineRing(event, anchor, radius, bright, style, true);
        drawSpokes(event, anchor, radius, edge, style, nowMs);
        drawEmbers(event, anchor, radius, fade(bright, EMBER_ALPHA), style, nowMs);
    }

    /**
     * Short arcs just outside the pool, turning once every spin period. The angle wraps at a full turn, where
     * a full turn and zero are the same direction, so the rotation never jumps.
     */
    private static void drawSpokes(Render3DEvent event, Vec3 anchor, float radius, int color,
                                   SoftStyle style, long nowMs) {
        double spin = (nowMs % SPIN_PERIOD_MS) / (double) SPIN_PERIOD_MS * TWO_PI;
        float outer = radius * SPOKE_OUTER;
        float inner = radius * SPOKE_INNER;
        int tail = color & 0x00FFFFFF;
        for (int index = 0; index < SPOKES; index++) {
            double from = spin + index * TWO_PI / SPOKES;
            SoftGeometry.drawSoftArc(event, anchor, outer, inner, from, from + SPOKE_SPAN,
                    color, tail, style, false, true);
        }
    }

    /**
     * Embers climbing out of the pool, as ONE mesh over the shared point buffer — the crown is rebuilt every
     * frame and must not allocate.
     *
     * <p>Each ember's phase is its own constant offset plus the shared cycle, so they do not march in step,
     * and its length is a sine over that phase: zero at the bottom of the climb and zero again at the top,
     * which is what makes a looping ember grow out of the pool and dissolve rather than blink in and out.
     */
    private static void drawEmbers(Render3DEvent event, Vec3 anchor, float radius, int color,
                                   SoftStyle style, long nowMs) {
        if ((color >>> 24) == 0) {
            return;
        }
        float cycle = (nowMs % EMBER_MS) / (float) EMBER_MS;
        for (int index = 0; index < EMBERS; index++) {
            float seed = index * EMBER_SEED_STEP;
            float phase = fract(cycle + seed);
            float angle = index * GOLDEN_ANGLE;
            float ring = radius * (EMBER_RING_MIN + EMBER_RING_SPAN * fract(seed * EMBER_RING_SEED));
            int base = index * EMBER_STRIDE;
            EMBER_POINTS[base] = (float) Math.cos(angle) * ring;
            EMBER_POINTS[base + 1] = phase * radius * EMBER_RISE;
            EMBER_POINTS[base + 2] = (float) Math.sin(angle) * ring;
            EMBER_POINTS[base + 3] = EMBER_POINTS[base];
            EMBER_POINTS[base + 4] = EMBER_POINTS[base + 1]
                    + (float) Math.sin(phase * Math.PI) * EMBER_LENGTH;
            EMBER_POINTS[base + 5] = EMBER_POINTS[base + 2];
        }
        SoftGeometry.drawSoftSegments(event, anchor, EMBER_POINTS, EMBERS, color, style, EMBER_WIDTH, true);
    }

    /**
     * Cold, then hot, then white-hot.
     *
     * <p>The first half of the range crosses from the cold tone to the hot one, and past the white knee the
     * result is pulled towards white, so a site being hammered without pause goes white-hot. The alpha of the
     * warm colour survives the white mix: under additive blending alpha IS brightness, and letting it run to
     * white's full opacity would blow the pool out the moment it got hot.
     */
    private static int rampColor(float heat) {
        int warm = lerpColor(CrystalColors.FORGE_COLD, CrystalColors.FORGE_HOT, Math.min(heat / HOT_KNEE, 1f));
        float white = Math.clamp((heat - WHITE_KNEE) / (1f - WHITE_KNEE), 0f, 1f);
        int mixed = lerpColor(warm, 0xFFFFFFFF, white * WHITE_MIX);
        return (warm & 0xFF000000) | (mixed & 0x00FFFFFF);
    }

    /**
     * The breath of the pool: a fixed-rate sine whose amplitude is the heat, so the harder the site is worked
     * the more it heaves. Tying the pulse to the detonation rate through the heat rather than through the rate
     * of the sine keeps its phase from jumping every time the rate changes.
     */
    private static float pulseOf(float heat, long nowMs) {
        float phase = (nowMs % PULSE_MS) / (float) PULSE_MS;
        return 1f + PULSE_AMOUNT * heat * (float) Math.sin(phase * TWO_PI_F);
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    /** Scales only the alpha, clamped. */
    private static int fade(int argb, float factor) {
        int alpha = Math.clamp(Math.round(((argb >>> 24) & 0xFF) * factor), 0, 255);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Linear blend of two colours, alpha included. */
    private static int lerpColor(int from, int to, float t) {
        float clamped = Math.clamp(t, 0f, 1f);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            result |= Math.round(a + (b - a) * clamped) << shift;
        }
        return result;
    }
}
