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

/**
 * The easing shapes of the overlay.
 *
 * <p>{@link #SPRING} and {@link #SOFT_SPRING} are cubic beziers {@code (0.45, 1.45, 0.49, 1.15)} and
 * {@code (0.27, 1.09, 0.49, 1.06)}. Both control points sit above {@code 1.0}, i.e. the curve deliberately
 * overshoots and settles back — that over-unity is what makes a box "arrive" rather than slide to a stop.
 *
 * <p>This is intentionally a private enum of the overlay rather than an extension of any shared easing list:
 * adding constants to a list other modules expose as a setting would silently change their option lists.
 */
public enum AuraEase {
    /** No shaping; constant rate. */
    LINEAR,

    /** {@code 1 - (1 - t)^3}: fast start, gentle stop. The default for fades. */
    OUT_CUBIC,

    /** Symmetric cubic acceleration and deceleration; used for cross-fades. */
    IN_OUT_CUBIC,

    /** The springy bezier {@code (0.45, 1.45, 0.49, 1.15)} — the strongest overshoot. */
    SPRING,

    /** The gentler bezier {@code (0.27, 1.09, 0.49, 1.06)}. */
    SOFT_SPRING,

    /** Closed-form back-out with the classic {@code 1.70158} overshoot constant. */
    OUT_BACK;

    private static final float BACK_OVERSHOOT = 1.70158f;
    private static final float HALF = 0.5f;

    /** Maps a {@code 0..1} progress onto the eased {@code 0..1} (or slightly beyond, for the overshoots). */
    public float ease(float progress) {
        float t = Math.clamp(progress, 0f, 1f);
        return switch (this) {
            case LINEAR -> t;
            case OUT_CUBIC -> outCubic(t);
            case IN_OUT_CUBIC -> inOutCubic(t);
            case SPRING -> Curves.SPRING.solve(t);
            case SOFT_SPRING -> Curves.SOFT_SPRING.solve(t);
            case OUT_BACK -> outBack(t);
        };
    }

    private static float outCubic(float t) {
        float rest = 1f - t;
        return 1f - rest * rest * rest;
    }

    private static float inOutCubic(float t) {
        if (t < HALF) {
            return 4f * t * t * t;
        }
        float rest = -2f * t + 2f;
        return 1f - rest * rest * rest / 2f;
    }

    private static float outBack(float t) {
        float u = t - 1f;
        return 1f + (BACK_OVERSHOOT + 1f) * u * u * u + BACK_OVERSHOOT * u * u;
    }

    /** Holder so the two bezier solvers are built after the constants, not during enum initialisation. */
    private static final class Curves {
        private static final UnitBezier SPRING = new UnitBezier(0.45f, 1.45f, 0.49f, 1.15f);
        private static final UnitBezier SOFT_SPRING = new UnitBezier(0.27f, 1.09f, 0.49f, 1.06f);

        private Curves() {
        }
    }

    /**
     * A unit cubic bezier {@code (0,0) -> (x1,y1) -> (x2,y2) -> (1,1)} evaluated the standard way:
     * Newton-Raphson on the x polynomial to recover the curve parameter for a given time, then the y polynomial
     * at that parameter.
     *
     * <p>Eight iterations, an absolute error tolerance of {@code 1e-5}, a {@code 1e-6} floor on the slope to
     * avoid dividing by a flat spot, and the parameter clamped into {@code 0..1} after every step. Only the two
     * control points may leave {@code 0..1}, which is what produces the overshoot.
     */
    private record UnitBezier(float x1, float y1, float x2, float y2) {

        private static final int NEWTON_STEPS = 8;
        private static final float TOLERANCE = 1e-5f;
        private static final float SLOPE_EPSILON = 1e-6f;

        /** The eased value for time {@code x}; may exceed {@code 1} when the control points do. */
        float solve(float x) {
            return polynomial(parameterFor(x), y1, y2);
        }

        private float parameterFor(float x) {
            float t = x;
            for (int step = 0; step < NEWTON_STEPS; step++) {
                float error = polynomial(t, x1, x2) - x;
                float slope = derivative(t, x1, x2);
                if (Math.abs(error) < TOLERANCE || Math.abs(slope) < SLOPE_EPSILON) {
                    return t;
                }
                t = Math.clamp(t - error / slope, 0f, 1f);
            }
            return t;
        }

        /** {@code 3(1-t)^2 t a + 3(1-t) t^2 b + t^3}, one axis with endpoints {@code 0} and {@code 1}. */
        private static float polynomial(float t, float a, float b) {
            float rest = 1f - t;
            return 3f * rest * rest * t * a + 3f * rest * t * t * b + t * t * t;
        }

        private static float derivative(float t, float a, float b) {
            return 3f * ((1f - t) * (1f - 3f * t) * a + (2f * t - 3f * t * t) * b) + 3f * t * t;
        }
    }
}
