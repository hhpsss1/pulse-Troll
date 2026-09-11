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

import cc.aerial.client.theme.ThemeManager;

/**
 * The aura palette, ported from LiquidBounce's {@code RenderSettings} {@code Color4b} defaults.
 *
 * <p>Aerial has no colour property type, so these are constants rather than settings. Where a
 * colour should follow the user's client theme instead of the fixed palette, call
 * {@link #accent(double, double)} — the two coordinates pick a point on the theme's gradient the
 * same way the rest of the UI does.</p>
 *
 * <p>ARGB, alpha in the high byte, matching {@code Render3DUtility.withAlpha}.</p>
 */
public final class CrystalColors {
    private CrystalColors() {
    }

    /** Placement box interior. */
    public static final int FILL = argb(0, 255, 0, 40);
    /** Placement box edges. */
    public static final int OUTLINE = argb(0, 255, 0, 180);

    /** Blast ring at minimum damage. */
    public static final int RING_COLD = argb(80, 170, 255, 170);
    /** Blast ring at lethal damage. */
    public static final int RING_HOT = argb(255, 90, 60, 200);

    /** Wedge marking the direction the victim is shielded from. */
    public static final int SHIELD = argb(255, 210, 60, 110);
    /** Expanding ring on detonation. */
    public static final int SHOCKWAVE = argb(255, 255, 255, 180);
    /** Marker on the crystal currently being attacked. */
    public static final int ATTACK = argb(255, 80, 80, 200);

    /** Forge haze at the cool end of the gradient. */
    public static final int FORGE_COLD = argb(80, 170, 255, 150);
    /** Forge haze at the hot end. */
    public static final int FORGE_HOT = argb(255, 140, 40, 210);

    /**
     * Self damage in the figures over the placement, dimmer than the target's own number so the two read as a
     * pair rather than as equals. A literal in LiquidBounce too, never a setting.
     */
    public static final int SELF_TEXT = argb(190, 190, 190, 200);

    /**
     * The colour every one-shot flash of the overlay pulls towards — the attacked crystal's outline, the blast
     * ring when a crystal of the projected kill is consumed. A literal in LiquidBounce too.
     */
    public static final int WHITE = argb(255, 255, 255, 255);

    /** Fully transparent; the resting value of a colour tween that has nothing to show yet. */
    public static final int TRANSPARENT = 0;

    /**
     * A point on the user's theme gradient, opaque.
     *
     * @param x horizontal position on the gradient, in the same space the UI uses
     * @param y vertical position on the gradient
     */
    public static int accent(double x, double y) {
        return 0xFF000000 | (ThemeManager.getTheme().getAccentRgb(x, y) & 0x00FFFFFF);
    }

    /** A point on the user's theme gradient at the given alpha, 0..255. */
    public static int accent(double x, double y, int alpha) {
        return ((alpha & 0xFF) << 24) | (ThemeManager.getTheme().getAccentRgb(x, y) & 0x00FFFFFF);
    }

    private static int argb(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
