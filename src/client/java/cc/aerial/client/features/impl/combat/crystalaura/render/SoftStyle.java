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

import cc.aerial.client.features.impl.combat.crystalaura.config.RenderSettings;

/**
 * Every knob of the soft look in one place, so a call site reads as geometry and not as a parameter list.
 *
 * <p>The module's render settings map onto this one-to-one; {@link #of} is the single place that reads them, so
 * a whole frame is guaranteed to draw with one consistent look.
 *
 * @param enabled {@code false} restores the ORIGINAL hard-edged look: every entry point of {@link SoftGeometry}
 *     falls straight through to the plain {@code Render3DUtility} helper it replaced, so the toggle is a true
 *     A/B and not a second, dimmer version of the same drawing.
 * @param outlineWidth World-space width of an outline ribbon, in blocks. This is the FULL width including both
 *     feather zones, so {@code 0.02} is a two-centimetre line that keeps its thickness at any distance instead
 *     of collapsing to the one-pixel wire a {@code lines} pipeline produces.
 * @param feather Fraction of a band's half-width spent fading, {@code 0..1}. {@code 0} gives a solid ribbon
 *     with hard sides (thick, but still aliased), {@code 1} gives a pure triangular falloff with no solid core
 *     at all, and the default {@code 0.5} keeps a solid centre with a soft shoulder on each side — that
 *     shoulder is what removes the hard border.
 * @param fresnelPower Exponent of the grazing-angle term, {@code f^fresnelPower}. Higher pulls the bright rim
 *     tighter against the silhouette (glassier, more volumetric); {@code 0} disables the effect and every face
 *     is painted flat.
 * @param faceMin Alpha floor of a face seen exactly head-on, {@code 0..1}. This is the single term that makes a
 *     filled box read as a VOLUME rather than a decal: at {@code 0.15} the face you look straight at almost
 *     vanishes while the faces you graze stay bright, which is what the eye reads as depth.
 * @param gradientTop Alpha multiplier at the TOP edge of a face relative to its bottom edge. Below {@code 1}
 *     fades upwards (the default, so nothing is one flat tone), {@code 1} is a constant tone, above {@code 1}
 *     fades downwards instead.
 * @param fadeStart Distance in blocks at which the whole drawing starts losing alpha.
 * @param fadeEnd Distance in blocks at which it is fully gone. The interpolation is smoothstep, so nothing pops
 *     as the camera walks across either threshold.
 * @param alphaGain Multiplier applied to EVERY resolved alpha, on top of the distance fade. {@code 1} is the
 *     honest look; above that the same drawing is emitted brighter without any of its colours being changed,
 *     which is what a glow pass needs: a halo derived from the colours routed into it gets a nearly black
 *     source when a fill at alpha 40/255 has already been scaled down to {@link #faceMin} by the Fresnel term.
 *     The world pass keeps {@code 1}; the pass routed into the halo uses the module's glow gain. Saturates at
 *     full alpha rather than wrapping.
 */
public record SoftStyle(
        boolean enabled,
        float outlineWidth,
        float feather,
        float fresnelPower,
        float faceMin,
        float gradientTop,
        float fadeStart,
        float fadeEnd,
        float alphaGain) {

    /**
     * The frame's style at a given {@code alphaGain}, built once per pass and threaded through every draw, so
     * the settings are read exactly once per frame. Only the gain ever differs between the world pass and the
     * glow pass.
     */
    public static SoftStyle of(RenderSettings settings, float alphaGain) {
        return new SoftStyle(
                settings.soft.getValue(),
                settings.outlineWidth.getValue().floatValue(),
                settings.feather.getValue().floatValue(),
                settings.fresnel.getValue().floatValue(),
                settings.faceMin.getValue().floatValue(),
                settings.gradient.getValue().floatValue(),
                settings.fadeStart.getValue().floatValue(),
                settings.fadeEnd.getValue().floatValue(),
                alphaGain);
    }
}
