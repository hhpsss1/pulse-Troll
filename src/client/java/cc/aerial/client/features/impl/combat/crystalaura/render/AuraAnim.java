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
 * A wall-clock tween of a single float, the animation vocabulary of the aura's world overlay.
 *
 * <p>The object is built from {@code (duration, initial value, easing)}; assigning a NEW target records the
 * value shown right now as the start of a fresh tween and stamps the wall clock; reading the value interpolates
 * {@code start + (target - start) * ease(elapsed / duration)} and snaps to the target once
 * {@code elapsed >= duration}.
 *
 * <p>Nothing here is tick- or frame-driven: the value is a pure function of {@link System#currentTimeMillis()},
 * so the animation looks the same at 30 and at 300 fps and costs nothing while it is settled.
 */
public final class AuraAnim {

    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 3f;

    /**
     * Global tween-rate divisor shared by every animation of the overlay, written once per frame from the
     * module's animation-speed setting. {@code 2.0} makes every tween take half as long, {@code 0.5} twice as
     * long.
     *
     * <p>It is applied at read time rather than baked into the duration, so moving the slider is visible on
     * animations that are already running.
     */
    private static float speed = 1f;

    /** Nominal tween length, before the shared {@link #speed} divisor. */
    private final long durationMs;

    /** The shape of the interpolation. */
    private final AuraEase easing;

    /** Value the current tween started from, and the value it walks towards. */
    private float origin;
    private float goal;

    /** Wall clock of the last retarget; {@code 0} means "settled since forever", so a fresh instance rests. */
    private long startedAt;

    public AuraAnim(long durationMs, float initial, AuraEase easing) {
        this.durationMs = durationMs;
        this.easing = easing;
        this.origin = initial;
        this.goal = initial;
    }

    /** Sets the shared tween-rate divisor, clamped to a sane band. */
    public static void setSpeed(float value) {
        speed = Math.clamp(value, MIN_SPEED, MAX_SPEED);
    }

    public static float speed() {
        return speed;
    }

    /**
     * A continuous {@code 0..1} sine of the wall clock: {@code 0.5 + 0.5 * sin(2 pi * now / period)}. Used for
     * the breathing and pulsing elements, which have no target to tween towards and just have to keep moving.
     */
    public static float pulse(long nowMs, long periodMs) {
        if (periodMs <= 0L) {
            return 1f;
        }
        double phase = (double) Math.floorMod(nowMs, periodMs) / periodMs;
        return 0.5f + 0.5f * (float) Math.sin(2.0 * Math.PI * phase);
    }

    /** The value the tween is walking towards. */
    public float target() {
        return goal;
    }

    /**
     * Retargets the tween. A value that differs from the current target restarts the tween from whatever is on
     * screen right now; the same value again is a no-op, so pushing the same target every frame does not freeze
     * the animation at its first sample.
     */
    public void setTarget(float value) {
        if (value == goal) {
            return;
        }
        long now = System.currentTimeMillis();
        origin = valueAt(now);
        goal = value;
        startedAt = now;
    }

    /** The interpolated value for this instant, clamped to the target once the tween is over. */
    public float value() {
        return valueAt(System.currentTimeMillis());
    }

    /** True once the tween reached its target and nothing is moving any more. */
    public boolean settled() {
        return System.currentTimeMillis() - startedAt >= scaledDuration();
    }

    /** Drops the running tween and places the value at {@code value} immediately (no interpolation). */
    public void snap(float value) {
        origin = value;
        goal = value;
        startedAt = 0L;
    }

    /** Effective length of one tween after the shared divisor; never zero, so the ratio stays finite. */
    private long scaledDuration() {
        return Math.max(1L, (long) (durationMs / speed));
    }

    private float valueAt(long now) {
        long duration = scaledDuration();
        long elapsed = now - startedAt;
        if (elapsed >= duration) {
            return goal;
        }
        if (elapsed <= 0L) {
            return origin;
        }
        return origin + (goal - origin) * easing.ease((float) elapsed / duration);
    }
}
