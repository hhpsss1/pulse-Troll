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

import cc.aerial.client.render.TextRenderUtil;
import cc.aerial.client.render.font.AerialFont;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The overlay text path of the combat auras: one measured, drawn line at a requested PIXEL height.
 *
 * <p>The face is resolved lazily and cached, because building one rasterises an atlas and needs the render
 * system to exist — asking for it during class initialisation or from a tick handler would throw. A face that
 * cannot be built is cached as absent and every entry point below degrades to drawing nothing, which is the
 * same guarded behaviour LiquidBounce's version has around its own glyph manager.
 *
 * <p>LiquidBounce needs a second, differently-measuring text path as a fallback and warns that mixing a width
 * from one with a height from the other draws crooked text. Aerial has exactly one text stack, so that hazard
 * is gone and the measuring helpers are thin wrappers — they are kept anyway so a call site still reads as one
 * vocabulary and the font stays a parameter rather than a lookup.
 */
public final class AuraText {

    private AuraText() {
    }

    /** The face the damage figures are drawn in. */
    private static final String FONT_RESOURCE = "ProductSansMedium.ttf";

    /** Resolved face, and whether resolution has already been attempted and failed. */
    @Nullable
    private static AerialFont cached;
    private static boolean failed;

    /**
     * The shared face, or {@code null} while it cannot be built.
     *
     * <p>Only safe to call from a render handler: the atlas is uploaded to the GPU on creation.
     */
    @Nullable
    public static AerialFont font() {
        if (cached != null || failed) {
            return cached;
        }
        try {
            cached = AerialFont.createFromResource(FONT_RESOURCE);
        } catch (RuntimeException exception) {
            failed = true;
        }
        return cached;
    }

    /** Advanced width of {@code text} at pixel height {@code size}. */
    public static float width(@Nullable AerialFont face, CharSequence text, float size) {
        return face == null ? 0f : face.stringWidth(text, size);
    }

    /** Height of one line at pixel height {@code size}. */
    public static float lineHeight(@Nullable AerialFont face, float size) {
        return face == null ? 0f : face.height(size);
    }

    /** Draws {@code text} with its top-left at ({@code drawX}, {@code drawY}) and returns the pen X after it. */
    public static float draw(@Nullable AerialFont face, GuiGraphicsExtractor ctx, CharSequence text,
                             float drawX, float drawY, float size, int argb) {
        if (face == null) {
            return drawX;
        }
        return drawX + TextRenderUtil.drawString(ctx, face, text, drawX, drawY, size, argb);
    }

    /** One decimal, locale-independent: the format the auras print damage figures in. */
    public static String format(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
