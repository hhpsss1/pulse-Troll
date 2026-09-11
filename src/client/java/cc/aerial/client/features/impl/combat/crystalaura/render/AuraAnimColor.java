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
 * The colour counterpart of {@link AuraAnim}: a {@code 0..1} tween driving a per-channel interpolation of two
 * packed ARGB colours, so a heat shift cross-fades instead of switching.
 *
 * <p>LiquidBounce tweens a {@code Color4b} here. Aerial has no colour type, so the channels are unpacked from
 * and repacked into an {@code int} — the same arithmetic, one allocation fewer.
 */
public final class AuraAnimColor {

    private final AuraAnim progress;
    private int origin;
    private int goal;

    public AuraAnimColor(long durationMs, int initial, AuraEase easing) {
        this.progress = new AuraAnim(durationMs, 1f, easing);
        this.origin = initial;
        this.goal = initial;
    }

    /** The colour being walked towards. */
    public int target() {
        return goal;
    }

    /** Retargets the cross-fade; a different colour restarts it from whatever is currently drawn. */
    public void setTarget(int argb) {
        if (argb == goal) {
            return;
        }
        origin = value();
        goal = argb;
        progress.snap(0f);
        progress.setTarget(1f);
    }

    /** The cross-faded colour for this instant. */
    public int value() {
        return lerp(origin, goal, progress.value());
    }

    /** True once the cross-fade finished. */
    public boolean settled() {
        return progress.settled();
    }

    /** Drops the running cross-fade and places the colour at {@code argb} immediately. */
    public void snap(int argb) {
        origin = argb;
        goal = argb;
        progress.snap(1f);
    }

    /** Per-channel interpolation with every channel clamped to {@code 0..255}. */
    public static int lerp(int from, int to, float t) {
        float ratio = Math.clamp(t, 0f, 1f);
        int a = channel(from, 24, to, ratio);
        int r = channel(from, 16, to, ratio);
        int g = channel(from, 8, to, ratio);
        int b = channel(from, 0, to, ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Truncating, like the {@code Color4b} arithmetic this replaces, and clamped to a byte. */
    private static int channel(int from, int shift, int to, float t) {
        int start = (from >>> shift) & 0xFF;
        int end = (to >>> shift) & 0xFF;
        return Math.clamp((int) (start + (end - start) * t), 0, 255);
    }
}
